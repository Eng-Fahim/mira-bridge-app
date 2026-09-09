package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.example.uhf.api.MiraApiClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;

public class IdentitySyncManager {

    private static final String TAG = "IdentitySyncManager";
    private static final String OFFLINE_PREFS = "identity_studio_cache";
    private static final int MAX_PENDING_ITEMS = 200;

    private final Context context;
    private final SharedPreferences offlineCache;
    private final MiraApiClient apiClient;
    private final ExecutorService executor;

    public interface SyncCallback {
        void onSyncComplete(int syncedCount);
        void onSyncError(String error);
    }

    public IdentitySyncManager(Context context, ExecutorService executor) {
        this.context = context;
        this.executor = executor;
        this.offlineCache = context.getSharedPreferences(OFFLINE_PREFS, Context.MODE_PRIVATE);
        this.apiClient = MiraApiClient.getInstance(context);
    }

    public void saveEncodeOffline(JSONObject payload) {
        try {
            String json = offlineCache.getString("pending_encodes", "[]");
            JSONArray arr = new JSONArray(json);
            
            String targetEpc = payload.optString("epc");
            for (int i = 0; i < arr.length(); i++) {
                if (arr.getJSONObject(i).optString("epc").equals(targetEpc)) {
                    arr.remove(i);
                    break;
                }
            }

            arr.put(payload);
            while (arr.length() > MAX_PENDING_ITEMS) arr.remove(0);
            offlineCache.edit().putString("pending_encodes", arr.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "saveEncodeOffline error: " + e.getMessage());
        }
    }

    public int getPendingCount() {
        try {
            String json = offlineCache.getString("pending_encodes", "[]");
            JSONArray arr = new JSONArray(json);
            return arr.length();
        } catch (Exception e) {
            return 0;
        }
    }

    public void syncPendingEncodes(int branchId, SyncCallback callback) {
        executor.execute(() -> {
            try {
                String json = offlineCache.getString("pending_encodes", "[]");
                JSONArray arr = new JSONArray(json);
                if (arr.length() == 0) {
                    if (callback != null) callback.onSyncComplete(0);
                    return;
                }

                JSONArray remainingArr = new JSONArray();
                int synced = 0;
                for (int i = 0; i < arr.length(); i++) {
                    try {
                        JSONObject payload = arr.getJSONObject(i);
                        if (!payload.has("branch_id") || payload.optInt("branch_id", 0) <= 0) {
                            payload.put("branch_id", branchId);
                        }

                        MiraApiClient.ApiResponse response = apiClient.encodeItem(payload);
                        if (response != null && response.isSuccess) {
                            synced++;
                        } else {
                            remainingArr.put(payload);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to sync item", e);
                        remainingArr.put(arr.getJSONObject(i));
                    }
                }
                offlineCache.edit().putString("pending_encodes", remainingArr.toString()).apply();

                if (callback != null) callback.onSyncComplete(synced);
            } catch (Exception e) {
                Log.e(TAG, "syncPendingEncodes error: " + e.getMessage());
                if (callback != null) callback.onSyncError(e.getMessage());
            }
        });
    }

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

    public void removePendingEncode(String epc) {
        try {
            String json = offlineCache.getString("pending_encodes", "[]");
            JSONArray arr = new JSONArray(json);
            JSONArray newArr = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null && !epc.equals(obj.optString("epc", ""))) newArr.put(obj);
            }
            offlineCache.edit().putString("pending_encodes", newArr.toString()).apply();
        } catch (Exception ignored) {
            Log.e(TAG, "removePendingEncode error", ignored);
        }
    }
}
