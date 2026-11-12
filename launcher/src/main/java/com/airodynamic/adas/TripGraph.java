package com.airodynamic.adas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;

import java.util.List;

/**
 * Bottom graph across the screen: the last 20 km of this trip (user's spec, 2026-11-11).
 * Blue = speed (scale 0–80 km/h, grows when faster), orange = power in kW (+ using, − regen;
 * scale −30 to +50, grows when beyond), green shading = regen (pedal line removed 2026-11-11).
 * Each point is the average over 100 m (no extra smoothing); the newest point is the
 * real-time reading (about 3 times a second) and blinks. Scales go back to their starting size
 * once the big values have scrolled out of the last 20 km.
 */
public final class TripGraph extends View implements TripEnergy.Listener {
    private final Theme th;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private List<TripEnergy.Point> pts;
    private boolean liveRegen;
    private int liveBrake;
    private double liveFromKm;
    private float liveX0, liveX1;
    private static final int REGEN_STAMP = 0xFF2EE59D, MILD_STAMP = 0xFFFF9A1F, HARD_STAMP = 0xFFFF3B30;
    private double nowKm, liveSpeed = Double.NaN, livePower = Double.NaN, livePedal = Double.NaN;
    private static final int POWER_COL = 0xFFFFB020, PEDAL_COL = 0xFFB8A4FF, REGEN_COL = 0x553DD9C1;
   // average with 2 points each side

    public TripGraph(Context c, Theme th) { super(c); this.th = th; }

    @Override public void onTrip(TripEnergy t) {
        pts = t.points();
        nowKm = t.km;
        liveSpeed = t.liveSpeed;
        livePower = t.powerKw;
        livePedal = t.livePedal;
        liveRegen = t.liveRegen();
        liveBrake = t.liveBrake();
        liveFromKm = t.lastPointKm();
        postInvalidate();
    }

    private static double val(TripEnergy.Point q, int which) {
        return which == 0 ? q.speed : which == 1 ? q.power : q.pedal;
    }

    /** Speed line in the theme's card colour, bright enough to see: blue / red / black (user, 2026-11-12). */
    private int speedCol() { return th == Theme.RED ? 0xFFC8202E : th == Theme.WHITE ? 0xFF000000 : 0xFF3A78E8; }

    /** Moving average set in Settings, in metres (0 = real time). */
    private int avgM(int which) {
        Prefs pr = new Prefs(getContext());
        return which == 0 ? pr.smoothSpeed() : which == 1 ? pr.smoothPower() : 100;
    }

    /** Value of point i averaged over the last N metres (Settings; each point is 100 m), ignoring missing ones. */
    private double smooth(int i, int which) {
        double sum = 0;
        int n = 0;
        int back = Math.max(1, avgM(which) / 100) - 1;
        int from = i - back, to = i;
        for (int k = Math.max(0, from); k <= Math.min(pts.size() - 1, to); k++) {
            double v = val(pts.get(k), which);
            if (!Double.isNaN(v)) { sum += v; n++; }
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    /** GPS height of point i averaged with its neighbours (300 m), to calm GPS wobble. */
    private double altSmooth(int i) {
        double sum = 0; int k = 0;
        for (int j = Math.max(0, i - 1); j <= Math.min(pts.size() - 1, i + 1); j++) {
            double a = pts.get(j).alt;
            if (!Double.isNaN(a)) { sum += a; k++; }
        }
        return k == 0 ? Double.NaN : sum / k;
    }

    private double live(int which) { return which == 0 ? liveSpeed : which == 1 ? livePower : livePedal; }

    @Override protected void onDraw(Canvas c) {
        float d = getResources().getDisplayMetrics().density;
        float w = getWidth(), h = getHeight();
        float left = 46 * d, right = w - 46 * d, top = 22 * d, bottom = h - 16 * d - 20 * d;   // 2 stamp rows below the lines
        float regenY = bottom + 4 * d, brakeY = bottom + 12 * d, rowH = 6 * d;
        double from = Math.max(0, nowKm - TripEnergy.WINDOW_KM), to = from + TripEnergy.WINDOW_KM;
        liveX0 = X(Math.max(from, liveFromKm), from, to, left, right);
        liveX1 = X(nowKm, from, to, left, right);

        // scales: starting size, grown to fit what is on screen (and the live reading)
        double sMax = 80, eMin = -10, eMax = 30;   // power: −10…30 kW so the line isn't flat (user, 2026-11-12)
        int n = pts == null ? 0 : pts.size();
        for (int i = 0; i < n; i++) {
            if (pts.get(i).km < from) continue;
            double sv = smooth(i, 0), ev = smooth(i, 1);
            if (!Double.isNaN(sv)) sMax = Math.max(sMax, Math.ceil(sv / 20) * 20);
            if (!Double.isNaN(ev)) { eMin = Math.min(eMin, Math.floor(ev / 10) * 10); eMax = Math.max(eMax, Math.ceil(ev / 10) * 10); }
        }
        if (!Double.isNaN(liveSpeed)) sMax = Math.max(sMax, Math.ceil(liveSpeed / 20) * 20);
        double hLo = Double.MAX_VALUE, hHi = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            if (pts.get(i).km < from) continue;
            double a = altSmooth(i);
            if (!Double.isNaN(a)) { hLo = Math.min(hLo, a); hHi = Math.max(hHi, a); }
        }
        if (!Double.isNaN(livePower)) { eMin = Math.min(eMin, Math.floor(livePower / 10) * 10); eMax = Math.max(eMax, Math.ceil(livePower / 10) * 10); }

        p.setTextSize(11 * d);
        p.setStyle(Paint.Style.FILL);
        float lx = left;
        int ms = avgM(0), mp = avgM(1);
        lx = legend(c, lx, 13 * d, speedCol(), "Speed km/h (" + (ms == 0 ? "real time" : ms + " m") + ")", d);
        lx = legend(c, lx, 13 * d, POWER_COL, "Power kW (" + (mp == 0 ? "real time" : mp + " m") + ")", d);
        if (hLo <= hHi) lx = legend(c, lx, 13 * d, 0xFF7FA6C9, String.format(java.util.Locale.US, "Height ↕ %.0f m", hHi - hLo), d);
        lx = legend(c, lx, 13 * d, REGEN_STAMP, "Regen", d);
        lx = legend(c, lx, 13 * d, MILD_STAMP, "mild brake", d);
        lx = legend(c, lx, 13 * d, HARD_STAMP, "hard brake", d);
        p.setColor(th.muted);
        c.drawText("· stamps are live", lx, 13 * d + 4 * d, p);

        // grid + axis labels (speed left, kW right)
        p.setStrokeWidth(1 * d);
        for (int i = 0; i <= 4; i++) {
            float y = top + (bottom - top) * i / 4f;
            p.setColor(th.line);
            c.drawLine(left, y, right, y, p);
            p.setColor(speedCol());
            p.setTextAlign(Paint.Align.RIGHT);
            c.drawText(String.valueOf(Math.round(sMax * (4 - i) / 4)), left - 5 * d, y + 4 * d, p);
            p.setColor(POWER_COL);
            p.setTextAlign(Paint.Align.LEFT);
            c.drawText(String.valueOf(Math.round(eMin + (eMax - eMin) * (4 - i) / 4)), right + 5 * d, y + 4 * d, p);
        }
        float y0 = (float) (bottom - (0 - eMin) / (eMax - eMin) * (bottom - top));
        p.setColor(th.muted);
        c.drawLine(left, y0, right, y0, p);   // 0 kW
        // distance axis: trip km (0–20 at first; after 20 km the window pans and the numbers move);
        // "now" sits under the live point
        p.setTextAlign(Paint.Align.CENTER);
        float nowX = X(nowKm, from, to, left, right);
        for (int km = (int) Math.ceil(from / 5.0) * 5; km <= to + 0.001; km += 5) {
            float x = X(km, from, to, left, right);
            if (Math.abs(x - nowX) < 34 * d) continue;   // keep "now" readable
            c.drawText(km + " km", x, h - 3 * d, p);
        }
        p.setColor(th.text);
        c.drawText("now", Math.max(left + 12 * d, Math.min(right, nowX)), h - 3 * d, p);
        p.setTextAlign(Paint.Align.LEFT);

        if (n == 0 && Double.isNaN(liveSpeed)) {
            p.setColor(th.muted);
            p.setTextSize(13 * d);
            c.drawText("The graph starts when you drive", left + 12 * d, (top + bottom) / 2, p);
            return;
        }

        // height (GPS): faint hill shape behind the lines. Fitted to what is on screen, at least 10 m
        // from bottom to top so even Bangkok's small rises show (user, 2026-11-11)
        if (hLo <= hHi) {
            double span = Math.max(10, hHi - hLo), mid = (hLo + hHi) / 2, lo = mid - span / 2;
            float hb = bottom, ht = top + (bottom - top) * .1f;
            android.graphics.Path hp = new android.graphics.Path(), ridge = new android.graphics.Path();
            boolean started = false; float firstX = 0, lastX = 0;
            for (int i = 0; i < n; i++) {
                TripEnergy.Point q = pts.get(i);
                if (q.km < from) continue;
                double a = altSmooth(i);
                if (Double.isNaN(a)) continue;
                float x = X(q.km, from, to, left, right), y = (float) (hb - (a - lo) / span * (hb - ht));
                if (!started) { hp.moveTo(x, hb); hp.lineTo(x, y); ridge.moveTo(x, y); firstX = x; started = true; }
                else { hp.lineTo(x, y); ridge.lineTo(x, y); }
                lastX = x;
            }
            if (started) {
                hp.lineTo(lastX, hb); hp.close();
                p.setStyle(Paint.Style.FILL); p.setColor(0x297FA6C9);
                c.drawPath(hp, p);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.4f * d); p.setColor(0x8C7FA6C9);
                c.drawPath(ridge, p);
                p.setStyle(Paint.Style.FILL);
            }
        }

        line(c, 1, from, to, eMin, eMax, left, right, top, bottom, POWER_COL, 2.2f * d, 5 * d);
        line(c, 0, from, to, 0, sMax, left, right, top, bottom, speedCol(), 3.75f * d, 6 * d);   // 1.5x thicker
        // stamp rows (live): regen green; braking orange (mild ≥0.15 g) / red (hard ≥0.4 g)
        p.setTextSize(9 * d);
        p.setTextAlign(Paint.Align.RIGHT);
        p.setColor(REGEN_STAMP); c.drawText("regen", left - 5 * d, regenY + rowH, p);
        p.setColor(MILD_STAMP); c.drawText("brake", left - 5 * d, brakeY + rowH, p);
        p.setTextAlign(Paint.Align.LEFT);
        float stepPx = (float) (TripEnergy.STEP_KM / TripEnergy.WINDOW_KM * (right - left));
        for (int i = 0; i < n; i++) {
            TripEnergy.Point q = pts.get(i);
            if (q.km < from) continue;
            float x = X(q.km, from, to, left, right);
            if (q.regen) { p.setColor(REGEN_STAMP); c.drawRect(x - stepPx, regenY, x, regenY + rowH, p); }
            int bl = q.decelG >= .4 ? 2 : q.decelG >= .15 ? 1 : 0;
            if (bl > 0) { p.setColor(bl == 2 ? HARD_STAMP : MILD_STAMP); c.drawRect(x - stepPx, brakeY, x, brakeY + rowH, p); }
        }
        {   // the 100 m being driven right now: stamps appear at once
            float x1 = liveX1, x0 = Math.min(liveX0, x1 - 3 * d);
            if (liveRegen) { p.setColor(REGEN_STAMP); c.drawRect(x0, regenY, x1, regenY + rowH, p); }
            if (liveBrake > 0) { p.setColor(liveBrake == 2 ? HARD_STAMP : MILD_STAMP); c.drawRect(x0, brakeY, x1, brakeY + rowH, p); }
        }
        removeCallbacks(blink);
        postDelayed(blink, 500);
    }

    private static float X(double km, double from, double to, float l, float r) { return (float) (l + (km - from) / (to - from) * (r - l)); }
    private static float Y(double v, double lo, double hi, float t, float b) { return (float) (b - (v - lo) / (hi - lo) * (b - t)); }

    private float legend(Canvas c, float x, float y, int col, String s, float d) {
        p.setColor(col);
        p.setStrokeWidth(3 * d);
        p.setStyle(Paint.Style.STROKE);
        c.drawLine(x, y, x + 16 * d, y, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(th.muted);
        c.drawText(s, x + 21 * d, y + 4 * d, p);
        return x + 21 * d + p.measureText(s) + 14 * d;
    }

    /** Smoothed line through the stored points, then on to the real-time point (which blinks). */
    private void line(Canvas c, int which, double from, double to, double lo, double hi,
                      float l, float r, float t, float b, int col, float width, float dot) {
        path.reset();
        boolean started = false;
        int n = pts == null ? 0 : pts.size();
        if (which < 2 && avgM(which) == 0) {   // real time: every 10 m sample as it came
            for (double[] f : TripEnergy.get(getContext()).fine()) {
                if (f[0] < from) continue;
                double v = f[1 + which];
                if (Double.isNaN(v)) { started = false; continue; }
                float x = X(f[0], from, to, l, r), y = Y(Math.max(lo, Math.min(hi, v)), lo, hi, t, b);
                if (!started) { path.moveTo(x, y); started = true; } else path.lineTo(x, y);
            }
            n = 0;
        }
        for (int i = 0; i < n; i++) {
            TripEnergy.Point q = pts.get(i);
            if (q.km < from) continue;
            double v = smooth(i, which);
            if (Double.isNaN(v)) { started = false; continue; }
            float x = X(q.km, from, to, l, r), y = Y(v, lo, hi, t, b);
            if (!started) { path.moveTo(x, y); started = true; } else path.lineTo(x, y);
        }
        double lv = live(which);
        float lastX = Float.NaN, lastY = Float.NaN;
        if (!Double.isNaN(lv)) {
            lastX = X(nowKm, from, to, l, r);
            lastY = Y(Math.max(lo, Math.min(hi, lv)), lo, hi, t, b);
            if (started) path.lineTo(lastX, lastY); else path.moveTo(lastX, lastY);
        }
        p.setStyle(Paint.Style.STROKE);
        p.setColor(col);
        p.setStrokeWidth(width);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeCap(Paint.Cap.ROUND);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
        if (!Float.isNaN(lastX)) {
            boolean on = (SystemClock.uptimeMillis() / 500) % 2 == 0;
            p.setColor(col);
            p.setAlpha(on ? 255 : 70);
            c.drawCircle(lastX, lastY, on ? dot : dot * 0.7f, p);
            p.setAlpha(on ? 90 : 0);
            c.drawCircle(lastX, lastY, dot * 2f, p);
            p.setAlpha(255);
        }
    }

    private final Runnable blink = this::invalidate;

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); TripEnergy.get(getContext()).addListener(this); }
    @Override protected void onDetachedFromWindow() { removeCallbacks(blink); TripEnergy.get(getContext()).removeListener(this); super.onDetachedFromWindow(); }
}
