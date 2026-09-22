package uz.tuit.lmsbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
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

    private static final byte[] MAGIC = "LMSID1".getBytes(StandardCharsets.US_ASCII);
    /** Токен с остатком жизни меньше минуты считаем протухшим: не успеет доехать. */
    private static final long SKEW_MS = 60_000;

    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();
    private final Path dir;
    private final byte[] key;

    public OneIdTokenStore(Path sessionDir, String botToken) {
        this.dir = sessionDir;
        String secret = System.getenv("STATE_KEY");
        if (secret == null || secret.isBlank()) secret = botToken;
        try {
            this.key = MessageDigest.getInstance("SHA-256")
                    .digest(("lmsbot-oneid|" + secret).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
            Files.write(file(userId), encrypt(plain));
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
            JsonNode json = mapper.readTree(decrypt(Files.readAllBytes(f)));
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

    private byte[] encrypt(byte[] plain) throws Exception {
        byte[] iv = new byte[12];
        random.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] enc = c.doFinal(plain);
        byte[] out = new byte[MAGIC.length + iv.length + enc.length];
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
        System.arraycopy(iv, 0, out, MAGIC.length, iv.length);
        System.arraycopy(enc, 0, out, MAGIC.length + iv.length, enc.length);
        return out;
    }

    private byte[] decrypt(byte[] data) throws Exception {
        if (data.length < MAGIC.length + 12) throw new IllegalArgumentException("too short");
        for (int i = 0; i < MAGIC.length; i++) if (data[i] != MAGIC[i]) throw new IllegalArgumentException("bad magic");
        byte[] iv = java.util.Arrays.copyOfRange(data, MAGIC.length, MAGIC.length + 12);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        return c.doFinal(data, MAGIC.length + 12, data.length - MAGIC.length - 12);
    }
}
