package com.airodynamic.adas;

import android.graphics.Color;

/** The three looks from the design canvas. Status colours are the same in every theme. */
public enum Theme {
    // bg = screen, panel = Info/right windows, card = cards in them, chip = buttons/small tiles,
    // eng = Engine Sound window and graph (user's layout, 2026-11-11); blue/red cards darker (2026-11-11)
    BLUE("#000000", "#071226", "#030915", "#0A1A33", "#22365C", "#EAF1FF", "#8FA0BD", "#4DA3FF", "#04101F", "#05070B"),
    WHITE("#F3F4F6", "#C9CDD3", "#FFFFFF", "#EEF0F3", "#B8BDC5", "#111418", "#4A505A", "#1F6FEB", "#FFFFFF", "#FFFFFF"),
    RED("#000000", "#1C0507", "#0C0203", "#2A090C", "#56181E", "#F6EDEE", "#B79A9D", "#E5202E", "#FFFFFF", "#050304"),
    // (Air 2026-11-26) more colours, the same dark style, from the car's ambient colour bar
    GREEN(0xFF1AE076), PURPLE(0xFF9A1CF0), ORANGE(0xFFEA700E), CYAN(0xFF0AC4F1), PINK(0xFFD815EB), YELLOW(0xFFDACB0D);

    /** The colour shown for this theme in the Theme menu. */
    public int swatch() { return this == WHITE ? 0xFFFFFFFF : this == BLUE ? 0xFF2E8BFF : accent; }
    public String label() { String n = name(); return n.charAt(0) + n.substring(1).toLowerCase(java.util.Locale.US); }

    /** The colour theme closest to an ambient light colour (whitish ambient = Blue). */
    public static Theme nearest(int rgb) {
        float[] hsv = new float[3];
        Color.colorToHSV(rgb, hsv);
        if (hsv[1] < 0.3f) return BLUE;
        Theme best = BLUE; float bestD = 999;
        for (Theme t : values()) {
            if (t == WHITE) continue;
            float[] h2 = new float[3];
            Color.colorToHSV(t.swatch(), h2);
            float d = Math.abs(hsv[0] - h2[0]); d = Math.min(d, 360 - d);
            if (d < bestD) { bestD = d; best = t; }
        }
        return best;
    }

    Theme(int acc) {
        this.bg = 0xFF000000;
        this.panel = mix(acc, 0.11f);
        this.card = mix(acc, 0.05f);
        this.chip = mix(acc, 0.16f);
        this.line = mix(acc, 0.33f);
        this.text = 0xFFF2F2F2;
        this.muted = 0xFFA9ADB3;
        this.accent = acc;
        float lum = (0.299f * Color.red(acc) + 0.587f * Color.green(acc) + 0.114f * Color.blue(acc)) / 255f;
        this.onAccent = lum > 0.6f ? 0xFF000000 : 0xFFFFFFFF;
        this.eng = 0xFF050505;
    }
    private static int mix(int c, float f) { return Color.rgb(Math.round(Color.red(c) * f), Math.round(Color.green(c) * f), Math.round(Color.blue(c) * f)); }

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
