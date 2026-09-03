package com.example.uhf;

import android.app.Application;
import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import androidx.multidex.MultiDex;

import com.google.firebase.FirebaseApp;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

import com.example.uhf.tools.FileUtils;
import com.example.uhf.tools.ToastUtil;

/**
 * Global Application base class for managing application configuration
 */
public class AppContext extends Application implements Thread.UncaughtExceptionHandler {
    private static final String TAG = "AppContext";

    public static final String DEFAULT_PATH = "/sdcard/uhf/";

    protected static volatile AppContext mApp;

    public static AppContext getInstance() {
        return mApp;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mApp = this;
        
        initFirebase();
        Thread.setDefaultUncaughtExceptionHandler(this);
        ToastUtil.init(mApp);
        
        Log.d(TAG, "AppContext initialized successfully");
    }

    private void initFirebase() {
        try {
            FirebaseApp.initializeApp(this);
            if (BuildConfig.DEBUG) {
                FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(false);
            } else {
                FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true);
            }
        } catch (Exception e) {
            Log.e(TAG, "Firebase initialization failed: " + e.getMessage());
        }
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        Log.e(TAG, "Uncaught Exception in thread " + thread.getName(), throwable);
        
        try {
            FirebaseCrashlytics.getInstance().recordException(throwable);
            FileUtils.saveCrashFile(throwable);
            SystemClock.sleep(300);
        } catch (Exception e) {
            Log.e(TAG, "Error handling uncaught exception: " + e.getMessage());
        } finally {
            // إنهاء آمن وموثوق لعملية أندرويد بدون ترك أجزاء معلقة
            Process.killProcess(Process.myPid());
            System.exit(1);
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

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        System.gc();
        Log.w(TAG, "Low memory warning - GC requested");
    }
}
