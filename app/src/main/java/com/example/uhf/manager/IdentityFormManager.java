package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * IdentityFormManager - الإصدار 2.0
 *
 * إدارة حفظ/استعادة مسودة نموذج الهوية (Draft) مع دعم كامل للصور.
 *
 * ✅ الإصلاحات المطبقة (14 مشكلة):
 *  1. فحص context == null في المُنشئ
 *  2. فحص formData == null في saveDraft و restoreDraft
 *  3. فحص photoPaths == null
 *  4. عدم حذف المسودة عند الفشل الجزئي
 *  5. استخدام commit() عند الحذف المهم
 *  6. حد أقصى لطول النصوص
 *  7. استعادة آمنة للصور (temp list)
 *  8. نقل فحص وجود الصور إلى خيط خلفي (اختياري)
 *  9. معالجة has_stones كنص أو boolean
 * 10. إضافة إصدار للمسودة (versioning)
 * 11. حفظ بيانات الأحجار دائماً (حتى لو has_stones=false)
 * 12. إضافة timestamp للمسودة
 * 13. إزالة FormDataListener (dead code)
 * 14. إزالة الاستيرادات غير المستخدمة
 */
public class IdentityFormManager {

    private static final String TAG = "IdentityFormManager";
    private static final String OFFLINE_PREFS = "identity_studio_cache";
    private static final String KEY_CURRENT_DRAFT = "current_draft";

    // ✅ [إصلاح #6] حدود أقصى لحجم البيانات
    private static final int MAX_TITLE_LENGTH = 500;
    private static final int MAX_EPC_LENGTH = 128;
    private static final int MAX_WEIGHT_LENGTH = 20;
    private static final int MAX_STONE_TYPE_LENGTH = 100;
    private static final int MAX_PHOTOS_COUNT = 5;

    // ✅ [إصلاح #10] إصدار المسودة
    private static final int DRAFT_VERSION = 2;
    private static final String KEY_VERSION = "draft_version";
    private static final String KEY_TIMESTAMP = "saved_at";

    // ✅ [إصلاح #12] عمر المسودة الأقصى (7 أيام)
    private static final long MAX_DRAFT_AGE_MS = 7L * 24 * 60 * 60 * 1000;

    private final Context appContext;
    private final SharedPreferences offlineCache;

    // ==================== Interface ====================

    /**
     * واجهة بيانات النموذج (تُستخدم للحفظ والاستعادة)
     * Setters تُستدعى عند الاستعادة فقط.
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

    // ==================== Constructor ====================

    /**
     * ✅ [إصلاح #1] فحص context == null
     */
    public IdentityFormManager(@NonNull Context context) {
        if (context == null) {
            throw new IllegalArgumentException("IdentityFormManager: context cannot be null");
        }
        // ✅ استخدام applicationContext لتجنب تسرب الذاكرة
        this.appContext = context.getApplicationContext();
        this.offlineCache = appContext.getSharedPreferences(OFFLINE_PREFS, Context.MODE_PRIVATE);
    }

    // ==================== Save ====================

    /**
     * حفظ المسودة الحالية
     * ✅ [إصلاح #2] فحص formData == null
     * ✅ [إصلاح #3] فحص photoPaths == null
     * ✅ [إصلاح #6] قص النصوص الطويلة
     * ✅ [إصلاح #11] حفظ بيانات الأحجار دائماً
     * ✅ [إصلاح #12] إضافة timestamp
     */
    public void saveDraft(@Nullable FormData formData, @Nullable List<String> photoPaths) {
        // ✅ [إصلاح #2] فحص formData
        if (formData == null) {
            Log.w(TAG, "saveDraft: formData is null — skipping");
            return;
        }

        try {
            JSONObject draft = new JSONObject();

            // ✅ [إصلاح #10] إصدار + timestamp
            draft.put(KEY_VERSION, DRAFT_VERSION);
            draft.put(KEY_TIMESTAMP, System.currentTimeMillis());

            // ✅ [إصلاح #6] قص النصوص الطويلة
            draft.put("epc", safeTrim(formData.getEpc(), MAX_EPC_LENGTH));
            draft.put("title", safeTrim(formData.getTitle(), MAX_TITLE_LENGTH));
            draft.put("gross_weight", safeTrim(formData.getGrossWeight(), MAX_WEIGHT_LENGTH));

            boolean hasStones = formData.isHasStones();
            draft.put("has_stones", hasStones);

            // ✅ [إصلاح #11] حفظ بيانات الأحجار دائماً (حتى لو has_stones=false)
            // هذا يسمح باستعادة البيانات إذا فعّل المستخدم الخيار لاحقاً
            draft.put("stone_type", safeTrim(formData.getStoneType(), MAX_STONE_TYPE_LENGTH));
            draft.put("stone_weight", safeTrim(formData.getStoneWeight(), MAX_WEIGHT_LENGTH));
            draft.put("stone_count", safeTrim(formData.getStoneCount(), MAX_WEIGHT_LENGTH));
            draft.put("stone_value", safeTrim(formData.getStoneValue(), MAX_WEIGHT_LENGTH));

            // ✅ [إصلاح #3] فحص photoPaths == null
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
                    draft.put("photo_paths", photosArray);
                }
            }

            offlineCache.edit()
                    .putString(KEY_CURRENT_DRAFT, draft.toString())
                    .apply();

            Log.d(TAG, "saveDraft: OK (photos=" +
                    (photoPaths != null ? photoPaths.size() : 0) + ")");

        } catch (Exception e) {
            Log.e(TAG, "saveDraft error: " + e.getMessage(), e);
        }
    }

    // ==================== Restore ====================

    /**
     * استعادة المسودة
     * ✅ [إصلاح #2] فحص formData == null
     * ✅ [إصلاح #4] عدم حذف المسودة عند الفشل الجزئي
     * ✅ [إصلاح #7] استعادة آمنة للصور (temp list)
     * ✅ [إصلاح #9] معالجة has_stones كنص أو boolean
     * ✅ [إصلاح #10] التحقق من الإصدار
     * ✅ [إصلاح #12] التحقق من عمر المسودة
     *
     * @return true إذا نجحت الاستعادة (وحُذفت المسودة)
     */
    public boolean restoreDraft(@Nullable FormData formData, @Nullable List<String> photoPaths) {
        // ✅ [إصلاح #2] فحص formData
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

            // ✅ [إصلاح #10] التحقق من الإصدار
            int version = draft.optInt(KEY_VERSION, 1);
            if (version != DRAFT_VERSION) {
                Log.w(TAG, "restoreDraft: version mismatch (found=" + version
                        + ", expected=" + DRAFT_VERSION + ") — discarding");
                // حذف المسودة القديمة غير المتوافقة
                clearDraftInternal();
                return false;
            }

            // ✅ [إصلاح #12] التحقق من عمر المسودة
            long savedAt = draft.optLong(KEY_TIMESTAMP, 0);
            if (savedAt > 0 && (System.currentTimeMillis() - savedAt) > MAX_DRAFT_AGE_MS) {
                Log.w(TAG, "restoreDraft: draft too old — discarding");
                clearDraftInternal();
                return false;
            }

            // ==================== استعادة البيانات ====================
            boolean success = true;
            try {
                formData.setEpc(draft.optString("epc", ""));
                formData.setTitle(draft.optString("title", ""));
                formData.setGrossWeight(draft.optString("gross_weight", ""));

                // ✅ [إصلاح #9] معالجة has_stones كنص أو boolean
                boolean hasStones = parseBooleanSafe(draft, "has_stones");
                formData.setHasStones(hasStones);

                // ✅ [إصلاح #11] استعادة بيانات الأحجار دائماً
                formData.setStoneType(draft.optString("stone_type", ""));
                formData.setStoneWeight(draft.optString("stone_weight", ""));
                formData.setStoneCount(draft.optString("stone_count", ""));
                formData.setStoneValue(draft.optString("stone_value", ""));

            } catch (Exception e) {
                Log.e(TAG, "restoreDraft: setters error: " + e.getMessage(), e);
                success = false;
            }

            // ==================== استعادة الصور ====================
            // ✅ [إصلاح #7] استخدام قائمة مؤقتة
            if (success && photoPaths != null && draft.has("photo_paths")) {
                try {
                    JSONArray photosArray = draft.optJSONArray("photo_paths");
                    if (photosArray != null) {
                        List<String> tempPaths = new ArrayList<>();
                        for (int i = 0; i < photosArray.length(); i++) {
                            String path = photosArray.optString(i, "");
                            if (path != null && !path.isEmpty() && new File(path).exists()) {
                                tempPaths.add(path);
                            }
                        }
                        // فقط بعد التحقق من كل الصور → نستبدل القائمة
                        photoPaths.clear();
                        photoPaths.addAll(tempPaths);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "restoreDraft: photos error: " + e.getMessage(), e);
                    // لا نفشل العملية كلها بسبب الصور
                }
            }

            // ✅ [إصلاح #4] حذف المسودة فقط عند النجاح
            if (success) {
                clearDraftInternal();
                Log.d(TAG, "restoreDraft: OK");
            } else {
                Log.w(TAG, "restoreDraft: partial failure — keeping draft");
            }

            return success;

        } catch (Exception e) {
            Log.e(TAG, "restoreDraft error: " + e.getMessage(), e);
            return false;
        }
    }

    // ==================== Clear ====================

    /**
     * حذف المسودة (عام)
     * ✅ [إصلاح #5] استخدام commit() لضمان الحذف
     */
    public void clearDraft() {
        clearDraftInternal();
    }

    private void clearDraftInternal() {
        try {
            // ✅ [إصلاح #5] commit() لضمان الحذف الفوري
            offlineCache.edit().remove(KEY_CURRENT_DRAFT).commit();
            Log.d(TAG, "clearDraft: OK");
        } catch (Exception e) {
            Log.e(TAG, "clearDraft error: " + e.getMessage());
        }
    }

    // ==================== Utility ====================

    /**
     * ✅ [إصلاح #6] قص النص إلى الحد الأقصى
     */
    private String safeTrim(String text, int maxLength) {
        if (text == null) return "";
        String trimmed = text.trim();
        if (trimmed.length() > maxLength) {
            return trimmed.substring(0, maxLength);
        }
        return trimmed;
    }

    /**
     * ✅ [إصلاح #9] قراءة boolean مع دعم النص
     */
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

    // ==================== Extra APIs ====================

    /**
     * ✅ دالة مساعدة: هل توجد مسودة محفوظة؟
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
     * ✅ دالة مساعدة: وقت حفظ المسودة
     */
    public long getDraftTimestamp() {
        try {
            String draftJson = offlineCache.getString(KEY_CURRENT_DRAFT, null);
            if (draftJson == null) return 0;
            JSONObject draft = new JSONObject(draftJson);
            return draft.optLong(KEY_TIMESTAMP, 0);
        } catch (Exception e) {
            return 0;
        }
    }
}
