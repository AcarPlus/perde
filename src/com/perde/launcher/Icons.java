package com.perde.launcher;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Basit, çizimle üretilmiş ikonlar. s = ikon kutusunun kenar uzunluğu. */
final class Icons {
    private static final RectF R = new RectF();
    private static final Path P = new Path();

    private static void stroke(Paint p, float w) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    static void wifi(Canvas c, float cx, float cy, float s, Paint p, boolean on) {
        stroke(p, s * 0.09f);
        float by = cy + s * 0.30f;
        for (int i = 1; i <= 3; i++) {
            float r = s * 0.17f * i;
            R.set(cx - r, by - r, cx + r, by + r);
            c.drawArc(R, 225, 90, false, p);
        }
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, by, s * 0.07f, p);
        if (!on) {
            stroke(p, s * 0.08f);
            c.drawLine(cx - s * 0.42f, cy - s * 0.42f, cx + s * 0.42f, cy + s * 0.42f, p);
        }
    }

    // Birim (s=1, merkez 0,0) şekiller: bir kez kurulur, ölçekle çizilir; GPU her karede yeniden üçgenlemez.
    private static final Path BT_U = new Path(), BLADE_U = new Path(), HDMI_U = new Path();

    static {
        BT_U.moveTo(-0.2f, -0.2f);
        BT_U.lineTo(0.2f, 0.2f);
        BT_U.lineTo(0f, 0.44f);
        BT_U.lineTo(0f, -0.44f);
        BT_U.lineTo(0.2f, -0.2f);
        BT_U.lineTo(-0.2f, 0.2f);

        BLADE_U.moveTo(0, 0);
        BLADE_U.cubicTo(-0.20f, -0.10f, -0.22f, -0.44f, 0.02f, -0.46f);
        BLADE_U.cubicTo(0.16f, -0.46f, 0.14f, -0.22f, 0, 0);
        BLADE_U.close();

        HDMI_U.moveTo(-0.42f, -0.16f);
        HDMI_U.lineTo(0.42f, -0.16f);
        HDMI_U.lineTo(0.42f, 0.02f);
        HDMI_U.lineTo(0.30f, 0.18f);
        HDMI_U.lineTo(-0.30f, 0.18f);
        HDMI_U.lineTo(-0.42f, 0.02f);
        HDMI_U.close();
    }

    private static void unit(Canvas c, Path u, float cx, float cy, float s, Paint p) {
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        c.drawPath(u, p);
        c.restore();
    }

    static void bluetooth(Canvas c, float cx, float cy, float s, Paint p) {
        stroke(p, 0.085f);
        unit(c, BT_U, cx, cy, s, p);
    }

    static void gear(Canvas c, float cx, float cy, float s, Paint p) {
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 8; i++) {
            c.save();
            c.rotate(i * 45f, cx, cy);
            R.set(cx - s * 0.085f, cy - s * 0.46f, cx + s * 0.085f, cy - s * 0.26f);
            c.drawRoundRect(R, s * 0.03f, s * 0.03f, p);
            c.restore();
        }
        stroke(p, s * 0.12f);
        c.drawCircle(cx, cy, s * 0.27f, p);
        stroke(p, s * 0.07f);
        c.drawCircle(cx, cy, s * 0.09f, p);
    }

    static void fan(Canvas c, float cx, float cy, float s, Paint p, float angle) {
        p.setStyle(Paint.Style.FILL);
        c.save();
        c.rotate(angle, cx, cy);
        for (int i = 0; i < 3; i++) {
            c.save();
            c.rotate(i * 120f, cx, cy);
            unit(c, BLADE_U, cx, cy, s, p);
            c.restore();
        }
        c.restore();
        c.drawCircle(cx, cy, s * 0.09f, p);
    }

    static void hdmi(Canvas c, float cx, float cy, float s, Paint p) {
        stroke(p, 0.07f);
        unit(c, HDMI_U, cx, cy, s, p);
        p.setStyle(Paint.Style.FILL);
        for (int i = -3; i <= 3; i++) {
            float x = cx + i * s * 0.085f;
            R.set(x - s * 0.022f, cy - s * 0.06f, x + s * 0.022f, cy + s * 0.04f);
            c.drawRect(R, p);
        }
    }

    /** USB bellek: yuvarlak gövde + metal uç (iki delikli) + gövdede küçük ışık. */
    static void usb(Canvas c, float cx, float cy, float s, Paint p) {
        float bw = s * 0.42f, bh = s * 0.50f, top = cy - s * 0.10f;
        // metal uç
        stroke(p, s * 0.06f);
        R.set(cx - bw * 0.36f, cy - s * 0.46f, cx + bw * 0.36f, top);
        c.drawRoundRect(R, s * 0.03f, s * 0.03f, p);
        p.setStyle(Paint.Style.FILL);
        float hy = cy - s * 0.33f;
        R.set(cx - bw * 0.20f, hy - s * 0.035f, cx - bw * 0.04f, hy + s * 0.035f);
        c.drawRect(R, p);
        R.set(cx + bw * 0.04f, hy - s * 0.035f, cx + bw * 0.20f, hy + s * 0.035f);
        c.drawRect(R, p);
        // gövde (HDMI simgesi gibi çizgi), içinde küçük ışık
        stroke(p, s * 0.07f);
        R.set(cx - bw / 2f, top, cx + bw / 2f, top + bh);
        c.drawRoundRect(R, s * 0.09f, s * 0.09f, p);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, top + bh * 0.62f, s * 0.05f, p);
    }

    /** YouTube tarzı yuvarlatılmış kutu içinde oynat üçgeni (filigran). */
    static void playBox(Canvas c, float cx, float cy, float s, Paint p) {
        stroke(p, s * 0.06f);
        R.set(cx - s * 0.46f, cy - s * 0.32f, cx + s * 0.46f, cy + s * 0.32f);
        c.drawRoundRect(R, s * 0.16f, s * 0.16f, p);
        p.setStyle(Paint.Style.FILL);
        P.reset();
        P.moveTo(cx - s * 0.10f, cy - s * 0.15f);
        P.lineTo(cx + s * 0.17f, cy);
        P.lineTo(cx - s * 0.10f, cy + s * 0.15f);
        P.close();
        c.drawPath(P, p);
    }

    /** Elmas içinde oynat üçgeni (filigran). */
    static void diamond(Canvas c, float cx, float cy, float s, Paint p) {
        stroke(p, s * 0.06f);
        P.reset();
        P.moveTo(cx, cy - s * 0.46f);
        P.lineTo(cx + s * 0.46f, cy);
        P.lineTo(cx, cy + s * 0.46f);
        P.lineTo(cx - s * 0.46f, cy);
        P.close();
        c.drawPath(P, p);
        p.setStyle(Paint.Style.FILL);
        P.reset();
        P.moveTo(cx - s * 0.08f, cy - s * 0.14f);
        P.lineTo(cx + s * 0.16f, cy);
        P.lineTo(cx - s * 0.08f, cy + s * 0.14f);
        P.close();
        c.drawPath(P, p);
    }

    /** Projeksiyon cihazı: gövde, mercek, havalandırma çizgileri, ayaklar. */
    static void projector(Canvas c, float cx, float cy, float s, Paint p) {
        stroke(p, s * 0.07f);
        R.set(cx - s * 0.44f, cy - s * 0.20f, cx + s * 0.44f, cy + s * 0.22f);
        c.drawRoundRect(R, s * 0.10f, s * 0.10f, p);
        c.drawCircle(cx + s * 0.20f, cy + s * 0.01f, s * 0.12f, p);
        c.drawLine(cx - s * 0.30f, cy - s * 0.05f, cx - s * 0.08f, cy - s * 0.05f, p);
        c.drawLine(cx - s * 0.30f, cy + s * 0.07f, cx - s * 0.08f, cy + s * 0.07f, p);
        c.drawLine(cx - s * 0.30f, cy + s * 0.22f, cx - s * 0.30f, cy + s * 0.32f, p);
        c.drawLine(cx + s * 0.30f, cy + s * 0.22f, cx + s * 0.30f, cy + s * 0.32f, p);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(cx + s * 0.20f, cy + s * 0.01f, s * 0.045f, p);
    }

    static void thermo(Canvas c, float cx, float cy, float s, Paint p) {
        stroke(p, s * 0.08f);
        R.set(cx - s * 0.11f, cy - s * 0.44f, cx + s * 0.11f, cy + s * 0.12f);
        c.drawRoundRect(R, s * 0.11f, s * 0.11f, p);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy + s * 0.26f, s * 0.18f, p);
    }
}
