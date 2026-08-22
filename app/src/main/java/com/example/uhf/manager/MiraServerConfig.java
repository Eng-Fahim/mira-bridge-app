package com.example.uhf.manager;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * MIRA Server Configuration Manager
 * إدارة عنوان السيرفر والنطاق بشكل ديناميكي وآمن
 */
public class MiraServerConfig {

    private static final String PREFS_NAME = "MIRA_SERVER_CONFIG";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_GATE_ID = "gate_id";
    private static final String KEY_AUTH_TOKEN = "auth_token";
    
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
     * ✅ الرابط الافتراضي
     */
    public String getBaseUrl() {
        return prefs.getString(KEY_BASE_URL, "https://sa.mirabarcode.com/wp-json/");
    }

    public void setBaseUrl(String baseUrl) {
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/";
        }
        prefs.edit().putString(KEY_BASE_URL, baseUrl).apply();
    }

    /**
     * ✅ مفتاح API
     */
    public String getApiKey() {
        return prefs.getString(KEY_API_KEY, "");
    }

    public void setApiKey(String apiKey) {
        prefs.edit().putString(KEY_API_KEY, apiKey).apply();
    }

    /**
     * ✅ معرف البوابة
     */
    public String getGateId() {
        return prefs.getString(KEY_GATE_ID, "");
    }

    public void setGateId(String gateId) {
        prefs.edit().putString(KEY_GATE_ID, gateId).apply();
    }

    /**
     * ✅ حفظ بيانات الجلسة بعد تسجيل الدخول
     */
    public void saveSession(String apiKey, String gateId, String authToken) {
        // ✅ استخدام prefs الموجود في الكلاس مباشرة
        prefs.edit()
            .putString(KEY_API_KEY, apiKey != null ? apiKey : "")
            .putString(KEY_GATE_ID, gateId != null ? gateId : "")
            .putString(KEY_AUTH_TOKEN, authToken != null ? authToken : "")
            .apply();
    }

    /**
     * ✅ الحصول على التوكن المحفوظ
     */
    public String getAuthToken() {
        return prefs.getString(KEY_AUTH_TOKEN, "");
    }

    /**
     * ✅ مسح الجلسة عند تسجيل الخروج
     */
    public void clearSession() {
        prefs.edit()
            .remove(KEY_API_KEY)
            .remove(KEY_GATE_ID)
            .remove(KEY_AUTH_TOKEN)
            .apply();
    }

    /**
     * ✅ التحقق من وجود جلسة نشطة
     */
    public boolean hasSession() {
        return !getApiKey().isEmpty() && !getAuthToken().isEmpty();
    }

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
