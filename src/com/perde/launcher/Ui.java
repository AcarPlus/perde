package com.perde.launcher;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.widget.TextView;

final class Ui {
    static float density = 1f, scaled = 1f;
    /** false = bekleme modu: sürekli animasyonlar durur (ısı/işlemci tasarrufu). */
    static volatile boolean awake = true;

    /** Geçici ölçüm anahtarı: `setprop debug.perde.off <harfler>` ile efektler kapatılır. */
    static volatile String off = "";

    /** 30 fps bekçisinin kapattığı efektler (MainActivity.fpsSample). */
    static volatile String auto = "";

    static boolean on(char k) { return off.indexOf(k) < 0 && auto.indexOf(k) < 0; }

    static void readDebug() { off = prop("debug.perde.off", ""); }

    static String prop(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (String) sp.getMethod("get", String.class, String.class).invoke(null, key, def);
        } catch (Exception e) {
            return def;
        }
    }

    /** Gece modu (22:00–07:00): renkler ve hareket yumuşar. */
    static volatile boolean night;

    static boolean isNightHour() {
        int hr = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String force = prop("debug.perde.night", "");
        if (force.equals("1")) return true;
        if (force.equals("0")) return false;
        return hr >= 22 || hr < 7;
    }

    static void init(Context c) {
        density = c.getResources().getDisplayMetrics().density;
        scaled = c.getResources().getDisplayMetrics().scaledDensity;
    }

    static int dp(float v) { return Math.round(v * density); }

    static float sp(float v) { return v * scaled; }

    static TextView text(Context c, float sizeSp, int color, String family) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    static int lerpColor(int a, int b, float t) {
        t = clamp(t, 0f, 1f);
        int aa = (a >>> 24), ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24), br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    static int withAlpha(int c, float a) {
        return (c & 0x00FFFFFF) | (Math.round(255 * clamp(a, 0f, 1f)) << 24);
    }

    /** Sıcaklığa göre renk: yeşil → sarı → kırmızı. */
    static int tempColor(float t) {
        if (t < 0) return 0xFF9CA3AF;
        if (t < 60) return lerpColor(0xFF34D399, 0xFFA3E635, (t - 40) / 20f);
        if (t < 75) return lerpColor(0xFFFBBF24, 0xFFFB923C, (t - 60) / 15f);
        return lerpColor(0xFFFB923C, 0xFFF43F5E, (t - 75) / 15f);
    }
}
