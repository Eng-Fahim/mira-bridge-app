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
    
    public interface ItemLookupCallback {
        void onItemFound(JSONObject item, boolean fromServer);
        void onItemNotFound();
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
    
    public void findItem(String code, ItemLookupCallback callback) {
        findItem(code, getActiveBranchId(), callback);
    }
    
    public void findItem(String code, int branchId, ItemLookupCallback callback) {
        executor.execute(() -> {
            try {
                Log.e(TAG, "🔍 findItem called: code=" + code + ", branchId=" + branchId);
                
                JSONObject payload = new JSONObject();
                payload.put("epc", code);
                payload.put("serial_no", code);
                payload.put("branch_id", branchId);
                payload.put("context", "pos_sale");
                
                Log.e(TAG, "📦 findItem payload: " + payload.toString());
                
                MiraApiClient.ApiResponse response = apiClient.searchPosItem(payload);
                
                Log.e(TAG, "📥 findItem response: code=" + response.code + ", success=" + response.isSuccess);
                Log.e(TAG, "📥 findItem error: " + response.error);
                Log.e(TAG, "📥 findItem body: " + response.getBody());
                
                // ✅ التحقق من أن القطعة في الفرع الصحيح
                if (response != null && response.isSuccess && response.data != null) {
                    JSONObject item = response.data.optJSONObject("item");
                    
                    if (item != null) {
                        int itemBranchId = item.optInt("branch_id", -1);
                        
                        if (itemBranchId <= 0 || itemBranchId == branchId) {
                            itemCache.cacheItem(code, item);
                            
                            if (callback != null) {
                                callback.onItemFound(item, true);
                            }
                            return;
                        } else {
                            Log.w(TAG, "Item branch_id=" + itemBranchId + " != current branch=" + branchId);
                            if (callback != null) {
                                callback.onItemNotFound();
                            }
                            return;
                        }
                    }
                }
                
                // 2️⃣ البحث في الكاش المحلي
                JSONObject cachedItem = itemCache.findItemComprehensive(code);
                if (cachedItem != null) {
                    int cachedBranchId = cachedItem.optInt("branch_id", -1);
                    if (cachedBranchId <= 0 || cachedBranchId == branchId) {
                        if (callback != null) {
                            callback.onItemFound(cachedItem, false);
                        }
                        return;
                    }
                }
                
                // 3️⃣ غير موجود
                if (callback != null) {
                    callback.onItemNotFound();
                }
                
            } catch (Exception e) {
                Log.e(TAG, "findItem error: " + e.getMessage(), e);
                
                // 4️⃣ خطأ اتصال - جرب الكاش
                JSONObject cachedItem = itemCache.findItemComprehensive(code);
                if (cachedItem != null) {
                    int cachedBranchId = cachedItem.optInt("branch_id", -1);
                    if (cachedBranchId <= 0 || cachedBranchId == branchId) {
                        if (callback != null) {
                            callback.onItemFound(cachedItem, false);
                        }
                        return;
                    }
                }
                
                if (callback != null) {
                    callback.onItemNotFound();
                }
            }
        });
    }
    
    private int getActiveBranchId() {
        try {
            return MiraBranchesManager.getInstance(context).getActiveBranchId();
        } catch (Exception e) {
            return 1;
        }
    }
}
