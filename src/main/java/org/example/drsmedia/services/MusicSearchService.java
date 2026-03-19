//package org.example.drsmedia.services;
//
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.stereotype.Service;
//
///**
// * Сервис для поиска музыки
// * Опционально использует Spotify API
// */
//@Slf4j
//@Service
//public class MusicSearchService {
//
//    @Value("${spotify.client.id:}")
//    private String spotifyClientId;
//
//    @Value("${spotify.client.secret:}")
//    private String spotifyClientSecret;
//
//    /**
//     * Поиск музыки по названию
//     * В данной версии возвращает сообщение о поиске
//     */
//    public String searchMusic(String query) {
//        try {
//            log.info("Поиск музыки: {}", query);
//
//            if (spotifyClientId == null || spotifyClientId.isEmpty()) {
//                log.warn("Spotify API не настроен");
//                return "⚠️ Сервис поиска музыки не настроен\n\n" +
//                        "Для включения поиска по Spotify:\n" +
//                        "1. Зарегистрируйтесь на https://developer.spotify.com/\n" +
//                        "2. Получите Client ID и Secret\n" +
//                        "3. Добавьте в application.yml";
//            }
//
//            // Базовая функциональность без Spotify (для теста)
//            return String.format("🔍 Поиск по запросу: '%s'\n\n" +
//                    "Результат: Функция поиска музыки в разработке\n\n" +
//                    "Доступные песни:\n" +
//                    "1. The Weeknd - Blinding Lights\n" +
//                    "2. Dua Lipa - Levitating\n" +
//                    "3. Billie Eilish - Bad Guy\n\n" +
//                    "Подпишитесь на обновления!", query);
//
//        } catch (Exception e) {
//            log.error("Ошибка при поиске музыки", e);
//            return "❌ Ошибка поиска: " + e.getMessage();
//        }
//    }
//
//    /**
//     * Поиск музыки с фильтрацией по релевантности
//     */
//    public String searchMusicPartial(String partialQuery) {
//        try {
//            log.info("Частичный поиск музыки: {}", partialQuery);
//            return searchMusic(partialQuery);
//
//        } catch (Exception e) {
//            log.error("Ошибка при частичном поиске музыки", e);
//            return null;
//        }
//    }
//}