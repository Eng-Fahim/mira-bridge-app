package com.example.uhf.manager;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.uhf.network.NetworkClient;
import com.example.uhf.tools.SessionManager;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ═══════════════════════════════════════════════════════════════
 *  PermissionSyncManager — يجعل الصلاحيات تنعكس فوراً دون إعادة تسجيل دخول
 * ═══════════════════════════════════════════════════════════════
 *
 *  آليتان تعملان معاً:
 *    1) onResumeCheck() — فحص فوري عند عودة التطبيق للواجهة.
 *    2) startPolling() / stopPolling() — كل 30 ثانية خلال الاستخدام.
 *
 *  كل فحص = GET /me/permissions/version (~50 bytes)
 *  عند اختلاف النسخة → GET /me/permissions → applyFromServer → notify
 */
public class PermissionSyncManager {

    private static final String TAG = "PermissionSyncManager";
    private static final long POLL_INTERVAL_MS = 30_000L;

    private static volatile PermissionSyncManager instance;
    private static volatile boolean forceLogoutInProgress = false;

    private final Context appContext;
    private final PermissionManager permissionManager;
    private final NetworkClient networkClient;
    private final SessionManager sessionManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean polling = new AtomicBoolean(false);
    private final AtomicBoolean fetching = new AtomicBoolean(false);

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isActiveSession()) {
                stopPolling();
                return;
            }
            checkNow(null);
            if (polling.get() && isActiveSession()) {
                handler.postDelayed(this, POLL_INTERVAL_MS);
            }
        }
    };

    public interface Callback {
        void onResult(boolean changed, Throwable error);
    }

    private PermissionSyncManager(Context ctx, NetworkClient client) {
        this.appContext = ctx.getApplicationContext();
        this.permissionManager = PermissionManager.getInstance(appContext);
        this.networkClient = client;
        this.sessionManager = new SessionManager(appContext);
    }

    public static PermissionSyncManager getInstance(Context ctx, NetworkClient client) {
        if (instance == null) {
            synchronized (PermissionSyncManager.class) {
                if (instance == null) {
                    instance = new PermissionSyncManager(ctx, client);
                }
            }
        }
        return instance;
    }

    private boolean isActiveSession() {
        return sessionManager != null && sessionManager.isLoggedIn();
    }

    public static void resetForceLogoutGuard() {
        forceLogoutInProgress = false;
    }

    /** يُستدعى من BaseActivity.onResume() */
    public void onResumeCheck() {
        if (!isActiveSession()) {
            stopPolling();
            return;
        }
        forceLogoutInProgress = false;
        checkNow(null);
    }

    /** بدء الفحص الدوري — استدعها من أي شاشة مفتوحة */
    public void startPolling() {
        if (!isActiveSession()) {
            stopPolling();
            return;
        }
        forceLogoutInProgress = false;
        if (polling.compareAndSet(false, true)) {
            handler.removeCallbacks(pollRunnable);
            handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
            Log.d(TAG, "▶ polling started");
        }
    }

    public void stopPolling() {
        polling.set(false);
        handler.removeCallbacks(pollRunnable);
        Log.d(TAG, "⏸ polling stopped");
    }

    /**
     * فحص فوري متزامن (async) — يُستدعى عند onResume أو يدوياً.
     */
    public void checkNow(Callback cb) {
        if (!isActiveSession()) {
            stopPolling();
            if (cb != null) cb.onResult(false, null);
            return;
        }

        if (!fetching.compareAndSet(false, true)) {
            if (cb != null) cb.onResult(false, null);
            return;
        }

        networkClient.get("/api/v1/me/permissions/version", new NetworkClient.JsonCallback() {
            @Override
            public void onSuccess(JSONObject versionResponse) {
                try {
                    if (!isActiveSession()) {
                        stopPolling();
                        if (cb != null) cb.onResult(false, null);
                        return;
                    }

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

            @Override
            public void onError(Throwable t) {
                String msg = (t != null && t.getMessage() != null) ? t.getMessage() : "";
                Log.w(TAG, "version endpoint failed: " + msg);

                // إيقاف الفحص الدوري فوراً عند حدوث خطأ أو 401 لمنع التكرار
                stopPolling();

                if (isActiveSession() && (msg.contains("401")
                        || msg.toLowerCase().contains("unauthenticated")
                        || msg.toLowerCase().contains("unauthorized"))) {
                    PermissionSyncManager.triggerForceLogout(appContext, null);
                }

                if (cb != null) cb.onResult(false, t);
                fetching.set(false);
            }
        });
    }

    private void fetchFullPermissions(String serverVersion, Callback cb) {
        if (!isActiveSession()) {
            stopPolling();
            fetching.set(false);
            if (cb != null) cb.onResult(false, null);
            return;
        }

        networkClient.get("/api/v1/me/permissions", new NetworkClient.JsonCallback() {
            @Override
            public void onSuccess(JSONObject fullResponse) {
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

            @Override
            public void onError(Throwable t) {
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

    public static void triggerForceLogout(Context context, String serverMessage) {
        if (forceLogoutInProgress) {
            return;
        }
        forceLogoutInProgress = true;

        try {
            Log.w(TAG, "Force logout triggered by server");

            Context appCtx = context.getApplicationContext();
            if (instance != null) {
                instance.stopPolling();
            }

            SessionManager sessionManager = new SessionManager(appCtx);
            if (sessionManager.isLoggedIn()) {
                sessionManager.logoutUser();
            }

            new Handler(Looper.getMainLooper()).post(() -> {
                android.content.Intent intent =
                        new android.content.Intent(
                                appCtx,
                                com.example.uhf.activity.LoginActivity.class
                        );

                intent.addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                );

                if (serverMessage != null && !serverMessage.trim().isEmpty()) {
                    intent.putExtra("force_logout_message", serverMessage);
                }

                appCtx.startActivity(intent);
            });
        } catch (Exception e) {
            forceLogoutInProgress = false;
            Log.e(TAG, "Force logout failed", e);
        }
    }
}
