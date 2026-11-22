package com.airodynamic.adas;

import android.app.Activity;
import android.os.Bundle;

/**
 * When the car's voice button asks Android for "the voice assistant", NATEE answers: it opens Hey Natee chat
 * (user, 2026-11-22). Invisible, closes at once.
 */
public final class VoiceEntry extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (new Prefs(this).aiChat()) AiChat.startNow(this);
        finish();
        overridePendingTransition(0, 0);
    }
}
