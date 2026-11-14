package com.airodynamic.adas;

import android.content.Context;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Driving history over the right window (user, 2026-11-12): one row per day — date, distance, energy,
 * kWh/100, average speed, time — this month's days with a "Month so far" total, earlier months folded
 * into one total row (tap to open), and a total per year. Today is live, in the accent colour.
 */
final class HistoryOverlay extends FrameLayout {
    private static final float[] COLS = {1.25f, 1f, .9f, 1f, .8f, 1.15f};
    private static final String[] MONTHS = {"JANUARY", "FEBRUARY", "MARCH", "APRIL", "MAY", "JUNE", "JULY",
            "AUGUST", "SEPTEMBER", "OCTOBER", "NOVEMBER", "DECEMBER"};
    private static final String[] MON = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
    private static final int MONTH_COL = 0xFFFFB020, YEAR_COL = 0xFF2EE59D;

    private final Theme th;
    private final LinearLayout list;
    private final Set<String> open = new HashSet<>();   // folded months / years the user opened
    private final Runnable tick = this::refresh;

    HistoryOverlay(Context c, Theme th, Runnable onClose) {
        super(c);
        this.th = th;
        setBackground(Ui.round(th.panel, th.accent, 20, c));
        setClipToOutline(true);
        setClickable(true);   // taps don't fall through to the cards underneath
        int p = Ui.dp(c, 10);
        setPadding(p, p, p, p);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.label(c, "Driving history", th), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView x = Ui.text(c, "✕", 20, th.text, true);
        x.setGravity(Gravity.CENTER);
        x.setBackground(Ui.round(th.card, th.accent, 21, c));
        x.setContentDescription("Close the driving history");
        x.setOnClickListener(v -> onClose.run());
        head.addView(x, new LinearLayout.LayoutParams(Ui.dp(c, 42), Ui.dp(c, 42)));
        col.addView(head);
        col.addView(row(new String[]{"Date", "Distance", "Avg speed", "Energy", "kWh/100", "Time"}, th.muted, false, 11, 0, Double.NaN));

        list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(c);
        sv.setBackground(Ui.round(th.card, th.line, 12, c));
        sv.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4));
        sv.addView(list, new ScrollView.LayoutParams(-1, -2));
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView hint = Ui.text(c, "scroll for older days · tap a month to open its days", 11, th.muted, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, Ui.dp(c, 4), 0, 0);
        col.addView(hint);
        addView(col, new LayoutParams(-1, -1));
        refresh();
    }

    @Override protected void onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow(); }

    /** Rebuilds the list (today is live: every 10 s while open). */
    private void refresh() {
        removeCallbacks(tick);
        list.removeAllViews();
        TreeMap<String, double[]> h = TripEnergy.get(getContext()).history();
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date());
        String thisMonth = today.substring(0, 7), thisYear = today.substring(0, 4);
        if (h.isEmpty()) {
            TextView t = Ui.text(getContext(), "No driving saved yet: the history starts today and fills up as you drive.", 13, th.muted, false);
            t.setPadding(Ui.dp(getContext(), 10), Ui.dp(getContext(), 10), Ui.dp(getContext(), 10), Ui.dp(getContext(), 10));
            list.addView(t);
        }
        // newest first: years, then months, then days
        TreeMap<String, TreeMap<String, List<Map.Entry<String, double[]>>>> years = new TreeMap<>(java.util.Collections.reverseOrder());
        for (Map.Entry<String, double[]> e : h.entrySet()) {
            String y = e.getKey().substring(0, 4), m = e.getKey().substring(0, 7);
            years.computeIfAbsent(y, k -> new TreeMap<>(java.util.Collections.reverseOrder()))
                    .computeIfAbsent(m, k -> new ArrayList<>()).add(e);
        }
        for (Map.Entry<String, TreeMap<String, List<Map.Entry<String, double[]>>>> ye : years.entrySet()) {
            String y = ye.getKey();
            boolean yearOpen = y.equals(thisYear) || open.contains(y);
            double[] yt = new double[4];
            for (List<Map.Entry<String, double[]>> days : ye.getValue().values()) for (Map.Entry<String, double[]> d : days) add(yt, d.getValue());
            if (!y.equals(thisYear)) {   // an earlier year: folded into its total, tap to see its months
                list.addView(heading("YEAR " + y + (yearOpen ? " ⌄" : " ›"), () -> toggle(y)));
                list.addView(total(y + " total", yt, YEAR_COL));
                if (!yearOpen) continue;
            }
            for (Map.Entry<String, List<Map.Entry<String, double[]>>> me : ye.getValue().entrySet()) {
                String m = me.getKey();
                int mi = Integer.parseInt(m.substring(5, 7)) - 1;
                boolean current = m.equals(thisMonth), monthOpen = current || open.contains(m);
                double[] mt = new double[4];
                for (Map.Entry<String, double[]> d : me.getValue()) add(mt, d.getValue());
                list.addView(heading(MONTHS[mi] + " " + y + (current ? "" : monthOpen ? " ⌄" : " ›"), current ? null : () -> toggle(m)));
                list.addView(total(current ? "Month so far" : cap(MONTHS[mi]) + " total", mt, MONTH_COL));
                if (!monthOpen) continue;
                List<Map.Entry<String, double[]>> days = me.getValue();
                java.util.Collections.reverse(days);   // newest day first
                for (Map.Entry<String, double[]> d : days) {
                    boolean isToday = d.getKey().equals(today);
                    int dd = Integer.parseInt(d.getKey().substring(8, 10));
                    list.addView(row(cells(dd + " " + MON[mi] + (isToday ? " · today" : ""), d.getValue()), isToday ? th.accent : th.text, false, 13, 0, speed(d.getValue())));
                }
            }
            if (y.equals(thisYear)) {
                list.addView(heading("YEAR " + y, null));
                list.addView(total(y + " total", yt, YEAR_COL));
            }
        }
        postDelayed(tick, 10_000);
    }

    private void toggle(String key) { if (!open.remove(key)) open.add(key); refresh(); }

    private static void add(double[] t, double[] v) { t[0] += v[0]; t[1] += v[1]; t[2] += v[2]; t[3] += v[3]; }
    private static String cap(String s) { return s.charAt(0) + s.substring(1).toLowerCase(Locale.US); }

    /** Average speed while moving (stops not counted), km/h. */
    private static double speed(double[] v) { return v[3] >= 60 ? v[0] / (v[3] / 3600.0) : Double.NaN; }

    /** {label, km, km/h, kWh, kWh/100, "1 h 54 min"} — average speed next to the distance (user, 2026-11-14) */
    private static String[] cells(String label, double[] v) {
        double km = v[0], kwh = v[1], sec = v[2], sp = speed(v);
        long min = Math.round(sec / 60);
        return new String[]{label,
                String.format(Locale.US, "%.1f km", km),
                Double.isNaN(sp) ? "–" : Math.round(sp) + " km/h",
                String.format(Locale.US, "%.1f kWh", kwh),
                km >= 0.5 && kwh > 0 ? String.format(Locale.US, "%.1f", kwh / km * 100) : "–",
                (min / 60) + " h " + String.format(Locale.US, "%02d", min % 60) + " min"};
    }

    private TextView heading(String s, Runnable onTap) {
        TextView t = Ui.text(getContext(), s, 12, th.muted, false);
        t.setLetterSpacing(0.06f);
        t.setPadding(Ui.dp(getContext(), 10), Ui.dp(getContext(), 8), Ui.dp(getContext(), 8), Ui.dp(getContext(), 3));
        if (onTap != null) t.setOnClickListener(v -> onTap.run());
        return t;
    }

    private LinearLayout total(String label, double[] v, int col) {
        return row(cells(label, v), col, true, 13, (col & 0x00FFFFFF) | 0x26000000, speed(v));
    }

    private LinearLayout row(String[] cells, int color, boolean bold, float size, int bg, double speed) {
        Context c = getContext();
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 10), Ui.dp(c, 4));
        if (bg != 0) r.setBackground(Ui.round(bg, 0, 8, c));
        for (int i = 0; i < cells.length; i++) {
            TextView t = Ui.text(c, cells[i], size, i == 2 && !Double.isNaN(speed) ? Theme.speedColour(speed) : color, bold);
            t.setSingleLine(true);
            t.setGravity(i == 0 ? Gravity.START : Gravity.END);
            r.addView(t, new LinearLayout.LayoutParams(0, -2, COLS[i]));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        if (bg != 0) lp.setMargins(Ui.dp(c, 6), Ui.dp(c, 1), Ui.dp(c, 6), Ui.dp(c, 1));
        r.setLayoutParams(lp);
        return r;
    }
}
