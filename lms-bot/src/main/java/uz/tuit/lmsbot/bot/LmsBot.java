package uz.tuit.lmsbot.bot;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import uz.tuit.lmsbot.config.AppConfig;
import uz.tuit.lmsbot.model.*;
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
    private static final boolean TEST_MODE = true;

    /** Интервал напоминания списка дедлайнов: тест=1мин, прод=6ч */
    private static final long DEADLINES_LIST_INTERVAL_MS = TEST_MODE
            ? 1L * 60 * 1000
            : 6L * 60 * 60 * 1000;

    /** Интервал напоминания срочного дедлайна по умолчанию (минуты): тест=1, прод=180 */
    private static final int URGENT_DEADLINE_DEFAULT_INTERVAL_MIN = TEST_MODE ? 1 : 180;

    /** Окно проверки напоминания о паре (±минут от старта): тест=широкое, прод=узкое */
    private static final int PAIR_REMINDER_WINDOW_MIN = TEST_MODE ? 2 : 1;

    /** За сколько минут до пары напоминать */
    private static final int PAIR_REMINDER_BEFORE_MIN = 10;

    // ─────────────────────────────────────────────

    private final AppConfig config;
    private final LmsService lmsService;

    private final Map<Long, String>       userState    = new ConcurrentHashMap<>();
    private final Map<Long, String>       tempLogin    = new ConcurrentHashMap<>();
    private final Map<Long, String>       userLogin    = new ConcurrentHashMap<>();
    private final Map<Long, String>       tmpOldPass   = new ConcurrentHashMap<>();
    private final Map<Long, String>       tmpNewPass   = new ConcurrentHashMap<>();
    private final Map<Long, List<Course>> userCourses  = new ConcurrentHashMap<>();
    private final Map<Long, Integer>      userSemester = new ConcurrentHashMap<>();
    private final Map<Long, String>       userLang     = new ConcurrentHashMap<>();
    private final Map<Long, Map<Integer, Map<String, List<CalendarEntry>>>> userCalendars = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, CalendarEntry.FileAttachment>>      pendingFiles  = new ConcurrentHashMap<>();
    private final Map<Long, Map<Integer, List<Activity>>> userActivities = new ConcurrentHashMap<>();
    private final Map<Long, Long>                         lastChatId     = new ConcurrentHashMap<>();

    private static class PendingUpload {
        int courseId;
        String activityId;
        int activityIndex;
    }

    private final ExecutorService executor = Executors.newFixedThreadPool(20);
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
                send(chatId, tr(userId, "❌ Загрузка отменена.", "❌ Yuklash bekor qilindi.", "❌ Юклаш бекор қилинди."), null);
            } else {
                send(chatId, tr(userId,
                        "⚠️ Пожалуйста, отправьте файл (doc, pdf, jpg, zip...) или /cancel.",
                        "⚠️ Iltimos, fayl yuboring (doc, pdf, jpg, zip...) yoki /cancel yozing.",
                        "⚠️ Илтимос, файл юборинг (doc, pdf, jpg, zip...) ёки /cancel ёзинг."), null);
            }
            return;
        }

        if (!msg.hasText()) return;
        String text = msg.getText().trim();

        if (handleGlobalCommands(chatId, userId, text, msg.getFrom().getFirstName())) return;

        if ("WAIT_OLD_PASSWORD".equals(state)) {
            tmpOldPass.put(userId, text);
            userState.put(userId, "WAIT_NEW_PASSWORD");
            send(chatId, tr(userId,
                    "🔒 <b>Новый пароль</b>:",
                    "🔒 <b>Yangi parol</b>:",
                    "🔒 <b>Янги парол</b>:"), null);
            return;
        }
        if ("WAIT_NEW_PASSWORD".equals(state)) {
            tmpNewPass.put(userId, text);
            userState.put(userId, "WAIT_NEW_PASSWORD_CONFIRM");
            send(chatId, tr(userId,
                    "🔒 <b>Подтвердите новый пароль</b>:",
                    "🔒 <b>Yangi parolni tasdiqlang</b>:",
                    "🔒 <b>Янги паролни тасдиқланг</b>:"), null);
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
                        "❌ <b>Хатолик</b>\n\n<blockquote>Пароллар мос эмас.</blockquote>"), null);
                return;
            }
            send(chatId, tr(userId,
                    "⏳ <b>Меняю пароль...</b>",
                    "⏳ <b>Parol o'zgartirilmoqda...</b>",
                    "⏳ <b>Парол ўзгартирилмоқда...</b>"), null);
            final String oldFinal = oldP;
            final String newFinal = newPNorm;
            executor.submit(() -> {
                boolean ok = lmsService.changePassword(userId, oldFinal, newFinal, conf);
                if (ok) {
                    send(chatId, tr(userId,
                            "✅ <b>Пароль изменён</b>",
                            "✅ <b>Parol o'zgartirildi</b>",
                            "✅ <b>Парол ўзгартирилди</b>"), null);
                } else {
                    send(chatId, tr(userId,
                            "❌ <b>Не удалось изменить пароль</b>\n\n<blockquote>Проверьте старый пароль и требования к новому.</blockquote>",
                            "❌ <b>Parol o'zgartirib bo'lmadi</b>\n\n<blockquote>Eski parol va yangi parol talablarini tekshiring.</blockquote>",
                            "❌ <b>Парол ўзгартириб бўлмади</b>\n\n<blockquote>Эски парол ва янги парол талабларини текширинг.</blockquote>"), null);
                }
            });
            return;
        }

        if ("WAIT_LOGIN".equals(state)) {
            tempLogin.put(userId, text);
            userState.put(userId, "WAIT_PASSWORD");
            send(chatId, tr(userId,
                    "🔐 Теперь введите ваш пароль:",
                    "🔐 Endi parolingizni kiriting:",
                    "🔐 Энди паролингизни киритинг:"), null);
            return;
        }
        if ("WAIT_PASSWORD".equals(state)) {
            userState.put(userId, "IDLE");
            handleLogin(chatId, userId, tempLogin.remove(userId), text);
            return;
        }

        switch (text) {
            case "/start":
                if (!userLang.containsKey(userId)) sendLanguageChoice(chatId);
                else sendWelcome(chatId, userId, msg.getFrom().getFirstName());
                break;
            case "/login":
            case "🔑 Kirish":
            case "🔑 Войти":
            case "🔑 Кириш":             askForLogin(chatId, userId); break;
            case "/logout":
            case "🚪 Chiqish":
            case "🚪 Выйти":
            case "🚪 Чиқиш":              handleLogout(chatId, userId); break;
            case "/courses":
            case "📚 Mening fanlarim":
            case "📚 Мои предметы":
            case "📚 Менинг фанларим":    showCourses(chatId, userId, getDefaultSemesterId()); break;
            case "📅 Dars jadvali":
            case "📅 Расписание":
            case "📅 Дарс жадвали":       showSchedule(chatId, userId, getDefaultSemesterId()); break;
            case "📖 O'quv reja":
            case "📖 Учебный план":
            case "📖 Ўқув режа":          showStudyPlanFull(chatId, userId); break;
            case "🏆 Yakuniy imtihon":
            case "🏆 Итоговый экзамен":
            case "🏆 Якуний имтиҳон":     showFinals(chatId, userId, getDefaultSemesterId()); break;
            case "👤 Profil":
            case "👤 Профиль":
            case "👤 Профил":             showProfile(chatId, userId); break;
            case "⚙️ Sozlamalar":
            case "⚙️ Настройки":
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
        int  messageId = update.getCallbackQuery().getMessage().getMessageId();
        String data    = update.getCallbackQuery().getData();

        answerCallback(update.getCallbackQuery().getId());

        if (data.startsWith("lang_")) {
            String lang = data.substring("lang_".length());
            userLang.put(userId, lang);
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
            int semId = userSemester.getOrDefault(userId, getDefaultSemesterId());
            showCourses(chatId, userId, semId);
        } else if ("change_semester".equals(data)) {
            edit(chatId, messageId, tr(userId, "📅 Выберите семестр:", "📅 Semestrni tanlang:", "📅 Семестрни танланг:"), semesterKeyboard());
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
        } else if ("settings".equals(data)) {
            showSettings(chatId, userId);
        } else if ("settings_lang".equals(data)) {
            edit(chatId, messageId, tr(userId, "🌐 <b>Выберите язык</b>:", "🌐 <b>Tilni tanlang</b>:", "🌐 <b>Тилни танланг</b>:"), langChoiceMarkup());
        } else if ("settings_password".equals(data)) {
            startChangePassword(chatId, userId);
        } else if ("settings_logout".equals(data)) {
            handleLogout(chatId, userId);
        } else if (data.startsWith("setlang_")) {
            String lang = data.substring("setlang_".length());
            setUserLang(userId, lang);
            send(chatId, tr(userId,
                    "✅ <b>Язык изменён</b>",
                    "✅ <b>Til o'zgartirildi</b>",
                    "✅ <b>Тил ўзгартирилди</b>"), null);
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
                        "✅ <b>Эслатма оралиғи ўзгартирилди</b>")
                        + "\n\n<blockquote>"
                        + tr(userId,
                        "Теперь: <b>" + minutesToLabelRu(minutes) + "</b>",
                        "Endi: <b>" + minutesToLabelUzLat(minutes) + "</b>",
                        "Энди: <b>" + minutesToLabelUzCyr(minutes) + "</b>")
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

    private void edit(long chatId, int messageId, String text, InlineKeyboardMarkup markup) {
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

    private void sendWelcome(long chatId, long userId, String firstName) {
        String lang = userLang.getOrDefault(userId, "uz_lat");
        String text = switch (lang) {
            case "ru"     -> "👋 <b>Привет, " + esc(firstName) + "!</b>\n\n"
                    + "🎓 Добро пожаловать в <b>TUIT LMS Bot</b>!\n\nЧтобы начать, войди в систему 👇";
            case "uz_cyr" -> "👋 <b>Assalomu alaykum, " + esc(firstName) + "!</b>\n\n"
                    + "🎓 <b>TUIT LMS Bot</b>га хуш келибсиз!\n\nБошлаш учун тизимга киринг 👇";
            default       -> "👋 <b>Assalomu alaykum, " + esc(firstName) + "!</b>\n\n"
                    + "🎓 <b>TUIT LMS Bot</b>ga xush kelibsiz!\n\nBoshlash uchun tizimga kiring 👇";
        };
        send(chatId, text, loginKeyboard(userId));
    }

    private void askForLogin(long chatId, long userId) {
        userState.put(userId, "WAIT_LOGIN");
        send(chatId, tr(userId,
                "👤 <b>Введите ваш LMS логин</b>:\n\nНапример: <code>1bk27748</code>",
                "👤 <b>LMS loginingizni</b> kiriting:\n\nMasalan: <code>1bk27748</code>",
                "👤 <b>LMS логинингизни</b> киритинг:\n\nМасалан: <code>1bk27748</code>"), null);
    }

    private void handleLogin(long chatId, long userId, String login, String password) {
        send(chatId, tr(userId,
                "⏳ Входим в систему...",
                "⏳ Tizimga kirilmoqda...",
                "⏳ Тизимга кирилмоқда..."), null);
        executor.submit(() -> {
            boolean ok = lmsService.login(userId, login, password);
            if (ok) {
                if (login != null && !login.isBlank()) userLogin.put(userId, login.trim());
                send(chatId, tr(userId,
                                "✅ <b>Вы успешно вошли!</b>\n\nВыберите пункт из меню ниже 👇",
                                "✅ <b>Muvaffaqiyatli kirdingiz!</b>\n\nQuyidagi menyudan foydalaning 👇",
                                "✅ <b>Муваффақиятли кирдингиз!</b>\n\nҚуйидаги менюдан фойдаланинг 👇"),
                        mainMenuKeyboard(userId));
            } else {
                send(chatId, tr(userId,
                                "❌ <b>Неверный логин или пароль!</b>\n\nПопробуйте ещё раз.",
                                "❌ <b>Login yoki parol noto'g'ri!</b>\n\nQayta urinib ko'ring.",
                                "❌ <b>Логин ёки парол нотўғри!</b>\n\nҚайта уриниб кўринг."),
                        loginKeyboard(userId));
            }
        });
    }

    private void handleLogout(long chatId, long userId) {
        lmsService.logout(userId);
        userCourses.remove(userId);
        userSemester.remove(userId);
        userLogin.remove(userId);
        send(chatId, tr(userId,
                "👋 Вы вышли из системы.\n\nЧтобы войти снова: /login",
                "👋 Tizimdan chiqtingiz.\n\nQayta kirish: /login",
                "👋 Тизимдан чиқдингиз.\n\nҚайта кириш: /login"), loginKeyboard(userId));
    }

    private void sendMainMenu(long chatId, long userId) {
        send(chatId, tr(userId,
                "🏠 <b>Главное меню:</b>",
                "🏠 <b>Asosiy menyu:</b>",
                "🏠 <b>Асосий меню:</b>"), mainMenuKeyboard(userId));
    }

    // ─────────────────────────────────────────────
    //  COURSES
    // ─────────────────────────────────────────────

    private void showCourses(long chatId, long userId, int semesterId) {
        if (!checkLogin(chatId, userId)) return;
        send(chatId, t(userId, "courses.loading"), null);

        executor.submit(() -> {
            List<Course> courses = lmsService.getMyCourses(userId, semesterId);
            String semName = config.getSemesterMap().getOrDefault(semesterId, "Semester " + semesterId);

            if (courses.isEmpty()) {
                send(chatId, t(userId, "courses.empty_semester"), semesterKeyboard());
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
                        + tr(userId, "НБ", "NB", "НБ") + ": <b>" + c.getAttendance() + "</b>";
                msg.append("<blockquote>").append(inner).append("</blockquote>\n");
            }

            msg.append("\n📌 ").append(t(userId, "courses.total")).append(": <b>")
                    .append(courses.size()).append("</b> ")
                    .append(t(userId, "courses.subjects_suffix"));
            send(chatId, msg.toString(), coursesActionKeyboard(userId));
        });
    }

    // ─────────────────────────────────────────────
    //  COURSE SELECTION (edit in-place)
    // ─────────────────────────────────────────────

    private void editCourseSelection(long chatId, long userId, int messageId, String action) {
        List<Course> courses = userCourses.get(userId);
        int semesterId = userSemester.getOrDefault(userId, getDefaultSemesterId());

        if (courses == null || courses.isEmpty()) {
            edit(chatId, messageId,
                    tr(userId,
                            "⚠️ Сначала откройте «Мои предметы».",
                            "⚠️ Avval \"Mening fanlarim\" bo'limini oching.",
                            "⚠️ Аввал «Менинг фанларим» бўлимини очинг."),
                    null);
            return;
        }
        String title = switch (action) {
            case "attend"   -> tr(userId, "📊 <b>Посещаемость</b> — выберите предмет:", "📊 <b>Davomat</b> — fan tanlang:", "📊 <b>Давомат</b> — фан танланг:");
            case "calendar" -> tr(userId, "📆 <b>План занятий</b> — выберите предмет:", "📆 <b>Dars rejasi</b> — fan tanlang:", "📆 <b>Дарс режаси</b> — фан танланг:");
            default         -> tr(userId, "📋 <b>Активности</b> — выберите предмет:", "📋 <b>Aktivnosti</b> — fan tanlang:", "📋 <b>Активностлар</b> — фан танланг:");
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
                rec.append("Лекция".equals(r.getType()) ? "📖" : "🔬")
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
        send(chatId, t(userId, "act.loading"), null);

        executor.submit(() -> {
            CourseSummary summary = lmsService.getActivities(userId, courseId);
            if (summary == null) { send(chatId, t(userId, "act.no_data"), null); return; }

            List<Activity> activities = summary.getActivities();
            userActivities.computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                    .put(courseId, activities != null ? activities : Collections.emptyList());

            StringBuilder sb = new StringBuilder();
            sb.append("📊 <b>").append(tr(userId, "Результаты", "Natijalar", "Натижалар")).append("</b>\n<blockquote>");
            sb.append("🏆 ").append(tr(userId, "Балл", "Ball", "Балл")).append(": <b>").append(orDash(summary.getEarned()))
                    .append("</b> / <b>").append(orDash(summary.getMaxScore())).append("</b>\n");
            sb.append("📈 ").append(tr(userId, "Прогресс", "Muvaffaqiyat", "Муваффақият")).append(": <b>").append(orDash(summary.getProgress())).append("</b>\n");
            sb.append("🎓 ").append(tr(userId, "Оценка", "Baho", "Баҳо")).append(": <b>").append(orDash(summary.getGrade())).append("</b>");
            sb.append("</blockquote>\n\n");

            if (activities == null || activities.isEmpty()) {
                sb.append(t(userId, "act.none"));
                send(chatId, sb.toString(), null);
                return;
            }

            sb.append("📋 <b>").append(tr(userId, "Задания", "Topshiriqlar", "Топшириқлар"))
                    .append("</b>: <b>").append(activities.size()).append("</b>\n\n");

            long now = System.currentTimeMillis();
            for (int i = 0; i < activities.size(); i++) {
                Activity a = activities.get(i);
                String datePart = (a.getDeadline() != null && a.getDeadline().length() >= 10)
                        ? a.getDeadline().substring(0, 10) : "—";
                long dl = parseActivityDeadlineTs(a.getDeadline());
                boolean expired  = dl > 0 && dl <= now;
                boolean hasUpload = a.getUploadedFileUrl() != null && !a.getUploadedFileUrl().isBlank();

                String statusIcon = hasUpload ? "✅" : (expired ? "❌" : "📭");
                String earned = orDash(a.getEarnedScore());
                String max    = orDash(a.getMaxScore());
                String teacher = (a.getTeacher() != null && !a.getTeacher().isBlank()) ? a.getTeacher() : "—";
                String typeRaw  = a.getType() != null ? a.getType().toLowerCase() : "";
                String typeIcon = typeRaw.contains("лек") || typeRaw.contains("lecture") ? "📖" : "🔬";

                sb.append("<blockquote>")
                        .append(statusIcon).append(" ").append(typeIcon).append(" <b>").append(i + 1).append(".</b> ")
                        .append(esc(truncateTopic(a.getTask(), 40))).append("\n")
                        .append("⏰ <b>").append(esc(datePart)).append("</b>")
                        .append(" | 🏆 ").append(earned).append("/").append(max).append("\n")
                        .append("👨‍🏫 ").append(esc(teacher))
                        .append("</blockquote>\n");
            }

            sb.append("\n<i>").append(tr(userId,
                    "Для выбора задания 👇",
                    "Topshiriqni tanlash uchun 👇",
                    "Топшириқни танлаш учун 👇")).append("</i>");

            List<List<InlineKeyboardButton>> allRows = new ArrayList<>();
            allRows.add(List.of(inlineBtn(
                    tr(userId, "⏰ Выбрать по дедлайну", "⏰ Deadline bo'yicha tanlash", "⏰ Дедлайн бўйича танлаш"),
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
            case "uploaded"   -> tr(userId, "📥 <b>Загруженные</b>", "📥 <b>Yuklangan</b>", "📥 <b>Юкланган</b>");
            case "uploadable" -> tr(userId, "⬆️ <b>Загрузить</b>", "⬆️ <b>Yuklash</b>", "⬆️ <b>Юклаш</b>");
            default           -> tr(userId, "📋 <b>Задания</b>", "📋 <b>Topshiriqlar</b>", "📋 <b>Топшириқлар</b>");
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
                    ? tr(userId, "📭 Нет заданий для загрузки (все загружены или срок истёк).", "📭 Yuklash uchun topshiriqlar yo'q.", "📭 Юклаш учун топшириқлар йўқ.")
                    : t(userId, "act.none");
            edit(chatId, messageId, empty, null);
            return;
        }

        List<InlineKeyboardButton> filterRow = new ArrayList<>();
        filterRow.add(inlineBtn(tr(userId, "📋 Задания", "📋 Topshiriqlar", "📋 Топшириқлар"), "act_filter_all_" + courseId));
        filterRow.add(inlineBtn(tr(userId, "📥 Загруженные", "📥 Yuklangan", "📥 Юкланган"), "act_filter_uploaded_" + courseId));
        filterRow.add(inlineBtn(tr(userId, "⬆️ Загрузить", "⬆️ Yuklash", "⬆️ Юклаш"), "act_filter_uploadable_" + courseId));
        rows.add(filterRow);

        edit(chatId, messageId, title + "\n\n" + tr(userId, "Выберите 👇", "Tanlang 👇", "Танланг 👇"), markup(rows));
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
            String statusIcon = hasUpload ? "✅" : (expired ? "❌" : "📭");
            String datePart   = (a.getDeadline() != null && a.getDeadline().length() >= 10) ? a.getDeadline().substring(0, 10) : "—";
            String earned     = orDash(a.getEarnedScore());
            String max        = orDash(a.getMaxScore());
            String typeRaw    = a.getType() != null ? a.getType().toLowerCase() : "";
            String typeIcon   = typeRaw.contains("лек") || typeRaw.contains("lecture") ? "📖" : "🔬";
            String btnText    = statusIcon + " " + typeIcon + " " + datePart + " | " + truncateTopic(a.getTask(), 22) + " | " + earned + "/" + max;
            rows.add(List.of(inlineBtn(btnText, "act_pick_" + courseId + "_" + i)));
        }
        rows.add(List.of(inlineBtn(t(userId, "btn.back"), "ac_back_" + courseId)));

        String header = tr(userId,
                "⏰ <b>Задания по дедлайну</b>\n\n✅ Загружено  |  ❌ Истёк  |  📭 Не загружено\n📖 Лекция  |  🔬 Практика\n\nВыберите 👇",
                "⏰ <b>Deadline bo'yicha topshiriqlar</b>\n\n✅ Yuklangan  |  ❌ Muddat o'tgan  |  📭 Yuklanmagan\n📖 Leksiya  |  🔬 Amaliyot\n\nTanlang 👇",
                "⏰ <b>Дедлайн бўйича топшириқлар</b>\n\n✅ Юкланган  |  ❌ Муддат ўтган  |  📭 Юкланмаган\n📖 Маъруза  |  🔬 Амалиёт\n\nТанланг 👇");
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
                    "⚠️ Маълумот эскирган. Активностларни қайта очинг."), null);
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
                + "⏰ " + tr(userId, "Дедлайн", "Deadline", "Дедлайн") + ": <b>" + esc(a.getDeadline()) + "</b>\n"
                + "🏆 " + tr(userId, "Балл", "Ball", "Балл") + ": <b>" + earned + "</b> / <b>" + max + "</b>\n"
                + (hasUpload
                ? tr(userId, "✅ Загружено", "✅ Yuklangan", "✅ Юкланган")
                : tr(userId, "📭 Не загружено", "📭 Yuklanmagan", "📭 Юкланмаган"));

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();
        if (hasSample) row.add(inlineBtn(tr(userId, "📄 Задание", "📄 Topshiriq", "📄 Топшириқ"), "act_sample_" + courseId + "_" + index));
        if (hasUpload) row.add(inlineBtn(tr(userId, "📥 Моя загрузка", "📥 Yuklanganim", "📥 Юкланганим"), "act_uploaded_" + courseId + "_" + index));
        if (!row.isEmpty()) rows.add(row);

        if (!hasUpload && hasActId && deadlineOk)
            rows.add(List.of(inlineBtn(tr(userId, "⬆️ Загрузить", "⬆️ Yuklash", "⬆️ Юклаш"), "act_upload_" + courseId + "_" + index + "_" + a.getActivityId())));
        if (hasUpload && hasActId && deadlineOk)
            rows.add(List.of(inlineBtn(tr(userId, "🔄 Перезагрузить", "🔄 Qayta yuklash", "🔄 Қайта юклаш"), "act_upload_" + courseId + "_" + index + "_" + a.getActivityId())));

        rows.add(List.of(inlineBtn(t(userId, "btn.back"), "act_deadline_list_" + courseId)));
        edit(chatId, messageId, text, markup(rows));
    }

    // ─────────────────────────────────────────────
    //  SCHEDULE
    // ─────────────────────────────────────────────

    private void showSchedule(long chatId, long userId, int semesterId) {
        if (!checkLogin(chatId, userId)) return;
        send(chatId, t(userId, "sched.loading"), null);

        executor.submit(() -> {
            List<ScheduleEvent> events = lmsService.getSchedule(userId, semesterId);
            String semName = config.getSemesterMap().getOrDefault(semesterId, "Semester " + semesterId);

            if (events == null || events.isEmpty()) {
                send(chatId, t(userId, "sched.empty"), semesterScheduleKeyboard());
                return;
            }

            Map<String, List<ScheduleEvent>> byDate = new LinkedHashMap<>();
            for (ScheduleEvent ev : events)
                byDate.computeIfAbsent(ev.getStart().substring(0, 10), k -> new ArrayList<>()).add(ev);
            for (List<ScheduleEvent> list : byDate.values())
                list.sort(Comparator.comparing(ScheduleEvent::getStart));

            String[] dayNames = switch (lang(userId)) {
                case "ru"     -> new String[]{"","Понедельник","Вторник","Среда","Четверг","Пятница","Суббота","Воскресенье"};
                case "uz_cyr" -> new String[]{"","Душанба","Сешанба","Чоршанба","Пайшанба","Жума","Шанба","Якшанба"};
                default       -> new String[]{"","Dushanba","Seshanba","Chorshanba","Payshanba","Juma","Shanba","Yakshanba"};
            };
            String[] dowColors = {"","🟡","🟢","🔵","🟣","🟠","🔴","⚪"};

            StringBuilder schedMsg = new StringBuilder();
            schedMsg.append("📅 <b>").append(esc(semName)).append("</b>\n\n");

            for (Map.Entry<String, List<ScheduleEvent>> entry : byDate.entrySet()) {
                String dateStr = entry.getKey();
                List<ScheduleEvent> dayEvents = entry.getValue();
                String dayHeader;
                int dowIndex = 0;
                try {
                    int dow = java.time.LocalDate.parse(dateStr).getDayOfWeek().getValue();
                    dayHeader = dow < dayNames.length ? dayNames[dow].toUpperCase() : dateStr;
                    dowIndex = dow < dowColors.length ? dow : 0;
                } catch (Exception e) { dayHeader = dateStr; }
                String dot = dowColors[dowIndex];

                StringBuilder inner = new StringBuilder();
                inner.append(dot).append(" <b>").append(esc(dayHeader)).append("</b> — <i>").append(esc(dateStr)).append("</i>\n");
                for (ScheduleEvent ev : dayEvents) {
                    String time    = ev.getStart().length() >= 16 ? ev.getStart().substring(11, 16) : "";
                    String[] parts = ev.getTitle().split("\n", 2);
                    String room    = parts.length > 0 ? parts[0].trim() : "";
                    String subject = parts.length > 1 ? parts[1].trim() : ev.getTitle();
                    String icon    = ev.getType() == 2 || ev.getType() == 3 ? "🔬" : "📖";
                    inner.append("\n⏰ <b>").append(esc(time)).append("</b>");
                    if (!room.isEmpty()) inner.append(" | ").append(esc(room));
                    inner.append("\n").append(icon).append(" ").append(esc(subject)).append("\n");
                }
                schedMsg.append("<blockquote>").append(inner.toString().trim()).append("</blockquote>\n");
            }

            send(chatId, schedMsg.toString(), semesterScheduleKeyboard());
        });
    }

    // ─────────────────────────────────────────────
    //  STUDY PLAN
    // ─────────────────────────────────────────────

    private void showStudyPlanFull(long chatId, long userId) {
        if (!checkLogin(chatId, userId)) return;
        send(chatId, t(userId, "plan.loading"), null);

        executor.submit(() -> {
            List<StudyPlanSubject> subjects = lmsService.getStudyPlan(userId);
            if (subjects.isEmpty()) { send(chatId, t(userId, "common.no_data"), null); return; }

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
                            "🟢 Аъло (5)  🔵 Яхши (4)  🟡 Қониқарли (3)  🔴 Қониқарсиз (2)"))
                    .append("</blockquote>\n");
            planMsg.append("\n💡 ").append(t(userId, "plan.gpa_hint"));
            send(chatId, planMsg.toString(), gpaChoiceKeyboard(userId));
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
                                inlineBtn(tr(userId, "📖 Лекция", "📖 Lektsiya", "📖 Маъруза"), "caltype_" + cid + "_lecture"),
                                inlineBtn(tr(userId, "🔬 Практика", "🔬 Amaliyot", "🔬 Амалиёт"), "caltype_" + cid + "_practice")
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
                ? tr(userId, "Лекция", "Lektsiya", "Маъруза")
                : tr(userId, "Практика", "Amaliyot", "Амалиёт");

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
                ? tr(userId, "Лекция", "Lektsiya", "Маъруза")
                : tr(userId, "Практика", "Amaliyot", "Амалиёт");

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
        send(chatId, t(userId, "finals.loading"), null);

        executor.submit(() -> {
            List<FinalExam> exams = lmsService.getFinals(userId, semesterId);
            String semName = config.getSemesterMap().getOrDefault(semesterId, "Semester " + semesterId);

            if (exams.isEmpty()) {
                send(chatId, t(userId, "finals.empty"), finalsSemesterKeyboard());
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
                    exBlock.append("🔢 ").append(tr(userId, "Поток", "Oqim", "Оқим")).append(": ").append(esc(ex.getStream())).append("\n");
                if (ex.getDate() != null && !ex.getDate().isBlank()) {
                    exBlock.append("📅 <b>").append(esc(ex.getDate())).append("</b>");
                    if (ex.getFrom() != null && !ex.getFrom().isBlank())
                        exBlock.append(" 🕐 <b>").append(esc(ex.getFrom())).append("</b>");
                    exBlock.append("\n");
                }
                if (ex.getRoom() != null && !ex.getRoom().isBlank())
                    exBlock.append("🚪 <b>").append(esc(ex.getRoom())).append("</b>\n");
                exBlock.append(gradeIcon).append(" ").append(tr(userId, "Балл", "Ball", "Балл"))
                        .append(": <b>").append(esc(ex.getGrade())).append("</b>");
                sb.append("<blockquote>").append(exBlock).append("</blockquote>\n");
            }

            send(chatId, sb.toString(), finalsSemesterKeyboard());
        });
    }

    // ─────────────────────────────────────────────
    //  PROFILE
    // ─────────────────────────────────────────────

    private void showProfile(long chatId, long userId) {
        if (!checkLogin(chatId, userId)) return;
        send(chatId, tr(userId, "⏳ Профиль загружается...", "⏳ Profil yuklanmoqda...", "⏳ Профил юкланмоқда..."), null);

        executor.submit(() -> {
            StudentInfo info = lmsService.getStudentInfo(userId);
            if (info == null) { send(chatId, t(userId, "common.no_data"), null); return; }

            StringBuilder sb = new StringBuilder();
            sb.append(tr(userId, "👤 <b>Профиль</b>", "👤 <b>Profil</b>", "👤 <b>Профил</b>")).append("\n");
            sb.append("┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄\n\n");

            String login = userLogin.getOrDefault(userId, "—");
            sb.append("<blockquote>")
                    .append("🔑 ").append(tr(userId, "Логин", "Login", "Логин")).append(": <b>").append(esc(login)).append("</b>")
                    .append("</blockquote>\n\n");

            StringBuilder p1 = new StringBuilder();
            row(p1, "👤 " + tr(userId, "Ф.И.О", "F.I.O", "Ф.И.О"), info.getFullName());
            row(p1, "🎂 " + tr(userId, "Дата рождения", "Tug'ilgan kun", "Туғилган кун"), info.getBirthDate());
            row(p1, "⚧ " + tr(userId, "Пол", "Jinsi", "Жинси"), info.getGender());
            row(p1, "📒 " + tr(userId, "Зачётка", "Zach. daftari", "Зачётка"), info.getRecordBook());
            row(p1, "🏠 " + tr(userId, "Адрес", "Manzil", "Манзил"), info.getAddress());
            sb.append("📌 <b>").append(tr(userId, "Личные данные", "Shaxsiy ma'lumotlar", "Шахсий маълумотлар")).append("</b>\n")
                    .append("<blockquote>").append(p1.toString().trim()).append("</blockquote>\n\n");

            StringBuilder p2 = new StringBuilder();
            row(p2, "🎓 " + tr(userId, "Направление", "Yo'nalish", "Йўналиш"), info.getDirection());
            row(p2, "🌐 " + tr(userId, "Язык обучения", "O'qish tili", "Ўқиш тили"), info.getLanguage());
            row(p2, "📜 " + tr(userId, "Степень", "Daraja", "Даража"), info.getDegree());
            row(p2, "🏫 " + tr(userId, "Тип обучения", "O'qish turi", "Ўқиш тури"), info.getStudyType());
            row(p2, "📅 " + tr(userId, "Курс", "Kurs", "Курс"), info.getCourse());
            row(p2, "👥 " + tr(userId, "Группа", "Guruh", "Гуруҳ"), info.getGroup());
            row(p2, "👨‍🏫 " + tr(userId, "Куратор", "Kurator", "Куратор"), info.getCurator());
            row(p2, "💰 " + tr(userId, "Стипендия", "Stipendiya", "Стипендия"), info.getScholarship());
            sb.append("📚 <b>").append(tr(userId, "Учебные данные", "O'quv ma'lumotlari", "Ўқув маълумотлари")).append("</b>\n")
                    .append("<blockquote>").append(p2.toString().trim()).append("</blockquote>");

            InlineKeyboardMarkup kb = markup(List.of(
                    List.of(inlineBtn(tr(userId, "🖼 Фотография", "🖼 Foto", "🖼 Фото"), "profile_photo"))
            ));
            send(chatId, sb.toString(), kb);
        });
    }

    private void row(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank())
            sb.append(label).append(": <b>").append(esc(value)).append("</b>\n");
    }

    private void showProfilePhoto(long chatId, long userId) {
        if (!checkLogin(chatId, userId)) return;
        send(chatId, tr(userId, "⏳ Загружаю фото...", "⏳ Foto yuklanmoqda...", "⏳ Фото юкланмоқда..."), null);
        executor.submit(() -> {
            File tmp = null;
            try {
                String dataUrl = lmsService.getProfilePhotoDataUrl(userId);
                if (dataUrl == null || !dataUrl.startsWith("data:image")) {
                    send(chatId, tr(userId, "📭 <b>Фото не найдено</b>", "📭 <b>Foto topilmadi</b>", "📭 <b>Фото топилмади</b>"), null);
                    return;
                }
                int comma = dataUrl.indexOf(',');
                String meta = comma > 0 ? dataUrl.substring(0, comma) : "";
                String b64  = comma > 0 ? dataUrl.substring(comma + 1) : "";
                String ext  = meta.contains("png") ? ".png" : ".jpg";
                byte[] bytes = java.util.Base64.getDecoder().decode(b64);
                tmp = File.createTempFile("lms_avatar_", ext);
                Files.write(tmp.toPath(), bytes);
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
                "🔒 <b>Паролни ўзгартириш</b>\n\n<blockquote><b>Эски парол</b>ни киритинг:</blockquote>"), null);
    }

    // ─────────────────────────────────────────────
    //  SETTINGS
    // ─────────────────────────────────────────────

    private void showSettings(long chatId, long userId) {
        String currentLang = userLang.getOrDefault(userId, "uz_lat");
        String title = tr(userId, "⚙️ <b>Настройки</b>", "⚙️ <b>Sozlamalar</b>", "⚙️ <b>Созламалар</b>");
        String body = "<blockquote>"
                + tr(userId, "🌐 Язык", "🌐 Til", "🌐 Тил") + ": <b>" + esc(langLabel(currentLang)) + "</b>"
                + "</blockquote>";

        InlineKeyboardMarkup kb = markup(List.of(
                List.of(inlineBtn(tr(userId, "🌐 Сменить язык", "🌐 Tilni o'zgartirish", "🌐 Тилни ўзгартириш"), "settings_lang")),
                List.of(inlineBtn(tr(userId, "🔒 Сменить пароль", "🔒 Parolni o'zgartirish", "🔒 Паролни ўзгартириш"), "settings_password")),
                List.of(inlineBtn(tr(userId, "🚪 Выйти из аккаунта", "🚪 Akkauntdan chiqish", "🚪 Акkauntdan chiqish"), "settings_logout"))
        ));
        send(chatId, title + "\n\n" + body, kb);
    }

    // ─────────────────────────────────────────────
    //  DEADLINES
    // ─────────────────────────────────────────────

    private void showDeadlinesList(long chatId, long userId) {
        if (!checkLogin(chatId, userId)) return;
        send(chatId, tr(userId,
                "⏳ <b>Собираю дедлайны...</b>",
                "⏳ <b>Deadlinelar yig'ilmoqda...</b>",
                "⏳ <b>Дедлайнлар йиғилмоқда...</b>"), null);

        executor.submit(() -> {
            List<Course> courses = userCourses.get(userId);
            int semesterId = userSemester.getOrDefault(userId, getDefaultSemesterId());
            if (courses == null || courses.isEmpty()) {
                courses = lmsService.getMyCourses(userId, semesterId);
                if (courses != null && !courses.isEmpty()) {
                    userCourses.put(userId, courses);
                    userSemester.put(userId, semesterId);
                }
            }
            if (courses == null || courses.isEmpty()) {
                send(chatId, tr(userId,
                        "📭 <b>Список дедлайнов</b>\n\n<blockquote>Сначала откройте «Мои предметы».</blockquote>",
                        "📭 <b>Deadline ro'yxati</b>\n\n<blockquote>Avval «Mening fanlarim» bo'limini oching.</blockquote>",
                        "📭 <b>Дедлайнлар рўйхати</b>\n\n<blockquote>Аввал «Менинг фанларим» бўлимига киринг.</blockquote>"), null);
                return;
            }

            long now = System.currentTimeMillis();
            List<DeadlineItem> allItems = new ArrayList<>();
            List<DeadlineItem> items    = new ArrayList<>();

            for (Course c : courses) {
                CourseSummary cs = lmsService.getActivities(userId, c.getId());
                if (cs == null || cs.getActivities() == null) continue;
                for (Activity a : cs.getActivities()) {
                    long dl = parseActivityDeadlineTs(a.getDeadline());
                    if (dl <= 0 || dl < now) continue;
                    DeadlineItem it = new DeadlineItem();
                    it.course = c.getSubject(); it.courseId = c.getId(); it.act = a; it.dl = dl;
                    allItems.add(it);
                    boolean hasUpload = a.getUploadedFileUrl() != null && !a.getUploadedFileUrl().isBlank();
                    if (!hasUpload) items.add(it);
                }
            }
            allItems.sort(Comparator.comparingLong(x -> x.dl));
            items.sort(Comparator.comparingLong(x -> x.dl));

            lastDeadlineItems.put(userId, allItems);

            StringBuilder sb = new StringBuilder();
            sb.append(tr(userId, "🗓 <b>Список дедлайнов</b>", "🗓 <b>Deadline ro'yxati</b>", "🗓 <b>Дедлайнлар рўйхати</b>"))
                    .append("\n");
            sb.append("<blockquote>")
                    .append(tr(userId, "📭 Только не загруженные задания", "📭 Faqat yuklanmagan topshiriqlar", "📭 Фақат юкланмаган топшириқлар"))
                    .append("</blockquote>\n\n");

            if (items.isEmpty()) {
                sb.append("<blockquote>✅ ").append(tr(userId,
                        "Все задания загружены или дедлайнов нет.",
                        "Barcha topshiriqlar yuklangan yoki deadlinelar yo'q.",
                        "Барча топшириқлар юкланган ёки дедлайнлар йўқ.")).append("</blockquote>");
                send(chatId, sb.toString(), null);
                return;
            }

            List<DeadlineItem> urgent = new ArrayList<>(), d1_3 = new ArrayList<>(),
                    d3_7 = new ArrayList<>(), rest = new ArrayList<>();
            for (DeadlineItem it : items) {
                double d = (it.dl - now) / (24.0 * 60 * 60 * 1000);
                if (d <= 1.0) urgent.add(it);
                else if (d <= 3.0) d1_3.add(it);
                else if (d <= 7.0) d3_7.add(it);
                else rest.add(it);
            }

            appendDeadlineGroup(sb, userId, tr(userId, "🔥 Срочные (≤1 день)", "🔥 Shoshilinch (≤1 kun)", "🔥 Шошилинч (≤1 кун)"), urgent, now);
            appendDeadlineGroup(sb, userId, tr(userId, "⏳ 1–3 дня", "⏳ 1–3 kun", "⏳ 1–3 кун"), d1_3, now);
            appendDeadlineGroup(sb, userId, tr(userId, "📅 3–7 дней", "📅 3–7 kun", "📅 3–7 кун"), d3_7, now);
            List<DeadlineItem> restTop = rest.size() <= 3 ? rest : rest.subList(0, 3);
            appendDeadlineGroup(sb, userId, tr(userId, "📌 Остальные (ближайшие 3)", "📌 Qolganlari (eng yaqin 3)", "📌 Қолганлари (энг яқин 3)"), restTop, now);

            InlineKeyboardMarkup kb = markup(List.of(List.of(
                    inlineBtn(tr(userId, "📋 Все дедлайны", "📋 Barcha deadlinelar", "📋 Барча дедлайнлар"), "deadlines_all_0")
            )));
            send(chatId, sb.toString(), kb);
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
        sb.append(tr(userId, "📋 <b>Все дедлайны</b>", "📋 <b>Barcha deadlinelar</b>", "📋 <b>Барча дедлайнлар</b>"))
                .append("\n")
                .append(t(userId, "common.page")).append(" ").append(page + 1).append(" / ").append(maxPage + 1)
                .append("\n\n");

        for (int i = from; i < to; i++) {
            DeadlineItem it = items.get(i);
            Activity a = it.act;
            boolean hasUpload = a.getUploadedFileUrl() != null && !a.getUploadedFileUrl().isBlank();
            String statusIcon = hasUpload ? "✅" : "📭";
            String datePart   = (a.getDeadline() != null && a.getDeadline().length() >= 16)
                    ? a.getDeadline().substring(0, 16) : (a.getDeadline() != null ? a.getDeadline() : "—");
            long diffMin = Math.max(0, (it.dl - now) / 60000L);
            String inStr = diffMin < 60
                    ? tr(userId, "через " + diffMin + " мин", diffMin + " min ichida", diffMin + " мин ичида")
                    : tr(userId, "через " + (diffMin / 60) + " ч", (diffMin / 60) + " soat ichida", (diffMin / 60) + " соат ичида");

            sb.append("<blockquote>")
                    .append(statusIcon).append(" <b>").append(esc(it.course)).append("</b>\n")
                    .append("📝 ").append(esc(truncateTopic(a.getTask(), 70))).append("\n")
                    .append("⏰ <b>").append(esc(datePart)).append("</b> — <i>").append(esc(inStr)).append("</i>\n")
                    .append("🏆 ").append(orDash(a.getEarnedScore())).append("/").append(orDash(a.getMaxScore()))
                    .append("</blockquote>\n");
        }

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> nav = new ArrayList<>();
        if (page > 0)      nav.add(inlineBtn(tr(userId, "⬅️ Предыдущий", "⬅️ Oldingi", "⬅️ Олдинги"), "deadlines_all_" + (page - 1)));
        if (page < maxPage) nav.add(inlineBtn(tr(userId, "Следующий ➡️", "Keyingi ➡️", "Кейинги ➡️"), "deadlines_all_" + (page + 1)));
        if (!nav.isEmpty()) rows.add(nav);
        rows.add(List.of(inlineBtn(tr(userId, "🔙 Назад", "🔙 Orqaga", "🔙 Орқага"), "deadlines_back")));

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
                    "⚠️ Файл ҳаволаси эскирган. «📆 Дарс режаси» бўлимидан қайта очиб кўринг."), null);
            return;
        }
        if ("url".equals(att.getType())) {
            send(chatId, "🔗 <b>" + esc(att.getName()) + "</b>\n\n" + att.getUrl(), null);
            return;
        }
        send(chatId, tr(userId, "⏳ Загружаю файл...", "⏳ Fayl yuklanmoqda...", "⏳ Файл юкланмоқда..."), null);
        executor.submit(() -> {
            File tmp = null;
            try {
                LmsService.DownloadedFile dl = lmsService.downloadFile(userId, att.getUrl(), att.getName());
                tmp = dl.file();
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
                    "⚠️ Маълумот эскирган. Активностларни қайта очинг."), null);
            return;
        }
        Activity act = acts.get(actIndex);
        String url  = "sample".equals(which) ? act.getSampleFileUrl()  : act.getUploadedFileUrl();
        String name = "sample".equals(which) ? act.getSampleFileName() : act.getUploadedFileName();
        if (url == null || url.isBlank()) {
            send(chatId, tr(userId, "📭 Файл не найден.", "📭 Fayl topilmadi.", "📭 Файл топилмади."), null);
            return;
        }
        send(chatId, tr(userId, "⏳ Загружаю файл...", "⏳ Fayl yuklanmoqda...", "⏳ Файл юкланмоқда..."), null);
        executor.submit(() -> {
            File tmp = null;
            try {
                LmsService.DownloadedFile dl = lmsService.downloadFile(userId, url, name);
                tmp = dl.file();
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
                "📤 <b>Топшириқни юбориш</b>\n\n" + header + "<blockquote>• Макс. ҳажм: 50 МБ\n• Турлари: jpg, png, doc, docx, pdf, ppt, pptx, zip, rar</blockquote>\n\nФайл юборинг ёки /cancel ёзинг."), null);
    }

    private void handleActivityFileUpload(long chatId, long userId, Message msg) {
        PendingUpload pu = pendingUpload.remove(userId);
        if (pu == null) {
            send(chatId, tr(userId,
                    "⚠️ Контекст загрузки утерян. Попробуйте снова.",
                    "⚠️ Upload kontekst yo'q. Qayta urinib ko'ring.",
                    "⚠️ Юклаш контексти йўқ. Қайта уриниб кўринг."), null);
            return;
        }
        send(chatId, tr(userId,
                "⏳ Загружаю файл в LMS...",
                "⏳ Fayl LMS ga yuklanmoqda...",
                "⏳ Файл LMS га юкланмоқда..."), null);
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
                            "❌ Нотўғри файл тури. Рухсат: jpg, png, doc, docx, pdf, ppt, pptx, zip, rar"), null);
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
                            "❌ Файл жуда катта (макс. 50 МБ)."), null);
                    return;
                }
                boolean ok = lmsService.uploadActivityFile(userId, pu.courseId, pu.activityId, tmp, filename);
                if (ok) {
                    send(chatId, tr(userId,
                            "✅ <b>Файл успешно загружен в LMS!</b>",
                            "✅ <b>Fayl muvaffaqiyatli LMS ga yuklandi!</b>",
                            "✅ <b>Файл муваффақиятли LMS га юкланди!</b>"), null);
                    showActivities(chatId, userId, pu.courseId);
                } else {
                    send(chatId, tr(userId,
                            "❌ Ошибка загрузки в LMS. Попробуйте позже.",
                            "❌ LMS ga yuklashda xatolik. Keyinroq urinib ko'ring.",
                            "❌ LMS га юклашда хатолик. Кейинроқ уриниб кўринг."), null);
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
                tickDeadlinesListReminder();
                tickUrgentDeadlineReminders();
            } catch (Exception e) {
                System.err.println("[LmsBot] Scheduler error: " + e.getMessage());
            }
        }, 10, 30, TimeUnit.SECONDS);
    }

    private void tickPairReminders() {
        for (Long userId : new ArrayList<>(lastChatId.keySet())) {
            if (!lmsService.isLoggedIn(userId)) continue;
            Long chatId = lastChatId.get(userId);
            if (chatId == null) continue;
            int semesterId = userSemester.getOrDefault(userId, getDefaultSemesterId());
            List<ScheduleEvent> events = lmsService.getSchedule(userId, semesterId);
            if (events == null || events.isEmpty()) continue;

            long now = System.currentTimeMillis();
            for (ScheduleEvent ev : events) {
                long start = parseScheduleStartTs(ev.getStart());
                if (start <= 0) continue;

                long diffMin = (start - now) / 60000L;

                boolean inWindow = diffMin >= (PAIR_REMINDER_BEFORE_MIN - PAIR_REMINDER_WINDOW_MIN)
                        && diffMin <= (PAIR_REMINDER_BEFORE_MIN + PAIR_REMINDER_WINDOW_MIN);
                if (!inWindow) continue;

                String key = "pair|" + start + "|" + ev.getTitle();
                if (!markOnce(userId, key)) continue;

                PairInfo info = parsePairInfo(ev);
                send(chatId, tr(userId,
                        "⏰ <b>Напоминание: пара через " + PAIR_REMINDER_BEFORE_MIN + " минут</b>\n\n<blockquote>"
                                + "📖 Предмет: <b>" + esc(info.subject) + "</b>\n"
                                + (info.teacher.isBlank() ? "" : "👨‍🏫 Преподаватель: <b>" + esc(info.teacher) + "</b>\n")
                                + "🕐 Время: <b>" + esc(info.time) + "</b>\n"
                                + (info.room.isBlank() ? "" : "🚪 Кабинет: <b>" + esc(info.room) + "</b>\n")
                                + "</blockquote>",
                        "⏰ <b>Eslatma: dars " + PAIR_REMINDER_BEFORE_MIN + " daqiqadan so'ng boshlanadi</b>\n\n<blockquote>"
                                + "📖 Fan: <b>" + esc(info.subject) + "</b>\n"
                                + (info.teacher.isBlank() ? "" : "👨‍🏫 O'qituvchi: <b>" + esc(info.teacher) + "</b>\n")
                                + "🕐 Vaqt: <b>" + esc(info.time) + "</b>\n"
                                + (info.room.isBlank() ? "" : "🚪 Xona: <b>" + esc(info.room) + "</b>\n")
                                + "</blockquote>",
                        "⏰ <b>Эслатма: дарс " + PAIR_REMINDER_BEFORE_MIN + " дақиқадан кейин бошланади</b>\n\n<blockquote>"
                                + "📖 Фан: <b>" + esc(info.subject) + "</b>\n"
                                + (info.teacher.isBlank() ? "" : "👨‍🏫 Ўқитувчи: <b>" + esc(info.teacher) + "</b>\n")
                                + "🕐 Вақт: <b>" + esc(info.time) + "</b>\n"
                                + (info.room.isBlank() ? "" : "🚪 Хона: <b>" + esc(info.room) + "</b>\n")
                                + "</blockquote>"), null);
            }
        }
    }

    private void tickNbReminders() {
        for (Long userId : new ArrayList<>(lastChatId.keySet())) {
            if (!lmsService.isLoggedIn(userId)) continue;
            Long chatId = lastChatId.get(userId);
            if (chatId == null) continue;
            int semesterId = userSemester.getOrDefault(userId, getDefaultSemesterId());
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
                                + "📌 НБ: <b>" + nb + "</b> (аввал " + old + " эди)</blockquote>"), null);
            }
        }
    }

    private void tickDeadlinesListReminder() {
        for (Long userId : new ArrayList<>(lastChatId.keySet())) {
            if (!lmsService.isLoggedIn(userId)) continue;
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
    private void tickUrgentDeadlineReminders() {
        for (Long userId : new ArrayList<>(lastChatId.keySet())) {
            if (!lmsService.isLoggedIn(userId)) continue;
            Long chatId = lastChatId.get(userId);
            if (chatId == null) continue;

            int semesterId = userSemester.getOrDefault(userId, getDefaultSemesterId());

            // Всегда обеспечиваем актуальный список курсов
            List<Course> courses = userCourses.get(userId);
            if (courses == null || courses.isEmpty()) {
                courses = lmsService.getMyCourses(userId, semesterId);
                if (courses != null && !courses.isEmpty()) {
                    userCourses.put(userId, courses);
                    userSemester.put(userId, semesterId);
                }
            }
            if (courses == null || courses.isEmpty()) continue;

            long now = System.currentTimeMillis();
            long urgentUntil = now + 24L * 60 * 60 * 1000;

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

                    lastDeadlineNotifyTs.get(userId).put(key, now);

                    String datePart = a.getDeadline() != null ? a.getDeadline() : "—";

                    long diffMin = Math.max(0, (dl - now) / 60000L);
                    String inStr;
                    if (diffMin < 60) {
                        inStr = tr(userId,
                                "через " + diffMin + " мин",
                                diffMin + " min ichida",
                                diffMin + " мин ичида");
                    } else {
                        long h = diffMin / 60;
                        long m = diffMin % 60;
                        inStr = tr(userId,
                                "через " + h + " ч " + m + " мин",
                                h + " soat " + m + " min ichida",
                                h + " соат " + m + " мин ичида");
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
                            "🔔 <b>Дедлайн ҳақида эслатма</b>");

                    String text = title + "\n\n<blockquote>"
                            + "📖 " + tr(userId, "Предмет", "Fan", "Фан") + ": <b>" + esc(c.getSubject()) + "</b>\n"
                            + "📝 " + esc(truncateTopic(a.getTask(), 90)) + "\n"
                            + "⏰ " + tr(userId, "Дедлайн", "Deadline", "Дедлайн") + ": <b>" + esc(datePart) + "</b>\n"
                            + "⏱ " + esc(inStr) + "\n"
                            + "📌 " + tr(userId, "Статус", "Status", "Статус") + ": <b>📭 "
                            + tr(userId, "Не загружено", "Yuklanmagan", "Юкланмаган") + "</b>\n"
                            + "🔁 " + tr(userId, "Интервал напоминания", "Eslatma oralig'i", "Эслатма оралиғи") + ": <b>"
                            + esc(tr(userId,
                            minutesToLabelRu(intervalMin),
                            minutesToLabelUzLat(intervalMin),
                            minutesToLabelUzCyr(intervalMin)))
                            + "</b></blockquote>";

                    InlineKeyboardMarkup kb = markup(List.of(List.of(
                            inlineBtn(
                                    tr(userId, "⏱ Изменить интервал", "⏱ Oraliqni o'zgartirish", "⏱ Оралиқни ўзгартириш"),
                                    "dl_remind_" + key)
                    )));
                    send(chatId, text, kb);
                }
            }
        }
    }

    private void showDeadlineIntervalPicker(long chatId, long userId, int messageId, String key) {
        edit(chatId, messageId, tr(userId,
                        "⏱ <b>Напоминать через</b>\n\n<blockquote>Выберите интервал.</blockquote>",
                        "⏱ <b>Qachon eslatilsin</b>\n\n<blockquote>Oraliqni tanlang.</blockquote>",
                        "⏱ <b>Қачон эслатилсин</b>\n\n<blockquote>Оралиқни танланг.</blockquote>"),
                markup(List.of(
                        List.of(inlineBtn(tr(userId, "30 мин", "30 min", "30 мин"), "dl_set_" + key + "_30"),
                                inlineBtn(tr(userId, "1 час", "1 soat", "1 соат"), "dl_set_" + key + "_60")),
                        List.of(inlineBtn(tr(userId, "3 часа", "3 soat", "3 соат"), "dl_set_" + key + "_180"),
                                inlineBtn(tr(userId, "5 часов", "5 soat", "5 соат"), "dl_set_" + key + "_300"))
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
            if (!userLang.containsKey(userId)) sendLanguageChoice(chatId);
            else sendWelcome(chatId, userId, firstName);
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
            showDeadlinesList(chatId, userId);
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
        return t.equals("📚 Mening fanlarim") || t.equals("📚 Мои предметы") || t.equals("📚 Менинг фанларим")
                || t.equals("📅 Dars jadvali") || t.equals("📅 Расписание") || t.equals("📅 Дарс жадвали")
                || t.equals("📖 O'quv reja") || t.equals("📖 Учебный план") || t.equals("📖 Ўқув режа")
                || t.equals("🏆 Yakuniy imtihon") || t.equals("🏆 Итоговый экзамен") || t.equals("🏆 Якуний имтиҳон")
                || t.equals("👤 Profil") || t.equals("👤 Профиль") || t.equals("👤 Профил");
    }

    private boolean isLoginCommand(String t)     { return "/login".equals(t) || "🔑 Kirish".equals(t) || "🔑 Войти".equals(t) || "🔑 Кириш".equals(t); }
    private boolean isLogoutCommand(String t)    { return "/logout".equals(t) || "🚪 Chiqish".equals(t) || "🚪 Выйти".equals(t) || "🚪 Чиқиш".equals(t); }
    private boolean isSettingsCommand(String t)  { return "⚙️ Sozlamalar".equals(t) || "⚙️ Настройки".equals(t) || "⚙️ Созламалар".equals(t); }
    private boolean isDeadlinesListCommand(String t) { return "🗓 Deadline ro'yxati".equals(t) || "🗓 Список дедлайнов".equals(t) || "🗓 Дедлайнлар рўйхати".equals(t); }

    // ─────────────────────────────────────────────
    //  KEYBOARDS
    // ─────────────────────────────────────────────

    private ReplyKeyboardMarkup loginKeyboard(long userId) {
        String label = tr(userId, "🔑 Войти", "🔑 Kirish", "🔑 Кириш");
        KeyboardRow row = new KeyboardRow();
        row.add(new KeyboardButton(label));
        ReplyKeyboardMarkup m = new ReplyKeyboardMarkup();
        m.setKeyboard(List.of(row));
        m.setResizeKeyboard(true);
        return m;
    }

    private ReplyKeyboardMarkup mainMenuKeyboard(long userId) {
        String myCourses = tr(userId, "📚 Мои предметы", "📚 Mening fanlarim", "📚 Менинг фанларим");
        String schedule  = tr(userId, "📅 Расписание", "📅 Dars jadvali", "📅 Дарс жадвали");
        String plan      = tr(userId, "📖 Учебный план", "📖 O'quv reja", "📖 Ўқув режа");
        String finals    = tr(userId, "🏆 Итоговый экзамен", "🏆 Yakuniy imtihon", "🏆 Якуний имтиҳон");
        String profile   = tr(userId, "👤 Профиль", "👤 Profil", "👤 Профил");
        String settings  = tr(userId, "⚙️ Настройки", "⚙️ Sozlamalar", "⚙️ Созламалар");

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
                inlineBtn(tr(userId, "✅ Оценённые предметы", "✅ Baholangan fanlar", "✅ Баҳоланган фанлар"), "gpa_all"),
                inlineBtn(tr(userId, "⬜ С текущим сем. (0)", "⬜ Joriy semestr 0 bilan", "⬜ Жорий семестр 0 билан"), "gpa_with_zero")
        )));
    }

    private InlineKeyboardMarkup finalsSemesterKeyboard()   { return buildSemesterKeyboard("finals_");   }
    private InlineKeyboardMarkup semesterKeyboard()         { return buildSemesterKeyboard("semester_"); }
    private InlineKeyboardMarkup semesterScheduleKeyboard() { return buildSemesterKeyboard("schedule_"); }

    private InlineKeyboardMarkup buildSemesterKeyboard(String prefix) {
        List<AppConfig.SemesterConfig> semesters = config.getSemesters();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (int i = 0; i < semesters.size(); i += 2) {
            List<InlineKeyboardButton> row = new ArrayList<>();
            AppConfig.SemesterConfig s1 = semesters.get(i);
            row.add(inlineBtn(s1.getYear() + " " + s1.getEmoji(), prefix + s1.getId()));
            if (i + 1 < semesters.size()) {
                AppConfig.SemesterConfig s2 = semesters.get(i + 1);
                row.add(inlineBtn(s2.getYear() + " " + s2.getEmoji(), prefix + s2.getId()));
            }
            rows.add(row);
        }
        return markup(rows);
    }

    private void sendLanguageChoice(long chatId) {
        send(chatId, "🌐 Tilni tanlang / Выберите язык:", langChoiceMarkup("lang_"));
    }

    private InlineKeyboardMarkup langChoiceMarkup() { return langChoiceMarkup("setlang_"); }
    private InlineKeyboardMarkup langChoiceMarkup(String prefix) {
        return markup(List.of(
                List.of(inlineBtn("🇷🇺 Русский",             prefix + "ru")),
                List.of(inlineBtn("🇺🇿 O'zbekcha (lotin)",   prefix + "uz_lat")),
                List.of(inlineBtn("🇺🇿 Ўзбекча (кирилл)",   prefix + "uz_cyr"))
        ));
    }

    private InlineKeyboardMarkup markup(List<List<InlineKeyboardButton>> rows) {
        InlineKeyboardMarkup m = new InlineKeyboardMarkup();
        m.setKeyboard(rows);
        return m;
    }

    private InlineKeyboardButton inlineBtn(String text, String data) {
        InlineKeyboardButton btn = new InlineKeyboardButton();
        btn.setText(text);
        btn.setCallbackData(data);
        return btn;
    }

    // ─────────────────────────────────────────────
    //  LOCALIZATION  t() / tr()
    // ─────────────────────────────────────────────

    private String lang(long userId) { return userLang.getOrDefault(userId, "uz_lat"); }

    private String tr(long userId, String ru, String uzLat, String uzCyr) {
        return switch (lang(userId)) { case "ru" -> ru; case "uz_cyr" -> uzCyr; default -> uzLat; };
    }

    private String t(long userId, String key) {
        String l = lang(userId);
        return switch (key) {
            case "common.no_data"        -> switch(l){ case "ru"->"📭 Данные не найдены.";        case "uz_cyr"->"📭 Маълумот топилмади.";        default->"📭 Ma'lumot topilmadi."; };
            case "common.page"           -> switch(l){ case "ru"->"Страница";                     case "uz_cyr"->"Саҳифа";                        default->"Sahifa"; };
            case "btn.back"              -> switch(l){ case "ru"->"🔙 Назад";                     case "uz_cyr"->"🔙 Орқага";                     default->"🔙 Orqaga"; };
            case "btn.back_courses"      -> switch(l){ case "ru"->"🔙 Мои предметы";              case "uz_cyr"->"🔙 Менинг фанларим";            default->"🔙 Mening fanlarim"; };
            case "btn.back_plan"         -> switch(l){ case "ru"->"🔙 Назад к плану";             case "uz_cyr"->"🔙 Режага қайтиш";              default->"🔙 O'quv rejaga qaytish"; };
            case "btn.prev"              -> switch(l){ case "ru"->"⬅️ Назад";                     case "uz_cyr"->"⬅️ Олдинги";                    default->"⬅️ Oldingi"; };
            case "btn.next"              -> switch(l){ case "ru"->"Вперёд ➡️";                    case "uz_cyr"->"Кейинги ➡️";                    default->"Keyingi ➡️"; };
            case "btn.files"             -> switch(l){ case "ru"->"📂 Файлы";                     case "uz_cyr"->"📂 Файллар";                    default->"📂 Fayllar"; };
            case "btn.attendance"        -> switch(l){ case "ru"->"📊 Посещаемость";              case "uz_cyr"->"📊 Давомат";                    default->"📊 Davomat"; };
            case "btn.activities"        -> switch(l){ case "ru"->"📋 Активности";                case "uz_cyr"->"📋 Активностлар";               default->"📋 Aktivnosti"; };
            case "btn.calendar"          -> switch(l){ case "ru"->"📆 План занятий";              case "uz_cyr"->"📆 Дарс режаси";                default->"📆 Fan rejasi"; };
            case "btn.deadlines"         -> switch(l){ case "ru"->"🗓 Список дедлайнов";          case "uz_cyr"->"🗓 Дедлайнлар рўйхати";         default->"🗓 Deadline ro'yxati"; };
            case "btn.change_semester"   -> switch(l){ case "ru"->"📅 Сменить семестр";           case "uz_cyr"->"📅 Семестрни ўзгартириш";       default->"📅 Semestrni o'zgartirish"; };
            case "courses.loading"       -> switch(l){ case "ru"->"⏳ Загружаю предметы...";      case "uz_cyr"->"⏳ Фанлар юкланмоқда...";       default->"⏳ Fanlar yuklanmoqda..."; };
            case "courses.empty_semester"-> switch(l){ case "ru"->"📭 В этом семестре предметов не найдено."; case "uz_cyr"->"📭 Бу семестрда фанлар топилмади."; default->"📭 Bu semestrda fanlar topilmadi."; };
            case "courses.total"         -> switch(l){ case "ru"->"Итого";                        case "uz_cyr"->"Жами";                          default->"Jami"; };
            case "courses.subjects_suffix"->switch(l){ case "ru"->"предмет(ов)";                  case "uz_cyr"->"та фан";                        default->"ta fan"; };
            case "att.loading"           -> switch(l){ case "ru"->"⏳ Загружается посещаемость..."; case "uz_cyr"->"⏳ Давомат юкланмоқда...";    default->"⏳ Davomat yuklanmoqda..."; };
            case "att.none"              -> switch(l){ case "ru"->"📭 Данные по посещаемости не найдены."; case "uz_cyr"->"📭 Давомат маълумоти топилмади."; default->"📭 Davomat ma'lumoti topilmadi."; };
            case "att.all_ok_title"      -> switch(l){ case "ru"->"✅ <b>Вы посетили все занятия!</b>"; case "uz_cyr"->"✅ <b>Барча дарсларда қатнашгансиз!</b>"; default->"✅ <b>Barcha darslarga qatnashgansiz!</b>"; };
            case "att.total_lessons"     -> switch(l){ case "ru"->"📅 Всего занятий";             case "uz_cyr"->"📅 Жами дарслар";               default->"📅 Jami darslar"; };
            case "att.missed"            -> switch(l){ case "ru"->"🔴 Пропущено";                 case "uz_cyr"->"🔴 Қолдирилган";                default->"🔴 Qoldirilgan"; };
            case "att.reason_yes"        -> switch(l){ case "ru"->"✅ Есть уважительная причина"; case "uz_cyr"->"✅ Сабаб бор";                  default->"✅ Sabab bor"; };
            case "att.reason_no"         -> switch(l){ case "ru"->"❌ Без уважительной причины";  case "uz_cyr"->"❌ Сабаб йўқ";                  default->"❌ Sabab yo'q"; };
            case "cal.loading"           -> switch(l){ case "ru"->"⏳ Загружается план занятий..."; case "uz_cyr"->"⏳ Дарс режаси юкланмоқда..."; default->"⏳ Dars rejasi yuklanmoqda..."; };
            case "cal.no_data"           -> switch(l){ case "ru"->"📭 Для этого предмета план занятий не найден."; case "uz_cyr"->"📭 Бу фан учун дарс режаси топилмади."; default->"📭 Bu fan uchun dars rejasi topilmadi."; };
            case "cal.plan_label"        -> switch(l){ case "ru"->"план занятий";                 case "uz_cyr"->"режаси";                        default->"rejasi"; };
            case "cal.files_empty"       -> switch(l){ case "ru"->"📁 Для этой темы нет файлов."; case "uz_cyr"->"📁 Бу мавзу учун файллар йўқ."; default->"📁 Bu mavzu uchun fayllar yo'q."; };
            case "cal.files_title"       -> switch(l){ case "ru"->"📂 <b>Файлы</b>";              case "uz_cyr"->"📂 <b>Файллар</b>";              default->"📂 <b>Fayllar</b>"; };
            case "cal.choose_type"       -> switch(l){ case "ru"->"Какой план хотите посмотреть?"; case "uz_cyr"->"Қайси режани кўрмоқчисиз?";   default->"Qaysi rejani ko'rmoqchisiz?"; };
            case "act.loading"           -> switch(l){ case "ru"->"⏳ Загружаются активности..."; case "uz_cyr"->"⏳ Активностлар юкланмоқда..."; default->"⏳ Aktivnostlar yuklanmoqda..."; };
            case "act.no_data"           -> switch(l){ case "ru"->"📭 Данные не загружены.";      case "uz_cyr"->"📭 Маълумот юкланмади.";        default->"📭 Ma'lumot yuklanmadi."; };
            case "act.none"              -> switch(l){ case "ru"->"📭 Заданий нет.";              case "uz_cyr"->"📭 Топшириқлар йўқ.";           default->"📭 Topshiriqlar yo'q."; };
            case "sched.loading"         -> switch(l){ case "ru"->"⏳ Загружается расписание..."; case "uz_cyr"->"⏳ Жадвал юкланмоқда...";       default->"⏳ Jadval yuklanmoqda..."; };
            case "sched.empty"           -> switch(l){ case "ru"->"📭 В этом семестре расписание не найдено."; case "uz_cyr"->"📭 Бу семестрда жадвал топилмади."; default->"📭 Bu semestrda jadval topilmadi."; };
            case "plan.title"            -> switch(l){ case "ru"->"Учебный план";                 case "uz_cyr"->"Ўқув режа";                     default->"O'quv reja"; };
            case "plan.loading"          -> switch(l){ case "ru"->"⏳ Загружается учебный план..."; case "uz_cyr"->"⏳ Ўқув режа юкланмоқда...";  default->"⏳ O'quv reja yuklanmoqda..."; };
            case "plan.semester_suffix"  -> switch(l){ case "ru"->"семестр";                      case "uz_cyr"->"семестр";                       default->"semestr"; };
            case "plan.current"          -> switch(l){ case "ru"->"текущий";                      case "uz_cyr"->"жорий";                         default->"joriy"; };
            case "plan.credits"          -> switch(l){ case "ru"->"кр";                           case "uz_cyr"->"кр";                            default->"kr"; };
            case "plan.gpa_hint"         -> switch(l){ case "ru"->"Выберите метод расчёта GPA 👇"; case "uz_cyr"->"GPA ҳисоблаш усулини танланг 👇"; default->"GPA hisoblash usulini tanlang 👇"; };
            case "plan.gpa_loading"      -> switch(l){ case "ru"->"⏳ Считаю GPA...";             case "uz_cyr"->"⏳ GPA ҳисобланмоқда...";        default->"⏳ GPA hisoblanmoqda..."; };
            case "plan.gpa_mode_graded"  -> switch(l){ case "ru"->"Только оценённые предметы";    case "uz_cyr"->"Фақат баҳоланган фанлар";        default->"Faqat baholangan fanlar"; };
            case "plan.gpa_mode_with_zero"->switch(l){ case "ru"->"Текущий семестр с 0";          case "uz_cyr"->"Жорий семестр 0 билан";          default->"Joriy semestr 0 bilan"; };
            case "plan.calc_credits"     -> switch(l){ case "ru"->"Учтено кредитов";              case "uz_cyr"->"Ҳисобланган кредит";             default->"Hisoblangan kredit"; };
            case "plan.gpa_no_grades"    -> switch(l){ case "ru"->"Нет оценок для расчёта.";      case "uz_cyr"->"Ҳисоблаш учун баҳолар топилмади."; default->"Hisoblash uchun baholar topilmadi."; };
            case "finals.title"          -> switch(l){ case "ru"->"Итоговые экзамены";            case "uz_cyr"->"Якуний имтиҳонлар";             default->"Yakuniy imtihonlar"; };
            case "finals.loading"        -> switch(l){ case "ru"->"⏳ Загружаются итоговые экзамены..."; case "uz_cyr"->"⏳ Якуний имтиҳонлар юкланмоқда..."; default->"⏳ Yakuniy imtihonlar yuklanmoqda..."; };
            case "finals.empty"          -> switch(l){ case "ru"->"📭 В этом семестре итоговых экзаменов не найдено."; case "uz_cyr"->"📭 Бу семестрда якуний имтиҳон топилмади."; default->"📭 Bu semestrda yakuniy imtihon topilmadi."; };
            default -> key;
        };
    }

    // ─────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────

    private boolean checkLogin(long chatId, long userId) {
        if (!lmsService.isLoggedIn(userId)) {
            send(chatId, tr(userId,
                    "⚠️ Сначала войдите в систему! /login",
                    "⚠️ Avval tizimga kiring! /login",
                    "⚠️ Аввал тизимга киринг! /login"), null);
            return false;
        }
        return true;
    }

    private void send(long chatId, String text, Object keyboard) {
        try {
            SendMessage msg = new SendMessage();
            msg.setChatId(String.valueOf(chatId));
            msg.setText(text);
            msg.setParseMode("HTML");
            if (keyboard instanceof ReplyKeyboardMarkup k)       msg.setReplyMarkup(k);
            else if (keyboard instanceof InlineKeyboardMarkup k) msg.setReplyMarkup(k);
            execute(msg);
        } catch (Exception e) {
            System.err.println("[LmsBot] Send error: " + e.getMessage());
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
                    ? tr(userId, "через " + diffMin + " мин", diffMin + " min ichida", diffMin + " мин ичида")
                    : tr(userId, "через " + (diffMin / 60) + " ч", (diffMin / 60) + " soat ichida", (diffMin / 60) + " соат ичида");
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

    /**
     * Парсинг дедлайна активности.
     * LMS возвращает формат: "24-03-2026 23:59:59" (dd-MM-yyyy HH:mm:ss)
     */
    private long parseActivityDeadlineTs(String deadline) {
        if (deadline == null || deadline.isBlank()) return -1;
        String s = deadline.trim();

        // Основной формат LMS: "24-03-2026 23:59:59"
        try {
            return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception ignored) {}

        // Fallback: ISO формат "2026-03-24T23:59:59"
        try {
            return LocalDateTime.parse(s)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception ignored) {}

        // Fallback: "2026-03-24 23:59:59"
        try {
            return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Парсинг времени старта пары из расписания.
     * LMS возвращает формат: "2026-03-24T10:00:00" (ISO с буквой T)
     */
    private long parseScheduleStartTs(String start) {
        if (start == null || start.isBlank()) return -1;
        String s = start.trim();

        // Приоритет: ISO формат "2026-03-24T10:00:00"
        try {
            return LocalDateTime.parse(s)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception ignored) {}

        // Fallback: с пробелом "2026-03-24 10:00:00"
        try {
            return LocalDateTime.parse(s.replace(" ", "T"))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception ignored) {}

        try {
            return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return -1;
        }
    }

    private int detectCurrentSemester(Map<Integer, List<StudyPlanSubject>> bySemester) {
        int max = bySemester.keySet().stream().mapToInt(i -> i).max().orElse(0);
        for (int sem = 1; sem <= max; sem++) {
            List<StudyPlanSubject> list = bySemester.get(sem);
            if (list != null && list.stream().anyMatch(s -> s.getGrade() == null)) return sem;
        }
        return max;
    }

    private String deadlineKey(int courseId, long dl, String task) {
        String safe = safeKey(task).replace(" ", "");
        return courseId + "-" + dl + "-" + safe;
    }

    private String safeKey(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() > 40 ? t.substring(0, 40) : t;
    }

    private boolean markOnce(long userId, String key) {
        return sentReminders.computeIfAbsent(userId, k -> Collections.newSetFromMap(new ConcurrentHashMap<>())).add(key);
    }

    private String truncateTopic(String topic, int maxLen) {
        if (topic == null) return "";
        String t = topic.trim();
        return t.length() <= maxLen ? t : t.substring(0, Math.max(0, maxLen - 1)) + "…";
    }

    private String orDash(String val) { return (val == null || val.isBlank()) ? "—" : val; }

    private String gradeIcon(int g) { return switch(g){ case 5->"🟢"; case 4->"🔵"; case 3->"🟡"; case 2->"🔴"; default->"⬜"; }; }

    private String gpaComment(long userId, double gpa) {
        if (gpa >= 4.5) return tr(userId, "Отличный результат! Так держать! 🚀", "A'lo natija! Davom eting! 🚀", "Аъло натижа! Давом этинг! 🚀");
        if (gpa >= 4.0) return tr(userId, "Хороший результат! 👍", "Yaxshi natija! 👍", "Яхши натижа! 👍");
        if (gpa >= 3.5) return tr(userId, "Выше среднего.", "O'rtacha yaxshi natija.", "Ўртачадан юқори.");
        if (gpa >= 3.0) return tr(userId, "Средний результат, есть куда расти.", "O'rtacha natija, yaxshilash mumkin.", "Ўртача натижа, яхшилаш мумкин.");
        return tr(userId, "Слабый результат, нужно больше усилий! 💪", "Past natija, ko'proq harakat kerak! 💪", "Паст натижа, кўпроқ ҳаракат керак! 💪");
    }

    private String langLabel(String lang) {
        return switch (lang) { case "ru" -> "Русский"; case "uz_cyr" -> "Ўзбекча (кирилл)"; default -> "O'zbekcha (lotin)"; };
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
    private String minutesToLabelUzCyr(int m) {
        return switch(m){ case 1->"1 минут"; case 30->"30 минут"; case 60->"1 соат"; case 180->"3 соат"; case 300->"5 соат"; default->m+" мин"; };
    }

    private int getDefaultSemesterId() {
        List<AppConfig.SemesterConfig> s = config.getSemesters();
        return (s != null && !s.isEmpty()) ? s.get(0).getId() : 49;
    }

    private static class PairInfo { String subject = "—", teacher = "", room = "", time = "—"; }
    private PairInfo parsePairInfo(ScheduleEvent ev) {
        PairInfo p = new PairInfo();
        String start = ev.getStart();
        if (start != null) {
            int tIdx = start.indexOf('T');
            if (tIdx >= 0 && start.length() >= tIdx + 6) {
                p.time = start.substring(tIdx + 1, Math.min(tIdx + 6, start.length()));
            } else if (start.length() >= 16) {
                p.time = start.substring(11, 16);
            }
        }
        String title = ev.getTitle() != null ? ev.getTitle() : "";
        String[] lines = title.split("\n");
        if (lines.length >= 1) p.room = lines[0].trim();
        if (lines.length >= 2) p.subject = lines[1].trim();
        if (lines.length >= 3) p.teacher = lines[2].trim();
        if (p.subject.isBlank()) p.subject = title.replace("\n", " ").trim();
        return p;
    }

    private String esc(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public void shutdown() {
        executor.shutdown();
        scheduler.shutdown();
        try { if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow(); }
        catch (InterruptedException e) { executor.shutdownNow(); }
    }
}