package com.airodynamic.adas;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Reads the car (read-only) a few times a second. Every reading below was confirmed on
 * the user's Atto 3 in Phase 0 (see docs/PHASE0_RESULTS.md). Nothing here sends commands.
 */
public final class CarData {

    /** One snapshot. Missing values are NaN / -1. */
    public static final class State {
        public double speedKmh = Double.NaN;
        public int gear = -1;                 // 0/1 P, 2 R, 3 N, 4 D
        public double socPct = Double.NaN;
        public int rangeKm = -1;
        public int outsideC = Integer.MIN_VALUE;
        public int pmIn = -1, pmOut = -1;
        public final double[] tyreBar = {Double.NaN, Double.NaN, Double.NaN, Double.NaN}; // FL FR RL RR
        public int turn;                      // 0 none, 1 left, 2 right, 3 hazard
        public int[] radar = new int[0];      // cm per sensor, 150 = nothing near
        public boolean known;                 // at least one reading worked

        public boolean parked() { return (gear == 0 || gear == 1) && !(speedKmh > 1); }
        public String gearName() {
            switch (gear) { case 0: case 1: return "P"; case 2: return "R"; case 3: return "N"; case 4: return "D"; default: return "–"; }
        }
    }

    public interface Listener { void onCar(State s); }

    private static CarData instance;
    public static synchronized CarData get(Context c) {
        if (instance == null) instance = new CarData(c.getApplicationContext());
        return instance;
    }

    private static final String P = "android.hardware.bydauto.";
    private final Context ctx;
    private final Map<String, Object> devices = new HashMap<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Handler worker;
    private volatile State last = new State();
    private boolean running;

    private CarData(Context c) { ctx = new Bypass(c); }

    public State last() { return last; }

    public void addListener(Listener l) { listeners.add(l); l.onCar(last); start(); }
    public void removeListener(Listener l) { listeners.remove(l); }

    private synchronized void start() {
        if (running) return;
        running = true;
        HandlerThread t = new HandlerThread("car-data");
        t.start();
        worker = new Handler(t.getLooper());
        worker.post(poll);
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            State s = read();
            last = s;
            main.post(() -> { for (Listener l : listeners) l.onCar(s); });
            worker.postDelayed(this, 300);
        }
    };

    private Object dev(String cls) {
        if (devices.containsKey(cls)) return devices.get(cls);
        Object d = null;
        try { d = Class.forName(P + cls).getMethod("getInstance", Context.class).invoke(null, ctx); } catch (Throwable ignored) {}
        devices.put(cls, d);
        return d;
    }

    private Object call(String cls, String name, Object... args) {
        Object d = dev(cls);
        if (d == null) return null;
        try {
            Class<?>[] types = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) types[i] = int.class;
            Method m = d.getClass().getMethod(name, types);
            return m.invoke(d, args);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean ok(Object v) {
        if (!(v instanceof Number)) return false;
        double d = ((Number) v).doubleValue();
        return d > -10000 && d != 65535 && d != 8191;   // the car's "not available" codes
    }

    private State read() {
        State s = new State();
        Object v;
        if (ok(v = call("speed.BYDAutoSpeedDevice", "getCurrentSpeed"))) { s.speedKmh = ((Number) v).doubleValue(); s.known = true; }
        if (ok(v = call("gearbox.BYDAutoGearboxDevice", "getGearboxAutoModeType"))) s.gear = ((Number) v).intValue();
        if (ok(v = call("statistic.BYDAutoStatisticDevice", "getElecPercentageValue"))) s.socPct = ((Number) v).doubleValue();
        if (ok(v = call("statistic.BYDAutoStatisticDevice", "getElecDrivingRangeValue"))) s.rangeKm = ((Number) v).intValue();
        if (ok(v = call("instrument.BYDAutoInstrumentDevice", "getOutCarTemperature"))) s.outsideC = ((Number) v).intValue();
        v = call("pm2p5.BYDAutoPM2p5Device", "getPM2p5Value");
        if (v instanceof int[] && ((int[]) v).length >= 2) { s.pmIn = ((int[]) v)[0]; s.pmOut = ((int[]) v)[1]; }
        for (int i = 0; i < 4; i++) {
            v = call("tyre.BYDAutoTyreDevice", "getTyrePressureValue", i + 1);
            if (ok(v) && ((Number) v).intValue() > 0) s.tyreBar[i] = ((Number) v).intValue() / 100.0;   // kPa -> bar
        }
        // Flash state (confirmed): 1 off, 2/3 left, 4/5 right, 6/7 hazard
        if (ok(v = call("light.BYDAutoLightDevice", "getTurnLightFlashState"))) {
            int f = ((Number) v).intValue();
            s.turn = (f == 2 || f == 3) ? 1 : (f == 4 || f == 5) ? 2 : (f == 6 || f == 7) ? 3 : 0;
        }
        v = call("radar.BYDAutoRadarDevice", "getAllRadarDistance");
        if (v instanceof int[]) s.radar = (int[]) v;
        return s;
    }

    /** The car's classes check permissions inside our process; answer "granted" (as OverDrive does, MIT). */
    static final class Bypass extends ContextWrapper {
        Bypass(Context base) { super(base); }
        @Override public void enforceCallingOrSelfPermission(String p, String m) {}
        @Override public void enforcePermission(String p, int pid, int uid, String m) {}
        @Override public void enforceCallingPermission(String p, String m) {}
        @Override public int checkCallingOrSelfPermission(String p) { return PackageManager.PERMISSION_GRANTED; }
        @Override public int checkPermission(String p, int pid, int uid) { return PackageManager.PERMISSION_GRANTED; }
        @Override public int checkSelfPermission(String p) { return PackageManager.PERMISSION_GRANTED; }
        @Override public Looper getMainLooper() {
            try { Looper l = super.getMainLooper(); if (l != null) return l; } catch (NullPointerException ignored) {}
            return Looper.getMainLooper();
        }
    }
}
