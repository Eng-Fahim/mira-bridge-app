package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 🏢 مدير بيانات الجهة - يعمم البيانات على كامل التطبيق
 */
public class OrgProfileManager {

    private static final String PREFS_NAME = "MIRA_ORG_PROFILE";
    private static OrgProfileManager instance;
    private final SharedPreferences prefs;

    private OrgProfileManager(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static OrgProfileManager getInstance(Context context) {
        if (instance == null) {
            instance = new OrgProfileManager(context);
        }
        return instance;
    }

    public String getOrgName() {
        return prefs.getString("org_name", "MIRA Jewelry");
    }

    public String getOrgSlogan() {
        return prefs.getString("org_slogan", "Jewelry Enterprise Platform");
    }

    public String getOrgPhone() {
        return prefs.getString("org_phone", "");
    }

    public String getOrgEmail() {
        return prefs.getString("org_email", "");
    }

    public String getOrgAddress() {
        return prefs.getString("org_address", "");
    }

    public String getOrgWebsite() {
        return prefs.getString("org_website", "");
    }

    /**
     * ✅ جلب اسم التطبيق الكامل (اسم الجهة + MIRA)
     */
    public String getFullAppTitle() {
        return getOrgName() + " - MIRA";
    }

    /**
     * ✅ جلب توقيع التطبيق (للشاشات والفواتير)
     */
    public String getAppSignature() {
        return getOrgName() + " | " + getOrgSlogan();
    }
}
