package com.perde.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

/** Sağ üstteki hap: canlı sıcaklık halkası + gerçek fan hızıyla dönen fan. */
final class StatusView extends View {
    private final Sensors s;
    private float shownCpu = -1;
    private float angle;
    private long last = SystemClock.uptimeMillis();
    private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ic = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private boolean running;

    StatusView(Context c, Sensors s) {
        super(c);
        this.s = s;
        pill.setColor(0x24FFFFFF);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(Ui.dp(4));
        track.setColor(0x33FFFFFF);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(Ui.dp(4));
        arc.setStrokeCap(Paint.Cap.ROUND);
        big.setColor(0xFFFFFFFF);
        big.setTextSize(Ui.sp(22));
        big.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        small.setColor(0xB3FFFFFF);
        small.setTextSize(Ui.sp(12));
        ic.setColor(0xFFFFFFFF);
    }

    void setRunning(boolean on) {
        running = on;
        last = SystemClock.uptimeMillis();
        if (on) invalidate();
    }

    @Override
    protected void onMeasure(int wm, int hm) {
        setMeasuredDimension(Ui.dp(300), Ui.dp(64));
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.1f, (now - last) / 1000f);
        last = now;
        float w = getWidth(), h = getHeight();
        r.set(0, 0, w, h);
        c.drawRoundRect(r, h / 2f, h / 2f, pill);

        float cpu = s.cpu;
        if (cpu > 0) shownCpu = shownCpu < 0 ? cpu : shownCpu + (cpu - shownCpu) * 0.08f;

        // sıcaklık halkası
        float gx = h / 2f + Ui.dp(4), gy = h / 2f, gr = Ui.dp(19);
        r.set(gx - gr, gy - gr, gx + gr, gy + gr);
        c.drawArc(r, 135, 270, false, track);
        if (shownCpu > 0) {
            float frac = Ui.clamp((shownCpu - 25f) / 70f, 0.03f, 1f);
            int col = Ui.tempColor(shownCpu);
            if (shownCpu >= 80) {
                float p = 0.5f + 0.5f * (float) Math.sin(now / 180.0);
                col = Ui.lerpColor(col, 0xFFFFFFFF, 0.3f * p);
            }
            arc.setColor(col);
            c.drawArc(r, 135, 270 * frac, false, arc);
        }
        Icons.thermo(c, gx, gy, Ui.dp(18), ic);

        float tx = gx + gr + Ui.dp(12);
        c.drawText(shownCpu > 0 ? Math.round(shownCpu) + "°C" : "—", tx, gy + Ui.sp(4), big);
        c.drawText("İşlemci", tx, gy + Ui.sp(20), small);

        // ayraç
        float sx = w * 0.53f;
        small.setAlpha(60);
        c.drawLine(sx, h * 0.25f, sx, h * 0.75f, small);
        small.setAlpha(179);

        // fan
        if (Theme.cur.motion) angle = (angle + s.fanDegPerSec() * dt) % 360f;
        float fx = sx + Ui.dp(32);
        Icons.fan(c, fx, gy, Ui.dp(30), ic, angle);
        String st = !s.fanKnown() ? "Otomatik" : (s.fanSpinning() ? "Dönüyor" : "Durdu");
        c.drawText("Fan", fx + Ui.dp(26), gy + Ui.sp(4), big);
        c.drawText(st, fx + Ui.dp(26), gy + Ui.sp(20), small);

        // yeniden çizim: ortak kare saati
    }
}
