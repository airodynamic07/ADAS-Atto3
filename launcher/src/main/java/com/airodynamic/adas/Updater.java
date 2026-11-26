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

    public interface Log {
        void line(String s);
        /** download progress 0–100 (−1 = finished / hide) with a short text (Air 2026-11-23: show a status bar) */
        default void progress(int pct, String text) { }
    }

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

    /** (Air 2026-11-26) the newest build number on GitHub, or 0 when it can't be reached; for the "update available" badge */
    public static int latestBuild() {
        try (InputStream in = open(LATEST).getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return Integer.parseInt(new JSONObject(b.toString("UTF-8")).optString("tag_name", "").replaceAll("\\D", ""));
        } catch (Throwable e) { return 0; }
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
            HttpURLConnection dc = open(url);
            long total = dc.getContentLengthLong(), got = 0;
            int lastPct = -1;
            try (InputStream in = dc.getInputStream(); FileOutputStream out = new FileOutputStream(apk)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    got += n;
                    int pct = total > 0 ? (int) (got * 100 / total) : 0;
                    if (pct != lastPct) {
                        lastPct = pct;
                        log.progress(pct, String.format(java.util.Locale.US, "Downloading build %d · %d %% · %.0f / %.0f MB",
                                latest, pct, got / 1048576.0, total / 1048576.0));
                    }
                }
            }
            log.progress(100, "Installing build " + latest + "… the launcher restarts by itself in about 15 s");
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
            // after the update: start again, and switch the wheel-button watcher + mic + floating windows on again (2026-11-22)
            d.shell("setsid sh -c 'sleep 15; am start -n " + ctx.getPackageName() + "/.LauncherActivity; X=com.airodynamic.adas/com.airodynamic.adas.KeyWatch; c=$(settings get secure enabled_accessibility_services); case \"$c\" in *KeyWatch*) ;; null|\"\") settings put secure enabled_accessibility_services $X ;; *) settings put secure enabled_accessibility_services \"$c:$X\" ;; esac; settings put secure accessibility_enabled 1; pm grant com.airodynamic.adas android.permission.RECORD_AUDIO; appops set com.airodynamic.adas SYSTEM_ALERT_WINDOW allow; pm grant com.airodynamic.adas android.permission.WRITE_SECURE_SETTINGS' </dev/null >/dev/null 2>&1 &");
            d.install(apk, "-r");
        }
    }
}
