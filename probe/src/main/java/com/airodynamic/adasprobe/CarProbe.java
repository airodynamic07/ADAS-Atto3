package com.airodynamic.adasprobe;

import android.content.Context;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Reads the BYD car "devices" (speed, gear, A/C, body, lights, tyres...) by
 * reflection, the same way OverDrive does. Lists every method each device has,
 * reads every simple getter, and runs the small comfort-only control tests.
 */
public final class CarProbe {

    /** Every BYD device class OverDrive knows about. Missing ones are reported, not an error. */
    public static final String[] DEVICES = {
            "android.hardware.bydauto.speed.BYDAutoSpeedDevice",
            "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice",
            "android.hardware.bydauto.light.BYDAutoLightDevice",
            "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
            "android.hardware.bydauto.doorlock.BYDAutoDoorLockDevice",
            "android.hardware.bydauto.ac.BYDAutoAcDevice",
            "android.hardware.bydauto.tyre.BYDAutoTyreDevice",
            "android.hardware.bydauto.radar.BYDAutoRadarDevice",
            "android.hardware.bydauto.sensor.BYDAutoSensorDevice",
            "android.hardware.bydauto.pm2p5.BYDAutoPM2p5Device",
            "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice",
            "android.hardware.bydauto.energy.BYDAutoEnergyDevice",
            "android.hardware.bydauto.statistic.BYDAutoStatisticDevice",
            "android.hardware.bydauto.charging.BYDAutoChargingDevice",
            "android.hardware.bydauto.power.BYDAutoPowerDevice",
            "android.hardware.bydauto.engine.BYDAutoEngineDevice",
            "android.hardware.bydauto.safetybelt.BYDAutoSafetyBeltDevice",
            "android.hardware.bydauto.setting.BYDAutoSettingDevice",
            "android.hardware.bydauto.multimedia.BYDAutoMultimediaDevice",
            "android.hardware.bydauto.wiper.BYDAutoWiperDevice",
            "android.hardware.bydauto.location.BYDAutoLocationDevice",
            "android.hardware.bydauto.vehiclehealth.BYDAutoVehicleHealthDevice",
            "android.hardware.bydauto.special.BYDAutoSpecialDevice",
            "android.hardware.bydauto.adas.BYDAutoADASDevice",
            "android.hardware.bydauto.dipilot.BYDAutoDiPilotDevice",
            "android.hardware.bydauto.collectdata.BYDAutoCollectDataDevice",
            "android.hardware.bydauto.startup.BYDAutoStartupAppDevice",
            "android.hardware.bydauto.ota.BYDAutoOtaDevice",
            "android.hardware.bydauto.mirror.BYDAutoRearViewMirrorDevice",
            "android.hardware.bydauto.rearview.BYDAutoRearViewMirrorDevice",
            "android.hardware.bydauto.rearviewmirror.BYDAutoRearViewMirrorDevice",
            "android.hardware.bydauto.doormirror.BYDAutoRearViewMirrorDevice",
            "android.hardware.bydauto.rvm.BYDAutoRearViewMirrorDevice",
    };

    /** Devices watched in the "flick your turn signal" test. */
    public static final String[] WATCH_DEVICES = {
            "android.hardware.bydauto.light.BYDAutoLightDevice",
            "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice",
            "android.hardware.bydauto.speed.BYDAutoSpeedDevice",
            "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
            "android.hardware.bydauto.doorlock.BYDAutoDoorLockDevice",
            "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice",
            "android.hardware.bydauto.radar.BYDAutoRadarDevice",
            "android.hardware.bydauto.sensor.BYDAutoSensorDevice",
            "android.hardware.bydauto.safetybelt.BYDAutoSafetyBeltDevice",
            "android.hardware.bydauto.ac.BYDAutoAcDevice",
            "android.hardware.bydauto.pm2p5.BYDAutoPM2p5Device",
    };

    private static final String AC = "android.hardware.bydauto.ac.BYDAutoAcDevice";
    private static final String BODY = "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice";
    private static final String GEARBOX = "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice";
    private static final String SPEED = "android.hardware.bydauto.speed.BYDAutoSpeedDevice";

    /** Getter names we never call (not real readings, or known to misbehave). */
    private static final Pattern SKIP_GETTER = Pattern.compile(
            "(?i)(getInstance|getClass|hashCode|toString|.*learn.*|.*listener.*|.*callback.*|getType)");

    private final Context ctx;
    private final Report r;
    private final Map<String, Object> devices = new LinkedHashMap<>();
    private final ExecutorService exec = Executors.newCachedThreadPool(runnable -> {
        Thread t = new Thread(runnable, "car-call");
        t.setDaemon(true);
        return t;
    });

    public CarProbe(Context ctx, Report r) {
        this.ctx = ctx;
        this.r = r;
    }

    // ------------------------------------------------------------------ basics

    private <T> T timed(Callable<T> c, long ms) throws Exception {
        Future<T> f = exec.submit(c);
        try {
            return f.get(ms, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            f.cancel(true);
            throw new Exception("timed out after " + ms + " ms");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c2 = e.getCause();
            if (c2 instanceof java.lang.reflect.InvocationTargetException && c2.getCause() != null) {
                c2 = c2.getCause();
            }
            throw new Exception(c2 == null ? "error" : c2.getClass().getSimpleName() + ": " + c2.getMessage());
        }
    }

    /** Gets (and caches) one car device. Returns null if this car does not have it. */
    public synchronized Object device(String cls) {
        if (devices.containsKey(cls)) return devices.get(cls);
        Object dev = null;
        try {
            final Class<?> c = Class.forName(cls);
            final Method gi = c.getMethod("getInstance", Context.class);
            dev = timed(() -> gi.invoke(null, ctx), 6000);
        } catch (ClassNotFoundException e) {
            dev = null;
        } catch (Throwable t) {
            dev = null;
        }
        devices.put(cls, dev);
        return dev;
    }

    /** Like {@link #device} but reports why it failed. */
    private Object deviceVerbose(String cls) {
        try {
            Class.forName(cls);
        } catch (Throwable t) {
            r.info("not on this car: " + shortName(cls));
            return null;
        }
        try {
            final Class<?> c = Class.forName(cls);
            final Method gi = c.getMethod("getInstance", Context.class);
            Object dev = timed(() -> gi.invoke(null, ctx), 6000);
            synchronized (this) { devices.put(cls, dev); }
            if (dev == null) r.fail(shortName(cls), "getInstance returned null");
            return dev;
        } catch (Throwable t) {
            synchronized (this) { devices.put(cls, null); }
            r.fail(shortName(cls), t.getMessage());
            return null;
        }
    }

    public static String shortName(String cls) {
        return cls.substring(cls.lastIndexOf('.') + 1);
    }

    public static String fmt(Object v) {
        if (v == null) return "null";
        if (v instanceof int[]) return Arrays.toString((int[]) v);
        if (v instanceof float[]) return Arrays.toString((float[]) v);
        if (v instanceof double[]) return Arrays.toString((double[]) v);
        if (v instanceof long[]) return Arrays.toString((long[]) v);
        if (v instanceof boolean[]) return Arrays.toString((boolean[]) v);
        if (v instanceof byte[]) return "byte[" + ((byte[]) v).length + "]";
        if (v instanceof Object[]) return Arrays.deepToString((Object[]) v);
        return String.valueOf(v);
    }

    private static boolean isSimple(Class<?> t) {
        return t.isPrimitive() && t != void.class || t == String.class || Number.class.isAssignableFrom(t)
                || t == Boolean.class || (t.isArray() && t.getComponentType().isPrimitive());
    }

    private static String signature(Method m) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.getReturnType().getSimpleName()).append(' ').append(m.getName()).append('(');
        Class<?>[] p = m.getParameterTypes();
        for (int i = 0; i < p.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(p[i].getSimpleName());
        }
        return sb.append(')').toString();
    }

    private static List<Method> publicMethods(Object dev) {
        List<Method> out = new ArrayList<>();
        for (Method m : dev.getClass().getMethods()) {
            if (m.getDeclaringClass() == Object.class) continue;
            if (Modifier.isStatic(m.getModifiers())) continue;
            out.add(m);
        }
        Collections.sort(out, new Comparator<Method>() {
            @Override public int compare(Method a, Method b) { return signature(a).compareTo(signature(b)); }
        });
        return out;
    }

    private static boolean isGetterName(String n) {
        return (n.startsWith("get") || n.startsWith("is")) && !SKIP_GETTER.matcher(n).matches();
    }

    // ------------------------------------------------------------------ full dump

    /**
     * For every device: is it there, all its methods (written to `methods`),
     * and the current value of every simple getter.
     */
    public void dumpAll(Report methods) {
        r.section("Car devices (" + (ctx instanceof BypassContext ? "with permission bypass" : "plain") + ")");
        for (String cls : DEVICES) {
            Object dev = deviceVerbose(cls);
            if (dev == null) continue;
            List<Method> ms = publicMethods(dev);
            r.ok(shortName(cls), ms.size() + " methods (" + dev.getClass().getName() + ")");
            methods.section(cls + "  [" + dev.getClass().getName() + "]");
            for (Method m : ms) methods.info(signature(m));
            readGetters(dev, ms);
        }
    }

    private void readGetters(final Object dev, List<Method> ms) {
        int fails = 0;
        for (final Method m : ms) {
            if (!isGetterName(m.getName()) || !isSimple(m.getReturnType())) continue;
            Class<?>[] p = m.getParameterTypes();
            try {
                if (p.length == 0) {
                    Object v = timed(() -> m.invoke(dev), 1500);
                    r.info("    " + m.getName() + "() = " + fmt(v));
                } else if (p.length == 1 && p[0] == int.class) {
                    StringBuilder sb = new StringBuilder("    " + m.getName() + "(0..5) =");
                    for (int a = 0; a <= 5; a++) {
                        final int arg = a;
                        Object v;
                        try {
                            v = timed(() -> m.invoke(dev, arg), 1000);
                        } catch (Exception e) {
                            v = "ERR";
                        }
                        sb.append(' ').append(fmt(v));
                    }
                    r.info(sb.toString());
                }
            } catch (Exception e) {
                fails++;
                r.info("    " + m.getName() + "() -> " + e.getMessage());
                if (fails > 15) {
                    r.info("    (too many failures, skipping the rest of this device)");
                    return;
                }
            }
        }
    }

    /** Dumps every feature id the car's SDK publishes (BYDAutoFeatureIds and its inner classes). */
    public static void dumpFeatureIds(Report out) {
        out.section("BYDAutoFeatureIds");
        try {
            Class<?> c = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds");
            dumpConstants(out, c, "");
            for (Class<?> inner : c.getDeclaredClasses()) dumpConstants(out, inner, inner.getSimpleName() + ".");
        } catch (Throwable t) {
            out.error("BYDAutoFeatureIds", t);
        }
    }

    private static void dumpConstants(Report out, Class<?> c, String prefix) {
        for (Field f : c.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            try {
                f.setAccessible(true);
                out.info(prefix + f.getName() + " = " + fmt(f.get(null)));
            } catch (Throwable ignored) {}
        }
    }

    // ------------------------------------------------------------------ watch test

    /** All simple zero-arg and int-arg (0..4) getter values for the given devices. */
    public Map<String, String> snapshot(String[] classes) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String cls : classes) {
            final Object dev = device(cls);
            if (dev == null) continue;
            String sn = shortName(cls).replace("BYDAuto", "").replace("Device", "");
            for (final Method m : publicMethods(dev)) {
                if (!isGetterName(m.getName()) || !isSimple(m.getReturnType())) continue;
                Class<?>[] p = m.getParameterTypes();
                try {
                    if (p.length == 0) {
                        out.put(sn + "." + m.getName() + "()", fmt(timed(() -> m.invoke(dev), 800)));
                    } else if (p.length == 1 && p[0] == int.class) {
                        for (int a = 0; a <= 4; a++) {
                            final int arg = a;
                            out.put(sn + "." + m.getName() + "(" + a + ")",
                                    fmt(timed(() -> m.invoke(dev, arg), 500)));
                        }
                    }
                } catch (Exception ignored) {
                    // unreadable getters are simply left out
                }
            }
        }
        return out;
    }

    /**
     * Watches all readings for `seconds` and prints every change. The user
     * flicks the turn signals, opens a door, presses the brake meanwhile.
     */
    public void watch(int seconds) {
        r.section("Watch test (" + seconds + " s): flick LEFT signal, then RIGHT, open a door, press brake");
        long t0 = System.currentTimeMillis();
        Map<String, String> prev = snapshot(WATCH_DEVICES);
        r.info("watching " + prev.size() + " readings (one round took "
                + (System.currentTimeMillis() - t0) + " ms)");
        long end = System.currentTimeMillis() + seconds * 1000L;
        int changes = 0;
        while (System.currentTimeMillis() < end) {
            try { Thread.sleep(300); } catch (InterruptedException e) { break; }
            Map<String, String> now = snapshot(WATCH_DEVICES);
            long t = (System.currentTimeMillis() - t0) / 100;
            for (Map.Entry<String, String> e : now.entrySet()) {
                String before = prev.get(e.getKey());
                if (before != null && !before.equals(e.getValue())) {
                    changes++;
                    r.info(String.format(java.util.Locale.US, "  t=%5.1fs  %s : %s -> %s",
                            t / 10.0, e.getKey(), before, e.getValue()));
                }
            }
            prev = now;
        }
        r.info("watch finished, " + changes + " changes seen");
    }

    /** Devices read in the "watch cruise control" test (read only). */
    public static final String[] CRUISE_DEVICES = {
            "android.hardware.bydauto.adas.BYDAutoADASDevice",
            "android.hardware.bydauto.dipilot.BYDAutoDiPilotDevice",
            "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice",
            "android.hardware.bydauto.setting.BYDAutoSettingDevice",
    };

    /**
     * READ ONLY. Lists every ADAS / DiPilot reading, then for `seconds` prints each change with the
     * time, so we can see which numbers follow adaptive cruise (on, set speed, gap, off).
     * Readings that change all the time are hidden after a few changes.
     */
    public void watchCruise(int seconds) {
        r.section("Cruise control watch (" + seconds + " s): turn ACC ON, change set speed, change gap, turn OFF");
        long t0 = System.currentTimeMillis();
        Map<String, String> prev = snapshot(CRUISE_DEVICES);
        r.info("watching " + prev.size() + " readings (one round " + (System.currentTimeMillis() - t0) + " ms)");
        for (Map.Entry<String, String> e : prev.entrySet())
            if (e.getKey().startsWith("ADAS.") || e.getKey().startsWith("DiPilot.")) r.info("  start  " + e.getKey() + " = " + e.getValue());
        Map<String, Integer> count = new java.util.HashMap<>();
        long end = System.currentTimeMillis() + seconds * 1000L;
        int changes = 0;
        while (System.currentTimeMillis() < end) {
            try { Thread.sleep(400); } catch (InterruptedException e) { break; }
            Map<String, String> now = snapshot(CRUISE_DEVICES);
            double t = (System.currentTimeMillis() - t0) / 1000.0;
            for (Map.Entry<String, String> e : now.entrySet()) {
                String before = prev.get(e.getKey());
                if (before == null || before.equals(e.getValue())) continue;
                int n = count.containsKey(e.getKey()) ? count.get(e.getKey()) + 1 : 1;
                count.put(e.getKey(), n);
                if (n > 8) continue;
                changes++;
                r.info(String.format(java.util.Locale.US, "  t=%5.1fs  %s : %s -> %s%s", t, e.getKey(), before, e.getValue(),
                        n == 8 ? "   (keeps changing: hidden from now on)" : ""));
            }
            prev = now;
        }
        r.info("cruise watch finished, " + changes + " changes shown");
    }

    // ------------------------------------------------------------------ live helpers

    private Object read(String cls, String name, Integer arg) {
        final Object dev = device(cls);
        if (dev == null) return null;
        try {
            if (arg == null) {
                final Method m = dev.getClass().getMethod(name);
                return timed(() -> m.invoke(dev), 1500);
            }
            final Method m = dev.getClass().getMethod(name, int.class);
            final int a = arg;
            return timed(() -> m.invoke(dev, a), 1500);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Tries several getter names (no-arg, then with arg 0/1). First non-null wins. */
    private String readAny(String cls, String... names) {
        for (String n : names) {
            Object v = read(cls, n, null);
            if (v == null) v = read(cls, n, 0);
            if (v == null) v = read(cls, n, 1);
            if (v != null) return n + "=" + fmt(v);
        }
        return "(no reading)";
    }

    private static Integer asInt(Object v) {
        return v instanceof Number ? ((Number) v).intValue() : null;
    }

    /** Raw gear code: 0/1 = P, 2 = R, 3 = N, 4 = D (from OverDrive's GearMonitor). */
    public Integer gear() {
        return asInt(read(GEARBOX, "getGearboxAutoModeType", null));
    }

    public Object speed() {
        return read(SPEED, "getCurrentSpeed", null);
    }

    public boolean isParked() {
        Integer g = gear();
        Object s = speed();
        double sp = s instanceof Number ? ((Number) s).doubleValue() : 0;
        return g != null && (g == 0 || g == 1) && sp < 1.0;
    }

    // ------------------------------------------------------------------ comfort-only control tests

    private Object command(String cls, String method, int... args) throws Exception {
        final Object dev = device(cls);
        if (dev == null) throw new Exception(shortName(cls) + " not available");
        Safety.checkCommand(dev, method);
        Class<?>[] types = new Class<?>[args.length];
        final Object[] boxed = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = int.class;
            boxed[i] = args[i];
        }
        final Method m = dev.getClass().getMethod(method, types);
        return timed(() -> m.invoke(dev, boxed), 3000);
    }

    /**
     * Generic feature write set(int[]{id}, BYDAutoEventValue) — used by OverDrive
     * for recirc and defrost on DiLink 3. Only A/C ("Ac.") feature ids are allowed.
     */
    private Object acFeature(String[] acFields, int fallbackId, int value) throws Exception {
        final Object dev = device(AC);
        if (dev == null) throw new Exception("A/C device not available");
        Safety.checkCommand(dev, "set");
        int id = fallbackId;
        String from = "fallback";
        try {
            Class<?> c = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds");
            outer:
            for (String fieldName : acFields) {
                if (!fieldName.startsWith("Ac.")) throw new SecurityException("Only Ac.* feature ids allowed");
                String[] parts = fieldName.split("\\.");
                for (Class<?> inner : c.getDeclaredClasses()) {
                    if (inner.getSimpleName().equals("Ac")) {
                        try {
                            id = inner.getField(parts[1]).getInt(null);
                            from = fieldName;
                            break outer;
                        } catch (NoSuchFieldException ignored) {}
                    }
                }
            }
        } catch (ClassNotFoundException ignored) {}
        r.info("  feature id " + id + " (" + from + ") value " + value);
        Class<?> evc = Class.forName("android.hardware.bydauto.BYDAutoEventValue");
        final Object ev = evc.getConstructor().newInstance();
        evc.getField("intValue").setInt(ev, value);
        final Method set = dev.getClass().getMethod("set", int[].class, evc);
        final int[] ids = {id};
        return timed(() -> set.invoke(dev, ids, ev), 3000);
    }

    private static void pause(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private boolean requireParked(String test) {
        r.section("Control test: " + test);
        Integer g = gear();
        r.info("gear code = " + g + ", speed = " + fmt(speed()));
        if (!isParked()) {
            r.fail(test, "NOT RUN — car must be in P and stopped (gear code 0 or 1)");
            return false;
        }
        return true;
    }

    private void step(String what, ThrowingCall call) {
        try {
            Object res = call.run();
            r.ok(what, "result " + fmt(res) + (Integer.valueOf(0).equals(res) ? " (accepted)" : ""));
        } catch (SecurityException se) {
            r.fail(what, se.getMessage());
        } catch (Exception e) {
            r.fail(what, e.getMessage());
        }
    }

    private interface ThrowingCall { Object run() throws Exception; }

    public void testFan() {
        if (!requireParked("Fan +1 then back")) return;
        String before = readAny(AC, "getAcWindLevel", "getWindLevel");
        r.info("before: " + before);
        Integer lvl = null;
        try { lvl = Integer.parseInt(before.substring(before.indexOf('=') + 1).trim()); } catch (Exception ignored) {}
        if (lvl == null) {
            r.fail("fan", "cannot read the current fan level, so the test is skipped (it could not restore it)");
            return;
        }
        final int target = lvl >= 7 ? lvl - 1 : lvl + 1;
        final int orig = lvl;
        step("setAcWindLevel(0, " + target + ")", () -> command(AC, "setAcWindLevel", 0, target));
        pause(3000);
        r.info("after: " + readAny(AC, "getAcWindLevel", "getWindLevel") + "   <- did the fan change on the car screen?");
        step("restore setAcWindLevel(0, " + orig + ")", () -> command(AC, "setAcWindLevel", 0, orig));
    }

    public void testTemperature() {
        if (!requireParked("Temperature +1 then back")) return;
        Object unitObj = read(AC, "getTemperatureUnit", null);
        final int unit = unitObj instanceof Number ? ((Number) unitObj).intValue() : 1;
        Integer t = asInt(read(AC, "getTemprature", 1));
        if (t == null) t = asInt(read(AC, "getTemprature", 0));
        if (t == null) t = asInt(read(AC, "getAcTemperature", 0));
        r.info("unit = " + fmt(unitObj) + ", current set temperature = " + t);
        if (t == null) {
            r.fail("temperature", "cannot read the current set temperature, test skipped");
            return;
        }
        final int orig = t;
        final int target = t + 1;
        step("setAcTemperature(0, " + target + ", 0, " + unit + ")",
                () -> command(AC, "setAcTemperature", 0, target, 0, unit));
        pause(3000);
        r.info("after: " + fmt(read(AC, "getTemprature", 1)) + " / " + fmt(read(AC, "getTemprature", 0)));
        step("restore setAcTemperature(0, " + orig + ", 0, " + unit + ")",
                () -> command(AC, "setAcTemperature", 0, orig, 0, unit));
    }

    public void testRecirc() {
        if (!requireParked("Recirculation on then off")) return;
        r.info("before: " + readAny(AC, "getAcCycleMode", "getCycleMode"));
        final String[] f = {"Ac.AC_CYCLE_MODE_SET", "Ac.CYCLE_MODE_SET"};
        step("recirc ON (feature write 1)", () -> acFeature(f, 501219355, 1));
        pause(3000);
        r.info("after ON: " + readAny(AC, "getAcCycleMode", "getCycleMode") + "   <- did the recirc icon light up?");
        step("recirc OFF (feature write 0)", () -> acFeature(f, 501219355, 0));
        pause(2000);
        r.info("after OFF: " + readAny(AC, "getAcCycleMode", "getCycleMode"));
    }

    public void testAirDirection() {
        if (!requireParked("Air direction change then back")) return;
        String before = readAny(AC, "getAcWindMode", "getWindMode");
        r.info("before: " + before);
        Integer mode = null;
        try { mode = Integer.parseInt(before.substring(before.indexOf('=') + 1).trim()); } catch (Exception ignored) {}
        if (mode == null) {
            r.fail("air direction", "cannot read current mode, test skipped");
            return;
        }
        final int orig = mode;
        for (int candidate = 1; candidate <= 6; candidate++) {
            if (candidate == orig) continue;
            final int m = candidate;
            step("setAcWindMode(0, " + m + ")", () -> command(AC, "setAcWindMode", 0, m));
            pause(2500);
            r.info("  now: " + readAny(AC, "getAcWindMode", "getWindMode") + "   <- note what the car screen shows");
        }
        step("restore setAcWindMode(0, " + orig + ")", () -> command(AC, "setAcWindMode", 0, orig));
    }

    public void testDefrost() {
        if (!requireParked("Front defrost on then off")) return;
        final String[] f = {"Ac.AC_DEFROST_FRONT_STATE_SET", "Ac.DEFROST_FRONT_SET"};
        step("defrost ON", () -> acFeature(f, 501219362, 1));
        pause(3000);
        r.info("<- did front defrost turn on?");
        step("defrost OFF", () -> acFeature(f, 501219362, 0));
    }

    public void testPurify() {
        if (!requireParked("Air purify on then off")) return;
        r.info("before: " + readAny(AC, "getAutoCleanAirState", "getAcPurifyState"));
        step("setAutoCleanAirState(1) = on", () -> command(AC, "setAutoCleanAirState", 1));
        pause(3000);
        r.info("after ON: " + readAny(AC, "getAutoCleanAirState", "getAcPurifyState"));
        step("setAutoCleanAirState(2) = off", () -> command(AC, "setAutoCleanAirState", 2));
    }

    /**
     * Driver window: this car has no setWindowOpenPercent, so use OverDrive's
     * setAllWindowState(LF, RF, LR, RR) with 1 = open, 2 = close, 3 = stop, 0 = leave alone.
     */
    public void testWindow() {
        if (!requireParked("Driver window: down a little, then up")) return;
        r.info("before: " + fmt(read(BODY, "getWindowOpenPercent", 1)) + " / state " + fmt(read(BODY, "getWindowState", 1)));
        step("setAllWindowState(1,0,0,0) = driver OPEN", () -> command(BODY, "setAllWindowState", 1, 0, 0, 0));
        pause(700);
        step("setAllWindowState(3,0,0,0) = STOP", () -> command(BODY, "setAllWindowState", 3, 0, 0, 0));
        pause(1500);
        r.info("after down: " + fmt(read(BODY, "getWindowOpenPercent", 1)) + "   <- did the driver window go down a little?");
        step("setAllWindowState(2,0,0,0) = driver CLOSE", () -> command(BODY, "setAllWindowState", 2, 0, 0, 0));
        pause(4000);
        step("setAllWindowState(3,0,0,0) = STOP", () -> command(BODY, "setAllWindowState", 3, 0, 0, 0));
        r.info("end: " + fmt(read(BODY, "getWindowOpenPercent", 1)) + " (if it is not fully closed, use the window switch)");
    }

    /**
     * Sunroof nudge: command 1 for about 1 s then STOP, then command 2 for about
     * 1.5 s then STOP. We don't know yet which of 1/2 is open and which is close —
     * that's what this test finds out. STOP is voice code 4 (OverDrive's mapping).
     */
    public void testSunroof() {
        if (!requireParked("Sunroof nudge")) return;
        r.info("before: position=" + fmt(read(BODY, "getSunroofPosition", null))
                + " state=" + fmt(read(BODY, "getSunroofState", null))
                + " windowPercent(5)=" + fmt(read(BODY, "getWindowOpenPercent", 5))
                + " config=" + fmt(read(BODY, "getMoonRoofConfig", null)));
        step("voiceCtlMoonRoof(1)", () -> command(BODY, "voiceCtlMoonRoof", 1));
        pause(1000);
        step("STOP voiceCtlMoonRoof(4)", () -> command(BODY, "voiceCtlMoonRoof", 4));
        step("STOP setMoonRoofAndSunshadeStop()", () -> command(BODY, "setMoonRoofAndSunshadeStop"));
        pause(1500);
        r.info("after cmd 1: position=" + fmt(read(BODY, "getSunroofPosition", null))
                + " windowPercent(5)=" + fmt(read(BODY, "getWindowOpenPercent", 5)));
        step("voiceCtlMoonRoof(2)", () -> command(BODY, "voiceCtlMoonRoof", 2));
        pause(1500);
        step("STOP voiceCtlMoonRoof(4)", () -> command(BODY, "voiceCtlMoonRoof", 4));
        step("STOP setMoonRoofAndSunshadeStop()", () -> command(BODY, "setMoonRoofAndSunshadeStop"));
        pause(1500);
        r.info("after cmd 2: position=" + fmt(read(BODY, "getSunroofPosition", null))
                + " windowPercent(5)=" + fmt(read(BODY, "getWindowOpenPercent", 5)));
        r.info("If the sunroof is not fully closed now, close it with the car's own button.");
    }

    private static final String SETTING = "android.hardware.bydauto.setting.BYDAutoSettingDevice";

    /** Ambient light (IAL): change colour, then brightness, then put both back. */
    public void testAmbient() {
        if (!requireParked("Ambient light colour + brightness, then back")) return;
        Integer col = asInt(read(SETTING, "getIALColor", 1));
        Integer bri = asInt(read(SETTING, "getIALBrightness", 1));
        r.info("before: colour(1)=" + col + " brightness(1)=" + bri + " area=" + fmt(read(SETTING, "getIALArea", null)));
        if (col == null || bri == null || col < 0 || bri < 0) {
            r.fail("ambient", "cannot read the current colour/brightness, test skipped");
            return;
        }
        final int oc = col, ob = bri;
        final int nc = oc == 10 ? 20 : 10;
        step("setIALColor(" + nc + ")", () -> command(SETTING, "setIALColor", nc));
        pause(3000);
        r.info("after colour: colour(1)=" + fmt(read(SETTING, "getIALColor", 1)) + "   <- did the ambient colour change?");
        step("restore setIALColor(" + oc + ")", () -> command(SETTING, "setIALColor", oc));
        pause(1500);
        final int nb = ob <= 2 ? ob + 3 : 1;
        step("setIALBrightness(" + nb + ")", () -> command(SETTING, "setIALBrightness", nb));
        pause(3000);
        r.info("after brightness: brightness(1)=" + fmt(read(SETTING, "getIALBrightness", 1)));
        step("restore setIALBrightness(" + ob + ")", () -> command(SETTING, "setIALBrightness", ob));
    }

    /** Lists methods that look related to the Known unknowns, from every device. */
    public void listUnknowns() {
        r.section("Methods related to Known unknowns");
        Pattern p = Pattern.compile("(?i).*(roof|sunshade|ambient|atmos|ial|rain|wiper|pm2|pm25|purif|clean|ion|"
                + "cycle|windmode|defrost|tyre|tire|humid|inside|incar|temp).*");
        for (String cls : DEVICES) {
            Object dev = device(cls);
            if (dev == null) continue;
            for (Method m : publicMethods(dev)) {
                if (p.matcher(m.getName()).matches()) r.info(shortName(cls) + ": " + signature(m));
            }
        }
    }

    /** Quick check whether the plain (no bypass) context can read the car. */
    public static void plainContextCheck(Context plain, Report r) {
        r.section("Car access WITHOUT permission bypass");
        try {
            Class<?> c = Class.forName(SPEED);
            Object dev = c.getMethod("getInstance", Context.class).invoke(null, plain);
            Object v = dev == null ? null : c.getMethod("getCurrentSpeed").invoke(dev);
            r.ok("speed via plain context", fmt(v));
        } catch (Throwable t) {
            r.error("speed via plain context", t);
        }
    }
}
