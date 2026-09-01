package com.example.uhf.activity;

import android.Manifest;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;  // ✅ أضف هذا

import com.example.uhf.BuildConfig;
import com.example.uhf.R;
import com.example.uhf.fragment.KeyDwonFragment;
import com.example.uhf.tools.DeviceDetector;
import com.example.uhf.tools.UIHelper;

import com.example.uhf.tools.MemoryManager;
import com.rscja.deviceapi.RFIDWithUHFUART;
import com.rscja.utility.StringUtility;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class BaseTabFragmentActivity extends AppCompatActivity {

    private static final String TAG = "BaseTabFragmentActivity";
    
    public RFIDWithUHFUART mReader;
    public Fragment currentFragment = null;  // ✅ تغير من KeyDwonFragment إلى Fragment
    public int TidLen = 6;

    
    private static final int MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE = 1;
    private static final int RFID_INIT_TIMEOUT_MS = 10000; // 10 ثواني
    private int initRetryCount = 0;
    private final AtomicBoolean isDestroyed = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService executorService;
    
    private boolean isInitialized = false;
    private ProgressDialog initDialog;
    private long initStartTime = 0;

    private static final int[] SCAN_KEY_CODES = {
        139, 280, 291, 293, 294, 311, 312, 313, 315, 591, 593, 594, 596
    };

    // ==================== LIFECYCLE ====================
    
    @Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    
    // ✅ تسجيل النشاط في MemoryManager
    MemoryManager.getInstance().registerActivity(this);
    
    // ✅ تسجيل لاستقبال إشعارات الذاكرة
    registerComponentCallbacks(MemoryManager.getInstance());
        isDestroyed.set(false);
        
        // ✅ طلب إذن القراءة من التخزين الخارجي
        checkStoragePermission();
    }

    @Override
protected void onDestroy() {
    isDestroyed.set(true);
    
    dismissInitDialogSafely();
    shutdownExecutorSafely();
    freeReaderSafely();
    
    // ❌ احذف هذا السطر
    // mainHandler.removeCallbacksAndMessages(null);
    
    MemoryManager.getInstance().unregisterActivity(this);
    unregisterComponentCallbacks(MemoryManager.getInstance());
    MemoryManager.getInstance().forceCleanup();
    super.onDestroy();
}

    // ==================== PERMISSIONS ====================

    private void checkStoragePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE) {
            boolean granted = grantResults.length > 0 && 
                            grantResults[0] == PackageManager.PERMISSION_GRANTED;
            
            Log.i(TAG, granted ? 
                "✅ SD Card permission granted" : 
                "❌ SD Card permission denied");
            
            if (!granted) {
                // ✅ يمكن إضافة تنبيه للمستخدم هنا
                Log.w(TAG, "Storage permission denied - some features may not work");
            }
        }
    }

    // ==================== UHF INITIALIZATION ====================

    /**
     * ✅ تهيئة قارئ UHF بشكل آمن
     */
    public void initUHF() {
        if (isDestroyed.get()) {
            Log.w(TAG, "Activity destroyed - skipping UHF init");
            return;
        }
        
        try {
            mReader = RFIDWithUHFUART.getInstance();
        } catch (Exception ex) {
            Log.e(TAG, "Failed to get UHF instance", ex);
            toastMessageSafe("Failed to get UHF instance: " + ex.getMessage());
            return;
        }
        
        if (mReader != null) {
            initializeReaderAsync();
        }
    }

    /**
     * ✅ تهيئة القارئ في خيط منفصل مع التحقق من الحالة
     */
    private void initializeReaderAsync() {
        // ✅ عرض حوار التحميل
        showInitDialog();
        initStartTime = System.currentTimeMillis();
        
        // ✅ إنشاء ExecutorService إذا لم يكن موجوداً
        if (executorService == null || executorService.isShutdown()) {
            executorService = Executors.newSingleThreadExecutor();
        }
        
        executorService.execute(() -> {
            try {
                // ✅ تهيئة القارئ
                boolean result = mReader.init(BaseTabFragmentActivity.this);
                
                // ✅ التحقق من المهلة
                long elapsed = System.currentTimeMillis() - initStartTime;
                if (elapsed > RFID_INIT_TIMEOUT_MS) {
                    Log.w(TAG, "UHF init took too long: " + elapsed + "ms");
                }
                
                // ✅ العودة للخيط الرئيسي
                mainHandler.post(() -> handleInitResult(result));
                
            } catch (Exception e) {
                Log.e(TAG, "UHF init error", e);
                mainHandler.post(() -> handleInitResult(false));
            }
        });
    }

    /**
     * ✅ معالجة نتيجة التهيئة
     */
    private void handleInitResult(boolean result) {
    // ✅ إخفاء الحوار
    dismissInitDialogSafely();
    
    // ✅ التحقق من أن النشاط ما زال موجوداً
    if (isDestroyed.get() || isFinishing() || isDestroyed()) {
        Log.w(TAG, "Activity destroyed - ignoring init result");
        return;
    }
    
    if (!result) {
        isInitialized = false;
        DeviceDetector.reset();
        
        String errorMsg = "UHF Init Failed\nDevice: " + Build.MODEL;
        Log.e(TAG, errorMsg);
        toastMessageSafe(errorMsg);
        
        // ✅ إعادة المحاولة مرة واحدة فقط
        if (initRetryCount < 1) {
            initRetryCount++;
            mainHandler.postDelayed(() -> {
                if (!isDestroyed.get() && !isInitialized) {
                    Log.i(TAG, "Retrying UHF init (attempt " + initRetryCount + ")");
                    initUHF();
                }
            }, 3000);
        } else {
            Log.w(TAG, "UHF init failed permanently - user can retry manually");
            // ✅ إظهار رسالة نهائية
            toastMessageSafe("UHF init failed - Please check device");
        }
        
    } else {
        isInitialized = true;
        initRetryCount = 0; // ✅ إعادة تعيين العداد عند النجاح
        Log.i(TAG, "✅ UHF initialized successfully");
        configureReaderForDevice();
    }
}

    // ==================== PROGRESS DIALOG ====================

    /**
     * ✅ عرض حوار التحميل بأمان
     */
    private void showInitDialog() {
        if (isDestroyed.get() || isFinishing() || isDestroyed()) {
            return;
        }
        
        try {
            dismissInitDialogSafely();
            
            initDialog = new ProgressDialog(this);
            initDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
            initDialog.setMessage("Initializing UHF...");
            initDialog.setCanceledOnTouchOutside(false);
            initDialog.setCancelable(false);
            initDialog.show();
        } catch (Exception e) {
            Log.e(TAG, "Show dialog error: " + e.getMessage());
        }
    }

    /**
     * ✅ إخفاء حوار التحميل بأمان
     */
    private void dismissInitDialogSafely() {
        try {
            if (initDialog != null && initDialog.isShowing()) {
                initDialog.dismiss();
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Dismiss dialog error: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Dismiss dialog unexpected error: " + e.getMessage());
        } finally {
            initDialog = null;
        }
    }

    // ==================== EXECUTOR SERVICE ====================

    /**
     * ✅ إيقاف ExecutorService بأمان
     */
    private void shutdownExecutorSafely() {
        try {
            if (executorService != null && !executorService.isShutdown()) {
                executorService.shutdownNow();
            }
        } catch (Exception e) {
            Log.e(TAG, "Shutdown executor error: " + e.getMessage());
        } finally {
            executorService = null;
        }
    }

    // ==================== READER MANAGEMENT ====================

    /**
     * ✅ تحرير القارئ بأمان
     */
    private void freeReaderSafely() {
        try {
            if (mReader != null) {
                mReader.free();
            }
        } catch (Exception e) {
            Log.e(TAG, "Free reader error: " + e.getMessage());
        } finally {
            mReader = null;
        }
    }

    /**
     * ✅ تكوين القارئ حسب نوع الجهاز
     */
    public void configureReaderForDevice() {
        if (mReader == null || !isInitialized) {
            Log.w(TAG, "Cannot configure - reader not initialized");
            return;
        }
        
        if (isDestroyed.get() || isFinishing()) {
            return;
        }

        try {
            String model = Build.MODEL;
            DeviceDetector.DeviceModel deviceModel = DeviceDetector.getDeviceModel();
            
            Log.i(TAG, "===================================");
            Log.i(TAG, "Device Model: " + model);
            Log.i(TAG, "Detected as: " + deviceModel.name());
            
            // ✅ استخدام DeviceDetector.configure
            DeviceDetector.DeviceProfile profile = DeviceDetector.configure(mReader);
            
            if (profile != null && profile.ready) {
                Log.i(TAG, "Power set to: " + profile.appliedPower + " dBm (OK)");
                Log.i(TAG, "Frequency Range: " + profile.frequencyRange);
            } else {
                Log.w(TAG, "Profile not ready: " + DeviceDetector.getLastError());
            }
            
            Log.i(TAG, "===================================");
            
        } catch (Exception e) {
            Log.e(TAG, "Configuration error", e);
        }
    }

    /**
     * ✅ التحقق من جاهزية القارئ
     */
    public boolean isReaderReady() {
        return mReader != null && isInitialized && !isDestroyed.get();
    }

    // ==================== KEY HANDLING ====================

    private boolean isScanKey(int keyCode) {
        for (int code : SCAN_KEY_CODES) {
            if (code == keyCode) return true;
        }
        return false;
    }

   @Override
public boolean onKeyDown(int keyCode, KeyEvent event) {
    if (isScanKey(keyCode)) {
        if (event.getRepeatCount() == 0 && currentFragment != null) {
            // ✅ تحقق من النوع أولاً
            if (currentFragment instanceof KeyDwonFragment) {
                ((KeyDwonFragment) currentFragment).myOnKeyDwon();
            }
        }
        return true;
    }
    return super.onKeyDown(keyCode, event);
}
    // ==================== MENU ====================

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main, menu);
        
        if (BuildConfig.Test) {
            MenuItem speedItem = menu.findItem(R.id.speed);
            if (speedItem != null) {
                speedItem.setVisible(true);
            }
        }
        
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        
        if (id == android.R.id.home) {
            return false;
        }
        
        if (id == R.id.UHF_ver) {
            getUHFVersion();
            return true;
        }
        
        if (id == R.id.export) {
            exportData();
            return true;
        }
        
        if (id == R.id.speed) {
            startActivity(new Intent(this, TestActivity.class));
            return true;
        }
        
        return super.onOptionsItemSelected(item);
    }

    // ==================== UTILITY METHODS ====================

    /**
     * ✅ تصدير البيانات (يمكن تجاوزها في الفئات الفرعية)
     */
    public void exportData() {
        toastMessageSafe("Export");
    }

    /**
     * ✅ عرض رسالة Toast بأمان
     */
    public void toastMessage(String msg) {
        toastMessageSafe(msg);
    }

    /**
     * ✅ عرض Toast مع التحقق من الحالة
     */
    private void toastMessageSafe(String msg) {
        if (isDestroyed.get() || isFinishing()) {
            return;
        }
        
        mainHandler.post(() -> {
            if (!isDestroyed.get() && !isFinishing()) {
                Toast.makeText(BaseTabFragmentActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * ✅ التحقق من صحة الإدخال السداسي
     */
    public boolean vailHexInput(String str) {
        if (str == null || str.length() == 0) return false;
        if (str.length() % 2 == 0) return StringUtility.isHexNumberRex(str);
        return false;
    }

    /**
     * ✅ الحصول على إصدار UHF
     */
    public void getUHFVersion() {
        if (mReader == null || isDestroyed.get()) {
            toastMessageSafe("Reader not available");
            return;
        }
        
        try {
            String rfidVer = mReader.getVersion();
            String hardwareVersion = mReader.getHardwareVersion();
            String deviceInfo = DeviceDetector.getDeviceInfo();
            
            String version = "=== Device ===\n" + 
                           deviceInfo + 
                           "\n=== UHF ===\n" +
                           "Software: " + rfidVer + 
                           "\nHardware: " + hardwareVersion;
            
            UIHelper.alert(this, R.string.action_uhf_ver, version, R.drawable.webtext);
        } catch (Exception e) {
            Log.e(TAG, "Get UHF version error: " + e.getMessage());
            toastMessageSafe("Error getting UHF version");
        }
    }

    /**
     * ✅ الحصول على اسم الإصدار
     */
    public String getVerName() {
        try {
            return this.getPackageManager()
                      .getPackageInfo(this.getPackageName(), 0)
                      .versionName;
        } catch (Exception e) {
            Log.e(TAG, "Get version error: " + e.getMessage());
            return "";
        }
    }
}
