package com.airodynamic.adasprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Phase 0 probe: one screen, buttons on the left, live report on the right.
 * Every test saves a text file in Download/atto3_probe/.
 */
public class ProbeActivity extends Activity {

    private static final int BG = Color.parseColor("#05080F");
    private static final int ACCENT = Color.parseColor("#4DA3FF");
    private static final int TEAL = Color.parseColor("#3DD9C1");
    private static final int AMBER = Color.parseColor("#FFB020");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView log;
    private ScrollView logScroll;
    private TextView status;
    private Button modeButton;
    private boolean carTestsViaHelper = true;
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);

        // ---- left: buttons
        ScrollView leftScroll = new ScrollView(this);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(dp(10), dp(8), dp(10), dp(8));
        leftScroll.addView(left);
        root.addView(leftScroll, new LinearLayout.LayoutParams(dp(330), ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = text("ADAS Probe — Phase 0  v" + versionName(), 18, ACCENT, true);
        left.addView(title);
        status = text("Ready.", 13, TEAL, false);
        left.addView(status);

        header(left, "READ-ONLY  (safe any time)");
        button(left, "1  Basic check (app)", v -> runTask("app_basic", this::basicCheck));
        button(left, "2  Car data via helper (shell)", v -> helper("read", 240));
        button(left, "3  Camera picture via helper", v -> helper("camera", 120));
        button(left, "4  Camera picture from app (no helper)", v -> runTask("app_camera", this::appCamera));
        button(left, "5  Watch signals 30 s  (flick indicators!)", v -> watch());
        button(left, "6  Voice test (Thai + English)", v -> runTask("app_voice", this::voiceTest));
        button(left, "7  Open Google Maps in left window", v -> openWindow("com.google.android.apps.maps", 0));
        button(left, "8  Open YouTube in middle window", v -> openWindow("com.google.android.youtube", 1));
        button(left, "9  Open Netflix in right window", v -> openWindow("com.netflix.mediaclient", 2));

        header(left, "CONTROL TESTS  (car must be in P)");
        modeButton = button(left, "", v -> {
            carTestsViaHelper = !carTestsViaHelper;
            updateModeButton();
        });
        updateModeButton();
        control(left, "Fan +1, then back", "fan");
        control(left, "Temperature +1, then back", "temp");
        control(left, "Recirculation on, then off", "recirc");
        control(left, "Air direction: try each, then back", "airdir");
        control(left, "Front defrost on, then off", "defrost");
        control(left, "Air purify on, then off", "purify");
        control(left, "Driver window 5% down, then up", "window");
        control(left, "Sunroof small nudge (watch it!)", "sunroof");
        Button others = button(left, "Other cameras (may restart camera service)", v ->
                confirm("Other cameras", "Opens any extra cameras in the car's list (e.g. windshield). "
                        + "On some cars this briefly restarts the camera service and the 360 view. Car must be in P.",
                        () -> helper("cameras-all", 180)));
        others.setTextColor(AMBER);

        header(left, "REPORT");
        button(left, "Show where reports are saved", v -> append("\nReports folder: " + Report.DIR
                + "\nFiles: " + listReports()
                + "\nOn your phone (Termux):\n  adb pull " + Report.DIR + "\n"));
        TextView hint = text("If button 2 fails, run this in Termux instead:\n"
                + HelperLauncher.termuxCommand("read"), 11, Color.LTGRAY, false);
        hint.setTextIsSelectable(true);
        left.addView(hint);

        // ---- right: log
        logScroll = new ScrollView(this);
        log = text("", 12, Color.parseColor("#D8E4F0"), false);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        log.setPadding(dp(10), dp(8), dp(10), dp(8));
        logScroll.addView(log);
        root.addView(logScroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        setContentView(root);
        append("Welcome. Start with button 1, then 2 and 3. Every result is saved to " + Report.DIR + "\n"
                + "Control tests only run when the car is in P.\n");
    }

    // ------------------------------------------------------------------ UI helpers

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private void header(LinearLayout parent, String s) {
        TextView t = text(s, 12, Color.GRAY, true);
        t.setPadding(0, dp(12), 0, dp(4));
        parent.addView(t);
    }

    private Button button(LinearLayout parent, String label, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextSize(14);
        btn.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        btn.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(2);
        parent.addView(btn, lp);
        return btn;
    }

    private void control(LinearLayout parent, final String label, final String task) {
        button(parent, label, v -> confirm(label,
                "The car must be in P. This does one small change and then undoes it. "
                        + "Watch the car and note what happens.", () -> {
                    if (carTestsViaHelper) helper(task, 90);
                    else runTask("app_" + task, rep ->
                            ShellMain.runTask(task, new BypassContext(getApplicationContext()), rep));
                }));
    }

    private void updateModeButton() {
        modeButton.setText("Run car tests in: " + (carTestsViaHelper ? "HELPER (shell)" : "APP") + "  (tap to switch)");
    }

    private void confirm(String title, String msg, Runnable yes) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(msg)
                .setPositiveButton("Run", (d, w) -> yes.run())
                .setNegativeButton("Cancel", null).show();
    }

    private void append(final String s) {
        ui.post(() -> {
            log.append(s.endsWith("\n") ? s : s + "\n");
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void setStatus(final String s) { ui.post(() -> status.setText(s)); }

    private String listReports() {
        File[] fs = Report.DIR.listFiles();
        if (fs == null || fs.length == 0) return "(none yet)";
        StringBuilder sb = new StringBuilder();
        for (File f : fs) sb.append("\n  ").append(f.getName()).append("  (").append(f.length() / 1024).append(" KB)");
        return sb.toString();
    }

    // ------------------------------------------------------------------ running tasks

    private interface Task { void run(Report r) throws Exception; }

    private void runTask(final String name, final Task task) {
        if (busy) {
            append("(Busy — wait for the current test to finish.)");
            return;
        }
        busy = true;
        setStatus("Running: " + name + " ...");
        worker.execute(() -> {
            Report r = new Report(name, this::append);
            try {
                task.run(r);
            } catch (Throwable t) {
                r.line("ERROR: " + Report.stack(t));
            }
            File f = r.save();
            append("Saved: " + (f == null ? "(failed)" : f.getAbsolutePath()) + "\n");
            setStatus("Done: " + name);
            busy = false;
        });
    }

    private void helper(final String task, final int timeoutSec) {
        runTask("app_helper_" + task, r -> {
            try {
                String out = HelperLauncher.run(getApplicationContext(), task, timeoutSec, r);
                r.section("Helper output");
                for (String line : out.split("\n")) r.line(line);
            } catch (Throwable t) {
                r.error("starting helper", t);
                r.info("Tip: make sure wireless ADB is on, then try again. If the car showed 'Allow USB debugging?', "
                        + "tap Always allow + OK and press the button again.");
                r.info("Or run from Termux: " + HelperLauncher.termuxCommand(task));
            }
        });
    }

    private void basicCheck(Report r) {
        SystemProbe.run(this, r);
        CarProbe.plainContextCheck(getApplicationContext(), r);
        CarProbe car = new CarProbe(new BypassContext(getApplicationContext()), r);
        Report methods = new Report("app_car_methods", null);
        car.dumpAll(methods);
        methods.save();
        car.listUnknowns();
        Report ids = new Report("app_feature_ids", null);
        CarProbe.dumpFeatureIds(ids);
        ids.save();
        r.section("Gear / speed right now");
        r.info("gear code " + car.gear() + " (0/1 = P, 2 = R, 3 = N, 4 = D), speed " + CarProbe.fmt(car.speed())
                + ", parked = " + car.isParked());
        new CameraProbe(r).listCameras();
        r.info("");
        r.info("Full method lists: app_car_methods.txt, feature ids: app_feature_ids.txt");
    }

    private void appCamera(Report r) {
        CameraProbe cp = new CameraProbe(r);
        Map<String, Integer> cams = cp.listCameras();
        Integer pano = CameraProbe.panoId(cams);
        if (pano == null) {
            r.fail("camera", "no pano_h / pano_l camera in the list — not opening anything");
            return;
        }
        cp.capture(pano, 5120, 960, "app_pano_id" + pano);
    }

    private void watch() {
        if (carTestsViaHelper) {
            append("\nWATCH: in 5 seconds, flick LEFT indicator, then RIGHT, open and close a door, press the brake.\n");
            helper("watch", 90);
        } else {
            runTask("app_watch", r -> {
                r.info("In 5 seconds: flick LEFT indicator, then RIGHT, open and close a door, press the brake.");
                Thread.sleep(5000);
                new CarProbe(new BypassContext(getApplicationContext()), r).watch(30);
            });
        }
    }

    private void openWindow(String pkg, int slot) {
        append(SystemProbe.openInWindow(this, pkg, slot));
    }

    private void voiceTest(final Report r) throws Exception {
        final Object lock = new Object();
        final int[] st = {-99};
        final TextToSpeech[] holder = new TextToSpeech[1];
        ui.post(() -> holder[0] = new TextToSpeech(getApplicationContext(), s -> {
            synchronized (lock) { st[0] = s; lock.notifyAll(); }
        }));
        synchronized (lock) {
            long end = System.currentTimeMillis() + 8000;
            while (st[0] == -99 && System.currentTimeMillis() < end) lock.wait(500);
        }
        TextToSpeech tts = holder[0];
        if (tts == null || st[0] != TextToSpeech.SUCCESS) {
            r.fail("Text-to-speech", "engine did not start (status " + st[0] + ")");
            return;
        }
        r.ok("TTS engine", tts.getDefaultEngine());
        for (TextToSpeech.EngineInfo e : tts.getEngines()) r.info("engine: " + e.name + " (" + e.label + ")");
        Locale th = new Locale("th", "TH");
        r.info("Thai available: " + describe(tts.isLanguageAvailable(th)));
        r.info("English available: " + describe(tts.isLanguageAvailable(Locale.US)));
        try {
            Set<Voice> voices = tts.getVoices();
            int n = 0;
            if (voices != null) for (Voice v : voices) {
                if (v.getLocale().getLanguage().equals("th") || v.getLocale().getLanguage().equals("en")) {
                    if (n++ < 30) r.info("voice: " + v.getName() + " " + v.getLocale());
                }
            }
        } catch (Throwable ignored) {}
        tts.setLanguage(Locale.US);
        tts.speak("Speed limit test. You are over your set limit.", TextToSpeech.QUEUE_ADD, null, "en");
        Thread.sleep(4000);
        int thRes = tts.setLanguage(th);
        r.info("setLanguage(Thai) -> " + describe(thRes));
        tts.speak("ทดสอบเสียง ความเร็วเกินที่ตั้งไว้", TextToSpeech.QUEUE_ADD, null, "th");
        Thread.sleep(5000);
        r.info("Did you hear English, then Thai, through the car speakers?");
        tts.shutdown();
    }

    private static String describe(int lang) {
        switch (lang) {
            case TextToSpeech.LANG_COUNTRY_AVAILABLE:
            case TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE:
            case TextToSpeech.LANG_AVAILABLE: return "yes (" + lang + ")";
            case TextToSpeech.LANG_MISSING_DATA: return "voice data missing";
            case TextToSpeech.LANG_NOT_SUPPORTED: return "not supported";
            default: return String.valueOf(lang);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }
}
