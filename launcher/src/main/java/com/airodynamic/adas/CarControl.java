package com.airodynamic.adas;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * What "Hey Natee chat" may read and change (user, 2026-11-21: "add everything now so I can test at once").
 * Reading: the car's state the launcher already knows. Changing: COMFORT ONLY — fan, temperature, A/C on/off, A/C auto, defrost, air direction, recirculation, purifier,
 * cabin light, sunshade. Nothing that drives, brakes, steers or assists: those calls simply don't exist here
 * (tools/safety-check.sh still fails the build on any driving-system command).
 * Fan and temperature use the calls the probe confirmed on this car (2026-11-10); A/C on/off, cabin light and
 * sunshade try the likely calls and say exactly what the car answered, so a photo of the chat tells Claude.
 */
final class CarControl {
    private static final String P = "android.hardware.bydauto.";
    private static final String AC = "ac.BYDAutoAcDevice", BODY = "bodywork.BYDAutoBodyworkDevice", LIGHT = "light.BYDAutoLightDevice";

    private final Context c;
    /** The chat so far, saved with a problem report (set by AiChat). */
    volatile String chatSoFar = "";
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
        f.put(fn("set_defrost", "Front windscreen defrost (ไล่ฝ้า / ไล่ฝ้ากระจกหน้า) on or off.", props("on", "boolean", "true = on, false = off")));
        f.put(fn("set_air_direction", "Where the A/C blows (ทิศทางลม): face (เป่าหน้า), face_feet (หน้าและขา), feet (เป่าขา), "
                + "feet_windscreen (ขาและกระจก), windscreen (กระจก).", props("direction", "string", "face, face_feet, feet, feet_windscreen or windscreen")));
        f.put(fn("set_recirculation", "Inside air / recirculation (หมุนเวียนอากาศภายใน) or fresh outside air (อากาศภายนอก).",
                props("inside", "boolean", "true = inside air (recirculation), false = outside air")));
        f.put(fn("set_purifier", "The A/C air purifier / clean air (ฟอกอากาศ) on or off.", props("on", "boolean", "true = on, false = off")));
        f.put(fn("set_ac_auto", "A/C AUTO mode (แอร์ออโต้) on or off.", props("on", "boolean", "true = on, false = off")));
        f.put(fn("set_range_view", "Which view the range graph (and its numbers) shows: trip (this trip), today, or charge (charge to charge).",
                props("view", "string", "trip, today or charge")));
        JSONObject gp = new JSONObject().put("type", "object").put("properties", new JSONObject()
                .put("speed_average_m", new JSONObject().put("type", "integer").put("description", "speed line moving average in metres: 0 = real time, or 100, 200, 300, 500, 1000"))
                .put("power_average_m", new JSONObject().put("type", "integer").put("description", "power / consumption line moving average in metres: 0 = real time, or 100, 200, 300, 500, 1000"))
                .put("zoom", new JSONObject().put("type", "string").put("description", "normal (last 20 km) or extended (zoom extend: the whole trip)")));
        f.put(fn("set_trip_graph", "The graph along the bottom of the screen (speed and power / consumption): moving average of each line, and zoom. Pass only what the driver asked to change.", gp));
        f.put(fn("set_screen_brightness", "Screen brightness: darkest, medium or brightest (or a percent 1-100).",
                props("level", "string", "darkest, medium, brightest, or a number 1-100")));
        f.put(fn("split_screen", "Try to show two apps side by side (e.g. Google Maps on the right, YouTube on the left). This car may not allow it: say what the result says.",
                new JSONObject().put("type", "object").put("properties", new JSONObject()
                        .put("left", new JSONObject().put("type", "string").put("description", "app for the left side"))
                        .put("right", new JSONObject().put("type", "string").put("description", "app for the right side")))
                        .put("required", new JSONArray().put("left").put("right"))));
        f.put(fn("engine_sound", "Start or stop the Engine Sound (the petrol engine sound in the middle window).",
                props("on", "boolean", "true = start, false = stop")));
        f.put(fn("ghost_race", "Control Ghost Race (the launcher's map / navigation / race app): full_screen (open it big / enlarge), small_left (minimize, left window), small_right (small, on top of Engine Sound), navigation_mode, map_mode, "
                + "avoid_tolls (เลี่ยงทางด่วน), allow_tolls (ใช้ทางด่วนได้), fastest (เร็วที่สุด), shortest (สั้นที่สุด).",
                props("action", "string", "full_screen (enlarge), small_left (minimize to the left window), small_right (small, on top of Engine Sound), navigation_mode, map_mode, avoid_tolls, allow_tolls, fastest or shortest")));
        f.put(fn("set_cabin_light", "Cabin / reading light: this car has no command for it, so say it can't be done from here.", props("on", "boolean", "true = on, false = off")));
        f.put(fn("sunroof", "Open, close or stop the glass SUNROOF (not the sunshade). Ask the driver to confirm open/close first.",
                props("action", "string", "open, close or stop")));
        f.put(fn("sunshade", "Open, close or stop the SUNSHADE (the cloth under the glass roof). Ask the driver to confirm open/close first.",
                props("action", "string", "open, close or stop")));
        f.put(fn("open_app", "Open an app on the car screen by its name, including the apps inside GBox (e.g. Google Maps, YouTube Music, Spotify, Waze).",
                props("name", "string", "the app's name as the driver said it")));
        JSONObject mp = new JSONObject().put("type", "object").put("properties", new JSONObject()
                .put("action", new JSONObject().put("type", "string").put("description", "play, pause, next, previous, or search (play a song / artist / playlist)"))
                .put("query", new JSONObject().put("type", "string").put("description", "for search: what to play")))
                .put("required", new JSONArray().put("action"));
        JSONObject rp = new JSONObject().put("type", "object").put("properties", new JSONObject()
                .put("kind", new JSONObject().put("type", "string").put("description", "problem (something not working) or idea (a new function the driver wants)"))
                .put("description", new JSONObject().put("type", "string").put("description", "what is wrong or what the driver wants, in their words, clear and complete")))
                .put("required", new JSONArray().put("kind").put("description"));
        f.put(fn("report_problem", "Save a note for the developer (Claude): a problem when something isn't working ('report a problem: the map"
                + " is stuck'), or an idea for a new function ('idea: show the charging time on the dashboard').", rp));
        f.put(fn("navigate", "Start Google Maps navigation to a place: an address, a place name, or 'home' / 'work' (the driver's saved places).",
                props("destination", "string", "where to go, e.g. home, Central World, a full address")));
        f.put(fn("youtube_video", "Search and open a YouTube video. Always call it when asked; the app itself decides if it may open.",
                props("query", "string", "what to search for, e.g. Radiohead Creep live")));
        f.put(fn("navigate_ghost_race", "Open Ghost Race (the driver's own navigation and race app in this launcher) with a destination: use"
                + " it when the driver says Ghost Race (or its favourites), else use navigate (Google Maps). Ghost Race first looks the"
                + " place up in the driver's own favourites list by name, so pass just the place's name as said (e.g. 'Seacon Square Golf').",
                props("destination", "string", "the place name or address")));
        f.put(fn("music", "Control the music: play, pause, next or previous song, or play something by name.", mp));
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
                case "set_range_view": {
                    String v = a.optString("view", "").toLowerCase(Locale.US);
                    int m = v.startsWith("tod") || v.contains("วันนี้") ? 1 : v.startsWith("char") || v.contains("c to c") || v.contains("ชาร์จ") ? 2 : v.startsWith("trip") || v.contains("ทริป") ? 0 : -1;
                    if (m < 0) return res(false, "say trip, today or charge to charge");
                    c.getSharedPreferences("range_graph", Context.MODE_PRIVATE).edit().putInt("mode", m).apply();
                    return res(true, "range graph shows " + (m == 0 ? "this trip" : m == 1 ? "today" : "charge to charge"));
                }
                case "set_trip_graph": {
                    Prefs pr = new Prefs(c);
                    StringBuilder done = new StringBuilder();
                    if (a.has("speed_average_m")) { int v = Math.max(0, a.optInt("speed_average_m", 200)); pr.setSmoothSpeed(v); done.append("speed ").append(v == 0 ? "real time" : v + " m").append(". "); }
                    if (a.has("power_average_m")) { int v = Math.max(0, a.optInt("power_average_m", 200)); pr.setSmoothPower(v); done.append("power ").append(v == 0 ? "real time" : v + " m").append(". "); }
                    if (a.has("zoom")) { String z = a.optString("zoom", "").toLowerCase(Locale.US); boolean w = z.startsWith("ext") || z.contains("whole"); pr.setGraphWhole(w); done.append(w ? "whole trip" : "last 20 km").append(". "); }
                    return done.length() == 0 ? res(false, "nothing to change") : res(true, done.toString().trim());
                }
                case "set_screen_brightness": return brightness(a.optString("level", ""));
                case "split_screen": return split(a.optString("left", ""), a.optString("right", ""));
                case "engine_sound": return voice(a.optBoolean("on", true) ? "engine_on" : "engine_off", a.optBoolean("on", true) ? "Engine Sound started" : "Engine Sound stopped");
                case "ghost_race": {
                    String act = a.optString("action", "").toLowerCase(Locale.US);
                    String cmd = act.contains("left") || act.startsWith("mini") ? "left" : act.contains("right") ? "right"
                            : act.startsWith("full") || act.startsWith("enlarge") || act.startsWith("big") ? "full" : act.startsWith("nav") ? "nav" : act.startsWith("map") ? "map"
                            : act.startsWith("avoid") ? "avoid_tolls" : act.startsWith("allow") ? "allow_tolls" : act.startsWith("fast") ? "fastest" : act.startsWith("short") ? "shortest" : "";
                    if (cmd.isEmpty()) return res(false, "unknown Ghost Race action " + act);
                    return voice("ghost:" + cmd, "Ghost Race: " + act.replace('_', ' ') + (cmd.contains("toll") || cmd.endsWith("est") ? " (applies to the next route search)" : ""));
                }
                case "set_defrost": return defrost(a.optBoolean("on", true));
                case "set_air_direction": return airDirection(a.optString("direction", ""));
                case "set_recirculation": return recirc(a.optBoolean("inside", true));
                case "set_purifier": return purifier(a.optBoolean("on", true));
                case "set_ac_auto": return acAuto(a.optBoolean("on", true));
                case "sunshade": return shade(a.optString("action", ""));
                case "sunroof": return roof(a.optString("action", ""));
                case "report_problem": {
                    String d = a.optString("description", "").trim();
                    if (d.isEmpty()) return res(false, "what's the problem?");
                    boolean idea = a.optString("kind", "problem").toLowerCase(Locale.US).startsWith("idea");
                    ProblemLog.add(c, (idea ? "💡 IDEA: " : "⚠ PROBLEM: ") + d, chatSoFar);
                    return res(true, "saved; the driver can find it in Settings → Problems → Voice reports & ideas and send a photo to Claude");
                }
                case "navigate": return navigate(a.optString("destination", ""));
                case "navigate_ghost_race": {
                    // the same way as "Share → NATEE Launcher" from Google Maps: Ghost Race opens its destination search on it
                    String d = a.optString("destination", "").trim();
                    if (d.isEmpty()) return res(false, "where to?");
                    final android.content.Intent gi = new android.content.Intent(c, LauncherActivity.class)
                            .putExtra(ShareActivity.TEXT, d).putExtra(ShareActivity.TITLE, d)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    main.post(() -> { try { c.startActivity(gi); } catch (Throwable ignored) { } });
                    final String dd = d;
                    new Thread(() -> {   // blocked while another app is in front: through the car's ADB
                        try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
                        if (c.getPackageName().equals(KeyWatch.front)) return;
                        try {
                            Updater.adbShell(c, "am start -n " + c.getPackageName() + "/.LauncherActivity -f 0x30000000 --es "
                                    + ShareActivity.TEXT + " '" + dd.replace("'", "") + "' --es " + ShareActivity.TITLE + " '" + dd.replace("'", "") + "'");
                        } catch (Throwable ignored) { }
                    }).start();
                    return res(true, "Ghost Race is searching for " + d + ": tap the right result to start");
                }
                case "youtube_video": return youtube(a.optString("query", ""));
                case "open_app": return openApp(a.optString("name", ""));
                case "music": return music(a.optString("action", ""), a.optString("query", ""));
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
        // the same numbers the range graph shows on screen (Air 2026-11-25); the car's own display figure kept apart
        double[] g = RangeGraph.Rec.get(c).shown();
        put(o, "range_nedc_km", Double.isNaN(g[0]) ? r[0] : g[0]);
        put(o, "range_wltp_km", Double.isNaN(g[1]) ? r[1] : g[1]);
        put(o, "range_epa_km", Double.isNaN(g[2]) ? (Double.isNaN(r[1]) ? Double.NaN : r[1] * RangeGraph.EPA_OF_WLTP) : g[2]);
        put(o, "my_range_km_from_own_driving", Double.isNaN(g[3]) ? r[2] : g[3]);
        put(o, "range_km_on_the_cars_own_dashboard", r[0]);
        put(o, "battery_left_kwh", s.battLeftKwh);
        put(o, "battery_health_percent", TripEnergy.get(c).health());   // calibrated daily from your driving
        put(o, "speed_kmh", s.speedKmh);
        double la = s.lat, ln = s.lng;   // where the car is: for "weather here", "near me"
        if (Double.isNaN(la)) { android.location.Location l = Places.last(c); if (l != null) { la = l.getLatitude(); ln = l.getLongitude(); } }
        put(o, "lat", la); put(o, "lng", ln);
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
        JSONObject o = answer(v, "fan " + level);
        // (Air 2026-11-23: "it said done but no") read the car's real setting back and report that
        try { Thread.sleep(900); } catch (InterruptedException ignored) { }
        Object now = call(AC, "getAcWindLevel");
        if (now instanceof Number) {
            int n = ((Number) now).intValue();
            try { o.put("ok", n == level).put("result", n == level ? "fan is now " + n : "the car's fan is still " + n + " (it didn't change)"); } catch (Exception ignored) { }
        }
        return o;
    }

    private JSONObject temp(double celsius) {
        if (Double.isNaN(celsius) || celsius < 17 || celsius > 33) return res(false, "temperature must be 17 to 33 °C");
        Object u = call(AC, "getTemperatureUnit");
        int unit = u instanceof Number ? ((Number) u).intValue() : 1;
        int v = (int) Math.round(celsius);
        Object r = call(AC, "setAcTemperature", 0, v, 0, unit);   // confirmed on this car (probe: temperature +1)
        JSONObject o = answer(r, "temperature " + v + " °C");
        // read the car's real setting back and report that
        try { Thread.sleep(900); } catch (InterruptedException ignored) { }
        Object now = call(AC, "getTemprature", 1);
        if (!(now instanceof Number)) now = call(AC, "getTemprature", 0);
        if (now instanceof Number) {
            int n = ((Number) now).intValue();
            if (n >= 10 && n <= 40) try { o.put("ok", n == v).put("result", n == v ? "temperature is now " + n + " °C" : "the car's temperature is still " + n + " °C (it didn't change)"); } catch (Exception ignored) { }
        }
        return o;
    }

    // ---- more A/C (Air 2026-11-24: "add them"). Comfort only. Defrost and recirculation use the car's feature write,
    // as the ADAS Probe tests did (accepted on this car); purifier and air direction the calls the probe confirmed.
    private JSONObject defrost(boolean on) {
        Object r = acFeature(new String[]{"AC_DEFROST_FRONT_STATE_SET", "DEFROST_FRONT_SET"}, 501219362, on ? 1 : 0);
        return answer(r, on ? "front defrost on" : "front defrost off");
    }

    private JSONObject recirc(boolean inside) {
        Object r = acFeature(new String[]{"AC_CYCLE_MODE_SET", "CYCLE_MODE_SET"}, 501219355, inside ? 1 : 0);
        JSONObject o = answer(r, inside ? "inside air (recirculation)" : "outside air");
        try { Thread.sleep(900); } catch (InterruptedException ignored) { }
        Object now = call(AC, "getAcCycleMode");   // probe: 1 = inside air, 0 = outside air
        if (now instanceof Number) {
            boolean in = ((Number) now).intValue() == 1;
            try { o.put("ok", in == inside).put("result", in == inside ? (in ? "now inside air" : "now outside air") : "the car didn't change it"); } catch (Exception ignored) { }
        }
        return o;
    }

    private JSONObject purifier(boolean on) {
        Object r = call(AC, "setAutoCleanAirState", on ? 1 : 2);   // probe: 1 = on, 2 = off
        return answer(r, on ? "air purifier on" : "air purifier off");
    }

    /** Which setAcWindMode number is which direction: learned in Settings → AI chat → Air direction test. */
    static final String[] DIRECTIONS = {"face", "face_feet", "feet", "feet_windscreen", "windscreen"};
    private JSONObject airDirection(String dir) {
        dir = dir.trim().toLowerCase(Locale.US).replace(' ', '_').replace('+', '_');
        int mode = c.getSharedPreferences("air_dir", Context.MODE_PRIVATE).getInt(dir, -1);
        if (mode < 0) return res(false, "the launcher doesn't know this car's number for '" + dir + "' yet: the driver can teach it in Settings → AI chat → Air direction test");
        Object r = call(AC, "setAcWindMode", 0, mode);
        JSONObject o = answer(r, "air to " + dir.replace('_', ' '));
        try { Thread.sleep(900); } catch (InterruptedException ignored) { }
        Object now = call(AC, "getAcWindMode");
        if (now instanceof Number) {
            int n = ((Number) now).intValue();
            try { o.put("ok", n == mode).put("result", n == mode ? "air direction changed" : "the car didn't change the air direction"); } catch (Exception ignored) { }
        }
        return o;
    }

    private JSONObject acAuto(boolean on) {
        // not found on this car yet: the likely calls, one or two numbers, first one that exists wins
        String[] names = {"setAcAutoState", "setAcAutoMode", "setAutoMode", "setAcAuto", "setAcAutoSwitch"};
        for (String n : names) {
            Object r = call(AC, n, 0, on ? 1 : 0);
            if (r == MISSING) r = call(AC, n, on ? 1 : 0);
            if (r != MISSING) { JSONObject a = answer(r, on ? "A/C auto on" : "A/C auto off"); try { a.put("call", n); } catch (Exception ignored) { } return a; }
        }
        Object r = acFeature(new String[]{"AC_AUTO_MODE_SET", "AUTO_MODE_SET", "AC_AUTO_SET"}, -1, on ? 1 : 0);
        if (r != MISSING) return answer(r, on ? "A/C auto on" : "A/C auto off");
        return res(false, "this car has no A/C auto command the launcher can use yet");
    }

    /** The car's generic A/C feature write, set(int[]{id}, value) — only Ac.* feature ids. */
    Object acFeature(String[] fields, int fallbackId, int value) {
        Object d = CarDev.get(c, AC);
        if (d == null) return MISSING;
        int id = fallbackId;
        try {
            Class<?> ids = Class.forName(P + "BYDAutoFeatureIds");
            outer:
            for (Class<?> inner : ids.getDeclaredClasses()) {
                if (!inner.getSimpleName().equals("Ac")) continue;
                for (String f : fields) { try { id = inner.getField(f).getInt(null); break outer; } catch (NoSuchFieldException ignored) { } }
            }
        } catch (Throwable ignored) { }
        if (id < 0) return MISSING;
        try {
            Class<?> evc = Class.forName(P + "BYDAutoEventValue");
            Object ev = evc.getConstructor().newInstance();
            evc.getField("intValue").setInt(ev, value);
            Method set = d.getClass().getMethod("set", int[].class, evc);
            Object r = set.invoke(d, new int[]{id}, ev);
            return r == null ? 0 : r;
        } catch (NoSuchMethodException e) { return MISSING; } catch (Throwable e) { return FAILED; }
    }

    /** For the air direction test in Settings. */
    Object setWindMode(int m) { return call(AC, "setAcWindMode", 0, m); }
    Object windMode() { return call(AC, "getAcWindMode"); }

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
        return res(false, "this car has no reading/cabin light command the launcher can use (checked 2026-11-22)");
    }

    /** The glass sunroof (Air 2026-11-22: the older version's command opened it): voiceCtlMoonRoof 1 open, 2 close, 4 stop. */
    private JSONObject roof(String action) {
        String a = action.toLowerCase(Locale.US);
        int code = a.startsWith("open") ? 1 : a.startsWith("close") ? 2 : a.startsWith("stop") ? 4 : -1;
        if (code < 0) return res(false, "say open, close or stop");
        if (code == 4) call(BODY, "setMoonRoofAndSunshadeStop");
        Object r = call(BODY, "voiceCtlMoonRoof", code);
        return answer(r, "sunroof " + (code == 1 ? "opening" : code == 2 ? "closing" : "stopped"));
    }

    private JSONObject shade(String action) {
        String a = action.toLowerCase(Locale.US);
        // (Air 2026-11-22) the sunshade's OWN commands, found with Settings → Find car controls: voiceCtlSunshadePanel
        // (voice codes, like the roof's: 1 open, 2 close, 4 stop). voiceCtlMoonRoof moved the GLASS roof: never used.
        int code = a.startsWith("open") ? 1 : a.startsWith("close") ? 2 : a.startsWith("stop") ? 4 : -1;
        if (code < 0) return res(false, "say open, close or stop");
        Object before = call(BODY, "getSunroofWindowblindPosition");
        Object r = call(BODY, "voiceCtlSunshadePanel", code);
        if (code == 4 && r == MISSING) r = call(BODY, "setMoonRoofAndSunshadeStop");
        JSONObject o = answer(r, "sunshade " + (code == 1 ? "opening" : code == 2 ? "closing" : "stopped"));
        try {
            if (before instanceof Number) o.put("position_before", ((Number) before).intValue());
            o.put("note", "first test of this command: if anything other than the sunshade moves, the driver says stop and tells Claude");
        } catch (Exception ignored) { }
        return o;
    }

    // ------------------------------------------------------------ apps and music (user, 2026-11-21)
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    private JSONObject openApp(String name) throws Exception {
        String want = norm(name).replace("ingbox", "").replace("gbox", "");
        if (want.isEmpty() && norm(name).contains("gbox")) {   // "open GBox" (Air 2026-11-22)
            if (!Apps.installed(c, "com.gbox.android")) return res(false, "GBox isn't installed");
            android.content.Intent gl = c.getPackageManager().getLaunchIntentForPackage("com.gbox.android");
            if (gl != null) launch(gl, "com.gbox.android");
            return res(true, "opening GBox");
        }
        if (want.isEmpty()) return res(false, "which app?");
        // (Air 2026-11-23: "open Google Maps" opened the Google app) the word Google is ignored on both sides, so
        // "Google Maps" = Maps; only "Google" on its own means the Google app
        final String wantG = want.startsWith("google") && want.length() > 6 ? want.substring(6) : want;
        // (Air 2026-11-23: "open YouTube Music" opened YouTube) YouTube Music always means the music app itself
        if (want.contains("music") && (want.contains("youtube") || want.contains("yt"))) {
            for (String y : MusicCard.YTM) if (Apps.installed(c, y)) {
                android.content.Intent li = c.getPackageManager().getLaunchIntentForPackage(y);
                if (li != null) launch(li, y);
                return res(true, "opening YouTube Music");
            }
        }
        Apps.App best = findApp(name);
        if (best == null) return res(false, "no app called " + name + " on this car");
        final Apps.App app = best;
        if (!(Apps.VIDEO.contains(app.pkg) && !CarData.get(c).last().parked() && !new Prefs(c).videoWhileDriving())) {
            android.content.Intent li = c.getPackageManager().getLaunchIntentForPackage(app.pkg);
            if (li != null) launch(li, app.pkg);
        }
        boolean video = Apps.VIDEO.contains(app.pkg) && !CarData.get(c).last().parked() && !new Prefs(c).videoWhileDriving();
        return res(!video, video ? app.label + " is a video app: it opens only in P" : "opening " + app.label);
    }

    /** A command for the launcher's middle window, carried out by the launcher (it comes to the front). */
    private JSONObject voice(String cmd, String said) {
        final android.content.Intent li = new android.content.Intent(c, LauncherActivity.class).putExtra(LauncherActivity.VOICE, cmd)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
        main.post(() -> { try { c.startActivity(li); } catch (Throwable ignored) { } });
        new Thread(() -> {   // blocked while another app is in front: through the car's ADB
            try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
            if (c.getPackageName().equals(KeyWatch.front)) return;
            try { Updater.adbShell(c, "am start -n " + c.getPackageName() + "/.LauncherActivity -f 0x30000000 --es " + LauncherActivity.VOICE + " '" + cmd + "'"); } catch (Throwable ignored) { }
        }).start();
        return res(true, said);
    }

    /** The installed app the driver means by this name (same rules as open_app). */
    private Apps.App findApp(String name) {
        String want = norm(name).replace("ingbox", "").replace("gbox", "");
        if (want.isEmpty()) return null;
        final String wantG = want.startsWith("google") && want.length() > 6 ? want.substring(6) : want;
        if (want.contains("music") && (want.contains("youtube") || want.contains("yt")))
            for (Apps.App a : Apps.all(c)) for (String y : MusicCard.YTM) if (a.pkg.equals(y)) return a;
        // (Air 2026-11-22) maps apps: the car's own Google Maps (outside GBox) wins; other apps: the GBox copy wins
        boolean mapsApp = want.contains("map") || want.contains("waze") || want.contains("นำทาง");
        Apps.App best = null; int bestScore = 0;
        for (Apps.App a : Apps.all(c)) {
            if (a.pkg.equals("com.gbox.android") && !want.isEmpty() && !norm(name).contains("gbox")) continue;   // GBox itself only when asked for
            String l = norm(a.label).replace("gbox", "");
            if (!wantG.equals(want) && l.startsWith("google") && l.length() > 6) l = l.substring(6);
            if (l.length() < 2) continue;
            if (!wantG.equals(want) && l.equals("google")) continue;   // asked for "Google X": never the Google app itself
            String w = wantG;
            int sc = l.equals(w) ? 3 : l.startsWith(w) || w.startsWith(l) ? 2 : l.contains(w) || w.contains(l) ? 1 : 0;
            if (sc == 0) continue;
            boolean gbox = a.pkg.startsWith("com.gbox.");
            sc = sc * 2 + (mapsApp ? (gbox ? 0 : 1) : (gbox ? 1 : 0));
            if (sc > bestScore) { bestScore = sc; best = a; }
        }
        return best;
    }

    /** Darkest / medium / brightest (Air 2026-11-26): Android's screen brightness, through the car's ADB. */
    private JSONObject brightness(String level) {
        String l = level.toLowerCase(Locale.US);
        int v;
        if (l.contains("dark") || l.contains("มืด") || l.contains("min") || l.contains("low")) v = 10;
        else if (l.contains("bright") || l.contains("สว่างสุด") || l.contains("max") || l.contains("high")) v = 255;
        else if (l.contains("med") || l.contains("กลาง") || l.contains("mid")) v = 128;
        else { try { v = Math.round(Math.max(1, Math.min(100, Float.parseFloat(l.replaceAll("[^0-9.]", "")))) * 2.55f); } catch (Exception e) { return res(false, "say darkest, medium or brightest"); } }
        try {
            String out = Updater.adbShell(c, "settings put system screen_brightness_mode 0; settings put system screen_brightness " + v + "; settings get system screen_brightness");
            boolean ok = out.trim().startsWith(String.valueOf(v));
            return res(ok, ok ? "brightness set (" + Math.round(v / 2.55) + " %)" : "the car answered: " + out);
        } catch (Exception e) { return res(false, "couldn't reach the car's ADB to change the brightness"); }
    }

    /** Two apps side by side (Air 2026-11-26). This car opened other apps full screen in the probe tests; Android's
     *  split-screen is tried through the car's ADB, and the result says what happened. Video apps follow the P rule. */
    private JSONObject split(String leftName, String rightName) {
        Apps.App l = findApp(leftName), r = findApp(rightName);
        if (l == null || r == null) return res(false, "no app called " + (l == null ? leftName : rightName) + " on this car");
        boolean moving = !CarData.get(c).last().parked() && !new Prefs(c).videoWhileDriving();
        if (moving && (Apps.VIDEO.contains(l.pkg) || Apps.VIDEO.contains(r.pkg))) return res(false, "a video app opens only in P");
        android.content.ComponentName lc = comp(l.pkg), rc = comp(r.pkg);
        if (lc == null || rc == null) return res(false, "couldn't find how to open those apps");
        try {
            // Android 10: split-screen primary (3) goes left in landscape, the next app fills the other side (4)
            String out = Updater.adbShell(c, "am start --windowingMode 3 -n " + lc.flattenToShortString() + " 2>&1; sleep 1; "
                    + "am start --windowingMode 4 -n " + rc.flattenToShortString() + " 2>&1; sleep 1; dumpsys activity activities | grep -c -i 'mode=split'");
            String[] ls = out.trim().split("\n");
            String last = ls[ls.length - 1].trim();
            boolean ok = last.matches("[0-9]+") && !last.equals("0");
            return res(ok, ok ? "split screen: " + l.label + " left, " + r.label + " right"
                    : "this car doesn't allow split screen: both apps opened full screen (" + r.label + " in front)");
        } catch (Exception e) { return res(false, "couldn't reach the car's ADB"); }
    }

    private android.content.ComponentName comp(String pkg) {
        android.content.Intent li = c.getPackageManager().getLaunchIntentForPackage(pkg);
        return li == null ? null : li.getComponent();
    }

    /** Google Maps (outside GBox first) turn-by-turn to the place; "home" / "work" are Maps' own saved places. */
    private JSONObject navigate(String dest) throws Exception {
        String d = dest.trim();
        if (d.isEmpty()) return res(false, "where to?");
        String q = d.equalsIgnoreCase("home") || d.equals("บ้าน") ? "Home" : d.equalsIgnoreCase("work") || d.equals("ที่ทำงาน") ? "Work" : d;
        // (Air 2026-11-22: a second "navigate to…" opened another Maps window and got stuck) always the same Maps app as
        // last time, and the new place goes to its open window (it reroutes there) instead of a new one
        android.content.SharedPreferences st = c.getSharedPreferences("natee_chat", Context.MODE_PRIVATE);
        String used = st.getString("mapsPkg", "");
        String[] pkgs = used.isEmpty() ? new String[]{"com.google.android.apps.maps", "com.gbox.com.google.android.apps.maps"}
                : new String[]{used, "com.google.android.apps.maps", "com.gbox.com.google.android.apps.maps"};
        for (String p : pkgs) {
            if (!Apps.installed(c, p)) continue;
            // (Air 2026-11-23: a second "navigate to…" did nothing) Google's directions link with navigation: Maps takes it
            // even with a route already on; CLEAR_TOP restarts its screen on the new place instead of ignoring it
            String url = "https://www.google.com/maps/dir/?api=1&travelmode=driving&dir_action=navigate&destination=" + android.net.Uri.encode(q);
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).setPackage(p)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (c.getPackageManager().resolveActivity(i, 0) == null)   // this copy doesn't take the link: the old way
                i = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("google.navigation:q=" + android.net.Uri.encode(q)))
                        .setPackage(p).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (c.getPackageManager().resolveActivity(i, 0) == null) continue;
            st.edit().putString("mapsPkg", p).apply();
            launch(i, p);
            return res(true, "asked " + Apps.label(c, p) + " to navigate to " + q + " (it may ask to confirm the place or start)"
                    + (q.equals("Home") || q.equals("Work") ? "; uses the Home/Work saved in Google Maps" : ""));
        }
        return res(false, "Google Maps isn't installed");
    }

    /** YouTube search, opened full screen: only in P (the project's rule: video only when parked). */
    private JSONObject youtube(String query) throws Exception {
        if (query.trim().isEmpty()) return res(false, "which video?");
        // while driving only with Settings → Song video "While driving too" (for the passenger; Air 2026-11-22)
        if (!CarData.get(c).last().parked() && !new Prefs(c).videoWhileDriving())
            return res(false, "videos open only in P (Settings → Song video → While driving too allows it for the passenger)");
        android.net.Uri u = android.net.Uri.parse("https://www.youtube.com/results?search_query=" + android.net.Uri.encode(query));
        for (String p : new String[]{"com.gbox.com.google.android.youtube", "com.google.android.youtube"}) {
            if (!Apps.installed(c, p)) continue;
            final android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW, u).setPackage(p)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            if (c.getPackageManager().resolveActivity(i, 0) == null) {
                // this copy doesn't take links: open its search instead
                final android.content.Intent s2 = new android.content.Intent(android.content.Intent.ACTION_SEARCH).setPackage(p)
                        .putExtra(android.app.SearchManager.QUERY, query).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                if (c.getPackageManager().resolveActivity(s2, 0) == null) continue;
                launch(s2, p);
            } else launch(i, p);
            return res(true, "opening YouTube: " + query);
        }
        return res(false, "YouTube isn't installed");
    }

    private static String norm(String s) { return s == null ? "" : s.toLowerCase(Locale.US).replaceAll("[^a-z0-9ก-๙]", ""); }

    private JSONObject music(String action, String query) throws Exception {
        String a = action.toLowerCase(Locale.US);
        // "play Radiohead" (Air 2026-11-22): inside GBox, YouTube Music's player is registered under GBox's own name, so
        // ask whichever music player is there (not the car's own Media). None yet: open YouTube Music, then ask it.
        if (!query.trim().isEmpty() && !a.equals("pause") && !a.equals("next") && !a.equals("previous")) {   // "play" + a name = search too
            android.media.session.MediaController p = searchablePlayer();
            if (p == null) {   // nothing playing yet: open YouTube Music first and give it a few seconds
                for (String y : MusicCard.YTM) {
                    if (!Apps.installed(c, y)) continue;
                    android.content.Intent li = c.getPackageManager().getLaunchIntentForPackage(y);
                    if (li != null) launch(li, y);
                    for (int i = 0; i < 10 && p == null; i++) { try { Thread.sleep(600); } catch (InterruptedException ignored) { } p = searchablePlayer(); }
                    break;
                }
            }
            if (p != null) {
                // (Air 2026-11-23: "it said it plays the song, but it doesn't") ask, then check a different song really started
                String before = songOf(p);
                p.getTransportControls().playFromSearch(query, new android.os.Bundle());
                for (int i = 0; i < 10; i++) {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) { }
                    String now = songOf(p);
                    android.media.session.PlaybackState ps = p.getPlaybackState();
                    boolean playing = ps != null && ps.getState() == android.media.session.PlaybackState.STATE_PLAYING;
                    if (playing && !now.isEmpty() && !now.equals(before)) return res(true, "now playing: " + now);
                }
            }
            // it ignored the request: open its search page for it, one tap to play
            for (String y : MusicCard.YTM) {
                if (!Apps.installed(c, y)) continue;
                final android.content.Intent v = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://music.youtube.com/search?q=" + android.net.Uri.encode(query)))
                        .setPackage(y).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                if (c.getPackageManager().resolveActivity(v, 0) != null) launch(v, y);
                else { android.content.Intent li = c.getPackageManager().getLaunchIntentForPackage(y); if (li != null) launch(li, y); }
                return res(false, "YouTube Music didn't start it by itself; its search for \"" + query
                        + "\" is open (or YouTube Music itself): tap the song to play. Tell the driver exactly this.");
            }
            return res(false, "YouTube Music isn't installed");
        }
        android.media.session.MediaController mc = musicSession();
        if (mc == null) {
            for (String y : MusicCard.YTM) if (Apps.installed(c, y)) {
                android.content.Intent li = c.getPackageManager().getLaunchIntentForPackage(y);
                if (li != null) launch(li, y);
                return res(false, "no music was playing: opened YouTube Music, ask again in a few seconds");
            }
            return res(false, "no music app is playing (and Notification access may be off)");
        }
        android.media.session.MediaController.TransportControls t = mc.getTransportControls();
        switch (a) {
            case "play": t.play(); return res(true, "playing");
            case "pause": case "stop": t.pause(); return res(true, "paused");
            case "next": t.skipToNext(); return res(true, "next song");
            case "previous": case "back": t.skipToPrevious(); return res(true, "previous song");
            default:
                if (query.isEmpty()) return res(false, "what should I play?");
                boolean ytm = false;
                for (String y : MusicCard.YTM) ytm |= y.equals(mc.getPackageName());
                ytm |= mc.getPackageName().contains("gbox");
                if (!ytm) return res(false, "YouTube Music couldn't be asked to play it; open YouTube Music and ask again");
                t.playFromSearch(query, new android.os.Bundle());
                return res(true, "asked " + Apps.label(c, mc.getPackageName()) + " to play " + query + " (some apps don't support this)");
        }
    }

    /**
     * Opens an app / link, also while another app is in front (Air 2026-11-23: it only worked from the home screen —
     * Android blocks apps in the background from opening others). The normal way first; if the app isn't in front 2 s
     * later, through the car's own ADB, which Android always allows.
     */
    private void launch(android.content.Intent i, String pkg) {
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        final long t0 = android.os.SystemClock.uptimeMillis();
        // (Air 2026-11-23: no navigation while on the Maps page) already in front = Android may silently drop it and we
        // couldn't tell: go straight through the car's ADB then
        final boolean alreadyFront = pkg.equals(KeyWatch.front);
        if (!alreadyFront) main.post(() -> { try { c.startActivity(i); } catch (Throwable ignored) { } });
        new Thread(() -> {
            if (!alreadyFront) for (int k = 0; k < 8; k++) {
                try { Thread.sleep(250); } catch (InterruptedException ignored) { }
                if (pkg.equals(KeyWatch.front) && KeyWatch.frontAt > t0) return;   // it really came up after the request
            }
            try {
                String cmd;
                if (i.getData() != null)
                    cmd = "am start -a " + i.getAction() + " -d '" + i.getData().toString().replace("'", "") + "' -p " + pkg + " -f 0x14000000";
                else {
                    android.content.Intent li = c.getPackageManager().getLaunchIntentForPackage(pkg);
                    cmd = li != null && li.getComponent() != null ? "am start -n " + li.getComponent().flattenToShortString() + " -f 0x10200000"
                            : "monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1";
                }
                Updater.adbShell(c, cmd);
            } catch (Throwable ignored) { }
        }).start();
    }

    private static String songOf(android.media.session.MediaController p) {
        try {
            android.media.MediaMetadata m = p.getMetadata();
            if (m == null) return "";
            String t = m.getString(android.media.MediaMetadata.METADATA_KEY_TITLE), ar = m.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST);
            return (t == null ? "" : t) + (ar == null ? "" : " · " + ar);
        } catch (Throwable e) { return ""; }
    }

    /** A music player to ask "play …": YouTube Music or anything inside GBox first, never the car's own Media / radio. */
    private android.media.session.MediaController searchablePlayer() {
        try {
            android.media.session.MediaSessionManager m = (android.media.session.MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            android.media.session.MediaController other = null;
            for (android.media.session.MediaController mc : m.getActiveSessions(NowPlaying.component(c))) {
                String p = mc.getPackageName();
                if (p.contains("gbox") || p.contains("youtube.music") || p.contains("spotify")) return mc;
                if (!p.startsWith("com.byd") && other == null) other = mc;
            }
            return other;
        } catch (Throwable e) { return null; }
    }

    private android.media.session.MediaController musicSession() {
        try {
            android.media.session.MediaSessionManager m = (android.media.session.MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            for (android.media.session.MediaController mc : m.getActiveSessions(NowPlaying.component(c)))
                for (String y : MusicCard.YTM) if (y.equals(mc.getPackageName())) return mc;
            return NowPlaying.active(c);
        } catch (Throwable e) { return null; }
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

    private static void put(JSONObject o, String k, double v) throws Exception {
        if (!Double.isNaN(v)) o.put(k, k.equals("lat") || k.equals("lng") ? round(v, 4) : round(v, 1));
    }
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
