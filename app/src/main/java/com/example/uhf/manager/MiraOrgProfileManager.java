package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 🏢 MiraOrgProfileManager - مدير بيانات الجهة المركزي
 * 
 * يحفظ بيانات الجهة (الاسم، الشعار، جهات الاتصال) ويعممها على كامل التطبيق
 * تستخدم في: شاشة تسجيل الدخول، الشاشة الرئيسية، الفواتير، الإيصالات
 */
public class MiraOrgProfileManager {

    private static final String PREFS_NAME = "MIRA_ORG_PROFILE";
    private static MiraOrgProfileManager instance;
    private final SharedPreferences prefs;

    private MiraOrgProfileManager(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static MiraOrgProfileManager getInstance(Context context) {
        if (instance == null) {
            instance = new MiraOrgProfileManager(context);
        }
        return instance;
    }

    // ==================== Getters ====================

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

    // ==================== دوال مساعدة ====================

    /**
     * ✅ اسم التطبيق الكامل: "اسم الجهة - MIRA"
     */
    public String getFullAppTitle() {
        String name = getOrgName();
        return name.isEmpty() ? "MIRA Jewel" : name + " - MIRA";
    }

    /**
     * ✅ توقيع الجهة للفواتير والشاشات
     */
    public String getAppSignature() {
        return getOrgName() + " | " + getOrgSlogan();
    }

    /**
     * ✅ معلومات الاتصال كاملة
     */
    public String getContactInfo() {
        StringBuilder sb = new StringBuilder();
        String phone = getOrgPhone();
        String email = getOrgEmail();
        String address = getOrgAddress();
        
        if (!phone.isEmpty()) sb.append("📞 ").append(phone);
        if (!email.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("📧 ").append(email);
        }
        if (!address.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("📍 ").append(address);
        }
        return sb.toString();
    }

    /**
     * ✅ التحقق من اكتمال بيانات الجهة
     */
    public boolean isProfileComplete() {
        return !getOrgName().equals("MIRA Jewelry") 
            && !getOrgName().isEmpty();
    }
}
