package com.airodynamic.adas;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/**
 * The car's microphone as 16 kHz mono (what the wake word and Gemini want), whatever the car's sound chip accepts
 * (2026-11-22: "microphone busy" — 16 kHz mono from the voice-recognition input was refused). Tries the usual inputs,
 * rates and channel counts, then mixes and converts.
 */
final class Mic {
    final AudioRecord rec;
    final int rate, channels;
    final String desc;
    private short[] raw = new short[0];
    private double pos;   // resampling position carried between reads

    private Mic(AudioRecord r, int rate, int ch, String desc) { rec = r; this.rate = rate; channels = ch; this.desc = desc; }

    /** Last reason nothing could be opened (for the wake word check). */
    static volatile String lastError = "";

    static Mic open(boolean forChat) {
        int[] sources = forChat
                ? new int[]{MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.DEFAULT}
                : new int[]{MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.DEFAULT, MediaRecorder.AudioSource.VOICE_COMMUNICATION};
        String[] srcName = forChat ? new String[]{"voice call", "mic", "voice recognition", "default"} : new String[]{"voice recognition", "mic", "default", "voice call"};
        int[] rates = {16000, 48000, 44100, 32000};
        int[][] chans = {{AudioFormat.CHANNEL_IN_MONO, 1}, {AudioFormat.CHANNEL_IN_STEREO, 2}};
        StringBuilder why = new StringBuilder();
        for (int si = 0; si < sources.length; si++)
            for (int rate : rates)
                for (int[] ch : chans) {
                    AudioRecord r = null;
                    try {
                        int min = AudioRecord.getMinBufferSize(rate, ch[0], AudioFormat.ENCODING_PCM_16BIT);
                        if (min <= 0) continue;
                        r = new AudioRecord(sources[si], rate, ch[0], AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate / 5 * 2 * ch[1]));
                        if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                            r.startRecording();
                            if (r.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                                lastError = "";
                                return new Mic(r, rate, ch[1], srcName[si] + " · " + rate / 1000 + " kHz · " + (ch[1] == 1 ? "mono" : "stereo"));
                            }
                        }
                    } catch (Throwable e) {
                        if (why.length() < 80) why.append(e.getClass().getSimpleName()).append(' ');
                    }
                    if (r != null) try { r.release(); } catch (Throwable ignored) { }
                }
        lastError = why.length() > 0 ? why.toString().trim() : "every input refused";
        return null;
    }

    /** Fills out with up to out.length samples at 16 kHz mono; returns how many (0 = nothing yet). */
    int read16k(short[] out) {
        int need = (int) Math.ceil(out.length * (rate / 16000.0)) * channels + channels;
        if (raw.length < need) raw = new short[need];
        int n = rec.read(raw, 0, need - need % channels);
        if (n <= 0) return 0;
        int frames = n / channels;
        double step = rate / 16000.0;
        int k = 0;
        double p = pos;
        while (k < out.length && p < frames) {
            int i = (int) p;
            int v = 0;
            for (int c = 0; c < channels; c++) v += raw[i * channels + c];
            out[k++] = (short) (v / channels);
            p += step;
        }
        pos = p - frames;
        if (pos < 0) pos = 0;
        return k;
    }

    void close() {
        try { rec.stop(); } catch (Throwable ignored) { }
        try { rec.release(); } catch (Throwable ignored) { }
    }
}
