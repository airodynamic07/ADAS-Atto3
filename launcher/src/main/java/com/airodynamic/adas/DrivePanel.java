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


    private final Context c;
    private final Theme th;
    private final LinearLayout root;
    private android.widget.GridLayout tray;
    private final Prefs prefs;
    private TextView outside, pm, limit, todayKm, todayKwh, todayAvg, todayDrive;
    private LinearLayout limitCard;
    private final TextView used, avg, dist, power, soc, since, topSpd, peak, zeroTo, avgSpd, driveT, brakes, bestTitle, bestEmpty;
    private static final int BEST_N = 10;   // keep the 10 best, scroll to see them (user, 2026-11-12)
    private static final int TOP_GREEN = 0xFF2EE59D;
    private final TextView[] bestKwh = new TextView[BEST_N], bestSpd = new TextView[BEST_N], bestRange = new TextView[BEST_N], bestKmTxt = new TextView[BEST_N];
    private final LinearLayout[] bestRows = new LinearLayout[BEST_N];

    public DrivePanel(Context c, Theme th, Prefs prefs, View themeControls) {
        this.c = c;
        this.prefs = prefs;
        this.th = th;
        root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(c, 8);
        root.setPadding(pad, pad, pad, pad);

        // --- top (user's layout, 2026-11-11): [PM2.5 / my speed limit / today] | [outside · theme row / app tray, 4 per row]
        LinearLayout top = new LinearLayout(c);
        LinearLayout info = new LinearLayout(c);
        info.setOrientation(LinearLayout.VERTICAL);
        // (user, 2026-11-11) PM2.5 gets the whole first row so "Moderate" fits; these cards use the Trip card colour
        LinearLayout tp = infoTile("PM2.5 out / in"); pm = big(tp);
        info.addView(tp, gap());
        limitCard = infoTile("My speed limit");
        LinearLayout lb = new LinearLayout(c);
        lb.setGravity(Gravity.CENTER_VERTICAL);
        limit = big(new LinearLayout(c));
        ((android.view.ViewGroup) limit.getParent()).removeView(limit);
        lb.addView(limit, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams pb = new LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 32));
        pb.leftMargin = Ui.dp(c, 4);
        android.widget.Button minus = Ui.chip(c, "−", false, th, v -> { prefs.setSpeedLimit(Math.max(30, prefs.speedLimit() - 10)); showLimit(); });
        android.widget.Button plus = Ui.chip(c, "+", false, th, v -> { prefs.setSpeedLimit(Math.min(180, prefs.speedLimit() + 10)); showLimit(); });
        for (android.widget.Button bt : new android.widget.Button[]{minus, plus}) { bt.setMinHeight(0); bt.setMinimumHeight(0); lb.addView(bt, pb); }
        limit.setOnClickListener(v -> { prefs.setSpeedVoice(!prefs.speedVoice()); showLimit(); });
        limitCard.addView(lb);
        info.addView(limitCard, gap());
        // Today: taller, fills down to the bottom of the app box; 2 lines of 2 values
        LinearLayout td = infoTile("Today");
        td.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tr1 = new LinearLayout(c), tr2 = new LinearLayout(c);
        todayKm = pair(tr1, "Distance "); todayKwh = pair(tr1, "Used ");
        todayAvg = pair(tr2, "Avg "); todayDrive = pair(tr2, "Drive ");
        td.addView(tr1); td.addView(tr2);
        info.addView(td, new LinearLayout.LayoutParams(-1, 0, 1f));
        showLimit();
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(0, -1, 1.4f);   // wider so Today fits (user, 2026-11-11)
        il.rightMargin = Ui.dp(c, 6);
        top.addView(info, il);

        LinearLayout side = new LinearLayout(c);
        side.setOrientation(LinearLayout.VERTICAL);
        // outside temperature sits in the theme row; the theme dots get narrower
        LinearLayout themeRow = new LinearLayout(c);
        LinearLayout to = infoTile("Outside"); outside = big(to);
        to.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(-2, -1);
        ol.rightMargin = Ui.dp(c, 6);
        themeRow.addView(to, ol);
        themeRow.addView(themeControls, new LinearLayout.LayoutParams(0, -2, 1f));
        side.addView(themeRow, gap());
        tray = new android.widget.GridLayout(c);
        tray.setColumnCount(4);
        LinearLayout trayBox = card();
        trayBox.setGravity(Gravity.CENTER_VERTICAL);
        trayBox.setPadding(Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6));
        trayBox.addView(tray);
        side.addView(trayBox, new LinearLayout.LayoutParams(-1, 0, 1f));   // bottom lines up with Today
        drawShortcuts();
        top.addView(side, new LinearLayout.LayoutParams(0, -1, 1f));   // narrower app box + theme dots; Outside keeps its size
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
            lp.bottomMargin = Ui.dp(c, 4);
            en.addView(r, lp);
        }

        // Best stretches: top 3 (bigger text), never overlapping; tap the title to type the length (km)
        LinearLayout b10 = col();
        b10.setGravity(Gravity.CENTER_VERTICAL);
        b10.setBackground(Ui.round(th.chip, th.line, 12, c));
        b10.setPadding(Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 10), Ui.dp(c, 4));
        bestTitle = Ui.text(c, "", 12, th.muted, false);
        bestTitle.setPadding(0, Ui.dp(c, 2), 0, Ui.dp(c, 3));
        bestTitle.setOnClickListener(v -> editBestKm());
        b10.addView(bestTitle);
        // the rows scroll; the title stays put. Best is always on top; 1–3 in green.
        LinearLayout list = col();
        android.widget.ScrollView sv = new android.widget.ScrollView(c);
        sv.setVerticalScrollBarEnabled(true);
        sv.setFillViewport(true);
        sv.addView(list, new android.widget.ScrollView.LayoutParams(-1, -2));
        for (int i = 0; i < BEST_N; i++) {
            int fg = i < 3 ? TOP_GREEN : th.text;
            LinearLayout r = new LinearLayout(c);
            r.setGravity(Gravity.BOTTOM);
            r.setPadding(0, Ui.dp(c, 1), 0, Ui.dp(c, 1));
            r.addView(Ui.text(c, String.valueOf(i + 1), 13, i < 3 ? TOP_GREEN : th.muted, true), new LinearLayout.LayoutParams(Ui.dp(c, 20), -2));
            bestKwh[i] = Ui.text(c, "", 17, fg, true);
            r.addView(bestKwh[i], new LinearLayout.LayoutParams(Ui.dp(c, 50), -2));
            r.addView(Ui.text(c, "kWh/100", 11, th.muted, false), new LinearLayout.LayoutParams(Ui.dp(c, 54), -2));
            bestSpd[i] = Ui.text(c, "", 17, fg, true);
            r.addView(bestSpd[i], new LinearLayout.LayoutParams(Ui.dp(c, 36), -2));
            r.addView(Ui.text(c, "km/h", 11, th.muted, false), new LinearLayout.LayoutParams(Ui.dp(c, 36), -2));
            bestRange[i] = Ui.text(c, "", 12, th.muted, false);
            r.addView(bestRange[i], new LinearLayout.LayoutParams(0, -2, 1f));
            bestKmTxt[i] = Ui.text(c, "", 12, th.muted, false);
            r.addView(bestKmTxt[i]);
            bestRows[i] = r;
            list.addView(r);
        }
        bestEmpty = Ui.text(c, "", 11, th.muted, false);
        list.addView(bestEmpty);
        b10.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
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
        lp.height = Ui.dp(c, 40);
        int m = Ui.dp(c, 3);
        lp.setMargins(m, m, m, m);
        return lp;
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
        l.setPadding(Ui.dp(c, 7), Ui.dp(c, 2), Ui.dp(c, 6), Ui.dp(c, 2));   // compact: 3 rows + best 3 must fit
        l.addView(Ui.text(c, label, 10.5f, th.muted, false));
        return l;
    }

    /** Info card with the same background as the Trip card (user, 2026-11-11). */
    private LinearLayout infoTile(String label) {
        LinearLayout l = col();
        l.setBackground(Ui.round(th.card, th.line, 12, c));
        l.setPadding(Ui.dp(c, 8), Ui.dp(c, 4), Ui.dp(c, 6), Ui.dp(c, 4));
        l.addView(Ui.text(c, label, 10.5f, th.muted, false));
        return l;
    }

    /** "Label value" in half of a row (Today card). */
    private TextView pair(LinearLayout row, String label) {
        LinearLayout p = new LinearLayout(c);
        p.setGravity(Gravity.BOTTOM);
        p.addView(Ui.text(c, label, 11, th.muted, false));
        TextView v = Ui.text(c, "–", 14, th.text, true);
        v.setSingleLine(true);
        p.addView(v);
        row.addView(p, new LinearLayout.LayoutParams(0, -2, 1f));
        return v;
    }

    private TextView big(LinearLayout t) {
        TextView v = Ui.text(c, "–", 15, th.text, true);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.addView(v);
        return v;
    }
    private TextView sub(LinearLayout t) { TextView v = Ui.text(c, "", 11, th.muted, false); t.addView(v); return v; }

    private LinearLayout.LayoutParams gap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(c, 6);
        return lp;
    }

    private void showLimit() {
        limit.setText(prefs.speedLimit() + (prefs.speedVoice() ? " 🔊" : " 🔇"));
    }

    private static String pmLevel(int v) { return v < 0 ? "" : v <= 25 ? " good" : v <= 50 ? " moderate" : " unhealthy"; }

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
        double tk = t.todayKm(), te = t.todayKwh();
        todayKm.setText(String.format(Locale.US, "%.1f km", tk));
        todayKwh.setText(String.format(Locale.US, "%.1f kWh", te));
        todayAvg.setText(tk >= 0.5 ? String.format(Locale.US, "%.1f /100", te / tk * 100) : "–");
        long ts = Math.round(t.todaySec());
        todayDrive.setText(String.format(Locale.US, "%d:%02d", ts / 3600, ts / 60 % 60));
        double bk = t.bestKm();
        String kmTxt = bk == Math.floor(bk) ? String.valueOf((int) bk) : String.format(Locale.US, "%.1f", bk);
        bestTitle.setText("BEST " + kmTxt + " KM STRETCHES OF THIS TRIP   ✎ tap to change");
        java.util.List<TripEnergy.Best> list = t.best();
        for (int i = 0; i < BEST_N; i++) {
            boolean has = i < list.size();
            bestRows[i].setVisibility(has ? View.VISIBLE : View.GONE);
            if (!has) continue;
            TripEnergy.Best b = list.get(i);
            bestKwh[i].setText(String.format(Locale.US, "%.1f", b.kwh100));
            bestSpd[i].setText(Double.isNaN(b.speed) ? "–" : String.valueOf(Math.round(b.speed)));
            bestRange[i].setText(Double.isNaN(b.min) ? "" : String.format(Locale.US, "%.0f–%.0f km/h", b.min, b.max));
            bestKmTxt[i].setText(String.format(Locale.US, "km %.1f–%.1f", b.fromKm, b.toKm));
        }
        bestEmpty.setVisibility(list.size() < BEST_N ? View.VISIBLE : View.GONE);
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
                .setMessage("Find the 10 best stretches of this many km (1–100)")
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
        outside.setText(s.outsideC == Integer.MIN_VALUE ? "–" : s.outsideC + "°C");
        pm.setText((s.pmOut < 0 ? "–" : String.valueOf(s.pmOut)) + " / " + (s.pmIn < 0 ? "–" : String.valueOf(s.pmIn)) + pmLevel(s.pmOut));
        pm.setTextColor(s.pmOut > 50 ? Theme.DANGER : s.pmOut > 25 ? Theme.CAUTION : th.text);
        // My speed limit: card turns red while over (warning only; SpeedAlert beeps)
        boolean over = SpeedAlert.get(c).over();
        limitCard.setBackground(over ? Ui.round(Theme.DANGER, Theme.DANGER, 12, c) : Ui.round(th.card, th.line, 12, c));
        limit.setTextColor(over ? 0xFFFFFFFF : th.text);
        soc.setText((Double.isNaN(s.socPct) ? "–" : Math.round(s.socPct) + "%") + (s.rangeKm >= 0 ? " · " + s.rangeKm + " km" : ""));
    }
}
