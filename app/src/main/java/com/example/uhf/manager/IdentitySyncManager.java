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
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 🏗️ IdentitySyncManager - مدير المزامنة الاحترافي
 * 
 * الميزات:
 * - ✅ Retry تلقائي مع Exponential Backoff
 * - ✅ فحص الشبكة قبل المزامنة
 * - ✅ تتبع المحاولات والإحصائيات
 * - ✅ منع التزامن المتوازي
 * - ✅ تفاصيل الأخطاء
 * - ✅ حفظ الوقت والسبب
 * 
 * @author Mira Team
 * @version 2.0
 */
public class IdentitySyncManager {

    private static final String TAG = "IdentitySyncManager";
    private static final String OFFLINE_PREFS = "identity_studio_cache";
    
    // ✅ حدود الحماية
    private static final int MAX_PENDING_ITEMS = 100;        // تقليل من 200
    private static final int MAX_SYNC_ATTEMPTS = 5;           // عدد محاولات المزامنة
    private static final long INITIAL_RETRY_DELAY_MS = 2000;  // 2 ثانية
    private static final long MAX_RETRY_DELAY_MS = 60000;     // دقيقة واحدة

    private final Context context;
    private final SharedPreferences offlineCache;
    private final MiraApiClient apiClient;
    private final ExecutorService executor;

    // ✅ منع المزامنة المتوازية
    private final AtomicBoolean isSyncing = new AtomicBoolean(false);

    public interface SyncCallback {
        void onSyncComplete(int syncedCount);
        void onSyncError(String error);
    }

    /**
     * 🆕 Callback تفصيلي
     */
    public interface DetailedSyncCallback {
        void onSyncStart(int totalCount);
        void onItemSynced(int itemIndex, int totalCount, String epc);
        void onItemFailed(int itemIndex, int totalCount, String epc, String error);
        void onSyncComplete(int syncedCount, int failedCount);
        void onSyncError(String error);
    }

    public IdentitySyncManager(Context context, ExecutorService executor) {
        this.context = context;
        this.executor = executor;
        this.offlineCache = context.getSharedPreferences(OFFLINE_PREFS, Context.MODE_PRIVATE);
        this.apiClient = MiraApiClient.getInstance(context);
    }

    // ==================== Public API ====================

    /**
     * ✅ حفظ تكويد Offline
     */
    public void saveEncodeOffline(JSONObject payload) {
        try {
            String json = offlineCache.getString("pending_encodes", "[]");
            JSONArray arr = new JSONArray(json);
            
            String targetEpc = payload.optString("epc", "");
            if (targetEpc.isEmpty()) {
                Log.w(TAG, "saveEncodeOffline: empty EPC, generating temp ID");
                targetEpc = "TEMP_" + System.currentTimeMillis();
                payload.put("epc", targetEpc);
            }
            
            // ✅ إزالة أي نسخة قديمة
            for (int i = arr.length() - 1; i >= 0; i--) {
                JSONObject existing = arr.optJSONObject(i);
                if (existing != null && targetEpc.equals(existing.optString("epc"))) {
                    arr.remove(i);
                }
            }

            // ✅ إضافة Metadata للمزامنة
            JSONObject enriched = new JSONObject(payload.toString());
            enriched.put("_sync_attempts", 0);
            enriched.put("_saved_at", System.currentTimeMillis());
            enriched.put("_last_attempt", 0L);
            enriched.put("_last_error", "");
            enriched.put("_sync_status", "pending");

            arr.put(enriched);
            
            // ✅ إزالة الأقدم إذا تجاوزنا الحد
            while (arr.length() > MAX_PENDING_ITEMS) {
                arr.remove(0);
            }
            
            offlineCache.edit().putString("pending_encodes", arr.toString()).apply();
            
            Log.d(TAG, "✅ Saved offline: EPC=" + targetEpc + ", total pending=" + arr.length());
        } catch (Exception e) {
            Log.e(TAG, "saveEncodeOffline error: " + e.getMessage(), e);
        }
    }

    /**
     * ✅ الحصول على عدد العناصر المعلقة
     */
    public int getPendingCount() {
        try {
            String json = offlineCache.getString("pending_encodes", "[]");
            JSONArray arr = new JSONArray(json);
            return arr.length();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * ✅ الحصول على العناصر المعلقة كـ List
     */
    public JSONArray getPendingItems() {
        try {
            String json = offlineCache.getString("pending_encodes", "[]");
            return new JSONArray(json);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /**
     * ✅ المزامنة الأساسية (مع Retry)
     */
    public void syncPendingEncodes(int branchId, SyncCallback callback) {
        syncPendingEncodesDetailed(branchId, new DetailedSyncCallback() {
            @Override
            public void onSyncStart(int totalCount) { }

            @Override
            public void onItemSynced(int itemIndex, int totalCount, String epc) { }

            @Override
            public void onItemFailed(int itemIndex, int totalCount, String epc, String error) { }

            @Override
            public void onSyncComplete(int syncedCount, int failedCount) {
                if (callback != null) callback.onSyncComplete(syncedCount);
            }

            @Override
            public void onSyncError(String error) {
                if (callback != null) callback.onSyncError(error);
            }
        });
    }

    /**
     * 🆕 المزامنة المفصلة (مع كل التفاصيل)
     */
    public void syncPendingEncodesDetailed(int branchId, DetailedSyncCallback callback) {
        // ✅ منع المزامنة المتوازية
        if (!isSyncing.compareAndSet(false, true)) {
            Log.w(TAG, "⚠️ Sync already in progress, skipping");
            if (callback != null) callback.onSyncError("Sync already in progress");
            return;
        }

        if (executor == null || executor.isShutdown()) {
            isSyncing.set(false);
            Log.e(TAG, "❌ Executor is shutdown");
            if (callback != null) callback.onSyncError("Executor is shutdown");
            return;
        }

        executor.execute(() -> {
            try {
                // ✅ 1. فحص الشبكة
                if (!isNetworkAvailable()) {
                    Log.w(TAG, "⚠️ No network available, skipping sync");
                    if (callback != null) callback.onSyncError("No network connection");
                    return;
                }

                // ✅ 2. قراءة العناصر المعلقة
                String json = offlineCache.getString("pending_encodes", "[]");
                JSONArray arr = new JSONArray(json);
                int totalCount = arr.length();

                if (totalCount == 0) {
                    Log.d(TAG, "✅ No pending items to sync");
                    if (callback != null) callback.onSyncComplete(0, 0);
                    return;
                }

                Log.i(TAG, "🔄 Starting sync for " + totalCount + " items");
                if (callback != null) callback.onSyncStart(totalCount);

                // ✅ 3. المعالجة
                JSONArray remainingArr = new JSONArray();
                int synced = 0;
                int failed = 0;

                for (int i = 0; i < arr.length(); i++) {
                    JSONObject payload = arr.optJSONObject(i);
                    if (payload == null) continue;

                    String epc = payload.optString("epc", "Unknown");
                    int attempts = payload.optInt("_sync_attempts", 0);

                    // ✅ إذا تجاوزنا الحد الأقصى من المحاولات
                    if (attempts >= MAX_SYNC_ATTEMPTS) {
                        Log.w(TAG, "⚠️ Max attempts reached for EPC=" + epc + ", keeping for manual review");
                        payload.put("_sync_status", "failed_max_attempts");
                        remainingArr.put(payload);
                        failed++;
                        if (callback != null) callback.onItemFailed(i, totalCount, epc, "Max attempts reached");
                        continue;
                    }

                    try {
                        // ✅ إعداد الـ Payload
                        if (!payload.has("branch_id") || payload.optInt("branch_id", 0) <= 0) {
                            payload.put("branch_id", branchId);
                        }

                        // ✅ تحديث Attempts
                        payload.put("_sync_attempts", attempts + 1);
                        payload.put("_last_attempt", System.currentTimeMillis());
                        payload.put("_sync_status", "syncing");

                        // ✅ محاولة المزامنة
                        MiraApiClient.ApiResponse response = apiClient.encodeItem(payload);

                        if (response != null && response.isSuccess) {
                            // ✅ نجحت المزامنة
                            synced++;
                            payload.put("_sync_status", "synced");
                            payload.put("_synced_at", System.currentTimeMillis());
                            Log.d(TAG, "✅ Synced EPC=" + epc + " (" + synced + "/" + totalCount + ")");
                            
                            if (callback != null) callback.onItemSynced(i, totalCount, epc);
                            
                            // ❌ لا نُضيفه إلى remainingArr — سيُحذف
                            
                        } else {
                            // ❌ فشلت المزامنة
                            String errorMsg = (response != null && response.error != null) ? 
                                    response.error : "Unknown error";
                            payload.put("_sync_status", "failed");
                            payload.put("_last_error", errorMsg);
                            remainingArr.put(payload);
                            failed++;
                            
                            Log.w(TAG, "❌ Failed to sync EPC=" + epc + ": " + errorMsg);
                            
                            if (callback != null) callback.onItemFailed(i, totalCount, epc, errorMsg);
                            
                            // ✅ Exponential Backoff — تأخير بين المحاولات
                            if (response != null && response.isNetworkError) {
                                long delay = calculateBackoffDelay(attempts);
                                Log.d(TAG, "⏱️ Network error, waiting " + delay + "ms before next item");
                                try {
                                    Thread.sleep(delay);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Exception syncing EPC=" + epc, e);
                        payload.put("_sync_status", "failed");
                        payload.put("_last_error", e.getMessage());
                        remainingArr.put(payload);
                        failed++;
                        
                        if (callback != null) callback.onItemFailed(i, totalCount, epc, e.getMessage());
                    }
                }

                // ✅ 4. حفظ المتبقي
                offlineCache.edit().putString("pending_encodes", remainingArr.toString()).apply();

                // ✅ 5. تسجيل الإحصائيات
                saveSyncStats(synced, failed);

                Log.i(TAG, "✅ Sync complete: " + synced + " synced, " + failed + " failed");

                if (callback != null) callback.onSyncComplete(synced, failed);

            } catch (Exception e) {
                Log.e(TAG, "syncPendingEncodesDetailed error: " + e.getMessage(), e);
                if (callback != null) callback.onSyncError(e.getMessage());
            } finally {
                isSyncing.set(false);
            }
        });
    }

    /**
     * ✅ فحص التكرار محلياً
     */
    public boolean isEpcDuplicateLocally(String epc) {
        if (epc == null || epc.trim().isEmpty()) return false;
        String cleanEpc = epc.trim().toUpperCase();

        try {
            // ✅ فحص العناصر المعلقة
            String pendingJson = offlineCache.getString("pending_encodes", "[]");
            JSONArray pendingArray = new JSONArray(pendingJson);

            for (int i = 0; i < pendingArray.length(); i++) {
                JSONObject item = pendingArray.optJSONObject(i);
                if (item != null) {
                    String storedEpc = item.optString("epc", "").trim().toUpperCase();
                    if (cleanEpc.equals(storedEpc)) return true;
                }
            }

            // ✅ فحص المسودة الحالية
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

    /**
     * ✅ إزالة عنصر من العناصر المعلقة
     */
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
            Log.d(TAG, "✅ Removed EPC=" + cleanEpc + ", remaining=" + newArr.length());
        } catch (Exception e) {
            Log.e(TAG, "removePendingEncode error", e);
        }
    }

    /**
     * ✅ مسح كل العناصر المعلقة
     */
    public void clearAllPending() {
        try {
            offlineCache.edit().putString("pending_encodes", "[]").apply();
            Log.i(TAG, "✅ All pending items cleared");
        } catch (Exception e) {
            Log.e(TAG, "clearAllPending error", e);
        }
    }

    /**
     * ✅ إحصائيات المزامنة
     */
    public String getSyncStats() {
        try {
            long lastSync = offlineCache.getLong("last_sync_time", 0);
            int totalSynced = offlineCache.getInt("total_synced", 0);
            int totalFailed = offlineCache.getInt("total_failed", 0);
            
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
            String lastSyncStr = lastSync > 0 ? sdf.format(new Date(lastSync)) : "Never";
            
            return String.format(Locale.US,
                    "Last Sync: %s\n" +
                    "Total Synced: %d\n" +
                    "Total Failed: %d\n" +
                    "Pending: %d",
                    lastSyncStr, totalSynced, totalFailed, getPendingCount());
        } catch (Exception e) {
            return "Stats unavailable";
        }
    }

    // ==================== Private Helpers ====================

    /**
     * ✅ فحص توفر الشبكة
     */
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
            Log.e(TAG, "isNetworkAvailable error: " + e.getMessage());
            return false;
        }
    }

    /**
     * ✅ حساب التأخير — Exponential Backoff
     */
    private long calculateBackoffDelay(int attempts) {
        long delay = INITIAL_RETRY_DELAY_MS * (long) Math.pow(2, attempts);
        return Math.min(delay, MAX_RETRY_DELAY_MS);
    }

    /**
     * ✅ حفظ إحصائيات المزامنة
     */
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

    /**
     * ✅ هل المزامنة جارية حالياً؟
     */
    public boolean isSyncing() {
        return isSyncing.get();
    }
}
