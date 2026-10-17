package com.example.uhf.manager;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import com.example.uhf.api.MiraApiClient;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ═══════════════════════════════════════════════════════════════════
 *  OfflineSalesSyncer — محرّك مزامنة المبيعات Offline
 * ═══════════════════════════════════════════════════════════════════
 * 
 * يُشغَّل عند:
 *   - فتح شاشة POS (onResume)
 *   - عودة الاتصال بالإنترنت
 *   - يدوياً من الواجهة
 * 
 * لكل بيع في الطابور:
 *   - يحاول إرساله بنفس local_uuid
 *   - نجاح → markAsSent
 *   - فشل شبكة → incrementRetry (يُعاد المحاولة لاحقاً)
 *   - فشل نهائي → markAsFailed
 * 
 * @since v1.0
 */
public class OfflineSalesSyncer {

    private static final String TAG = "OfflineSalesSyncer";

    /** بحد أقصى 20 بيعاً في كل جلسة مزامنة */
    private static final int BATCH_SIZE = 20;

    /** تأخير بين كل بيع والآخر لتقليل الضغط على السيرفر */
    private static final long DELAY_BETWEEN_MS = 500;

    private static volatile OfflineSalesSyncer instance;
    private static final Object LOCK = new Object();

    private final Context context;
    private final PendingSalesQueue queue;
    private final Handler mainHandler;
    private final AtomicBoolean isSyncing = new AtomicBoolean(false);

    // ═══════════════════════════════════════════════════════════════
    //  Callback للواجهة
    // ═══════════════════════════════════════════════════════════════
    public interface SyncCallback {
        /** يُستدعى عند بدء المزامنة — total = عدد البيوع في الطابور */
        void onSyncStart(int total);

        /** يُستدعى لكل بيع بعد محاولة إرساله */
        void onSaleSynced(PendingSale sale, boolean success, 
                          @Nullable String error);

        /** يُستدعى عند انتهاء المزامنة */
        void onSyncComplete(int synced, int failed, int remaining);
    }

    private OfflineSalesSyncer(Context context) {
        this.context = context.getApplicationContext();
        this.queue = PendingSalesQueue.getInstance(this.context);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public static OfflineSalesSyncer getInstance(Context context) {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new OfflineSalesSyncer(context);
                }
            }
        }
        return instance;
    }

    // ═══════════════════════════════════════════════════════════════
    //  🚀 المزامنة الرئيسية
    // ═══════════════════════════════════════════════════════════════

    /**
     * ✅ يحاول إرسال كل البيوع المعلّقة.
     * 
     * @param callback قد يكون null
     */
    public void syncAll(@Nullable final SyncCallback callback) {
        // ✅ حماية من المزامنة المتزامنة
        if (!isSyncing.compareAndSet(false, true)) {
            Log.d(TAG, "⏭️ Already syncing — skipped");
            if (callback != null) {
                mainHandler.post(() -> {
                    int remaining = queue.countPending();
                    callback.onSyncComplete(0, 0, remaining);
                });
            }
            return;
        }

        new Thread(() -> {
            try {
                doSync(callback);
            } catch (Exception e) {
                Log.e(TAG, "syncAll error", e);
                if (callback != null) {
                    mainHandler.post(() -> 
                            callback.onSyncComplete(0, 0, queue.countPending()));
                }
            } finally {
                isSyncing.set(false);
            }
        }, "OfflineSync").start();
    }

    private void doSync(@Nullable SyncCallback callback) {
        // 1) جلب البيوع المعلّقة
        List<PendingSale> pendingList = queue.getPending(BATCH_SIZE);

        if (pendingList.isEmpty()) {
            Log.d(TAG, "✅ No pending sales — nothing to sync");
            if (callback != null) {
                mainHandler.post(() -> callback.onSyncComplete(0, 0, 0));
            }
            return;
        }

        Log.i(TAG, "🚀 Syncing " + pendingList.size() + " pending sales");

        // 2) إعلام الواجهة
        if (callback != null) {
            final int total = pendingList.size();
            mainHandler.post(() -> callback.onSyncStart(total));
        }

        // 3) الحصول على API client
        MiraApiClient apiClient = MiraApiClient.getInstance(context);
        if (apiClient == null) {
            Log.e(TAG, "❌ apiClient is null — abort");
            if (callback != null) {
                mainHandler.post(() -> 
                        callback.onSyncComplete(0, 0, queue.countPending()));
            }
            return;
        }

        int synced = 0;
        int failed = 0;
        int networkErrors = 0;

        // 4) معالجة كل بيع
        for (PendingSale sale : pendingList) {
            try {
                // أ) تحقق من صحة الـ payload
                JSONObject payload;
                try {
                    payload = new JSONObject(sale.payloadJson);
                } catch (Exception e) {
                    Log.e(TAG, "❌ Invalid JSON for " + sale.localUuid, e);
                    queue.markAsFailed(sale.localUuid, "Invalid JSON: " + e.getMessage());
                    failed++;
                    notifySaleResult(callback, sale, false, "Invalid JSON");
                    continue;
                }

                // ب) أرسل الطلب
                Log.d(TAG, "📤 Sending " + sale.localUuid 
                        + " | total=" + sale.totalUsd);
                
                MiraApiClient.ApiResponse response = 
                        apiClient.checkout(payload);

                // ج) حلّل النتيجة
                if (response == null) {
                    Log.w(TAG, "⚠️ Null response for " + sale.localUuid);
                    queue.incrementRetry(sale.localUuid, "Null response");
                    networkErrors++;
                    notifySaleResult(callback, sale, false, "Null response");
                    continue;
                }

                // ✅ نجاح أو "already_synced" → كلاهما مقبول
                if (response.isSuccess) {
                    boolean wasDuplicate = false;
                    if (response.data != null) {
                        wasDuplicate = response.data.optBoolean(
                                "already_synced", false);
                    }
                    
                    queue.markAsSent(sale.localUuid);
                    synced++;
                    
                    Log.i(TAG, "✅ Synced: " + sale.localUuid 
                            + (wasDuplicate ? " (duplicate — idempotent)" : ""));
                    notifySaleResult(callback, sale, true, null);
                    
                } else if (response.isNetworkError) {
                    // ⚠️ فشل شبكة → يُبقى في الطابور
                    queue.incrementRetry(sale.localUuid, 
                            response.error != null ? response.error : "network");
                    networkErrors++;
                    
                    Log.w(TAG, "🌐 Network error for " + sale.localUuid 
                            + " — kept for retry");
                    notifySaleResult(callback, sale, false, "network");
                    
                    // ⚠️ إذا فشلنا بسبب الشبكة → لا نكمل باقي القائمة
                    //    (لأن الباقي سيفشل أيضاً)
                    Log.i(TAG, "⏸️ Stopping sync (network down)");
                    break;
                    
                } else {
                    // 🔴 فشل من السيرفر (400/500)
                    String err = response.error != null 
                            ? response.error : "server error";
                    queue.incrementRetry(sale.localUuid, err);
                    failed++;
                    
                    Log.w(TAG, "⚠️ Server error for " + sale.localUuid 
                            + ": " + err);
                    notifySaleResult(callback, sale, false, err);
                }

                // د) تأخير صغير بين الطلبات
                try {
                    Thread.sleep(DELAY_BETWEEN_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }

            } catch (Exception e) {
                Log.e(TAG, "❌ Error syncing " + sale.localUuid, e);
                queue.incrementRetry(sale.localUuid, e.getMessage());
                failed++;
                notifySaleResult(callback, sale, false, e.getMessage());
            }
        }

        // 5) تقرير نهائي
        int remaining = queue.countPending();
        Log.i(TAG, "📊 Sync complete: synced=" + synced 
                + ", failed=" + failed 
                + ", network=" + networkErrors
                + ", remaining=" + remaining);

        // 6) تنظيف البيوع المُرسلة القديمة
        try {
            queue.cleanupOldSent();
        } catch (Exception ignored) {}

        // 7) إعلام الواجهة
        if (callback != null) {
            final int fSynced = synced;
            final int fFailed = failed;
            final int fRemaining = remaining;
            mainHandler.post(() -> 
                    callback.onSyncComplete(fSynced, fFailed, fRemaining));
        }
    }

    private void notifySaleResult(@Nullable SyncCallback callback,
                                  PendingSale sale, 
                                  boolean success, 
                                  @Nullable String error) {
        if (callback == null) return;
        mainHandler.post(() -> {
            try {
                callback.onSaleSynced(sale, success, error);
            } catch (Exception e) {
                Log.e(TAG, "onSaleSynced callback error", e);
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════
    //  📊 حالة
    // ═══════════════════════════════════════════════════════════════

    public boolean isSyncing() {
        return isSyncing.get();
    }

    public int getPendingCount() {
        return queue.countPending();
    }
}
