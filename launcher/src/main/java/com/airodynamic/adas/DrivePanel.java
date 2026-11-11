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
    private final Prefs prefs;
    private final TextView used, avg, dist, power, soc, since, gptText, topSpd, peak, zeroTo, avgSpd, driveT, brakes, bestTitle, bestEmpty;
    private final TextView[] bestKwh = new TextView[4], bestSpd = new TextView[4], bestRange = new TextView[4], bestKmTxt = new TextView[4];
    private final LinearLayout[] bestRows = new LinearLayout[4];

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

        // --- box 2: this trip (user's layout, 2026-11-11): one line of energy, 9 small stats, big "Best 10 km"
        LinearLayout en = card();
        // header line: THIS TRIP · Used · Avg · start time · reset
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.BOTTOM);
        head.addView(Ui.label(c, "This trip  ", th));
        head.addView(Ui.text(c, "Used ", 11, th.muted, false));
        used = Ui.text(c, "–", 20, th.text, true);
        head.addView(used);
        head.addView(Ui.text(c, " kWh   Avg ", 11, th.muted, false));
        avg = Ui.text(c, "–", 20, Theme.CAUTION, true);
        head.addView(avg);
        head.addView(Ui.text(c, " kWh/100", 11, th.muted, false));
        head.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
        since = Ui.text(c, "", 11, th.muted, false);
        head.addView(since);
        TextView reset = Ui.text(c, "  ⟲", 16, th.accent, true);
        reset.setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 2), 0);
        reset.setOnClickListener(v -> { TripEnergy.get(c).reset(); Toast.makeText(c, "New trip started", Toast.LENGTH_SHORT).show(); });
        head.addView(reset);
        head.setPadding(0, 0, 0, Ui.dp(c, 5));
        en.addView(head);

        LinearLayout r1 = new LinearLayout(c), r2 = new LinearLayout(c), r3 = new LinearLayout(c);
        LinearLayout t1 = tile("Distance"); dist = big(t1);
        LinearLayout t2 = tile("Power now"); power = big(t2);
        LinearLayout t3 = tile("Battery"); soc = big(t3);
        r1.addView(t1, w(true)); r1.addView(t2, w(true)); r1.addView(t3, w(false));
        LinearLayout t4 = tile("Top speed"); topSpd = big(t4);
        LinearLayout t5 = tile("Peak / regen"); peak = big(t5);
        LinearLayout t6 = tile("Best 0–100"); zeroTo = big(t6);
        r2.addView(t4, w(true)); r2.addView(t5, w(true)); r2.addView(t6, w(false));
        LinearLayout t7 = tile("Avg speed"); avgSpd = big(t7);
        LinearLayout t8 = tile("Drive time"); driveT = big(t8);
        LinearLayout t9 = tile("Hard brakes"); brakes = big(t9);
        r3.addView(t7, w(true)); r3.addView(t8, w(true)); r3.addView(t9, w(false));
        for (LinearLayout r : new LinearLayout[]{r1, r2, r3}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = Ui.dp(c, 5);
            en.addView(r, lp);
        }

        // Best stretches: top 4, never overlapping; tap the title to type the length (km)
        LinearLayout b10 = col();
        b10.setGravity(Gravity.CENTER_VERTICAL);
        b10.setBackground(Ui.round(th.chip, th.line, 12, c));
        b10.setPadding(Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 10), Ui.dp(c, 4));
        bestTitle = Ui.text(c, "", 11, th.muted, false);
        bestTitle.setPadding(0, Ui.dp(c, 2), 0, Ui.dp(c, 3));
        bestTitle.setOnClickListener(v -> editBestKm());
        b10.addView(bestTitle);
        for (int i = 0; i < 4; i++) {
            LinearLayout r = new LinearLayout(c);
            r.setGravity(Gravity.BOTTOM);
            r.addView(Ui.text(c, String.valueOf(i + 1), 11, th.muted, false), new LinearLayout.LayoutParams(Ui.dp(c, 14), -2));
            bestKwh[i] = Ui.text(c, "", 14, i == 0 ? Theme.SAFE : th.text, true);
            r.addView(bestKwh[i], new LinearLayout.LayoutParams(Ui.dp(c, 40), -2));
            r.addView(Ui.text(c, "kWh/100", 10, th.muted, false), new LinearLayout.LayoutParams(Ui.dp(c, 52), -2));
            bestSpd[i] = Ui.text(c, "", 14, th.text, true);
            r.addView(bestSpd[i], new LinearLayout.LayoutParams(Ui.dp(c, 28), -2));
            r.addView(Ui.text(c, "km/h avg", 10, th.muted, false), new LinearLayout.LayoutParams(Ui.dp(c, 50), -2));
            bestRange[i] = Ui.text(c, "", 10, th.muted, false);
            r.addView(bestRange[i], new LinearLayout.LayoutParams(0, -2, 1f));
            bestKmTxt[i] = Ui.text(c, "", 10, th.muted, false);
            r.addView(bestKmTxt[i]);
            bestRows[i] = r;
            b10.addView(r);
        }
        bestEmpty = Ui.text(c, "", 11, th.muted, false);
        b10.addView(bestEmpty);
        en.addView(b10, new LinearLayout.LayoutParams(-1, 0, 1f));
        TripEnergy.get(c).setBestKm(prefs.bestKm());
        root.addView(en, new LinearLayout.LayoutParams(-1, 0, 1f));   // fills the window to the bottom
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
        l.setBackground(Ui.round(th.chip, th.line, 10, c));
        l.setPadding(Ui.dp(c, 7), Ui.dp(c, 3), Ui.dp(c, 6), Ui.dp(c, 3));   // compact: 3 rows must fit
        l.addView(Ui.text(c, label, 10.5f, th.muted, false));
        return l;
    }

    private TextView big(LinearLayout t) {
        TextView v = Ui.text(c, "–", 15, th.text, true);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.addView(v);
        return v;
    }
    private TextView sub(LinearLayout t) { TextView v = Ui.text(c, "", 11, th.muted, false); t.addView(v); return v; }

    private LinearLayout.LayoutParams w(boolean gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        if (gap) lp.rightMargin = Ui.dp(c, 5);
        return lp;
    }

    // ---------------------------------------------------------------- data

    @Override public void onTrip(TripEnergy t) {
        used.setText(String.format(Locale.US, "%.1f", t.kwh));
        double a = t.avg();
        avg.setText(Double.isNaN(a) ? "–" : String.format(Locale.US, "%.1f", a));
        dist.setText(String.format(Locale.US, "%.1f km", t.km));
        power.setText(Double.isNaN(t.powerKw) ? (t.fromRemaining ? "n/a" : "–") : String.format(Locale.US, "%+.0f kW", t.powerKw));
        since.setText(t.startedAt == 0 ? "" : "since " + new SimpleDateFormat("HH:mm", Locale.US).format(new Date(t.startedAt)));
        topSpd.setText(t.topSpeed > 0 ? Math.round(t.topSpeed) + " km/h" : "–");
        peak.setText(t.peakKw > 0 || t.maxRegenKw < 0 ? Math.round(t.peakKw) + " / " + Math.round(t.maxRegenKw) + " kW" : "–");
        zeroTo.setText(Double.isNaN(t.best0100) ? "–" : String.format(Locale.US, "%.1f s", t.best0100));
        double am = t.avgMoving();
        avgSpd.setText(Double.isNaN(am) ? "–" : Math.round(am) + " km/h");
        long sec = Math.round(t.movingSec + t.stoppedSec);
        driveT.setText(sec >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60)
                : String.format(Locale.US, "%d:%02d", sec / 60, sec % 60));
        brakes.setText(String.valueOf(t.hardBrakes));
        double bk = t.bestKm();
        String kmTxt = bk == Math.floor(bk) ? String.valueOf((int) bk) : String.format(Locale.US, "%.1f", bk);
        bestTitle.setText("BEST " + kmTxt + " KM STRETCHES OF THIS TRIP   ✎ tap to change");
        java.util.List<TripEnergy.Best> list = t.best();
        for (int i = 0; i < 4; i++) {
            boolean has = i < list.size();
            bestRows[i].setVisibility(has ? View.VISIBLE : View.GONE);
            if (!has) continue;
            TripEnergy.Best b = list.get(i);
            bestKwh[i].setText(String.format(Locale.US, "%.1f", b.kwh100));
            bestSpd[i].setText(Double.isNaN(b.speed) ? "–" : String.valueOf(Math.round(b.speed)));
            bestRange[i].setText(Double.isNaN(b.min) ? "" : String.format(Locale.US, "%.0f–%.0f km/h", b.min, b.max));
            bestKmTxt[i].setText(String.format(Locale.US, "km %.1f–%.1f", b.fromKm, b.toKm));
        }
        bestEmpty.setVisibility(list.size() < 4 ? View.VISIBLE : View.GONE);
        bestEmpty.setText(list.isEmpty() ? String.format(Locale.US, "shows after %s km (%.1f km so far)", kmTxt, t.km)
                : "more rows appear as the trip gets longer");
    }

    /** Pop-up with the number keyboard: type the stretch length in km (1–100). */
    private void editBestKm() {
        final android.widget.EditText in = new android.widget.EditText(c);
        in.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        double cur = TripEnergy.get(c).bestKm();
        in.setText(cur == Math.floor(cur) ? String.valueOf((int) cur) : String.valueOf(cur));
        in.setSelectAllOnFocus(true);
        in.setTextSize(26);
        LinearLayout box = new LinearLayout(c);
        int p = Ui.dp(c, 20);
        box.setPadding(p, Ui.dp(c, 6), p, 0);
        box.setGravity(Gravity.BOTTOM);
        box.addView(in, new LinearLayout.LayoutParams(0, -2, 1f));
        box.addView(Ui.text(c, " km", 16, th.muted, false));
        android.app.AlertDialog d = new android.app.AlertDialog.Builder(c)
                .setTitle("Best stretch length")
                .setMessage("Find the 4 best stretches of this many km (1–100)")
                .setView(box)
                .setPositiveButton("Save", (dlg, w) -> {
                    try {
                        float km = Float.parseFloat(in.getText().toString().trim());
                        if (km < 1 || km > 100) throw new NumberFormatException();
                        prefs.setBestKm(km);
                        TripEnergy.get(c).setBestKm(km);
                    } catch (Exception e) {
                        Toast.makeText(c, "Type a number from 1 to 100", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        // open the number keyboard straight away
        d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        d.show();
        in.requestFocus();
    }

    @Override public View view() { return root; }

    @Override public void setActive(boolean a) {
        if (a) TripEnergy.get(c).addListener(this); else TripEnergy.get(c).removeListener(this);
    }

    @Override public void onCar(CarData.State s) {
        soc.setText((Double.isNaN(s.socPct) ? "–" : Math.round(s.socPct) + "%") + (s.rangeKm >= 0 ? " · " + s.rangeKm + " km" : ""));
    }
}
