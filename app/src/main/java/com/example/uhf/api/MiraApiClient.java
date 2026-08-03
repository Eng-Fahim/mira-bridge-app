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

    // ===================== 🏷️ Item Encoding & Tagging (التكويد الجديد) =====================

    /**
     * 🟢 تكويد قطعة جديدة أو ربط RFID EPC بقطعة قائمة عبر إرسال JSON Payload
     */
    public ApiResponse encodeItem(JSONObject itemPayload) {
        return post("/encode-item", itemPayload);
    }

    /**
     * 🟢 دالة مساعدة مريحة لتكويد القطعة مباشرةً بالمعاملات التفصيلية
     */
    public ApiResponse encodeItem(String epc, String barcode, String title, String purityCode, 
                                  double grossWeight, double netWeight, double makingPerGram, 
                                  double profitMargin, String category, String location) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("epc", epc);
            payload.put("barcode", barcode != null ? barcode : "");
            payload.put("title", title != null ? title : "قطعة مجوهرات");
            payload.put("purity_code", purityCode != null ? purityCode : "21K");
            payload.put("gross_weight", grossWeight);
            payload.put("net_weight", netWeight);
            payload.put("making_per_gram", makingPerGram);
            payload.put("profit_margin", profitMargin);
            payload.put("category", category != null ? category : "مجوهرات");
            payload.put("location", location != null ? location : "المعرض الرئيسي");
            payload.put("gate_id", gateId);
        } catch (Exception e) {
            Log.e(TAG, "Error building encode payload: " + e.getMessage());
        }
        return encodeItem(payload);
    }

    // ===================== 📈 Gold Rates & Settings =====================

    /**
     * 🟢 جلب أسعار البورصة للعيارات والمصنعيات اللحظية
     */
    public ApiResponse getGoldRatesAndSettings() {
        return get("/gold-rates");
    }

    // ===================== 🔑 Authorize =====================

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

    // ===================== 🛒 POS Checkout =====================

    public ApiResponse checkout(JSONObject payload) {
        return post("/pos/checkout", payload);
    }

    public ApiResponse updateStock(JSONObject payload) {
        return post("/update-stock", payload);
    }

    // ===================== 🏷️ Item Detail =====================

    public ApiResponse getItemDetail(String code) {
        return get("/item/" + code);
    }

    // ===================== 📊 Dashboard =====================

    public ApiResponse getDashboardSummary() {
        return get("/dashboard/summary");
    }

    public ApiResponse getDashboardFull() {
        return get("/dashboard/full");
    }

    // ===================== ⚙️ Settings =====================

    public ApiResponse getSettings() {
        return get("/settings/" + gateId);
    }

    /**
     * 📊 جلب سجل المبيعات
     */
    public ApiResponse getSalesHistory() {
        return get("/pos/sales");
    }

    public ApiResponse saveSettings(JSONObject settings) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("gate_id", gateId);
            payload.put("settings", settings);
        } catch (Exception ignored) {}
        return post("/settings", payload);
    }

    // ===================== 📊 Inventory =====================

    public ApiResponse startInventorySession(String location, String type) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("location", location != null ? location : "default");
            payload.put("type", type != null ? type : "full");
        } catch (Exception ignored) {}
        return post("/inventory/start-session", payload);
    }

    public ApiResponse startBlindInventory(String location, String category) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("location", location != null ? location : "Main Showroom");
            if (category != null && !category.isEmpty()) {
                payload.put("category", category);
            }
        } catch (Exception ignored) {}
        return post("/inventory/blind-start", payload);
    }

    public ApiResponse scanInventoryItem(int sessionId, String code, String rssi) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("session_id", sessionId);
            payload.put("code", code);
            if (rssi != null) payload.put("rssi", rssi);
        } catch (Exception ignored) {}
        return post("/inventory/scan-item", payload);
    }

    public ApiResponse closeInventorySession(JSONObject payload) {
        return post("/inventory/close-session", payload);
    }

    public ApiResponse createInventorySnapshot(JSONObject payload) {
        return post("/inventory/create-snapshot", payload);
    }

    // ===================== 📸 Snapshots =====================

    public ApiResponse getLastSnapshot(String location) {
        return get("/inventory/last-snapshot?location=" + (location != null ? location : "Main Showroom"));
    }

    public ApiResponse getInventoryHistory(String location, int limit) {
        return get("/inventory/history/" + location + "?limit=" + limit);
    }

    // ===================== 💎 High Value Items =====================

    public ApiResponse getHighValueItems(String location, double threshold) {
        return get("/inventory/high-value?location=" + (location != null ? location : "Main Showroom") + "&threshold=" + threshold);
    }

    public ApiResponse getInventorySettings(String location) {
        return get("/inventory/settings/" + (location != null ? location : "default"));
    }

    // ===================== 🚪 Gates =====================

    public ApiResponse getGates() {
        return get("/gates");
    }

    public ApiResponse getGatesStats() {
        return get("/gates/stats");
    }

    // ===================== 💓 Heartbeat =====================

    public ApiResponse heartbeat(String status, int battery) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("gate_id", gateId);
            payload.put("status", status != null ? status : "online");
            if (battery > 0) payload.put("battery", battery);
        } catch (Exception ignored) {}
        return post("/heartbeat", payload);
    }

    // ===================== 🔧 Tag Operations =====================

    public ApiResponse spotlightTags(String[] tags) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("gate_id", gateId);
            payload.put("tags", new org.json.JSONArray(java.util.Arrays.asList(tags)));
        } catch (Exception ignored) {}
        return post("/spotlight", payload);
    }

    public ApiResponse programTag(String serial, String newEpc) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("serial", serial);
            payload.put("new_epc", newEpc);
            payload.put("gate_id", gateId);
        } catch (Exception ignored) {}
        return post("/program", payload);
    }

    public ApiResponse lockTag(String serial) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("serial", serial);
            payload.put("gate_id", gateId);
        } catch (Exception ignored) {}
        return post("/lock", payload);
    }

    public ApiResponse deactivateTag(String serial) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("serial", serial);
            payload.put("gate_id", gateId);
        } catch (Exception ignored) {}
        return post("/deactivate", payload);
    }

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

    // ===================== Getters =====================

    public String getGateId() {
        return gateId;
    }

    public String getApiUrl() {
        return apiUrl;
    }

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

        public JSONObject optJSONObject(String key) {
            if (data != null) return data.optJSONObject(key);
            return null;
        }
    }
}
