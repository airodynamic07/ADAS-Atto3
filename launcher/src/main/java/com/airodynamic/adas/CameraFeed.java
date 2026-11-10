package com.airodynamic.adas;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Surface;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * Live 360 camera (hidden AVMCamera class, loaded from /system/framework/bmmcamera.jar).
 * On this car the four cameras arrive as one 5120x960 strip on camera id 1 (pano_h):
 * slice 0 rear, 1 left, 2 right, 3 front. Each slice (or the chosen part of it) is scaled to 640x480.
 */
public final class CameraFeed {

    public static final int REAR = 0, LEFT = 1, RIGHT = 2, FRONT = 3;
    public static final int W = 640, H = 480;
    private static final int STRIP_W = 5120, STRIP_H = 960;
    private static final String BMM_JAR = "/system/framework/bmmcamera.jar";

    public interface Listener { void onFrame(Bitmap b); void onError(String msg); }
    /** Gets every converted frame on the camera thread (used by the AI, which copies it). */
    public interface Sink { void onArgb(int[] argb, int w, int h, int slice, float[] crop); }
    /** Part of each camera's picture to show, as fractions {x0, y0, x1, y1}; whole picture by default. */
    private final float[][] crops = {{0, 0, 1, 1}, {0, 0, 1, 1}, {0, 0, 1, 1}, {0, 0, 1, 1}};
    public void setCrop(int s, float x0, float y0, float x1, float y1) { crops[s] = new float[]{x0, y0, x1, y1}; }
    public float[] crop(int s) { return crops[s]; }
    private volatile Sink sink;
    public void setSink(Sink s) { sink = s; }
    public int slice() { return slice; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler h;
    private ImageReader reader;
    private Object camera;
    private volatile int slice = FRONT;
    private volatile Listener listener;
    private final int[] argb = new int[W * H];
    private final Bitmap[] bmps = {Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888), Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)};
    private int idx;
    private byte[] yRow, uRow, vRow;
    private static ClassLoader loader;

    public void setSlice(int s) { slice = s; }
    public boolean running() { return camera != null; }

    public synchronized void start(Listener l) {
        listener = l;
        if (thread != null) return;
        thread = new HandlerThread("camera-feed");
        thread.start();
        h = new Handler(thread.getLooper());
        h.post(this::open);
    }

    public synchronized void stop() {
        listener = null;
        final Object cam = camera;
        final ImageReader r = reader;
        camera = null;
        reader = null;
        if (thread != null) { thread.quitSafely(); thread = null; }
        Thread t = new Thread(() -> {
            if (cam != null) for (String m : new String[]{"stopPreview", "close"}) {
                try { Method mm = cam.getClass().getDeclaredMethod(m); mm.setAccessible(true); mm.invoke(cam); } catch (Throwable ignored) {}
            }
            if (r != null) r.close();
        }, "camera-close");
        t.setDaemon(true);
        t.start();
    }

    private static synchronized Class<?> hidden(String name) throws ClassNotFoundException {
        try { return Class.forName(name); } catch (ClassNotFoundException e) {
            if (loader == null) loader = new dalvik.system.PathClassLoader(BMM_JAR,
                    "/system/lib64:/system/lib:/vendor/lib64:/vendor/lib", CameraFeed.class.getClassLoader());
            return Class.forName(name, true, loader);
        }
    }

    private int panoId() {
        try {
            Class<?> bmm = hidden("android.hardware.BmmCameraInfo");
            Method m = bmm.getDeclaredMethod("getCameraId", String.class);
            m.setAccessible(true);
            for (String tag : new String[]{"pano_h", "pano_l"}) {
                Object v = m.invoke(null, tag);
                if (v instanceof Integer && (Integer) v >= 0) return (Integer) v;
            }
        } catch (Throwable ignored) {}
        return 1;   // confirmed on this car
    }

    private void open() {
        try {
            if (!new File(BMM_JAR).isFile()) throw new IllegalStateException("no 360 camera on this unit");
            reader = ImageReader.newInstance(STRIP_W, STRIP_H, ImageFormat.YUV_420_888, 3);
            reader.setOnImageAvailableListener(this::onImage, h);
            Class<?> avm = hidden("android.hardware.AVMCamera");
            Constructor<?> c = avm.getDeclaredConstructor(int.class);
            c.setAccessible(true);
            Object cam = c.newInstance(panoId());
            Method open = avm.getDeclaredMethod("open");
            open.setAccessible(true);
            if (!Boolean.TRUE.equals(open.invoke(cam))) throw new IllegalStateException("camera busy (OverDrive or the 360 view?)");
            Method add = avm.getDeclaredMethod("addPreviewSurface", Surface.class, int.class);
            add.setAccessible(true);
            add.invoke(cam, reader.getSurface(), 0);
            Method start = avm.getDeclaredMethod("startPreview");
            start.setAccessible(true);
            start.invoke(cam);
            camera = cam;
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            final String msg = c.getMessage() != null ? c.getMessage() : c.getClass().getSimpleName();
            final Listener l = listener;
            if (l != null) main.post(() -> l.onError(msg));
        }
    }

    private void onImage(ImageReader rd) {
        Image img = null;
        try {
            img = rd.acquireLatestImage();
            if (img == null || listener == null) return;
            final int s = slice;
            final float[] cr = crops[s];
            toArgb(img, s, cr);
            img.close();
            img = null;
            Sink k = sink;
            if (k != null) k.onArgb(argb, W, H, s, cr);
            final Bitmap b = bmps[idx];
            idx ^= 1;
            b.setPixels(argb, 0, W, 0, 0, W, H);
            final Listener l = listener;
            if (l != null) main.post(() -> l.onFrame(b));
        } catch (Throwable ignored) {
        } finally {
            if (img != null) img.close();
        }
    }

    private int[] sx = new int[W];

    /** Converts the chosen part of one camera (full resolution) to a W x H picture. */
    private void toArgb(Image img, int slice, float[] cr) {
        final int SW = STRIP_W / 4;
        Image.Plane[] p = img.getPlanes();
        ByteBuffer yb = p[0].getBuffer(), ub = p[1].getBuffer(), vb = p[2].getBuffer();
        int ys = p[0].getRowStride(), ypx = p[0].getPixelStride();
        int uvs = p[1].getRowStride(), uvp = p[1].getPixelStride();
        int x0 = (slice * SW + Math.round(cr[0] * SW)) & ~1;
        int y0 = Math.round(cr[1] * STRIP_H);
        float cw = (cr[2] - cr[0]) * SW, ch = (cr[3] - cr[1]) * STRIP_H;
        int span = Math.min((int) Math.ceil(cw) + 2, slice * SW + SW - x0);
        for (int ox = 0; ox < W; ox++) sx[ox] = Math.min(span - 1, (int) (ox * cw / W));
        int segY = (span - 1) * ypx + 1, segUV = (span / 2) * uvp + 1;
        if (yRow == null || yRow.length < segY) yRow = new byte[segY];
        if (uRow == null || uRow.length < segUV) { uRow = new byte[segUV]; vRow = new byte[segUV]; }
        for (int oy = 0; oy < H; oy++) {
            int sy = Math.min(STRIP_H - 1, y0 + (int) (oy * ch / H));
            yb.position(sy * ys + x0 * ypx);
            yb.get(yRow, 0, Math.min(segY, yb.remaining()));
            int uvOff = (sy / 2) * uvs + (x0 / 2) * uvp;
            ub.position(uvOff); ub.get(uRow, 0, Math.min(segUV, ub.remaining()));
            vb.position(uvOff); vb.get(vRow, 0, Math.min(segUV, vb.remaining()));
            int o = oy * W;
            for (int ox = 0; ox < W; ox++) {
                int k = sx[ox];
                int Y = (yRow[k * ypx] & 0xFF) - 16;
                int U = (uRow[(k / 2) * uvp] & 0xFF) - 128;
                int V = (vRow[(k / 2) * uvp] & 0xFF) - 128;
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
}
