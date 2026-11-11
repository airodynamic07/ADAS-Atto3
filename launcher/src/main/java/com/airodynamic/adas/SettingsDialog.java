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
            String n = t == Theme.BLUE ? "Black blue" : t == Theme.WHITE ? "White minimal" : "Black red sport";
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
        root.addView(Ui.text(c, "Same colours as BYD's own ambient bar. Slide a bar: the light follows. Try plays the gear's colour with the style.\nLast: "
                + Ambient.get(c).report, 12, th.muted, false));
        // Live: which colour number the car has now. Pick a colour in BYD's own ambient menu,
        // then open this to see its number (so the bar can be matched to BYD's colours).
        final TextView now = Ui.text(c, "Car's colour now: …", 13, th.text, true);
        root.addView(now);
        new Thread(() -> {
            Object v = CarData.get(c).ambient("getIALColor", 1);
            now.post(() -> now.setText("Car's colour now: " + v));
        }).start();
        for (int g = 0; g < Prefs.GEARS.length; g++) {
            final int gg = g;
            LinearLayout gr = row(c);
            gr.setGravity(android.view.Gravity.CENTER_VERTICAL);
            gr.setPadding(0, Ui.dp(c, 4), 0, 0);
            final TextView val = Ui.text(c, Prefs.GEARS[g] + "  " + prefs.ambientColour(g), 15, th.text, true);
            gr.addView(val, new LinearLayout.LayoutParams(Ui.dp(c, 64), -2));
            // Slide to pick a colour (BYD's colour bar): the car's light follows as you slide (colours 1–31)
            android.widget.SeekBar bar = colourBar(c, th, prefs.ambientColour(g), v -> {
                prefs.setAmbientColour(gg, v);
                val.setText(Prefs.GEARS[gg] + "  " + v);
                Ambient.get(c).apply(v, 0);
            });
            gr.addView(bar, new LinearLayout.LayoutParams(0, Ui.dp(c, 48), 1f));
            gr.addView(Ui.chip(c, "Try", false, th, v -> {
                Ambient.get(c).apply(prefs.ambientColour(gg), prefs.ambientStyle());
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() ->
                        android.widget.Toast.makeText(c, Ambient.get(c).report, android.widget.Toast.LENGTH_LONG).show(), 1500);
            }), new LinearLayout.LayoutParams(Ui.dp(c, 72), -2));
            root.addView(gr);
        }

        section(c, root, th, "Ambient light when the screen turns red (above " + prefs.autoSportKmh() + " km/h): "
                + (prefs.ambientOver() ? "on" : "off") + " · blinks, then holds the colour");
        LinearLayout ao = row(c);
        ao.setGravity(android.view.Gravity.CENTER_VERTICAL);
        ao.addView(Ui.chip(c, prefs.ambientOver() ? "On" : "Off", prefs.ambientOver(), th, v -> { prefs.setAmbientOver(!prefs.ambientOver()); redo.run(); }),
                new LinearLayout.LayoutParams(Ui.dp(c, 72), -2));
        ao.addView(colourBar(c, th, prefs.ambientOverColour(), v -> { prefs.setAmbientOverColour(v); Ambient.get(c).apply(v, 0); }),
                new LinearLayout.LayoutParams(0, Ui.dp(c, 48), 1f));
        ao.addView(Ui.chip(c, "Try", false, th, v -> Ambient.get(c).apply(prefs.ambientOverColour(), 2)),
                new LinearLayout.LayoutParams(Ui.dp(c, 72), -2));
        root.addView(ao);

        section(c, root, th, "Left / Right camera picture");
        LinearLayout sc = row(c);
        sc.addView(Ui.chip(c, "Close-up of the side", prefs.sideCloseUp(), th, v -> { prefs.setSideCloseUp(true); redo.run(); }), weight(c));
        sc.addView(Ui.chip(c, "Whole picture", !prefs.sideCloseUp(), th, v -> { prefs.setSideCloseUp(false); redo.run(); }), weight(c));
        root.addView(sc);

        section(c, root, th, "Tyre pressure unit");
        LinearLayout tu = row(c);
        for (String[] u : new String[][]{{"psi", "PSI"}, {"bar", "bar"}, {"kpa", "kPa"}}) {
            tu.addView(Ui.chip(c, u[1], prefs.tyreUnit().equals(u[0]), th, v -> { prefs.setTyreUnit(u[0]); redo.run(); }), weight(c));
        }
        root.addView(tu);

        section(c, root, th, "Launcher & windows");
        LinearLayout lw = row(c);
        lw.addView(Ui.chip(c, "⇄ Swap left and right", false, th, v -> { prefs.setInfoLeft(!prefs.infoLeft()); redo.run(); }), weight(c));
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

    /** BYD's own ambient colour bar (sampled from the car's settings screen, 2026-11-11), left to right. */
    static final int[] BYD_COLOURS = {
            0xFFD815EB, 0xFF9A1CF0, 0xFF4B2BF2, 0xFF0B4EF0, 0xFF0BACED, 0xFF0AC4F1, 0xFF0CD4EE, 0xFF13DFE7,
            0xFF17E1E3, 0xFF62DCDC, 0xFF73DBD4, 0xFF79DCBE, 0xFF1AE076, 0xFF0AE80C, 0xFF9FE011, 0xFFC8D116,
            0xFFD6C936, 0xFFD7C72B, 0xFFD8CC0E, 0xFFDACB0D, 0xFFE5C50E, 0xFFE9A911, 0xFFEA700E, 0xFFE74115,
            0xFFF80020, 0xFFEE005D, 0xFFE95C6B, 0xFFCACDCA, 0xFFB4C8C9, 0xFF84CCD4, 0xFF7ECDD6};

    public interface IntPick { void on(int v); }

    /** Colour bar 1–31 painted as a spectrum; calls back while sliding. */
    private static android.widget.SeekBar colourBar(Context c, Theme th, int value, IntPick pick) {
        android.widget.SeekBar bar = new android.widget.SeekBar(c);
        bar.setMax(30);
        bar.setProgress(value - 1);
        android.graphics.drawable.GradientDrawable spec = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, BYD_COLOURS);
        spec.setCornerRadius(Ui.dp(c, 6));
        bar.setProgressDrawable(spec);
        bar.setPadding(Ui.dp(c, 14), Ui.dp(c, 14), Ui.dp(c, 14), Ui.dp(c, 14));
        bar.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int pr, boolean user) { if (user) pick.on(pr + 1); }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {}
        });
        return bar;
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
