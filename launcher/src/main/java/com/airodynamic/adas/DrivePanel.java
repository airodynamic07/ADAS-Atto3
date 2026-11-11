package com.airodynamic.adas;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Left window (user's layout, 2026-11-10): ChatGPT on top, this trip's energy below,
 * theme dots + Settings at the bottom. The trip graph runs along the bottom of the screen.
 */
public final class DrivePanel implements Panel, TripEnergy.Listener {

    /** ChatGPT: GBox copy first (the car's own store may not have it), then a normal install. */
    private static final String[] CHATGPT = {"com.gbox.com.openai.chatgpt", "com.openai.chatgpt"};

    private final Context c;
    private final Theme th;
    private final LinearLayout root;
    private android.widget.GridLayout tray;
    private MusicCard music;
    private final Prefs prefs;
    private final TextView used, avg, dist, power, regen, soc, range, since, gptText;

    public DrivePanel(Context c, Theme th, Prefs prefs, View themeControls) {
        this.c = c;
        this.prefs = prefs;
        this.th = th;
        root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(c, 8);
        root.setPadding(pad, pad, pad, pad);

        // --- top row (user's layout, 2026-11-11): big ChatGPT box | theme dots + Settings over the app tray
        LinearLayout top = new LinearLayout(c);
        LinearLayout gpt = card();
        gpt.addView(Ui.label(c, "ChatGPT", th));
        String pkg = chatGpt();
        gptText = Ui.text(c, pkg == null ? "Install ChatGPT (GBox or store)" : "Opens " + Apps.label(c, pkg), 15, th.text, true);
        gptText.setPadding(0, Ui.dp(c, 3), 0, 0);
        gpt.addView(gptText);
        gpt.addView(new View(c), new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout gb = new LinearLayout(c);
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, Ui.dp(c, 44), 1f);
        b1.rightMargin = Ui.dp(c, 6);
        gb.addView(Ui.chip(c, "🎤 Talk", true, th, v -> openGpt()), b1);
        gb.addView(Ui.chip(c, "⤢ Open", false, th, v -> openGpt()), new LinearLayout.LayoutParams(0, Ui.dp(c, 44), 1f));
        gpt.addView(gb);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(0, -1, 1.25f);
        gl.rightMargin = Ui.dp(c, 6);
        top.addView(gpt, gl);

        LinearLayout side = new LinearLayout(c);
        side.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.bottomMargin = Ui.dp(c, 6);
        side.addView(themeControls, tl);
        tray = new android.widget.GridLayout(c);
        tray.setColumnCount(3);
        LinearLayout trayBox = card();
        trayBox.setPadding(Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6));
        trayBox.addView(tray);
        side.addView(trayBox, new LinearLayout.LayoutParams(-1, -2));
        drawShortcuts();
        top.addView(side, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams g1 = new LinearLayout.LayoutParams(-1, -2);
        g1.bottomMargin = Ui.dp(c, 6);
        root.addView(top, g1);

        // --- box 2: this trip's energy
        LinearLayout en = card();
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.label(c, "This trip · energy", th), new LinearLayout.LayoutParams(0, -2, 1f));
        since = Ui.text(c, "", 12, th.muted, false);
        head.addView(since);
        TextView reset = Ui.text(c, "  ⟲ reset", 13, th.accent, true);
        reset.setPadding(Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 2), Ui.dp(c, 6));
        reset.setOnClickListener(v -> { TripEnergy.get(c).reset(); Toast.makeText(c, "New trip started", Toast.LENGTH_SHORT).show(); });
        head.addView(reset);
        en.addView(head);

        LinearLayout bigRow = new LinearLayout(c);
        LinearLayout u = col();
        u.addView(Ui.text(c, "Used", 12, th.muted, false));
        used = Ui.text(c, "–", 30, th.text, true);
        u.addView(used);
        LinearLayout a = col();
        a.addView(Ui.text(c, "Average (live)", 12, th.muted, false));
        avg = Ui.text(c, "–", 30, Theme.CAUTION, true);
        a.addView(avg);
        bigRow.addView(u, new LinearLayout.LayoutParams(0, -2, 1f));
        bigRow.addView(a, new LinearLayout.LayoutParams(0, -2, 1.2f));
        en.addView(bigRow);

        LinearLayout small = new LinearLayout(c);
        small.setPadding(0, Ui.dp(c, 6), 0, 0);
        LinearLayout t1 = tile("Distance"); dist = big(t1);
        LinearLayout t2 = tile("Power now"); power = big(t2); regen = sub(t2);
        LinearLayout t3 = tile("Battery"); soc = big(t3); range = sub(t3);
        small.addView(t1, w(true)); small.addView(t2, w(true)); small.addView(t3, w(false));
        en.addView(small);
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(-1, -2);
        el.bottomMargin = Ui.dp(c, 6);
        root.addView(en, el);
        music = new MusicCard(c, th);
        root.addView(music.view(), new LinearLayout.LayoutParams(-1, -2));
        root.addView(new View(c), new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    /** App tray: tap an icon to open it full screen, press and hold to remove, + adds. */
    private void drawShortcuts() {
        tray.removeAllViews();
        android.content.pm.PackageManager pm = c.getPackageManager();
        java.util.List<String> list = prefs.shortcuts(c);
        for (String pkg : list) {
            android.widget.ImageView icon = new android.widget.ImageView(c);
            try { icon.setImageDrawable(pm.getApplicationIcon(pkg)); } catch (Exception e) { continue; }
            int ip = Ui.dp(c, 3);
            icon.setPadding(ip, ip, ip, ip);
            icon.setContentDescription(Apps.label(c, pkg));
            icon.setOnClickListener(v -> Apps.open(c, pkg, CarData.get(c).last()));
            icon.setOnLongClickListener(v -> {
                java.util.List<String> l = prefs.shortcuts(c);
                l.remove(pkg);
                prefs.setShortcuts(l);
                Toast.makeText(c, "Removed " + Apps.label(c, pkg), Toast.LENGTH_SHORT).show();
                drawShortcuts();
                return true;
            });
            tray.addView(icon, cell());
        }
        tray.addView(Ui.chip(c, "＋", false, th, v -> AppPicker.pickOne(c, th, pkg -> {
            java.util.List<String> l = prefs.shortcuts(c);
            if (!l.contains(pkg)) l.add(pkg);
            prefs.setShortcuts(l);
            drawShortcuts();
        })), cell());
    }

    private android.widget.GridLayout.LayoutParams cell() {
        android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams(
                android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED),
                android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f));
        lp.width = 0;
        lp.height = Ui.dp(c, 46);
        int m = Ui.dp(c, 3);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    private String chatGpt() {
        for (String p : CHATGPT) if (Apps.installed(c, p)) return p;
        return null;
    }

    /** Opens ChatGPT; in GBox it can't be told to start listening, so tap its mic there. */
    private void openGpt() {
        String p = chatGpt();
        if (p == null) {
            Toast.makeText(c, "ChatGPT is not installed. Install it in GBox (or from the store), then sign in with \"Continue with email\".", Toast.LENGTH_LONG).show();
            return;
        }
        Apps.open(c, p, CarData.get(c).last());
    }

    // ---------------------------------------------------------------- helpers

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(Ui.round(th.card, th.line, 14, c));
        int p = Ui.dp(c, 10);
        l.setPadding(p, Ui.dp(c, 8), p, Ui.dp(c, 8));
        return l;
    }

    private LinearLayout col() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout tile(String label) {
        LinearLayout l = col();
        l.setBackground(Ui.round(th.chip, th.line, 12, c));
        int p = Ui.dp(c, 8);
        l.setPadding(p, p, p, p);
        l.addView(Ui.text(c, label, 12, th.muted, false));
        return l;
    }

    private TextView big(LinearLayout t) { TextView v = Ui.text(c, "–", 20, th.text, true); t.addView(v); return v; }
    private TextView sub(LinearLayout t) { TextView v = Ui.text(c, "", 11, th.muted, false); t.addView(v); return v; }

    private LinearLayout.LayoutParams w(boolean gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        if (gap) lp.rightMargin = Ui.dp(c, 6);
        return lp;
    }

    // ---------------------------------------------------------------- data

    @Override public void onTrip(TripEnergy t) {
        used.setText(String.format(Locale.US, "%.1f kWh", t.kwh));
        double a = t.avg();
        avg.setText(Double.isNaN(a) ? "–" : String.format(Locale.US, "%.1f kWh/100", a));
        dist.setText(String.format(Locale.US, "%.1f km", t.km));
        power.setText(Double.isNaN(t.powerKw) ? (t.fromRemaining ? "n/a" : "–") : String.format(Locale.US, "%+.0f kW", t.powerKw));
        regen.setText(t.fromRemaining ? "from battery level" : String.format(Locale.US, "regen %.1f kWh back", t.regenKwh));
        since.setText(t.startedAt == 0 ? "" : "since " + new SimpleDateFormat("HH:mm", Locale.US).format(new Date(t.startedAt)));
    }

    @Override public View view() { return root; }

    @Override public void setActive(boolean a) {
        if (a) TripEnergy.get(c).addListener(this); else TripEnergy.get(c).removeListener(this);
        music.setActive(a);
    }

    @Override public void onCar(CarData.State s) {
        soc.setText(Double.isNaN(s.socPct) ? "–" : Math.round(s.socPct) + "%");
        range.setText(s.rangeKm >= 0 ? s.rangeKm + " km left" : "");
    }
}
