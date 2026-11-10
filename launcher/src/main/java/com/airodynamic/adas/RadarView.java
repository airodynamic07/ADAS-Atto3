package com.airodynamic.adas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * "Map" view for now: your car from above with the parking radar around it.
 * AI icons for cars, motorbikes and people join it in Phase 2.
 * Radar sensor order is not confirmed yet: 9 values, 150 = nothing near; shown front row
 * (first 4) and rear row (next 4) until a car test pins each sensor down.
 */
public final class RadarView extends View {
    private final Theme th;
    private int[] radar = new int[0];
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    public RadarView(Context c, Theme th) { super(c); this.th = th; }

    public void setRadar(int[] v) { radar = v; invalidate(); }

    private int color(int cm) {
        if (cm <= 0 || cm >= 150) return th.line;
        if (cm < 40) return Theme.DANGER;
        if (cm < 80) return Theme.CAUTION;
        return Theme.SAFE;
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float d = getResources().getDisplayMetrics().density;
        // road
        p.setStyle(Paint.Style.FILL);
        p.setColor(th.chip);
        c.drawRect(w * 0.12f, 0, w * 0.88f, h, p);
        p.setColor(th.line);
        p.setStrokeWidth(3 * d);
        for (float y = 0; y < h; y += 36 * d) {
            c.drawLine(w * 0.37f, y, w * 0.37f, y + 18 * d, p);
            c.drawLine(w * 0.63f, y, w * 0.63f, y + 18 * d, p);
        }
        // our car
        float cw = Math.min(w * 0.16f, 70 * d), ch = cw * 2.1f;
        float cx = w / 2, cy = h / 2;
        r.set(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2);
        p.setColor(th.text);
        c.drawRoundRect(r, cw * 0.28f, cw * 0.28f, p);
        p.setColor(th.chip);
        r.set(cx - cw * 0.34f, cy - ch * 0.30f, cx + cw * 0.34f, cy - ch * 0.12f);
        c.drawRoundRect(r, 6 * d, 6 * d, p);
        // radar arcs: front row above the car, rear row below
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(6 * d);
        p.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < Math.min(8, radar.length); i++) {
            boolean front = i < 4;
            int k = i % 4;
            float x = cx - cw * 0.6f + k * (cw * 1.2f / 3f);
            float y = front ? cy - ch / 2 - 14 * d : cy + ch / 2 + 14 * d;
            p.setColor(color(radar[i]));
            c.drawLine(x - 8 * d, y, x + 8 * d, y, p);
        }
        p.setStyle(Paint.Style.FILL);
    }
}
