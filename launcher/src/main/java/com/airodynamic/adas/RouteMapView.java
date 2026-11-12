package com.airodynamic.adas;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Today's route on a dark street map (user, 2026-11-12): blue line, green start dot, red "now" dot,
 * white arrows moving along the line in the driving direction. Fits the whole route when it opens,
 * then keeps the car in view; drag / pinch to look around (it follows again 10 s later, or tap the dot).
 * Map pictures: CARTO basemaps (OpenStreetMap data), kept on disk so places seen before load at once.
 */
final class RouteMapView extends View {
    private static final int TILE = 256;
    private final Theme th;
    private final float d;
    private final boolean light;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), tileP = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final LruCache<String, Bitmap> tiles = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private final Set<String> loading = new HashSet<>();
    private static final ExecutorService NET = Executors.newFixedThreadPool(3);
    private final File cacheDir;

    private double zoom = 15;            // map zoom (fractional)
    private double cx, cy;               // centre in world pixels at zoom 0 (0..256)
    private boolean fitted, following = true;
    private long touchedAt;
    private double[] pts = new double[0];
    private int seenVersion = -1;

    private final ScaleGestureDetector pinch;
    private float lastX, lastY, downX, downY;
    private boolean dragging;

    RouteMapView(Context c, Theme th) {
        super(c);
        this.th = th;
        d = c.getResources().getDisplayMetrics().density;
        light = th == Theme.WHITE;
        cacheDir = new File(c.getCacheDir(), "tiles");
        //noinspection ResultOfMethodCallIgnored
        cacheDir.mkdirs();
        pinch = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector g) {
                zoom = Math.max(3, Math.min(18, zoom + Math.log(g.getScaleFactor()) / Math.log(2)));
                touched();
                invalidate();
                return true;
            }
        });
    }

    // ---------------------------------------------------------------- projection (Web Mercator)
    private static double wx(double lon) { return (lon + 180) / 360 * TILE; }
    private static double wy(double lat) {
        double s = Math.sin(Math.toRadians(Math.max(-85, Math.min(85, lat))));
        return (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * TILE;
    }
    private double scale() { return Math.pow(2, zoom) * d * 0.9; }   // screen px per world px
    private float sx(double x) { return (float) ((x - cx) * scale() + getWidth() / 2.0); }
    private float sy(double y) { return (float) ((y - cy) * scale() + getHeight() / 2.0); }

    private void touched() { touchedAt = SystemClock.uptimeMillis(); following = false; }

    /** Zoom and centre so the whole route fits (with room around it). */
    private void fit() {
        int n = pts.length / 2;
        if (n == 0 || getWidth() == 0) return;
        double x0 = 1e9, x1 = -1e9, y0 = 1e9, y1 = -1e9;
        for (int i = 0; i < n; i++) {
            double x = wx(pts[2 * i + 1]), y = wy(pts[2 * i]);
            x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y);
        }
        cx = (x0 + x1) / 2; cy = (y0 + y1) / 2;
        double span = Math.max((x1 - x0) / (getWidth() * .8), (y1 - y0) / (getHeight() * .7));
        zoom = span <= 0 ? 16 : Math.max(3, Math.min(17, Math.log(1 / (span * d * 0.9)) / Math.log(2)));
        fitted = true;
    }

    // ---------------------------------------------------------------- touch
    @Override public boolean onTouchEvent(MotionEvent e) {
        pinch.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = downX = e.getX(); lastY = downY = e.getY(); dragging = false;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (pinch.isInProgress() || e.getPointerCount() > 1) { lastX = e.getX(); lastY = e.getY(); return true; }
                float dx = e.getX() - lastX, dy = e.getY() - lastY;
                if (dragging || Math.hypot(e.getX() - downX, e.getY() - downY) > 8 * d) {
                    dragging = true;
                    cx -= dx / scale(); cy -= dy / scale();
                    touched();
                    invalidate();
                }
                lastX = e.getX(); lastY = e.getY();
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging && !pinch.isInProgress()) {   // a tap near the red dot: follow the car again
                    int n = pts.length / 2;
                    if (n > 0 && Math.hypot(e.getX() - sx(wx(pts[2 * n - 1])), e.getY() - sy(wy(pts[2 * n - 2]))) < 40 * d) {
                        following = true;
                        invalidate();
                    }
                }
                return true;
        }
        return true;
    }

    // ---------------------------------------------------------------- tiles
    private Bitmap tile(int z, int x, int y) {
        int max = 1 << z;
        if (y < 0 || y >= max) return null;
        x = ((x % max) + max) % max;
        final String key = z + "/" + x + "/" + y;
        Bitmap b = tiles.get(key);
        if (b != null) return b;
        synchronized (loading) { if (!loading.add(key)) return null; }
        final int fx = x;
        NET.execute(() -> {
            Bitmap bm = null;
            String style = light ? "light_all" : "dark_all";
            File f = new File(cacheDir, style + "_" + key.replace('/', '_') + ".png");
            try {
                if (f.exists()) bm = BitmapFactory.decodeFile(f.getPath());
                if (bm == null) {
                    char sub = "abcd".charAt((fx + y) & 3);
                    HttpURLConnection con = (HttpURLConnection) new URL("https://" + sub + ".basemaps.cartocdn.com/"
                            + style + "/" + z + "/" + fx + "/" + y + "@2x.png").openConnection();
                    con.setRequestProperty("User-Agent", "NATEE-Launcher/1.0 (car home screen)");
                    con.setConnectTimeout(8000); con.setReadTimeout(10000);
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    try (InputStream in = con.getInputStream()) {
                        byte[] bb = new byte[16384]; int r;
                        while ((r = in.read(bb)) > 0) buf.write(bb, 0, r);
                    }
                    byte[] data = buf.toByteArray();
                    bm = BitmapFactory.decodeByteArray(data, 0, data.length);
                    if (bm != null) try (FileOutputStream out = new FileOutputStream(f)) { out.write(data); }
                }
            } catch (Throwable ignored) { }
            if (bm != null) tiles.put(key, bm);
            synchronized (loading) { loading.remove(key); }
            if (bm != null) postInvalidate();
        });
        return null;
    }

    // ---------------------------------------------------------------- drawing
    private long lastFrame;

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawColor(light ? 0xFFF2F2F2 : 0xFF0B1220);
        TodayRoute r = TodayRoute.get(getContext());
        if (r.version() != seenVersion) { seenVersion = r.version(); pts = r.points(); }
        int n = pts.length / 2;
        if (!fitted) fit();
        if (!following && SystemClock.uptimeMillis() - touchedAt > 10_000) following = true;
        if (following && n > 0) {   // keep the car in view: pan only when it gets near the edge
            float px = sx(wx(pts[2 * n - 1])), py = sy(wy(pts[2 * n - 2]));
            if (px < w * .15f || px > w * .85f || py < h * .2f || py > h * .85f) { cx = wx(pts[2 * n - 1]); cy = wy(pts[2 * n - 2]); }
        }

        // map pictures
        int z = (int) Math.max(0, Math.min(18, Math.floor(zoom)));
        double tilePx = TILE * Math.pow(2, zoom - z) * d * 0.9;   // on-screen size of one tile
        double ox = cx * Math.pow(2, z), oy = cy * Math.pow(2, z);  // centre in tile-pixel space at level z
        int tx0 = (int) Math.floor((ox - w / 2.0 / tilePx * TILE) / TILE), tx1 = (int) Math.floor((ox + w / 2.0 / tilePx * TILE) / TILE);
        int ty0 = (int) Math.floor((oy - h / 2.0 / tilePx * TILE) / TILE), ty1 = (int) Math.floor((oy + h / 2.0 / tilePx * TILE) / TILE);
        RectF dst = new RectF();
        for (int ty = ty0; ty <= ty1; ty++) for (int tx = tx0; tx <= tx1; tx++) {
            Bitmap b = tile(z, tx, ty);
            if (b == null) continue;
            float left = (float) ((tx * TILE - ox) / TILE * tilePx + w / 2.0), top = (float) ((ty * TILE - oy) / TILE * tilePx + h / 2.0);
            dst.set(left, top, (float) (left + tilePx + 1), (float) (top + tilePx + 1));
            c.drawBitmap(b, null, dst, tileP);
        }

        if (n == 0) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(th.muted);
            p.setTextSize(15 * d);
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText("Today's route appears once you drive (GPS)", w / 2f, h / 2f, p);
            p.setTextAlign(Paint.Align.LEFT);
            return;
        }

        // route line
        Path path = new Path();
        for (int i = 0; i < n; i++) {
            float x = sx(wx(pts[2 * i + 1])), y = sy(wy(pts[2 * i]));
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeJoin(Paint.Join.ROUND); p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(th.accent); p.setAlpha(90); p.setStrokeWidth(12 * d);
        c.drawPath(path, p);
        p.setAlpha(255); p.setStrokeWidth(5 * d);
        c.drawPath(path, p);

        // arrows moving along the line, in the driving direction
        PathMeasure pm = new PathMeasure(path, false);
        float gap = 46 * d, shift = (SystemClock.uptimeMillis() % 1500) / 1500f * gap;
        float[] pos = new float[2], tan = new float[2];
        p.setColor(0xFFFFFFFF); p.setStrokeWidth(2.6f * d);
        float a = 5 * d;
        do {
            float len = pm.getLength();
            for (float s = shift; s < len; s += gap) {
                pm.getPosTan(s, pos, tan);
                if (pos[0] < -20 || pos[0] > w + 20 || pos[1] < -20 || pos[1] > h + 20) continue;
                float ux = tan[0], uy = tan[1];
                Path ar = new Path();
                ar.moveTo(pos[0] - ux * a - uy * a, pos[1] - uy * a + ux * a);
                ar.lineTo(pos[0] + ux * a * .8f, pos[1] + uy * a * .8f);
                ar.lineTo(pos[0] - ux * a + uy * a, pos[1] - uy * a - ux * a);
                c.drawPath(ar, p);
            }
        } while (pm.nextContour());

        // start (green) and now (red) dots
        p.setStyle(Paint.Style.FILL);
        dot(c, sx(wx(pts[1])), sy(wy(pts[0])), 0xFF2EE59D, 7 * d);
        dot(c, sx(wx(pts[2 * n - 1])), sy(wy(pts[2 * n - 2])), 0xFFE5202E, 9 * d);

        // map credit (required by the map's licence)
        p.setTextSize(9 * d); p.setColor(th.muted); p.setTextAlign(Paint.Align.RIGHT);
        c.drawText("© OpenStreetMap · © CARTO", w - 6 * d, h - 6 * d, p);
        p.setTextAlign(Paint.Align.LEFT);

        // keep the arrows moving (about 25 frames a second, only while shown)
        long now = SystemClock.uptimeMillis();
        postInvalidateDelayed(Math.max(0, 40 - (now - lastFrame)));
        lastFrame = now;
    }

    private void dot(Canvas c, float x, float y, int col, float r) {
        p.setColor(0xFFFFFFFF); c.drawCircle(x, y, r + 2.5f * d, p);
        p.setColor(col); c.drawCircle(x, y, r, p);
    }
}
