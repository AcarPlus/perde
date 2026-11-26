package com.perde.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.Parcel;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v3.7: 4 köşe kayıtları (Oda, Bodrum, Yer 3, Yer 4) ve yere göre otomatik uygulama.
 * Her kayıtla birlikte o anki Wi-Fi "parmak izi" saklanır: görünen erişim noktaları (BSSID) ve sinyal güçleri.
 * Projeksiyon başka odaya taşınınca aynı modem görünse bile sinyal güçleri (komşu ağlar dahil) değişir;
 * Wi-Fi bağlanınca en yakın parmak izi seçilir ve yeterince belirginse o kaydın köşeleri uygulanır.
 */
final class KeystoneProfiles {
    static final String[] NAMES = {"Oda", "Bodrum", "Yer 3", "Yer 4"};
    static final int N = NAMES.length;
    private static final int FLOOR = -100;   // görünmeyen ağın varsayılan gücü (dBm)
    private static IBinder flinger;

    private KeystoneProfiles() { }

    private static SharedPreferences p(Context c) { return c.getSharedPreferences("kose", Context.MODE_PRIVATE); }

    static boolean autoOn(Context c) { return p(c).getBoolean("oto", true); }

    static void setAuto(Context c, boolean on) { p(c).edit().putBoolean("oto", on).apply(); }

    static boolean has(Context c, int slot) { return p(c).getString("v" + slot, null) != null; }

    static boolean any(Context c) {
        for (int i = 0; i < N; i++) if (has(c, i)) return true;
        return false;
    }

    /** Kayıttaki 8 değer; boşsa null. */
    static int[] values(Context c, int slot) {
        String s = p(c).getString("v" + slot, null);
        if (s == null) return null;
        String[] a = s.split(",");
        if (a.length != 8) return null;
        int[] r = new int[8];
        try {
            for (int i = 0; i < 8; i++) r[i] = Integer.parseInt(a[i].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return r;
    }

    /** Köşeleri ve o anki Wi-Fi parmak izini kaydeder. Parmak izi alınamadıysa false (köşeler yine kaydedilir). */
    static boolean save(Context c, int slot, int[] v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append(i == 0 ? "" : ",").append(v[i]);
        String fp = fingerprint(c);
        p(c).edit().putString("v" + slot, sb.toString()).putString("w" + slot, fp).apply();
        return !fp.isEmpty();
    }

    // ---------- Wi-Fi parmak izi ----------
    private static WifiManager wm(Context c) {
        return (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    static void startScan(Context c) {
        try {
            wm(c).startScan();
        } catch (Throwable ignored) {
        }
    }

    /** "@bağlı_bssid;bssid=dBm;…" (en güçlü 25 ağ). */
    static String fingerprint(Context c) {
        StringBuilder sb = new StringBuilder();
        try {
            WifiManager w = wm(c);
            WifiInfo info = w.getConnectionInfo();
            String cb = info != null ? info.getBSSID() : null;
            if (cb != null && !cb.startsWith("02:00:00") && !cb.equals("00:00:00:00:00:00")) sb.append('@').append(cb);
            List<ScanResult> rs = new ArrayList<>(w.getScanResults());
            Collections.sort(rs, (x, y) -> y.level - x.level);
            int n = 0;
            for (ScanResult r : rs) {
                if (r.BSSID == null) continue;
                if (n++ >= 25) break;
                sb.append(';').append(r.BSSID).append('=').append(r.level);
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private static Map<String, Integer> levels(String fp) {
        Map<String, Integer> m = new HashMap<>();
        if (fp == null) return m;
        for (String part : fp.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0 || part.startsWith("@")) continue;
            try {
                m.put(part.substring(0, eq), Integer.parseInt(part.substring(eq + 1)));
            } catch (NumberFormatException ignored) {
            }
        }
        return m;
    }

    private static String connected(String fp) {
        if (fp == null || !fp.startsWith("@")) return null;
        int semi = fp.indexOf(';');
        return semi < 0 ? fp.substring(1) : fp.substring(1, semi);
    }

    /** İki parmak izi arasındaki ortalama sinyal farkı (dBm); karşılaştırılacak ağ yoksa sonsuz. */
    private static double dist(Map<String, Integer> a, Map<String, Integer> b) {
        Set<String> keys = new HashSet<>(a.keySet());
        keys.addAll(b.keySet());
        double sum = 0;
        int n = 0;
        for (String k : keys) {
            int x = a.containsKey(k) ? a.get(k) : FLOOR, y = b.containsKey(k) ? b.get(k) : FLOOR;
            if (Math.max(x, y) < -85) continue; // ikisinde de çok zayıf: gürültü
            sum += Math.min(Math.abs(x - y), 30);
            n++;
        }
        return n < 2 ? Double.MAX_VALUE : sum / n;
    }

    /** Şu anki yere en uyan kayıt; belirgin bir eşleşme yoksa -1. */
    static int match(Context c) {
        String now = fingerprint(c);
        Map<String, Integer> cur = levels(now);
        if (cur.isEmpty()) return -1;
        String curB = connected(now);
        int best = -1;
        double bd = Double.MAX_VALUE, second = Double.MAX_VALUE;
        for (int i = 0; i < N; i++) {
            if (!has(c, i)) continue;
            String fp = p(c).getString("w" + i, "");
            double d = dist(cur, levels(fp));
            if (d == Double.MAX_VALUE) continue;
            if (curB != null && curB.equals(connected(fp))) d -= 2; // aynı erişim noktasına bağlı: küçük artı
            if (d < bd) {
                second = bd;
                bd = d;
                best = i;
            } else if (d < second) {
                second = d;
            }
        }
        if (best < 0 || bd > 12) return -1;                       // hiçbirine yeterince benzemiyor
        if (second != Double.MAX_VALUE && second - bd < 4) return -1; // iki kayıt arasında kararsız
        return best;
    }

    /** Wi-Fi bağlanınca çağrılır: yer tanınırsa o kaydın köşelerini uygular, uygulanan kaydın adını döndürür. */
    static String autoApply(Context c) {
        if (!autoOn(c) || !any(c)) return null;
        int slot = match(c);
        if (slot < 0) return null;
        int[] v = values(c, slot);
        if (v == null || java.util.Arrays.equals(v, KeystoneScreen.read())) return null;
        return push(v) ? NAMES[slot] : null;
    }

    /** SurfaceFlinger'a 1050 kodlu çağrı (üreticinin HTC ayarlarıyla aynı yol); değerler sistemde kalıcıdır. */
    static boolean push(int[] v) {
        try {
            if (flinger == null) {
                Class<?> sm = Class.forName("android.os.ServiceManager");
                Method g = sm.getMethod("getService", String.class);
                flinger = (IBinder) g.invoke(null, "SurfaceFlinger");
            }
            if (flinger == null) return false;
            Parcel p = Parcel.obtain();
            try {
                p.writeInterfaceToken("android.ui.ISurfaceComposer");
                for (int i = 0; i < 8; i++) p.writeFloat(v[i] * 0.001f);
                flinger.transact(1050, p, null, 0);
            } finally {
                p.recycle();
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
