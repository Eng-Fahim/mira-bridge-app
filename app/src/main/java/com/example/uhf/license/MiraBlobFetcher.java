package com.example.uhf.license;

import android.content.Context;
import android.util.Log;

import com.example.uhf.BuildConfig;
import com.example.uhf.tools.CertificatePinningManager;
import com.example.uhf.tools.SessionManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

/**
 * MiraBlobFetcher — يجلب الـ blob من سيرفر العميل + Lookup من المركز
 *
 * v2.0 — نقل كامل من WordPress إلى Laravel
 *
 * Endpoints:
 *   GET  {serverUrl}/v1/license/client-blob   (يحتاج X-MIRA-License-Token)
 *   POST {centralUrl}/license/lookup          (Public)
 */
public final class MiraBlobFetcher {

    private static final String TAG = "MiraBlobFetcher";
    private static final int TIMEOUT_MS = 15_000;

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
    //  Build client-blob URL (Laravel — مع دعم الترحيل)
    // ═══════════════════════════════════════════════════════
    private static String buildClientBlobUrl(String serverUrl) {
        String base = serverUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        // ترحيل من WordPress
        base = base.replace("/wp-json", "");

        if (base.endsWith("/v1")) {
            return base + "/license/client-blob";
        }
        return base + "/v1/license/client-blob";
    }

    // ═══════════════════════════════════════════════════════
    //  Fetch client-blob from customer server
    // ═══════════════════════════════════════════════════════
    public static FetchResult fetch(Context ctx) {
        HttpURLConnection conn = null;
        try {
            SessionManager session = new SessionManager(ctx);
            String serverUrl = session.getServerUrl();
            String token = session.getActivationToken();

            if (serverUrl == null || serverUrl.trim().isEmpty()) {
                return FetchResult.error("no_server_url", "عنوان الخادم غير محدد");
            }
            if (token == null || token.isEmpty()) {
                return FetchResult.error("no_token", "لا يوجد ترخيص مفعّل");
            }

            String fullUrl = buildClientBlobUrl(serverUrl);
            Log.d(TAG, "GET " + fullUrl);

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
            conn.setRequestProperty("X-MIRA-License-Token", token);
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
                    while ((line = reader.readLine()) != null) sb.append(line);
                    body = sb.toString();
                }
            }

            if (code < 200 || code >= 300) {
                try {
                    JSONObject err = new JSONObject(body);
                    return FetchResult.error(
                            err.optString("code", "http_" + code),
                            err.optString("error",
                                    err.optString("message", "HTTP " + code)));
                } catch (Exception e) {
                    return FetchResult.error("http_" + code, "HTTP " + code);
                }
            }

            JSONObject json = new JSONObject(body);
            if (!json.optBoolean("success", false)) {
                return FetchResult.error(
                        json.optString("code", "failed"),
                        json.optString("error",
                                json.optString("message", "Unknown error")));
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
                    json.optInt("active_users", -1),
                    json.optInt("active_devices", -1)
            );
            return FetchResult.success(data);

        } catch (java.net.UnknownHostException e) {
            return FetchResult.error("dns", "تعذر الوصول للخادم — تحقق من الإنترنت");
        } catch (java.net.SocketTimeoutException e) {
            return FetchResult.error("timeout", "انتهت مهلة الاتصال");
        } catch (javax.net.ssl.SSLException e) {
            return FetchResult.error("ssl", "فشل SSL: " + e.getMessage());
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null || msg.isEmpty()) msg = e.getClass().getSimpleName();
            Log.e(TAG, "fetch error: " + msg, e);
            return FetchResult.error("network", msg);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Fetch + Verify + Store
    // ═══════════════════════════════════════════════════════
    public static boolean fetchVerifyAndStore(Context ctx) {
        FetchResult result = fetch(ctx);
        if (!result.success) {
            Log.w(TAG, "❌ Fetch failed: " + result.code + " — " + result.message);
            return false;
        }

        MiraBlobStorage.BlobData newBlob = result.blobData;
        MiraBlobStorage.BlobData currentBlob = MiraBlobStorage.load(ctx);

        MiraBlobVerifier.VerifyResult verify =
                MiraBlobVerifier.verifyAndValidate(newBlob, currentBlob);
        if (!verify.valid) {
            Log.e(TAG, "❌ Verify failed: " + verify.code + " — " + verify.message);
            return false;
        }

        if (!MiraBlobStorage.save(ctx, newBlob)) {
            Log.e(TAG, "❌ Storage failed");
            return false;
        }

        Log.d(TAG, "✅ Blob v" + newBlob.version + " verified and stored");
        return true;
    }

    // ═══════════════════════════════════════════════════════
    //  Lookup: يجلب server_url من المركز (Laravel)
    //  ⚡ الـ centralUrl = BuildConfig.MIRA_LICENSE_BASE_URL
    //     = "https://jewelry.ibreg.org/api/v1"
    //     endpoint = {centralUrl}/license/lookup
    //     result   = "https://jewelry.ibreg.org/api/v1/license/lookup"
    // ═══════════════════════════════════════════════════════
    public static LookupResult lookupServerUrl(Context context,
                                               String centralUrl,
                                               String licenseKey) {
        HttpURLConnection conn = null;
        try {
            String url = centralUrl.trim();
            if (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            // ترحيل: إذا كانت URL قديمة WordPress → نظّفها
            url = url.replace("/wp-json", "");

            // ⚡ Laravel: {centralUrl}/license/lookup
            //    لا نضيف /v1 لأن centralUrl ينتهي به أصلاً
            String endpoint;
            if (url.endsWith("/v1")) {
                endpoint = url + "/license/lookup";
            } else {
                endpoint = url + "/v1/license/lookup";
            }

            Log.d(TAG, "POST " + endpoint);

            JSONObject payload = new JSONObject();
            payload.put("license_key", licenseKey.toUpperCase().trim());

            URL u = new URL(endpoint);
            if (!"https".equalsIgnoreCase(u.getProtocol())) {
                return new LookupResult(false, null, false, null, null,
                        "يُشترط HTTPS");
            }

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
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(stream, "UTF-8"))) {
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
                    json.optString("error",
                            json.optString("message", "خطأ غير معروف"))
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

    private static String appVersion(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0";
        }
    }
}
