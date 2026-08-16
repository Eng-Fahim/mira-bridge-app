package com.example.uhf.activity;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.example.uhf.R;
import com.example.uhf.fragment.JewelryDashboardFragment;
import com.example.uhf.fragment.MiraAccountingFragment;
import com.example.uhf.fragment.MiraBranchPickerFragment;
import com.example.uhf.fragment.MiraBranchesManagementFragment;
import com.example.uhf.fragment.MiraDashboardFragment;
import com.example.uhf.fragment.MiraGateSettingsFragment;
import com.example.uhf.fragment.MiraGoldCurrencyFragment;
import com.example.uhf.fragment.MiraIdentityStudioFragment;
import com.example.uhf.fragment.MiraInventoryProFragment;
import com.example.uhf.fragment.MiraPosFragment;
import com.example.uhf.fragment.MiraReaderSetupFragment;
import com.example.uhf.fragment.MiraReceiptFragment;
import com.example.uhf.fragment.MiraReportsProFragment;
import com.example.uhf.fragment.MiraSalesHistoryFragment;
import com.example.uhf.fragment.MiraSecureGateFragment;
import com.example.uhf.fragment.MiraTransferFragment;
import com.example.uhf.fragment.MiraUHFRadarLocationFragment;
import com.example.uhf.fragment.MiraUHFReadTagFragment;
import com.example.uhf.fragment.UHFReadWriteFragment;
import com.example.uhf.fragment.UHFSetFragment;
import com.example.uhf.fragment.UsersManagementFragment;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.tools.ExportExcelAsyncTask;
import com.example.uhf.tools.MiraLanguageHelper;
import com.example.uhf.tools.SessionManager;
import com.example.uhf.tools.ToastUtil;
import com.example.uhf.tools.UIHelper;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.rscja.deviceapi.entity.UHFTAGInfo;
import com.rscja.deviceapi.interfaces.ConnectionStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class UHFMainActivity extends BaseTabFragmentActivity {

    private static final String TAG = "UHFMainActivity";
    private static final int PERMISSION_REQUEST_CODE = 1001;

    // UI Elements
    private FrameLayout loadingOverlay;
    private FragmentManager fragmentManager;
    public Fragment currentFragment;
    private MiraPosFragment activePOSFragment = null;
    private MiraIdentityStudioFragment activeEncodingFragment;
    private OnRfidScannedListener activeRfidListener;

    // Managers & Handlers
    private SessionManager sessionManager;
    private MiraBranchesManager branchesManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public int selectIndex = -1;
    public ArrayList<UHFTAGInfo> tagList = new ArrayList<>();
    public boolean loopFlag = false;
    private boolean isArabic = true;
    private int lastActiveBranchId = -1;

    // Audio System
    private PlaySoundThread playSoundThread = null;
    private SoundPool soundPool;
    private AudioManager audioManager;
    private final HashMap<Integer, Integer> soundMap = new HashMap<>();

    public interface OnRfidScannedListener {
        void onRfidScanned(String epc);
    }

    public void setActiveEncodingFragment(MiraIdentityStudioFragment fragment) {
        this.activeEncodingFragment = fragment;
    }

    public void setActiveRfidListener(OnRfidScannedListener listener) {
        this.activeRfidListener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
            String savedLang = MiraLanguageHelper.getSavedLanguage(this);
            MiraLanguageHelper.applyLanguage(this, savedLang);
            super.onCreate(savedInstanceState);

            ToastUtil.init(getApplicationContext());
            initializeLanguageState();

            sessionManager = new SessionManager(this);
            if (!sessionManager.isLoggedIn()) {
                navigateToLogin();
                return;
            }

            branchesManager = MiraBranchesManager.getInstance(this);
            if (branchesManager != null) {
                lastActiveBranchId = branchesManager.getActiveBranchId();
            }

            setContentView(R.layout.activity_main);
            loadingOverlay = findViewById(R.id.loading_overlay);
            fragmentManager = getSupportFragmentManager();

            MaterialToolbar toolbar = findViewById(R.id.toolbar);
            if (toolbar != null) {
                setSupportActionBar(toolbar);
                updateToolbarTitle();
            }

            // ربط زر حالة الأجهزة العلوي بالوصول السريع
            View btnHardwareStatus = findViewById(R.id.btnHardwareStatus);
            if (btnHardwareStatus != null) {
                btnHardwareStatus.setOnClickListener(v -> showHardwareOptionsDialog());
            }

            checkReadWritePermission();

            safeExecute(this::initSound, "Sound Init Error");
            safeExecute(this::initUHF, "UHF Hardware Init Error");
            safeExecute(this::configureReaderForDevice, "Reader Configuration Error");

            setupQuickNav();

            // تحميل شاشة البيع افتراضياً عند فتح التطبيق
            loadFragment(new MiraPosFragment(), "POS");

            showLoading();
            mainHandler.postDelayed(this::hideLoading, 300);

        } catch (Exception e) {
            Log.e(TAG, "Fatal error in onCreate: " + e.getMessage(), e);
            Toast.makeText(this, isArabic ? "حدث خطأ أثناء تحميل الواجهة" : "Initialization Error", Toast.LENGTH_LONG).show();
        }
    }

    private void initializeLanguageState() {
        try {
            isArabic = getResources().getConfiguration().locale.getLanguage().equals("ar");
            getWindow().getDecorView().setLayoutDirection(
                    isArabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR
            );
        } catch (Exception e) {
            Log.w(TAG, "Locale check failed: " + e.getMessage());
        }
    }

    // ==================== NAV SYSTEM & FRAGMENT MANAGEMENT ====================

    private void setupQuickNav() {
        try {
            BottomNavigationView quickNav = findViewById(R.id.bottomQuickNav);
            if (quickNav == null) return;

            quickNav.setOnItemSelectedListener(item -> {
                int id = item.getItemId();

                if (id == R.id.nav_pos) {
                    checkInventoryLockAndExecute(() -> loadFragment(new MiraPosFragment(), "POS"));
                    return true;
                } else if (id == R.id.nav_inventory) {
                    checkInventoryLockAndExecute(() -> loadFragment(new MiraUHFReadTagFragment(), "Scan"));
                    return true;
                } else if (id == R.id.nav_transfers) {
                    checkInventoryLockAndExecute(() -> loadFragment(new MiraTransferFragment(), "Transfers"));
                    return true;
                } else if (id == R.id.nav_more) {
                    showAdminBottomSheet();
                    return true;
                }
                return false;
            });
        } catch (Exception e) {
            Log.e(TAG, "QuickNav Setup Error: " + e.getMessage());
        }
    }

    public void loadFragment(Fragment fragment, String tag) {
        try {
            if (fragment == null) return;
            showLoading();
            currentFragment = fragment;
            fragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, fragment, tag)
                    .commitAllowingStateLoss();

            mainHandler.postDelayed(this::hideLoading, 200);
        } catch (Exception e) {
            Log.e(TAG, "Error switching fragment: " + e.getMessage());
            hideLoading();
        }
    }

    private void showAdminBottomSheet() {
        try {
            BottomSheetDialog bottomSheet = new BottomSheetDialog(this);
            View view = getLayoutInflater().inflate(R.layout.dialog_quick_menu, null);
            bottomSheet.setContentView(view);

            // 1. العمليات والإدارة
            bindMenuAction(view, R.id.btnAdminIdentity, bottomSheet, new MiraIdentityStudioFragment(), "Identity");
            bindMenuAction(view, R.id.btnAdminUsers, bottomSheet, new UsersManagementFragment(), "Users");
            bindMenuAction(view, R.id.btnAdminReports, bottomSheet, new MiraReportsProFragment(), "Reports");
            bindMenuAction(view, R.id.btnAdminSettings, bottomSheet, new UHFSetFragment(), "Settings");

            // 2. إعدادات ومعدات الأجهزة
            bindMenuAction(view, R.id.btnHardwareRfid, bottomSheet, new MiraReaderSetupFragment(), "ReaderSetup");
            bindMenuAction(view, R.id.btnHardwareGate, bottomSheet, new MiraGateSettingsFragment(), "GateSettings");

            bottomSheet.show();
        } catch (Exception e) {
            Log.e(TAG, "Admin BottomSheet Error: " + e.getMessage(), e);
        }
    }

    private void bindMenuAction(View root, int viewId, BottomSheetDialog dialog, Fragment fragment, String tag) {
        View btn = root.findViewById(viewId);
        if (btn != null) {
            btn.setOnClickListener(v -> {
                dialog.dismiss();
                checkInventoryLockAndExecute(() -> loadFragment(fragment, tag));
            });
        }
    }

    private void showHardwareOptionsDialog() {
        String[] options = isArabic ? new String[]{"إعدادات قارئ RFID", "تهيئة البوابات الأمنية", "اختبار التوصيل"}
                : new String[]{"RFID Reader Setup", "Gate Security Setup", "Connection Test"};

        new AlertDialog.Builder(this)
                .setTitle(isArabic ? "📡 أجهزة المنظومة" : "📡 Hardware Control")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) loadFragment(new MiraReaderSetupFragment(), "ReaderSetup");
                    else if (which == 1) loadFragment(new MiraGateSettingsFragment(), "GateSettings");
                    else Toast.makeText(this, isArabic ? "الأجهزة متصلة وتعمل بشكل ممتازة" : "Hardware is online", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    // ==================== INVENTORY LOCK PROTECTION ====================

    public boolean isInventoryActive() {
        if (loopFlag) return true;
        if (currentFragment instanceof MiraUHFReadTagFragment && currentFragment.isVisible()) {
            return ((MiraUHFReadTagFragment) currentFragment).isScanning();
        }
        return false;
    }

    private void checkInventoryLockAndExecute(Runnable onConfirmed) {
        if (isInventoryActive()) {
            new AlertDialog.Builder(this)
                    .setTitle(isArabic ? "⚠️ الجرد قيد التشغيل" : "⚠️ Inventory In Progress")
                    .setMessage(isArabic ? "عملية الجرد تعمل حالياً. هل تريد إيقاف الجرد والانتقال؟"
                            : "Active scanning in progress. Stop and proceed?")
                    .setPositiveButton(isArabic ? "إيقاف والانتقال" : "Stop & Proceed", (dialog, which) -> {
                        stopActiveInventory();
                        if (onConfirmed != null) onConfirmed.run();
                    })
                    .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                    .show();
        } else {
            if (onConfirmed != null) onConfirmed.run();
        }
    }

    private void stopActiveInventory() {
        try {
            loopFlag = false;
            if (currentFragment instanceof MiraUHFReadTagFragment) {
                ((MiraUHFReadTagFragment) currentFragment).stopScanning();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping inventory: " + e.getMessage());
        }
    }

    @Override
    public void onBackPressed() {
        if (isInventoryActive()) {
            checkInventoryLockAndExecute(super::onBackPressed);
            return;
        }
        super.onBackPressed();
    }

    // ==================== RFID & HARDWARE LISTENERS ====================

    public void onTagRead(String epc) {
        try {
            if (activeEncodingFragment != null && activeEncodingFragment.isAdded()) {
                activeEncodingFragment.onRfidScanned(epc);
                return;
            }
            if (activeRfidListener != null) {
                activeRfidListener.onRfidScanned(epc);
                return;
            }
            if (activePOSFragment != null && activePOSFragment.isVisible()) {
                activePOSFragment.onRingScanned(epc);
                return;
            }
            if (currentFragment instanceof MiraUHFReadTagFragment && currentFragment.isVisible()) {
                ((MiraUHFReadTagFragment) currentFragment).onTagScanned(epc, -50.0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Tag Read Processing Error: " + e.getMessage());
        }
    }

    // ==================== SYSTEM & TOOLBAR HELPERS ====================

    private void updateToolbarTitle() {
        try {
            if (getSupportActionBar() != null && sessionManager != null) {
                String displayName = sessionManager.getDisplayName();
                if (displayName == null || displayName.trim().isEmpty()) displayName = sessionManager.getUsername();
                MiraOrgProfileManager orgProfile = MiraOrgProfileManager.getInstance(this);
                String orgName = (orgProfile != null) ? orgProfile.getOrgName() : "MIRA";

                getSupportActionBar().setTitle(orgName + " - " + displayName);
                if (branchesManager != null && branchesManager.hasActiveBranch()) {
                    getSupportActionBar().setSubtitle("📍 " + branchesManager.getActiveBranchDisplayName());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Toolbar Update Error: " + e.getMessage());
        }
    }

    public void showLoading() {
        if (loadingOverlay != null && !isFinishing() && !isDestroyed()) {
            TextView tvTitle = loadingOverlay.findViewById(R.id.tvLoadingTitle);
            if (tvTitle != null) tvTitle.setText(isArabic ? "جاري التحميل..." : "Loading...");
            loadingOverlay.setVisibility(View.VISIBLE);
        }
    }

    public void hideLoading() {
        if (loadingOverlay != null && !isFinishing() && !isDestroyed()) {
            loadingOverlay.setVisibility(View.GONE);
        }
    }

    public void setActivePOSFragment(MiraPosFragment fragment) {
        this.activePOSFragment = fragment;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            View view = getCurrentFocus();
            if (view != null && view instanceof EditText) {
                int[] location = new int[2];
                view.getLocationOnScreen(location);
                float x = ev.getRawX() + view.getLeft() - location[0];
                float y = ev.getRawY() + view.getTop() - location[1];

                if (x < view.getLeft() || x > view.getRight() || y < view.getTop() || y > view.getBottom()) {
                    hideKeyboard(view);
                    view.clearFocus();
                }
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private void hideKeyboard(View view) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    private void initSound() {
        soundPool = new SoundPool(10, AudioManager.STREAM_MUSIC, 5);
        soundMap.put(1, soundPool.load(this, R.raw.barcodebeep, 1));
        soundMap.put(2, soundPool.load(this, R.raw.alarm_breach_security, 2));
        audioManager = (AudioManager) this.getSystemService(AUDIO_SERVICE);
        playSoundThread = new PlaySoundThread();
        playSoundThread.start();
    }

    public void playSound(int id) {
        if (soundPool == null || audioManager == null) return;
        try {
            float max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            float cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            float volumeRatio = cur / max;
            soundPool.play(soundMap.get(id), volumeRatio, volumeRatio, 1, 0, 1);
        } catch (Exception e) {
            Log.e(TAG, "Play Sound Error: " + e.getMessage());
        }
    }

    private void checkReadWritePermission() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, PERMISSION_REQUEST_CODE);
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                List<String> permissions = new ArrayList<>();
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                }
                if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
                }
                if (!permissions.isEmpty()) {
                    requestPermissions(permissions.toArray(new String[0]), PERMISSION_REQUEST_CODE);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Permission Error: " + e.getMessage());
        }
    }

    private void navigateToLogin() {
        try {
            Intent intent = new Intent(this, LoginActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        try {
            stopActiveInventory();
            mainHandler.removeCallbacksAndMessages(null);
            if (playSoundThread != null) playSoundThread.stopPlay();
            if (soundPool != null) soundPool.release();
            if (mReader != null) mReader.free();
        } catch (Exception e) {
            Log.e(TAG, "onDestroy cleanup error: " + e.getMessage());
        }
        super.onDestroy();
    }

    private void safeExecute(Runnable runnable, String errorMessage) {
        try {
            runnable.run();
        } catch (Exception e) {
            Log.e(TAG, errorMessage + ": " + e.getMessage());
        }
    }

    private class PlaySoundThread extends Thread {
        private volatile boolean isStop = false;
        private int interval = 500;
        private long lastPlayTime = SystemClock.elapsedRealtime();

        @Override
        public void run() {
            while (!isStop) {
                synchronized (this) {
                    try {
                        wait(interval);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                if (!isStop && SystemClock.elapsedRealtime() - lastPlayTime < 500) {
                    playSound(1);
                }
            }
        }

        public void stopPlay() {
            isStop = true;
            synchronized (this) {
                notifyAll();
            }
        }
    }
    // ==================== LEGACY HELPER METHODS ====================

    /**
     * عرض رسائل Toast المترجمة من النصوص أو الـ Resources
     */
    public void showToast(String text) {
        if (text == null) return;
        mainHandler.post(() -> Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT).show());
    }

    public void showToast(int resId) {
        mainHandler.post(() -> Toast.makeText(getApplicationContext(), getString(resId), Toast.LENGTH_SHORT).show());
    }

    /**
     * تشغيل الصوت مع تأخير زمني مخصص للفرع القديم
     */
    public void playSoundDelayed(int val) {
        mainHandler.postDelayed(() -> {
            try {
                playSound(val);
            } catch (Exception e) {
                Log.e(TAG, "Error playing delayed sound: " + e.getMessage());
            }
        }, 10);
    }

}
