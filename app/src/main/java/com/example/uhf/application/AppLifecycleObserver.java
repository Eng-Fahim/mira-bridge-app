package com.example.uhf.application;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.example.uhf.tools.SessionManager;

/**
 * ✅ AppLifecycleObserver
 * 
 * يراقب دورة حياة التطبيق كاملاً (Process Lifecycle):
 * - onStop  → التطبيق دخل في الخلفية → احفظ وقت الخروج
 * - onStart → التطبيق عاد للمقدمة → ستتحقق الأنشطة من Auto-Lock
 * 
 * ✅ يستخدم ProcessLifecycleOwner من androidx.lifecycle
 *    (ليس Activity.onPause/onStop — لأنها تُستدعى عند التنقل بين الأنشطة)
 */
public class AppLifecycleObserver implements DefaultLifecycleObserver {

    private static final String TAG = "AppLifecycleObserver";

    private final SessionManager sessionManager;
    private volatile boolean wasInBackground = false;

    public AppLifecycleObserver(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        Log.d(TAG, "📱 App → Foreground");
        wasInBackground = false;

        // ✅ لا نتصرف هنا — الأنشطة ستتحقق في onResume
        //    فقط نسجل الحدث للـ debugging
    }

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        Log.d(TAG, "📱 App → Background");
        wasInBackground = true;

        // ✅ حفظ وقت الخروج للتطبيق كاملاً
        if (sessionManager != null) {
            try {
                sessionManager.setLastAppActiveTime(System.currentTimeMillis());
            } catch (Exception e) {
                Log.e(TAG, "onStop error: " + e.getMessage());
            }
        }
    }

    @Override
    public void onResume(@NonNull LifecycleOwner owner) {
        // لا شيء
    }

    @Override
    public void onPause(@NonNull LifecycleOwner owner) {
        // لا شيء
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        Log.d(TAG, "📱 App → Destroyed");
    }

    public boolean wasInBackground() {
        return wasInBackground;
    }
}
