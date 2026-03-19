package org.example.drsmedia.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendAudio;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class BotTaskService {

    private final VideoService videoService;

    // key → url (для MP3)
    private final Map<String, String> pendingAudio = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);

    // ===================== HANDLE URL =====================
    @Async("botTaskExecutor")
    public void handleUrl(AbsSender bot, Long chatId, String url, String lang,
                          Map<String, String> L,
                          InlineKeyboardMarkup audioMarkupFn,
                          String audioKey,
                          long maxVideoBytes) {

        if ("instagram".equals(videoService.detectPlatform(url)) && videoService.isInstagramPhoto(url)) {
            sendMd(bot, chatId, L.get("photo_not_supported"));
            return;
        }

        File video = null;
        try {
            video = videoService.downloadVideoTemp(url, videoService.detectPlatform(url));
            if (video == null) {
                sendMd(bot, chatId, L.get("download_error"));
                return;
            }

            if (video.length() > maxVideoBytes) {
                log.warn("Видео слишком большое: {} MB", video.length() / 1024 / 1024);
                videoService.cleanup(video);
                sendMd(bot, chatId, L.get("file_too_large"));
                return;
            }

            // Сохраняем URL для MP3
            pendingAudio.put(audioKey, url);

            // Автоудаление через 30 минут если не нажали MP3
            scheduler.schedule(() -> pendingAudio.remove(audioKey), 30, TimeUnit.MINUTES);

            SendVideo sv = new SendVideo();
            sv.setChatId(chatId.toString());
            sv.setVideo(new InputFile(video));
            sv.setCaption(L.get("video_caption"));
            sv.setParseMode("MarkdownV2");
            sv.setSupportsStreaming(true);
            sv.setReplyMarkup(audioMarkupFn);
            bot.execute(sv);

            videoService.cleanup(video);

        } catch (Exception e) {
            log.error("Ошибка при обработке ссылки", e);
            sendMd(bot, chatId, L.get("error"));
            if (video != null) videoService.cleanup(video);
        }
    }

    // ===================== HANDLE AUDIO =====================
    @Async("botTaskExecutor")
    public void handleAudio(AbsSender bot, Long chatId, String key,
                            Map<String, String> L, long maxAudioBytes) {
        String url = pendingAudio.get(key);

        if (url == null || url.isBlank()) {
            pendingAudio.remove(key);
            try {
                SendMessage sm = new SendMessage();
                sm.setChatId(chatId.toString());
                sm.setText("❌ Файл устарел. Отправьте ссылку заново.");
                bot.execute(sm);
            } catch (Exception ignored) {}
            return;
        }

        sendMd(bot, chatId, L.get("extracting"));

        File videoFile = null;
        File audio = null;

        try {
            String platform = videoService.detectPlatform(url);
            videoFile = videoService.downloadVideoTemp(url, platform);
            if (videoFile == null) {
                sendMd(bot, chatId, L.get("download_error"));
                return;
            }

            audio = videoService.extractAudio(videoFile);
            if (audio != null) {
                if (audio.length() > maxAudioBytes) {
                    log.warn("Аудио слишком большое: {} MB", audio.length() / 1024 / 1024);
                    videoService.cleanup(audio);
                    sendMd(bot, chatId, L.get("file_too_large"));
                } else {
                    SendAudio sa = new SendAudio();
                    sa.setChatId(chatId.toString());
                    sa.setAudio(new InputFile(audio));
                    bot.execute(sa);
                }
            } else {
                sendMd(bot, chatId, L.get("audio_error"));
            }
        } catch (Exception e) {
            log.error("Ошибка при конвертации аудио", e);
            sendMd(bot, chatId, L.get("audio_error"));
        } finally {
            pendingAudio.remove(key);
            if (audio != null && audio.exists()) videoService.cleanup(audio);
            if (videoFile != null && videoFile.exists()) videoService.cleanup(videoFile);
        }
    }

    public String getPendingUrl(String key) {
        return pendingAudio.get(key);
    }

    public void putPendingAudio(String key, String url) {
        pendingAudio.put(key, url);
    }

    private void sendMd(AbsSender bot, Long chatId, String text) {
        try {
            SendMessage sm = new SendMessage();
            sm.setChatId(chatId.toString());
            sm.setText(text);
            sm.setParseMode("MarkdownV2");
            bot.execute(sm);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки сообщения", e);
        }
    }
}