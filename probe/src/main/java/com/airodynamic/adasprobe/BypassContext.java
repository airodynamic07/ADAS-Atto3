package com.airodynamic.adasprobe;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Looper;

/**
 * The BYD car classes check permissions on the calling side (inside our own
 * process). This wrapper answers "granted" to those checks — the same approach
 * OverDrive uses (MIT licence, see NOTICE). Phase 0 tests whether the car
 * services also enforce on their side.
 */
public final class BypassContext extends ContextWrapper {
    public BypassContext(Context base) { super(base); }

    @Override public void enforceCallingOrSelfPermission(String p, String m) {}
    @Override public void enforcePermission(String p, int pid, int uid, String m) {}
    @Override public void enforceCallingPermission(String p, String m) {}
    @Override public int checkCallingOrSelfPermission(String p) { return PackageManager.PERMISSION_GRANTED; }
    @Override public int checkPermission(String p, int pid, int uid) { return PackageManager.PERMISSION_GRANTED; }
    @Override public int checkSelfPermission(String p) { return PackageManager.PERMISSION_GRANTED; }

    @Override public Looper getMainLooper() {
        try {
            Looper l = super.getMainLooper();
            if (l != null) return l;
        } catch (NullPointerException ignored) {}
        Looper l = Looper.getMainLooper();
        return l != null ? l : Looper.myLooper();
    }

    @Override public Context getApplicationContext() {
        try {
            Context c = super.getApplicationContext();
            return c != null ? c : this;
        } catch (NullPointerException e) {
            return this;
        }
    }
}
