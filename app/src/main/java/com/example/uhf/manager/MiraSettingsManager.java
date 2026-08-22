package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MiraSettingsManager - Singleton
 * 
 * يدير إعدادات تطبيق MIRA Bridge مركزياً
 * - حفظ/استرجاع الإعدادات
 * - إشعار جميع أجزاء التطبيق فوراً عند تغيير أي إعداد
 * - دعم المستمعين (Listeners) للتحديث الحي بأمان خيطي كامل (Thread-Safe)
 */
public class MiraSettingsManager {
    
    private static final String TAG = "MiraSettingsManager";
    private static volatile MiraSettingsManager instance;
    private final SharedPreferences prefs;
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SettingsChangeListener>> listeners;
    
    // =============================================
    // 🟢 واجهة المستمع للتغييرات
    // =============================================
    public interface SettingsChangeListener {
        void onSettingChanged(String key, Object value);
    }
    
    // =============================================
    // 🟢 Constructor خاص (Singleton)
    // =============================================
    private MiraSettingsManager(Context context) {
        prefs = context.getApplicationContext()
            .getSharedPreferences("MIRA_BRIDGE_SETTINGS", Context.MODE_PRIVATE);
        listeners = new ConcurrentHashMap<>();
    }
    
    // =============================================
    // 🟢 الحصول على النسخة الوحيدة (Double-Checked Locking)
    // =============================================
    public static MiraSettingsManager getInstance(Context context) {
        if (instance == null) {
            synchronized (MiraSettingsManager.class) {
                if (instance == null) {
                    instance = new MiraSettingsManager(context);
                }
            }
        }
        return instance;
    }
    
    // =============================================
    // 🟢 حفظ الإعدادات
    // =============================================
    
    public void saveSetting(String key, String value) {
        prefs.edit().putString(key, value).apply();
        notifyListeners(key, value);
    }
    
    public void saveSetting(String key, boolean value) {
        prefs.edit().putBoolean(key, value).apply();
        notifyListeners(key, value);
    }
    
    public void saveSetting(String key, int value) {
        prefs.edit().putInt(key, value).apply();
        notifyListeners(key, value);
    }
    
    public void saveSetting(String key, float value) {
        prefs.edit().putFloat(key, value).apply();
        notifyListeners(key, value);
    }
    
    public void saveSetting(String key, long value) {
        prefs.edit().putLong(key, value).apply();
        notifyListeners(key, value);
    }
    
    // =============================================
    // 🟢 استرجاع الإعدادات
    // =============================================
    
    public String getString(String key, String defaultValue) {
        return prefs.getString(key, defaultValue);
    }
    
    public boolean getBoolean(String key, boolean defaultValue) {
        return prefs.getBoolean(key, defaultValue);
    }
    
    public int getInt(String key, int defaultValue) {
        return prefs.getInt(key, defaultValue);
    }
    
    public float getFloat(String key, float defaultValue) {
        return prefs.getFloat(key, defaultValue);
    }
    
    public long getLong(String key, long defaultValue) {
        return prefs.getLong(key, defaultValue);
    }

    // =============================================
    // 🟢 دوال متوافقة للوصول بالصيغة العامة
    // =============================================
    
    public String getSetting(String key, String defaultValue) {
        return getString(key, defaultValue);
    }

    public boolean getSetting(String key, boolean defaultValue) {
        return getBoolean(key, defaultValue);
    }

    public int getSetting(String key, int defaultValue) {
        return getInt(key, defaultValue);
    }

    public float getSetting(String key, float defaultValue) {
        return getFloat(key, defaultValue);
    }

    public long getSetting(String key, long defaultValue) {
        return getLong(key, defaultValue);
    }

    // =============================================
    // 🟢 إدارة المستمعين (Listeners)
    // =============================================
    
    public void registerListener(String tag, SettingsChangeListener listener) {
        if (listener == null) return;
        listeners.computeIfAbsent(tag, k -> new CopyOnWriteArrayList<>()).addIfAbsent(listener);
    }
    
    public void registerListener(String tag, String key, SettingsChangeListener listener) {
        if (listener == null) return;
        String combinedTag = tag + "::" + key;
        listeners.computeIfAbsent(combinedTag, k -> new CopyOnWriteArrayList<>()).addIfAbsent(listener);
    }
    
    /**
     * إلغاء تسجيل جميع مستمعي tag معين بطريقة Safe-Thread
     */
    public void unregisterListener(String tag) {
        if (tag == null) return;
        listeners.remove(tag);
        
        // حذف المفاتيح المركبة دون التسبب في ConcurrentModificationException
        String prefix = tag + "::";
        listeners.keySet().removeIf(k -> k.startsWith(prefix));
    }
    
    public void unregisterAll() {
        listeners.clear();
    }
    
    // =============================================
    // 🟢 إشعار المستمعين
    // =============================================
    
    private void notifyListeners(String key, Object value) {
        for (Map.Entry<String, CopyOnWriteArrayList<SettingsChangeListener>> entry : listeners.entrySet()) {
            String entryKey = entry.getKey();
            
            // 1. المستمعين العامين
            if (!entryKey.contains("::")) {
                notifyList(entry.getValue(), key, value);
            } 
            // 2. المستمعين الخاصين بالـ Key
            else if (entryKey.endsWith("::" + key)) {
                notifyList(entry.getValue(), key, value);
            }
        }
    }

    private void notifyList(CopyOnWriteArrayList<SettingsChangeListener> list, String key, Object value) {
        for (SettingsChangeListener listener : list) {
            try {
                listener.onSettingChanged(key, value);
            } catch (Exception e) {
                Log.e(TAG, "Error notifying listener for key: " + key, e);
            }
        }
    }
    
    // =============================================
    // 🟢 دوال مساعدة
    // =============================================
    
    public boolean contains(String key) {
        return prefs.contains(key);
    }
    
    public void removeSetting(String key) {
        prefs.edit().remove(key).apply();
        notifyListeners(key, null);
    }
    
    public void clearAll() {
        prefs.edit().clear().apply();
        for (CopyOnWriteArrayList<SettingsChangeListener> list : listeners.values()) {
            notifyList(list, "__ALL_CLEARED__", null);
        }
    }
    
    public Map<String, ?> getAll() {
        return prefs.getAll();
    }
    
    public void dumpSettings() {
        Map<String, ?> all = prefs.getAll();
        StringBuilder sb = new StringBuilder();
        sb.append("=== MIRA Bridge Settings ===\n");
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            sb.append(entry.getKey()).append(" = ").append(entry.getValue()).append("\n");
        }
        sb.append("============================");
        Log.d(TAG, sb.toString());
    }
}
