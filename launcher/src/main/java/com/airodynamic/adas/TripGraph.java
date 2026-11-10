package com.airodynamic.adas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.List;
import java.util.Locale;

/**
 * Bottom graph across the screen: the last 20 km of this trip.
 * Blue = speed (scale 0–80 km/h, grows when faster), orange = live kWh/100 km over each 100 m
 * (scale -10 to 40, grows when beyond; the trip average is on the energy card, not here),
 * green shading where it goes below zero (regen), thin purple = accelerator pedal 0–100 %. Scales go back to their
 * starting size once the big values have scrolled out of the last 20 km.
 */
public final class TripGraph extends View implements TripEnergy.Listener {
    private final Theme th;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private List<TripEnergy.Point> pts;
    private double nowKm;
    private static final int SPEED_COL = 0xFF4DA3FF, ENERGY_COL = 0xFFFFB020, PEDAL_COL = 0xFFB8A4FF, REGEN_COL = 0x553DD9C1;

    public TripGraph(Context c, Theme th) { super(c); this.th = th; }

    @Override public void onTrip(TripEnergy t) {
        pts = t.points();
        nowKm = t.km;
        postInvalidate();
    }

    @Override protected void onDraw(Canvas c) {
        float d = getResources().getDisplayMetrics().density;
        float w = getWidth(), h = getHeight();
        float left = 46 * d, right = w - 46 * d, top = 22 * d, bottom = h - 16 * d;
        double from = Math.max(0, nowKm - TripEnergy.WINDOW_KM), to = from + TripEnergy.WINDOW_KM;

        // scales: start size, grown to fit what is on screen
        double sMax = 80, eMin = -10, eMax = 40;
        if (pts != null) for (TripEnergy.Point q : pts) {
            if (q.km < from) continue;
            sMax = Math.max(sMax, Math.ceil(q.speed / 20) * 20);
            for (double e : new double[]{q.live}) {
                if (Double.isNaN(e) || Double.isInfinite(e)) continue;
                eMin = Math.min(eMin, Math.floor(e / 10) * 10);
                eMax = Math.max(eMax, Math.ceil(e / 10) * 10);
            }
        }

        p.setTextSize(11 * d);
        p.setStyle(Paint.Style.FILL);
        // legend
        float lx = left;
        lx = legend(c, lx, 13 * d, SPEED_COL, false, "Speed km/h", d);
        lx = legend(c, lx, 13 * d, ENERGY_COL, false, "Live kWh/100 km", d);
        lx = legend(c, lx, 13 * d, PEDAL_COL, false, "Pedal 0–100%", d);
        lx = legend(c, lx, 13 * d, 0xFF3DD9C1, false, "Regen", d);
        p.setColor(th.muted);
        c.drawText("· last 20 km", lx, 13 * d + 4 * d, p);

        // grid + axis labels
        p.setStrokeWidth(1 * d);
        for (int i = 0; i <= 4; i++) {
            float y = top + (bottom - top) * i / 4f;
            p.setColor(th.line);
            c.drawLine(left, y, right, y, p);
            p.setColor(SPEED_COL);
            p.setTextAlign(Paint.Align.RIGHT);
            c.drawText(String.valueOf(Math.round(sMax * (4 - i) / 4)), left - 5 * d, y + 4 * d, p);
            p.setColor(ENERGY_COL);
            p.setTextAlign(Paint.Align.LEFT);
            c.drawText(String.valueOf(Math.round(eMin + (eMax - eMin) * (4 - i) / 4)), right + 5 * d, y + 4 * d, p);
        }
        // zero line for energy (regen below it)
        if (eMin < 0) {
            float y0 = (float) (bottom - (0 - eMin) / (eMax - eMin) * (bottom - top));
            p.setColor(th.muted);
            c.drawLine(left, y0, right, y0, p);
        }
        p.setColor(th.muted);
        p.setTextAlign(Paint.Align.CENTER);
        for (int k = 0; k <= 4; k++) {
            float x = left + (right - left) * k / 4f;
            c.drawText(k == 4 ? "now" : "−" + (20 - k * 5) + " km", x, h - 3 * d, p);
        }
        p.setTextAlign(Paint.Align.LEFT);

        if (pts == null || pts.isEmpty()) {
            p.setColor(th.muted);
            p.setTextSize(13 * d);
            c.drawText("The graph starts after the first 100 m of driving", left + 12 * d, (top + bottom) / 2, p);
            return;
        }
        // regen: green shading between the zero line and the orange line where it goes below zero
        float y0 = (float) (bottom - (0 - eMin) / (eMax - eMin) * (bottom - top));
        float stepPx = (float) (TripEnergy.STEP_KM / TripEnergy.WINDOW_KM * (right - left));
        p.setColor(REGEN_COL);
        for (TripEnergy.Point q : pts) {
            if (q.km < from || !(q.live < 0)) continue;
            float x = (float) (left + (q.km - from) / (to - from) * (right - left));
            float y = (float) (bottom - (q.live - eMin) / (eMax - eMin) * (bottom - top));
            c.drawRect(x - stepPx, y0, x, y, p);
        }
        line(c, 3, from, to, 0, 100, left, right, top, bottom, PEDAL_COL, false, 1.5f * d, 3.5f * d);
        line(c, 0, from, to, 0, sMax, left, right, top, bottom, SPEED_COL, false, 2.5f * d, 5 * d);
        line(c, 1, from, to, eMin, eMax, left, right, top, bottom, ENERGY_COL, false, 2.2f * d, 5 * d);
        // newest points blink (twice a second)
        removeCallbacks(blink);
        postDelayed(blink, 500);
    }

    private float legend(Canvas c, float x, float y, int col, boolean dashed, String s, float d) {
        p.setColor(col);
        p.setStrokeWidth(3 * d);
        p.setPathEffect(dashed ? new DashPathEffect(new float[]{5 * d, 4 * d}, 0) : null);
        p.setStyle(Paint.Style.STROKE);
        c.drawLine(x, y, x + 16 * d, y, p);
        p.setPathEffect(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(th.muted);
        c.drawText(s, x + 21 * d, y + 4 * d, p);
        return x + 21 * d + p.measureText(s) + 16 * d;
    }

    private void line(Canvas c, int which, double from, double to, double lo, double hi,
                      float l, float r, float t, float b, int col, boolean dashed, float width, float dot) {
        float lastX = Float.NaN, lastY = Float.NaN;
        path.reset();
        boolean started = false;
        for (TripEnergy.Point q : pts) {
            if (q.km < from) continue;
            double v = which == 0 ? q.speed : which == 1 ? q.live : which == 3 ? q.pedal : q.avg;
            if (Double.isNaN(v) || Double.isInfinite(v)) { started = false; continue; }
            float x = (float) (l + (q.km - from) / (to - from) * (r - l));
            float y = (float) (b - (v - lo) / (hi - lo) * (b - t));
            if (!started) { path.moveTo(x, y); started = true; } else path.lineTo(x, y);
            lastX = x; lastY = y;
        }
        p.setStyle(Paint.Style.STROKE);
        p.setColor(col);
        p.setStrokeWidth(width);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setPathEffect(dashed ? new DashPathEffect(new float[]{8, 6}, 0) : null);
        c.drawPath(path, p);
        p.setPathEffect(null);
        p.setStyle(Paint.Style.FILL);
        if (!Float.isNaN(lastX)) {
            boolean on = (android.os.SystemClock.uptimeMillis() / 500) % 2 == 0;
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
