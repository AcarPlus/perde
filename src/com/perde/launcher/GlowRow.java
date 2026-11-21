package com.perde.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.widget.LinearLayout;

/**
 * Panel satırı. Seçili panelin çevresine kendi renginde yumuşak bir parlama çizer
 * (gerçek gölge her karede yeniden hesaplandığı için çok pahalıydı; bu birkaç çizgiyle sahte parlama).
 */
final class GlowRow extends LinearLayout {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    GlowRow(Context c) {
        super(c);
        setWillNotDraw(false);
        p.setStyle(Paint.Style.STROKE);
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        View f = findFocus();
        if (f instanceof PanelView && f.getParent() == this && Ui.on('g')) {
            PanelView pv = (PanelView) f;
            float sx = f.getScaleX(), sy = f.getScaleY();
            float cx = f.getLeft() + f.getWidth() / 2f, cy = f.getTop() + f.getHeight() / 2f;
            float hw = f.getWidth() * sx / 2f, hh = f.getHeight() * sy / 2f;
            float rad = Ui.dp(28);
            for (int i = 0; i < 6; i++) {
                float grow = Ui.dp(4) + i * Ui.dp(5);
                p.setStrokeWidth(Ui.dp(6));
                p.setColor(Ui.withAlpha(pv.c1, 0.22f * (1f - i / 6f)));
                r.set(cx - hw - grow, cy - hh - grow + f.getTranslationY(), cx + hw + grow, cy + hh + grow + f.getTranslationY());
                c.drawRoundRect(r, rad + grow, rad + grow, p);
            }
        }
        super.dispatchDraw(c);
    }
}
