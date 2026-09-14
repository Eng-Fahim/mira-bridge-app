package com.example.uhf.manager.inventory;

import android.content.Context;
import android.util.Log;

import com.example.uhf.api.MiraApiClient;
import com.example.uhf.database.InventoryDatabase;
import com.example.uhf.database.InventoryTransactionDao;
import com.example.uhf.database.InventoryTransactionEntity;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.model.InventoryReport;
import com.example.uhf.model.ScannedItemDisplay;

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
 * ✅ InventoryPersistenceManager
 * 
 * مسؤول عن:
 * - حفظ المعاملات محلياً (Room Database)
 * - مزامنة المعاملات الأوفلاين مع السيرفر
 * - اعتماد الجرد النهائي (Commit)
 * - جلب تقارير الأرشيف
 * - جلب اللقطات (Snapshots)
 * 
 * لا يحتوي على مراجع للـ UI → آمن من التسريبات
 */
public class InventoryPersistenceManager {

    private static final String TAG = "InventoryPersistence";

    private final Context context;
    private final boolean isArabic;
    private final ExecutorService executor;
    
    // ✅ Database
    private InventoryDatabase inventoryDatabase;
    private InventoryTransactionDao transactionDao;
    
    // ✅ Atomic flags
    private final AtomicBoolean isSyncing = new AtomicBoolean(false);
    private final AtomicBoolean isCommitting = new AtomicBoolean(false);
    
    // ==================== Callbacks ====================
    
    public interface SyncCallback {
        void onSyncComplete(int syncedCount);
        void onSyncError(String error);
    }
    
    public interface CommitCallback {
        void onCommitSuccess(JSONObject report);
        void onCommitError(String error);
    }
    
    public interface ArchiveCallback {
        void onArchiveLoaded(List<InventoryReport> reports);
        void onArchiveError(String error);
    }
    
    // ==================== Constructor ====================
    
    public InventoryPersistenceManager(Context context, boolean isArabic, ExecutorService executor) {
        this.context = context.getApplicationContext();
        this.isArabic = isArabic;
        this.executor = executor;
        initDatabase();
    }
    
    // ==================== Database Init ====================
    
    private void initDatabase() {
        try {
            inventoryDatabase = InventoryDatabase.getInstance(context);
            transactionDao = inventoryDatabase.inventoryTransactionDao();
            Log.d(TAG, "✅ Database initialized");
        } catch (Exception e) {
            Log.e(TAG, "initDatabase error: " + e.getMessage());
        }
    }
    
    // ==================== 1. Save Transaction Locally ====================
    
    /**
     * ✅ حفظ معاملة محلياً
     * 
     * @param sessionId  معرف الجلسة
     * @param epc        كود القطعة
     * @param rssi       قوة الإشارة
     * @param deviceId   معرف الجهاز
     * @param userId     معرف المستخدم
     * @param branchId   معرف الفرع
     */
    public void saveTransactionLocally(String sessionId, 
                                        String epc, 
                                        int rssi,
                                        String deviceId,
                                        String userId,
                                        int branchId) {
        try {
            if (transactionDao == null) return;
            
            InventoryTransactionEntity transaction = new InventoryTransactionEntity();
            transaction.sessionId = sessionId;
            transaction.epcCode = epc;
            transaction.rssi = rssi;
            transaction.timestamp = System.currentTimeMillis();
            transaction.deviceId = deviceId;
            transaction.userId = userId;
            transaction.branchId = branchId;
            transaction.synced = false;
            
            if (executor != null && !executor.isShutdown()) {
                executor.execute(() -> {
                    try {
                        transactionDao.insertTransaction(transaction);
                    } catch (Exception e) {
                        Log.e(TAG, "saveTransactionLocally insert error: " + e.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "saveTransactionLocally error: " + e.getMessage());
        }
    }
    
    // ==================== 2. Sync Offline Transactions ====================
    
    /**
     * ✅ مزامنة المعاملات الأوفلاين مع السيرفر
     */
    public void syncOfflineTransactions(MiraApiClient apiClient, SyncCallback callback) {
        if (!isSyncing.compareAndSet(false, true)) {
            if (callback != null) {
                callback.onSyncError("Sync already in progress");
            }
            return;
        }
        
        if (executor == null || executor.isShutdown() 
                || transactionDao == null || apiClient == null) {
            isSyncing.set(false);
            if (callback != null) {
                callback.onSyncError("Prerequisites not met");
            }
            return;
        }
        
        executor.execute(() -> {
            try {
                List<InventoryTransactionEntity> unsynced = 
                        transactionDao.getUnsyncedTransactions();
                
                if (unsynced == null || unsynced.isEmpty()) {
                    isSyncing.set(false);
                    if (callback != null) {
                        callback.onSyncComplete(0);
                    }
                    return;
                }
                
                JSONArray transactionsArray = new JSONArray();
                List<Long> successfulIds = new ArrayList<>();
                
                for (InventoryTransactionEntity transaction : unsynced) {
                    try {
                        JSONObject transactionJson = new JSONObject();
                        transactionJson.put("session_id", transaction.sessionId);
                        transactionJson.put("epc", transaction.epcCode);
                        transactionJson.put("rssi", transaction.rssi);
                        transactionJson.put("timestamp", transaction.timestamp);
                        transactionJson.put("device_id", transaction.deviceId);
                        transactionJson.put("user_id", transaction.userId);
                        transactionJson.put("branch_id", transaction.branchId);
                        
                        transactionsArray.put(transactionJson);
                        successfulIds.add((long) transaction.id);
                    } catch (Exception e) {
                        Log.e(TAG, "Error preparing transaction: " + e.getMessage());
                    }
                }
                
                if (transactionsArray.length() > 0) {
                    MiraApiClient.ApiResponse response = 
                            apiClient.syncOfflineTransactions(transactionsArray);
                    
                    if (response != null && response.isSuccess) {
                        transactionDao.markAsSynced(successfulIds);
                        
                        final int syncedCount = successfulIds.size();
                        isSyncing.set(false);
                        
                        if (callback != null) {
                            callback.onSyncComplete(syncedCount);
                        }
                    } else {
                        isSyncing.set(false);
                        if (callback != null) {
                            callback.onSyncError("Server returned error");
                        }
                    }
                } else {
                    isSyncing.set(false);
                    if (callback != null) {
                        callback.onSyncComplete(0);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "syncOfflineTransactions error: " + e.getMessage());
                isSyncing.set(false);
                if (callback != null) {
                    callback.onSyncError(e.getMessage());
                }
            }
        });
    }
    
    // ==================== 3. Commit Inventory ====================
    
    /**
     * ✅ اعتماد الجرد النهائي
     */
    public void commitInventory(String sessionId,
                                 int branchId,
                                 int expectedCount,
                                 int totalReads,
                                 int scannedCount,
                                 List<ScannedItemDisplay> items,
                                 MiraApiClient apiClient,
                                 CommitCallback callback) {
        
        if (!isCommitting.compareAndSet(false, true)) {
            if (callback != null) {
                callback.onCommitError(isArabic ? 
                        "جاري الإرسال بالفعل..." : "Already sending...");
            }
            return;
        }
        
        if (executor == null || executor.isShutdown() || apiClient == null) {
            isCommitting.set(false);
            if (callback != null) {
                callback.onCommitError("Prerequisites not met");
            }
            return;
        }
        
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                
                payload.put("session_id", sessionId);
                payload.put("client_session_id", sessionId);
                payload.put("branch_id", branchId);
                payload.put("location", "Main Showroom");
                
                payload.put("expected_count", expectedCount);
                payload.put("total_scanned", totalReads);
                payload.put("unique_count", scannedCount);
                payload.put("found_count", scannedCount);
                payload.put("missing_count", Math.max(0, expectedCount - scannedCount));
                payload.put("unexpected_count", 0);
                
                JSONArray itemsArray = new JSONArray();
                JSONArray epcsArray = new JSONArray();
                
                List<ScannedItemDisplay> snapshot;
                synchronized (items) {
                    snapshot = new ArrayList<>(items);
                }
                
                for (ScannedItemDisplay item : snapshot) {
                    JSONObject itemObj = new JSONObject();
                    itemObj.put("code", item.epc);
                    itemObj.put("serial", item.epc);
                    itemObj.put("rssi", String.valueOf(item.rssi));
                    itemsArray.put(itemObj);
                    
                    epcsArray.put(item.epc);
                }
                
                payload.put("items", itemsArray);
                payload.put("scanned_epcs", epcsArray);
                
                MiraApiClient.ApiResponse response = 
                        apiClient.closeInventorySession(payload);
                
                isCommitting.set(false);
                
                if (response != null && response.isSuccess) {
                    JSONObject report = response.data != null ? 
                            response.data.optJSONObject("report") : null;
                    if (report == null && response.data != null) {
                        report = response.data;
                    }
                    
                    if (callback != null) {
                        callback.onCommitSuccess(report != null ? report : new JSONObject());
                    }
                } else {
                    String error = response != null ? response.error : "Unknown error";
                    if (callback != null) {
                        callback.onCommitError(error);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "commitInventory error: " + e.getMessage());
                isCommitting.set(false);
                if (callback != null) {
                    callback.onCommitError(e.getMessage());
                }
            }
        });
    }
    
    // ==================== 4. Fetch Archive Reports ====================
    
    /**
     * ✅ جلب تقارير الأرشيف
     */
    public void fetchArchiveReports(MiraApiClient apiClient,
                                     int branchId,
                                     ArchiveCallback callback) {
        if (executor == null || executor.isShutdown() || apiClient == null) {
            if (callback != null) {
                callback.onArchiveError("Prerequisites not met");
            }
            return;
        }
        
        executor.execute(() -> {
            try {
                List<InventoryReport> reports = new ArrayList<>();
                
                MiraApiClient.ApiResponse response = 
                        apiClient.getInventoryReports(branchId, 1, 50);
                
                if (response != null && response.isSuccess && response.data != null) {
                    JSONObject dataObj = response.data.optJSONObject("data");
                    JSONArray sessions = null;
                    
                    if (dataObj != null) {
                        sessions = dataObj.optJSONArray("sessions");
                    } else {
                        sessions = response.data.optJSONArray("sessions");
                    }
                    
                    if (sessions != null) {
                        for (int i = 0; i < sessions.length(); i++) {
                            JSONObject obj = sessions.getJSONObject(i);
                            
                            InventoryReport report = new InventoryReport(
                                obj.optInt("id", i + 1),
                                obj.optString("client_session_id", 
                                    obj.optString("session_id", "SESS-" + (i + 1))),
                                obj.optString("started_at", 
                                    obj.optString("created_at", "")),
                                obj.optInt("expected_count", 0),
                                obj.optInt("unique_items_found", 
                                    obj.optInt("scanned_count", 0)),
                                obj.optInt("missing_count", 0),
                                obj.optString("status", "completed")
                            );
                            reports.add(report);
                        }
                    }
                }
                
                if (callback != null) {
                    callback.onArchiveLoaded(reports);
                }
                
            } catch (Exception e) {
                Log.e(TAG, "fetchArchiveReports error: " + e.getMessage());
                if (callback != null) {
                    callback.onArchiveError(e.getMessage());
                }
            }
        });
    }
    
    // ==================== 5. Fetch Snapshots ====================
    
    /**
     * ✅ جلب اللقطات من السيرفر
     */
    public void fetchSnapshots(MiraApiClient apiClient,
                                String location,
                                int limit,
                                ArchiveCallback callback) {
        if (executor == null || executor.isShutdown() || apiClient == null) {
            if (callback != null) {
                callback.onArchiveError("Prerequisites not met");
            }
            return;
        }
        
        executor.execute(() -> {
            try {
                List<InventoryReport> snapshots = new ArrayList<>();
                
                MiraApiClient.ApiResponse response = 
                        apiClient.getInventoryHistory(location, limit);
                
                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray history = response.data.optJSONArray("history");
                    if (history != null) {
                        for (int i = 0; i < history.length(); i++) {
                            JSONObject obj = history.getJSONObject(i);
                            
                            InventoryReport report = new InventoryReport(
                                i + 1,
                                obj.optString("snapshot_date", ""),
                                obj.optString("snapshot_date", ""),
                                obj.optInt("total_items", 0),
                                obj.optInt("total_items", 0),
                                0,
                                "completed"
                            );
                            snapshots.add(report);
                        }
                    }
                }
                
                if (callback != null) {
                    callback.onArchiveLoaded(snapshots);
                }
                
            } catch (Exception e) {
                Log.e(TAG, "fetchSnapshots error: " + e.getMessage());
                if (callback != null) {
                    callback.onArchiveError(e.getMessage());
                }
            }
        });
    }
    
    // ==================== 6. Utilities ====================
    
    /**
     * ✅ الحصول على معرف الجهاز
     */
    public String getDeviceId() {
        try {
            return android.provider.Settings.Secure.getString(
                    context.getContentResolver(),
                    android.provider.Settings.Secure.ANDROID_ID
            );
        } catch (Exception e) {
            return "UNKNOWN-DEVICE";
        }
    }
    
    /**
     * ✅ الحصول على معرف المستخدم
     */
    public String getCurrentUserId() {
        String deviceId = getDeviceId();
        return "USER-" + (deviceId.length() > 8 ? deviceId.substring(0, 8) : deviceId);
    }
    
    /**
     * ✅ هل المزامنة جارية؟
     */
    public boolean isSyncingNow() {
        return isSyncing.get();
    }
    
    /**
     * ✅ هل الاعتماد جارٍ؟
     */
    public boolean isCommittingNow() {
        return isCommitting.get();
    }
    
    // ==================== Cleanup ====================
    
    /**
     * ✅ تنظيف
     */
    public void cleanup() {
        try {
            inventoryDatabase = null;
            transactionDao = null;
            Log.d(TAG, "✅ Cleanup complete");
        } catch (Exception e) {
            Log.e(TAG, "cleanup error: " + e.getMessage());
        }
    }
}
