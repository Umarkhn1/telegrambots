package uz.tuit.lmsbot.service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Состояние бота на диске: сессии LMS (sessions/), языки пользователей (settings/)
 * и список студентов (students.json) в ~/.tuit-lms-bot.
 *
 * Диск Render стирается при каждом деплое и пробуждении, поэтому всё это упаковывается
 * в zip и шифруется AES-GCM ключом из токена бота: в резервной копии лежат cookie
 * чужих сессий, и без токена их не прочитать. Сама копия хранится в Telegram (см. LmsBot).
 */
public class StateBackup {

    public static final String FILE_NAME = "lmsbot-state.bin";
    private static final byte[] MAGIC = "LMSST1".getBytes(StandardCharsets.US_ASCII);

    private final Path root;
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();
    /**
     * Кто удалил свои данные, а копия в Telegram ещё старая: при восстановлении их файлы
     * пропускаем, иначе стёртое вернулось бы из копии. Чистится после закачки новой копии.
     */
    private final java.util.Set<Long> erased = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.regex.Pattern USER_FILE = java.util.regex.Pattern.compile("[a-z]+_(\\d+)\\.json");

    public StateBackup(String botToken) {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) home = ".";
        this.root = Paths.get(home, ".tuit-lms-bot");
        try {
            this.key = MessageDigest.getInstance("SHA-256")
                    .digest(("lmsbot-state|" + botToken).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Файлы состояния: только наши json из sessions/, settings/ и students.json. */
    private List<Path> files() throws IOException {
        List<Path> out = new ArrayList<>();
        for (String dir : new String[]{"sessions", "settings"}) {
            Path d = root.resolve(dir);
            if (!Files.isDirectory(d)) continue;
            try (Stream<Path> s = Files.list(d)) {
                // creds_ — пароли старого «Автовхода»: в копию не берём, даже если файл ещё не стёрт.
                s.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .filter(p -> !p.getFileName().toString().startsWith("creds_"))
                        .sorted().forEach(out::add);
            }
        }
        Path students = root.resolve("students.json");
        if (Files.exists(students)) out.add(students);
        return out;
    }

    /**
     * Id пользователей, которых стоит проверить при старте: у кого на диске есть
     * cookie LMS либо токен OneID — по токену сессия поднимется, даже если cookie мертва.
     */
    public List<Long> sessionUsers() {
        java.util.LinkedHashSet<Long> out = new java.util.LinkedHashSet<>();
        try {
            for (Path p : files()) {
                String n = p.getFileName().toString();
                if (!n.endsWith(".json")) continue;
                for (String prefix : new String[]{"cookies_", "oneid_"}) {
                    if (!n.startsWith(prefix)) continue;
                    try { out.add(Long.parseLong(n.substring(prefix.length(), n.length() - 5))); }
                    catch (NumberFormatException ignored) {}
                }
            }
        } catch (IOException ignored) {}
        return new ArrayList<>(out);
    }

    /**
     * Отпечаток «важных» изменений: кто вошёл/вышел, языки, список студентов, токены OneID.
     * Сами cookie LMS меняются на каждом запросе, их содержимое по отпечатку не отслеживаем —
     * иначе копия перезаливалась бы каждые три минуты.
     */
    public String fingerprint() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Path p : files()) {
                String rel = root.relativize(p).toString();
                md.update(rel.getBytes(StandardCharsets.UTF_8));
                if (!p.getFileName().toString().startsWith("cookies_")) md.update(Files.readAllBytes(p));
            }
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return "";
        }
    }

    public byte[] pack() throws Exception {
        ByteArrayOutputStream zipped = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(zipped)) {
            for (Path p : files()) {
                zip.putNextEntry(new ZipEntry(root.relativize(p).toString().replace('\\', '/')));
                zip.write(Files.readAllBytes(p));
                zip.closeEntry();
            }
        }
        byte[] iv = new byte[12];
        random.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] enc = c.doFinal(zipped.toByteArray());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(MAGIC);
        out.write(iv);
        out.write(enc);
        return out.toByteArray();
    }

    /** Удаляет с диска все файлы пользователя (сессия, токен, роль, настройки). Возвращает их число. */
    public int erase(long userId) {
        erased.add(userId);
        int n = 0;
        try {
            for (Path p : files()) {
                if (userOf(p.getFileName().toString()) == userId && Files.deleteIfExists(p)) n++;
            }
        } catch (IOException ignored) {}
        return n;
    }

    public java.util.Set<Long> erasedSnapshot() {
        return java.util.Set.copyOf(erased);
    }

    /** Копия без этих пользователей уже закачана — отсеивать их больше незачем. */
    public void forgetErased(java.util.Set<Long> users) {
        erased.removeAll(users);
    }

    /** Id из имени вида cookies_123.json, иначе -1. */
    private static long userOf(String name) {
        java.util.regex.Matcher m = USER_FILE.matcher(name);
        if (!m.matches()) return -1;
        try { return Long.parseLong(m.group(1)); } catch (NumberFormatException e) { return -1; }
    }

    /** Раскладывает копию по диску. Возвращает число восстановленных файлов. */
    public int unpack(byte[] data) throws Exception {
        if (data.length < MAGIC.length + 12) throw new IOException("backup too short");
        for (int i = 0; i < MAGIC.length; i++) if (data[i] != MAGIC[i]) throw new IOException("not a state backup");
        byte[] iv = java.util.Arrays.copyOfRange(data, MAGIC.length, MAGIC.length + 12);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] zipped = c.doFinal(data, MAGIC.length + 12, data.length - MAGIC.length - 12);

        int n = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipped))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName();
                boolean allowed = name.equals("students.json")
                        || name.matches("(sessions|settings)/[A-Za-z0-9_.-]+\\.json");
                if (!allowed || e.isDirectory()) continue;
                String base = name.substring(name.lastIndexOf('/') + 1);
                if (base.startsWith("creds_") || erased.contains(userOf(base))) continue;
                Path target = root.resolve(name).normalize();
                if (!target.startsWith(root)) continue;
                Files.createDirectories(target.getParent());
                // Свежее на диске не затираем: копия может быть старше текущих данных.
                if (!Files.exists(target)) {
                    Files.write(target, zip.readAllBytes());
                    n++;
                }
            }
        }
        return n;
    }
}
