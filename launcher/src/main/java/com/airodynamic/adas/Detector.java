package com.airodynamic.adas;

import android.content.Context;
import android.content.res.AssetFileDescriptor;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * YOLO26n object detector (Ultralytics, AGPL-3.0 — see NOTICE). The model is
 * "end-to-end": it returns up to 300 finished boxes [x1, y1, x2, y2, score, class]
 * in 640x640 input pixels, so no extra NMS step is needed.
 *
 * Runs on the GPU delegate when possible (brief: ~50-80 ms on this chip),
 * otherwise CPU with 4 threads (~200-300 ms).
 */
public final class Detector implements AutoCloseable {

    public static final int SIZE = 640;

    /** COCO classes we care about on the road. */
    public static final int PERSON = 0, BICYCLE = 1, CAR = 2, MOTORCYCLE = 3, BUS = 5, TRUCK = 7;

    public static final class Det {
        public float x1, y1, x2, y2, score;
        public int cls;
    }

    private Interpreter interp;
    private GpuDelegate gpu;
    private final String mode;
    private final ByteBuffer input = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder());
    private final float[][][] out = new float[1][300][6];

    public Detector(Context ctx, boolean wantGpu) throws Exception {
        MappedByteBuffer model = load(ctx, "models/yolo26n.tflite");
        String m = "CPU (4 threads)";
        Interpreter it = null;
        if (wantGpu) {
            try {
                Interpreter.Options o = new Interpreter.Options();
                CompatibilityList cl = new CompatibilityList();
                gpu = cl.isDelegateSupportedOnThisDevice()
                        ? new GpuDelegate(cl.getBestOptionsForThisDevice()) : new GpuDelegate();
                cl.close();
                o.addDelegate(gpu);
                it = new Interpreter(model, o);
                m = "GPU";
            } catch (Throwable t) {
                if (gpu != null) { try { gpu.close(); } catch (Throwable ignored) {} gpu = null; }
                it = null;
                m = "CPU (GPU failed: " + t.getClass().getSimpleName() + ")";
            }
        }
        if (it == null) {
            Interpreter.Options o = new Interpreter.Options();
            o.setNumThreads(4);
            o.setUseXNNPACK(true);
            it = new Interpreter(model, o);
        }
        interp = it;
        mode = m;
    }

    public String mode() { return mode; }

    private static MappedByteBuffer load(Context ctx, String asset) throws Exception {
        try (AssetFileDescriptor fd = ctx.getAssets().openFd(asset);
             FileInputStream in = new FileInputStream(fd.getFileDescriptor())) {
            return in.getChannel().map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
        }
    }

    /**
     * @param argb picture, w x h (w, h <= 640), letterboxed into 640x640
     * @return detections in picture pixels
     */
    public List<Det> run(int[] argb, int w, int h, float minScore) {
        float s = Math.min((float) SIZE / w, (float) SIZE / h);
        int nw = Math.round(w * s), nh = Math.round(h * s);
        int px = (SIZE - nw) / 2, py = (SIZE - nh) / 2;
        FloatBuffer f = input.asFloatBuffer();
        final float pad = 114f / 255f;
        for (int y = 0; y < SIZE; y++) {
            int sy = (int) ((y - py) / s);
            boolean rowIn = y >= py && y < py + nh && sy < h;
            for (int x = 0; x < SIZE; x++) {
                int sx = (int) ((x - px) / s);
                if (rowIn && x >= px && x < px + nw && sx < w) {
                    int c = argb[sy * w + sx];
                    f.put(((c >> 16) & 0xFF) / 255f);
                    f.put(((c >> 8) & 0xFF) / 255f);
                    f.put((c & 0xFF) / 255f);
                } else {
                    f.put(pad); f.put(pad); f.put(pad);
                }
            }
        }
        input.rewind();
        interp.run(input, out);
        List<Det> res = new ArrayList<>();
        for (float[] r : out[0]) {
            if (r[4] < minScore) continue;
            int c = (int) r[5];
            if (c != PERSON && c != BICYCLE && c != CAR && c != MOTORCYCLE && c != BUS && c != TRUCK) continue;
            Det d = new Det();
            d.x1 = (r[0] - px) / s; d.y1 = (r[1] - py) / s;
            d.x2 = (r[2] - px) / s; d.y2 = (r[3] - py) / s;
            d.score = r[4];
            d.cls = c;
            res.add(d);
        }
        return res;
    }

    public static String name(int cls) {
        switch (cls) {
            case PERSON: return "person";
            case BICYCLE: return "bicycle";
            case CAR: return "car";
            case MOTORCYCLE: return "motorbike";
            case BUS: return "bus";
            case TRUCK: return "truck";
            default: return "?";
        }
    }

    @Override public void close() {
        if (interp != null) { interp.close(); interp = null; }
        if (gpu != null) { gpu.close(); gpu = null; }
    }
}
