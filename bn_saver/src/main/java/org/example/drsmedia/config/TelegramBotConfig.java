package org.example.drsmedia.config;

import lombok.extern.slf4j.Slf4j;
import org.example.drsmedia.bot.MusicDownloaderBot;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

@Slf4j
@Configuration
public class TelegramBotConfig {

    @Bean
    public TelegramBotsApi telegramBotsApi(MusicDownloaderBot bot) throws Exception {
        TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
        try {
            api.registerBot(bot);
            log.info("✅ Бот зарегистрирован успешно!");
        } catch (Exception e) {
            log.error("❌ Ошибка регистрации бота: {} — проверьте токен в application.yml", e.getMessage());
            // Не бросаем исключение — Spring поднимется, ошибку увидите в логах
        }
        return api;
    }
}