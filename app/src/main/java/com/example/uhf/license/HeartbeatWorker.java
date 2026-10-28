package com.example.uhf.license;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.uhf.tools.SessionManager;

import org.json.JSONObject;

/**
 * HeartbeatWorker — يعمل كل 6 ساعات في الخلفية
 *
 * v2.0 — يدمج:
 *   1. جلب الـ blob من خادم العميل
 *   2. التحقق من التوقيع الرقمي
 *   3. إرسال heartbeat للمركز مع blob_version
 */
public class HeartbeatWorker extends Worker {

    private static final String TAG = "HeartbeatWorker";
    public static final String WORK_NAME = "mira_license_heartbeat";

    public HeartbeatWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        SessionManager sm = new SessionManager(ctx);

        // ═══════════════════════════════════════════════════════
        //  [1] فحص وجود ترخيص (activation token)
        // ═══════════════════════════════════════════════════════
        String token = sm.getActivationToken();
        if (token == null || token.isEmpty()) {
            Log.w(TAG, "⚠️ No activation token — nothing to heartbeat");
            return Result.success();
        }

        // ═══════════════════════════════════════════════════════
        //  [2] جلب وتحديث الـ blob من خادم العميل
        // ═══════════════════════════════════════════════════════
        Log.d(TAG, "🔄 Fetching latest license blob from server");
        boolean blobUpdated = false;
        try {
            blobUpdated = MiraBlobFetcher.fetchVerifyAndStore(ctx);
        } catch (Exception e) {
            Log.e(TAG, "Blob fetch error: " + e.getMessage(), e);
        }

        if (!blobUpdated) {
            Log.w(TAG, "⚠️ Could not fetch fresh blob — verifying stored one");

            MiraBlobStorage.BlobData stored = MiraBlobStorage.load(ctx);
            if (stored == null) {
                Log.e(TAG, "🚫 No license at all — blocking app");
                sm.clearLicense();
                return Result.failure();
            }

            MiraBlobVerifier.VerifyResult verify = MiraBlobVerifier.verify(
                    stored.blobJson, stored.signatureB64, stored.publicKeyB64
            );
            if (!verify.valid) {
                Log.e(TAG, "🚨 Stored license tampered — blocking app");
                sm.clearLicense();
                return Result.failure();
            }

            Log.d(TAG, "✅ Stored license valid (v" + stored.version + ")");
        } else {
            Log.d(TAG, "✅ Fresh license verified and stored");
        }

        // ═══════════════════════════════════════════════════════
        //  [3] إرسال heartbeat (blocking — ننتظر النتيجة)
        // ═══════════════════════════════════════════════════════
        Log.d(TAG, "💓 Sending heartbeat...");

        final Object lock = new Object();
        final boolean[] done = {false};
        final boolean[] success = {false};
        final String[] failureCode = {null};

        LicenseManager.heartbeat(ctx, new LicenseManager.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                synchronized (lock) {
                    success[0] = true;
                    done[0] = true;
                    lock.notifyAll();
                }
            }

            @Override
            public void onError(String code, String message, int httpCode) {
                synchronized (lock) {
                    success[0] = false;
                    failureCode[0] = code;
                    done[0] = true;
                    lock.notifyAll();
                }
            }
        });

        // انتظر النتيجة بحد أقصى 20 ثانية
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + 20_000;
            while (!done[0]) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                try {
                    lock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (!done[0]) {
            Log.w(TAG, "⏱️ Heartbeat timeout");
            return Result.retry();
        }

        if (success[0]) {
            Log.d(TAG, "✅ Heartbeat OK");
            return Result.success();
        }

        // ═══════════════════════════════════════════════════════
        //  [4] تحليل الفشل
        // ═══════════════════════════════════════════════════════
        String code = failureCode[0];
        Log.w(TAG, "❌ Heartbeat failed: " + code);

        if ("revoked".equals(code) || "kill_switch".equals(code)
                || "license_killed".equals(code)) {
            Log.e(TAG, "🚨 License revoked remotely — clearing local state");
            sm.clearLicense();
            MiraBlobStorage.clear(ctx);
            sm.logoutUser();
            return Result.failure();
        }

        if ("license_expired".equals(code) || "license_inactive".equals(code)) {
            Log.e(TAG, "⏰ License expired/inactive — clearing");
            sm.clearLicense();
            MiraBlobStorage.clear(ctx);
            return Result.failure();
        }

        // فشل شبكة أو مؤقت — أعد المحاولة لاحقاً
        return Result.retry();
    }
}
