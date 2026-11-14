package com.airodynamic.adas;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Ghost Race on Firefox's engine, in its OWN process (":ghost"). If the Firefox engine crashes,
 * only this window closes: the launcher keeps running and the "Select a Home app" box can't appear
 * (2026-11-11: the engine inside the launcher took the whole launcher down).
 * The window sits exactly over the launcher's Ghost Race area; the rest of the launcher stays usable.
 */
public class GhostActivity extends Activity {
    static final String X = "x", Y = "y", W = "w", H = "h", THEME = "theme";
    /** Sent by the launcher when its colour changes (theme dots or Auto Sport). */
    static final String ACTION_THEME = "com.airodynamic.adas.GHOST_THEME";
    private String baseUrl;
    private FrameLayout frame;
    /** Window frame in the launcher's colour: blue, red, or black for the white theme (user, 2026-11-12). */
    private void setFrame(String t) {
        int col = "red".equals(t) ? 0xFF6B1018 : "white".equals(t) ? 0xFF9AA0A8 : 0xFF1C3D78;   // dark red / black / dark blue (user, 2026-11-12)
        if (frame == null) return;
        android.graphics.drawable.GradientDrawable g = Ui.round(Color.BLACK, 0, 18, this);
        g.setStroke(Ui.dp(this, 3), col);
        frame.setBackground(g);
    }
    private final android.content.BroadcastReceiver themeRx = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, Intent i) {
            String t = i.getStringExtra(THEME);
            if (t != null) { theme = t; setFrame(t); if (gecko != null) gecko.load(baseUrl + "#natee=" + t + (mode == 0 ? "" : "&mini=1")); }
        }
    };
    private GeckoGhost gecko;

    static final String AREAS = "areas", ACTION_STOPPED = "com.airodynamic.adas.GHOST_STOPPED";
    /**
     * @param areas screen boxes {x, y, w, h} x3: big (windows 1+2), window 1, window 2. Ghost Race runs in its own
     *              task, so going Home or opening another app never ends it: it keeps recording (user, 2026-11-13).
     */
    static void open(Activity from, int[] areas, String theme) {
        Intent i = new Intent(from, GhostActivity.class).putExtra(AREAS, areas).putExtra(THEME, theme)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        from.startActivity(i);
        from.overridePendingTransition(0, 0);
    }

    // 0 = big (windows 1+2, all of Ghost Race), 1 = small in window 1, 2 = small in window 2 (map only)
    private int mode;
    private int[] areas;
    private String theme = "blue";
    private android.widget.Button bSmall, bStop, bEnlarge, bMove, bAgain;
    private TextView rec;

    private void place() {
        if (areas == null || areas.length < 12) return;
        int o = mode * 4;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.x = areas[o]; lp.y = areas[o + 1]; lp.width = areas[o + 2]; lp.height = areas[o + 3];
        getWindow().setAttributes(lp);
        boolean big = mode == 0;
        bSmall.setVisibility(big ? android.view.View.VISIBLE : android.view.View.GONE);
        bStop.setVisibility(big ? android.view.View.VISIBLE : android.view.View.GONE);
        bAgain.setVisibility(android.view.View.GONE);
        bEnlarge.setVisibility(big ? android.view.View.GONE : android.view.View.VISIBLE);
        bMove.setVisibility(big ? android.view.View.GONE : android.view.View.VISIBLE);
        rec.setVisibility(big ? android.view.View.GONE : android.view.View.VISIBLE);
        bMove.setText(mode == 1 ? "→ 2" : "1 ←");
        FrameLayout.LayoutParams ml = (FrameLayout.LayoutParams) bMove.getLayoutParams();
        ml.gravity = Gravity.BOTTOM | (mode == 1 ? Gravity.END : Gravity.START);
        bMove.setLayoutParams(ml);
        if (gecko != null) gecko.load(baseUrl + "#natee=" + theme + (big ? "" : "&mini=1"));   // # change: no reload
    }

    /** Hold ⟳: can this window reach the map servers? (white theme's day map failed, 2026-11-13) */
    private void netTest() {
        android.widget.Toast.makeText(this, "Testing the map servers…", android.widget.Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String[] urls = {"https://tiles.openfreemap.org/styles/liberty", "https://tiles.openfreemap.org/styles/dark",
                    "https://tiles.openfreemap.org/planet", "https://ghostrace.airodynamic.workers.dev/"};
            StringBuilder sb = new StringBuilder();
            for (String u : urls) {
                long t0 = System.currentTimeMillis();
                String res;
                try {
                    java.net.HttpURLConnection con = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                    con.setConnectTimeout(8000); con.setReadTimeout(8000);
                    con.setRequestProperty("User-Agent", "Mozilla/5.0 (Android 10; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0 NATEELauncher/1");
                    res = "HTTP " + con.getResponseCode();
                    con.disconnect();
                } catch (Throwable e) { res = e.getClass().getSimpleName() + ": " + e.getMessage(); }
                sb.append(u.replace("https://", "")).append("\n   → ").append(res).append("  (").append(System.currentTimeMillis() - t0).append(" ms)\n");
            }
            runOnUiThread(() -> new android.app.AlertDialog.Builder(this).setTitle("Map server test (send a photo to Claude)")
                    .setMessage(sb.toString()).setPositiveButton("OK", null).show());
        }).start();
    }

    @Override protected void onNewIntent(Intent in) {
        super.onNewIntent(in);
        int[] a = in.getIntArrayExtra(AREAS);
        if (a != null && a.length >= 12) { areas = a; place(); }
    }

    @Override protected void onStop() {
        super.onStop();
        if (gecko != null) gecko.keepActive();   // keep recording while hidden behind another app
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        Window win = getWindow();
        win.requestFeature(Window.FEATURE_NO_TITLE);
        win.setBackgroundDrawableResource(android.R.color.transparent);
        win.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        // screen coordinates; touches outside go to the launcher behind
        win.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        WindowManager.LayoutParams lp = win.getAttributes();
        lp.gravity = Gravity.TOP | Gravity.START;
        areas = in.getIntArrayExtra(AREAS);
        if (in.getStringExtra(THEME) != null) theme = in.getStringExtra(THEME);
        if (areas != null && areas.length >= 4) { lp.x = areas[0]; lp.y = areas[1]; lp.width = areas[2]; lp.height = areas[3]; }
        lp.windowAnimations = 0;
        win.setAttributes(lp);
        setFinishOnTouchOutside(false);

        Theme th = new Prefs(this).theme();
        frame = new FrameLayout(this);
        FrameLayout root = frame;
        int bw = Ui.dp(this, 3);
        setFrame(in.getStringExtra(THEME));
        root.setPadding(bw, bw, bw, bw);
        try {
            // size: Android's own scale times the Settings choice (60–150 %); 100 % = Android's own
            int zoom = 100;   // fixed: Ghost Race always fits its window in landscape (size setting removed, 2026-11-12)
            float density = zoom == 100 ? 0 : getResources().getDisplayMetrics().density * zoom / 100f;
            // landscape from the start, in the launcher's colour (Ghost Race reads both from the address)
            String t = in.getStringExtra(THEME);
            baseUrl = EngineHost.GHOST_URL + "?natee=1&natee-theme=" + (t == null ? "blue" : t);
            gecko = new GeckoGhost(this, baseUrl, density);
            registerReceiver(themeRx, new android.content.IntentFilter(ACTION_THEME));
            root.addView(gecko.view(), new FrameLayout.LayoutParams(-1, -1));
        } catch (Throwable t) {
            TextView err = Ui.text(this, "Firefox engine didn't start (" + t.getClass().getSimpleName() + ")\n"
                    + "Settings → Ghost Race browser → car's built-in", 16, Color.WHITE, true);
            err.setGravity(Gravity.CENTER);
            root.addView(err, new FrameLayout.LayoutParams(-1, -1));
        }
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            android.widget.Toast.makeText(this, "Location is not allowed for NATEE yet: Ghost Race can't find GPS", android.widget.Toast.LENGTH_LONG).show();
        // big: ▭ Small, ⟳, ■ Stop  ·  small: ⤢ Enlarge, → 2 / 1 ←, ● REC (user, 2026-11-13)
        bSmall = Ui.chip(this, "▭  Small", false, th, v -> { mode = 1; place(); });
        bSmall.setAlpha(0.9f);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-2, Ui.dp(this, 40), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        sl.topMargin = Ui.dp(this, 4);
        root.addView(bSmall, sl);
        bSmall.setOnLongClickListener(v -> { netTest(); return true; });   // hold "Small": map-server test
        bAgain = Ui.chip(this, "⟳", false, th, v -> { if (gecko != null) gecko.reload(); });
        bAgain.setAlpha(0.85f);
        bAgain.setVisibility(android.view.View.GONE);   // removed (user, 2026-11-13); reload still happens by itself
        FrameLayout.LayoutParams al = new FrameLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 40), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        al.topMargin = Ui.dp(this, 4);
        al.rightMargin = Ui.dp(this, 330);
        root.addView(bAgain, al);
        bStop = Ui.chip(this, "■  Stop", false, th, v -> new android.app.AlertDialog.Builder(this)
                .setTitle("Stop Ghost Race?").setMessage("Recording stops and Ghost Race closes.")
                .setPositiveButton("Stop", (d, w) -> {
                    sendBroadcast(new Intent(ACTION_STOPPED).setPackage(getPackageName()));
                    finish();
                }).setNegativeButton("Keep running", null).show());
        bStop.setAlpha(0.9f);
        FrameLayout.LayoutParams stl = new FrameLayout.LayoutParams(-2, Ui.dp(this, 40), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        stl.topMargin = Ui.dp(this, 4);
        stl.rightMargin = Ui.dp(this, 150);   // just left of "Small" (user, 2026-11-13)
        root.addView(bStop, stl);
        bEnlarge = Ui.chip(this, "⤢  Enlarge", false, th, v -> { mode = 0; place(); });
        FrameLayout.LayoutParams el = new FrameLayout.LayoutParams(-2, Ui.dp(this, 40), Gravity.TOP | Gravity.END);
        el.topMargin = el.rightMargin = Ui.dp(this, 6);
        root.addView(bEnlarge, el);
        bMove = Ui.chip(this, "→ 2", false, th, v -> { mode = mode == 1 ? 2 : 1; place(); });
        FrameLayout.LayoutParams mvl = new FrameLayout.LayoutParams(-2, Ui.dp(this, 40), Gravity.BOTTOM | Gravity.END);
        mvl.bottomMargin = mvl.rightMargin = mvl.leftMargin = Ui.dp(this, 6);
        root.addView(bMove, mvl);
        rec = Ui.text(this, "● REC", 13, Color.WHITE, true);
        rec.setBackground(Ui.round(0xCCE5202E, 0, 10, this));
        rec.setPadding(Ui.dp(this, 9), Ui.dp(this, 2), Ui.dp(this, 9), Ui.dp(this, 2));
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        rl.topMargin = rl.leftMargin = Ui.dp(this, 8);
        root.addView(rec, rl);
        setContentView(root);
        place();
        reloadWhenOnline();
    }

    /**
     * Right after the car starts, its internet isn't up yet: Ghost Race's map then fails ("NetworkError",
     * 2026-11-12). If the window opens without working internet, load the page again once it is there.
     * Only for that first load: a dropout later (a tunnel) never reloads, so a race in progress is safe.
     */
    private android.net.ConnectivityManager.NetworkCallback netCb;
    private void reloadWhenOnline() {
        try {
            final android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.Network n = cm.getActiveNetwork();
            android.net.NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
            if (caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return;
            netCb = new android.net.ConnectivityManager.NetworkCallback() {
                @Override public void onCapabilitiesChanged(android.net.Network net, android.net.NetworkCapabilities nc) {
                    if (!nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return;
                    try { cm.unregisterNetworkCallback(this); } catch (Exception ignored) { }
                    netCb = null;
                    runOnUiThread(() -> { if (gecko != null) gecko.reload(); });
                }
            };
            cm.registerDefaultNetworkCallback(netCb);
        } catch (Throwable ignored) { }
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(themeRx); } catch (Exception ignored) { }
        if (netCb != null) try { ((android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(netCb); } catch (Exception ignored) { }
        if (gecko != null) gecko.close();
        super.onDestroy();
        // the Firefox engine is set up once per process: end this one so a new size applies next time
        // (this process only ever holds the Ghost Race window)
        // the song video shares this process (2026-11-14): only end it when no video window is open
        if (isFinishing() && VideoActivity.live <= 0) android.os.Process.killProcess(android.os.Process.myPid());
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }
}
