package com.example.uhf.viewmodel;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.uhf.engine.MiraInventoryEngine;
import com.example.uhf.model.InventoryItem;
import com.example.uhf.model.ScannedItemDisplay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ═══════════════════════════════════════════════════════════════════
 *  MiraInventoryViewModel — العقل المُدبِّر لشاشة الجرد الاحترافية
 * ═══════════════════════════════════════════════════════════════════
 *  
 *  🎯 الهدف:
 *  حفظ كل بيانات الجرد في مكان آمن يعيش أطول من دورة حياة الـ Fragment،
 *  مما يمنع فقدان:
 *  - القائمة الممسوحة (scannedItemsDisplayList)
 *  - الكتالوج (catalog + barcodeMaps)
 *  - حالة الجلسة (sessionId + inventoryEngine)
 *  - الإعدادات (mode, karatFilter, power, search)
 *  - حالة المسح (isScanning, scanStartTime)
 *  
 *  🔄 الفرق بينه وبين Fragment:
 *  - Fragment: يُدمَّر عند onDestroyView (تبديل التبويبات)
 *  - ViewModel: يعيش مع الـ Activity (UHFMainActivity) — لا يُدمَّر إلا عند إغلاق التطبيق
 *  
 *  📌 ملاحظة مهمة: نستخدم AndroidViewModel لأن MiraInventoryEngine يحتاج Context.
 *  
 *  ⚙️ المستوى: Activity-scoped (يُشارك بين كل الـ Fragments عبر requireActivity())
 */
public class MiraInventoryViewModel extends AndroidViewModel {

    private static final String TAG = "MiraInventoryVM";

    // ═══════════════════════════════════════════════════════════════════
    //  1. البيانات الحية (LiveData) — تُراقَب من الـ Fragment
    // ═══════════════════════════════════════════════════════════════════

    /** 📋 قائمة العناصر الممسوحة (المصدر الوحيد للحقيقة) */
    private final MutableLiveData<List<ScannedItemDisplay>> scannedItemsLiveData =
            new MutableLiveData<>(new ArrayList<>());

    /** 📊 العدد المتوقع (من الكتالوج) */
    private final MutableLiveData<Integer> expectedCountLiveData =
            new MutableLiveData<>(0);

    /** 🔢 عدد العناصر الممسوحة (مشتق من القائمة — لكنه مخزّن لتسريع UI) */
    private final MutableLiveData<Integer> scannedCountLiveData =
            new MutableLiveData<>(0);

    /** 🟢 هل الجرد نشط حالياً؟ */
    private final MutableLiveData<Boolean> isScanningLiveData =
            new MutableLiveData<>(false);

    /** 🆔 معرّف الجلسة الحالية */
    private final MutableLiveData<String> sessionIdLiveData =
            new MutableLiveData<>("");

    /** 🎛️ نمط الجرد: full / category / blind */
    private final MutableLiveData<String> currentModeLiveData =
            new MutableLiveData<>("full");

    /** 💎 فلتر العيار: all / 24K / 22K / 21K / 18K / 14K */
    private final MutableLiveData<String> currentKaratFilterLiveData =
            new MutableLiveData<>("all");

    /** ⚡ قدرة الهوائي (dBm) */
    private final MutableLiveData<Integer> currentPowerDbmLiveData =
            new MutableLiveData<>(26);

    /** 🔍 نص البحث الحالي */
    private final MutableLiveData<String> currentSearchQueryLiveData =
            new MutableLiveData<>("");

    /** ⏱️ وقت بدء الجرد (elapsedRealtime) */
    private final MutableLiveData<Long> scanStartTimeLiveData =
            new MutableLiveData<>(0L);

    // ═══════════════════════════════════════════════════════════════════
    //  2. الخرائط المشتركة (Maps) — ليست LiveData (لا تُراقب، تُقرأ فقط)
    // ═══════════════════════════════════════════════════════════════════

    /** 📦 EPCs الموجودة في الكتالوج (للتحقق السريع) */
    private final Set<String> catalogEpcs =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** 📦 خريطة EPC → InventoryItem (للتفاصيل الكاملة) */
    private final Map<String, InventoryItem> catalogItemsMap =
            new ConcurrentHashMap<>();

    /** 🔄 خريطة باركود → EPC */
    private final Map<String, String> barcodeToEpcMap =
            new ConcurrentHashMap<>();

    /** 🔄 خريطة EPC → باركود */
    private final Map<String, String> epcToBarcodeMap =
            new ConcurrentHashMap<>();

    /** 🔒 سجل القراءات الأخيرة (لمنع التكرار) */
    private final Map<String, Long> recentTagReads =
            new ConcurrentHashMap<>();

    // ═══════════════════════════════════════════════════════════════════
    //  3. محرّك الجرد (Engine) — كائن حي داخل ViewModel
    // ═══════════════════════════════════════════════════════════════════

    /** 🎯 محرّك الجرد — يحتفظ بحالة الجلسة الكاملة */
    private MiraInventoryEngine inventoryEngine;

    /** 🆔 معرف الجهاز (يُحسب مرة واحدة) */
    private final String deviceId;

    // ═══════════════════════════════════════════════════════════════════
    //  4. البنّاء (Constructor)
    // ═══════════════════════════════════════════════════════════════════

    public MiraInventoryViewModel(@NonNull Application application) {
        super(application);
        this.deviceId = computeDeviceId(application);
        
        // 🎯 إنشاء المحرّك مرة واحدة وتبقى الجلسة محفوظة طالما ViewModel حي
        try {
            String sessionId = generateSessionId();
            this.inventoryEngine = new MiraInventoryEngine(
                    application, sessionId, deviceId);
            this.sessionIdLiveData.setValue(sessionId);
            Log.d(TAG, "ViewModel initialized. Session: " + sessionId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize inventory engine", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  5. Getters (للـ LiveData — يُراقَب من الـ Fragment)
    // ═══════════════════════════════════════════════════════════════════

    public LiveData<List<ScannedItemDisplay>> getScannedItems() {
        return scannedItemsLiveData;
    }

    public LiveData<Integer> getExpectedCount() {
        return expectedCountLiveData;
    }

    public LiveData<Integer> getScannedCount() {
        return scannedCountLiveData;
    }

    public LiveData<Boolean> getIsScanning() {
        return isScanningLiveData;
    }

    public LiveData<String> getSessionId() {
        return sessionIdLiveData;
    }

    public LiveData<String> getCurrentMode() {
        return currentModeLiveData;
    }

    public LiveData<String> getCurrentKaratFilter() {
        return currentKaratFilterLiveData;
    }

    public LiveData<Integer> getCurrentPowerDbm() {
        return currentPowerDbmLiveData;
    }

    public LiveData<String> getCurrentSearchQuery() {
        return currentSearchQueryLiveData;
    }

    public LiveData<Long> getScanStartTime() {
        return scanStartTimeLiveData;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  6. Getters (المباشرة — للخرائط والمحرّك)
    // ═══════════════════════════════════════════════════════════════════

    public Set<String> getCatalogEpcs() {
        return catalogEpcs;
    }

    public Map<String, InventoryItem> getCatalogItemsMap() {
        return catalogItemsMap;
    }

    public Map<String, String> getBarcodeToEpcMap() {
        return barcodeToEpcMap;
    }

    public Map<String, String> getEpcToBarcodeMap() {
        return epcToBarcodeMap;
    }

    public Map<String, Long> getRecentTagReads() {
        return recentTagReads;
    }

    public MiraInventoryEngine getInventoryEngine() {
        return inventoryEngine;
    }

    public String getDeviceId() {
        return deviceId;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  7. عمليات قائمة الممسوحات (Scanned Items Operations)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 🆕 إضافة أو تحديث عنصر ممسوح.
     * 
     * @return نوع التغذية الراجعة (KNOWN / UNKNOWN / DUPLICATE) — لعرضها للمستخدم
     */
    public ScanFeedback addOrUpdateScannedItem(@NonNull ScannedItemDisplay newItem) {
        List<ScannedItemDisplay> current = scannedItemsLiveData.getValue();
        if (current == null) current = new ArrayList<>();

        // ✅ البحث عن عنصر مطابق
        for (ScannedItemDisplay existing : current) {
            if (existing != null && newItem.epc.equals(existing.epc)) {
                // 📈 تحديث العنصر الموجود
                existing.count++;
                existing.rssi = newItem.rssi;
                existing.time = newItem.time;
                // إعادة نشر القائمة لتحديث الواجهة
                scannedItemsLiveData.setValue(new ArrayList<>(current));
                return ScanFeedback.DUPLICATE;
            }
        }

        // 🆕 إضافة عنصر جديد في المقدمة
        List<ScannedItemDisplay> updated = new ArrayList<>(current);
        updated.add(0, newItem);
        scannedItemsLiveData.setValue(updated);

        // 📊 تحديث العداد
        scannedCountLiveData.setValue(updated.size());

        // ✅ تحديد نوع التغذية الراجعة
        return catalogEpcs.contains(newItem.epc)
                ? ScanFeedback.KNOWN
                : ScanFeedback.UNKNOWN;
    }

    /**
     * 🗑️ حذف عنصر ممسوح حسب EPC.
     */
    public void removeScannedItem(@NonNull String epc) {
        List<ScannedItemDisplay> current = scannedItemsLiveData.getValue();
        if (current == null || current.isEmpty()) return;

        List<ScannedItemDisplay> updated = new ArrayList<>();
        for (ScannedItemDisplay item : current) {
            if (item != null && !epc.equals(item.epc)) {
                updated.add(item);
            }
        }
        scannedItemsLiveData.setValue(updated);
        scannedCountLiveData.setValue(updated.size());
    }

    /**
     * 🧹 مسح كل العناصر الممسوحة (بداية جلسة جديدة).
     */
    public void clearScannedItems() {
        scannedItemsLiveData.setValue(new ArrayList<>());
        scannedCountLiveData.setValue(0);
        recentTagReads.clear();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  8. عمليات الكتالوج (Catalog Operations)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 📥 تحميل الكتالوج من السيرفر (يُستدعى من الـ Fragment).
     */
    /**
 * 📥 تحميل الكتالوج من السيرفر (يُستدعى من الـ Fragment).
 */
public void updateCatalog(@NonNull Map<String, InventoryItem> catalog) {
    catalogEpcs.clear();
    catalogItemsMap.clear();
    barcodeToEpcMap.clear();
    epcToBarcodeMap.clear();

    for (Map.Entry<String, InventoryItem> entry : catalog.entrySet()) {
        String epc = entry.getKey();
        InventoryItem item = entry.getValue();
        if (epc == null || item == null) continue;

        catalogEpcs.add(epc);
        catalogItemsMap.put(epc, item);

        // ✅ بناء خرائط الباركود — باستخدام الأسماء الصحيحة
        String gtin = item.getGtin();
        if (gtin != null && !gtin.isEmpty()) {
            barcodeToEpcMap.put(gtin, epc);
            epcToBarcodeMap.put(epc, gtin);
        }

        String serial = item.getSerial();
        if (serial != null && !serial.isEmpty()) {
            barcodeToEpcMap.put(serial, epc);
            epcToBarcodeMap.put(epc, serial);
        }
    }

    expectedCountLiveData.setValue(catalog.size());
    Log.d(TAG, "Catalog loaded: " + catalog.size() + " items");
}

    /**
     * 🧹 مسح الكتالوج (للجرد الأعمى).
     */
    public void clearCatalog() {
        catalogEpcs.clear();
        catalogItemsMap.clear();
        barcodeToEpcMap.clear();
        epcToBarcodeMap.clear();
        expectedCountLiveData.setValue(0);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  9. عمليات الحالة والإعدادات (State & Settings)
    // ═══════════════════════════════════════════════════════════════════

    public void setScanning(boolean scanning) {
        isScanningLiveData.setValue(scanning);
        if (scanning) {
            scanStartTimeLiveData.setValue(android.os.SystemClock.elapsedRealtime());
        }
    }

    public void setCurrentMode(@NonNull String mode) {
        currentModeLiveData.setValue(mode);
    }

    public void setCurrentKaratFilter(@NonNull String filter) {
        currentKaratFilterLiveData.setValue(filter);
    }

    public void setCurrentPowerDbm(int dbm) {
        currentPowerDbmLiveData.setValue(dbm);
    }

    public void setCurrentSearchQuery(@NonNull String query) {
        currentSearchQueryLiveData.setValue(query);
    }

    /**
     * 🔄 بدء جلسة جديدة (بعد الاعتماد أو إعادة الضبط).
     */
    public void startNewSession() {
        try {
            String newSessionId = generateSessionId();
            this.inventoryEngine = new MiraInventoryEngine(
                    getApplication(), newSessionId, deviceId);
            sessionIdLiveData.setValue(newSessionId);
            
            clearScannedItems();
            scanStartTimeLiveData.setValue(0L);
            isScanningLiveData.setValue(false);
            
            Log.d(TAG, "New session started: " + newSessionId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start new session", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  10. دوال مساعدة خاصة (Private Helpers)
    // ═══════════════════════════════════════════════════════════════════

    private static String generateSessionId() {
        return "SESS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private static String computeDeviceId(@NonNull Application app) {
        try {
            return android.provider.Settings.Secure.getString(
                    app.getContentResolver(),
                    android.provider.Settings.Secure.ANDROID_ID
            );
        } catch (Exception e) {
            return "UNKNOWN-DEVICE";
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  11. دورة الحياة — onCleared
    // ═══════════════════════════════════════════════════════════════════

    @Override
    protected void onCleared() {
        super.onCleared();
        Log.d(TAG, "ViewModel cleared — releasing resources");
        
        // ✅ تنظيف صريح
        scannedItemsLiveData.setValue(new ArrayList<>());
        recentTagReads.clear();
        catalogEpcs.clear();
        catalogItemsMap.clear();
        barcodeToEpcMap.clear();
        epcToBarcodeMap.clear();
        
        inventoryEngine = null;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  12. أنواع مساعدة (Helper Types)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 🎯 نوع التغذية الراجعة عند إضافة عنصر جديد.
     */
    public enum ScanFeedback {
        /** ✅ عنصر معروف في الكتالوج (أول قراءة) */
        KNOWN,
        /** ⚠️ عنصر غير معروف (خارج الكتالوج) */
        UNKNOWN,
        /** 🔄 عنصر مكرر (قُرئ سابقاً) */
        DUPLICATE
    }
}
