package com.example.uhf.tools;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;

public class RecentActivityCache {

    private static final String TAG = "RecentActivityCache";
    private static final String PREFS_NAME = "recent_activity_cache_v2";
    private static final String KEY_ACTIVITIES = "activities_json";
    private static final String KEY_LAST_UPDATE = "last_update";
    private static final long CACHE_VALIDITY_MS = 2 * 60 * 1000L;

    private final SharedPreferences prefs;

    public RecentActivityCache(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private String keyData(int branchId) {
        return KEY_ACTIVITIES + "_b" + Math.max(0, branchId);
    }

    private String keyTime(int branchId) {
        return KEY_LAST_UPDATE + "_b" + Math.max(0, branchId);
    }

    public void save(JSONArray activities) {
        save(0, activities);
    }

    public void save(int branchId, JSONArray activities) {
        if (activities == null) return;
        try {
            prefs.edit()
                    .putString(keyData(branchId), activities.toString())
                    .putLong(keyTime(branchId), System.currentTimeMillis())
                    .apply();
            Log.d(TAG, "✅ Saved " + activities.length() + " unified operations (branch=" + branchId + ")");
        } catch (Exception e) {
            Log.e(TAG, "save error: " + e.getMessage());
        }
    }

    public JSONArray load() {
        return load(0);
    }

    public JSONArray load(int branchId) {
        try {
            String json = prefs.getString(keyData(branchId), null);
            if (json == null || json.isEmpty()) return new JSONArray();
            return new JSONArray(json);
        } catch (Exception e) {
            Log.e(TAG, "load error: " + e.getMessage());
            return new JSONArray();
        }
    }

    public boolean isValid() {
        return isValid(0);
    }

    public boolean isValid(int branchId) {
        long lastUpdate = prefs.getLong(keyTime(branchId), 0);
        if (lastUpdate <= 0) return false;
        return (System.currentTimeMillis() - lastUpdate) < CACHE_VALIDITY_MS;
    }

    public long getLastUpdate() {
        return prefs.getLong(keyTime(0), 0);
    }

    public void clear() {
        prefs.edit().clear().apply();
    }
}
