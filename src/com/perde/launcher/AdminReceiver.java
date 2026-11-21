package com.perde.launcher;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;

/** Uyku zamanlayıcısının cihazı uyutabilmesi için cihaz yöneticisi (yalnız "force-lock" yetkisi). */
public class AdminReceiver extends DeviceAdminReceiver {
    static ComponentName cn(Context c) { return new ComponentName(c, AdminReceiver.class); }

    static boolean active(Context c) {
        DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isAdminActive(cn(c));
    }

    static void remove(Context c) {
        DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm != null && dpm.isAdminActive(cn(c))) dpm.removeActiveAdmin(cn(c));
    }

    /** Ekranı uyut (projeksiyon uyku moduna geçer; kumandanın güç tuşuyla uyanır). */
    static boolean sleepNow(Context c) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm == null || !dpm.isAdminActive(cn(c))) return false;
            dpm.lockNow();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
