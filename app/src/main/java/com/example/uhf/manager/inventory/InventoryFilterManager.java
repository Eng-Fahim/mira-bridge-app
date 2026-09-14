package com.example.uhf.manager.inventory;

import android.util.Log;

import com.example.uhf.model.InventoryItem;
import com.example.uhf.model.ScannedItemDisplay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * ✅ InventoryFilterManager
 * 
 * مسؤول عن:
 * - الفلترة الحية (موجود/مفقود/إضافي/جديد/مكرر)
 * - البحث النصي
 * - إرجاع قوائم القطع حسب الفلتر
 * 
 * لا يحتوي على مراجع للـ UI → آمن من التسريبات
 */
public class InventoryFilterManager {

    private static final String TAG = "InventoryFilterMgr";
    
    private final boolean isArabic;
    
    // ✅ ثوابت الفلاتر
    public static final int FILTER_ALL = 0;
    public static final int FILTER_FOUND = 1;
    public static final int FILTER_MISSING = 2;
    public static final int FILTER_UNEXPECTED = 3;
    public static final int FILTER_NEW = 4;
    public static final int FILTER_DUPLICATE = 5;
    
    private int currentFilter = FILTER_ALL;
    private String currentSearchQuery = "";
    
    // ==================== Constructor ====================
    
    public InventoryFilterManager(boolean isArabic) {
        this.isArabic = isArabic;
    }
    
    // ==================== Filter Names ====================
    
    /**
     * ✅ الحصول على قائمة الفلاتر (للعرض في الحوار)
     */
    public String[] getFilterOptions() {
        return isArabic ? new String[]{
                "📋 الكل",
                "✅ موجود",
                "❌ مفقود",
                "⚠️ إضافي",
                "🆕 جديد",
                "📊 مكرر"
        } : new String[]{
                "📋 All",
                "✅ Found",
                "❌ Missing",
                "⚠️ Unexpected",
                "🆕 New",
                "📊 Duplicate"
        };
    }
    
    /**
     * ✅ الحصول على اسم الفلتر الحالي
     */
    public String getCurrentFilterName() {
        String[] names = isArabic ? 
                new String[]{"الكل", "موجود", "مفقود", "إضافي", "جديد", "مكرر"} :
                new String[]{"All", "Found", "Missing", "Unexpected", "New", "Duplicate"};
        
        if (currentFilter >= 0 && currentFilter < names.length) {
            return names[currentFilter];
        }
        return names[0];
    }
    
    // ==================== Setters / Getters ====================
    
    public void setCurrentFilter(int filter) {
        this.currentFilter = filter;
    }
    
    public int getCurrentFilter() {
        return currentFilter;
    }
    
    public void setSearchQuery(String query) {
        this.currentSearchQuery = query != null ? query.toLowerCase().trim() : "";
    }
    
    public String getSearchQuery() {
        return currentSearchQuery;
    }
    
    public boolean hasSearchQuery() {
        return currentSearchQuery != null && !currentSearchQuery.isEmpty();
    }
    
    // ==================== Main Filter Method ====================
    
    /**
     * ✅ تطبيق الفلتر الحالي + البحث على القائمة
     * 
     * @param allItems       كل القطع الممسوحة
     * @param catalogEpcs    EPCs الموجودة في الكتالوج
     * @return  القائمة المُفلترة
     */
    public List<ScannedItemDisplay> applyFilter(
            List<ScannedItemDisplay> allItems,
            java.util.Set<String> catalogEpcs) {
        
        if (allItems == null || allItems.isEmpty()) {
            return new ArrayList<>();
        }
        
        // ✅ نسخة آمنة
        List<ScannedItemDisplay> snapshot;
        synchronized (allItems) {
            snapshot = new ArrayList<>(allItems);
        }
        
        // ✅ 1) تطبيق فلتر الفئة
        snapshot = filterByType(snapshot, catalogEpcs);
        
        // ✅ 2) تطبيق البحث النصي
        if (hasSearchQuery()) {
            snapshot = filterBySearch(snapshot, currentSearchQuery);
        }
        
        return snapshot;
    }
    
    // ==================== Filter by Type ====================
    
    /**
     * ✅ فلترة حسب النوع
     */
    private List<ScannedItemDisplay> filterByType(
            List<ScannedItemDisplay> items,
            java.util.Set<String> catalogEpcs) {
        
        if (currentFilter == FILTER_ALL) {
            return items;
        }
        
        List<ScannedItemDisplay> filtered = new ArrayList<>();
        
        for (ScannedItemDisplay item : items) {
            if (item == null || item.epc == null) continue;
            
            boolean isInCatalog = catalogEpcs != null && catalogEpcs.contains(item.epc);
            
            switch (currentFilter) {
                case FILTER_FOUND:
                    // موجود في الكتالوج
                    if (isInCatalog) filtered.add(item);
                    break;
                    
                case FILTER_MISSING:
                    // ⚠️ المفقود ليس في القطع الممسوحة — يُعالج بطريقة أخرى
                    // هنا نُرجع القطع التي قد تكون مفقودة (للتوافق فقط)
                    // القائمة الحقيقية للمفقودين تُطلب من getMissingItems()
                    if (!isInCatalog) filtered.add(item);
                    break;
                    
                case FILTER_UNEXPECTED:
                    // غير موجود في الكتالوج (إضافي)
                    if (!isInCatalog) filtered.add(item);
                    break;
                    
                case FILTER_NEW:
                    // قُرئ مرة واحدة فقط
                    if (item.count == 1) filtered.add(item);
                    break;
                    
                case FILTER_DUPLICATE:
                    // قُرئ أكثر من مرة
                    if (item.count > 1) filtered.add(item);
                    break;
                    
                default:
                    filtered.add(item);
            }
        }
        
        return filtered;
    }
    
    // ==================== Filter by Search ====================
    
    /**
     * ✅ البحث النصي في القطع
     */
    private List<ScannedItemDisplay> filterBySearch(
            List<ScannedItemDisplay> items, 
            String query) {
        
        if (query == null || query.isEmpty()) return items;
        
        List<ScannedItemDisplay> filtered = new ArrayList<>();
        
        for (ScannedItemDisplay item : items) {
            if (item == null) continue;
            
            boolean matches = false;
            
            if (item.epc != null && item.epc.toLowerCase().contains(query)) {
                matches = true;
            } else if (item.title != null && item.title.toLowerCase().contains(query)) {
                matches = true;
            } else if (item.karat != null && item.karat.toLowerCase().contains(query)) {
                matches = true;
            }
            
            if (matches) filtered.add(item);
        }
        
        return filtered;
    }
    
    // ==================== Missing Items ====================
    
    /**
     * ✅ الحصول على قائمة القطع المفقودة
     * (في الكتالوج لكن لم تُمسح)
     */
    public List<String> getMissingItems(
            java.util.Set<String> catalogEpcs,
            List<ScannedItemDisplay> scannedItems) {
        
        List<String> missing = new ArrayList<>();
        
        if (catalogEpcs == null || catalogEpcs.isEmpty()) {
            return missing;
        }
        
        // ✅ بناء Set من EPCs الممسوحة
        java.util.Set<String> scannedSet = new java.util.HashSet<>();
        if (scannedItems != null) {
            synchronized (scannedItems) {
                for (ScannedItemDisplay item : scannedItems) {
                    if (item != null && item.epc != null) {
                        scannedSet.add(item.epc);
                    }
                }
            }
        }
        
        // ✅ إيجاد المفقودين
        for (String epc : catalogEpcs) {
            if (!scannedSet.contains(epc)) {
                missing.add(epc);
            }
        }
        
        return missing;
    }
    
    // ==================== Utilities ====================
    
    /**
     * ✅ فحص إذا كانت القطعة ممسوحة
     */
    public boolean isItemScanned(String epc, List<ScannedItemDisplay> scannedItems) {
        if (epc == null || scannedItems == null) return false;
        
        synchronized (scannedItems) {
            for (ScannedItemDisplay item : scannedItems) {
                if (item != null && item.epc != null 
                        && item.epc.equalsIgnoreCase(epc)) {
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * ✅ بناء نص الحوار للفلترة
     */
    public String buildFilterDialogTitle() {
        return isArabic ? "🔍 فلترة النتائج" : "🔍 Filter Results";
    }
    
    /**
     * ✅ بناء رسالة "لا توجد نتائج"
     */
    public String buildEmptyResultMessage() {
        return isArabic ? "لا توجد نتائج مطابقة" : "No matching results";
    }
    
    /**
     * ✅ بناء رسالة "لا يوجد كتالوج"
     */
    public String buildNoCatalogMessage() {
        return isArabic ? "⚠️ لا يوجد كتالوج محمّل" : "⚠️ No catalog loaded";
    }
    
    /**
     * ✅ بناء رسالة "لا توجد قطع مفقودة"
     */
    public String buildNoMissingMessage() {
        return isArabic ? "✅ لا توجد قطع مفقودة" : "✅ No missing items";
    }
    
    /**
     * ✅ بناء عنوان حوار المفقودين
     */
    public String buildMissingItemsTitle(int count) {
        return isArabic ? 
                "❌ المفقود (" + count + ")" : 
                "❌ Missing (" + count + ")";
    }
    
    /**
     * ✅ بناء نص عنصر مفقود
     */
    public String buildMissingItemLabel(String epc, InventoryItem item) {
        String title = item != null && item.getTitle() != null ? 
                item.getTitle() : epc;
        return "❌ " + title + "\n" + epc;
    }
    
    /**
     * ✅ بناء عناصر قائمة المفقودين
     */
    public String[] buildMissingItemsList(
            List<String> missingEpcs,
            Map<String, InventoryItem> catalogItemsMap) {
        
        if (missingEpcs == null || missingEpcs.isEmpty()) {
            return new String[0];
        }
        
        String[] result = new String[missingEpcs.size()];
        
        for (int i = 0; i < missingEpcs.size(); i++) {
            String epc = missingEpcs.get(i);
            InventoryItem item = catalogItemsMap != null ? 
                    catalogItemsMap.get(epc) : null;
            result[i] = buildMissingItemLabel(epc, item);
        }
        
        return result;
    }
    
    /**
     * ✅ إعادة تعيين الفلتر
     */
    public void reset() {
        currentFilter = FILTER_ALL;
        currentSearchQuery = "";
    }
    
    // ==================== Statistics ====================
    
    /**
     * ✅ إحصائيات الفلترة
     */
    public String buildFilterStats(
            List<ScannedItemDisplay> allItems,
            java.util.Set<String> catalogEpcs) {
        
        if (allItems == null) return "";
        
        int total = 0;
        int found = 0;
        int unexpected = 0;
        int duplicates = 0;
        
        synchronized (allItems) {
            total = allItems.size();
            
            for (ScannedItemDisplay item : allItems) {
                if (item == null || item.epc == null) continue;
                
                boolean inCatalog = catalogEpcs != null 
                        && catalogEpcs.contains(item.epc);
                
                if (inCatalog) {
                    found++;
                } else {
                    unexpected++;
                }
                
                if (item.count > 1) {
                    duplicates++;
                }
            }
        }
        
        int missing = 0;
        if (catalogEpcs != null) {
            missing = Math.max(0, catalogEpcs.size() - found);
        }
        
        return isArabic ?
                "📊 الإجمالي: " + total + 
                " | ✅ موجود: " + found + 
                " | ❌ مفقود: " + missing +
                " | ⚠️ إضافي: " + unexpected +
                " | 📊 مكرر: " + duplicates
                :
                "📊 Total: " + total + 
                " | ✅ Found: " + found + 
                " | ❌ Missing: " + missing +
                " | ⚠️ Unexpected: " + unexpected +
                " | 📊 Dup: " + duplicates;
    }
}
