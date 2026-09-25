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
import com.example.uhf.manager.OfflineGateCache;

/**
 * MiraGateMonitorService v2.0
 * ═══════════════════════════════════════════════════════════════
 * خدمة مراقبة البوابات في الخلفية.
 *
 * التحسينات الجديدة:
 *  1. 3 قنوات إشعار (Monitor / Alert / Sync)
 *  2. تنظيف دوري للقائمة المسموحة (كل ساعة)
 *  3. إشعار ديناميكي يُحدَّث بعدد القطع المسموحة
 *  4. استقبال أحداث البيع/الإرجاع لتحديث الإشعار
 *  5. إحصائيات داخلية (SALE/REFUND counters)
 *  6. Fail-safe شامل
 */
public class MiraGateMonitorService extends Service {

    private static final String TAG = "MiraGateMonitor";

    // ═══════════════════════════════════════════════════════════
    // Notification Channels
    // ═══════════════════════════════════════════════════════════
    private static final String CHANNEL_MONITOR = "mira_gate_monitor_channel";
    private static final String CHANNEL_ALERT   = "mira_gate_alert_channel";
    private static final String CHANNEL_SYNC    = "mira_gate_sync_channel";

    private static final int NOTIFICATION_ID_MONITOR = 1001;
    private static final int NOTIFICATION_ID_ALERT   = 1002;
    private static final int NOTIFICATION_ID_SYNC    = 1003;

    // ═══════════════════════════════════════════════════════════
    // Extras — يقرأها الـ Receiver
    // ═══════════════════════════════════════════════════════════
    public static final String EXTRA_FORCE_SYNC = "force_sync";
    public static final String EXTRA_EVENT_TYPE = "event_type";
    public static final String EXTRA_ALLOWED    = "allowed";

    // ═══════════════════════════════════════════════════════════
    // Timings
    // ═══════════════════════════════════════════════════════════
    private static final long CLEANUP_INTERVAL_MS = 60 * 60 * 1000L; // كل ساعة
    private static final long NOTIFICATION_UPDATE_MIN_INTERVAL_MS = 5000L;

    // ═══════════════════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════════════════
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastNotificationUpdate = 0L;
    private volatile boolean isRunning = false;

    // إحصائيات الجلسة
    private volatile int sessionSalesCount = 0;
    private volatile int sessionRefundsCount = 0;
    private volatile int sessionAlertsCount = 0;

    // ═══════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            createAllNotificationChannels();
            isRunning = true;
            Log.i(TAG, "Service created");
        } catch (Exception e) {
            Log.e(TAG, "onCreate error: " + e.getMessage(), e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            // 1. Foreground notification
            startForeground(NOTIFICATION_ID_MONITOR, buildMonitorNotification());

            // 2. معالجة Intent الوارد
            if (intent != null) {
                handleIncomingIntent(intent);
            }

            // 3. جدولة التنظيف الدوري (مرة واحدة فقط)
            if (!hasScheduledCleanup) {
                hasScheduledCleanup = true;
                scheduleCleanup();
            }

            Log.d(TAG, "Service started");
        } catch (Exception e) {
            Log.e(TAG, "onStartCommand error: " + e.getMessage(), e);
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        try {
            isRunning = false;
            handler.removeCallbacksAndMessages(null);
            Log.d(TAG, "Service destroyed");
        } catch (Exception e) {
            Log.e(TAG, "onDestroy error: " + e.getMessage());
        }
        super.onDestroy();
    }

    // ═══════════════════════════════════════════════════════════
    // Intent Handling
    // ═══════════════════════════════════════════════════════════

    private void handleIncomingIntent(Intent intent) {
        try {
            String eventType = intent.getStringExtra(EXTRA_EVENT_TYPE);
            boolean forceSync = intent.getBooleanExtra(EXTRA_FORCE_SYNC, false);
            boolean allowed = intent.getBooleanExtra(EXTRA_ALLOWED, false);

            Log.d(TAG, "📥 Intent: type=" + eventType
                    + ", forceSync=" + forceSync
                    + ", allowed=" + allowed);

            // 1. تحديث الإحصائيات
            if (eventType != null) {
                switch (eventType) {
                    case "SALE_COMPLETED":
                        sessionSalesCount++;
                        break;
                    case "REFUND_COMPLETED":
                        sessionRefundsCount++;
                        break;
                    case "GATE_ALERT":
                        sessionAlertsCount++;
                        showAlertNotification(eventType);
                        break;
                    case "GATE_EVENT":
                        if (forceSync) scheduleSyncNotification();
                        break;
                    case "GATE_SYNC":
                        scheduleSyncNotification();
                        break;
                }
            }

            // 2. تحديث الإشعار الدائم (بمعدل محدود)
            updateMonitorNotificationThrottled();

        } catch (Exception e) {
            Log.e(TAG, "handleIncomingIntent error: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Cleanup (تنظيف دوري)
    // ═══════════════════════════════════════════════════════════

    private volatile boolean hasScheduledCleanup = false;

    private void scheduleCleanup() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isRunning) return;

                try {
                    OfflineGateCache cache = OfflineGateCache.getInstance(
                            getApplicationContext());
                    int removed = cache.cleanupExpiredAllowed();
                    int remaining = cache.getAllowedListSize();

                    Log.i(TAG, "🧹 Cleanup: removed=" + removed
                            + ", remaining=" + remaining);

                    // تحديث الإشعار بالعدد الجديد
                    updateMonitorNotification();

                } catch (Exception e) {
                    Log.e(TAG, "scheduleCleanup error: " + e.getMessage());
                }

                // جدولة الدورة التالية
                if (isRunning) {
                    handler.postDelayed(this, CLEANUP_INTERVAL_MS);
                }
            }
        }, CLEANUP_INTERVAL_MS);
    }

    // ═══════════════════════════════════════════════════════════
    // Notifications
    // ═══════════════════════════════════════════════════════════

    private void createAllNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;

        // 1. Monitor (LOW — دائم)
        NotificationChannel monitor = new NotificationChannel(
                CHANNEL_MONITOR,
                "MIRA Gate Monitor",
                NotificationManager.IMPORTANCE_LOW
        );
        monitor.setDescription("مراقبة البوابات الأمنية في الخلفية");
        monitor.setShowBadge(false);
        manager.createNotificationChannel(monitor);

        // 2. Alert (HIGH — إنذار)
        NotificationChannel alert = new NotificationChannel(
                CHANNEL_ALERT,
                "MIRA Gate Alerts",
                NotificationManager.IMPORTANCE_HIGH
        );
        alert.setDescription("إنذارات أمنية من البوابات");
        alert.setShowBadge(true);
        alert.enableVibration(true);
        manager.createNotificationChannel(alert);

        // 3. Sync (DEFAULT — مزامنة)
        NotificationChannel sync = new NotificationChannel(
                CHANNEL_SYNC,
                "MIRA Gate Sync",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        sync.setDescription("إشعارات مزامنة بيانات البوابات");
        sync.setShowBadge(true);
        manager.createNotificationChannel(sync);
    }

    private Notification buildMonitorNotification() {
        int allowedCount = 0;
        int pendingEvents = 0;
        try {
            OfflineGateCache cache = OfflineGateCache.getInstance(this);
            allowedCount = cache.getAllowedListSize();
            pendingEvents = cache.getPendingEventsCount();
        } catch (Exception e) {
            Log.w(TAG, "buildMonitorNotification stats error: " + e.getMessage());
        }

        String contentText = "🛡️ مراقبة نشطة"
                + " • مسموح: " + allowedCount
                + " • معلّق: " + pendingEvents;

        if (sessionSalesCount > 0 || sessionRefundsCount > 0) {
            contentText += " • بيع: " + sessionSalesCount
                    + " • إرجاع: " + sessionRefundsCount;
        }

        Intent notificationIntent = new Intent(this, UHFMainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int piFlags = PendingIntent.FLAG_IMMUTABLE
                | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, piFlags);

        return new NotificationCompat.Builder(this, CHANNEL_MONITOR)
                .setContentTitle("🛡️ MIRA Gate Monitor")
                .setContentText(contentText)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(contentText))
                .setSmallIcon(R.drawable.ic_luxury_mira_logo)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateMonitorNotification() {
        try {
            NotificationManager nm = (NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID_MONITOR, buildMonitorNotification());
            }
        } catch (Exception e) {
            Log.w(TAG, "updateMonitorNotification error: " + e.getMessage());
        }
    }

    private void updateMonitorNotificationThrottled() {
        long now = System.currentTimeMillis();
        if (now - lastNotificationUpdate < NOTIFICATION_UPDATE_MIN_INTERVAL_MS) {
            return;
        }
        lastNotificationUpdate = now;
        updateMonitorNotification();
    }

    private void showAlertNotification(String eventType) {
        try {
            Intent alertIntent = new Intent(this, UHFMainActivity.class);
            alertIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            alertIntent.putExtra("open_gate_alerts", true);

            int piFlags = PendingIntent.FLAG_IMMUTABLE
                    | PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this, 100, alertIntent, piFlags);

            Notification notification = new NotificationCompat.Builder(
                    this, CHANNEL_ALERT)
                    .setContentTitle("🚨 إنذار أمني")
                    .setContentText("محاولة خروج غير مصرّح بها")
                    .setSmallIcon(R.drawable.ic_luxury_mira_logo)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .build();

            NotificationManager nm = (NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID_ALERT, notification);
            }
        } catch (Exception e) {
            Log.e(TAG, "showAlertNotification error: " + e.getMessage());
        }
    }

    private void scheduleSyncNotification() {
        try {
            Notification notification = new NotificationCompat.Builder(
                    this, CHANNEL_SYNC)
                    .setContentTitle("🔄 مزامنة البوابات")
                    .setContentText("جارٍ مزامنة البيانات...")
                    .setSmallIcon(R.drawable.ic_luxury_mira_logo)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build();

            NotificationManager nm = (NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID_SYNC, notification);
            }
        } catch (Exception e) {
            Log.e(TAG, "scheduleSyncNotification error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Public static helpers
    // ═══════════════════════════════════════════════════════════

    /**
     * يُشغّل الخدمة بشكل آمن من أي مكان.
     */
    public static void startSafely(Context context) {
        if (context == null) return;
        try {
            Intent intent = new Intent(context, MiraGateMonitorService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "startSafely error: " + e.getMessage());
        }
    }

    /**
     * يُوقف الخدمة بشكل آمن.
     */
    public static void stopSafely(Context context) {
        if (context == null) return;
        try {
            context.stopService(new Intent(context, MiraGateMonitorService.class));
        } catch (Exception e) {
            Log.e(TAG, "stopSafely error: " + e.getMessage());
        }
    }
}
