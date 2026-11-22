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

    @Override protected boolean onKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            SharedPreferences s = store(this);
            String seen = s.getString("seen", "");
            String item = code + " " + KeyEvent.keyCodeToString(code).replace("KEYCODE_", "") + " · " +
                    new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date());
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
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastPress > 700) { lastPress = now; pressedAt = now; AiChat.startNow(this); }
        }
        return true;   // the wheel button now belongs to Natee chat
    }

    /** BYD's own voice assistant still pops up from the wheel button: a voice window opening right after the press is closed. */
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (android.os.SystemClock.uptimeMillis() - pressedAt > 3000) return;
        CharSequence pk = event.getPackageName();
        if (pk == null) return;
        String p = pk.toString(), lp = p.toLowerCase(Locale.US);
        if (p.equals(getPackageName())) return;
        SharedPreferences s = store(this);
        String known = s.getString("bydVoice", "");
        boolean voice = p.equals(known) || lp.contains("voice") || lp.contains("speech") || lp.contains("assistant") || lp.contains(".vr")
                || lp.contains("xiaodi") || lp.contains("aispeech") || lp.contains("iflytek") || lp.contains("txz");
        s.edit().putString("afterPress", p).apply();   // shown in the wheel button test
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

    /** The key watcher is switched off by some updates: switch it on again through the car's ADB. */
    static void ensureOn(Context c) {
        try {
            String me = c.getPackageName() + "/" + KeyWatch.class.getName();
            String cur = android.provider.Settings.Secure.getString(c.getContentResolver(), android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (cur != null && cur.contains(me)) return;
            String list = cur == null || cur.isEmpty() || "null".equals(cur) ? me : cur + ":" + me;
            Updater.adbShell(c, "settings put secure enabled_accessibility_services " + list + "; settings put secure accessibility_enabled 1");
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
