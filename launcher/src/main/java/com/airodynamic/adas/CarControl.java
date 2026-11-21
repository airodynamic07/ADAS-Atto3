package com.airodynamic.adas;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * What "Hey Natee chat" may read and change (user, 2026-11-21: "add everything now so I can test at once").
 * Reading: the car's state the launcher already knows. Changing: COMFORT ONLY — fan, temperature, A/C on/off,
 * cabin light, sunshade. Nothing that drives, brakes, steers or assists: those calls simply don't exist here
 * (tools/safety-check.sh still fails the build on any driving-system command).
 * Fan and temperature use the calls the probe confirmed on this car (2026-11-10); A/C on/off, cabin light and
 * sunshade try the likely calls and say exactly what the car answered, so a photo of the chat tells Claude.
 */
final class CarControl {
    private static final String P = "android.hardware.bydauto.";
    private static final String AC = "ac.BYDAutoAcDevice", BODY = "bodywork.BYDAutoBodyworkDevice", LIGHT = "light.BYDAutoLightDevice";

    private final Context c;
    CarControl(Context c) { this.c = c.getApplicationContext(); }

    // ------------------------------------------------------------ tools for Gemini
    static JSONArray tools() throws Exception {
        JSONArray f = new JSONArray();
        f.put(fn("get_car_info", "Read the car's current information: battery %, ranges (NEDC, WLTP, EPA estimate, My range), battery kWh left, "
                + "speed, gear, tyre pressures, PM2.5 outside/inside, outside/cabin/battery temperature, doors/windows open, A/C fan and "
                + "temperature setting, sunshade position, this trip and today (km, kWh used, kWh/100 km, average speed).", null));
        f.put(fn("set_fan", "Set the A/C fan speed.", props("level", "integer", "fan level 1 (lowest) to 7 (highest)")));
        f.put(fn("set_temperature", "Set the A/C temperature.", props("celsius", "number", "target temperature, 17 to 33 °C")));
        f.put(fn("set_ac", "Switch the air conditioning on or off.", props("on", "boolean", "true = on, false = off")));
        f.put(fn("set_cabin_light", "Switch the cabin / reading light on or off.", props("on", "boolean", "true = on, false = off")));
        f.put(fn("sunshade", "Open, close or stop the sunroof sunshade. Only after the driver confirmed it.",
                props("action", "string", "open, close or stop")));
        return new JSONArray().put(new JSONObject().put("functionDeclarations", f));
    }

    private static JSONObject fn(String name, String desc, JSONObject params) throws Exception {
        JSONObject o = new JSONObject().put("name", name).put("description", desc);
        if (params != null) o.put("parameters", params);
        return o;
    }

    private static JSONObject props(String name, String type, String desc) throws Exception {
        return new JSONObject().put("type", "object")
                .put("properties", new JSONObject().put(name, new JSONObject().put("type", type).put("description", desc)))
                .put("required", new JSONArray().put(name));
    }

    /** Runs one tool; the answer goes back to Gemini, which tells the driver. */
    JSONObject run(String name, JSONObject a) {
        try {
            switch (name) {
                case "get_car_info": return info();
                case "set_fan": return fan(a.optInt("level", -1));
                case "set_temperature": return temp(a.optDouble("celsius", Double.NaN));
                case "set_ac": return ac(a.optBoolean("on", true));
                case "set_cabin_light": return light(a.optBoolean("on", true));
                case "sunshade": return shade(a.optString("action", ""));
                default: return res(false, "unknown tool " + name);
            }
        } catch (Throwable e) {
            return res(false, "error: " + e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------ reading
    private JSONObject info() throws Exception {
        CarData.State s = CarData.get(c).last();
        TripEnergy te = TripEnergy.get(c);
        JSONObject o = new JSONObject();
        put(o, "battery_percent", s.socPct);
        double[] r = RangeGraph.Rec.ranges(c, s);
        put(o, "range_nedc_km_car_display", r[0]);
        put(o, "range_wltp_km", r[1]);
        if (!Double.isNaN(r[1])) put(o, "range_epa_estimate_km", r[1] * RangeGraph.EPA_OF_WLTP);
        put(o, "my_range_km_from_own_driving", r[2]);
        put(o, "battery_left_kwh", s.battLeftKwh);
        put(o, "battery_health_percent", s.healthPct);
        put(o, "speed_kmh", s.speedKmh);
        o.put("gear", s.gear == 2 ? "R" : s.gear == 3 ? "N" : s.gear == 4 ? "D" : s.gear >= 0 ? "P" : "unknown");
        JSONObject ty = new JSONObject();
        String[] n = {"front_left", "front_right", "rear_left", "rear_right"};
        for (int i = 0; i < 4; i++) if (!Double.isNaN(s.tyreBar[i])) ty.put(n[i] + "_bar", round(s.tyreBar[i], 2));
        o.put("tyres", ty);
        if (s.pmOut >= 0) o.put("pm25_outside", s.pmOut);
        if (s.pmIn >= 0) o.put("pm25_inside", s.pmIn);
        if (s.outsideC != Integer.MIN_VALUE) o.put("outside_c", s.outsideC);
        put(o, "cabin_c", s.cabinC);
        put(o, "battery_temp_c", s.batteryC);
        put(o, "battery_12v", s.volt12);
        JSONArray open = new JSONArray();
        String[] dn = {"", "driver door (front right)", "front left door", "rear right door", "rear left door", "boot"};
        for (int i = 1; i < s.doorOpen.length && i < dn.length; i++) if (s.doorOpen[i]) open.put(dn[i]);
        for (int i = 1; i < s.windowPct.length; i++) if (s.windowPct[i] > 0) open.put("window " + i + " " + s.windowPct[i] + "%");
        if (s.sunroofOpen) open.put("sunroof/sunshade");
        o.put("open", open);
        Object fan = call(AC, "getAcWindLevel");
        if (fan instanceof Number) o.put("ac_fan_level", ((Number) fan).intValue());
        Object t = call(AC, "getTemprature", 1);
        if (!(t instanceof Number)) t = call(AC, "getTemprature", 0);
        if (t instanceof Number) o.put("ac_set_temperature", ((Number) t).intValue());
        JSONObject trip = new JSONObject();
        trip.put("km", round(te.km, 1)).put("kwh_used", round(te.kwh, 1));
        put(trip, "kwh_per_100km", te.avg());
        put(trip, "avg_speed_kmh", te.avgWithStops());
        o.put("this_trip", trip);
        JSONObject day = new JSONObject();
        day.put("km", round(te.todayKm(), 1)).put("kwh_used", round(te.todayKwh(), 1));
        if (te.todayKm() > 0.2) day.put("kwh_per_100km", round(te.todayKwh() / te.todayKm() * 100, 1));
        o.put("today", day);
        return o;
    }

    // ------------------------------------------------------------ comfort controls
    private JSONObject fan(int level) {
        if (level < 1 || level > 7) return res(false, "fan level must be 1 to 7");
        Object v = call(AC, "setAcWindLevel", 0, level);   // confirmed on this car (probe: fan 3 → 4)
        return answer(v, "fan " + level);
    }

    private JSONObject temp(double celsius) {
        if (Double.isNaN(celsius) || celsius < 17 || celsius > 33) return res(false, "temperature must be 17 to 33 °C");
        Object u = call(AC, "getTemperatureUnit");
        int unit = u instanceof Number ? ((Number) u).intValue() : 1;
        int v = (int) Math.round(celsius);
        Object r = call(AC, "setAcTemperature", 0, v, 0, unit);   // confirmed on this car (probe: temperature +1)
        return answer(r, "temperature " + v + " °C");
    }

    private JSONObject ac(boolean on) {
        // not tested on this car yet: the likely calls, first one that exists wins
        String[][] tries = on ? new String[][]{{"start", "0"}, {"setAcStartState", "1"}, {"setAcPower", "1"}}
                : new String[][]{{"stop", "0"}, {"setAcStartState", "0"}, {"setAcPower", "0"}};
        return tryCalls(AC, tries, on ? "A/C on" : "A/C off");
    }

    private JSONObject light(boolean on) {
        // not tested on this car yet: look for a reading / dome / roof light call on the light and bodywork devices
        for (String dev : new String[]{LIGHT, BODY}) {
            Object d = CarDev.get(c, dev);
            if (d == null) continue;
            for (Method m : d.getClass().getMethods()) {
                String nm = m.getName().toLowerCase(Locale.US);
                if (!nm.startsWith("set") || !(nm.contains("read") || nm.contains("dome") || nm.contains("roof") && nm.contains("light")
                        || nm.contains("interior") || nm.contains("ceiling") || nm.contains("cabin"))) continue;
                if (!(nm.contains("light") || nm.contains("lamp"))) continue;
                Class<?>[] p = m.getParameterTypes();
                try {
                    Object r;
                    if (p.length == 1 && p[0] == int.class) r = m.invoke(d, on ? 1 : 0);
                    else if (p.length == 2 && p[0] == int.class && p[1] == int.class) r = m.invoke(d, 0, on ? 1 : 0);
                    else continue;
                    JSONObject a = answer(r, (on ? "cabin light on" : "cabin light off"));
                    a.put("call", m.getName());
                    return a;
                } catch (Throwable ignored) { }
            }
        }
        return res(false, "this car has no cabin light control the launcher can use yet");
    }

    private JSONObject shade(String action) {
        String a = action.toLowerCase(Locale.US);
        int code = a.startsWith("open") ? 1 : a.startsWith("close") ? 2 : a.startsWith("stop") ? 4 : -1;
        if (code < 0) return res(false, "say open, close or stop");
        if (code == 4) { call(BODY, "setMoonRoofAndSunshadeStop"); return answer(call(BODY, "voiceCtlMoonRoof", 4), "sunshade stopped"); }
        Object r = call(BODY, "voiceCtlMoonRoof", code);   // the car accepted this in the probe test; direction not confirmed yet
        JSONObject o = answer(r, "sunshade " + (code == 1 ? "opening" : "closing"));
        try { o.put("note", "first test: if it moved the wrong way, tell the driver to say stop and tell Claude"); } catch (Exception ignored) { }
        return o;
    }

    // ------------------------------------------------------------ helpers
    private JSONObject tryCalls(String dev, String[][] tries, String what) {
        for (String[] t : tries) {
            Object r = call(dev, t[0], Integer.parseInt(t[1]));
            if (r != MISSING) { JSONObject a = answer(r, what); try { a.put("call", t[0] + "(" + t[1] + ")"); } catch (Exception ignored) { } return a; }
        }
        return res(false, "this car has no " + what + " command the launcher can use yet");
    }

    private JSONObject answer(Object r, String what) {
        if (r == MISSING) return res(false, "the car has no command for " + what);
        if (r == FAILED) return res(false, "the car refused " + what);
        boolean ok = !(r instanceof Number) || ((Number) r).intValue() == 0;   // BYD calls answer 0 when accepted
        JSONObject o = res(ok, ok ? what + ": done" : what + ": the car answered " + r);
        return o;
    }

    private static JSONObject res(boolean ok, String msg) {
        try { return new JSONObject().put("ok", ok).put("result", msg); } catch (Exception e) { return new JSONObject(); }
    }

    private static void put(JSONObject o, String k, double v) throws Exception { if (!Double.isNaN(v)) o.put(k, round(v, 1)); }
    private static double round(double v, int d) { double f = Math.pow(10, d); return Math.round(v * f) / f; }

    private static final Object MISSING = new Object(), FAILED = new Object();

    private Object call(String dev, String name, int... args) {
        Object d = CarDev.get(c, dev);
        if (d == null) return MISSING;
        Class<?>[] types = new Class<?>[args.length];
        Object[] boxed = new Object[args.length];
        for (int i = 0; i < args.length; i++) { types[i] = int.class; boxed[i] = args[i]; }
        Method m;
        try { m = d.getClass().getMethod(name, types); } catch (Throwable e) { return MISSING; }
        try { Object r = m.invoke(d, boxed); return r == null ? 0 : r; } catch (Throwable e) { return FAILED; }
    }

    /** The car's device objects, found by name (same way CarData reads them). */
    static final class CarDev {
        private static final java.util.Map<String, Object> devs = new java.util.HashMap<>();
        static synchronized Object get(Context c, String cls) {
            if (devs.containsKey(cls)) return devs.get(cls);
            Object d = null;
            try { d = Class.forName(P + cls).getMethod("getInstance", Context.class).invoke(null, c); } catch (Throwable ignored) { }
            devs.put(cls, d);
            return d;
        }
    }
}
