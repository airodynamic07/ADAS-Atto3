package com.airodynamic.adasprobe;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Phase 1: one camera from the 360 strip + YOLO boxes + speed numbers.
 * Warnings only: this screen never sends anything to the car.
 */
public class AdasCameraActivity extends Activity {

    private static final int BG = Color.parseColor("#05080F");
    private static final int ACCENT = Color.parseColor("#4DA3FF");
    private static final int AMBER = Color.parseColor("#FFB020");
    private static final int PURPLE = Color.parseColor("#B8A4FF");
    private static final int TEAL = Color.parseColor("#3DD9C1");

    /** Slices in the 5120-wide strip (confirmed on this car): 0 rear, 1 left, 2 right, 3 front. */
    private static final String[] VIEW_NAMES = {"Rear", "Left", "Right", "Front"};
    private static final int STRIP_W = 5120, STRIP_H = 960, OUT_W = 640, OUT_H = 480;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread camThread, aiThread;
    private Handler camH, aiH;
    private ImageReader reader;
    private volatile Object camera;
    private volatile Detector detector;
    private volatile boolean aiBusy, running;
    private volatile int view = 3;              // front
    private volatile boolean wantGpu = true;

    private ImageView image;
    private TextView stats, title;
    private final Bitmap[] bmps = {
            Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888),
            Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888)};
    private int bmpIdx;
    private volatile Bitmap lastShown;

    // numbers
    private long camFrames, shownFrames, aiCount;
    private double aiMsSum, convMsSum;
    private long statT0 = System.currentTimeMillis();
    private String camFps = "-", shownFps = "-", aiMs = "-", convMs = "-";
    private int lastObjects;
    private Report log;
    private long lastLogSave;

    /** Live picture (camera thread) and the copy the AI is working on (AI thread). */
    private final int[] argb = new int[OUT_W * OUT_H];
    private final int[] argbAi = new int[OUT_W * OUT_H];
    /** Latest AI result and when its picture was taken. Boxes older than MAX_BOX_AGE_MS are hidden. */
    private volatile List<Detector.Det> lastDets = java.util.Collections.emptyList();
    private volatile long lastDetsAt;
    private static final long MAX_BOX_AGE_MS = 3000;
    private byte[] yRow, uvRowU, uvRowV;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        log = new Report("phase1", null);
        log.info("WebView: " + webViewInfo());

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(image, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(14), dp(10), dp(14), dp(10));
        top.setBackgroundColor(0x99000000);
        title = tv("Front camera + AI", 20, ACCENT, true);
        stats = tv("Starting camera...", 15, Color.WHITE, false);
        stats.setTypeface(Typeface.MONOSPACE);
        top.addView(title);
        top.addView(stats);
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(8), dp(6), dp(8), dp(6));
        bar.setBackgroundColor(0x99000000);
        for (int i : new int[]{3, 0, 1, 2}) {
            final int v = i;
            bar.addView(btn(VIEW_NAMES[i], x -> { view = v; updateTitle(); }));
        }
        bar.addView(btn("GPU / CPU", x -> switchDelegate()));
        bar.addView(btn("Save picture", x -> savePicture()));
        bar.addView(btn("Back", x -> finish()));
        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);
        updateTitle();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView tv(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button btn(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void updateTitle() {
        title.setText(VIEW_NAMES[view] + " camera + AI   (Phase 1 test — warnings only)");
    }

    private String webViewInfo() {
        try {
            android.content.pm.PackageInfo p = android.webkit.WebView.getCurrentWebViewPackage();
            return p == null ? "none" : p.packageName + " " + p.versionName;
        } catch (Throwable t) {
            return "unknown (" + t.getClass().getSimpleName() + ")";
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onResume() {
        super.onResume();
        running = true;
        camThread = new HandlerThread("p1-cam"); camThread.start(); camH = new Handler(camThread.getLooper());
        aiThread = new HandlerThread("p1-ai"); aiThread.start(); aiH = new Handler(aiThread.getLooper());
        aiH.post(this::makeDetector);
        camH.post(this::openCamera);
    }

    @Override protected void onPause() {
        super.onPause();
        running = false;
        final Object cam = camera;
        camera = null;
        final ImageReader r = reader;
        reader = null;
        Thread closer = new Thread(() -> {
            if (cam != null) for (String m : new String[]{"stopPreview", "close"}) {
                try { Method mm = cam.getClass().getDeclaredMethod(m); mm.setAccessible(true); mm.invoke(cam); }
                catch (Throwable ignored) {}
            }
            if (r != null) r.close();
        }, "p1-close");
        closer.setDaemon(true);
        closer.start();
        if (aiH != null) aiH.post(() -> { if (detector != null) { detector.close(); detector = null; } });
        if (aiThread != null) aiThread.quitSafely();
        if (camThread != null) camThread.quitSafely();
        log.save();
    }

    private void makeDetector() {
        try {
            if (detector != null) detector.close();
            long t = System.currentTimeMillis();
            detector = new Detector(this, wantGpu);
            log.info("detector " + detector.mode() + " ready in " + (System.currentTimeMillis() - t) + " ms");
        } catch (Throwable t) {
            detector = null;
            log.error("detector", t);
            ui.post(() -> stats.setText("AI model failed to load: " + t));
        }
    }

    private void switchDelegate() {
        wantGpu = !wantGpu;
        aiCount = 0; aiMsSum = 0; convMsSum = 0;
        if (aiH != null) aiH.post(this::makeDetector);
    }

    // ------------------------------------------------------------------ camera

    private void openCamera() {
        Report r = new Report("phase1_camera", null);
        try {
            Map<String, Integer> cams = new CameraProbe(r).listCameras();
            Integer id = CameraProbe.panoId(cams);
            if (id == null) id = 1;
            reader = ImageReader.newInstance(STRIP_W, STRIP_H, ImageFormat.YUV_420_888, 3);
            reader.setOnImageAvailableListener(this::onFrame, camH);
            Surface surface = reader.getSurface();
            Class<?> avm = CameraProbe.hidden("android.hardware.AVMCamera", r);
            Constructor<?> ctor = avm.getDeclaredConstructor(int.class);
            ctor.setAccessible(true);
            Object cam = ctor.newInstance(id);
            Method open = avm.getDeclaredMethod("open");
            open.setAccessible(true);
            if (!Boolean.TRUE.equals(open.invoke(cam))) throw new RuntimeException("AVMCamera.open() refused (is OverDrive or the 360 view using it?)");
            Method add = avm.getDeclaredMethod("addPreviewSurface", Surface.class, int.class);
            add.setAccessible(true);
            add.invoke(cam, surface, 0);
            Method start = avm.getDeclaredMethod("startPreview");
            start.setAccessible(true);
            start.invoke(cam);
            camera = cam;
            log.info("camera id " + id + " started");
        } catch (Throwable t) {
            log.error("camera", t);
            ui.post(() -> stats.setText("Camera failed: " + t + "\nClose OverDrive's live view and the 360 view, then try again."));
        }
        r.save();
    }

    /** Camera thread: cut the chosen slice out of the strip at half size, then hand it to the AI thread. */
    private void onFrame(ImageReader rd) {
        Image img = null;
        try {
            img = rd.acquireLatestImage();
            if (img == null) return;
            camFrames++;
            if (!running) return;
            long t0 = System.nanoTime();
            toArgb(img, view * (STRIP_W / 4));
            final double conv = (System.nanoTime() - t0) / 1e6;
            final long frameAt = System.currentTimeMillis();
            img.close();
            img = null;
            // 1) Show this picture right away with the newest boxes we have (may be 1-2 s old).
            show(conv);
            // 2) If the AI is free, give it a copy of this picture.
            if (!aiBusy && detector != null) {
                System.arraycopy(argb, 0, argbAi, 0, argb.length);
                aiBusy = true;
                aiH.post(() -> process(frameAt));
            }
        } catch (Throwable t) {
            log.error("frame", t);
        } finally {
            if (img != null) img.close();
        }
    }

    /** YUV_420_888 slice starting at x0 (full res 1280x960) -> 640x480 ARGB (every 2nd pixel). */
    private void toArgb(Image img, int x0) {
        Image.Plane[] p = img.getPlanes();
        ByteBuffer yb = p[0].getBuffer(), ub = p[1].getBuffer(), vb = p[2].getBuffer();
        int ys = p[0].getRowStride(), ypx = p[0].getPixelStride();
        int uvs = p[1].getRowStride(), uvp = p[1].getPixelStride();
        int segY = (OUT_W * 2 - 1) * ypx + 1, segUV = (OUT_W - 1) * uvp + 1;
        if (yRow == null || yRow.length < segY) yRow = new byte[segY];
        if (uvRowU == null || uvRowU.length < segUV) { uvRowU = new byte[segUV]; uvRowV = new byte[segUV]; }
        for (int oy = 0; oy < OUT_H; oy++) {
            int sy = oy * 2;
            yb.position(sy * ys + x0 * ypx);
            yb.get(yRow, 0, Math.min(segY, yb.remaining()));
            int uvOff = (sy / 2) * uvs + (x0 / 2) * uvp;
            ub.position(uvOff); ub.get(uvRowU, 0, Math.min(segUV, ub.remaining()));
            vb.position(uvOff); vb.get(uvRowV, 0, Math.min(segUV, vb.remaining()));
            int o = oy * OUT_W;
            for (int ox = 0; ox < OUT_W; ox++) {
                int Y = (yRow[ox * 2 * ypx] & 0xFF) - 16;
                int U = (uvRowU[ox * uvp] & 0xFF) - 128;
                int V = (uvRowV[ox * uvp] & 0xFF) - 128;
                if (Y < 0) Y = 0;
                int c = 1192 * Y;
                int r = (c + 1634 * V) >> 10, g = (c - 833 * V - 400 * U) >> 10, b = (c + 2066 * U) >> 10;
                r = r < 0 ? 0 : (r > 255 ? 255 : r);
                g = g < 0 ? 0 : (g > 255 ? 255 : g);
                b = b < 0 ? 0 : (b > 255 ? 255 : b);
                argb[o + ox] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
    }

    // ------------------------------------------------------------------ AI + drawing

    private final Paint box = new Paint(), label = new Paint(), labelBg = new Paint();
    {
        box.setStyle(Paint.Style.STROKE);
        box.setStrokeWidth(3f);
        label.setColor(Color.BLACK);
        label.setTextSize(18f);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setAntiAlias(true);
    }

    private static int colorFor(int cls) {
        if (cls == Detector.PERSON) return PURPLE;
        if (cls == Detector.MOTORCYCLE || cls == Detector.BICYCLE) return AMBER;
        return TEAL;
    }

    /** AI thread: find objects in the copied picture; the camera thread draws them. */
    private void process(long frameAt) {
        try {
            Detector d = detector;
            if (d == null) return;
            long t0 = System.nanoTime();
            List<Detector.Det> dets = d.run(argbAi, OUT_W, OUT_H, 0.35f);
            double ms = (System.nanoTime() - t0) / 1e6;
            aiCount++;
            aiMsSum += ms;
            lastDets = dets;
            lastDetsAt = frameAt;
            lastObjects = dets.size();
        } catch (Throwable t) {
            log.error("process", t);
        } finally {
            aiBusy = false;
        }
    }

    /** Camera thread: live picture + the newest boxes, labelled with their age. */
    private void show(double conv) {
        Bitmap bmp = bmps[bmpIdx];
        bmpIdx ^= 1;
        bmp.setPixels(argb, 0, OUT_W, 0, 0, OUT_W, OUT_H);
        long age = System.currentTimeMillis() - lastDetsAt;
        boolean fresh = lastDetsAt > 0 && age <= MAX_BOX_AGE_MS;
        if (fresh) {
            Canvas c = new Canvas(bmp);
            for (Detector.Det det : lastDets) {
                int col = colorFor(det.cls);
                box.setColor(col);
                c.drawRect(det.x1, det.y1, det.x2, det.y2, box);
                String s = Detector.name(det.cls) + " " + Math.round(det.score * 100) + "%";
                float w = label.measureText(s) + 8;
                labelBg.setColor(col);
                float ty = Math.max(det.y1, 22);
                c.drawRect(det.x1, ty - 22, det.x1 + w, ty, labelBg);
                c.drawText(s, det.x1 + 4, ty - 5, label);
            }
        }
        convMsSum += conv;
        shownFrames++;
        lastShown = bmp;
        Detector d = detector;
        final String mode = d == null ? "loading" : d.mode();
        updateNumbers(mode);
        final String ageTxt = lastDetsAt == 0 ? "-" : String.format(Locale.US, "%.1f s ago%s", age / 1000.0, fresh ? "" : " (hidden)");
        final String text = String.format(Locale.US, "Camera %s fps   Screen %s fps   AI %s ms (%s)   Boxes %s   Objects %d",
                camFps, shownFps, aiMs, mode, ageTxt, fresh ? lastObjects : 0);
        ui.post(() -> { image.setImageBitmap(bmp); stats.setText(text); });
    }

    private void updateNumbers(String mode) {
        long now = System.currentTimeMillis();
        long dt = now - statT0;
        if (dt < 2000) return;
        camFps = String.format(Locale.US, "%.1f", camFrames * 1000.0 / dt);
        shownFps = String.format(Locale.US, "%.1f", shownFrames * 1000.0 / dt);
        if (aiCount > 0) aiMs = String.format(Locale.US, "%.0f", aiMsSum / aiCount);
        if (shownFrames > 0) convMs = String.format(Locale.US, "%.0f", convMsSum / shownFrames);
        camFrames = 0; shownFrames = 0; aiCount = 0; aiMsSum = 0; convMsSum = 0;
        statT0 = now;
        if (now - lastLogSave > 10000) {
            log.info(Report.now() + "  " + VIEW_NAMES[view] + "  camera " + camFps + " fps, AI " + aiMs
                    + " ms (" + mode + "), screen " + shownFps + " fps, convert " + convMs + " ms");
            log.save();
            lastLogSave = now;
        }
    }

    private void savePicture() {
        final Bitmap b = lastShown;
        if (b == null) return;
        new Thread(() -> {
            try {
                Report.DIR.mkdirs();
                File f = new File(Report.DIR, "phase1_" + VIEW_NAMES[view].toLowerCase(Locale.US) + "_"
                        + System.currentTimeMillis() / 1000 + ".jpg");
                try (FileOutputStream o = new FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.JPEG, 90, o); }
                log.info("saved picture " + f.getName());
                log.save();
                ui.post(() -> stats.setText("Saved " + f.getName()));
            } catch (Throwable t) {
                log.error("save", t);
            }
        }).start();
    }
}
