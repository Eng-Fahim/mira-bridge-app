package com.example.uhf.api;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 🔌 MIRA API Client - العميل المركزي للاتصال بـ MIRA ID
 * يستخدم من جميع واجهات التطبيق دون تكرار الكود
 */
public class MiraApiClient {

    private static final String TAG = "MiraApiClient";
    private static final String PREFS_NAME = "mira_settings";

    // الإعدادات الافتراضية
    private static final String DEFAULT_API_URL = "https://ams.ibreg.org/wp-json/mira-gate/v1";
    private static final String DEFAULT_API_KEY = "mira_gate_test071234567890abcdefghijklmnop";
    private static final String DEFAULT_GATE_ID = "handheld_c72";

    private final Context context;
    private final String apiUrl;
    private final String apiKey;
    private final String gateId;

    // Singleton
    private static MiraApiClient instance;

    /**
     * الحصول على نسخة Singleton من العميل
     */
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

    // ===================== طرق الاستدعاء الرئيسية =====================

    /**
     * 🔍 تفويض قطعة - يستخدم في المسح والتحقق
     * @param epc كود القطعة
     * @param context سياق الاستدعاء (pos_sale, scan, inventory)
     * @param extraParams معاملات إضافية (scale_weight, source, rssi)
     * @return ApiResponse
     */
    public ApiResponse authorize(String epc, String context, JSONObject extraParams) {
        return post("/authorize", buildAuthPayload(epc, context, extraParams));
    }

    /**
     * 🛒 إتمام عملية البيع
     * @param payload حمولة الطلب كاملة
     * @return ApiResponse
     */
    public ApiResponse checkout(JSONObject payload) {
        return post("/pos/checkout", payload);
    }

    /**
     * 📊 تحديث المخزون
     * @param payload حمولة الطلب
     * @return ApiResponse
     */
    public ApiResponse updateStock(JSONObject payload) {
        return post("/update-stock", payload);
    }

    /**
     * 🏷️ جلب تفاصيل قطعة
     * @param code كود القطعة
     * @return ApiResponse
     */
    public ApiResponse getItemDetail(String code) {
        return get("/item/" + code);
    }

    /**
     * 📊 لوحة القيادة
     * @return ApiResponse
     */
    public ApiResponse getDashboard() {
        return get("/dashboard/summary");
    }

    /**
     * ⚙️ جلب الإعدادات
     * @return ApiResponse
     */
    public ApiResponse getSettings() {
        return get("/settings/" + gateId);
    }

    // ===================== Core HTTP Methods =====================

    /**
     * POST Request
     */
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
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);

            if (payload != null) {
                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = payload.toString().getBytes("utf-8");
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

    /**
     * GET Request
     */
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
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            return buildResponse(conn);

        } catch (Exception e) {
            Log.e(TAG, "GET Error: " + e.getMessage());
            return new ApiResponse(-1, null, e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * بناء الاستجابة
     */
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
        JSONObject json = null;
        try {
            json = new JSONObject(responseBody);
        } catch (Exception ignored) {}

        return new ApiResponse(responseCode, json, null);
    }

    // ===================== Helpers =====================

    /**
     * بناء حمولة طلب authorize
     */
    private JSONObject buildAuthPayload(String epc, String context, JSONObject extraParams) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("epc", epc);
            payload.put("gate_id", gateId);
            payload.put("context", context != null ? context : "scan");

            if (extraParams != null) {
                // دمج المعاملات الإضافية
                java.util.Iterator<String> keys = extraParams.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    payload.put(key, extraParams.get(key));
                }
            }

            return payload;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /**
     * كائن الاستجابة الموحد
     */
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
            if (data != null) {
                return data.optString(key, defaultValue);
            }
            return defaultValue;
        }

        public JSONObject optJSONObject(String key) {
            if (data != null) {
                return data.optJSONObject(key);
            }
            return null;
        }
    }

    // ===================== Getters =====================

    public String getGateId() {
        return gateId;
    }

    public String getApiUrl() {
        return apiUrl;
    }
}
