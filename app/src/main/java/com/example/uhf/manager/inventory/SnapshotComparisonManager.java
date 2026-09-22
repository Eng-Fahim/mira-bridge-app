package com.example.uhf.manager.inventory;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.uhf.model.InventorySnapshot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ✅ SnapshotComparisonManager v1.0
 *
 * مقارنة بين لقطتي مخزون:
 * - اكتشاف القطع المضافة
 * - اكتشاف القطع المفقودة
 * - حساب الفروقات (عدد/وزن/قيمة)
 * - تقرير مفصل
 */
public class SnapshotComparisonManager {

    // ==================== البيانات ====================

    public static class ComparisonResult {
        public InventorySnapshot snapshot1;  // الأقدم
        public InventorySnapshot snapshot2;  // الأحدث

        // ✅ فروقات إجمالية
        public int itemsDiff;
        public double weightDiff;
        public double valueDiff;

        // ✅ القطع
        public List<String> addedEpcs = new ArrayList<>();
        public List<String> removedEpcs = new ArrayList<>();
        public List<String> unchangedEpcs = new ArrayList<>();

        // ✅ تفاصيل
        public boolean isSameTotal;
        public String summary;
        public long timeSpanMs;

        public boolean hasChanges() {
            return !addedEpcs.isEmpty() || !removedEpcs.isEmpty() 
                    || itemsDiff != 0;
        }

        public int getTotalChanges() {
            return addedEpcs.size() + removedEpcs.size();
        }
    }

    // ==================== MAIN ====================

    /**
     * ✅ مقارنة لقطتين
     *
     * @param s1 اللقطة الأولى (الأقدم)
     * @param s2 اللقطة الثانية (الأحدث)
     * @param isArabic للغة
     */
    @Nullable
    public static ComparisonResult compare(@Nullable InventorySnapshot s1,
                                             @Nullable InventorySnapshot s2,
                                             boolean isArabic) {
        if (s1 == null || s2 == null) return null;

        try {
            ComparisonResult result = new ComparisonResult();

            // ✅ رتّب حسب الوقت (الأقدم أولاً)
            if (s1.getTimestamp() <= s2.getTimestamp()) {
                result.snapshot1 = s1;
                result.snapshot2 = s2;
            } else {
                result.snapshot1 = s2;
                result.snapshot2 = s1;
            }

            // ✅ الفروقات الإجمالية
            result.itemsDiff = result.snapshot2.getTotalItems() 
                    - result.snapshot1.getTotalItems();
            result.weightDiff = result.snapshot2.getTotalWeight() 
                    - result.snapshot1.getTotalWeight();
            result.valueDiff = result.snapshot2.getTotalValue() 
                    - result.snapshot1.getTotalValue();

            result.isSameTotal = (result.itemsDiff == 0 
                    && Math.abs(result.weightDiff) < 0.01);

            result.timeSpanMs = result.snapshot2.getTimestamp() 
                    - result.snapshot1.getTimestamp();

            // ✅ قائمة EPCs
            Set<String> epcs1 = toSet(result.snapshot1.getEpcs());
            Set<String> epcs2 = toSet(result.snapshot2.getEpcs());

            // ✅ القطع المضافة (في 2 وليس في 1)
            for (String epc : epcs2) {
                if (!epcs1.contains(epc)) {
                    result.addedEpcs.add(epc);
                } else {
                    result.unchangedEpcs.add(epc);
                }
            }

            // ✅ القطع المفقودة (في 1 وليس في 2)
            for (String epc : epcs1) {
                if (!epcs2.contains(epc)) {
                    result.removedEpcs.add(epc);
                }
            }

            // ✅ ملخص نصي
            result.summary = buildSummary(result, isArabic);

            return result;

        } catch (Exception e) {
            return null;
        }
    }

    private static Set<String> toSet(JSONArray arr) {
        Set<String> set = new HashSet<>();
        if (arr == null) return set;
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "");
            if (!s.isEmpty()) set.add(s);
        }
        return set;
    }

    private static String buildSummary(ComparisonResult r, boolean isArabic) {
        StringBuilder sb = new StringBuilder();

        // ✅ عدد القطع
        if (r.itemsDiff > 0) {
            sb.append(isArabic 
                    ? "📈 زيادة " + r.itemsDiff + " قطعة"
                    : "📈 " + r.itemsDiff + " items added");
        } else if (r.itemsDiff < 0) {
            sb.append(isArabic 
                    ? "📉 نقص " + Math.abs(r.itemsDiff) + " قطعة"
                    : "📉 " + Math.abs(r.itemsDiff) + " items removed");
        } else {
            sb.append(isArabic ? "➡️ عدد القطع ثابت" : "➡️ Same item count");
        }

        sb.append("\n");

        // ✅ الوزن
        if (Math.abs(r.weightDiff) < 0.01) {
            sb.append(isArabic ? "⚖️ الوزن ثابت" : "⚖️ Same weight");
        } else if (r.weightDiff > 0) {
            sb.append(String.format(java.util.Locale.US,
                    isArabic ? "⚖️ +%.2f g" : "⚖️ +%.2f g", r.weightDiff));
        } else {
            sb.append(String.format(java.util.Locale.US,
                    isArabic ? "⚖️ %.2f g" : "⚖️ %.2f g", r.weightDiff));
        }

        sb.append("\n");

        // ✅ القيمة
        if (Math.abs(r.valueDiff) < 0.01) {
            sb.append(isArabic ? "💰 القيمة ثابتة" : "💰 Same value");
        } else {
            sb.append(String.format(java.util.Locale.US,
                    isArabic ? "💰 %+,.2f USD" : "💰 %+,.2f USD", r.valueDiff));
        }

        // ✅ التغييرات في EPCs
        if (r.hasChanges()) {
            sb.append("\n\n");
            sb.append(isArabic ? "🔍 التغييرات التفصيلية:" : "🔍 Detailed changes:");
            if (!r.addedEpcs.isEmpty()) {
                sb.append("\n");
                sb.append(isArabic 
                        ? "🆕 قطع جديدة: " + r.addedEpcs.size()
                        : "🆕 New items: " + r.addedEpcs.size());
            }
            if (!r.removedEpcs.isEmpty()) {
                sb.append("\n");
                sb.append(isArabic 
                        ? "❌ قطع مفقودة: " + r.removedEpcs.size()
                        : "❌ Missing items: " + r.removedEpcs.size());
            }
        }

        return sb.toString();
    }

    // ==================== تقرير مفصل ====================

    /**
     * ✅ بناء تقرير مفصل HTML/نصي
     */
    @NonNull
    public static String buildDetailedReport(@Nullable ComparisonResult result,
                                               boolean isArabic) {
        if (result == null) {
            return isArabic ? "لا توجد بيانات" : "No data";
        }

        StringBuilder sb = new StringBuilder();

        // ✅ الرأس
        sb.append("═══════════════════════════════\n");
        sb.append(isArabic ? "📊 تقرير مقارنة لقطتين\n" 
                : "📊 Snapshot Comparison Report\n");
        sb.append("═══════════════════════════════\n\n");

        // ✅ التفاصيل الزمنية
        sb.append(isArabic ? "🕐 الفترة الزمنية:\n" : "🕐 Time span:\n");
        sb.append("  • ").append(isArabic ? "من: " : "From: ")
                .append(result.snapshot1.getFormattedDate()).append("\n");
        sb.append("  • ").append(isArabic ? "إلى: " : "To: ")
                .append(result.snapshot2.getFormattedDate()).append("\n");
        sb.append("  • ").append(isArabic ? "المدة: " : "Duration: ")
                .append(formatDuration(result.timeSpanMs, isArabic)).append("\n\n");

        // ✅ اللقطة الأولى
        sb.append(isArabic ? "📍 اللقطة الأولى:\n" : "📍 First snapshot:\n");
        appendSnapshotInfo(sb, result.snapshot1, isArabic);
        sb.append("\n");

        // ✅ اللقطة الثانية
        sb.append(isArabic ? "📍 اللقطة الثانية:\n" : "📍 Second snapshot:\n");
        appendSnapshotInfo(sb, result.snapshot2, isArabic);
        sb.append("\n");

        // ✅ الفروقات
        sb.append("═══════════════════════════════\n");
        sb.append(isArabic ? "📊 الفروقات:\n" : "📊 Differences:\n");
        sb.append("═══════════════════════════════\n");

        // ✅ عدد
        sb.append("  • ").append(isArabic ? "العدد: " : "Items: ")
                .append(formatDiff(result.itemsDiff, "قطعة", "items", isArabic))
                .append("\n");

        // ✅ وزن
        sb.append("  • ").append(isArabic ? "الوزن: " : "Weight: ")
                .append(String.format(java.util.Locale.US, 
                        "%+.2f g", result.weightDiff)).append("\n");

        // ✅ قيمة
        sb.append("  • ").append(isArabic ? "القيمة: " : "Value: ")
                .append(String.format(java.util.Locale.US, 
                        "%+,.2f USD", result.valueDiff)).append("\n\n");

        // ✅ التغييرات
        if (result.hasChanges()) {
            sb.append("═══════════════════════════════\n");
            sb.append(isArabic ? "🔍 التغييرات التفصيلية:\n" 
                    : "🔍 Detailed Changes:\n");
            sb.append("═══════════════════════════════\n");

            if (!result.addedEpcs.isEmpty()) {
                sb.append("\n");
                sb.append(isArabic 
                        ? "🆕 قطع مضافة (" + result.addedEpcs.size() + "):\n"
                        : "🆕 Added Items (" + result.addedEpcs.size() + "):\n");
                int limit = Math.min(result.addedEpcs.size(), 10);
                for (int i = 0; i < limit; i++) {
                    sb.append("  + ").append(result.addedEpcs.get(i)).append("\n");
                }
                if (result.addedEpcs.size() > limit) {
                    sb.append("  ... +").append(result.addedEpcs.size() - limit)
                            .append(isArabic ? " أخرى" : " more").append("\n");
                }
            }

            if (!result.removedEpcs.isEmpty()) {
                sb.append("\n");
                sb.append(isArabic 
                        ? "❌ قطع مفقودة (" + result.removedEpcs.size() + "):\n"
                        : "❌ Missing Items (" + result.removedEpcs.size() + "):\n");
                int limit = Math.min(result.removedEpcs.size(), 10);
                for (int i = 0; i < limit; i++) {
                    sb.append("  − ").append(result.removedEpcs.get(i)).append("\n");
                }
                if (result.removedEpcs.size() > limit) {
                    sb.append("  ... +").append(result.removedEpcs.size() - limit)
                            .append(isArabic ? " أخرى" : " more").append("\n");
                }
            }
        } else {
            sb.append(isArabic ? "✅ لا توجد تغييرات" : "✅ No changes detected");
        }

        return sb.toString();
    }

    private static void appendSnapshotInfo(StringBuilder sb, InventorySnapshot s, boolean isArabic) {
        sb.append("  • ").append(isArabic ? "الاسم: " : "Name: ")
                .append(s.getName()).append("\n");
        sb.append("  • ").append(isArabic ? "التاريخ: " : "Date: ")
                .append(s.getFormattedDate()).append("\n");
        sb.append("  • ").append(isArabic ? "القطع: " : "Items: ")
                .append(s.getTotalItems()).append("\n");
        sb.append("  • ").append(isArabic ? "الوزن: " : "Weight: ")
                .append(String.format(java.util.Locale.US, "%.2f g", s.getTotalWeight()))
                .append("\n");
        sb.append("  • ").append(isArabic ? "القيمة: " : "Value: ")
                .append(String.format(java.util.Locale.US, "$%,.2f", s.getTotalValue()))
                .append("\n");
    }

    private static String formatDiff(int diff, String arUnit, String enUnit, boolean isArabic) {
        if (diff == 0) {
            return isArabic ? "لا تغيير" : "No change";
        }
        String sign = diff > 0 ? "+" : "";
        return sign + diff + " " + (isArabic ? arUnit : enUnit);
    }

    private static String formatDuration(long ms, boolean isArabic) {
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0) {
            return isArabic ? days + " يوم" : days + " days";
        } else if (hours > 0) {
            return isArabic ? hours + " ساعة" : hours + " hours";
        } else if (minutes > 0) {
            return isArabic ? minutes + " دقيقة" : minutes + " minutes";
        } else {
            return isArabic ? seconds + " ثانية" : seconds + " seconds";
        }
    }

    /**
     * ✅ بناء JSON للنتيجة (للسيرفر)
     */
    @Nullable
    public static JSONObject toJson(@Nullable ComparisonResult result) {
        if (result == null) return null;

        try {
            JSONObject obj = new JSONObject();
            obj.put("snapshot_1_id", result.snapshot1.getId());
            obj.put("snapshot_2_id", result.snapshot2.getId());
            obj.put("items_diff", result.itemsDiff);
            obj.put("weight_diff", result.weightDiff);
            obj.put("value_diff", result.valueDiff);
            obj.put("added_count", result.addedEpcs.size());
            obj.put("removed_count", result.removedEpcs.size());
            obj.put("unchanged_count", result.unchangedEpcs.size());

            JSONArray addedArr = new JSONArray();
            for (String epc : result.addedEpcs) addedArr.put(epc);
            obj.put("added_epcs", addedArr);

            JSONArray removedArr = new JSONArray();
            for (String epc : result.removedEpcs) removedArr.put(epc);
            obj.put("removed_epcs", removedArr);

            obj.put("time_span_ms", result.timeSpanMs);

            return obj;

        } catch (Exception e) {
            return null;
        }
    }
}
