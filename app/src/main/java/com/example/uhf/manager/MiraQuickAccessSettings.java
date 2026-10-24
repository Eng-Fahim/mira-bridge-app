package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.uhf.R;

import java.util.ArrayList;
import java.util.List;

/**
 * MIRA Quick Access Settings v2.0
 * إدارة تخصيص أزرار الوصول السريع
 * 
 * ✅ v2.0:
 *  - إضافة iconRes للـ QuickAction
 *  - إضافة getLabel() للترجمة الديناميكية
 *  - دعم رموز متعددة
 */
public class MiraQuickAccessSettings {

    private static final String PREFS_NAME = "MIRA_QUICK_ACCESS";
    private static final String KEY_ENABLED_ACTIONS = "enabled_actions";

    private static MiraQuickAccessSettings instance;
    private final SharedPreferences prefs;

    // ═══════════════════════════════════════════════════════
    // ✅ QuickAction v2.0 — مع iconRes
    // ═══════════════════════════════════════════════════════
    public static class QuickAction {
        public String id;
        public String titleAr;
        public String titleEn;
        public boolean enabled;
        public int iconRes;        // ✅ جديد

        public QuickAction(String id, String titleAr, String titleEn, boolean enabled) {
            this(id, titleAr, titleEn, enabled, 0);
        }

        public QuickAction(String id, String titleAr, String titleEn, 
                            boolean enabled, int iconRes) {
            this.id = id;
            this.titleAr = titleAr;
            this.titleEn = titleEn;
            this.enabled = enabled;
            this.iconRes = iconRes;
        }

        /**
         * ✅ الحصول على العنوان حسب اللغة
         */
        public String getLabel(boolean isArabic) {
            return isArabic ? titleAr : titleEn;
        }
    }

    private MiraQuickAccessSettings(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized MiraQuickAccessSettings getInstance(Context context) {
        if (instance == null) {
            instance = new MiraQuickAccessSettings(context);
        }
        return instance;
    }

    /**
     * ✅ الحصول على جميع الإجراءات المتاحة مع الأيقونات
     */
    public List<QuickAction> getAllActions() {
        List<QuickAction> actions = new ArrayList<>();

        // ═══════════════════════════════════════════════════════
        // ✅ الإجراءات الرئيسية مع الأيقونات
        // ═══════════════════════════════════════════════════════
        actions.add(new QuickAction(
                "identity", "استوديو التكويد", "Identity Studio",
                true, R.drawable.ic_identity_studio));

        actions.add(new QuickAction(
                "radar", "رادار التتبع", "Radar Tracking",
                true, R.drawable.ic_radar));

        actions.add(new QuickAction(
                "gate", "مراقبة البوابات", "Gate Monitor",
                true, R.drawable.ic_security));

        actions.add(new QuickAction(
                "reports", "التقارير", "Reports",
                true, R.drawable.ic_reports));

        actions.add(new QuickAction(
                "accounting", "المحاسبة", "Accounting",
                true, R.drawable.ic_accounting));

        actions.add(new QuickAction(
                "transfers", "التحويلات", "Transfers",
                true, R.drawable.ic_transfer));

        actions.add(new QuickAction(
                "admin_hub", "الخيارات الإدارية", "Admin Hub",
                true, R.drawable.ic_admin_hub));

        actions.add(new QuickAction(
                "sales_history", "سجل المبيعات", "Sales History",
                true, R.drawable.ic_sales_history));

        actions.add(new QuickAction(
                "server_config", "إعدادات السيرفر", "Server Config",
                true, R.drawable.ic_server_config));

        actions.add(new QuickAction(
        "vouchers", "السندات المالية", "Vouchers",
        true, R.drawable.ic_accounting)); // يمكنك تغيير الأيقونة لاحقاً
        // ═══════════════════════════════════════════════════════
        // ✅ إجراءات إضافية (بدون أيقونة مخصصة)
        // ═══════════════════════════════════════════════════════
        actions.add(new QuickAction(
                "gold_rates", "أسعار الذهب", "Gold Rates",
                true, R.drawable.ic_reports));

        actions.add(new QuickAction(
                "users", "المستخدمين", "Users",
                true, R.drawable.ic_identity_studio));

        actions.add(new QuickAction(
                "branches", "الفروع", "Branches",
                true, R.drawable.ic_server_config));

        // تحميل الحالة المحفوظة
        for (QuickAction action : actions) {
            action.enabled = prefs.getBoolean(action.id, action.enabled);
        }

        return actions;
    }

    /**
     * حفظ حالة إجراء
     */
    public void setActionEnabled(String actionId, boolean enabled) {
        prefs.edit().putBoolean(actionId, enabled).apply();
    }

    /**
     * التحقق مما إذا كان الإجراء مفعلاً
     */
    public boolean isActionEnabled(String actionId) {
        return prefs.getBoolean(actionId, true);
    }

    /**
     * الحصول على الإجراءات المفعّلة فقط
     */
    public List<QuickAction> getEnabledActions() {
        List<QuickAction> enabled = new ArrayList<>();
        for (QuickAction action : getAllActions()) {
            if (action.enabled) {
                enabled.add(action);
            }
        }
        return enabled;
    }

    /**
     * ✅ البحث عن إجراء بالـ ID
     */
    public QuickAction getActionById(String id) {
        if (id == null) return null;
        for (QuickAction action : getAllActions()) {
            if (id.equals(action.id)) return action;
        }
        return null;
    }
}
