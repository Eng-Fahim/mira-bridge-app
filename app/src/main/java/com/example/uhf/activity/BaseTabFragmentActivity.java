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

public class BaseTabFragmentActivity extends AppCompatActivity {

    private static final String TAG = "BaseTabFragmentActivity";
    
    // ✅ وضع التطوير - اجعلها true فقط عند وصول الجهاز لبرمجة الشرائح فعلياً
    public static final boolean RFID_DEVICE_PRESENT = false;
    private static final boolean SKIP_RFID_INIT_IF_ABSENT = true;
    
    public RFIDWithUHFUART mReader;
    public Fragment currentFragment = null;
    public int TidLen = 6;

    protected MiraDialogHelper dialogHelper;
    private static final int MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE = 1;
    private static final int RFID_INIT_TIMEOUT_MS = 5000;
    private int initRetryCount = 0;
    private final AtomicBoolean isDestroyed = new AtomicBoolean(false);
    protected final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    private volatile ExecutorService rfidExecutor;
    
    private boolean isInitialized = false;
    private ProgressDialog initDialog;
    private long initStartTime = 0;

    private static final int[] SCAN_KEY_CODES = {
        139, 280, 291, 293, 294, 311, 312, 313, 315, 591, 593, 594, 596
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MemoryManager.getInstance().registerActivity(this);
        dialogHelper = new MiraDialogHelper(this);
        registerComponentCallbacks(MemoryManager.getInstance());
        isDestroyed.set(false);
        
        if (RFID_DEVICE_PRESENT) {
            createExecutorIfNeeded();
        }
        
        checkStoragePermission();
    }

    private void createExecutorIfNeeded() {
        if (rfidExecutor == null || rfidExecutor.isShutdown() || rfidExecutor.isTerminated()) {
            rfidExecutor = Executors.newSingleThreadExecutor();
        }
    }

    @Override
    protected void onDestroy() {
        isDestroyed.set(true);
        mainHandler.removeCallbacksAndMessages(null);
        dismissInitDialogSafely();
        
        if (RFID_DEVICE_PRESENT) {
            freeReaderSafely();
            shutdownExecutorSafely();
        }
        
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
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) Log.w(TAG, "Storage permission denied");
        }
    }

    public void initUHF() {
        if (isDestroyed.get()) return;
        
        // ✅ تم التعديل هنا: تجاوز تهيئة العتاد بالكامل إن لم يكن موجوداً لتجنب حظر الواجهة تماماً
        if (!RFID_DEVICE_PRESENT) {
            Log.i(TAG, "RFID device present flag is FALSE - Bypassing UHF Init completely");
            isInitialized = false;
            dismissInitDialogSafely();
            return;
        }
        
        createExecutorIfNeeded();
        
        showInitDialog();
        initStartTime = System.currentTimeMillis();
        
        rfidExecutor.execute(() -> {
            try {
                if (mReader == null) {
                    mReader = RFIDWithUHFUART.getInstance();
                }
                if (mReader != null) {
                    final boolean[] initResult = new boolean[1];
                    Thread initThread = new Thread(() -> {
                        try {
                            initResult[0] = mReader.init(BaseTabFragmentActivity.this);
                        } catch (Exception e) {
                            Log.e(TAG, "UHF init thread error", e);
                            initResult[0] = false;
                        }
                    });
                    initThread.start();
                    initThread.join(RFID_INIT_TIMEOUT_MS);
                    
                    if (initThread.isAlive()) {
                        Log.e(TAG, "UHF init timeout!");
                        mainHandler.post(() -> handleInitResult(false));
                        return;
                    }
                    
                    boolean result = initResult[0];
                    long elapsed = System.currentTimeMillis() - initStartTime;
                    if (elapsed > RFID_INIT_TIMEOUT_MS) {
                        Log.w(TAG, "UHF init took too long: " + elapsed + "ms");
                    }
                    mainHandler.post(() -> handleInitResult(result));
                } else {
                    mainHandler.post(() -> handleInitResult(false));
                }
            } catch (Exception e) {
                Log.e(TAG, "UHF init error", e);
                mainHandler.post(() -> handleInitResult(false));
            }
        });
    }

    private void handleInitResult(boolean result) {
        dismissInitDialogSafely();
        if (isDestroyed.get() || isFinishing()) return;
        
        if (!result) {
            isInitialized = false;
            DeviceDetector.reset();
            
            if (RFID_DEVICE_PRESENT) {
                String errorMsg = "UHF Init Failed\nDevice: " + Build.MODEL;
                toastMessageSafe(errorMsg);
            }
            
            if (initRetryCount < 1 && RFID_DEVICE_PRESENT) {
                initRetryCount++;
                mainHandler.postDelayed(() -> {
                    if (!isDestroyed.get() && !isInitialized) initUHF();
                }, 10000); 
            }
        } else {
            isInitialized = true;
            initRetryCount = 0;
            configureReaderForDevice();
        }
    }

    private void showInitDialog() {
        if (!RFID_DEVICE_PRESENT) {
            return;
        }
        
        if (isDestroyed.get() || isFinishing()) return;
        mainHandler.post(() -> {
            try {
                if (isDestroyed.get() || isFinishing()) return;
                
                dismissInitDialogSafely();
                initDialog = new ProgressDialog(BaseTabFragmentActivity.this);
                initDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
                initDialog.setMessage("Initializing UHF...");
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

    private void freeReaderSafely() {
        if (mReader == null) return;
        
        final RFIDWithUHFUART readerToFree = mReader;
        mReader = null;
        
        try {
            Thread freeThread = new Thread(() -> {
                try {
                    readerToFree.free();
                } catch (Exception e) {
                    Log.e(TAG, "Free reader error", e);
                }
            });
            freeThread.start();
            freeThread.join(2000);
            
            if (freeThread.isAlive()) {
                Log.w(TAG, "Free reader timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void configureReaderForDevice() {
        if (!RFID_DEVICE_PRESENT || !isInitialized) return;
        
        createExecutorIfNeeded();
        
        rfidExecutor.execute(() -> {
            try {
                DeviceDetector.DeviceProfile profile = DeviceDetector.configure(mReader);
                if (profile != null && profile.ready) {
                    Log.i(TAG, "Power set to: " + profile.appliedPower + " dBm (OK)");
                }
            } catch (Exception e) {
                Log.e(TAG, "Configuration error", e);
            }
        });
    }

    public boolean isReaderReady() {
        return RFID_DEVICE_PRESENT && mReader != null && isInitialized && !isDestroyed.get();
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
            if (event.getRepeatCount() == 0 && currentFragment instanceof KeyDwonFragment) {
                ((KeyDwonFragment) currentFragment).myOnKeyDwon();
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
                Toast.makeText(BaseTabFragmentActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    public boolean vailHexInput(String str) {
        if (str == null || str.length() == 0) return false;
        if (str.length() % 2 == 0) return StringUtility.isHexNumberRex(str);
        return false;
    }

    public void getUHFVersion() {
        if (!RFID_DEVICE_PRESENT) {
            toastMessageSafe("RFID device not connected");
            return;
        }
        
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
                String version = "=== Device ===\n" + deviceInfo + "\n=== UHF ===\nSoftware: " + rfidVer + "\nHardware: " + hardwareVersion;
                mainHandler.post(() -> UIHelper.alert(BaseTabFragmentActivity.this, R.string.action_uhf_ver, version, R.drawable.webtext));
            } catch (Exception e) {
                Log.e(TAG, "Get UHF version error: " + e.getMessage());
                toastMessageSafe("Error getting UHF version");
            }
        });
    }

    public String getVerName() {
        try {
            return this.getPackageManager().getPackageInfo(this.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }
}
