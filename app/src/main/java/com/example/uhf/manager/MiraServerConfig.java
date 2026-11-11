package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * MIRA Server Configuration Manager
 * إدارة عنوان السيرفر والنطاق بشكل ديناميكي وآمن
 *
 * ✅ مضبوط لـ Laravel API:
 *    BASE = https://jewelry.ibreg.org/api/
 *    PING = {BASE}ping  →  https://jewelry.ibreg.org/api/ping
 */
public class MiraServerConfig {

    private static final String TAG = "MiraServerConfig";
    private static final String PREFS_NAME = "MIRA_SERVER_CONFIG";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_GATE_ID = "gate_id";
    private static final String KEY_AUTH_TOKEN = "auth_token";

    // ✅ عنوان Laravel API الافتراضي (ينتهي بـ / دائماً)
    private static final String DEFAULT_BASE_URL = "https://jewelry.ibreg.org/api/";

    // ✅ مسار فحص الاتصال (يجب أن يطابق Route في routes/api.php)
    private static final String PING_PATH = "ping";

    private static MiraServerConfig instance;
    private final SharedPreferences prefs;

    private MiraServerConfig(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized MiraServerConfig getInstance(Context context) {
        if (instance == null) {
            instance = new MiraServerConfig(context);
        }
        return instance;
    }

    // ==========================================================
    //  Base URL
    // ==========================================================

    /**
     * ✅ إرجاع الرابط الأساسي مع ضمان:
     *   - قيمة افتراضية عند عدم الحفظ
     *   - تحويل أي قيمة قديمة تحتوي /wp-json/ إلى /api/
     *   - نهاية / مضمونة
     */
    public String getBaseUrl() {
        String url = prefs.getString(KEY_BASE_URL, "");
        if (url == null || url.trim().isEmpty()) {
            url = DEFAULT_BASE_URL;
            setBaseUrl(url);
            return url;
        }

        String clean = sanitizeBaseUrl(url);
        if (!clean.equals(url)) {
            setBaseUrl(clean); // ترحيل القيم القديمة تلقائياً
        }
        return clean;
    }

    public void setBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            Log.e(TAG, "Cannot save empty base URL!");
            return;
        }
        String clean = sanitizeBaseUrl(baseUrl);
        Log.d(TAG, "Saving base URL: " + clean);
        prefs.edit().putString(KEY_BASE_URL, clean).apply();
    }

    /**
     * ✅ توحيد الصيغة: يحوّل أي wp-json قديم إلى api، ويضمن / نهائي
     */
    private String sanitizeBaseUrl(String raw) {
        String clean = (raw == null) ? DEFAULT_BASE_URL : raw.trim();

        // ترحيل: إذا كانت قيمة محفوظة بصيغة WordPress
        int wpIdx = clean.indexOf("/wp-json/");
        if (wpIdx > 0) {
            clean = clean.substring(0, wpIdx) + "/api/";
        }

        if (clean.isEmpty()) {
            clean = DEFAULT_BASE_URL;
        }
        if (!clean.endsWith("/")) {
            clean += "/";
        }
        return clean;
    }

    // ==========================================================
    //  فحص الاتصال (Laravel ping)
    // ==========================================================

    /**
     * ✅ يبني: {BASE}ping  (بدون wp-json)
     * مثال: https://jewelry.ibreg.org/api/ping
     */
    public boolean testConnection(String baseUrl) {
        try {
            String cleanUrl = sanitizeBaseUrl(baseUrl);
            String pingUrl = cleanUrl + PING_PATH;

            Log.d(TAG, "Testing connection to: " + pingUrl);

            java.net.URL url = new java.net.URL(pingUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");

            int responseCode = conn.getResponseCode();
            Log.d(TAG, "Connection test response: " + responseCode);

            conn.disconnect();
            return responseCode >= 200 && responseCode < 400;
        } catch (Exception e) {
            Log.e(TAG, "Connection test failed: " + e.getMessage());
            return false;
        }
    }

    // ==========================================================
    //  API Key / Gate ID / Auth Token
    // ==========================================================

    public String getApiKey() {
        return prefs.getString(KEY_API_KEY, "");
    }

    public void setApiKey(String apiKey) {
        Log.d(TAG, "Saving API Key: " + (apiKey != null && !apiKey.isEmpty() ? "SET" : "EMPTY"));
        prefs.edit().putString(KEY_API_KEY, apiKey != null ? apiKey : "").apply();
    }

    public String getGateId() {
        return prefs.getString(KEY_GATE_ID, "");
    }

    public void setGateId(String gateId) {
        Log.d(TAG, "Saving Gate ID: " + (gateId != null && !gateId.isEmpty() ? "SET" : "EMPTY"));
        prefs.edit().putString(KEY_GATE_ID, gateId != null ? gateId : "").apply();
    }

    public String getAuthToken() {
        return prefs.getString(KEY_AUTH_TOKEN, "");
    }

    // ==========================================================
    //  Session
    // ==========================================================

    public void saveSession(String apiKey, String gateId, String authToken) {
        Log.d(TAG, "Saving session - API Key: " + (apiKey != null && !apiKey.isEmpty() ? "SET" : "EMPTY")
                + ", Gate ID: " + (gateId != null && !gateId.isEmpty() ? "SET" : "EMPTY")
                + ", Auth Token: " + (authToken != null && !authToken.isEmpty() ? "SET" : "EMPTY"));

        prefs.edit()
                .putString(KEY_API_KEY, apiKey != null ? apiKey : "")
                .putString(KEY_GATE_ID, gateId != null ? gateId : "")
                .putString(KEY_AUTH_TOKEN, authToken != null ? authToken : "")
                .apply();
    }

    public void clearAuthToken() {
        prefs.edit().remove(KEY_AUTH_TOKEN).apply();
        Log.d(TAG, "Auth token cleared from serverConfig");
    }

    public void clearSession() {
        Log.d(TAG, "Clearing session");
        prefs.edit()
                .remove(KEY_API_KEY)
                .remove(KEY_GATE_ID)
                .remove(KEY_AUTH_TOKEN)
                .apply();
    }

    public void clearAllData() {
        Log.d(TAG, "Clearing all data");
        prefs.edit().clear().apply();
    }

    public boolean hasSession() {
        boolean hasKey = !getApiKey().isEmpty();
        boolean hasToken = !getAuthToken().isEmpty();
        boolean hasSession = hasKey && hasToken;
        Log.d(TAG, "Has session: " + hasSession + " (Key: " + hasKey + ", Token: " + hasToken + ")");
        return hasSession;
    }

    public boolean hasSavedBaseUrl() {
        String url = prefs.getString(KEY_BASE_URL, "");
        return url != null && !url.isEmpty();
    }

    // ==========================================================
    //  Reset
    // ==========================================================

    public void resetToDefault() {
        Log.d(TAG, "Resetting to default settings");
        setBaseUrl(DEFAULT_BASE_URL);
        clearSession();
    }
}
