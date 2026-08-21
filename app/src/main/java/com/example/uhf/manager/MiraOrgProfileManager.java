package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * 🏢 MiraOrgProfileManager - مدير بيانات الجهة المركزي
 * 
 * يحفظ بيانات الجهة (الاسم، الشعار، جهات الاتصال، الرقم الضريبي) ويعممها على كامل التطبيق
 * تستخدم في: شاشة تسجيل الدخول، الشاشة الرئيسية، الفواتير، الإيصالات
 */
public class MiraOrgProfileManager {

    private static final String PREFS_NAME = "MIRA_ORG_PROFILE";
    private static MiraOrgProfileManager instance;
    private final SharedPreferences prefs;
    private final Context context;

    private MiraOrgProfileManager(Context context) {
        this.context = context.getApplicationContext();
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

    public String getOrgNameEn() {
        return prefs.getString("org_name_en", "");
    }

    public String getOrgSloganEn() {
        return prefs.getString("org_slogan_en", "");
    }

    public String getOrgLogo() {
        return prefs.getString("org_logo", "");
    }

    public String getTaxNumber() {
        return prefs.getString("tax_number", "VAT-3100293841");
    }

    // ✅ إضافة getters جديدة
    public String getOrgNameAr() {
        return prefs.getString("org_name_ar", prefs.getString("org_name", "ميرا للتكنولوجيا"));
    }

    // ==================== Setters ====================

    public void setTaxNumber(String taxNumber) {
        prefs.edit().putString("tax_number", taxNumber).apply();
    }

    public void saveOrgProfile(String name, String slogan, String phone, String address, String taxNumber) {
        prefs.edit()
                .putString("org_name", name)
                .putString("org_slogan", slogan)
                .putString("org_phone", phone)
                .putString("org_address", address)
                .putString("tax_number", taxNumber)
                .apply();
    }

    /**
     * ✅ حفظ بيانات كاملة من JSON (من السيرفر)
     */
    public void saveFromServer(JSONObject org) {
        if (org == null) return;
        
        SharedPreferences.Editor editor = prefs.edit();
        
        editor.putString("org_name_ar", org.optString("name_ar", getOrgNameAr()));
        editor.putString("org_name_en", org.optString("name_en", getOrgNameEn()));
        editor.putString("org_name", org.optString("name_ar", getOrgName()));
        editor.putString("org_phone", org.optString("phone", getOrgPhone()));
        editor.putString("org_email", org.optString("email", getOrgEmail()));
        editor.putString("tax_number", org.optString("tax_number", getTaxNumber()));
        editor.putString("org_logo", org.optString("logo_url", getOrgLogo()));
        
        editor.apply();
    }

    /**
     * ✅ حفظ بيانات كاملة يدوياً
     */
    public void saveFullProfile(String nameAr, String nameEn, String phone, String email, String tax) {
        SharedPreferences.Editor editor = prefs.edit();
        
        editor.putString("org_name_ar", nameAr);
        editor.putString("org_name_en", nameEn);
        editor.putString("org_name", nameAr);
        editor.putString("org_phone", phone);
        editor.putString("org_email", email);
        editor.putString("tax_number", tax);
        
        editor.apply();
    }

    // ==================== دوال مساعدة ====================

    /**
     * ✅ الحصول على الاسم حسب اللغة الحالية
     */
    public String getDisplayName() {
        String locale = java.util.Locale.getDefault().getLanguage();
        if (locale.equals("ar")) {
            String ar = getOrgNameAr();
            return ar.isEmpty() ? getOrgNameEn() : ar;
        }
        String en = getOrgNameEn();
        return en.isEmpty() ? getOrgNameAr() : en;
    }

    /**
     * ✅ اسم التطبيق الكامل: "اسم الجهة - MIRA"
     */
    public String getFullAppTitle() {
        String name = getDisplayName();
        return name.isEmpty() ? "MIRA Jewel" : name + " - MIRA";
    }

    /**
     * ✅ توقيع الجهة للفواتير والشاشات
     */
    public String getAppSignature() {
        return getDisplayName() + " | " + getOrgSlogan();
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

    // ==================== دوال المستخدم ====================

    public int getCurrentUserId() {
        try {
            SharedPreferences authPrefs = context.getSharedPreferences("MIRA_AUTH", Context.MODE_PRIVATE);
            return authPrefs.getInt("user_id", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    public String getAuthToken() {
        try {
            SharedPreferences authPrefs = context.getSharedPreferences("MIRA_AUTH", Context.MODE_PRIVATE);
            String token = authPrefs.getString("auth_token", "");
            if (token.isEmpty()) {
                token = authPrefs.getString("session_token", "");
            }
            return token;
        } catch (Exception e) {
            return "";
        }
    }

    public void saveUserAuth(int userId, String token) {
        try {
            SharedPreferences authPrefs = context.getSharedPreferences("MIRA_AUTH", Context.MODE_PRIVATE);
            authPrefs.edit()
                    .putInt("user_id", userId)
                    .putString("auth_token", token)
                    .apply();
        } catch (Exception e) {
            // تجاهل الأخطاء
        }
    }
}
