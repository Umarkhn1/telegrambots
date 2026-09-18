package uz.tuit.lmsbot.web;

import uz.tuit.lmsbot.bot.LmsBot;
import uz.tuit.lmsbot.config.AppConfig;
import uz.tuit.lmsbot.model.*;
import uz.tuit.lmsbot.service.LmsService;
import uz.tuit.lmsbot.service.SemesterSchedule;
import uz.tuit.lmsbot.service.StudentRegistry;
import uz.tuit.lmsbot.util.LmsDates;

import java.io.File;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static uz.tuit.lmsbot.web.WebServer.ApiError;
import static uz.tuit.lmsbot.web.WebServer.Req;

/**
 * JSON API мини-приложения. Пользователь определяется по подписанной initData,
 * данные берутся из той же LMS-сессии, что использует бот.
 */
public class WebApi {

    private static final Set<String> LANGS = Set.of("ru", "uz_lat", "uz_cyr", "en");
    private static final Set<String> UPLOAD_EXT = Set.of("jpg", "jpeg", "png", "doc", "docx", "pdf", "ppt", "pptx", "zip", "rar");
    private static final long UPLOAD_LIMIT = 50L * 1024 * 1024;

    /** Сколько держать ответы LMS: переключение вкладок не должно каждый раз ходить на сайт. */
    private static final long CACHE_MS = 3L * 60 * 1000;
    /** Как часто перепроверять, что LMS-сессия ещё жива. */
    private static final long PROBE_MS = 10L * 60 * 1000;

    private final AppConfig config;
    private final LmsService lms;
    private final LmsBot bot;
    private final ExecutorService pool = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "webapi-lms");
        t.setDaemon(true);
        return t;
    });

    private record Cached(long ts, Object value) {}
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastProbe = new ConcurrentHashMap<>();

    private record FileTicket(long uid, String url, String name, long expires) {}
    private final Map<String, FileTicket> tickets = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public WebApi(AppConfig config, LmsService lms, LmsBot bot) {
        this.config = config;
        this.lms = lms;
        this.bot = bot;
    }

    /** Поднимает HTTP-сервер (health + API + фронтенд) на указанном порту. */
    public void start(int port) throws Exception {
        WebServer s = new WebServer(config.getBot().getToken());

        s.get("/api/me", this::me);

        s.post("/api/auth/lms", this::authLms);
        s.post("/api/auth/oneid", this::authOneId);
        s.post("/api/auth/oneid/confirm", this::authOneIdConfirm);
        s.post("/api/auth/mobile/send", this::authMobileSend);
        s.post("/api/auth/mobile/confirm", this::authMobileConfirm);
        s.post("/api/auth/logout", this::logout);

        s.get("/api/semesters", r -> { requireLogin(r); return semesters(r.uid()); });
        s.get("/api/courses", this::courses);
        s.get("/api/courses/(\\d+)/activities", this::activities);
        s.get("/api/courses/(\\d+)/attendance", this::attendance);
        s.get("/api/courses/(\\d+)/calendar", this::calendar);
        s.get("/api/schedule", this::schedule);
        s.get("/api/deadlines", this::deadlines);
        s.get("/api/study-plan", this::studyPlan);
        s.get("/api/finals", this::finals);
        s.get("/api/profile", this::profile);
        s.get("/api/profile/photo", this::profilePhoto);
        s.get("/api/contract", this::contract);
        s.post("/api/profile/password", this::changePassword);
        s.post("/api/settings", this::settings);

        s.post("/api/files/send", this::fileSend);
        s.post("/api/files/link", this::fileLink);
        s.publicGet("/api/files/get", this::fileGet);
        s.post("/api/upload", this::upload);

        s.get("/api/admin/students", this::adminStudents);

        s.start(port);
    }

    // ─────────────────────────────────────────────
    //  SESSION
    // ─────────────────────────────────────────────

    private Object me(Req r) {
        long uid = r.uid();
        boolean loggedIn = ensureSession(uid);
        if (loggedIn) {
            bot.appTouched(uid);
            bot.students().onSeen(uid, tgName(r), r.user.username());
        }

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", uid);
        user.put("firstName", r.user.firstName());
        user.put("lastName", r.user.lastName());
        user.put("username", r.user.username());
        user.put("photoUrl", r.user.photoUrl());
        user.put("languageCode", r.user.languageCode());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", user);
        out.put("lang", bot.appLang(uid));
        out.put("loggedIn", loggedIn);
        out.put("botUsername", config.getBot().getUsername());
        out.put("admin", AppConfig.isAdmin(uid));
        if (loggedIn) {
            out.put("semesters", semesters(uid));
            int cur = lms.getCurrentSemesterId(uid);
            out.put("currentSemesterId", cur > 0 ? cur : null);
        }
        return out;
    }

    /**
     * После перезапуска сервиса флаг входа живёт только в памяти, а cookie — на диске;
     * если сессия уже помечена живой, раз в 10 минут проверяем, не истекла ли она на LMS.
     */
    private boolean ensureSession(long uid) {
        if (!lms.isLoggedIn(uid)) {
            boolean ok = lms.restoreSession(uid);
            if (ok) lastProbe.put(uid, System.currentTimeMillis());
            return ok;
        }
        long now = System.currentTimeMillis();
        if (now - lastProbe.getOrDefault(uid, 0L) > PROBE_MS) {
            Boolean alive = lms.probeSession(uid);
            if (alive != null) lastProbe.put(uid, now);
            if (Boolean.FALSE.equals(alive)) {
                dropCache(uid);
                return false;
            }
        }
        return true;
    }

    private void requireLogin(Req r) {
        if (!lms.isLoggedIn(r.uid()) && !lms.restoreSession(r.uid())) throw new ApiError(403, "not_logged_in");
    }

    private static String tgName(Req r) {
        return (r.user.firstName() + " " + r.user.lastName()).trim();
    }

    private void onLoggedIn(Req r, String login) {
        long uid = r.uid();
        bot.students().onLogin(uid, login, tgName(r), r.user.username());
        dropCache(uid);
        lastProbe.put(uid, System.currentTimeMillis());
        bot.appLoggedIn(uid);
    }

    private Object authLms(Req r) throws Exception {
        String login = r.str("login").trim();
        String password = r.str("password");
        if (login.isEmpty() || password.isEmpty()) throw new ApiError(400, "empty");
        boolean ok = lms.login(r.uid(), login, password);
        if (ok) onLoggedIn(r, login);
        return Map.of("ok", ok);
    }

    private Object authOneId(Req r) throws Exception {
        String login = r.str("login").trim();
        String password = r.str("password");
        if (login.isEmpty() || password.isEmpty()) throw new ApiError(400, "empty");
        return oneIdResult(r, login, lms.oneIdLogin(r.uid(), login, password));
    }

    private Object authOneIdConfirm(Req r) throws Exception {
        return oneIdResult(r, r.str("login").trim(), lms.oneIdConfirm(r.uid(), r.str("login").trim(), r.str("code").trim()));
    }

    private Object authMobileSend(Req r) throws Exception {
        String phone = LmsService.normalizeUzPhone(r.str("phone"));
        if (phone == null) return Map.of("status", "ERROR", "reason", "phone");
        LmsService.OneIdResult res = lms.oneIdMobileSendSms(r.uid(), phone);
        if (res.status == LmsService.OneIdResult.Status.NEED_SMS) return Map.of("status", "NEED_SMS", "phone", phone);
        return error(res.message);
    }

    private Object authMobileConfirm(Req r) throws Exception {
        return oneIdResult(r, null, lms.oneIdMobileConfirm(r.uid(), r.str("code").trim()));
    }

    /** OK от OneID — это ещё не вход: надо обменять токен на сессию LMS. */
    private Object oneIdResult(Req r, String login, LmsService.OneIdResult res) {
        long uid = r.uid();
        switch (res.status) {
            case NEED_SMS: return Map.of("status", "NEED_SMS");
            case OK:
                if (lms.oneIdFinish(uid)) {
                    onLoggedIn(r, login);
                    return Map.of("status", "OK");
                }
                return Map.of("status", "ERROR", "reason", "link");
            default:
                return error(res.message);
        }
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ERROR");
        m.put("message", message);
        return m;
    }

    private Object logout(Req r) {
        lms.logout(r.uid());
        bot.appLoggedOut(r.uid());
        dropCache(r.uid());
        lastProbe.remove(r.uid());
        return null;
    }

    private Object settings(Req r) throws Exception {
        String lang = r.str("lang");
        if (!LANGS.contains(lang)) throw new ApiError(400, "bad_lang");
        bot.appSetLang(r.uid(), lang);
        return null;
    }

    // ─────────────────────────────────────────────
    //  DATA
    // ─────────────────────────────────────────────

    private List<Map<String, Object>> semesters(long uid) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AppConfig.SemesterConfig s : lms.getSemesters(uid)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("name", s.getName());
            m.put("year", s.getYear());
            out.add(m);
        }
        return out;
    }

    private int semester(Req r) {
        int sem = r.qInt("semester", -1);
        if (sem <= 0) sem = lms.getCurrentSemesterId(r.uid());
        if (sem <= 0) throw new ApiError(409, "no_semester");
        return sem;
    }

    private Object courses(Req r) {
        requireLogin(r);
        int sem = semester(r);
        bot.appSemester(r.uid(), sem);
        return cached(r, "courses:" + sem, () -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Course c : lms.getMyCourses(r.uid(), sem)) out.add(courseJson(c));
            return out;
        });
    }

    private static Map<String, Object> courseJson(Course c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("subject", c.getSubject());
        m.put("attendance", c.getAttendance());
        m.put("failed", c.isFailed());

        List<Map<String, String>> teachers = new ArrayList<>();
        String[] names = c.getTeachers() == null || c.getTeachers().isBlank() ? new String[0] : c.getTeachers().split("###");
        String[] streams = c.getStreams() == null || c.getStreams().isBlank() ? new String[0] : c.getStreams().split("###");
        for (int i = 0; i < names.length; i++) {
            if (names[i].isBlank()) continue;
            teachers.add(Map.of("name", names[i].trim(), "stream", i < streams.length ? streams[i].trim() : ""));
        }
        m.put("teachers", teachers);
        return m;
    }

    private Object activities(Req r) {
        requireLogin(r);
        int courseId = r.pathInt(1);
        return cached(r, "act:" + courseId, () -> {
            CourseSummary cs = lms.getActivities(r.uid(), courseId);
            if (cs == null) throw new ApiError(502, "lms_unavailable");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("earned", cs.getEarned());
            out.put("maxScore", cs.getMaxScore());
            out.put("progress", cs.getProgress());
            out.put("grade", cs.getGrade());
            List<Map<String, Object>> acts = new ArrayList<>();
            long now = System.currentTimeMillis();
            List<Activity> list = cs.getActivities() != null ? cs.getActivities() : List.of();
            for (int i = 0; i < list.size(); i++) acts.add(activityJson(list.get(i), i, now));
            out.put("activities", acts);
            return out;
        });
    }

    private static Map<String, Object> activityJson(Activity a, int index, long now) {
        long dl = LmsDates.parseDeadline(a.getDeadline());
        boolean uploaded = notBlank(a.getUploadedFileUrl());
        boolean graded = isGraded(a.getEarnedScore());
        boolean open = dl > 0 && dl > now;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", index);
        m.put("type", a.getType());
        m.put("lecture", isLecture(a.getType()));
        m.put("teacher", a.getTeacher());
        m.put("task", a.getTask());
        m.put("deadline", a.getDeadline());
        m.put("deadlineTs", dl > 0 ? dl : null);
        m.put("earned", a.getEarnedScore());
        m.put("max", a.getMaxScore());
        m.put("criteria", a.getCriteria());
        m.put("sample", notBlank(a.getSampleFileUrl()) ? fileRef(a.getSampleFileUrl(), a.getSampleFileName()) : null);
        m.put("uploaded", uploaded ? fileRef(a.getUploadedFileUrl(), a.getUploadedFileName()) : null);
        m.put("activityId", a.getActivityId());
        m.put("canUpload", notBlank(a.getActivityId()) && open);
        // Балл выставлен — работа принята, даже если ссылки на файл уже нет.
        m.put("status", uploaded ? "uploaded" : graded ? "graded" : open ? "open" : dl > 0 ? "missed" : "unknown");
        return m;
    }

    private static Map<String, String> fileRef(String url, String name) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("url", url);
        m.put("name", name == null || name.isBlank() ? "file" : name);
        return m;
    }

    private Object attendance(Req r) {
        requireLogin(r);
        int courseId = r.pathInt(1);
        int sem = semester(r);
        String subject = r.q("subject");
        return cached(r, "att:" + courseId + ":" + sem, () -> {
            List<AttendanceRecord> recs = lms.getAttendance(r.uid(), courseId, sem, subject);
            List<Map<String, Object>> items = new ArrayList<>();
            for (AttendanceRecord a : recs) {
                if (a.isSummary()) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("date", a.getDate());
                m.put("type", a.getType());
                m.put("lecture", isLecture(a.getType()));
                m.put("topic", a.getCalendar());
                m.put("excused", a.getHasReason() == 1);
                items.add(m);
            }
            return Map.of("missed", items.size(), "items", items);
        });
    }

    private Object calendar(Req r) {
        requireLogin(r);
        int courseId = r.pathInt(1);
        return cached(r, "cal:" + courseId, () -> {
            List<Map<String, Object>> tabs = new ArrayList<>();
            for (Map.Entry<String, List<CalendarEntry>> e : lms.getCalendar(r.uid(), courseId).entrySet()) {
                List<Map<String, Object>> entries = new ArrayList<>();
                for (CalendarEntry c : e.getValue()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("number", c.getNumber());
                    m.put("topic", c.getTopic());
                    m.put("date", c.getDate());
                    List<Map<String, String>> files = new ArrayList<>();
                    for (CalendarEntry.FileAttachment f : c.getFiles()) {
                        files.add(Map.of("name", f.getName(), "url", f.getUrl(), "type", f.getType()));
                    }
                    m.put("files", files);
                    entries.add(m);
                }
                tabs.add(Map.of("key", e.getKey(), "entries", entries));
            }
            return tabs;
        });
    }

    /** Расписание на весь семестр — с обрезкой каждого предмета по его плану занятий. */
    private Object schedule(Req r) {
        requireLogin(r);
        int sem = semester(r);
        SemesterSchedule.Result res = bot.semesterSchedule().get(r.uid(), sem, "1".equals(r.q("fresh")));
        Map<String, Object> out = new LinkedHashMap<>();
        if (res == null) {
            out.put("lessons", List.of());
            return out;
        }
        out.put("start", res.start().toString());
        out.put("end", res.end().toString());
        out.put("weeks", res.weekCount());
        out.put("currentWeek", res.weekOf(java.time.LocalDate.now(AppConfig.LMS_ZONE)));
        List<Map<String, Object>> lessons = new ArrayList<>();
        for (SemesterSchedule.Lesson l : res.lessons()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", l.date().toString());
            m.put("time", l.time());
            m.put("ts", l.ts());
            m.put("subject", l.subject());
            m.put("kind", l.kind());
            m.put("room", l.room());
            m.put("teacher", l.teacher());
            m.put("stream", l.stream());
            m.put("topic", l.topic());
            m.put("courseId", l.courseId());
            m.put("last", l.last());
            lessons.add(m);
        }
        out.put("lessons", lessons);
        return out;
    }

    /** Ближайшие дедлайны по всем предметам семестра. Предметы опрашиваются параллельно. */
    private Object deadlines(Req r) {
        requireLogin(r);
        int sem = semester(r);
        long uid = r.uid();
        return cached(r, "dl:" + sem, () -> {
            List<Course> courses = lms.getMyCourses(uid, sem);
            long now = System.currentTimeMillis();
            List<Future<List<Map<String, Object>>>> futures = new ArrayList<>();
            for (Course c : courses) {
                futures.add(pool.submit(() -> {
                    List<Map<String, Object>> items = new ArrayList<>();
                    CourseSummary cs = lms.getActivities(uid, c.getId());
                    if (cs == null || cs.getActivities() == null) return items;
                    List<Activity> list = cs.getActivities();
                    for (int i = 0; i < list.size(); i++) {
                        long dl = LmsDates.parseDeadline(list.get(i).getDeadline());
                        if (dl <= 0 || dl < now) continue;
                        Map<String, Object> m = activityJson(list.get(i), i, now);
                        m.put("courseId", c.getId());
                        m.put("course", c.getSubject());
                        items.add(m);
                    }
                    return items;
                }));
            }
            List<Map<String, Object>> all = new ArrayList<>();
            for (Future<List<Map<String, Object>>> f : futures) {
                try { all.addAll(f.get(60, TimeUnit.SECONDS)); } catch (Exception ignored) {}
            }
            all.sort(Comparator.comparingLong(m -> (Long) m.get("deadlineTs")));
            return all;
        });
    }

    private Object studyPlan(Req r) {
        requireLogin(r);
        return cached(r, "plan", () -> lms.getStudyPlan(r.uid()));
    }

    private Object finals(Req r) {
        requireLogin(r);
        int sem = semester(r);
        return cached(r, "finals:" + sem, () -> lms.getFinals(r.uid(), sem));
    }

    private Object profile(Req r) {
        requireLogin(r);
        return cached(r, "profile", () -> {
            StudentInfo info = lms.getStudentInfo(r.uid());
            if (info == null) throw new ApiError(502, "lms_unavailable");
            return info;
        });
    }

    private Object profilePhoto(Req r) {
        requireLogin(r);
        return cached(r, "photo", () -> {
            Map<String, Object> m = new HashMap<>();
            m.put("dataUrl", lms.getProfilePhotoDataUrl(r.uid()));
            return m;
        });
    }

    /** Оплата контракта: суммы и доля оплаченного; found=false — LMS не показывает контракт. */
    private Object contract(Req r) {
        requireLogin(r);
        return cached(r, "contract", () -> {
            LmsService.ContractInfo c = lms.getContract(r.uid());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("found", c != null);
            if (c != null) {
                m.put("notice", c.notice());
                m.put("total", c.total());
                m.put("paid", c.paid());
                m.put("debt", c.debt());
            }
            return m;
        });
    }

    private Object changePassword(Req r) throws Exception {
        requireLogin(r);
        String oldP = r.str("old"), newP = r.str("new"), conf = r.str("confirm");
        if (oldP.isEmpty() || newP.isEmpty()) throw new ApiError(400, "empty");
        if (!newP.equals(conf)) throw new ApiError(400, "mismatch");
        return Map.of("ok", lms.changePassword(r.uid(), oldP, newP, conf));
    }

    // ─────────────────────────────────────────────
    //  ADMIN
    // ─────────────────────────────────────────────

    private static final List<String> FILTERS = List.of("group", "course", "direction", "gender", "studyType", "language");

    private static String field(StudentRegistry.Student s, String name) {
        return switch (name) {
            case "fullName" -> s.fullName != null ? s.fullName : s.tgName;
            case "login" -> s.login;
            case "group" -> s.group;
            case "direction" -> s.direction;
            case "course" -> s.course;
            case "gender" -> s.gender;
            case "birthDate" -> s.birthDate;
            case "curator" -> s.curator;
            case "studyType" -> s.studyType;
            case "language" -> s.language;
            default -> null;
        };
    }

    /** «12.03.2006» → 20060312 для сортировки по дате рождения. */
    private static Long dateKey(String d) {
        if (d == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})").matcher(d);
        if (m.find()) return Long.parseLong(m.group(3)) * 10000 + Long.parseLong(m.group(2)) * 100 + Long.parseLong(m.group(1));
        m = java.util.regex.Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})").matcher(d);
        if (m.find()) return Long.parseLong(m.group(1)) * 10000 + Long.parseLong(m.group(2)) * 100 + Long.parseLong(m.group(3));
        return null;
    }

    private static Comparable<?> sortKey(StudentRegistry.Student s, String sort) {
        switch (sort) {
            case "gpa": return s.gpa;
            case "lastSeen": return s.lastSeen > 0 ? s.lastSeen : null;
            case "birthDate": return dateKey(s.birthDate);
            case "course": {
                String c = s.course;
                if (c == null) return null;
                String digits = c.replaceAll("\\D", "");
                return digits.isEmpty() ? null : Long.parseLong(digits);
            }
            default: {
                String v = field(s, sort);
                return v == null || v.isBlank() ? null : v.toLowerCase(Locale.ROOT);
            }
        }
    }

    /**
     * Список студентов для администратора: поиск, фильтры, сортировка, пагинация.
     * Доступ проверяется здесь, на сервере; во фронтенде кнопка лишь скрыта.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object adminStudents(Req r) {
        if (!AppConfig.isAdmin(r.uid())) throw new ApiError(403, "forbidden");

        List<StudentRegistry.Student> all = bot.students().all();
        long now = System.currentTimeMillis();

        // Значения для фильтров — по всему списку, чтобы выбор не зависел от поиска.
        Map<String, List<String>> facets = new LinkedHashMap<>();
        for (String f : FILTERS) {
            TreeSet<String> vals = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (StudentRegistry.Student s : all) {
                String v = field(s, f);
                if (v != null && !v.isBlank()) vals.add(v.trim());
            }
            facets.put(f, new ArrayList<>(vals));
        }

        String q = Optional.ofNullable(r.q("q")).orElse("").trim().toLowerCase(Locale.ROOT);
        List<StudentRegistry.Student> list = new ArrayList<>();
        for (StudentRegistry.Student s : all) {
            boolean ok = true;
            for (String f : FILTERS) {
                String want = r.q(f);
                if (want == null || want.isBlank()) continue;
                String v = field(s, f);
                if (v == null || !v.trim().equalsIgnoreCase(want.trim())) { ok = false; break; }
            }
            if (!ok) continue;
            if (!q.isEmpty()) {
                String hay = String.join(" ", Arrays.asList(s.fullName, s.tgName, s.tgUsername, s.login, s.group,
                        s.direction, s.curator, s.recordBook, String.valueOf(s.telegramId))).toLowerCase(Locale.ROOT);
                if (!hay.contains(q)) continue;
            }
            list.add(s);
        }

        String sort = Optional.ofNullable(r.q("sort")).orElse("lastSeen");
        boolean desc = !"asc".equalsIgnoreCase(r.q("dir"));
        Comparator<StudentRegistry.Student> cmp = (a, b) -> {
            Comparable ka = sortKey(a, sort), kb = sortKey(b, sort);
            if (ka == null && kb == null) return 0;
            if (ka == null) return 1;   // пустые — всегда в конце
            if (kb == null) return -1;
            int c = ka.compareTo(kb);
            return desc ? -c : c;
        };
        list.sort(cmp.thenComparing(s -> String.valueOf(field(s, "fullName")), String.CASE_INSENSITIVE_ORDER));

        int size = Math.max(5, Math.min(100, r.qInt("size", 20)));
        int pages = Math.max(1, (list.size() + size - 1) / size);
        int page = Math.max(1, Math.min(pages, r.qInt("page", 1)));
        List<Map<String, Object>> items = new ArrayList<>();
        for (StudentRegistry.Student s : list.subList((page - 1) * size, Math.min(list.size(), page * size))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("telegramId", s.telegramId);
            m.put("tgName", s.tgName);
            m.put("tgUsername", s.tgUsername);
            m.put("fullName", s.fullName);
            m.put("login", s.login);
            m.put("recordBook", s.recordBook);
            m.put("group", s.group);
            m.put("direction", s.direction);
            m.put("course", s.course);
            m.put("gender", s.gender);
            m.put("birthDate", s.birthDate);
            m.put("curator", s.curator);
            m.put("studyType", s.studyType);
            m.put("language", s.language);
            m.put("gpa", s.gpa);
            m.put("firstSeen", s.firstSeen);
            m.put("lastSeen", s.lastSeen);
            items.add(m);
        }

        long day = 24L * 60 * 60 * 1000;
        double gpaSum = 0;
        int gpaN = 0;
        for (StudentRegistry.Student s : all) if (s.gpa != null) { gpaSum += s.gpa; gpaN++; }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", all.size());
        stats.put("active24h", all.stream().filter(s -> now - s.lastSeen < day).count());
        stats.put("active7d", all.stream().filter(s -> now - s.lastSeen < 7 * day).count());
        stats.put("avgGpa", gpaN > 0 ? Math.round(gpaSum / gpaN * 100) / 100.0 : null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", list.size());
        out.put("page", page);
        out.put("pages", pages);
        out.put("size", size);
        out.put("stats", stats);
        out.put("facets", facets);
        return out;
    }

    // ─────────────────────────────────────────────
    //  FILES
    // ─────────────────────────────────────────────

    /**
     * Скачивать разрешено только с lms.tuit.uz: иначе через API можно было бы
     * заставить сервер ходить на произвольные адреса.
     */
    private String lmsUrl(String url) {
        if (url == null || url.isBlank()) throw new ApiError(400, "bad_url");
        String base = config.getLms().getBaseUrl();
        String full = url.startsWith("http://") || url.startsWith("https://") ? url
                : base + (url.startsWith("/") ? "" : "/") + url;
        try {
            String host = URI.create(full.replace(" ", "%20")).getHost();
            if (host != null && host.equalsIgnoreCase(URI.create(base).getHost())) return full;
        } catch (Exception ignored) {}
        throw new ApiError(400, "bad_url");
    }

    private Object fileSend(Req r) throws Exception {
        requireLogin(r);
        String url = lmsUrl(r.str("url"));
        File tmp = null;
        try {
            LmsService.DownloadedFile dl = lms.downloadFile(r.uid(), url, r.str("name"));
            tmp = dl.file();
            bot.appSendDocument(r.uid(), tmp, dl.filename());
            return null;
        } catch (ApiError e) {
            throw e;
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            // Пользователь не запускал бота или заблокировал его — писать ему нельзя.
            if (msg.contains("chat not found") || msg.contains("blocked") || msg.contains("403")) throw new ApiError(409, "chat_unavailable");
            throw new ApiError(502, "download_failed");
        } finally {
            if (tmp != null) tmp.delete();
        }
    }

    /** Одноразовая ссылка для Telegram.WebApp.downloadFile: сам запрос приходит без initData. */
    private Object fileLink(Req r) throws Exception {
        requireLogin(r);
        String url = lmsUrl(r.str("url"));
        byte[] b = new byte[18];
        random.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        long now = System.currentTimeMillis();
        tickets.values().removeIf(t -> t.expires() < now);
        tickets.put(token, new FileTicket(r.uid(), url, r.str("name"), now + 5 * 60 * 1000));
        return Map.of("path", "/api/files/get?t=" + token);
    }

    private Object fileGet(Req r) throws Exception {
        FileTicket t = tickets.remove(String.valueOf(r.q("t")));
        if (t == null || t.expires() < System.currentTimeMillis()) throw new ApiError(404, "expired");
        File tmp = null;
        try {
            LmsService.DownloadedFile dl = lms.downloadFile(t.uid(), t.url(), t.name());
            tmp = dl.file();
            return new WebServer.Raw(Files.readAllBytes(tmp.toPath()), dl.contentType(), dl.filename());
        } finally {
            if (tmp != null) tmp.delete();
        }
    }

    private Object upload(Req r) throws Exception {
        requireLogin(r);
        int courseId = r.qInt("course", -1);
        String activityId = r.q("activity");
        if (courseId <= 0 || activityId == null || !activityId.matches("\\d{1,12}")) throw new ApiError(400, "bad_request");

        String rawName = r.header("X-File-Name");
        String filename = rawName == null ? "" : URLDecoder.decode(rawName.replace("+", "%2B"), StandardCharsets.UTF_8);
        filename = filename.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "_").trim();
        if (filename.isEmpty()) filename = "file";
        int dot = filename.lastIndexOf('.');
        String ext = dot >= 0 ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (!UPLOAD_EXT.contains(ext)) throw new ApiError(415, "bad_type");

        byte[] data = r.bytes(UPLOAD_LIMIT);
        if (data.length == 0) throw new ApiError(400, "empty");

        File tmp = File.createTempFile("lmsapp_", "." + ext);
        try {
            Files.write(tmp.toPath(), data);
            boolean ok = lms.uploadActivityFile(r.uid(), courseId, activityId, tmp, filename);
            if (!ok) throw new ApiError(502, "upload_failed");
            cache.keySet().removeIf(k -> k.startsWith(r.uid() + "|act:" + courseId) || k.startsWith(r.uid() + "|dl:"));
            return null;
        } finally {
            tmp.delete();
        }
    }

    // ─────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────

    private Object cached(Req r, String key, Supplier<Object> loader) {
        String k = r.uid() + "|" + key;
        long now = System.currentTimeMillis();
        Cached c = cache.get(k);
        if (!"1".equals(r.q("fresh")) && c != null && now - c.ts() < CACHE_MS) return c.value();
        Object v = loader.get();
        // Пустой ответ чаще всего значит сбой LMS, а не отсутствие данных — не кэшируем.
        boolean empty = v == null || (v instanceof Collection<?> col && col.isEmpty());
        if (!empty) cache.put(k, new Cached(now, v));
        return v;
    }

    private void dropCache(long uid) {
        bot.semesterSchedule().invalidate(uid);
        String prefix = uid + "|";
        cache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private static boolean isGraded(String raw) {
        if (raw == null || raw.isBlank()) return false;
        try { return Double.parseDouble(raw.replace(',', '.').trim()) > 0; } catch (NumberFormatException e) { return false; }
    }

    /** Тип занятия приходит на языке LMS, поэтому лекцию узнаём по всем написаниям. */
    private static boolean isLecture(String type) {
        if (type == null) return false;
        String t = type.toLowerCase(Locale.ROOT);
        return t.contains("лек") || t.contains("lecture") || t.contains("ma'ruza") || t.contains("maruza") || t.contains("маъруза");
    }
}
