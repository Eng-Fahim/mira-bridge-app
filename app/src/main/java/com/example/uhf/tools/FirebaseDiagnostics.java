package com.example.uhf.tools;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FirebaseDiagnostics {
    private static final String TAG = "FirebaseDiagnostics";
    
    // ✅ التحكم في التسجيل
    private static final boolean ENABLE_CRASHLYTICS_LOGGING = false;  // ❌ معطل
    private static final boolean ENABLE_ANALYTICS_LOGGING = false;    // ❌ معطل
    private static final boolean ENABLE_LOGCAT = true;                // ✅ مفعل
    
    private static FirebaseAnalytics analytics;
    private static FirebaseCrashlytics crashlytics;
    private static final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static boolean isInitialized = false;
    
    public static void init(Context context) {
        try {
            if (isInitialized) return;
            
            // ✅ تهيئة فقط إذا كانت مفعلة
            if (ENABLE_ANALYTICS_LOGGING) {
                analytics = FirebaseAnalytics.getInstance(context.getApplicationContext());
            }
            if (ENABLE_CRASHLYTICS_LOGGING) {
                crashlytics = FirebaseCrashlytics.getInstance();
                crashlytics.setCrashlyticsCollectionEnabled(true);
            }
            
            isInitialized = true;
            
            if (ENABLE_LOGCAT) {
                Log.d(TAG, "Firebase Diagnostics Initialized (Logcat only)");
            }
        } catch (Exception e) {
            Log.e(TAG, "Init error: " + e.getMessage(), e);
        }
    }
    
    // ✅ دالة مبسطة - تستدعي الدالة الرئيسية
    public static void log(String event, String details) {
        log("INFO", event, details);
    }
    
    // ✅ الدالة الرئيسية
    public static void log(String category, String event, String details) {
        try {
            if (!ENABLE_LOGCAT) return;
            
            String timestamp = timeFormat.format(new Date());
            String fullMessage = String.format("[%s] [%s] %s - %s", timestamp, category, event, details);
            
            // ✅ Logcat فقط
            Log.d(TAG, fullMessage);
            
        } catch (Exception e) {
            // تجاهل
        }
    }
    
    public static void logError(String location, Throwable throwable) {
        try {
            Log.e(TAG, "ERROR at " + location + ": " + throwable.getMessage(), throwable);
            
            if (crashlytics != null && ENABLE_CRASHLYTICS_LOGGING) {
                crashlytics.recordException(throwable);
            }
        } catch (Exception e) {
            // تجاهل
        }
    }
    
    public static void logApiCall(String endpoint, int code, boolean success, String message) {
        if (!ENABLE_LOGCAT) return;
        String status = success ? "SUCCESS" : "FAILED";
        Log.d(TAG, "API: " + endpoint + " - " + status + " code=" + code);
    }
    
    public static void logSessionState(String state, String username, boolean isLoggedIn) {
        if (!ENABLE_LOGCAT) return;
        Log.d(TAG, "SESSION: " + state + " user=" + username);
    }
    
    public static void logManagerState(String managerName, String state) {
        if (!ENABLE_LOGCAT) return;
        Log.d(TAG, "MANAGER: " + managerName + " - " + state);
    }
    
    public static void logDataState(String dataType, String state, int count) {
        if (!ENABLE_LOGCAT) return;
        Log.d(TAG, "DATA: " + dataType + " - " + state + " count=" + count);
    }
    
    public static void logMemoryState(String location) {
        if (!ENABLE_LOGCAT) return;
        try {
            Runtime runtime = Runtime.getRuntime();
            long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
            Log.d(TAG, "MEMORY: " + location + " used=" + usedMemory + "MB");
        } catch (Exception e) {
            // تجاهل
        }
    }
}
