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
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;

/** Left window: speed, battery, status, view buttons, the view, alert bar. Warnings only. */
public final class AdasPanel implements Panel {

    private static final String[] VIEWS = {"Map", "Top", "Front", "Rear", "Left", "Right"};
    private static final int MAP = 0, TOP = 1, FRONT = 2, REAR = 3, LEFT = 4, RIGHT = 5;

    private final Context c;
    private final Theme th;
    private final Prefs prefs;
    private final CameraFeed feed;
    private final LinearLayout root;
    private final TextView speed, soc, range, status, alert;
    private final ProgressBar socBar;
    private final LinearLayout viewBar;
    private final FrameLayout content;
    private final RadarView radar;
    private final ImageView camera;
    private final TextView note;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int view = MAP;
    private boolean active, autoView;
    private int lastTurn;
    private final Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);

    public AdasPanel(Context c, Theme th, Prefs prefs, CameraFeed feed) {
        this.c = c;
        this.th = th;
        this.prefs = prefs;
        this.feed = feed;
        root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(c, 14);
        root.setPadding(pad, Ui.dp(c, 8), pad, pad);

        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        speed = Ui.text(c, "–", 34, th.text, true);
        TextView kmh = Ui.text(c, "  km/h", 13, th.muted, false);
        top.addView(speed);
        top.addView(kmh);
        View spacer = new View(c);
        top.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        LinearLayout bat = new LinearLayout(c);
        bat.setOrientation(LinearLayout.VERTICAL);
        bat.setGravity(Gravity.END);
        LinearLayout batRow = new LinearLayout(c);
        batRow.setGravity(Gravity.BOTTOM);
        soc = Ui.text(c, "–%", 22, th.text, true);
        range = Ui.text(c, "  – km", 13, th.muted, false);
        batRow.addView(soc);
        batRow.addView(range);
        socBar = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        socBar.setMax(100);
        socBar.setProgressTintList(android.content.res.ColorStateList.valueOf(th.accent));
        socBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(th.chip));
        bat.addView(batRow);
        bat.addView(socBar, new LinearLayout.LayoutParams(Ui.dp(c, 110), Ui.dp(c, 8)));
        top.addView(bat);
        root.addView(top);

        status = Ui.text(c, "● Around you", 12, th.muted, false);
        status.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 8));
        root.addView(status);

        viewBar = new LinearLayout(c);
        viewBar.setPadding(Ui.dp(c, 3), Ui.dp(c, 3), Ui.dp(c, 3), Ui.dp(c, 3));
        viewBar.setBackground(Ui.round(th.chip, 0, 14, c));
        root.addView(viewBar);
        buildViewBar();

        content = new FrameLayout(c);
        content.setBackground(Ui.round(th.chip, 0, 14, c));
        content.setClipToOutline(true);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, 0, 1f);
        clp.topMargin = Ui.dp(c, 10);
        clp.bottomMargin = Ui.dp(c, 10);
        root.addView(content, clp);
        radar = new RadarView(c, th);
        camera = new ImageView(c);
        camera.setScaleType(ImageView.ScaleType.CENTER_CROP);
        note = Ui.text(c, "", 14, th.muted, false);
        note.setGravity(Gravity.CENTER);
        note.setPadding(pad, pad, pad, pad);
        content.addView(radar, new FrameLayout.LayoutParams(-1, -1));
        content.addView(camera, new FrameLayout.LayoutParams(-1, -1));
        content.addView(note, new FrameLayout.LayoutParams(-1, -1));

        alert = Ui.text(c, "", 16, 0xFF000000, true);
        alert.setPadding(pad, Ui.dp(c, 12), pad, Ui.dp(c, 12));
        alert.setVisibility(View.GONE);
        root.addView(alert);

        guide.setStrokeWidth(3);
        showView(MAP, false);
    }

    private void buildViewBar() {
        viewBar.removeAllViews();
        for (int i = 0; i < VIEWS.length; i++) {
            final int v = i;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMargins(Ui.dp(c, 1), 0, Ui.dp(c, 1), 0);
            viewBar.addView(Ui.chip(c, VIEWS[i], i == view, th, x -> showView(v, false)), lp);
        }
    }

    private void showView(int v, boolean auto) {
        view = v;
        autoView = auto;
        buildViewBar();
        boolean cam = v >= FRONT;
        radar.setVisibility(v == MAP ? View.VISIBLE : View.GONE);
        camera.setVisibility(cam ? View.VISIBLE : View.GONE);
        note.setVisibility(v == TOP ? View.VISIBLE : View.GONE);
        if (v == TOP) note.setText("Top (bird's-eye) view arrives in Phase 2");
        if (cam) {
            feed.setSlice(v == FRONT ? CameraFeed.FRONT : v == REAR ? CameraFeed.REAR : v == LEFT ? CameraFeed.LEFT : CameraFeed.RIGHT);
            camera.setImageBitmap(null);
        }
        updateCamera();
    }

    private void updateCamera() {
        boolean want = active && view >= FRONT;
        if (want) {
            feed.start(new CameraFeed.Listener() {
                @Override public void onFrame(Bitmap b) {
                    if (view == LEFT || view == RIGHT) drawGuides(b);
                    camera.setImageBitmap(b);
                }
                @Override public void onError(String msg) {
                    camera.setVisibility(View.GONE);
                    note.setVisibility(View.VISIBLE);
                    note.setText("Camera: " + msg + "\nClose OverDrive's live view or the 360 view.");
                }
            });
        } else {
            feed.stop();
        }
    }

    /** Simple distance guide lines on the side cameras (rough until calibrated in Phase 2). */
    private void drawGuides(Bitmap b) {
        Canvas cv = new Canvas(b);
        float w = b.getWidth(), h = b.getHeight();
        int[] cols = {Theme.DANGER, Theme.CAUTION, Theme.SAFE};
        String[] lab = {"3 m", "6 m", "10 m"};
        float[] ys = {0.78f, 0.62f, 0.50f};
        guide.setTextSize(22);
        for (int i = 0; i < 3; i++) {
            guide.setColor(cols[i]);
            float y = h * ys[i];
            cv.drawLine(w * 0.15f, y, w * 0.85f, y, guide);
            cv.drawText(lab[i], w * 0.86f, y + 8, guide);
        }
    }

    @Override public View view() { return root; }

    @Override public void setActive(boolean a) {
        active = a;
        updateCamera();
    }

    @Override public void onCar(CarData.State s) {
        speed.setText(Double.isNaN(s.speedKmh) ? "–" : String.valueOf(Math.round(s.speedKmh)));
        if (!Double.isNaN(s.socPct)) {
            soc.setText(Math.round(s.socPct) + "%");
            socBar.setProgress((int) Math.round(s.socPct));
        }
        range.setText(s.rangeKm >= 0 ? "  " + s.rangeKm + " km" : "  – km");
        radar.setRadar(s.radar);

        // Turn signal: show the side camera, go back to Map a set time after it stops
        if (prefs.turnCamera()) {
            if (s.turn == 1 && view != LEFT) showView(LEFT, true);
            else if (s.turn == 2 && view != RIGHT) showView(RIGHT, true);
            else if (s.turn == 0 && lastTurn != 0 && autoView) {
                main.removeCallbacksAndMessages(null);
                main.postDelayed(() -> { if (autoView && CarData.get(c).last().turn == 0) showView(MAP, false); },
                        prefs.turnCameraReturnSec() * 1000L);
            }
        }
        lastTurn = s.turn;

        String st = s.turn == 1 ? "◀ Left signal · auto view" : s.turn == 2 ? "Right signal ▶ · auto view"
                : s.known ? "● Around you · gear " + s.gearName() : "● Waiting for car data…";
        status.setText(st);

        // Alert bar: nearest radar reading
        int min = 999;
        for (int v : s.radar) if (v > 0 && v < min) min = v;
        if (min < 80) {
            alert.setVisibility(View.VISIBLE);
            alert.setText(String.format(Locale.US, "⚠  Obstacle %d cm", min));
            alert.setBackground(Ui.round(min < 40 ? Theme.DANGER : Theme.CAUTION, 0, 14, c));
        } else {
            alert.setVisibility(View.GONE);
        }
    }
}
