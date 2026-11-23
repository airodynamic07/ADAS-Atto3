package com.airodynamic.adas;

import android.content.Context;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** Today's route map filling the middle window (user, 2026-11-12), with today's numbers and a ✕. */
final class RouteOverlay extends FrameLayout implements TripEnergy.Listener {
    private final TextView head;
    private final String day;   // null = today (live)

    RouteOverlay(Context c, Theme th, Runnable onClose) { this(c, th, onClose, null, null); }

    /** A chosen day's route (Every day Map, Air 2026-11-23); onBack goes back to the list of days. */
    RouteOverlay(Context c, Theme th, Runnable onClose, String day, Runnable onBack) {
        super(c);
        boolean isToday = day == null || day.equals(TodayRoute.get(c).today());
        this.day = isToday ? null : day;
        setBackground(Ui.round(th.card, th.accent, 20, c));
        setClipToOutline(true);
        int b = Ui.dp(c, 2);
        setPadding(b, b, b, b);
        final RouteMapView map = new RouteMapView(c, th);
        if (this.day != null) map.showDay(this.day);
        addView(map, new LayoutParams(-1, -1));
        // + / − zoom buttons, bottom right (pinch works too)
        LinearLayout zb = new LinearLayout(c);
        zb.setOrientation(LinearLayout.VERTICAL);
        android.widget.Button zin = Ui.chip(c, "+", false, th, v -> map.zoomBy(1));
        android.widget.Button zout = Ui.chip(c, "−", false, th, v -> map.zoomBy(-1));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(Ui.dp(c, 46), Ui.dp(c, 46));
        bl.bottomMargin = Ui.dp(c, 6);
        zb.addView(zin, bl); zb.addView(zout, new LinearLayout.LayoutParams(Ui.dp(c, 46), Ui.dp(c, 46)));
        LayoutParams zl = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        zl.rightMargin = Ui.dp(c, 8); zl.bottomMargin = Ui.dp(c, 30);
        addView(zb, zl);

        int glass = (th.card & 0x00FFFFFF) | 0xDD000000;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.round(glass, th.line, 12, c));
        box.setPadding(Ui.dp(c, 10), Ui.dp(c, 5), Ui.dp(c, 12), Ui.dp(c, 6));
        box.addView(Ui.label(c, this.day == null ? "Today route" : niceDay(this.day), th));
        head = Ui.text(c, "–", 17, th.text, true);
        box.addView(head);
        LayoutParams hl = new LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        hl.leftMargin = hl.topMargin = Ui.dp(c, 8);
        addView(box, hl);

        TextView x = Ui.text(c, "✕", 22, th.text, true);
        x.setGravity(Gravity.CENTER);
        x.setBackground(Ui.round(glass, th.accent, 23, c));
        x.setContentDescription("Close the route map");
        x.setOnClickListener(v -> onClose.run());
        LayoutParams xl = new LayoutParams(Ui.dp(c, 46), Ui.dp(c, 46), Gravity.TOP | Gravity.END);
        xl.rightMargin = xl.topMargin = Ui.dp(c, 8);
        addView(x, xl);
        if (onBack != null) {   // ‹ back to the list of days
            TextView back = Ui.text(c, "‹", 26, th.text, true);
            back.setGravity(Gravity.CENTER);
            back.setBackground(Ui.round(glass, th.accent, 23, c));
            back.setOnClickListener(v -> onBack.run());
            LayoutParams bk = new LayoutParams(Ui.dp(c, 46), Ui.dp(c, 46), Gravity.TOP | Gravity.END);
            bk.rightMargin = Ui.dp(c, 62); bk.topMargin = Ui.dp(c, 8);
            addView(back, bk);
        }
        if (this.day != null) {   // an earlier day: its numbers from the history
            double[] v = TripEnergy.get(c).history().get(this.day);
            if (v != null) {
                long s = Math.round(v[3]);
                head.setText(String.format(Locale.US, "%.1f km · %d:%02d · %s kWh/100", v[0], s / 3600, s / 60 % 60,
                        v[0] >= 0.5 && v[1] > 0 ? String.format(Locale.US, "%.1f", v[1] / v[0] * 100) : "–"));
            }
        }
    }

    static String niceDay(String d) {
        try {
            return new java.text.SimpleDateFormat("EEE d MMM yyyy", Locale.US).format(new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d));
        } catch (Exception e) { return d; }
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); TripEnergy.get(getContext()).addListener(this); }
    @Override protected void onDetachedFromWindow() { TripEnergy.get(getContext()).removeListener(this); super.onDetachedFromWindow(); }

    @Override public void onTrip(TripEnergy t) {
        if (day != null) return;   // an earlier day doesn't change
        long s = Math.round(t.todaySec());
        double k = t.todayKm();
        head.setText(String.format(Locale.US, "%.1f km · %d:%02d · %s kWh/100", k, s / 3600, s / 60 % 60,
                k >= 0.5 && t.todayKwh() > 0 ? String.format(Locale.US, "%.1f", t.todayKwh() / k * 100) : "–"));
    }
}
