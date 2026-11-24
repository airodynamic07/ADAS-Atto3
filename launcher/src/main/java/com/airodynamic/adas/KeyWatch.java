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
    private static volatile KeyWatch live;
    /** when Android last connected the watcher (uptime ms) */
    static volatile long connAtMs;
    @Override protected void onServiceConnected() {
        super.onServiceConnected(); live = this; connected = true; connAtMs = android.os.SystemClock.uptimeMillis();
        store(this).edit().putString("connAt", new java.text.SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(new java.util.Date())).apply();
    }

    /** Best guess only (shown in the test window); nothing is restarted because of it. */
    static boolean running() {
        KeyWatch k = live;
        if (k == null) return false;
        try { return k.getServiceInfo() != null; } catch (Throwable t) { return false; }
    }

    /**
     * (Air 2026-11-23) The launcher's own automatic restarts of the watcher did more harm than good: a watcher that was
     * taking the button fine right after a start stopped once it was restarted. So nothing is restarted automatically
     * any more. Only Air's tap on the "wheel mic → chat" button does it: out of Android's list, a pause, back in.
     * True when Android connected it afresh.
     */
    static synchronized boolean restart(Context c) {
        StringBuilder log = new StringBuilder(new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date()));
        long t0 = android.os.SystemClock.uptimeMillis();
        // step 1: out of Android's list, a pause, back in (by the launcher itself)
        log.append(" · own: ").append(toggle(c, true, false));
        if (waitConnected(t0, 6000)) { fixLog(c, log.append(" → ok")); return true; }
        // step 2 (Air 2026-11-24: "couldn't switch"): switch ALL accessibility off and on through the car's ADB,
        // which makes Android connect every watcher afresh
        long t1 = android.os.SystemClock.uptimeMillis();
        log.append(" · adb: ").append(toggle(c, true, true));
        if (waitConnected(t1, 8000)) { fixLog(c, log.append(" → ok")); return true; }
        // step 3 (Air 2026-11-24: steps 1 and 2 "still not connected"; only an update brought it back): Android can keep a
        // watcher stuck "waiting to start" and then ignores the settings; switching the watcher's own part of the app
        // off and on makes Android read it afresh, as an update does
        long t2 = android.os.SystemClock.uptimeMillis();
        try {
            android.content.ComponentName me = new android.content.ComponentName(c, KeyWatch.class);
            android.content.pm.PackageManager pm = c.getPackageManager();
            pm.setComponentEnabledSetting(me, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP);
            try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
            pm.setComponentEnabledSetting(me, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, android.content.pm.PackageManager.DONT_KILL_APP);
            try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
            log.append(" · part: ").append(toggle(c, true, false));
        } catch (Throwable t) { log.append(" · part: error ").append(t.getClass().getSimpleName()); }
        boolean ok = waitConnected(t2, 8000);
        if (ok) { fixLog(c, log.append(" → ok")); return true; }
        // step 4 (Air 2026-11-24: a plain launcher restart didn't help; only installing an update ever did): the car
        // installs the launcher's own APK over itself, exactly like an update, then switches the watcher on and opens
        // the launcher again. Runs in the car's shell, so it carries on while the launcher is replaced.
        try {
            String me = c.getPackageName() + "/" + KeyWatch.class.getName();
            String cur = android.provider.Settings.Secure.getString(c.getContentResolver(), android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String without = cur == null || "null".equals(cur) ? "" : cur.replace(":" + me, "").replace(me + ":", "").replace(me, "");
            String with = without.isEmpty() ? me : without + ":" + me;
            String pk = c.getPackageName();
            fixLog(c, log.append(" → reinstalling the launcher (like an update)"));
            store(c).edit().putBoolean("restartedForWatcher", true).commit();
            Updater.adbShell(c, "nohup sh -c 'pm install -r \"$(pm path " + pk + " | head -n 1 | cut -d: -f2)\"; sleep 2; "
                    + "settings put secure enabled_accessibility_services \"" + with + "\"; settings put secure accessibility_enabled 1; "
                    + "pm grant " + pk + " android.permission.WRITE_SECURE_SETTINGS; "
                    + "am start -n " + pk + "/.LauncherActivity' > /dev/null 2>&1 &");
        } catch (Throwable t) { fixLog(c, log.append(" → couldn't reach the car's ADB (").append(t.getClass().getSimpleName()).append(")")); }
        return false;
    }
    private static boolean waitConnected(long since, long ms) {
        for (long i = 0; i < ms / 500 && connAtMs < since; i++) { try { Thread.sleep(500); } catch (InterruptedException ignored) { } }
        return connAtMs >= since;
    }
    private static void fixLog(Context c, CharSequence l) { store(c).edit().putString("lastFix", l.toString()).apply(); }

    /** Puts the watcher into Android's list when it is missing (updates and car starts can drop it). Never restarts it. */
    static synchronized void ensureOn(Context c) {
        if (!isOn(c)) toggle(c, false, false);
    }

    /** @return what happened, for the test window */
    private static String toggle(Context c, boolean restart, boolean viaAdb) {
        try {
            String me = c.getPackageName() + "/" + KeyWatch.class.getName();
            android.content.ContentResolver cr = c.getContentResolver();
            String cur = android.provider.Settings.Secure.getString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            boolean listed = cur != null && cur.contains(me);
            if (listed && !restart) return "listed";
            boolean own = c.checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED;
            String without = cur == null || "null".equals(cur) ? "" : cur.replace(":" + me, "").replace(me + ":", "").replace(me, "");
            String with = without.isEmpty() ? me : without + ":" + me;
            if (own && !viaAdb) {
                if (listed) {
                    android.provider.Settings.Secure.putString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, without);
                    try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
                }
                android.provider.Settings.Secure.putString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, with);
                android.provider.Settings.Secure.putString(cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, "1");
                return "written";
            }
            if (!own && !viaAdb && !restart) viaAdb = true;
            if (!viaAdb) return "no permission";
            String out = Updater.adbShell(c, "settings put secure accessibility_enabled 0; settings put secure enabled_accessibility_services '" + without + "'; sleep 3; "
                    + "settings put secure enabled_accessibility_services '" + with + "'; settings put secure accessibility_enabled 1; "
                    + "pm grant " + c.getPackageName() + " android.permission.WRITE_SECURE_SETTINGS; echo done");
            return out == null ? "no answer" : out.trim().replace('\n', ' ');
        } catch (Throwable t) {
            return "error " + t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
        }
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
