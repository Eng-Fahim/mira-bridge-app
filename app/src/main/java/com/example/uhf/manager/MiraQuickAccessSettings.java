package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.uhf.R;

import java.util.ArrayList;
import java.util.List;

/**
 * MIRA Quick Access Settings v2.1
 * إدارة تخصيص أزرار الوصول السريع (متوافقة كلياً مع Server-Driven Permissions)
 */
public class MiraQuickAccessSettings {

    private static final String PREFS_NAME = "MIRA_QUICK_ACCESS";

    private static MiraQuickAccessSettings instance;
    private final SharedPreferences prefs;
    private final Context context;

    public static class QuickAction {
        public String id;
        public String titleAr;
        public String titleEn;
        public boolean enabled;
        public int iconRes;

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

        public String getLabel(boolean isArabic) {
            return isArabic ? titleAr : titleEn;
        }
    }

    private MiraQuickAccessSettings(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized MiraQuickAccessSettings getInstance(Context context) {
        if (instance == null) {
            instance = new MiraQuickAccessSettings(context);
        }
        return instance;
    }

    public List<QuickAction> getAllActions() {
        List<QuickAction> actions = new ArrayList<>();

        actions.add(new QuickAction("identity", "استوديو التكويد", "Identity Studio", true, R.drawable.ic_identity_studio));
        actions.add(new QuickAction("radar", "رادار التتبع", "Radar Tracking", true, R.drawable.ic_radar));
        actions.add(new QuickAction("gate", "مراقبة البوابات", "Gate Monitor", true, R.drawable.ic_security));
        actions.add(new QuickAction("reports", "التقارير", "Reports", true, R.drawable.ic_reports));
        actions.add(new QuickAction("accounting", "المحاسبة", "Accounting", true, R.drawable.ic_accounting));
        actions.add(new QuickAction("transfers", "التحويلات", "Transfers", true, R.drawable.ic_transfer));
        actions.add(new QuickAction("admin_hub", "الخيارات الإدارية", "Admin Hub", true, R.drawable.ic_admin_hub));
        actions.add(new QuickAction("sales_history", "سجل المبيعات", "Sales History", true, R.drawable.ic_sales_history));
        actions.add(new QuickAction("server_config", "إعدادات السيرفر", "Server Config", true, R.drawable.ic_server_config));
        actions.add(new QuickAction("vouchers", "السندات المالية", "Vouchers", true, R.drawable.ic_accounting));
        actions.add(new QuickAction("gold_rates", "أسعار الذهب", "Gold Rates", true, R.drawable.ic_reports));
        actions.add(new QuickAction("users", "المستخدمين", "Users", true, R.drawable.ic_identity_studio));
        actions.add(new QuickAction("branches", "الفروع", "Branches", true, R.drawable.ic_server_config));

        // تحميل الحالة المحفوظة محلياً للتفضيلات
        for (QuickAction action : actions) {
            action.enabled = prefs.getBoolean(action.id, action.enabled);
        }

        return actions;
    }

    public void setActionEnabled(String actionId, boolean enabled) {
        prefs.edit().putBoolean(actionId, enabled).apply();
    }

    public boolean isActionEnabled(String actionId) {
        return prefs.getBoolean(actionId, true);
    }

    /**
     * ✅ الحصول على الإجراءات المفعّلة محلياً والتي يسمح بها السيرفر للمستخدم حصرياً
     */
    public List<QuickAction> getEnabledActions() {
        List<QuickAction> enabled = new ArrayList<>();
        PermissionManager pm = PermissionManager.getInstance(context);

        for (QuickAction action : getAllActions()) {
            // 1. التحقق من التفعيل المحلي للمستخدم
            if (!action.enabled) continue;

            // 2. التحقق من صلاحيات السيرفر (Server-Driven) عبر PermissionManager
            if (checkServerPermission(pm, action.id)) {
                enabled.add(action);
            }
        }
        return enabled;
    }

    /**
     * ربط معرفات الأزرار بدوال الصلاحيات في PermissionManager القادمة من السيرفر
     */
    private boolean checkServerPermission(PermissionManager pm, String actionId) {
        if (pm == null) return false;
        switch (actionId) {
            case "identity":
                return pm.canAccessFragment("IdentityStudio");
            case "radar":
                return pm.canAccessFragment("Radar");
            case "gate":
                return pm.canAccessFragment("SecureGate");
            case "reports":
                return pm.canAccessFragment("Reports");
            case "accounting":
                return pm.canAccessFragment("Accounting");
            case "transfers":
                return pm.canAccessFragment("Transfers");
            case "admin_hub":
                return pm.canAccessFragment("AdminHub");
            case "sales_history":
                return pm.canAccessFragment("SalesHistory");
            case "server_config":
                return pm.canAccessFragment("ServerConfig");
            case "gold_rates":
                return pm.canAccessFragment("GoldRates");
            case "vouchers":
                return pm.canAccessFragment("Vouchers");
            case "users":
                return pm.canAccessFragment("UsersManagement");
            case "branches":
                return pm.canAccessFragment("Branches");
            default:
                return true;
        }
    }

    public QuickAction getActionById(String id) {
        if (id == null) return null;
        for (QuickAction action : getAllActions()) {
            if (id.equals(action.id)) return action;
        }
        return null;
    }
}
