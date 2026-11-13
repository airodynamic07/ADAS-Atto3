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
            if (profile != null) {
                java.io.File dir = new java.io.File(c.getFilesDir(), "gecko-" + profile);
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                sb.arguments(new String[]{"-profile", dir.getAbsolutePath()});
            }
            runtime = GeckoRuntime.create(c.getApplicationContext(), sb.build());
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
        session.loadUri(url);
    }

    View view() { return view; }

    void reload() { session.reload(); }

    /** Same page, only the part after # changes: Ghost Race switches colour without reloading. */
    void load(String url) { session.loadUri(url); }

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
