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
}
