package uz.tuit.lmsbot.bot;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Что студенту уже показано: НБ и баллы по предметам. Лежит на диске рядом с сессией и попадает
 * в резервную копию: НБ и оценка, поставленные, пока бот был выключен, не теряются, а первый
 * обход после входа только запоминает значения и ничего не рассылает.
 */
final class SeenStore {

    static final class Seen {
        public Map<String, Integer> nb = new HashMap<>();
        /** Предмет → задание → балл ("" — балла нет). */
        public Map<String, Map<String, String>> scores = new HashMap<>();
    }

    /** Новый балл: was — прежний балл либо null, если его не было. */
    record Change(String key, String was) {}

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path dir;
    private final Map<Long, Seen> cache = new ConcurrentHashMap<>();

    SeenStore() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) home = ".";
        this.dir = Paths.get(home, ".tuit-lms-bot", "sessions");
    }

    Seen get(long userId) {
        return cache.computeIfAbsent(userId, this::load);
    }

    private Seen load(long userId) {
        Path f = file(userId);
        if (Files.exists(f)) {
            try {
                return mapper.readValue(Files.readAllBytes(f), Seen.class);
            } catch (Exception e) {
                System.err.println("[SeenStore] load " + userId + ": " + e.getMessage());
            }
        }
        return new Seen();
    }

    /** Запись через временный файл: отключение света посреди записи не испортит прежний. */
    void save(long userId) {
        Seen seen = get(userId);
        try {
            byte[] bytes;
            synchronized (seen) {
                bytes = mapper.writeValueAsBytes(seen);
            }
            Files.createDirectories(dir);
            Path tmp = dir.resolve("seen_" + userId + ".json.tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, file(userId), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            System.err.println("[SeenStore] save " + userId + ": " + e.getMessage());
        }
    }

    void forget(long userId) {
        cache.remove(userId);
        try {
            Files.deleteIfExists(file(userId));
        } catch (Exception ignored) {}
    }

    /** Сверяет баллы группы (предмета) с запомненными, запоминает новые и возвращает изменения. */
    List<Change> apply(long userId, String group, Map<String, String> current) {
        Seen seen = get(userId);
        List<Change> changes;
        boolean dirty;
        synchronized (seen) {
            Map<String, String> known = seen.scores.get(group);
            if (known == null) {
                // Предмет встретился впервые: уже стоящие баллы новостью не считаем.
                known = new LinkedHashMap<>();
                seen.scores.put(group, known);
                for (Map.Entry<String, String> e : current.entrySet()) known.put(e.getKey(), orEmpty(e.getValue()));
                changes = List.of();
                dirty = !current.isEmpty();
            } else {
                Map<String, String> before = new HashMap<>(known);
                changes = diff(known, current);
                dirty = !before.equals(known);
            }
        }
        if (dirty) save(userId);
        return changes;
    }

    /** Строки, которых сейчас нет на странице, не трогаем: сбой LMS не должен стереть запомненное. */
    static List<Change> diff(Map<String, String> known, Map<String, String> current) {
        List<Change> out = new ArrayList<>();
        for (Map.Entry<String, String> e : current.entrySet()) {
            String now = orEmpty(e.getValue());
            String prev = known.put(e.getKey(), now);
            if (isScore(now) && !now.equals(prev)) out.add(new Change(e.getKey(), isScore(prev) ? prev : null));
        }
        return out;
    }

    /** Одинаковые задания в предмете различаем порядковым номером, иначе их баллы перепутаются. */
    static String uniqueKey(Map<String, String> taken, String base) {
        if (!taken.containsKey(base)) return base;
        int n = 2;
        while (taken.containsKey(base + "#" + n)) n++;
        return base + "#" + n;
    }

    /** Выставленный балл — число больше нуля: «0» и «—» LMS показывает, пока работу не оценили. */
    static boolean isScore(String raw) {
        if (raw == null || raw.isBlank()) return false;
        try {
            return Double.parseDouble(raw.replace(',', '.').trim()) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private Path file(long userId) {
        return dir.resolve("seen_" + userId + ".json");
    }
}
