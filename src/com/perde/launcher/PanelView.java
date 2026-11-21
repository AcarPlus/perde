package com.perde.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;

/**
 * Büyük ana panel. Her panelin kendi canlı sahnesi var (YouTube: kayan video kareleri, Stremio: dönen elmaslar,
 * HDMI: akan sinyal dalgaları). Odaklanınca genişler, kendi renginde parlar, çevresinde renkli çerçeve döner.
 */
final class PanelView extends View {
    interface FocusListener { void onPanelFocused(PanelView p); }

    final String title;
    String sub;
    final int c1, c2;
    private final int mark;
    private final Drawable icon; // null ise HDMI çizilir
    private float f;             // 0..1 odak
    private float sheen = -1f;   // -1 = yok
    private float rip = -1f;     // tıklama dalgası
    private ValueAnimator fa, sa, ra;
    private final long t0 = SystemClock.uptimeMillis();
    private FocusListener listener;

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrim = new Paint();
    private final Paint sheenP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titleP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fx = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fxs = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF(), r2 = new RectF();
    private final Path tri = new Path(), starP = new Path();
    private final float[] wavePts = new float[48 * 4];
    private final Matrix m = new Matrix();
    private SweepGradient sweep;
    private final float radius = Ui.dp(28);

    // Stremio parıltıları için sabit konumlar
    private static final float[][] SPARKS = {
            {0.12f, 0.14f}, {0.34f, 0.08f}, {0.52f, 0.22f}, {0.88f, 0.10f},
            {0.93f, 0.55f}, {0.45f, 0.48f}, {0.20f, 0.36f}, {0.70f, 0.70f}};

    PanelView(Context c, int mark, String title, String sub, int c1, int c2, Drawable icon, OnClickListener click) {
        super(c);
        this.mark = mark;
        this.title = title;
        this.sub = sub;
        this.c1 = c1;
        this.c2 = c2;
        this.icon = icon == null ? null : icon.mutate();
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        setOnClickListener(click);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Ui.dp(4));
        inner.setStyle(Paint.Style.STROKE);
        inner.setStrokeWidth(Ui.dp(1.5f));
        inner.setColor(0x66FFFFFF);
        titleP.setColor(Color.WHITE);
        titleP.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        // birim üçgen ve yıldız: bir kez kurulur, ölçek/öteleme ile çizilir (GPU önbelleğinde kalır)
        tri.moveTo(-0.7f, -1f);
        tri.lineTo(1f, 0f);
        tri.lineTo(-0.7f, 1f);
        tri.close();
        starP.moveTo(0, -1f);
        starP.quadTo(0, 0, 1f, 0);
        starP.quadTo(0, 0, 0, 1f);
        starP.quadTo(0, 0, -1f, 0);
        starP.quadTo(0, 0, 0, -1f);
        starP.close();
        subP.setColor(0xE6FFFFFF);
        subP.setTextSize(Ui.sp(15));
        subP.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        subTp.set(subP);
        glyph.setColor(Color.WHITE);
        fxs.setStyle(Paint.Style.STROKE);
        fxs.setStrokeCap(Paint.Cap.ROUND);
        fxs.setStrokeJoin(Paint.Join.ROUND);
        // v2.4: köşe kırpma (clipToOutline) yok. Ekran kartında her karede kırpma maskesi demekti (~8 fps kaybı);
        // yuvarlak köşeler artık doğrudan yuvarlak dikdörtgen çizimleriyle veriliyor.
        rippleP.setShader(new android.graphics.RadialGradient(0, 0, 1000f,
                new int[]{0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF}, new float[]{0f, 0.985f, 1f}, Shader.TileMode.CLAMP));
        setLayerType(LAYER_TYPE_HARDWARE, null); // başlangıçta seçili değil: donanım katmanında önbellekli
    }

    void setListener(FocusListener l) { listener = l; }

    // ---------- kaldığın yerden devam ----------
    private android.graphics.Bitmap art;
    private String resumeLine;
    private float progress = -1f;
    private final Paint artP = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rippleP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix artM = new Matrix(), ripM = new Matrix();

    /** Panel arka planında izlenen içeriğin kapağı, altında "Devam: başlık" ve ilerleme çubuğu. */
    void setResume(android.graphics.Bitmap a, String line, float prog) {
        art = a;
        artP.setShader(a == null ? null : new android.graphics.BitmapShader(a, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        resumeLine = line;
        progress = prog;
        invalidate();
    }

    boolean hasResume() { return resumeLine != null; }

    private void drawArt(Canvas c, float w, float h) {
        // ortala-kırp (centerCrop), yuvarlak köşeli dikdörtgene doku olarak
        float bw = art.getWidth(), bh = art.getHeight();
        float scale = Math.max(w / bw, h / bh);
        artM.setScale(scale, scale);
        artM.postTranslate((w - bw * scale) / 2f, (h - bh * scale) / 2f);
        artP.getShader().setLocalMatrix(artM);
        c.drawRoundRect(rect, radius, radius, artP);
        tint.setColor(Ui.withAlpha(c2, 0.35f)); // panel renginde hafif ton
        c.drawRoundRect(rect, radius, radius, tint);
    }

    void setSub(String s) {
        sub = s;
        invalidate();
    }

    /** Tıklama dalgası. */
    void ripple() {
        if (ra != null) ra.cancel();
        ra = ValueAnimator.ofFloat(0f, 1f);
        ra.setDuration(520);
        ra.setInterpolator(new DecelerateInterpolator(1.6f));
        ra.addUpdateListener(a -> { rip = (float) a.getAnimatedValue(); invalidate(); });
        ra.start();
    }

    // ---------- önceden hazırlanan dokular (her karede yeniden çizilmez) ----------
    private android.graphics.BitmapShader gridShader;
    private final Paint gridP = new Paint(Paint.FILTER_BITMAP_FLAG);
    private float gridW, gridH;
    private android.graphics.Bitmap iconBmp;
    private final Paint iconP = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private LinearGradient sheenShader;
    private final Matrix sheenM = new Matrix();

    /**
     * YouTube sahnesinin "video kareleri" ızgarası: tekrar eden tek bir döşeme dokusu. Eskiden her karede ~60 yuvarlak
     * dikdörtgen + üçgen çiziliyordu; şimdi panel tek bir dokulu dikdörtgenle dolduruluyor (kayma = doku kaydırma).
     */
    private void buildGrid() {
        float tw = Ui.dp(118), th = Ui.dp(66), gap = Ui.dp(14);
        float sx = tw + gap, sy = th + gap;
        int W = Math.round(sx * 3), H = Math.round(sy * 2);
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(W, H, android.graphics.Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 3; col++) {
                float x = col * sx + (row % 2) * sx * 0.5f, y = row * sy + gap / 2f;
                boolean hl = (row == 0 && col == 0) || (row == 1 && col == 2);
                for (int dx = -1; dx <= 1; dx++) { // döşeme kenarından taşan kareler karşı kenarda devam eder
                    float xx = x + dx * W;
                    p.setColor(Ui.withAlpha(0xFFFFFFFF, hl ? 0.13f : 0.06f));
                    r2.set(xx, y, xx + tw, y + th);
                    c.drawRoundRect(r2, Ui.dp(10), Ui.dp(10), p);
                    if (hl) {
                        p.setColor(Ui.withAlpha(0xFFFFFFFF, 0.28f));
                        float s = th * 0.22f, cx = xx + tw / 2f, cy = y + th / 2f;
                        c.save();
                        c.translate(cx, cy);
                        c.scale(s, s);
                        c.drawPath(tri, p);
                        c.restore();
                    }
                }
            }
        }
        gridW = W;
        gridH = H;
        gridShader = new android.graphics.BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        gridP.setShader(gridShader);
    }

    /** Uygulama ikonu: yuvarlak köşeli bitmap'e bir kez çizilir (her karede kırpma maskesi gerekmez). */
    private void buildIcon() {
        if (icon == null || iconBmp != null) return;
        int s = Ui.dp(96);
        iconBmp = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(iconBmp);
        Path clip = new Path();
        clip.addRoundRect(new RectF(0, 0, s, s), s * 0.24f, s * 0.24f, Path.Direction.CW);
        c.clipPath(clip);
        icon.setBounds(0, 0, s, s);
        icon.draw(c);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        rect.set(0, 0, w, h);
        if (mark == 0 && gridShader == null) buildGrid();
        buildIcon();
        sheenShader = new LinearGradient(0, 0, w * 0.45f, h * 0.55f,
                new int[]{0x00FFFFFF, 0x40FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
        sheenP.setShader(sheenShader);
        bg.setShader(new LinearGradient(0, 0, w, h, c1, c2, Shader.TileMode.CLAMP));
        scrim.setShader(new LinearGradient(0, h * 0.42f, 0, h, 0x00000000, 0x8C000000, Shader.TileMode.CLAMP));
        int light = Ui.lerpColor(c1, 0xFFFFFFFF, 0.55f);
        sweep = new SweepGradient(w / 2f, h / 2f,
                new int[]{0xFFFFFFFF, light, 0x00FFFFFF, c1, 0xFFFFFFFF, light, 0x00FFFFFF, c1, 0xFFFFFFFF},
                null);
        border.setShader(sweep);
    }

    @Override
    protected void onFocusChanged(boolean gained, int dir, Rect prev) {
        super.onFocusChanged(gained, dir, prev);
        animateFocus(gained ? 1f : 0f);
        if (gained) {
            if (sa != null) sa.cancel();
            sa = ValueAnimator.ofFloat(0f, 1f);
            sa.setDuration(1100);
            sa.setStartDelay(120);
            sa.addUpdateListener(a -> { sheen = (float) a.getAnimatedValue(); invalidate(); });
            sa.start();
            if (listener != null) listener.onPanelFocused(this);
        }
    }

    void applyDebug() {
        setCached(f < 0.01f);
    }

    /** Seçili değilken panel bir kez donanım katmanına çizilir; her karede sahnesi yeniden işlenmez. */
    private void setCached(boolean cached) {
        int want = cached && Ui.on('l') ? LAYER_TYPE_HARDWARE : LAYER_TYPE_NONE;
        if (getLayerType() != want) setLayerType(want, null);
    }

    private void animateFocus(float target) {
        if (fa != null) fa.cancel();
        if (target > 0f) setCached(false);
        fa = ValueAnimator.ofFloat(f, target);
        fa.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }

            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (!cancelled && target == 0f) setCached(true);
            }
        });
        fa.setDuration(360);
        fa.setInterpolator(new DecelerateInterpolator(2f));
        fa.addUpdateListener(a -> {
            f = (float) a.getAnimatedValue();
            if (getLayoutParams() instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) getLayoutParams();
                lp.weight = 1f + 0.55f * f;
                setLayoutParams(lp);
            }
            setScaleY(1f + 0.03f * f);
            invalidate();
            if (getParent() instanceof View) ((View) getParent()).invalidate(); // parlama çizen satır
        });
        fa.start();
    }

    // ---------- sahneler ----------

    private void sceneYouTube(Canvas c, float w, float h, float t) {
        // eğik, kayan "video kareleri" ızgarası: tek dokulu dikdörtgen
        if (gridShader != null) {
            m.setTranslate(-((t * Ui.dp(16)) % gridW), (t * Ui.dp(7)) % gridH);
            m.postRotate(-12f, w / 2f, h / 2f);
            gridShader.setLocalMatrix(m);
            c.drawRoundRect(rect, radius, radius, gridP);
        }
        // nabız gibi atan büyük oynat düğmesi
        float pulse = 1f + 0.05f * (float) Math.sin(t * 2.4f) * f;
        float bw = h * 0.40f * pulse, bh = bw * 0.70f, cx = w * 0.70f, cy = h * 0.36f;
        fx.setColor(Ui.withAlpha(0xFFFFFFFF, 0.10f + 0.08f * f));
        r2.set(cx - bw * 0.62f, cy - bh * 0.62f, cx + bw * 0.62f, cy + bh * 0.62f);
        c.drawRoundRect(r2, bh * 0.3f, bh * 0.3f, fx);
        fx.setColor(Ui.withAlpha(0xFFFFFFFF, 0.20f + 0.10f * f));
        r2.set(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f);
        c.drawRoundRect(r2, bh * 0.28f, bh * 0.28f, fx);
        fx.setColor(Ui.withAlpha(0xFFFFFFFF, 0.55f + 0.3f * f));
        drawTri(c, cx, cy, bh * 0.26f);
    }

    private void drawTri(Canvas c, float cx, float cy, float s) {
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        c.drawPath(tri, fx);
        c.restore();
    }

    private final float[] dpts = new float[16];

    /**
     * Dönen elmas: köşeleri elle döndürüp 4 düz çizgi (drawLines). Döndürülmüş yuvarlak dikdörtgen çerçeve
     * ekran kartında doğrudan çizilemiyor, her karede yazılımla maske üretiyordu.
     */
    private void diamond(Canvas c, float cx, float cy, float r, float rot) {
        double a0 = Math.toRadians(rot);
        float d = r * 1.4142f; // kare köşesinin merkeze uzaklığı
        for (int i = 0; i < 4; i++) {
            double a = a0 + i * Math.PI / 2, b = a0 + (i + 1) * Math.PI / 2;
            dpts[i * 4] = cx + d * (float) Math.cos(a);
            dpts[i * 4 + 1] = cy + d * (float) Math.sin(a);
            dpts[i * 4 + 2] = cx + d * (float) Math.cos(b);
            dpts[i * 4 + 3] = cy + d * (float) Math.sin(b);
        }
        c.drawLines(dpts, fxs);
    }

    private void star(Canvas c, float x, float y, float s) {
        c.save();
        c.translate(x, y);
        c.scale(s, s);
        c.drawPath(starP, fx);
        c.restore();
    }

    private void sceneStremio(Canvas c, float w, float h, float t) {
        float cx = w * 0.70f, cy = h * 0.36f;
        fxs.setStrokeCap(Paint.Cap.ROUND); // 4 çizgilik elmasta yuvarlak uç köşeleri kapatır (ucuz)
        fxs.setStrokeWidth(Ui.dp(3));
        fxs.setColor(Ui.withAlpha(0xFFFFFFFF, 0.10f + 0.05f * f));
        diamond(c, cx, cy, h * 0.36f, -t * 9f);
        fxs.setColor(Ui.withAlpha(0xFFC4B5FD, 0.25f + 0.1f * f));
        diamond(c, cx, cy, h * 0.26f, t * 16f);
        fxs.setStrokeWidth(Ui.dp(4));
        fxs.setColor(Ui.withAlpha(0xFFFFFFFF, 0.30f + 0.2f * f));
        diamond(c, cx, cy, h * 0.16f, -t * 26f);
        fx.setColor(Ui.withAlpha(0xFFFFFFFF, 0.55f + 0.3f * f));
        drawTri(c, cx, cy, h * 0.06f);
        for (int i = 0; i < SPARKS.length; i++) {
            float tw = 0.5f + 0.5f * (float) Math.sin(t * (1.6f + i * 0.3f) + i * 1.9f);
            fx.setColor(Ui.withAlpha(i % 3 == 0 ? 0xFFFDE68A : 0xFFFFFFFF, (0.15f + 0.65f * tw) * (0.5f + 0.5f * f)));
            star(c, SPARKS[i][0] * w, SPARKS[i][1] * h, Ui.dp(i % 2 == 0 ? 9 : 6)); // boyut sabit, parıltı alfa ile
        }
    }

    /** Dalga: Path yerine drawLines (GPU'da doğrudan çizilir, her karede yeniden üçgenlemeye gerek yok). */
    private void wave(Canvas c, float w, float y, float amp, float len, float phase) {
        float px = 0, py = y + amp * (float) Math.sin(phase);
        int k = 0;
        for (int i = 1; i <= 28; i++) {
            float x = w * i / 28f;
            float yy = y + amp * (float) Math.sin(x / len * 6.2832f + phase);
            wavePts[k++] = px;
            wavePts[k++] = py;
            wavePts[k++] = x;
            wavePts[k++] = yy;
            px = x;
            py = yy;
        }
        c.drawLines(wavePts, 0, k, fxs);
    }

    private void sceneHdmi(Canvas c, float w, float h, float t) {
        int[] cols = {0xFF67E8F9, 0xFFA7F3D0, 0xFFFFFFFF, 0xFFBEF264};
        fxs.setStrokeCap(Paint.Cap.BUTT); // yuvarlak uçlar her segmentte pahalı; düz uç GPU'da hızlı
        for (int i = 0; i < 4; i++) {
            fxs.setStrokeWidth(Ui.dp(i == 2 ? 2 : 3));
            fxs.setColor(Ui.withAlpha(cols[i], (0.22f + 0.25f * f) * (i == 2 ? 0.6f : 1f)));
            wave(c, w, h * (0.20f + 0.09f * i), Ui.dp(10 + 4 * i), w * (0.35f + 0.1f * i), t * (2.2f - i * 0.35f) + i);
        }
        // akan veri noktaları
        for (int i = 0; i < 7; i++) {
            float x = ((t * Ui.dp(70) + i * w / 7f) % (w + Ui.dp(20))) - Ui.dp(10);
            fx.setColor(Ui.withAlpha(0xFFFFFFFF, 0.35f + 0.35f * f));
            c.drawCircle(x, h * 0.56f, Ui.dp(3), fx);
        }
        glyph.setAlpha((int) (50 + 30 * f));
        Icons.hdmi(c, w * 0.80f, h * 0.68f, h * 0.38f, glyph);
    }

    /** USB: eğik, akan film şeridi (delikli kenarlar + kareler) ve köşede silik USB bellek. */
    private void sceneUsb(Canvas c, float w, float h, float t) {
        float band = h * 0.30f, fw = band * 0.78f, gap = band * 0.14f, step = fw + gap;
        float off = (t * Ui.dp(26)) % step;
        c.save();
        c.rotate(-8f, w / 2f, h * 0.34f);
        float top = h * 0.34f - band / 2f;
        fx.setColor(Ui.withAlpha(0xFF000000, 0.22f + 0.08f * f));
        r2.set(-w * 0.2f, top, w * 1.2f, top + band);
        c.drawRect(r2, fx);
        float hole = band * 0.07f, holeStep = band * 0.19f;
        float hoff = (t * Ui.dp(26)) % holeStep;
        fx.setColor(Ui.withAlpha(0xFFFFFFFF, 0.20f + 0.15f * f));
        for (float x = -w * 0.2f - hoff; x < w * 1.2f; x += holeStep) { // delikler
            r2.set(x, top + band * 0.05f, x + hole * 1.3f, top + band * 0.05f + hole);
            c.drawRoundRect(r2, hole * 0.3f, hole * 0.3f, fx);
            r2.set(x, top + band * 0.95f - hole, x + hole * 1.3f, top + band * 0.95f);
            c.drawRoundRect(r2, hole * 0.3f, hole * 0.3f, fx);
        }
        int k = 0;
        for (float x = -w * 0.2f - off; x < w * 1.2f; x += step, k++) { // film kareleri
            boolean hl = (k % 3) == 1;
            fx.setColor(Ui.withAlpha(0xFFFFFFFF, (hl ? 0.20f : 0.10f) + 0.06f * f));
            r2.set(x, top + band * 0.2f, x + fw, top + band * 0.8f);
            c.drawRoundRect(r2, Ui.dp(4), Ui.dp(4), fx);
            if (hl) {
                fx.setColor(Ui.withAlpha(0xFFFFFFFF, 0.45f + 0.2f * f));
                drawTri(c, x + fw / 2f, top + band / 2f, band * 0.11f);
            }
        }
        c.restore();
        glyph.setAlpha((int) (50 + 30 * f));
        Icons.usb(c, w * 0.80f, h * 0.70f, h * 0.36f, glyph);
    }

    private final RectF iconRect = new RectF();
    private final android.text.TextPaint subTp = new android.text.TextPaint();
    private String shownSrc;
    private float shownW;
    private CharSequence shownText = "";

    /** Ana ikon: uygulama ikonu yuvarlatılmış köşelerle; HDMI için çizim. */
    private void drawIcon(Canvas c, float left, float top, float size) {
        if (iconBmp != null) {
            iconRect.set(left, top, left + size, top + size);
            c.drawBitmap(iconBmp, null, iconRect, iconP);
        } else {
            glyph.setAlpha(255);
            if (mark == 3) Icons.usb(c, left + size / 2f, top + size / 2f, size, glyph);
            else Icons.hdmi(c, left + size / 2f, top + size / 2f, size, glyph);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!Ui.on('q')) return;
        float w = getWidth(), h = getHeight();
        long now = SystemClock.uptimeMillis();
        boolean motion = Theme.cur.motion;
        float t = motion ? (now - t0) / 1000f : 0f;

        c.drawRoundRect(rect, radius, radius, bg);
        if (art != null) {
            drawArt(c, w, h);
        } else if (Ui.on('s') && motion) {
            c.save();
            c.clipRect(rect); // dikdörtgen kırpma ekran kartında bedava (makas testi)
            if (mark == 0) sceneYouTube(c, w, h, t);
            else if (mark == 1) sceneStremio(c, w, h, t);
            else if (mark == 2) sceneHdmi(c, w, h, t);
            else sceneUsb(c, w, h, t);
            c.restore();
        }
        c.drawRoundRect(rect, radius, radius, scrim);

        // ışık süpürmesi
        if (sheen >= 0f && sheen < 1f && sheenShader != null) {
            sheenM.setTranslate(-w * 0.6f + w * 2.0f * sheen, 0);
            sheenShader.setLocalMatrix(sheenM);
            c.drawRoundRect(rect, radius, radius, sheenP);
        }

        // seçili değilken karart
        if (f < 0.99f) {
            tint.setColor(Ui.withAlpha(0xFF000000, 0.40f * (1f - f)));
            c.drawRoundRect(rect, radius, radius, tint);
        }

        // içerik: sol alt
        float pad = Ui.dp(28);
        float subBase = h - pad;
        String line = resumeLine != null ? resumeLine : sub;
        boolean hasSub = line != null && !line.isEmpty();
        float titleBase = hasSub ? subBase - Ui.sp(26) : subBase;
        float isz = Ui.dp(68) + Ui.dp(20) * f;
        float bob = motion ? (float) Math.sin(t * 1.9f) * Ui.dp(5) * f : 0f;
        float iconTop = titleBase - Ui.sp(44) - isz + bob;
        if (Ui.on('i')) drawIcon(c, pad, iconTop, isz);

        // tıklama dalgası
        if (rip >= 0f && rip < 1f) { // daire, panelin yuvarlak köşelerine taşmasın diye doku olarak
            float rr = Math.max(w, h) * 1.3f * rip;
            ripM.setScale(rr / 1000f, rr / 1000f);
            ripM.postTranslate(pad + isz / 2f, iconTop + isz / 2f);
            rippleP.getShader().setLocalMatrix(ripM);
            rippleP.setAlpha((int) (255 * 0.38f * (1f - rip)));
            c.drawRoundRect(rect, radius, radius, rippleP);
        }

        titleP.setTextSize(Ui.sp(30) + Ui.sp(8) * f);
        c.drawText(title, pad, titleBase, titleP);
        if (hasSub) {
            subP.setAlpha((int) (160 + 95 * f));
            float avail = w - pad * 2;
            if (!line.equals(shownSrc) || Math.abs(avail - shownW) > 1f) { // kısaltma yalnız metin/genişlik değişince
                shownSrc = line;
                shownW = avail;
                shownText = android.text.TextUtils.ellipsize(line, subTp, avail, android.text.TextUtils.TruncateAt.END);
            }
            c.drawText(shownText, 0, shownText.length(), pad, subBase, subP);
        }
        if (progress >= 0f) { // izleme ilerlemesi
            // yuvarlak uçlu ince çubuk, panel köşelerinden içeride
            float bh = Ui.dp(5), y = h - Ui.dp(11), x0 = radius * 0.6f, x1 = w - radius * 0.6f;
            fx.setColor(0x40FFFFFF);
            r2.set(x0, y, x1, y + bh);
            c.drawRoundRect(r2, bh / 2f, bh / 2f, fx);
            fx.setColor(Ui.lerpColor(c1, 0xFFFFFFFF, 0.25f));
            r2.set(x0, y, x0 + (x1 - x0) * Ui.clamp(progress, 0f, 1f), y + bh);
            c.drawRoundRect(r2, bh / 2f, bh / 2f, fx);
        }

        // dönen renkli çerçeve + ince iç çizgi
        if (f > 0.01f && sweep != null && Ui.on('r')) {
            m.setRotate((t * 90f) % 360f, w / 2f, h / 2f);
            sweep.setLocalMatrix(m);
            border.setAlpha((int) (255 * f));
            float in = Ui.dp(2);
            c.drawRoundRect(in, in, w - in, h - in, radius - in, radius - in, border);
            inner.setAlpha((int) (110 * f));
            float in2 = Ui.dp(6);
            c.drawRoundRect(in2, in2, w - in2, h - in2, radius - in2, radius - in2, inner);
        }
    }
}
