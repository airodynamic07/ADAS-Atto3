package com.airodynamic.adas;

import android.content.Context;
import android.view.View;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

/**
 * Ghost Race on Firefox's engine (GeckoView) instead of the car's old WebView, whose map stays
 * blank (2026-11-11 test). Behaves like Ghost Race in Firefox: its own GPS (location is allowed
 * automatically), no AttoHost bridge yet. One Gecko runtime per app.
 */
final class GeckoGhost {
    private static GeckoRuntime runtime;
    private static boolean freshCache;
    private final GeckoSession session;
    private final GeckoView view;

    /**
     * @param density how many screen pixels per web pixel. Chosen so Ghost Race's side panel fills the window's
     *                height like it does in full-screen Firefox (user, 2026-11-12); 0 = Android's own.
     */
    GeckoGhost(Context c, String url, float density) { this(c, url, density, null); }

    /**
     * @param profile its own browser data folder (the song video): Ghost Race keeps running in another process
     *                and holds the shared one, so a second engine on it stayed black (2026-11-13). null = default.
     */
    GeckoGhost(Context c, String url, float density, String profile) {
        if (runtime == null) {
            org.mozilla.geckoview.GeckoRuntimeSettings.Builder sb = new org.mozilla.geckoview.GeckoRuntimeSettings.Builder();
            if (density > 0) sb.displayDensityOverride(density);
            // (2026-11-14) the engine network settings tried in probe-133 (HTTP/3, IPv6, DNS-over-HTTPS off) are
            // gone again: after them the night map failed too. Engine defaults, as when the night map worked.
            if (profile != null) {
                // (2026-11-14) the video worked once, then stayed black every time, even after restarts: most likely the
                // engine's "in use" lock was left in this folder when the window's process was ended, so the engine
                // couldn't open it again. Start from a new folder (the old one is deleted) and clear any stale lock.
                deleteTree(new java.io.File(c.getFilesDir(), "gecko-" + profile));
                java.io.File dir = new java.io.File(c.getFilesDir(), "gecko-" + profile + "2");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                for (String n : new String[]{"parent.lock", ".parentlock", "lock"}) //noinspection ResultOfMethodCallIgnored
                    new java.io.File(dir, n).delete();
                writeUserJs(dir);
                sb.arguments(new String[]{"-profile", dir.getAbsolutePath()});
            }
            if (profile == null) {   // the default browser data (Ghost Race's saved trips stay where they are)
                java.io.File moz = new java.io.File(c.getFilesDir(), "mozilla");
                java.io.File[] ps = moz.listFiles();
                if (ps != null) for (java.io.File p : ps) if (p.isDirectory() && p.getName().contains("default")) writeUserJs(p);
            }
            runtime = GeckoRuntime.create(c.getApplicationContext(), sb.build());
            freshCache = profile == null;   // Ghost Race: start without saved web files (see below)
        }
        // "NATEELauncher/1" at the end of the browser name: Ghost Race turns Pro on here (Air's own car, 2026-11-12)
        String ua;
        try { ua = GeckoSession.getDefaultUserAgent(); } catch (Throwable t) { ua = "Mozilla/5.0 (Android 10; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0"; }
        session = new GeckoSession(new org.mozilla.geckoview.GeckoSessionSettings.Builder()
                .userAgentOverride(ua + " NATEELauncher/1").build());
        session.setPermissionDelegate(new GeckoSession.PermissionDelegate() {
            @Override public void onAndroidPermissionsRequest(GeckoSession s, String[] permissions, Callback callback) {
                callback.grant();   // location is already granted to the launcher at install
            }
            @Override public GeckoResult<Integer> onContentPermissionRequest(GeckoSession s, ContentPermission perm) {
                return GeckoResult.fromValue(ContentPermission.VALUE_ALLOW);   // Ghost Race asks for location
            }
        });
        session.open(runtime);
        view = new GeckoView(c);
        view.setSession(session);
        // What the engine is doing, on screen (2026-11-14: black video, map "NetworkError" while Android itself reaches
        // the servers): "Loading…", or the engine's own error name, or "stopped" when its page process ended.
        box = new android.widget.FrameLayout(c);
        box.addView(view, new android.widget.FrameLayout.LayoutParams(-1, -1));
        status = new android.widget.TextView(c);
        status.setTextColor(0xFFFFFFFF);
        status.setTextSize(13);
        status.setBackgroundColor(0xB0000000);
        status.setPadding(16, 8, 16, 8);
        status.setVisibility(View.GONE);
        hideStatus = () -> { if ("Loading…".contentEquals(status.getText())) show(null); };
        box.addView(status, new android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.BOTTOM | android.view.Gravity.START));
        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override public void onPageStart(GeckoSession s, String u) {
                if (u != null && u.startsWith("natee://")) return;
                show("Loading…");
                status.removeCallbacks(hideStatus); status.postDelayed(hideStatus, 15_000);   // never stuck on screen
            }
            @Override public void onPageStop(GeckoSession s, boolean ok) { if (ok || "Loading…".contentEquals(status.getText())) show(null); }
        });
        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            // Ghost Race's own Stop / Small buttons call "natee://stop" etc. (2026-11-16): handled here, never loaded
            @Override public GeckoResult<org.mozilla.geckoview.AllowOrDeny> onLoadRequest(GeckoSession s, LoadRequest r) {
                return natee(r);
            }
            // (2026-11-16) Ghost Race now sends them through a hidden mini-page: opening the link in the page itself
            // cancelled everything it was still downloading (map, icons, route, trail)
            @Override public GeckoResult<org.mozilla.geckoview.AllowOrDeny> onSubframeLoadRequest(GeckoSession s, LoadRequest r) {
                return natee(r);
            }
            @Override public GeckoResult<String> onLoadError(GeckoSession s, String u, org.mozilla.geckoview.WebRequestError e) {
                show("Engine error: " + errName(e.code) + " (" + e.code + ")\n" + (u == null ? "" : u));
                return null;
            }
        });
        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override public void onCrash(GeckoSession s) { restart("crashed"); }
            @Override public void onKill(GeckoSession s) { restart("was stopped by Android"); }
        });
        lastUrl = url;
        // (2026-11-16) one map file at a time kept failing with "NetworkError" (day style, then the map icons) while
        // Android reached the server fine: most likely a broken saved copy in the engine. Ghost Race starts with the
        // engine's saved web files cleared (its trips and settings are kept), then loads.
        if (freshCache) {
            freshCache = false;
            final boolean[] done = {false};
            final Runnable go = () -> { if (!done[0]) { done[0] = true; session.loadUri(url); } };
            try {
                runtime.getStorageController().clearData(org.mozilla.geckoview.StorageController.ClearFlags.NETWORK_CACHE
                        | org.mozilla.geckoview.StorageController.ClearFlags.IMAGE_CACHE)
                        .accept(v -> view.post(go), e -> view.post(go));
                view.postDelayed(go, 3000);   // never wait longer than this
            } catch (Throwable t) { go.run(); }
        } else session.loadUri(url);
    }

    private GeckoResult<org.mozilla.geckoview.AllowOrDeny> natee(GeckoSession.NavigationDelegate.LoadRequest r) {
        if (r.uri == null || !r.uri.startsWith("natee://")) return null;
        final String what = r.uri.substring(8).replace("/", "");
        if (onNatee != null) view.post(() -> onNatee.accept(what));
        return GeckoResult.fromValue(org.mozilla.geckoview.AllowOrDeny.DENY);
    }

    private final android.widget.FrameLayout box;
    /** "stop", "small", "hello" from Ghost Race's own buttons. */
    java.util.function.Consumer<String> onNatee;
    private final android.widget.TextView status;
    private String lastUrl;
    private Runnable hideStatus;
    private int restarts;

    private void show(String t) {
        status.post(() -> { status.setText(t == null ? "" : t); status.setVisibility(t == null ? View.GONE : View.VISIBLE); });
    }

    /** The page's process ended (a black window before): open it again, up to 3 times. */
    private void restart(String why) {
        if (restarts++ >= 3) { show("The page " + why + " — close and open again"); return; }
        show("The page " + why + " — reopening…");
        status.postDelayed(() -> {
            try {
                if (!session.isOpen()) session.open(runtime);
                session.loadUri(lastUrl);
            } catch (Throwable t) { show("Couldn't reopen (" + t.getClass().getSimpleName() + ")"); }
        }, 800);
    }

    private static String errName(int code) {
        for (java.lang.reflect.Field f : org.mozilla.geckoview.WebRequestError.class.getFields()) {
            try {
                if (f.getName().startsWith("ERROR_") && !f.getName().startsWith("ERROR_CATEGORY") && f.getType() == int.class
                        && f.getInt(null) == code) return f.getName().substring(6);
            } catch (Throwable ignored) { }
        }
        return "unknown";
    }

    /**
     * Engine settings in the browser data folder, read when the engine starts. Firefox's engine goes "offline" when it
     * thinks the car has no internet (it judges that by itself, and the car's own connection can confuse it); Android
     * still reached the map server then (test: HTTP 200). This keeps it online. (2026-11-14)
     */
    private static void deleteTree(java.io.File f) {
        java.io.File[] k = f.listFiles();
        if (k != null) for (java.io.File x : k) deleteTree(x);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static void writeUserJs(java.io.File dir) {
        try (java.io.FileWriter w = new java.io.FileWriter(new java.io.File(dir, "user.js"))) {
            w.write("user_pref(\"network.manage-offline-status\", false);\n"
                    + "user_pref(\"network.offline-mirrors-connectivity\", false);\n");
        } catch (Throwable ignored) { }
    }

    View view() { return box; }

    void reload() { session.reload(); }

    /** Same page, only the part after # changes: Ghost Race switches colour without reloading. */
    void load(String url) { if (!url.startsWith("javascript:")) lastUrl = url; session.loadUri(url); }

    /** Keep the page running (GPS, recording) while the window is hidden. */
    void keepActive() { try { session.setActive(true); } catch (Throwable ignored) { } }

    /** Song video window: install the small built-in add-on that shows only the video, filling the box,
     *  then load the page again so the add-on is active on it. */
    void installVideoFit() {
        try {
            runtime.getWebExtensionController().ensureBuiltIn("resource://android/assets/ytfit/", "ytfit@natee")
                    .accept(ext -> session.reload(), err -> { });
        } catch (Throwable ignored) { }
    }

    /** Change only the part after # of the current page (no reload): the video add-on reads it. */
    void evalHash(String h) {
        try { session.loadUri("javascript:location.hash='" + h + "';void 0"); } catch (Throwable ignored) { }
    }

    void close() { try { view.releaseSession(); session.close(); } catch (Throwable ignored) { } }
}
