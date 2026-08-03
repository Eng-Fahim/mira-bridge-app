package com.example.uhf.api;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MiraApiClient {

    private static final String TAG = "MiraApiClient";
    private static final String PREFS_NAME = "mira_settings";

    private static final String DEFAULT_API_URL = "https://ams.ibreg.org/wp-json/mira-gate/v1";
    private static final String DEFAULT_API_KEY = "mira_gate_test071234567890abcdefghijklmnop";
    private static final String DEFAULT_GATE_ID = "handheld_c72";

    private final Context context;
    private final String apiUrl;
    private final String apiKey;
    private final String gateId;

    private static MiraApiClient instance;

    public static MiraApiClient getInstance(Context context) {
        if (instance == null) {
            instance = new MiraApiClient(context.getApplicationContext());
        }
        return instance;
    }

    private MiraApiClient(Context context) {
        this.context = context;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.apiUrl = prefs.getString("mira_api_url", DEFAULT_API_URL);
        this.apiKey = prefs.getString("mira_api_key", DEFAULT_API_KEY);
        this.gateId = prefs.getString("mira_gate_id", DEFAULT_GATE_ID);
    }

    // ===================== 🆕 AUTHENTICATION =====================

    /**
     * 🔑 مصادقة المستخدم - تسجيل الدخول
     * Endpoint: POST /auth/login
     */
    public ApiResponse login(String username, String password, String deviceId) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("username", username);
            payload.put("password", password);
            payload.put("device_id", deviceId != null ? deviceId : "");
            payload.put("gate_id", gateId);
            payload.put("app_version", "MIRA-ANDROID-v3.0");
        } catch (Exception e) {
            Log.e(TAG, "Error building login payload: " + e.getMessage());
        }
        return post("/auth/login", payload);
    }

    /**
     * 🔑 مصادقة سريعة باستخدام التوكن المخزن
     * Endpoint: POST /auth/validate-token
     */
    public ApiResponse validateToken(String token) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("token", token);
            payload.put("gate_id", gateId);
        } catch (Exception e) {
            Log.e(TAG, "Error building validate payload: " + e.getMessage());
        }
        return post("/auth/validate-token", payload);
    }

    /**
     * 🚪 تسجيل الخروج وإنهاء الجلسة على السيرفر
     * Endpoint: POST /auth/logout
     */
    public ApiResponse logout(String token) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("token", token);
            payload.put("gate_id", gateId);
        } catch (Exception e) {
            Log.e(TAG, "Error building logout payload: " + e.getMessage());
        }
        return post("/auth/logout", payload);
    }

    /**
     * 👤 جلب بيانات المستخدم الحالي وصلاحياته
     * Endpoint: GET /auth/me
     */
    public ApiResponse getCurrentUser() {
        return get("/auth/me");
    }

    /**
     * 🔄 تجديد التوكن قبل انتهاء صلاحيته
     * Endpoint: POST /auth/refresh-token
     */
    public ApiResponse refreshToken(String oldToken) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("token", oldToken);
            payload.put("gate_id", gateId);
        } catch (Exception e) {
            Log.e(TAG, "Error building refresh payload: " + e.getMessage());
        }
        return post("/auth/refresh-token", payload);
    }

    /**
     * 📱 تسجيل جهاز جديد للمصادقة البيومترية
     * Endpoint: POST /auth/register-biometric
     */
    public ApiResponse registerBiometric(String token, String deviceId, String publicKey) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("token", token);
            payload.put("device_id", deviceId);
            payload.put("public_key", publicKey);
            payload.put("gate_id", gateId);
        } catch (Exception e) {
            Log.e(TAG, "Error building biometric payload: " + e.getMessage());
        }
        return post("/auth/register-biometric", payload);
    }

    /**
     * 🔐 تحقق بيومتري بعد المصادقة الأولية
     * Endpoint: POST /auth/verify-biometric
     */
    public ApiResponse verifyBiometric(String token, String signedChallenge) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("token", token);
            payload.put("signed_challenge", signedChallenge);
            payload.put("gate_id", gateId);
        } catch (Exception e) {
            Log.e(TAG, "Error building biometric verify payload: " + e.getMessage());
        }
        return post("/auth/verify-biometric", payload);
    }

    // ===================== 🔑 AUTHORIZE (موجودة مسبقاً) =====================

    public ApiResponse authorize(String epc, String contextType, JSONObject extraParams) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("epc", epc);
            payload.put("gate_id", gateId);
            payload.put("context", contextType != null ? contextType : "scan");
            if (extraParams != null) {
                java.util.Iterator<String> keys = extraParams.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    payload.put(key, extraParams.get(key));
                }
            }
        } catch (Exception ignored) {}
        return post("/authorize", payload);
    }

    // ===================== باقي الدوال كما هي =====================
    // (encodeItem, getGoldRatesAndSettings, checkout, getItemDetail,
    //  getDashboardSummary, getDashboardFull, startInventorySession,
    //  scanInventoryItem, closeInventorySession, heartbeat, إلخ...)

    // [... جميع الدوال الأخرى تبقى كما هي بدون تغيير ...]

    // ===================== Core HTTP Methods =====================

    private ApiResponse post(String endpoint, JSONObject payload) {
        HttpURLConnection conn = null;
        try {
            String fullUrl = apiUrl + endpoint;
            Log.d(TAG, "POST " + fullUrl);

            URL url = new URL(fullUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("X-MIRA-API-Key", apiKey);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);

            if (payload != null) {
                String jsonString = payload.toString();
                Log.d(TAG, "Payload: " + jsonString);
                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonString.getBytes("utf-8");
                    os.write(input, 0, input.length);
                }
            }

            return buildResponse(conn);

        } catch (Exception e) {
            Log.e(TAG, "POST Error: " + e.getMessage());
            return new ApiResponse(-1, null, e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private ApiResponse get(String endpoint) {
        HttpURLConnection conn = null;
        try {
            String fullUrl = apiUrl + endpoint;
            Log.d(TAG, "GET " + fullUrl);

            URL url = new URL(fullUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("X-MIRA-API-Key", apiKey);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            return buildResponse(conn);

        } catch (Exception e) {
            Log.e(TAG, "GET Error: " + e.getMessage());
            return new ApiResponse(-1, null, e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private ApiResponse buildResponse(HttpURLConnection conn) throws Exception {
        int responseCode = conn.getResponseCode();
        InputStream is = (responseCode >= 200 && responseCode < 300) ?
                conn.getInputStream() : conn.getErrorStream();

        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "utf-8"));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line.trim());
        }
        reader.close();

        String responseBody = response.toString();
        Log.d(TAG, "Response [" + responseCode + "]: " + responseBody);

        JSONObject json = null;
        try {
            json = new JSONObject(responseBody);
        } catch (Exception ignored) {}

        return new ApiResponse(responseCode, json, null);
    }

    // ===================== Getters =====================

    public String getGateId() { return gateId; }
    public String getApiUrl() { return apiUrl; }

    // ===================== ApiResponse =====================

    public static class ApiResponse {
        public final int code;
        public final JSONObject data;
        public final String error;
        public final boolean isSuccess;

        public ApiResponse(int code, JSONObject data, String error) {
            this.code = code;
            this.data = data;
            this.error = error;
            this.isSuccess = code >= 200 && code < 300;
        }

        public String optString(String key, String defaultValue) {
            if (data != null) return data.optString(key, defaultValue);
            return defaultValue;
        }

        public int optInt(String key, int defaultValue) {
            if (data != null) return data.optInt(key, defaultValue);
            return defaultValue;
        }

        public JSONObject optJSONObject(String key) {
            if (data != null) return data.optJSONObject(key);
            return null;
        }

        public JSONArray optJSONArray(String key) {
            if (data != null) return data.optJSONArray(key);
            return null;
        }
    }
}
