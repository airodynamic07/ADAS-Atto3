package com.airodynamic.adas;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Problems reported by voice through "Hey Natee chat" (user, 2026-11-21): what the driver said, when, and the car's
 * state at that moment, kept on the head unit (last 50). Settings → Problems shows them to photograph for Claude.
 */
final class ProblemLog {
    private static final int MAX = 50;

    private static SharedPreferences store(Context c) { return c.getSharedPreferences("problems", Context.MODE_PRIVATE); }

    static synchronized void add(Context c, String what, String chat) {
        try {
            CarData.State s = CarData.get(c).last();
            TripEnergy te = TripEnergy.get(c);
            StringBuilder b = new StringBuilder();
            b.append(new SimpleDateFormat("d MMM HH:mm", Locale.US).format(new Date())).append(" · ").append(what.trim());
            b.append("\n   car: ").append(Double.isNaN(s.speedKmh) ? "–" : Math.round(s.speedKmh) + " km/h")
                    .append(" · gear ").append(s.gear == 2 ? "R" : s.gear == 3 ? "N" : s.gear == 4 ? "D" : "P")
                    .append(" · battery ").append(Double.isNaN(s.socPct) ? "–" : Math.round(s.socPct) + "%")
                    .append(" · trip ").append(String.format(Locale.US, "%.1f km", te.km));
            try {
                String v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
                b.append(" · launcher ").append(v);
            } catch (Exception ignored) { }
            if (chat != null && !chat.trim().isEmpty()) b.append("\n   chat: ").append(chat.trim());
            JSONArray a = new JSONArray(store(c).getString("list", "[]"));
            JSONArray out = new JSONArray().put(b.toString());
            for (int i = 0; i < a.length() && out.length() < MAX; i++) out.put(a.getString(i));
            store(c).edit().putString("list", out.toString()).apply();
        } catch (Exception ignored) { }
    }

    static synchronized String[] all(Context c) {
        try {
            JSONArray a = new JSONArray(store(c).getString("list", "[]"));
            String[] r = new String[a.length()];
            for (int i = 0; i < a.length(); i++) r[i] = a.getString(i);
            return r;
        } catch (Exception e) { return new String[0]; }
    }

    static void clear(Context c) { store(c).edit().remove("list").apply(); }
}
