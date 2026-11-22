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
        // the wheel button also sends 327 right after 304: swallow it too, or BYD's own voice assistant opens as well
        if (code == 327 && store(this).getInt("chosen", -1) < 0) return true;
        if (!isChatKey(this, code)) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) AiChat.startNow(this);
        return true;   // the wheel button now belongs to Natee chat
    }

    static boolean isChatKey(Context c, int code) {
        int chosen = store(c).getInt("chosen", -1);
        if (chosen >= 0) return code == chosen;
        // 304 = AUTO_MEDIA_VOICE: the Atto 3's steering-wheel mic button (seen in the button test, 2026-11-22)
        return code == 304 || code == KeyEvent.KEYCODE_VOICE_ASSIST || code == KeyEvent.KEYCODE_ASSIST || code == KeyEvent.KEYCODE_SEARCH;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
}
