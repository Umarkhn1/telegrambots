package uz.tuit.lmsbot.bot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class UserSettingsStore {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path dir;

    public UserSettingsStore() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) home = ".";
        this.dir = Paths.get(home, ".tuit-lms-bot", "settings");
        try { Files.createDirectories(dir); } catch (Exception ignored) {}
    }

    public Map<Long, UserSettings> loadAll() {
        // Not needed right now (we load per user on demand).
        return new HashMap<>();
    }

    public UserSettings load(long userId) {
        Path f = file(userId);
        if (!Files.exists(f)) return UserSettings.defaults();
        try {
            return mapper.readValue(Files.readAllBytes(f), UserSettings.class);
        } catch (Exception e) {
            return UserSettings.defaults();
        }
    }

    public void save(long userId, UserSettings settings) {
        try {
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(settings);
            Files.write(file(userId), bytes);
        } catch (Exception ignored) {}
    }

    private Path file(long userId) {
        return dir.resolve("settings_" + userId + ".json");
    }
}

