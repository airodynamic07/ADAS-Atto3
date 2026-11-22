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
 * Range graph in the right window (user, 2026-11-21; redesigned 2026-11-22). For this trip, today or charge to charge:
 * NEDC (grey) = 480 km, WLTP (blue) = 420 km, EPA (purple) = WLTP − 12 %, each × battery health × the battery % at the
 * start, then straight down 1 km per km driven (parallel; your driving never changes them). My range (orange) = 100 %
 * your own driving: battery left ÷ your kWh/100 km over the last 10 km; it starts on the WLTP point, slides into your own
 * range over 5 km, and is smoothed over ~2 km (no jitter). This trip shows the last 50 km, zoomed, sliding right and
 * down; Today and Charge to charge show everything from 0, with where each line reaches 0 ahead. Read only.
 */
final class RangeGraph extends LinearLayout {
    static final int NEDC = 0xFF9AA0A8, WLTP = 0xFF3AA0FF, EPA = 0xFFB388FF, MY = 0xFFFF8A1F;
    /** EPA (US test): no official Atto 3 figure, estimated as WLTP − 12 % (user, 2026-11-21). */
    static final double EPA_OF_WLTP = 0.88;

    private static final String[] MODES = {"Trip", "Today", "C to C"};   // short on the button (it didn't fit); the menu keeps the full names
    private static final String[] MENU = {"This trip", "Today", "Charge to charge"};
    private static final double STEP_KM = 0.1;
    private static final int MAX_POINTS = 900;
    static final double NEDC_KM = 480, WLTP_KM = 420;

    /** One view (trip / today / charge): where it started, and My range points {km, my range}. */
    private static final class Series {
        final List<double[]> pts = new ArrayList<>();
        double soc0 = Double.NaN, health = 100, xRef;   // battery % and health at the start, and the km it was taken at
        double sm = Double.NaN, lastX = Double.NaN;      // smoothed My range

        void clear() { pts.clear(); soc0 = Double.NaN; sm = Double.NaN; lastX = Double.NaN; }

        /** the three straight lines: k 0 NEDC, 1 WLTP, 2 EPA, at km x (never below 0) */
        double ref(int k, double x) {
            if (Double.isNaN(soc0)) return Double.NaN;
            double f = health / 100 * soc0 / 100;
            double start = k == 0 ? NEDC_KM * f : k == 1 ? WLTP_KM * f : WLTP_KM * f * EPA_OF_WLTP;
            return Math.max(0, start - (x - xRef));
        }

        void add(double x, double soc, double health, double own) {
            if (Double.isNaN(soc0) && !Double.isNaN(soc)) { soc0 = soc; this.health = health; xRef = x; }
            if (Double.isNaN(soc0)) return;
            if (!pts.isEmpty() && x < pts.get(pts.size() - 1)[0]) x = pts.get(pts.size() - 1)[0];   // distance never goes back
            double w = Math.max(0, x - xRef);
            double wl = ref(1, x);
            // starts on the WLTP point, slides into your own range over the first 5 km
            double target = Double.isNaN(own) ? wl : (1 - Math.min(1, w / 5)) * wl + Math.min(1, w / 5) * own;
            if (Double.isNaN(sm)) sm = wl;
            else if (x > lastX) sm += (target - sm) * Math.min(1, (x - lastX) / 2.0);   // smoothed over ~2 km: no jitter
            lastX = x;
            double[] p = {x, sm};
            int n = pts.size();
            if (n < 2 || x - pts.get(n - 2)[0] >= STEP_KM) pts.add(p); else pts.set(n - 1, p);
            if (pts.size() > MAX_POINTS) {
                List<double[]> half = new ArrayList<>();
                for (int i = 0; i < pts.size(); i++) if (i % 2 == 0 || i == pts.size() - 1) half.add(pts.get(i));
                pts.clear(); pts.addAll(half);
            }
        }

        String save() {
            StringBuilder b = new StringBuilder(String.format(Locale.US, "%.2f,%.2f,%.3f,%.2f,%.3f|", soc0, health, xRef, sm, lastX));
            for (double[] p : pts) b.append(String.format(Locale.US, "%.2f,%.1f;", p[0], p[1]));
            return b.toString();
        }

        void load(String s) {
            clear();
            if (s == null || !s.contains("|")) return;
            try {
                String[] h = s.substring(0, s.indexOf('|')).split(",");
                soc0 = Double.parseDouble(h[0]); health = Double.parseDouble(h[1]); xRef = Double.parseDouble(h[2]);
                sm = Double.parseDouble(h[3]); lastX = Double.parseDouble(h[4]);
                for (String part : s.substring(s.indexOf('|') + 1).split(";")) {
                    String[] v = part.split(",");
                    if (v.length == 2) pts.add(new double[]{Double.parseDouble(v[0]), Double.parseDouble(v[1])});
                }
            } catch (Exception e) { clear(); }
        }

        Series copy() {
            Series c = new Series();
            c.pts.addAll(pts); c.soc0 = soc0; c.health = health; c.xRef = xRef; c.sm = sm; c.lastX = lastX;
            return c;
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
            // new format (2026-11-22): fresh start for all three views
            series[0].load(store.getString("trip2", ""));
            lastTripKm = Double.longBitsToDouble(store.getLong("tripKm", Double.doubleToLongBits(-1)));
            tripStart = store.getLong("tripStart", 0);
            series[1].load(store.getString("today2", ""));
            series[2].load(store.getString("charge2", ""));
            totalKm = Double.longBitsToDouble(store.getLong("total", 0));
            chargeBaseKm = Double.longBitsToDouble(store.getLong("chargeBase", 0));
        }

        /** {NEDC (the car's display), WLTP (× 420/480), My range (100 % your own driving)}; NaN when unknown. */
        static double[] ranges(Context c, CarData.State s) {
            double nedc = s.rangeKm >= 0 ? s.rangeKm : Double.NaN;
            double wltp = !Double.isNaN(nedc) ? nedc * WLTP_KM / NEDC_KM : Double.NaN;
            return new double[]{nedc, wltp, ownRange(c, s)};
        }

        /** battery left ÷ your kWh/100 km over the last 10 km (else 50 km, else the car's own figure) × 100 */
        static double ownRange(Context c, CarData.State s) {
            double left = !Double.isNaN(s.battLeftKwh) ? s.battLeftKwh : !Double.isNaN(s.socPct) ? s.socPct / 100 * 60.48 * (Double.isNaN(s.healthPct) ? 1 : s.healthPct / 100.0) : Double.NaN;
            TripEnergy te = TripEnergy.get(c);
            double mine = te.myAvg10();
            if (Double.isNaN(mine)) mine = te.myAvg50();
            if (Double.isNaN(mine)) mine = s.last50;
            return !Double.isNaN(left) && !Double.isNaN(mine) && mine > 3 ? left / mine * 100 : Double.NaN;
        }

        @Override public synchronized void onCar(CarData.State s) {
            TripEnergy te = TripEnergy.get(app);
            double own = ownRange(app, s);
            double health = Double.isNaN(s.healthPct) ? 100 : s.healthPct;   // the car's own battery health
            String today = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date());
            if (!today.equals(day)) { day = today; series[1].clear(); lastTodayKm = -1; }
            double tk = te.todayKm();
            if (lastTodayKm >= 0) totalKm += tk >= lastTodayKm ? tk - lastTodayKm : tk;
            lastTodayKm = tk;
            // charged: battery % went up while parked in P -> "Charge to charge" starts again (from the % after charging)
            boolean still = (Double.isNaN(s.speedKmh) || s.speedKmh < 1) && s.gear >= 0 && s.gear < 2;
            if (!Double.isNaN(s.socPct)) {
                if (!Double.isNaN(lastSoc) && still && s.socPct >= lastSoc + 1) { series[2].clear(); chargeBaseKm = totalKm; }
                if (Double.isNaN(lastSoc) || !still || s.socPct < lastSoc || s.socPct >= lastSoc + 1) lastSoc = s.socPct;
            }
            // a real new trip (car turned off, or Reset) starts the trip view again
            if (te.startedAt != tripStart) { if (tripStart != 0 || te.km < lastTripKm - 1) series[0].clear(); tripStart = te.startedAt; }
            lastTripKm = te.km;
            series[0].add(te.km, s.socPct, health, own);
            series[1].add(tk, s.socPct, health, own);
            series[2].add(totalKm - chargeBaseKm, s.socPct, health, own);
            if (s.at - savedAt > 30_000) {
                savedAt = s.at;
                store.edit().putString("day", day).putString("trip2", series[0].save()).putLong("tripKm", Double.doubleToLongBits(lastTripKm)).putLong("tripStart", tripStart)
                        .putString("today2", series[1].save()).putString("charge2", series[2].save())
                        .putLong("total", Double.doubleToLongBits(totalKm)).putLong("chargeBase", Double.doubleToLongBits(chargeBaseKm)).apply();
            }
        }

        synchronized Series copy(int mode) { return series[mode].copy(); }
    }

    /** The drawing: grid, the three straight lines, My range as a smooth curve, a dot and the km of each at your position. */
    private final class Plot extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), grid = new Paint(), text = new Paint(Paint.ANTI_ALIAS_FLAG), dash = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final float d;
        private double curLo = Double.NaN, curHi = Double.NaN;   // the height shown, gliding towards the wanted one
        private int curMode = -1;

        Plot(Context c) {
            super(c);
            d = getResources().getDisplayMetrics().density;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
            dash.setStyle(Paint.Style.STROKE);
            dash.setPathEffect(new android.graphics.DashPathEffect(new float[]{6 * d, 6 * d}, 0));
            grid.setColor((th.line & 0x00FFFFFF) | 0x80000000);
            grid.setStrokeWidth(d);
        }

        @Override protected void onDraw(Canvas cv) {
            Series sr = rec.copy(mode);
            List<double[]> pts = sr.pts;
            float w = getWidth(), h = getHeight();
            text.setTextSize(13.5f * d);
            text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            float labW = text.measureText("888 km");
            float left = 32 * d, right = w - labW - 10 * d, top = 6 * d, bottom = h - 14 * d;
            text.setTextSize(11f * d);
            text.setColor(th.muted);
            text.setTypeface(android.graphics.Typeface.DEFAULT);
            if (pts.isEmpty() || Double.isNaN(sr.soc0)) {
                String msg = "fills in as you drive";
                cv.drawText(msg, (left + right - text.measureText(msg)) / 2, (top + bottom) / 2, text);
                return;
            }
            double xc = pts.get(pts.size() - 1)[0];
            boolean trip = mode == 0;
            // across: this trip = the last 50 km (sliding); today / charge = from 0 to where NEDC reaches 0
            double xs = trip ? Math.max(sr.xRef, xc - 50) : sr.xRef;
            double xe = trip ? Math.max(xs + 10, xc) : Math.max(xs + 10, Math.max(xc, sr.xRef + sr.ref(0, sr.xRef)));
            // up: this trip = just around the lines in the window; today / charge = 0 to the top
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (int k = 0; k < 3; k++) for (double x : new double[]{xs, Math.min(xc, xe)}) { double v = sr.ref(k, x); lo = Math.min(lo, v); hi = Math.max(hi, v); }
            for (double[] p : pts) if (p[0] >= xs) { lo = Math.min(lo, p[1]); hi = Math.max(hi, p[1]); }
            if (trip) { double pad = Math.max(8, (hi - lo) * 0.12); lo = Math.max(0, lo - pad); hi += pad; }
            else { lo = 0; hi = hi * 1.06 + 5; }
            // glide smoothly to the new height (no jumps) as the window moves right and down
            if (curMode != mode || Double.isNaN(curLo)) { curLo = lo; curHi = hi; curMode = mode; }
            else {
                curLo += (lo - curLo) * 0.2; curHi += (hi - curHi) * 0.2;
                if (Math.abs(lo - curLo) > 0.5 || Math.abs(hi - curHi) > 0.5) postInvalidateDelayed(40);
            }
            final double yLo = curLo, yHi = Math.max(curLo + 10, curHi);
            final double fxs = xs, fxe = xe;
            java.util.function.DoubleUnaryOperator X = x -> left + (right - left) * (x - fxs) / (fxe - fxs);
            java.util.function.DoubleUnaryOperator Y = v -> bottom - (bottom - top) * (v - yLo) / (yHi - yLo);
            for (int g = 0; g <= 2; g++) {
                float y = top + (bottom - top) * g / 2f;
                cv.drawLine(left, y, right, y, grid);
                cv.drawText(String.valueOf(Math.round(yHi - (yHi - yLo) * g / 2.0)), 0, y + 4 * d, text);
            }
            for (int g = 0; g <= 2; g++) {
                double xv = xs + (xe - xs) * g / 2;
                float x = (float) X.applyAsDouble(xv);
                cv.drawLine(x, top, x, bottom, grid);
                String lab = String.format(Locale.US, "%.0f km", xv - sr.xRef);
                if (g == 0 && xv - sr.xRef < 0.5) lab = "0";
                float tw = text.measureText(lab);
                cv.drawText(lab, g == 0 ? x : g == 1 ? x - tw / 2 : x - tw, h - 1.5f * d, text);
            }
            cv.save();
            cv.clipRect(left, top - 4 * d, right + 2 * d, bottom + 4 * d);
            int[] colors = {NEDC, WLTP, EPA, MY};
            float[] endX = new float[4], endY = new float[4];
            double[] endV = new double[4];
            // the three straight lines: solid up to you; Today / charge also show (faint) where each reaches 0 ahead
            for (int k = 0; k < 3; k++) {
                double zeroX = sr.xRef + sr.ref(k, sr.xRef);
                double x1 = Math.min(xc, zeroX);
                line.setColor(colors[k]);
                line.setStrokeWidth(2f * d);
                cv.drawLine((float) X.applyAsDouble(xs), (float) Y.applyAsDouble(sr.ref(k, xs)), (float) X.applyAsDouble(x1), (float) Y.applyAsDouble(sr.ref(k, x1)), line);
                if (!trip && zeroX > xc) {
                    dash.setColor((colors[k] & 0x00FFFFFF) | 0x73000000);
                    dash.setStrokeWidth(1.6f * d);
                    cv.drawLine((float) X.applyAsDouble(xc), (float) Y.applyAsDouble(sr.ref(k, xc)), (float) X.applyAsDouble(zeroX), (float) Y.applyAsDouble(0), dash);
                }
                endX[k] = (float) X.applyAsDouble(xc); endY[k] = (float) Y.applyAsDouble(sr.ref(k, xc)); endV[k] = sr.ref(k, xc);
            }
            // My range: a smooth curve through its points
            path.reset();
            float px = 0, py = 0;
            boolean started = false;
            for (double[] p : pts) {
                if (p[0] < xs) continue;
                float x = (float) X.applyAsDouble(p[0]), y = (float) Y.applyAsDouble(p[1]);
                if (!started) { path.moveTo(x, y); started = true; }
                else path.quadTo(px, py, (px + x) / 2, (py + y) / 2);
                px = x; py = y;
            }
            if (started) path.lineTo(px, py);
            line.setColor(MY);
            line.setStrokeWidth(2.8f * d);
            cv.drawPath(path, line);
            double[] last = pts.get(pts.size() - 1);
            endX[3] = (float) X.applyAsDouble(last[0]); endY[3] = (float) Y.applyAsDouble(last[1]); endV[3] = last[1];
            // your position: a dashed vertical line (today / charge)
            if (!trip) {
                dash.setColor(0xFF3A6FD8); dash.setStrokeWidth(1.2f * d);
                cv.drawLine(endX[3], top, endX[3], bottom, dash);
            }
            cv.restore();
            // dots and the km of each line at your position, kept apart so they never sit on top of each other
            text.setTextSize(13.5f * d);
            text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            Integer[] order = {0, 1, 2, 3};
            java.util.Arrays.sort(order, (a, b) -> Float.compare(endY[a], endY[b]));
            float[] ly = new float[4];
            float prev = -1e9f;
            for (int i : order) { ly[i] = Math.max(Math.max(endY[i] + 5f * d, top + 10 * d), prev + 13.5f * d); prev = ly[i]; }
            float over = prev - (bottom + 4 * d);
            Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
            for (int i = 0; i < 4; i++) {
                if (over > 0) ly[i] -= over;
                dot.setColor(colors[i]);
                cv.drawCircle(endX[i], endY[i], (i == 3 ? 3.4f : 2.8f) * d, dot);
                text.setColor(colors[i]);
                cv.drawText(Math.round(endV[i]) + " km", Math.min(endX[i] + 8 * d, w - labW - 2 * d), ly[i], text);
            }
        }
    }
}
