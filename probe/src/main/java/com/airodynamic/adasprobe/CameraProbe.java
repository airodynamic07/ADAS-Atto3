package com.airodynamic.adasprobe;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lists the car's cameras and saves one picture, using the hidden
 * android.hardware.AVMCamera class by reflection (same steps as OverDrive:
 * new AVMCamera(id) -> open() -> setCameraFps -> addPreviewSurface -> startPreview).
 *
 * SAFETY: on the Atto 3, opening camera id 1 crashes the car's camera service
 * (found by OverDrive). We only open ids the car's own camera list reports.
 */
public final class CameraProbe {

    public static final String[] TAGS = {"front", "rear", "rvs", "rf", "dms", "face",
            "pano_h", "pano_l", "apa", "byd_apa", "d954_h_m", "d954_h_s", "d954_l_m", "d954_l_s"};

    /** Slice order in the Atto 3 strip, from OverDrive's legacy_seal_atto mapping. */
    public static final String[] SLICE_NAMES = {"1_rear", "2_left", "3_right", "4_front"};

    private final Report r;

    public CameraProbe(Report r) { this.r = r; }

    /** tag -> camera id (only ids >= 0). */
    public Map<String, Integer> listCameras() {
        r.section("Camera list (BmmCameraInfo)");
        Map<String, Integer> out = new LinkedHashMap<>();
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class);
            r.info("vehicle.config.cam_sort = '" + get.invoke(null, "vehicle.config.cam_sort") + "'");
        } catch (Throwable t) {
            r.error("cam_sort", t);
        }
        try {
            Class.forName("android.hardware.AVMCamera");
            r.ok("android.hardware.AVMCamera", "present");
        } catch (Throwable t) {
            r.fail("android.hardware.AVMCamera", "missing — " + t);
        }
        try {
            Class<?> bmm = Class.forName("android.hardware.BmmCameraInfo");
            Method getId = bmm.getDeclaredMethod("getCameraId", String.class);
            getId.setAccessible(true);
            for (String tag : TAGS) {
                try {
                    Object v = getId.invoke(null, tag);
                    int id = v instanceof Integer ? (Integer) v : -1;
                    if (id >= 0) {
                        out.put(tag, id);
                        r.info(String.format("%-10s -> camera id %d   %s", tag, id, previewSize(bmm, id)));
                    } else {
                        r.info(String.format("%-10s -> (none)", tag));
                    }
                } catch (Throwable t) {
                    r.info(tag + " -> error " + t.getClass().getSimpleName());
                }
            }
            for (Method m : bmm.getDeclaredMethods()) r.info("  BmmCameraInfo." + m.getName()
                    + "(" + m.getParameterTypes().length + " args)");
        } catch (Throwable t) {
            r.error("BmmCameraInfo", t);
        }
        Integer pano = panoId(out);
        r.info("360 strip camera id = " + pano);
        boolean windshield = false;
        for (String tag : new String[]{"front", "rf", "d954_h_m", "d954_l_m"}) {
            Integer id = out.get(tag);
            if (id != null && !id.equals(pano)) {
                windshield = true;
                r.info("Possible separate windshield/front camera: tag '" + tag + "' id " + id);
            }
        }
        if (!windshield) r.info("No separate windshield camera found in the camera list.");
        return out;
    }

    private static String previewSize(Class<?> bmm, int id) {
        try {
            Method gw = bmm.getDeclaredMethod("getDefaultPreviewWidth", int.class);
            Method gh = bmm.getDeclaredMethod("getDefaultPreviewHeight", int.class);
            gw.setAccessible(true);
            gh.setAccessible(true);
            return "(" + gw.invoke(null, id) + "x" + gh.invoke(null, id) + ")";
        } catch (Throwable t) {
            return "";
        }
    }

    public static Integer panoId(Map<String, Integer> cams) {
        if (cams.containsKey("pano_h")) return cams.get("pano_h");
        if (cams.containsKey("pano_l")) return cams.get("pano_l");
        return null;
    }

    /**
     * Opens one camera, grabs one frame, saves it as JPEG (and the 4 slices if it
     * is the 5120-wide strip), then closes the camera. Everything has a hard timeout.
     */
    public boolean capture(final int cameraId, int width, int height, final String label) {
        r.section("Capture camera id " + cameraId + " (" + label + ") " + width + "x" + height);
        final HandlerThread ht = new HandlerThread("cam-probe");
        ht.start();
        final Handler h = new Handler(ht.getLooper());
        final ImageReader reader;
        try {
            reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 3);
        } catch (Throwable t) {
            r.error("ImageReader", t);
            ht.quitSafely();
            return false;
        }
        final CountDownLatch got = new CountDownLatch(1);
        final AtomicReference<byte[]> jpeg = new AtomicReference<>();
        final long[] frameCount = {0};
        final long tStart = System.currentTimeMillis();
        final long[] firstFrameMs = {-1};
        reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override public void onImageAvailable(ImageReader rd) {
                Image img = null;
                try {
                    img = rd.acquireLatestImage();
                    if (img == null) return;
                    frameCount[0]++;
                    if (firstFrameMs[0] < 0) firstFrameMs[0] = System.currentTimeMillis() - tStart;
                    // Skip the first few frames (often dark while the camera warms up).
                    if (frameCount[0] == 8 && jpeg.get() == null) {
                        jpeg.set(toJpeg(img));
                        got.countDown();
                    }
                } catch (Throwable t) {
                    r.error("frame", t);
                } finally {
                    if (img != null) img.close();
                }
            }
        }, h);
        final Surface surface = reader.getSurface();
        final AtomicReference<Object> camRef = new AtomicReference<>();
        final AtomicReference<Throwable> openErr = new AtomicReference<>();
        final CountDownLatch opened = new CountDownLatch(1);

        Thread opener = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Class<?> avm = Class.forName("android.hardware.AVMCamera");
                    Constructor<?> ctor = avm.getDeclaredConstructor(int.class);
                    ctor.setAccessible(true);
                    Object cam = ctor.newInstance(cameraId);
                    Method open = avm.getDeclaredMethod("open");
                    open.setAccessible(true);
                    Object ok = open.invoke(cam);
                    if (!(ok instanceof Boolean) || !(Boolean) ok) {
                        throw new RuntimeException("AVMCamera.open() returned " + ok);
                    }
                    camRef.set(cam);
                    try {
                        Method fps = avm.getDeclaredMethod("setCameraFps", int.class);
                        fps.setAccessible(true);
                        r.info("setCameraFps(15) -> " + fps.invoke(cam, 15));
                    } catch (NoSuchMethodException e) {
                        r.info("setCameraFps not available");
                    }
                    Method add = avm.getDeclaredMethod("addPreviewSurface", Surface.class, int.class);
                    add.setAccessible(true);
                    r.info("addPreviewSurface -> " + add.invoke(cam, surface, 0));
                    Method start = avm.getDeclaredMethod("startPreview");
                    start.setAccessible(true);
                    r.info("startPreview -> " + start.invoke(cam));
                } catch (Throwable t) {
                    openErr.set(t);
                } finally {
                    opened.countDown();
                }
            }
        }, "cam-open");
        opener.setDaemon(true);
        opener.start();

        boolean success = false;
        try {
            if (!opened.await(15, TimeUnit.SECONDS)) {
                r.fail("open", "no answer after 15 s (camera may be busy — OverDrive or the 360 view?)");
            } else if (openErr.get() != null) {
                r.error("open camera " + cameraId, openErr.get());
            } else {
                r.ok("open camera " + cameraId, "opened in " + (System.currentTimeMillis() - tStart) + " ms");
                if (got.await(10, TimeUnit.SECONDS) && jpeg.get() != null) {
                    long ms = System.currentTimeMillis() - tStart - firstFrameMs[0];
                    double fps = ms > 0 ? frameCount[0] * 1000.0 / ms : 0;
                    r.ok("frames", frameCount[0] + " frames, first after " + firstFrameMs[0]
                            + " ms, about " + String.format(java.util.Locale.US, "%.1f", fps) + " fps");
                    success = save(jpeg.get(), label, width);
                } else {
                    r.fail("frames", "no picture within 10 s (" + frameCount[0] + " frames seen). "
                            + "The camera may need a different image format.");
                }
            }
        } catch (InterruptedException ignored) {
        } finally {
            close(camRef.get());
            reader.close();
            ht.quitSafely();
        }
        return success;
    }

    private void close(final Object cam) {
        if (cam == null) return;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                for (String m : new String[]{"stopPreview", "close"}) {
                    try {
                        Method mm = cam.getClass().getDeclaredMethod(m);
                        mm.setAccessible(true);
                        mm.invoke(cam);
                    } catch (Throwable t) {
                        r.info(m + " failed: " + t);
                    }
                }
            }
        }, "cam-close");
        t.setDaemon(true);
        t.start();
        try { t.join(5000); } catch (InterruptedException ignored) {}
        r.info(t.isAlive() ? "camera close is still hanging after 5 s" : "camera closed");
    }

    private boolean save(byte[] jpg, String label, int width) {
        try {
            Report.DIR.mkdirs();
            File f = new File(Report.DIR, "cam_" + label + ".jpg");
            try (FileOutputStream o = new FileOutputStream(f)) { o.write(jpg); }
            f.setReadable(true, false);
            r.ok("saved", f.getAbsolutePath());
            if (width >= 5120) {
                Bitmap full = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
                int w4 = full.getWidth() / 4;
                for (int i = 0; i < 4; i++) {
                    Bitmap s = Bitmap.createBitmap(full, i * w4, 0, w4, full.getHeight());
                    File sf = new File(Report.DIR, "cam_" + label + "_slice" + SLICE_NAMES[i] + ".jpg");
                    try (FileOutputStream o = new FileOutputStream(sf)) {
                        s.compress(Bitmap.CompressFormat.JPEG, 90, o);
                    }
                    sf.setReadable(true, false);
                    s.recycle();
                }
                full.recycle();
                r.ok("slices", "4 slice pictures saved (names are OverDrive's guess: rear/left/right/front — please check)");
            }
            return true;
        } catch (Throwable t) {
            r.error("save picture", t);
            return false;
        }
    }

    /** YUV_420_888 -> NV21 -> JPEG. */
    static byte[] toJpeg(Image img) {
        int w = img.getWidth(), h = img.getHeight();
        byte[] nv21 = new byte[w * h * 3 / 2];
        Image.Plane[] p = img.getPlanes();
        copyPlane(p[0].getBuffer(), p[0].getRowStride(), p[0].getPixelStride(), w, h, nv21, 0, 1);
        // NV21 = Y then interleaved V,U
        copyPlane(p[2].getBuffer(), p[2].getRowStride(), p[2].getPixelStride(), w / 2, h / 2, nv21, w * h, 2);
        copyPlane(p[1].getBuffer(), p[1].getRowStride(), p[1].getPixelStride(), w / 2, h / 2, nv21, w * h + 1, 2);
        YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, w, h, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuv.compressToJpeg(new Rect(0, 0, w, h), 90, out);
        return out.toByteArray();
    }

    private static void copyPlane(ByteBuffer buf, int rowStride, int pixStride, int w, int h,
                                  byte[] out, int offset, int outStep) {
        buf.rewind();
        int o = offset;
        byte[] row = new byte[rowStride];
        for (int y = 0; y < h; y++) {
            int len = Math.min(rowStride, buf.remaining());
            buf.get(row, 0, len);
            for (int x = 0; x < w; x++) {
                int idx = x * pixStride;
                out[o] = idx < len ? row[idx] : 0;
                o += outStep;
            }
        }
    }
}
