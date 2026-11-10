package com.airodynamic.adas;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Middle window: your Engine Sound app (with Ghost Race inside it). This car can't show
 * another app inside a window, so the button opens it full screen; Home brings you back.
 */
public final class EnginePanel implements Panel {
    private final LinearLayout root;
    private final TextView state;
    private final Context c;

    public EnginePanel(Context c, Theme th) {
        this.c = c;
        root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = Ui.dp(c, 20);
        root.setPadding(pad, pad, pad, pad);
        TextView t = Ui.text(c, "Atto 3 Engine Sound", 22, th.text, true);
        t.setGravity(Gravity.CENTER);
        root.addView(t);
        state = Ui.text(c, "", 14, th.muted, false);
        state.setGravity(Gravity.CENTER);
        state.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 20));
        root.addView(state);
        Button open = Ui.chip(c, "Open Engine Sound", true, th, v -> Apps.open(c, Prefs.ENGINE_PKG, CarData.get(c).last()));
        open.setTextSize(18);
        root.addView(open, new LinearLayout.LayoutParams(-1, Ui.dp(c, 64)));
        TextView hint = Ui.text(c, "Ghost Race: the purple ghost button inside Engine Sound.\nPress the car's Home button to come back here.", 13, th.muted, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, Ui.dp(c, 16), 0, 0);
        root.addView(hint);
        refresh();
    }

    private void refresh() {
        state.setText(Apps.installed(c, Prefs.ENGINE_PKG) ? "Revs with your real accelerator on this screen" : "Not installed on this car");
    }

    @Override public View view() { return root; }
    @Override public void onCar(CarData.State s) {}
    @Override public void setActive(boolean a) { if (a) refresh(); }
}
