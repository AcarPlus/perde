package com.perde.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Görüntü ekranı (TV tarzı): üstte profil seçici, altında ayar çubukları. Yukarı/aşağı satır seçer,
 * sol/sağ değeri değiştirir (basılı tutunca sürer), her değişiklik anında ekrana uygulanır ve kaydedilir.
 */
final class PictureScreen {
    private final MainActivity a;
    private final int[] v;
    private int profile; // 0..5 ya da ColorProfiles.CUSTOM
    private Row profileRow;
    private final Row[] rows = new Row[ColorProfiles.N];
    private long lastSave;

    PictureScreen(MainActivity a) {
        this.a = a;
        this.v = ColorProfiles.load(a);
        this.profile = ColorProfiles.presetOf(v);
    }

    void show() {
        LinearLayout card = a.newCard("Görüntü");
        card.setMinimumWidth(Ui.dp(720));
        TextView hint = Ui.text(a, 14, 0x99FFFFFF, "sans-serif");
        hint.setText("Yukarı/aşağı: ayar seç   ·   Sol/sağ: değiştir   ·   Anında uygulanır");
        hint.setPadding(Ui.dp(4), Ui.dp(6), 0, Ui.dp(4));
        card.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        profileRow = new Row(a, -1);
        list.addView(profileRow, rowLp(Ui.dp(12)));
        for (int i = 0; i < ColorProfiles.N; i++) {
            rows[i] = new Row(a, i);
            // gruplar arası daha geniş boşluk: profil | parlaklık…sıcaklık | kırmızı/yeşil/mavi
            list.addView(rows[i], rowLp(i == 0 || i == ColorProfiles.RED ? Ui.dp(18) : Ui.dp(7)));
        }
        card.addView(list, new LinearLayout.LayoutParams(LIST_W, -2));
        // düğmeler: ayar satırlarıyla aynı genişlikte, üstte belirgin boşluk
        LinearLayout btns = new LinearLayout(a);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, Ui.dp(20), 0, 0);
        TextView ks = a.newButton("4 köşe düzeltme", 10, () -> {
            flush();
            new KeystoneScreen(a).show();
        });
        TextView reset = a.newButton("Sıfırla", 10, () -> setAll(ColorProfiles.preset(0)));
        TextView close = a.newButton("Kapat", 10, a::hideOverlay);
        float[] weights = {1.4f, 1f, 1f};
        TextView[] bs = {ks, reset, close};
        for (int i = 0; i < 3; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, weights[i]);
            lp.setMargins(i == 0 ? 0 : Ui.dp(7), 0, i == 2 ? 0 : Ui.dp(7), 0);
            bs[i].setLayoutParams(lp);
            btns.addView(bs[i]);
        }
        card.addView(btns, new LinearLayout.LayoutParams(LIST_W, -2));
        a.showOverlay(card, profileRow);
        a.setOverlayKeys(this::onKey);
        a.setOverlayClose(this::flush);
    }

    private static final int LIST_W = Ui.dp(680);

    private static LinearLayout.LayoutParams rowLp(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(44));
        lp.topMargin = top;
        return lp;
    }

    private boolean onKey(KeyEvent e) {
        int k = e.getKeyCode();
        if (k != KeyEvent.KEYCODE_DPAD_LEFT && k != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        View f = a.getCurrentFocus();
        if (!(f instanceof Row)) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) ((Row) f).step(k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
        return true;
    }

    private void setAll(int[] nv) {
        System.arraycopy(nv, 0, v, 0, ColorProfiles.N);
        changed();
    }

    private void changed() {
        profile = ColorProfiles.presetOf(v);
        if (!ColorProfiles.apply(v)) a.toast("Görüntü ayarı uygulanamadı");
        profileRow.invalidate();
        for (Row r : rows) r.invalidate();
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastSave > 400) { // basılı tutarken her adımda diske yazma
            ColorProfiles.save(a, v);
            lastSave = now;
        }
    }

    private void flush() { ColorProfiles.save(a, v); }

    /** Tek ayar satırı (idx = -1 profil seçici). */
    private final class Row extends View {
        final int idx;
        private boolean focused;
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG),
                track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Row(Context c, int idx) {
            super(c);
            this.idx = idx;
            setFocusable(true);
            setFocusableInTouchMode(true);
            setSoundEffectsEnabled(false);
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            text.setTextSize(Ui.sp(17));
        }

        void step(int dir) {
            if (idx < 0) {
                int n = ColorProfiles.NAMES.length;
                int cur = profile == ColorProfiles.CUSTOM ? (dir > 0 ? -1 : 0) : profile;
                setAll(ColorProfiles.preset(((cur + dir) % n + n) % n));
                return;
            }
            int nv = Math.max(ColorProfiles.MIN[idx], Math.min(ColorProfiles.MAX[idx], v[idx] + dir * ColorProfiles.STEP[idx]));
            if (nv == v[idx]) return;
            v[idx] = nv;
            changed();
        }

        @Override
        protected void onFocusChanged(boolean gained, int d, android.graphics.Rect p) {
            super.onFocusChanged(gained, d, p);
            focused = gained;
            invalidate();
        }

        private String valueText() {
            int x = v[idx];
            if (idx == ColorProfiles.BRIGHT) return "%" + x;
            if (idx == ColorProfiles.TEMP) return x == 0 ? "nötr" : x > 0 ? "sıcak +" + x : "soğuk " + (-x);
            return x > 0 ? "+" + x : String.valueOf(x);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), rad = h / 2f;
            r.set(0, 0, w, h);
            bg.setColor(focused ? 0xFFFFFFFF : 0x14FFFFFF);
            c.drawRoundRect(r, rad, rad, bg);
            int fg = focused ? 0xFF0B1220 : 0xFFFFFFFF;
            text.setColor(fg);
            float base = h / 2f + text.getTextSize() * 0.35f;
            float pad = Ui.dp(24);
            if (idx < 0) {
                text.setTextAlign(Paint.Align.LEFT);
                c.drawText("Profil", pad, base, text);
                String name = profile == ColorProfiles.CUSTOM ? "Özel" : ColorProfiles.NAMES[profile]
                        + "  ·  " + ColorProfiles.HINTS[profile];
                text.setTextAlign(Paint.Align.CENTER);
                c.drawText((focused ? "◀   " : "") + name + (focused ? "   ▶" : ""), w * 0.6f, base, text);
                return;
            }
            text.setTextAlign(Paint.Align.LEFT);
            c.drawText(ColorProfiles.LABELS[idx], pad, base, text);
            // çubuk: sağda değer yazısı, ortada iz
            float x0 = w * 0.38f, x1 = w - Ui.dp(130), th = Ui.dp(6), cy = h / 2f;
            track.setColor(focused ? 0x330B1220 : 0x33FFFFFF);
            r.set(x0, cy - th / 2, x1, cy + th / 2);
            c.drawRoundRect(r, th / 2, th / 2, track);
            int min = ColorProfiles.MIN[idx], max = ColorProfiles.MAX[idx];
            float k = (v[idx] - min) / (float) (max - min);
            boolean centered = min < 0; // ortası sıfır olan ayarlarda dolgu ortadan başlar
            float from = centered ? (x0 + x1) / 2f : x0, to = x0 + (x1 - x0) * k;
            fill.setColor(barColor(focused));
            r.set(Math.min(from, to), cy - th / 2, Math.max(from, to), cy + th / 2);
            c.drawRoundRect(r, th / 2, th / 2, fill);
            c.drawCircle(to, cy, Ui.dp(9), fill);
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText(valueText(), w - pad, base, text);
        }

        private int barColor(boolean f) {
            switch (idx) {
                case ColorProfiles.RED: return 0xFFEF4444;
                case ColorProfiles.GREEN: return 0xFF22C55E;
                case ColorProfiles.BLUE: return 0xFF3B82F6;
                case ColorProfiles.TEMP: return v[idx] >= 0 ? 0xFFF59E0B : 0xFF38BDF8;
                default: return f ? 0xFF0B1220 : 0xFFFFFFFF;
            }
        }
    }
}
