package com.gathin.tvhome;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.List;

/**
 * System notification and media listener service.
 * Enables zero-lag discovery of active media sessions and playback state.
 */
public class NowPlayingListenerService extends NotificationListenerService {
    private static final String TAG = "NowPlayingListener";
    private static NowPlayingListenerService sInstance;
    private MediaSessionManager sessionManager;
    private MediaSessionManager.OnActiveSessionsChangedListener sessionsListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static NowPlayingListenerService getInstance() {
        return sInstance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        sInstance = this;
        registerSessionsListener();
        updateSessions();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        sInstance = null;
        if (sessionManager != null && sessionsListener != null) {
            try {
                sessionManager.removeOnActiveSessionsChangedListener(sessionsListener);
            } catch (Exception ignored) {}
        }
    }

    private void registerSessionsListener() {
        if (sessionManager == null) return;
        sessionsListener = controllers -> mainHandler.post(() -> {
            NowPlayingManager.get().onSessionsUpdated(controllers);
        });
        try {
            ComponentName cn = new ComponentName(this, NowPlayingListenerService.class);
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, cn);
        } catch (SecurityException e) {
            Log.w(TAG, "SecurityException registering session listener: " + e.getMessage());
        }
    }

    public void updateSessions() {
        if (sessionManager == null) return;
        try {
            ComponentName cn = new ComponentName(this, NowPlayingListenerService.class);
            List<MediaController> controllers = sessionManager.getActiveSessions(cn);
            NowPlayingManager.get().onSessionsUpdated(controllers);
        } catch (SecurityException e) {
            Log.w(TAG, "SecurityException getting active sessions: " + e.getMessage());
            checkNotificationsForMediaSession();
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return;

        if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
            try {
                MediaSession.Token token = n.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);
                if (token != null) {
                    MediaController controller = new MediaController(this, token);
                    NowPlayingManager.get().onMediaControllerFound(controller);
                }
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn != null) {
            mainHandler.post(this::updateSessions);
        }
    }

    private void checkNotificationsForMediaSession() {
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification sbn : active) {
                    onNotificationPosted(sbn);
                }
            }
        } catch (Exception ignored) {}
    }
}
