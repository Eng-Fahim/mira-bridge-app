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
    
    public static void init(Context context) {
        try {
            analytics = FirebaseAnalytics.getInstance(context.getApplicationContext());
            crashlytics = FirebaseCrashlytics.getInstance();
            crashlytics.setCrashlyticsCollectionEnabled(true);
            
            log("SYSTEM", "Firebase Diagnostics Initialized", "");
        } catch (Exception e) {
            Log.e(TAG, "Init error: " + e.getMessage(), e);
        }
    }
    
    public static void log(String event, String details) {
        log("INFO", event, details);
    }
    
    public static void log(String category, String event, String details) {
        try {
            String timestamp = timeFormat.format(new Date());
            String fullMessage = String.format("[%s] [%s] %s - %s", timestamp, category, event, details);
            
            // Logcat
            Log.d(TAG, fullMessage);
            
            // Crashlytics
            if (crashlytics != null) {
                crashlytics.log(fullMessage);
                crashlytics.setCustomKey("last_category", category);
                crashlytics.setCustomKey("last_event", event);
                crashlytics.setCustomKey("last_details", details != null ? details : "");
                crashlytics.setCustomKey("last_time", timestamp);
            }
            
            // Analytics
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
    
    public static void logError(String location, Throwable throwable) {
        try {
            Log.e(TAG, "ERROR at " + location + ": " + throwable.getMessage(), throwable);
            
            if (crashlytics != null) {
                crashlytics.setCustomKey("error_location", location);
                crashlytics.setCustomKey("error_message", throwable.getMessage() != null ? throwable.getMessage() : "Unknown");
                crashlytics.recordException(throwable);
            }
        } catch (Exception e) {
            Log.e(TAG, "LogError error: " + e.getMessage());
        }
    }
    
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
    
    public static void logSessionState(String state, String username, boolean isLoggedIn) {
        log("SESSION", "Session " + state, "user=" + (username != null ? username : "null") + ", loggedIn=" + isLoggedIn);
        
        if (crashlytics != null) {
            crashlytics.setCustomKey("session_state", state);
            crashlytics.setCustomKey("session_user", username != null ? username : "");
            crashlytics.setCustomKey("session_logged_in", isLoggedIn);
        }
    }
    
    public static void logManagerState(String managerName, String state) {
        log("MANAGER", managerName + " - " + state, "");
        
        if (crashlytics != null) {
            crashlytics.setCustomKey("manager_name", managerName);
            crashlytics.setCustomKey("manager_state", state);
        }
    }
}
