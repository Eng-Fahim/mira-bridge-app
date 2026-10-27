package com.example.uhf.license;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import com.example.uhf.tools.CertificatePinningManager;
import com.example.uhf.tools.SessionManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

/**
 * LicenseManager — التواصل مع MIRA License Center
 *
 * Endpoints:
 *   POST {serverUrl}/mira-license/v1/activate
 *   POST {serverUrl}/mira-license/v1/heartbeat
 *   POST {serverUrl}/mira-license/v1/deactivate
 *   GET  {serverUrl}/mira-license/v1/status
 */
public class LicenseManager {

    private static final String TAG = "LicenseManager";
    private static final String ENDPOINT_PREFIX = "mira-license/v1";

    private static final int CONNECT_TIMEOUT = 15_000;
    private static final int READ_TIMEOUT    = 15_000;

    public interface Callback {
        void onSuccess(JSONObject response);
        void onError(String code, String message, int httpCode);
    }

    // ═══════════════════════════════════════════════════════
    //  Activate
    // ═══════════════════════════════════════════════════════

    public static void activate(final Context context,
                                final String serverUrl,
                                final String licenseKey,
                                final Callback callback) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                // ═══ 1. بناء الطلب ═══
                String baseUrl = normalizeUrl(serverUrl);
                String fullUrl = baseUrl + "/" + ENDPOINT_PREFIX + "/activate";

                JSONObject device = new JSONObject();
                device.put("device_name", (Build.MANUFACTURER + " " + Build.MODEL).trim());
                device.put("device_model", Build.MODEL != null ? Build.MODEL : "");
                device.put("device_os", "Android " + Build.VERSION.RELEASE
                        + " (API " + Build.VERSION.SDK_INT + ")");

                JSONObject fingerprint = new JSONObject();
                String androidId = Settings.Secure.getString(
                        context.getContentResolver(),
                        Settings.Secure.ANDROID_ID);
                fingerprint.put("android_id", androidId != null ? androidId : "");

                String serial = "";
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        serial = Build.getSerial();
                    } else {
                        serial = Build.SERIAL;
                    }
                } catch (SecurityException ignored) {}
                fingerprint.put("serial", serial != null ? serial : "");
                fingerprint.put("model", Build.MODEL != null ? Build.MODEL : "");

                device.put("fingerprint", fingerprint);
                device.put("app_version", getAppVersion(context));

                JSONObject payload = new JSONObject();
                payload.put("license_key", licenseKey.toUpperCase().trim());
                payload.put("device", device);

                // ═══ 2. الاتصال ═══
                URL url = new URL(fullUrl);
                if (!"https".equalsIgnoreCase(url.getProtocol())) {
                    callback.onError("insecure_url",
                            "يُشترط اتصال HTTPS آمن للتفعيل", 0);
                    return;
                }

                HttpsURLConnection https = (HttpsURLConnection) url.openConnection();
                CertificatePinningManager.applyPinning(https);
                conn = https;

                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("User-Agent",
                        "MIRA-Android/" + getAppVersion(context));
                conn.setConnectTimeout(CONNECT_TIMEOUT);
                conn.setReadTimeout(READ_TIMEOUT);
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                }

                // ═══ 3. قراءة الاستجابة ═══
                int code = conn.getResponseCode();
                InputStream is = (code >= 200 && code < 300)
                        ? conn.getInputStream()
                        : conn.getErrorStream();

                StringBuilder sb = new StringBuilder();
                if (is != null) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(is, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) sb.append(line);
                    }
                }

                String body = sb.toString().trim();
                Log.d(TAG, "Activate response: HTTP " + code + " | " + body);

                JSONObject json = body.isEmpty() ? new JSONObject() : new JSONObject(body);

                if (code >= 200 && code < 300) {
                    String token = json.optString("activation_token", "");
                    String uuid  = json.optString("device_uuid", "");

                    if (!token.isEmpty()) {
                        // حفظ في SessionManager
                        SessionManager sm = new SessionManager(context);
                        sm.saveActivationToken(token);
                        if (!uuid.isEmpty()) sm.saveDeviceUuid(uuid);

                        JSONObject lic = json.optJSONObject("license");
                        if (lic != null) {
                            sm.saveLicenseInfo(
                                    lic.optString("type", "custom"),
                                    lic.optString("valid_from", ""),
                                    lic.optString("valid_until", ""),
                                    lic.optInt("max_devices", 1),
                                    lic.optInt("max_users", 5)
                            );
                        }

                        JSONObject settings = json.optJSONObject("settings");
                        if (settings != null) {
                            sm.saveOfflineSettings(
                                    settings.optInt("offline_grace_days", 7),
                                    settings.optInt("offline_warning_day", 4),
                                    settings.optInt("offline_restrict_day", 6)
                            );
                        }

                        sm.saveLastHeartbeat(System.currentTimeMillis());

                        Log.d(TAG, "✅ Activation saved successfully");
                    }

                    callback.onSuccess(json);
                } else {
                    String errCode = json.optString("code", "http_" + code);
                    String errMsg  = json.optString("message", "خطأ غير معروف");
                    callback.onError(errCode, errMsg, code);
                }

            } catch (java.net.UnknownHostException e) {
                callback.onError("network", "تعذر الوصول للخادم. تحقق من الإنترنت وعنوان الخادم", 0);
            } catch (java.net.SocketTimeoutException e) {
                callback.onError("timeout", "انتهت مهلة الاتصال بالخادم", 0);
            } catch (Exception e) {
                Log.e(TAG, "Activate exception", e);
                callback.onError("unknown", e.getMessage() != null ? e.getMessage() : "خطأ غير متوقع", 0);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    // ═══════════════════════════════════════════════════════
    //  Heartbeat
    // ═══════════════════════════════════════════════════════

    public static void heartbeat(final Context context,
                                 final Callback callback) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                SessionManager sm = new SessionManager(context);
                String serverUrl = sm.getServerUrl();
                String token = sm.getActivationToken();

                if (token == null || token.isEmpty()) {
                    callback.onError("no_token", "لا يوجد ترخيص مفعّل", 0);
                    return;
                }

                String baseUrl = normalizeUrl(serverUrl);
                String fullUrl = baseUrl + "/" + ENDPOINT_PREFIX + "/heartbeat";

                JSONObject payload = new JSONObject();
                payload.put("app_version", getAppVersion(context));

                URL url = new URL(fullUrl);
                HttpsURLConnection https = (HttpsURLConnection) url.openConnection();
                CertificatePinningManager.applyPinning(https);
                conn = https;

                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("X-MIRA-License-Token", token);
                conn.setRequestProperty("User-Agent",
                        "MIRA-Android/" + getAppVersion(context));
                conn.setConnectTimeout(CONNECT_TIMEOUT);
                conn.setReadTimeout(READ_TIMEOUT);
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();
                InputStream is = (code >= 200 && code < 300)
                        ? conn.getInputStream()
                        : conn.getErrorStream();

                StringBuilder sb = new StringBuilder();
                if (is != null) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(is, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) sb.append(line);
                    }
                }

                String body = sb.toString().trim();
                JSONObject json = body.isEmpty() ? new JSONObject() : new JSONObject(body);

                if (code >= 200 && code < 300) {
                    String action = json.optString("action", "continue");

                    if ("revoke_access".equals(action)) {
                        // الترخيص أُلغي/عُلّق → مسح محلي
                        sm.clearLicense();
                        callback.onError(
                                "revoked",
                                json.optString("reason", "الترخيص موقوف. تواصل مع ميرا"),
                                code
                        );
                        return;
                    }

                    // تحديث آخر heartbeat
                    sm.saveLastHeartbeat(System.currentTimeMillis());

                    // تحديث الإعدادات لو تغيرت
                    JSONObject settings = json.optJSONObject("settings");
                    if (settings != null) {
                        sm.saveOfflineSettings(
                                settings.optInt("offline_grace_days", 7),
                                settings.optInt("offline_warning_day", 4),
                                settings.optInt("offline_restrict_day", 6)
                        );
                    }

                    callback.onSuccess(json);
                } else {
                    String errCode = json.optString("code", "http_" + code);
                    String errMsg  = json.optString("message", "فشل التحقق من الترخيص");
                    callback.onError(errCode, errMsg, code);
                }

            } catch (Exception e) {
                Log.w(TAG, "Heartbeat failed: " + e.getMessage());
                callback.onError("network", e.getMessage(), 0);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    // ═══════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════

    private static String normalizeUrl(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    private static String getAppVersion(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0.0";
        }
    }
}
