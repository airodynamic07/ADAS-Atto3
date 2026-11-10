package com.airodynamic.adas;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;

import java.util.List;

/**
 * "Now playing": reads the active music app (Spotify, Media Center, YouTube Music...).
 * Android only allows this with "Notification access" for this app, which the user
 * switches on once (the card has a button that opens that settings page).
 */
public final class NowPlaying extends NotificationListenerService {

    public static ComponentName component(Context c) { return new ComponentName(c, NowPlaying.class); }

    /** The first music app that is playing (or paused), or null. */
    public static MediaController active(Context c) {
        try {
            MediaSessionManager m = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> list = m.getActiveSessions(component(c));
            MediaController paused = null;
            for (MediaController mc : list) {
                PlaybackState st = mc.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) return mc;
                if (paused == null) paused = mc;
            }
            return paused;
        } catch (SecurityException e) {
            return null;   // notification access not granted yet
        }
    }

    public static boolean allowed(Context c) {
        try {
            ((MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE)).getActiveSessions(component(c));
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    public static String title(MediaController mc) {
        MediaMetadata md = mc.getMetadata();
        if (md == null) return "";
        String t = md.getString(MediaMetadata.METADATA_KEY_TITLE);
        String a = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        return (t == null ? "" : t) + (a == null || a.isEmpty() ? "" : " — " + a);
    }
}
