package uz.tuit.lmsbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * JWT OneID пользователя — чтобы поднимать сессию LMS без повторного входа.
 *
 * Сессия LMS живёт около двух часов бездействия, а бесплатный Render спит ночами,
 * поэтому утром cookie уже мертва и раньше приходилось входить заново. Имея JWT,
 * бот сам проходит sso/v1/generate → callbackUrl и получает новую сессию: пароль
 * OneID при этом нигде не хранится и второй фактор не запрашивается.
 *
 * Файл лежит в sessions/ рядом с cookie, поэтому попадает в общую резервную копию
 * (StateBackup), и дополнительно шифруется AES-GCM: ключ берётся из STATE_KEY,
 * а если её нет — из токена бота.
 */
public class OneIdTokenStore {

    /** JWT и момент, когда он перестаёт действовать (0 — в токене не было exp). */
    public record Token(String jwt, long expiresAt) {}

    /** Токен с остатком жизни меньше минуты считаем протухшим: не успеет доехать. */
    private static final long SKEW_MS = 60_000;

    private final ObjectMapper mapper = new ObjectMapper();
    private final SecretBox box;
    private final Path dir;

    public OneIdTokenStore(Path sessionDir, String botToken) {
        this.dir = sessionDir;
        this.box = new SecretBox("lmsbot-oneid", botToken, "LMSID1");
    }

    private Path file(long userId) {
        return dir.resolve("oneid_" + userId + ".json");
    }

    public void save(long userId, String jwt) {
        if (jwt == null || jwt.isBlank()) return;
        try {
            long exp = expiresAt(jwt);
            byte[] plain = mapper.createObjectNode()
                    .put("jwt", jwt)
                    .put("exp", exp)
                    .put("saved", System.currentTimeMillis())
                    .toString().getBytes(StandardCharsets.UTF_8);
            Files.createDirectories(dir);
            Files.write(file(userId), box.seal(plain));
            System.out.println("🔑 OneID-токен сохранён u=" + userId + " до "
                    + (exp > 0 ? java.time.Instant.ofEpochMilli(exp) : "неизвестно"));
        } catch (Exception e) {
            System.err.println("[OneIdTokenStore] save " + userId + ": " + e.getMessage());
        }
    }

    /** Действующий токен пользователя либо null, если его нет или он истёк. */
    public Token load(long userId) {
        Path f = file(userId);
        if (!Files.exists(f)) return null;
        try {
            JsonNode json = mapper.readTree(box.open(Files.readAllBytes(f)));
            String jwt = json.path("jwt").asText(null);
            if (jwt == null || jwt.isBlank()) return null;
            long exp = json.path("exp").asLong(0);
            if (exp > 0 && exp - SKEW_MS <= System.currentTimeMillis()) {
                clear(userId);
                return null;
            }
            return new Token(jwt, exp);
        } catch (Exception e) {
            // Чужой ключ или битый файл — толку от него нет.
            clear(userId);
            return null;
        }
    }

    public void clear(long userId) {
        try { Files.deleteIfExists(file(userId)); } catch (Exception ignored) {}
    }

    /** Срок жизни из claim exp (секунды с эпохи), 0 — если разобрать не удалось. */
    static long expiresAt(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return 0;
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            long exp = new ObjectMapper().readTree(payload).path("exp").asLong(0);
            return exp > 0 ? exp * 1000 : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
