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
    private static final String SESSION_PREFS = "MiraEnterpriseSession";

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

    // ===================== 🔑 AUTHENTICATION =====================

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

    public ApiResponse getCurrentUser() {
        return getWithAuth("/auth/me");
    }

    // ===================== 📊 INVENTORY SESSIONS =====================

    // جلب قائمة جلسات الجرد
    public ApiResponse getInventorySessionsList() {
        return getWithAuth("/inventory/sessions");
    }

    // جلب تفاصيل جلسة جرد معينة حسب الـ ID
    public ApiResponse getInventorySessionDetail(int sessionId) {
        return getWithAuth("/inventory/sessions/" + sessionId);
    }

    public ApiResponse refreshToken(String oldToken) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("token", oldToken);
            payload.put("gate_id", gateId);
        } catch (Exception e) {
            Log.e(TAG, "Error building refresh payload: " + e.getMessage());
        }
        return postWithAuth("/auth/refresh-token", payload);
    }

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

    // ===================== 🏢 ORGANIZATION PROFILE =====================

    public ApiResponse saveOrgProfile(JSONObject payload) {
        return postWithAuth("/org-profile/save", payload);
    }

    public ApiResponse getOrgProfile() {
        return get("/org-profile/get");
    }
    
    // ===================== 👥 USERS MANAGEMENT =====================

    public ApiResponse getUsers() {
        return getWithAuth("/auth/users");
    }

    public ApiResponse createUser(JSONObject payload) {
        return postWithAuth("/auth/users/create", payload);
    }

    public ApiResponse updateUser(JSONObject payload) {
        return postWithAuth("/auth/users/update", payload);
    }

    public ApiResponse deleteUser(int userId) {
        JSONObject p = new JSONObject();
        try { p.put("user_id", userId); } catch (Exception ignored) {}
        return postWithAuth("/auth/users/delete", p);
    }

    // ===================== 🔑 AUTHORIZE =====================

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

    // ===================== 📊 DASHBOARD =====================

    public ApiResponse getDashboardSummary() {
        return get("/dashboard/summary");
    }

    public ApiResponse getDashboardFull() {
        return get("/dashboard/full");
    }

    // ===================== 🛒 POS =====================

    public ApiResponse checkout(JSONObject payload) {
        return post("/pos/checkout", payload);
    }

    public ApiResponse getSalesHistory() {
        return get("/pos/sales");
    }

    // ===================== 🏷️ ITEM DETAIL =====================

    public ApiResponse getItemDetail(String code) {
        return get("/item/" + code);
    }

    // ===================== 📸 SNAPSHOTS =====================

    public ApiResponse getLastSnapshot(String location) {
        return get("/inventory/last-snapshot?location=" + (location != null ? location : "Main Showroom"));
    }

    public ApiResponse getInventoryHistory(String location, int limit) {
        return get("/inventory/history/" + location + "?limit=" + limit);
    }

    // ===================== 💎 HIGH VALUE ITEMS =====================

    public ApiResponse getHighValueItems(String location, double threshold) {
        return get("/inventory/high-value?location=" + (location != null ? location : "Main Showroom") + "&threshold=" + threshold);
    }

    // ===================== 📊 INVENTORY =====================

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

    public ApiResponse getInventorySettings(String location) {
        return get("/inventory/settings/" + (location != null ? location : "default"));
    }

    // ===================== 🏷️ ENCODE ITEM =====================

    public ApiResponse encodeItem(JSONObject itemPayload) {
        return post("/encode-item", itemPayload);
    }

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

    // ===================== 📈 GOLD RATES =====================

    public ApiResponse getGoldRatesAndSettings() {
        return get("/gold-rates");
    }

    // ===================== 🚪 GATES =====================

    public ApiResponse getGates() {
        return get("/gates");
    }

    public ApiResponse getGatesStats() {
        return get("/gates/stats");
    }

    // ===================== 💓 HEARTBEAT =====================

    public ApiResponse heartbeat(String status, int battery) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("gate_id", gateId);
            payload.put("status", status != null ? status : "online");
            if (battery > 0) payload.put("battery", battery);
        } catch (Exception ignored) {}
        return post("/heartbeat", payload);
    }

    // ===================== ⚙️ SETTINGS =====================

    public ApiResponse getSettings() {
        return get("/settings/" + gateId);
    }

    public ApiResponse saveSettings(JSONObject settings) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("gate_id", gateId);
            payload.put("settings", settings);
        } catch (Exception ignored) {}
        return post("/settings", payload);
    }

    // ===================== 🔧 TAG OPERATIONS =====================

    public ApiResponse spotlightTags(String[] tags) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("gate_id", gateId);
            payload.put("tags", new JSONArray(java.util.Arrays.asList(tags)));
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

    // ===================== CORE HTTP METHODS =====================

    private String getAuthToken() {
        SharedPreferences prefs = context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE);
        return prefs.getString("authToken", "");
    }

    private ApiResponse getWithAuth(String endpoint) {
        HttpURLConnection conn = null;
        try {
            String fullUrl = apiUrl + endpoint;
            Log.d(TAG, "GET(Auth) " + fullUrl);

            URL url = new URL(fullUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("X-MIRA-API-Key", apiKey);
            
            String token = getAuthToken();
            if (!token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setRequestProperty("X-MIRA-Auth-Token", token);
            }
            
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            return buildResponse(conn);

        } catch (Exception e) {
            Log.e(TAG, "GET(Auth) Error: " + e.getMessage());
            return new ApiResponse(-1, null, e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private ApiResponse postWithAuth(String endpoint, JSONObject payload) {
        HttpURLConnection conn = null;
        try {
            String fullUrl = apiUrl + endpoint;
            Log.d(TAG, "POST(Auth) " + fullUrl);

            URL url = new URL(fullUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("X-MIRA-API-Key", apiKey);
            
            String token = getAuthToken();
            if (!token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setRequestProperty("X-MIRA-Auth-Token", token);
            }
            
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
            Log.e(TAG, "POST(Auth) Error: " + e.getMessage());
            return new ApiResponse(-1, null, e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

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

    // ===================== 🏢 BRANCHES MANAGEMENT =====================

/**
 * جلب قائمة الفروع
 */
public ApiResponse getBranches() {
    return getWithAuth("/branches");
}

/**
 * جلب تفاصيل فرع محدد
 */
public ApiResponse getBranch(int branchId) {
    return getWithAuth("/branches/" + branchId);
}

/**
 * إنشاء فرع جديد
 */
public ApiResponse createBranch(JSONObject payload) {
    return postWithAuth("/branches", payload);
}

/**
 * تحديث بيانات فرع
 */
public ApiResponse updateBranch(int branchId, JSONObject payload) {
    return postWithAuth("/branches/" + branchId, payload);
}

/**
 * جلب مخزون فرع
 */
public ApiResponse getBranchInventory(int branchId) {
    return getWithAuth("/branches/" + branchId + "/inventory");
}

/**
 * جلب مستخدمي فرع
 */
public ApiResponse getBranchUsers(int branchId) {
    return getWithAuth("/branches/" + branchId + "/users");
}

/**
 * جلب إعدادات فرع
 */
public ApiResponse getBranchSettings(int branchId) {
    return getWithAuth("/branches/" + branchId + "/settings");
}

/**
 * حفظ إعدادات فرع
 */
public ApiResponse saveBranchSettings(int branchId, JSONObject settings) {
    return postWithAuth("/branches/" + branchId + "/settings", settings);
}

// ===================== 📦 TRANSFERS =====================

/**
 * جلب قائمة التحويلات
 */
public ApiResponse getTransfers(int branchId, String status) {
    String url = "/transfers?branch_id=" + branchId;
    if (status != null && !status.isEmpty()) {
        url += "&status=" + status;
    }
    return getWithAuth(url);
}

/**
 * إنشاء تحويل جديد
 */
public ApiResponse createTransfer(JSONObject payload) {
    return postWithAuth("/transfers", payload);
}

/**
 * جلب تفاصيل تحويل
 */
public ApiResponse getTransfer(int transferId) {
    return getWithAuth("/transfers/" + transferId);
}

/**
 * اعتماد تحويل
 */
public ApiResponse approveTransfer(int transferId) {
    return postWithAuth("/transfers/" + transferId + "/approve", null);
}

/**
 * استلام تحويل
 */
public ApiResponse receiveTransfer(int transferId) {
    return postWithAuth("/transfers/" + transferId + "/receive", null);
}

/**
 * مسح قطعة في تحويل
 */
public ApiResponse scanTransferItem(int transferId, String epc) {
    JSONObject payload = new JSONObject();
    try {
        payload.put("epc", epc);
    } catch (Exception ignored) {}
    return postWithAuth("/transfers/" + transferId + "/scan", payload);
}

    // ===================== GETTERS =====================

    public String getGateId() { return gateId; }
    public String getApiUrl() { return apiUrl; }

    // ===================== API RESPONSE CLASS =====================

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
