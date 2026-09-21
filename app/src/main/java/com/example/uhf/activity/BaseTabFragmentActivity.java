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
import androidx.fragment.app.Fragment;

import com.example.uhf.BuildConfig;
import com.example.uhf.R;
import com.example.uhf.fragment.KeyDwonFragment;
import com.example.uhf.tools.DeviceDetector;
import com.example.uhf.tools.UIHelper;
import com.example.uhf.tools.MemoryManager;
import com.example.uhf.utils.MiraDialogHelper;
import com.rscja.deviceapi.RFIDWithUHFUART;
import com.rscja.utility.StringUtility;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ✅ BaseTabFragmentActivity v3.0 — النسخة النهائية المحصّنة
 * 
 * التغييرات الجذرية:
 * 1. إزالة RFID_DEVICE_PRESENT الثابت → isRfidDevicePresent() ديناميكية
 * 2. initUHF() يُستدعى تلقائياً في onCreate()
 * 3. القارئ مشترك عبر sSharedReader (Singleton-like)
 * 4. منع التهيئة المزدوجة عبر sIsInitializing
 * 5. إعادة محاولة تلقائية عند فشل التهيئة (3 مرات)
 * 6. لا يوجد Simulator صامت - إما C72 أو خطأ واضح
 */
public class BaseTabFragmentActivity extends AppCompatActivity {

    private static final String TAG = "BaseTabFragmentActivity";
    
    // ============================================================================
    // ✅ الحالة الديناميكية (Static - Shared across all instances)
    // ============================================================================
    
    /** null = لم يتم الفحص بعد | true = جهاز موجود | false = غير موجود */
    private static final AtomicReference<Boolean> sRfidDevicePresent = 
        new AtomicReference<>(null);
    
    /** القارئ المشترك - Singleton-like */
    private static final AtomicReference<RFIDWithUHFUART> sSharedReader = 
        new AtomicReference<>(null);
    
    /** منع التهيئة المزدوجة */
    private static final AtomicBoolean sIsInitializing = new AtomicBoolean(false);
    
    /**
     * ✅ دالة ثابتة للاستعلام من أي مكان في التطبيق
     * 
     * @return true إذا كان القارئ الحقيقي متاحاً وجاهزاً
     */
    public static boolean isRfidDevicePresent() {
        Boolean current = sRfidDevicePresent.get();
        return current != null && current;
    }
    
    /**
     * ✅ الحصول على القارئ المشترك (للاستخدام من RFIDManager/ChainwayReader)
     */
    public static RFIDWithUHFUART getSharedReader() {
        return sSharedReader.get();
    }
    
    /**
     * ✅ إعادة تعيين الحالة (للاختبارات فقط)
     */
    public static void resetRfidState() {
        sRfidDevicePresent.set(null);
    }
    
    // ==================== Lifecycle Fields ====================
    
    public RFIDWithUHFUART mReader;
    public Fragment currentFragment = null;
    public int TidLen = 6;

    protected MiraDialogHelper dialogHelper;
    private static final int MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE = 1;
    private static final int RFID_INIT_TIMEOUT_MS = 8000;  // 8 ثواني (كافية للـ AAR الجديد)
    private int initRetryCount = 0;
    private final AtomicBoolean isDestroyed = new AtomicBoolean(false);
    protected final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    private volatile ExecutorService rfidExecutor;
    
    private volatile boolean isInitialized = false;
    private ProgressDialog initDialog;
    private long initStartTime = 0;

    private static final int[] SCAN_KEY_CODES = {
        139, 280, 291, 293, 294, 311, 312, 313, 315, 591, 593, 594, 596
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "🚀 onCreate started");
        
        MemoryManager.getInstance().registerActivity(this);
        dialogHelper = new MiraDialogHelper(this);
        registerComponentCallbacks(MemoryManager.getInstance());
        isDestroyed.set(false);
        
        checkStoragePermission();
        
        // ✅ الأهم: بدء تهيئة UHF تلقائياً
        // ننتظر 200ms لضمان استقرار الواجهة
        mainHandler.postDelayed(this::initUHF, 200);
    }

    private void createExecutorIfNeeded() {
        if (rfidExecutor == null || rfidExecutor.isShutdown() || rfidExecutor.isTerminated()) {
            rfidExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "UHF-Init-Executor");
                t.setDaemon(true);
                return t;
            });
        }
    }

    @Override
    protected void onDestroy() {
        isDestroyed.set(true);
        mainHandler.removeCallbacksAndMessages(null);
        dismissInitDialogSafely();
        
        // ✅ لا نحرر mReader هنا — RFIDManager يدير دورة الحياة
        // لأن mReader مشترك بين الأنشطة
        
        shutdownExecutorSafely();
        
        MemoryManager.getInstance().unregisterActivity(this);
        unregisterComponentCallbacks(MemoryManager.getInstance());
        MemoryManager.getInstance().forceCleanup();
        super.onDestroy();
    }

    private void shutdownExecutorSafely() {
        if (rfidExecutor != null && !rfidExecutor.isShutdown()) {
            try {
                rfidExecutor.shutdown();
                if (!rfidExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    rfidExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                rfidExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

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
            if (!granted) Log.w(TAG, "Storage permission denied");
        }
    }

    /**
     * ✅ تهيئة UHF تلقائياً
     * - يستخدم sSharedReader إذا موجود
     * - يمنع التهيئة المزدوجة عبر sIsInitializing
     * - يعيد المحاولة 3 مرات عند الفشل
     */
    public void initUHF() {
        if (isDestroyed.get()) return;
        
        // ✅ إذا كان هناك قارئ مشترك جاهز
        RFIDWithUHFUART shared = sSharedReader.get();
        if (shared != null && isInitialized) {
            Log.d(TAG, "✅ Using existing shared reader");
            mReader = shared;
            sRfidDevicePresent.set(true);
            return;
        }
        
        // ✅ منع التهيئة المزدوجة
        if (!sIsInitializing.compareAndSet(false, true)) {
            Log.d(TAG, "⏳ UHF init already in progress");
            return;
        }
        
        if (isInitialized && mReader != null) {
            Log.d(TAG, "initUHF: already initialized");
            sIsInitializing.set(false);
            return;
        }
        
        createExecutorIfNeeded();
        showInitDialog();
        initStartTime = System.currentTimeMillis();
        
        rfidExecutor.execute(() -> {
            try {
                Log.i(TAG, "🔧 Starting UHF initialization...");
                
                if (mReader == null) {
                    mReader = RFIDWithUHFUART.getInstance();
                }
                
                if (mReader == null) {
                    Log.e(TAG, "❌ RFIDWithUHFUART.getInstance() returned null");
                    sRfidDevicePresent.set(false);
                    mainHandler.post(() -> {
                        sIsInitializing.set(false);
                        handleInitResult(false);
                    });
                    return;
                }
                
                final boolean[] initResult = new boolean[1];
                Thread initThread = new Thread(() -> {
                    try {
                        initResult[0] = mReader.init(BaseTabFragmentActivity.this);
                    } catch (Throwable e) {
                        Log.e(TAG, "UHF init thread error", e);
                        initResult[0] = false;
                    }
                }, "UHF-Native-Init");
                initThread.setDaemon(true);
                initThread.start();
                initThread.join(RFID_INIT_TIMEOUT_MS);
                
                if (initThread.isAlive()) {
                    Log.e(TAG, "❌ UHF init timeout after " + RFID_INIT_TIMEOUT_MS + "ms");
                    sRfidDevicePresent.set(false);
                    mainHandler.post(() -> {
                        sIsInitializing.set(false);
                        handleInitResult(false);
                    });
                    return;
                }
                
                boolean result = initResult[0];
                long elapsed = System.currentTimeMillis() - initStartTime;
                Log.i(TAG, "UHF init result: " + result + " (took " + elapsed + "ms)");
                
                // ✅ حفظ الحالة الديناميكية
                sRfidDevicePresent.set(result);
                if (result) {
                    sSharedReader.set(mReader);
                    Log.i(TAG, "✅ Shared reader registered");
                }
                
                mainHandler.post(() -> {
                    sIsInitializing.set(false);
                    handleInitResult(result);
                });
                
            } catch (Exception e) {
                Log.e(TAG, "❌ UHF init exception", e);
                sRfidDevicePresent.set(false);
                mainHandler.post(() -> {
                    sIsInitializing.set(false);
                    handleInitResult(false);
                });
            }
        });
    }

    private void handleInitResult(boolean result) {
        dismissInitDialogSafely();
        if (isDestroyed.get() || isFinishing()) return;
        
        if (!result) {
            isInitialized = false;
            DeviceDetector.reset();
            
            Log.e(TAG, "⚠️ UHF initialization FAILED");
            
            // ✅ رسالة أولى
            if (initRetryCount == 0) {
                toastMessageSafe("⚠️ تعذر تهيئة قارئ RFID\nجاري إعادة المحاولة...");
            }
            
            // ✅ إعادة محاولة 3 مرات
            if (initRetryCount < 3) {
                initRetryCount++;
                Log.i(TAG, "🔄 Retry #" + initRetryCount + "/3 in 3s...");
                mainHandler.postDelayed(() -> {
                    if (!isDestroyed.get() && !isInitialized) {
                        initUHF();
                    }
                }, 3000);
            } else {
                toastMessageSafe("❌ فشل الاتصال بقارئ RFID\n\n" +
                    "تحقق من:\n" +
                    "• الجهاز مدعوم (Chainway C72)\n" +
                    "• القارئ مفعّل من الإعدادات\n" +
                    "• أعد تشغيل الجهاز");
            }
        } else {
            isInitialized = true;
            initRetryCount = 0;
            Log.i(TAG, "✅ UHF initialized successfully");
            toastMessageSafe("✅ قارئ RFID جاهز");
            configureReaderForDevice();
        }
    }

    private void showInitDialog() {
        if (isDestroyed.get() || isFinishing()) return;
        mainHandler.post(() -> {
            try {
                if (isDestroyed.get() || isFinishing()) return;
                dismissInitDialogSafely();
                initDialog = new ProgressDialog(BaseTabFragmentActivity.this);
                initDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
                initDialog.setMessage("جاري تهيئة قارئ RFID...");
                initDialog.setCanceledOnTouchOutside(false);
                initDialog.setCancelable(false);
                initDialog.show();
            } catch (Exception e) {
                Log.e(TAG, "Show dialog error: " + e.getMessage());
            }
        });
    }

    private void dismissInitDialogSafely() {
        mainHandler.post(() -> {
            try {
                if (initDialog != null && initDialog.isShowing()) {
                    initDialog.dismiss();
                }
            } catch (Exception e) {
                Log.e(TAG, "Dismiss dialog error: " + e.getMessage());
            } finally {
                initDialog = null;
            }
        });
    }

    public void configureReaderForDevice() {
        if (!isInitialized || mReader == null) return;
        
        createExecutorIfNeeded();
        
        rfidExecutor.execute(() -> {
            try {
                DeviceDetector.DeviceProfile profile = DeviceDetector.configure(mReader);
                if (profile != null && profile.ready) {
                    Log.i(TAG, "✅ Power set to: " + profile.appliedPower + " dBm");
                }
            } catch (Exception e) {
                Log.e(TAG, "Configuration error", e);
            }
        });
    }

    /**
     * ✅ فحص جاهزية القارئ
     */
    public boolean isReaderReady() {
        return isInitialized && mReader != null && !isDestroyed.get();
    }
    
    /**
     * ✅ الحصول على mReader
     */
    public RFIDWithUHFUART getReader() {
        return mReader;
    }

    private boolean isScanKey(int keyCode) {
        for (int code : SCAN_KEY_CODES) {
            if (code == keyCode) return true;
        }
        return false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isScanKey(keyCode)) {
            if (event.getRepeatCount() == 0
                    && currentFragment instanceof KeyDwonFragment) {
                KeyDwonFragment kdf = (KeyDwonFragment) currentFragment;
                kdf.myOnKeyDwon(keyCode, event);
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main, menu);
        if (BuildConfig.Test) {
            MenuItem speedItem = menu.findItem(R.id.speed);
            if (speedItem != null) speedItem.setVisible(true);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) return false;
        if (id == R.id.UHF_ver) { getUHFVersion(); return true; }
        if (id == R.id.export) { exportData(); return true; }
        if (id == R.id.speed) { startActivity(new Intent(this, TestActivity.class)); return true; }
        return super.onOptionsItemSelected(item);
    }

    public void exportData() {
        toastMessageSafe("Export");
    }

    public void toastMessage(String msg) {
        toastMessageSafe(msg);
    }

    private void toastMessageSafe(String msg) {
        if (isDestroyed.get() || isFinishing()) return;
        mainHandler.post(() -> {
            if (!isDestroyed.get() && !isFinishing()) {
                Toast.makeText(BaseTabFragmentActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    public boolean vailHexInput(String str) {
        if (str == null || str.length() == 0) return false;
        if (str.length() % 2 == 0) return StringUtility.isHexNumberRex(str);
        return false;
    }

    public void getUHFVersion() {
        if (mReader == null || isDestroyed.get()) {
            toastMessageSafe("Reader not available");
            return;
        }
        
        createExecutorIfNeeded();
        
        rfidExecutor.execute(() -> {
            try {
                String rfidVer = mReader.getVersion();
                String hardwareVersion = mReader.getHardwareVersion();
                String deviceInfo = DeviceDetector.getDeviceInfo();
                String version = "=== Device ===\n" + deviceInfo + 
                    "\n=== UHF ===\nSoftware: " + rfidVer + 
                    "\nHardware: " + hardwareVersion;
                mainHandler.post(() -> UIHelper.alert(BaseTabFragmentActivity.this, 
                    R.string.action_uhf_ver, version, R.drawable.webtext));
            } catch (Exception e) {
                Log.e(TAG, "Get UHF version error: " + e.getMessage());
                toastMessageSafe("Error getting UHF version");
            }
        });
    }

    public String getVerName() {
        try {
            return this.getPackageManager()
                .getPackageInfo(this.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }
}
