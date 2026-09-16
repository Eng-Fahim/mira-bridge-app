package com.example.uhf.manager.inventory;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.example.uhf.model.ScannedItemDisplay;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * ✅ InventorySessionManager v2.0
 * 
 * إصلاحات v2.0:
 * - إزالة synchronized الزائد (الكتابة على Main Thread)
 * - نسخة آمنة قبل الكتابة (new ArrayList<>)
 * - حفظ عبر apply() (لا يحجب)
 * - بناء JSON محسّن
 * - استرجاع محمي ضد JSON تالف
 */
public class InventorySessionManager {

    private static final String TAG = "InventorySessionMgr";

    private static final String PREFS_INVENTORY = "inventory_session";
    private static final String KEY_SESSION_DATA = "session_data";
    private static final String KEY_SESSION_TIME = "session_time";
    private static final long SESSION_EXPIRY_MS = 30 * 60 * 1000; // 30 دقيقة

    private final Context context;
    private final boolean isArabic;

    // ==================== Callbacks ====================

    public interface SessionCallback {
        void onSessionFound(JSONArray items, int expectedCount,
                             String category, long timeAgoMillis);
        void onNoSession();
    }

    // ==================== Constructor ====================

    public InventorySessionManager(Context context, boolean isArabic) {
        this.context = context.getApplicationContext();
        this.isArabic = isArabic;
    }

    // ==================== Save ====================

    /**
     * ✅ حفظ الجلسة الحالية
     * 
     * ⚠️ يجب استدعاؤها من Main Thread فقط
     */
    public void saveSession(int expectedCount,
                             long scanStartTime,
                             String category,
                             List<ScannedItemDisplay> items) {
        try {
            if (context == null) return;

            JSONObject sessionData = new JSONObject();
            sessionData.put("expectedCount", expectedCount);
            sessionData.put("scanStartTime", scanStartTime);
            sessionData.put("category", category != null ? category : "");

            JSONArray itemsArray = new JSONArray();

            // ✅ نسخة آمنة (لا synchronized — نسخة واحدة، كتابة على Main Thread)
            List<ScannedItemDisplay> snapshot = null;
            if (items != null) {
                snapshot = new ArrayList<>(items);
            }

            if (snapshot != null) {
                for (ScannedItemDisplay item : snapshot) {
                    if (item == null) continue;
                    try {
                        JSONObject itemObj = new JSONObject();
                        itemObj.put("epc", item.epc != null ? item.epc : "");
                        itemObj.put("title", item.title != null ? item.title : "");
                        itemObj.put("karat", item.karat != null ? item.karat : "");
                        itemObj.put("count", item.count);
                        itemObj.put("rssi", item.rssi);
                        itemObj.put("time", item.time != null ? item.time : "");
                        itemObj.put("weight", item.weight);
                        itemObj.put("price", item.price);
                        itemsArray.put(itemObj);
                    } catch (Exception e) {
                        Log.e(TAG, "item serialize error: " + e.getMessage());
                    }
                }
            }

            sessionData.put("items", itemsArray);

            context.getSharedPreferences(PREFS_INVENTORY, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_SESSION_DATA, sessionData.toString())
                    .putLong(KEY_SESSION_TIME, System.currentTimeMillis())
                    .apply();  // ✅ apply() لا يحجب

            Log.d(TAG, "✅ Session saved: " + itemsArray.length() + " items");

        } catch (Exception e) {
            Log.e(TAG, "saveSession error: " + e.getMessage());
        }
    }

    // ==================== Restore ====================

    /**
     * ✅ محاولة استرجاع جلسة سابقة
     */
    public void tryRestoreSession(SessionCallback callback) {
        try {
            if (context == null) {
                if (callback != null) callback.onNoSession();
                return;
            }

            SharedPreferences prefs = context.getSharedPreferences(
                    PREFS_INVENTORY, Context.MODE_PRIVATE);

            String sessionDataStr = prefs.getString(KEY_SESSION_DATA, null);
            long sessionTime = prefs.getLong(KEY_SESSION_TIME, 0);

            if (sessionDataStr == null) {
                if (callback != null) callback.onNoSession();
                return;
            }

            // ✅ التحقق من الصلاحية
            long timeDiff = System.currentTimeMillis() - sessionTime;
            if (timeDiff > SESSION_EXPIRY_MS) {
                Log.d(TAG, "Session expired (age: " + timeDiff + "ms)");
                clearSession();
                if (callback != null) callback.onNoSession();
                return;
            }

            // ✅ تحليل البيانات
            JSONObject sessionData = new JSONObject(sessionDataStr);
            int expectedCount = sessionData.optInt("expectedCount", 0);
            String category = sessionData.optString("category", "");
            JSONArray items = sessionData.optJSONArray("items");

            if (items == null || items.length() == 0) {
                clearSession();
                if (callback != null) callback.onNoSession();
                return;
            }

            if (callback != null) {
                callback.onSessionFound(items, expectedCount, category, timeDiff);
            }

        } catch (Exception e) {
            Log.e(TAG, "tryRestoreSession error: " + e.getMessage());
            if (callback != null) callback.onNoSession();
        }
    }

    // ==================== Clear ====================

    public void clearSession() {
        try {
            if (context == null) return;
            context.getSharedPreferences(PREFS_INVENTORY, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .apply();
            Log.d(TAG, "✅ Session cleared");
        } catch (Exception e) {
            Log.e(TAG, "clearSession error: " + e.getMessage());
        }
    }

    // ==================== Check ====================

    public boolean hasValidSession() {
        try {
            if (context == null) return false;

            SharedPreferences prefs = context.getSharedPreferences(
                    PREFS_INVENTORY, Context.MODE_PRIVATE);

            String sessionDataStr = prefs.getString(KEY_SESSION_DATA, null);
            long sessionTime = prefs.getLong(KEY_SESSION_TIME, 0);

            if (sessionDataStr == null) return false;

            long timeDiff = System.currentTimeMillis() - sessionTime;
            return timeDiff <= SESSION_EXPIRY_MS;

        } catch (Exception e) {
            return false;
        }
    }

    // ==================== Helpers ====================

    public String formatTimeAgo(long millis) {
        long minutes = millis / 60000;
        if (minutes < 1) return isArabic ? "أقل من دقيقة" : "less than a minute";
        if (minutes < 60) return minutes + (isArabic ? " دقيقة" : " min");
        long hours = minutes / 60;
        return hours + (isArabic ? " ساعة" : " hour");
    }

    // ==================== Builders ====================

    public String buildRestoreMessage(JSONArray items, long timeAgoMillis) {
        int count = items != null ? items.length() : 0;
        return isArabic ?
                "تم العثور على جلسة سابقة من " +
                formatTimeAgo(timeAgoMillis) + " تحتوي على " + count + " قطعة.\n\n" +
                "هل تريد استرجاعها؟" :
                "Found a previous session from " +
                formatTimeAgo(timeAgoMillis) + " with " + count + " items.\n\n" +
                "Restore it?";
    }

    public String buildSessionPreview(JSONArray items, int expected, String category) {
        try {
            if (items == null) items = new JSONArray();

            StringBuilder sb = new StringBuilder();
            sb.append(isArabic ? "📊 ملخص الجلسة:\n\n" : "📊 Session Summary:\n\n");
            sb.append(isArabic ? "• المتوقع: " : "• Expected: ").append(expected).append("\n");
            sb.append(isArabic ? "• الممسوح: " : "• Scanned: ").append(items.length()).append("\n");

            if (category != null && !category.isEmpty()) {
                sb.append(isArabic ? "• الفئة: " : "• Category: ").append(category).append("\n");
            }

            sb.append("\n").append(isArabic ? "📋 القطع المحفوظة:\n\n" : "📋 Saved items:\n\n");

            int max = Math.min(items.length(), 10);
            for (int i = 0; i < max; i++) {
                JSONObject item = items.getJSONObject(i);
                String title = item.optString("title", "");
                if (title.isEmpty() || title.equals("—")) {
                    title = item.optString("epc");
                }
                sb.append("• ").append(title)
                  .append(" ×").append(item.optInt("count", 1)).append("\n");
            }

            if (items.length() > max) {
                sb.append("\n... +").append(items.length() - max)
                  .append(isArabic ? " قطعة" : " more");
            }

            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "buildSessionPreview error: " + e.getMessage());
            return "";
        }
    }
}
