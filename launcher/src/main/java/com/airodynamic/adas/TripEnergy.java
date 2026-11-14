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
        /** regen happened in this 100 m (power below −2 kW) */
        public boolean regen;
        /** average GPS height over this 100 m, metres (NaN when GPS gave none) */
        public double alt = Double.NaN;
        Point(double km, double speed, double live, double avg, double pedal, double power) {
            this.km = km; this.speed = speed; this.live = live; this.avg = avg; this.pedal = pedal; this.power = power;
        }
    }

    private static TripEnergy instance;
    public static synchronized TripEnergy get(Context c) {
        if (instance == null) {
            instance = new TripEnergy();
            instance.store = c.getApplicationContext().getSharedPreferences("today", Context.MODE_PRIVATE);
            instance.hist = c.getApplicationContext().getSharedPreferences("history", Context.MODE_PRIVATE);
            instance.loadToday();
            CarData.get(c).addListener(instance);
            instance.startGps(c.getApplicationContext());
        }
        return instance;
    }

    // ---- GPS height for the graph's hill shape (read only; GPS height wobbles a few metres)
    private double altSum; private int altN;
    private volatile double lastAlt = Double.NaN; private volatile long lastAltAt;
    private final android.location.LocationListener gps = new android.location.LocationListener() {
        @Override public void onLocationChanged(android.location.Location l) {
            if (l.hasAltitude()) { lastAlt = l.getAltitude(); lastAltAt = System.currentTimeMillis(); }
            if (route != null) route.add(l);   // today's route map
        }
        @Override public void onStatusChanged(String p, int st, android.os.Bundle b) { }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { }
    };
    /** Called once location has been allowed. */
    public void restartGps(Context c) { startGps(c.getApplicationContext()); }

    private TodayRoute route;

    @android.annotation.SuppressLint("MissingPermission")
    private void startGps(Context c) {
        route = TodayRoute.get(c);
        try {
            ((android.location.LocationManager) c.getSystemService(Context.LOCATION_SERVICE)).removeUpdates(gps);
            android.location.LocationManager lm = (android.location.LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 1000, 0, gps, android.os.Looper.getMainLooper());
        } catch (Throwable ignored) { }   // no GPS or no permission: the graph simply has no hill shape
    }

    // ---- fine samples for the graph's "real time" lines: one every 10 m, last 21 km
    private final java.util.ArrayDeque<double[]> fine = new java.util.ArrayDeque<>();   // {km, speed, power}
    private double fineKm = -1;
    public synchronized java.util.List<double[]> fine() { return new java.util.ArrayList<>(fine); }

    public interface Listener { void onTrip(TripEnergy t); }
    private final List<Listener> listeners = new ArrayList<>();
    public void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); l.onTrip(this); }
    public void removeListener(Listener l) { listeners.remove(l); }

    // ---- today: km and kWh across all trips, starts again each day, saved so a restart keeps it
    private android.content.SharedPreferences store;
    private String day = "";
    private double dayKm, dayKwh, daySec, dayDrv;   // dayDrv: time in a driving gear, stops included (history)
    private long savedAt;
    public double todayKm() { rollDay(); return dayKm; }
    public double todayKwh() { rollDay(); return dayKwh; }
    public double todaySec() { rollDay(); return daySec; }
    private static String dayNow() { return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date()); }
    private void loadToday() {
        day = store.getString("day", "");
        dayKm = Double.longBitsToDouble(store.getLong("km", 0));
        dayKwh = Double.longBitsToDouble(store.getLong("kwh", 0));
        daySec = Double.longBitsToDouble(store.getLong("sec", 0));
        dayDrv = Math.max(daySec, Double.longBitsToDouble(store.getLong("drv", 0)));
        if (dayKwh < 0) dayKwh = 0;   // charging counted by an older version
        // not saved by a version before this one: then all of today so far was counted without correction
        rawDayKm = store.contains("rawKm") ? store.getFloat("rawKm", 0) : dayKm;
        rawDayKwh = store.contains("rawKwh") ? store.getFloat("rawKwh", 0) : dayKwh;
        // (user, 2026-11-13) today counted charging as used: delete 13 Nov completely, once
        if (!store.getBoolean("wiped20261113", false)) {
            if ("2026-11-13".equals(day)) { dayKm = dayKwh = daySec = dayDrv = 0; }
            if (hist != null) hist.edit().remove("2026-11-13").apply();
            android.content.SharedPreferences.Editor ed = store.edit().putBoolean("wiped20261113", true);
            if ("2026-11-13".equals(day)) ed.putLong("km", 0).putLong("kwh", 0).putLong("sec", 0).putLong("drv", 0);
            ed.apply();
        }
        rollDay();
    }
    private void rollDay() {
        String d = dayNow();
        if (d.equals(day)) return;
        saveHistory();   // the day that just ended keeps its numbers in the history
        day = d; dayKm = dayKwh = daySec = dayDrv = 0; rawDayKm = rawDayKwh = 0;
    }

    /** (user, 2026-11-14: "yes") the days saved before the energy check worked read about 1.6x high: once the check has
     *  its first correction, those days' kWh are corrected with it, one time only. Today is corrected separately. */
    private void scaleOldHistory(double f) {
        if (hist == null || store == null || store.getBoolean("histScaled", false)) return;
        android.content.SharedPreferences.Editor ed = hist.edit();
        for (java.util.Map.Entry<String, ?> e : hist.getAll().entrySet()) {
            if (e.getKey().equals(day)) continue;
            try {
                String[] p = String.valueOf(e.getValue()).split(",");
                p[1] = String.format(java.util.Locale.US, "%.3f", Double.parseDouble(p[1]) * f);
                ed.putString(e.getKey(), android.text.TextUtils.join(",", p));
            } catch (Exception ignored) { }
        }
        ed.apply();
        store.edit().putBoolean("histScaled", true).apply();
    }

    // ---- driving history (user, 2026-11-12): one entry per day, kept for good: "km,kWh,seconds"
    private android.content.SharedPreferences hist;
    private void saveHistory() {
        if (hist == null || day.isEmpty() || dayKm < 0.05) return;
        hist.edit().putString(day, String.format(java.util.Locale.US, "%.3f,%.3f,%.0f,%.0f", dayKm, Math.max(0, dayKwh), dayDrv, daySec)).apply();
    }
    /** Day → {km, kWh, seconds, moving seconds}, today included (live). Older days have no moving time: their
     *  average speed uses the whole driving time. */
    public synchronized java.util.TreeMap<String, double[]> history() {
        rollDay();
        java.util.TreeMap<String, double[]> out = new java.util.TreeMap<>();
        if (hist != null) for (java.util.Map.Entry<String, ?> e : hist.getAll().entrySet()) {
            try {
                String[] p = String.valueOf(e.getValue()).split(",");
                out.put(e.getKey(), new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                        p.length > 3 ? Double.parseDouble(p[3]) : Double.parseDouble(p[2])});
            } catch (Exception ignored) { }
        }
        if (dayKm >= 0.05) out.put(day, new double[]{dayKm, Math.max(0, dayKwh), dayDrv, daySec});
        return out;
    }
    private void saveToday(long now) {
        if (store == null || now - savedAt < 20_000) return;
        savedAt = now;
        store.edit().putString("day", day).putLong("km", Double.doubleToLongBits(dayKm)).putLong("kwh", Double.doubleToLongBits(dayKwh)).putLong("sec", Double.doubleToLongBits(daySec)).putLong("drv", Double.doubleToLongBits(dayDrv))
                .putFloat("rawKm", (float) rawDayKm).putFloat("rawKwh", (float) rawDayKwh).apply();
        saveHistory();
    }

    public long startedAt;
    public double km, kwh, regenKwh, powerKw = Double.NaN;
    public boolean fromRemaining;           // true when using the battery's remaining energy instead of power
    private double startRemain = Double.NaN;
    private double lastSoc = Double.NaN;
    private boolean chargingLatch;
    private double rawDayKm, rawDayKwh, rawTripKm, rawTripKwh;

    // ---- your own consumption over the last 50 km, across days (user, 2026-11-14: "My range" from my driving).
    // One entry per km driven: the kWh it took (regen included, charging never). Kept after a restart.
    private final java.util.ArrayDeque<Double> last50 = new java.util.ArrayDeque<>();
    private double l50Km, l50Kwh;
    private boolean l50Loaded;
    private void l50Add(double dKm, double dKwh) {
        if (store == null) return;
        if (!l50Loaded) {
            l50Loaded = true;
            for (String p : store.getString("last50", "").split(";")) try { if (!p.isEmpty()) last50.addLast(Double.parseDouble(p)); } catch (Exception ignored) { }
        }
        l50Km += dKm; l50Kwh += dKwh;
        if (l50Km < 1) return;
        last50.addLast(l50Kwh / l50Km);
        l50Km = 0; l50Kwh = 0;
        while (last50.size() > 50) last50.removeFirst();
        StringBuilder b = new StringBuilder();
        for (double d : last50) b.append(String.format(java.util.Locale.US, "%.4f;", d));
        store.edit().putString("last50", b.toString()).apply();
    }
    /** kWh per 100 km over your last (up to) 50 km; NaN before 10 km. */
    public synchronized double myAvg50() {
        if (last50.size() < 10) return Double.NaN;
        double sum = 0;
        for (double d : last50) sum += d;
        return sum / last50.size() * 100;
    }   // counted while no correction was known yet

    // ---- check against the car's own counters (user, 2026-11-14: car 10 km / 1.4 kWh, here 11.3 km / 2.4 kWh).
    // Speed × time and power × time both read high on this car, so the sums are compared with the car's trip
    // counter (0.1 km) and its energy counter (or, without one, the energy left in the battery), and corrected.
    private double calCarKm, calIntKm, calCarKwh, calIntKwh, calLastTrip = Double.NaN, calLastE = Double.NaN;
    private long calSavedAt;
    private boolean calLoaded;
    private void calLoad() {
        if (calLoaded || store == null) return;
        calLoaded = true;
        calCarKm = store.getFloat("calCarKm", 0); calIntKm = store.getFloat("calIntKm", 0);
        // "…2": the old energy check (car's total counter) never got usable numbers, start fresh (2026-11-14)
        calCarKwh = store.getFloat("calCarKwh2", 0); calIntKwh = store.getFloat("calIntKwh2", 0);
    }
    private double kmFactor() {
        if (calCarKm < 5 || calIntKm <= 0) return 1;
        double f = calCarKm / calIntKm;
        return f >= 0.85 && f <= 1.08 ? f : 1;
    }
    private double kwhFactor() {
        if (calIntKwh < 2 || calCarKwh <= 0) return 1;
        double f = calCarKwh / calIntKwh;
        return f >= 0.4 && f <= 1.3 ? f : 1;
    }
    private void calibrate(CarData.State s, double rawKm, double rawKwh, boolean charging) {
        calLoad();
        if (!Double.isNaN(s.tripKm)) {
            double d = s.tripKm - calLastTrip;
            if (!Double.isNaN(calLastTrip) && d >= 0 && d <= 0.5) { calCarKm += d; calIntKm += rawKm; }
            calLastTrip = s.tripKm;
        }
        // (user, 2026-11-14: 38 km, Battery left 25.0 → 19.5 = 5.5 kWh, launcher 8.9 kWh) the car's total energy counter
        // doesn't move while driving, so the check never worked. Now it compares with Battery left, which drops live
        // in 0.1 kWh steps and matches the battery % drop.
        double e = -s.battLeftKwh;   // goes up as energy is used
        if (charging || Double.isNaN(e)) calLastE = Double.NaN;
        else {
            double d = e - calLastE;
            if (!Double.isNaN(calLastE) && Math.abs(d) <= 0.5) { calCarKwh += d; calIntKwh += rawKwh; }
            calLastE = e;
        }
        if (calIntKm > 300) { calIntKm /= 2; calCarKm /= 2; }       // older driving counts less
        if (calIntKwh > 40) { calIntKwh /= 2; calCarKwh /= 2; }
        if (store != null && s.at - calSavedAt > 60_000) {
            calSavedAt = s.at;
            store.edit().putFloat("calCarKm", (float) calCarKm).putFloat("calIntKm", (float) calIntKm)
                    .putFloat("calCarKwh2", (float) calCarKwh).putFloat("calIntKwh2", (float) calIntKwh).apply();
        }
    }
    /** For Settings: what the check found so far. */
    public synchronized String checkInfo() {
        calLoad();
        return String.format(java.util.Locale.US,
                "Distance: car %.1f km · launcher %.1f km → correction ×%.2f\nEnergy (%s): car %.1f kWh · launcher %.1f kWh → correction ×%.2f\n"
                        + "The correction starts after 5 km and 2 kWh of driving, then keeps adjusting.",
                calCarKm, calIntKm, kmFactor(), "Battery left drop",
                calCarKwh, calIntKwh, kwhFactor());
    }
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
    private double segMin = Double.NaN, segMax = Double.NaN, segDecel, segMinPower;
    /** Live stamps for the graph (the 100 m being driven now): regen yes/no, brake 0 none, 1 mild, 2 hard. */
    /** Regen stamp only while "Power now" shows a minus number (user, 2026-11-11). */
    public boolean liveRegen() { return !Double.isNaN(powerKw) && powerKw <= -0.5 && liveSpeed > 1; }   // not while charging
    public int liveBrake() { double g = segDecel / 9.81; return g >= .4 ? 2 : g >= .15 ? 1 : 0; }
    /** km where the last stored point ends (start of the live stamp). */
    public synchronized double lastPointKm() { return points.isEmpty() ? Math.max(0, km - (km % STEP_KM)) : points.get(points.size() - 1).km; }
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
        synchronized (this) { fine.clear(); fineKm = -1; }
        best = new ArrayList<>(); latest = null; lastBlock = null; segs.clear(); segDecel = 0; hn = 0;
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
                startedAt = s.at; km = kwh = regenKwh = 0; rawTripKm = rawTripKwh = 0; segKm = segKwh = segSpeedSum = 0; segN = 0;
                startRemain = Double.NaN; points.clear(); lastAt = 0; clearStats();
            }
            double dt = lastAt == 0 ? 0 : Math.min(2.0, (s.at - lastAt) / 1000.0);   // seconds; gaps don't count
            lastAt = s.at;
            powerKw = s.powerKw;
            double dKm = Double.isNaN(s.speedKmh) ? 0 : Math.max(0, s.speedKmh) * dt / 3600.0;
            double dKwh;
            // Charging (parked and energy going in) is not driving: it doesn't count as trip or today energy
            // (user, 2026-11-12: "Used −0.3 kWh" while charging).
            // standing still with energy going in = charging (regen needs the wheels turning); the gear can
            // read "unknown" while the car is off and charging, so don't rely on P (2026-11-12: −5555 kWh/100)
            boolean still = Double.isNaN(s.speedKmh) || s.speedKmh < 1;
            // (2026-11-13: Today showed ~20 kWh too much after charging in the morning) some chargers make the
            // power reading go UP, not below zero: standing still, anything over 5 kW is charging too (the climate
            // and 12 V system use less than that), and so is the battery % rising while standing.
            boolean socUp = !Double.isNaN(s.socPct) && !Double.isNaN(lastSoc) && s.socPct > lastSoc;
            if (!Double.isNaN(s.socPct)) lastSoc = s.socPct;
            boolean charging = still && !Double.isNaN(s.powerKw) && (s.powerKw < 0 || s.powerKw > 5 || socUp);
            // (2026-11-13) a slow home charger (≈3 kW) only showed as "battery % went up" for the one moment the % changed;
            // in between, its power was counted as driving. Once charging is seen, it stays "charging" until the car moves.
            if (charging) chargingLatch = true;
            if (!still) chargingLatch = false;
            charging = charging || chargingLatch;
            if (charging) {
                fromRemaining = false;
                dKwh = 0;
            } else if (!Double.isNaN(s.powerKw)) {
                fromRemaining = false;
                dKwh = s.powerKw * dt / 3600.0;
                if (s.powerKw < 0) regenKwh += -dKwh;
            } else if (!Double.isNaN(s.remainKwh)) {
                fromRemaining = true;
                if (Double.isNaN(startRemain)) startRemain = s.remainKwh;
                dKwh = (startRemain - s.remainKwh) - kwh;
                if (dKwh < 0 && still) { startRemain = s.remainKwh + kwh; dKwh = 0; }   // charged while standing
            } else dKwh = 0;
            calibrate(s, dKm, fromRemaining ? 0 : dKwh, charging);
            dKm *= kmFactor();
            if (!fromRemaining) {
                double f = kwhFactor();
                if (dKwh < 0) regenKwh += -dKwh * (f - 1);   // regen was added uncorrected just above
                dKwh *= f;
            }
            km += dKm;
            kwh += dKwh;
            rollDay();
            dayKm += dKm;
            dayKwh += dKwh;
            l50Add(dKm, dKwh);
            // (user, 2026-11-14, "option 1") what was counted before the correction was ready is corrected once, when it is
            double fk = kmFactor(), fe = kwhFactor();
            if (fk == 1) { rawDayKm += dKm; rawTripKm += dKm; }
            else if (rawDayKm > 0 || rawTripKm > 0) { dayKm -= rawDayKm * (1 - fk); km -= rawTripKm * (1 - fk); rawDayKm = rawTripKm = 0; }
            if (fe != 1) scaleOldHistory(fe);
            if (fe == 1) { if (!fromRemaining) { rawDayKwh += dKwh; rawTripKwh += dKwh; } }
            else if (rawDayKwh != 0 || rawTripKwh != 0) {
                dayKwh -= rawDayKwh * (1 - fe); kwh -= rawTripKwh * (1 - fe); regenKwh *= fe; rawDayKwh = rawTripKwh = 0;
            }
            saveToday(s.at);
            segKm += dKm;
            segKwh += dKwh;
            if (!Double.isNaN(s.speedKmh)) { segSpeedSum += s.speedKmh; segN++; }
            if (!Double.isNaN(s.pedalPct)) { segPedalSum += s.pedalPct; segPedalN++; }
            if (!Double.isNaN(lastAlt) && s.at - lastAltAt < 5000) { altSum += lastAlt; altN++; }
            if (!Double.isNaN(s.powerKw)) { segPowerSum += s.powerKw; segPowerN++; segMinPower = Math.min(segMinPower, s.powerKw); }
            if (fineKm < 0 || km - fineKm >= 0.01) synchronized (this) {
                fine.addLast(new double[]{km, s.speedKmh, s.powerKw});
                fineKm = km;
                while (!fine.isEmpty() && fine.peekFirst()[0] < km - WINDOW_KM - 1) fine.removeFirst();
            }
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
                pt.alt = altN > 0 ? altSum / altN : Double.NaN;
                altSum = 0; altN = 0;
                pt.regen = segPowerN > 0 && segPowerSum / segPowerN <= -0.5;   // the stretch averaged below zero
                segMinPower = 0;
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
        if (!Double.isNaN(s.powerKw)) {
            peakKw = Math.max(peakKw, s.powerKw);
            // regen only while rolling: standing still with energy going in is charging (station / home), not regen
            if (s.speedKmh > 1) maxRegenKw = Math.min(maxRegenKw, s.powerKw);
        }
        if (Double.isNaN(s.speedKmh)) return;
        double v = s.speedKmh;
        segMin = Double.isNaN(segMin) ? v : Math.min(segMin, v);
        segMax = Double.isNaN(segMax) ? v : Math.max(segMax, v);
        if (v > topSpeed) { topSpeed = v; topSpeedKm = km; }
        if (v > 2) { movingSec += dt; daySec += dt; } else stoppedSec += dt;
        if (v > 1 || s.gear >= 2) dayDrv += dt;   // in R / N / D or rolling: driving time, stops at lights included
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
     * Top 10 stretches of bestKm with the lowest kWh/100 km, never overlapping:
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
                if (out.size() == 10) break;   // top 10, best first (user, 2026-11-12)
            }
        }
        best = out;
        // the latest stretch: the last bestKm just driven (shown in blue above the list)
        Best lt = null;
        // Recent shows from the first 100 m: until a full stretch is driven it covers the trip so far (user, 2026-11-13)
        if (n > 0 && w > 0) {
            int st = Math.max(0, n - w);
            double len = (n - st) * STEP_KM;
            double e = 0, t = 0, lo = Double.MAX_VALUE, hi = 0;
            for (int k = st; k < n; k++) { double[] g = segs.get(k); e += g[0]; t += g[1]; if (!Double.isNaN(g[2])) lo = Math.min(lo, g[2]); if (!Double.isNaN(g[3])) hi = Math.max(hi, g[3]); }
            lt = new Best();
            lt.kwh100 = e / len * 100;
            lt.speed = t > 0 ? len / (t / 3600.0) : Double.NaN;
            lt.min = lo == Double.MAX_VALUE ? Double.NaN : lo; lt.max = hi;
            lt.toKm = segs.get(n - 1)[4]; lt.fromKm = lt.toKm - len;
        }
        latest = lt;
        // the last finished stretch: trips are cut into back-to-back blocks of bestKm (0–5, 5–10, …);
        // "Latest" is the newest complete block, fixed until the next one is complete (user, 2026-11-13)
        Best lb = null;
        int blocks = w > 0 ? n / w : 0;
        if (blocks > 0) {
            int st = (blocks - 1) * w;
            double e = 0, t = 0, lo = Double.MAX_VALUE, hi = 0;
            for (int k = st; k < st + w; k++) { double[] g = segs.get(k); e += g[0]; t += g[1]; if (!Double.isNaN(g[2])) lo = Math.min(lo, g[2]); if (!Double.isNaN(g[3])) hi = Math.max(hi, g[3]); }
            lb = new Best();
            lb.kwh100 = e / bestKm * 100;
            lb.speed = t > 0 ? bestKm / (t / 3600.0) : Double.NaN;
            lb.min = lo == Double.MAX_VALUE ? Double.NaN : lo; lb.max = hi;
            lb.toKm = segs.get(st + w - 1)[4]; lb.fromKm = lb.toKm - bestKm;
        }
        lastBlock = lb;
    }
    private volatile Best lastBlock;
    /** The newest finished block of bestKm (fixed); latest() is the moving one being driven now. */
    public Best lastBlock() { return lastBlock; }
    private volatile Best latest;
    public Best latest() { return latest; }
}
