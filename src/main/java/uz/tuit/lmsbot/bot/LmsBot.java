package uz.tuit.lmsbot.bot;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LmsBot extends TelegramLongPollingBot {

    private final AppConfig config;
    private final LmsService lmsService;

    // User-scoped state
    private final Map<Long, String>       userState    = new ConcurrentHashMap<>();
    private final Map<Long, String>       tempLogin    = new ConcurrentHashMap<>();
    private final Map<Long, List<Course>> userCourses  = new ConcurrentHashMap<>();
    private final Map<Long, Integer>      userSemester = new ConcurrentHashMap<>();
    private final Map<Long, String>       userLang     = new ConcurrentHashMap<>(); // ru, uz_lat, uz_cyr
    // For calendar navigation: stored per user
    private final Map<Long, Map<Integer, Map<String, List<CalendarEntry>>>> userCalendars = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, CalendarEntry.FileAttachment>>      pendingFiles  = new ConcurrentHashMap<>();



    // FIX 1: missing fields
    private final Map<Long, Map<Integer, List<Activity>>> userActivities = new ConcurrentHashMap<>();
    private final Map<Long, Long>                         lastChatId     = new ConcurrentHashMap<>();

    // FIX 2: missing inner class + map
    private static class PendingUpload {
        int courseId;
        String activityId;
        int activityIndex;
    }


    private final ExecutorService executor = Executors.newFixedThreadPool(20);
    private final Map<Long, PendingUpload> pendingUpload = new ConcurrentHashMap<>();

    public LmsBot(AppConfig config, LmsService lmsService) {
        this.config = config;
        this.lmsService = lmsService;
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

        // ── File upload handler ─────────────────────
        String state = userState.getOrDefault(userId, "IDLE");
        if ("WAIT_UPLOAD".equals(state) && (msg.hasDocument() || msg.hasPhoto())) {
            userState.put(userId, "IDLE");
            handleActivityFileUpload(chatId, userId, msg);
            return;
        }
        if ("WAIT_UPLOAD".equals(state) && msg.hasText()) {
            // User sent text instead of file
            String txt = msg.getText().trim();
            if ("/cancel".equals(txt) || "bekor".equalsIgnoreCase(txt)) {
                pendingUpload.remove(userId);
                userState.put(userId, "IDLE");
                send(chatId, "❌ Yuklash bekor qilindi.", null);
            } else {
                send(chatId, "⚠️ Iltimos, fayl yuboring (doc, pdf, jpg, zip...) yoki /cancel yozing.", null);
            }
            return;
        }

        // FIX 3: hasText check + String text declaration
        if (!msg.hasText()) return;
        String text = msg.getText().trim();

        if ("WAIT_LOGIN".equals(state)) {
            tempLogin.put(userId, text);
            userState.put(userId, "WAIT_PASSWORD");
            send(chatId, "🔐 Endi parolingizni kiriting:", null);
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
            case "🚪 Chiqish":            handleLogout(chatId, userId); break;
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
            default:
                if (lmsService.isLoggedIn(userId)) sendMainMenu(chatId, userId);
                else sendWelcome(chatId, userId, msg.getFrom().getFirstName());
        }
    }

    private void handleCallback(Update update) {
        long chatId = update.getCallbackQuery().getMessage().getChatId();
        long userId = update.getCallbackQuery().getFrom().getId();
        String data = update.getCallbackQuery().getData();

        if (data.startsWith("lang_")) {
            String lang = data.substring("lang_".length());
            userLang.put(userId, lang);
            String firstName = update.getCallbackQuery().getFrom().getFirstName();
            sendWelcome(chatId, userId, firstName != null ? firstName : "");
        } else if (data.startsWith("semester_")) {
            showCourses(chatId, userId, Integer.parseInt(data.replace("semester_", "")));
        } else if (data.startsWith("file_")) {
            String token = data.substring("file_".length());
            handleFileDownload(chatId, userId, token);
        } else if (data.startsWith("calfiles_")) {
            // calfiles_{courseId}_{type}_{index}
            String[] parts = data.split("_", 4);
            if (parts.length == 4) {
                int courseId = Integer.parseInt(parts[1]);
                String type  = parts[2];
                int index    = Integer.parseInt(parts[3]);
                showCalendarFiles(chatId, userId, courseId, type, index);
            }
        } else if (data.startsWith("calpage_")) {
            // calpage_{courseId}_{type}_{page}
            String[] parts = data.split("_", 4);
            if (parts.length == 4) {
                int courseId = Integer.parseInt(parts[1]);
                String type  = parts[2];
                int page     = Integer.parseInt(parts[3]);
                showCalendarPage(chatId, userId, courseId, type, page);
            }
        } else if (data.startsWith("calentry_")) {
            // calentry_{courseId}_{type}_{index}
            String[] parts = data.split("_", 4);
            if (parts.length == 4) {
                int courseId = Integer.parseInt(parts[1]);
                String type  = parts[2];
                int index    = Integer.parseInt(parts[3]);
                showCalendarEntry(chatId, userId, courseId, type, index);
            }
        } else if (data.startsWith("calback_")) {
            // calback_{courseId}_{type}_{index}
            String[] parts = data.split("_", 4);
            if (parts.length == 4) {
                int courseId = Integer.parseInt(parts[1]);
                String type  = parts[2];
                int index    = Integer.parseInt(parts[3]);
                int page     = Math.max(0, index / 5);
                showCalendarPage(chatId, userId, courseId, type, page);
            }
        } else if ("select_attend".equals(data)) {
            showCourseSelection(chatId, userId, "attend");
        } else if ("select_activities".equals(data)) {
            showCourseSelection(chatId, userId, "activities");
        } else if ("back_to_courses".equals(data)) {
            int semId = userSemester.getOrDefault(userId, getDefaultSemesterId());
            showCourses(chatId, userId, semId);
        } else if ("change_semester".equals(data)) {
            send(chatId, "📅 Semestrni tanlang:", semesterKeyboard());
        } else if (data.startsWith("ai_")) {
            String[] parts = data.replace("ai_", "").split("_");
            int index = Integer.parseInt(parts[0]);
            int semesterId = Integer.parseInt(parts[1]);
            List<Course> courses = userCourses.get(userId);
            if (courses != null && index < courses.size())
                showAttendance(chatId, userId, courses.get(index).getId(), semesterId);
        } else if (data.startsWith("ac_back_")) {
            int courseId = Integer.parseInt(data.replace("ac_back_", ""));
            showActivities(chatId, userId, courseId);
        } else if (data.startsWith("ac_")) {
            int index = Integer.parseInt(data.replace("ac_", ""));
            List<Course> courses = userCourses.get(userId);
            if (courses != null && index < courses.size())
                showActivities(chatId, userId, courses.get(index).getId());
        } else if (data.startsWith("act_sample_")) {
            // act_sample_{courseId}_{actIndex}
            String[] p = data.replace("act_sample_", "").split("_", 2);
            if (p.length == 2) downloadAndSendActivityFile(chatId, userId,
                    Integer.parseInt(p[0]), Integer.parseInt(p[1]), "sample");
        } else if (data.startsWith("act_uploaded_")) {
            // act_uploaded_{courseId}_{actIndex}
            String[] p = data.replace("act_uploaded_", "").split("_", 2);
            if (p.length == 2) downloadAndSendActivityFile(chatId, userId,
                    Integer.parseInt(p[0]), Integer.parseInt(p[1]), "uploaded");
        } else if (data.startsWith("act_upload_")) {
            // act_upload_{courseId}_{actIndex}_{activityId}
            String[] p = data.replace("act_upload_", "").split("_", 3);
            if (p.length == 3) promptActivityUpload(chatId, userId,
                    Integer.parseInt(p[0]), Integer.parseInt(p[1]), p[2]);
        } else if (data.startsWith("act_deadline_list_")) {
            int courseId = Integer.parseInt(data.replace("act_deadline_list_", ""));
            showDeadlineList(chatId, userId, courseId);
        } else if (data.startsWith("act_filter_all_")) {
            int courseId = Integer.parseInt(data.replace("act_filter_all_", ""));
            showActivitiesList(chatId, userId, courseId, "all");
        } else if (data.startsWith("act_filter_uploaded_")) {
            int courseId = Integer.parseInt(data.replace("act_filter_uploaded_", ""));
            showActivitiesList(chatId, userId, courseId, "uploaded");
        } else if (data.startsWith("act_filter_uploadable_")) {
            int courseId = Integer.parseInt(data.replace("act_filter_uploadable_", ""));
            showActivitiesList(chatId, userId, courseId, "uploadable");
        } else if (data.startsWith("act_pick_")) {
            // act_pick_{courseId}_{actIndex}
            String[] p = data.replace("act_pick_", "").split("_", 2);
            if (p.length == 2) showActivityDetails(chatId, userId,
                    Integer.parseInt(p[0]), Integer.parseInt(p[1]));
        } else if (data.startsWith("schedule_")) {
            showSchedule(chatId, userId, Integer.parseInt(data.replace("schedule_", "")));
        } else if ("gpa_all".equals(data)) {
            showStudyPlanGpa(chatId, userId, false);
        } else if ("gpa_with_zero".equals(data)) {
            showStudyPlanGpa(chatId, userId, true);
        } else if ("back_to_study_plan".equals(data)) {
            showStudyPlanFull(chatId, userId);
        } else if (data.startsWith("finals_")) {
            showFinals(chatId, userId, Integer.parseInt(data.replace("finals_", "")));
        } else if ("select_calendar".equals(data)) {
            showCourseSelection(chatId, userId, "calendar");
        } else if (data.startsWith("cal_")) {
            int index = Integer.parseInt(data.replace("cal_", ""));
            List<Course> courses = userCourses.get(userId);
            if (courses != null && index < courses.size())
                showCalendar(chatId, userId, courses.get(index));
        } else if (data.startsWith("caltype_")) {
            String[] parts = data.replace("caltype_", "").split("_", 2);
            int courseId = Integer.parseInt(parts[0]);
            String type  = parts[1];
            showCalendarType(chatId, userId, courseId, type);
        }
    }

    // FIX 4: restored sendWelcome
    private void sendWelcome(long chatId, long userId, String firstName) {
        String lang = userLang.getOrDefault(userId, "uz_lat");
        String text = switch (lang) {
            case "ru"     -> "👋 <b>Привет, " + esc(firstName) + "!</b>\n\n"
                    + "🎓 Добро пожаловать в <b>TUIT LMS Bot</b>!\n\nЧтобы начать, войди в систему 👇";
            case "uz_cyr" -> "👋 <b>Ассалому алайкум, " + esc(firstName) + "!</b>\n\n"
                    + "🎓 <b>TUIT LMS Bot</b>га хуш келибсиз!\n\nБошлаш учун тизимга киринг 👇";
            default       -> "👋 <b>Assalomu alaykum, " + esc(firstName) + "!</b>\n\n"
                    + "🎓 <b>TUIT LMS Bot</b>ga xush kelibsiz!\n\nBoshlash uchun tizimga kiring 👇";
        };
        send(chatId, text, loginKeyboard(userId));
    }

    // FIX 5: restored askForLogin (was broken: missing signature + LmsBot() call)
    private void askForLogin(long chatId, long userId) {
        userState.put(userId, "WAIT_LOGIN");
        String lang = userLang.getOrDefault(userId, "uz_lat");
        String text = switch (lang) {
            case "ru"     -> "👤 <b>Введите ваш LMS логин</b>:\n\nНапример: <code>1bk27748</code>";
            case "uz_cyr" -> "👤 <b>LMS логинингизни</b> киритинг:\n\nМасалан: <code>1bk27748</code>";
            default       -> "👤 <b>LMS loginingizni</b> kiriting:\n\nMasalan: <code>1bk27748</code>";
        };
        send(chatId, text, null);
    }

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
            sb.append("📊 <b>Natijalar</b>\n<blockquote>");
            sb.append("🏆 Ball: <b>").append(orDash(summary.getEarned()))
                    .append("</b> / <b>").append(orDash(summary.getMaxScore())).append("</b>\n");
            sb.append("📈 Muvaffaqiyat: <b>").append(orDash(summary.getProgress())).append("</b>\n");
            sb.append("🎓 Baho: <b>").append(orDash(summary.getGrade())).append("</b>");
            sb.append("</blockquote>\n\n");

            if (activities == null || activities.isEmpty()) {
                sb.append(t(userId, "act.none"));
                send(chatId, sb.toString(), null);
                return;
            }

            // Список дедлайнов в blockquote
            sb.append("📋 <b>Topshiriqlar</b>: <b>").append(activities.size()).append("</b>\n\n");

            long now = System.currentTimeMillis();
            for (int i = 0; i < activities.size(); i++) {
                Activity a = activities.get(i);
                String datePart = (a.getDeadline() != null && a.getDeadline().length() >= 10)
                        ? a.getDeadline().substring(0, 10) : "—";
                long dl = parseActivityDeadlineTs(a.getDeadline());
                boolean expired = dl > 0 && dl <= now;
                boolean hasUpload = a.getUploadedFileUrl() != null && !a.getUploadedFileUrl().isBlank();

                String statusIcon = hasUpload ? "✅" : (expired ? "❌" : "📭");
                String earned = (a.getEarnedScore() != null && !a.getEarnedScore().isBlank()) ? a.getEarnedScore() : "—";
                String max    = (a.getMaxScore()    != null && !a.getMaxScore().isBlank())    ? a.getMaxScore()    : "—";

                String teacher = (a.getTeacher() != null && !a.getTeacher().isBlank())
                        ? a.getTeacher() : "—";
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

            sb.append("\n<i>Topshiriqni tanlash uchun 👇</i>");

            // Кнопка дедлайн + кнопка назад
            List<List<InlineKeyboardButton>> allRows = new ArrayList<>();
            allRows.add(List.of(inlineBtn("⏰ Deadline bo'yicha tanlash", "act_deadline_list_" + courseId)));
            allRows.add(List.of(inlineBtn("🔙 Orqaga", "select_activities")));

            InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
            markup.setKeyboard(allRows);
            send(chatId, sb.toString(), markup);
        });
    }

    private long parseActivityDeadlineTs(String deadline) {
        if (deadline == null) return -1;
        String d = deadline.trim();
        if (d.isEmpty()) return -1;
        try {
            DateTimeFormatter f = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
            LocalDateTime dt = LocalDateTime.parse(d, f);
            return dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            return -1;
        }
    }

    private void showActivitiesList(long chatId, long userId, int courseId, String filter) {
        if (!checkLogin(chatId, userId)) return;
        List<Activity> acts = userActivities
                .getOrDefault(userId, Collections.emptyMap())
                .get(courseId);
        if (acts == null || acts.isEmpty()) {
            send(chatId, t(userId, "act.none"), null);
            return;
        }

        long now = System.currentTimeMillis();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        String title = switch (filter) {
            case "uploaded" -> "📥 <b>Загруженные</b>";
            case "uploadable" -> "⬆️ <b>Загрузить</b>";
            default -> "📋 <b>Задания</b>";
        };

        for (int i = 0; i < acts.size(); i++) {
            Activity a = acts.get(i);
            boolean hasUpload = a.getUploadedFileUrl() != null && !a.getUploadedFileUrl().isBlank();
            boolean hasActId = a.getActivityId() != null && !a.getActivityId().isBlank();

            long dl = parseActivityDeadlineTs(a.getDeadline());
            boolean deadlineOk = dl > 0 && dl > now;

            if ("uploaded".equals(filter) && !hasUpload) continue;
            if ("uploadable".equals(filter)) {
                if (hasUpload) continue;
                if (!hasActId) continue;
                if (!deadlineOk) continue;
            }

            String earned = (a.getEarnedScore() != null && !a.getEarnedScore().isBlank()) ? a.getEarnedScore() : "—";
            String max = (a.getMaxScore() != null && !a.getMaxScore().isBlank()) ? a.getMaxScore() : "—";
            String datePart = (a.getDeadline() != null && a.getDeadline().length() >= 10) ? a.getDeadline().substring(0, 10) : "—";

            String btnText = "📝 " + truncateTopic(a.getTask(), 22)
                    + " | " + datePart
                    + " | " + earned + "/" + max;
            rows.add(List.of(inlineBtn(btnText, "act_pick_" + courseId + "_" + i)));
        }

        if (rows.isEmpty()) {
            String empty = "uploadable".equals(filter)
                    ? "📭 Yuklash uchun topshiriqlar yo'q (hammasi yuklangan yoki muddat o'tgan)."
                    : "📭 Topshiriqlar topilmadi.";
            send(chatId, empty, null);
            return;
        }

        // Filter row (always 3 buttons only)
        List<InlineKeyboardButton> filterRow = new ArrayList<>();
        filterRow.add(inlineBtn("📋 Задания", "act_filter_all_" + courseId));
        filterRow.add(inlineBtn("📥 Загруженные", "act_filter_uploaded_" + courseId));
        filterRow.add(inlineBtn("⬆️ Загрузить", "act_filter_uploadable_" + courseId));
        rows.add(filterRow);

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        send(chatId, title + "\n\nTanlang 👇", markup);
    }

    private void showDeadlineList(long chatId, long userId, int courseId) {
        if (!checkLogin(chatId, userId)) return;
        List<Activity> acts = userActivities
                .getOrDefault(userId, Collections.emptyMap())
                .get(courseId);
        if (acts == null || acts.isEmpty()) {
            send(chatId, t(userId, "act.none"), null);
            return;
        }

        long now = System.currentTimeMillis();

        // Создаём индексированный список и сортируем по дедлайну (возрастание)
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
            String datePart   = (a.getDeadline() != null && a.getDeadline().length() >= 10)
                    ? a.getDeadline().substring(0, 10) : "—";
            String earned = (a.getEarnedScore() != null && !a.getEarnedScore().isBlank()) ? a.getEarnedScore() : "—";
            String max    = (a.getMaxScore()    != null && !a.getMaxScore().isBlank())    ? a.getMaxScore()    : "—";

            // Иконка типа: лекция или практика
            String typeRaw = a.getType() != null ? a.getType().toLowerCase() : "";
            String typeIcon = typeRaw.contains("лек") || typeRaw.contains("lecture") ? "📖" : "🔬";

            String btnText = statusIcon + " " + typeIcon + " " + datePart
                    + " | " + truncateTopic(a.getTask(), 22)
                    + " | " + earned + "/" + max;
            rows.add(List.of(inlineBtn(btnText, "act_pick_" + courseId + "_" + i)));
        }

        // Кнопка назад → экран результатов предмета
        rows.add(List.of(inlineBtn("🔙 Orqaga", "ac_back_" + courseId)));

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        send(chatId,
                "⏰ <b>Deadline bo'yicha topshiriqlar</b>\n\n"
                        + "✅ Yuklangan  |  ❌ Muddat o'tgan  |  📭 Yuklanmagan\n"
                        + "📖 Leksiya  |  🔬 Amaliyot\n\n"
                        + "Tanlang 👇", markup);
    }

    private void showActivityDetails(long chatId, long userId, int courseId, int index) {
        if (!checkLogin(chatId, userId)) return;
        List<Activity> acts = userActivities
                .getOrDefault(userId, Collections.emptyMap())
                .get(courseId);
        if (acts == null || index < 0 || index >= acts.size()) {
            send(chatId, "⚠️ Ma'lumot eskirgan. Aktivnostlarni qayta oching.", null);
            return;
        }
        Activity a = acts.get(index);

        boolean hasSample = a.getSampleFileUrl() != null && !a.getSampleFileUrl().isBlank();
        boolean hasUpload = a.getUploadedFileUrl() != null && !a.getUploadedFileUrl().isBlank();
        boolean hasActId = a.getActivityId() != null && !a.getActivityId().isBlank();
        long now = System.currentTimeMillis();
        long dl = parseActivityDeadlineTs(a.getDeadline());
        boolean deadlineOk = dl > 0 && dl > now;

        String earned = (a.getEarnedScore() != null && !a.getEarnedScore().isBlank()) ? a.getEarnedScore() : "—";
        String max = (a.getMaxScore() != null && !a.getMaxScore().isBlank()) ? a.getMaxScore() : "—";

        String text = "📝 <b>" + esc(a.getTask()) + "</b>\n"
                + "👨‍🏫 " + esc(a.getTeacher()) + "\n"
                + "⏰ Deadline: <b>" + esc(a.getDeadline()) + "</b>\n"
                + "🏆 Ball: <b>" + earned + "</b> / <b>" + max + "</b>\n"
                + (hasUpload ? "✅ Загружено" : "📭 Не загружено");

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();
        if (hasSample) row.add(inlineBtn("📄 Topshiriq", "act_sample_" + courseId + "_" + index));
        if (hasUpload) row.add(inlineBtn("📥 Yuklanganim", "act_uploaded_" + courseId + "_" + index));
        if (!row.isEmpty()) rows.add(row);

        // Загрузить: если нет файла и срок не прошёл
        if (!hasUpload && hasActId && deadlineOk) {
            rows.add(List.of(inlineBtn("⬆️ Yuklash", "act_upload_" + courseId + "_" + index + "_" + a.getActivityId())));
        }
        // Переюзать: если файл есть И срок ещё не прошёл
        if (hasUpload && hasActId && deadlineOk) {
            rows.add(List.of(inlineBtn("🔄 Qayta yuklash", "act_upload_" + courseId + "_" + index + "_" + a.getActivityId())));
        }

        rows.add(List.of(inlineBtn("🔙 Orqaga", "act_deadline_list_" + courseId)));

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        send(chatId, text, markup);
    }

    private void handleLogin(long chatId, long userId, String login, String password) {
        String lang = userLang.getOrDefault(userId, "uz_lat");
        String loading = switch (lang) {
            case "ru"     -> "⏳ Входим в систему...";
            case "uz_cyr" -> "⏳ Тизимга кирилмоқда...";
            default       -> "⏳ Tizimga kirilmoqda...";
        };
        send(chatId, loading, null);
        executor.submit(() -> {
            boolean ok = lmsService.login(userId, login, password);
            String langInner = userLang.getOrDefault(userId, "uz_lat");
            if (ok) {
                String msg = switch (langInner) {
                    case "ru"     -> "✅ <b>Вы успешно вошли!</b>\n\nВыберите пункт из меню ниже 👇";
                    case "uz_cyr" -> "✅ <b>Муффақиятли кирдингиз!</b>\n\nҚуйидаги менюдан фойдаланинг 👇";
                    default       -> "✅ <b>Muvaffaqiyatli kirdingiz!</b>\n\nQuyidagi menyudan foydalaning 👇";
                };
                send(chatId, msg, mainMenuKeyboard(userId));
            } else {
                String err = switch (langInner) {
                    case "ru"     -> "❌ <b>Неверный логин или пароль!</b>\n\nПопробуйте ещё раз.";
                    case "uz_cyr" -> "❌ <b>Логин ёки парол нотўғри!</b>\n\nҚайта уриниб кўринг.";
                    default       -> "❌ <b>Login yoki parol noto'g'ri!</b>\n\nQayta urinib ko'ring.";
                };
                send(chatId, err, loginKeyboard(userId));
            }
        });
    }

    private void handleLogout(long chatId, long userId) {
        lmsService.logout(userId);
        userCourses.remove(userId);
        userSemester.remove(userId);
        String lang = userLang.getOrDefault(userId, "uz_lat");
        String text = switch (lang) {
            case "ru"     -> "👋 Вы вышли из системы.\n\nЧтобы войти снова: /login";
            case "uz_cyr" -> "👋 Тизимдан чиқдингиз.\n\nҚайта кириш: /login";
            default       -> "👋 Tizimdan chiqtingiz.\n\nQayta kirish: /login";
        };
        send(chatId, text, loginKeyboard(userId));
    }

    private void sendMainMenu(long chatId, long userId) {
        String lang = userLang.getOrDefault(userId, "uz_lat");
        String text = switch (lang) {
            case "ru"     -> "🏠 <b>Главное меню:</b>";
            case "uz_cyr" -> "🏠 <b>Асосий меню:</b>";
            default       -> "🏠 <b>Asosiy menyu:</b>";
        };
        send(chatId, text, mainMenuKeyboard(userId));
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
                        + (c.getAttendance() > 0 ? "🔴" : "🟢") + " НБ: <b>" + c.getAttendance() + "</b>";
                msg.append("<blockquote>").append(inner).append("</blockquote>\n");
            }

            msg.append("\n📌 ").append(t(userId, "courses.total")).append(": <b>")
                    .append(courses.size()).append("</b> ")
                    .append(t(userId, "courses.subjects_suffix"));
            send(chatId, msg.toString(), coursesActionKeyboard(userId));
        });
    }

    // ─────────────────────────────────────────────
    //  SCHEDULE
    // ─────────────────────────────────────────────

    private void showSchedule(long chatId, long userId, int semesterId) {
        if (!checkLogin(chatId, userId)) return;
        send(chatId, "⏳ Jadval yuklanmoqda...", null);

        executor.submit(() -> {
            List<ScheduleEvent> events = lmsService.getSchedule(userId, semesterId);
            String semName = config.getSemesterMap().getOrDefault(semesterId, "Semester " + semesterId);

            if (events == null || events.isEmpty()) {
                send(chatId, "📭 Bu semestrda jadval topilmadi.", semesterScheduleKeyboard());
                return;
            }

            Map<String, List<ScheduleEvent>> byDate = new LinkedHashMap<>();
            for (ScheduleEvent ev : events)
                byDate.computeIfAbsent(ev.getStart().substring(0, 10), k -> new ArrayList<>()).add(ev);
            for (List<ScheduleEvent> list : byDate.values())
                list.sort(Comparator.comparing(ScheduleEvent::getStart));

            String[] dayNames = {"","Dushanba","Seshanba","Chorshanba","Payshanba","Juma","Shanba","Yakshanba"};

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
                } catch (Exception e) {
                    dayHeader = dateStr;
                }
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
        send(chatId, "⏳ O'quv reja yuklanmoqda...", null);

        executor.submit(() -> {
            List<StudyPlanSubject> subjects = lmsService.getStudyPlan(userId);
            if (subjects.isEmpty()) { send(chatId, "❌ Ma'lumot yuklanmadi.", null); return; }

            Map<Integer, List<StudyPlanSubject>> bySemester = new LinkedHashMap<>();
            for (StudyPlanSubject s : subjects)
                bySemester.computeIfAbsent(s.getSemester(), k -> new ArrayList<>()).add(s);

            int currentSemester = detectCurrentSemester(bySemester);
            String[] roman = {"","I","II","III","IV","V","VI","VII","VIII"};

            String[] semColors = {"🟡","🟢","🔵","🟣","🟠","🔴","⚪","🟤"};
            StringBuilder planMsg = new StringBuilder();
            planMsg.append("📖 <b>O'quv reja</b>\n\n");

            for (Map.Entry<Integer, List<StudyPlanSubject>> entry : bySemester.entrySet()) {
                int sem = entry.getKey();
                boolean isCurrent = sem == currentSemester;
                String semLabel = sem < roman.length ? roman[sem] : String.valueOf(sem);
                String dot = isCurrent ? "📌" : semColors[(sem - 1) % semColors.length];

                StringBuilder block = new StringBuilder();
                block.append(dot).append(" <b>").append(semLabel).append("-semestr</b>");
                if (isCurrent) block.append(" <i>(joriy)</i>");
                block.append("\n");
                for (StudyPlanSubject s : entry.getValue()) {
                    String gradeStr = s.getGrade() != null
                            ? gradeIcon(s.getGrade()) + " <b>" + s.getGrade() + "</b>"
                            : "";
                    block.append(esc(s.getName()))
                            .append(" <i>(").append(s.getCredits()).append(" kr)</i>");
                    if (!gradeStr.isEmpty()) block.append(" — ").append(gradeStr);
                    block.append("\n");
                }
                planMsg.append("<blockquote>").append(block.toString().trim()).append("</blockquote>\n");
            }

            planMsg.append("\n<blockquote>")
                    .append("🟢 A'lo (5)  🔵 Yaxshi (4)  🟡 Qoniqarli (3)  🔴 Qoniqarsiz (2)")
                    .append("</blockquote>\n");
            planMsg.append("\n💡 GPA hisoblash uchun tanlang 👇");
            send(chatId, planMsg.toString(), gpaChoiceKeyboard());
        });
    }

    private void showStudyPlanGpa(long chatId, long userId, boolean includeCurrentWithZero) {
        send(chatId, "⏳ GPA hisoblanmoqda...", null);

        executor.submit(() -> {
            List<StudyPlanSubject> subjects = lmsService.getStudyPlan(userId);
            if (subjects.isEmpty()) { send(chatId, "❌ Ma'lumot yuklanmadi.", null); return; }

            Map<Integer, List<StudyPlanSubject>> bySemester = new LinkedHashMap<>();
            for (StudyPlanSubject s : subjects)
                bySemester.computeIfAbsent(s.getSemester(), k -> new ArrayList<>()).add(s);

            int currentSemester = detectCurrentSemester(bySemester);
            long totalCredits = 0, totalPoints = 0;
            String mode = includeCurrentWithZero ? "Joriy semestr 0 bilan" : "Faqat baholangan fanlar";

            for (Map.Entry<Integer, List<StudyPlanSubject>> entry : bySemester.entrySet()) {
                int sem = entry.getKey();
                boolean isCurrent = sem == currentSemester;
                for (StudyPlanSubject s : entry.getValue()) {
                    int gradeForCalc;
                    if (s.getGrade() != null) {
                        gradeForCalc = s.getGrade();
                    } else if (isCurrent && includeCurrentWithZero) {
                        gradeForCalc = 0;
                    } else {
                        continue;
                    }
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
                        .append("📚 Hisoblangan kredit: <b>").append(totalCredits).append("</b>\n")
                        .append("✨ ").append(esc(gpaComment(gpa)))
                        .append("</blockquote>");
            } else {
                gpaMsg.append("<blockquote>⚠️ Hisoblash uchun baholar topilmadi.</blockquote>");
            }

            // Кнопка назад на план
            InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
            markup.setKeyboard(List.of(List.of(inlineBtn("🔙 O'quv rejaga qaytish", "back_to_study_plan"))));
            send(chatId, gpaMsg.toString(), markup);
        });
    }

    // ─────────────────────────────────────────────
    //  COURSE SELECTION
    // ─────────────────────────────────────────────

    private void showCourseSelection(long chatId, long userId, String action) {
        List<Course> courses = userCourses.get(userId);
        int semesterId = userSemester.getOrDefault(userId, getDefaultSemesterId());

        if (courses == null || courses.isEmpty()) {
            send(chatId, "⚠️ Avval \"Mening fanlarim\" bo'limini oching.", null);
            return;
        }
        String title = "attend".equals(action)   ? "📊 <b>Davomat</b> — fan tanlang:"
                : "calendar".equals(action) ? "📆 <b>Dars rejasi</b> — fan tanlang:"
                : "📋 <b>Aktivnosti</b> — fan tanlang:";

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (int i = 0; i < courses.size(); i++) {
            Course c = courses.get(i);
            String label  = (c.isFailed() ? "⚠️ " : "") + c.getSubject();
            String cbData = "attend".equals(action)   ? "ai_" + i + "_" + semesterId
                    : "calendar".equals(action) ? "cal_" + i
                    : "ac_" + i;
            rows.add(List.of(inlineBtn(label, cbData)));
        }
        rows.add(List.of(inlineBtn("🔙 Mening fanlarim", "back_to_courses")));
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        send(chatId, title, markup);
    }

    // ─────────────────────────────────────────────
    //  ATTENDANCE
    // ─────────────────────────────────────────────

    private void showAttendance(long chatId, long userId, int subjectId, int semesterId) {
        if (!checkLogin(chatId, userId)) return;
        send(chatId, t(userId, "att.loading"), null);

        executor.submit(() -> {
            // subjectId is LMS internal ID; for filtering we rely on subject name
            List<Course> courses = userCourses.get(userId);
            String subjectName = null;
            if (courses != null) {
                for (Course c : courses) {
                    if (c.getId() == subjectId) {
                        subjectName = c.getSubject();
                        break;
                    }
                }
            }

            List<AttendanceRecord> records = lmsService.getAttendance(userId, subjectId, semesterId, subjectName);
            if (records.isEmpty()) { send(chatId, t(userId, "att.none"), null); return; }

            AttendanceRecord summary = records.get(0);
            List<AttendanceRecord> missed = records.subList(1, records.size());

            if (missed.isEmpty()) {
                StringBuilder ok = new StringBuilder();
                ok.append(t(userId, "att.all_ok_title")).append("\n\n")
                        .append("<blockquote>")
                        .append(t(userId, "att.total_lessons")).append(": <b>").append(summary.getTotal()).append("</b>")
                        .append("</blockquote>");
                InlineKeyboardMarkup backMarkup = new InlineKeyboardMarkup();
                backMarkup.setKeyboard(List.of(List.of(inlineBtn("🔙 Orqaga", "select_attend"))));
                send(chatId, ok.toString(), backMarkup);
                return;
            }

            StringBuilder sb = new StringBuilder();
            String subjectNameFromData = missed.get(0).getSubject();
            String title = subjectName != null && !subjectName.isEmpty()
                    ? subjectName
                    : subjectNameFromData;

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

            InlineKeyboardMarkup attMarkup = new InlineKeyboardMarkup();
            attMarkup.setKeyboard(List.of(List.of(inlineBtn("🔙 Orqaga", "select_attend"))));
            send(chatId, sb.toString(), attMarkup);
        });
    }

    /** Download sample or uploaded file and send to Telegram */
    private void downloadAndSendActivityFile(long chatId, long userId, int courseId, int actIndex, String which) {
        if (!checkLogin(chatId, userId)) return;
        List<Activity> acts = userActivities
                .getOrDefault(userId, Collections.emptyMap())
                .get(courseId);
        if (acts == null || actIndex >= acts.size()) {
            send(chatId, "⚠️ Ma'lumot eskirgan. Aktivnostlarni qayta oching.", null);
            return;
        }
        Activity act = acts.get(actIndex);
        String url  = "sample".equals(which) ? act.getSampleFileUrl()  : act.getUploadedFileUrl();
        String name = "sample".equals(which) ? act.getSampleFileName() : act.getUploadedFileName();
        if (url == null || url.isBlank()) { send(chatId, "📭 Fayl topilmadi.", null); return; }
        send(chatId, "⏳ Fayl yuklanmoqda...", null);
        executor.submit(() -> {
            java.io.File tmp = null;
            try {
                LmsService.DownloadedFile dl = lmsService.downloadFile(userId, url, name);
                tmp = dl.file();
                SendDocument doc = new SendDocument();
                doc.setChatId(String.valueOf(chatId));
                doc.setDocument(new InputFile(tmp, dl.filename()));
                execute(doc);
            } catch (Exception e) {
                send(chatId, "❌ Faylni yuklab bo'lmadi: " + esc(e.getMessage()), null);
            } finally {
                if (tmp != null) try { tmp.delete(); } catch (Exception ign) {}
            }
        });
    }

    /** Tell user to send a file for upload */
    private void promptActivityUpload(long chatId, long userId, int courseId, int actIndex, String activityId) {
        if (!checkLogin(chatId, userId)) return;
        List<Activity> acts = userActivities
                .getOrDefault(userId, Collections.emptyMap())
                .get(courseId);
        String taskName = (acts != null && actIndex < acts.size()) ? acts.get(actIndex).getTask() : "";

        PendingUpload pu = new PendingUpload();
        pu.courseId      = courseId;
        pu.activityId    = activityId;
        pu.activityIndex = actIndex;
        pendingUpload.put(userId, pu);
        userState.put(userId, "WAIT_UPLOAD");

        String header = taskName.isBlank() ? "" : "📝 " + esc(taskName) + "\n\n";
        String text = "📤 <b>Topshiriqni yuborish</b>\n\n"
                + header
                + "<blockquote>• Maks. hajm: 50 MB\n"
                + "• Turlari: jpg, png, doc, docx, pdf, ppt, pptx, zip, rar</blockquote>\n\n"
                + "Fayl yuboring yoki /cancel yozing.";
        send(chatId, text, null);
    }

    /** Handle file received in WAIT_UPLOAD state */
    private void handleActivityFileUpload(long chatId, long userId, Message msg) {
        PendingUpload pu = pendingUpload.remove(userId);
        if (pu == null) { send(chatId, "⚠️ Upload kontekst yo'q. Qayta urinib ko'ring.", null); return; }
        send(chatId, "⏳ Fayl LMS ga yuklanmoqda...", null);
        executor.submit(() -> {
            java.io.File tmp = null;
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
                    send(chatId, "❌ Noto'g'ri fayl turi. Ruxsat: jpg, png, doc, docx, pdf, ppt, pptx, zip, rar", null);
                    return;
                }
                org.telegram.telegrambots.meta.api.methods.GetFile gf =
                        new org.telegram.telegrambots.meta.api.methods.GetFile();
                gf.setFileId(fileId);
                org.telegram.telegrambots.meta.api.objects.File tgFile = execute(gf);
                tmp = java.io.File.createTempFile("lmsup_", "_" + filename);
                downloadFile(tgFile, tmp);
                if (tmp.length() > 50L * 1024 * 1024) {
                    send(chatId, "❌ Fayl juda katta (maks. 50 MB).", null); return;
                }
                boolean ok = lmsService.uploadActivityFile(userId, pu.courseId, pu.activityId, tmp, filename);
                if (ok) {
                    send(chatId, "✅ <b>Fayl muvaffaqiyatli LMS ga yuklandi!</b>", null);
                    showActivities(chatId, userId, pu.courseId);
                } else {
                    send(chatId, "❌ LMS ga yuklashda xatolik. Keyinroq urinib ko'ring.", null);
                }
            } catch (Exception e) {
                send(chatId, "❌ Xatolik: " + esc(e.getMessage()), null);
            } finally {
                if (tmp != null) try { tmp.delete(); } catch (Exception ign) {}
            }
        });
    }

    // FIX 6: restored handleFileDownload (was completely missing)
    private void handleFileDownload(long chatId, long userId, String token) {
        if (!checkLogin(chatId, userId)) return;
        CalendarEntry.FileAttachment att = pendingFiles
                .getOrDefault(userId, Collections.emptyMap())
                .get(token);
        if (att == null) {
            send(chatId, "⚠️ Fayl havolasi eskirgan. \"📆 Fan rejasi\" bo'limidan qayta ochib ko'ring.", null);
            return;
        }

        // Ссылка — отправляем текстом чтобы открылась в браузере
        if ("url".equals(att.getType())) {
            send(chatId, "🔗 <b>" + esc(att.getName()) + "</b>\n\n" + att.getUrl(), null);
            return;
        }

        send(chatId, "⏳ Fayl yuklanmoqda...", null);
        executor.submit(() -> {
            java.io.File tmp = null;
            try {
                LmsService.DownloadedFile dl = lmsService.downloadFile(userId, att.getUrl(), att.getName());
                tmp = dl.file();
                SendDocument doc = new SendDocument();
                doc.setChatId(String.valueOf(chatId));
                doc.setDocument(new InputFile(tmp, dl.filename()));
                execute(doc);
            } catch (Exception e) {
                send(chatId, "❌ Faylni yuklab bo'lmadi: " + esc(e.getMessage()), null);
            } finally {
                if (tmp != null) try { tmp.delete(); } catch (Exception ign) {}
            }
        });
    }

    // ─────────────────────────────────────────────
    //  CALENDAR
    // ─────────────────────────────────────────────

    private void showCalendar(long chatId, long userId, Course course) {
        if (!checkLogin(chatId, userId)) return;
        send(chatId, t(userId, "cal.loading"), null);

        executor.submit(() -> {            Map<String, List<CalendarEntry>> calendar = lmsService.getCalendar(userId, course.getId());
            if (calendar.isEmpty()) { send(chatId, t(userId, "cal.no_data"), null); return; }

            // cache per user / course
            userCalendars
                    .computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                    .put(course.getId(), calendar);

            boolean hasL = calendar.containsKey("lecture");
            boolean hasP = calendar.containsKey("practice");

            if (hasL && !hasP) {
                showCalendarPage(chatId, userId, course.getId(), "lecture", 0);
            } else if (!hasL && hasP) {
                showCalendarPage(chatId, userId, course.getId(), "practice", 0);
            } else {
                int cid = course.getId();
                InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
                markup.setKeyboard(List.of(List.of(
                        inlineBtn("📖 Lektsiya",  "caltype_" + cid + "_lecture"),
                        inlineBtn("🔬 Amaliyot",  "caltype_" + cid + "_practice")
                )));
                send(chatId, "📆 <b>" + esc(course.getSubject()) + "</b>\n\nQaysi rejani ko'rmoqchisiz?", markup);
            }
        });
    }

    private void showCalendarType(long chatId, long userId, int courseId, String type) {
        if (!checkLogin(chatId, userId)) return;
        send(chatId, "⏳ Yuklanmoqda...", null);

        executor.submit(() -> {
            Map<String, List<CalendarEntry>> calendar = userCalendars
                    .getOrDefault(userId, Collections.emptyMap())
                    .get(courseId);
            if (calendar == null || calendar.isEmpty()) {
                // fallback: reload if cache was lost
                calendar = lmsService.getCalendar(userId, courseId);
                if (calendar.isEmpty()) { send(chatId, "📭 Ma'lumot topilmadi.", null); return; }
                userCalendars
                        .computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                        .put(courseId, calendar);
            }
            List<CalendarEntry> entries = calendar.get(type);
            if (entries == null || entries.isEmpty()) { send(chatId, "📭 Ma'lumot topilmadi.", null); return; }
            String courseName = "";
            List<Course> courses = userCourses.get(userId);
            if (courses != null)
                for (Course c : courses)
                    if (c.getId() == courseId) { courseName = c.getSubject(); break; }
            // first page (0)
            showCalendarPage(chatId, userId, courseId, type, 0);
        });
    }

    /**
     * Show list of topics (calendar entries) with pagination (5 per page).
     */
    private void showCalendarPage(long chatId, long userId, int courseId, String type, int page) {
        Map<String, List<CalendarEntry>> calendar = userCalendars
                .getOrDefault(userId, Collections.emptyMap())
                .get(courseId);
        if (calendar == null || calendar.isEmpty()) {
            send(chatId, t(userId, "common.no_data"), null);
            return;
        }
        List<CalendarEntry> entries = calendar.get(type);
        if (entries == null || entries.isEmpty()) {
            send(chatId, "📭 Ma'lumot topilmadi.", null);
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
        String typeName = "lecture".equals(type) ? "Lektsiya" : "Amaliyot";

        StringBuilder sb = new StringBuilder();
        sb.append("📆 <b>").append(esc(subject)).append("</b>\n");
        sb.append("<i>").append(esc(typeName)).append(" ").append(esc(t(userId, "cal.plan_label"))).append("</i>\n");
        sb.append(t(userId, "common.page")).append(" ").append(page + 1).append(" / ").append(maxPage + 1).append("\n\n");

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        for (int i = from; i < to; i++) {
            CalendarEntry e = entries.get(i);
            String text = e.getNumber() + ". " + truncateTopic(e.getTopic(), 40)
                    + " (" + e.getDate() + ")";
            rows.add(List.of(inlineBtn(text, "calentry_" + courseId + "_" + type + "_" + i)));
        }

        // pagination row
        List<InlineKeyboardButton> navRow = new ArrayList<>();
        if (page > 0) {
            navRow.add(inlineBtn(t(userId, "btn.prev"), "calpage_" + courseId + "_" + type + "_" + (page - 1)));
        }
        if (page < maxPage) {
            navRow.add(inlineBtn(t(userId, "btn.next"), "calpage_" + courseId + "_" + type + "_" + (page + 1)));
        }
        if (!navRow.isEmpty()) rows.add(navRow);

        // back to course calendar type choice
        rows.add(List.of(inlineBtn(t(userId, "btn.back"), "select_calendar")));

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        send(chatId, sb.toString(), markup);
    }

    /**
     * Show single topic details + button "Files".
     */
    private void showCalendarEntry(long chatId, long userId, int courseId, String type, int index) {
        Map<String, List<CalendarEntry>> calendar = userCalendars
                .getOrDefault(userId, Collections.emptyMap())
                .get(courseId);
        if (calendar == null || calendar.isEmpty()) {
            send(chatId, "📭 Ma'lumot topilmadi.", null);
            return;
        }
        List<CalendarEntry> entries = calendar.get(type);
        if (entries == null || index < 0 || index >= entries.size()) {
            send(chatId, t(userId, "common.no_data"), null);
            return;
        }
        CalendarEntry e = entries.get(index);

        String subject = "";
        List<Course> courses = userCourses.get(userId);
        if (courses != null)
            for (Course c : courses)
                if (c.getId() == courseId) { subject = c.getSubject(); break; }
        String typeName = "lecture".equals(type) ? "Lektsiya" : "Amaliyot";

        StringBuilder sb = new StringBuilder();
        sb.append("📆 <b>").append(esc(subject)).append("</b>\n");
        sb.append("<i>").append(esc(typeName)).append("</i>\n\n");
        sb.append("<b>").append(e.getNumber()).append(".</b> ").append(esc(e.getTopic())).append("\n");
        sb.append("📅 <i>").append(esc(e.getDate())).append("</i>");

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // files button (файлы не перечисляем в тексте, только отдельный шаг)
        rows.add(List.of(inlineBtn(t(userId, "btn.files"), "calfiles_" + courseId + "_" + type + "_" + index)));

        // back to list (page is inferred from index)
        rows.add(List.of(inlineBtn(t(userId, "btn.back"), "calback_" + courseId + "_" + type + "_" + index)));

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        send(chatId, sb.toString(), markup);
    }

    /**
     * Show list of files for selected topic; user then chooses which one to download.
     */
    private void showCalendarFiles(long chatId, long userId, int courseId, String type, int index) {
        Map<String, List<CalendarEntry>> calendar = userCalendars
                .getOrDefault(userId, Collections.emptyMap())
                .get(courseId);
        if (calendar == null || calendar.isEmpty()) {
            send(chatId, t(userId, "common.no_data"), null);
            return;
        }
        List<CalendarEntry> entries = calendar.get(type);
        if (entries == null || index < 0 || index >= entries.size()) {
            send(chatId, t(userId, "common.no_data"), null);
            return;
        }
        CalendarEntry e = entries.get(index);
        List<CalendarEntry.FileAttachment> files = e.getFiles();
        if (files == null || files.isEmpty()) {
            send(chatId, t(userId, "cal.files_empty"), null);
            return;
        }

        // Очистим прошлые токены и создадим новые только для этого набора файлов
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
                case "pdf"   -> "📄";
                case "ppt"   -> "📊";
                case "doc"   -> "📝";
                case "video" -> "🎬";
                case "url"   -> "🔗";
                default      -> "📎";
            };
            String btnText = icon + " " + truncateTopic(f.getName(), 40);

            if ("url".equals(f.getType())) {
                // Ссылка — открывает браузер напрямую
                InlineKeyboardButton btn = new InlineKeyboardButton();
                btn.setText(btnText);
                btn.setUrl(f.getUrl());
                rows.add(List.of(btn));
            } else {
                // Файл — скачивается через бота и шлётся в чат
                String token = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
                userMap.put(token, f);
                rows.add(List.of(inlineBtn(btnText, "file_" + token)));
            }
        }

        // back to topic details
        rows.add(List.of(inlineBtn("🔙 Orqaga", "calentry_" + courseId + "_" + type + "_" + index)));

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        send(chatId, sb.toString(), markup);
    }

    private String truncateTopic(String topic, int maxLen) {
        if (topic == null) return "";
        String t = topic.trim();
        if (t.length() <= maxLen) return t;
        return t.substring(0, Math.max(0, maxLen - 1)) + "…";
    }

    // ─────────────────────────────────────────────
    //  PROFILE
    // ─────────────────────────────────────────────

    private void showProfile(long chatId, long userId) {
        if (!checkLogin(chatId, userId)) return;
        send(chatId, "⏳ Profil yuklanmoqda...", null);

        executor.submit(() -> {
            StudentInfo info = lmsService.getStudentInfo(userId);
            if (info == null) { send(chatId, "❌ Ma'lumot yuklanmadi.", null); return; }

            StringBuilder sb = new StringBuilder();
            sb.append("👤 <b>Talaba profili</b>\n");
            sb.append("┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄\n\n");

            StringBuilder p1 = new StringBuilder();
            row(p1, "👤 F.I.O",         info.getFullName());
            row(p1, "🎂 Tug'ilgan kun", info.getBirthDate());
            row(p1, "⚧ Jinsi",          info.getGender());
            row(p1, "📒 Zach. daftari", info.getRecordBook());
            row(p1, "🏠 Manzil",         info.getAddress());
            sb.append("📌 <b>Shaxsiy ma'lumotlar</b>\n")
                    .append("<blockquote>").append(p1.toString().trim()).append("</blockquote>\n\n");

            StringBuilder p2 = new StringBuilder();
            row(p2, "🎓 Yo'nalish",    info.getDirection());
            row(p2, "🌐 O'qish tili",  info.getLanguage());
            row(p2, "📜 Daraja",        info.getDegree());
            row(p2, "🏫 O'qish turi",  info.getStudyType());
            row(p2, "📅 Kurs",          info.getCourse());
            row(p2, "👥 Guruh",         info.getGroup());
            row(p2, "👨‍🏫 Kurator",      info.getCurator());
            row(p2, "💰 Stipendiya",    info.getScholarship());
            sb.append("📚 <b>O'quv ma'lumotlari</b>\n")
                    .append("<blockquote>").append(p2.toString().trim()).append("</blockquote>");

            send(chatId, sb.toString(), null);
        });
    }

    private void row(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank())
            sb.append(label).append(": <b>").append(esc(value)).append("</b>\n");
    }

    // ─────────────────────────────────────────────
    //  FINALS
    // ─────────────────────────────────────────────

    private void showFinals(long chatId, long userId, int semesterId) {
        if (!checkLogin(chatId, userId)) return;
        send(chatId, "⏳ Yakuniy imtihonlar yuklanmoqda...", null);

        executor.submit(() -> {
            List<FinalExam> exams = lmsService.getFinals(userId, semesterId);
            String semName = config.getSemesterMap().getOrDefault(semesterId, "Semester " + semesterId);

            if (exams.isEmpty()) {
                send(chatId, "📭 Bu semestrda yakuniy imtihon topilmadi.", finalsSemesterKeyboard());
                return;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("🏆 <b>Yakuniy imtihonlar</b>\n");
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
                    exBlock.append("🔢 Поток: ").append(esc(ex.getStream())).append("\n");
                if (ex.getDate() != null && !ex.getDate().isBlank()) {
                    exBlock.append("📅 <b>").append(esc(ex.getDate())).append("</b>");
                    if (ex.getFrom() != null && !ex.getFrom().isBlank())
                        exBlock.append(" 🕐 <b>").append(esc(ex.getFrom())).append("</b>");
                    exBlock.append("\n");
                }
                if (ex.getRoom() != null && !ex.getRoom().isBlank())
                    exBlock.append("🚪 <b>").append(esc(ex.getRoom())).append("</b>\n");
                exBlock.append(gradeIcon).append(" Ball: <b>").append(esc(ex.getGrade())).append("</b>");
                sb.append("<blockquote>").append(exBlock).append("</blockquote>\n");
            }

            send(chatId, sb.toString(), finalsSemesterKeyboard());
        });
    }

    // ─────────────────────────────────────────────
    //  KEYBOARDS
    // ─────────────────────────────────────────────

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
        InlineKeyboardMarkup m = new InlineKeyboardMarkup();
        m.setKeyboard(rows);
        return m;
    }

    private int detectCurrentSemester(Map<Integer, List<StudyPlanSubject>> bySemester) {
        int maxSemester = bySemester.keySet().stream().mapToInt(i -> i).max().orElse(0);
        for (int sem = 1; sem <= maxSemester; sem++) {
            List<StudyPlanSubject> list = bySemester.get(sem);
            if (list != null && list.stream().anyMatch(s -> s.getGrade() == null)) return sem;
        }
        return maxSemester;
    }

    private ReplyKeyboardMarkup loginKeyboard(long userId) {
        String lang = userLang.getOrDefault(userId, "uz_lat");
        String label = switch (lang) {
            case "ru"     -> "🔑 Войти";
            case "uz_cyr" -> "🔑 Кириш";
            default       -> "🔑 Kirish";
        };
        KeyboardRow row = new KeyboardRow();
        row.add(new KeyboardButton(label));
        ReplyKeyboardMarkup m = new ReplyKeyboardMarkup();
        m.setKeyboard(List.of(row));
        m.setResizeKeyboard(true);
        m.setOneTimeKeyboard(false);
        return m;
    }

    private ReplyKeyboardMarkup mainMenuKeyboard(long userId) {
        String lang = userLang.getOrDefault(userId, "uz_lat");
        String myCourses = switch (lang) {
            case "ru"     -> "📚 Мои предметы";
            case "uz_cyr" -> "📚 Менинг фанларим";
            default       -> "📚 Mening fanlarim";
        };
        String schedule = switch (lang) {
            case "ru"     -> "📅 Расписание";
            case "uz_cyr" -> "📅 Дарс жадвали";
            default       -> "📅 Dars jadvali";
        };
        String plan = switch (lang) {
            case "ru"     -> "📖 Учебный план";
            case "uz_cyr" -> "📖 Ўқув режа";
            default       -> "📖 O'quv reja";
        };
        String finals = switch (lang) {
            case "ru"     -> "🏆 Итоговый экзамен";
            case "uz_cyr" -> "🏆 Якуний имтиҳон";
            default       -> "🏆 Yakuniy imtihon";
        };
        String profile = switch (lang) {
            case "ru"     -> "👤 Профиль";
            case "uz_cyr" -> "👤 Профил";
            default       -> "👤 Profil";
        };
        String logout = switch (lang) {
            case "ru"     -> "🚪 Выйти";
            case "uz_cyr" -> "🚪 Чиқиш";
            default       -> "🚪 Chiqish";
        };

        KeyboardRow r1 = new KeyboardRow(); r1.add(new KeyboardButton(myCourses));
        KeyboardRow r2 = new KeyboardRow();
        r2.add(new KeyboardButton(schedule)); r2.add(new KeyboardButton(plan));
        KeyboardRow r3 = new KeyboardRow();
        r3.add(new KeyboardButton(finals)); r3.add(new KeyboardButton(profile));
        KeyboardRow r4 = new KeyboardRow(); r4.add(new KeyboardButton(logout));
        ReplyKeyboardMarkup m = new ReplyKeyboardMarkup();
        m.setKeyboard(List.of(r1, r2, r3, r4));
        m.setResizeKeyboard(true);
        return m;
    }

    private InlineKeyboardMarkup coursesActionKeyboard(long userId) {
        InlineKeyboardMarkup m = new InlineKeyboardMarkup();
        m.setKeyboard(List.of(
                List.of(inlineBtn(t(userId, "btn.attendance"), "select_attend"),
                        inlineBtn(t(userId, "btn.activities"), "select_activities")),
                List.of(inlineBtn(t(userId, "btn.calendar"), "select_calendar")),
                List.of(inlineBtn(t(userId, "btn.change_semester"), "change_semester"))
        ));
        return m;
    }

    private InlineKeyboardMarkup gpaChoiceKeyboard() {
        InlineKeyboardMarkup m = new InlineKeyboardMarkup();
        m.setKeyboard(List.of(List.of(
                inlineBtn("✅ Baholangan fanlar", "gpa_all"),
                inlineBtn("⬜ Joriy semestr 0 bilan", "gpa_with_zero")
        )));
        return m;
    }

    private void sendLanguageChoice(long chatId) {
        InlineKeyboardMarkup m = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(inlineBtn("🇷🇺 Русский", "lang_ru")));
        rows.add(List.of(inlineBtn("🇺🇿 O'zbekcha (lotin)", "lang_uz_lat")));
        rows.add(List.of(inlineBtn("🇺🇿 Ўзбекча (кирилл)", "lang_uz_cyr")));
        m.setKeyboard(rows);
        send(chatId, "🌐 Tilni tanlang / Выберите язык:", m);
    }

    private InlineKeyboardButton inlineBtn(String text, String data) {
        InlineKeyboardButton btn = new InlineKeyboardButton();
        btn.setText(text);
        btn.setCallbackData(data);
        return btn;
    }

    // ─────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────

    private boolean checkLogin(long chatId, long userId) {
        if (!lmsService.isLoggedIn(userId)) {
            String lang = userLang.getOrDefault(userId, "uz_lat");
            String text = switch (lang) {
                case "ru"     -> "⚠️ Сначала войдите в систему! /login";
                case "uz_cyr" -> "⚠️ Аввал тизимга киринг! /login";
                default       -> "⚠️ Avval tizimga kiring! /login";
            };
            send(chatId, text, null);
            return false;
        }
        return true;
    }

    // ─────────────────────────────────────────────
    //  LOCALIZATION HELPERS
    // ─────────────────────────────────────────────

    private String lang(long userId) {
        return userLang.getOrDefault(userId, "uz_lat");
    }

    private String t(long userId, String key) {
        String l = lang(userId);
        return switch (key) {
            // Common
            case "common.no_data" -> switch (l) {
                case "ru"     -> "📭 Данные не найдены.";
                case "uz_cyr" -> "📭 Маълумот топилмади.";
                default       -> "📭 Ma'lumot topilmadi.";
            };
            case "common.page" -> switch (l) {
                case "ru"     -> "Страница";
                case "uz_cyr" -> "Саҳифа";
                default       -> "Sahifa";
            };
            case "btn.back" -> switch (l) {
                case "ru"     -> "🔙 Назад";
                case "uz_cyr" -> "🔙 Орқага";
                default       -> "🔙 Orqaga";
            };
            case "btn.prev" -> switch (l) {
                case "ru"     -> "⬅️ Назад";
                case "uz_cyr" -> "⬅️ Олдинги";
                default       -> "⬅️ Oldingi";
            };
            case "btn.next" -> switch (l) {
                case "ru"     -> "Вперёд ➡️";
                case "uz_cyr" -> "Кейинги ➡️";
                default       -> "Keyingi ➡️";
            };
            case "btn.files" -> switch (l) {
                case "ru"     -> "📂 Файлы";
                case "uz_cyr" -> "📂 Файллар";
                default       -> "📂 Fayllar";
            };
            // Courses
            case "courses.loading" -> switch (l) {
                case "ru"     -> "⏳ Загружаю предметы...";
                case "uz_cyr" -> "⏳ Фанлар юкланмоқда...";
                default       -> "⏳ Fanlar yuklanmoqda...";
            };
            case "courses.empty_semester" -> switch (l) {
                case "ru"     -> "📭 В этом семестре предметов не найдено.";
                case "uz_cyr" -> "📭 Бу семестрда фанлар топилмади.";
                default       -> "📭 Bu semestrda fanlar topilmadi.";
            };
            case "courses.total" -> switch (l) {
                case "ru"     -> "Итого";
                case "uz_cyr" -> "Жами";
                default       -> "Jami";
            };
            case "courses.subjects_suffix" -> switch (l) {
                case "ru"     -> "предмет(ов)";
                case "uz_cyr" -> "та фан";
                default       -> "ta fan";
            };
            // Courses actions keyboard
            case "btn.attendance" -> switch (l) {
                case "ru"     -> "📊 Посещаемость";
                case "uz_cyr" -> "📊 Давомат";
                default       -> "📊 Davomat";
            };
            case "btn.activities" -> switch (l) {
                case "ru"     -> "📋 Активности";
                case "uz_cyr" -> "📋 Активности";
                default       -> "📋 Aktivnosti";
            };
            case "btn.calendar" -> switch (l) {
                case "ru"     -> "📆 План занятий";
                case "uz_cyr" -> "📆 Дарс режаси";
                default       -> "📆 Fan rejasi";
            };
            case "btn.change_semester" -> switch (l) {
                case "ru"     -> "📅 Сменить семестр";
                case "uz_cyr" -> "📅 Семестрни ўзгартириш";
                default       -> "📅 Semestrni o'zgartirish";
            };
            // Attendance
            case "att.loading" -> switch (l) {
                case "ru"     -> "⏳ Загружается посещаемость...";
                case "uz_cyr" -> "⏳ Давомат юкланмоқда...";
                default       -> "⏳ Davomat yuklanmoqda...";
            };
            case "att.none" -> switch (l) {
                case "ru"     -> "📭 Данные по посещаемости не найдены.";
                case "uz_cyr" -> "📭 Давомат маълумоти топилмади.";
                default       -> "📭 Davomat ma'lumoti topilmadi.";
            };
            case "att.all_ok_title" -> switch (l) {
                case "ru"     -> "✅ <b>Вы посетили все занятия!</b>";
                case "uz_cyr" -> "✅ <b>Барча дарсларда қатнашгансиз!</b>";
                default       -> "✅ <b>Barcha darslarga qatnashgansiz!</b>";
            };
            case "att.total_lessons" -> switch (l) {
                case "ru"     -> "📅 Всего занятий";
                case "uz_cyr" -> "📅 Жами дарслар";
                default       -> "📅 Jami darslar";
            };
            case "att.missed" -> switch (l) {
                case "ru"     -> "🔴 Пропущено";
                case "uz_cyr" -> "🔴 Қолдирилган";
                default       -> "🔴 Qoldirilgan";
            };
            case "att.reason_yes" -> switch (l) {
                case "ru"     -> "✅ Есть уважительная причина";
                case "uz_cyr" -> "✅ Сабаб бор";
                default       -> "✅ Sabab bor";
            };
            case "att.reason_no" -> switch (l) {
                case "ru"     -> "❌ Без уважительной причины";
                case "uz_cyr" -> "❌ Сабаб йўқ";
                default       -> "❌ Sabab yo'q";
            };
            // Calendar
            case "cal.loading" -> switch (l) {
                case "ru"     -> "⏳ Загружается план занятий...";
                case "uz_cyr" -> "⏳ Дарс режаси юкланмоқда...";
                default       -> "⏳ Dars rejasi yuklanmoqda...";
            };
            case "cal.no_data" -> switch (l) {
                case "ru"     -> "📭 Для этого предмета план занятий не найден.";
                case "uz_cyr" -> "📭 Бу фан учун дарс режаси топилмади.";
                default       -> "📭 Bu fan uchun dars rejasi topilmadi.";
            };
            case "cal.plan_label" -> switch (l) {
                case "ru"     -> "план занятий";
                case "uz_cyr" -> "режаси";
                default       -> "rejasi";
            };
            case "cal.files_empty" -> switch (l) {
                case "ru"     -> "📁 Для этой темы нет файлов.";
                case "uz_cyr" -> "📁 Бу мавзу учун файллар йўқ.";
                default       -> "📁 Bu mavzu uchun fayllar yo'q.";
            };
            case "cal.files_title" -> switch (l) {
                case "ru"     -> "📂 <b>Файлы</b>";
                case "uz_cyr" -> "📂 <b>Файллар</b>";
                default       -> "📂 <b>Fayllar</b>";
            };
            // Activities
            case "act.loading" -> switch (l) {
                case "ru"     -> "⏳ Загружаются активности...";
                case "uz_cyr" -> "⏳ Активностьлар юкланмоқда...";
                default       -> "⏳ Aktivnostlar yuklanmoqda...";
            };
            case "act.no_data" -> switch (l) {
                case "ru"     -> "📭 Данные не загружены.";
                case "uz_cyr" -> "📭 Маълумот юкланмади.";
                default       -> "📭 Ma'lumot yuklanmadi.";
            };
            case "act.none" -> switch (l) {
                case "ru"     -> "📭 Заданий нет.";
                case "uz_cyr" -> "📭 Топшириқлар йўқ.";
                default       -> "📭 Topshiriqlar yo'q.";
            };
            // Fallback
            default -> key;
        };
    }

    /** HTML escaping for Telegram HTML parse mode */
    private String esc(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String b(String text)  { return "<b>" + esc(text) + "</b>"; }
    private String i(String text)  { return "<i>" + esc(text) + "</i>"; }
    private String bq(String text) { return "<blockquote>" + text + "</blockquote>"; }

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

    private String orDash(String val) { return (val == null || val.isBlank()) ? "—" : val; }

    private String gradeIcon(int grade) {
        return switch (grade) { case 5 -> "🟢"; case 4 -> "🔵"; case 3 -> "🟡"; case 2 -> "🔴"; default -> "⬜"; };
    }

    private String gpaComment(double gpa) {
        if (gpa >= 4.5) return "A'lo natija! Davom eting! 🚀";
        if (gpa >= 4.0) return "Yaxshi natija! 👍";
        if (gpa >= 3.5) return "O'rtacha yaxshi natija.";
        if (gpa >= 3.0) return "O'rtacha natija, yaxshilash mumkin.";
        return "Past natija, ko'proq harakat kerak! 💪";
    }

    private int getDefaultSemesterId() {
        List<AppConfig.SemesterConfig> s = config.getSemesters();
        return (s != null && !s.isEmpty()) ? s.get(0).getId() : 49;
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }
}