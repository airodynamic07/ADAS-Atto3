package com.airodynamic.adas;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.location.Location;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * Right window, one page (user's choice): tyres + doors/lock, trip / battery health /
 * odometer, PM2.5 / my speed limit, now playing (weather, parked spot and assistant removed 2026-11-10).
 * No climate panel: the car's own bottom bar does the A/C.
 */
public final class InfoPanel implements Panel {
    private static final double LOW_BAR = 2.3;   // = 33 psi = 230 kPa

    private final Context c;
    private final Theme th;
    private final Prefs prefs;
    private final LinearLayout root;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean active;

    private final TextView[] tyre = new TextView[4];
    private final TextView[] tyreT = new TextView[4];
    private final DoorsView doors;
    private TextView drlBtn;
    private final TextView doorsText, trip, tripSub, health, healthSub, odo, odoSub, serviceLabel;
    private final MusicCard music;

    public InfoPanel(Context c, Theme th, Prefs prefs) {
        this.c = c;
        this.th = th;
        this.prefs = prefs;
        root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(c, 8);
        root.setPadding(pad, pad, pad, pad);

        // --- top (user's layout, 2026-11-11): [battery health | trip] over [odometer] on the left;
        //     doors · windows · tyres drawing on the right, tyre pressure/temperature beside each wheel
        LinearLayout top = new LinearLayout(c);
        LinearLayout leftCol = new LinearLayout(c);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout r2 = new LinearLayout(c);
        LinearLayout t2 = tile("Battery health");
        health = big(t2); health.setTextColor(0xFFFF8A1F);   // orange (user, 2026-11-13) healthSub = small(t2); healthSub.setVisibility(View.GONE);   // shorter cards (2026-11-13)
        LinearLayout t1 = tile("Date");   // was the car's trip (user, 2026-11-13): day and month
        trip = big(t1); tripSub = small(t1); tripSub.setVisibility(View.GONE);
        r2.addView(t2, weight(1, true)); r2.addView(t1, weight(1, false));
        LinearLayout.LayoutParams r2lp = new LinearLayout.LayoutParams(-1, -2);
        r2lp.bottomMargin = Ui.dp(c, 6);
        leftCol.addView(r2, r2lp);
        // Odometer on the left, "Service in" on the right, a bit smaller (user's drawing, 2026-11-11)
        LinearLayout t3 = card();
        t3.setOrientation(LinearLayout.HORIZONTAL);
        t3.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout oL = new LinearLayout(c), oR = new LinearLayout(c);
        oL.setOrientation(LinearLayout.VERTICAL); oR.setOrientation(LinearLayout.VERTICAL);
        oL.addView(label("Odometer"));
        odo = big(oL); odo.setTextColor(0xFFFF8A1F);   // orange (user, 2026-11-13)
        oR.addView(serviceLabel = label("Service in"));
        odoSub = Ui.text(c, "–", 15, th.text, true);
        odoSub.setSingleLine(true);
        odoSub.setPadding(0, Ui.dp(c, 2), 0, 0);
        oR.addView(odoSub);
        t3.addView(oL, new LinearLayout.LayoutParams(0, -2, 1.15f));
        t3.addView(oR, new LinearLayout.LayoutParams(0, -2, 1f));
        t3.setOnClickListener(v -> serviceDialog());
        leftCol.addView(t3, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout.LayoutParams lcl = new LinearLayout.LayoutParams(0, -1, 1.6f);
        lcl.rightMargin = Ui.dp(c, 6);
        top.addView(leftCol, lcl);

        LinearLayout dw = card();
        dw.setGravity(Gravity.CENTER_HORIZONTAL);
        dw.setPadding(Ui.dp(c, 5), Ui.dp(c, 5), Ui.dp(c, 5), Ui.dp(c, 5));
        String unit = prefs.tyreUnit();
        TextView dl = label("Doors · tyres " + (unit.equals("bar") ? "bar" : unit.equals("kpa") ? "kPa" : "psi"));
        dl.setGravity(Gravity.CENTER);
        dw.addView(dl, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout carRow = new LinearLayout(c);
        carRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout lt = tyreCol(0, 2, Gravity.START), rt = tyreCol(1, 3, Gravity.END);   // FL/RL left, FR/RR right
        carRow.addView(lt, new LinearLayout.LayoutParams(0, -1, 1f));
        doors = new DoorsView(c, th);
        carRow.addView(doors, new LinearLayout.LayoutParams(Ui.dp(c, 70), Ui.dp(c, 92)));
        carRow.addView(rt, new LinearLayout.LayoutParams(0, -1, 1f));
        dw.addView(carRow, new LinearLayout.LayoutParams(-1, -2));
        // DRL status (read only); tap = BYD's own settings, where the DRL is switched (user, 2026-11-12)
        LinearLayout dr = new LinearLayout(c);
        dr.setGravity(Gravity.CENTER);
        drlBtn = Ui.text(c, "◐ DRL –", 12, th.muted, true);
        drlBtn.setGravity(Gravity.CENTER);
        drlBtn.setPadding(Ui.dp(c, 9), Ui.dp(c, 2), Ui.dp(c, 9), Ui.dp(c, 2));
        drlBtn.setBackground(Ui.round(th.chip, th.line, 14, c));
        drlBtn.setOnClickListener(v -> openLightSettings());
        dr.addView(drlBtn, new LinearLayout.LayoutParams(-2, -2));
        dw.addView(dr, new LinearLayout.LayoutParams(-1, -2));
        doorsText = Ui.text(c, "", 10, Theme.SAFE, false);
        doorsText.setVisibility(View.GONE);   // the drawing's colours already show open / closed (user, 2026-11-13)
        doorsText.setGravity(Gravity.CENTER);
        dw.addView(doorsText, new LinearLayout.LayoutParams(-1, -2));
        top.addView(dw, new LinearLayout.LayoutParams(0, -2, 1.25f));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.bottomMargin = Ui.dp(c, 6);
        root.addView(top, tlp);

        // --- row 4: now playing (cover, song, prev/play/next) — moved here from the right window (2026-11-11)
        music = new MusicCard(c, th);
        music.setVideoOpener(this::openVideo);
        root.addView(music.view(), new LinearLayout.LayoutParams(-1, 0, 1f));   // fills the window to the bottom (lyrics)

    }

    // ---------------------------------------------------------------- building helpers

    /** Two tyres stacked (front over rear) beside the car drawing. */
    private LinearLayout tyreCol(int front, int rear, int gravity) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(gravity | Gravity.CENTER_VERTICAL);
        for (int i : new int[]{front, rear}) {
            tyre[i] = Ui.text(c, "–", 16, th.text, true);
            tyre[i].setGravity(gravity);
            tyreT[i] = Ui.text(c, "", 10, th.muted, false);
            tyreT[i].setGravity(gravity);
            col.addView(tyre[i], new LinearLayout.LayoutParams(-1, -2));
            col.addView(tyreT[i], new LinearLayout.LayoutParams(-1, -2));
            if (i == front) col.addView(new View(c), new LinearLayout.LayoutParams(1, Ui.dp(c, 14)));
        }
        return col;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(c, 6);
        r.setLayoutParams(lp);
        return r;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(c, 9);
        l.setPadding(p, Ui.dp(c, 7), p, Ui.dp(c, 7));
        l.setBackground(Ui.round(th.card, th.line, 16, c));
        return l;
    }

    private LinearLayout tile(String name) {
        LinearLayout l = card();
        l.addView(label(name));
        return l;
    }

    private TextView label(String s) {
        TextView t = Ui.label(c, s, th);
        t.setTextSize(10);
        return t;
    }

    private TextView big(LinearLayout parent) {
        TextView t = Ui.text(c, "–", 18, th.text, true);
        t.setPadding(0, Ui.dp(c, 2), 0, 0);
        parent.addView(t);
        return t;
    }

    private TextView small(LinearLayout parent) {
        TextView t = Ui.text(c, "", 10, th.muted, false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        parent.addView(t);
        return t;
    }

    /** A compact chip (32 dp tall) for the one-line cards. */
    private Button small(Button b) {
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    private LinearLayout.LayoutParams weight(float w, boolean gapRight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, w);
        if (gapRight) lp.rightMargin = Ui.dp(c, 6);
        return lp;
    }

    private LinearLayout.LayoutParams sq() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 40));
        lp.leftMargin = Ui.dp(c, 4);
        return lp;
    }


    // ---------------------------------------------------------------- actions

    private void serviceDialog() {
        CarData.State s = CarData.get(c).last();
        int base = prefs.serviceAtKm() > 0 ? prefs.serviceAtKm()
                : (Double.isNaN(s.odometerKm) ? 10000 : (int) Math.round(s.odometerKm / 1000.0) * 1000 + 10000);
        final int[] v = {base};
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(c, 20), Ui.dp(c, 12), Ui.dp(c, 20), 0);
        TextView t = Ui.text(c, "", 26, th.text, true);
        t.setGravity(Gravity.CENTER);
        Runnable show = () -> t.setText(String.format(Locale.US, "%,d km", v[0]));
        show.run();
        box.addView(t);
        LinearLayout b = new LinearLayout(c);
        b.addView(Ui.chip(c, "− 1,000", false, th, x -> { v[0] = Math.max(1000, v[0] - 1000); show.run(); }), weight(1, true));
        b.addView(Ui.chip(c, "+ 1,000", false, th, x -> { v[0] += 1000; show.run(); }), weight(1, false));
        box.addView(b, new LinearLayout.LayoutParams(-1, Ui.dp(c, 48)));
        new AlertDialog.Builder(c).setTitle("Next service at (odometer)").setView(box)
                .setPositiveButton("Save", (d, w) -> prefs.setServiceAtKm(v[0]))
                .setNegativeButton("Cancel", null).show();
    }

    private final Runnable slow = new Runnable() {
        @Override public void run() {
            if (!active) return;
            main.postDelayed(this, 5000);
        }
    };

    // ---------------------------------------------------------------- Panel

    @Override public View view() { return root; }

    // ---- the song's video over the Now playing card (user, 2026-11-13)
    private boolean pausedForVideo, videoOpen;
    static final String ACTION_VIDEO_CLOSE = "com.airodynamic.adas.VIDEO_CLOSE";
    private void openVideo() {
        String q = music.songQuery();
        if (q == null) { android.widget.Toast.makeText(c, "Play a song first", android.widget.Toast.LENGTH_SHORT).show(); return; }
        if (!prefs.videoWhileDriving() && !CarData.get(c).last().parked()) {
            android.widget.Toast.makeText(c, "Video only in P (Settings)", android.widget.Toast.LENGTH_SHORT).show(); return;
        }
        if (!(c instanceof android.app.Activity)) return;
        View v = music.view();
        int[] l = new int[2];
        v.getLocationOnScreen(l);
        pausedForVideo = music.pauseMusic();
        videoOpen = true;
        String t = th == Theme.RED ? "red" : th == Theme.WHITE ? "white" : "blue";
        VideoActivity.open((android.app.Activity) c, l[0], l[1], v.getWidth(), v.getHeight(), q, t);
    }

    @Override public void setActive(boolean a) {
        active = a;
        main.removeCallbacks(slow);
        if (a) main.post(slow);
        music.setActive(a);
        if (a && videoOpen) { videoOpen = false; if (pausedForVideo) music.resumeMusic(); pausedForVideo = false; }   // video closed
    }

    /** BYD's own settings (the DRL switch is there); if no BYD settings app is found, Android's Settings. */
    private void openLightSettings() {
        android.content.pm.PackageManager pm = c.getPackageManager();
        for (String pkg : new String[]{"com.byd.vehiclesetting", "com.byd.carsetting", "com.byd.carsettings", "com.byd.setting",
                "com.byd.settings", "com.byd.bydsetting", "com.byd.vehiclesettings", "com.byd.autosetting"}) {
            android.content.Intent i = pm.getLaunchIntentForPackage(pkg);
            if (i != null) { try { c.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); return; } catch (Exception ignored) { } }
        }
        try { c.startActivity(new android.content.Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) { android.widget.Toast.makeText(c, "Couldn't open the car's settings", android.widget.Toast.LENGTH_SHORT).show(); }
    }

    @Override public void onCar(CarData.State s) {
        String[] names = {"FL", "FR", "RL", "RR"};
        for (int i = 0; i < 4; i++) {
            double b = s.tyreBar[i];
            boolean low = !Double.isNaN(b) && b < LOW_BAR;
            tyre[i].setText(Double.isNaN(b) ? "–" : pressure(b, prefs.tyreUnit()));
            tyre[i].setTextColor(low ? Theme.CAUTION : th.text);
            tyreT[i].setText(s.tyreC[i] == Integer.MIN_VALUE ? names[i] : s.tyreC[i] + "°C");
        }
        doors.set(s);
        if (videoOpen && !prefs.videoWhileDriving() && !s.parked())   // "Video only in P": close it when leaving P
            c.sendBroadcast(new android.content.Intent(ACTION_VIDEO_CLOSE).setPackage(c.getPackageName()));
        boolean open = s.anyOpen();
        String lock = s.lock == 1 ? " · 🔒 Locked" : s.lock == 2 ? " · Unlocked" : "";
        doorsText.setText((s.sunroofOpen ? "Sunroof open" : open ? "Something open" : "All closed") + lock
                + (s.roofPos != Integer.MIN_VALUE || s.roofState != Integer.MIN_VALUE
                    ? "\nroof " + (s.roofPos == Integer.MIN_VALUE ? "–" : s.roofPos) + " / " + (s.roofState == Integer.MIN_VALUE ? "–" : s.roofState) : "")
                + (s.bootRaw.isEmpty() ? "" : "\nboot " + s.bootRaw)
                + (s.drlRaw.isEmpty() ? "" : "\ndrl " + s.drlRaw));   // raw values, temporary
        boolean drlOn = s.drl == 1;
        drlBtn.setText(s.drl < 0 ? "◐ DRL ? ›" : drlOn ? "◐ DRL ON ›" : "◐ DRL OFF ›");
        drlBtn.setTextColor(drlOn ? Theme.SAFE : th.muted);
        drlBtn.setBackground(Ui.round(drlOn ? (Theme.SAFE & 0x00FFFFFF) | 0x33000000 : th.chip, drlOn ? Theme.SAFE : th.line, 14, c));
        doorsText.setTextColor(open || s.lock == 2 ? Theme.CAUTION : Theme.SAFE);

        java.util.Date now = new java.util.Date();
        trip.setText(new java.text.SimpleDateFormat("d MMM", Locale.US).format(now));
        tripSub.setVisibility(View.VISIBLE);
        tripSub.setText(new java.text.SimpleDateFormat("EEEE", Locale.US).format(now));
        health.setText(Double.isNaN(s.healthPct) ? "–" : String.format(Locale.US, "%.0f%%", s.healthPct));
        healthSub.setText(Double.isNaN(s.healthPct) ? (s.known ? "car not reporting" : "from the car") : "from the car");
        odo.setText(Double.isNaN(s.odometerKm) ? "–" : String.format(Locale.US, "%,d", Math.round(s.odometerKm)));
        int due = prefs.serviceAtKm();
        if (due > 0 && !Double.isNaN(s.odometerKm)) {
            long left = Math.round(due - s.odometerKm);
            serviceLabel.setText(left > 0 ? "SERVICE IN" : "SERVICE");
            odoSub.setText(left > 0 ? String.format(Locale.US, "%,d km", left) : "due now");
            odoSub.setTextColor(left < 1000 ? Theme.CAUTION : th.text);
        } else {
            serviceLabel.setText("SERVICE");
            odoSub.setText("tap to set");
            odoSub.setTextColor(th.muted);
        }

    }

    /** Pressure in the chosen unit: PSI (default), bar or kPa. */
    static String pressure(double bar, String unit) {
        if ("bar".equals(unit)) return String.format(Locale.US, "%.1f", bar);
        if ("kpa".equals(unit)) return String.format(Locale.US, "%.0f", bar * 100);
        return String.format(Locale.US, "%.0f", bar * 14.5038);
    }

    private static String level(int pm) {
        if (pm < 0) return "–";
        return pm <= 25 ? "good" : pm <= 50 ? "moderate" : "unhealthy";
    }

    /**
     * The car from above (nose up), drawn as the driver sees it: right side on the right.
     * Doors swing open and closed with an animation; the boot lid slides out behind.
     * Closed = teal, open door or window = amber.
     */
    static final class DoorsView extends View {
        private final Theme th;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final android.graphics.Path path = new android.graphics.Path();
        private CarData.State s;
        // animated opening 0..1 for: FR, FL, RR, RL (car areas 1..4), boot
        private final float[] cur = new float[5];
        private static final int[] WIN_FOR_DOOR = {2, 1, 3, 4};   // window on the same side as the drawn door
        private long lastFrame;

        DoorsView(Context c, Theme th) { super(c); this.th = th; }

        void set(CarData.State st) { s = st; invalidate(); }

        private boolean open(int area) { return s != null && s.doorOpen[area]; }

        @Override protected void onDraw(Canvas cv) {
            // --- animation step (about 0.45 s for a full swing)
            long now = android.os.SystemClock.uptimeMillis();
            float dt = lastFrame == 0 ? 0 : Math.min(0.05f, (now - lastFrame) / 1000f);
            lastFrame = now;
            boolean moving = false;
            for (int i = 0; i < 5; i++) {
                float target = open(i + 1) ? 1f : 0f;
                float step = dt / 0.45f;
                if (Math.abs(target - cur[i]) > 0.001f) {
                    cur[i] += Math.signum(target - cur[i]) * Math.min(step, Math.abs(target - cur[i]));
                    moving = true;
                }
            }

            float w = getWidth(), h = getHeight(), d = getResources().getDisplayMetrics().density;
            float bw = Math.min(w * 0.42f, h * 0.55f), x0 = (w - bw) / 2, x1 = x0 + bw;
            float top = d * 2, bot = h - d * 10;     // room behind the car for the boot lid

            // body
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * d);
            p.setColor(th.text);
            r.set(x0, top, x1, bot);
            cv.drawRoundRect(r, bw * 0.38f, bw * 0.30f, p);
            // windscreen and rear glass
            p.setStrokeWidth(1.4f * d);
            p.setColor(th.muted);
            float bh = bot - top;
            cv.drawLine(x0 + bw * 0.18f, top + bh * 0.26f, x1 - bw * 0.18f, top + bh * 0.26f, p);
            cv.drawLine(x0 + bw * 0.2f, top + bh * 0.82f, x1 - bw * 0.2f, top + bh * 0.82f, p);
            // sunroof
            boolean sun = s != null && s.sunroofOpen;
            p.setStyle(Paint.Style.FILL);
            p.setColor(sun ? Theme.CAUTION : th.muted);
            r.set(x0 + bw * 0.26f, top + bh * 0.34f, x1 - bw * 0.26f, top + bh * 0.48f);
            cv.drawRoundRect(r, 3 * d, 3 * d, p);

            // doors: {area index 0..3, right side?, hinge y, end y} — FR/FL front, RR/RL rear
            // rear doors: area 3 is drawn on the left, area 4 on the right (user, 2026-11-12: they were swapped)
            float[][] door = {{0, 1, 0.28f, 0.52f}, {1, 0, 0.28f, 0.52f}, {2, 0, 0.55f, 0.78f}, {3, 1, 0.55f, 0.78f}};
            p.setStrokeCap(Paint.Cap.ROUND);
            for (float[] dr : door) {
                int i = (int) dr[0];
                boolean right = dr[1] == 1;
                float k = ease(cur[i]);
                float hx = right ? x1 : x0, hy = top + bh * dr[2];
                float len = bh * (dr[3] - dr[2]);
                double ang = Math.toRadians(58 * k);           // swings outward around the front hinge
                float ex = hx + (float) Math.sin(ang) * len * (right ? 1 : -1);
                float ey = hy + (float) Math.cos(ang) * len;
                // windows are numbered differently from doors on BYD: 1 front-left, 2 front-right, 3 rear-left, 4 rear-right
                // (drawn door order is FR, FL, RR, RL), so FR uses window 2, FL 1, RR 4, RL 3
                boolean win = s != null && s.windowPct[WIN_FOR_DOOR[i]] > 0;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(3.5f * d);
                p.setColor(k > 0.02f ? Theme.CAUTION : win ? Theme.CAUTION : Theme.SAFE);
                cv.drawLine(hx, hy, ex, ey, p);
                if (win && k < 0.02f) {   // open window: small amber notch inside the door
                    p.setStrokeWidth(2 * d);
                    float ix = right ? hx - 4 * d : hx + 4 * d;
                    cv.drawLine(ix, hy + len * 0.25f, ix, hy + len * 0.75f, p);
                }
            }
            // DRL on: a small glowing arc at the front of the car
            if (s != null && s.drl == 1) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(3f * d);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setColor(Theme.SAFE);
                cv.drawArc(new android.graphics.RectF(x0 + bw * .15f, top - d, x1 - bw * .15f, top + bw * .5f), 205, 130, false, p);
            }
            // boot: lid slides out behind the car when open
            float kb = ease(cur[4]);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3.5f * d);
            p.setColor(kb > 0.02f ? Theme.CAUTION : Theme.SAFE);
            float by = bot - d + kb * 8 * d;
            cv.drawLine(x0 + bw * 0.28f, by, x1 - bw * 0.28f, by, p);

            if (moving) postInvalidateOnAnimation(); else lastFrame = 0;
        }

        private static float ease(float x) { return x < 0.5f ? 2 * x * x : 1 - (float) Math.pow(-2 * x + 2, 2) / 2; }
    }
}
