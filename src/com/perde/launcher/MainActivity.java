package com.perde.launcher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity implements PanelView.FocusListener {
    static final String YT = "com.google.android.youtube.tv";
    static final String STREMIO = "com.stremio.one";
    static final String HTC = "com.htc.htcsettings";
    static final String HTC_HOME_PKG = "com.htc.htclauncherhighenglishd08";
    static final String LIVETV = "com.softwinner.awlivetv";
    /** Yazılımda üç HDMI girişi görünüyor ama projeksiyonda tek fiziksel HDMI var (bkz. hdmiSource). */
    static final String[] SOURCES = {"HDMI1", "HDMI2", "HDMI3"};
    static final Locale TR = new Locale("tr", "TR");

    final Handler h = new Handler(Looper.getMainLooper());
    private final Sensors sensors = new Sensors();
    private FrameLayout root;
    private AuroraView aurora;
    private TextView date;
    private final PanelView[] panels = new PanelView[4];
    private final BubbleView[] bubbles = new BubbleView[4];
    private BubbleView settingsB;
    private FrameLayout overlay;
    private View focusBeforeOverlay, lastFocus;
    private SharedPreferences prefs;
    private boolean introDone;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            StringBuilder sb = new StringBuilder(greeting()).append("  ·  ")
                    .append(new SimpleDateFormat("EEEE, d MMMM", TR).format(new Date()));
            long sleepMin = SleepReceiver.remainingMin(MainActivity.this);
            if (sleepMin > 0) sb.append("  ·  ☾ ").append(sleepMin).append(" dk sonra uyku");
            date.setText(sb);
            applyNight(Ui.isNightHour());
            bubbles[0].wifiOn = isNetworkConnected();
            bubbles[0].invalidate();
            h.postDelayed(this, 15000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        prefs = getSharedPreferences("perde", MODE_PRIVATE);
        Theme.load(this);
        final Theme th = Theme.cur;
        // pencere arka planı yok: tam ekranı zaten opak bulut katmanı dolduruyor (her karede bir tam ekran dolgusu az)
        getWindow().setBackgroundDrawable(null);
        // ölçüm: setprop debug.perde.fmt 565 → 16 bit pencere (yarı bellek trafiği; gradyanlarda bantlaşma riski)
        if ("565".equals(Ui.prop("debug.perde.fmt", ""))) getWindow().setFormat(android.graphics.PixelFormat.RGB_565);

        root = new FrameLayout(this);
        aurora = new AuroraView(this);
        root.addView(aurora, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(56), Ui.dp(34), Ui.dp(56), Ui.dp(8));
        col.setClipChildren(false);
        col.setClipToPadding(false);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // üst çubuk: saat + tarih | sıcaklık + fan
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout timeBox = new LinearLayout(this);
        timeBox.setOrientation(LinearLayout.VERTICAL);
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextSize(52);
        clock.setTextColor(0xFFFFFFFF);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setIncludeFontPadding(false);
        date = Ui.text(this, 18, 0xB3FFFFFF, "sans-serif");
        date.setPadding(Ui.dp(3), Ui.dp(4), 0, 0);
        timeBox.addView(clock);
        timeBox.addView(date);
        top.addView(timeBox, new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(top, new LinearLayout.LayoutParams(-1, -2));

        // üç ana panel
        LinearLayout row = new GlowRow(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(0, Ui.dp(24), 0, Ui.dp(14));
        panels[0] = new PanelView(this, 0, "YouTube", "Video ve canlı yayın", th.panel[0][0], th.panel[0][1],
                appIcon(YT), v -> { ((PanelView) v).ripple(); pulse(v, () -> openResumeOrApp(YT)); });
        panels[1] = new PanelView(this, 1, "Stremio", "Film ve dizi", th.panel[1][0], th.panel[1][1],
                appIcon(STREMIO), v -> { ((PanelView) v).ripple(); pulse(v, () -> openResumeOrApp(STREMIO)); });
        panels[2] = new PanelView(this, 2, "HDMI", "", th.panel[2][0], th.panel[2][1], null,
                v -> { ((PanelView) v).ripple(); pulse(v, this::openHdmi); });
        panels[2].setLongClickable(false);
        panels[3] = new PanelView(this, 3, "USB", "", th.panel[3][0], th.panel[3][1], null,
                v -> { ((PanelView) v).ripple(); pulse(v, this::openUsb); });
        for (PanelView p : panels) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
            lp.setMargins(Ui.dp(12), 0, Ui.dp(12), 0);
            p.setListener(this);
            p.setAlpha(0f);
            p.setTranslationY(Ui.dp(60));
            row.addView(p, lp);
        }
        col.addView(row, new LinearLayout.LayoutParams(-1, 0, 1f));

        // alt baloncuklar
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_HORIZONTAL);
        bar.setClipChildren(false);
        // v3.0: Wi-Fi, Bluetooth ve 4 köşe Perde'nin kendi ekranları (sistem uygulaması yetkisiyle).
        // HTC'nin eski ekranlarına Ayarlar baloncuğu ve Diğer menüsünden hâlâ ulaşılır.
        bubbles[0] = new BubbleView(this, BubbleView.WIFI, "Wi-Fi", th.bubble[0][0], th.bubble[0][1], sensors,
                v -> pulse(v, () -> new WifiScreen(this).show()));
        bubbles[1] = new BubbleView(this, BubbleView.BT, "Bluetooth", th.bubble[1][0], th.bubble[1][1], sensors,
                v -> pulse(v, () -> new BtScreen(this).show()));
        bubbles[2] = new BubbleView(this, BubbleView.PROJ, "Görüntü", th.bubble[2][0], th.bubble[2][1], sensors,
                v -> pulse(v, this::showPicture));
        bubbles[2].hint = "renk · 4 köşe";
        bubbles[3] = settingsB = new BubbleView(this, BubbleView.SETTINGS, "Ayarlar", th.bubble[4][0], th.bubble[4][1], sensors,
                v -> pulse(v, this::showMore)); // tek basış: Perde ayarları; uzun basış: HTC ayarları (dispatchKeyEvent)
        settingsB.hint = "uzun bas: HTC";
        settingsB.setLongClickable(false);
        for (BubbleView bv : bubbles) {
            bv.setAlpha(0f);
            bv.setTranslationY(Ui.dp(30));
            bar.addView(bv, new LinearLayout.LayoutParams(Ui.dp(124), Ui.dp(140)));
        }
        col.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        // v3.3: alttaki "Uygulamalar" ipucu ve gizli çekmece kaldırıldı (Ayarlar › Tüm uygulamalar yeterli)

        View.OnFocusChangeListener remember = (v, has) -> { if (has) lastFocus = v; };
        for (PanelView p : panels) p.setOnFocusChangeListener(remember);
        for (BubbleView bv : bubbles) bv.setOnFocusChangeListener(remember);

        nightShade = new View(this);
        nightShade.setBackgroundColor(0x4A0A0500); // gece: sıcak tonlu hafif karartma
        nightShade.setVisibility(View.GONE);
        root.addView(nightShade, new FrameLayout.LayoutParams(-1, -1));

        aurora.setAccent(th.panel[0][0]);

        setContentView(root);
        silence();
        // renk matrisi yeniden başlatınca sıfırlanır: kayıtlı profil açılışta yeniden uygulanır
        int[] gv = ColorProfiles.load(this);
        if (!ColorProfiles.isDefault(gv)) ColorProfiles.apply(gv);
        hideSystemUi();
        updateHdmiSub();
    }

    private View nightShade;

    // ---------- tam sessizlik ----------
    /**
     * Ok tuşlarındaki "tık" sesi Android'in sistem dokunma sesi (Settings.System.SOUND_EFFECTS_ENABLED).
     * Perde sistem uygulaması olduğu için ayarı kendisi kapatır (HTC ayarları açsa bile her açılışta yeniden).
     * Ayrıca Perde'nin kendi görünümleri hiç ses çalmaz (setSoundEffectsEnabled(false)).
     */
    private void silence() {
        try {
            if (Settings.System.getInt(getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED, 1) != 0) {
                Settings.System.putInt(getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED, 0);
                android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                if (am != null) am.unloadSoundEffects();
            }
        } catch (Throwable ignored) {
        }
        mute(root);
    }

    static void mute(View v) {
        v.setSoundEffectsEnabled(false);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) mute(g.getChildAt(i));
        }
    }

    // ---------- gece modu ----------
    private void applyNight(boolean on) {
        if (Ui.night == on && nightShade.getVisibility() == (on ? View.VISIBLE : View.GONE)) return;
        Ui.night = on;
        nightShade.setVisibility(on ? View.VISIBLE : View.GONE);
        aurora.refresh();
    }

    // ---------- bekleme ekranı: 5 dk işlem yoksa büyük saat ----------
    private static final long SAVER_MS = 5 * 60_000L;
    private FrameLayout saver;
    private LinearLayout saverBox;
    private final Runnable showSaver = this::showSaver;
    private final Runnable driftSaver = new Runnable() {
        @Override
        public void run() {
            if (saver == null || saverBox == null) return;
            float mx = root.getWidth() * 0.22f, my = root.getHeight() * 0.22f;
            saverBox.animate().translationX((float) (Math.random() * 2 - 1) * mx)
                    .translationY((float) (Math.random() * 2 - 1) * my).setDuration(6000).start();
            h.postDelayed(this, 20_000);
        }
    };

    private void showSaver() {
        if (saver != null || !resumed) return;
        hideOverlayNow();
        saver = new FrameLayout(this);
        saver.setBackgroundColor(0xFF000000);
        saver.setAlpha(0f);
        saverBox = new LinearLayout(this);
        saverBox.setOrientation(LinearLayout.VERTICAL);
        saverBox.setGravity(Gravity.CENTER_HORIZONTAL);
        TextClock big = new TextClock(this);
        big.setFormat24Hour("HH:mm");
        big.setFormat12Hour("HH:mm");
        big.setTextSize(140);
        big.setTextColor(Ui.night ? 0x99FFFFFF : 0xE6FFFFFF);
        big.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        big.setIncludeFontPadding(false);
        TextView d = Ui.text(this, 24, 0x99FFFFFF, "sans-serif-light");
        d.setText(new SimpleDateFormat("EEEE, d MMMM", TR).format(new Date()));
        saverBox.addView(big);
        saverBox.addView(d);
        saver.addView(saverBox, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addView(saver, new FrameLayout.LayoutParams(-1, -1));
        saver.animate().alpha(1f).setDuration(1500).start();
        h.post(driftSaver);
    }

    private boolean hideSaver() {
        if (saver == null) return false;
        h.removeCallbacks(driftSaver);
        final FrameLayout s = saver;
        saver = null;
        s.animate().alpha(0f).setDuration(350).withEndAction(() -> root.removeView(s)).start();
        return true;
    }

    // ---------- HDMI sinyal algılama ----------
    private android.media.tv.TvInputManager tim;
    private final java.util.Map<String, Integer> hdmiStates = new java.util.HashMap<>();
    private android.media.tv.TvInputManager.TvInputCallback tvCb;

    private static String hdmiInputId(String source) {
        return "com.softwinner.vis/.HdmiInputService/HW" + source.replace("HDMI", "");
    }

    private void startHdmiWatch() {
        try {
            tim = (android.media.tv.TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            if (tim == null) return;
            for (String src : SOURCES) hdmiStates.put(src, tim.getInputState(hdmiInputId(src)));
            tvCb = new android.media.tv.TvInputManager.TvInputCallback() {
                @Override
                public void onInputStateChanged(String inputId, int state) {
                    for (String src : SOURCES) {
                        if (!hdmiInputId(src).equals(inputId)) continue;
                        Integer old = hdmiStates.put(src, state);
                        updateHdmiSub();
                        if (state == android.media.tv.TvInputManager.INPUT_STATE_CONNECTED
                                && (old == null || old != android.media.tv.TvInputManager.INPUT_STATE_CONNECTED)) {
                            onHdmiConnected(src);
                        }
                    }
                }
            };
            tim.registerCallback(tvCb, h);
        } catch (Throwable ignored) {
        }
    }

    private void stopHdmiWatch() {
        try {
            if (tim != null && tvCb != null) tim.unregisterCallback(tvCb);
        } catch (Throwable ignored) {
        }
        tvCb = null;
    }

    private boolean hdmiConnected(String src) {
        Integer st = hdmiStates.get(src);
        return st != null && st == android.media.tv.TvInputManager.INPUT_STATE_CONNECTED;
    }

    private void onHdmiConnected(String src) {
        // tek fiziksel giriş: sinyalin geldiği yazılım girişi öğrenilir, bundan sonra hep o açılır
        prefs.edit().putString("hdmi", src).apply();
        updateHdmiSub();
        hideSaver();
        LinearLayout card = newCard("HDMI'de görüntü var");
        card.setMinimumWidth(Ui.dp(520));
        card.addView(newNote("HDMI girişine bir cihaz bağlandı."));
        TextView open = newButton("Aç", 300, () -> {
            hideOverlayNow();
            openHdmi();
        });
        card.addView(open);
        card.addView(newButton("Şimdi değil", 300, this::hideOverlay));
        showOverlay(card, open);
        final FrameLayout mine = overlay;
        h.postDelayed(() -> { if (overlay == mine) hideOverlay(); }, 15_000);
    }

    // ---------- USB bellek ----------
    static final String VLC = "org.videolan.vlc";
    static final String FILES = "com.softwinner.TvdFileManager";
    private android.content.BroadcastReceiver usbRx;

    /** Takılı USB belleğin adı; yoksa null. */
    private String usbLabel() {
        try {
            android.os.storage.StorageManager sm = getSystemService(android.os.storage.StorageManager.class);
            for (android.os.storage.StorageVolume v : sm.getStorageVolumes()) {
                if (v.isRemovable() && android.os.Environment.MEDIA_MOUNTED.equals(v.getState())) {
                    String d = v.getDescription(this);
                    return d == null || d.isEmpty() ? "USB bellek" : d;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void updateUsbSub() {
        String l = usbLabel();
        panels[3].setSub(l != null ? "● " + l + " takılı" : "Bellek takılı değil");
    }

    private void startUsbWatch() {
        if (usbRx != null) return;
        usbRx = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                h.postDelayed(() -> {
                    updateUsbSub();
                    if (Intent.ACTION_MEDIA_MOUNTED.equals(i.getAction()) && resumed && usbLabel() != null) onUsbMounted();
                }, 400);
            }
        };
        android.content.IntentFilter f = new android.content.IntentFilter();
        f.addAction(Intent.ACTION_MEDIA_MOUNTED);
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        f.addAction(Intent.ACTION_MEDIA_REMOVED);
        f.addAction(Intent.ACTION_MEDIA_EJECT);
        f.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
        f.addDataScheme("file");
        registerReceiver(usbRx, f);
    }

    private void stopUsbWatch() {
        if (usbRx == null) return;
        try {
            unregisterReceiver(usbRx);
        } catch (Exception ignored) {
        }
        usbRx = null;
    }

    private void onUsbMounted() {
        hideSaver();
        LinearLayout card = newCard("USB bellek takıldı");
        card.setMinimumWidth(Ui.dp(520));
        card.addView(newNote(usbLabel() + " hazır. Filmleri VLC ile açabilirsin."));
        TextView open = newButton("Aç", 300, () -> {
            hideOverlayNow();
            openUsb();
        });
        card.addView(open);
        card.addView(newButton("Şimdi değil", 300, this::hideOverlay));
        showOverlay(card, open);
        final FrameLayout mine = overlay;
        h.postDelayed(() -> { if (overlay == mine) hideOverlay(); }, 15_000);
    }

    /** USB filmleri: VLC (USB'yi "Klasörler/Depolama" altında listeler); yoksa cihazın dosya yöneticisi. */
    private void openUsb() {
        PackageManager pm = getPackageManager();
        for (String pkg : new String[]{VLC, FILES}) {
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            if (i != null) {
                safeStart(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
        }
        toast("VLC ya da dosya yöneticisi bulunamadı");
    }

    // ---------- kaldığın yerden devam ----------
    private void loadResume() {
        SharedPreferences p = getSharedPreferences("devam", MODE_PRIVATE);
        String[] pk = {YT, STREMIO};
        for (int i = 0; i < 2; i++) {
            String title = p.getString(pk[i] + ".title", null);
            long time = p.getLong(pk[i] + ".time", 0);
            long pos = p.getLong(pk[i] + ".pos", -1), dur = p.getLong(pk[i] + ".dur", 0);
            boolean fresh = System.currentTimeMillis() - time < 14L * 24 * 3600_000L;
            boolean finished = dur > 0 && pos >= 0 && pos > dur * 0.95;
            if (title == null || !fresh || finished) {
                panels[i].setResume(null, null, -1f);
                continue;
            }
            String line = "▶ Devam: " + title + (pos > 0 ? "  ·  " + fmt(pos) + (dur > 0 ? " / " + fmt(dur) : "") : "");
            panels[i].setResume(MediaWatcher.loadArt(this, pk[i]), line, dur > 0 && pos >= 0 ? pos / (float) dur : -1f);
        }
    }

    private static String fmt(long ms) {
        long s = ms / 1000, hh = s / 3600, mm = (s % 3600) / 60, ss = s % 60;
        return hh > 0 ? String.format(Locale.US, "%d:%02d:%02d", hh, mm, ss) : String.format(Locale.US, "%d:%02d", mm, ss);
    }

    /** Devam bilgisi varsa: YouTube'da video kimliği biliniyorsa tam kalınan saniyeden açar; yoksa uygulamayı açar. */
    private void openResumeOrApp(String pkg) {
        SharedPreferences p = getSharedPreferences("devam", MODE_PRIVATE);
        String id = p.getString(pkg + ".id", null);
        long pos = p.getLong(pkg + ".pos", 0);
        if (YT.equals(pkg) && id != null && id.matches("[A-Za-z0-9_-]{11}") && p.getString(pkg + ".title", null) != null) {
            Intent i = new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://www.youtube.com/watch?v=" + id + "&t=" + Math.max(0, pos / 1000 - 3) + "s"))
                    .setPackage(YT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        openApp(pkg);
    }

    // ---------- uyku zamanlayıcısı ----------
    private void showSleep() {
        LinearLayout card = newCard("Uyku zamanlayıcısı");
        card.setMinimumWidth(Ui.dp(560));
        long left = SleepReceiver.remainingMin(this);
        card.addView(newNote((left > 0 ? "Şu an açık: " + left + " dk sonra uyku.\n" : "")
                + "Süre dolunca projeksiyon uyku moduna geçer, kumandanın güç tuşuyla uyanır.\n1 dakika önce ekranda uyarı çıkar."));
        LinearLayout row1 = new LinearLayout(this), row2 = new LinearLayout(this);
        row1.setGravity(Gravity.CENTER);
        row2.setGravity(Gravity.CENTER);
        int[] mins = {15, 30, 45, 60, 90, 120};
        TextView first = null;
        for (int k = 0; k < mins.length; k++) {
            final int m = mins[k];
            TextView b = newButton(m < 60 ? m + " dk" : (m / 60) + (m % 60 == 0 ? " saat" : ",5 saat"), 150, () -> {
                hideOverlay();
                startSleep(m); // v3.0: sistem uygulaması olarak doğrudan uyutur, ayrı yetki gerekmez
            });
            ((LinearLayout.LayoutParams) b.getLayoutParams()).setMargins(Ui.dp(8), Ui.dp(10), Ui.dp(8), 0);
            (k < 3 ? row1 : row2).addView(b);
            if (first == null) first = b;
        }
        card.addView(row1, new LinearLayout.LayoutParams(-2, -2));
        card.addView(row2, new LinearLayout.LayoutParams(-2, -2));
        if (left > 0) {
            card.addView(newButton("Zamanlayıcıyı kapat", 360, () -> {
                SleepReceiver.set(this, 0);
                hideOverlay();
                h.removeCallbacks(tick);
                h.post(tick);
                toast("Uyku zamanlayıcısı kapatıldı");
            }));
        }
        if (AdminReceiver.active(this) && left <= 0) {
            card.addView(newButton("Uyutma yetkisini kaldır", 360, () -> {
                AdminReceiver.remove(this);
                hideOverlay();
                toast("Uyutma yetkisi kaldırıldı");
            }));
        }
        card.addView(newButton("Vazgeç", 220, this::hideOverlay));
        showOverlay(card, first);
    }

    private static final int REQ_ADMIN = 7;
    private int pendingSleep;

    private void startSleep(int m) {
        SleepReceiver.set(this, m);
        h.removeCallbacks(tick);
        h.post(tick);
        toast("Uyku zamanlayıcısı: " + m + " dk");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_ADMIN) {
            if (AdminReceiver.active(this) && pendingSleep > 0) startSleep(pendingSleep);
            else toast("Uyutma yetkisi verilmedi, zamanlayıcı kurulmadı");
            pendingSleep = 0;
        }
    }

    /** Selamlama; Ayarlar › İsim girildiyse sonuna eklenir ("İyi akşamlar, Ayşe"). */
    private String greeting() {
        int hr = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String g = hr >= 5 && hr < 12 ? "Günaydın" : hr >= 12 && hr < 18 ? "İyi günler" : hr >= 18 && hr < 23 ? "İyi akşamlar" : "İyi geceler";
        String name = prefs.getString("isim", "").trim();
        return name.isEmpty() ? g : g + ", " + name;
    }

    /** Ayarlar › İsim: selamlamada görünecek ad (boş bırakılabilir). */
    private void showName() {
        LinearLayout card = newCard("İsim");
        card.setMinimumWidth(Ui.dp(560));
        card.addView(newNote("Ana ekrandaki selamlamada görünür. Boş bırakabilirsin."));
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setSingleLine(true);
        et.setTextSize(22);
        et.setTextColor(0xFFFFFFFF);
        et.setHintTextColor(0x66FFFFFF);
        et.setHint("Adın");
        et.setText(prefs.getString("isim", ""));
        et.setSelection(et.length());
        et.setGravity(Gravity.CENTER);
        et.setSoundEffectsEnabled(false);
        et.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        et.setPadding(Ui.dp(20), Ui.dp(14), Ui.dp(20), Ui.dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(18));
        bg.setColor(0x26FFFFFF);
        et.setBackground(bg);
        et.setOnFocusChangeListener((v, has) -> bg.setStroke(Ui.dp(2), has ? 0xFFFFFFFF : 0x00FFFFFF));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(440), -2);
        lp.topMargin = Ui.dp(10);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(et, lp);
        Runnable save = () -> {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(et.getWindowToken(), 0);
            prefs.edit().putString("isim", et.getText().toString().trim()).apply();
            hideOverlay();
            h.removeCallbacks(tick);
            h.post(tick);
        };
        TextView ok = newButton("Kaydet", 260, save);
        et.setOnEditorActionListener((v, id, ev) -> {
            ok.requestFocus();
            return false;
        });
        card.addView(ok);
        card.addView(newButton("Vazgeç", 220, this::hideOverlay));
        showOverlay(card, et);
    }

    // ---------- bekleme modu: 60 sn tuşa basılmazsa animasyonlar durur ----------
    private static final long IDLE_MS = 30_000;
    private final Runnable sleep = () -> Ui.awake = false;

    private void wake() {
        h.removeCallbacks(sleep);
        h.postDelayed(sleep, IDLE_MS);
        h.removeCallbacks(showSaver);
        h.postDelayed(showSaver, SAVER_MS);
        if (!Ui.awake) {
            Ui.awake = true;
            h.removeCallbacks(frame);
            scheduleFrame();
        }
    }

    // ---------- ortak kare saati: ekran yenilemesine (vsync) bağlı, her zaman en yüksek hız ----------
    private boolean frameQueued;

    // ---------- 30 fps bekçisi ----------
    /**
     * Animasyon hep en yüksek hızda çalışır. Ölçülen kare hızı 2 sn'lik iki pencere üst üste 30'un altına düşerse
     * efektler kademeli hafifletilir: 1) bokeh tozu, 2) panel parlaması, 3) arka plan bulut hareketi. Uygulama
     * süreci yaşadıkça kademe korunur (her açılışta yeniden düşmesin). `setprop debug.perde.fpslog 1` ile logcat'e yazar.
     */
    private static int fpsLevel;
    private static final String[] FPS_LEVELS = {"", "b", "bg", "bgx"};
    private long fpsWinStart, fpsLastNs, fpsQuietUntil;
    private int fpsFrames, fpsBadWins;

    private void fpsSample(long ns) {
        long now = android.os.SystemClock.uptimeMillis();
        // geçişler (odak, katman açılışı) ve ilk saniye ölçülmez; aradaki uzun boşluk (bekleme) pencereyi sıfırlar
        if (now < fpsQuietUntil || overlay != null || fpsLastNs == 0 || ns - fpsLastNs > 200_000_000L) {
            fpsLastNs = ns;
            fpsWinStart = ns;
            fpsFrames = 0;
            return;
        }
        fpsLastNs = ns;
        fpsFrames++;
        long win = ns - fpsWinStart;
        if (win < 2_000_000_000L) return;
        float fps = fpsFrames * 1e9f / win;
        if ("1".equals(Ui.prop("debug.perde.fpslog", ""))) {
            android.util.Log.i("Perde", "fps " + String.format(Locale.US, "%.1f", fps) + " kademe " + fpsLevel);
        }
        fpsWinStart = ns;
        fpsFrames = 0;
        if (fps < 30f) {
            if (++fpsBadWins >= 2 && fpsLevel < FPS_LEVELS.length - 1) {
                fpsLevel++;
                fpsBadWins = 0;
                Ui.auto = FPS_LEVELS[fpsLevel];
                android.util.Log.i("Perde", "kare hızı 30 altı → efekt kademesi " + fpsLevel);
                invalidateAll(root);
                fpsQuietUntil = now + 1500;
            }
        } else {
            fpsBadWins = 0;
        }
    }

    /** Odak değişimi ve menü geçişleri kısa süre ölçülmez (geçiş animasyonları ölçümü yanıltır). */
    private void fpsQuiet() {
        fpsQuietUntil = android.os.SystemClock.uptimeMillis() + 900;
    }

    private void scheduleFrame() {
        if (frameQueued) return;
        frameQueued = true;
        android.view.Choreographer.getInstance().postFrameCallback(vsync);
    }

    private final android.view.Choreographer.FrameCallback vsync = new android.view.Choreographer.FrameCallback() {
      @Override
      public void doFrame(long ns) {
        frameQueued = false;
        if (!resumed) return;
        if (++dbgTick % 120 == 0) { // ~2 sn'de bir ölçüm anahtarlarını oku
            String before = Ui.off;
            Ui.readDebug();
            if (!before.equals(Ui.off)) {
                for (PanelView pv : panels) pv.applyDebug();
                invalidateAll(root);
            }
        }
        if (!Ui.awake) { // bekleme modu: 2 sn'de bir yalnız durum ve fan
            h.removeCallbacks(frame);
            h.postDelayed(frame, 2000);
            return;
        }
        boolean motion = Theme.cur.motion;
        if (!motion && overlayAnimated.isEmpty()) {
            // hareketsiz tema: kare saati durur; durum ve fan değerleri saniyede bir yenilenir
            h.removeCallbacks(frame);
            h.postDelayed(frame, 1000);
            return;
        }
        fpsSample(ns);
        aurora.tick();
        // fan simgeleri: dönüyorsa her kare, duruyorsa yarım saniyede bir (sıcaklık değeri için)
        View f = getCurrentFocus();
        if (Ui.on('u')) {
            if (motion && f instanceof BubbleView) f.invalidate();
        }
        if (motion && f instanceof PanelView && Ui.on('p')) f.invalidate();
        for (View v : overlayAnimated) v.invalidate();
        scheduleFrame();
      }
    };
    private boolean resumed;
    private int dbgTick;

    private static boolean isInside(View v, View parent) {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p == parent) return true;
        return false;
    }

    private static void invalidateAll(View v) {
        v.invalidate();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) invalidateAll(g.getChildAt(i));
        }
    }
    private final List<View> overlayAnimated = new ArrayList<>();
    /** Bekleme modunda 2 sn'lik yavaş tik: bir sonraki vsync karesini ister. */
    private final Runnable frame = () -> { if (resumed) scheduleFrame(); };

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        wake();
        fpsQuiet();
    }

    private void playIntro() {
        if (introDone) return;
        introDone = true;
        long base = 0; // v3.0: perde açılış animasyonu kaldırıldı
        for (int i = 0; i < panels.length; i++) {
            panels[i].setScaleX(0.85f);
            final View pv = panels[i];
            // parlamayı çizen satır panelin süzülmesini görmez: her adımda yeniden çizilsin
            panels[i].animate().alpha(1f).translationY(0).scaleX(1f).setStartDelay(base + 80 + i * 120L).setDuration(700)
                    .setInterpolator(new OvershootInterpolator(1.1f))
                    .setUpdateListener(a -> ((View) pv.getParent()).invalidate()).start();
        }
        for (int i = 0; i < bubbles.length; i++) {
            bubbles[i].setScaleX(0.4f);
            bubbles[i].setScaleY(0.4f);
            bubbles[i].animate().alpha(1f).translationY(0).scaleX(1f).scaleY(1f).setStartDelay(base + 480 + i * 80L)
                    .setDuration(520).setInterpolator(new OvershootInterpolator(2.2f)).start();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        hideOverlayNow(); // Wi-Fi/Bluetooth ekranlarının alıcıları ve aramaları kapansın
    }

    @Override
    protected void onResume() {
        super.onResume();
        silence();
        fpsQuiet();
        hideSystemUi();
        resumed = true;
        wake();
        h.removeCallbacks(frame);
        scheduleFrame();
        h.removeCallbacks(cleaner);
        h.postDelayed(cleaner, CLEAN_MS);
        aurora.setRunning(true);
        h.removeCallbacks(tick);
        h.post(tick);
        startHdmiWatch();
        updateHdmiSub();
        startUsbWatch();
        updateUsbSub();
        loadResume();
        root.post(() -> {
            playIntro();
            View cur = getCurrentFocus();
            if (overlay == null && (cur == null || !(cur instanceof PanelView || cur instanceof BubbleView))) {
                (lastFocus != null ? lastFocus : panels[0]).requestFocus();
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        aurora.setRunning(false);
        h.removeCallbacks(tick);
        h.removeCallbacks(sleep);
        resumed = false;
        h.removeCallbacks(frame);
        h.removeCallbacks(cleaner);
        h.removeCallbacks(showSaver);
        if (saver != null) {
            h.removeCallbacks(driftSaver);
            root.removeView(saver);
            saver = null;
        }
        stopHdmiWatch();
        stopUsbWatch();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // HOME: zaten ana ekrandaysak ilk panele, değilse son seçilene dön
        boolean alreadyHome = hasWindowFocus() && overlay == null;
        hideOverlay();
        if (alreadyHome || lastFocus == null) panels[0].requestFocus();
        else lastFocus.requestFocus();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    public void onBackPressed() {
        if (overlay != null) hideOverlay();
        // ana ekranda geri tuşu bir şey yapmaz
    }

    private boolean longHandled;

    /** Uzun basış: OK tuşu basılı tutulunca gelen tekrar olaylarından algılanır (Ayarlar → Diğer, HDMI → giriş değiştir). */
    private boolean swallowUp;

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        // bekleme ekranı açıksa herhangi bir tuş sadece onu kapatır
        if (e.getAction() == KeyEvent.ACTION_DOWN && saver != null && k != KeyEvent.KEYCODE_HOME) {
            hideSaver();
            wake();
            swallowUp = true;
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_UP && swallowUp) {
            swallowUp = false;
            return true;
        }
        // açık ekran tuşları kendisi işliyorsa (4 köşe: oklar köşeyi kaydırır)
        if (overlay != null && overlayKeys != null && k != KeyEvent.KEYCODE_HOME && overlayKeys.onKey(e)) return true;
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
            if (e.getAction() == KeyEvent.ACTION_UP && longHandled) {
                longHandled = false;
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() > 0) {
                if (longHandled) return true;
                View f = getCurrentFocus();
                if (overlay == null && f == settingsB) {
                    longHandled = true;
                    openComponent(HTC, "com.htc.activity.HomeActivity", Settings.ACTION_SETTINGS);
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onPanelFocused(PanelView p) {
        aurora.setAccent(p.c1);
        fpsQuiet();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    /** Basma hissi: kısa küçülüp geri büyür, sonra işi yapar. */
    private void pulse(View v, Runnable r) {
        final float sx = v.getScaleX(), sy = v.getScaleY();
        v.animate().scaleX(sx * 0.94f).scaleY(sy * 0.94f).setDuration(90).withEndAction(() -> {
            v.animate().scaleX(sx).scaleY(sy).setDuration(160).setInterpolator(new OvershootInterpolator(2f)).start();
            r.run();
        }).start();
    }

    // ---------- açılışlar ----------

    private Drawable appIcon(String pkg) {
        try {
            return getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private void openApp(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) {
            toast("Uygulama yüklü değil");
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        safeStart(i);
    }

    private void openComponent(String pkg, String cls, String fallbackAction) {
        Intent i = new Intent().setComponent(new ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            safeStart(new Intent(fallbackAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    /**
     * Projeksiyonda tek fiziksel HDMI var; yazılım yine de HW1–3 diye üç giriş bildiriyor. Seçim yok:
     * 1) şu an sinyal veren giriş, 2) daha önce sinyal görülen (öğrenilen) giriş, 3) HDMI 3.
     */
    private String hdmiSource() {
        for (String s : SOURCES) if (hdmiConnected(s)) return s;
        String s = prefs.getString("hdmi", null);
        if (s == null || !s.startsWith("HDMI")) s = "HDMI3";
        return s;
    }

    private void updateHdmiSub() {
        boolean any = false;
        for (String s : SOURCES) any |= hdmiConnected(s);
        panels[2].setSub(any ? "● Görüntü var" : "HDMI girişi");
    }

    private void openHdmi() {
        // Üretici launcher'ı ile aynı yol: AwLiveTv + input_source
        Intent i = new Intent().setComponent(new ComponentName(LIVETV, LIVETV + ".MainActivity"))
                .putExtra("input_source", hdmiSource())
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        safeStart(i);
    }

    private void safeStart(Intent i) {
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            toast("Açılamadı");
        }
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------- Wi-Fi / Bluetooth / 4 köşe ekranlarının ortak parçaları ----------
    interface OverlayKeys { boolean onKey(KeyEvent e); }

    private OverlayKeys overlayKeys;
    private Runnable overlayClose;

    /** Açık katman kapanınca (ya da yerine başka katman gelince) çalışacak temizlik. */
    void setOverlayClose(Runnable r) { overlayClose = r; }

    void setOverlayKeys(OverlayKeys k) { overlayKeys = k; }

    private void runOverlayClose() {
        overlayKeys = null;
        Runnable r = overlayClose;
        overlayClose = null;
        if (r != null) r.run();
    }

    /** Liste satırı: solda ad, sağda soluk durum yazısı. */
    TextView newRow(String main, String right, Runnable action) {
        TextView b = Ui.text(this, 19, 0xFFFFFFFF, "sans-serif-medium");
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(main);
        if (right != null && !right.isEmpty()) {
            int st = sb.length();
            sb.append("    ").append(right);
            sb.setSpan(new android.text.style.RelativeSizeSpan(0.78f), st, sb.length(), 0); // renk odakla değişir
        }
        b.setText(sb);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(Ui.dp(24), Ui.dp(13), Ui.dp(24), Ui.dp(13));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(18));
        bg.setColor(0x14FFFFFF);
        b.setBackground(bg);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setClickable(true);
        b.setSoundEffectsEnabled(false);
        b.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xFFFFFFFF : 0x14FFFFFF);
            b.setTextColor(has ? 0xFF0B1220 : 0xFFFFFFFF);
        });
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private boolean isNetworkConnected() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            return nc != null && (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        } catch (Exception e) {
            return true;
        }
    }

    // ---------- katmanlar (ısı, diğer, uygulamalar) ----------

    LinearLayout newCard(String titleText) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(Ui.dp(40), Ui.dp(30), Ui.dp(40), Ui.dp(28));
        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(0xF0111827);
        cbg.setCornerRadius(Ui.dp(28));
        cbg.setStroke(Ui.dp(1), 0x33FFFFFF);
        card.setBackground(cbg);
        TextView title = Ui.text(this, 26, 0xFFFFFFFF, "sans-serif-medium");
        title.setText(titleText);
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    TextView newButton(String label, int widthDp, Runnable action) {
        TextView b = Ui.text(this, 18, 0xFFFFFFFF, "sans-serif-medium");
        b.setSoundEffectsEnabled(false);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setPadding(Ui.dp(28), Ui.dp(14), Ui.dp(28), Ui.dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(26));
        bg.setColor(0x26FFFFFF);
        b.setBackground(bg);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setClickable(true);
        b.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xFFFFFFFF : 0x26FFFFFF);
            b.setTextColor(has ? 0xFF0B1220 : 0xFFFFFFFF);
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(180).start();
        });
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(widthDp), -2);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = Ui.dp(12);
        b.setLayoutParams(lp);
        return b;
    }

    TextView newNote(String s) {
        TextView note = Ui.text(this, 15, 0x99FFFFFF, "sans-serif");
        note.setText(s);
        note.setGravity(Gravity.CENTER);
        note.setLineSpacing(Ui.dp(4), 1f);
        note.setPadding(0, Ui.dp(6), 0, Ui.dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        note.setLayoutParams(lp);
        return note;
    }

    void showOverlay(View card, View focusTarget) { showOverlay(card, focusTarget, false); }

    private boolean drawerOpen;

    /** drawer = alttan kayan çekmece (uygulamalar); değilse ortada açılan kart. */
    private void showOverlay(View card, View focusTarget, boolean drawer) {
        if (overlay != null) hideOverlayNow();
        View cur = getCurrentFocus();
        if (cur instanceof PanelView || cur instanceof BubbleView) focusBeforeOverlay = cur;
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(drawer ? 0x9005070D : 0xC005070D);
        overlay.setAlpha(0f);
        overlay.setClickable(true);
        drawerOpen = drawer;
        if (drawer) {
            overlay.addView(card, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            card.setTranslationY(Ui.dp(560));
            card.animate().translationY(0).setDuration(420).setInterpolator(new DecelerateInterpolator(2.4f)).start();
        } else {
            overlay.addView(card, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            card.setScaleX(0.9f);
            card.setScaleY(0.9f);
            card.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(new OvershootInterpolator(1.4f)).start();
        }
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        mute(overlay);
        overlay.animate().alpha(1f).setDuration(220).start();
        setHomeFocusable(false);
        focusTarget.requestFocus();
    }

    private void setHomeFocusable(boolean on) {
        if (on) overlayAnimated.clear();
        for (PanelView p : panels) p.setFocusable(on);
        for (BubbleView bv : bubbles) bv.setFocusable(on);
    }

    private void hideOverlayNow() {
        if (overlay == null) return;
        runOverlayClose();
        root.removeView(overlay);
        overlay = null;
        setHomeFocusable(true);
    }

    void hideOverlay() {
        if (overlay == null) return;
        runOverlayClose();
        final FrameLayout o = overlay;
        overlay = null;
        setHomeFocusable(true);
        o.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            if (o.getParent() != null) ((ViewGroup) o.getParent()).removeView(o);
        }).start();
        View back = focusBeforeOverlay != null && focusBeforeOverlay.isAttachedToWindow()
                && (focusBeforeOverlay instanceof PanelView || focusBeforeOverlay instanceof BubbleView)
                ? focusBeforeOverlay : settingsB;
        back.requestFocus();
    }

    private void showHeat() {
        LinearLayout card = newCard("Isı ve fan");
        LinearLayout gauges = new LinearLayout(this);
        gauges.setOrientation(LinearLayout.HORIZONTAL);
        gauges.setPadding(0, Ui.dp(18), 0, Ui.dp(4));
        for (int k : new int[]{GaugeView.CPU, GaugeView.GPU, GaugeView.FAN}) {
            GaugeView g = new GaugeView(this, k, sensors);
            gauges.addView(g);
            overlayAnimated.add(g);
        }
        card.addView(gauges, new LinearLayout.LayoutParams(-2, -2));
        card.addView(newNote("Fanı cihaz otomatik yönetiyor; bu modelde hızı yazılımla değiştirilemiyor.\n"
                + "Fan durursa cihaz kendi uyarısını gösterir."));
        TextView close = newButton("Kapat", 220, this::hideOverlay);
        card.addView(close);
        showOverlay(card, close);
        scheduleFrame(); // hareketsiz temada da göstergeler hemen canlansın
    }

    private void showMore() {
        showMore(0);
    }

    /** Diğer menüsü: iki sütun düğme (tek sütunda ekrana sığmıyordu). focusIdx = açılınca seçili düğme. */
    private void showMore(int focusIdx) {
        LinearLayout card = newCard("Ayarlar");
        card.setMinimumWidth(Ui.dp(560));
        final int W = 330;
        long left = SleepReceiver.remainingMin(this);
        TextView[] b = {
                newButton("Tüm uygulamalar", W, this::showApps),
                newButton("Yakınlaştırma, yansıtma (HTC)", W, () -> {
                    hideOverlay();
                    openComponent(HTC, "com.htc.activity.ProjectionActivity", Settings.ACTION_SETTINGS);
                }),
                newButton("Tema: " + Theme.cur.name.replaceAll(" \\(.*", ""), W, this::showThemes),
                newButton(left > 0 ? "Uyku (" + left + " dk)" : "Uyku zamanlayıcısı", W, this::showSleep),
                newButton("Arka plan temizliği: " + (prefs.getBoolean("temizle", true) ? "açık" : "kapalı"), W, () -> {
                    prefs.edit().putBoolean("temizle", !prefs.getBoolean("temizle", true)).apply();
                    showMore(4);
                }),
                newButton("Eski ana ekran (HTC)", W, () -> {
                    hideOverlay();
                    openComponent(HTC_HOME_PKG, "com.htc.activity.HomeActivity", Settings.ACTION_SETTINGS);
                }),
                newButton("İsim: " + (prefs.getString("isim", "").trim().isEmpty() ? "yok" : prefs.getString("isim", "").trim()),
                        W, this::showName),
        };
        for (int i = 0; i < b.length; i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER);
            for (int k = i; k < i + 2 && k < b.length; k++) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b[k].getLayoutParams();
                lp.setMargins(Ui.dp(8), Ui.dp(12), Ui.dp(8), 0);
                row.addView(b[k]);
            }
            card.addView(row, new LinearLayout.LayoutParams(-2, -2));
        }
        TextView apps = b[Math.max(0, Math.min(focusIdx, b.length - 1))];
        if (!MediaWatcher.granted(this)) {
            card.addView(newNote("\"Kaldığın yerden devam\" için bildirim erişimi izni verilmemiş."));
        }
        card.addView(newNote("Perde'yi tamamen bırakmak için: Ayarlar › Uygulamalar › Perde › Kaldır.\n"
                + "Kaldırınca eski ana ekran kendiliğinden geri gelir."));
        card.addView(newButton("Kapat", 220, this::hideOverlay));
        showOverlay(card, apps);
    }

    // ---------- görüntü: parlaklık, kontrast, renk, sıcaklık, profiller + 4 köşe (PictureScreen) ----------
    private void showPicture() {
        new PictureScreen(this).show();
    }

    // ---------- tema ----------
    /** Temanın üç panel rengini gösteren küçük renk örneği. */
    private static Drawable swatch(Theme t) {
        return new Drawable() {
            final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

            @Override
            public void draw(android.graphics.Canvas c) {
                android.graphics.Rect b = getBounds();
                float r = b.height() / 2f;
                for (int i = 0; i < 3; i++) {
                    p.setColor(t.panel[i][0]);
                    c.drawCircle(b.left + r + i * r * 1.5f, b.centerY(), r, p);
                }
            }

            @Override public int getIntrinsicWidth() { return Ui.dp(52); }
            @Override public int getIntrinsicHeight() { return Ui.dp(18); }
            @Override public void setAlpha(int a) { }
            @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }

    private void showThemes() {
        LinearLayout card = newCard("Tema");
        card.setMinimumWidth(Ui.dp(560));
        TextView focus = null;
        for (Theme t : Theme.ALL) {
            TextView b = newButton((t == Theme.cur ? "✓  " : "") + t.name, 460, () -> applyTheme(t));
            b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            b.setCompoundDrawablesWithIntrinsicBounds(swatch(t), null, null, null);
            b.setCompoundDrawablePadding(Ui.dp(18));
            card.addView(b);
            if (t == Theme.cur || focus == null) focus = b;
        }
        card.addView(newNote("Sade: sürekli hareket yok, yalnız geçişler. En az ısı ve işlemci."));
        card.addView(newButton("Vazgeç", 220, this::hideOverlay));
        showOverlay(card, focus);
    }

    private void applyTheme(Theme t) {
        if (t == Theme.cur) {
            hideOverlay();
            return;
        }
        Theme.save(this, t);
        recreate();
    }

    // ---------- arka plan temizliği ----------
    /** Ana ekranda 1 dk kalınca, arka planda kalan uygulamaların süreçleri kapatılır (YouTube ve Stremio hariç). */
    private static final long CLEAN_MS = 60_000;
    private final Runnable cleaner = this::cleanBackground;

    private static boolean keepAlive(String p) {
        return p.equals(YT) || p.equals(STREMIO) || p.equals("com.perde.launcher") || p.equals("android")
                || p.startsWith("com.htc.") || p.startsWith("com.softwinner.") || p.startsWith("com.android.")
                || p.equals("com.google.android.gms") || p.equals("com.google.android.gsf");
    }

    private void cleanBackground() {
        if (!resumed || !prefs.getBoolean("temizle", true)) return;
        new Thread(() -> {
            try {
                android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                PackageManager pm = getPackageManager();
                java.util.Set<String> pk = new java.util.TreeSet<>();
                for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                    for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                        pk.add(ri.activityInfo.packageName);
                    }
                }
                pk.add("com.google.android.katniss"); // sesli arama: gerekince kendiliğinden yeniden başlar
                for (String p : pk) if (!keepAlive(p)) am.killBackgroundProcesses(p);
            } catch (Throwable ignored) {
            }
        }, "perde-temizlik").start();
    }

    private void showApps() { showApps(false); }

    private void showApps(boolean drawer) {
        PackageManager pm = getPackageManager();
        Map<String, ResolveInfo> byPkg = new LinkedHashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            List<ResolveInfo> l = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0);
            for (ResolveInfo ri : l) {
                String p = ri.activityInfo.packageName;
                if (p.equals(getPackageName()) || byPkg.containsKey(p)) continue;
                byPkg.put(p, ri);
            }
        }
        final Collator col = Collator.getInstance(TR);
        List<ResolveInfo> list = new ArrayList<>(byPkg.values());
        List<String> labels = new ArrayList<>();
        Collections.sort(list, (a, b2) -> col.compare(String.valueOf(a.loadLabel(pm)), String.valueOf(b2.loadLabel(pm))));

        LinearLayout card = newCard("Uygulamalar");
        if (drawer) { // çekmece: alt köşeleri düz, üstü yuvarlak, tam genişlik
            GradientDrawable dbg = new GradientDrawable();
            dbg.setColor(0xF20B1020);
            float r = Ui.dp(28);
            dbg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
            dbg.setStroke(Ui.dp(1), 0x26FFFFFF);
            card.setBackground(dbg);
            card.setPadding(Ui.dp(40), Ui.dp(22), Ui.dp(40), Ui.dp(10));
        }
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(Ui.dp(10), Ui.dp(10), Ui.dp(10), Ui.dp(10));
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        final int perRow = 5;
        LinearLayout rowL = null;
        View first = null;
        for (int i = 0; i < list.size(); i++) {
            ResolveInfo ri = list.get(i);
            ActivityInfo ai = ri.activityInfo;
            Drawable banner = ai.loadBanner(pm);
            Drawable icon = ai.loadIcon(pm);
            String label = String.valueOf(ri.loadLabel(pm));
            final ComponentName cn = new ComponentName(ai.packageName, ai.name);
            AppTile t = new AppTile(this, label, banner, icon, v -> pulse(v, () -> {
                hideOverlayNow();
                safeStart(new Intent(Intent.ACTION_MAIN).setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }));
            if (i % perRow == 0) {
                rowL = new LinearLayout(this);
                rowL.setOrientation(LinearLayout.HORIZONTAL);
                rowL.setClipChildren(false);
                grid.addView(rowL);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(Ui.dp(8), Ui.dp(10), Ui.dp(8), Ui.dp(10));
            rowL.addView(t, lp);
            if (first == null) first = t;
        }
        ScrollView sv = new ScrollView(this);
        sv.setClipChildren(false);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(grid);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, Ui.dp(drawer ? 400 : 430));
        slp.topMargin = Ui.dp(10);
        card.addView(sv, slp);
        if (first == null) {
            card.addView(newNote("Uygulama bulunamadı"));
            first = newButton("Kapat", 220, this::hideOverlay);
            card.addView(first);
        }
        showOverlay(card, first, drawer);
    }
}
