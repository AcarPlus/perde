package com.perde.launcher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * Uyku zamanlayıcısı. Süre dolunca projeksiyonu uyku moduna alır (cihaz yöneticisi lockNow; üreticinin kapatma
 * penceresindeki "Uyku" ile aynı). Bir dakika önce ekranda uyarı çıkar. Android 11 arka plandan pencere açmayı
 * engellediği için üreticinin kapatma penceresi yalnızca yedek yol.
 */
public class SleepReceiver extends BroadcastReceiver {
    static final String SLEEP = "com.perde.launcher.UYKU", WARN = "com.perde.launcher.UYKU_UYARI";
    static final String CLOSE_PKG = "com.htc.closedialog";

    @Override
    public void onReceive(Context c, Intent i) {
        if (WARN.equals(i.getAction())) {
            Toast.makeText(c, "Uyku zamanlayıcısı: 1 dakika sonra projeksiyon kapanacak", Toast.LENGTH_LONG).show();
            return;
        }
        if (!SLEEP.equals(i.getAction())) return;
        c.getSharedPreferences("perde", Context.MODE_PRIVATE).edit().remove("uyku_bitis").apply();
        // v3.0: Perde sistem uygulaması → güç tuşuna kısa basmakla aynı uyku (PowerManager.goToSleep, gizli çağrı)
        try {
            android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
            pm.getClass().getMethod("goToSleep", long.class).invoke(pm, android.os.SystemClock.uptimeMillis());
            return;
        } catch (Throwable ignored) {
        }
        // Asıl yol: cihaz yöneticisi ile ekranı uyut (arka planda da çalışır).
        if (AdminReceiver.sleepNow(c)) return;
        // Yetki yoksa: üreticinin kapatma penceresini dene (yalnız Perde ön plandayken açılabilir).
        try {
            c.startActivity(new Intent().setComponent(new ComponentName(CLOSE_PKG, CLOSE_PKG + ".MainActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(c, "Uyku zamanlayıcısı: uyutma yetkisi yok", Toast.LENGTH_LONG).show();
        }
    }

    private static PendingIntent pi(Context c, String action, int code) {
        return PendingIntent.getBroadcast(c, code, new Intent(c, SleepReceiver.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** minutes <= 0 → iptal. */
    static void set(Context c, int minutes) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pi(c, SLEEP, 1));
        am.cancel(pi(c, WARN, 2));
        if (minutes <= 0) {
            c.getSharedPreferences("perde", Context.MODE_PRIVATE).edit().remove("uyku_bitis").apply();
            return;
        }
        long ms = minutes * 60_000L;
        String test = Ui.prop("debug.perde.uykutest", ""); // ölçüm/test için: saniye cinsinden süre
        if (!test.isEmpty()) {
            try { ms = Long.parseLong(test) * 1000L; } catch (NumberFormatException ignored) { }
        }
        long end = System.currentTimeMillis() + ms;
        c.getSharedPreferences("perde", Context.MODE_PRIVATE).edit().putLong("uyku_bitis", end).apply();
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pi(c, SLEEP, 1));
        if (ms > 90_000L) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end - 60_000L, pi(c, WARN, 2));
    }

    /** Kalan dakika; yoksa -1. */
    static long remainingMin(Context c) {
        long end = c.getSharedPreferences("perde", Context.MODE_PRIVATE).getLong("uyku_bitis", 0);
        long left = end - System.currentTimeMillis();
        if (end == 0 || left <= 0) return -1;
        return (left + 59_999L) / 60_000L;
    }
}
