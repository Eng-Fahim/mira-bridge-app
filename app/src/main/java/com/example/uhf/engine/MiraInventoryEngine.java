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

    public void onTagScanned(String rawEpc, int rssi) {
        String epc = InventoryItem.normalizeEpc(rawEpc);
        if (epc.isEmpty()) return;

        totalReads.incrementAndGet();

        scannedByEpcMap.compute(epc, (k, existingItem) -> {
            if (existingItem == null) {
                uniqueCount.incrementAndGet();
                InventoryItem catalogMatch = catalogByEpc.get(epc);
                InventoryItem newItem = (catalogMatch != null) 
                        ? catalogMatch 
                        : new InventoryItem(epc, "", "", "Uncatalogued Asset");
                newItem.recordScan(rssi);
                return newItem;
            } else {
                duplicateCount.incrementAndGet();
                existingItem.recordScan(rssi);
                return existingItem;
            }
        });
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
}
