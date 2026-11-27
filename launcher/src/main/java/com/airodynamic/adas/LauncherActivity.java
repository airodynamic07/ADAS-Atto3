package com.airodynamic.adas;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The home screen: three windows and the trip graph (see rebuild()).
 * Other apps open full screen (this car can't put them in a window); Home comes back here.
 */
public class LauncherActivity extends Activity implements CarData.Listener, SettingsDialog.Host {

    /** Sent to the Engine Sound app so it can match the launcher's colours. */
    public static final String ACTION_THEME = "com.airodynamic.adas.THEME";
    public static final String ACTION_THEME_REQUEST = "com.airodynamic.adas.THEME_REQUEST";

    private Prefs prefs;
    private Theme theme;            // what is on screen now (may be Sport via Auto Sport)
    /** Engine Sound + Ghost Race, kept alive for the whole launcher session (sound never restarts). */
    private EngineHost engineHost;

    private EngineHost engineHost() {
        if (engineHost == null) {
            engineHost = new EngineHost(this);
            engineHost.setGhostListener(open -> rebuild());   // Ghost Race opens wide, closes back
            engineHost.setGhostArea(this::ghostArea);
            engineHost.setWindowAreas(() -> box(frames[2]), () -> box(frames[1]));
        }
        return engineHost;
    }
    private final android.content.BroadcastReceiver ghostStopped = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, android.content.Intent i) { if (engineHost != null) engineHost.ghostWindowClosed(); }
    };
    private boolean sportOn;        // Auto Sport currently active
    private int[] winOrder = {2, 1, 0};
    /** Screen box covering the first two windows (left + middle), for the Ghost Race window. */
    private int[] box(View v) {
        if (v == null || v.getWidth() == 0) return null;
        int[] l = new int[2]; v.getLocationOnScreen(l);
        return new int[]{l[0], l[1], v.getWidth(), v.getHeight()};
    }
    private int[] ghostArea() {
        View a = frames[2], b = frames[1];   // info + engine (the trip window is never covered)
        if (a == null || b == null || a.getWidth() == 0) return null;
        int[] la = new int[2], lb = new int[2];
        a.getLocationOnScreen(la); b.getLocationOnScreen(lb);
        int x0 = Math.min(la[0], lb[0]), y0 = Math.min(la[1], lb[1]);
        int x1 = Math.max(la[0] + a.getWidth(), lb[0] + b.getWidth()), y1 = Math.max(la[1] + a.getHeight(), lb[1] + b.getHeight());
        return new int[]{x0, y0, x1 - x0, y1 - y0};
    }
    private final CameraFeed feed = new CameraFeed();
    private final Panel[] panels = new Panel[3];
    private TurnCamera turnCam;
    private final FrameLayout[] bodies = new FrameLayout[3];
    private final LinearLayout[] frames = new LinearLayout[3];
    private boolean resumed;

    private final BroadcastReceiver themeRequest = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { sendTheme(); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ambient.get(this);   // ambient light by gear, keeps working while other apps are open
        Ambient.onChange = ambientChanged;   // the theme can follow the ambient colour
        checkUpdates.run();
        prefs = new Prefs(this);
        SpeedAlert.get(this);   // my speed limit warning, also while other apps are open
        TripEnergy.get(this);   // counts this trip's energy even while other apps are open
        AiChat.sync(this);      // "Hey Natee chat" wake word, when switched on in Settings
        turnCam = new TurnCamera(this, feed, prefs);
        registerReceiver(themeRequest, new IntentFilter(ACTION_THEME_REQUEST));
        registerReceiver(ghostStopped, new IntentFilter(GhostActivity.ACTION_STOPPED));
        rebuild();
        ensureLocation();
        takeShare(getIntent());
        takeVoice(getIntent());
    }

    /** A place shared to NATEE (ShareActivity): open Ghost Race on it. */
    private void takeShare(Intent i) {
        if (i == null || (!i.hasExtra(ShareActivity.TEXT) && !i.hasExtra(ShareActivity.TITLE))) return;
        String text = i.getStringExtra(ShareActivity.TEXT), title = i.getStringExtra(ShareActivity.TITLE);
        i.removeExtra(ShareActivity.TEXT); i.removeExtra(ShareActivity.TITLE);
        String share = (text == null || text.isEmpty() ? "" : "&share_text=" + android.net.Uri.encode(text))
                + (title == null || title.isEmpty() ? "" : "&share_title=" + android.net.Uri.encode(title));
        if (share.isEmpty()) return;
        final String s = share;
        // after the screen is laid out, so the Ghost Race window gets its place
        getWindow().getDecorView().postDelayed(() -> engineHost().shareToGhost(s), 600);
    }

    /**
     * Location was never actually switched on for the launcher (2026-11-11: Ghost Race said "couldn't
     * get a location fix" while Firefox found it). Switch it on through the car's own ADB, like Now
     * playing; if that fails, show Android's normal question. Then start GPS again.
     */
    private void ensureLocation() {
        final String fine = android.Manifest.permission.ACCESS_FINE_LOCATION;
        if (checkSelfPermission(fine) == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        new Thread(() -> {
            try {
                Updater.adbShell(this, "pm grant " + getPackageName() + " android.permission.ACCESS_FINE_LOCATION; pm grant "
                        + getPackageName() + " android.permission.ACCESS_COARSE_LOCATION");
            } catch (Throwable ignored) { }
            runOnUiThread(() -> {
                if (checkSelfPermission(fine) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    TripEnergy.get(this).restartGps(this);
                    android.widget.Toast.makeText(this, "Location switched on for NATEE", android.widget.Toast.LENGTH_SHORT).show();
                } else {
                    try { requestPermissions(new String[]{fine, android.Manifest.permission.ACCESS_COARSE_LOCATION}, 7); } catch (Throwable ignored) { }
                }
            });
        }).start();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 7) TripEnergy.get(this).restartGps(this);
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(themeRequest); } catch (Exception ignored) {}
        try { unregisterReceiver(ghostStopped); } catch (Exception ignored) {}
        if (turnCam != null) turnCam.release();
        super.onDestroy();
    }

    @Override public void onBackPressed() { /* home screen: nothing behind it */ }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        takeShare(intent);
        takeVoice(intent);
    }

    /** Voice commands for the middle window (Air 2026-11-26): Engine Sound on/off, Ghost Race big / modes / route options. */
    static final String VOICE = "natee_voice";
    private void takeVoice(Intent i) {
        String v = i == null ? null : i.getStringExtra(VOICE);
        if (v == null) return;
        i.removeExtra(VOICE);
        getWindow().getDecorView().postDelayed(() -> {
            if (v.equals("engine_on") || v.equals("engine_off")) engineHost().engine(v.equals("engine_on"));
            else if (v.equals("swap")) { prefs.setInfoLeft(!prefs.infoLeft()); rebuild(); }
            else if (v.equals("theme:AMBIENT")) { prefs.setThemeAmbient(true); rebuild(); }
            else if (v.startsWith("theme:")) { try { prefs.setTheme(Theme.valueOf(v.substring(6))); rebuild(); } catch (Throwable ignored) { } }
            else if (v.startsWith("sound:")) engineHost().chooseSound(v.substring(6));
            else if (v.startsWith("gauge:")) engineHost().chooseGauge(v.substring(6));
            else if (v.startsWith("level:")) {   // level:<part>:<0-5 or -1>:<delta>
                String[] p = v.split(":");
                try { engineHost().soundLevel(p[1], Integer.parseInt(p[2]), Integer.parseInt(p[3])); } catch (Throwable ignored) { }
            }
            else if (v.startsWith("ghost:")) engineHost().shareToGhost("&ghost_cmd=" + android.net.Uri.encode(v.substring(6)));
        }, 600);
    }

    /** newest build on GitHub when it is newer than this one (else 0); checked at start and every 6 hours */
    static volatile int updateAvailable;
    private final android.os.Handler updH = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable checkUpdates = new Runnable() {
        @Override public void run() {
            new Thread(() -> {
                int latest = Updater.latestBuild();
                int mine = 0;
                try { mine = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; } catch (Exception ignored) { }
                int avail = latest > mine ? latest : 0;
                if (avail != updateAvailable) { updateAvailable = avail; runOnUiThread(() -> rebuild()); }
            }).start();
            updH.postDelayed(this, 6 * 3600_000L);
        }
    };

    private Theme effectiveTheme() {
        if (prefs.themeAmbient()) {   // follows the ambient light (Auto Sport too: its ambient colour), Air 2026-11-26
            if (sportOn && !(prefs.ambientOver())) return Theme.RED;
            int col = Ambient.current;
            if (col >= 1 && col <= SettingsDialog.BYD_COLOURS.length) return Theme.nearest(SettingsDialog.BYD_COLOURS[col - 1]);
            return prefs.theme();
        }
        return sportOn ? Theme.RED : prefs.theme();
    }
    /** the ambient colour changed: rebuild in the new colour when the theme follows it */
    // (Air 2026-11-27 crash: the ambient light's own thread rebuilt the screen) always on the screen's thread
    private final Runnable ambientChanged = () -> runOnUiThread(() -> { if (prefs.themeAmbient() && effectiveTheme() != theme) rebuild(); });

    /**
     * Builds the whole screen for the current theme (user's layout, 2026-11-10):
     * no clock line and no app bars; left = ChatGPT + shortcuts + trip energy,
     * middle = Engine Sound (side camera covers it while signalling), right = Info,
     * and the trip graph along the bottom.
     */
    /** NATEE Settings on top (Air 2026-11-22: the Ghost Race map covered it): Ghost Race steps back while it's open. */
    private boolean settingsOpen;
    private void openSettings() {
        if (engineHost != null && engineHost.ghostOpen()) {
            settingsOpen = true;
            sendBroadcast(new Intent(GhostActivity.ACTION_HIDE).setPackage(getPackageName()));
        }
        SettingsDialog.show(this, theme, prefs, this);
    }

    @Override public void settingsClosed() {
        if (!settingsOpen) return;
        settingsOpen = false;
        if (engineHost != null) engineHost.reshowGhost();
    }

    @Override public void rebuild() {
        for (Panel p : panels) if (p != null) p.setActive(false);
        theme = effectiveTheme();
        if (engineHost != null) engineHost.setTheme(theme);
        Theme th = theme;
        getWindow().setStatusBarColor(th.bg);
        getWindow().setNavigationBarColor(th.bg);

        // (user, 2026-11-13) the trip window (right) reaches the bottom of the screen; the other two windows
        // and the graph share the rest: [ [info | engine] over [graph] ] [trip]
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(th.bg);
        int pad = Ui.dp(this, 10);
        root.setPadding(pad, Ui.dp(this, 4), pad, Ui.dp(this, 6));
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        side.addView(row, new LinearLayout.LayoutParams(-1, 0, 1f));
        panels[0] = new DrivePanel(this, th, prefs, themeControls(th));
        ((DrivePanel) panels[0]).setRouteOpener(() -> { routeOpen = true; showRoute(); });
        ((DrivePanel) panels[0]).setHistoryOpener(() -> { historyOpen = true; showHistory(); });
        panels[1] = new EnginePanel(engineHost(), th);
        panels[2] = new InfoPanel(this, th, prefs);
        // Ghost Race open: it fills the left + middle windows; the right window and the graph stay
        // (user, 2026-11-12).
        boolean wide = engineHost().ghostOpen() && !engineHost().ghostInWindow();
        // Window order: Info left and ChatGPT/energy right by default (user, 2026-11-10); swappable in Settings.
        int[] order = prefs.infoLeft() ? new int[]{2, 1, 0} : new int[]{0, 1, 2};
        winOrder = order;
        for (int i = 0; i < 3; i++) {
            frames[i] = new LinearLayout(this);
            frames[i].setOrientation(LinearLayout.VERTICAL);
            frames[i].setBackground(Ui.round(i == 1 ? th.eng : th.panel, th.line, 20, this));
            frames[i].setClipToOutline(true);
            bodies[i] = new FrameLayout(this);
            bodies[i].addView(panels[i].view(), new FrameLayout.LayoutParams(-1, -1));
            frames[i].addView(bodies[i], new LinearLayout.LayoutParams(-1, -1));
        }
        boolean first = true;
        for (int i : order) {
            if (i == 0) continue;           // the trip window has the full height (added below)
            if (wide && i == 2) continue;   // Info is hidden under Ghost Race
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, wide && i == 1 ? 2.03f : 1f);
            if (!first) lp.leftMargin = Ui.dp(this, 10);
            first = false;
            row.addView(frames[i], lp);
        }
        // remember where the Engine Sound window is: the Natee chat box opens there, the same size (Air 2026-11-22)
        if (!wide) frames[1].addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (v.getWidth() <= 0) return;
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            getSharedPreferences("chat_box", MODE_PRIVATE).edit().putInt("x", loc[0]).putInt("y", loc[1])
                    .putInt("w", v.getWidth()).putInt("h", v.getHeight()).apply();
        });
        engineHost().addGhostClose(th);
        if (routeOpen && !wide) showRoute();   // stays open across a theme change
        if (historyOpen) showHistory();
        turnCam.attach(bodies[1], th);

        FrameLayout graphBox = new FrameLayout(this);
        graphBox.setBackground(Ui.round(th.eng, th.line, 20, this));
        graphBox.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 2));
        graphBox.addView(new TripGraph(this, th), new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 132));
        glp.topMargin = Ui.dp(this, 8);
        side.addView(graphBox, glp);
        boolean tripRight = order[2] == 0;
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, -1, wide ? 2.03f : 2.03f);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -1, 1f);
        if (tripRight) { tp.leftMargin = Ui.dp(this, 10); root.addView(side, sp); root.addView(frames[0], tp); }
        else { sp.leftMargin = Ui.dp(this, 10); root.addView(frames[0], tp); root.addView(side, sp); }   // the graph stays across the bottom with Ghost Race open too (user, 2026-11-12)

        setContentView(root);
        for (int i = 0; i < 3; i++) panels[i].onCar(CarData.get(this).last());
        // also while Ghost Race's window is on top (the launcher is paused but the right window is visible):
        // a theme change rebuilt the panels and they stayed empty until Ghost Race closed (2026-11-12)
        if (resumed || engineHost().ghostInWindow()) for (Panel p : panels) p.setActive(true);
        sendTheme();
    }

    // Driving history: covers the right window (the Today card opens it); ✕ closes it
    private boolean historyOpen;
    private HistoryOverlay history;
    private void showHistory() {
        if (history != null && history.getParent() != null) ((android.view.ViewGroup) history.getParent()).removeView(history);
        history = new HistoryOverlay(this, theme, () -> {
            historyOpen = false;
            if (history != null && history.getParent() != null) ((android.view.ViewGroup) history.getParent()).removeView(history);
            history = null;
        });
        bodies[0].addView(history, new FrameLayout.LayoutParams(-1, -1));
    }

    // Today's route map: covers the middle window (Engine Sound keeps playing underneath); ✕ closes it
    private boolean routeOpen;
    private android.view.View route;
    private void showRoute() {   // "Every day Map": the months / days list first (Air 2026-11-23)
        if (route != null && route.getParent() != null) ((android.view.ViewGroup) route.getParent()).removeView(route);
        route = new MapDaysOverlay(this, theme, this::closeRoute, this::showRouteDay);
        bodies[1].addView(route, new FrameLayout.LayoutParams(-1, -1));
    }

    private void showRouteDay(String day) {
        if (route != null && route.getParent() != null) ((android.view.ViewGroup) route.getParent()).removeView(route);
        route = new RouteOverlay(this, theme, this::closeRoute, day, this::showRoute);
        bodies[1].addView(route, new FrameLayout.LayoutParams(-1, -1));
    }

    private void closeRoute() {
        routeOpen = false;
        if (route != null && route.getParent() != null) ((android.view.ViewGroup) route.getParent()).removeView(route);
        route = null;
    }

    /** "Theme ▾" (Air 2026-11-26, was three dots): a menu with Match ambient light on top, then every colour. */
    private LinearLayout themeControls(Theme th) {
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(Ui.round(th.card, th.line, 14, this));
        box.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 2), 0);
        LinearLayout tb = new LinearLayout(this);
        tb.setGravity(Gravity.CENTER);
        View sw = new View(this);
        sw.setBackground(prefs.themeAmbient() ? rainbow(11) : Ui.round(th.swatch(), th.line, 11, this));
        tb.addView(sw, new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20)));
        android.widget.TextView tl = Ui.text(this, "  Theme ▾", 15, th.text, true);
        tb.addView(tl);
        tb.setOnClickListener(v -> themeMenu(v, th));
        box.addView(tb, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f));
        // (Air 2026-11-26) a newer version on GitHub: the ⚙ button shows ⬆ (Settings → About installs it)
        android.widget.Button settings = Ui.chip(this, updateAvailable > 0 ? "⚙⬆" : "⚙", updateAvailable > 0, th, v -> openSettings());
        settings.setContentDescription("Settings");
        box.addView(settings, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 40)));
        return box;
    }

    private android.graphics.drawable.Drawable rainbow(int radiusDp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{0xFFFF0000, 0xFFFFA500, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF});
        g.setCornerRadius(Ui.dp(this, radiusDp));
        return g;
    }

    private void themeMenu(View anchor, Theme th) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(Ui.round(th.chip, th.line, 14, this));
        final android.widget.PopupWindow[] pw = new android.widget.PopupWindow[1];
        java.util.List<Object[]> items = new java.util.ArrayList<>();
        items.add(new Object[]{null, "Match ambient light", prefs.themeAmbient()});
        for (Theme t : Theme.values()) items.add(new Object[]{t, t.label(), !prefs.themeAmbient() && prefs.theme() == t});
        for (Object[] it : items) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 18), Ui.dp(this, 10));
            if ((Boolean) it[2]) row.setBackgroundColor(th.line);
            View d = new View(this);
            d.setBackground(it[0] == null ? rainbow(9) : Ui.round(((Theme) it[0]).swatch(), th.line, 9, this));
            row.addView(d, new LinearLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 18)));
            row.addView(Ui.text(this, "   " + it[1] + ((Boolean) it[2] ? "  ✓" : ""), 16, th.text, false));
            final Theme t = (Theme) it[0];
            row.setOnClickListener(v -> {
                pw[0].dismiss();
                if (t == null) prefs.setThemeAmbient(true); else prefs.setTheme(t);
                rebuild();
            });
            list.addView(row);
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(list);
        pw[0] = new android.widget.PopupWindow(sv, Ui.dp(this, 260), Math.min(Ui.dp(this, 440), getWindow().getDecorView().getHeight() - Ui.dp(this, 40)), true);
        pw[0].setOutsideTouchable(true);
        pw[0].setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        pw[0].showAsDropDown(anchor);
    }

    @Override protected void onResume() {
        super.onResume();
        // Ghost Race keeps running in its own window (it only ends with its ■ Stop): show it again on top
        if (engineHost != null) { engineHost.setForeground(true); if (!settingsOpen) getWindow().getDecorView().post(engineHost::reshowGhost); }
        resumed = true;
        CarData.get(this).addListener(this);
        turnCam.setAllowed(true);
        for (Panel p : panels) if (p != null) p.setActive(true);
        sendTheme();
    }

    @Override protected void onPause() {
        super.onPause();
        if (engineHost != null) engineHost.setForeground(false);   // keep the engine sound going
        resumed = false;
        // Ghost Race's own window is on top but the right window and graph are still visible: keep them live
        if (engineHost != null && engineHost.ghostOpen()) { turnCam.setAllowed(false); feed.stop(); return; }
        CarData.get(this).removeListener(this);
        for (Panel p : panels) if (p != null) p.setActive(false);
        turnCam.setAllowed(false);
        feed.stop();
    }

    private int lastGear = -1;

    @Override public void onCar(CarData.State s) {
        // Where I parked: remember the spot whenever the car goes into P
        boolean nowP = s.gear == 0 || s.gear == 1;
        if (nowP && lastGear > 1) {
            android.location.Location loc = Places.last(this);
            if (loc != null) prefs.setParked(loc.getLatitude(), loc.getLongitude(), System.currentTimeMillis());
        }
        if (s.gear >= 0) lastGear = s.gear;
        // Auto Sport: black-red above the set speed, back 10 km/h below it
        if (prefs.autoSport() && !Double.isNaN(s.speedKmh)) {
            boolean want = sportOn ? s.speedKmh > prefs.autoSportKmh() - 10 : s.speedKmh > prefs.autoSportKmh();
            if (want != sportOn) { sportOn = want; rebuild(); return; }
        } else if (sportOn) { sportOn = false; rebuild(); return; }
        // (turn camera listens to the car itself, also while other apps are in front)
        for (Panel p : panels) if (p != null) p.onCar(s);
    }

    /** Tell Engine Sound which colours to use (it switches live, even in the background). */
    private void sendTheme() {
        Intent i = new Intent(ACTION_THEME).setPackage(Prefs.ENGINE_PKG);
        i.putExtra("theme", theme == Theme.RED ? "red" : theme == Theme.WHITE ? "white" : "blue");
        sendBroadcast(i);
    }

    @Override public void log(String s) {
        runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }

    /** Update download: a small window with a bar, % and MB (Air 2026-11-23). */
    private android.app.AlertDialog updDlg;
    private android.widget.ProgressBar updBar;
    private TextView updText;
    @Override public void progress(int pct, String text) {
        runOnUiThread(() -> {
            if (pct < 0) { if (updDlg != null) updDlg.dismiss(); updDlg = null; return; }
            if (updDlg == null) {
                LinearLayout box = new LinearLayout(this);
                box.setOrientation(LinearLayout.VERTICAL);
                int p = Ui.dp(this, 20);
                box.setPadding(p, p, p, p);
                updText = Ui.text(this, "", 15, theme.text, false);
                box.addView(updText);
                updBar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                updBar.setMax(100);
                LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, Ui.dp(this, 16));
                bl.topMargin = Ui.dp(this, 12);
                box.addView(updBar, bl);
                updDlg = new android.app.AlertDialog.Builder(this).setTitle("Updating NATEE Launcher").setView(box)
                        .setPositiveButton("Hide", null).show();
            }
            updBar.setProgress(pct);
            updText.setText(text);
        });
    }
}
