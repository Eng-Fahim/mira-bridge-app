package com.example.uhf.activity;

import android.Manifest;
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
import com.example.uhf.MiraRfid.RFIDManager;
import com.example.uhf.MiraRfid.MiraIRFIDReader;
import com.example.uhf.MiraRfid.MiraReaderSettings;
import com.example.uhf.MiraRfid.readers.ChainwayReader;
import com.rscja.deviceapi.RFIDWithUHFUART;
import com.rscja.utility.StringUtility;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  BaseTabFragmentActivity v4.1 — "Consumer-only" لـ RFIDManager
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * [v4.0]
 *  ✅ التهيئة مفوّضة بالكامل إلى RFIDManager (ensureReady)
 *  ✅ publishSharedReader() / getSharedReader()
 *  ✅ isRfidDevicePresent() → RFIDManager.isReady()
 *
 * [v4.1] ← جديد
 *  ✅ onResume() — إعادة تطبيق أحدث إعدادات MIRA على القارئ المشترك
 *     تلقائياً عند عودة أي شاشة (Fragment). هذا يضمن أن شاشة الجرد
 *     تستخدم أحدث الإعدادات حتى لو تم تغييرها من شاشة أخرى.
 *  ✅ applyReaderSettingsToSharedReader() — دالة عامة static للاستخدام
 *     اليدوي من أي مكان عند الحاجة.
 */
public class BaseTabFragmentActivity extends AppCompatActivity {

    private static final String TAG = "BaseTabFragmentActivity";

    // ═══════════════════════════════════════════════════════════
    //  القارئ المشترك
    // ═══════════════════════════════════════════════════════════
    private static final AtomicReference<RFIDWithUHFUART> sSharedReader =
            new AtomicReference<>(null);

    private static volatile Context sAppContext;

    // 🆕 v4.1 — throttle لمنع تطبيق متكرر عند التنقل السريع
    private static volatile long sLastSettingsApplyTime = 0;
    private static final long SETTINGS_APPLY_THROTTLE_MS = 500;

    public static void publishSharedReader(RFIDWithUHFUART reader) {
        sSharedReader.set(reader);
        Log.i(TAG, "📢 Shared reader published: " + (reader != null));
    }

    public static RFIDWithUHFUART getSharedReader() {
        return sSharedReader.get();
    }

    public static boolean isRfidDevicePresent() {
        if (sAppContext == null) return false;
        try {
            RFIDManager mgr = RFIDManager.getInstance(sAppContext);
            return mgr != null && mgr.isReady();
        } catch (Exception e) {
            return false;
        }
    }

    public static void resetRfidState() {
        sSharedReader.set(null);
        if (sAppContext == null) return;
        try {
            RFIDManager mgr = RFIDManager.getInstance(sAppContext);
            if (mgr != null) mgr.reset();
        } catch (Exception ignored) {}
    }

    // ═══════════════════════════════════════════════════════════
    //  🆕 v4.1 — تطبيق الإعدادات على القارئ المشترك
    // ═══════════════════════════════════════════════════════════
    /**
     * ✅ تُطبّق أحدث إعدادات MIRA على القارئ المشترك.
     *
     * تُستدعى تلقائياً من onResume() — وتُتيح استدعاءها يدوياً
     * من أي مكان في التطبيق عند الحاجة.
     *
     * @return true إذا تم التطبيق أو كان القارئ غير جاهز
     */
    public static boolean applyReaderSettingsToSharedReader() {
        // 1) هل لدينا سياق وقارئ؟
        if (sAppContext == null) {
            return false;
        }

        // 2) throttle — منع التطبيق المتكرر السريع
        long now = System.currentTimeMillis();
        if (now - sLastSettingsApplyTime < SETTINGS_APPLY_THROTTLE_MS) {
            return true;
        }
        sLastSettingsApplyTime = now;

        // 3) مرّ عبر RFIDManager (المسار المُفضّل — v4.1 مع المستمع)
        try {
            RFIDManager mgr = RFIDManager.getInstance(sAppContext);
            if (mgr != null && mgr.isReady()) {
                MiraIRFIDReader reader = mgr.getReader();
                if (reader instanceof ChainwayReader) {
                    boolean ok = ((ChainwayReader) reader).applySettingsNow();
                    Log.d(TAG, "🔧 Settings applied via ChainwayReader: " + ok);
                    return ok;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "applyReaderSettings (ChainwayReader path) error", e);
        }

        // 4) Fallback — تطبيق مباشر على SDK
        RFIDWithUHFUART sdk = getSharedReader();
        if (sdk == null) {
            Log.d(TAG, "applyReaderSettings: no shared reader yet");
            return false;
        }

        try {
            MiraReaderSettings settings = MiraReaderSettings.getInstance(sAppContext);
            if (settings != null) {
                MiraReaderSettings.ApplyResult r = settings.applyToReader(sdk);
                Log.i(TAG, "🔧 Settings applied (direct SDK): " + r);
                return r.isSuccess();
            }
        } catch (Exception e) {
            Log.e(TAG, "applyReaderSettings (direct SDK) error", e);
        }
        return false;
    }

    // ==================== Lifecycle Fields ====================

    @Deprecated
    public RFIDWithUHFUART mReader;

    public Fragment currentFragment = null;
    public int TidLen = 6;

    protected MiraDialogHelper dialogHelper;
    private static final int MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE = 1;
    private final AtomicBoolean isDestroyed = new AtomicBoolean(false);
    protected final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile ExecutorService rfidExecutor;

    private static final int[] SCAN_KEY_CODES = {
        139, 280, 291, 293, 294, 311, 312, 313, 315, 591, 593, 594, 596
    };

    // ═══════════════════════════════════════════════════════════
    //  onCreate
    // ═══════════════════════════════════════════════════════════
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "🚀 onCreate started");

        sAppContext = getApplicationContext();

        MemoryManager.getInstance().registerActivity(this);
        dialogHelper = new MiraDialogHelper(this);
        registerComponentCallbacks(MemoryManager.getInstance());
        isDestroyed.set(false);

        checkStoragePermission();

        if (DeviceDetector.isRfidHardwareSupported()) {
            Log.i(TAG, "✅ RFID hardware supported — requesting ensureReady");
            requestReaderReady();
        } else {
            Log.w(TAG, "⚠️ RFID hardware NOT supported — skipping ensureReady");
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  🆕 v4.1 — onResume: إعادة تطبيق الإعدادات تلقائياً
    // ═══════════════════════════════════════════════════════════
    /**
     * ✅ عند عودة النشاط للمقدمة، نُعيد تطبيق أحدث إعدادات MIRA
     *    على القارئ المشترك. هذا يضمن أن شاشة الجرد تحصل على
     *    أحدث إعدادات حتى لو تم تغييرها من شاشة أخرى.
     *
     * ملاحظة: هذا يتم على الـ main thread لكن العملية سريعة جداً
     * (كلها استدعاءات SDK خفيفة) — لا تحتاج Executor.
     */
    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "▶️ onResume — applying latest MIRA settings");

        // تطبيق الإعدادات على القارئ المشترك
        boolean ok = applyReaderSettingsToSharedReader();
        Log.d(TAG, "   └─ Settings applied: " + ok);

        // إعادة مزامنة mReader (للتوافق العكسي)
        mReader = getSharedReader();
    }

    private void requestReaderReady() {
        try {
            RFIDManager mgr = RFIDManager.getInstance(getApplicationContext());

            mgr.ensureReady(new RFIDManager.ReadyCallback() {
                @Override
                public void onReady(MiraIRFIDReader reader) {
                    Log.i(TAG, "✅ RFIDManager.ensureReady → READY");

                    // 🆕 v4.1 — طبّق الإعدادات فور الجاهزية
                    applyReaderSettingsToSharedReader();

                    mainHandler.post(() -> {
                        if (isDestroyed.get()) return;
                        mReader = getSharedReader();
                        Log.i(TAG, "   └─ mReader synced: "
                                + (mReader != null));
                    });
                }

                @Override
                public void onError(String reason) {
                    Log.e(TAG, "❌ RFIDManager.ensureReady → ERROR: " + reason);
                    mainHandler.post(() -> {
                        if (!isDestroyed.get() && !isFinishing()) {
                            toastMessageSafe("تعذر تهيئة قارئ RFID");
                        }
                    });
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "requestReaderReady error", e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  onDestroy
    // ═══════════════════════════════════════════════════════════
    @Override
    protected void onDestroy() {
        isDestroyed.set(true);
        mainHandler.removeCallbacksAndMessages(null);

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

    private void createExecutorIfNeeded() {
        if (rfidExecutor == null || rfidExecutor.isShutdown()
                || rfidExecutor.isTerminated()) {
            rfidExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "UHF-Init-Executor");
                t.setDaemon(true);
                return t;
            });
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Permissions (unchanged)
    // ═══════════════════════════════════════════════════════════
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
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) Log.w(TAG, "Storage permission denied");
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Public Accessors
    // ═══════════════════════════════════════════════════════════

    public boolean isReaderReady() {
        if (isDestroyed.get()) return false;
        try {
            RFIDManager mgr = RFIDManager.getInstance(getApplicationContext());
            return mgr != null && mgr.isReady();
        } catch (Exception e) {
            return false;
        }
    }

    public RFIDWithUHFUART getReader() {
        try {
            if (!isReaderReady()) return null;

            RFIDWithUHFUART shared = getSharedReader();
            if (shared != null) {
                mReader = shared;
                return shared;
            }
            return mReader;
        } catch (Exception e) {
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Key Handling (unchanged)
    // ═══════════════════════════════════════════════════════════

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
            RFIDManager mgr = RFIDManager.getInstance(getApplicationContext());
            if (mgr != null) {
                mgr.stopScan();
                Log.d(TAG, "✅ Scan stopped via onKeyUp");
            } else {
                Log.w(TAG, "⚠️ RFIDManager is null in onKeyUp");
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ stopRfidScanSafely error: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Menus (unchanged)
    // ═══════════════════════════════════════════════════════════

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
        if (id == R.id.speed) {
            startActivity(new Intent(this, TestActivity.class));
            return true;
        }
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

    // ═══════════════════════════════════════════════════════════
    //  UHF Version (unchanged)
    // ═══════════════════════════════════════════════════════════
    public void getUHFVersion() {
        final RFIDWithUHFUART reader = getReader();
        if (reader == null || isDestroyed.get()) {
            toastMessageSafe("Reader not available");
            return;
        }

        createExecutorIfNeeded();

        rfidExecutor.execute(() -> {
            try {
                String rfidVer = reader.getVersion();
                String hardwareVersion = reader.getHardwareVersion();
                String deviceInfo = DeviceDetector.getDeviceInfo();
                String version = "=== Device ===\n" + deviceInfo
                        + "\n=== UHF ===\nSoftware: " + rfidVer
                        + "\nHardware: " + hardwareVersion;
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
            return getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }
}
