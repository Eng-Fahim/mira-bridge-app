package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.example.uhf.api.MiraApiClient;
import com.example.uhf.model.MessageModel;
import com.example.uhf.model.NotificationModel;

import java.util.ArrayList;
import java.util.List;

public class MiraMessagingManager {

    private static final String PREF_NAME = "mira_messaging_prefs";
    private static final String KEY_UNREAD_COUNT = "unread_messages_count";
    private static final String KEY_USER_ID = "current_user_id";
    
    private static MiraMessagingManager instance;
    private final Context context;
    private final SharedPreferences prefs;
    private final MiraApiClient apiClient; 
    
    // دعم القائمتين لمنع أي تعارض
    private final List<OnNotificationChangeListener> oldListeners = new ArrayList<>();
    private final List<OnUnreadCountChangedListener> unreadListeners = new ArrayList<>();
    
    private final List<MessageModel> messageCache = new ArrayList<>();
    private final List<NotificationModel> notificationCache = new ArrayList<>();
    
    // 1. الواجهة الجديدة المطابقة لـ UHFMainActivity
    public interface OnUnreadCountChangedListener {
        void onUnreadCountChanged(int unreadCount);
    }

    // 2. الواجهة القديمة للحفاظ على التوافق
    public interface OnNotificationChangeListener {
        void OnNotificationCountChanged(int unreadCount);
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

    // ==================== Listener Management ====================
    
    // دعم الواجهة الجديدة
    public void addListener(OnUnreadCountChangedListener listener) {
        if (listener != null && !unreadListeners.contains(listener)) {
            unreadListeners.add(listener);
        }
    }

    public void removeListener(OnUnreadCountChangedListener listener) {
        unreadListeners.remove(listener);
    }

    // دعم الواجهة القديمة (Overloading)
    public void addListener(OnNotificationChangeListener listener) {
        if (listener != null && !oldListeners.contains(listener)) {
            oldListeners.add(listener);
        }
    }

    public void removeListener(OnNotificationChangeListener listener) {
        oldListeners.remove(listener);
    }

    // ==================== Unread Count Management ====================
    
    public int getUnreadCount() {
        return prefs.getInt(KEY_UNREAD_COUNT, 0);
    }

    public void setUnreadCount(int count) {
        prefs.edit().putInt(KEY_UNREAD_COUNT, count).apply();
        notifyListeners(count);
    }

    public void incrementUnreadCount() {
        int newCount = getUnreadCount() + 1;
        setUnreadCount(newCount);
    }

    public void clearUnreadCount() {
        setUnreadCount(0);
    }

    // ==================== API Integration ====================
    
    public void fetchUnreadCountFromServer() {
        String userId = getCurrentUserId();
        if (userId == null) return;

        apiClient.getUnreadCount(userId, new MiraApiClient.UnreadCountCallback() {
            @Override
            public void onSuccess(int unreadCount) {
                setUnreadCount(unreadCount);
            }

            @Override
            public void onError(String error) {
                // في حالة الخطأ، نحتفظ بالعداد الحالي
            }
        });
    }

    public void fetchMessagesFromServer(OnMessagesLoadedListener listener) {
        String userId = getCurrentUserId();
        if (userId == null) {
            if (listener != null) listener.onError("User ID not found");
            return;
        }

        apiClient.getAllMessages(userId, new MiraApiClient.MessageCallback() {
            @Override
            public void onSuccess(List<MessageModel> messages) {
                messageCache.clear();
                messageCache.addAll(messages);
                
                int unreadCount = 0;
                for (MessageModel message : messages) {
                    if (!message.isRead()) {
                        unreadCount++;
                    }
                }
                setUnreadCount(unreadCount);
                
                if (listener != null) listener.onMessagesLoaded(messages);
            }

            @Override
            public void onError(String error) {
                if (listener != null) listener.onError(error);
            }
        });
    }

    public void fetchNotificationsFromServer(OnNotificationsLoadedListener listener) {
        String userId = getCurrentUserId();
        if (userId == null) {
            if (listener != null) listener.onError("User ID not found");
            return;
        }

        apiClient.getNotifications(userId, new MiraApiClient.NotificationCallback() {
            @Override
            public void onSuccess(List<NotificationModel> notifications) {
                notificationCache.clear();
                notificationCache.addAll(notifications);
                if (listener != null) listener.onNotificationsLoaded(notifications);
            }

            @Override
            public void onError(String error) {
                if (listener != null) listener.onError(error);
            }
        });
    }

    public void sendMessage(String receiverId, String content, MiraApiClient.SendMessageCallback callback) {  
        String userId = getCurrentUserId();
        if (userId == null) {
            if (callback != null) callback.onError("User ID not found");
            return;
        }

        MessageModel message = new MessageModel(userId, receiverId, content);
        
        apiClient.sendMessage(message, new MiraApiClient.SendMessageCallback() {
            @Override
            public void onSuccess(MessageModel sentMessage) {
                messageCache.add(sentMessage);
                if (callback != null) callback.onSuccess(sentMessage);
            }

            @Override
            public void onError(String error) {
                if (callback != null) callback.onError(error);
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

    private void notifyListeners(int count) {
        new Handler(Looper.getMainLooper()).post(() -> {
            // إشعار المستمعين للواجهة الجديدة
            for (OnUnreadCountChangedListener listener : new ArrayList<>(unreadListeners)) {
                if (listener != null) {
                    listener.onUnreadCountChanged(count);
                }
            }
            // إشعار المستمعين للواجهة القديمة
            for (OnNotificationChangeListener listener : new ArrayList<>(oldListeners)) {
                if (listener != null) {
                    listener.OnNotificationCountChanged(count);
                }
            }
        });
    }
}
