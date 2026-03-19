//package org.example.drsmedia.services;
//
//import com.google.gson.JsonObject;
//import com.google.gson.JsonParser;
//import lombok.extern.slf4j.Slf4j;
//import okhttp3.MediaType;
//import okhttp3.OkHttpClient;
//import okhttp3.Request;
//import okhttp3.RequestBody;
//import okhttp3.Response;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.stereotype.Service;
//
//import java.io.File;
//import java.io.IOException;
//import java.nio.file.Files;
//import java.util.concurrent.TimeUnit;
//
///**
// * Сервис для распознавания музыки из аудиофайлов
// */
//@Slf4j
//@Service
//public class SoundRecognitionService {
//
//    @Value("${shazam.api.key:}")
//    private String shazamApiKey;
//
//    @Value("${download.directory:downloads}")
//    private String downloadDir;
//
//    private final OkHttpClient httpClient;
//    private final VideoService videoService;
//
//    public SoundRecognitionService(VideoService videoService) {
//        this.videoService = videoService;
//        this.httpClient = new OkHttpClient.Builder()
//            .connectTimeout(30, TimeUnit.SECONDS)
//            .readTimeout(30, TimeUnit.SECONDS)
//            .build();
//    }
//
//    /**
//     * Распознает музыку из аудиофайла по ID Telegram
//     */
//    public String recognizeMusic(String fileId) {
//        try {
//            log.info("Начинается распознавание музыки для файла: {}", fileId);
//
//            // Для полной реализации нужно скачать файл из Telegram
//            // Здесь приведен пример работы с локальным файлом
//
//            return recognizeWithShazam(null);
//
//        } catch (Exception e) {
//            log.error("Ошибка при распознавании музыки", e);
//            return null;
//        }
//    }
//
//    /**
//     * Распознавание музыки через Shazam API
//     */
//    private String recognizeWithShazam(File audioFile) {
//        try {
//            if (shazamApiKey == null || shazamApiKey.isEmpty()) {
//                log.warn("Shazam API ключ не настроен");
//                return "⚠️ Сервис распознавания музыки не настроен";
//            }
//
//            if (audioFile == null || !audioFile.exists()) {
//                log.error("Аудиофайл не найден");
//                return null;
//            }
//
//            byte[] audioBytes = Files.readAllBytes(audioFile.toPath());
//
//            log.info("Отправка запроса к Shazam API, размер: {} байт", audioBytes.length);
//
//            String response = sendShazamRequest(audioBytes);
//
//            if (response != null && !response.isEmpty()) {
//                String result = parseShazamResponse(response);
//                if (result != null) {
//                    log.info("Музыка распознана: {}", result);
//                    return result;
//                }
//            }
//
//            return null;
//
//        } catch (Exception e) {
//            log.error("Ошибка при распознавании через Shazam", e);
//            return null;
//        }
//    }
//
//    /**
//     * Отправляет запрос к Shazam API
//     */
//    private String sendShazamRequest(byte[] audioBytes) {
//        try {
//            RequestBody body = RequestBody.create(
//                audioBytes,
//                MediaType.parse("audio/wav")
//            );
//
//            Request request = new Request.Builder()
//                .url("https://www.shazam.com/services/recognize?")
//                .post(body)
//                .addHeader("User-Agent", "Shazam/2.0 (Android)")
//                .addHeader("Content-Type", "audio/wav")
//                .build();
//
//            try (Response response = httpClient.newCall(request).execute()) {
//                if (response.isSuccessful() && response.body() != null) {
//                    return response.body().string();
//                } else {
//                    log.warn("Shazam ответил с кодом: {}", response.code());
//                }
//            }
//
//        } catch (IOException e) {
//            log.error("Ошибка при отправке запроса к Shazam", e);
//        }
//
//        return null;
//    }
//
//    /**
//     * Парсит ответ от Shazam
//     */
//    private String parseShazamResponse(String jsonResponse) {
//        try {
//            JsonObject json = JsonParser.parseString(jsonResponse).getAsJsonObject();
//
//            if (json.has("track")) {
//                JsonObject track = json.getAsJsonObject("track");
//
//                String title = track.get("title").getAsString();
//                String subtitle = track.get("subtitle").getAsString();
//
//                log.info("Распознана музыка: {} - {}", subtitle, title);
//                return String.format("🎵 *%s* - %s", subtitle, title);
//            }
//
//        } catch (Exception e) {
//            log.error("Ошибка при парсинге ответа Shazam", e);
//        }
//
//        return null;
//    }
//
//    /**
//     * Альтернативное распознавание через AcousticID API
//     */
//    public String recognizeWithAcousticID(File audioFile) {
//        try {
//            if (audioFile == null || !audioFile.exists()) {
//                log.error("Аудиофайл не найден");
//                return null;
//            }
//
//            log.info("Использование AcousticID для распознавания");
//
//            // Здесь можно добавить логику для AcousticID API
//            // https://acousticid.org/api
//
//            return null;
//
//        } catch (Exception e) {
//            log.error("Ошибка при распознавании через AcousticID", e);
//            return null;
//        }
//    }
//
//    /**
//     * Конвертирует аудио в WAV формат
//     */
//    public File convertToWavFormat(File audioFile) {
//        try {
//            return videoService.convertToWav(audioFile);
//        } catch (Exception e) {
//            log.error("Ошибка при конвертировании в WAV", e);
//            return null;
//        }
//    }
//}