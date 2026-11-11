package com.airodynamic.adas;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Song lyrics from LRCLIB (free, no key: lrclib.net), timed per line so they follow the music.
 * Music apps don't share lyrics with other apps, so the song is looked up by title and artist.
 * Only title, artist and length are sent.
 */
final class Lyrics {
    private Lyrics() {}

    static final class Line { final long ms; final String text; Line(long ms, String text) { this.ms = ms; this.text = text; } }
    interface Done { void on(String key, List<Line> lines, String note); }

    private static final Pattern TAG = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)\\]");

    /** Removes "(Official Video)", "[Lyrics]", "- Remastered" and the like, which stop the lookup from matching. */
    static String clean(String t) {
        String s = t.replaceAll("(?i)[\\(\\[][^\\)\\]]*(official|video|audio|lyric|visualizer|remaster|mv|hd|4k)[^\\)\\]]*[\\)\\]]", "");
        s = s.replaceAll("(?i)\\s+-\\s+.*(remaster|version|edit).*$", "");
        return s.trim();
    }

    static void find(String title, String artist, long durMs, Done done, String key) {
        new Thread(() -> {
            try {
                String t = clean(title), a = artist == null ? "" : artist.replaceAll("(?i)\\s*-\\s*topic$", "").trim();
                String json = null;
                if (!a.isEmpty()) {
                    String u = "https://lrclib.net/api/get?track_name=" + enc(t) + "&artist_name=" + enc(a)
                            + (durMs > 0 ? "&duration=" + Math.round(durMs / 1000.0) : "");
                    json = get(u);
                }
                String synced = null, plain = null;
                if (json != null) { JSONObject o = new JSONObject(json); synced = o.optString("syncedLyrics", null); plain = o.optString("plainLyrics", null); }
                if (isEmpty(synced)) {   // fall back to a search
                    String r = get("https://lrclib.net/api/search?q=" + enc((t + " " + a).trim()));
                    if (r != null) {
                        JSONArray arr = new JSONArray(r);
                        for (int i = 0; i < arr.length() && isEmpty(synced); i++) {
                            JSONObject o = arr.getJSONObject(i);
                            String sl = o.optString("syncedLyrics", null);
                            if (!isEmpty(sl)) synced = sl;
                            else if (isEmpty(plain)) plain = o.optString("plainLyrics", null);
                        }
                    }
                }
                List<Line> lines = new ArrayList<>();
                if (!isEmpty(synced)) {
                    for (String ln : synced.split("\n")) {
                        Matcher m = TAG.matcher(ln);
                        List<Long> times = new ArrayList<>();
                        int end = 0;
                        while (m.find()) { times.add((long) (Integer.parseInt(m.group(1)) * 60000 + Double.parseDouble(m.group(2)) * 1000)); end = m.end(); }
                        String text = ln.substring(end).trim();
                        for (long ms : times) lines.add(new Line(ms, text.isEmpty() ? "♪" : text));
                    }
                    java.util.Collections.sort(lines, (x, y) -> Long.compare(x.ms, y.ms));
                    done.on(key, lines, "");
                } else {
                    done.on(key, lines, isEmpty(plain) ? "No lyrics found for this song" : "Only untimed lyrics found for this song");
                }
            } catch (Exception e) {
                done.on(key, new ArrayList<>(), "Lyrics need internet");
            }
        }, "lyrics").start();
    }

    /** Index of the line playing at this position (ms), or -1 before the first line. */
    static int lineAt(List<Line> lines, long pos) {
        int i = -1;
        for (int k = 0; k < lines.size(); k++) { if (lines.get(k).ms <= pos + 200) i = k; else break; }
        return i;
    }

    private static boolean isEmpty(String s) { return s == null || s.isEmpty() || s.equals("null"); }
    private static String enc(String s) throws Exception { return URLEncoder.encode(s, "UTF-8"); }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "NATEE-Launcher (car home screen)");
        if (c.getResponseCode() == 404) return null;
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }
}
