package com.example.uhf.api;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraServerConfig;
import com.example.uhf.model.ApiProvider;
import com.example.uhf.model.ItemModel;
import com.example.uhf.model.MessageModel;
import com.example.uhf.model.NotificationModel;
import com.example.uhf.tools.CertificatePinningManager;
import com.example.uhf.tools.SessionManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

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
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HttpsURLConnection;

import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * ✅ MiraApiClient v3.1 — النسخة المُحصّنة والمُصحّحة 100%
 *
 * التحسينات والإصلاحات:
 * 1. إصلاح تهيئة accountingService و gson (كانا null) — CRITICAL
 * 2. pingApiAsync() — لا يُجمّد Main Thread
 * 3. reinitializeWithServerAsync() — آمنة للواجهات
 * 4. Cache للـ Auth Token — تقليل Disk I/O
 * 5. refreshCredentialsAsync على background مع حماية من race
 * 6. invalidateTokenCache عند فشل login
 * 7. تنظيف أفضل للخيوط
 * 8. Brotli decompression اختياري (لا يكسر إن لم تكن المكتبة موجودة)
 * 9. ApiResponse.rawBody لا يُهيَّأ خطأً
 */
public class MiraApiClient {

    private static final String TAG = "MiraApiClient";

    // ==================== الإعدادات العامة ====================
    private static final String API_PREFIX = "mira-gate/v1";
    private static final String DEFAULT_BASE_URL = "https://id.ibreg.org/wp-json";

    private static final int DEFAULT_CONNECT_TIMEOUT = 10_000;
    private static final int DEFAULT_READ_TIMEOUT    = 10_000;
    private static final int LONG_CONNECT_TIMEOUT    = 15_000;
    private static final int LONG_READ_TIMEOUT       = 60_000;
    private static final int PING_CONNECT_TIMEOUT    = 5_000;
    private static final int PING_READ_TIMEOUT       = 5_000;

    // ✅ Cache للـ Token
    private static final long TOKEN_CACHE_MS = 30_000;

    private final AccountingApiService accountingService;
    private final Gson gson;

    private final Context context;
    private final MiraServerConfig serverConfig;
    private final SessionManager sessionManager;

    // ==================== حالة الشبكة ====================
    private volatile String baseUrl;
    private volatile String apiUrl;
    private volatile String apiKey;
    private volatile String gateId;
    private final Object credentialsLock = new Object();

    // ✅ Cache للـ Auth Token
    private volatile String cachedToken = null;
    private volatile long cachedTokenTimestamp = 0;

    // ✅ حماية من race في refreshCredentialsAsync
    private final AtomicBoolean credentialsRefreshPending = new AtomicBoolean(false);

    // ==================== الخيوط ====================
    private final ExecutorService executorService;
    private final ExecutorService networkExecutor;
    private final Handler mainHandler;

    // ==================== Singleton ====================
    private static volatile MiraApiClient instance;
    private static final Object INSTANCE_LOCK = new Object();

    public static MiraApiClient getInstance(Context context) {
        if (instance == null) {
            synchronized (INSTANCE_LOCK) {
                if (instance == null) {
                    instance = new MiraApiClient(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    public static void reloadInstance(Context context) {
        MiraApiClient old;
        synchronized (INSTANCE_LOCK) {
            old = instance;
            instance = new MiraApiClient(context.getApplicationContext());
        }
        if (old != null) {
            old.shutdown();
        }
    }

    // ==================== البناء ====================
    private MiraApiClient(Context context) {
        this.context = context;
        this.serverConfig = MiraServerConfig.getInstance(context);
        this.sessionManager = new SessionManager(context);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executorService = Executors.newFixedThreadPool(4, new NamedThreadFactory("MiraApi-IO"));
        this.networkExecutor = Executors.newFixedThreadPool(4, new NamedThreadFactory("MiraApi-Net"));

        // ✅ إصلاح CRITICAL: تهيئة Gson
        this.gson = new GsonBuilder()
                .setLenient()
                .serializeNulls()
                .create();

        // ✅ إصلاح CRITICAL: تهيئة AccountingApiService
        // ملاحظة: تأكد من أن الـ Constructor يقبل (MiraApiClient, Context)
        // إذا كان يقبل (Context) فقط، عدّل السطر التالي:
        // ═══════════════════════════════════════════════════════════
//  ✅ OkHttpClient مع Interceptor لإضافة X-MIRA-API-Key
//  ⚠️ هذا نفس النظام الذي يستخدمه ActivityAPI
// ═══════════════════════════════════════════════════════════
okhttp3.OkHttpClient accountingOkHttp = new okhttp3.OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .addInterceptor(chain -> {
        okhttp3.Request original = chain.request();

        // ✅ اقرأ القيم مباشرة من sessionManager
        String token = "";
        String apiKey = "";
        String gateId = "";
        
        try {
            token = sessionManager.getAuthToken();
            apiKey = sessionManager.getApiKey();
            gateId = sessionManager.getGateId();
        } catch (Exception e) {
            Log.e(TAG, "Auth read error", e);
        }

        // ✅ Log للتشخيص
        Log.e(TAG, "═══════════════════════════════");
        Log.e(TAG, "URL: " + original.url());
        Log.e(TAG, "API Key: " + (apiKey == null || apiKey.isEmpty() 
            ? "❌ NULL" : "✅ " + apiKey.substring(0, Math.min(15, apiKey.length())) + "..."));
        Log.e(TAG, "Token: " + (token == null || token.isEmpty() 
            ? "❌ NULL" : "✅ " + token.length() + " chars"));
        Log.e(TAG, "═══════════════════════════════");

        // ✅ ابنِ الطلب مع الرؤوس
        okhttp3.Request.Builder builder = original.newBuilder()
            .header("Accept", "application/json");

        // ✅ الأهم: X-MIRA-API-Key (هذا ما يقرأه ApiKeyManager)
        if (apiKey != null && !apiKey.isEmpty()) {
            builder.header("X-MIRA-API-Key", apiKey);
        }
        
        // ✅ Authorization (للتوافق)
        if (token != null && !token.isEmpty()) {
            builder.header("Authorization", "Bearer " + token);
        }
        
        // ✅ Gate ID
        if (gateId != null && !gateId.isEmpty()) {
            builder.header("X-MIRA-Gate-ID", gateId);
        }

        return chain.proceed(builder.build());
    })
    .build();

// ═══════════════════════════════════════════════════════════
//  ✅ Retrofit مع OkHttpClient المخصص
// ═══════════════════════════════════════════════════════════
this.accountingService = new retrofit2.Retrofit.Builder()
    .baseUrl("https://id.ibreg.org/wp-json/mira/v2/")
    .client(accountingOkHttp)  // ← ⚠️⚠️⚠️ الأهم! هذا السطر مفقود حالياً
    .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
    .build()
    .create(AccountingApiService.class);

        String savedBaseUrl = sessionManager.getServerUrl();
        if (savedBaseUrl == null || savedBaseUrl.isEmpty()) {
            savedBaseUrl = serverConfig.getBaseUrl();
            if (savedBaseUrl == null || savedBaseUrl.isEmpty()) {
                savedBaseUrl = DEFAULT_BASE_URL;
            }
            sessionManager.setServerUrl(savedBaseUrl);
            serverConfig.setBaseUrl(savedBaseUrl);
        }
        this.baseUrl = normalizeBaseUrl(savedBaseUrl);

        // ✅ قراءة أولية — سريعة عادةً (< 10ms)
        refreshCredentials();
    }

    /**
     * ✅ إغلاق كل الخيوط
     */
    public void shutdown() {
        Log.d(TAG, "shutdown() — closing executors");
        safeShutdown(executorService);
        safeShutdown(networkExecutor);
    }

    private void safeShutdown(ExecutorService executor) {
        if (executor == null || executor.isShutdown()) return;
        try {
            executor.shutdown();
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ==================== إدارة الاعتمادات ====================
    private void refreshCredentials() {
        synchronized (credentialsLock) {
            String cleanBaseUrl = normalizeBaseUrl(baseUrl);
            this.apiUrl = cleanBaseUrl + API_PREFIX + "/";

            String key = sessionManager.getApiKey();
            if (key == null || key.isEmpty()) {
                key = serverConfig.getApiKey();
            }
            this.apiKey = key != null ? key : "";

            String gate = sessionManager.getGateId();
            if (gate == null || gate.isEmpty()) {
                gate = serverConfig.getGateId();
            }
            this.gateId = gate != null ? gate : "";

            // ✅ إجبار تحديث Cache
            this.cachedToken = null;
            this.cachedTokenTimestamp = 0;

            Log.d(TAG, "Credentials refreshed: URL=" + apiUrl
                    + ", Key=" + (apiKey.isEmpty() ? "EMPTY" : "EXISTS")
                    + ", Gate=" + (gateId.isEmpty() ? "EMPTY" : gateId));
        }
    }

    /**
     * ✅ refreshCredentialsAsync — على background مع حماية race
     */
    private void refreshCredentialsAsync() {
        if (!credentialsRefreshPending.compareAndSet(false, true)) {
            Log.d(TAG, "refreshCredentialsAsync — already pending, skipped");
            return;
        }
        executorService.execute(() -> {
            try {
                refreshCredentials();
            } finally {
                credentialsRefreshPending.set(false);
            }
        });
    }

    private String normalizeBaseUrl(String url) {
        if (url == null) return "";
        String trimmed = url.trim();
        if (trimmed.isEmpty()) return "";
        return trimmed.endsWith("/") ? trimmed : trimmed + "/";
    }

    public boolean reinitializeWithServer(String newServerUrl) {
        if (newServerUrl == null || newServerUrl.trim().isEmpty()) {
            Log.e(TAG, "reinitializeWithServer: empty URL");
            return false;
        }
        try {
            String cleanUrl = normalizeBaseUrl(newServerUrl);
            sessionManager.setServerUrl(cleanUrl);
            serverConfig.setBaseUrl(cleanUrl);

            synchronized (credentialsLock) {
                this.baseUrl = cleanUrl;
            }
            refreshCredentials();

            return pingApi();
        } catch (Exception e) {
            Log.e(TAG, "Reinitialize error: " + e.getMessage(), e);
            return false;
        }
    }

    // ==================== ✅ دوال Async ====================
    public interface PingCallback {
        void onResult(boolean isSuccess);
    }

    /**
     * ✅ Ping غير متزامن — آمن للاستخدام من Main Thread
     */
    public void pingApiAsync(PingCallback callback) {
        networkExecutor.execute(() -> {
            final boolean result = pingApi();
            mainHandler.post(() -> {
                if (callback != null) {
                    try {
                        callback.onResult(result);
                    } catch (Exception e) {
                        Log.e(TAG, "pingApiAsync callback error", e);
                    }
                }
            });
        });
    }

    /**
     * ✅ إعادة تهيئة async — لا تُجمّد Main Thread
     */
    public void reinitializeWithServerAsync(String newServerUrl, PingCallback callback) {
        executorService.execute(() -> {
            boolean success = false;
            try {
                if (newServerUrl != null && !newServerUrl.trim().isEmpty()) {
                    String cleanUrl = normalizeBaseUrl(newServerUrl);
                    sessionManager.setServerUrl(cleanUrl);
                    serverConfig.setBaseUrl(cleanUrl);

                    synchronized (credentialsLock) {
                        this.baseUrl = cleanUrl;
                    }
                    refreshCredentials();

                    success = pingApi();
                    Log.d(TAG, "reinitializeWithServerAsync result: " + success);
                } else {
                    Log.e(TAG, "reinitializeWithServerAsync: empty URL");
                }
            } catch (Exception e) {
                Log.e(TAG, "reinitializeWithServerAsync error: " + e.getMessage(), e);
                success = false;
            }

            final boolean finalSuccess = success;
            mainHandler.post(() -> {
                if (callback != null) {
                    try {
                        callback.onResult(finalSuccess);
                    } catch (Exception e) {
                        Log.e(TAG, "reinitialize callback error", e);
                    }
                }
            });
        });
    }

    // ==================== الدوال الأساسية ===================
    private String getEffectiveApiKey() {
        String key = apiKey;
        if (key == null || key.isEmpty()) {
            Log.e(TAG, "⛔ API Key is empty! User must login first.");
            return "";
        }
        return key;
    }

    private String getEffectiveGateId() {
        String gate = gateId;
        if (gate == null || gate.isEmpty()) {
            Log.e(TAG, "⛔ Gate ID is empty! User must login first.");
            return "";
        }
        return gate;
    }

    /**
     * ✅ Cache مُحسّن للـ Auth Token
     */
    private String getAuthToken() {
        long now = System.currentTimeMillis();
        String cached = cachedToken;
        if (cached != null && (now - cachedTokenTimestamp) < TOKEN_CACHE_MS) {
            return cached;
        }

        String token = sessionManager.getAuthToken();

        if (token == null || token.isEmpty()) {
            if (sessionManager.isLoggedIn()) {
                String fallback = serverConfig.getAuthToken();
                if (fallback != null && !fallback.isEmpty()) {
                    Log.d(TAG, "Using fallback token");
                    token = fallback;
                }
            }
        }

        String result = token != null ? token : "";
        cachedToken = result;
        cachedTokenTimestamp = now;

        return result;
    }

    /**
     * ✅ إجبار تحديث Token Cache
     */
    public void invalidateTokenCache() {
        cachedToken = null;
        cachedTokenTimestamp = 0;
        Log.d(TAG, "Token cache invalidated");
    }

    public boolean hasValidCredentials() {
        return !getEffectiveApiKey().isEmpty() && !getEffectiveGateId().isEmpty();
    }

    public boolean isAuthenticated() {
        String token = getAuthToken();
        return !token.isEmpty();
    }

    public String getGateId() { return getEffectiveGateId(); }
    public String getApiUrl() { return apiUrl; }

    public String getAuthDiagnostics() {
        StringBuilder sb = new StringBuilder();
        String token = getAuthToken();
        String key = apiKey != null ? apiKey : "";
        String gate = gateId != null ? gateId : "";

        sb.append("Token: ").append(!token.isEmpty()
                ? "✅ موجود (" + token.length() + " حرف)" : "❌ مفقود").append("\n");
        sb.append("API Key: ").append(!key.isEmpty()
                ? "✅ موجود" : "❌ مفقود").append("\n");
        sb.append("Gate ID: ").append(!gate.isEmpty()
                ? "✅ " + gate : "❌ مفقود").append("\n");
        sb.append("Base URL: ").append(apiUrl).append("\n");

        return sb.toString();
    }

    // ==================== الرؤوس الموحدة ====================
    private void applyStandardHeaders(HttpURLConnection conn, boolean requiresAuth) {
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "application/json");

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

    // ==================== المُنفِّذ الموحَّد للطلبات ====================
    public ApiResponse executeRequest(String method,
                                       String endpoint,
                                       JSONObject payload,
                                       boolean requiresAuth,
                                       int connectTimeout,
                                       int readTimeout) {
        HttpURLConnection conn = null;
        try {
            String cleanEndpoint = endpoint.startsWith("/") ? endpoint.substring(1) : endpoint;
            String fullUrl = apiUrl + cleanEndpoint;
            URL url = new URL(fullUrl);

            if ("https".equalsIgnoreCase(url.getProtocol())) {
                HttpsURLConnection httpsConn = (HttpsURLConnection) url.openConnection();
                CertificatePinningManager.applyPinning(httpsConn);
                conn = httpsConn;
            } else {
                conn = (HttpURLConnection) url.openConnection();
            }

            conn.setRequestMethod(method);
            applyStandardHeaders(conn, requiresAuth);
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);

            if (payload != null) {
                conn.setDoOutput(true);
                byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(input, 0, input.length);
                }
            }

            return buildResponse(conn);

        } catch (java.net.UnknownHostException e) {
            return networkError("الخادم غير موجود");
        } catch (java.net.ConnectException e) {
            return networkError("لا يمكن الاتصال بالخادم");
        } catch (java.net.SocketTimeoutException e) {
            return networkError("انتهت المهلة - السيرفر بطيء");
        } catch (Exception e) {
            Log.e(TAG, "executeRequest error: " + e.getMessage(), e);
            return networkError(e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private ApiResponse networkError(String message) {
        ApiResponse response = new ApiResponse(-1, null, message);
        response.isNetworkError = true;
        return response;
    }

    // ==================== دوال الشبكة العامة ====================
    public ApiResponse getWithAuth(String endpoint) {
        return executeRequest("GET", endpoint, null, true,
                DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    public ApiResponse getWithAuthLongTimeout(String endpoint) {
        return executeRequest("GET", endpoint, null, true,
                LONG_CONNECT_TIMEOUT, LONG_READ_TIMEOUT);
    }

    public ApiResponse postWithAuth(String endpoint, JSONObject payload) {
        return executeRequest("POST", endpoint, payload, true,
                DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    public ApiResponse postWithAuthLongTimeout(String endpoint, JSONObject payload) {
        return executeRequest("POST", endpoint, payload, true,
                LONG_CONNECT_TIMEOUT, LONG_READ_TIMEOUT);
    }

    private ApiResponse post(String endpoint, JSONObject payload) {
        return executeRequest("POST", endpoint, payload, false,
                DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    private ApiResponse get(String endpoint) {
        return executeRequest("GET", endpoint, null, false,
                DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    // ==================== بناء الاستجابة ====================
    private ApiResponse buildResponse(HttpURLConnection conn) throws Exception {
        int responseCode = conn.getResponseCode();

        Log.d(TAG, "═══════════════════════════════════════════");
        Log.d(TAG, "📥 RESPONSE CODE: " + responseCode);
        Log.d(TAG, "   Content-Encoding: " + conn.getContentEncoding());
        Log.d(TAG, "   Content-Length: " + conn.getContentLength());

        InputStream rawStream = (responseCode >= 200 && responseCode < 300)
                ? conn.getInputStream()
                : conn.getErrorStream();

        if (rawStream == null) {
            return new ApiResponse(responseCode, null, "Empty response");
        }

        // ✅ فك الترميز
        InputStream is = rawStream;
        String encoding = conn.getContentEncoding();

        try {
            if (encoding != null && !encoding.isEmpty()) {
                String enc = encoding.toLowerCase(Locale.US).trim();

                if (enc.contains("br")) {
                    Log.d(TAG, "   ✅ Decompressing Brotli...");
                    is = wrapBrotli(rawStream);
                } else if (enc.contains("gzip")) {
                    Log.d(TAG, "   ✅ Decompressing Gzip...");
                    is = new GZIPInputStream(rawStream);
                } else if (enc.contains("deflate")) {
                    Log.d(TAG, "   ✅ Decompressing Deflate...");
                    is = new InflaterInputStream(rawStream);
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "   ❌ Decompression error: " + e.getMessage());
            is = rawStream;
        }

        // ✅ قراءة الاستجابة
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                response.append(buffer, 0, read);
            }
        }

        String responseBody = response.toString().trim();

        Log.d(TAG, "   ✅ Body length: " + responseBody.length());
        Log.d(TAG, "   Preview: " + (responseBody.length() > 100
                ? responseBody.substring(0, 100) + "..." : responseBody));
        Log.d(TAG, "═══════════════════════════════════════════");

        // ✅ تحليل JSON
        JSONObject json = null;
        String serverMessage = null;

        if (!responseBody.isEmpty()) {
            try {
                json = new JSONObject(responseBody);
                serverMessage = json.optString("message", null);
            } catch (Exception e) {
                Log.w(TAG, "   ⚠️ Non-JSON");
            }
        }

        boolean isSuccess = responseCode >= 200 && responseCode < 300;
        String errorMessage = isSuccess ? null
                : (serverMessage != null ? serverMessage : responseBody);

        ApiResponse apiResponse = new ApiResponse(responseCode, json, errorMessage);
        apiResponse.setBody(responseBody);

        if (isSuccess && serverMessage != null) {
            apiResponse.message = serverMessage;
        }

        return apiResponse;
    }

    /**
     * ✅ Brotli decompression — باستخدام Reflection لتجنب كسر البناء
     * إذا كانت مكتبة brotli غير موجودة → fallback للسلسلة الأصلية
     */
    private InputStream wrapBrotli(InputStream rawStream) {
        try {
            Class<?> clazz = Class.forName("org.brotli.dec.BrotliInputStream");
            return (InputStream) clazz
                    .getConstructor(InputStream.class)
                    .newInstance(rawStream);
        } catch (Throwable t) {
            Log.w(TAG, "Brotli library not available, using raw stream", t);
            return rawStream;
        }
    }
    
    // ═══════════════════════════════════════════════════════════
//  ✅ طلبات mira/v2 (للإعداد المحاسبي)
// ═══════════════════════════════════════════════════════════

public ApiResponse getAccounting(String endpoint) {
    return accountingRequest("GET", endpoint, null);
}

public ApiResponse postAccounting(String endpoint, JSONObject payload) {
    return accountingRequest("POST", endpoint, payload);
}

private ApiResponse accountingRequest(String method, String endpoint, JSONObject payload) {
    HttpURLConnection conn = null;
    try {
        String cleanEndpoint = endpoint.startsWith("/") ? endpoint.substring(1) : endpoint;
        String fullUrl = "https://id.ibreg.org/wp-json/mira/v2/" + cleanEndpoint;
        URL url = new URL(fullUrl);
        
        HttpsURLConnection httpsConn = (HttpsURLConnection) url.openConnection();
        CertificatePinningManager.applyPinning(httpsConn);
        conn = httpsConn;
        
        conn.setRequestMethod(method);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "application/json");
        
        String apiKey = sessionManager.getApiKey();
        String token = sessionManager.getAuthToken();
        
        if (apiKey != null && !apiKey.isEmpty()) {
            conn.setRequestProperty("X-MIRA-API-Key", apiKey);
        }
        if (token != null && !token.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        
        conn.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT);
        conn.setReadTimeout(DEFAULT_READ_TIMEOUT);
        
        if (payload != null) {
            conn.setDoOutput(true);
            byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(input, 0, input.length);
            }
        }
        
        Log.e(TAG, "📡 accounting " + method + ": " + fullUrl);
        
        return buildResponse(conn);
        
    } catch (Exception e) {
        Log.e(TAG, "accountingRequest error: " + e.getMessage(), e);
        return networkError(e.getMessage());
    } finally {
        if (conn != null) conn.disconnect();
    }
}

    // ==================== المصادقة ====================
    public ApiResponse login(String username, String password, String deviceId) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("username", username);
            payload.put("password", password);
            payload.put("device_id", deviceId != null ? deviceId : "");

            String effectiveGate = getEffectiveGateId();
            if (effectiveGate.isEmpty()) {
                effectiveGate = deviceId != null && !deviceId.isEmpty()
                        ? deviceId : "default-gate";
                serverConfig.setGateId(effectiveGate);
            }

            payload.put("gate_id", effectiveGate);
            payload.put("app_version", "MIRA-ANDROID-v3.0");
        } catch (Exception ignored) {}

        ApiResponse response = post("/auth/login", payload);

        if (response.isSuccess && response.data != null) {
            String newApiKey = response.data.optString("api_key", "");
            String newGateId = response.data.optString("gate_id", "");
            String authToken = response.data.optString("token", "");
            long expiresIn = response.data.optLong("expires_in", 0);

            // 1. API Key
            if (!newApiKey.isEmpty()) {
                sessionManager.saveApiKey(newApiKey);
                serverConfig.setApiKey(newApiKey);
            }

            // 2. Gate ID
            String effectiveGateId = !newGateId.isEmpty() ? newGateId
                    : (deviceId != null && !deviceId.isEmpty() ? deviceId : "");
            if (!effectiveGateId.isEmpty()) {
                sessionManager.saveGateId(effectiveGateId);
                serverConfig.setGateId(effectiveGateId);
            }

            // 3. Auth Token
            if (!authToken.isEmpty()) {
                if (expiresIn > 0) {
                    sessionManager.saveAuthToken(authToken, expiresIn);
                } else {
                    sessionManager.saveAuthToken(authToken);
                }
                serverConfig.saveSession(newApiKey, effectiveGateId, authToken);
                Log.d(TAG, "✅ Auth token saved (" + authToken.length() + " chars)");
            }

            // 4. User info
            try {
                String uname = response.data.optString("username", username);
                String role = response.data.optString("role", "");
                String displayName = response.data.optString("display_name", "");
                int userId = response.data.optInt("user_id", 0);
                String email = response.data.optString("email", "");
                String phone = response.data.optString("phone", "");

                if (!uname.isEmpty()) sessionManager.setUsername(uname);
                if (!role.isEmpty()) sessionManager.setRole(role);
                if (!displayName.isEmpty()) sessionManager.setDisplayName(displayName);
                if (userId > 0) sessionManager.setUserId(userId);
                if (!email.isEmpty()) sessionManager.setEmail(email);
                if (!phone.isEmpty()) sessionManager.setPhone(phone);

                sessionManager.createLoginSession(
                        uname, role, authToken,
                        serverConfig.getBaseUrl(),
                        expiresIn > 0 ? expiresIn : 86400 * 7);
            } catch (Exception e) {
                Log.e(TAG, "Save user info error: " + e.getMessage());
            }

            // ✅ 5. تحديث Credentials — على background
            refreshCredentialsAsync();

        } else {
            // ✅ إصلاح: إبطال Cache عند فشل login
            invalidateTokenCache();
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
                    if (!authToken.isEmpty()) {
                        sessionManager.saveAuthToken(authToken);
                    }
                    refreshCredentialsAsync();
                }
            } else {
                invalidateTokenCache();
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

        ApiResponse response = post("/auth/logout", payload);

        // ✅ تنظيف Cache
        invalidateTokenCache();

        try {
            if (serverConfig != null) {
                serverConfig.clearSession();
            }
        } catch (Exception e) {
            Log.e(TAG, "logout cleanup error: " + e.getMessage());
        }

        return response;
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

    public ApiResponse changePassword(String currentPassword, String newPassword) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("current_password", currentPassword);
            payload.put("new_password", newPassword);
        } catch (Exception ignored) {}
        return postWithAuth("/auth/change-password", payload);
    }

    // ==================== الرسائل والإشعارات ====================
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

    public interface NotificationCallback {
        void onSuccess(List<NotificationModel> notifications);
        void onError(String error);
    }

    public interface SearchCallback {
        void onSuccess(List<ItemModel> results);
        void onError(String error);
    }

    public void getUnreadMessages(String userId, MessageCallback callback) {
        executorService.execute(() -> {
            try {
                String endpoint = "/messages/unread?user_id=" + userId;
                ApiResponse response = getWithAuth(endpoint);

                if (response.isSuccess && response.data != null) {
                    List<MessageModel> messages = new ArrayList<>();
                    JSONArray arr = response.data.optJSONArray("messages");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            MessageModel m = parseMessageFromJson(arr.getJSONObject(i));
                            if (m != null) messages.add(m);
                        }
                    }
                    mainHandler.post(() -> callback.onSuccess(messages));
                } else {
                    String err = response.error != null ? response.error : "Failed";
                    mainHandler.post(() -> callback.onError(err));
                }
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public void getAllMessages(String userId, MessageCallback callback) {
        executorService.execute(() -> {
            try {
                String endpoint = "/messages?user_id=" + userId;
                ApiResponse response = getWithAuth(endpoint);

                if (response.isSuccess && response.data != null) {
                    List<MessageModel> messages = new ArrayList<>();
                    JSONArray arr = response.data.optJSONArray("messages");
                    if (arr == null) arr = response.data.optJSONArray("data");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            MessageModel m = parseMessageFromJson(arr.getJSONObject(i));
                            if (m != null) messages.add(m);
                        }
                    }
                    mainHandler.post(() -> callback.onSuccess(messages));
                } else {
                    String err = response.error != null ? response.error : "Failed";
                    mainHandler.post(() -> callback.onError(err));
                }
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

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
                    if (response.data != null) {
                        JSONObject msgJson = response.data.optJSONObject("message");
                        if (msgJson != null) {
                            MessageModel sent = parseMessageFromJson(msgJson);
                            if (sent != null) {
                                mainHandler.post(() -> callback.onSuccess(sent));
                                return;
                            }
                        }
                    }
                    mainHandler.post(() -> callback.onSuccess(message));
                } else {
                    String err = response.error != null ? response.error : "Failed";
                    mainHandler.post(() -> callback.onError(err));
                }
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public void getUnreadCount(String userId, UnreadCountCallback callback) {
        executorService.execute(() -> {
            try {
                String endpoint = "/messages/unread-count?user_id=" + userId;
                ApiResponse response = getWithAuth(endpoint);

                if (response.isSuccess && response.data != null) {
                    int unreadCount = response.data.optInt("unread_count", 0);
                    mainHandler.post(() -> callback.onSuccess(unreadCount));
                } else {
                    String err = response.error != null ? response.error : "Failed";
                    mainHandler.post(() -> callback.onError(err));
                }
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public void getNotifications(String userId, NotificationCallback callback) {
        executorService.execute(() -> {
            try {
                String endpoint = "/notifications?user_id=" + userId;
                ApiResponse response = getWithAuth(endpoint);

                if (response.isSuccess && response.data != null) {
                    List<NotificationModel> notifications = new ArrayList<>();
                    JSONArray arr = response.data.optJSONArray("notifications");
                    if (arr == null) arr = response.data.optJSONArray("data");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            NotificationModel n = parseNotificationFromJson(arr.getJSONObject(i));
                            if (n != null) notifications.add(n);
                        }
                    }
                    mainHandler.post(() -> callback.onSuccess(notifications));
                } else {
                    String err = response.error != null ? response.error : "Failed";
                    mainHandler.post(() -> callback.onError(err));
                }
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
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
                    String err = response.error != null ? response.error : "Search failed";
                    mainHandler.post(() -> callback.onError(err));
                }
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

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

    // ==================== الملف الشخصي ====================
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

    // ==================== المستخدمون ====================
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

    public ApiResponse getUserPermissions(int userId) {
        return getWithAuth("/auth/users/" + userId + "/permissions");
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

    // ==================== التصريح ====================
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

    // ═══════════════════════════════════════════════════════════
//  📝 قيود التسوية — بعد اعتماد الإعداد
// ═══════════════════════════════════════════════════════════

/**
 * ✅ إنشاء قيد تسوية مخصص
 * 
 * @param payload {entry_date, reference, description, lines[]}
 */
public ApiResponse createAdjustingEntry(JSONObject payload) {
    return postAccounting("setup/adjusting-entry", payload);
}

/**
 * ✅ إضافة رصيد جديد (واجهة مبسطة)
 * 
 * @param type   cash|bank|gold|receivable|payable|fixed_asset|diamond
 * @param amount المبلغ (أو الوزن للذهب)
 * @param extra  {karat, description, account_id}
 */
public ApiResponse addPostApprovalBalance(String type, double amount,
                                          JSONObject extra) {
    try {
        JSONObject payload = new JSONObject();
        payload.put("type", type);
        payload.put("amount", amount);
        if (extra != null) {
            if (extra.has("karat")) {
                payload.put("karat", extra.optString("karat"));
            }
            if (extra.has("description")) {
                payload.put("description", extra.optString("description"));
            }
            if (extra.has("account_id")) {
                payload.put("account_id", extra.optInt("account_id"));
            }
        }
        return postAccounting("setup/add-balance", payload);
    } catch (Exception e) {
        Log.e(TAG, "addPostApprovalBalance error: " + e.getMessage());
        return new ApiResponse(-1, null, e.getMessage());
    }
}

    // ==================== Ping ====================
    /**
     * ⚠️ Ping — SYNC
     *
     * ⚠️⚠️ تحذير: هذه الدالة Synchronous!
     * - إذا استُدعيت من Main Thread → ANR!
     * - استخدم pingApiAsync() من الواجهات
     */
    public boolean pingApi() {
        HttpURLConnection conn = null;
        try {
            String cleanUrl = this.apiUrl;
            if (cleanUrl == null || cleanUrl.isEmpty()) {
                return false;
            }
            if (cleanUrl.endsWith("/")) {
                cleanUrl = cleanUrl.substring(0, cleanUrl.length() - 1);
            }

            URL url = new URL(cleanUrl + "/ping");
            if ("https".equalsIgnoreCase(url.getProtocol())) {
                HttpsURLConnection httpsConn = (HttpsURLConnection) url.openConnection();
                CertificatePinningManager.applyPinning(httpsConn);
                conn = httpsConn;
            } else {
                conn = (HttpURLConnection) url.openConnection();
            }
            conn.setRequestMethod("GET");
            applyStandardHeaders(conn, true);
            conn.setConnectTimeout(PING_CONNECT_TIMEOUT);
            conn.setReadTimeout(PING_READ_TIMEOUT);

            int responseCode = conn.getResponseCode();
            return responseCode >= 200 && responseCode < 400;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ==================== لوحة التحكم ====================
    public ApiResponse getDashboardSummary() {
        return getWithAuth("/dashboard/summary");
    }

    public ApiResponse getDashboardFull(int branchId) {
        String endpoint = "/dashboard/full";
        if (branchId > 0) endpoint += "?branch_id=" + branchId;
        return getWithAuth(endpoint);
    }

    public ApiResponse getDashboardFull() {
        return getDashboardFull(0);
    }

    // ==================== POS ====================
    public ApiResponse checkoutWithLongTimeout(JSONObject payload) {
        try {
            if (payload == null) payload = new JSONObject();
            if (!payload.has("idempotency_key")) {
                payload.put("idempotency_key", UUID.randomUUID().toString());
            }
            if (!payload.has("gate_id")) {
                payload.put("gate_id", getEffectiveGateId());
            }
        } catch (Exception e) {
            Log.e(TAG, "checkout payload prep error: " + e.getMessage());
        }
        return executeRequest("POST", "pos/checkout", payload, true,
                LONG_CONNECT_TIMEOUT, LONG_READ_TIMEOUT);
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
            if (payload == null) payload = new JSONObject();
            if (!payload.has("gate_id")) {
                payload.put("gate_id", getEffectiveGateId());
            }
            if (!payload.has("idempotency_key")) {
                payload.put("idempotency_key", UUID.randomUUID().toString());
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
            if (payload == null) payload = new JSONObject();
            if (!payload.has("branch_id") || payload.optInt("branch_id", 0) <= 0) {
                MiraBranchesManager bm = MiraBranchesManager.getInstance(context);
                payload.put("branch_id", bm.getActiveBranchId());
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

    public ApiResponse searchPosItem(JSONObject payload) {
        try {
            if (payload == null) payload = new JSONObject();
            if (!payload.has("branch_id") || payload.optInt("branch_id", 0) <= 0) {
                MiraBranchesManager bm = MiraBranchesManager.getInstance(context);
                payload.put("branch_id", bm.getActiveBranchId());
            }
            if (!payload.has("gate_id")) {
                payload.put("gate_id", getEffectiveGateId());
            }

            Log.d(TAG, "SEARCH POS ITEM START");
            ApiResponse response = postWithAuth("/pos/search-item", payload);
            Log.d(TAG, "SEARCH POS ITEM END -> Code: " + response.code);
            return response;
        } catch (Exception e) {
            Log.e(TAG, "searchPosItem error: " + e.getMessage(), e);
            ApiResponse response = new ApiResponse(-1, null, e.getMessage());
            response.isNetworkError = true;
            return response;
        }
    }

    public ApiResponse getSalesHistory() {
        return getWithAuth("/pos/sales");
    }

    public ApiResponse getSalesHistoryPaginated(int page, int pageSize) {
        return getWithAuth("/pos/sales?page=" + page
                + "&limit=" + pageSize + "&per_page=" + pageSize);
    }

    public ApiResponse searchSalesHistory(String query, int page, int pageSize) {
        try {
            String encodedQuery = URLEncoder.encode(query, "UTF-8");
            return getWithAuth("/pos/sales?page=" + page
                    + "&limit=" + pageSize + "&per_page=" + pageSize
                    + "&search=" + encodedQuery);
        } catch (Exception e) {
            return getWithAuth("/pos/sales?page=" + page
                    + "&limit=" + pageSize + "&per_page=" + pageSize
                    + "&search=" + query);
        }
    }

    public ApiResponse getSalesHistoryByPaymentMethod(String paymentMethod, int page, int pageSize) {
        return getWithAuth("/pos/sales?page=" + page
                + "&limit=" + pageSize + "&per_page=" + pageSize
                + "&payment_method=" + paymentMethod);
    }
    
    // ═══════════════════════════════════════════════════════════
//  ✅ طرق الدفع (POS Payment Methods)
// ═══════════════════════════════════════════════════════════

public ApiResponse getPaymentMethods() {
    return getWithAuth("/pos/payment-methods");
}

    public ApiResponse getSalesStats() {
        return getWithAuth("/pos/sales/stats");
    }

    public ApiResponse getSalesHistoryByDateRange(String fromDate, String toDate, int page, int pageSize) {
        String endpoint = "/pos/sales?page=" + page
                + "&limit=" + pageSize + "&per_page=" + pageSize;
        if (fromDate != null && !fromDate.isEmpty()) endpoint += "&from=" + fromDate;
        if (toDate != null && !toDate.isEmpty()) endpoint += "&to=" + toDate;
        return getWithAuth(endpoint);
    }

    public ApiResponse getSalesHistoryAdvanced(String search, String paymentMethod,
                                               String fromDate, String toDate,
                                               int page, int pageSize, int branchId) {
        try {
            StringBuilder endpoint = new StringBuilder("/pos/sales?");
            endpoint.append("page=").append(page);
            endpoint.append("&limit=").append(pageSize);

            if (search != null && !search.trim().isEmpty()) {
                endpoint.append("&search=").append(URLEncoder.encode(search, "UTF-8"));
            }
            if (paymentMethod != null && !paymentMethod.equalsIgnoreCase("ALL")) {
                endpoint.append("&payment_method=").append(paymentMethod.toLowerCase(Locale.ROOT));
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

    // ==================== رفع الصور ====================
    public ApiResponse uploadItemImage(int itemId, File imageFile, boolean isPrimary, String altText) {
        HttpURLConnection conn = null;
        try {
            String fullUrl = apiUrl + "/items/" + itemId + "/images";
            URL url = new URL(fullUrl);
            if ("https".equalsIgnoreCase(url.getProtocol())) {
                HttpsURLConnection httpsConn = (HttpsURLConnection) url.openConnection();
                CertificatePinningManager.applyPinning(httpsConn);
                conn = httpsConn;
            } else {
                conn = (HttpURLConnection) url.openConnection();
            }
            conn.setRequestMethod("POST");
            applyStandardHeaders(conn, true);
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(30_000);
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

    // ==================== المخزون ====================
    public ApiResponse getLastSnapshot(String location) {
        return getWithAuth("/inventory/last-snapshot?location="
                + (location != null ? location : "Main Showroom"));
    }

    public ApiResponse getInventoryHistory(String location, int limit) {
        return getWithAuth("/inventory/history/" + location + "?limit=" + limit);
    }

    public ApiResponse getInventoryReports(int branchId, int page, int perPage) {
        return getWithAuth("/inventory/sessions?branch_id=" + branchId
                + "&page=" + page + "&per_page=" + perPage);
    }

    public ApiResponse getHighValueItems(String location, double threshold) {
        return getWithAuth("/inventory/high-value?location="
                + (location != null ? location : "Main Showroom")
                + "&threshold=" + threshold);
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
        return getWithAuth("/inventory/compare-sessions?session_id_1=" + sessionId1
                + "&session_id_2=" + sessionId2);
    }

    public ApiResponse createInventorySnapshot(JSONObject payload) {
        return postWithAuth("/inventory/create-snapshot", payload);
    }

    public ApiResponse getInventorySettings(String location) {
        return getWithAuth("/inventory/settings/"
                + (location != null ? location : "default"));
    }

    public ApiResponse submitInventoryAuditReport(JSONObject payload) {
        return postWithAuth("/inventory/submit-audit", payload);
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

    // ==================== Encode / Tags ====================
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

    public ApiResponse getTestEpcs() {
        return getWithAuth("/test-epcs");
    }

    // ==================== Heartbeat / Settings ====================
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

    // ==================== الفروع ====================
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

    public ApiResponse getBranchItemsListWithLongTimeout(int branchId) {
        Log.d(TAG, "🌐 getBranchItemsListWithLongTimeout | branchId=" + branchId);
        String endpoint = "/branches/" + branchId + "/inventory/items";
        ApiResponse response = getWithAuthLongTimeout(endpoint);

        if (response != null) {
            Log.d(TAG, "📥 Code: " + response.code + ", isSuccess: " + response.isSuccess);
        }
        return response;
    }

    public ApiResponse getBranchInventoryStats(int branchId) {
        return getWithAuth("/branches/" + branchId + "/inventory/stats");
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

    // ==================== Gates ====================
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

    // ==================== التحويلات ====================
    public ApiResponse getTransfers(int branchId, String status) {
        String url = "/transfers?branch_id=" + branchId;
        if (status != null && !status.isEmpty()) {
            url += "&status=" + status;
        }
        return getWithAuth(url);
    }

    /**
     * ✅ جلب عدد التحويلات الواردة للفرع الحالي فقط
     */
    public ApiResponse getIncomingTransfersCount() {
        return getWithAuth("/transfers/incoming/count");
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

    // ==================== المحاسبة ====================
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

    public AccountingApiService accounting() {
        return accountingService;
    }

    public Gson gson() {
        return gson;
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
            String cleanType = reportType != null
                    ? reportType.replace("\"", "").replace("\\", "").replace("'", "").trim()
                    : "dashboard";

            StringBuilder endpoint = new StringBuilder("/reports-app?type=")
                    .append(URLEncoder.encode(cleanType, "UTF-8"));
            if (fromDate != null && !fromDate.isEmpty()) {
                endpoint.append("&from=").append(URLEncoder.encode(fromDate, "UTF-8"));
            }
            if (toDate != null && !toDate.isEmpty()) {
                endpoint.append("&to=").append(URLEncoder.encode(toDate, "UTF-8"));
            }
            if (branchId > 0) endpoint.append("&branch_id=").append(branchId);

            return getWithAuth(endpoint.toString());
        } catch (Exception e) {
            return new ApiResponse(-1, null, e.getMessage());
        }
    }

    // ==================== الذهب والعملات ====================
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
                        JSONObject pj = providersArray.getJSONObject(i);
                        ApiProvider provider = new ApiProvider();
                        provider.setId(pj.optInt("id", 0));
                        provider.setProviderName(pj.optString("name", ""));
                        provider.setProviderType(pj.optString("type", ""));
                        provider.setApiKey(pj.optString("api_key", ""));
                        provider.setBaseUrl(pj.optString("base_url", ""));
                        provider.setActive(pj.optBoolean("is_active", true));
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

    // ==================== السجلات ====================
    public ApiResponse logActivity(JSONObject logEntry) {
        return postWithAuth("/activity/log", logEntry);
    }

    /**
     * ✅ إعادة قراءة URL من SessionManager
     */
    public void refreshUrl() {
        try {
            String savedBaseUrl = sessionManager.getServerUrl();
            if (savedBaseUrl != null && !savedBaseUrl.isEmpty()) {
                synchronized (credentialsLock) {
                    this.baseUrl = normalizeBaseUrl(savedBaseUrl);
                }
                refreshCredentials();
                Log.d(TAG, "✅ URL refreshed: " + apiUrl);
            }
        } catch (Exception e) {
            Log.e(TAG, "refreshUrl error: " + e.getMessage(), e);
        }
    }

    public ApiResponse getUnifiedActivity(int branchId, int limit) {
        String endpoint = "/activity/unified?limit=" + limit;
        if (branchId > 0) endpoint += "&branch_id=" + branchId;
        return getWithAuth(endpoint);
    }

    public ApiResponse getUserActivityLogs(int userId, int limit) {
        return getWithAuth("/activity/logs?user_id=" + userId + "&limit=" + limit);
    }

    // ==================== الدوال التجاوبية ====================
    public interface ApiCallback {
        void onSuccess(ApiResponse response);
        void onFailure(String errorMessage);
    }

    public void get(final String endpoint, final ApiCallback callback) {
        networkExecutor.execute(() -> {
            final ApiResponse response = getWithAuth(endpoint);
            mainHandler.post(() -> {
                if (response != null && response.isSuccess) {
                    callback.onSuccess(response);
                } else {
                    String err = (response != null && response.error != null)
                            ? response.error : "Unknown error";
                    callback.onFailure(err);
                }
            });
        });
    }

    public void post(final String endpoint, final JSONObject payload, final ApiCallback callback) {
        networkExecutor.execute(() -> {
            final ApiResponse response = postWithAuth(endpoint, payload);
            mainHandler.post(() -> {
                if (response != null && response.isSuccess) {
                    callback.onSuccess(response);
                } else {
                    String err = (response != null && response.error != null)
                            ? response.error : "Unknown error";
                    callback.onFailure(err);
                }
            });
        });
    }

    // ==================== كلاس الاستجابة ====================
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
            // ✅ لا نُهيِّئ rawBody هنا — يُعيَّن صراحةً عند الحاجة عبر setBody()
            this.rawBody = null;
            this.message = (data != null) ? data.optString("message", "") : "";
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

    // ==================== مصنع خيوط مسمّى ====================
    private static class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger(1);

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }
}
