package com.example.uhf.tools;

import android.content.Context;
import android.content.SharedPreferences;

public class AppConfigManager {

    private static final String PREF_NAME = "mira_app_config";
    private final SharedPreferences prefs;

    public AppConfigManager(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    // حفظ التكوينات الكاملة من السيرفر
    public void saveConfig(String nameAr, String nameEn, String phone, String email, String taxNum, String currAr, String currEn, boolean rfid, boolean biometric) {
        prefs.edit()
                .putString("org_name_ar", nameAr)
                .putString("org_name_en", nameEn)
                .putString("org_phone", phone)
                .putString("org_email", email)
                .putString("org_tax_num", taxNum)
                .putString("currency_ar", currAr)
                .putString("currency_en", currEn)
                .putBoolean("enable_rfid", rfid)
                .putBoolean("enable_biometric", biometric)
                .apply();
    }

    // جلب اسم المنشأة الديناميكي بحسب لغة الواجهة الحالية
    public String getLocalizedOrgName(boolean isArabic) {
        if (isArabic) {
            return prefs.getString("org_name_ar", "ميرا للتكنولوجيا");
        } else {
            return prefs.getString("org_name_en", "Mira Technology");
        }
    }

    // جلب العملة بحسب اللغة
    public String getLocalizedCurrency(boolean isArabic) {
        return isArabic ? prefs.getString("currency_ar", "دولار") : prefs.getString("currency_en", "USD");
    }

    public String getOrgPhone() { return prefs.getString("org_phone", ""); }
    public String getOrgEmail() { return prefs.getString("org_email", ""); }
    public String getTaxNumber() { return prefs.getString("org_tax_num", ""); }
    public boolean isRfidEnabled() { return prefs.getBoolean("enable_rfid", true); }
    public boolean isBiometricEnabled() { return prefs.getBoolean("enable_biometric", true); }
}
