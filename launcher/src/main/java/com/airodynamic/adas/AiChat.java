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
 * Gemini app needed. An offline wake-word listener (Vosk, nothing leaves the car) waits for "Harley Quinn" (was "Quinn", before that "hey natee chat"); then a
 * bubble opens over the screen and the voice goes to Gemini, which answers by voice and text. It can read the car's
 * information and change comfort things only (fan, temperature, A/C, cabin light, sunshade: see CarControl), never
 * anything about driving (user's OK for comfort controls, 2026-11-21).
 * Stays open and listening until the wheel mic button is pressed again, ✕, or "close Natee chat" (Air 2026-11-22).
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
    private volatile boolean alive, muted, speaking;
    static volatile boolean chatting;
    private Thread wakeThread;
    private org.vosk.Model model;
    private Prefs prefs;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        control = new CarControl(this);
        alive = true;
        new Thread(() -> { if (!micAllowed(this) || !overlayAllowed(this)) fixPermissions(this); }).start();
        main.postDelayed(watchCheck, 6000);   // a moment after start, so Android can connect the watcher by itself first
        // the car waking up (screen on again, or the launcher started): start the wheel button watcher afresh
        try {
            android.content.IntentFilter f = new android.content.IntentFilter(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_USER_PRESENT);
            registerReceiver(wokeUp, f);
        } catch (Throwable ignored) { }
        main.postDelayed(this::showArm, 4000);
        startWake();
    }

    /** Close the chat (double press on the wheel mic button). */
    static void closeNow(Context c) {
        try { c.startService(new Intent(c, AiChat.class).setAction("close")); } catch (Throwable ignored) { }
    }

    /** Open the chat now (wheel mic button, see KeyWatch / VoiceEntry). */
    static void startNow(Context c) {
        try { c.startService(new Intent(c, AiChat.class).setAction("chat")); } catch (Throwable ignored) { }
    }

    @Override public int onStartCommand(Intent i, int f, int id) {
        if (i != null && "chat".equals(i.getAction())) { main.post(this::hideArm); main.post(this::pressMic); }
        if (i != null && "close".equals(i.getAction())) main.post(this::endChat);
        return START_STICKY;
    }

    // ---------------- walkie-talkie (Air 2026-11-22): press = talk, press again = finished → answer at once ----------------
    private volatile boolean talking, activityOpen, heard, manual, autoEnd, pendingTalk;
    private volatile long lastVoiceAt;
    private static final int C_LISTEN = 0xFF3AA0FF, C_HEAR = 0xFF2EE59D, C_ANSWER = 0xFF7C4DFF, C_REPEAT = 0xFFFF8A1F;
    private final Runnable hideLater = () -> closeWhenQuiet();
    private volatile long lastSoundAt;   // the last moment the microphone heard a voice (talking turn or not)
    // (Air 2026-11-23: "I can't press to stop and it keeps waiting because of the noise while driving") road noise is
    // as loud as a voice, so a turn started by the wake word ends on WORDS: Gemini writes down what it hears, noise
    // gives no words. 1.8 s without new words = finished; no words at all within 6 s = only noise; 20 s at most.
    private volatile long lastWordsAt, openedAt;

    /** Never close while someone is still talking (Air 2026-11-22: "I'm still talking and it closes"). */
    private void closeWhenQuiet() {
        if (System.currentTimeMillis() - lastWordsAt < 3000) { main.postDelayed(this::closeWhenQuiet, 1000); return; }
        endChat();
    }
    private boolean silentStart;   // listening again after an answer: no beep

    private void pressMic() {
        wakeTurn = false;
        if (!chatting) { byPress = true; startChat(); return; }   // opens, and starts listening as soon as Gemini is connected
        if (talking) { pressedFinish = true; finishTalk(); pressedFinish = false; } else { byPress = true; startTalk(); }
    }
    /** this turn was started with the mic button: everything is sent, no voice check (Air 2026-11-22: it closed while talking) */
    private volatile boolean byPress;
    private volatile boolean wordsThisTurn, dropReply, pressedFinish;
    private volatile String lastAnswer = "";

    /** Gemini's own voice coming back through the microphone (Air 2026-11-22: "you: ok ค่ะ" five times): a lone
     *  short filler word, or words Gemini itself just said. Only for turns that ended by themselves, never a press. */
    private static boolean closesSystem(String t) {
        String e = t.replaceAll("[\\s.,!?…]+$", "");
        return e.matches("(?s).*ปิด\\s*ระบบ\\s*(นะ)?\\s*(ครับ|คับ|คะ|ค่ะ|ค๊ะ)?") || e.matches("(?s).*\\bclose\\s+(the\\s+)?system(\\s+please)?");
    }

    private static boolean endsSentence(String t) {
        String e = t.replaceAll("[\\s.,!?…]+$", "");
        return e.endsWith("ครับ") || e.endsWith("คับ") || e.endsWith("คะ") || e.endsWith("ค่ะ") || e.endsWith("ค๊ะ") || e.endsWith("please");
    }

    private boolean isEcho() {
        String y = you.toString().trim().toLowerCase(java.util.Locale.US).replaceAll("[\\s.,!?]+", " ").trim();
        if (y.isEmpty()) return true;
        String bare = y.replace(" ", "");
        String[] fillers = {"ok", "okay", "ค่ะ", "คะ", "ครับ", "นะคะ", "okค่ะ", "okayค่ะ", "โอเค", "โอเคค่ะ", "ใช่", "อืม", "yes", "yeah", "mm"};
        for (String f : fillers) if (bare.equals(f)) return true;
        String g = (lastAnswer + gem).toLowerCase(java.util.Locale.US).replace(" ", "");
        return bare.length() >= 2 && g.contains(bare);
    }

    private void startTalk() {
        main.removeCallbacks(hideLater);
        main.removeCallbacks(noAnswer);
        if (!ready) { pendingTalk = true; return; }
        playQ.clear();                     // talking over Gemini stops its answer
        heard = false; activityOpen = false; lastVoiceAt = 0;
        talking = true;
        look(C_LISTEN, "listening… speak now");
        if (!silentStart) beep();
        silentStart = false;
        // (Air 2026-11-25: stuck on "listening… speak now" for ever) every turn has a way out: no words for a while = it closes
        main.postDelayed(noAnswer, byPress ? 30_000 : 12_000);
    }
    /** this turn was started by the wake word: everything heard is sent (road noise can hide a voice from the level
     *  check), and it ends on words, see the mic thread */
    private volatile boolean wakeTurn;

    /** Finished talking: Gemini answers straight away. Nothing heard: say so, nothing is sent. */
    private void finishTalk() {
        if (!talking) return;
        talking = false;
        autoEnd = false;
        if (activityOpen) {
            activityOpen = false;
            WebSocket s = ws;
            if (s != null && manual) s.send("{\"realtimeInput\":{\"activityEnd\":{}}}");
            if ((!wordsThisTurn || isEcho()) && !pressedFinish) {
                // only noise (no words came back): whatever Gemini says to it is dropped, and it keeps waiting quietly
                dropReply = true;
                talking = true; heard = false;
                look(C_LISTEN, "listening… (closes in 10 s if you say nothing)");
                main.removeCallbacks(noAnswer);
                main.postDelayed(noAnswer, 10_000);
                return;
            }
            look(C_ANSWER, "answering…");
        } else {
            look(C_REPEAT, "didn't hear you: say it again (or press the mic)");
            talking = true;                         // keep listening
            main.removeCallbacks(noAnswer);
            main.postDelayed(noAnswer, 10_000);
        }
    }


    /** After an answer (Air 2026-11-22): it listens again by itself, so a conversation needs no presses to start;
     *  press the mic when you've finished, or a 2 s pause finishes it. Nothing said within 10 s: it closes. */
    private void answerDone() {
        if (talking || !chatting) return;
        main.postDelayed(this::listenAgain, 1500);   // after Gemini's voice has really left the speakers (it heard itself at 0.8 s)
    }

    private void listenAgain() {
        if (talking || !chatting || speaking || !playQ.isEmpty()) return;
        // (Air 2026-11-22: "ค่ะ ค่ะ ค่ะ" — its own voice came back through the microphone) after a plain answer it only
        // listens again when Settings says so; after a question it does (to hear the answer)
        if (!askedQuestion()) {   // the old "keep listening after every answer" is gone for good (Air 2026-11-22)
            look(C_ANSWER, "done · press the mic to talk again · closes in 10 s");
            main.removeCallbacks(hideLater);
            main.postDelayed(hideLater, 10_000);
            return;
        }
        silentStart = true;
        byPress = false;
        startTalk();
        look(C_LISTEN, askedQuestion() ? "listening for your answer…" : "listening… (closes in 10 s if you say nothing)");
        main.removeCallbacks(noAnswer);
        main.postDelayed(noAnswer, askedQuestion() ? 20_000 : 10_000);
    }

    // closes when no words came for a while, even if road noise keeps the microphone busy
    private final Runnable noAnswer = () -> { if (talking && (!activityOpen || System.currentTimeMillis() - lastWordsAt > 8000)) closeWhenQuiet(); };

    /** Gemini asked something ("Open the sunroof?"): it waits 20 s for the answer instead of 10. */
    private boolean askedQuestion() {
        String g = gem.toString().trim().toLowerCase(java.util.Locale.US);
        return g.endsWith("?") || g.endsWith("ไหม") || g.endsWith("มั้ย") || g.endsWith("ไหมคะ") || g.endsWith("มั้ยคะ")
                || g.endsWith("หรือเปล่าคะ") || g.endsWith("หรือไม่คะ") || g.endsWith("ใช่ไหมคะ") || g.endsWith("คะ?");
    }

    /** The wheel button watcher: switched on again whenever an update turned it off (checked every minute). */
    private final android.content.BroadcastReceiver wokeUp = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { main.postDelayed(AiChat.this::showArm, 1500); }
    };
    // (Air 2026-11-23) after a car start the wheel mic goes to BYD again: a small button to tap once, "like Snow
    // Assistant, which has to be opened first"; it switches the mic button to the chat and disappears
    private View arm;
    private void showArm() {
        if (arm != null || !alive || !prefs.aiChat()) return;
        boolean after = KeyWatch.store(this).getBoolean("restartedForWatcher", false);
        android.widget.TextView t = new android.widget.TextView(this);
        t.setText("\uD83C\uDFA4  Tap: wheel mic \u2192 " + prefs.aiName());
        t.setTextSize(16); t.setTextColor(0xFFFFFFFF); t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(Ui.dp(this, 18), Ui.dp(this, 10), Ui.dp(this, 18), Ui.dp(this, 10));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xE61E88E5); bg.setCornerRadius(Ui.dp(this, 22));
        t.setBackground(bg);
        t.setOnClickListener(v -> {
            t.setText("\uD83C\uDFA4  switching\u2026");
            new Thread(() -> {
                final boolean ok = KeyWatch.restart(AiChat.this);
                KeyWatch.keepOff(AiChat.this);
                main.post(() -> {
                    boolean re = KeyWatch.store(AiChat.this).getString("lastFix", "").contains("reinstalling");
                    t.setText(ok ? "\uD83C\uDFA4  wheel mic \u2192 " + prefs.aiName() + " \u2713"
                            : re ? "\uD83C\uDFA4  re-installing the launcher \u00b7 back in about a minute" : "\uD83C\uDFA4  couldn't switch \u00b7 tap again");
                    if (ok) main.postDelayed(AiChat.this::hideArm, 1500);
                });
            }).start();
        });
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            @SuppressWarnings("deprecation") int type = WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL; lp.y = Ui.dp(this, 70);
            wm.addView(t, lp);
            arm = t;
            if (after) {   // back after the blue button reinstalled the launcher: say whether it worked
                KeyWatch.store(this).edit().putBoolean("restartedForWatcher", false).apply();
                t.setText("\uD83C\uDFA4  checking the wheel mic\u2026");
                main.postDelayed(() -> {
                    if (arm != t) return;
                    boolean ok = KeyWatch.running();
                    t.setText(ok ? "\uD83C\uDFA4  wheel mic \u2192 " + prefs.aiName() + " \u2713" : "\uD83C\uDFA4  still not working \u00b7 send Claude the test photo");
                    main.postDelayed(this::hideArm, ok ? 4000 : 15000);
                }, 6000);
            }
        } catch (Throwable ignored) { }
    }
    private void hideArm() {
        if (arm == null) return;
        try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(arm); } catch (Throwable ignored) { }
        arm = null;
    }

    private final Runnable watchCheck = new Runnable() {
        @Override public void run() {
            if (!alive) return;
            // not listed, or listed but not running (after a car start): repair it; checked every 20 s
            final boolean fix = !KeyWatch.isOn(AiChat.this);   // only when it is missing from Android's list; never a restart
            new Thread(() -> { if (fix) KeyWatch.ensureOn(AiChat.this); KeyWatch.keepOff(AiChat.this); }).start();
            main.postDelayed(this, 20_000);
        }
    };

    @Override public void onDestroy() {
        alive = false;
        try { unregisterReceiver(wokeUp); } catch (Throwable ignored) { }
        hideArm();
        wakeState = "off"; micLevel = -1;
        endChat();
        if (wakeThread != null) wakeThread.interrupt();
        super.onDestroy();
    }

    // ---------------- wake word ----------------
    private void startWake() {
        wakeState = "loading the wake word engine (first time ~30 s)";
        wakeThread = new Thread(() -> {
            try {
                if (model == null) {
                    org.vosk.LibVosk.setLogLevel(org.vosk.LogLevel.WARNINGS);
                    model = new org.vosk.Model(org.vosk.android.StorageService.sync(this, "vosk-model", "model"));
                }
            } catch (Throwable e) { wakeState = "engine couldn't start: " + e.getClass().getSimpleName() + " " + e.getMessage(); status("Harley Quinn: wake word engine couldn't start (" + e.getClass().getSimpleName() + ")"); return; }
            wakeState = "ready";
            // only these phrases are listened for ("natee" isn't an English word: the nearest sounds stand in for it)
            // wake word = the assistant's two-word name from Settings (Air 2026-11-23, default "Harley Quinn"; "queen" for "Quinn")
            final String w1 = prefs.aiName1(), w2 = prefs.aiName2();
            String alt = w2.equals("quinn") ? ", \"" + w1 + " queen\"" : "";
            String grammar = "[\"" + w1 + " " + w2 + "\", \"hey " + w1 + " " + w2 + "\", \"hi " + w1 + " " + w2 + "\"" + alt + ", \"[unk]\"]";
            wakeWords = new String[]{w1, w2};
            wakeGrammar = grammar;
            while (alive) {
                if (chatting) { sleep(300); continue; }
                Mic mic = null;
                try (org.vosk.Recognizer r = new org.vosk.Recognizer(model, IN_RATE, grammar)) {
                    r.setWords(true);   // per-word confidence, see isWake
                    if (!micAllowed(this)) { wakeState = "no microphone permission: Settings → AI chat → Fix permissions"; sleep(10_000); continue; }
                    mic = Mic.open(false);
                    if (mic == null) { wakeState = "microphone busy or refused (" + Mic.lastError + ")"; sleep(10_000); continue; }   // try again later
                    wakeState = "listening · " + mic.desc;
                    short[] b = new short[1600];   // 0.1 s at 16 kHz
                    while (alive && !chatting) {
                        int n = mic.read16k(b);
                        if (n <= 0) { sleep(50); continue; }
                        long sum = 0; for (int k = 0; k < n; k++) sum += Math.abs(b[k]);
                        micLevel = (int) Math.min(100, sum / n * 100 / 3000);   // 0 = silence; talking normally ≈ 20–80
                        boolean fin = r.acceptWaveForm(b, n);
                        String txt = fin ? r.getResult() : r.getPartialResult();
                        if (fin) { String h = heard(txt); if (!h.isEmpty()) lastHeard = h; }
                        if (fin && isWake(txt)) { r.reset(); main.post(() -> { autoEnd = true; byPress = false; wakeTurn = true; if (chatting) startTalk(); else startChat(); }); break; }
                    }
                } catch (Throwable e) {
                    wakeState = "error: " + e.getClass().getSimpleName();
                    sleep(5000);
                } finally {
                    if (mic != null) mic.close();
                }
            }
        }, "hey-natee");
        wakeThread.start();
    }

    /** For Settings → AI chat → Wake word check (2026-11-22: "Hey Natee chat, nothing happens"). */
    static volatile String wakeState = "not started (AI chat off?)", lastHeard = "";
    private volatile String wakeGrammar;
    static volatile int micLevel = -1;

    static boolean micAllowed(Context c) {
        return c.checkCallingOrSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                && appOpAllowed(c, "android:record_audio");
    }

    static boolean overlayAllowed(Context c) {
        try { return android.provider.Settings.canDrawOverlays(c); } catch (Throwable e) { return true; }
    }

    private static boolean appOpAllowed(Context c, String op) {
        try {
            android.app.AppOpsManager ao = (android.app.AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            return ao.checkOpNoThrow(op, android.os.Process.myUid(), c.getPackageName()) == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Throwable e) { return true; }
    }

    /** Gives NATEE the microphone and the floating windows through the car's own ADB. Returns what happened. */
    static String fixPermissions(Context c) {
        String p = c.getPackageName();
        try {
            Updater.adbShell(c, "pm grant " + p + " android.permission.RECORD_AUDIO; appops set " + p + " RECORD_AUDIO allow; appops set " + p + " SYSTEM_ALERT_WINDOW allow; pm grant " + p + " android.permission.WRITE_SECURE_SETTINGS");
        } catch (Exception e) { return "adb: " + e.getClass().getSimpleName(); }
        return "";
    }

    private static String heard(String json) {
        try { return new JSONObject(json).optString("text", "").trim(); } catch (Exception e) { return ""; }
    }

    private static volatile String[] wakeWords = {"harley", "quinn"};

    /** (Air 2026-11-25: "it opens when I didn't say it") only a FINISHED phrase counts (not a half-heard one), and the
     *  listener must be sure of both words: with only a few phrases to choose from, it used to squeeze any noise or
     *  talk into the name. WAKE_CONF is how sure (0..1). */
    private static final double WAKE_CONF = 0.85;
    private static boolean isWake(String json) {
        if (json == null) return false;
        try {
            org.json.JSONArray w = new org.json.JSONObject(json).optJSONArray("result");
            if (w != null) {
                String a = wakeWords[0], b = wakeWords[1];
                for (int i = 0; i + 1 < w.length(); i++) {
                    org.json.JSONObject x = w.getJSONObject(i), y = w.getJSONObject(i + 1);
                    String wx = x.optString("word"), wy = y.optString("word");
                    boolean names = wx.equals(a) && (wy.equals(b) || (b.equals("quinn") && wy.equals("queen")));
                    if (names && x.optDouble("conf", 0) >= WAKE_CONF && y.optDouble("conf", 0) >= WAKE_CONF) return true;
                }
                return false;
            }
        } catch (Throwable ignored) { }
        String t = " " + json.toLowerCase(java.util.Locale.US).replaceAll("[^a-z]+", " ") + " ";
        String a = wakeWords[0], b = wakeWords[1];
        // both words, one right after the other
        return t.contains(" " + a + " " + b + " ") || (b.equals("quinn") && t.contains(" " + a + " queen "));
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
        if (key.isEmpty()) { status("Natee chat: add your Gemini API key in Settings → AI chat"); return; }
        chatting = true; muted = false; ready = false; retries = 0;
        talking = false; activityOpen = false; pendingTalk = true;
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
    }

    private final Runnable quietCheck = new Runnable() {
        @Override public void run() {
            if (!chatting) return;
            // (Air 2026-11-22) no closing on quiet: it keeps listening until closed by the driver
            main.postDelayed(this, 1000);
        }
    };

    private void connect() {
        String url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=" + prefs.aiKey();
        final int idx = modelIdx;
        ws = http.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            // each model is tried first with the quick end-of-speech setting, then without it (in case Google refuses it)
            // each model is tried first in walkie-talkie mode (the app says when you start and stop), then without it
            @Override public void onOpen(WebSocket s, Response r) { manual = idx % 2 == 0; s.send(setupJson(MODELS[idx / 2], manual)); }
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
            if (!ready && idx == modelIdx && modelIdx + 1 < MODELS.length * 2 && code != 1006 && code != -1) {   // this model refused: try the next
                modelIdx++;
                connect();
                return;
            }
            // Gemini ends a voice session after some minutes, or the network drops: connect again and keep listening
            if (idx == modelIdx && retries < 3) {
                retries++;
                ready = false;
                setState("reconnecting…");
                main.postDelayed(() -> { if (chatting) connect(); }, 1500);
                return;
            }
            setState("couldn't reach Gemini (" + code + (reason == null || reason.isEmpty() ? "" : " " + reason) + ")");
            main.postDelayed(this::endChat, 6000);
        });
    }

    /** Google Search for current things: weather, news, places, prices (Air 2026-11-22: "she should answer everything").
     *  Only on the first try of each model; if Google refuses it, the next try works without it. */
    private static JSONArray withSearch(JSONArray tools) throws Exception {
        return tools.put(new JSONObject().put("googleSearch", new JSONObject()));
    }

    /** Adds "close_chat": when the driver asks to close / stop / end the chat in any words, Gemini closes it. */
    private static JSONArray withClose(JSONArray tools) throws Exception {
        tools.getJSONObject(0).getJSONArray("functionDeclarations").put(new JSONObject().put("name", "close_chat")
                .put("description", "Close this chat when the driver asks to close, stop or end it (e.g. 'close Natee chat', 'ปิดแชท'). Say a short goodbye first."));
        return tools;
    }

    private String setupJson(String model, boolean quickEnd) {
        try {
            String lang = prefs.aiLang();
            String li = "en".equals(lang) ? "Always answer in English." : "th".equals(lang) ? "Always answer in Thai." :
                    "Answer in the language the driver speaks (Thai or English).";
            JSONObject voice = new JSONObject().put("prebuiltVoiceConfig", new JSONObject().put("voiceName", prefs.aiVoice()));
            JSONObject gen = new JSONObject().put("responseModalities", new JSONArray().put("AUDIO"))
                    .put("speechConfig", new JSONObject().put("voiceConfig", voice));
            JSONObject sys = new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text",
                    "You are " + prefs.aiName() + " (the driver calls you by saying \"" + prefs.wakeWord() + "\"), a friendly voice assistant in the driver's BYD Atto 3 electric car in Thailand. The driver is"
                            + " driving: keep answers short and easy to listen to (one to three sentences), no lists or links."
                            + " You cannot see the road. You can read the car's information with get_car_info (use it whenever the"
                            + " driver asks about the car: range, battery, tyres, air, temperatures, trip, doors) and change comfort"
                            + " things only: fan, temperature, A/C on/off, A/C auto, front defrost, air direction, inside/outside air, air purifier, sunshade, sunroof. You can also open apps (open_app), start navigation in Google Maps (navigate, e.g. home) or in Ghost Race when the driver says so (navigate_ghost_race), open YouTube videos (youtube_video), control"
                            + " the music (music), and save notes for the developer (report_problem) when the driver"
                            + " reports something not working or has an idea for a new function. Never anything about driving."
                            + " Understand casual requests (\"too hot\" = temperature down 1-2 degrees or fan up). After a change,"
                            + " say in a few words what you did (\"Fan 4.\"); if the result says it failed, say so plainly."
                            + " For anything current (weather, news, traffic, opening hours, prices, places, facts you aren't sure of) use Google"
                            + " Search; for \"here\" or \"near me\" use the car's location from get_car_info (lat, lng). Answer everything you can."
                            + " For EVERY change in the car (fan, temperature, A/C, sunroof, sunshade), apps, music or navigation you MUST call the"
                            + " tool again, every single time, even if you did the same thing a minute ago: never answer from memory."
                            + " Several changes in one sentence = several tool calls."
                            + " Never say something is done, playing or open before the tool's result comes back; then say only what the"
                            + " result says (if it says it didn't work, say that)."
                            + " Never refuse music, apps or videos yourself because the car might be moving: always call the tool, it knows the"
                            + " car's state and the driver's settings, and tells you if something can't open."
                            + " If what you hear is only noise, music, silence or no clear words, do not answer at all: stay silent."
                            + " Don't ask \"anything else?\" after every answer."
                            + " Before opening or closing the sunshade or the sunroof, ask the driver to confirm, and only call it after a yes. The sunshade is the cloth under the glass; the sunroof is the glass itself: never mix them up. " + li)));
            JSONObject setup = new JSONObject().put("model", model).put("generationConfig", gen).put("systemInstruction", sys)
                    .put("inputAudioTranscription", new JSONObject()).put("outputAudioTranscription", new JSONObject())
                    .put("tools", quickEnd ? withSearch(withClose(CarControl.tools())) : withClose(CarControl.tools()));
            // walkie-talkie (Air 2026-11-22): Google doesn't guess when you've finished; the app tells it (second mic press)
            if (quickEnd) setup.put("realtimeInputConfig", new JSONObject().put("automaticActivityDetection", new JSONObject().put("disabled", true)));
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
                    if (t.trim().length() > 0 && !t.trim().startsWith("<")) {
                        wordsThisTurn = true; lastWordsAt = System.currentTimeMillis();
                        // the words came in after the turn was taken for noise: it was you after all, let the answer through
                        if (dropReply && !activityOpen && talking && !isEcho()) { dropReply = false; talking = false; main.removeCallbacks(noAnswer); look(C_ANSWER, "answering…"); }
                    }   // "<noise>" isn't words
                    if (talking) look(C_HEAR, (byPress ? "I hear you · press the mic when you've finished" : "I hear you · pause when you've finished"));
                    String all = you.toString().toLowerCase(java.util.Locale.US);
                    // "close <any spelling of Natee> chat" / "ปิด…แชท" (the name is written differently each time)
                    boolean close = all.matches("(?s).*\\bclose\\b.{0,20}\\bchat\\b.*") || all.matches("(?s).*ปิด.{0,20}แชท.*")
                            || all.contains("bye natee") || all.contains("ปิดนที") || all.contains("ปิด นที");
                    if (close) main.postDelayed(this::endChat, 1500);
                    // (Air 2026-11-24) "ปิดระบบ" / "close the system" closes it too, but only when it ends what was said
                    // ("ปิดระบบแอร์" is the A/C, not the chat): checked again a moment later
                    if (closesSystem(all)) main.postDelayed(() -> { if (closesSystem(you.toString().toLowerCase(java.util.Locale.US))) endChat(); }, 1200);
                    // (Air 2026-11-24) a sentence ending in ครับ / คับ / คะ / ค่ะ, or "please", is finished: answer at once
                    // (a short moment first, in case more words follow)
                    if (talking && activityOpen && endsSentence(all)) {
                        final long at = lastWordsAt;
                        main.postDelayed(() -> { if (talking && activityOpen && lastWordsAt == at && endsSentence(you.toString().toLowerCase(java.util.Locale.US))) finishTalk(); }, 700);
                    }
                });
            }
            JSONObject ot = sc.optJSONObject("outputTranscription");
            if (ot != null && ot.optString("text").length() > 0) {
                final String t = ot.optString("text");
                if (!dropReply) main.post(() -> { if (newTurn) { you.setLength(0); gem.setLength(0); newTurn = false; } gem.append(t); showText(); });
            }
            JSONObject mt = sc.optJSONObject("modelTurn");
            if (mt != null) {
                JSONArray parts = mt.optJSONArray("parts");
                for (int i = 0; parts != null && i < parts.length(); i++) {
                    JSONObject d = parts.getJSONObject(i).optJSONObject("inlineData");
                    if (d != null && d.optString("mimeType").startsWith("audio") && !dropReply) playQ.offer(Base64.decode(d.getString("data"), Base64.DEFAULT));
                }
            }
            if (sc.optBoolean("interrupted")) playQ.clear();
            if (sc.optBoolean("turnComplete") && dropReply) { dropReply = false; return; }
            if (sc.optBoolean("turnComplete")) main.post(() -> { lastAnswer = gem.toString(); newTurn = true; lastActive = System.currentTimeMillis(); if (playQ.isEmpty() && !speaking) answerDone(); });
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
                control.chatSoFar = (you.length() > 0 ? "you: " + you.toString().trim() : "") + (lastAction.length() > 0 ? " · last: " + lastAction : "");
                JSONObject r;
                if ("close_chat".equals(name)) { r = new JSONObject().put("ok", true).put("result", "closing"); main.postDelayed(this::endChat, 2500); }
                else r = control.run(name, args == null ? new JSONObject() : args);
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

    private int retries;

    private void onReady() {
        if (!chatting) return;
        ready = true;
        retries = 0;
        if (micThread != null && micThread.isAlive()) { setState("connected"); if (pendingTalk) { pendingTalk = false; startTalk(); } return; }   // reconnected
        setState("connected");
        lastActive = System.currentTimeMillis();
        int ob = Math.max(AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), OUT_RATE);
        track = new AudioTrack(AudioManager.STREAM_MUSIC, OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, ob, AudioTrack.MODE_STREAM);
        track.play();
        playThread = new Thread(() -> {
            while (chatting) {
                try {
                    byte[] a = playQ.poll(200, TimeUnit.MILLISECONDS);
                    if (a == null) { if (speaking) { speaking = false; lastActive = System.currentTimeMillis(); main.post(this::answerDone); } continue; }
                    if (!speaking) { speaking = true; main.post(() -> { main.removeCallbacks(hideLater); if (!talking) look(C_ANSWER, "answering…"); }); }
                    AudioTrack t = track;
                    if (t != null) t.write(a, 0, a.length);
                } catch (InterruptedException e) { return; } catch (Throwable ignored) { }
            }
        }, "natee-play");
        playThread.start();
        micThread = new Thread(() -> {
            Mic mic = null;
            try {
                mic = Mic.open(true);
                if (mic == null) { main.post(() -> setState("microphone busy (" + Mic.lastError + ")")); return; }
                try { if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) android.media.audiofx.AcousticEchoCanceler.create(mic.rec.getAudioSessionId()).setEnabled(true); } catch (Throwable ignored) { }
                short[] b = new short[1600];   // 0.1 s at 16 kHz
                java.util.ArrayDeque<String> pre = new java.util.ArrayDeque<>();   // the 0.3 s before your voice starts
                double floor = 4;                                                    // the car's own noise (fan, road)
                // (Air 2026-11-24) the chat holds the microphone, so the wake word is listened for here too: after an
                // answer, saying the name starts listening again without pressing the mic
                org.vosk.Recognizer wakeR = null;
                try { if (model != null && wakeGrammar != null) { wakeR = new org.vosk.Recognizer(model, IN_RATE, wakeGrammar); wakeR.setWords(true); } } catch (Throwable ignored) { }
                int voiced = 0;
                while (chatting) {
                    int n = mic.read16k(b);
                    if (n <= 0) continue;
                    long sum = 0; for (int k = 0; k < n; k++) sum += Math.abs(b[k]);
                    int lv = (int) Math.min(100, sum / n * 100 / 3000);
                    micLevel = lv;
                    if (!activityOpen) floor = floor * 0.97 + Math.min(lv, floor + 8) * 0.03;
                    boolean loud = lv > Math.max(8, floor * 2 + 6);
                    voiced = loud ? voiced + 1 : 0;
                    boolean voice = voiced >= 3;   // 0.3 s of clear voice, not a bump of road noise
                    byte[] out = new byte[n * 2];
                    for (int k = 0; k < n; k++) { out[2 * k] = (byte) b[k]; out[2 * k + 1] = (byte) (b[k] >> 8); }   // 16-bit little-endian
                    String msg = "{\"realtimeInput\":{\"audio\":{\"mimeType\":\"audio/pcm;rate=16000\",\"data\":\""
                            + Base64.encodeToString(out, Base64.NO_WRAP) + "\"}}}";
                    WebSocket s = ws;
                    boolean someVoice = lv > Math.max(5, floor * 1.6 + 4);   // loose: for "you've stopped talking"
                    if (someVoice && !speaking) lastSoundAt = System.currentTimeMillis();   // not Gemini's own voice
                    if (wakeR != null && !activityOpen && !speaking && playQ.isEmpty()) {   // also while waiting for a voice
                        try {
                            boolean fin = wakeR.acceptWaveForm(b, n);
                            if (fin && isWake(wakeR.getResult())) {
                                wakeR.reset();
                                main.post(() -> { if (chatting && !activityOpen) { autoEnd = true; byPress = false; wakeTurn = true; startTalk(); look(C_HEAR, "I hear you · pause when you've finished"); } });
                            }
                        } catch (Throwable ignored) { }
                    }
                    if (!talking || muted || s == null) { pre.clear(); continue; }
                    if (!activityOpen) {
                        pre.addLast(msg);
                        if (pre.size() > 3) pre.removeFirst();
                        if (!voice && !byPress && !wakeTurn) continue;
                        wakeTurn = false;   // pressed: you said you're talking, everything is sent
                        wordsThisTurn = false;
                        // your voice: tell Gemini you started, with the moment just before
                        activityOpen = true; heard = true; openedAt = System.currentTimeMillis();
                        if (manual) s.send("{\"realtimeInput\":{\"activityStart\":{}}}");
                        while (!pre.isEmpty()) s.send(pre.removeFirst());
                        lastVoiceAt = voice || someVoice ? System.currentTimeMillis() : 0;
                        if (voice || someVoice) main.post(() -> { if (talking) look(C_HEAR, (byPress ? "I hear you · press the mic when you've finished" : "I hear you · pause when you've finished")); });
                        continue;
                    }
                    s.send(msg);
                    if (!byPress) {   // wake word / listening again: no press will come, so words decide
                        long now = System.currentTimeMillis();
                        if (wordsThisTurn ? now - lastWordsAt > 1800 : now - openedAt > 6000) { main.post(this::finishTalk); continue; }
                        if (now - openedAt > 20_000) { main.post(this::finishTalk); continue; }
                    }
                    if (byPress ? someVoice : loud) {
                        if (lastVoiceAt == 0) main.post(() -> { if (talking) look(C_HEAR, (byPress ? "I hear you · press the mic when you've finished" : "I hear you · pause when you've finished")); });
                        lastVoiceAt = System.currentTimeMillis();
                    }
                    // 2 s quiet after your voice = finished (or press the mic); never before any voice was heard
                    else if (lastVoiceAt > 0 && System.currentTimeMillis() - lastVoiceAt > 2000) main.post(this::finishTalk);
                }
                if (wakeR != null) try { wakeR.close(); } catch (Throwable ignored) { }
            } catch (Throwable ignored) {
            } finally { if (mic != null) mic.close(); }
        }, "natee-mic");
        micThread.start();
        if (pendingTalk) { pendingTalk = false; startTalk(); }
    }

    private void endChat() {
        if (!chatting) return;
        chatting = false; ready = false; speaking = false; talking = false; activityOpen = false; pendingTalk = false; autoEnd = false;
        main.removeCallbacks(hideLater);
        if (ws != null) { try { ws.close(1000, "bye"); } catch (Throwable ignored) { } ws = null; }
        playQ.clear();
        if (track != null) { try { track.stop(); } catch (Throwable ignored) { } track.release(); track = null; }
        micThread = null; playThread = null;
        try { ((AudioManager) getSystemService(AUDIO_SERVICE)).abandonAudioFocus(focus); } catch (Throwable ignored) { }
        hideBubble();
    }

    // ---------------- bubble over the screen ----------------
    private View bubble, levelBar;
    private float shownLevel;

    private final Runnable levelTick = new Runnable() {
        @Override public void run() {
            if (levelBar == null || bubble == null) return;
            float want = talking && !muted ? Math.min(1f, Math.max(0, micLevel) / 60f) : 0f;
            shownLevel += (want - shownLevel) * 0.5f;   // quick but not jumpy
            View parent = (View) levelBar.getParent();
            int w = parent == null ? 0 : (int) (parent.getWidth() * shownLevel);
            android.view.ViewGroup.LayoutParams lp = levelBar.getLayoutParams();
            if (lp.width != w) { lp.width = w; levelBar.setLayoutParams(lp); }
            levelBar.setBackground(Ui.round(activityOpen ? C_HEAR : C_LISTEN, 0, 4, AiChat.this));
            main.postDelayed(this, 80);
        }
    };
    private android.graphics.drawable.GradientDrawable bubbleBg;

    /** Border + state colour: blue listening, green I hear you, purple answering, orange say it again. */
    private void look(int color, String text) {
        if (bubbleBg != null) bubbleBg.setStroke(Ui.dp(this, 4), color);
        if (stateV != null) { stateV.setText("  · " + text); stateV.setTextColor(color); }
    }
    private TextView stateV, youV, gemV;
    private android.widget.ScrollView talkScroll;

    private void showBubble() {
        if (bubble != null) return;
        Theme th = prefs.theme();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable g = Ui.round(0xEE101F3D, 0xFF7C4DFF, 22, this);
        g.setStroke(Ui.dp(this, 2), 0xFF7C4DFF);
        box.setBackground(g);
        bubbleBg = g;
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
        mute.setOnClickListener(v -> { muted = !muted; mute.setText(muted ? "🎤 Unmute" : "🔇 Mute"); setState(muted ? "muted" : "ready"); });
        head.addView(mute, new LinearLayout.LayoutParams(-2, Ui.dp(this, 40)));
        android.widget.Button close = Ui.chip(this, "✕", false, th, v -> endChat());
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 40));
        cl.leftMargin = Ui.dp(this, 6);
        head.addView(close, cl);
        box.addView(head);
        // live sound bar (Air 2026-11-22): moves with your voice the moment the microphone hears it
        android.widget.FrameLayout barBox = new android.widget.FrameLayout(this);
        barBox.setBackground(Ui.round(0x33FFFFFF, 0, 4, this));
        levelBar = new View(this);
        levelBar.setBackground(Ui.round(C_HEAR, 0, 4, this));
        barBox.addView(levelBar, new android.widget.FrameLayout.LayoutParams(0, -1));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, Ui.dp(this, 8));
        bl.topMargin = Ui.dp(this, 8);
        box.addView(barBox, bl);
        main.post(levelTick);
        // the chat text fills the box and scrolls (the box is as big as the Engine Sound window)
        LinearLayout talk = new LinearLayout(this);
        talk.setOrientation(LinearLayout.VERTICAL);
        youV = Ui.text(this, "", 18, 0xFFFFFFFF, false);
        youV.setPadding(0, Ui.dp(this, 10), 0, 0);
        talk.addView(youV);
        gemV = Ui.text(this, "Say something…", 18, 0xFFFFFFFF, false);
        gemV.setPadding(0, Ui.dp(this, 8), 0, 0);
        talk.addView(gemV);
        talkScroll = new android.widget.ScrollView(this);
        talkScroll.addView(talk);
        box.addView(talkScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView hint = Ui.text(this, "press the wheel mic to talk, again when you've finished (or pause 2 s) · double press or ✕ closes", 12, 0xFF9FB0D0, false);
        hint.setPadding(0, Ui.dp(this, 8), 0, 0);
        box.addView(hint);
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            @SuppressWarnings("deprecation") int type = WindowManager.LayoutParams.TYPE_PHONE;
            // same size and place as the launcher's Engine Sound window (Air 2026-11-22); before it's known: centred
            android.content.SharedPreferences r = getSharedPreferences("chat_box", MODE_PRIVATE);
            int w = r.getInt("w", Ui.dp(this, 520)), h = r.getInt("h", Ui.dp(this, 360));
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h, type,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
            if (r.contains("x")) { lp.gravity = Gravity.TOP | Gravity.START; lp.x = r.getInt("x", 0); lp.y = r.getInt("y", 0); }
            else { lp.gravity = Gravity.CENTER; }
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
        levelBar = null;
        main.removeCallbacks(levelTick);
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
        if (talkScroll != null) talkScroll.post(() -> talkScroll.fullScroll(View.FOCUS_DOWN));
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
