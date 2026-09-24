package uz.tuit.lmsbot.bot;

import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import uz.tuit.lmsbot.config.AppConfig;
import uz.tuit.lmsbot.model.ScheduleEvent;
import uz.tuit.lmsbot.model.StudyPlanSubject;
import uz.tuit.lmsbot.service.LmsService;
import uz.tuit.lmsbot.service.SemesterSchedule;
import uz.tuit.lmsbot.service.TeacherService;
import uz.tuit.lmsbot.service.TeacherService.*;

import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Кабинет преподавателя (и тьютора) в боте.
 *
 * Всё, что относится к LMS, — статусы, подписи полей, инструкции, виды материалов,
 * сроки — берётся со страниц lms.tuit.uz и показывается как есть (на языке аккаунта
 * LMS). На языке пользователя бота только навигация.
 *
 * Кнопки — префикс «t:», выбор семестра — «tc_» (предметы) и «tf_» (итоговые):
 * клавиатура семестров бота строит данные вида prefix + id.
 */
public class TeacherBot {

    private final LmsBot bot;
    private final LmsService lms;
    private final TeacherService ts;

    TeacherBot(LmsBot bot, LmsService lms) {
        this.bot = bot;
        this.lms = lms;
        this.ts = lms.teacher();
    }

    // ─────────────────────────────────────────────
    //  STATE
    // ─────────────────────────────────────────────

    private final Map<Long, List<TCourse>> courses = new ConcurrentHashMap<>();
    private final Map<Long, Integer> semester = new ConcurrentHashMap<>();
    private final Map<Long, Map<Integer, GradeSheet>> sheets = new ConcurrentHashMap<>();

    private static final class GradeCtx {
        int courseId, activityId, studentId;
        GradeForm form;
        Map<String, String> values;
        String moduleGrade;
    }

    /** Открытая карточка оценивания (для кнопок файла, «Убрать оценку»). */
    private final Map<Long, GradeCtx> grading = new ConcurrentHashMap<>();
    /**
     * Оценка, которую сейчас вводят (состояния T_GRADE → T_GCOMMENT). Отдельно от открытой
     * карточки: листание к другому студенту посреди ввода не должно менять адресата.
     */
    private final Map<Long, GradeCtx> pendingGrade = new ConcurrentHashMap<>();

    private static final class ActDraft {
        int courseId;
        TActivities meta;
        String name, deadline, max;
        List<String[]> criteria;
        File file;
        String fileName;
    }

    private final Map<Long, ActDraft> actDrafts = new ConcurrentHashMap<>();

    private static final class AppealDraft {
        AppealPage page;
        Option stream;
        List<Select2Item> lessons = List.of();
        Select2Item lesson;
        List<Select2Item> students = List.of();
        Select2Item student;
        String pair;
    }

    private final Map<Long, AppealDraft> appeals = new ConcurrentHashMap<>();

    private static final class MatCtx {
        int subjectId, semesterId;
        String subject, lang, lessonType;
        MaterialPage page;
        List<TTopic> topics = List.of();
        int topicId;
        String contentType;
    }

    private final Map<Long, MatCtx> materials = new ConcurrentHashMap<>();
    private final Map<Long, List<TSubject>> subjects = new ConcurrentHashMap<>();

    private final Map<Long, Map<Integer, TGroup>> groups = new ConcurrentHashMap<>();
    private final Map<Long, Map<Integer, List<TStudent>>> groupStudents = new ConcurrentHashMap<>();
    private final Map<Long, Map<Integer, TutorStudent>> tutorStudents = new ConcurrentHashMap<>();
    private final Map<Long, Map<Integer, Integer>> studentGroup = new ConcurrentHashMap<>();

    /** Сбрасывает всё, что относится к прежней сессии (вход, выход, смена аккаунта). */
    void reset(long uid) {
        courses.remove(uid);
        semester.remove(uid);
        sheets.remove(uid);
        grading.remove(uid);
        pendingGrade.remove(uid);
        activityCache.remove(uid);
        dropDraft(uid);
        appeals.remove(uid);
        materials.remove(uid);
        subjects.remove(uid);
        groups.remove(uid);
        groupStudents.remove(uid);
        tutorStudents.remove(uid);
        studentGroup.remove(uid);
        lastScan.remove(uid);
        ts.forget(uid);
    }

    private void dropDraft(long uid) {
        ActDraft d = actDrafts.remove(uid);
        if (d != null && d.file != null) d.file.delete();
    }

    // ─────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────

    private String T(long uid, String ru, String uz, String uzc, String en) {
        return bot.tr(uid, ru, uz, uzc, en);
    }

    private String esc(String s) {
        return bot.esc(s);
    }

    private InlineKeyboardButton btn(String text, String data) {
        return bot.inlineBtn(text, data);
    }

    private InlineKeyboardMarkup kb(List<List<InlineKeyboardButton>> rows) {
        return bot.markup(rows);
    }

    private static String cut(String s, int n) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > n ? s.substring(0, n - 1) + "…" : s;
    }

    /** Telegram режет сообщения длиннее 4096 символов — длинные списки укорачиваем. */
    private static String fit(String html) {
        if (html.length() <= 4000) return html;
        int cut = html.lastIndexOf("\n", 3900);
        String head = html.substring(0, cut > 0 ? cut : 3900);
        // Не оставляем незакрытую цитату.
        int open = head.lastIndexOf("<blockquote"), close = head.lastIndexOf("</blockquote>");
        if (open > close) head += "</blockquote>";
        return head + "\n…";
    }

    private void show(long chatId, Integer messageId, String text, InlineKeyboardMarkup k) {
        if (messageId != null) bot.edit(chatId, messageId, fit(text), k);
        else bot.send(chatId, fit(text), k);
    }

    private interface Job {
        void run() throws Exception;
    }

    /** Поход в LMS в фоне; сбой — понятное сообщение вместо тишины. */
    private void async(long chatId, long uid, Job job) {
        bot.executor.submit(() -> {
            try {
                job.run();
            } catch (Exception e) {
                System.err.println("[TeacherBot] " + uid + ": " + e);
                bot.send(chatId, T(uid,
                        "⚠️ <b>LMS не ответила</b>\n\n<blockquote>" + esc(String.valueOf(e.getMessage())) + "</blockquote>\nПопробуйте ещё раз чуть позже.",
                        "⚠️ <b>LMS javob bermadi</b>\n\n<blockquote>" + esc(String.valueOf(e.getMessage())) + "</blockquote>\nBirozdan keyin qayta urinib ko'ring.",
                        "⚠️ <b>LMS жавоб бермади</b>\n\n<blockquote>" + esc(String.valueOf(e.getMessage())) + "</blockquote>\nБироздан кейин қайта уриниб кўринг.",
                        "⚠️ <b>LMS did not respond</b>\n\n<blockquote>" + esc(String.valueOf(e.getMessage())) + "</blockquote>\nPlease try again a bit later."), null);
            }
        });
    }

    private void loading(long chatId, long uid) {
        bot.sendProgress(chatId, T(uid, "⏳ Загружаю из LMS...", "⏳ LMS dan yuklanmoqda...", "⏳ LMS дан юкланмоқда...", "⏳ Loading from LMS..."));
    }

    private String errors(FormResult r) {
        StringBuilder sb = new StringBuilder();
        for (String e : r.errors()) sb.append("• ").append(esc(e)).append("\n");
        return sb.toString().trim();
    }

    private void sendFile(long chatId, long uid, String url, String name) {
        bot.sendProgress(chatId, T(uid, "⏳ Загружаю файл...", "⏳ Fayl yuklanmoqda...", "⏳ Файл юкланмоқда...", "⏳ Loading file..."));
        async(chatId, uid, () -> {
            File tmp = null;
            try {
                LmsService.DownloadedFile dl = lms.downloadFile(uid, url, name);
                tmp = dl.file();
                bot.dropProgress(chatId);
                SendDocument doc = new SendDocument();
                doc.setChatId(String.valueOf(chatId));
                doc.setDocument(new InputFile(tmp, dl.filename()));
                bot.execute(doc);
            } finally {
                if (tmp != null) tmp.delete();
            }
        });
    }

    private int currentSemester(long uid) {
        Integer s = semester.get(uid);
        return s != null ? s : bot.getDefaultSemesterId(uid);
    }

    private List<TCourse> coursesOf(long uid) throws Exception {
        List<TCourse> list = courses.get(uid);
        if (list != null) return list;
        int sem = currentSemester(uid);
        if (sem <= 0) return List.of();
        list = ts.courses(uid, sem);
        courses.put(uid, list);
        return list;
    }

    private TCourse course(long uid, int id) throws Exception {
        for (TCourse c : coursesOf(uid)) if (c.id() == id) return c;
        return null;
    }

    private String courseTitle(TCourse c) {
        if (c == null) return "";
        return c.subject() + (c.stream() != null ? " (" + c.stream() + ")" : "");
    }

    private GradeSheet sheet(long uid, int courseId, boolean fresh) throws Exception {
        Map<Integer, GradeSheet> m = sheets.computeIfAbsent(uid, k -> new ConcurrentHashMap<>());
        GradeSheet s = fresh ? null : m.get(courseId);
        if (s == null) {
            s = ts.gradeSheet(uid, courseId);
            m.put(courseId, s);
        }
        return s;
    }

    private static final DateTimeFormatter DMY_HM = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");

    /** «04-10-2026 23:59» → миллисекунды по Ташкенту; -1, если не разобрать. */
    static long lmsTime(String s) {
        if (s == null) return -1;
        try {
            return LocalDateTime.parse(s.trim(), DMY_HM).atZone(AppConfig.LMS_ZONE).toInstant().toEpochMilli();
        } catch (Exception e) {
            long v = uz.tuit.lmsbot.util.LmsDates.parseDeadline(s);
            return v;
        }
    }

    private static boolean pending(GCell c) {
        return c.submitted() && (c.grade() == null || c.grade().isBlank());
    }

    private static boolean graded(GCell c) {
        return c.grade() != null && !c.grade().isBlank();
    }

    // ─────────────────────────────────────────────
    //  MENU
    // ─────────────────────────────────────────────

    private static final String[] M_COURSES = {"📚 Мои предметы", "📚 Mening fanlarim", "📚 Менинг фанларим", "📚 My subjects"};
    private static final String[] M_SCHEDULE = {"📅 Расписание", "📅 Dars jadvali", "📅 Дарс жадвали", "📅 Timetable"};
    private static final String[] M_GRADING = {"📝 Проверка работ", "📝 Ishlarni tekshirish", "📝 Ишларни текшириш", "📝 Grading"};
    private static final String[] M_MATERIALS = {"📂 Материалы", "📂 Materiallar", "📂 Материаллар", "📂 Materials"};
    private static final String[] M_APPEALS = {"📨 Исправление НБ", "📨 NB tuzatish", "📨 НБ тузатиш", "📨 Absence fixes"};
    private static final String[] M_FINALS = {"🏆 Итоговый экзамен", "🏆 Yakuniy imtihon", "🏆 Якуний имтиҳон", "🏆 Final exams"};
    private static final String[] M_TUTOR = {"👥 Тьютор", "👥 Tyutor", "👥 Тьютор", "👥 Tutor"};
    private static final String[] M_PROFILE = {"👤 Профиль", "👤 Profil", "👤 Профил", "👤 Profile"};
    private static final String[] M_SETTINGS = {"⚙️ Настройки", "⚙️ Sozlamalar", "⚙️ Созламалар", "⚙️ Settings"};
    private static final String[] M_DEADLINES_OLD = {"🗓 Список дедлайнов", "🗓 Deadline ro'yxati", "🗓 Дедлайнлар рўйхати", "🗓 Deadlines"};

    private String label(long uid, String[] v) {
        return T(uid, v[0], v[1], v[2], v[3]);
    }

    private static boolean in(String t, String[] v) {
        for (String s : v) if (s.equals(t)) return true;
        return false;
    }

    boolean isMenuText(String t) {
        return in(t, M_COURSES) || in(t, M_SCHEDULE) || in(t, M_GRADING) || in(t, M_MATERIALS) || in(t, M_APPEALS)
                || in(t, M_FINALS) || in(t, M_TUTOR) || in(t, M_PROFILE) || in(t, M_DEADLINES_OLD);
    }

    ReplyKeyboardMarkup menuKeyboard(long uid) {
        List<KeyboardRow> rows = new ArrayList<>();
        rows.add(row(label(uid, M_COURSES), label(uid, M_SCHEDULE)));
        rows.add(row(label(uid, M_GRADING), label(uid, M_MATERIALS)));
        rows.add(row(label(uid, M_APPEALS), label(uid, M_FINALS)));
        if (lms.hasTutor(uid)) rows.add(row(label(uid, M_TUTOR), label(uid, M_PROFILE)));
        else rows.add(row(label(uid, M_PROFILE)));
        rows.add(row(label(uid, M_SETTINGS)));
        ReplyKeyboardMarkup m = new ReplyKeyboardMarkup();
        m.setKeyboard(rows);
        m.setResizeKeyboard(true);
        return m;
    }

    private static KeyboardRow row(String... labels) {
        KeyboardRow r = new KeyboardRow();
        for (String l : labels) r.add(new KeyboardButton(l));
        return r;
    }

    /** Кнопка меню или команда. true — обработано. */
    boolean onMenu(long chatId, long uid, String text) {
        if (in(text, M_COURSES) || "/courses".equals(text)) showCourses(chatId, uid, null, currentSemester(uid));
        else if (in(text, M_SCHEDULE)) bot.showSchedule(chatId, uid, bot.getDefaultSemesterId(uid));
        else if (in(text, M_GRADING) || in(text, M_DEADLINES_OLD)) showGrading(chatId, uid, null);
        else if (in(text, M_MATERIALS)) showSubjects(chatId, uid, null);
        else if (in(text, M_APPEALS)) showAppeals(chatId, uid, null);
        else if (in(text, M_FINALS)) showFinals(chatId, uid, bot.getDefaultSemesterId(uid));
        else if (in(text, M_TUTOR)) showGroups(chatId, uid, null);
        else if (in(text, M_PROFILE)) showProfile(chatId, uid);
        else return false;
        return true;
    }

    String welcome(long uid, String name) {
        String hi = name == null || name.isBlank() ? "" : ", " + name;
        boolean tutor = lms.hasTutor(uid);
        return T(uid,
                "👋 <b>Здравствуйте" + hi + "!</b>\n\nВы вошли как <b>преподаватель</b>.\n\n"
                        + "📚 предметы, календарный план и посещаемость\n📋 ведомость и оценивание работ\n"
                        + "🧩 активности (задания) потоков\n📂 учебные материалы\n📨 исправление НБ\n"
                        + (tutor ? "👥 режим тьютора — ваши группы\n" : "")
                        + "⏰ напоминания о парах, отметке посещаемости и сроках проверки\n\nВыберите раздел в меню ниже 👇",
                "👋 <b>Assalomu alaykum" + hi + "!</b>\n\nSiz <b>o'qituvchi</b> sifatida kirdingiz.\n\n"
                        + "📚 fanlar, kalendar reja va davomat\n📋 qaydnoma va ishlarni baholash\n"
                        + "🧩 oqimlar aktivliklari (topshiriqlar)\n📂 o'quv materiallari\n📨 NB tuzatish\n"
                        + (tutor ? "👥 tyutor rejimi — guruhlaringiz\n" : "")
                        + "⏰ darslar, davomat belgilash va tekshirish muddatlari eslatmalari\n\nQuyidagi menyudan bo'limni tanlang 👇",
                "👋 <b>Ассалому алайкум" + hi + "!</b>\n\nСиз <b>ўқитувчи</b> сифатида кирдингиз.\n\n"
                        + "📚 фанлар, календар режа ва давомат\n📋 қайднома ва ишларни баҳолаш\n"
                        + "🧩 оқимлар активликлари (топшириқлар)\n📂 ўқув материаллари\n📨 НБ тузатиш\n"
                        + (tutor ? "👥 тьютор режими — гуруҳларингиз\n" : "")
                        + "⏰ дарслар, давомат белгилаш ва текшириш муддатлари эслатмалари\n\nҚуйидаги менюдан бўлимни танланг 👇",
                "👋 <b>Hello" + hi + "!</b>\n\nYou're signed in as a <b>teacher</b>.\n\n"
                        + "📚 subjects, class plan and attendance\n📋 grade sheet and grading\n"
                        + "🧩 stream activities (assignments)\n📂 teaching materials\n📨 absence fixes\n"
                        + (tutor ? "👥 tutor mode — your groups\n" : "")
                        + "⏰ reminders about classes, marking attendance and grading deadlines\n\nPick a section in the menu below 👇");
    }

    // ─────────────────────────────────────────────
    //  CALLBACKS
    // ─────────────────────────────────────────────

    boolean onCallback(long chatId, long uid, int msgId, String data) {
        if (!data.startsWith("t:") && !data.startsWith("tc_") && !data.startsWith("tf_")) return false;
        if (!bot.checkLogin(chatId, uid)) return true;
        // Кнопки, оставшиеся от кабинета преподавателя, после входа в студенческий аккаунт не работают.
        if (!lms.isTeacher(uid)) return true;
        try {
            if (data.startsWith("tc_")) {
                showCourses(chatId, uid, msgId, Integer.parseInt(data.substring(3)));
                return true;
            }
            if (data.startsWith("tf_")) {
                showFinals(chatId, uid, Integer.parseInt(data.substring(3)));
                return true;
            }
            String[] p = data.substring(2).split(":");
            switch (p[0]) {
                case "cl" -> showCourses(chatId, uid, msgId, currentSemester(uid));
                case "sem" -> bot.edit(chatId, msgId, T(uid, "📅 Выберите семестр:", "📅 Semestrni tanlang:", "📅 Семестрни танланг:", "📅 Choose a semester:"),
                        bot.buildSemesterKeyboard(uid, "tc_"));
                case "co" -> showCourse(chatId, uid, msgId, i(p[1]));
                case "cal" -> showCalendar(chatId, uid, msgId, i(p[1]));
                case "att" -> showAttendance(chatId, uid, msgId, i(p[1]), i(p[2]));
                case "gs" -> showSheet(chatId, uid, msgId, i(p[1]), p.length > 2 && "f".equals(p[2]));
                case "gt" -> showTotals(chatId, uid, msgId, i(p[1]));
                case "ga" -> showColumn(chatId, uid, msgId, i(p[1]), i(p[2]), p.length > 3 ? i(p[3]) : 0);
                case "gf" -> showGradeForm(chatId, uid, msgId, i(p[1]), i(p[2]), i(p[3]), null);
                case "gn" -> gradeNeighbour(chatId, uid, msgId, i(p[1]), i(p[2]), i(p[3]), "n".equals(p[4]));
                case "gset" -> startGrade(chatId, uid, i(p[1]), i(p[2]), i(p[3]));
                case "gclr" -> confirmClear(chatId, uid, msgId, i(p[1]), i(p[2]), i(p[3]));
                case "gclr!" -> doClear(chatId, uid, msgId, i(p[1]), i(p[2]), i(p[3]));
                case "gfl" -> gradeFile(chatId, uid, i(p[1]), i(p[2]), i(p[3]), i(p[4]));
                case "gcm" -> finishGrade(chatId, uid, "keep".equals(p[1]) ? null : "");
                case "ac" -> showActivities(chatId, uid, msgId, i(p[1]));
                case "acs" -> activitySample(chatId, uid, i(p[1]), i(p[2]));
                case "acadd" -> startActivity(chatId, uid, i(p[1]));
                case "acdel" -> confirmDeleteActivity(chatId, uid, msgId, i(p[1]), i(p[2]));
                case "acdel!" -> deleteActivity(chatId, uid, msgId, i(p[1]), i(p[2]));
                case "ad" -> draftAction(chatId, uid, p[1]);
                case "dl" -> showGrading(chatId, uid, msgId);
                case "ap" -> showAppeals(chatId, uid, msgId);
                case "apn" -> appealStreams(chatId, uid, msgId);
                case "aps" -> appealLessons(chatId, uid, msgId, i(p[1]), p.length > 2 ? i(p[2]) : 0);
                case "apl" -> appealStudents(chatId, uid, msgId, i(p[1]), p.length > 2 ? i(p[2]) : 0);
                case "apt" -> appealPair(chatId, uid, msgId, i(p[1]));
                case "ap!" -> appealSubmit(chatId, uid, msgId);
                case "apx" -> {
                    appeals.remove(uid);
                    if ("T_AP_PAIR".equals(bot.userState.get(uid))) bot.userState.put(uid, "IDLE");
                    showAppeals(chatId, uid, msgId);
                }
                case "apd" -> confirmDeleteAppeal(chatId, uid, msgId, i(p[1]));
                case "apd!" -> deleteAppeal(chatId, uid, msgId, i(p[1]));
                case "apf" -> sendFile(chatId, uid, ts.appealPdfUrl(i(p[1])), "appeal-" + p[1] + ".pdf");
                case "ms" -> showSubjects(chatId, uid, msgId);
                case "msu" -> showSubject(chatId, uid, msgId, i(p[1]));
                case "mt" -> showTopics(chatId, uid, msgId, p[1], i(p[2]));
                case "mtp" -> showTopic(chatId, uid, msgId, i(p[1]));
                case "mdl" -> materialDownload(chatId, uid, i(p[1]));
                case "madd" -> materialAdd(chatId, uid, msgId, p[1]);
                case "mdel" -> confirmDeleteMaterial(chatId, uid, msgId, i(p[1]));
                case "mdel!" -> deleteMaterial(chatId, uid, msgId, i(p[1]));
                case "tg" -> showGroups(chatId, uid, msgId);
                case "tgs" -> showGroup(chatId, uid, msgId, i(p[1]));
                case "tst" -> showStudent(chatId, uid, msgId, i(p[1]), p.length > 2 ? p[2] : null);
                case "tsm" -> studentSemesters(chatId, uid, msgId, i(p[1]), i(p[2]));
                case "tsp" -> showStudentPlan(chatId, uid, msgId, i(p[1]));
                case "tss" -> showStudentSchedule(chatId, uid, msgId, i(p[1]), p[2]);
                default -> { return true; }
            }
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            System.err.println("[TeacherBot] bad callback " + data);
        }
        return true;
    }

    private static int i(String s) {
        return Integer.parseInt(s);
    }

    // ─────────────────────────────────────────────
    //  TEXT / FILE INPUT (состояния T_*)
    // ─────────────────────────────────────────────

    /** Текст в одном из диалогов преподавателя. */
    void onText(long chatId, long uid, String text, String state) {
        if ("/cancel".equals(text)) {
            cancel(chatId, uid);
            return;
        }
        switch (state) {
            case "T_GRADE" -> gradeInput(chatId, uid, text);
            case "T_GCOMMENT" -> finishGrade(chatId, uid, text);
            case "T_ACT_NAME", "T_ACT_DEADLINE", "T_ACT_MAX", "T_ACT_CRIT" -> draftInput(chatId, uid, state, text);
            case "T_ACT_FILE" -> bot.send(chatId, T(uid,
                    "📎 Отправьте файл или нажмите «Без файла».", "📎 Fayl yuboring yoki «Faylsiz» tugmasini bosing.",
                    "📎 Файл юборинг ёки «Файлсиз» тугмасини босинг.", "📎 Send a file or tap «No file»."), null);
            case "T_AP_PAIR" -> appealPairInput(chatId, uid, text);
            case "T_MAT_URL" -> materialUrl(chatId, uid, text);
            case "T_MAT_FILE" -> bot.send(chatId, T(uid,
                    "📎 Отправьте файл или /cancel.", "📎 Fayl yuboring yoki /cancel.", "📎 Файл юборинг ёки /cancel.", "📎 Send a file or /cancel."), null);
            default -> bot.userState.put(uid, "IDLE");
        }
    }

    /** Файл в диалоге преподавателя (инструкция к активности или учебный материал). */
    void onFile(long chatId, long uid, Message msg, String state) {
        if (!"T_ACT_FILE".equals(state) && !"T_MAT_FILE".equals(state)) {
            bot.send(chatId, T(uid, "🤷 Сейчас файл не нужен.", "🤷 Hozir fayl kerak emas.", "🤷 Ҳозир файл керак эмас.", "🤷 No file is expected now."), null);
            return;
        }
        bot.sendProgress(chatId, T(uid, "⏳ Получаю файл...", "⏳ Fayl olinmoqda...", "⏳ Файл олинмоқда...", "⏳ Receiving the file..."));
        async(chatId, uid, () -> {
            String fileId, name;
            if (msg.hasDocument()) {
                fileId = msg.getDocument().getFileId();
                name = msg.getDocument().getFileName();
            } else {
                var photos = msg.getPhoto();
                fileId = photos.get(photos.size() - 1).getFileId();
                name = "photo.jpg";
            }
            if (name == null || name.isBlank()) name = "file";
            org.telegram.telegrambots.meta.api.methods.GetFile gf = new org.telegram.telegrambots.meta.api.methods.GetFile();
            gf.setFileId(fileId);
            File tmp = File.createTempFile("lmst_", "_" + name.replaceAll("[\\\\/:*?\"<>|]", "_"));
            bot.downloadFile(bot.execute(gf), tmp);
            if ("T_ACT_FILE".equals(state)) draftFile(chatId, uid, tmp, name);
            else materialFile(chatId, uid, tmp, name, msg.getCaption());
        });
    }

    private void cancel(long chatId, long uid) {
        bot.userState.put(uid, "IDLE");
        pendingGrade.remove(uid);
        dropDraft(uid);
        appeals.remove(uid);
        bot.send(chatId, T(uid, "❌ Отменено.", "❌ Bekor qilindi.", "❌ Бекор қилинди.", "❌ Cancelled."), null);
    }

    // ─────────────────────────────────────────────
    //  МОИ ПРЕДМЕТЫ
    // ─────────────────────────────────────────────

    private static final String[] DOTS = {"🟡", "🟢", "🔵", "🟣", "🟠", "🔴", "⚪", "🟤"};

    void showCourses(long chatId, long uid, Integer msgId, int sem) {
        if (!bot.checkLogin(chatId, uid)) return;
        if (!bot.checkSemester(chatId, uid, sem)) return;
        if (msgId == null) loading(chatId, uid);
        async(chatId, uid, () -> {
            List<TCourse> list = ts.courses(uid, sem);
            String semName = lms.semesterName(uid, sem);
            if (list.isEmpty()) {
                show(chatId, msgId, "📭 " + T(uid, "В этом семестре предметов нет.", "Bu semestrda fanlar yo'q.",
                        "Бу семестрда фанлар йўқ.", "No subjects in this semester.") + "\n<i>" + esc(semName) + "</i>",
                        bot.buildSemesterKeyboard(uid, "tc_"));
                return;
            }
            courses.put(uid, list);
            semester.put(uid, sem);
            bot.rememberSemester(uid, sem);
            StringBuilder sb = new StringBuilder("📚 <b>").append(esc(semName)).append("</b>\n\n");
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            List<InlineKeyboardButton> line = new ArrayList<>();
            Map<String, String> dot = new LinkedHashMap<>();
            for (int k = 0; k < list.size(); k++) {
                TCourse c = list.get(k);
                String d = dot.computeIfAbsent(c.subject(), s -> DOTS[dot.size() % DOTS.length]);
                sb.append("<blockquote>").append(d).append(" <b>").append(k + 1).append(". ").append(esc(c.subject())).append("</b>\n")
                        .append("🔹 ").append(esc(c.type()));
                if (c.stream() != null) sb.append(" · <b>").append(esc(c.stream())).append("</b>");
                sb.append(" · 👥 ").append(c.students());
                if (c.rejected() > 0) sb.append(" · ✉️ ").append(c.rejected());
                sb.append("</blockquote>\n");
                line.add(btn(d + " " + (k + 1) + (c.stream() != null ? " · " + c.stream() : ""), "t:co:" + c.id()));
                if (line.size() == 2) { rows.add(line); line = new ArrayList<>(); }
            }
            if (!line.isEmpty()) rows.add(line);
            if (list.stream().anyMatch(c -> c.rejected() > 0))
                sb.append("\n✉️ ").append(T(uid, "— есть активности, возвращённые на доработку.", "— qayta ishlashga qaytarilgan aktivliklar bor.",
                        "— қайта ишлашга қайтарилган активликлар бор.", "— some activities were sent back for changes."));
            rows.add(List.of(btn(label(uid, M_GRADING), "t:dl")));
            rows.add(List.of(btn(T(uid, "📅 Сменить семестр", "📅 Semestrni o'zgartirish", "📅 Семестрни ўзгартириш", "📅 Change semester"), "t:sem")));
            show(chatId, msgId, sb.toString(), kb(rows));
        });
    }

    private void showCourse(long chatId, long uid, int msgId, int courseId) {
        async(chatId, uid, () -> {
            TCourse c = course(uid, courseId);
            if (c == null) { showCourses(chatId, uid, msgId, currentSemester(uid)); return; }
            StringBuilder sb = new StringBuilder("📘 <b>").append(esc(c.subject())).append("</b>\n\n<blockquote>");
            sb.append("🔹 ").append(esc(c.type()));
            if (c.stream() != null) sb.append(" · <b>").append(esc(c.stream())).append("</b>");
            sb.append("\n👥 ").append(T(uid, "Студентов", "Talabalar", "Талабалар", "Students")).append(": <b>").append(c.students()).append("</b>");
            if (c.rejected() > 0) sb.append("\n✉️ ").append(T(uid, "Возвращено на доработку", "Qayta ishlashga qaytarilgan", "Қайта ишлашга қайтарилган", "Sent back for changes"))
                    .append(": <b>").append(c.rejected()).append("</b>");
            sb.append("</blockquote>");
            show(chatId, msgId, sb.toString(), kb(List.of(
                    List.of(btn(T(uid, "📆 Календарный план", "📆 Kalendar reja", "📆 Календар режа", "📆 Class plan"), "t:cal:" + courseId),
                            btn(T(uid, "📋 Ведомость", "📋 Qaydnoma", "📋 Қайднома", "📋 Grade sheet"), "t:gs:" + courseId)),
                    List.of(btn(T(uid, "🧩 Активности", "🧩 Aktivliklar", "🧩 Активликлар", "🧩 Activities"), "t:ac:" + courseId)),
                    List.of(btn(T(uid, "🔙 Мои предметы", "🔙 Mening fanlarim", "🔙 Менинг фанларим", "🔙 My subjects"), "t:cl")))));
        });
    }

    // ─────────────────────────────────────────────
    //  КАЛЕНДАРНЫЙ ПЛАН И ПОСЕЩАЕМОСТЬ
    // ─────────────────────────────────────────────

    private void showCalendar(long chatId, long uid, int msgId, int courseId) {
        async(chatId, uid, () -> {
            TCalendar cal = ts.calendar(uid, courseId);
            ts.rememberStream(uid, courseId, cal.stream());
            TCourse c = course(uid, courseId);
            LocalDate today = LocalDate.now(AppConfig.LMS_ZONE);
            StringBuilder sb = new StringBuilder("📆 <b>").append(esc(c != null ? c.subject() : "")).append("</b> · ").append(esc(cal.stream())).append("\n");
            if (cal.note() != null) sb.append("<i>").append(esc(cal.note())).append("</i>\n");
            sb.append("\n");
            List<InlineKeyboardButton> line = new ArrayList<>();
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            boolean movedHeader = false;
            StringBuilder block = new StringBuilder();
            for (TLesson l : cal.lessons()) {
                if (l.moved() && !movedHeader) {
                    sb.append("<blockquote>").append(block.toString().trim()).append("</blockquote>\n\n🔁 <b>")
                            .append(T(uid, "Перенесённые", "Ko'chirilganlar", "Кўчирилганлар", "Rescheduled")).append("</b>\n");
                    block.setLength(0);
                    movedHeader = true;
                }
                LocalDate d = SemesterScheduleDates.parse(l.date());
                String icon = l.marked() ? "✅" : d != null && d.isBefore(today) ? "🔴" : d != null && d.equals(today) ? "📍" : "▫️";
                block.append(icon).append(" <b>").append(l.number()).append(".</b> ").append(esc(l.date()))
                        .append(" — ").append(esc(cut(l.topic(), 70))).append("\n");
                if (l.lessonId() != null) {
                    line.add(btn("📊 " + l.number(), "t:att:" + courseId + ":" + l.lessonId()));
                    if (line.size() == 5) { rows.add(line); line = new ArrayList<>(); }
                }
            }
            if (block.length() > 0) sb.append("<blockquote>").append(block.toString().trim()).append("</blockquote>\n");
            if (cal.lessons().isEmpty()) sb.append(T(uid, "📭 План пуст — его формируют на сайте LMS.", "📭 Reja bo'sh — u LMS saytida shakllantiriladi.",
                    "📭 Режа бўш — у LMS сайтида шакллантирилади.", "📭 The plan is empty — it is generated on the LMS website.")).append("\n");
            sb.append("\n✅ ").append(T(uid, "отмечено", "belgilangan", "белгиланган", "marked"))
                    .append(" · 🔴 ").append(T(uid, "не отмечено", "belgilanmagan", "белгиланмаган", "not marked"))
                    .append(" · 📍 ").append(T(uid, "сегодня", "bugun", "бугун", "today"));
            if (!line.isEmpty()) rows.add(line);
            if (!rows.isEmpty()) sb.append("\n📊 ").append(T(uid, "— посещаемость занятия", "— dars davomati", "— дарс давомати", "— class attendance"));
            rows.add(List.of(btn(T(uid, "🔙 Назад", "🔙 Orqaga", "🔙 Орқага", "🔙 Back"), "t:co:" + courseId)));
            show(chatId, msgId, sb.toString(), kb(rows));
        });
    }

    private void showAttendance(long chatId, long uid, int msgId, int courseId, int lessonId) {
        async(chatId, uid, () -> {
            TAttendance a = ts.attendance(uid, courseId, lessonId);
            long present = a.students().stream().filter(s -> Boolean.TRUE.equals(s.present())).count();
            long absent = a.students().stream().filter(s -> Boolean.FALSE.equals(s.present())).count();
            StringBuilder sb = new StringBuilder("📊 <b>").append(esc(a.header())).append("</b>\n<i>").append(esc(a.topic())).append("</i>\n\n");
            sb.append("✅ ").append(present).append(" · 🔴 ").append(absent).append(" · 👥 ").append(a.students().size()).append("\n\n");
            StringBuilder abs = new StringBuilder(), pres = new StringBuilder(), none = new StringBuilder();
            for (AttStudent s : a.students()) {
                String line = s.number() + ". " + esc(s.name()) + (s.group().isBlank() ? "" : " · <i>" + esc(s.group()) + "</i>") + "\n";
                if (Boolean.FALSE.equals(s.present())) abs.append("🔴 ").append(line);
                else if (Boolean.TRUE.equals(s.present())) pres.append("✅ ").append(line);
                else none.append("▫️ ").append(line);
            }
            if (abs.length() > 0) sb.append("<blockquote>").append(abs.toString().trim()).append("</blockquote>\n");
            if (none.length() > 0) sb.append("<blockquote>").append(none.toString().trim()).append("</blockquote>\n");
            if (pres.length() > 0) sb.append("<blockquote expandable>").append(pres.toString().trim()).append("</blockquote>");
            show(chatId, msgId, sb.toString(), kb(List.of(List.of(
                    btn(T(uid, "🔙 Календарный план", "🔙 Kalendar reja", "🔙 Календар режа", "🔙 Class plan"), "t:cal:" + courseId)))));
        });
    }

    // ─────────────────────────────────────────────
    //  ВЕДОМОСТЬ
    // ─────────────────────────────────────────────

    private void showSheet(long chatId, long uid, int msgId, int courseId, boolean fresh) {
        async(chatId, uid, () -> {
            GradeSheet s = sheet(uid, courseId, fresh);
            StringBuilder sb = new StringBuilder("📋 <b>").append(esc(s.title())).append("</b>\n");
            if (s.note() != null) sb.append("<i>").append(esc(s.note())).append("</i>\n");
            sb.append("\n");
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            List<InlineKeyboardButton> line = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (int k = 0; k < s.columns().size(); k++) {
                GColumn c = s.columns().get(k);
                int sub = 0, done = 0, wait = 0;
                for (GRow r : s.rows()) {
                    GCell cell = r.cells().get(k);
                    if (cell.submitted()) sub++;
                    if (graded(cell)) done++;
                    if (pending(cell)) wait++;
                }
                long td = lmsTime(c.teacherDeadline());
                sb.append("<blockquote>🧩 <b>").append(esc(c.name())).append("</b>");
                if (!c.max().isBlank()) sb.append(" · ").append(T(uid, "макс.", "maks.", "макс.", "max")).append(" ").append(esc(c.max()));
                if (c.studentDeadline() != null) sb.append("\n🎓 ").append(esc(c.studentDeadline()));
                if (c.teacherDeadline() != null) sb.append("\n👨‍🏫 ").append(esc(c.teacherDeadline())).append(td > 0 && td < now ? " 🔒" : "");
                sb.append("\n📥 ").append(sub).append(" · ✅ ").append(done).append(" · ⏳ ").append(wait).append("</blockquote>\n");
                line.add(btn("🧩 " + cut(c.name(), 14) + (wait > 0 ? " ⏳" + wait : ""), "t:ga:" + courseId + ":" + k));
                if (line.size() == 2) { rows.add(line); line = new ArrayList<>(); }
            }
            if (s.columns().isEmpty()) sb.append(T(uid, "📭 Активностей пока нет.", "📭 Hozircha aktivliklar yo'q.", "📭 Ҳозирча активликлар йўқ.", "📭 No activities yet.")).append("\n");
            else sb.append("\n📥 ").append(T(uid, "сдали", "topshirgan", "топширган", "submitted"))
                    .append(" · ✅ ").append(T(uid, "оценено", "baholangan", "баҳоланган", "graded"))
                    .append(" · ⏳ ").append(T(uid, "ждут проверки", "tekshiruvni kutmoqda", "текширувни кутмоқда", "awaiting grading"))
                    .append("\n🎓 ").append(T(uid, "срок студента", "talaba muddati", "талаба муддати", "student deadline"))
                    .append(" · 👨‍🏫 ").append(T(uid, "срок оценивания", "baholash muddati", "баҳолаш муддати", "grading deadline"));
            if (!line.isEmpty()) rows.add(line);
            rows.add(List.of(btn(T(uid, "🏅 Итоги студентов", "🏅 Talabalar natijalari", "🏅 Талабалар натижалари", "🏅 Student totals"), "t:gt:" + courseId),
                    btn(T(uid, "🔄 Обновить", "🔄 Yangilash", "🔄 Янгилаш", "🔄 Refresh"), "t:gs:" + courseId + ":f")));
            rows.add(List.of(btn(T(uid, "🔙 Назад", "🔙 Orqaga", "🔙 Орқага", "🔙 Back"), "t:co:" + courseId)));
            show(chatId, msgId, sb.toString(), kb(rows));
        });
    }

    private void showTotals(long chatId, long uid, int msgId, int courseId) {
        async(chatId, uid, () -> {
            GradeSheet s = sheet(uid, courseId, false);
            StringBuilder sb = new StringBuilder("🏅 <b>").append(esc(s.title())).append("</b>\n\n<blockquote>");
            for (GRow r : s.rows()) sb.append(r.number()).append(". ").append(esc(cut(r.name(), 34))).append(" — <b>")
                    .append(esc(r.total())).append("</b> · ").append(esc(r.percent())).append("\n");
            sb.append("</blockquote>");
            show(chatId, msgId, sb.toString(), kb(List.of(List.of(btn(T(uid, "🔙 Ведомость", "🔙 Qaydnoma", "🔙 Қайднома", "🔙 Grade sheet"), "t:gs:" + courseId)))));
        });
    }

    /** Порядок проверки: сначала сданные без оценки, потом оценённые, затем остальные. */
    private List<GRow> orderFor(GradeSheet s, int col) {
        List<GRow> out = new ArrayList<>();
        for (GRow r : s.rows()) if (pending(r.cells().get(col))) out.add(r);
        for (GRow r : s.rows()) if (!pending(r.cells().get(col)) && graded(r.cells().get(col))) out.add(r);
        for (GRow r : s.rows()) if (!out.contains(r)) out.add(r);
        return out;
    }

    private String cellIcon(GCell c) {
        return pending(c) ? "⏳" : graded(c) ? "✅" : c.submitted() ? "📥" : "▫️";
    }

    private void showColumn(long chatId, long uid, int msgId, int courseId, int col, int page) {
        async(chatId, uid, () -> {
            GradeSheet s = sheet(uid, courseId, false);
            if (col >= s.columns().size()) { showSheet(chatId, uid, msgId, courseId, true); return; }
            GColumn c = s.columns().get(col);
            StringBuilder sb = new StringBuilder("🧩 <b>").append(esc(c.name())).append("</b> · ").append(esc(s.title())).append("\n");
            if (c.studentDeadline() != null) sb.append("🎓 ").append(esc(c.studentDeadline()));
            if (c.teacherDeadline() != null) sb.append(" · 👨‍🏫 ").append(esc(c.teacherDeadline()));
            // Лекционный поток бывает на сотню студентов и больше — листаем по 40.
            List<GRow> order = orderFor(s, col);
            int size = 40, pages = Math.max(1, (order.size() + size - 1) / size);
            int pg = Math.max(0, Math.min(page, pages - 1));
            if (pages > 1) sb.append("\n").append(pg + 1).append(" / ").append(pages);
            sb.append("\n\n<blockquote>");
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            List<InlineKeyboardButton> line = new ArrayList<>();
            for (GRow r : order.subList(pg * size, Math.min(order.size(), (pg + 1) * size))) {
                GCell cell = r.cells().get(col);
                sb.append(cellIcon(cell)).append(" ").append(r.number()).append(". ").append(esc(cut(r.name(), 32)));
                if (graded(cell)) sb.append(" — <b>").append(esc(cell.grade())).append("</b>");
                sb.append("\n");
                if (cell.studentId() != null && cell.activityId() != null) {
                    line.add(btn(cellIcon(cell) + r.number(), "t:gf:" + courseId + ":" + cell.activityId() + ":" + cell.studentId()));
                    if (line.size() == 5) { rows.add(line); line = new ArrayList<>(); }
                }
            }
            sb.append("</blockquote>\n⏳ ").append(T(uid, "ждёт проверки", "tekshiruvni kutmoqda", "текширувни кутмоқда", "awaiting grading"))
                    .append(" · ✅ ").append(T(uid, "оценено", "baholangan", "баҳоланган", "graded"))
                    .append(" · ▫️ ").append(T(uid, "не сдано", "topshirilmagan", "топширилмаган", "not submitted"));
            if (!line.isEmpty()) rows.add(line);
            if (pages > 1) rows.add(pager(pg, pages, "t:ga:" + courseId + ":" + col + ":"));
            rows.add(List.of(btn(T(uid, "🔙 Ведомость", "🔙 Qaydnoma", "🔙 Қайднома", "🔙 Grade sheet"), "t:gs:" + courseId)));
            show(chatId, msgId, sb.toString(), kb(rows));
        });
    }

    private int columnOf(GradeSheet s, int activityId) {
        for (int k = 0; k < s.columns().size(); k++) {
            Integer id = s.columns().get(k).activityId();
            if (id != null && id == activityId) return k;
        }
        return -1;
    }

    private void showGradeForm(long chatId, long uid, Integer msgId, int courseId, int activityId, int studentId, String notice) {
        async(chatId, uid, () -> {
            GradeForm f = ts.gradeForm(uid, studentId, activityId);
            GradeSheet s = sheet(uid, courseId, false);
            Map<String, String> L = s.labels();
            StringBuilder sb = new StringBuilder();
            if (notice != null) sb.append(notice).append("\n\n");
            sb.append("📝 <b>").append(esc(L.getOrDefault("title", "📝"))).append("</b> · ").append(esc(s.title())).append("\n\n");
            sb.append("<blockquote>🧩 <b>").append(esc(f.name())).append("</b>\n👤 ").append(esc(f.student()))
                    .append("\n📅 ").append(esc(f.deadline()))
                    .append("\n🎯 ").append(T(uid, "Макс. балл", "Maks. ball", "Макс. балл", "Max score")).append(": <b>").append(esc(f.maxPoint())).append("</b>");
            if (f.files().isEmpty()) sb.append("\n📎 ").append(esc(f.fileText() == null || f.fileText().isBlank() ? "—" : f.fileText()));
            sb.append("</blockquote>\n");
            if (f.module()) {
                sb.append("\n<b>").append(esc(L.getOrDefault("grade", "✏️"))).append("</b>: <b>")
                        .append(f.grade().isBlank() ? "—" : esc(f.grade())).append("</b>\n");
            } else if (!f.criteria().isEmpty()) {
                sb.append("\n<b>").append(esc(L.getOrDefault("criterion", ""))).append("</b> — ")
                        .append(esc(L.getOrDefault("points", ""))).append(" / ").append(esc(L.getOrDefault("max", ""))).append("\n<blockquote>");
                for (Criterion c : f.criteria())
                    sb.append("• ").append(esc(c.name())).append(" — <b>").append(c.value().isBlank() ? "—" : esc(c.value()))
                            .append("</b> / ").append(esc(c.max())).append("\n");
                sb.append(esc(L.getOrDefault("total", "Σ"))).append(": <b>").append(f.grade().isBlank() ? "—" : esc(f.grade())).append("</b></blockquote>\n");
            }
            if (!f.comment().isBlank()) sb.append("💬 ").append(esc(L.getOrDefault("comment", ""))).append(": <i>").append(esc(f.comment())).append("</i>\n");
            if (!f.editable()) sb.append("\n🔒 ").append(T(uid, "Срок выставления оценок прошёл — LMS не даёт изменить оценку.",
                    "Baholash muddati o'tgan — LMS bahoni o'zgartirishga ruxsat bermaydi.",
                    "Баҳолаш муддати ўтган — LMS баҳони ўзгартиришга рухсат бермайди.",
                    "The grading deadline has passed — LMS does not allow changing the grade."));

            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            for (int k = 0; k < f.files().size(); k++)
                rows.add(List.of(btn("📎 " + cut(f.files().get(k).name(), 40), "t:gfl:" + courseId + ":" + activityId + ":" + studentId + ":" + k)));
            List<InlineKeyboardButton> act = new ArrayList<>();
            if (f.editable()) act.add(btn("✏️ " + T(uid, "Оценить", "Baholash", "Баҳолаш", "Grade"), "t:gset:" + courseId + ":" + activityId + ":" + studentId));
            if (f.editable() && f.canClear() && !f.grade().isBlank())
                act.add(btn("🗑 " + L.getOrDefault("clear", T(uid, "Убрать оценку", "Bahoni olib tashlash", "Баҳони олиб ташлаш", "Clear grade")),
                        "t:gclr:" + courseId + ":" + activityId + ":" + studentId));
            if (!act.isEmpty()) rows.add(act);
            rows.add(List.of(btn("⬅️", "t:gn:" + courseId + ":" + activityId + ":" + studentId + ":p"),
                    btn("➡️", "t:gn:" + courseId + ":" + activityId + ":" + studentId + ":n")));
            int col = columnOf(s, activityId);
            rows.add(List.of(btn(T(uid, "🔙 К списку", "🔙 Ro'yxatga", "🔙 Рўйхатга", "🔙 To the list"),
                    col >= 0 ? "t:ga:" + courseId + ":" + col : "t:gs:" + courseId)));

            GradeCtx ctx = new GradeCtx();
            ctx.courseId = courseId;
            ctx.activityId = activityId;
            ctx.studentId = studentId;
            ctx.form = f;
            grading.put(uid, ctx);
            show(chatId, msgId, sb.toString(), kb(rows));
        });
    }

    private void gradeNeighbour(long chatId, long uid, int msgId, int courseId, int activityId, int studentId, boolean next) {
        async(chatId, uid, () -> {
            GradeSheet s = sheet(uid, courseId, false);
            int col = columnOf(s, activityId);
            if (col < 0) return;
            List<GRow> order = orderFor(s, col);
            int at = -1;
            for (int k = 0; k < order.size(); k++) if (Objects.equals(order.get(k).studentId(), studentId)) at = k;
            if (order.isEmpty()) return;
            int to = ((next ? at + 1 : at - 1) + order.size()) % order.size();
            GCell cell = order.get(to).cells().get(col);
            if (cell.studentId() == null) return;
            showGradeForm(chatId, uid, msgId, courseId, activityId, cell.studentId(), null);
        });
    }

    private void gradeFile(long chatId, long uid, int courseId, int activityId, int studentId, int idx) {
        async(chatId, uid, () -> {
            GradeCtx ctx = grading.get(uid);
            GradeForm f = ctx != null && ctx.activityId == activityId && ctx.studentId == studentId
                    ? ctx.form : ts.gradeForm(uid, studentId, activityId);
            if (idx >= f.files().size()) return;
            FileLink link = f.files().get(idx);
            sendFile(chatId, uid, ts.absoluteUrl(link.url()), link.name());
        });
    }

    private void startGrade(long chatId, long uid, int courseId, int activityId, int studentId) {
        async(chatId, uid, () -> {
            GradeCtx shown = grading.get(uid);
            GradeCtx ctx = new GradeCtx();
            ctx.courseId = courseId;
            ctx.activityId = activityId;
            ctx.studentId = studentId;
            ctx.form = shown != null && shown.activityId == activityId && shown.studentId == studentId
                    ? shown.form : ts.gradeForm(uid, studentId, activityId);
            GradeForm f = ctx.form;
            if (!f.editable()) {
                bot.send(chatId, "🔒 " + T(uid, "Оценку изменить нельзя — срок прошёл.", "Bahoni o'zgartirib bo'lmaydi — muddat o'tgan.",
                        "Баҳони ўзгартириб бўлмайди — муддат ўтган.", "The grade can't be changed — the deadline has passed."), null);
                return;
            }
            Map<String, String> L = sheet(uid, courseId, false).labels();
            String hint = sheet(uid, courseId, false).hint();
            StringBuilder sb = new StringBuilder("✏️ <b>").append(esc(f.student())).append("</b> · ").append(esc(f.name())).append("\n\n");
            if (f.module() || f.criteria().isEmpty()) {
                sb.append(T(uid, "Введите ", "Kiriting: ", "Киритинг: ", "Enter ")).append("<b>").append(esc(L.getOrDefault("grade", "")))
                        .append("</b> (0 – ").append(esc(f.maxPoint())).append(").");
            } else if (f.criteria().size() == 1) {
                Criterion c = f.criteria().get(0);
                sb.append(T(uid, "Введите балл", "Ballni kiriting", "Баллни киритинг", "Enter the score")).append(": <b>")
                        .append(esc(c.name())).append("</b> (0 – ").append(esc(c.max())).append(").");
            } else {
                sb.append(T(uid, "Введите баллы по критериям <b>через пробел</b>, по порядку:",
                        "Mezonlar bo'yicha ballarni <b>bo'sh joy bilan</b>, tartib bilan kiriting:",
                        "Мезонлар бўйича балларни <b>бўш жой билан</b>, тартиб билан киритинг:",
                        "Enter the scores per criterion <b>separated by spaces</b>, in order:")).append("\n<blockquote>");
                for (int k = 0; k < f.criteria().size(); k++) {
                    Criterion c = f.criteria().get(k);
                    sb.append(k + 1).append(". ").append(esc(c.name())).append(" (0 – ").append(esc(c.max())).append(")\n");
                }
                sb.append("</blockquote>").append(T(uid, "Например", "Masalan", "Масалан", "Example")).append(": <code>");
                StringBuilder ex = new StringBuilder();
                for (Criterion c : f.criteria()) ex.append(c.max()).append(" ");
                sb.append(ex.toString().trim()).append("</code>");
            }
            if (hint != null) sb.append("\n\n<i>").append(esc(hint)).append("</i>");
            sb.append("\n\n/cancel");
            pendingGrade.put(uid, ctx);
            bot.userState.put(uid, "T_GRADE");
            bot.send(chatId, sb.toString(), null);
        });
    }

    private static BigDecimal number(String s) {
        try {
            return new BigDecimal(s.trim().replace(',', '.'));
        } catch (Exception e) {
            return null;
        }
    }

    private static String plainNum(BigDecimal d) {
        BigDecimal v = d.stripTrailingZeros();
        return v.scale() < 0 ? v.setScale(0).toPlainString() : v.toPlainString();
    }

    private void gradeInput(long chatId, long uid, String text) {
        GradeCtx ctx = pendingGrade.get(uid);
        if (ctx == null) { bot.userState.put(uid, "IDLE"); return; }
        GradeForm f = ctx.form;
        String[] parts = text.trim().split("[\\s;]+");
        String bad = T(uid, "⚠️ Не понял число. Дробные — через точку, например <code>4.5</code>. Попробуйте ещё раз или /cancel.",
                "⚠️ Son tushunarsiz. Kasr sonlar nuqta bilan, masalan <code>4.5</code>. Qayta kiriting yoki /cancel.",
                "⚠️ Сон тушунарсиз. Каср сонлар нуқта билан, масалан <code>4.5</code>. Қайта киритинг ёки /cancel.",
                "⚠️ Couldn't read the number. Use a dot for decimals, e.g. <code>4.5</code>. Try again or /cancel.");
        if (f.module() || f.criteria().isEmpty()) {
            BigDecimal v = parts.length == 1 ? number(parts[0]) : null;
            BigDecimal max = number(f.maxPoint());
            if (v == null) { bot.send(chatId, bad, null); return; }
            if (v.signum() < 0 || (max != null && v.compareTo(max) > 0)) {
                bot.send(chatId, T(uid, "⚠️ Оценка должна быть от 0 до ", "⚠️ Baho 0 dan ", "⚠️ Баҳо 0 дан ", "⚠️ The grade must be from 0 to ")
                        + esc(f.maxPoint()) + T(uid, ".", " gacha bo'lishi kerak.", " гача бўлиши керак.", "."), null);
                return;
            }
            ctx.moduleGrade = plainNum(v);
        } else {
            if (parts.length != f.criteria().size()) {
                bot.send(chatId, T(uid, "⚠️ Нужно чисел: ", "⚠️ Kerakli sonlar soni: ", "⚠️ Керакли сонлар сони: ", "⚠️ Numbers needed: ")
                        + f.criteria().size() + ". /cancel", null);
                return;
            }
            Map<String, String> vals = new LinkedHashMap<>();
            for (int k = 0; k < parts.length; k++) {
                Criterion c = f.criteria().get(k);
                BigDecimal v = number(parts[k]);
                BigDecimal max = number(c.max());
                if (v == null) { bot.send(chatId, bad, null); return; }
                if (v.signum() < 0 || (max != null && v.compareTo(max) > 0)) {
                    bot.send(chatId, "⚠️ " + esc(c.name()) + ": 0 – " + esc(c.max()) + ". /cancel", null);
                    return;
                }
                vals.put(c.id(), plainNum(v));
            }
            ctx.values = vals;
        }
        bot.userState.put(uid, "T_GCOMMENT");
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (!f.comment().isBlank())
            rows.add(List.of(btn(T(uid, "💬 Оставить прежний", "💬 Avvalgisini qoldirish", "💬 Аввалгисини қолдириш", "💬 Keep the current one"), "t:gcm:keep")));
        rows.add(List.of(btn(T(uid, "➡️ Без комментария", "➡️ Izohsiz", "➡️ Изоҳсиз", "➡️ No comment"), "t:gcm:none")));
        bot.send(chatId, "💬 " + T(uid, "Комментарий для студента — отправьте текст или нажмите кнопку.",
                "Talaba uchun izoh — matn yuboring yoki tugmani bosing.",
                "Талаба учун изоҳ — матн юборинг ёки тугмани босинг.",
                "A comment for the student — send text or tap a button.")
                + (f.comment().isBlank() ? "" : "\n<blockquote>" + esc(f.comment()) + "</blockquote>"), kb(rows));
    }

    /** comment: null — оставить прежний, "" — без комментария, иначе новый текст. */
    private void finishGrade(long chatId, long uid, String comment) {
        if (!"T_GCOMMENT".equals(bot.userState.get(uid))) return;
        // Забираем сразу: повторное нажатие кнопки не отправит оценку второй раз.
        GradeCtx ctx = pendingGrade.remove(uid);
        if (ctx == null) return;
        bot.userState.put(uid, "IDLE");
        bot.sendProgress(chatId, T(uid, "⏳ Сохраняю оценку...", "⏳ Baho saqlanmoqda...", "⏳ Баҳо сақланмоқда...", "⏳ Saving the grade..."));
        async(chatId, uid, () -> {
            FormResult r = ts.setGrade(uid, ctx.studentId, ctx.activityId, ctx.form, ctx.values, ctx.moduleGrade,
                    comment == null ? ctx.form.comment() : comment);
            if (!r.ok()) {
                bot.send(chatId, "❌ <b>" + T(uid, "LMS не приняла оценку", "LMS bahoni qabul qilmadi", "LMS баҳони қабул қилмади", "LMS rejected the grade")
                        + "</b>\n\n<blockquote>" + errors(r) + "</blockquote>", null);
                return;
            }
            String saved = r.body() != null && r.body().hasNonNull("grade") ? r.body().get("grade").asText() : null;
            sheet(uid, ctx.courseId, true);
            showGradeForm(chatId, uid, null, ctx.courseId, ctx.activityId, ctx.studentId,
                    "✅ <b>" + T(uid, "Оценка сохранена", "Baho saqlandi", "Баҳо сақланди", "Grade saved") + "</b>"
                            + (saved != null && !saved.isBlank() ? ": <b>" + esc(saved) + "</b>" : ""));
        });
    }

    private void confirmClear(long chatId, long uid, int msgId, int courseId, int activityId, int studentId) {
        GradeCtx ctx = grading.get(uid);
        String who = ctx != null && ctx.studentId == studentId ? ctx.form.student() : "";
        bot.edit(chatId, msgId, "🗑 " + T(uid, "Убрать оценку", "Bahoni olib tashlash", "Баҳони олиб ташлаш", "Clear the grade")
                        + (who.isBlank() ? "" : ": <b>" + esc(who) + "</b>") + "?",
                kb(List.of(List.of(btn(T(uid, "🗑 Да", "🗑 Ha", "🗑 Ҳа", "🗑 Yes"), "t:gclr!:" + courseId + ":" + activityId + ":" + studentId),
                        btn(T(uid, "Отмена", "Bekor", "Бекор", "Cancel"), "t:gf:" + courseId + ":" + activityId + ":" + studentId)))));
    }

    private void doClear(long chatId, long uid, int msgId, int courseId, int activityId, int studentId) {
        async(chatId, uid, () -> {
            FormResult r = ts.clearGrade(uid, studentId, activityId);
            sheet(uid, courseId, true);
            showGradeForm(chatId, uid, msgId, courseId, activityId, studentId, r.ok()
                    ? "🗑 <b>" + T(uid, "Оценка убрана", "Baho olib tashlandi", "Баҳо олиб ташланди", "Grade cleared") + "</b>"
                    : "❌ " + errors(r));
        });
    }

    // ─────────────────────────────────────────────
    //  АКТИВНОСТИ
    // ─────────────────────────────────────────────

    private final Map<Long, Map<Integer, TActivities>> activityCache = new ConcurrentHashMap<>();

    private void showActivities(long chatId, long uid, Integer msgId, int courseId) {
        async(chatId, uid, () -> {
            TActivities a = ts.activities(uid, courseId);
            activityCache.computeIfAbsent(uid, k -> new ConcurrentHashMap<>()).put(courseId, a);
            TCourse c = course(uid, courseId);
            StringBuilder sb = new StringBuilder("🧩 <b>").append(esc(courseTitle(c))).append("</b>\n");
            if (a.budgetText() != null) sb.append("🎯 ").append(esc(a.budgetText())).append("\n");
            if (a.note() != null) sb.append("<i>").append(esc(a.note())).append("</i>\n");
            sb.append("\n");
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            for (TActivity t : a.items()) {
                String status = a.statusLabels().getOrDefault(t.status(), String.valueOf(t.status()));
                String icon = switch (t.status()) { case 1 -> "✅"; case 2 -> "❌"; case 3 -> "✏️"; default -> "🕓"; };
                sb.append("<blockquote><b>").append(esc(t.name())).append("</b>").append(t.module() ? " · 📦" : "")
                        .append("\n📅 ").append(esc(t.deadline())).append(" · 🎯 ").append(esc(t.maxPoint()))
                        .append("\n").append(icon).append(" ").append(esc(status));
                if (t.status() == 3 && !t.comment().isBlank()) sb.append("\n💬 <i>").append(esc(t.comment())).append("</i>");
                if (!t.criteria().isEmpty()) {
                    sb.append("\n");
                    for (String[] cr : t.criteria()) sb.append("• ").append(esc(cr[0])).append(" — ").append(esc(cr[1])).append("\n");
                }
                sb.append("</blockquote>\n");
                List<InlineKeyboardButton> line = new ArrayList<>();
                if (t.sampleUrl() != null && !t.sampleUrl().isBlank()) line.add(btn("📎 " + cut(t.name(), 16), "t:acs:" + courseId + ":" + t.id()));
                if (t.deletable() && !t.module()) line.add(btn("🗑 " + cut(t.name(), 16), "t:acdel:" + courseId + ":" + t.id()));
                if (!line.isEmpty()) rows.add(line);
            }
            if (a.items().isEmpty()) sb.append(T(uid, "📭 Активностей пока нет.", "📭 Hozircha aktivliklar yo'q.", "📭 Ҳозирча активликлар йўқ.", "📭 No activities yet.")).append("\n");
            rows.add(List.of(btn(T(uid, "➕ Добавить активность", "➕ Aktivlik qo'shish", "➕ Активлик қўшиш", "➕ Add an activity"), "t:acadd:" + courseId)));
            rows.add(List.of(btn(T(uid, "🔙 Назад", "🔙 Orqaga", "🔙 Орқага", "🔙 Back"), "t:co:" + courseId)));
            show(chatId, msgId, sb.toString(), kb(rows));
        });
    }

    private TActivity cachedActivity(long uid, int courseId, int id) {
        TActivities a = activityCache.getOrDefault(uid, Map.of()).get(courseId);
        if (a == null) return null;
        for (TActivity t : a.items()) if (t.id() == id) return t;
        return null;
    }

    private void activitySample(long chatId, long uid, int courseId, int id) {
        TActivity t = cachedActivity(uid, courseId, id);
        if (t == null || t.sampleUrl() == null) return;
        sendFile(chatId, uid, ts.absoluteUrl(t.sampleUrl()), t.sampleName());
    }

    private void confirmDeleteActivity(long chatId, long uid, int msgId, int courseId, int id) {
        TActivity t = cachedActivity(uid, courseId, id);
        bot.edit(chatId, msgId, "🗑 " + T(uid, "Удалить активность", "Aktivlikni o'chirish", "Активликни ўчириш", "Delete the activity")
                        + " <b>" + esc(t != null ? t.name() : "#" + id) + "</b>?",
                kb(List.of(List.of(btn(T(uid, "🗑 Да, удалить", "🗑 Ha, o'chirish", "🗑 Ҳа, ўчириш", "🗑 Yes, delete"), "t:acdel!:" + courseId + ":" + id),
                        btn(T(uid, "Отмена", "Bekor", "Бекор", "Cancel"), "t:ac:" + courseId)))));
    }

    private void deleteActivity(long chatId, long uid, int msgId, int courseId, int id) {
        async(chatId, uid, () -> {
            FormResult r = ts.deleteActivity(uid, courseId, id);
            if (!r.ok()) bot.send(chatId, "❌ " + errors(r), null);
            sheets.getOrDefault(uid, new HashMap<>()).remove(courseId);
            showActivities(chatId, uid, msgId, courseId);
        });
    }

    private void startActivity(long chatId, long uid, int courseId) {
        async(chatId, uid, () -> {
            TActivities meta = activityCache.getOrDefault(uid, Map.of()).get(courseId);
            if (meta == null) meta = ts.activities(uid, courseId);
            dropDraft(uid);
            ActDraft d = new ActDraft();
            d.courseId = courseId;
            d.meta = meta;
            actDrafts.put(uid, d);
            bot.userState.put(uid, "T_ACT_NAME");
            bot.send(chatId, "➕ <b>" + esc(courseTitle(course(uid, courseId))) + "</b>\n\n"
                    + "✏️ <b>" + esc(meta.labels().getOrDefault("name", "")) + "</b>:\n\n/cancel", null);
        });
    }

    private static final java.util.regex.Pattern DMY = java.util.regex.Pattern.compile("(\\d{1,2})[.\\-/](\\d{1,2})[.\\-/](\\d{4})");

    private void draftInput(long chatId, long uid, String state, String text) {
        ActDraft d = actDrafts.get(uid);
        if (d == null) { bot.userState.put(uid, "IDLE"); return; }
        Map<String, String> L = d.meta.labels();
        switch (state) {
            case "T_ACT_NAME" -> {
                d.name = text.trim();
                bot.userState.put(uid, "T_ACT_DEADLINE");
                bot.send(chatId, "📅 <b>" + esc(L.getOrDefault("deadline", "")) + "</b>\n"
                        + T(uid, "Формат: ДД-ММ-ГГГГ", "Format: KK-OO-YYYY", "Формат: КК-ОО-ЙЙЙЙ", "Format: DD-MM-YYYY")
                        + (d.meta.note() != null ? "\n<i>" + esc(d.meta.note()) + "</i>" : "") + "\n\n/cancel", null);
            }
            case "T_ACT_DEADLINE" -> {
                java.util.regex.Matcher m = DMY.matcher(text.trim());
                LocalDate date = null;
                if (m.matches()) {
                    try { date = LocalDate.of(i(m.group(3)), i(m.group(2)), i(m.group(1))); } catch (Exception ignored) {}
                }
                if (date == null || !date.isAfter(LocalDate.now(AppConfig.LMS_ZONE))) {
                    bot.send(chatId, T(uid, "⚠️ Нужна будущая дата в формате ДД-ММ-ГГГГ.", "⚠️ KK-OO-YYYY formatidagi kelajak sana kerak.",
                            "⚠️ КК-ОО-ЙЙЙЙ форматидаги келажак сана керак.", "⚠️ A future date in DD-MM-YYYY format is needed."), null);
                    return;
                }
                d.deadline = date.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));
                bot.userState.put(uid, "T_ACT_MAX");
                bot.send(chatId, "🎯 <b>" + esc(L.getOrDefault("max_point", "")) + "</b>"
                        + (d.meta.budgetText() != null ? "\n<i>" + esc(d.meta.budgetText()) + "</i>" : "") + "\n\n/cancel", null);
            }
            case "T_ACT_MAX" -> {
                BigDecimal v = number(text);
                BigDecimal left = d.meta.budget() != null ? number(d.meta.budget().left()) : null;
                if (v == null || v.signum() <= 0 || (left != null && v.compareTo(left) > 0)) {
                    bot.send(chatId, "⚠️ " + T(uid, "Нужно положительное число", "Musbat son kerak", "Мусбат сон керак", "A positive number is needed")
                            + (left != null ? " ≤ " + esc(d.meta.budget().left()) : "") + ".", null);
                    return;
                }
                d.max = plainNum(v);
                bot.userState.put(uid, "T_ACT_CRIT");
                String crit = L.getOrDefault("criterion", ""), pts = L.getOrDefault("points", "");
                bot.send(chatId, "📐 <b>" + esc(crit) + "</b> — <b>" + esc(pts) + "</b>\n"
                                + T(uid, "По одному в строке: <code>название — балл</code>. Сумма должна быть равна " + esc(d.max) + ".",
                                "Har qatorda bittadan: <code>nomi — ball</code>. Yig'indi " + esc(d.max) + " ga teng bo'lishi kerak.",
                                "Ҳар қаторда биттадан: <code>номи — балл</code>. Йиғинди " + esc(d.max) + " га тенг бўлиши керак.",
                                "One per line: <code>name — points</code>. The total must equal " + esc(d.max) + ".")
                                + "\n\n/cancel",
                        kb(List.of(List.of(btn("1️⃣ " + cut(d.name, 20) + " — " + d.max, "t:ad:one")))));
            }
            case "T_ACT_CRIT" -> {
                List<String[]> items = new ArrayList<>();
                BigDecimal sum = BigDecimal.ZERO;
                for (String line : text.split("\n")) {
                    if (line.isBlank()) continue;
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.+?)[\\s]*[—–:\\-=]+[\\s]*(\\d+(?:[.,]\\d+)?)\\s*$").matcher(line.trim());
                    BigDecimal v = m.matches() ? number(m.group(2)) : null;
                    if (v == null || v.signum() <= 0) {
                        bot.send(chatId, "⚠️ " + esc(line.trim()) + "\n" + T(uid, "Формат: <code>название — балл</code>", "Format: <code>nomi — ball</code>",
                                "Формат: <code>номи — балл</code>", "Format: <code>name — points</code>"), null);
                        return;
                    }
                    items.add(new String[]{m.group(1).trim(), plainNum(v)});
                    sum = sum.add(v);
                }
                if (items.isEmpty() || sum.compareTo(number(d.max)) != 0) {
                    bot.send(chatId, "⚠️ " + T(uid, "Сумма баллов", "Ballar yig'indisi", "Баллар йиғиндиси", "The total") + " = " + plainNum(sum)
                            + ", " + T(uid, "а нужно", "kerak", "керак", "but must be") + " " + esc(d.max) + ".", null);
                    return;
                }
                d.criteria = items;
                askFile(chatId, uid, d);
            }
            default -> { }
        }
    }

    private void askFile(long chatId, long uid, ActDraft d) {
        bot.userState.put(uid, "T_ACT_FILE");
        StringBuilder sb = new StringBuilder("📎 <b>").append(esc(d.meta.labels().getOrDefault("file", ""))).append("</b>\n");
        if (!d.meta.fileHints().isEmpty()) {
            sb.append("<blockquote>");
            for (String h : d.meta.fileHints()) sb.append(esc(h)).append("\n");
            sb.append("</blockquote>");
        }
        bot.send(chatId, sb.toString().trim(), kb(List.of(List.of(btn(T(uid, "➡️ Без файла", "➡️ Faylsiz", "➡️ Файлсиз", "➡️ No file"), "t:ad:nofile")))));
    }

    private void draftFile(long chatId, long uid, File tmp, String name) {
        ActDraft d = actDrafts.get(uid);
        if (d == null) { tmp.delete(); return; }
        if (d.file != null) d.file.delete();
        d.file = tmp;
        d.fileName = name;
        summary(chatId, uid, d);
    }

    private void summary(long chatId, long uid, ActDraft d) {
        bot.userState.put(uid, "IDLE");
        Map<String, String> L = d.meta.labels();
        StringBuilder sb = new StringBuilder("🧾 <b>").append(esc(courseTitle(course0(uid, d.courseId)))).append("</b>\n\n<blockquote>");
        sb.append(esc(L.getOrDefault("name", ""))).append(": <b>").append(esc(d.name)).append("</b>\n")
                .append(esc(L.getOrDefault("deadline", ""))).append(": <b>").append(esc(d.deadline)).append("</b>\n")
                .append(esc(L.getOrDefault("max_point", ""))).append(": <b>").append(esc(d.max)).append("</b>\n")
                .append(esc(L.getOrDefault("file", ""))).append(": <b>").append(d.fileName != null ? esc(d.fileName) : "—").append("</b>\n")
                .append(esc(L.getOrDefault("criterion", ""))).append(":\n");
        for (String[] c : d.criteria) sb.append("• ").append(esc(c[0])).append(" — ").append(esc(c[1])).append("\n");
        sb.append("</blockquote>");
        bot.send(chatId, sb.toString(), kb(List.of(List.of(
                btn(T(uid, "✅ Создать", "✅ Yaratish", "✅ Яратиш", "✅ Create"), "t:ad:save"),
                btn(T(uid, "❌ Отмена", "❌ Bekor", "❌ Бекор", "❌ Cancel"), "t:ad:cancel")))));
    }

    private TCourse course0(long uid, int id) {
        try { return course(uid, id); } catch (Exception e) { return null; }
    }

    private void draftAction(long chatId, long uid, String action) {
        ActDraft d = actDrafts.get(uid);
        if (d == null) return;
        switch (action) {
            case "one" -> {
                if (!"T_ACT_CRIT".equals(bot.userState.get(uid))) return;
                d.criteria = List.<String[]>of(new String[]{d.name, d.max});
                askFile(chatId, uid, d);
            }
            case "nofile" -> {
                if (!"T_ACT_FILE".equals(bot.userState.get(uid))) return;
                summary(chatId, uid, d);
            }
            case "cancel" -> {
                dropDraft(uid);
                bot.userState.put(uid, "IDLE");
                showActivities(chatId, uid, null, d.courseId);
            }
            case "save" -> {
                if (d.criteria == null) return;
                // Черновик забираем сразу: второе нажатие «Создать» не создаст дубликат.
                if (!actDrafts.remove(uid, d)) return;
                bot.sendProgress(chatId, T(uid, "⏳ Создаю активность...", "⏳ Aktivlik yaratilmoqda...", "⏳ Активлик яратилмоқда...", "⏳ Creating the activity..."));
                async(chatId, uid, () -> {
                    FormResult r;
                    try {
                        r = ts.createActivity(uid, d.courseId, d.name, d.deadline, d.max, d.criteria, d.file, d.fileName);
                    } finally {
                        if (d.file != null) d.file.delete();
                    }
                    if (!r.ok()) {
                        bot.send(chatId, "❌ <b>" + T(uid, "LMS не приняла активность", "LMS aktivlikni qabul qilmadi", "LMS активликни қабул қилмади", "LMS rejected the activity")
                                + "</b>\n\n<blockquote>" + errors(r) + "</blockquote>", kb(List.of(List.of(
                                btn(T(uid, "🔁 Заполнить заново", "🔁 Qaytadan to'ldirish", "🔁 Қайтадан тўлдириш", "🔁 Fill in again"), "t:acadd:" + d.courseId)))));
                        return;
                    }
                    activityCache.getOrDefault(uid, new HashMap<>()).remove(d.courseId);
                    bot.send(chatId, "✅ <b>" + T(uid, "Активность создана", "Aktivlik yaratildi", "Активлик яратилди", "Activity created") + "</b>", null);
                    showActivities(chatId, uid, null, d.courseId);
                });
            }
            default -> { }
        }
    }

    // ─────────────────────────────────────────────
    //  ПРОВЕРКА РАБОТ (сроки со страницы «Активности»)
    // ─────────────────────────────────────────────

    private record GradingItem(TDeadline d, int col, GColumn column, int pending, int submitted, long studentTs, long teacherTs) {}

    /** Сроки из календаря LMS, дополненные данными ведомостей: срок оценивания и число непроверенных. */
    private List<GradingItem> gradingItems(long uid) throws Exception {
        List<GradingItem> out = new ArrayList<>();
        Map<Integer, GradeSheet> cache = sheets.computeIfAbsent(uid, k -> new ConcurrentHashMap<>());
        for (TeacherService.GradingItem g : ts.grading(uid)) {
            if (g.sheet() != null) cache.put(g.deadline().courseId(), g.sheet());
            out.add(new GradingItem(g.deadline(), g.column(), g.info(), g.pending(), g.submitted(), g.studentTs(), g.teacherTs()));
        }
        return out;
    }

    void showGrading(long chatId, long uid, Integer msgId) {
        if (!bot.checkLogin(chatId, uid)) return;
        if (msgId == null) loading(chatId, uid);
        async(chatId, uid, () -> {
            List<GradingItem> items = gradingItems(uid);
            long now = System.currentTimeMillis();
            StringBuilder open = new StringBuilder(), check = new StringBuilder();
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            for (GradingItem g : items) {
                boolean accepting = g.studentTs() > now;
                boolean grading = !accepting && (g.teacherTs() <= 0 || g.teacherTs() > now);
                if (!accepting && !grading) continue;
                StringBuilder b = accepting ? open : check;
                b.append("<blockquote><b>").append(esc(g.d().activity())).append("</b> · ").append(esc(g.d().stream()))
                        .append("\n").append(esc(cut(g.d().subject(), 50)));
                if (accepting) {
                    b.append("\n🎓 ").append(esc(g.column() != null && g.column().studentDeadline() != null ? g.column().studentDeadline() : g.d().start()))
                            .append(" · 📥 ").append(g.submitted());
                } else {
                    if (g.column() != null && g.column().teacherDeadline() != null) b.append("\n👨‍🏫 ").append(esc(g.column().teacherDeadline()));
                    b.append(" · ⏳ ").append(g.pending());
                }
                b.append("</blockquote>\n");
                String data = g.col() >= 0 ? "t:ga:" + g.d().courseId() + ":" + g.col() : "t:gs:" + g.d().courseId();
                rows.add(List.of(btn((accepting ? "🎓 " : "⏳ ") + cut(g.d().activity(), 14) + " · " + cut(g.d().stream(), 14)
                        + (!accepting && g.pending() > 0 ? " (" + g.pending() + ")" : ""), data)));
            }
            StringBuilder sb = new StringBuilder("📝 <b>").append(label(uid, M_GRADING).replace("📝 ", "")).append("</b>\n\n");
            if (check.length() > 0) sb.append("⏳ <b>").append(T(uid, "Ждут вашей оценки", "Bahoingizni kutmoqda", "Баҳоингизни кутмоқда", "Waiting for your grades"))
                    .append("</b>\n").append(check).append("\n");
            if (open.length() > 0) sb.append("🎓 <b>").append(T(uid, "Идёт приём работ", "Ishlar qabul qilinmoqda", "Ишлар қабул қилинмоқда", "Accepting submissions"))
                    .append("</b>\n").append(open);
            if (check.length() == 0 && open.length() == 0)
                sb.append(T(uid, "📭 Сейчас нет открытых сроков.", "📭 Hozir ochiq muddatlar yo'q.", "📭 Ҳозир очиқ муддатлар йўқ.", "📭 No open deadlines right now."));
            rows.add(List.of(btn(T(uid, "🔄 Обновить", "🔄 Yangilash", "🔄 Янгилаш", "🔄 Refresh"), "t:dl")));
            show(chatId, msgId, sb.toString(), kb(rows));
        });
    }

    // ─────────────────────────────────────────────
    //  ИТОГОВЫЕ И ПРОФИЛЬ
    // ─────────────────────────────────────────────

    void showFinals(long chatId, long uid, int sem) {
        if (!bot.checkLogin(chatId, uid)) return;
        loading(chatId, uid);
        async(chatId, uid, () -> {
            List<TFinal> list = ts.finals(uid, sem);
            StringBuilder sb = new StringBuilder("🏆 <b>").append(esc(lms.semesterName(uid, sem))).append("</b>\n\n");
            for (TFinal f : list) {
                sb.append("<blockquote><b>").append(esc(f.subjects())).append("</b>")
                        .append(f.streams().isBlank() ? "" : "\n👥 " + esc(f.streams()))
                        .append("\n📅 ").append(esc(f.date())).append(f.from().isBlank() ? "" : " · ⏰ " + esc(f.from()))
                        .append(f.room().isBlank() ? "" : "\n🚪 " + esc(f.room())).append("</blockquote>\n");
            }
            if (list.isEmpty()) sb.append(T(uid, "📭 Итоговых в этом семестре нет.", "📭 Bu semestrda yakuniy yo'q.", "📭 Бу семестрда якуний йўқ.", "📭 No finals this semester."));
            bot.send(chatId, fit(sb.toString()), bot.buildSemesterKeyboard(uid, "tf_"));
        });
    }

    void showProfile(long chatId, long uid) {
        if (!bot.checkLogin(chatId, uid)) return;
        loading(chatId, uid);
        async(chatId, uid, () -> {
            TeacherInfo info = ts.info(uid);
            StringBuilder sb = new StringBuilder("👤 <b>").append(esc(info.fullName() != null ? info.fullName() : "")).append("</b>\n")
                    .append("<i>").append(T(uid, "Преподаватель", "O'qituvchi", "Ўқитувчи", "Teacher"))
                    .append(lms.hasTutor(uid) ? " · " + T(uid, "тьютор", "tyutor", "тьютор", "tutor") : "").append("</i>\n\n<blockquote>");
            for (String[] f : info.fields().subList(Math.min(1, info.fields().size()), info.fields().size()))
                sb.append(esc(f[0])).append(": <b>").append(esc(f[1])).append("</b>\n");
            sb.append("</blockquote>");
            bot.send(chatId, sb.toString(), kb(List.of(List.of(btn(T(uid, "🖼 Фотография", "🖼 Foto", "🖼 Фото", "🖼 Photo"), "profile_photo")))));
        });
    }

    // ─────────────────────────────────────────────
    //  ИСПРАВЛЕНИЕ НБ
    // ─────────────────────────────────────────────

    void showAppeals(long chatId, long uid, Integer msgId) {
        if (!bot.checkLogin(chatId, uid)) return;
        if (msgId == null) loading(chatId, uid);
        async(chatId, uid, () -> {
            AppealPage page = ts.appealPage(uid);
            List<TAppeal> list = ts.appeals(uid);
            StringBuilder sb = new StringBuilder("📨 <b>").append(esc(page.title() != null ? page.title() : "")).append("</b>\n\n");
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            for (TAppeal a : list.subList(0, Math.min(15, list.size()))) {
                String icon = switch (a.status()) { case 1 -> "✅"; case 0 -> "🕓"; default -> "❌"; };
                sb.append("<blockquote>#").append(a.id()).append(" · <b>").append(esc(a.stream())).append("</b> · ").append(esc(a.date()))
                        .append(a.pair().isBlank() ? "" : " · " + esc(a.pair()))
                        .append("\n").append(esc(cut(a.theme(), 60)))
                        .append("\n👤 ").append(esc(cut(a.students(), 80)))
                        .append("\n").append(icon).append(" ").append(esc(a.statusText())).append("</blockquote>\n");
                if (a.status() == 1) rows.add(List.of(btn("📄 PDF #" + a.id(), "t:apf:" + a.id())));
                else if (a.status() == 0) rows.add(List.of(btn("🗑 #" + a.id(), "t:apd:" + a.id())));
            }
            if (list.isEmpty()) sb.append(T(uid, "📭 Заявлений пока нет.", "📭 Hozircha arizalar yo'q.", "📭 Ҳозирча аризалар йўқ.", "📭 No requests yet.")).append("\n");
            if (page.instructions() != null) sb.append("\n<blockquote expandable>").append(esc(page.instructions())).append("</blockquote>");
            rows.add(0, List.of(btn(T(uid, "➕ Новое заявление", "➕ Yangi ariza", "➕ Янги ариза", "➕ New request"), "t:apn")));
            show(chatId, msgId, sb.toString(), kb(rows));
        });
    }

    private AppealDraft draftAppeal(long uid) throws Exception {
        AppealDraft d = appeals.get(uid);
        if (d == null) {
            d = new AppealDraft();
            d.page = ts.appealPage(uid);
            appeals.put(uid, d);
        }
        return d;
    }

    private InlineKeyboardButton cancelAppeal(long uid) {
        return btn(T(uid, "❌ Отмена", "❌ Bekor", "❌ Бекор", "❌ Cancel"), "t:apx");
    }

    private void appealStreams(long chatId, long uid, int msgId) {
        async(chatId, uid, () -> {
            appeals.remove(uid);
            AppealDraft d = draftAppeal(uid);
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            List<InlineKeyboardButton> line = new ArrayList<>();
            for (int k = 0; k < d.page.streams().size(); k++) {
                line.add(btn(d.page.streams().get(k).text(), "t:aps:" + k));
                if (line.size() == 3) { rows.add(line); line = new ArrayList<>(); }
            }
            if (!line.isEmpty()) rows.add(line);
            rows.add(List.of(cancelAppeal(uid)));
            show(chatId, msgId, "📨 <b>" + esc(d.page.labels().getOrDefault("course_part_id", "")) + "</b>:", kb(rows));
        });
    }

    /** Сколько кнопок-строк показываем за раз: у Telegram предел 100 кнопок на сообщение. */
    private static final int PICK_PAGE = 30;

    /** Навигация по страницам списка кнопок; prefix + номер страницы. */
    private List<InlineKeyboardButton> pager(int page, int pages, String prefix) {
        return List.of(btn("◀️", prefix + (page > 0 ? page - 1 : pages - 1)), btn((page + 1) + "/" + pages, "noop"),
                btn("▶️", prefix + (page < pages - 1 ? page + 1 : 0)));
    }

    private void appealLessons(long chatId, long uid, int msgId, int idx, int page) {
        async(chatId, uid, () -> {
            AppealDraft d = draftAppeal(uid);
            if (idx >= d.page.streams().size()) return;
            Option stream = d.page.streams().get(idx);
            if (d.stream == null || !d.stream.id().equals(stream.id()) || d.lessons.isEmpty()) {
                d.stream = stream;
                d.lessons = ts.appealLessons(uid, stream.id());
            }
            // Шаг сменился — всё, что выбрано дальше по цепочке, больше не действует.
            d.lesson = null;
            d.students = List.of();
            d.student = null;
            d.pair = null;
            int pages = Math.max(1, (d.lessons.size() + PICK_PAGE - 1) / PICK_PAGE);
            int pg = Math.max(0, Math.min(page, pages - 1));
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            for (int k = pg * PICK_PAGE; k < Math.min(d.lessons.size(), (pg + 1) * PICK_PAGE); k++)
                rows.add(List.of(btn(cut(d.lessons.get(k).text(), 60), "t:apl:" + k)));
            if (d.lessons.isEmpty()) rows.add(List.of(btn("📭 —", "noop")));
            if (pages > 1) rows.add(pager(pg, pages, "t:aps:" + idx + ":"));
            rows.add(List.of(cancelAppeal(uid)));
            show(chatId, msgId, "📨 <b>" + esc(d.stream.text()) + "</b>\n\n<b>" + esc(d.page.labels().getOrDefault("teacher_calendar_id", "")) + "</b>:", kb(rows));
        });
    }

    private void appealStudents(long chatId, long uid, int msgId, int idx, int page) {
        async(chatId, uid, () -> {
            AppealDraft d = draftAppeal(uid);
            if (d.stream == null || idx >= d.lessons.size()) return;
            Select2Item lesson = d.lessons.get(idx);
            if (d.lesson == null || !d.lesson.id().equals(lesson.id()) || d.students.isEmpty()) {
                d.lesson = lesson;
                List<Select2Item> list = new ArrayList<>(ts.appealStudents(uid, lesson.id()));
                // Сначала те, у кого НБ: заявление подают на них.
                list.sort(Comparator.comparing((Select2Item s) -> !Boolean.FALSE.equals(s.present())).thenComparing(Select2Item::text));
                d.students = list;
            }
            d.student = null;
            d.pair = null;
            int pages = Math.max(1, (d.students.size() + PICK_PAGE - 1) / PICK_PAGE);
            int pg = Math.max(0, Math.min(page, pages - 1));
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            for (int k = pg * PICK_PAGE; k < Math.min(d.students.size(), (pg + 1) * PICK_PAGE); k++) {
                Select2Item s = d.students.get(k);
                rows.add(List.of(btn((Boolean.FALSE.equals(s.present()) ? "🔴 " : "✅ ") + cut(s.text(), 50), "t:apt:" + k)));
            }
            if (pages > 1) rows.add(pager(pg, pages, "t:apl:" + idx + ":"));
            rows.add(List.of(cancelAppeal(uid)));
            show(chatId, msgId, "📨 <b>" + esc(d.stream.text()) + "</b> · " + esc(cut(d.lesson.text(), 60)) + "\n\n<b>"
                    + esc(d.page.labels().getOrDefault("students[]", "")) + "</b>:\n🔴 — " + esc(T(uid, "НБ", "NB", "НБ", "absent")), kb(rows));
        });
    }

    private void appealPair(long chatId, long uid, int msgId, int idx) {
        AppealDraft d = appeals.get(uid);
        if (d == null || idx >= d.students.size()) return;
        d.student = d.students.get(idx);
        bot.userState.put(uid, "T_AP_PAIR");
        bot.edit(chatId, msgId, "📨 <b>" + esc(d.student.text()) + "</b>\n\n✏️ <b>" + esc(d.page.labels().getOrDefault("pair", "")) + "</b> — "
                + T(uid, "отправьте номер числом.", "raqamini yuboring.", "рақамини юборинг.", "send the number.") + "\n\n/cancel", kb(List.of(List.of(cancelAppeal(uid)))));
    }

    private void appealPairInput(long chatId, long uid, String text) {
        AppealDraft d = appeals.get(uid);
        if (d == null || d.student == null) { bot.userState.put(uid, "IDLE"); return; }
        if (!text.trim().matches("\\d{1,2}")) {
            bot.send(chatId, T(uid, "⚠️ Нужно число.", "⚠️ Son kerak.", "⚠️ Сон керак.", "⚠️ A number is needed."), null);
            return;
        }
        d.pair = text.trim();
        bot.userState.put(uid, "IDLE");
        Map<String, String> L = d.page.labels();
        bot.send(chatId, "🧾 <b>" + esc(d.page.title() != null ? d.page.title() : "") + "</b>\n\n<blockquote>"
                        + esc(L.getOrDefault("course_part_id", "")) + ": <b>" + esc(d.stream.text()) + "</b>\n"
                        + esc(L.getOrDefault("teacher_calendar_id", "")) + ": <b>" + esc(d.lesson.text()) + "</b>\n"
                        + esc(L.getOrDefault("pair", "")) + ": <b>" + esc(d.pair) + "</b>\n"
                        + esc(L.getOrDefault("students[]", "")) + ": <b>" + esc(d.student.text()) + "</b></blockquote>",
                kb(List.of(List.of(btn(T(uid, "✅ Отправить", "✅ Yuborish", "✅ Юбориш", "✅ Submit"), "t:ap!"), cancelAppeal(uid)))));
    }

    private void appealSubmit(long chatId, long uid, int msgId) {
        // Забираем черновик сразу: второе нажатие «Отправить» не создаст второе заявление.
        AppealDraft d = appeals.remove(uid);
        if (d == null || d.pair == null || d.stream == null || d.lesson == null || d.student == null) return;
        async(chatId, uid, () -> {
            FormResult r = ts.createAppeal(uid, d.stream.id(), d.lesson.id(), d.pair, d.student.id());
            if (!r.ok()) {
                bot.edit(chatId, msgId, "❌ <b>" + T(uid, "LMS не приняла заявление", "LMS arizani qabul qilmadi", "LMS аризани қабул қилмади", "LMS rejected the request")
                        + "</b>\n\n<blockquote>" + errors(r) + "</blockquote>", kb(List.of(List.of(btn("🔙", "t:ap")))));
                return;
            }
            appeals.remove(uid);
            bot.edit(chatId, msgId, "✅ <b>" + T(uid, "Заявление отправлено", "Ariza yuborildi", "Ариза юборилди", "Request submitted") + "</b>", null);
            showAppeals(chatId, uid, null);
        });
    }

    private void confirmDeleteAppeal(long chatId, long uid, int msgId, int id) {
        bot.edit(chatId, msgId, "🗑 " + T(uid, "Удалить заявление", "Arizani o'chirish", "Аризани ўчириш", "Delete request") + " #" + id + "?",
                kb(List.of(List.of(btn(T(uid, "🗑 Да", "🗑 Ha", "🗑 Ҳа", "🗑 Yes"), "t:apd!:" + id), btn(T(uid, "Отмена", "Bekor", "Бекор", "Cancel"), "t:ap")))));
    }

    private void deleteAppeal(long chatId, long uid, int msgId, int id) {
        async(chatId, uid, () -> {
            FormResult r = ts.deleteAppeal(uid, id);
            if (!r.ok()) bot.send(chatId, "❌ " + errors(r), null);
            showAppeals(chatId, uid, msgId);
        });
    }

    // ─────────────────────────────────────────────
    //  УЧЕБНЫЕ МАТЕРИАЛЫ
    // ─────────────────────────────────────────────

    void showSubjects(long chatId, long uid, Integer msgId) {
        if (!bot.checkLogin(chatId, uid)) return;
        int sem = currentSemester(uid);
        if (!bot.checkSemester(chatId, uid, sem)) return;
        if (msgId == null) loading(chatId, uid);
        async(chatId, uid, () -> {
            List<TSubject> list = ts.materialSubjects(uid, sem);
            subjects.put(uid, list);
            StringBuilder sb = new StringBuilder("📂 <b>").append(esc(lms.semesterName(uid, sem))).append("</b>\n\n");
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            for (int k = 0; k < list.size(); k++) {
                TSubject s = list.get(k);
                sb.append("<blockquote><b>").append(k + 1).append(". ").append(esc(s.subject())).append("</b> (").append(esc(s.language())).append(")")
                        .append("\n").append(esc(s.code())).append(s.department().isBlank() ? "" : " · " + esc(cut(s.department(), 60))).append("</blockquote>\n");
                rows.add(List.of(btn((k + 1) + ". " + cut(s.subject(), 36) + " (" + s.language() + ")", "t:msu:" + k)));
            }
            if (list.isEmpty()) sb.append(T(uid, "📭 Предметов нет.", "📭 Fanlar yo'q.", "📭 Фанлар йўқ.", "📭 No subjects."));
            show(chatId, msgId, sb.toString(), kb(rows));
        });
    }

    private MatCtx mat(long uid) {
        return materials.computeIfAbsent(uid, k -> new MatCtx());
    }

    private void showSubject(long chatId, long uid, int msgId, int idx) {
        async(chatId, uid, () -> {
            List<TSubject> list = subjects.get(uid);
            if (list == null || idx >= list.size()) { showSubjects(chatId, uid, msgId); return; }
            TSubject s = list.get(idx);
            MatCtx m = new MatCtx();
            m.subjectId = s.id();
            m.semesterId = currentSemester(uid);
            m.subject = s.subject();
            m.lang = s.language().toLowerCase(Locale.ROOT);
            m.page = ts.materialPage(uid, m.subjectId, m.semesterId, m.lang);
            materials.put(uid, m);
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            List<InlineKeyboardButton> line = new ArrayList<>();
            for (Option o : m.page.lessonTypes()) line.add(btn(o.text(), "t:mt:" + o.id() + ":0"));
            if (!line.isEmpty()) rows.add(line);
            rows.add(List.of(btn(T(uid, "🔙 Предметы", "🔙 Fanlar", "🔙 Фанлар", "🔙 Subjects"), "t:ms")));
            show(chatId, msgId, "📂 <b>" + esc(m.page.title() != null ? m.page.title() : s.subject()) + "</b>", kb(rows));
        });
    }

    private static final int TOPICS_PAGE = 10;

    private void showTopics(long chatId, long uid, int msgId, String type, int page) {
        async(chatId, uid, () -> {
            MatCtx m = mat(uid);
            if (m.page == null) { showSubjects(chatId, uid, msgId); return; }
            if (!type.equals(m.lessonType) || m.topics.isEmpty()) {
                m.lessonType = type;
                m.topics = ts.materialTopics(uid, m.subjectId, m.semesterId, type, m.lang);
            }
            int pages = Math.max(1, (m.topics.size() + TOPICS_PAGE - 1) / TOPICS_PAGE);
            int pg = Math.max(0, Math.min(page, pages - 1));
            String typeName = type;
            for (Option o : m.page.lessonTypes()) if (o.id().equals(type)) typeName = o.text();
            StringBuilder sb = new StringBuilder("📂 <b>").append(esc(m.subject)).append("</b> · ").append(esc(typeName)).append("\n\n<blockquote>");
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            List<InlineKeyboardButton> line = new ArrayList<>();
            for (int k = pg * TOPICS_PAGE; k < Math.min(m.topics.size(), (pg + 1) * TOPICS_PAGE); k++) {
                TTopic t = m.topics.get(k);
                sb.append("<b>").append(esc(t.number())).append(".</b> ").append(esc(cut(t.name(), 70)))
                        .append(t.resources().isEmpty() ? "" : " · 📎" + t.resources().size()).append("\n");
                line.add(btn(t.number() + (t.resources().isEmpty() ? "" : " 📎"), "t:mtp:" + t.id()));
                if (line.size() == 5) { rows.add(line); line = new ArrayList<>(); }
            }
            sb.append("</blockquote>");
            if (m.topics.isEmpty()) sb = new StringBuilder("📂 <b>" + esc(m.subject) + "</b> · " + esc(typeName) + "\n\n📭");
            if (!line.isEmpty()) rows.add(line);
            if (pages > 1) rows.add(List.of(btn("◀️", "t:mt:" + type + ":" + (pg > 0 ? pg - 1 : pages - 1)),
                    btn((pg + 1) + "/" + pages, "noop"), btn("▶️", "t:mt:" + type + ":" + (pg < pages - 1 ? pg + 1 : 0))));
            rows.add(List.of(btn(T(uid, "🔙 Назад", "🔙 Orqaga", "🔙 Орқага", "🔙 Back"), "t:ms")));
            show(chatId, msgId, sb.toString(), kb(rows));
        });
    }

    private TTopic topic(MatCtx m, int id) {
        for (TTopic t : m.topics) if (t.id() == id) return t;
        return null;
    }

    private String contentName(MatCtx m, String type) {
        if (m.page != null) for (Option o : m.page.contentTypes()) if (o.id().equals(type)) return o.text();
        return type;
    }

    private void showTopic(long chatId, long uid, Integer msgId, int topicId) {
        async(chatId, uid, () -> {
            MatCtx m = mat(uid);
            TTopic t = topic(m, topicId);
            if (t == null) { showSubjects(chatId, uid, msgId); return; }
            m.topicId = topicId;
            StringBuilder sb = new StringBuilder("📂 <b>").append(esc(t.number())).append(". ").append(esc(t.name())).append("</b>\n<i>")
                    .append(esc(m.subject)).append("</i>\n\n");
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            for (TResource r : t.resources()) {
                String name = r.name().isBlank() ? contentName(m, r.type()) : r.name();
                sb.append("• ").append(esc(contentName(m, r.type()))).append(": <b>").append(esc(name)).append("</b>\n");
                List<InlineKeyboardButton> line = new ArrayList<>();
                if (r.url() != null && !r.url().isBlank()) line.add(btn("⬇️ " + cut(name, 24), "t:mdl:" + r.id()));
                if (!r.pastDate()) line.add(btn("🗑 " + cut(name, 12), "t:mdel:" + r.id()));
                if (!line.isEmpty()) rows.add(line);
            }
            if (t.resources().isEmpty()) sb.append(T(uid, "📭 Материалов пока нет.", "📭 Hozircha materiallar yo'q.", "📭 Ҳозирча материаллар йўқ.", "📭 No materials yet.")).append("\n");
            sb.append("\n➕ ").append(T(uid, "Добавить:", "Qo'shish:", "Қўшиш:", "Add:"));
            List<InlineKeyboardButton> line = new ArrayList<>();
            // Виды материалов — из формы сайта; видеоконференция требует даты и пары, её оставляем сайту.
            for (Option o : m.page.contentTypes()) {
                if ("meeting".equals(o.id())) continue;
                line.add(btn("➕ " + o.text(), "t:madd:" + o.id()));
                if (line.size() == 3) { rows.add(line); line = new ArrayList<>(); }
            }
            if (!line.isEmpty()) rows.add(line);
            rows.add(List.of(btn(T(uid, "🔙 Темы", "🔙 Mavzular", "🔙 Мавзулар", "🔙 Topics"), "t:mt:" + m.lessonType + ":0")));
            show(chatId, msgId, sb.toString(), kb(rows));
        });
    }

    private TResource resource(MatCtx m, int id) {
        for (TTopic t : m.topics) for (TResource r : t.resources()) if (r.id() == id) return r;
        return null;
    }

    private void materialDownload(long chatId, long uid, int id) {
        MatCtx m = mat(uid);
        TResource r = resource(m, id);
        if (r == null) return;
        String url = ts.absoluteUrl(r.url());
        if ("url".equals(r.type()) || "meeting".equals(r.type()) || !url.startsWith(lms.baseUrl())) {
            bot.send(chatId, "🔗 <b>" + esc(r.name().isBlank() ? contentName(m, r.type()) : r.name()) + "</b>\n" + esc(url), null);
            return;
        }
        sendFile(chatId, uid, url, r.name());
    }

    private void materialAdd(long chatId, long uid, int msgId, String type) {
        MatCtx m = mat(uid);
        if (m.page == null || topic(m, m.topicId) == null) return;
        m.contentType = type;
        Map<String, String> L = m.page.labels();
        if ("url".equals(type)) {
            bot.userState.put(uid, "T_MAT_URL");
            bot.send(chatId, "🔗 <b>" + esc(L.getOrDefault("url", "URL")) + "</b> — " + T(uid, "отправьте ссылку.", "havolani yuboring.", "ҳаволани юборинг.", "send the link.")
                    + "\n\n/cancel", null);
        } else {
            bot.userState.put(uid, "T_MAT_FILE");
            bot.send(chatId, "📎 <b>" + esc(contentName(m, type)) + "</b> — " + T(uid,
                    "отправьте файл. Подпись к файлу станет полем «" + esc(L.getOrDefault("name", "")) + "», без подписи — имя файла.",
                    "fayl yuboring. Faylga izoh «" + esc(L.getOrDefault("name", "")) + "» maydoniga yoziladi, izohsiz — fayl nomi.",
                    "файл юборинг. Файлга изоҳ «" + esc(L.getOrDefault("name", "")) + "» майдонига ёзилади, изоҳсиз — файл номи.",
                    "send the file. Its caption becomes «" + esc(L.getOrDefault("name", "")) + "», without one — the file name.") + "\n\n/cancel", null);
        }
    }

    private void materialUrl(long chatId, long uid, String text) {
        String url = text.trim();
        if (!url.matches("(?i)https?://\\S+")) {
            bot.send(chatId, T(uid, "⚠️ Нужна ссылка вида https://…", "⚠️ https://… ko'rinishidagi havola kerak", "⚠️ https://… кўринишидаги ҳавола керак", "⚠️ A link like https://… is needed"), null);
            return;
        }
        bot.userState.put(uid, "IDLE");
        saveMaterial(chatId, uid, null, url, null, null);
    }

    private void materialFile(long chatId, long uid, File tmp, String name, String caption) {
        bot.userState.put(uid, "IDLE");
        String title = caption != null && !caption.isBlank() ? caption.trim() : name.replaceFirst("\\.[^.]+$", "");
        saveMaterial(chatId, uid, title, null, tmp, name);
    }

    private void saveMaterial(long chatId, long uid, String name, String url, File file, String fileName) {
        MatCtx m = mat(uid);
        bot.sendProgress(chatId, T(uid, "⏳ Отправляю в LMS...", "⏳ LMS ga yuborilmoqda...", "⏳ LMS га юборилмоқда...", "⏳ Sending to LMS..."));
        async(chatId, uid, () -> {
            try {
                FormResult r = ts.addMaterial(uid, m.subjectId, m.semesterId, m.topicId, m.lang, m.contentType, name, url, file, fileName);
                if (!r.ok()) {
                    bot.send(chatId, "❌ <b>" + T(uid, "LMS не приняла материал", "LMS materialni qabul qilmadi", "LMS материални қабул қилмади", "LMS rejected the material")
                            + "</b>\n\n<blockquote>" + errors(r) + "</blockquote>", null);
                    return;
                }
                m.topics = ts.materialTopics(uid, m.subjectId, m.semesterId, m.lessonType, m.lang);
                bot.send(chatId, "✅ <b>" + T(uid, "Материал добавлен", "Material qo'shildi", "Материал қўшилди", "Material added") + "</b>", null);
                showTopic(chatId, uid, null, m.topicId);
            } finally {
                if (file != null) file.delete();
            }
        });
    }

    private void confirmDeleteMaterial(long chatId, long uid, int msgId, int id) {
        MatCtx m = mat(uid);
        TResource r = resource(m, id);
        bot.edit(chatId, msgId, "🗑 " + T(uid, "Удалить материал", "Materialni o'chirish", "Материални ўчириш", "Delete the material")
                        + " <b>" + esc(r != null ? (r.name().isBlank() ? contentName(m, r.type()) : r.name()) : "#" + id) + "</b>?",
                kb(List.of(List.of(btn(T(uid, "🗑 Да", "🗑 Ha", "🗑 Ҳа", "🗑 Yes"), "t:mdel!:" + id),
                        btn(T(uid, "Отмена", "Bekor", "Бекор", "Cancel"), "t:mtp:" + m.topicId)))));
    }

    private void deleteMaterial(long chatId, long uid, int msgId, int id) {
        async(chatId, uid, () -> {
            MatCtx m = mat(uid);
            FormResult r = ts.deleteMaterial(uid, id);
            if (!r.ok()) bot.send(chatId, "❌ " + errors(r), null);
            m.topics = ts.materialTopics(uid, m.subjectId, m.semesterId, m.lessonType, m.lang);
            showTopic(chatId, uid, msgId, m.topicId);
        });
    }

    // ─────────────────────────────────────────────
    //  ТЬЮТОР
    // ─────────────────────────────────────────────

    void showGroups(long chatId, long uid, Integer msgId) {
        if (!bot.checkLogin(chatId, uid)) return;
        if (!lms.hasTutor(uid)) {
            bot.send(chatId, T(uid, "👥 Режим тьютора в LMS вам не назначен.", "👥 LMS da sizga tyutor rejimi berilmagan.",
                    "👥 LMS да сизга тьютор режими берилмаган.", "👥 Tutor mode is not assigned to you in LMS."), null);
            return;
        }
        if (msgId == null) loading(chatId, uid);
        async(chatId, uid, () -> {
            List<TGroup> list = ts.tutorGroups(uid);
            Map<Integer, TGroup> m = new LinkedHashMap<>();
            list.forEach(g -> m.put(g.id(), g));
            groups.put(uid, m);
            StringBuilder sb = new StringBuilder("👥 <b>").append(T(uid, "Мои группы", "Guruhlarim", "Гуруҳларим", "My groups")).append("</b>\n\n");
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            for (TGroup g : list) {
                sb.append("<blockquote><b>").append(esc(g.name())).append("</b>\n").append(esc(g.speciality())).append("</blockquote>\n");
                rows.add(List.of(btn("👥 " + g.name(), "t:tgs:" + g.id())));
            }
            if (list.isEmpty()) sb.append("📭");
            show(chatId, msgId, sb.toString(), kb(rows));
        });
    }

    private static String nbIcon(int nb) {
        return nb >= 5 ? "🔴" : nb >= 3 ? "🟠" : nb > 0 ? "🟡" : "🟢";
    }

    private void showGroup(long chatId, long uid, int msgId, int groupId) {
        async(chatId, uid, () -> {
            List<TStudent> list = ts.tutorStudents(uid, groupId);
            groupStudents.computeIfAbsent(uid, k -> new ConcurrentHashMap<>()).put(groupId, list);
            Map<Integer, Integer> sg = studentGroup.computeIfAbsent(uid, k -> new ConcurrentHashMap<>());
            TGroup g = groups.getOrDefault(uid, Map.of()).get(groupId);
            StringBuilder sb = new StringBuilder("👥 <b>").append(esc(g != null ? g.name() : "#" + groupId)).append("</b> · ")
                    .append(list.size()).append("\n\n<blockquote>");
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            List<InlineKeyboardButton> line = new ArrayList<>();
            for (int k = 0; k < list.size(); k++) {
                TStudent s = list.get(k);
                sg.put(s.id(), groupId);
                sb.append(nbIcon(s.attendance())).append(" ").append(k + 1).append(". ").append(esc(cut(s.fio(), 38)))
                        .append(" — НБ <b>").append(s.attendance()).append("</b>\n");
                line.add(btn((k + 1) + " " + nbIcon(s.attendance()), "t:tst:" + s.id()));
                if (line.size() == 5) { rows.add(line); line = new ArrayList<>(); }
            }
            sb.append("</blockquote>");
            if (!line.isEmpty()) rows.add(line);
            rows.add(List.of(btn(T(uid, "🔙 Группы", "🔙 Guruhlar", "🔙 Гуруҳлар", "🔙 Groups"), "t:tg")));
            show(chatId, msgId, sb.toString(), kb(rows));
        });
    }

    private TutorStudent tutorStudent(long uid, int sid) throws Exception {
        Map<Integer, TutorStudent> m = tutorStudents.computeIfAbsent(uid, k -> new ConcurrentHashMap<>());
        TutorStudent t = m.get(sid);
        if (t == null) {
            t = ts.tutorStudent(uid, sid);
            m.put(sid, t);
        }
        return t;
    }

    private String semName(TutorStudent t, String sem) {
        for (Option o : t.semesters()) if (o.id().equals(sem)) return o.text();
        return sem;
    }

    private void showStudent(long chatId, long uid, int msgId, int sid, String sem) {
        async(chatId, uid, () -> {
            TutorStudent t = tutorStudent(uid, sid);
            String s = sem != null ? sem : t.currentSemester();
            StringBuilder sb = new StringBuilder("🎓 <b>").append(esc(t.title())).append("</b>\n<i>").append(esc(semName(t, s))).append("</i>\n\n");
            if (t.userId() != null && s != null) {
                List<TStudentCourse> list = ts.tutorStudentCourses(uid, t.userId(), s);
                for (TStudentCourse c : list) {
                    sb.append("<blockquote><b>").append(esc(c.subject())).append("</b>").append(c.failed() ? " ⚠️" : "")
                            .append("\n").append(nbIcon(c.attendance())).append(" НБ: <b>").append(c.attendance()).append("</b>");
                    for (String[] tt : c.teachers()) sb.append("\n👨‍🏫 ").append(esc(tt[0])).append(" — ").append(esc(tt[1]));
                    sb.append("</blockquote>\n");
                }
                if (list.isEmpty()) sb.append("📭\n");
            }
            Integer groupId = studentGroup.getOrDefault(uid, Map.of()).get(sid);
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            List<InlineKeyboardButton> top = new ArrayList<>();
            top.add(btn(T(uid, "📖 Учебный план", "📖 O'quv reja", "📖 Ўқув режа", "📖 Study plan"), "t:tsp:" + sid));
            if (s != null) top.add(btn(T(uid, "📅 Расписание", "📅 Jadval", "📅 Жадвал", "📅 Timetable"), "t:tss:" + sid + ":" + s));
            rows.add(top);
            rows.add(List.of(btn(T(uid, "📅 Другой семестр", "📅 Boshqa semestr", "📅 Бошқа семестр", "📅 Another semester"), "t:tsm:" + sid + ":0")));
            if (groupId != null) rows.add(List.of(btn(T(uid, "🔙 Группа", "🔙 Guruh", "🔙 Гуруҳ", "🔙 Group"), "t:tgs:" + groupId)));
            show(chatId, msgId, sb.toString(), kb(rows));
        });
    }

    private void studentSemesters(long chatId, long uid, int msgId, int sid, int page) {
        async(chatId, uid, () -> {
            TutorStudent t = tutorStudent(uid, sid);
            int size = 6, pages = Math.max(1, (t.semesters().size() + size - 1) / size);
            int pg = Math.max(0, Math.min(page, pages - 1));
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            for (int k = pg * size; k < Math.min(t.semesters().size(), (pg + 1) * size); k++) {
                Option o = t.semesters().get(k);
                rows.add(List.of(btn(cut(o.text(), 50), "t:tst:" + sid + ":" + o.id())));
            }
            if (pages > 1) rows.add(List.of(btn("◀️", "t:tsm:" + sid + ":" + (pg > 0 ? pg - 1 : pages - 1)),
                    btn((pg + 1) + "/" + pages, "noop"), btn("▶️", "t:tsm:" + sid + ":" + (pg < pages - 1 ? pg + 1 : 0))));
            show(chatId, msgId, "📅 <b>" + esc(t.title()) + "</b>", kb(rows));
        });
    }

    private void showStudentPlan(long chatId, long uid, int msgId, int sid) {
        async(chatId, uid, () -> {
            TutorStudent t = tutorStudent(uid, sid);
            List<StudyPlanSubject> plan = ts.tutorStudyPlan(uid, sid);
            StringBuilder sb = new StringBuilder("📖 <b>").append(esc(t.title())).append("</b>\n");
            long credits = 0, points = 0;
            int sem = -1;
            StringBuilder block = new StringBuilder();
            for (StudyPlanSubject s : plan) {
                if (s.getSemester() != sem) {
                    if (block.length() > 0) sb.append("<blockquote>").append(block.toString().trim()).append("</blockquote>");
                    block.setLength(0);
                    sem = s.getSemester();
                    sb.append("\n<b>").append(sem).append(" ").append(bot.t(uid, "plan.semester_suffix")).append("</b>\n");
                }
                Integer g = s.getGrade();
                block.append(g != null ? bot.gradeIcon(g) : "⬜").append(" ").append(esc(cut(s.getName(), 40)))
                        .append(" · ").append(s.getCredits()).append(" ").append(bot.t(uid, "plan.credits"))
                        .append(g != null ? " · <b>" + g + "</b>" : "").append("\n");
                if (g != null) { credits += s.getCredits(); points += (long) g * s.getCredits(); }
            }
            if (block.length() > 0) sb.append("<blockquote>").append(block.toString().trim()).append("</blockquote>");
            if (credits > 0) sb.append("\n📊 GPA: <b>").append(String.format(Locale.ROOT, "%.2f", points * 1.0 / credits)).append("</b>");
            show(chatId, msgId, sb.toString(), kb(List.of(List.of(btn(T(uid, "🔙 Назад", "🔙 Orqaga", "🔙 Орқага", "🔙 Back"), "t:tst:" + sid)))));
        });
    }

    private void showStudentSchedule(long chatId, long uid, int msgId, int sid, String sem) {
        async(chatId, uid, () -> {
            TutorStudent t = tutorStudent(uid, sid);
            List<ScheduleEvent> events = ts.tutorStudentSchedule(uid, sid, t.userId(), sem);
            TeacherService.ScheduleMeta meta = ts.scheduleMeta(uid);
            String[] days = bot.dayNames(uid);
            Map<Integer, List<String>> byDay = new TreeMap<>();
            List<ScheduleEvent> sorted = new ArrayList<>(events);
            sorted.sort(Comparator.comparing(ScheduleEvent::getStart));
            Map<Integer, List<String[]>> slots = new TreeMap<>();
            for (ScheduleEvent e : sorted) {
                LocalDateTime st;
                try { st = LocalDateTime.parse(e.getStart().trim().replace(" ", "T")); } catch (Exception ex) { continue; }
                String week = e.getType() == 2 ? meta.oddWeek() : e.getType() == 3 ? meta.evenWeek() : null;
                slots.computeIfAbsent(st.getDayOfWeek().getValue(), k -> new ArrayList<>())
                        .add(new String[]{st.toLocalTime().toString().substring(0, 5), e.getTitle().replace("\n", " "), week});
            }
            StringBuilder sb = new StringBuilder("📅 <b>").append(esc(t.title())).append("</b>\n<i>").append(esc(semName(t, sem))).append("</i>\n\n");
            for (Map.Entry<Integer, List<String[]>> d : slots.entrySet()) {
                sb.append("<blockquote><b>").append(esc(days[d.getKey()])).append("</b>");
                d.getValue().sort(Comparator.comparing(a -> a[0]));
                for (String[] s : d.getValue()) sb.append("\n⏰ <b>").append(s[0]).append("</b> ").append(esc(s[1]))
                        .append(s[2] != null ? " · <i>" + esc(s[2]) + "</i>" : "");
                sb.append("</blockquote>\n");
            }
            if (slots.isEmpty()) sb.append(bot.t(uid, "sched.empty"));
            show(chatId, msgId, sb.toString(), kb(List.of(List.of(btn(T(uid, "🔙 Назад", "🔙 Orqaga", "🔙 Орқага", "🔙 Back"), "t:tst:" + sid + ":" + sem)))));
        });
    }

    // ─────────────────────────────────────────────
    //  НАПОМИНАНИЯ ПРЕПОДАВАТЕЛЮ
    //  • приём работ закрыт, а сданные работы ждут оценки;
    //  • до конца срока оценивания (из ведомости LMS) меньше суток;
    //  • пара закончилась, а посещаемость в календарном плане не отмечена.
    //  Пары за 10 минут и сводка дня приходят общим механизмом бота — расписание
    //  преподавателя строит SemesterSchedule.
    // ─────────────────────────────────────────────

    private final Map<Long, Long> lastScan = new ConcurrentHashMap<>();
    private static final long SCAN_MS = 30L * 60 * 1000;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    /** Раз в минуту из планировщика бота. */
    void tick() {
        long now = System.currentTimeMillis();
        for (Long uid : new ArrayList<>(bot.lastChatId.keySet())) {
            if (!lms.isLoggedIn(uid) || !lms.isTeacher(uid)) continue;
            Long chatId = bot.lastChatId.get(uid);
            if (chatId == null) continue;
            if (now - lastScan.getOrDefault(uid, 0L) >= SCAN_MS) {
                lastScan.put(uid, now);
                bot.executor.submit(() -> scanGrading(chatId, uid));
            }
            checkAttendanceMarks(chatId, uid, now);
        }
    }

    private void scanGrading(long chatId, long uid) {
        try {
            long now = System.currentTimeMillis();
            for (GradingItem g : gradingItems(uid)) {
                if (g.pending() <= 0 || g.col() < 0 || g.studentTs() > now) continue;
                String key = g.d().courseId() + "|" + g.column().activityId();
                InlineKeyboardMarkup open = kb(List.of(List.of(btn(T(uid, "📋 Проверить", "📋 Tekshirish", "📋 Текшириш", "📋 Grade now"),
                        "t:ga:" + g.d().courseId() + ":" + g.col()))));
                String what = "<b>" + esc(g.d().activity()) + "</b> · " + esc(g.d().stream()) + "\n" + esc(g.d().subject());
                boolean due = g.teacherTs() > now && g.teacherTs() - now <= DAY_MS;
                if (due && bot.markOnce(uid, "tdue|" + key)) {
                    bot.send(chatId, "⏰ <b>" + T(uid, "До конца срока оценивания меньше суток", "Baholash muddati tugashiga bir kundan kam qoldi",
                            "Баҳолаш муддати тугашига бир кундан кам қолди", "Less than a day left to grade") + "</b>\n\n<blockquote>" + what
                            + "\n👨‍🏫 " + esc(g.column().teacherDeadline()) + "\n⏳ " + g.pending() + "</blockquote>", open);
                } else if (now - g.studentTs() < 2 * DAY_MS && bot.markOnce(uid, "tclose|" + key)) {
                    bot.send(chatId, "📥 <b>" + T(uid, "Приём работ закрыт — есть что проверить", "Ishlar qabuli yopildi — tekshiradigan narsa bor",
                            "Ишлар қабули ёпилди — текширадиган нарса бор", "Submissions are closed — there is work to grade") + "</b>\n\n<blockquote>" + what
                            + "\n⏳ " + g.pending() + (g.column().teacherDeadline() != null ? "\n👨‍🏫 " + esc(g.column().teacherDeadline()) : "")
                            + "</blockquote>", open);
                }
            }
        } catch (Exception e) {
            System.err.println("[TeacherBot] grading scan " + uid + ": " + e.getMessage());
        }
    }

    private final Set<String> attendanceChecked = ConcurrentHashMap.newKeySet();
    private final Set<String> attendanceInFlight = ConcurrentHashMap.newKeySet();

    private void checkAttendanceMarks(long chatId, long uid, long now) {
        int sem = bot.getDefaultSemesterId(uid);
        if (sem <= 0) return;
        SemesterSchedule.Result sched = bot.semesterSchedule.get(uid, sem, false);
        if (sched == null) return;
        LocalDate today = LocalDate.now(AppConfig.LMS_ZONE);
        for (SemesterSchedule.Lesson l : sched.on(today)) {
            if (l.courseId() == null) continue;
            int minutes;
            try { minutes = ts.scheduleMeta(uid).lessonMinutes(); } catch (Exception e) { return; }
            if (minutes <= 0) return;
            long end = l.ts() + minutes * 60_000L;
            if (now < end + 10 * 60_000L || now > end + 6 * 60 * 60_000L) continue;
            // Ключ — сама пара: у потока бывает две пары в день. Отмечаем «проверено»
            // только после ответа LMS, чтобы сбой сайта не отменял напоминание.
            String key = "tatt|" + l.courseId() + "|" + l.ts();
            if (attendanceChecked.contains(uid + "|" + key) || !attendanceInFlight.add(uid + "|" + key)) continue;
            bot.executor.submit(() -> {
                try {
                    TCalendar cal = ts.calendar(uid, l.courseId());
                    attendanceChecked.add(uid + "|" + key);
                    for (TLesson x : cal.lessons()) {
                        if (!today.equals(SemesterScheduleDates.parse(x.date())) || x.marked()) continue;
                        bot.send(chatId, "📊 <b>" + T(uid, "Посещаемость не отмечена", "Davomat belgilanmagan", "Давомат белгиланмаган", "Attendance is not marked")
                                        + "</b>\n\n<blockquote><b>" + esc(l.subject()) + "</b> · " + esc(cal.stream()) + "\n⏰ " + esc(l.time())
                                        + "\n📝 " + esc(cut(x.topic(), 80)) + "</blockquote>" + (cal.note() != null ? "\n<i>" + esc(cal.note()) + "</i>" : ""),
                                kb(List.of(List.of(btn(T(uid, "📆 Календарный план", "📆 Kalendar reja", "📆 Календар режа", "📆 Class plan"), "t:cal:" + l.courseId())))));
                        return;
                    }
                } catch (Exception e) {
                    System.err.println("[TeacherBot] attendance check " + uid + ": " + e.getMessage());
                } finally {
                    attendanceInFlight.remove(uid + "|" + key);
                }
            });
        }
    }

    /** Разбор дат LMS «17-09-2026». */
    static final class SemesterScheduleDates {
        static LocalDate parse(String s) {
            if (s == null) return null;
            java.util.regex.Matcher m = DMY.matcher(s);
            if (!m.find()) return null;
            try {
                return LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
            } catch (Exception e) {
                return null;
            }
        }
    }
}
