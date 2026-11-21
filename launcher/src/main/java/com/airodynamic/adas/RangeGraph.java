package com.airodynamic.adas;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Range graph in the right window (user, 2026-11-21): NEDC (the car's own range, grey), WLTP (blue) and My range
 * (orange) against the distance driven — for this trip, today, or since the last charge (tap to switch).
 * Across: starts at 0–20 km and shrinks to fit longer drives. Up: a 200 km window, taller when the lines spread
 * further apart, so every line always fits. Each line ends with a dot and its current value. Read only.
 */
final class RangeGraph extends LinearLayout {
    static final int NEDC = 0xFF9AA0A8, WLTP = 0xFF3AA0FF, MY = 0xFFFF8A1F;
    private static final String[] MODES = {"Trip", "Today", "Charge"};
    private static final double STEP_KM = 0.1;
    private static final int MAX_POINTS = 600;

    /** One series: points {km, nedc, wltp, my}. */
    private static final class Series {
        final List<double[]> pts = new ArrayList<>();
        void add(double[] p) {
            if (pts.isEmpty() || p[0] - pts.get(pts.size() - 1)[0] >= STEP_KM) pts.add(p);
            else pts.set(pts.size() - 1, p);   // the newest point always shows the current values
            if (pts.size() > MAX_POINTS) {     // keep it small: drop every other point
                List<double[]> half = new ArrayList<>();
                for (int i = 0; i < pts.size(); i++) if (i % 2 == 0 || i == pts.size() - 1) half.add(pts.get(i));
                pts.clear(); pts.addAll(half);
            }
        }
        String save() {
            StringBuilder b = new StringBuilder();
            for (double[] p : pts) b.append(String.format(Locale.US, "%.2f,%.0f,%.0f,%.0f;", p[0], p[1], p[2], p[3]));
            return b.toString();
        }
        void load(String s) {
            pts.clear();
            if (s == null) return;
            for (String part : s.split(";")) {
                String[] v = part.split(",");
                if (v.length != 4) continue;
                try { pts.add(new double[]{Double.parseDouble(v[0]), Double.parseDouble(v[1]), Double.parseDouble(v[2]), Double.parseDouble(v[3])}); }
                catch (NumberFormatException ignored) { }
            }
        }
    }

    private final Theme th;
    private final SharedPreferences store;
    private final Series[] series = {new Series(), new Series(), new Series()};
    private final TextView[] chips = new TextView[3];
    private final Plot plot;
    private int mode;
    private double lastTripKm = -1, lastTodayKm = -1, totalKm, chargeBaseKm;
    private double lastSoc = Double.NaN;
    private String day = "";
    private long savedAt;

    RangeGraph(Context c, Theme th) {
        super(c);
        this.th = th;
        store = c.getSharedPreferences("range_graph", Context.MODE_PRIVATE);
        mode = store.getInt("mode", 0);
        day = store.getString("day", "");
        series[1].load(store.getString("today", ""));
        series[2].load(store.getString("charge", ""));
        totalKm = Double.longBitsToDouble(store.getLong("total", 0));
        chargeBaseKm = Double.longBitsToDouble(store.getLong("chargeBase", 0));

        setOrientation(VERTICAL);
        setBackground(Ui.round(th.card, th.line, 12, c));
        setPadding(Ui.dp(c, 8), Ui.dp(c, 3), Ui.dp(c, 6), Ui.dp(c, 2));
        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(legend(c, "NEDC", NEDC));
        top.addView(legend(c, "WLTP", WLTP));
        top.addView(legend(c, "My range", MY));
        top.addView(new View(c), new LayoutParams(0, 1, 1f));
        for (int i = 0; i < 3; i++) {
            final int m = i;
            TextView t = Ui.text(c, MODES[i], 10.5f, th.muted, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(Ui.dp(c, 7), Ui.dp(c, 1), Ui.dp(c, 7), Ui.dp(c, 1));
            t.setOnClickListener(v -> { mode = m; store.edit().putInt("mode", m).apply(); showMode(); });
            LayoutParams lp = new LayoutParams(-2, -2);
            lp.leftMargin = Ui.dp(c, 4);
            top.addView(t, lp);
            chips[i] = t;
        }
        addView(top, new LayoutParams(-1, -2));
        plot = new Plot(c);
        addView(plot, new LayoutParams(-1, 0, 1f));
        showMode();
    }

    private TextView legend(Context c, String name, int color) {
        TextView t = Ui.text(c, "▬ " + name, 10.5f, th.muted, false);
        android.text.SpannableString s = new android.text.SpannableString(t.getText());
        s.setSpan(new android.text.style.ForegroundColorSpan(color), 0, 1, 0);
        t.setText(s);
        t.setPadding(0, 0, Ui.dp(c, 8), 0);
        return t;
    }

    private void showMode() {
        for (int i = 0; i < 3; i++) {
            boolean on = i == mode;
            chips[i].setBackground(on ? Ui.round(0xFFFF8A1F, 0xFFFF8A1F, 10, getContext()) : Ui.round(0, th.line, 10, getContext()));
            chips[i].setTextColor(on ? 0xFF2A1400 : th.muted);
        }
        plot.invalidate();
    }

    /** A new reading: the three ranges (NaN when unknown) and the car's state. */
    void update(CarData.State s, double nedc, double wltp, double my) {
        TripEnergy te = TripEnergy.get(getContext());
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date());
        if (!today.equals(day)) { day = today; series[1].pts.clear(); lastTodayKm = -1; }
        // total distance driven, for "since charge": what today's distance added since the last reading
        double tk = te.todayKm();
        if (lastTodayKm >= 0) totalKm += tk >= lastTodayKm ? tk - lastTodayKm : tk;
        lastTodayKm = tk;
        // charged: battery % went up while standing still -> "Charge" starts again
        boolean still = Double.isNaN(s.speedKmh) || s.speedKmh < 1;
        if (!Double.isNaN(s.socPct)) {
            if (!Double.isNaN(lastSoc) && still && s.socPct >= lastSoc + 1) { series[2].pts.clear(); chargeBaseKm = totalKm; }
            if (Double.isNaN(lastSoc) || !still || s.socPct < lastSoc || s.socPct >= lastSoc + 1) lastSoc = s.socPct;
        }
        // a new trip starts the "Trip" line again
        if (te.km < lastTripKm - 0.05) series[0].pts.clear();
        lastTripKm = te.km;
        if (Double.isNaN(nedc) && Double.isNaN(wltp) && Double.isNaN(my)) return;
        series[0].add(new double[]{te.km, nedc, wltp, my});
        series[1].add(new double[]{tk, nedc, wltp, my});
        series[2].add(new double[]{totalKm - chargeBaseKm, nedc, wltp, my});
        if (s.at - savedAt > 30_000) {
            savedAt = s.at;
            store.edit().putString("day", day).putString("today", series[1].save()).putString("charge", series[2].save())
                    .putLong("total", Double.doubleToLongBits(totalKm)).putLong("chargeBase", Double.doubleToLongBits(chargeBaseKm)).apply();
        }
        plot.invalidate();
    }

    /** The drawing: grid, three lines, a dot and the current value at each line's end. */
    private final class Plot extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), grid = new Paint(), text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final float d;

        Plot(Context c) {
            super(c);
            d = getResources().getDisplayMetrics().density;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
            grid.setColor((th.line & 0x00FFFFFF) | 0x80000000);
            grid.setStrokeWidth(d);
        }

        @Override protected void onDraw(Canvas cv) {
            List<double[]> pts = series[mode].pts;
            float w = getWidth(), h = getHeight();
            float left = 26 * d, right = w - 2 * d, top = 4 * d, bottom = h - 11 * d;
            // across: from this series' start, at least 20 km
            double x0 = pts.isEmpty() ? 0 : pts.get(0)[0];
            double xEnd = pts.isEmpty() ? 0 : pts.get(pts.size() - 1)[0] - x0;
            double xSpan = Math.max(20, xEnd);
            // up: every value fits; at least 200 km tall, centred on the lines
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (double[] p : pts) for (int k = 1; k <= 3; k++) if (!Double.isNaN(p[k])) { lo = Math.min(lo, p[k]); hi = Math.max(hi, p[k]); }
            if (lo == Double.MAX_VALUE) { lo = 150; hi = 350; }
            double span = Math.max(200, (hi - lo) * 1.15);
            double mid = (hi + lo) / 2;
            double yMin = Math.floor((mid - span / 2) / 10) * 10, yMax = yMin + Math.ceil(span / 10) * 10;
            text.setTextSize(8.5f * d);
            text.setColor(th.muted);
            text.setTypeface(android.graphics.Typeface.DEFAULT);
            for (int g = 0; g <= 2; g++) {
                float y = top + (bottom - top) * g / 2f;
                cv.drawLine(left, y, right, y, grid);
                cv.drawText(String.valueOf(Math.round(yMax - (yMax - yMin) * g / 2.0)), 0, y + 3 * d, text);
            }
            for (int g = 0; g <= 2; g++) {
                float x = left + (right - left) * g / 2f;
                cv.drawLine(x, top, x, bottom, grid);
                String lab = g == 0 ? "0" : String.format(Locale.US, "%.0f km", xSpan * g / 2);
                float tw = text.measureText(lab);
                cv.drawText(lab, g == 0 ? x : g == 1 ? x - tw / 2 : x - tw, h - 1.5f * d, text);
            }
            if (pts.isEmpty()) {
                text.setTextSize(10 * d);
                String msg = "fills in as you drive";
                cv.drawText(msg, (left + right - text.measureText(msg)) / 2, (top + bottom) / 2, text);
                return;
            }
            int[] colors = {NEDC, WLTP, MY};
            float[] endX = new float[3], endY = new float[3];
            double[] endV = new double[3];
            for (int k = 0; k < 3; k++) {
                path.reset();
                boolean started = false;
                endV[k] = Double.NaN;
                for (double[] p : pts) {
                    if (Double.isNaN(p[k + 1])) continue;
                    float x = (float) (left + (right - left) * (p[0] - x0) / xSpan);
                    float y = (float) (bottom - (bottom - top) * (p[k + 1] - yMin) / (yMax - yMin));
                    if (!started) { path.moveTo(x, y); started = true; } else path.lineTo(x, y);
                    endX[k] = x; endY[k] = y; endV[k] = p[k + 1];
                }
                if (!started) continue;
                line.setColor(colors[k]);
                line.setStrokeWidth((k == 2 ? 2.6f : 2f) * d);
                cv.drawPath(path, line);
            }
            // dots and values at the line ends; labels kept apart so they never sit on top of each other
            text.setTextSize(10 * d);
            text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            Integer[] order = {0, 1, 2};
            java.util.Arrays.sort(order, (a, b) -> Float.compare(endY[a], endY[b]));
            float lastLabelY = -1e9f;
            for (int i : order) {
                if (Double.isNaN(endV[i])) continue;
                Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
                dot.setColor(colors[i]);
                cv.drawCircle(endX[i], endY[i], (i == 2 ? 3.4f : 2.8f) * d, dot);
                String lab = Math.round(endV[i]) + " km";
                float tw = text.measureText(lab);
                float ly = Math.max(endY[i] + 3.5f * d, lastLabelY + 10 * d);
                ly = Math.min(ly, bottom);
                lastLabelY = ly;
                float lx = endX[i] + 5 * d + tw <= right ? endX[i] + 5 * d : endX[i] - 5 * d - tw;   // left of the dot at the edge
                text.setColor(colors[i]);
                cv.drawText(lab, lx, ly, text);
            }
        }
    }
}
