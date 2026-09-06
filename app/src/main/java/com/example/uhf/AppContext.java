package com.example.uhf;

import android.app.Application;
import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;



import com.example.uhf.tools.FileUtils;
import com.example.uhf.tools.ToastUtil;

/**
 * Global Application base class for managing application configuration
 */
public class AppContext extends Application {
    private static final String TAG = "AppContext";

    public static final String DEFAULT_PATH = "/sdcard/uhf/";

    protected static volatile AppContext mApp;

    public static AppContext getInstance() {
        return mApp;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mApp = this;
        
        // ❌ تم إزالة استدعاء initFirebase()
        ToastUtil.init(mApp);
        
        Log.d(TAG, "AppContext initialized successfully");
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
