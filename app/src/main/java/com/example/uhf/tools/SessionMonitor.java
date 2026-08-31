package com.example.uhf.tools;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SessionMonitor - مراقبة تلقائية لصلاحية الجلسة
 * 
 * المميزات:
 * - فحص صلاحية التوكن كل 60 ثانية
 * - إشعار قبل انتهاء الجلسة بـ 5 دقائق
 * - تسجيل خروج تلقائي عند انتهاء الصلاحية
 * - دعم المستمعين (Listeners)
 * 
 * @version 1.0.0
 */
public class SessionMonitor {

    private static final String TAG = "SessionMonitor";
    
    // فترات الفحص
    private static final long CHECK_INTERVAL_SECONDS = 60; // فحص كل دقيقة
    private static final long WARNING_THRESHOLD_MS = 5 * 60 * 1000; // تحذير قبل 5 دقائق
    private static final long EXPIRY_BUFFER_MS = 30 * 1000; // هامش أمان 30 ثانية
    
    private static SessionMonitor instance;
    
    private final SessionManager sessionManager;
    private final Handler mainHandler;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean isMonitoring = new AtomicBoolean(false);
    
    // المستمعون
    private SessionExpiryListener expiryListener;
    private SessionWarningListener warningListener;
    private SessionStateListener stateListener;
    
    // حالة التحذير
    private boolean warningSent = false;
    private boolean isLoggedIn = false;

    /**
     * واجهة الاستماع لانتهاء الجلسة
     */
    public interface SessionExpiryListener {
        void onSessionExpired(String username);
    }

    /**
     * واجهة الاستماع للتحذير قبل الانتهاء
     */
    public interface SessionWarningListener {
        void onSessionWarning(long timeRemainingMs);
    }

    /**
     * واجهة الاستماع لتغيير الحالة
     */
    public interface SessionStateListener {
        void onSessionStateChanged(boolean isLoggedIn);
    }

    private SessionMonitor(Context context) {
        this.sessionManager = new SessionManager(context.getApplicationContext());
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SessionMonitor");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * الحصول على النسخة الوحيدة (Singleton)
     */
    public static synchronized SessionMonitor getInstance(Context context) {
        if (instance == null) {
            instance = new SessionMonitor(context);
        }
        return instance;
    }

    /**
     * بدء المراقبة
     */
    public void startMonitoring() {
        if (isMonitoring.getAndSet(true)) {
            Log.d(TAG, "Monitoring already started");
            return;
        }
        
        // تحديث الحالة الأولية
        isLoggedIn = sessionManager.isLoggedIn();
        
        scheduler.scheduleAtFixedRate(this::checkSession, 
                0, CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
        
        Log.d(TAG, "Session monitoring started");
    }

    /**
     * إيقاف المراقبة
     */
    public void stopMonitoring() {
        if (!isMonitoring.getAndSet(false)) {
            return;
        }
        
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        Log.d(TAG, "Session monitoring stopped");
    }

    /**
     * فحص الجلسة
     */
    private void checkSession() {
        try {
            boolean currentLoggedIn = sessionManager.isLoggedIn();
            
            // تغيير الحالة
            if (currentLoggedIn != isLoggedIn) {
                isLoggedIn = currentLoggedIn;
                notifyStateChanged(isLoggedIn);
                warningSent = false;
            }
            
            if (!isLoggedIn) {
                return;
            }
            
            // فحص صلاحية التوكن
            if (!sessionManager.hasValidToken()) {
                handleSessionExpired();
                return;
            }
            
            // فحص الحاجة للتحذير
            if (sessionManager.needsTokenRefresh() && !warningSent) {
                long remainingTime = getRemainingTime();
                if (remainingTime > 0 && remainingTime <= WARNING_THRESHOLD_MS) {
                    warningSent = true;
                    notifyWarning(remainingTime);
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error checking session", e);
        }
    }

    /**
     * معالجة انتهاء الجلسة
     */
    private void handleSessionExpired() {
        String username = sessionManager.getUsername();
        
        Log.w(TAG, "Session expired for user: " + username);
        
        // تسجيل الخروج
        sessionManager.logoutUser();
        isLoggedIn = false;
        warningSent = false;
        
        // إشعار المستمعين
        notifyExpired(username);
        notifyStateChanged(false);
    }

    /**
     * الحصول على الوقت المتبقي
     */
    public long getRemainingTime() {
        // يمكن تحسين هذا ليعيد الوقت الفعلي من التوكن
        return WARNING_THRESHOLD_MS;
    }

    /**
     * فحص فوري (للاستدعاء اليدوي)
     */
    public void checkNow() {
        checkSession();
    }

    /**
     * إعادة تعيين حالة التحذير
     */
    public void resetWarning() {
        warningSent = false;
    }

    // ==================== المستمعون ====================

    public void setSessionExpiryListener(SessionExpiryListener listener) {
        this.expiryListener = listener;
    }

    public void setSessionWarningListener(SessionWarningListener listener) {
        this.warningListener = listener;
    }

    public void setSessionStateListener(SessionStateListener listener) {
        this.stateListener = listener;
    }

    private void notifyExpired(String username) {
        mainHandler.post(() -> {
            if (expiryListener != null) {
                expiryListener.onSessionExpired(username);
            }
        });
    }

    private void notifyWarning(long timeRemaining) {
        mainHandler.post(() -> {
            if (warningListener != null) {
                warningListener.onSessionWarning(timeRemaining);
            }
        });
    }

    private void notifyStateChanged(boolean loggedIn) {
        mainHandler.post(() -> {
            if (stateListener != null) {
                stateListener.onSessionStateChanged(loggedIn);
            }
        });
    }

    /**
     * التحقق من حالة المراقبة
     */
    public boolean isMonitoring() {
        return isMonitoring.get();
    }
}
