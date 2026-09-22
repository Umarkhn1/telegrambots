package uz.tuit.lmsbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Учётные данные для кнопки «Автовход» — строго по согласию пользователя, которое
 * бот спрашивает отдельно после успешного входа.
 *
 * Аккаунтов может быть несколько: пользователь выбирает в меню «Мои аккаунты», каким
 * входить. Когда сохранён ровно один, он и берётся, выбирать нечего.
 *
 * Базы у бота нет: это файл sessions/creds_<id>.json, зашифрованный AES-GCM
 * (см. SecretBox, ключ из STATE_KEY). Вместе с сессиями он попадает в резервную
 * копию, поэтому автовход переживает деплой и пробуждение Render. Удаляются данные
 * только явно — в том же меню; выход из аккаунта их не трогает, иначе менеджером
 * было бы невозможно пользоваться.
 */
public class CredentialStore {

    /** method: "oneid" — логин и пароль OneID, "lms" — учётная запись lms.tuit.uz. */
    public record Creds(String method, String login, String password) {
        /** Короткий и устойчивый ключ для callback-данных кнопок. */
        public String id() { return method + ":" + login; }
    }

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

    // ─────────────────────────────────────────────
    //  ЧТЕНИЕ
    // ─────────────────────────────────────────────

    public synchronized List<Creds> all(long userId) {
        List<Creds> out = new ArrayList<>();
        JsonNode root = read(userId);
        if (root == null) return out;
        for (JsonNode n : items(root)) {
            String login = n.path("login").asText(null);
            String password = n.path("password").asText(null);
            if (login == null || password == null) continue;
            out.add(new Creds(n.path("method").asText("lms"), login, password));
        }
        return out;
    }

    /** Чем входить: единственный аккаунт, иначе выбранный, иначе первый. */
    public synchronized Creds selected(long userId) {
        List<Creds> list = all(userId);
        if (list.isEmpty()) return null;
        if (list.size() == 1) return list.get(0);
        JsonNode root = read(userId);
        String sel = root == null ? null : root.path("selected").asText(null);
        for (Creds c : list) if (c.id().equals(sel)) return c;
        return list.get(0);
    }

    public synchronized boolean has(long userId) {
        return !all(userId).isEmpty();
    }

    public synchronized String selectedId(long userId) {
        Creds c = selected(userId);
        return c == null ? null : c.id();
    }

    // ─────────────────────────────────────────────
    //  ИЗМЕНЕНИЕ
    // ─────────────────────────────────────────────

    /** Добавляет аккаунт или обновляет пароль уже сохранённого; новый сразу становится выбранным. */
    public synchronized void add(long userId, Creds c) {
        if (c == null || c.login() == null || c.password() == null) return;
        List<Creds> list = all(userId);
        list.removeIf(x -> x.id().equals(c.id()));
        list.add(c);
        write(userId, list, c.id());
    }

    public synchronized void remove(long userId, String id) {
        List<Creds> list = all(userId);
        String sel = selectedId(userId);
        list.removeIf(x -> x.id().equals(id));
        if (list.isEmpty()) { clear(userId); return; }
        write(userId, list, id.equals(sel) ? list.get(0).id() : sel);
    }

    public synchronized void select(long userId, String id) {
        List<Creds> list = all(userId);
        for (Creds c : list) {
            if (c.id().equals(id)) { write(userId, list, id); return; }
        }
    }

    public synchronized void clear(long userId) {
        try { Files.deleteIfExists(file(userId)); } catch (Exception ignored) {}
    }

    // ─────────────────────────────────────────────
    //  ФАЙЛ
    // ─────────────────────────────────────────────

    private JsonNode read(long userId) {
        Path f = file(userId);
        if (!Files.exists(f)) return null;
        try {
            return mapper.readTree(box.open(Files.readAllBytes(f)));
        } catch (Exception e) {
            // Чужой ключ или битый файл — пользоваться нечем.
            clear(userId);
            return null;
        }
    }

    /** Старый формат — один аккаунт в корне объекта; читаем его как список из одного. */
    private List<JsonNode> items(JsonNode root) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode arr = root.get("items");
        if (arr != null && arr.isArray()) {
            arr.forEach(out::add);
        } else if (root.hasNonNull("login")) {
            out.add(root);
        }
        return out;
    }

    private void write(long userId, List<Creds> list, String selected) {
        try {
            ObjectNode root = mapper.createObjectNode();
            if (selected != null) root.put("selected", selected);
            ArrayNode arr = root.putArray("items");
            for (Creds c : list) {
                arr.addObject()
                        .put("method", c.method())
                        .put("login", c.login())
                        .put("password", c.password());
            }
            root.put("saved", System.currentTimeMillis());
            Files.createDirectories(dir);
            Files.write(file(userId), box.seal(root.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            System.err.println("[CredentialStore] write " + userId + ": " + e.getMessage());
        }
    }
}
