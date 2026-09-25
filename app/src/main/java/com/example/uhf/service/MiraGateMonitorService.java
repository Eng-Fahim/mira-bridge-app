package com.example.uhf.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.OfflineGateCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ✅ MiraGateMonitorService v2.0
 *
 * الخدمة الاحترافية لمراقبة البوابات في الخلفية.
 *
 * الميزات:
 * 1. مراقبة فعلية للبوابات كل 30 ثانية
 * 2. إشعار عند تغيير حالة أي بوابة
 * 3. مزامنة الأحداث المعلقة تلقائياً
 * 4. Exponential Backoff عند فشل الشبكة
 * 5. إشعارات منفصلة لكل نوع حدث
 * 6. تسجيل مستمر في سجل موحد
 */
public class MiraGateMonitorService extends Service {

    private static final String TAG = "MiraGateMonitor";

    // ==================== الإشعارات ====================
    private static final String CHANNEL_SERVICE = "mira_gate_service";
    private static final String CHANNEL_ALERTS = "mira_gate_alerts";
    private static final String CHANNEL_EVENTS = "mira_gate_events";

    private static final int NOTIFICATION_ID_SERVICE = 1001;
    private static final int NOTIFICATION_ID_ALERT_BASE = 2000;

    // ==================== الإعدادات ====================
    private static final long INITIAL_POLL_INTERVAL_MS = 30_000;    // 30 ثانية
    private static final long MAX_POLL_INTERVAL_MS = 5 * 60_000;     // 5 دقائق
    private static final long MIN_POLL_INTERVAL_MS = 15_000;         // 15 ثانية
    private static final long SYNC_INTERVAL_MS = 60_000;             // 60 ثانية

    // ==================== الحالة ====================
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> pollFuture;
    private ScheduledFuture<?> syncFuture;

    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private OfflineGateCache offlineCache;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private volatile long currentPollInterval = INITIAL_POLL_INTERVAL_MS;
    private volatile int consecutiveFailures = 0;
    private volatile int lastKnownGateCount = -1;
    private volatile boolean lastKnownOnlineStatus = true;

    // ==================== دورة الحياة ====================

    @Override
    public void onCreate() {
        super.onCreate();

        Log.d(TAG, "🚀 onCreate");

        try {
            Context ctx = getApplicationContext();
            apiClient = MiraApiClient.getInstance(ctx);
            branchesManager = MiraBranchesManager.getInstance(ctx);
            offlineCache = OfflineGateCache.getInstance(ctx);
        } catch (Exception e) {
            Log.e(TAG, "onCreate init error: " + e.getMessage(), e);
        }

        createNotificationChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "🚀 onStartCommand");

        // ✅ تحقق من عدم التشغيل المزدوج
        if (isRunning.get()) {
            Log.d(TAG, "Service already running, ignoring duplicate start");
            return START_STICKY;
        }

        // ✅ ابدأ الخدمة في المقدمة
        startForeground(NOTIFICATION_ID_SERVICE, buildServiceNotification());

        // ✅ شغّل المراقبة
        startMonitoring();

        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "🛑 onDestroy");

        stopMonitoring();

        super.onDestroy();
    }

    // ==================== المراقبة ====================

    private void startMonitoring() {
        if (!isRunning.compareAndSet(false, true)) {
            Log.w(TAG, "startMonitoring called but already running");
            return;
        }

        try {
            scheduler = Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "GateMonitor-Worker");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });

            // ✅ 1) Poll البوابات — كل 30 ثانية
            pollFuture = scheduler.scheduleWithFixedDelay(
                    this::pollGatesTask,
                    0,
                    INITIAL_POLL_INTERVAL_MS,
                    TimeUnit.MILLISECONDS
            );

            // ✅ 2) مزامنة الأحداث المعلقة — كل 60 ثانية
            syncFuture = scheduler.scheduleWithFixedDelay(
                    this::syncPendingEventsTask,
                    15_000,         // ابدأ بعد 15 ثانية
                    SYNC_INTERVAL_MS,
                    TimeUnit.MILLISECONDS
            );

            Log.d(TAG, "✅ Monitoring started");

        } catch (Exception e) {
            Log.e(TAG, "startMonitoring error: " + e.getMessage(), e);
            isRunning.set(false);
        }
    }

    private void stopMonitoring() {
        if (!isRunning.compareAndSet(true, false)) {
            return;
        }

        try {
            if (pollFuture != null) {
                pollFuture.cancel(true);
                pollFuture = null;
            }
            if (syncFuture != null) {
                syncFuture.cancel(true);
                syncFuture = null;
            }
            if (scheduler != null) {
                scheduler.shutdownNow();
                scheduler = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "stopMonitoring error: " + e.getMessage(), e);
        }

        Log.d(TAG, "🛑 Monitoring stopped");
    }

    // ==================== Poll البوابات ====================

    private void pollGatesTask() {
        if (!isRunning.get()) return;

        try {
            if (apiClient == null) {
                Log.w(TAG, "apiClient is null, skipping poll");
                return;
            }

            int branchId = branchesManager != null
                    ? branchesManager.getActiveBranchId() : 0;

            MiraApiClient.ApiResponse response = apiClient.getGatesByBranch(branchId);

            if (response != null && response.isSuccess && response.data != null) {
                // ✅ نجاح — أعد ضبط الـ backoff
                consecutiveFailures = 0;
                currentPollInterval = INITIAL_POLL_INTERVAL_MS;

                if (!lastKnownOnlineStatus) {
                    lastKnownOnlineStatus = true;
                    Log.d(TAG, "✅ Connection restored");
                }

                processGatesResponse(response.data);

            } else {
                // ❌ فشل
                handlePollFailure(response != null ? response.error : "Unknown error");
            }

        } catch (Exception e) {
            Log.e(TAG, "pollGatesTask error: " + e.getMessage(), e);
            handlePollFailure(e.getMessage());
        }
    }

    private void processGatesResponse(JSONObject data) {
        try {
            JSONArray gatesArray = extractGatesArray(data);
            if (gatesArray == null) return;

            int currentCount = gatesArray.length();

            // ✅ 1) تغيير عدد البوابات
            if (lastKnownGateCount != -1 && lastKnownGateCount != currentCount) {
                String msg = "🔔 عدد البوابات تغير: " + lastKnownGateCount
                        + " → " + currentCount;
                Log.d(TAG, msg);
                showEventNotification("تغيير في البوابات", msg);
            }
            lastKnownGateCount = currentCount;

            // ✅ 2) فحص حالة كل بوابة
            for (int i = 0; i < gatesArray.length(); i++) {
                JSONObject gate = gatesArray.optJSONObject(i);
                if (gate == null) continue;

                String gateName = gate.optString("gate_name", "بوابة");
                String status = gate.optString("status", "offline");
                int denyCount = gate.optInt("deny_count", 0);
                String lastEvent = gate.optString("last_event", "");

                // ✅ 3) حالة الخرق
                if ("alert".equals(status) || "breach".equals(status)) {
                    showAlertNotification(
                            "🚨 خرق أمني في " + gateName,
                            lastEvent.isEmpty() ? "حدث اختراق" : lastEvent
                    );
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "processGatesResponse error: " + e.getMessage(), e);
        }
    }

    private JSONArray extractGatesArray(JSONObject data) {
        JSONArray arr = data.optJSONArray("gates");
        if (arr != null) return arr;

        JSONObject d = data.optJSONObject("data");
        if (d != null) {
            arr = d.optJSONArray("gates");
            if (arr != null) return arr;
        }

        return data.optJSONArray("items");
    }

    private void handlePollFailure(String error) {
        consecutiveFailures++;

        if (lastKnownOnlineStatus) {
            lastKnownOnlineStatus = false;
            Log.w(TAG, "⚠️ Connection lost: " + error);
        }

        // ✅ Exponential Backoff
        long newInterval = Math.min(
                INITIAL_POLL_INTERVAL_MS * (1L << Math.min(consecutiveFailures, 5)),
                MAX_POLL_INTERVAL_MS
        );

        if (newInterval != currentPollInterval) {
            currentPollInterval = newInterval;
            Log.d(TAG, "📉 Poll interval increased to " + (currentPollInterval / 1000) + "s");
        }
    }

    // ==================== مزامنة الأحداث ====================

    private void syncPendingEventsTask() {
        if (!isRunning.get()) return;

        try {
            if (offlineCache == null || apiClient == null) return;

            JSONArray pendingEvents = offlineCache.getOfflineEvents();
            if (pendingEvents == null || pendingEvents.length() == 0) return;

            Log.d(TAG, "🔄 Syncing " + pendingEvents.length() + " pending events");

            MiraApiClient.ApiResponse response = apiClient.syncOfflineTransactions(pendingEvents);

            if (response != null && response.isSuccess) {
                offlineCache.clearOfflineEvents();
                offlineCache.saveLastSyncTime(System.currentTimeMillis());

                Log.d(TAG, "✅ Synced " + pendingEvents.length() + " events");
            }

        } catch (Exception e) {
            Log.e(TAG, "syncPendingEventsTask error: " + e.getMessage(), e);
        }
    }

    // ==================== الإشعارات ====================

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;

        // ✅ 1) Service channel (منخفض الأهمية)
        NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_SERVICE,
                "مراقبة البوابات",
                NotificationManager.IMPORTANCE_LOW
        );
        serviceChannel.setDescription("خدمة مراقبة البوابات الأمنية في الخلفية");
        serviceChannel.setShowBadge(false);
        manager.createNotificationChannel(serviceChannel);

        // ✅ 2) Alerts channel (عالي جداً)
        NotificationChannel alertsChannel = new NotificationChannel(
                CHANNEL_ALERTS,
                "🚨 إنذارات أمنية",
                NotificationManager.IMPORTANCE_HIGH
        );
        alertsChannel.setDescription("إنذارات فورية عند اكتشاف خرق أمني");
        alertsChannel.enableVibration(true);
        alertsChannel.setVibrationPattern(new long[]{0, 1000, 500, 1000});
        alertsChannel.enableLights(true);
        alertsChannel.setLightColor(0xFFFF0000);
        alertsChannel.setShowBadge(true);
        manager.createNotificationChannel(alertsChannel);

        // ✅ 3) Events channel (متوسط)
        NotificationChannel eventsChannel = new NotificationChannel(
                CHANNEL_EVENTS,
                "📋 أحداث البوابات",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        eventsChannel.setDescription("أحداث عادية في نظام البوابات");
        eventsChannel.setShowBadge(true);
        manager.createNotificationChannel(eventsChannel);
    }

    private Notification buildServiceNotification() {
        Intent notificationIntent = new Intent(this, UHFMainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        String statusText = lastKnownOnlineStatus
                ? "المراقبة الأمنية تعمل في الخلفية"
                : "⚠️ غير متصل - إعادة المحاولة...";

        return new NotificationCompat.Builder(this, CHANNEL_SERVICE)
                .setContentTitle("🛡️ MIRA Gate Monitor")
                .setContentText(statusText)
                .setSmallIcon(R.drawable.ic_luxury_mira_logo)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void showAlertNotification(String title, String message) {
        try {
            Intent intent = new Intent(this, UHFMainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra("navigate_to", "SecureGate");

            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this,
                    (int) System.currentTimeMillis(),
                    intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            );

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ALERTS)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setSmallIcon(R.drawable.ic_luxury_mira_logo)
                    .setContentIntent(pendingIntent)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setAutoCancel(true)
                    .setVibrate(new long[]{0, 1000, 500, 1000})
                    .build();

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                int notificationId = NOTIFICATION_ID_ALERT_BASE
                        + (int) (System.currentTimeMillis() % 1000);
                manager.notify(notificationId, notification);
            }

            Log.d(TAG, "🚨 Alert notification: " + title);

        } catch (Exception e) {
            Log.e(TAG, "showAlertNotification error: " + e.getMessage(), e);
        }
    }

    private void showEventNotification(String title, String message) {
        try {
            Intent intent = new Intent(this, UHFMainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra("navigate_to", "SecureGate");

            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this,
                    (int) System.currentTimeMillis(),
                    intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            );

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_EVENTS)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setSmallIcon(R.drawable.ic_luxury_mira_logo)
                    .setContentIntent(pendingIntent)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setCategory(NotificationCompat.CATEGORY_EVENT)
                    .setAutoCancel(true)
                    .build();

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                int notificationId = 3000 + (int) (System.currentTimeMillis() % 1000);
                manager.notify(notificationId, notification);
            }

            Log.d(TAG, "📋 Event notification: " + title);

        } catch (Exception e) {
            Log.e(TAG, "showEventNotification error: " + e.getMessage(), e);
        }
    }
}
