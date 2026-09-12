package com.example.uhf.manager;

import android.util.Log;

/**
 * ⚠️ AppWatchdog - تم تعطيله
 * 
 * السبب: كان يُسبب restartApp خاطئ أثناء التنقلات
 * بسبب حجب UI thread عند replace().
 * 
 * ✅ بديل أفضل: Trust Android's ANR system.
 */
public class AppWatchdog {
    
    private static final String TAG = "AppWatchdog";
    private static AppWatchdog instance;
    private boolean isMonitoring = false;
    
    private AppWatchdog() {}
    
    public static synchronized AppWatchdog getInstance() {
        if (instance == null) {
            instance = new AppWatchdog();
        }
        return instance;
    }
    
    public void startMonitoring(Runnable onFreezeDetected) {
        // ✅ DISABLED — كان يُسبب restartApp خاطئ
        Log.i(TAG, "AppWatchdog.startMonitoring called (DISABLED)");
        isMonitoring = false;
    }
    
    public void stopMonitoring() {
        // ✅ DISABLED
        isMonitoring = false;
    }
}
