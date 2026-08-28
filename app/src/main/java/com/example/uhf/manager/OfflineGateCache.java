package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * ✅ التخزين المحلي للعمل بدون إنترنت
 */
public class OfflineGateCache {

    private static final String TAG = "OfflineGateCache";
    private static final String PREFS_NAME = "mira_offline_gate_cache";
    
    private static OfflineGateCache instance;
    private final SharedPreferences prefs;
    
    private OfflineGateCache(Context context) {
        prefs = context.getApplicationContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
    
    public static synchronized OfflineGateCache getInstance(Context context) {
        if (instance == null) {
            instance = new OfflineGateCache(context);
        }
        return instance;
    }
    
    // ============================================
    // ✅ القائمة المسموحة (Allowed EPCs)
    // ============================================
    
    public void saveAllowedEpcs(Set<String> allowedEpcs) {
        prefs.edit().putStringSet("allowed_epcs", allowedEpcs).apply();
        Log.d(TAG, "✅ Allowed EPCs cached: " + allowedEpcs.size());
    }
    
    public Set<String> getAllowedEpcs() {
        return new HashSet<>(prefs.getStringSet("allowed_epcs", new HashSet<>()));
    }
    
    public boolean isAllowedLocally(String epc) {
        return getAllowedEpcs().contains(epc);
    }
    
    // ============================================
    // ✅ سجل الأحداث المحلي
    // ============================================
    
    public void saveOfflineEvent(String epc, boolean allowed, String itemTitle, String gateId) {
        JSONObject event = new JSONObject();
        try {
            event.put("epc", epc);
            event.put("allowed", allowed);
            event.put("item_title", itemTitle);
            event.put("gate_id", gateId);
            event.put("timestamp", System.currentTimeMillis());
            
            JSONArray events = getOfflineEvents();
            events.put(event);
            
            if (events.length() > 1000) {
                JSONArray trimmed = new JSONArray();
                for (int i = events.length() - 1000; i < events.length(); i++) {
                    trimmed.put(events.optJSONObject(i));
                }
                events = trimmed;
            }
            
            prefs.edit().putString("offline_events", events.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "Save offline event: " + e.getMessage());
        }
    }
    
    public JSONArray getOfflineEvents() {
        String json = prefs.getString("offline_events", "[]");
        try {
            return new JSONArray(json);
        } catch (Exception e) {
            return new JSONArray();
        }
    }
    
    public int getPendingEventsCount() {
        return getOfflineEvents().length();
    }
    
    public void clearOfflineEvents() {
        prefs.edit().remove("offline_events").apply();
    }
    
    // ============================================
    // ✅ حالة الاتصال
    // ============================================
    
    public void saveConnectionState(boolean isOnline) {
        prefs.edit().putBoolean("is_online", isOnline).apply();
    }
    
    public boolean isOnline() {
        return prefs.getBoolean("is_online", true);
    }
    
    public void saveLastSyncTime(long timestamp) {
        prefs.edit().putLong("last_sync", timestamp).apply();
    }
    
    public long getLastSyncTime() {
        return prefs.getLong("last_sync", 0);
    }
}
