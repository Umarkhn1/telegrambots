package org.example.drsmedia.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Конфигурация приложения
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AppConfig {

    /**
     * Базовая конфигурация уже настроена через Spring Boot
     * Telegram бот автоматически регистрируется благодаря
     * TelegramBots Spring Boot Starter
     */
}