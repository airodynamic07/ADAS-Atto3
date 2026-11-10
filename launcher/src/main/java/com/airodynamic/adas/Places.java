package com.airodynamic.adas;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.Locale;

/**
 * Location for "Where I parked" (saved when the car goes into P) and the weather
 * (Open-Meteo, free and keyless; refreshed every 20 minutes when there is internet).
 */
public final class Places {
    private Places() {}

    @SuppressLint("MissingPermission")
    public static Location last(Context c) {
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            Location best = null;
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean asked;
    private static volatile int fixes;
    private static volatile String lastProvider = "", askError = "";

    /** Short GPS report for the weather card while it waits: providers on/off, fixes received. */
    public static String gpsReport(Context c) {
        StringBuilder b = new StringBuilder();
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            for (String p : lm.getAllProviders()) {
                if (LocationManager.PASSIVE_PROVIDER.equals(p)) continue;
                b.append(p).append(lm.isProviderEnabled(p) ? " on, " : " OFF, ");
            }
            int mode = android.provider.Settings.Secure.getInt(c.getContentResolver(), android.provider.Settings.Secure.LOCATION_MODE, -1);
            b.append("mode ").append(mode).append(", ");
        } catch (Throwable t) { b.append(t.getClass().getSimpleName()).append(", "); }
        b.append(fixes).append(" fixes");
        if (!askError.isEmpty()) b.append(", ").append(askError);
        return b.toString();
    }

    /** Ask the car for location updates (GPS and network) so "last known" is never empty. Once. */
    @SuppressLint("MissingPermission")
    public static synchronized void startUpdates(Context c) {
        if (asked) return;
        asked = true;
        try {
            LocationManager lm = (LocationManager) c.getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
            android.location.LocationListener ll = new android.location.LocationListener() {
                @Override public void onLocationChanged(Location l) { fixes++; lastProvider = l.getProvider(); }
                @Override public void onStatusChanged(String p, int s, android.os.Bundle b) {}
                @Override public void onProviderEnabled(String p) {}
                @Override public void onProviderDisabled(String p) {}
            };
            for (String p : lm.getAllProviders()) {
                if (LocationManager.PASSIVE_PROVIDER.equals(p)) continue;
                try { lm.requestLocationUpdates(p, 60_000L, 50f, ll, Looper.getMainLooper()); }
                catch (Throwable t) { askError = p + ": " + t.getClass().getSimpleName(); }
            }
        } catch (Throwable ignored) {}
    }

    public static final class Weather {
        public String note = "";
        public int tempC = Integer.MIN_VALUE, code = -1, rainPct = -1, rainHour = -1;
        public String icon() {
            if (code < 0) return "–";
            if (code == 0) return "☀";
            if (code <= 3) return "⛅";
            if (code <= 48) return "🌫";
            if (code <= 67 || (code >= 80 && code <= 82)) return "🌧";
            if (code >= 95) return "⛈";
            return "☁";
        }
    }

    private static Weather cached;
    private static long cachedAt;
    private static volatile long failedAt;

    public interface WeatherListener { void onWeather(Weather w); }

    public static void weather(Context c, WeatherListener l) {
        if (cached != null && System.currentTimeMillis() - cachedAt < 20 * 60_000L) { l.onWeather(cached); return; }
        if (System.currentTimeMillis() - failedAt < 60_000L) return;
        startUpdates(c);
        Location l0 = last(c);
        if (l0 == null) {
            Prefs pr = new Prefs(c);
            if (!Double.isNaN(pr.parkedLat())) {
                l0 = new Location("saved");
                l0.setLatitude(pr.parkedLat());
                l0.setLongitude(pr.parkedLng());
            }
        }
        if (l0 == null) { Weather w = new Weather(); w.note = "waiting for GPS · " + gpsReport(c); l.onWeather(w); return; }
        final Location loc = l0;
        failedAt = System.currentTimeMillis();   // one try at a time; cleared on success
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                String url = String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code"
                        + "&hourly=precipitation_probability&forecast_days=1&timezone=auto", loc.getLatitude(), loc.getLongitude());
                HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
                con.setConnectTimeout(8000);
                con.setReadTimeout(10000);
                String json;
                try (InputStream in = con.getInputStream()) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                    json = b.toString("UTF-8");
                }
                JSONObject o = new JSONObject(json);
                Weather w = new Weather();
                JSONObject cur = o.getJSONObject("current");
                w.tempC = (int) Math.round(cur.getDouble("temperature_2m"));
                w.code = cur.getInt("weather_code");
                JSONArray p = o.getJSONObject("hourly").getJSONArray("precipitation_probability");
                int now = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
                for (int h = now; h < Math.min(24, p.length()); h++) {
                    int v = p.optInt(h, 0);
                    if (v > w.rainPct) { w.rainPct = v; w.rainHour = h; }
                }
                failedAt = 0;
                cached = w;
                cachedAt = System.currentTimeMillis();
                main.post(() -> l.onWeather(w));
            } catch (Throwable t) {
                // no internet: say so on the card and try again in a minute
                Weather w = new Weather();
                w.note = "no internet (" + t.getClass().getSimpleName() + ")";
                main.post(() -> l.onWeather(w));
            }
        }, "weather").start();
    }
}
