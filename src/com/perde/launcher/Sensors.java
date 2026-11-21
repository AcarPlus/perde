package com.perde.launcher;

import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.FileReader;

/**
 * Sıcaklık ve fan durumunu yalnızca OKUR. Hiçbir dosyaya yazmaz.
 * Fan: üretici servisi debug_fantrl sayacını 5 sn'de bir sıfırlıyor; iki okuma arasındaki artış = fan darbeleri/sn.
 */
final class Sensors {
    static final String CPU = "/sys/class/thermal/thermal_zone0/temp";
    static final String GPU = "/sys/class/thermal/thermal_zone1/temp";
    static final String FAN = "/sys/devices/platform/fan_ctrl/debug_fantrl";

    volatile float cpu = -1, gpu = -1;
    /** Darbe/sn; -1 = okunamıyor. */
    volatile float fanRate = -1;
    private volatile boolean running;
    private Thread thread;

    void start() {
        if (running) return;
        running = true;
        thread = new Thread(() -> {
            int prev = -1;
            long prevT = 0;
            while (running) {
                int c = readInt(CPU), g = readInt(GPU);
                if (c > 0) cpu = c / 1000f;
                if (g > 0) gpu = g / 1000f;
                int v = readInt(FAN);
                long now = SystemClock.elapsedRealtime();
                if (v >= 0) {
                    if (prev >= 0 && v >= prev && now > prevT) {
                        float r = (v - prev) * 1000f / (now - prevT);
                        fanRate = fanRate < 0 ? r : fanRate * 0.6f + r * 0.4f;
                    }
                    prev = v;
                    prevT = now;
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "perde-sensors");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    boolean fanKnown() { return fanRate >= 0; }

    boolean fanSpinning() { return fanRate > 0.5f; }

    /** Fan ikonunun dönüş hızı (derece/sn). */
    float fanDegPerSec() {
        if (fanRate < 0) return 120f;
        if (fanRate <= 0.5f) return 0f;
        return Ui.clamp(120f + fanRate * 5f, 120f, 900f);
    }

    static int readInt(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String s = r.readLine();
            return s == null ? -1 : Integer.parseInt(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }
}
