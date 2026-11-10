package com.airodynamic.adasprobe;

import android.content.Context;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;

import dadb.AdbKeyPair;
import dadb.AdbShellResponse;
import dadb.Dadb;

/**
 * Starts the shell helper through the car's own ADB (127.0.0.1:5555).
 * The first time, the car screen asks "Allow USB debugging?" — tick
 * "Always allow from this computer" and tap OK.
 */
public final class HelperLauncher {
    private HelperLauncher() {}

    /** Where the helper (shell user) writes; the app copies the files back afterwards. */
    public static final String HELPER_DIR = "/data/local/tmp/atto3_probe";

    /** bmmcamera.jar first (camera classes, as OverDrive does), then our APK. */
    public static String command(Context ctx, String task) {
        String apk = ctx.getApplicationInfo().sourceDir;
        String cp = (new File(CameraProbe.BMM_JAR).isFile() ? CameraProbe.BMM_JAR + ":" : "") + apk;
        return "CLASSPATH=" + cp + " app_process /system/bin " + ShellMain.class.getName() + " " + task + " " + HELPER_DIR;
    }

    /** The same command for Termux (adb shell). */
    public static String termuxCommand(String task) {
        return "adb shell 'CLASSPATH=" + CameraProbe.BMM_JAR + ":$(pm path " + ShellMain.PKG
                + " | cut -d: -f2) app_process /system/bin " + ShellMain.class.getName() + " " + task + "'";
    }

    private static boolean portOpen(String host, int port, int ms) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), ms);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static AdbKeyPair keys(Context ctx) throws Exception {
        File priv = new File(ctx.getFilesDir(), "adbkey");
        File pub = new File(ctx.getFilesDir(), "adbkey.pub");
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub);
        return AdbKeyPair.read(priv, pub);
    }

    /** Runs one shell command as the shell user (more file access than the app). Null if ADB is unavailable. */
    public static String shell(Context ctx, String cmd, Report r) {
        if (!portOpen("127.0.0.1", 5555, 3000)) {
            r.info("ADB port 5555 closed, cannot use the helper for this.");
            return null;
        }
        Dadb dadb = null;
        try {
            dadb = Dadb.create("127.0.0.1", 5555, keys(ctx), 5000, 60000);
            return dadb.shell(cmd + " 2>&1").getAllOutput();
        } catch (Throwable t) {
            r.error("ADB shell", t);
            return null;
        } finally {
            if (dadb != null) try { dadb.close(); } catch (Throwable ignored) {}
        }
    }

    /** Installs an APK over this app through the car's own ADB (no prompt; data is kept). */
    public static boolean install(Context ctx, File apk, Report r) {
        if (!portOpen("127.0.0.1", 5555, 3000)) {
            r.fail("install", "the car's ADB port 5555 is closed");
            return false;
        }
        Dadb dadb = null;
        try {
            dadb = Dadb.create("127.0.0.1", 5555, keys(ctx), 5000, 120000);
            r.info("Installing... the app will close and reopen by itself in a few seconds.");
            // Reopen the app after the install replaces it.
            dadb.shell("setsid sh -c 'sleep 15; am start -n " + ShellMain.PKG + "/.ProbeActivity' "
                    + "</dev/null >/dev/null 2>&1 &");
            dadb.install(apk, "-r");
            return true;
        } catch (Throwable t) {
            r.error("install", t);
            return false;
        } finally {
            if (dadb != null) try { dadb.close(); } catch (Throwable ignored) {}
        }
    }

    /** Installs another APK (the launcher) through the car's ADB; this app keeps running. */
    public static boolean installOther(Context ctx, File apk, Report r) {
        if (!portOpen("127.0.0.1", 5555, 3000)) {
            r.fail("install", "the car's ADB port 5555 is closed — install the file from the USB stick instead");
            return false;
        }
        try (Dadb dadb = Dadb.create("127.0.0.1", 5555, keys(ctx), 5000, 180000)) {
            r.info("Installing " + apk.getName() + " (" + (apk.length() / 1024) + " KB) through the car's ADB...");
            // push + pm install so Android's exact answer (e.g. INSTALL_FAILED_...) is shown
            String remote = "/data/local/tmp/adas_install.apk";
            dadb.push(apk, remote, 0644, System.currentTimeMillis());
            AdbShellResponse res = dadb.shell("pm install -r -d " + remote + " 2>&1; rm -f " + remote);
            String out = res.getAllOutput().trim();
            r.info("Android says: " + out);
            boolean ok = out.contains("Success");
            if (ok) r.ok("install", "done");
            else r.fail("install", explain(out));
            return ok;
        } catch (Throwable t) {
            r.error("install", t);
            return false;
        }
    }

    /** Plain-language meaning of the most common install errors. */
    static String explain(String out) {
        if (out.contains("UPDATE_INCOMPATIBLE") || out.contains("signatures do not match"))
            return "the installed version was signed with a different key. Uninstall the old app first (your settings in it will be lost).";
        if (out.contains("INSUFFICIENT_STORAGE")) return "the car's storage is full. Free some space (Settings > Storage).";
        if (out.contains("VERSION_DOWNGRADE")) return "the car has a newer version than this file.";
        if (out.contains("OLDER_SDK") || out.contains("NO_MATCHING_ABIS")) return "this file doesn't fit this car's Android version or chip.";
        if (out.contains("INVALID_APK") || out.contains("PARSE")) return "the file is damaged or not a real APK. Download it again.";
        return "see the message above and send me a photo.";
    }

    public static String run(Context ctx, String task, int timeoutSec, Report r) throws Exception {
        r.info("Checking the car's ADB port 5555 ...");
        boolean p5555 = portOpen("127.0.0.1", 5555, 3000);
        r.info("  127.0.0.1:5555 " + (p5555 ? "OPEN" : "CLOSED"));
        if (!p5555) {
            r.fail("ADB", "wireless ADB is not listening on port 5555, so the helper cannot start. "
                    + "Car data still works from the app; camera needs the helper.");
            return "";
        }
        File priv = new File(ctx.getFilesDir(), "adbkey");
        File pub = new File(ctx.getFilesDir(), "adbkey.pub");
        if (!priv.exists() || !pub.exists()) {
            r.info("Creating this app's ADB key (first run)...");
            AdbKeyPair.generate(priv, pub);
        }
        AdbKeyPair keys = AdbKeyPair.read(priv, pub);
        r.info("Connecting ... LOOK AT THE CAR SCREEN NOW: if it asks 'Allow USB debugging?', "
                + "tick 'Always allow' and tap OK. (waiting up to 60 s)");
        // connect timeout 5 s; read timeout 60 s covers waiting for you to accept the prompt
        Dadb dadb = Dadb.create("127.0.0.1", 5555, keys, 5000, 60000);
        try {
            AdbShellResponse who = dadb.shell("id");
            r.ok("ADB connected", who.getOutput().trim());
        } catch (Throwable t) {
            try { dadb.close(); } catch (Throwable ignored) {}
            r.fail("ADB", "no answer — the 'Allow USB debugging?' prompt was not accepted, or ADB refused this app ("
                    + t.getClass().getSimpleName() + ")");
            return "";
        }
        // A fresh connection with a long read timeout for the actual task.
        try { dadb.close(); } catch (Throwable ignored) {}
        dadb = Dadb.create("127.0.0.1", 5555, keys, 5000, (timeoutSec + 30) * 1000);
        try {
            dadb.shell("mkdir -p " + HELPER_DIR);
            String cmd = "timeout " + timeoutSec + " sh -c '" + command(ctx, task) + "' 2>&1";
            r.info("Running helper task '" + task + "' (up to " + timeoutSec + " s)...");
            AdbShellResponse res = dadb.shell(cmd);
            r.info("helper exit code " + res.getExitCode());
            pullResults(dadb, r);
            return res.getAllOutput();
        } finally {
            try { dadb.close(); } catch (Throwable ignored) {}
        }
    }

    /** Copies the helper's files (reports, camera pictures) into the app's own results folder. */
    private static void pullResults(Dadb dadb, Report r) {
        try {
            String list = dadb.shell("ls " + HELPER_DIR).getOutput();
            int n = 0;
            for (String name : list.split("\\s+")) {
                if (name.isEmpty()) continue;
                Report.DIR.mkdirs();
                dadb.pull(new File(Report.DIR, name), HELPER_DIR + "/" + name);
                n++;
            }
            r.ok("copied helper files into the app folder", n + " files");
        } catch (Throwable t) {
            r.error("copy helper files", t);
        }
    }
}
