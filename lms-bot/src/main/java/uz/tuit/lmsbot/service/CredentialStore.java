package uz.tuit.lmsbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Логин и пароль пользователя для кнопки «Автовход» — строго по его согласию,
 * которое бот спрашивает отдельно после успешного входа.
 *
 * Базы у бота нет: это файл sessions/creds_<id>.json, зашифрованный AES-GCM
 * (см. SecretBox, ключ из STATE_KEY). Вместе с сессиями он попадает в резервную
 * копию, поэтому автовход переживает деплой и пробуждение Render. /logout файл
 * удаляет — иначе выход не был бы настоящим.
 */
public class CredentialStore {

    /** method: "oneid" — логин и пароль OneID, "lms" — учётная запись lms.tuit.uz. */
    public record Creds(String method, String login, String password) {}

    private final ObjectMapper mapper = new ObjectMapper();
    private final SecretBox box;
    private final Path dir;

    public CredentialStore(Path sessionDir, String botToken) {
        this.dir = sessionDir;
        this.box = new SecretBox("lmsbot-creds", botToken, "LMSCR1");
    }

    private Path file(long userId) {
        return dir.resolve("creds_" + userId + ".json");
    }

    public void save(long userId, Creds c) {
        if (c == null || c.login() == null || c.password() == null) return;
        try {
            byte[] plain = mapper.createObjectNode()
                    .put("method", c.method())
                    .put("login", c.login())
                    .put("password", c.password())
                    .put("saved", System.currentTimeMillis())
                    .toString().getBytes(StandardCharsets.UTF_8);
            Files.createDirectories(dir);
            Files.write(file(userId), box.seal(plain));
        } catch (Exception e) {
            System.err.println("[CredentialStore] save " + userId + ": " + e.getMessage());
        }
    }

    public Creds load(long userId) {
        Path f = file(userId);
        if (!Files.exists(f)) return null;
        try {
            JsonNode json = mapper.readTree(box.open(Files.readAllBytes(f)));
            String login = json.path("login").asText(null);
            String password = json.path("password").asText(null);
            if (login == null || password == null) return null;
            return new Creds(json.path("method").asText("lms"), login, password);
        } catch (Exception e) {
            // Чужой ключ или битый файл — пользоваться нечем.
            clear(userId);
            return null;
        }
    }

    /** Сохранённый логин для подписи кнопки, либо null. */
    public String login(long userId) {
        Creds c = load(userId);
        return c == null ? null : c.login();
    }

    public boolean has(long userId) {
        return Files.exists(file(userId));
    }

    public void clear(long userId) {
        try { Files.deleteIfExists(file(userId)); } catch (Exception ignored) {}
    }
}
