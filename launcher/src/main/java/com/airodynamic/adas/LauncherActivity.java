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
 * The home screen: top strip (time, date) + three windows, each with its own bar.
 * No title bar; the theme dots and Settings sit at the bottom-left of the left window.
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
        if (engineHost == null) engineHost = new EngineHost(this);
        return engineHost;
    }
    private boolean sportOn;        // Auto Sport currently active
    private final CameraFeed feed = new CameraFeed();
    private final Panel[] panels = new Panel[3];
    private final String[] showing = new String[3];
    private final FrameLayout[] bodies = new FrameLayout[3];
    private final LinearLayout[] frames = new LinearLayout[3];
    private int fullscreen = -1;
    private boolean resumed;
    private TextView toast;

    private final BroadcastReceiver themeRequest = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { sendTheme(); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        for (int i = 0; i < 3; i++) showing[i] = prefs.defaultPanel(i);
        registerReceiver(themeRequest, new IntentFilter(ACTION_THEME_REQUEST));
        rebuild();
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(themeRequest); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override public void onBackPressed() { /* home screen: nothing behind it */ }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: leave full screen
        if (fullscreen >= 0) { fullscreen = -1; rebuild(); }
    }

    private Theme effectiveTheme() { return sportOn ? Theme.RED : prefs.theme(); }

    /** Builds the whole screen for the current theme. */
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
        int pad = Ui.dp(this, 14);
        root.setPadding(pad, Ui.dp(this, 6), pad, pad);

        // ---- top strip
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("HH:mm");
        time.setTextSize(26);
        time.setTextColor(th.text);
        time.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        TextClock date = new TextClock(this);
        date.setFormat24Hour("  EEEE d MMMM");
        date.setFormat12Hour("  EEEE d MMMM");
        date.setTextSize(14);
        date.setTextColor(th.muted);
        top.addView(time);
        top.addView(date);
        toast = Ui.text(this, "", 13, th.muted, false);
        toast.setPadding(Ui.dp(this, 16), 0, 0, 0);
        top.addView(toast, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(top);

        // ---- three windows
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, 0, 1f);
        rlp.topMargin = Ui.dp(this, 6);
        root.addView(row, rlp);
        for (int i = 0; i < 3; i++) {
            frames[i] = buildWindow(i, th);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
            if (i > 0) lp.leftMargin = Ui.dp(this, 12);
            frames[i].setVisibility(fullscreen < 0 || fullscreen == i ? View.VISIBLE : View.GONE);
            row.addView(frames[i], lp);
        }
        setContentView(root);
        for (int i = 0; i < 3; i++) panels[i].onCar(CarData.get(this).last());
        if (resumed) for (Panel p : panels) p.setActive(true);
        sendTheme();
    }

    private LinearLayout buildWindow(int win, Theme th) {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setBackground(Ui.round(th.panel, th.line, 20, this));
        f.setClipToOutline(true);

        LinearLayout bar = new LinearLayout(this);
        int bp = Ui.dp(this, 8);
        bar.setPadding(bp, bp, bp, bp);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        for (int s = 0; s < 3; s++) {
            final String item = prefs.slot(win, s);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.rightMargin = Ui.dp(this, 4);
            bar.addView(Ui.chip(this, Apps.label(this, item), item.equals(showing[win]), th, v -> {
                if (item.startsWith("@")) { showing[win] = item; rebuild(); }
                else Apps.open(this, item, CarData.get(this).last());
            }), lp);
        }
        LinearLayout.LayoutParams sq = new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44));
        sq.rightMargin = Ui.dp(this, 4);
        bar.addView(Ui.chip(this, "＋", false, th, v -> AppPicker.show(this, theme, prefs, win, this::rebuild)), sq);
        bar.addView(Ui.chip(this, fullscreen == win ? "⤡" : "⤢", false, th, v -> {
            fullscreen = fullscreen == win ? -1 : win;
            rebuild();
        }), sq);
        bar.addView(Ui.chip(this, "×", false, th, v -> {
            showing[win] = prefs.defaultPanel(win);
            if (fullscreen == win) fullscreen = -1;
            rebuild();
        }), new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        f.addView(bar);
        View line = new View(this);
        line.setBackgroundColor(th.line);
        f.addView(line, new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));

        bodies[win] = new FrameLayout(this);
        String what = showing[win];
        Panel p = Prefs.ADAS.equals(what) ? new AdasPanel(this, th, prefs, feed)
                : Prefs.ENGINE.equals(what) ? new EnginePanel(engineHost(), th)
                : new InfoPanel(this, th, prefs);
        panels[win] = p;
        bodies[win].addView(p.view(), new FrameLayout.LayoutParams(-1, -1));
        f.addView(bodies[win], new LinearLayout.LayoutParams(-1, 0, 1f));
        // Theme dots + Settings live at the bottom-left of the left window (user's layout, 2026-11-10)
        if (win == 0) {
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
            clp.setMargins(Ui.dp(this, 6), 0, 0, Ui.dp(this, 4));
            bodies[win].addView(themeControls(th), clp);
        }
        return f;
    }

    /** Blue / white / red dots and the Settings button. */
    private LinearLayout themeControls(Theme th) {
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(Ui.round(th.panel, th.line, 22, this));
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
            box.addView(hit, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        }
        android.widget.Button settings = Ui.chip(this, "⚙", false, th, v -> SettingsDialog.show(this, theme, prefs, this));
        settings.setContentDescription("Settings");
        box.addView(settings, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 44)));
        return box;
    }

    @Override protected void onResume() {
        super.onResume();
        if (engineHost != null) engineHost.setForeground(true);
        resumed = true;
        CarData.get(this).addListener(this);
        for (Panel p : panels) if (p != null) p.setActive(true);
        sendTheme();
    }

    @Override protected void onPause() {
        super.onPause();
        if (engineHost != null) engineHost.setForeground(false);   // keep the engine sound going
        resumed = false;
        CarData.get(this).removeListener(this);
        for (Panel p : panels) if (p != null) p.setActive(false);
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
        for (Panel p : panels) if (p != null) p.onCar(s);
    }

    /** Tell Engine Sound which colours to use (it switches live, even in the background). */
    private void sendTheme() {
        Intent i = new Intent(ACTION_THEME).setPackage(Prefs.ENGINE_PKG);
        i.putExtra("theme", theme == Theme.RED ? "red" : theme == Theme.WHITE ? "white" : "blue");
        sendBroadcast(i);
    }

    @Override public void log(String s) {
        runOnUiThread(() -> {
            if (toast != null) toast.setText(s);
            Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        });
    }
}
