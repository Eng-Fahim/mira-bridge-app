package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * MIRA Quick Access Settings
 * إدارة تخصيص أزرار الوصول السريع
 */
public class MiraQuickAccessSettings {

    private static final String PREFS_NAME = "MIRA_QUICK_ACCESS";
    private static final String KEY_ENABLED_ACTIONS = "enabled_actions";
    
    private static MiraQuickAccessSettings instance;
    private final SharedPreferences prefs;

    // تعريف جميع الإجراءات المتاحة
    public static class QuickAction {
        public String id;
        public String titleAr;
        public String titleEn;
        public boolean enabled;

        public QuickAction(String id, String titleAr, String titleEn, boolean enabled) {
            this.id = id;
            this.titleAr = titleAr;
            this.titleEn = titleEn;
            this.enabled = enabled;
        }
    }

    private MiraQuickAccessSettings(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized MiraQuickAccessSettings getInstance(Context context) {
        if (instance == null) {
            instance = new MiraQuickAccessSettings(context);
        }
        return instance;
    }

    /**
     * الحصول على جميع الإجراءات المتاحة
     */
    public List<QuickAction> getAllActions() {
        List<QuickAction> actions = new ArrayList<>();
        actions.add(new QuickAction("identity", "استوديو التكويد", "Identity Studio", true));
        actions.add(new QuickAction("radar", "رادار التتبع", "Radar Tracking", true));
        actions.add(new QuickAction("gate", "مراقبة البوابات", "Gate Monitor", true));
        actions.add(new QuickAction("reports", "التقارير", "Reports", true));
        actions.add(new QuickAction("accounting", "المحاسبة", "Accounting", true));
        actions.add(new QuickAction("transfers", "التحويلات", "Transfers", true));
        actions.add(new QuickAction("admin_hub", "الخيارات الإدارية", "Admin Hub", true));
        actions.add(new QuickAction("sales_history", "سجل المبيعات", "Sales History", true));
        actions.add(new QuickAction("server_config", "إعدادات السيرفر", "Server Config", true));
        actions.add(new QuickAction("gold_rates", "أسعار الذهب", "Gold Rates", false));
        actions.add(new QuickAction("users", "المستخدمين", "Users", false));
        actions.add(new QuickAction("branches", "الفروع", "Branches", false));
        
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
}
