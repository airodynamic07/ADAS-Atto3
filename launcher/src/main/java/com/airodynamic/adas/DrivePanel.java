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
    private final LinearLayout shortcuts;
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

        // --- box 1: ChatGPT
        LinearLayout gpt = card();
        gpt.setOrientation(LinearLayout.HORIZONTAL);
        gpt.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout gt = new LinearLayout(c);
        gt.setOrientation(LinearLayout.VERTICAL);
        gt.addView(Ui.label(c, "ChatGPT", th));
        String pkg = chatGpt();
        gptText = Ui.text(c, pkg == null ? "Install ChatGPT (GBox or store)" : "Opens " + Apps.label(c, pkg), 13, th.text, false);
        gt.addView(gptText);
        gpt.addView(gt, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, Ui.dp(c, 44));
        bl.leftMargin = Ui.dp(c, 6);
        gpt.addView(Ui.chip(c, "🎤 Talk", true, th, v -> openGpt()), bl);
        gpt.addView(Ui.chip(c, "⤢ Open", false, th, v -> openGpt()), bl);
        LinearLayout box1 = card();
        box1.setPadding(0, 0, 0, 0);
        box1.addView(gpt);
        shortcuts = new LinearLayout(c);
        shortcuts.setGravity(Gravity.CENTER_VERTICAL);
        shortcuts.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 8), Ui.dp(c, 8));
        box1.addView(shortcuts);
        drawShortcuts();
        LinearLayout.LayoutParams g1 = new LinearLayout.LayoutParams(-1, -2);
        g1.bottomMargin = Ui.dp(c, 8);
        root.addView(box1, g1);

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
        used = Ui.text(c, "–", 34, th.text, true);
        u.addView(used);
        LinearLayout a = col();
        a.addView(Ui.text(c, "Average (live)", 12, th.muted, false));
        avg = Ui.text(c, "–", 34, Theme.CAUTION, true);
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
        View fill = new View(c);
        en.addView(fill, new LinearLayout.LayoutParams(-1, 0, 1f));
        en.addView(themeControls, new LinearLayout.LayoutParams(-2, -2));
        root.addView(en, new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    /** App icons: tap opens full screen, press and hold removes, + adds. */
    private void drawShortcuts() {
        shortcuts.removeAllViews();
        android.content.pm.PackageManager pm = c.getPackageManager();
        java.util.List<String> list = prefs.shortcuts(c);
        for (String pkg : list) {
            android.widget.ImageView icon = new android.widget.ImageView(c);
            try { icon.setImageDrawable(pm.getApplicationIcon(pkg)); } catch (Exception e) { continue; }
            int ip = Ui.dp(c, 4);
            icon.setPadding(ip, ip, ip, ip);
            icon.setBackground(Ui.round(th.panel, 0, 12, c));
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
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44));
            lp.rightMargin = Ui.dp(c, 6);
            shortcuts.addView(icon, lp);
        }
        shortcuts.addView(Ui.chip(c, "＋", false, th, v -> AppPicker.pickOne(c, th, pkg -> {
            java.util.List<String> l = prefs.shortcuts(c);
            if (!l.contains(pkg)) l.add(pkg);
            prefs.setShortcuts(l);
            drawShortcuts();
        })), new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));
        if (list.isEmpty()) shortcuts.addView(Ui.text(c, "  Add app shortcuts · hold an icon to remove", 12, th.muted, false));
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
        l.setBackground(Ui.round(th.chip, 0, 14, c));
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
        l.setBackground(Ui.round(th.panel, 0, 12, c));
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
    }

    @Override public void onCar(CarData.State s) {
        soc.setText(Double.isNaN(s.socPct) ? "–" : Math.round(s.socPct) + "%");
        range.setText(s.rangeKm >= 0 ? s.rangeKm + " km left" : "");
    }
}
