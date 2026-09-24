package uz.tuit.lmsbot.service;

import uz.tuit.lmsbot.config.AppConfig;
import uz.tuit.lmsbot.model.CalendarEntry;
import uz.tuit.lmsbot.model.Course;
import uz.tuit.lmsbot.model.ScheduleEvent;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Расписание на весь семестр.
 *
 * /student/schedule/load отдаёт не календарь, а недельную сетку: у каждой пары
 * важны только день недели и время. Поле type — сторона недели: 1 — каждую неделю,
 * 2 и 3 — через неделю (2 — в недели той же чётности, что и первая неделя семестра).
 *
 * Сетку протягиваем с первой по последнюю неделю семестра, а каждую пару обрезаем
 * по плану занятий её предмета (/student/calendar/{id}): первая и последняя дата
 * в плане лекций — границы лекций, в плане практик — границы практик и т.д.
 * Так предметы заканчиваются каждый в свой срок.
 *
 * Заголовок пары: «(A-301) Предмет-PSA201». Вид занятия — по потоку в конце:
 * «PSA201» (оканчивается цифрами) — лекция, «PSA201-2» — практика,
 * «PSA201-1a» (две позиции после тире) — лабораторная.
 */
public class SemesterSchedule {

    public static final String LECTURE = "lecture";
    public static final String PRACTICE = "practice";
    public static final String LAB = "lab";

    public record Lesson(LocalDate date, String time, long ts, String subject, String kind,
                         String room, String teacher, String stream, String topic,
                         Integer courseId, boolean last) {}

    /** Все пары семестра по порядку; start — понедельник первой недели. */
    public record Result(List<Lesson> lessons, LocalDate start, LocalDate end) {
        public int weekCount() {
            return (int) (ChronoUnit.WEEKS.between(start, monday(end)) + 1);
        }

        public LocalDate weekStart(int index) { return start.plusWeeks(index); }

        public int weekOf(LocalDate d) {
            if (d.isBefore(start)) return 0;
            int w = (int) ChronoUnit.WEEKS.between(start, monday(d));
            return Math.min(w, weekCount() - 1);
        }

        public List<Lesson> on(LocalDate d) {
            List<Lesson> out = new ArrayList<>();
            for (Lesson l : lessons) if (l.date().equals(d)) out.add(l);
            return out;
        }
    }

    private static final long CACHE_MS = 3L * 60 * 60 * 1000;
    private static final Pattern STREAM = Pattern.compile("(\\p{L}{2,6}\\d{2,4}(?:-\\w{1,3})?)\\s*$");
    private static final Pattern ROOM_PAREN = Pattern.compile("\\(([^)]*)\\)");
    private static final Pattern ROOM_CODE = Pattern.compile("(?<![\\p{L}\\d])(\\p{L}{1,3}-\\d{2,4}\\p{L}?)(?![\\p{L}\\d])");
    private static final Pattern DATE_DMY = Pattern.compile("(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})");
    private static final Pattern DATE_YMD = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");

    private final LmsService lms;
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "semester-schedule");
        t.setDaemon(true);
        return t;
    });

    private record Cached(long ts, Result result) {}
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public SemesterSchedule(LmsService lms) {
        this.lms = lms;
    }

    public void invalidate(long userId) {
        cache.keySet().removeIf(k -> k.startsWith(userId + "|"));
    }

    /** Кэшированное расписание семестра; null, если LMS не отдала ни одной пары. */
    public Result get(long userId, int semesterId, boolean fresh) {
        String key = userId + "|" + semesterId;
        Cached c = cache.get(key);
        if (!fresh && c != null && System.currentTimeMillis() - c.ts() < CACHE_MS) return c.result();
        Result r = build(userId, semesterId);
        if (r != null) cache.put(key, new Cached(System.currentTimeMillis(), r));
        return r;
    }

    // ─────────────────────────────────────────────
    //  BUILD
    // ─────────────────────────────────────────────

    /** Пара недельной сетки. side: 1 — каждую неделю, 2/3 — через неделю. */
    record Slot(DayOfWeek day, LocalTime time, LocalDate sample, String subject, String kind,
                String room, String teacher, String stream, int side) {}

    /** Что известно о предмете из плана занятий: границы каждого вида занятий и темы по датам. */
    private static final class Plan {
        final Map<String, LocalDate> start = new HashMap<>();
        final Map<String, LocalDate> end = new HashMap<>();
        final Map<String, Map<LocalDate, String>> topics = new HashMap<>();
    }

    private Result build(long userId, int semesterId) {
        if (lms.isTeacher(userId)) {
            try {
                return buildTeacher(userId, semesterId);
            } catch (Exception e) {
                System.err.println("[SemesterSchedule] teacher " + userId + ": " + e.getMessage());
                return null;
            }
        }
        List<Slot> slots = new ArrayList<>();
        for (ScheduleEvent ev : lms.getSchedule(userId, semesterId)) {
            Slot s = parseSlot(ev);
            if (s != null) slots.add(s);
        }
        if (slots.isEmpty()) return null;

        List<Course> courses = lms.getMyCourses(userId, semesterId);
        Map<Integer, Plan> plans = loadPlans(userId, courses);

        LocalDate planStart = null, planEnd = null;
        for (Plan p : plans.values()) {
            for (LocalDate d : p.start.values()) if (planStart == null || d.isBefore(planStart)) planStart = d;
            for (LocalDate d : p.end.values())   if (planEnd == null || d.isAfter(planEnd)) planEnd = d;
        }

        LocalDate sampleMin = slots.stream().map(Slot::sample).min(LocalDate::compareTo).orElseThrow();
        LocalDate first = monday(planStart != null ? planStart : sampleMin);
        LocalDate end = planEnd != null ? planEnd : fallbackEnd(sampleMin);

        Map<Slot, Integer> courseOf = new HashMap<>();
        for (Slot s : slots) {
            Integer id = matchCourse(s.subject(), courses);
            if (id != null) courseOf.put(s, id);
        }

        List<Lesson> lessons = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(end); d = d.plusDays(1)) {
            // Первая неделя семестра — сторона 2, следующая — 3, и так по очереди.
            int side = ChronoUnit.WEEKS.between(first, monday(d)) % 2 == 0 ? 2 : 3;
            for (Slot s : slots) {
                if (s.day() != d.getDayOfWeek()) continue;
                if (s.side() != 1 && s.side() != side) continue;

                Integer courseId = courseOf.get(s);
                Plan plan = courseId != null ? plans.get(courseId) : null;
                if (plan != null) {
                    LocalDate kStart = plan.start.get(s.kind());
                    LocalDate kEnd = plan.end.get(s.kind());
                    // Пара идёт только в границах своего плана: предмет закончился — пары нет.
                    if (kEnd != null && d.isAfter(kEnd)) continue;
                    if (kStart != null && d.isBefore(monday(kStart))) continue;
                }

                long ts = d.atTime(s.time()).atZone(AppConfig.LMS_ZONE).toInstant().toEpochMilli();
                String topic = plan != null ? plan.topics.getOrDefault(s.kind(), Map.of()).get(d) : null;
                lessons.add(new Lesson(d, s.time().toString().substring(0, 5), ts, s.subject(), s.kind(),
                        s.room(), teacherFor(courses, courseId, s.stream()), s.stream(), topic, courseId, false));
            }
        }
        lessons.sort(Comparator.comparingLong(Lesson::ts));

        // Помечаем последнее занятие каждого вида по каждому предмету.
        Map<String, Integer> lastIdx = new HashMap<>();
        for (int i = 0; i < lessons.size(); i++) lastIdx.put(norm(lessons.get(i).subject()) + "|" + lessons.get(i).kind(), i);
        Set<Integer> lastSet = new HashSet<>(lastIdx.values());
        List<Lesson> marked = new ArrayList<>(lessons.size());
        for (int i = 0; i < lessons.size(); i++) {
            Lesson l = lessons.get(i);
            marked.add(lastSet.contains(i) ? new Lesson(l.date(), l.time(), l.ts(), l.subject(), l.kind(), l.room(),
                    l.teacher(), l.stream(), l.topic(), l.courseId(), true) : l);
        }

        LocalDate lastDay = marked.isEmpty() ? end : marked.get(marked.size() - 1).date();
        return new Result(marked, first, lastDay.isBefore(first) ? first : lastDay);
    }

    /**
     * Расписание преподавателя. У него точнее, чем у студента: календарный план потока
     * (/teacher/calendar/show/{id}) содержит дату каждого занятия, а недельная сетка
     * даёт к ней время и аудиторию. Пары ставим на даты плана; поток без плана
     * протягиваем по сетке, как у студентов.
     */
    private Result buildTeacher(long userId, int semesterId) throws Exception {
        TeacherService ts = lms.teacher();
        List<Slot> slots = new ArrayList<>();
        for (ScheduleEvent ev : ts.schedule(userId, semesterId)) {
            Slot s = parseSlot(ev);
            if (s != null) slots.add(s);
        }
        List<TeacherService.TCourse> courses = ts.courses(userId, semesterId);
        if (slots.isEmpty() && courses.isEmpty()) return null;

        Map<Integer, TeacherService.TCalendar> calendars = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>();
        for (TeacherService.TCourse c : courses) {
            futures.add(pool.submit(() -> {
                try {
                    calendars.put(c.id(), ts.calendar(userId, c.id()));
                } catch (Exception e) {
                    System.err.println("[SemesterSchedule] calendar " + c.id() + ": " + e.getMessage());
                }
            }));
        }
        for (Future<?> f : futures) {
            try { f.get(60, TimeUnit.SECONDS); } catch (Exception ignored) {}
        }

        List<Lesson> lessons = new ArrayList<>();
        Set<String> planned = new HashSet<>();
        LocalDate min = null, max = null;
        for (TeacherService.TCourse c : courses) {
            TeacherService.TCalendar cal = calendars.get(c.id());
            if (cal == null) continue;
            List<Slot> own = new ArrayList<>();
            for (Slot s : slots) if (s.stream() != null && s.stream().equalsIgnoreCase(cal.stream())) own.add(s);
            String kind = teacherKind(c.type(), cal.stream());
            for (TeacherService.TLesson l : cal.lessons()) {
                LocalDate d = parseDate(l.date());
                if (d == null) continue;
                Slot slot = null;
                for (Slot s : own) if (s.day() == d.getDayOfWeek()) { slot = s; break; }
                // Перенесённое занятие может выпасть на другой день — время берём у потока.
                if (slot == null && !own.isEmpty()) slot = own.get(0);
                if (slot == null) continue;
                planned.add(cal.stream().toLowerCase(Locale.ROOT));
                long ts0 = d.atTime(slot.time()).atZone(AppConfig.LMS_ZONE).toInstant().toEpochMilli();
                lessons.add(new Lesson(d, slot.time().toString().substring(0, 5), ts0, c.subject(), kind,
                        slot.room(), "", cal.stream(), l.topic(), c.id(), false));
                if (min == null || d.isBefore(min)) min = d;
                if (max == null || d.isAfter(max)) max = d;
            }
        }

        // Потоки, у которых календарный план ещё не сформирован, — по недельной сетке.
        List<Slot> rest = new ArrayList<>();
        for (Slot s : slots) if (s.stream() == null || !planned.contains(s.stream().toLowerCase(Locale.ROOT))) rest.add(s);
        if (!rest.isEmpty()) {
            LocalDate sampleMin = rest.stream().map(Slot::sample).min(LocalDate::compareTo).orElseThrow();
            LocalDate first = monday(min != null && min.isBefore(sampleMin) ? min : sampleMin);
            LocalDate end = max != null ? max : fallbackEnd(sampleMin);
            for (LocalDate d = first; !d.isAfter(end); d = d.plusDays(1)) {
                int side = ChronoUnit.WEEKS.between(first, monday(d)) % 2 == 0 ? 2 : 3;
                for (Slot s : rest) {
                    if (s.day() != d.getDayOfWeek() || (s.side() != 1 && s.side() != side)) continue;
                    Integer courseId = null;
                    String kind = s.kind();
                    for (TeacherService.TCourse c : courses) {
                        TeacherService.TCalendar cal = calendars.get(c.id());
                        if (cal != null && s.stream() != null && s.stream().equalsIgnoreCase(cal.stream())) {
                            courseId = c.id();
                            kind = teacherKind(c.type(), s.stream());
                        }
                    }
                    long ts0 = d.atTime(s.time()).atZone(AppConfig.LMS_ZONE).toInstant().toEpochMilli();
                    lessons.add(new Lesson(d, s.time().toString().substring(0, 5), ts0, s.subject(), kind,
                            s.room(), "", s.stream(), null, courseId, false));
                }
            }
        }
        if (lessons.isEmpty()) return null;
        lessons.sort(Comparator.comparingLong(Lesson::ts));

        Map<String, Integer> lastIdx = new HashMap<>();
        for (int i = 0; i < lessons.size(); i++)
            lastIdx.put(norm(lessons.get(i).subject()) + "|" + lessons.get(i).stream(), i);
        Set<Integer> lastSet = new HashSet<>(lastIdx.values());
        List<Lesson> marked = new ArrayList<>(lessons.size());
        for (int i = 0; i < lessons.size(); i++) {
            Lesson l = lessons.get(i);
            marked.add(lastSet.contains(i) ? new Lesson(l.date(), l.time(), l.ts(), l.subject(), l.kind(), l.room(),
                    l.teacher(), l.stream(), l.topic(), l.courseId(), true) : l);
        }
        LocalDate first = monday(marked.get(0).date());
        return new Result(marked, first, marked.get(marked.size() - 1).date());
    }

    /** Вид занятия по типу потока из «Мои предметы» («Лекция», «Практика», «Лаборатория»…). */
    static String teacherKind(String type, String stream) {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        if (t.contains("лек") || t.contains("lec") || t.contains("ma'ruza") || t.contains("maruza") || t.contains("маъруза")) return LECTURE;
        if (t.contains("лаб") || t.contains("lab")) return LAB;
        if (t.contains("прак") || t.contains("prac") || t.contains("amal") || t.contains("семин") || t.contains("sem")) return PRACTICE;
        return kindOf(stream);
    }

    /** Преподаватель пары: в «Мои предметы» у каждого преподавателя указан его поток. */
    static String teacherFor(List<Course> courses, Integer courseId, String stream) {
        if (courseId == null || stream == null) return "";
        for (Course c : courses) {
            if (c.getId() != courseId || c.getTeachers() == null || c.getStreams() == null) continue;
            String[] names = c.getTeachers().split("###");
            String[] streams = c.getStreams().split("###");
            for (int i = 0; i < Math.min(names.length, streams.length); i++) {
                if (streams[i].trim().equalsIgnoreCase(stream)) return names[i].trim();
            }
        }
        return "";
    }

    /** Планов занятий нет — тянем до конца учебного полугодия. */
    private static LocalDate fallbackEnd(LocalDate from) {
        int m = from.getMonthValue();
        if (m >= 8) return LocalDate.of(from.getYear(), 12, 31);
        if (m == 1) return LocalDate.of(from.getYear(), 1, 31);
        return LocalDate.of(from.getYear(), 6, 30);
    }

    private Map<Integer, Plan> loadPlans(long userId, List<Course> courses) {
        Map<Integer, Plan> plans = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>();
        for (Course c : courses) {
            futures.add(pool.submit(() -> {
                Map<String, List<CalendarEntry>> cal = lms.getCalendar(userId, c.getId());
                Plan p = new Plan();
                for (Map.Entry<String, List<CalendarEntry>> tab : cal.entrySet()) {
                    String kind = tabKind(tab.getKey());
                    Map<LocalDate, String> topics = p.topics.computeIfAbsent(kind, k -> new HashMap<>());
                    for (CalendarEntry e : tab.getValue()) {
                        LocalDate d = parseDate(e.getDate());
                        if (d == null) continue;
                        topics.putIfAbsent(d, e.getTopic());
                        p.end.merge(kind, d, (a, b) -> a.isAfter(b) ? a : b);
                        p.start.merge(kind, d, (a, b) -> a.isBefore(b) ? a : b);
                    }
                }
                if (!p.end.isEmpty()) plans.put(c.getId(), p);
            }));
        }
        for (Future<?> f : futures) {
            try { f.get(60, TimeUnit.SECONDS); } catch (Exception ignored) {}
        }
        return plans;
    }

    // ─────────────────────────────────────────────
    //  PARSING
    // ─────────────────────────────────────────────

    static Slot parseSlot(ScheduleEvent ev) {
        LocalDateTime start;
        try { start = LocalDateTime.parse(ev.getStart().trim().replace(" ", "T")); }
        catch (Exception e) { return null; }

        String raw = ev.getTitle() == null ? "" : ev.getTitle();
        String flat = raw.replace("/", " ").replace("\n", " ").replaceAll("\\s+", " ").trim();

        String stream = null;
        Matcher sm = STREAM.matcher(flat);
        if (sm.find()) {
            stream = sm.group(1);
            flat = flat.substring(0, sm.start()).trim();
        }

        String room = "";
        Matcher rm = ROOM_PAREN.matcher(flat);
        if (rm.find()) {
            room = rm.group(1).trim();
            flat = (flat.substring(0, rm.start()) + " " + flat.substring(rm.end())).trim();
        } else {
            Matcher rc = ROOM_CODE.matcher(flat);
            if (rc.find()) {
                room = rc.group(1);
                flat = (flat.substring(0, rc.start()) + " " + flat.substring(rc.end())).trim();
            }
        }
        String subject = flat.replaceAll("^[-–,|:\\s]+|[-–,|:\\s]+$", "").trim();
        if (subject.isEmpty()) subject = raw.replace("\n", " ").trim();

        return new Slot(start.getDayOfWeek(), start.toLocalTime(), start.toLocalDate(), subject,
                kindOf(stream), room, "", stream, ev.getType() == 2 || ev.getType() == 3 ? ev.getType() : 1);
    }

    /** «PSA201» — лекция, «PSA201-2» — практика, «PSA201-1a» — лабораторная. */
    static String kindOf(String stream) {
        if (stream == null || !stream.contains("-")) return LECTURE;
        String suffix = stream.substring(stream.lastIndexOf('-') + 1);
        return suffix.length() >= 2 ? LAB : PRACTICE;
    }

    static String tabKind(String key) {
        String k = key == null ? "" : key.toLowerCase(Locale.ROOT);
        if (k.contains("lec") || k.contains("лек") || k.contains("ruza") || k.contains("руза")) return LECTURE;
        if (k.contains("lab")) return LAB;
        return PRACTICE;
    }

    static LocalDate parseDate(String text) {
        if (text == null) return null;
        try {
            Matcher ymd = DATE_YMD.matcher(text);
            if (ymd.find()) return LocalDate.of(Integer.parseInt(ymd.group(1)), Integer.parseInt(ymd.group(2)), Integer.parseInt(ymd.group(3)));
            Matcher dmy = DATE_DMY.matcher(text);
            if (dmy.find()) return LocalDate.of(Integer.parseInt(dmy.group(3)), Integer.parseInt(dmy.group(2)), Integer.parseInt(dmy.group(1)));
        } catch (Exception ignored) {}
        return null;
    }

    static LocalDate monday(LocalDate d) {
        return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** Предмет из сетки → предмет из «Мои предметы»: точное совпадение, вхождение, затем общие слова. */
    static Integer matchCourse(String subject, List<Course> courses) {
        String s = norm(subject);
        if (s.isEmpty()) return null;
        for (Course c : courses) if (norm(c.getSubject()).equals(s)) return c.getId();
        for (Course c : courses) {
            String n = norm(c.getSubject());
            if (!n.isEmpty() && (n.contains(s) || s.contains(n))) return c.getId();
        }
        Set<String> words = new HashSet<>(Arrays.asList(s.split(" ")));
        Integer best = null;
        double bestScore = 0.5;
        for (Course c : courses) {
            Set<String> other = new HashSet<>(Arrays.asList(norm(c.getSubject()).split(" ")));
            Set<String> inter = new HashSet<>(words);
            inter.retainAll(other);
            Set<String> union = new HashSet<>(words);
            union.addAll(other);
            double score = union.isEmpty() ? 0 : (double) inter.size() / union.size();
            if (score > bestScore) { bestScore = score; best = c.getId(); }
        }
        return best;
    }
}
