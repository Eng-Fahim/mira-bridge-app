package com.example.uhf.manager;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import com.example.uhf.activity.SplashActivity;
import com.example.uhf.application.MiraApplication;

public class AppRestartManager {
    
    private static final String TAG = "AppRestartManager";
    private static final int RESTART_DELAY_MS = 500; // نصف ثانية تأخير
    
    /**
     * إعادة تشغيل التطبيق بشكل كامل
     */
    public static void restartApp(Context context) {
        restartApp(context, RESTART_DELAY_MS);
    }
    
    /**
     * إعادة تشغيل التطبيق مع تأخير محدد
     */
    public static void restartApp(Context context, int delayMs) {
        try {
            Log.i(TAG, "Restarting app...");
            
            // تنظيف أي موارد قبل إعادة التشغيل
            cleanupBeforeRestart(context);
            
            // إنشاء Intent لإعادة التشغيل
            Intent restartIntent = new Intent(context, SplashActivity.class);
            restartIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            restartIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            restartIntent.putExtra("is_restart", true);
            
            PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                9999,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE
            );
            
            // جدولة إعادة التشغيل
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null) {
                long triggerTime = System.currentTimeMillis() + delayMs;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC,
                        triggerTime,
                        pendingIntent
                    );
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    alarmManager.setExact(
                        AlarmManager.RTC,
                        triggerTime,
                        pendingIntent
                    );
                } else {
                    alarmManager.set(
                        AlarmManager.RTC,
                        triggerTime,
                        pendingIntent
                    );
                }
            }
            
            // إنهاء العملية الحالية
            terminateCurrentProcess(context);
            
        } catch (Exception e) {
            Log.e(TAG, "Restart app error: " + e.getMessage(), e);
            // محاولة بديلة
            fallbackRestart(context);
        }
    }
    
    /**
     * تنظيف الموارد قبل إعادة التشغيل
     */
    private static void cleanupBeforeRestart(Context context) {
        try {
            // تنظيف أي موارد مشتركة
            if (context instanceof Activity) {
                Activity activity = (Activity) context;
                if (!activity.isFinishing()) {
                    activity.finishAffinity();
                }
            }
            
            // تنظيف MiraApplication إذا وجدت
            if (context.getApplicationContext() instanceof MiraApplication) {
                MiraApplication app = (MiraApplication) context.getApplicationContext();
                app.cleanupOnRestart();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Cleanup error: " + e.getMessage());
        }
    }
    
    /**
     * إنهاء العملية الحالية
     */
    private static void terminateCurrentProcess(Context context) {
        try {
            // إغلاق جميع الأنشطة
            if (context instanceof Activity) {
                ((Activity) context).finishAffinity();
            }
            
            // إنهاء العملية
            new android.os.Handler().postDelayed(() -> {
                Process.killProcess(Process.myPid());
                System.exit(0);
            }, 200);
            
        } catch (Exception e) {
            Log.e(TAG, "Terminate process error: " + e.getMessage());
            Process.killProcess(Process.myPid());
            System.exit(0);
        }
    }
    
    /**
     * خطة بديلة إذا فشلت الطريقة الأساسية
     */
    private static void fallbackRestart(Context context) {
        try {
            Intent intent = new Intent(context, SplashActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            intent.putExtra("is_restart", true);
            context.startActivity(intent);
            
            if (context instanceof Activity) {
                ((Activity) context).finish();
            }
            
            Process.killProcess(Process.myPid());
        } catch (Exception e) {
            Log.e(TAG, "Fallback restart error: " + e.getMessage());
        }
    }
}
