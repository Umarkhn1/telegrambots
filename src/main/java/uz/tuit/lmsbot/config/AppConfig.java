package uz.tuit.lmsbot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class AppConfig {

    private BotConfig bot = new BotConfig();
    private LmsConfig lms = new LmsConfig();
    private List<SemesterConfig> semesters;

    private static AppConfig instance;

    public static AppConfig load() {
        if (instance != null) return instance;
        try {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            InputStream is = AppConfig.class.getResourceAsStream("/application.yml");
            instance = mapper.readValue(is, AppConfig.class);

            // Override with environment variables if present
            String envToken = System.getenv("BOT_TOKEN");
            String envUsername = System.getenv("BOT_USERNAME");
            if (envToken != null && !envToken.isEmpty()) instance.bot.setToken(envToken);
            if (envUsername != null && !envUsername.isEmpty()) instance.bot.setUsername(envUsername);

            return instance;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load application.yml: " + e.getMessage(), e);
        }
    }

    public Map<Integer, String> getSemesterMap() {
        Map<Integer, String> map = new LinkedHashMap<>();
        if (semesters != null) {
            for (SemesterConfig s : semesters) {
                map.put(s.getId(), s.getName());
            }
        }
        return map;
    }

    @Data
    public static class BotConfig {
        private String token;
        private String username;
    }

    @Data
    public static class LmsConfig {
        @JsonProperty("base-url")
        private String baseUrl = "https://lms.tuit.uz";

        @JsonProperty("login-url")
        private String loginUrl = "https://lms.tuit.uz/auth/login";
    }

    @Data
    public static class SemesterConfig {
        private int id;
        private String name;
        private String emoji;
        private String year;
    }
}
