package com.airodynamic.adas;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * YouTube Music in the left window: cover, song, ⏮ ⏯ ⏭ and 👍 Like.
 * Reads the music app's media session (needs "Notification access", same as Now playing).
 * YouTube Music first (GBox or normal); any other playing music app if it isn't there.
 * Like: YouTube Music's own "thumbs up" action if it offers one, else a thumbs-up rating.
 */
public final class MusicCard {
    static final String[] YTM = {"com.gbox.com.google.android.apps.youtube.music", "com.google.android.apps.youtube.music"};

    private final Context c;
    private final Theme th;
    private final LinearLayout root;
    private final ImageView art;
    private final TextView title, artist;
    private final Button play, like;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean active;

    public MusicCard(Context c, Theme th) {
        this.c = c;
        this.th = th;
        root = new LinearLayout(c);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setBackground(Ui.round(th.panel, 0, 12, c));
        int p = Ui.dp(c, 8);
        root.setPadding(p, p, p, p);
        art = new ImageView(c);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackground(Ui.round(th.chip, 0, 8, c));
        art.setClipToOutline(true);
        art.setOnClickListener(v -> openApp());
        root.addView(art, new LinearLayout.LayoutParams(Ui.dp(c, 52), Ui.dp(c, 52)));
        LinearLayout txt = new LinearLayout(c);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 4), 0);
        TextView lab = Ui.label(c, "YouTube Music", th);
        txt.addView(lab);
        title = Ui.text(c, "", 14, th.text, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        artist = Ui.text(c, "", 12, th.muted, false);
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        txt.addView(title);
        txt.addView(artist);
        txt.setOnClickListener(v -> openApp());
        root.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(Ui.chip(c, "⏮", false, th, v -> act(0)), sq());
        play = Ui.chip(c, "⏯", true, th, v -> act(1));
        root.addView(play, sq());
        root.addView(Ui.chip(c, "⏭", false, th, v -> act(2)), sq());
        like = Ui.chip(c, "👍", false, th, v -> act(3));
        root.addView(like, sq());
        refresh();
    }

    public LinearLayout view() { return root; }

    public void setActive(boolean a) {
        active = a;
        main.removeCallbacks(tick);
        if (a) main.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() { if (!active) return; refresh(); main.postDelayed(this, 2000); }
    };

    private LinearLayout.LayoutParams sq() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44));
        lp.leftMargin = Ui.dp(c, 4);
        return lp;
    }

    /** YouTube Music's session if it has one, else the playing (or first) music session. */
    private MediaController session() {
        try {
            MediaSessionManager m = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> list = m.getActiveSessions(NowPlaying.component(c));
            for (MediaController mc : list) for (String y : YTM) if (y.equals(mc.getPackageName())) return mc;
            return NowPlaying.active(c);
        } catch (SecurityException e) {
            return null;
        }
    }

    private void refresh() {
        if (!NowPlaying.allowed(c)) {
            title.setText("Tap ⏯ to allow");
            artist.setText("Notification access, once");
            return;
        }
        MediaController mc = session();
        if (mc == null) {
            title.setText("Not playing");
            artist.setText("Tap ⏯ to open YouTube Music");
            art.setImageBitmap(null);
            return;
        }
        MediaMetadata md = mc.getMetadata();
        String t = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_TITLE);
        String a = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        title.setText(t == null || t.isEmpty() ? Apps.label(c, mc.getPackageName()) : t);
        artist.setText(a == null ? "" : a);
        Bitmap b = md == null ? null : md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null && md != null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        art.setImageBitmap(b);
        PlaybackState st = mc.getPlaybackState();
        play.setText(st != null && st.getState() == PlaybackState.STATE_PLAYING ? "⏸" : "▶");
        like.setText(liked(mc) ? "👍✓" : "👍");
    }

    private static boolean liked(MediaController mc) {
        MediaMetadata md = mc.getMetadata();
        if (md == null) return false;
        Rating r = md.getRating(MediaMetadata.METADATA_KEY_USER_RATING);
        return r != null && r.isRated() && r.getRatingStyle() == Rating.RATING_THUMB_UP_DOWN && r.isThumbUp();
    }

    private void act(int what) {
        if (!NowPlaying.allowed(c)) {
            Toast.makeText(c, "Turn on 'Notification access' for ADAS Launcher", Toast.LENGTH_LONG).show();
            try { c.startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) {}
            return;
        }
        MediaController mc = session();
        if (mc == null) { if (what == 1) openApp(); return; }
        MediaController.TransportControls tc = mc.getTransportControls();
        if (what == 0) tc.skipToPrevious();
        else if (what == 2) tc.skipToNext();
        else if (what == 1) {
            PlaybackState st = mc.getPlaybackState();
            if (st != null && st.getState() == PlaybackState.STATE_PLAYING) tc.pause(); else tc.play();
        } else {
            boolean done = false;
            PlaybackState st = mc.getPlaybackState();
            if (st != null) for (PlaybackState.CustomAction ca : st.getCustomActions()) {
                String id = (ca.getAction() + " " + ca.getName()).toLowerCase(Locale.US);
                if ((id.contains("like") || id.contains("thumb")) && !id.contains("dislike") && !id.contains("down")) {
                    tc.sendCustomAction(ca, null);
                    done = true;
                    break;
                }
            }
            if (!done) tc.setRating(Rating.newThumbRating(true));
            Toast.makeText(c, "👍 Liked", Toast.LENGTH_SHORT).show();
        }
        main.postDelayed(this::refresh, 600);
    }

    private void openApp() {
        for (String y : YTM) if (Apps.installed(c, y)) { Apps.open(c, y, CarData.get(c).last()); return; }
        Toast.makeText(c, "YouTube Music is not installed", Toast.LENGTH_SHORT).show();
    }
}
