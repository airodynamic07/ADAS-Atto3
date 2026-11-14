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
        /** new row (user, 2026-11-13): NaN until a working reading is found */
        public double cabinC = Double.NaN, batteryC = Double.NaN, volt12 = Double.NaN;
        public int pmIn = -1, pmOut = -1;
        public final double[] tyreBar = {Double.NaN, Double.NaN, Double.NaN, Double.NaN}; // FL FR RL RR
        public int turn;                      // 0 none, 1 left, 2 right, 3 hazard
        public int[] radar = new int[0];      // cm per sensor, 150 = nothing near
        public final int[] tyreC = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        public double odometerKm = Double.NaN;
        public double tripKm = Double.NaN;      // trip A
        public double kwhPer100 = Double.NaN;   // average consumption
        public double healthPct = Double.NaN;   // battery health index (if the car reports it)
        public final boolean[] doorOpen = new boolean[6];     // car area numbers: 1 FR (driver, RHD), 2 FL, 3 RR, 4 RL, 5 boot
        public final int[] windowPct = {0, 0, 0, 0, 0};       // 0 unused, 1..4 windows
        public boolean sunroofOpen;
        public int roofPos = Integer.MIN_VALUE, roofState = Integer.MIN_VALUE;   // raw sunroof readings (meaning still to confirm)
        public String bootRaw = "";
        public int drl = -1;                    // daytime running lights: 1 on, 0 off, -1 unknown (read only)
        public String drlRaw = "";             // raw back-door readings (which one works is still to confirm)
        public int lock;                        // 0 unknown, 1 locked, 2 unlocked
        public double lat = Double.NaN, lng = Double.NaN;
        public double pedalPct = Double.NaN;    // accelerator 0-100
        public double powerKw = Double.NaN;     // motor power: + driving, - regen
        public double remainKwh = Double.NaN;   // energy left in the battery
        public double totalKwh = Double.NaN;    // the car's own "energy used since new" counter, if it has one
        public long at;                         // when this snapshot was read (ms)

        public boolean anyOpen() {
            for (boolean d : doorOpen) if (d) return true;
            for (int w : windowPct) if (w > 0) return true;
            return sunroofOpen;
        }
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

    public void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); l.onCar(last); start(); }
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

    private synchronized Object dev(String cls) {
        if (devices.containsKey(cls)) return devices.get(cls);
        Object d = null;
        try { d = Class.forName(P + cls).getMethod("getInstance", Context.class).invoke(null, ctx); } catch (Throwable ignored) {}
        devices.put(cls, d);
        return d;
    }

    /** Fast read for Engine Sound (20 times a second): {km/h, accelerator 0-100 or NaN}. */
    public double[] speedAndPedal() {
        Object v = call("speed.BYDAutoSpeedDevice", "getCurrentSpeed");
        Object p = call("speed.BYDAutoSpeedDevice", "getAccelerateDeepness");
        double kmh = ok(v) ? Math.max(0, ((Number) v).doubleValue()) : 0;
        double pedal = ok(p) && ((Number) p).doubleValue() >= 0 && ((Number) p).doubleValue() <= 100 ? ((Number) p).doubleValue() : Double.NaN;
        return new double[]{kmh, pedal};
    }

    public boolean onBydCar() {
        try { Class.forName(P + "speed.BYDAutoSpeedDevice"); return true; } catch (Throwable t) { return false; }
    }

    /**
     * Interior ambient light (IAL) only: the one thing the launcher may change on the car.
     * Anything else is refused here. Not a driving system (hard rule 1).
     */
    public Object ambient(String method, int v) {
        if (!method.matches("(get|set)IAL(Color|Brightness)")) return null;
        return call("setting.BYDAutoSettingDevice", method, v);
    }

    /**
     * First reading that gives a believable number (lo..hi). Values 10x too big (e.g. 126 for 12.6 V) are scaled
     * when decimals > 0. A reading picked in Settings ("prefs: read_<name>") is tried first. Read only.
     */
    private boolean searching;
    /**
     * The guessed readings gave nothing on this car (cards stayed "–", 2026-11-13): look through every reading once,
     * in the background, for one whose name and value fit, and remember it. Read only.
     */
    private void autoFind(Context c) {
        if (searching) return;
        final android.content.SharedPreferences sp = c.getSharedPreferences("adas_launcher", 0);
        if (System.currentTimeMillis() - sp.getLong("read_searched2", 0) < 6 * 3600_000L) return;
        searching = true;
        new Thread(() -> {
            try {
                Map<String, Double> all = snapshotAll("statistic.BYDAutoStatisticDevice", "instrument.BYDAutoInstrumentDevice",
                        "bodywork.BYDAutoBodyworkDevice", "bms.BYDAutoBmsDevice", "energy.BYDAutoEnergyDevice",
                        "power.BYDAutoPowerDevice", "engine.BYDAutoEngineDevice", "charging.BYDAutoChargingDevice");
                android.content.SharedPreferences.Editor ed = sp.edit().putLong("read_searched2", System.currentTimeMillis());
                String volt = null, batt = null;
                for (Map.Entry<String, Double> e : all.entrySet()) {
                    String m = e.getKey().split("\\|")[1].toLowerCase(java.util.Locale.US);
                    double v = e.getValue();
                    if (volt == null && m.contains("volt") && !m.contains("cell") && ((v >= 9 && v <= 16) || (v >= 90 && v <= 160))) volt = e.getKey();
                    if (batt == null && m.contains("temp") && (m.contains("batt") || m.contains("cell") || m.contains("pack"))
                            && v >= -20 && v <= 70) batt = e.getKey();
                }
                if (volt != null && sp.getString("read_volt12", "").isEmpty()) ed.putString("read_volt12", volt);
                if (batt != null && sp.getString("read_battery", "").isEmpty()) ed.putString("read_battery", batt);
                ed.apply();
            } catch (Throwable ignored) { }
            searching = false;
        }).start();
    }

    private double pick(Context c, String name, String[][] tries, double lo, double hi, int decimals) {
        String chosen = c.getSharedPreferences("adas_launcher", 0).getString("read_" + name, "");
        java.util.List<String> keys = new java.util.ArrayList<>();
        if (!chosen.isEmpty()) keys.add(chosen);
        for (String[] t : tries) keys.add(t[0] + "|" + t[1] + "|" + (t[2].isEmpty() ? "-1" : t[2]));
        for (String k : keys) {
            Double v;
            try { v = readKeyD(k); } catch (Throwable e) { v = null; }
            if (v == null || !ok(v)) continue;
            double d = v;
            if (d >= lo && d <= hi) return d;
            if (decimals == 0 && d / 10 >= lo && d / 10 <= hi) return d / 10;   // 126 → 12.6 V
            if (d / 10 >= lo && d / 10 <= hi && decimals > 0) return d / 10;
        }
        if (!"cabin".equals(name)) autoFind(c);
        return Double.NaN;
    }

    /** Reads one "device|method|arg" key (arg -1 = no argument). Read only. */
    /** Same as readKey but keeps decimals (12.6 V). */
    public Double readKeyD(String key) {
        String[] k = key.split("\\|");
        if (k.length != 3) return null;
        int a = Integer.parseInt(k[2]);
        Object v = a < 0 ? call(k[0], k[1]) : call(k[0], k[1], a);
        return v instanceof Number ? ((Number) v).doubleValue() : v instanceof Boolean ? ((Boolean) v ? 1.0 : 0.0) : null;
    }

    /** Every number reading of these devices, decimals included ("device|method|arg" → value). Read only. */
    public Map<String, Double> snapshotAll(String... classes) {
        Map<String, Double> out = new java.util.TreeMap<>();
        for (String cls : classes) {
            Object d = dev(cls);
            if (d == null) continue;
            for (Method m : d.getClass().getMethods()) {
                String n = m.getName();
                if (!(n.startsWith("get") || n.startsWith("is")) || m.getDeclaringClass() == Object.class) continue;
                Class<?> r = m.getReturnType();
                if (!(r == int.class || r == double.class || r == float.class || r == long.class || r == Integer.class
                        || r == Double.class || r == Float.class)) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 0) putD(out, cls + "|" + n + "|-1");
                else if (p.length == 1 && p[0] == int.class) for (int a = 0; a <= 5; a++) putD(out, cls + "|" + n + "|" + a);
            }
        }
        return out;
    }
    private void putD(Map<String, Double> out, String key) {
        try { Double v = readKeyD(key); if (v != null && ok(v) && !v.isNaN()) out.put(key, v); } catch (Throwable ignored) { }
    }

    public Integer readKey(String key) {
        String[] k = key.split("\\|");
        if (k.length != 3) return null;
        int a = Integer.parseInt(k[2]);
        Object v = a < 0 ? call(k[0], k[1]) : call(k[0], k[1], a);
        return v instanceof Number ? ((Number) v).intValue() : v instanceof Boolean ? ((Boolean) v ? 1 : 0) : null;
    }

    /**
     * Every simple reading of these devices ("device|method|arg" → value), to find which one changes
     * when the driver switches something in BYD's own settings (Settings → Find DRL reading).
     * Only get…/is… methods are called: nothing on the car is changed.
     */
    public Map<String, Integer> snapshot(String... classes) {
        Map<String, Integer> out = new java.util.TreeMap<>();
        for (String cls : classes) {
            Object d = dev(cls);
            if (d == null) continue;
            for (Method m : d.getClass().getMethods()) {
                String n = m.getName();
                if (!(n.startsWith("get") || n.startsWith("is")) || m.getDeclaringClass() == Object.class) continue;
                Class<?> r = m.getReturnType();
                if (!(r == int.class || r == boolean.class || r == Integer.class || r == Boolean.class)) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 0) put(out, cls + "|" + n + "|-1");
                else if (p.length == 1 && p[0] == int.class) for (int a = 0; a <= 5; a++) put(out, cls + "|" + n + "|" + a);
            }
        }
        return out;
    }
    private void put(Map<String, Integer> out, String key) {
        try { Integer v = readKey(key); if (v != null && ok(v)) out.put(key, v); } catch (Throwable ignored) { }
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

    /**
     * Generic feature read: device.get(int[]{id}, <primitive type>) (OverDrive's method).
     * The car only answers when asked with the primitive type (int.class, not Integer.class),
     * and replies with a BYDAutoEventValue whose intValue field holds the number.
     */
    private Integer feature(String cls, int id) {
        Object d = dev(cls);
        if (d == null) return null;
        Method m = null;
        for (Class<?> k = d.getClass(); k != null && k != Object.class && m == null; k = k.getSuperclass()) {
            try { m = k.getDeclaredMethod("get", int[].class, Class.class); m.setAccessible(true); } catch (Throwable ignored) {}
        }
        if (m == null) return null;
        for (Class<?> t : new Class<?>[]{int.class, long.class}) {
            try {
                Integer v = intOf(m.invoke(d, new int[]{id}, t));
                if (v != null) return v;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** device.get(deviceType, featureId) — the other read channel some firmwares use. */
    private Integer featureTyped(String cls, int type, int id) {
        Object d = dev(cls);
        if (d == null) return null;
        try {
            Method m = d.getClass().getMethod("get", int.class, int.class);
            return intOf(m.invoke(d, type, id));
        } catch (Throwable t) { return null; }
    }

    private static Integer intOf(Object r) {
        if (r == null) return null;
        if (r instanceof int[]) return ((int[]) r).length > 0 ? ((int[]) r)[0] : null;
        if (r instanceof Number) return ((Number) r).intValue();
        try { return r.getClass().getField("intValue").getInt(r); } catch (Throwable t) { return null; }
    }

    /** Motor power in kW (OverDrive's reads): getter first, then the feature id as a double. -1 means "no value". */
    private double power() {
        Object o = call("engine.BYDAutoEngineDevice", "getEnginePower");
        if (o instanceof Number) {
            double kw = ((Number) o).doubleValue();
            if (kw >= -200 && kw <= 400 && kw != -1) return kw;
        }
        Object d = dev("engine.BYDAutoEngineDevice");
        if (d == null) return Double.NaN;
        for (Class<?> k = d.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod("get", int[].class, Class.class);
                m.setAccessible(true);
                Object r = m.invoke(d, new int[]{339738656}, double.class);   // ENGINE_POWER
                if (r == null) return Double.NaN;
                double raw = r instanceof Number ? ((Number) r).doubleValue() : r.getClass().getField("doubleValue").getDouble(r);
                double kw = raw > 100 ? raw * 0.1 : raw;
                return raw >= -200 && raw <= 4000 && kw != -1 ? kw : Double.NaN;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) { return Double.NaN; }
        }
        return Double.NaN;
    }

    private double health = Double.NaN;
    private long healthAt;

    /** Battery health %, tried the ways OverDrive does; read again every 10 minutes once found. */
    private double batteryHealth() {
        long now = System.currentTimeMillis();
        if (!Double.isNaN(health) && now - healthAt < 600_000L) return health;
        if (Double.isNaN(health) && now - healthAt < 30_000L) return health;   // not found: retry every 30 s
        healthAt = now;
        Integer v = featureTyped("instrument.BYDAutoInstrumentDevice", 1000, 0x44400028);
        if (!plausibleHealth(v)) { Object o = call("statistic.BYDAutoStatisticDevice", "getStatisticBatteryHealthyIndex"); v = o instanceof Number ? ((Number) o).intValue() : null; }
        if (!plausibleHealth(v)) v = featureTyped("statistic.BYDAutoStatisticDevice", 1014, 1145045032);
        if (!plausibleHealth(v)) v = feature("statistic.BYDAutoStatisticDevice", 1145045032);   // STATISTIC_BATTERY_HEALTHY_INDEX
        if (!plausibleHealth(v)) { Object o = call("vehiclehealth.BYDAutoVehicleHealthDevice", "getBatteryHealthStatus"); v = o instanceof Number && ((Number) o).intValue() >= 50 ? ((Number) o).intValue() : null; }
        health = plausibleHealth(v) ? v : Double.NaN;
        return health;
    }

    private static boolean plausibleHealth(Integer v) { return v != null && v > 0 && v <= 100; }

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
        // cabin / battery temperature / 12 V: a reading chosen in Settings → Find readings wins; else try the likely ones
        s.cabinC = pick(ctx, "cabin", new String[][]{{"ac.BYDAutoAcDevice", "getTemprature", "3"}, {"ac.BYDAutoAcDevice", "getInsideTemperature", ""},
                {"instrument.BYDAutoInstrumentDevice", "getInCarTemperature", ""}, {"ac.BYDAutoAcDevice", "getTemperature", "3"}}, -30, 70, 1);
        s.batteryC = pick(ctx, "battery", new String[][]{{"statistic.BYDAutoStatisticDevice", "getBatteryTemperature", ""},
                {"bms.BYDAutoBmsDevice", "getBatteryTemperature", ""}, {"energy.BYDAutoEnergyDevice", "getBatteryTemperature", ""},
                {"statistic.BYDAutoStatisticDevice", "getAverageBatteryTemperature", ""},
                {"statistic.BYDAutoStatisticDevice", "getHighestBatteryTemperature", ""}, {"statistic.BYDAutoStatisticDevice", "getBatteryHighestTemp", ""},
                {"energy.BYDAutoEnergyDevice", "getPowerBatteryTemperature", ""}, {"bms.BYDAutoBmsDevice", "getMaxCellTemperature", ""},
                {"bms.BYDAutoBmsDevice", "getHighestTemperature", ""}, {"charging.BYDAutoChargingDevice", "getBatteryTemperature", ""}}, -30, 80, 1);
        s.volt12 = pick(ctx, "volt12", new String[][]{{"bodywork.BYDAutoBodyworkDevice", "getBatteryVoltage", ""},
                {"statistic.BYDAutoStatisticDevice", "getLowVoltageBatteryVoltage", ""}, {"power.BYDAutoPowerDevice", "getBatteryVoltage", ""},
                {"instrument.BYDAutoInstrumentDevice", "getBatteryVoltage", ""}}, 8, 16, 0);
        v = call("pm2p5.BYDAutoPM2p5Device", "getPM2p5Value");
        if (v instanceof int[] && ((int[]) v).length >= 2) { s.pmIn = ((int[]) v)[0]; s.pmOut = ((int[]) v)[1]; }
        for (int i = 0; i < 4; i++) {
            v = call("tyre.BYDAutoTyreDevice", "getTyrePressureValue", i + 1);
            if (ok(v) && ((Number) v).intValue() > 0) s.tyreBar[i] = ((Number) v).intValue() / 100.0;   // kPa -> bar
        }
        // Daytime running lights: which reading the Atto 3 uses is still to confirm, so try the likely ones
        // and show the raw values (2026-11-12). READ ONLY: NATEE never switches the lights.
        StringBuilder drl = new StringBuilder();
        String[][] drlCalls = {{"light.BYDAutoLightDevice", "getDaytimeRunningLightState"}, {"light.BYDAutoLightDevice", "getDRLState"},
                {"light.BYDAutoLightDevice", "getDayRunningLightState"}, {"light.BYDAutoLightDevice", "getDaytimeRunningLampState"},
                {"setting.BYDAutoSettingDevice", "getDaytimeRunningLightOnOff"}, {"setting.BYDAutoSettingDevice", "getDRLOnOff"},
                {"setting.BYDAutoSettingDevice", "getDaytimeRunningLight"}};
        s.drl = -1;
        // learned in Settings → "Find DRL reading": device|method|arg and the value that means ON
        String learned = Prefs.drlKey(ctx);
        if (!learned.isEmpty()) {
            Integer lv = readKey(learned);
            if (lv != null) { s.drl = lv == Prefs.drlOn(ctx) ? 1 : 0; drl.append(learned.split("\\|")[1].replace("get", "")).append('=').append(lv).append(' '); }
        }
        for (String[] dc : drlCalls) {
            if (!learned.isEmpty()) break;
            v = call(dc[0], dc[1]);
            if (!ok(v) || !(v instanceof Number)) continue;
            int iv = ((Number) v).intValue();
            drl.append(dc[1].replace("get", "").replace("Daytime", "Day").replace("State", "")).append('=').append(iv).append(' ');
            if (s.drl < 0) s.drl = iv == 1 ? 1 : 0;
        }
        s.drlRaw = drl.toString().trim();
        // Flash state (confirmed): 1 off, 2/3 left, 4/5 right, 6/7 hazard
        if (ok(v = call("light.BYDAutoLightDevice", "getTurnLightFlashState"))) {
            int f = ((Number) v).intValue();
            s.turn = (f == 2 || f == 3) ? 1 : (f == 4 || f == 5) ? 2 : (f == 6 || f == 7) ? 3 : 0;
        }
        for (int i = 0; i < 4; i++) {
            if (ok(v = call("instrument.BYDAutoInstrumentDevice", "getWheelTemperature", i + 1))) s.tyreC[i] = ((Number) v).intValue();
        }
        // Odometer: whole km, or 0.1 km when the raw value is >= 1,000,000 (OverDrive's rule)
        if (ok(v = call("statistic.BYDAutoStatisticDevice", "getTotalMileageValue")) && ((Number) v).doubleValue() > 0) {
            double raw = ((Number) v).doubleValue();
            s.odometerKm = raw >= 1_000_000 ? raw / 10.0 : raw;
        }
        if (ok(v = call("statistic.BYDAutoStatisticDevice", "getMileageNumber", 0)) && ((Number) v).doubleValue() >= 0)
            s.tripKm = ((Number) v).doubleValue() / 10.0;
        if (ok(v = call("statistic.BYDAutoStatisticDevice", "getAverageElectricConsumption", 1)) && ((Number) v).doubleValue() > 0)
            s.kwhPer100 = ((Number) v).doubleValue();
        if (ok(v = call("statistic.BYDAutoStatisticDevice", "getTotalElecConValue")) && ((Number) v).doubleValue() > 0)
            s.totalKwh = ((Number) v).doubleValue();
        s.healthPct = batteryHealth();
        s.powerKw = power();
        s.pedalPct = speedAndPedal()[1];
        if (ok(v = call("statistic.BYDAutoStatisticDevice", "getRemainingBatteryPower")) && ((Number) v).intValue() > 10 && ((Number) v).intValue() < 1200)
            s.remainKwh = ((Number) v).intValue() / 10.0;   // 0.1 kWh units
        s.at = System.currentTimeMillis();
        for (int a = 1; a <= 5; a++) {
            if (ok(v = call("bodywork.BYDAutoBodyworkDevice", "getDoorState", a))) s.doorOpen[a] = ((Number) v).intValue() == 1;
        }
        for (int a = 1; a <= 4; a++) {
            if (ok(v = call("bodywork.BYDAutoBodyworkDevice", "getWindowOpenPercent", a))) s.windowPct[a] = Math.max(0, ((Number) v).intValue());
        }
        if (ok(v = call("bodywork.BYDAutoBodyworkDevice", "getWindowOpenPercent", 5))) s.sunroofOpen = ((Number) v).intValue() > 0;
        // Back door (tailgate): getDoorState(5) never changed (2026-11-12), so try the other likely readings too
        // and keep the raw values on screen until we know which one the Atto 3 uses. Read only.
        StringBuilder boot = new StringBuilder();
        String[][] bootCalls = {{"getDoorState", "5"}, {"getDoorState", "6"}, {"getTrunkState", ""}, {"getBackDoorState", ""},
                {"getTailgateState", ""}, {"getTrunkDoorState", ""}, {"getElectricTailgateState", ""}};
        for (String[] bc : bootCalls) {
            v = bc[1].isEmpty() ? call("bodywork.BYDAutoBodyworkDevice", bc[0]) : call("bodywork.BYDAutoBodyworkDevice", bc[0], Integer.parseInt(bc[1]));
            if (!ok(v) || !(v instanceof Number)) continue;
            int iv = ((Number) v).intValue();
            boot.append(bc[0].replace("get", "").replace("State", "")).append(bc[1]).append('=').append(iv).append(' ');
            if (iv == 1) s.doorOpen[5] = true;
        }
        s.bootRaw = boot.toString().trim();
        // Sunroof position/state (Phase 0 found these; values not confirmed yet). Position 2 (or 0) is closed.
        // Position 2 = closed (seen in the car, 2026-11-11). When the position is known it decides, so the
        // other reading can't make it flicker between closed and open.
        if (ok(v = call("bodywork.BYDAutoBodyworkDevice", "getSunroofPosition"))) { s.roofPos = ((Number) v).intValue(); s.sunroofOpen = s.roofPos != 2 && s.roofPos != 0; }
        if (ok(v = call("bodywork.BYDAutoBodyworkDevice", "getSunroofState"))) s.roofState = ((Number) v).intValue();
        // Lock: OTA device's left-front lock state. On the user's Atto 3 (car test 2026-11-10)
        // the raw value is the opposite of OverDrive's note: 1 = unlocked, 2 = locked.
        if (ok(v = call("ota.BYDAutoOtaDevice", "getLFDoorLockState"))) {
            int l = ((Number) v).intValue();
            s.lock = l == 2 ? 1 : l == 1 ? 2 : 0;
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
