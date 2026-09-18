package com.example.uhf.manager.inventory;

import android.content.Context;
import android.util.Log;

import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.model.InventoryItem;
import com.example.uhf.utils.RFIDCodeUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ✅ InventoryCatalogManager v3.0
 * 
 * إصلاحات v3.0:
 * - استخدام getBranchItemsListWithLongTimeout (60s read)
 * - Retry تلقائي مرة واحدة عند فشل الشبكة
 * - رسائل خطأ واضحة (401/403/404/Timeout/No Internet)
 * - Logging مفصل للتشخيص
 * - فلترة فئوية دقيقة (من v2.0)
 */
public class InventoryCatalogManager {

    private static final String TAG = "InventoryCatalogMgr";

    private final Context context;
    private final boolean isArabic;

    private final Map<String, InventoryItem> catalogItemsMap = new ConcurrentHashMap<>();
    private final Map<String, String> barcodeToEpcMap = new ConcurrentHashMap<>();
    private final Map<String, String> epcToBarcodeMap = new ConcurrentHashMap<>();

    public interface CatalogCallback {
        void onCatalogLoaded(Map<String, InventoryItem> catalog);
        void onError(String error);
    }

    public InventoryCatalogManager(Context context, boolean isArabic) {
        this.context = context.getApplicationContext();
        this.isArabic = isArabic;
    }

    public Map<String, InventoryItem> getCatalogItemsMap() { return catalogItemsMap; }
    public Map<String, String> getBarcodeToEpcMap() { return barcodeToEpcMap; }
    public Map<String, String> getEpcToBarcodeMap() { return epcToBarcodeMap; }

    // ==================== Fetch (v3.0) ====================

    /**
     * ✅ جلب الكتالوج من السيرفر — إصدار v3.0
     * 
     * التحسينات:
     * - Timeout طويل (60s)
     * - Retry تلقائي مرة واحدة
     * - رسائل خطأ واضحة
     */
    public void fetchCatalog(MiraApiClient apiClient,
                             MiraBranchesManager branchesManager,
                             CatalogCallback callback) {

        Map<String, InventoryItem> catalog = new ConcurrentHashMap<>();

        try {
            // ==================== 1. التحقق من المدخلات ====================
            if (apiClient == null || branchesManager == null) {
                Log.e(TAG, "❌ apiClient or branchesManager is null");
                if (callback != null) callback.onError(
                        isArabic ? "خطأ في التهيئة" : "Initialization error");
                return;
            }

            int branchId = branchesManager.getActiveBranchId();
            Log.d(TAG, "📡 fetchCatalog START | branchId=" + branchId);

            if (branchId <= 0) {
                Log.e(TAG, "❌ Invalid branchId: " + branchId);
                if (callback != null) callback.onError(
                        isArabic ? "لم يتم تحديد فرع نشط" : "No active branch");
                return;
            }

            // ==================== 2. المحاولة الأولى (Timeout طويل) ====================
            MiraApiClient.ApiResponse response = 
                    apiClient.getBranchItemsListWithLongTimeout(branchId);

            if (response == null) {
                Log.e(TAG, "❌ Null response from server");
                if (callback != null) callback.onError(
                        isArabic ? "لا يوجد رد من الخادم" : "No response from server");
                return;
            }

            Log.d(TAG, "📥 Response: code=" + response.code 
                    + ", isSuccess=" + response.isSuccess
                    + ", isNetworkError=" + response.isNetworkError);

            // ==================== 3. إذا كان خطأ شبكة → Retry ====================
            if (response.isNetworkError) {
    Log.w(TAG, "⚠️ Network error: " + response.error + " — retrying in 3s...");
    try {
        Thread.sleep(3000);
    } catch (InterruptedException ignored) {}

    // ✅ Retry
    Log.d(TAG, "🔄 Retry #2");
    response = apiClient.getBranchItemsListWithLongTimeout(branchId);
    
    if (response == null) {
        if (callback != null) callback.onError(
                isArabic ? "لا يوجد رد من الخادم بعد محاولتين" 
                         : "No response after 2 attempts");
        return;
    }
    
    if (response.isNetworkError) {
        String err = response.error != null ? response.error 
                : (isArabic ? "فشل الاتصال بعد محاولتين" 
                            : "Connection failed after 2 attempts");
        Log.e(TAG, "❌ Retry #2 failed: " + err);
        if (callback != null) callback.onError(err);
        return;
    }
}

            // ==================== 4. إذا كان خطأ HTTP ====================
            if (!response.isSuccess || response.data == null) {
                String err = response.error;
                if (err == null || err.isEmpty()) {
                    if (response.code == 401 || response.code == 403) {
                        err = isArabic 
                                ? "انتهت صلاحية الجلسة - يرجى إعادة تسجيل الدخول"
                                : "Session expired - please re-login";
                    } else if (response.code == 404) {
                        err = isArabic 
                                ? "الفرع غير موجود على السيرفر"
                                : "Branch not found on server";
                    } else if (response.code >= 500) {
                        err = isArabic 
                                ? "خطأ في السيرفر (" + response.code + ")"
                                : "Server error (" + response.code + ")";
                    } else {
                        err = isArabic 
                                ? "خطأ " + response.code 
                                : "Error " + response.code;
                    }
                }
                Log.e(TAG, "❌ HTTP error: " + err);
                if (callback != null) callback.onError(err);
                return;
            }

            // ==================== 5. معالجة البيانات ====================
            JSONArray items = response.data.optJSONArray("items");
            if (items == null) {
                JSONObject dataObj = response.data.optJSONObject("data");
                if (dataObj != null) items = dataObj.optJSONArray("items");
            }

            if (items == null) {
                Log.w(TAG, "⚠️ No items array — branch is empty");
                if (callback != null) callback.onCatalogLoaded(catalog);
                return;
            }

            Log.d(TAG, "📦 Found " + items.length() + " items in response");

            for (int i = 0; i < items.length(); i++) {
                try {
                    processCatalogItem(items.getJSONObject(i), catalog);
                } catch (Exception e) {
                    Log.e(TAG, "processCatalogItem error at " + i + ": " + e.getMessage());
                }
            }

            Log.d(TAG, "✅ Catalog loaded: " + catalog.size() + " items");

            // ✅ Log عينات للتشخيص
            if (!catalog.isEmpty() && Log.isLoggable(TAG, Log.DEBUG)) {
                int count = 0;
                Log.d(TAG, "📋 Sample keys:");
                for (String key : catalog.keySet()) {
                    Log.d(TAG, "   → " + key);
                    if (++count >= 5) break;
                }
            }

            if (callback != null) callback.onCatalogLoaded(catalog);

        } catch (Exception e) {
            Log.e(TAG, "❌ fetchCatalog exception: " + e.getMessage(), e);
            if (callback != null) callback.onError(
                    e.getMessage() != null ? e.getMessage() 
                    : (isArabic ? "خطأ غير متوقع" : "Unexpected error"));
        }
    }

    // ==================== Process Item ====================

    private void processCatalogItem(JSONObject obj, Map<String, InventoryItem> catalog) {
        try {
            String gtin13 = obj.optString("gtin13", "").trim();
            String serialNumber = obj.optString("serial_number", "").trim();
            String epcCode = obj.optString("epc", obj.optString("rfid_epc", "")).trim();

            String title = obj.optString("item_title",
                    obj.optString("title", isArabic ? "قطعة ذهبية" : "Gold Item"));
            String karat = obj.optString("gold_karat", obj.optString("karat", "21K"));
            double weight = obj.optDouble("gross_weight_grams", obj.optDouble("weight", 0));
            double price = obj.optDouble("retail_price_usd",
                    obj.optDouble("retail_price", obj.optDouble("price", 0)));

            String primaryEpc;
            if (!epcCode.isEmpty()) {
                primaryEpc = epcCode.toUpperCase();
            } else if (!serialNumber.isEmpty()) {
                primaryEpc = serialNumber.toUpperCase();
            } else if (!gtin13.isEmpty()) {
                primaryEpc = "EAN13:" + gtin13;
            } else {
                Log.w(TAG, "⚠️ Skipping item without any ID");
                return;
            }

            String normalizedEpc = RFIDCodeUtils.normalizeCodeToEpc(primaryEpc, barcodeToEpcMap);

            InventoryItem item = new InventoryItem(
                    normalizedEpc, gtin13, serialNumber, title, karat
            );
            item.setWeight(weight);
            item.setPrice(price);

            catalog.put(normalizedEpc, item);
            catalogItemsMap.put(normalizedEpc, item);

            // ✅ سجّل كل الصيغ الممكنة في barcodeToEpcMap
            if (!gtin13.isEmpty()) {
                barcodeToEpcMap.put(gtin13, normalizedEpc);
                barcodeToEpcMap.put(gtin13.toUpperCase(), normalizedEpc);
            }
            if (!serialNumber.isEmpty()) {
                barcodeToEpcMap.put(serialNumber, normalizedEpc);
                barcodeToEpcMap.put(serialNumber.toUpperCase(), normalizedEpc);
            }
            if (!epcCode.isEmpty()) {
                barcodeToEpcMap.put(epcCode, normalizedEpc);
                barcodeToEpcMap.put(epcCode.toUpperCase(), normalizedEpc);
            }

            // ✅ الأهم: سجّل المفتاح نفسه (idempotency)
            barcodeToEpcMap.put(normalizedEpc, normalizedEpc);

            if (!gtin13.isEmpty()) epcToBarcodeMap.put(normalizedEpc, gtin13);
            else if (!serialNumber.isEmpty()) epcToBarcodeMap.put(normalizedEpc, serialNumber);

        } catch (Exception e) {
            Log.e(TAG, "processCatalogItem error: " + e.getMessage());
        }
    }

    // ==================== Filter by Category ====================

    public Map<String, InventoryItem> filterByCategory(Map<String, InventoryItem> fullCatalog,
                                                        String category) {
        Map<String, InventoryItem> filtered = new ConcurrentHashMap<>();

        if (fullCatalog == null || category == null || category.isEmpty()) {
            return fullCatalog != null ? fullCatalog : filtered;
        }

        for (Map.Entry<String, InventoryItem> entry : fullCatalog.entrySet()) {
            InventoryItem item = entry.getValue();
            if (item != null && matchesCategory(item, category)) {
                filtered.put(entry.getKey(), item);
            }
        }

        Log.d(TAG, "✅ Filtered " + filtered.size() + " / " + fullCatalog.size()
                + " items for category: " + category);
        return filtered;
    }

    private boolean matchesCategory(InventoryItem item, String category) {
        if (item == null || category == null) return false;

        String itemKarat = item.getKarat();
        if (itemKarat == null) itemKarat = "";
        itemKarat = itemKarat.toUpperCase().trim();

        String cat = category.trim();
        String catUpper = cat.toUpperCase();

        for (String k : new String[]{"24", "22", "21", "18", "14"}) {
            if (cat.equals("عيار " + k) || catUpper.equals(k + "K")) {
                return itemKarat.equals(k + "K") || itemKarat.equals(k);
            }
        }

        if (cat.equals("سبائك") || catUpper.equals("BULLION")) {
            return itemKarat.contains("BULLION")
                    || itemKarat.contains("BAR")
                    || itemKarat.contains("سبيكة");
        }

        if (cat.equals("أونصة") || catUpper.equals("OUNCE")) {
            return itemKarat.contains("OUNCE") || itemKarat.contains("OZ");
        }

        if (cat.equals("فضة") || catUpper.equals("SILVER")) {
            return itemKarat.contains("SILVER") || itemKarat.contains("AG");
        }

        if (cat.equals("ألماس") || catUpper.equals("DIAMOND")) {
            return itemKarat.contains("DIAMOND") || itemKarat.contains("DIA");
        }

        if (cat.equals("أخرى") || catUpper.equals("OTHER")) {
            return !itemKarat.matches("^(24K|22K|21K|18K|14K|24|22|21|18|14)$")
                    && !itemKarat.contains("BULLION")
                    && !itemKarat.contains("OUNCE")
                    && !itemKarat.contains("SILVER")
                    && !itemKarat.contains("DIAMOND");
        }

        return false;
    }

    // ==================== Getters ====================

    public String getItemKarat(String epc) {
        InventoryItem item = catalogItemsMap.get(epc);
        return item != null ? item.getKarat() : "—";
    }

    public String getItemTitle(String epc) {
        InventoryItem item = catalogItemsMap.get(epc);
        return item != null ? item.getTitle() : null;
    }

    public InventoryItem getItemByEpc(String epc) {
        return catalogItemsMap.get(epc);
    }

    public boolean isInCatalog(String epc) {
        return catalogItemsMap.containsKey(epc);
    }

    public void clear() {
        try {
            catalogItemsMap.clear();
            barcodeToEpcMap.clear();
            epcToBarcodeMap.clear();
            Log.d(TAG, "✅ Catalog cleared");
        } catch (Exception e) {
            Log.e(TAG, "clear error: " + e.getMessage());
        }
    }

    public Set<String> getAllEpcs() { return catalogItemsMap.keySet(); }
    public int size() { return catalogItemsMap.size(); }
    public boolean isEmpty() { return catalogItemsMap.isEmpty(); }
}
