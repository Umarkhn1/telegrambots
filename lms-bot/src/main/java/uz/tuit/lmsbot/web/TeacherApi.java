package uz.tuit.lmsbot.web;

import com.fasterxml.jackson.databind.JsonNode;
import uz.tuit.lmsbot.bot.LmsBot;
import uz.tuit.lmsbot.service.LmsService;
import uz.tuit.lmsbot.service.TeacherService;
import uz.tuit.lmsbot.service.TeacherService.*;

import java.io.File;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static uz.tuit.lmsbot.web.WebServer.ApiError;
import static uz.tuit.lmsbot.web.WebServer.Req;

/**
 * API кабинета преподавателя и тьютора для мини-приложения (/api/t/…).
 * Данные — те же, что видит бот: TeacherService над той же LMS-сессией.
 * Подписи и статусы LMS отдаются как есть, чтобы приложение не держало своих копий.
 */
class TeacherApi {

    private static final long CACHE_MS = 3L * 60 * 1000;
    private static final long UPLOAD_LIMIT = 50L * 1024 * 1024;

    private final LmsService lms;
    private final TeacherService ts;
    private final LmsBot bot;

    private record Cached(long ts, Object value) {}
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    TeacherApi(LmsService lms, LmsBot bot) {
        this.lms = lms;
        this.ts = lms.teacher();
        this.bot = bot;
    }

    void register(WebServer s) {
        s.get("/api/t/courses", this::courses);
        s.get("/api/t/courses/(\\d+)/calendar", r -> call(r, "cal:" + r.pathInt(1), () -> {
            TCalendar c = ts.calendar(r.uid(), r.pathInt(1));
            ts.rememberStream(r.uid(), r.pathInt(1), c.stream());
            return c;
        }));
        s.get("/api/t/courses/(\\d+)/attendance/(\\d+)", r -> call(r, "att:" + r.pathInt(1) + ":" + r.pathInt(2),
                () -> ts.attendance(r.uid(), r.pathInt(1), r.pathInt(2))));
        s.get("/api/t/courses/(\\d+)/sheet", r -> call(r, "sheet:" + r.pathInt(1), () -> ts.gradeSheet(r.uid(), r.pathInt(1))));
        s.get("/api/t/courses/(\\d+)/activities", r -> call(r, "act:" + r.pathInt(1), () -> ts.activities(r.uid(), r.pathInt(1))));
        s.post("/api/t/courses/(\\d+)/activities", this::createActivity);
        s.post("/api/t/courses/(\\d+)/activities/(\\d+)/delete", r -> {
            teacher(r);
            FormResult res = ts.deleteActivity(r.uid(), r.pathInt(1), r.pathInt(2));
            drop(r.uid(), "act:" + r.pathInt(1), "sheet:" + r.pathInt(1), "grading");
            return result(res);
        });
        s.get("/api/t/grade/(\\d+)/(\\d+)", r -> {
            teacher(r);
            return ts.gradeForm(r.uid(), r.pathInt(1), r.pathInt(2));
        });
        s.post("/api/t/grade/(\\d+)/(\\d+)", this::setGrade);
        s.post("/api/t/grade/(\\d+)/(\\d+)/clear", r -> {
            teacher(r);
            FormResult res = ts.clearGrade(r.uid(), r.pathInt(1), r.pathInt(2));
            drop(r.uid(), "sheet:", "grading");
            return result(res);
        });
        s.get("/api/t/grading", r -> call(r, "grading", () -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (GradingItem g : ts.grading(r.uid())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("courseId", g.deadline().courseId());
                m.put("subject", g.deadline().subject());
                m.put("activity", g.deadline().activity());
                m.put("stream", g.deadline().stream());
                m.put("column", g.column());
                m.put("activityId", g.info() != null ? g.info().activityId() : null);
                m.put("studentDeadline", g.info() != null && g.info().studentDeadline() != null ? g.info().studentDeadline() : g.deadline().start());
                m.put("teacherDeadline", g.info() != null ? g.info().teacherDeadline() : null);
                m.put("studentTs", g.studentTs());
                m.put("teacherTs", g.teacherTs() > 0 ? g.teacherTs() : null);
                m.put("submitted", g.submitted());
                m.put("graded", g.graded());
                m.put("pending", g.pending());
                out.add(m);
            }
            return out;
        }));
        s.get("/api/t/finals", r -> {
            int sem = r.qInt("semester", lms.getCurrentSemesterId(r.uid()));
            return call(r, "finals:" + sem, () -> ts.finals(r.uid(), sem));
        });
        s.get("/api/t/profile", r -> call(r, "profile", () -> ts.info(r.uid())));

        s.get("/api/t/appeals", r -> call(r, "appeals", () -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("page", ts.appealPage(r.uid()));
            m.put("items", ts.appeals(r.uid()));
            return m;
        }));
        s.get("/api/t/appeals/lessons", r -> {
            teacher(r);
            return ts.appealLessons(r.uid(), need(r.q("stream")));
        });
        s.get("/api/t/appeals/students", r -> {
            teacher(r);
            return ts.appealStudents(r.uid(), need(r.q("lesson")));
        });
        s.post("/api/t/appeals", r -> {
            teacher(r);
            String pair = r.str("pair").trim();
            if (!pair.matches("\\d{1,2}")) throw new ApiError(400, "bad_pair");
            FormResult res = ts.createAppeal(r.uid(), need(r.str("stream")), need(r.str("lesson")), pair, need(r.str("student")));
            drop(r.uid(), "appeals");
            return result(res);
        });
        s.post("/api/t/appeals/(\\d+)/delete", r -> {
            teacher(r);
            FormResult res = ts.deleteAppeal(r.uid(), r.pathInt(1));
            drop(r.uid(), "appeals");
            return result(res);
        });
        s.get("/api/t/appeals/(\\d+)/pdf", r -> {
            teacher(r);
            return Map.of("url", ts.appealPdfUrl(r.pathInt(1)));
        });

        s.get("/api/t/materials", r -> {
            int sem = r.qInt("semester", lms.getCurrentSemesterId(r.uid()));
            return call(r, "msub:" + sem, () -> ts.materialSubjects(r.uid(), sem));
        });
        s.get("/api/t/materials/(\\d+)", r -> {
            int sem = r.qInt("semester", lms.getCurrentSemesterId(r.uid()));
            String lang = lang(r);
            return call(r, "mpage:" + r.pathInt(1) + ":" + sem + ":" + lang, () -> ts.materialPage(r.uid(), r.pathInt(1), sem, lang));
        });
        s.get("/api/t/materials/(\\d+)/topics", r -> {
            int sem = r.qInt("semester", lms.getCurrentSemesterId(r.uid()));
            String lang = lang(r), type = need(r.q("type"));
            return call(r, "mtop:" + r.pathInt(1) + ":" + sem + ":" + lang + ":" + type,
                    () -> ts.materialTopics(r.uid(), r.pathInt(1), sem, type, lang));
        });
        s.post("/api/t/materials/(\\d+)/add", this::addMaterial);
        s.post("/api/t/materials/resource/(\\d+)/delete", r -> {
            teacher(r);
            FormResult res = ts.deleteMaterial(r.uid(), r.pathInt(1));
            drop(r.uid(), "mtop:");
            return result(res);
        });

        s.get("/api/t/tutor/groups", r -> call(r, "tgroups", () -> ts.tutorGroups(r.uid())));
        s.get("/api/t/tutor/groups/(\\d+)", r -> call(r, "tgroup:" + r.pathInt(1), () -> ts.tutorStudents(r.uid(), r.pathInt(1))));
        s.get("/api/t/tutor/students/(\\d+)", r -> call(r, "tst:" + r.pathInt(1), () -> ts.tutorStudent(r.uid(), r.pathInt(1))));
        s.get("/api/t/tutor/students/(\\d+)/courses", r -> {
            int user = r.qInt("user", -1);
            String sem = need(r.q("semester"));
            if (user <= 0) throw new ApiError(400, "bad_request");
            return call(r, "tsc:" + user + ":" + sem, () -> ts.tutorStudentCourses(r.uid(), user, sem));
        });
        s.get("/api/t/tutor/students/(\\d+)/plan", r -> call(r, "tsp:" + r.pathInt(1), () -> ts.tutorStudyPlan(r.uid(), r.pathInt(1))));
        s.get("/api/t/tutor/students/(\\d+)/schedule", r -> {
            String sem = need(r.q("semester"));
            int user = r.qInt("user", -1);
            return call(r, "tss:" + r.pathInt(1) + ":" + sem, () -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("events", ts.tutorStudentSchedule(r.uid(), r.pathInt(1), user > 0 ? user : null, sem));
                m.put("meta", ts.scheduleMeta(r.uid()));
                return m;
            });
        });
    }

    // ─────────────────────────────────────────────
    //  HANDLERS
    // ─────────────────────────────────────────────

    private Object courses(Req r) throws Exception {
        teacher(r);
        int sem = r.qInt("semester", -1);
        if (sem <= 0) sem = lms.getCurrentSemesterId(r.uid());
        if (sem <= 0) throw new ApiError(409, "no_semester");
        int s = sem;
        bot.appSemester(r.uid(), s);
        return call(r, "courses:" + s, () -> ts.courses(r.uid(), s));
    }

    private Object setGrade(Req r) throws Exception {
        teacher(r);
        int student = r.pathInt(1), activity = r.pathInt(2);
        GradeForm form = ts.gradeForm(r.uid(), student, activity);
        if (!form.editable()) throw new ApiError(409, "locked");
        JsonNode body = r.json();
        Map<String, String> values = new LinkedHashMap<>();
        JsonNode v = body.get("values");
        if (v != null && v.isObject()) v.fields().forEachRemaining(e -> values.put(e.getKey(), number(e.getValue().asText())));
        for (Criterion c : form.criteria()) {
            String val = values.get(c.id());
            if (val == null) continue;
            if (!within(val, c.max())) throw new ApiError(400, "out_of_range");
        }
        String grade = body.hasNonNull("grade") ? number(body.get("grade").asText()) : null;
        if (grade != null && !within(grade, form.maxPoint())) throw new ApiError(400, "out_of_range");
        String comment = body.has("comment") && !body.get("comment").isNull() ? body.get("comment").asText() : null;
        FormResult res = ts.setGrade(r.uid(), student, activity, form, values, grade, comment);
        drop(r.uid(), "sheet:", "grading");
        Map<String, Object> out = result(res);
        if (res.ok()) out.put("form", ts.gradeForm(r.uid(), student, activity));
        return out;
    }

    private Object createActivity(Req r) throws Exception {
        teacher(r);
        int course = r.pathInt(1);
        String name = need(r.q("name")).trim();
        String deadline = need(r.q("deadline")).trim();
        String max = number(need(r.q("max")));
        List<String[]> criteria = new ArrayList<>();
        JsonNode items = new com.fasterxml.jackson.databind.ObjectMapper().readTree(need(r.q("criteria")));
        for (JsonNode it : items) criteria.add(new String[]{it.path("name").asText("").trim(), number(it.path("points").asText(""))});
        if (criteria.isEmpty()) throw new ApiError(400, "no_criteria");
        File tmp = null;
        String fileName = fileName(r);
        try {
            if (fileName != null) {
                byte[] data = r.bytes(UPLOAD_LIMIT);
                if (data.length > 0) {
                    tmp = File.createTempFile("lmsact_", "_" + fileName);
                    Files.write(tmp.toPath(), data);
                }
            }
            FormResult res = ts.createActivity(r.uid(), course, name, deadline, max, criteria, tmp, fileName);
            drop(r.uid(), "act:" + course, "sheet:" + course, "grading");
            return result(res);
        } finally {
            if (tmp != null) tmp.delete();
        }
    }

    private Object addMaterial(Req r) throws Exception {
        teacher(r);
        int subject = r.pathInt(1);
        int sem = r.qInt("semester", lms.getCurrentSemesterId(r.uid()));
        int topic = r.qInt("topic", -1);
        if (topic <= 0) throw new ApiError(400, "bad_request");
        String type = need(r.q("type"));
        String url = r.q("url");
        String name = r.q("name");
        File tmp = null;
        String fileName = fileName(r);
        try {
            if (fileName != null) {
                byte[] data = r.bytes(UPLOAD_LIMIT);
                if (data.length == 0) throw new ApiError(400, "empty");
                tmp = File.createTempFile("lmsmat_", "_" + fileName);
                Files.write(tmp.toPath(), data);
            } else if (url == null || !url.matches("(?i)https?://\\S+")) {
                throw new ApiError(400, "bad_url");
            }
            FormResult res = ts.addMaterial(r.uid(), subject, sem, topic, lang(r), type, name, url, tmp, fileName);
            drop(r.uid(), "mtop:");
            return result(res);
        } finally {
            if (tmp != null) tmp.delete();
        }
    }

    // ─────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────

    /** Раздел только для вошедшего преподавателя. */
    private void teacher(Req r) {
        long uid = r.uid();
        if (!lms.isLoggedIn(uid) && !lms.restoreSession(uid)) throw new ApiError(403, "not_logged_in");
        if (!lms.isTeacher(uid)) throw new ApiError(403, "not_teacher");
    }

    private interface Loader {
        Object load() throws Exception;
    }

    private Object call(Req r, String key, Loader loader) throws Exception {
        teacher(r);
        String k = r.uid() + "|" + key;
        long now = System.currentTimeMillis();
        Cached c = cache.get(k);
        if (!"1".equals(r.q("fresh")) && c != null && now - c.ts() < CACHE_MS) return c.value();
        Object v;
        try {
            v = loader.load();
        } catch (ApiError e) {
            throw e;
        } catch (Exception e) {
            System.err.println("[TeacherApi] " + key + ": " + e.getMessage());
            throw new ApiError(502, "lms_unavailable");
        }
        boolean empty = v == null || (v instanceof Collection<?> col && col.isEmpty());
        if (!empty) cache.put(k, new Cached(now, v));
        return v;
    }

    void dropAll(long uid) {
        cache.keySet().removeIf(k -> k.startsWith(uid + "|"));
    }

    private void drop(long uid, String... prefixes) {
        for (String p : prefixes) cache.keySet().removeIf(k -> k.startsWith(uid + "|" + p));
    }

    private static Map<String, Object> result(FormResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", r.ok());
        m.put("errors", r.errors());
        return m;
    }

    private static String need(String v) {
        if (v == null || v.isBlank()) throw new ApiError(400, "bad_request");
        return v;
    }

    private static String lang(Req r) {
        String l = r.q("lang");
        return l == null || !l.matches("[a-z]{2,4}") ? "ru" : l;
    }

    /** Число с точкой: «4,5» → «4.5»; не число — 400. */
    private static String number(String s) {
        try {
            java.math.BigDecimal d = new java.math.BigDecimal(s.trim().replace(',', '.')).stripTrailingZeros();
            if (d.signum() < 0) throw new ApiError(400, "out_of_range");
            return d.scale() < 0 ? d.setScale(0).toPlainString() : d.toPlainString();
        } catch (NumberFormatException e) {
            throw new ApiError(400, "bad_number");
        }
    }

    private static boolean within(String value, String max) {
        try {
            return new java.math.BigDecimal(value).compareTo(new java.math.BigDecimal(max)) <= 0;
        } catch (Exception e) {
            return true;
        }
    }

    private static String fileName(Req r) {
        String raw = r.header("X-File-Name");
        if (raw == null || raw.isBlank()) return null;
        String name = URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8)
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "_").trim();
        return name.isEmpty() ? "file" : name;
    }
}
