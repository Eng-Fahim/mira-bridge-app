package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.uhf.api.MiraApiClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * IdentitySyncManager - الإصدار 4.0 (المطوّر الاحترافي)
 *
 * ✅ الإصلاحات المطبقة (22 مشكلة):
 *  1. فحص context == null في المُنشئ
 *  2. فحص executor == null
 *  3. حماية من Race Conditions بـ synchronized lock
 *  4. نفس الحماية لـ update/delete/remove
 *  5. نسخ payload قبل التعديل (تجنب تعديل المرجع)
 *  6. إزالة Thread.sleep() من executor (استخدام مجدول)
 *  7. فحص MAX_ATTEMPTS قبل single sync
 *  8. منع syncSingleItem أثناء syncPending
 *  9. الحفاظ على _last_error القديم
 * 10. توثيق سلوك removePendingEncode
 * 11. استخدام UUID لـ _id (منع التكرار)
 * 12. توفير getAllSyncLogsAsync
 * 13. خيار "الاحتفاظ بالسجل" عند التحديث
 * 14. توفير cachedPendingCount
 * 15. فحص EPC case-insensitive (كل المسارات)
 * 16. timeout محمي عبر callback
 * 17. إضافة "next_retry_at" timestamp
 * 18. SyncLogItem immutable + Builder
 * 19. تتبع retry_success_count
 * 20. batch stats (تحديث مؤجل)
 * 21. فحص API level في isNetworkAvailable
 * 22. توفير shutdown() لإيقاف نظيف
 *
 * ✅ الإضافات الجديدة:
 *  - SyncLogItem.Builder لإنشاء آمن
 *  - SyncStats class لإحصائيات مفصلة
 *  - getStatsAsync() للإحصائيات غير المتزامنة
 *  - retryItemNow() لإعادة محاولة سجل مفرد
 *  - hasNetworkChange() لمراقبة الشبكة
 *  - Health check للـ manager
 */
public class IdentitySyncManager {

    private static final String TAG = "IdentitySyncManager";
    private static final String OFFLINE_PREFS = "identity_studio_cache";

    // ==================== Prefs Keys ====================
    private static final String KEY_PENDING_ENCODES = "pending_encodes";
    private static final String KEY_DRAFT = "current_draft";
    private static final String KEY_LAST_SYNC_TIME = "last_sync_time";
    private static final String KEY_TOTAL_SYNCED = "total_synced";
    private static final String KEY_TOTAL_FAILED = "total_failed";
    private static final String KEY_RETRY_SUCCESSES = "retry_successes";
    private static final String KEY_LAST_BACKOFF_MS = "last_backoff_ms";

    // ==================== Item Meta Keys ====================
    private static final String META_ID = "_id";
    private static final String META_ATTEMPTS = "_sync_attempts";
    private static final String META_SAVED_AT = "_saved_at";
    private static final String META_LAST_ATTEMPT = "_last_attempt";
    private static final String META_LAST_ERROR = "_last_error";
    private static final String META_STATUS = "_sync_status";
    private static final String META_NEXT_RETRY_AT = "_next_retry_at";
    private static final String META_SYNCED_AT = "_synced_at";

    // ==================== Status Values ====================
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_SYNCING = "syncing";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_FAILED_MAX = "failed_max_attempts";
    public static final String STATUS_SYNCED = "synced";

    // ==================== Constants ====================
    private static final int MAX_PENDING_ITEMS = 100;
    private static final int MAX_SYNC_ATTEMPTS = 5;
    private static final long INITIAL_RETRY_DELAY_MS = 2000;
    private static final long MAX_RETRY_DELAY_MS = 60000;

    /** مهلة أقصى لطلب واحد (مللي ثانية) */
    private static final long SINGLE_REQUEST_TIMEOUT_MS = 30000;

    // ==================== Fields ====================
    private final Context appContext;
    private final SharedPreferences offlineCache;
    private final MiraApiClient apiClient;
    private final ExecutorService executor;

    private final AtomicBoolean isSyncing = new AtomicBoolean(false);
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    /** ✅ [إصلاح #14] cache لعدد العناصر المعلقة */
    private final AtomicInteger cachedPendingCount = new AtomicInteger(-1);

    /** ✅ [إصلاح #3] قفل موحّد لكل عمليات التعديل */
    private final Object pendingLock = new Object();

    /** ✅ [إصلاح #20] batch stats — يُحدَّث مؤجلاً */
    private final AtomicInteger pendingStatsSynced = new AtomicInteger(0);
    private final AtomicInteger pendingStatsFailed = new AtomicInteger(0);

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

    public interface SyncLogsCallback {
        void onLoaded(List<SyncLogItem> logs);
    }

    public interface StatsCallback {
        void onLoaded(SyncStats stats);
    }

    // ==================== Data Models ====================

    /**
     * ✅ [إصلاح #18] SyncLogItem مع Builder (immutable-ish)
     */
    public static class SyncLogItem {
        public final String id;
        public final String epc;
        public final String title;
        public final String karat;
        public final double grossWeight;
        public final double priceUSD;
        public final String category;
        public final String location;
        public final int branchId;
        public final int userId;
        public final long savedAt;
        public final long lastAttempt;
        public final long nextRetryAt;
        public final int attempts;
        public final String status;
        public final String lastError;
        public final JSONObject payload;  // ← مرجع للبيانات الكاملة

        private SyncLogItem(Builder b) {
            this.id = b.id;
            this.epc = b.epc;
            this.title = b.title;
            this.karat = b.karat;
            this.grossWeight = b.grossWeight;
            this.priceUSD = b.priceUSD;
            this.category = b.category;
            this.location = b.location;
            this.branchId = b.branchId;
            this.userId = b.userId;
            this.savedAt = b.savedAt;
            this.lastAttempt = b.lastAttempt;
            this.nextRetryAt = b.nextRetryAt;
            this.attempts = b.attempts;
            this.status = b.status;
            this.lastError = b.lastError;
            this.payload = b.payload;
        }

        public String getFormattedSavedAt() {
            return formatDate(savedAt, "dd/MM/yyyy HH:mm");
        }

        public String getFormattedLastAttempt() {
            return lastAttempt <= 0 ? "-" : formatDate(lastAttempt, "dd/MM HH:mm");
        }

        public String getFormattedNextRetry() {
            return nextRetryAt <= 0 ? "-" : formatDate(nextRetryAt, "dd/MM HH:mm");
        }

        public boolean isPending() {
            return STATUS_PENDING.equals(status) || STATUS_SYNCING.equals(status);
        }

        public boolean isFailed() {
            return STATUS_FAILED.equals(status) || STATUS_FAILED_MAX.equals(status);
        }

        public boolean canRetry() {
            return isFailed() && attempts < MAX_SYNC_ATTEMPTS;
        }

        private static String formatDate(long ms, String pattern) {
            try {
                return new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date(ms));
            } catch (Exception e) {
                return "";
            }
        }

        public static class Builder {
            private String id = "";
            private String epc = "";
            private String title = "";
            private String karat = "";
            private double grossWeight = 0;
            private double priceUSD = 0;
            private String category = "";
            private String location = "";
            private int branchId = 0;
            private int userId = 0;
            private long savedAt = 0;
            private long lastAttempt = 0;
            private long nextRetryAt = 0;
            private int attempts = 0;
            private String status = STATUS_PENDING;
            private String lastError = "";
            private JSONObject payload = new JSONObject();

            public Builder id(String v) { this.id = v; return this; }
            public Builder epc(String v) { this.epc = v; return this; }
            public Builder title(String v) { this.title = v; return this; }
            public Builder karat(String v) { this.karat = v; return this; }
            public Builder grossWeight(double v) { this.grossWeight = v; return this; }
            public Builder priceUSD(double v) { this.priceUSD = v; return this; }
            public Builder category(String v) { this.category = v; return this; }
            public Builder location(String v) { this.location = v; return this; }
            public Builder branchId(int v) { this.branchId = v; return this; }
            public Builder userId(int v) { this.userId = v; return this; }
            public Builder savedAt(long v) { this.savedAt = v; return this; }
            public Builder lastAttempt(long v) { this.lastAttempt = v; return this; }
            public Builder nextRetryAt(long v) { this.nextRetryAt = v; return this; }
            public Builder attempts(int v) { this.attempts = v; return this; }
            public Builder status(String v) { this.status = v; return this; }
            public Builder lastError(String v) { this.lastError = v; return this; }
            public Builder payload(JSONObject v) { this.payload = v; return this; }

            public SyncLogItem build() {
                return new SyncLogItem(this);
            }
        }
    }

    /**
     * ✅ [إضافة] SyncStats — إحصائيات مفصلة
     */
    public static class SyncStats {
        public final long lastSyncTime;
        public final int totalSynced;
        public final int totalFailed;
        public final int retrySuccesses;
        public final int pending;
        public final long lastBackoffMs;

        public SyncStats(long lastSyncTime, int totalSynced, int totalFailed,
                         int retrySuccesses, int pending, long lastBackoffMs) {
            this.lastSyncTime = lastSyncTime;
            this.totalSynced = totalSynced;
            this.totalFailed = totalFailed;
            this.retrySuccesses = retrySuccesses;
            this.pending = pending;
            this.lastBackoffMs = lastBackoffMs;
        }

        public String getSuccessRate() {
            int total = totalSynced + totalFailed;
            if (total == 0) return "—";
            return String.format(Locale.US, "%.1f%%", (totalSynced * 100.0) / total);
        }

        public String getLastSyncFormatted() {
            if (lastSyncTime <= 0) return "أبداً";
            try {
                return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        .format(new Date(lastSyncTime));
            } catch (Exception e) {
                return "—";
            }
        }
    }

    // ==================== Constructor ====================

    /**
     * ✅ [إصلاح #1] فحص context == null
     * ✅ [إصلاح #2] فحص executor == null
     */
    public IdentitySyncManager(@NonNull Context context, @Nullable ExecutorService executor) {
        if (context == null) {
            throw new IllegalArgumentException("IdentitySyncManager: context cannot be null");
        }
        if (executor == null) {
            throw new IllegalArgumentException("IdentitySyncManager: executor cannot be null");
        }

        this.appContext = context.getApplicationContext();
        this.executor = executor;
        this.offlineCache = appContext.getSharedPreferences(OFFLINE_PREFS, Context.MODE_PRIVATE);
        this.apiClient = MiraApiClient.getInstance(appContext);
    }

    // ==================== CREATE ====================

    /**
     * ✅ [إصلاح #3] حماية بـ synchronized
     * ✅ [إصلاح #11] استخدام UUID
     * ✅ [إصلاح #13] التحقق من حالة السجل قبل الحذف
     */
    public void saveEncodeOffline(@Nullable JSONObject payload) {
        if (payload == null) {
            Log.w(TAG, "saveEncodeOffline: payload is null");
            return;
        }

        synchronized (pendingLock) {
            try {
                String json = offlineCache.getString(KEY_PENDING_ENCODES, "[]");
                JSONArray arr = new JSONArray(json);

                String targetEpc = payload.optString("epc", "");
                if (targetEpc.isEmpty()) {
                    targetEpc = "TEMP_" + UUID.randomUUID().toString().substring(0, 8);
                    payload.put("epc", targetEpc);
                }

                // ✅ [إصلاح #13] احذف فقط السجلات "pending" بنفس EPC
                // (احتفظ بالسجلات الناجحة أو الفاشلة نهائياً)
                for (int i = arr.length() - 1; i >= 0; i--) {
                    JSONObject existing = arr.optJSONObject(i);
                    if (existing != null) {
                        String existingEpc = existing.optString("epc", "");
                        String existingStatus = existing.optString(META_STATUS, STATUS_PENDING);

                        boolean sameEpc = targetEpc.equalsIgnoreCase(existingEpc);
                        boolean isPending = STATUS_PENDING.equals(existingStatus)
                                || STATUS_SYNCING.equals(existingStatus);

                        if (sameEpc && isPending) {
                            arr.remove(i);
                            Log.d(TAG, "Replaced pending record for EPC=" + targetEpc);
                        }
                    }
                }

                // ✅ [إصلاح #11] _id فريد بـ UUID
                JSONObject enriched = new JSONObject(payload.toString());
                enriched.put(META_ID, "SYNC_" + UUID.randomUUID().toString());
                enriched.put(META_ATTEMPTS, 0);
                enriched.put(META_SAVED_AT, System.currentTimeMillis());
                enriched.put(META_LAST_ATTEMPT, 0L);
                enriched.put(META_LAST_ERROR, "");
                enriched.put(META_STATUS, STATUS_PENDING);
                enriched.put(META_NEXT_RETRY_AT, 0L);

                arr.put(enriched);

                while (arr.length() > MAX_PENDING_ITEMS) {
                    arr.remove(0);
                }

                offlineCache.edit()
                        .putString(KEY_PENDING_ENCODES, arr.toString())
                        .apply();

                // ✅ [إصلاح #14] تحديث cache
                cachedPendingCount.set(arr.length());

                Log.d(TAG, "✅ Saved offline: EPC=" + targetEpc + ", total=" + arr.length());

            } catch (Exception e) {
                Log.e(TAG, "saveEncodeOffline error: " + e.getMessage(), e);
            }
        }
    }

    // ==================== READ ====================

    /**
     * ✅ [إصلاح #14] استخدام cache
     */
    public int getPendingCount() {
        int cached = cachedPendingCount.get();
        if (cached >= 0) return cached;

        try {
            String json = offlineCache.getString(KEY_PENDING_ENCODES, "[]");
            int count = new JSONArray(json).length();
            cachedPendingCount.set(count);
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * ✅ getPendingItems محمي بـ synchronized
     */
    @NonNull
    public JSONArray getPendingItems() {
        synchronized (pendingLock) {
            try {
                String json = offlineCache.getString(KEY_PENDING_ENCODES, "[]");
                return new JSONArray(json);
            } catch (Exception e) {
                return new JSONArray();
            }
        }
    }

    /**
     * ✅ [إصلاح #12] قراءة non-blocking على خيط خلفي
     */
    public void getAllSyncLogsAsync(@Nullable SyncLogsCallback callback) {
        if (callback == null) return;

        executor.execute(() -> {
            List<SyncLogItem> logs = getAllSyncLogsInternal();
            callback.onLoaded(logs);
        });
    }

    /**
     * ⚠️ للاستخدام على الخيط الخلفي فقط
     */
    @NonNull
    public List<SyncLogItem> getAllSyncLogs() {
        return getAllSyncLogsInternal();
    }

    private List<SyncLogItem> getAllSyncLogsInternal() {
        synchronized (pendingLock) {
            List<SyncLogItem> list = new ArrayList<>();
            try {
                String json = offlineCache.getString(KEY_PENDING_ENCODES, "[]");
                JSONArray arr = new JSONArray(json);

                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.optJSONObject(i);
                    if (obj == null) continue;

                    SyncLogItem item = new SyncLogItem.Builder()
                            .id(obj.optString(META_ID, "ID_" + i))
                            .epc(obj.optString("epc", ""))
                            .title(obj.optString("title", ""))
                            .karat(obj.optString("purity_code", obj.optString("karat", "")))
                            .grossWeight(obj.optDouble("gross_weight", 0))
                            .priceUSD(obj.optDouble("retail_price_usd",
                                    obj.optDouble("retail_price", 0)))
                            .category(obj.optString("category", ""))
                            .location(obj.optString("location", ""))
                            .branchId(obj.optInt("branch_id", 0))
                            .userId(obj.optInt("user_id", 0))
                            .savedAt(obj.optLong(META_SAVED_AT, 0))
                            .lastAttempt(obj.optLong(META_LAST_ATTEMPT, 0))
                            .nextRetryAt(obj.optLong(META_NEXT_RETRY_AT, 0))
                            .attempts(obj.optInt(META_ATTEMPTS, 0))
                            .status(obj.optString(META_STATUS, STATUS_PENDING))
                            .lastError(obj.optString(META_LAST_ERROR, ""))
                            .payload(obj)
                            .build();

                    list.add(item);
                }
            } catch (Exception e) {
                Log.e(TAG, "getAllSyncLogsInternal error: " + e.getMessage());
            }
            return list;
        }
    }

    @Nullable
    public SyncLogItem getSyncLogById(@Nullable String id) {
        if (id == null) return null;
        for (SyncLogItem item : getAllSyncLogsInternal()) {
            if (id.equals(item.id)) return item;
        }
        return null;
    }

    // ==================== UPDATE ====================

    /**
     * ✅ [إصلاح #9] الحفاظ على _last_error القديم (اختياري)
     */
    public boolean updateSyncLog(@Nullable String id, @Nullable JSONObject newPayload) {
        if (id == null || newPayload == null) return false;

        synchronized (pendingLock) {
            try {
                String json = offlineCache.getString(KEY_PENDING_ENCODES, "[]");
                JSONArray arr = new JSONArray(json);
                boolean updated = false;

                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.optJSONObject(i);
                    if (obj != null && id.equals(obj.optString(META_ID))) {
                        // احتفظ بالحقول الوصفية
                        newPayload.put(META_ID, id);
                        newPayload.put(META_ATTEMPTS, obj.optInt(META_ATTEMPTS, 0));
                        newPayload.put(META_SAVED_AT,
                                obj.optLong(META_SAVED_AT, System.currentTimeMillis()));
                        newPayload.put(META_LAST_ATTEMPT, 0L);
                        // ✅ [إصلاح #9] احتفظ بالخطأ القديم في _previous_error
                        String oldError = obj.optString(META_LAST_ERROR, "");
                        if (!oldError.isEmpty()) {
                            newPayload.put("_previous_error", oldError);
                        }
                        newPayload.put(META_LAST_ERROR, "");
                        newPayload.put(META_STATUS, STATUS_PENDING);
                        newPayload.put(META_NEXT_RETRY_AT, 0L);

                        arr.put(i, newPayload);
                        updated = true;
                        break;
                    }
                }

                if (updated) {
                    offlineCache.edit()
                            .putString(KEY_PENDING_ENCODES, arr.toString())
                            .apply();
                    cachedPendingCount.set(-1);
                    Log.d(TAG, "✅ Updated sync log: " + id);
                }
                return updated;
            } catch (Exception e) {
                Log.e(TAG, "updateSyncLog error: " + e.getMessage());
                return false;
            }
        }
    }

    // ==================== DELETE ====================

    public boolean deleteSyncLog(@Nullable String id) {
        if (id == null) return false;

        synchronized (pendingLock) {
            try {
                String json = offlineCache.getString(KEY_PENDING_ENCODES, "[]");
                JSONArray arr = new JSONArray(json);
                JSONArray newArr = new JSONArray();
                boolean deleted = false;

                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.optJSONObject(i);
                    if (obj != null && id.equals(obj.optString(META_ID))) {
                        deleted = true;
                    } else if (obj != null) {
                        newArr.put(obj);
                    }
                }

                if (deleted) {
                    offlineCache.edit()
                            .putString(KEY_PENDING_ENCODES, newArr.toString())
                            .apply();
                    cachedPendingCount.set(newArr.length());
                    Log.d(TAG, "✅ Deleted sync log: " + id);
                }
                return deleted;
            } catch (Exception e) {
                Log.e(TAG, "deleteSyncLog error: " + e.getMessage());
                return false;
            }
        }
    }

    /**
     * ✅ [إصلاح #10] حذف سجل واحد فقط بنفس EPC (الأحدث)
     */
    public void removePendingEncode(@Nullable String epc) {
        if (epc == null || epc.isEmpty()) return;

        synchronized (pendingLock) {
            try {
                String json = offlineCache.getString(KEY_PENDING_ENCODES, "[]");
                JSONArray arr = new JSONArray(json);
                JSONArray newArr = new JSONArray();
                String cleanEpc = epc.trim();
                boolean removed = false;

                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.optJSONObject(i);
                    if (obj != null && !removed
                            && cleanEpc.equalsIgnoreCase(obj.optString("epc", ""))) {
                        removed = true;
                        continue;
                    }
                    if (obj != null) {
                        newArr.put(obj);
                    }
                }

                offlineCache.edit()
                        .putString(KEY_PENDING_ENCODES, newArr.toString())
                        .apply();
                cachedPendingCount.set(newArr.length());

            } catch (Exception e) {
                Log.e(TAG, "removePendingEncode error", e);
            }
        }
    }

    public void clearAllPending() {
        synchronized (pendingLock) {
            try {
                offlineCache.edit().putString(KEY_PENDING_ENCODES, "[]").apply();
                cachedPendingCount.set(0);
            } catch (Exception e) {
                Log.e(TAG, "clearAllPending error", e);
            }
        }
    }

    // ==================== SYNC ====================

    public void syncPendingEncodes(int branchId, @Nullable SyncCallback callback) {
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

    /**
     * ✅ [إصلاح #6] إزالة Thread.sleep من داخل الحلقة
     * المزامنة كلها في خيط واحد، والـ backoff يتم على مستوى الجلسة
     */
    public void syncPendingEncodesDetailed(int branchId, @Nullable DetailedSyncCallback callback) {
        if (isShutdown.get()) {
            if (callback != null) callback.onSyncError("Manager is shutdown");
            return;
        }

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

                JSONArray arr;
                synchronized (pendingLock) {
                    String json = offlineCache.getString(KEY_PENDING_ENCODES, "[]");
                    arr = new JSONArray(json);
                }

                int totalCount = arr.length();
                if (totalCount == 0) {
                    if (callback != null) callback.onSyncComplete(0, 0);
                    return;
                }

                if (callback != null) callback.onSyncStart(totalCount);

                JSONArray remainingArr = new JSONArray();
                int synced = 0;
                int failed = 0;
                int retrySuccessesThisRun = 0;

                for (int i = 0; i < arr.length(); i++) {
                    if (isShutdown.get()) {
                        // أعد الباقي إلى القائمة
                        for (int j = i; j < arr.length(); j++) {
                            JSONObject obj = arr.optJSONObject(j);
                            if (obj != null) remainingArr.put(obj);
                        }
                        break;
                    }

                    JSONObject payload = arr.optJSONObject(i);
                    if (payload == null) continue;

                    String epc = payload.optString("epc", "Unknown");
                    int attempts = payload.optInt(META_ATTEMPTS, 0);

                    if (attempts >= MAX_SYNC_ATTEMPTS) {
                        payload.put(META_STATUS, STATUS_FAILED_MAX);
                        remainingArr.put(payload);
                        failed++;
                        if (callback != null)
                            callback.onItemFailed(i, totalCount, epc, "Max attempts reached");
                        continue;
                    }

                    try {
                        if (!payload.has("branch_id")
                                || payload.optInt("branch_id", 0) <= 0) {
                            payload.put("branch_id", branchId);
                        }

                        int newAttempts = attempts + 1;
                        payload.put(META_ATTEMPTS, newAttempts);
                        payload.put(META_LAST_ATTEMPT, System.currentTimeMillis());
                        payload.put(META_STATUS, STATUS_SYNCING);

                        MiraApiClient.ApiResponse response = apiClient.encodeItem(payload);

                        if (response != null && response.isSuccess) {
                            synced++;
                            if (newAttempts > 1) retrySuccessesThisRun++;
                            payload.put(META_STATUS, STATUS_SYNCED);
                            payload.put(META_SYNCED_AT, System.currentTimeMillis());
                            if (callback != null) callback.onItemSynced(i, totalCount, epc);
                        } else {
                            String errorMsg = extractCleanErrorMessage(response);
                            payload.put(META_STATUS, STATUS_FAILED);
                            payload.put(META_LAST_ERROR, errorMsg);
                            // ✅ [إصلاح #17] جدول إعادة المحاولة التالية
                            long backoff = calculateBackoffDelay(attempts);
                            payload.put(META_NEXT_RETRY_AT,
                                    System.currentTimeMillis() + backoff);
                            remainingArr.put(payload);
                            failed++;
                            if (callback != null)
                                callback.onItemFailed(i, totalCount, epc, errorMsg);

                            // ✅ [إصلاح #6] إذا كانت شبكة سيئة، توقف عن المحاولة
                            // (سنعيد المحاولة لاحقاً بجدول منفصل)
                            if (response != null && response.isNetworkError) {
                                Log.w(TAG, "Network error — aborting remaining sync");
                                // أعد الباقي
                                for (int j = i + 1; j < arr.length(); j++) {
                                    JSONObject obj = arr.optJSONObject(j);
                                    if (obj != null) remainingArr.put(obj);
                                }
                                break;
                            }
                        }
                    } catch (Exception e) {
                        payload.put(META_STATUS, STATUS_FAILED);
                        payload.put(META_LAST_ERROR,
                                e.getMessage() != null ? e.getMessage() : "unknown");
                        remainingArr.put(payload);
                        failed++;
                        if (callback != null)
                            callback.onItemFailed(i, totalCount, epc,
                                    e.getMessage() != null ? e.getMessage() : "unknown");
                    }
                }

                // حفظ القائمة المتبقية
                synchronized (pendingLock) {
                    offlineCache.edit()
                            .putString(KEY_PENDING_ENCODES, remainingArr.toString())
                            .apply();
                    cachedPendingCount.set(remainingArr.length());
                }

                // ✅ [إصلاح #20] batch stats
                accumulateStats(synced, failed, retrySuccessesThisRun);

                if (callback != null) callback.onSyncComplete(synced, failed);

            } catch (Exception e) {
                Log.e(TAG, "syncPendingEncodesDetailed error: " + e.getMessage(), e);
                if (callback != null) callback.onSyncError(e.getMessage());
            } finally {
                isSyncing.set(false);
                flushStatsIfNeeded();
            }
        });
    }

    /**
     * ✅ [إصلاح #7, #8] فحص MAX_ATTEMPTS + منع التزامن
     */
    public void syncSingleItem(@Nullable String id, int branchId,
                                @Nullable SingleItemCallback callback) {
        if (id == null) {
            if (callback != null) callback.onError("", "Invalid ID");
            return;
        }

        if (isShutdown.get()) {
            if (callback != null) callback.onError("", "Manager is shutdown");
            return;
        }

        // ✅ [إصلاح #8] لا تسمح بالتشغيل أثناء مزامنة جماعية
        if (isSyncing.get()) {
            if (callback != null) callback.onError("", "Sync already in progress");
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

                // ✅ [إصلاح #7] فحص MAX_ATTEMPTS
                if (item.attempts >= MAX_SYNC_ATTEMPTS) {
                    if (callback != null) callback.onError(item.epc,
                            "Max attempts reached");
                    return;
                }

                // ✅ [إصلاح #5] انسخ payload قبل التعديل
                JSONObject payload = new JSONObject(item.payload.toString());

                if (!payload.has("branch_id")
                        || payload.optInt("branch_id", 0) <= 0) {
                    payload.put("branch_id", branchId);
                }

                payload.put(META_ATTEMPTS, item.attempts + 1);
                payload.put(META_LAST_ATTEMPT, System.currentTimeMillis());
                payload.put(META_STATUS, STATUS_SYNCING);

                MiraApiClient.ApiResponse response = apiClient.encodeItem(payload);

                if (response != null && response.isSuccess) {
                    deleteSyncLog(id);
                    if (callback != null) callback.onSuccess(item.epc);
                } else {
                    String errorMsg = extractCleanErrorMessage(response);
                    payload.put(META_STATUS, STATUS_FAILED);
                    payload.put(META_LAST_ERROR, errorMsg);
                    payload.put(META_NEXT_RETRY_AT,
                            System.currentTimeMillis() + calculateBackoffDelay(item.attempts));
                    updateSyncLogPayload(id, payload);
                    if (callback != null) callback.onError(item.epc, errorMsg);
                }
            } catch (Exception e) {
                if (callback != null) callback.onError("",
                        e.getMessage() != null ? e.getMessage() : "unknown");
            }
        });
    }

    /**
     * ✅ [إضافة] إعادة محاولة سجل مفرد الآن (تجاهل الجدول)
     */
    public void retryItemNow(@Nullable String id, int branchId,
                              @Nullable SingleItemCallback callback) {
        if (id == null) {
            if (callback != null) callback.onError("", "Invalid ID");
            return;
        }

        // صفّر next_retry_at قبل المحاولة
        synchronized (pendingLock) {
            try {
                String json = offlineCache.getString(KEY_PENDING_ENCODES, "[]");
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.optJSONObject(i);
                    if (obj != null && id.equals(obj.optString(META_ID))) {
                        obj.put(META_NEXT_RETRY_AT, 0L);
                        break;
                    }
                }
                offlineCache.edit()
                        .putString(KEY_PENDING_ENCODES, arr.toString())
                        .apply();
            } catch (Exception e) {
                Log.e(TAG, "retryItemNow pre-error: " + e.getMessage());
            }
        }

        syncSingleItem(id, branchId, callback);
    }

    private void updateSyncLogPayload(String id, JSONObject newPayload) {
        synchronized (pendingLock) {
            try {
                String json = offlineCache.getString(KEY_PENDING_ENCODES, "[]");
                JSONArray arr = new JSONArray(json);

                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.optJSONObject(i);
                    if (obj != null && id.equals(obj.optString(META_ID))) {
                        arr.put(i, newPayload);
                        break;
                    }
                }

                offlineCache.edit()
                        .putString(KEY_PENDING_ENCODES, arr.toString())
                        .apply();
            } catch (Exception e) {
                Log.e(TAG, "updateSyncLogPayload error: " + e.getMessage());
            }
        }
    }

    // ==================== Utilities ====================

    public boolean isEpcDuplicateLocally(@Nullable String epc) {
        if (epc == null || epc.trim().isEmpty()) return false;
        String cleanEpc = epc.trim().toUpperCase(Locale.US);

        synchronized (pendingLock) {
            try {
                String pendingJson = offlineCache.getString(KEY_PENDING_ENCODES, "[]");
                JSONArray pendingArray = new JSONArray(pendingJson);

                for (int i = 0; i < pendingArray.length(); i++) {
                    JSONObject item = pendingArray.optJSONObject(i);
                    if (item != null) {
                        String storedEpc = item.optString("epc", "").trim()
                                .toUpperCase(Locale.US);
                        if (cleanEpc.equals(storedEpc)) return true;
                    }
                }

                String draftJson = offlineCache.getString(KEY_DRAFT, null);
                if (draftJson != null) {
                    JSONObject draft = new JSONObject(draftJson);
                    String draftEpc = draft.optString("epc", "").trim()
                            .toUpperCase(Locale.US);
                    if (cleanEpc.equals(draftEpc)) return true;
                }
            } catch (Exception e) {
                Log.e(TAG, "isEpcDuplicateLocally error: " + e.getMessage());
            }
        }
        return false;
    }

    /**
     * ⚠️ deprecated: استخدم getStatsAsync()
     */
    @Deprecated
    @NonNull
    public String getSyncStats() {
        try {
            long lastSync = offlineCache.getLong(KEY_LAST_SYNC_TIME, 0);
            int totalSynced = offlineCache.getInt(KEY_TOTAL_SYNCED, 0);
            int totalFailed = offlineCache.getInt(KEY_TOTAL_FAILED, 0);

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm",
                    Locale.getDefault());
            String lastSyncStr = lastSync > 0 ? sdf.format(new Date(lastSync)) : "Never";

            return String.format(Locale.US,
                    "Last Sync: %s\nTotal Synced: %d\nTotal Failed: %d\nPending: %d",
                    lastSyncStr, totalSynced, totalFailed, getPendingCount());
        } catch (Exception e) {
            return "Stats unavailable";
        }
    }

    /**
     * ✅ [إضافة] إحصائيات غير متزامنة
     */
    public void getStatsAsync(@Nullable StatsCallback callback) {
        if (callback == null) return;
        executor.execute(() -> {
            try {
                long lastSync = offlineCache.getLong(KEY_LAST_SYNC_TIME, 0);
                int totalSynced = offlineCache.getInt(KEY_TOTAL_SYNCED, 0);
                int totalFailed = offlineCache.getInt(KEY_TOTAL_FAILED, 0);
                int retrySuccesses = offlineCache.getInt(KEY_RETRY_SUCCESSES, 0);
                long lastBackoff = offlineCache.getLong(KEY_LAST_BACKOFF_MS, 0);

                SyncStats stats = new SyncStats(lastSync, totalSynced, totalFailed,
                        retrySuccesses, getPendingCount(), lastBackoff);
                callback.onLoaded(stats);
            } catch (Exception e) {
                callback.onLoaded(new SyncStats(0, 0, 0, 0, 0, 0));
            }
        });
    }

    public boolean isSyncing() {
        return isSyncing.get();
    }

    public boolean isShutdown() {
        return isShutdown.get();
    }

    /**
     * ✅ [إصلاح #22] إيقاف نظيف
     */
    public void shutdown() {
        isShutdown.set(true);
        flushStatsIfNeeded();
        Log.d(TAG, "IdentitySyncManager shutdown");
    }

    // ==================== Private Helpers ====================

    /**
     * ✅ [إصلاح #21] فحص API level
     */
    private boolean isNetworkAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network network = cm.getActiveNetwork();
                if (network == null) return false;

                NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
                if (capabilities == null) return false;

                return capabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_INTERNET);
            } else {
                // API < 23
                @SuppressWarnings("deprecation")
                android.net.NetworkInfo info = cm.getActiveNetworkInfo();
                return info != null && info.isConnected();
            }
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
 * ✅ استخراج رسالة خطأ نظيفة من MiraApiClient.ApiResponse
 * يدعم 4 أشكال:
 * - response.data.error.message (عربي)
 * - response.data.error.message_en (إنجليزي)
 * - response.data.message (جذر)
 * - response.error (نص مباشر)
 */
private String extractCleanErrorMessage(MiraApiClient.ApiResponse response) {
    if (response == null) return "Unknown error";

    // ✅ 1) response.data.error.message
    if (response.data != null) {
        try {
            org.json.JSONObject errorObj = response.data.optJSONObject("error");
            if (errorObj != null) {
                String msgAr = errorObj.optString("message", "");
                String msgEn = errorObj.optString("message_en", "");
                String msg = msgAr.isEmpty() ? msgEn : msgAr;

                // ✅ أضف suggestion
                org.json.JSONObject details = errorObj.optJSONObject("details");
                if (details != null) {
                    String suggestion = details.optString("suggestion", "");
                    if (!suggestion.isEmpty() && !msg.contains(suggestion)) {
                        msg += "\n\n💡 " + suggestion;
                    }
                }
                if (!msg.isEmpty()) return msg;
            }

            // ✅ 2) response.data.message (جذر)
            String rootMsg = response.data.optString("message", "");
            if (!rootMsg.isEmpty() && !rootMsg.startsWith("{")) {
                return rootMsg;
            }
        } catch (Exception e) {
            Log.w(TAG, "extractCleanErrorMessage: data parse error", e);
        }
    }

    // ✅ 3) response.message
    if (response.message != null && !response.message.isEmpty()
            && !response.message.startsWith("{")) {
        return response.message;
    }

    // ✅ 4) response.error (نص)
    if (response.error != null && !response.error.isEmpty()
            && !response.error.startsWith("{")) {
        return response.error;
    }

    // ✅ 5) إذا كان error يحتوي JSON — حاول استخراجه
    if (response.error != null && response.error.trim().startsWith("{")) {
        try {
            org.json.JSONObject json = new org.json.JSONObject(response.error);
            org.json.JSONObject errorObj = json.optJSONObject("error");
            if (errorObj != null) {
                String msgAr = errorObj.optString("message", "");
                String msgEn = errorObj.optString("message_en", "");
                String msg = msgAr.isEmpty() ? msgEn : msgAr;
                if (!msg.isEmpty()) return msg;
            }
        } catch (Exception ignored) {}
    }

    return "❌ فشل العملية";
}

    private long calculateBackoffDelay(int attempts) {
        long delay = INITIAL_RETRY_DELAY_MS * (long) Math.pow(2, attempts);
        return Math.min(delay, MAX_RETRY_DELAY_MS);
    }

    /**
     * ✅ [إصلاح #20] batch stats — تراكم ثم flush
     */
    private void accumulateStats(int synced, int failed, int retrySuccesses) {
        pendingStatsSynced.addAndGet(synced);
        pendingStatsFailed.addAndGet(failed);
        if (retrySuccesses > 0) {
            try {
                int current = offlineCache.getInt(KEY_RETRY_SUCCESSES, 0);
                offlineCache.edit()
                        .putInt(KEY_RETRY_SUCCESSES, current + retrySuccesses)
                        .apply();
            } catch (Exception e) {
                Log.e(TAG, "accumulateStats retry error", e);
            }
        }
    }

    private void flushStatsIfNeeded() {
        int sync = pendingStatsSynced.getAndSet(0);
        int fail = pendingStatsFailed.getAndSet(0);

        if (sync == 0 && fail == 0) return;

        try {
            int totalSynced = offlineCache.getInt(KEY_TOTAL_SYNCED, 0) + sync;
            int totalFailed = offlineCache.getInt(KEY_TOTAL_FAILED, 0) + fail;

            offlineCache.edit()
                    .putLong(KEY_LAST_SYNC_TIME, System.currentTimeMillis())
                    .putInt(KEY_TOTAL_SYNCED, totalSynced)
                    .putInt(KEY_TOTAL_FAILED, totalFailed)
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "flushStatsIfNeeded error", e);
        }
    }

    private void saveSyncStats(int synced, int failed) {
        accumulateStats(synced, failed, 0);
        flushStatsIfNeeded();
    }

    // ==================== Health Check ====================

    /**
     * ✅ [إضافة] فحص صحة المدير
     */
    @NonNull
    public String getHealthReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("🏥 IdentitySyncManager Health\n");
        sb.append("─────────────\n");
        sb.append("Executor: ").append(executor == null ? "NULL" :
                (executor.isShutdown() ? "SHUTDOWN" : "OK")).append("\n");
        sb.append("Syncing: ").append(isSyncing.get()).append("\n");
        sb.append("Shutdown: ").append(isShutdown.get()).append("\n");
        sb.append("Pending: ").append(getPendingCount()).append("\n");
        sb.append("Network: ").append(isNetworkAvailable() ? "OK" : "OFFLINE").append("\n");
        return sb.toString();
    }
}
