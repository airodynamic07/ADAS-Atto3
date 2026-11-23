package com.airodynamic.adas;

import android.content.Context;
import android.location.Location;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.BufferedInputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Today's driven route from GPS (user, 2026-11-12): a point every 15 m, saved to a small file per day
 * so a restart keeps it; a new day starts empty. Read only: GPS only.
 */
public final class TodayRoute {
    private static TodayRoute instance;
    public static synchronized TodayRoute get(Context c) {
        if (instance == null) instance = new TodayRoute(c.getApplicationContext());
        return instance;
    }

    private final File dir;
    private String day = "";
    /** lat, lon pairs */
    private double[] pts = new double[4096];
    private int n;   // number of points
    private volatile int version;

    private TodayRoute(Context c) {
        dir = new File(c.getFilesDir(), "route");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        roll();
    }

    private static String todayNow() { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()); }
    private File file() { return new File(dir, day + ".bin"); }

    /** New day: start empty, load today's file if there is one. Earlier days are kept for "Every day Map" (Air
     *  2026-11-23; they used to be deleted); only days older than ~400 are removed. */
    private synchronized void roll() {
        String d = todayNow();
        if (d.equals(day)) return;
        day = d;
        n = 0;
        File[] old = dir.listFiles();
        String cutoff = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(System.currentTimeMillis() - 400L * 86_400_000L));
        if (old != null) for (File f : old) if (f.getName().compareTo(cutoff) < 0) //noinspection ResultOfMethodCallIgnored
            f.delete();
        File f = file();
        if (f.exists()) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
                while (in.available() >= 16) put(in.readDouble(), in.readDouble());
            } catch (Exception ignored) { }
        }
        version++;
    }

    private void put(double lat, double lon) {
        if (2 * n + 2 > pts.length) pts = java.util.Arrays.copyOf(pts, pts.length * 2);
        pts[2 * n] = lat; pts[2 * n + 1] = lon; n++;
    }

    /** A GPS fix: kept when it is accurate enough and 15 m or more from the last kept point. */
    public synchronized void add(Location l) {
        roll();
        if (l.hasAccuracy() && l.getAccuracy() > 40) return;
        double lat = l.getLatitude(), lon = l.getLongitude();
        if (n > 0) {
            float[] d = new float[1];
            Location.distanceBetween(pts[2 * n - 2], pts[2 * n - 1], lat, lon, d);
            if (d[0] < 15) return;
        }
        put(lat, lon);
        version++;
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file(), true))) {
            out.writeDouble(lat); out.writeDouble(lon);
        } catch (Exception ignored) { }
    }

    /** Copy of today's points as lat, lon pairs. */
    public synchronized double[] points() { roll(); return java.util.Arrays.copyOf(pts, 2 * n); }
    public int version() { return version; }

    /** Days that have a route (yyyy-MM-dd), newest first. */
    public synchronized java.util.List<String> days() {
        roll();
        java.util.List<String> out = new java.util.ArrayList<>();
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) if (f.getName().endsWith(".bin") && f.length() >= 32) out.add(f.getName().substring(0, f.getName().length() - 4));
        java.util.Collections.sort(out, java.util.Collections.reverseOrder());
        return out;
    }

    /** One day's route as lat, lon pairs (today: live copy). */
    public synchronized double[] load(String d) {
        roll();
        if (d == null || d.equals(day)) return points();
        File f = new File(dir, d + ".bin");
        double[] a = new double[Math.max(0, (int) (f.length() / 8))];
        int k = 0;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            while (k + 1 < a.length && in.available() >= 16) { a[k++] = in.readDouble(); a[k++] = in.readDouble(); }
        } catch (Exception ignored) { }
        return java.util.Arrays.copyOf(a, k);
    }

    public String today() { roll(); return day; }
}
