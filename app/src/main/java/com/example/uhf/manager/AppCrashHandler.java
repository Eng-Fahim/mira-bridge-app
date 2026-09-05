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

public class AppCrashHandler implements Thread.UncaughtExceptionHandler {
    
    private static final String TAG = "AppCrashHandler";
    private final Thread.UncaughtExceptionHandler defaultHandler;
    private final Context context;
    
    public AppCrashHandler(Context context) {
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        this.context = context.getApplicationContext();
    }
    
    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        Log.e(TAG, "Uncaught exception detected!", throwable);
        
        try {
            // حفظ معلومات الخطأ
            saveCrashLog(throwable);
            
            // إعادة تشغيل التطبيق
            restartApplication();
            
        } catch (Exception e) {
            Log.e(TAG, "Error handling crash: " + e.getMessage());
            // استخدام المعالج الافتراضي
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        }
    }
    
    private void saveCrashLog(Throwable throwable) {
        try {
            StringBuilder logBuilder = new StringBuilder();
            logBuilder.append("=== Crash Report ===\n");
            logBuilder.append("Time: ").append(System.currentTimeMillis()).append("\n");
            logBuilder.append("Thread: ").append(Thread.currentThread().getName()).append("\n");
            logBuilder.append("Error: ").append(throwable.toString()).append("\n\n");
            
            for (StackTraceElement element : throwable.getStackTrace()) {
                logBuilder.append("    at ").append(element.toString()).append("\n");
            }
            
            Log.e(TAG, logBuilder.toString());
            
            // يمكنك حفظ السجل في ملف هنا
            // saveToFile(logBuilder.toString());
            
        } catch (Exception e) {
            Log.e(TAG, "Save crash log error: " + e.getMessage());
        }
    }
    
    private void restartApplication() {
        try {
            Intent restartIntent = new Intent(context, SplashActivity.class);
            restartIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            restartIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            restartIntent.putExtra("is_restart", true);
            restartIntent.putExtra("crash_detected", true);
            
            PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                8888,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE
            );
            
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC,
                        System.currentTimeMillis() + 1000,
                        pendingIntent
                    );
                } else {
                    alarmManager.set(
                        AlarmManager.RTC,
                        System.currentTimeMillis() + 1000,
                        pendingIntent
                    );
                }
            }
            
            Process.killProcess(Process.myPid());
            System.exit(1);
            
        } catch (Exception e) {
            Log.e(TAG, "Restart application error: " + e.getMessage());
        }
    }
}
