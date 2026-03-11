package org.example.drsmedia.services;

import lombok.extern.slf4j.Slf4j;
import net.bramp.ffmpeg.FFmpeg;
import net.bramp.ffmpeg.FFmpegExecutor;
import net.bramp.ffmpeg.builder.FFmpegBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class VideoService {

    @Value("${yt-dlp.path:yt-dlp}")
    private String ytDlpPath;

    @Value("${ffmpeg.timeout.seconds:300}")
    private long ffmpegTimeout;

    // Если задан вручную — используется только он
    @Value("${yt-dlp.tiktok-proxy:}")
    private String manualTiktokProxy;

    private FFmpeg ffmpeg;
    private FFmpegExecutor executor;

    // Кэш рабочего прокси — не ищем заново каждый раз
    private final AtomicReference<String> cachedProxy = new AtomicReference<>(null);

    // Источники списков прокси (PL / EU)
    private static final String PROXY_LIST_URL =
            "https://raw.githubusercontent.com/TheSpeedX/SOCKS-List/master/socks5.txt";
    private static final String PROXY_LIST_URL2 =
            "https://raw.githubusercontent.com/hookzof/socks5_list/master/proxy.txt";
    // TikTok test endpoint
    private static final String TIKTOK_TEST_URL = "https://www.tiktok.com/";

    public VideoService() {
        try {
            this.ffmpeg   = new FFmpeg();
            this.executor = new FFmpegExecutor(ffmpeg);
            log.info("FFmpeg инициализирован успешно");
        } catch (IOException e) {
            log.error("Ошибка инициализации FFmpeg", e);
        }
    }

    // ─────────────────────────────────────────────────────────
    public String detectPlatform(String url) {
        String u = url.toLowerCase();
        if (u.contains("youtube.com") || u.contains("youtu.be"))                              return "youtube";
        if (u.contains("instagram.com"))                                                       return "instagram";
        if (u.contains("tiktok.com") || u.contains("vm.tiktok") || u.contains("vt.tiktok"))  return "tiktok";
        return "unknown";
    }

    // ─────────────────────────────────────────────────────────
    public File downloadVideoTemp(String url, String platform) {
        try {
            Path tmp = Files.createTempFile("drs_" + platform + "_", ".mp4");
            Files.deleteIfExists(tmp);
            String outputPath = tmp.toAbsolutePath().toString();
            log.info("Скачивание [{}]: {}", platform, url);

            // Для TikTok определяем прокси
            String proxy = null;
            if ("tiktok".equals(platform)) {
                proxy = resolveProxy();
            }

            List<String> cmd = buildCommand(url, platform, outputPath, proxy);
            log.debug("Команда: {}", String.join(" ", cmd));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) log.info("[yt-dlp] {}", line);
            }

            int exitCode = process.waitFor();
            File out = new File(outputPath);

            // yt-dlp может сохранить с другим расширением
            if (!out.exists() || out.length() == 0) {
                String baseName = out.getName().replace(".mp4", "");
                File[] cands = out.getParentFile().listFiles(
                        f -> f.getName().startsWith(baseName) && f.length() > 0);
                if (cands != null && cands.length > 0) {
                    out = cands[0];
                    log.info("Найден файл: {}", out.getName());
                }
            }

            if (exitCode == 0 && out.exists() && out.length() > 0) {
                log.info("✅ Скачано: {} ({} KB)", out.getName(), out.length() / 1024);
                return out;
            } else {
                // Если TikTok упал с прокси — сбрасываем кэш, попробуем другой в следующий раз
                if ("tiktok".equals(platform)) {
                    log.warn("TikTok скачивание упало — сбрасываем кэш прокси");
                    cachedProxy.set(null);
                }
                log.error("❌ Ошибка скачивания (exitCode={})", exitCode);
                cleanup(out);
                return null;
            }
        } catch (Exception e) {
            log.error("Ошибка при скачивании видео", e);
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────
    // Возвращает рабочий прокси: ручной → кэш → авто-поиск
    private String resolveProxy() {
        if (manualTiktokProxy != null && !manualTiktokProxy.isBlank()) {
            log.info("Используем ручной прокси: {}", manualTiktokProxy);
            return manualTiktokProxy;
        }
        String cached = cachedProxy.get();
        if (cached != null) {
            log.info("Используем кэшированный прокси: {}", cached);
            return cached;
        }
        log.info("Ищем рабочий прокси для TikTok...");
        String found = findWorkingProxy();
        if (found != null) {
            cachedProxy.set(found);
            log.info("✅ Найден рабочий прокси: {}", found);
        } else {
            log.warn("⚠️ Рабочий прокси не найден — пробуем без прокси");
        }
        return found;
    }

    // Скачивает список прокси и параллельно проверяет их
    private String findWorkingProxy() {
        List<String> proxies = new ArrayList<>();
        for (String listUrl : List.of(PROXY_LIST_URL, PROXY_LIST_URL2)) {
            try {
                URL url = new URL(listUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(8000);
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()))) {
                    br.lines()
                            .map(String::trim)
                            .filter(l -> l.matches("\\d+\\.\\d+\\.\\d+\\.\\d+:\\d+"))
                            .limit(200)
                            .forEach(proxies::add);
                }
                log.info("Загружено {} прокси из {}", proxies.size(), listUrl);
                if (!proxies.isEmpty()) break;
            } catch (Exception e) {
                log.warn("Не удалось загрузить список прокси: {}", e.getMessage());
            }
        }

        if (proxies.isEmpty()) return null;

        // Перемешиваем и берём первые 50 для проверки
        Collections.shuffle(proxies);
        List<String> batch = proxies.subList(0, Math.min(50, proxies.size()));

        ExecutorService pool = Executors.newFixedThreadPool(20);
        CompletionService<String> cs = new ExecutorCompletionService<>(pool);

        for (String proxy : batch) {
            cs.submit(() -> testProxy(proxy) ? proxy : null);
        }

        String working = null;
        try {
            for (int i = 0; i < batch.size(); i++) {
                Future<String> f = cs.poll(8, TimeUnit.SECONDS);
                if (f == null) break;
                String result = f.get();
                if (result != null) {
                    working = result;
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("Ошибка при поиске прокси: {}", e.getMessage());
        } finally {
            pool.shutdownNow();
        }
        return working;
    }

    // Проверяет один SOCKS5 прокси — может ли достучаться до TikTok
    private boolean testProxy(String proxyStr) {
        try {
            String[] parts = proxyStr.split(":");
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);
            Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(host, port));
            URL url = new URL(TIKTOK_TEST_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy);
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestMethod("HEAD");
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36");
            int code = conn.getResponseCode();
            conn.disconnect();
            return code < 500;
        } catch (Exception e) {
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────
    private List<String> buildCommand(String url, String platform,
                                      String outputPath, String proxy) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ytDlpPath);

        switch (platform) {
            case "tiktok" -> {
                // Только прокси — НЕ добавляем user-agent и заголовки:
                // они ломают встроенный JS challenge solver yt-dlp
                if (proxy != null && !proxy.isBlank()) {
                    cmd.add("--proxy"); cmd.add(proxy);
                }
                cmd.add("--socket-timeout"); cmd.add("30");
                cmd.add("--retries");        cmd.add("3");
                cmd.add("--fragment-retries"); cmd.add("3");
            }
            case "instagram" -> {
                cmd.add("--user-agent");
                cmd.add("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1");
                cmd.add("--add-header"); cmd.add("Referer:https://www.instagram.com/");
                cmd.add("--socket-timeout"); cmd.add("30");
                cmd.add("--retries");        cmd.add("3");
            }
        }

        cmd.add("-f");    cmd.add("bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best");
        cmd.add("--merge-output-format"); cmd.add("mp4");
        cmd.add("--no-playlist");
        cmd.add("-o");    cmd.add(outputPath);
        cmd.add(url);
        return cmd;
    }

    // ─────────────────────────────────────────────────────────
    public File extractAudio(File videoFile) {
        try {
            if (ffmpeg == null || executor == null) {
                log.error("FFmpeg не инициализирован"); return null;
            }
            String audioPath = videoFile.getAbsolutePath()
                    .replaceAll("\\.(mp4|webm|mkv|avi|mov)$", ".mp3");
            log.info("Извлечение аудио: {}", videoFile.getName());
            executor.createJob(new FFmpegBuilder()
                    .setInput(videoFile.getAbsolutePath())
                    .addOutput(audioPath)
                    .setAudioCodec("libmp3lame")
                    .setAudioBitRate(192_000)
                    .setAudioSampleRate(44100)
                    .setAudioChannels(2)
                    .addExtraArgs("-vn")
                    .done()).run();
            File audio = new File(audioPath);
            if (audio.exists() && audio.length() > 0) {
                log.info("✅ Аудио готово: {}", audioPath);
                return audio;
            }
        } catch (Exception e) {
            log.error("Ошибка извлечения аудио", e);
        }
        return null;
    }

    public File convertToWav(File audioFile) {
        try {
            if (ffmpeg == null || executor == null) return null;
            String wavPath = audioFile.getAbsolutePath().replaceAll("\\.(mp3|m4a|aac)$", ".wav");
            executor.createJob(new FFmpegBuilder()
                    .setInput(audioFile.getAbsolutePath())
                    .addOutput(wavPath)
                    .setAudioCodec("pcm_s16le")
                    .setAudioSampleRate(16000)
                    .setAudioChannels(1)
                    .done()).run();
            File wav = new File(wavPath);
            return (wav.exists() && wav.length() > 0) ? wav : null;
        } catch (Exception e) {
            log.error("Ошибка конвертации в WAV", e); return null;
        }
    }

    public void cleanup(File... files) {
        for (File f : files) {
            try {
                if (f != null && f.exists()) {
                    if (f.delete()) log.info("Удалён: {}", f.getName());
                    else            log.warn("Не удалось удалить: {}", f.getName());
                }
            } catch (Exception ignored) {}
        }
    }
}