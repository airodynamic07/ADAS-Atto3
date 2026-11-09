package com.airodynamic.adasprobe;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.media.MediaDrm;
import android.os.Build;
import android.provider.Settings;
import android.util.DisplayMetrics;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.UUID;

/** Versions, screen, installed apps, multi-window/freeform, DRM. */
public final class SystemProbe {
    private SystemProbe() {}

    public static final String[][] APPS = {
            {"Google Maps", "com.google.android.apps.maps"},
            {"YouTube", "com.google.android.youtube"},
            {"Netflix", "com.netflix.mediaclient"},
            {"Google Play services", "com.google.android.gms"},
            {"Google Play Store", "com.android.vending"},
            {"Google app / Gemini", "com.google.android.googlequicksearchbox"},
            {"Gemini", "com.google.android.apps.bard"},
            {"Chrome", "com.android.chrome"},
            {"Android System WebView", "com.google.android.webview"},
            {"OverDrive", "com.overdrive.app"},
            {"Atto 3 Engine Sound (has Ghost Race)", "com.air.atto3sound"},
            {"BYD 360 (apa)", "com.byd.apa"},
            {"BYD 360 (avm)", "com.byd.avm"},
            {"Google TTS", "com.google.android.tts"},
    };

    public static void run(Context ctx, Report r) {
        r.section("Versions");
        r.info("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        r.info("Build.DISPLAY = " + Build.DISPLAY);
        r.info("Model = " + Build.MANUFACTURER + " / " + Build.MODEL + " / " + Build.PRODUCT + " / " + Build.DEVICE);
        r.info("Board/Hardware = " + Build.BOARD + " / " + Build.HARDWARE + "  SoC ABI " + Build.SUPPORTED_ABIS[0]);
        r.info("Fingerprint = " + Build.FINGERPRINT);
        r.info("Our process uid = " + android.os.Process.myUid());

        r.section("System properties that look like DiLink / car info");
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"getprop"});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            int n = 0;
            while ((line = br.readLine()) != null) {
                String l = line.toLowerCase();
                if (l.contains("dilink") || l.contains("byd") || l.contains("vehicle") || l.contains("car")
                        || l.contains("version") || l.contains("ro.product") || l.contains("ro.board")
                        || l.contains("cam") || l.contains("avm") || l.contains("freeform")) {
                    r.info(line);
                    n++;
                }
            }
            r.info("(" + n + " properties)");
        } catch (Throwable t) {
            r.error("getprop", t);
        }

        r.section("Screen");
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        r.info(dm.widthPixels + " x " + dm.heightPixels + " px, density " + dm.density + " (" + dm.densityDpi + " dpi)");

        r.section("Apps");
        PackageManager pm = ctx.getPackageManager();
        for (String[] a : APPS) {
            try {
                PackageInfo pi = pm.getPackageInfo(a[1], 0);
                ApplicationInfo ai = pi.applicationInfo;
                r.ok(a[0], "installed v" + pi.versionName + (ai.enabled ? "" : " (DISABLED)"));
            } catch (PackageManager.NameNotFoundException e) {
                r.fail(a[0], "not installed");
            }
        }
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo def = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        r.info("Current home screen app: " + (def == null ? "?" : def.activityInfo.packageName));
        List<ResolveInfo> homes = pm.queryIntentActivities(home, 0);
        for (ResolveInfo ri : homes) r.info("  home candidate: " + ri.activityInfo.packageName);
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        r.info("Apps with a launcher icon: " + pm.queryIntentActivities(launcher, 0).size());

        r.section("Multi-window / freeform");
        r.info("FEATURE_FREEFORM_WINDOW_MANAGEMENT = "
                + pm.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT));
        r.info("FEATURE_PICTURE_IN_PICTURE = " + pm.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE));
        for (String k : new String[]{"enable_freeform_support", "force_resizable_activities",
                "development_settings_enabled"}) {
            r.info("Settings.Global." + k + " = " + Settings.Global.getString(ctx.getContentResolver(), k));
        }
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        r.info("Low RAM device = " + am.isLowRamDevice() + ", memory class " + am.getMemoryClass() + " MB");
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        r.info("RAM total " + mi.totalMem / (1024 * 1024) + " MB, free " + mi.availMem / (1024 * 1024) + " MB");

        r.section("DRM (Netflix needs Widevine)");
        try {
            MediaDrm drm = new MediaDrm(UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"));
            r.ok("Widevine securityLevel", drm.getPropertyString("securityLevel"));
            r.info("Widevine version " + drm.getPropertyString("version"));
            drm.close();
        } catch (Throwable t) {
            r.error("Widevine", t);
        }
    }

    /**
     * Opens an app in a freeform window (left third of the screen) if the
     * system allows it. Returns a message for the report.
     */
    public static String openInWindow(Context ctx, String pkg, int slot) {
        PackageManager pm = ctx.getPackageManager();
        Intent i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return pkg + " is not installed";
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int third = dm.widthPixels / 3;
        Rect bounds = new Rect(third * slot, dm.heightPixels / 10, third * (slot + 1), dm.heightPixels);
        try {
            ActivityOptions o = ActivityOptions.makeBasic();
            o.setLaunchBounds(bounds);
            try {
                // WINDOWING_MODE_FREEFORM = 5 (hidden API on Android 10)
                ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class).invoke(o, 5);
            } catch (Throwable ignored) {}
            ctx.startActivity(i, o.toBundle());
            return "Asked to open " + pkg + " in window " + bounds.toShortString()
                    + ". Look at the screen: is it in a window or full screen?";
        } catch (Throwable t) {
            return "Failed to open " + pkg + ": " + t;
        }
    }
}
