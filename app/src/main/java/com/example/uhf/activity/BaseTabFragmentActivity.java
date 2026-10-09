package com.example.uhf.activity;

import android.Manifest;
import android.app.ProgressDialog;
import android.content.Context;
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
import com.example.uhf.MiraRfid.MiraReaderSettings;
import com.rscja.deviceapi.RFIDWithUHFUART;
import com.rscja.utility.StringUtility;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ✅ BaseTabFragmentActivity v3.1 — مع الحاجز المركزي
 */
public class BaseTabFragmentActivity extends AppCompatActivity {

    private static final String TAG = "BaseTabFragmentActivity";

    // ═══════════════════════════════════════════════════════════
    //  ✅ الحالة الديناميكية (Static - Shared across all instances)
    // ═══════════════════════════════════════════════════════════

    private static final AtomicReference<Boolean> sRfidDevicePresent =
        new AtomicReference<>(null);

    private static final AtomicReference<RFIDWithUHFUART> sSharedReader =
        new AtomicReference<>(null);

    private static final AtomicBoolean sIsInitializing = new AtomicBoolean(false);

    /** ✅ [جديد] حاجز مركزي — آخر وقت تم فيه تطبيق إعدادات MIRA */
    private static volatile long sLastSettingsApplied = 0;

    /** ✅ [جديد] application context للحاجز */
    private static Context sAppContext;

    /** ✅ فترة الحاجز — لا تُطبّق الإعدادات أكثر من مرة كل 500ms */
    private static final long SETTINGS_GUARD_INTERVAL_MS = 500;

    // ═══════════════════════════════════════════════════════════
    //  ✅ getSharedReader — دالة وحيدة مع حاجز مركزي
    // ═══════════════════════════════════════════════════════════

    public static boolean isRfidDevicePresent() {
        Boolean current = sRfidDevicePresent.get();
        return current != null && current;
    }

    /**
     * ✅ الحصول على القارئ المشترك + حاجز مركزي تلقائي
     *
     * في كل استدعاء (بفاصل 500ms على الأقل)، يُعيد تطبيق إعدادات MIRA
     * على القارئ. هذا يمنع أي تطبيق خارجي (UHF الرسمي) من التأثير
     * على إعدادات MIRA.
     */
    public static RFIDWithUHFUART getSharedReader() {
        RFIDWithUHFUART reader = sSharedReader.get();

        // ✅ حاجز وقائي: أعِد تطبيق الإعدادات مرة كل 500ms على الأكثر
        if (reader != null && sAppContext != null) {
            long now = System.currentTimeMillis();
            if (now - sLastSettingsApplied > SETTINGS_GUARD_INTERVAL_MS) {
                sLastSettingsApplied = now;
                try {
                    MiraReaderSettings.getInstance(sAppContext).applyToReader(reader);
                } catch (Exception e) {
                    Log.w(TAG, "Guard applyToReader error: " + e.getMessage());
                }
            }
        }
        return reader;
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

        // ✅ [جديد] احفظ application context للحاجز المركزي
        sAppContext = getApplicationContext();

        MemoryManager.getInstance().registerActivity(this);
        dialogHelper = new MiraDialogHelper(this);
        registerComponentCallbacks(MemoryManager.getInstance());
        isDestroyed.set(false);

        checkStoragePermission();

        if (DeviceDetector.isRfidHardwareSupported()) {
            Log.i(TAG, "✅ RFID hardware supported — scheduling UHF init");
            mainHandler.postDelayed(this::initUHF, 800);
        } else {
            Log.w(TAG, "⚠️ RFID hardware NOT supported — skipping UHF init");
            sRfidDevicePresent.set(false);
        }
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

    public void initUHF() {
        if (isDestroyed.get()) return;

        if (!DeviceDetector.isRfidHardwareSupported()) {
            Log.w(TAG, "⚠️ initUHF skipped — hardware not supported");
            sRfidDevicePresent.set(false);
            return;
        }

        RFIDWithUHFUART shared = sSharedReader.get();
        if (shared != null && isInitialized) {
            Log.d(TAG, "✅ Using existing shared reader");
            mReader = shared;
            sRfidDevicePresent.set(true);
            return;
        }

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

        toastMessageSafe("جاري تهيئة قارئ RFID...");
        initStartTime = System.currentTimeMillis();

        rfidExecutor.execute(() -> {
            try {
                Log.i(TAG, "🔧 Starting UHF initialization...");

                if (!DeviceDetector.isRfidHardwareSupported()) {
                    Log.w(TAG, "⚠️ Hardware not supported (inside thread)");
                    sRfidDevicePresent.set(false);
                    mainHandler.post(() -> sIsInitializing.set(false));
                    return;
                }

                if (mReader == null) {
                    try {
                        mReader = RFIDWithUHFUART.getInstance();
                    } catch (Throwable e) {
                        Log.e(TAG, "❌ RFIDWithUHFUART.getInstance() failed", e);
                        sRfidDevicePresent.set(false);
                        mainHandler.post(() -> {
                            sIsInitializing.set(false);
                            handleInitResult(false);
                        });
                        return;
                    }
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

                initThread.join(3000);

                if (initThread.isAlive()) {
                    Log.e(TAG, "❌ UHF init timeout after 3000ms");
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

            if (initRetryCount < 1) {
                initRetryCount++;
                Log.i(TAG, "🔄 Retry #" + initRetryCount + "/1 in 5s...");
                mainHandler.postDelayed(() -> {
                    if (!isDestroyed.get() && !isInitialized
                            && DeviceDetector.isRfidHardwareSupported()) {
                        initUHF();
                    }
                }, 5000);
            } else {
                Log.e(TAG, "❌ UHF init permanently failed");
            }
        } else {
            isInitialized = true;
            initRetryCount = 0;
            Log.i(TAG, "✅ UHF initialized successfully");

            // ✅ [جديد] طبّق إعدادات MIRA فور نجاح التهيئة
            applyMiraSettingsNow();

            configureReaderForDevice();
        }
    }

    /**
     * ✅ [جديد] تطبيق فوري لإعدادات MIRA — يُستدعى مرة واحدة بعد التهيئة
     */
    private void applyMiraSettingsNow() {
        if (mReader == null || sAppContext == null) return;
        try {
            MiraReaderSettings.getInstance(sAppContext).applyToReader(mReader);
            sLastSettingsApplied = System.currentTimeMillis();
            Log.i(TAG, "✅ MIRA settings applied after init");
        } catch (Exception e) {
            Log.w(TAG, "applyMiraSettingsNow error: " + e.getMessage());
        }
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

    public boolean isReaderReady() {
        return isInitialized && mReader != null && !isDestroyed.get();
    }

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
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (!isScanKey(keyCode)) {
            return super.onKeyUp(keyCode, event);
        }

        Log.d(TAG, "🔓 onKeyUp received: " + keyCode);

        if (currentFragment instanceof KeyDwonFragment) {
            try {
                java.lang.reflect.Method m = currentFragment.getClass()
                    .getMethod("myOnKeyUp", int.class, KeyEvent.class);
                m.invoke(currentFragment, keyCode, event);
                Log.d(TAG, "   └─ myOnKeyUp invoked on Fragment");
            } catch (NoSuchMethodException ignored) {
                Log.d(TAG, "   └─ Fragment has no myOnKeyUp (ok)");
            } catch (Exception e) {
                Log.e(TAG, "   └─ myOnKeyUp error", e);
            }
        }

        stopRfidScanSafely();

        return true;
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (isScanKey(keyCode)) {
            Log.d(TAG, "⏸️ onKeyLongPress ignored: " + keyCode);
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    private void stopRfidScanSafely() {
        try {
            com.example.uhf.MiraRfid.RFIDManager manager =
                com.example.uhf.MiraRfid.RFIDManager
                    .getInstance(getApplicationContext());

            if (manager != null) {
                manager.stopScan();
                Log.d(TAG, "✅ Scan stopped via onKeyUp");
            } else {
                Log.w(TAG, "⚠️ RFIDManager is null in onKeyUp");
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ stopRfidScanSafely error: " + e.getMessage(), e);
        }
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
