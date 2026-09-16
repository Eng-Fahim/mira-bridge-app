package com.example.uhf.manager.inventory;

import android.util.Log;

import com.example.uhf.model.InventoryItem;
import com.example.uhf.model.ScannedItemDisplay;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ✅ InventoryFilterManager v2.0
 * 
 * إصلاحات:
 * - إزالة synchronized الزائد (CopyOnWriteArrayList آمن)
 * - FILTER_MISSING منطقي (يُستخدم بشكل صحيح)
 * - دعم أفضل للبحث
 */
public class InventoryFilterManager {

    private static final String TAG = "InventoryFilterMgr";

    private final boolean isArabic;

    public static final int FILTER_ALL = 0;
    public static final int FILTER_FOUND = 1;
    public static final int FILTER_MISSING = 2;
    public static final int FILTER_UNEXPECTED = 3;
    public static final int FILTER_NEW = 4;
    public static final int FILTER_DUPLICATE = 5;

    private volatile int currentFilter = FILTER_ALL;
    private volatile String currentSearchQuery = "";

    public InventoryFilterManager(boolean isArabic) {
        this.isArabic = isArabic;
    }

    public String[] getFilterOptions() {
        return isArabic ? new String[]{
                "📋 الكل", "✅ موجود", "❌ مفقود",
                "⚠️ إضافي", "🆕 جديد", "📊 مكرر"
        } : new String[]{
                "📋 All", "✅ Found", "❌ Missing",
                "⚠️ Unexpected", "🆕 New", "📊 Duplicate"
        };
    }

    public String getCurrentFilterName() {
        String[] names = isArabic ?
                new String[]{"الكل", "موجود", "مفقود", "إضافي", "جديد", "مكرر"} :
                new String[]{"All", "Found", "Missing", "Unexpected", "New", "Duplicate"};
        return (currentFilter >= 0 && currentFilter < names.length)
                ? names[currentFilter] : names[0];
    }

    public void setCurrentFilter(int filter) { this.currentFilter = filter; }
    public int getCurrentFilter() { return currentFilter; }
    public void setSearchQuery(String q) {
        this.currentSearchQuery = q != null ? q.toLowerCase().trim() : "";
    }
    public String getSearchQuery() { return currentSearchQuery; }
    public boolean hasSearchQuery() {
        return currentSearchQuery != null && !currentSearchQuery.isEmpty();
    }

    /**
     * ✅ النسخة الرئيسية — بدون synchronized زائد
     */
    public List<ScannedItemDisplay> applyFilter(List<ScannedItemDisplay> allItems,
                                                 Set<String> catalogEpcs) {
        if (allItems == null || allItems.isEmpty()) return new ArrayList<>();

        // CopyOnWriteArrayList: القراءة آمنة بدون قفل
        List<ScannedItemDisplay> result = new ArrayList<>(allItems);

        result = filterByType(result, catalogEpcs);

        if (hasSearchQuery()) {
            result = filterBySearch(result, currentSearchQuery);
        }
        return result;
    }

    private List<ScannedItemDisplay> filterByType(List<ScannedItemDisplay> items,
                                                   Set<String> catalogEpcs) {
        if (currentFilter == FILTER_ALL) return items;

        List<ScannedItemDisplay> filtered = new ArrayList<>(items.size());

        for (ScannedItemDisplay item : items) {
            if (item == null || item.epc == null) continue;
            boolean inCatalog = catalogEpcs != null && catalogEpcs.contains(item.epc);

            switch (currentFilter) {
                case FILTER_FOUND:
                    if (inCatalog) filtered.add(item);
                    break;
                case FILTER_UNEXPECTED:
                    if (!inCatalog) filtered.add(item);
                    break;
                case FILTER_NEW:
                    if (item.count == 1) filtered.add(item);
                    break;
                case FILTER_DUPLICATE:
                    if (item.count > 1) filtered.add(item);
                    break;
                case FILTER_MISSING:
                    // المفقود = في الكتالوج لكن لم يُمسح — لا يظهر هنا
                    // يُستخدم getMissingItems() بدلاً من ذلك
                    break;
                default:
                    filtered.add(item);
            }
        }
        return filtered;
    }

    private List<ScannedItemDisplay> filterBySearch(List<ScannedItemDisplay> items,
                                                     String query) {
        if (query == null || query.isEmpty()) return items;

        List<ScannedItemDisplay> filtered = new ArrayList<>();
        for (ScannedItemDisplay item : items) {
            if (item == null) continue;
            if ((item.epc != null && item.epc.toLowerCase().contains(query))
                    || (item.title != null && item.title.toLowerCase().contains(query))
                    || (item.karat != null && item.karat.toLowerCase().contains(query))) {
                filtered.add(item);
            }
        }
        return filtered;
    }

    /**
     * ✅ المفقودين — بدون synchronized زائد
     */
    public List<String> getMissingItems(Set<String> catalogEpcs,
                                          List<ScannedItemDisplay> scannedItems) {
        List<String> missing = new ArrayList<>();
        if (catalogEpcs == null || catalogEpcs.isEmpty()) return missing;

        Set<String> scannedSet = new HashSet<>();
        if (scannedItems != null) {
            for (ScannedItemDisplay item : scannedItems) {
                if (item != null && item.epc != null) scannedSet.add(item.epc);
            }
        }

        for (String epc : catalogEpcs) {
            if (!scannedSet.contains(epc)) missing.add(epc);
        }
        return missing;
    }

    public boolean isItemScanned(String epc, List<ScannedItemDisplay> scannedItems) {
        if (epc == null || scannedItems == null) return false;
        for (ScannedItemDisplay item : scannedItems) {
            if (item != null && item.epc != null && item.epc.equalsIgnoreCase(epc)) {
                return true;
            }
        }
        return false;
    }

    public String buildFilterDialogTitle() {
        return isArabic ? "🔍 فلترة النتائج" : "🔍 Filter Results";
    }

    public String buildEmptyResultMessage() {
        return isArabic ? "لا توجد نتائج مطابقة" : "No matching results";
    }

    public String buildNoCatalogMessage() {
        return isArabic ? "⚠️ لا يوجد كتالوج محمّل" : "⚠️ No catalog loaded";
    }

    public String buildNoMissingMessage() {
        return isArabic ? "✅ لا توجد قطع مفقودة" : "✅ No missing items";
    }

    public String buildMissingItemsTitle(int count) {
        return isArabic ? "❌ المفقود (" + count + ")" : "❌ Missing (" + count + ")";
    }

    public String buildMissingItemLabel(String epc, InventoryItem item) {
        String title = item != null && item.getTitle() != null ? item.getTitle() : epc;
        return "❌ " + title + "\n" + epc;
    }

    public String[] buildMissingItemsList(List<String> missingEpcs,
                                            Map<String, InventoryItem> catalogItemsMap) {
        if (missingEpcs == null || missingEpcs.isEmpty()) return new String[0];
        String[] result = new String[missingEpcs.size()];
        for (int i = 0; i < missingEpcs.size(); i++) {
            String epc = missingEpcs.get(i);
            InventoryItem item = catalogItemsMap != null ? catalogItemsMap.get(epc) : null;
            result[i] = buildMissingItemLabel(epc, item);
        }
        return result;
    }

    public void reset() {
        currentFilter = FILTER_ALL;
        currentSearchQuery = "";
    }

    public String buildFilterStats(List<ScannedItemDisplay> allItems,
                                     Set<String> catalogEpcs) {
        if (allItems == null) return "";
        int total = 0, found = 0, unexpected = 0, duplicates = 0;

        for (ScannedItemDisplay item : allItems) {
            if (item == null || item.epc == null) continue;
            total++;
            boolean inCatalog = catalogEpcs != null && catalogEpcs.contains(item.epc);
            if (inCatalog) found++; else unexpected++;
            if (item.count > 1) duplicates++;
        }

        int missing = catalogEpcs != null ? Math.max(0, catalogEpcs.size() - found) : 0;

        return isArabic ?
                "📊 الإجمالي: " + total + " | ✅ موجود: " + found
                        + " | ❌ مفقود: " + missing + " | ⚠️ إضافي: " + unexpected
                        + " | 📊 مكرر: " + duplicates
                :
                "📊 Total: " + total + " | ✅ Found: " + found
                        + " | ❌ Missing: " + missing + " | ⚠️ Unexpected: " + unexpected
                        + " | 📊 Dup: " + duplicates;
    }
}
