package uz.tuit.lmsbot.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * HTTP-сервер на $PORT: health-check для Render («/»), JSON API мини-приложения («/api/»)
 * и собранный фронтенд из ресурсов jar («/app/»).
 */
public class WebServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Ошибка API с HTTP-статусом и машинным кодом для фронтенда. */
    static class ApiError extends RuntimeException {
        final int status;
        final String code;
        ApiError(int status, String code) { super(code); this.status = status; this.code = code; }
    }

    /** Ответ, который пишется как есть (файлы), а не сериализуется в JSON. */
    record Raw(byte[] body, String contentType, String filename) {}

    static final class Req {
        final HttpExchange ex;
        final Matcher path;
        final Map<String, String> query;
        TelegramAuth.TgUser user;
        private JsonNode json;
        private byte[] bytes;

        Req(HttpExchange ex, Matcher path) {
            this.ex = ex;
            this.path = path;
            this.query = parseQuery(ex.getRequestURI().getRawQuery());
        }

        long uid() { return user.id(); }

        String q(String key) { return query.get(key); }

        int qInt(String key, int def) {
            try { return Integer.parseInt(query.get(key)); } catch (Exception e) { return def; }
        }

        int pathInt(int group) {
            try { return Integer.parseInt(path.group(group)); } catch (Exception e) { throw new ApiError(400, "bad_request"); }
        }

        String header(String name) { return ex.getRequestHeaders().getFirst(name); }

        byte[] bytes(long limit) throws IOException {
            if (bytes != null) return bytes;
            try (InputStream in = ex.getRequestBody()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[64 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > limit) throw new ApiError(413, "too_large");
                    out.write(buf, 0, n);
                }
                bytes = out.toByteArray();
            }
            return bytes;
        }

        JsonNode json() throws IOException {
            if (json == null) {
                byte[] b = bytes(64 * 1024);
                json = b.length == 0 ? MAPPER.createObjectNode() : MAPPER.readTree(b);
            }
            return json;
        }

        String str(String field) throws IOException {
            JsonNode n = json().get(field);
            return n == null || n.isNull() ? "" : n.asText("");
        }
    }

    @FunctionalInterface
    interface Handler { Object handle(Req r) throws Exception; }

    private record Route(String method, Pattern pattern, boolean auth, Handler handler) {}

    private final List<Route> routes = new ArrayList<>();
    private final TelegramAuth auth;
    private final Long devUser;

    WebServer(String botToken) {
        this.auth = new TelegramAuth(botToken);
        Long dev = null;
        try {
            String v = System.getenv("WEBAPP_DEV_USER");
            if (v != null && !v.isBlank()) dev = Long.parseLong(v.trim());
        } catch (Exception ignored) {}
        this.devUser = dev;
        if (dev != null) System.out.println("⚠️ WEBAPP_DEV_USER=" + dev + ": запросы без initData идут от этого пользователя");
    }

    void get(String regex, Handler h)        { routes.add(new Route("GET",  Pattern.compile(regex), true,  h)); }
    void post(String regex, Handler h)       { routes.add(new Route("POST", Pattern.compile(regex), true,  h)); }
    void publicGet(String regex, Handler h)  { routes.add(new Route("GET",  Pattern.compile(regex), false, h)); }

    private String webhookPath;
    private String webhookSecret;
    private java.util.function.Consumer<byte[]> webhookSink;

    /** Приём апдейтов Telegram по webhook: тело POST уходит в sink как есть. */
    void webhook(String path, String secret, java.util.function.Consumer<byte[]> sink) {
        this.webhookPath = path;
        this.webhookSecret = secret;
        this.webhookSink = sink;
    }

    void start(int port) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(port), 0);
        http.createContext("/api/", this::handleApi);
        http.createContext("/app", this::handleStatic);
        if (webhookPath != null) http.createContext(webhookPath, this::handleWebhook);
        http.createContext("/", WebServer::handleHealth);
        http.setExecutor(Executors.newFixedThreadPool(16));
        http.start();
    }

    // ─────────────────────────────────────────────
    //  WEBHOOK
    // ─────────────────────────────────────────────

    /** Больше апдейт быть не может: Telegram шлёт максимум ~1 МБ. */
    private static final int WEBHOOK_LIMIT = 1024 * 1024;

    private void handleWebhook(HttpExchange ex) {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            // Адрес webhook знает только Telegram, но заголовок проверяем всё равно:
            // без него любой, кто узнал путь, слал бы боту поддельные апдейты.
            String secret = ex.getRequestHeaders().getFirst("X-Telegram-Bot-Api-Secret-Token");
            if (webhookSecret != null && !webhookSecret.equals(secret)) {
                ex.sendResponseHeaders(403, -1);
                return;
            }
            byte[] body;
            try (InputStream in = ex.getRequestBody()) {
                body = in.readNBytes(WEBHOOK_LIMIT);
            }
            // Отвечаем до обработки: Telegram ждёт ответа и повторяет апдейт, если
            // мы задумались, а поход в LMS занимает секунды.
            ex.sendResponseHeaders(200, -1);
            webhookSink.accept(body);
        } catch (IOException e) {
            System.err.println("[WebServer] webhook: " + e.getMessage());
        } finally {
            ex.close();
        }
    }

    // ─────────────────────────────────────────────
    //  HEALTH
    // ─────────────────────────────────────────────

    private static void handleHealth(HttpExchange ex) throws IOException {
        byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
        // Health-check Render ходит методом HEAD, а на HEAD тело слать нельзя:
        // длина ответа должна быть -1, иначе JDK пишет WARNING в лог.
        boolean head = "HEAD".equalsIgnoreCase(ex.getRequestMethod());
        ex.sendResponseHeaders(200, head ? -1 : body.length);
        if (!head) {
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        } else {
            ex.close();
        }
    }

    // ─────────────────────────────────────────────
    //  API
    // ─────────────────────────────────────────────

    private void handleApi(HttpExchange ex) {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();

            Route matched = null;
            Matcher m = null;
            boolean pathKnown = false;
            for (Route r : routes) {
                Matcher mm = r.pattern.matcher(path);
                if (!mm.matches()) continue;
                pathKnown = true;
                if (r.method.equals(method)) { matched = r; m = mm; break; }
            }
            if (matched == null) {
                writeJson(ex, pathKnown ? 405 : 404, Map.of("error", pathKnown ? "method_not_allowed" : "not_found"));
                return;
            }

            Req req = new Req(ex, m);
            if (matched.auth) {
                req.user = authenticate(req);
                if (req.user == null) throw new ApiError(401, "unauthorized");
            }

            Object result = matched.handler.handle(req);
            if (result instanceof Raw raw) writeRaw(ex, raw);
            else writeJson(ex, 200, result == null ? Map.of("ok", true) : result);

        } catch (ApiError e) {
            safeWriteJson(ex, e.status, Map.of("error", e.code));
        } catch (Exception e) {
            System.err.println("[WebServer] " + ex.getRequestURI().getPath() + ": " + e);
            safeWriteJson(ex, 500, Map.of("error", "internal"));
        } finally {
            ex.close();
        }
    }

    private TelegramAuth.TgUser authenticate(Req req) {
        TelegramAuth.TgUser u = auth.verify(req.header("X-Telegram-Init-Data"));
        if (u != null) return u;
        String raw = req.header("X-Telegram-Init-Data");
        if (devUser != null && (raw == null || raw.isBlank())) {
            return new TelegramAuth.TgUser(devUser, "Dev", "", "dev", "ru", "");
        }
        return null;
    }

    private static void writeJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        sendMaybeGzip(ex, status, bytes);
    }

    private static void safeWriteJson(HttpExchange ex, int status, Object body) {
        try { writeJson(ex, status, body); } catch (Exception ignored) {}
    }

    private static void writeRaw(HttpExchange ex, Raw raw) throws IOException {
        ex.getResponseHeaders().set("Content-Type", raw.contentType() != null ? raw.contentType() : "application/octet-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        if (raw.filename() != null) {
            String enc = java.net.URLEncoder.encode(raw.filename(), StandardCharsets.UTF_8).replace("+", "%20");
            String ascii = raw.filename().replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "");
            ex.getResponseHeaders().set("Content-Disposition",
                    "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + enc);
        }
        ex.sendResponseHeaders(200, raw.body().length);
        try (OutputStream os = ex.getResponseBody()) { os.write(raw.body()); }
    }

    private static void sendMaybeGzip(HttpExchange ex, int status, byte[] bytes) throws IOException {
        String ae = ex.getRequestHeaders().getFirst("Accept-Encoding");
        if (bytes.length > 1024 && ae != null && ae.contains("gzip")) {
            bytes = gzip(bytes);
            ex.getResponseHeaders().set("Content-Encoding", "gzip");
            ex.getResponseHeaders().add("Vary", "Accept-Encoding");
        }
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 3);
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) { gz.write(data); }
        return out.toByteArray();
    }

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> q = new HashMap<>();
        if (raw == null || raw.isEmpty()) return q;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            q.put(k, v);
        }
        return q;
    }

    // ─────────────────────────────────────────────
    //  STATIC (собранный фронтенд лежит в jar под /webapp)
    // ─────────────────────────────────────────────

    private record Asset(byte[] plain, byte[] gzipped, String type) {}
    private final Map<String, Optional<Asset>> assets = new ConcurrentHashMap<>();

    private void handleStatic(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/app")) {
                ex.getResponseHeaders().set("Location", "/app/");
                ex.sendResponseHeaders(301, -1);
                return;
            }
            String rel = path.substring("/app/".length());
            if (rel.isEmpty() || !rel.contains(".")) rel = "index.html";
            if (rel.contains("..")) { ex.sendResponseHeaders(400, -1); return; }

            Optional<Asset> asset = assets.computeIfAbsent(rel, WebServer::loadAsset);
            if (asset.isEmpty()) {
                byte[] nf = "not found".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(404, nf.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(nf); }
                return;
            }
            Asset a = asset.get();
            ex.getResponseHeaders().set("Content-Type", a.type());
            // Файлы из assets/ содержат хэш в имени — их можно кэшировать навсегда;
            // index.html — никогда, иначе после деплоя Telegram держит старую версию.
            ex.getResponseHeaders().set("Cache-Control", rel.startsWith("assets/")
                    ? "public, max-age=31536000, immutable" : "no-cache");
            String ae = ex.getRequestHeaders().getFirst("Accept-Encoding");
            byte[] body = a.plain();
            if (a.gzipped() != null && ae != null && ae.contains("gzip")) {
                body = a.gzipped();
                ex.getResponseHeaders().set("Content-Encoding", "gzip");
                ex.getResponseHeaders().add("Vary", "Accept-Encoding");
            }
            boolean head = "HEAD".equalsIgnoreCase(ex.getRequestMethod());
            ex.sendResponseHeaders(200, head ? -1 : body.length);
            if (!head) try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        } finally {
            ex.close();
        }
    }

    private static Optional<Asset> loadAsset(String rel) {
        try (InputStream in = WebServer.class.getResourceAsStream("/webapp/" + rel)) {
            if (in == null) return Optional.empty();
            byte[] data = in.readAllBytes();
            String type = contentType(rel);
            boolean text = type.startsWith("text/") || type.contains("javascript") || type.contains("json") || type.contains("svg");
            return Optional.of(new Asset(data, text && data.length > 1024 ? gzip(data) : null, type));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static String contentType(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".html"))  return "text/html; charset=utf-8";
        if (n.endsWith(".js"))    return "text/javascript; charset=utf-8";
        if (n.endsWith(".css"))   return "text/css; charset=utf-8";
        if (n.endsWith(".svg"))   return "image/svg+xml";
        if (n.endsWith(".png"))   return "image/png";
        if (n.endsWith(".ico"))   return "image/x-icon";
        if (n.endsWith(".woff2")) return "font/woff2";
        if (n.endsWith(".woff"))  return "font/woff";
        if (n.endsWith(".json"))  return "application/json";
        if (n.endsWith(".webmanifest")) return "application/manifest+json";
        return "application/octet-stream";
    }
}
