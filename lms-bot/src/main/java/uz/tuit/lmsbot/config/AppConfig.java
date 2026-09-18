package uz.tuit.lmsbot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.InputStream;

@Data
public class AppConfig {

    /**
     * Часовой пояс LMS. Сайт отдаёт дедлайны и расписание в ташкентском времени
     * без указания пояса, поэтому переводить их надо именно в Asia/Tashkent,
     * а не в пояс сервера: на хостинге он обычно UTC, и всё съехало бы на 5 часов.
     */
    public static final java.time.ZoneId LMS_ZONE = java.time.ZoneId.of("Asia/Tashkent");

    /**
     * Администраторы — Telegram id через запятую в ADMIN_IDS. Им доступна панель со списком
     * студентов; по умолчанию — владелец бота.
     */
    private static final java.util.Set<Long> ADMINS = parseAdmins(System.getenv("ADMIN_IDS"));

    private static java.util.Set<Long> parseAdmins(String raw) {
        java.util.Set<Long> out = new java.util.LinkedHashSet<>();
        for (String p : (raw == null || raw.isBlank() ? "743813399,5221460399" : raw).split("[,\\s]+")) {
            try { out.add(Long.parseLong(p.trim())); } catch (NumberFormatException ignored) {}
        }
        if (out.isEmpty()) out.add(743813399L);
        return java.util.Collections.unmodifiableSet(out);
    }

    public static boolean isAdmin(long userId) { return ADMINS.contains(userId); }

    /** Первый администратор — в его чате хранится резервная копия списка студентов. */
    public static long primaryAdmin() { return ADMINS.iterator().next(); }

    private BotConfig bot = new BotConfig();
    private LmsConfig lms = new LmsConfig();

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
