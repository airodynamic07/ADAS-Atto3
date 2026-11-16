package com.airodynamic.adas;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * "Share → NATEE Launcher" from Google Maps or any app (user, 2026-11-16): the shared place goes to Ghost Race,
 * which opens its destination search on it (a short link, else the place's name / address). Shows nothing itself.
 */
public class ShareActivity extends Activity {
    static final String TEXT = "share_text", TITLE = "share_title";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        CharSequence text = in.getCharSequenceExtra(Intent.EXTRA_TEXT);
        String title = in.getStringExtra(Intent.EXTRA_SUBJECT);
        if (text != null || title != null) {
            startActivity(new Intent(this, LauncherActivity.class)
                    .putExtra(TEXT, text == null ? "" : text.toString()).putExtra(TITLE, title == null ? "" : title)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
