package com.example.uhf.license;

import android.content.Context;
import android.util.Log;

import com.example.uhf.tools.CertificatePinningManager;
import com.example.uhf.tools.SessionManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

/**
 * MiraBlobFetcher — يجلب الـ blob من خادم العميل ويخزّنه بعد التحقق
 *
 * v1.1 — إصلاح: serverUrl قد يحتوي على /wp-json بالفعل
 */
public final class MiraBlobFetcher {

    private static final String TAG = "MiraBlobFetcher";
    private static final int TIMEOUT_MS = 15_000;

    // المسار النسبي بعد /wp-json
    private static final String ENDPOINT_PATH = "/mira-license/v1/client-blob";

    private MiraBlobFetcher() {}

    // ═══════════════════════════════════════════════════════
    //  Result
    // ═══════════════════════════════════════════════════════
    public static class FetchResult {
        public final boolean success;
        public final MiraBlobStorage.BlobData blobData;
        public final String code;
        public final String message;

        private FetchResult(boolean success, MiraBlobStorage.BlobData data,
                            String code, String message) {
            this.success  = success;
            this.blobData = data;
            this.code     = code;
            this.message  = message;
        }

        public static FetchResult success(MiraBlobStorage.BlobData data) {
            return new FetchResult(true, data, "ok", "");
        }

        public static FetchResult error(String code, String message) {
            return new FetchResult(false, null, code, message);
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Build URL (يتعامل مع /wp-json المكرر)
    // ═══════════════════════════════════════════════════════
    private static String buildClientBlobUrl(String serverUrl) {
        String base = serverUrl.trim();

        // إزالة slash نهائي
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }

        // إذا كانت تنتهي بـ /wp-json → فقط نضيف المسار النسبي
        if (base.endsWith("/wp-json")) {
            return base + ENDPOINT_PATH;
        }

        // إذا كانت لا تحتوي /wp-json → نضيفها
        return base + "/wp-json" + ENDPOINT_PATH;
    }

    // ═══════════════════════════════════════════════════════
    //  Fetch from server
    // ═══════════════════════════════════════════════════════
    public static FetchResult fetch(Context ctx) {
        HttpURLConnection conn = null;
        try {
            SessionManager session = new SessionManager(ctx);
            String serverUrl = session.getServerUrl();

            if (serverUrl == null || serverUrl.trim().isEmpty()) {
                return FetchResult.error("no_server_url", "عنوان الخادم غير محدد");
            }

            String fullUrl = buildClientBlobUrl(serverUrl);
            Log.d(TAG, "Fetching: " + fullUrl);

            URL url = new URL(fullUrl);
            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                return FetchResult.error("insecure", "يُشترط HTTPS");
            }

            HttpsURLConnection https = (HttpsURLConnection) url.openConnection();
            try {
                CertificatePinningManager.applyPinning(https);
            } catch (Exception pinEx) {
                Log.w(TAG, "Certificate pinning skipped: " + pinEx.getMessage());
            }
            conn = https;

            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "MIRA-Android/" + appVersion(ctx));
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);

            int code = conn.getResponseCode();
            java.io.InputStream stream = (code >= 200 && code < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            String body = "";
            if (stream != null) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(stream, "UTF-8"))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    body = sb.toString();
                }
            }

            Log.d(TAG, "HTTP " + code + " | "
                    + (body.length() > 200 ? body.substring(0, 200) : body));

            if (code < 200 || code >= 300) {
                try {
                    JSONObject err = new JSONObject(body);
                    return FetchResult.error(
                            err.optString("code", "http_" + code),
                            err.optString("message", "HTTP " + code));
                } catch (Exception e) {
                    return FetchResult.error("http_" + code,
                            "HTTP " + code + ": "
                                    + (body.length() > 100 ? body.substring(0, 100) : body));
                }
            }

            JSONObject json = new JSONObject(body);
            if (!json.optBoolean("success", false)) {
                return FetchResult.error(
                        json.optString("code", "failed"),
                        json.optString("message", "Unknown error"));
            }

            JSONObject blobObj = json.optJSONObject("blob");
            if (blobObj == null) {
                return FetchResult.error("no_blob", "Blob مفقود في الاستجابة");
            }

            String blobJson = blobObj.toString();
            MiraBlobVerifier.BlobValues values = MiraBlobVerifier.extractBlobValues(blobJson);
            if (values == null) {
                return FetchResult.error("invalid_blob", "Blob JSON غير صالح");
            }

            MiraBlobStorage.BlobData data = new MiraBlobStorage.BlobData(
        blobJson,
        json.optString("signature", ""),
        json.optString("public_key", ""),
        json.optString("fingerprint", ""),
        json.optInt("version", values.version),
        json.optString("key_id", ""),
        json.optInt("license_id", values.licenseId),
        values.maxUsers,
        values.maxDevices,
        values.validUntil,
        System.currentTimeMillis(),
        json.optInt("active_users", -1),    // ← جديد
        json.optInt("active_devices", -1)   // ← جديد
);
            return FetchResult.success(data);

        } catch (java.net.UnknownHostException e) {
            Log.e(TAG, "DNS failed: " + e.getMessage());
            return FetchResult.error("dns", "تعذر الوصول للخادم — تحقق من الإنترنت");
        } catch (java.net.SocketTimeoutException e) {
            Log.e(TAG, "Timeout: " + e.getMessage());
            return FetchResult.error("timeout", "انتهت مهلة الاتصال");
        } catch (javax.net.ssl.SSLException e) {
            Log.e(TAG, "SSL failed: " + e.getMessage(), e);
            return FetchResult.error("ssl", "فشل SSL: " + e.getMessage());
        } catch (Exception e) {
    String msg = e.getMessage();
    if (msg == null || msg.isEmpty()) {
        msg = e.getClass().getSimpleName();
    }
    Log.e(TAG, "fetch error: " + msg, e);
    return FetchResult.error("network", msg);
} finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Fetch + Verify + Store (all-in-one)
    // ═══════════════════════════════════════════════════════
    public static boolean fetchVerifyAndStore(Context ctx) {
        Log.d(TAG, "🔄 fetchVerifyAndStore started");

        // [1] جلب
        FetchResult result = fetch(ctx);
        if (!result.success) {
            Log.w(TAG, "❌ Fetch failed: " + result.code + " — " + result.message);
            return false;
        }

        MiraBlobStorage.BlobData newBlob = result.blobData;
        MiraBlobStorage.BlobData currentBlob = MiraBlobStorage.load(ctx);

        // [2] تحقق شامل
        MiraBlobVerifier.VerifyResult verify =
                MiraBlobVerifier.verifyAndValidate(newBlob, currentBlob);
        if (!verify.valid) {
            Log.e(TAG, "❌ Verify failed: " + verify.code + " — " + verify.message);
            return false;
        }

        // [3] تخزين
        if (!MiraBlobStorage.save(ctx, newBlob)) {
            Log.e(TAG, "❌ Storage failed");
            return false;
        }

        Log.d(TAG, "✅ Blob v" + newBlob.version + " verified and stored (max_users="
                + newBlob.maxUsers + ")");
        return true;
    }

    private static String appVersion(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0";
        }
    }
    
    /**
 * Lookup: يجلب server_url من المركز بناءً على License Key
 *
 * @param centralUrl URL المركز (id.ibreg.org/wp-json)
 * @param licenseKey رقم الترخيص
 * @return LookupResult مع server_url
 */
public static LookupResult lookupServerUrl(Context context,
                                            String centralUrl,
                                            String licenseKey) {
    HttpURLConnection conn = null;
    try {
        String url = centralUrl.trim();
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);

        String endpoint = url + "/mira-license/v1/lookup";

        JSONObject payload = new JSONObject();
        payload.put("license_key", licenseKey.toUpperCase().trim());

        URL u = new URL(endpoint);
        HttpsURLConnection https = (HttpsURLConnection) u.openConnection();
        try {
            CertificatePinningManager.applyPinning(https);
        } catch (Exception ignored) {}

        conn = https;
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "MIRA-Android/" + appVersion(context));
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(15_000);
        conn.setDoOutput(true);

        try (java.io.OutputStream os = conn.getOutputStream()) {
            os.write(payload.toString().getBytes("UTF-8"));
        }

        int code = conn.getResponseCode();
        java.io.InputStream stream = (code >= 200 && code < 300)
                ? conn.getInputStream()
                : conn.getErrorStream();

        StringBuilder sb = new StringBuilder();
        if (stream != null) {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(stream, "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }
        }

        JSONObject json = new JSONObject(sb.toString());

        if (code >= 200 && code < 300 && json.optBoolean("success", false)) {
            return new LookupResult(
                    true,
                    json.optString("server_url", ""),
                    json.optBoolean("server_ready", false),
                    json.optString("customer_name", ""),
                    json.optString("license_type", ""),
                    null
            );
        }

        return new LookupResult(
                false, null, false, null, null,
                json.optString("message", "خطأ غير معروف")
        );

    } catch (Exception e) {
        Log.e(TAG, "lookupServerUrl error: " + e.getMessage(), e);
        return new LookupResult(false, null, false, null, null,
                e.getMessage() != null ? e.getMessage() : "فشل الاتصال");
    } finally {
        if (conn != null) conn.disconnect();
    }
}

public static class LookupResult {
    public final boolean success;
    public final String serverUrl;
    public final boolean serverReady;
    public final String customerName;
    public final String licenseType;
    public final String error;

    public LookupResult(boolean success, String serverUrl, boolean serverReady,
                        String customerName, String licenseType, String error) {
        this.success      = success;
        this.serverUrl    = serverUrl;
        this.serverReady  = serverReady;
        this.customerName = customerName;
        this.licenseType  = licenseType;
        this.error        = error;
    }
}
}
