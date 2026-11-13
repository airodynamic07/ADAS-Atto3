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
        am.addView(Ui.chip(c, prefs.ambientGear() ? "On" : "Off", prefs.ambientGear(), th, v -> { prefs.setAmbientGear(!prefs.ambientGear()); if (prefs.ambientGear()) Ambient.get(c).applyCurrentGear(); redo.run(); }), weight(c));
        for (int i = 0; i < Ambient.STYLES.length; i++) {
            final int st = i;
            am.addView(Ui.chip(c, Ambient.STYLES[i], prefs.ambientStyle() == i, th, v -> { prefs.setAmbientStyle(st); redo.run(); }), weight(c));
        }
        root.addView(am);
        root.addView(Ui.text(c, "Same colours as BYD's own ambient bar. Slide a bar: the light follows. Try plays the gear's colour with the style.\nLast: "
                + Ambient.get(c).report + "\nLast gear change: " + Ambient.get(c).gearReport, 12, th.muted, false));
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

        section(c, root, th, "Ambient light when a door is open: " + (prefs.ambientDoor() ? "on" : "off")
                + " · blinks like over-speed, back to normal when all doors are closed");
        LinearLayout ad = row(c);
        ad.setGravity(android.view.Gravity.CENTER_VERTICAL);
        ad.addView(Ui.chip(c, prefs.ambientDoor() ? "On" : "Off", prefs.ambientDoor(), th, v -> { prefs.setAmbientDoor(!prefs.ambientDoor()); redo.run(); }),
                new LinearLayout.LayoutParams(Ui.dp(c, 72), -2));
        ad.addView(colourBar(c, th, prefs.ambientDoorColour(), v -> { prefs.setAmbientDoorColour(v); Ambient.get(c).apply(v, 0); }),
                new LinearLayout.LayoutParams(0, Ui.dp(c, 48), 1f));
        ad.addView(Ui.chip(c, "Try", false, th, v -> Ambient.get(c).apply(prefs.ambientDoorColour(), 2)),
                new LinearLayout.LayoutParams(Ui.dp(c, 72), -2));
        root.addView(ad);

        section(c, root, th, "Left / Right camera picture");
        LinearLayout sc = row(c);
        sc.addView(Ui.chip(c, "Close-up of the side", prefs.sideCloseUp(), th, v -> { prefs.setSideCloseUp(true); redo.run(); }), weight(c));
        sc.addView(Ui.chip(c, "Whole picture", !prefs.sideCloseUp(), th, v -> { prefs.setSideCloseUp(false); redo.run(); }), weight(c));
        root.addView(sc);
        section(c, root, th, "Side camera view height (close-up)");
        LinearLayout su = row(c);
        String[] ul = {"Low", "Middle", "High"};
        for (int u = 0; u < 3; u++) {
            final int uu = u;
            su.addView(Ui.chip(c, ul[u], prefs.sideCamUp() == u, th, v -> { prefs.setSideCamUp(uu); redo.run(); }), weight(c));
        }
        root.addView(su);

        // graph moving average (user, 2026-11-12): real time or over 100–500 m, separately for each line
        for (int which = 0; which < 2; which++) {
            final boolean spd = which == 0;
            section(c, root, th, spd ? "Graph: speed line average" : "Graph: power line average");
            LinearLayout sm = row(c);
            int cur = spd ? prefs.smoothSpeed() : prefs.smoothPower();
            for (int m : new int[]{0, 100, 200, 300, 400, 500}) {
                sm.addView(Ui.chip(c, m == 0 ? "Real time" : m + " m", cur == m, th, v -> {
                    if (spd) prefs.setSmoothSpeed(m); else prefs.setSmoothPower(m);
                    redo.run();
                }), weight(c));
            }
            root.addView(sm);
        }

        section(c, root, th, "Song video (🎬 on Now playing): " + (prefs.videoWhileDriving() ? "also while driving, for the passenger" : "only in P"));
        LinearLayout vd = row(c);
        vd.addView(Ui.chip(c, "While driving too", prefs.videoWhileDriving(), th, v -> {
            prefs.setVideoWhileDriving(true);
            android.widget.Toast.makeText(c, "Passenger only: the driver must not watch", android.widget.Toast.LENGTH_LONG).show();
            redo.run(); }), weight(c));
        vd.addView(Ui.chip(c, "Only in P", !prefs.videoWhileDriving(), th, v -> { prefs.setVideoWhileDriving(false); redo.run(); }), weight(c));
        root.addView(vd);

        section(c, root, th, "Tyre pressure unit");
        LinearLayout tu = row(c);
        for (String[] u : new String[][]{{"psi", "PSI"}, {"bar", "bar"}, {"kpa", "kPa"}}) {
            tu.addView(Ui.chip(c, u[1], prefs.tyreUnit().equals(u[0]), th, v -> { prefs.setTyreUnit(u[0]); redo.run(); }), weight(c));
        }
        root.addView(tu);

        section(c, root, th, "Launcher & windows");
        LinearLayout lw = row(c);
        lw.addView(Ui.chip(c, "⇄ Swap left and right", false, th, v -> { prefs.setInfoLeft(!prefs.infoLeft()); redo.run(); }), weight(c));
        // BYD keeps asking "Select a Home app": set NATEE as the Home app through the car's ADB
        lw.addView(Ui.chip(c, "Make NATEE the Home app", false, th, v -> adb(c, host,
                "cmd package set-home-activity " + c.getPackageName() + "/.LauncherActivity", "Home app")), weight(c));
        lw.addView(Ui.chip(c, "Choose home screen…", false, th, v -> {
            Intent i = new Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { c.startActivity(i); } catch (Exception e) {
                c.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        }), weight(c));
        root.addView(lw);

        // DRL button showed OFF always (2026-11-12): learn which reading follows the DRL switch
        section(c, root, th, "DRL button: " + (Prefs.drlKey(c).isEmpty() ? "reading not found yet" : "using " + Prefs.drlKey(c).replaceAll(".*\\|(get)?", "").replaceAll("\\|-1$", "")));
        LinearLayout dl = row(c);
        dl.addView(Ui.chip(c, "Find DRL reading", false, th, v -> findDrl(c, th, redo)), weight(c));
        root.addView(dl);

        // cabin / battery / 12 V cards show "–": list the car's readings to find the right ones (user, 2026-11-13)
        section(c, root, th, "Cabin · Battery · 12 V cards");
        LinearLayout fr = row(c);
        fr.addView(Ui.chip(c, "Find readings", false, th, v -> findReadings(c, th)), weight(c));
        root.addView(fr);

        section(c, root, th, "Problems");
        LinearLayout pb = row(c);
        pb.addView(Ui.chip(c, "Show crash log", false, th, v -> new Thread(() -> {
            String out;
            try {
                out = Updater.adbShell(c, "logcat -d -b crash -t 120; echo ----; logcat -d -t 400 *:E | grep -i -E 'com.airodynamic|gecko|fatal|AndroidRuntime' | grep -v -i -E 'token|GmsHttp|AuthRequest|oauth|email' | tail -n 60");
            } catch (Exception e) { out = "Couldn't reach the car's ADB: " + e; }
            final String text = out.isEmpty() ? "No crash recorded." : out;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                TextView t = Ui.text(c, text, 10, th.text, false);
                t.setTextIsSelectable(true);
                t.setPadding(24, 16, 24, 16);
                ScrollView sv2 = new ScrollView(c);
                sv2.addView(t);
                new AlertDialog.Builder(c).setTitle("Crash log (send a photo)").setView(sv2).setPositiveButton("Close", null).show();
            });
        }).start()), weight(c));
        root.addView(pb);

        section(c, root, th, "About");
        String ver = "?";
        try { ver = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception ignored) {}
        TextView about = Ui.text(c, "NATEE Launcher v" + ver + " · github.com/airodynamic07/ADAS-Atto3\n"
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

    /** Runs a shell command through the car's ADB and reports the answer. */
    private static void adb(Context c, Host host, String cmd, String what) {
        new Thread(() -> {
            String out;
            try { out = Updater.adbShell(c, cmd); out = what + ": " + (out.isEmpty() ? "done" : out); }
            catch (Exception e) { out = what + ": couldn't reach the car's ADB (" + e.getClass().getSimpleName() + ")"; }
            host.log(out);
        }).start();
    }

    /**
     * Step 1: note every light / setting reading. Step 2: the driver switches the DRL in BYD's page and
     * comes back. Step 3: the readings that changed are shown; the driver says whether the DRL is now
     * on or off, and the first changed light reading is remembered. Reading only, nothing is switched.
     */
    private static void findDrl(Context c, Theme th, Runnable redo) {
        final String[] devs = {"light.BYDAutoLightDevice", "setting.BYDAutoSettingDevice", "bodywork.BYDAutoBodyworkDevice"};
        android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        android.widget.Toast.makeText(c, "Reading the car… (a few seconds)", android.widget.Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final java.util.Map<String, Integer> before = CarData.get(c).snapshot(devs);
            ui.post(() -> new AlertDialog.Builder(c).setTitle("Find DRL reading (" + before.size() + " readings noted)")
                    .setMessage("1. Open BYD's Light page → Exterior and tap Daytime Running Light once (on → off, or off → on).\n"
                            + "2. Come back here and tap Done.")
                    .setPositiveButton("Done", (d, w) -> new Thread(() -> {
                        java.util.Map<String, Integer> after = CarData.get(c).snapshot(devs);
                        final java.util.List<String> changed = new java.util.ArrayList<>();
                        for (java.util.Map.Entry<String, Integer> e : after.entrySet()) {
                            Integer b = before.get(e.getKey());
                            if (b != null && !b.equals(e.getValue())) changed.add(e.getKey());
                        }
                        // light device first
                        java.util.Collections.sort(changed, (a, b2) -> Boolean.compare(!a.startsWith("light."), !b2.startsWith("light.")));
                        ui.post(() -> {
                            if (changed.isEmpty()) {
                                new AlertDialog.Builder(c).setTitle("No reading changed")
                                        .setMessage("NATEE couldn't see the DRL switch change. Send a photo of this to Claude.")
                                        .setPositiveButton("OK", null).show();
                                return;
                            }
                            StringBuilder sb = new StringBuilder();
                            for (String k : changed) sb.append(k.replaceAll(".*\\.BYDAuto|Device", "")).append(": ")
                                    .append(before.get(k)).append(" → ").append(after.get(k)).append('\n');
                            final String key = changed.get(0);
                            new AlertDialog.Builder(c).setTitle("Changed readings (send a photo to Claude)")
                                    .setMessage(sb + "\nIs the DRL ON or OFF now?")
                                    .setPositiveButton("DRL is ON", (d2, w2) -> { Prefs.setDrl(c, key, after.get(key)); redo.run(); })
                                    .setNegativeButton("DRL is OFF", (d2, w2) -> { Prefs.setDrl(c, key, before.get(key)); redo.run(); })
                                    .setNeutralButton("Cancel", null).show();
                        });
                    }).start())
                    .setNegativeButton("Cancel", null).show());
        }).start();
    }

    /** Every temperature / voltage-looking reading the car gives; tap one to use it for a card. Read only. */
    private static void findReadings(Context c, Theme th) {
        android.widget.Toast.makeText(c, "Reading the car… (a few seconds)", android.widget.Toast.LENGTH_SHORT).show();
        android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            java.util.Map<String, Integer> all = CarData.get(c).snapshot("ac.BYDAutoAcDevice", "statistic.BYDAutoStatisticDevice",
                    "instrument.BYDAutoInstrumentDevice", "bodywork.BYDAutoBodyworkDevice", "bms.BYDAutoBmsDevice",
                    "energy.BYDAutoEnergyDevice", "power.BYDAutoPowerDevice", "engine.BYDAutoEngineDevice", "charging.BYDAutoChargingDevice");
            final java.util.List<String> keys = new java.util.ArrayList<>();
            final java.util.List<String> labels = new java.util.ArrayList<>();
            // the air-conditioner's readings last: the first photo showed only those (2026-11-13)
            java.util.List<java.util.Map.Entry<String, Integer>> sorted = new java.util.ArrayList<>(all.entrySet());
            java.util.Collections.sort(sorted, (x, y) -> Boolean.compare(x.getKey().startsWith("ac."), y.getKey().startsWith("ac.")));
            for (java.util.Map.Entry<String, Integer> e : sorted) {
                String m = e.getKey().split("\\|")[1].toLowerCase(java.util.Locale.US);
                if (!(m.contains("temp") || m.contains("volt"))) continue;
                if (m.contains("color") || m.contains("antivirus") || m.contains("unit") || m.contains("mode")) continue;
                keys.add(e.getKey());
                labels.add(e.getKey().replaceAll(".*\\.BYDAuto|Device", "").replace("|-1", "") + " = " + e.getValue());
            }
            ui.post(() -> {
                if (keys.isEmpty()) { new AlertDialog.Builder(c).setTitle("No readings found").setPositiveButton("OK", null).show(); return; }
                new AlertDialog.Builder(c).setTitle("Car readings (send a photo to Claude) — tap one to use it")
                        .setItems(labels.toArray(new String[0]), (d, i) -> new AlertDialog.Builder(c).setTitle(labels.get(i))
                                .setItems(new String[]{"Use for Cabin", "Use for Battery temp", "Use for 12 V"}, (d2, j) -> {
                                    String n = j == 0 ? "cabin" : j == 1 ? "battery" : "volt12";
                                    c.getSharedPreferences("adas_launcher", 0).edit().putString("read_" + n, keys.get(i)).apply();
                                }).show())
                        .setPositiveButton("Close", null).show();
            });
        }).start();
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
