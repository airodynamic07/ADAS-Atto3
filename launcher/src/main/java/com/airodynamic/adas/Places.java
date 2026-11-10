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

    public static final class Weather {
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

    public interface WeatherListener { void onWeather(Weather w); }

    public static void weather(Context c, WeatherListener l) {
        if (cached != null && System.currentTimeMillis() - cachedAt < 20 * 60_000L) { l.onWeather(cached); return; }
        final Location loc = last(c);
        if (loc == null) return;
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
                cached = w;
                cachedAt = System.currentTimeMillis();
                main.post(() -> l.onWeather(w));
            } catch (Throwable ignored) {
                // no internet: the card keeps showing "–"
            }
        }, "weather").start();
    }
}
