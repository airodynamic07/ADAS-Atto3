package com.airodynamic.adas;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** "Choose apps" for one window: 3 slots on top, every app below (names only, no icons). */
public final class AppPicker {
    private AppPicker() {}

    public static void show(Context c, Theme th, Prefs prefs, int win, Runnable onChange) {
        final int[] slot = {0};
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(c, 16);
        root.setPadding(p, p, p, p);
        root.setBackgroundColor(th.panel);

        TextView hint = Ui.text(c, "Tap a slot, then tap an app for it.", 14, th.muted, false);
        root.addView(hint);
        LinearLayout slots = new LinearLayout(c);
        slots.setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 10));
        root.addView(slots);

        GridLayout grid = new GridLayout(c);
        grid.setColumnCount(4);
        ScrollView sv = new ScrollView(c);
        sv.addView(grid);
        root.addView(sv, new LinearLayout.LayoutParams(-1, Ui.dp(c, 360)));

        final Runnable[] drawSlots = new Runnable[1];
        drawSlots[0] = () -> {
            slots.removeAllViews();
            for (int i = 0; i < 3; i++) {
                final int k = i;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
                lp.setMargins(Ui.dp(c, 3), 0, Ui.dp(c, 3), 0);
                slots.addView(Ui.chip(c, (i + 1) + ". " + Apps.label(c, prefs.slot(win, i)), slot[0] == i, th,
                        v -> { slot[0] = k; drawSlots[0].run(); }), lp);
            }
        };
        drawSlots[0].run();

        List<String[]> items = new ArrayList<>();
        items.add(new String[]{Prefs.ADAS, "ADAS"});
        items.add(new String[]{Prefs.ENGINE, "Engine Sound"});
        items.add(new String[]{Prefs.INFO, "Info"});
        for (Apps.App a : Apps.all(c)) items.add(new String[]{a.pkg, a.label});
        for (String[] it : items) {
            Button b = Ui.chip(c, it[1], false, th, v -> {
                prefs.setSlot(win, slot[0], it[0]);
                slot[0] = (slot[0] + 1) % 3;
                drawSlots[0].run();
                onChange.run();
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.setMargins(Ui.dp(c, 3), Ui.dp(c, 3), Ui.dp(c, 3), Ui.dp(c, 3));
            grid.addView(b, lp);
        }
        new AlertDialog.Builder(c).setTitle("Choose apps for this window").setView(root)
                .setPositiveButton("Done", null).show();
    }
}
