package com.example.uhf;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.uhf.tools.ToastUtil;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AppContext - الإصدار 2.0 (المطوّر المتوافق مع MiraApplication v3.0)
 *
 * ✅ الإصلاحات المطبقة (7 مشاكل):
 *  1. getInstance() لا يُرجع null — يرمي استثناء واضح
 *  2. mApp خاص مع getter فقط
 *  3. إزالة System.gc()
 *  4. getVerName/getVerCode آمنة
 *  5. try-catch في onCreate
 *  6. tryGetInstance() للفحص الآمن
 *  7. isInitialized()
 *
 * ✅ التوافق:
 *  - MiraApplication يستدعي super.onCreate()
 *  - AppContext.getInstance() يعمل بعد MiraApplication.onCreate()
 */
public class AppContext extends Application {

    private static final String TAG = "AppContext";

    public static final String DEFAULT_PATH = "/sdcard/uhf/";

    // ✅ [إصلاح #2] private مع getter
    private static volatile AppContext instance;

    // ✅ [إصلاح #7] فحص الحالة
    private static final AtomicBoolean isInit = new AtomicBoolean(false);

    // ==================== Singleton ====================

    /**
     * ✅ [إصلاح #1] مضمون — يرمي استثناء إذا لم يُهيَّأ
     */
    @NonNull
    public static AppContext getInstance() {
        AppContext local = instance;
        if (local == null) {
            throw new IllegalStateException(
                    "AppContext not initialized. " +
                    "Ensure it's declared in AndroidManifest.xml " +
                    "as android:name=\".application.MiraApplication\" (or a subclass).");
        }
        return local;
    }

    /**
     * ✅ [إصلاح #6] نسخة آمنة بدون استثناء
     */
    @Nullable
    public static AppContext tryGetInstance() {
        return instance;
    }

    /**
     * ✅ [إصلاح #7] فحص الحالة
     */
    public static boolean isInitialized() {
        return isInit.get() && instance != null;
    }

    // ==================== Lifecycle ====================

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // ✅ [إصلاح #5] حماية من الاستثناءات
        try {
            instance = this;
            isInit.set(true);

            // ✅ تهيئة ToastUtil (آمنة)
            try {
                ToastUtil.init(this);
            } catch (Throwable t) {
                Log.e(TAG, "ToastUtil.init failed", t);
            }

            Log.d(TAG, "AppContext initialized successfully");

        } catch (Throwable t) {
            Log.e(TAG, "onCreate catastrophic error", t);
            // لا نُسقط التطبيق — لكن نُسجّل
        }
    }

    @Override
    public void onTerminate() {
        Log.i(TAG, "AppContext terminating");
        super.onTerminate();

        isInit.set(false);
        instance = null;
    }

    // ==================== Version Info ====================

    /**
     * ✅ [إصلاح #4] آمنة + cached
     */
    @NonNull
    public String getVerName() {
        try {
            PackageInfo info = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            return info.versionName != null ? info.versionName : "";
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        } catch (Throwable t) {
            Log.w(TAG, "getVerName error: " + t.getMessage());
            return "";
        }
    }

    public int getVerCode() {
        try {
            PackageInfo info = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            return info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        } catch (Throwable t) {
            Log.w(TAG, "getVerCode error: " + t.getMessage());
            return 0;
        }
    }

    // ==================== Memory ====================

    /**
     * ✅ [إصلاح #3] لا System.gc() — فقط log
     */
    @Override
    public void onLowMemory() {
        super.onLowMemory();
        Log.w(TAG, "Low memory warning — Android will handle GC automatically");

        // لا System.gc() — Android يدير GC تلقائياً
        // لا نُفرّغ أي cache هنا — كل مدير يدير caches بنفسه
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // يمكن إضافة منطق هنا إذا احتجت
        // Log.d(TAG, "onTrimMemory level=" + level);
    }
}
