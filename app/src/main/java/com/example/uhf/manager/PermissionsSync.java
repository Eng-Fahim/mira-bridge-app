package com.example.uhf.manager;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.uhf.network.NetworkClient;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ═══════════════════════════════════════════════════════════════
 *  PermissionsSync — يجعل الصلاحيات تنعكس فوراً دون إعادة تسجيل دخول
 * ═══════════════════════════════════════════════════════════════
 *
 *  آليتان تعملان معاً:
 *    1) onResumeCheck() — فحص فوري عند عودة التطبيق للواجهة.
 *    2) startPolling() / stopPolling() — كل 30 ثانية خلال الاستخدام.
 *
 *  كل فحص = GET /me/permissions/version (~50 bytes)
 *  عند اختلاف النسخة → GET /me/permissions → applyFromServer → notify
 */
public class PermissionsSync {

    private static final String TAG = "PermissionsSync";
    private static final long POLL_INTERVAL_MS = 30_000L;

    private static volatile PermissionsSync instance;

    private final Context appContext;
    private final PermissionManager permissionManager;
    private final NetworkClient networkClient;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean polling = new AtomicBoolean(false);
    private final AtomicBoolean fetching = new AtomicBoolean(false);

    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            checkNow(null);
            if (polling.get()) {
                handler.postDelayed(this, POLL_INTERVAL_MS);
            }
        }
    };

    public interface Callback {
        void onResult(boolean changed, Throwable error);
    }

    private PermissionsSync(Context ctx, NetworkClient client) {
        this.appContext = ctx.getApplicationContext();
        this.permissionManager = PermissionManager.getInstance(appContext);
        this.networkClient = client;
    }

    public static PermissionsSync getInstance(Context ctx, NetworkClient client) {
        if (instance == null) {
            synchronized (PermissionsSync.class) {
                if (instance == null) instance = new PermissionsSync(ctx, client);
            }
        }
        return instance;
    }

    /** يُستدعى من BaseActivity.onResume() */
    public void onResumeCheck() {
        checkNow(null);
    }

    /** بدء الفحص الدوري — استدعها من أي شاشة مفتوحة */
    public void startPolling() {
        if (polling.compareAndSet(false, true)) {
            handler.post(pollRunnable);
            Log.d(TAG, "▶ polling started");
        }
    }

    public void stopPolling() {
        if (polling.compareAndSet(true, false)) {
            handler.removeCallbacks(pollRunnable);
            Log.d(TAG, "⏸ polling stopped");
        }
    }

    /**
     * فحص فوري متزامن (async) — يُستدعى عند onResume أو يدوياً.
     */
        public void checkNow(Callback cb) {
        if (!fetching.compareAndSet(false, true)) {
            if (cb != null) cb.onResult(false, null);
            return;
        }

        networkClient.get("/api/v1/me/permissions/version", new NetworkClient.JsonCallback() {
            @Override public void onSuccess(JSONObject versionResponse) {
                try {
                    String serverVersion = "";
                    if (versionResponse != null) {
                        serverVersion = versionResponse.optString("version", "");
                        if (serverVersion.isEmpty() && versionResponse.has("data")) {
                            JSONObject data = versionResponse.optJSONObject("data");
                            if (data != null) {
                                serverVersion = data.optString("version", "");
                            }
                        }
                    }

                    if (!permissionManager.needsRefresh(serverVersion)) {
                        if (cb != null) cb.onResult(false, null);
                        fetching.set(false);
                        return;
                    }

                    // النسخة تغيّرت → اجلب الحمولة الكاملة
                    fetchFullPermissions(serverVersion, cb);
                } catch (Throwable t) {
                    Log.e(TAG, "version check error", t);
                    if (cb != null) cb.onResult(false, t);
                    fetching.set(false);
                }
            }

            @Override public void onError(Throwable t) {
                String msg = (t != null && t.getMessage() != null) ? t.getMessage() : "";
                Log.w(TAG, "version endpoint failed: " + msg);

                // إذا تم إلغاء التوكن من الويب (Force Logout) سيعود السيرفر بـ 401 → اطرد الجلسة فوراً
                if (msg.contains("401") || msg.toLowerCase().contains("unauthenticated")
                        || msg.toLowerCase().contains("unauthorized")) {
                    PermissionSyncManager.triggerForceLogout(appContext, null);
                }

                if (cb != null) cb.onResult(false, t);
                fetching.set(false);
            }
        });
    }


    private void fetchFullPermissions(String serverVersion, Callback cb) {
        networkClient.get("/api/v1/me/permissions", new NetworkClient.JsonCallback() {
            @Override public void onSuccess(JSONObject fullResponse) {
                try {
                    boolean changed = permissionManager.applyFromServer(fullResponse, serverVersion);
                    Log.i(TAG, changed
                            ? "✅ permissions updated (new version: " + shortV(serverVersion) + ")"
                            : "no effective change");
                    if (cb != null) cb.onResult(changed, null);
                } catch (Throwable t) {
                    Log.e(TAG, "apply failed", t);
                    if (cb != null) cb.onResult(false, t);
                } finally {
                    fetching.set(false);
                }
            }

            @Override public void onError(Throwable t) {
                Log.w(TAG, "full permissions fetch failed: " + t.getMessage());
                if (cb != null) cb.onResult(false, t);
                fetching.set(false);
            }
        });
    }

    private static String shortV(String v) {
        if (v == null || v.length() < 10) return String.valueOf(v);
        return v.substring(0, 8) + "…";
    }
}
