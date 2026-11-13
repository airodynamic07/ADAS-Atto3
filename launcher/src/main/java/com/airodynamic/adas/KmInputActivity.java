package com.airodynamic.adas;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

/**
 * "Best stretch length" as its own small window (user, 2026-11-13): as a dialog inside the launcher it opened
 * behind the Ghost Race / song video windows and the keyboard never came up. Its own activity always comes to
 * the front with the number keyboard; Ghost Race comes back on top when it closes.
 */
public class KmInputActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Best stretch length");
        Theme th = new Prefs(this).theme();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        root.setPadding(p, Ui.dp(this, 12), p, Ui.dp(this, 12));
        root.addView(Ui.text(this, "Find the 10 best stretches of this many km (1–100)", 15, th.muted, false));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.BOTTOM);
        final EditText in = new EditText(this);
        in.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        double cur = TripEnergy.get(this).bestKm();
        in.setText(cur == Math.floor(cur) ? String.valueOf((int) cur) : String.valueOf(cur));
        in.setSelectAllOnFocus(true);
        in.setTextSize(26);
        row.addView(in, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(Ui.text(this, " km", 16, th.muted, false));
        root.addView(row);
        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.END);
        btns.addView(Ui.chip(this, "Cancel", false, th, v -> finish()));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.leftMargin = Ui.dp(this, 10);
        btns.addView(Ui.chip(this, "Save", true, th, v -> {
            try {
                float km = Float.parseFloat(in.getText().toString().trim());
                if (km < 1 || km > 100) throw new NumberFormatException();
                new Prefs(this).setBestKm(km);
                TripEnergy.get(this).setBestKm(km);
                finish();
            } catch (Exception e) {
                Toast.makeText(this, "Type a number from 1 to 100", Toast.LENGTH_SHORT).show();
            }
        }), sl);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.topMargin = Ui.dp(this, 12);
        root.addView(btns, bl);
        setContentView(root);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        in.requestFocus();
    }
}
