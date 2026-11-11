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
    public double best10 = Double.NaN, best10Speed = Double.NaN, best10From = Double.NaN;   // kWh/100, km/h, trip km
    private long runStart; private double runMax; private boolean runArmed, brakeArmed = true;
    private double prevV = Double.NaN, segSec;
    private final java.util.ArrayDeque<double[]> win = new java.util.ArrayDeque<>();   // last 10 km: {kWh, s} per 100 m
    private double winKwh, winSec;

    private void clearStats() {
        topSpeed = topSpeedKm = peakKw = maxRegenKw = 0;
        best0100 = Double.NaN; runs0100 = hardBrakes = 0; movingSec = stoppedSec = 0;
        best10 = best10Speed = best10From = Double.NaN;
        runStart = 0; runMax = 0; runArmed = false; brakeArmed = true; prevV = Double.NaN; segSec = 0;
        win.clear(); winKwh = winSec = 0;
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
                // best 10 km: slide a 10 km window (100 x 100 m) along the trip, keep the lowest kWh/100
                win.addLast(new double[]{segKwh, segSec}); winKwh += segKwh; winSec += segSec;
                if (win.size() > 100) { double[] o = win.removeFirst(); winKwh -= o[0]; winSec -= o[1]; }
                if (win.size() == 100 && winSec > 0) {
                    double c = winKwh * 10;   // kWh per 10 km -> per 100 km
                    if (Double.isNaN(best10) || c < best10) { best10 = c; best10Speed = 10 / (winSec / 3600.0); best10From = km - 10; }
                }
                segSec = 0;
                points.add(new Point(km, segN > 0 ? segSpeedSum / segN : 0, live, avg(),
                        segPedalN > 0 ? segPedalSum / segPedalN : Double.NaN,
                        segPowerN > 0 ? segPowerSum / segPowerN : Double.NaN));
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
    }
}
