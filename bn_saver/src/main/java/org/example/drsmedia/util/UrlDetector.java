package org.example.drsmedia.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UrlDetector {
    private static final Pattern URL_PATTERN =
            Pattern.compile("(https?://\\S+)", Pattern.CASE_INSENSITIVE);

    public static String extractFirstUrl(String text) {
        if (text == null) return null;
        Matcher m = URL_PATTERN.matcher(text);
        if (m.find()) return m.group(1);
        return null;
    }
}