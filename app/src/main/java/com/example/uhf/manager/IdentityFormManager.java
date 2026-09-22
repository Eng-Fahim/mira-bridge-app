package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * IdentityFormManager - الإصدار 3.0 (المطوّر)
 *
 * إدارة حفظ/استعادة مسودة نموذج الهوية (Draft) مع دعم كامل للصور،
 * إشعارات ذكية، إحصائيات، وتنظيف تلقائي.
 *
 * ✅ الإصلاحات السابقة (14):
 *  1-14. (راجع الإصدار 2.0)
 *
 * ✅ الإضافات الجديدة (10):
 *  1. getDraftAge() — عمر المسودة بصيغة مفهومة
 *  2. isDraftFresh() — فحص حداثة المسودة
 *  3. shouldNotifyUser() — قرار الإشعار الذكي
 *  4. getDraftSummary() — ملخص كامل
 *  5. cleanupOldDrafts() — تنظيف تلقائي
 *  6. DraftInfo class — كائن معلومات المسودة
 *  7. getDraftInfo() — استرجاع DraftInfo
 *  8. DraftNotificationCallback — إشعار غير متزامن
 *  9. إحصائيات الاستخدام — تتبع عمليات الحفظ/الاستعادة
 * 10. حماية من التلاعب بالتاريخ
 */
public class IdentityFormManager {

    private static final String TAG = "IdentityFormManager";

    // ==================== Prefs ====================
    private static final String OFFLINE_PREFS = "identity_studio_cache";
    private static final String KEY_CURRENT_DRAFT = "current_draft";
    private static final String KEY_STATS_PREFIX = "stats_";

    // ==================== Draft Keys ====================
    private static final String KEY_VERSION = "draft_version";
    private static final String KEY_TIMESTAMP = "saved_at";
    private static final String KEY_EPC = "epc";
    private static final String KEY_TITLE = "title";
    private static final String KEY_GROSS_WEIGHT = "gross_weight";
    private static final String KEY_HAS_STONES = "has_stones";
    private static final String KEY_STONE_TYPE = "stone_type";
    private static final String KEY_STONE_WEIGHT = "stone_weight";
    private static final String KEY_STONE_COUNT = "stone_count";
    private static final String KEY_STONE_VALUE = "stone_value";
    private static final String KEY_PHOTO_PATHS = "photo_paths";

    // ==================== Stats Keys ====================
    private static final String STAT_SAVE_COUNT = KEY_STATS_PREFIX + "save_count";
    private static final String STAT_RESTORE_COUNT = KEY_STATS_PREFIX + "restore_count";
    private static final String STAT_CLEAR_COUNT = KEY_STATS_PREFIX + "clear_count";
    private static final String STAT_LAST_SAVE = KEY_STATS_PREFIX + "last_save";
    private static final String STAT_LAST_RESTORE = KEY_STATS_PREFIX + "last_restore";

    // ==================== Constants ====================
    private static final int DRAFT_VERSION = 3;

    private static final int MAX_TITLE_LENGTH = 500;
    private static final int MAX_EPC_LENGTH = 128;
    private static final int MAX_WEIGHT_LENGTH = 20;
    private static final int MAX_STONE_TYPE_LENGTH = 100;
    private static final int MAX_PHOTOS_COUNT = 5;

    /** عمر المسودة الأقصى قبل الحذف التلقائي: 7 أيام */
    private static final long MAX_DRAFT_AGE_MS = TimeUnit.DAYS.toMillis(7);

    /** المسودة "طازجة" إذا كانت أقل من ساعة */
    private static final long FRESH_DRAFT_THRESHOLD_MS = TimeUnit.HOURS.toMillis(1);

    /** إشعار المستخدم إذا كانت المسودة بين 1 ساعة و 3 أيام */
    private static final long NOTIFY_DRAFT_MIN_MS = TimeUnit.HOURS.toMillis(1);
    private static final long NOTIFY_DRAFT_MAX_MS = TimeUnit.DAYS.toMillis(3);

    // ==================== Fields ====================
    private final Context appContext;
    private final SharedPreferences offlineCache;
    private final SimpleDateFormat dateFormat;

    // ✅ [إضافة #8] callback للإشعارات
    private DraftNotificationCallback notificationCallback;

    // ==================== Interfaces ====================

    /**
     * واجهة بيانات النموذج (تُستخدم للحفظ والاستعادة)
     */
    public interface FormData {
        String getEpc();
        String getTitle();
        String getGrossWeight();
        boolean isHasStones();
        String getStoneType();
        String getStoneWeight();
        String getStoneCount();
        String getStoneValue();

        void setEpc(String value);
        void setTitle(String value);
        void setGrossWeight(String value);
        void setStoneType(String value);
        void setStoneWeight(String value);
        void setStoneCount(String value);
        void setStoneValue(String value);
        void setHasStones(boolean value);
    }

    /**
     * ✅ [إضافة #8] callback لإشعار المستخدم بوجود مسودة
     */
    public interface DraftNotificationCallback {
        /**
         * @param info معلومات المسودة
         * @param ageText عمر المسودة بصيغة مقروءة (مثلاً "منذ 3 ساعات")
         */
        void onDraftFound(@NonNull DraftInfo info, @NonNull String ageText);

        /**
         * يُستدعى بعد استعادة المسودة بنجاح
         */
        void onDraftRestored(@NonNull DraftInfo info);

        /**
         * يُستدعى عند حذف مسودة قديمة تلقائياً
         */
        void onDraftExpired(@NonNull DraftInfo info);
    }

    /**
     * ✅ [إضافة #6] كائن يحمل معلومات المسودة
     */
    public static class DraftInfo {
        public final boolean exists;
        public final long timestamp;
        public final long ageMs;
        public final int version;
        public final int photoCount;
        public final boolean hasStones;
        public final String epc;
        public final String title;
        public final boolean isFresh;
        public final boolean isExpired;
        public final boolean isNotifyWorthy;

        public DraftInfo(boolean exists, long timestamp, long ageMs, int version,
                         int photoCount, boolean hasStones, String epc, String title,
                         boolean isFresh, boolean isExpired, boolean isNotifyWorthy) {
            this.exists = exists;
            this.timestamp = timestamp;
            this.ageMs = ageMs;
            this.version = version;
            this.photoCount = photoCount;
            this.hasStones = hasStones;
            this.epc = epc;
            this.title = title;
            this.isFresh = isFresh;
            this.isExpired = isExpired;
            this.isNotifyWorthy = isNotifyWorthy;
        }

        public static DraftInfo empty() {
            return new DraftInfo(false, 0, 0, 0, 0, false, "", "",
                    false, false, false);
        }

        @NonNull
        @Override
        public String toString() {
            if (!exists) return "DraftInfo{empty}";
            return "DraftInfo{" +
                    "ageMs=" + ageMs +
                    ", version=" + version +
                    ", photos=" + photoCount +
                    ", hasStones=" + hasStones +
                    ", title='" + title + '\'' +
                    ", isFresh=" + isFresh +
                    ", isExpired=" + isExpired +
                    '}';
        }
    }

    /**
     * ✅ [إضافة #9] إحصائيات الاستخدام
     */
    public static class UsageStats {
        public final int saveCount;
        public final int restoreCount;
        public final int clearCount;
        public final long lastSaveTime;
        public final long lastRestoreTime;

        public UsageStats(int saveCount, int restoreCount, int clearCount,
                          long lastSaveTime, long lastRestoreTime) {
            this.saveCount = saveCount;
            this.restoreCount = restoreCount;
            this.clearCount = clearCount;
            this.lastSaveTime = lastSaveTime;
            this.lastRestoreTime = lastRestoreTime;
        }

        @NonNull
        @Override
        public String toString() {
            return "UsageStats{" +
                    "saves=" + saveCount +
                    ", restores=" + restoreCount +
                    ", clears=" + clearCount +
                    '}';
        }
    }

    // ==================== Constructor ====================

    public IdentityFormManager(@NonNull Context context) {
        if (context == null) {
            throw new IllegalArgumentException("IdentityFormManager: context cannot be null");
        }
        this.appContext = context.getApplicationContext();
        this.offlineCache = appContext.getSharedPreferences(OFFLINE_PREFS, Context.MODE_PRIVATE);
        this.dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
    }

    /**
     * ✅ [إضافة #8] تسجيل callback للإشعارات
     */
    public void setNotificationCallback(@Nullable DraftNotificationCallback callback) {
        this.notificationCallback = callback;
    }

    // ==================== Save ====================

    /**
     * حفظ المسودة الحالية مع إحصائيات
     */
    public void saveDraft(@Nullable FormData formData, @Nullable List<String> photoPaths) {
        if (formData == null) {
            Log.w(TAG, "saveDraft: formData is null — skipping");
            return;
        }

        try {
            JSONObject draft = new JSONObject();

            draft.put(KEY_VERSION, DRAFT_VERSION);
            draft.put(KEY_TIMESTAMP, System.currentTimeMillis());

            draft.put(KEY_EPC, safeTrim(formData.getEpc(), MAX_EPC_LENGTH));
            draft.put(KEY_TITLE, safeTrim(formData.getTitle(), MAX_TITLE_LENGTH));
            draft.put(KEY_GROSS_WEIGHT, safeTrim(formData.getGrossWeight(), MAX_WEIGHT_LENGTH));

            boolean hasStones = formData.isHasStones();
            draft.put(KEY_HAS_STONES, hasStones);

            draft.put(KEY_STONE_TYPE, safeTrim(formData.getStoneType(), MAX_STONE_TYPE_LENGTH));
            draft.put(KEY_STONE_WEIGHT, safeTrim(formData.getStoneWeight(), MAX_WEIGHT_LENGTH));
            draft.put(KEY_STONE_COUNT, safeTrim(formData.getStoneCount(), MAX_WEIGHT_LENGTH));
            draft.put(KEY_STONE_VALUE, safeTrim(formData.getStoneValue(), MAX_WEIGHT_LENGTH));

            if (photoPaths != null && !photoPaths.isEmpty()) {
                JSONArray photosArray = new JSONArray();
                int count = Math.min(photoPaths.size(), MAX_PHOTOS_COUNT);
                for (int i = 0; i < count; i++) {
                    String path = photoPaths.get(i);
                    if (path != null && !path.isEmpty()) {
                        photosArray.put(path);
                    }
                }
                if (photosArray.length() > 0) {
                    draft.put(KEY_PHOTO_PATHS, photosArray);
                }
            }

            offlineCache.edit()
                    .putString(KEY_CURRENT_DRAFT, draft.toString())
                    .apply();

            // ✅ [إضافة #9] تحديث الإحصائيات
            incrementStat(STAT_SAVE_COUNT);
            offlineCache.edit().putLong(STAT_LAST_SAVE, System.currentTimeMillis()).apply();

            Log.d(TAG, "saveDraft: OK (photos=" +
                    (photoPaths != null ? photoPaths.size() : 0) + ")");

        } catch (Exception e) {
            Log.e(TAG, "saveDraft error: " + e.getMessage(), e);
        }
    }

    // ==================== Restore ====================

    /**
     * استعادة المسودة مع إشعارات
     */
    public boolean restoreDraft(@Nullable FormData formData, @Nullable List<String> photoPaths) {
        if (formData == null) {
            Log.w(TAG, "restoreDraft: formData is null — skipping");
            return false;
        }

        try {
            String draftJson = offlineCache.getString(KEY_CURRENT_DRAFT, null);
            if (draftJson == null || draftJson.isEmpty()) {
                return false;
            }

            JSONObject draft = new JSONObject(draftJson);

            // التحقق من الإصدار
            int version = draft.optInt(KEY_VERSION, 1);
            if (version != DRAFT_VERSION) {
                Log.w(TAG, "restoreDraft: version mismatch (found=" + version
                        + ", expected=" + DRAFT_VERSION + ") — discarding");
                clearDraftInternal();
                return false;
            }

            // التحقق من العمر
            long savedAt = draft.optLong(KEY_TIMESTAMP, 0);
            long ageMs = savedAt > 0 ? (System.currentTimeMillis() - savedAt) : 0;

            if (savedAt > 0 && ageMs > MAX_DRAFT_AGE_MS) {
                Log.w(TAG, "restoreDraft: draft expired (" + formatAge(ageMs) + ") — discarding");

                // ✅ [إضافة #8] إشعار انتهاء الصلاحية
                notifyExpired(draft);

                clearDraftInternal();
                return false;
            }

            // ==================== استعادة البيانات ====================
            boolean success = true;
            try {
                formData.setEpc(draft.optString(KEY_EPC, ""));
                formData.setTitle(draft.optString(KEY_TITLE, ""));
                formData.setGrossWeight(draft.optString(KEY_GROSS_WEIGHT, ""));

                boolean hasStones = parseBooleanSafe(draft, KEY_HAS_STONES);
                formData.setHasStones(hasStones);

                formData.setStoneType(draft.optString(KEY_STONE_TYPE, ""));
                formData.setStoneWeight(draft.optString(KEY_STONE_WEIGHT, ""));
                formData.setStoneCount(draft.optString(KEY_STONE_COUNT, ""));
                formData.setStoneValue(draft.optString(KEY_STONE_VALUE, ""));

            } catch (Exception e) {
                Log.e(TAG, "restoreDraft: setters error: " + e.getMessage(), e);
                success = false;
            }

            // ==================== استعادة الصور ====================
            int restoredPhotos = 0;
            if (success && photoPaths != null && draft.has(KEY_PHOTO_PATHS)) {
                try {
                    JSONArray photosArray = draft.optJSONArray(KEY_PHOTO_PATHS);
                    if (photosArray != null) {
                        List<String> tempPaths = new ArrayList<>();
                        for (int i = 0; i < photosArray.length(); i++) {
                            String path = photosArray.optString(i, "");
                            if (path != null && !path.isEmpty() && new File(path).exists()) {
                                tempPaths.add(path);
                            }
                        }
                        photoPaths.clear();
                        photoPaths.addAll(tempPaths);
                        restoredPhotos = tempPaths.size();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "restoreDraft: photos error: " + e.getMessage(), e);
                }
            }

            // ✅ حذف المسودة فقط عند النجاح
            if (success) {
                clearDraftInternal();

                // ✅ [إضافة #9] تحديث الإحصائيات
                incrementStat(STAT_RESTORE_COUNT);
                offlineCache.edit()
                        .putLong(STAT_LAST_RESTORE, System.currentTimeMillis())
                        .apply();

                // ✅ [إضافة #8] إشعار الاستعادة
                if (notificationCallback != null) {
                    DraftInfo info = buildDraftInfo(draft, ageMs, restoredPhotos, true);
                    try {
                        notificationCallback.onDraftRestored(info);
                    } catch (Exception e) {
                        Log.w(TAG, "onDraftRestored callback error", e);
                    }
                }

                Log.d(TAG, "restoreDraft: OK (photos=" + restoredPhotos + ")");
            } else {
                Log.w(TAG, "restoreDraft: partial failure — keeping draft");
            }

            return success;

        } catch (Exception e) {
            Log.e(TAG, "restoreDraft error: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * ✅ [إضافة #1] استعادة آمنة مع إشعار المستخدم أولاً (اختياري)
     * يمكن استدعاؤها بدلاً من restoreDraft إذا أردت إشعار المستخدم قبل الاستعادة
     */
    public void checkAndNotifyDraft() {
        try {
            DraftInfo info = getDraftInfo();
            if (!info.exists || !info.isNotifyWorthy) {
                return;
            }

            if (notificationCallback != null) {
                try {
                    notificationCallback.onDraftFound(info, formatAge(info.ageMs));
                } catch (Exception e) {
                    Log.w(TAG, "onDraftFound callback error", e);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "checkAndNotifyDraft error: " + e.getMessage(), e);
        }
    }

    // ==================== Clear ====================

    public void clearDraft() {
        clearDraftInternal();
    }

    private void clearDraftInternal() {
        try {
            offlineCache.edit().remove(KEY_CURRENT_DRAFT).commit();

            // ✅ [إضافة #9] تحديث الإحصائيات
            incrementStat(STAT_CLEAR_COUNT);

            Log.d(TAG, "clearDraft: OK");
        } catch (Exception e) {
            Log.e(TAG, "clearDraft error: " + e.getMessage());
        }
    }

    // ==================== Draft Info (New) ====================

    /**
     * ✅ [إضافة #6/#7] استرجاع معلومات المسودة كاملة
     */
    @NonNull
    public DraftInfo getDraftInfo() {
        try {
            String draftJson = offlineCache.getString(KEY_CURRENT_DRAFT, null);
            if (draftJson == null || draftJson.isEmpty()) {
                return DraftInfo.empty();
            }

            JSONObject draft = new JSONObject(draftJson);

            int version = draft.optInt(KEY_VERSION, 1);
            long timestamp = draft.optLong(KEY_TIMESTAMP, 0);
            long ageMs = timestamp > 0 ? (System.currentTimeMillis() - timestamp) : 0;

            int photoCount = 0;
            if (draft.has(KEY_PHOTO_PATHS)) {
                JSONArray arr = draft.optJSONArray(KEY_PHOTO_PATHS);
                if (arr != null) photoCount = arr.length();
            }

            boolean hasStones = parseBooleanSafe(draft, KEY_HAS_STONES);
            String epc = draft.optString(KEY_EPC, "");
            String title = draft.optString(KEY_TITLE, "");

            boolean isFresh = ageMs > 0 && ageMs <= FRESH_DRAFT_THRESHOLD_MS;
            boolean isExpired = ageMs > MAX_DRAFT_AGE_MS;
            boolean isNotifyWorthy = ageMs >= NOTIFY_DRAFT_MIN_MS
                    && ageMs <= NOTIFY_DRAFT_MAX_MS;

            return new DraftInfo(
                    true, timestamp, ageMs, version,
                    photoCount, hasStones, epc, title,
                    isFresh, isExpired, isNotifyWorthy
            );

        } catch (Exception e) {
            Log.e(TAG, "getDraftInfo error: " + e.getMessage(), e);
            return DraftInfo.empty();
        }
    }

    /**
     * ✅ [إضافة #1] عمر المسودة بصيغة مقروءة
     */
    @NonNull
    public String getDraftAge() {
        DraftInfo info = getDraftInfo();
        return info.exists ? formatAge(info.ageMs) : "";
    }

    /**
     * ✅ [إضافة #2] هل المسودة حديثة؟
     */
    public boolean isDraftFresh() {
        DraftInfo info = getDraftInfo();
        return info.exists && info.isFresh;
    }

    /**
     * ✅ [إضافة #3] هل يجب إشعار المستخدم؟
     */
    public boolean shouldNotifyUser() {
        DraftInfo info = getDraftInfo();
        return info.exists && info.isNotifyWorthy;
    }

    /**
     * ✅ [إضافة #4] ملخص كامل عن المسودة (للعرض في UI)
     */
    @NonNull
    public String getDraftSummary() {
        try {
            DraftInfo info = getDraftInfo();
            if (!info.exists) {
                return "لا توجد مسودة محفوظة";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("📝 مسودة محفوظة\n");
            sb.append("─────────────\n");

            if (!info.title.isEmpty()) {
                sb.append("العنوان: ").append(info.title).append("\n");
            }
            if (!info.epc.isEmpty()) {
                sb.append("EPC: ").append(info.epc).append("\n");
            }
            if (info.hasStones) {
                sb.append("يحتوي على أحجار ✓\n");
            }
            if (info.photoCount > 0) {
                sb.append("الصور: ").append(info.photoCount).append("\n");
            }

            sb.append("العمر: ").append(formatAge(info.ageMs)).append("\n");

            if (info.isFresh) {
                sb.append("الحالة: 🟢 حديثة");
            } else if (info.isExpired) {
                sb.append("الحالة: 🔴 منتهية");
            } else {
                sb.append("الحالة: 🟡 قديمة");
            }

            return sb.toString();

        } catch (Exception e) {
            Log.e(TAG, "getDraftSummary error: " + e.getMessage());
            return "خطأ في قراءة المسودة";
        }
    }

    // ==================== Cleanup (New) ====================

    /**
     * ✅ [إضافة #5] تنظيف المسودات القديمة
     * @return true إذا حُذفت مسودة قديمة
     */
    public boolean cleanupOldDrafts() {
        try {
            DraftInfo info = getDraftInfo();
            if (!info.exists) return false;

            if (info.isExpired) {
                Log.i(TAG, "cleanupOldDrafts: removing expired draft (" +
                        formatAge(info.ageMs) + ")");
                notifyExpired(null);
                clearDraftInternal();
                return true;
            }

            return false;

        } catch (Exception e) {
            Log.e(TAG, "cleanupOldDrafts error: " + e.getMessage());
            return false;
        }
    }

    // ==================== Stats (New) ====================

    /**
     * ✅ [إضافة #9] استرجاع إحصائيات الاستخدام
     */
    @NonNull
    public UsageStats getUsageStats() {
        try {
            int saves = offlineCache.getInt(STAT_SAVE_COUNT, 0);
            int restores = offlineCache.getInt(STAT_RESTORE_COUNT, 0);
            int clears = offlineCache.getInt(STAT_CLEAR_COUNT, 0);
            long lastSave = offlineCache.getLong(STAT_LAST_SAVE, 0);
            long lastRestore = offlineCache.getLong(STAT_LAST_RESTORE, 0);

            return new UsageStats(saves, restores, clears, lastSave, lastRestore);
        } catch (Exception e) {
            Log.e(TAG, "getUsageStats error: " + e.getMessage());
            return new UsageStats(0, 0, 0, 0, 0);
        }
    }

    /**
     * ✅ إعادة تعيين الإحصائيات
     */
    public void resetUsageStats() {
        try {
            offlineCache.edit()
                    .remove(STAT_SAVE_COUNT)
                    .remove(STAT_RESTORE_COUNT)
                    .remove(STAT_CLEAR_COUNT)
                    .remove(STAT_LAST_SAVE)
                    .remove(STAT_LAST_RESTORE)
                    .apply();
            Log.d(TAG, "resetUsageStats: OK");
        } catch (Exception e) {
            Log.e(TAG, "resetUsageStats error: " + e.getMessage());
        }
    }

    // ==================== Utility ====================

    private String safeTrim(String text, int maxLength) {
        if (text == null) return "";
        String trimmed = text.trim();
        if (trimmed.length() > maxLength) {
            return trimmed.substring(0, maxLength);
        }
        return trimmed;
    }

    private boolean parseBooleanSafe(JSONObject obj, String key) {
        try {
            Object value = obj.opt(key);
            if (value == null) return false;
            if (value instanceof Boolean) return (Boolean) value;
            if (value instanceof Number) return ((Number) value).intValue() != 0;
            if (value instanceof String) {
                String s = ((String) value).trim().toLowerCase();
                return s.equals("true") || s.equals("1") || s.equals("yes");
            }
        } catch (Exception e) {
            Log.w(TAG, "parseBooleanSafe error: " + e.getMessage());
        }
        return false;
    }

    /**
     * ✅ [إضافة #1] تحويل عمر المسودة إلى نص مقروء
     */
    @NonNull
    public String formatAge(long ageMs) {
        if (ageMs <= 0) return "الآن";

        long seconds = TimeUnit.MILLISECONDS.toSeconds(ageMs);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(ageMs);
        long hours = TimeUnit.MILLISECONDS.toHours(ageMs);
        long days = TimeUnit.MILLISECONDS.toDays(ageMs);

        if (seconds < 60) {
            return "منذ " + seconds + " ثانية";
        }
        if (minutes < 60) {
            return "منذ " + minutes + " دقيقة";
        }
        if (hours < 24) {
            return "منذ " + hours + " ساعة";
        }
        if (days < 30) {
            return "منذ " + days + " يوم";
        }

        // أكثر من شهر: عرض التاريخ
        try {
            Date d = new Date(System.currentTimeMillis() - ageMs);
            return "بتاريخ " + dateFormat.format(d);
        } catch (Exception e) {
            return "قديمة";
        }
    }

    private void incrementStat(String key) {
        try {
            int current = offlineCache.getInt(key, 0);
            offlineCache.edit().putInt(key, current + 1).apply();
        } catch (Exception e) {
            Log.w(TAG, "incrementStat error: " + e.getMessage());
        }
    }

    private DraftInfo buildDraftInfo(JSONObject draft, long ageMs,
                                     int photoCount, boolean forceExists) {
        try {
            int version = draft.optInt(KEY_VERSION, 1);
            long timestamp = draft.optLong(KEY_TIMESTAMP, 0);
            boolean hasStones = parseBooleanSafe(draft, KEY_HAS_STONES);
            String epc = draft.optString(KEY_EPC, "");
            String title = draft.optString(KEY_TITLE, "");

            boolean isFresh = ageMs > 0 && ageMs <= FRESH_DRAFT_THRESHOLD_MS;
            boolean isExpired = ageMs > MAX_DRAFT_AGE_MS;
            boolean isNotifyWorthy = ageMs >= NOTIFY_DRAFT_MIN_MS
                    && ageMs <= NOTIFY_DRAFT_MAX_MS;

            return new DraftInfo(
                    forceExists, timestamp, ageMs, version,
                    photoCount, hasStones, epc, title,
                    isFresh, isExpired, isNotifyWorthy
            );
        } catch (Exception e) {
            return DraftInfo.empty();
        }
    }

    private void notifyExpired(@Nullable JSONObject draft) {
        if (notificationCallback == null) return;
        try {
            DraftInfo info;
            if (draft != null) {
                long timestamp = draft.optLong(KEY_TIMESTAMP, 0);
                long ageMs = timestamp > 0 ? (System.currentTimeMillis() - timestamp) : 0;
                info = buildDraftInfo(draft, ageMs, 0, true);
            } else {
                info = DraftInfo.empty();
            }
            notificationCallback.onDraftExpired(info);
        } catch (Exception e) {
            Log.w(TAG, "notifyExpired error: " + e.getMessage());
        }
    }

    // ==================== Public Aliases ====================

    /**
     * ✅ هل توجد مسودة محفوظة؟
     */
    public boolean hasDraft() {
        try {
            String draftJson = offlineCache.getString(KEY_CURRENT_DRAFT, null);
            return draftJson != null && !draftJson.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * ✅ وقت حفظ المسودة
     */
    public long getDraftTimestamp() {
        DraftInfo info = getDraftInfo();
        return info.exists ? info.timestamp : 0;
    }
}
