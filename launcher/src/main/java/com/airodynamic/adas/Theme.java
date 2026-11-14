package com.airodynamic.adas;

import android.graphics.Color;

/** The three looks from the design canvas. Status colours are the same in every theme. */
public enum Theme {
    // bg = screen, panel = Info/right windows, card = cards in them, chip = buttons/small tiles,
    // eng = Engine Sound window and graph (user's layout, 2026-11-11); blue/red cards darker (2026-11-11)
    BLUE("#000000", "#071226", "#030915", "#0A1A33", "#22365C", "#EAF1FF", "#8FA0BD", "#4DA3FF", "#04101F", "#05070B"),
    WHITE("#F3F4F6", "#C9CDD3", "#FFFFFF", "#EEF0F3", "#B8BDC5", "#111418", "#4A505A", "#1F6FEB", "#FFFFFF", "#FFFFFF"),
    RED("#000000", "#1C0507", "#0C0203", "#2A090C", "#56181E", "#F6EDEE", "#B79A9D", "#E5202E", "#FFFFFF", "#050304");

    public final int bg, panel, card, chip, line, text, muted, accent, onAccent, eng;

    Theme(String bg, String panel, String card, String chip, String line, String text, String muted, String accent, String onAccent, String eng) {
        this.bg = Color.parseColor(bg);
        this.panel = Color.parseColor(panel);
        this.card = Color.parseColor(card);
        this.chip = Color.parseColor(chip);
        this.line = Color.parseColor(line);
        this.text = Color.parseColor(text);
        this.muted = Color.parseColor(muted);
        this.accent = Color.parseColor(accent);
        this.onAccent = Color.parseColor(onAccent);
        this.eng = Color.parseColor(eng);
    }

    public static final int SAFE = Color.parseColor("#3DD9C1");
    public static final int CAUTION = Color.parseColor("#FFB020");
    /** Average speed colour (user, 2026-11-14): green under 60, blue 60–90, red over 90 km/h. */
    public static int speedColour(double kmh) { return kmh > 90 ? 0xFFFF5A5A : kmh >= 60 ? 0xFF3AA0FF : 0xFF2EE59D; }
    public static final int DANGER = Color.parseColor("#FF4D3D");
    public static final int TRAFFIC = Color.parseColor("#9AA8C2");
    public static final int PERSON = Color.parseColor("#B8A4FF");
}
