package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * ✅ التخزين المحلي للعمل بدون إنترنت
 *
 * v2.0 — إضافات:
 *  - Allowed List مع saleId + انتهاء صلاحية (30 دقيقة)
 *  - دوال addToAllowedList / removeFromAllowedList / isAllowedToExit
 *  - تنظيف تلقائي للإدخالات المنتهية
 *  - توافقية كاملة مع saveAllowedEpcs/getAllowedEpcs القديمة
 */
public class OfflineGateCache {

    private static final String TAG = "OfflineGateCache";
    private static final String PREFS_NAME = "mira_offline_gate_cache";

    // ═══════════════════════════════════════════════════════
    // ثوابت القائمة المسموحة (v2.0)
    // ═══════════════════════════════════════════════════════
    private static final String KEY_ALLOWED_PREFIX  = "gate_allowed_";
    private static final long   ALLOWED_LIST_TTL_MS = 30 * 60 * 1000L; // 30 دقيقة
    private static final String KEY_ALLOWED_LEGACY  = "allowed_epcs";

    private static OfflineGateCache instance;
    private final SharedPreferences prefs;

    private OfflineGateCache(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized OfflineGateCache getInstance(Context context) {
        if (instance == null) {
            instance = new OfflineGateCache(context);
        }
        return instance;
    }

    // ═══════════════════════════════════════════════════════════
    // ✅ القائمة المسموحة — الإصدار القديم (Legacy) — محفوظ للتوافقية
    // ═══════════════════════════════════════════════════════════

    /**
     * @deprecated استخدم {@link #addToAllowedList} بدلاً منها.
     * محفوظة للتوافقية فقط.
     */
    @Deprecated
    public void saveAllowedEpcs(Set<String> allowedEpcs) {
        if (allowedEpcs == null) allowedEpcs = new HashSet<>();
        prefs.edit().putStringSet(KEY_ALLOWED_LEGACY, new HashSet<>(allowedEpcs)).apply();
        Log.d(TAG, "✅ [legacy] Allowed EPCs cached: " + allowedEpcs.size());
    }

    /**
     * يدمج بين القائمة القديمة (StringSet) والقائمة الجديدة (JSON entries).
     * يُستخدم من قِبل {@link #isAllowedLocally} فقط.
     */
    public Set<String> getAllowedEpcs() {
        Set<String> result = new HashSet<>();

        // 1. القائمة القديمة
        Set<String> legacy = prefs.getStringSet(KEY_ALLOWED_LEGACY, new HashSet<>());
        if (legacy != null) result.addAll(legacy);

        // 2. القائمة الجديدة (JSON entries)
        try {
            long now = System.currentTimeMillis();
            Map<String, ?> all = prefs.getAll();
            for (Map.Entry<String, ?> e : all.entrySet()) {
                String key = e.getKey();
                if (!key.startsWith(KEY_ALLOWED_PREFIX)) continue;
                Object value = e.getValue();
                if (!(value instanceof String)) continue;
                try {
                    JSONObject entry = new JSONObject((String) value);
                    long expiresAt = entry.optLong("expires_at", 0);
                    if (expiresAt == 0 || expiresAt > now) {
                        String epc = entry.optString("epc",
                                key.substring(KEY_ALLOWED_PREFIX.length()));
                        if (!epc.isEmpty()) result.add(epc);
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ex) {
            Log.w(TAG, "getAllowedEpcs merge error: " + ex.getMessage());
        }

        return result;
    }

    /**
     * فحص سريع: هل EPC مسموح محلياً؟
     * يفحص القائمتين (القديمة + الجديدة).
     */
    public boolean isAllowedLocally(String epc) {
        if (epc == null || epc.isEmpty()) return false;

        // 1. القائمة القديمة
        Set<String> legacy = prefs.getStringSet(KEY_ALLOWED_LEGACY, new HashSet<>());
        if (legacy != null && legacy.contains(epc)) return true;

        // 2. القائمة الجديدة (مع فحص انتهاء الصلاحية)
        return isAllowedToExit(epc);
    }

    // ═══════════════════════════════════════════════════════════
    // ✅ القائمة المسموحة — الإصدار الجديد (v2.0)
    // ═══════════════════════════════════════════════════════════

    /**
     * يُضيف EPC إلى قائمة القطع المسموح لها بالخروج (بعد بيع ناجح).
     *
     * @param epc        رمز EPC للقطعة
     * @param saleId     معرف البيع (0 للأوفلاين)
     * @param branchId   معرف الفرع
     * @param isOffline  هل البيع أوفلاين؟
     */
    public void addToAllowedList(@NonNull String epc, long saleId,
                                 int branchId, boolean isOffline) {
        if (epc == null || epc.trim().isEmpty()) {
            Log.w(TAG, "addToAllowedList: empty epc — skip");
            return;
        }
        try {
            String cleanEpc = epc.trim();
            long now = System.currentTimeMillis();

            JSONObject entry = new JSONObject();
            entry.put("epc", cleanEpc);
            entry.put("sale_id", saleId);
            entry.put("branch_id", branchId);
            entry.put("is_offline", isOffline);
            entry.put("added_at", now);
            entry.put("expires_at", now + ALLOWED_LIST_TTL_MS);

            // ✅ نحفظ أيضاً في القائمة القديمة للتوافقية
            Set<String> legacy = new HashSet<>(
                    prefs.getStringSet(KEY_ALLOWED_LEGACY, new HashSet<>()));
            legacy.add(cleanEpc);

            prefs.edit()
                    .putString(KEY_ALLOWED_PREFIX + cleanEpc, entry.toString())
                    .putStringSet(KEY_ALLOWED_LEGACY, legacy)
                    .apply();

            Log.d(TAG, "✅ addToAllowedList: " + cleanEpc
                    + " (sale=" + saleId + ", branch=" + branchId
                    + ", offline=" + isOffline + ")");
        } catch (Exception e) {
            Log.e(TAG, "addToAllowedList error: " + e.getMessage(), e);
        }
    }

    /**
     * يزيل EPC من القائمة المسموحة (بعد الإرجاع مثلاً).
     */
    public void removeFromAllowedList(@Nullable String epc) {
        if (epc == null || epc.trim().isEmpty()) return;
        try {
            String cleanEpc = epc.trim();

            Set<String> legacy = new HashSet<>(
                    prefs.getStringSet(KEY_ALLOWED_LEGACY, new HashSet<>()));
            legacy.remove(cleanEpc);

            prefs.edit()
                    .remove(KEY_ALLOWED_PREFIX + cleanEpc)
                    .putStringSet(KEY_ALLOWED_LEGACY, legacy)
                    .apply();

            Log.d(TAG, "🗑️ removeFromAllowedList: " + cleanEpc);
        } catch (Exception e) {
            Log.e(TAG, "removeFromAllowedList error: " + e.getMessage());
        }
    }

    /**
     * يفحص إذا كان EPC مسموحاً له بالخروج (مع فحص انتهاء الصلاحية).
     * يحذف تلقائياً الإدخال إذا انتهى.
     */
    public boolean isAllowedToExit(@Nullable String epc) {
        if (epc == null || epc.trim().isEmpty()) return false;
        try {
            String key = KEY_ALLOWED_PREFIX + epc.trim();
            String json = prefs.getString(key, null);
            if (json == null) return false;

            JSONObject entry = new JSONObject(json);
            long expiresAt = entry.optLong("expires_at", 0);

            if (expiresAt > 0 && System.currentTimeMillis() > expiresAt) {
                // منتهي الصلاحية — احذفه
                removeFromAllowedList(epc);
                Log.d(TAG, "⏰ Expired allowed entry removed: " + epc);
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "isAllowedToExit error: " + e.getMessage());
            return false;
        }
    }

    /**
     * ينظف الإدخالات المنتهية من القائمة الجديدة.
     * يُستدعى دورياً (من MiraGateMonitorService).
     *
     * @return عدد الإدخالات المحذوفة
     */
    public int cleanupExpiredAllowed() {
        try {
            Map<String, ?> all = prefs.getAll();
            long now = System.currentTimeMillis();
            int removed = 0;
            SharedPreferences.Editor editor = prefs.edit();

            for (Map.Entry<String, ?> e : all.entrySet()) {
                String key = e.getKey();
                if (!key.startsWith(KEY_ALLOWED_PREFIX)) continue;
                Object value = e.getValue();
                if (!(value instanceof String)) continue;
                try {
                    JSONObject entry = new JSONObject((String) value);
                    long expiresAt = entry.optLong("expires_at", 0);
                    if (expiresAt > 0 && expiresAt < now) {
                        editor.remove(key);
                        removed++;
                    }
                } catch (Exception ignored) {}
            }

            if (removed > 0) {
                editor.apply();
                Log.i(TAG, "🧹 Cleaned up " + removed + " expired allowed entries");
            }
            return removed;
        } catch (Exception e) {
            Log.e(TAG, "cleanupExpiredAllowed error: " + e.getMessage());
            return 0;
        }
    }

    /**
     * عدد القطع الحالية في القائمة المسموحة (غير المنتهية).
     */
    public int getAllowedListSize() {
        int count = 0;
        try {
            long now = System.currentTimeMillis();
            Map<String, ?> all = prefs.getAll();
            for (Map.Entry<String, ?> e : all.entrySet()) {
                String key = e.getKey();
                if (!key.startsWith(KEY_ALLOWED_PREFIX)) continue;
                Object value = e.getValue();
                if (!(value instanceof String)) continue;
                try {
                    JSONObject entry = new JSONObject((String) value);
                    long expiresAt = entry.optLong("expires_at", 0);
                    if (expiresAt == 0 || expiresAt > now) count++;
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.w(TAG, "getAllowedListSize error: " + e.getMessage());
        }
        return count;
    }

    // ═══════════════════════════════════════════════════════════
    // ✅ سجل الأحداث المحلي
    // ═══════════════════════════════════════════════════════════

    public void saveOfflineEvent(String epc, boolean allowed, String itemTitle, String gateId) {
        JSONObject event = new JSONObject();
        try {
            event.put("epc", epc);
            event.put("allowed", allowed);
            event.put("item_title", itemTitle);
            event.put("gate_id", gateId);
            event.put("timestamp", System.currentTimeMillis());

            JSONArray events = getOfflineEvents();
            events.put(event);

            if (events.length() > 1000) {
                JSONArray trimmed = new JSONArray();
                for (int i = events.length() - 1000; i < events.length(); i++) {
                    trimmed.put(events.optJSONObject(i));
                }
                events = trimmed;
            }

            prefs.edit().putString("offline_events", events.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "Save offline event: " + e.getMessage());
        }
    }

    public JSONArray getOfflineEvents() {
        String json = prefs.getString("offline_events", "[]");
        try {
            return new JSONArray(json);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public int getPendingEventsCount() {
        return getOfflineEvents().length();
    }

    public void clearOfflineEvents() {
        prefs.edit().remove("offline_events").apply();
    }

    // ═══════════════════════════════════════════════════════════
    // ✅ حالة الاتصال
    // ═══════════════════════════════════════════════════════════

    public void saveConnectionState(boolean isOnline) {
        prefs.edit().putBoolean("is_online", isOnline).apply();
    }

    public boolean isOnline() {
        return prefs.getBoolean("is_online", true);
    }

    public void saveLastSyncTime(long timestamp) {
        prefs.edit().putLong("last_sync", timestamp).apply();
    }

    public long getLastSyncTime() {
        return prefs.getLong("last_sync", 0);
    }
}
