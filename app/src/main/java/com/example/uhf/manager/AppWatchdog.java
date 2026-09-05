package com.example.uhf.manager;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class AppWatchdog {
    
    private static final String TAG = "AppWatchdog";
    private static final long TIMEOUT_MS = 5000; // 5 ثواني
    private static AppWatchdog instance;
    private boolean isMonitoring = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    private AppWatchdog() {}
    
    public static synchronized AppWatchdog getInstance() {
        if (instance == null) {
            instance = new AppWatchdog();
        }
        return instance;
    }
    
    public void startMonitoring(Runnable onFreezeDetected) {
        if (isMonitoring) return;
        isMonitoring = true;
        
        new Thread(() -> {
            while (isMonitoring) {
                final boolean[] isMainThreadResponsive = {false};
                
                // إرسال مهمة للـ Main Thread
                mainHandler.post(() -> isMainThreadResponsive[0] = true);
                
                try {
                    Thread.sleep(TIMEOUT_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                // إذا لم يستجب الـ Main Thread خلال المدة، فهو متجمد (ANR)
                if (!isMainThreadResponsive[0] && isMonitoring) {
                    Log.e(TAG, "Main Thread is FROZEN! Restarting...");
                    if (onFreezeDetected != null) {
                        onFreezeDetected.run();
                    }
                    isMonitoring = false;
                }
            }
        }).start();
    }
    
    public void stopMonitoring() {
        isMonitoring = false;
    }
}
