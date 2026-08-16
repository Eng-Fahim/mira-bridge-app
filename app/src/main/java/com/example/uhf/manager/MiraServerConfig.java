package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * MIRA Server Configuration Manager
 * إدارة عنوان السيرفر والنطاق بشكل ديناميكي
 */
public class MiraServerConfig {

    private static final String PREFS_NAME = "MIRA_SERVER_CONFIG";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_GATE_ID = "gate_id";
    
    private static MiraServerConfig instance;
    private final SharedPreferences prefs;

    private MiraServerConfig(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized MiraServerConfig getInstance(Context context) {
        if (instance == null) {
            instance = new MiraServerConfig(context);
        }
        return instance;
    }

    /**
     * الحصول على الرابط الأساسي
     */
    public String getBaseUrl() {
        return prefs.getString(KEY_BASE_URL, "https://ams.ibreg.org/wp-json/");
    }

    /**
     * حفظ الرابط الأساسي
     */
    public void setBaseUrl(String baseUrl) {
        // التأكد من أن الرابط ينتهي بـ /
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/";
        }
        prefs.edit().putString(KEY_BASE_URL, baseUrl).apply();
    }

    /**
     * الحصول على الرابط الكامل لـ API
     */
    public String getApiUrl(String endpoint) {
        String baseUrl = getBaseUrl();
        // إزالة / الزائدة من البداية
        if (endpoint.startsWith("/")) {
            endpoint = endpoint.substring(1);
        }
        return baseUrl + endpoint;
    }

    /**
     * الحصول على مفتاح API
     */
    public String getApiKey() {
        return prefs.getString(KEY_API_KEY, "mira_gate_test071234567890abcdefghijklmnop");
    }

    /**
     * حفظ مفتاح API
     */
    public void setApiKey(String apiKey) {
        prefs.edit().putString(KEY_API_KEY, apiKey).apply();
    }

    /**
     * الحصول على معرف البوابة
     */
    public String getGateId() {
        return prefs.getString(KEY_GATE_ID, "secure_gate_01");
    }

    /**
     * حفظ معرف البوابة
     */
    public void setGateId(String gateId) {
        prefs.edit().putString(KEY_GATE_ID, gateId).apply();
    }

    /**
     * اختبار الاتصال بالسيرفر
     */
    public boolean testConnection(String baseUrl) {
        try {
            java.net.URL url = new java.net.URL(baseUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            int responseCode = conn.getResponseCode();
            conn.disconnect();
            return responseCode > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
