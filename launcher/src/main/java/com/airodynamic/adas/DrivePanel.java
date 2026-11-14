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
    private TextView batt, volt;
    private TextView outside, pm, limit, todayKm, todayKwh, todayAvg, todayDrive, todaySpd, tripSpd;
    private LinearLayout limitCard;
    private TextView used, avg, dist, power, soc, since, topSpd, peak, zeroTo, avgSpd, driveT, brakes, bestTitle, bestEmpty, regenPct;
    private SpecialRow recentRow, latestRow;
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

        // --- top (user's layout, 2026-11-12): three compact rows
        //     [PM2.5 | Outside | theme dots]  /  [My speed limit | apps in one row]  /  [Today in one line]
        // (user, 2026-11-13) rows 1, new row and 2 the same height; row 1 and the new row on the same 3 columns
        final int rowH = Ui.dp(c, 44);
        LinearLayout row1 = new LinearLayout(c);
        LinearLayout tp = flatTile("PM2.5"); pm = big(tp);
        // PM2.5 takes the old PM2.5 + Range width so "moderate" fits; Range moves to the row below (user, 2026-11-14)
        // exactly as wide as Range + the card next to it below (one gap included), so the theme card is exactly as
        // wide as the card under it (user, 2026-11-14)
        LinearLayout.LayoutParams pmLp = new LinearLayout.LayoutParams(Ui.dp(c, 6), -1, 2f);
        pmLp.rightMargin = Ui.dp(c, 6);
        row1.addView(tp, pmLp);
        row1.addView(themeControls, new LinearLayout.LayoutParams(0, -1, 1f));
        root.addView(row1, gapH(rowH));
        LinearLayout rowN = new LinearLayout(c);
        LinearLayout tc = flatTile("Range WLTP"); outside = big(tc);   // where Cabin was (user, 2026-11-14)
        LinearLayout tb = flatTile("My range"); batt = big(tb);   // from your own driving (user, 2026-11-14)
        LinearLayout tvC = flatTile("Battery left"); volt = big(tvC);   // kWh (user, 2026-11-14)
        rowN.addView(tc, wgap(1.2f)); rowN.addView(tb, wgap(.8f)); rowN.addView(tvC, new LinearLayout.LayoutParams(0, -1, 1f));
        root.addView(rowN, gapH(rowH));

        LinearLayout row2 = new LinearLayout(c);
        limitCard = flatTile("Limit");
        LinearLayout lb = new LinearLayout(c);
        lb.setGravity(Gravity.CENTER_VERTICAL);
        limit = big(new LinearLayout(c));
        ((android.view.ViewGroup) limit.getParent()).removeView(limit);
        lb.addView(limit, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams pb = new LinearLayout.LayoutParams(Ui.dp(c, 26), Ui.dp(c, 26));   // smaller − + so the box lines up with PM2.5 / Cabin (user, 2026-11-13)
        pb.leftMargin = Ui.dp(c, 3);
        android.widget.Button minus = Ui.chip(c, "−", false, th, v -> { prefs.setSpeedLimit(Math.max(30, prefs.speedLimit() - 10)); showLimit(); });
        android.widget.Button plus = Ui.chip(c, "+", false, th, v -> { prefs.setSpeedLimit(Math.min(180, prefs.speedLimit() + 10)); showLimit(); });
        for (android.widget.Button bt : new android.widget.Button[]{minus, plus}) { bt.setMinHeight(0); bt.setMinimumHeight(0); bt.setMinWidth(0); bt.setMinimumWidth(0); bt.setPadding(0, 0, 0, 0); lb.addView(bt, pb); }
        limit.setOnClickListener(v -> { prefs.setSpeedVoice(!prefs.speedVoice()); showLimit(); });
        limitCard.addView(lb, new LinearLayout.LayoutParams(0, -2, 1f));
        row2.addView(limitCard, wgap(1.2f));   // same width as PM2.5 and Cabin above
        tray = new android.widget.GridLayout(c);
        tray.setColumnCount(TRAY_N + 1);   // apps in one row + "＋"
        LinearLayout trayBox = card();
        trayBox.setGravity(Gravity.CENTER_VERTICAL);
        trayBox.setPadding(Ui.dp(c, 4), Ui.dp(c, 3), Ui.dp(c, 4), Ui.dp(c, 3));
        trayBox.addView(tray);
        row2.addView(trayBox, new LinearLayout.LayoutParams(0, -1, 1.8f));
        drawShortcuts();
        root.addView(row2, gapH(rowH));
        showLimit();

        // Today and This trip: the same 4 columns, label above value, lined up with the cards below (user, 2026-11-13)
        LinearLayout td = card();
        td.setPadding(Ui.dp(c, 9), Ui.dp(c, 5), Ui.dp(c, 9), Ui.dp(c, 6));
        TextView tl = Ui.label(c, "Today", th); tl.setTextSize(10);
        td.addView(tl);
        TextView[] tv = statRow(td);
        todayKm = tv[0]; todaySpd = tv[1]; todayKwh = tv[2]; todayAvg = tv[3]; todayDrive = tv[4];
        LinearLayout.LayoutParams g1 = new LinearLayout.LayoutParams(-1, -2);
        g1.bottomMargin = Ui.dp(c, 6);
        root.addView(td, g1);

        LinearLayout en = card();
        en.setPadding(Ui.dp(c, 9), Ui.dp(c, 5), Ui.dp(c, 9), Ui.dp(c, 6));
        TextView trl = Ui.label(c, "This trip", th); trl.setTextSize(10);
        en.addView(trl);
        TextView[] trv = statRow(en);
        dist = trv[0]; tripSpd = trv[1]; used = trv[2]; avg = trv[3]; driveT = trv[4];
        LinearLayout foot = new LinearLayout(c);
        foot.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        soc = Ui.text(c, "–", 11, th.text, true);
        foot.addView(soc);
        since = Ui.text(c, "", 11, th.muted, false);
        foot.addView(since);
        TextView reset = Ui.text(c, "  ⟲", 15, th.accent, true);
        reset.setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 2), 0);
        reset.setOnClickListener(v -> { TripEnergy.get(c).reset(); Toast.makeText(c, "New trip started", Toast.LENGTH_SHORT).show(); });
        foot.addView(reset);
        foot.setPadding(0, 0, 0, Ui.dp(c, 4));
        en.addView(foot, new LinearLayout.LayoutParams(-1, -2));

        // 8 short cards, 2 rows x 4, same columns as above (user, 2026-11-13)
        LinearLayout r1 = new LinearLayout(c), r2 = new LinearLayout(c);
        LinearLayout t1 = tile("Today route"); TextView mapB = big(t1); mapB.setText("🗺 Map ›");
        tapButton(t1, mapB);
        t1.setOnClickListener(v -> openRoute());
        LinearLayout t2 = tile("Peak power"); power = big(t2);
        LinearLayout t6 = tile("Best 0–100"); zeroTo = big(t6);
        LinearLayout t5 = tile("Regen (kWh · %)"); peak = big(t5);   // "0.8 · 12 %" on one line (user, 2026-11-13)
        regenPct = Ui.text(c, "", 11, TOP_GREEN, true);
        r1.addView(t1, w(true)); r1.addView(t2, w(true)); r1.addView(t6, w(true)); r1.addView(t5, wide());
        LinearLayout t8 = tile("Every day"); TextView histB = big(t8); histB.setText("📅 History"); histB.setTextSize(13.5f); histB.setEllipsize(null);
        tapButton(t8, histB);
        t8.setOnClickListener(v -> { if (historyOpener != null) historyOpener.run(); });
        LinearLayout t7 = tile("Avg speed"); avgSpd = big(t7);
        LinearLayout t4 = tile("Top speed"); topSpd = big(t4);
        LinearLayout t9 = tile("Battery used"); brakes = big(t9);
        r2.addView(t8, w(true)); r2.addView(t7, w(true)); r2.addView(t4, w(true)); r2.addView(t9, wide());
        for (LinearLayout r : new LinearLayout[]{r1, r2}) {   // the two card rows the same height (user, 2026-11-13)
            // 1.5 times shorter, so the Best list shows more rows (user, 2026-11-14)
            for (int k = 0; k < r.getChildCount(); k++) {
                LinearLayout card = (LinearLayout) r.getChildAt(k);
                card.setPadding(card.getPaddingLeft(), 0, card.getPaddingRight(), 0);
                card.setGravity(Gravity.CENTER_VERTICAL);
                ((TextView) card.getChildAt(0)).setTextSize(9.5f);
                if (card.getChildCount() > 1 && card.getChildAt(1) instanceof TextView) ((TextView) card.getChildAt(1)).setTextSize(14);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 34));
            lp.bottomMargin = Ui.dp(c, 6);
            en.addView(r, lp);
        }

        // Best stretches: Recent (live, orange), Latest (last finished, blue), then BEST 1–10; fills to the bottom
        LinearLayout b10 = col();
        b10.setBackground(Ui.round(th.chip, th.line, 12, c));
        b10.setPadding(Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 10), Ui.dp(c, 4));
        LinearLayout bh = new LinearLayout(c);
        bh.setGravity(Gravity.CENTER_VERTICAL);
        bestTitle = Ui.text(c, "", 12, th.muted, false);
        bh.addView(bestTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        bestBtn = Ui.chip(c, "✎ km", false, th, v -> editBestKm());
        bestBtn.setMinHeight(0); bestBtn.setMinimumHeight(0); bestBtn.setMinWidth(0); bestBtn.setMinimumWidth(0);
        bestBtn.setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 10), 0);
        bestBtn.setTextSize(12);
        bh.addView(bestBtn, new LinearLayout.LayoutParams(-2, Ui.dp(c, 26)));
        bh.setPadding(0, Ui.dp(c, 2), 0, Ui.dp(c, 3));
        b10.addView(bh);
        b10.addView(bestHeader());   // units on top, numbers only in the rows (user, 2026-11-13)
        recentRow = specialRow(b10, "Recent", Theme.CAUTION);
        latestRow = specialRow(b10, "Latest", th.accent);
        TextView bestHead = Ui.text(c, "BEST", 11, TOP_GREEN, true);
        bestHead.setLetterSpacing(0.08f);
        bestHead.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 1));
        b10.addView(bestHead);
        LinearLayout list = col();
        android.widget.ScrollView sv = new android.widget.ScrollView(c);
        sv.setVerticalScrollBarEnabled(true);
        sv.addView(list, new android.widget.ScrollView.LayoutParams(-1, -2));
        for (int i = 0; i < BEST_N; i++) {
            int fg = i == 0 ? TOP_GREEN : th.text;
            LinearLayout r = new LinearLayout(c);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(Ui.dp(c, 6), Ui.dp(c, 2), Ui.dp(c, 6), Ui.dp(c, 2));
            r.addView(cell(String.valueOf(i + 1), 14, i == 0 ? TOP_GREEN : th.muted, true, false), bc(0));
            bestKwh[i] = cell("", 18, fg, true, false); r.addView(bestKwh[i], bc(1));
            bestSpd[i] = cell("", 18, fg, true, false); r.addView(bestSpd[i], bc(2));
            bestRange[i] = cell("", 13, th.muted, false, false); r.addView(bestRange[i], bc(3));
            bestKmTxt[i] = cell("", 13, th.muted, false, true); r.addView(bestKmTxt[i], bc(4));
            bestRows[i] = r;
            list.addView(r);
        }
        bestEmpty = Ui.text(c, "", 12, th.muted, false);
        list.addView(bestEmpty);
        b10.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        en.addView(b10, new LinearLayout.LayoutParams(-1, 0, 1f));   // runs to the bottom of the window
        TripEnergy.get(c).setBestKm(prefs.bestKm());
        root.addView(en, new LinearLayout.LayoutParams(-1, 0, 1f));   // fills the window to the bottom
    }

    /** App tray: tap an icon to open it full screen, press and hold to remove, + adds. */
    private void drawShortcuts() {
        tray.removeAllViews();
        android.content.pm.PackageManager pm = c.getPackageManager();
        java.util.List<String> list = prefs.shortcuts(c);
        int shown = 0;
        for (String pkg : list) {
            if (shown >= TRAY_N) break;
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
            shown++;
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
        lp.height = Ui.dp(c, 30);
        int m = Ui.dp(c, 2);
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

    /** Flat card: label beside the value, one line (user, 2026-11-12: more room for Today). */
    private LinearLayout flatTile(String label) {
        LinearLayout l = new LinearLayout(c);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setBackground(Ui.round(th.card, th.line, 12, c));
        l.setPadding(Ui.dp(c, 8), Ui.dp(c, 2), Ui.dp(c, 6), Ui.dp(c, 2));
        TextView t = Ui.text(c, label + "  ", 10.5f, th.muted, false);
        l.addView(t);
        return l;
    }

    /** "Label value" in half of a row (Today card). */
    private TextView pair(LinearLayout row, String label) {
        LinearLayout p = new LinearLayout(c);
        p.setGravity(Gravity.BOTTOM);
        p.addView(Ui.text(c, label, 12, th.muted, false));
        TextView v = Ui.text(c, "–", 16, th.text, true);
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

    private static final int TRAY_N = 5;   // apps shown in the one-row tray (user, 2026-11-12)

    private LinearLayout.LayoutParams wgap(float w) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, w);
        lp.rightMargin = Ui.dp(c, 6);
        return lp;
    }

    private LinearLayout.LayoutParams gapH(int h) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, h);
        lp.bottomMargin = Ui.dp(c, 6);
        return lp;
    }

    private LinearLayout.LayoutParams gap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(c, 6);
        return lp;
    }

    private void showLimit() {
        limit.setText(prefs.speedLimit() + (prefs.speedVoice() ? " 🔊" : " 🔇"));
    }

    private static String pmLevel(int v) { return v < 0 ? "" : v <= 25 ? " good" : v <= 50 ? " moderate" : " unhealthy"; }

    /** Distance · Avg km/h · Used · Avg (kWh) · Drive: label above value (avg speed added, user 2026-11-14). */
    private TextView[] statRow(LinearLayout parent) {
        LinearLayout r = new LinearLayout(c);
        String[] labels = {"Distance (km)", "Avg km/h", "Used (kWh)", "kWh/100 km", "Drive"};   // units on top (user, 2026-11-13)
        float[] wts = {1.15f, .95f, 1f, 1f, .85f};
        TextView[] out = new TextView[5];
        for (int i = 0; i < 5; i++) {
            LinearLayout cl = col();
            cl.setPadding(Ui.dp(c, 7), 0, 0, 0);
            cl.addView(Ui.text(c, labels[i], 11, th.muted, false));
            out[i] = Ui.text(c, "–", 19, i == 3 ? Theme.CAUTION : th.text, true);
            out[i].setSingleLine(true);
            cl.addView(out[i]);
            r.addView(cl, new LinearLayout.LayoutParams(0, -2, wts[i]));
        }
        parent.addView(r, new LinearLayout.LayoutParams(-1, -2));
        return out;
    }

    /** One highlighted line in Best stretches (Recent / Latest). */
    private static final class SpecialRow { LinearLayout row; TextView kwh, spd, range, km; }
    /** Column widths shared by the header and every row: name, kWh/100, avg km/h, range, km. */
    private LinearLayout.LayoutParams bc(int i) {
        float[] wt = {.9f, 1f, 1f, 1.3f, 1.7f};
        return new LinearLayout.LayoutParams(0, -2, wt[i]);
    }
    private TextView cell(String t, float sp, int col, boolean bold, boolean right) {
        TextView v = Ui.text(c, t, sp, col, bold);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (right) v.setGravity(Gravity.END);
        return v;
    }
    private LinearLayout bestHeader() {
        LinearLayout r = new LinearLayout(c);
        r.setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 6), Ui.dp(c, 1));
        String[] h = {"", "kWh/100", "Avg km/h", "Range km/h", "km"};
        for (int i = 0; i < 5; i++) r.addView(cell(h[i], 11, th.muted, false, i == 4), bc(i));
        return r;
    }
    private SpecialRow specialRow(LinearLayout parent, String name, int col) {
        SpecialRow sr = new SpecialRow();
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(Ui.round((col & 0x00FFFFFF) | 0x33000000, col, 8, c));
        r.setPadding(Ui.dp(c, 6), Ui.dp(c, 2), Ui.dp(c, 6), Ui.dp(c, 2));
        r.addView(cell(name, 14, col, true, false), bc(0));
        sr.kwh = cell("", 18, col, true, false); r.addView(sr.kwh, bc(1));
        sr.spd = cell("", 18, col, true, false); r.addView(sr.spd, bc(2));
        sr.range = cell("", 13, th.muted, false, false); r.addView(sr.range, bc(3));
        sr.km = cell("", 13, th.muted, false, true); r.addView(sr.km, bc(4));
        sr.row = r;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(c, 3);
        parent.addView(r, lp);
        return sr;
    }
    private void fill(SpecialRow sr, TripEnergy.Best b, String tag) {
        sr.row.setVisibility(b == null ? View.GONE : View.VISIBLE);
        if (b == null) return;
        sr.kwh.setText(String.format(Locale.US, "%.1f", b.kwh100));
        sr.spd.setText(Double.isNaN(b.speed) ? "–" : String.valueOf(Math.round(b.speed)));
        sr.range.setText(Double.isNaN(b.min) ? "" : String.format(Locale.US, "%.0f–%.0f", b.min, b.max));
        sr.km.setText(String.format(Locale.US, "%.1f–%.1f · %s", b.fromKm, b.toKm, tag));
    }

    /** Tappable cards look like buttons: yellow in the blue / red themes, the theme's blue in white (user, 2026-11-13). */
    private void tapButton(LinearLayout card, TextView value) {
        boolean white = th == Theme.WHITE;
        int bg = white ? th.accent : 0xFFFF8A1F, fg = white ? 0xFFFFFFFF : 0xFF1A0B00;   // orange (user, 2026-11-13)
        card.setBackground(Ui.round(bg, bg, 10, c));
        value.setTextColor(fg);
        ((TextView) card.getChildAt(0)).setTextColor(white ? 0xDDFFFFFF : 0xFF3A1800);
    }

    private LinearLayout.LayoutParams wide() { return new LinearLayout.LayoutParams(0, -1, 1.45f); }

    private LinearLayout.LayoutParams w(boolean gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        if (gap) lp.rightMargin = Ui.dp(c, 8);   // more room between the 9 cards (user, 2026-11-12)
        return lp;
    }

    // ---------------------------------------------------------------- data

    @Override public void onTrip(TripEnergy t) {
        used.setText(String.format(Locale.US, "%.1f", t.kwh));
        double a = t.avg();
        avg.setText(Double.isNaN(a) ? "–" : String.format(Locale.US, "%.1f", a));
        dist.setText(String.format(Locale.US, "%.1f", t.km));
        since.setText(t.startedAt == 0 ? "" : "  · since " + new SimpleDateFormat("HH:mm", Locale.US).format(new Date(t.startedAt)));
        topSpd.setText(t.topSpeed > 0 ? Math.round(t.topSpeed) + " km/h" : "–");
        double gross = t.kwh + t.regenKwh;   // energy driven before regen came back
        android.text.SpannableString rs = new android.text.SpannableString(String.format(Locale.US, "%.1f", t.regenKwh)
                + (gross > 0.05 ? " · " + Math.round(t.regenKwh / gross * 100) + " %" : ""));
        int cut = rs.toString().indexOf(" · ");
        if (cut > 0) rs.setSpan(new android.text.style.ForegroundColorSpan(TOP_GREEN), cut, rs.length(), 0);
        peak.setText(rs);
        power.setText(t.peakKw > 0 ? Math.round(t.peakKw) + " kW" : "–");
        zeroTo.setText(Double.isNaN(t.best0100) ? "–" : String.format(Locale.US, "%.1f s", t.best0100));
        double am = t.avgMoving();
        avgSpd.setText(Double.isNaN(am) ? "–" : Math.round(am) + " km/h");
        long sec = Math.round(t.movingSec + t.stoppedSec);
        driveT.setText(String.format(Locale.US, "%d:%02d", sec / 3600, sec / 60 % 60));
        // battery used = this trip's energy / 60.48 kWh; charging is never counted, so it keeps going past 100 %
        brakes.setText(Math.round(Math.max(0, t.kwh) / 60.48 * 100) + " %");

        double tk = t.todayKm(), te = t.todayKwh();
        // average speed while moving (stops not counted), coloured green < 60, blue 60–90, red > 90 (user, 2026-11-14)
        double tsec = t.todaySec(), tdS = tsec >= 60 ? tk / (tsec / 3600.0) : Double.NaN, trS = t.avgMoving();
        todaySpd.setText(Double.isNaN(tdS) ? "–" : String.valueOf(Math.round(tdS)));
        if (!Double.isNaN(tdS)) todaySpd.setTextColor(Theme.speedColour(tdS));
        tripSpd.setText(Double.isNaN(trS) ? "–" : String.valueOf(Math.round(trS)));
        if (!Double.isNaN(trS)) tripSpd.setTextColor(Theme.speedColour(trS));
        todayKm.setText(String.format(Locale.US, "%.1f", tk));
        todayKwh.setText(String.format(Locale.US, "%.1f", te));
        todayAvg.setText(tk >= 0.5 && te > 0 ? String.format(Locale.US, "%.1f", te / tk * 100) : "–");
        long ts = Math.round(t.todaySec());
        todayDrive.setText(String.format(Locale.US, "%d:%02d", ts / 3600, ts / 60 % 60));
        double bk = t.bestKm();
        String kmTxt = bk == Math.floor(bk) ? String.valueOf((int) bk) : String.format(Locale.US, "%.1f", bk);
        bestTitle.setText("BEST " + kmTxt + " KM STRETCHES OF THIS TRIP");
        bestBtn.setText("✎ " + kmTxt + " km");
        fill(recentRow, t.latest(), "live");
        fill(latestRow, t.lastBlock(), "saved");
        java.util.List<TripEnergy.Best> list = t.best();
        for (int i = 0; i < BEST_N; i++) {
            boolean has = i < list.size();
            bestRows[i].setVisibility(has ? View.VISIBLE : View.GONE);
            if (!has) continue;
            TripEnergy.Best b = list.get(i);
            bestKwh[i].setText(String.format(Locale.US, "%.1f", b.kwh100));
            bestSpd[i].setText(Double.isNaN(b.speed) ? "–" : String.valueOf(Math.round(b.speed)));
            bestRange[i].setText(Double.isNaN(b.min) ? "" : String.format(Locale.US, "%.0f–%.0f", b.min, b.max));
            bestKmTxt[i].setText(String.format(Locale.US, "%.1f–%.1f", b.fromKm, b.toKm));
        }
        bestEmpty.setVisibility(list.size() < BEST_N ? View.VISIBLE : View.GONE);
        bestEmpty.setText(list.isEmpty() ? String.format(Locale.US, "shows after %s km (%.1f km so far)", kmTxt, t.km)
                : "more rows appear as the trip gets longer");
    }

    /** Pop-up with the number keyboard: type the stretch length in km (1–100). */
    private void editBestKm() {
        // its own window, so it's always on top (also over Ghost Race / the song video) and the keyboard opens
        c.startActivity(new android.content.Intent(c, KmInputActivity.class));
    }

    @Override public View view() { return root; }

    /** Set by the launcher: opens Today's route map in the middle window. */
    public void setRouteOpener(Runnable r) { routeOpener = r; }
    /** Set by the launcher: opens the driving history over this window. */
    public void setHistoryOpener(Runnable r) { historyOpener = r; }
    private Runnable historyOpener;
    private Runnable routeOpener;
    private android.widget.Button bestBtn;
    private void openRoute() { if (routeOpener != null) routeOpener.run(); }

    @Override public void setActive(boolean a) {
        if (a) TripEnergy.get(c).addListener(this); else TripEnergy.get(c).removeListener(this);
    }

    @Override public void onCar(CarData.State s) {
        // WLTP range (user, 2026-11-14): the car shows NEDC range; Atto 3 is rated 480 km NEDC and 420 km WLTP → × 0.875
        outside.setText(s.rangeKm >= 0 ? Math.round(s.rangeKm * 420.0 / 480.0) + " km" : "–");
        // My range = battery left ÷ your kWh/100 over your last 50 km × 100; before 10 km of your own driving the car's
        // own "last 50 km" figure is used (user, 2026-11-14)
        double left = !Double.isNaN(s.battLeftKwh) ? s.battLeftKwh : !Double.isNaN(s.socPct) ? s.socPct / 100 * 60.48 : Double.NaN;
        double mine = TripEnergy.get(c).myAvg50();
        if (Double.isNaN(mine)) mine = s.last50;
        batt.setText(!Double.isNaN(left) && !Double.isNaN(mine) && mine > 3 ? Math.round(left / mine * 100) + " km" : "–");
        volt.setText(Double.isNaN(left) ? "–" : String.format(Locale.US, "%.1f kWh", left));
        pm.setText((s.pmOut < 0 ? "–" : String.valueOf(s.pmOut)) + " / " + (s.pmIn < 0 ? "–" : String.valueOf(s.pmIn)) + pmLevel(s.pmOut));
        pm.setTextColor(s.pmOut > 50 ? Theme.DANGER : s.pmOut > 25 ? Theme.CAUTION : th.text);
        // My speed limit: card turns red while over (warning only; SpeedAlert beeps)
        boolean over = SpeedAlert.get(c).over();
        limitCard.setBackground(over ? Ui.round(Theme.DANGER, Theme.DANGER, 12, c) : Ui.round(th.card, th.line, 12, c));
        limit.setTextColor(over ? 0xFFFFFFFF : th.text);
        soc.setText("🔋 " + (Double.isNaN(s.socPct) ? "–" : Math.round(s.socPct) + "%") + (s.rangeKm >= 0 ? " · " + s.rangeKm + " km" : ""));
    }
}
