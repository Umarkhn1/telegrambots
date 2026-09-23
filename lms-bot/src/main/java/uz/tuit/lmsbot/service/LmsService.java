package uz.tuit.lmsbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import uz.tuit.lmsbot.config.AppConfig;
import uz.tuit.lmsbot.model.*;

import java.io.File;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LmsService {

    private final AppConfig config;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<Long, OkHttpClient> clients     = new ConcurrentHashMap<>();
    private final Map<Long, Boolean>      loggedInMap = new ConcurrentHashMap<>();
    private final Map<Long, PersistentCookieJar> jars  = new ConcurrentHashMap<>();

    /** Диагностика HTTP-слоя. По умолчанию выключена: в лог попадают куки и токены. */
    private static final boolean DEBUG = "true".equalsIgnoreCase(System.getenv("LMS_DEBUG"));

    static void dbg(String fmt, Object... args) {
        if (DEBUG) System.out.println("[LMS-DBG] " + String.format(fmt, args));
    }

    private static String snip(String s) {
        if (s == null) return "null";
        String one = s.replaceAll("\s+", " ").trim();
        return one.length() > 220 ? one.substring(0, 220) + "..." : one;
    }

    /** JWT OneID вошедших пользователей — из него поднимается новая сессия LMS. */
    private final OneIdTokenStore oneIdTokens;
    /** Логин и пароль тех, кто согласился на автовход. */
    private final CredentialStore credentials;

    public LmsService(AppConfig config) {
        this.config = config;
        String token = config.getBot().getToken();
        this.oneIdTokens = new OneIdTokenStore(sessionDir(), token);
        this.credentials = new CredentialStore(sessionDir(), token);
    }

    public CredentialStore credentials() {
        return credentials;
    }

    // ─────────────────────────────────────────────
    //  HTTP CLIENT PER USER
    // ─────────────────────────────────────────────

    private OkHttpClient getClient(long userId) {
        return clients.computeIfAbsent(userId, id -> {
            OkHttpClient.Builder b = new OkHttpClient.Builder()
                    .cookieJar(jars.compute(userId, (k, v) -> new PersistentCookieJar(sessionDir(), userId)))
                    .followRedirects(true)
                    // Хост OneID умеет просто не отвечать; ждать полминуты незачем,
                    // пользователю нужен быстрый внятный отказ.
                    .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS);
            if (EGOV_PROXY != null) {
                b.proxySelector(EGOV_PROXY_SELECTOR);
                if (EGOV_PROXY_AUTH != null) b.proxyAuthenticator((route, resp) ->
                        resp.request().newBuilder().header("Proxy-Authorization", EGOV_PROXY_AUTH).build());
            }
            return b.build();
        });
    }

    // ─────────────────────────────────────────────
    //  ПРОКСИ ДЛЯ EGOV
    //  С некоторых адресов id.egov.uz и sso.egov.uz просто не отвечают: соединение
    //  висит до таймаута, хотя lms.tuit.uz с того же сервера открывается мгновенно.
    //  ONEID_PROXY=http://[логин:пароль@]хост:порт уводит туда только запросы к egov.uz,
    //  всё остальное продолжает ходить напрямую.
    // ─────────────────────────────────────────────

    private static final java.net.Proxy EGOV_PROXY = parseProxy(System.getenv("ONEID_PROXY"));
    private static final String EGOV_PROXY_AUTH = parseProxyAuth(System.getenv("ONEID_PROXY"));

    private static final java.net.ProxySelector EGOV_PROXY_SELECTOR = new java.net.ProxySelector() {
        @Override public java.util.List<java.net.Proxy> select(java.net.URI uri) {
            String h = uri == null ? null : uri.getHost();
            boolean egov = h != null && (h.equals("egov.uz") || h.endsWith(".egov.uz"));
            return java.util.List.of(egov && EGOV_PROXY != null ? EGOV_PROXY : java.net.Proxy.NO_PROXY);
        }
        @Override public void connectFailed(java.net.URI uri, java.net.SocketAddress sa, java.io.IOException e) {
            System.err.println("[LmsService] прокси не отвечает для " + uri + ": " + e.getMessage());
        }
    };

    private static java.net.Proxy parseProxy(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            java.net.URI u = java.net.URI.create(raw.contains("://") ? raw.trim() : "http://" + raw.trim());
            int port = u.getPort() > 0 ? u.getPort() : 8080;
            java.net.Proxy.Type type = "socks".equalsIgnoreCase(u.getScheme()) || "socks5".equalsIgnoreCase(u.getScheme())
                    ? java.net.Proxy.Type.SOCKS : java.net.Proxy.Type.HTTP;
            System.out.println("🔀 Запросы к egov.uz идут через " + type + " " + u.getHost() + ":" + port);
            return new java.net.Proxy(type, new java.net.InetSocketAddress(u.getHost(), port));
        } catch (Exception e) {
            System.err.println("[LmsService] ONEID_PROXY не разобран: " + e.getMessage());
            return null;
        }
    }

    private static String parseProxyAuth(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            java.net.URI u = java.net.URI.create(raw.contains("://") ? raw.trim() : "http://" + raw.trim());
            String info = u.getUserInfo();
            if (info == null || info.isBlank()) return null;
            return "Basic " + java.util.Base64.getEncoder()
                    .encodeToString(info.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private Path sessionDir() {
        // Keep it simple and OS-safe: %USERPROFILE%\.tuit-lms-bot\sessions
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) home = ".";
        return Paths.get(home, ".tuit-lms-bot", "sessions");
    }

    // ─────────────────────────────────────────────
    //  AUTH
    // ─────────────────────────────────────────────

    /** Итог входа по логину LMS. */
    public enum LoginResult { OK, WRONG_CREDENTIALS, ONEID_REQUIRED, ERROR }

    /**
     * LMS отказывает во входе по паролю тем, кому положен OneID, и пишет
     * «Iltimos, OneID orqali tizimga kiring» / «Пожалуйста, войдите в систему через OneID».
     * Кнопка «OneID» есть на странице всегда, поэтому ищем именно фразу — и в тексте,
     * и в скриптах уведомлений, где она бывает экранирована как \\uXXXX.
     */
    private static final Pattern ONEID_REQUIRED = Pattern.compile(
            "(?iu)(oneid\\s+orqali\\s+tizimga\\s+kiring|oneid\\s+орқали\\s+тизимга\\s+киринг"
                    + "|войдите\\s+(?:в\\s+систему\\s+)?через\\s+oneid|вход\\s+только\\s+через\\s+oneid"
                    + "|(?:log|sign)\\s*in\\s+(?:via|through|with|using)\\s+oneid)");

    static boolean oneIdRequired(String html) {
        if (html == null) return false;
        if (ONEID_REQUIRED.matcher(html).find()) return true;
        Matcher m = Pattern.compile("\\\\u([0-9a-fA-F]{4})").matcher(html);
        if (!m.find()) return false;
        StringBuilder sb = new StringBuilder();
        m.reset();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(m.group(1), 16))));
        m.appendTail(sb);
        return ONEID_REQUIRED.matcher(sb).find();
    }

    public boolean login(long userId, String login, String password) {
        return loginDetailed(userId, login, password) == LoginResult.OK;
    }

    public LoginResult loginDetailed(long userId, String login, String password) {
        try {
            // Как и в OneID: входим всегда с чистого jar, иначе живая сессия
            // предыдущего аккаунта переживает вход и данные приходят чужие.
            resetSession(userId);
            OkHttpClient client = getClient(userId);
            String loginUrl = config.getLms().getLoginUrl();

            Request getReq = new Request.Builder()
                    .url(loginUrl)
                    .header("User-Agent", userAgent())
                    .build();

            String html;
            try (Response resp = client.newCall(getReq).execute()) {
                html = resp.body().string();
            }

            String csrf = extractCsrf(html);
            if (csrf == null) return LoginResult.ERROR;

            RequestBody body = new FormBody.Builder()
                    .add("_token", csrf)
                    .add("login", login)
                    .add("password", password)
                    .add("g-recaptcha-response", "")
                    .build();

            Request postReq = new Request.Builder()
                    .url(loginUrl)
                    .post(body)
                    .header("User-Agent", userAgent())
                    .header("Referer", loginUrl)
                    .build();

            String finalUrl;
            String responseBody;
            try (Response resp = client.newCall(postReq).execute()) {
                finalUrl     = resp.request().url().toString();
                responseBody = resp.body().string();
            }

            boolean success = finalUrl.contains("/dashboard")
                    || responseBody.contains("page-sidebar")
                    || responseBody.contains("Студент");

            dbg("LMS-LOGIN u=%d final=%s success=%s len=%d", userId, finalUrl, success, responseBody.length());
            dbg("LMS-LOGIN cookies u=%d -> %s", userId, cookieDump(userId));

            loggedInMap.put(userId, success);
            if (success) {
                invalidateSemesters(userId);
                // Вход по паролю LMS мог сменить аккаунт: старый токен OneID поднял бы
                // сессию предыдущего пользователя, и данные пришли бы чужие.
                oneIdTokens.clear(userId);
                return LoginResult.OK;
            }
            if (oneIdRequired(responseBody)) {
                System.out.println("[LmsService] login: LMS requires OneID for this account");
                return LoginResult.ONEID_REQUIRED;
            }
            return LoginResult.WRONG_CREDENTIALS;

        } catch (Exception e) {
            System.err.println("[LmsService] Login error: " + e.getMessage());
            return LoginResult.ERROR;
        }
    }


    // ─────────────────────────────────────────────
    //  ONEID (id.egov.uz) AUTH
    // ─────────────────────────────────────────────

    private static final String ONEID_API = "https://id.egov.uz/api/";
    private static final String ONEID_SSO_CLIENT = "https://sso.egov.uz/sso/oauth/client";
    private static final MediaType JSON_UTF8 = MediaType.parse("application/json; charset=utf-8");

    /** token_id + client_id текущей OneID-сессии пользователя. */
    private static class OneIdSession {
        String tokenId;
        String clientId;
        String jwt;
        // Mobile ID: выдаются generateCodeForMobileId, нужны для подтверждения SMS
        String phone;
        String actionId;
        long controlCode;
        /** Когда диалог входа начался: брошенный на полпути не должен мешать вечно. */
        final long started = System.currentTimeMillis();
    }

    private final Map<Long, OneIdSession> oneIdSessions = new ConcurrentHashMap<>();

    /** Сколько ждём человека на шагах входа, прежде чем считать диалог брошенным. */
    private static final long LOGIN_DIALOG_MS = 10L * 60 * 1000;

    /** Результат шага OneID-авторизации. */
    public static class OneIdResult {
        public enum Status { OK, NEED_SMS, ERROR, UNREACHABLE }
        public final Status status;
        public final String message;
        private OneIdResult(Status s, String m) { this.status = s; this.message = m; }
        static OneIdResult ok()              { return new OneIdResult(Status.OK, null); }
        static OneIdResult needSms()         { return new OneIdResult(Status.NEED_SMS, null); }
        static OneIdResult error(String msg) { return new OneIdResult(Status.ERROR, msg); }
        /** Сам OneID не ответил — отличать от отказа, иначе выглядит как неверный пароль. */
        static OneIdResult unreachable()     { return new OneIdResult(Status.UNREACHABLE, null); }
    }

    /**
     * Шаг 1. Открывает LMS-эндпоинт /login/oneid тем же cookie jar, что и обычный вход,
     * и достаёт из финального URL id.egov.uz параметры token_id / client_id.
     */
    private OneIdSession oneIdStart(long userId) throws Exception {
        // Старая сессия LMS перехватывает /login/oneid и редиректит сразу на /dashboard,
        // из-за чего token_id не выдаётся. Поэтому вход всегда начинаем с чистого jar.
        resetSession(userId);
        OkHttpClient client = getClient(userId);
        Request req = new Request.Builder()
                .url(config.getLms().getBaseUrl() + "/login/oneid")
                .header("User-Agent", userAgent())
                .build();
        String finalUrl;
        try (Response resp = client.newCall(req).execute()) {
            finalUrl = resp.request().url().toString();
            if (resp.body() != null) resp.body().string();
        }
        dbg("ONEID start u=%d final=%s", userId, finalUrl);
        HttpUrl url = HttpUrl.parse(finalUrl);
        if (url == null) throw new IllegalStateException("OneID: bad redirect url");
        OneIdSession s = new OneIdSession();
        s.tokenId  = url.queryParameter("token_id");
        s.clientId = url.queryParameter("client_id");
        if (s.tokenId == null || s.clientId == null)
            throw new IllegalStateException("OneID: token_id/client_id not found in " + finalUrl);
        oneIdSessions.put(userId, s);
        return s;
    }

    private Request.Builder oneIdReq(String path) {
        return new Request.Builder()
                .url(ONEID_API + path)
                .header("User-Agent", userAgent())
                .header("Accept", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-Origin", "web-client")
                .header("Accept-Language", "ru")
                .header("Origin", "https://id.egov.uz")
                .header("Referer", "https://id.egov.uz/");
    }

    /** Достаёт человекочитаемую ошибку из тела ответа OneID. */
    private String oneIdError(String body) {
        try {
            JsonNode n = mapper.readTree(body);
            String msg = n.path("message").asText(null);
            if (msg != null && !msg.isBlank()) return msg;
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Шаг 2. Логин по паролю OneID. Возвращает OK (можно завершать),
     * NEED_SMS (нужен 2FA-код из приложения OneID) либо ERROR с текстом от OneID.
     */
    public OneIdResult oneIdLogin(long userId, String login, String password) {
        try {
            oneIdStart(userId);
            OneIdSession s = oneIdSessions.get(userId);

            String payload = mapper.createObjectNode()
                    .put("login", login)
                    .put("password", password)
                    .toString();

            Request req = oneIdReq("identity/auth/login")
                    .header("X-Authorization-Method", "LOGINPASSMETHOD")
                    .post(RequestBody.create(payload, JSON_UTF8))
                    .build();

            String body;
            int code;
            try (Response resp = getClient(userId).newCall(req).execute()) {
                code = resp.code();
                body = resp.body() != null ? resp.body().string() : "";
            }

            dbg("ONEID login u=%d code=%d len=%d", userId, code, body.length());
            if (code >= 400) return OneIdResult.error(oneIdError(body));

            JsonNode json = mapper.readTree(body);
            if (json.hasNonNull("token")) {
                s.jwt = json.get("token").asText();
                return OneIdResult.ok();
            }
            if (json.path("code").asInt(-1) == 2) {
                dbg("ONEID 2fa u=%d body=%s", userId, snip(body));
                return OneIdResult.needSms();
            }
            return OneIdResult.error(oneIdError(body));

        } catch (Exception e) {
            System.err.println("[LmsService] OneID login error: " + e.getMessage());
            return unreachable(e) ? OneIdResult.unreachable() : OneIdResult.error(null);
        }
    }

    /** Сбой сети, а не отказ OneID: соединение не встало или оборвалось. */
    private static boolean unreachable(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.SocketTimeoutException
                    || t instanceof java.net.ConnectException
                    || t instanceof java.net.UnknownHostException
                    || t instanceof java.net.NoRouteToHostException) return true;
        }
        return false;
    }

    /** Номер в формате OneID (+998XXXXXXXXX) или null, если номер не узбекский. */
    public static String normalizeUzPhone(String raw) {
        if (raw == null) return null;
        String d = raw.replaceAll("\\D", "");
        if (d.length() == 9) d = "998" + d;
        return d.length() == 12 && d.startsWith("998") ? "+" + d : null;
    }

    /**
     * Шаг 2 (Mobile ID). Запрашивает SMS-код на номер, привязанный к Mobile ID.
     * NEED_SMS — код отправлен, ждём его от пользователя.
     */
    public OneIdResult oneIdMobileSendSms(long userId, String phone) {
        try {
            OneIdSession s = oneIdStart(userId);
            s.phone = phone;

            String payload = mapper.createObjectNode().put("phone", phone).toString();
            Request req = oneIdReq("identity/auth/generateCodeForMobileId")
                    .post(RequestBody.create(payload, JSON_UTF8))
                    .build();

            String body;
            int code;
            try (Response resp = getClient(userId).newCall(req).execute()) {
                code = resp.code();
                body = resp.body() != null ? resp.body().string() : "";
            }
            dbg("ONEID mobile sms u=%d code=%d body=%s", userId, code, snip(body));
            if (code >= 400) return OneIdResult.error(oneIdError(body));

            JsonNode json = mapper.readTree(body);
            s.actionId    = json.path("actionId").asText(null);
            s.controlCode = json.path("controlCode").asLong(0);
            if (s.actionId == null || s.actionId.isBlank()) return OneIdResult.error(oneIdError(body));
            return OneIdResult.needSms();

        } catch (Exception e) {
            System.err.println("[LmsService] OneID mobile sms error: " + e.getMessage());
            return unreachable(e) ? OneIdResult.unreachable() : OneIdResult.error(null);
        }
    }

    /** Шаг 2b (Mobile ID). Вход по SMS-коду, полученному после oneIdMobileSendSms. */
    public OneIdResult oneIdMobileConfirm(long userId, String smsCode) {
        try {
            OneIdSession s = oneIdSessions.get(userId);
            if (s == null || s.actionId == null) return OneIdResult.error(null);

            String payload = mapper.createObjectNode()
                    .put("actionId", s.actionId)
                    .put("controlCode", s.controlCode)
                    .put("smsCode", smsCode.trim())
                    .put("phone", s.phone)
                    .toString();

            Request req = oneIdReq("identity/auth/login")
                    .header("X-Authorization-Method", "MOBILEIDMETHOD")
                    .post(RequestBody.create(payload, JSON_UTF8))
                    .build();

            String body;
            int code;
            try (Response resp = getClient(userId).newCall(req).execute()) {
                code = resp.code();
                body = resp.body() != null ? resp.body().string() : "";
            }
            dbg("ONEID mobile login u=%d code=%d len=%d", userId, code, body.length());
            if (code >= 400) return OneIdResult.error(oneIdError(body));

            JsonNode json = mapper.readTree(body);
            if (json.hasNonNull("token")) {
                s.jwt = json.get("token").asText();
                return OneIdResult.ok();
            }
            return OneIdResult.error(oneIdError(body));

        } catch (Exception e) {
            System.err.println("[LmsService] OneID mobile login error: " + e.getMessage());
            return unreachable(e) ? OneIdResult.unreachable() : OneIdResult.error(null);
        }
    }

    /** Шаг 2b. Подтверждение входа 2FA-кодом из приложения OneID (когда OneID вернул code=2). */
    public OneIdResult oneIdConfirm(long userId, String login, String smsCode) {
        try {
            OneIdSession s = oneIdSessions.get(userId);
            if (s == null) return OneIdResult.error(null);

            String payload = mapper.createObjectNode()
                    .put("login", login)
                    .put("code", smsCode)
                    .toString();

            Request req = oneIdReq("identity/auth/login/confirm")
                    .post(RequestBody.create(payload, JSON_UTF8))
                    .build();

            String body;
            int code;
            try (Response resp = getClient(userId).newCall(req).execute()) {
                code = resp.code();
                body = resp.body() != null ? resp.body().string() : "";
            }
            if (code >= 400) return OneIdResult.error(oneIdError(body));

            JsonNode json = mapper.readTree(body);
            if (json.hasNonNull("token")) {
                s.jwt = json.get("token").asText();
                return OneIdResult.ok();
            }
            return OneIdResult.error(oneIdError(body));

        } catch (Exception e) {
            System.err.println("[LmsService] OneID confirm error: " + e.getMessage());
            return unreachable(e) ? OneIdResult.unreachable() : OneIdResult.error(null);
        }
    }

    /**
     * Шаг 3. Обменивает JWT OneID на one_code через sso/v1/generate и дергает
     * callbackUrl LMS тем же cookie jar — после этого сессия LMS авторизована.
     */
    public boolean oneIdFinish(long userId) {
        OneIdSession s = oneIdSessions.get(userId);
        if (s == null || s.jwt == null) return false;
        boolean success = ssoExchange(userId, s.jwt, s.tokenId, s.clientId);
        oneIdSessions.remove(userId);
        loggedInMap.put(userId, success);
        if (success) {
            invalidateSemesters(userId);
            // Токен переживает и сон сервиса, и деплой: по нему сессия поднимется сама.
            oneIdTokens.save(userId, s.jwt);
        }
        return success;
    }

    /**
     * Обменивает JWT OneID на сессию LMS: sso/v1/generate выдаёт одноразовый код,
     * его забирает callbackUrl LMS тем же cookie jar. Пароль при этом не нужен.
     */
    private boolean ssoExchange(long userId, String jwt, String tokenId, String clientId) {
        try {
            String payload = mapper.createObjectNode()
                    .put("uuid", tokenId)
                    .put("scope", clientId)
                    .toString();

            Request gen = oneIdReq("sso/v1/generate")
                    .header("Authorization", "Bearer " + jwt)
                    .post(RequestBody.create(payload, JSON_UTF8))
                    .build();

            String body;
            try (Response resp = getClient(userId).newCall(gen).execute()) {
                body = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    System.err.println("[LmsService] OneID generate failed: " + body);
                    return false;
                }
            }

            dbg("ONEID generate u=%d body=%s", userId, snip(body));
            JsonNode json = mapper.readTree(body);
            String callbackUrl = json.path("callbackUrl").asText(null);
            String oneCode     = json.path("code").asText(null);
            String state       = json.path("state").asText("");
            if (callbackUrl == null || oneCode == null) return false;

            HttpUrl cb = HttpUrl.parse(callbackUrl);
            if (cb == null) return false;
            HttpUrl target = cb.newBuilder()
                    .addQueryParameter("code", oneCode)
                    .addQueryParameter("state", state)
                    .build();

            Request cbReq = new Request.Builder()
                    .url(target)
                    .header("User-Agent", userAgent())
                    .header("Referer", "https://id.egov.uz/")
                    .build();

            String finalUrl, html;
            try (Response resp = getClient(userId).newCall(cbReq).execute()) {
                finalUrl = resp.request().url().toString();
                html     = resp.body() != null ? resp.body().string() : "";
            }

            boolean success = finalUrl.contains("/dashboard")
                    || html.contains("page-sidebar")
                    || html.contains("Студент");

            dbg("ONEID callback u=%d target=%s final=%s success=%s len=%d",
                    userId, target, finalUrl, success, html.length());
            dbg("ONEID cookies u=%d -> %s", userId, cookieDump(userId));

            return success;

        } catch (Exception e) {
            System.err.println("[LmsService] OneID sso exchange error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Поднимает сессию LMS по сохранённому JWT OneID, без участия пользователя.
     * Вызывать только когда сессия точно мертва: oneIdStart очищает cookie jar.
     */
    public boolean reauthOneId(long userId) {
        // Пользователь прямо сейчас входит руками: oneIdStart ниже стёр бы cookie jar
        // вместе с сессией, которой выдан его token_id, и ввод пароля или SMS-кода
        // провалился бы. Ждать нечего — он и так вот-вот войдёт.
        OneIdSession pending = oneIdSessions.get(userId);
        if (pending != null) {
            if (System.currentTimeMillis() - pending.started < LOGIN_DIALOG_MS) return false;
            oneIdSessions.remove(userId);   // диалог бросили — запись больше не мешает
        }
        OneIdTokenStore.Token token = oneIdTokens.load(userId);
        if (token == null) return false;
        try {
            // Нужны свежие token_id/client_id — старые одноразовые.
            OneIdSession s = oneIdStart(userId);
            boolean ok = ssoExchange(userId, token.jwt(), s.tokenId, s.clientId);
            oneIdSessions.remove(userId);
            loggedInMap.put(userId, ok);
            if (ok) {
                invalidateSemesters(userId);
                System.out.println("♻️ Сессия LMS восстановлена по OneID-токену u=" + userId);
            } else {
                // Токен отозван или протух раньше времени — больше им не пользуемся.
                oneIdTokens.clear(userId);
                System.out.println("⚠️ OneID-токен больше не подходит u=" + userId + ", нужен вход вручную");
            }
            return ok;
        } catch (Exception e) {
            System.err.println("[LmsService] OneID reauth " + userId + ": " + e.getMessage());
            return false;
        }
    }

    /** Имя вуза-клиента OneID для показа пользователю (может вернуть null). */
    public String oneIdClientName(long userId) {
        try {
            OneIdSession s = oneIdSessions.get(userId);
            if (s == null) return null;
            Request req = new Request.Builder()
                    .url(ONEID_SSO_CLIENT + "?clientId=" + s.clientId + "&tokenId=" + s.tokenId)
                    .header("User-Agent", userAgent())
                    .build();
            try (Response resp = getClient(userId).newCall(req).execute()) {
                String body = resp.body() != null ? resp.body().string() : "";
                return mapper.readTree(body).path("name").asText(null);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Короткий дамп cookie для lms.tuit.uz — нужен для диагностики сессии. */
    String cookieDump(long userId) {
        PersistentCookieJar jar = jars.get(userId);
        if (jar == null) return "<no jar>";
        StringBuilder sb = new StringBuilder();
        for (okhttp3.Cookie c : jar.loadForRequest(HttpUrl.parse(config.getLms().getBaseUrl() + "/"))) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(c.name()).append('=')
              .append(c.value().length() > 12 ? c.value().substring(0, 12) + "~" : c.value());
        }
        return sb.length() == 0 ? "<empty>" : sb.toString();
    }

    /** Сбрасывает cookie jar пользователя, не трогая остальное состояние бота. */
    private void resetSession(long userId) {
        clients.remove(userId);
        loggedInMap.remove(userId);
        invalidateSemesters(userId);
        try {
            new PersistentCookieJar(sessionDir(), userId).clear();
        } catch (Exception ignored) {}
    }

    /**
     * Возвращает пользователя в строй после перезапуска или сна сервиса: loggedInMap
     * живёт в памяти, а cookie — на диске. Если сессия на стороне LMS уже истекла
     * (она умирает примерно через два часа простоя), молча поднимаем новую по
     * сохранённому JWT OneID — пользователь ничего не замечает.
     */
    public boolean restoreSession(long userId) {
        Boolean alive = probeSession(userId);
        if (Boolean.TRUE.equals(alive)) return true;
        // null — LMS не ответила: о сессии ничего не известно, сносить её нельзя.
        if (alive == null) return false;
        return reauthOneId(userId);
    }

    /**
     * Жива ли сессия на стороне LMS: true/false, либо null, если LMS не ответила
     * (сеть, таймаут) — тогда о сессии ничего не известно и трогать её нельзя.
     * Протухшую сессию помечает как невошедшую, cookie не стирает.
     */
    public Boolean probeSession(long userId) {
        try {
            Request req = new Request.Builder()
                    .url(config.getLms().getBaseUrl() + "/student/info")
                    .header("User-Agent", userAgent())
                    .build();
            String finalUrl, html;
            try (Response resp = getClient(userId).newCall(req).execute()) {
                if (resp.code() >= 500) return null;
                finalUrl = resp.request().url().toString();
                html     = resp.body() != null ? resp.body().string() : "";
            }
            boolean ok = !finalUrl.contains("/auth/login")
                    && !html.contains("name=\"password\"")
                    && finalUrl.contains("/student/info");
            if (ok) loggedInMap.put(userId, true);
            else    loggedInMap.remove(userId);
            return ok;
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isLoggedIn(long userId) {
        return loggedInMap.getOrDefault(userId, false);
    }

    public void logout(long userId) {
        loggedInMap.remove(userId);
        clients.remove(userId);
        oneIdSessions.remove(userId);
        // Без этого сессия поднялась бы обратно по сохранённому токену.
        // Сами учётные данные не трогаем: ими управляет меню «Мои аккаунты»,
        // и выход из одного аккаунта не должен стирать список остальных.
        oneIdTokens.clear(userId);
        invalidateSemesters(userId);
        try {
            // Also clear persistent cookies so user is fully logged out
            new PersistentCookieJar(sessionDir(), userId).clear();
        } catch (Exception ignored) {}
    }

    // ─────────────────────────────────────────────
    //  MY COURSES
    // ─────────────────────────────────────────────

    public List<Course> getMyCourses(long userId, int semesterId) {
        // id семестра известен только после разбора страницы LMS; -1 значит «ещё не знаем».
        if (semesterId <= 0) return Collections.emptyList();
        try {
            String url = config.getLms().getBaseUrl()
                    + "/student/my-courses/data?"
                    + "draw=1"
                    + "&columns%5B0%5D%5Bdata%5D=subject&columns%5B0%5D%5Bname%5D=subject"
                    + "&columns%5B0%5D%5Bsearchable%5D=true&columns%5B0%5D%5Borderable%5D=true"
                    + "&columns%5B0%5D%5Bsearch%5D%5Bvalue%5D=&columns%5B0%5D%5Bsearch%5D%5Bregex%5D=false"
                    + "&columns%5B1%5D%5Bdata%5D=teachers&columns%5B1%5D%5Bname%5D=teachers"
                    + "&columns%5B1%5D%5Bsearchable%5D=false&columns%5B1%5D%5Borderable%5D=true"
                    + "&columns%5B1%5D%5Bsearch%5D%5Bvalue%5D=&columns%5B1%5D%5Bsearch%5D%5Bregex%5D=false"
                    + "&columns%5B2%5D%5Bdata%5D=attendance&columns%5B2%5D%5Bname%5D=attendance"
                    + "&columns%5B2%5D%5Bsearchable%5D=false&columns%5B2%5D%5Borderable%5D=true"
                    + "&columns%5B2%5D%5Bsearch%5D%5Bvalue%5D=&columns%5B2%5D%5Bsearch%5D%5Bregex%5D=false"
                    + "&order%5B0%5D%5Bcolumn%5D=0&order%5B0%5D%5Bdir%5D=asc"
                    + "&start=0&length=100"
                    + "&semester_id=" + semesterId;

            String json = getJson(userId, url, config.getLms().getBaseUrl() + "/student/my-courses");
            JsonNode root = mapper.readTree(json);
            JsonNode data = root.get("data");

            List<Course> courses = new ArrayList<>();
            if (data == null || !data.isArray()) return courses;

            for (JsonNode node : data) {
                Course c = new Course();
                c.setId(node.path("id").asInt());
                c.setSubject(node.path("subject").asText(""));
                c.setTeachers(node.path("teachers").asText(""));
                c.setStreams(node.path("streams").asText(""));
                c.setAttendance(node.path("attendance").asInt(0));
                c.setFailed(node.path("failed").asBoolean(false));
                c.setSemesterId(node.path("semester_id").asInt(semesterId));
                c.setDownloadQuestionsUrl(node.path("download_questions_url").asText(null));
                courses.add(c);
            }

            courses.sort(Comparator
                    .comparing(Course::isFailed).reversed()
                    .thenComparing(Course::getSubject));

            return courses;

        } catch (Exception e) {
            System.err.println("[LmsService] getMyCourses error: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    // ─────────────────────────────────────────────
    //  ATTENDANCE
    // ─────────────────────────────────────────────

    public List<AttendanceRecord> getAttendance(long userId, int subjectId, int semesterId, String subjectFilter) {
        if (semesterId <= 0) return Collections.emptyList();
        try {
            String url = config.getLms().getBaseUrl()
                    + "/student/attendance/data?"
                    + "draw=1"
                    + "&columns%5B0%5D%5Bdata%5D=date&columns%5B0%5D%5Bname%5D=date"
                    + "&columns%5B0%5D%5Bsearchable%5D=true&columns%5B0%5D%5Borderable%5D=true"
                    + "&columns%5B1%5D%5Bdata%5D=type&columns%5B1%5D%5Bname%5D=type"
                    + "&columns%5B1%5D%5Bsearchable%5D=true&columns%5B1%5D%5Borderable%5D=true"
                    + "&columns%5B2%5D%5Bdata%5D=calendar&columns%5B2%5D%5Bname%5D=calendar"
                    + "&columns%5B2%5D%5Bsearchable%5D=true&columns%5B2%5D%5Borderable%5D=true"
                    + "&order%5B0%5D%5Bcolumn%5D=0&order%5B0%5D%5Bdir%5D=desc"
                    + "&start=0&length=100"
                    + "&semester_id=" + semesterId;

            String referer = config.getLms().getBaseUrl()
                    + "/student/attendance/?subject_id=" + subjectId
                    + "&semester_id=" + semesterId;

            String json = getJson(userId, url, referer);
            JsonNode root = mapper.readTree(json);

            JsonNode data = root.get("data");

            List<AttendanceRecord> records = new ArrayList<>();

            AttendanceRecord summary = new AttendanceRecord();
            summary.setSummary(true);

            if (data != null && data.isArray()) {
                for (JsonNode node : data) {
                    String subj = node.path("subject").asText("");
                    if (subjectFilter != null && !subjectFilter.isBlank()
                            && !subjectFilter.equals(subj)) continue;

                    AttendanceRecord r = new AttendanceRecord();
                    r.setDate(node.path("date").asText(""));
                    r.setType(node.path("type").asText(""));
                    r.setCalendar(node.path("calendar").asText(""));
                    r.setHasReason(node.path("has_reason").asInt(0));
                    r.setSubject(node.path("subject").asText(""));
                    records.add(r);
                }
            }

            int missed = Math.max(0, records.size());
            int total  = missed; // endpoint returns only missed lessons per subject/semester

            summary.setTotal(total);
            summary.setMissed(missed);
            records.add(0, summary);

            records.subList(1, records.size())
                    .sort(Comparator.comparing(AttendanceRecord::getDate).reversed());

            return records;

        } catch (Exception e) {
            System.err.println("[LmsService] getAttendance error: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    // ─────────────────────────────────────────────
    //  ACTIVITIES (HTML parse with Jsoup)
    // ─────────────────────────────────────────────

    /** Значение из блока сводки: в тексте сначала подпись, затем само число. */
    private static String summaryBoxValue(Element box) {
        Element val = box.selectFirst(".sc-summary-value");
        if (val != null) return val.text().trim();
        String[] parts = box.text().trim().split("\\s+");
        return parts.length > 0 ? parts[parts.length - 1] : "";
    }

    /** «—», «-» и пустая строка означают «балла ещё нет». */
    private static String normalizeScore(String raw) {
        if (raw == null) return null;
        String v = raw.replace('\u2014', '-').replace('\u2013', '-').trim();
        if (v.isEmpty() || v.equals("-")) return null;
        return v;
    }

    /** Убирает служебный префикс «Критерий:» из строки критериев. */
    private static String cleanCriteria(String raw) {
        if (raw == null) return null;
        String v = raw.replaceAll("\\s+", " ").trim();
        v = v.replaceFirst("(?iu)^(критерий|критерии|mezon|mezonlar)\\s*:?\\s*", "");
        return v.isEmpty() ? null : v;
    }

    public CourseSummary getActivities(long userId, int courseId) {
        try {
            String url     = config.getLms().getBaseUrl() + "/student/my-courses/show/" + courseId;
            String referer = config.getLms().getBaseUrl() + "/student/my-courses";

            String html = getHtml(userId, url, referer);
            Document doc = Jsoup.parse(html);

            CourseSummary summary = new CourseSummary();

            // Новая вёрстка: четыре блока .sc-summary-box («Набранные баллы»,
            // «Макс. балл», «Успеваемость», «Текущая оценка»).
            // Старая — h4 внутри table.table-bordered, держим запасным вариантом.
            Elements boxes = doc.select(".sc-summary-box");
            if (boxes.size() >= 4) {
                summary.setEarned(summaryBoxValue(boxes.get(0)));
                summary.setMaxScore(summaryBoxValue(boxes.get(1)));
                summary.setProgress(summaryBoxValue(boxes.get(2)));
                summary.setGrade(summaryBoxValue(boxes.get(3)));
            } else {
                Elements scoreCells = doc.select("table.table-bordered td h4");
                if (scoreCells.size() >= 4) {
                    summary.setEarned(scoreCells.get(0).text().trim());
                    summary.setMaxScore(scoreCells.get(1).text().trim());
                    summary.setProgress(scoreCells.get(2).text().trim());
                    summary.setGrade(scoreCells.get(3).text().trim());
                }
            }

            List<Activity> activities = new ArrayList<>();
            Elements rows = doc.select("table#simple-table1 tbody tr");

            for (int rowIdx = 0; rowIdx < rows.size(); rowIdx++) {
                Element row = rows.get(rowIdx);
                Elements cells = row.select("td");
                if (cells.size() < 6) continue;

                Activity act = new Activity();
                act.setType(cells.get(0).text().trim());
                act.setTeacher(cells.get(1).text().trim());

                // col 2: task name + sample file (btn-default)
                Element taskNameEl = cells.get(2).selectFirst("p b");
                act.setTask(taskNameEl != null ? taskNameEl.text().trim() : cells.get(2).text().trim());

                Element sampleLink = cells.get(2).selectFirst("a.btn-default[href]");
                if (sampleLink == null) sampleLink = cells.get(2).selectFirst("a[href]");
                if (sampleLink != null) {
                    String href = sampleLink.attr("href");
                    if (!href.isEmpty() && !href.equals(config.getLms().getBaseUrl() + "/")
                            && !href.equals(config.getLms().getBaseUrl())) {
                        act.setSampleFileUrl(href);
                        act.setSampleFileName(sampleLink.text().trim());
                    }
                }

                act.setDeadline(cells.get(3).text().trim());

                // Балл теперь <div class="sc-score">4 <span class="sc-max">/ 6</span></div>.
                // Раньше это были две <button>, из-за чего во всех заданиях стояло «—/—».
                Element scoreEl = cells.get(4).selectFirst(".sc-score");
                if (scoreEl != null) {
                    Element maxEl  = scoreEl.selectFirst(".sc-max");
                    String maxText = maxEl != null ? maxEl.text().trim() : "";
                    String earned  = scoreEl.text().trim();
                    if (!maxText.isEmpty() && earned.endsWith(maxText))
                        earned = earned.substring(0, earned.length() - maxText.length()).trim();
                    act.setEarnedScore(normalizeScore(earned));
                    act.setMaxScore(normalizeScore(maxText.replace("/", "")));
                } else {
                    Elements scoreButtons = cells.get(4).select("button");
                    if (scoreButtons.size() >= 2) {
                        act.setEarnedScore(normalizeScore(scoreButtons.get(0).text()));
                        act.setMaxScore(normalizeScore(scoreButtons.get(1).text()));
                    }
                }

                // В колонке «Файл» либо ссылка на сданную работу, либо кнопка отправки.
                // Класс ссылки сменился с btn-primary на btn-default, а btn-primary
                // теперь у самой кнопки загрузки — отсюда «ничего не загружено».
                for (Element link : cells.get(5).select("a[href]")) {
                    String href = link.attr("href").trim();
                    if (href.isEmpty() || href.equals("#") || link.hasClass("js-btn-upload")) continue;
                    act.setUploadedFileUrl(href);
                    act.setUploadedFileName(link.text().trim());
                    break;
                }

                // activityId: only from upload button (.js-btn-upload[data-id])
                Element uploadBtn = cells.get(5).selectFirst(".js-btn-upload[data-id]");
                if (uploadBtn != null && !uploadBtn.attr("data-id").isEmpty()) {
                    act.setActivityId(uploadBtn.attr("data-id"));
                } else {
                    act.setActivityId(null);
                }

                // Критерии лежат отдельной строкой таблицы сразу под заданием.
                if (rowIdx + 1 < rows.size()) {
                    Element next = rows.get(rowIdx + 1);
                    if (next.select("td").size() < 6) {
                        Element crit = next.selectFirst(".sc-criteria-list");
                        if (crit == null) crit = next.selectFirst("td");
                        if (crit != null) {
                            List<String> items = uz.tuit.lmsbot.util.Criteria.extract(crit);
                            act.setCriteria(items.isEmpty() ? cleanCriteria(crit.text()) : String.join("\n", items));
                        }
                    }
                }

                activities.add(act);
            }

            activities.sort(Comparator.comparing(Activity::getDeadline));
            summary.setActivities(activities);
            return summary;

        } catch (Exception e) {
            System.err.println("[LmsService] getActivities error: " + e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────
    //  SCHEDULE
    // ─────────────────────────────────────────────

    public List<ScheduleEvent> getSchedule(long userId, int semesterId) {
        // id семестра известен только после разбора страницы LMS; -1 значит «ещё не знаем».
        if (semesterId <= 0) return Collections.emptyList();
        try {
            OkHttpClient client = getClient(userId);
            Request request = new Request.Builder()
                    .url(config.getLms().getBaseUrl() + "/student/schedule/load/" + semesterId)
                    .addHeader("X-Requested-With", "XMLHttpRequest")
                    .addHeader("Accept", "application/json")
                    .build();
            try (Response response = client.newCall(request).execute()) {
                String body = response.body().string();
                JsonNode root = mapper.readTree(body);
                JsonNode jsonArr = root.get("json");
                List<ScheduleEvent> events = new ArrayList<>();
                if (jsonArr != null && jsonArr.isArray()) {
                    for (JsonNode node : jsonArr) {
                        ScheduleEvent e = new ScheduleEvent();
                        e.setTitle(node.get("title").asText());
                        e.setStart(node.get("start").asText());
                        e.setType(node.has("type") ? node.get("type").asInt() : 1);
                        events.add(e);
                    }
                }
                return events;
            }
        } catch (Exception e) {
            System.err.println("[LmsService] Schedule error: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    // ─────────────────────────────────────────────
    //  STUDY PLAN
    // ─────────────────────────────────────────────

    /** «I», «VIII» → 1, 8. Возвращает -1, если это не римское число. */
    private static int romanToInt(String roman) {
        if (roman == null) return -1;
        String r = roman.trim().toUpperCase();
        if (r.isEmpty() || !r.matches("[IVXLC]+")) return -1;
        Map<Character, Integer> val = Map.of('I', 1, 'V', 5, 'X', 10, 'L', 50, 'C', 100);
        int total = 0;
        for (int i = 0; i < r.length(); i++) {
            int cur = val.get(r.charAt(i));
            int next = i + 1 < r.length() ? val.get(r.charAt(i + 1)) : 0;
            total += cur < next ? -cur : cur;
        }
        return total;
    }

    /** Первое целое число в строке; null, если чисел нет («—», пустая ячейка). */
    private static Integer firstInt(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("\\d+").matcher(text);
        return m.find() ? Integer.valueOf(m.group()) : null;
    }

    public List<StudyPlanSubject> getStudyPlan(long userId) {
        try {
            String html = getHtml(userId,
                    config.getLms().getBaseUrl() + "/student/study-plan",
                    config.getLms().getBaseUrl() + "/student/study-plan");
            List<StudyPlanSubject> subjects = parseStudyPlan(html);
            dbg("STUDY-PLAN u=%d subjects=%d", userId, subjects.size());
            return subjects;
        } catch (Exception e) {
            System.err.println("[LmsService] StudyPlan error: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    static List<StudyPlanSubject> parseStudyPlan(String html) {
            Document doc = Jsoup.parse(html);
            List<StudyPlanSubject> subjects = new ArrayList<>();

            // Вёрстка LMS: .semester-card с римским номером семестра в .semester-num.
            // Старый макет (div.card + p.font-18) держим как запасной вариант.
            Elements cards = doc.select("div.semester-card");
            boolean legacy = cards.isEmpty();
            if (legacy) cards = doc.select("div.card");

            int fallbackNum = 0;
            for (Element card : cards) {
                int semester;
                if (legacy) {
                    if (card.selectFirst("p.font-18") == null) continue;
                    semester = ++fallbackNum;
                } else {
                    Element num = card.selectFirst(".semester-num");
                    int parsed = num != null ? romanToInt(num.text()) : -1;
                    if (parsed < 0 && num != null) {
                        Integer arabic = firstInt(num.text());
                        parsed = arabic != null ? arabic : -1;
                    }
                    semester = parsed > 0 ? parsed : ++fallbackNum;
                    fallbackNum = Math.max(fallbackNum, semester);
                }

                for (Element row : card.select("tbody tr")) {
                    Elements cols = row.select("td");
                    if (cols.size() < 3) continue;

                    Element nameEl   = row.selectFirst("td.td-subject");
                    Element creditEl = row.selectFirst("td.td-credit");
                    Element gradeEl  = row.selectFirst("td.td-grade");
                    if (nameEl   == null) nameEl   = cols.get(0);
                    if (creditEl == null) creditEl = cols.get(1);
                    if (gradeEl  == null) gradeEl  = cols.get(2);

                    String name = nameEl.text().trim();
                    if (name.isEmpty()) continue;

                    StudyPlanSubject s = new StudyPlanSubject();
                    s.setName(name);
                    Integer credits = firstInt(creditEl.text());
                    s.setCredits(credits != null ? credits : 0);
                    // «—» в .grade-empty означает, что предмет ещё не сдан.
                    Element badge = gradeEl.selectFirst(".grade-badge");
                    s.setGrade(firstInt(badge != null ? badge.text() : gradeEl.text()));
                    s.setSemester(semester);
                    subjects.add(s);
                }
            }

            subjects.sort(Comparator.comparingInt(StudyPlanSubject::getSemester));
            return subjects;
    }


    // ─────────────────────────────────────────────
    //  SEMESTERS (подтягиваются с LMS, config — только fallback)
    // ─────────────────────────────────────────────

    private static final long SEMESTERS_TTL_MS = 6L * 60 * 60 * 1000;
    private static final Pattern SEM_YEAR_RE = Pattern.compile("(\\d{4})\\s*[-–/]\\s*(\\d{4})");
    private static final Pattern SEM_NUM_RE  = Pattern.compile("(\\d)\\s*-?\\s*(?:семестр|semestr|semester)", Pattern.CASE_INSENSITIVE);

    // Кэш семестров — строго per-user: у разных студентов разные учебные планы,
    // и общий кэш раздавал всем semester_id первого, кто успел распарсить страницу.
    private final Map<Long, List<AppConfig.SemesterConfig>> semestersCache   = new ConcurrentHashMap<>();
    private final Map<Long, Long>    semestersCacheTs        = new ConcurrentHashMap<>();
    private final Map<Long, Integer> detectedDefaultSemester = new ConcurrentHashMap<>();

    /**
     * Список семестров студента прямо из LMS (выпадающий список на страницах раздела
     * «Мои предметы» / «Расписание»). Кэш на 6 часов, при неудаче — семестры из application.yml.
     */
    public List<AppConfig.SemesterConfig> getSemesters(long userId) {
        long now = System.currentTimeMillis();
        List<AppConfig.SemesterConfig> cached = semestersCache.get(userId);
        if (cached != null && !cached.isEmpty()
                && now - semestersCacheTs.getOrDefault(userId, 0L) < SEMESTERS_TTL_MS) return cached;

        List<AppConfig.SemesterConfig> parsed = parseSemesters(userId);
        if (parsed != null && !parsed.isEmpty()) {
            semestersCache.put(userId, parsed);
            semestersCacheTs.put(userId, now);
            return parsed;
        }
        // Запасного списка нет намеренно: семестры и их id у каждого студента свои
        // и заводятся в LMS, поэтому любой зашитый id рано или поздно даёт пустые ответы.
        return List.of();
    }

    /**
     * Текущий семестр по данным LMS. Возвращает -1, если список ещё не получен —
     * вызывающий код обязан это проверить и не слать запрос с выдуманным id.
     */
    public int getCurrentSemesterId(long userId) {
        List<AppConfig.SemesterConfig> list = getSemesters(userId);
        int detected = detectedDefaultSemester.getOrDefault(userId, -1);
        if (detected > 0) return detected;
        return list.isEmpty() ? -1 : list.get(0).getId();
    }

    /**
     * true — семестр действительно вычитан из LMS для этого пользователя.
     * false — отдаётся запасной id из application.yml, его нельзя запоминать
     * как «выбранный семестр»: он почти наверняка чужой и даст пустые списки.
     */
    public boolean isSemesterDetected(long userId) {
        return detectedDefaultSemester.getOrDefault(userId, -1) > 0;
    }

    /** Человекочитаемое имя семестра. */
    public String semesterName(long userId, int semesterId) {
        for (AppConfig.SemesterConfig s : getSemesters(userId)) {
            if (s.getId() == semesterId) return s.getName();
        }
        return "Semester " + semesterId;
    }

    /** Сбрасывает кэш семестров конкретного пользователя (смена аккаунта, повторный вход). */
    public void invalidateSemesters(long userId) {
        semestersCache.remove(userId);
        semestersCacheTs.remove(userId);
        detectedDefaultSemester.remove(userId);
    }

    /** Сбрасывает кэш семестров всех пользователей. */
    public void invalidateSemesters() {
        semestersCache.clear();
        semestersCacheTs.clear();
        detectedDefaultSemester.clear();
    }

    /**
     * Значок семестра по его названию. LMS пишет их словами («Второй семестр»,
     * «Переобучение первого семестра»), цифры в тексте нет — по SEM_NUM_RE
     * не находилось ничего и всем семестрам подряд доставалась «1».
     */
    private static String semesterEmoji(String text) {
        String t = text.toLowerCase();
        if (t.contains("переобуч") || t.contains("qayta")) return "🔁";
        Matcher nm = SEM_NUM_RE.matcher(t);
        boolean second = (nm.find() && "2".equals(nm.group(1)))
                || t.contains("втор") || t.contains("ikkinchi") || t.contains("иккинчи");
        return second ? "2️⃣" : "1️⃣";
    }

    private List<AppConfig.SemesterConfig> parseSemesters(long userId) {
        // Без живой сессии страницы отдают форму логина: парсить нечего, а запомнить
        // запасной семестр из конфига — верный способ получить пустые списки.
        if (!isLoggedIn(userId)) return null;
        String base = config.getLms().getBaseUrl();
        String[] pages = { "/student/my-courses", "/student/schedule", "/student/final-exams", "/student/attendance" };

        for (String page : pages) {
            try {
                String html = getHtml(userId, base + page, base + "/dashboard");
                if (html == null || html.contains("name=\"password\"")) continue;
                Document doc = Jsoup.parse(html);

                for (Element sel : doc.select("select")) {
                    String key = (sel.id() + " " + sel.attr("name") + " " + sel.className()).toLowerCase();
                    boolean named = key.contains("semester") || key.contains("semestr");

                    Map<Integer, AppConfig.SemesterConfig> found = new LinkedHashMap<>();
                    int selectedId = -1;

                    for (Element o : sel.select("option")) {
                        String value = o.attr("value").trim();
                        String text  = o.text().trim();
                        if (!value.matches("\\d{1,6}") || text.isEmpty()) continue;

                        Matcher ym = SEM_YEAR_RE.matcher(text);
                        boolean hasYear = ym.find();
                        // Без явного имени select'а доверяем только опциям вида "2026-2027 ..."
                        if (!named && !hasYear) continue;

                        AppConfig.SemesterConfig s = new AppConfig.SemesterConfig();
                        s.setId(Integer.parseInt(value));
                        s.setName(text);
                        s.setYear(hasYear ? ym.group(1) + "-" + ym.group(2) : text);

                        s.setEmoji(semesterEmoji(text));

                        found.put(s.getId(), s);
                        if (o.hasAttr("selected")) selectedId = s.getId();
                    }

                    if (found.size() < 2) continue;

                    List<AppConfig.SemesterConfig> list = new ArrayList<>(found.values());
                    // Новые семестры — сверху
                    list.sort((a, b) -> Integer.compare(b.getId(), a.getId()));
                    int def = selectedId > 0 ? selectedId : list.get(0).getId();
                    detectedDefaultSemester.put(userId, def);
                    dbg("SEMESTERS u=%d page=%s default=%d list=%d", userId, page, def, list.size());
                    return list;
                }
            } catch (Exception e) {
                System.err.println("[LmsService] parseSemesters(" + page + "): " + e.getMessage());
            }
        }
        return null;
    }


    /** Сырой HTML произвольной страницы LMS — нужен, чтобы чинить парсеры под реальную вёрстку. */
    public String dumpPage(long userId, String path) {
        try {
            String url = config.getLms().getBaseUrl() + (path.startsWith("/") ? path : "/" + path);
            return getHtml(userId, url, config.getLms().getBaseUrl() + "/student/info");
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    // ─────────────────────────────────────────────
    //  STUDENT INFO
    // ─────────────────────────────────────────────

    public StudentInfo getStudentInfo(long userId) {
        try {
            String html = getHtml(userId,
                    config.getLms().getBaseUrl() + "/student/info",
                    config.getLms().getBaseUrl() + "/student/info");
            Document doc = Jsoup.parse(html);
            StudentInfo info = new StudentInfo();

            Element name = doc.selectFirst(".si-student-name");
            if (name != null) info.setFullName(name.text().trim());

            // «№ 32118-23» — студенческий номер, он же зачётка
            Element sid = doc.selectFirst(".si-student-id");
            if (sid != null) info.setRecordBook(sid.text().replace("№", "").trim());

            // Личные данные (.si-field) и учебные (.si-info-item) устроены одинаково:
            // .si-field-label + .si-field-value
            for (Element f : doc.select(".si-field, .si-info-item")) {
                Element labelEl = f.selectFirst(".si-field-label");
                Element valueEl = f.selectFirst(".si-field-value");
                if (labelEl == null || valueEl == null) continue;

                String label = labelEl.text().toLowerCase().trim();
                String value = valueEl.text().trim();
                if (value.isEmpty()) continue;

                if (label.startsWith("дата рожд") || label.contains("tug"))     info.setBirthDate(value);
                else if (label.equals("пол") || label.contains("jins"))          info.setGender(value);
                else if (label.startsWith("адрес(") || label.contains("(вр)"))   { /* временный адрес пропускаем */ }
                else if (label.startsWith("адрес") || label.contains("manzil"))  info.setAddress(value);
                else if (label.contains("направлен") || label.contains("yo'nalish")) info.setDirection(value);
                else if (label.contains("язык") || label.contains("til"))        info.setLanguage(value);
                else if (label.contains("степень") || label.contains("daraja"))  info.setDegree(value);
                else if (label.contains("тип обучен") || label.contains("turi")) info.setStudyType(value);
                else if (label.equals("курс") || label.contains("kurs"))         info.setCourse(value);
                else if (label.contains("группа") || label.contains("guruh"))    info.setGroup(value);
                else if (label.contains("куратор") || label.contains("kurator")) info.setCurator(value);
                else if (label.contains("стипенд") || label.contains("stipend")) info.setScholarship(value);
            }

            return info;
        } catch (Exception e) {
            System.err.println("[LmsService] getStudentInfo error: " + e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────
    //  FINALS
    // ─────────────────────────────────────────────

    public List<FinalExam> getFinals(long userId, int semesterId) {
        // id семестра известен только после разбора страницы LMS; -1 значит «ещё не знаем».
        if (semesterId <= 0) return Collections.emptyList();
        try {
            String url = config.getLms().getBaseUrl()
                    + "/student/finals/data?"
                    + "draw=1"
                    + "&columns%5B0%5D%5Bdata%5D=subject&columns%5B0%5D%5Bname%5D=subject"
                    + "&columns%5B0%5D%5Bsearchable%5D=true&columns%5B0%5D%5Borderable%5D=true"
                    + "&columns%5B1%5D%5Bdata%5D=stream&columns%5B1%5D%5Bname%5D=stream"
                    + "&columns%5B1%5D%5Bsearchable%5D=true&columns%5B1%5D%5Borderable%5D=true"
                    + "&columns%5B2%5D%5Bdata%5D=date&columns%5B2%5D%5Bname%5D=date"
                    + "&columns%5B2%5D%5Bsearchable%5D=true&columns%5B2%5D%5Borderable%5D=true"
                    + "&columns%5B3%5D%5Bdata%5D=from&columns%5B3%5D%5Bname%5D=from"
                    + "&columns%5B3%5D%5Bsearchable%5D=true&columns%5B3%5D%5Borderable%5D=true"
                    + "&columns%5B4%5D%5Bdata%5D=room&columns%5B4%5D%5Bname%5D=room"
                    + "&columns%5B4%5D%5Bsearchable%5D=true&columns%5B4%5D%5Borderable%5D=true"
                    + "&columns%5B5%5D%5Bdata%5D=f_grade&columns%5B5%5D%5Bname%5D=f_grade"
                    + "&columns%5B5%5D%5Bsearchable%5D=true&columns%5B5%5D%5Borderable%5D=true"
                    + "&order%5B0%5D%5Bcolumn%5D=2&order%5B0%5D%5Bdir%5D=asc"
                    + "&start=0&length=100"
                    + "&semester_id=" + semesterId;

            String referer = config.getLms().getBaseUrl() + "/student/finals";
            String json = getJson(userId, url, referer);
            JsonNode root = mapper.readTree(json);
            JsonNode data = root.get("data");

            List<FinalExam> exams = new ArrayList<>();
            if (data == null || !data.isArray()) return exams;

            for (JsonNode node : data) {
                FinalExam ex = new FinalExam();
                ex.setSubject(node.path("subject").asText(""));
                ex.setStream(node.path("stream").asText(""));
                ex.setDate(node.path("date").asText(""));
                ex.setFrom(node.path("from").asText(""));
                ex.setRoom(node.path("room").asText(""));
                JsonNode gradeNode = node.get("f_grade");
                ex.setGrade(gradeNode != null && !gradeNode.isNull() ? gradeNode.asText() : "—");
                exams.add(ex);
            }

            return exams;

        } catch (Exception e) {
            System.err.println("[LmsService] getFinals error: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    // ─────────────────────────────────────────────
    //  CALENDAR — /student/calendar/{courseId}
    // ─────────────────────────────────────────────

    public Map<String, List<CalendarEntry>> getCalendar(long userId, int courseId) {
        try {
            String url = config.getLms().getBaseUrl() + "/student/calendar/" + courseId;
            String html = getHtml(userId, url, config.getLms().getBaseUrl() + "/student/my-courses");
            Document doc = Jsoup.parse(html);

            Map<String, List<CalendarEntry>> result = new LinkedHashMap<>();

            // Вкладок может быть больше двух (лекция/практика/лаборатория) — берём все
            for (Element pane : doc.select(".tab-content .tab-pane[id]")) {
                String id = pane.id();
                if (id == null || id.isBlank()) continue;
                List<CalendarEntry> list = parseCalendarTab(doc, id);
                if (!list.isEmpty()) result.put(id, list);
            }

            if (result.isEmpty()) {
                List<CalendarEntry> lectures  = parseCalendarTab(doc, "lecture");
                List<CalendarEntry> practices = parseCalendarTab(doc, "practice");
                if (!lectures.isEmpty())  result.put("lecture",  lectures);
                if (!practices.isEmpty()) result.put("practice", practices);
            }

            return result;
        } catch (Exception e) {
            System.err.println("[LmsService] getCalendar error: " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    private List<CalendarEntry> parseCalendarTab(Document doc, String tabId) {
        List<CalendarEntry> entries = new ArrayList<>();
        Element tab = doc.getElementById(tabId);
        if (tab == null) return entries;

        // Актуальная вёрстка: <li class="cal-item"> с .cal-num / .cal-title / .cal-date
        for (Element item : tab.select("li.cal-item")) {
            CalendarEntry entry = new CalendarEntry();

            Element num = item.selectFirst(".cal-num");
            try { entry.setNumber(Integer.parseInt(num.text().trim())); }
            catch (Exception ex) { entry.setNumber(entries.size() + 1); }

            Element title = item.selectFirst(".cal-title");
            entry.setTopic(title != null ? title.text().trim() : "");

            Element date = item.selectFirst(".cal-date");
            entry.setDate(date != null ? date.text().trim() : "");

            // Материалы лежат в раскрывающемся блоке .cal-drawer ссылками .cal-res
            List<CalendarEntry.FileAttachment> files = new ArrayList<>();
            for (Element a : item.select("a.cal-res, .cal-drawer a[href]")) {
                String url = a.attr("abs:href");
                if (url.isEmpty()) url = a.attr("href");
                Element nameEl = a.selectFirst(".cal-res-name");
                String name = nameEl != null ? nameEl.text().trim() : a.text().trim();
                if (!name.isEmpty() && !url.isEmpty() && !url.startsWith("#")) {
                    files.add(new CalendarEntry.FileAttachment(name, url, detectFileType(url, a)));
                }
            }
            entry.setFiles(files);

            if (!entry.getTopic().isEmpty()) entries.add(entry);
        }
        if (!entries.isEmpty()) return entries;

        // Fallback: старая табличная вёрстка
        for (Element row : tab.select("tbody tr")) {
            Elements cols = row.select("td");
            if (cols.size() < 3) continue;

            CalendarEntry entry = new CalendarEntry();
            try { entry.setNumber(Integer.parseInt(cols.get(0).text().trim())); }
            catch (Exception ex) { entry.setNumber(0); }

            Element topicP = cols.get(1).selectFirst("p");
            entry.setTopic(topicP != null ? topicP.text().trim() : cols.get(1).ownText().trim());

            List<CalendarEntry.FileAttachment> files = new ArrayList<>();
            for (Element a : cols.get(1).select("a[href]")) {
                String url  = a.attr("href");
                String name = a.text().trim();
                if (!name.isEmpty() && !url.isEmpty()) {
                    files.add(new CalendarEntry.FileAttachment(name, url, detectFileType(url, a)));
                }
            }
            entry.setFiles(files);
            entry.setDate(cols.get(2).text().trim());
            entries.add(entry);
        }
        return entries;
    }

    /**
     * Detect file type from URL extension or icon class.
     */
    private String detectFileType(String url, Element a) {
        String lower = url.toLowerCase();
        Element icon = a.selectFirst("i[class]");
        String iconClass = icon != null ? icon.attr("class") : "";

        if (lower.endsWith(".pdf"))                               return "pdf";
        if (lower.endsWith(".pptx") || lower.endsWith(".ppt"))    return "ppt";
        if (lower.endsWith(".docx") || lower.endsWith(".doc"))    return "doc";
        if (lower.endsWith(".mp4")  || lower.endsWith(".avi")
                || lower.endsWith(".mov"))                        return "video";
        if (iconClass.contains("video-camera"))                   return "video";
        if (iconClass.contains("file-powerpoint"))                return "ppt";
        if (iconClass.contains("book"))                           return "pdf";
        // Любая внешняя ссылка (не lms.tuit.uz) — открывать в браузере
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            if (!lower.contains("lms.tuit.uz")) return "url";
        }
        return "file";
    }

    // ─────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────

    public record DownloadedFile(File file, String filename, String contentType) {}

    /**
     * Download a file using user's authenticated LMS session (cookies).
     * Returns a temp file; caller should delete it after use.
     */
    public DownloadedFile downloadFile(long userId, String url, String suggestedName) throws Exception {
        String fullUrl = resolveUrl(url);
        Request req = new Request.Builder()
                .url(fullUrl)
                .header("User-Agent", userAgent())
                .header("Referer", config.getLms().getBaseUrl() + "/student/calendar")
                .build();

        try (Response resp = getClient(userId).newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IllegalStateException("Download failed: HTTP " + resp.code());
            }

            String contentType = resp.header("Content-Type", "application/octet-stream");
            String filename = filenameFromDisposition(resp.header("Content-Disposition"));
            if (filename == null || filename.isBlank()) filename = filenameFromUrl(fullUrl);
            if ((filename == null || filename.isBlank()) && suggestedName != null && !suggestedName.isBlank())
                filename = suggestedName;
            if (filename == null || filename.isBlank()) filename = "file";
            filename = sanitizeFilename(filename);

            String suffix = "";
            int dot = filename.lastIndexOf('.');
            if (dot >= 0 && dot < filename.length() - 1) suffix = filename.substring(dot);
            File tmp = File.createTempFile("lmsbot_", suffix.isBlank() ? ".bin" : suffix);

            try (InputStream in = resp.body().byteStream()) {
                Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return new DownloadedFile(tmp, filename, contentType);
        }
    }

    private String resolveUrl(String url) {
        if (url == null) return "";
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        String base = config.getLms().getBaseUrl();
        if (url.startsWith("/")) return base + url;
        return base + "/" + url;
    }

    private String filenameFromUrl(String fullUrl) {
        try {
            int q = fullUrl.indexOf('?');
            String path = (q >= 0 ? fullUrl.substring(0, q) : fullUrl);
            int slash = path.lastIndexOf('/');
            if (slash >= 0 && slash < path.length() - 1) {
                String name = path.substring(slash + 1);
                name = URLDecoder.decode(name, StandardCharsets.UTF_8);
                return name;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String filenameFromDisposition(String cd) {
        if (cd == null) return null;
        // Handles: filename="x.ext" and filename*=UTF-8''x.ext
        Matcher mStar = Pattern.compile("filename\\*=(?:UTF-8''|utf-8''?)([^;]+)").matcher(cd);
        if (mStar.find()) {
            String v = mStar.group(1).trim();
            v = trimQuotes(v);
            try { return URLDecoder.decode(v, StandardCharsets.UTF_8); } catch (Exception ignored) { return v; }
        }
        Matcher m = Pattern.compile("filename=([^;]+)").matcher(cd);
        if (m.find()) return trimQuotes(m.group(1).trim());
        return null;
    }

    private String trimQuotes(String s) {
        if (s == null) return null;
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))
            return s.substring(1, s.length() - 1);
        return s;
    }

    private String sanitizeFilename(String name) {
        // Windows/Telegram friendly
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "_").trim();
        if (cleaned.isBlank()) return "file";
        if (cleaned.length() > 120) cleaned = cleaned.substring(0, 120);
        return cleaned;
    }

    private String getJson(long userId, String url, String referer) throws Exception {
        Request req = new Request.Builder()
                .url(url)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept", "application/json")
                .header("User-Agent", userAgent())
                .header("Referer", referer)
                .build();
        try (Response resp = getClient(userId).newCall(req).execute()) {
            String body = resp.body().string();
            dbg("GET-JSON u=%d code=%d final=%s ct=%s len=%d body=%s",
                    userId, resp.code(), resp.request().url(),
                    resp.header("Content-Type"), body.length(), snip(body));
            return body;
        }
    }

    private String getHtml(long userId, String url, String referer) throws Exception {
        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", userAgent())
                .header("Referer", referer)
                .build();
        try (Response resp = getClient(userId).newCall(req).execute()) {
            String body = resp.body().string();
            dbg("GET-HTML u=%d code=%d final=%s len=%d loginForm=%s sidebar=%s",
                    userId, resp.code(), resp.request().url(), body.length(),
                    body.contains("name=\"password\""), body.contains("page-sidebar"));
            return body;
        }
    }

    private String extractCsrf(String html) {
        Pattern p = Pattern.compile("name=\"_token\"\\s+value=\"([^\"]+)\"");
        Matcher m = p.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private String userAgent() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    }

    // ─────────────────────────────────────────────
    //  PROFILE (photo + password)
    // ─────────────────────────────────────────────

    /** Фото студента (data:image/...): сначала со страницы «Информация», затем со смены пароля. */
    public String getProfilePhotoDataUrl(long userId) {
        String base = config.getLms().getBaseUrl();
        for (String path : new String[]{"/student/info", "/profile/password"}) {
            try {
                Document doc = Jsoup.parse(getHtml(userId, base + path, base + path));
                for (Element img : doc.select("img[src^=data:image]")) {
                    String src = img.attr("src");
                    // Иконки и заглушки короткие; настоящее фото — килобайты base64.
                    if (src.length() > 2000) return src;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    // ─────────────────────────────────────────────
    //  CONTRACT (оплата контракта)
    // ─────────────────────────────────────────────

    /**
     * Суммы контракта в сумах (null — значения нет на странице). notice — сообщение LMS
     * вместо сумм, например «Данные по контракту за 2026-2027 учебный год ещё не сформированы».
     */
    public record ContractInfo(Long total, Long paid, Long debt, String notice, String source) {}

    private final Map<Long, String> contractPath = new ConcurrentHashMap<>();

    /** Слова, по которым видно, что страница вообще про контракт. */
    private static final Pattern K_CONTRACT = Pattern.compile("(?iu)(контракт|kontrakt|shartnoma|contract|шартнома)");
    /** LMS ещё не выставила контракт на учебный год. */
    private static final Pattern K_NOT_FORMED = Pattern.compile(
            "(?iu)(не\\s+сформирован|ещё\\s+не|еще\\s+не|shakllantirilmagan|shakllanmagan|шакллантирилмаган|not\\s+(yet\\s+)?(formed|generated|available)|mavjud\\s+emas|мавжуд\\s+эмас|отсутству)");

    private static final Pattern MONEY = Pattern.compile("(\\d{1,3}(?:[ \u00a0\u202f.,]\\d{3})+|\\d{4,})(?:[.,]\\d{1,2})?(?!\\d)");
    private static final Pattern K_TOTAL = Pattern.compile("(?iu)(сумма\\s+контракт|контракт\\S*\\s+сумм|стоимость|shartnoma\\s+summa|kontrakt\\s+summa|to'lov-?kontrakt|contract\\s+amount|umumiy\\s+summa|jami\\s+summa|общая\\s+сумма|итого)");
    private static final Pattern K_PAID = Pattern.compile("(?iu)(оплачен|оплата\\s+произвед|внесено|to'langan|tolangan|to‘langan|paid)");
    private static final Pattern K_DEBT = Pattern.compile("(?iu)(задолж|долг|qarzdorlik|qarz|остаток|qoldiq|debt)");

    /**
     * Контракт в LMS лежит на отдельной странице, адрес которой в разных версиях сайта разный,
     * поэтому перебираем известные варианты. Общие страницы (/student/info, /dashboard) не
     * смотрим: там попадаются посторонние числа, которые принимались за суммы. Ничего не нашли — null.
     */
    public ContractInfo getContract(long userId) {
        String base = config.getLms().getBaseUrl();
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        // Страница, где контракт нашёлся в прошлый раз, — первой: не перебираем адреса каждый раз.
        String known = contractPath.get(userId);
        if (known != null) paths.add(known);
        paths.addAll(menuContractLinks(userId));
        paths.addAll(List.of("/student/contract", "/student/contracts", "/student/payment", "/student/payments",
                "/student/finance", "/student/kontrakt", "/student/contract-info", "/student/payment-info"));
        for (String path : paths) {
            try {
                Request req = new Request.Builder().url(base + path).header("User-Agent", userAgent())
                        .header("Referer", base + "/student/info").build();
                String html, finalUrl;
                try (Response resp = getClient(userId).newCall(req).execute()) {
                    if (!resp.isSuccessful()) continue;
                    finalUrl = resp.request().url().encodedPath();
                    html = resp.body() != null ? resp.body().string() : "";
                }
                if (html.contains("name=\"password\"")) continue;
                // Несуществующий раздел LMS тихо уводит на другую страницу.
                int qm = path.indexOf('?');
                if (!finalUrl.startsWith(qm >= 0 ? path.substring(0, qm) : path)) continue;
                ContractInfo info = parseContract(html, path);
                if (info != null) {
                    contractPath.put(userId, path);
                    // Без сумм и личных данных: только какая страница подошла — чтобы знать адрес.
                    System.out.println("[LmsService] contract page=" + path
                            + (info.notice() != null ? " notice" : " amounts"));
                    return info;
                }
            } catch (Exception e) {
                dbg("CONTRACT u=%d page=%s err=%s", userId, path, e.getMessage());
            }
        }
        return null;
    }

    /** Пункты меню LMS, ведущие на контракт/оплату, — пути на lms.tuit.uz. */
    private List<String> menuContractLinks(long userId) {
        List<String> out = new ArrayList<>();
        try {
            String base = config.getLms().getBaseUrl();
            Document doc = Jsoup.parse(getHtml(userId, base + "/student/info", base + "/student/info"), base);
            for (Element a : doc.select("a[href]")) {
                String href = a.attr("abs:href");
                String low = href.toLowerCase(Locale.ROOT);
                boolean byText = K_CONTRACT.matcher(a.text()).find();
                boolean byHref = low.contains("contract") || low.contains("kontrakt") || low.contains("payment");
                if (!(byText || byHref) || !href.startsWith(base)) continue;
                String path = href.substring(base.length());
                int q = path.indexOf('#');
                if (q >= 0) path = path.substring(0, q);
                if (path.startsWith("/") && path.length() > 1 && !out.contains(path)) out.add(path);
            }
        } catch (Exception e) {
            dbg("CONTRACT menu u=%d err=%s", userId, e.getMessage());
        }
        return out;
    }

    static ContractInfo parseContract(String html, String source) {
        Document doc = Jsoup.parse(html);
        doc.select("script, style, nav, .page-sidebar, .sidebar, header, footer, .navbar, .dropdown-menu").remove();
        if (!K_CONTRACT.matcher(doc.body() != null ? doc.body().text() : "").find()) return null;

        // Контракт на учебный год ещё не выставлен — LMS пишет об этом вместо таблицы.
        // Берём самый короткий блок с этим сообщением, чтобы показать его дословно.
        String notice = null;
        for (Element el : doc.select("div, p, span, h1, h2, h3, h4, h5, h6, td, li, strong, b")) {
            String text = el.text().trim();
            if (text.isEmpty() || text.length() > 300) continue;
            if (K_NOT_FORMED.matcher(text).find() && K_CONTRACT.matcher(text).find()
                    && (notice == null || text.length() < notice.length())) notice = text;
        }
        if (notice != null) return new ContractInfo(null, null, null, notice, source);

        Long total = null, paid = null, debt = null;
        // Подпись и сумма обычно в одной строке таблицы, карточке или соседних элементах —
        // берём самый мелкий блок, где есть и подпись, и число.
        for (Element el : doc.select("tr, li, p, div, span, td, th, h1, h2, h3, h4, h5, h6, label, dt, dd")) {
            String text = el.text();
            if (text.length() > 160 || text.isEmpty()) continue;
            Matcher mm = MONEY.matcher(text);
            if (!mm.find()) continue;
            long value = parseMoney(mm.group(1));
            if (value < 10_000) continue;
            if (total == null && K_TOTAL.matcher(text).find()) total = value;
            else if (paid == null && K_PAID.matcher(text).find()) paid = value;
            else if (debt == null && K_DEBT.matcher(text).find()) debt = value;
        }
        if (total == null && paid == null && debt == null) return null;
        if (debt == null && total != null && paid != null) debt = Math.max(0, total - paid);
        if (paid == null && total != null && debt != null) paid = Math.max(0, total - debt);
        if (total == null && paid != null && debt != null) total = paid + debt;
        return new ContractInfo(total, paid, debt, null, source);
    }

    private static long parseMoney(String s) {
        return Long.parseLong(s.replaceAll("[^\\d]", ""));
    }

    public boolean changePassword(long userId, String oldPassword, String newPassword, String confirmPassword) {
        try {
            OkHttpClient client = getClient(userId);
            String url = config.getLms().getBaseUrl() + "/profile/password";

            // get CSRF from page
            String html;
            try (Response r = client.newCall(new Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent())
                    .header("Referer", url)
                    .build()).execute()) {
                html = r.body() != null ? r.body().string() : "";
            }
            String csrf = extractCsrf(html);
            if (csrf == null) return false;

            RequestBody body = new FormBody.Builder()
                    .add("_token", csrf)
                    .add("old_password", oldPassword != null ? oldPassword : "")
                    .add("password", newPassword != null ? newPassword : "")
                    .add("password_confirmation", confirmPassword != null ? confirmPassword : "")
                    .build();

            Request req = new Request.Builder()
                    .url(url)
                    .post(body)
                    .header("User-Agent", userAgent())
                    .header("Referer", url)
                    .build();

            String respBody;
            int code;
            try (Response resp = client.newCall(req).execute()) {
                code = resp.code();
                respBody = resp.body() != null ? resp.body().string() : "";
            }
            if (code < 200 || code >= 300) return false;

            String lower = respBody.toLowerCase();
            // heuristics: success toast or no validation errors
            if (lower.contains("toast-success") || lower.contains("успеш") || lower.contains("success")) return true;
            if (lower.contains("invalid") || lower.contains("ошиб") || lower.contains("error")) return false;
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    // ─────────────────────────────────────────────
    //  UPLOAD ACTIVITY FILE
    // ─────────────────────────────────────────────

    /**
     * Upload student file to LMS.
     * POST /student/my-courses/upload
     * Fields: _token (CSRF), id (activityId), file (multipart)
     */
    public boolean uploadActivityFile(long userId, int courseId, String activityId, java.io.File file, String filename) {
        try {
            OkHttpClient client = getClient(userId);
            String showUrl   = config.getLms().getBaseUrl() + "/student/my-courses/show/" + courseId;
            String uploadUrl = config.getLms().getBaseUrl() + "/student/my-courses/upload";

            // get CSRF token from course page
            String html;
            try (Response r = client.newCall(new Request.Builder()
                    .url(showUrl).header("User-Agent", userAgent()).build()).execute()) {
                html = r.body().string();
            }
            String csrf = extractCsrf(html);
            if (csrf == null) { System.err.println("[LmsService] uploadActivityFile: no CSRF"); return false; }

            String lc = filename.toLowerCase();
            String mt = "application/octet-stream";
            if      (lc.endsWith(".pdf"))  mt = "application/pdf";
            else if (lc.endsWith(".doc"))  mt = "application/msword";
            else if (lc.endsWith(".docx")) mt = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            else if (lc.endsWith(".ppt"))  mt = "application/vnd.ms-powerpoint";
            else if (lc.endsWith(".pptx")) mt = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            else if (lc.endsWith(".jpg") || lc.endsWith(".jpeg")) mt = "image/jpeg";
            else if (lc.endsWith(".png"))  mt = "image/png";
            else if (lc.endsWith(".zip"))  mt = "application/zip";
            else if (lc.endsWith(".rar"))  mt = "application/x-rar-compressed";

            RequestBody fileBody = RequestBody.create(file, MediaType.parse(mt));
            MultipartBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("_token", csrf)
                    .addFormDataPart("id", activityId)
                    .addFormDataPart("file", filename, fileBody)
                    .build();

            Request req = new Request.Builder()
                    .url(uploadUrl).post(body)
                    .header("User-Agent", userAgent())
                    .header("Referer", showUrl)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .build();

            try (Response resp = client.newCall(req).execute()) {
                String rb = resp.body() != null ? resp.body().string() : "";
                System.out.println("[LmsService] upload " + resp.code() + ": " + rb);
                return resp.isSuccessful();
            }
        } catch (Exception e) {
            System.err.println("[LmsService] uploadActivityFile error: " + e.getMessage());
            return false;
        }
    }
}