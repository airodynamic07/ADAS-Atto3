package com.airodynamic.adasprobe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Collects report lines, shows them live (optional listener) and saves them
 * to Download/atto3_probe/ on the car.
 */
public final class Report {

    public interface Listener { void onLine(String line); }

    public static final File DIR = new File("/sdcard/Download/atto3_probe");

    private final String name;
    private final StringBuilder sb = new StringBuilder();
    private final Listener listener;

    public Report(String name, Listener listener) {
        this.name = name;
        this.listener = listener;
        line("=== " + name + " — " + now() + " ===");
    }

    public static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    public synchronized void line(String s) {
        sb.append(s).append('\n');
        if (listener != null) listener.onLine(s);
        // Shell helper: also print so the Termux user sees progress.
        if (listener == null) System.out.println(s);
    }

    public void section(String title) {
        line("");
        line("## " + title);
    }

    public void ok(String what, Object value) { line("  [OK]   " + what + " = " + value); }
    public void fail(String what, Object why) { line("  [FAIL] " + what + " — " + why); }
    public void info(String s) { line("  " + s); }

    public void error(String what, Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c != c.getCause()) c = c.getCause();
        fail(what, c.getClass().getSimpleName() + ": " + c.getMessage());
    }

    public static String stack(Throwable t) {
        StringWriter w = new StringWriter();
        t.printStackTrace(new PrintWriter(w));
        return w.toString();
    }

    public synchronized String text() { return sb.toString(); }

    /** Saves to Download/atto3_probe/<name>.txt. Returns the file, or null on failure. */
    public synchronized File save() {
        try {
            DIR.mkdirs();
            File f = new File(DIR, name + ".txt");
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(f), StandardCharsets.UTF_8)) {
                w.write(sb.toString());
            }
            f.setReadable(true, false);
            return f;
        } catch (Throwable t) {
            if (listener != null) listener.onLine("Could not save report: " + t);
            return null;
        }
    }
}
