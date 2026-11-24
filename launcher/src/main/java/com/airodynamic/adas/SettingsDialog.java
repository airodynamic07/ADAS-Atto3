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

    public interface Host { void rebuild(); void log(String s); default void settingsClosed() { } default void progress(int pct, String text) { } }

    public static void show(Context c, Theme th, Prefs prefs, Host host) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(c, 18);
        root.setPadding(p, p, p, p);
        root.setBackgroundColor(th.panel);
        ScrollView sv = new ScrollView(c);
        sv.addView(root);
        final AlertDialog[] dlg = new AlertDialog[1];
        final boolean[] redoing = {false};
        Runnable redo = () -> { redoing[0] = true; dlg[0].dismiss(); host.rebuild(); show(c, prefs.theme(), prefs, host); };

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

        // "Hey Natee chat": voice chat with Gemini (user, 2026-11-21). Reads the car; changes comfort things only (CarControl).
        String key = prefs.aiKey();
        section(c, root, th, "AI chat (Gemini) · say \"" + prefs.aiName() + "\" or press the wheel mic: " + (prefs.aiChat() ? (key.isEmpty() ? "on, needs your API key" : "on") : "off"));
        LinearLayout ai = row(c);
        ai.addView(Ui.chip(c, "On", prefs.aiChat(), th, v -> { prefs.setAiChat(true); AiChat.sync(c); redo.run(); }), weight(c));
        ai.addView(Ui.chip(c, "Off", !prefs.aiChat(), th, v -> { prefs.setAiChat(false); AiChat.sync(c); redo.run(); }), weight(c));
        ai.addView(Ui.chip(c, key.isEmpty() ? "API key: add ›" : "API key: " + key.substring(0, Math.min(4, key.length())) + "•••• ›", false, th, v -> {
            android.widget.EditText e = new android.widget.EditText(c);
            e.setSingleLine(true);
            e.setHint("paste your key from aistudio.google.com");
            e.setText(prefs.aiKey());
            new AlertDialog.Builder(c).setTitle("Gemini API key").setView(e)
                    .setPositiveButton("Save", (d, i) -> { prefs.setAiKey(e.getText().toString()); redo.run(); })
                    .setNegativeButton("Cancel", null).show();
        }), weight(c));
        root.addView(ai);
        // the assistant's name = the wake word: two words (Air 2026-11-23)
        LinearLayout an = row(c);
        an.addView(Ui.chip(c, "Name / wake word: " + prefs.aiName() + " ›", false, th, v -> {
            LinearLayout box = new LinearLayout(c);
            box.setOrientation(LinearLayout.VERTICAL);
            int pd = Ui.dp(c, 18);
            box.setPadding(pd, pd / 2, pd, 0);
            android.widget.EditText e1 = new android.widget.EditText(c), e2 = new android.widget.EditText(c);
            e1.setSingleLine(true); e2.setSingleLine(true);
            e1.setHint("first word, e.g. Harley"); e2.setHint("second word, e.g. Quinn");
            e1.setText(prefs.aiName1()); e2.setText(prefs.aiName2());
            box.addView(e1); box.addView(e2);
            TextView hint = Ui.text(c, "Two English words (the offline listener knows English words). Check it with Wake word check.", 12, th.muted, false);
            box.addView(hint);
            new AlertDialog.Builder(c).setTitle("Assistant name (= wake word)").setView(box)
                    .setPositiveButton("Save", (d, i) -> {
                        String a1 = e1.getText().toString().trim().toLowerCase(java.util.Locale.US).replaceAll("[^a-z]", "");
                        String a2 = e2.getText().toString().trim().toLowerCase(java.util.Locale.US).replaceAll("[^a-z]", "");
                        if (a1.length() < 2 || a2.length() < 2) {
                            android.widget.Toast.makeText(c, "Please fill in both words (English letters)", android.widget.Toast.LENGTH_LONG).show();
                            return;
                        }
                        prefs.setAiName(a1, a2);
                        // restart the listener with the new name
                        c.stopService(new android.content.Intent(c, AiChat.class));
                        AiChat.sync(c);
                        redo.run();
                    })
                    .setNegativeButton("Cancel", null).show();
        }), weight(c));
        root.addView(an);
        LinearLayout al = row(c);
        for (String[] l : new String[][]{{"auto", "Thai + English"}, {"en", "English"}, {"th", "Thai"}}) {
            al.addView(Ui.chip(c, l[1], prefs.aiLang().equals(l[0]), th, v -> { prefs.setAiLang(l[0]); redo.run(); }), weight(c));
        }
        root.addView(al);
        LinearLayout av = row(c);
        for (String[] vo : new String[][]{{"Aoede", "Female · calm"}, {"Kore", "Female · bright"}, {"Charon", "Male · deep"}, {"Puck", "Male · lively"}}) {
            av.addView(Ui.chip(c, vo[1], prefs.aiVoice().equals(vo[0]), th, v -> { prefs.setAiVoice(vo[0]); redo.run(); }), weight(c));
        }
        root.addView(av);
        // the steering wheel's mic button (user, 2026-11-22): switch the key watcher on, then see what the button sends
        LinearLayout aw = row(c);
        int chosenKey = KeyWatch.store(c).getInt("chosen", -1);
        aw.addView(Ui.chip(c, "Wake word check ›", false, th, v -> wakeCheck(c)), weight(c));
        aw.addView(Ui.chip(c, "Find car controls ›", false, th, v -> findControls(c)), weight(c));
        boolean permOk = AiChat.micAllowed(c) && AiChat.overlayAllowed(c);
        aw.addView(Ui.chip(c, permOk ? "Permissions: OK" : "Fix permissions ›", false, th, v -> fixPerms(c)), weight(c));
        root.addView(aw);
        // their own row, so the labels aren't cut off (Air 2026-11-23: couldn't find the button)
        LinearLayout aw2 = row(c);
        aw2.addView(Ui.chip(c, "Switch BYD voice assistant off / on ›", false, th, v -> bydVoice(c)), weight(c));
        aw2.addView(Ui.chip(c, "Wheel mic button: " + (KeyWatch.isOn(c) ? "on" : "OFF, tap to fix") + " · test ›", false, th, v -> wheelTest(c)), weight(c));
        root.addView(aw2);

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

        // trip distance and energy are checked against the car's own counters (user, 2026-11-14)
        section(c, root, th, "Trip numbers · check against the car");
        root.addView(Ui.text(c, TripEnergy.get(c).checkInfo(), 13, th.muted, false));

        section(c, root, th, "Problems");
        LinearLayout pb = row(c);
        pb.addView(Ui.chip(c, "Show crash log", false, th, v -> new Thread(() -> {
            String out;
            try {
                out = Updater.adbShell(c, "logcat -d -b crash -t 120; echo ----; logcat -d -t 400 *:E | grep -i -E 'com.airodynamic|gecko|fatal|AndroidRuntime' | grep -v -i -E 'token|GmsHttp|AuthRequest|oauth|email' | tail -n 60; echo ---- wheel button watcher; logcat -d -t 3000 | grep -i -E 'KeyWatch|airodynamic.adas.*(died|crash|kill|ANR)|AccessibilityManager' | grep -v -i -E 'token|email' | tail -n 30");
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
        String[] reports = ProblemLog.all(c);
        pb.addView(Ui.chip(c, "Voice reports & ideas (" + reports.length + ")", false, th, v -> {
            TextView t = Ui.text(c, reports.length == 0 ? "Nothing yet. Say \"" + prefs.aiName() + "\" (or press the wheel mic), then \"report a problem: …\" or \"idea: …\"" : android.text.TextUtils.join("\n\n", reports), 12, th.text, false);
            t.setTextIsSelectable(true);
            t.setPadding(24, 16, 24, 16);
            ScrollView sv3 = new ScrollView(c);
            sv3.addView(t);
            new AlertDialog.Builder(c).setTitle("Voice reports & ideas (send a photo to Claude)").setView(sv3)
                    .setNegativeButton("Clear all", (d, i) -> { ProblemLog.clear(c); redo.run(); })
                    .setPositiveButton("Close", null).show();
        }), weight(c));
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
            new Thread(() -> Updater.update(c, new Updater.Log() {
                @Override public void line(String s) { host.log(s); }
                @Override public void progress(int pct, String text) { host.progress(pct, text); }
            })).start();
        }), weight(c));
        // (Air 2026-11-24) real screenshots for the feature guide: Settings closes, then the car's ADB takes one
        // screenshot every 8 s (6 in all) so you can change what's on screen between them; saved on the head unit and
        // copied to a USB stick when one is plugged in
        up.addView(Ui.chip(c, "📸 6 screenshots for the guide", false, th, v -> {
            if (dlg[0] != null) dlg[0].dismiss();
            android.widget.Toast.makeText(c, "Screenshots start in 8 s: one every 8 s, 6 in all. Change the screen between them.", android.widget.Toast.LENGTH_LONG).show();
            new Thread(() -> {
                String stamp = new java.text.SimpleDateFormat("MMdd_HHmm", java.util.Locale.US).format(new java.util.Date());
                String out;
                try {
                    out = Updater.adbShell(c, "mkdir -p /sdcard/Pictures/NATEE; for i in 1 2 3 4 5 6; do sleep 8; "
                            + "screencap -p /sdcard/Pictures/NATEE/natee_" + stamp + "_$i.png; done; n=0; "
                            + "for d in /storage/*; do case $d in */emulated|*/self) ;; *) mkdir -p $d/NATEE 2>/dev/null && cp /sdcard/Pictures/NATEE/natee_" + stamp + "_*.png $d/NATEE/ 2>/dev/null && echo USB:$d/NATEE && n=1;; esac; done; "
                            + "ls /sdcard/Pictures/NATEE | grep -c " + stamp);
                } catch (Exception e) { out = "couldn't reach the car's ADB (" + e.getClass().getSimpleName() + ")"; }
                final String r = out.contains("USB:") ? "6 screenshots saved, also on the USB stick in the NATEE folder."
                        : out.contains("ADB") ? "Screenshots failed: " + out
                        : "6 screenshots saved in Pictures/NATEE on the head unit (no USB stick found to copy them to).";
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> android.widget.Toast.makeText(c, r, android.widget.Toast.LENGTH_LONG).show());
            }).start();
        }), weight(c));
        root.addView(up);

        dlg[0] = new AlertDialog.Builder(c).setTitle("Settings").setView(sv).setPositiveButton("Close", null)
                .setOnDismissListener(d -> { if (!redoing[0]) host.settingsClosed(); }).show();
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
            java.util.Map<String, Double> all = CarData.get(c).snapshotAll("ac.BYDAutoAcDevice", "statistic.BYDAutoStatisticDevice",
                    "instrument.BYDAutoInstrumentDevice", "bodywork.BYDAutoBodyworkDevice", "bms.BYDAutoBmsDevice",
                    "energy.BYDAutoEnergyDevice", "power.BYDAutoPowerDevice", "engine.BYDAutoEngineDevice", "charging.BYDAutoChargingDevice");
            final java.util.List<String> keys = new java.util.ArrayList<>();
            final java.util.List<String> labels = new java.util.ArrayList<>();
            // the air-conditioner's readings last: the first photo showed only those (2026-11-13)
            java.util.List<java.util.Map.Entry<String, Double>> sorted = new java.util.ArrayList<>(all.entrySet());
            java.util.Collections.sort(sorted, (x, y) -> Boolean.compare(x.getKey().startsWith("ac."), y.getKey().startsWith("ac.")));
            for (java.util.Map.Entry<String, Double> e : sorted) {
                String m = e.getKey().split("\\|")[1].toLowerCase(java.util.Locale.US);
                if (!(m.contains("temp") || m.contains("volt") || m.contains("batter") || m.contains("elec") || m.contains("power"))) continue;
                if (m.contains("color") || m.contains("antivirus") || m.contains("unit") || m.contains("mode")) continue;
                keys.add(e.getKey());
                labels.add(e.getKey().replaceAll(".*\\.BYDAuto|Device", "").replace("|-1", "") + " = " + (e.getValue() == Math.rint(e.getValue()) ? String.valueOf(e.getValue().longValue()) : String.valueOf(e.getValue())));
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

    /**
     * Finds the sunshade and cabin-light commands (Air 2026-11-22: "open sunshade" opened the glass sunroof): lists the
     * car's commands with matching names, then which readings change when the driver uses the car's own buttons.
     */
    private static void findControls(Context c) {
        final String[] devs = {"bodywork.BYDAutoBodyworkDevice", "light.BYDAutoLightDevice", "setting.BYDAutoSettingDevice",
                "ac.BYDAutoAcDevice", "instrument.BYDAutoInstrumentDevice", "multimedia.BYDAutoMultimediaDevice", "sensor.BYDAutoSensorDevice"};
        android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            StringBuilder names = new StringBuilder();
            java.util.regex.Pattern want = java.util.regex.Pattern.compile("(?i).*(shade|curtain|blind|read|dome|lamp|roof|ceiling|interior|cabin|inner|top).*");
            for (String d : devs) {
                Object dev = CarControl.CarDev.get(c, d);
                if (dev == null) continue;
                java.util.TreeSet<String> ms = new java.util.TreeSet<>();
                for (java.lang.reflect.Method m : dev.getClass().getMethods()) {
                    // (2026-11-22, reading light not found) every voice command, and every light setter, whatever its name
                    boolean voiceCmd = m.getName().startsWith("voiceCtl");
                    boolean lightSet = d.startsWith("light.") && m.getName().startsWith("set");
                    if (!voiceCmd && !lightSet && !want.matcher(m.getName()).matches()) continue;
                    if (!(m.getName().startsWith("set") || m.getName().startsWith("get") || m.getName().startsWith("voice"))) continue;
                    ms.add(m.getName() + "(" + m.getParameterTypes().length + ")");
                }
                if (!ms.isEmpty()) names.append(d.replaceAll(".*\\.BYDAuto|Device", "")).append(": ").append(android.text.TextUtils.join(", ", ms)).append("\n\n");
                // (2026-11-22, reading light) the light part's named feature numbers, for its general set(number, value)
                if (d.startsWith("light.")) {
                    java.util.TreeSet<String> fs = new java.util.TreeSet<>();
                    java.util.regex.Pattern lamp = java.util.regex.Pattern.compile("(?i).*(read|dome|roof|ceiling|interior|inner|cabin|map_?lamp|top_?lamp|front_?lamp|rear_?lamp|atmos|ambient).*");
                    for (java.lang.reflect.Field f : dev.getClass().getFields()) {
                        if (!java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) continue;
                        if (!lamp.matcher(f.getName()).matches()) continue;
                        try { fs.add(f.getName() + "=" + f.getInt(null)); } catch (Throwable ignored) { }
                    }
                    names.append("Light feature numbers: ").append(fs.isEmpty() ? "none with a matching name" : android.text.TextUtils.join(", ", fs)).append("\n\n");
                }
            }
            final java.util.Map<String, Integer> before = CarData.get(c).snapshot(devs);
            final String list = names.length() == 0 ? "no matching commands found\n\n" : names.toString();
            ui.post(() -> {
                TextView t1 = Ui.text(c, list + "Next: with the car's OWN buttons, open or close the SUNSHADE a little and switch the READING LIGHT on, then tap Done.", 12, 0xFFFFFFFF, false);
                t1.setTextIsSelectable(true);
                t1.setPadding(32, 16, 32, 16);
                ScrollView sv = new ScrollView(c);
                sv.addView(t1);
                new AlertDialog.Builder(c).setTitle("Find car controls (1/2) — send a photo to Claude").setView(sv)
                        .setPositiveButton("Done", (dd, w) -> new Thread(() -> {
                            java.util.Map<String, Integer> after = CarData.get(c).snapshot(devs);
                            StringBuilder ch = new StringBuilder();
                            for (java.util.Map.Entry<String, Integer> e : after.entrySet()) {
                                Integer b = before.get(e.getKey());
                                if (b != null && !b.equals(e.getValue()))
                                    ch.append(e.getKey().replaceAll(".*\\.BYDAuto|Device", "")).append(": ").append(b).append(" → ").append(e.getValue()).append("\n");
                            }
                            final String changed = ch.length() == 0 ? "No reading changed." : ch.toString();
                            ui.post(() -> new AlertDialog.Builder(c).setTitle("Find car controls (2/2) — send a photo to Claude")
                                    .setMessage(changed).setPositiveButton("Close", null).show());
                        }).start())
                        .setNegativeButton("Close", null).show();
            });
        }).start();
    }

    /** Microphone + floating windows for Natee chat and the side camera: through the car's ADB, else Android's own pages. */
    private static void fixPerms(Context c) {
        android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        android.widget.Toast.makeText(c, "Giving NATEE the microphone and floating windows… if the car asks 'Allow USB debugging?', tick Always allow + OK", android.widget.Toast.LENGTH_LONG).show();
        new Thread(() -> {
            String err = AiChat.fixPermissions(c);
            ui.postDelayed(() -> {
                boolean mic = AiChat.micAllowed(c), ov = AiChat.overlayAllowed(c);
                if (mic && ov) {
                    android.widget.Toast.makeText(c, "Done: microphone and floating windows allowed", android.widget.Toast.LENGTH_LONG).show();
                    AiChat.sync(c);
                    return;
                }
                // ADB didn't work: open Android's own pages so the switch can be turned on by hand
                try {
                    if (!ov) c.startActivity(new android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + c.getPackageName())).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                    else c.startActivity(new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:" + c.getPackageName())).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                    android.widget.Toast.makeText(c, (ov ? "Turn on Microphone under Permissions" : "Turn on 'Display over other apps'")
                            + (err.isEmpty() ? "" : " (" + err + ")"), android.widget.Toast.LENGTH_LONG).show();
                } catch (Throwable t) {
                    android.widget.Toast.makeText(c, "Couldn't open the permission page (" + err + ")", android.widget.Toast.LENGTH_LONG).show();
                }
            }, 800);
        }).start();
    }

    /** Live: is the wake-word listener running, how loud is the microphone, what did it hear (send a photo to Claude). */
    private static void wakeCheck(Context c) {
        TextView t = Ui.text(c, "", 15, 0xFFFFFFFF, false);
        t.setPadding(40, 24, 40, 24);
        android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable tick = new Runnable() {
            @Override public void run() {
                int lv = AiChat.micLevel;
                StringBuilder bar = new StringBuilder();
                for (int i = 0; i < 20; i++) bar.append(lv >= 0 && i < lv / 5 ? "█" : "·");
                t.setText("Microphone permission: " + (AiChat.micAllowed(c) ? "yes" : "NO") + " · floating window permission: " + (AiChat.overlayAllowed(c) ? "yes" : "NO")
                        + "\nWake word: " + AiChat.wakeState
                        + "\nMicrophone: " + (lv < 0 ? "–" : lv + "  " + bar)
                        + "\nLast heard: " + (AiChat.lastHeard.isEmpty() ? "–" : "\"" + AiChat.lastHeard + "\"")
                        + "\n\nSay your assistant's name and watch the microphone bar. If it stays at 0 while you talk, another app"
                        + " (the car's own voice assistant) is holding the microphone.");
                if (t.isAttachedToWindow() || t.getWindowToken() == null) ui.postDelayed(this, 300);
            }
        };
        new AlertDialog.Builder(c).setTitle("Wake word check (send a photo to Claude)").setView(t)
                .setOnDismissListener(d -> ui.removeCallbacksAndMessages(null))
                .setPositiveButton("Close", null).show();
        ui.post(tick);
    }

    /**
     * BYD's own voice assistant opens from the wheel button alongside Quinn (the car starts it itself, so swallowing the
     * key doesn't stop it; Air 2026-11-23). Lists the car's voice apps; tap one to switch it off or on again (through the
     * car's ADB: pm disable-user / enable, nothing is deleted).
     */
    private static void bydVoice(Context c) {
        android.content.pm.PackageManager pm = c.getPackageManager();
        final java.util.List<String> pk = new java.util.ArrayList<>();
        final java.util.List<String> lab = new java.util.ArrayList<>();
        java.util.regex.Pattern want = java.util.regex.Pattern.compile("(?i).*(voice|speech|\\.vr|vrservice|assistant|aispeech|iflytek|txz|xiaodi|dilink.*voice).*");
        for (android.content.pm.ApplicationInfo ai : pm.getInstalledApplications(android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS)) {
            if (!want.matcher(ai.packageName).matches() || ai.packageName.equals(c.getPackageName())) continue;
            boolean on = ai.enabled;
            try { int st = pm.getApplicationEnabledSetting(ai.packageName); on = st != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER && st != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED; } catch (Throwable ignored) { }
            pk.add(ai.packageName);
            lab.add((on ? "ON   " : "OFF  ") + pm.getApplicationLabel(ai) + "  (" + ai.packageName + ")");
        }
        if (pk.isEmpty()) { new AlertDialog.Builder(c).setTitle("BYD voice assistant").setMessage("No voice app found on this car.").setPositiveButton("OK", null).show(); return; }
        new AlertDialog.Builder(c).setTitle("Tap the BYD voice app to switch it off / on (\"Hi BYD\" stops while it's off) — send a photo to Claude")
                .setItems(lab.toArray(new String[0]), (d, i) -> {
                    final String p = pk.get(i);
                    final boolean turnOff = lab.get(i).startsWith("ON");
                    KeyWatch.rememberOff(c, p, turnOff);   // kept off after every car start
                    new Thread(() -> {
                        String r;
                        try { r = Updater.adbShell(c, (turnOff ? "pm disable-user --user 0 " : "pm enable ") + p); }
                        catch (Exception e) { r = "couldn't reach the car's ADB (" + e.getClass().getSimpleName() + ")"; }
                        final String rr = r;
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                                android.widget.Toast.makeText(c, (turnOff ? "Off: " : "On: ") + p + " · " + rr, android.widget.Toast.LENGTH_LONG).show());
                    }).start();
                })
                .setPositiveButton("Close", null).show();
    }

    /** Switches the key watcher on (through the car's own ADB), then lists the keys it saw: press the wheel's mic button and tap it. */
    private static void wheelTest(Context c) {
        android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            KeyWatch.ensureOn(c);   // by the launcher itself when it may, else through the car's ADB
            try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
            String err = KeyWatch.isOn(c) ? null : "Watcher is not in Android's list — send a photo.";
            final String e2 = err;
            ui.post(() -> showKeys(c, e2));
        }).start();
    }

    /** The other accessibility services switched on (package names only): another app may be taking the wheel button. */
    private static String otherWatchers(Context c) {
        String cur = android.provider.Settings.Secure.getString(c.getContentResolver(), android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (cur == null || cur.isEmpty() || "null".equals(cur)) return "none";
        StringBuilder b = new StringBuilder();
        for (String x : cur.split(":")) {
            String pk = x.contains("/") ? x.substring(0, x.indexOf('/')) : x;
            if (pk.isEmpty() || pk.equals(c.getPackageName())) continue;
            if (b.length() > 0) b.append(", ");
            b.append(pk);
        }
        return b.length() == 0 ? "none" : b.toString();
    }

    private static void showKeys(Context c, String err) {
        String seen = KeyWatch.store(c).getString("seen", "");
        String after = KeyWatch.store(c).getString("afterPress", "");
        if (!after.isEmpty()) seen = "opened after the press: " + after + (seen.isEmpty() ? "" : "\n" + seen);
        seen = "listed: " + (KeyWatch.isOn(c) ? "yes" : "NO") + " · running: " + (KeyWatch.running() ? "yes" : "NO") + " · can fix itself: "
                + (c.checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED ? "yes" : "NO")
                + "\nother watchers: " + otherWatchers(c)
                + "\nlast switch: " + KeyWatch.store(c).getString("lastFix", "none")
                + "\nwatcher started " + KeyWatch.store(c).getString("connAt", "never") + " · now "
                + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date()) + (seen.isEmpty() ? "" : "\n" + seen);
        final String[] lines = seen.split("\n");
        AlertDialog.Builder b = new AlertDialog.Builder(c)
                .setTitle((err != null ? err + "\n" : "") + "Press the wheel's mic button, then tap Refresh. Tap the key it sent to use it:");
        if (lines.length == 0) b.setMessage("No button press seen yet.");
        else b.setItems(lines, (d, i) -> {
            try {
                int code = Integer.parseInt(lines[i].split(" ")[0]);
                KeyWatch.store(c).edit().putInt("chosen", code).apply();
                android.widget.Toast.makeText(c, "Wheel button set: key " + code, android.widget.Toast.LENGTH_LONG).show();
            } catch (Exception ignored) { }
        });
        b.setNeutralButton("Refresh", (d, i) -> showKeys(c, null))
                .setNegativeButton("Usual voice keys", (d, i) -> KeyWatch.store(c).edit().remove("chosen").apply())
                .setPositiveButton("Close", null).show();
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
