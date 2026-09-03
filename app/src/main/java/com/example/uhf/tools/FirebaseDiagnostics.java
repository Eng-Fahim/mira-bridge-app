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
    private static FirebaseAnalytics analytics;
    private static FirebaseCrashlytics crashlytics;
    private static final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static boolean isInitialized = false;
    
    /**
     * تهيئة Firebase Diagnostics
     */
    public static void init(Context context) {
        try {
            if (isInitialized) return;
            
            analytics = FirebaseAnalytics.getInstance(context.getApplicationContext());
            crashlytics = FirebaseCrashlytics.getInstance();
            crashlytics.setCrashlyticsCollectionEnabled(true);
            
            isInitialized = true;
            log("SYSTEM", "Firebase Diagnostics Initialized", "");
        } catch (Exception e) {
            Log.e(TAG, "Init error: " + e.getMessage(), e);
        }
    }
    
    /**
     * تسجيل حدث بسيط
     */
    public static void log(String event, String details) {
        log("INFO", event, details);
    }
    
    /**
     * تسجيل حدث مفصل
     */
    public static void log(String category, String event, String details) {
        try {
            String timestamp = timeFormat.format(new Date());
            String fullMessage = String.format("[%s] [%s] %s - %s", timestamp, category, event, details);
            
            // ✅ Logcat
            Log.d(TAG, fullMessage);
            
            // ✅ Crashlytics
            if (crashlytics != null) {
                crashlytics.log(fullMessage);
                crashlytics.setCustomKey("last_category", category);
                crashlytics.setCustomKey("last_event", event);
                crashlytics.setCustomKey("last_details", details != null ? details : "");
                crashlytics.setCustomKey("last_time", timestamp);
            }
            
            // ✅ Analytics
            if (analytics != null) {
                Bundle bundle = new Bundle();
                bundle.putString("category", category);
                bundle.putString("event_name", event);
                bundle.putString("event_details", details != null ? details : "");
                bundle.putString("timestamp", timestamp);
                bundle.putLong("timestamp_ms", System.currentTimeMillis());
                analytics.logEvent("app_diagnostic", bundle);
            }
        } catch (Exception e) {
            Log.e(TAG, "Log error: " + e.getMessage());
        }
    }
    
    /**
     * تسجيل خطأ
     */
    public static void logError(String location, Throwable throwable) {
        try {
            Log.e(TAG, "ERROR at " + location + ": " + throwable.getMessage(), throwable);
            
            if (crashlytics != null) {
                crashlytics.setCustomKey("error_location", location);
                crashlytics.setCustomKey("error_message", throwable.getMessage() != null ? throwable.getMessage() : "Unknown");
                crashlytics.setCustomKey("error_class", throwable.getClass().getSimpleName());
                crashlytics.setCustomKey("error_time", timeFormat.format(new Date()));
                crashlytics.recordException(throwable);
            }
        } catch (Exception e) {
            Log.e(TAG, "LogError error: " + e.getMessage());
        }
    }
    
    /**
     * تسجيل استدعاء API
     */
    public static void logApiCall(String endpoint, int code, boolean success, String message) {
        String status = success ? "SUCCESS" : "FAILED";
        log("API", endpoint + " - " + status, "code=" + code + ", msg=" + (message != null ? message : "null"));
        
        if (crashlytics != null) {
            crashlytics.setCustomKey("api_endpoint", endpoint);
            crashlytics.setCustomKey("api_code", code);
            crashlytics.setCustomKey("api_success", success);
            crashlytics.setCustomKey("api_message", message != null ? message : "");
        }
    }
    
    /**
     * تسجيل حالة الجلسة
     */
    public static void logSessionState(String state, String username, boolean isLoggedIn) {
        log("SESSION", "Session " + state, "user=" + (username != null ? username : "null") + ", loggedIn=" + isLoggedIn);
        
        if (crashlytics != null) {
            crashlytics.setCustomKey("session_state", state);
            crashlytics.setCustomKey("session_user", username != null ? username : "");
            crashlytics.setCustomKey("session_logged_in", isLoggedIn);
        }
    }
    
    /**
     * تسجيل حالة الـ Managers
     */
    public static void logManagerState(String managerName, String state) {
        log("MANAGER", managerName + " - " + state, "");
        
        if (crashlytics != null) {
            crashlytics.setCustomKey("manager_name", managerName);
            crashlytics.setCustomKey("manager_state", state);
        }
    }
    
    /**
     * تسجيل حالة البيانات
     */
    public static void logDataState(String dataType, String state, int count) {
        log("DATA", dataType + " - " + state, "count=" + count);
        
        if (crashlytics != null) {
            crashlytics.setCustomKey("data_type", dataType);
            crashlytics.setCustomKey("data_state", state);
            crashlytics.setCustomKey("data_count", count);
        }
    }
    
    /**
     * تسجيل حالة الذاكرة
     */
    public static void logMemoryState(String location) {
        try {
            Runtime runtime = Runtime.getRuntime();
            long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
            long maxMemory = runtime.maxMemory() / (1024 * 1024);
            
            log("MEMORY", "Memory at " + location, "used=" + usedMemory + "MB, max=" + maxMemory + "MB");
        } catch (Exception e) {
            Log.e(TAG, "LogMemory error: " + e.getMessage());
        }
    }
}
