package com.perde.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Uygulamalar ızgarasındaki tek kutucuk: varsa TV afişi, yoksa ikon. */
final class AppTile extends View {
    static final int W = 196, H = 150, ART_H = 110;
    private final Drawable banner, icon;
    private final String label;
    private float f;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF art = new RectF();
    private final Path clip = new Path();

    AppTile(Context c, String label, Drawable banner, Drawable icon, OnClickListener click) {
        super(c);
        this.label = label;
        this.banner = banner;
        this.icon = icon;
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        setOnClickListener(click);
        bg.setColor(0xFF1F2937);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Ui.dp(3));
        border.setColor(0xFFFFFFFF);
        text.setColor(0xFFFFFFFF);
        text.setTextSize(Ui.sp(14));
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    @Override
    protected void onMeasure(int wm, int hm) {
        setMeasuredDimension(Ui.dp(W), Ui.dp(H));
    }

    @Override
    protected void onFocusChanged(boolean gained, int dir, Rect prev) {
        super.onFocusChanged(gained, dir, prev);
        animate().scaleX(gained ? 1.08f : 1f).scaleY(gained ? 1.08f : 1f).translationZ(gained ? Ui.dp(10) : 0)
                .setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
        f = gained ? 1f : 0f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), r = Ui.dp(14);
        art.set(0, 0, w, Ui.dp(ART_H));
        clip.reset();
        clip.addRoundRect(art, r, r, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        c.drawRect(art, bg);
        if (banner != null) {
            banner.setBounds(0, 0, (int) w, Ui.dp(ART_H));
            banner.draw(c);
        } else if (icon != null) {
            int s = Ui.dp(64);
            int l = (int) (w - s) / 2, t = (Ui.dp(ART_H) - s) / 2;
            icon.setBounds(l, t, l + s, t + s);
            icon.draw(c);
        }
        if (f < 1f) c.drawColor(0x33000000);
        c.restore();
        if (f > 0f) {
            float in = Ui.dp(1.5f);
            c.drawRoundRect(in, in, w - in, Ui.dp(ART_H) - in, r - in, r - in, border);
        }
        text.setAlpha(f > 0 ? 255 : 190);
        CharSequence t = TextUtils.ellipsize(label, text, w - Ui.dp(8), TextUtils.TruncateAt.END);
        c.drawText(t, 0, t.length(), w / 2f, Ui.dp(ART_H) + Ui.dp(26), text);
    }
}
