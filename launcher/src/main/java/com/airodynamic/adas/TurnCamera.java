package com.airodynamic.adas;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * While the turn signal is on, the side camera covers the middle window (like Kinex);
 * it goes away a set time after the signal stops. (user, 2026-11-22) With another app in front (Ghost Race, YouTube Music)
 * it floats on top of it in the same place, as its own small window. Never in R: the car's own reverse view
 * must always get the cameras. Picture only (no AI).
 */
public final class TurnCamera implements CarData.Listener {
    private final Context c;
    private final CameraFeed feed;
    private final Prefs prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
    private FrameLayout box;
    private ImageView img;
    private TextView label;
    private int side;              // 0 none, 1 left, 2 right
    private int lastTurn;
    private boolean allowed;       // launcher on screen
    private int[] rect;            // where the middle window is on the screen (for the floating window)
    private FrameLayout floatBox;
    private ImageView floatImg;
    private TextView floatLabel;
    private boolean floating;

    public TurnCamera(Context c, CameraFeed feed, Prefs prefs) {
        this.c = c;
        this.feed = feed;
        this.prefs = prefs;
        guide.setStrokeWidth(3);
        applySideCrop(feed, prefs.sideCloseUp(), prefs.sideCamUp());
        CarData.get(c).addListener(this);   // also while the launcher is behind another app
    }

    /** The launcher is closing for good. */
    public void release() { CarData.get(c).removeListener(this); hide(); }

    /** Close-up of the side looking back, cut from the full-resolution picture. */
    public static void applySideCrop(CameraFeed feed, boolean closeUp, int up) {
        if (closeUp) {
            // view height (user, 2026-11-12: "too low, I want to see cars coming from the side"):
            // 0 low (as before), 1 middle, 2 high (default) — the cut-out moves up the picture
            float top = up >= 2 ? 0.02f : up == 1 ? 0.10f : 0.18f, bot = top + 0.70f;
            feed.setCrop(CameraFeed.RIGHT, 0.30f, top, 1.00f, bot);
            feed.setCrop(CameraFeed.LEFT, 0.00f, top, 0.70f, bot);
        } else {
            feed.setCrop(CameraFeed.RIGHT, 0, 0, 1, 1);
            feed.setCrop(CameraFeed.LEFT, 0, 0, 1, 1);
        }
    }

    /** Puts the (hidden) camera layer on top of the middle window. Called on every rebuild. */
    public void attach(FrameLayout parent, Theme th) {
        applySideCrop(feed, prefs.sideCloseUp(), prefs.sideCamUp());
        box = new FrameLayout(c);
        box.setBackgroundColor(0xFF000000);
        img = new ImageView(c);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        box.addView(img, new FrameLayout.LayoutParams(-1, -1));
        label = Ui.text(c, "", 14, 0xFF000000, true);
        label.setBackground(Ui.round(Theme.CAUTION, 0, 8, c));
        label.setPadding(Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 10), Ui.dp(c, 4));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        lp.setMargins(Ui.dp(c, 8), Ui.dp(c, 8), 0, 0);
        box.addView(label, lp);
        box.setOnClickListener(v -> hide());   // tap to close early
        parent.addView(box, new FrameLayout.LayoutParams(-1, -1));
        box.setVisibility(View.GONE);
        // remember where the middle window is, for the floating window over other apps
        parent.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            if (v.getWidth() > 0) rect = new int[]{loc[0], loc[1], v.getWidth(), v.getHeight()};
        });
        if (side != 0) show(side);
    }

    public void setAllowed(boolean a) {
        if (allowed == a) return;
        allowed = a;
        // switch between inside the launcher and floating: the next reading shows it again in the right place
        if (side != 0) hide();
    }

    public void onCar(CarData.State s) {
        if (s.gear == 2) { if (side != 0) hide(); lastTurn = s.turn; return; }   // R: leave the cameras to the car
        if (prefs.turnCamera()) {
            if (s.turn == 1 && side != 1) show(1);
            else if (s.turn == 2 && side != 2) show(2);
            else if ((s.turn == 0 || s.turn == 3) && lastTurn != s.turn && side != 0) {
                main.removeCallbacksAndMessages(null);
                main.postDelayed(() -> { if (CarData.get(c).last().turn == 0) hide(); }, prefs.turnCameraReturnSec() * 1000L);
            }
        }
        lastTurn = s.turn;
    }

    private void show(int which) {
        main.removeCallbacksAndMessages(null);
        side = which;
        final ImageView im;
        final TextView lb;
        if (allowed) {
            if (box == null) return;
            box.setVisibility(View.VISIBLE);
            im = img; lb = label;
        } else {
            if (!showFloat()) { side = 0; return; }
            im = floatImg; lb = floatLabel;
        }
        final int slice = which == 1 ? CameraFeed.LEFT : CameraFeed.RIGHT;
        feed.setSlice(slice);
        lb.setText(which == 1 ? "◀ Left signal · side camera" : "Right signal ▶ · side camera");
        feed.start(new CameraFeed.Listener() {
            @Override public void onFrame(Bitmap b) {
                if (side == 0) return;
                // distance lines removed (user, 2026-11-11): plain camera picture
                im.setImageBitmap(b);
            }
            @Override public void onError(String msg) { main.post(() -> lb.setText("Camera: " + msg)); }
        });
    }

    private void hide() {
        main.removeCallbacksAndMessages(null);
        side = 0;
        feed.stop();
        if (box != null) { box.setVisibility(View.GONE); img.setImageBitmap(null); }
        hideFloat();
    }

    /** Its own small window over whatever app is in front, where the launcher's middle window is. */
    private boolean showFloat() {
        if (floating) return true;
        int[] r = rect;
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        if (r == null) r = new int[]{dm.widthPixels / 3, 0, dm.widthPixels / 3, dm.heightPixels * 2 / 3};
        floatBox = new FrameLayout(c);
        floatBox.setBackgroundColor(0xFF000000);
        floatImg = new ImageView(c);
        floatImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        floatBox.addView(floatImg, new FrameLayout.LayoutParams(-1, -1));
        floatLabel = Ui.text(c, "", 14, 0xFF000000, true);
        floatLabel.setBackground(Ui.round(Theme.CAUTION, 0, 8, c));
        floatLabel.setPadding(Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 10), Ui.dp(c, 4));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        lp.setMargins(Ui.dp(c, 8), Ui.dp(c, 8), 0, 0);
        floatBox.addView(floatLabel, lp);
        floatBox.setOnClickListener(v -> hide());   // tap to close early
        try {
            @SuppressWarnings("deprecation") int type = android.view.WindowManager.LayoutParams.TYPE_PHONE;
            android.view.WindowManager.LayoutParams wl = new android.view.WindowManager.LayoutParams(r[2], r[3], type,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.OPAQUE);
            wl.gravity = Gravity.TOP | Gravity.START;
            wl.x = r[0]; wl.y = r[1];
            ((android.view.WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).addView(floatBox, wl);
            floating = true;
            return true;
        } catch (Throwable e) {
            floatBox = null;
            return false;
        }
    }

    private void hideFloat() {
        if (!floating) return;
        floating = false;
        try { ((android.view.WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).removeView(floatBox); } catch (Throwable ignored) { }
        floatBox = null; floatImg = null; floatLabel = null;
    }

    /** Distance lines (rough, not calibrated): 3 m red, 6 m amber, 10 m green, in whole-picture terms. */
    private void drawGuides(Bitmap b, float[] cr) {
        Canvas cv = new Canvas(b);
        float w = b.getWidth(), h = b.getHeight();
        int[] cols = {Theme.DANGER, Theme.CAUTION, Theme.SAFE};
        String[] lab = {"3 m", "6 m", "10 m"};
        float[] ys = {0.78f, 0.62f, 0.50f};
        guide.setTextSize(22);
        for (int i = 0; i < 3; i++) {
            guide.setColor(cols[i]);
            float y = h * (ys[i] - cr[1]) / (cr[3] - cr[1]);
            cv.drawLine(w * 0.10f, y, w * 0.86f, y, guide);
            cv.drawText(lab[i], w * 0.87f, y + 8, guide);
        }
    }
}
