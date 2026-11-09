package com.airodynamic.adasprobe;

import android.content.Context;

import java.io.File;

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

    public static String command(Context ctx, String task) {
        String apk = ctx.getApplicationInfo().sourceDir;
        return "CLASSPATH=" + apk + " app_process /system/bin " + ShellMain.class.getName() + " " + task;
    }

    /** The same command for the user to paste into Termux (adb shell). */
    public static String termuxCommand(String task) {
        return "adb shell 'CLASSPATH=$(pm path " + ShellMain.PKG + " | cut -d: -f2) app_process /system/bin "
                + ShellMain.class.getName() + " " + task + "'";
    }

    public static String run(Context ctx, String task, int timeoutSec, Report r) throws Exception {
        File priv = new File(ctx.getFilesDir(), "adbkey");
        File pub = new File(ctx.getFilesDir(), "adbkey.pub");
        if (!priv.exists() || !pub.exists()) {
            r.info("Creating this app's ADB key (first run)...");
            AdbKeyPair.generate(priv, pub);
        }
        AdbKeyPair keys = AdbKeyPair.read(priv, pub);
        r.info("Connecting to the car's ADB at 127.0.0.1:5555 ... (if the car asks 'Allow USB debugging?', tap Always allow + OK)");
        Dadb dadb = Dadb.create("127.0.0.1", 5555, keys);
        try {
            AdbShellResponse who = dadb.shell("id");
            r.ok("ADB connected", who.getOutput().trim());
            String cmd = "timeout " + timeoutSec + " sh -c '" + command(ctx, task) + "' 2>&1";
            r.info("Running helper task '" + task + "' (can take up to " + timeoutSec + " s)...");
            AdbShellResponse res = dadb.shell(cmd);
            r.info("helper exit code " + res.getExitCode());
            return res.getAllOutput();
        } finally {
            try { dadb.close(); } catch (Throwable ignored) {}
        }
    }
}
