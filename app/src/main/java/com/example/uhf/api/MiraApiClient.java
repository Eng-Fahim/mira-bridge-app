package com.example.uhf.api;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraServerConfig;

import com.example.uhf.model.ApiProvider;
import com.example.uhf.manager.MiraServerConfigManager; // ✅ للإدارة المركزية لإعدادات السيرفر
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

import java.util.ArrayList;
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
 * ✅ إعادة ربط الجهاز بعد مسح البيانات
 */
public ApiResponse reconnectDevice(String username, String password, String gateId) {
    try {
        JSONObject params = new JSONObject();
        params.put("username", username);
        params.put("password", password);
        params.put("gate_id", gateId);
        
        return post("/auth/reconnect", params);
    } catch (Exception e) {
        Log.e(TAG, "Reconnect error: " + e.getMessage());
        return null;
    }
}
    // في كل طلب API
private void addApiKeyHeader(JSONObject request) {
    String apiKey = MiraServerConfigManager.getInstance(context).getApiKey();
    
    if (apiKey.isEmpty()) {
        // ✅ استخدام المفتاح الافتراضي
        apiKey = "mira_gate_test071234567890abcdefghijklmnop";
    }
    
    request.put("X-MIRA-API-Key", apiKey);
}
    // دالة لجلب قائمة المزودين النشطين من السيرفر
    public List<ApiProvider> getActiveProviders() {
        List<ApiProvider> providers = new ArrayList<>();
        
        try {
            // استخدام getWithAuth بدلاً من httpGet المباشر
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
   /**
 * ✅ تفعيل مزود محدد وتعطيل الآخرين
 */
public ApiResponse activateProvider(int providerId) {
    JSONObject payload = new JSONObject();
    try {
        payload.put("provider_id", providerId);
    } catch (Exception e) {
        Log.e(TAG, "Error building activate payload: " + e.getMessage());
    }
    return postWithAuth("/gold/providers/activate", payload);
} 
    // دالة لجلب الأسعار تلقائياً باستخدام المزود المحفوظ
    public ApiResponse fetchGoldPriceWithProvider(int providerId) {
        try {
            // جلب بيانات المزود من السيرفر
            ApiResponse providerResponse = getWithAuth("/gold/providers/" + providerId);
            
            if (providerResponse.isSuccess && providerResponse.data != null) {
                JSONObject providerData = providerResponse.data.optJSONObject("provider");
                if (providerData == null) {
                    providerData = providerResponse.data.optJSONObject("data");
                }
                
                if (providerData != null) {
                    String providerType = providerData.optString("type", "");
                    String apiKey = providerData.optString("api_key", "");
                    
                    // استخدام المزود لجلب الأسعار
                    return fetchGoldPrice(providerType, apiKey);
                }
            }
            
            return new ApiResponse(-1, null, "Provider not found");
        } catch (Exception e) {
            Log.e(TAG, "Error fetching price with provider: " + e.getMessage());
            return new ApiResponse(-1, null, e.getMessage());
        }
    }

    /**
     * إعادة تحميل الإعدادات بعد تغيير الدومين
     */
    public static synchronized void reloadInstance(Context context) {
        instance = new MiraApiClient(context.getApplicationContext());
    }

    /**
     * ✅ الحصول على api_key الفعال
     * لا توجد قيم افتراضية - يجب أن تكون من السيرفر
     */
    private String getEffectiveApiKey() {
        if (apiKey == null || apiKey.isEmpty()) {
            Log.e(TAG, "API Key is empty! User must login first.");
            return "";
        }
        return apiKey;
    }

    /**
     * ✅ الحصول على gate_id الفعال
     * لا توجد قيم افتراضية - يجب أن تكون من السيرفر
     */
    private String getEffectiveGateId() {
        if (gateId == null || gateId.isEmpty()) {
            Log.e(TAG, "Gate ID is empty! User must login first.");
            return "";
        }
        return gateId;
    }

    /**
     * ✅ التحقق من أن المستخدم مسجل دخول ولديه credentials صالحة
     */
    public boolean hasValidCredentials() {
        return !getEffectiveApiKey().isEmpty() && !getEffectiveGateId().isEmpty();
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
        
        // ✅ إذا أرسل السيرفر api_key جديد، احفظه
        if (!newApiKey.isEmpty()) {
            serverConfig.saveSession(newApiKey, newGateId, authToken);
            refreshCredentials();
            Log.d(TAG, "✅ New API Key saved: " + newApiKey.substring(0, Math.min(8, newApiKey.length())) + "...");
        } 
        // ✅ إذا لم يرسل api_key (لأنه موجود)، احتفظ بالمفتاح الحالي
        else if (!authToken.isEmpty()) {
            // ✅ احتفظ بالمفتاح الحالي ولا تمسحه
            String currentApiKey = getEffectiveApiKey();
            String currentGateId = getEffectiveGateId();
            
            serverConfig.saveSession(
                !currentApiKey.isEmpty() ? currentApiKey : "",  // ✅ احتفظ بالمفتاح الحالي
                !newGateId.isEmpty() ? newGateId : currentGateId,
                authToken
            );
            refreshCredentials();
            Log.d(TAG, "✅ Using existing API Key: " + 
                (!currentApiKey.isEmpty() ? currentApiKey.substring(0, Math.min(8, currentApiKey.length())) + "..." : "EMPTY"));
        }
    }
    
    return response;
}

    public ApiResponse getBranchItemsByCategory(int branchId, String category) {
        String endpoint = "/branches/" + branchId + "/inventory/items";
        if (category != null && !category.isEmpty()) {
            endpoint += "?category=" + category;
        }
        return getWithAuth(endpoint);
    }
    
    public ApiResponse addItemsToTransfer(int transferId, JSONArray epcs) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("items", epcs);
        } catch (Exception ignored) {}
        return postWithAuth("/transfers/" + transferId + "/items", payload);
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

    // ===================== 📦 TRANSFERS =====================

    public ApiResponse receiveTransferWithAudit(JSONObject payload) {
        return postWithAuth("/transfers/receive-audit", payload);
    }

    public ApiResponse getTransferDetails(int transferId) {
        return getWithAuth("/transfers/" + transferId);
    }

    public ApiResponse registerBiometric(String token, String deviceId, String publicKey) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("token", token);
            payload.put("device_id", deviceId);
            payload.put("public_key", publicKey);
            payload.put("gate_id", getEffectiveGateId());
        } catch (Exception e) {}
        return post("/auth/register-biometric", payload);
    }

    public ApiResponse verifyBiometric(String token, String signedChallenge) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("token", token);
            payload.put("signed_challenge", signedChallenge);
            payload.put("gate_id", getEffectiveGateId());
        } catch (Exception e) {}
        return post("/auth/verify-biometric", payload);
    }

    // ===================== 🏢 ORGANIZATION =====================
// ===================== PROFILE =====================

public ApiResponse getOrgProfile() {
    return get("/profile/get");
}

public ApiResponse saveOrgProfile(JSONObject payload) {
    return post("/profile/update", payload);
}

// ✅ أضف هذه الدالة
public ApiResponse updateUserProfile(JSONObject payload) {
    return post("/profile/update-user", payload);
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
                java.util.Iterator<String> keys = extraParams.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    payload.put(key, extraParams.get(key));
                }
            }
        } catch (Exception ignored) {}
        return post("/authorize", payload);
    }

    // ===================== 📡 PING =====================

    public boolean pingApi(String targetUrl, String targetApiKey) {
        HttpURLConnection conn = null;
        try {
            String baseUrl = (targetUrl != null && !targetUrl.isEmpty()) ? targetUrl : this.apiUrl;
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }

            URL url = new URL(baseUrl + "/heartbeat");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("X-MIRA-API-Key", (targetApiKey != null && !targetApiKey.isEmpty()) ? targetApiKey : getEffectiveApiKey());
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            int responseCode = conn.getResponseCode();
            return (responseCode >= 200 && responseCode < 400);
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public boolean pingApi() {
        return pingApi(this.apiUrl, getEffectiveApiKey());
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
        } catch (Exception e) {}
        return postWithAuth("/pos/refund", payload);
    }

    public ApiResponse getSalesHistory() {
        return getWithAuth("/pos/sales");
    }

    // ===================== 🏷️ ITEM =====================

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
    
    public ApiResponse getInventoryReports(int branchId, int page, int perPage) {
        HttpURLConnection conn = null;
        try {
            String fullUrl = apiUrl + "/inventory/sessions?branch_id=" + branchId 
                           + "&page=" + page + "&per_page=" + perPage;
            
            URL url = new URL(fullUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("X-MIRA-API-Key", getEffectiveApiKey());
            
            String token = getAuthToken();
            if (!token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setRequestProperty("X-MIRA-Auth-Token", token);
            }
            
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            
            return buildResponse(conn);
        } catch (Exception e) {
            Log.e(TAG, "getInventoryReports error: " + e.getMessage());
            return new ApiResponse(-1, null, e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
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
    String endpoint = "/inventory/compare-sessions?session_id_1=" + sessionId1 + 
                      "&session_id_2=" + sessionId2;
    return getWithAuth(endpoint);
    }

    public ApiResponse createInventorySnapshot(JSONObject payload) {
        return post("/inventory/create-snapshot", payload);
    }

    public ApiResponse getInventorySettings(String location) {
        return get("/inventory/settings/" + (location != null ? location : "default"));
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
        } catch (Exception e) {}
        return encodeItem(payload);
    }

    // ===================== 📈 GOLD =====================

    public ApiResponse getGoldRatesAndSettings() {
        return get("/gold-rates");
    }

    // ===================== 🚪 GATES =====================

    public ApiResponse createGate(JSONObject gateData) {
        return postWithAuth("/gates", gateData);
    }

    public ApiResponse getGates() {
        return getWithAuth("/gates");
    }

    public ApiResponse getGatesStats() {
        return get("/gates/stats");
    }

    // ===================== 💓 HEARTBEAT =====================

    public ApiResponse heartbeat(String status, int battery) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("gate_id", getEffectiveGateId());
            payload.put("status", status != null ? status : "online");
            if (battery > 0) payload.put("battery", battery);
        } catch (Exception ignored) {}
        return post("/heartbeat", payload);
    }

    // ===================== ⚙️ SETTINGS =====================

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
            payload.put("tags", new JSONArray(java.util.Arrays.asList(tags)));
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

    public ApiResponse uploadItemImage(int itemId, File imageFile, boolean isPrimary, String altText) {
        HttpURLConnection conn = null;
        try {
            String fullUrl = apiUrl + "/items/" + itemId + "/images";
            URL url = new URL(fullUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("X-MIRA-API-Key", getEffectiveApiKey());
            
            String token = getAuthToken();
            if (!token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            
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
                os.write(sb.toString().getBytes("utf-8"));
                
                FileInputStream fis = new FileInputStream(imageFile);
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
                fis.close();
                
                sb = new StringBuilder();
                sb.append("\r\n--").append(boundary).append("\r\n");
                sb.append("Content-Disposition: form-data; name=\"is_primary\"\r\n\r\n");
                sb.append(isPrimary ? "1" : "0");
                os.write(sb.toString().getBytes("utf-8"));
                
                sb = new StringBuilder();
                sb.append("\r\n--").append(boundary).append("\r\n");
                sb.append("Content-Disposition: form-data; name=\"alt_text\"\r\n\r\n");
                sb.append(altText != null ? altText : "");
                os.write(sb.toString().getBytes("utf-8"));
                
                os.write(("\r\n--" + boundary + "--\r\n").getBytes("utf-8"));
                os.flush();
            }
            
            return buildResponse(conn);
        } catch (Exception e) {
            return new ApiResponse(-1, null, e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
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

    // ===================== 📦 TRANSFERS =====================

    public ApiResponse getTransfers(int branchId, String status) {
        String url = "/transfers?branch_id=" + branchId;
        if (status != null && !status.isEmpty()) {
            url += "&status=" + status;
        }
        return getWithAuth(url);
    }

    public ApiResponse getBranchItemsList(int branchId) {
        return getWithAuth("/branches/" + branchId + "/inventory/items");
    }

    public ApiResponse createTransfer(JSONObject payload) {
        return postWithAuth("/transfers", payload);
    }

    public ApiResponse getTransfer(int transferId) {
        return getWithAuth("/transfers/" + transferId);
    }

    public ApiResponse approveTransfer(int transferId) {
        return postWithAuth("/transfers/" + transferId + "/approve", new JSONObject());
    }

    public ApiResponse receiveTransfer(int transferId) {
        return postWithAuth("/transfers/" + transferId + "/receive", new JSONObject());
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

    public ApiResponse getReportData(String reportType, String fromDate, String toDate, int branchId) {
        String endpoint = "/reports/data?type=" + (reportType != null ? reportType : "")
                + "&from=" + (fromDate != null ? fromDate : "")
                + "&to=" + (toDate != null ? toDate : "")
                + "&branch_id=" + branchId;
        return getWithAuth(endpoint);
    }


    // ===================== CORE HTTP =====================

    private String getAuthToken() {
        SharedPreferences prefs = context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE);
        return prefs.getString("authToken", "");
    }

    public ApiResponse getWithAuth(String endpoint) {
        // ✅ التحقق من وجود credentials صالحة
        if (!hasValidCredentials()) {
            Log.e(TAG, "Attempting API call without valid credentials: " + endpoint);
            return new ApiResponse(401, null, "Unauthorized - Please login first");
        }
        
        HttpURLConnection conn = null;
        try {
            String fullUrl = apiUrl + endpoint;
            URL url = new URL(fullUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("X-MIRA-API-Key", getEffectiveApiKey());
            String token = getAuthToken();
            if (!token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setRequestProperty("X-MIRA-Auth-Token", token);
            }
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            return buildResponse(conn);
        } catch (Exception e) {
            return new ApiResponse(-1, null, e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public ApiResponse postWithAuth(String endpoint, JSONObject payload) {
        // ✅ التحقق من وجود credentials صالحة
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
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("X-MIRA-API-Key", getEffectiveApiKey());
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
                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonString.getBytes("utf-8");
                    os.write(input, 0, input.length);
                }
            }
            return buildResponse(conn);
        } catch (Exception e) {
            return new ApiResponse(-1, null, e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private ApiResponse post(String endpoint, JSONObject payload) {
        // للـ login فقط - لا يتطلب credentials مسبقة
        HttpURLConnection conn = null;
        try {
            String fullUrl = apiUrl + endpoint;
            URL url = new URL(fullUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            
            // ✅ نستخدم apiKey فقط إذا كان موجوداً
            String effectiveKey = getEffectiveApiKey();
            if (!effectiveKey.isEmpty()) {
                conn.setRequestProperty("X-MIRA-API-Key", effectiveKey);
            }
            
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            if (payload != null) {
                String jsonString = payload.toString();
                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonString.getBytes("utf-8");
                    os.write(input, 0, input.length);
                }
            }
            return buildResponse(conn);
        } catch (Exception e) {
            return new ApiResponse(-1, null, e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private ApiResponse get(String endpoint) {
        // للـ endpoints العامة - لا يتطلب auth
        HttpURLConnection conn = null;
        try {
            String fullUrl = apiUrl + endpoint;
            URL url = new URL(fullUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            
            // ✅ نستخدم apiKey فقط إذا كان موجوداً
            String effectiveKey = getEffectiveApiKey();
            if (!effectiveKey.isEmpty()) {
                conn.setRequestProperty("X-MIRA-API-Key", effectiveKey);
            }
            
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            return buildResponse(conn);
        } catch (Exception e) {
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

    public String getGateId() { return getEffectiveGateId(); }
    public String getApiUrl() { return apiUrl; }

    // ... باقي الكود كما هو ...
public void submitInventoryAuditReport(JSONObject payload) {
        HttpURLConnection conn = null;
        try {
            String fullUrl = apiUrl + "/inventory/submit-audit";
            URL url = new URL(fullUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("X-MIRA-API-Key", getEffectiveApiKey());
            String token = getAuthToken();
            if (!token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            if (payload != null) {
                String jsonString = payload.toString();
                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonString.getBytes("utf-8");
                    os.write(input, 0, input.length);
                }
            }
            conn.getResponseCode();
        } catch (Exception e) {
            Log.e(TAG, "submitInventoryAuditReport error: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ===================== 💰 GOLD & CURRENCY =====================

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
    
    // ✅ الدالة الجديدة (معامل واحد)
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
    
    
    // ===================== ACCOUNTING - إضافات =====================

public ApiResponse createAccount(JSONObject payload) {
    return postWithAuth("/accounting/chart-of-accounts/create", payload);
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

// ===================== ACCOUNTING REPORTS =====================

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

// ===================== ACCOUNTING - ADVANCED =====================

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
