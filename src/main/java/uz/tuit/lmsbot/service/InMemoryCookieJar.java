package uz.tuit.lmsbot.service;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InMemoryCookieJar implements CookieJar {

    private final Map<String, List<Cookie>> store = new HashMap<>();

    @Override
    public void saveFromResponse(@NotNull HttpUrl url, @NotNull List<Cookie> cookies) {
        store.put(url.host(), new ArrayList<>(cookies));
    }

    @NotNull
    @Override
    public List<Cookie> loadForRequest(@NotNull HttpUrl url) {
        List<Cookie> cookies = store.get(url.host());
        return cookies != null ? cookies : new ArrayList<>();
    }

    public void clear() {
        store.clear();
    }
}
