package com.perde.launcher;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;

import java.lang.reflect.Method;

/**
 * Görüntü ayarları: parlaklık, kontrast, renk doygunluğu, renk sıcaklığı, kırmızı/yeşil/mavi dengesi ve hazır
 * profiller. Hepsi tek bir renk matrisinde birleşir ve SurfaceFlinger'a (1015, "istemci renk matrisi") verilir.
 * Projeksiyonun kendi ışık/renk ayar yolları bu modelde yok (ışık kaynağı parlaklığı Android'e bağlı değil:
 * screen_brightness değişince /sys/class/backlight/tv hep 100 kalıyor) → parlaklık yalnız AZALTILABİLİR.
 * Yalnız sistem uygulaması çağırabilir. Yeniden başlatınca sıfırlanır → Perde açılışta yeniden uygular.
 */
final class ColorProfiles {
    // ---------- ayarlar ----------
    static final int BRIGHT = 0, CONTRAST = 1, SAT = 2, TEMP = 3, RED = 4, GREEN = 5, BLUE = 6, N = 7;
    static final String[] LABELS = {"Parlaklık", "Kontrast", "Renk doygunluğu", "Renk sıcaklığı", "Kırmızı", "Yeşil", "Mavi"};
    static final int[] MIN = {30, -50, -50, -10, -20, -20, -20};
    static final int[] MAX = {100, 50, 50, 10, 20, 20, 20};
    static final int[] STEP = {5, 5, 5, 1, 1, 1, 1};
    static final int[] DEF = {100, 0, 0, 0, 0, 0, 0};

    // ---------- hazır profiller (yukarıdaki ayarların değerleri) ----------
    static final String[] NAMES = {"Standart", "Sinema", "Sıcak", "Soğuk", "Canlı", "Göz dostu"};
    static final String[] HINTS = {"üreticinin renkleri", "hafif sıcak, film için", "belirgin sıcak ton",
            "mavimsi, ferah", "daha doygun ve canlı", "mavi ışık az, gece için"};
    private static final int[][] PRESET = {
            {100, 0, 0, 0, 0, 0, 0},
            {100, 5, 5, 3, 0, 0, 0},
            {100, 0, 0, 7, 0, 0, 0},
            {100, 0, 0, -6, 0, 0, 0},
            {100, 10, 30, 0, 0, 0, 0},
            {85, -5, -5, 10, 0, 0, 0}};
    static final int CUSTOM = -1;

    private static final float[] W = {0.2126f, 0.7152f, 0.0722f}; // parlaklık katsayıları (BT.709)
    private static IBinder flinger;

    static int[] load(Context c) {
        int[] v = DEF.clone();
        String s = c.getSharedPreferences("perde", Context.MODE_PRIVATE).getString("goruntu", null);
        if (s != null) {
            String[] p = s.split(",");
            for (int i = 0; i < N && i < p.length; i++) {
                try {
                    v[i] = Math.max(MIN[i], Math.min(MAX[i], Integer.parseInt(p[i].trim())));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return v;
    }

    static void save(Context c, int[] v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < N; i++) sb.append(i == 0 ? "" : ",").append(v[i]);
        c.getSharedPreferences("perde", Context.MODE_PRIVATE).edit().putString("goruntu", sb.toString()).apply();
    }

    static int[] preset(int i) { return PRESET[i].clone(); }

    /** Değerler bir hazır profile denk geliyorsa onun sırası, yoksa CUSTOM. */
    static int presetOf(int[] v) {
        for (int i = 0; i < PRESET.length; i++) if (java.util.Arrays.equals(PRESET[i], v)) return i;
        return CUSTOM;
    }

    /** Profil örneği rengi: beyazın o profilde neye dönüştüğü. */
    static int tint(int i) {
        float[] g = gains(PRESET[i]);
        float br = PRESET[i][BRIGHT] / 100f;
        return 0xFF000000 | (c255(g[0] * br) << 16) | (c255(g[1] * br) << 8) | c255(g[2] * br);
    }

    private static int c255(float x) { return Math.round(255 * Math.max(0f, Math.min(1f, x))); }

    /** Sıcaklık + R/G/B dengesi → kanal kazançları. */
    private static float[] gains(int[] v) {
        float t = v[TEMP], r = 1f, g = 1f, b = 1f;
        if (t > 0) { // sıcak: mavi ve biraz yeşil azalır
            g -= 0.012f * t;
            b -= 0.035f * t;
        } else if (t < 0) { // soğuk: kırmızı ve biraz yeşil azalır
            r += 0.030f * t;
            g += 0.008f * t;
        }
        r *= 1f + v[RED] * 0.01f;
        g *= 1f + v[GREEN] * 0.01f;
        b *= 1f + v[BLUE] * 0.01f;
        return new float[]{r, g, b};
    }

    /**
     * out = parlaklık × (kontrast × kazanç × doygunluk(x) + 0,5 × (1 − kontrast))
     * 4×4 sütun öncelikli: sütun = giriş kanalı, satır = çıkış; 4. sütun = öteleme.
     */
    static float[] matrix(int[] v) {
        float br = v[BRIGHT] / 100f;
        float c = 1f + v[CONTRAST] / 100f * 0.6f;
        float s = 1f + v[SAT] / 100f;
        float[] g = gains(v);
        float[] m = new float[16];
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float x;
                if (row == 3) x = col == 3 ? 1f : 0f;
                else if (col == 3) x = br * 0.5f * (1f - c);
                else x = br * c * g[row] * ((1f - s) * W[col] + (col == row ? s : 0f));
                m[col * 4 + row] = x;
            }
        }
        return m;
    }

    static boolean isDefault(int[] v) { return java.util.Arrays.equals(v, DEF); }

    /** SurfaceFlinger'a uygular; başarılıysa true. Varsayılan değerlerde matris tamamen kaldırılır. */
    static boolean apply(int[] v) {
        try {
            if (flinger == null) {
                Class<?> sm = Class.forName("android.os.ServiceManager");
                Method gs = sm.getMethod("getService", String.class);
                flinger = (IBinder) gs.invoke(null, "SurfaceFlinger");
            }
            if (flinger == null) return false;
            Parcel d = Parcel.obtain(), r = Parcel.obtain();
            try {
                d.writeInterfaceToken("android.ui.ISurfaceComposer");
                if (isDefault(v)) {
                    d.writeInt(0);
                } else {
                    d.writeInt(1);
                    for (float f : matrix(v)) d.writeFloat(f);
                }
                return flinger.transact(1015, d, r, 0);
            } finally {
                d.recycle();
                r.recycle();
            }
        } catch (Throwable t) {
            android.util.Log.w("Perde", "görüntü ayarı uygulanamadı", t);
            return false;
        }
    }
}
