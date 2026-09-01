package com.example.uhf.tools;

import android.app.Activity;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * MemoryManager - إدارة الذاكرة ومنع التسرب
 * 
 * المميزات:
 * - تنظيف الموارد عند انخفاض الذاكرة
 * - تتبع الأنشطة النشطة
 * - تنظيف الكائنات غير المستخدمة
 * - مراقبة استخدام الذاكرة
 */
public class MemoryManager implements ComponentCallbacks2 {

    private static final String TAG = "MemoryManager";
    
    private static MemoryManager instance;
    
    private final List<WeakReference<Activity>> activeActivities = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private MemoryListener memoryListener;
    
    /**
     * مستمع لانخفاض الذاكرة
     */
    public interface MemoryListener {
        void onLowMemory(int level);
        void onCriticalMemory();
    }

    private MemoryManager() {
    }

    public static synchronized MemoryManager getInstance() {
        if (instance == null) {
            instance = new MemoryManager();
        }
        return instance;
    }

    /**
     * تسجيل نشاط جديد
     */
    public void registerActivity(Activity activity) {
        activeActivities.add(new WeakReference<>(activity));
        Log.d(TAG, "Activity registered: " + activity.getClass().getSimpleName());
    }

    /**
     * إلغاء تسجيل نشاط
     */
    public void unregisterActivity(Activity activity) {
        for (int i = activeActivities.size() - 1; i >= 0; i--) {
            Activity a = activeActivities.get(i).get();
            if (a == null || a == activity) {
                activeActivities.remove(i);
            }
        }
        Log.d(TAG, "Activity unregistered: " + activity.getClass().getSimpleName());
    }

    /**
     * تنظيف جميع المراجع الضعيفة المنتهية
     */
    public void cleanupDeadReferences() {
        for (int i = activeActivities.size() - 1; i >= 0; i--) {
            if (activeActivities.get(i).get() == null) {
                activeActivities.remove(i);
            }
        }
    }

    /**
     * الحصول على عدد الأنشطة النشطة
     */
    public int getActiveActivityCount() {
        cleanupDeadReferences();
        return activeActivities.size();
    }

    /**
     * تنظيف شامل للذاكرة
     */
    public void forceCleanup() {
        Log.d(TAG, "Forcing memory cleanup");
        
        // تنظيف المراجع الميتة
        cleanupDeadReferences();
        
        // طلب جمع القمامة
        Runtime.getRuntime().gc();
        
        // طلب جمع نهائي
        Runtime.getRuntime().runFinalization();
        
        // طلب جمع مرة أخرى
        Runtime.getRuntime().gc();
    }

    /**
     * الحصول على معلومات الذاكرة
     */
    public String getMemoryInfo() {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        
        return String.format(
            "Used: %.2f MB, Free: %.2f MB, Total: %.2f MB, Max: %.2f MB (%.0f%%)",
            usedMemory / (1024.0 * 1024.0),
            runtime.freeMemory() / (1024.0 * 1024.0),
            totalMemory / (1024.0 * 1024.0),
            maxMemory / (1024.0 * 1024.0),
            (usedMemory * 100.0) / maxMemory
        );
    }

    /**
     * التحقق من اقتراب نفاد الذاكرة
     */
    public boolean isMemoryLow() {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        return usedMemory > maxMemory * 0.8; // 80% من الذاكرة القصوى
    }

    /**
     * جدولة تنظيف دوري
     */
    public void schedulePeriodicCleanup(long intervalMs) {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                cleanupDeadReferences();
                if (isMemoryLow()) {
                    forceCleanup();
                }
                mainHandler.postDelayed(this, intervalMs);
            }
        }, intervalMs);
    }

    /**
     * تعيين مستمع لانخفاض الذاكرة
     */
    public void setMemoryListener(MemoryListener listener) {
        this.memoryListener = listener;
    }

    // ==================== ComponentCallbacks2 ====================

    @Override
    public void onTrimMemory(int level) {
        Log.d(TAG, "onTrimMemory: " + level);
        
        switch (level) {
            case TRIM_MEMORY_UI_HIDDEN:
                // الواجهة مخفية - تنظيف خفيف
                cleanupDeadReferences();
                break;
                
            case TRIM_MEMORY_RUNNING_MODERATE:
            case TRIM_MEMORY_BACKGROUND:
                // تنظيف متوسط
                cleanupDeadReferences();
                Runtime.getRuntime().gc();
                break;
                
            case TRIM_MEMORY_RUNNING_LOW:
            case TRIM_MEMORY_MODERATE:
                // تنظيف قوي
                forceCleanup();
                if (memoryListener != null) {
                    memoryListener.onLowMemory(level);
                }
                break;
                
            case TRIM_MEMORY_RUNNING_CRITICAL:
            case TRIM_MEMORY_COMPLETE:
                // حالة حرجة
                forceCleanup();
                if (memoryListener != null) {
                    memoryListener.onCriticalMemory();
                }
                break;
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        // لا شيء مطلوب
    }

    @Override
    public void onLowMemory() {
        Log.w(TAG, "onLowMemory called by system!");
        forceCleanup();
        if (memoryListener != null) {
            memoryListener.onCriticalMemory();
        }
    }
}
