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
