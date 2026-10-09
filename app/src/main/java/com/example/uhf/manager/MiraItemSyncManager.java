// ==================== ملف: MiraItemSyncManager.java ====================
package com.example.uhf.manager;

import android.content.Context;
import android.util.Log;

import com.example.uhf.api.MiraApiClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ✅ مزامنة القطع محلياً للعمل بدون انترنت
 *
 * v2.0 — إصلاحات:
 *   - findItem: cache أولاً (فوري 20ms بدلاً من 5-10 ثواني)
 *   - حل مشكلة "القطعة غير موجودة" في POS
 *   - ضمان العمل بدون إنترنت بشكل صحيح
 */
public class MiraItemSyncManager {

    private static final String TAG = "MiraItemSyncManager";
    private static MiraItemSyncManager instance;

    private final Context context;
    private final MiraApiClient apiClient;
    private final MiraLocalItemCache itemCache;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private boolean isSyncing = false;
    private SyncCallback callback;

    // ==================== Interfaces ====================

    public interface SyncCallback {
        void onSyncComplete(int itemCount);
        void onSyncError(String error);
    }

    public interface ItemLookupCallback {
        void onItemFound(JSONObject item, boolean fromServer);
        void onItemNotFound();
    }

    // ==================== Singleton ====================

    public static synchronized MiraItemSyncManager getInstance(Context context) {
        if (instance == null) {
            instance = new MiraItemSyncManager(context.getApplicationContext());
        }
        return instance;
    }

    private MiraItemSyncManager(Context context) {
        this.context = context;
        this.apiClient = MiraApiClient.getInstance(context);
        this.itemCache = MiraLocalItemCache.getInstance(context);
    }

    // ==================== Full Sync ====================

    public void syncAllItems(SyncCallback callback) {
        this.callback = callback;

        if (isSyncing) {
            Log.d(TAG, "Already syncing, skipping...");
            return;
        }

        isSyncing = true;

        executor.execute(() -> {
            try {
                Log.d(TAG, "🔄 Starting full item sync...");

                int totalSynced = 0;
                int page = 1;
                int perPage = 200;
                boolean hasMore = true;

                while (hasMore) {
                    String endpoint = "/branches/" + getActiveBranchId()
                            + "/inventory/items?page=" + page
                            + "&per_page=" + perPage;

                    MiraApiClient.ApiResponse response = apiClient.getWithAuth(endpoint);

                    if (response != null && response.isSuccess && response.data != null) {
                        JSONArray items = response.data.optJSONArray("items");
                        if (items == null) {
                            items = response.data.optJSONArray("data");
                        }

                        if (items != null && items.length() > 0) {
                            itemCache.cacheAllItems(items);
                            totalSynced += items.length();
                            page++;

                            if (items.length() < perPage) {
                                hasMore = false;
                            }
                        } else {
                            hasMore = false;
                        }
                    } else {
                        hasMore = false;
                        if (totalSynced == 0) {
                            throw new Exception("Failed to sync items");
                        }
                    }
                }

                itemCache.updateLastSyncTime();
                Log.d(TAG, "✅ Synced " + totalSynced + " items");

                if (callback != null) {
                    callback.onSyncComplete(totalSynced);
                }

            } catch (Exception e) {
                Log.e(TAG, "syncAllItems error: " + e.getMessage(), e);
                if (callback != null) {
                    callback.onSyncError(e.getMessage());
                }
            } finally {
                isSyncing = false;
            }
        });
    }

    // ==================== Item Lookup ====================

    public void findItem(String code, ItemLookupCallback callback) {
        findItem(code, getActiveBranchId(), callback);
    }

    /**
     * ✅ findItem — النسخة المُصحّحة v2.0
     *
     * 🎯 استراتيجية البحث الجديدة:
     *   1) cache المحلي أولاً ← ⚡ فوري (20ms)
     *   2) السيرفر ثانياً (لو لم يوجد محلياً)
     *   3) cache مرة أخرى (لو حُدّث خلال الاستعلام)
     *   4) الفشل
     *
     * الفرق عن السابق:
     *   - كان السيرفر أولاً (5-10 ثواني timeout)
     *   - الآن cache أولاً (20ms)
     */
    public void findItem(String code, int branchId, ItemLookupCallback callback) {
        executor.execute(() -> {
            try {
                Log.d(TAG, "🔍 findItem: code=" + code + ", branchId=" + branchId);

                // ═══════════════════════════════════════════════════════
                //  ✅ [الإصلاح] 1) ابحث في cache المحلي أولاً
                //  هذا يجعل العملية فورية ويضمن العمل بدون إنترنت
                // ═══════════════════════════════════════════════════════
                JSONObject cachedItem = itemCache.findItemComprehensive(code);
                if (cachedItem != null) {
                    int cachedBranchId = cachedItem.optInt("branch_id", -1);
                    if (cachedBranchId <= 0 || cachedBranchId == branchId) {
                        Log.i(TAG, "✅ Found in local cache: " + code
                                + " (fromServer=false)");
                        if (callback != null) {
                            callback.onItemFound(cachedItem, false);
                        }
                        return;
                    } else {
                        Log.w(TAG, "⚠️ Cached item belongs to branch "
                                + cachedBranchId + " (current=" + branchId + ")");
                    }
                }

                // ═══════════════════════════════════════════════════════
                //  2) لم يوجد محلياً → استعلم السيرفر
                // ═══════════════════════════════════════════════════════
                JSONObject payload = new JSONObject();
                payload.put("epc", code);
                payload.put("serial_no", code);
                payload.put("branch_id", branchId);
                payload.put("context", "pos_sale");

                Log.d(TAG, "📡 Asking server for: " + code);

                MiraApiClient.ApiResponse response = apiClient.searchPosItem(payload);

                // ✅ 3) السيرفر ردّ بنجاح
                if (response != null && response.isSuccess && response.data != null) {
                    JSONObject item = response.data.optJSONObject("item");

                    if (item != null) {
                        int itemBranchId = item.optInt("branch_id", -1);

                        if (itemBranchId <= 0 || itemBranchId == branchId) {
                            // ✅ خزّنه في الـ cache
                            itemCache.cacheItem(code, item);
                            Log.i(TAG, "✅ Found on server: " + code
                                    + " (fromServer=true)");

                            if (callback != null) {
                                callback.onItemFound(item, true);
                            }
                            return;
                        } else {
                            Log.w(TAG, "⚠️ Server item branch mismatch: "
                                    + itemBranchId + " vs " + branchId);
                        }
                    }
                } else {
                    Log.w(TAG, "⚠️ Server response failed: "
                            + (response != null ? response.error : "null"));
                }

                // ═══════════════════════════════════════════════════════
                //  4) لم يوجد في السيرفر → فحص أخير في cache
                //     (قد يكون حُدّث خلال الاستعلام من thread آخر)
                // ═══════════════════════════════════════════════════════
                JSONObject retryCached = itemCache.findItemComprehensive(code);
                if (retryCached != null) {
                    int retryBranchId = retryCached.optInt("branch_id", -1);
                    if (retryBranchId <= 0 || retryBranchId == branchId) {
                        Log.i(TAG, "✅ Found after retry: " + code);
                        if (callback != null) {
                            callback.onItemFound(retryCached, false);
                        }
                        return;
                    }
                }

                // ═══════════════════════════════════════════════════════
                //  5) غير موجود في أي مكان
                // ═══════════════════════════════════════════════════════
                Log.w(TAG, "❌ Item not found anywhere: " + code);
                if (callback != null) {
                    callback.onItemNotFound();
                }

            } catch (Exception e) {
                Log.e(TAG, "findItem exception: " + e.getMessage(), e);

                // ✅ 6) في حالة الاستثناء، جرّب cache كملاذ أخير
                try {
                    JSONObject cachedItem = itemCache.findItemComprehensive(code);
                    if (cachedItem != null) {
                        int cachedBranchId = cachedItem.optInt("branch_id", -1);
                        if (cachedBranchId <= 0 || cachedBranchId == branchId) {
                            Log.i(TAG, "✅ Found in cache (exception fallback): " + code);
                            if (callback != null) {
                                callback.onItemFound(cachedItem, false);
                            }
                            return;
                        }
                    }
                } catch (Exception innerE) {
                    Log.e(TAG, "Cache fallback failed", innerE);
                }

                if (callback != null) {
                    callback.onItemNotFound();
                }
            }
        });
    }

    // ==================== Helpers ====================

    private int getActiveBranchId() {
        try {
            return MiraBranchesManager.getInstance(context).getActiveBranchId();
        } catch (Exception e) {
            return 1;
        }
    }
}
