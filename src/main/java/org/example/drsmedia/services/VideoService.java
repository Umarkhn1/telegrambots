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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class VideoService {

    @Value("${yt-dlp.path:yt-dlp}")
    private String ytDlpPath;

    @Value("${ffmpeg.timeout.seconds:300}")
    private long ffmpegTimeout;

    @Value("${yt-dlp.tiktok-proxy:}")
    private String manualTiktokProxy;

    private FFmpeg ffmpeg;
    private FFmpegExecutor executor;

    @Value("${yt-dlp.proxy-pool:}")
    private List<String> proxyPool;

    private final AtomicInteger proxyIndex = new AtomicInteger(0);
    private final AtomicReference<String> cachedProxy = new AtomicReference<>(null);

    private static final String PROXY_LIST_URL =
            "https://raw.githubusercontent.com/TheSpeedX/SOCKS-List/master/socks5.txt";
    private static final String PROXY_LIST_URL2 =
            "https://raw.githubusercontent.com/hookzof/socks5_list/master/proxy.txt";
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
    private String resolveProxy() {
        if (manualTiktokProxy != null && !manualTiktokProxy.isBlank()) {
            return manualTiktokProxy;
        }
        if (proxyPool != null && !proxyPool.isEmpty()) {
            int idx = proxyIndex.getAndIncrement() % proxyPool.size();
            String proxy = proxyPool.get(idx);
            log.info("Используем прокси из пула [{}]: {}", idx, proxy);
            return proxy;
        }
        String cached = cachedProxy.get();
        if (cached != null) return cached;
        String found = findWorkingProxy();
        if (found != null) cachedProxy.set(found);
        return found;
    }

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
                if (proxy != null && !proxy.isBlank()) {
                    cmd.add("--proxy"); cmd.add(proxy);
                }
                cmd.add("--socket-timeout"); cmd.add("30");
                cmd.add("--retries");        cmd.add("3");
                cmd.add("--fragment-retries"); cmd.add("3");
                // Лучшее качество без ограничений
                cmd.add("-f"); cmd.add("bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best");
            }
            case "instagram" -> {
                cmd.add("--cookies"); cmd.add("/home/ubuntu/bn_saver_bot/instagram_cookies.txt");
                cmd.add("--user-agent");
                cmd.add("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1");
                cmd.add("--add-header"); cmd.add("Referer:https://www.instagram.com/");
                cmd.add("--socket-timeout"); cmd.add("30");
                cmd.add("--retries");        cmd.add("3");
                // Лучшее качество без ограничений
                cmd.add("-f"); cmd.add("bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best");
            }
            case "youtube" -> {
                cmd.add("--cookies"); cmd.add("/home/ubuntu/bn_saver_bot/youtube_cookies.txt");
                cmd.add("--socket-timeout"); cmd.add("30");
                cmd.add("--retries");        cmd.add("3");
                // 720p — быстро и качественно, большинство видео именно так
                cmd.add("-f"); cmd.add("bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/best[height<=720][ext=mp4]/best[ext=mp4]");
            }
        }

        cmd.add("--merge-output-format"); cmd.add("mp4");
        cmd.add("--no-playlist");
        // Лимит убран — Telegram сам ограничит до 50MB при отправке
        cmd.add("-o"); cmd.add(outputPath);
        cmd.add(url);
        return cmd;
    }
    public boolean isLiveStream(String url) {
        String u = url.toLowerCase();
        // YouTube live
        if (u.contains("youtube.com/live/") || u.contains("youtu.be/live/")) return true;
        if (u.contains("youtube.com/watch") && u.contains("live")) return true;
        // Instagram live
        if (u.contains("instagram.com/") && u.contains("/live")) return true;
        return false;
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
    public boolean isInstagramPhoto(String url) {
        return url.contains("instagram.com/p/");
    }

    /**
     * Returns [width, height] of the video file using ffprobe, or null on error.
     * This preserves vertical/portrait orientation so Telegram won't crop it.
     */
    public int[] getVideoDimensions(File videoFile) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffprobe", "-v", "error",
                    "-select_streams", "v:0",
                    "-show_entries", "stream=width,height",
                    "-of", "csv=p=0",
                    videoFile.getAbsolutePath()
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = br.readLine();
                if (line != null && line.contains(",")) {
                    String[] parts = line.trim().split(",");
                    int w = Integer.parseInt(parts[0].trim());
                    int h = Integer.parseInt(parts[1].trim());
                    log.info("Размеры видео: {}x{}", w, h);
                    return new int[]{w, h};
                }
            }
            p.waitFor();
        } catch (Exception e) {
            log.warn("Не удалось получить размеры видео: {}", e.getMessage());
        }
        return null;
    }
}