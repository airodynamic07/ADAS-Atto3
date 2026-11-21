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
 * Across: starts at 0–10 km and shrinks to fit longer drives. Up: a 200 km window, taller when the lines spread
 * further apart, so every line always fits. Each line ends with a dot and its current value. Read only.
 */
final class RangeGraph extends LinearLayout {
    static final int NEDC = 0xFF9AA0A8, WLTP = 0xFF3AA0FF, EPA = 0xFFB388FF, MY = 0xFFFF8A1F;
    /** EPA (US test): no official Atto 3 figure, estimated as WLTP − 12 % (user, 2026-11-21). */
    static final double EPA_OF_WLTP = 0.88;

    /** line k of a point: 0 NEDC, 1 WLTP, 2 EPA (from WLTP), 3 My range */
    private static double val(double[] p, int k) { return k == 2 ? p[2] * EPA_OF_WLTP : k == 3 ? p[3] : p[k + 1]; }
    private static final String[] MODES = {"Trip", "Today", "Charge to charge"};
    private static final String[] MENU = {"This trip", "Today", "Charge to charge"};
    private static final double STEP_KM = 0.1;
    private static final int MAX_POINTS = 600;

    /** One series: points {km, nedc, wltp, my}. */
    private static final class Series {
        final List<double[]> pts = new ArrayList<>();
        void add(double[] p) {
            // the distance can be corrected a little downwards: never go backwards, the line just carries on
            if (!pts.isEmpty() && p[0] < pts.get(pts.size() - 1)[0]) p[0] = pts.get(pts.size() - 1)[0];
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
    private TextView pick;
    private final Plot plot;
    private int mode;
    private final Rec rec;

    RangeGraph(Context c, Theme th) {
        super(c);
        this.th = th;
        store = c.getSharedPreferences("range_graph", Context.MODE_PRIVATE);
        mode = store.getInt("mode", 0);
        rec = Rec.get(c);

        setOrientation(VERTICAL);
        setBackground(Ui.round(th.card, th.line, 12, c));
        setPadding(Ui.dp(c, 8), Ui.dp(c, 3), Ui.dp(c, 6), Ui.dp(c, 2));
        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(legend(c, "NEDC", NEDC));
        top.addView(legend(c, "WLTP", WLTP));
        top.addView(legend(c, "EPA", EPA));
        top.addView(legend(c, "My range", MY));
        top.addView(new View(c), new LayoutParams(0, 1, 1f));
        // one button: shows what you see; a tap pops up the choices (user, 2026-11-21: was three chips, "Charge" got cut off)
        pick = Ui.text(c, "", 10.5f, 0xFF2A1400, true);
        pick.setGravity(Gravity.CENTER);
        pick.setPadding(Ui.dp(c, 9), Ui.dp(c, 1), Ui.dp(c, 9), Ui.dp(c, 1));
        pick.setBackground(Ui.round(MY, MY, 10, c));
        pick.setOnClickListener(v -> openMenu());
        top.addView(pick, new LayoutParams(-2, -2));
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
        t.setPadding(0, 0, Ui.dp(c, 6), 0);
        return t;
    }

    private void showMode() {
        pick.setText(MODES[mode] + " ▾");
        plot.invalidate();
    }

    private void openMenu() {
        Context c = getContext();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(VERTICAL);
        box.setBackground(Ui.round(th.panel, 0xFF3A6FD8, 12, c));
        int pad = Ui.dp(c, 2);
        box.setPadding(pad, pad, pad, pad);
        final android.widget.PopupWindow pw = new android.widget.PopupWindow(box, Ui.dp(c, 190), -2, true);
        for (int i = 0; i < 3; i++) {
            final int m = i;
            boolean on = i == mode;
            TextView t = Ui.text(c, (on ? "✓ " : "") + MENU[i], 14, on ? MY : th.text, on);
            t.setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 10));
            if (on) t.setBackground(Ui.round(0x33FF8A1F, 0x33FF8A1F, 10, c));
            t.setOnClickListener(v -> { mode = m; store.edit().putInt("mode", m).apply(); showMode(); pw.dismiss(); });
            box.addView(t, new LayoutParams(-1, -2));
        }
        pw.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));   // a tap outside closes it
        pw.setOutsideTouchable(true);
        pw.setElevation(Ui.dp(c, 8));
        pw.showAsDropDown(pick, 0, Ui.dp(c, 4), Gravity.END);
    }

    /** Redraw with the newest readings (the recording itself runs all the time in {@link Rec}). */
    void refresh() { plot.postInvalidate(); }

    /**
     * The recording (user, 2026-11-21: "this trip 3 km already but the graph didn't develop"): it used to record only
     * while the launcher screen was showing, so a drive with another app in front was missed. Now it listens to the car
     * all the time, next to the trip energy counter.
     */
    static final class Rec implements CarData.Listener {
        private static Rec instance;
        static synchronized Rec get(Context c) {
            if (instance == null) {
                instance = new Rec(c.getApplicationContext());
                CarData.get(c).addListener(instance);
            }
            return instance;
        }
        private final Context app;
        private final SharedPreferences store;
        final Series[] series = {new Series(), new Series(), new Series()};
        private double lastTripKm = -1, lastTodayKm = -1, totalKm, chargeBaseKm;
        private long tripStart;
        private double lastSoc = Double.NaN;
        private String day;
        private long savedAt;

        private Rec(Context app) {
            this.app = app;
            store = app.getSharedPreferences("range_graph", Context.MODE_PRIVATE);
            day = store.getString("day", "");
            series[0].load(store.getString("trip", ""));
            lastTripKm = Double.longBitsToDouble(store.getLong("tripKm", Double.doubleToLongBits(-1)));
            tripStart = store.getLong("tripStart", 0);
            series[1].load(store.getString("today", ""));
            series[2].load(store.getString("charge", ""));
            totalKm = Double.longBitsToDouble(store.getLong("total", 0));
            chargeBaseKm = Double.longBitsToDouble(store.getLong("chargeBase", 0));
        }

        /** NEDC (the car's range), WLTP (× 420/480) and My range (80 % your last 10 km, 20 % WLTP); NaN when unknown. */
        static double[] ranges(Context c, CarData.State s) {
            double left = !Double.isNaN(s.battLeftKwh) ? s.battLeftKwh : !Double.isNaN(s.socPct) ? s.socPct / 100 * 60.48 * (Double.isNaN(s.healthPct) ? 1 : s.healthPct / 100.0) : Double.NaN;
            TripEnergy te = TripEnergy.get(c);
            double mine = te.myAvg10();   // last 10 km (user, 2026-11-21: was 50)
            if (Double.isNaN(mine)) mine = te.myAvg50();
            if (Double.isNaN(mine)) mine = s.last50;
            double own = !Double.isNaN(left) && !Double.isNaN(mine) && mine > 3 ? left / mine * 100 : Double.NaN;
            double nedc = s.rangeKm >= 0 ? s.rangeKm : Double.NaN;
            double wltp = !Double.isNaN(nedc) ? nedc * 420.0 / 480.0 : Double.NaN;
            double my = !Double.isNaN(own) && !Double.isNaN(wltp) ? 0.8 * own + 0.2 * wltp : own;
            return new double[]{nedc, wltp, my};
        }

        @Override public synchronized void onCar(CarData.State s) {
            TripEnergy te = TripEnergy.get(app);
            double[] r = ranges(app, s);
            double nedc = r[0], wltp = r[1], my = r[2];
            String today = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date());
            if (!today.equals(day)) { day = today; series[1].pts.clear(); lastTodayKm = -1; }
            // total distance driven, for "since charge": what today's distance added since the last reading
            double tk = te.todayKm();
            if (lastTodayKm >= 0) totalKm += tk >= lastTodayKm ? tk - lastTodayKm : tk;
            lastTodayKm = tk;
            // charged: battery % went up while standing still -> "Charge to charge" starts again
            boolean still = Double.isNaN(s.speedKmh) || s.speedKmh < 1;
            if (!Double.isNaN(s.socPct)) {
                if (!Double.isNaN(lastSoc) && still && s.socPct >= lastSoc + 1) { series[2].pts.clear(); chargeBaseKm = totalKm; }
                if (Double.isNaN(lastSoc) || !still || s.socPct < lastSoc || s.socPct >= lastSoc + 1) lastSoc = s.socPct;
            }
            // a new trip starts the "Trip" line again (2026-11-21: only a real new trip; the trip distance is sometimes
            // corrected a little downwards, and that used to wipe the line, leaving just a dot)
            if (te.startedAt != tripStart) { if (tripStart != 0 || te.km < lastTripKm - 1) series[0].pts.clear(); tripStart = te.startedAt; }
            lastTripKm = te.km;
            if (Double.isNaN(nedc) && Double.isNaN(wltp) && Double.isNaN(my)) return;
            series[0].add(new double[]{te.km, nedc, wltp, my});
            series[1].add(new double[]{tk, nedc, wltp, my});
            series[2].add(new double[]{totalKm - chargeBaseKm, nedc, wltp, my});
            if (s.at - savedAt > 30_000) {
                savedAt = s.at;
                store.edit().putString("day", day).putString("trip", series[0].save()).putLong("tripKm", Double.doubleToLongBits(lastTripKm)).putLong("tripStart", tripStart)
                        .putString("today", series[1].save()).putString("charge", series[2].save())
                        .putLong("total", Double.doubleToLongBits(totalKm)).putLong("chargeBase", Double.doubleToLongBits(chargeBaseKm)).apply();
            }
        }

        synchronized List<double[]> copy(int mode) { return new ArrayList<>(series[mode].pts); }
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
            List<double[]> pts = rec.copy(mode);
            float w = getWidth(), h = getHeight();
            // the values sit in their own column right of the lines, so they never cover them (user, 2026-11-21)
            text.setTextSize(13.5f * d);
            text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            float labW = text.measureText("888 km");
            float left = 32 * d, right = w - labW - 10 * d, top = 6 * d, bottom = h - 14 * d;
            // across: from this series' start, at least 10 km (user, 2026-11-21: was 20)
            double x0 = 0;   // always from 0 km: the start of the trip, the day or the charge (user, 2026-11-21)
            double xEnd = pts.isEmpty() ? 0 : pts.get(pts.size() - 1)[0];
            double xSpan = Math.max(10, xEnd);
            // up: every value fits; at least 200 km tall, centred on the lines
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (double[] p : pts) for (int k = 0; k < 4; k++) { double v = val(p, k); if (!Double.isNaN(v)) { lo = Math.min(lo, v); hi = Math.max(hi, v); } }
            if (lo == Double.MAX_VALUE) { lo = 150; hi = 350; }
            double span = Math.max(200, (hi - lo) * 1.15);
            double mid = (hi + lo) / 2;
            double yMin = Math.floor((mid - span / 2) / 10) * 10, yMax = yMin + Math.ceil(span / 10) * 10;
            text.setTextSize(11f * d);   // axis numbers bigger (user, 2026-11-21)
            text.setColor(th.muted);
            text.setTypeface(android.graphics.Typeface.DEFAULT);
            for (int g = 0; g <= 2; g++) {
                float y = top + (bottom - top) * g / 2f;
                cv.drawLine(left, y, right, y, grid);
                cv.drawText(String.valueOf(Math.round(yMax - (yMax - yMin) * g / 2.0)), 0, y + 4 * d, text);
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
            int[] colors = {NEDC, WLTP, EPA, MY};
            float[] endX = new float[4], endY = new float[4];
            double[] endV = new double[4];
            for (int k = 0; k < 4; k++) {
                path.reset();
                boolean started = false;
                endV[k] = Double.NaN;
                for (double[] p : pts) {
                    double v = val(p, k);
                    if (Double.isNaN(v)) continue;
                    float x = (float) (left + (right - left) * (p[0] - x0) / xSpan);
                    float y = (float) (bottom - (bottom - top) * (v - yMin) / (yMax - yMin));
                    if (!started) { path.moveTo(x, y); started = true; } else path.lineTo(x, y);
                    endX[k] = x; endY[k] = y; endV[k] = v;
                }
                if (!started) continue;
                line.setColor(colors[k]);
                line.setStrokeWidth((k == 3 ? 2.6f : 2f) * d);
                cv.drawPath(path, line);
            }
            // dots and values at the line ends; labels kept apart so they never sit on top of each other
            text.setTextSize(13.5f * d);   // line-end values bigger (user, 2026-11-21)
            text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            Integer[] order = {0, 1, 2, 3};
            java.util.Arrays.sort(order, (a, b) -> Float.compare(endY[a], endY[b]));
            float[] ly = new float[4];
            float prev = -1e9f;
            for (int i : order) {
                if (Double.isNaN(endV[i])) continue;
                ly[i] = Math.max(Math.max(endY[i] + 5f * d, top + 10 * d), prev + 13.5f * d);   // four values now: a little tighter
                prev = ly[i];
            }
            float over = prev - (bottom + 4 * d);   // pushed past the bottom: move the whole column up
            for (int i = 0; i < 4; i++) {
                if (Double.isNaN(endV[i])) continue;
                if (over > 0) ly[i] -= over;
                Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
                dot.setColor(colors[i]);
                cv.drawCircle(endX[i], endY[i], (i == 3 ? 3.4f : 2.8f) * d, dot);
                text.setColor(colors[i]);
                cv.drawText(Math.round(endV[i]) + " km", right + 8 * d, ly[i], text);
            }
        }
    }
}
