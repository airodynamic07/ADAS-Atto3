package com.airodynamic.adasprobe;

import android.content.Context;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * The shell helper. Runs as the "shell" user, started through ADB:
 *
 *   CLASSPATH=<path to our apk> app_process /system/bin com.airodynamic.adasprobe.ShellMain <task>
 *
 * Tasks: read | camera | cameras-all | watch | fan | temp | recirc | airdir | defrost | purify | window | sunroof
 * Writes Download/atto3_probe/helper_<task>.txt and prints progress.
 */
public final class ShellMain {

    public static final String PKG = "com.airodynamic.adasprobe";

    public static void main(final String[] args) {
        final String task = args.length > 0 ? args[0] : "read";
        Report.DIR = new java.io.File(args.length > 1 ? args[1] : "/data/local/tmp/atto3_probe");
        try { Looper.prepareMainLooper(); } catch (Throwable ignored) {}

        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                int code = 0;
                Report r = new Report("helper_" + task, null);
                try {
                    r.info("running as uid " + android.os.Process.myUid() + " (shell is 2000)");
                    if (task.startsWith("camera")) CameraProbe.loadNativeLibs(r);
                    Context ctx = createContext(r);
                    runTask(task, ctx, r);
                } catch (Throwable t) {
                    r.line("HELPER CRASHED: " + Report.stack(t));
                    code = 1;
                }
                java.io.File f = r.save();
                System.out.println("REPORT_SAVED " + (f == null ? "none" : f.getAbsolutePath()));
                System.out.flush();
                System.exit(code);
            }
        }, "helper-main");
        worker.start();
        Looper.loop();
    }

    static void runTask(String task, Context ctx, Report r) {
        CarProbe car = new CarProbe(ctx, r);
        switch (task) {
            case "read": {
                Report methods = new Report("helper_car_methods", null);
                car.dumpAll(methods);
                methods.save();
                car.listUnknowns();
                Report ids = new Report("helper_feature_ids", null);
                CarProbe.dumpFeatureIds(ids);
                ids.save();
                CameraProbe cp = new CameraProbe(r);
                cp.listCameras();
                break;
            }
            case "camera": {
                CameraProbe cp = new CameraProbe(r);
                Map<String, Integer> cams = cp.listCameras();
                Integer pano = CameraProbe.panoId(cams);
                if (pano == null) {
                    r.fail("camera", "no pano_h / pano_l camera in the list — not opening anything");
                } else {
                    cp.capture(pano, 5120, 960, "shell_pano_id" + pano);
                }
                break;
            }
            case "cameras-all": {
                CameraProbe cp = new CameraProbe(r);
                Map<String, Integer> cams = cp.listCameras();
                Integer pano = CameraProbe.panoId(cams);
                java.util.Set<Integer> done = new java.util.HashSet<>();
                if (pano != null) done.add(pano);
                for (Map.Entry<String, Integer> e : cams.entrySet()) {
                    int id = e.getValue();
                    if (done.contains(id)) continue;
                    done.add(id);
                    if (!car.isParked()) {
                        r.fail("other cameras", "stopped — car is not in P");
                        break;
                    }
                    cp.capture(id, 1280, 720, "shell_" + e.getKey() + "_id" + id);
                }
                if (done.size() <= (pano == null ? 0 : 1)) r.info("No other cameras in the list.");
                break;
            }
            case "watch":
                r.info("Starting in 5 s: flick LEFT indicator, then RIGHT, open and close a door, press the brake.");
                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                car.watch(30);
                break;
            case "fan": car.testFan(); break;
            case "temp": car.testTemperature(); break;
            case "recirc": car.testRecirc(); break;
            case "airdir": car.testAirDirection(); break;
            case "defrost": car.testDefrost(); break;
            case "purify": car.testPurify(); break;
            case "window": car.testWindow(); break;
            case "sunroof": car.testSunroof(); break;
            default: r.fail("task", "unknown task '" + task + "'");
        }
    }

    /** A Context good enough for the BYD car classes (approach from OverDrive, MIT). */
    static Context createContext(Report r) {
        try {
            final Class<?> at = Class.forName("android.app.ActivityThread");
            final Object[] holder = new Object[1];
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    try { holder[0] = at.getMethod("systemMain").invoke(null); }
                    catch (Throwable e) { r.info("systemMain failed: " + e); }
                }
            }, "systemMain");
            t.setDaemon(true);
            t.start();
            t.join(10000);
            Object thread = holder[0];
            if (thread == null) {
                thread = at.getMethod("currentActivityThread").invoke(null);
            }
            if (thread != null) {
                Method gsc = at.getMethod("getSystemContext");
                Context sys = (Context) gsc.invoke(thread);
                try {
                    Context app = sys.createPackageContext(PKG,
                            Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
                    r.ok("context", "package context");
                    return new BypassContext(app);
                } catch (Throwable e) {
                    r.info("package context failed: " + e);
                    r.ok("context", "system context");
                    return new BypassContext(sys);
                }
            }
        } catch (Throwable e) {
            r.info("context creation failed: " + e);
        }
        r.fail("context", "using empty fallback context");
        return new BypassContext(null);
    }
}
