package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.util.Log;

import com.example.uhf.api.MiraApiClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 🏗️ IdentitySyncManager - مدير المزامنة الاحترافي v3.0
 * 
 * الميزات:
 * - ✅ CRUD كامل على السجلات
 * - ✅ Retry تلقائي مع Exponential Backoff
 * - ✅ فحص الشبكة قبل المزامنة
 * - ✅ تتبع المحاولات والإحصائيات
 * - ✅ منع التزامن المتوازي
 * - ✅ إعادة إرسال سجل مفرد
 * - ✅ تعديل سجل قبل الإرسال
 * 
 * @author Mira Team
 * @version 3.0
 */
public class IdentitySyncManager {

    private static final String TAG = "IdentitySyncManager";
    private static final String OFFLINE_PREFS = "identity_studio_cache";
    
    private static final int MAX_PENDING_ITEMS = 100;
    private static final int MAX_SYNC_ATTEMPTS = 5;
    private static final long INITIAL_RETRY_DELAY_MS = 2000;
    private static final long MAX_RETRY_DELAY_MS = 60000;

    private final Context context;
    private final SharedPreferences offlineCache;
    private final MiraApiClient apiClient;
    private final ExecutorService executor;

    private final AtomicBoolean isSyncing = new AtomicBoolean(false);

    // ==================== Callbacks ====================

    public interface SyncCallback {
        void onSyncComplete(int syncedCount);
        void onSyncError(String error);
    }

    public interface DetailedSyncCallback {
        void onSyncStart(int totalCount);
        void onItemSynced(int itemIndex, int totalCount, String epc);
        void onItemFailed(int itemIndex, int totalCount, String epc, String error);
        void onSyncComplete(int syncedCount, int failedCount);
        void onSyncError(String error);
    }

    public interface SingleItemCallback {
        void onSuccess(String epc);
        void onError(String epc, String error);
    }

    // ==================== Data Model ====================

    /**
     * ✅ نموذج سجل المزامنة
     */
    public static class SyncLogItem {
        public String id;
        public String epc;
        public String title;
        public String karat;
        public double grossWeight;
        public double priceUSD;
        public String category;
        public String location;
        public int branchId;
        public int userId;
        public long savedAt;
        public long lastAttempt;
        public int attempts;
        public String status;
        public String lastError;
        public JSONObject payload;

        public String getFormattedSavedAt() {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());
                return sdf.format(new Date(savedAt));
            } catch (Exception e) {
                return "";
            }
        }

        public String getFormattedLastAttempt() {
            if (lastAttempt <= 0) return "-";
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault());
                return sdf.format(new Date(lastAttempt));
            } catch (Exception e) {
                return "-";
            }
        }
    }

    // ==================== Constructor ====================

    public IdentitySyncManager(Context context, ExecutorService executor) {
        this.context = context;
        this.executor = executor;
        this.offlineCache = context.getSharedPreferences(OFFLINE_PREFS, Context.MODE_PRIVATE);
        this.apiClient = MiraApiClient.getInstance(context);
    }

    // ==================== CREATE ====================

    public void saveEncodeOffline(JSONObject payload) {
        try {
            String json = offlineCache.getString("pending_encodes", "[]");
            JSONArray arr = new JSONArray(json);
            
            String targetEpc = payload.optString("epc", "");
            if (targetEpc.isEmpty()) {
                targetEpc = "TEMP_" + System.currentTimeMillis();
                payload.put("epc", targetEpc);
            }
            
            for (int i = arr.length() - 1; i >= 0; i--) {
                JSONObject existing = arr.optJSONObject(i);
                if (existing != null && targetEpc.equals(existing.optString("epc"))) {
                    arr.remove(i);
                }
            }

            JSONObject enriched = new JSONObject(payload.toString());
            enriched.put("_id", "SYNC_" + System.currentTimeMillis() + "_" + targetEpc);
            enriched.put("_sync_attempts", 0);
            enriched.put("_saved_at", System.currentTimeMillis());
            enriched.put("_last_attempt", 0L);
            enriched.put("_last_error", "");
            enriched.put("_sync_status", "pending");

            arr.put(enriched);
            
            while (arr.length() > MAX_PENDING_ITEMS) {
                arr.remove(0);
            }
            
            offlineCache.edit().putString("pending_encodes", arr.toString()).apply();
            
            Log.d(TAG, "✅ Saved offline: EPC=" + targetEpc + ", total=" + arr.length());
        } catch (Exception e) {
            Log.e(TAG, "saveEncodeOffline error: " + e.getMessage(), e);
        }
    }

    // ==================== READ ====================

    public int getPendingCount() {
        try {
            String json = offlineCache.getString("pending_encodes", "[]");
            return new JSONArray(json).length();
        } catch (Exception e) {
            return 0;
        }
    }

    public JSONArray getPendingItems() {
        try {
            String json = offlineCache.getString("pending_encodes", "[]");
            return new JSONArray(json);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public List<SyncLogItem> getAllSyncLogs() {
        List<SyncLogItem> list = new ArrayList<>();
        try {
            String json = offlineCache.getString("pending_encodes", "[]");
            JSONArray arr = new JSONArray(json);

            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;

                SyncLogItem item = new SyncLogItem();
                item.id = obj.optString("_id", "ID_" + i);
                item.epc = obj.optString("epc", "");
                item.title = obj.optString("title", "");
                item.karat = obj.optString("purity_code", obj.optString("karat", ""));
                item.grossWeight = obj.optDouble("gross_weight", 0);
                item.priceUSD = obj.optDouble("retail_price_usd", obj.optDouble("retail_price", 0));
                item.category = obj.optString("category", "");
                item.location = obj.optString("location", "");
                item.branchId = obj.optInt("branch_id", 0);
                item.userId = obj.optInt("user_id", 0);
                item.savedAt = obj.optLong("_saved_at", 0);
                item.lastAttempt = obj.optLong("_last_attempt", 0);
                item.attempts = obj.optInt("_sync_attempts", 0);
                item.status = obj.optString("_sync_status", "pending");
                item.lastError = obj.optString("_last_error", "");
                item.payload = obj;

                list.add(item);
            }
        } catch (Exception e) {
            Log.e(TAG, "getAllSyncLogs error: " + e.getMessage());
        }
        return list;
    }

    public SyncLogItem getSyncLogById(String id) {
        if (id == null) return null;
        for (SyncLogItem item : getAllSyncLogs()) {
            if (id.equals(item.id)) return item;
        }
        return null;
    }

    // ==================== UPDATE ====================

    public boolean updateSyncLog(String id, JSONObject newPayload) {
        try {
            String json = offlineCache.getString("pending_encodes", "[]");
            JSONArray arr = new JSONArray(json);
            boolean updated = false;

            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null && id.equals(obj.optString("_id"))) {
                    newPayload.put("_id", id);
                    newPayload.put("_sync_attempts", obj.optInt("_sync_attempts", 0));
                    newPayload.put("_saved_at", obj.optLong("_saved_at", System.currentTimeMillis()));
                    newPayload.put("_last_attempt", 0L);
                    newPayload.put("_last_error", "");
                    newPayload.put("_sync_status", "pending");

                    arr.put(i, newPayload);
                    updated = true;
                    break;
                }
            }

            if (updated) {
                offlineCache.edit().putString("pending_encodes", arr.toString()).apply();
                Log.d(TAG, "✅ Updated sync log: " + id);
            }
            return updated;
        } catch (Exception e) {
            Log.e(TAG, "updateSyncLog error: " + e.getMessage());
            return false;
        }
    }

    // ==================== DELETE ====================

    public boolean deleteSyncLog(String id) {
        if (id == null) return false;
        try {
            String json = offlineCache.getString("pending_encodes", "[]");
            JSONArray arr = new JSONArray(json);
            JSONArray newArr = new JSONArray();
            boolean deleted = false;

            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null && id.equals(obj.optString("_id"))) {
                    deleted = true;
                } else if (obj != null) {
                    newArr.put(obj);
                }
            }

            if (deleted) {
                offlineCache.edit().putString("pending_encodes", newArr.toString()).apply();
                Log.d(TAG, "✅ Deleted sync log: " + id);
            }
            return deleted;
        } catch (Exception e) {
            Log.e(TAG, "deleteSyncLog error: " + e.getMessage());
            return false;
        }
    }

    public void removePendingEncode(String epc) {
        if (epc == null || epc.isEmpty()) return;
        try {
            String json = offlineCache.getString("pending_encodes", "[]");
            JSONArray arr = new JSONArray(json);
            JSONArray newArr = new JSONArray();
            String cleanEpc = epc.trim();

            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null && !cleanEpc.equals(obj.optString("epc", ""))) {
                    newArr.put(obj);
                }
            }

            offlineCache.edit().putString("pending_encodes", newArr.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "removePendingEncode error", e);
        }
    }

    public void clearAllPending() {
        try {
            offlineCache.edit().putString("pending_encodes", "[]").apply();
        } catch (Exception e) {
            Log.e(TAG, "clearAllPending error", e);
        }
    }

    // ==================== SYNC ====================

    public void syncPendingEncodes(int branchId, SyncCallback callback) {
        syncPendingEncodesDetailed(branchId, new DetailedSyncCallback() {
            @Override public void onSyncStart(int totalCount) { }
            @Override public void onItemSynced(int i, int t, String epc) { }
            @Override public void onItemFailed(int i, int t, String epc, String error) { }
            @Override public void onSyncComplete(int synced, int failed) {
                if (callback != null) callback.onSyncComplete(synced);
            }
            @Override public void onSyncError(String error) {
                if (callback != null) callback.onSyncError(error);
            }
        });
    }

    public void syncPendingEncodesDetailed(int branchId, DetailedSyncCallback callback) {
        if (!isSyncing.compareAndSet(false, true)) {
            Log.w(TAG, "⚠️ Sync already in progress");
            if (callback != null) callback.onSyncError("Sync already in progress");
            return;
        }

        if (executor == null || executor.isShutdown()) {
            isSyncing.set(false);
            if (callback != null) callback.onSyncError("Executor is shutdown");
            return;
        }

        executor.execute(() -> {
            try {
                if (!isNetworkAvailable()) {
                    if (callback != null) callback.onSyncError("No network connection");
                    return;
                }

                String json = offlineCache.getString("pending_encodes", "[]");
                JSONArray arr = new JSONArray(json);
                int totalCount = arr.length();

                if (totalCount == 0) {
                    if (callback != null) callback.onSyncComplete(0, 0);
                    return;
                }

                if (callback != null) callback.onSyncStart(totalCount);

                JSONArray remainingArr = new JSONArray();
                int synced = 0;
                int failed = 0;

                for (int i = 0; i < arr.length(); i++) {
                    JSONObject payload = arr.optJSONObject(i);
                    if (payload == null) continue;

                    String epc = payload.optString("epc", "Unknown");
                    int attempts = payload.optInt("_sync_attempts", 0);

                    if (attempts >= MAX_SYNC_ATTEMPTS) {
                        payload.put("_sync_status", "failed_max_attempts");
                        remainingArr.put(payload);
                        failed++;
                        if (callback != null) 
                            callback.onItemFailed(i, totalCount, epc, "Max attempts reached");
                        continue;
                    }

                    try {
                        if (!payload.has("branch_id") || payload.optInt("branch_id", 0) <= 0) {
                            payload.put("branch_id", branchId);
                        }

                        payload.put("_sync_attempts", attempts + 1);
                        payload.put("_last_attempt", System.currentTimeMillis());
                        payload.put("_sync_status", "syncing");

                        MiraApiClient.ApiResponse response = apiClient.encodeItem(payload);

                        if (response != null && response.isSuccess) {
                            synced++;
                            payload.put("_sync_status", "synced");
                            payload.put("_synced_at", System.currentTimeMillis());
                            if (callback != null) callback.onItemSynced(i, totalCount, epc);
                        } else {
                            String errorMsg = (response != null && response.error != null) ?
                                    response.error : "Unknown error";
                            payload.put("_sync_status", "failed");
                            payload.put("_last_error", errorMsg);
                            remainingArr.put(payload);
                            failed++;
                            if (callback != null) 
                                callback.onItemFailed(i, totalCount, epc, errorMsg);

                            if (response != null && response.isNetworkError) {
                                long delay = calculateBackoffDelay(attempts);
                                try { Thread.sleep(delay); } catch (InterruptedException e) { 
                                    Thread.currentThread().interrupt(); break; 
                                }
                            }
                        }
                    } catch (Exception e) {
                        payload.put("_sync_status", "failed");
                        payload.put("_last_error", e.getMessage());
                        remainingArr.put(payload);
                        failed++;
                        if (callback != null) 
                            callback.onItemFailed(i, totalCount, epc, e.getMessage());
                    }
                }

                offlineCache.edit().putString("pending_encodes", remainingArr.toString()).apply();
                saveSyncStats(synced, failed);

                if (callback != null) callback.onSyncComplete(synced, failed);

            } catch (Exception e) {
                Log.e(TAG, "syncPendingEncodesDetailed error: " + e.getMessage(), e);
                if (callback != null) callback.onSyncError(e.getMessage());
            } finally {
                isSyncing.set(false);
            }
        });
    }

    public void syncSingleItem(String id, int branchId, SingleItemCallback callback) {
        if (id == null) {
            if (callback != null) callback.onError("", "Invalid ID");
            return;
        }

        executor.execute(() -> {
            try {
                if (!isNetworkAvailable()) {
                    if (callback != null) callback.onError("", "No network");
                    return;
                }

                SyncLogItem item = getSyncLogById(id);
                if (item == null) {
                    if (callback != null) callback.onError("", "Item not found");
                    return;
                }

                JSONObject payload = item.payload;
                if (!payload.has("branch_id") || payload.optInt("branch_id", 0) <= 0) {
                    payload.put("branch_id", branchId);
                }

                payload.put("_sync_attempts", item.attempts + 1);
                payload.put("_last_attempt", System.currentTimeMillis());
                payload.put("_sync_status", "syncing");

                MiraApiClient.ApiResponse response = apiClient.encodeItem(payload);

                if (response != null && response.isSuccess) {
                    deleteSyncLog(id);
                    if (callback != null) callback.onSuccess(item.epc);
                } else {
                    String errorMsg = (response != null && response.error != null) ?
                            response.error : "Unknown error";
                    payload.put("_sync_status", "failed");
                    payload.put("_last_error", errorMsg);
                    updateSyncLogPayload(id, payload);
                    if (callback != null) callback.onError(item.epc, errorMsg);
                }
            } catch (Exception e) {
                if (callback != null) callback.onError("", e.getMessage());
            }
        });
    }

    private void updateSyncLogPayload(String id, JSONObject newPayload) {
        try {
            String json = offlineCache.getString("pending_encodes", "[]");
            JSONArray arr = new JSONArray(json);

            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null && id.equals(obj.optString("_id"))) {
                    arr.put(i, newPayload);
                    break;
                }
            }

            offlineCache.edit().putString("pending_encodes", arr.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "updateSyncLogPayload error: " + e.getMessage());
        }
    }

    // ==================== Utilities ====================

    public boolean isEpcDuplicateLocally(String epc) {
        if (epc == null || epc.trim().isEmpty()) return false;
        String cleanEpc = epc.trim().toUpperCase();

        try {
            String pendingJson = offlineCache.getString("pending_encodes", "[]");
            JSONArray pendingArray = new JSONArray(pendingJson);

            for (int i = 0; i < pendingArray.length(); i++) {
                JSONObject item = pendingArray.optJSONObject(i);
                if (item != null) {
                    String storedEpc = item.optString("epc", "").trim().toUpperCase();
                    if (cleanEpc.equals(storedEpc)) return true;
                }
            }

            String draftJson = offlineCache.getString("current_draft", null);
            if (draftJson != null) {
                JSONObject draft = new JSONObject(draftJson);
                String draftEpc = draft.optString("epc", "").trim().toUpperCase();
                if (cleanEpc.equals(draftEpc)) return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking duplicate EPC: " + e.getMessage());
        }
        return false;
    }

    public String getSyncStats() {
        try {
            long lastSync = offlineCache.getLong("last_sync_time", 0);
            int totalSynced = offlineCache.getInt("total_synced", 0);
            int totalFailed = offlineCache.getInt("total_failed", 0);

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
            String lastSyncStr = lastSync > 0 ? sdf.format(new Date(lastSync)) : "Never";

            return String.format(Locale.US,
                    "Last Sync: %s\nTotal Synced: %d\nTotal Failed: %d\nPending: %d",
                    lastSyncStr, totalSynced, totalFailed, getPendingCount());
        } catch (Exception e) {
            return "Stats unavailable";
        }
    }

    public boolean isSyncing() {
        return isSyncing.get();
    }

    // ==================== Private Helpers ====================

    private boolean isNetworkAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager) 
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;

            android.net.Network network = cm.getActiveNetwork();
            if (network == null) return false;

            NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
            if (capabilities == null) return false;

            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return false;
        }
    }

    private long calculateBackoffDelay(int attempts) {
        long delay = INITIAL_RETRY_DELAY_MS * (long) Math.pow(2, attempts);
        return Math.min(delay, MAX_RETRY_DELAY_MS);
    }

    private void saveSyncStats(int synced, int failed) {
        try {
            int totalSynced = offlineCache.getInt("total_synced", 0) + synced;
            int totalFailed = offlineCache.getInt("total_failed", 0) + failed;

            offlineCache.edit()
                    .putLong("last_sync_time", System.currentTimeMillis())
                    .putInt("total_synced", totalSynced)
                    .putInt("total_failed", totalFailed)
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "saveSyncStats error", e);
        }
    }
}
