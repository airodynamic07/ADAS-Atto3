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
    static final String X = "x", Y = "y", W = "w", H = "h";
    private GeckoGhost gecko;

    static void open(Activity from, int x, int y, int w, int h) {
        Intent i = new Intent(from, GhostActivity.class).putExtra(X, x).putExtra(Y, y).putExtra(W, w).putExtra(H, h);
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
        FrameLayout root = new FrameLayout(this);
        int bw = Ui.dp(this, 3);
        root.setBackground(Ui.round(Color.BLACK, Theme.SAFE, 18, this));
        root.setPadding(bw, bw, bw, bw);
        try {
            // Ghost Race's side panel is about 440 web pixels tall: scale so it fills this window's height
            int hPx = in.getIntExtra(H, 0);
            float density = hPx > 0 ? Math.max(.75f, Math.min(3f, hPx / 440f)) : 0;
            gecko = new GeckoGhost(this, EngineHost.GHOST_URL + "?natee=1", density);   // Ghost Race starts in landscape
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
        setContentView(root);
    }

    @Override protected void onDestroy() {
        if (gecko != null) gecko.close();
        super.onDestroy();
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }
}
