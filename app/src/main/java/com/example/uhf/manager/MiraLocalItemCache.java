// ==================== ملف جديد: MiraLocalItemCache.java ====================
package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * ✅ مخزن محلي للقطع - يعمل بدون انترنت
 */
public class MiraLocalItemCache {
    
    private static final String TAG = "MiraLocalItemCache";
    private static final String CACHE_PREFS = "mira_item_cache";
    private static final String ITEMS_KEY = "cached_items";
    private static final String LAST_SYNC_KEY = "last_sync_time";
    
    private static MiraLocalItemCache instance;
    private final SharedPreferences prefs;
    private final Map<String, JSONObject> itemCache = new HashMap<>();
    
    public static synchronized MiraLocalItemCache getInstance(Context context) {
        if (instance == null) {
            instance = new MiraLocalItemCache(context.getApplicationContext());
        }
        return instance;
    }
    
    private MiraLocalItemCache(Context context) {
        prefs = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE);
        loadCache();
    }
    
    /**
     * ✅ تحميل الكاش من الذاكرة
     */
    private void loadCache() {
        try {
            String json = prefs.getString(ITEMS_KEY, "{}");
            JSONObject allItems = new JSONObject(json);
            
            java.util.Iterator<String> keys = allItems.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject item = allItems.optJSONObject(key);
                if (item != null) {
                    itemCache.put(key, item);
                }
            }
            
            Log.d(TAG, "Loaded " + itemCache.size() + " items from cache");
        } catch (Exception e) {
            Log.e(TAG, "loadCache error: " + e.getMessage());
        }
    }
    
    /**
     * ✅ حفظ قطعة في الكاش
     */
    public void cacheItem(String code, JSONObject item) {
        try {
            itemCache.put(code, item);
            saveCache();
        } catch (Exception e) {
            Log.e(TAG, "cacheItem error: " + e.getMessage());
        }
    }
    
    /**
     * ✅ البحث عن قطعة في الكاش
     */
    public JSONObject findItem(String code) {
        if (code == null || code.isEmpty()) return null;
        
        // البحث المباشر
        JSONObject item = itemCache.get(code);
        if (item != null) return item;
        
        // البحث في الحقول البديلة
        for (JSONObject cachedItem : itemCache.values()) {
            try {
                String serial = cachedItem.optString("serial", "");
                String gtin = cachedItem.optString("gtin", "");
                String epc = cachedItem.optString("epc", "");
                
                if (code.equals(serial) || code.equals(gtin) || code.equals(epc)) {
                    return cachedItem;
                }
            } catch (Exception e) {}
        }
        
        return null;
    }
    
    /**
     * ✅ حفظ جميع القطع في الكاش
     */
    public void cacheAllItems(JSONArray items) {
        try {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null) {
                    String serial = item.optString("serial", item.optString("serial_number", ""));
                    String gtin = item.optString("gtin", item.optString("gtin13", ""));
                    String epc = item.optString("epc", item.optString("rfid_epc", ""));
                    
                    if (!serial.isEmpty()) itemCache.put(serial, item);
                    if (!gtin.isEmpty()) itemCache.put(gtin, item);
                    if (!epc.isEmpty()) itemCache.put(epc, item);
                }
            }
            saveCache();
            Log.d(TAG, "Cached " + items.length() + " items, total cache: " + itemCache.size());
        } catch (Exception e) {
            Log.e(TAG, "cacheAllItems error: " + e.getMessage());
        }
    }
    
    /**
     * ✅ حفظ الكاش في الذاكرة الدائمة
     */
    private void saveCache() {
        try {
            JSONObject allItems = new JSONObject();
            for (Map.Entry<String, JSONObject> entry : itemCache.entrySet()) {
                allItems.put(entry.getKey(), entry.getValue());
            }
            prefs.edit().putString(ITEMS_KEY, allItems.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "saveCache error: " + e.getMessage());
        }
    }
    
    /**
     * ✅ تحديث وقت آخر مزامنة
     */
    public void updateLastSyncTime() {
        prefs.edit().putLong(LAST_SYNC_KEY, System.currentTimeMillis()).apply();
    }
    
    /**
     * ✅ الحصول على وقت آخر مزامنة
     */
    public long getLastSyncTime() {
        return prefs.getLong(LAST_SYNC_KEY, 0);
    }
    
    /**
     * ✅ الحصول على عدد القطع المخزنة
     */
    public int getCachedItemCount() {
        return itemCache.size();
    }
    
    /**
     * ✅ مسح الكاش
     */
    public void clearCache() {
        itemCache.clear();
        prefs.edit().remove(ITEMS_KEY).apply();
    }
}
