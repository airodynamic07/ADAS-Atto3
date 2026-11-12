package com.airodynamic.adas;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Saved settings. Rule 3: never reset on update — every value has a default that is
 * only used when nothing was saved yet; nothing here ever clears what the user set.
 */
public final class Prefs {
    /** Built-in panels a window can show. Apps are stored as their package name. */
    public static final String ADAS = "@adas", ENGINE = "@engine", INFO = "@info";
    public static final String[] DEFAULT_PANEL = {ADAS, ENGINE, INFO};
    public static final String ENGINE_PKG = "com.air.atto3sound";

    private final SharedPreferences p;

    public Prefs(Context c) { p = c.getSharedPreferences("adas_launcher", Context.MODE_PRIVATE); }

    public Theme theme() {
        try { return Theme.valueOf(p.getString("theme", Theme.BLUE.name())); } catch (Exception e) { return Theme.BLUE; }
    }
    public void setTheme(Theme t) { p.edit().putString("theme", t.name()).apply(); }

    public boolean autoSport() { return p.getBoolean("autoSport", true); }
    public void setAutoSport(boolean on) { p.edit().putBoolean("autoSport", on).apply(); }
    public int autoSportKmh() { return p.getInt("autoSportKmh", 120); }
    public void setAutoSportKmh(int v) { p.edit().putInt("autoSportKmh", v).apply(); }

    public boolean turnCamera() { return p.getBoolean("turnCam", true); }
    public void setTurnCamera(boolean on) { p.edit().putBoolean("turnCam", on).apply(); }
    public int turnCameraReturnSec() { return p.getInt("turnCamReturn", 3); }
    public void setTurnCameraReturnSec(int s) { p.edit().putInt("turnCamReturn", s).apply(); }

    public int speedLimit() { return p.getInt("speedLimit", 100); }
    public void setSpeedLimit(int v) { p.edit().putInt("speedLimit", v).apply(); }
    public boolean speedVoice() { return p.getBoolean("speedVoice", true); }
    public void setSpeedVoice(boolean on) { p.edit().putBoolean("speedVoice", on).apply(); }

    /** Graph moving average in metres for the speed / power line: 0 = real time, 100…500; default 200 (user, 2026-11-12). */
    public int smoothSpeed() { return p.getInt("smoothSpeed", 200); }
    public void setSmoothSpeed(int m) { p.edit().putInt("smoothSpeed", m).apply(); }
    public int smoothPower() { return p.getInt("smoothPower", 200); }
    public void setSmoothPower(int m) { p.edit().putInt("smoothPower", m).apply(); }

    /** Ghost Race size in its window, percent of normal (60–150, default 100) (user, 2026-11-12). */
    public int ghostZoom() { return p.getInt("ghostZoom", 100); }
    public void setGhostZoom(int z) { p.edit().putInt("ghostZoom", Math.max(60, Math.min(150, z))).apply(); }

    /** Side camera close-up height: 0 low, 1 middle, 2 high (default, 2026-11-12). */
    public int sideCamUp() { return p.getInt("sideCamUp", 2); }
    public void setSideCamUp(int u) { p.edit().putInt("sideCamUp", u).apply(); }

    /** The DRL reading found in Settings ("device|method|arg") and its ON value. */
    public static String drlKey(android.content.Context c) { return c.getSharedPreferences("adas_launcher", 0).getString("drlKey", ""); }
    public static int drlOn(android.content.Context c) { return c.getSharedPreferences("adas_launcher", 0).getInt("drlOn", 1); }
    public static void setDrl(android.content.Context c, String key, int on) { c.getSharedPreferences("adas_launcher", 0).edit().putString("drlKey", key).putInt("drlOn", on).apply(); }

    /** Tyre pressure unit: "psi" (default), "bar" or "kpa". */
    public String tyreUnit() { return p.getString("tyreUnit", "psi"); }
    public void setTyreUnit(String u) { p.edit().putString("tyreUnit", u).apply(); }

    /** Next service odometer reading in km; 0 = not set yet. */
    public int serviceAtKm() { return p.getInt("serviceAtKm", 0); }
    public void setServiceAtKm(int km) { p.edit().putInt("serviceAtKm", km).apply(); }

    /** Where the car was last put into P. */
    public double parkedLat() { return Double.longBitsToDouble(p.getLong("parkLat", Double.doubleToLongBits(Double.NaN))); }
    public double parkedLng() { return Double.longBitsToDouble(p.getLong("parkLng", Double.doubleToLongBits(Double.NaN))); }
    public long parkedAt() { return p.getLong("parkAt", 0); }
    public void setParked(double lat, double lng, long at) {
        p.edit().putLong("parkLat", Double.doubleToLongBits(lat)).putLong("parkLng", Double.doubleToLongBits(lng)).putLong("parkAt", at).apply();
    }

    /** Window order after swapping: which default panel sits in window i. */
    public String defaultPanel(int win) { return p.getString("win" + win + ".default", DEFAULT_PANEL[win]); }
    public void setDefaultPanel(int win, String v) { p.edit().putString("win" + win + ".default", v).apply(); }

    /** The 3 quick-app buttons of a window. */
    public String slot(int win, int i) {
        String def;
        switch (win) {
            case 0: def = new String[]{ADAS, "com.gbox.com.google.android.apps.maps", "com.waze"}[i]; break;
            case 1: def = new String[]{ENGINE, "com.gbox.com.google.android.youtube", "com.gbox.com.spotify.music"}[i]; break;
            default: def = new String[]{INFO, "com.byd.bluetoothcall", "com.byd.mediacenter"}[i]; break;   // label shown as "Media"
        }
        return p.getString("win" + win + ".slot" + i, def);
    }
    public void setSlot(int win, int i, String v) { p.edit().putString("win" + win + ".slot" + i, v).apply(); }

    // Ambient light by gear (off until switched on). Colour numbers are the car's own (unknown palette: try them).
    public static final String[] GEARS = {"P", "R", "N", "D"};
    private static final int[] GEAR_COLOUR = {10, 1, 20, 25};
    public boolean ambientGear() { return p.getBoolean("ambGear", false); }
    public void setAmbientGear(boolean on) { p.edit().putBoolean("ambGear", on).apply(); }
    public int ambientColour(int g) { return p.getInt("ambCol" + GEARS[g], GEAR_COLOUR[g]); }
    public void setAmbientColour(int g, int v) { p.edit().putInt("ambCol" + GEARS[g], v).apply(); }
    /** 0 Solid, 1 Breathe, 2 Flash */
    public int ambientStyle() { return p.getInt("ambStyle", 0); }
    public void setAmbientStyle(int v) { p.edit().putInt("ambStyle", v).apply(); }
    /** Left/Right cameras: close-up of the side looking back (like Kinex) instead of the whole fisheye. */
    public boolean sideCloseUp() { return p.getBoolean("sideCloseUp", true); }
    public void setSideCloseUp(boolean on) { p.edit().putBoolean("sideCloseUp", on).apply(); }

    /**
     * App shortcuts in the ChatGPT box (user adds with +, removes by press-and-hold).
     * Starts from the apps the user had put on the old window bars, so nothing is lost.
     */
    public java.util.List<String> shortcuts(Context c) {
        String v = p.getString("shortcuts", null);
        java.util.List<String> out = new java.util.ArrayList<>();
        if (v != null) {
            for (String s : v.split(",")) if (!s.isEmpty()) out.add(s);
            return out;
        }
        for (int w = 0; w < 3; w++) for (int i = 0; i < 3; i++) {
            String s = slot(w, i);
            if (!s.startsWith("@") && !out.contains(s) && Apps.installed(c, s)) out.add(s);
        }
        return out;
    }
    public void setShortcuts(java.util.List<String> list) {
        StringBuilder b = new StringBuilder();
        for (String s : list) { if (b.length() > 0) b.append(','); b.append(s); }
        p.edit().putString("shortcuts", b.toString()).apply();
    }

    /** Ambient light above the red-screen (Auto Sport) speed: on/off (off until switched on), colour. Blinks. */
    public boolean ambientOver() { return p.getBoolean("ambOver", false); }
    public void setAmbientOver(boolean on) { p.edit().putBoolean("ambOver", on).apply(); }
    public int ambientOverColour() { return p.getInt("ambOverCol", 1); }
    public void setAmbientOverColour(int v) { p.edit().putInt("ambOverCol", v).apply(); }

    /** Ambient light while a door is open: on/off (off until switched on), colour. Blinks like over-speed. */
    public boolean ambientDoor() { return p.getBoolean("ambDoor", false); }
    public void setAmbientDoor(boolean on) { p.edit().putBoolean("ambDoor", on).apply(); }
    public int ambientDoorColour() { return p.getInt("ambDoorCol", 1); }
    public void setAmbientDoorColour(int v) { p.edit().putInt("ambDoorCol", v).apply(); }

    /** Info window on the left, ChatGPT/music/energy on the right (default since 2026-11-10). */
    public boolean infoLeft() { return p.getBoolean("infoLeft", true); }
    public void setInfoLeft(boolean v) { p.edit().putBoolean("infoLeft", v).apply(); }

    /** Ghost Race on Firefox's engine (GeckoView) instead of the car's WebView. On by default (2026-11-11). */
    public boolean ghostGecko() { return true; }   // the car's own browser can't show Ghost Race's map: Firefox engine only (2026-11-12)
    public void setGhostGecko(boolean on) { p.edit().putBoolean("ghostGecko", on).apply(); }

    /** Length of the "best stretches" on the trip card, km (user can type it; default 10). */
    public float bestKm() { return p.getFloat("bestKm", 10f); }
    public void setBestKm(float km) { p.edit().putFloat("bestKm", km).apply(); }

    /** Assistant box: "gemini" (works in GBox with the user's account, 2026-11-11) or "chatgpt". */
    public String assistant() { return p.getString("assistant", "gemini"); }
    public void setAssistant(String a) { p.edit().putString("assistant", a).apply(); }

    /** App picked for the assistant box by press-and-hold (null = find it by name). */
    public String assistantPkg() { return p.getString("assistantPkg", null); }
    public void setAssistantPkg(String pkg) { p.edit().putString("assistantPkg", pkg).apply(); }
}
