package uz.tuit.lmsbot.util;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Критерии оценивания задания. LMS отдаёт их одним блоком, и склеенный text()
 * превращался в кашу вида «Критерий: Выполнение 3 балла Оформление 2 балла».
 * Здесь блок разбирается на пункты «Название — баллы»; в Activity.criteria они
 * хранятся строками через \n.
 */
public final class Criteria {

    public record Item(String name, String points) {}

    private static final String UNIT = "(?:балл(?:а|ов)?|ball(?:ar)?|балл|б\\.|pts|points?|очк(?:о|а|ов))";
    private static final Pattern PREFIX = Pattern.compile("(?iu)^\\s*(критерий|критерии|критерии оценивания|mezon|mezonlar|мезон|мезонлар|criteria|criterion)\\s*:?\\s*");
    /** Строка, в которой нет ничего, кроме заголовка «Критерии». */
    private static final Pattern HEADING_ONLY = Pattern.compile("(?iu)^(критерий|критерии|критерии оценивания|mezon|mezonlar|мезон|мезонлар|criteria|criterion)\\s*:?$");
    private static final Pattern BULLET = Pattern.compile("^\\s*(?:[-–—•·*▪◦]|\\d{1,2}[.)])\\s+");
    /** «Выполнение — 3 балла», «Выполнение (3)», «Выполнение: 3» */
    private static final Pattern TRAILING = Pattern.compile("(?iu)^(.*?\\S)[\\s:–—\\-(]*(\\d+(?:[.,]\\d+)?)\\s*" + UNIT + "?\\)?\\.?$");
    /** «3 балла — Выполнение» */
    private static final Pattern LEADING = Pattern.compile("(?iu)^(\\d+(?:[.,]\\d+)?)\\s*" + UNIT + "?\\s*[–—:\\-]\\s*(.+)$");
    /** Граница пунктов в сплошном тексте — сразу после «N баллов». */
    private static final Pattern AFTER_POINTS = Pattern.compile("(?iu)(?<=\\d\\s?" + UNIT + ")[\\s,.;]+(?=\\S)");

    private Criteria() {}

    /** Пункты критериев из блока LMS; пустой список, если критериев нет. */
    public static List<String> extract(Element block) {
        if (block == null) return List.of();
        Element el = block.clone();
        el.select("br").forEach(br -> br.replaceWith(new TextNode("\n")));

        List<String> parts = new ArrayList<>();
        // 1. Настоящие элементы списка
        Elements items = el.select("li, .sc-criteria-item, .criteria-item, tr, .badge, .label, p");
        for (Element it : items) {
            if (!it.select("li, .sc-criteria-item, .criteria-item, tr, p").isEmpty() && it.children().size() > 1) continue;
            String t = it.wholeText().trim();
            if (!t.isEmpty()) parts.add(t);
        }
        // 2. Переносы строк
        if (parts.size() < 2) {
            parts.clear();
            for (Element b : el.select("div, span, li, p")) b.appendChild(new TextNode("\n"));
            for (String line : el.wholeText().split("\n")) if (!line.isBlank()) parts.add(line);
        }
        // 3. Сплошной текст: разделители, затем граница после «N баллов»
        if (parts.size() < 2) {
            String flat = String.join(" ", parts).replaceAll("\\s+", " ").trim();
            flat = PREFIX.matcher(flat).replaceFirst("");
            parts.clear();
            String[] bySep = flat.split("\\s*(?:;|•|\\||·)\\s*");
            if (bySep.length > 1) parts.addAll(List.of(bySep));
            else parts.addAll(List.of(AFTER_POINTS.split(flat)));
        }

        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String line = format(p);
            if (line != null && !out.contains(line)) out.add(line);
        }
        return out;
    }

    /** Один пункт в виде «Название — N» (или просто «Название», если баллов нет). */
    static String format(String raw) {
        String s = raw.replaceAll("\\s+", " ").trim();
        s = PREFIX.matcher(s).replaceFirst("");
        s = BULLET.matcher(s).replaceFirst("");
        s = s.replaceAll("^[,.;:\\s]+|[,;:\\s]+$", "").trim();
        if (s.isEmpty() || HEADING_ONLY.matcher(s).matches()) return null;
        Item it = parse(s);
        return it.points() == null ? it.name() : it.name() + " — " + it.points();
    }

    /** Разбор готовой строки «Название — N» на название и баллы. */
    public static Item parse(String line) {
        String s = line.trim();
        Matcher lead = LEADING.matcher(s);
        if (lead.matches()) return new Item(capitalize(lead.group(2).trim()), lead.group(1).replace(',', '.'));
        Matcher tail = TRAILING.matcher(s);
        if (tail.matches() && !tail.group(1).isBlank()) {
            return new Item(capitalize(tail.group(1).replaceAll("[\\s:–—\\-(]+$", "").trim()), tail.group(2).replace(',', '.'));
        }
        return new Item(capitalize(s), null);
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
