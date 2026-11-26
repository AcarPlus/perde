package com.perde.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 4 köşe düzeltme (Perde görünümü). Üreticinin HTC ayarlarındaki yolun aynısı: SurfaceFlinger'a 1050 kodlu
 * çağrı, 8 değer (her köşenin içeri kayması, piksel × 0,001). Değerler persist.display.keystone_* özelliklerinde
 * saklanıyor. Sınırlar da üreticininki: karşılıklı iki köşenin toplam kayması ekranın 3/8'ini geçemez.
 *
 * Kumanda: OK = sıradaki köşe (sol üst → sağ üst → sağ alt → sol alt → düğmeler), oklar = seçili köşeyi o yöne
 * kaydır (basılı tutunca hızlanır), Geri = kaydet ve çık.
 */
final class KeystoneScreen {
    static final String[] PROPS = {"lbx", "lby", "ltx", "lty", "rtx", "rty", "rbx", "rby"};
    private static final String[] NAMES = {"Sol üst", "Sağ üst", "Sağ alt", "Sol alt"};
    // köşe sırası (ekranda saat yönü): LT, RT, RB, LB → dizideki x indeksleri
    private static final int LT = 2, RT = 4, RB = 6, LB = 0;
    private static final int[] ORDER = {LT, RT, RB, LB};

    private final MainActivity a;
    private final int[] v = new int[8]; // lbx lby ltx lty rtx rty rbx rby (içeri kayma, piksel)
    private int sel;                    // 0..3 köşe, 4 = düğmeler
    private int maxH, maxV;
    private FrameLayout box;
    private Frame frame;
    private LinearLayout row, slotRow;
    private TextView head, info, resetB, doneB, saveB, autoB;
    private final TextView[] slotB = new TextView[KeystoneProfiles.N];
    private boolean saveMode;           // "Kaydet"e basıldı: sıradaki yer düğmesi kaydeder

    KeystoneScreen(MainActivity a) { this.a = a; }

    static int[] read() {
        int[] r = new int[8];
        for (int i = 0; i < 8; i++) {
            try {
                r[i] = Integer.parseInt(Ui.prop("persist.display.keystone_" + PROPS[i], "0").trim());
            } catch (NumberFormatException e) {
                r[i] = 0;
            }
        }
        return r;
    }

    void show() {
        int w = a.getResources().getDisplayMetrics().widthPixels, h = a.getResources().getDisplayMetrics().heightPixels;
        int lw = Math.max(w, h), lh = Math.min(w, h);
        maxH = lw / 2 - lw / 8; // üreticinin sınırı (1920 → 720)
        maxV = lh / 2 - lh / 8; // (1080 → 405)
        System.arraycopy(read(), 0, v, 0, 8);
        // v3.7: kayıtlı yer varsa düğmelerle açılır (yer seçmek tek tuş); köşeler için Yukarı
        sel = KeystoneProfiles.any(a) ? 4 : 0;

        // v3.6: tam siyah ekran + kenarlarda %100 beyaz çerçeve. Çerçeve ekranın gerçek kenarı olduğu için
        // duvardaki görüntünün köşeleri doğrudan görülür; seçili köşede kalın beyaz "L" işareti var.
        box = new FrameLayout(a);
        box.setBackgroundColor(0xFF000000);
        frame = new Frame(a);
        box.addView(frame, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout mid = new LinearLayout(a);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.CENTER_HORIZONTAL);
        head = Ui.text(a, 30, 0xFFFFFFFF, "sans-serif-light");
        head.setGravity(Gravity.CENTER);
        mid.addView(head, new LinearLayout.LayoutParams(-2, -2));
        info = a.newNote("");
        info.setTextColor(0xFFFFFFFF);
        info.setTextSize(16);
        mid.addView(info);
        // v3.7: kayıtlı yerler (Oda, Bodrum, …): OK = o yerin köşelerini uygula; "Kaydet"ten sonra OK = buraya kaydet
        slotRow = new LinearLayout(a);
        slotRow.setGravity(Gravity.CENTER);
        for (int i = 0; i < KeystoneProfiles.N; i++) {
            final int slot = i;
            slotB[i] = a.newButton("", 190, () -> onSlot(slot));
            ((LinearLayout.LayoutParams) slotB[i].getLayoutParams()).setMargins(Ui.dp(8), Ui.dp(12), Ui.dp(8), 0);
            slotB[i].setFocusable(false);
            slotRow.addView(slotB[i]);
        }
        mid.addView(slotRow, new LinearLayout.LayoutParams(-2, -2));
        row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER);
        saveB = a.newButton("Kaydet", 190, () -> {
            saveMode = true;
            slotB[0].requestFocus();
            update();
        });
        autoB = a.newButton("", 250, () -> {
            KeystoneProfiles.setAuto(a, !KeystoneProfiles.autoOn(a));
            update();
        });
        resetB = a.newButton("Sıfırla", 190, () -> {
            for (int i = 0; i < 8; i++) v[i] = 0;
            apply();
            saveMode = false;
            sel = 0;
            update();
        });
        doneB = a.newButton("Bitti", 190, a::hideOverlay);
        for (TextView b : new TextView[]{saveB, autoB, resetB, doneB}) {
            ((LinearLayout.LayoutParams) b.getLayoutParams()).setMargins(Ui.dp(8), Ui.dp(12), Ui.dp(8), 0);
            b.setFocusable(false); // köşe ayarlanırken oklar düğmelere kaçmasın
            row.addView(b);
        }
        mid.addView(row, new LinearLayout.LayoutParams(-2, -2));
        KeystoneProfiles.startScan(a); // kaydederken Wi-Fi parmak izi taze olsun
        box.addView(mid, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        a.showOverlayFull(box);
        a.setOverlayKeys(this::onKey);
        // beyaz gerçekten %100 olsun: pencere parlaklığı en yüksek, kişisel renk profili geçici olarak kapalı
        final android.view.Window win = a.getWindow();
        final android.view.WindowManager.LayoutParams wl = win.getAttributes();
        final float oldBright = wl.screenBrightness;
        wl.screenBrightness = 1f;
        win.setAttributes(wl);
        final int[] prof = ColorProfiles.load(a);
        final boolean custom = !ColorProfiles.isDefault(prof);
        if (custom) ColorProfiles.apply(ColorProfiles.preset(0));
        a.setOverlayClose(() -> {
            android.view.WindowManager.LayoutParams l2 = win.getAttributes();
            l2.screenBrightness = oldBright;
            win.setAttributes(l2);
            if (custom) ColorProfiles.apply(prof);
        });
        update();
    }

    private boolean onKey(KeyEvent e) {
        int k = e.getKeyCode();
        if (sel == 4) { // düğmeler: normal gezinme; en üst sıradan yukarı = köşelere dön
            if (k == KeyEvent.KEYCODE_DPAD_UP && e.getAction() == KeyEvent.ACTION_DOWN && slotRow.hasFocus()) {
                saveMode = false;
                sel = 0;
                update();
                return true;
            }
            if (k == KeyEvent.KEYCODE_BACK && saveMode) { // kaydetmekten vazgeç
                if (e.getAction() == KeyEvent.ACTION_UP) {
                    saveMode = false;
                    saveB.requestFocus();
                    update();
                }
                return true;
            }
            return false;
        }
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
            if (down && e.getRepeatCount() == 0) {
                sel++;
                update();
            }
            return true;
        }
        int dx = 0, dy = 0;
        if (k == KeyEvent.KEYCODE_DPAD_LEFT) dx = -1;
        else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) dx = 1;
        else if (k == KeyEvent.KEYCODE_DPAD_UP) dy = -1;
        else if (k == KeyEvent.KEYCODE_DPAD_DOWN) dy = 1;
        else return false;
        if (!down) return true;
        int step = Math.min(2 + e.getRepeatCount(), 14);
        move(ORDER[sel], dx * step, dy * step);
        return true;
    }

    /** Köşeyi ekranda (dx, dy) piksel kaydır; içeri kayma değerine çevrilir ve sınırlanır. */
    private void move(int xi, int dx, int dy) {
        boolean left = xi == LT || xi == LB, top = xi == LT || xi == RT;
        int inX = left ? dx : -dx;  // sol köşe sağa giderse içeri, sağ köşe sola giderse içeri
        int inY = top ? dy : -dy;   // üst köşe aşağı giderse içeri
        int ox = xi == LT ? RT : xi == RT ? LT : xi == RB ? LB : RB;   // aynı satırdaki karşı köşe
        int oy = xi == LT ? LB : xi == LB ? LT : xi == RT ? RB : RT;   // aynı sütundaki karşı köşe
        v[xi] = clamp(v[xi] + inX, 0, maxH - v[ox]);
        v[xi + 1] = clamp(v[xi + 1] + inY, 0, maxV - v[oy + 1]);
        apply();
        update();
    }

    private static int clamp(int x, int lo, int hi) { return x < lo ? lo : Math.min(x, Math.max(lo, hi)); }

    private void apply() {
        if (!KeystoneProfiles.push(v)) a.toast("4 köşe uygulanamadı (yetki?)");
    }

    private void onSlot(int slot) {
        String name = KeystoneProfiles.NAMES[slot];
        if (saveMode) {
            boolean wifi = KeystoneProfiles.save(a, slot, v);
            saveMode = false;
            a.toast(name + " kaydedildi" + (wifi ? "" : " (Wi-Fi görünmüyor: otomatik tanıma çalışmaz)"));
            update();
            return;
        }
        int[] s = KeystoneProfiles.values(a, slot);
        if (s == null) {
            a.toast(name + " boş: köşeleri ayarla, Kaydet'e bas, sonra bu yeri seç");
            return;
        }
        System.arraycopy(s, 0, v, 0, 8);
        apply();
        a.toast(name + " uygulandı");
        update();
    }

    private int firstSaved() {
        for (int i = 0; i < KeystoneProfiles.N; i++)
            if (KeystoneProfiles.has(a, i) && java.util.Arrays.equals(KeystoneProfiles.values(a, i), v)) return i;
        for (int i = 0; i < KeystoneProfiles.N; i++) if (KeystoneProfiles.has(a, i)) return i;
        return 0;
    }

    private void update() {
        boolean buttons = sel >= 4;
        boolean entering = buttons && !(slotRow.hasFocus() || row.hasFocus());
        for (TextView b : new TextView[]{saveB, autoB, resetB, doneB}) b.setFocusable(buttons);
        for (int i = 0; i < KeystoneProfiles.N; i++) {
            slotB[i].setFocusable(buttons);
            boolean has = KeystoneProfiles.has(a, i);
            boolean here = has && java.util.Arrays.equals(KeystoneProfiles.values(a, i), v);
            slotB[i].setText((here ? "✓ " : "") + KeystoneProfiles.NAMES[i] + (has ? "" : " (boş)"));
        }
        autoB.setText("Otomatik: " + (KeystoneProfiles.autoOn(a) ? "açık" : "kapalı"));
        row.setVisibility(buttons ? View.VISIBLE : View.GONE);
        slotRow.setVisibility(buttons ? View.VISIBLE : View.GONE);
        if (buttons) {
            sel = 4;
            if (saveMode) {
                head.setText("Hangi yere kaydedeyim?");
                info.setText("Yeri seçip OK'a bas  ·  Geri: vazgeç");
            } else {
                head.setText("4 köşe düzeltme");
                info.setText("Yer seç: o yerin köşeleri uygulanır  ·  Otomatik: Wi-Fi'ye bakıp yeri kendisi tanır\n"
                        + "Yukarı (üst sıradan): köşeleri ayarla  ·  Geri: kaydet ve çık");
            }
            if (entering) (KeystoneProfiles.any(a) ? slotB[firstSaved()] : saveB).requestFocus();
        } else {
            int xi = ORDER[sel];
            box.requestFocus();
            head.setText(NAMES[sel] + " köşe  (" + (sel + 1) + "/4)");
            info.setText("içeri: " + v[xi] + ", " + v[xi + 1]
                    + "\nOklar: köşeyi kaydır (basılı tut: hızlı)  ·  OK: sıradaki köşe  ·  Geri: kaydet ve çık");
        }
        frame.invalidate();
    }

    /** Tam ekran: kenarda %100 beyaz çerçeve, seçili köşede kalın beyaz L. */
    private final class Frame extends View {
        private final Paint white = new Paint();

        Frame(Context c) {
            super(c);
            white.setColor(0xFFFFFFFF);
            white.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onDraw(Canvas c) {
            // v3.7: dış çerçeve ince (2 px): duvar kenarına hizalamak kolay; seçili köşedeki L kalın kalır
            float w = getWidth(), h = getHeight(), t = 2;
            c.drawColor(0xFF000000);
            c.drawRect(0, 0, w, t, white);
            c.drawRect(0, h - t, w, h, white);
            c.drawRect(0, 0, t, h, white);
            c.drawRect(w - t, 0, w, h, white);
            if (sel >= 4) return;
            float arm = Ui.dp(150), th = Ui.dp(18);
            boolean left = sel == 0 || sel == 3, top = sel == 0 || sel == 1;
            float x0 = left ? 0 : w - arm, y0 = top ? 0 : h - th;
            c.drawRect(x0, y0, x0 + arm, y0 + th, white);                 // yatay kol
            float x1 = left ? 0 : w - th, y1 = top ? 0 : h - arm;
            c.drawRect(x1, y1, x1 + th, y1 + arm, white);                 // dikey kol
        }
    }
}
