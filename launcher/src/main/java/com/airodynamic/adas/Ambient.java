package com.airodynamic.adas;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

/**
 * Ambient light colour for each gear (P / R / N / D) with a style:
 * Solid, Breathe (fades for 5 s) or Flash (blinks for 5 s), then it stays on the gear's colour
 * at your normal brightness. Uses only setIALColor / setIALBrightness (interior light).
 */
public final class Ambient implements CarData.Listener {
    public static final String[] STYLES = {"Solid", "Breathe", "Flash"};
    private static final long EFFECT_MS = 5000;

    private static Ambient instance;
    public static synchronized Ambient get(Context c) {
        if (instance == null) {
            instance = new Ambient(c.getApplicationContext());
            CarData.get(c).addListener(instance);
        }
        return instance;
    }

    private final Context c;
    private final Prefs prefs;
    private final Handler h;
    private int lastGear = -1;
    private volatile int run;          // bumps on every new gear so an old effect stops
    private volatile boolean effecting;
    private int normal = 5;
    /** What the car answered to the last colour sent (shown in Settings, to see if it listens). */
    public volatile String report = "nothing sent yet";
    /** What happened at the last gear change (shown in Settings). */
    public volatile String gearReport = "no gear change seen yet";            // your own brightness, read when no effect is running

    private Ambient(Context c) {
        this.c = c;
        prefs = new Prefs(c);
        HandlerThread t = new HandlerThread("ambient");
        t.start();
        h = new Handler(t.getLooper());
    }

    /** 0 P, 1 R, 2 N, 3 D, -1 unknown */
    private static int slot(int gear) {
        switch (gear) { case 0: case 1: return 0; case 2: return 1; case 3: return 2; case 4: return 3; default: return -1; }
    }

    private volatile boolean overLimit;
    private volatile int before = -1;   // the colour before the over-limit warning, put back afterwards

    private long lastBlink;

    /**
     * Same speed as the screen turning red (Auto Sport speed, user's choice 2026-11-10):
     * above it, blink; 10 km/h below it, back to normal. Works whether Auto Sport is on or off.
     */
    private void checkSpeed(CarData.State s) {
        if (Double.isNaN(s.speedKmh)) return;
        int k = prefs.autoSportKmh();
        long now = System.currentTimeMillis();
        if (s.speedKmh > k) {
            if (!overLimit || now - lastBlink >= 20_000) { lastBlink = now; speedOver(true); }
        } else if (s.speedKmh < k - 10 && overLimit) {
            speedOver(false);
        }
    }

    /**
     * Over the red-screen speed: blink the over-limit colour for 5 s (again on each
     * repeat while still over), then hold it; back under: put the earlier colour back.
     */
    public void speedOver(boolean over) {
        if (!prefs.ambientOver()) { if (!over && overLimit) restore(); overLimit = false; return; }
        if (over) {
            if (!overLimit) {
                overLimit = true;
                h.post(() -> {   // remember the colour first, then blink
                    Object v = CarData.get(c).ambient("getIALColor", 1);
                    if (v instanceof Number && before < 0) before = ((Number) v).intValue();
                    apply(prefs.ambientOverColour(), 2);
                });
            } else {
                apply(prefs.ambientOverColour(), 2);
            }
        } else if (overLimit) {
            overLimit = false;
            restore();
        }
    }

    private void restore() {
        int g = lastGear;
        int col = prefs.ambientGear() && g >= 0 ? prefs.ambientColour(g) : before;
        before = -1;
        if (col > 0) apply(col, 0);
    }

    @Override public void onCar(CarData.State s) {
        checkSpeed(s);
        int g = slot(s.gear);
        if (g < 0 || g == lastGear) return;
        boolean first = lastGear < 0;
        lastGear = g;
        if (first) return;   // only on a real gear change, never at start-up
        if (!prefs.ambientGear()) { gearReport = "gear " + Prefs.GEARS[g] + ": 'Ambient light by gear' is Off"; return; }
        if (overLimit) return;
        final int col = prefs.ambientColour(g), gi = g;
        apply(col, prefs.ambientStyle());
        // the car may set its own colour just after a gear change: send ours again a moment later
        h.postDelayed(() -> {
            CarData.get(c).ambient("setIALColor", col);
            Object back = CarData.get(c).ambient("getIALColor", 1);
            gearReport = "gear " + Prefs.GEARS[gi] + " at " + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date())
                    + ": sent colour " + col + ", car now reports " + back;
        }, 1800);
    }

    /** Switching "Ambient light by gear" on: show the current gear's colour straight away. */
    public void applyCurrentGear() {
        int g = slot(CarData.get(c).last().gear);
        if (g >= 0) { apply(prefs.ambientColour(g), 0); gearReport = "switched on in gear " + Prefs.GEARS[g] + ": sent colour " + prefs.ambientColour(g); }
        else gearReport = "switched on, but the gear isn't known yet (" + CarData.get(c).last().gear + ")";
    }

    /** Also used by the "Try" buttons in Settings. */
    public void apply(int colour, int style) {
        final int my = ++run;
        h.removeCallbacksAndMessages(null);
        h.post(() -> {
            CarData car = CarData.get(c);
            if (!effecting) {
                Object b = car.ambient("getIALBrightness", 1);
                if (b instanceof Number && ((Number) b).intValue() > 1) normal = ((Number) b).intValue();
            }
            int hi = normal;
            Object res = car.ambient("setIALColor", colour);
            Object back = car.ambient("getIALColor", 1);
            report = "sent colour " + colour + " · car answered " + res + " · car now reports " + back;
            if (style == 0) { if (effecting) car.ambient("setIALBrightness", hi); effecting = false; return; }
            effecting = true;
            long step = style == 1 ? 250 : 400;
            long t0 = System.currentTimeMillis();
            int i = 0;
            while (my == run && System.currentTimeMillis() - t0 < EFFECT_MS) {
                int level;
                if (style == 2) level = (i % 2 == 0) ? 1 : hi;                   // Flash: off-ish / on
                else { int k = i % (2 * (hi - 1)); level = 1 + (k < hi - 1 ? k : 2 * (hi - 1) - k); }  // Breathe: up and down
                car.ambient("setIALBrightness", level);
                i++;
                try { Thread.sleep(step); } catch (InterruptedException e) { break; }
            }
            if (my == run) { car.ambient("setIALBrightness", hi); effecting = false; }   // back to your normal brightness
        });
    }
}
