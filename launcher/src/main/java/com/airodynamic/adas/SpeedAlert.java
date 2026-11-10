package com.airodynamic.adas;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/**
 * My speed limit (the user's own limit, not the road's): warning only.
 * Over the limit for 2 s: two beeps (if 🔊 is on) and the ambient light blinks (if switched on),
 * again every 20 s while still over;
 * clears when 3 km/h below. The Info card turns red while over (InfoPanel reads over()).
 * The car has no voice engine, so beeps for now; recorded voice clips can come later.
 */
public final class SpeedAlert implements CarData.Listener {
    private static SpeedAlert instance;
    public static synchronized SpeedAlert get(Context c) {
        if (instance == null) {
            instance = new SpeedAlert(c.getApplicationContext());
            CarData.get(c).addListener(instance);
        }
        return instance;
    }

    private final Prefs prefs;
    private final Context c;
    private boolean over;
    private long overSince, lastBeep;

    private SpeedAlert(Context c) { this.c = c; prefs = new Prefs(c); }

    public boolean over() { return over; }

    @Override public void onCar(CarData.State s) {
        if (Double.isNaN(s.speedKmh)) return;
        int lim = prefs.speedLimit();
        long now = System.currentTimeMillis();
        if (s.speedKmh > lim + 1) {
            if (overSince == 0) overSince = now;
            if (now - overSince >= 2000) {
                over = true;
                if (now - lastBeep >= 20_000) {
                    lastBeep = now;
                    if (prefs.speedVoice()) beep();
                    Ambient.get(c).speedOver(true);   // blink the ambient light (if switched on)
                }
            }
        } else if (s.speedKmh < lim - 3) {
            if (over) Ambient.get(c).speedOver(false);
            over = false;
            overSince = 0;
            lastBeep = 0;
        } else {
            overSince = over ? overSince : 0;
        }
    }

    private static void beep() {
        new Thread(() -> {
            try {
                int rate = 44100;
                short[] pcm = new short[rate / 5];
                for (int i = 0; i < pcm.length; i++) pcm[i] = (short) (Math.sin(2 * Math.PI * 988 * i / rate) * 12000);
                AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, pcm.length * 2, AudioTrack.MODE_STATIC);
                t.write(pcm, 0, pcm.length);
                for (int k = 0; k < 2; k++) {
                    t.stop();
                    t.reloadStaticData();
                    t.play();
                    Thread.sleep(350);
                }
                t.release();
            } catch (Throwable ignored) {}
        }, "speed-beep").start();
    }
}
