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
                + " it when the driver says Ghost Race, else use navigate (Google Maps).",
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
        put(o, "range_nedc_km_car_display", r[0]);
        put(o, "range_wltp_km", r[1]);
        if (!Double.isNaN(r[1])) put(o, "range_epa_estimate_km", r[1] * RangeGraph.EPA_OF_WLTP);
        put(o, "my_range_km_from_own_driving", r[2]);
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
            main.post(() -> Apps.open(c, "com.gbox.android", CarData.get(c).last()));
            return res(true, "opening GBox");
        }
        if (want.isEmpty()) return res(false, "which app?");
        // (Air 2026-11-23: "open Google Maps" opened the Google app) the word Google is ignored on both sides, so
        // "Google Maps" = Maps; only "Google" on its own means the Google app
        final String wantG = want.startsWith("google") && want.length() > 6 ? want.substring(6) : want;
        // (Air 2026-11-23: "open YouTube Music" opened YouTube) YouTube Music always means the music app itself
        if (want.contains("music") && (want.contains("youtube") || want.contains("yt"))) {
            for (String y : MusicCard.YTM) if (Apps.installed(c, y)) {
                main.post(() -> Apps.open(c, y, CarData.get(c).last()));
                return res(true, "opening YouTube Music");
            }
        }
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
        if (best == null) return res(false, "no app called " + name + " on this car");
        final Apps.App app = best;
        main.post(() -> Apps.open(c, app.pkg, CarData.get(c).last()));   // video apps still only in P
        boolean video = Apps.VIDEO.contains(app.pkg) && !CarData.get(c).last().parked() && !new Prefs(c).videoWhileDriving();
        return res(!video, video ? app.label + " is a video app: it opens only in P" : "opening " + app.label);
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
            final android.content.Intent fi = i;
            main.post(() -> { try { c.startActivity(fi); } catch (Throwable ignored) { } });
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
                main.post(() -> { try { c.startActivity(s2); } catch (Throwable ignored) { } });
            } else main.post(() -> { try { c.startActivity(i); } catch (Throwable ignored) { } });
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
                    main.post(() -> Apps.open(c, y, CarData.get(c).last()));
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
                final String yy = y;
                main.post(() -> { try { c.startActivity(v); } catch (Throwable t) { Apps.open(c, yy, CarData.get(c).last()); } });
                return res(false, "YouTube Music didn't start it by itself; its search for \"" + query
                        + "\" is open (or YouTube Music itself): tap the song to play. Tell the driver exactly this.");
            }
            return res(false, "YouTube Music isn't installed");
        }
        android.media.session.MediaController mc = musicSession();
        if (mc == null) {
            for (String y : MusicCard.YTM) if (Apps.installed(c, y)) {
                main.post(() -> Apps.open(c, y, CarData.get(c).last()));
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
