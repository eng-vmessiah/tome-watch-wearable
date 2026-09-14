package br.vitor.tomewatch;

import android.graphics.Bitmap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.common.BitMatrix;

import java.util.EnumMap;
import java.util.Map;

/** Minimal QR generation via ZXing core (no camera code pulled in). */
public final class Qr {

    /** Large, quiet-zone-free QR; watch screen is tiny → scale to max square. */
    public static Bitmap encode(String content, int sizePx) {
        try {
            EnumMap<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 0);
            BitMatrix m = new QRCodeWriter()
                    .encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
            Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565);
            for (int y = 0; y < sizePx; y++) {
                for (int x = 0; x < sizePx; x++) {
                    bmp.setPixel(x, y, m.get(x, y) ? 0xFFE8E2D5 : 0xFF101010);
                }
            }
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    private Qr() {}
}
