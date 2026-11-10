package com.airodynamic.adas;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Runs the AI (YOLO26n) on camera frames in the background, one frame at a time.
 * The video never waits for it: results arrive whenever they are ready (often 1–2 s late)
 * and are kept per camera with their time. Warnings only — nothing here talks to the car.
 */
public final class Vision {

    public interface Callback { void onResult(int slice); }

    /** One thing the AI found, with a rough distance from where its bottom edge sits in the picture. */
    public static final class Obj {
        public float x1, y1, x2, y2, score, metres;
        public int cls;
    }

    private static Vision instance;
    public static synchronized Vision get(Context c) {
        if (instance == null) instance = new Vision(c.getApplicationContext());
        return instance;
    }

    private static final long GAP_MS = 250;   // rest between checks so the head unit stays cool
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler h;
    private Detector det;
    private int[] buf = new int[0];
    private volatile boolean busy;
    private volatile long nextAt;
    private volatile Callback cb;
    private final Object lock = new Object();
    private final List<Obj>[] results;
    private final long[] at = new long[4];
    private volatile String mode = "starting";
    private volatile String error;
    private volatile float avgMs;

    @SuppressWarnings("unchecked")
    private Vision(Context app) {
        this.app = app;
        HandlerThread t = new HandlerThread("ai");
        t.start();
        h = new Handler(t.getLooper());
        results = new List[4];
        for (int i = 0; i < 4; i++) results[i] = Collections.emptyList();
    }

    public void setCallback(Callback c) { cb = c; }

    /** Called on the camera thread for every frame; takes a copy only when the AI is free. */
    public void offer(int[] argb, int w, int hh, int slice) {
        if (busy || error != null || SystemClock.uptimeMillis() < nextAt) return;
        busy = true;
        if (buf.length != argb.length) buf = new int[argb.length];
        System.arraycopy(argb, 0, buf, 0, argb.length);
        h.post(() -> work(w, hh, slice));
    }

    private void work(int w, int hh, int slice) {
        try {
            if (det == null) {
                try { det = new Detector(app, true); }
                catch (Throwable gpuFail) { det = new Detector(app, false); }
                mode = det.mode();
            }
            long t0 = SystemClock.uptimeMillis();
            List<Detector.Det> raw = det.run(buf, w, hh, 0.35f);
            long ms = SystemClock.uptimeMillis() - t0;
            avgMs = avgMs == 0 ? ms : avgMs * 0.8f + ms * 0.2f;
            List<Obj> out = new ArrayList<>();
            for (Detector.Det d : raw) {
                if (!wanted(d.cls)) continue;
                Obj o = new Obj();
                o.x1 = d.x1; o.y1 = d.y1; o.x2 = d.x2; o.y2 = d.y2; o.score = d.score; o.cls = d.cls;
                o.metres = metres(d.y2 / hh);
                out.add(o);
            }
            synchronized (lock) { results[slice] = out; at[slice] = SystemClock.uptimeMillis(); }
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
        } finally {
            nextAt = SystemClock.uptimeMillis() + GAP_MS;
            busy = false;
        }
        final Callback c = cb;
        if (c != null) main.post(() -> c.onResult(slice));
    }

    private static boolean wanted(int cls) {
        return cls == Detector.PERSON || cls == Detector.BICYCLE || cls == Detector.CAR
                || cls == Detector.MOTORCYCLE || cls == Detector.BUS || cls == Detector.TRUCK;
    }

    /**
     * Rough distance from the bottom of the box (0 = top of picture, 1 = bottom).
     * Uses the same marks as the guide lines: 0.78 = 3 m, 0.62 = 6 m, 0.50 = 10 m.
     * Not calibrated yet (Phase 2) — treat it as "near / medium / far".
     */
    public static float metres(float yn) {
        if (yn >= 0.78f) return Math.max(0.5f, 3f - (yn - 0.78f) / 0.22f * 2.5f);
        if (yn >= 0.62f) return 3f + (0.78f - yn) / 0.16f * 3f;
        if (yn >= 0.50f) return 6f + (0.62f - yn) / 0.12f * 4f;
        return Math.min(30f, 10f + (0.50f - yn) * 80f);
    }

    /** Things seen by this camera, or an empty list if the result is older than maxAgeMs. */
    public List<Obj> objects(int slice, long maxAgeMs) {
        synchronized (lock) {
            if (at[slice] == 0 || age(slice) > maxAgeMs) return Collections.emptyList();
            return results[slice];
        }
    }

    public long age(int slice) { return at[slice] == 0 ? Long.MAX_VALUE : SystemClock.uptimeMillis() - at[slice]; }

    public String status() {
        if (error != null) return "AI off: " + error;
        if (avgMs == 0) return "AI starting…";
        return String.format(java.util.Locale.US, "AI %d ms (%s)", Math.round(avgMs), mode);
    }

    public static String label(int cls) {
        switch (cls) {
            case Detector.PERSON: return "Person";
            case Detector.BICYCLE: return "Bicycle";
            case Detector.MOTORCYCLE: return "Motorbike";
            case Detector.BUS: return "Bus";
            case Detector.TRUCK: return "Truck";
            default: return "Car";
        }
    }

    public static boolean vulnerable(int cls) {
        return cls == Detector.PERSON || cls == Detector.BICYCLE || cls == Detector.MOTORCYCLE;
    }

    public static int color(int cls) {
        if (cls == Detector.PERSON) return Theme.PERSON;
        if (cls == Detector.BICYCLE || cls == Detector.MOTORCYCLE) return Theme.CAUTION;
        return Theme.SAFE;
    }
}
