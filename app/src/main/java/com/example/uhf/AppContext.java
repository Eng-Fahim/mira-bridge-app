package com.example.uhf;

import android.app.Application;
import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import androidx.multidex.MultiDex;

// استيرادات Firebase
import com.google.firebase.FirebaseApp;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

import com.example.uhf.tools.FileUtils;
import com.example.uhf.tools.ToastUtil;

/**
 * 全局应用程序类：用于保存和调用全局应用配置
 * Global Application class for managing app configuration
 */
public class AppContext extends Application implements Thread.UncaughtExceptionHandler {
    private static final String TAG = "AppContext";

    public static final String DEFAULT_PATH = "/sdcard/uhf/";

    private static AppContext mApp;

    public static AppContext getInstance() {
        return mApp;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // دعم MultiDex للتطبيقات الكبيرة
        MultiDex.install(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mApp = this;
        
        // تهيئة Firebase
        initFirebase();

        Thread.setDefaultUncaughtExceptionHandler(this);

        ToastUtil.init(mApp);
        
        Log.d(TAG, "Application initialized successfully");
    }

    /**
     * تهيئة Firebase services
     */
    private void initFirebase() {
        try {
            FirebaseApp.initializeApp(this);
            
            // تعطيل Crashlytics في وضع التصحيح
            if (BuildConfig.DEBUG) {
                FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(false);
                Log.d(TAG, "Crashlytics disabled in debug mode");
            } else {
                FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true);
                Log.d(TAG, "Crashlytics enabled in release mode");
            }
        } catch (Exception e) {
            Log.e(TAG, "Firebase initialization failed: " + e.getMessage());
        }
    }

    @Override
    public synchronized void uncaughtException(Thread thread, Throwable throwable) {
        Log.e("FileUtils", "uncaughtException throwable:" + throwable);
        
        try {
            // إرسال الاستثناء إلى Firebase قبل الخروج
            FirebaseCrashlytics.getInstance().recordException(throwable);
            
            // حفظ ملف الكراش محلياً
            FileUtils.saveCrashFile(throwable);
            
            // انتظار قليل للتأكد من حفظ البيانات
            SystemClock.sleep(300);
        } catch (Exception e) {
            Log.e(TAG, "Error handling uncaught exception: " + e.getMessage());
        } finally {
            // إنهاء التطبيق
            System.exit(0);
        }
    }

    public String getVerName() {
        try {
            return this.getPackageManager().getPackageInfo(this.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        return "";
    }

    public int getVerCode() {
        try {
            return this.getPackageManager().getPackageInfo(this.getPackageName(), 0).versionCode;
        } catch (Exception ignored) {
        }
        return 0;
    }

    /**
     * تنظيف الموارد عند انخفاض الذاكرة
     */
    @Override
    public void onLowMemory() {
        super.onLowMemory();
        // تنظيف الذاكرة المؤقتة
        System.gc();
        Log.w(TAG, "Low memory warning - cache cleared");
    }
}
