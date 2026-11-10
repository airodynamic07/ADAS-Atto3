package com.airodynamic.adas;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Right window: assistant card (Phase 5), tyres, outside and inside cards with PM2.5.
 * No climate panel: the car's own bottom bar already does the A/C (user's choice).
 */
public final class InfoPanel implements Panel {
    private static final double LOW_BAR = 2.3;
    private final Context c;
    private final Theme th;
    private final LinearLayout root;
    private final TextView[] tyre = new TextView[4];
    private final TextView tyreNote, outTemp, outPm, outLevel, inPm, inLevel;

    public InfoPanel(Context c, Theme th) {
        this.c = c;
        this.th = th;
        root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        int gap = Ui.dp(c, 8);

        LinearLayout ai = card();
        ai.addView(Ui.label(c, "Assistant", th));
        TextView aiText = Ui.text(c, "Ask about your car — coming in Phase 5", 15, th.text, false);
        aiText.setPadding(0, Ui.dp(c, 6), 0, 0);
        ai.addView(aiText);
        root.addView(ai, lp(-2, 0, gap));

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout tyres = card();
        LinearLayout head = new LinearLayout(c);
        head.addView(Ui.label(c, "Tyres (bar)", th), new LinearLayout.LayoutParams(0, -2, 1f));
        tyreNote = Ui.text(c, "", 11, Theme.CAUTION, true);
        head.addView(tyreNote);
        tyres.addView(head);
        GridLayout g = new GridLayout(c);
        g.setColumnCount(2);
        g.setPadding(0, Ui.dp(c, 10), 0, 0);
        for (int i = 0; i < 4; i++) {
            tyre[i] = Ui.text(c, "–", 22, th.text, true);
            tyre[i].setGravity(Gravity.CENTER);
            tyre[i].setBackground(Ui.round(th.chip, 0, 10, c));
            GridLayout.LayoutParams p = new GridLayout.LayoutParams(GridLayout.spec(i / 2, 1f), GridLayout.spec(i % 2, 1f));
            p.width = 0;
            p.height = Ui.dp(c, 54);
            p.setMargins(Ui.dp(c, 4), i / 2 == 1 ? Ui.dp(c, 40) : 0, Ui.dp(c, 4), 0);
            g.addView(tyre[i], p);
        }
        tyres.addView(g);
        TextView car = Ui.text(c, "▲ front", 11, th.muted, false);
        car.setGravity(Gravity.CENTER);
        tyres.addView(car);
        row.addView(tyres, new LinearLayout.LayoutParams(0, -1, 1f));

        LinearLayout temps = new LinearLayout(c);
        temps.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -1, 1f);
        tl.leftMargin = gap;
        row.addView(temps, tl);

        LinearLayout out = card();
        out.addView(Ui.label(c, "Outside", th));
        outTemp = Ui.text(c, "–°", 40, th.text, true);
        out.addView(outTemp);
        LinearLayout op = new LinearLayout(c);
        op.addView(Ui.text(c, "PM2.5 ", 12, th.muted, false));
        outPm = Ui.text(c, "–", 18, th.text, true);
        outLevel = Ui.text(c, "", 13, th.accent, true);
        op.addView(outPm);
        op.addView(outLevel);
        out.addView(op);
        temps.addView(out, lp(0, 1f, gap));

        LinearLayout in = card();
        in.addView(Ui.label(c, "Inside", th));
        in.addView(Ui.text(c, "–°", 40, th.muted, true));   // the car has no cabin temperature reading
        LinearLayout ip = new LinearLayout(c);
        ip.addView(Ui.text(c, "PM2.5 ", 12, th.muted, false));
        inPm = Ui.text(c, "–", 18, th.text, true);
        inLevel = Ui.text(c, "", 13, th.accent, true);
        ip.addView(inPm);
        ip.addView(inLevel);
        in.addView(ip);
        temps.addView(in, lp(0, 1f, 0));
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(c, 12);
        l.setPadding(p, p, p, p);
        l.setBackground(Ui.round(th.panel, th.line, 20, c));
        return l;
    }

    private static LinearLayout.LayoutParams lp(int h, float weight, int bottom) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, h == 0 ? 0 : h, weight);
        l.bottomMargin = bottom;
        return l;
    }

    private void pm(TextView v, TextView level, int pm) {
        if (pm < 0) { v.setText("–"); level.setText(""); return; }
        v.setText(pm + "  ");
        if (pm <= 25) { level.setText("Good"); level.setTextColor(th.accent); }
        else if (pm <= 50) { level.setText("Moderate"); level.setTextColor(Theme.CAUTION); }
        else { level.setText("Unhealthy"); level.setTextColor(Theme.DANGER); }
    }

    @Override public View view() { return root; }
    @Override public void setActive(boolean a) {}

    @Override public void onCar(CarData.State s) {
        String[] names = {"FL", "FR", "RL", "RR"};
        String low = "";
        for (int i = 0; i < 4; i++) {
            double b = s.tyreBar[i];
            tyre[i].setText(Double.isNaN(b) ? "–" : String.format(Locale.US, "%.1f", b));
            boolean isLow = !Double.isNaN(b) && b < LOW_BAR;
            tyre[i].setBackground(Ui.round(isLow ? Theme.CAUTION : th.chip, 0, 10, c));
            tyre[i].setTextColor(isLow ? 0xFF000000 : th.text);
            if (isLow) low = names[i] + " low";
        }
        tyreNote.setText(low);
        outTemp.setText(s.outsideC == Integer.MIN_VALUE ? "–°" : s.outsideC + "°");
        pm(outPm, outLevel, s.pmOut);
        pm(inPm, inLevel, s.pmIn);
    }
}
