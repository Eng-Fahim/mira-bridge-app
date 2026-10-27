package com.example.uhf.license;

import android.content.Context;
import android.util.Log;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * HeartbeatScheduler — بدء/إيقاف/تسريع الـ Heartbeat
 */
public class HeartbeatScheduler {

    private static final String TAG = "HeartbeatScheduler";

    /**
     * فاصل النبضة — 6 ساعات
     * الحد الأدنى في WorkManager = 15 دقيقة
     */
    private static final long INTERVAL_HOURS = 6;

    /**
     * تسجيل الجدولة الدورية — يُستدعى مرة واحدة بعد التفعيل
     */
    public static void schedule(Context context) {
        try {
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();

            PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                    HeartbeatWorker.class,
                    INTERVAL_HOURS, TimeUnit.HOURS,
                    30, TimeUnit.MINUTES  // flexible window
            )
                    .setConstraints(constraints)
                    .addTag(HeartbeatWorker.WORK_NAME)
                    .build();

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    HeartbeatWorker.WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
            );

            Log.d(TAG, "✅ Heartbeat scheduled every " + INTERVAL_HOURS + "h");
        } catch (Exception e) {
            Log.e(TAG, "❌ Schedule failed: " + e.getMessage(), e);
        }
    }

    /**
     * تشغيل نبضة فورية (عند عودة الإنترنت أو عند فتح التطبيق)
     */
    public static void triggerNow(Context context) {
        try {
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();

            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(HeartbeatWorker.class)
                    .setConstraints(constraints)
                    .addTag(HeartbeatWorker.WORK_NAME + "_now")
                    .build();

            WorkManager.getInstance(context).enqueue(request);
            Log.d(TAG, "⚡ Immediate heartbeat triggered");
        } catch (Exception e) {
            Log.e(TAG, "❌ Trigger failed: " + e.getMessage(), e);
        }
    }

    /**
     * إيقاف الجدولة (عند إلغاء الترخيص أو Logout كامل)
     */
    public static void cancel(Context context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(HeartbeatWorker.WORK_NAME);
            Log.d(TAG, "🛑 Heartbeat cancelled");
        } catch (Exception e) {
            Log.e(TAG, "❌ Cancel failed: " + e.getMessage(), e);
        }
    }
}
