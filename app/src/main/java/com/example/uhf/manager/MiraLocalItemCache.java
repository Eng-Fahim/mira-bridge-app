package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ✅ MiraLocalItemCache v2.0 — مخزن محلي للقطع
 */
public class MiraLocalItemCache {

    private static final String TAG = "MiraLocalItemCache";
    private static final String CACHE_PREFS = "mira_item_cache";
    private static final String ITEMS_KEY = "cached_items";
    private static final String LAST_SYNC_KEY = "last_sync_time";

    private static MiraLocalItemCache instance;

    private final SharedPreferences prefs;

    /** فهرس البحث: identifier → JSONObject */
    private final Map<String, JSONObject> itemCache = new HashMap<>();

    /** العناصر الفريدة: primary_key → JSONObject */
    private final Map<String, JSONObject> uniqueItems = new HashMap<>();

    // ==================== Singleton ====================

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

    // ==================== Load ====================

    private void loadCache() {
        try {
            String json = prefs.getString(ITEMS_KEY, "{}");
            JSONObject allItems = new JSONObject(json);

            itemCache.clear();
            uniqueItems.clear();

            java.util.Iterator<String> keys = allItems.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject item = allItems.optJSONObject(key);
                if (item != null) {
                    registerItem(item);
                }
            }

            Log.d(TAG, "✅ Loaded " + uniqueItems.size()
                    + " unique items (indexed as "
                    + itemCache.size() + " entries)");

        } catch (Exception e) {
            Log.e(TAG, "loadCache error: " + e.getMessage(), e);
        }
    }

    // ==================== Register Item ====================

    private void registerItem(JSONObject item) {
        if (item == null) return;

        try {
            String epc = item.optString("epc", "");
            String rfidEpc = item.optString("rfid_epc", "");
            String serial = item.optString("serial",
                    item.optString("serial_number", ""));
            String gtin = item.optString("gtin",
                    item.optString("gtin13", ""));
            String barcode = item.optString("barcode", "");
            String id = item.optString("id",
                    item.optString("item_id", ""));

            String primaryKey = !epc.isEmpty() ? epc
                    : (!serial.isEmpty() ? "SN:" + serial
                    : (!id.isEmpty() ? "ID:" + id
                    : "UNKNOWN:" + System.identityHashCode(item)));

            uniqueItems.put(primaryKey, item);

            if (!epc.isEmpty()) itemCache.put(epc, item);
            if (!rfidEpc.isEmpty() && !rfidEpc.equals(epc)) {
                itemCache.put(rfidEpc, item);
            }
            if (!serial.isEmpty()) itemCache.put(serial, item);
            if (!gtin.isEmpty()) itemCache.put(gtin, item);
            if (!barcode.isEmpty()) itemCache.put(barcode, item);
            if (!id.isEmpty()) itemCache.put(id, item);

        } catch (Exception e) {
            Log.e(TAG, "registerItem error: " + e.getMessage());
        }
    }

    // ==================== Cache Operations ====================

    public void cacheItem(String code, JSONObject item) {
        if (item == null) return;

        try {
            if (code != null && !code.isEmpty()) {
                itemCache.put(code, item);
            }
            registerItem(item);
            saveCache();

        } catch (Exception e) {
            Log.e(TAG, "cacheItem error: " + e.getMessage(), e);
        }
    }

    public void cacheAllItems(JSONArray items) {
        if (items == null) return;

        try {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null) {
                    registerItem(item);
                }
            }

            saveCache();
            Log.d(TAG, "✅ Cached " + items.length()
                    + " items, unique: " + uniqueItems.size()
                    + ", indexed: " + itemCache.size());

        } catch (Exception e) {
            Log.e(TAG, "cacheAllItems error: " + e.getMessage(), e);
        }
    }

    // ==================== Lookup ====================

    public JSONObject findItemComprehensive(String code) {
        try {
            if (code == null || code.isEmpty()) return null;

            String cleanCode = code.trim();

            JSONObject direct = itemCache.get(cleanCode);
            if (direct != null) {
                Log.d(TAG, "✅ Direct match: " + cleanCode);
                return direct;
            }

            for (Map.Entry<String, JSONObject> entry : itemCache.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(cleanCode)) {
                    Log.d(TAG, "✅ Case-insensitive match: " + cleanCode);
                    return entry.getValue();
                }
            }

            for (JSONObject item : uniqueItems.values()) {
                if (matchesAnyField(item, cleanCode)) {
                    Log.d(TAG, "✅ Field match: " + cleanCode);
                    return item;
                }
            }

            Log.d(TAG, "❌ Not found: " + cleanCode);
            return null;

        } catch (Exception e) {
            Log.e(TAG, "findItemComprehensive error: " + e.getMessage());
            return null;
        }
    }

    private boolean matchesAnyField(JSONObject item, String code) {
        try {
            String epc = item.optString("epc", "");
            String serial = item.optString("serial",
                    item.optString("serial_number", ""));
            String gtin = item.optString("gtin",
                    item.optString("gtin13", ""));
            String barcode = item.optString("barcode", "");
            String rfidEpc = item.optString("rfid_epc", "");
            String id = item.optString("id",
                    item.optString("item_id", ""));

            return code.equalsIgnoreCase(epc)
                    || code.equalsIgnoreCase(serial)
                    || code.equalsIgnoreCase(gtin)
                    || code.equalsIgnoreCase(barcode)
                    || code.equalsIgnoreCase(rfidEpc)
                    || code.equalsIgnoreCase(id);

        } catch (Exception e) {
            return false;
        }
    }

    public JSONObject findItem(String code) {
        return findItemComprehensive(code);
    }

    // ==================== Specialized Finders ====================

    public JSONObject findByEpc(String epc, int branchId) {
        if (epc == null || epc.isEmpty()) return null;
        JSONObject item = itemCache.get(epc);
        if (item != null) return item;
        for (Map.Entry<String, JSONObject> e : itemCache.entrySet()) {
            if (e.getKey().equalsIgnoreCase(epc)) return e.getValue();
        }
        return null;
    }

    public JSONObject findBySerialNumber(String serial, int branchId) {
        if (serial == null || serial.isEmpty()) return null;
        for (JSONObject item : uniqueItems.values()) {
            String s = item.optString("serial",
                    item.optString("serial_number", ""));
            if (s.equalsIgnoreCase(serial)) return item;
        }
        return null;
    }

    public JSONObject findByGtin(String gtin, int branchId) {
        if (gtin == null || gtin.isEmpty()) return null;
        for (JSONObject item : uniqueItems.values()) {
            String g = item.optString("gtin",
                    item.optString("gtin13", ""));
            if (g.equalsIgnoreCase(gtin)) return item;
        }
        return null;
    }

    public JSONObject findByBarcode(String barcode, int branchId) {
        if (barcode == null || barcode.isEmpty()) return null;
        for (JSONObject item : uniqueItems.values()) {
            String b = item.optString("barcode", "");
            if (b.equalsIgnoreCase(barcode)) return item;
        }
        return null;
    }

    // ==================== Status Updates ====================

    public void markItemAsSold(String epc) {
        if (epc == null || epc.isEmpty()) return;

        try {
            JSONObject item = findItemComprehensive(epc);
            if (item == null) {
                Log.w(TAG, "markItemAsSold: item not found: " + epc);
                return;
            }

            item.put("status", "sold");
            item.put("vault_status", "sold");
            item.put("last_update", System.currentTimeMillis());

            saveCache();
            Log.d(TAG, "✅ Item marked as sold: " + epc);

        } catch (Exception e) {
            Log.e(TAG, "markItemAsSold error: " + e.getMessage(), e);
        }
    }

    public void markItemAsAvailable(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) return;

        try {
            String code = identifier.trim();

            prefs.edit()
                 .remove("sold_" + code)
                 .remove("item_sold_" + code)
                 .apply();

            JSONObject item = findItemComprehensive(code);
            if (item != null) {
                item.put("status", "available");
                item.put("vault_status", "in_stock");
                item.put("is_refunded", true);
                item.put("last_update", System.currentTimeMillis());

                saveCache();
                Log.d(TAG, "✅ Item marked as available: " + code);
            } else {
                Log.d(TAG, "⚠️ Item not in cache: " + code);
            }

        } catch (Exception e) {
            Log.e(TAG, "markItemAsAvailable error: " + e.getMessage(), e);
        }
    }

    // ==================== Save ====================

    private void saveCache() {
        try {
            JSONObject allItems = new JSONObject();

            for (Map.Entry<String, JSONObject> entry : uniqueItems.entrySet()) {
                allItems.put(entry.getKey(), entry.getValue());
            }

            prefs.edit().putString(ITEMS_KEY, allItems.toString()).apply();
            Log.d(TAG, "💾 Saved " + uniqueItems.size() + " unique items");

        } catch (Exception e) {
            Log.e(TAG, "saveCache error: " + e.getMessage(), e);
        }
    }

    // ==================== Utility ====================

    public List<JSONObject> getAllItems(int branchId) {
        return new ArrayList<>(uniqueItems.values());
    }

    public void updateLastSyncTime() {
        prefs.edit().putLong(LAST_SYNC_KEY, System.currentTimeMillis()).apply();
    }

    public long getLastSyncTime() {
        return prefs.getLong(LAST_SYNC_KEY, 0);
    }

    public int getCachedItemCount() {
        return uniqueItems.size();
    }

    public void clearCache() {
        itemCache.clear();
        uniqueItems.clear();
        prefs.edit().remove(ITEMS_KEY).apply();
        Log.d(TAG, "🗑️ Cache cleared");
    }
}
