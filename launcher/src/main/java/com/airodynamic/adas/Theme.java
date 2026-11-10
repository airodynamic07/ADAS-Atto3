package com.airodynamic.adas;

import android.graphics.Color;

/** The three looks from the design canvas. Status colours are the same in every theme. */
public enum Theme {
    BLUE("#05080F", "#0D1422", "#16203A", "#1E2A40", "#EAF1FF", "#97A6C0", "#4DA3FF", "#04101F"),
    WHITE("#F3F5F9", "#FFFFFF", "#EDF1F7", "#D5DCE8", "#0F1726", "#5B6577", "#1F6FEB", "#FFFFFF"),
    RED("#000000", "#0E0E10", "#1A1A1E", "#2A2A30", "#F4F4F6", "#9A9AA3", "#E5202E", "#FFFFFF");

    public final int bg, panel, chip, line, text, muted, accent, onAccent;

    Theme(String bg, String panel, String chip, String line, String text, String muted, String accent, String onAccent) {
        this.bg = Color.parseColor(bg);
        this.panel = Color.parseColor(panel);
        this.chip = Color.parseColor(chip);
        this.line = Color.parseColor(line);
        this.text = Color.parseColor(text);
        this.muted = Color.parseColor(muted);
        this.accent = Color.parseColor(accent);
        this.onAccent = Color.parseColor(onAccent);
    }

    public static final int SAFE = Color.parseColor("#3DD9C1");
    public static final int CAUTION = Color.parseColor("#FFB020");
    public static final int DANGER = Color.parseColor("#FF4D3D");
    public static final int TRAFFIC = Color.parseColor("#9AA8C2");
    public static final int PERSON = Color.parseColor("#B8A4FF");
}
