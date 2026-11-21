package com.airodynamic.adas;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * "Hey Natee chat" (user, 2026-11-21): a voice chat with Google's Gemini (Live API), straight from the launcher, no
 * Gemini app needed. An offline wake-word listener (Vosk, nothing leaves the car) waits for "hey natee chat"; then a
 * bubble opens over the screen and the voice goes to Gemini, which answers by voice and text. It can read the car's
 * information and change comfort things only (fan, temperature, A/C, cabin light, sunshade: see CarControl), never
 * anything about driving (user's OK for comfort controls, 2026-11-21).
 * Ends with ✕, "bye natee", or 20 s of quiet.
 */
public final class AiChat extends Service {
    private static final int IN_RATE = 16000, OUT_RATE = 24000;
    private static final long QUIET_END_MS = 20_000;
    /** Live models, newest first: if Google refuses one (renamed or retired), the next is tried. */
    private static final String[] MODELS = {
            "models/gemini-2.5-flash-native-audio-preview-09-2025",
            "models/gemini-live-2.5-flash-preview",
            "models/gemini-2.0-flash-live-001"};

    static void sync(Context c) {
        Intent i = new Intent(c, AiChat.class);
        try {
            if (new Prefs(c).aiChat()) c.startService(i); else c.stopService(i);
        } catch (Throwable ignored) { }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean alive, chatting, muted, speaking;
    private Thread wakeThread;
    private org.vosk.Model model;
    private Prefs prefs;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        control = new CarControl(this);
        alive = true;
        startWake();
    }

    @Override public int onStartCommand(Intent i, int f, int id) { return START_STICKY; }

    @Override public void onDestroy() {
        alive = false;
        endChat();
        if (wakeThread != null) wakeThread.interrupt();
        super.onDestroy();
    }

    // ---------------- wake word ----------------
    private void startWake() {
        wakeThread = new Thread(() -> {
            try {
                if (model == null) {
                    org.vosk.LibVosk.setLogLevel(org.vosk.LogLevel.WARNINGS);
                    model = new org.vosk.Model(org.vosk.android.StorageService.sync(this, "vosk-model", "model"));
                }
            } catch (Throwable e) { status("Hey Natee chat: wake word engine couldn't start (" + e.getClass().getSimpleName() + ")"); return; }
            // only these phrases are listened for ("natee" isn't an English word: the nearest sounds stand in for it)
            String grammar = "[\"hey natty chat\", \"hey nate chat\", \"hey nature chat\", \"hey naughty chat\", \"hey [unk] chat\", \"[unk]\"]";
            while (alive) {
                if (chatting) { sleep(300); continue; }
                AudioRecord rec = null;
                try (org.vosk.Recognizer r = new org.vosk.Recognizer(model, IN_RATE, grammar)) {
                    int buf = Math.max(AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 6400);
                    rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf);
                    if (rec.getState() != AudioRecord.STATE_INITIALIZED) { rec.release(); rec = null; sleep(10_000); continue; }   // microphone busy: try again later
                    rec.startRecording();
                    short[] b = new short[1600];   // 0.1 s
                    while (alive && !chatting) {
                        int n = rec.read(b, 0, b.length);
                        if (n <= 0) { sleep(200); continue; }
                        boolean fin = r.acceptWaveForm(b, n);
                        String txt = fin ? r.getResult() : r.getPartialResult();
                        if (isWake(txt)) { r.reset(); main.post(this::startChat); break; }
                    }
                } catch (Throwable e) {
                    sleep(5000);
                } finally {
                    if (rec != null) { try { rec.stop(); } catch (Throwable ignored) { } rec.release(); }
                }
            }
        }, "hey-natee");
        wakeThread.start();
    }

    private static boolean isWake(String json) {
        if (json == null) return false;
        String t = json.toLowerCase(java.util.Locale.US);
        int h = t.indexOf("hey "), c = t.indexOf(" chat");
        return h >= 0 && c > h;
    }

    // ---------------- chat ----------------
    private WebSocket ws;
    private OkHttpClient http;
    private AudioTrack track;
    private Thread micThread, playThread;
    private final LinkedBlockingQueue<byte[]> playQ = new LinkedBlockingQueue<>();
    private int modelIdx;
    private volatile boolean ready;
    private volatile long lastActive;
    private final StringBuilder you = new StringBuilder(), gem = new StringBuilder();
    private boolean newTurn = true;
    private AudioManager.OnAudioFocusChangeListener focus = f -> { };

    private void startChat() {
        if (chatting || !alive) return;
        String key = prefs.aiKey();
        if (key.isEmpty()) { status("Hey Natee chat: add your Gemini API key in Settings → AI chat"); return; }
        chatting = true; muted = false; ready = false;
        lastActive = System.currentTimeMillis();
        you.setLength(0); gem.setLength(0); newTurn = true; lastAction = "";
        showBubble();
        setState("connecting…");
        beep();
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        try { am.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK); } catch (Throwable ignored) { }
        modelIdx = 0;
        http = new OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build();
        connect();
        main.postDelayed(quietCheck, 1000);
    }

    private final Runnable quietCheck = new Runnable() {
        @Override public void run() {
            if (!chatting) return;
            if (!speaking && System.currentTimeMillis() - lastActive > QUIET_END_MS) { endChat(); return; }
            main.postDelayed(this, 1000);
        }
    };

    private void connect() {
        String url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=" + prefs.aiKey();
        final int idx = modelIdx;
        ws = http.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            @Override public void onOpen(WebSocket s, Response r) { s.send(setupJson(MODELS[idx])); }
            @Override public void onMessage(WebSocket s, String text) { handle(text); }
            @Override public void onMessage(WebSocket s, ByteString bytes) { handle(bytes.utf8()); }
            @Override public void onClosed(WebSocket s, int code, String reason) { closed(idx, code, reason); }
            @Override public void onFailure(WebSocket s, Throwable t, Response r) {
                closed(idx, r != null ? r.code() : -1, t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : ""));
            }
        });
    }

    private void closed(int idx, int code, String reason) {
        main.post(() -> {
            if (!chatting) return;
            if (!ready && idx == modelIdx && modelIdx + 1 < MODELS.length && code != 1006 && code != -1) {   // this model refused: try the next
                modelIdx++;
                connect();
                return;
            }
            setState("couldn't reach Gemini (" + code + (reason == null || reason.isEmpty() ? "" : " " + reason) + ")");
            main.postDelayed(this::endChat, 6000);
        });
    }

    private String setupJson(String model) {
        try {
            String lang = prefs.aiLang();
            String li = "en".equals(lang) ? "Always answer in English." : "th".equals(lang) ? "Always answer in Thai." :
                    "Answer in the language the driver speaks (Thai or English).";
            JSONObject voice = new JSONObject().put("prebuiltVoiceConfig", new JSONObject().put("voiceName", prefs.aiVoice()));
            JSONObject gen = new JSONObject().put("responseModalities", new JSONArray().put("AUDIO"))
                    .put("speechConfig", new JSONObject().put("voiceConfig", voice));
            JSONObject sys = new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text",
                    "You are Natee, a friendly voice assistant in the driver's BYD Atto 3 electric car in Thailand. The driver is"
                            + " driving: keep answers short and easy to listen to (one to three sentences), no lists or links."
                            + " You cannot see the road. You can read the car's information with get_car_info (use it whenever the"
                            + " driver asks about the car: range, battery, tyres, air, temperatures, trip, doors) and change comfort"
                            + " things only: fan, temperature, A/C on/off, cabin light, sunshade. You can also open apps (open_app) and control"
                            + " the music (music). Never anything about driving."
                            + " Understand casual requests (\"too hot\" = temperature down 1-2 degrees or fan up). After a change,"
                            + " say in a few words what you did (\"Fan 4.\"); if the result says it failed, say so plainly."
                            + " Before opening or closing the sunshade, ask the driver to confirm, and only call it after a yes. " + li)));
            JSONObject setup = new JSONObject().put("model", model).put("generationConfig", gen).put("systemInstruction", sys)
                    .put("inputAudioTranscription", new JSONObject()).put("outputAudioTranscription", new JSONObject())
                    .put("tools", CarControl.tools());
            return new JSONObject().put("setup", setup).toString();
        } catch (Exception e) { return "{}"; }
    }

    private void handle(String text) {
        try {
            JSONObject m = new JSONObject(text);
            if (m.has("setupComplete")) { main.post(this::onReady); return; }
            if (m.has("goAway")) return;
            JSONObject tc = m.optJSONObject("toolCall");
            if (tc != null) { runTools(tc.optJSONArray("functionCalls")); return; }
            JSONObject sc = m.optJSONObject("serverContent");
            if (sc == null) return;
            JSONObject it = sc.optJSONObject("inputTranscription");
            if (it != null && it.optString("text").length() > 0) {
                final String t = it.optString("text");
                main.post(() -> {
                    if (newTurn) { you.setLength(0); gem.setLength(0); lastAction = ""; newTurn = false; }
                    you.append(t); lastActive = System.currentTimeMillis(); showText();
                    String all = you.toString().toLowerCase(java.util.Locale.US);
                    if (all.contains("bye natee") || all.contains("bye natty") || all.contains("bye nate") || all.contains("บาย")) main.postDelayed(this::endChat, 1500);
                });
            }
            JSONObject ot = sc.optJSONObject("outputTranscription");
            if (ot != null && ot.optString("text").length() > 0) {
                final String t = ot.optString("text");
                main.post(() -> { if (newTurn) { you.setLength(0); gem.setLength(0); newTurn = false; } gem.append(t); showText(); });
            }
            JSONObject mt = sc.optJSONObject("modelTurn");
            if (mt != null) {
                JSONArray parts = mt.optJSONArray("parts");
                for (int i = 0; parts != null && i < parts.length(); i++) {
                    JSONObject d = parts.getJSONObject(i).optJSONObject("inlineData");
                    if (d != null && d.optString("mimeType").startsWith("audio")) playQ.offer(Base64.decode(d.getString("data"), Base64.DEFAULT));
                }
            }
            if (sc.optBoolean("interrupted")) playQ.clear();
            if (sc.optBoolean("turnComplete")) main.post(() -> { newTurn = true; lastActive = System.currentTimeMillis(); });
        } catch (Exception ignored) { }
    }

    /** Gemini asked to read or change something: run it (comfort only, see CarControl) and send the answer back. */
    private void runTools(JSONArray calls) {
        if (calls == null) return;
        try {
            JSONArray out = new JSONArray();
            for (int i = 0; i < calls.length(); i++) {
                JSONObject fc = calls.getJSONObject(i);
                String name = fc.optString("name");
                JSONObject args = fc.optJSONObject("args");
                JSONObject r = control.run(name, args == null ? new JSONObject() : args);
                if (!"get_car_info".equals(name)) main.post(() -> { lastAction = name.replace('_', ' ') + ": " + r.optString("result"); showText(); });
                out.put(new JSONObject().put("id", fc.optString("id")).put("name", name).put("response", r));
            }
            lastActive = System.currentTimeMillis();
            WebSocket s = ws;
            if (s != null) s.send(new JSONObject().put("toolResponse", new JSONObject().put("functionResponses", out)).toString());
        } catch (Exception ignored) { }
    }
    private CarControl control;
    private String lastAction = "";

    private void onReady() {
        if (!chatting) return;
        ready = true;
        setState("listening…");
        lastActive = System.currentTimeMillis();
        int ob = Math.max(AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), OUT_RATE);
        track = new AudioTrack(AudioManager.STREAM_MUSIC, OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, ob, AudioTrack.MODE_STREAM);
        track.play();
        playThread = new Thread(() -> {
            while (chatting) {
                try {
                    byte[] a = playQ.poll(200, TimeUnit.MILLISECONDS);
                    if (a == null) { if (speaking) { speaking = false; lastActive = System.currentTimeMillis(); main.post(() -> setState("listening…")); } continue; }
                    if (!speaking) { speaking = true; main.post(() -> setState("speaking…")); }
                    AudioTrack t = track;
                    if (t != null) t.write(a, 0, a.length);
                } catch (InterruptedException e) { return; } catch (Throwable ignored) { }
            }
        }, "natee-play");
        playThread.start();
        micThread = new Thread(() -> {
            AudioRecord rec = null;
            try {
                int buf = Math.max(AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 6400);
                rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf);
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) { main.post(() -> setState("microphone busy")); return; }
                try { if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) android.media.audiofx.AcousticEchoCanceler.create(rec.getAudioSessionId()).setEnabled(true); } catch (Throwable ignored) { }
                rec.startRecording();
                byte[] b = new byte[3200];   // 0.1 s
                byte[] quiet = new byte[3200];
                while (chatting) {
                    int n = rec.read(b, 0, b.length);
                    if (n <= 0) continue;
                    // while Gemini talks (and when muted) silence is sent, so it doesn't hear itself through the speakers
                    byte[] send = muted || speaking ? quiet : b;
                    WebSocket s = ws;
                    if (s != null) s.send("{\"realtimeInput\":{\"audio\":{\"mimeType\":\"audio/pcm;rate=16000\",\"data\":\""
                            + Base64.encodeToString(send, 0, n, Base64.NO_WRAP) + "\"}}}");
                }
            } catch (Throwable ignored) {
            } finally { if (rec != null) { try { rec.stop(); } catch (Throwable ignored) { } rec.release(); } }
        }, "natee-mic");
        micThread.start();
    }

    private void endChat() {
        if (!chatting) return;
        chatting = false; ready = false; speaking = false;
        main.removeCallbacks(quietCheck);
        if (ws != null) { try { ws.close(1000, "bye"); } catch (Throwable ignored) { } ws = null; }
        playQ.clear();
        if (track != null) { try { track.stop(); } catch (Throwable ignored) { } track.release(); track = null; }
        try { ((AudioManager) getSystemService(AUDIO_SERVICE)).abandonAudioFocus(focus); } catch (Throwable ignored) { }
        hideBubble();
    }

    // ---------------- bubble over the screen ----------------
    private View bubble;
    private TextView stateV, youV, gemV;

    private void showBubble() {
        if (bubble != null) return;
        Theme th = prefs.theme();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable g = Ui.round(0xEE101F3D, 0xFF7C4DFF, 22, this);
        g.setStroke(Ui.dp(this, 2), 0xFF7C4DFF);
        box.setBackground(g);
        int p = Ui.dp(this, 14);
        box.setPadding(p, Ui.dp(this, 10), p, p);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View orb = new View(this);
        android.graphics.drawable.GradientDrawable og = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{0xFFB388FF, 0xFF3A6FD8});
        og.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        orb.setBackground(og);
        head.addView(orb, new LinearLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30)));
        TextView name = Ui.text(this, "  Gemini", 17, 0xFFFFFFFF, true);
        head.addView(name);
        stateV = Ui.text(this, "", 14, 0xFF9FB0D0, false);
        head.addView(stateV, new LinearLayout.LayoutParams(0, -2, 1f));
        android.widget.Button mute = Ui.chip(this, "🔇 Mute", false, th, null);
        mute.setOnClickListener(v -> { muted = !muted; mute.setText(muted ? "🎤 Unmute" : "🔇 Mute"); setState(muted ? "muted" : "listening…"); });
        head.addView(mute, new LinearLayout.LayoutParams(-2, Ui.dp(this, 40)));
        android.widget.Button close = Ui.chip(this, "✕", false, th, v -> endChat());
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 40));
        cl.leftMargin = Ui.dp(this, 6);
        head.addView(close, cl);
        box.addView(head);
        youV = Ui.text(this, "", 15, 0xFFFFFFFF, false);
        youV.setPadding(0, Ui.dp(this, 8), 0, 0);
        box.addView(youV);
        gemV = Ui.text(this, "Say something…", 15, 0xFFFFFFFF, false);
        gemV.setPadding(0, Ui.dp(this, 6), 0, 0);
        gemV.setMaxLines(6);
        box.addView(gemV);
        TextView hint = Ui.text(this, "talk any time · closes by itself after 20 s of quiet · or say \"bye Natee\"", 12, 0xFF9FB0D0, false);
        hint.setPadding(0, Ui.dp(this, 8), 0, 0);
        box.addView(hint);
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            @SuppressWarnings("deprecation") int type = WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(Ui.dp(this, 520), -2, type,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = Ui.dp(this, 90);
            wm.addView(box, lp);
            bubble = box;
        } catch (Throwable e) {
            status("Gemini is listening (the chat bubble couldn't be shown)");
        }
    }

    private void hideBubble() {
        if (bubble == null) return;
        try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(bubble); } catch (Throwable ignored) { }
        bubble = null;
    }

    private void setState(String s) { if (stateV != null) stateV.setText("  · " + s); }

    private void showText() {
        if (youV == null) return;
        android.text.SpannableStringBuilder a = new android.text.SpannableStringBuilder();
        if (you.length() > 0) {
            a.append("You: ", new android.text.style.ForegroundColorSpan(0xFF9FB0D0), 0);
            a.append(you.toString().trim());
        }
        youV.setText(a);
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder();
        if (gem.length() > 0) {
            b.append("Gemini: ", new android.text.style.ForegroundColorSpan(0xFFB388FF), 0);
            b.append(gem.toString().trim());
        }
        if (lastAction.length() > 0) b.append(b.length() > 0 ? "\n" : "").append("✓ " + lastAction, new android.text.style.ForegroundColorSpan(0xFF2EE59D), 0);
        gemV.setText(b);
    }

    private void status(String s) { main.post(() -> android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()); }

    private void beep() {
        try {
            android.media.ToneGenerator t = new android.media.ToneGenerator(AudioManager.STREAM_MUSIC, 60);
            t.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 150);
            main.postDelayed(t::release, 400);
        } catch (Throwable ignored) { }
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }
}
