package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

public class MiraMessagingManager {

    private static final String PREF_NAME = "mira_messaging_prefs";
    private static final String KEY_UNREAD_COUNT = "unread_messages_count";
    private static MiraMessagingManager instance;
    private final Context context;
    private final SharedPreferences prefs;
    private final List<OnNotificationChangeListener> listeners = new ArrayList<>();

    public interface OnNotificationChangeListener {
        void OnNotificationCountChanged(int unreadCount);
    }

    private MiraMessagingManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized MiraMessagingManager getInstance(Context context) {
        if (instance == null) {
            instance = new MiraMessagingManager(context);
        }
        return instance;
    }

    public void addListener(OnNotificationChangeListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(OnNotificationChangeListener listener) {
        listeners.remove(listener);
    }

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

    private void notifyListeners(int count) {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (OnNotificationChangeListener listener : listeners) {
                listener.OnNotificationCountChanged(count);
            }
        });
    }
}
