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

        if ("YOUR_TELEGRAM_BOT_TOKEN".equals(config.getBot().getToken())) {
            System.err.println("❌ BOT_TOKEN not configured!");
            System.err.println("   Option 1: export BOT_TOKEN=your_token");
            System.err.println("   Option 2: edit src/main/resources/application.yml");
            System.exit(1);
        }

        LmsService lmsService = new LmsService(config);
        LmsBot bot = new LmsBot(config, lmsService);

        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        botsApi.registerBot(bot);

        // ✅ НОВОЕ: чистая остановка всех потоков при Ctrl+C или kill
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("🛑 Shutting down bot...");
            bot.shutdown();
        }));

        System.out.println("✅ Bot is running! Username: @" + config.getBot().getUsername());
    }
}