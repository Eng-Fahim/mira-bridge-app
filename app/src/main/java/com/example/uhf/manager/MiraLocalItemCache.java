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
    
    public void cacheItem(String code, JSONObject item) {
        try {
            itemCache.put(code, item);
            saveCache();
        } catch (Exception e) {
            Log.e(TAG, "cacheItem error: " + e.getMessage());
        }
    }
    
    public JSONObject findItem(String code) {
        if (code == null || code.isEmpty()) return null;
        
        JSONObject item = itemCache.get(code);
        if (item != null) return item;
        
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
    
    public void markItemAsSold(String epc) {
        try {
            String itemsJson = prefs.getString(ITEMS_KEY, "{}");
            JSONObject allItems = new JSONObject(itemsJson);
            
            java.util.Iterator<String> keys = allItems.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject item = allItems.optJSONObject(key);
                if (item != null) {
                    String itemEpc = item.optString("epc", "");
                    String serial = item.optString("serial", "");
                    
                    if (epc.equals(itemEpc) || epc.equals(serial)) {
                        item.put("status", "sold");
                        allItems.put(key, item);
                        break;
                    }
                }
            }
            
            prefs.edit().putString(ITEMS_KEY, allItems.toString()).apply();
            itemCache.clear();
            loadCache();
            
            Log.d(TAG, "✅ Item marked as sold: " + epc);
        } catch (Exception e) {
            Log.e(TAG, "markItemAsSold error: " + e.getMessage());
        }
    }
    
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
 * ✅ البحث بالـ EPC
 */
public JSONObject findByEpc(String epc, int branchId) {
    try {
        if (epc == null || epc.isEmpty()) return null;
        
        for (JSONObject item : itemCache.values()) {
            String itemEpc = item.optString("epc", "");
            if (itemEpc.equalsIgnoreCase(epc)) {
                return item;
            }
        }
        return null;
    } catch (Exception e) {
        Log.e(TAG, "findByEpc error: " + e.getMessage());
        return null;
    }
}

/**
 * ✅ البحث بالـ Serial Number
 */
public JSONObject findBySerialNumber(String serial, int branchId) {
    try {
        if (serial == null || serial.isEmpty()) return null;
        
        for (JSONObject item : itemCache.values()) {
            String itemSerial = item.optString("serial", item.optString("serial_number", ""));
            if (itemSerial.equalsIgnoreCase(serial)) {
                return item;
            }
        }
        return null;
    } catch (Exception e) {
        Log.e(TAG, "findBySerialNumber error: " + e.getMessage());
        return null;
    }
}

/**
 * ✅ البحث بالـ GTIN
 */
public JSONObject findByGtin(String gtin, int branchId) {
    try {
        if (gtin == null || gtin.isEmpty()) return null;
        
        for (JSONObject item : itemCache.values()) {
            String itemGtin = item.optString("gtin", item.optString("gtin13", ""));
            if (itemGtin.equalsIgnoreCase(gtin)) {
                return item;
            }
        }
        return null;
    } catch (Exception e) {
        Log.e(TAG, "findByGtin error: " + e.getMessage());
        return null;
    }
}

/**
 * ✅ البحث بالباركود
 */
public JSONObject findByBarcode(String barcode, int branchId) {
    try {
        if (barcode == null || barcode.isEmpty()) return null;
        
        for (JSONObject item : itemCache.values()) {
            String itemBarcode = item.optString("barcode", "");
            if (itemBarcode.equalsIgnoreCase(barcode)) {
                return item;
            }
        }
        return null;
    } catch (Exception e) {
        Log.e(TAG, "findByBarcode error: " + e.getMessage());
        return null;
    }
}

/**
 * ✅ البحث الشامل - يبحث في جميع الحقول
 */
public JSONObject findItemComprehensive(String code) {
    try {
        if (code == null || code.isEmpty()) return null;
        
        // ✅ 1. البحث المباشر في المفتاح
        JSONObject directMatch = itemCache.get(code);
        if (directMatch != null) {
            Log.d(TAG, "✅ Direct match: " + code);
            return directMatch;
        }
        
        // ✅ 2. البحث في جميع الحقول
        for (JSONObject item : itemCache.values()) {
            String epc = item.optString("epc", "");
            String serial = item.optString("serial", item.optString("serial_number", ""));
            String gtin = item.optString("gtin", item.optString("gtin13", ""));
            String barcode = item.optString("barcode", "");
            String rfidEpc = item.optString("rfid_epc", "");
            String itemId = item.optString("id", item.optString("item_id", ""));
            
            if (code.equalsIgnoreCase(epc) || 
                code.equalsIgnoreCase(serial) || 
                code.equalsIgnoreCase(gtin) || 
                code.equalsIgnoreCase(barcode) || 
                code.equalsIgnoreCase(rfidEpc) ||
                code.equalsIgnoreCase(itemId)) {
                Log.d(TAG, "✅ Found in field search: " + code);
                return item;
            }
        }
        
        Log.d(TAG, "❌ Not found: " + code);
        return null;
    } catch (Exception e) {
        Log.e(TAG, "findItemComprehensive error: " + e.getMessage());
        return null;
    }
}

/**
 * ✅ الحصول على جميع العناصر لفرع معين
 */
public java.util.List<JSONObject> getAllItems(int branchId) {
    java.util.List<JSONObject> items = new java.util.ArrayList<>();
    try {
        for (JSONObject item : itemCache.values()) {
            // ✅ تجنب التكرار
            boolean exists = false;
            for (JSONObject existing : items) {
                if (existing == item) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                items.add(item);
            }
        }
    } catch (Exception e) {
        Log.e(TAG, "getAllItems error: " + e.getMessage());
    }
    return items;
}
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
    
    public void updateLastSyncTime() {
        prefs.edit().putLong(LAST_SYNC_KEY, System.currentTimeMillis()).apply();
    }
    
    public long getLastSyncTime() {
        return prefs.getLong(LAST_SYNC_KEY, 0);
    }
    
    public int getCachedItemCount() {
        return itemCache.size();
    }
    
    public void clearCache() {
        itemCache.clear();
        prefs.edit().remove(ITEMS_KEY).apply();
    }
}
