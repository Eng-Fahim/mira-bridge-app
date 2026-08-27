package com.example.uhf.api;

import android.content.Context;
import android.util.Log;

import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraServerConfig;
import com.example.uhf.model.ApiProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

public class MiraApiClient {

    private static final String TAG = "MiraApiClient";
    private static final String SESSION_PREFS = "MiraEnterpriseSession";

    private final Context context;
    private final MiraServerConfig serverConfig;

    // ✅ قيم ديناميكية تتحدث بعد تسجيل الدخول
    private String apiUrl;
    private String apiKey;
    private String gateId;

    private static volatile MiraApiClient instance;

    public static synchronized MiraApiClient getInstance(Context context) {
        if (instance == null) {
            instance = new MiraApiClient(context.getApplicationContext());
        }
        return instance;
    }

    private MiraApiClient(Context context) {
        this.context = context;
        this.serverConfig = MiraServerConfig.getInstance(context);
        refreshCredentials();
    }

    /**
     * ✅ تحديث القيم من MiraServerConfig
     */
    private void refreshCredentials() {
        this.apiUrl = serverConfig.getBaseUrl() + "mira-gate/v1";
        this.apiKey = serverConfig.getApiKey();
        this.gateId = serverConfig.getGateId();

        Log.d(TAG, "Credentials: URL=" + apiUrl + ", Key=" + (apiKey.isEmpty() ? "EMPTY" : "SET") + ", Gate=" + (gateId.isEmpty() ? "EMPTY" : gateId));
    }

    /**
     * إعادة تحميل الإعدادات بعد تغيير الدومين
     */
    public static synchronized void reloadInstance(Context context) {
        instance = new MiraApiClient(context.getApplicationContext());
    }

    private String getEffectiveApiKey() {
        if (apiKey == null || apiKey.isEmpty()) {
            Log.e(TAG, "API Key is empty! User must login first.");
            return "";
        }
        return apiKey;
    }

    private String getEffectiveGateId() {
        if (gateId == null || gateId.isEmpty()) {
            Log.e(TAG, "Gate ID is empty! User must login first.");
            return "";
        }
        return gateId;
    }

    private String getAuthToken() {
        return serverConfig.getAuthToken();
    }

    public boolean hasValidCredentials() {
        return !getEffectiveApiKey().isEmpty() && !getEffectiveGateId().isEmpty();
    }

    public String getGateId() { return getEffectiveGateId(); }
    public String getApiUrl() { return apiUrl; }

    /**
     * ✅ توحيد إضافة Headers للطلبات لتجنب التكرار والخطأ
     */
    private void applyStandardHeaders(HttpURLConnection conn, boolean requiresAuth) {
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

        String effectiveKey = getEffectiveApiKey();
        if (!effectiveKey.isEmpty()) {
            conn.setRequestProperty("X-MIRA-API-Key", effectiveKey);
        }

        if (requiresAuth) {
            String token = getAuthToken();
            if (!token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setRequestProperty("X-MIRA-Auth-Token", token);
            }
        }
    }

    // ===================== CORE HTTP METHODS =====================

    public ApiResponse getWithAuth(String endpoint) {
        if (!hasValidCredentials()) {
            Log.w(TAG, "⚠️ No valid credentials for: " + endpoint + " - trying anyway");
        }

        HttpURLConnection conn = null;
        try {
            String fullUrl = apiUrl + endpoint;
            URL url = new URL(fullUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            applyStandardHeaders(conn, true);

            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            return buildResponse(conn);
        } catch (Exception e) {
            Log.e(TAG, "GET Auth Error [" + endpoint + "]: " + e.getMessage());
            return new ApiResponse(-1, null, e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public ApiResponse postWithAuth(String endpoint, JSONObject payload) {
        if (!hasValidCredentials()) {
            Log.e(TAG, "Attempting API call without valid credentials: " + endpoint);
            return new ApiResponse(401, null, "Unauthorized - Please login first");
        }

        HttpURLConnection conn = null;
        try {
            String fullUrl = apiUrl + endpoint;
            URL url = new URL(fullUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            applyStandardHeaders(conn, true);

            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);

            if (payload != null) {
                byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(input, 0, input.length);
                }
            }
            return buildResponse(conn);
        } catch (Exception e) {
            Log.e(TAG, "POST Auth Error [" + endpoint + "]: " + e.getMessage());
            return new ApiResponse(-1, null, e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private ApiResponse post(String endpoint, JSONObject payload) {
        HttpURLConnection conn = null;
        try {
            String fullUrl = apiUrl + endpoint;
            URL url = new URL(fullUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            applyStandardHeaders(conn, false);

            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);

            if (payload != null) {
                byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(input, 0, input.length);
                }
            }
            return buildResponse(conn);
        } catch (Exception e) {
            Log.e(TAG, "POST Error [" + endpoint + "]: " + e.getMessage());
            return new ApiResponse(-1, null, e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private ApiResponse get(String endpoint) {
        HttpURLConnection conn = null;
        try {
            String fullUrl = apiUrl + endpoint;
            URL url = new URL(fullUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            applyStandardHeaders(conn, false);

            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            return buildResponse(conn);
        } catch (Exception e) {
            Log.e(TAG, "GET Error [" + endpoint + "]: " + e.getMessage());
            return new ApiResponse(-1, null, e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private ApiResponse buildResponse(HttpURLConnection conn) throws Exception {
    int responseCode = conn.getResponseCode();
    InputStream is = (responseCode >= 200 && responseCode < 300) ? conn.getInputStream() : conn.getErrorStream();

    if (is == null) {
        return new ApiResponse(responseCode, null, "Empty response");
    }

    StringBuilder response = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line.trim());
        }
    }

    String responseBody = response.toString();
    JSONObject json = null;
    try {
        if (!responseBody.isEmpty()) {
            json = new JSONObject(responseBody);
        }
    } catch (Exception ignored) {}

    // ✅ إنشاء ApiResponse مع تخزين النص الخام
    ApiResponse apiResponse = new ApiResponse(responseCode, json, responseCode >= 400 ? responseBody : null);
    apiResponse.setBody(responseBody); // ✅ حفظ النص الخام
    
    return apiResponse;
}

    // ===================== 🔑 AUTHENTICATION =====================

    public ApiResponse login(String username, String password, String deviceId) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("username", username);
            payload.put("password", password);
            payload.put("device_id", deviceId != null ? deviceId : "");
            payload.put("gate_id", getEffectiveGateId());
            payload.put("app_version", "MIRA-ANDROID-v3.0");
        } catch (Exception e) {
            Log.e(TAG, "Error building login payload: " + e.getMessage());
        }

        ApiResponse response = post("/auth/login", payload);

        if (response.isSuccess && response.data != null) {
            String newApiKey = response.data.optString("api_key", "");
            String newGateId = response.data.optString("gate_id", "");
            String authToken = response.data.optString("token", "");

            if (!newApiKey.isEmpty()) {
                String effectiveGateId = !newGateId.isEmpty() ? newGateId :
                        (deviceId != null && !deviceId.isEmpty() ? deviceId : "");

                serverConfig.saveSession(newApiKey, effectiveGateId, authToken);

                com.example.uhf.tools.SessionManager sessionManager =
                        new com.example.uhf.tools.SessionManager(context);
                sessionManager.saveApiKey(newApiKey);
                sessionManager.saveGateId(effectiveGateId);

                refreshCredentials();
                Log.d(TAG, "✅ API Key saved to BOTH places");
            } else if (!authToken.isEmpty()) {
                String currentApiKey = getEffectiveApiKey();
                String currentGateId = getEffectiveGateId();

                String effectiveGateId = !currentGateId.isEmpty() ? currentGateId :
                        (deviceId != null && !deviceId.isEmpty() ? deviceId : "");

                serverConfig.saveSession(
                        !currentApiKey.isEmpty() ? currentApiKey : "",
                        effectiveGateId,
                        authToken
                );
                refreshCredentials();
                Log.d(TAG, "✅ Using existing API Key with gate_id: " + effectiveGateId);
            }
        }

        return response;
    }

    public ApiResponse reconnectDevice(String username, String password, String gateId) {
        try {
            JSONObject params = new JSONObject();
            params.put("username", username);
            params.put("password", password);
            params.put("gate_id", gateId);

            ApiResponse response = post("/auth/reconnect", params);
            if (response != null && response.isSuccess && response.data != null) {
                String newApiKey = response.data.optString("api_key", "");
                String newGateId = response.data.optString("gate_id", gateId);
                String authToken = response.data.optString("token", "");

                if (!newApiKey.isEmpty() || !authToken.isEmpty()) {
                    serverConfig.saveSession(newApiKey, newGateId, authToken);
                    refreshCredentials();
                }
            }
            return response;
        } catch (Exception e) {
            Log.e(TAG, "Reconnect error: " + e.getMessage());
            return new ApiResponse(-1, null, e.getMessage());
        }
    }

    public ApiResponse validateToken(String token) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("token", token);
            payload.put("gate_id", getEffectiveGateId());
        } catch (Exception e) {
            Log.e(TAG, "Error building validate payload: " + e.getMessage());
        }
        return post("/auth/validate-token", payload);
    }

    public ApiResponse logout(String token) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("token", token);
            payload.put("gate_id", getEffectiveGateId());
        } catch (Exception e) {
            Log.e(TAG, "Error building logout payload: " + e.getMessage());
        }
        return post("/auth/logout", payload);
    }

    public ApiResponse getCurrentUser() {
        return getWithAuth("/auth/me");
    }

    public ApiResponse refreshToken(String oldToken) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("token", oldToken);
            payload.put("gate_id", getEffectiveGateId());
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
            payload.put("gate_id", getEffectiveGateId());
        } catch (Exception ignored) {}
        return post("/auth/register-biometric", payload);
    }

    public ApiResponse verifyBiometric(String token, String signedChallenge) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("token", token);
            payload.put("signed_challenge", signedChallenge);
            payload.put("gate_id", getEffectiveGateId());
        } catch (Exception ignored) {}
        return post("/auth/verify-biometric", payload);
    }

    // ===================== 🏢 PROFILE & ORG =====================

    public ApiResponse getOrgProfile() {
        return getWithAuth("/profile/get");
    }

    public ApiResponse saveOrgProfile(JSONObject payload) {
        return postWithAuth("/profile/update", payload);
    }

    public ApiResponse updateUserProfile(JSONObject payload) {
        return postWithAuth("/profile/update-user", payload);
    }
    public ApiResponse uploadUserAvatar(JSONObject payload) {
    return postWithAuth("/profile/upload-avatar", payload);
}

// ✅✅✅ أضف هذه الدالة لتغيير كلمة المرور
public ApiResponse changePassword(String currentPassword, String newPassword) {
    JSONObject payload = new JSONObject();
    try {
        payload.put("current_password", currentPassword);
        payload.put("new_password", newPassword);
    } catch (Exception ignored) {}
    return postWithAuth("/auth/change-password", payload);
}

    // ===================== 👥 USERS =====================

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
            payload.put("gate_id", getEffectiveGateId());
            payload.put("context", contextType != null ? contextType : "scan");
            if (extraParams != null) {
                Iterator<String> keys = extraParams.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    payload.put(key, extraParams.get(key));
                }
            }
        } catch (Exception ignored) {}
        return post("/authorize", payload);
    }

    // ===================== 📡 PING =====================

    public boolean pingApi() {
        HttpURLConnection conn = null;
        try {
            String baseUrl = this.apiUrl;
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }

            URL url = new URL(baseUrl + "/ping");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            applyStandardHeaders(conn, true);

            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            int responseCode = conn.getResponseCode();
            return (responseCode >= 200 && responseCode < 400);
        } catch (Exception e) {
            Log.e(TAG, "Ping error: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ===================== 📊 DASHBOARD =====================

    public ApiResponse getDashboardSummary() {
        return getWithAuth("/dashboard/summary");
    }

    public ApiResponse getDashboardFull(int branchId) {
        String endpoint = "/dashboard/full";
        if (branchId > 0) {
            endpoint += "?branch_id=" + branchId;
        }
        return getWithAuth(endpoint);
    }

    public ApiResponse getDashboardFull() {
        return getDashboardFull(0);
    }

    // ===================== 🛒 POS =====================

    public ApiResponse checkout(JSONObject payload) {
        return postWithAuth("/pos/checkout", payload);
    }

    public ApiResponse processRefund(String epc, String serialNo, String reason) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("epc", epc != null ? epc : "");
            payload.put("serial_no", serialNo != null ? serialNo : "");
            payload.put("reason", reason != null ? reason : "مرتجع مبيعات");
            payload.put("gate_id", getEffectiveGateId());
        } catch (Exception ignored) {}
        return postWithAuth("/pos/refund", payload);
    }

    public ApiResponse getSalesHistory() {
    return getWithAuth("/pos/sales");
}

// ✅ دالة جديدة للتحميل التدريجي (Pagination)
public ApiResponse getSalesHistoryPaginated(int page, int pageSize) {
    String endpoint = "/pos/sales?page=" + page + "&limit=" + pageSize + "&per_page=" + pageSize;
    return getWithAuth(endpoint);
}

// ✅ دالة للبحث مع التحميل التدريجي
public ApiResponse searchSalesHistory(String query, int page, int pageSize) {
    try {
        String encodedQuery = URLEncoder.encode(query, "UTF-8");
        String endpoint = "/pos/sales?page=" + page + "&limit=" + pageSize + "&per_page=" + pageSize + "&search=" + encodedQuery;
        return getWithAuth(endpoint);
    } catch (Exception e) {
        // في حالة فشل الترميز، نستخدم بدون ترميز
        String endpoint = "/pos/sales?page=" + page + "&limit=" + pageSize + "&per_page=" + pageSize + "&search=" + query;
        return getWithAuth(endpoint);
    }
}

// ✅ دالة للفلترة حسب نوع الدفع مع التحميل التدريجي
public ApiResponse getSalesHistoryByPaymentMethod(String paymentMethod, int page, int pageSize) {
    String endpoint = "/pos/sales?page=" + page + "&limit=" + pageSize + "&per_page=" + pageSize + "&payment_method=" + paymentMethod;
    return getWithAuth(endpoint);
}

// ✅ دالة لجلب إحصائيات المبيعات (الإجمالي والعدد)
public ApiResponse getSalesStats() {
    return getWithAuth("/pos/sales/stats");
}

// ✅ دالة لجلب المبيعات حسب نطاق تاريخ
public ApiResponse getSalesHistoryByDateRange(String fromDate, String toDate, int page, int pageSize) {
    String endpoint = "/pos/sales?page=" + page + "&limit=" + pageSize + "&per_page=" + pageSize;
    
    if (fromDate != null && !fromDate.isEmpty()) {
        endpoint += "&from=" + fromDate;
    }
    
    if (toDate != null && !toDate.isEmpty()) {
        endpoint += "&to=" + toDate;
    }
    
    return getWithAuth(endpoint);
}

// ✅ دالة شاملة للفلترة المتقدمة
public ApiResponse getSalesHistoryAdvanced(String search, String paymentMethod, String fromDate, String toDate, int page, int pageSize) {
    StringBuilder endpoint = new StringBuilder("/pos/sales?page=" + page + "&limit=" + pageSize + "&per_page=" + pageSize);
    
    try {
        if (search != null && !search.isEmpty()) {
            endpoint.append("&search=").append(URLEncoder.encode(search, "UTF-8"));
        }
        
        if (paymentMethod != null && !paymentMethod.isEmpty() && !paymentMethod.equals("ALL")) {
            endpoint.append("&payment_method=").append(paymentMethod);
        }
        
        if (fromDate != null && !fromDate.isEmpty()) {
            endpoint.append("&from=").append(fromDate);
        }
        
        if (toDate != null && !toDate.isEmpty()) {
            endpoint.append("&to=").append(toDate);
        }
    } catch (Exception e) {
        Log.e(TAG, "Error building advanced search URL", e);
    }
    
    return getWithAuth(endpoint.toString());
}

    // ===================== 🏷️ ITEM =====================

    public ApiResponse getItemDetail(String code) {
        return get("/item/" + code);
    }

    public ApiResponse uploadItemImage(int itemId, File imageFile, boolean isPrimary, String altText) {
        HttpURLConnection conn = null;
        try {
            String fullUrl = apiUrl + "/items/" + itemId + "/images";
            URL url = new URL(fullUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            applyStandardHeaders(conn, true);

            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);

            String boundary = "----MiraBoundary" + System.currentTimeMillis();
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            try (OutputStream os = conn.getOutputStream()) {
                StringBuilder sb = new StringBuilder();
                sb.append("--").append(boundary).append("\r\n");
                sb.append("Content-Disposition: form-data; name=\"image\"; filename=\"")
                        .append(imageFile.getName()).append("\"\r\n");
                sb.append("Content-Type: image/jpeg\r\n\r\n");
                os.write(sb.toString().getBytes(StandardCharsets.UTF_8));

                try (FileInputStream fis = new FileInputStream(imageFile)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                    }
                }

                sb = new StringBuilder();
                sb.append("\r\n--").append(boundary).append("\r\n");
                sb.append("Content-Disposition: form-data; name=\"is_primary\"\r\n\r\n");
                sb.append(isPrimary ? "1" : "0");
                os.write(sb.toString().getBytes(StandardCharsets.UTF_8));

                sb = new StringBuilder();
                sb.append("\r\n--").append(boundary).append("\r\n");
                sb.append("Content-Disposition: form-data; name=\"alt_text\"\r\n\r\n");
                sb.append(altText != null ? altText : "");
                os.write(sb.toString().getBytes(StandardCharsets.UTF_8));

                os.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            return buildResponse(conn);
        } catch (Exception e) {
            Log.e(TAG, "uploadItemImage error: " + e.getMessage());
            return new ApiResponse(-1, null, e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ===================== 📸 SNAPSHOTS & INVENTORY REPORTS =====================

    public ApiResponse getLastSnapshot(String location) {
        return get("/inventory/last-snapshot?location=" + (location != null ? location : "Main Showroom"));
    }

    public ApiResponse getInventoryHistory(String location, int limit) {
        return get("/inventory/history/" + location + "?limit=" + limit);
    }

    public ApiResponse getInventoryReports(int branchId, int page, int perPage) {
        String endpoint = "/inventory/sessions?branch_id=" + branchId + "&page=" + page + "&per_page=" + perPage;
        return getWithAuth(endpoint);
    }

    public ApiResponse getHighValueItems(String location, double threshold) {
        return get("/inventory/high-value?location=" + (location != null ? location : "Main Showroom") + "&threshold=" + threshold);
    }

    // ===================== 📊 INVENTORY =====================

    public ApiResponse getInventorySessionsList() {
        return getWithAuth("/inventory/sessions");
    }

    public ApiResponse getInventorySessionDetail(int sessionId) {
        return getWithAuth("/inventory/sessions/" + sessionId);
    }

    public ApiResponse startInventorySession(String location, String type) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("location", location != null ? location : "default");
            payload.put("type", type != null ? type : "full");
            MiraBranchesManager bm = MiraBranchesManager.getInstance(context);
            payload.put("branch_id", bm.getActiveBranchId());
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
        return postWithAuth("/inventory/close-session", payload);
    }

    public ApiResponse compareInventorySessions(int sessionId1, int sessionId2) {
        String endpoint = "/inventory/compare-sessions?session_id_1=" + sessionId1 + "&session_id_2=" + sessionId2;
        return getWithAuth(endpoint);
    }

    public ApiResponse createInventorySnapshot(JSONObject payload) {
        return post("/inventory/create-snapshot", payload);
    }

    public ApiResponse getInventorySettings(String location) {
        return get("/inventory/settings/" + (location != null ? location : "default"));
    }

    public void submitInventoryAuditReport(JSONObject payload) {
        postWithAuth("/inventory/submit-audit", payload);
    }

    // ===================== 🏷️ ENCODE =====================

    public ApiResponse encodeItem(JSONObject itemPayload) {
        return postWithAuth("/encode-item", itemPayload);
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
            payload.put("gate_id", getEffectiveGateId());
        } catch (Exception ignored) {}
        return encodeItem(payload);
    }

    // ===================== 🚪 GATES =====================

    public ApiResponse createGate(JSONObject gateData) {
    return postWithAuth("/gates", gateData);
}

public ApiResponse getGates() {
    return getWithAuth("/gates");
}

/**
 * ✅✅✅ جلب بوابات فرع محدد - دالة جديدة
 */
public ApiResponse getGatesByBranch(int branchId) {
    return getWithAuth("/gates?branch_id=" + branchId);
}

public ApiResponse getGatesStats() {
    return get("/gates/stats");
}

    // ===================== 💓 HEARTBEAT & SETTINGS =====================

    public ApiResponse heartbeat(String status, int battery) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("gate_id", getEffectiveGateId());
            payload.put("status", status != null ? status : "online");
            if (battery > 0) payload.put("battery", battery);
        } catch (Exception ignored) {}
        return post("/heartbeat", payload);
    }

    public ApiResponse getSettings() {
        return get("/settings/" + getEffectiveGateId());
    }

    public ApiResponse saveSettings(JSONObject settings) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("gate_id", getEffectiveGateId());
            payload.put("settings", settings);
        } catch (Exception ignored) {}
        return post("/settings", payload);
    }

    // ===================== 🔧 TAG OPERATIONS =====================

    public ApiResponse spotlightTags(String[] tags) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("gate_id", getEffectiveGateId());
            payload.put("tags", new JSONArray(Arrays.asList(tags)));
        } catch (Exception ignored) {}
        return post("/spotlight", payload);
    }

    public ApiResponse programTag(String serial, String newEpc) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("serial", serial);
            payload.put("new_epc", newEpc);
            payload.put("gate_id", getEffectiveGateId());
        } catch (Exception ignored) {}
        return post("/program", payload);
    }

    public ApiResponse lockTag(String serial) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("serial", serial);
            payload.put("gate_id", getEffectiveGateId());
        } catch (Exception ignored) {}
        return post("/lock", payload);
    }

    public ApiResponse deactivateTag(String serial) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("serial", serial);
            payload.put("gate_id", getEffectiveGateId());
        } catch (Exception ignored) {}
        return post("/deactivate", payload);
    }

    // ===================== 🏢 BRANCHES =====================

    public ApiResponse getBranches() {
        return getWithAuth("/branches");
    }

    public ApiResponse getBranch(int branchId) {
        return getWithAuth("/branches/" + branchId);
    }

    public ApiResponse createBranch(JSONObject payload) {
        return postWithAuth("/branches", payload);
    }

    public ApiResponse updateBranch(int branchId, JSONObject payload) {
        return postWithAuth("/branches/" + branchId, payload);
    }

    public ApiResponse getBranchInventory(int branchId) {
        return getWithAuth("/branches/" + branchId + "/inventory");
    }

    public ApiResponse getBranchUsers(int branchId) {
        return getWithAuth("/branches/" + branchId + "/users");
    }

    public ApiResponse getBranchSettings(int branchId) {
        return getWithAuth("/branches/" + branchId + "/settings");
    }

    public ApiResponse saveBranchSettings(int branchId, JSONObject settings) {
        return postWithAuth("/branches/" + branchId + "/settings", settings);
    }

    public ApiResponse getBranchItemsByCategory(int branchId, String category) {
        String endpoint = "/branches/" + branchId + "/inventory/items";
        if (category != null && !category.isEmpty()) {
            try {
                endpoint += "?category=" + URLEncoder.encode(category, "UTF-8");
            } catch (Exception e) {
                endpoint += "?category=" + category;
            }
        }
        return getWithAuth(endpoint);
    }

    public ApiResponse getBranchItemsList(int branchId) {
        return getWithAuth("/branches/" + branchId + "/inventory/items");
    }

    // ===================== 📦 TRANSFERS =====================

    public ApiResponse getTransfers(int branchId, String status) {
        String url = "/transfers?branch_id=" + branchId;
        if (status != null && !status.isEmpty()) {
            url += "&status=" + status;
        }
        return getWithAuth(url);
    }

    public ApiResponse createTransfer(JSONObject payload) {
        return postWithAuth("/transfers", payload);
    }

    public ApiResponse getTransfer(int transferId) {
        return getWithAuth("/transfers/" + transferId);
    }

    public ApiResponse getTransferDetails(int transferId) {
        return getWithAuth("/transfers/" + transferId);
    }

    public ApiResponse approveTransfer(int transferId) {
        return postWithAuth("/transfers/" + transferId + "/approve", new JSONObject());
    }

    public ApiResponse receiveTransfer(int transferId) {
        return postWithAuth("/transfers/" + transferId + "/receive", new JSONObject());
    }

    public ApiResponse receiveTransferWithAudit(JSONObject payload) {
        return postWithAuth("/transfers/receive-audit", payload);
    }

    public ApiResponse scanTransferItem(int transferId, String epc) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("epc", epc);
        } catch (Exception ignored) {}
        return postWithAuth("/transfers/" + transferId + "/scan", payload);
    }

    public ApiResponse updateTransfer(int transferId, JSONObject payload) {
        return postWithAuth("/transfers/" + transferId, payload);
    }

    public ApiResponse addItemsToTransfer(int transferId, JSONArray epcs) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("items", epcs);
        } catch (Exception ignored) {}
        return postWithAuth("/transfers/" + transferId + "/items", payload);
    }

    // ===================== 💰 ACCOUNTING =====================

    public ApiResponse getJournalEntries(String branchId) {
        String url = "/accounting/journal-entries";
        if (branchId != null && !branchId.isEmpty()) {
            url += "?branch_id=" + branchId;
        }
        return getWithAuth(url);
    }

    public ApiResponse getJournalEntryDetail(String entryId) {
        return getWithAuth("/accounting/journal-entries/" + entryId);
    }

    public ApiResponse createJournalEntry(JSONObject payload) {
        return postWithAuth("/accounting/journal-entries/create", payload);
    }

    public ApiResponse getChartOfAccounts() {
        return getWithAuth("/accounting/chart-of-accounts");
    }

    public ApiResponse createAccount(JSONObject payload) {
        return postWithAuth("/accounting/chart-of-accounts/create", payload);
    }

    public ApiResponse getTrialBalance(String branchId) {
        String url = "/accounting/trial-balance";
        if (branchId != null && !branchId.isEmpty()) {
            url += "?branch_id=" + branchId;
        }
        return getWithAuth(url);
    }

    public ApiResponse getAccountingSummary(String branchId) {
        String url = "/accounting/summary";
        if (branchId != null && !branchId.isEmpty()) {
            url += "?branch_id=" + branchId;
        }
        return getWithAuth(url);
    }

    public ApiResponse getVouchers(String branchId, String voucherType) {
        String url = "/accounting/vouchers";
        boolean hasParam = false;
        if (branchId != null && !branchId.isEmpty()) {
            url += "?branch_id=" + branchId;
            hasParam = true;
        }
        if (voucherType != null && !voucherType.isEmpty()) {
            url += (hasParam ? "&" : "?") + "voucher_type=" + voucherType;
        }
        return getWithAuth(url);
    }

    public ApiResponse createVoucher(JSONObject payload) {
        return postWithAuth("/accounting/vouchers/create", payload);
    }

    public ApiResponse getFiscalYears() {
        return getWithAuth("/accounting/fiscal-years");
    }

    public ApiResponse createFiscalYear(JSONObject payload) {
        return postWithAuth("/accounting/fiscal-years/create", payload);
    }

    public ApiResponse closeFiscalYear(int yearId) {
        return postWithAuth("/accounting/fiscal-years/" + yearId + "/close", new JSONObject());
    }

    public ApiResponse getOpeningBalances() {
        return getWithAuth("/accounting/opening-balances");
    }

    public ApiResponse setOpeningBalance(int accountId, double cashBalance, double goldBalance) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("account_id", accountId);
            payload.put("opening_balance", cashBalance);
            payload.put("opening_gold", goldBalance);
            return postWithAuth("/accounting/opening-balances/set", payload);
        } catch (Exception e) {
            return new ApiResponse(-1, null, e.getMessage());
        }
    }

    public ApiResponse getAccountingAuditLog() {
        return getWithAuth("/accounting/audit-log?limit=50");
    }

    // ===================== 📈 ACCOUNTING REPORTS & GENERAL REPORTS =====================

    public ApiResponse getBalanceSheet(String branchId, String asOfDate) {
        String url = "/accounting/balance-sheet";
        boolean hasParam = false;
        if (branchId != null && !branchId.isEmpty()) {
            url += "?branch_id=" + branchId;
            hasParam = true;
        }
        if (asOfDate != null && !asOfDate.isEmpty()) {
            url += (hasParam ? "&" : "?") + "as_of=" + asOfDate;
        }
        return getWithAuth(url);
    }

    public ApiResponse getIncomeStatement(String branchId, String fromDate, String toDate) {
        String url = "/accounting/income-statement?from=" + fromDate + "&to=" + toDate;
        if (branchId != null && !branchId.isEmpty()) {
            url += "&branch_id=" + branchId;
        }
        return getWithAuth(url);
    }

    public ApiResponse getGoldMovementReport(String branchId, String fromDate, String toDate) {
        String url = "/accounting/gold-movement?from=" + fromDate + "&to=" + toDate;
        if (branchId != null && !branchId.isEmpty()) {
            url += "&branch_id=" + branchId;
        }
        return getWithAuth(url);
    }

    public ApiResponse getReportData(String reportType, String fromDate, String toDate, int branchId) {
        try {
            String cleanType = reportType != null ? reportType.replace("\"", "").replace("\\", "").replace("'", "").trim() : "dashboard";

            StringBuilder endpoint = new StringBuilder("/reports-app?type=").append(URLEncoder.encode(cleanType, "UTF-8"));
            if (fromDate != null && !fromDate.isEmpty()) endpoint.append("&from=").append(URLEncoder.encode(fromDate, "UTF-8"));
            if (toDate != null && !toDate.isEmpty()) endpoint.append("&to=").append(URLEncoder.encode(toDate, "UTF-8"));
            if (branchId > 0) endpoint.append("&branch_id=").append(branchId);

            Log.d(TAG, "📊 Reports URL: " + endpoint.toString());
            return getWithAuth(endpoint.toString());
        } catch (Exception e) {
            return new ApiResponse(-1, null, e.getMessage());
        }
    }

    // ===================== 📈 GOLD & CURRENCY =====================

    public List<ApiProvider> getActiveProviders() {
        List<ApiProvider> providers = new ArrayList<>();
        try {
            ApiResponse response = getWithAuth("/gold/providers/active");
            if (response.isSuccess && response.data != null) {
                JSONArray providersArray = response.data.optJSONArray("providers");
                if (providersArray == null) {
                    providersArray = response.data.optJSONArray("data");
                }

                if (providersArray != null) {
                    for (int i = 0; i < providersArray.length(); i++) {
                        JSONObject providerJson = providersArray.getJSONObject(i);
                        ApiProvider provider = new ApiProvider();
                        provider.setId(providerJson.optInt("id", 0));
                        provider.setProviderName(providerJson.optString("name", ""));
                        provider.setProviderType(providerJson.optString("type", ""));
                        provider.setApiKey(providerJson.optString("api_key", ""));
                        provider.setBaseUrl(providerJson.optString("base_url", ""));
                        provider.setActive(providerJson.optBoolean("is_active", true));

                        providers.add(provider);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching providers: " + e.getMessage());
        }
        return providers;
    }

    public ApiResponse activateProvider(int providerId) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("provider_id", providerId);
        } catch (Exception e) {
            Log.e(TAG, "Error building activate payload: " + e.getMessage());
        }
        return postWithAuth("/gold/providers/activate", payload);
    }

    public ApiResponse fetchGoldPriceWithProvider(int providerId) {
        try {
            ApiResponse providerResponse = getWithAuth("/gold/providers/" + providerId);
            if (providerResponse.isSuccess && providerResponse.data != null) {
                JSONObject providerData = providerResponse.data.optJSONObject("provider");
                if (providerData == null) {
                    providerData = providerResponse.data.optJSONObject("data");
                }

                if (providerData != null) {
                    String providerType = providerData.optString("type", "");
                    String apiKey = providerData.optString("api_key", "");
                    return fetchGoldPrice(providerType, apiKey);
                }
            }
            return new ApiResponse(-1, null, "Provider not found");
        } catch (Exception e) {
            Log.e(TAG, "Error fetching price with provider: " + e.getMessage());
            return new ApiResponse(-1, null, e.getMessage());
        }
    }

    public ApiResponse getGoldRatesAndSettings() {
        return get("/gold-rates");
    }

    public ApiResponse getGoldCurrencySettings() {
        return getWithAuth("/gold-currency/settings");
    }

    public ApiResponse getGoldSettings() {
        return getWithAuth("/gold/settings");
    }

    public ApiResponse saveGoldSettings(JSONObject goldSettings) {
        return postWithAuth("/gold/settings", goldSettings);
    }

    public ApiResponse getCurrencySettings() {
        return getWithAuth("/currency/settings");
    }

    public ApiResponse saveCurrencySettings(JSONObject currencySettings) {
        return postWithAuth("/currency/settings", currencySettings);
    }

    public ApiResponse saveGoldCurrencySettings(JSONObject goldSettings, JSONObject currencySettings) {
        JSONObject payload = new JSONObject();
        try {
            if (goldSettings != null) payload.put("gold", goldSettings);
            if (currencySettings != null) payload.put("currency", currencySettings);
        } catch (Exception ignored) {}
        return postWithAuth("/gold-currency/settings", payload);
    }

    public ApiResponse saveGoldCurrencySettings(JSONObject fullPayload) {
        return postWithAuth("/gold-currency/settings", fullPayload);
    }

    public ApiResponse fetchGoldPrice(String provider, String apiKey) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("provider", provider);
            payload.put("api_key", apiKey);
        } catch (Exception ignored) {}
        return postWithAuth("/gold/price/fetch", payload);
    }

    public ApiResponse getGoldPrice() {
        return getWithAuth("/gold/price");
    }

    public ApiResponse updateGoldPrice(JSONObject prices) {
        return postWithAuth("/gold/price", prices);
    }

    public ApiResponse getExchangeRates() {
        return getWithAuth("/currency/rates");
    }

    public ApiResponse calculateGoldPrice(int karat, double weight, String currency) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("karat", karat);
            payload.put("weight", weight);
            payload.put("currency", currency);
        } catch (Exception ignored) {}
        return postWithAuth("/gold/calculate", payload);
    }

    public ApiResponse getLivePrices() {
        return getWithAuth("/gold-currency/live-prices");
    }
    
    //المزامنة للجرد
    // ✅ الكود الصحيح
public ApiResponse syncOfflineTransactions(JSONArray transactionsArray) {
    try {
        // إنشاء payload
        JSONObject payload = new JSONObject();
        payload.put("transactions", transactionsArray);
        payload.put("gate_id", getEffectiveGateId());
        
        // استخدام الدوال الموجودة في الكلاس
        return postWithAuth("/inventory/sync-offline", payload);
        
    } catch (Exception e) {
        Log.e(TAG, "Error syncing offline transactions", e);
        return new ApiResponse(-1, null, e.getMessage());
    }
}
    // ===================== 📦 MY BRANCH INVENTORY =====================

/**
 * ✅ جلب قطع فرع المستخدم الحالي المتاحة للتحويل
 */
public ApiResponse getMyBranchInventoryItems(String category) {
    String endpoint = "/my-branch/inventory/items";
    if (category != null && !category.isEmpty()) {
        try {
            endpoint += "?category=" + URLEncoder.encode(category, "UTF-8");
        } catch (Exception e) {
            endpoint += "?category=" + category;
        }
    }
    return getWithAuth(endpoint);
}

/**
 * ✅ جلب إحصائيات مخزون الفرع
 */
public ApiResponse getBranchInventoryStats(int branchId) {
    return getWithAuth("/branches/" + branchId + "/inventory/stats");
}

    // ===================== 👥 USER PERMISSIONS & LOGS =====================

    public ApiResponse checkUsername(String username) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("username", username);
        } catch (Exception e) {
            Log.e(TAG, "Error building checkUsername payload: " + e.getMessage());
        }
        return postWithAuth("/auth/users/check-username", payload);
    }

    public ApiResponse updateUserPermissions(JSONObject payload) {
        return postWithAuth("/auth/users/update-permissions", payload);
    }

    public ApiResponse logActivity(JSONObject logEntry) {
        return postWithAuth("/activity/log", logEntry);
    }

    public ApiResponse getUserPermissions(int userId) {
        return getWithAuth("/auth/users/" + userId + "/permissions");
    }

    public ApiResponse getUserActivityLogs(int userId, int limit) {
        String endpoint = "/activity/logs?user_id=" + userId + "&limit=" + limit;
        return getWithAuth(endpoint);
    }

    public ApiResponse updateUserStatus(int userId, String status) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("user_id", userId);
            payload.put("status", status);
        } catch (Exception e) {
            Log.e(TAG, "Error building updateUserStatus payload: " + e.getMessage());
        }
        return postWithAuth("/auth/users/update-status", payload);
    }

    public ApiResponse getPermissionTemplates() {
        return getWithAuth("/auth/permissions/templates");
    }

    // ===================== ASYNC CALLBACK EXECUTION =====================

    public interface ApiCallback {
        void onSuccess(ApiResponse response);
        void onFailure(String errorMessage);
        void onError(String errorMessage);
    }

    public void get(final String endpoint, final ApiCallback callback) {
        new Thread(() -> {
            final ApiResponse response = getWithAuth(endpoint);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (response != null && response.isSuccess) {
                    callback.onSuccess(response);
                } else {
                    String err = (response != null && response.error != null) ? response.error : "Unknown error";
                    callback.onFailure(err);
                    callback.onError(err);
                }
            });
        }).start();
    }

    // ===================== ApiResponse =====================


    // ===================== ApiResponse =====================

    public static class ApiResponse {
    public final int code;
    public final JSONObject data;
    public final String error;
    public String message; // ✅ إضافة متغير message
    public final boolean isSuccess;
    private String rawBody;

    public ApiResponse(int code, JSONObject data, String error) {
        this.code = code;
        this.data = data;
        this.error = error;
        this.isSuccess = code >= 200 && code < 300;
        this.rawBody = data != null ? data.toString() : "";
        
        // ✅ استخراج message من data إذا كانت موجودة
        if (data != null) {
            this.message = data.optString("message", "");
        } else {
            this.message = "";
        }
    }

    public String getBody() {
        if (rawBody != null && !rawBody.isEmpty()) {
            return rawBody;
        }
        return data != null ? data.toString() : "";
    }

    public void setBody(String body) {
        this.rawBody = body;
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
