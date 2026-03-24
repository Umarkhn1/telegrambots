package uz.tuit.lmsbot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Simple persistent CookieJar per Telegram userId.
 * Stores cookies as JSON on disk, so LMS session survives bot restarts.
 */
public class PersistentCookieJar implements CookieJar {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path file;

    // key: host
    private final Map<String, List<Cookie>> store = new HashMap<>();

    public PersistentCookieJar(Path sessionDir, long userId) {
        try {
            Files.createDirectories(sessionDir);
        } catch (Exception ignored) {}
        this.file = sessionDir.resolve("cookies_" + userId + ".json");
        load();
    }

    @Override
    public synchronized void saveFromResponse(@NotNull HttpUrl url, @NotNull List<Cookie> cookies) {
        if (cookies.isEmpty()) return;
        String host = url.host();
        List<Cookie> existing = new ArrayList<>(store.getOrDefault(host, Collections.emptyList()));

        // Replace by (name, domain, path)
        for (Cookie c : cookies) {
            existing.removeIf(x ->
                    Objects.equals(x.name(), c.name())
                            && Objects.equals(x.domain(), c.domain())
                            && Objects.equals(x.path(), c.path()));
            existing.add(c);
        }

        // Prune expired
        long now = System.currentTimeMillis();
        existing.removeIf(c -> c.expiresAt() <= now);

        store.put(host, existing);
        persist();
    }

    @NotNull
    @Override
    public synchronized List<Cookie> loadForRequest(@NotNull HttpUrl url) {
        String host = url.host();
        List<Cookie> cookies = new ArrayList<>(store.getOrDefault(host, Collections.emptyList()));

        long now = System.currentTimeMillis();
        cookies.removeIf(c -> c.expiresAt() <= now);
        store.put(host, cookies);
        // Don't persist every request; it's fine.
        return cookies;
    }

    public synchronized void clear() {
        store.clear();
        try {
            Files.deleteIfExists(file);
        } catch (Exception ignored) {}
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            Map<String, List<CookieDto>> raw = mapper.readValue(
                    Files.readAllBytes(file),
                    new TypeReference<>() {}
            );
            store.clear();
            long now = System.currentTimeMillis();
            for (Map.Entry<String, List<CookieDto>> e : raw.entrySet()) {
                List<Cookie> list = new ArrayList<>();
                if (e.getValue() == null) continue;
                for (CookieDto dto : e.getValue()) {
                    Cookie c = dto.toCookie();
                    if (c != null && c.expiresAt() > now) list.add(c);
                }
                if (!list.isEmpty()) store.put(e.getKey(), list);
            }
        } catch (Exception ignored) {
            // If corrupt, ignore and start fresh.
            store.clear();
        }
    }

    private void persist() {
        try {
            Map<String, List<CookieDto>> raw = new LinkedHashMap<>();
            for (Map.Entry<String, List<Cookie>> e : store.entrySet()) {
                List<CookieDto> list = new ArrayList<>();
                for (Cookie c : e.getValue()) list.add(CookieDto.from(c));
                raw.put(e.getKey(), list);
            }
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(raw);
            Files.write(file, bytes);
        } catch (Exception ignored) {}
    }

    public static class CookieDto {
        public String name;
        public String value;
        public String domain;
        public String path;
        public long expiresAt;
        public boolean secure;
        public boolean httpOnly;
        public boolean hostOnly;
        public boolean persistent;

        public static CookieDto from(Cookie c) {
            CookieDto dto = new CookieDto();
            dto.name = c.name();
            dto.value = c.value();
            dto.domain = c.domain();
            dto.path = c.path();
            dto.expiresAt = c.expiresAt();
            dto.secure = c.secure();
            dto.httpOnly = c.httpOnly();
            dto.hostOnly = c.hostOnly();
            dto.persistent = c.persistent();
            return dto;
        }

        public Cookie toCookie() {
            try {
                Cookie.Builder b = new Cookie.Builder()
                        .name(name)
                        .value(value)
                        .path(path != null ? path : "/");
                if (hostOnly) b.hostOnlyDomain(domain);
                else b.domain(domain);
                if (secure) b.secure();
                if (httpOnly) b.httpOnly();
                if (persistent) b.expiresAt(expiresAt);
                return b.build();
            } catch (Exception e) {
                return null;
            }
        }
    }
}

