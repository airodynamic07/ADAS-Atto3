package com.airodynamic.adas;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.Locale;

/**
 * The steering wheel's mic button opens "Hey Natee chat" (user, 2026-11-22). Watches key presses only (nothing on
 * screen): the usual voice keys, or the key chosen in Settings → AI chat → Wheel mic button test. Every key seen is
 * kept for that test (last 12), so a press of the wheel button shows what this car sends.
 */
public final class KeyWatch extends AccessibilityService {
    static SharedPreferences store(Context c) { return c.getSharedPreferences("key_watch", Context.MODE_PRIVATE); }

    private long lastPress, pressedAt;
    private boolean singlePending;
    private long openGuard;
    private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable single = () -> { singlePending = false; AiChat.startNow(this); };

    @Override protected boolean onKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        live = this; connected = true;   // a key arrived: it is running
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            SharedPreferences s = store(this);
            String seen = s.getString("seen", "");
            String item = code + " " + KeyEvent.keyCodeToString(code).replace("KEYCODE_", "") + " · " +
                    new java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new java.util.Date());
            seen = item + (seen.isEmpty() ? "" : "\n" + seen);
            String[] lines = seen.split("\n");
            if (lines.length > 12) seen = android.text.TextUtils.join("\n", java.util.Arrays.copyOf(lines, 12));
            s.edit().putString("seen", seen).apply();
        }
        if (!new Prefs(this).aiChat()) return false;
        boolean wheel = store(this).getInt("chosen", -1) < 0 && (code == 304 || code == 327);
        if (!wheel && !isChatKey(this, code)) return false;
        // (Air 2026-11-22: "press mic, Natee chat doesn't wake up") act on the first key that arrives (304 or 327, the
        // wheel sends both), once per press; walkie-talkie: AiChat decides talk / finished
        // (Air 2026-11-23) one press sends 304 then 327, sometimes well apart: only the press key counts (304, or the
        // chosen key); 327 is swallowed and never counted as a second press (that made every press a "double = close")
        int pressKey = store(this).getInt("chosen", -1) >= 0 ? store(this).getInt("chosen", -1) : 304;
        if (code != pressKey && code != KeyEvent.KEYCODE_VOICE_ASSIST && code != KeyEvent.KEYCODE_ASSIST && code != KeyEvent.KEYCODE_SEARCH) return true;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastPress < 150) return true;
            lastPress = now; pressedAt = now;
            // (Air 2026-11-23) double press = close the chat; a single press waits a moment to see if a second one follows
            // (Air 2026-11-23: "press mic, only BYD's assistant") while the chat is closed a press opens it at once;
            // anything the wheel sends in the next second belongs to that same press, never a "double = close"
            if (!AiChat.chatting) {
                if (now < openGuard) return true;
                openGuard = now + 1000;
                store(this).edit().putString("afterPress", "").apply();
                AiChat.startNow(this);
                return true;
            }
            if (now < openGuard) return true;
            if (singlePending) {
                singlePending = false;
                h.removeCallbacks(single);
                AiChat.closeNow(this);
            } else {
                singlePending = true;
                h.postDelayed(single, 450);
            }
        }
        return true;   // the wheel button now belongs to Natee chat
    }

    /** BYD's own voice assistant still pops up from the wheel button: a voice window opening right after the press is closed. */
    /** The app in front right now (for Quinn: did the app really open?). */
    static volatile String front = "";
    static volatile long frontAt;   // when a window last changed

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pk = event.getPackageName();
        if (pk == null) return;
        front = pk.toString();
        frontAt = android.os.SystemClock.uptimeMillis();
        if (android.os.SystemClock.uptimeMillis() - pressedAt > 3000) return;
        String p = pk.toString(), lp = p.toLowerCase(Locale.US);
        if (p.equals(getPackageName())) return;
        SharedPreferences s = store(this);
        String known = s.getString("bydVoice", "");
        boolean voice = p.equals(known) || lp.contains("voice") || lp.contains("speech") || lp.contains("assistant") || lp.contains(".vr")
                || lp.contains("xiaodi") || lp.contains("aispeech") || lp.contains("iflytek") || lp.contains("txz");
        String ap = s.getString("afterPress", "");   // every app that opened after the press, shown in the wheel button test
        if (!(" " + ap + " ").contains(" " + p + " ")) s.edit().putString("afterPress", (ap + " " + p).trim()).apply();
        if (!voice) return;
        if (!p.equals(known)) s.edit().putString("bydVoice", p).apply();
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    /** Is the wheel button watcher switched on (Android's accessibility list)? */
    static boolean isOn(Context c) {
        String me = c.getPackageName() + "/" + KeyWatch.class.getName();
        String cur = android.provider.Settings.Secure.getString(c.getContentResolver(), android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return cur != null && cur.contains(me);
    }

    /** Is the watcher really running (Android has connected it)? */
    static volatile boolean connected;
    // (Air 2026-11-23: test said "not running" while it was logging keys) after a restart of the watcher the OLD one
    // says goodbye after the new one has connected: only the newest one may say "not running" — otherwise the
    // 20 s check kept restarting a healthy watcher, and BYD's assistant got the button in the gaps
    private static volatile KeyWatch live;
    private static volatile long lastToggle;
    @Override protected void onServiceConnected() { super.onServiceConnected(); live = this; connected = true; }
    @Override public boolean onUnbind(android.content.Intent i) { if (live == this) connected = false; return super.onUnbind(i); }
    @Override public void onDestroy() { if (live == this) connected = false; super.onDestroy(); }

    /**
     * The watcher is switched off by updates and by every car start (Air 2026-11-23: "after I turn the car off and on it
     * always goes back to BYD's assistant"). Switch it on again: by the launcher itself when it has the secure-settings
     * permission (granted once through ADB, survives restarts), else through the car's ADB.
     */
    static void ensureOn(Context c) {
        try {
            String me = c.getPackageName() + "/" + KeyWatch.class.getName();
            android.content.ContentResolver cr = c.getContentResolver();
            String cur = android.provider.Settings.Secure.getString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            boolean listed = cur != null && cur.contains(me);
            if (listed && connected) return;
            long now = android.os.SystemClock.uptimeMillis();
            if (listed && lastToggle != 0 && now - lastToggle < 120_000) return;   // give Android time; never restart it in a loop
            lastToggle = now;
            boolean own = c.checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED;
            String without = cur == null || "null".equals(cur) ? "" : cur.replace(":" + me, "").replace(me + ":", "").replace(me, "");
            String with = without.isEmpty() ? me : without + ":" + me;
            if (own) {
                if (listed) {   // listed but not running: take it out and put it back, so Android starts it again
                    android.provider.Settings.Secure.putString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, without);
                    try { Thread.sleep(700); } catch (InterruptedException ignored) { }
                }
                android.provider.Settings.Secure.putString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, with);
                android.provider.Settings.Secure.putString(cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, "1");
                return;
            }
            Updater.adbShell(c, (listed ? "settings put secure enabled_accessibility_services '" + without + "'; sleep 1; " : "")
                    + "settings put secure enabled_accessibility_services '" + with + "'; settings put secure accessibility_enabled 1; "
                    + "pm grant " + c.getPackageName() + " android.permission.WRITE_SECURE_SETTINGS");
        } catch (Throwable ignored) { }
    }

    /** Voice apps Air switched off in Settings: the car switches them back on at every start (Air 2026-11-23:
     *  "it works until I restart"), so they are switched off again whenever they are found on. */
    static void rememberOff(Context c, String pkg, boolean off) {
        java.util.Set<String> set = new java.util.HashSet<>(store(c).getStringSet("bydOff", new java.util.HashSet<String>()));
        if (off) set.add(pkg); else set.remove(pkg);
        store(c).edit().putStringSet("bydOff", set).apply();
    }

    static void keepOff(Context c) {
        try {
            StringBuilder cmd = new StringBuilder();
            for (String p : store(c).getStringSet("bydOff", new java.util.HashSet<String>())) {
                if (!p.matches("[A-Za-z0-9._]+")) continue;
                int st = c.getPackageManager().getApplicationEnabledSetting(p);
                boolean on = st != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                        && st != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
                if (on) cmd.append("pm disable-user --user 0 ").append(p).append("; ");
            }
            if (cmd.length() > 0) Updater.adbShell(c, cmd.toString());
        } catch (Throwable ignored) { }
    }

    static boolean isChatKey(Context c, int code) {
        int chosen = store(c).getInt("chosen", -1);
        if (chosen >= 0) return code == chosen;
        // 304 = AUTO_MEDIA_VOICE: the Atto 3's steering-wheel mic button (seen in the button test, 2026-11-22)
        return code == 304 || code == KeyEvent.KEYCODE_VOICE_ASSIST || code == KeyEvent.KEYCODE_ASSIST || code == KeyEvent.KEYCODE_SEARCH;
    }

    @Override public void onInterrupt() { }
}
