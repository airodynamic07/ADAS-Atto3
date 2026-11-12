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
        }
        return engineHost;
    }
    private boolean sportOn;        // Auto Sport currently active
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
        prefs = new Prefs(this);
        SpeedAlert.get(this);   // my speed limit warning, also while other apps are open
        TripEnergy.get(this);   // counts this trip's energy even while other apps are open
        turnCam = new TurnCamera(this, feed, prefs);
        registerReceiver(themeRequest, new IntentFilter(ACTION_THEME_REQUEST));
        rebuild();
        ensureLocation();
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
        super.onDestroy();
    }

    @Override public void onBackPressed() { /* home screen: nothing behind it */ }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
    }

    private Theme effectiveTheme() { return sportOn ? Theme.RED : prefs.theme(); }

    /**
     * Builds the whole screen for the current theme (user's layout, 2026-11-10):
     * no clock line and no app bars; left = ChatGPT + shortcuts + trip energy,
     * middle = Engine Sound (side camera covers it while signalling), right = Info,
     * and the trip graph along the bottom.
     */
    @Override public void rebuild() {
        for (Panel p : panels) if (p != null) p.setActive(false);
        theme = effectiveTheme();
        if (engineHost != null) engineHost.setTheme(theme);
        Theme th = theme;
        getWindow().setStatusBarColor(th.bg);
        getWindow().setNavigationBarColor(th.bg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(th.bg);
        int pad = Ui.dp(this, 10);
        root.setPadding(pad, Ui.dp(this, 4), pad, Ui.dp(this, 6));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row, new LinearLayout.LayoutParams(-1, 0, 1f));
        panels[0] = new DrivePanel(this, th, prefs, themeControls(th));
        ((DrivePanel) panels[0]).setRouteOpener(() -> { routeOpen = true; showRoute(); });
        panels[1] = new EnginePanel(engineHost(), th);
        panels[2] = new InfoPanel(this, th, prefs);
        // Ghost Race open: it fills the left + middle windows; the right window and the graph stay
        // (user, 2026-11-12).
        boolean wide = engineHost().ghostOpen();
        // Window order: Info left and ChatGPT/energy right by default (user, 2026-11-10); swappable in Settings.
        int[] order = prefs.infoLeft() ? new int[]{2, 1, 0} : new int[]{0, 1, 2};
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
            if (wide && i == 2) continue;   // Info is hidden under Ghost Race
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, wide && i == 1 ? 2.03f : 1f);
            if (!first) lp.leftMargin = Ui.dp(this, 10);
            first = false;
            row.addView(frames[i], lp);
        }
        engineHost().addGhostClose(th);
        if (routeOpen && !wide) showRoute();   // stays open across a theme change
        turnCam.attach(bodies[1], th);

        FrameLayout graphBox = new FrameLayout(this);
        graphBox.setBackground(Ui.round(th.eng, th.line, 20, this));
        graphBox.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 2));
        graphBox.addView(new TripGraph(this, th), new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 132));
        glp.topMargin = Ui.dp(this, 8);
        root.addView(graphBox, glp);   // the graph stays across the bottom with Ghost Race open too (user, 2026-11-12)

        setContentView(root);
        for (int i = 0; i < 3; i++) panels[i].onCar(CarData.get(this).last());
        if (resumed) for (Panel p : panels) p.setActive(true);
        sendTheme();
    }

    // Today's route map: covers the middle window (Engine Sound keeps playing underneath); ✕ closes it
    private boolean routeOpen;
    private RouteOverlay route;
    private void showRoute() {
        if (route != null && route.getParent() != null) ((android.view.ViewGroup) route.getParent()).removeView(route);
        route = new RouteOverlay(this, theme, () -> {
            routeOpen = false;
            if (route != null && route.getParent() != null) ((android.view.ViewGroup) route.getParent()).removeView(route);
            route = null;
        });
        bodies[1].addView(route, new FrameLayout.LayoutParams(-1, -1));
    }

    /** Blue / white / red dots and the Settings button. */
    private LinearLayout themeControls(Theme th) {
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(Ui.round(th.card, th.line, 14, this));
        box.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 2), 0);
        for (Theme t : Theme.values()) {
            View dot = new View(this);
            boolean sel = prefs.theme() == t;
            dot.setBackground(Ui.round(t == Theme.WHITE ? 0xFFFFFFFF : t == Theme.RED ? 0xFFE5202E : 0xFF2E8BFF,
                    sel ? th.text : th.line, 11, this));
            FrameLayout hit = new FrameLayout(this);
            hit.setContentDescription(t.name() + " theme");
            hit.addView(dot, new FrameLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22), Gravity.CENTER));
            hit.setOnClickListener(v -> { prefs.setTheme(t); rebuild(); });
            box.addView(hit, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f));   // share the width
        }
        android.widget.Button settings = Ui.chip(this, "⚙", false, th, v -> SettingsDialog.show(this, theme, prefs, this));
        settings.setContentDescription("Settings");
        box.addView(settings, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 40)));
        return box;
    }

    @Override protected void onResume() {
        super.onResume();
        if (engineHost != null) { engineHost.setForeground(true); engineHost.ghostWindowClosed(); }
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
        turnCam.onCar(s);
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
}
