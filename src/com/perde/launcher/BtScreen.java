package com.perde.launcher;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Perde görünümünde Bluetooth ekranı: aç/kapat, eşleşmiş cihazlar (bağlan / bağlantıyı kes / unut),
 * yakındaki cihazları arama ve eşleştirme. Hoparlöre bağlanma (A2DP connect) gizli bir sistem çağrısı;
 * Perde sistem uygulaması olduğu için HTC ayarlarının yaptığı gibi yansıma ile çağrılır.
 */
final class BtScreen {
    private final MainActivity a;
    private final BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
    private BluetoothProfile a2dp, hid;
    private LinearLayout list;
    private TextView toggle, status, scanB;
    private BroadcastReceiver rx;
    private final Map<String, BluetoothDevice> found = new LinkedHashMap<>();
    private final Map<String, String> busy = new LinkedHashMap<>(); // adres -> "bağlanıyor…" vb.
    private String focusKey;
    private boolean open, autoConnectAfterBond;

    BtScreen(MainActivity a) { this.a = a; }

    private final BluetoothProfile.ServiceListener proxy = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int p, BluetoothProfile prof) {
            if (p == BluetoothProfile.A2DP) a2dp = prof;
            else if (p == 4) hid = prof; // HID_HOST
            refresh();
        }

        @Override
        public void onServiceDisconnected(int p) {
            if (p == BluetoothProfile.A2DP) a2dp = null;
            else if (p == 4) hid = null;
        }
    };

    void show() {
        if (ad == null) {
            a.toast("Bluetooth bulunamadı");
            return;
        }
        LinearLayout card = a.newCard("Bluetooth");
        card.setMinimumWidth(Ui.dp(760));
        status = a.newNote("");
        card.addView(status);
        LinearLayout top = new LinearLayout(a);
        top.setGravity(android.view.Gravity.CENTER);
        toggle = a.newButton("", 300, this::toggleBt);
        scanB = a.newButton("Cihaz ara", 300, this::scan);
        ((LinearLayout.LayoutParams) toggle.getLayoutParams()).setMargins(Ui.dp(8), Ui.dp(12), Ui.dp(8), 0);
        ((LinearLayout.LayoutParams) scanB.getLayoutParams()).setMargins(Ui.dp(8), Ui.dp(12), Ui.dp(8), 0);
        top.addView(toggle);
        top.addView(scanB);
        card.addView(top, new LinearLayout.LayoutParams(-2, -2));
        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(Ui.dp(6), Ui.dp(6), Ui.dp(6), Ui.dp(6));
        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipChildren(false);
        sv.addView(list);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(Ui.dp(700), Ui.dp(390));
        slp.topMargin = Ui.dp(8);
        card.addView(sv, slp);
        card.addView(a.newButton("Kapat", 220, a::hideOverlay));
        a.showOverlay(card, toggle);
        a.setOverlayClose(this::close);
        open = true;

        try {
            ad.getProfileProxy(a.getApplicationContext(), proxy, BluetoothProfile.A2DP);
            ad.getProfileProxy(a.getApplicationContext(), proxy, 4);
        } catch (Throwable ignored) {
        }
        rx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String act = i.getAction();
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (BluetoothDevice.ACTION_FOUND.equals(act) && d != null) {
                    if (d.getBondState() != BluetoothDevice.BOND_BONDED && d.getName() != null) found.put(d.getAddress(), d);
                } else if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(act) && d != null) {
                    autoPair(d, i);
                } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(act) && d != null) {
                    int st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                    if (st == BluetoothDevice.BOND_BONDED) {
                        found.remove(d.getAddress());
                        busy.remove(d.getAddress());
                        if (autoConnectAfterBond) {
                            autoConnectAfterBond = false;
                            connect(d);
                        }
                    } else if (st == BluetoothDevice.BOND_NONE) {
                        if ("eşleşiyor…".equals(busy.remove(d.getAddress()))) a.toast(name(d) + ": eşleşme olmadı");
                    }
                } else if (d != null && ("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED".equals(act)
                        || BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(act))) {
                    int st = i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                    if (st != BluetoothProfile.STATE_CONNECTING && st != BluetoothProfile.STATE_DISCONNECTING) {
                        busy.remove(d.getAddress());
                    }
                    if (st == BluetoothProfile.STATE_CONNECTED) a.toast(name(d) + " bağlandı");
                }
                refresh();
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
        f.addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED");
        f.addAction("android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED");
        IntentFilter pf = new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
        pf.setPriority(1000);
        a.registerReceiver(rx, f);
        a.registerReceiver(rx, pf);
        skipCapture = true; // açılışta odak "aç/kapat"ta; önceki seçili satır (focusKey) korunsun
        refresh();
    }

    private boolean skipCapture;

    private void close() {
        open = false;
        if (rx != null) {
            try {
                a.unregisterReceiver(rx);
            } catch (Exception ignored) {
            }
            rx = null;
        }
        try {
            if (ad != null && ad.isDiscovering()) ad.cancelDiscovery();
            if (a2dp != null) ad.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
            if (hid != null) ad.closeProfileProxy(4, hid);
        } catch (Throwable ignored) {
        }
        a2dp = hid = null;
    }

    private void toggleBt() {
        focusKey = "toggle";
        try {
            boolean ok = ad.isEnabled() ? ad.disable() : ad.enable();
            if (!ok) a.toast("Bluetooth değiştirilemedi");
        } catch (Throwable t) {
            a.toast("Bluetooth değiştirilemedi");
        }
    }

    private void scan() {
        focusKey = "scan";
        if (!ad.isEnabled()) {
            a.toast("Önce Bluetooth'u aç");
            return;
        }
        try {
            if (ad.isDiscovering()) ad.cancelDiscovery();
            found.clear();
            if (!ad.startDiscovery()) a.toast("Arama başlatılamadı");
        } catch (Throwable t) {
            a.toast("Arama başlatılamadı");
        }
        refresh();
    }

    private static String name(BluetoothDevice d) {
        String n = null;
        try {
            Method m = d.getClass().getMethod("getAlias");
            n = (String) m.invoke(d);
        } catch (Throwable ignored) {
        }
        if (n == null || n.isEmpty()) n = d.getName();
        return n == null || n.isEmpty() ? d.getAddress() : n;
    }

    private static boolean isAudio(BluetoothDevice d) {
        BluetoothClass c = d.getBluetoothClass();
        if (c == null) return true;
        return c.getMajorDeviceClass() == BluetoothClass.Device.Major.AUDIO_VIDEO
                || c.hasService(BluetoothClass.Service.AUDIO) || c.hasService(BluetoothClass.Service.RENDER);
    }

    private static boolean isInput(BluetoothDevice d) {
        BluetoothClass c = d.getBluetoothClass();
        return c != null && c.getMajorDeviceClass() == BluetoothClass.Device.Major.PERIPHERAL;
    }

    private int state(BluetoothDevice d) {
        int best = BluetoothProfile.STATE_DISCONNECTED;
        for (BluetoothProfile p : new BluetoothProfile[]{a2dp, hid}) {
            if (p == null) continue;
            try {
                int s = p.getConnectionState(d);
                if (s == BluetoothProfile.STATE_CONNECTED) return s;
                if (s == BluetoothProfile.STATE_CONNECTING) best = s;
            } catch (Throwable ignored) {
            }
        }
        return best;
    }

    void refresh() {
        if (!open || list == null) return;
        View cur = a.getCurrentFocus();
        if (skipCapture) skipCapture = false;
        else if (cur != null && cur.getTag() instanceof String) focusKey = (String) cur.getTag();
        else if (cur == toggle) focusKey = "toggle";
        else if (cur == scanB) focusKey = "scan";

        int st = ad.getState();
        boolean on = st == BluetoothAdapter.STATE_ON;
        toggle.setText("Bluetooth: " + (on ? "açık" : st == BluetoothAdapter.STATE_TURNING_ON ? "açılıyor…"
                : st == BluetoothAdapter.STATE_TURNING_OFF ? "kapanıyor…" : "kapalı"));
        scanB.setText(on && ad.isDiscovering() ? "Aranıyor…" : "Cihaz ara");
        list.removeAllViews();
        View focusMe = null;
        if (!on) {
            status.setText("Bluetooth kapalı.");
        } else {
            List<BluetoothDevice> bonded = new ArrayList<>(ad.getBondedDevices());
            String connectedName = null;
            for (BluetoothDevice d : bonded) if (state(d) == BluetoothProfile.STATE_CONNECTED && connectedName == null) connectedName = name(d);
            status.setText(connectedName != null ? "Bağlı: " + connectedName : "Bir hoparlör ya da kulaklık seç.");
            if (!bonded.isEmpty()) list.addView(header("Eşleşmiş cihazlar"));
            for (BluetoothDevice d : bonded) {
                int s = state(d);
                String b = busy.get(d.getAddress());
                String right = b != null ? b : s == BluetoothProfile.STATE_CONNECTED ? "● bağlı"
                        : s == BluetoothProfile.STATE_CONNECTING ? "bağlanıyor…" : "bağlı değil";
                TextView row = a.newRow(name(d), right, () -> onBonded(d));
                row.setTag("bt:" + d.getAddress());
                list.addView(row);
                if (row.getTag().equals(focusKey)) focusMe = row;
            }
            list.addView(header(ad.isDiscovering() ? "Yakındaki cihazlar (aranıyor…)" : "Yakındaki cihazlar"));
            if (found.isEmpty()) {
                list.addView(a.newNote(ad.isDiscovering() ? "Hoparlörünü eşleşme moduna al…" : "\"Cihaz ara\" ile yeni cihaz bul."));
            }
            for (BluetoothDevice d : found.values()) {
                String b = busy.get(d.getAddress());
                TextView row = a.newRow(name(d), b != null ? b : "eşleştir", () -> pair(d));
                row.setTag("bt:" + d.getAddress());
                list.addView(row);
                if (row.getTag().equals(focusKey)) focusMe = row;
            }
        }
        if (focusMe != null) focusMe.requestFocus();
        else if ("toggle".equals(focusKey)) toggle.requestFocus();
        else if ("scan".equals(focusKey)) scanB.requestFocus();
    }

    private TextView header(String s) {
        TextView t = Ui.text(a, 14, 0x99FFFFFF, "sans-serif-medium");
        t.setText(s.toUpperCase(MainActivity.TR));
        t.setLetterSpacing(0.08f);
        t.setPadding(Ui.dp(14), Ui.dp(16), 0, Ui.dp(4));
        return t;
    }

    private void onBonded(BluetoothDevice d) {
        focusKey = "bt:" + d.getAddress();
        boolean conn = state(d) == BluetoothProfile.STATE_CONNECTED;
        LinearLayout card = a.newCard(name(d));
        card.setMinimumWidth(Ui.dp(520));
        TextView first = conn
                ? a.newButton("Bağlantıyı kes", 320, () -> { disconnect(d); back(); })
                : a.newButton("Bağlan", 320, () -> { connect(d); back(); });
        card.addView(first);
        if (isInput(d)) {
            card.addView(a.newNote("Bu bir giriş cihazı (kumanda/klavye). Unutulursa yeniden eşleştirmek gerekir."));
        }
        card.addView(a.newButton("Cihazı unut", 320, () -> {
            try {
                d.getClass().getMethod("removeBond").invoke(d);
                a.toast(name(d) + " unutuldu");
            } catch (Throwable t) {
                a.toast("Unutulamadı");
            }
            back();
        }));
        card.addView(a.newButton("Vazgeç", 220, this::back));
        a.showOverlay(card, first);
        a.setOverlayClose(this::close);
    }

    private void back() {
        close();
        show();
    }

    private void pair(BluetoothDevice d) {
        focusKey = "bt:" + d.getAddress();
        try {
            if (ad.isDiscovering()) ad.cancelDiscovery(); // arama sürerken eşleşme yavaşlar
            busy.put(d.getAddress(), "eşleşiyor…");
            autoConnectAfterBond = isAudio(d);
            if (!d.createBond()) {
                busy.remove(d.getAddress());
                a.toast("Eşleşme başlatılamadı");
            }
        } catch (Throwable t) {
            busy.remove(d.getAddress());
            a.toast("Eşleşme başlatılamadı");
        }
        refresh();
    }

    /** Hoparlörlerin çoğu "onay" ya da sabit PIN ister: onayı Perde verir, sistem penceresi açılmaz. */
    private void autoPair(BluetoothDevice d, Intent i) {
        int v = i.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1);
        try {
            if (v == BluetoothDevice.PAIRING_VARIANT_PIN) {
                d.setPin("0000".getBytes());
            } else if (v == BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION || v == 3 /* CONSENT */) {
                d.setPairingConfirmation(true);
            } else {
                return; // başka türler (ekranda kod girme) sistem penceresine kalsın
            }
            if (rx != null && rx.isOrderedBroadcast()) rx.abortBroadcast();
        } catch (Throwable ignored) {
        }
    }

    private void connect(BluetoothDevice d) {
        focusKey = "bt:" + d.getAddress();
        BluetoothProfile p = isInput(d) && hid != null ? hid : a2dp;
        if (p == null) {
            a.toast("Ses servisi hazır değil, tekrar dene");
            return;
        }
        try {
            if (ad.isDiscovering()) ad.cancelDiscovery();
            Method m = p.getClass().getMethod("connect", BluetoothDevice.class);
            boolean ok = (Boolean) m.invoke(p, d);
            android.util.Log.i("Perde", "BT connect " + d.getAddress() + " → " + ok);
            if (ok) busy.put(d.getAddress(), "bağlanıyor…");
            else a.toast(name(d) + ": bağlanılamadı (açık ve yakında mı?)");
        } catch (Throwable t) {
            android.util.Log.w("Perde", "BT connect hata", t);
            a.toast("Bağlanılamadı");
        }
        refresh();
    }

    private void disconnect(BluetoothDevice d) {
        for (BluetoothProfile p : new BluetoothProfile[]{a2dp, hid}) {
            if (p == null) continue;
            try {
                if (p.getConnectionState(d) != BluetoothProfile.STATE_DISCONNECTED) {
                    p.getClass().getMethod("disconnect", BluetoothDevice.class).invoke(p, d);
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
