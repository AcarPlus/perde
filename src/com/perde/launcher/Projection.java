package com.perde.launcher;

import java.lang.reflect.Method;

/**
 * v3.15: projeksiyon yönü (ters/düz, aynalı). Üreticinin HTC ayarlarındaki "Projeksiyon modu" ile aynı yol:
 * Allwinner TvServer HIDL servisine factorySetPanelValue(E_AW_PANEL_CONFIG_MIRROR = 3, değer) çağrısı
 * (işlem kodu 127, okuma 128). Değer = panel aynalaması: 0 yok, 1 yatay, 2 dikey, 3 ikisi (180°).
 * HTC karşılıkları: 0 Rotate Front, 1 Rotate Rear, 2 Rotate Rear Ceiling, 3 Rotate Ceiling.
 * HwBinder/HwParcel gizli API olduğu için yansımayla çağrılır (Perde sistem kimliğiyle çalışıyor).
 */
final class Projection {
    static final int H = 1, V = 2;              // değerdeki bitler: yatay, dikey aynalama
    /** Düğmede dönme sırası: Düz → Ters (tavan) → Aynalı (arkadan) → Ters aynalı (tavan arkadan). */
    static final int[] CYCLE = {0, 3, 1, 2};
    private static final String IFACE = "vendor.aw.homlet.tvsystem.tvserver@1.0::ITvServer";
    private static final int CONFIG_MIRROR = 3, TX_SET = 127, TX_GET = 128;

    private Projection() { }

    static String name(int m) {
        switch (m) {
            case 0: return "Düz";
            case 3: return "Ters";
            case 1: return "Aynalı";
            case 2: return "Ters aynalı";
            default: return "?";
        }
    }

    static int next(int m) {
        for (int i = 0; i < CYCLE.length; i++) if (CYCLE[i] == m) return CYCLE[(i + 1) % CYCLE.length];
        return 0;
    }

    /** Şu anki yön (0..3); okunamazsa -1. */
    static int get() {
        try {
            int r = call(TX_GET, CONFIG_MIRROR, null);
            return r >= 0 && r <= 3 ? r : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Yönü ayarla; başarılıysa true. HTC ayarları gibi persist.sys.panelvalue'yu da yazar. */
    static boolean set(int m) {
        try {
            call(TX_SET, CONFIG_MIRROR, m);
            try {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                sp.getMethod("set", String.class, String.class).invoke(null, "persist.sys.panelvalue", String.valueOf(m));
            } catch (Throwable ignored) {
                // yalnız HTC ayarlarının gösterdiği değer; asıl ayar servise yazıldı
            }
            return get() == m;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object service;

    private static int call(int code, int a, Integer b) throws Exception {
        Class<?> hb = Class.forName("android.os.HwBinder");
        Class<?> ihb = Class.forName("android.os.IHwBinder");
        Class<?> hp = Class.forName("android.os.HwParcel");
        if (service == null) {
            Method gs = hb.getMethod("getService", String.class, String.class);
            service = gs.invoke(null, IFACE, "default");
        }
        Object req = hp.getConstructor().newInstance();
        Object rep = hp.getConstructor().newInstance();
        try {
            hp.getMethod("writeInterfaceToken", String.class).invoke(req, IFACE);
            Method w = hp.getMethod("writeInt32", int.class);
            w.invoke(req, a);
            if (b != null) w.invoke(req, b);
            try {
                ihb.getMethod("transact", int.class, hp, hp, int.class).invoke(service, code, req, rep, 0);
            } catch (java.lang.reflect.InvocationTargetException e) {
                service = null; // servis yeniden başlamış olabilir: bir sonraki çağrıda yeniden al
                throw e;
            }
            hp.getMethod("verifySuccess").invoke(rep);
            hp.getMethod("releaseTemporaryStorage").invoke(req);
            return (Integer) hp.getMethod("readInt32").invoke(rep);
        } finally {
            hp.getMethod("release").invoke(rep);
        }
    }
}
