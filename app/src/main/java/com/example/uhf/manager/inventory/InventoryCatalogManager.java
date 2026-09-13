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
 * ✅ InventoryCatalogManager
 * 
 * مسؤول عن:
 * - جلب الكتالوج من السيرفر
 * - بناء الخرائط (catalogItemsMap, barcodeToEpcMap, epcToBarcodeMap)
 * - فلترة الكتالوج حسب الفئة
 * 
 * لا يحتوي على مراجع للـ UI → آمن من التسريبات
 */
public class InventoryCatalogManager {

    private static final String TAG = "InventoryCatalogMgr";
    
    private final Context context;
    private final boolean isArabic;
    
    // ✅ الخرائط (مشتركة مع الـ Fragment)
    private final Map<String, InventoryItem> catalogItemsMap = new ConcurrentHashMap<>();
    private final Map<String, String> barcodeToEpcMap = new ConcurrentHashMap<>();
    private final Map<String, String> epcToBarcodeMap = new ConcurrentHashMap<>();
    
    // ==================== Callbacks ====================
    
    public interface CatalogCallback {
        /**
         * تم جلب الكتالوج بنجاح
         */
        void onCatalogLoaded(Map<String, InventoryItem> catalog);
        
        /**
         * فشل الجلب
         */
        void onError(String error);
    }
    
    // ==================== Constructor ====================
    
    public InventoryCatalogManager(Context context, boolean isArabic) {
        this.context = context.getApplicationContext();
        this.isArabic = isArabic;
    }
    
    // ==================== Getters ====================
    
    public Map<String, InventoryItem> getCatalogItemsMap() {
        return catalogItemsMap;
    }
    
    public Map<String, String> getBarcodeToEpcMap() {
        return barcodeToEpcMap;
    }
    
    public Map<String, String> getEpcToBarcodeMap() {
        return epcToBarcodeMap;
    }
    
    // ==================== Fetch from Server ====================
    
    /**
     * ✅ جلب الكتالوج من السيرفر
     * 
     * @param apiClient       MiraApiClient
     * @param branchesManager MiraBranchesManager
     * @param callback        يُستدعى مع النتيجة
     */
    public void fetchCatalog(MiraApiClient apiClient, 
                             MiraBranchesManager branchesManager, 
                             CatalogCallback callback) {
        
        Map<String, InventoryItem> catalog = new ConcurrentHashMap<>();
        
        try {
            if (apiClient == null || branchesManager == null) {
                if (callback != null) callback.onError("API or Branches manager not available");
                return;
            }
            
            int branchId = branchesManager.getActiveBranchId();
            MiraApiClient.ApiResponse response = apiClient.getBranchItemsList(branchId);
            
            if (response == null || !response.isSuccess || response.data == null) {
                if (callback != null) callback.onError("Failed to fetch catalog");
                return;
            }
            
            // ✅ استخراج items من الاستجابة
            JSONArray items = response.data.optJSONArray("items");
            
            if (items == null) {
                JSONObject dataObj = response.data.optJSONObject("data");
                if (dataObj != null) {
                    items = dataObj.optJSONArray("items");
                }
            }
            
            if (items == null) {
                if (callback != null) callback.onCatalogLoaded(catalog);
                return;
            }
            
            // ✅ معالجة كل قطعة
            for (int i = 0; i < items.length(); i++) {
                processCatalogItem(items.getJSONObject(i), catalog);
            }
            
            Log.d(TAG, "✅ Catalog loaded: " + catalog.size() + " items");
            
            if (callback != null) callback.onCatalogLoaded(catalog);
            
        } catch (Exception e) {
            Log.e(TAG, "fetchCatalog error: " + e.getMessage(), e);
            if (callback != null) callback.onError(e.getMessage());
        }
    }
    
    /**
     * ✅ معالجة قطعة واحدة من الكتالوج
     */
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
            
            // ✅ اختيار EPC الأساسي
            String primaryEpc;
            if (!epcCode.isEmpty()) {
                primaryEpc = epcCode.toUpperCase();
            } else if (!serialNumber.isEmpty()) {
                primaryEpc = serialNumber.toUpperCase();
            } else if (!gtin13.isEmpty()) {
                primaryEpc = "EAN13:" + gtin13;
            } else {
                return; // لا يوجد معرف — تخطى
            }
            
            String normalizedEpc = RFIDCodeUtils.normalizeCodeToEpc(primaryEpc, barcodeToEpcMap);
            
            // ✅ بناء InventoryItem
            InventoryItem item = new InventoryItem(
                    normalizedEpc, gtin13, serialNumber, title, karat
            );
            item.setWeight(weight);
            item.setPrice(price);
            
            // ✅ إضافة للخرائط
            catalog.put(normalizedEpc, item);
            catalogItemsMap.put(normalizedEpc, item);
            
            if (!gtin13.isEmpty()) {
                barcodeToEpcMap.put(gtin13, normalizedEpc);
                epcToBarcodeMap.put(normalizedEpc, gtin13);
            }
            if (!serialNumber.isEmpty()) {
                barcodeToEpcMap.put(serialNumber, normalizedEpc);
                epcToBarcodeMap.put(normalizedEpc, serialNumber);
            }
            if (!epcCode.isEmpty()) {
                barcodeToEpcMap.put(epcCode.toUpperCase(), normalizedEpc);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "processCatalogItem error: " + e.getMessage());
        }
    }
    
    // ==================== Filter by Category ====================
    
    /**
     * ✅ فلترة الكتالوج حسب الفئة
     * 
     * @param category  الفئة (مثل "عيار 21", "سبائك", ...)
     * @return  Map جديدة تحتوي فقط على القطع المطابقة
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
        
        Log.d(TAG, "✅ Filtered " + filtered.size() + " items for category: " + category);
        return filtered;
    }
    
    /**
     * ✅ فحص إذا كانت القطعة تنتمي للفئة المطلوبة
     */
    private boolean matchesCategory(InventoryItem item, String category) {
        if (item == null || category == null) return false;
        
        String itemKarat = item.getKarat();
        if (itemKarat == null) itemKarat = "";
        itemKarat = itemKarat.toUpperCase().trim();
        
        String cat = category.trim();
        
        // ✅ مطابقة العيار
        if (cat.equals("عيار 24") || cat.equalsIgnoreCase("24K")) {
            return itemKarat.equals("24K") || itemKarat.equals("24");
        }
        if (cat.equals("عيار 22") || cat.equalsIgnoreCase("22K")) {
            return itemKarat.equals("22K") || itemKarat.equals("22");
        }
        if (cat.equals("عيار 21") || cat.equalsIgnoreCase("21K")) {
            return itemKarat.equals("21K") || itemKarat.equals("21");
        }
        if (cat.equals("عيار 18") || cat.equalsIgnoreCase("18K")) {
            return itemKarat.equals("18K") || itemKarat.equals("18");
        }
        if (cat.equals("عيار 14") || cat.equalsIgnoreCase("14K")) {
            return itemKarat.equals("14K") || itemKarat.equals("14");
        }
        
        // ✅ مطابقة الفئات الأخرى
        if (cat.equals("سبائك") || cat.equalsIgnoreCase("Bullion")) {
            return itemKarat.contains("BULLION") || itemKarat.contains("BAR");
        }
        if (cat.equals("أونصة") || cat.equalsIgnoreCase("Ounce")) {
            return itemKarat.contains("OUNCE") || itemKarat.contains("OZ");
        }
        if (cat.equals("فضة") || cat.equalsIgnoreCase("Silver")) {
            return itemKarat.contains("SILVER") || itemKarat.contains("AG");
        }
        if (cat.equals("ألماس") || cat.equalsIgnoreCase("Diamond")) {
            return itemKarat.contains("DIAMOND") || itemKarat.contains("DIA");
        }
        if (cat.equals("أخرى") || cat.equalsIgnoreCase("Other")) {
            return !itemKarat.matches("^(24K|22K|21K|18K|14K|24|22|21|18|14)$")
                && !itemKarat.contains("BULLION")
                && !itemKarat.contains("OUNCE")
                && !itemKarat.contains("SILVER")
                && !itemKarat.contains("DIAMOND");
        }
        
        return false;
    }
    
    // ==================== Utilities ====================
    
    /**
     * ✅ الحصول على العيار المتاح
     */
    public String getItemKarat(String epc) {
        InventoryItem item = catalogItemsMap.get(epc);
        if (item != null) return item.getKarat();
        return "—";
    }
    
    /**
     * ✅ الحصول على عنوان القطعة
     */
    public String getItemTitle(String epc) {
        InventoryItem item = catalogItemsMap.get(epc);
        if (item != null) return item.getTitle();
        return null;
    }
    
    /**
     * ✅ الحصول على القطعة بواسطة EPC
     */
    public InventoryItem getItemByEpc(String epc) {
        return catalogItemsMap.get(epc);
    }
    
    /**
     * ✅ هل EPC موجود في الكتالوج؟
     */
    public boolean isInCatalog(String epc) {
        return catalogItemsMap.containsKey(epc);
    }
    
    // ==================== Clear ====================
    
    /**
     * ✅ مسح كل الخرائط (عند التدمير)
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
    
    // ==================== Getters for external use ====================
    
    /**
     * ✅ الحصول على مجموعة كل EPCs
     */
    public Set<String> getAllEpcs() {
        return catalogItemsMap.keySet();
    }
    
    /**
     * ✅ حجم الكتالوج
     */
    public int size() {
        return catalogItemsMap.size();
    }
    
    /**
     * ✅ هل الكتالوج فارغ؟
     */
    public boolean isEmpty() {
        return catalogItemsMap.isEmpty();
    }
}
