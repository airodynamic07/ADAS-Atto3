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
        public final double km, speed, live, avg, pedal;
        Point(double km, double speed, double live, double avg, double pedal) {
            this.km = km; this.speed = speed; this.live = live; this.avg = avg; this.pedal = pedal;
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
    private int segN, segPedalN;
    private double segPedalSum;
    private final ArrayList<Point> points = new ArrayList<>();

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
        for (Listener l : listeners) l.onTrip(this);
    }

    @Override public void onCar(CarData.State s) {
        if (s.at == 0 || !s.known) return;
        synchronized (this) {
            if (startedAt == 0 || (lastAt > 0 && s.at - lastAt > NEW_TRIP_GAP_MS)) {
                startedAt = s.at; km = kwh = regenKwh = 0; segKm = segKwh = segSpeedSum = 0; segN = 0;
                startRemain = Double.NaN; points.clear(); lastAt = 0;
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
            if (segKm >= STEP_KM) {
                double live = segKwh / segKm * 100;
                points.add(new Point(km, segN > 0 ? segSpeedSum / segN : 0, live, avg(),
                        segPedalN > 0 ? segPedalSum / segPedalN : Double.NaN));
                segKm = segKwh = segSpeedSum = segPedalSum = 0;
                segN = segPedalN = 0;
                while (!points.isEmpty() && points.get(0).km < km - WINDOW_KM - 1) points.remove(0);
            }
        }
        for (Listener l : listeners) l.onTrip(this);
    }
}
