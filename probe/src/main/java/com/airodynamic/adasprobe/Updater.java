package com.airodynamic.adasprobe;

import android.content.Context;
import android.content.pm.PackageInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * "Update app" button: downloads the newest APK from this repo's GitHub
 * Releases and installs it through the car's own ADB. Needs internet in the
 * car (e.g. your phone's hotspot). Saved results and settings are kept.
 */
public final class Updater {
    private Updater() {}

    static final String LATEST = "https://api.github.com/repos/airodynamic07/ADAS-Atto3/releases/latest";

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "ADAS-Atto3-probe");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        return c;
    }

    public static void update(Context ctx, Report r) throws Exception {
        PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
        int mine = pi.versionCode;
        r.info("This app: v" + pi.versionName + " (build " + mine + ")");
        r.info("Checking GitHub for a newer build...");
        HttpURLConnection c = open(LATEST);
        String json;
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            json = b.toString("UTF-8");
        } catch (Exception e) {
            r.fail("internet", "cannot reach GitHub (" + e.getClass().getSimpleName()
                    + "). Connect the car to Wi-Fi, e.g. your phone's hotspot, and try again.");
            return;
        }
        JSONObject rel = new JSONObject(json);
        String tag = rel.optString("tag_name", "");            // e.g. probe-7
        int latest = 0;
        try { latest = Integer.parseInt(tag.replaceAll("\\D", "")); } catch (NumberFormatException ignored) {}
        r.info("Newest on GitHub: " + tag + " (build " + latest + ")");
        if (latest <= mine) {
            r.ok("update", "you already have the newest version");
            return;
        }
        String url = null;
        JSONArray assets = rel.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url");
        }
        if (url == null) {
            r.fail("update", "no APK in the newest release");
            return;
        }
        File apk = new File(ctx.getExternalFilesDir(null), "update.apk");
        r.info("Downloading " + url + " ...");
        HttpURLConnection d = open(url);
        d.setRequestProperty("Accept", "application/octet-stream");
        long total = 0;
        try (InputStream in = d.getInputStream(); FileOutputStream out = new FileOutputStream(apk)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); total += n; }
        }
        apk.setReadable(true, false);
        r.ok("downloaded", (total / 1024) + " KB");
        if (!HelperLauncher.install(ctx, apk, r)) {
            r.info("Automatic install failed. The file is saved; you can still install with the USB stick.");
        }
    }
}
