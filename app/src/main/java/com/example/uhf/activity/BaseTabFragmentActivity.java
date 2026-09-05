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
import java.util.concurrent.atomic.AtomicBoolean;

public class BaseTabFragmentActivity extends AppCompatActivity {

    private static final String TAG = "BaseTabFragmentActivity";
    
    public RFIDWithUHFUART mReader;
    public Fragment currentFragment = null;
    public int TidLen = 6;

    protected MiraDialogHelper dialogHelper;
    private static final int MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE = 1;
    private static final int RFID_INIT_TIMEOUT_MS = 10000;
    private int initRetryCount = 0;
    private final AtomicBoolean isDestroyed = new AtomicBoolean(false);
    protected final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // ✅ مسار واحد مخصص لجميع عمليات الهاردوير لمنع التداخل والتعليق
    protected final ExecutorService rfidExecutor = Executors.newSingleThreadExecutor();
    
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
        checkStoragePermission();
    }

    @Override
    protected void onDestroy() {
        isDestroyed.set(true);
        mainHandler.removeCallbacksAndMessages(null);
        dismissInitDialogSafely();
        
        freeReaderSafely(); // ✅ التنظيف يعمل في الخلفية
        rfidExecutor.shutdown(); // إغلاق المسار بعد إكمال المهام المتبقية
        
        MemoryManager.getInstance().unregisterActivity(this);
        unregisterComponentCallbacks(MemoryManager.getInstance());
        MemoryManager.getInstance().forceCleanup();
        super.onDestroy();
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
        showInitDialog();
        initStartTime = System.currentTimeMillis();
        
        // ✅ نقل getInstance و init معاً إلى المسار الخلفي
        rfidExecutor.execute(() -> {
            try {
                if (mReader == null) {
                    mReader = RFIDWithUHFUART.getInstance();
                }
                if (mReader != null) {
                    boolean result = mReader.init(BaseTabFragmentActivity.this);
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
            String errorMsg = "UHF Init Failed\nDevice: " + Build.MODEL;
            toastMessageSafe(errorMsg);
            
            if (initRetryCount < 1) {
                initRetryCount++;
                mainHandler.postDelayed(() -> {
                    if (!isDestroyed.get() && !isInitialized) initUHF();
                }, 3000);
            } else {
                toastMessageSafe("UHF init failed - Please check device");
            }
        } else {
            isInitialized = true;
            initRetryCount = 0;
            configureReaderForDevice();
        }
    }

    private void showInitDialog() {
        if (isDestroyed.get() || isFinishing()) return;
        mainHandler.post(() -> {
            try {
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
        // ✅ التحرير يتم عبر rfidExecutor لمنع تعليق الشاشة عند إغلاق التطبيق
        rfidExecutor.execute(() -> {
            try {
                if (mReader != null) {
                    mReader.free();
                    mReader = null;
                }
            } catch (Exception e) {
                Log.e(TAG, "Free reader error: " + e.getMessage());
            }
        });
    }

    public void configureReaderForDevice() {
        if (!isInitialized) return;
        
        // ✅ إعداد الهاردوير يحتاج أن يكون في الخلفية لتفادي أي ثواني تجميد
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
        return mReader != null && isInitialized && !isDestroyed.get();
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
        if (mReader == null || isDestroyed.get()) {
            toastMessageSafe("Reader not available");
            return;
        }
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
