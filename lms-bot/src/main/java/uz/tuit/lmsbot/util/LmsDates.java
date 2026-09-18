package uz.tuit.lmsbot.util;

import uz.tuit.lmsbot.config.AppConfig;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Разбор дат LMS. Все даты сайта — ташкентское время без указания пояса. */
public final class LmsDates {

    private static final DateTimeFormatter DMY_HMS = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
    private static final DateTimeFormatter YMD_HMS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private LmsDates() {}

    /** Дедлайн активности: основной формат LMS "24-03-2026 23:59:59". -1, если не разобрать. */
    public static long parseDeadline(String deadline) {
        if (deadline == null || deadline.isBlank()) return -1;
        String s = deadline.trim();
        try { return toMillis(LocalDateTime.parse(s, DMY_HMS)); } catch (Exception ignored) {}
        try { return toMillis(LocalDateTime.parse(s)); } catch (Exception ignored) {}
        try { return toMillis(LocalDateTime.parse(s, YMD_HMS)); } catch (Exception e) { return -1; }
    }

    /** Начало пары из расписания: "2026-03-24T10:00:00". -1, если не разобрать. */
    public static long parseScheduleStart(String start) {
        if (start == null || start.isBlank()) return -1;
        String s = start.trim();
        try { return toMillis(LocalDateTime.parse(s)); } catch (Exception ignored) {}
        try { return toMillis(LocalDateTime.parse(s.replace(" ", "T"))); } catch (Exception ignored) {}
        try { return toMillis(LocalDateTime.parse(s, YMD_HMS)); } catch (Exception e) { return -1; }
    }

    private static long toMillis(LocalDateTime t) {
        return t.atZone(AppConfig.LMS_ZONE).toInstant().toEpochMilli();
    }
}
