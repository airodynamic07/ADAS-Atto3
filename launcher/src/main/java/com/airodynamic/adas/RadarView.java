package com.airodynamic.adas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.Collections;
import java.util.List;

/**
 * "Map" view: your car from above, drawn to scale and zoomed out so about 14 m ahead and
 * behind fit on screen, with the parking radar and the AI's icons (rough distances) around it.
 * Radar sensor order is not confirmed yet: 9 values, 150 = nothing near; shown front row
 * (first 4) and rear row (next 4) until a car test pins each sensor down.
 */
public final class RadarView extends View {
    private static final float VIEW_M = 14f;           // metres from the car's centre to the top/bottom edge
    private static final float CAR_W = 1.9f, CAR_L = 4.45f;
    private final Theme th;
    private int[] radar = new int[0];
    @SuppressWarnings("unchecked")
    private final List<Vision.Obj>[] objs = new List[]{Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList()};
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    public RadarView(Context c, Theme th) { super(c); this.th = th; }

    public void setRadar(int[] v) { radar = v; invalidate(); }

    /** AI objects per camera slice (CameraFeed.REAR/LEFT/RIGHT/FRONT). */
    public void setObjects(int slice, List<Vision.Obj> o) { objs[slice] = o; invalidate(); }

    private int color(int cm) {
        if (cm <= 0 || cm >= 150) return th.line;
        if (cm < 40) return Theme.DANGER;
        if (cm < 80) return Theme.CAUTION;
        return Theme.SAFE;
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float d = getResources().getDisplayMetrics().density;
        float ppm = Math.min(h / 2f / VIEW_M, w / 2f / 9f);   // pixels per metre
        float cx = w / 2, cy = h / 2;
        float cw = CAR_W * ppm, ch = CAR_L * ppm;
        float lane = 3.5f * ppm;

        // road: our lane plus one each side, dashed lane lines
        p.setStyle(Paint.Style.FILL);
        p.setColor(th.chip);
        c.drawRect(cx - lane * 1.5f, 0, cx + lane * 1.5f, h, p);
        p.setColor(th.line);
        p.setStrokeWidth(2 * d);
        for (float y = 0; y < h; y += 3 * ppm) {
            c.drawLine(cx - lane / 2, y, cx - lane / 2, y + 1.5f * ppm, p);
            c.drawLine(cx + lane / 2, y, cx + lane / 2, y + 1.5f * ppm, p);
        }
        // distance rings 5 m and 10 m
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1 * d);
        p.setTextSize(10 * d);
        for (int m : new int[]{5, 10}) {
            c.drawCircle(cx, cy, m * ppm, p);
        }
        p.setStyle(Paint.Style.FILL);
        c.drawText("5 m", cx + 5 * ppm * 0.71f + 3 * d, cy - 5 * ppm * 0.71f, p);
        c.drawText("10 m", cx + 10 * ppm * 0.71f + 3 * d, cy - 10 * ppm * 0.71f, p);

        // our car
        r.set(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2);
        p.setColor(th.text);
        c.drawRoundRect(r, cw * 0.28f, cw * 0.28f, p);
        p.setColor(th.chip);
        r.set(cx - cw * 0.34f, cy - ch * 0.30f, cx + cw * 0.34f, cy - ch * 0.12f);
        c.drawRoundRect(r, 3 * d, 3 * d, p);

        // radar arcs: front row above the car, rear row below
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4 * d);
        p.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < Math.min(8, radar.length); i++) {
            boolean front = i < 4;
            int k = i % 4;
            float x = cx - cw * 0.6f + k * (cw * 1.2f / 3f);
            float y = front ? cy - ch / 2 - 6 * d : cy + ch / 2 + 6 * d;
            p.setColor(color(radar[i]));
            c.drawLine(x - 4 * d, y, x + 4 * d, y, p);
        }

        // AI icons. Sideways spread from where the box sits across the picture (wide-angle lens, rough).
        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.CENTER);
        for (int s = 0; s < 4; s++) {
            for (Vision.Obj o : objs[s]) {
                float xn = (o.x1 + o.x2) / 2f - 0.5f;     // -0.5 .. 0.5
                float m = o.metres, along = xn * m * 2.2f;
                float x, y;
                if (s == CameraFeed.FRONT) { x = cx + along * ppm; y = cy - ch / 2 - m * ppm; }
                else if (s == CameraFeed.REAR) { x = cx - along * ppm; y = cy + ch / 2 + m * ppm; }
                else if (s == CameraFeed.LEFT) { x = cx - cw / 2 - m * ppm; y = cy - along * ppm; }
                else { x = cx + cw / 2 + m * ppm; y = cy + along * ppm; }
                drawIcon(c, o.cls, x, y, ppm, d);
                p.setColor(th.text);
                p.setTextSize(10 * d);
                c.drawText(String.format(java.util.Locale.US, "%.0f m", m), x, y + 1.6f * ppm + 10 * d, p);
            }
        }
        p.setTextAlign(Paint.Align.LEFT);
    }

    private void drawIcon(Canvas c, int cls, float x, float y, float ppm, float d) {
        p.setColor(Vision.color(cls));
        float hw, hl;
        if (cls == Detector.PERSON) { c.drawCircle(x, y, Math.max(5 * d, 0.4f * ppm), p); return; }
        if (cls == Detector.BICYCLE || cls == Detector.MOTORCYCLE) { hw = 0.4f; hl = 1.0f; }
        else if (cls == Detector.BUS || cls == Detector.TRUCK) { hw = 1.25f; hl = 4.5f; }
        else { hw = 0.9f; hl = 2.2f; }
        r.set(x - Math.max(4 * d, hw * ppm), y - Math.max(8 * d, hl * ppm), x + Math.max(4 * d, hw * ppm), y + Math.max(8 * d, hl * ppm));
        c.drawRoundRect(r, 3 * d, 3 * d, p);
    }
}
