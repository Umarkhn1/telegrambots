package uz.tuit.lmsbot.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/**
 * Проверка initData мини-приложения Telegram.
 * https://core.telegram.org/bots/webapps#validating-data-received-via-the-mini-app
 *
 * Подпись сделана токеном бота, поэтому пользователь из initData — ровно тот же
 * userId, под которым бот хранит LMS-сессию: вход в боте работает и в приложении.
 */
final class TelegramAuth {

    /** Сколько живёт initData. Приложение может долго висеть открытым, поэтому сутки. */
    private static final long MAX_AGE_SEC = 24 * 60 * 60;

    record TgUser(long id, String firstName, String lastName, String username, String languageCode, String photoUrl) {}

    private final byte[] secretKey;
    private final ObjectMapper mapper = new ObjectMapper();

    TelegramAuth(String botToken) {
        this.secretKey = hmac("WebAppData".getBytes(StandardCharsets.UTF_8), botToken.getBytes(StandardCharsets.UTF_8));
    }

    /** Пользователь из подписанной initData либо null, если подпись не сходится или данные устарели. */
    TgUser verify(String initData) {
        if (initData == null || initData.isBlank()) return null;
        try {
            Map<String, String> fields = new TreeMap<>();
            String hash = null;
            for (String pair : initData.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                if ("hash".equals(k)) hash = v;
                else fields.put(k, v);
            }
            if (hash == null) return null;

            StringBuilder check = new StringBuilder();
            for (Map.Entry<String, String> e : fields.entrySet()) {
                if (check.length() > 0) check.append('\n');
                check.append(e.getKey()).append('=').append(e.getValue());
            }
            byte[] expected = hmac(secretKey, check.toString().getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(expected, hexToBytes(hash))) return null;

            long authDate = Long.parseLong(fields.getOrDefault("auth_date", "0"));
            if (System.currentTimeMillis() / 1000 - authDate > MAX_AGE_SEC) return null;

            String userJson = fields.get("user");
            if (userJson == null) return null;
            JsonNode u = mapper.readTree(userJson);
            if (!u.hasNonNull("id")) return null;
            return new TgUser(
                    u.get("id").asLong(),
                    u.path("first_name").asText(""),
                    u.path("last_name").asText(""),
                    u.path("username").asText(""),
                    u.path("language_code").asText(""),
                    u.path("photo_url").asText(""));
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hexToBytes(String hex) {
        if (hex.length() % 2 != 0) return new byte[0];
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
