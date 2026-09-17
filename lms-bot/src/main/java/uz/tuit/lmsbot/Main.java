package uz.tuit.lmsbot;

import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import uz.tuit.lmsbot.bot.LmsBot;
import uz.tuit.lmsbot.config.AppConfig;
import uz.tuit.lmsbot.service.LmsService;

public class Main {

    public static void main(String[] args) throws Exception {
        System.out.println("🚀 TUIT LMS Bot starting...");

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

        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        botsApi.registerBot(bot);
        bot.applyBotCommands();

        // ✅ НОВОЕ: чистая остановка всех потоков при Ctrl+C или kill
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("🛑 Shutting down bot...");
            bot.shutdown();
        }));

        System.out.println("✅ Bot is running! Username: @" + config.getBot().getUsername());

        // Хостинги вроде Render считают веб-сервис упавшим, если он не слушает $PORT.
        // Боту порт не нужен (long polling), поэтому поднимаем его только когда
        // переменная задана — как health-check и как способ не дать сервису уснуть.
        startHealthServer();
        startKeepAlive();
    }

    private static void startHealthServer() {
        String port = System.getenv("PORT");
        if (port == null || port.isBlank()) return;
        try {
            com.sun.net.httpserver.HttpServer http =
                    com.sun.net.httpserver.HttpServer.create(
                            new java.net.InetSocketAddress(Integer.parseInt(port)), 0);
            http.createContext("/", exchange -> {
                byte[] body = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                // Health-check Render ходит методом HEAD, а на HEAD тело слать нельзя:
                // длина ответа должна быть -1, иначе JDK пишет WARNING в лог.
                boolean head = "HEAD".equalsIgnoreCase(exchange.getRequestMethod());
                exchange.sendResponseHeaders(200, head ? -1 : body.length);
                if (!head) {
                    try (java.io.OutputStream os = exchange.getResponseBody()) { os.write(body); }
                } else {
                    exchange.close();
                }
            });
            http.setExecutor(null);
            http.start();
            System.out.println("🌐 Health endpoint on :" + port);
        } catch (Exception e) {
            System.err.println("[Main] health server: " + e.getMessage());
        }
    }

    /** Каждые сколько минут дёргать собственный адрес, чтобы сервис не уснул. */
    private static final int KEEPALIVE_MINUTES = 14;

    /**
     * Бесплатный веб-сервис Render засыпает после 15 минут без входящих запросов,
     * а спящий бот перестаёт забирать апдейты. Поэтому раз в 14 минут дёргаем
     * собственный публичный адрес (RENDER_EXTERNAL_URL Render подставляет сам).
     *
     * Оговорка: это спасает только пока процесс жив — разбудить уже уснувший
     * сервис изнутри нельзя, для этого нужен внешний пингер.
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

        System.out.println("💓 Keepalive: " + target + " каждые " + KEEPALIVE_MINUTES + " мин");
    }
}