package com.perde.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Kaldığın yerden devam": YouTube ve Stremio oynatırken sisteme yayınladıkları medya oturumunu (başlık, kapak,
 * konum) izler ve saklar. Oturum uygulamadan çıkınca silindiği için bilgiyi izleme sırasında yakalamak gerekiyor.
 * Çalışması için bildirim erişimi izni gerekir (TV ayarlarında sayfası yok; adb ile bir kez verilir).
 * Bildirimlerin kendisine hiç bakmaz.
 */
public class MediaWatcher extends NotificationListenerService {
    static final String[] PKGS = {MainActivity.YT, MainActivity.STREMIO};

    private MediaSessionManager msm;
    private final Map<MediaController, MediaController.Callback> tracked = new HashMap<>();

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = this::onSessions;

    @Override
    public void onListenerConnected() {
        msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        ComponentName cn = new ComponentName(this, MediaWatcher.class);
        try {
            msm.addOnActiveSessionsChangedListener(sessions, cn);
            onSessions(msm.getActiveSessions(cn));
        } catch (SecurityException ignored) {
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (msm != null) msm.removeOnActiveSessionsChangedListener(sessions);
        for (Map.Entry<MediaController, MediaController.Callback> e : tracked.entrySet())
            e.getKey().unregisterCallback(e.getValue());
        tracked.clear();
    }

    private static boolean watched(String pkg) {
        for (String p : PKGS) if (p.equals(pkg)) return true;
        return false;
    }

    private void onSessions(List<MediaController> list) {
        if (list == null) return;
        for (final MediaController mc : list) {
            if (!watched(mc.getPackageName())) continue;
            boolean known = false;
            for (MediaController k : tracked.keySet())
                if (k.getSessionToken().equals(mc.getSessionToken())) known = true;
            if (known) continue;
            MediaController.Callback cb = new MediaController.Callback() {
                @Override
                public void onMetadataChanged(MediaMetadata m) { save(mc, true); }

                @Override
                public void onPlaybackStateChanged(PlaybackState s) { save(mc, false); }

                @Override
                public void onSessionDestroyed() {
                    save(mc, false);
                    mc.unregisterCallback(this);
                    tracked.remove(mc);
                }
            };
            mc.registerCallback(cb);
            tracked.put(mc, cb);
            save(mc, true);
        }
    }

    /** Konumu, oynuyorsa son güncellemeden bu yana geçen süreyle tahmin eder. */
    private static long position(PlaybackState ps) {
        if (ps == null) return -1;
        long pos = ps.getPosition();
        if (ps.getState() == PlaybackState.STATE_PLAYING && ps.getLastPositionUpdateTime() > 0) {
            pos += (long) ((SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime()) * ps.getPlaybackSpeed());
        }
        return pos;
    }

    private void save(MediaController mc, boolean withArt) {
        try {
            MediaMetadata md = mc.getMetadata();
            if (md == null) return;
            String title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (title == null && md.getDescription() != null && md.getDescription().getTitle() != null)
                title = md.getDescription().getTitle().toString();
            if (title == null || title.trim().isEmpty()) return;
            String pkg = mc.getPackageName();
            SharedPreferences p = getSharedPreferences("devam", MODE_PRIVATE);
            boolean newTitle = !title.equals(p.getString(pkg + ".title", null));
            SharedPreferences.Editor e = p.edit()
                    .putString(pkg + ".title", title)
                    .putString(pkg + ".sub", nz(md.getString(MediaMetadata.METADATA_KEY_ARTIST),
                            md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)))
                    .putString(pkg + ".id", md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID))
                    .putLong(pkg + ".dur", md.getLong(MediaMetadata.METADATA_KEY_DURATION))
                    .putLong(pkg + ".time", System.currentTimeMillis());
            long pos = position(mc.getPlaybackState());
            if (pos >= 0) e.putLong(pkg + ".pos", pos);
            e.apply();
            if (withArt || newTitle) saveArt(pkg, md);
        } catch (Throwable ignored) {
        }
    }

    private static String nz(String a, String b) { return a != null ? a : b; }

    private void saveArt(String pkg, MediaMetadata md) {
        Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        File f = artFile(this, pkg);
        if (b == null) {
            f.delete();
            return;
        }
        float k = Math.min(1f, 720f / Math.max(b.getWidth(), b.getHeight()));
        Bitmap s = k < 1f ? Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k), Math.round(b.getHeight() * k), true) : b;
        try (FileOutputStream o = new FileOutputStream(f)) {
            s.compress(Bitmap.CompressFormat.JPEG, 88, o);
        } catch (Exception ignored) {
        }
    }

    static File artFile(Context c, String pkg) { return new File(c.getFilesDir(), "kapak_" + pkg + ".jpg"); }

    static Bitmap loadArt(Context c, String pkg) {
        File f = artFile(c, pkg);
        return f.exists() ? BitmapFactory.decodeFile(f.getPath()) : null;
    }

    static boolean granted(Context c) {
        try {
            android.app.NotificationManager nm = c.getSystemService(android.app.NotificationManager.class);
            return nm.isNotificationListenerAccessGranted(new ComponentName(c, MediaWatcher.class));
        } catch (Throwable t) {
            return false;
        }
    }
}
