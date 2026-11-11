package com.airodynamic.adas;

import android.content.Context;
import android.content.pm.PackageInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;

import dadb.AdbKeyPair;
import dadb.Dadb;

/**
 * "Check for updates": newest adas-launcher.apk from GitHub Releases, installed through the
 * car's own ADB (same as the probe). Saved settings are kept (rule 3).
 */
public final class Updater {
    private Updater() {}

    public interface Log { void line(String s); }

    private static final String LATEST = "https://api.github.com/repos/airodynamic07/ADAS-Atto3/releases/latest";
    private static final String ASSET = "adas-launcher.apk";

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "ADAS-Atto3-launcher");
        return c;
    }

    public static void update(Context ctx, Log log) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            log.line("This launcher: v" + pi.versionName);
            String json;
            try (InputStream in = open(LATEST).getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                json = b.toString("UTF-8");
            } catch (Exception e) {
                log.line("No internet — connect the car to Wi-Fi (e.g. your phone's hotspot).");
                return;
            }
            JSONObject rel = new JSONObject(json);
            int latest = 0;
            try { latest = Integer.parseInt(rel.optString("tag_name", "").replaceAll("\\D", "")); } catch (NumberFormatException ignored) {}
            String url = null;
            JSONArray assets = rel.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (ASSET.equals(a.getString("name"))) url = a.getString("browser_download_url");
            }
            if (url == null || latest <= pi.versionCode) {
                log.line("You have the newest version.");
                return;
            }
            log.line("Downloading build " + latest + "…");
            File apk = new File(ctx.getExternalFilesDir(null), "update.apk");
            try (InputStream in = open(url).getInputStream(); FileOutputStream out = new FileOutputStream(apk)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            apk.setReadable(true, false);
            install(ctx, apk, log);
        } catch (Throwable t) {
            log.line("Update failed: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    /**
     * Runs one shell command through the car's own ADB (127.0.0.1:5555), like the installs do.
     * Used to switch on Notification access for Now playing, whose settings page BYD has blocked.
     */
    public static String adbShell(Context ctx, String cmd) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
        }
        File priv = new File(ctx.getFilesDir(), "adbkey"), pub = new File(ctx.getFilesDir(), "adbkey.pub");
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub);
        try (Dadb d = Dadb.create("127.0.0.1", 5555, AdbKeyPair.read(priv, pub), 5000, 30000)) {
            dadb.AdbShellResponse r = d.shell(cmd);
            return (r.getAllOutput() == null ? "" : r.getAllOutput().trim()) + (r.getExitCode() != 0 ? " (exit " + r.getExitCode() + ")" : "");
        }
    }

    private static void install(Context ctx, File apk, Log log) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
        } catch (Exception e) {
            log.line("The car's ADB is off — install the file from the USB stick instead.");
            return;
        }
        File priv = new File(ctx.getFilesDir(), "adbkey"), pub = new File(ctx.getFilesDir(), "adbkey.pub");
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub);
        log.line("Installing… if the car asks 'Allow USB debugging?', tick Always allow + OK.");
        try (Dadb d = Dadb.create("127.0.0.1", 5555, AdbKeyPair.read(priv, pub), 5000, 120000)) {
            d.shell("setsid sh -c 'sleep 15; am start -n " + ctx.getPackageName() + "/.LauncherActivity' </dev/null >/dev/null 2>&1 &");
            d.install(apk, "-r");
        }
    }
}
