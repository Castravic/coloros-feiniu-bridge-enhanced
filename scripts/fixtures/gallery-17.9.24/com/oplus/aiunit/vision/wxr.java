// Trimmed shape of com.oplus.aiunit.vision.wxr (Gallery 17.9.24), the NAS backup condition checker.
//
// Anchors used by EnhancementLocator:
//   * every method loads the "NasBackupCondChk" log tag
//   * a(boolean, boolean) is the raw checker, b(boolean, boolean) wraps it
//   * both return the un-obfuscated PauseReason enum
package com.oplus.aiunit.vision;

import com.oplus.gallery.framework.abilities.cloudsync.nas.backup.state.PauseReason;

public final class wxr {
    public final PauseReason a(boolean foreground, boolean forceRefresh) {
        if (!NetworkPermissionManager.d()) {
            return PauseReason.NETWORK_PERMISSION_DENIED;
        }
        if (!NetworkMonitor.e()) {
            return PauseReason.NO_WLAN;
        }
        if (ui90.a() > 0.0f) {
            return PauseReason.HIGH_TEMPERATURE;
        }
        if (ActivityLifecycle.c()) {
            return PauseReason.EXTERNAL_APP_FOREGROUND;
        }
        return PauseReason.NONE;
    }

    public final PauseReason b(boolean foreground, boolean forceRefresh) {
        long started = android.os.SystemClock.elapsedRealtime();
        PauseReason reason = a(foreground, forceRefresh);
        return reason;
    }
}
