package uz.tuit.lmsbot.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import uz.tuit.lmsbot.model.StudentInfo;
import uz.tuit.lmsbot.model.StudyPlanSubject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Список студентов, которые входили в бота или мини-приложение, — для панели администратора.
 *
 * Список лежит в ~/.tuit-lms-bot/students.json и вместе с сессиями попадает
 * в зашифрованную резервную копию бота (StateBackup): диск Render эфемерный.
 */
public class StudentRegistry {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Student {
        public long telegramId;
        public String tgName;
        public String tgUsername;
        public String login;
        public String fullName;
        public String recordBook;
        public String group;
        public String direction;
        public String course;
        public String gender;
        public String birthDate;
        public String curator;
        public String studyType;
        public String language;
        public Double gpa;
        /** student или teacher — по меню LMS. */
        public String role;
        /** Кафедра преподавателя. */
        public String department;
        public long firstSeen;
        public long lastSeen;
        /** Когда профиль последний раз перечитывался из LMS. */
        public long profileUpdated;
    }

    /** Профиль из LMS перечитываем не чаще, чем раз в 12 часов. */
    private static final long PROFILE_TTL_MS = 12L * 60 * 60 * 1000;
    /** lastSeen пишем на диск не на каждое сообщение, а если прошло больше 5 минут. */
    private static final long SEEN_GRANULARITY_MS = 5L * 60 * 1000;
    /** Резервная копия — не чаще раза в 3 минуты, изменения копятся. */
    private static final long BACKUP_DELAY_MIN = 3;

    private final LmsService lms;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<Long, Student> students = new ConcurrentHashMap<>();
    private final Set<Long> refreshing = ConcurrentHashMap.newKeySet();
    private final Path file;
    private final ExecutorService worker = Executors.newFixedThreadPool(2, daemon("student-registry"));
    private final ScheduledExecutorService saver = Executors.newSingleThreadScheduledExecutor(daemon("student-backup"));
    private volatile boolean dirty;
    private volatile Consumer<byte[]> backup;

    public StudentRegistry(LmsService lms) {
        this.lms = lms;
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) home = ".";
        this.file = Paths.get(home, ".tuit-lms-bot", "students.json");
        loadFile();
        saver.scheduleWithFixedDelay(this::flush, BACKUP_DELAY_MIN, BACKUP_DELAY_MIN, TimeUnit.MINUTES);
    }

    private static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    public void setBackup(Consumer<byte[]> backup) {
        this.backup = backup;
    }

    /** Поднимает список из резервной копии, если в ней больше данных, чем на диске. */
    public void restore(byte[] json) {
        if (json == null || json.length == 0) return;
        try {
            List<Student> list = mapper.readValue(json, new TypeReference<List<Student>>() {});
            int added = 0;
            for (Student s : list) {
                Student cur = students.get(s.telegramId);
                if (cur == null || cur.lastSeen < s.lastSeen) {
                    students.put(s.telegramId, s);
                    added++;
                }
            }
            if (added > 0) {
                saveFile();
                System.out.println("👥 Студентов из резервной копии: " + added + " (всего " + students.size() + ")");
            }
        } catch (Exception e) {
            System.err.println("[StudentRegistry] restore: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    //  EVENTS
    // ─────────────────────────────────────────────

    /** Успешный вход: создаём запись, запоминаем логин и сразу тянем профиль. */
    public void onLogin(long uid, String login, String tgName, String tgUsername) {
        Student s = students.computeIfAbsent(uid, this::create);
        if (login != null && !login.isBlank()) s.login = login.trim();
        tg(s, tgName, tgUsername);
        s.lastSeen = System.currentTimeMillis();
        s.profileUpdated = 0;
        dirty = true;
        refreshAsync(uid);
    }

    /**
     * Любое действие вошедшего пользователя. Тех, кто вошёл до появления списка,
     * добавляем при первом же обращении.
     */
    public void onSeen(long uid, String tgName, String tgUsername) {
        long now = System.currentTimeMillis();
        Student s = students.computeIfAbsent(uid, this::create);
        tg(s, tgName, tgUsername);
        if (now - s.lastSeen > SEEN_GRANULARITY_MS) {
            s.lastSeen = now;
            dirty = true;
        }
        if (now - s.profileUpdated > PROFILE_TTL_MS) refreshAsync(uid);
    }

    private Student create(long uid) {
        Student s = new Student();
        s.telegramId = uid;
        s.firstSeen = System.currentTimeMillis();
        dirty = true;
        return s;
    }

    private void tg(Student s, String name, String username) {
        if (name != null && !name.isBlank() && !name.equals(s.tgName)) { s.tgName = name.trim(); dirty = true; }
        if (username != null && !username.isBlank() && !username.equals(s.tgUsername)) { s.tgUsername = username; dirty = true; }
    }

    private void refreshAsync(long uid) {
        if (!refreshing.add(uid)) return;
        worker.submit(() -> {
            try {
                refreshProfile(uid);
            } finally {
                refreshing.remove(uid);
            }
        });
    }

    private void refreshProfile(long uid) {
        if (!lms.isLoggedIn(uid)) return;
        Student s = students.get(uid);
        if (s == null) return;
        try {
            if (lms.isTeacher(uid)) {
                refreshTeacher(uid, s);
                return;
            }
            s.role = "student";
            StudentInfo info = lms.getStudentInfo(uid);
            if (info != null && info.getFullName() != null) {
                s.fullName = info.getFullName();
                s.recordBook = info.getRecordBook();
                s.group = info.getGroup();
                s.direction = info.getDirection();
                s.course = info.getCourse();
                s.gender = info.getGender();
                s.birthDate = info.getBirthDate();
                s.curator = info.getCurator();
                s.studyType = info.getStudyType();
                s.language = info.getLanguage();
            }
            Double g = gpa(lms.getStudyPlan(uid));
            if (g != null) s.gpa = g;
            s.profileUpdated = System.currentTimeMillis();
            dirty = true;
        } catch (Exception e) {
            System.err.println("[StudentRegistry] refresh " + uid + ": " + e.getMessage());
        }
    }

    /** Преподаватель: ФИО, пол, дата рождения и кафедра со страницы «Информация». */
    private void refreshTeacher(long uid, Student s) throws Exception {
        TeacherService.TeacherInfo info = lms.teacher().info(uid);
        s.role = "teacher";
        if (info.fullName() != null) s.fullName = info.fullName();
        for (String[] f : info.fields()) {
            String label = f[0].toLowerCase(java.util.Locale.ROOT);
            if (label.contains("кафедр") || label.contains("kafedra") || label.contains("department")) s.department = f[1];
            else if (label.startsWith("дата рожд") || label.contains("tug") || label.contains("birth")) s.birthDate = f[1];
            else if (label.equals("пол") || label.contains("jins") || label.contains("gender")) s.gender = f[1];
        }
        s.profileUpdated = System.currentTimeMillis();
        dirty = true;
    }

    /** GPA по оценённым предметам — так же, как считает бот. */
    static Double gpa(List<StudyPlanSubject> plan) {
        long credits = 0, points = 0;
        for (StudyPlanSubject s : plan) {
            if (s.getGrade() == null) continue;
            credits += s.getCredits();
            points += (long) s.getGrade() * s.getCredits();
        }
        return credits > 0 ? Math.round(points * 100.0 / credits) / 100.0 : null;
    }

    public List<Student> all() {
        return new ArrayList<>(students.values());
    }

    // ─────────────────────────────────────────────
    //  STORAGE
    // ─────────────────────────────────────────────

    /** Перечитать список с диска — после восстановления резервной копии. */
    public void reloadFromDisk() {
        loadFile();
    }

    private void loadFile() {
        try {
            if (!Files.exists(file)) return;
            List<Student> list = mapper.readValue(Files.readAllBytes(file), new TypeReference<List<Student>>() {});
            for (Student s : list) students.put(s.telegramId, s);
        } catch (Exception e) {
            System.err.println("[StudentRegistry] load: " + e.getMessage());
        }
    }

    private byte[] saveFile() {
        try {
            List<Student> list = all();
            list.sort(Comparator.comparingLong(s -> s.telegramId));
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(list);
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("students.json.tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return bytes;
        } catch (Exception e) {
            System.err.println("[StudentRegistry] save: " + e.getMessage());
            return null;
        }
    }

    /** Сохраняет накопившиеся изменения на диск и в резервную копию. */
    public void flush() {
        if (!dirty) return;
        dirty = false;
        byte[] bytes = saveFile();
        Consumer<byte[]> b = backup;
        if (bytes != null && b != null) {
            try { b.accept(bytes); } catch (Exception e) {
                dirty = true;
                System.err.println("[StudentRegistry] backup: " + e.getMessage());
            }
        }
    }
}
