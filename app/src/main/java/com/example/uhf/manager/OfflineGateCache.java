package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  OfflineGateCache v3.2 — PRODUCTION READY
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * إصلاحات v3.2 (تكامل مع OfflineSalesSyncer):
 *   🔴 [CRITICAL] saveAllowedEpcs() لا يحذف إدخالات is_offline=true
 *      — يحفظ البيوع التي لم تُزامَن بعد
 *      — كانت تُمحى عند أول refresh بعد عودة الشبكة
 *      — العميل يدفع offline → البوابة ترفضه بعد عودة الشبكة
 *   ✅ markAsSynced(epc) — جديدة
 *      — تُستدعى من OfflineSalesSyncer بعد نجاح المزامنة
 *      — تُزيل is_offline flag (السيرفر الآن يعرف القطعة)
 *
 * إصلاحات v3.1:
 *   ✅ addToAllowedList() يحدّث KEY_LAST_CACHE_FETCH
 *      — إضافة محلية = ثقة محلية → فحص الطازجية يعمل
 *
 * إصلاحات v3.0:
 *   ✅ حذف القائمة القديمة `allowed_epcs` بدون TTL
 *   ✅ saveAllowedEpcs يستبدل بدل يدمج
 *   ✅ TTL = 24 ساعة (مطابق للسيرفر)
 *   ✅ تطبيع EPC إلى UPPERCASE
 *   ✅ تتبّع last_cache_fetch_time
 *   ✅ migration legacy
 */
public class OfflineGateCache {

    private static final String TAG = "OfflineGateCache";
    private static final String PREFS_NAME = "mira_offline_gate_cache";

    // ═══════════════════════════════════════════════════════
    // ثوابت القائمة المسموحة
    // ═══════════════════════════════════════════════════════
    private static final String KEY_ALLOWED_PREFIX   = "gate_allowed_";

    /** ✅ TTL = 24 ساعة — مطابق للسيرفر */
    private static final long   ALLOWED_LIST_TTL_MS  = 24 * 60 * 60 * 1000L;

    /** ✅ مفتاح لتتبّع آخر جلب ناجح من السيرفر */
    private static final String KEY_LAST_CACHE_FETCH = "last_cache_fetch";

    /** ⚠️ مفتاح legacy — يُحذف عند التهيئة */
    @Deprecated
    private static final String KEY_ALLOWED_LEGACY   = "allowed_epcs";

    // ═══════════════════════════════════════════════════════
    // Singleton
    // ═══════════════════════════════════════════════════════
    private static OfflineGateCache instance;
    private final SharedPreferences prefs;

    private OfflineGateCache(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // ✅ migration: احذف legacy مرة واحدة (بدون TTL — خطر أمني)
        try {
            if (prefs.contains(KEY_ALLOWED_LEGACY)) {
                int size = prefs.getStringSet(KEY_ALLOWED_LEGACY,
                        new HashSet<>()).size();
                prefs.edit().remove(KEY_ALLOWED_LEGACY).apply();
                Log.w(TAG, "🧹 [SECURITY] Deleted legacy allowed_epcs ("
                        + size + " entries without TTL)");
            }
        } catch (Exception e) {
            Log.e(TAG, "migration legacy delete error", e);
        }
    }

    public static synchronized OfflineGateCache getInstance(Context context) {
        if (instance == null) {
            instance = new OfflineGateCache(context);
        }
        return instance;
    }

    // ═══════════════════════════════════════════════════════
    // تطبيع EPC (uppercase + trim)
    // ═══════════════════════════════════════════════════════
    @NonNull
    private static String normalize(@NonNull String epc) {
        return epc.trim().toUpperCase(Locale.US);
    }

    // ═══════════════════════════════════════════════════════
    // إضافة / إزالة
    // ═══════════════════════════════════════════════════════

    /**
     * يضيف EPC إلى القائمة المسموحة (بعد بيع ناجح).
     * ✅ [v3.1] يحدّث طازجية الكاش — إضافة محلية = ثقة محلية.
     */
    public void addToAllowedList(@NonNull String epc, long saleId,
                                 int branchId, boolean isOffline) {
        if (epc == null || epc.trim().isEmpty()) {
            Log.w(TAG, "addToAllowedList: empty epc — skip");
            return;
        }
        try {
            String cleanEpc = normalize(epc);
            long now = System.currentTimeMillis();

            JSONObject entry = new JSONObject();
            entry.put("epc",        cleanEpc);
            entry.put("sale_id",    saleId);
            entry.put("branch_id",  branchId);
            entry.put("is_offline", isOffline);
            entry.put("added_at",   now);
            entry.put("expires_at", now + ALLOWED_LIST_TTL_MS);

            prefs.edit()
                    .putString(KEY_ALLOWED_PREFIX + cleanEpc, entry.toString())
                    // ✅ [v3.1] إضافة محلية = ثقة محلية
                    .putLong(KEY_LAST_CACHE_FETCH, now)
                    .apply();

            Log.d(TAG, "✅ addToAllowedList: " + cleanEpc
                    + " (sale=" + saleId + ", branch=" + branchId
                    + ", offline=" + isOffline + ", TTL=24h)");
        } catch (Exception e) {
            Log.e(TAG, "addToAllowedList error: " + e.getMessage(), e);
        }
    }

    /**
     * يزيل EPC من القائمة المسموحة.
     */
    public void removeFromAllowedList(@Nullable String epc) {
        if (epc == null || epc.trim().isEmpty()) return;
        try {
            String cleanEpc = normalize(epc);
            prefs.edit()
                    .remove(KEY_ALLOWED_PREFIX + cleanEpc)
                    .apply();
            Log.d(TAG, "🗑️ removeFromAllowedList: " + cleanEpc);
        } catch (Exception e) {
            Log.e(TAG, "removeFromAllowedList error: " + e.getMessage());
        }
    }

    /**
     * ✅ [v3.2] بعد مزامنة بيع offline بنجاح.
     * يُزيل is_offline flag حتى يستبدلها refresh القادم من السيرفر.
     *
     * يُستدعى من OfflineSalesSyncer.SyncCallback.onSaleSynced().
     */
    public void markAsSynced(@Nullable String epc) {
        if (epc == null || epc.trim().isEmpty()) return;
        try {
            String cleanEpc = normalize(epc);
            String key = KEY_ALLOWED_PREFIX + cleanEpc;
            String json = prefs.getString(key, null);
            if (json == null) return;

            JSONObject entry = new JSONObject(json);
            entry.put("is_offline", false);
            entry.put("synced_at", System.currentTimeMillis());

            prefs.edit().putString(key, entry.toString()).apply();
            Log.d(TAG, "✅ markAsSynced: " + cleanEpc);
        } catch (Exception e) {
            Log.e(TAG, "markAsSynced error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════
    // فحص الصلاحية
    // ═══════════════════════════════════════════════════════

    /**
     * هل EPC مسموح بالخروج (مع فحص TTL)?
     */
    public boolean isAllowedToExit(@Nullable String epc) {
        if (epc == null || epc.trim().isEmpty()) return false;
        try {
            String cleanEpc = normalize(epc);
            String key = KEY_ALLOWED_PREFIX + cleanEpc;
            String json = prefs.getString(key, null);
            if (json == null) return false;

            JSONObject entry = new JSONObject(json);
            long expiresAt = entry.optLong("expires_at", 0);

            if (expiresAt > 0 && System.currentTimeMillis() > expiresAt) {
                prefs.edit().remove(key).apply();
                Log.d(TAG, "⏰ Expired allowed entry removed: " + cleanEpc);
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "isAllowedToExit error: " + e.getMessage());
            return false;
        }
    }

    /**
     * الفحص الموحّد — يُستخدم من MiraSecureGateFragment.
     */
    public boolean isAllowedLocally(String epc) {
        return isAllowedToExit(epc);
    }

    // ═══════════════════════════════════════════════════════
    // استبدال القائمة (من السيرفر)
    // ═══════════════════════════════════════════════════════

    /**
     * ✅ [v3.2] استبدال القائمة — يحافظ على إدخالات offline
     *
     * ⚠️ CRITICAL: بدون v3.2، إدخالات is_offline=true كانت تُمحى
     * عند أول refresh بعد عودة الشبكة، قبل مزامنة البيع → العميل يُرفض.
     *
     * المنطق:
     *   - احذف كل إدخالات السيرفر (is_offline=false)
     *   - احتفظ بالإدخالات offline غير المنتهية (is_offline=true)
     *   - أضف serverAllowedEpcs (بـ is_offline=false)
     */
    public void saveAllowedEpcs(Set<String> serverAllowedEpcs) {
        if (serverAllowedEpcs == null) return;
        try {
            long now = System.currentTimeMillis();
            SharedPreferences.Editor editor = prefs.edit();

            // ═══════════════════════════════════════════════════
            // 1) حذف انتقائي — احتفظ بـ offline
            // ═══════════════════════════════════════════════════
            Map<String, ?> all = prefs.getAll();
            int preservedOffline = 0;
            int removedServer    = 0;

            for (Map.Entry<String, ?> e : all.entrySet()) {
                String key = e.getKey();
                if (!key.startsWith(KEY_ALLOWED_PREFIX)) continue;

                Object value = e.getValue();
                if (!(value instanceof String)) {
                    editor.remove(key);
                    continue;
                }

                try {
                    JSONObject entry = new JSONObject((String) value);
                    boolean isOffline = entry.optBoolean("is_offline", false);
                    long expiresAt    = entry.optLong("expires_at", 0);
                    boolean isExpired = expiresAt > 0 && expiresAt < now;

                    if (isOffline && !isExpired) {
                        // ✅ احتفظ — البيع لم يُزامَن بعد
                        preservedOffline++;
                        continue;
                    }

                    editor.remove(key);
                    removedServer++;
                } catch (Exception ex) {
                    editor.remove(key);
                }
            }

            // ═══════════════════════════════════════════════════
            // 2) إضافة القائمة الجديدة من السيرفر
            // ═══════════════════════════════════════════════════
            int added = 0;
            for (String raw : serverAllowedEpcs) {
                if (raw == null || raw.trim().isEmpty()) continue;
                String cleanEpc = normalize(raw);

                // ✅ لا تُطغِ على إدخال offline محفوظ
                String existingKey = KEY_ALLOWED_PREFIX + cleanEpc;
                String existingJson = prefs.getString(existingKey, null);
                if (existingJson != null) {
                    try {
                        JSONObject existing = new JSONObject(existingJson);
                        if (existing.optBoolean("is_offline", false)) {
                            // موجود كـ offline — اتركه
                            continue;
                        }
                    } catch (Exception ignored) {}
                }

                try {
                    JSONObject entry = new JSONObject();
                    entry.put("epc",        cleanEpc);
                    entry.put("sale_id",    0);
                    entry.put("branch_id",  0);
                    entry.put("is_offline", false);
                    entry.put("added_at",   now);
                    entry.put("expires_at", now + ALLOWED_LIST_TTL_MS);
                    entry.put("source",     "server_sync");

                    editor.putString(existingKey, entry.toString());
                    added++;
                } catch (Exception ignored) {}
            }

            // ═══════════════════════════════════════════════════
            // 3) سجّل وقت الجلب
            // ═══════════════════════════════════════════════════
            editor.putLong(KEY_LAST_CACHE_FETCH, now);
            editor.apply();

            Log.i(TAG, "✅ saveAllowedEpcs: " + added
                    + " from server, " + preservedOffline
                    + " offline preserved, " + removedServer
                    + " server entries removed");
        } catch (Exception e) {
            Log.e(TAG, "saveAllowedEpcs error: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════
    // قراءة
    // ═══════════════════════════════════════════════════════

    /**
     * يقرأ القائمة الحالية (إدخالات غير منتهية فقط).
     */
    public Set<String> getAllowedEpcs() {
        Set<String> result = new HashSet<>();
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
            Log.w(TAG, "getAllowedEpcs error: " + ex.getMessage());
        }
        return result;
    }

    /**
     * آخر وقت جلب ناجح من السيرفر — لفحص الطازجية.
     * يُستخدم من MiraSecureGateFragment لتفعيل isCacheFresh().
     */
    public long getLastCacheFetchTime() {
        return prefs.getLong(KEY_LAST_CACHE_FETCH, 0L);
    }

    /**
     * عدد القطع في القائمة (غير المنتهية).
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

    // ═══════════════════════════════════════════════════════
    // تنظيف
    // ═══════════════════════════════════════════════════════

    /**
     * ينظف الإدخالات المنتهية.
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

    // ═══════════════════════════════════════════════════════
    // سجل الأحداث المحلي (offline events)
    // ═══════════════════════════════════════════════════════

    public void saveOfflineEvent(String epc, boolean allowed,
                                 String itemTitle, String gateId) {
        JSONObject event = new JSONObject();
        try {
            event.put("epc",        epc != null ? normalize(epc) : "");
            event.put("allowed",    allowed);
            event.put("item_title", itemTitle);
            event.put("gate_id",    gateId);
            event.put("timestamp",  System.currentTimeMillis());

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

    // ═══════════════════════════════════════════════════════
    // حالة الاتصال
    // ═══════════════════════════════════════════════════════

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
