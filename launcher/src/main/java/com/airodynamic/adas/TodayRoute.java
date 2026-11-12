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

    private static String today() { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()); }
    private File file() { return new File(dir, day + ".bin"); }

    /** New day: start empty, drop old days' files, load today's file if there is one. */
    private synchronized void roll() {
        String d = today();
        if (d.equals(day)) return;
        day = d;
        n = 0;
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) if (!f.getName().equals(day + ".bin")) //noinspection ResultOfMethodCallIgnored
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
}
