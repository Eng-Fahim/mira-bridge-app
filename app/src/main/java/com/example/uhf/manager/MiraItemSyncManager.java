// ==================== ملف جديد: MiraItemSyncManager.java ====================
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
    
    public interface SyncCallback {
        void onSyncComplete(int itemCount);
        void onSyncError(String error);
    }
    
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
    
    /**
     * ✅ مزامنة جميع القطع من السيرفر
     */
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
                    String endpoint = "/branches/" + getActiveBranchId() + "/inventory/items?page=" + page + "&per_page=" + perPage;
                    
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
    
    /**
     * ✅ البحث عن قطعة - من السيرفر أولاً ثم المحلي
     */
    public void findItem(String code, ItemLookupCallback callback) {
        executor.execute(() -> {
            try {
                // 1️⃣ محاولة البحث في السيرفر
                JSONObject extraParams = new JSONObject();
                extraParams.put("source", "pos_search");
                extraParams.put("branch_id", getActiveBranchId());
                
                MiraApiClient.ApiResponse response = apiClient.authorize(code, "pos_sale", extraParams);
                
                if (response != null && response.isSuccess && response.data != null 
                        && response.data.optJSONObject("item") != null) {
                    JSONObject item = response.data.optJSONObject("item");
                    
                    // ✅ حفظ في الكاش للاستخدام المستقبلي
                    itemCache.cacheItem(code, item);
                    
                    if (callback != null) {
                        callback.onItemFound(item, true);
                    }
                    return;
                }
                
                // 2️⃣ البحث في الكاش المحلي
                JSONObject cachedItem = itemCache.findItem(code);
                if (cachedItem != null) {
                    if (callback != null) {
                        callback.onItemFound(cachedItem, false);
                    }
                    return;
                }
                
                // 3️⃣ غير موجود
                if (callback != null) {
                    callback.onItemNotFound();
                }
                
            } catch (Exception e) {
                // 4️⃣ خطأ اتصال - جرب الكاش
                JSONObject cachedItem = itemCache.findItem(code);
                if (cachedItem != null) {
                    if (callback != null) {
                        callback.onItemFound(cachedItem, false);
                    }
                } else {
                    if (callback != null) {
                        callback.onItemNotFound();
                    }
                }
            }
        });
    }
    
    public interface ItemLookupCallback {
        void onItemFound(JSONObject item, boolean fromServer);
        void onItemNotFound();
    }
    
    private int getActiveBranchId() {
        try {
            return MiraBranchesManager.getInstance(context).getActiveBranchId();
        } catch (Exception e) {
            return 1;
        }
    }
}
