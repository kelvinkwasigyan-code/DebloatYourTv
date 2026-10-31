package com.gathin.tvhome;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages active media sessions, track info, playback state, and transport controls.
 */
public class NowPlayingManager {
    private static final String TAG = "NowPlayingManager";
    private static NowPlayingManager sInstance;

    public interface Callback {
        void onNowPlayingUpdated(NowPlayingState state);
    }

    public static class NowPlayingState {
        public boolean hasActiveSession;
        public boolean isPlaying;
        public String title = "";
        public String artist = "";
        public String album = "";
        public String packageName = "";
        public Bitmap albumArt = null;
        public PendingIntent sessionActivity = null;
        public long duration = 0;
        public long position = 0;
        public float playbackSpeed = 1f;
        public long lastPositionUpdateTime = 0;

        public long getCurrentPosition() {
            if (!isPlaying || playbackSpeed <= 0) {
                return position;
            }
            long timeDelta = SystemClock.elapsedRealtime() - lastPositionUpdateTime;
            long estimated = position + (long) (timeDelta * playbackSpeed);
            if (duration > 0 && estimated > duration) {
                return duration;
            }
            return Math.max(0, estimated);
        }
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<Callback> callbacks = new ArrayList<>();
    private MediaController activeController;
    private MediaController.Callback controllerCallback;
    private final NowPlayingState currentState = new NowPlayingState();
    private Context appContext;

    public static synchronized NowPlayingManager get() {
        if (sInstance == null) {
            sInstance = new NowPlayingManager();
        }
        return sInstance;
    }

    public NowPlayingState getCurrentState() {
        return currentState;
    }

    public void init(Context context) {
        this.appContext = context.getApplicationContext();
        refreshSessions();
    }

    public void registerCallback(Callback cb) {
        if (!callbacks.contains(cb)) {
            callbacks.add(cb);
            cb.onNowPlayingUpdated(currentState);
        }
    }

    public void unregisterCallback(Callback cb) {
        callbacks.remove(cb);
    }

    public void onSessionsUpdated(List<MediaController> controllers) {
        if (controllers == null || controllers.isEmpty()) {
            if (!currentState.isPlaying) {
                clearSession();
            }
            return;
        }

        MediaController best = null;
        // Prioritize actively playing session
        for (MediaController mc : controllers) {
            PlaybackState ps = mc.getPlaybackState();
            if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) {
                best = mc;
                break;
            }
        }
        if (best == null) {
            // Prioritize known media apps
            for (MediaController mc : controllers) {
                String pkg = mc.getPackageName();
                if (pkg != null && (pkg.contains("spotify") || pkg.contains("music") || pkg.contains("audio"))) {
                    best = mc;
                    break;
                }
            }
        }
        if (best == null) {
            best = controllers.get(0);
        }

        bindController(best);
    }

    public void onMediaControllerFound(MediaController controller) {
        if (controller == null) return;
        if (activeController == null || !currentState.isPlaying) {
            bindController(controller);
        }
    }

    public void refreshSessions() {
        if (appContext == null) return;
        NowPlayingListenerService service = NowPlayingListenerService.getInstance();
        if (service != null) {
            service.updateSessions();
            return;
        }

        try {
            MediaSessionManager msm = (MediaSessionManager) appContext.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm != null) {
                ComponentName cn = new ComponentName(appContext, NowPlayingListenerService.class);
                List<MediaController> list = msm.getActiveSessions(cn);
                onSessionsUpdated(list);
            }
        } catch (SecurityException ignored) {
            // Permission or listener not connected yet
        }
    }

    private void bindController(MediaController controller) {
        if (controller == null) {
            clearSession();
            return;
        }
        if (activeController != null && activeController.getSessionToken().equals(controller.getSessionToken())) {
            updateFromController();
            return;
        }

        if (activeController != null && controllerCallback != null) {
            try {
                activeController.unregisterCallback(controllerCallback);
            } catch (Exception ignored) {}
        }

        activeController = controller;
        controllerCallback = new MediaController.Callback() {
            @Override
            public void onPlaybackStateChanged(PlaybackState state) {
                mainHandler.post(() -> updateFromController());
            }

            @Override
            public void onMetadataChanged(MediaMetadata metadata) {
                mainHandler.post(() -> updateFromController());
            }

            @Override
            public void onSessionDestroyed() {
                mainHandler.post(() -> {
                    clearSession();
                    refreshSessions();
                });
            }
        };

        try {
            activeController.registerCallback(controllerCallback, mainHandler);
        } catch (Exception ignored) {}

        updateFromController();
    }

    private void updateFromController() {
        if (activeController == null) {
            clearSession();
            return;
        }

        PlaybackState state = activeController.getPlaybackState();
        MediaMetadata meta = activeController.getMetadata();

        int playbackState = (state != null) ? state.getState() : PlaybackState.STATE_NONE;
        boolean playing = (playbackState == PlaybackState.STATE_PLAYING || playbackState == PlaybackState.STATE_BUFFERING);
        boolean paused = (playbackState == PlaybackState.STATE_PAUSED);

        String title = "";
        String artist = "";
        String album = "";
        Bitmap art = null;
        long duration = 0;

        if (meta != null) {
            title = meta.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (title == null || title.isEmpty()) {
                title = meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            }
            artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (artist == null || artist.isEmpty()) {
                artist = meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            }
            if (artist == null || artist.isEmpty()) {
                artist = meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            }
            album = meta.getString(MediaMetadata.METADATA_KEY_ALBUM);
            art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) {
                art = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);
            }
            duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION);
        }

        if ((title == null || title.isEmpty()) && activeController.getQueueTitle() != null) {
            title = activeController.getQueueTitle().toString();
        }

        if (title == null) title = "";
        if (artist == null) artist = "";
        if (album == null) album = "";

        // Only display if playing or paused with valid title
        if (title.isEmpty() && !playing) {
            clearSession();
            return;
        }

        long position = 0;
        float speed = 1f;
        long updateTime = 0;
        if (state != null) {
            position = state.getPosition();
            speed = state.getPlaybackSpeed();
            updateTime = state.getLastPositionUpdateTime();
        }

        currentState.hasActiveSession = true;
        currentState.isPlaying = playing;
        currentState.title = title;
        currentState.artist = artist;
        currentState.album = album;
        currentState.albumArt = art;
        currentState.packageName = activeController.getPackageName();
        currentState.sessionActivity = activeController.getSessionActivity();
        currentState.duration = Math.max(0, duration);
        currentState.position = Math.max(0, position);
        currentState.playbackSpeed = speed > 0 ? speed : 1f;
        currentState.lastPositionUpdateTime = updateTime > 0 ? updateTime : SystemClock.elapsedRealtime();

        notifyCallbacks();
    }

    private void clearSession() {
        currentState.hasActiveSession = false;
        currentState.isPlaying = false;
        currentState.title = "";
        currentState.artist = "";
        currentState.album = "";
        currentState.albumArt = null;
        currentState.sessionActivity = null;
        currentState.packageName = "";
        currentState.duration = 0;
        currentState.position = 0;
        currentState.playbackSpeed = 1f;
        currentState.lastPositionUpdateTime = 0;
        notifyCallbacks();
    }

    private void notifyCallbacks() {
        for (Callback cb : callbacks) {
            cb.onNowPlayingUpdated(currentState);
        }
    }

    public void play() {
        if (activeController != null && activeController.getTransportControls() != null) {
            try {
                activeController.getTransportControls().play();
            } catch (Exception ignored) {}
        }
        sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY);
    }

    public void pause() {
        if (activeController != null && activeController.getTransportControls() != null) {
            try {
                activeController.getTransportControls().pause();
            } catch (Exception ignored) {}
        }
        sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE);
    }

    public void togglePlayPause() {
        if (!currentState.hasActiveSession) {
            if (appContext != null) {
                openMusicApp(appContext);
            }
            return;
        }
        if (currentState.isPlaying) {
            pause();
        } else {
            play();
        }
    }

    public void skipToNext() {
        if (activeController != null && activeController.getTransportControls() != null) {
            try {
                activeController.getTransportControls().skipToNext();
            } catch (Exception ignored) {}
        }
        sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    public void skipToPrevious() {
        if (activeController != null && activeController.getTransportControls() != null) {
            try {
                activeController.getTransportControls().skipToPrevious();
            } catch (Exception ignored) {}
        }
        sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    public void openMusicApp(Context context) {
        if (context == null) context = appContext;
        if (context == null) return;
        if (currentState.sessionActivity != null) {
            try {
                currentState.sessionActivity.send();
                return;
            } catch (Exception ignored) {}
        }
        if (currentState.packageName != null && !currentState.packageName.isEmpty()) {
            Intent intent = context.getPackageManager().getLeanbackLaunchIntentForPackage(currentState.packageName);
            if (intent == null) {
                intent = context.getPackageManager().getLaunchIntentForPackage(currentState.packageName);
            }
            if (intent != null) {
                try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                    return;
                } catch (Exception ignored) {}
            }
        }
        String[] fallbackPkgs = new String[]{
                "com.spotify.tv.android",
                "com.google.android.youtube.tvmusic",
                "com.spotify.music",
                "com.google.android.apps.youtube.music",
                "com.amazon.mp3.tv",
                "com.apple.android.music"
        };
        for (String pkg : fallbackPkgs) {
            Intent intent = context.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (intent == null) {
                intent = context.getPackageManager().getLaunchIntentForPackage(pkg);
            }
            if (intent != null) {
                try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                    return;
                } catch (Exception ignored) {}
            }
        }
        try {
            Intent musicIntent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC);
            musicIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(musicIntent);
        } catch (Exception ignored) {}
    }

    private void sendMediaKey(int keyCode) {
        if (appContext == null) return;
        AudioManager am = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0);
        KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0);
        am.dispatchMediaKeyEvent(down);
        am.dispatchMediaKeyEvent(up);
    }
}
