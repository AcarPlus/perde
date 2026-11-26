package com.perde.launcher;

import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Live duvar: sessiz/sesli döngü videoları tam ekran oynatır. Perde açılınca bununla başlar.
 * Açılışta TÜM videolar karışık sırayla oynar; video (5 dk) bitince sıradakine geçer.
 * Videolar /sdcard/PerdeLive/&lt;mod klasörü&gt;/*.mp4 (ör. 02_akvaryum); klasör = mod.
 * Kumanda: sağ = sıradaki, sol = önceki, yukarı = döngü aç/kapat (aynı video başa sarar), aşağı = ayarlar (mod, ses),
 * OK = adı göster, Geri = ana ekran.
 */
final class LiveWall extends FrameLayout implements SurfaceHolder.Callback {
    /** APK içi eski klipler: dosya adı → ekrandaki ad. */
    private static final String[][] TITLES = {
            {"01", "Mercan resifi"}, {"02", "Resif balıkları"}, {"03", "Denizanası"}, {"04", "Kuzey ışıkları"},
            {"05", "Samanyolu"}, {"06", "Yıldızlar arasında"}, {"07", "Şömine"}, {"08", "Orman şelalesi"},
            {"09", "Göle yağmur"}, {"10", "Sahilde gün batımı"}, {"11", "Alpler"}};
    /** Klasör adı (numarasız) → ekrandaki mod adı. Listede olmayan klasör adından türetilir. */
    private static final String[][] MODE_NAMES = {
            {"deniz_tropik", "Tropik deniz"}, {"akvaryum", "Akvaryum"}, {"selale_dere", "Şelale ve dere"},
            {"gol_ferah_doga", "Göl ve doğa"}, {"gol_doga", "Göl ve doğa"}, {"gotik_gunduz", "Gotik gündüz"},
            {"gotik_kis", "Gotik kış"}, {"gotik_yagmur_sonbahar", "Gotik yağmur"}, {"gotik_yagmur", "Gotik yağmur"},
            {"manzara", "Manzara"}, {"kar_dag", "Karlı dağ"}, {"japon_bahcesi", "Japon bahçesi"},
            {"cicek_tarlasi", "Çiçek tarlası"}, {"akdeniz_kasaba", "Akdeniz kasabası"}, {"gokyuzu", "Gökyüzü"},
            {"tren_penceresi", "Tren penceresi"}, {"sonbahar_orman", "Sonbahar ormanı"},
            {"kuslar_hayvanlar", "Kuşlar ve hayvanlar"}, {"sehir_penceresi", "Şehir penceresi"},
            {"okyanus_alti", "Okyanus altı"}, {"ejderha", "Ejderha"}, {"fantastik_kale", "Fantastik kale"}};
    private static final String ALL = "Tümü";
    private static final String GOTHIC = "Gotik";

    private final MainActivity a;
    private final SharedPreferences sp;
    private final View black;
    private final LinearLayout label;
    private final TextView title, count;
    /** mod adı → videolar (ilk anahtar "Tümü"). */
    private final Map<String, List<String>> modes = new LinkedHashMap<>();
    private final List<String> modeKeys = new ArrayList<>();
    private int mode; // modeKeys içindeki sıra; açılışta her zaman 0 = Tümü
    private String[] files;
    private int idx, fails;
    private boolean sound;
    /** Döngü modu (▲): açıkken aynı video bitince başa sarar; açılışta kapalı. */
    private boolean loop;
    private TextView loopBadge;
    private MediaPlayer mp;
    private SurfaceHolder holder;
    private final Runnable hideLabel;
    private final Runnable revealFallback = this::reveal;

    // ayarlar penceresi
    private final FrameLayout pop;
    private final LinearLayout card;
    private final LinearLayout[] rows = new LinearLayout[2];
    private final TextView modeVal, modeInfo, soundVal;
    private int row, pendingMode;

    LiveWall(MainActivity a) {
        super(a);
        this.a = a;
        sp = a.getSharedPreferences("perde", 0);
        setBackgroundColor(0xFF000000);
        setKeepScreenOn(true);
        scan(a);
        sound = sp.getBoolean("liveSes", true);
        // son kapatıldığı modda açıl; döngü açıktıysa aynı videoyla döngüde
        int saved = modeKeys.indexOf(sp.getString("liveModName", ALL));
        mode = saved < 0 ? 0 : saved;
        applyMode();
        loop = sp.getBoolean("liveLoop", false);
        if (loop) {
            String last = sp.getString("liveFile", "");
            for (int i = 0; i < files.length; i++) {
                if (files[i].equals(last)) { idx = i; break; }
            }
        }

        SurfaceView sv = new SurfaceView(a);
        sv.getHolder().addCallback(this);
        addView(sv, new LayoutParams(-1, -1));

        black = new View(a);
        black.setBackgroundColor(0xFF000000);
        addView(black, new LayoutParams(-1, -1));

        // sol altta mod + sıra (geçişte belirir, birkaç saniye sonra kaybolur)
        label = new LinearLayout(a);
        label.setOrientation(LinearLayout.VERTICAL);
        label.setPadding(Ui.dp(26), Ui.dp(16), Ui.dp(30), Ui.dp(16));
        label.setBackground(round(0x8C05070D, 22));
        title = Ui.text(a, 30, 0xFFFFFFFF, "sans-serif-light");
        count = Ui.text(a, 15, 0xB3FFFFFF, "sans-serif");
        count.setPadding(Ui.dp(2), Ui.dp(6), 0, 0);
        label.addView(title);
        label.addView(count);
        label.setAlpha(0f);
        LayoutParams llp = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        llp.setMargins(Ui.dp(56), 0, 0, Ui.dp(44));
        addView(label, llp);
        hideLabel = () -> label.animate().alpha(0f).translationY(Ui.dp(10)).setDuration(600).start();

        // ---- ayarlar penceresi (yukarı tuşu) ----
        pop = new FrameLayout(a);
        pop.setBackgroundColor(0x99000000);
        pop.setAlpha(0f);
        pop.setVisibility(GONE);
        card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(34), Ui.dp(28), Ui.dp(34), Ui.dp(24));
        GradientDrawable cbg = round(0xF0101524, 28);
        cbg.setStroke(Ui.dp(1), 0x33FFFFFF);
        card.setBackground(cbg);
        TextView head = Ui.text(a, 26, 0xFFFFFFFF, "sans-serif-light");
        head.setText("Live");
        head.setPadding(Ui.dp(6), 0, 0, Ui.dp(16));
        card.addView(head);

        modeVal = Ui.text(a, 22, 0xFFFFFFFF, "sans-serif-medium");
        modeInfo = Ui.text(a, 14, 0x99FFFFFF, "sans-serif");
        rows[0] = row("Mod", modeVal, modeInfo);
        card.addView(rows[0]);

        soundVal = Ui.text(a, 22, 0xFFFFFFFF, "sans-serif-medium");
        rows[1] = row("Ses", soundVal, null);
        card.addView(rows[1]);

        TextView hint = Ui.text(a, 13, 0x80FFFFFF, "sans-serif");
        hint.setText("▲ ▼ satır   ·   ◀ ▶ değiştir   ·   OK / Geri: kapat");
        hint.setPadding(Ui.dp(6), Ui.dp(16), 0, 0);
        card.addView(hint);
        pop.addView(card, new LayoutParams(Ui.dp(520), -2, Gravity.CENTER));

        // sağ üstte döngü rozeti (yalnız döngü açıkken)
        loopBadge = Ui.text(a, 16, 0xFFFFFFFF, "sans-serif-medium");
        loopBadge.setText("↻  Döngü");
        loopBadge.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(18), Ui.dp(8));
        loopBadge.setBackground(round(0x8C05070D, 18));
        loopBadge.setAlpha(loop ? 1f : 0f); // döngüde kapatıldıysa döngüde açılır
        LayoutParams blp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        blp.setMargins(0, Ui.dp(36), Ui.dp(44), 0);
        addView(loopBadge, blp);
        addView(pop, new LayoutParams(-1, -1));

        if (files.length == 0) {
            title.setText("Live video bulunamadı");
            count.setText("Geri: ana ekran");
            label.setAlpha(1f);
        }
    }

    private static GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(Ui.dp(radiusDp));
        return g;
    }

    /** Ayar satırı: küçük başlık + ◀ değer ▶ + alt bilgi. */
    private LinearLayout row(String name, TextView value, View under) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(Ui.dp(18), Ui.dp(12), Ui.dp(18), Ui.dp(14));
        r.setBackground(round(0x00FFFFFF, 18));
        TextView n = Ui.text(a, 13, 0x99FFFFFF, "sans-serif-medium");
        n.setText(name.toUpperCase(new Locale("tr")));
        n.setLetterSpacing(0.08f);
        r.addView(n);
        value.setPadding(0, Ui.dp(4), 0, 0);
        r.addView(value);
        if (under != null) r.addView(under);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(6);
        r.setLayoutParams(lp);
        return r;
    }

    // ---------- videolar ----------

    /**
     * Videolar: /sdcard/PerdeLive (PC'den adb push ile). Alt klasör = mod; kökteki dosyalar "Manzara".
     * Hiç yoksa APK içi (assets/live).
     */
    private void scan(MainActivity a) {
        Map<String, List<String>> byMode = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<File> roots = new ArrayList<>();
        File ext = a.getExternalFilesDir("live");
        if (ext != null) roots.add(ext);
        roots.add(new File("/sdcard/PerdeLive"));
        for (File root : roots) {
            File[] fs = root.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                if (f.isDirectory()) {
                    File[] in = f.listFiles();
                    if (in == null) continue;
                    for (File v : in) if (isVideo(v)) add(byMode, f.getName(), v.getAbsolutePath());
                } else if (isVideo(f)) {
                    add(byMode, "99_manzara", f.getAbsolutePath());
                }
            }
        }
        List<String> all = new ArrayList<>();
        if (byMode.isEmpty()) {
            try {
                String[] as = a.getAssets().list("live");
                if (as != null) for (String n : as) all.add("asset:live/" + n);
            } catch (Exception ignored) {
            }
        }
        modes.put(ALL, all);
        modeKeys.add(ALL);
        // Gotik = tüm gotik klasörleri + ejderha + fantastik kale; Tümü'nden hemen sonra
        List<String> gothic = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : byMode.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.ROOT);
            if (k.contains("gotik") || k.contains("ejderha") || k.contains("fantastik")) gothic.addAll(e.getValue());
        }
        if (!gothic.isEmpty()) {
            modes.put(GOTHIC, gothic);
            modeKeys.add(GOTHIC);
        }
        for (Map.Entry<String, List<String>> e : byMode.entrySet()) {
            all.addAll(e.getValue());
            if (e.getKey().toLowerCase(Locale.ROOT).contains("gotik")) continue; // yalnız "Gotik" modunda
            String name = modeName(e.getKey());
            List<String> list = modes.get(name);
            if (list == null) {
                list = new ArrayList<>();
                modes.put(name, list);
                modeKeys.add(name);
            }
            list.addAll(e.getValue());
        }
    }

    private static void add(Map<String, List<String>> m, String k, String v) {
        List<String> l = m.get(k);
        if (l == null) m.put(k, l = new ArrayList<>());
        l.add(v);
    }

    private static boolean isVideo(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        return f.isFile() && f.length() > 0 && !n.contains(".tmp.")
                && (n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".webm"));
    }

    private static String modeName(String folder) {
        String k = folder.replaceFirst("^\\d+[ _.-]*", "").toLowerCase(Locale.ROOT);
        for (String[] m : MODE_NAMES) if (m[0].equals(k)) return m[1];
        String n = k.replace('_', ' ').trim();
        return n.isEmpty() ? folder : n.substring(0, 1).toUpperCase(new Locale("tr")) + n.substring(1);
    }

    private static String name(String path) { return path.substring(path.lastIndexOf('/') + 1); }

    /** Ekranda görünen ad: "07 Şömine.mp4" → "Şömine"; YouTube kimliği olan dosyada mod adı. */
    private String displayName(String path) {
        String key = name(path).replaceFirst("\\.[^.]*$", "");
        for (String[] t : TITLES) if (t[0].equals(key)) return t[1];
        if (key.matches("[A-Za-z0-9_-]{11}")) { // YouTube kimliği: klasörün (alt modun) adı, ör. "Gotik kış"
            int s = path.lastIndexOf('/');
            int p = s > 0 ? path.lastIndexOf('/', s - 1) : -1;
            if (s > 0) return modeName(path.substring(p + 1, s));
        }
        return key.replaceFirst("^\\d+[ _.-]*", "").replace('_', ' ').trim();
    }

    /** Seçili modun videolarını karıştırır; baştan başlar. */
    private void applyMode() {
        List<String> l = new ArrayList<>(modes.get(modeKeys.get(mode)));
        Collections.shuffle(l);
        files = l.toArray(new String[0]);
        idx = 0;
    }

    private static int mod(int x, int n) { return ((x % n) + n) % n; }

    // ---------- oynatma ----------
    @Override
    public void surfaceCreated(SurfaceHolder h) {
        holder = h;
        black.animate().cancel();
        black.setAlpha(1f);
        play();
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        holder = null;
        release();
    }

    private void play() {
        release();
        if (holder == null || files.length == 0) return;
        try {
            MediaPlayer m = new MediaPlayer();
            mp = m;
            String f = files[idx];
            sp.edit().putString("liveFile", f).apply();
            if (f.startsWith("asset:")) {
                AssetFileDescriptor afd = a.getAssets().openFd(f.substring(6));
                m.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                afd.close();
            } else {
                try (FileInputStream in = new FileInputStream(f)) {
                    m.setDataSource(in.getFD());
                }
            }
            m.setDisplay(holder);
            m.setLooping(loop || files.length <= 1); // döngü kapalıysa bitince sıradakine geçer
            m.setOnCompletionListener(p -> { if (p == mp) go(1); });
            float v = sound ? 1f : 0f;
            m.setVolume(v, v);
            m.setOnPreparedListener(p -> { if (p == mp) p.start(); });
            m.setOnInfoListener((p, what, extra) -> {
                if (p == mp && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) reveal();
                return false;
            });
            m.setOnErrorListener((p, w, e) -> {
                if (p == mp) a.h.post(this::onFail);
                return true;
            });
            m.prepareAsync();
            a.h.removeCallbacks(revealFallback);
            a.h.postDelayed(revealFallback, 2500); // görüntü başladı bilgisi gelmezse yine de aç
        } catch (Exception e) {
            onFail();
        }
    }

    private void reveal() {
        a.h.removeCallbacks(revealFallback);
        if (mp == null) return;
        fails = 0;
        black.animate().cancel();
        black.animate().alpha(0f).setDuration(700).start();
    }

    private void onFail() {
        release();
        if (files.length == 0) return;
        if (++fails >= files.length) {
            title.setText("Live videolar açılamadı");
            count.setText("Geri: ana ekran");
            label.animate().cancel();
            label.setAlpha(1f);
            return;
        }
        idx = (idx + 1) % files.length;
        showLabel();
        play();
    }

    private void release() {
        a.h.removeCallbacks(revealFallback);
        if (mp == null) return;
        MediaPlayer m = mp;
        mp = null;
        try {
            m.release();
        } catch (Exception ignored) {
        }
    }

    /** Ana ekrana dönerken: siyaha karar, sonra iş bitince çağır. */
    void close(Runnable done) {
        a.h.removeCallbacks(hideLabel);
        label.animate().alpha(0f).setDuration(200).start();
        black.animate().cancel();
        black.animate().alpha(1f).setDuration(260).withEndAction(() -> {
            release();
            done.run();
        }).start();
    }

    private void go(int dir) {
        if (files.length == 0) return;
        int n = idx + dir;
        if (n >= files.length && files.length > 2) { // tur bitti: yeniden karıştır, son video hemen tekrar gelmesin
            String last = files[idx];
            applyMode();
            if (files[0].equals(last)) { files[0] = files[1]; files[1] = last; }
            n = 0;
        }
        idx = mod(n, files.length);
        showLabel();
        black.animate().cancel();
        black.animate().alpha(1f).setDuration(220).withEndAction(this::play).start();
    }

    /** ▲: döngüyü aç/kapat. Rozet yaylanarak belirir; kapanınca küçülüp kaybolur. */
    private void toggleLoop() {
        loop = !loop;
        sp.edit().putBoolean("liveLoop", loop).apply();
        if (mp != null) {
            try { mp.setLooping(loop || files.length <= 1); } catch (Exception ignored) { }
        }
        loopBadge.animate().cancel();
        if (loop) {
            loopBadge.setScaleX(0.6f);
            loopBadge.setScaleY(0.6f);
            loopBadge.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f)).setDuration(420).start();
        } else {
            loopBadge.animate().alpha(0f).scaleX(0.7f).scaleY(0.7f).setDuration(260).start();
        }
        showLabel();
    }

    private void showLabel() {
        if (files.length == 0) return;
        String name = displayName(files[idx]);
        if (name.isEmpty()) name = "Live " + (idx + 1);
        title.setText(name);
        count.setText(modeKeys.get(mode) + "  ·  " + (idx + 1) + " / " + files.length
                + (loop ? "  ·  ↻ döngü açık" : "")
                + "   ·   ◀ ▶ değiştir   ·   ▲ döngü   ·   ▼ ayarlar   ·   Geri: ana ekran");
        a.h.removeCallbacks(hideLabel);
        label.animate().cancel();
        label.setTranslationY(label.getAlpha() > 0.5f ? 0 : Ui.dp(10));
        label.animate().alpha(1f).translationY(0).setDuration(300).start();
        a.h.postDelayed(hideLabel, 3500);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (files.length > 0) a.h.postDelayed(this::showLabel, 600);
    }

    // ---------- ayarlar penceresi ----------
    private boolean popOpen() { return pop.getVisibility() == VISIBLE; }

    /** Klasörleri yeniden okur (Live açıkken gelen yeni klipler/modlar görünsün); oynayan liste değişmez. */
    private void rescan() {
        String cur = modeKeys.isEmpty() ? ALL : modeKeys.get(mode);
        modes.clear();
        modeKeys.clear();
        scan(a);
        int i = modeKeys.indexOf(cur);
        mode = i < 0 ? 0 : i;
    }

    private void openPop() {
        rescan();
        pendingMode = mode;
        row = 0;
        paintPop();
        a.h.removeCallbacks(hideLabel);
        label.animate().alpha(0f).setDuration(200).start();
        pop.setVisibility(VISIBLE);
        pop.animate().cancel();
        pop.animate().alpha(1f).setDuration(220).start();
        card.setScaleX(0.92f);
        card.setScaleY(0.92f);
        card.setTranslationY(Ui.dp(18));
        card.animate().scaleX(1f).scaleY(1f).translationY(0)
                .setInterpolator(new DecelerateInterpolator(2f)).setDuration(320).start();
    }

    private void closePop() {
        pop.animate().cancel();
        card.animate().scaleX(0.96f).scaleY(0.96f).setDuration(180).start();
        pop.animate().alpha(0f).setDuration(180).withEndAction(() -> pop.setVisibility(GONE)).start();
        if (pendingMode != mode) {
            mode = pendingMode;
            sp.edit().putString("liveModName", modeKeys.get(mode)).apply();
            applyMode();
            fails = 0;
            showLabel();
            black.animate().cancel();
            black.animate().alpha(1f).setDuration(220).withEndAction(this::play).start();
        }
    }

    private void paintPop() {
        String m = modeKeys.get(pendingMode);
        modeVal.setText("◀   " + m + "   ▶");
        int n = modes.get(m).size();
        modeInfo.setText(n + " video" + (pendingMode == 0 ? " · karışık" : "")
                + "   ·   " + (pendingMode + 1) + " / " + modeKeys.size());
        soundVal.setText("◀   " + (sound ? "Açık" : "Kapalı") + "   ▶");
        for (int i = 0; i < rows.length; i++) {
            ((GradientDrawable) rows[i].getBackground()).setColor(i == row ? 0x24FFFFFF : 0x00FFFFFF);
            rows[i].animate().alpha(i == row ? 1f : 0.6f).setDuration(150).start();
        }
    }

    private void change(int dir) {
        if (row == 0) {
            pendingMode = mod(pendingMode + dir, modeKeys.size());
        } else {
            sound = !sound;
            sp.edit().putBoolean("liveSes", sound).apply();
            if (mp != null) {
                float v = sound ? 1f : 0f;
                try { mp.setVolume(v, v); } catch (Exception ignored) { }
            }
        }
        paintPop();
    }

    /** MainActivity.dispatchKeyEvent'ten gelir; true = tuş burada işlendi. */
    boolean onKey(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN, first = down && e.getRepeatCount() == 0;
        if (popOpen()) {
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down && row > 0) { row--; paintPop(); }
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && row < rows.length - 1) { row++; paintPop(); }
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (down) change(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) change(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_ESCAPE:
                    if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) closePop();
                    return true;
                default:
                    return false;
            }
        }
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (first) go(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                if (first) go(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (first && files.length > 0) toggleLoop();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (first && files.length > 0) openPop();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (first) showLabel(); // OK: yalnız adı göster
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) a.hideLive();
                return true;
            default:
                return false; // ses tuşları vb. sisteme gitsin
        }
    }
}
