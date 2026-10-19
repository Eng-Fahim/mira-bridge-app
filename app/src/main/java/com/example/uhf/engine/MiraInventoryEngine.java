package com.example.uhf.engine;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import com.example.uhf.model.InventoryItem;
import com.example.uhf.security.KeystoreSigner;
import com.example.uhf.storage.EncryptedQueueManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class MiraInventoryEngine {

    private static final String TAG = "MIRA_InventoryEngine";

    private final String clientSessionId;
    private final String deviceId;
    private final EncryptedQueueManager queueManager;

    private final Map<String, InventoryItem> catalogByEpc = new ConcurrentHashMap<>();
    private final Map<String, InventoryItem> scannedByEpcMap = new ConcurrentHashMap<>();

    private final AtomicInteger totalReads = new AtomicInteger(0);
    private final AtomicInteger uniqueCount = new AtomicInteger(0);
    private final AtomicInteger duplicateCount = new AtomicInteger(0);

    private long sessionStartTime = 0;
    
    /**
 * ✅ إعادة تعيين الجلسة الحالية
 * يمسح كل ما تم مسحه، لكن يحتفظ بالكتالوج
 */
public void clearSession() {
    try {
        scannedByEpcMap.clear();
        totalReads.set(0);
        uniqueCount.set(0);
        duplicateCount.set(0);
        sessionStartTime = SystemClock.elapsedRealtime();
        
        Log.d(TAG, "✅ Session cleared: " + clientSessionId);
    } catch (Exception e) {
        Log.e(TAG, "clearSession error: " + e.getMessage(), e);
    }
}
    public MiraInventoryEngine(Context context, String clientSessionId, String deviceId) {
        this.clientSessionId = clientSessionId;
        this.deviceId = deviceId;
        this.queueManager = new EncryptedQueueManager(context);

        KeystoreSigner.generateHardwareKeyIfNeeded();
    }

    public void startSession(Map<String, InventoryItem> loadedCatalog) {
        this.catalogByEpc.clear();
        this.scannedByEpcMap.clear();
        this.totalReads.set(0);
        this.uniqueCount.set(0);
        this.duplicateCount.set(0);

        if (loadedCatalog != null) {
            for (Map.Entry<String, InventoryItem> entry : loadedCatalog.entrySet()) {
                String canonicalEpc = InventoryItem.normalizeEpc(entry.getKey());
                this.catalogByEpc.put(canonicalEpc, entry.getValue());
            }
        }
        this.sessionStartTime = SystemClock.elapsedRealtime();
    }

/**
 * ✅ [Fix-A4] تحديث الكتالوج بعد التحميل المتأخر
 *
 * يُستدعى من MiraInventoryProFragment.loadCatalogInBackground
 * بعد أن يصبح الكتالوج متاحاً — يُبلّغ الـ Engine ليسجّل
 * expected_count الصحيح في التقرير المرسل للسيرفر.
 */
public void setCatalog(Map<String, InventoryItem> catalog) {
    try {
        if (catalog == null) {
            Log.d(TAG, "setCatalog: null — skipping");
            return;
        }

        catalogByEpc.clear();
        for (Map.Entry<String, InventoryItem> entry : catalog.entrySet()) {
            String canonicalEpc = InventoryItem.normalizeEpc(entry.getKey());
            catalogByEpc.put(canonicalEpc, entry.getValue());
        }

        Log.d(TAG, "✅ Catalog updated in Engine: " + catalogByEpc.size() + " items");
    } catch (Exception e) {
        Log.e(TAG, "setCatalog error", e);
    }
}
    public void onTagScanned(String rawEpc, int rssi) {
    String epc = InventoryItem.normalizeEpc(rawEpc);
    if (epc.isEmpty()) return;

    totalReads.incrementAndGet();

    scannedByEpcMap.compute(epc, (k, existingItem) -> {
        if (existingItem == null) {
            uniqueCount.incrementAndGet();
            
            InventoryItem catalogMatch = catalogByEpc.get(epc);
            InventoryItem newItem;
            
            if (catalogMatch != null) {
                // ✅ إصلاح: انسخ الكائن (لا تشارك!)
                newItem = new InventoryItem(
                        catalogMatch.getEpc(),
                        catalogMatch.getGtin(),
                        catalogMatch.getSerial(),
                        catalogMatch.getTitle(),
                        catalogMatch.getKarat());
                newItem.setWeight(catalogMatch.getWeight());
                newItem.setPrice(catalogMatch.getPrice());
            } else {
                newItem = new InventoryItem(epc, "", "", "Uncatalogued Asset");
            }
            
            newItem.recordScan(rssi);
            return newItem;
        } else {
            duplicateCount.incrementAndGet();
            existingItem.recordScan(rssi);
            return existingItem;
        }
    });
}
    
    public String getClientSessionId() {
    return clientSessionId;
    }

    public JSONObject buildEvidencePayload() {
        try {
            JSONObject payload = new JSONObject();
            payload.put("client_session_id", clientSessionId);
            payload.put("device_id", deviceId);
            payload.put("timestamp", System.currentTimeMillis() / 1000L);

            JSONObject telemetry = new JSONObject();
            telemetry.put("total_reads", totalReads.get());
            telemetry.put("unique_scanned", uniqueCount.get());
            telemetry.put("duplicates", duplicateCount.get());
            telemetry.put("scan_duration_sec", (SystemClock.elapsedRealtime() - sessionStartTime) / 1000);
            payload.put("telemetry", telemetry);

            JSONArray evidenceArray = new JSONArray();
            for (InventoryItem item : scannedByEpcMap.values()) {
                JSONObject itemObj = new JSONObject();
                itemObj.put("epc", item.getEpc());
                itemObj.put("read_count", item.getReadCount());
                itemObj.put("last_rssi", item.getLastRssi());
                itemObj.put("first_seen", item.getFirstSeenTimestamp());
                itemObj.put("last_seen", item.getLastSeenTimestamp());
                evidenceArray.put(itemObj);
            }
            payload.put("scanned_evidence", evidenceArray);

            Set<String> expectedEpcs = catalogByEpc.keySet();
            Set<String> scannedEpcs = scannedByEpcMap.keySet();

            Set<String> foundEpcs = new HashSet<>(scannedEpcs);
            foundEpcs.retainAll(expectedEpcs);

            Set<String> missingEpcs = new HashSet<>(expectedEpcs);
            missingEpcs.removeAll(scannedEpcs);

            Set<String> unexpectedEpcs = new HashSet<>(scannedEpcs);
            unexpectedEpcs.removeAll(expectedEpcs);

            JSONObject localAnalysis = new JSONObject();
            localAnalysis.put("expected_count", expectedEpcs.size());
            localAnalysis.put("found_count", foundEpcs.size());
            localAnalysis.put("missing_count", missingEpcs.size());
            localAnalysis.put("unexpected_count", unexpectedEpcs.size());
            payload.put("local_display_analysis", localAnalysis);

            String canonicalData = clientSessionId + "|" + deviceId + "|" + uniqueCount.get() + "|" + totalReads.get();
            payload.put("device_signature", KeystoreSigner.signData(canonicalData));

            return payload;
        } catch (Exception e) {
            Log.e(TAG, "Failed to compile evidence payload", e);
            return new JSONObject();
        }
    }

    public void processSessionClose() {
        JSONObject payload = buildEvidencePayload();
        queueManager.enqueuePayload(payload);
    }

    public int getTotalReads() { return totalReads.get(); }
    public int getUniqueCount() { return uniqueCount.get(); }
    public int getDuplicateCount() { return duplicateCount.get(); }



/**
 * ✅ [v4.3] تحديث بيانات قطعة من الكتالوج بعد التحميل
 * 
 * يُستدعى من reclassifyScannedItems() في MiraInventoryProFragment
 * لضمان تطابق سجل التقرير النهائي مع بيانات الكتالوج.
 */
public void updateItemFromCatalog(String epc, String title, String karat,
                                   double weight, double price) {
    try {
        if (epc == null || epc.isEmpty()) return;
        
        String normalized = InventoryItem.normalizeEpc(epc);
        InventoryItem item = scannedByEpcMap.get(normalized);
        
        if (item != null) {
            if (title != null && !title.isEmpty()) item.setTitle(title);
            if (karat != null && !karat.isEmpty()) item.setKarat(karat);
            if (weight > 0) item.setWeight(weight);
            if (price > 0) item.setPrice(price);
            
            Log.d(TAG, "✅ Engine updated: " + normalized 
                    + " | " + title + " | $" + price);
        }
    } catch (Exception e) {
        Log.e(TAG, "updateItemFromCatalog error: " + epc, e);
    }
}
    /**
 * ✅ إضافة EPC للجلسة الحالية (للاسترجاع)
 * 
 * تستخدم نفس منطق onTagScanned لكن بدون زيادة totalReads
 * (لأنها استرجاع، ليس مسحاً جديداً)
 * 
 * @param rawEpc EPC الخام
 * @param rssi   شدة الإشارة
 */
public void addEpc(String rawEpc, int rssi) {
    try {
        String epc = InventoryItem.normalizeEpc(rawEpc);
        if (epc.isEmpty()) return;

        // ⚠️ لا نزيد totalReads هنا — هذا استرجاع وليس مسحاً جديداً
        // ولا نزيد uniqueCount أيضاً لأنها موجودة مسبقاً

        scannedByEpcMap.compute(epc, (k, existingItem) -> {
            if (existingItem == null) {
                // ✅ عنصر جديد في الـ Engine
                uniqueCount.incrementAndGet();
                
                InventoryItem catalogMatch = catalogByEpc.get(epc);
                InventoryItem newItem;
                
                if (catalogMatch != null) {
                    newItem = new InventoryItem(
                            catalogMatch.getEpc(),
                            catalogMatch.getGtin(),
                            catalogMatch.getSerial(),
                            catalogMatch.getTitle(),
                            catalogMatch.getKarat());
                    newItem.setWeight(catalogMatch.getWeight());
                    newItem.setPrice(catalogMatch.getPrice());
                } else {
                    newItem = new InventoryItem(epc, "", "", "Uncatalogued Asset");
                }
                
                newItem.recordScan(rssi);
                return newItem;
            } else {
                // موجود مسبقاً — لا نزيد أي عداد
                existingItem.recordScan(rssi);
                return existingItem;
            }
        });

        Log.d(TAG, "✅ addEpc: " + epc + " (unique=" + uniqueCount.get() + ")");
        
    } catch (Exception e) {
        Log.e(TAG, "addEpc error: " + rawEpc, e);
    }
}
}
