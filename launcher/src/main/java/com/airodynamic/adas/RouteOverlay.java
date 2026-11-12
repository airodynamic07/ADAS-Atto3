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

    RouteOverlay(Context c, Theme th, Runnable onClose) {
        super(c);
        setBackground(Ui.round(th.card, th.accent, 20, c));
        setClipToOutline(true);
        int b = Ui.dp(c, 2);
        setPadding(b, b, b, b);
        addView(new RouteMapView(c, th), new LayoutParams(-1, -1));

        int glass = (th.card & 0x00FFFFFF) | 0xDD000000;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.round(glass, th.line, 12, c));
        box.setPadding(Ui.dp(c, 10), Ui.dp(c, 5), Ui.dp(c, 12), Ui.dp(c, 6));
        box.addView(Ui.label(c, "Today route", th));
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
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); TripEnergy.get(getContext()).addListener(this); }
    @Override protected void onDetachedFromWindow() { TripEnergy.get(getContext()).removeListener(this); super.onDetachedFromWindow(); }

    @Override public void onTrip(TripEnergy t) {
        long s = Math.round(t.todaySec());
        double k = t.todayKm();
        head.setText(String.format(Locale.US, "%.1f km · %d:%02d · %s kWh/100", k, s / 3600, s / 60 % 60,
                k >= 0.5 ? String.format(Locale.US, "%.1f", t.todayKwh() / k * 100) : "–"));
    }
}
