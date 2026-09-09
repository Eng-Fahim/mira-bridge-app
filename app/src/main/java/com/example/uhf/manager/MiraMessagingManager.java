package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.uhf.api.MiraApiClient;
import com.example.uhf.model.MessageModel;
import com.example.uhf.model.NotificationModel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class MiraMessagingManager {

    private static final String TAG = "MiraMessagingManager";
    private static final String PREF_NAME = "mira_messaging_prefs";
    private static final String KEY_UNREAD_MESSAGES = "unread_messages_count";
    private static final String KEY_UNREAD_NOTIFICATIONS = "unread_notifications_count";
    private static final String KEY_USER_ID = "current_user_id";
    
    private static MiraMessagingManager instance;
    private final Context context;
    private final SharedPreferences prefs;
    private final MiraApiClient apiClient;
    
    // ✅ فصل العدادات
    private final List<OnMessageCountChangedListener> messageListeners = new CopyOnWriteArrayList<>();
    private final List<OnNotificationCountChangedListener> notificationListeners = new CopyOnWriteArrayList<>();
    
    // ✅ فصل الكاش
    private final List<MessageModel> messageCache = new CopyOnWriteArrayList<>();
    private final List<NotificationModel> notificationCache = new CopyOnWriteArrayList<>();
    
    // ✅ واجهات منفصلة
    public interface OnMessageCountChangedListener {
        void onMessageCountChanged(int unreadCount);
    }
    
    public interface OnNotificationCountChangedListener {
        void onNotificationCountChanged(int unreadCount);
    }

    public interface OnMessagesLoadedListener {
        void onMessagesLoaded(List<MessageModel> messages);
        void onError(String error);
    }

    public interface OnNotificationsLoadedListener {
        void onNotificationsLoaded(List<NotificationModel> notifications);
        void onError(String error);
    }

    private MiraMessagingManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.apiClient = MiraApiClient.getInstance(context);
    }

    public static synchronized MiraMessagingManager getInstance(Context context) {
        if (instance == null) {
            instance = new MiraMessagingManager(context);
        }
        return instance;
    }

    // ==================== Message Listeners ====================
    
    public void addMessageCountListener(OnMessageCountChangedListener listener) {
        if (listener != null && !messageListeners.contains(listener)) {
            messageListeners.add(listener);
        }
    }

    public void removeMessageCountListener(OnMessageCountChangedListener listener) {
        messageListeners.remove(listener);
    }
    
    // ==================== Notification Listeners ====================
    
    public void addNotificationCountListener(OnNotificationCountChangedListener listener) {
        if (listener != null && !notificationListeners.contains(listener)) {
            notificationListeners.add(listener);
        }
    }

    public void removeNotificationCountListener(OnNotificationCountChangedListener listener) {
        notificationListeners.remove(listener);
    }

    // ==================== Message Count ====================
    
    public int getUnreadMessageCount() {
        return prefs.getInt(KEY_UNREAD_MESSAGES, 0);
    }

    public void setUnreadMessageCount(int count) {
        prefs.edit().putInt(KEY_UNREAD_MESSAGES, Math.max(0, count)).apply();
        notifyMessageListeners(Math.max(0, count));
    }
    
    // ==================== Notification Count ====================
    
    public int getUnreadNotificationCount() {
        return prefs.getInt(KEY_UNREAD_NOTIFICATIONS, 0);
    }

    public void setUnreadNotificationCount(int count) {
        prefs.edit().putInt(KEY_UNREAD_NOTIFICATIONS, Math.max(0, count)).apply();
        notifyNotificationListeners(Math.max(0, count));
    }
    
    // ==================== Total Badge Count ====================
    
    public int getTotalUnreadCount() {
        return getUnreadMessageCount() + getUnreadNotificationCount();
    }

    // ==================== Message API ====================
    
    public void fetchMessagesFromServer(OnMessagesLoadedListener listener) {
        String userId = getCurrentUserId();
        if (userId == null || userId.isEmpty()) {
            if (listener != null) listener.onError("User ID not found");
            return;
        }

        apiClient.getAllMessages(userId, new MiraApiClient.MessageCallback() {
            @Override
            public void onSuccess(List<MessageModel> messages) {
                messageCache.clear();
                if (messages != null) {
                    messageCache.addAll(messages);
                }
                
                int unreadCount = 0;
                for (MessageModel message : messageCache) {
                    if (!message.isRead()) {
                        unreadCount++;
                    }
                }
                setUnreadMessageCount(unreadCount);
                
                if (listener != null) listener.onMessagesLoaded(new ArrayList<>(messageCache));
            }

            @Override
            public void onError(String error) {
                if (listener != null) listener.onError(error);
            }
        });
    }

    public void sendMessage(String receiverId, String content, MiraApiClient.SendMessageCallback callback) {
        String userId = getCurrentUserId();
        if (userId == null || userId.isEmpty()) {
            if (callback != null) callback.onError("User ID not found");
            return;
        }
        
        if (content == null || content.trim().isEmpty()) {
            if (callback != null) callback.onError("Message content is empty");
            return;
        }

        MessageModel message = new MessageModel(userId, receiverId, content.trim());
        
        apiClient.sendMessage(message, new MiraApiClient.SendMessageCallback() {
            @Override
            public void onSuccess(MessageModel sentMessage) {
                if (sentMessage != null) {
                    messageCache.add(sentMessage);
                }
                if (callback != null) callback.onSuccess(sentMessage);
            }

            @Override
            public void onError(String error) {
                if (callback != null) callback.onError(error);
            }
        });
    }
    
    public void markConversationAsRead(String conversationId) {
        String userId = getCurrentUserId();
        if (userId == null || userId.isEmpty()) return;
        
        // TODO: استدعاء API لتعليم الرسائل كمقروءة
        // apiClient.markConversationAsRead(conversationId, userId, callback);
    }
    
    // ==================== Notification API ====================
    
    public void fetchNotificationsFromServer(OnNotificationsLoadedListener listener) {
        String userId = getCurrentUserId();
        if (userId == null || userId.isEmpty()) {
            if (listener != null) listener.onError("User ID not found");
            return;
        }

        apiClient.getNotifications(userId, new MiraApiClient.NotificationCallback() {
            @Override
            public void onSuccess(List<NotificationModel> notifications) {
                notificationCache.clear();
                if (notifications != null) {
                    notificationCache.addAll(notifications);
                }
                
                int unreadCount = 0;
                for (NotificationModel notification : notificationCache) {
                    if (!notification.isRead()) {
                        unreadCount++;
                    }
                }
                setUnreadNotificationCount(unreadCount);
                
                if (listener != null) listener.onNotificationsLoaded(new ArrayList<>(notificationCache));
            }

            @Override
            public void onError(String error) {
                if (listener != null) listener.onError(error);
            }
        });
    }

    // ==================== Helper Methods ====================
    
    private String getCurrentUserId() {
        return prefs.getString(KEY_USER_ID, null);
    }

    public void setCurrentUserId(String userId) {
        prefs.edit().putString(KEY_USER_ID, userId).apply();
    }

    public List<MessageModel> getCachedMessages() {
        return new ArrayList<>(messageCache);
    }

    public List<NotificationModel> getCachedNotifications() {
        return new ArrayList<>(notificationCache);
    }
    // ✅ أضف هذه الواجهة داخل الكلاس
public interface OnUnreadCountChangedListener {
    void onUnreadCountChanged(int unreadCount);
}
    
    private void notifyMessageListeners(int count) {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (OnMessageCountChangedListener listener : messageListeners) {
                if (listener != null) {
                    listener.onMessageCountChanged(count);
                }
            }
        });
    }
    
    private void notifyNotificationListeners(int count) {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (OnNotificationCountChangedListener listener : notificationListeners) {
                if (listener != null) {
                    listener.onNotificationCountChanged(count);
                }
            }
        });
    }
}
