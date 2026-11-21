package com.perde.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Perde görünümünde Wi-Fi ekranı: aç/kapat, ağ listesi, şifreyle bağlanma, unutma.
 * Sistem uygulaması (android.uid.system) olarak çalıştığı için WifiManager'ın ayrıcalıklı yolları açık
 * (addNetwork/enableNetwork, konum izni olmadan tarama sonuçları) — HTC ayarlarının yaptığıyla aynı.
 */
final class WifiScreen {
    private final MainActivity a;
    private final WifiManager wm;
    private LinearLayout list;
    private TextView toggle, status;
    private BroadcastReceiver rx;
    private String focusKey;          // yeniden çizimden sonra aynı satıra dönmek için
    private String pendingSsid;       // şifre girilip bağlanılmaya çalışılan ağ
    private int pendingNetId = -1;
    private boolean open;

    private final Runnable rescan = new Runnable() {
        @Override
        public void run() {
            if (!open) return;
            try {
                wm.startScan();
            } catch (Throwable ignored) {
            }
            a.h.postDelayed(this, 12_000);
        }
    };

    WifiScreen(MainActivity a) {
        this.a = a;
        this.wm = (WifiManager) a.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    void show() {
        LinearLayout card = a.newCard("Wi-Fi");
        card.setMinimumWidth(Ui.dp(760));
        status = a.newNote("");
        card.addView(status);
        toggle = a.newButton("", 360, this::toggleWifi);
        card.addView(toggle);
        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(Ui.dp(6), Ui.dp(6), Ui.dp(6), Ui.dp(6));
        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipChildren(false);
        sv.addView(list);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(Ui.dp(700), Ui.dp(400));
        slp.topMargin = Ui.dp(8);
        card.addView(sv, slp);
        card.addView(a.newButton("Kapat", 220, a::hideOverlay));
        a.showOverlay(card, toggle);
        a.setOverlayClose(this::close);
        open = true;

        rx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (WifiManager.SUPPLICANT_STATE_CHANGED_ACTION.equals(i.getAction())) {
                    int err = i.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, 0);
                    if (err == WifiManager.ERROR_AUTHENTICATING && pendingSsid != null) wrongPassword();
                }
                refresh();
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        f.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        a.registerReceiver(rx, f);
        skipCapture = true; // açılışta odak "aç/kapat"ta; önceki seçili satır (focusKey) korunsun
        refresh();
        a.h.post(rescan);
    }

    private boolean skipCapture;

    private void close() {
        open = false;
        a.h.removeCallbacks(rescan);
        if (rx != null) {
            try {
                a.unregisterReceiver(rx);
            } catch (Exception ignored) {
            }
            rx = null;
        }
    }

    private void toggleWifi() {
        boolean on = wm.isWifiEnabled();
        try {
            if (!wm.setWifiEnabled(!on)) a.toast("Wi-Fi değiştirilemedi");
        } catch (Throwable t) {
            a.toast("Wi-Fi değiştirilemedi");
        }
        focusKey = "toggle";
        a.h.postDelayed(this::refresh, 600);
    }

    private static String clean(String ssid) {
        if (ssid == null) return "";
        if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) return ssid.substring(1, ssid.length() - 1);
        return ssid;
    }

    private static String bars(int level) { // 0..3
        String[] b = {"▂", "▂▄", "▂▄▆", "▂▄▆█"};
        return b[Math.max(0, Math.min(3, level))];
    }

    private static int security(ScanResult r) {
        String c = r.capabilities == null ? "" : r.capabilities;
        if (c.contains("EAP")) return 3;
        if (c.contains("SAE") && !c.contains("PSK")) return 4;
        if (c.contains("PSK") || c.contains("SAE")) return 2;
        if (c.contains("WEP")) return 1;
        return 0;
    }

    private Map<String, WifiConfiguration> saved() {
        Map<String, WifiConfiguration> m = new LinkedHashMap<>();
        try {
            List<WifiConfiguration> l = wm.getConfiguredNetworks();
            if (l != null) for (WifiConfiguration c : l) m.put(clean(c.SSID), c);
        } catch (Throwable ignored) {
        }
        return m;
    }

    void refresh() {
        if (!open || list == null) return;
        View cur = a.getCurrentFocus();
        if (skipCapture) skipCapture = false;
        else if (cur != null && cur.getTag() instanceof String) focusKey = (String) cur.getTag();
        else if (cur == toggle) focusKey = "toggle";

        boolean on = wm.isWifiEnabled();
        toggle.setText("Wi-Fi: " + (on ? "açık" : "kapalı"));
        WifiInfo info = on ? wm.getConnectionInfo() : null;
        String connected = null;
        if (info != null && info.getNetworkId() != -1 && info.getSupplicantState() == SupplicantState.COMPLETED) {
            connected = clean(info.getSSID());
        }
        if (!on) status.setText("Wi-Fi kapalı.");
        else if (connected != null) status.setText("Bağlı: " + connected);
        else if (pendingSsid != null) status.setText(pendingSsid + " ağına bağlanılıyor…");
        else status.setText("Bir ağ seç.");
        if (connected != null && connected.equals(pendingSsid)) {
            pendingSsid = null;
            pendingNetId = -1;
        }

        list.removeAllViews();
        if (!on) return;
        Map<String, WifiConfiguration> saved = saved();
        Map<String, ScanResult> best = new LinkedHashMap<>();
        List<ScanResult> results;
        try {
            results = wm.getScanResults();
        } catch (Throwable t) {
            results = new ArrayList<>();
        }
        for (ScanResult r : results) {
            if (r.SSID == null || r.SSID.isEmpty()) continue;
            ScanResult o = best.get(r.SSID);
            if (o == null || r.level > o.level) best.put(r.SSID, r);
        }
        List<ScanResult> sorted = new ArrayList<>(best.values());
        final String conn = connected;
        Collections.sort(sorted, (x, y) -> {
            int px = x.SSID.equals(conn) ? 0 : saved.containsKey(x.SSID) ? 1 : 2;
            int py = y.SSID.equals(conn) ? 0 : saved.containsKey(y.SSID) ? 1 : 2;
            if (px != py) return px - py;
            return y.level - x.level;
        });
        View focusMe = null;
        for (ScanResult r : sorted) {
            boolean isConn = r.SSID.equals(conn), isSaved = saved.containsKey(r.SSID);
            int sec = security(r);
            String right = bars(WifiManager.calculateSignalLevel(r.level, 4))
                    + (isConn ? "   ● bağlı" : isSaved ? "   kayıtlı" : sec > 0 ? "   şifreli" : "   açık ağ");
            TextView row = a.newRow(r.SSID, right, () -> onPick(r, isConn, saved.get(r.SSID)));
            row.setTag("ag:" + r.SSID);
            list.addView(row);
            if (("ag:" + r.SSID).equals(focusKey)) focusMe = row;
        }
        if (sorted.isEmpty()) list.addView(a.newNote("Ağ aranıyor…"));
        if (focusMe != null) focusMe.requestFocus();
        else if ("toggle".equals(focusKey)) toggle.requestFocus();
    }

    private void onPick(ScanResult r, boolean isConn, WifiConfiguration cfg) {
        focusKey = "ag:" + r.SSID;
        if (isConn || cfg != null) {
            LinearLayout card = a.newCard(r.SSID);
            card.setMinimumWidth(Ui.dp(520));
            TextView first;
            if (isConn) {
                first = a.newButton("Bağlantıyı kes", 320, () -> {
                    wm.disconnect();
                    back();
                });
            } else {
                first = a.newButton("Bağlan", 320, () -> {
                    connectSaved(cfg.networkId, r.SSID);
                    back();
                });
            }
            card.addView(first);
            if (cfg != null) {
                card.addView(a.newButton("Ağı unut", 320, () -> {
                    wm.removeNetwork(cfg.networkId);
                    a.toast(r.SSID + " unutuldu");
                    back();
                }));
            }
            if (!isConn && security(r) > 0 && security(r) != 3) {
                card.addView(a.newButton("Şifreyi değiştir", 320, () -> askPassword(r)));
            }
            card.addView(a.newButton("Vazgeç", 220, this::back));
            a.showOverlay(card, first);
            a.setOverlayClose(this::close);
            open = true;
            return;
        }
        int sec = security(r);
        if (sec == 3) {
            a.toast("Kurumsal (EAP) ağlar desteklenmiyor");
            return;
        }
        if (sec == 0) {
            connectNew(r, null);
            return;
        }
        askPassword(r);
    }

    /** Wi-Fi ekranına geri dön (alt kart kapanır, liste yeniden kurulur). */
    private void back() {
        close();
        show();
    }

    private void connectSaved(int netId, String ssid) {
        pendingSsid = ssid;
        pendingNetId = -1; // kayıtlı ağ: şifre yanlışsa silinmez
        if (!wm.enableNetwork(netId, true)) a.toast("Bağlanılamadı");
        wm.reconnect();
    }

    private void askPassword(ScanResult r) {
        LinearLayout card = a.newCard(r.SSID);
        card.setMinimumWidth(Ui.dp(620));
        card.addView(a.newNote("Şifreyi gir. OK ile klavye açılır."));
        final EditText pw = new EditText(a);
        pw.setSingleLine(true);
        pw.setTextSize(22);
        pw.setTextColor(0xFFFFFFFF);
        pw.setHintTextColor(0x66FFFFFF);
        pw.setHint("Şifre");
        pw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        pw.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        pw.setSoundEffectsEnabled(false);
        pw.setGravity(Gravity.CENTER);
        pw.setPadding(Ui.dp(20), Ui.dp(14), Ui.dp(20), Ui.dp(14));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(Ui.dp(18));
        bg.setColor(0x26FFFFFF);
        pw.setBackground(bg);
        pw.setOnFocusChangeListener((v, has) -> bg.setStroke(Ui.dp(2), has ? 0xFFFFFFFF : 0x00FFFFFF));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(520), -2);
        lp.topMargin = Ui.dp(10);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(pw, lp);
        final boolean[] shown = {true};
        TextView eye = a.newButton("Şifre: görünür", 320, null);
        eye.setOnClickListener(v -> {
            shown[0] = !shown[0];
            pw.setInputType(InputType.TYPE_CLASS_TEXT | (shown[0] ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            pw.setSelection(pw.length());
            eye.setText("Şifre: " + (shown[0] ? "görünür" : "gizli"));
        });
        Runnable go = () -> {
            String s = pw.getText().toString();
            int sec = security(r);
            if ((sec == 2 || sec == 4) && (s.length() < 8 || s.length() > 63)) {
                a.toast("Şifre 8–63 karakter olmalı");
                return;
            }
            if (s.isEmpty()) {
                a.toast("Şifre boş");
                return;
            }
            hideIme(pw);
            connectNew(r, s);
            back();
        };
        TextView connect = a.newButton("Bağlan", 320, go);
        pw.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_DONE || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                hideIme(pw);
                connect.requestFocus();
                return true;
            }
            return false;
        });
        card.addView(connect);
        card.addView(eye);
        card.addView(a.newButton("Vazgeç", 220, () -> {
            hideIme(pw);
            back();
        }));
        a.showOverlay(card, pw);
        a.setOverlayClose(this::close);
        open = true;
        pw.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(pw, 0);
        }, 250);
    }

    private void hideIme(View v) {
        InputMethodManager imm = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private void connectNew(ScanResult r, String pass) {
        // aynı adla eski kayıt varsa sil (şifre değişmiş olabilir)
        WifiConfiguration old = saved().get(r.SSID);
        if (old != null) wm.removeNetwork(old.networkId);
        WifiConfiguration c = new WifiConfiguration();
        c.SSID = "\"" + r.SSID + "\"";
        int sec = security(r);
        if (sec == 4) {
            c.setSecurityParams(WifiConfiguration.SECURITY_TYPE_SAE);
            c.preSharedKey = "\"" + pass + "\"";
        } else if (sec == 2) {
            c.setSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK);
            c.preSharedKey = "\"" + pass + "\"";
        } else if (sec == 1) {
            c.setSecurityParams(WifiConfiguration.SECURITY_TYPE_WEP);
            boolean hex = pass.matches("[0-9A-Fa-f]{10}|[0-9A-Fa-f]{26}");
            c.wepKeys[0] = hex ? pass : "\"" + pass + "\"";
            c.wepTxKeyIndex = 0;
        } else {
            c.setSecurityParams(WifiConfiguration.SECURITY_TYPE_OPEN);
        }
        int id = wm.addNetwork(c);
        if (id == -1) {
            a.toast("Ağ eklenemedi");
            return;
        }
        pendingSsid = r.SSID;
        pendingNetId = id;
        if (!wm.enableNetwork(id, true)) a.toast("Bağlanılamadı");
        wm.reconnect();
    }

    private void wrongPassword() {
        String s = pendingSsid;
        if (pendingNetId != -1) wm.removeNetwork(pendingNetId); // yeni eklenen yanlış şifreli kaydı bırakma
        pendingSsid = null;
        pendingNetId = -1;
        a.toast(s + ": şifre yanlış");
    }

    static boolean isConnectedInfo(NetworkInfo ni) {
        return ni != null && ni.isConnected();
    }
}
