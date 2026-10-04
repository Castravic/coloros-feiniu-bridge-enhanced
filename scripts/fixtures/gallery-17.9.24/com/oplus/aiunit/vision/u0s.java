// Trimmed shape of com.oplus.aiunit.vision.u0s (Gallery 17.9.24), the NAS backup state info.
//
// Anchors used by EnhancementLocator:
//   * the class extends the un-obfuscated SyncStateInfo
//   * instance field g holds the backup state (com.oplus.aiunit.vision.q0s)
//   * m(int, Context) produces the state text; l(Context) produces the notifications
//
// q0s$h (shown below) is the paused state: it is the state class that carries a PauseReason, which is
// how BackupPauseReasonTextHook recognises "paused" without a class-name list.
package com.oplus.aiunit.vision;

import android.content.Context;
import com.oplus.gallery.business_lib.cloudsync.SyncStateInfo;
import com.oplus.gallery.framework.abilities.cloudsync.nas.backup.state.PauseReason;

public class u0s extends SyncStateInfo {
    private q0s g;

    public u0s(q0s backupState) {
        this.g = backupState;
    }

    @Override
    public String m(int status, Context context) {
        return u0s$a.a(context, new u0s$a$a(0, 0, 0, 0), status, 0);
    }

    @Override
    public java.util.List l(Context context) {
        return u0s$a.b(context, false, false, new java.util.ArrayList());
    }

    public static final class q0s$h {
        private final PauseReason a;

        public q0s$h(PauseReason reason) {
            this.a = reason;
        }
    }
}
