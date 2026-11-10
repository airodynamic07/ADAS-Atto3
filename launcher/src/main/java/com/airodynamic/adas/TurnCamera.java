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
 * it goes away a set time after the signal stops. Never in R: the car's own reverse view
 * must always get the cameras. Picture only (no AI).
 */
public final class TurnCamera {
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

    public TurnCamera(Context c, CameraFeed feed, Prefs prefs) {
        this.c = c;
        this.feed = feed;
        this.prefs = prefs;
        guide.setStrokeWidth(3);
        applySideCrop(feed, prefs.sideCloseUp());
    }

    /** Close-up of the side looking back, cut from the full-resolution picture. */
    public static void applySideCrop(CameraFeed feed, boolean closeUp) {
        if (closeUp) {
            feed.setCrop(CameraFeed.RIGHT, 0.30f, 0.18f, 1.00f, 0.88f);
            feed.setCrop(CameraFeed.LEFT, 0.00f, 0.18f, 0.70f, 0.88f);
        } else {
            feed.setCrop(CameraFeed.RIGHT, 0, 0, 1, 1);
            feed.setCrop(CameraFeed.LEFT, 0, 0, 1, 1);
        }
    }

    /** Puts the (hidden) camera layer on top of the middle window. Called on every rebuild. */
    public void attach(FrameLayout parent, Theme th) {
        applySideCrop(feed, prefs.sideCloseUp());
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
        if (side != 0) show(side);
    }

    public void setAllowed(boolean a) {
        allowed = a;
        if (!a) hide();
    }

    public void onCar(CarData.State s) {
        if (s.gear == 2) { if (side != 0) hide(); lastTurn = s.turn; return; }   // R: leave the cameras to the car
        if (allowed && prefs.turnCamera()) {
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
        if (box == null) return;
        final int slice = which == 1 ? CameraFeed.LEFT : CameraFeed.RIGHT;
        feed.setSlice(slice);
        label.setText(which == 1 ? "◀ Left signal · side camera" : "Right signal ▶ · side camera");
        box.setVisibility(View.VISIBLE);
        feed.start(new CameraFeed.Listener() {
            @Override public void onFrame(Bitmap b) {
                if (side == 0) return;
                drawGuides(b, feed.crop(slice));
                img.setImageBitmap(b);
            }
            @Override public void onError(String msg) { label.setText("Camera: " + msg); }
        });
    }

    private void hide() {
        main.removeCallbacksAndMessages(null);
        side = 0;
        feed.stop();
        if (box != null) { box.setVisibility(View.GONE); img.setImageBitmap(null); }
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
