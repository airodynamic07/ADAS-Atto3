package com.airodynamic.adas;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Phase 4 settings: theme, Auto Sport, turn-signal camera, windows, home screen, updates. */
public final class SettingsDialog {
    private SettingsDialog() {}

    public interface Host { void rebuild(); void log(String s); }

    public static void show(Context c, Theme th, Prefs prefs, Host host) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(c, 18);
        root.setPadding(p, p, p, p);
        root.setBackgroundColor(th.panel);
        ScrollView sv = new ScrollView(c);
        sv.addView(root);
        final AlertDialog[] dlg = new AlertDialog[1];
        Runnable redo = () -> { dlg[0].dismiss(); host.rebuild(); show(c, prefs.theme(), prefs, host); };

        section(c, root, th, "Theme");
        LinearLayout themes = row(c);
        for (Theme t : Theme.values()) {
            String n = t == Theme.BLUE ? "Dark blue" : t == Theme.WHITE ? "White minimal" : "Black red sport";
            themes.addView(Ui.chip(c, n, prefs.theme() == t, th, v -> { prefs.setTheme(t); redo.run(); }), weight(c));
        }
        root.addView(themes);

        section(c, root, th, "Auto Sport: black-red above " + prefs.autoSportKmh() + " km/h, back 10 km/h below");
        LinearLayout as = row(c);
        as.addView(Ui.chip(c, prefs.autoSport() ? "On" : "Off", prefs.autoSport(), th, v -> { prefs.setAutoSport(!prefs.autoSport()); redo.run(); }), weight(c));
        as.addView(Ui.chip(c, "− 10 km/h", false, th, v -> { prefs.setAutoSportKmh(Math.max(60, prefs.autoSportKmh() - 10)); redo.run(); }), weight(c));
        as.addView(Ui.chip(c, "+ 10 km/h", false, th, v -> { prefs.setAutoSportKmh(Math.min(200, prefs.autoSportKmh() + 10)); redo.run(); }), weight(c));
        root.addView(as);

        section(c, root, th, "Turn-signal camera: back to Map " + prefs.turnCameraReturnSec() + " s after the signal stops");
        LinearLayout tc = row(c);
        tc.addView(Ui.chip(c, prefs.turnCamera() ? "On" : "Off", prefs.turnCamera(), th, v -> { prefs.setTurnCamera(!prefs.turnCamera()); redo.run(); }), weight(c));
        tc.addView(Ui.chip(c, "− 1 s", false, th, v -> { prefs.setTurnCameraReturnSec(Math.max(1, prefs.turnCameraReturnSec() - 1)); redo.run(); }), weight(c));
        tc.addView(Ui.chip(c, "+ 1 s", false, th, v -> { prefs.setTurnCameraReturnSec(Math.min(15, prefs.turnCameraReturnSec() + 1)); redo.run(); }), weight(c));
        root.addView(tc);

        section(c, root, th, "Ambient light by gear: " + (prefs.ambientGear() ? "on" : "off")
                + " · style " + Ambient.STYLES[prefs.ambientStyle()]);
        LinearLayout am = row(c);
        am.addView(Ui.chip(c, prefs.ambientGear() ? "On" : "Off", prefs.ambientGear(), th, v -> { prefs.setAmbientGear(!prefs.ambientGear()); redo.run(); }), weight(c));
        for (int i = 0; i < Ambient.STYLES.length; i++) {
            final int st = i;
            am.addView(Ui.chip(c, Ambient.STYLES[i], prefs.ambientStyle() == i, th, v -> { prefs.setAmbientStyle(st); redo.run(); }), weight(c));
        }
        root.addView(am);
        root.addView(Ui.text(c, "The car numbers its colours (1–31?). Use − / + and Try to see each one on the car's light.", 12, th.muted, false));
        for (int g = 0; g < Prefs.GEARS.length; g++) {
            final int gg = g;
            LinearLayout gr = row(c);
            gr.setPadding(0, Ui.dp(c, 4), 0, 0);
            gr.addView(Ui.text(c, Prefs.GEARS[g] + "  colour " + prefs.ambientColour(g), 15, th.text, true), weight(c));
            gr.addView(Ui.chip(c, "−", false, th, v -> { prefs.setAmbientColour(gg, Math.max(1, prefs.ambientColour(gg) - 1)); Ambient.get(c).apply(prefs.ambientColour(gg), 0); redo.run(); }), weight(c));
            gr.addView(Ui.chip(c, "+", false, th, v -> { prefs.setAmbientColour(gg, Math.min(31, prefs.ambientColour(gg) + 1)); Ambient.get(c).apply(prefs.ambientColour(gg), 0); redo.run(); }), weight(c));
            gr.addView(Ui.chip(c, "Try", false, th, v -> Ambient.get(c).apply(prefs.ambientColour(gg), prefs.ambientStyle())), weight(c));
            root.addView(gr);
        }

        section(c, root, th, "Tyre pressure unit");
        LinearLayout tu = row(c);
        for (String[] u : new String[][]{{"psi", "PSI"}, {"bar", "bar"}, {"kpa", "kPa"}}) {
            tu.addView(Ui.chip(c, u[1], prefs.tyreUnit().equals(u[0]), th, v -> { prefs.setTyreUnit(u[0]); redo.run(); }), weight(c));
        }
        root.addView(tu);

        section(c, root, th, "Launcher & windows");
        LinearLayout lw = row(c);
        lw.addView(Ui.chip(c, "⇄ Swap windows", false, th, v -> {
            String a = prefs.defaultPanel(0), b = prefs.defaultPanel(1), d = prefs.defaultPanel(2);
            prefs.setDefaultPanel(0, d); prefs.setDefaultPanel(1, a); prefs.setDefaultPanel(2, b);
            redo.run();
        }), weight(c));
        lw.addView(Ui.chip(c, "Choose home screen…", false, th, v -> {
            Intent i = new Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { c.startActivity(i); } catch (Exception e) {
                c.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        }), weight(c));
        root.addView(lw);

        section(c, root, th, "About");
        String ver = "?";
        try { ver = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception ignored) {}
        TextView about = Ui.text(c, "ADAS Launcher v" + ver + " · github.com/airodynamic07/ADAS-Atto3\n"
                + "Camera and car-data methods from OverDrive (MIT). AI model YOLO26n (AGPL-3.0).", 13, th.muted, false);
        root.addView(about);
        LinearLayout up = row(c);
        up.addView(Ui.chip(c, "⟳ Check for updates", false, th, v -> {
            host.log("Checking for updates…");
            new Thread(() -> Updater.update(c, host::log)).start();
        }), weight(c));
        root.addView(up);

        dlg[0] = new AlertDialog.Builder(c).setTitle("Settings").setView(sv).setPositiveButton("Close", null).show();
    }

    private static void section(Context c, LinearLayout root, Theme th, String s) {
        TextView t = Ui.text(c, s, 14, th.text, true);
        t.setPadding(0, Ui.dp(c, 16), 0, Ui.dp(c, 6));
        root.addView(t);
    }

    private static LinearLayout row(Context c) { return new LinearLayout(c); }

    private static LinearLayout.LayoutParams weight(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMargins(Ui.dp(c, 3), 0, Ui.dp(c, 3), 0);
        return lp;
    }
}
