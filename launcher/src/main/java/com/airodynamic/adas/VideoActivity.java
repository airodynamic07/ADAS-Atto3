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
 * The current song's video, in its own small window over the Now playing card (user, 2026-11-13):
 * YouTube's mobile site in Android's own browser engine (2026-11-14), own process (":video"), never full screen. It plays while
 * driving for the passenger (Settings can limit it to P). ✕ closes it and the music app plays again.
 */
public class VideoActivity extends Activity {
    static final String X = "x", Y = "y", W = "w", H = "h", Q = "q", THEME = "theme";
    private android.webkit.WebView web;
    private TextView status;
    private boolean fit;
    static int live;   // windows alive in this process

    static void open(Activity from, int x, int y, int w, int h, String query, String theme) {
        from.startActivity(new Intent(from, VideoActivity.class).putExtra(X, x).putExtra(Y, y).putExtra(W, w).putExtra(H, h)
                .putExtra(Q, query).putExtra(THEME, theme));
        from.overridePendingTransition(0, 0);
    }

    @Override protected void onCreate(Bundle b) {
        // (2026-11-14) Android's own browser engine in its own process: Firefox's engine stayed on "Loading…" on the car.
        // A second process using it needs its own data folder.
        try { android.webkit.WebView.setDataDirectorySuffix("video"); } catch (Throwable ignored) { }
        super.onCreate(b);
        live++;
        Intent in = getIntent();
        Window win = getWindow();
        win.requestFeature(Window.FEATURE_NO_TITLE);
        win.setBackgroundDrawableResource(android.R.color.transparent);
        win.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        win.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        WindowManager.LayoutParams lp = win.getAttributes();
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = in.getIntExtra(X, 0); lp.y = in.getIntExtra(Y, 0);
        lp.width = in.getIntExtra(W, 600); lp.height = in.getIntExtra(H, 400);
        lp.windowAnimations = 0;
        win.setAttributes(lp);
        setFinishOnTouchOutside(false);

        Theme th = new Prefs(this).theme();
        String t = in.getStringExtra(THEME);
        int frameCol = "red".equals(t) ? 0xFF6B1018 : "white".equals(t) ? 0xFF9AA0A8 : 0xFF1C3D78;
        FrameLayout root = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable g = Ui.round(Color.BLACK, 0, 14, this);
        g.setStroke(Ui.dp(this, 2), frameCol);
        root.setBackground(g);
        int bw = Ui.dp(this, 2);
        root.setPadding(bw, bw, bw, bw);
        try {
            String q = in.getStringExtra(Q);
            String url = "https://m.youtube.com/results?search_query=" + android.net.Uri.encode((q == null ? "" : q) + " official video");
            web = new android.webkit.WebView(this);
            android.webkit.WebSettings ws = web.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setMediaPlaybackRequiresUserGesture(false);
            ws.setUseWideViewPort(true);
            ws.setLoadWithOverviewMode(true);
            web.setBackgroundColor(Color.BLACK);
            final String fitCss = asset("ytfit/fit.css"), fitJs = asset("ytfit/fit.js");
            web.setWebViewClient(new android.webkit.WebViewClient() {
                @Override public void onPageStarted(android.webkit.WebView v, String u, android.graphics.Bitmap f) { show("Loading…"); fit(v); }
                @Override public void onPageFinished(android.webkit.WebView v, String u) { show(null); fit(v); }
                @Override public void onReceivedError(android.webkit.WebView v, android.webkit.WebResourceRequest r, android.webkit.WebResourceError e) {
                    if (r.isForMainFrame()) show("Error: " + e.getDescription());
                }
                /** only the video, filling the box: the same rules as before, put into the page (user, 2026-11-13) */
                private void fit(android.webkit.WebView v) {
                    v.evaluateJavascript("(function(){if(!document.getElementById('natee-fit')){var s=document.createElement('style');s.id='natee-fit';"
                            + "s.textContent=" + org.json.JSONObject.quote(fitCss) + ";(document.head||document.documentElement).appendChild(s);"
                            + fitJs + "}})()", null);
                }
            });
            web.setWebChromeClient(new android.webkit.WebChromeClient());
            root.addView(web, new FrameLayout.LayoutParams(-1, -1));
            status = Ui.text(this, "", 13, Color.WHITE, false);
            status.setBackgroundColor(0xB0000000);
            status.setPadding(16, 8, 16, 8);
            status.setVisibility(android.view.View.GONE);
            root.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));
            web.loadUrl(url);
        } catch (Throwable e) {
            TextView err = Ui.text(this, "Video couldn't start (" + e.getClass().getSimpleName() + ")", 15, Color.WHITE, true);
            err.setGravity(Gravity.CENTER);
            root.addView(err, new FrameLayout.LayoutParams(-1, -1));
        }
        android.widget.Button close = Ui.chip(this, "✕", false, th, v -> finish());
        close.setAlpha(0.85f);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 40), Gravity.TOP | Gravity.END);
        cl.topMargin = cl.rightMargin = Ui.dp(this, 4);
        root.addView(close, cl);
        // ⤢ fill (no bars, sides trimmed) ⇄ fit (whole picture with bars) (user, 2026-11-13)
        android.widget.Button fitB = Ui.chip(this, "⤢", false, th, v -> {
            fit = !fit;
            if (web != null) web.evaluateJavascript("location.hash='" + (fit ? "natee-fit" : "natee-fill") + "';", null);
        });
        fitB.setAlpha(0.85f);
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 40), Gravity.TOP | Gravity.END);
        fl.topMargin = Ui.dp(this, 4); fl.rightMargin = Ui.dp(this, 52);
        root.addView(fitB, fl);
        setContentView(root);
        registerReceiver(closeRx, new android.content.IntentFilter(InfoPanel.ACTION_VIDEO_CLOSE));
    }
    private final android.content.BroadcastReceiver closeRx = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, Intent i) { finish(); }
    };

    @Override protected void onDestroy() {
        try { unregisterReceiver(closeRx); } catch (Exception ignored) { }
        closeGecko();
        super.onDestroy();
        live--;
    }

    private void closeGecko() {
        if (web != null) { try { web.loadUrl("about:blank"); web.destroy(); } catch (Throwable ignored) { } web = null; }   // stops the sound
    }

    private void show(String t) {
        if (status == null) return;
        status.setText(t == null ? "" : t);
        status.setVisibility(t == null ? android.view.View.GONE : android.view.View.VISIBLE);
    }

    private String asset(String name) {
        try (java.io.InputStream in = getAssets().open(name)) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toString("UTF-8");
        } catch (Exception e) { return ""; }
    }

    @Override public void finish() { closeGecko(); super.finish(); overridePendingTransition(0, 0); }
}
