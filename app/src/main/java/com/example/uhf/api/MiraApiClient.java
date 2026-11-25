package com.example.uhf.api;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.uhf.BuildConfig;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraGatesSettings;
import com.example.uhf.manager.MiraServerConfig;
import com.example.uhf.manager.PermissionManager;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import javax.net.ssl.HttpsURLConnection;

public class MiraApiClient {

    private static final String TAG = "MiraApiClient";

    // ==================== الإعدادات العامة ====================
    public static final String DEFAULT_SERVER_URL = "https://jewelry.ibreg.org/api";
    public static final String DEFAULT_BASE_URL = DEFAULT_SERVER_URL;

    private static final String API_PREFIX = "v1";
    private static final String ACCOUNTING_PREFIX = "mira/v2";

    private static final int DEFAULT_CONNECT_TIMEOUT = 10_000;
    private static final int DEFAULT_READ_TIMEOUT    = 10_000;
    private static final int LONG_CONNECT_TIMEOUT    = 15_000;
    private static final int LONG_READ_TIMEOUT       = 60_000;
    private static final int PING_CONNECT_TIMEOUT    = 5_000;
    private static final int PING_READ_TIMEOUT       = 5_000;

    private static final long TOKEN_CACHE_MS = 30_000;

    private volatile AccountingApiService accountingService;
    private final okhttp3.OkHttpClient accountingHttpClient;
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

    private volatile String cachedToken = null;
    private volatile long cachedTokenTimestamp = 0;

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

        this.gson = new GsonBuilder()
                .setLenient()
                .serializeNulls()
                .create();

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
        this.accountingHttpClient = buildAccountingHttpClient();
        this.accountingService = buildAccountingService(this.baseUrl);

        refreshCredentials();
    }

    private okhttp3.OkHttpClient buildAccountingHttpClient() {
        return new okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(chain -> {
                    okhttp3.Request original = chain.request();

                    String token = "";
                    String key = "";
                    String gate = "";
                    try {
                        token = getAuthToken();
                        key = getEffectiveApiKey();
                        gate = getEffectiveGateId();
                    } catch (Exception e) {
                        Log.w(TAG, "Auth read error: " + e.getClass().getSimpleName());
                    }

                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Net req: " + original.method() + " "
                                + original.url().encodedPath()
                                + " | key=" + (key.isEmpty() ? "empty" : "set")
                                + " tok=" + (token.isEmpty() ? "empty" : "set"));
                    }

                    okhttp3.Request.Builder builder = original.newBuilder()
                            .header("Accept", "application/json");

                    if (!key.isEmpty()) {
                        builder.header("X-MIRA-API-Key", key);
                    }
                    if (!token.isEmpty()) {
                        builder.header("Authorization", "Bearer " + token);
                        builder.header("X-MIRA-Auth-Token", token);
                    }
                    if (!gate.isEmpty()) {
                        builder.header("X-MIRA-Gate-ID", gate);
                    }
                    try {
                        String licenseToken = sessionManager.getActivationToken();
                        if (licenseToken != null && !licenseToken.isEmpty()) {
                            builder.header("X-MIRA-License-Token", licenseToken);
                        }
                    } catch (Exception ignored) {}

                    return chain.proceed(builder.build());
                })
                .build();
    }

    private AccountingApiService buildAccountingService(String base) {
        return new retrofit2.Retrofit.Builder()
                .baseUrl(normalizeBaseUrl(base) + ACCOUNTING_PREFIX + "/")
                .client(accountingHttpClient)
                .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
                .build()
                .create(AccountingApiService.class);
    }

    public void shutdown() {
        if (BuildConfig.DEBUG) Log.d(TAG, "shutdown() — closing executors");
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

    private void refreshCredentials() {
        synchronized (credentialsLock) {
            String cleanBaseUrl = normalizeBaseUrl(baseUrl);
            if (cleanBaseUrl.isEmpty()) {
                cleanBaseUrl = normalizeBaseUrl(serverConfig.getBaseUrl());
            }
            if (cleanBaseUrl.isEmpty()) {
                cleanBaseUrl = normalizeBaseUrl(DEFAULT_BASE_URL);
            }
            this.baseUrl = cleanBaseUrl;
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

            String token = sessionManager.getAuthToken();
            if (token != null && !token.isEmpty()) {
                this.cachedToken = token;
                this.cachedTokenTimestamp = System.currentTimeMillis();
            } else {
                this.cachedToken = null;
                this.cachedTokenTimestamp = 0;
            }

            if (!cleanBaseUrl.isEmpty()) {
                try {
                    this.accountingService = buildAccountingService(cleanBaseUrl);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to rebuild accountingService: " + e.getMessage());
                }
            }

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Credentials refreshed: URL=" + apiUrl
                        + ", Key=" + (apiKey.isEmpty() ? "EMPTY" : "EXISTS")
                        + ", Gate=" + (gateId.isEmpty() ? "EMPTY" : gateId)
                        + ", Token=" + (cachedToken == null ? "EMPTY" : "EXISTS"));
            }
        }
    }

    private void refreshCredentialsAsync() {
        if (!credentialsRefreshPending.compareAndSet(false, true)) {
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

    private String encodeParam(String value) {
        if (value == null) return "";
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private String sanitizePathSegment(String segment) {
        if (segment == null) return "";
        String cleaned = segment.trim().replaceAll("[^a-zA-Z0-9._-]", "");
        return encodeParam(cleaned);
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

    public interface PingCallback {
        void onResult(boolean isSuccess);
    }

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

    private String getEffectiveApiKey() {
        String key = apiKey;
        if (key == null || key.isEmpty()) {
            try {
                key = sessionManager.getApiKey();
                if (key != null && !key.isEmpty()) {
                    this.apiKey = key;
                    return key;
                }
            } catch (Exception ignored) {}
            try {
                key = serverConfig.getApiKey();
                if (key != null && !key.isEmpty()) {
                    this.apiKey = key;
                    return key;
                }
            } catch (Exception ignored) {}
        }
        return key != null ? key : "";
    }

    private String getEffectiveGateId() {
        String gate = gateId;
        if (gate == null || gate.isEmpty()) {
            try {
                gate = sessionManager.getGateId();
                if (gate != null && !gate.isEmpty()) {
                    this.gateId = gate;
                    return gate;
                }
            } catch (Exception ignored) {}
            try {
                gate = serverConfig.getGateId();
                if (gate != null && !gate.isEmpty()) {
                    this.gateId = gate;
                    return gate;
                }
            } catch (Exception ignored) {}
            return "";
        }
        return gate;
    }

    private String getAuthToken() {
        long now = System.currentTimeMillis();
        String cached = cachedToken;
        if (cached != null && !cached.isEmpty() && (now - cachedTokenTimestamp) < TOKEN_CACHE_MS) {
            return cached;
        }

        String token = sessionManager.getAuthToken();
        if (token == null || token.isEmpty()) {
            if (sessionManager.isLoggedIn()) {
                String fallback = serverConfig.getAuthToken();
                if (fallback != null && !fallback.isEmpty()) {
                    token = fallback;
                }
            }
        }

        String result = token != null ? token : "";
        cachedToken = result;
        cachedTokenTimestamp = now;
        return result;
    }

    public void invalidateTokenCache() {
        cachedToken = null;
        cachedTokenTimestamp = 0;
    }

    public boolean hasValidCredentials() {
        return !getEffectiveApiKey().isEmpty() && !getEffectiveGateId().isEmpty();
    }

    public boolean isAuthenticated() {
        return !getAuthToken().isEmpty();
    }

    public String getGateId() { return getEffectiveGateId(); }
    public String getApiUrl() { return apiUrl; }

    public String getAuthDiagnostics() {
        StringBuilder sb = new StringBuilder();
        String token = getAuthToken();
        String key = getEffectiveApiKey();
        String gate = getEffectiveGateId();

        sb.append("Token: ").append(!token.isEmpty()
                ? "✅ موجود (" + token.length() + " حرف)" : "❌ مفقود").append("\n");
        sb.append("API Key: ").append(!key.isEmpty()
                ? "✅ موجود" : "❌ مفقود").append("\n");
        sb.append("Gate ID: ").append(!gate.isEmpty()
                ? "✅ " + gate : "❌ مفقود").append("\n");
        sb.append("Base URL: ").append(apiUrl).append("\n");

        return sb.toString();
    }

    private HttpURLConnection openSecureConnection(String fullUrl) throws Exception {
        URL url = new URL(fullUrl);
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            String host = url.getHost();
            boolean isLocal = "localhost".equalsIgnoreCase(host)
                    || "127.0.0.1".equals(host)
                    || "10.0.2.2".equals(host);
            if (!(BuildConfig.DEBUG && isLocal)) {
                throw new SecurityException("Refused non-HTTPS URL: " + url.getProtocol() + "://" + host);
            }
            return (HttpURLConnection) url.openConnection();
        } else {
            HttpsURLConnection httpsConn = (HttpsURLConnection) url.openConnection();
            CertificatePinningManager.applyPinning(httpsConn);
            return httpsConn;
        }
    }

    private void applyStandardHeaders(HttpURLConnection conn, boolean requiresAuth, boolean isJsonBody) {
        if (isJsonBody) {
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        }
        conn.setRequestProperty("Accept", "application/json");

        String effectiveKey = getEffectiveApiKey();
        if (!effectiveKey.isEmpty()) {
            conn.setRequestProperty("X-MIRA-API-Key", effectiveKey);
        }

        String effectiveGate = getEffectiveGateId();
        if (!effectiveGate.isEmpty()) {
            conn.setRequestProperty("X-MIRA-Gate-ID", effectiveGate);
        }

        if (requiresAuth) {
            String token = getAuthToken();
            if (!token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setRequestProperty("X-MIRA-Auth-Token", token);
            }
        }

        try {
            String licenseToken = sessionManager.getActivationToken();
            if (licenseToken != null && !licenseToken.isEmpty()) {
                conn.setRequestProperty("X-MIRA-License-Token", licenseToken);
            }
        } catch (Exception ignored) {}
    }

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
            conn = openSecureConnection(fullUrl);

            conn.setRequestMethod(method);
            applyStandardHeaders(conn, requiresAuth, true);
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

        } catch (SecurityException se) {
            Log.e(TAG, "🚨 SECURITY: " + se.getMessage());
            return networkError("يُشترط اتصال HTTPS آمن");
        } catch (java.net.UnknownHostException | java.net.ConnectException | java.net.SocketTimeoutException e) {
            return networkError(getFriendlyErrorMessage(e));
        } catch (Exception e) {
            Log.e(TAG, "executeRequest error: " + e.getMessage(), e);
            return networkError(getFriendlyErrorMessage(e));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private ApiResponse networkError(String message) {
        ApiResponse response = new ApiResponse(-1, null, message);
        response.isNetworkError = true;
        return response;
    }

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

        private ApiResponse buildResponse(HttpURLConnection conn) throws Exception {
        int responseCode = conn.getResponseCode();

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "📥 RESPONSE CODE: " + responseCode + " | " + conn.getURL().getPath());
        }

        InputStream rawStream = (responseCode >= 200 && responseCode < 300)
                ? conn.getInputStream()
                : conn.getErrorStream();

        if (rawStream == null) {
            if (responseCode == 401) {
                handleUnauthorizedResponse(conn, null);
            }
            return new ApiResponse(responseCode, null, "Empty response");
        }

        InputStream is = rawStream;
        String encoding = conn.getContentEncoding();

        try {
            if (encoding != null && !encoding.isEmpty()) {
                String enc = encoding.toLowerCase(Locale.US).trim();
                if (enc.contains("br")) {
                    is = wrapBrotli(rawStream);
                } else if (enc.contains("gzip")) {
                    is = new GZIPInputStream(rawStream);
                } else if (enc.contains("deflate")) {
                    is = new InflaterInputStream(rawStream);
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "Decompression error: " + e.getMessage());
            is = rawStream;
        }

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

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Body length: " + responseBody.length() + " | Preview: "
                    + (responseBody.length() > 120 ? responseBody.substring(0, 120) + "..." : responseBody));
        }

        JSONObject json = null;
        String serverMessage = null;

        if (!responseBody.isEmpty()) {
            try {
                json = new JSONObject(responseBody);
                serverMessage = json.optString("message", null);
            } catch (Exception e) {
                if (BuildConfig.DEBUG) Log.w(TAG, "Non-JSON response");
            }
        }

        boolean isSuccess = responseCode >= 200 && responseCode < 300;
        String errorMessage = isSuccess ? null
                : (serverMessage != null && !serverMessage.isEmpty() ? serverMessage : responseBody);

        ApiResponse apiResponse = new ApiResponse(responseCode, json, errorMessage);
        apiResponse.setBody(responseBody);

        if (isSuccess && serverMessage != null) {
            apiResponse.message = serverMessage;
        }

        // 0) اعتراض الطرد الإجباري / انتهاء الجلسة (HTTP 401) على أي مسار غير تسجيل الدخول
        if (responseCode == 401) {
            handleUnauthorizedResponse(conn, serverMessage);
            return apiResponse;
        }

        // 1) التقاط بصمة الصلاحيات من الترويسة في أي استجابة ناجحة
        String headerPermVer = null;
        if (isSuccess) {
            try {
                headerPermVer = conn.getHeaderField("X-Permissions-Version");
            } catch (Exception ignored) {}
        }

        // 2) حفظ وثيقة الصلاحيات القادمة من السيرفر تلقائياً (مع تحديث الـ version وإعلام الشاشات)
        boolean savedCapabilitiesNow = false;
        if (isSuccess && json != null) {
            String path = "";
            try { path = conn.getURL().getPath(); } catch (Exception ignored) {}

            boolean isCapabilitiesSource =
                    path.endsWith("/auth/login")
                 || path.endsWith("/auth/me")
                 || path.endsWith("/auth/reconnect")
                 || path.endsWith("/mobile/capabilities")
                 || path.endsWith("/me/permissions")
                 || path.contains("/mobile/");

            JSONObject dataObj = json.optJSONObject("data");
            boolean hasCapabilitiesPayload =
                    json.has("legacy_permissions")
                 || (dataObj != null && (dataObj.has("modules") || dataObj.has("mobile_capabilities")));

            if (isCapabilitiesSource || hasCapabilitiesPayload) {
                try {
                    String payloadVer = (dataObj != null) ? dataObj.optString("version", "") : "";
                    if (payloadVer.isEmpty()) {
                        payloadVer = json.optString("permissions_version", "");
                    }
                    if (payloadVer.isEmpty() && headerPermVer != null) {
                        payloadVer = headerPermVer;
                    }

                    if (!payloadVer.isEmpty()) {
                        PermissionManager.getInstance(context).applyFromServer(json, payloadVer);
                    } else {
                        PermissionManager.getInstance(context).saveFromServer(json);
                    }
                    savedCapabilitiesNow = true;
                } catch (Exception e) {
                    Log.e(TAG, "Failed to save capabilities: " + e.getMessage());
                }
            }
        }

        // 3) إذا تغيّرت ترويسة X-Permissions-Version في أي طلب عادي → أطلق التحديث اللحظي فوراً
        if (isSuccess && !savedCapabilitiesNow && headerPermVer != null && !headerPermVer.isEmpty()) {
            try {
                if (PermissionManager.getInstance(context).needsRefresh(headerPermVer)) {
                    com.example.uhf.application.MiraApplication app =
                            com.example.uhf.application.MiraApplication.tryGetInstance();
                    if (app != null) {
                        app.getPermissionsSync().checkNow(null);
                    }
                }
            } catch (Exception ignored) {}
        }

        return apiResponse;
    }

    private void handleUnauthorizedResponse(HttpURLConnection conn, String serverMessage) {
        try {
            String path = conn.getURL().getPath();
            if (path != null && (path.endsWith("/auth/login") || path.endsWith("/login"))) {
                return;
            }
            com.example.uhf.manager.PermissionSyncManager.triggerForceLogout(context, serverMessage);
        } catch (Exception e) {
            Log.e(TAG, "handleUnauthorizedResponse error: " + e.getMessage());
        }
    }


    private InputStream wrapBrotli(InputStream rawStream) {
        try {
            Class<?> clazz = Class.forName("org.brotli.dec.BrotliInputStream");
            return (InputStream) clazz
                    .getConstructor(InputStream.class)
                    .newInstance(rawStream);
        } catch (Throwable t) {
            return rawStream;
        }
    }

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
            String fullUrl = normalizeBaseUrl(baseUrl) + ACCOUNTING_PREFIX + "/" + cleanEndpoint;
            conn = openSecureConnection(fullUrl);

            conn.setRequestMethod(method);
            applyStandardHeaders(conn, true, true);
            conn.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT);
            conn.setReadTimeout(DEFAULT_READ_TIMEOUT);

            if (payload != null) {
                conn.setDoOutput(true);
                byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(input, 0, input.length);
                }
            }

            return buildResponse(conn);

        } catch (SecurityException se) {
            Log.e(TAG, "SECURITY: accounting refused non-HTTPS");
            return networkError("يُشترط اتصال HTTPS آمن");
        } catch (Exception e) {
            Log.e(TAG, "accountingRequest error: " + e.getClass().getSimpleName());
            return networkError(getFriendlyErrorMessage(e));
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

            if (!newApiKey.isEmpty()) {
                sessionManager.saveApiKey(newApiKey);
                serverConfig.setApiKey(newApiKey);
            }

            String effectiveGateId = !newGateId.isEmpty() ? newGateId
                    : (deviceId != null && !deviceId.isEmpty() ? deviceId : "");
            if (!effectiveGateId.isEmpty()) {
                sessionManager.saveGateId(effectiveGateId);
                serverConfig.setGateId(effectiveGateId);

                try {
                    MiraGatesSettings.getInstance(context).setDeviceGateId(effectiveGateId);
                } catch (Exception ignored) {}
            }

            if (!authToken.isEmpty()) {
                if (expiresIn > 0) {
                    sessionManager.saveAuthToken(authToken, expiresIn);
                } else {
                    sessionManager.saveAuthToken(authToken);
                }
                serverConfig.saveSession(newApiKey, effectiveGateId, authToken);
            }

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

            refreshCredentials();

            try {
                ApiResponse capsResp = getWithAuth("/mobile/capabilities");
                if (capsResp != null && capsResp.isSuccess && capsResp.data != null) {
                    PermissionManager.getInstance(context).saveFromServer(capsResp.data);
                }
            } catch (Exception e) {
                Log.e(TAG, "Capabilities fetch error", e);
            }
        } else {
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
                    if (!newApiKey.isEmpty()) sessionManager.saveApiKey(newApiKey);
                    if (newGateId != null && !newGateId.isEmpty()) sessionManager.saveGateId(newGateId);
                    if (!authToken.isEmpty()) sessionManager.saveAuthToken(authToken);
                    refreshCredentialsAsync();
                }
            } else {
                invalidateTokenCache();
            }
            return response;
        } catch (Exception e) {
            return new ApiResponse(-1, null, getFriendlyErrorMessage(e));
        }
    }

    public ApiResponse validateToken(String token) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("token", token);
            payload.put("gate_id", getEffectiveGateId());
        } catch (Exception ignored) {}
        return postWithAuth("/auth/validate-token", payload);
    }

    public ApiResponse logout(String token) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("token", token);
            payload.put("gate_id", getEffectiveGateId());
        } catch (Exception ignored) {}

        ApiResponse response = postWithAuth("/auth/logout", payload);

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
                String endpoint = "/messages/unread?user_id=" + encodeParam(userId);
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
                mainHandler.post(() -> callback.onError(getFriendlyErrorMessage(e)));
            }
        });
    }

    public void getAllMessages(String userId, MessageCallback callback) {
        executorService.execute(() -> {
            try {
                String endpoint = "/messages?user_id=" + encodeParam(userId);
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
                mainHandler.post(() -> callback.onError(getFriendlyErrorMessage(e)));
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
                mainHandler.post(() -> callback.onError(getFriendlyErrorMessage(e)));
            }
        });
    }

    public void getUnreadCount(String userId, UnreadCountCallback callback) {
        executorService.execute(() -> {
            try {
                String endpoint = "/messages/unread-count?user_id=" + encodeParam(userId);
                ApiResponse response = getWithAuth(endpoint);

                if (response.isSuccess && response.data != null) {
                    int unreadCount = response.data.optInt("unread_count", 0);
                    mainHandler.post(() -> callback.onSuccess(unreadCount));
                } else {
                    String err = response.error != null ? response.error : "Failed";
                    mainHandler.post(() -> callback.onError(err));
                }
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(getFriendlyErrorMessage(e)));
            }
        });
    }

    public void getNotifications(String userId, NotificationCallback callback) {
        executorService.execute(() -> {
            try {
                String endpoint = "/notifications?user_id=" + encodeParam(userId);
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
                mainHandler.post(() -> callback.onError(getFriendlyErrorMessage(e)));
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
                mainHandler.post(() -> callback.onError(getFriendlyErrorMessage(e)));
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

    public ApiResponse getRoles() {
        return getWithAuth("/auth/roles");
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
    
        // ==================== مزامنة الصلاحيات الحية ====================
    public ApiResponse getPermissionsVersion() {
        return getWithAuth("/me/permissions/version");
    }

    public ApiResponse getMyPermissions() {
        return getWithAuth("/me/permissions");
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

            try {
                int userId = sessionManager.getUserId();
                String username = sessionManager.getUsername();
                if (userId > 0) payload.put("user_id", userId);
                if (username != null && !username.isEmpty()) {
                    payload.put("username", username);
                }
            } catch (Exception ignored) {}

            if (extraParams != null) {
                Iterator<String> keys = extraParams.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    payload.put(key, extraParams.get(key));
                }
            }
        } catch (Exception ignored) {}

        return postWithAuth("/authorize", payload);
    }

    public ApiResponse createAdjustingEntry(JSONObject payload) {
        return postAccounting("setup/adjusting-entry", payload);
    }

    public ApiResponse addPostApprovalBalance(String type, double amount, JSONObject extra) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("type", type);
            payload.put("amount", amount);
            if (extra != null) {
                if (extra.has("karat")) payload.put("karat", extra.optString("karat"));
                if (extra.has("description")) payload.put("description", extra.optString("description"));
                if (extra.has("account_id")) payload.put("account_id", extra.optInt("account_id"));
            }
            return postAccounting("setup/add-balance", payload);
        } catch (Exception e) {
            Log.e(TAG, "addPostApprovalBalance error: " + e.getMessage());
            return new ApiResponse(-1, null, getFriendlyErrorMessage(e));
        }
    }

    // ==================== Ping ====================
    public boolean pingApi() {
        if (apiUrl != null && !apiUrl.isEmpty() && tryPing(apiUrl + "ping")) {
            return true;
        }
        String base = normalizeBaseUrl(baseUrl);
        if (!base.isEmpty() && tryPing(base + "ping")) {
            return true;
        }
        return false;
    }

    private boolean tryPing(String urlString) {
        HttpURLConnection conn = null;
        try {
            conn = openSecureConnection(urlString);
            conn.setRequestMethod("GET");
            applyStandardHeaders(conn, false, false);
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
        String endpoint = "/dashboard/app";
        if (branchId > 0) endpoint += "?branch_id=" + branchId;
        return getWithAuth(endpoint);
    }

    public ApiResponse getDashboardFull() {
        return getDashboardFull(0);
    }

    public ApiResponse getDashboardCharts(int branchId) {
        String endpoint = "/dashboard/app-charts";
        if (branchId > 0) endpoint += "?branch_id=" + branchId;
        return getWithAuth(endpoint);
    }

    public ApiResponse getDashboardGoldPrices() {
        return getWithAuth("/dashboard/gold-prices");
    }

    /**
     * جلب ملخص الداشبورد وبيانات الخزينة/المخزون من المسارات الفعلية للسيرفر
     */
        public ApiResponse getDashboardSummarySmart(int branchId) {
        try {
            JSONObject combined = new JSONObject();

            // 1) المسار الرئيسي لتطبيق الداشبورد (/dashboard/app)
            ApiResponse appResp = getDashboardFull(branchId);
            if (appResp != null && appResp.isSuccess && appResp.data != null) {
                mergeJsonInto(combined, appResp.data);
            }

            // 2) المسار التكميلي (/dashboard/summary)
            if (!combined.has("vault") && !combined.has("inventory") && !combined.has("today_sales")) {
                String sumEp = branchId > 0 ? ("/dashboard/summary?branch_id=" + branchId) : "/dashboard/summary";
                ApiResponse sumResp = getWithAuth(sumEp);
                if (sumResp != null && sumResp.isSuccess && sumResp.data != null) {
                    mergeJsonInto(combined, sumResp.data);
                }
            }

            // 3) إحصائيات مخزون الفرع مباشرة من السيرفر (/branches/{branchId}/inventory/stats)
            JSONObject existingVault = combined.optJSONObject("vault");
            int pieces = existingVault != null
                    ? existingVault.optInt("total_pieces", existingVault.optInt("in_stock_count", 0))
                    : combined.optInt("total_pieces", combined.optInt("in_stock_count", 0));

            if (pieces <= 0 && branchId > 0) {
                ApiResponse statsResp = getBranchInventoryStats(branchId);
                if (statsResp != null && statsResp.isSuccess && statsResp.data != null) {
                    JSONObject sData = statsResp.data.optJSONObject("data") != null
                            ? statsResp.data.optJSONObject("data") : statsResp.data;
                    JSONObject statsObj = sData.optJSONObject("stats") != null
                            ? sData.optJSONObject("stats") : sData;

                    int stPieces = statsObj.optInt("total_pieces",
                            statsObj.optInt("in_stock", statsObj.optInt("total_items", statsObj.optInt("count", 0))));
                    double stWeight = statsObj.optDouble("total_weight",
                            statsObj.optDouble("gross_weight", statsObj.optDouble("weight", 0.0)));
                    double stPure = statsObj.optDouble("pure_24k_weight",
                            statsObj.optDouble("equivalent_24k", statsObj.optDouble("pure_weight", 0.0)));

                    if (stPieces > 0 || stWeight > 0.0 || stPure > 0.0) {
                        JSONObject vault = new JSONObject();
                        vault.put("total_pieces", stPieces);
                        vault.put("total_weight", stWeight);
                        vault.put("pure_24k_weight", stPure);
                        combined.put("vault", vault);
                    }
                }
            }

            return new ApiResponse(200, combined, null);
        } catch (Exception e) {
            Log.e(TAG, "getDashboardSummarySmart error: " + e.getMessage());
            return new ApiResponse(-1, null, getFriendlyErrorMessage(e));
        }
    }

    /**
     * جلب أسعار الذهب وسعر الصرف مباشرة من السيرفر بدون أي اشتقاقات محلية
     */
    public ApiResponse getGoldPricesSmart(int branchId) {
        try {
            String branchQuery = branchId > 0 ? ("?branch_id=" + branchId) : "";
            ApiResponse[] attempts = new ApiResponse[]{
                    getWithAuth("/gold-currency/settings" + (branchId > 0 ? ("?branch_id=" + branchId) : "")),
                    getWithAuth("/dashboard/gold-prices" + branchQuery),
                    getWithAuth("/gold-rates" + branchQuery),
                    getWithAuth("/gold-currency/live-prices" + branchQuery),
                    getWithAuth("/gold/price" + branchQuery)
            };

            for (ApiResponse r : attempts) {
                if (r != null && r.isSuccess && r.data != null) {
                    JSONObject parsed = normalizeGoldPricesPayload(r.data);
                    if (parsed != null && (parsed.optDouble("karat_24", 0.0) > 0.0
                            || parsed.optDouble("karat_22", 0.0) > 0.0
                            || parsed.optDouble("karat_21", 0.0) > 0.0
                            || parsed.optDouble("karat_18", 0.0) > 0.0
                            || parsed.optDouble("karat_14", 0.0) > 0.0
                            || parsed.optDouble("karat_24_usd", 0.0) > 0.0)) {
                        return new ApiResponse(200, parsed, null);
                    }
                }
            }
            return new ApiResponse(404, null, "Gold prices not available");
        } catch (Exception e) {
            Log.e(TAG, "getGoldPricesSmart error: " + e.getMessage());
            return new ApiResponse(-1, null, getFriendlyErrorMessage(e));
        }
    }

    private JSONObject normalizeGoldPricesPayload(JSONObject root) {
        try {
            JSONObject data = root.optJSONObject("data") != null ? root.optJSONObject("data") : root;
            JSONObject goldObj = data.optJSONObject("gold");
            JSONObject currObj = data.optJSONObject("currency");

            JSONObject prices = data.optJSONObject("gold_prices") != null
                    ? data.optJSONObject("gold_prices")
                    : (data.optJSONObject("prices") != null
                        ? data.optJSONObject("prices")
                        : (data.optJSONObject("rates") != null
                            ? data.optJSONObject("rates")
                            : (goldObj != null ? goldObj : data)));

            boolean useMarket = (currObj != null && currObj.optBoolean("use_market_rate", false))
                    || data.optBoolean("use_market_rate", false);

            String currency = "";
            if (currObj != null) {
                currency = currObj.optString("global_default_currency", currObj.optString("currency", ""));
            }
            if (currency.isEmpty()) {
                currency = prices.optString("currency",
                        data.optString("currency", data.optString("local_currency", "")));
            }
            currency = currency.trim().toUpperCase(Locale.US);

            double rate = firstPositiveDouble(prices, data,
                    "exchange_rate", "usd_rate", "dollar_rate", "rate", "selling_rate");
            if (rate <= 0.0 && currObj != null && !currency.isEmpty() && !"USD".equals(currency)) {
                String k1 = (useMarket ? "exchange_market_" : "exchange_official_") + currency;
                String k2 = (useMarket ? "exchange_official_" : "exchange_market_") + currency;
                rate = firstPositiveDouble(currObj, data, k1, k2);
            }

            double p24 = firstPositiveDouble(prices, data,
                    "karat_24", "price_24k", "gold_price_24k", "24k", "gold_24", "sell_24", "price_24", "k24");
            double p22 = firstPositiveDouble(prices, data,
                    "karat_22", "price_22k", "gold_price_22k", "22k", "gold_22", "sell_22", "price_22", "k22");
            double p21 = firstPositiveDouble(prices, data,
                    "karat_21", "price_21k", "gold_price_21k", "21k", "gold_21", "sell_21", "price_21", "k21");
            double p18 = firstPositiveDouble(prices, data,
                    "karat_18", "price_18k", "gold_price_18k", "18k", "gold_18", "sell_18", "price_18", "k18");
            double p14 = firstPositiveDouble(prices, data,
                    "karat_14", "price_14k", "gold_price_14k", "14k", "gold_14", "sell_14", "price_14", "k14");

            double p24Usd = firstPositiveDouble(prices, data,
                    "karat_24_usd", "price_24k_usd", "24k_usd", "usd_24k", "gold_24_usd", "gram_24k_usd");

            JSONArray arr = prices.optJSONArray("rates");
            if (arr == null) arr = prices.optJSONArray("gold_prices");
            if (arr == null) arr = data.optJSONArray("gold_rates");
            if (arr == null) arr = data.optJSONArray("data");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject row = arr.optJSONObject(i);
                    if (row == null) continue;
                    int k = row.optInt("karat", row.optInt("purity", 0));
                    double val = firstPositiveDouble(row, row,
                            "sell_price", "price", "price_local", "amount", "rate");
                    double valUsd = firstPositiveDouble(row, row,
                            "sell_price_usd", "price_usd", "usd_price");
                    if (k == 24) {
                        if (val > 0) p24 = val;
                        if (valUsd > 0) p24Usd = valUsd;
                    } else if (k == 22 && val > 0) {
                        p22 = val;
                    } else if (k == 21 && val > 0) {
                        p21 = val;
                    } else if (k == 18 && val > 0) {
                        p18 = val;
                    } else if (k == 14 && val > 0) {
                        p14 = val;
                    }
                }
            }

            String pricingVersion = root.optString("pricing_version",
                    data.optString("pricing_version", ""));

            JSONObject out = new JSONObject();
            out.put("karat_24", p24);
            out.put("karat_22", p22);
            out.put("karat_21", p21);
            out.put("karat_18", p18);
            out.put("karat_14", p14);
            out.put("karat_24_usd", p24Usd);
            out.put("exchange_rate", rate);
            out.put("currency", currency);
            if (!pricingVersion.isEmpty()) {
                out.put("pricing_version", pricingVersion);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }


    /**
     * جلب أسعار الذهب اليوم (24K / 21K / 18K) وسعر الصرف من المسارات المعتمدة
     */
    public ApiResponse getGoldPricesSmart(int branchId) {
        try {
            String branchQuery = branchId > 0 ? ("?branch_id=" + branchId) : "";
            ApiResponse[] attempts = new ApiResponse[]{
                    getWithAuth("/dashboard/gold-prices" + branchQuery),
                    getWithAuth("/gold-rates" + branchQuery),
                    getWithAuth("/gold-currency/live-prices" + branchQuery),
                    getWithAuth("/gold/price" + branchQuery)
            };

            for (ApiResponse r : attempts) {
                if (r != null && r.isSuccess && r.data != null) {
                    JSONObject parsed = normalizeGoldPricesPayload(r.data);
                    if (parsed != null && (parsed.optDouble("karat_24", 0.0) > 0.0
                            || parsed.optDouble("karat_21", 0.0) > 0.0
                            || parsed.optDouble("karat_24_usd", 0.0) > 0.0)) {
                        return new ApiResponse(200, parsed, null);
                    }
                }
            }
            return new ApiResponse(404, null, "Gold prices not available");
        } catch (Exception e) {
            Log.e(TAG, "getGoldPricesSmart error: " + e.getMessage());
            return new ApiResponse(-1, null, getFriendlyErrorMessage(e));
        }
    }

    private void mergeJsonInto(JSONObject target, JSONObject source) {
        if (target == null || source == null) return;
        try {
            JSONObject d = source.optJSONObject("data") != null ? source.optJSONObject("data") : source;
            Iterator<String> keys = d.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                Object val = d.opt(k);
                if (val != null && val != JSONObject.NULL) {
                    target.put(k, val);
                }
            }
        } catch (Exception ignored) {}
    }

    private JSONObject normalizeGoldPricesPayload(JSONObject root) {
        try {
            JSONObject data = root.optJSONObject("data") != null ? root.optJSONObject("data") : root;
            JSONObject prices = data.optJSONObject("gold_prices") != null
                    ? data.optJSONObject("gold_prices")
                    : (data.optJSONObject("prices") != null
                        ? data.optJSONObject("prices")
                        : (data.optJSONObject("rates") != null ? data.optJSONObject("rates") : data));

            double rate = firstPositiveDouble(prices, data,
                    "exchange_rate", "usd_rate", "dollar_rate", "rate", "selling_rate");
            String currency = prices.optString("currency",
                    data.optString("currency", data.optString("local_currency", "YER")));
            if (currency == null || currency.trim().isEmpty()) currency = "YER";

            double p24 = firstPositiveDouble(prices, data,
                    "karat_24", "price_24k", "24k", "gold_24", "sell_24", "price_24", "k24", "24");
            double p21 = firstPositiveDouble(prices, data,
                    "karat_21", "price_21k", "21k", "gold_21", "sell_21", "price_21", "k21", "21");
            double p18 = firstPositiveDouble(prices, data,
                    "karat_18", "price_18k", "18k", "gold_18", "sell_18", "price_18", "k18", "18");

            double p24Usd = firstPositiveDouble(prices, data,
                    "karat_24_usd", "price_24k_usd", "24k_usd", "usd_24k", "gold_24_usd", "gram_24k_usd");
            double p21Usd = firstPositiveDouble(prices, data,
                    "karat_21_usd", "price_21k_usd", "21k_usd", "usd_21k", "gram_21k_usd");
            double p18Usd = firstPositiveDouble(prices, data,
                    "karat_18_usd", "price_18k_usd", "18k_usd", "usd_18k", "gram_18k_usd");

            JSONArray arr = prices.optJSONArray("rates");
            if (arr == null) arr = prices.optJSONArray("gold_prices");
            if (arr == null) arr = data.optJSONArray("gold_rates");
            if (arr == null) arr = data.optJSONArray("data");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject row = arr.optJSONObject(i);
                    if (row == null) continue;
                    int k = row.optInt("karat", row.optInt("purity", 0));
                    double val = firstPositiveDouble(row, row,
                            "sell_price", "price", "price_local", "amount", "rate");
                    double valUsd = firstPositiveDouble(row, row,
                            "sell_price_usd", "price_usd", "usd_price");
                    if (k == 24) {
                        if (val > 0) p24 = val;
                        if (valUsd > 0) p24Usd = valUsd;
                    } else if (k == 21) {
                        if (val > 0) p21 = val;
                        if (valUsd > 0) p21Usd = valUsd;
                    } else if (k == 18) {
                        if (val > 0) p18 = val;
                        if (valUsd > 0) p18Usd = valUsd;
                    }
                }
            }

            if (p24 <= 0 && p24Usd > 0 && rate > 0) p24 = p24Usd * rate;
            if (p21 <= 0 && p21Usd > 0 && rate > 0) p21 = p21Usd * rate;
            if (p18 <= 0 && p18Usd > 0 && rate > 0) p18 = p18Usd * rate;

            if (p24 <= 0 && p21 > 0) p24 = p21 * (24.0 / 21.0);
            if (p24 <= 0 && p18 > 0) p24 = p18 * (24.0 / 18.0);
            if (p21 <= 0 && p24 > 0) p21 = p24 * (21.0 / 24.0);
            if (p18 <= 0 && p24 > 0) p18 = p24 * (18.0 / 24.0);

            if (p24Usd <= 0 && p24 > 0 && rate > 0) p24Usd = p24 / rate;

            JSONObject out = new JSONObject();
            out.put("karat_24", p24);
            out.put("karat_21", p21);
            out.put("karat_18", p18);
            out.put("karat_24_usd", p24Usd);
            out.put("exchange_rate", rate);
            out.put("currency", currency.toUpperCase(Locale.US));
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private double firstPositiveDouble(JSONObject primary, JSONObject secondary, String... keys) {
        for (String k : keys) {
            if (primary != null) {
                double v = primary.optDouble(k, 0.0);
                if (v > 0.0) return v;
            }
            if (secondary != null) {
                double v = secondary.optDouble(k, 0.0);
                if (v > 0.0) return v;
            }
        }
        return 0.0;
    }

    // ==================== نقطة البيع والمبيعات ====================
    public ApiResponse checkoutWithLongTimeout(JSONObject payload) {
        try {
            if (payload == null) payload = new JSONObject();
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
        try {
            if (payload == null) payload = new JSONObject();
            if (!payload.has("gate_id")) {
                payload.put("gate_id", getEffectiveGateId());
            }
        } catch (Exception e) {
            Log.e(TAG, "checkout payload prep error: " + e.getMessage());
        }
        return postWithAuth("/pos/checkout", payload);
    }

    public ApiResponse checkRefundEligibility(JSONObject payload) {
        try {
            return postWithAuth("/pos/check-refund", payload);
        } catch (Exception e) {
            Log.e(TAG, "checkRefundEligibility error: " + e.getMessage());
            return new ApiResponse(-1, null, getFriendlyErrorMessage(e));
        }
    }

    public ApiResponse processRefund(JSONObject payload) {
        try {
            if (payload == null) payload = new JSONObject();
            if (!payload.has("gate_id")) {
                payload.put("gate_id", getEffectiveGateId());
            }
            return postWithAuth("/pos/refund", payload);
        } catch (Exception e) {
            Log.e(TAG, "processRefund error: " + e.getMessage());
            ApiResponse response = new ApiResponse(-1, null, getFriendlyErrorMessage(e));
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
            ApiResponse response = new ApiResponse(-1, null, getFriendlyErrorMessage(e));
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
            return postWithAuth("/pos/search-item", payload);
        } catch (Exception e) {
            Log.e(TAG, "searchPosItem error: " + e.getMessage(), e);
            ApiResponse response = new ApiResponse(-1, null, getFriendlyErrorMessage(e));
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
        return getWithAuth("/pos/sales?page=" + page
                + "&limit=" + pageSize + "&per_page=" + pageSize
                + "&search=" + encodeParam(query));
    }

    public ApiResponse getSalesHistoryByPaymentMethod(String paymentMethod, int page, int pageSize) {
        return getWithAuth("/pos/sales?page=" + page
                + "&limit=" + pageSize + "&per_page=" + pageSize
                + "&payment_method=" + encodeParam(paymentMethod));
    }

    public ApiResponse getPaymentMethods() {
        return getWithAuth("/payment-methods");
    }

    public ApiResponse getSalesStats() {
        return getWithAuth("/pos/sales/stats");
    }

    public ApiResponse getSalesHistoryByDateRange(String fromDate, String toDate, int page, int pageSize) {
        StringBuilder endpoint = new StringBuilder("/pos/sales?page=")
                .append(page).append("&limit=").append(pageSize).append("&per_page=").append(pageSize);
        if (fromDate != null && !fromDate.isEmpty()) endpoint.append("&from=").append(encodeParam(fromDate));
        if (toDate != null && !toDate.isEmpty()) endpoint.append("&to=").append(encodeParam(toDate));
        return getWithAuth(endpoint.toString());
    }

    public ApiResponse getSalesHistoryAdvanced(String search, String paymentMethod,
                                               String fromDate, String toDate,
                                               int page, int pageSize, int branchId) {
        try {
            StringBuilder endpoint = new StringBuilder("/pos/sales?");
            endpoint.append("page=").append(page);
            endpoint.append("&limit=").append(pageSize);

            if (search != null && !search.trim().isEmpty()) {
                endpoint.append("&search=").append(encodeParam(search.trim()));
            }
            if (paymentMethod != null && !paymentMethod.equalsIgnoreCase("ALL")) {
                endpoint.append("&payment_method=").append(encodeParam(paymentMethod.toLowerCase(Locale.ROOT)));
            }
            if (fromDate != null && !fromDate.isEmpty()) endpoint.append("&from=").append(encodeParam(fromDate));
            if (toDate != null && !toDate.isEmpty()) endpoint.append("&to=").append(encodeParam(toDate));
            if (branchId > 0) endpoint.append("&branch_id=").append(branchId);

            return getWithAuth(endpoint.toString());
        } catch (Exception e) {
            ApiResponse response = new ApiResponse(-1, null, getFriendlyErrorMessage(e));
            response.isNetworkError = true;
            return response;
        }
    }
    /**
     * جلب التفاصيل الكاملة للفاتورة الواحدة من /pos/sales/{id}
     */
    public ApiResponse getSaleDetail(long saleId) {
        if (saleId <= 0) {
            return new ApiResponse(400, null, "Invalid sale ID");
        }
        return getWithAuth("/pos/sales/" + saleId);
    }

    /**
     * جلب تفاصيل فاتورة محددة برقمها (يجلب التفاصيل الكاملة من /pos/sales/{id})
     */
    public ApiResponse getSaleDetailsByNumber(String saleNumber) {
        try {
            if (saleNumber == null || saleNumber.trim().isEmpty()) {
                return new ApiResponse(400, null, "Empty sale number");
            }
            String cleanNo = saleNumber.trim();
            ApiResponse resp = getSalesHistoryAdvanced(cleanNo, "ALL", null, null, 1, 10, 0);
            if (resp != null && resp.isSuccess && resp.data != null) {
                JSONArray salesArr = extractArrayFromPayload(resp.data, "sales");
                if (salesArr != null && salesArr.length() > 0) {
                    JSONObject bestMatch = salesArr.optJSONObject(0);
                    for (int i = 0; i < salesArr.length(); i++) {
                        JSONObject item = salesArr.optJSONObject(i);
                        if (item == null) continue;
                        String sNo = item.optString("sale_number", item.optString("invoice_number", "")).trim();
                        if (sNo.equalsIgnoreCase(cleanNo)) {
                            bestMatch = item;
                            break;
                        }
                    }
                    if (bestMatch != null) {
                        long dbId = bestMatch.optLong("id", bestMatch.optLong("sale_id", 0L));
                        if (dbId > 0) {
                            ApiResponse fullResp = getSaleDetail(dbId);
                            if (fullResp != null && fullResp.isSuccess && fullResp.data != null) {
                                return fullResp;
                            }
                        }
                        JSONObject wrapper = new JSONObject();
                        wrapper.put("success", true);
                        wrapper.put("sale", bestMatch);
                        return new ApiResponse(200, wrapper, null);
                    }
                }
            }
            return resp != null ? resp : new ApiResponse(404, null, "Sale not found");
        } catch (Exception e) {
            Log.e(TAG, "getSaleDetailsByNumber error: " + e.getMessage());
            return new ApiResponse(-1, null, e.getMessage());
        }
    }


    public ApiResponse getItemDetail(String code) {
        return getWithAuth("/item/" + sanitizePathSegment(code));
    }

    // ==================== رفع الصور ====================
    public ApiResponse uploadItemImage(int itemId, File imageFile, boolean isPrimary, String altText) {
        if (imageFile == null || !imageFile.exists() || !imageFile.isFile()) {
            return new ApiResponse(400, null, "Image file not found");
        }
        HttpURLConnection conn = null;
        try {
            String fullUrl = apiUrl + "items/" + itemId + "/images";
            conn = openSecureConnection(fullUrl);

            conn.setRequestMethod("POST");
            applyStandardHeaders(conn, true, false);
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(30_000);
            conn.setDoOutput(true);

            String boundary = "----MiraBoundary" + System.currentTimeMillis();
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            String safeFileName = imageFile.getName().replaceAll("[\"\\r\\n]", "_");

            try (OutputStream os = conn.getOutputStream()) {
                StringBuilder sb = new StringBuilder();
                sb.append("--").append(boundary).append("\r\n");
                sb.append("Content-Disposition: form-data; name=\"image\"; filename=\"")
                        .append(safeFileName).append("\"\r\n");
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
        } catch (SecurityException se) {
            return networkError("يُشترط اتصال HTTPS آمن");
        } catch (Exception e) {
            Log.e(TAG, "uploadItemImage error: " + e.getClass().getSimpleName());
            return new ApiResponse(-1, null, getFriendlyErrorMessage(e));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ==================== المخزون ====================
    public ApiResponse getLastSnapshot(String location) {
        String loc = (location != null && !location.isEmpty()) ? location : "Main Showroom";
        return getWithAuth("/inventory/last-snapshot?location=" + encodeParam(loc));
    }

    public ApiResponse getInventoryHistory(String location, int limit) {
        return getWithAuth("/inventory/history/" + sanitizePathSegment(location) + "?limit=" + limit);
    }

    public ApiResponse getInventoryReports(int branchId, int page, int perPage) {
        return getWithAuth("/inventory/sessions?branch_id=" + branchId
                + "&page=" + page + "&per_page=" + perPage);
    }

    public ApiResponse getHighValueItems(String location, double threshold) {
        String loc = (location != null && !location.isEmpty()) ? location : "Main Showroom";
        return getWithAuth("/inventory/high-value?location=" + encodeParam(loc)
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
        String loc = (location != null && !location.isEmpty()) ? location : "default";
        return getWithAuth("/inventory/settings/" + sanitizePathSegment(loc));
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
            return new ApiResponse(-1, null, getFriendlyErrorMessage(e));
        }
    }

    public ApiResponse updateInventoryItemStatus(JSONObject payload) {
        try {
            if (payload == null) payload = new JSONObject();
            if (!payload.has("branch_id") || payload.optInt("branch_id", 0) <= 0) {
                MiraBranchesManager bm = MiraBranchesManager.getInstance(context);
                payload.put("branch_id", bm.getActiveBranchId());
            }
            if (!payload.has("gate_id")) {
                payload.put("gate_id", getEffectiveGateId());
            }
            return postWithAuth("/inventory/item-status", payload);
        } catch (Exception e) {
            Log.e(TAG, "updateInventoryItemStatus error: " + e.getMessage());
            ApiResponse r = new ApiResponse(-1, null, getFriendlyErrorMessage(e));
            r.isNetworkError = true;
            return r;
        }
    }

    public ApiResponse updateInventoryItemStatus(int sessionId, String epc,
                                                 String requestedStatus,
                                                 String damageType, String notes) {
        JSONObject payload = new JSONObject();
        try {
            if (sessionId > 0) payload.put("session_id", sessionId);
            payload.put("epc", epc != null ? epc : "");
            payload.put("status", requestedStatus != null ? requestedStatus : "");
            if (damageType != null && !damageType.isEmpty()) {
                payload.put("damage_type", damageType);
            }
            if (notes != null && !notes.isEmpty()) {
                payload.put("notes", notes);
            }
        } catch (Exception ignored) {}
        return updateInventoryItemStatus(payload);
    }

    public ApiResponse getInventoryExpectedCatalog(int branchId, String location) {
        try {
            int effectiveBranchId = branchId > 0
                    ? branchId
                    : MiraBranchesManager.getInstance(context).getActiveBranchId();
            StringBuilder endpoint = new StringBuilder("/inventory/expected-catalog?branch_id=")
                    .append(effectiveBranchId);
            if (location != null && !location.trim().isEmpty()) {
                endpoint.append("&location=").append(encodeParam(location.trim()));
            }
            return getWithAuthLongTimeout(endpoint.toString());
        } catch (Exception e) {
            Log.e(TAG, "getInventoryExpectedCatalog error: " + e.getMessage());
            ApiResponse r = new ApiResponse(-1, null, getFriendlyErrorMessage(e));
            r.isNetworkError = true;
            return r;
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
        return postWithAuth("/heartbeat", payload);
    }

    public ApiResponse getSettings() {
        return getWithAuth("/settings/" + sanitizePathSegment(getEffectiveGateId()));
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
            endpoint += "?category=" + encodeParam(category);
        }
        return getWithAuth(endpoint);
    }

    public ApiResponse getBranchItemsList(int branchId) {
        return getWithAuth("/branches/" + branchId + "/inventory/items");
    }

    public ApiResponse getBranchItemsListWithLongTimeout(int branchId) {
        return getWithAuthLongTimeout("/branches/" + branchId + "/inventory/items");
    }

    public ApiResponse getBranchInventoryStats(int branchId) {
        return getWithAuth("/branches/" + branchId + "/inventory/stats");
    }

    public ApiResponse getMyBranchInventoryItems(String category) {
        String endpoint = "/my-branch/inventory/items";
        if (category != null && !category.isEmpty()) {
            endpoint += "?category=" + encodeParam(category);
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
            url += "&status=" + encodeParam(status);
        }
        return getWithAuth(url);
    }

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
            url += "?branch_id=" + encodeParam(branchId);
        }
        return getAccounting(url);
    }

    public ApiResponse getJournalEntryDetail(String entryId) {
        return getAccounting("/accounting/journal-entries/" + sanitizePathSegment(entryId));
    }

    public ApiResponse createJournalEntry(JSONObject payload) {
        return postAccounting("/accounting/journal-entries/create", payload);
    }

    public ApiResponse getChartOfAccounts() {
        return getAccounting("/accounting/chart-of-accounts");
    }

    public ApiResponse createAccount(JSONObject payload) {
        return postAccounting("/accounting/chart-of-accounts/create", payload);
    }

    public ApiResponse getTrialBalance(String branchId) {
        String url = "/accounting/trial-balance";
        if (branchId != null && !branchId.isEmpty()) {
            url += "?branch_id=" + encodeParam(branchId);
        }
        return getAccounting(url);
    }

    public ApiResponse getAccountingSummary(String branchId) {
        String url = "/accounting/summary";
        if (branchId != null && !branchId.isEmpty()) {
            url += "?branch_id=" + encodeParam(branchId);
        }
        return getAccounting(url);
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
            url += "?branch_id=" + encodeParam(branchId);
            hasParam = true;
        }
        if (voucherType != null && !voucherType.isEmpty()) {
            url += (hasParam ? "&" : "?") + "voucher_type=" + encodeParam(voucherType);
        }
        return getAccounting(url);
    }

    public ApiResponse createVoucher(JSONObject payload) {
        return postAccounting("/accounting/vouchers/create", payload);
    }

    public ApiResponse getFiscalYears() {
        return getAccounting("/accounting/fiscal-years");
    }

    public ApiResponse createFiscalYear(JSONObject payload) {
        return postAccounting("/accounting/fiscal-years/create", payload);
    }

    public ApiResponse closeFiscalYear(int year) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("year", year);
        } catch (Exception ignored) {}
        return postAccounting("/accounting/fiscal-years/close", payload);
    }

    public ApiResponse getOpeningBalances() {
        return getAccounting("/accounting/opening-balances");
    }

    public ApiResponse setOpeningBalance(int accountId, double cashBalance, double goldBalance) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("account_id", accountId);
            payload.put("opening_balance", cashBalance);
            payload.put("opening_gold", goldBalance);
            return postAccounting("/accounting/opening-balances/set", payload);
        } catch (Exception e) {
            return new ApiResponse(-1, null, getFriendlyErrorMessage(e));
        }
    }

    public ApiResponse getAccountingSetupStatus() {
        return getAccounting("/setup/status");
    }

    public ApiResponse getAccountingAuditLog() {
        return getAccounting("/accounting/audit-log?limit=50");
    }

    public ApiResponse getBalanceSheet(String branchId, String asOfDate) {
        String url = "/accounting/balance-sheet";
        boolean hasParam = false;
        if (branchId != null && !branchId.isEmpty()) {
            url += "?branch_id=" + encodeParam(branchId);
            hasParam = true;
        }
        if (asOfDate != null && !asOfDate.isEmpty()) {
            url += (hasParam ? "&" : "?") + "as_of=" + encodeParam(asOfDate);
        }
        return getAccounting(url);
    }

    public ApiResponse getIncomeStatement(String branchId, String fromDate, String toDate) {
        String url = "/accounting/income-statement?from=" + encodeParam(fromDate) + "&to=" + encodeParam(toDate);
        if (branchId != null && !branchId.isEmpty()) {
            url += "&branch_id=" + encodeParam(branchId);
        }
        return getAccounting(url);
    }

    public ApiResponse getGoldMovementReport(String branchId, String fromDate, String toDate) {
        String url = "/accounting/gold-movement?from=" + encodeParam(fromDate) + "&to=" + encodeParam(toDate);
        if (branchId != null && !branchId.isEmpty()) {
            url += "&branch_id=" + encodeParam(branchId);
        }
        return getAccounting(url);
    }

    public ApiResponse getReportData(String reportType, String fromDate, String toDate, int branchId) {
        return getReportData(reportType, fromDate, toDate, branchId, null, null, null);
    }

    public ApiResponse getReportData(String reportType, String fromDate, String toDate,
                                     int branchId, String search, String filter1, String filter2) {
        try {
            String cleanType = reportType != null
                    ? reportType.replace("\"", "").replace("\\", "").replace("'", "").trim()
                    : "dashboard";

            StringBuilder endpoint = new StringBuilder("/reports-app?type=")
                    .append(encodeParam(cleanType));

            if (fromDate != null && !fromDate.isEmpty()) {
                endpoint.append("&from=").append(encodeParam(fromDate));
            }
            if (toDate != null && !toDate.isEmpty()) {
                endpoint.append("&to=").append(encodeParam(toDate));
            }
            if (branchId > 0) endpoint.append("&branch_id=").append(branchId);

            if (search != null && !search.trim().isEmpty()) {
                endpoint.append("&search=").append(encodeParam(search));
            }
            if (filter1 != null && !filter1.trim().isEmpty()) {
                endpoint.append("&filter1=").append(encodeParam(filter1));
            }
            if (filter2 != null && !filter2.trim().isEmpty()) {
                endpoint.append("&filter2=").append(encodeParam(filter2));
            }

            return getWithAuth(endpoint.toString());
        } catch (Exception e) {
            return new ApiResponse(-1, null, getFriendlyErrorMessage(e));
        }
    }

    public ApiResponse getCashFlowStatement(String date, int branchId) {
        String url = "/accounting/cash-flow?date=" + encodeParam(date);
        if (branchId > 0) url += "&branch_id=" + branchId;
        return getAccounting(url);
    }

    public ApiResponse reverseJournalEntry(String entryId) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("reason", "عكس من تطبيق الأندرويد");
        } catch (Exception ignored) {}
        return postAccounting("/accounting/journal-entries/" + sanitizePathSegment(entryId) + "/reverse", payload);
    }

    public ApiResponse getAgingReport(String asOfDate, String type) {
        return getAccounting("/accounting/aging-report?as_of=" + encodeParam(asOfDate)
                + "&type=" + encodeParam(type));
    }

    public ApiResponse processGoldConversion(JSONObject payload) {
        return postAccounting("/accounting/gold-conversion", payload);
    }

    public ApiResponse getProfitAnalysis(String branchId, String fromDate, String toDate) {
        String url = "/accounting/profit-analysis?from=" + encodeParam(fromDate) + "&to=" + encodeParam(toDate);
        if (branchId != null && !branchId.isEmpty()) {
            url += "&branch_id=" + encodeParam(branchId);
        }
        return getAccounting(url);
    }

    public ApiResponse previewInventoryRevaluation(int branchId) {
        return getAccounting("/accounting/inventory-revaluation/preview?branch_id=" + branchId);
    }

    public ApiResponse postInventoryRevaluation(JSONObject payload) {
        return postAccounting("/accounting/inventory-revaluation/post", payload);
    }

    public ApiResponse savePurchaseInvoice(JSONObject payload) {
        try {
            if (payload == null) payload = new JSONObject();
            if (!payload.has("branch_id")) {
                MiraBranchesManager bm = MiraBranchesManager.getInstance(context);
                payload.put("branch_id", bm.getActiveBranchId());
            }
            return postWithAuth("/purchases/create", payload);
        } catch (Exception e) {
            ApiResponse response = new ApiResponse(-1, null, getFriendlyErrorMessage(e));
            response.isNetworkError = true;
            return response;
        }
    }

    public ApiResponse getAuditLogs(String status, String action, int limit) {
        String url = "/audit-logs?limit=" + limit;
        if (status != null && !status.isEmpty()) url += "&status=" + encodeParam(status);
        if (action != null && !action.isEmpty()) url += "&action=" + encodeParam(action);
        return getAccounting(url);
    }

    // ==================== قفل الفترات المالية ====================
    public ApiResponse togglePeriodLock(JSONObject payload) {
        return postAccounting("/accounting/lock-period", payload);
    }

    public ApiResponse getLockedPeriods() {
        return getAccounting("/accounting/locked-periods");
    }

    // ==================== إدارة الشيكات (Cheques) ====================
    public ApiResponse getCheques(String type, String status) {
        String url = "/cheques";
        boolean hasParam = false;
        if (type != null && !type.isEmpty()) {
            url += "?type=" + encodeParam(type);
            hasParam = true;
        }
        if (status != null && !status.isEmpty()) {
            url += (hasParam ? "&" : "?") + "status=" + encodeParam(status);
        }
        return getAccounting(url);
    }

    public ApiResponse createCheque(JSONObject payload) {
        return postAccounting("/cheques", payload);
    }

    public ApiResponse depositCheque(int chequeId, JSONObject payload) {
        return postAccounting("/cheques/" + chequeId + "/deposit", payload);
    }

    public ApiResponse returnCheque(int chequeId, JSONObject payload) {
        return postAccounting("/cheques/" + chequeId + "/return", payload);
    }

    // ==================== التسوية البنكية (Bank Reconciliation) ====================
    public ApiResponse getReconciliationSummary(int bankAccountId, String date) {
        return getAccounting("/reconciliation/summary?bank_account_id=" + bankAccountId
                + "&date=" + encodeParam(date));
    }

    public ApiResponse saveReconciliation(JSONObject payload) {
        return postAccounting("/reconciliation/save", payload);
    }

    // ==================== الأصول الثابتة (Fixed Assets) ====================
    public ApiResponse getFixedAssets() {
        return getAccounting("/fixed-assets");
    }

    public ApiResponse createFixedAsset(JSONObject payload) {
        return postAccounting("/fixed-assets", payload);
    }

    public ApiResponse runDepreciation(JSONObject payload) {
        return postAccounting("/fixed-assets/depreciate", payload);
    }

    // ==================== الذهب والعملات ====================
    public List<ApiProvider> getActiveProviders() {
        List<ApiProvider> providers = new ArrayList<>();
        try {
            if (getAuthToken().isEmpty()) {
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
        } catch (Exception ignored) {}
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
                    String key = providerData.optString("api_key", "");
                    return fetchGoldPrice(providerType, key);
                }
            }
            return new ApiResponse(-1, null, "Provider not found");
        } catch (Exception e) {
            return new ApiResponse(-1, null, getFriendlyErrorMessage(e));
        }
    }

    public ApiResponse getGoldRatesAndSettings() {
        return getWithAuth("/gold-rates");
    }

    public ApiResponse getGoldCurrencySettings() {
        String v = "";
        try {
            if (this.context != null) {
                android.content.SharedPreferences sp =
                        this.context.getSharedPreferences("MIRA_GOLD_SETTINGS", Context.MODE_PRIVATE);
                v = sp.getString("pricing_version", "");
            }
        } catch (Exception ignored) {}

        String path = "/gold-currency/settings?t=" + System.currentTimeMillis();
        if (v != null && !v.isEmpty()) {
            path += "&v=" + android.net.Uri.encode(v);
        }
        return getWithAuth(path);
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

    // ==================== السجلات والعمليات الموحدة ====================
    public ApiResponse logActivity(JSONObject logEntry) {
        return postWithAuth("/activity/log", logEntry);
    }

    public void refreshUrl() {
        try {
            String savedBaseUrl = sessionManager.getServerUrl();
            if (savedBaseUrl != null && !savedBaseUrl.isEmpty()) {
                synchronized (credentialsLock) {
                    this.baseUrl = normalizeBaseUrl(savedBaseUrl);
                }
                refreshCredentials();
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

    public interface ApiCallback {
        void onSuccess(ApiResponse response);
        void onFailure(String errorMessage);
    }

    public ApiResponse getUnifiedOperations(int branchId, int page, int limit, String search, String filterType) {
        return getUnifiedOperations(branchId, 0, "", true, page, limit, search, filterType);
    }

    public ApiResponse getUnifiedOperations(
            int branchId,
            int userId,
            String username,
            boolean isManager,
            int page,
            int limit,
            String search,
            String filterType
    ) {
        try {
            String normalizedFilter = (filterType == null || filterType.trim().isEmpty())
                    ? "all" : filterType.trim().toLowerCase(Locale.ROOT);
            String cleanSearch = (search != null) ? search.trim() : "";
            String cleanUsername = (username != null) ? username.trim() : "";

            List<JSONObject> mergedList = new ArrayList<>();
            Set<String> seenSaleNumbers = new HashSet<>();
            int totalSalesOnServer = 0;
            boolean hasMoreSales = false;

            boolean includeSales = normalizedFilter.equals("all")
                    || normalizedFilter.equals("sale")
                    || normalizedFilter.equals("refund");

            boolean includeOtherActivities = normalizedFilter.equals("all")
                    || normalizedFilter.equals("scrap")
                    || normalizedFilter.equals("transfer")
                    || normalizedFilter.equals("inventory")
                    || normalizedFilter.equals("rfid");

            if (includeSales) {
                int fetchLimit = isManager ? limit : Math.max(limit * 3, 60);
                ApiResponse salesResp = getSalesHistoryAdvanced(
                        cleanSearch, "ALL", null, null, page, fetchLimit, branchId
                );

                if (salesResp != null && salesResp.isSuccess && salesResp.data != null) {
                    JSONArray salesArr = extractArrayFromPayload(salesResp.data, "sales");
                    JSONObject dataObj = salesResp.data.optJSONObject("data");
                    if (dataObj != null) {
                        totalSalesOnServer = dataObj.optInt("total", 0);
                        int lastP = dataObj.optInt("last_page", 1);
                        int currP = dataObj.optInt("current_page", page);
                        hasMoreSales = currP < lastP;
                    }
                    if (totalSalesOnServer == 0) {
                        totalSalesOnServer = salesResp.data.optInt("total", 0);
                    }

                    if (salesArr != null) {
                        for (int i = 0; i < salesArr.length(); i++) {
                            JSONObject sale = salesArr.optJSONObject(i);
                            if (sale == null) continue;

                            if (branchId > 0) {
                                int sBranch = sale.optInt("branch_id", 0);
                                if (sBranch > 0 && sBranch != branchId) continue;
                            }

                            if (!isManager && !matchesCurrentUser(sale, userId, cleanUsername)) {
                                continue;
                            }

                            JSONObject normalizedSale = normalizeSaleAsUnifiedOperation(sale);
                            String opType = normalizedSale.optString("type", "sale");

                            if (normalizedFilter.equals("refund") && !opType.equals("refund")) continue;
                            if (normalizedFilter.equals("sale") && !opType.equals("sale")) continue;

                            String saleNo = normalizedSale.optString("sale_number", "");
                            if (!saleNo.isEmpty()) {
                                seenSaleNumbers.add(saleNo.toUpperCase(Locale.US));
                            }
                            mergedList.add(normalizedSale);
                        }
                    }
                }
            }

            if (includeOtherActivities && page == 1) {
                int actFetchLimit = isManager ? Math.max(limit, 25) : 60;
                ApiResponse actResp = getUnifiedActivity(branchId, actFetchLimit);
                if (actResp != null && actResp.isSuccess && actResp.data != null) {
                    JSONArray actArr = extractArrayFromPayload(actResp.data, "activities");
                    if (actArr != null) {
                        for (int i = 0; i < actArr.length(); i++) {
                            JSONObject act = actArr.optJSONObject(i);
                            if (act == null) continue;

                            if (!isManager && !matchesCurrentUser(act, userId, cleanUsername)) {
                                continue;
                            }

                            String saleRef = act.optString("sale_number",
                                    act.optString("invoice_number", "")).trim();
                            if (!saleRef.isEmpty() && seenSaleNumbers.contains(saleRef.toUpperCase(Locale.US))) {
                                continue;
                            }

                            String rawType = (act.optString("type", "") + " " +
                                    act.optString("action", "") + " " +
                                    act.optString("event", "")).toLowerCase(Locale.ROOT);

                            boolean isGenericSystem = rawType.trim().isEmpty()
                                    || rawType.contains("system")
                                    || rawType.contains("journal");
                            boolean hasMeaningfulAmount = act.optDouble("amount", 0.0) > 0.0
                                    || act.optDouble("weight", 0.0) > 0.0;

                            if (isGenericSystem && !hasMeaningfulAmount && saleRef.isEmpty()) {
                                continue;
                            }

                            String mappedType = mapActivityType(rawType);
                            if (!normalizedFilter.equals("all") && !mappedType.equals(normalizedFilter)) {
                                continue;
                            }

                            if (!cleanSearch.isEmpty() && !act.toString().toLowerCase(Locale.ROOT)
                                    .contains(cleanSearch.toLowerCase(Locale.ROOT))) {
                                continue;
                            }

                            act.put("type", mappedType);
                            if (!act.has("timestamp") || act.optString("timestamp").isEmpty()) {
                                act.put("timestamp", act.optString("created_at", ""));
                            }
                            mergedList.add(act);
                        }
                    }
                }
            }

            Collections.sort(mergedList, (a, b) -> {
                String tA = a.optString("created_at", a.optString("timestamp", ""));
                String tB = b.optString("created_at", b.optString("timestamp", ""));
                return tB.compareTo(tA);
            });

            JSONArray finalArray = new JSONArray();
            for (JSONObject item : mergedList) {
                finalArray.put(item);
            }

            int effectiveTotal = isManager
                    ? Math.max(totalSalesOnServer, finalArray.length())
                    : finalArray.length();

            JSONObject wrapper = new JSONObject();
            wrapper.put("success", true);
            wrapper.put("activities", finalArray);
            wrapper.put("total", effectiveTotal);
            wrapper.put("has_more", hasMoreSales);

            return new ApiResponse(200, wrapper, null);
        } catch (Exception e) {
            Log.e(TAG, "getUnifiedOperations error: " + e.getMessage(), e);
            return new ApiResponse(-1, null, getFriendlyErrorMessage(e));
        }
    }

    private JSONArray extractArrayFromPayload(JSONObject root, String primaryKey) {
        if (root == null) return null;
        if (primaryKey != null) {
            JSONArray direct = root.optJSONArray(primaryKey);
            if (direct != null) return direct;
        }
        JSONArray dataArr = root.optJSONArray("data");
        if (dataArr != null) return dataArr;

        JSONObject nestedData = root.optJSONObject("data");
        if (nestedData != null) {
            if (primaryKey != null) {
                JSONArray nestedPrimary = nestedData.optJSONArray(primaryKey);
                if (nestedPrimary != null) return nestedPrimary;
            }
            JSONArray nestedItems = nestedData.optJSONArray("data");
            if (nestedItems != null) return nestedItems;
        }
        return null;
    }

    private JSONObject normalizeSaleAsUnifiedOperation(JSONObject sale) {
        try {
            JSONObject out = new JSONObject(sale.toString());
            String status = sale.optString("refund_status", sale.optString("status", "")).toLowerCase(Locale.ROOT);
            boolean isRefund = status.contains("refund");
            out.put("type", isRefund ? "refund" : "sale");

            String saleNo = sale.optString("sale_number", sale.optString("invoice_number", "")).trim();
            if (!saleNo.isEmpty()) {
                out.put("sale_number", saleNo);
                if (!out.has("title_ar") || out.optString("title_ar").isEmpty()) {
                    out.put("title_ar", (isRefund ? "مرتجع فاتورة #" : "فاتورة مبيعات #") + saleNo);
                }
                if (!out.has("title_en") || out.optString("title_en").isEmpty()) {
                    out.put("title_en", (isRefund ? "Refund Invoice #" : "Sales Invoice #") + saleNo);
                }
            }

            if (!out.has("timestamp") || out.optString("timestamp").isEmpty()) {
                out.put("timestamp", sale.optString("created_at", ""));
            }

            // حساب إجمالي وزن الفاتورة من حقل الوزن أو بجمع أوزان الأصناف
            double saleWeight = sale.optDouble("total_weight", sale.optDouble("weight", 0.0));
            if (saleWeight <= 0.0) {
                JSONArray items = sale.optJSONArray("items");
                if (items == null) items = sale.optJSONArray("sold_items");
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject it = items.optJSONObject(i);
                        if (it != null) {
                            saleWeight += it.optDouble("weight",
                                    it.optDouble("gross_weight_grams", it.optDouble("gross_weight", 0.0)));
                        }
                    }
                }
            }
            out.put("total_weight", saleWeight);
            return out;
        } catch (Exception e) {
            return sale;
        }
    }

    private String mapActivityType(String rawType) {
        if (rawType == null) return "system";
        String lower = rawType.toLowerCase(Locale.ROOT);
        if (lower.contains("refund") || lower.contains("return")) return "refund";
        if (lower.contains("sale") || lower.contains("pos") || lower.contains("invoice")) return "sale";
        if (lower.contains("scrap")) return "scrap";
        if (lower.contains("transfer") || lower.contains("move")) return "transfer";
        if (lower.contains("inventory") || lower.contains("audit") || lower.contains("stock")) return "inventory";
        if (lower.contains("rfid") || lower.contains("encode") || lower.contains("gate") || lower.contains("tag")) return "rfid";
        return "system";
    }

    private boolean matchesCurrentUser(JSONObject record, int currentUserId, String currentUsername) {
        if (record == null) return false;

        int[] candidateIds = new int[]{
                record.optInt("user_id", 0),
                record.optInt("cashier_id", 0),
                record.optInt("employee_id", 0),
                record.optInt("seller_id", 0),
                record.optInt("created_by", 0),
                record.optInt("operator_id", 0)
        };

        JSONObject nestedUser = record.optJSONObject("user");
        int nestedUserId = (nestedUser != null) ? nestedUser.optInt("id", 0) : 0;

        boolean hasAnyNumericId = nestedUserId > 0;
        for (int id : candidateIds) {
            if (id > 0) {
                hasAnyNumericId = true;
                break;
            }
        }

        if (currentUserId > 0 && hasAnyNumericId) {
            if (nestedUserId == currentUserId) return true;
            for (int id : candidateIds) {
                if (id == currentUserId) return true;
            }
            return false;
        }

        if (currentUsername != null && !currentUsername.isEmpty()) {
            String target = currentUsername.trim().toLowerCase(Locale.ROOT);
            String[] candidateNames = new String[]{
                    record.optString("cashier_name", ""),
                    record.optString("employee_name", ""),
                    record.optString("seller_name", ""),
                    record.optString("username", ""),
                    record.optString("user", ""),
                    record.optString("created_by_name", ""),
                    (nestedUser != null) ? nestedUser.optString("name", nestedUser.optString("username", "")) : ""
            };
            for (String name : candidateNames) {
                if (name != null && !name.trim().isEmpty()) {
                    if (name.trim().toLowerCase(Locale.ROOT).equals(target)) return true;
                }
            }
        }

        return false;
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

    // ==================== حساب قيمة الذهب الكسر والاسترجاع ====================
    public ApiResponse calculateScrap(double weightGrams, String karat) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("weight_grams", weightGrams);
            payload.put("karat", karat);
            if (!payload.has("gate_id")) {
                payload.put("gate_id", getEffectiveGateId());
            }
            return postWithAuth("/scrap/calculate", payload);
        } catch (Exception e) {
            Log.e(TAG, "calculateScrap error: " + e.getMessage());
            ApiResponse r = new ApiResponse(-1, null, getFriendlyErrorMessage(e));
            r.isNetworkError = true;
            return r;
        }
    }

    public ApiResponse findSaleForRefund(String query) {
        try {
            return getWithAuth("/refunds/find-sale?q=" + encodeParam(query));
        } catch (Exception e) {
            ApiResponse r = new ApiResponse(-1, null, getFriendlyErrorMessage(e));
            r.isNetworkError = true;
            return r;
        }
    }

    public ApiResponse findSaleForRefundByItem(String code) {
        try {
            return getWithAuth("/refunds/find-by-item?code=" + encodeParam(code));
        } catch (Exception e) {
            ApiResponse r = new ApiResponse(-1, null, getFriendlyErrorMessage(e));
            r.isNetworkError = true;
            return r;
        }
    }

    public ApiResponse processRefundV2(JSONObject payload) {
        try {
            if (payload == null) payload = new JSONObject();
            if (!payload.has("gate_id")) {
                payload.put("gate_id", getEffectiveGateId());
            }
            return postWithAuth("/refunds", payload);
        } catch (Exception e) {
            ApiResponse r = new ApiResponse(-1, null, getFriendlyErrorMessage(e));
            r.isNetworkError = true;
            return r;
        }
    }

    public ApiResponse prepareCheckout(List<Integer> itemIds) {
        try {
            JSONObject payload = new JSONObject();
            JSONArray arr = new JSONArray();
            if (itemIds != null) {
                for (Integer id : itemIds) {
                    if (id != null && id > 0) arr.put(id);
                }
            }
            payload.put("item_ids", arr);
            return postWithAuth("/pos/prepare-checkout", payload);
        } catch (Exception e) {
            ApiResponse r = new ApiResponse(-1, null, getFriendlyErrorMessage(e));
            r.isNetworkError = true;
            return r;
        }
    }

    // ==================== شراء كسر الذهب والتسوية النقدية ====================
    public ApiResponse saveScrapPurchase(JSONObject payload) {
        try {
            if (payload == null) payload = new JSONObject();
            if (!payload.has("gate_id")) {
                payload.put("gate_id", getEffectiveGateId());
            }
            return postWithAuth("/scrap/purchase", payload);
        } catch (Exception e) {
            ApiResponse response = new ApiResponse(-1, null, getFriendlyErrorMessage(e));
            response.isNetworkError = true;
            return response;
        }
    }

    public ApiResponse getScrapSummary(int branchId) {
        return getWithAuth("/scrap/summary?branch_id=" + branchId);
    }

    public ApiResponse getAvailableScrapPurchases(int branchId) {
        return getWithAuth("/scrap/available-purchases?branch_id=" + branchId);
    }

    public ApiResponse createScrapBatch(JSONObject payload) {
        return postWithAuth("/scrap/batches", payload);
    }

    public ApiResponse getScrapBatches(int branchId) {
        return getWithAuth("/scrap/batches?branch_id=" + branchId);
    }

    public ApiResponse sendScrapBatch(int batchId) {
        return postWithAuth("/scrap/batches/" + batchId + "/send", new JSONObject());
    }

    public ApiResponse receiveScrapBatch(int batchId, JSONObject payload) {
        return postWithAuth("/scrap/batches/" + batchId + "/receive", payload);
    }

    public ApiResponse getScrapProfitReport(int branchId, String fromDate, String toDate) {
        return getWithAuth("/scrap/profit-report?branch_id=" + branchId
                + "&from=" + encodeParam(fromDate) + "&to=" + encodeParam(toDate));
    }

    public ApiResponse getCashReconciliationSummary(int branchId, String date) {
        return getWithAuth("/cash-reconciliation/daily?branch_id=" + branchId
                + "&date=" + encodeParam(date));
    }

    public ApiResponse closeCashier(JSONObject payload) {
        return postWithAuth("/cash-reconciliation/close", payload);
    }

    public ApiResponse getScrapPurchaseReceipt(int purchaseId) {
        return getWithAuth("/scrap/purchase/" + purchaseId + "/receipt");
    }

    // ==================== السندات والعملاء والموردون ====================
    public ApiResponse getVouchers(String type, int branchId) {
        String url = "/vouchers?branch_id=" + branchId;
        if (type != null && !type.isEmpty()) url += "&type=" + encodeParam(type);
        return getWithAuth(url);
    }

    public ApiResponse createVoucherDirect(JSONObject payload) {
        return postWithAuth("/vouchers", payload);
    }

    public ApiResponse getVoucherAccounts() {
        return getWithAuth("/vouchers/accounts");
    }

    public ApiResponse getVoucherParties() {
        return getWithAuth("/vouchers/parties");
    }

    public ApiResponse getVoucherDetail(int voucherId) {
        return getWithAuth("/vouchers/" + voucherId);
    }

    public ApiResponse getPartiesList(String type, String search) {
        StringBuilder url = new StringBuilder("/parties/list?");
        if (type != null && !type.isEmpty()) url.append("type=").append(encodeParam(type)).append("&");
        if (search != null && !search.isEmpty()) url.append("search=").append(encodeParam(search));
        return getWithAuth(url.toString());
    }

    public ApiResponse createParty(JSONObject payload) {
        return postWithAuth("/parties/create", payload);
    }

    public ApiResponse getPartyStatement(int partyId) {
        return getWithAuth("/parties/" + partyId + "/statement");
    }

    public ApiResponse getScrapAgingReport(int branchId) {
        return getWithAuth("/scrap/aging?branch_id=" + branchId);
    }

    public ApiResponse getScrapAgingSummary(int branchId) {
        return getWithAuth("/scrap/aging/summary?branch_id=" + branchId);
    }

    // ==================== معالجة الأخطاء ====================
    private String getFriendlyErrorMessage(Throwable throwable) {
        if (throwable == null) {
            return isArabic() ? "حدث خطأ غير متوقع، يرجى المحاولة لاحقاً." : "An unexpected error occurred. Please try again.";
        }

        if (!isNetworkAvailable()) {
            return isArabic()
                    ? "لا يوجد اتصال بالإنترنت. يرجى التحقق من الشبكة والمحاولة مرة أخرى."
                    : "No internet connection. Please check your network and try again.";
        }

        if (throwable instanceof java.net.UnknownHostException) {
            return isArabic()
                    ? "تعذر الوصول إلى الخادم. يرجى التأكد من اتصالك بالإنترنت."
                    : "Unable to reach the server. Please check your connection.";
        } else if (throwable instanceof java.net.SocketTimeoutException) {
            return isArabic()
                    ? "انتهت مهلة الاتصال بالخادم. يرجى المحاولة مرة أخرى."
                    : "Connection timed out. Please try again.";
        } else if (throwable instanceof java.net.ConnectException) {
            return isArabic()
                    ? "فشل الاتصال بالخادم. يرجى المحاولة لاحقاً."
                    : "Failed to connect to the server. Please try again later.";
        }

        return isArabic()
                ? "حدث خطأ في الاتصال. يرجى المحاولة لاحقاً."
                : "A network error occurred. Please try again later.";
    }

    private boolean isArabic() {
        return Locale.getDefault().getLanguage().equals("ar");
    }

    private boolean isNetworkAvailable() {
        try {
            if (context == null) return false;
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network network = cm.getActiveNetwork();
                if (network == null) return false;
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            } else {
                android.net.NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
                return activeNetwork != null && activeNetwork.isConnected();
            }
        } catch (Exception ignored) {}
        return true;
    }
    
    /**
 * جلب تنبيهات الفرع (المعلّقة والمحلولة)
 */
        public ApiResponse getBranchAlerts(int branchId, boolean includeResolved) {
        String endpoint = "/alerts?branch_id=" + branchId + "&include_resolved=" + (includeResolved ? 1 : 0);
        return getWithAuth(endpoint);
    }

    public ApiResponse resolveAlert(int branchId, String alertId, String note, boolean resolveAll) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("branch_id", branchId);
            payload.put("alert_id", alertId != null ? alertId : "");
            payload.put("resolution_note", note != null ? note : "");
            payload.put("resolve_all", resolveAll);
            return postWithAuth("/alerts/resolve", payload);
        } catch (Exception e) {
            return new ApiResponse(-1, null, getFriendlyErrorMessage(e));
        }
    }


    private ApiResponse executeAlertsHttp(String method, String endpoint, JSONObject body) {
        java.net.HttpURLConnection conn = null;
        try {
            String resolvedBase = "";
            String resolvedToken = "";
            android.content.Context foundCtx = null;

            for (java.lang.reflect.Field f : this.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(this);
                if (val instanceof String) {
                    String s = ((String) val).trim();
                    String fname = f.getName().toLowerCase(java.util.Locale.US);
                    if (s.startsWith("http") && (fname.contains("url") || fname.contains("base") || resolvedBase.isEmpty())) {
                        resolvedBase = s;
                    } else if (!s.isEmpty() && fname.contains("token")) {
                        resolvedToken = s;
                    }
                } else if (val instanceof android.content.Context) {
                    foundCtx = (android.content.Context) val;
                }
            }

            if (resolvedToken.isEmpty() && foundCtx != null) {
                try {
                    com.example.uhf.tools.SecureSessionManager sm =
                            com.example.uhf.tools.SecureSessionManager.getInstance(foundCtx);
                    for (java.lang.reflect.Method m : sm.getClass().getMethods()) {
                        if ((m.getName().equals("getToken") || m.getName().equals("getAuthToken"))
                                && m.getParameterCount() == 0) {
                            Object t = m.invoke(sm);
                            if (t != null && !t.toString().isEmpty()) {
                                resolvedToken = t.toString();
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            if (resolvedBase.isEmpty()) {
                return new ApiResponse(0, null, "Base URL not configured");
            }

            String cleanBase = resolvedBase.endsWith("/")
                    ? resolvedBase.substring(0, resolvedBase.length() - 1)
                    : resolvedBase;
            if (!cleanBase.endsWith("/api/v1")) {
                cleanBase = cleanBase + "/api/v1";
            }
            String fullUrl = cleanBase + "/" + (endpoint.startsWith("/") ? endpoint.substring(1) : endpoint);

            java.net.URL url = new java.net.URL(fullUrl);
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            if (!resolvedToken.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + resolvedToken);
            }

            if ("POST".equals(method) && body != null) {
                conn.setDoOutput(true);
                byte[] out = body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                try (java.io.OutputStream os = conn.getOutputStream()) {
                    os.write(out);
                }
            }

            int statusCode = conn.getResponseCode();
            java.io.InputStream is = (statusCode >= 200 && statusCode < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            StringBuilder sb = new StringBuilder();
            if (is != null) {
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line);
                    }
                }
            }

            String raw = sb.toString().trim();
            JSONObject json = raw.startsWith("{") ? new JSONObject(raw) : new JSONObject();
            String msg = json.optString("message", (statusCode >= 200 && statusCode < 300) ? "OK" : "HTTP " + statusCode);
            return new ApiResponse(statusCode, json, msg);
        } catch (Exception e) {
            return new ApiResponse(0, null, e.getMessage() != null ? e.getMessage() : "Network error");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }



    // ==================== نماذج الاستجابة والخيوط ====================
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
            this.statusCode = code;
            this.isSuccess = code >= 200 && code < 300;
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
