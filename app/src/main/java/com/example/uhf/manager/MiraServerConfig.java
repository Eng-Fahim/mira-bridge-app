package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * MIRA Server Configuration Manager
 * إدارة عنوان السيرفر والنطاق بشكل ديناميكي وآمن
 */
public class MiraServerConfig {

    private static final String TAG = "MiraServerConfig";
    private static final String PREFS_NAME = "MIRA_SERVER_CONFIG";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_GATE_ID = "gate_id";
    private static final String KEY_AUTH_TOKEN = "auth_token";
    
    // ✅ إضافة العنوان الافتراضي كـ constant
    private static final String DEFAULT_BASE_URL = "https://id.ibreg.org/wp-json/";
    
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
     * ✅ الحصول على الرابط الأساسي
     * في حال عدم وجود رابط محفوظ، يتم استخدام الرابط الافتراضي
     */
    /**
 * ✅ إصلاح: ضمان / نهائي دائماً
 */
public String getBaseUrl() {
    String url = prefs.getString(KEY_BASE_URL, "");
    if (url == null || url.isEmpty()) {
        url = DEFAULT_BASE_URL;
        setBaseUrl(DEFAULT_BASE_URL);
    }
    // ✅ ضمان / نهائي
    if (!url.endsWith("/")) {
        url += "/";
        setBaseUrl(url);
    }
    return url;
}


/**
 * ✅ إصلاح: دعم baseUrl مع أو بدون wp-json
 */
public boolean testConnection(String baseUrl) {
    try {
        Log.d(TAG, "Testing connection to: " + baseUrl);
        
        String cleanUrl = baseUrl.trim();
        if (!cleanUrl.endsWith("/")) cleanUrl += "/";
        
        // ✅ لا تكرر wp-json
        String pingUrl;
        if (cleanUrl.contains("/wp-json/")) {
            pingUrl = cleanUrl + "mira-gate/v1/ping";
        } else {
            pingUrl = cleanUrl + "wp-json/mira-gate/v1/ping";
        }
        
        Log.d(TAG, "Ping URL: " + pingUrl);
        
        java.net.URL url = new java.net.URL(pingUrl);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestMethod("GET");
        
        int responseCode = conn.getResponseCode();
        Log.d(TAG, "Connection test response: " + responseCode);
        
        conn.disconnect();
        return responseCode >= 200 && responseCode < 400;
    } catch (Exception e) {
        Log.e(TAG, "Connection test failed: " + e.getMessage());
        return false;
    }
}


/**
 * ✅ جديد: مسح Token منفرداً (يُستدعى من logout)
 */
public void clearAuthToken() {
    prefs.edit().remove(KEY_AUTH_TOKEN).apply();
    Log.d(TAG, "Auth token cleared from serverConfig");
}

    /**
     * ✅ حفظ الرابط الأساسي
     */
    public void setBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            Log.e(TAG, "Cannot save empty base URL!");
            return;
        }
        
        // ✅ التأكد من وجود / في نهاية الرابط
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/";
        }
        
        Log.d(TAG, "Saving base URL: " + baseUrl);
        prefs.edit().putString(KEY_BASE_URL, baseUrl).apply();
    }

    /**
     * ✅ مفتاح API
     */
    public String getApiKey() {
        return prefs.getString(KEY_API_KEY, "");
    }

    public void setApiKey(String apiKey) {
        Log.d(TAG, "Saving API Key: " + (apiKey != null && !apiKey.isEmpty() ? "SET" : "EMPTY"));
        prefs.edit().putString(KEY_API_KEY, apiKey != null ? apiKey : "").apply();
    }

    /**
     * ✅ معرف البوابة
     */
    public String getGateId() {
        return prefs.getString(KEY_GATE_ID, "");
    }

    public void setGateId(String gateId) {
        Log.d(TAG, "Saving Gate ID: " + (gateId != null && !gateId.isEmpty() ? "SET" : "EMPTY"));
        prefs.edit().putString(KEY_GATE_ID, gateId != null ? gateId : "").apply();
    }

    /**
     * ✅ حفظ بيانات الجلسة بعد تسجيل الدخول
     */
    public void saveSession(String apiKey, String gateId, String authToken) {
        Log.d(TAG, "Saving session - API Key: " + (apiKey != null && !apiKey.isEmpty() ? "SET" : "EMPTY") + 
                   ", Gate ID: " + (gateId != null && !gateId.isEmpty() ? "SET" : "EMPTY") + 
                   ", Auth Token: " + (authToken != null && !authToken.isEmpty() ? "SET" : "EMPTY"));
        
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
        Log.d(TAG, "Clearing session");
        prefs.edit()
            .remove(KEY_API_KEY)
            .remove(KEY_GATE_ID)
            .remove(KEY_AUTH_TOKEN)
            .apply();
    }

    /**
     * ✅ مسح كل البيانات (عند تسجيل الخروج الكامل)
     */
    public void clearAllData() {
        Log.d(TAG, "Clearing all data");
        prefs.edit()
            .clear()
            .apply();
    }

    /**
     * ✅ التحقق من وجود جلسة نشطة
     */
    public boolean hasSession() {
        boolean hasKey = !getApiKey().isEmpty();
        boolean hasToken = !getAuthToken().isEmpty();
        boolean hasSession = hasKey && hasToken;
        
        Log.d(TAG, "Has session: " + hasSession + " (Key: " + hasKey + ", Token: " + hasToken + ")");
        return hasSession;
    }

    /**
     * ✅ التحقق من وجود عنوان خادم محفوظ
     */
    public boolean hasSavedBaseUrl() {
        String url = prefs.getString(KEY_BASE_URL, "");
        return url != null && !url.isEmpty();
    }

   

    /**
     * ✅ إعادة تعيين الإعدادات إلى الافتراضي
     */
    public void resetToDefault() {
        Log.d(TAG, "Resetting to default settings");
        setBaseUrl(DEFAULT_BASE_URL);
        clearSession();
    }
}
