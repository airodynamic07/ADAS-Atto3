package com.airodynamic.adas;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.Locale;

/**
 * Engine Sound inside the launcher's middle window: its own page and sounds, read from the
 * installed Engine Sound app, with the same bridges that app provides (car speed + pedal,
 * Ghost Race with GPS / car data / theme). Kept alive across theme changes so the sound
 * never stops; driven by a background tick while another app is in front.
 */
public final class EngineHost {

    static final String ENGINE_PKG = Prefs.ENGINE_PKG;
    static final String ENGINE_ORIGIN = "https://engine.local/";
    static final String GHOST_URL = "https://ghostrace.airodynamic.workers.dev/";
    static final String GHOST_HOST = "ghostrace.airodynamic.workers.dev";

    private final Context ctx;
    private final FrameLayout root;
    private final WebView engine;
    private WebView ghost;
    private final TextView diag;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread carThread = new HandlerThread("engine-car");
    private final Handler car;
    private AssetManager assets;

    private volatile boolean carOn, engineRunning, ghostVisible, ghostGps, ghostCar, batteryWanted, foreground = true;
    private String theme = "blue";
    private long lastGhostCarAt;

    @SuppressLint("SetJavaScriptEnabled")
    public EngineHost(Context c) {
        ctx = c.getApplicationContext();
        carThread.start();
        car = new Handler(carThread.getLooper());
        root = new FrameLayout(ctx);
        root.setBackgroundColor(Color.BLACK);
        try { assets = ctx.createPackageContext(ENGINE_PKG, 0).getAssets(); } catch (Exception ignored) {}

        engine = newWebView();
        engine.addJavascriptInterface(new Bridge(), "AndroidBridge");
        engine.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return serveAsset(r.getUrl().toString());
            }
            @Override public void onPageFinished(WebView v, String url) { applyTheme(); }
        });
        engine.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage m) {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) showDiag("Engine page: " + m.message() + " (line " + m.lineNumber() + ")");
                return true;
            }
        });
        root.addView(engine, new FrameLayout.LayoutParams(-1, -1));

        diag = new TextView(ctx);
        diag.setTextColor(Color.WHITE);
        diag.setTextSize(11);
        diag.setBackgroundColor(0xCCB00020);
        diag.setPadding(16, 8, 16, 8);
        diag.setVisibility(View.GONE);
        diag.setOnClickListener(v -> v.setVisibility(View.GONE));
        root.addView(diag, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        if (assets == null) {
            engine.loadData("<body style='background:#000;color:#ccc;font:18px sans-serif;padding:24px'>"
                    + "Engine Sound is not installed on this car.</body>", "text/html", "UTF-8");
        } else {
            engine.loadUrl(ENGINE_ORIGIN + "index.html");
        }
    }

    public View view() {
        ViewGroup p = (ViewGroup) root.getParent();
        if (p != null) p.removeView(root);
        return root;
    }

    private WebView newWebView() {
        WebView w = new WebView(ctx);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setGeolocationEnabled(true);
        w.setBackgroundColor(Color.BLACK);
        return w;
    }

    /** Engine Sound's page, sounds and scripts come straight from its installed APK. */
    private WebResourceResponse serveAsset(String url) {
        if (!url.startsWith(ENGINE_ORIGIN) || assets == null) return null;
        String path = url.substring(ENGINE_ORIGIN.length());
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        if (path.isEmpty()) path = "index.html";
        try {
            InputStream in = assets.open(android.net.Uri.decode(path));
            if (path.equals("index.html")) in = withOldBrowserFix(in);
            return new WebResourceResponse(mime(path), mime(path).startsWith("text") ? "UTF-8" : null, in);
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "UTF-8", 404, "Not found",
                    new java.util.HashMap<String, String>(), new java.io.ByteArrayInputStream(new byte[0]));
        }
    }

    /**
     * The car's WebView is older than the phone's: it has no CSS aspect-ratio (the rev meter
     * collapsed to zero height) and no inset. Same fix as Engine Sound's own page, added here so
     * an Engine Sound already installed on the car works without reinstalling it.
     */
    private static final String OLD_BROWSER_CSS = "<style>"
            + ".cluster{aspect-ratio:auto!important}"
            + ".cluster::before{content:\"\";display:block;padding-top:78%}"
            + "canvas#tach{position:absolute!important;top:0;left:0}"
            + ".sheet{top:0;right:0;bottom:0;left:0}"
            + "</style>";

    private static InputStream withOldBrowserFix(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        String html = b.toString("UTF-8");
        int i = html.indexOf("</head>");
        html = i >= 0 ? html.substring(0, i) + OLD_BROWSER_CSS + html.substring(i) : OLD_BROWSER_CSS + html;
        return new java.io.ByteArrayInputStream(html.getBytes("UTF-8"));
    }

    private static String mime(String p) {
        String l = p.toLowerCase(Locale.US);
        if (l.endsWith(".html")) return "text/html";
        if (l.endsWith(".js")) return "text/javascript";
        if (l.endsWith(".css")) return "text/css";
        if (l.endsWith(".json")) return "application/json";
        if (l.endsWith(".svg")) return "image/svg+xml";
        if (l.endsWith(".png")) return "image/png";
        if (l.endsWith(".ogg") || l.endsWith(".oga")) return "audio/ogg";
        if (l.endsWith(".mp3")) return "audio/mpeg";
        if (l.endsWith(".wav")) return "audio/wav";
        if (l.endsWith(".m4a") || l.endsWith(".aac")) return "audio/mp4";
        if (l.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    private void js(WebView w, String code) {
        if (w != null) main.post(() -> w.evaluateJavascript(code, null));
    }

    private static String q(String s) { return JSONObject.quote(s); }

    /** Ghost Race's map (MapLibre) needs WebGL2: report what this WebView has if the map can't start. */
    private void checkGhostBrowser(WebView v) {
        v.evaluateJavascript("(function(){var c=document.createElement('canvas');"
                + "var g2=!!c.getContext('webgl2'),g1=!!(c.getContext('webgl')||c.getContext('experimental-webgl'));"
                + "var m=navigator.userAgent.match(/Chrome\\/(\\d+)/);return (g2?'1':'0')+(g1?'1':'0')+' '+(m?m[1]:'?');})()",
                r -> {
                    String t = r == null ? "" : r.replace("\"", "");
                    if (!t.startsWith("1")) showDiag("Ghost Race map: WebGL2 " + (t.startsWith("0") ? "missing" : "?")
                            + ", WebGL1 " + (t.length() > 1 && t.charAt(1) == '1' ? "yes" : "no")
                            + ", Chrome " + (t.indexOf(' ') > 0 ? t.substring(t.indexOf(' ') + 1) : "?"));
                });
    }

    private void showDiag(String s) {
        main.post(() -> {
            String wv = "?";
            try { android.content.pm.PackageInfo p = WebView.getCurrentWebViewPackage(); if (p != null) wv = p.packageName + " " + p.versionName; } catch (Throwable ignored) {}
            diag.setText("⚠ " + s + "\nWebView: " + wv + "   (tap to hide; send a photo)");
            diag.setVisibility(View.VISIBLE);
        });
    }

    // ---------------------------------------------------------------- theme / lifecycle

    public void setTheme(Theme t) {
        theme = t == Theme.RED ? "red" : t == Theme.WHITE ? "white" : "blue";
        applyTheme();
    }

    private void applyTheme() {
        js(engine, "window.setLauncherTheme && window.setLauncherTheme(" + q(theme) + ")");
        js(ghost, "window.__attoTheme && window.__attoTheme(" + q(theme) + ")");
    }

    /** Launcher in front or not: when hidden, the page's own animation loop stops, so tick it from here. */
    public void setForeground(boolean fg) {
        foreground = fg;
        main.removeCallbacks(tick);
        if (!fg) main.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (foreground || !engineRunning) return;
            engine.evaluateJavascript("window.__tick && window.__tick()", null);
            main.postDelayed(this, 40);
        }
    };

    // ---------------------------------------------------------------- car data (20 a second)

    private final Runnable carPoll = new Runnable() {
        @Override public void run() {
            if (!carOn && !ghostCar) return;
            double[] sp = CarData.get(ctx).speedAndPedal();
            String pedal = Double.isNaN(sp[1]) ? "null" : String.format(Locale.US, "%.1f", sp[1]);
            if (carOn) {
                js(engine, String.format(Locale.US, "window.onCarData && window.onCarData({speed:%.2f,pedal:%s,src:'car'})", sp[0], pedal));
            }
            long now = System.currentTimeMillis();
            if (ghostCar && now - lastGhostCarAt >= 200) {
                lastGhostCarAt = now;
                try {
                    JSONObject o = new JSONObject().put("status", "ready").put("speedKmh", Math.round(sp[0]));
                    double soc = CarData.get(ctx).last().socPct;
                    if (batteryWanted && !Double.isNaN(soc)) o.put("socPct", soc);
                    js(ghost, "window.__attoObd && window.__attoObd(" + o + ")");
                } catch (Exception ignored) {}
            }
            car.postDelayed(this, 50);
        }
    };

    private void startCarPoll() { car.removeCallbacks(carPoll); car.post(carPoll); }

    /** window.AndroidBridge for Engine Sound's page. */
    final class Bridge {
        @JavascriptInterface public boolean hasCar() { return CarData.get(ctx).onBydCar(); }
        @JavascriptInterface public void startCar() {
            carOn = true;
            js(engine, "window.onCarStatus && window.onCarStatus(" + q("Car · reading speed and pedal") + ", 'ok')");
            startCarPoll();
        }
        @JavascriptInterface public void stopCar() { carOn = false; }
        @JavascriptInterface public void engineRunning(boolean on) { engineRunning = on; if (!foreground) setForeground(false); }
        @JavascriptInterface public void openGhostRace() { main.post(() -> showGhost(true)); }
        // The dongle and phone GPS belong to the phone app; on the car screen use "Car".
        @JavascriptInterface public void scanObd() { obdNote(); }
        @JavascriptInterface public void connectObd(String a) { obdNote(); }
        @JavascriptInterface public void disconnectObd() {}
        @JavascriptInterface public void startGps() {
            js(engine, "window.onGpsStatus && window.onGpsStatus(" + q("On the car screen use 'Car' instead") + ", 'err')");
        }
        @JavascriptInterface public void stopGps() {}
        private void obdNote() {
            js(engine, "window.onObdStatus && window.onObdStatus(" + q("On the car screen use 'Car' (no dongle needed)") + ", 'err')");
        }
    }

    // ---------------------------------------------------------------- Ghost Race

    @SuppressLint("SetJavaScriptEnabled")
    private void showGhost(boolean show) {
        if (show && ghost == null) {
            ghost = newWebView();
            ghost.addJavascriptInterface(new GhostHost(), "AttoHost");
            ghost.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                    return !GHOST_HOST.equals(r.getUrl().getHost());
                }
                @Override public void onPageFinished(WebView v, String url) { applyTheme(); checkGhostBrowser(v); }
                @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                    if (r.isForMainFrame()) showDiag("Ghost Race didn't load: " + e.getDescription() + " (internet?)");
                }
            });
            ghost.setWebChromeClient(new WebChromeClient() {
                @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                    cb.invoke(origin, true, false);
                }
                @Override public boolean onConsoleMessage(ConsoleMessage m) {
                    if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR)
                        showDiag("Ghost Race: " + m.message() + " (" + m.sourceId().replaceAll(".*/", "") + ":" + m.lineNumber() + ")");
                    return true;
                }
            });
            root.addView(ghost, 1, new FrameLayout.LayoutParams(-1, -1));
            ghost.loadUrl(GHOST_URL);
        }
        if (ghost != null) ghost.setVisibility(show ? View.VISIBLE : View.GONE);
        ghostVisible = show;
    }

    /** window.AttoHost for Ghost Race (same functions as in the Engine Sound app). */
    final class GhostHost {
        @JavascriptInterface public void startGps() { main.post(() -> { ghostGps = true; startGpsUpdates(); }); }
        @JavascriptInterface public void stopGps() { main.post(() -> { ghostGps = false; stopGpsUpdates(); }); }
        @JavascriptInterface public void setBatteryWanted(boolean on) { batteryWanted = on; }
        @JavascriptInterface public String launcherTheme() { return theme; }
        @JavascriptInterface public void openEngine() { main.post(() -> showGhost(false)); }
        /** On the car the "dongle" is the car itself: speed (and battery %) straight from it. */
        @JavascriptInterface public void connectObd() { ghostCar = true; startCarPoll(); }
    }

    private int ghostFixes;
    private final LocationListener gps = new LocationListener() {
        @Override public void onLocationChanged(Location l) {
            ghostFixes++;
            if (!ghostGps) return;
            try {
                JSONObject o = new JSONObject().put("epochMs", l.getTime()).put("lat", l.getLatitude()).put("lng", l.getLongitude())
                        .put("speed", l.hasSpeed() ? (Object) (double) l.getSpeed() : JSONObject.NULL)
                        .put("heading", l.hasBearing() ? (Object) (double) l.getBearing() : JSONObject.NULL)
                        .put("accuracy", (double) l.getAccuracy());
                js(ghost, "window.__attoGeo && window.__attoGeo(" + o + ")");
            } catch (Exception ignored) {}
        }
        @Override public void onStatusChanged(String p, int s, Bundle b) {}
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {
            js(ghost, "window.__attoGeoError && window.__attoGeoError('unavailable')");
        }
    };

    @SuppressLint("MissingPermission")
    private void startGpsUpdates() {
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, gps, Looper.getMainLooper());
            try { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000, 0, gps, Looper.getMainLooper()); } catch (Throwable ignored) {}
            main.postDelayed(() -> { if (ghostGps && Places.gpsReport(ctx).contains(" 0 fixes") && ghostFixes == 0)
                showDiag("Ghost Race: no GPS from the car after 60 s · " + Places.gpsReport(ctx)); }, 60_000L);
        } catch (Throwable t) {
            js(ghost, "window.__attoGeoError && window.__attoGeoError('unavailable')");
        }
    }

    private void stopGpsUpdates() {
        try { ((LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE)).removeUpdates(gps); } catch (Throwable ignored) {}
    }
}
