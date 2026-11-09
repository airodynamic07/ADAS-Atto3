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
 * Every test saves a text file in the app's own folder; a button copies them to a USB stick.
 */
public class ProbeActivity extends Activity {

    private static final int BG = Color.parseColor("#05080F");
    private static final int ACCENT = Color.parseColor("#4DA3FF");
    private static final int TEAL = Color.parseColor("#3DD9C1");
    private static final int AMBER = Color.parseColor("#FFB020");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newCachedThreadPool();
    private java.util.concurrent.Future<?> current;
    private TextView log;
    private ScrollView logScroll;
    private TextView status;
    private Button modeButton;
    private boolean carTestsViaHelper = false;  // Phase 0 run 1: car data works from the app
    private volatile boolean busy;
    private volatile int generation;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        File ext = getExternalFilesDir(null);
        Report.DIR = new File(ext != null ? ext : getFilesDir(), "atto3_probe");
        Report.DIR.mkdirs();
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
        Button all = button(left, "▶  RUN EVERYTHING  (about 10 min)", v -> runTask("answers", this::runAll));
        all.setTextColor(ACCENT);
        all.setTypeface(Typeface.DEFAULT_BOLD);
        Button r2 = button(left, "▶  ROUND 2  (about 5 min)", v -> runTask("answers2", this::round2));
        r2.setTextColor(ACCENT);
        r2.setTypeface(Typeface.DEFAULT_BOLD);
        Button r2b = button(left, "▶  ROUND 2 — after restart", v -> runTask("answers3", this::round2AfterRestart));
        r2b.setTextColor(ACCENT);
        button(left, "⟳  Update app from internet", v -> runTask("app_update", rep -> Updater.update(this, rep)));
        Button stop = button(left, "■  Stop current test", v -> stopTask());
        stop.setTextColor(AMBER);

        header(left, "READ-ONLY  (safe any time)");
        button(left, "1  Basic check (app)", v -> runTask("app_basic", this::basicCheck));
        button(left, "2  Car data via helper (shell)", v -> helper("read", 240));
        button(left, "3  Camera picture via helper", v -> helper("camera", 120));
        button(left, "4  Camera picture from app (no helper)", v -> runTask("app_camera", this::appCamera));
        button(left, "5  Watch signals 30 s  (flick indicators!)", v -> watch());
        button(left, "6  Voice test (Thai + English)", v -> runTask("app_voice", this::voiceTest));
        button(left, "7  Open GBox in left window", v -> {
            java.util.List<String> g = SystemProbe.findGbox(this);
            if (g.isEmpty()) append("GBox not found."); else openWindow(g.get(0), 0);
        });
        button(left, "8  Open Engine Sound in middle window", v -> openWindow("com.air.atto3sound", 1));
        button(left, "8b Open YouTube in middle window", v -> openWindow("com.google.android.youtube", 1));
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
        control(left, "Ambient light colour + brightness, then back", "ambient");
        control(left, "Sunroof small nudge (watch it!)", "sunroof");
        Button others = button(left, "Other cameras (may restart camera service)", v ->
                confirm("Other cameras", "Opens any extra cameras in the car's list (e.g. windshield). "
                        + "On some cars this briefly restarts the camera service and the 360 view. Car must be in P.",
                        () -> helper("cameras-all", 180)));
        others.setTextColor(AMBER);

        header(left, "REPORT");
        button(left, "Copy all results to USB stick", v -> runTask("app_usb_copy", this::copyToUsb));
        button(left, "Show where reports are saved", v -> append("\nReports folder: " + Report.DIR
                + "\nFiles: " + listReports()
                + "\nTo take them home: plug in a USB stick and press 'Copy all results to USB stick'.\n"));
        TextView hint = text("If button 2 says port 5555 is CLOSED, wireless ADB is off. "
                + "Car data still works from the app (button 1, control tests in APP mode).", 11, Color.LTGRAY, false);
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
        append("Welcome. Start with button 1, then 2 and 3. Every result is saved in the app's folder;\n"
                + "press 'Copy all results to USB stick' at the end.\n"
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
        setStatus("Running: " + name + " ...  (Stop button cancels)");
        final int gen = ++generation;
        current = worker.submit(() -> {
            Report r = new Report(name, this::append);
            try {
                task.run(r);
            } catch (Throwable t) {
                r.line("ERROR: " + Report.stack(t));
            }
            File f = r.save();
            append("Saved: " + (f == null ? "(failed)" : f.getAbsolutePath()) + "\n");
            if (gen == generation) {
                setStatus("Done: " + name);
                busy = false;
            }
        });
    }

    // ------------------------------------------------------------------ Run everything

    /** Shows a question on screen and waits for the answer (called from the worker thread). */
    private String ask(final String title, final String msg, final String... options) throws InterruptedException {
        final String[] res = {null};
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        ui.post(() -> {
            AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setCancelable(false);
            b.setPositiveButton(options[0], (d, w) -> { res[0] = options[0]; done.countDown(); });
            if (options.length > 1) b.setNegativeButton(options[1], (d, w) -> { res[0] = options[1]; done.countDown(); });
            if (options.length > 2) b.setNeutralButton(options[2], (d, w) -> { res[0] = options[2]; done.countDown(); });
            AlertDialog dlg = b.create();
            dlg.show();
            for (int id : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
                Button btn = dlg.getButton(id);
                if (btn != null) { btn.setTextSize(18); btn.setAllCaps(false); }
            }
            TextView m = dlg.findViewById(android.R.id.message);
            if (m != null) m.setTextSize(18);
        });
        done.await();
        return res[0];
    }

    /** Runs one test into its own report file (same names as the single buttons). */
    private void step(String name, Task t) {
        setStatus("Run everything: " + name + " ...");
        Report rep = new Report(name, this::append);
        try {
            t.run(rep);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Throwable e) {
            rep.line("ERROR: " + Report.stack(e));
        }
        rep.save();
    }

    private void bringBack() {
        try {
            startActivity(new android.content.Intent(this, ProbeActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            | android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {}
    }

    private void runAll(Report answers) throws Exception {
        String go = ask("Run everything",
                "Before you start:\n\n• Car ON and in P\n• Close OverDrive and the 360 camera view\n"
                        + "• USB stick plugged in\n\nThe app tells you what to do and asks simple questions. "
                        + "About 10 minutes.", "Start", "Cancel");
        if (!"Start".equals(go)) return;
        answers.info("Answers from the driver, " + Report.now());

        step("app_basic", this::basicCheck);
        step("app_camera", this::appCamera);

        step("app_voice", this::voiceTest);
        step("app_beep", this::beep);
        answers.info("Beep heard: " + ask("Sound", "Did you hear three BEEPS from the car speakers?", "Yes", "No"));

        ask("Signals", "When you press OK you have 30 seconds to:\n\n1. Flick the LEFT indicator (3 s)\n"
                + "2. Flick the RIGHT indicator (3 s)\n3. Open and close a door\n4. Press the brake pedal", "OK");
        step("app_watch", rep -> new CarProbe(new BypassContext(getApplicationContext()), rep).watch(30));

        String[][] controls = {
                {"fan", "Fan +1, then back", "Did the FAN level change on the car screen, then go back?"},
                {"temp", "Temperature +1, then back", "Did the TEMPERATURE change, then go back?"},
                {"recirc", "Recirculation on, then off", "Did RECIRCULATION turn on, then off?"},
                {"airdir", "Air direction: tries each, then back", "Did the AIR DIRECTION change several times?"},
                {"defrost", "Front defrost on, then off", "Did FRONT DEFROST turn on, then off?"},
                {"purify", "Air purify on, then off", "Did AIR PURIFY turn on, then off?"},
                {"window", "Driver window 5% down, then up", "Did the DRIVER WINDOW go down a little, then up?"},
                {"ambient", "Ambient light colour + brightness, then back", "Did the AMBIENT LIGHT change colour, then brightness, then go back?"},
                {"sunroof", "Sunroof small nudge", "Did the SUNROOF move? (If it is open now, close it with the car's button.)"},
        };
        for (String[] c : controls) {
            String run = ask("Next: " + c[1], "Watch the car while it runs.", "Run", "Skip");
            if (!"Run".equals(run)) { answers.info(c[0] + ": skipped"); continue; }
            final String task = c[0];
            step("app_" + task, rep -> ShellMain.runTask(task, new BypassContext(getApplicationContext()), rep));
            answers.info(c[0] + ": " + ask(c[1], c[2], "Yes", "No", "Not sure"));
        }

        java.util.List<String[]> apps = new java.util.ArrayList<>();
        for (String g : SystemProbe.findGbox(this)) apps.add(new String[]{g, "GBox", "0"});
        apps.add(new String[]{"com.netflix.mediaclient", "Netflix", "2"});
        for (String[] a : apps) {
            boolean gbox = a[1].equals("GBox");
            ask("Window test: " + a[1], gbox
                    ? "GBox will open for about 25 seconds. Inside GBox, open GOOGLE MAPS and watch whether it "
                      + "stays in the window. Then ADAS Probe comes back (if not, open it yourself)."
                    : "The app will open for about 8 seconds, then ADAS Probe comes back. "
                      + "If it doesn't come back, open ADAS Probe yourself.", "OK");
            String msg = SystemProbe.openInWindow(this, a[0], Integer.parseInt(a[2]));
            answers.info(a[1] + " open: " + msg);
            if (msg.contains("not installed")) { answers.info(a[1] + ": not installed"); continue; }
            Thread.sleep(gbox ? 25000 : 8000);
            bringBack();
            Thread.sleep(1500);
            answers.info(a[1] + " shown as: " + ask(a[1], "How did " + a[1] + " open?", "In a window", "Full screen", "Didn't open"));
            if (gbox) answers.info("Maps inside GBox: " + ask("Google Maps in GBox",
                    "When you opened Google Maps inside GBox, where did it show?", "In the window", "Full screen", "Didn't try"));
        }

        answers.save();
        step("app_usb_copy", this::copyToUsb);
        ask("All done 🎉", "Take the USB stick home, open the folder atto3_probe on it, zip it and send it in the chat.\n\n"
                + "If the copy failed, take a photo of the screen instead.", "OK");
    }

    /** Re-tests only what was unclear in run 1, then switches on app windows (needs a restart). */
    private void round2(Report answers) throws Exception {
        if (!"Start".equals(ask("Round 2", "Car ON and in P. Watch the car during each test and answer the question.\n\n"
                + "At the end the car needs ONE restart.", "Start", "Cancel"))) return;
        String[][] controls = {
                {"fan", "Fan +1, then back", "Did the FAN level change on the car screen, then go back?"},
                {"recirc", "Recirculation on, then off", "Did the RECIRCULATION icon turn on, then off?"},
                {"airdir", "Air direction: 5 changes, then back", "Did the AIR DIRECTION icon change several times?"},
                {"defrost", "Front defrost on, then off", "Did FRONT DEFROST turn on, then off?"},
                {"purify", "Air purify on, then off", "Did AIR PURIFY turn on, then off?"},
                {"ambient", "Ambient light colour, then brightness", "Did the AMBIENT LIGHT change colour, then get dimmer, then go back?"},
                {"window", "Driver window down a little, then up", "Did the DRIVER WINDOW go down a little, then back up?"},
                {"sunroof", "Sunroof small nudge", "Did the SUNROOF (or its sunshade) move at all?"},
        };
        for (String[] c : controls) {
            if (!"Run".equals(ask("Next: " + c[1], "Watch the car while it runs.", "Run", "Skip"))) {
                answers.info(c[0] + ": skipped");
                continue;
            }
            final String task = c[0];
            step("app_" + task, rep -> ShellMain.runTask(task, new BypassContext(getApplicationContext()), rep));
            answers.info(c[0] + ": " + ask(c[1], c[2], "Yes", "No", "Not sure"));
            answers.save();
        }
        step("app_enable_windows", rep -> {
            String out = HelperLauncher.shell(this, "settings put global enable_freeform_support 1; "
                    + "settings put global force_resizable_activities 1; "
                    + "settings get global enable_freeform_support; settings get global force_resizable_activities", rep);
            rep.info("result: " + out);
        });
        answers.save();
        step("app_usb_copy", this::copyToUsb);
        ask("Restart the car screen", "App windows are now switched on, but the screen must restart:\n\n"
                + "Hold the VOLUME DOWN button (or the volume knob) for about 5 seconds until the screen restarts.\n\n"
                + "Then open ADAS Probe and tap  ▶ ROUND 2 — after restart.", "OK");
    }

    private void round2AfterRestart(Report answers) throws Exception {
        android.content.pm.PackageManager pm = getPackageManager();
        answers.info("freeform feature after restart: " + pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)
                + ", enable_freeform_support=" + android.provider.Settings.Global.getString(getContentResolver(), "enable_freeform_support"));
        String[][] apps = {
                {"com.gbox.com.google.android.apps.maps", "Google Maps (GBox)", "0"},
                {"com.gbox.com.google.android.youtube", "YouTube (GBox)", "1"},
                {"com.air.atto3sound", "Engine Sound", "2"},
        };
        for (String[] a : apps) {
            ask("Window test: " + a[1], a[1] + " opens for about 10 seconds. Look: is it in a WINDOW or FULL SCREEN? "
                    + "Then ADAS Probe comes back (if not, open it yourself).", "OK");
            String msg = SystemProbe.openInWindow(this, a[0], Integer.parseInt(a[2]));
            answers.info(a[1] + " open: " + msg);
            if (msg.contains("not installed")) continue;
            Thread.sleep(10000);
            bringBack();
            Thread.sleep(1500);
            answers.info(a[1] + " shown as: " + ask(a[1], "How did " + a[1] + " open?", "In a window", "Full screen", "Didn't open"));
            answers.save();
        }
        step("app_usb_copy", this::copyToUsb);
        ask("All done 🎉", "Take the USB stick home and send the atto3_probe folder in the chat.", "OK");
    }

    private void stopTask() {
        if (!busy) {
            append("(Nothing is running.)");
            return;
        }
        if (current != null) current.cancel(true);
        generation++;
        busy = false;
        setStatus("Stopped. Ready.");
        append("Stopped by you. You can run another test now.\n");
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

    /** Every place DiLink / Android may mount a USB stick. */
    private java.util.List<File> usbCandidates(Report r) {
        java.util.LinkedHashSet<File> out = new java.util.LinkedHashSet<>();
        File[] dirs = getExternalFilesDirs(null);
        for (int i = 1; i < dirs.length; i++) if (dirs[i] != null) out.add(dirs[i]);
        try {
            android.os.storage.StorageManager sm = (android.os.storage.StorageManager) getSystemService(STORAGE_SERVICE);
            for (android.os.storage.StorageVolume v : sm.getStorageVolumes()) {
                String desc = v.getDescription(this);
                String path = null;
                try { path = (String) v.getClass().getMethod("getPath").invoke(v); } catch (Throwable ignored) {}
                r.info("storage volume: " + desc + "  removable=" + v.isRemovable() + "  state=" + v.getState() + "  path=" + path);
                if (path != null && v.isRemovable()) out.add(new File(path));
            }
        } catch (Throwable t) {
            r.info("StorageManager: " + t);
        }
        for (String root : new String[]{"/storage", "/mnt/media_rw", "/mnt/usb", "/mnt/usb_storage", "/mnt/udisk"}) {
            File[] kids = new File(root).listFiles();
            r.info("ls " + root + " -> " + (kids == null ? "(not readable)" : java.util.Arrays.toString(kids)));
            if (kids == null) continue;
            for (File k : kids) {
                String n = k.getName();
                if (n.equals("emulated") || n.equals("self") || n.equals("sdcard0")) continue;
                out.add(k);
            }
        }
        for (String fixed : new String[]{"/storage/udisk", "/storage/usb0", "/storage/usbotg", "/mnt/usb_storage/USB_DISK0"}) {
            if (new File(fixed).isDirectory()) out.add(new File(fixed));
        }
        return new java.util.ArrayList<>(out);
    }

    /** Copies the results folder to the USB stick: first as the app, then via the helper. */
    private void copyToUsb(Report r) throws Exception {
        File[] src = Report.DIR.listFiles();
        if (src == null || src.length == 0) {
            r.fail("copy", "no results yet — run the tests first");
            return;
        }
        java.util.List<File> cands = usbCandidates(r);
        r.info("USB candidates: " + cands);
        int ok = 0;
        for (File root : cands) {
            File dest = new File(root, "atto3_probe");
            try {
                dest.mkdirs();
                int n = 0;
                for (File f : src) if (f.isFile()) { copyFile(f, new File(dest, f.getName())); n++; }
                r.ok("copied " + n + " files (app) to", dest.getAbsolutePath());
                ok++;
            } catch (Throwable t) {
                r.info("app cannot write " + dest + ": " + t.getClass().getSimpleName());
            }
        }
        if (ok > 0) {
            r.info("On the USB stick, look for the folder atto3_probe (on the stick itself, or under Android > data > "
                    + getPackageName() + " > files).");
            return;
        }
        r.info("The app itself may not write to the USB stick — asking the helper (shell user) to copy...");
        StringBuilder cmd = new StringBuilder();
        for (File root : cands) {
            String d = root.getAbsolutePath();
            cmd.append("if [ -d '").append(d).append("' ] && mkdir -p '").append(d).append("/atto3_probe' 2>/dev/null")
               .append(" && cp -r '").append(Report.DIR.getAbsolutePath()).append("/.' '").append(d).append("/atto3_probe/'")
               .append("; then echo COPIED_TO ").append(d).append("/atto3_probe; fi; ");
        }
        // also any mounted vfat/exfat/fuse volume under /mnt/media_rw or /storage
        cmd.append("for d in /mnt/media_rw/* /storage/*; do case \"$d\" in */emulated|*/self) continue;; esac; ")
           .append("mkdir -p \"$d/atto3_probe\" 2>/dev/null && cp -r '").append(Report.DIR.getAbsolutePath())
           .append("/.' \"$d/atto3_probe/\" && echo COPIED_TO $d/atto3_probe; done; mount | grep -iE 'vfat|exfat|fuse|media_rw|usb' | head -20");
        String out = HelperLauncher.shell(this, cmd.toString(), r);
        if (out != null) for (String line : out.split("\n")) r.info("  " + line);
        if (out != null && out.contains("COPIED_TO")) {
            r.ok("copy", "done via helper — look for the folder atto3_probe on the USB stick");
        } else {
            r.fail("copy", "could not write to the USB stick. Take a photo of this screen and send it to me.");
        }
    }

    private static void copyFile(File a, File b) throws Exception {
        try (java.io.FileInputStream in = new java.io.FileInputStream(a);
             java.io.FileOutputStream out = new java.io.FileOutputStream(b)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
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

    /** Three short beeps through the media speakers (the car has no text-to-speech engine). */
    private void beep(Report r) throws Exception {
        int rate = 44100;
        short[] pcm = new short[rate / 4];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short) (Math.sin(2 * Math.PI * 880 * i / rate) * 12000);
        android.media.AudioTrack t = new android.media.AudioTrack(android.media.AudioManager.STREAM_MUSIC, rate,
                android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT,
                pcm.length * 2, android.media.AudioTrack.MODE_STATIC);
        t.write(pcm, 0, pcm.length);
        for (int k = 0; k < 3; k++) {
            t.stop();
            t.reloadStaticData();
            t.play();
            Thread.sleep(500);
        }
        t.release();
        r.ok("beep", "played 3 beeps on the media stream");
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
