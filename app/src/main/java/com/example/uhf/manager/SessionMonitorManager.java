package com.example.uhf.manager;

import android.app.Activity;
import android.content.Context;
import android.widget.Toast;

import com.example.uhf.activity.LoginActivity;
import com.example.uhf.tools.SessionMonitor;

/**
 * SessionMonitorManager - مدير مراقبة الجلسة
 * 
 * يربط SessionMonitor مع الواجهة
 */
public class SessionMonitorManager {

    private static SessionMonitorManager instance;
    private SessionMonitor sessionMonitor;
    private Context appContext;

    private SessionMonitorManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public static synchronized SessionMonitorManager getInstance(Context context) {
        if (instance == null) {
            instance = new SessionMonitorManager(context);
        }
        return instance;
    }

    /**
     * بدء المراقبة مع المستمعين الافتراضيين
     */
    public void startMonitoring(Activity currentActivity) {
        if (sessionMonitor == null) {
            sessionMonitor = SessionMonitor.getInstance(appContext);
        }

        // مستمع انتهاء الجلسة
        sessionMonitor.setSessionExpiryListener(username -> {
            // الانتقال لشاشة الدخول
            if (currentActivity != null && !currentActivity.isFinishing()) {
                Toast.makeText(currentActivity, 
                    "انتهت صلاحية الجلسة - سجل الدخول مجدداً", 
                    Toast.LENGTH_LONG).show();
                
                android.content.Intent intent = new android.content.Intent(
                    currentActivity, LoginActivity.class);
                intent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | 
                               android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
                currentActivity.startActivity(intent);
                currentActivity.finish();
            }
        });

        // مستمع التحذير
        sessionMonitor.setSessionWarningListener(timeRemaining -> {
            if (currentActivity != null && !currentActivity.isFinishing()) {
                long minutes = timeRemaining / (60 * 1000);
                Toast.makeText(currentActivity, 
                    "⚠️ ستنتهي الجلسة خلال " + minutes + " دقيقة", 
                    Toast.LENGTH_SHORT).show();
            }
        });

        // مستمع تغيير الحالة
        sessionMonitor.setSessionStateListener(isLoggedIn -> {
            if (!isLoggedIn && currentActivity != null && !currentActivity.isFinishing()) {
                // يمكن إضافة منطق إضافي هنا
            }
        });

        sessionMonitor.startMonitoring();
    }

    /**
     * إيقاف المراقبة
     */
    public void stopMonitoring() {
        if (sessionMonitor != null) {
            sessionMonitor.stopMonitoring();
        }
    }

    /**
     * فحص فوري
     */
    public void checkNow() {
        if (sessionMonitor != null) {
            sessionMonitor.checkNow();
        }
    }
}
