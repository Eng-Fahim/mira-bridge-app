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
 *
 * الإصلاحات:
 *   1. cacheItem: يسجّل بكل المعرّفات (epc, serial, gtin, barcode, id)
 *   2. saveCache: يخزّن العناصر الفريدة فقط (بدون تكرار)
 *   3. loadCache: يعيد بناء الفهرسة كاملة بعد إعادة التشغيل
 *   4. getAllItems: بدون تكرار
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
                    // ✅ أعِد تسجيل العنصر بكل معرّفاته
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

    /**
     * ✅ [الإصلاح] يُسجّل العنصر بكل معرّفاته في الفهرس
     */
    private void registerItem(JSONObject item) {
        if (item == null) return;

        try {
            // استخراج كل المعرّفات الممكنة
            String epc = item.optString("epc", "");
            String rfidEpc = item.optString("rfid_epc", "");
            String serial = item.optString("serial",
                    item.optString("serial_number", ""));
            String gtin = item.optString("gtin",
                    item.optString("gtin13", ""));
            String barcode = item.optString("barcode", "");
            String id = item.optString("id",
                    item.optString("item_id", ""));

            // ✅ المفتاح الأساسي (للتفريد)
            String primaryKey = !epc.isEmpty() ? epc
                    : (!serial.isEmpty() ? "SN:" + serial
                    : (!id.isEmpty() ? "ID:" + id
                    : "UNKNOWN:" + System.identityHashCode(item)));

            // ✅ سجّل في فهرس التفريد
            uniqueItems.put(primaryKey, item);

            // ✅ سجّل في فهرس البحث بكل المعرّفات
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

    /**
     * ✅ [الإصلاح] cacheItem — يسجّل بكل المعرّفات
     *     متوافق مع cacheAllItems
     */
    public void cacheItem(String code, JSONObject item) {
        if (item == null) return;

        try {
            // ✅ إذا كان الـ code يمثّل معرّفاً إضافياً، سجّله
            if (code != null && !code.isEmpty()) {
                itemCache.put(code, item);
            }

            // ✅ سجّل بكل المعرّفات الأخرى
            registerItem(item);

            // ✅ احفظ
            saveCache();

        } catch (Exception e) {
            Log.e(TAG, "cacheItem error: " + e.getMessage(), e);
        }
    }

    /**
     * ✅ [الإصلاح] cacheAllItems — يحفظ العناصر الفريدة
     *     ويعيد بناء الفهرس
     */
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

    /**
     * ✅ findItemComprehensive — بحث شامل
     */
    public JSONObject findItemComprehensive(String code) {
        try {
            if (code == null || code.isEmpty()) return null;

            String cleanCode = code.trim();

            // ✅ 1. بحث مباشر
            JSONObject direct = itemCache.get(cleanCode);
            if (direct != null) {
                Log.d(TAG, "✅ Direct match: " + cleanCode);
                return direct;
            }

            // ✅ 2. بحث مباشر (case-insensitive)
            for (Map.Entry<String, JSONObject> entry : itemCache.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(cleanCode)) {
                    Log.d(TAG, "✅ Case-insensitive match: " + cleanCode);
                    return entry.getValue();
                }
            }

            // ✅ 3. بحث في الحقول (احتياطي)
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

    /**
     * ✅ [مساعد] هل يطابق العنصر أياً من حقوله؟
     */
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

    /**
     * ✅ findItem — التوافق مع الكود القديم
     */
    public JSONObject findItem(String code) {
        return findItemComprehensive(code);
    }

    // ==================== Specialized Finders ====================

    public JSONObject findByEpc(String epc, int branchId) {
        if (epc == null || epc.isEmpty()) return null;
        JSONObject item = itemCache.get(epc);
        if (item != null) return item;
        // case-insensitive fallback
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

    /**
     * ✅ [الإصلاح] saveCache — يخزّن العناصر الفريدة فقط
     */
    private void saveCache() {
        try {
            JSONObject allItems = new JSONObject();

            // ✅ خزّن العناصر الفريدة فقط
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
        // ✅ بدون تكرار — نستخدم uniqueItems
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
                }كود
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
