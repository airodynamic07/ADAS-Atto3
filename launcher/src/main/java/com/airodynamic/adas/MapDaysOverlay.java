package com.airodynamic.adas;

import android.content.Context;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * "Every day Map" (Air 2026-11-23, was "Today route"): the months that have routes; tap a month to see its days, tap a
 * day to see that day's map. Days are kept from 23 Nov 2026 on (earlier ones were deleted by older versions).
 */
final class MapDaysOverlay extends FrameLayout {
    private static final String[] MONTHS = {"JANUARY", "FEBRUARY", "MARCH", "APRIL", "MAY", "JUNE", "JULY",
            "AUGUST", "SEPTEMBER", "OCTOBER", "NOVEMBER", "DECEMBER"};
    private final Theme th;
    private final LinearLayout list;
    private final java.util.function.Consumer<String> onDay;
    private String openMonth;   // yyyy-MM the driver opened

    MapDaysOverlay(Context c, Theme th, Runnable onClose, java.util.function.Consumer<String> onDay) {
        super(c);
        this.th = th;
        this.onDay = onDay;
        setBackground(Ui.round(th.panel, th.accent, 20, c));
        setClipToOutline(true);
        setClickable(true);
        int p = Ui.dp(c, 10);
        setPadding(p, p, p, p);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.label(c, "Every day map", th), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView x = Ui.text(c, "✕", 20, th.text, true);
        x.setGravity(Gravity.CENTER);
        x.setBackground(Ui.round(th.card, th.accent, 21, c));
        x.setOnClickListener(v -> onClose.run());
        head.addView(x, new LinearLayout.LayoutParams(Ui.dp(c, 42), Ui.dp(c, 42)));
        col.addView(head);
        list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(c);
        sv.setBackground(Ui.round(th.card, th.line, 12, c));
        sv.addView(list, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, 0, 1f);
        sl.topMargin = Ui.dp(c, 6);
        col.addView(sv, sl);
        TextView hint = Ui.text(c, "tap a month to see its days · tap a day to see its map", 11, th.muted, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, Ui.dp(c, 4), 0, 0);
        col.addView(hint);
        addView(col, new LayoutParams(-1, -1));
        refresh();
    }

    private void refresh() {
        Context c = getContext();
        list.removeAllViews();
        List<String> days = TodayRoute.get(c).days();
        TreeMap<String, double[]> hist = TripEnergy.get(c).history();
        String today = TodayRoute.get(c).today();
        if (!days.contains(today)) days.add(0, today);   // today is always there (live)
        Map<String, List<String>> byMonth = new LinkedHashMap<>();
        for (String d : days) {
            String m = d.substring(0, 7);
            List<String> l = byMonth.get(m);
            if (l == null) byMonth.put(m, l = new ArrayList<>());
            l.add(d);
        }
        for (Map.Entry<String, List<String>> e : byMonth.entrySet()) {
            String m = e.getKey();
            double km = 0;
            for (String d : e.getValue()) { double[] v = hist.get(d); if (v != null) km += v[0]; }
            int mi = Integer.parseInt(m.substring(5, 7)) - 1;
            boolean open = m.equals(openMonth);
            TextView row = Ui.text(c, (open ? "▾ " : "▸ ") + MONTHS[mi] + " " + m.substring(0, 4) + "   ·   "
                    + e.getValue().size() + (e.getValue().size() == 1 ? " day" : " days") + String.format(Locale.US, "   ·   %.0f km", km),
                    15, 0xFFFFB020, true);
            row.setPadding(Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12));
            row.setOnClickListener(v -> { openMonth = open ? null : m; refresh(); });
            list.addView(row);
            if (!open) continue;
            for (String d : e.getValue()) {
                double[] v = hist.get(d);
                String label = (d.equals(today) ? "Today · " : "") + RouteOverlay.niceDay(d)
                        + (v == null ? "" : String.format(Locale.US, "   ·   %.1f km", v[0]));
                TextView dr = Ui.text(c, "    🗺  " + label, 14, d.equals(today) ? th.accent : th.text, false);
                dr.setPadding(Ui.dp(c, 12), Ui.dp(c, 9), Ui.dp(c, 12), Ui.dp(c, 9));
                dr.setOnClickListener(v2 -> onDay.accept(d));
                list.addView(dr);
            }
        }
    }
}
