package uz.tuit.lmsbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import uz.tuit.lmsbot.config.AppConfig;
import uz.tuit.lmsbot.model.ScheduleEvent;
import uz.tuit.lmsbot.model.StudyPlanSubject;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Кабинет преподавателя и тьютора на lms.tuit.uz.
 *
 * Списки LMS отдаёт в формате DataTables (…/data), формы принимает AJAX-запросами
 * с заголовком X-CSRF-TOKEN — ровно так, как это делают скрипты самого сайта
 * (crud.js, teacher.grading.js, teacher.activity.js). Поля и адреса сверены с ними.
 *
 * Разделы тьютора открываются только в режиме тьютора, поэтому все запросы идут
 * через LmsService.asTeacher / asTutor: режим сессии переключается под замком.
 */
public class TeacherService {

    private final LmsService lms;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<Long, String> csrf = new ConcurrentHashMap<>();

    public TeacherService(LmsService lms) {
        this.lms = lms;
    }

    private String base() {
        return lms.baseUrl();
    }

    // ─────────────────────────────────────────────
    //  MODELS
    // ─────────────────────────────────────────────

    /** Поток преподавателя в семестре («Мои предметы»). id — course_part_id, stream — «RVI201-2». */
    public record TCourse(int id, String subject, String type, int students, int rejected, String stream) {}

    /** Занятие календарного плана. lessonId есть, когда посещаемость уже отмечена. */
    public record TLesson(int number, String topic, String date, Integer lessonId, boolean marked,
                          String markedAt, boolean moved) {}

    public record TCalendar(String stream, String note, List<TLesson> lessons) {}

    /** present: true — «+», false — НБ, null — не отмечено. */
    public record AttStudent(int number, String name, String faculty, String direction, String group, Boolean present) {}

    public record TAttendance(String topic, String header, List<AttStudent> students) {}

    public record GColumn(Integer activityId, String name, String studentDeadline, String teacherDeadline, String max) {}

    /** Ячейка ведомости: submitted — студент загрузил работу (синяя кнопка на сайте). */
    public record GCell(Integer activityId, Integer studentId, String grade, boolean submitted) {}

    public record GRow(int number, String name, String group, Integer studentId, List<GCell> cells,
                       String total, String percent) {}

    /**
     * labels — подписи окна «Оценивание» (grade, comment, criterion, points, max, total, clear, save),
     * hint — подсказка LMS про дробные числа.
     */
    public record GradeSheet(String title, String note, List<GColumn> columns, List<GRow> rows,
                             Map<String, String> labels, String hint) {}

    public record FileLink(String name, String url) {}

    public record Criterion(String id, String name, String value, String max) {}

    /** Окно оценивания одной работы. editable=false — срок выставления прошёл. */
    public record GradeForm(boolean editable, boolean canClear, String grade, boolean module, String name,
                            String student, String maxPoint, List<FileLink> files, String fileText,
                            String deadline, String comment, List<Criterion> criteria) {}

    /** Итог отправки формы: ok либо сообщения об ошибках от LMS. */
    public record FormResult(boolean ok, List<String> errors, JsonNode body) {
        static FormResult fail(String msg) { return new FormResult(false, List.of(msg), null); }
    }

    /** Активность потока. status: 0 — в ожидании, 1 — подтверждено, 2 — отказано, 3 — на доработку. */
    public record TActivity(int id, String name, String deadline, String maxPoint, int status,
                            boolean hasActivities, boolean module, List<String[]> criteria,
                            String sampleUrl, String sampleName, String comment) {
        /** Удалить можно, пока студенты ничего не сдали и это не модуль — как на сайте. */
        public boolean deletable() { return !hasActivities; }
    }

    /** «Использовано 5 из 50, осталось 45» — баллы, которые ещё можно раздать активностям. */
    public record PointBudget(String used, String total, String left) {}

    /**
     * Активности потока вместе с тем, что показывает сама страница LMS: подпись остатка
     * баллов, подсказка о сроках, названия статусов и подписи полей формы — на языке
     * аккаунта LMS, без собственных переводов.
     */
    public record TActivities(List<TActivity> items, PointBudget budget, String budgetText, String note,
                              Map<Integer, String> statusLabels, Map<String, String> labels, List<String> fileHints) {}

    /** Подписи полей формы (label[for] → текст) — чтобы бот спрашивал теми же словами, что и сайт. */
    static Map<String, String> labels(Element scope) {
        Map<String, String> out = new LinkedHashMap<>();
        if (scope == null) return out;
        for (Element l : scope.select("label[for]")) {
            String t = l.text().replaceAll("\\s*:+\\s*$", "").trim();
            if (!t.isEmpty()) out.putIfAbsent(l.attr("for"), t);
        }
        return out;
    }

    private static final Pattern JS_LANG = Pattern.compile("var\\s+_lang\\s*=\\s*\\{(.*?)\\}", Pattern.DOTALL);
    private static final Pattern JS_PAIR = Pattern.compile("(\\d+)\\s*:\\s*'([^']*)'");

    static Map<Integer, String> statusLabels(String html) {
        Map<Integer, String> out = new LinkedHashMap<>();
        Matcher m = JS_LANG.matcher(html);
        if (!m.find()) return out;
        Matcher p = JS_PAIR.matcher(m.group(1));
        while (p.find()) out.put(Integer.parseInt(p.group(1)), p.group(2).trim());
        return out;
    }

    static TActivities activityPage(String html, List<TActivity> items) {
        Document d = Jsoup.parse(html);
        Element small = d.selectFirst(".js-max-ball-content small");
        Element note = d.selectFirst("#main-wrapper .alert-info");
        Element form = d.getElementById("formModal");
        Map<String, String> labels = labels(form);
        if (form != null) {
            Elements th = form.select(".js-criterions thead th");
            if (th.size() >= 2) {
                labels.put("criterion", th.get(0).text().trim());
                labels.put("points", th.get(1).text().trim());
            }
        }
        List<String> hints = new ArrayList<>();
        if (form != null) for (Element li : form.select(".alert-info li")) hints.add(li.text().trim());
        if (form != null) for (Element sm : form.select(".form-group small")) hints.add(sm.text().trim());
        return new TActivities(items, parseBudget(html), small != null ? small.text().trim() : null,
                note != null ? note.text().trim() : null, statusLabels(html), labels, hints);
    }

    /** Срок сдачи в календаре активностей; courseId — поток, ведомость которого открыть. */
    public record TDeadline(String subject, String activity, String stream, String start, long ts, Integer courseId) {}

    public record TFinal(int id, String subjects, String streams, String date, String from, String room, boolean hasStreams) {}

    public record TeacherInfo(String fullName, List<String[]> fields) {}

    public record Option(String id, String text) {}

    public record TGroup(int id, String name, String speciality) {}

    public record TStudent(int id, String fio, int attendance) {}

    /** Карточка студента у тьютора: userId нужен для запросов данных, studentId — для страниц. */
    public record TutorStudent(int studentId, Integer userId, String title, List<Option> semesters, String currentSemester) {}

    public record TStudentCourse(int id, Integer subjectId, String subject, List<String[]> teachers, int attendance, boolean failed) {}

    public record TAppeal(int id, String stream, String date, String pair, String theme, String students, String statusText, int status) {}

    public record Select2Item(String id, String text, Boolean present) {}

    public record TSubject(int id, String code, String subject, String language, String department) {}

    public record TResource(int id, String type, String name, String url, boolean pastDate) {}

    public record TTopic(int id, String number, String name, List<TResource> resources) {}

    // ─────────────────────────────────────────────
    //  HTTP
    // ─────────────────────────────────────────────

    private String getHtml(long uid, String path) throws Exception {
        String html = lms.getHtml(uid, base() + path, base() + "/dashboard/news");
        rememberCsrf(uid, html);
        return html;
    }

    private JsonNode getJson(long uid, String urlOrPath, String refererPath) throws Exception {
        String url = urlOrPath.startsWith("http") ? urlOrPath : base() + urlOrPath;
        String body = lms.getJson(uid, url, base() + refererPath);
        if (body == null || body.isBlank() || body.trim().startsWith("<"))
            throw new IllegalStateException("LMS вернула не JSON: " + url);
        return mapper.readTree(body);
    }

    private void rememberCsrf(long uid, String html) {
        if (html == null) return;
        Matcher m = CSRF_META.matcher(html);
        if (m.find()) csrf.put(uid, m.group(1));
    }

    private static final Pattern CSRF_META = Pattern.compile("<meta\\s+name=\"csrf-token\"\\s+content=\"([^\"]+)\"");

    private String csrfToken(long uid, boolean fresh) throws Exception {
        String t = fresh ? null : csrf.get(uid);
        if (t != null) return t;
        getHtml(uid, "/teacher/my-course");
        t = csrf.get(uid);
        if (t == null) throw new IllegalStateException("нет CSRF-токена");
        return t;
    }

    private record Resp(int code, String body) {}

    private Resp post(long uid, String path, RequestBody body, String refererPath) throws Exception {
        Resp r = postOnce(uid, path, body, refererPath, false);
        // 419 — токен сессии устарел (LMS перевыпустила его): берём свежий и повторяем.
        if (r.code() == 419) r = postOnce(uid, path, body, refererPath, true);
        return r;
    }

    private Resp postOnce(long uid, String path, RequestBody body, String refererPath, boolean freshToken) throws Exception {
        Request req = new Request.Builder()
                .url(base() + path)
                .post(body)
                .header("User-Agent", LmsService.userAgent())
                .header("Referer", base() + refererPath)
                .header("X-CSRF-TOKEN", csrfToken(uid, freshToken))
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .build();
        try (Response resp = lms.getClient(uid).newCall(req).execute()) {
            return new Resp(resp.code(), resp.body() != null ? resp.body().string() : "");
        }
    }

    /**
     * Ответ формы LMS: {"status":"error","errors":{поле:[…]}} или 422 с тем же — ошибка,
     * всё остальное 2xx — успех. Формат повторяет разбор в crud.js.
     */
    private FormResult formResult(Resp r) {
        JsonNode json = null;
        try { json = mapper.readTree(r.body()); } catch (Exception ignored) {}
        List<String> errors = new ArrayList<>();
        if (json != null && json.has("errors")) {
            json.get("errors").fields().forEachRemaining(e -> {
                if (e.getValue().isArray()) e.getValue().forEach(v -> errors.add(v.asText()));
                else errors.add(e.getValue().asText());
            });
        }
        if (json != null && errors.isEmpty() && "error".equals(json.path("status").asText())) {
            String msg = json.path("message").asText("");
            errors.add(msg.isBlank() ? "error" : msg);
        }
        boolean ok = r.code() >= 200 && r.code() < 300 && errors.isEmpty();
        if (!ok && errors.isEmpty()) errors.add(r.code() == 419 ? "session_expired" : "HTTP " + r.code());
        return new FormResult(ok, errors, json);
    }

    /** Адрес списка DataTables с параметрами, которые шлёт сам сайт. */
    static HttpUrl dataTable(String url, String[][] columns, int orderColumn, String orderDir, Map<String, String> extra) {
        HttpUrl.Builder b = Objects.requireNonNull(HttpUrl.parse(url)).newBuilder().addQueryParameter("draw", "1");
        for (int i = 0; i < columns.length; i++) {
            String p = "columns[" + i + "]";
            b.addQueryParameter(p + "[data]", columns[i][0])
                    .addQueryParameter(p + "[name]", columns[i][1])
                    .addQueryParameter(p + "[searchable]", "true")
                    .addQueryParameter(p + "[orderable]", "true")
                    .addQueryParameter(p + "[search][value]", "")
                    .addQueryParameter(p + "[search][regex]", "false");
        }
        b.addQueryParameter("order[0][column]", String.valueOf(orderColumn))
                .addQueryParameter("order[0][dir]", orderDir)
                .addQueryParameter("start", "0")
                .addQueryParameter("length", "500")
                .addQueryParameter("search[value]", "")
                .addQueryParameter("search[regex]", "false");
        if (extra != null) extra.forEach(b::addQueryParameter);
        return b.build();
    }

    private static String[][] cols(String... dataName) {
        String[][] out = new String[dataName.length][];
        for (int i = 0; i < dataName.length; i++) {
            String[] p = dataName[i].split(":", 2);
            out[i] = new String[]{p[0], p.length > 1 ? p[1] : p[0]};
        }
        return out;
    }

    private JsonNode dataRows(long uid, String path, String[][] columns, int orderCol, String dir,
                              Map<String, String> extra, String refererPath) throws Exception {
        JsonNode root = getJson(uid, dataTable(base() + path, columns, orderCol, dir, extra).toString(), refererPath);
        JsonNode data = root.get("data");
        return data != null && data.isArray() ? data : mapper.createArrayNode();
    }

    /** Текст без HTML: в ячейках DataTables иногда приходят теги. */
    static String plain(JsonNode n) {
        if (n == null || n.isNull()) return "";
        String s = n.asText("");
        return s.contains("<") ? Jsoup.parse(s).text().trim() : s.trim();
    }

    // ─────────────────────────────────────────────
    //  МОИ ПРЕДМЕТЫ
    // ─────────────────────────────────────────────

    public List<TCourse> courses(long uid, int semesterId) throws Exception {
        List<TCourse> out = lms.asTeacher(uid, () -> {
            JsonNode data = dataRows(uid, "/teacher/my-course/data",
                    cols("subject", "type", "students_number"), 0, "asc",
                    Map.of("semester_id", String.valueOf(semesterId)), "/teacher/my-course");
            List<TCourse> list = new ArrayList<>();
            for (JsonNode n : data) {
                String stream = null;
                for (String f : new String[]{"identificator", "stream", "course_part"}) {
                    String v = plain(n.get(f));
                    if (!v.isEmpty()) { stream = v; break; }
                }
                list.add(new TCourse(n.path("id").asInt(), plain(n.get("subject")), plain(n.get("type")),
                        n.path("students_number").asInt(0), n.path("rejected_number").asInt(0), stream));
            }
            return list;
        });
        // Имени потока в списке нет: у двух практик одного предмета различается только
        // оно. Берём его из формы «Исправление НБ», где потоки перечислены по id.
        if (out.stream().anyMatch(c -> c.stream() == null)) {
            Map<String, String> names = streamNames(uid);
            out.replaceAll(c -> c.stream() != null ? c : new TCourse(c.id(), c.subject(), c.type(), c.students(),
                    c.rejected(), names.get(String.valueOf(c.id()))));
        }
        out.sort(Comparator.comparing(TCourse::subject).thenComparing(TCourse::type)
                .thenComparing(c -> c.stream() == null ? "" : c.stream()).thenComparingInt(TCourse::id));
        return out;
    }

    private final Map<Long, Map<String, String>> streamNames = new ConcurrentHashMap<>();

    private final Set<Long> streamsLoaded = ConcurrentHashMap.newKeySet();

    private Map<String, String> streamNames(long uid) {
        Map<String, String> m = streamNames.computeIfAbsent(uid, k -> new ConcurrentHashMap<>());
        if (streamsLoaded.contains(uid)) return m;
        try {
            for (Option o : appealStreams(uid)) m.put(o.id(), o.text());
            streamsLoaded.add(uid);
        } catch (Exception e) {
            System.err.println("[TeacherService] streams " + uid + ": " + e.getMessage());
        }
        return m;
    }

    /** Имя потока стало известно из календарного плана — запоминаем для списков. */
    public void rememberStream(long uid, int courseId, String stream) {
        if (stream == null || stream.isBlank()) return;
        streamNames.computeIfAbsent(uid, k -> new ConcurrentHashMap<>()).putIfAbsent(String.valueOf(courseId), stream);
    }

    public void forget(long uid) {
        streamNames.remove(uid);
        streamsLoaded.remove(uid);
        scheduleMeta.remove(uid);
        csrf.remove(uid);
    }

    // ─────────────────────────────────────────────
    //  КАЛЕНДАРНЫЙ ПЛАН И ПОСЕЩАЕМОСТЬ
    // ─────────────────────────────────────────────

    public TCalendar calendar(long uid, int courseId) throws Exception {
        return lms.asTeacher(uid, () -> parseCalendar(getHtml(uid, "/teacher/calendar/show/" + courseId)));
    }

    private static final Pattern DMY = Pattern.compile("\\d{2}-\\d{2}-\\d{4}");
    private static final Pattern ATT_LINK = Pattern.compile("/teacher/attendance/show/\\d+/(\\d+)");

    static TCalendar parseCalendar(String html) {
        Document doc = Jsoup.parse(html);
        Element h = doc.selectFirst(".page-title h3, h3.breadcrumb-header");
        String title = h != null ? h.text().trim() : "";
        // «Календарный план - RVI201-2»
        String stream = title.contains(" - ") ? title.substring(title.lastIndexOf(" - ") + 3).trim() : title;
        Element note = doc.selectFirst(".page-title .alert, .page-inner .alert-info");
        List<TLesson> lessons = new ArrayList<>();
        lessons.addAll(parseLessons(doc.getElementById("simple-table1"), false));
        lessons.addAll(parseLessons(doc.getElementById("simple-table2"), true));
        return new TCalendar(stream, note != null ? note.text().trim() : null, lessons);
    }

    private static List<TLesson> parseLessons(Element table, boolean moved) {
        List<TLesson> out = new ArrayList<>();
        if (table == null) return out;
        for (Element tr : table.select("tbody tr")) {
            Elements td = tr.select("> td");
            if (td.size() < 3) continue;
            Integer num = firstInt(td.get(0).text());
            String topic = td.get(1).text().trim();
            Matcher dm = DMY.matcher(td.get(2).text());
            String date = dm.find() ? dm.group() : td.get(2).text().trim();
            Integer lessonId = null;
            boolean marked = false;
            String markedAt = null;
            if (td.size() > 3) {
                Element status = td.get(3);
                Element a = status.selectFirst("a[href*=/teacher/attendance/show/]");
                if (a != null) {
                    Matcher lm = ATT_LINK.matcher(a.attr("href"));
                    if (lm.find()) lessonId = Integer.parseInt(lm.group(1));
                }
                marked = status.selectFirst(".fa-check, .text-success") != null;
                String rest = status.ownText() + " " + status.select("> div").text();
                Matcher mm = DMY.matcher(rest);
                if (marked && mm.find()) markedAt = mm.group();
            }
            out.add(new TLesson(num != null ? num : out.size() + 1, topic, date, lessonId, marked, markedAt, moved));
        }
        return out;
    }

    public TAttendance attendance(long uid, int courseId, int lessonId) throws Exception {
        return lms.asTeacher(uid, () -> parseAttendance(getHtml(uid, "/teacher/attendance/show/" + courseId + "/" + lessonId)));
    }

    static TAttendance parseAttendance(String html) {
        Document doc = Jsoup.parse(html);
        Element h = doc.selectFirst("h3.breadcrumb-header");
        Element table = doc.selectFirst("form table.table, table.table");
        String header = "";
        List<AttStudent> out = new ArrayList<>();
        if (table != null) {
            Elements head = table.select("thead td, thead th");
            if (head.size() > 1) header = head.get(1).text().trim();
            for (Element tr : table.select("tbody tr")) {
                Elements td = tr.select("> td");
                if (td.size() < 2) continue;
                Element mark = td.get(td.size() - 1);
                String m = mark.text().trim();
                Boolean present;
                if (mark.selectFirst(".text-success") != null || m.equals("+")) present = true;
                else if (mark.selectFirst(".text-danger") != null || m.equalsIgnoreCase("нб") || m.equalsIgnoreCase("nb")) present = false;
                else {
                    Element box = mark.selectFirst("input[type=checkbox]");
                    present = box != null ? box.hasAttr("checked") : null;
                }
                Integer n = firstInt(td.get(0).text());
                out.add(new AttStudent(n != null ? n : out.size() + 1, td.get(1).text().trim(),
                        td.size() > 2 ? td.get(2).text().trim() : "",
                        td.size() > 3 ? td.get(3).text().trim() : "",
                        td.size() > 4 ? td.get(4).text().trim() : "", present));
            }
        }
        return new TAttendance(h != null ? h.text().trim() : "", header, out);
    }

    // ─────────────────────────────────────────────
    //  ВЕДОМОСТЬ И ОЦЕНИВАНИЕ
    // ─────────────────────────────────────────────

    public GradeSheet gradeSheet(long uid, int courseId) throws Exception {
        return lms.asTeacher(uid, () -> parseGradeSheet(getHtml(uid, "/teacher/my-course/grading/" + courseId)));
    }

    private static final Pattern DMY_HM = Pattern.compile("\\d{2}-\\d{2}-\\d{4}(?:\\s+\\d{1,2}:\\d{2})?");

    static GradeSheet parseGradeSheet(String html) {
        Document doc = Jsoup.parse(html);
        Element h = doc.selectFirst("h3.breadcrumb-header");
        Element note = doc.selectFirst(".page-inner > .alert, p.alert");
        Element table = doc.getElementById("simple-table1");
        List<GColumn> columns = new ArrayList<>();
        List<GRow> rows = new ArrayList<>();
        if (table != null) {
            Elements headRows = table.select("thead tr");
            List<Element> heads = headRows.isEmpty() ? List.of() : headRows.get(0).select(".activity-header");
            List<Element> maxes = headRows.size() > 1 ? headRows.get(1).select(".activity-header") : List.of();
            for (int i = 0; i < heads.size(); i++) {
                Element hd = heads.get(i);
                Element name = hd.selectFirst(".activity-name");
                String sd = null, td = null;
                for (Element b : hd.select(".badge-custom")) {
                    Matcher m = DMY_HM.matcher(b.text());
                    if (!m.find()) continue;
                    if (b.hasClass("teacher")) td = m.group();
                    else sd = m.group();
                }
                String max = i < maxes.size() ? maxes.get(i).text().trim() : "";
                columns.add(new GColumn(null, name != null ? name.text().trim() : hd.text().trim(), sd, td, max));
            }
            Integer[] ids = new Integer[columns.size()];
            for (Element tr : table.select("tbody tr")) {
                Elements tds = tr.select("> td");
                if (tds.size() < 2 + columns.size()) continue;
                String who = tds.get(1).text().trim();
                String name = who, group = "";
                int paren = who.lastIndexOf(" (");
                if (paren > 0 && who.endsWith(")")) {
                    name = who.substring(0, paren).trim();
                    String inside = who.substring(paren + 2, who.length() - 1);
                    group = inside.contains(" - ") ? inside.substring(inside.lastIndexOf(" - ") + 3).trim() : inside.trim();
                }
                Integer studentId = null;
                List<GCell> cells = new ArrayList<>();
                for (int i = 0; i < columns.size(); i++) {
                    Element cell = tds.get(2 + i);
                    Element a = cell.selectFirst(".js-grading");
                    Integer act = a != null ? intAttr(a, "data-activity") : null;
                    Integer st = a != null ? intAttr(a, "data-student") : null;
                    if (st != null) studentId = st;
                    if (act != null && ids[i] == null) ids[i] = act;
                    String grade = (a != null ? a.text() : cell.text()).replace('\u00a0', ' ').trim();
                    cells.add(new GCell(act, st, grade, a != null && a.hasClass("btn-primary")));
                }
                String total = tds.size() > 2 + columns.size() ? tds.get(2 + columns.size()).text().trim() : "";
                String pct = tds.size() > 3 + columns.size() ? tds.get(3 + columns.size()).text().trim() : "";
                Integer n = firstInt(tds.get(0).text());
                rows.add(new GRow(n != null ? n : rows.size() + 1, name, group, studentId, cells, total, pct));
            }
            for (int i = 0; i < columns.size(); i++) {
                GColumn c = columns.get(i);
                columns.set(i, new GColumn(ids[i], c.name(), c.studentDeadline(), c.teacherDeadline(), c.max()));
            }
        }
        Element modal = doc.getElementById("gradingModal");
        Map<String, String> labels = labels(modal);
        String hint = null;
        if (modal != null) {
            Elements th = modal.select(".js-criteria thead th");
            if (th.size() >= 3) {
                labels.put("criterion", th.get(0).text().trim());
                labels.put("points", th.get(1).text().trim());
                labels.put("max", th.get(2).text().trim());
            }
            Element total = modal.selectFirst(".js-criteria tfoot th");
            if (total != null) labels.put("total", total.text().replaceAll("\\s*:+\\s*$", "").trim());
            Element clear = modal.selectFirst(".js-grade-clear");
            if (clear != null) labels.put("clear", clear.text().trim());
            Element save = modal.selectFirst(".js-garde-save");
            if (save != null) labels.put("save", save.text().trim());
            Element title = modal.selectFirst(".modal-title");
            if (title != null) labels.put("title", title.text().trim());
            Element small = modal.selectFirst(".modal-body small");
            if (small != null) hint = small.text().replaceFirst("^\\*\\s*", "").trim();
        }
        return new GradeSheet(h != null ? h.text().trim() : "", note != null ? note.text().trim() : null, columns, rows,
                labels, hint);
    }

    public GradeForm gradeForm(long uid, int studentId, int activityId) throws Exception {
        return lms.asTeacher(uid, () -> parseGradeForm(getJson(uid,
                "/teacher/my-course/grading-form/" + studentId + "/" + activityId + "/", "/teacher/my-course")));
    }

    static GradeForm parseGradeForm(JsonNode n) {
        List<Criterion> criteria = new ArrayList<>();
        JsonNode cr = n.get("criteria");
        if (cr != null && cr.isArray()) {
            for (JsonNode c : cr) {
                criteria.add(new Criterion(c.path("id").asText(), plain(c.get("name")),
                        num(c.get("value")), num(c.get("grade"))));
            }
        }
        List<FileLink> files = new ArrayList<>();
        String fileHtml = n.path("file").asText("");
        String fileText = null;
        if (!fileHtml.isBlank()) {
            Document fd = Jsoup.parseBodyFragment(fileHtml);
            for (Element a : fd.select("a[href]")) {
                String href = a.attr("href").trim();
                if (href.isEmpty() || href.equals("#")) continue;
                String text = a.text().trim();
                files.add(new FileLink(text.isEmpty() ? "file" : text, href));
            }
            if (files.isEmpty()) fileText = fd.text().trim();
        }
        return new GradeForm(n.path("status").asBoolean(false), n.path("can_clear").asBoolean(false),
                num(n.get("grade")), n.path("is_module").asBoolean(false), plain(n.get("name")),
                plain(n.get("student")), num(n.get("max_point")), files, fileText,
                plain(n.get("deadline")), n.path("comment").isNull() ? "" : n.path("comment").asText(""), criteria);
    }

    /** Число из LMS без лишнего «.00»: 4.50 → 4.5, 5.00 → 5; пусто — "". */
    static String num(JsonNode n) {
        if (n == null || n.isNull()) return "";
        String s = n.asText("").trim();
        if (s.isEmpty()) return "";
        try {
            java.math.BigDecimal d = new java.math.BigDecimal(s.replace(',', '.')).stripTrailingZeros();
            return d.scale() < 0 ? d.setScale(0).toPlainString() : d.toPlainString();
        } catch (NumberFormatException e) {
            return s;
        }
    }

    /**
     * Сохраняет оценку. Поля те же, что сериализует форма #form на сайте: grades[id]
     * по каждому критерию, grade и comment. Для модуля оценка вводится одним числом,
     * для обычной активности итог LMS считает сама по критериям, а grade уходит прежним.
     */
    public FormResult setGrade(long uid, int studentId, int activityId, GradeForm form,
                               Map<String, String> criteriaValues, String moduleGrade, String comment) throws Exception {
        FormBody.Builder b = new FormBody.Builder();
        for (Criterion c : form.criteria()) {
            String v = criteriaValues != null ? criteriaValues.get(c.id()) : null;
            b.add("grades[" + c.id() + "]", v != null ? v : c.value());
        }
        // Без критериев поле «Оценка» — единственное, как у модуля.
        boolean single = form.module() || form.criteria().isEmpty();
        String grade = single && moduleGrade != null ? moduleGrade : form.grade();
        b.add("grade", grade == null ? "" : grade);
        b.add("comment", comment != null ? comment : form.comment());
        FormBody body = b.build();
        return lms.asTeacher(uid, () -> {
            Resp r = post(uid, "/teacher/my-course/grade-set/" + studentId + "/" + activityId, body,
                    "/teacher/my-course");
            return formResult(r);
        });
    }

    public FormResult clearGrade(long uid, int studentId, int activityId) throws Exception {
        return lms.asTeacher(uid, () -> formResult(post(uid, "/teacher/my-course/grade-clear/" + studentId + "/" + activityId,
                new FormBody.Builder().build(), "/teacher/my-course")));
    }

    // ─────────────────────────────────────────────
    //  АКТИВНОСТИ (задания потока)
    // ─────────────────────────────────────────────

    private static final Pattern BUDGET = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\D+?(\\d+(?:[.,]\\d+)?)\\D+?(\\d+(?:[.,]\\d+)?)");

    public TActivities activities(long uid, int courseId) throws Exception {
        return lms.asTeacher(uid, () -> {
            String page = getHtml(uid, "/teacher/activity/" + courseId);
            JsonNode data = dataRows(uid, "/teacher/activity/data",
                    cols("id", "name", "deadline", "max_point", "status"), 0, "desc",
                    Map.of("course_id", String.valueOf(courseId)), "/teacher/activity/" + courseId);
            List<TActivity> out = new ArrayList<>();
            for (JsonNode n : data) {
                List<String[]> crit = new ArrayList<>();
                JsonNode cr = n.get("criteria");
                if (cr != null && cr.isArray()) for (JsonNode c : cr) crit.add(new String[]{plain(c.get("name")), num(c.get("grade"))});
                out.add(new TActivity(n.path("id").asInt(), plain(n.get("name")), plain(n.get("deadline")),
                        num(n.get("max_point")), n.path("status").asInt(0),
                        n.path("has_activities").asBoolean(false) || n.path("has_activities").asInt(0) > 0,
                        n.path("module").asInt(0) > 0 || n.path("module").asBoolean(false), crit,
                        n.path("sample_url").isNull() ? null : n.path("sample_url").asText(null),
                        n.path("sample_name").isNull() ? null : n.path("sample_name").asText(null),
                        plain(n.get("last_comment"))));
            }
            out.sort(Comparator.comparingInt(TActivity::id));
            return activityPage(page, out);
        });
    }

    static PointBudget parseBudget(String html) {
        Element small = Jsoup.parse(html).selectFirst(".js-max-ball-content small");
        if (small == null) return null;
        Matcher m = BUDGET.matcher(small.text());
        return m.find() ? new PointBudget(m.group(1), m.group(2), m.group(3)) : null;
    }

    /**
     * Новая активность. Поля формы #form со страницы «Активности»: name, deadline
     * (дд-мм-гггг), max_point, критерии items[i][name]/items[i][grade] и файл инструкции.
     */
    public FormResult createActivity(long uid, int courseId, String name, String deadline, String maxPoint,
                                     List<String[]> criteria, File instruction, String instructionName) throws Exception {
        MultipartBody.Builder b = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("id", "")
                .addFormDataPart("course_part_id", String.valueOf(courseId))
                .addFormDataPart("name", name)
                .addFormDataPart("deadline", deadline);
        if (instruction != null) {
            b.addFormDataPart("file", instructionName, RequestBody.create(instruction, MediaType.parse(mimeOf(instructionName))));
        }
        b.addFormDataPart("max_point", maxPoint);
        for (int i = 0; i < criteria.size(); i++) {
            b.addFormDataPart("items[" + i + "][name]", criteria.get(i)[0]);
            b.addFormDataPart("items[" + i + "][grade]", criteria.get(i)[1]);
        }
        MultipartBody body = b.build();
        return lms.asTeacher(uid, () -> formResult(post(uid, "/teacher/activity/form", body, "/teacher/activity/" + courseId)));
    }

    public FormResult deleteActivity(long uid, int courseId, int activityId) throws Exception {
        return lms.asTeacher(uid, () -> formResult(post(uid, "/teacher/activity/delete",
                new FormBody.Builder().add("id", String.valueOf(activityId)).build(), "/teacher/activity/" + courseId)));
    }

    // ─────────────────────────────────────────────
    //  РАСПИСАНИЕ, СРОКИ, ИТОГОВЫЕ, ПРОФИЛЬ
    // ─────────────────────────────────────────────

    /** Недельная сетка преподавателя: тот же формат, что у студентов («(ауд.)\nПредмет-ПОТОК»). */
    public List<ScheduleEvent> schedule(long uid, int semesterId) throws Exception {
        return lms.asTeacher(uid, () -> {
            JsonNode root = getJson(uid, "/teacher/schedule/load/" + semesterId, "/teacher/schedule");
            return events(root.get("json"));
        });
    }

    /**
     * Что страница расписания LMS знает о парах: подписи «Нечетная/Четная неделя» из
     * легенды и длительность пары (defaultTimedEventDuration календаря).
     */
    public record ScheduleMeta(String oddWeek, String evenWeek, int lessonMinutes) {}

    private final Map<Long, ScheduleMeta> scheduleMeta = new ConcurrentHashMap<>();

    public ScheduleMeta scheduleMeta(long uid) throws Exception {
        ScheduleMeta cached = scheduleMeta.get(uid);
        if (cached != null) return cached;
        ScheduleMeta m = lms.asTeacher(uid, () -> parseScheduleMeta(getHtml(uid, "/teacher/schedule")));
        scheduleMeta.put(uid, m);
        return m;
    }

    private static final Pattern DURATION = Pattern.compile("defaultTimedEventDuration\\s*:\\s*'(\\d{1,2}):(\\d{2})'");

    static ScheduleMeta parseScheduleMeta(String html) {
        String odd = null, even = null;
        for (Element item : Jsoup.parse(html).select(".cal-legend-item")) {
            if (item.selectFirst(".is-odd") != null) odd = item.text().trim();
            else even = item.text().trim();
        }
        Matcher m = DURATION.matcher(html);
        int minutes = m.find() ? Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2)) : 0;
        return new ScheduleMeta(odd, even, minutes);
    }

    static List<ScheduleEvent> events(JsonNode arr) {
        List<ScheduleEvent> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode n : arr) {
            ScheduleEvent e = new ScheduleEvent();
            e.setTitle(n.path("title").asText(""));
            e.setStart(n.path("start").asText(""));
            e.setType(n.path("type").asInt(1));
            out.add(e);
        }
        return out;
    }

    public List<TDeadline> deadlines(long uid) throws Exception {
        return lms.asTeacher(uid, () -> parseDeadlines(getHtml(uid, "/teacher/deadlines")));
    }

    private static final Pattern CAL_CALL = Pattern.compile("initCalendar\\(\\s*(\\[.*?\\])\\s*\\);", Pattern.DOTALL);
    private static final Pattern GRADING_ID = Pattern.compile("/grading/(\\d+)");

    static List<TDeadline> parseDeadlines(String html) {
        List<TDeadline> out = new ArrayList<>();
        Matcher m = CAL_CALL.matcher(html);
        if (!m.find()) return out;
        try {
            for (JsonNode n : new ObjectMapper().readTree(m.group(1))) {
                String[] parts = n.path("title").asText("").split("\n");
                String start = n.path("start").asText("");
                long ts = uz.tuit.lmsbot.util.LmsDates.parseScheduleStart(start);
                Matcher g = GRADING_ID.matcher(n.path("url").asText(""));
                out.add(new TDeadline(parts.length > 0 ? parts[0].trim() : "", parts.length > 1 ? parts[1].trim() : "",
                        parts.length > 2 ? parts[2].trim() : "", start, ts, g.find() ? Integer.valueOf(g.group(1)) : null));
            }
        } catch (Exception e) {
            System.err.println("[TeacherService] deadlines: " + e.getMessage());
        }
        out.sort(Comparator.comparingLong(TDeadline::ts));
        return out;
    }

    /**
     * Срок сдачи из календаря LMS вместе с данными ведомости: сколько сдали, сколько
     * ждут оценки и до какого момента преподаватель может оценивать (срок из ведомости).
     */
    public record GradingItem(TDeadline deadline, int column, GColumn info, int submitted, int graded, int pending,
                              long studentTs, long teacherTs, GradeSheet sheet) {}

    private final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "teacher-lms");
        t.setDaemon(true);
        return t;
    });

    /** Сроки последних двух недель и будущие; ведомости потоков загружаются параллельно. */
    public List<GradingItem> grading(long uid) throws Exception {
        List<TDeadline> list = deadlines(uid);
        long from = System.currentTimeMillis() - 14L * 24 * 3600_000;
        Set<Integer> ids = new LinkedHashSet<>();
        for (TDeadline d : list) if (d.courseId() != null && d.ts() > from) ids.add(d.courseId());
        Map<Integer, java.util.concurrent.Future<GradeSheet>> futures = new LinkedHashMap<>();
        for (Integer id : ids) futures.put(id, pool.submit(() -> gradeSheet(uid, id)));
        Map<Integer, GradeSheet> sheets = new HashMap<>();
        for (Map.Entry<Integer, java.util.concurrent.Future<GradeSheet>> e : futures.entrySet()) {
            try {
                sheets.put(e.getKey(), e.getValue().get(60, java.util.concurrent.TimeUnit.SECONDS));
            } catch (Exception ex) {
                System.err.println("[TeacherService] sheet " + e.getKey() + ": " + ex.getMessage());
            }
        }
        List<GradingItem> out = new ArrayList<>();
        for (TDeadline d : list) {
            if (d.courseId() == null || !ids.contains(d.courseId())) continue;
            GradeSheet s = sheets.get(d.courseId());
            int col = -1;
            if (s != null) for (int k = 0; k < s.columns().size(); k++) if (s.columns().get(k).name().equals(d.activity())) col = k;
            int sub = 0, done = 0, pend = 0;
            GColumn info = null;
            if (col >= 0) {
                info = s.columns().get(col);
                for (GRow r : s.rows()) {
                    GCell c = r.cells().get(col);
                    boolean g = c.grade() != null && !c.grade().isBlank();
                    if (c.submitted()) sub++;
                    if (g) done++;
                    if (c.submitted() && !g) pend++;
                }
            }
            long tdl = info != null ? parseDmyHm(info.teacherDeadline()) : -1;
            out.add(new GradingItem(d, col, info, sub, done, pend, d.ts(), tdl, s));
        }
        return out;
    }

    private static final java.time.format.DateTimeFormatter DMY_HM_FMT = java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");

    /** «04-10-2026 23:59» → миллисекунды по Ташкенту; -1, если не разобрать. */
    public static long parseDmyHm(String s) {
        if (s == null || s.isBlank()) return -1;
        try {
            return java.time.LocalDateTime.parse(s.trim(), DMY_HM_FMT).atZone(AppConfig.LMS_ZONE).toInstant().toEpochMilli();
        } catch (Exception e) {
            return uz.tuit.lmsbot.util.LmsDates.parseDeadline(s);
        }
    }

    public List<TFinal> finals(long uid, int semesterId) throws Exception {
        return lms.asTeacher(uid, () -> {
            Map<String, String> extra = semesterId > 0 ? Map.of("semester_id", String.valueOf(semesterId)) : Map.of();
            JsonNode data = dataRows(uid, "/teacher/final/data", cols("subjects", "streams", "date", "from", "room"),
                    2, "asc", extra, "/teacher/final");
            List<TFinal> out = new ArrayList<>();
            for (JsonNode n : data) {
                out.add(new TFinal(n.path("id").asInt(), plain(n.get("subjects")), plain(n.get("streams")),
                        plain(n.get("date")), plain(n.get("from")), plain(n.get("room")),
                        n.path("has_streams").asBoolean(false) || n.path("has_streams").asInt(0) > 0));
            }
            return out;
        });
    }

    public TeacherInfo info(long uid) throws Exception {
        return lms.asTeacher(uid, () -> parseInfo(getHtml(uid, "/teacher/information")));
    }

    static TeacherInfo parseInfo(String html) {
        Document doc = Jsoup.parse(html);
        List<String[]> fields = new ArrayList<>();
        for (Element p : doc.select(".page-inner .card p.m-b-xs")) {
            Element strong = p.selectFirst("strong");
            if (strong == null) continue;
            String label = strong.text().trim().replaceAll("\\s*:+\\s*$", "");
            String value = p.text().substring(Math.min(p.text().length(), strong.text().length())).trim();
            if (label.isEmpty() || value.isEmpty() || value.equals("---")) continue;
            fields.add(new String[]{label, value});
        }
        String name = fields.isEmpty() ? null : fields.get(0)[1];
        return new TeacherInfo(name, fields);
    }

    // ─────────────────────────────────────────────
    //  ИСПРАВЛЕНИЕ НБ (/report/attendance)
    // ─────────────────────────────────────────────

    /**
     * Страница «Исправление НБ»: потоки для заявления, инструкция из окна «Сформировать»
     * и подписи полей формы — всё в том виде, как их показывает LMS.
     */
    public record AppealPage(List<Option> streams, String instructions, Map<String, String> labels, String title) {}

    public AppealPage appealPage(long uid) throws Exception {
        return lms.asTeacher(uid, () -> parseAppealPage(getHtml(uid, "/report/attendance")));
    }

    static AppealPage parseAppealPage(String html) {
        Document d = Jsoup.parse(html);
        List<Option> streams = new ArrayList<>();
        for (Element o : d.select("select.js-streams option")) {
            String v = o.attr("value").trim();
            if (!v.isEmpty()) streams.add(new Option(v, o.text().trim()));
        }
        Element modal = d.getElementById("formModal");
        Element info = modal != null ? modal.selectFirst(".alert-info") : null;
        String instructions = null;
        if (info != null) {
            // Пункты инструкции разделены <br> — сохраняем переносы строк.
            info.select("br").after("\n");
            instructions = info.wholeText().replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll(" *\n *", "\n").replaceAll("\n{2,}", "\n").trim();
        }
        Element h = d.selectFirst("h3.breadcrumb-header");
        return new AppealPage(streams, instructions, labels(modal), h != null ? h.text().trim() : null);
    }

    public List<Option> appealStreams(long uid) throws Exception {
        return appealPage(uid).streams();
    }

    public List<TAppeal> appeals(long uid) throws Exception {
        return lms.asTeacher(uid, () -> {
            JsonNode data = dataRows(uid, "/report/attendance/data",
                    cols("id", "identificator", "date", "pair", "theme", "students", "status_format:status"),
                    0, "desc", null, "/report/attendance");
            List<TAppeal> out = new ArrayList<>();
            for (JsonNode n : data) {
                out.add(new TAppeal(n.path("id").asInt(), plain(n.get("identificator")), plain(n.get("date")),
                        plain(n.get("pair")), plain(n.get("theme")), plain(n.get("students")),
                        plain(n.get("status_format")), n.path("status").asInt(0)));
            }
            return out;
        });
    }

    public List<Select2Item> appealLessons(long uid, String coursePartId) throws Exception {
        return lms.asTeacher(uid, () -> {
            HttpUrl url = Objects.requireNonNull(HttpUrl.parse(base() + "/report/attendance/select2-calendars")).newBuilder()
                    .addQueryParameter("course_part_id", coursePartId)
                    .addQueryParameter("teacher_id", "").build();
            List<Select2Item> out = new ArrayList<>();
            for (JsonNode n : getJson(uid, url.toString(), "/report/attendance").path("items"))
                out.add(new Select2Item(n.path("id").asText(), plain(n.get("name")), null));
            return out;
        });
    }

    public List<Select2Item> appealStudents(long uid, String calendarId) throws Exception {
        return lms.asTeacher(uid, () -> {
            HttpUrl url = Objects.requireNonNull(HttpUrl.parse(base() + "/report/attendance/select2-students")).newBuilder()
                    .addQueryParameter("teacher_calendar_id", calendarId).build();
            List<Select2Item> out = new ArrayList<>();
            for (JsonNode n : getJson(uid, url.toString(), "/report/attendance").path("items")) {
                JsonNode st = n.get("status");
                Boolean present = st == null || st.isNull() ? null : st.asBoolean(false) || st.asInt(0) > 0;
                out.add(new Select2Item(n.path("id").asText(), plain(n.get("fio")), present));
            }
            return out;
        });
    }

    /** Заявление на исправление НБ: поток, занятие, номер пары и студент — как в форме сайта. */
    public FormResult createAppeal(long uid, String coursePartId, String calendarId, String pair, String studentId) throws Exception {
        MultipartBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("id", "")
                .addFormDataPart("course_part_id", coursePartId)
                .addFormDataPart("teacher_calendar_id", calendarId)
                .addFormDataPart("pair", pair)
                .addFormDataPart("students[]", studentId)
                .build();
        return lms.asTeacher(uid, () -> formResult(post(uid, "/report/attendance/form", body, "/report/attendance")));
    }

    public FormResult deleteAppeal(long uid, int id) throws Exception {
        return lms.asTeacher(uid, () -> formResult(post(uid, "/report/attendance/delete",
                new FormBody.Builder().add("id", String.valueOf(id)).build(), "/report/attendance")));
    }

    public String appealPdfUrl(int id) {
        return base() + "/report/attendance/download-pdf/" + id;
    }

    // ─────────────────────────────────────────────
    //  УЧЕБНЫЕ МАТЕРИАЛЫ (/teacher/subject-management)
    // ─────────────────────────────────────────────

    public List<Option> materialSemesters(long uid) throws Exception {
        return lms.asTeacher(uid, () -> {
            List<Option> out = new ArrayList<>();
            Document d = Jsoup.parse(getHtml(uid, "/teacher/subject-management"));
            Element sel = d.selectFirst(".js-filter-form select[name=semester_id]");
            if (sel != null) for (Element o : sel.select("option")) {
                String v = o.attr("value").trim();
                if (!v.isEmpty()) out.add(new Option(v, o.text().replaceAll("\\s+", " ").trim()));
            }
            return out;
        });
    }

    public List<TSubject> materialSubjects(long uid, int semesterId) throws Exception {
        return lms.asTeacher(uid, () -> {
            JsonNode data = dataRows(uid, "/teacher/subject-management/data",
                    cols("code", "subject", "department:department_id"), 0, "asc",
                    Map.of("semester_id", String.valueOf(semesterId)), "/teacher/subject-management");
            List<TSubject> out = new ArrayList<>();
            for (JsonNode n : data) {
                out.add(new TSubject(n.path("id").asInt(), plain(n.get("code")), plain(n.get("subject")),
                        plain(n.get("language")), plain(n.get("department"))));
            }
            return out;
        });
    }

    /**
     * Страница плана предмета: виды занятий (фильтр «Лекция / Практика»), виды материалов
     * из формы добавления (Литература, Видео, Презентация, Файл, URL…) и подписи полей.
     */
    public record MaterialPage(String title, List<Option> lessonTypes, List<Option> contentTypes, Map<String, String> labels) {}

    public MaterialPage materialPage(long uid, int subjectId, int semesterId, String language) throws Exception {
        return lms.asTeacher(uid, () -> parseMaterialPage(getHtml(uid,
                "/teacher/subject-management/" + subjectId + "/" + semesterId + "?lang=" + language)));
    }

    static MaterialPage parseMaterialPage(String html) {
        Document d = Jsoup.parse(html);
        Element h = d.selectFirst("h3.breadcrumb-header");
        return new MaterialPage(h != null ? h.text().trim() : null,
                options(d.selectFirst(".js-filter-form select[name=type]")),
                options(d.selectFirst("#form select[name=type]")),
                labels(d.getElementById("formModal")));
    }

    static List<Option> options(Element select) {
        List<Option> out = new ArrayList<>();
        if (select == null) return out;
        for (Element o : select.select("option")) {
            String v = o.attr("value").trim();
            if (!v.isEmpty()) out.add(new Option(v, o.text().replaceAll("\\s+", " ").trim()));
        }
        return out;
    }

    /** Темы плана предмета с прикреплёнными материалами. type — вид занятия из фильтра страницы. */
    public List<TTopic> materialTopics(long uid, int subjectId, int semesterId, String type, String language) throws Exception {
        return lms.asTeacher(uid, () -> {
            String page = "/teacher/subject-management/" + subjectId + "/" + semesterId + "?lang=" + language;
            Map<String, String> extra = new LinkedHashMap<>();
            extra.put("semester_id", String.valueOf(semesterId));
            extra.put("subject_id", String.valueOf(subjectId));
            extra.put("type", type);
            extra.put("language", language);
            JsonNode data = dataRows(uid, "/teacher/subject-management/" + subjectId + "/" + semesterId + "/data",
                    cols("number", "name"), 0, "asc", extra, page);
            List<TTopic> out = new ArrayList<>();
            for (JsonNode n : data) {
                List<TResource> res = new ArrayList<>();
                for (JsonNode r : n.path("resources")) {
                    String url = r.path("url_format").asText("");
                    if (url.isBlank() || "null".equals(url)) url = r.path("url").asText("");
                    res.add(new TResource(r.path("id").asInt(), r.path("type").asText(""), plain(r.get("name")), url,
                            r.path("is_past_date").asBoolean(false) || r.path("is_past_date").asInt(0) > 0));
                }
                out.add(new TTopic(n.path("id").asInt(), plain(n.get("number")), plain(n.get("name")), res));
            }
            return out;
        });
    }

    /**
     * Материал к теме. Форма сайта отправляет все поля разом (id, subject_calendar_id,
     * language, type, url, file, name, date, pair) — повторяем её: для ссылки заполнен url,
     * для файла — file и name.
     */
    public FormResult addMaterial(long uid, int subjectId, int semesterId, int topicId, String language, String type,
                                  String name, String url, File file, String fileName) throws Exception {
        MultipartBody.Builder b = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("id", "")
                .addFormDataPart("subject_calendar_id", String.valueOf(topicId))
                .addFormDataPart("language", language)
                .addFormDataPart("type", type)
                .addFormDataPart("url", url != null ? url : "");
        if (file != null) b.addFormDataPart("file", fileName, RequestBody.create(file, MediaType.parse(mimeOf(fileName))));
        b.addFormDataPart("name", name != null ? name : "")
                .addFormDataPart("date", "")
                .addFormDataPart("pair", "1");
        MultipartBody body = b.build();
        String referer = "/teacher/subject-management/" + subjectId + "/" + semesterId + "?lang=" + language;
        return lms.asTeacher(uid, () -> formResult(post(uid, "/teacher/subject-management/resource/form", body, referer)));
    }

    public FormResult deleteMaterial(long uid, int resourceId) throws Exception {
        return lms.asTeacher(uid, () -> formResult(post(uid, "/teacher/subject-management/resource/delete",
                new FormBody.Builder().add("id", String.valueOf(resourceId)).build(), "/teacher/subject-management")));
    }

    // ─────────────────────────────────────────────
    //  ТЬЮТОР
    // ─────────────────────────────────────────────

    public List<TGroup> tutorGroups(long uid) throws Exception {
        return lms.asTutor(uid, () -> {
            JsonNode data = dataRows(uid, "/tutor/groups/data", cols("id", "name", "speciality"), 0, "asc", null, "/tutor/groups");
            List<TGroup> out = new ArrayList<>();
            for (JsonNode n : data) out.add(new TGroup(n.path("id").asInt(), plain(n.get("name")), plain(n.get("speciality"))));
            return out;
        });
    }

    public List<TStudent> tutorStudents(long uid, int groupId) throws Exception {
        return lms.asTutor(uid, () -> {
            JsonNode data = dataRows(uid, "/tutor/group/students/data", cols("id", "image", "fio", "attendance"),
                    2, "asc", Map.of("group_id", String.valueOf(groupId)), "/tutor/group/students?group_id=" + groupId);
            List<TStudent> out = new ArrayList<>();
            for (JsonNode n : data) out.add(new TStudent(n.path("id").asInt(), plain(n.get("fio")), n.path("attendance").asInt(0)));
            out.sort(Comparator.comparing(TStudent::fio));
            return out;
        });
    }

    private static final Pattern USER_ID_VAR = Pattern.compile("var\\s+user_id\\s*=\\s*['\"]?(\\d+)");

    /** Страница «Предметы» студента: оттуда берём user_id и список семестров. */
    public TutorStudent tutorStudent(long uid, int studentId) throws Exception {
        return lms.asTutor(uid, () -> parseTutorStudent(studentId, getHtml(uid, "/tutor/group/student/" + studentId)));
    }

    static TutorStudent parseTutorStudent(int studentId, String html) {
        Document doc = Jsoup.parse(html);
        Matcher m = USER_ID_VAR.matcher(html);
        Integer userId = m.find() ? Integer.valueOf(m.group(1)) : null;
        Element h = doc.selectFirst("h3.breadcrumb-header");
        List<Option> sems = new ArrayList<>();
        String current = null;
        Element sel = doc.selectFirst("select.js-semester");
        if (sel != null) for (Element o : sel.select("option")) {
            String v = o.attr("value").trim();
            if (v.isEmpty()) continue;
            sems.add(new Option(v, o.text().replaceAll("\\s+", " ").trim()));
            if (o.hasAttr("selected")) current = v;
        }
        if (current == null && !sems.isEmpty()) current = sems.get(0).id();
        return new TutorStudent(studentId, userId, h != null ? h.text().trim() : "", sems, current);
    }

    public List<TStudentCourse> tutorStudentCourses(long uid, int userId, String semesterId) throws Exception {
        return lms.asTutor(uid, () -> {
            Map<String, String> extra = new LinkedHashMap<>();
            extra.put("semester_id", semesterId);
            extra.put("user_id", String.valueOf(userId));
            JsonNode data = dataRows(uid, "/tutor/group/student/course/data", cols("subject", "teachers"), 0, "asc",
                    extra, "/tutor/groups");
            List<TStudentCourse> out = new ArrayList<>();
            for (JsonNode n : data) {
                String[] names = n.path("teachers").asText("").split("###");
                String[] streams = n.path("streams").asText("").split("###");
                List<String[]> teachers = new ArrayList<>();
                for (int i = 0; i < names.length; i++) {
                    if (names[i].isBlank()) continue;
                    teachers.add(new String[]{i < streams.length ? streams[i].trim() : "", names[i].trim()});
                }
                out.add(new TStudentCourse(n.path("id").asInt(), n.path("subject_id").isMissingNode() ? null : n.path("subject_id").asInt(),
                        plain(n.get("subject")), teachers, n.path("attendance").asInt(0),
                        n.path("failed").asBoolean(false) || n.path("failed").asInt(0) > 0));
            }
            return out;
        });
    }

    public List<StudyPlanSubject> tutorStudyPlan(long uid, int studentId) throws Exception {
        return lms.asTutor(uid, () -> parseTutorPlan(getHtml(uid, "/tutor/study-plan/" + studentId)));
    }

    private static final Pattern ROMAN_SEM = Pattern.compile("^([IVXLC]+)\\s*-");

    static List<StudyPlanSubject> parseTutorPlan(String html) {
        List<StudyPlanSubject> out = new ArrayList<>();
        int semester = 0;
        for (Element tr : Jsoup.parse(html).select(".page-inner table tr")) {
            Elements td = tr.select("> td");
            if (td.isEmpty()) continue;
            Element head = td.first();
            if (head.hasAttr("rowspan")) {
                Matcher m = ROMAN_SEM.matcher(head.text().trim());
                if (m.find()) semester = romanToInt(m.group(1));
                else {
                    Integer n = firstInt(head.text());
                    if (n != null) semester = n;
                }
                continue;
            }
            if (td.size() < 3 || head.hasAttr("colspan")) continue;
            String name = td.get(0).text().trim();
            if (name.isEmpty()) continue;
            StudyPlanSubject s = new StudyPlanSubject();
            s.setName(name);
            Integer credits = firstInt(td.get(1).text());
            s.setCredits(credits != null ? credits : 0);
            s.setGrade(firstInt(td.get(2).text()));
            s.setSemester(semester);
            out.add(s);
        }
        return out;
    }

    private static final Pattern USER_JSON_ID = Pattern.compile("var\\s+user\\s*=\\s*\\{\\s*\"id\"\\s*:\\s*(\\d+)");

    /** Недельная сетка студента в семестре. */
    public List<ScheduleEvent> tutorStudentSchedule(long uid, int studentId, Integer userId, String semesterId) throws Exception {
        return lms.asTutor(uid, () -> {
            Integer id = userId;
            if (id == null) {
                Matcher m = USER_JSON_ID.matcher(getHtml(uid, "/tutor/group/schedule/" + studentId));
                if (!m.find()) return List.of();
                id = Integer.valueOf(m.group(1));
            }
            JsonNode root = getJson(uid, "/tutor/group/schedule/load/" + id + "/" + semesterId, "/tutor/group/schedule/" + studentId);
            return events(root.get("json"));
        });
    }

    // ─────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────

    public String absoluteUrl(String url) {
        if (url == null || url.isBlank()) return null;
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        return base() + (url.startsWith("/") ? "" : "/") + url;
    }

    static String mimeOf(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".doc")) return "application/msword";
        if (n.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (n.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        if (n.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (n.endsWith(".xls")) return "application/vnd.ms-excel";
        if (n.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".rar")) return "application/x-rar-compressed";
        if (n.endsWith(".mp4")) return "video/mp4";
        return "application/octet-stream";
    }

    private static Integer intAttr(Element e, String attr) {
        try { return Integer.valueOf(e.attr(attr).trim()); } catch (Exception ex) { return null; }
    }

    static Integer firstInt(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("\\d+").matcher(text);
        return m.find() ? Integer.valueOf(m.group()) : null;
    }

    static int romanToInt(String roman) {
        Map<Character, Integer> val = Map.of('I', 1, 'V', 5, 'X', 10, 'L', 50, 'C', 100);
        int total = 0;
        for (int i = 0; i < roman.length(); i++) {
            int cur = val.getOrDefault(roman.charAt(i), 0);
            int next = i + 1 < roman.length() ? val.getOrDefault(roman.charAt(i + 1), 0) : 0;
            total += cur < next ? -cur : cur;
        }
        return total;
    }

    /** Текущий момент по Ташкенту — все сроки LMS в этом поясе. */
    public static java.time.LocalDateTime now() {
        return java.time.LocalDateTime.now(AppConfig.LMS_ZONE);
    }
}
