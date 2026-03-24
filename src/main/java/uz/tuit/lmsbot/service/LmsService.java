package uz.tuit.lmsbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import uz.tuit.lmsbot.config.AppConfig;
import uz.tuit.lmsbot.model.*;

import java.io.File;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LmsService {

    private final AppConfig config;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<Long, OkHttpClient> clients     = new ConcurrentHashMap<>();
    private final Map<Long, Boolean>      loggedInMap = new ConcurrentHashMap<>();

    public LmsService(AppConfig config) {
        this.config = config;
    }

    // ─────────────────────────────────────────────
    //  HTTP CLIENT PER USER
    // ─────────────────────────────────────────────

    private OkHttpClient getClient(long userId) {
        return clients.computeIfAbsent(userId, id -> new OkHttpClient.Builder()
                .cookieJar(new PersistentCookieJar(sessionDir(), userId))
                .followRedirects(true)
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build());
    }

    private Path sessionDir() {
        // Keep it simple and OS-safe: %USERPROFILE%\.tuit-lms-bot\sessions
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) home = ".";
        return Paths.get(home, ".tuit-lms-bot", "sessions");
    }

    // ─────────────────────────────────────────────
    //  AUTH
    // ─────────────────────────────────────────────

    public boolean login(long userId, String login, String password) {
        try {
            OkHttpClient client = getClient(userId);
            String loginUrl = config.getLms().getLoginUrl();

            Request getReq = new Request.Builder()
                    .url(loginUrl)
                    .header("User-Agent", userAgent())
                    .build();

            String html;
            try (Response resp = client.newCall(getReq).execute()) {
                html = resp.body().string();
            }

            String csrf = extractCsrf(html);
            if (csrf == null) return false;

            RequestBody body = new FormBody.Builder()
                    .add("_token", csrf)
                    .add("login", login)
                    .add("password", password)
                    .add("g-recaptcha-response", "")
                    .build();

            Request postReq = new Request.Builder()
                    .url(loginUrl)
                    .post(body)
                    .header("User-Agent", userAgent())
                    .header("Referer", loginUrl)
                    .build();

            String finalUrl;
            String responseBody;
            try (Response resp = client.newCall(postReq).execute()) {
                finalUrl     = resp.request().url().toString();
                responseBody = resp.body().string();
            }

            boolean success = finalUrl.contains("/dashboard")
                    || responseBody.contains("page-sidebar")
                    || responseBody.contains("Студент");

            loggedInMap.put(userId, success);
            return success;

        } catch (Exception e) {
            System.err.println("[LmsService] Login error: " + e.getMessage());
            return false;
        }
    }

    public boolean isLoggedIn(long userId) {
        return loggedInMap.getOrDefault(userId, false);
    }

    public void logout(long userId) {
        loggedInMap.remove(userId);
        clients.remove(userId);
        try {
            // Also clear persistent cookies so user is fully logged out
            new PersistentCookieJar(sessionDir(), userId).clear();
        } catch (Exception ignored) {}
    }

    // ─────────────────────────────────────────────
    //  MY COURSES
    // ─────────────────────────────────────────────

    public List<Course> getMyCourses(long userId, int semesterId) {
        try {
            String url = config.getLms().getBaseUrl()
                    + "/student/my-courses/data?"
                    + "draw=1"
                    + "&columns%5B0%5D%5Bdata%5D=subject&columns%5B0%5D%5Bname%5D=subject"
                    + "&columns%5B0%5D%5Bsearchable%5D=true&columns%5B0%5D%5Borderable%5D=true"
                    + "&columns%5B0%5D%5Bsearch%5D%5Bvalue%5D=&columns%5B0%5D%5Bsearch%5D%5Bregex%5D=false"
                    + "&columns%5B1%5D%5Bdata%5D=teachers&columns%5B1%5D%5Bname%5D=teachers"
                    + "&columns%5B1%5D%5Bsearchable%5D=false&columns%5B1%5D%5Borderable%5D=true"
                    + "&columns%5B1%5D%5Bsearch%5D%5Bvalue%5D=&columns%5B1%5D%5Bsearch%5D%5Bregex%5D=false"
                    + "&columns%5B2%5D%5Bdata%5D=attendance&columns%5B2%5D%5Bname%5D=attendance"
                    + "&columns%5B2%5D%5Bsearchable%5D=false&columns%5B2%5D%5Borderable%5D=true"
                    + "&columns%5B2%5D%5Bsearch%5D%5Bvalue%5D=&columns%5B2%5D%5Bsearch%5D%5Bregex%5D=false"
                    + "&order%5B0%5D%5Bcolumn%5D=0&order%5B0%5D%5Bdir%5D=asc"
                    + "&start=0&length=100"
                    + "&semester_id=" + semesterId;

            String json = getJson(userId, url, config.getLms().getBaseUrl() + "/student/my-courses");
            JsonNode root = mapper.readTree(json);
            JsonNode data = root.get("data");

            List<Course> courses = new ArrayList<>();
            if (data == null || !data.isArray()) return courses;

            for (JsonNode node : data) {
                Course c = new Course();
                c.setId(node.path("id").asInt());
                c.setSubject(node.path("subject").asText(""));
                c.setTeachers(node.path("teachers").asText(""));
                c.setStreams(node.path("streams").asText(""));
                c.setAttendance(node.path("attendance").asInt(0));
                c.setFailed(node.path("failed").asBoolean(false));
                c.setSemesterId(node.path("semester_id").asInt(semesterId));
                c.setDownloadQuestionsUrl(node.path("download_questions_url").asText(null));
                courses.add(c);
            }

            courses.sort(Comparator
                    .comparing(Course::isFailed).reversed()
                    .thenComparing(Course::getSubject));

            return courses;

        } catch (Exception e) {
            System.err.println("[LmsService] getMyCourses error: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    // ─────────────────────────────────────────────
    //  ATTENDANCE
    // ─────────────────────────────────────────────

    public List<AttendanceRecord> getAttendance(long userId, int subjectId, int semesterId, String subjectFilter) {
        try {
            String url = config.getLms().getBaseUrl()
                    + "/student/attendance/data?"
                    + "draw=1"
                    + "&columns%5B0%5D%5Bdata%5D=date&columns%5B0%5D%5Bname%5D=date"
                    + "&columns%5B0%5D%5Bsearchable%5D=true&columns%5B0%5D%5Borderable%5D=true"
                    + "&columns%5B1%5D%5Bdata%5D=type&columns%5B1%5D%5Bname%5D=type"
                    + "&columns%5B1%5D%5Bsearchable%5D=true&columns%5B1%5D%5Borderable%5D=true"
                    + "&columns%5B2%5D%5Bdata%5D=calendar&columns%5B2%5D%5Bname%5D=calendar"
                    + "&columns%5B2%5D%5Bsearchable%5D=true&columns%5B2%5D%5Borderable%5D=true"
                    + "&order%5B0%5D%5Bcolumn%5D=0&order%5B0%5D%5Bdir%5D=desc"
                    + "&start=0&length=100"
                    + "&semester_id=" + semesterId;

            String referer = config.getLms().getBaseUrl()
                    + "/student/attendance/?subject_id=" + subjectId
                    + "&semester_id=" + semesterId;

            String json = getJson(userId, url, referer);
            JsonNode root = mapper.readTree(json);

            JsonNode data = root.get("data");

            List<AttendanceRecord> records = new ArrayList<>();

            AttendanceRecord summary = new AttendanceRecord();
            summary.setSummary(true);

            if (data != null && data.isArray()) {
                for (JsonNode node : data) {
                    String subj = node.path("subject").asText("");
                    if (subjectFilter != null && !subjectFilter.isBlank()
                            && !subjectFilter.equals(subj)) continue;

                    AttendanceRecord r = new AttendanceRecord();
                    r.setDate(node.path("date").asText(""));
                    r.setType(node.path("type").asText(""));
                    r.setCalendar(node.path("calendar").asText(""));
                    r.setHasReason(node.path("has_reason").asInt(0));
                    r.setSubject(node.path("subject").asText(""));
                    records.add(r);
                }
            }

            int missed = Math.max(0, records.size());
            int total  = missed; // endpoint returns only missed lessons per subject/semester

            summary.setTotal(total);
            summary.setMissed(missed);
            records.add(0, summary);

            records.subList(1, records.size())
                    .sort(Comparator.comparing(AttendanceRecord::getDate).reversed());

            return records;

        } catch (Exception e) {
            System.err.println("[LmsService] getAttendance error: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    // ─────────────────────────────────────────────
    //  ACTIVITIES (HTML parse with Jsoup)
    // ─────────────────────────────────────────────

    public CourseSummary getActivities(long userId, int courseId) {
        try {
            String url     = config.getLms().getBaseUrl() + "/student/my-courses/show/" + courseId;
            String referer = config.getLms().getBaseUrl() + "/student/my-courses";

            String html = getHtml(userId, url, referer);
            Document doc = Jsoup.parse(html);

            CourseSummary summary = new CourseSummary();

            Elements scoreCells = doc.select("table.table-bordered td h4");
            if (scoreCells.size() >= 4) {
                summary.setEarned(scoreCells.get(0).text().trim());
                summary.setMaxScore(scoreCells.get(1).text().trim());
                summary.setProgress(scoreCells.get(2).text().trim());
                summary.setGrade(scoreCells.get(3).text().trim());
            }

            List<Activity> activities = new ArrayList<>();
            Elements rows = doc.select("table#simple-table1 tbody tr");

            for (int rowIdx = 0; rowIdx < rows.size(); rowIdx++) {
                Element row = rows.get(rowIdx);
                Elements cells = row.select("td");
                if (cells.size() < 6) continue;

                Activity act = new Activity();
                act.setType(cells.get(0).text().trim());
                act.setTeacher(cells.get(1).text().trim());

                // col 2: task name + sample file (btn-default)
                Element taskNameEl = cells.get(2).selectFirst("p b");
                act.setTask(taskNameEl != null ? taskNameEl.text().trim() : cells.get(2).text().trim());

                Element sampleLink = cells.get(2).selectFirst("a.btn-default[href]");
                if (sampleLink == null) sampleLink = cells.get(2).selectFirst("a[href]");
                if (sampleLink != null) {
                    String href = sampleLink.attr("href");
                    if (!href.isEmpty() && !href.equals(config.getLms().getBaseUrl() + "/")
                            && !href.equals(config.getLms().getBaseUrl())) {
                        act.setSampleFileUrl(href);
                        act.setSampleFileName(sampleLink.text().trim());
                    }
                }

                act.setDeadline(cells.get(3).text().trim());

                Elements scoreButtons = cells.get(4).select("button");
                if (scoreButtons.size() >= 2) {
                    act.setEarnedScore(scoreButtons.get(0).text().trim());
                    act.setMaxScore(scoreButtons.get(1).text().trim());
                }

                // col 5: uploaded file (btn-primary) — student yuklagan fayl
                Element uploadedLink = cells.get(5).selectFirst("a.btn-primary[href]");
                if (uploadedLink != null) {
                    String href = uploadedLink.attr("href");
                    if (!href.isEmpty() && !href.equals("#")) {
                        act.setUploadedFileUrl(href);
                        act.setUploadedFileName(uploadedLink.text().trim());
                    }
                }

                // activityId: only from upload button (.js-btn-upload[data-id])
                Element uploadBtn = cells.get(5).selectFirst(".js-btn-upload[data-id]");
                if (uploadBtn != null && !uploadBtn.attr("data-id").isEmpty()) {
                    act.setActivityId(uploadBtn.attr("data-id"));
                } else {
                    act.setActivityId(null);
                }

                activities.add(act);
            }

            activities.sort(Comparator.comparing(Activity::getDeadline));
            summary.setActivities(activities);
            return summary;

        } catch (Exception e) {
            System.err.println("[LmsService] getActivities error: " + e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────
    //  SCHEDULE
    // ─────────────────────────────────────────────

    public List<ScheduleEvent> getSchedule(long userId, int semesterId) {
        try {
            OkHttpClient client = getClient(userId);
            Request request = new Request.Builder()
                    .url(config.getLms().getBaseUrl() + "/student/schedule/load/" + semesterId)
                    .addHeader("X-Requested-With", "XMLHttpRequest")
                    .addHeader("Accept", "application/json")
                    .build();
            try (Response response = client.newCall(request).execute()) {
                String body = response.body().string();
                JsonNode root = mapper.readTree(body);
                JsonNode jsonArr = root.get("json");
                List<ScheduleEvent> events = new ArrayList<>();
                if (jsonArr != null && jsonArr.isArray()) {
                    for (JsonNode node : jsonArr) {
                        ScheduleEvent e = new ScheduleEvent();
                        e.setTitle(node.get("title").asText());
                        e.setStart(node.get("start").asText());
                        e.setType(node.has("type") ? node.get("type").asInt() : 1);
                        events.add(e);
                    }
                }
                return events;
            }
        } catch (Exception e) {
            System.err.println("[LmsService] Schedule error: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    // ─────────────────────────────────────────────
    //  STUDY PLAN
    // ─────────────────────────────────────────────

    public List<StudyPlanSubject> getStudyPlan(long userId) {
        try {
            String html = getHtml(userId,
                    config.getLms().getBaseUrl() + "/student/study-plan",
                    config.getLms().getBaseUrl() + "/student/study-plan");
            Document doc = Jsoup.parse(html);
            List<StudyPlanSubject> subjects = new ArrayList<>();
            Elements cards = doc.select("div.card");
            int semesterNum = 0;
            for (Element card : cards) {
                Element title = card.selectFirst("p.font-18");
                if (title == null) continue;
                semesterNum++;
                Elements rows = card.select("tbody tr");
                for (Element row : rows) {
                    Elements cols = row.select("td");
                    if (cols.size() < 3) continue;
                    StudyPlanSubject s = new StudyPlanSubject();
                    s.setName(cols.get(0).text().trim());
                    try { s.setCredits(Integer.parseInt(cols.get(1).text().trim())); }
                    catch (Exception ex) { s.setCredits(0); }
                    String gradeStr = cols.get(2).text().trim();
                    s.setGrade(gradeStr.isEmpty() ? null : Integer.parseInt(gradeStr));
                    s.setSemester(semesterNum);
                    subjects.add(s);
                }
            }
            return subjects;
        } catch (Exception e) {
            System.err.println("[LmsService] StudyPlan error: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    // ─────────────────────────────────────────────
    //  STUDENT INFO
    // ─────────────────────────────────────────────

    public StudentInfo getStudentInfo(long userId) {
        try {
            String html = getHtml(userId,
                    config.getLms().getBaseUrl() + "/student/info",
                    config.getLms().getBaseUrl() + "/student/info");
            Document doc = Jsoup.parse(html);
            StudentInfo info = new StudentInfo();

            Elements leftPs = doc.select("div.col-sm-4 div.card p");
            for (Element p : leftPs) {
                Element strong = p.selectFirst("strong");
                if (strong == null) continue;
                String label = strong.text().toLowerCase();
                String value = p.text().replace(strong.text(), "").trim();
                if (label.contains("ф.и.о"))            info.setFullName(value);
                else if (label.contains("рожден"))       info.setBirthDate(value);
                else if (label.contains("пол"))          info.setGender(value);
                else if (label.contains("зачёт"))        info.setRecordBook(value);
                else if (label.contains("адрес") && !label.contains("вр")) info.setAddress(value);
            }

            Elements rightPs = doc.select("div.col-sm-7 div.card p");
            for (Element p : rightPs) {
                Element strong = p.selectFirst("strong");
                if (strong == null) continue;
                String label = strong.text().toLowerCase();
                String value = p.text().replace(strong.text(), "").trim();
                if (label.contains("направлен"))         info.setDirection(value);
                else if (label.contains("язык"))         info.setLanguage(value);
                else if (label.contains("степень"))      info.setDegree(value);
                else if (label.contains("тип обучен"))   info.setStudyType(value);
                else if (label.contains("курс"))         info.setCourse(value);
                else if (label.contains("группа"))       info.setGroup(value);
                else if (label.contains("куратор"))      info.setCurator(value);
                else if (label.contains("стипенд"))      info.setScholarship(value);
            }

            return info;
        } catch (Exception e) {
            System.err.println("[LmsService] getStudentInfo error: " + e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────
    //  FINALS
    // ─────────────────────────────────────────────

    public List<FinalExam> getFinals(long userId, int semesterId) {
        try {
            String url = config.getLms().getBaseUrl()
                    + "/student/finals/data?"
                    + "draw=1"
                    + "&columns%5B0%5D%5Bdata%5D=subject&columns%5B0%5D%5Bname%5D=subject"
                    + "&columns%5B0%5D%5Bsearchable%5D=true&columns%5B0%5D%5Borderable%5D=true"
                    + "&columns%5B1%5D%5Bdata%5D=stream&columns%5B1%5D%5Bname%5D=stream"
                    + "&columns%5B1%5D%5Bsearchable%5D=true&columns%5B1%5D%5Borderable%5D=true"
                    + "&columns%5B2%5D%5Bdata%5D=date&columns%5B2%5D%5Bname%5D=date"
                    + "&columns%5B2%5D%5Bsearchable%5D=true&columns%5B2%5D%5Borderable%5D=true"
                    + "&columns%5B3%5D%5Bdata%5D=from&columns%5B3%5D%5Bname%5D=from"
                    + "&columns%5B3%5D%5Bsearchable%5D=true&columns%5B3%5D%5Borderable%5D=true"
                    + "&columns%5B4%5D%5Bdata%5D=room&columns%5B4%5D%5Bname%5D=room"
                    + "&columns%5B4%5D%5Bsearchable%5D=true&columns%5B4%5D%5Borderable%5D=true"
                    + "&columns%5B5%5D%5Bdata%5D=f_grade&columns%5B5%5D%5Bname%5D=f_grade"
                    + "&columns%5B5%5D%5Bsearchable%5D=true&columns%5B5%5D%5Borderable%5D=true"
                    + "&order%5B0%5D%5Bcolumn%5D=2&order%5B0%5D%5Bdir%5D=asc"
                    + "&start=0&length=100"
                    + "&semester_id=" + semesterId;

            String referer = config.getLms().getBaseUrl() + "/student/finals";
            String json = getJson(userId, url, referer);
            JsonNode root = mapper.readTree(json);
            JsonNode data = root.get("data");

            List<FinalExam> exams = new ArrayList<>();
            if (data == null || !data.isArray()) return exams;

            for (JsonNode node : data) {
                FinalExam ex = new FinalExam();
                ex.setSubject(node.path("subject").asText(""));
                ex.setStream(node.path("stream").asText(""));
                ex.setDate(node.path("date").asText(""));
                ex.setFrom(node.path("from").asText(""));
                ex.setRoom(node.path("room").asText(""));
                JsonNode gradeNode = node.get("f_grade");
                ex.setGrade(gradeNode != null && !gradeNode.isNull() ? gradeNode.asText() : "—");
                exams.add(ex);
            }

            return exams;

        } catch (Exception e) {
            System.err.println("[LmsService] getFinals error: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    // ─────────────────────────────────────────────
    //  CALENDAR — /student/calendar/{courseId}
    // ─────────────────────────────────────────────

    public Map<String, List<CalendarEntry>> getCalendar(long userId, int courseId) {
        try {
            String url = config.getLms().getBaseUrl() + "/student/calendar/" + courseId;
            String html = getHtml(userId, url, config.getLms().getBaseUrl() + "/student/my-courses");
            Document doc = Jsoup.parse(html);

            Map<String, List<CalendarEntry>> result = new LinkedHashMap<>();

            List<CalendarEntry> lectures  = parseCalendarTab(doc, "lecture");
            List<CalendarEntry> practices = parseCalendarTab(doc, "practice");

            if (!lectures.isEmpty())  result.put("lecture",  lectures);
            if (!practices.isEmpty()) result.put("practice", practices);

            return result;
        } catch (Exception e) {
            System.err.println("[LmsService] getCalendar error: " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    private List<CalendarEntry> parseCalendarTab(Document doc, String tabId) {
        List<CalendarEntry> entries = new ArrayList<>();
        Element tab = doc.getElementById(tabId);
        if (tab == null) return entries;

        Elements rows = tab.select("tbody tr");
        for (Element row : rows) {
            Elements cols = row.select("td");
            if (cols.size() < 3) continue;

            CalendarEntry entry = new CalendarEntry();

            // col 0: number
            try { entry.setNumber(Integer.parseInt(cols.get(0).text().trim())); }
            catch (Exception ex) { entry.setNumber(0); }

            // col 1: topic text (inside <p>) + file attachments (inside <a>)
            Element topicP = cols.get(1).selectFirst("p");
            entry.setTopic(topicP != null ? topicP.text().trim() : cols.get(1).ownText().trim());

            // Parse all <a href> buttons as file attachments
            List<CalendarEntry.FileAttachment> files = new ArrayList<>();
            for (Element a : cols.get(1).select("a[href]")) {
                String url  = a.attr("href");
                String name = a.text().trim();
                if (!name.isEmpty() && !url.isEmpty()) {
                    files.add(new CalendarEntry.FileAttachment(name, url, detectFileType(url, a)));
                }
            }
            entry.setFiles(files);

            // col 2: date
            entry.setDate(cols.get(2).text().trim());

            entries.add(entry);
        }
        return entries;
    }

    /**
     * Detect file type from URL extension or icon class.
     */
    private String detectFileType(String url, Element a) {
        String lower = url.toLowerCase();
        Element icon = a.selectFirst("i[class]");
        String iconClass = icon != null ? icon.attr("class") : "";

        if (lower.endsWith(".pdf"))                               return "pdf";
        if (lower.endsWith(".pptx") || lower.endsWith(".ppt"))    return "ppt";
        if (lower.endsWith(".docx") || lower.endsWith(".doc"))    return "doc";
        if (lower.endsWith(".mp4")  || lower.endsWith(".avi")
                || lower.endsWith(".mov"))                        return "video";
        if (iconClass.contains("video-camera"))                   return "video";
        if (iconClass.contains("file-powerpoint"))                return "ppt";
        if (iconClass.contains("book"))                           return "pdf";
        // Любая внешняя ссылка (не lms.tuit.uz) — открывать в браузере
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            if (!lower.contains("lms.tuit.uz")) return "url";
        }
        return "file";
    }

    // ─────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────

    public record DownloadedFile(File file, String filename, String contentType) {}

    /**
     * Download a file using user's authenticated LMS session (cookies).
     * Returns a temp file; caller should delete it after use.
     */
    public DownloadedFile downloadFile(long userId, String url, String suggestedName) throws Exception {
        String fullUrl = resolveUrl(url);
        Request req = new Request.Builder()
                .url(fullUrl)
                .header("User-Agent", userAgent())
                .header("Referer", config.getLms().getBaseUrl() + "/student/calendar")
                .build();

        try (Response resp = getClient(userId).newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IllegalStateException("Download failed: HTTP " + resp.code());
            }

            String contentType = resp.header("Content-Type", "application/octet-stream");
            String filename = filenameFromDisposition(resp.header("Content-Disposition"));
            if (filename == null || filename.isBlank()) filename = filenameFromUrl(fullUrl);
            if ((filename == null || filename.isBlank()) && suggestedName != null && !suggestedName.isBlank())
                filename = suggestedName;
            if (filename == null || filename.isBlank()) filename = "file";
            filename = sanitizeFilename(filename);

            String suffix = "";
            int dot = filename.lastIndexOf('.');
            if (dot >= 0 && dot < filename.length() - 1) suffix = filename.substring(dot);
            File tmp = File.createTempFile("lmsbot_", suffix.isBlank() ? ".bin" : suffix);

            try (InputStream in = resp.body().byteStream()) {
                Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return new DownloadedFile(tmp, filename, contentType);
        }
    }

    private String resolveUrl(String url) {
        if (url == null) return "";
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        String base = config.getLms().getBaseUrl();
        if (url.startsWith("/")) return base + url;
        return base + "/" + url;
    }

    private String filenameFromUrl(String fullUrl) {
        try {
            int q = fullUrl.indexOf('?');
            String path = (q >= 0 ? fullUrl.substring(0, q) : fullUrl);
            int slash = path.lastIndexOf('/');
            if (slash >= 0 && slash < path.length() - 1) {
                String name = path.substring(slash + 1);
                name = URLDecoder.decode(name, StandardCharsets.UTF_8);
                return name;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String filenameFromDisposition(String cd) {
        if (cd == null) return null;
        // Handles: filename="x.ext" and filename*=UTF-8''x.ext
        Matcher mStar = Pattern.compile("filename\\*=(?:UTF-8''|utf-8''?)([^;]+)").matcher(cd);
        if (mStar.find()) {
            String v = mStar.group(1).trim();
            v = trimQuotes(v);
            try { return URLDecoder.decode(v, StandardCharsets.UTF_8); } catch (Exception ignored) { return v; }
        }
        Matcher m = Pattern.compile("filename=([^;]+)").matcher(cd);
        if (m.find()) return trimQuotes(m.group(1).trim());
        return null;
    }

    private String trimQuotes(String s) {
        if (s == null) return null;
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))
            return s.substring(1, s.length() - 1);
        return s;
    }

    private String sanitizeFilename(String name) {
        // Windows/Telegram friendly
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "_").trim();
        if (cleaned.isBlank()) return "file";
        if (cleaned.length() > 120) cleaned = cleaned.substring(0, 120);
        return cleaned;
    }

    private String getJson(long userId, String url, String referer) throws Exception {
        Request req = new Request.Builder()
                .url(url)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept", "application/json")
                .header("User-Agent", userAgent())
                .header("Referer", referer)
                .build();
        try (Response resp = getClient(userId).newCall(req).execute()) {
            return resp.body().string();
        }
    }

    private String getHtml(long userId, String url, String referer) throws Exception {
        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", userAgent())
                .header("Referer", referer)
                .build();
        try (Response resp = getClient(userId).newCall(req).execute()) {
            return resp.body().string();
        }
    }

    private String extractCsrf(String html) {
        Pattern p = Pattern.compile("name=\"_token\"\\s+value=\"([^\"]+)\"");
        Matcher m = p.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private String userAgent() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    }

    // ─────────────────────────────────────────────
    //  PROFILE (photo + password)
    // ─────────────────────────────────────────────

    public String getProfilePhotoDataUrl(long userId) {
        try {
            String url = config.getLms().getBaseUrl() + "/profile/password";
            String html = getHtml(userId, url, url);
            Document doc = Jsoup.parse(html);
            Element img = doc.selectFirst("img[src^=data:image]");
            if (img == null) return null;
            String src = img.attr("src");
            return (src != null && src.startsWith("data:image")) ? src : null;
        } catch (Exception e) {
            return null;
        }
    }

    public boolean changePassword(long userId, String oldPassword, String newPassword, String confirmPassword) {
        try {
            OkHttpClient client = getClient(userId);
            String url = config.getLms().getBaseUrl() + "/profile/password";

            // get CSRF from page
            String html;
            try (Response r = client.newCall(new Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent())
                    .header("Referer", url)
                    .build()).execute()) {
                html = r.body() != null ? r.body().string() : "";
            }
            String csrf = extractCsrf(html);
            if (csrf == null) return false;

            RequestBody body = new FormBody.Builder()
                    .add("_token", csrf)
                    .add("old_password", oldPassword != null ? oldPassword : "")
                    .add("password", newPassword != null ? newPassword : "")
                    .add("password_confirmation", confirmPassword != null ? confirmPassword : "")
                    .build();

            Request req = new Request.Builder()
                    .url(url)
                    .post(body)
                    .header("User-Agent", userAgent())
                    .header("Referer", url)
                    .build();

            String respBody;
            int code;
            try (Response resp = client.newCall(req).execute()) {
                code = resp.code();
                respBody = resp.body() != null ? resp.body().string() : "";
            }
            if (code < 200 || code >= 300) return false;

            String lower = respBody.toLowerCase();
            // heuristics: success toast or no validation errors
            if (lower.contains("toast-success") || lower.contains("успеш") || lower.contains("success")) return true;
            if (lower.contains("invalid") || lower.contains("ошиб") || lower.contains("error")) return false;
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    // ─────────────────────────────────────────────
    //  UPLOAD ACTIVITY FILE
    // ─────────────────────────────────────────────

    /**
     * Upload student file to LMS.
     * POST /student/my-courses/upload
     * Fields: _token (CSRF), id (activityId), file (multipart)
     */
    public boolean uploadActivityFile(long userId, int courseId, String activityId, java.io.File file, String filename) {
        try {
            OkHttpClient client = getClient(userId);
            String showUrl   = config.getLms().getBaseUrl() + "/student/my-courses/show/" + courseId;
            String uploadUrl = config.getLms().getBaseUrl() + "/student/my-courses/upload";

            // get CSRF token from course page
            String html;
            try (Response r = client.newCall(new Request.Builder()
                    .url(showUrl).header("User-Agent", userAgent()).build()).execute()) {
                html = r.body().string();
            }
            String csrf = extractCsrf(html);
            if (csrf == null) { System.err.println("[LmsService] uploadActivityFile: no CSRF"); return false; }

            String lc = filename.toLowerCase();
            String mt = "application/octet-stream";
            if      (lc.endsWith(".pdf"))  mt = "application/pdf";
            else if (lc.endsWith(".doc"))  mt = "application/msword";
            else if (lc.endsWith(".docx")) mt = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            else if (lc.endsWith(".ppt"))  mt = "application/vnd.ms-powerpoint";
            else if (lc.endsWith(".pptx")) mt = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            else if (lc.endsWith(".jpg") || lc.endsWith(".jpeg")) mt = "image/jpeg";
            else if (lc.endsWith(".png"))  mt = "image/png";
            else if (lc.endsWith(".zip"))  mt = "application/zip";
            else if (lc.endsWith(".rar"))  mt = "application/x-rar-compressed";

            RequestBody fileBody = RequestBody.create(file, MediaType.parse(mt));
            MultipartBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("_token", csrf)
                    .addFormDataPart("id", activityId)
                    .addFormDataPart("file", filename, fileBody)
                    .build();

            Request req = new Request.Builder()
                    .url(uploadUrl).post(body)
                    .header("User-Agent", userAgent())
                    .header("Referer", showUrl)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .build();

            try (Response resp = client.newCall(req).execute()) {
                String rb = resp.body() != null ? resp.body().string() : "";
                System.out.println("[LmsService] upload " + resp.code() + ": " + rb);
                return resp.isSuccessful();
            }
        } catch (Exception e) {
            System.err.println("[LmsService] uploadActivityFile error: " + e.getMessage());
            return false;
        }
    }
}