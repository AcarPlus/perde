package com.perde.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

/** Büyük kadran: sıcaklık (CPU/GPU) ya da fan. */
final class GaugeView extends View {
    static final int CPU = 0, GPU = 1, FAN = 2;
    private final int kind;
    private final Sensors s;
    private float shown = -1, angle, intro;
    private long last = SystemClock.uptimeMillis();
    private final long born = SystemClock.uptimeMillis();
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ic = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    GaugeView(Context c, int kind, Sensors s) {
        super(c);
        this.kind = kind;
        this.s = s;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(Ui.dp(10));
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setColor(0x26FFFFFF);
        arc.set(track);
        big.setColor(0xFFFFFFFF);
        big.setTextAlign(Paint.Align.CENTER);
        big.setTextSize(Ui.sp(38));
        big.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        small.setColor(0xB3FFFFFF);
        small.setTextAlign(Paint.Align.CENTER);
        small.setTextSize(Ui.sp(16));
        ic.setColor(0xFFFFFFFF);
    }

    @Override
    protected void onMeasure(int wm, int hm) {
        setMeasuredDimension(Ui.dp(200), Ui.dp(220));
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.1f, (now - last) / 1000f);
        last = now;
        intro = Ui.clamp((now - born) / 900f, 0f, 1f);
        float ease = 1f - (1f - intro) * (1f - intro) * (1f - intro);
        float cx = getWidth() / 2f, cy = Ui.dp(96), rad = Ui.dp(78);
        r.set(cx - rad, cy - rad, cx + rad, cy + rad);
        c.drawArc(r, 135, 270, false, track);

        String label;
        if (kind == FAN) {
            float rate = s.fanRate;
            float frac = rate < 0 ? 0.5f : Ui.clamp(rate / 120f, 0.04f, 1f);
            arc.setColor(rate < 0 ? 0xFF60A5FA : (s.fanSpinning() ? 0xFF38BDF8 : 0xFFF43F5E));
            c.drawArc(r, 135, 270 * frac * ease, false, arc);
            angle = (angle + s.fanDegPerSec() * dt) % 360f;
            Icons.fan(c, cx, cy, Ui.dp(70), ic, angle);
            label = !s.fanKnown() ? "Fan · otomatik" : (s.fanSpinning() ? "Fan dönüyor" : "Fan durdu");
        } else {
            float v = kind == CPU ? s.cpu : s.gpu;
            if (v > 0) shown = shown < 0 ? v : shown + (v - shown) * 0.1f;
            if (shown > 0) {
                arc.setColor(Ui.tempColor(shown));
                c.drawArc(r, 135, 270 * Ui.clamp((shown - 25f) / 70f, 0.03f, 1f) * ease, false, arc);
            }
            c.drawText(shown > 0 ? Math.round(shown * ease + 25 * (1 - ease)) + "°" : "—", cx, cy + Ui.sp(14), big);
            label = kind == CPU ? "İşlemci" : "Grafik";
        }
        c.drawText(label, cx, cy + rad + Ui.dp(30), small);
    }
}
