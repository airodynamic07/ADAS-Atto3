package com.airodynamic.adas;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.HandlerThread;

/**
 * My speed limit (the user's own limit, not the road's): warning only.
 * Over the limit for 2 s: beep-beep-beep continuously, about 5 beeps every 3 seconds (user, 2026-11-11),
 * as long as you stay over and 🔊 is on; stops when 3 km/h below the limit. The card turns red while over.
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

    private static final long PERIOD_MS = 600;   // 5 beeps in 3 s
    private final Prefs prefs;
    private final Handler h;
    private volatile boolean over, beeping;
    private long overSince;
    private AudioTrack track;

    private SpeedAlert(Context c) {
        prefs = new Prefs(c);
        HandlerThread t = new HandlerThread("speed-beep");
        t.start();
        h = new Handler(t.getLooper());
    }

    public boolean over() { return over; }

    @Override public void onCar(CarData.State s) {
        if (Double.isNaN(s.speedKmh)) return;
        int lim = prefs.speedLimit();
        long now = System.currentTimeMillis();
        if (s.speedKmh > lim + 1) {
            if (overSince == 0) overSince = now;
            if (now - overSince >= 2000) over = true;
        } else if (s.speedKmh < lim - 3) {
            over = false;
            overSince = 0;
        } else if (!over) {
            overSince = 0;
        }
        boolean want = over && prefs.speedVoice();
        if (want && !beeping) { beeping = true; h.post(beepLoop); }
        else if (!want) beeping = false;
    }

    private final Runnable beepLoop = new Runnable() {
        @Override public void run() {
            if (!beeping) return;
            beep();
            h.postDelayed(this, PERIOD_MS);
        }
    };

    /** One short 988 Hz beep (about 0.15 s) on the media stream. */
    private void beep() {
        try {
            if (track == null) {
                int rate = 44100;
                short[] pcm = new short[rate * 15 / 100];
                for (int i = 0; i < pcm.length; i++) {
                    double env = Math.min(1, Math.min(i, pcm.length - i) / 300.0);   // soft edges, no click
                    pcm[i] = (short) (Math.sin(2 * Math.PI * 988 * i / rate) * 12000 * env);
                }
                track = new AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, pcm.length * 2, AudioTrack.MODE_STATIC);
                track.write(pcm, 0, pcm.length);
            }
            track.stop();
            track.reloadStaticData();
            track.play();
        } catch (Throwable ignored) {}
    }
}
