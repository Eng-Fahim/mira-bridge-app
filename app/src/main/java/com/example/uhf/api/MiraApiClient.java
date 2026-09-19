package com.example.uhf.api;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraServerConfig;
import com.example.uhf.model.ApiProvider;
import com.example.uhf.tools.CertificatePinningManager;  
import com.example.uhf.tools.SessionManager;

import com.example.uhf.model.MessageModel;
import com.example.uhf.model.NotificationModel;
import com.example.uhf.model.ItemModel;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;  

public class MiraApiClient {

    private static final String TAG = "MiraApiClient";
    private static final String SESSION_PREFS = "MiraEnterpriseSession";
    
    private static final String API_PREFIX = "mira-gate/v1";
    private static final String DEFAULT_BASE_URL = "https://sa.mirabarcode.com/wp-json";

    private final Context context;
    private final MiraServerConfig serverConfig;
    private final SessionManager sessionManager;

    private String baseUrl;
    private String apiUrl;
    private String apiKey;
    private String gateId;

    // ✅ إدارة الخيوط البرمجية بشكل فعال
    private final ExecutorService executorService = Executors.newFixedThreadPool(4);
    private final ExecutorService networkExecutor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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
        this.sessionManager = new SessionManager(context);
        
        String savedBaseUrl = sessionManager.getServerUrl();
        if (savedBaseUrl == null || savedBaseUrl.isEmpty()) {
            savedBaseUrl = serverConfig.getBaseUrl();
            if (savedBaseUrl == null || savedBaseUrl.isEmpty()) {
                savedBaseUrl = DEFAULT_BASE_URL;
            }
            sessionManager.setServerUrl(savedBaseUrl);
            serverConfig.setBaseUrl(savedBaseUrl);
        }
        baseUrl = savedBaseUrl;
        
        refreshCredentials();
    }

    private void refreshCredentials() {
        String cleanBaseUrl = baseUrl;
        if (!cleanBaseUrl.endsWith("/")) {
            cleanBaseUrl += "/";
        }
        
        this.apiUrl = cleanBaseUrl + API_PREFIX + "/";
        
        this.apiKey = sessionManager.getApiKey();
        this.gateId = sessionManager.getGateId();
        
        if (this.apiKey.isEmpty()) {
            this.apiKey = serverConfig.getApiKey();
        }
        if (this.gateId.isEmpty()) {
            this.gateId = serverConfig.getGateId();
        }
        
        Log.d(TAG, "Credentials: URL=" + apiUrl + ", Key=" + (apiKey.isEmpty() ? "EMPTY" : "EXISTS") + ", Gate=" + (gateId.isEmpty() ? "EMPTY" : gateId));
    }

    public static synchronized void reloadInstance(Context context) {
        instance = new MiraApiClient(context.getApplicationContext());
    }

    public boolean reinitializeWithServer(String newServerUrl) {
        try {
            String cleanUrl = newServerUrl.trim();
            if (!cleanUrl.endsWith("/")) {
                cleanUrl += "/";
            }
            
            sessionManager.setServerUrl(cleanUrl);
            serverConfig.setBaseUrl(cleanUrl);
            
            baseUrl = cleanUrl;
            refreshCredentials();
            
            return pingApi();
            
        } catch (Exception e) {
            Log.e(TAG, "Reinitialize error: " + e.getMessage());
            return false;
        }
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
    String token = sessionManager.getAuthToken();
    if (token == null || token.isEmpty()) {
        token = serverConfig.getAuthToken();
    }
    return token;
}
    
    public boolean hasValidCredentials() {
        return !getEffectiveApiKey().isEmpty() && !getEffectiveGateId().isEmpty();
    }

    public String getGateId() { return getEffectiveGateId(); }
    public String getApiUrl() { return apiUrl; }

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

    public ApiResponse getWithAuth(String endpoint) {
        HttpURLConnection conn = null;
        try {
            String token = getAuthToken();
            if (token == null || token.isEmpty()) {
                Log.w(TAG, "No auth token for endpoint: " + endpoint);
                ApiResponse response = new ApiResponse(401, null, "No auth token");
                response.isNetworkError = false;
                return response;
            }
            
            String cleanEndpoint = endpoint;
            if (cleanEndpoint.startsWith("/")) {
                cleanEndpoint = cleanEndpoint.substring(1);
            }
            
            String fullUrl = apiUrl + cleanEndpoint;
            URL url = new URL(fullUrl);
            
            if (url.getProtocol().equals("https")) {
                HttpsURLConnection httpsConn = (HttpsURLConnection) url.openConnection();
                CertificatePinningManager.applyPinning(httpsConn);
                conn = httpsConn;
            } else {
                conn = (HttpURLConnection) url.openConnection();
            }
            conn.setRequestMethod("GET");
            applyStandardHeaders(conn, true);
            conn.setConnectTimeout(10000);  
            conn.setReadTimeout(10000);     
            return buildResponse(conn);
            
        } catch (java.net.UnknownHostException e) {
            ApiResponse response = new ApiResponse(-1, null, "الخادم غير موجود");
            response.isNetworkError = true;
            return response;
        } catch (java.net.ConnectException e) {
            ApiResponse response = new ApiResponse(-1, null, "لا يمكن الاتصال بالخادم");
            response.isNetworkError = true;
            return response;
        } catch (java.net.SocketTimeoutException e) {
            ApiResponse response = new ApiResponse(-1, null, "انتهت المهلة");
            response.isNetworkError = true;
            return response;
        } catch (Exception e) {
            ApiResponse response = new ApiResponse(-1, null, e.getMessage());
            response.isNetworkError = true;
            return response;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public ApiResponse postWithAuth(String endpoint, JSONObject payload) {
        HttpURLConnection conn = null;
        try {
            String token = getAuthToken();
            if (token == null || token.isEmpty()) {
                Log.w(TAG, "No auth token for endpoint: " + endpoint);
                ApiResponse response = new ApiResponse(401, null, "No auth token");
                response.isNetworkError = false;
                return response;
            }
            
            String cleanEndpoint = endpoint;
            if (cleanEndpoint.startsWith("/")) {
                cleanEndpoint = cleanEndpoint.substring(1);
            }
            
            String fullUrl = apiUrl + cleanEndpoint;
            URL url = new URL(fullUrl);
            if (url.getProtocol().equals("https")) {
                HttpsURLConnection httpsConn = (HttpsURLConnection) url.openConnection();
                CertificatePinningManager.applyPinning(httpsConn);
                conn = httpsConn;
            } else {
                conn = (HttpURLConnection) url.openConnection();
            }
            conn.setRequestMethod("POST");
            applyStandardHeaders(conn, true);
            conn.setConnectTimeout(10000);  
            conn.setReadTimeout(10000);     
            conn.setDoOutput(true);

            if (payload != null) {
                byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(input, 0, input.length);
                }
            }
            return buildResponse(conn);
            
        } catch (java.net.UnknownHostException e) {
            ApiResponse response = new ApiResponse(-1, null, "الخادم غير موجود");
            response.isNetworkError = true;
            return response;
        } catch (java.net.ConnectException e) {
            ApiResponse response = new ApiResponse(-1, null, "لا يمكن الاتصال بالخادم");
            response.isNetworkError = true;
            return response;
        } catch (java.net.SocketTimeoutException e) {
            ApiResponse response = new ApiResponse(-1, null, "انتهت المهلة");
            response.isNetworkError = true;
            return response;
        } catch (Exception e) {
            ApiResponse response = new ApiResponse(-1, null, e.getMessage());
            response.isNetworkError = true;
            return response;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private ApiResponse post(String endpoint, JSONObject payload) {
        HttpURLConnection conn = null;
        try {
            String cleanEndpoint = endpoint;
            if (cleanEndpoint.startsWith("/")) {
                cleanEndpoint = cleanEndpoint.substring(1);
            }
            
            String fullUrl = apiUrl + cleanEndpoint;
            URL url = new URL(fullUrl);
            if (url.getProtocol().equals("https")) {
                HttpsURLConnection httpsConn = (HttpsURLConnection) url.openConnection();
                CertificatePinningManager.applyPinning(httpsConn);
                conn = httpsConn;
            } else {
                conn = (HttpURLConnection) url.openConnection();
            }
            conn.setRequestMethod("POST");
            applyStandardHeaders(conn, false);

            conn.setConnectTimeout(10000);  
            conn.setReadTimeout(10000);     
            conn.setDoOutput(true);

            if (payload != null) {
                byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(input, 0, input.length);
                }
            }
            return buildResponse(conn);
        } catch (java.net.UnknownHostException e) {
            ApiResponse response = new ApiResponse(-1, null, "الخادم غير موجود");
            response.isNetworkError = true;
            return response;
        } catch (java.net.ConnectException e) {
            ApiResponse response = new ApiResponse(-1, null, "لا يمكن الاتصال بالخادم");
            response.isNetworkError = true;
            return response;
        } catch (java.net.SocketTimeoutException e) {
            ApiResponse response = new ApiResponse(-1, null, "انتهت المهلة");
            response.isNetworkError = true;
            return response;
        } catch (Exception e) {
            ApiResponse response = new ApiResponse(-1, null, e.getMessage());
            response.isNetworkError = true;
            return response;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private ApiResponse get(String endpoint) {
        HttpURLConnection conn = null;
        try {
            String cleanEndpoint = endpoint;
            if (cleanEndpoint.startsWith("/")) {
                cleanEndpoint = cleanEndpoint.substring(1);
            }
            
            String fullUrl = apiUrl + cleanEndpoint;
            URL url = new URL(fullUrl);
            if (url.getProtocol().equals("https")) {
                HttpsURLConnection httpsConn = (HttpsURLConnection) url.openConnection();
                CertificatePinningManager.applyPinning(httpsConn);
                conn = httpsConn;
            } else {
                conn = (HttpURLConnection) url.openConnection();
            }
            conn.setRequestMethod("GET");
            applyStandardHeaders(conn, false);
            conn.setConnectTimeout(10000);  
            conn.setReadTimeout(10000);     
            return buildResponse(conn);
            
        } catch (java.net.UnknownHostException e) {
            ApiResponse response = new ApiResponse(-1, null, "الخادم غير موجود");
            response.isNetworkError = true;
            return response;
        } catch (java.net.ConnectException e) {
            ApiResponse response = new ApiResponse(-1, null, "لا يمكن الاتصال بالخادم");
            response.isNetworkError = true;
            return response;
        } catch (Exception e) {
            ApiResponse response = new ApiResponse(-1, null, e.getMessage());
            response.isNetworkError = true;
            return response;
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
        String errorMessage = null;

        try {
            if (!responseBody.isEmpty()) {
                json = new JSONObject(responseBody);
                if (json.has("message")) {
                    errorMessage = json.optString("message", null);
                }
            }
        } catch (Exception ignored) {}

        if (errorMessage == null && responseCode >= 400) {
            errorMessage = responseBody;
        }

        ApiResponse apiResponse = new ApiResponse(responseCode, json, errorMessage);
        apiResponse.setBody(responseBody);
        
        return apiResponse;
    }

    public ApiResponse login(String username, String password, String deviceId) {
    JSONObject payload = new JSONObject();
    try {
        payload.put("username", username);
        payload.put("password", password);
        payload.put("device_id", deviceId != null ? deviceId : "");
        
        String effectiveGateId = getEffectiveGateId();
        if (effectiveGateId.isEmpty()) {
            effectiveGateId = deviceId != null ? deviceId : "default-gate";
            serverConfig.setGateId(effectiveGateId);
        }
        
        payload.put("gate_id", effectiveGateId);
        payload.put("app_version", "MIRA-ANDROID-v3.0");
    } catch (Exception ignored) {}

    ApiResponse response = post("/auth/login", payload);

    if (response.isSuccess && response.data != null) {
        String newApiKey = response.data.optString("api_key", "");
        String newGateId = response.data.optString("gate_id", "");
        String authToken = response.data.optString("token", "");
        long expiresIn = response.data.optLong("expires_in", 86400 * 7);
        
        Log.d(TAG, "🔐 Login response received:");
        Log.d(TAG, "   - api_key: " + (newApiKey.isEmpty() ? "EMPTY" : "EXISTS"));
        Log.d(TAG, "   - token: " + (authToken.isEmpty() ? "EMPTY" : "EXISTS (" + authToken.length() + " chars)"));
        Log.d(TAG, "   - gate_id: " + (newGateId.isEmpty() ? "EMPTY" : newGateId));
        
        // ✅ 1. حفظ التوكن (مهم جداً!)
        if (!authToken.isEmpty()) {
            sessionManager.saveAuthToken(authToken, expiresIn);
            Log.d(TAG, "✅ Auth token saved");
        }
        
        // ✅ 2. حفظ API Key
        if (!newApiKey.isEmpty()) {
            sessionManager.saveApiKey(newApiKey);
            Log.d(TAG, "✅ API Key saved");
        }
        
        // ✅ 3. حفظ Gate ID
        String effectiveGateId = !newGateId.isEmpty() ? newGateId :
                (deviceId != null && !deviceId.isEmpty() ? deviceId : "");
        if (!effectiveGateId.isEmpty()) {
            sessionManager.saveGateId(effectiveGateId);
            Log.d(TAG, "✅ Gate ID saved");
        }
        
        // ✅ 4. تحديث الـ Credentials الداخلية
        refreshCredentials();
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
                    if (!newApiKey.isEmpty()) {
                        sessionManager.saveApiKey(newApiKey);
                    }
                    if (newGateId != null && !newGateId.isEmpty()) {
                        sessionManager.saveGateId(newGateId);
                    }
                    refreshCredentials();
                }
            }
            return response;
        } catch (Exception e) {
            return new ApiResponse(-1, null, e.getMessage());
        }
    }

    public ApiResponse validateToken(String token) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("token", token);
            payload.put("gate_id", getEffectiveGateId());
        } catch (Exception ignored) {}
        return post("/auth/validate-token", payload);
    }

    public ApiResponse logout(String token) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("token", token);
            payload.put("gate_id", getEffectiveGateId());
        } catch (Exception ignored) {}
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
        } catch (Exception ignored) {}
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
    
    // ==================== دوال المراسلات والإشعارات ====================
    
public interface MessageCallback {
    void onSuccess(List<MessageModel> messages);
    void onError(String error);
}

public interface SendMessageCallback {
    void onSuccess(MessageModel message);
    void onError(String error);
}

public interface UnreadCountCallback {
    void onSuccess(int unreadCount);
    void onError(String error);
}

// جلب الرسائل غير المقروءة
public void getUnreadMessages(String userId, MessageCallback callback) {
    executorService.execute(() -> {
        try {
            String endpoint = "/messages/unread?user_id=" + userId;
            ApiResponse response = getWithAuth(endpoint);
            
            if (response.isSuccess && response.data != null) {
                List<MessageModel> messages = new ArrayList<>();
                JSONArray messagesArray = response.data.optJSONArray("messages");
                
                if (messagesArray != null) {
                    for (int i = 0; i < messagesArray.length(); i++) {
                        JSONObject msgJson = messagesArray.getJSONObject(i);
                        MessageModel message = parseMessageFromJson(msgJson);
                        if (message != null) {
                            messages.add(message);
                        }
                    }
                }
                
                mainHandler.post(() -> callback.onSuccess(messages));
            } else {
                String error = response.error != null ? response.error : "Failed to fetch messages";
                mainHandler.post(() -> callback.onError(error));
            }
        } catch (Exception e) {
            mainHandler.post(() -> callback.onError(e.getMessage()));
        }
    });
}

// جلب كل الرسائل
public void getAllMessages(String userId, MessageCallback callback) {
    executorService.execute(() -> {
        try {
            String endpoint = "/messages?user_id=" + userId;
            ApiResponse response = getWithAuth(endpoint);
            
            if (response.isSuccess && response.data != null) {
                List<MessageModel> messages = new ArrayList<>();
                JSONArray messagesArray = response.data.optJSONArray("messages");
                
                if (messagesArray == null) {
                    messagesArray = response.data.optJSONArray("data");
                }
                
                if (messagesArray != null) {
                    for (int i = 0; i < messagesArray.length(); i++) {
                        JSONObject msgJson = messagesArray.getJSONObject(i);
                        MessageModel message = parseMessageFromJson(msgJson);
                        if (message != null) {
                            messages.add(message);
                        }
                    }
                }
                
                mainHandler.post(() -> callback.onSuccess(messages));
            } else {
                String error = response.error != null ? response.error : "Failed to fetch messages";
                mainHandler.post(() -> callback.onError(error));
            }
        } catch (Exception e) {
            mainHandler.post(() -> callback.onError(e.getMessage()));
        }
    });
}

// إرسال رسالة جديدة
public void sendMessage(MessageModel message, SendMessageCallback callback) {
    executorService.execute(() -> {
        try {
            JSONObject payload = new JSONObject();
            payload.put("sender_id", message.getSenderId());
            payload.put("receiver_id", message.getReceiverId());
            payload.put("content", message.getContent());
            payload.put("message_type", message.getMessageType());
            payload.put("timestamp", message.getTimestamp());
            
            ApiResponse response = postWithAuth("/messages/send", payload);
            
            if (response.isSuccess) {
                // تحديث بيانات الرسالة من الاستجابة
                if (response.data != null) {
                    JSONObject msgJson = response.data.optJSONObject("message");
                    if (msgJson != null) {
                        MessageModel sentMessage = parseMessageFromJson(msgJson);
                        if (sentMessage != null) {
                            mainHandler.post(() -> callback.onSuccess(sentMessage));
                            return;
                        }
                    }
                }
                mainHandler.post(() -> callback.onSuccess(message));
            } else {
                String error = response.error != null ? response.error : "Failed to send message";
                mainHandler.post(() -> callback.onError(error));
            }
        } catch (Exception e) {
            mainHandler.post(() -> callback.onError(e.getMessage()));
        }
    });
}

// جلب عدد الرسائل غير المقروءة
public void getUnreadCount(String userId, UnreadCountCallback callback) {
    executorService.execute(() -> {
        try {
            String endpoint = "/messages/unread-count?user_id=" + userId;
            ApiResponse response = getWithAuth(endpoint);
            
            if (response.isSuccess && response.data != null) {
                int unreadCount = response.data.optInt("unread_count", 0);
                mainHandler.post(() -> callback.onSuccess(unreadCount));
            } else {
                String error = response.error != null ? response.error : "Failed to fetch unread count";
                mainHandler.post(() -> callback.onError(error));
            }
        } catch (Exception e) {
            mainHandler.post(() -> callback.onError(e.getMessage()));
        }
    });
}

// جلب الإشعارات
public void getNotifications(String userId, NotificationCallback callback) {
    executorService.execute(() -> {
        try {
            String endpoint = "/notifications?user_id=" + userId;
            ApiResponse response = getWithAuth(endpoint);
            
            if (response.isSuccess && response.data != null) {
                List<NotificationModel> notifications = new ArrayList<>();
                JSONArray notificationsArray = response.data.optJSONArray("notifications");
                
                if (notificationsArray == null) {
                    notificationsArray = response.data.optJSONArray("data");
                }
                
                if (notificationsArray != null) {
                    for (int i = 0; i < notificationsArray.length(); i++) {
                        JSONObject notifJson = notificationsArray.getJSONObject(i);
                        NotificationModel notification = parseNotificationFromJson(notifJson);
                        if (notification != null) {
                            notifications.add(notification);
                        }
                    }
                }
                
                mainHandler.post(() -> callback.onSuccess(notifications));
            } else {
                String error = response.error != null ? response.error : "Failed to fetch notifications";
                mainHandler.post(() -> callback.onError(error));
            }
        } catch (Exception e) {
            mainHandler.post(() -> callback.onError(e.getMessage()));
        }
    });
}

public interface NotificationCallback {
    void onSuccess(List<NotificationModel> notifications);
    void onError(String error);
}

public interface SearchCallback {
    void onSuccess(List<ItemModel> results);
    void onError(String error);
}

public void searchGlobal(String query, SearchCallback callback) {
    executorService.execute(() -> {
        try {
            JSONObject payload = new JSONObject();
            payload.put("query", query);
            payload.put("gate_id", getEffectiveGateId());
            
            ApiResponse response = postWithAuth("/search/global", payload);
            
            if (response.isSuccess && response.data != null) {
                List<ItemModel> results = new ArrayList<>();
                JSONArray itemsArray = response.data.optJSONArray("items");
                
                if (itemsArray != null) {
                    for (int i = 0; i < itemsArray.length(); i++) {
                        JSONObject itemJson = itemsArray.getJSONObject(i);
                        ItemModel item = new ItemModel();
                        item.setEpc(itemJson.optString("epc", ""));
                        item.setBarcode(itemJson.optString("barcode", ""));
                        item.setTitle(itemJson.optString("title", ""));
                        item.setCategory(itemJson.optString("category", ""));
                        item.setLocation(itemJson.optString("location", ""));
                        results.add(item);
                    }
                }
                
                mainHandler.post(() -> callback.onSuccess(results));
            } else {
                String error = response.error != null ? response.error : "Search failed";
                mainHandler.post(() -> callback.onError(error));
            }
        } catch (Exception e) {
            mainHandler.post(() -> callback.onError(e.getMessage()));
        }
    });
}
// دوال مساعدة لتحليل JSON
private MessageModel parseMessageFromJson(JSONObject json) {
    try {
        MessageModel message = new MessageModel();
        message.setId(json.optString("id", ""));
        message.setSenderId(json.optString("sender_id", ""));
        message.setSenderName(json.optString("sender_name", ""));
        message.setReceiverId(json.optString("receiver_id", ""));
        message.setReceiverName(json.optString("receiver_name", ""));
        message.setContent(json.optString("content", ""));
        message.setTimestamp(json.optLong("timestamp", System.currentTimeMillis()));
        message.setRead(json.optBoolean("is_read", false));
        message.setMessageType(json.optString("message_type", "TEXT"));
        return message;
    } catch (Exception e) {
        Log.e(TAG, "Error parsing message: " + e.getMessage());
        return null;
    }
}

private NotificationModel parseNotificationFromJson(JSONObject json) {
    try {
        NotificationModel notification = new NotificationModel();
        notification.setId(json.optString("id", ""));
        notification.setTitle(json.optString("title", ""));
        notification.setContent(json.optString("content", ""));
        notification.setTimestamp(json.optLong("timestamp", System.currentTimeMillis()));
        notification.setNotificationType(json.optString("notification_type", "SYSTEM"));
        notification.setRead(json.optBoolean("is_read", false));
        notification.setPriority(json.optString("priority", "MEDIUM"));
        return notification;
    } catch (Exception e) {
        Log.e(TAG, "Error parsing notification: " + e.getMessage());
        return null;
    }
}

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

    public ApiResponse changePassword(String currentPassword, String newPassword) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("current_password", currentPassword);
            payload.put("new_password", newPassword);
        } catch (Exception ignored) {}
        return postWithAuth("/auth/change-password", payload);
    }

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

    public boolean pingApi() {
        HttpURLConnection conn = null;
        try {
            String cleanUrl = this.apiUrl;
            if (cleanUrl.endsWith("/")) {
                cleanUrl = cleanUrl.substring(0, cleanUrl.length() - 1);
            }

            URL url = new URL(cleanUrl + "/ping");
            if (url.getProtocol().equals("https")) {
                HttpsURLConnection httpsConn = (HttpsURLConnection) url.openConnection();
                CertificatePinningManager.applyPinning(httpsConn);
                conn = httpsConn;
            } else {
                conn = (HttpURLConnection) url.openConnection();
            }
            conn.setRequestMethod("GET");
            applyStandardHeaders(conn, true);
            conn.setConnectTimeout(5000);   
            conn.setReadTimeout(5000);      

            int responseCode = conn.getResponseCode();
            return (responseCode >= 200 && responseCode < 400);
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

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
    
   /**
 * ✅ Checkout مع Timeout طويل (60 ثانية)
 * يستخدم لعمليات البيع التي قد تستغرق وقتاً طويلاً على السيرفر
 */
public ApiResponse checkoutWithLongTimeout(JSONObject payload) {
    HttpURLConnection conn = null;
    try {
        String cleanEndpoint = "pos/checkout";
        String fullUrl = apiUrl + cleanEndpoint;
        URL url = new URL(fullUrl);
        
        if (url.getProtocol().equals("https")) {
            HttpsURLConnection httpsConn = (HttpsURLConnection) url.openConnection();
            CertificatePinningManager.applyPinning(httpsConn);
            conn = httpsConn;
        } else {
            conn = (HttpURLConnection) url.openConnection();
        }
        
        conn.setRequestMethod("POST");
        applyStandardHeaders(conn, true);
        
        // ✅ Timeout مخصص للـ Checkout
        conn.setConnectTimeout(15000);   // 15 ثانية للاتصال
        conn.setReadTimeout(60000);      // 60 ثانية للقراءة
        
        conn.setDoOutput(true);
        
        if (payload != null) {
            byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(input, 0, input.length);
            }
        }
        
        return buildResponse(conn);
        
    } catch (java.net.SocketTimeoutException e) {
        Log.e(TAG, "Checkout timeout — Sale may have succeeded");
        ApiResponse response = new ApiResponse(-1, null, "انتهت المهلة");
        response.isNetworkError = true;
        return response;
    } catch (java.net.UnknownHostException e) {
        ApiResponse response = new ApiResponse(-1, null, "الخادم غير موجود");
        response.isNetworkError = true;
        return response;
    } catch (java.net.ConnectException e) {
        ApiResponse response = new ApiResponse(-1, null, "لا يمكن الاتصال بالخادم");
        response.isNetworkError = true;
        return response;
    } catch (Exception e) {
        Log.e(TAG, "checkoutWithLongTimeout error: " + e.getMessage());
        ApiResponse response = new ApiResponse(-1, null, e.getMessage());
        response.isNetworkError = true;
        return response;
    } finally {
        if (conn != null) conn.disconnect();
    }
}

    public ApiResponse checkout(JSONObject payload) {
        return postWithAuth("/pos/checkout", payload);
    }

    public ApiResponse checkRefundEligibility(JSONObject payload) {
        try {
            return postWithAuth("/pos/check-refund", payload);
        } catch (Exception e) {
            Log.e(TAG, "checkRefundEligibility error: " + e.getMessage());
            return new ApiResponse(-1, null, e.getMessage());
        }
    }

    public ApiResponse processRefund(JSONObject payload) {
        try {
            if (payload == null) {
                payload = new JSONObject();
            }
            if (!payload.has("gate_id")) {
                payload.put("gate_id", getEffectiveGateId());
            }
            
            return postWithAuth("/pos/refund", payload);
        } catch (Exception e) {
            Log.e(TAG, "processRefund error: " + e.getMessage());
            ApiResponse response = new ApiResponse(-1, null, e.getMessage());
            response.isNetworkError = true;
            return response;
        }
    }

    public ApiResponse processRefund(String epc, String serialNo, String reason) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("epc", epc != null ? epc : "");
            payload.put("serial_no", serialNo != null ? serialNo : "");
            payload.put("reason", reason != null ? reason : "مرتجع مبيعات");
            payload.put("gate_id", getEffectiveGateId());
        } catch (Exception ignored) {}
        return processRefund(payload); 
    }

    public ApiResponse searchItem(JSONObject payload) {
        try {
            if (payload == null) {
                payload = new JSONObject();
            }
            if (!payload.has("branch_id") || payload.optInt("branch_id", 0) <= 0) {
                MiraBranchesManager bm = MiraBranchesManager.getInstance(context);
                int branchId = bm.getActiveBranchId();
                payload.put("branch_id", branchId);
            }
            if (!payload.has("gate_id")) {
                payload.put("gate_id", getEffectiveGateId());
            }
            
            return postWithAuth("/pos/search-item", payload);
        } catch (Exception e) {
            Log.e(TAG, "searchItem error: " + e.getMessage());
            ApiResponse response = new ApiResponse(-1, null, e.getMessage());
            response.isNetworkError = true;
            return response;
        }
    }

    public ApiResponse getSalesHistory() {
        return getWithAuth("/pos/sales");
    }

    public ApiResponse getSalesHistoryPaginated(int page, int pageSize) {
        String endpoint = "/pos/sales?page=" + page + "&limit=" + pageSize + "&per_page=" + pageSize;
        return getWithAuth(endpoint);
    }

    public ApiResponse searchPosItem(JSONObject payload) {
        try {
            if (payload == null) {
                payload = new JSONObject();
            }
            
            if (!payload.has("branch_id") || payload.optInt("branch_id", 0) <= 0) {
                MiraBranchesManager bm = MiraBranchesManager.getInstance(context);
                int branchId = bm.getActiveBranchId();
                payload.put("branch_id", branchId);
            }
            
            if (!payload.has("gate_id")) {
                payload.put("gate_id", getEffectiveGateId());
            }
            
            // ✅ إصلاح أمني: إزالة طباعة المفتاح والتوكن الصريح لحماية البيانات في Production Logcat
            Log.d(TAG, "SEARCH POS ITEM START -> Payload Branch=" + payload.optInt("branch_id", 0));
            
            ApiResponse response = postWithAuth("/pos/search-item", payload);
            
            Log.d(TAG, "SEARCH POS ITEM END -> Response Code: " + response.code);
            return response;
        } catch (Exception e) {
            Log.e(TAG, "searchPosItem error: " + e.getMessage(), e);
            ApiResponse response = new ApiResponse(-1, null, e.getMessage());
            response.isNetworkError = true;
            return response;
        }
    }

    public ApiResponse searchSalesHistory(String query, int page, int pageSize) {
        try {
            String encodedQuery = URLEncoder.encode(query, "UTF-8");
            String endpoint = "/pos/sales?page=" + page + "&limit=" + pageSize + "&per_page=" + pageSize + "&search=" + encodedQuery;
            return getWithAuth(endpoint);
        } catch (Exception e) {
            String endpoint = "/pos/sales?page=" + page + "&limit=" + pageSize + "&per_page=" + pageSize + "&search=" + query;
            return getWithAuth(endpoint);
        }
    }

    public ApiResponse getSalesHistoryByPaymentMethod(String paymentMethod, int page, int pageSize) {
        String endpoint = "/pos/sales?page=" + page + "&limit=" + pageSize + "&per_page=" + pageSize + "&payment_method=" + paymentMethod;
        return getWithAuth(endpoint);
    }

    public ApiResponse getSalesStats() {
        return getWithAuth("/pos/sales/stats");
    }

    public ApiResponse getSalesHistoryByDateRange(String fromDate, String toDate, int page, int pageSize) {
        String endpoint = "/pos/sales?page=" + page + "&limit=" + pageSize + "&per_page=" + pageSize;
        if (fromDate != null && !fromDate.isEmpty()) endpoint += "&from=" + fromDate;
        if (toDate != null && !toDate.isEmpty()) endpoint += "&to=" + toDate;
        return getWithAuth(endpoint);
    }

    public ApiResponse getSalesHistoryAdvanced(String search, String paymentMethod, String fromDate, String toDate, int page, int pageSize, int branchId) {
        try {
            StringBuilder endpoint = new StringBuilder("/pos/sales?");
            endpoint.append("page=").append(page);
            endpoint.append("&limit=").append(pageSize);
            
            if (search != null && !search.trim().isEmpty()) {
                endpoint.append("&search=").append(URLEncoder.encode(search, "UTF-8"));
            }
            if (paymentMethod != null && !paymentMethod.equalsIgnoreCase("ALL")) {
                endpoint.append("&payment_method=").append(paymentMethod.toLowerCase());
            }
            if (fromDate != null && !fromDate.isEmpty()) endpoint.append("&from=").append(fromDate);
            if (toDate != null && !toDate.isEmpty()) endpoint.append("&to=").append(toDate);
            if (branchId > 0) endpoint.append("&branch_id=").append(branchId);
            
            return getWithAuth(endpoint.toString());
        } catch (Exception e) {
            ApiResponse response = new ApiResponse(-1, null, e.getMessage());
            response.isNetworkError = true;
            return response;
        }
    }

    public ApiResponse getItemDetail(String code) {
        return getWithAuth("/item/" + code); 
    }
   
    public ApiResponse uploadItemImage(int itemId, File imageFile, boolean isPrimary, String altText) {
        HttpURLConnection conn = null;
        try {
            String fullUrl = apiUrl + "/items/" + itemId + "/images";
            URL url = new URL(fullUrl);
            if (url.getProtocol().equals("https")) {
                HttpsURLConnection httpsConn = (HttpsURLConnection) url.openConnection();
                CertificatePinningManager.applyPinning(httpsConn);
                conn = httpsConn;
            } else {
                conn = (HttpURLConnection) url.openConnection();
            }
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
            return new ApiResponse(-1, null, e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ✅ تحديث دوال المخزون الحساسة لاستخدام المصادقة (getWithAuth)
    public ApiResponse getLastSnapshot(String location) {
        return getWithAuth("/inventory/last-snapshot?location=" + (location != null ? location : "Main Showroom"));
    }

    public ApiResponse getInventoryHistory(String location, int limit) {
        return getWithAuth("/inventory/history/" + location + "?limit=" + limit);
    }

    public ApiResponse getInventoryReports(int branchId, int page, int perPage) {
        String endpoint = "/inventory/sessions?branch_id=" + branchId + "&page=" + page + "&per_page=" + perPage;
        return getWithAuth(endpoint);
    }

    public ApiResponse getHighValueItems(String location, double threshold) {
        return getWithAuth("/inventory/high-value?location=" + (location != null ? location : "Main Showroom") + "&threshold=" + threshold);
    }

    public ApiResponse getInventorySessionsList() {
        return getWithAuth("/inventory/sessions");
    }

    public ApiResponse getInventorySessionDetail(int sessionId) {
        return getWithAuth("/inventory/sessions/" + sessionId);
    }
    
    public ApiResponse deleteInventorySession(int sessionId) {
    JSONObject payload = new JSONObject();
    try {
        payload.put("session_id", sessionId);
        payload.put("gate_id", getEffectiveGateId());
    } catch (Exception ignored) {}
    return postWithAuth("/inventory/sessions/" + sessionId + "/delete", payload);
}

public ApiResponse getInventorySessionItems(int sessionId) {
    return getWithAuth("/inventory/sessions/" + sessionId + "/items");
}

    public ApiResponse startInventorySession(String location, String type) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("location", location != null ? location : "default");
            payload.put("type", type != null ? type : "full");
            MiraBranchesManager bm = MiraBranchesManager.getInstance(context);
            payload.put("branch_id", bm.getActiveBranchId());
        } catch (Exception ignored) {}
        return postWithAuth("/inventory/start-session", payload);
    }

    public ApiResponse startBlindInventory(String location, String category) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("location", location != null ? location : "Main Showroom");
            if (category != null && !category.isEmpty()) {
                payload.put("category", category);
            }
        } catch (Exception ignored) {}
        return postWithAuth("/inventory/blind-start", payload);
    }

    public ApiResponse scanInventoryItem(int sessionId, String code, String rssi) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("session_id", sessionId);
            payload.put("code", code);
            if (rssi != null) payload.put("rssi", rssi);
        } catch (Exception ignored) {}
        return postWithAuth("/inventory/scan-item", payload);
    }

    public ApiResponse closeInventorySession(JSONObject payload) {
        return postWithAuth("/inventory/close-session", payload);
    }

    public ApiResponse compareInventorySessions(int sessionId1, int sessionId2) {
        String endpoint = "/inventory/compare-sessions?session_id_1=" + sessionId1 + "&session_id_2=" + sessionId2;
        return getWithAuth(endpoint);
    }

    public ApiResponse createInventorySnapshot(JSONObject payload) {
        return postWithAuth("/inventory/create-snapshot", payload);
    }

    public ApiResponse getInventorySettings(String location) {
        return getWithAuth("/inventory/settings/" + (location != null ? location : "default"));
    }

    public ApiResponse submitInventoryAuditReport(JSONObject payload) {
        return postWithAuth("/inventory/submit-audit", payload);
    }

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

    public ApiResponse createGate(JSONObject gateData) {
        return postWithAuth("/gates", gateData);
    }

    public ApiResponse getGates() {
        return getWithAuth("/gates");
    }

    public ApiResponse getGatesByBranch(int branchId) {
        return getWithAuth("/gates?branch_id=" + branchId);
    }

    public ApiResponse getGatesStats() {
        return getWithAuth("/gates/stats");
    }

    public ApiResponse getTestEpcs() {
        return getWithAuth("/test-epcs");
    }

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
        return getWithAuth("/settings/" + getEffectiveGateId());
    }

    public ApiResponse saveSettings(JSONObject settings) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("gate_id", getEffectiveGateId());
            payload.put("settings", settings);
        } catch (Exception ignored) {}
        return postWithAuth("/settings", payload);
    }

    public ApiResponse spotlightTags(String[] tags) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("gate_id", getEffectiveGateId());
            payload.put("tags", new JSONArray(Arrays.asList(tags)));
        } catch (Exception ignored) {}
        return postWithAuth("/spotlight", payload);
    }

    public ApiResponse programTag(String serial, String newEpc) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("serial", serial);
            payload.put("new_epc", newEpc);
            payload.put("gate_id", getEffectiveGateId());
        } catch (Exception ignored) {}
        return postWithAuth("/program", payload);
    }

    public ApiResponse lockTag(String serial) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("serial", serial);
            payload.put("gate_id", getEffectiveGateId());
        } catch (Exception ignored) {}
        return postWithAuth("/lock", payload);
    }

    public ApiResponse deactivateTag(String serial) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("serial", serial);
            payload.put("gate_id", getEffectiveGateId());
        } catch (Exception ignored) {}
        return postWithAuth("/deactivate", payload);
    }

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
    
 /**
 * ✅ جلب قطع الفرع — نسخة مبسطة تعتمد على getWithAuth
 * تستخدم نفس Headers Dashboard المُجرَّبة
 */
public ApiResponse getBranchItemsListWithLongTimeout(int branchId) {
    Log.d(TAG, "🌐 getBranchItemsListWithLongTimeout | branchId=" + branchId);
    
    String endpoint = "/branches/" + branchId + "/inventory/items";
    Log.d(TAG, "📡 Calling getWithAuth: " + endpoint);
    
    // ✅ استخدم getWithAuth المُجرَّبة (نفس Headers Dashboard)
    ApiResponse response = getWithAuth(endpoint);
    
    // ✅ Logging تفصيلي
    if (response != null) {
        Log.d(TAG, "═══════════════════════════════════════════");
        Log.d(TAG, "📥 RESPONSE from: " + endpoint);
        Log.d(TAG, "   Code: " + response.code);
        Log.d(TAG, "   isSuccess: " + response.isSuccess);
        Log.d(TAG, "   isNetworkError: " + response.isNetworkError);
        Log.d(TAG, "   error: " + (response.error != null ? response.error : "null"));
        
        String body = response.getBody();
        if (body != null && body.length() > 0) {
            Log.d(TAG, "   body: " + body.substring(0, Math.min(200, body.length())));
        }
        Log.d(TAG, "═══════════════════════════════════════════");
    } else {
        Log.e(TAG, "❌ Null response from getWithAuth");
    }
    
    return response;
}

/**
 * ✅ مساعد لمعرفة اللغة
 */
private boolean isArabicText() {
    try {
        return java.util.Locale.getDefault().getLanguage().equals("ar");
    } catch (Exception e) {
        return true;
    }
}
    
    public ApiResponse getTransferItems(int transferId) {
        return getWithAuth("/transfers/" + transferId + "/items");
    }

    public ApiResponse deleteTransferItem(int transferId, String epc) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("epc", epc);
        } catch (Exception ignored) {}
        return postWithAuth("/transfers/" + transferId + "/items/delete", payload);
    }

    public ApiResponse cancelTransfer(int transferId) {
        return postWithAuth("/transfers/" + transferId + "/cancel", new JSONObject());
    }

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

            return getWithAuth(endpoint.toString());
        } catch (Exception e) {
            return new ApiResponse(-1, null, e.getMessage());
        }
    }

    public List<ApiProvider> getActiveProviders() {
        List<ApiProvider> providers = new ArrayList<>();
        try {
            if (getAuthToken().isEmpty()) {
                Log.w(TAG, "No auth token - returning empty providers list");
                return providers;
            }
            
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
            return new ApiResponse(-1, null, e.getMessage());
        }
    }

    public ApiResponse getGoldRatesAndSettings() {
        return getWithAuth("/gold-rates");
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
    
    public ApiResponse syncOfflineTransactions(JSONArray transactionsArray) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("transactions", transactionsArray);
            payload.put("gate_id", getEffectiveGateId());
            
            return postWithAuth("/inventory/sync-offline", payload);
        } catch (Exception e) {
            return new ApiResponse(-1, null, e.getMessage());
        }
    }

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

    public ApiResponse getBranchInventoryStats(int branchId) {
        return getWithAuth("/branches/" + branchId + "/inventory/stats");
    }

    public ApiResponse checkUsername(String username) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("username", username);
        } catch (Exception ignored) {}
        return postWithAuth("/auth/users/check-username", payload);
    }

    public ApiResponse updateUserPermissions(JSONObject payload) {
        return postWithAuth("/auth/users/update-permissions", payload);
    }

    public ApiResponse logActivity(JSONObject logEntry) {
        return postWithAuth("/activity/log", logEntry);
    }
    public ApiResponse getUnifiedActivity(int branchId, int limit) {
    String endpoint = "/activity/unified?limit=" + limit;
    if (branchId > 0) endpoint += "&branch_id=" + branchId;
    return getWithAuth(endpoint);
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
        } catch (Exception ignored) {}
        return postWithAuth("/auth/users/update-status", payload);
    }

    public ApiResponse getPermissionTemplates() {
        return getWithAuth("/auth/permissions/templates");
    }

    public interface ApiCallback {
    void onSuccess(ApiResponse response);
    void onFailure(String errorMessage);
    void onError(String errorMessage);
}

    // ✅ استدعاء تجاوبي ممتاز باستخدام ExecutorService و Handler لمنع تسريب الذاكرة
    public void get(final String endpoint, final ApiCallback callback) {
        networkExecutor.execute(() -> {
            final ApiResponse response = getWithAuth(endpoint);
            mainHandler.post(() -> {
                if (response != null && response.isSuccess) {
                    callback.onSuccess(response);
                } else {
                    String err = (response != null && response.error != null) ? response.error : "Unknown error";
                    callback.onFailure(err);
                    callback.onError(err);
                }
            });
        });
    }
        // ✅ كلاس داخلي للتوافق مع الملفات القديمة
    public static class ApiResponse {
        public final int code;
        public final JSONObject data;
        public final String error;
        public String message;
        public int statusCode;
        public boolean isNetworkError;
        public String errorCode;
        public final boolean isSuccess;
        private String rawBody;

        public ApiResponse(int code, JSONObject data, String error) {
            this.code = code;
            this.data = data;
            this.error = error;
            this.isSuccess = code >= 200 && code < 300;
            this.rawBody = data != null ? data.toString() : "";
            
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
} // نهاية MiraApiClient
