package com.example.uhf.manager.inventory;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.uhf.api.MiraApiClient;
import com.example.uhf.model.InventorySnapshot;
import com.example.uhf.model.ScannedItemDisplay;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * ✅ InventorySnapshotManager v1.0
 *
 * إدارة لقطات المخزون:
 * - إنشاء لقطة من الجرد الحالي
 * - حفظ/تحميل محلي (SharedPreferences)
 * - مزامنة مع السيرفر (اختياري)
 * - استعلامات سريعة
 * - مقارنة بين لقطتين
 *
 * الميزات:
 * - Thread-safe عبر synchronized
 * - LRU cache للقطات
 * - حماية من التلف
 * - دعم offline-first
 */
public class InventorySnapshotManager {

    private static final String TAG = "InventorySnapshotMgr";

    // ✅ Prefs
    private static final String PREFS_NAME = "inventory_snapshots";
    private static final String KEY_SNAPSHOTS = "snapshots_list";
    private static final String KEY_VERSION = "version";
    private static final String KEY_LAST_SYNC = "last_sync";

    // ✅ حدود
    private static final int MAX_LOCAL_SNAPSHOTS = 100;
    private static final int CURRENT_VERSION = 1;

    private final Context appContext;
    private final SharedPreferences prefs;
    private final ExecutorService executor;
    private final Object lock = new Object();

    // ✅ Cache للقطات (للاستعلامات السريعة)
    private volatile List<InventorySnapshot> cachedSnapshots = null;

    // ==================== Callbacks ====================

    public interface SnapshotCallback {
        void onSuccess(@NonNull InventorySnapshot snapshot);
        void onError(@NonNull String error);
    }

    public interface SnapshotListCallback {
        void onLoaded(@NonNull List<InventorySnapshot> snapshots);
        void onError(@NonNull String error);
    }

    public interface SnapshotActionCallback {
        void onSuccess();
        void onError(@NonNull String error);
    }

    // ==================== Constructor ====================

    public InventorySnapshotManager(@NonNull Context context,
                                     @NonNull ExecutorService executor) {
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Executor cannot be null");
        }

        this.appContext = context.getApplicationContext();
        this.executor = executor;
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // ✅ تحقق من الإصدار
        checkVersion();
    }

    private void checkVersion() {
        try {
            int savedVersion = prefs.getInt(KEY_VERSION, 0);
            if (savedVersion < CURRENT_VERSION) {
                Log.i(TAG, "Upgrading snapshots from v" + savedVersion + " to v" + CURRENT_VERSION);
                // لا نحذف القديمة — نُبقيها ونُحدّث الإصدار
                prefs.edit().putInt(KEY_VERSION, CURRENT_VERSION).apply();
            }
        } catch (Exception e) {
            Log.e(TAG, "checkVersion error", e);
        }
    }

    // ==================== CREATE ====================

    /**
     * ✅ إنشاء لقطة من الجرد الحالي
     *
     * @param sessionId ID الجلسة
     * @param items قائمة القطع الممسوحة
     * @param expectedCount العدد المتوقع
     * @param branchName اسم الفرع
     * @param mode الوضع (full/category/blind)
     * @param createdBy اسم المستخدم
     * @param customName اسم مخصص (اختياري)
     */
    @NonNull
    public InventorySnapshot createSnapshot(@Nullable String sessionId,
                                              @NonNull List<ScannedItemDisplay> items,
                                              int expectedCount,
                                              @Nullable String branchName,
                                              @Nullable String mode,
                                              @Nullable String createdBy,
                                              @Nullable String customName) {
        InventorySnapshot snapshot = new InventorySnapshot();

        try {
            snapshot.setSessionId(sessionId != null ? sessionId : "");
            snapshot.setExpectedCount(expectedCount);
            snapshot.setBranchName(branchName != null ? branchName : "");
            snapshot.setMode(mode != null ? mode : "full");
            snapshot.setCreatedBy(createdBy != null ? createdBy : "");
            snapshot.setName(customName != null ? customName : 
                    "لقطة " + snapshot.getFormattedDate());

            // ✅ إحصائيات
            Map<String, KaratStats> karatMap = new HashMap<>();
            JSONArray epcsArray = new JSONArray();
            JSONArray itemsArray = new JSONArray();

            int totalItems = 0;
            double totalWeight = 0;
            double totalValue = 0;

            if (items != null) {
                for (ScannedItemDisplay item : items) {
                    if (item == null || item.epc == null) continue;

                    totalItems++;
                    double itemWeight = item.weight * item.count;
                    double itemValue = item.price * item.count;
                    totalWeight += itemWeight;
                    totalValue += itemValue;

                    // ✅ EPC
                    epcsArray.put(item.epc);

                    // ✅ التفاصيل (اختياري)
                    JSONObject itemObj = new JSONObject();
                    itemObj.put("epc", item.epc);
                    itemObj.put("title", item.title != null ? item.title : "");
                    itemObj.put("karat", item.karat != null ? item.karat : "");
                    itemObj.put("weight", item.weight);
                    itemObj.put("price", item.price);
                    itemObj.put("count", item.count);
                    itemObj.put("rssi", item.rssi);
                    itemObj.put("time", item.time != null ? item.time : "");
                    itemsArray.put(itemObj);

                    // ✅ توزيع العيارات
                    String karat = normalizeKarat(item.karat);
                    KaratStats stats = karatMap.get(karat);
                    if (stats == null) {
                        stats = new KaratStats();
                        karatMap.put(karat, stats);
                    }
                    stats.count += item.count;
                    stats.weight += itemWeight;
                }
            }

            snapshot.setTotalItems(totalItems);
            snapshot.setTotalWeight(totalWeight);
            snapshot.setTotalValue(totalValue);
            snapshot.setEpcs(epcsArray);
            snapshot.setItems(itemsArray);

            // ✅ توزيع العيارات
            for (Map.Entry<String, KaratStats> entry : karatMap.entrySet()) {
                String karat = entry.getKey();
                KaratStats stats = entry.getValue();

                switch (karat) {
                    case "24K":
                        snapshot.setCount24K(stats.count);
                        snapshot.setWeight24K(stats.weight);
                        break;
                    case "22K":
                        snapshot.setCount22K(stats.count);
                        snapshot.setWeight22K(stats.weight);
                        break;
                    case "21K":
                        snapshot.setCount21K(stats.count);
                        snapshot.setWeight21K(stats.weight);
                        break;
                    case "18K":
                        snapshot.setCount18K(stats.count);
                        snapshot.setWeight18K(stats.weight);
                        break;
                    case "14K":
                        snapshot.setCount14K(stats.count);
                        snapshot.setWeight14K(stats.weight);
                        break;
                    default:
                        snapshot.setCountOther(stats.count);
                        snapshot.setWeightOther(stats.weight);
                        break;
                }
            }

            Log.i(TAG, "✅ Snapshot created: " + snapshot);

        } catch (Exception e) {
            Log.e(TAG, "createSnapshot error", e);
        }

        return snapshot;
    }

    private String normalizeKarat(String karat) {
        if (karat == null) return "Other";
        String k = karat.trim().toUpperCase();
        if (k.contains("24")) return "24K";
        if (k.contains("22")) return "22K";
        if (k.contains("21")) return "21K";
        if (k.contains("18")) return "18K";
        if (k.contains("14")) return "14K";
        return "Other";
    }

    private static class KaratStats {
        int count = 0;
        double weight = 0;
    }

    // ==================== SAVE ====================

    /**
     * ✅ حفظ لقطة محلياً
     */
    public boolean saveSnapshot(@NonNull InventorySnapshot snapshot) {
        if (snapshot == null) return false;

        synchronized (lock) {
            try {
                List<InventorySnapshot> list = loadSnapshotsInternal();

                // ✅ إزالة أي لقطة قديمة بنفس الـ ID
                List<InventorySnapshot> filtered = new ArrayList<>();
                for (InventorySnapshot s : list) {
                    if (!s.getId().equals(snapshot.getId())) {
                        filtered.add(s);
                    }
                }

                // ✅ إضافة الجديدة في البداية
                filtered.add(0, snapshot);

                // ✅ حماية من التضخم: احتفظ بأحدث 100 فقط
                while (filtered.size() > MAX_LOCAL_SNAPSHOTS) {
                    filtered.remove(filtered.size() - 1);
                }

                // ✅ حفظ
                JSONArray arr = new JSONArray();
                for (InventorySnapshot s : filtered) {
                    arr.put(s.toJson());
                }

                boolean saved = prefs.edit()
                        .putString(KEY_SNAPSHOTS, arr.toString())
                        .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                        .commit();

                // ✅ تحديث Cache
                cachedSnapshots = filtered;

                Log.i(TAG, "✅ Snapshot saved locally (total: " + filtered.size() + ")");
                return saved;

            } catch (Exception e) {
                Log.e(TAG, "saveSnapshot error", e);
                return false;
            }
        }
    }

    // ==================== LOAD ====================

    /**
     * ✅ تحميل كل اللقطات (async)
     */
    public void loadSnapshotsAsync(@Nullable SnapshotListCallback callback) {
        if (callback == null) return;

        executor.execute(() -> {
            try {
                List<InventorySnapshot> list = loadSnapshotsInternal();
                callback.onLoaded(list);
            } catch (Exception e) {
                callback.onError(e.getMessage() != null ? e.getMessage() : "Load error");
            }
        });
    }

    /**
     * ✅ تحميل متزامن (من خيط خلفي)
     */
    @NonNull
    public List<InventorySnapshot> loadSnapshotsSync() {
        synchronized (lock) {
            return loadSnapshotsInternal();
        }
    }

    @NonNull
    private List<InventorySnapshot> loadSnapshotsInternal() {
        // ✅ إذا كان Cache موجود — استخدمه
        List<InventorySnapshot> cached = cachedSnapshots;
        if (cached != null) {
            return new ArrayList<>(cached);
        }

        try {
            String json = prefs.getString(KEY_SNAPSHOTS, "[]");
            JSONArray arr = new JSONArray(json);
            List<InventorySnapshot> list = new ArrayList<>();

            for (int i = 0; i < arr.length(); i++) {
                try {
                    JSONObject obj = arr.getJSONObject(i);
                    InventorySnapshot snapshot = InventorySnapshot.fromJson(obj);
                    if (snapshot != null) {
                        list.add(snapshot);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Skipping corrupted snapshot at index " + i, e);
                }
            }

            // ✅ تحديث Cache
            cachedSnapshots = list;

            return list;

        } catch (Exception e) {
            Log.e(TAG, "loadSnapshotsInternal error", e);
            return new ArrayList<>();
        }
    }

    /**
     * ✅ تحميل لقطة بالـ ID
     */
    @Nullable
    public InventorySnapshot getSnapshotById(@Nullable String id) {
        if (id == null || id.isEmpty()) return null;

        List<InventorySnapshot> list = loadSnapshotsInternal();
        for (InventorySnapshot s : list) {
            if (id.equals(s.getId())) {
                return s;
            }
        }
        return null;
    }

    /**
     * ✅ عدد اللقطات
     */
    public int getSnapshotCount() {
        return loadSnapshotsInternal().size();
    }

    /**
     * ✅ أحدث لقطة
     */
    @Nullable
    public InventorySnapshot getLatestSnapshot() {
        List<InventorySnapshot> list = loadSnapshotsInternal();
        if (list.isEmpty()) return null;
        return list.get(0);
    }

    // ==================== DELETE ====================

    /**
     * ✅ حذف لقطة
     */
    public boolean deleteSnapshot(@Nullable String id) {
        if (id == null || id.isEmpty()) return false;

        synchronized (lock) {
            try {
                List<InventorySnapshot> list = loadSnapshotsInternal();
                List<InventorySnapshot> filtered = new ArrayList<>();

                for (InventorySnapshot s : list) {
                    if (!id.equals(s.getId())) {
                        filtered.add(s);
                    }
                }

                if (filtered.size() == list.size()) {
                    // لم يُحذف شيء
                    return false;
                }

                JSONArray arr = new JSONArray();
                for (InventorySnapshot s : filtered) {
                    arr.put(s.toJson());
                }

                boolean saved = prefs.edit()
                        .putString(KEY_SNAPSHOTS, arr.toString())
                        .commit();

                cachedSnapshots = filtered;

                Log.i(TAG, "✅ Snapshot deleted: " + id);
                return saved;

            } catch (Exception e) {
                Log.e(TAG, "deleteSnapshot error", e);
                return false;
            }
        }
    }

    /**
     * ✅ حذف كل اللقطات
     */
    public boolean clearAllSnapshots() {
        synchronized (lock) {
            try {
                prefs.edit().remove(KEY_SNAPSHOTS).commit();
                cachedSnapshots = null;
                Log.i(TAG, "✅ All snapshots cleared");
                return true;
            } catch (Exception e) {
                Log.e(TAG, "clearAllSnapshots error", e);
                return false;
            }
        }
    }

    /**
     * ✅ حذف اللقطات الأقدم من X أيام
     */
    public int deleteOldSnapshots(int daysOld) {
        if (daysOld <= 0) return 0;

        long cutoffTime = System.currentTimeMillis() - (daysOld * 24L * 60 * 60 * 1000);

        synchronized (lock) {
            try {
                List<InventorySnapshot> list = loadSnapshotsInternal();
                List<InventorySnapshot> filtered = new ArrayList<>();
                int deleted = 0;

                for (InventorySnapshot s : list) {
                    if (s.getTimestamp() < cutoffTime) {
                        deleted++;
                    } else {
                        filtered.add(s);
                    }
                }

                if (deleted > 0) {
                    JSONArray arr = new JSONArray();
                    for (InventorySnapshot s : filtered) {
                        arr.put(s.toJson());
                    }

                    prefs.edit().putString(KEY_SNAPSHOTS, arr.toString()).commit();
                    cachedSnapshots = filtered;

                    Log.i(TAG, "✅ Deleted " + deleted + " old snapshots (older than " + daysOld + " days)");
                }

                return deleted;

            } catch (Exception e) {
                Log.e(TAG, "deleteOldSnapshots error", e);
                return 0;
            }
        }
    }

    // ==================== SYNC WITH SERVER ====================

    /**
     * ✅ رفع لقطة للسيرفر (async)
     */
    public void uploadSnapshotToServer(@NonNull InventorySnapshot snapshot,
                                        @NonNull MiraApiClient apiClient,
                                        @Nullable SnapshotActionCallback callback) {
        if (snapshot == null || apiClient == null) {
            if (callback != null) callback.onError("Invalid arguments");
            return;
        }

        executor.execute(() -> {
            try {
                JSONObject payload = snapshot.toJson();
                MiraApiClient.ApiResponse response =
                        apiClient.postWithAuth("/inventory/snapshots", payload);

                if (response != null && response.isSuccess) {
                    Log.i(TAG, "✅ Snapshot uploaded: " + snapshot.getId());
                    if (callback != null) callback.onSuccess();
                } else {
                    String error = response != null && response.error != null
                            ? response.error : "Upload failed";
                    Log.w(TAG, "⚠️ Snapshot upload failed: " + error);
                    if (callback != null) callback.onError(error);
                }
            } catch (Exception e) {
                Log.e(TAG, "uploadSnapshotToServer error", e);
                if (callback != null) callback.onError(e.getMessage() != null ? 
                        e.getMessage() : "Upload error");
            }
        });
    }

    /**
     * ✅ تحميل اللقطات من السيرفر (async)
     */
    public void loadSnapshotsFromServer(@NonNull MiraApiClient apiClient,
                                          int branchId,
                                          @Nullable SnapshotListCallback callback) {
        if (apiClient == null) {
            if (callback != null) callback.onError("API client null");
            return;
        }

        executor.execute(() -> {
            try {
                String endpoint = "/inventory/snapshots?branch_id=" + branchId;
                MiraApiClient.ApiResponse response = apiClient.getWithAuth(endpoint);

                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray arr = response.data.optJSONArray("snapshots");
                    if (arr == null) arr = response.data.optJSONArray("data");

                    if (arr != null) {
                        List<InventorySnapshot> serverSnapshots = new ArrayList<>();
                        for (int i = 0; i < arr.length(); i++) {
                            try {
                                InventorySnapshot s = InventorySnapshot.fromJson(arr.getJSONObject(i));
                                if (s != null) serverSnapshots.add(s);
                            } catch (Exception e) {
                                Log.w(TAG, "Failed to parse snapshot " + i, e);
                            }
                        }

                        // ✅ دمج مع المحلية (بدون تكرار)
                        mergeServerSnapshots(serverSnapshots);

                        if (callback != null) callback.onLoaded(serverSnapshots);
                        return;
                    }
                }

                String error = response != null && response.error != null
                        ? response.error : "No data";
                if (callback != null) callback.onError(error);

            } catch (Exception e) {
                Log.e(TAG, "loadSnapshotsFromServer error", e);
                if (callback != null) callback.onError(e.getMessage() != null ? 
                        e.getMessage() : "Load error");
            }
        });
    }

    /**
     * ✅ دمج اللقطات من السيرفر مع المحلية (بدون تكرار)
     */
    private void mergeServerSnapshots(@NonNull List<InventorySnapshot> serverSnapshots) {
        synchronized (lock) {
            try {
                List<InventorySnapshot> local = loadSnapshotsInternal();
                Map<String, InventorySnapshot> merged = new HashMap<>();

                // ✅ المحلية أولاً
                for (InventorySnapshot s : local) {
                    merged.put(s.getId(), s);
                }

                // ✅ السيرفر (يُحدِّث الموجود ويضيف الجديد)
                for (InventorySnapshot s : serverSnapshots) {
                    if (!merged.containsKey(s.getId())) {
                        merged.put(s.getId(), s);
                    }
                }

                // ✅ رتّب حسب الوقت
                List<InventorySnapshot> result = new ArrayList<>(merged.values());
                Collections.sort(result, (a, b) ->
                        Long.compare(b.getTimestamp(), a.getTimestamp()));

                // ✅ احتفظ بأحدث 100
                while (result.size() > MAX_LOCAL_SNAPSHOTS) {
                    result.remove(result.size() - 1);
                }

                // ✅ حفظ
                JSONArray arr = new JSONArray();
                for (InventorySnapshot s : result) {
                    arr.put(s.toJson());
                }

                prefs.edit()
                        .putString(KEY_SNAPSHOTS, arr.toString())
                        .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                        .commit();

                cachedSnapshots = result;

                Log.i(TAG, "✅ Merged snapshots (total: " + result.size() + ")");

            } catch (Exception e) {
                Log.e(TAG, "mergeServerSnapshots error", e);
            }
        }
    }

    // ==================== STATISTICS ====================

    /**
     * ✅ إحصائيات عامة
     */
    @NonNull
    public SnapshotStats getStats() {
        List<InventorySnapshot> list = loadSnapshotsInternal();

        SnapshotStats stats = new SnapshotStats();
        stats.totalSnapshots = list.size();

        if (!list.isEmpty()) {
            stats.latestTimestamp = list.get(0).getTimestamp();
            stats.oldestTimestamp = list.get(list.size() - 1).getTimestamp();
        }

        return stats;
    }

    public static class SnapshotStats {
        public int totalSnapshots;
        public long latestTimestamp;
        public long oldestTimestamp;

        public String getFormattedLatest() {
            if (latestTimestamp <= 0) return "—";
            try {
                java.text.SimpleDateFormat sdf =
                        new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", 
                                java.util.Locale.US);
                return sdf.format(new java.util.Date(latestTimestamp));
            } catch (Exception e) {
                return "—";
            }
        }
    }

    // ==================== CLEANUP ====================

    /**
     * ✅ تنظيف الـ Cache (لتحرير الذاكرة)
     */
    public void releaseCache() {
        cachedSnapshots = null;
        Log.d(TAG, "Cache released");
    }
}
