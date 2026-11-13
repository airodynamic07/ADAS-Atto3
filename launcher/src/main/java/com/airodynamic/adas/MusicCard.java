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
 * Now playing (YouTube Music first): cover, song, ⏮ ⏯ ⏭. Info and controls only; open the
 * music app yourself (user, 2026-11-11).
 * Reads the music app's media session (needs "Notification access", same as Now playing).
 * YouTube Music first (GBox or normal); any other playing music app if it isn't there.
 */
public final class MusicCard {
    static final String[] YTM = {"com.gbox.com.google.android.apps.youtube.music", "com.google.android.apps.youtube.music"};

    private final Context c;
    private final Theme th;
    private final LinearLayout root;
    private final ImageView art;
    private final TextView title, artist;
    private final Button play, like;
    private final TextView lyPrev, lyNow, lyNext;
    private final LinearLayout queueRow;
    private String queueKey = "";
    private final TextView lab;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean active;

    public MusicCard(Context c, Theme th) {
        this.c = c;
        this.th = th;
        root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.round(th.card, th.line, 14, c));
        int p = Ui.dp(c, 8);
        root.setPadding(p, p, p, p);
        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));
        art = new ImageView(c);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackground(Ui.round(th.chip, 0, 8, c));
        art.setClipToOutline(true);
        top.addView(art, new LinearLayout.LayoutParams(Ui.dp(c, 64), Ui.dp(c, 64)));
        LinearLayout txt = new LinearLayout(c);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 4), 0);
        lab = Ui.label(c, "Now playing", th);
        txt.addView(lab);
        title = Ui.text(c, "", 14, th.text, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        artist = Ui.text(c, "", 12, th.muted, false);
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        txt.addView(title);
        txt.addView(artist);
        top.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(Ui.chip(c, "⏮", false, th, v -> act(0)), sq());
        play = Ui.chip(c, "⏯", true, th, v -> act(1));
        top.addView(play, sq());
        top.addView(Ui.chip(c, "⏭", false, th, v -> act(2)), sq());
        like = Ui.chip(c, "👍", false, th, v -> act(3));
        top.addView(like, sq());
        // 🎬 the song's video in this window (user, 2026-11-13: passenger can watch while driving)
        top.addView(Ui.chip(c, "🎬", false, th, v -> openVideo()), sq());

        // lyrics (from LRCLIB, matched by title/artist, in time with the song): previous, CURRENT, next lines
        LinearLayout ly = new LinearLayout(c);
        ly.setOrientation(LinearLayout.VERTICAL);
        ly.setGravity(Gravity.CENTER);
        ly.setPadding(Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6), 0);
        // bigger lyrics and one more line, using the room from the single Up next row (user, 2026-11-13)
        lyPrev = lyricLine(17, th.muted, false);
        lyNow = lyricLine(25, th.text, true);
        lyNext = lyricLine(17, th.muted, false);
        lyNext2 = lyricLine(16, th.muted, false);
        lyNext3 = lyricLine(15, th.muted, false);
        ly.addView(lyPrev); ly.addView(lyNow); ly.addView(lyNext);   // 3 lines (user, 2026-11-13)
        // tap the lyrics: a slider to move in the song pops up and hides 3 s after the last touch
        android.widget.FrameLayout lyBox = new android.widget.FrameLayout(c);
        lyBox.addView(ly, new android.widget.FrameLayout.LayoutParams(-1, -1));
        seekBox = new LinearLayout(c);
        seekBox.setGravity(Gravity.CENTER_VERTICAL);
        seekBox.setBackground(Ui.round((th.card & 0x00FFFFFF) | 0xEE000000, th.accent, 12, c));
        seekBox.setPadding(Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 10), Ui.dp(c, 4));
        seekPos = Ui.text(c, "0:00", 12, th.muted, false);
        seekLen = Ui.text(c, "0:00", 12, th.muted, false);
        seek = new android.widget.SeekBar(c);
        seek.setMax(1000);
        seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar b, int v, boolean user) {
                if (!user) return;
                long len = songLength();
                if (len > 0) seekPos.setText(mmss(len * v / 1000));
                hideSeekLater();
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar b) { main.removeCallbacks(hideSeek); }
            @Override public void onStopTrackingTouch(android.widget.SeekBar b) {
                long len = songLength();
                MediaController mc = session();
                if (mc != null && len > 0) mc.getTransportControls().seekTo(len * b.getProgress() / 1000);
                hideSeekLater();
            }
        });
        seekBox.addView(seekPos);
        seekBox.addView(seek, new LinearLayout.LayoutParams(0, Ui.dp(c, 36), 1f));
        seekBox.addView(seekLen);
        seekBox.setVisibility(android.view.View.GONE);
        android.widget.FrameLayout.LayoutParams sbl = new android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        sbl.leftMargin = sbl.rightMargin = Ui.dp(c, 4);
        lyBox.addView(seekBox, sbl);
        lyBox.setOnClickListener(v -> showSeek());
        ly.setOnClickListener(v -> showSeek());
        root.addView(lyBox, new LinearLayout.LayoutParams(-1, 0, 1f));

        // weather: now and in 1 hour (user, 2026-11-13); says why when it can't get it
        LinearLayout wx = new LinearLayout(c);
        wx.setGravity(Gravity.CENTER_VERTICAL);
        wx.setBackground(Ui.round(th.card, th.line, 12, c));
        wx.setPadding(Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 10), Ui.dp(c, 4));
        wxNowIcon = Ui.text(c, "–", 24, th.text, false);
        wx.addView(wxNowIcon);
        LinearLayout wn = new LinearLayout(c); wn.setOrientation(LinearLayout.VERTICAL); wn.setPadding(Ui.dp(c, 8), 0, 0, 0);
        wn.addView(Ui.text(c, "Now", 11, th.muted, false));
        wxNow = Ui.text(c, "–", 18, th.text, true); wn.addView(wxNow);
        wx.addView(wn, new LinearLayout.LayoutParams(0, -2, 1f));
        wxNextIcon = Ui.text(c, "", 22, th.text, false);
        wx.addView(wxNextIcon);
        LinearLayout wl = new LinearLayout(c); wl.setOrientation(LinearLayout.VERTICAL); wl.setPadding(Ui.dp(c, 8), 0, 0, 0);
        wl.addView(Ui.text(c, "In 1 hour", 11, th.muted, false));
        wxNext = Ui.text(c, "", 18, th.text, true); wl.addView(wxNext);
        wx.addView(wl, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams wxl = new LinearLayout.LayoutParams(-1, -2);
        wxl.topMargin = wxl.bottomMargin = Ui.dp(c, 4);
        root.addView(wx, wxl);

        // up next: the app's queue, swipe sideways, tap a song to play it
        root.addView(Ui.label(c, "Up next", th));
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        queueRow = new LinearLayout(c);
        queueRow.setGravity(Gravity.CENTER_VERTICAL);
        hs.addView(queueRow);
        root.addView(hs, new LinearLayout.LayoutParams(-1, Ui.dp(c, 34)));
        // second row of Up next (user, 2026-11-13)
        android.widget.HorizontalScrollView hs2 = new android.widget.HorizontalScrollView(c);
        hs2.setHorizontalScrollBarEnabled(false);
        queueRow2 = new LinearLayout(c);
        queueRow2.setGravity(Gravity.CENTER_VERTICAL);
        hs2.addView(queueRow2);
        LinearLayout.LayoutParams q2 = new LinearLayout.LayoutParams(-1, Ui.dp(c, 34));
        q2.topMargin = Ui.dp(c, 4);
        // root.addView(hs2, q2);   // second row removed (user, 2026-11-13)
        refresh();
    }

    public LinearLayout view() { return root; }

    public void setActive(boolean a) {
        active = a;
        main.removeCallbacks(tick);
        if (a) main.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() { if (!active) return; refresh(); main.postDelayed(this, lyrics.isEmpty() ? 2000 : 400); }
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
        refreshWeather();
        if (!NowPlaying.allowed(c)) {
            title.setText("Tap ⏯ to switch on");
            artist.setText("once; uses the car's ADB");
            return;
        }
        MediaController mc = session();
        if (mc == null) {
            title.setText("Nothing playing");
            artist.setText("Play music in any app; it shows here");
            lab.setText("Now playing");
            art.setImageBitmap(null);
            queueRow.removeAllViews();
        queueRow2.removeAllViews(); queueKey = "";
            return;
        }
        MediaMetadata md = mc.getMetadata();
        String t = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_TITLE);
        String a = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        title.setText(t == null || t.isEmpty() ? Apps.label(c, mc.getPackageName()) : t);
        lab.setText("Now playing · " + Apps.label(c, mc.getPackageName()));
        artist.setText(a == null ? "" : a);
        Bitmap b = md == null ? null : md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null && md != null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        art.setImageBitmap(b);
        PlaybackState st = mc.getPlaybackState();
        play.setText(st != null && st.getState() == PlaybackState.STATE_PLAYING ? "⏸" : "▶");
        like.setText(liked(mc) ? "👍✓" : "👍");
        showQueue(mc, st);
        // lyrics: look the song up once, then follow the playing position
        String key = (t == null ? "" : t) + "|" + (a == null ? "" : a);
        if (!key.equals(lyricsKey)) {
            lyricsKey = key;
            lyrics = new java.util.ArrayList<>();
            lyPrev.setText(""); lyNext.setText(""); lyNext2.setText(""); lyNext3.setText("");
            lyNow.setText(t == null ? "" : "Looking for lyrics…");
            long dur = md == null ? 0 : md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            if (t != null) Lyrics.find(t, a, dur, (k, lines, note) -> main.post(() -> {
                if (!k.equals(lyricsKey)) return;
                lyrics = lines;
                if (lines.isEmpty()) { lyNow.setText(note); lyPrev.setText(""); lyNext.setText(""); lyNext2.setText(""); lyNext3.setText(""); }
            }), key);
        }
        if (!lyrics.isEmpty() && st != null) {
            long pos = st.getPosition();
            if (st.getState() == PlaybackState.STATE_PLAYING)
                pos += (long) ((android.os.SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
            int i = Lyrics.lineAt(lyrics, pos);
            lyPrev.setText(i > 0 ? lyrics.get(i - 1).text : "");
            lyNow.setText(i >= 0 ? lyrics.get(i).text : "♪");
            lyNext.setText(i + 1 < lyrics.size() ? lyrics.get(i + 1).text : "");
            lyNext2.setText(i + 2 < lyrics.size() ? lyrics.get(i + 2).text : "");
            lyNext3.setText(i + 3 < lyrics.size() ? lyrics.get(i + 3).text : "");
        }
    }

    /** Upcoming songs from the app's queue (rebuilt only when it changes). */
    private void showQueue(MediaController mc, PlaybackState st) {
        List<android.media.session.MediaSession.QueueItem> q = mc.getQueue();
        long active = st == null ? -1 : st.getActiveQueueItemId();
        StringBuilder k = new StringBuilder(mc.getPackageName()).append(active).append('|');
        if (q != null) for (android.media.session.MediaSession.QueueItem it : q) k.append(it.getQueueId()).append(',');
        if (k.toString().equals(queueKey)) return;
        queueKey = k.toString();
        queueRow.removeAllViews();
        if (q == null || q.isEmpty()) {
            queueRow.addView(Ui.text(c, "This app doesn't share its Up next list", 12, th.muted, false));
            return;
        }
        int start = 0;
        for (int i = 0; i < q.size(); i++) if (q.get(i).getQueueId() == active) { start = i + 1; break; }
        int shown = 0;
        for (int i = start; i < q.size() && shown < 30; i++, shown++) {
            final android.media.session.MediaSession.QueueItem it = q.get(i);
            android.media.MediaDescription d = it.getDescription();
            // text only, one line: "Title · Artist" (user, 2026-11-11: no pictures, shorter)
            LinearLayout cell = new LinearLayout(c);
            cell.setGravity(Gravity.CENTER_VERTICAL);
            cell.setBackground(Ui.round(th.chip, th.line, 10, c));
            cell.setPadding(Ui.dp(c, 9), 0, Ui.dp(c, 9), 0);
            String ti = d.getTitle() == null ? "" : d.getTitle().toString(), ar = d.getSubtitle() == null ? "" : d.getSubtitle().toString();
            TextView t1 = Ui.text(c, ti, 12, th.text, true);
            t1.setSingleLine(true); t1.setEllipsize(TextUtils.TruncateAt.END); t1.setMaxWidth(Ui.dp(c, 120));
            cell.addView(t1);
            if (!ar.isEmpty()) {
                TextView t2 = Ui.text(c, " · " + ar, 11, th.muted, false);
                t2.setSingleLine(true); t2.setEllipsize(TextUtils.TruncateAt.END); t2.setMaxWidth(Ui.dp(c, 90));
                cell.addView(t2);
            }
            cell.setOnClickListener(v -> {
                mc.getTransportControls().skipToQueueItem(it.getQueueId());
                main.postDelayed(this::refresh, 600);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -1);
            lp.rightMargin = Ui.dp(c, 6);
            queueRow.addView(cell, lp);   // one row again (user, 2026-11-13)
        }
        if (shown == 0) queueRow.addView(Ui.text(c, "Nothing more in the queue", 12, th.muted, false));
    }

    private TextView lyNext2, lyNext3;
    private LinearLayout seekBox;
    private android.widget.SeekBar seek;
    private TextView seekPos, seekLen, wxNowIcon, wxNow, wxNextIcon, wxNext;
    private long wxAt;
    private final Runnable hideSeek = () -> { if (seekBox != null) seekBox.setVisibility(android.view.View.GONE); };
    private void hideSeekLater() { main.removeCallbacks(hideSeek); main.postDelayed(hideSeek, 3000); }
    private static String mmss(long ms) { long s = ms / 1000; return (s / 60) + ":" + String.format(java.util.Locale.US, "%02d", s % 60); }
    private long songLength() {
        MediaController mc = session();
        if (mc == null || mc.getMetadata() == null) return 0;
        return mc.getMetadata().getLong(android.media.MediaMetadata.METADATA_KEY_DURATION);
    }
    /** Show the song slider (tap on the lyrics or the video). */
    public void showSeek() {
        MediaController mc = session();
        long len = songLength();
        if (mc == null || len <= 0) return;
        PlaybackState st = mc.getPlaybackState();
        long pos = st == null ? 0 : st.getPosition();
        if (st != null && st.getState() == PlaybackState.STATE_PLAYING)
            pos += (long) ((android.os.SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
        seek.setProgress((int) Math.max(0, Math.min(1000, pos * 1000 / len)));
        seekPos.setText(mmss(pos));
        seekLen.setText(mmss(len));
        seekBox.setVisibility(android.view.View.VISIBLE);
        hideSeekLater();
    }
    private void refreshWeather() {
        if (System.currentTimeMillis() - wxAt < 60_000L) return;
        wxAt = System.currentTimeMillis();
        Places.weather(c, w -> {
            if (w.tempC == Integer.MIN_VALUE) {
                wxNowIcon.setText("☁"); wxNow.setText(w.note.isEmpty() ? "–" : w.note); wxNow.setTextSize(12);
                wxNextIcon.setText(""); wxNext.setText("");
                return;
            }
            wxNow.setTextSize(18);
            wxNowIcon.setText(w.icon()); wxNow.setText(w.tempC + "°C");
            if (w.nextTempC != Integer.MIN_VALUE) {
                wxNextIcon.setText(w.nextIcon());
                wxNext.setText(w.nextTempC + "°C" + (w.nextRainPct >= 0 ? " · " + w.nextRainPct + "%" : ""));
            }
        });
    }
    private LinearLayout queueRow2;
    private Runnable videoOpener;
    /** Set by the launcher: shows the current song's video over this card. */
    public void setVideoOpener(Runnable r) { videoOpener = r; }
    private void openVideo() { if (videoOpener != null) videoOpener.run(); }
    /** "Title Artist" of the song playing now, for the video search; null if nothing plays. */
    public String songQuery() {
        MediaController mc = session();
        if (mc == null || mc.getMetadata() == null) return null;
        android.media.MediaMetadata m = mc.getMetadata();
        String t = m.getString(android.media.MediaMetadata.METADATA_KEY_TITLE), a = m.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST);
        return t == null ? null : (t + (a == null ? "" : " " + a));
    }
    /** Pause / resume the music app (so the video and the app don't play together). */
    public boolean pauseMusic() {
        MediaController mc = session();
        if (mc == null || mc.getPlaybackState() == null || mc.getPlaybackState().getState() != PlaybackState.STATE_PLAYING) return false;
        mc.getTransportControls().pause();
        return true;
    }
    public void resumeMusic() { MediaController mc = session(); if (mc != null) mc.getTransportControls().play(); }

    private String lyricsKey = "";
    private java.util.List<Lyrics.Line> lyrics = new java.util.ArrayList<>();

    private TextView lyricLine(float sp, int col, boolean bold) {
        TextView v = Ui.text(c, "", sp, col, bold);
        v.setGravity(Gravity.CENTER);
        v.setMaxLines(2);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setPadding(0, Ui.dp(c, 3), 0, Ui.dp(c, 3));
        return v;
    }

    private static boolean liked(MediaController mc) {
        MediaMetadata md = mc.getMetadata();
        if (md == null) return false;
        android.media.Rating r = md.getRating(MediaMetadata.METADATA_KEY_USER_RATING);
        return r != null && r.isRated() && r.getRatingStyle() == android.media.Rating.RATING_THUMB_UP_DOWN && r.isThumbUp();
    }

    private void act(int what) {
        if (!NowPlaying.allowed(c)) {
            // BYD blocks the Notification access page ("IVI system does not support this operation"),
            // so switch it on through the car's own ADB instead (same way the installs work).
            Toast.makeText(c, "Switching on Now playing… if the car asks 'Allow USB debugging?', tick Always allow + OK", Toast.LENGTH_LONG).show();
            new Thread(() -> {
                String msg;
                try {
                    String out = Updater.adbShell(c, "cmd notification allow_listener " + NowPlaying.component(c).flattenToString());
                    msg = NowPlaying.allowed(c) ? "Now playing is on" : "Car answered: " + (out.isEmpty() ? "nothing" : out);
                } catch (Exception e) {
                    msg = "Couldn't reach the car's ADB (" + e.getClass().getSimpleName() + ")";
                }
                final String m = msg;
                main.post(() -> { Toast.makeText(c, m, Toast.LENGTH_LONG).show(); refresh(); });
            }, "allow-listener").start();
            return;
        }
        MediaController mc = session();
        if (mc == null) return;
        MediaController.TransportControls tc = mc.getTransportControls();
        if (what == 0) tc.skipToPrevious();
        else if (what == 2) tc.skipToNext();
        else if (what == 1) {
            PlaybackState st = mc.getPlaybackState();
            if (st != null && st.getState() == PlaybackState.STATE_PLAYING) tc.pause(); else tc.play();
        } else {
            // Like = YouTube Music's own thumbs-up action if it offers one (adds to Liked songs), else a thumbs-up rating
            boolean done = false;
            PlaybackState st = mc.getPlaybackState();
            StringBuilder seen = new StringBuilder();
            if (st != null) for (PlaybackState.CustomAction ca : st.getCustomActions()) {
                String id = (ca.getAction() + " " + ca.getName()).toLowerCase(java.util.Locale.US);
                seen.append(ca.getName()).append(", ");
                if ((id.contains("like") || id.contains("thumb")) && !id.contains("dislike") && !id.contains("down")) {
                    tc.sendCustomAction(ca, null);
                    done = true;
                    break;
                }
            }
            if (!done) tc.setRating(android.media.Rating.newThumbRating(true));
            Toast.makeText(c, done ? "👍 Liked" : "👍 Sent as a rating" + (seen.length() > 0 ? " (app offers: " + seen + ")" : ""), Toast.LENGTH_LONG).show();
        }
        main.postDelayed(this::refresh, 600);
    }

}
