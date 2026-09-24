package uz.tuit.lmsbot.bot;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import uz.tuit.lmsbot.config.AppConfig;
import uz.tuit.lmsbot.model.*;
import uz.tuit.lmsbot.service.CredentialStore;
import uz.tuit.lmsbot.service.LmsService;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public class LmsBot extends TelegramLongPollingBot {

    // ─────────────────────────────────────────────
    //  ТЕСТ-РЕЖИМ: все интервалы = 1 минута
    //  Чтобы вернуть назад — поменяй на false
    // ─────────────────────────────────────────────
    private static final boolean TEST_MODE = false;

    /** Интервал напоминания списка дедлайнов: тест=1мин, прод=6ч */
    private static final long DEADLINES_LIST_INTERVAL_MS = TEST_MODE
            ? 1L * 60 * 1000
            : 6L * 60 * 60 * 1000;

    /** Интервал напоминания срочного дедлайна по умолчанию (минуты): тест=1, прод=180 */
    private static final int URGENT_DEADLINE_DEFAULT_INTERVAL_MIN = TEST_MODE ? 1 : 240;

    /** Окно проверки напоминания о паре (±минут от старта): тест=широкое, прод=узкое */
    private static final int PAIR_REMINDER_WINDOW_MIN = TEST_MODE ? 2 : 1;

    /** За сколько минут до пары напоминать */
    private static final int PAIR_REMINDER_BEFORE_MIN = 10;

    // ─────────────────────────────────────────────

    private final AppConfig config;
    final LmsService lmsService;
    final uz.tuit.lmsbot.service.SemesterSchedule semesterSchedule;
    /** Кабинет преподавателя и тьютора: меню, кнопки «t:…», диалоги T_*, напоминания. */
    private final TeacherBot teacherBot;
    private final uz.tuit.lmsbot.service.StudentRegistry students;
    private final uz.tuit.lmsbot.service.StateBackup stateBackup;
    /** Имя и @username из последнего апдейта — для списка студентов. */
    private final Map<Long, String[]> tgIdentity = new ConcurrentHashMap<>();

    final Map<Long, String>       userState    = new ConcurrentHashMap<>();
    private final Map<Long, String>       tempLogin    = new ConcurrentHashMap<>();
    private final Map<Long, String>       tempOneIdLogin = new ConcurrentHashMap<>();
    private final Map<Long, String>       userLogin    = new ConcurrentHashMap<>();
    /**
     * Логин и пароль только что вошедшего — {способ, логин, пароль} — пока он не ответил,
     * сохранять ли их для автовхода. На диск попадают лишь после согласия.
     */
    private final Map<Long, String[]>     pendingCreds = new ConcurrentHashMap<>();
    private final Map<Long, String>       tmpOldPass   = new ConcurrentHashMap<>();
    private final Map<Long, String>       tmpNewPass   = new ConcurrentHashMap<>();
    private final Map<Long, List<Course>> userCourses  = new ConcurrentHashMap<>();
    private final Map<Long, Integer>      userSemester = new ConcurrentHashMap<>();
    private final Map<Long, String>       userLang     = new ConcurrentHashMap<>();
    private final Map<Long, Map<Integer, Map<String, List<CalendarEntry>>>> userCalendars = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, CalendarEntry.FileAttachment>>      pendingFiles  = new ConcurrentHashMap<>();
    private final Map<Long, Map<Integer, List<Activity>>> userActivities = new ConcurrentHashMap<>();
    final Map<Long, Long>                         lastChatId     = new ConcurrentHashMap<>();

    /** За сколько минут до первой пары присылать сводку на день. */
    private static final int DAY_DIGEST_BEFORE_MIN = 60;

    /** Как часто проверять новые НБ. */
    private final Map<Long, Long> lastNbCheckTs = new ConcurrentHashMap<>();
    private static final long NB_CHECK_MS = 15L * 60 * 1000;

    /** Срочный дедлайн — осталось не больше 2 дней. */
    private static final long URGENT_DEADLINE_WINDOW_MS = 2L * 24 * 60 * 60 * 1000;
    /** Как часто обходить предметы в поисках срочных дедлайнов. */
    private static final long URGENT_SCAN_MS = 15L * 60 * 1000;
    private final Map<Long, Long> lastUrgentScanTs = new ConcurrentHashMap<>();

    private static class PendingUpload {
        int courseId;
        String activityId;
        int activityIndex;
    }

    final ExecutorService executor = Executors.newFixedThreadPool(20);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<Long, PendingUpload> pendingUpload = new ConcurrentHashMap<>();

    private final Map<Long, UserSettings> userSettings = new ConcurrentHashMap<>();
    private final UserSettingsStore settingsStore = new UserSettingsStore();

    private final Map<Long, Set<String>> sentReminders = new ConcurrentHashMap<>();
    private final Map<Long, Map<Integer, Integer>> lastNbByCourse = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, Integer>> deadlineIntervalsMin = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, Long>> lastDeadlineNotifyTs = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastDeadlinesListNotifyTs = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, DeadlineMeta>> deadlineMeta = new ConcurrentHashMap<>();
    private final Map<Long, List<DeadlineItem>> lastDeadlineItems = new ConcurrentHashMap<>();

    private static class DeadlineItem {
        String course;
        int courseId;
        Activity act;
        long dl;
    }

    private static class DeadlineMeta {
        String course;
        String task;
        String deadline;
        String status;
    }

    public LmsBot(AppConfig config, LmsService lmsService) {
        this.config = config;
        this.lmsService = lmsService;
        this.semesterSchedule = new uz.tuit.lmsbot.service.SemesterSchedule(lmsService);
        this.students = new uz.tuit.lmsbot.service.StudentRegistry(lmsService);
        this.stateBackup = new uz.tuit.lmsbot.service.StateBackup(config.getBot().getToken());
        this.teacherBot = new TeacherBot(this, lmsService);
        startSchedulers();
    }

    @Override public String getBotToken()    { return config.getBot().getToken(); }
    @Override public String getBotUsername() { return config.getBot().getUsername(); }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasCallbackQuery()) {
            handleCallback(update);
            return;
        }
        if (!update.hasMessage()) return;
        Message msg  = update.getMessage();
        long chatId  = msg.getChatId();
        long userId  = msg.getFrom().getId();
        lastChatId.put(userId, chatId);
        noteUser(msg.getFrom());

        String state = userState.getOrDefault(userId, "IDLE");
        if ("WAIT_UPLOAD".equals(state) && (msg.hasDocument() || msg.hasPhoto())) {
            userState.put(userId, "IDLE");
            handleActivityFileUpload(chatId, userId, msg);
            return;
        }
        if ("WAIT_UPLOAD".equals(state) && msg.hasText()) {
            String txt = msg.getText().trim();
            if ("/cancel".equals(txt) || "bekor".equalsIgnoreCase(txt)) {
                pendingUpload.remove(userId);
                userState.put(userId, "IDLE");
                send(chatId, tr(userId, "❌ Загрузка отменена.", "❌ Yuklash bekor qilindi.", "❌ Юклаш бекор қилинди.", "❌ Upload cancelled."), null);
            } else {
                send(chatId, tr(userId,
                        "⚠️ Пожалуйста, отправьте файл (doc, pdf, jpg, zip...) или /cancel.",
                        "⚠️ Iltimos, fayl yuboring (doc, pdf, jpg, zip...) yoki /cancel yozing.",
                        "⚠️ Илтимос, файл юборинг (doc, pdf, jpg, zip...) ёки /cancel ёзинг.",
                        "⚠️ Please send a file (doc, pdf, jpg, zip...) or /cancel."), null);
            }
            return;
        }

        if (state.startsWith("T_") && (msg.hasDocument() || msg.hasPhoto())) {
            teacherBot.onFile(chatId, userId, msg, state);
            return;
        }

        if (!msg.hasText()) return;
        String text = msg.getText().trim();

        if (handleGlobalCommands(chatId, userId, text, msg.getFrom().getFirstName())) return;

        // Диалоги преподавателя (оценка, новая активность, заявление, материал).
        // Кнопка меню сбрасывает состояние в handleGlobalCommands — читаем его заново.
        state = userState.getOrDefault(userId, "IDLE");
        if (state.startsWith("T_")) {
            teacherBot.onText(chatId, userId, text, state);
            return;
        }

        if ("WAIT_OLD_PASSWORD".equals(state)) {
            tmpOldPass.put(userId, text);
            userState.put(userId, "WAIT_NEW_PASSWORD");
            send(chatId, tr(userId,
                    "🔒 <b>Новый пароль</b>:",
                    "🔒 <b>Yangi parol</b>:",
                    "🔒 <b>Янги парол</b>:",
                    "🔒 <b>New password</b>:"), null);
            return;
        }
        if ("WAIT_NEW_PASSWORD".equals(state)) {
            tmpNewPass.put(userId, text);
            userState.put(userId, "WAIT_NEW_PASSWORD_CONFIRM");
            send(chatId, tr(userId,
                    "🔒 <b>Подтвердите новый пароль</b>:",
                    "🔒 <b>Yangi parolni tasdiqlang</b>:",
                    "🔒 <b>Янги паролни тасдиқланг</b>:",
                    "🔒 <b>Confirm the new password</b>:"), null);
            return;
        }
        if ("WAIT_NEW_PASSWORD_CONFIRM".equals(state)) {
            String oldP = tmpOldPass.remove(userId);
            String newP = tmpNewPass.remove(userId);
            String conf = text;
            userState.put(userId, "IDLE");
            String newPNorm = (newP == null) ? "" : newP;
            if (!newPNorm.equals(conf)) {
                send(chatId, tr(userId,
                        "❌ <b>Ошибка</b>\n\n<blockquote>Пароли не совпадают.</blockquote>",
                        "❌ <b>Xatolik</b>\n\n<blockquote>Parollar mos emas.</blockquote>",
                        "❌ <b>Хатолик</b>\n\n<blockquote>Пароллар мос эмас.</blockquote>",
                        "❌ <b>Error</b>\n\n<blockquote>Passwords do not match.</blockquote>"), null);
                return;
            }
            sendProgress(chatId, tr(userId,
                    "⏳ <b>Меняю пароль...</b>",
                    "⏳ <b>Parol o'zgartirilmoqda...</b>",
                    "⏳ <b>Парол ўзгартирилмоқда...</b>",
                    "⏳ <b>Changing password...</b>"));
            final String oldFinal = oldP;
            final String newFinal = newPNorm;
            executor.submit(() -> {
                boolean ok = lmsService.changePassword(userId, oldFinal, newFinal, conf);
                if (ok) {
                    send(chatId, tr(userId,
                            "✅ <b>Пароль изменён</b>",
                            "✅ <b>Parol o'zgartirildi</b>",
                            "✅ <b>Парол ўзгартирилди</b>",
                            "✅ <b>Password changed</b>"), null);
                } else {
                    send(chatId, tr(userId,
                            "❌ <b>Не удалось изменить пароль</b>\n\n<blockquote>Проверьте старый пароль и требования к новому.</blockquote>",
                            "❌ <b>Parol o'zgartirib bo'lmadi</b>\n\n<blockquote>Eski parol va yangi parol talablarini tekshiring.</blockquote>",
                            "❌ <b>Парол ўзгартириб бўлмади</b>\n\n<blockquote>Эски парол ва янги парол талабларини текширинг.</blockquote>",
                            "❌ <b>Could not change the password</b>\n\n<blockquote>Check your old password and the new password requirements.</blockquote>"), null);
                }
            });
            return;
        }

        if ("WAIT_ONEID_LOGIN".equals(state)) {
            tempOneIdLogin.put(userId, text);
            userState.put(userId, "WAIT_ONEID_PASSWORD");
            send(chatId, tr(userId,
                    "🔐 Теперь введите <b>пароль OneID</b>:",
                    "🔐 Endi <b>OneID parolini</b> kiriting:",
                    "🔐 Энди <b>OneID паролини</b> киритинг:",
                    "🔐 Now enter your <b>OneID password</b>:"), null);
            return;
        }
        if ("WAIT_ONEID_PASSWORD".equals(state)) {
            userState.put(userId, "IDLE");
            handleOneIdLogin(chatId, userId, tempOneIdLogin.get(userId), text);
            return;
        }
        if ("WAIT_ONEID_SMS".equals(state)) {
            userState.put(userId, "IDLE");
            handleOneIdSms(chatId, userId, text);
            return;
        }
        if ("WAIT_ONEID_PHONE".equals(state)) {
            String phone = LmsService.normalizeUzPhone(text);
            if (phone == null) {
                send(chatId, tr(userId,
                        "⚠️ Неверный номер. Введите в формате <code>+998901234567</code>:",
                        "⚠️ Noto'g'ri raqam. <code>+998901234567</code> formatida kiriting:",
                        "⚠️ Нотўғри рақам. <code>+998901234567</code> форматида киритинг:",
                        "⚠️ Invalid number. Enter it as <code>+998901234567</code>:"), null);
                return;
            }
            userState.put(userId, "IDLE");
            handleOneIdPhone(chatId, userId, phone);
            return;
        }
        if ("WAIT_ONEID_MOBILE_SMS".equals(state)) {
            userState.put(userId, "IDLE");
            handleOneIdMobileSms(chatId, userId, text);
            return;
        }

        if ("WAIT_LOGIN".equals(state)) {
            tempLogin.put(userId, text);
            userState.put(userId, "WAIT_PASSWORD");
            send(chatId, tr(userId,
                    "🔐 Теперь введите ваш пароль:",
                    "🔐 Endi parolingizni kiriting:",
                    "🔐 Энди паролингизни киритинг:",
                    "🔐 Now enter your password:"), null);
            return;
        }
        if ("WAIT_PASSWORD".equals(state)) {
            userState.put(userId, "IDLE");
            handleLogin(chatId, userId, tempLogin.remove(userId), text);
            return;
        }

        if (lmsService.isTeacher(userId) && teacherBot.onMenu(chatId, userId, text)) return;

        switch (text) {
            case "/start":
                if (!hasLang(userId)) sendLanguageChoice(chatId);
                else sendWelcome(chatId, userId, msg.getFrom().getFirstName());
                break;
            case "/login":
            case "🔑 Kirish":
            case "🔑 Войти":
            case "🔑 Sign in":
            case "🔑 Кириш":             askForLogin(chatId, userId); break;
            case "⚡ Автовход":
            case "⚡ Avtokirish":
            case "⚡ Автокириш":
            case "⚡ Auto sign-in":       handleAutoLogin(chatId, userId); break;
            case "🗂 Мои аккаунты":
            case "🗂 Hisoblarim":
            case "🗂 Ҳисобларим":
            case "🗂 My accounts":        showCreds(chatId, userId); break;
            case "/logout":
            case "🚪 Chiqish":
            case "🚪 Выйти":
            case "🚪 Sign out":
            case "🚪 Чиқиш":              handleLogout(chatId, userId); break;
            case "/courses":
            case "📚 Mening fanlarim":
            case "📚 Мои предметы":
            case "📚 My subjects":
            case "📚 Менинг фанларим":    showCourses(chatId, userId, getDefaultSemesterId(userId)); break;
            case "📅 Dars jadvali":
            case "📅 Расписание":
            case "📅 Timetable":
            case "📅 Дарс жадвали":       showSchedule(chatId, userId, getDefaultSemesterId(userId)); break;
            case "📖 O'quv reja":
            case "📖 Учебный план":
            case "📖 Study plan":
            case "📖 Ўқув режа":          showStudyPlanFull(chatId, userId); break;
            case "🏆 Yakuniy imtihon":
            case "🏆 Итоговый экзамен":
            case "🏆 Final exams":
            case "🏆 Якуний имтиҳон":     showFinals(chatId, userId, getDefaultSemesterId(userId)); break;
            case "👤 Profil":
            case "👤 Профиль":
            case "👤 Profile":
            case "👤 Профил":             showProfile(chatId, userId); break;
            case "⚙️ Sozlamalar":
            case "⚙️ Настройки":
            case "⚙️ Settings":
            case "⚙️ Созламалар":         showSettings(chatId, userId); break;
            default:
                if (lmsService.isLoggedIn(userId)) sendMainMenu(chatId, userId);
                else sendWelcome(chatId, userId, msg.getFrom().getFirstName());
        }
    }

    // ─────────────────────────────────────────────
    //  CALLBACK HANDLER
    // ─────────────────────────────────────────────

    private void handleCallback(Update update) {
        long chatId    = update.getCallbackQuery().getMessage().getChatId();
        long userId    = update.getCallbackQuery().getFrom().getId();
        noteUser(update.getCallbackQuery().getFrom());
        int  messageId = update.getCallbackQuery().getMessage().getMessageId();
        String data    = update.getCallbackQuery().getData();

        answerCallback(update.getCallbackQuery().getId());

        if (data.startsWith("t:") || data.startsWith("tc_") || data.startsWith("tf_")) {
            teacherBot.onCallback(chatId, userId, messageId, data);
            return;
        }
        if (data.startsWith("lang_")) {
            String lang = data.substring("lang_".length());
            setUserLang(userId, lang);
            String firstName = update.getCallbackQuery().getFrom().getFirstName();
            sendWelcome(chatId, userId, firstName != null ? firstName : "");
        } else if (data.startsWith("semester_")) {
            showCourses(chatId, userId, Integer.parseInt(data.replace("semester_", "")));
        } else if (data.startsWith("file_")) {
            handleFileDownload(chatId, userId, data.substring("file_".length()));
        } else if (data.startsWith("calfiles_")) {
            String[] parts = data.split("_", 4);
            if (parts.length == 4)
                showCalendarFiles(chatId, userId, messageId, Integer.parseInt(parts[1]), parts[2], Integer.parseInt(parts[3]));
        } else if (data.startsWith("calpage_")) {
            String[] parts = data.split("_", 4);
            if (parts.length == 4)
                editCalendarPage(chatId, userId, messageId, Integer.parseInt(parts[1]), parts[2], Integer.parseInt(parts[3]));
        } else if (data.startsWith("calentry_")) {
            String[] parts = data.split("_", 4);
            if (parts.length == 4)
                editCalendarEntry(chatId, userId, messageId, Integer.parseInt(parts[1]), parts[2], Integer.parseInt(parts[3]));
        } else if (data.startsWith("calback_")) {
            String[] parts = data.split("_", 4);
            if (parts.length == 4) {
                int page = Math.max(0, Integer.parseInt(parts[3]) / 5);
                editCalendarPage(chatId, userId, messageId, Integer.parseInt(parts[1]), parts[2], page);
            }
        } else if ("select_attend".equals(data)) {
            editCourseSelection(chatId, userId, messageId, "attend");
        } else if ("select_activities".equals(data)) {
            editCourseSelection(chatId, userId, messageId, "activities");
        } else if ("back_to_courses".equals(data)) {
            int semId = userSemester.getOrDefault(userId, getDefaultSemesterId(userId));
            showCourses(chatId, userId, semId);
        } else if ("change_semester".equals(data)) {
            edit(chatId, messageId, tr(userId, "📅 Выберите семестр:", "📅 Semestrni tanlang:", "📅 Семестрни танланг:", "📅 Choose a semester:"), semesterKeyboard(userId));
        } else if (data.startsWith("ai_")) {
            String[] parts = data.replace("ai_", "").split("_");
            int index = Integer.parseInt(parts[0]);
            int semesterId = Integer.parseInt(parts[1]);
            List<Course> courses = userCourses.get(userId);
            if (courses != null && index < courses.size())
                showAttendance(chatId, userId, messageId, courses.get(index).getId(), semesterId);
        } else if (data.startsWith("ac_back_")) {
            int courseId = Integer.parseInt(data.replace("ac_back_", ""));
            showActivities(chatId, userId, courseId);
        } else if (data.startsWith("ac_")) {
            int index = Integer.parseInt(data.replace("ac_", ""));
            List<Course> courses = userCourses.get(userId);
            if (courses != null && index < courses.size())
                showActivities(chatId, userId, courses.get(index).getId());
        } else if (data.startsWith("act_sample_")) {
            String[] p = data.replace("act_sample_", "").split("_", 2);
            if (p.length == 2) downloadAndSendActivityFile(chatId, userId, Integer.parseInt(p[0]), Integer.parseInt(p[1]), "sample");
        } else if (data.startsWith("act_uploaded_")) {
            String[] p = data.replace("act_uploaded_", "").split("_", 2);
            if (p.length == 2) downloadAndSendActivityFile(chatId, userId, Integer.parseInt(p[0]), Integer.parseInt(p[1]), "uploaded");
        } else if (data.startsWith("act_upload_")) {
            String[] p = data.replace("act_upload_", "").split("_", 3);
            if (p.length == 3) promptActivityUpload(chatId, userId, Integer.parseInt(p[0]), Integer.parseInt(p[1]), p[2]);
        } else if (data.startsWith("act_deadline_list_")) {
            int courseId = Integer.parseInt(data.replace("act_deadline_list_", ""));
            editDeadlineList(chatId, userId, messageId, courseId);
        } else if (data.startsWith("act_filter_all_")) {
            int courseId = Integer.parseInt(data.replace("act_filter_all_", ""));
            editActivitiesList(chatId, userId, messageId, courseId, "all");
        } else if (data.startsWith("act_filter_uploaded_")) {
            int courseId = Integer.parseInt(data.replace("act_filter_uploaded_", ""));
            editActivitiesList(chatId, userId, messageId, courseId, "uploaded");
        } else if (data.startsWith("act_filter_uploadable_")) {
            int courseId = Integer.parseInt(data.replace("act_filter_uploadable_", ""));
            editActivitiesList(chatId, userId, messageId, courseId, "uploadable");
        } else if (data.startsWith("act_pick_")) {
            String[] p = data.replace("act_pick_", "").split("_", 2);
            if (p.length == 2) editActivityDetails(chatId, userId, messageId, Integer.parseInt(p[0]), Integer.parseInt(p[1]));
        } else if (data.startsWith("sw_")) {
            String[] p = data.substring(3).split("_");
            if (p.length == 2) editScheduleWeek(chatId, userId, messageId, Integer.parseInt(p[0]), Integer.parseInt(p[1]));
        } else if ("sched_sem".equals(data)) {
            edit(chatId, messageId, tr(userId, "📅 Выберите семестр:", "📅 Semestrni tanlang:", "📅 Семестрни танланг:", "📅 Choose a semester:"), semesterScheduleKeyboard(userId));
        } else if (data.startsWith("schedule_")) {
            showSchedule(chatId, userId, Integer.parseInt(data.replace("schedule_", "")));
        } else if ("gpa_all".equals(data)) {
            showStudyPlanGpa(chatId, userId, messageId, false);
        } else if ("gpa_with_zero".equals(data)) {
            showStudyPlanGpa(chatId, userId, messageId, true);
        } else if ("back_to_study_plan".equals(data)) {
            showStudyPlanFull(chatId, userId);
        } else if (data.startsWith("finals_")) {
            showFinals(chatId, userId, Integer.parseInt(data.replace("finals_", "")));
        } else if ("select_calendar".equals(data)) {
            editCourseSelection(chatId, userId, messageId, "calendar");
        } else if (data.startsWith("cal_")) {
            int index = Integer.parseInt(data.replace("cal_", ""));
            List<Course> courses = userCourses.get(userId);
            if (courses != null && index < courses.size())
                showCalendar(chatId, userId, messageId, courses.get(index));
        } else if (data.startsWith("caltype_")) {
            String[] parts = data.replace("caltype_", "").split("_", 2);
            editCalendarPage(chatId, userId, messageId, Integer.parseInt(parts[0]), parts[1], 0);
        } else if ("deadlines_list".equals(data)) {
            showDeadlinesList(chatId, userId);
        } else if (data.startsWith("deadlines_all_")) {
            int page = Integer.parseInt(data.replace("deadlines_all_", ""));
            editAllDeadlinesPage(chatId, userId, messageId, page);
        } else if ("deadlines_back".equals(data)) {
            showDeadlinesList(chatId, userId);
        } else if (data.startsWith("semp_")) {
            String rest = data.substring("semp_".length());
            int cut = rest.lastIndexOf('_');
            if (cut > 0) {
                String prefix = rest.substring(0, cut) + "_";
                int page = Integer.parseInt(rest.substring(cut + 1));
                editMarkup(chatId, messageId, buildSemesterKeyboard(userId, prefix, page));
            }
        } else if ("noop".equals(data)) {
            // только индикатор страницы
        } else if ("back_main".equals(data)) {
            send(chatId, tr(userId, "🏠 <b>Главное меню</b>", "🏠 <b>Asosiy menyu</b>", "🏠 <b>Асосий меню</b>", "🏠 <b>Main menu</b>"),
                    mainMenuKeyboard(userId));
        } else if ("creds_add".equals(data)) {
            askForLogin(chatId, userId);
        } else if (data.startsWith("credsel_")) {
            lmsService.credentials().select(userId, data.substring("credsel_".length()));
            showCreds(chatId, userId);
        } else if (data.startsWith("creddel_")) {
            removeCreds(chatId, userId, data.substring("creddel_".length()));
        } else if ("creds_yes".equals(data)) {
            saveCredsAnswer(chatId, userId, true);
        } else if ("creds_no".equals(data)) {
            saveCredsAnswer(chatId, userId, false);
        } else if ("auth_lms".equals(data)) {
            askForLmsLogin(chatId, userId);
        } else if ("auth_oneid".equals(data)) {
            askForOneIdMethod(chatId, userId);
        } else if ("oneid_pw".equals(data)) {
            askForOneIdLogin(chatId, userId);
        } else if ("oneid_mobile".equals(data)) {
            askForOneIdPhone(chatId, userId);
        } else if ("oneid_qr".equals(data)) {
            startOneIdQr(chatId, userId);
        } else if ("oneid_qr_x".equals(data)) {
            cancelOneIdQr(chatId, userId, messageId);
        } else if ("settings".equals(data)) {
            showSettings(chatId, userId);
        } else if ("settings_lang".equals(data)) {
            edit(chatId, messageId, tr(userId, "🌐 <b>Выберите язык</b>:", "🌐 <b>Tilni tanlang</b>:", "🌐 <b>Тилни танланг</b>:", "🌐 <b>Choose a language</b>:"), langChoiceMarkup());
        } else if ("settings_password".equals(data)) {
            startChangePassword(chatId, userId);
        } else if ("settings_logout".equals(data)) {
            handleLogout(chatId, userId);
        } else if (data.startsWith("setlang_")) {
            String lang = data.substring("setlang_".length());
            setUserLang(userId, lang);
            // Reply-клавиатуру Telegram не перерисовывает сам: пока не отправить
            // новую разметку, нижние кнопки остаются на прежнем языке.
            send(chatId, tr(userId,
                    "✅ <b>Язык изменён</b>",
                    "✅ <b>Til o'zgartirildi</b>",
                    "✅ <b>Тил ўзгартирилди</b>",
                    "✅ <b>Language changed</b>"),
                    lmsService.isLoggedIn(userId) ? mainMenuKeyboard(userId) : loginKeyboard(userId));
            showSettings(chatId, userId);
        } else if (data.startsWith("dl_remind_")) {
            showDeadlineIntervalPicker(chatId, userId, messageId, data.substring("dl_remind_".length()));
        } else if (data.startsWith("dl_set_")) {
            String rest = data.substring("dl_set_".length());
            int lastUnd = rest.lastIndexOf('_');
            if (lastUnd > 0) {
                String key = rest.substring(0, lastUnd);
                int minutes = Integer.parseInt(rest.substring(lastUnd + 1));
                setDeadlineInterval(userId, key, minutes);
                send(chatId, tr(userId,
                        "✅ <b>Интервал напоминания изменён</b>",
                        "✅ <b>Eslatma oralig'i o'zgartirildi</b>",
                        "✅ <b>Эслатма оралиғи ўзгартирилди</b>",
                        "✅ <b>Reminder interval changed</b>")
                        + "\n\n<blockquote>"
                        + tr(userId,
                        "Теперь: <b>" + minutesToLabelRu(minutes) + "</b>",
                        "Endi: <b>" + minutesToLabelUzLat(minutes) + "</b>",
                        "Энди: <b>" + minutesToLabelUzCyr(minutes) + "</b>",
                        "Now: <b>" + minutesToLabelEn(minutes) + "</b>")
                        + "</blockquote>", null);
            }
        } else if ("profile_photo".equals(data)) {
            showProfilePhoto(chatId, userId);
        }
    }

    private void answerCallback(String callbackId) {
        try {
            org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery ans =
                    new org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery();
            ans.setCallbackQueryId(callbackId);
            execute(ans);
        } catch (Exception ignored) {}
    }

    // ─────────────────────────────────────────────
    //  EDIT MESSAGE HELPERS
    // ─────────────────────────────────────────────

    /** Меняет только клавиатуру сообщения — для листания страниц семестров. */
    private void editMarkup(long chatId, int messageId, InlineKeyboardMarkup markup) {
        dropProgress(chatId);
        try {
            org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup e =
                    new org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup();
            e.setChatId(String.valueOf(chatId));
            e.setMessageId(messageId);
            e.setReplyMarkup(markup);
            execute(e);
        } catch (Exception ex) {
            if (ex.getMessage() == null || !ex.getMessage().contains("message is not modified")) {
                System.err.println("[LmsBot] EditMarkup error: " + ex.getMessage());
            }
        }
    }

    void edit(long chatId, int messageId, String text, InlineKeyboardMarkup markup) {
        dropProgress(chatId);
        try {
            EditMessageText edit = new EditMessageText();
            edit.setChatId(String.valueOf(chatId));
            edit.setMessageId(messageId);
            edit.setText(text);
            edit.setParseMode("HTML");
            if (markup != null) edit.setReplyMarkup(markup);
            execute(edit);
        } catch (Exception e) {
            if (e.getMessage() == null || !e.getMessage().contains("message is not modified")) {
                System.err.println("[LmsBot] Edit error: " + e.getMessage());
            }
        }
    }

    // ─────────────────────────────────────────────
    //  WELCOME / LOGIN
    // ─────────────────────────────────────────────

    /** Автор бота — подписывается в приветствии и описании. */
    private static final String AUTHOR = "@umarkhn_1";

    private void sendWelcome(long chatId, long userId, String firstName) {
        boolean in = lmsService.isLoggedIn(userId);
        String name = esc(firstName == null ? "" : firstName.trim());
        String text = tr(userId,
                "👋 <b>Привет" + (name.isEmpty() ? "" : ", " + name) + "!</b>\n\n"
                        + "Я — <b>TUIT LMS Bot</b>, помощник студента ТУИТ для lms.tuit.uz.\n\n"
                        + "<b>Что я умею:</b>\n"
                        + "📚 предметы, задания и баллы\n"
                        + "📅 расписание на весь семестр\n"
                        + "⏰ напоминания о парах и дедлайнах\n"
                        + "📊 посещаемость, учебный план и GPA\n"
                        + "📤 загрузка работ прямо из Telegram\n"
                        + "🎓 интерактивное приложение — кнопка «LMS» слева от поля ввода\n\n"
                        + (in ? "Вы уже вошли — выбирайте раздел в меню ниже 👇" : "Чтобы начать, войдите в аккаунт 👇")
                        + "\n\n<i>Автор: " + AUTHOR + "</i>",
                "👋 <b>Assalomu alaykum" + (name.isEmpty() ? "" : ", " + name) + "!</b>\n\n"
                        + "Men — <b>TUIT LMS Bot</b>, lms.tuit.uz uchun TATU talabasining yordamchisiman.\n\n"
                        + "<b>Nimalar qila olaman:</b>\n"
                        + "📚 fanlar, topshiriqlar va ballar\n"
                        + "📅 butun semestr uchun dars jadvali\n"
                        + "⏰ darslar va deadlinelar haqida eslatmalar\n"
                        + "📊 davomat, o'quv reja va GPA\n"
                        + "📤 ishlarni to'g'ridan-to'g'ri Telegramdan yuklash\n"
                        + "🎓 interaktiv ilova — yozish maydoni chapidagi «LMS» tugmasi\n\n"
                        + (in ? "Siz tizimdasiz — quyidagi menyudan bo'limni tanlang 👇" : "Boshlash uchun hisobingizga kiring 👇")
                        + "\n\n<i>Muallif: " + AUTHOR + "</i>",
                "👋 <b>Ассалому алайкум" + (name.isEmpty() ? "" : ", " + name) + "!</b>\n\n"
                        + "Мен — <b>TUIT LMS Bot</b>, lms.tuit.uz учун ТАТУ талабасининг ёрдамчисиман.\n\n"
                        + "<b>Нималар қила оламан:</b>\n"
                        + "📚 фанлар, топшириқлар ва баллар\n"
                        + "📅 бутун семестр учун дарс жадвали\n"
                        + "⏰ дарслар ва дедлайнлар ҳақида эслатмалар\n"
                        + "📊 давомат, ўқув режа ва GPA\n"
                        + "📤 ишларни тўғридан-тўғри Telegramдан юклаш\n"
                        + "🎓 интерактив илова — ёзиш майдони чапидаги «LMS» тугмаси\n\n"
                        + (in ? "Сиз тизимдасиз — қуйидаги менюдан бўлимни танланг 👇" : "Бошлаш учун ҳисобингизга киринг 👇")
                        + "\n\n<i>Муаллиф: " + AUTHOR + "</i>",
                "👋 <b>Hi" + (name.isEmpty() ? "" : ", " + name) + "!</b>\n\n"
                        + "I'm <b>TUIT LMS Bot</b>, a student assistant for TUIT's lms.tuit.uz.\n\n"
                        + "<b>What I can do:</b>\n"
                        + "📚 subjects, assignments and scores\n"
                        + "📅 the timetable for the whole semester\n"
                        + "⏰ class and deadline reminders\n"
                        + "📊 attendance, study plan and GPA\n"
                        + "📤 upload your work right from Telegram\n"
                        + "🎓 an interactive app — the «LMS» button left of the message field\n\n"
                        + (in ? "You're already signed in — pick a section in the menu below 👇" : "To get started, sign in to your account 👇")
                        + "\n\n<i>Author:" + AUTHOR + "</i>");
        if (in && lmsService.isTeacher(userId)) text = teacherBot.welcome(userId, name);
        send(chatId, text, in ? mainMenuKeyboard(userId) : loginKeyboard(userId));
        if (in) sendAppAfterLogin(chatId, userId);
    }

    /**
     * Описание в профиле бота и на пустом экране чата («Что умеет этот бот?»).
     * Отдельного кода для узбекской кириллицы у Telegram нет — ей достаётся описание по умолчанию.
     */
    private void publishDescriptions() {
        String[][] texts = {
                // { language_code, description, short description }
                {null,
                        "🎓 TUIT LMS Bot — TATU talabalari uchun yordamchi (lms.tuit.uz).\n\n"
                                + "📚 Fanlar, topshiriqlar va ballar\n"
                                + "📅 Butun semestr jadvali\n"
                                + "⏰ Dars va deadline eslatmalari\n"
                                + "📊 Davomat, o'quv reja, GPA\n"
                                + "📤 Ishlarni LMS ga yuklash\n"
                                + "📱 Interaktiv ilova\n\n"
                                + "Kirish — LMS login yoki OneID orqali.\n\n"
                                + "Muallif: " + AUTHOR,
                        "TATU talabasi yordamchisi: jadval, deadlinelar, baholar va GPA. Muallif: " + AUTHOR},
                {"ru",
                        "🎓 TUIT LMS Bot — помощник студента ТУИТ (lms.tuit.uz).\n\n"
                                + "📚 Предметы, задания и баллы\n"
                                + "📅 Расписание на весь семестр\n"
                                + "⏰ Напоминания о парах и дедлайнах\n"
                                + "📊 Посещаемость, учебный план, GPA\n"
                                + "📤 Загрузка работ в LMS\n"
                                + "📱 Интерактивное приложение\n\n"
                                + "Вход — по логину LMS или через OneID.\n\n"
                                + "Автор: " + AUTHOR,
                        "Помощник студента ТУИТ: расписание, дедлайны, оценки и GPA. Автор: " + AUTHOR},
                {"en",
                        "🎓 TUIT LMS Bot — a student assistant for TUIT (lms.tuit.uz).\n\n"
                                + "📚 Subjects, assignments and scores\n"
                                + "📅 Whole-semester timetable\n"
                                + "⏰ Class and deadline reminders\n"
                                + "📊 Attendance, study plan, GPA\n"
                                + "📤 Upload your work to LMS\n"
                                + "📱 Interactive app\n\n"
                                + "Sign in with your LMS login or OneID.\n\n"
                                + "Author: " + AUTHOR,
                        "TUIT student assistant: timetable, deadlines, grades and GPA. Author: " + AUTHOR},
        };
        for (String[] t : texts) {
            try {
                org.telegram.telegrambots.meta.api.methods.description.SetMyDescription d =
                        new org.telegram.telegrambots.meta.api.methods.description.SetMyDescription();
                d.setDescription(t[1]);
                if (t[0] != null) d.setLanguageCode(t[0]);
                execute(d);
                org.telegram.telegrambots.meta.api.methods.description.SetMyShortDescription sd =
                        new org.telegram.telegrambots.meta.api.methods.description.SetMyShortDescription();
                sd.setShortDescription(t[2]);
                if (t[0] != null) sd.setLanguageCode(t[0]);
                execute(sd);
            } catch (Exception e) {
                System.err.println("[LmsBot] setMyDescription(" + t[0] + "): " + e.getMessage());
            }
        }
    }

    private void askForLogin(long chatId, long userId) {
        executor.submit(() -> askForLoginSync(chatId, userId));
    }

    private void askForLoginSync(long chatId, long userId) {
        // Сессия могла пережить перезапуск бота — проверяем сохранённые cookie.
        if (!lmsService.isLoggedIn(userId) && !loggingIn(userId) && lmsService.restoreSession(userId)) {
            send(chatId, tr(userId,
                            "✅ <b>Вы уже вошли в систему</b>\n\nЧтобы сменить аккаунт — /logout",
                            "✅ <b>Siz allaqachon tizimdasiz</b>\n\nHisobni almashtirish uchun — /logout",
                            "✅ <b>Сиз аллақачон тизимдасиз</b>\n\nҲисобни алмаштириш учун — /logout",
                            "✅ <b>You're already signed in</b>\n\nTo switch accounts — /logout"),
                    mainMenuKeyboard(userId));
            onBotLogin(chatId, userId);
            return;
        }
        InlineKeyboardMarkup kb = markup(List.of(
                List.of(inlineBtn(tr(userId,
                        "🆔 Через OneID", "🆔 OneID orqali", "🆔 OneID орқали", "🆔 With OneID"), "auth_oneid")),
                List.of(inlineBtn(tr(userId,
                        "🔑 Логин и пароль LMS", "🔑 LMS login va parol", "🔑 LMS логин ва парол", "🔑 LMS login and password"), "auth_lms"))
        ));
        send(chatId, tr(userId,
                "🔐 <b>Выберите способ входа</b>\n\n"
                        + "<blockquote>🆔 <b>OneID</b> — вход по данным id.egov.uz (рекомендуется)\n"
                        + "🔑 <b>LMS</b> — обычный логин и пароль от lms.tuit.uz</blockquote>",
                "🔐 <b>Kirish usulini tanlang</b>\n\n"
                        + "<blockquote>🆔 <b>OneID</b> — id.egov.uz ma'lumotlari bilan (tavsiya etiladi)\n"
                        + "🔑 <b>LMS</b> — lms.tuit.uz login va paroli</blockquote>",
                "🔐 <b>Кириш усулини танланг</b>\n\n"
                        + "<blockquote>🆔 <b>OneID</b> — id.egov.uz маълумотлари билан (тавсия этилади)\n"
                        + "🔑 <b>LMS</b> — lms.tuit.uz логин ва пароли</blockquote>",
                "🔐 <b>Choose how to sign in</b>\n\n"
                        + "<blockquote>🆔 <b>OneID</b> — sign in with your id.egov.uz account (recommended)\n"
                        + "🔑 <b>LMS</b> — your usual lms.tuit.uz login and password</blockquote>"), kb);
    }

    private void askForLmsLogin(long chatId, long userId) {
        userState.put(userId, "WAIT_LOGIN");
        send(chatId, tr(userId,
                "👤 <b>Введите ваш LMS логин</b>:\n\nНапример: <code>1bk27748</code>",
                "👤 <b>LMS loginingizni</b> kiriting:\n\nMasalan: <code>1bk27748</code>",
                "👤 <b>LMS логинингизни</b> киритинг:\n\nМасалан: <code>1bk27748</code>",
                "👤 <b>Enter your LMS login</b>:\n\nFor example: <code>1bk27748</code>"), null);
    }

    private void askForOneIdMethod(long chatId, long userId) {
        InlineKeyboardMarkup kb = markup(List.of(
                List.of(inlineBtn(tr(userId,
                        "🔑 Логин и пароль", "🔑 Login va parol", "🔑 Логин ва парол", "🔑 Login and password"), "oneid_pw")),
                List.of(inlineBtn(tr(userId,
                        "📱 Mobile ID (SMS)", "📱 Mobile ID (SMS)", "📱 Mobile ID (SMS)", "📱 Mobile ID (SMS)"), "oneid_mobile")),
                List.of(inlineBtn(tr(userId,
                        "📷 QR-код", "📷 QR-kod", "📷 QR-код", "📷 QR code"), "oneid_qr"))
        ));
        send(chatId, tr(userId,
                "🆔 <b>Вход через OneID</b>\n\nВыберите способ:",
                "🆔 <b>OneID orqali kirish</b>\n\nUsulni tanlang:",
                "🆔 <b>OneID орқали кириш</b>\n\nУсулни танланг:",
                "🆔 <b>Sign in with OneID</b>\n\nChoose a method:"), kb);
    }

    // ─────────────────────────────────────────────
    //  ONEID ПО QR-КОДУ
    //  Бот присылает QR картинкой; её сканируют приложением OneID — с другого экрана или
    //  со скриншота из галереи. Код живёт ~30 секунд, поэтому картинка в том же сообщении
    //  подменяется новой, пока человек не войдёт, не нажмёт «Отмена» или не выйдет время.
    // ─────────────────────────────────────────────

    /** Сколько ждём сканирования, прежде чем бросить попытку. */
    private static final long QR_WAIT_MS = 5L * 60 * 1000;
    /** Номер попытки входа по QR: новая попытка или отмена останавливают прежний опрос. */
    private final Map<Long, Integer> qrAttempt = new ConcurrentHashMap<>();

    private String qrCaption(long userId, long expiresAt) {
        long sec = Math.max(0, (expiresAt - System.currentTimeMillis()) / 1000);
        return tr(userId,
                "📷 <b>Вход через OneID по QR-коду</b>\n\n"
                        + "1. Откройте приложение <b>OneID</b> и выберите сканирование QR-кода.\n"
                        + "2. Наведите камеру на этот код — или сделайте скриншот и выберите его из галереи.\n"
                        + "3. Подтвердите вход в приложении — бот войдёт сам.\n\n"
                        + "<i>Код действует ~" + sec + " с и обновляется автоматически.</i>",
                "📷 <b>OneID orqali QR-kod bilan kirish</b>\n\n"
                        + "1. <b>OneID</b> ilovasini oching va QR-kodni skanerlashni tanlang.\n"
                        + "2. Kamerani shu kodga qarating — yoki skrinshot olib, uni galereyadan tanlang.\n"
                        + "3. Ilovada kirishni tasdiqlang — bot o'zi kiradi.\n\n"
                        + "<i>Kod ~" + sec + " s amal qiladi va avtomatik yangilanadi.</i>",
                "📷 <b>OneID орқали QR-код билан кириш</b>\n\n"
                        + "1. <b>OneID</b> иловасини очинг ва QR-кодни сканерлашни танланг.\n"
                        + "2. Камерани шу кодга қаратинг — ёки скриншот олиб, уни галереядан танланг.\n"
                        + "3. Иловада киришни тасдиқланг — бот ўзи киради.\n\n"
                        + "<i>Код ~" + sec + " с амал қилади ва автоматик янгиланади.</i>",
                "📷 <b>Sign in with OneID by QR code</b>\n\n"
                        + "1. Open the <b>OneID</b> app and choose QR code scanning.\n"
                        + "2. Point the camera at this code — or take a screenshot and pick it from the gallery.\n"
                        + "3. Confirm the sign-in in the app — the bot will sign you in.\n\n"
                        + "<i>The code is valid for ~" + sec + " s and refreshes automatically.</i>");
    }

    private InlineKeyboardMarkup qrCancelKeyboard(long userId) {
        return markup(List.of(List.of(inlineBtn(tr(userId, "❌ Отмена", "❌ Bekor qilish", "❌ Бекор қилиш", "❌ Cancel"), "oneid_qr_x"))));
    }

    private void startOneIdQr(long chatId, long userId) {
        int attempt = qrAttempt.merge(userId, 1, Integer::sum);
        // Пока ждём сканирования, сессию не восстанавливаем: это стёрло бы token_id входа.
        userState.put(userId, "WAIT_ONEID_QR");
        sendProgress(chatId, tr(userId, "⏳ Получаю QR-код...", "⏳ QR-kod olinmoqda...", "⏳ QR-код олинмоқда...", "⏳ Getting a QR code..."));
        Thread t = new Thread(() -> runOneIdQr(chatId, userId, attempt), "oneid-qr-" + userId);
        t.setDaemon(true);
        t.start();
    }

    private boolean qrActive(long userId, int attempt) {
        return qrAttempt.getOrDefault(userId, 0) == attempt && "WAIT_ONEID_QR".equals(userState.get(userId));
    }

    private void runOneIdQr(long chatId, long userId, int attempt) {
        Integer photoId = null;
        try {
            LmsService.OneIdQr qr = lmsService.oneIdQrStart(userId);
            photoId = sendQrPhoto(chatId, userId, qr, null);
            long deadline = System.currentTimeMillis() + QR_WAIT_MS;
            while (qrActive(userId, attempt) && System.currentTimeMillis() < deadline) {
                Thread.sleep(3000);
                if (!qrActive(userId, attempt)) break;
                LmsService.QrStatus st = lmsService.oneIdQrCheck(userId);
                if (st == LmsService.QrStatus.OK) {
                    userState.put(userId, "IDLE");
                    deleteMessage(chatId, photoId);
                    finishOneId(chatId, userId, null);
                    return;
                }
                if (st == LmsService.QrStatus.UNREACHABLE) {
                    userState.put(userId, "IDLE");
                    deleteMessage(chatId, photoId);
                    sendOneIdUnreachable(chatId, userId);
                    return;
                }
                if (st == LmsService.QrStatus.ERROR) {
                    userState.put(userId, "IDLE");
                    deleteMessage(chatId, photoId);
                    sendOneIdError(chatId, userId, null);
                    return;
                }
                // Код истёк или вот-вот истечёт — меняем картинку в том же сообщении.
                if (st == LmsService.QrStatus.EXPIRED || System.currentTimeMillis() > qr.expiresAt() - 4000) {
                    qr = lmsService.oneIdQrNext(userId);
                    photoId = sendQrPhoto(chatId, userId, qr, photoId);
                }
            }
            if (!qrActive(userId, attempt)) {
                // Ушли в другой раздел или начали новую попытку — старый код больше не нужен.
                deleteMessage(chatId, photoId);
            } else {
                userState.put(userId, "IDLE");
                lmsService.oneIdQrCancel(userId);
                deleteMessage(chatId, photoId);
                send(chatId, tr(userId,
                        "⌛ Время ожидания вышло. Чтобы получить новый QR-код: /login",
                        "⌛ Kutish vaqti tugadi. Yangi QR-kod uchun: /login",
                        "⌛ Кутиш вақти тугади. Янги QR-код учун: /login",
                        "⌛ Time is up. To get a new QR code: /login"), loginKeyboard(userId));
            }
        } catch (Exception e) {
            System.err.println("[LmsBot] OneID QR " + userId + ": " + e.getMessage());
            if (qrActive(userId, attempt)) userState.put(userId, "IDLE");
            deleteMessage(chatId, photoId);
            if (e instanceof java.io.IOException) sendOneIdUnreachable(chatId, userId);
            else sendOneIdError(chatId, userId, null);
        }
    }

    /** Присылает QR или подменяет картинку в уже отправленном сообщении. Возвращает id сообщения. */
    private Integer sendQrPhoto(long chatId, long userId, LmsService.OneIdQr qr, Integer messageId) throws Exception {
        byte[] png = uz.tuit.lmsbot.util.QrImage.png(qr.payload(), 600);
        if (messageId != null) {
            try {
                org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto media =
                        new org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto();
                media.setMedia(new java.io.ByteArrayInputStream(png), "oneid-qr.png");
                media.setCaption(qrCaption(userId, qr.expiresAt()));
                media.setParseMode("HTML");
                execute(org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageMedia.builder()
                        .chatId(String.valueOf(chatId)).messageId(messageId).media(media)
                        .replyMarkup(qrCancelKeyboard(userId)).build());
                return messageId;
            } catch (Exception e) {
                // Сообщение удалили — пришлём новое.
                System.err.println("[LmsBot] QR edit: " + e.getMessage());
            }
        }
        dropProgress(chatId);
        SendPhoto p = new SendPhoto();
        p.setChatId(String.valueOf(chatId));
        p.setPhoto(new InputFile(new java.io.ByteArrayInputStream(png), "oneid-qr.png"));
        p.setCaption(qrCaption(userId, qr.expiresAt()));
        p.setParseMode("HTML");
        p.setReplyMarkup(qrCancelKeyboard(userId));
        return execute(p).getMessageId();
    }

    private void cancelOneIdQr(long chatId, long userId, int messageId) {
        qrAttempt.merge(userId, 1, Integer::sum);
        if ("WAIT_ONEID_QR".equals(userState.get(userId))) userState.put(userId, "IDLE");
        lmsService.oneIdQrCancel(userId);
        deleteMessage(chatId, messageId);
        send(chatId, tr(userId, "❌ Вход по QR-коду отменён.", "❌ QR-kod bilan kirish bekor qilindi.",
                "❌ QR-код билан кириш бекор қилинди.", "❌ QR code sign-in cancelled."), loginKeyboard(userId));
    }

    private void deleteMessage(long chatId, Integer messageId) {
        if (messageId == null) return;
        try {
            DeleteMessage del = new DeleteMessage();
            del.setChatId(String.valueOf(chatId));
            del.setMessageId(messageId);
            execute(del);
        } catch (Exception ignored) {
            // Уже удалено или старше 48 часов.
        }
    }

    private void askForOneIdPhone(long chatId, long userId) {
        userState.put(userId, "WAIT_ONEID_PHONE");
        send(chatId, tr(userId,
                "📱 <b>Вход через Mobile ID</b>\n\nВведите номер телефона, привязанный к Mobile ID:\n\nНапример: <code>+998901234567</code>",
                "📱 <b>Mobile ID orqali kirish</b>\n\nMobile ID ga bog'langan telefon raqamini kiriting:\n\nMasalan: <code>+998901234567</code>",
                "📱 <b>Mobile ID орқали кириш</b>\n\nMobile ID га боғланган телефон рақамини киритинг:\n\nМасалан: <code>+998901234567</code>",
                "📱 <b>Sign in with Mobile ID</b>\n\nEnter the phone number linked to Mobile ID:\n\nFor example: <code>+998901234567</code>"), null);
    }

    private void handleOneIdPhone(long chatId, long userId, String phone) {
        sendProgress(chatId, tr(userId,
                "⏳ Отправляем SMS...",
                "⏳ SMS yuborilmoqda...",
                "⏳ SMS юборилмоқда...",
                "⏳ Sending SMS..."));
        executor.submit(() -> {
            LmsService.OneIdResult res = lmsService.oneIdMobileSendSms(userId, phone);
            if (res.status == LmsService.OneIdResult.Status.NEED_SMS) {
                userState.put(userId, "WAIT_ONEID_MOBILE_SMS");
                send(chatId, tr(userId,
                        "✉️ SMS-код отправлен на <code>" + phone + "</code>\n\nВведите код из SMS:",
                        "✉️ SMS-kod <code>" + phone + "</code> raqamiga yuborildi\n\nSMS dagi kodni kiriting:",
                        "✉️ SMS-код <code>" + phone + "</code> рақамига юборилди\n\nSMS даги кодни киритинг:",
                        "✉️ SMS code sent to <code>" + phone + "</code>\n\nEnter the code from the SMS:"), null);
            } else {
                sendOneIdError(chatId, userId, res.message);
            }
        });
    }

    private void handleOneIdMobileSms(long chatId, long userId, String code) {
        sendProgress(chatId, tr(userId,
                "⏳ Проверяем код...",
                "⏳ Kod tekshirilmoqda...",
                "⏳ Код текширилмоқда...",
                "⏳ Checking the code..."));
        executor.submit(() -> {
            LmsService.OneIdResult res = lmsService.oneIdMobileConfirm(userId, code);
            if (res.status == LmsService.OneIdResult.Status.OK) {
                finishOneId(chatId, userId, null);
            } else {
                sendOneIdError(chatId, userId, res.message);
            }
        });
    }

    private void askForOneIdLogin(long chatId, long userId) {
        userState.put(userId, "WAIT_ONEID_LOGIN");
        send(chatId, tr(userId,
                "🆔 <b>Вход через OneID</b>\n\nВведите <b>логин OneID</b> от аккаунта id.egov.uz:",
                "🆔 <b>OneID orqali kirish</b>\n\nid.egov.uz hisobingizning <b>OneID loginini</b> kiriting:",
                "🆔 <b>OneID орқали кириш</b>\n\nid.egov.uz ҳисобингизнинг <b>OneID логинини</b> киритинг:",
                "🆔 <b>Sign in with OneID</b>\n\nEnter your <b>OneID login</b> for id.egov.uz:"), null);
    }

    private void handleOneIdLogin(long chatId, long userId, String login, String password) {
        handleOneIdLogin(chatId, userId, login, password, true);
    }

    private void handleOneIdLogin(long chatId, long userId, String login, String password, boolean offerSave) {
        // Пароль держим в памяти до ответа на вопрос о сохранении; откажется — забудем.
        if (offerSave) pendingCreds.put(userId, new String[]{"oneid", login, password});
        // При автовходе через меню этот шаг пропускается, а шагу с кодом 2FA логин нужен.
        if (login != null && !login.isBlank()) tempOneIdLogin.put(userId, login);
        sendProgress(chatId, tr(userId,
                "⏳ Проверяем данные в OneID...",
                "⏳ OneID ma'lumotlari tekshirilmoqda...",
                "⏳ OneID маълумотлари текширилмоқда...",
                "⏳ Checking your OneID details..."));
        executor.submit(() -> {
            LmsService.OneIdResult res = lmsService.oneIdLogin(userId, login, password);
            switch (res.status) {
                case NEED_SMS -> {
                    userState.put(userId, "WAIT_ONEID_SMS");
                    send(chatId, tr(userId,
                            "🔑 <b>Двухфакторная аутентификация</b>\n\nОткройте приложение <b>OneID</b> "
                                    + "и введите <b>код аутентификации</b>:",
                            "🔑 <b>Ikki bosqichli autentifikatsiya</b>\n\n<b>OneID</b> ilovasini oching "
                                    + "va <b>autentifikatsiya kodini</b> kiriting:",
                            "🔑 <b>Икки босқичли аутентификация</b>\n\n<b>OneID</b> иловасини очинг "
                                    + "ва <b>аутентификация кодини</b> киритинг:",
                            "🔑 <b>Two-factor authentication</b>\n\nOpen the <b>OneID</b> app"
                                    + "and enter the <b>authentication code</b>:"), null);
                }
                case OK -> finishOneId(chatId, userId, login);
                case UNREACHABLE -> {
                    tempOneIdLogin.remove(userId);
                    sendOneIdUnreachable(chatId, userId);
                }
                default -> {
                    tempOneIdLogin.remove(userId);
                    pendingCreds.remove(userId);
                    sendOneIdError(chatId, userId, res.message);
                }
            }
        });
    }

    private void handleOneIdSms(long chatId, long userId, String code) {
        sendProgress(chatId, tr(userId,
                "⏳ Проверяем код...",
                "⏳ Kod tekshirilmoqda...",
                "⏳ Код текширилмоқда...",
                "⏳ Checking the code..."));
        executor.submit(() -> {
            String login = tempOneIdLogin.get(userId);
            LmsService.OneIdResult res = lmsService.oneIdConfirm(userId, login, code);
            if (res.status == LmsService.OneIdResult.Status.OK) {
                finishOneId(chatId, userId, login);
            } else if (res.status == LmsService.OneIdResult.Status.UNREACHABLE) {
                tempOneIdLogin.remove(userId);
                sendOneIdUnreachable(chatId, userId);
            } else {
                tempOneIdLogin.remove(userId);
                pendingCreds.remove(userId);
                sendOneIdError(chatId, userId, res.message);
            }
        });
    }

    /** Завершающий шаг: обмен OneID-токена на сессию LMS. */
    private void finishOneId(long chatId, long userId, String login) {
        send(chatId, tr(userId,
                "🔗 Входим в LMS через OneID...",
                "🔗 OneID orqali LMS ga kirilmoqda...",
                "🔗 OneID орқали LMS га кирилмоқда...",
                "🔗 Signing in to LMS with OneID..."), null);
        boolean ok = lmsService.oneIdFinish(userId);
        tempOneIdLogin.remove(userId);
        if (ok) {
            resetUserCaches(userId);
            if (login != null && !login.isBlank()) userLogin.put(userId, login.trim());
            send(chatId, tr(userId,
                            "✅ <b>Вы успешно вошли через OneID!</b>\n\nВыберите пункт из меню ниже 👇",
                            "✅ <b>OneID orqali muvaffaqiyatli kirdingiz!</b>\n\nQuyidagi menyudan foydalaning 👇",
                            "✅ <b>OneID орқали муваффақиятли кирдингиз!</b>\n\nҚуйидаги менюдан фойдаланинг 👇",
                            "✅ <b>Signed in with OneID!</b>\n\nChoose an item from the menu below 👇"),
                    mainMenuKeyboard(userId));
            onBotLogin(chatId, userId);
            offerSaveCreds(chatId, userId);
        } else {
            pendingCreds.remove(userId);
            send(chatId, tr(userId,
                            "❌ <b>Не удалось связать OneID с LMS</b>\n\n<blockquote>Возможно, ваш аккаунт OneID не привязан к lms.tuit.uz. Попробуйте ещё раз.</blockquote>",
                            "❌ <b>OneID ni LMS bilan bog'lab bo'lmadi</b>\n\n<blockquote>OneID hisobingiz lms.tuit.uz ga bog'lanmagan bo'lishi mumkin. Qayta urinib ko'ring.</blockquote>",
                            "❌ <b>OneID ни LMS билан боғлаб бўлмади</b>\n\n<blockquote>OneID ҳисобингиз lms.tuit.uz га боғланмаган бўлиши мумкин. Қайта уриниб кўринг.</blockquote>",
                            "❌ <b>Could not link OneID to LMS</b>\n\n<blockquote>Your OneID account may not be linked to lms.tuit.uz. Please try again.</blockquote>"),
                    loginKeyboard(userId));
        }
    }

    /** OneID не отвечает: это не про пароль, и вход по логину LMS в это время работает. */
    private void sendOneIdUnreachable(long chatId, long userId) {
        pendingCreds.remove(userId);
        send(chatId, tr(userId,
                "🚧 <b>OneID сейчас недоступен</b>\n\n<blockquote>Сервер id.egov.uz не отвечает — пароль тут ни при чём. Попробуйте позже или войдите логином и паролем LMS.</blockquote>",
                "🚧 <b>OneID hozir ishlamayapti</b>\n\n<blockquote>id.egov.uz javob bermayapti — parol aybdor emas. Keyinroq urinib ko'ring yoki LMS login va paroli bilan kiring.</blockquote>",
                "🚧 <b>OneID ҳозир ишламаяпти</b>\n\n<blockquote>id.egov.uz жавоб бермаяпти — парол айбдор эмас. Кейинроқ уриниб кўринг ёки LMS логин ва пароли билан киринг.</blockquote>",
                "🚧 <b>OneID is unavailable right now</b>\n\n<blockquote>id.egov.uz is not responding — your password is fine. Try later, or sign in with your LMS login and password.</blockquote>"),
                loginKeyboard(userId));
    }

    private void sendOneIdError(long chatId, long userId, String oneIdMessage) {
        String extra = (oneIdMessage == null || oneIdMessage.isBlank())
                ? "" : "\n\n<blockquote>" + esc(oneIdMessage) + "</blockquote>";
        send(chatId, tr(userId,
                        "❌ <b>Вход через OneID не удался</b>" + extra + "\n\nПопробуйте ещё раз: /login",
                        "❌ <b>OneID orqali kirish amalga oshmadi</b>" + extra + "\n\nQayta urinib ko'ring: /login",
                        "❌ <b>OneID орқали кириш амалга ошмади</b>" + extra + "\n\nҚайта уриниб кўринг: /login",
                        "❌ <b>OneID sign-in failed</b>" + extra + "\n\nTry again: /login"),
                loginKeyboard(userId));
    }

    private void handleLogin(long chatId, long userId, String login, String password) {
        handleLogin(chatId, userId, login, password, true);
    }

    private void handleLogin(long chatId, long userId, String login, String password, boolean offerSave) {
        if (offerSave) pendingCreds.put(userId, new String[]{"lms", login, password});
        sendProgress(chatId, tr(userId,
                "⏳ Входим в систему...",
                "⏳ Tizimga kirilmoqda...",
                "⏳ Тизимга кирилмоқда...",
                "⏳ Signing in..."));
        executor.submit(() -> {
            LmsService.LoginResult res = lmsService.loginDetailed(userId, login, password);
            if (res == LmsService.LoginResult.ONEID_REQUIRED) {
                // Пароль верный, но LMS пускает этот аккаунт только через OneID — как на сайте.
                send(chatId, tr(userId,
                                "🆔 <b>Пожалуйста, войдите в систему через OneID.</b>\n\n<blockquote>LMS не пускает этот аккаунт по логину и паролю — нужен вход через OneID.</blockquote>",
                                "🆔 <b>Iltimos, OneID orqali tizimga kiring.</b>\n\n<blockquote>LMS bu hisobni login va parol bilan kiritmaydi — OneID orqali kirish kerak.</blockquote>",
                                "🆔 <b>Илтимос, OneID орқали тизимга киринг.</b>\n\n<blockquote>LMS бу ҳисобни логин ва парол билан киритмайди — OneID орқали кириш керак.</blockquote>",
                                "🆔 <b>Please sign in with OneID.</b>\n\n<blockquote>LMS does not allow this account to sign in with a login and password — OneID is required.</blockquote>"),
                        markup(List.of(List.of(inlineBtn(tr(userId,
                                "🆔 Войти через OneID", "🆔 OneID orqali kirish", "🆔 OneID орқали кириш", "🆔 Sign in with OneID"),
                                "auth_oneid")))));
                pendingCreds.remove(userId);
                return;
            }
            if (res == LmsService.LoginResult.ERROR) {
                send(chatId, tr(userId,
                                "⚠️ <b>LMS не отвечает.</b>\n\nПопробуйте войти чуть позже.",
                                "⚠️ <b>LMS javob bermayapti.</b>\n\nBirozdan keyin qayta kiring.",
                                "⚠️ <b>LMS жавоб бермаяпти.</b>\n\nБироздан кейин қайта киринг.",
                                "⚠️ <b>LMS is not responding.</b>\n\nPlease try signing in a bit later."),
                        loginKeyboard(userId));
                pendingCreds.remove(userId);
                return;
            }
            boolean ok = res == LmsService.LoginResult.OK;
            if (ok) {
                resetUserCaches(userId);
                if (login != null && !login.isBlank()) userLogin.put(userId, login.trim());
                send(chatId, tr(userId,
                                "✅ <b>Вы успешно вошли!</b>\n\nВыберите пункт из меню ниже 👇",
                                "✅ <b>Muvaffaqiyatli kirdingiz!</b>\n\nQuyidagi menyudan foydalaning 👇",
                                "✅ <b>Муваффақиятли кирдингиз!</b>\n\nҚуйидаги менюдан фойдаланинг 👇",
                                "✅ <b>Signed in!</b>\n\nChoose an item from the menu below 👇"),
                        mainMenuKeyboard(userId));
                onBotLogin(chatId, userId);
                offerSaveCreds(chatId, userId);
            } else {
                pendingCreds.remove(userId);
                send(chatId, tr(userId,
                                "❌ <b>Неверный логин или пароль!</b>\n\nПопробуйте ещё раз.",
                                "❌ <b>Login yoki parol noto'g'ri!</b>\n\nQayta urinib ko'ring.",
                                "❌ <b>Логин ёки парол нотўғри!</b>\n\nҚайта уриниб кўринг.",
                                "❌ <b>Wrong login or password!</b>\n\nPlease try again."),
                        loginKeyboard(userId));
            }
        });
    }

    /**
     * Сбрасывает всё, что кэшировано под старую сессию. Вызывается и при выходе,
     * и при каждом успешном входе: иначе после входа в аккаунт остаются предметы,
     * семестр и дедлайны предыдущей сессии, и данные приходят от чужого семестра.
     */
    private void resetUserCaches(long userId) {
        userCourses.remove(userId);
        userSemester.remove(userId);
        userActivities.remove(userId);
        userCalendars.remove(userId);
        pendingFiles.remove(userId);
        semesterSchedule.invalidate(userId);
        lastNbCheckTs.remove(userId);
        lastNbByCourse.remove(userId);
        lastUrgentScanTs.remove(userId);
        sentReminders.remove(userId);
        lastDeadlineNotifyTs.remove(userId);
        lastDeadlinesListNotifyTs.remove(userId);
        deadlineMeta.remove(userId);
        lastDeadlineItems.remove(userId);
        teacherBot.reset(userId);
    }

    /**
     * Семестры приходят только из LMS. Пока список не получен, id неизвестен (-1),
     * и слать запрос с выдуманным номером нельзя — вернётся пустота.
     */
    boolean checkSemester(long chatId, long userId, int semesterId) {
        if (semesterId > 0) return true;
        send(chatId, t(userId, "sem.unavailable"), null);
        return false;
    }

    /** Запоминать семестр можно, только если он реально вычитан из LMS. */
    void rememberSemester(long userId, int semesterId) {
        if (lmsService.isSemesterDetected(userId)) userSemester.put(userId, semesterId);
    }

    // ─────────────────────────────────────────────
    //  АВТОВХОД
    //  Кнопка появляется на клавиатуре этапа авторизации, если пользователь сам
    //  разрешил сохранить логин и пароль. Хранятся они не в базе, а в шифрованном
    //  файле рядом с сессиями (CredentialStore), и /logout их удаляет.
    // ─────────────────────────────────────────────

    /** Спрашивает согласие на сохранение; молчит, если сохранять нечего или уже сохранено. */
    private void offerSaveCreds(long chatId, long userId) {
        String[] c = pendingCreds.remove(userId);
        if (c == null) return;
        for (CredentialStore.Creds saved : lmsService.credentials().all(userId)) {
            // Уже сохранён с тем же паролем — спрашивать второй раз незачем.
            if (saved.login().equals(c[1]) && saved.password().equals(c[2])) return;
        }
        pendingCreds.put(userId, c);
        InlineKeyboardMarkup kb = markup(List.of(
                List.of(inlineBtn(tr(userId, "✅ Сохранить", "✅ Saqlash", "✅ Сақлаш", "✅ Save"), "creds_yes")),
                List.of(inlineBtn(tr(userId, "🚫 Не сохранять", "🚫 Saqlanmasin", "🚫 Сақланмасин", "🚫 Don't save"), "creds_no"))
        ));
        send(chatId, tr(userId,
                "💾 <b>Сохранить логин и пароль для автовхода?</b>\n\n"
                        + "<blockquote>Тогда при следующем входе хватит одной кнопки «⚡ Автовход» — вводить ничего не придётся.\n\n"
                        + "Данные шифруются и хранятся только для вашего аккаунта. Команда /logout удаляет их.</blockquote>",
                "💾 <b>Avtokirish uchun login va parol saqlansinmi?</b>\n\n"
                        + "<blockquote>Keyingi safar «⚡ Avtokirish» tugmasining o'zi yetadi — hech narsa kiritish shart emas.\n\n"
                        + "Ma'lumotlar shifrlanadi va faqat sizning hisobingiz uchun saqlanadi. /logout ularni o'chiradi.</blockquote>",
                "💾 <b>Автокириш учун логин ва парол сақлансинми?</b>\n\n"
                        + "<blockquote>Кейинги сафар «⚡ Автокириш» тугмасининг ўзи етади — ҳеч нарса киритиш шарт эмас.\n\n"
                        + "Маълумотлар шифрланади ва фақат сизнинг ҳисобингиз учун сақланади. /logout уларни ўчиради.</blockquote>",
                "💾 <b>Save your login and password for auto sign-in?</b>\n\n"
                        + "<blockquote>Next time the «⚡ Auto sign-in» button alone will be enough — nothing to type.\n\n"
                        + "The data is encrypted and kept for your account only. /logout deletes it.</blockquote>"), kb);
    }

    private void saveCredsAnswer(long chatId, long userId, boolean save) {
        String[] c = pendingCreds.remove(userId);
        if (!save || c == null) {
            send(chatId, tr(userId,
                    "👌 Ничего не сохранил.",
                    "👌 Hech narsa saqlanmadi.",
                    "👌 Ҳеч нарса сақланмади.",
                    "👌 Nothing was saved."), null);
            return;
        }
        lmsService.credentials().add(userId, new CredentialStore.Creds(c[0], c[1], c[2]));
        send(chatId, tr(userId,
                "✅ Сохранено. В следующий раз нажмите «⚡ Автовход».",
                "✅ Saqlandi. Keyingi safar «⚡ Avtokirish» tugmasini bosing.",
                "✅ Сақланди. Кейинги сафар «⚡ Автокириш» тугмасини босинг.",
                "✅ Saved. Next time just tap «⚡ Auto sign-in»."), null);
    }

    /** Список сохранённых аккаунтов: выбрать, каким входить, или удалить лишние. */
    private void showCreds(long chatId, long userId) {
        java.util.List<CredentialStore.Creds> list = lmsService.credentials().all(userId);
        String selected = lmsService.credentials().selectedKey(userId);
        java.util.List<java.util.List<org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton>> rows =
                new ArrayList<>();
        for (CredentialStore.Creds c : list) {
            String mark = c.key().equals(selected) ? "✅ " : "▫️ ";
            String kind = "oneid".equals(c.method()) ? "OneID" : "LMS";
            rows.add(List.of(
                    inlineBtn(mark + c.login() + " · " + kind, "credsel_" + c.key()),
                    inlineBtn("🗑", "creddel_" + c.key())));
        }
        rows.add(List.of(inlineBtn(tr(userId,
                "➕ Добавить аккаунт", "➕ Hisob qo'shish", "➕ Ҳисоб қўшиш", "➕ Add account"), "creds_add")));

        String head = list.isEmpty()
                ? tr(userId,
                    "🗂 <b>Мои аккаунты</b>\n\nПока ничего не сохранено. Войдите и согласитесь сохранить данные — аккаунт появится здесь.",
                    "🗂 <b>Hisoblarim</b>\n\nHozircha hech narsa saqlanmagan. Tizimga kiring va saqlashga rozi bo'ling — hisob shu yerda paydo bo'ladi.",
                    "🗂 <b>Ҳисобларим</b>\n\nҲозирча ҳеч нарса сақланмаган. Тизимга киринг ва сақлашга рози бўлинг — ҳисоб шу ерда пайдо бўлади.",
                    "🗂 <b>My accounts</b>\n\nNothing saved yet. Sign in and agree to save — the account will show up here.")
                : tr(userId,
                    "🗂 <b>Мои аккаунты</b>\n\n<blockquote>Отмеченный ✅ используется кнопкой «⚡ Автовход». Нажмите на другой, чтобы выбрать его, или 🗑 — чтобы удалить.</blockquote>",
                    "🗂 <b>Hisoblarim</b>\n\n<blockquote>✅ bilan belgilangani «⚡ Avtokirish» tugmasida ishlatiladi. Boshqasini tanlash uchun bosing, o'chirish uchun 🗑.</blockquote>",
                    "🗂 <b>Ҳисобларим</b>\n\n<blockquote>✅ билан белгиланган «⚡ Автокириш» тугмасида ишлатилади. Бошқасини танлаш учун босинг, ўчириш учун 🗑.</blockquote>",
                    "🗂 <b>My accounts</b>\n\n<blockquote>The one marked ✅ is used by «⚡ Auto sign-in». Tap another to pick it, or 🗑 to delete.</blockquote>");
        send(chatId, head, markup(rows));
    }

    private void removeCreds(long chatId, long userId, String id) {
        lmsService.credentials().remove(userId, id);
        send(chatId, tr(userId,
                "🗑 Аккаунт удалён.",
                "🗑 Hisob o'chirildi.",
                "🗑 Ҳисоб ўчирилди.",
                "🗑 Account deleted."), loginKeyboard(userId));
        showCreds(chatId, userId);
    }

    /** Кнопка «Автовход»: бот сам подставляет сохранённые данные и входит. */
    private void handleAutoLogin(long chatId, long userId) {
        CredentialStore.Creds c = lmsService.credentials().selected(userId);
        if (c == null) {
            send(chatId, tr(userId,
                    "🤷 Сохранённых данных нет — войдите обычным способом.",
                    "🤷 Saqlangan ma'lumot yo'q — odatdagidek kiring.",
                    "🤷 Сақланган маълумот йўқ — одатдагидек киринг.",
                    "🤷 Nothing is saved — please sign in the usual way."), loginKeyboard(userId));
            return;
        }
        send(chatId, tr(userId,
                "⚡ Входим как <b>" + esc(c.login()) + "</b>...",
                "⚡ <b>" + esc(c.login()) + "</b> sifatida kirilmoqda...",
                "⚡ <b>" + esc(c.login()) + "</b> сифатида кирилмоқда...",
                "⚡ Signing in as <b>" + esc(c.login()) + "</b>..."), null);
        // offerSave=false: данные уже сохранены, второй раз спрашивать незачем.
        if ("oneid".equals(c.method())) handleOneIdLogin(chatId, userId, c.login(), c.password(), false);
        else                            handleLogin(chatId, userId, c.login(), c.password(), false);
    }

    private void handleLogout(long chatId, long userId) {
        lmsService.logout(userId);
        resetUserCaches(userId);
        userLogin.remove(userId);
        pendingCreds.remove(userId);
        String tail = lmsService.credentials().has(userId)
                ? tr(userId,
                    "\n\nСохранённые аккаунты остались — «⚡ Автовход» вернёт вас одним нажатием, а удалить их можно в «🗂 Мои аккаунты».",
                    "\n\nSaqlangan hisoblar joyida — «⚡ Avtokirish» bir bosishda qaytaradi, o'chirish «🗂 Hisoblarim» da.",
                    "\n\nСақланган ҳисоблар жойида — «⚡ Автокириш» бир босишда қайтаради, ўчириш «🗂 Ҳисобларим» да.",
                    "\n\nSaved accounts are still there — «⚡ Auto sign-in» brings you back in one tap, delete them in «🗂 My accounts».")
                : "";
        send(chatId, tr(userId,
                "👋 Вы вышли из системы.\n\nЧтобы войти снова: /login",
                "👋 Tizimdan chiqtingiz.\n\nQayta kirish: /login",
                "👋 Тизимдан чиқдингиз.\n\nҚайта кириш: /login",
                "👋 You've signed out.\n\nTo sign in again: /login") + tail, loginKeyboard(userId));
    }

    private void sendMainMenu(long chatId, long userId) {
        send(chatId, tr(userId,
                "🏠 <b>Главное меню:</b>",
                "🏠 <b>Asosiy menyu:</b>",
                "🏠 <b>Асосий меню:</b>",
                "🏠 <b>Main menu:</b>"), mainMenuKeyboard(userId));
    }

    // ─────────────────────────────────────────────
    //  COURSES
    // ─────────────────────────────────────────────

    private void showCourses(long chatId, long userId, int semesterId) {
        if (!checkLogin(chatId, userId)) return;
        if (!checkSemester(chatId, userId, semesterId)) return;
        sendProgress(chatId, t(userId, "courses.loading"));

        executor.submit(() -> {
            List<Course> courses = lmsService.getMyCourses(userId, semesterId);
            String semName = lmsService.semesterName(userId, semesterId);

            if (courses.isEmpty()) {
                // Семестр НЕ закрепляем: у переобучения предметов часто нет вовсе,
                // и закреплённый пустой семестр обнулил бы все остальные разделы.
                send(chatId, t(userId, "courses.empty_semester"), semesterKeyboard(userId));
                return;
            }
            userCourses.put(userId, courses);
            userSemester.put(userId, semesterId);

            String[] courseColors = {"🟡","🟢","🔵","🟣","🟠","🔴","⚪","🟤"};
            StringBuilder msg = new StringBuilder();
            msg.append("📚 <b>").append(esc(semName)).append("</b>\n\n");

            for (int i = 0; i < courses.size(); i++) {
                Course c = courses.get(i);
                String dot = courseColors[i % courseColors.length];
                String inner = dot + " <b>" + (i + 1) + ". " + esc(c.getSubject()) + "</b>"
                        + (c.isFailed() ? " ⚠️" : "") + "\n"
                        + "👨‍🏫 " + esc(c.getFormattedTeachers()) + "\n"
                        + (c.getAttendance() > 0 ? "🔴" : "🟢") + " "
                        + tr(userId, "НБ", "NB", "НБ", "Absences") + ": <b>" + c.getAttendance() + "</b>";
                msg.append("<blockquote>").append(inner).append("</blockquote>\n");
            }

            msg.append("\n📌 ").append(t(userId, "courses.total")).append(": <b>")
                    .append(courses.size()).append("</b> ")
                    .append(t(userId, "courses.subjects_suffix"));
            send(chatId, msg.toString(), withBack(userId, coursesActionKeyboard(userId)));
        });
    }

    // ─────────────────────────────────────────────
    //  COURSE SELECTION (edit in-place)
    // ─────────────────────────────────────────────

    private void editCourseSelection(long chatId, long userId, int messageId, String action) {
        List<Course> courses = userCourses.get(userId);
        int semesterId = userSemester.getOrDefault(userId, getDefaultSemesterId(userId));

        if (courses == null || courses.isEmpty()) {
            edit(chatId, messageId,
                    tr(userId,
                            "⚠️ Сначала откройте «Мои предметы».",
                            "⚠️ Avval \"Mening fanlarim\" bo'limini oching.",
                            "⚠️ Аввал «Менинг фанларим» бўлимини очинг.",
                            "⚠️ Open «My subjects» first."),
                    null);
            return;
        }
        String title = switch (action) {
            case "attend"   -> tr(userId, "📊 <b>Посещаемость</b> — выберите предмет:", "📊 <b>Davomat</b> — fan tanlang:", "📊 <b>Давомат</b> — фан танланг:", "📊 <b>Attendance</b> — choose a subject:");
            case "calendar" -> tr(userId, "📆 <b>План занятий</b> — выберите предмет:", "📆 <b>Dars rejasi</b> — fan tanlang:", "📆 <b>Дарс режаси</b> — фан танланг:", "📆 <b>Class plan</b> — choose a subject:");
            default         -> tr(userId, "📋 <b>Активности</b> — выберите предмет:", "📋 <b>Aktivnosti</b> — fan tanlang:", "📋 <b>Активностлар</b> — фан танланг:", "📋 <b>Activities</b> — choose a subject:");
        };

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (int i = 0; i < courses.size(); i++) {
            Course c = courses.get(i);
            String label  = (c.isFailed() ? "⚠️ " : "") + c.getSubject();
            String cbData = switch (action) {
                case "attend"   -> "ai_" + i + "_" + semesterId;
                case "calendar" -> "cal_" + i;
                default         -> "ac_" + i;
            };
            rows.add(List.of(inlineBtn(label, cbData)));
        }
        rows.add(List.of(inlineBtn(t(userId, "btn.back_courses"), "back_to_courses")));
        edit(chatId, messageId, title, markup(rows));
    }

    // ─────────────────────────────────────────────
    //  ATTENDANCE (edit in-place)
    // ─────────────────────────────────────────────

    private void showAttendance(long chatId, long userId, int messageId, int subjectId, int semesterId) {
        if (!checkLogin(chatId, userId)) return;
        edit(chatId, messageId, t(userId, "att.loading"), null);

        executor.submit(() -> {
            List<Course> courses = userCourses.get(userId);
            String subjectName = null;
            if (courses != null)
                for (Course c : courses)
                    if (c.getId() == subjectId) { subjectName = c.getSubject(); break; }

            List<AttendanceRecord> records = lmsService.getAttendance(userId, subjectId, semesterId, subjectName);
            if (records.isEmpty()) { edit(chatId, messageId, t(userId, "att.none"), null); return; }

            AttendanceRecord summary = records.get(0);
            List<AttendanceRecord> missed = records.subList(1, records.size());

            InlineKeyboardMarkup backMarkup = markup(List.of(
                    List.of(inlineBtn(t(userId, "btn.back"), "select_attend"))
            ));

            if (missed.isEmpty()) {
                String ok = t(userId, "att.all_ok_title") + "\n\n<blockquote>"
                        + t(userId, "att.total_lessons") + ": <b>" + summary.getTotal() + "</b>"
                        + "</blockquote>";
                edit(chatId, messageId, ok, backMarkup);
                return;
            }

            StringBuilder sb = new StringBuilder();
            String title = subjectName != null && !subjectName.isEmpty() ? subjectName
                    : (missed.isEmpty() ? "" : missed.get(0).getSubject());
            if (title != null && !title.isEmpty())
                sb.append("📋 <b>").append(esc(title)).append("</b>\n\n");
            sb.append("<blockquote>")
                    .append(t(userId, "att.total_lessons")).append(": <b>").append(summary.getTotal()).append("</b>\n")
                    .append(t(userId, "att.missed")).append(": <b>").append(summary.getMissed()).append("</b>")
                    .append("</blockquote>\n\n");

            for (AttendanceRecord r : missed) {
                StringBuilder rec = new StringBuilder();
                rec.append(isLecture(r.getType()) ? "📖" : "🔬")
                        .append(" <b>").append(esc(r.getDate())).append("</b> — ").append(esc(r.getType())).append("\n");
                rec.append(r.getHasReason() == 1 ? t(userId, "att.reason_yes") : t(userId, "att.reason_no")).append("\n");
                if (r.getCalendar() != null && !r.getCalendar().isEmpty())
                    rec.append("📝 <i>").append(esc(r.getCalendar())).append("</i>\n");
                sb.append("<blockquote>").append(rec.toString().trim()).append("</blockquote>\n");
            }

            edit(chatId, messageId, sb.toString(), backMarkup);
        });
    }

    // ─────────────────────────────────────────────
    //  ACTIVITIES
    // ─────────────────────────────────────────────

    private void showActivities(long chatId, long userId, int courseId) {
        if (!checkLogin(chatId, userId)) return;
        sendProgress(chatId, t(userId, "act.loading"));

        executor.submit(() -> {
            CourseSummary summary = lmsService.getActivities(userId, courseId);
            if (summary == null) { send(chatId, t(userId, "act.no_data"), null); return; }

            List<Activity> activities = summary.getActivities();
            userActivities.computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                    .put(courseId, activities != null ? activities : Collections.emptyList());

            StringBuilder sb = new StringBuilder();
            sb.append("📊 <b>").append(tr(userId, "Результаты", "Natijalar", "Натижалар", "Results")).append("</b>\n<blockquote>");
            sb.append("🏆 ").append(tr(userId, "Балл", "Ball", "Балл", "Score")).append(": <b>").append(orDash(summary.getEarned()))
                    .append("</b> / <b>").append(orDash(summary.getMaxScore())).append("</b>\n");
            sb.append("📈 ").append(tr(userId, "Прогресс", "Muvaffaqiyat", "Муваффақият", "Progress")).append(": <b>").append(orDash(summary.getProgress())).append("</b>\n");
            sb.append("🎓 ").append(tr(userId, "Оценка", "Baho", "Баҳо", "Grade")).append(": <b>").append(orDash(summary.getGrade())).append("</b>");
            sb.append("</blockquote>\n\n");

            if (activities == null || activities.isEmpty()) {
                sb.append(t(userId, "act.none"));
                send(chatId, sb.toString(), backOnly(userId));
                return;
            }

            sb.append("📋 <b>").append(tr(userId, "Задания", "Topshiriqlar", "Топшириқлар", "Assignments"))
                    .append("</b>: <b>").append(activities.size()).append("</b>\n\n");

            long now = System.currentTimeMillis();
            for (int i = 0; i < activities.size(); i++) {
                Activity a = activities.get(i);
                String datePart = (a.getDeadline() != null && a.getDeadline().length() >= 10)
                        ? a.getDeadline().substring(0, 10) : "—";
                long dl = parseActivityDeadlineTs(a.getDeadline());
                boolean expired  = dl > 0 && dl <= now;
                boolean hasUpload = a.getUploadedFileUrl() != null && !a.getUploadedFileUrl().isBlank();

                String statusIcon = activityStatusIcon(a, hasUpload, expired);
                String earned = orDash(a.getEarnedScore());
                String max    = orDash(a.getMaxScore());
                String teacher = (a.getTeacher() != null && !a.getTeacher().isBlank()) ? a.getTeacher() : "—";
                String typeIcon = isLecture(a.getType()) ? "📖" : "🔬";

                sb.append("<blockquote>")
                        .append(statusIcon).append(" ").append(typeIcon).append(" <b>").append(i + 1).append(".</b> ")
                        .append(esc(truncateTopic(a.getTask(), 40))).append("\n")
                        .append("⏰ <b>").append(esc(datePart)).append("</b>")
                        .append(" | 🏆 ").append(earned).append("/").append(max).append("\n")
                        .append("👨‍🏫 ").append(esc(teacher))
                        .append(criteriaShort(userId, a))
                        .append("</blockquote>\n");
            }

            sb.append("\n<i>").append(tr(userId,
                    "Для выбора задания 👇",
                    "Topshiriqni tanlash uchun 👇",
                    "Топшириқни танлаш учун 👇",
                    "To choose an assignment 👇")).append("</i>");

            List<List<InlineKeyboardButton>> allRows = new ArrayList<>();
            allRows.add(List.of(inlineBtn(
                    tr(userId, "⏰ Выбрать по дедлайну", "⏰ Deadline bo'yicha tanlash", "⏰ Дедлайн бўйича танлаш", "⏰ Choose by deadline"),
                    "act_deadline_list_" + courseId)));
            allRows.add(List.of(inlineBtn(t(userId, "btn.back"), "select_activities")));
            send(chatId, sb.toString(), markup(allRows));
        });
    }

    private void editActivitiesList(long chatId, long userId, int messageId, int courseId, String filter) {
        if (!checkLogin(chatId, userId)) return;
        List<Activity> acts = userActivities
                .getOrDefault(userId, Collections.emptyMap()).get(courseId);
        if (acts == null || acts.isEmpty()) {
            edit(chatId, messageId, t(userId, "act.none"), null);
            return;
        }

        long now = System.currentTimeMillis();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        String title = switch (filter) {
            case "uploaded"   -> tr(userId, "📥 <b>Загруженные</b>", "📥 <b>Yuklangan</b>", "📥 <b>Юкланган</b>", "📥 <b>Uploaded</b>");
            case "uploadable" -> tr(userId, "⬆️ <b>Загрузить</b>", "⬆️ <b>Yuklash</b>", "⬆️ <b>Юклаш</b>", "⬆️ <b>Upload</b>");
            default           -> tr(userId, "📋 <b>Задания</b>", "📋 <b>Topshiriqlar</b>", "📋 <b>Топшириқлар</b>", "📋 <b>Assignments</b>");
        };

        for (int i = 0; i < acts.size(); i++) {
            Activity a = acts.get(i);
            boolean hasUpload = a.getUploadedFileUrl() != null && !a.getUploadedFileUrl().isBlank();
            boolean hasActId  = a.getActivityId() != null && !a.getActivityId().isBlank();
            long dl = parseActivityDeadlineTs(a.getDeadline());
            boolean deadlineOk = dl > 0 && dl > now;

            if ("uploaded".equals(filter) && !hasUpload) continue;
            if ("uploadable".equals(filter) && (hasUpload || !hasActId || !deadlineOk)) continue;

            String earned = orDash(a.getEarnedScore());
            String max    = orDash(a.getMaxScore());
            String datePart = (a.getDeadline() != null && a.getDeadline().length() >= 10) ? a.getDeadline().substring(0, 10) : "—";
            String btnText = "📝 " + truncateTopic(a.getTask(), 22) + " | " + datePart + " | " + earned + "/" + max;
            rows.add(List.of(inlineBtn(btnText, "act_pick_" + courseId + "_" + i)));
        }

        if (rows.isEmpty()) {
            String empty = "uploadable".equals(filter)
                    ? tr(userId, "📭 Нет заданий для загрузки (все загружены или срок истёк).", "📭 Yuklash uchun topshiriqlar yo'q.", "📭 Юклаш учун топшириқлар йўқ.", "📭 Nothing to upload (everything is uploaded or the deadline has passed).")
                    : t(userId, "act.none");
            edit(chatId, messageId, empty, null);
            return;
        }

        List<InlineKeyboardButton> filterRow = new ArrayList<>();
        filterRow.add(inlineBtn(tr(userId, "📋 Задания", "📋 Topshiriqlar", "📋 Топшириқлар", "📋 Assignments"), "act_filter_all_" + courseId));
        filterRow.add(inlineBtn(tr(userId, "📥 Загруженные", "📥 Yuklangan", "📥 Юкланган", "📥 Uploaded"), "act_filter_uploaded_" + courseId));
        filterRow.add(inlineBtn(tr(userId, "⬆️ Загрузить", "⬆️ Yuklash", "⬆️ Юклаш", "⬆️ Upload"), "act_filter_uploadable_" + courseId));
        rows.add(filterRow);

        edit(chatId, messageId, title + "\n\n" + tr(userId, "Выберите 👇", "Tanlang 👇", "Танланг 👇", "Choose 👇"), markup(rows));
    }

    private void editDeadlineList(long chatId, long userId, int messageId, int courseId) {
        if (!checkLogin(chatId, userId)) return;
        List<Activity> acts = userActivities
                .getOrDefault(userId, Collections.emptyMap()).get(courseId);
        if (acts == null || acts.isEmpty()) { edit(chatId, messageId, t(userId, "act.none"), null); return; }

        long now = System.currentTimeMillis();
        List<int[]> indexed = new ArrayList<>();
        for (int i = 0; i < acts.size(); i++) indexed.add(new int[]{i});
        indexed.sort((a, b) -> {
            long dlA = parseActivityDeadlineTs(acts.get(a[0]).getDeadline());
            long dlB = parseActivityDeadlineTs(acts.get(b[0]).getDeadline());
            if (dlA < 0 && dlB < 0) return 0;
            if (dlA < 0) return 1;
            if (dlB < 0) return -1;
            return Long.compare(dlA, dlB);
        });

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (int[] idx : indexed) {
            int i = idx[0];
            Activity a = acts.get(i);
            long dl = parseActivityDeadlineTs(a.getDeadline());
            boolean expired   = dl > 0 && dl <= now;
            boolean hasUpload = a.getUploadedFileUrl() != null && !a.getUploadedFileUrl().isBlank();
            String statusIcon = activityStatusIcon(a, hasUpload, expired);
            String datePart   = (a.getDeadline() != null && a.getDeadline().length() >= 10) ? a.getDeadline().substring(0, 10) : "—";
            String earned     = orDash(a.getEarnedScore());
            String max        = orDash(a.getMaxScore());
            String typeIcon   = isLecture(a.getType()) ? "📖" : "🔬";
            String btnText    = statusIcon + " " + typeIcon + " " + datePart + " | " + truncateTopic(a.getTask(), 22) + " | " + earned + "/" + max;
            rows.add(List.of(inlineBtn(btnText, "act_pick_" + courseId + "_" + i)));
        }
        rows.add(List.of(inlineBtn(t(userId, "btn.back"), "ac_back_" + courseId)));

        String header = tr(userId,
                "⏰ <b>Задания по дедлайну</b>\n\n✅ Загружено  |  ❌ Истёк  |  📭 Не загружено\n📖 Лекция  |  🔬 Практика\n\nВыберите 👇",
                "⏰ <b>Deadline bo'yicha topshiriqlar</b>\n\n✅ Yuklangan  |  ❌ Muddat o'tgan  |  📭 Yuklanmagan\n📖 Leksiya  |  🔬 Amaliyot\n\nTanlang 👇",
                "⏰ <b>Дедлайн бўйича топшириқлар</b>\n\n✅ Юкланган  |  ❌ Муддат ўтган  |  📭 Юкланмаган\n📖 Маъруза  |  🔬 Амалиёт\n\nТанланг 👇",
                "⏰ <b>Assignments by deadline</b>\n\n✅ Uploaded  |  ❌ Expired  |  📭 Not uploaded\n📖 Lecture  |  🔬 Practice\n\nChoose 👇");
        edit(chatId, messageId, header, markup(rows));
    }

    private void editActivityDetails(long chatId, long userId, int messageId, int courseId, int index) {
        if (!checkLogin(chatId, userId)) return;
        List<Activity> acts = userActivities
                .getOrDefault(userId, Collections.emptyMap()).get(courseId);
        if (acts == null || index < 0 || index >= acts.size()) {
            edit(chatId, messageId, tr(userId,
                    "⚠️ Данные устарели. Откройте активности заново.",
                    "⚠️ Ma'lumot eskirgan. Aktivnostlarni qayta oching.",
                    "⚠️ Маълумот эскирган. Активностларни қайта очинг.",
                    "⚠️ The data is outdated. Open the activities again."), null);
            return;
        }
        Activity a = acts.get(index);

        boolean hasSample  = a.getSampleFileUrl() != null && !a.getSampleFileUrl().isBlank();
        boolean hasUpload  = a.getUploadedFileUrl() != null && !a.getUploadedFileUrl().isBlank();
        boolean hasActId   = a.getActivityId() != null && !a.getActivityId().isBlank();
        long now = System.currentTimeMillis();
        long dl = parseActivityDeadlineTs(a.getDeadline());
        boolean deadlineOk = dl > 0 && dl > now;

        String earned = orDash(a.getEarnedScore());
        String max    = orDash(a.getMaxScore());

        String text = "📝 <b>" + esc(a.getTask()) + "</b>\n"
                + "👨‍🏫 " + esc(a.getTeacher()) + "\n"
                + "⏰ " + tr(userId, "Дедлайн", "Deadline", "Дедлайн", "Deadline") + ": <b>" + esc(a.getDeadline()) + "</b>\n"
                + "🏆 " + tr(userId, "Балл", "Ball", "Балл", "Score") + ": <b>" + earned + "</b> / <b>" + max + "</b>\n"
                + (hasUpload
                ? tr(userId, "✅ Загружено", "✅ Yuklangan", "✅ Юкланган", "✅ Uploaded")
                : isGraded(a)
                ? tr(userId, "✅ Оценено", "✅ Baholangan", "✅ Баҳоланган", "✅ Graded")
                : tr(userId, "📭 Не загружено", "📭 Yuklanmagan", "📭 Юкланмаган", "📭 Not uploaded"))
                + criteriaLine(userId, a);

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();
        if (hasSample) row.add(inlineBtn(tr(userId, "📄 Задание", "📄 Topshiriq", "📄 Топшириқ", "📄 Assignment"), "act_sample_" + courseId + "_" + index));
        if (hasUpload) row.add(inlineBtn(tr(userId, "📥 Моя загрузка", "📥 Yuklanganim", "📥 Юкланганим", "📥 My upload"), "act_uploaded_" + courseId + "_" + index));
        if (!row.isEmpty()) rows.add(row);

        if (!hasUpload && hasActId && deadlineOk)
            rows.add(List.of(inlineBtn(tr(userId, "⬆️ Загрузить", "⬆️ Yuklash", "⬆️ Юклаш", "⬆️ Upload"), "act_upload_" + courseId + "_" + index + "_" + a.getActivityId())));
        if (hasUpload && hasActId && deadlineOk)
            rows.add(List.of(inlineBtn(tr(userId, "🔄 Перезагрузить", "🔄 Qayta yuklash", "🔄 Қайта юклаш", "🔄 Re-upload"), "act_upload_" + courseId + "_" + index + "_" + a.getActivityId())));

        rows.add(List.of(inlineBtn(t(userId, "btn.back"), "act_deadline_list_" + courseId)));
        edit(chatId, messageId, text, markup(rows));
    }

    // ─────────────────────────────────────────────
    //  SCHEDULE
    // ─────────────────────────────────────────────

    void showSchedule(long chatId, long userId, int semesterId) {
        if (!checkLogin(chatId, userId)) return;
        if (!checkSemester(chatId, userId, semesterId)) return;
        sendProgress(chatId, t(userId, "sched.loading"));

        executor.submit(() -> {
            uz.tuit.lmsbot.service.SemesterSchedule.Result sched = semesterSchedule.get(userId, semesterId, false);
            if (sched == null || sched.lessons().isEmpty()) {
                send(chatId, t(userId, "sched.empty"), semesterScheduleKeyboard(userId));
                return;
            }
            rememberSemester(userId, semesterId);
            int week = sched.weekOf(java.time.LocalDate.now(AppConfig.LMS_ZONE));
            send(chatId, scheduleWeekText(userId, semesterId, sched, week), scheduleWeekKeyboard(userId, semesterId, sched, week));
        });
    }

    private void editScheduleWeek(long chatId, long userId, int messageId, int semesterId, int week) {
        if (!checkLogin(chatId, userId)) return;
        executor.submit(() -> {
            uz.tuit.lmsbot.service.SemesterSchedule.Result sched = semesterSchedule.get(userId, semesterId, false);
            if (sched == null || sched.lessons().isEmpty()) {
                edit(chatId, messageId, t(userId, "sched.empty"), semesterScheduleKeyboard(userId));
                return;
            }
            int w = Math.max(0, Math.min(week, sched.weekCount() - 1));
            edit(chatId, messageId, scheduleWeekText(userId, semesterId, sched, w), scheduleWeekKeyboard(userId, semesterId, sched, w));
        });
    }

    String[] dayNames(long userId) {
        return switch (lang(userId)) {
            case "ru"     -> new String[]{"","Понедельник","Вторник","Среда","Четверг","Пятница","Суббота","Воскресенье"};
            case "uz_cyr" -> new String[]{"","Душанба","Сешанба","Чоршанба","Пайшанба","Жума","Шанба","Якшанба"};
            case "en"     -> new String[]{"","Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday"};
            default       -> new String[]{"","Dushanba","Seshanba","Chorshanba","Payshanba","Juma","Shanba","Yakshanba"};
        };
    }

    String kindLabel(long userId, String kind) {
        return switch (kind) {
            case uz.tuit.lmsbot.service.SemesterSchedule.PRACTICE -> tr(userId, "Практика", "Amaliyot", "Амалиёт", "Practice");
            case uz.tuit.lmsbot.service.SemesterSchedule.LAB -> tr(userId, "Лабораторная", "Laboratoriya", "Лаборатория", "Lab");
            default -> tr(userId, "Лекция", "Ma'ruza", "Маъруза", "Lecture");
        };
    }

    private String kindIcon(String kind) {
        return uz.tuit.lmsbot.service.SemesterSchedule.LECTURE.equals(kind) ? "📖 " : "🔬 ";
    }

    /** Одна неделя расписания; неделя — страница пагинации до конца семестра. */
    private String scheduleWeekText(long userId, int semesterId,
                                    uz.tuit.lmsbot.service.SemesterSchedule.Result sched, int week) {
        java.time.LocalDate from = sched.weekStart(week);
        java.time.LocalDate to = from.plusDays(6);
        java.time.LocalDate today = java.time.LocalDate.now(AppConfig.LMS_ZONE);
        DateTimeFormatter dm = DateTimeFormatter.ofPattern("dd.MM");
        String[] days = dayNames(userId);

        StringBuilder sb = new StringBuilder();
        sb.append("📅 <b>").append(esc(lmsService.semesterName(userId, semesterId))).append("</b>\n")
                .append(tr(userId, "Неделя ", "Hafta ", "Ҳафта ", "Week")).append(week + 1).append(" / ").append(sched.weekCount())
                .append(" · <i>").append(from.format(dm)).append(" – ").append(to.format(dm)).append("</i>\n\n");

        boolean any = false;
        for (java.time.LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            List<uz.tuit.lmsbot.service.SemesterSchedule.Lesson> lessons = sched.on(d);
            if (lessons.isEmpty()) continue;
            any = true;
            StringBuilder inner = new StringBuilder();
            inner.append(d.equals(today) ? "📍 " : "").append("<b>").append(esc(days[d.getDayOfWeek().getValue()].toUpperCase()))
                    .append("</b> — <i>").append(d.format(dm)).append("</i>");
            if (d.equals(today)) inner.append(" · ").append(tr(userId, "сегодня", "bugun", "бугун", "today"));
            inner.append("\n");
            for (uz.tuit.lmsbot.service.SemesterSchedule.Lesson l : lessons) {
                inner.append("\n⏰ <b>").append(esc(l.time())).append("</b>");
                if (!l.room().isBlank()) inner.append(" | 🚪 ").append(esc(l.room()));
                inner.append("\n").append(kindIcon(l.kind()))
                        .append(esc(l.subject())).append(" · <i>").append(esc(kindLabel(userId, l.kind()))).append("</i>");
                if (l.stream() != null) inner.append(" · ").append(esc(l.stream()));
                if (!l.teacher().isBlank()) inner.append("\n👨‍🏫 ").append(esc(l.teacher()));
                if (l.topic() != null && !l.topic().isBlank()) inner.append("\n📝 <i>").append(esc(truncateTopic(l.topic(), 80))).append("</i>");
                if (l.last()) inner.append("\n🏁 ").append(tr(userId, "Последнее занятие", "Oxirgi dars", "Охирги дарс", "Last class"));
                inner.append("\n");
            }
            sb.append("<blockquote>").append(inner.toString().trim()).append("</blockquote>\n");
        }
        if (!any) {
            sb.append("<blockquote>").append(tr(userId, "На этой неделе пар нет.", "Bu haftada dars yo'q.", "Бу ҳафтада дарс йўқ.", "No classes this week.")).append("</blockquote>");
        }
        return sb.toString();
    }

    private InlineKeyboardMarkup scheduleWeekKeyboard(long userId, int semesterId,
                                                      uz.tuit.lmsbot.service.SemesterSchedule.Result sched, int week) {
        int total = sched.weekCount();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> nav = new ArrayList<>();
        nav.add(inlineBtn("◀️", week > 0 ? "sw_" + semesterId + "_" + (week - 1) : "noop"));
        nav.add(inlineBtn((week + 1) + " / " + total, "noop"));
        nav.add(inlineBtn("▶️", week < total - 1 ? "sw_" + semesterId + "_" + (week + 1) : "noop"));
        rows.add(nav);
        int current = sched.weekOf(java.time.LocalDate.now(AppConfig.LMS_ZONE));
        List<InlineKeyboardButton> extra = new ArrayList<>();
        if (week != current) extra.add(inlineBtn(tr(userId, "📍 Эта неделя", "📍 Shu hafta", "📍 Шу ҳафта", "📍 This week"), "sw_" + semesterId + "_" + current));
        extra.add(inlineBtn(tr(userId, "📅 Семестр", "📅 Semestr", "📅 Семестр", "📅 Semester"), "sched_sem"));
        rows.add(extra);
        return markup(rows);
    }

    // ─────────────────────────────────────────────
    //  STUDY PLAN
    // ─────────────────────────────────────────────

    private void showStudyPlanFull(long chatId, long userId) {
        if (!checkLogin(chatId, userId)) return;
        sendProgress(chatId, t(userId, "plan.loading"));

        executor.submit(() -> {
            List<StudyPlanSubject> subjects = lmsService.getStudyPlan(userId);
            if (subjects.isEmpty()) { send(chatId, t(userId, "common.no_data"), backOnly(userId)); return; }

            Map<Integer, List<StudyPlanSubject>> bySemester = new LinkedHashMap<>();
            for (StudyPlanSubject s : subjects)
                bySemester.computeIfAbsent(s.getSemester(), k -> new ArrayList<>()).add(s);

            int currentSemester = detectCurrentSemester(bySemester);
            String[] roman = {"","I","II","III","IV","V","VI","VII","VIII"};
            String[] semColors = {"🟡","🟢","🔵","🟣","🟠","🔴","⚪","🟤"};

            StringBuilder planMsg = new StringBuilder();
            planMsg.append("📖 <b>").append(t(userId, "plan.title")).append("</b>\n\n");

            for (Map.Entry<Integer, List<StudyPlanSubject>> entry : bySemester.entrySet()) {
                int sem = entry.getKey();
                boolean isCurrent = sem == currentSemester;
                String semLabel = sem < roman.length ? roman[sem] : String.valueOf(sem);
                String dot = isCurrent ? "📌" : semColors[(sem - 1) % semColors.length];

                StringBuilder block = new StringBuilder();
                block.append(dot).append(" <b>").append(semLabel).append("-")
                        .append(t(userId, "plan.semester_suffix")).append("</b>");
                if (isCurrent) block.append(" <i>(").append(t(userId, "plan.current")).append(")</i>");
                block.append("\n");
                for (StudyPlanSubject s : entry.getValue()) {
                    String gradeStr = s.getGrade() != null
                            ? gradeIcon(s.getGrade()) + " <b>" + s.getGrade() + "</b>" : "";
                    block.append(esc(s.getName()))
                            .append(" <i>(").append(s.getCredits()).append(" ")
                            .append(t(userId, "plan.credits")).append(")</i>");
                    if (!gradeStr.isEmpty()) block.append(" — ").append(gradeStr);
                    block.append("\n");
                }
                planMsg.append("<blockquote>").append(block.toString().trim()).append("</blockquote>\n");
            }

            planMsg.append("\n<blockquote>")
                    .append(tr(userId,
                            "🟢 Отлично (5)  🔵 Хорошо (4)  🟡 Удовлетворительно (3)  🔴 Неудовлетворительно (2)",
                            "🟢 A'lo (5)  🔵 Yaxshi (4)  🟡 Qoniqarli (3)  🔴 Qoniqarsiz (2)",
                            "🟢 Аъло (5)  🔵 Яхши (4)  🟡 Қониқарли (3)  🔴 Қониқарсиз (2)",
                            "🟢 Excellent (5)  🔵 Good (4)  🟡 Satisfactory (3)  🔴 Unsatisfactory (2)"))
                    .append("</blockquote>\n");
            planMsg.append("\n💡 ").append(t(userId, "plan.gpa_hint"));
            send(chatId, planMsg.toString(), withBack(userId, gpaChoiceKeyboard(userId)));
        });
    }

    private void showStudyPlanGpa(long chatId, long userId, int messageId, boolean includeCurrentWithZero) {
        edit(chatId, messageId, t(userId, "plan.gpa_loading"), null);

        executor.submit(() -> {
            List<StudyPlanSubject> subjects = lmsService.getStudyPlan(userId);
            if (subjects.isEmpty()) { edit(chatId, messageId, t(userId, "common.no_data"), null); return; }

            Map<Integer, List<StudyPlanSubject>> bySemester = new LinkedHashMap<>();
            for (StudyPlanSubject s : subjects)
                bySemester.computeIfAbsent(s.getSemester(), k -> new ArrayList<>()).add(s);

            int currentSemester = detectCurrentSemester(bySemester);
            long totalCredits = 0, totalPoints = 0;
            String mode = includeCurrentWithZero
                    ? t(userId, "plan.gpa_mode_with_zero")
                    : t(userId, "plan.gpa_mode_graded");

            for (Map.Entry<Integer, List<StudyPlanSubject>> entry : bySemester.entrySet()) {
                boolean isCurrent = entry.getKey() == currentSemester;
                for (StudyPlanSubject s : entry.getValue()) {
                    int gradeForCalc;
                    if (s.getGrade() != null) gradeForCalc = s.getGrade();
                    else if (isCurrent && includeCurrentWithZero) gradeForCalc = 0;
                    else continue;
                    totalCredits += s.getCredits();
                    totalPoints  += (long) gradeForCalc * s.getCredits();
                }
            }

            StringBuilder gpaMsg = new StringBuilder();
            gpaMsg.append("📊 <b>GPA — ").append(esc(mode)).append("</b>\n\n");
            if (totalCredits > 0) {
                double gpa = (double) totalPoints / totalCredits;
                gpaMsg.append("<blockquote>")
                        .append("🎓 <b>GPA: ").append(String.format("%.2f", gpa)).append("</b>\n")
                        .append("📚 ").append(t(userId, "plan.calc_credits")).append(": <b>").append(totalCredits).append("</b>\n")
                        .append("✨ ").append(esc(gpaComment(userId, gpa)))
                        .append("</blockquote>");
            } else {
                gpaMsg.append("<blockquote>⚠️ ").append(t(userId, "plan.gpa_no_grades")).append("</blockquote>");
            }

            InlineKeyboardMarkup markup = markup(List.of(
                    List.of(inlineBtn(t(userId, "btn.back_plan"), "back_to_study_plan"))
            ));
            edit(chatId, messageId, gpaMsg.toString(), markup);
        });
    }

    // ─────────────────────────────────────────────
    //  CALENDAR (edit in-place)
    // ─────────────────────────────────────────────

    private void showCalendar(long chatId, long userId, int messageId, Course course) {
        if (!checkLogin(chatId, userId)) return;
        edit(chatId, messageId, t(userId, "cal.loading"), null);

        executor.submit(() -> {
            Map<String, List<CalendarEntry>> calendar = lmsService.getCalendar(userId, course.getId());
            if (calendar.isEmpty()) { edit(chatId, messageId, t(userId, "cal.no_data"), null); return; }

            userCalendars.computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                    .put(course.getId(), calendar);

            boolean hasL = calendar.containsKey("lecture");
            boolean hasP = calendar.containsKey("practice");

            if (hasL && !hasP) {
                buildAndEditCalendarPage(chatId, userId, messageId, course.getId(), "lecture", 0);
            } else if (!hasL && hasP) {
                buildAndEditCalendarPage(chatId, userId, messageId, course.getId(), "practice", 0);
            } else {
                int cid = course.getId();
                edit(chatId, messageId,
                        "📆 <b>" + esc(course.getSubject()) + "</b>\n\n" + t(userId, "cal.choose_type"),
                        markup(List.of(List.of(
                                inlineBtn(tr(userId, "📖 Лекция", "📖 Lektsiya", "📖 Маъруза", "📖 Lecture"), "caltype_" + cid + "_lecture"),
                                inlineBtn(tr(userId, "🔬 Практика", "🔬 Amaliyot", "🔬 Амалиёт", "🔬 Practice"), "caltype_" + cid + "_practice")
                        ))));
            }
        });
    }

    private void editCalendarPage(long chatId, long userId, int messageId, int courseId, String type, int page) {
        Map<String, List<CalendarEntry>> calendar = userCalendars
                .getOrDefault(userId, Collections.emptyMap()).get(courseId);
        if (calendar == null || calendar.isEmpty()) {
            edit(chatId, messageId, t(userId, "common.no_data"), null);
            return;
        }
        buildAndEditCalendarPage(chatId, userId, messageId, courseId, type, page);
    }

    private void buildAndEditCalendarPage(long chatId, long userId, int messageId, int courseId, String type, int page) {
        Map<String, List<CalendarEntry>> calendar = userCalendars
                .getOrDefault(userId, Collections.emptyMap()).get(courseId);
        List<CalendarEntry> entries = calendar != null ? calendar.get(type) : null;
        if (entries == null || entries.isEmpty()) {
            edit(chatId, messageId, t(userId, "common.no_data"), null);
            return;
        }

        int pageSize = 5;
        int total    = entries.size();
        int maxPage  = (total - 1) / pageSize;
        if (page < 0) page = 0;
        if (page > maxPage) page = maxPage;

        int from = page * pageSize;
        int to   = Math.min(from + pageSize, total);

        String subject = "";
        List<Course> courses = userCourses.get(userId);
        if (courses != null)
            for (Course c : courses)
                if (c.getId() == courseId) { subject = c.getSubject(); break; }

        String typeName = "lecture".equals(type)
                ? tr(userId, "Лекция", "Lektsiya", "Маъруза", "Lecture")
                : tr(userId, "Практика", "Amaliyot", "Амалиёт", "Practice");

        StringBuilder sb = new StringBuilder();
        sb.append("📆 <b>").append(esc(subject)).append("</b>\n");
        sb.append("<i>").append(esc(typeName)).append(" ").append(t(userId, "cal.plan_label")).append("</i>\n");
        sb.append(t(userId, "common.page")).append(" ").append(page + 1).append(" / ").append(maxPage + 1).append("\n\n");

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (int i = from; i < to; i++) {
            CalendarEntry e = entries.get(i);
            String text = e.getNumber() + ". " + truncateTopic(e.getTopic(), 40) + " (" + e.getDate() + ")";
            rows.add(List.of(inlineBtn(text, "calentry_" + courseId + "_" + type + "_" + i)));
        }

        List<InlineKeyboardButton> navRow = new ArrayList<>();
        if (page > 0)      navRow.add(inlineBtn(t(userId, "btn.prev"), "calpage_" + courseId + "_" + type + "_" + (page - 1)));
        if (page < maxPage) navRow.add(inlineBtn(t(userId, "btn.next"), "calpage_" + courseId + "_" + type + "_" + (page + 1)));
        if (!navRow.isEmpty()) rows.add(navRow);

        rows.add(List.of(inlineBtn(t(userId, "btn.back"), "select_calendar")));

        edit(chatId, messageId, sb.toString(), markup(rows));
    }

    private void editCalendarEntry(long chatId, long userId, int messageId, int courseId, String type, int index) {
        Map<String, List<CalendarEntry>> calendar = userCalendars
                .getOrDefault(userId, Collections.emptyMap()).get(courseId);
        if (calendar == null) { edit(chatId, messageId, t(userId, "common.no_data"), null); return; }
        List<CalendarEntry> entries = calendar.get(type);
        if (entries == null || index < 0 || index >= entries.size()) {
            edit(chatId, messageId, t(userId, "common.no_data"), null);
            return;
        }
        CalendarEntry e = entries.get(index);

        String subject = "";
        List<Course> courses = userCourses.get(userId);
        if (courses != null)
            for (Course c : courses)
                if (c.getId() == courseId) { subject = c.getSubject(); break; }
        String typeName = "lecture".equals(type)
                ? tr(userId, "Лекция", "Lektsiya", "Маъруза", "Lecture")
                : tr(userId, "Практика", "Amaliyot", "Амалиёт", "Practice");

        StringBuilder sb = new StringBuilder();
        sb.append("📆 <b>").append(esc(subject)).append("</b>\n");
        sb.append("<i>").append(esc(typeName)).append("</i>\n\n");
        sb.append("<b>").append(e.getNumber()).append(".</b> ").append(esc(e.getTopic())).append("\n");
        sb.append("📅 <i>").append(esc(e.getDate())).append("</i>");

        edit(chatId, messageId, sb.toString(), markup(List.of(
                List.of(inlineBtn(t(userId, "btn.files"), "calfiles_" + courseId + "_" + type + "_" + index)),
                List.of(inlineBtn(t(userId, "btn.back"), "calback_" + courseId + "_" + type + "_" + index))
        )));
    }

    private void showCalendarFiles(long chatId, long userId, int messageId, int courseId, String type, int index) {
        Map<String, List<CalendarEntry>> calendar = userCalendars
                .getOrDefault(userId, Collections.emptyMap()).get(courseId);
        if (calendar == null) { edit(chatId, messageId, t(userId, "common.no_data"), null); return; }
        List<CalendarEntry> entries = calendar.get(type);
        if (entries == null || index < 0 || index >= entries.size()) {
            edit(chatId, messageId, t(userId, "common.no_data"), null);
            return;
        }
        CalendarEntry e = entries.get(index);
        List<CalendarEntry.FileAttachment> files = e.getFiles();
        if (files == null || files.isEmpty()) {
            edit(chatId, messageId, t(userId, "cal.files_empty"), markup(List.of(
                    List.of(inlineBtn(t(userId, "btn.back"), "calentry_" + courseId + "_" + type + "_" + index))
            )));
            return;
        }

        pendingFiles.remove(userId);
        Map<String, CalendarEntry.FileAttachment> userMap =
                pendingFiles.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());

        StringBuilder sb = new StringBuilder();
        sb.append(t(userId, "cal.files_title")).append("\n");
        sb.append("<b>").append(e.getNumber()).append(".</b> ").append(esc(e.getTopic())).append("\n");
        sb.append("📅 <i>").append(esc(e.getDate())).append("</i>\n\n");

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (CalendarEntry.FileAttachment f : files) {
            String icon = switch (f.getType()) {
                case "pdf"   -> "📄"; case "ppt" -> "📊"; case "doc" -> "📝";
                case "video" -> "🎬"; case "url" -> "🔗"; default    -> "📎";
            };
            String btnText = icon + " " + truncateTopic(f.getName(), 40);
            if ("url".equals(f.getType())) {
                InlineKeyboardButton btn = new InlineKeyboardButton();
                btn.setText(btnText);
                btn.setUrl(f.getUrl());
                rows.add(List.of(btn));
            } else {
                String token = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
                userMap.put(token, f);
                rows.add(List.of(inlineBtn(btnText, "file_" + token)));
            }
        }
        rows.add(List.of(inlineBtn(t(userId, "btn.back"), "calentry_" + courseId + "_" + type + "_" + index)));
        edit(chatId, messageId, sb.toString(), markup(rows));
    }

    // ─────────────────────────────────────────────
    //  FINALS
    // ─────────────────────────────────────────────

    private void showFinals(long chatId, long userId, int semesterId) {
        if (!checkLogin(chatId, userId)) return;
        if (!checkSemester(chatId, userId, semesterId)) return;
        sendProgress(chatId, t(userId, "finals.loading"));

        executor.submit(() -> {
            List<FinalExam> exams = lmsService.getFinals(userId, semesterId);
            String semName = lmsService.semesterName(userId, semesterId);

            if (exams.isEmpty()) {
                send(chatId, t(userId, "finals.empty"), finalsSemesterKeyboard(userId));
                return;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("🏆 <b>").append(t(userId, "finals.title")).append("</b>\n");
            sb.append("<i>").append(esc(semName)).append("</i>\n\n");

            String[] examColors = {"🟡","🟢","🔵","🟣","🟠","🔴","⚪","🟤"};
            for (int ei = 0; ei < exams.size(); ei++) {
                FinalExam ex = exams.get(ei);
                String gradeIcon = "⬜";
                try {
                    double g = Double.parseDouble(ex.getGrade());
                    if (g >= 86) gradeIcon = "🟢";
                    else if (g >= 71) gradeIcon = "🔵";
                    else if (g >= 56) gradeIcon = "🟡";
                    else gradeIcon = "🔴";
                } catch (Exception ignored) {}

                String dot = examColors[ei % examColors.length];
                StringBuilder exBlock = new StringBuilder();
                exBlock.append(dot).append(" <b>").append(esc(ex.getSubject())).append("</b>\n");
                if (ex.getStream() != null && !ex.getStream().isBlank())
                    exBlock.append("🔢 ").append(tr(userId, "Поток", "Oqim", "Оқим", "Stream")).append(": ").append(esc(ex.getStream())).append("\n");
                if (ex.getDate() != null && !ex.getDate().isBlank()) {
                    exBlock.append("📅 <b>").append(esc(ex.getDate())).append("</b>");
                    if (ex.getFrom() != null && !ex.getFrom().isBlank())
                        exBlock.append(" 🕐 <b>").append(esc(ex.getFrom())).append("</b>");
                    exBlock.append("\n");
                }
                if (ex.getRoom() != null && !ex.getRoom().isBlank())
                    exBlock.append("🚪 <b>").append(esc(ex.getRoom())).append("</b>\n");
                exBlock.append(gradeIcon).append(" ").append(tr(userId, "Балл", "Ball", "Балл", "Score"))
                        .append(": <b>").append(esc(ex.getGrade())).append("</b>");
                sb.append("<blockquote>").append(exBlock).append("</blockquote>\n");
            }

            send(chatId, sb.toString(), finalsSemesterKeyboard(userId));
        });
    }

    // ─────────────────────────────────────────────
    //  PROFILE
    // ─────────────────────────────────────────────

    private void showProfile(long chatId, long userId) {
        if (!checkLogin(chatId, userId)) return;
        sendProgress(chatId, tr(userId, "⏳ Профиль загружается...", "⏳ Profil yuklanmoqda...", "⏳ Профил юкланмоқда...", "⏳ Loading profile..."));

        executor.submit(() -> {
            StudentInfo info = lmsService.getStudentInfo(userId);
            if (info == null) { send(chatId, t(userId, "common.no_data"), backOnly(userId)); return; }

            StringBuilder sb = new StringBuilder();
            sb.append(tr(userId, "👤 <b>Профиль</b>", "👤 <b>Profil</b>", "👤 <b>Профил</b>", "👤 <b>Profile</b>")).append("\n");
            sb.append("┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄\n\n");

            String sid = info.getRecordBook() != null && !info.getRecordBook().isBlank()
                    ? info.getRecordBook() : "—";
            sb.append("<blockquote>")
                    .append("🆔 ").append(tr(userId, "Студенческий №", "Talaba raqami", "Талаба рақами", "Student ID"))
                    .append(": <b>").append(esc(sid)).append("</b>")
                    .append("</blockquote>\n\n");

            StringBuilder p1 = new StringBuilder();
            row(p1, "👤 " + tr(userId, "Ф.И.О", "F.I.O", "Ф.И.О", "Full name"), info.getFullName());
            row(p1, "🎂 " + tr(userId, "Дата рождения", "Tug'ilgan kun", "Туғилган кун", "Date of birth"), info.getBirthDate());
            row(p1, "⚧ " + tr(userId, "Пол", "Jinsi", "Жинси", "Gender"), info.getGender());
            row(p1, "🏠 " + tr(userId, "Адрес", "Manzil", "Манзил", "Address"), info.getAddress());
            sb.append("📌 <b>").append(tr(userId, "Личные данные", "Shaxsiy ma'lumotlar", "Шахсий маълумотлар", "Personal details")).append("</b>\n")
                    .append("<blockquote>").append(p1.toString().trim()).append("</blockquote>\n\n");

            StringBuilder p2 = new StringBuilder();
            row(p2, "🎓 " + tr(userId, "Направление", "Yo'nalish", "Йўналиш", "Field of study"), info.getDirection());
            row(p2, "🌐 " + tr(userId, "Язык обучения", "O'qish tili", "Ўқиш тили", "Language of study"), info.getLanguage());
            row(p2, "📜 " + tr(userId, "Степень", "Daraja", "Даража", "Degree"), info.getDegree());
            row(p2, "🏫 " + tr(userId, "Тип обучения", "O'qish turi", "Ўқиш тури", "Form of study"), info.getStudyType());
            row(p2, "📅 " + tr(userId, "Курс", "Kurs", "Курс", "Year"), info.getCourse());
            row(p2, "👥 " + tr(userId, "Группа", "Guruh", "Гуруҳ", "Group"), info.getGroup());
            row(p2, "👨‍🏫 " + tr(userId, "Куратор", "Kurator", "Куратор", "Curator"), info.getCurator());
            row(p2, "💰 " + tr(userId, "Стипендия", "Stipendiya", "Стипендия", "Scholarship"), info.getScholarship());
            sb.append("📚 <b>").append(tr(userId, "Учебные данные", "O'quv ma'lumotlari", "Ўқув маълумотлари", "Study details")).append("</b>\n")
                    .append("<blockquote>").append(p2.toString().trim()).append("</blockquote>");

            InlineKeyboardMarkup kb = markup(List.of(
                    List.of(inlineBtn(tr(userId, "🖼 Фотография", "🖼 Foto", "🖼 Фото", "🖼 Photo"), "profile_photo"))
            ));
            send(chatId, sb.toString(), withBack(userId, kb));
        });
    }

    private void row(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank())
            sb.append(label).append(": <b>").append(esc(value)).append("</b>\n");
    }

    void showProfilePhoto(long chatId, long userId) {
        if (!checkLogin(chatId, userId)) return;
        sendProgress(chatId, tr(userId, "⏳ Загружаю фото...", "⏳ Foto yuklanmoqda...", "⏳ Фото юкланмоқда...", "⏳ Loading photo..."));
        executor.submit(() -> {
            File tmp = null;
            try {
                String dataUrl = lmsService.getProfilePhotoDataUrl(userId);
                if (dataUrl == null || !dataUrl.startsWith("data:image")) {
                    send(chatId, tr(userId, "📭 <b>Фото не найдено</b>", "📭 <b>Foto topilmadi</b>", "📭 <b>Фото топилмади</b>", "📭 <b>Photo not found</b>"), null);
                    return;
                }
                int comma = dataUrl.indexOf(',');
                String meta = comma > 0 ? dataUrl.substring(0, comma) : "";
                String b64  = comma > 0 ? dataUrl.substring(comma + 1) : "";
                String ext  = meta.contains("png") ? ".png" : ".jpg";
                byte[] bytes = java.util.Base64.getDecoder().decode(b64);
                tmp = File.createTempFile("lms_avatar_", ext);
                Files.write(tmp.toPath(), bytes);
                dropProgress(chatId);
                SendPhoto p = new SendPhoto();
                p.setChatId(String.valueOf(chatId));
                p.setPhoto(new InputFile(tmp, "profile" + ext));
                execute(p);
            } catch (Exception e) {
                send(chatId, "❌ " + esc(e.getMessage()), null);
            } finally {
                if (tmp != null) try { tmp.delete(); } catch (Exception ignored) {}
            }
        });
    }

    private void startChangePassword(long chatId, long userId) {
        userState.put(userId, "WAIT_OLD_PASSWORD");
        tmpOldPass.remove(userId);
        tmpNewPass.remove(userId);
        send(chatId, tr(userId,
                "🔒 <b>Сменить пароль</b>\n\n<blockquote>Введите <b>старый пароль</b>:</blockquote>",
                "🔒 <b>Parolni o'zgartirish</b>\n\n<blockquote><b>Eski parol</b>ni kiriting:</blockquote>",
                "🔒 <b>Паролни ўзгартириш</b>\n\n<blockquote><b>Эски парол</b>ни киритинг:</blockquote>",
                "🔒 <b>Change password</b>\n\n<blockquote>Enter your <b>old password</b>:</blockquote>"), null);
    }

    // ─────────────────────────────────────────────
    //  SETTINGS
    // ─────────────────────────────────────────────

    private void showSettings(long chatId, long userId) {
        String currentLang = lang(userId);
        String title = tr(userId, "⚙️ <b>Настройки</b>", "⚙️ <b>Sozlamalar</b>", "⚙️ <b>Созламалар</b>", "⚙️ <b>Settings</b>");
        String body = "<blockquote>"
                + tr(userId, "🌐 Язык", "🌐 Til", "🌐 Тил", "🌐 Language") + ": <b>" + esc(langLabel(currentLang)) + "</b>"
                + "</blockquote>";

        InlineKeyboardMarkup kb = markup(List.of(
                List.of(inlineBtn(tr(userId, "🌐 Сменить язык", "🌐 Tilni o'zgartirish", "🌐 Тилни ўзгартириш", "🌐 Change language"), "settings_lang")),
                List.of(inlineBtn(tr(userId, "🔒 Сменить пароль", "🔒 Parolni o'zgartirish", "🔒 Паролни ўзгартириш", "🔒 Change password"), "settings_password")),
                List.of(inlineBtn(tr(userId, "🚪 Выйти из аккаунта", "🚪 Akkauntdan chiqish", "🚪 Акkauntdan chiqish", "🚪 Sign out"), "settings_logout"))
        ));
        send(chatId, title + "\n\n" + body, kb);
    }

    // ─────────────────────────────────────────────
    //  DEADLINES
    // ─────────────────────────────────────────────

    private void showDeadlinesList(long chatId, long userId) {
        if (!checkLogin(chatId, userId)) return;
        sendProgress(chatId, tr(userId,
                "⏳ <b>Собираю дедлайны...</b>",
                "⏳ <b>Deadlinelar yig'ilmoqda...</b>",
                "⏳ <b>Дедлайнлар йиғилмоқда...</b>",
                "⏳ <b>Collecting deadlines...</b>"));

        executor.submit(() -> {
            List<Course> courses = userCourses.get(userId);
            int semesterId = userSemester.getOrDefault(userId, getDefaultSemesterId(userId));
            if ((courses == null || courses.isEmpty()) && semesterId <= 0) {
                send(chatId, t(userId, "sem.unavailable"), null);
                return;
            }
            if (courses == null || courses.isEmpty()) {
                courses = lmsService.getMyCourses(userId, semesterId);
                if (courses != null && !courses.isEmpty()) {
                    userCourses.put(userId, courses);
                    rememberSemester(userId, semesterId);
                }
            }
            if (courses == null || courses.isEmpty()) {
                send(chatId, tr(userId,
                        "📭 <b>Список дедлайнов</b>\n\n<blockquote>Сначала откройте «Мои предметы».</blockquote>",
                        "📭 <b>Deadline ro'yxati</b>\n\n<blockquote>Avval «Mening fanlarim» bo'limini oching.</blockquote>",
                        "📭 <b>Дедлайнлар рўйхати</b>\n\n<blockquote>Аввал «Менинг фанларим» бўлимига киринг.</blockquote>",
                        "📭 <b>Deadlines</b>\n\n<blockquote>Open «My subjects» first.</blockquote>"), null);
                return;
            }

            long now = System.currentTimeMillis();
            List<DeadlineItem> allItems = new ArrayList<>();
            List<DeadlineItem> items    = new ArrayList<>();

            // Предметы опрашиваем параллельно — последовательно это занимало десятки секунд
            List<java.util.concurrent.Future<List<DeadlineItem>>> futures = new ArrayList<>();
            for (Course c : courses) {
                futures.add(executor.submit(() -> {
                    List<DeadlineItem> out = new ArrayList<>();
                    CourseSummary cs = lmsService.getActivities(userId, c.getId());
                    if (cs == null || cs.getActivities() == null) return out;
                    for (Activity a : cs.getActivities()) {
                        long dl = parseActivityDeadlineTs(a.getDeadline());
                        if (dl <= 0 || dl < now) continue;
                        DeadlineItem it = new DeadlineItem();
                        it.course = c.getSubject(); it.courseId = c.getId(); it.act = a; it.dl = dl;
                        out.add(it);
                    }
                    return out;
                }));
            }
            for (java.util.concurrent.Future<List<DeadlineItem>> f : futures) {
                try {
                    for (DeadlineItem it : f.get()) {
                        allItems.add(it);
                        boolean hasUpload = it.act.getUploadedFileUrl() != null && !it.act.getUploadedFileUrl().isBlank();
                        if (!hasUpload) items.add(it);
                    }
                } catch (Exception ignored) {}
            }
            allItems.sort(Comparator.comparingLong(x -> x.dl));
            items.sort(Comparator.comparingLong(x -> x.dl));

            lastDeadlineItems.put(userId, allItems);

            StringBuilder sb = new StringBuilder();
            sb.append(tr(userId, "🗓 <b>Список дедлайнов</b>", "🗓 <b>Deadline ro'yxati</b>", "🗓 <b>Дедлайнлар рўйхати</b>", "🗓 <b>Deadlines</b>"))
                    .append("\n");
            sb.append("<blockquote>")
                    .append(tr(userId, "📭 Только не загруженные задания", "📭 Faqat yuklanmagan topshiriqlar", "📭 Фақат юкланмаган топшириқлар", "📭 Only assignments that are not uploaded"))
                    .append("</blockquote>\n\n");

            if (items.isEmpty()) {
                sb.append("<blockquote>✅ ").append(tr(userId,
                        "Все задания загружены или дедлайнов нет.",
                        "Barcha topshiriqlar yuklangan yoki deadlinelar yo'q.",
                        "Барча топшириқлар юкланган ёки дедлайнлар йўқ.",
                        "Everything is uploaded or there are no deadlines.")).append("</blockquote>");
                send(chatId, sb.toString(), backOnly(userId));
                return;
            }

            List<DeadlineItem> urgent = new ArrayList<>(), d1_3 = new ArrayList<>(), rest = new ArrayList<>();
            for (DeadlineItem it : items) {
                double d = (it.dl - now) / (24.0 * 60 * 60 * 1000);
                if (d <= 1.0) urgent.add(it);
                else if (d <= 3.0) d1_3.add(it);
                else rest.add(it);
            }

            appendDeadlineGroup(sb, userId, tr(userId, "🔥 Остался 1 день", "🔥 1 kun qoldi", "🔥 1 кун қолди", "🔥 1 day left"), urgent, now);
            appendDeadlineGroup(sb, userId, tr(userId, "⏳ Осталось 3 дня", "⏳ 3 kun qoldi", "⏳ 3 кун қолди", "⏳ 3 days left"), d1_3, now);
            // Остальные — по порядку, но не все: только 4 ближайших
            List<DeadlineItem> restTop = rest.size() <= 4 ? rest : rest.subList(0, 4);
            appendDeadlineGroup(sb, userId, tr(userId, "📌 Остальные (ближайшие 4)", "📌 Qolganlari (eng yaqin 4)", "📌 Қолганлари (энг яқин 4)", "📌 Others (next 4)"), restTop, now);

            InlineKeyboardMarkup kb = markup(List.of(List.of(
                    inlineBtn(tr(userId, "📋 Все дедлайны", "📋 Barcha deadlinelar", "📋 Барча дедлайнлар", "📋 All deadlines"), "deadlines_all_0")
            )));
            send(chatId, sb.toString(), withBack(userId, kb));
        });
    }

    private void editAllDeadlinesPage(long chatId, long userId, int messageId, int page) {
        if (!checkLogin(chatId, userId)) return;
        List<DeadlineItem> items = lastDeadlineItems.get(userId);
        if (items == null || items.isEmpty()) {
            showDeadlinesList(chatId, userId);
            return;
        }

        int pageSize = 5;
        int total   = items.size();
        int maxPage = Math.max(0, (total - 1) / pageSize);
        if (page < 0) page = 0;
        if (page > maxPage) page = maxPage;

        int from = page * pageSize;
        int to   = Math.min(from + pageSize, total);
        long now = System.currentTimeMillis();

        StringBuilder sb = new StringBuilder();
        sb.append(tr(userId, "📋 <b>Все дедлайны</b>", "📋 <b>Barcha deadlinelar</b>", "📋 <b>Барча дедлайнлар</b>", "📋 <b>All deadlines</b>"))
                .append("\n")
                .append(t(userId, "common.page")).append(" ").append(page + 1).append(" / ").append(maxPage + 1)
                .append("\n\n");

        for (int i = from; i < to; i++) {
            DeadlineItem it = items.get(i);
            Activity a = it.act;
            boolean hasUpload = a.getUploadedFileUrl() != null && !a.getUploadedFileUrl().isBlank();
            String statusIcon = (hasUpload || isGraded(a)) ? "✅" : "📭";
            String datePart   = (a.getDeadline() != null && a.getDeadline().length() >= 16)
                    ? a.getDeadline().substring(0, 16) : (a.getDeadline() != null ? a.getDeadline() : "—");
            long diffMin = Math.max(0, (it.dl - now) / 60000L);
            String inStr = diffMin < 60
                    ? tr(userId, "через " + diffMin + " мин", diffMin + " min ichida", diffMin + " мин ичида", "in" + diffMin + " min")
                    : tr(userId, "через " + (diffMin / 60) + " ч", (diffMin / 60) + " soat ichida", (diffMin / 60) + " соат ичида", "in" + (diffMin / 60) + " h");

            sb.append("<blockquote>")
                    .append(statusIcon).append(" <b>").append(esc(it.course)).append("</b>\n")
                    .append("📝 ").append(esc(truncateTopic(a.getTask(), 70))).append("\n")
                    .append("⏰ <b>").append(esc(datePart)).append("</b> — <i>").append(esc(inStr)).append("</i>\n")
                    .append("🏆 ").append(orDash(a.getEarnedScore())).append("/").append(orDash(a.getMaxScore()))
                    .append(criteriaShort(userId, a))
                    .append("</blockquote>\n");
        }

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> nav = new ArrayList<>();
        if (page > 0)      nav.add(inlineBtn(tr(userId, "⬅️ Предыдущий", "⬅️ Oldingi", "⬅️ Олдинги", "⬅️ Previous"), "deadlines_all_" + (page - 1)));
        if (page < maxPage) nav.add(inlineBtn(tr(userId, "Следующий ➡️", "Keyingi ➡️", "Кейинги ➡️", "Next ➡️"), "deadlines_all_" + (page + 1)));
        if (!nav.isEmpty()) rows.add(nav);
        rows.add(List.of(inlineBtn(tr(userId, "🔙 Назад", "🔙 Orqaga", "🔙 Орқага", "🔙 Back"), "deadlines_back")));

        edit(chatId, messageId, sb.toString(), markup(rows));
    }

    // ─────────────────────────────────────────────
    //  FILE DOWNLOAD
    // ─────────────────────────────────────────────

    private void handleFileDownload(long chatId, long userId, String token) {
        if (!checkLogin(chatId, userId)) return;
        CalendarEntry.FileAttachment att = pendingFiles
                .getOrDefault(userId, Collections.emptyMap()).get(token);
        if (att == null) {
            send(chatId, tr(userId,
                    "⚠️ Ссылка на файл устарела. Откройте план занятий заново.",
                    "⚠️ Fayl havolasi eskirgan. \"📆 Fan rejasi\" bo'limidan qayta ochib ko'ring.",
                    "⚠️ Файл ҳаволаси эскирган. «📆 Дарс режаси» бўлимидан қайта очиб кўринг.",
                    "⚠️ The file link has expired. Open the class plan again."), null);
            return;
        }
        if ("url".equals(att.getType())) {
            send(chatId, "🔗 <b>" + esc(att.getName()) + "</b>\n\n" + att.getUrl(), null);
            return;
        }
        sendProgress(chatId, tr(userId, "⏳ Загружаю файл...", "⏳ Fayl yuklanmoqda...", "⏳ Файл юкланмоқда...", "⏳ Loading file..."));
        executor.submit(() -> {
            File tmp = null;
            try {
                LmsService.DownloadedFile dl = lmsService.downloadFile(userId, att.getUrl(), att.getName());
                tmp = dl.file();
                dropProgress(chatId);
                SendDocument doc = new SendDocument();
                doc.setChatId(String.valueOf(chatId));
                doc.setDocument(new InputFile(tmp, dl.filename()));
                execute(doc);
            } catch (Exception e) {
                send(chatId, "❌ " + esc(e.getMessage()), null);
            } finally {
                if (tmp != null) try { tmp.delete(); } catch (Exception ign) {}
            }
        });
    }

    private void downloadAndSendActivityFile(long chatId, long userId, int courseId, int actIndex, String which) {
        if (!checkLogin(chatId, userId)) return;
        List<Activity> acts = userActivities.getOrDefault(userId, Collections.emptyMap()).get(courseId);
        if (acts == null || actIndex >= acts.size()) {
            send(chatId, tr(userId,
                    "⚠️ Данные устарели. Откройте активности заново.",
                    "⚠️ Ma'lumot eskirgan. Aktivnostlarni qayta oching.",
                    "⚠️ Маълумот эскирган. Активностларни қайта очинг.",
                    "⚠️ The data is outdated. Open the activities again."), null);
            return;
        }
        Activity act = acts.get(actIndex);
        String url  = "sample".equals(which) ? act.getSampleFileUrl()  : act.getUploadedFileUrl();
        String name = "sample".equals(which) ? act.getSampleFileName() : act.getUploadedFileName();
        if (url == null || url.isBlank()) {
            send(chatId, tr(userId, "📭 Файл не найден.", "📭 Fayl topilmadi.", "📭 Файл топилмади.", "📭 File not found."), null);
            return;
        }
        sendProgress(chatId, tr(userId, "⏳ Загружаю файл...", "⏳ Fayl yuklanmoqda...", "⏳ Файл юкланмоқда...", "⏳ Loading file..."));
        executor.submit(() -> {
            File tmp = null;
            try {
                LmsService.DownloadedFile dl = lmsService.downloadFile(userId, url, name);
                tmp = dl.file();
                dropProgress(chatId);
                SendDocument doc = new SendDocument();
                doc.setChatId(String.valueOf(chatId));
                doc.setDocument(new InputFile(tmp, dl.filename()));
                execute(doc);
            } catch (Exception e) {
                send(chatId, "❌ " + esc(e.getMessage()), null);
            } finally {
                if (tmp != null) try { tmp.delete(); } catch (Exception ign) {}
            }
        });
    }

    private void promptActivityUpload(long chatId, long userId, int courseId, int actIndex, String activityId) {
        if (!checkLogin(chatId, userId)) return;
        List<Activity> acts = userActivities.getOrDefault(userId, Collections.emptyMap()).get(courseId);
        String taskName = (acts != null && actIndex < acts.size()) ? acts.get(actIndex).getTask() : "";

        PendingUpload pu = new PendingUpload();
        pu.courseId = courseId; pu.activityId = activityId; pu.activityIndex = actIndex;
        pendingUpload.put(userId, pu);
        userState.put(userId, "WAIT_UPLOAD");

        String header = taskName.isBlank() ? "" : "📝 " + esc(taskName) + "\n\n";
        send(chatId, tr(userId,
                "📤 <b>Отправить задание</b>\n\n" + header + "<blockquote>• Макс. размер: 50 МБ\n• Типы: jpg, png, doc, docx, pdf, ppt, pptx, zip, rar</blockquote>\n\nОтправьте файл или /cancel.",
                "📤 <b>Topshiriqni yuborish</b>\n\n" + header + "<blockquote>• Maks. hajm: 50 MB\n• Turlari: jpg, png, doc, docx, pdf, ppt, pptx, zip, rar</blockquote>\n\nFayl yuboring yoki /cancel yozing.",
                "📤 <b>Топшириқни юбориш</b>\n\n" + header + "<blockquote>• Макс. ҳажм: 50 МБ\n• Турлари: jpg, png, doc, docx, pdf, ppt, pptx, zip, rar</blockquote>\n\nФайл юборинг ёки /cancel ёзинг.",
                "📤 <b>Submit assignment</b>\n\n" + header + "<blockquote>• Max size: 50 MB\n• Types: jpg, png, doc, docx, pdf, ppt, pptx, zip, rar</blockquote>\n\nSend a file or /cancel."), null);
    }

    private void handleActivityFileUpload(long chatId, long userId, Message msg) {
        PendingUpload pu = pendingUpload.remove(userId);
        if (pu == null) {
            send(chatId, tr(userId,
                    "⚠️ Контекст загрузки утерян. Попробуйте снова.",
                    "⚠️ Upload kontekst yo'q. Qayta urinib ko'ring.",
                    "⚠️ Юклаш контексти йўқ. Қайта уриниб кўринг.",
                    "⚠️ The upload context was lost. Please try again."), null);
            return;
        }
        sendProgress(chatId, tr(userId,
                "⏳ Загружаю файл в LMS...",
                "⏳ Fayl LMS ga yuklanmoqda...",
                "⏳ Файл LMS га юкланмоқда...",
                "⏳ Uploading the file to LMS..."));
        executor.submit(() -> {
            File tmp = null;
            try {
                String fileId, filename;
                if (msg.hasDocument()) {
                    fileId   = msg.getDocument().getFileId();
                    filename = msg.getDocument().getFileName();
                    if (filename == null || filename.isBlank()) filename = "file";
                } else {
                    var photos = msg.getPhoto();
                    fileId   = photos.get(photos.size() - 1).getFileId();
                    filename = "photo.jpg";
                }
                String fn = filename.toLowerCase();
                boolean allowed = fn.endsWith(".jpg") || fn.endsWith(".jpeg") || fn.endsWith(".png")
                        || fn.endsWith(".doc") || fn.endsWith(".docx") || fn.endsWith(".pdf")
                        || fn.endsWith(".ppt") || fn.endsWith(".pptx")
                        || fn.endsWith(".zip") || fn.endsWith(".rar");
                if (!allowed) {
                    send(chatId, tr(userId,
                            "❌ Неверный тип файла. Разрешены: jpg, png, doc, docx, pdf, ppt, pptx, zip, rar",
                            "❌ Noto'g'ri fayl turi. Ruxsat: jpg, png, doc, docx, pdf, ppt, pptx, zip, rar",
                            "❌ Нотўғри файл тури. Рухсат: jpg, png, doc, docx, pdf, ppt, pptx, zip, rar",
                            "❌ Wrong file type. Allowed: jpg, png, doc, docx, pdf, ppt, pptx, zip, rar"), null);
                    return;
                }
                org.telegram.telegrambots.meta.api.methods.GetFile gf = new org.telegram.telegrambots.meta.api.methods.GetFile();
                gf.setFileId(fileId);
                org.telegram.telegrambots.meta.api.objects.File tgFile = execute(gf);
                tmp = File.createTempFile("lmsup_", "_" + filename);
                downloadFile(tgFile, tmp);
                if (tmp.length() > 50L * 1024 * 1024) {
                    send(chatId, tr(userId,
                            "❌ Файл слишком большой (макс. 50 МБ).",
                            "❌ Fayl juda katta (maks. 50 MB).",
                            "❌ Файл жуда катта (макс. 50 МБ).",
                            "❌ The file is too large (max 50 MB)."), null);
                    return;
                }
                boolean ok = lmsService.uploadActivityFile(userId, pu.courseId, pu.activityId, tmp, filename);
                if (ok) {
                    send(chatId, tr(userId,
                            "✅ <b>Файл успешно загружен в LMS!</b>",
                            "✅ <b>Fayl muvaffaqiyatli LMS ga yuklandi!</b>",
                            "✅ <b>Файл муваффақиятли LMS га юкланди!</b>",
                            "✅ <b>File uploaded to LMS!</b>"), null);
                    showActivities(chatId, userId, pu.courseId);
                } else {
                    send(chatId, tr(userId,
                            "❌ Ошибка загрузки в LMS. Попробуйте позже.",
                            "❌ LMS ga yuklashda xatolik. Keyinroq urinib ko'ring.",
                            "❌ LMS га юклашда хатолик. Кейинроқ уриниб кўринг.",
                            "❌ Upload to LMS failed. Please try later."), null);
                }
            } catch (Exception e) {
                send(chatId, "❌ " + esc(e.getMessage()), null);
            } finally {
                if (tmp != null) try { tmp.delete(); } catch (Exception ign) {}
            }
        });
    }

    // ─────────────────────────────────────────────
    //  BACKGROUND REMINDERS
    // ─────────────────────────────────────────────

    private void startSchedulers() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                tickPairReminders();
                tickNbReminders();
                // Авто-рассылка ПОЛНОГО списка дедлайнов отключена — он только по кнопке.
                // Автоматически напоминаем лишь о срочных (≤2 дней).
                tickUrgentDeadlineReminders();
                teacherBot.tick();
            } catch (Exception e) {
                System.err.println("[LmsBot] Scheduler error: " + e.getMessage());
            }
        }, 15, 60, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::tickStateBackup, 3, 3, TimeUnit.MINUTES);
        scheduler.scheduleWithFixedDelay(this::tickSessionSweep, 5, SESSION_SWEEP_MIN, TimeUnit.MINUTES);
    }

    /**
     * Реже, чем LMS убивает сессию за простой (около двух часов), — с хорошим запасом
     * на случай, если сервис часть времени спал.
     */
    private static final int SESSION_SWEEP_MIN = 25;

    /**
     * Держит сессии живыми и чинит сломанные. Каждому, у кого на диске есть cookie или
     * токен OneID, делается один запрос к LMS: вошедшим он обновляет время простоя, и
     * сессия не истекает вовсе; остальных — возвращает в строй (при старте LMS могла не
     * ответить, и тогда напоминания молча пропускали бы такого пользователя, пока он
     * сам не напишет боту).
     */
    private void tickSessionSweep() {
        if (!stateRestored) return;
        for (Long uid : stateBackup.sessionUsers()) {
            if (loggingIn(uid)) continue;
            executor.submit(() -> {
                if (lmsService.restoreSession(uid)) lastChatId.putIfAbsent(uid, uid);
            });
        }
    }

    /**
     * Напоминания по расписанию: сводка дня за час до первой пары и
     * напоминание за 10 минут до каждой пары. Пары берутся из расписания семестра,
     * поэтому о закончившихся по плану лекциях и практиках бот не напоминает.
     */
    private void tickPairReminders() {
        for (Long userId : new ArrayList<>(lastChatId.keySet())) {
            if (!lmsService.isLoggedIn(userId)) continue;
            Long chatId = lastChatId.get(userId);
            if (chatId == null) continue;
            int semesterId = userSemester.getOrDefault(userId, getDefaultSemesterId(userId));
            if (semesterId <= 0) continue;

            uz.tuit.lmsbot.service.SemesterSchedule.Result sched = semesterSchedule.get(userId, semesterId, false);
            if (sched == null) continue;

            java.time.LocalDate today = java.time.LocalDate.now(AppConfig.LMS_ZONE);
            List<uz.tuit.lmsbot.service.SemesterSchedule.Lesson> lessons = sched.on(today);
            if (lessons.isEmpty()) continue;

            long now = System.currentTimeMillis();
            long toFirst = (lessons.get(0).ts() - now) / 60000L;
            if (toFirst >= DAY_DIGEST_BEFORE_MIN - PAIR_REMINDER_WINDOW_MIN
                    && toFirst <= DAY_DIGEST_BEFORE_MIN + PAIR_REMINDER_WINDOW_MIN
                    && markOnce(userId, "day|" + today)) {
                sendDayDigest(chatId, userId, lessons);
            }

            for (uz.tuit.lmsbot.service.SemesterSchedule.Lesson l : lessons) {
                long diffMin = (l.ts() - now) / 60000L;
                boolean inWindow = diffMin >= (PAIR_REMINDER_BEFORE_MIN - PAIR_REMINDER_WINDOW_MIN)
                        && diffMin <= (PAIR_REMINDER_BEFORE_MIN + PAIR_REMINDER_WINDOW_MIN);
                if (!inWindow) continue;
                if (!markOnce(userId, "pair|" + l.ts() + "|" + l.subject())) continue;

                String kind = kindLabel(userId, l.kind());
                send(chatId, tr(userId,
                        "⏰ <b>Напоминание: пара через " + PAIR_REMINDER_BEFORE_MIN + " минут</b>\n\n<blockquote>"
                                + "📖 Предмет: <b>" + esc(l.subject()) + "</b> · <i>" + esc(kind) + "</i>\n"
                                + (l.teacher().isBlank() ? "" : "👨‍🏫 Преподаватель: <b>" + esc(l.teacher()) + "</b>\n")
                                + "🕐 Время: <b>" + esc(l.time()) + "</b>\n"
                                + (l.room().isBlank() ? "" : "🚪 Кабинет: <b>" + esc(l.room()) + "</b>\n")
                                + "</blockquote>",
                        "⏰ <b>Eslatma: dars " + PAIR_REMINDER_BEFORE_MIN + " daqiqadan so'ng boshlanadi</b>\n\n<blockquote>"
                                + "📖 Fan: <b>" + esc(l.subject()) + "</b> · <i>" + esc(kind) + "</i>\n"
                                + (l.teacher().isBlank() ? "" : "👨‍🏫 O'qituvchi: <b>" + esc(l.teacher()) + "</b>\n")
                                + "🕐 Vaqt: <b>" + esc(l.time()) + "</b>\n"
                                + (l.room().isBlank() ? "" : "🚪 Xona: <b>" + esc(l.room()) + "</b>\n")
                                + "</blockquote>",
                        "⏰ <b>Эслатма: дарс " + PAIR_REMINDER_BEFORE_MIN + " дақиқадан кейин бошланади</b>\n\n<blockquote>"
                                + "📖 Фан: <b>" + esc(l.subject()) + "</b> · <i>" + esc(kind) + "</i>\n"
                                + (l.teacher().isBlank() ? "" : "👨‍🏫 Ўқитувчи: <b>" + esc(l.teacher()) + "</b>\n")
                                + "🕐 Вақт: <b>" + esc(l.time()) + "</b>\n"
                                + (l.room().isBlank() ? "" : "🚪 Хона: <b>" + esc(l.room()) + "</b>\n")
                                + "</blockquote>",
                        "⏰ <b>Reminder: class in" + PAIR_REMINDER_BEFORE_MIN + " minutes</b>\n\n<blockquote>"
                                + "📖 Subject: <b>" + esc(l.subject()) + "</b> · <i>" + esc(kind) + "</i>\n"
                                + (l.teacher().isBlank() ? "" : "👨‍🏫 Teacher: <b>" + esc(l.teacher()) + "</b>\n")
                                + "🕐 Time: <b>" + esc(l.time()) + "</b>\n"
                                + (l.room().isBlank() ? "" : "🚪 Room: <b>" + esc(l.room()) + "</b>\n")
                                + "</blockquote>"), null);
            }
        }
    }

    /** Сводка на день: все сегодняшние пары с аудиторией и преподавателем. */
    private void sendDayDigest(long chatId, long userId, List<uz.tuit.lmsbot.service.SemesterSchedule.Lesson> lessons) {
        StringBuilder sb = new StringBuilder();
        sb.append(tr(userId,
                "☀️ <b>Пары на сегодня</b> — первая через час",
                "☀️ <b>Bugungi darslar</b> — birinchisi bir soatdan keyin",
                "☀️ <b>Бугунги дарслар</b> — биринчиси бир соатдан кейин",
                "☀️ <b>Today's classes</b> — the first one starts in an hour")).append("\n");
        sb.append("<i>").append(tr(userId, "Всего пар: ", "Jami darslar: ", "Жами дарслар: ", "Classes:")).append(lessons.size()).append("</i>\n\n");
        for (int i = 0; i < lessons.size(); i++) {
            uz.tuit.lmsbot.service.SemesterSchedule.Lesson l = lessons.get(i);
            StringBuilder b = new StringBuilder();
            b.append("<b>").append(i + 1).append(". ").append(esc(l.time())).append("</b> · ")
                    .append(kindIcon(l.kind()))
                    .append(esc(kindLabel(userId, l.kind()))).append("\n")
                    .append("<b>").append(esc(l.subject())).append("</b>");
            if (l.stream() != null) b.append(" · ").append(esc(l.stream()));
            if (!l.room().isBlank()) b.append("\n🚪 ").append(tr(userId, "Кабинет", "Xona", "Хона", "Room")).append(": <b>").append(esc(l.room())).append("</b>");
            if (!l.teacher().isBlank()) b.append("\n👨‍🏫 ").append(esc(l.teacher()));
            if (l.topic() != null && !l.topic().isBlank()) b.append("\n📝 <i>").append(esc(truncateTopic(l.topic(), 80))).append("</i>");
            if (l.last()) b.append("\n🏁 ").append(tr(userId, "Последнее занятие", "Oxirgi dars", "Охирги дарс", "Last class"));
            sb.append("<blockquote>").append(b).append("</blockquote>\n");
        }
        send(chatId, sb.toString(), null);
    }

    private void tickNbReminders() {
        for (Long userId : new ArrayList<>(lastChatId.keySet())) {
            if (!lmsService.isLoggedIn(userId) || lmsService.isTeacher(userId)) continue;
            Long chatId = lastChatId.get(userId);
            if (chatId == null) continue;
            long nowTs = System.currentTimeMillis();
            if (nowTs - lastNbCheckTs.getOrDefault(userId, 0L) < NB_CHECK_MS) continue;
            lastNbCheckTs.put(userId, nowTs);

            int semesterId = userSemester.getOrDefault(userId, getDefaultSemesterId(userId));
            if (semesterId <= 0) continue;
            List<Course> courses = lmsService.getMyCourses(userId, semesterId);
            if (courses == null || courses.isEmpty()) continue;
            Map<Integer, Integer> prev = lastNbByCourse.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
            for (Course c : courses) {
                int nb = c.getAttendance();
                Integer old = prev.put(c.getId(), nb);
                if (old == null || nb <= old) continue;
                String key = "nb|" + c.getId() + "|" + nb;
                if (!markOnce(userId, key)) continue;
                send(chatId, tr(userId,
                        "🔴 <b>Новый пропуск (НБ)</b>\n\n<blockquote>"
                                + "📖 Предмет: <b>" + esc(c.getSubject()) + "</b>\n"
                                + "📌 НБ стало: <b>" + nb + "</b> (было " + old + ")</blockquote>",
                        "🔴 <b>Yangi NB (dars qoldirildi)</b>\n\n<blockquote>"
                                + "📖 Fan: <b>" + esc(c.getSubject()) + "</b>\n"
                                + "📌 NB: <b>" + nb + "</b> (avval " + old + " edi)</blockquote>",
                        "🔴 <b>Янги НБ (дарс қолдирилди)</b>\n\n<blockquote>"
                                + "📖 Фан: <b>" + esc(c.getSubject()) + "</b>\n"
                                + "📌 НБ: <b>" + nb + "</b> (аввал " + old + " эди)</blockquote>",
                        "🔴 <b>New absence</b>\n\n<blockquote>"
                                + "📖 Subject: <b>" + esc(c.getSubject()) + "</b>\n"
                                + "📌 Absences now: <b>" + nb + "</b> (was" + old + ")</blockquote>"), null);
            }
        }
    }

    private void tickDeadlinesListReminder() {
        for (Long userId : new ArrayList<>(lastChatId.keySet())) {
            if (!lmsService.isLoggedIn(userId) || lmsService.isTeacher(userId)) continue;
            Long chatId = lastChatId.get(userId);
            if (chatId == null) continue;

            long now  = System.currentTimeMillis();
            long last = lastDeadlinesListNotifyTs.getOrDefault(userId, 0L);
            if (now - last < DEADLINES_LIST_INTERVAL_MS) continue;

            lastDeadlinesListNotifyTs.put(userId, now);
            showDeadlinesList(chatId, userId);
        }
    }

    /**
     * ★ ИСПРАВЛЕНО: Напоминание о срочных дедлайнах (≤1 день).
     *
     * Проблема была в том, что дедлайны из LMS приходят в формате
     * "24-03-2026 23:59:59" (dd-MM-yyyy HH:mm:ss), а parseActivityDeadlineTs
     * правильно их парсит. Но scheduler мог не отправлять уведомления потому что:
     *
     * 1. userCourses мог быть пустым — теперь всегда загружаем из lmsService.
     * 2. Уже загруженные задания (hasUpload) пропускаем.
     * 3. Ключ sentReminders использовался только для пар — теперь для дедлайнов
     *    используется отдельный механизм через lastDeadlineNotifyTs с интервалом.
     *
     * Дополнительно: заголовок сообщения теперь явно называется
     * "🔔 Напоминание о дедлайне" на всех трёх языках.
     */
    /** Свежий запрос в LMS: загружено ли задание (студент мог сдать минуту назад). */
    private boolean isActivityUploaded(long userId, int courseId, Activity target) {
        try {
            CourseSummary cs = lmsService.getActivities(userId, courseId);
            if (cs == null || cs.getActivities() == null) return false;
            for (Activity a : cs.getActivities()) {
                if (a.getTask() == null || !a.getTask().equals(target.getTask())) continue;
                return a.getUploadedFileUrl() != null && !a.getUploadedFileUrl().isBlank();
            }
        } catch (Exception e) {
            System.err.println("[LmsBot] isActivityUploaded: " + e.getMessage());
        }
        return false;
    }

    private void tickUrgentDeadlineReminders() {
        for (Long userId : new ArrayList<>(lastChatId.keySet())) {
            if (!lmsService.isLoggedIn(userId) || lmsService.isTeacher(userId)) continue;
            Long chatId = lastChatId.get(userId);
            if (chatId == null) continue;

            int semesterId = userSemester.getOrDefault(userId, getDefaultSemesterId(userId));
            if (semesterId <= 0) continue;

            // Всегда обеспечиваем актуальный список курсов
            List<Course> courses = userCourses.get(userId);
            if (courses == null || courses.isEmpty()) {
                courses = lmsService.getMyCourses(userId, semesterId);
                if (courses != null && !courses.isEmpty()) {
                    userCourses.put(userId, courses);
                    rememberSemester(userId, semesterId);
                }
            }
            if (courses == null || courses.isEmpty()) continue;

            long now = System.currentTimeMillis();
            // Срочным считаем дедлайн, до которого осталось не больше 2 дней
            long urgentUntil = now + URGENT_DEADLINE_WINDOW_MS;

            // Обход всех предметов — тяжёлый, чаще чем раз в 15 минут не нужен
            if (now - lastUrgentScanTs.getOrDefault(userId, 0L) < URGENT_SCAN_MS) continue;
            lastUrgentScanTs.put(userId, now);

            for (Course c : courses) {
                CourseSummary cs = lmsService.getActivities(userId, c.getId());
                if (cs == null || cs.getActivities() == null) continue;

                for (Activity a : cs.getActivities()) {
                    long dl = parseActivityDeadlineTs(a.getDeadline());
                    if (dl <= 0 || dl < now || dl > urgentUntil) continue;

                    // Пропускаем уже загруженные задания
                    boolean hasUpload = a.getUploadedFileUrl() != null && !a.getUploadedFileUrl().isBlank();
                    if (hasUpload) continue;

                    String key = deadlineKey(c.getId(), dl, a.getTask());

                    int intervalMin = deadlineIntervalsMin
                            .computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                            .getOrDefault(key, URGENT_DEADLINE_DEFAULT_INTERVAL_MIN);

                    long last = lastDeadlineNotifyTs
                            .computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                            .getOrDefault(key, 0L);

                    if (now - last < intervalMin * 60L * 1000) continue;

                    // Перед самой отправкой ещё раз спрашиваем LMS: вдруг уже загрузил
                    if (isActivityUploaded(userId, c.getId(), a)) {
                        lastDeadlineNotifyTs.get(userId).put(key, now);
                        continue;
                    }

                    lastDeadlineNotifyTs.get(userId).put(key, now);

                    String datePart = a.getDeadline() != null ? a.getDeadline() : "—";

                    long diffMin = Math.max(0, (dl - now) / 60000L);
                    String inStr;
                    if (diffMin < 60) {
                        inStr = tr(userId,
                                "через " + diffMin + " мин",
                                diffMin + " min ichida",
                                diffMin + " мин ичида",
                                "in" + diffMin + " min");
                    } else {
                        long h = diffMin / 60;
                        long m = diffMin % 60;
                        inStr = tr(userId,
                                "через " + h + " ч " + m + " мин",
                                h + " soat " + m + " min ichida",
                                h + " соат " + m + " мин ичида",
                                "in" + h + " h" + m + " min");
                    }

                    DeadlineMeta meta = new DeadlineMeta();
                    meta.course = c.getSubject();
                    meta.task = a.getTask();
                    meta.deadline = datePart;
                    meta.status = "📭";
                    deadlineMeta.computeIfAbsent(userId, k -> new ConcurrentHashMap<>()).put(key, meta);

                    // ★ ИСПРАВЛЕНО: название "Напоминание о дедлайне" на всех языках
                    String title = tr(userId,
                            "🔔 <b>Напоминание о дедлайне</b>",
                            "🔔 <b>Deadline haqida eslatma</b>",
                            "🔔 <b>Дедлайн ҳақида эслатма</b>",
                            "🔔 <b>Deadline reminder</b>");

                    String text = title + "\n\n<blockquote>"
                            + "📖 " + tr(userId, "Предмет", "Fan", "Фан", "Subject") + ": <b>" + esc(c.getSubject()) + "</b>\n"
                            + "📝 " + esc(truncateTopic(a.getTask(), 90)) + "\n"
                            + "⏰ " + tr(userId, "Дедлайн", "Deadline", "Дедлайн", "Deadline") + ": <b>" + esc(datePart) + "</b>\n"
                            + "⏱ " + esc(inStr) + "\n"
                            + "📌 " + tr(userId, "Статус", "Status", "Статус", "Status") + ": <b>📭 "
                            + tr(userId, "Не загружено", "Yuklanmagan", "Юкланмаган", "Not uploaded") + "</b>\n"
                            + "🔁 " + tr(userId, "Интервал напоминания", "Eslatma oralig'i", "Эслатма оралиғи", "Reminder interval") + ": <b>"
                            + esc(tr(userId,
                            minutesToLabelRu(intervalMin),
                            minutesToLabelUzLat(intervalMin),
                            minutesToLabelUzCyr(intervalMin),
                            minutesToLabelEn(intervalMin)))
                            + "</b></blockquote>";

                    InlineKeyboardMarkup kb = markup(List.of(
                            List.of(
                                    inlineBtn(tr(userId, "⏰ Через 30 мин", "⏰ 30 min keyin", "⏰ 30 мин кейин", "⏰ In 30 min"),
                                            "dl_set_" + key + "_30"),
                                    inlineBtn(tr(userId, "⏰ Через 1 час", "⏰ 1 soat keyin", "⏰ 1 соат кейин", "⏰ In 1 hour"),
                                            "dl_set_" + key + "_60")),
                            List.of(inlineBtn(
                                    tr(userId, "⏱ Изменить интервал", "⏱ Oraliqni o'zgartirish", "⏱ Оралиқни ўзгартириш", "⏱ Change interval"),
                                    "dl_remind_" + key))
                    ));
                    send(chatId, text, kb);
                }
            }
        }
    }

    private void showDeadlineIntervalPicker(long chatId, long userId, int messageId, String key) {
        edit(chatId, messageId, tr(userId,
                        "⏱ <b>Напоминать через</b>\n\n<blockquote>Выберите интервал.</blockquote>",
                        "⏱ <b>Qachon eslatilsin</b>\n\n<blockquote>Oraliqni tanlang.</blockquote>",
                        "⏱ <b>Қачон эслатилсин</b>\n\n<blockquote>Оралиқни танланг.</blockquote>",
                        "⏱ <b>Remind me in</b>\n\n<blockquote>Choose an interval.</blockquote>"),
                markup(List.of(
                        List.of(inlineBtn(tr(userId, "30 мин", "30 min", "30 мин", "30 min"), "dl_set_" + key + "_30"),
                                inlineBtn(tr(userId, "1 час", "1 soat", "1 соат", "1 hour"), "dl_set_" + key + "_60")),
                        List.of(inlineBtn(tr(userId, "3 часа", "3 soat", "3 соат", "3 hours"), "dl_set_" + key + "_180"),
                                inlineBtn(tr(userId, "5 часов", "5 soat", "5 соат", "5 hours"), "dl_set_" + key + "_300"))
                )));
    }

    private void setDeadlineInterval(long userId, String key, int minutes) {
        deadlineIntervalsMin.computeIfAbsent(userId, k -> new ConcurrentHashMap<>()).put(key, minutes);
    }

    // ─────────────────────────────────────────────
    //  GLOBAL COMMAND ROUTING
    // ─────────────────────────────────────────────

    private boolean handleGlobalCommands(long chatId, long userId, String text, String firstName) {
        String t = text != null ? text.trim() : "";
        if (t.isEmpty()) return false;

        if ("/start".equals(t)) {
            userState.put(userId, "IDLE");
            if (!hasLang(userId)) sendLanguageChoice(chatId);
            else sendWelcome(chatId, userId, firstName);
            return true;
        }
        if ("/app".equals(t)) {
            userState.put(userId, "IDLE");
            sendAppButton(chatId, userId);
            return true;
        }
        if (isLoginCommand(t)) {
            userState.put(userId, "IDLE");
            askForLogin(chatId, userId);
            return true;
        }
        if (isSettingsCommand(t)) {
            userState.put(userId, "IDLE");
            showSettings(chatId, userId);
            return true;
        }
        if (isDeadlinesListCommand(t)) {
            userState.put(userId, "IDLE");
            if (lmsService.isTeacher(userId)) teacherBot.showGrading(chatId, userId, null);
            else showDeadlinesList(chatId, userId);
            return true;
        }
        if (t.startsWith("/dump ")) {
            String path = t.substring("/dump ".length()).trim();
            executor.submit(() -> {
                String html = lmsService.dumpPage(userId, path);
                try {
                    java.nio.file.Path dir = java.nio.file.Paths.get("dumps");
                    java.nio.file.Files.createDirectories(dir);
                    String safe = path.replaceAll("[^A-Za-z0-9]+", "_");
                    java.nio.file.Path f = dir.resolve(safe + ".html");
                    java.nio.file.Files.write(f, html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    send(chatId, "💾 " + esc(f.toAbsolutePath().toString()) + " (" + html.length() + " b)", null);
                } catch (Exception e) {
                    send(chatId, "❌ dump: " + esc(String.valueOf(e.getMessage())), null);
                }
            });
            return true;
        }
        if (isLogoutCommand(t)) {
            userState.put(userId, "IDLE");
            handleLogout(chatId, userId);
            return true;
        }
        if (isMainMenuNav(t)) {
            userState.put(userId, "IDLE");
            return false;
        }
        return false;
    }

    private boolean isMainMenuNav(String t) {
        return teacherBot.isMenuText(t) || t.equals("📚 Mening fanlarim") || t.equals("📚 Мои предметы") || t.equals("📚 Менинг фанларим")
                || t.equals("📅 Dars jadvali") || t.equals("📅 Расписание") || t.equals("📅 Дарс жадвали")
                || t.equals("📖 O'quv reja") || t.equals("📖 Учебный план") || t.equals("📖 Ўқув режа")
                || t.equals("🏆 Yakuniy imtihon") || t.equals("🏆 Итоговый экзамен") || t.equals("🏆 Якуний имтиҳон")
                || t.equals("👤 Profil") || t.equals("👤 Профиль") || t.equals("👤 Профил")
                || t.equals("📚 My subjects") || t.equals("📅 Timetable") || t.equals("📖 Study plan")
                || t.equals("🏆 Final exams") || t.equals("👤 Profile");
    }

    private boolean isLoginCommand(String t)     { return "/login".equals(t) || "🔑 Kirish".equals(t) || "🔑 Войти".equals(t) || "🔑 Кириш".equals(t) || "🔑 Sign in".equals(t); }
    private boolean isLogoutCommand(String t)    { return "/logout".equals(t) || "🚪 Chiqish".equals(t) || "🚪 Выйти".equals(t) || "🚪 Чиқиш".equals(t) || "🚪 Sign out".equals(t); }
    private boolean isSettingsCommand(String t)  { return "⚙️ Sozlamalar".equals(t) || "⚙️ Настройки".equals(t) || "⚙️ Созламалар".equals(t) || "⚙️ Settings".equals(t); }
    private boolean isDeadlinesListCommand(String t) { return "🗓 Deadline ro'yxati".equals(t) || "🗓 Список дедлайнов".equals(t) || "🗓 Дедлайнлар рўйхати".equals(t) || "🗓 Deadlines".equals(t); }

    // ─────────────────────────────────────────────
    //  KEYBOARDS
    // ─────────────────────────────────────────────

    ReplyKeyboardMarkup loginKeyboard(long userId) {
        KeyboardRow signIn = new KeyboardRow();
        signIn.add(new KeyboardButton(tr(userId, "🔑 Войти", "🔑 Kirish", "🔑 Кириш", "🔑 Sign in")));
        List<KeyboardRow> rows = new ArrayList<>();
        // Кнопка живёт только на этой клавиатуре, то есть пока пользователь не вошёл:
        // после входа Telegram получает mainMenuKeyboard, и автовход исчезает сам.
        if (lmsService.credentials().has(userId)) {
            KeyboardRow auto = new KeyboardRow();
            auto.add(new KeyboardButton(autoLoginLabel(userId)));
            auto.add(new KeyboardButton(credsMenuLabel(userId)));
            rows.add(auto);
        }
        rows.add(signIn);
        ReplyKeyboardMarkup m = new ReplyKeyboardMarkup();
        m.setKeyboard(rows);
        m.setResizeKeyboard(true);
        return m;
    }

    /** Подписи кнопок; варианты повторены в разборе текста сообщений. */
    private String autoLoginLabel(long userId) {
        return tr(userId, "⚡ Автовход", "⚡ Avtokirish", "⚡ Автокириш", "⚡ Auto sign-in");
    }

    private String credsMenuLabel(long userId) {
        return tr(userId, "🗂 Мои аккаунты", "🗂 Hisoblarim", "🗂 Ҳисобларим", "🗂 My accounts");
    }

    private ReplyKeyboardMarkup mainMenuKeyboard(long userId) {
        if (lmsService.isTeacher(userId)) return teacherBot.menuKeyboard(userId);
        String myCourses = tr(userId, "📚 Мои предметы", "📚 Mening fanlarim", "📚 Менинг фанларим", "📚 My subjects");
        String schedule  = tr(userId, "📅 Расписание", "📅 Dars jadvali", "📅 Дарс жадвали", "📅 Timetable");
        String plan      = tr(userId, "📖 Учебный план", "📖 O'quv reja", "📖 Ўқув режа", "📖 Study plan");
        String finals    = tr(userId, "🏆 Итоговый экзамен", "🏆 Yakuniy imtihon", "🏆 Якуний имтиҳон", "🏆 Final exams");
        String profile   = tr(userId, "👤 Профиль", "👤 Profil", "👤 Профил", "👤 Profile");
        String settings  = tr(userId, "⚙️ Настройки", "⚙️ Sozlamalar", "⚙️ Созламалар", "⚙️ Settings");

        KeyboardRow r1 = new KeyboardRow(); r1.add(new KeyboardButton(myCourses));
        KeyboardRow r2 = new KeyboardRow(); r2.add(new KeyboardButton(schedule));  r2.add(new KeyboardButton(plan));
        KeyboardRow r3 = new KeyboardRow(); r3.add(new KeyboardButton(finals));    r3.add(new KeyboardButton(profile));
        KeyboardRow r4 = new KeyboardRow(); r4.add(new KeyboardButton(settings));
        ReplyKeyboardMarkup m = new ReplyKeyboardMarkup();
        m.setKeyboard(List.of(r1, r2, r3, r4));
        m.setResizeKeyboard(true);
        return m;
    }

    private InlineKeyboardMarkup coursesActionKeyboard(long userId) {
        return markup(List.of(
                List.of(inlineBtn(t(userId, "btn.attendance"), "select_attend"),
                        inlineBtn(t(userId, "btn.activities"), "select_activities")),
                List.of(inlineBtn(t(userId, "btn.calendar"), "select_calendar")),
                List.of(inlineBtn(t(userId, "btn.deadlines"), "deadlines_list")),
                List.of(inlineBtn(t(userId, "btn.change_semester"), "change_semester"))
        ));
    }

    private InlineKeyboardMarkup gpaChoiceKeyboard(long userId) {
        return markup(List.of(List.of(
                inlineBtn(tr(userId, "✅ Оценённые предметы", "✅ Baholangan fanlar", "✅ Баҳоланган фанлар", "✅ Graded subjects"), "gpa_all"),
                inlineBtn(tr(userId, "⬜ С текущим сем. (0)", "⬜ Joriy semestr 0 bilan", "⬜ Жорий семестр 0 билан", "⬜ With current sem. (0)"), "gpa_with_zero")
        )));
    }

    private InlineKeyboardMarkup finalsSemesterKeyboard(long userId)   { return buildSemesterKeyboard(userId, "finals_");   }
    private InlineKeyboardMarkup semesterKeyboard(long userId)         { return buildSemesterKeyboard(userId, "semester_"); }
    private InlineKeyboardMarkup semesterScheduleKeyboard(long userId) { return buildSemesterKeyboard(userId, "schedule_"); }

    /** Сколько семестров показываем на одной странице. */
    private static final int SEM_PAGE_SIZE = 4;

    InlineKeyboardMarkup buildSemesterKeyboard(long userId, String prefix) {
        return buildSemesterKeyboard(userId, prefix, 0);
    }

    /**
     * Клавиатура выбора семестра с пагинацией: семестров бывает много
     * (включая семестры переобучения), все кнопки сразу не помещаются.
     */
    private InlineKeyboardMarkup buildSemesterKeyboard(long userId, String prefix, int page) {
        List<AppConfig.SemesterConfig> semesters = lmsService.getSemesters(userId);
        if (semesters.isEmpty()) return markup(List.of());

        int maxPage = (semesters.size() - 1) / SEM_PAGE_SIZE;
        if (page < 0) page = 0;
        if (page > maxPage) page = maxPage;

        int from = page * SEM_PAGE_SIZE;
        int to   = Math.min(from + SEM_PAGE_SIZE, semesters.size());

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (int i = from; i < to; i++) {
            AppConfig.SemesterConfig s = semesters.get(i);
            rows.add(List.of(inlineBtn(semesterButtonLabel(userId, s), prefix + s.getId())));
        }

        if (maxPage > 0) {
            List<InlineKeyboardButton> nav = new ArrayList<>();
            String key = prefix.endsWith("_") ? prefix.substring(0, prefix.length() - 1) : prefix;
            nav.add(inlineBtn("◀️", "semp_" + key + "_" + (page > 0 ? page - 1 : maxPage)));
            nav.add(inlineBtn((page + 1) + "/" + (maxPage + 1), "noop"));
            nav.add(inlineBtn("▶️", "semp_" + key + "_" + (page < maxPage ? page + 1 : 0)));
            rows.add(nav);
        }

        return markup(rows);
    }

    /** Подпись кнопки семестра: показываем полное название, чтобы было видно переобучение. */
    private String semesterButtonLabel(long userId, AppConfig.SemesterConfig s) {
        String name = s.getName() != null ? s.getName().trim() : "";
        if (name.isEmpty()) name = s.getYear() != null ? s.getYear() : String.valueOf(s.getId());
        name = name.replaceAll("\\s+", " ");
        if (name.length() > 40) name = name.substring(0, 39) + "…";
        String emoji = s.getEmoji() != null ? s.getEmoji() : "📅";
        return name.matches(".*[0-9]\\s*-?\\s*(семестр|semestr|semester).*") ? name : emoji + " " + name;
    }

    private void sendLanguageChoice(long chatId) {
        send(chatId, "🌐 Tilni tanlang / Выберите язык / Choose a language:", langChoiceMarkup("lang_"));
    }

    private InlineKeyboardMarkup langChoiceMarkup() { return langChoiceMarkup("setlang_"); }
    private InlineKeyboardMarkup langChoiceMarkup(String prefix) {
        return markup(List.of(
                List.of(inlineBtn("🇷🇺 Русский",             prefix + "ru")),
                List.of(inlineBtn("🇺🇿 O'zbekcha (lotin)",   prefix + "uz_lat")),
                List.of(inlineBtn("🇺🇿 Ўзбекча (кирилл)",   prefix + "uz_cyr")),
                List.of(inlineBtn("🇬🇧 English",             prefix + "en"))
        ));
    }

    InlineKeyboardMarkup markup(List<List<InlineKeyboardButton>> rows) {
        InlineKeyboardMarkup m = new InlineKeyboardMarkup();
        m.setKeyboard(rows);
        return m;
    }

    /**
     * Раньше к каждому разделу добавлялась кнопка «Назад» в главное меню. Меню и так
     * всегда под рукой на нижней клавиатуре, а кнопка лишь плодила сообщения —
     * поэтому клавиатура раздела отдаётся как есть.
     */
    private InlineKeyboardMarkup withBack(long userId, InlineKeyboardMarkup kb) {
        return kb;
    }

    private InlineKeyboardMarkup backOnly(long userId) {
        return null;
    }

    /** Telegram отводит на callback_data 64 байта и отвергает всё сообщение целиком. */
    private static final int CALLBACK_LIMIT = 64;

    InlineKeyboardButton inlineBtn(String text, String data) {
        InlineKeyboardButton btn = new InlineKeyboardButton();
        btn.setText(text);
        if (data != null && data.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > CALLBACK_LIMIT) {
            // Раньше такое сообщение просто не отправлялось, и понять почему было нельзя.
            System.err.println("[LmsBot] callback_data длиннее " + CALLBACK_LIMIT + " байт, кнопка «"
                    + text + "» не будет работать: " + data);
        }
        btn.setCallbackData(data);
        return btn;
    }

    // ─────────────────────────────────────────────
    //  LOCALIZATION  t() / tr()
    // ─────────────────────────────────────────────

    String lang(long userId) {
        loadLangFromStore(userId);
        return userLang.getOrDefault(userId, "uz_lat");
    }

    /**
     * Выбранный язык лежит в settings_<id>.json, но раньше его только записывали
     * и никогда не читали: после перезапуска бота язык молча возвращался к uz_lat.
     */
    private void loadLangFromStore(long userId) {
        if (userLang.containsKey(userId)) return;
        UserSettings st = userSettings.computeIfAbsent(userId, k -> settingsStore.load(k));
        if (st.lang != null && !st.lang.isBlank()) userLang.put(userId, st.lang);
    }

    /** true — язык пользователь уже выбирал (в этой сессии или когда-либо раньше). */
    private boolean hasLang(long userId) {
        loadLangFromStore(userId);
        return userLang.containsKey(userId);
    }

    String tr(long userId, String ru, String uzLat, String uzCyr, String en) {
        return switch (lang(userId)) { case "ru" -> ru; case "uz_cyr" -> uzCyr; case "en" -> en; default -> uzLat; };
    }

    String t(long userId, String key) {
        String l = lang(userId);
        return switch (key) {
            case "common.no_data"        -> switch(l){ case "ru"->"📭 Данные не найдены."; case "en"->"📭 No data found.";        case "uz_cyr"->"📭 Маълумот топилмади.";        default->"📭 Ma'lumot topilmadi."; };
            case "common.page"           -> switch(l){ case "ru"->"Страница"; case "en"->"Page";                     case "uz_cyr"->"Саҳифа";                        default->"Sahifa"; };
            case "btn.back"              -> switch(l){ case "ru"->"🔙 Назад"; case "en"->"🔙 Back";                     case "uz_cyr"->"🔙 Орқага";                     default->"🔙 Orqaga"; };
            case "btn.back_courses"      -> switch(l){ case "ru"->"🔙 Мои предметы"; case "en"->"🔙 My subjects";              case "uz_cyr"->"🔙 Менинг фанларим";            default->"🔙 Mening fanlarim"; };
            case "btn.back_plan"         -> switch(l){ case "ru"->"🔙 Назад к плану"; case "en"->"🔙 Back to the plan";             case "uz_cyr"->"🔙 Режага қайтиш";              default->"🔙 O'quv rejaga qaytish"; };
            case "btn.prev"              -> switch(l){ case "ru"->"⬅️ Назад"; case "en"->"⬅️ Back";                     case "uz_cyr"->"⬅️ Олдинги";                    default->"⬅️ Oldingi"; };
            case "btn.next"              -> switch(l){ case "ru"->"Вперёд ➡️"; case "en"->"Next ➡️";                    case "uz_cyr"->"Кейинги ➡️";                    default->"Keyingi ➡️"; };
            case "btn.files"             -> switch(l){ case "ru"->"📂 Файлы"; case "en"->"📂 Files";                     case "uz_cyr"->"📂 Файллар";                    default->"📂 Fayllar"; };
            case "btn.attendance"        -> switch(l){ case "ru"->"📊 Посещаемость"; case "en"->"📊 Attendance";              case "uz_cyr"->"📊 Давомат";                    default->"📊 Davomat"; };
            case "btn.activities"        -> switch(l){ case "ru"->"📋 Активности"; case "en"->"📋 Activities";                case "uz_cyr"->"📋 Активностлар";               default->"📋 Aktivnosti"; };
            case "btn.calendar"          -> switch(l){ case "ru"->"📆 План занятий"; case "en"->"📆 Class plan";              case "uz_cyr"->"📆 Дарс режаси";                default->"📆 Fan rejasi"; };
            case "btn.deadlines"         -> switch(l){ case "ru"->"🗓 Список дедлайнов"; case "en"->"🗓 Deadlines";          case "uz_cyr"->"🗓 Дедлайнлар рўйхати";         default->"🗓 Deadline ro'yxati"; };
            case "btn.change_semester"   -> switch(l){ case "ru"->"📅 Сменить семестр"; case "en"->"📅 Change semester";           case "uz_cyr"->"📅 Семестрни ўзгартириш";       default->"📅 Semestrni o'zgartirish"; };
            case "courses.loading"       -> switch(l){ case "ru"->"⏳ Загружаю предметы..."; case "en"->"⏳ Loading subjects...";      case "uz_cyr"->"⏳ Фанлар юкланмоқда...";       default->"⏳ Fanlar yuklanmoqda..."; };
            case "sem.unavailable"      -> switch(l){ case "ru"->"⚠️ Не удалось получить список семестров из LMS. Попробуйте позже или войдите заново: /login"; case "en"->"⚠️ Could not get the semester list from LMS. Try later or sign in again: /login"; case "uz_cyr"->"⚠️ LMS дан семестрлар рўйхатини олиб бўлмади. Кейинроқ уриниб кўринг ёки қайта киринг: /login"; default->"⚠️ LMS dan semestrlar ro'yxatini olib bo'lmadi. Keyinroq urinib ko'ring yoki qayta kiring: /login"; };
            case "courses.empty_semester"-> switch(l){ case "ru"->"📭 В этом семестре предметов не найдено."; case "en"->"📭 No subjects found in this semester."; case "uz_cyr"->"📭 Бу семестрда фанлар топилмади."; default->"📭 Bu semestrda fanlar topilmadi."; };
            case "courses.total"         -> switch(l){ case "ru"->"Итого"; case "en"->"Total";                        case "uz_cyr"->"Жами";                          default->"Jami"; };
            case "courses.subjects_suffix"->switch(l){ case "ru"->"предмет(ов)"; case "en"->"subject(s)";                  case "uz_cyr"->"та фан";                        default->"ta fan"; };
            case "att.loading"           -> switch(l){ case "ru"->"⏳ Загружается посещаемость..."; case "en"->"⏳ Loading attendance..."; case "uz_cyr"->"⏳ Давомат юкланмоқда...";    default->"⏳ Davomat yuklanmoqda..."; };
            case "att.none"              -> switch(l){ case "ru"->"📭 Данные по посещаемости не найдены."; case "en"->"📭 No attendance data found."; case "uz_cyr"->"📭 Давомат маълумоти топилмади."; default->"📭 Davomat ma'lumoti topilmadi."; };
            case "att.all_ok_title"      -> switch(l){ case "ru"->"✅ <b>Вы посетили все занятия!</b>"; case "en"->"✅ <b>You attended every class!</b>"; case "uz_cyr"->"✅ <b>Барча дарсларда қатнашгансиз!</b>"; default->"✅ <b>Barcha darslarga qatnashgansiz!</b>"; };
            case "att.total_lessons"     -> switch(l){ case "ru"->"📅 Всего занятий"; case "en"->"📅 Total classes";             case "uz_cyr"->"📅 Жами дарслар";               default->"📅 Jami darslar"; };
            case "att.missed"            -> switch(l){ case "ru"->"🔴 Пропущено"; case "en"->"🔴 Missed";                 case "uz_cyr"->"🔴 Қолдирилган";                default->"🔴 Qoldirilgan"; };
            case "att.reason_yes"        -> switch(l){ case "ru"->"✅ Есть уважительная причина"; case "en"->"✅ Excused"; case "uz_cyr"->"✅ Сабаб бор";                  default->"✅ Sabab bor"; };
            case "att.reason_no"         -> switch(l){ case "ru"->"❌ Без уважительной причины"; case "en"->"❌ Unexcused";  case "uz_cyr"->"❌ Сабаб йўқ";                  default->"❌ Sabab yo'q"; };
            case "cal.loading"           -> switch(l){ case "ru"->"⏳ Загружается план занятий..."; case "en"->"⏳ Loading the class plan..."; case "uz_cyr"->"⏳ Дарс режаси юкланмоқда..."; default->"⏳ Dars rejasi yuklanmoqda..."; };
            case "cal.no_data"           -> switch(l){ case "ru"->"📭 Для этого предмета план занятий не найден."; case "en"->"📭 No class plan found for this subject."; case "uz_cyr"->"📭 Бу фан учун дарс режаси топилмади."; default->"📭 Bu fan uchun dars rejasi topilmadi."; };
            case "cal.plan_label"        -> switch(l){ case "ru"->"план занятий"; case "en"->"class plan";                 case "uz_cyr"->"режаси";                        default->"rejasi"; };
            case "cal.files_empty"       -> switch(l){ case "ru"->"📁 Для этой темы нет файлов."; case "en"->"📁 No files for this topic."; case "uz_cyr"->"📁 Бу мавзу учун файллар йўқ."; default->"📁 Bu mavzu uchun fayllar yo'q."; };
            case "cal.files_title"       -> switch(l){ case "ru"->"📂 <b>Файлы</b>"; case "en"->"📂 <b>Files</b>";              case "uz_cyr"->"📂 <b>Файллар</b>";              default->"📂 <b>Fayllar</b>"; };
            case "cal.choose_type"       -> switch(l){ case "ru"->"Какой план хотите посмотреть?"; case "en"->"Which plan do you want to see?"; case "uz_cyr"->"Қайси режани кўрмоқчисиз?";   default->"Qaysi rejani ko'rmoqchisiz?"; };
            case "act.loading"           -> switch(l){ case "ru"->"⏳ Загружаются активности..."; case "en"->"⏳ Loading activities..."; case "uz_cyr"->"⏳ Активностлар юкланмоқда..."; default->"⏳ Aktivnostlar yuklanmoqda..."; };
            case "act.no_data"           -> switch(l){ case "ru"->"📭 Данные не загружены."; case "en"->"📭 Data not loaded.";      case "uz_cyr"->"📭 Маълумот юкланмади.";        default->"📭 Ma'lumot yuklanmadi."; };
            case "act.none"              -> switch(l){ case "ru"->"📭 Заданий нет."; case "en"->"📭 No assignments.";              case "uz_cyr"->"📭 Топшириқлар йўқ.";           default->"📭 Topshiriqlar yo'q."; };
            case "sched.loading"         -> switch(l){ case "ru"->"⏳ Загружается расписание..."; case "en"->"⏳ Loading timetable..."; case "uz_cyr"->"⏳ Жадвал юкланмоқда...";       default->"⏳ Jadval yuklanmoqda..."; };
            case "sched.empty"           -> switch(l){ case "ru"->"📭 В этом семестре расписание не найдено."; case "en"->"📭 No timetable found for this semester."; case "uz_cyr"->"📭 Бу семестрда жадвал топилмади."; default->"📭 Bu semestrda jadval topilmadi."; };
            case "plan.title"            -> switch(l){ case "ru"->"Учебный план"; case "en"->"Study plan";                 case "uz_cyr"->"Ўқув режа";                     default->"O'quv reja"; };
            case "plan.loading"          -> switch(l){ case "ru"->"⏳ Загружается учебный план..."; case "en"->"⏳ Loading study plan..."; case "uz_cyr"->"⏳ Ўқув режа юкланмоқда...";  default->"⏳ O'quv reja yuklanmoqda..."; };
            case "plan.semester_suffix"  -> switch(l){ case "ru"->"семестр"; case "en"->"semester";                      case "uz_cyr"->"семестр";                       default->"semestr"; };
            case "plan.current"          -> switch(l){ case "ru"->"текущий"; case "en"->"current";                      case "uz_cyr"->"жорий";                         default->"joriy"; };
            case "plan.credits"          -> switch(l){ case "ru"->"кр"; case "en"->"cr";                           case "uz_cyr"->"кр";                            default->"kr"; };
            case "plan.gpa_hint"         -> switch(l){ case "ru"->"Выберите метод расчёта GPA 👇"; case "en"->"Choose how to calculate GPA 👇"; case "uz_cyr"->"GPA ҳисоблаш усулини танланг 👇"; default->"GPA hisoblash usulini tanlang 👇"; };
            case "plan.gpa_loading"      -> switch(l){ case "ru"->"⏳ Считаю GPA..."; case "en"->"⏳ Calculating GPA...";             case "uz_cyr"->"⏳ GPA ҳисобланмоқда...";        default->"⏳ GPA hisoblanmoqda..."; };
            case "plan.gpa_mode_graded"  -> switch(l){ case "ru"->"Только оценённые предметы"; case "en"->"Graded subjects only";    case "uz_cyr"->"Фақат баҳоланган фанлар";        default->"Faqat baholangan fanlar"; };
            case "plan.gpa_mode_with_zero"->switch(l){ case "ru"->"Текущий семестр с 0"; case "en"->"Current semester as 0";          case "uz_cyr"->"Жорий семестр 0 билан";          default->"Joriy semestr 0 bilan"; };
            case "plan.calc_credits"     -> switch(l){ case "ru"->"Учтено кредитов"; case "en"->"Credits counted";              case "uz_cyr"->"Ҳисобланган кредит";             default->"Hisoblangan kredit"; };
            case "plan.gpa_no_grades"    -> switch(l){ case "ru"->"Нет оценок для расчёта."; case "en"->"No grades to calculate.";      case "uz_cyr"->"Ҳисоблаш учун баҳолар топилмади."; default->"Hisoblash uchun baholar topilmadi."; };
            case "finals.title"          -> switch(l){ case "ru"->"Итоговые экзамены"; case "en"->"Final exams";            case "uz_cyr"->"Якуний имтиҳонлар";             default->"Yakuniy imtihonlar"; };
            case "finals.loading"        -> switch(l){ case "ru"->"⏳ Загружаются итоговые экзамены..."; case "en"->"⏳ Loading final exams..."; case "uz_cyr"->"⏳ Якуний имтиҳонлар юкланмоқда..."; default->"⏳ Yakuniy imtihonlar yuklanmoqda..."; };
            case "finals.empty"          -> switch(l){ case "ru"->"📭 В этом семестре итоговых экзаменов не найдено."; case "en"->"📭 No final exams found in this semester."; case "uz_cyr"->"📭 Бу семестрда якуний имтиҳон топилмади."; default->"📭 Bu semestrda yakuniy imtihon topilmadi."; };
            default -> key;
        };
    }

    // ─────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────

    /**
     * Идёт ли прямо сейчас диалог входа. Во время него сессию восстанавливать нельзя:
     * восстановление начинается с чистого cookie jar и убило бы наполовину пройденный
     * вход — например, ожидание пароля или кода подтверждения.
     */
    private boolean loggingIn(long userId) {
        String st = userState.getOrDefault(userId, "IDLE");
        return st.startsWith("WAIT_LOGIN") || st.startsWith("WAIT_PASSWORD") || st.startsWith("WAIT_ONEID");
    }

    boolean checkLogin(long chatId, long userId) {
        // Флаг входа живёт в памяти: после перезапуска или сна сервиса он пуст, хотя
        // cookie и токен OneID на месте. Сначала пробуем поднять сессию молча.
        if (!lmsService.isLoggedIn(userId) && (loggingIn(userId) || !lmsService.restoreSession(userId))) {
            send(chatId, tr(userId,
                    "⚠️ Сначала войдите в систему! /login",
                    "⚠️ Avval tizimga kiring! /login",
                    "⚠️ Аввал тизимга киринг! /login",
                    "⚠️ Sign in first! /login"), loginKeyboard(userId));
            return false;
        }
        return true;
    }

    /** Id висящего сообщения «⏳ …» для каждого чата. */
    private final Map<Long, Integer> progressMsg = new ConcurrentHashMap<>();

    /**
     * Сообщение о ходе работы: живёт ровно до следующего сообщения боту в этот чат.
     * Раньше все «⏳ Загружаю…» оставались в переписке навсегда.
     */
    void sendProgress(long chatId, String text) {
        dropProgress(chatId);
        Integer id = execSend(chatId, text, null);
        if (id != null) progressMsg.put(chatId, id);
    }

    /** Убирает «⏳ …», если оно ещё висит. Вызывается перед любой новой отправкой. */
    void dropProgress(long chatId) {
        Integer id = progressMsg.remove(chatId);
        if (id == null) return;
        try {
            DeleteMessage del = new DeleteMessage();
            del.setChatId(String.valueOf(chatId));
            del.setMessageId(id);
            execute(del);
        } catch (Exception ignored) {
            // Сообщение могли удалить вручную или оно старше 48 часов — не беда.
        }
    }

    void send(long chatId, String text, Object keyboard) {
        dropProgress(chatId);
        execSend(chatId, text, keyboard);
    }

    Integer execSend(long chatId, String text, Object keyboard) {
        try {
            SendMessage msg = new SendMessage();
            msg.setChatId(String.valueOf(chatId));
            msg.setText(text);
            msg.setParseMode("HTML");
            if (keyboard instanceof ReplyKeyboardMarkup k)       msg.setReplyMarkup(k);
            else if (keyboard instanceof InlineKeyboardMarkup k) msg.setReplyMarkup(k);
            Message sent = execute(msg);
            return sent != null ? sent.getMessageId() : null;
        } catch (Exception e) {
            System.err.println("[LmsBot] Send error: " + e.getMessage());
            return null;
        }
    }

    private void appendDeadlineGroup(StringBuilder sb, long userId, String title, List<DeadlineItem> group, long now) {
        if (group.isEmpty()) return;
        sb.append("<b>").append(esc(title)).append("</b>\n");
        for (DeadlineItem it : group) {
            Activity a = it.act;
            String datePart   = (a.getDeadline() != null && a.getDeadline().length() >= 16)
                    ? a.getDeadline().substring(0, 16) : (a.getDeadline() != null ? a.getDeadline() : "—");
            long diffMin = Math.max(0, (it.dl - now) / 60000L);
            String inStr = diffMin < 60
                    ? tr(userId, "через " + diffMin + " мин", diffMin + " min ichida", diffMin + " мин ичида", "in" + diffMin + " min")
                    : tr(userId, "через " + (diffMin / 60) + " ч", (diffMin / 60) + " soat ichida", (diffMin / 60) + " соат ичида", "in" + (diffMin / 60) + " h");
            sb.append("<blockquote>")
                    .append("📭 <b>").append(esc(it.course)).append("</b>\n")
                    .append("📝 ").append(esc(truncateTopic(a.getTask(), 60))).append("\n")
                    .append("⏰ <b>").append(esc(datePart)).append("</b> — <i>").append(esc(inStr)).append("</i>\n")
                    .append("🏆 ").append(orDash(a.getEarnedScore())).append("/").append(orDash(a.getMaxScore()))
                    .append("</blockquote>\n");
            String key = deadlineKey(it.courseId, it.dl, a.getTask());
            DeadlineMeta meta = new DeadlineMeta();
            meta.course = it.course; meta.task = a.getTask(); meta.deadline = datePart; meta.status = "📭";
            deadlineMeta.computeIfAbsent(userId, k -> new ConcurrentHashMap<>()).putIfAbsent(key, meta);
        }
        sb.append("\n");
    }

    private long parseActivityDeadlineTs(String deadline) {
        return uz.tuit.lmsbot.util.LmsDates.parseDeadline(deadline);
    }

    private long parseScheduleStartTs(String start) {
        return uz.tuit.lmsbot.util.LmsDates.parseScheduleStart(start);
    }

    private int detectCurrentSemester(Map<Integer, List<StudyPlanSubject>> bySemester) {
        int max = bySemester.keySet().stream().mapToInt(i -> i).max().orElse(0);
        for (int sem = 1; sem <= max; sem++) {
            List<StudyPlanSubject> list = bySemester.get(sem);
            if (list != null && list.stream().anyMatch(s -> s.getGrade() == null)) return sem;
        }
        return max;
    }

    /**
     * Ключ дедлайна — он же уезжает в callback_data кнопок напоминания, а там у
     * Telegram 64 байта. Раньше сюда клали заголовок задания, обрезанный по сорока
     * СИМВОЛАМ: кириллица занимает по два байта, и кнопка выходила за лимит — всё
     * сообщение с напоминанием молча не отправлялось. Теперь от заголовка берётся
     * короткий хэш, и длина ключа не зависит ни от языка, ни от названия.
     */
    private String deadlineKey(int courseId, long dl, String task) {
        return courseId + "-" + dl + "-" + shortHash(task);
    }

    private static String shortHash(String s) {
        if (s == null || s.isBlank()) return "0";
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(h).substring(0, 10);
        } catch (Exception e) {
            return "0";
        }
    }

    boolean markOnce(long userId, String key) {
        return sentReminders.computeIfAbsent(userId, k -> Collections.newSetFromMap(new ConcurrentHashMap<>())).add(key);
    }

    String truncateTopic(String topic, int maxLen) {
        if (topic == null) return "";
        String t = topic.trim();
        return t.length() <= maxLen ? t : t.substring(0, Math.max(0, maxLen - 1)) + "…";
    }

    private String orDash(String val) { return (val == null || val.isBlank()) ? "—" : val; }

    /**
     * Балл выставлен — значит работа принята, даже если LMS уже не отдаёт ссылку
     * на файл (в прошлых семестрах её нет). Без этого все старые задания
     * показывались как «дедлайн истёк, ничего не загружено».
     */
    private boolean isGraded(Activity a) {
        String raw = a.getEarnedScore();
        if (raw == null || raw.isBlank()) return false;
        try {
            return Double.parseDouble(raw.replace(',', '.').trim()) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** ✅ сдано/оценено, ❌ просрочено, 📭 ещё можно сдать. */
    private String activityStatusIcon(Activity a, boolean hasUpload, boolean expired) {
        if (hasUpload || isGraded(a)) return "✅";
        return expired ? "❌" : "📭";
    }

    /** Критерии оценивания отдельной строкой; пусто — если LMS их не дала. */
    private List<uz.tuit.lmsbot.util.Criteria.Item> criteriaItems(Activity a) {
        String c = a.getCriteria();
        if (c == null || c.isBlank()) return List.of();
        List<uz.tuit.lmsbot.util.Criteria.Item> out = new ArrayList<>();
        for (String line : c.split("\n")) if (!line.isBlank()) out.add(uz.tuit.lmsbot.util.Criteria.parse(line));
        return out;
    }

    /** Критерии в карточке задания: пункт на строку, баллы жирным, в конце — сумма. */
    private String criteriaLine(long userId, Activity a) {
        List<uz.tuit.lmsbot.util.Criteria.Item> items = criteriaItems(a);
        if (items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n📐 <b>")
                .append(esc(tr(userId, "Критерии оценивания", "Baholash mezonlari", "Баҳолаш мезонлари", "Grading criteria")))
                .append("</b>");
        double sum = 0;
        boolean allScored = true;
        for (uz.tuit.lmsbot.util.Criteria.Item it : items.subList(0, Math.min(items.size(), 12))) {
            sb.append("\n  ▫️ ").append(esc(truncateTopic(it.name(), 70)));
            if (it.points() != null) {
                sb.append(" — <b>").append(esc(it.points())).append("</b>");
                try { sum += Double.parseDouble(it.points()); } catch (NumberFormatException e) { allScored = false; }
            } else {
                allScored = false;
            }
        }
        if (allScored && items.size() > 1) {
            String total = sum == Math.rint(sum) ? String.valueOf((long) sum) : String.valueOf(sum);
            sb.append("\n  ").append(esc(tr(userId, "Итого", "Jami", "Жами", "Total"))).append(": <b>").append(total).append("</b>");
        }
        return sb.toString();
    }

    /** Критерии в длинных списках — одной строкой: «Выполнение 3 · Оформление 2». */
    private String criteriaShort(long userId, Activity a) {
        List<uz.tuit.lmsbot.util.Criteria.Item> items = criteriaItems(a);
        if (items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (uz.tuit.lmsbot.util.Criteria.Item it : items) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(it.name());
            if (it.points() != null) sb.append(" ").append(it.points());
        }
        return "\n📐 <i>" + esc(truncateTopic(sb.toString(), 120)) + "</i>";
    }

    String gradeIcon(int g) { return switch(g){ case 5->"🟢"; case 4->"🔵"; case 3->"🟡"; case 2->"🔴"; default->"⬜"; }; }

    private String gpaComment(long userId, double gpa) {
        if (gpa >= 4.5) return tr(userId, "Отличный результат! Так держать! 🚀", "A'lo natija! Davom eting! 🚀", "Аъло натижа! Давом этинг! 🚀", "Excellent result! Keep it up! 🚀");
        if (gpa >= 4.0) return tr(userId, "Хороший результат! 👍", "Yaxshi natija! 👍", "Яхши натижа! 👍", "Good result! 👍");
        if (gpa >= 3.5) return tr(userId, "Выше среднего.", "O'rtacha yaxshi natija.", "Ўртачадан юқори.", "Above average.");
        if (gpa >= 3.0) return tr(userId, "Средний результат, есть куда расти.", "O'rtacha natija, yaxshilash mumkin.", "Ўртача натижа, яхшилаш мумкин.", "Average result, room to grow.");
        return tr(userId, "Слабый результат, нужно больше усилий! 💪", "Past natija, ko'proq harakat kerak! 💪", "Паст натижа, кўпроқ ҳаракат керак! 💪", "Weak result, more effort needed! 💪");
    }

    /**
     * Тип занятия приходит из LMS на её языке, а не на языке бота,
     * поэтому лекцию опознаём по всем трём написаниям.
     */
    private boolean isLecture(String type) {
        if (type == null) return false;
        String t = type.toLowerCase();
        return t.contains("лек")        // Лекция / Маъруза(ru-вёрстка)
            || t.contains("lecture")
            || t.contains("ma'ruza") || t.contains("maruza") || t.contains("маъруза");
    }

    private String langLabel(String lang) {
        return switch (lang) { case "ru" -> "Русский"; case "uz_cyr" -> "Ўзбекча (кирилл)"; case "en" -> "English"; default -> "O'zbekcha (lotin)"; };
    }

    private void setUserLang(long userId, String lang) {
        userLang.put(userId, lang);
        UserSettings s = userSettings.computeIfAbsent(userId, k -> settingsStore.load(userId));
        s.lang = lang;
        settingsStore.save(userId, s);
    }

    private String minutesToLabelRu(int m) {
        return switch(m){ case 1->"1 минута"; case 30->"30 минут"; case 60->"1 час"; case 180->"3 часа"; case 300->"5 часов"; default->m+" мин"; };
    }
    private String minutesToLabelUzLat(int m) {
        return switch(m){ case 1->"1 minut"; case 30->"30 minut"; case 60->"1 soat"; case 180->"3 soat"; case 300->"5 soat"; default->m+" min"; };
    }
    private String minutesToLabelEn(int m) {
        return switch(m){ case 1->"1 minute"; case 30->"30 minutes"; case 60->"1 hour"; case 180->"3 hours"; case 300->"5 hours"; default->m+" min"; };
    }
    private String minutesToLabelUzCyr(int m) {
        return switch(m){ case 1->"1 минут"; case 30->"30 минут"; case 60->"1 соат"; case 180->"3 соат"; case 300->"5 соат"; default->m+" мин"; };
    }

    int getDefaultSemesterId(long userId) {
        return lmsService.getCurrentSemesterId(userId);
    }

    String esc(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Меню команд рядом с полем ввода Telegram берёт из setMyCommands, а не из
     * наших клавиатур, и хранит отдельный список на каждый language_code.
     * Отдельного кода для узбекской кириллицы у Telegram нет, поэтому для неё
     * работает список по умолчанию (латиница).
     */
    public void applyBotCommands() {
        publishCommands(null, "uz_lat");   // список по умолчанию
        publishCommands("uz", "uz_lat");
        publishCommands("ru", "ru");
        publishCommands("en", "en");
        publishDescriptions();
    }

    private void publishCommands(String languageCode, String lang) {
        List<BotCommand> commands = List.of(
                botCommand("start",   lang, "Перезапустить бота",  "Botni qayta ishga tushirish", "Ботни қайта ишга тушириш", "Restart the bot"),
                botCommand("app",     lang, "Открыть приложение",  "Ilovani ochish",              "Иловани очиш", "Open the app"),
                botCommand("login",   lang, "Войти в LMS",         "LMS ga kirish",               "LMS га кириш", "Sign in to LMS"),
                botCommand("courses", lang, "Мои предметы",        "Mening fanlarim",             "Менинг фанларим", "My subjects"),
                botCommand("logout",  lang, "Выйти",               "Chiqish",                     "Чиқиш", "Sign out")
        );
        try {
            SetMyCommands.SetMyCommandsBuilder b = SetMyCommands.builder()
                    .commands(commands)
                    .scope(new BotCommandScopeDefault());
            if (languageCode != null) b.languageCode(languageCode);
            execute(b.build());
        } catch (Exception e) {
            System.err.println("[LmsBot] setMyCommands(" + languageCode + "): " + e.getMessage());
        }
    }

    private BotCommand botCommand(String cmd, String lang, String ru, String uzLat, String uzCyr, String en) {
        String desc = switch (lang) { case "ru" -> ru; case "uz_cyr" -> uzCyr; case "en" -> en; default -> uzLat; };
        return new BotCommand(cmd, desc);
    }

    // ─────────────────────────────────────────────
    //  MINI APP BRIDGE
    //  Мини-приложение работает с тем же userId и той же LMS-сессией,
    //  что и бот; отсюда оно берёт язык, сбрасывает кэши и шлёт файлы в чат.
    // ─────────────────────────────────────────────

    public uz.tuit.lmsbot.service.SemesterSchedule semesterSchedule() {
        return semesterSchedule;
    }

    /** Язык пользователя или null, если он его ещё ни разу не выбирал. */
    public String appLang(long userId) {
        return hasLang(userId) ? lang(userId) : null;
    }

    public void appSetLang(long userId, String lang) {
        setUserLang(userId, lang);
    }

    /**
     * Вход через приложение: сбрасываем кэши старой сессии и запоминаем чат,
     * чтобы напоминания о парах, НБ и дедлайнах приходили и таким пользователям.
     * В личке с ботом chatId совпадает с userId.
     */
    public void appLoggedIn(long userId) {
        resetUserCaches(userId);
        lastChatId.putIfAbsent(userId, userId);
    }

    public void appTouched(long userId) {
        if (lmsService.isLoggedIn(userId)) lastChatId.putIfAbsent(userId, userId);
    }

    public void appLoggedOut(long userId) {
        resetUserCaches(userId);
        userLogin.remove(userId);
    }

    /** Семестр, выбранный в приложении, — по нему бот считает напоминания. */
    public void appSemester(long userId, int semesterId) {
        if (semesterId > 0) rememberSemester(userId, semesterId);
    }

    public void appSendDocument(long userId, File file, String filename) throws Exception {
        SendDocument doc = new SendDocument();
        doc.setChatId(String.valueOf(userId));
        doc.setDocument(new InputFile(file, filename));
        execute(doc);
    }

    public void appSendPhoto(long userId, File file, String filename) throws Exception {
        SendPhoto p = new SendPhoto();
        p.setChatId(String.valueOf(userId));
        p.setPhoto(new InputFile(file, filename));
        execute(p);
    }

    private volatile String webAppUrl;

    /**
     * Кнопка меню слева от поля ввода открывает мини-приложение во всех чатах.
     * Telegram принимает только https, поэтому без публичного адреса ничего не ставим.
     */
    public void applyWebApp(String url) {
        if (url == null || !url.startsWith("https://")) return;
        webAppUrl = url;
        try {
            execute(org.telegram.telegrambots.meta.api.methods.menubutton.SetChatMenuButton.builder()
                    .menuButton(org.telegram.telegrambots.meta.api.objects.menubutton.MenuButtonWebApp.builder()
                            .text("LMS")
                            .webAppInfo(new org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo(url))
                            .build())
                    .build());
            System.out.println("📱 Mini App: " + url);
        } catch (Exception e) {
            System.err.println("[LmsBot] setChatMenuButton: " + e.getMessage());
        }
    }

    /** Inline-кнопка «Интерактивный LMS»; null — публичного адреса приложения нет. */
    private InlineKeyboardMarkup appKeyboard(long userId) {
        if (webAppUrl == null) return null;
        InlineKeyboardButton btn = new InlineKeyboardButton();
        btn.setText(tr(userId, "🎓 Интерактивный LMS", "🎓 Interaktiv LMS", "🎓 Интерактив LMS", "🎓 Interactive LMS"));
        btn.setWebApp(new org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo(webAppUrl));
        return markup(List.of(List.of(btn)));
    }

    /** Сообщение с кнопкой запуска приложения (команда /app). */
    private void sendAppButton(long chatId, long userId) {
        InlineKeyboardMarkup kb = appKeyboard(userId);
        if (kb == null) {
            send(chatId, tr(userId,
                    "📱 Приложение пока недоступно.",
                    "📱 Ilova hozircha mavjud emas.",
                    "📱 Илова ҳозирча мавжуд эмас.",
                    "📱 The app is not available yet."), null);
            return;
        }
        send(chatId, tr(userId,
                "🎓 <b>Интерактивный LMS</b>\n\nВсе разделы — предметы, расписание, дедлайны, оценки — в одном приложении.",
                "🎓 <b>Interaktiv LMS</b>\n\nBarcha bo'limlar — fanlar, jadval, deadlinelar, baholar — bitta ilovada.",
                "🎓 <b>Интерактив LMS</b>\n\nБарча бўлимлар — фанлар, жадвал, дедлайнлар, баҳолар — битта иловада.",
                "🎓 <b>Interactive LMS</b>\n\nEvery section — subjects, timetable, deadlines, grades — in one app."),
                kb);
    }

    /**
     * После входа — отдельное сообщение с кнопкой приложения. Оно остаётся в чате:
     * reply-клавиатура меню и inline-кнопка в одном сообщении не уживаются, поэтому их два.
     */
    private void sendAppAfterLogin(long chatId, long userId) {
        InlineKeyboardMarkup kb = appKeyboard(userId);
        if (kb == null) return;
        send(chatId, tr(userId,
                "🎓 <b>Интерактивный LMS</b> — расписание на весь семестр, дедлайны, оценки, GPA и загрузка работ в удобном приложении 👇",
                "🎓 <b>Interaktiv LMS</b> — butun semestr jadvali, deadlinelar, baholar, GPA va ishlarni yuklash qulay ilovada 👇",
                "🎓 <b>Интерактив LMS</b> — бутун семестр жадвали, дедлайнлар, баҳолар, GPA ва ишларни юклаш қулай иловада 👇",
                "🎓 <b>Interactive LMS</b> — the whole-semester timetable, deadlines, grades, GPA and work uploads in a handy app 👇"),
                kb);
    }

    // ─────────────────────────────────────────────
    //  STUDENTS REGISTRY (панель администратора)
    // ─────────────────────────────────────────────

    public uz.tuit.lmsbot.service.StudentRegistry students() {
        return students;
    }

    private void noteUser(org.telegram.telegrambots.meta.api.objects.User u) {
        if (u == null) return;
        String name = ((u.getFirstName() == null ? "" : u.getFirstName()) + " "
                + (u.getLastName() == null ? "" : u.getLastName())).trim();
        tgIdentity.put(u.getId(), new String[]{name, u.getUserName()});
        if (lmsService.isLoggedIn(u.getId())) students.onSeen(u.getId(), name, u.getUserName());
    }

    /** Успешный вход в боте: запись в списке студентов и кнопка приложения. */
    private void onBotLogin(long chatId, long userId) {
        String[] id = tgIdentity.getOrDefault(userId, new String[]{null, null});
        students.onLogin(userId, userLogin.get(userId), id[0], id[1]);
        sendAppAfterLogin(chatId, userId);
    }

    // ─────────────────────────────────────────────
    //  STATE BACKUP
    //  Диск Render стирается при деплое и пробуждении: без копии каждый деплой
    //  разлогинивал всех и сбрасывал языки. Копия — зашифрованный документ,
    //  закреплённый в чате администратора; одно сообщение, файл в нём подменяется.
    // ─────────────────────────────────────────────

    private static final String LEGACY_STUDENTS = "students.json";
    /** Копию обновляем при входах/выходах и не реже, чем раз в полчаса. */
    private static final long STATE_MAX_AGE_MS = 30L * 60 * 1000;
    private volatile Integer backupMessageId;
    private volatile String lastStateFingerprint = "";
    private volatile long lastStateBackupTs;
    /**
     * Копию отправляем только после успешного восстановления: если при старте Telegram
     * не ответил, пустой диск не должен затереть хорошую копию.
     */
    private volatile boolean stateRestored;

    private void tickStateBackup() {
        if (!stateRestored) {
            restoreState();
            return;
        }
        try {
            students.flush();
            String fp = stateBackup.fingerprint();
            long now = System.currentTimeMillis();
            if (fp.equals(lastStateFingerprint) && now - lastStateBackupTs < STATE_MAX_AGE_MS) return;
            uploadState();
            lastStateFingerprint = fp;
            lastStateBackupTs = now;
        } catch (Exception e) {
            System.err.println("[LmsBot] state backup: " + e.getMessage());
        }
    }

    private synchronized void uploadState() throws Exception {
        byte[] data = stateBackup.pack();
        String chat = String.valueOf(AppConfig.primaryAdmin());
        if (backupMessageId != null) {
            try {
                org.telegram.telegrambots.meta.api.objects.media.InputMediaDocument media =
                        new org.telegram.telegrambots.meta.api.objects.media.InputMediaDocument();
                media.setMedia(new java.io.ByteArrayInputStream(data), uz.tuit.lmsbot.service.StateBackup.FILE_NAME);
                media.setCaption(backupCaption());
                execute(org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageMedia.builder()
                        .chatId(chat).messageId(backupMessageId).media(media).build());
                return;
            } catch (Exception e) {
                // Сообщение удалили вручную — создаём новое.
                backupMessageId = null;
            }
        }
        SendDocument doc = new SendDocument();
        doc.setChatId(chat);
        doc.setDocument(new InputFile(new java.io.ByteArrayInputStream(data), uz.tuit.lmsbot.service.StateBackup.FILE_NAME));
        doc.setCaption(backupCaption());
        doc.setDisableNotification(true);
        Message sent = execute(doc);
        backupMessageId = sent.getMessageId();
        org.telegram.telegrambots.meta.api.methods.pinnedmessages.PinChatMessage pin =
                new org.telegram.telegrambots.meta.api.methods.pinnedmessages.PinChatMessage(chat, sent.getMessageId());
        pin.setDisableNotification(true);
        execute(pin);
    }

    private String backupCaption() {
        return "🗂 Резервная копия бота: сессии LMS, языки, список студентов (зашифровано). "
                + "Не удаляйте и не открепляйте — после деплоя и перезапуска бот восстанавливается отсюда.";
    }

    /**
     * При старте, до подключения к Telegram: поднять состояние из закреплённой копии,
     * затем в фоне проверить восстановленные сессии LMS — живые сразу получают напоминания.
     */
    public void restoreState() {
        try {
            org.telegram.telegrambots.meta.api.objects.Chat chat = execute(
                    new org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat(String.valueOf(AppConfig.primaryAdmin())));
            Message pinned = chat.getPinnedMessage();
            if (pinned != null && pinned.hasDocument()) {
                String name = pinned.getDocument().getFileName();
                org.telegram.telegrambots.meta.api.methods.GetFile gf = new org.telegram.telegrambots.meta.api.methods.GetFile();
                gf.setFileId(pinned.getDocument().getFileId());
                byte[] data;
                try (java.io.InputStream in = downloadFileAsStream(execute(gf))) {
                    data = in.readAllBytes();
                }
                if (uz.tuit.lmsbot.service.StateBackup.FILE_NAME.equals(name)) {
                    backupMessageId = pinned.getMessageId();
                    int n = stateBackup.unpack(data);
                    students.reloadFromDisk();
                    System.out.println("♻️ Состояние восстановлено из резервной копии: файлов " + n);
                } else if (LEGACY_STUDENTS.equals(name)) {
                    students.restore(data);
                }
            }
        } catch (Exception e) {
            System.err.println("[LmsBot] restoreState: " + e.getMessage());
            return;
        }
        stateRestored = true;
        lastStateFingerprint = stateBackup.fingerprint();
        lastStateBackupTs = System.currentTimeMillis();

        List<Long> users = stateBackup.sessionUsers();
        for (Long uid : users) {
            executor.submit(() -> {
                if (lmsService.restoreSession(uid)) lastChatId.putIfAbsent(uid, uid);
            });
        }
        if (!users.isEmpty()) System.out.println("♻️ Проверяю сохранённые сессии LMS: " + users.size());
    }

    public void shutdown() {
        // Финальная копия перед остановкой: деплой или засыпание не должны разлогинить пользователей.
        try {
            students.flush();
            if (stateRestored) uploadState();
        } catch (Exception e) {
            System.err.println("[LmsBot] final state backup: " + e.getMessage());
        }
        executor.shutdown();
        scheduler.shutdown();
        try { if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow(); }
        catch (InterruptedException e) { executor.shutdownNow(); }
    }
}