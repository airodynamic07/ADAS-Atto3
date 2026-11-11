package com.airodynamic.adas;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * This trip's energy, worked out by the launcher from the car's live motor power:
 * kWh used (net, regen subtracted), kWh won back, distance, live and average kWh/100 km,
 * and one graph point every 100 m (last 20 km kept). A new trip starts when the car has
 * been off (no readings) for 10 minutes, or on Reset. If the car gives no power reading,
 * the battery's remaining energy is used instead (coarser).
 */
public final class TripEnergy implements CarData.Listener {

    public static final double STEP_KM = 0.1, WINDOW_KM = 20;
    private static final long NEW_TRIP_GAP_MS = 10 * 60_000L;

    public static final class Point {
        public final double km, speed, live, avg, pedal, power;
        /** strongest braking in this 100 m: 0 none, 1 light (≥0.15 g), 2 medium (≥0.25 g), 3 hard (≥0.4 g) */
        public int brake;
        /** strongest slowing-down in this 100 m, in g */
        public double decelG;
        Point(double km, double speed, double live, double avg, double pedal, double power) {
            this.km = km; this.speed = speed; this.live = live; this.avg = avg; this.pedal = pedal; this.power = power;
        }
    }

    private static TripEnergy instance;
    public static synchronized TripEnergy get(Context c) {
        if (instance == null) {
            instance = new TripEnergy();
            CarData.get(c).addListener(instance);
        }
        return instance;
    }

    public interface Listener { void onTrip(TripEnergy t); }
    private final List<Listener> listeners = new ArrayList<>();
    public void addListener(Listener l) { listeners.add(l); l.onTrip(this); }
    public void removeListener(Listener l) { listeners.remove(l); }

    public long startedAt;
    public double km, kwh, regenKwh, powerKw = Double.NaN;
    public boolean fromRemaining;           // true when using the battery's remaining energy instead of power
    private double startRemain = Double.NaN;
    private long lastAt;
    private double segKm, segKwh, segSpeedSum;
    private int segN, segPedalN, segPowerN;
    private double segPedalSum, segPowerSum;
    /** Right now (every reading, ~3 times a second): for the graph's live point. */
    public double liveSpeed = Double.NaN, livePedal = Double.NaN;
    private final ArrayList<Point> points = new ArrayList<>();

    // ---- performance stats for this trip (user, 2026-11-11)
    public double topSpeed, topSpeedKm, peakKw, maxRegenKw;      // maxRegenKw is negative
    public double best0100 = Double.NaN;                          // seconds
    public int runs0100, hardBrakes;
    public double movingSec, stoppedSec;
    private double segMin = Double.NaN, segMax = Double.NaN, segDecel;
    private final double[] hv = new double[4]; private final long[] ht = new long[4]; private int hn;   // last speeds, for slowing-down rate

    /** One of the best (lowest kWh/100 km) stretches of the chosen length; stretches never overlap. */
    public static final class Best { public double kwh100, speed, min, max, fromKm, toKm; }
    private volatile List<Best> best = new ArrayList<>();
    private double bestKm = 10;
    private final ArrayList<double[]> segs = new ArrayList<>();   // whole trip, per 100 m: {kWh, s, min km/h, max km/h, end km}
    public List<Best> best() { return best; }
    public double bestKm() { return bestKm; }
    public synchronized void setBestKm(double k) { bestKm = Math.max(1, Math.min(100, k)); findBest(); for (Listener l : listeners) l.onTrip(this); }
    private long runStart; private double runMax; private boolean runArmed, brakeArmed = true;
    private double prevV = Double.NaN, segSec;

    private void clearStats() {
        topSpeed = topSpeedKm = peakKw = maxRegenKw = 0;
        best0100 = Double.NaN; runs0100 = hardBrakes = 0; movingSec = stoppedSec = 0;
        best = new ArrayList<>(); segs.clear(); segDecel = 0; hn = 0;
        segMin = segMax = Double.NaN;
        runStart = 0; runMax = 0; runArmed = false; brakeArmed = true; prevV = Double.NaN; segSec = 0;
    }

    /** Average speed while moving (km/h). */
    public double avgMoving() { return movingSec > 30 ? km / (movingSec / 3600.0) : Double.NaN; }

    public synchronized List<Point> points() { return new ArrayList<>(points); }

    /** kWh/100 km over the whole trip, or NaN for the first 200 m. */
    public double avg() { return km >= 0.2 ? kwh / km * 100 : Double.NaN; }

    public synchronized void reset() {
        startedAt = System.currentTimeMillis();
        km = kwh = regenKwh = 0;
        segKm = segKwh = segSpeedSum = 0;
        segN = 0;
        startRemain = Double.NaN;
        points.clear();
        clearStats();
        for (Listener l : listeners) l.onTrip(this);
    }

    @Override public void onCar(CarData.State s) {
        if (s.at == 0 || !s.known) return;
        synchronized (this) {
            if (startedAt == 0 || (lastAt > 0 && s.at - lastAt > NEW_TRIP_GAP_MS)) {
                startedAt = s.at; km = kwh = regenKwh = 0; segKm = segKwh = segSpeedSum = 0; segN = 0;
                startRemain = Double.NaN; points.clear(); lastAt = 0; clearStats();
            }
            double dt = lastAt == 0 ? 0 : Math.min(2.0, (s.at - lastAt) / 1000.0);   // seconds; gaps don't count
            lastAt = s.at;
            powerKw = s.powerKw;
            double dKm = Double.isNaN(s.speedKmh) ? 0 : Math.max(0, s.speedKmh) * dt / 3600.0;
            double dKwh;
            if (!Double.isNaN(s.powerKw)) {
                fromRemaining = false;
                dKwh = s.powerKw * dt / 3600.0;
                if (s.powerKw < 0) regenKwh += -dKwh;
            } else if (!Double.isNaN(s.remainKwh)) {
                fromRemaining = true;
                if (Double.isNaN(startRemain)) startRemain = s.remainKwh;
                dKwh = (startRemain - s.remainKwh) - kwh;
            } else dKwh = 0;
            km += dKm;
            kwh += dKwh;
            segKm += dKm;
            segKwh += dKwh;
            if (!Double.isNaN(s.speedKmh)) { segSpeedSum += s.speedKmh; segN++; }
            if (!Double.isNaN(s.pedalPct)) { segPedalSum += s.pedalPct; segPedalN++; }
            if (!Double.isNaN(s.powerKw)) { segPowerSum += s.powerKw; segPowerN++; }
            liveSpeed = s.speedKmh;
            livePedal = s.pedalPct;
            segSec += dt;
            stats(s, dt);
            if (segKm >= STEP_KM) {
                double live = segKwh / segKm * 100;
                segs.add(new double[]{segKwh, segSec, segMin, segMax, km});
                findBest();
                segSec = 0; segMin = segMax = Double.NaN;
                Point pt = new Point(km, segN > 0 ? segSpeedSum / segN : 0, live, avg(),
                        segPedalN > 0 ? segPedalSum / segPedalN : Double.NaN,
                        segPowerN > 0 ? segPowerSum / segPowerN : Double.NaN);
                double gs = segDecel / 9.81;
                pt.brake = gs >= .4 ? 3 : gs >= .25 ? 2 : gs >= .15 ? 1 : 0;
                pt.decelG = gs;
                points.add(pt);
                segDecel = 0;
                segKm = segKwh = segSpeedSum = segPedalSum = segPowerSum = 0;
                segN = segPedalN = segPowerN = 0;
                while (!points.isEmpty() && points.get(0).km < km - WINDOW_KM - 1) points.remove(0);
            }
        }
        for (Listener l : listeners) l.onTrip(this);
    }

    /** Top speed, peak power/regen, 0–100 runs, moving/stopped time, hard brakes (> 0.4 g). */
    private void stats(CarData.State s, double dt) {
        if (!Double.isNaN(s.powerKw)) { peakKw = Math.max(peakKw, s.powerKw); maxRegenKw = Math.min(maxRegenKw, s.powerKw); }
        if (Double.isNaN(s.speedKmh)) return;
        double v = s.speedKmh;
        segMin = Double.isNaN(segMin) ? v : Math.min(segMin, v);
        segMax = Double.isNaN(segMax) ? v : Math.max(segMax, v);
        if (v > topSpeed) { topSpeed = v; topSpeedKm = km; }
        if (v > 2) movingSec += dt; else stoppedSec += dt;
        // 0–100 km/h: starts when the car moves off from standing still, ends at 100;
        // dropped if the speed falls back 5 km/h or it takes longer than 40 s
        if (v <= 1) { runStart = 0; runArmed = true; }
        else if (runArmed && runStart == 0) { runStart = s.at; runMax = v; runArmed = false; }
        if (runStart > 0) {
            runMax = Math.max(runMax, v);
            if (v >= 100) {
                double sec = (s.at - runStart) / 1000.0;
                if (Double.isNaN(best0100) || sec < best0100) best0100 = sec;
                runs0100++;
                runStart = 0;
            } else if (v < runMax - 5 || s.at - runStart > 40_000) runStart = 0;
        }
        // hard brake: slowing faster than 0.4 g (3.9 m/s²); counted once until the braking eases off
        if (!Double.isNaN(prevV) && dt > 0.1) {
            double decel = (prevV - v) / 3.6 / dt;
            if (decel > 3.9 && brakeArmed) { hardBrakes++; brakeArmed = false; }
            else if (decel < 1.5) brakeArmed = true;
        }
        prevV = v;
        // slowing-down rate over the last ~1 s (less jumpy than one reading), strongest one in this 100 m
        hv[hn % 4] = v; ht[hn % 4] = s.at; hn++;
        if (hn >= 4) {
            int o = hn % 4;   // oldest of the last 4
            double secs = (s.at - ht[o]) / 1000.0;
            if (secs > 0.3 && secs < 3) segDecel = Math.max(segDecel, (hv[o] - v) / 3.6 / secs);
        }
    }

    /**
     * Top 3 stretches of bestKm with the lowest kWh/100 km, never overlapping:
     * every window of that length along the whole trip, best first, skipping any that overlap one already picked.
     */
    private void findBest() {
        int w = (int) Math.round(bestKm / STEP_KM), n = segs.size();
        List<Best> out = new ArrayList<>();
        if (n >= w && w > 0) {
            double[] e = new double[n + 1], t = new double[n + 1];
            for (int i = 0; i < n; i++) { e[i + 1] = e[i] + segs.get(i)[0]; t[i + 1] = t[i] + segs.get(i)[1]; }
            Integer[] idx = new Integer[n - w + 1];
            for (int i = 0; i < idx.length; i++) idx[i] = i;
            java.util.Arrays.sort(idx, (a, b) -> Double.compare(e[a + w] - e[a], e[b + w] - e[b]));
            List<Integer> picked = new ArrayList<>();
            for (int st : idx) {
                boolean clash = false;
                for (int p : picked) if (st < p + w && p < st + w) { clash = true; break; }
                if (clash) continue;
                picked.add(st);
                Best b = new Best();
                b.kwh100 = (e[st + w] - e[st]) / bestKm * 100;
                double sec = t[st + w] - t[st];
                b.speed = sec > 0 ? bestKm / (sec / 3600.0) : Double.NaN;
                double lo = Double.MAX_VALUE, hi = 0;
                for (int k = st; k < st + w; k++) { double[] g = segs.get(k); if (!Double.isNaN(g[2])) lo = Math.min(lo, g[2]); if (!Double.isNaN(g[3])) hi = Math.max(hi, g[3]); }
                b.min = lo == Double.MAX_VALUE ? Double.NaN : lo; b.max = hi;
                b.toKm = segs.get(st + w - 1)[4]; b.fromKm = b.toKm - bestKm;
                out.add(b);
                if (out.size() == 3) break;   // top 3 (user, 2026-11-11)
            }
        }
        best = out;
    }
}
