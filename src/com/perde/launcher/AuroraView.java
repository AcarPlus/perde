package com.perde.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import java.util.Random;

/**
 * Arka plan: yavaşça kayan renk bulutları + projektör huzmesi (düşük çözünürlüklü GPU katmanı, ekrana büyütülür)
 * ve üstünde tam çözünürlükte bokeh ışıkları ile toz zerreleri.
 *
 * v2.4: bulutlar eskiden işlemcide yazılımla çiziliyordu (her güncellemede ~70 ms). Artık tek bir hazır
 * "yumuşak daire" dokusu renklendirilip ekran kartında çiziliyor; kare başına maliyet ~0,1 ms.
 */
final class AuroraView extends ViewGroup {
    private static final int SCALE = 6; // bulut katmanı ekranın 1/6'sı (320x180), v2.3 ile aynı yumuşaklık
    private final Clouds clouds;
    private final Dust dust;

    AuroraView(Context c) {
        super(c);
        clouds = new Clouds(c);
        dust = new Dust(c);
        addView(clouds);
        addView(dust);
        clouds.setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    @Override
    protected void onMeasure(int wm, int hm) {
        int w = MeasureSpec.getSize(wm), h = MeasureSpec.getSize(hm);
        setMeasuredDimension(w, h);
        int sw = Math.max(1, w / SCALE), sh = Math.max(1, h / SCALE);
        clouds.measure(MeasureSpec.makeMeasureSpec(sw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(sh, MeasureSpec.EXACTLY));
        dust.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        int sw = Math.max(1, w / SCALE), sh = Math.max(1, h / SCALE);
        clouds.layout(0, 0, sw, sh);
        clouds.setPivotX(0);
        clouds.setPivotY(0);
        clouds.setScaleX(w / (float) sw);
        clouds.setScaleY(h / (float) sh);
        dust.layout(0, 0, w, h);
    }

    void setAccent(int color) {
        clouds.target = color;
        clouds.invalidate();
    }

    /** Gece modu / tema değişince hemen yeniden çiz. */
    void refresh() {
        clouds.invalidate();
        dust.invalidate();
    }

    void setRunning(boolean on) {
        long now = SystemClock.uptimeMillis();
        clouds.last = now;
        dust.last = now;
        if (on) refresh();
    }

    /** Ortak kare saatinden her karede çağrılır. */
    void tick() {
        if (!Theme.cur.motion) return;
        if (Ui.on('x')) clouds.invalidate();
        if (Ui.on('b')) dust.invalidate();
    }

    // ------------------------------------------------------------------------------------------
    private static final class Clouds extends View {
        private static Bitmap sprite;
        private final Paint[] blobP = new Paint[4];
        private final int[] blobC = new int[4];
        private final RectF r = new RectF();
        private final Paint beam = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shade = new Paint();
        private final Path beamPath = new Path();
        private final long t0 = SystemClock.uptimeMillis();
        long last = t0;
        int target = Theme.cur.panel[0][0], cur = target;

        Clouds(Context c) {
            super(c);
            if (sprite == null) {
                // beyaz, kenara doğru saydamlaşan yumuşak daire; renk, çizerken filtreyle verilir
                int s = 96;
                sprite = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setShader(new RadialGradient(s / 2f, s / 2f, s / 2f,
                        new int[]{0xFFFFFFFF, 0x80FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
                new Canvas(sprite).drawCircle(s / 2f, s / 2f, s / 2f, p);
            }
            for (int i = 0; i < 4; i++) blobP[i] = new Paint(Paint.FILTER_BITMAP_FLAG);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            beam.setShader(new LinearGradient(0, 0, 0, h, 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            shade.setShader(new LinearGradient(0, h * 0.55f, 0, h, 0x00000000, 0xB0000000, Shader.TileMode.CLAMP));
        }

        private static int approach(int a, int b, float k) {
            int out = 0xFF000000;
            for (int sh = 16; sh >= 0; sh -= 8) {
                int x = (a >> sh) & 0xFF, y = (b >> sh) & 0xFF, d = y - x;
                int step = Math.round(d * k);
                if (step == 0 && d != 0) step = d > 0 ? 1 : -1;
                out |= (x + step) << sh;
            }
            return out;
        }

        private void blob(Canvas c, int i, float x, float y, float rad, int color, float alpha) {
            alpha *= Theme.cur.cloudAlpha;
            if (Ui.night) alpha *= 0.45f; // gece: bulutlar sönük
            int col = Ui.withAlpha(color, alpha);
            if (blobC[i] != col || blobP[i].getColorFilter() == null) { // filtre yalnız renk değişince yenilenir
                blobC[i] = col;
                blobP[i].setColorFilter(new PorterDuffColorFilter(col, PorterDuff.Mode.SRC_IN));
            }
            r.set(x - rad, y - rad, x + rad, y + rad);
            c.drawBitmap(sprite, null, r, blobP[i]);
        }

        @Override
        protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(0.2f, (now - last) / 1000f);
            last = now;
            float t = (now - t0) / 1000f * 0.5f; // bulutlar yarım hızda
            // vurgu rengi: seçili panelin rengine yumuşak geçiş (zamana bağlı, kare hızından bağımsız)
            // her kanal en az 1 adım ilerler (tamsayı yuvarlamasında takılıp sonsuz yeniden çizim olmasın)
            float k = Ui.awake ? 1f - (float) Math.pow(0.88, dt / 0.4f) : 1f;
            cur = approach(cur, target, Math.max(k, 0.02f));
            if (cur != target) postInvalidateOnAnimation(); // geçiş bitene kadar (bekleme modunda da) sürer

            Theme th = Theme.cur;
            float sw = getWidth(), sh = getHeight();
            c.drawColor(th.base);
            blob(c, 0, sw * (0.20f + 0.25f * (float) Math.sin(t * 0.11f)), sh * (0.30f + 0.15f * (float) Math.cos(t * 0.09f)),
                    sh * (0.80f + 0.08f * (float) Math.sin(t * 0.21f)), cur, 0.55f);
            blob(c, 1, sw * (0.80f + 0.15f * (float) Math.cos(t * 0.07f + 1.3f)), sh * (0.50f + 0.2f * (float) Math.sin(t * 0.08f + 0.4f)),
                    sh * (0.75f + 0.1f * (float) Math.cos(t * 0.17f)), th.clouds[0], 0.42f);
            blob(c, 2, sw * (0.50f + 0.30f * (float) Math.sin(t * 0.05f + 2.1f)), sh * (1.00f + 0.08f * (float) Math.cos(t * 0.13f)),
                    sh * 0.70f, th.clouds[1], 0.45f);
            blob(c, 3, sw * (0.05f + 0.1f * (float) Math.cos(t * 0.09f)), sh * 0.95f, sh * 0.5f, th.clouds[2], 0.30f);
            // projektör huzmesi: üstten açılan ışık konisi
            if (!Ui.night && th.motion) {
                float flick = 0.85f + 0.15f * (float) Math.sin(t * 7.0f) * (float) Math.sin(t * 2.3f);
                float bx = sw * (0.5f + 0.04f * (float) Math.sin(t * 0.3f));
                beamPath.rewind();
                beamPath.moveTo(bx - sw * 0.04f, 0);
                beamPath.lineTo(bx + sw * 0.04f, 0);
                beamPath.lineTo(bx + sw * 0.42f, sh);
                beamPath.lineTo(bx - sw * 0.42f, sh);
                beamPath.close();
                beam.setAlpha((int) (255 * flick));
                c.drawPath(beamPath, beam);
            }
            c.drawRect(0, 0, sw, sh, shade);
        }
    }

    // ------------------------------------------------------------------------------------------
    private static final class Dust extends View {
        private static final int B = 14, N = 40;
        private final Paint bokeh = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dust = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] bx = new float[B], by = new float[B], br = new float[B], bs = new float[B], ba = new float[B];
        private final float[] px = new float[N], py = new float[N], ps = new float[N], pr = new float[N], pa = new float[N];
        private final long t0 = SystemClock.uptimeMillis();
        long last = t0;

        Dust(Context c) {
            super(c);
            Random r = new Random(7);
            for (int i = 0; i < N; i++) {
                px[i] = r.nextFloat();
                py[i] = r.nextFloat();
                ps[i] = 0.004f + r.nextFloat() * 0.012f;
                pr[i] = 1f + r.nextFloat() * 2.2f;
                pa[i] = 0.05f + r.nextFloat() * 0.22f;
            }
            for (int i = 0; i < B; i++) {
                bx[i] = r.nextFloat();
                by[i] = r.nextFloat();
                br[i] = 10f + r.nextFloat() * 34f;
                bs[i] = 0.002f + r.nextFloat() * 0.006f;
                ba[i] = 0.06f + r.nextFloat() * 0.10f;
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(0.1f, (now - last) / 1000f);
            last = now;
            Theme th = Theme.cur;
            if (!th.motion) return;
            float t = (now - t0) / 1000f;
            int w = getWidth(), h = getHeight();

            // renkli bokeh ışıkları: büyük, yumuşak, yavaşça süzülür
            if (th.bokeh.length > 0 && !Ui.night) {
                for (int i = 0; i < B; i++) {
                    by[i] -= bs[i] * dt;
                    if (by[i] < -0.1f) by[i] = 1.1f;
                    float sway = (float) Math.sin(t * 0.25f + i * 2f) * 0.02f;
                    float breathe = 0.7f + 0.3f * (float) Math.sin(t * 0.8f + i);
                    bokeh.setColor(Ui.withAlpha(th.bokeh[i % th.bokeh.length], ba[i] * breathe));
                    c.drawCircle((bx[i] + sway) * w, by[i] * h, Ui.dp(br[i]), bokeh);
                }
            }
            // toz zerreleri: yavaşça yukarı süzülür
            for (int i = 0; i < N; i++) {
                py[i] -= ps[i] * dt;
                if (py[i] < -0.02f) py[i] = 1.02f;
                float sway = (float) Math.sin(t * 0.6f + i) * 0.004f;
                float twinkle = 0.6f + 0.4f * (float) Math.sin(t * 1.3f + i * 1.7f);
                dust.setColor(Ui.withAlpha(0xFFFFFFFF, pa[i] * twinkle));
                c.drawCircle((px[i] + sway) * w, py[i] * h, Ui.dp(pr[i]), dust);
            }
        }
    }
}
