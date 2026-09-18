package com.example.uhf.tools;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * ✅ تخزين مؤقت لآخر العمليات
 * - يمنع التأخير عند كل عودة للشاشة
 * - يعرض البيانات الفورية ثم يُحدّث في الخلفية
 */
public class RecentActivityCache {

    private static final String TAG = "RecentActivityCache";
    private static final String PREFS_NAME = "recent_activity_cache";
    private static final String KEY_ACTIVITIES = "activities_json";
    private static final String KEY_LAST_UPDATE = "last_update";
    private static final long CACHE_VALIDITY_MS = 2 * 60 * 1000; // دقيقتان

    private final SharedPreferences prefs;

    public RecentActivityCache(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * ✅ حفظ آخر العمليات في الكاش
     */
    public void save(JSONArray activities) {
        if (activities == null) return;
        try {
            prefs.edit()
                    .putString(KEY_ACTIVITIES, activities.toString())
                    .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
                    .apply();
            Log.d(TAG, "✅ Saved " + activities.length() + " activities to cache");
        } catch (Exception e) {
            Log.e(TAG, "save error: " + e.getMessage());
        }
    }

    /**
     * ✅ استرجاع آخر العمليات من الكاش
     */
    public JSONArray load() {
        try {
            String json = prefs.getString(KEY_ACTIVITIES, null);
            if (json == null || json.isEmpty()) return new JSONArray();
            return new JSONArray(json);
        } catch (Exception e) {
            Log.e(TAG, "load error: " + e.getMessage());
            return new JSONArray();
        }
    }

    /**
     * ✅ هل الكاش لا يزال صالحاً؟
     */
    public boolean isValid() {
        long lastUpdate = prefs.getLong(KEY_LAST_UPDATE, 0);
        if (lastUpdate <= 0) return false;
        return (System.currentTimeMillis() - lastUpdate) < CACHE_VALIDITY_MS;
    }

    public long getLastUpdate() {
        return prefs.getLong(KEY_LAST_UPDATE, 0);
    }

    public void clear() {
        prefs.edit().clear().apply();
    }
}
