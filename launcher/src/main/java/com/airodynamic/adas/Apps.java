package com.airodynamic.adas;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Opening other apps. This car can't show other apps inside a window, so they open
 * full screen and the Home button brings the launcher back.
 * Rule 2: video apps only open while the car is in P.
 */
public final class Apps {
    private Apps() {}

    public static final Set<String> VIDEO = new HashSet<>(Arrays.asList(
            "com.google.android.youtube", "com.gbox.com.google.android.youtube",
            "com.netflix.mediaclient", "com.zhiliaoapp.musically",
            "com.stingray.karaoke.car.byd6125f", "com.byd.zoomcar"));

    public static final class App {
        public final String pkg, label;
        App(String pkg, String label) { this.pkg = pkg; this.label = label; }
    }

    /** Every app with an icon, sorted by name (the picker shows names only). */
    public static List<App> all(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<App> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo r : pm.queryIntentActivities(i, 0)) {
            String p = r.activityInfo.packageName;
            if (p.equals(c.getPackageName()) || !seen.add(p)) continue;
            String label = String.valueOf(r.loadLabel(pm));
            if (p.startsWith("com.gbox.com.")) label = label + " (GBox)";
            out.add(new App(p, label));
        }
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    public static String label(Context c, String pkg) {
        if (Prefs.ADAS.equals(pkg)) return "ADAS";
        if (Prefs.ENGINE.equals(pkg)) return "Engine Sound";
        if (Prefs.INFO.equals(pkg)) return "Info";
        try {
            PackageManager pm = c.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return "—";
        }
    }

    public static boolean installed(Context c, String pkg) {
        return c.getPackageManager().getLaunchIntentForPackage(pkg) != null;
    }

    /** Opens an app full screen. Returns false (and says why) if it can't. */
    public static boolean open(Context c, String pkg, CarData.State car) {
        if (VIDEO.contains(pkg) && !car.parked()) {
            Toast.makeText(c, "Video apps open only in P", Toast.LENGTH_SHORT).show();
            return false;
        }
        Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) {
            Toast.makeText(c, label(c, pkg) + " is not installed", Toast.LENGTH_SHORT).show();
            return false;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        c.startActivity(i);
        return true;
    }
}
