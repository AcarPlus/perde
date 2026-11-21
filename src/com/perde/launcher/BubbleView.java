package com.perde.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/** Alttaki renkli, parlak yuvarlak kısayol. Odaklanınca büyür, çevresinde renkli halka döner, etiketi belirir. */
final class BubbleView extends View {
    static final int WIFI = 0, BT = 1, FAN = 2, SETTINGS = 3, PROJ = 4;

    final int kind;
    private final String label;
    private final Sensors sensors;
    private final int c1, c2;
    boolean wifiOn = true;
    String hint;
    private float f;
    private float angle;
    private long last = SystemClock.uptimeMillis();
    private final long t0 = last;
    private ValueAnimator fa;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ic = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Matrix m = new Matrix();
    private SweepGradient sweep;

    BubbleView(Context c, int kind, String label, int c1, int c2, Sensors sensors, OnClickListener click) {
        super(c);
        this.kind = kind;
        this.label = label;
        this.c1 = c1;
        this.c2 = c2;
        this.sensors = sensors;
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        setOnClickListener(click);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setShadowLayer(Ui.dp(4), 0, Ui.dp(1), 0x99000000);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dp(3));
        ring.setStrokeCap(Paint.Cap.ROUND);
        ic.setColor(0xFFFFFFFF);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float cx = w / 2f, cy = Ui.dp(46), rr = Ui.dp(40);
        fill.setShader(new LinearGradient(cx - rr, cy - rr, cx + rr, cy + rr, c1, c2, Shader.TileMode.CLAMP));
        gloss.setShader(new LinearGradient(0, cy - rr, 0, cy, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        int light = Ui.lerpColor(c1, 0xFFFFFFFF, 0.5f);
        sweep = new SweepGradient(cx, cy, new int[]{light, 0x00FFFFFF, c1, 0x00FFFFFF, light}, null);
        ring.setShader(sweep);
    }

    @Override
    protected void onFocusChanged(boolean gained, int dir, Rect prev) {
        super.onFocusChanged(gained, dir, prev);
        if (fa != null) fa.cancel();
        fa = ValueAnimator.ofFloat(f, gained ? 1f : 0f);
        fa.setDuration(gained ? 420 : 220);
        fa.setInterpolator(new OvershootInterpolator(gained ? 2.6f : 0f));
        fa.addUpdateListener(a -> { f = (float) a.getAnimatedValue(); invalidate(); });
        fa.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!Ui.on('k')) return;
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.1f, (now - last) / 1000f);
        last = now;
        float t = Theme.cur.motion ? (now - t0) / 1000f : 0f;
        float fc = Ui.clamp(f, 0f, 1f);
        float cx = getWidth() / 2f, cy = Ui.dp(46);
        float r0 = Ui.dp(31) + Ui.dp(8) * f;

        // renkli hale
        if (fc > 0.01f) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(t * 3.0);
            halo.setColor(Ui.withAlpha(c1, 0.28f * fc * (0.6f + 0.4f * pulse)));
            c.drawCircle(cx, cy, r0 + Ui.dp(10) + Ui.dp(4) * pulse, halo);
            m.setRotate((t * 160f) % 360f, cx, cy);
            sweep.setLocalMatrix(m);
            ring.setAlpha((int) (255 * fc));
            r.set(cx - r0 - Ui.dp(6), cy - r0 - Ui.dp(6), cx + r0 + Ui.dp(6), cy + r0 + Ui.dp(6));
            c.drawOval(r, ring);
        }

        // gövde: renk geçişi + parlak üst yansıma
        fill.setAlpha((int) (150 + 105 * fc));
        c.drawCircle(cx, cy, r0, fill);
        r.set(cx - r0 * 0.72f, cy - r0 * 0.92f, cx + r0 * 0.72f, cy - r0 * 0.05f);
        c.drawOval(r, gloss);

        float s = Ui.dp(30) + Ui.dp(5) * f;
        switch (kind) {
            case WIFI: Icons.wifi(c, cx, cy + Ui.dp(2), s, ic, wifiOn); break;
            case BT: Icons.bluetooth(c, cx, cy, s, ic); break;
            case PROJ: Icons.projector(c, cx, cy, s, ic); break;
            case SETTINGS:
                c.save();
                c.rotate(fc * ((t * 60f) % 360f), cx, cy);
                Icons.gear(c, cx, cy, s, ic);
                c.restore();
                break;
            case FAN:
                if (Theme.cur.motion) angle = (angle + sensors.fanDegPerSec() * dt) % 360f;
                Icons.fan(c, cx, cy, s, ic, angle);
                dot.setColor(Ui.tempColor(sensors.cpu));
                c.drawCircle(cx + r0 * 0.72f, cy - r0 * 0.72f, Ui.dp(7), dot);
                dot.setColor(0xFFFFFFFF);
                dot.setStyle(Paint.Style.STROKE);
                dot.setStrokeWidth(Ui.dp(2));
                c.drawCircle(cx + r0 * 0.72f, cy - r0 * 0.72f, Ui.dp(7), dot);
                dot.setStyle(Paint.Style.FILL);
                break;
        }

        if (fc < 0.02f) return; // etiket yalnız seçiliyken (gölgesi de leke bırakmasın)
        text.setShadowLayer(Ui.dp(4), 0, Ui.dp(1), Ui.withAlpha(0xFF000000, 0.6f * fc));
        text.setColor(Ui.withAlpha(0xFFFFFFFF, fc));
        text.setTextSize(Ui.sp(15));
        float ly = cy + r0 + Ui.dp(28) - Ui.dp(6) * (1 - fc);
        c.drawText(label, cx, ly, text);
        if (hint != null) {
            text.setTextSize(Ui.sp(11));
            text.setColor(Ui.withAlpha(0xFFFFFFFF, 0.65f * fc));
            c.drawText(hint, cx, ly + Ui.sp(17), text);
        }
        // sürekli animasyon ortak kare saatinden gelir
    }
}
