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

    GeckoGhost(Context c, String url) {
        if (runtime == null) runtime = GeckoRuntime.create(c.getApplicationContext());
        session = new GeckoSession();
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

    void close() { try { view.releaseSession(); session.close(); } catch (Throwable ignored) { } }
}
