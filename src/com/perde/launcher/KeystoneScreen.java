package com.perde.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.IBinder;
import android.os.Parcel;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Method;

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
    private Diagram diagram;
    private TextView info, resetB, doneB;
    private IBinder flinger;

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
        sel = 0;

        LinearLayout card = a.newCard("4 köşe düzeltme");
        card.setMinimumWidth(Ui.dp(640));
        diagram = new Diagram(a);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Ui.dp(480), Ui.dp(270));
        dl.topMargin = Ui.dp(14);
        dl.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(diagram, dl);
        info = a.newNote("");
        card.addView(info);
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER);
        resetB = a.newButton("Sıfırla", 220, () -> {
            for (int i = 0; i < 8; i++) v[i] = 0;
            apply();
            sel = 0;
            update();
        });
        doneB = a.newButton("Bitti", 220, a::hideOverlay);
        for (TextView b : new TextView[]{resetB, doneB}) {
            ((LinearLayout.LayoutParams) b.getLayoutParams()).setMargins(Ui.dp(8), Ui.dp(12), Ui.dp(8), 0);
            b.setFocusable(false); // köşe ayarlanırken oklar düğmelere kaçmasın
            row.addView(b);
        }
        card.addView(row, new LinearLayout.LayoutParams(-2, -2));
        a.showOverlay(card, card);
        card.setFocusable(true);
        card.requestFocus();
        a.setOverlayKeys(this::onKey);
        update();
    }

    private boolean onKey(KeyEvent e) {
        int k = e.getKeyCode();
        if (sel == 4) { // düğmeler: normal gezinme; köşeye dönmek için yukarı
            if (k == KeyEvent.KEYCODE_DPAD_UP && e.getAction() == KeyEvent.ACTION_DOWN) {
                sel = 0;
                update();
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
        try {
            if (flinger == null) {
                Class<?> sm = Class.forName("android.os.ServiceManager");
                Method g = sm.getMethod("getService", String.class);
                flinger = (IBinder) g.invoke(null, "SurfaceFlinger");
            }
            if (flinger == null) {
                a.toast("Görüntü servisine ulaşılamadı");
                return;
            }
            Parcel p = Parcel.obtain();
            try {
                p.writeInterfaceToken("android.ui.ISurfaceComposer");
                for (int i = 0; i < 8; i++) p.writeFloat(v[i] * 0.001f);
                flinger.transact(1050, p, null, 0);
            } finally {
                p.recycle();
            }
        } catch (Throwable t) {
            a.toast("4 köşe uygulanamadı (yetki?)");
        }
    }

    private void update() {
        boolean buttons = sel >= 4;
        resetB.setFocusable(buttons);
        doneB.setFocusable(buttons);
        if (buttons) {
            sel = 4;
            doneB.requestFocus();
            info.setText("Yukarı: köşelere dön  ·  Geri: kaydet ve çık");
        } else {
            int xi = ORDER[sel];
            info.setText(NAMES[sel] + " köşe  ·  " + v[xi] + ", " + v[xi + 1]
                    + "\nOklar: köşeyi kaydır (basılı tut: hızlı)  ·  OK: sıradaki  ·  Geri: kaydet ve çık");
        }
        diagram.invalidate();
    }

    /** Ekranın küçük şeması: düzeltilmiş dörtgen, seçili köşe parlak. */
    private final class Diagram extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
                dot = new Paint(Paint.ANTI_ALIAS_FLAG), frame = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Diagram(Context c) {
            super(c);
            fill.setColor(0x3338BDF8);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(Ui.dp(2));
            line.setColor(0xFF7DD3FC);
            frame.setStyle(Paint.Style.STROKE);
            frame.setStrokeWidth(Ui.dp(1));
            frame.setColor(0x40FFFFFF);
            frame.setPathEffect(new android.graphics.DashPathEffect(new float[]{Ui.dp(6), Ui.dp(6)}, 0));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), sx = w / (maxH * 8f / 3f), sy = h / (maxV * 8f / 3f);
            c.drawRect(1, 1, w - 1, h - 1, frame);
            float[][] pt = {
                    {v[LT] * sx, v[LT + 1] * sy},
                    {w - v[RT] * sx, v[RT + 1] * sy},
                    {w - v[RB] * sx, h - v[RB + 1] * sy},
                    {v[LB] * sx, h - v[LB + 1] * sy}};
            path.reset();
            path.moveTo(pt[0][0], pt[0][1]);
            for (int i = 1; i < 4; i++) path.lineTo(pt[i][0], pt[i][1]);
            path.close();
            c.drawPath(path, fill);
            c.drawPath(path, line);
            for (int i = 0; i < 4; i++) {
                boolean s = i == sel;
                dot.setColor(s ? 0xFFFFFFFF : 0x99FFFFFF);
                c.drawCircle(Ui.clamp(pt[i][0], Ui.dp(9), w - Ui.dp(9)), Ui.clamp(pt[i][1], Ui.dp(9), h - Ui.dp(9)),
                        Ui.dp(s ? 9 : 5), dot);
            }
        }
    }
}
