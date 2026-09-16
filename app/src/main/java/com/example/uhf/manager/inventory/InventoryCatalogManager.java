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
 * ✅ InventoryCatalogManager v2.0
 * 
 * إصلاحات v2.0:
 * - فلترة فئوية دقيقة (تطابق كامل مع "عيار X" و "XK")
 * - تحسين الأداء عند الفلترة الكبيرة
 * - إضافة clear آمن
 * - لا CountDownLatch (التزامن مسؤولية المُستدعي)
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

    // ==================== Fetch ====================

    /**
     * ✅ جلب الكتالوج من السيرفر
     * 
     * ⚠️ هذه الدالة **متزامنة** (blocking).
     * يجب استدعاؤها من خيط خلفي (ioExecutor).
     */
    public void fetchCatalog(MiraApiClient apiClient,
                             MiraBranchesManager branchesManager,
                             CatalogCallback callback) {

        Map<String, InventoryItem> catalog = new ConcurrentHashMap<>();

        try {
            if (apiClient == null || branchesManager == null) {
                Log.e(TAG, "❌ apiClient or branchesManager is null");
                if (callback != null) callback.onError("API or Branches manager not available");
                return;
            }

            int branchId = branchesManager.getActiveBranchId();
            Log.d(TAG, "📡 Fetching catalog for branchId: " + branchId);

            if (branchId <= 0) {
                Log.e(TAG, "❌❌❌ branchId = " + branchId + "! Branches not initialized");
                if (callback != null) callback.onError("No active branch (branchId=" + branchId + ")");
                return;
            }

            MiraApiClient.ApiResponse response = apiClient.getBranchItemsList(branchId);

            if (response == null) {
                Log.e(TAG, "❌ response is null");
                if (callback != null) callback.onError("Null response");
                return;
            }

            Log.d(TAG, "📡 Response: isSuccess=" + response.isSuccess
                    + ", code=" + response.code
                    + ", data=" + (response.data != null ? "present" : "null"));

            if (!response.isSuccess || response.data == null) {
                if (callback != null) callback.onError("Failed to fetch catalog: code " + response.code);
                return;
            }

            JSONArray items = response.data.optJSONArray("items");
            if (items == null) {
                JSONObject dataObj = response.data.optJSONObject("data");
                if (dataObj != null) items = dataObj.optJSONArray("items");
            }

            if (items == null) {
                Log.w(TAG, "⚠️ No items array in response");
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

            if (!catalog.isEmpty()) {
                int count = 0;
                Log.d(TAG, "📋 Sample keys:");
                for (String key : catalog.keySet()) {
                    Log.d(TAG, "   → " + key);
                    if (++count >= 5) break;
                }
            }

            if (callback != null) callback.onCatalogLoaded(catalog);

        } catch (Exception e) {
            Log.e(TAG, "fetchCatalog error: " + e.getMessage(), e);
            if (callback != null) callback.onError(e.getMessage());
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

            Log.d(TAG, "──── Item ────");
            Log.d(TAG, "  gtin13=" + gtin13 + " | serial=" + serialNumber + " | epc=" + epcCode);
            Log.d(TAG, "  primary=" + primaryEpc + " → normalized=" + normalizedEpc);

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

    // ==================== Filter by Category (v2.0 — محسّن) ====================

    /**
     * ✅ فلترة حسب الفئة — إصدار دقيق
     */
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

    /**
     * ✅ مطابقة الفئة — إصدار دقيق v2.0
     */
    private boolean matchesCategory(InventoryItem item, String category) {
        if (item == null || category == null) return false;

        String itemKarat = item.getKarat();
        if (itemKarat == null) itemKarat = "";
        itemKarat = itemKarat.toUpperCase().trim();

        String cat = category.trim();
        String catUpper = cat.toUpperCase();

        // ✅ 24K / 22K / 21K / 18K / 14K
        for (String k : new String[]{"24", "22", "21", "18", "14"}) {
            if (cat.equals("عيار " + k) || catUpper.equals(k + "K")) {
                return itemKarat.equals(k + "K") || itemKarat.equals(k);
            }
        }

        // ✅ سبائك / Bullion
        if (cat.equals("سبائك") || catUpper.equals("BULLION")) {
            return itemKarat.contains("BULLION")
                    || itemKarat.contains("BAR")
                    || itemKarat.contains("سبيكة");
        }

        // ✅ أونصة / Ounce
        if (cat.equals("أونصة") || catUpper.equals("OUNCE")) {
            return itemKarat.contains("OUNCE") || itemKarat.contains("OZ");
        }

        // ✅ فضة / Silver
        if (cat.equals("فضة") || catUpper.equals("SILVER")) {
            return itemKarat.contains("SILVER") || itemKarat.contains("AG");
        }

        // ✅ ألماس / Diamond
        if (cat.equals("ألماس") || catUpper.equals("DIAMOND")) {
            return itemKarat.contains("DIAMOND") || itemKarat.contains("DIA");
        }

        // ✅ أخرى / Other
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

    /**
     * ✅ مسح شامل — آمن
     */
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
