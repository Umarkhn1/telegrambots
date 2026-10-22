package uz.tuit.lmsbot.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * PNG с QR-кодом: чёрное на белом, с полями — так его уверенно берёт сканер OneID и со скриншота.
 * PNG собирается вручную (8-битный серый): в alpine-образе без AWT ImageIO может не завестись.
 */
public final class QrImage {

    private QrImage() {}

    public static byte[] png(String text, int size) throws Exception {
        BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
                Map.of(EncodeHintType.MARGIN, 3, EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                        EncodeHintType.CHARACTER_SET, "UTF-8"));
        int w = m.getWidth(), h = m.getHeight();

        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(raw, new Deflater(Deflater.BEST_COMPRESSION))) {
            byte[] row = new byte[w + 1];   // первый байт строки — фильтр 0 (без фильтра)
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) row[x + 1] = (byte) (m.get(x, y) ? 0x00 : 0xFF);
                z.write(row);
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(ihdr);
        d.writeInt(w);
        d.writeInt(h);
        d.writeByte(8);   // бит на канал
        d.writeByte(0);   // оттенки серого
        d.writeByte(0);   // deflate
        d.writeByte(0);   // стандартные фильтры
        d.writeByte(0);   // без чересстрочности
        chunk(out, "IHDR", ihdr.toByteArray());
        chunk(out, "IDAT", raw.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        DataOutputStream d = new DataOutputStream(out);
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        d.writeInt(data.length);
        d.write(t);
        d.write(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        d.writeInt((int) crc.getValue());
    }
}
