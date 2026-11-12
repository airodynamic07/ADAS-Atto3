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
        int col = "red".equals(t) ? 0xFFE5202E : "white".equals(t) ? 0xFF000000 : 0xFF4DA3FF;
        if (frame == null) return;
        android.graphics.drawable.GradientDrawable g = Ui.round(Color.BLACK, 0, 18, this);
        g.setStroke(Ui.dp(this, 3), col);
        frame.setBackground(g);
    }
    private final android.content.BroadcastReceiver themeRx = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, Intent i) {
            String t = i.getStringExtra(THEME);
            if (gecko != null && t != null) gecko.load(baseUrl + "#natee=" + t);
            if (t != null) setFrame(t);
        }
    };
    private GeckoGhost gecko;

    static void open(Activity from, int x, int y, int w, int h, String theme) {
        Intent i = new Intent(from, GhostActivity.class).putExtra(X, x).putExtra(Y, y).putExtra(W, w).putExtra(H, h).putExtra(THEME, theme);
        from.startActivity(i);
        from.overridePendingTransition(0, 0);
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
        lp.x = in.getIntExtra(X, 0);
        lp.y = in.getIntExtra(Y, 0);
        lp.width = in.getIntExtra(W, WindowManager.LayoutParams.MATCH_PARENT);
        lp.height = in.getIntExtra(H, WindowManager.LayoutParams.MATCH_PARENT);
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
            int zoom = new Prefs(this).ghostZoom();
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
                android.widget.Button close = Ui.chip(this, "✕  Engine Sound", false, th, v -> finish());
        close.setAlpha(0.85f);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, Ui.dp(this, 40), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        cl.topMargin = Ui.dp(this, 4);
        root.addView(close, cl);
        // ⟳: load Ghost Race again (e.g. the map didn't load because the internet wasn't up yet)
        android.widget.Button again = Ui.chip(this, "⟳", false, th, v -> { if (gecko != null) gecko.reload(); });
        again.setAlpha(0.85f);
        FrameLayout.LayoutParams al = new FrameLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 40), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        al.topMargin = Ui.dp(this, 4);
        al.leftMargin = Ui.dp(this, 210);
        root.addView(again, al);
        setContentView(root);
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
        if (isFinishing()) android.os.Process.killProcess(android.os.Process.myPid());
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }
}
