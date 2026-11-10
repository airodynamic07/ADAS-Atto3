package com.airodynamic.adas;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/** Small helpers so every screen is built the same way (no XML layouts). */
public final class Ui {
    private Ui() {}

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    public static GradientDrawable round(int fill, int stroke, int radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) g.setStroke(dp(c, 1), stroke);
        return g;
    }

    public static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public static TextView label(Context c, String s, Theme th) {
        TextView t = text(c, s.toUpperCase(), 11, th.muted, false);
        t.setLetterSpacing(0.06f);
        return t;
    }

    /** A chip button; selected = accent fill. At least 44dp tall (touch target). */
    public static Button chip(Context c, String s, boolean selected, Theme th, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setMinHeight(dp(c, 44));
        b.setMinimumHeight(dp(c, 44));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(c, 6), 0, dp(c, 6), 0);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(selected ? th.onAccent : th.text);
        b.setBackground(round(selected ? th.accent : th.chip, selected ? th.accent : th.line, 12, c));
        b.setStateListAnimator(null);
        b.setOnClickListener(l);
        return b;
    }
}
