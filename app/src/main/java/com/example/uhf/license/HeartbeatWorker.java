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
 * المهام:
 *   1. إرسال heartbeat إلى خادم ميرا
 *   2. تحديث last_heartbeat (يُستخدم لحساب فترة السماح offline)
 *   3. إذا رجع action=revoke_access → مسح الترخيص محلياً
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

        // ═══ 1. تحقق من وجود ترخيص ═══
        String token = sm.getActivationToken();
        if (token == null || token.isEmpty()) {
            Log.w(TAG, "⚠️ No activation token — nothing to heartbeat");
            return Result.success(); // لا حاجة لإعادة المحاولة
        }

        Log.d(TAG, "💓 Sending heartbeat...");

        // ═══ 2. إرسال heartbeat (blocking — ننتظر النتيجة) ═══
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

        // ═══ 3. تحليل الفشل ═══
        String code = failureCode[0];
        Log.w(TAG, "❌ Heartbeat failed: " + code);

        if ("revoked".equals(code) || "kill_switch".equals(code)
                || "license_killed".equals(code)) {
            // الترخيص أُلغي — سجّل خروجاً كاملاً
            Log.e(TAG, "🚨 License revoked remotely — clearing local state");
            sm.clearLicense();
            sm.logoutUser();
            return Result.failure();
        }

        if ("license_expired".equals(code) || "license_inactive".equals(code)) {
            // الترخيص منتهي — امسح فقط الترخيص
            sm.clearLicense();
            return Result.failure();
        }

        // فشل شبكة أو فشل مؤقت — أعد المحاولة لاحقاً
        return Result.retry();
    }
}
