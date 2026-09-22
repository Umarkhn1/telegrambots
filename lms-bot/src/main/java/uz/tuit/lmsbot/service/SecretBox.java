package uz.tuit.lmsbot.service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * AES-GCM над небольшими файлами состояния: сессии и учётные данные пользователей
 * лежат на диске Render и уезжают в резервную копию, поэтому в открытом виде их
 * держать нельзя.
 *
 * Ключ выводится из STATE_KEY, а если переменная не задана — из токена бота.
 * Каждое назначение (purpose) даёт свой ключ, чтобы файл одного вида нельзя было
 * подсунуть вместо другого.
 */
public final class SecretBox {

    private static final int IV_LEN = 12;

    private final byte[] key;
    private final byte[] magic;
    private final SecureRandom random = new SecureRandom();

    public SecretBox(String purpose, String botToken, String magic) {
        String secret = System.getenv("STATE_KEY");
        if (secret == null || secret.isBlank()) secret = botToken;
        this.magic = magic.getBytes(StandardCharsets.US_ASCII);
        try {
            this.key = MessageDigest.getInstance("SHA-256")
                    .digest((purpose + "|" + secret).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] seal(byte[] plain) throws Exception {
        byte[] iv = new byte[IV_LEN];
        random.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] enc = c.doFinal(plain);
        byte[] out = new byte[magic.length + IV_LEN + enc.length];
        System.arraycopy(magic, 0, out, 0, magic.length);
        System.arraycopy(iv, 0, out, magic.length, IV_LEN);
        System.arraycopy(enc, 0, out, magic.length + IV_LEN, enc.length);
        return out;
    }

    public byte[] open(byte[] data) throws Exception {
        if (data.length < magic.length + IV_LEN) throw new IllegalArgumentException("too short");
        for (int i = 0; i < magic.length; i++) {
            if (data[i] != magic[i]) throw new IllegalArgumentException("bad magic");
        }
        byte[] iv = java.util.Arrays.copyOfRange(data, magic.length, magic.length + IV_LEN);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        return c.doFinal(data, magic.length + IV_LEN, data.length - magic.length - IV_LEN);
    }
}
