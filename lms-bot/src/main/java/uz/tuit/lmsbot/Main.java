package uz.tuit.lmsbot;

import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import uz.tuit.lmsbot.bot.LmsBot;
import uz.tuit.lmsbot.config.AppConfig;
import uz.tuit.lmsbot.service.LmsService;
import uz.tuit.lmsbot.web.WebApi;

public class Main {

    public static void main(String[] args) throws Exception {
        System.out.println("🚀 TUIT LMS Bot starting...");
        System.out.println("🕐 Пояс JVM: " + java.time.ZoneId.systemDefault()
                + " | пояс LMS: " + AppConfig.LMS_ZONE
                + " | сейчас в Ташкенте: " + java.time.LocalDateTime.now(AppConfig.LMS_ZONE)
                        .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")));

        AppConfig config = AppConfig.load();

        String token = config.getBot().getToken();
        if (token == null || token.isBlank() || "YOUR_TELEGRAM_BOT_TOKEN".equals(token)) {
            System.err.println("❌ BOT_TOKEN не задан!");
            System.err.println("   Локально:    BOT_TOKEN=... java -jar build/libs/lms-bot.jar");
            System.err.println("   На хостинге: переменная окружения BOT_TOKEN у сервиса");
            System.exit(1);
        }

        LmsService lmsService = new LmsService(config);
        LmsBot bot = new LmsBot(config, lmsService);

        // Сессии, языки и список студентов — до первого апдейта, иначе ранние сообщения
        // обработаются с пустым диском.
        bot.restoreState();

        // Хостинги вроде Render считают веб-сервис упавшим, если он не слушает $PORT.
        // На этом же порту живут API и фронтенд мини-приложения и приём webhook,
        // поэтому сервер поднимаем до регистрации бота: Telegram проверяет адрес
        // webhook сразу же, и отвечать на эту проверку должно уже быть кому.
        boolean web = startWebServer(config, lmsService, bot);

        if (!(web && startWebhook(config, bot))) startLongPolling(bot);
        bot.applyBotCommands();

        // ✅ НОВОЕ: чистая остановка всех потоков при Ctrl+C или kill
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("🛑 Shutting down bot...");
            bot.shutdown();
        }));

        System.out.println("✅ Bot is running! Username: @" + config.getBot().getUsername());
        startKeepAlive();
    }

    private static boolean startWebServer(AppConfig config, LmsService lmsService, LmsBot bot) {
        String port = System.getenv("PORT");
        if (port == null || port.isBlank()) {
            System.out.println("🌐 PORT не задан — HTTP-сервер и мини-приложение выключены");
            return false;
        }
        try {
            new WebApi(config, lmsService, bot).start(Integer.parseInt(port.trim()));
            System.out.println("🌐 HTTP on :" + port + " (health /, API /api/, app /app/)");
        } catch (Exception e) {
            System.err.println("[Main] web server: " + e.getMessage());
            return false;
        }
        // Адрес мини-приложения: явный WEBAPP_URL или публичный адрес сервиса на Render.
        String url = System.getenv("WEBAPP_URL");
        if (url == null || url.isBlank()) {
            String base = publicUrl();
            if (base != null) url = base + "app/";
        }
        bot.applyWebApp(url);
        return true;
    }

    /** Публичный https-адрес сервиса со слэшем на конце либо null. */
    private static String publicUrl() {
        String url = System.getenv("WEBHOOK_URL");
        if (url == null || url.isBlank()) url = System.getenv("RENDER_EXTERNAL_URL");
        if (url == null || url.isBlank()) return null;
        url = url.trim();
        if (!url.startsWith("https://")) return null;   // Telegram принимает только https
        return url.endsWith("/") ? url : url + "/";
    }

    /**
     * Webhook вместо long polling: апдейт приходит обычным POST на наш адрес, а значит
     * сообщение пользователя само будит уснувший сервис. При long polling спящий сервис
     * просто не забирает апдейты, и бот молчит, пока его не разбудит кто-то извне.
     *
     * Выключается переменной WEBHOOK=off — тогда работает старый режим.
     */
    private static boolean startWebhook(AppConfig config, LmsBot bot) {
        if ("off".equalsIgnoreCase(System.getenv("WEBHOOK"))) return false;
        String base = publicUrl();
        if (base == null) {
            System.out.println("🌐 Публичный https-адрес неизвестен — webhook пропущен");
            return false;
        }
        String token = config.getBot().getToken();
        String url = base.substring(0, base.length() - 1) + WebApi.webhookPath(token);
        try {
            bot.execute(org.telegram.telegrambots.meta.api.methods.updates.SetWebhook.builder()
                    .url(url)
                    .secretToken(WebApi.webhookSecret(token))
                    // Апдейты, накопившиеся за сон, нужны: их и так не больше суточной очереди.
                    .dropPendingUpdates(false)
                    .maxConnections(20)
                    .build());
            System.out.println("🪝 Webhook: " + url.replaceAll("/tg/.*", "/tg/***"));
            return true;
        } catch (Exception e) {
            // Не смогли — лучше работать хуже, чем не работать: откатываемся на polling.
            System.err.println("[Main] setWebhook: " + e.getMessage());
            return false;
        }
    }

    private static void startLongPolling(LmsBot bot) throws Exception {
        // Пока webhook установлен, getUpdates отвечает 409 — снимаем его.
        try {
            bot.execute(org.telegram.telegrambots.meta.api.methods.updates.DeleteWebhook.builder()
                    .dropPendingUpdates(false).build());
        } catch (Exception e) {
            System.err.println("[Main] deleteWebhook: " + e.getMessage());
        }
        new TelegramBotsApi(DefaultBotSession.class).registerBot(bot);
        System.out.println("📡 Режим long polling");
    }

    /** Каждые сколько минут дёргать собственный адрес, чтобы сервис не уснул. */
    private static final int KEEPALIVE_MINUTES = 14;

    /**
     * Окно активности по ташкентскому времени (AppConfig.LMS_ZONE), например «7-1» —
     * с 07:00 до 01:00. Вне окна пинги прекращаются, сервис засыпает и перестаёт
     * тратить бесплатные часы Render. Пусто — работаем круглосуточно.
     */
    private static boolean withinActiveHours() {
        String window = System.getenv("KEEPALIVE_HOURS");
        if (window == null || window.isBlank()) return true;
        try {
            String[] p = window.split("-");
            int from = Integer.parseInt(p[0].trim());
            int to   = Integer.parseInt(p[1].trim());
            int hour = java.time.LocalTime.now(AppConfig.LMS_ZONE).getHour();
            // from > to means the window wraps over midnight (7-1 = 07:00…01:00).
            return from <= to ? hour >= from && hour < to : hour >= from || hour < to;
        } catch (Exception e) {
            System.err.println("[Main] KEEPALIVE_HOURS=" + window + " не разобрано: " + e.getMessage());
            return true;
        }
    }

    /**
     * Бесплатный веб-сервис Render засыпает после 15 минут без входящих запросов,
     * а спящий бот перестаёт забирать апдейты. Поэтому раз в 14 минут дёргаем
     * собственный публичный адрес (RENDER_EXTERNAL_URL Render подставляет сам).
     *
     * Оговорки: разбудить уже уснувший сервис изнутри нельзя — для этого нужен
     * внешний пингер; и вне KEEPALIVE_HOURS мы намеренно даём сервису уснуть.
     */
    private static void startKeepAlive() {
        String url = System.getenv("KEEPALIVE_URL");
        if (url == null || url.isBlank()) url = System.getenv("RENDER_EXTERNAL_URL");
        if (url == null || url.isBlank()) return;

        final java.net.URI target = java.net.URI.create(url.endsWith("/") ? url : url + "/");
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(15))
                .build();

        java.util.concurrent.ScheduledExecutorService ses =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "keepalive");
                    t.setDaemon(true);
                    return t;
                });

        ses.scheduleAtFixedRate(() -> {
            if (!withinActiveHours()) {
                System.out.println("😴 keepalive пропущен: вне окна активности, даём сервису уснуть");
                return;
            }
            try {
                java.net.http.HttpResponse<Void> resp = client.send(
                        java.net.http.HttpRequest.newBuilder(target)
                                .timeout(java.time.Duration.ofSeconds(20))
                                .GET().build(),
                        java.net.http.HttpResponse.BodyHandlers.discarding());
                System.out.println("💓 keepalive " + target + " -> " + resp.statusCode());
            } catch (Exception e) {
                System.err.println("[Main] keepalive: " + e.getMessage());
            }
        }, KEEPALIVE_MINUTES, KEEPALIVE_MINUTES, java.util.concurrent.TimeUnit.MINUTES);

        String window = System.getenv("KEEPALIVE_HOURS");
        System.out.println("💓 Keepalive: " + target + " каждые " + KEEPALIVE_MINUTES + " мин"
                + (window == null || window.isBlank() ? " (круглосуточно)" : ", окно " + window));
    }
}