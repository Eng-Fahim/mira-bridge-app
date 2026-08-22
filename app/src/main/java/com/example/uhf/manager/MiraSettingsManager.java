package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MiraSettingsManager - Singleton
 * 
 * يدير إعدادات تطبيق MIRA Bridge مركزياً
 * - حفظ/استرجاع الإعدادات
 * - إشعار جميع أجزاء التطبيق فوراً عند تغيير أي إعداد
 * - دعم المستمعين (Listeners) للتحديث الحي
 */
public class MiraSettingsManager {
    
    private static MiraSettingsManager instance;
    private SharedPreferences prefs;
    private Map<String, CopyOnWriteArrayList<SettingsChangeListener>> listeners;
    
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
        listeners = new HashMap<>();
    }
    
    // =============================================
    // 🟢 الحصول على النسخة الوحيدة
    // =============================================
    public static synchronized MiraSettingsManager getInstance(Context context) {
        if (instance == null) {
            instance = new MiraSettingsManager(context);
        }
        return instance;
    }
    
    // =============================================
    // 🟢 حفظ الإعدادات
    // =============================================
    
    /**
     * حفظ إعداد نصي
     */
    public void saveSetting(String key, String value) {
        prefs.edit().putString(key, value).apply();
        notifyListeners(key, value);
    }
    
    /**
     * حفظ إعداد منطقي
     */
    public void saveSetting(String key, boolean value) {
        prefs.edit().putBoolean(key, value).apply();
        notifyListeners(key, value);
    }
    
    /**
     * حفظ إعداد رقمي
     */
    public void saveSetting(String key, int value) {
        prefs.edit().putInt(key, value).apply();
        notifyListeners(key, value);
    }
    
    /**
     * حفظ إعداد عشري
     */
    public void saveSetting(String key, float value) {
        prefs.edit().putFloat(key, value).apply();
        notifyListeners(key, value);
    }
    
    /**
     * حفظ إعداد طويل
     */
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
    // 🟢 دوال عامة لاسترجاع الإعدادات (متوافقة مع getSetting)
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
    
    /**
     * تسجيل مستمع للتغييرات على جميع الإعدادات
     * @param tag معرف فريد للمستمع (مثل: "scan_fragment", "radar_fragment")
     * @param listener كائن المستمع
     */
    public void registerListener(String tag, SettingsChangeListener listener) {
        if (!listeners.containsKey(tag)) {
            listeners.put(tag, new CopyOnWriteArrayList<>());
        }
        listeners.get(tag).add(listener);
    }
    
    /**
     * تسجيل مستمع لإعداد محدد فقط
     * @param tag معرف فريد
     * @param key مفتاح الإعداد المراد مراقبته
     * @param listener كائن المستمع
     */
    public void registerListener(String tag, String key, SettingsChangeListener listener) {
        String combinedTag = tag + "::" + key;
        if (!listeners.containsKey(combinedTag)) {
            listeners.put(combinedTag, new CopyOnWriteArrayList<>());
        }
        listeners.get(combinedTag).add(listener);
    }
    
    /**
     * إلغاء تسجيل جميع مستمعي tag معين
     */
    public void unregisterListener(String tag) {
        listeners.remove(tag);
        
        // حذف جميع المفاتيح المركبة
        String prefix = tag + "::";
        for (String key : listeners.keySet()) {
            if (key.startsWith(prefix)) {
                listeners.remove(key);
            }
        }
    }
    
    /**
     * إلغاء تسجيل جميع المستمعين
     */
    public void unregisterAll() {
        listeners.clear();
    }
    
    // =============================================
    // 🟢 إشعار المستمعين
    // =============================================
    
    private void notifyListeners(String key, Object value) {
        // إشعار المستمعين العامين
        for (Map.Entry<String, CopyOnWriteArrayList<SettingsChangeListener>> entry : listeners.entrySet()) {
            if (!entry.getKey().contains("::")) {
                for (SettingsChangeListener listener : entry.getValue()) {
                    try {
                        listener.onSettingChanged(key, value);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        
        // إشعار المستمعين المخصصين لمفتاح محدد
        for (Map.Entry<String, CopyOnWriteArrayList<SettingsChangeListener>> entry : listeners.entrySet()) {
            if (entry.getKey().endsWith("::" + key)) {
                for (SettingsChangeListener listener : entry.getValue()) {
                    try {
                        listener.onSettingChanged(key, value);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }
    
    // =============================================
    // 🟢 دوال مساعدة
    // =============================================
    
    /**
     * التحقق من وجود إعداد
     */
    public boolean contains(String key) {
        return prefs.contains(key);
    }
    
    /**
     * حذف إعداد محدد
     */
    public void removeSetting(String key) {
        prefs.edit().remove(key).apply();
        notifyListeners(key, null);
    }
    
    /**
     * مسح جميع الإعدادات
     */
    public void clearAll() {
        prefs.edit().clear().apply();
        // إشعار المستمعين بمسح الكل
        for (CopyOnWriteArrayList<SettingsChangeListener> list : listeners.values()) {
            for (SettingsChangeListener listener : list) {
                try {
                    listener.onSettingChanged("__ALL_CLEARED__", null);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }
    
    /**
     * الحصول على جميع الإعدادات كـ Map
     */
    public Map<String, ?> getAll() {
        return prefs.getAll();
    }
    
    /**
     * طباعة جميع الإعدادات (للتشخيص)
     */
    public void dumpSettings() {
        Map<String, ?> all = prefs.getAll();
        StringBuilder sb = new StringBuilder();
        sb.append("=== MIRA Bridge Settings ===\n");
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            sb.append(entry.getKey()).append(" = ").append(entry.getValue()).append("\n");
        }
        sb.append("============================");
        android.util.Log.d("MiraSettingsManager", sb.toString());
    }
}
