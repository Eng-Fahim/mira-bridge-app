package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

public class PendingSalesManager {

    private static final String TAG = "PendingSalesManager";
    private static final String PREFS_OFFLINE = "pos_offline_sales_branch_";

    private Context context;

    public PendingSalesManager(Context context) {
        this.context = context;
    }

    public JSONArray getPendingSales(int branchId) {
        try {
            String prefsName = PREFS_OFFLINE + branchId;
            SharedPreferences prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
            return new JSONArray(prefs.getString("pending_sales", "[]"));
        } catch (Exception e) {
            Log.e(TAG, "getPendingSales error: " + e.getMessage());
            return new JSONArray();
        }
    }

    public void savePendingSales(int branchId, JSONArray salesArray) {
        try {
            String prefsName = PREFS_OFFLINE + branchId;
            SharedPreferences prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
            prefs.edit().putString("pending_sales", salesArray.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "savePendingSales error: " + e.getMessage());
        }
    }

    public int getPendingCount(int branchId) {
        return getPendingSales(branchId).length();
    }

    public boolean isItemSoldLocally(String epc, int branchId) {
        try {
            JSONArray arr = getPendingSales(branchId);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject sale = arr.optJSONObject(i);
                if (sale == null) continue;
                
                JSONArray items = sale.optJSONArray("items");
                if (items != null) {
                    for (int j = 0; j < items.length(); j++) {
                        JSONObject item = items.optJSONObject(j);
                        if (item != null && epc.equals(item.optString("epc", ""))) {
                            return true;
                        }
                    }
                }
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "isItemSoldLocally error: " + e.getMessage());
            return false;
        }
    }
}
