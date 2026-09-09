package com.example.uhf.manager;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;

import com.example.uhf.model.MessageModel;
import com.example.uhf.model.NotificationModel;

import java.util.List;

public class NotificationManager {

    private static final String TAG = "NotificationManager";
    private static final long POLL_INTERVAL_MS = 30000;
    private static final long INITIAL_DELAY_MS = 5000;

    private final Context context;
    private final MiraMessagingManager messagingManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pollingRunnable;
    private TextView badgeCountView;
    private int totalUnreadCount = 0;

    public NotificationManager(Context context) {
        this.context = context;
        this.messagingManager = MiraMessagingManager.getInstance(context);
    }

    public void init(int userId) {
        if (userId > 0) {
            messagingManager.setCurrentUserId(String.valueOf(userId));
        }
        setupListeners();
        startPolling();
    }

    private void setupListeners() {
        messagingManager.addMessageCountListener(this::onMessageCountChanged);
        messagingManager.addNotificationCountListener(this::onNotificationCountChanged);
    }

    private void onMessageCountChanged(int unreadCount) {
        updateTotalBadgeCount();
    }

    private void onNotificationCountChanged(int unreadCount) {
        updateTotalBadgeCount();
    }

    public void setBadgeView(TextView badgeCountView) {
        this.badgeCountView = badgeCountView;
        updateTotalBadgeCount();
    }

    private void updateTotalBadgeCount() {
        if (badgeCountView == null) return;

        totalUnreadCount = messagingManager.getTotalUnreadCount();

        if (totalUnreadCount > 0) {
            badgeCountView.setText(totalUnreadCount > 99 ? "99+" : String.valueOf(totalUnreadCount));
            badgeCountView.setVisibility(android.view.View.VISIBLE);
        } else {
            badgeCountView.setVisibility(android.view.View.GONE);
        }
    }

    public void startPolling() {
        stopPolling();

        pollingRunnable = new Runnable() {
            @Override
            public void run() {
                if (messagingManager != null) {
                    messagingManager.fetchMessagesFromServer(new MiraMessagingManager.OnMessagesLoadedListener() {
                        @Override
                        public void onMessagesLoaded(List<MessageModel> messages) {
                            Log.d(TAG, "Polling: Messages updated, count=" + (messages != null ? messages.size() : 0));
                        }

                        @Override
                        public void onError(String error) {
                            Log.w(TAG, "Polling: Failed to fetch messages: " + error);
                        }
                    });

                    messagingManager.fetchNotificationsFromServer(new MiraMessagingManager.OnNotificationsLoadedListener() {
                        @Override
                        public void onNotificationsLoaded(List<NotificationModel> notifications) {
                            Log.d(TAG, "Polling: Notifications updated, count=" + (notifications != null ? notifications.size() : 0));
                        }

                        @Override
                        public void onError(String error) {
                            Log.w(TAG, "Polling: Failed to fetch notifications: " + error);
                        }
                    });
                }

                handler.postDelayed(this, POLL_INTERVAL_MS);
            }
        };

        handler.postDelayed(pollingRunnable, INITIAL_DELAY_MS);
    }

    public void stopPolling() {
        if (handler != null && pollingRunnable != null) {
            handler.removeCallbacks(pollingRunnable);
            pollingRunnable = null;
        }
    }

    public void cleanup() {
        stopPolling();
        if (messagingManager != null) {
            messagingManager.removeMessageCountListener(this::onMessageCountChanged);
            messagingManager.removeNotificationCountListener(this::onNotificationCountChanged);
        }
    }
}
