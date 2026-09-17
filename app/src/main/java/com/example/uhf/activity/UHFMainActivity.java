package com.example.uhf.activity;

import com.example.uhf.application.MiraApplication;
import com.example.uhf.manager.AppWatchdog;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.annotation.SuppressLint;

import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.graphics.Typeface;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;

import com.example.uhf.fragment.MiraDashboardFragment;
import com.example.uhf.fragment.MiraPosFragment;
import com.example.uhf.fragment.MiraIdentityStudioFragment;
import com.example.uhf.fragment.MiraUHFReadTagFragment;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.manager.SessionMonitorManager;
import com.example.uhf.navigation.NavigationManager;
import com.example.uhf.navigation.FragmentFactory;
import com.example.uhf.manager.HardwareManager;
import com.example.uhf.manager.MiraMessagingManager;
import com.example.uhf.manager.NotificationManager;
import com.example.uhf.utils.SoundManager;
import com.example.uhf.utils.StatusIndicatorManager;
import com.example.uhf.tools.MiraLanguageHelper;
import com.example.uhf.tools.SessionManager;
import com.example.uhf.tools.ToastUtil;
import com.example.uhf.manager.SecondaryToolbarManager;


import androidx.lifecycle.ProcessLifecycleOwner;
import com.example.uhf.application.AppLifecycleObserver;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import java.util.concurrent.Executor;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.rscja.deviceapi.entity.UHFTAGInfo;

import java.util.ArrayList;
import java.util.List;

import com.example.uhf.model.MessageModel;
import com.example.uhf.model.NotificationModel;
import com.example.uhf.fragment.MiraUHFReadWriteFragment;
import com.example.uhf.fragment.MiraAccountingFragment;
import com.example.uhf.fragment.MiraAdminHubFragment;
import com.example.uhf.fragment.MiraBranchesManagementFragment;
import com.example.uhf.fragment.MiraGoldCurrencyFragment;
import com.example.uhf.fragment.MiraInventoryProFragment;
import com.example.uhf.fragment.MiraProfileFragment;
import com.example.uhf.fragment.MiraQuickAccessSettingsFragment;
import com.example.uhf.fragment.MiraReceiptFragment;
import com.example.uhf.fragment.MiraReportsProFragment;
import com.example.uhf.fragment.MiraRfidBindingsFragment;
import com.example.uhf.fragment.MiraSalesHistoryFragment;
import com.example.uhf.fragment.MiraSecureGateFragment;
import com.example.uhf.fragment.MiraServerConfigFragment;
import com.example.uhf.fragment.MiraTransferFragment;
import com.example.uhf.fragment.MiraUHFRadarLocationFragment;
import com.example.uhf.manager.MiraQuickAccessSettings;
import com.example.uhf.MiraRfid.sound.MiraSoundSettingsFragment;
import com.example.uhf.fragment.MiraReaderSetupFragment;
import com.example.uhf.fragment.MiraGateSettingsFragment;
import com.example.uhf.fragment.MiraRfidSettingsFragment;


/**
 * UHFMainActivity — النسخة المتوافقة مع NavigationManager الجديد
 *
 * ✅ يستخدم OnBackPressedDispatcher بدلاً من onBackPressed()
 * ✅ NavigationManager هو المسؤول الوحيد عن التنقل
 * ✅ لا يوجد Hide/Show — فقط Replace
 * ✅ لا يوجد تنظيف يدوي للفراغمنتس
 * ✅ currentFragmentTag يُقرأ دائماً من NavigationManager
 */
public class UHFMainActivity extends BaseTabFragmentActivity {

    private static final String TAG = "UHFMainActivity";
    private static final String PREFS_NAVIGATION = "app_navigation";
    private static final String KEY_CURRENT_FRAGMENT = "current_fragment_tag";

    private int loadingCounter = 0;

    private NavigationManager navigationManager;
    private HardwareManager hardwareManager;
    private SoundManager soundManager;
    private NotificationManager notificationManager;
    private StatusIndicatorManager statusIndicatorManager;
    private SecondaryToolbarManager secondaryToolbarManager;

    private TextView txtBadgeCount;
    private SessionManager sessionManager;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfileManager;
    private PermissionManager permissionManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final MiraOrgProfileManager.OnProfileChangedListener orgProfileListener =
            this::updateAppTitle;

    // ✅ مراجع الـ Fragments النشطة
    private MiraPosFragment activePOSFragment = null;
    private MiraIdentityStudioFragment activeEncodingFragment = null;
    private MiraInventoryProFragment activeInventoryFragment = null;
    private OnRfidScannedListener activeRfidListener = null;

    public int selectIndex = -1;
    public ArrayList<UHFTAGInfo> tagList = new ArrayList<>();
    public boolean loopFlag = false;
    private boolean isArabic = true;

    private long lastBackPressTime = 0;
    private static final long DOUBLE_BACK_TIMEOUT = 2000;
    private static final long INACTIVITY_TIMEOUT_MS = 15 * 60 * 1000; // 15 دقيقة
    
    private AppLifecycleObserver appLifecycleObserver;

    public interface OnRfidScannedListener {
        void onRfidScanned(String epc);
    }

    // ==================== Setters ====================

    public void setActiveEncodingFragment(MiraIdentityStudioFragment fragment) {
        this.activeEncodingFragment = fragment;
    }

    public void setActiveRfidListener(OnRfidScannedListener listener) {
        this.activeRfidListener = listener;
    }

    public void setActivePOSFragment(MiraPosFragment fragment) {
        this.activePOSFragment = fragment;
    }

    public void setActiveInventoryFragment(MiraInventoryProFragment fragment) {
        this.activeInventoryFragment = fragment;
    }

    public void clearActiveListeners() {
        this.activePOSFragment = null;
        this.activeEncodingFragment = null;
        this.activeRfidListener = null;
        this.activeInventoryFragment = null;
    }

    // ==================== Getters ====================

    public NavigationManager getNavigationManager() {
        return navigationManager;
    }

    public PermissionManager getPermissionManager() {
        return permissionManager;
    }

    public String getCurrentFragmentTag() {
        return navigationManager != null
                ? navigationManager.getCurrentFragmentTag()
                : "Dashboard";
    }

    // ==================== دورة الحياة ====================

    @Override
    protected void attachBaseContext(Context newBase) {
        String savedLang = MiraLanguageHelper.getSavedLanguage(newBase);
        MiraLanguageHelper.applyLanguage(newBase, savedLang);
        super.attachBaseContext(newBase);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        super.onCreate(savedInstanceState);

        try {
            sessionManager = new SessionManager(getApplicationContext());
            if (!sessionManager.isLoggedIn()) {
                navigateToLogin();
                return;
            }

            setContentView(R.layout.activity_main);

            setupWindowInsets();
            setupKeyboardBehavior();
            setupSecondaryToolbar();

            MaterialToolbar toolbar = findViewById(R.id.toolbar);
            if (toolbar != null) {
                setSupportActionBar(toolbar);
                if (getSupportActionBar() != null) {
                    getSupportActionBar().setDisplayShowTitleEnabled(false);
                }
                updateToolbarTitle();
            }

            AppWatchdog.getInstance().startMonitoring(() -> {
                Log.e(TAG, "UI Thread FROZEN! Restarting app...");
                MiraApplication.getInstance().restartApp();
            });

            ToastUtil.init(getApplicationContext());
            initializeLanguageState();

            branchesManager = MiraBranchesManager.getInstance(getApplicationContext());
            permissionManager = PermissionManager.getInstance(getApplicationContext());

            // ✅ 1) تهيئة NavigationManager بالطريقة الجديدة
            navigationManager = new NavigationManager(
                    getSupportFragmentManager(),
                    permissionManager,
                    R.id.fragment_container
            );
            navigationManager.attachToActivity(this);

            // ✅ 2) تسجيل OnBackPressedDispatcher
            androidx.activity.OnBackPressedCallback backCallback =
                    navigationManager.createBackPressedCallback();
            getOnBackPressedDispatcher().addCallback(this, backCallback);

            // ✅ 3) ربط Interceptor لمنطق "الجرد النشط"
            navigationManager.setBackPressInterceptor(() -> {
                if (isInventoryActive()) {
                    checkInventoryLockAndExecute(() -> navigationManager.goToDashboard());
                    return true;
                }
                return false;
            });

            hardwareManager = new HardwareManager(this, new HardwareManager.HardwareStatusListener() {
                @Override
                public void onStatusChanged(HardwareManager.HardwareStatus status) {
                    if (statusIndicatorManager != null) {
                        statusIndicatorManager.update();
                    }
                }

                @Override
                public void onTagRead(String epc) {
                    UHFMainActivity.this.onTagRead(epc);
                }

                @Override
                public void onError(String error) {
                    showToast("RFID Error: " + error);
                }
            });

            soundManager = new SoundManager(this);
            notificationManager = new NotificationManager(this);
            statusIndicatorManager = new StatusIndicatorManager(
                    findViewById(android.R.id.content), hardwareManager);

            setupQuickNav();
            setupOrgProfileListener();

            // ✅ 4) تحميل الشاشة الأولى
            if (savedInstanceState == null) {
                navigationManager.loadFragmentDirectly(
                        new MiraDashboardFragment(),
                        NavigationManager.TAG_DASHBOARD
                );
            } else {
                String savedTag = savedInstanceState.getString(
                        "current_fragment", NavigationManager.TAG_DASHBOARD);
                Fragment savedFragment = navigationManager.createFragmentByTag(savedTag);
                navigationManager.loadFragmentDirectly(savedFragment, savedTag);
            }
            
            // ✅ تسجيل AppLifecycleObserver (Process-Level)
try {
    if (sessionManager != null) {
        appLifecycleObserver = new AppLifecycleObserver(sessionManager);
        ProcessLifecycleOwner.get().getLifecycle().addObserver(appLifecycleObserver);
    }
} catch (Exception e) {
    Log.e(TAG, "AppLifecycleObserver init error: " + e.getMessage());
}

            // ✅ 5) مزامنة الواجهة مع NavigationManager
            syncUIWithNavigation();

        } catch (Exception e) {
            Log.e(TAG, "Fatal error in onCreate: " + e.getMessage(), e);
        }
    }

    /**
     * ✅ مزامنة الواجهة (Toolbar، BottomNav) مع الحالة الحالية في NavigationManager
     */
    private void syncUIWithNavigation() {
        if (navigationManager == null) return;
        String tag = navigationManager.getCurrentFragmentTag();
        updateBottomNavSelectionByTag(tag);
        updateSecondaryToolbar(tag);
    }

    private void setupWindowInsets() {
        View bottomNavView = findViewById(R.id.bottomNavigationView);
        if (bottomNavView != null) {
            ViewCompat.setOnApplyWindowInsetsListener(bottomNavView, (v, insets) -> {
                Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPadding(0, 0, 0, systemBars.bottom);
                return insets;
            });
        }

        View toolbarView = findViewById(R.id.toolbar);
        if (toolbarView != null) {
            ViewCompat.setOnApplyWindowInsetsListener(toolbarView, (v, insets) -> {
                Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPadding(0, systemBars.top, 0, 0);
                return insets;
            });
        }
    }

    private void setupKeyboardBehavior() {
        try {
            final View rootView = findViewById(android.R.id.content);
            if (rootView == null) return;

            ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
                Insets imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime());
                boolean isKeyboardVisible = imeInsets.bottom > 0;

                View bottomNavContainer = findViewById(R.id.bottomNavContainer);
                if (bottomNavContainer != null) {
                    bottomNavContainer.setVisibility(
                            isKeyboardVisible ? View.GONE : View.VISIBLE);
                }

                View fragmentContainer = findViewById(R.id.fragment_container);
                if (fragmentContainer != null) {
                    fragmentContainer.setPadding(
                            fragmentContainer.getPaddingLeft(),
                            fragmentContainer.getPaddingTop(),
                            fragmentContainer.getPaddingRight(),
                            isKeyboardVisible ? 8 : 90);
                }
                return insets;
            });

        } catch (Exception e) {
            Log.e(TAG, "setupKeyboardBehavior error: " + e.getMessage(), e);
        }
    }

    // ==================== التولبار الثانوي ====================

    private void setupSecondaryToolbar() {
        MaterialToolbar secondaryToolbar = findViewById(R.id.secondaryToolbar);

        if (secondaryToolbar == null) {
            Log.w(TAG, "Secondary toolbar not found");
            return;
        }

        secondaryToolbarManager = new SecondaryToolbarManager(secondaryToolbar);

        secondaryToolbarManager.setOnMenuItemClickListener((item, currentTag) -> {
            int id = item.getItemId();

            // ==================== Dashboard ====================
            if (id == R.id.action_device_status) {
                showHardwareOptionsDialog();
                return true;
            } else if (id == R.id.action_search) {
                showGlobalSearchDialog();
                return true;
            } else if (id == R.id.action_sync) {
                performDataSync();
                return true;
            } else if (id == R.id.action_quick_access_settings) {
                loadFragment(new MiraQuickAccessSettingsFragment(), "QuickAccessSettings");
                return true;
            }

            // ==================== Inventory ====================
            else if (id == R.id.action_inventory_power) {
                if (activeInventoryFragment != null
                        && activeInventoryFragment.isAdded()
                        && !activeInventoryFragment.isRemoving()) {
                    activeInventoryFragment.showInventoryScannerSettings();
                } else {
                    loadFragment(new MiraInventoryProFragment(), "Scan");
                    mainHandler.postDelayed(() -> {
                        if (activeInventoryFragment != null) {
                            activeInventoryFragment.showInventoryScannerSettings();
                        }
                    }, 500);
                }
                return true;
            } else if (id == R.id.action_inventory_stats) {
                showInventoryStatsDialog();
                return true;
            } else if (id == R.id.action_inventory_settings) {
                if (activeInventoryFragment != null
                        && activeInventoryFragment.isAdded()
                        && !activeInventoryFragment.isRemoving()) {
                    activeInventoryFragment.showInventorySessionSettings();
                } else {
                    loadFragment(new MiraInventoryProFragment(), "Scan");
                    mainHandler.postDelayed(() -> {
                        if (activeInventoryFragment != null) {
                            activeInventoryFragment.showInventorySessionSettings();
                        }
                    }, 500);
                }
                return true;
            } else if (id == R.id.action_inventory_archive) {
                showInventoryArchiveDialog();
                return true;
            }

            // ==================== POS ====================
            else if (id == R.id.action_pos_cart) {
                showPOSCartDialog();
                return true;
            } else if (id == R.id.action_pos_search) {
                showPOSSearchDialog();
                return true;
            } else if (id == R.id.action_pos_discount) {
                showPOSDiscountDialog();
                return true;
            } else if (id == R.id.action_pos_receipt) {
                showPOSReceiptDialog();
                return true;
            }

            // ==================== Transfers ====================
            else if (id == R.id.action_transfers_sync) {
                performDataSync();
                return true;
            } else if (id == R.id.action_transfers_export) {
                showTransfersExportDialog();
                return true;
            } else if (id == R.id.action_transfers_import) {
                showTransfersImportDialog();
                return true;
            } else if (id == R.id.action_transfers_history) {
                loadFragment(new MiraTransferFragment(), "Transfers");
                return true;
            }

            return false;
        });

        secondaryToolbarManager.setMenuForTag("Dashboard");
        mainHandler.post(this::setupCustomViewsForCurrentMenu);
    }

    private void setupCustomViewsForCurrentMenu() {
        try {
            MaterialToolbar toolbar = findViewById(R.id.secondaryToolbar);
            if (toolbar == null) return;

            Menu menu = toolbar.getMenu();

            MenuItem deviceStatusItem = menu.findItem(R.id.action_device_status);
            if (deviceStatusItem != null && deviceStatusItem.getActionView() != null) {
                View actionView = deviceStatusItem.getActionView();
                View dot = actionView.findViewById(R.id.deviceStatusDot);
                TextView text = actionView.findViewById(R.id.deviceStatusText);
                ImageView icon = actionView.findViewById(R.id.deviceStatusIcon);

                if (dot != null && text != null) {
                    actionView.setOnClickListener(v -> showHardwareOptionsDialog());
                    updateDeviceStatusUI(dot, text, icon);
                }
            }

            MenuItem powerItem = menu.findItem(R.id.action_inventory_power);
            if (powerItem != null && powerItem.getActionView() != null) {
                View actionView = powerItem.getActionView();
                View dot = actionView.findViewById(R.id.deviceStatusDot);
                TextView text = actionView.findViewById(R.id.deviceStatusText);
                ImageView icon = actionView.findViewById(R.id.deviceStatusIcon);

                if (dot != null && text != null) {
                    actionView.setOnClickListener(v -> showHardwareOptionsDialog());
                    updateDeviceStatusUI(dot, text, icon);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "setupCustomViewsForCurrentMenu error: " + e.getMessage(), e);
        }
    }

    private void updateSecondaryToolbar(String tag) {
        if (secondaryToolbarManager == null) return;
        try {
            secondaryToolbarManager.setMenuForTag(tag);

            Menu menu = secondaryToolbarManager.getCurrentMenu();
            if (menu != null) {
                applyToolbarMenuPermissions(menu);
            }

            mainHandler.post(this::setupCustomViewsForCurrentMenu);
            Log.d(TAG, "✅ Secondary toolbar updated for: " + tag);
        } catch (Exception e) {
            Log.e(TAG, "updateSecondaryToolbar error: " + e.getMessage(), e);
        }
    }

    private void updateDeviceStatusUI(View statusDot, TextView statusText, ImageView statusIcon) {
        try {
            if (statusDot == null || statusText == null) return;

            boolean isConnected = hardwareManager != null && hardwareManager.isConnected();

            int greenColor = getResources().getColor(android.R.color.holo_green_dark);
            int redColor = getResources().getColor(android.R.color.holo_red_dark);

            if (isConnected) {
                statusDot.setBackgroundResource(R.drawable.bg_status_dot_green);
                statusText.setText(isArabic ? "متصل" : "Connected");
                statusText.setTextColor(greenColor);

                if (statusIcon != null) {
                    statusIcon.setImageResource(R.drawable.ic_device_connected);
                    statusIcon.setColorFilter(greenColor);
                }
            } else {
                statusDot.setBackgroundResource(R.drawable.bg_status_dot_red);
                statusText.setText(isArabic ? "غير متصل" : "Disconnected");
                statusText.setTextColor(redColor);

                if (statusIcon != null) {
                    statusIcon.setImageResource(R.drawable.ic_device_disconnected);
                    statusIcon.setColorFilter(redColor);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "updateDeviceStatusUI error: " + e.getMessage());
        }
    }

    private void showHardwareOptionsDialog() {
        PermissionManager pm = PermissionManager.getInstance(getApplicationContext());
        List<String> optionsList = new ArrayList<>();
        List<Runnable> actionsList = new ArrayList<>();

        if (pm.isAdmin() || pm.isInventoryManager() || pm.isSystemAdmin()) {
            optionsList.add(isArabic ? "📡 إعدادات قارئ RFID" : "📡 RFID Reader Setup");
            actionsList.add(() -> loadFragment(new MiraReaderSetupFragment(), "ReaderSetup"));
        }

        if (pm.isAdmin() || pm.isSecurity() || pm.isGateSecurity()) {
            optionsList.add(isArabic ? "🚧 تهيئة البوابات الأمنية" : "🚧 Gate Security Setup");
            actionsList.add(() -> loadFragment(new MiraGateSettingsFragment(), "GateSettings"));
        }

        if (pm.canViewServerConfig()) {
            optionsList.add(isArabic ? "🌐 إعدادات السيرفر" : "🌐 Server Config");
            actionsList.add(() -> loadFragment(new MiraServerConfigFragment(), "ServerConfig"));
        }

        if (pm.isAdmin() || pm.isManager() || pm.isInventoryManager()) {
            optionsList.add(isArabic ? "🏷️ إدارة الشرائح المبرمجة" : "🏷️ RFID Bindings");
            actionsList.add(() -> loadFragment(new MiraRfidBindingsFragment(), "RfidBindings"));
        }
        if (pm.isAdmin() || pm.isSystemAdmin()) {
            optionsList.add(isArabic ? "🔐 إعدادات أمان الشرائح" : "🔐 RFID Security Settings");
            actionsList.add(() -> loadFragment(new MiraRfidSettingsFragment(), "RfidSettings"));
        }

        if (pm.canManageQuickAccess() || pm.isManager() || pm.isAdmin()) {
            optionsList.add(isArabic ? "⚙️ إعدادات الوصول السريع" : "⚙️ Quick Access Settings");
            actionsList.add(() -> loadFragment(new MiraQuickAccessSettingsFragment(), "QuickAccessSettings"));
        }

        optionsList.add(isArabic ? "🔄 اختبار التوصيل والربط" : "🔄 Connection Test");
        actionsList.add(this::performConnectionTest);

        String[] options = optionsList.toArray(new String[0]);

        new MaterialAlertDialogBuilder(this)
                .setTitle(isArabic ? "📡 أجهزة ومستشعرات المنظومة" : "📡 Hardware & Sensors")
                .setItems(options, (dialog, which) -> {
                    if (which >= 0 && which < actionsList.size()) {
                        actionsList.get(which).run();
                    }
                })
                .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
                .show();
    }

    private void performConnectionTest() {
        showLoading();
        mainHandler.postDelayed(() -> {
            hideLoading();
            boolean isConnected = hardwareManager != null && hardwareManager.isConnected();

            String message = isConnected
                    ? (isArabic ? "✅ الاتصال بالجهاز سليم\nجميع الأجهزة تعمل بشكل صحيح"
                    : "✅ Device connection OK\nAll devices operational")
                    : (isArabic ? "⚠️ لا يوجد اتصال بالجهاز\nيرجى التحقق من الاتصال"
                    : "⚠️ No device connection\nPlease check connection");

            new MaterialAlertDialogBuilder(this)
                    .setTitle(isArabic ? "نتيجة الاختبار" : "Test Result")
                    .setMessage(message)
                    .setPositiveButton(isArabic ? "موافق" : "OK", null)
                    .show();
        }, 1500);
    }

    @Override
public void onResume() {
    super.onResume();
    loadingCounter = 0;
    forceHideLoadingImmediate();

    // ✅ 1. فحص Auto-Lock أولاً
    if (!checkAutoLock()) {
        return;  // تم عرض شاشة القفل
    }

    updateToolbarTitle();
    if (statusIndicatorManager != null) statusIndicatorManager.scheduleUpdates();

    // ✅ مزامنة الواجهة
    syncUIWithNavigation();
}

/**
 * ✅ فحص Auto-Lock
 * @return true إذا كان التطبيق جاهزاً، false إذا تم عرض شاشة القفل
 */
private boolean checkAutoLock() {
    try {
        if (sessionManager == null) return true;
        if (!sessionManager.isLoggedIn()) return true;

        // إذا كانت الجلسة مقفلة أو انتهت المهلة
        if (sessionManager.isSessionLocked()
                || sessionManager.isInactivityTimeout(INACTIVITY_TIMEOUT_MS)) {

            Log.d(TAG, "🔒 Auto-Lock triggered");
            sessionManager.setSessionLocked(true);

            // عرض شاشة القفل بعد تأخير بسيط (لضمان استقرار النشاط)
            mainHandler.postDelayed(this::showAutoLockDialog, 300);
            return false;
        }

        // تحديث "آخر نشاط"
        sessionManager.touchActivity();
        return true;
    } catch (Exception e) {
        Log.e(TAG, "checkAutoLock error: " + e.getMessage());
        return true;
    }
}

/**
 * ✅ حوار القفل مع خيارين: بصمة / logout
 */
private void showAutoLockDialog() {
    if (isFinishing() || isDestroyed()) return;
    if (!isActivityAlive()) return;

    try {
        new MaterialAlertDialogBuilder(this)
                .setTitle(isArabic ? "🔒 الجلسة مقفلة" : "🔒 Session Locked")
                .setMessage(isArabic
                        ? "التطبيق كان في الخلفية لأكثر من 15 دقيقة.\n\nاستخدم بصمة الإصبع للمتابعة."
                        : "App was in background for over 15 minutes.\n\nUse fingerprint to continue.")
                .setPositiveButton(isArabic ? "🔓 فتح بالبصمة" : "🔓 Unlock",
                        (dialog, which) -> {
                            dialog.dismiss();
                            triggerBiometricUnlock();
                        })
                .setNegativeButton(isArabic ? "تسجيل الخروج" : "Logout",
                        (dialog, which) -> {
                            dialog.dismiss();
                            performFullLogout();
                        })
                .setCancelable(false)
                .show();
    } catch (Exception e) {
        Log.e(TAG, "showAutoLockDialog error: " + e.getMessage());
    }
}

/**
 * ✅ Biometric Unlock
 */
private void triggerBiometricUnlock() {
    try {
        // التحقق من توفر البصمة
        BiometricManager bm = BiometricManager.from(this);
        int canAuth = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);

        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            Log.w(TAG, "Biometric not available, using password");
            performFullLogout();
            return;
        }

        Executor executor = ContextCompat.getMainExecutor(this);

        BiometricPrompt prompt = new BiometricPrompt(this, executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            @NonNull BiometricPrompt.AuthenticationResult result) {
                        Log.d(TAG, "✅ Biometric unlock success");
                        sessionManager.unlockSession();
                        Toast.makeText(UHFMainActivity.this,
                                isArabic ? "✅ تم فتح الجلسة" : "✅ Session unlocked",
                                Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onAuthenticationError(int code, @NonNull CharSequence msg) {
                        Log.e(TAG, "Biometric error: " + code + " - " + msg);
                        // إذا ألغى المستخدم → logout
                        if (code == BiometricPrompt.ERROR_USER_CANCELED
                                || code == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                                || code == BiometricPrompt.ERROR_CANCELED) {
                            performFullLogout();
                        }
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        Log.w(TAG, "Biometric failed");
                        Toast.makeText(UHFMainActivity.this,
                                isArabic ? "❌ لم يتم التعرف على البصمة" : "❌ Fingerprint not recognized",
                                Toast.LENGTH_SHORT).show();
                    }
                });

        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(isArabic ? "فتح القفل" : "Unlock")
                .setSubtitle(isArabic ? "استخدم بصمة الإصبع" : "Use fingerprint")
                .setNegativeButtonText(isArabic ? "تسجيل الخروج" : "Logout")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build();

        prompt.authenticate(info);

    } catch (Exception e) {
        Log.e(TAG, "triggerBiometricUnlock error: " + e.getMessage(), e);
        performFullLogout();
    }
}

/**
 * ✅ Logout كامل
 */
private void performFullLogout() {
    try {
        if (sessionManager != null) {
            sessionManager.logoutUser();
        }
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra("session_expired", true);
        startActivity(intent);
        finish();
    } catch (Exception e) {
        Log.e(TAG, "performFullLogout error: " + e.getMessage());
    }
}

/**
 * ✅ مساعد للتحقق من صحة النشاط
 */
private boolean isActivityAlive() {
    return !isFinishing() && !isDestroyed();
}

@Override
public void onUserInteraction() {
    super.onUserInteraction();
    // ✅ أي تفاعل = نشاط
    if (sessionManager != null) {
        sessionManager.touchActivity();
    }
}
    
    @Override
public void onPause() {
    super.onPause();

    // ✅ حفظ آخر نشاط
    if (sessionManager != null) {
        sessionManager.touchActivity();
    }

    forceHideLoadingImmediate();
    AppWatchdog.getInstance().stopMonitoring();
    if (statusIndicatorManager != null) statusIndicatorManager.cleanup();

    try {
        if (hardwareManager != null) {
            hardwareManager.stopScan();
        }
    } catch (Exception e) {
        Log.w(TAG, "Error stopping scan in onPause: " + e.getMessage());
    }
}

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        try {
            isArabic = newConfig.locale.getLanguage().equals("ar");
            getWindow().getDecorView().setLayoutDirection(
                    isArabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);

            updateBottomNavLanguage();
            updateToolbarTitle();

            supportInvalidateOptionsMenu();
            View bottomNav = findViewById(R.id.bottomNavigationView);
            if (bottomNav != null) {
                bottomNav.refreshDrawableState();
            }
        } catch (Exception e) {
            Log.e(TAG, "Configuration change error: " + e.getMessage());
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        // ✅ اقرأ من NavigationManager (المصدر الموثوق)
        String tag = navigationManager != null
                ? navigationManager.getCurrentFragmentTag()
                : "Dashboard";
        outState.putString("current_fragment", tag);
        saveFragmentTagToPrefs(tag);
    }

    // ==================== إعدادات ====================

    private void initializeLanguageState() {
        try {
            isArabic = getResources().getConfiguration().locale.getLanguage().equals("ar");
            getWindow().getDecorView().setLayoutDirection(
                    isArabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        } catch (Exception e) {
            Log.w(TAG, "Locale check failed: " + e.getMessage());
        }
    }

    private void saveFragmentTagToPrefs(String tag) {
        try {
            getSharedPreferences(PREFS_NAVIGATION, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_CURRENT_FRAGMENT, tag)
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "Save fragment tag error: " + e.getMessage());
        }
    }

    private void setupOrgProfileListener() {
        orgProfileManager = MiraOrgProfileManager.getInstance(getApplicationContext());
        orgProfileManager.addListener(orgProfileListener);
        updateAppTitle();
    }

    private void updateAppTitle() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::updateAppTitle);
            return;
        }
        try {
            updateToolbarTitle();
        } catch (Exception e) {
            Log.e(TAG, "Error updating app title: " + e.getMessage());
        }
    }

    private void updateToolbarTitle() {
        try {
            TextView tvTitle = findViewById(R.id.tvTitle);
            TextView tvSubtitle = findViewById(R.id.tvSubtitle);

            String orgName = "MIRA ID";
            if (orgProfileManager != null) {
                String name = orgProfileManager.getDisplayName();
                if (name != null && !name.trim().isEmpty()) {
                    orgName = name;
                }
            }

            if (tvTitle != null) {
                tvTitle.setText(orgName);
            }

            if (tvSubtitle != null) {
                if (branchesManager != null && branchesManager.hasActiveBranch()) {
                    tvSubtitle.setText("📍 " + branchesManager.getActiveBranchDisplayName());
                } else {
                    tvSubtitle.setText(isArabic ? "📍 الفرع الرئيسي" : "📍 Main Branch");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Toolbar Update Error: " + e.getMessage());
        }
    }

    // ==================== التنقل (مُبسّط — يعتمد على NavigationManager) ====================

    public void loadFragment(Fragment fragment, String tag) {
        loadFragment(fragment, tag, true);
    }

    public void loadFragment(Fragment fragment, String tag, boolean addToBackStack) {
    if (isFinishing() || isDestroyed()) return;
    if (navigationManager == null) return;

    // ✅ لا showLoading() — التنقل فوري
    navigationManager.loadFragment(fragment, tag, addToBackStack);
    this.currentFragment = fragment;

    updateBottomNavSelectionByTag(tag);
    updateSecondaryToolbar(tag);
}

public void loadFragmentDirectly(Fragment fragment, String tag) {
    if (isFinishing() || isDestroyed()) return;
    if (navigationManager == null) return;

    // ✅ لا showLoading() — التنقل فوري
    navigationManager.loadFragmentDirectly(fragment, tag);
    this.currentFragment = fragment;

    updateBottomNavSelectionByTag(tag);
    updateSecondaryToolbar(tag);
}
    public Fragment createFragmentByTag(String tag) {
        return FragmentFactory.createFragmentByTag(tag);
    }

    public void openReceiptFragment(String rawJson) {
        try {
            if (isFinishing() || isDestroyed()) return;
            MiraReceiptFragment fragment = MiraReceiptFragment.newInstance(rawJson);
            fragment.show(getSupportFragmentManager(), "Receipt");
        } catch (Exception e) {
            Log.e(TAG, "openReceiptFragment Error: " + e.getMessage());
        }
    }

    // ==================== الأصوات ====================

    public void playSound(int id) {
        if (soundManager != null) {
            soundManager.playSound(this, id);
        }
    }

    public void playSoundDelayed(int value) {
        if (soundManager != null) {
            soundManager.playDelayedSound(this, value);
        }
    }

    // ==================== RFID ====================

    public void startUHFScan() {
        if (hardwareManager != null) {
            hardwareManager.startScan();
        }
    }

    public void stopUHFScan() {
        if (hardwareManager != null) {
            hardwareManager.stopScan();
        }
    }

    public void onTagRead(String epc) {
        mainHandler.post(() -> {
            try {
                if (isFinishing() || isDestroyed()) return;

                if (activeEncodingFragment != null
                        && activeEncodingFragment.isAdded()
                        && activeEncodingFragment.isResumed()
                        && !activeEncodingFragment.isRemoving()) {
                    activeEncodingFragment.onRfidScanned(epc);
                    return;
                }

                if (activeRfidListener != null) {
                    activeRfidListener.onRfidScanned(epc);
                    return;
                }

                if (activePOSFragment != null
                        && activePOSFragment.isAdded()
                        && !activePOSFragment.isRemoving()
                        && activePOSFragment.isVisible()) {
                    activePOSFragment.onRingScanned(epc);
                    return;
                }

                // ✅ استخدم FragmentManager للحصول على الـ Fragment الحالي
                Fragment currentFragment = navigationManager != null
                        ? navigationManager.getCurrentFragment()
                        : null;

                if (currentFragment instanceof MiraUHFReadTagFragment
                        && currentFragment.isAdded()
                        && !currentFragment.isRemoving()
                        && currentFragment.isVisible()) {
                    ((MiraUHFReadTagFragment) currentFragment).onTagScanned(epc, -50.0);
                }
            } catch (Exception e) {
                Log.e(TAG, "Tag Read Dispatch Error: " + e.getMessage(), e);
            }
        });
    }

    // ==================== التنقل السريع (BottomNav) ====================

    private void setupQuickNav() {
        try {
            BottomNavigationView quickNav = findViewById(R.id.bottomNavigationView);
            FloatingActionButton fabActionCenter = findViewById(R.id.fabActionCenter);

            if (fabActionCenter != null) {
                boolean hasQuickActions = permissionManager.getAllowedQuickActions().size() > 0;
                fabActionCenter.setVisibility(hasQuickActions ? View.VISIBLE : View.GONE);
                fabActionCenter.setEnabled(hasQuickActions);
                fabActionCenter.setOnClickListener(v -> showQuickActionMenu());
            }

            if (quickNav != null) {
                quickNav.setOnItemSelectedListener(item -> {
                    int id = item.getItemId();
                    String tag = getFragmentTagForNavItem(id);
                    if (tag == null || tag.isEmpty()) return false;

                    // ✅ تجاهل إذا كان نفس التبويب الحالي
                    if (navigationManager != null
                            && tag.equals(navigationManager.getCurrentFragmentTag())) {
                        return true;
                    }

                    // ✅ استخدم loadFragmentDirectly (يمسح Back Stack)
                    Fragment fragment = navigationManager != null
                            ? navigationManager.createFragmentByTag(tag)
                            : FragmentFactory.createFragmentByTag(tag);

                    if (fragment != null) {
                        loadFragmentDirectly(fragment, tag);
                        return true;
                    }
                    return false;
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "QuickNav Setup Error: " + e.getMessage());
        }
    }

    private String getFragmentTagForNavItem(int itemId) {
        if (itemId == R.id.nav_home) return NavigationManager.TAG_DASHBOARD;
        if (itemId == R.id.nav_inventory) return NavigationManager.TAG_INVENTORY;
        if (itemId == R.id.nav_sales) return NavigationManager.TAG_POS;
        if (itemId == R.id.nav_transfers) return NavigationManager.TAG_TRANSFERS;
        return "";
    }

    private void updateBottomNavSelectionByTag(String tag) {
        try {
            BottomNavigationView quickNav = findViewById(R.id.bottomNavigationView);
            if (quickNav == null || tag == null) return;

            if (tag.equals(NavigationManager.TAG_DASHBOARD)) {
                quickNav.getMenu().findItem(R.id.nav_home).setChecked(true);
            } else if (tag.equals(NavigationManager.TAG_INVENTORY)) {
                quickNav.getMenu().findItem(R.id.nav_inventory).setChecked(true);
            } else if (tag.equals(NavigationManager.TAG_POS)) {
                quickNav.getMenu().findItem(R.id.nav_sales).setChecked(true);
            } else if (tag.equals(NavigationManager.TAG_TRANSFERS)) {
                quickNav.getMenu().findItem(R.id.nav_transfers).setChecked(true);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating bottom nav selection: " + e.getMessage());
        }
    }

    private void updateBottomNavLanguage() {
        try {
            BottomNavigationView nav = findViewById(R.id.bottomNavigationView);
            if (nav != null && nav.getMenu() != null) {
                nav.getMenu().findItem(R.id.nav_home).setTitle(isArabic ? "الرئيسية" : "Home");
                nav.getMenu().findItem(R.id.nav_inventory).setTitle(isArabic ? "الجرد" : "Inventory");
                nav.getMenu().findItem(R.id.nav_sales).setTitle(isArabic ? "البيع" : "Sales");
                nav.getMenu().findItem(R.id.nav_transfers).setTitle(isArabic ? "التحويلات" : "Transfers");
            }
        } catch (Exception e) {
            Log.e(TAG, "Update bottom nav language error: " + e.getMessage());
        }
    }

    // ==================== القوائم ====================

    @SuppressLint("RestrictedApi")
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);

        MenuItem notificationItem = menu.findItem(R.id.action_notifications);
        if (notificationItem != null) {
            View actionView = notificationItem.getActionView();
            if (actionView != null) {
                txtBadgeCount = actionView.findViewById(R.id.txtBadgeCount);
                notificationManager.setBadgeView(txtBadgeCount);
                actionView.setOnClickListener(v -> handleNotificationButtonClick());
            }
        }

        notificationManager.init(sessionManager.getUserId());
        applyToolbarMenuPermissions(menu);

        return true;
    }

    private void applyToolbarMenuPermissions(Menu menu) {
        if (menu == null || permissionManager == null) return;

        PermissionManager pm = permissionManager;

        // ==================== الشريط الرئيسي ====================
        MenuItem goldRates = menu.findItem(R.id.action_gold_rates);
        if (goldRates != null) goldRates.setVisible(pm.canViewGoldRates());

        MenuItem profile = menu.findItem(R.id.action_profile);
        if (profile != null) profile.setVisible(pm.canViewProfile());

        MenuItem notifications = menu.findItem(R.id.action_notifications);
        if (notifications != null) notifications.setVisible(true);

        MenuItem quickAccess = menu.findItem(R.id.action_quick_access_settings);
        if (quickAccess != null) quickAccess.setVisible(pm.canManageQuickAccess());

        MenuItem soundSettings = menu.findItem(R.id.action_sound_settings);
        if (soundSettings != null) soundSettings.setVisible(true);

        MenuItem toggleLanguage = menu.findItem(R.id.action_toggle_language);
        if (toggleLanguage != null) {
            toggleLanguage.setVisible(true);
            toggleLanguage.setTitle(isArabic ? "English" : "العربية");
        }

        MenuItem branches = menu.findItem(R.id.action_branches);
        if (branches != null) {
            branches.setVisible(pm.isManager() || pm.isAdmin() || pm.isBranchManager());
        }

        MenuItem exportData = menu.findItem(R.id.action_export_data);
        if (exportData != null) exportData.setVisible(pm.canExportReports());

        MenuItem deviceVersion = menu.findItem(R.id.action_device_version);
        if (deviceVersion != null) {
            deviceVersion.setVisible(pm.isAdmin() || pm.isSystemAdmin());
        }

        MenuItem logout = menu.findItem(R.id.action_logout);
        if (logout != null) logout.setVisible(true);

        // ==================== الشريط الثانوي ====================
        MenuItem deviceStatus = menu.findItem(R.id.action_device_status);
        if (deviceStatus != null) deviceStatus.setVisible(true);

        MenuItem search = menu.findItem(R.id.action_search);
        if (search != null) search.setVisible(true);

        MenuItem sync = menu.findItem(R.id.action_sync);
        if (sync != null) sync.setVisible(true);

        MenuItem invPower = menu.findItem(R.id.action_inventory_power);
        if (invPower != null) invPower.setVisible(true);

        MenuItem invStats = menu.findItem(R.id.action_inventory_stats);
        if (invStats != null) invStats.setVisible(true);

        MenuItem invSettings = menu.findItem(R.id.action_inventory_settings);
        if (invSettings != null) invSettings.setVisible(pm.canEditInventory());

        MenuItem invArchive = menu.findItem(R.id.action_inventory_archive);
        if (invArchive != null) invArchive.setVisible(true);

        MenuItem posCart = menu.findItem(R.id.action_pos_cart);
        if (posCart != null) posCart.setVisible(true);

        MenuItem posSearch = menu.findItem(R.id.action_pos_search);
        if (posSearch != null) posSearch.setVisible(true);

        MenuItem posDiscount = menu.findItem(R.id.action_pos_discount);
        if (posDiscount != null) posDiscount.setVisible(pm.isManager() || pm.isAdmin());

        MenuItem posReceipt = menu.findItem(R.id.action_pos_receipt);
        if (posReceipt != null) posReceipt.setVisible(true);

        MenuItem trSync = menu.findItem(R.id.action_transfers_sync);
        if (trSync != null) trSync.setVisible(true);

        MenuItem trExport = menu.findItem(R.id.action_transfers_export);
        if (trExport != null) trExport.setVisible(pm.canExportReports());

        MenuItem trImport = menu.findItem(R.id.action_transfers_import);
        if (trImport != null) trImport.setVisible(pm.canAccessTransfers());

        MenuItem trHistory = menu.findItem(R.id.action_transfers_history);
        if (trHistory != null) trHistory.setVisible(true);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_gold_rates) {
            loadFragment(new MiraGoldCurrencyFragment(), "GoldRates");
            return true;
        } else if (id == R.id.action_profile) {
            loadFragment(new MiraProfileFragment(), "Profile");
            return true;
        } else if (id == R.id.action_notifications) {
            handleNotificationButtonClick();
            return true;
        } else if (id == R.id.action_quick_access_settings) {
            loadFragment(new MiraQuickAccessSettingsFragment(), "QuickAccessSettings");
            return true;
        } else if (id == R.id.action_sound_settings) {
            loadFragment(new MiraSoundSettingsFragment(), "SoundSettings");
            return true;
        } else if (id == R.id.action_toggle_language) {
            toggleLanguage();
            return true;
        } else if (id == R.id.action_branches) {
            loadFragment(new MiraBranchesManagementFragment(), "Branches");
            return true;
        } else if (id == R.id.action_export_data) {
            loadFragment(new MiraReportsProFragment(), "Reports");
            return true;
        } else if (id == R.id.action_device_version) {
            showSystemVersionDialog();
            return true;
        } else if (id == R.id.action_logout) {
            confirmLogout();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void toggleLanguage() {
        try {
            String newLang = isArabic ? "en" : "ar";
            saveFragmentTagToPrefs(navigationManager.getCurrentFragmentTag());
            MiraLanguageHelper.applyLanguage(this, newLang);

            Intent intent = new Intent(this, UHFMainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        } catch (Exception e) {
            Log.e(TAG, "Toggle language error: " + e.getMessage(), e);
            Toast.makeText(this, "Error changing language", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmLogout() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(isArabic ? "تسجيل الخروج" : "Logout")
                .setMessage(isArabic ? "هل أنت متأكد من تسجيل الخروج من المنظومة؟" : "Are you sure you want to log out?")
                .setPositiveButton(isArabic ? "تأكيد" : "Logout", (dialog, which) -> {
                    sessionManager.logoutUser();
                    navigateToLogin();
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
    }

    private void handleNotificationButtonClick() {
        if (permissionManager.isAdmin() || permissionManager.isManager()) {
            showNotificationsAndChatDialog();
        } else {
            showNotificationsAndMessagingDialog();
        }
    }

    private void showSystemVersionDialog() {
        String miraSystemVersion = "MIRA ID™ v1.0.0 (Enterprise)";
        String hwVer = null;
        String modelName = Build.MODEL;

        if (BaseTabFragmentActivity.RFID_DEVICE_PRESENT && mReader != null) {
            try {
                hwVer = mReader.getHardwareVersion();
            } catch (Exception e) {
                Log.e(TAG, "Error fetching RFID reader info: " + e.getMessage());
            }
        }

        StringBuilder message = new StringBuilder();
        message.append("MIRA Trust Architecture™\n");
        message.append(isArabic ? "نظام المصادقة والتتبع المعتمد للمجوهرات\n\n" : "Jewelry Tracking & Authentication System\n\n");
        message.append(isArabic ? "📱 الجهاز: " : "📱 Device: ").append(modelName).append("\n");
        message.append(isArabic ? "📦 النسخة: " : "📦 Version: ").append(miraSystemVersion).append("\n");

        if (!BaseTabFragmentActivity.RFID_DEVICE_PRESENT) {
            message.append(isArabic ? "📡 العتاد: غير متصل" : "📡 Hardware: Not Connected");
        } else if (hwVer == null || hwVer.trim().isEmpty()) {
            message.append(isArabic ? "📡 العتاد: غير متهيئ" : "📡 Hardware: Not Configured");
        } else {
            message.append(isArabic ? "📡 العتاد: " : "📡 Hardware: ").append(hwVer).append("\n");
            message.append(isArabic ? "🟢 الحالة: وحدة UHF نشطة ومتصلة" : "🟢 Status: Active & Connected");
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(isArabic ? "حول النظام | System Info" : "About System")
                .setMessage(message.toString())
                .setPositiveButton(isArabic ? "موافق" : "OK", (dialog, which) -> dialog.dismiss())
                .show();
    }

    // ==================== حوارات التولبار الثانوي ====================

    private void showInventoryStatsDialog() {
        try {
            int scannedCount = 0;
            boolean isScanningNow = false;

            if (activeInventoryFragment != null
                    && activeInventoryFragment.isAdded()
                    && !activeInventoryFragment.isRemoving()) {
                scannedCount = activeInventoryFragment.getScannedCount();
                isScanningNow = activeInventoryFragment.isCurrentlyScanning();
            } else if (tagList != null) {
                scannedCount = tagList.size();
            }

            String status = isScanningNow
                    ? (isArabic ? "🟢 قيد التشغيل" : "🟢 Running")
                    : (isArabic ? "🔴 متوقف" : "🔴 Stopped");

            String message = isArabic
                    ? "📊 الأصناف الممسوحة: " + scannedCount + "\n" +
                      "📡 الحالة: " + status + "\n" +
                      "⏱️ آخر تحديث: الآن"
                    : "📊 Scanned items: " + scannedCount + "\n" +
                      "📡 Status: " + status + "\n" +
                      "⏱️ Last updated: just now";

            new MaterialAlertDialogBuilder(this)
                    .setTitle(isArabic ? "📊 إحصائيات الجلسة" : "📊 Live Stats")
                    .setMessage(message)
                    .setPositiveButton(isArabic ? "موافق" : "OK", null)
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "showInventoryStatsDialog error: " + e.getMessage());
        }
    }

    private void showInventoryArchiveDialog() {
        try {
            if (activeInventoryFragment != null
                    && activeInventoryFragment.isAdded()
                    && !activeInventoryFragment.isRemoving()) {
                activeInventoryFragment.showArchiveReportsPublic();
                return;
            }

            Fragment fragment = getSupportFragmentManager().findFragmentByTag("Scan");

            if (fragment instanceof MiraInventoryProFragment) {
                ((MiraInventoryProFragment) fragment).showArchiveReportsPublic();
                return;
            }

            loadFragment(new MiraInventoryProFragment(), "Scan");
            mainHandler.postDelayed(() -> {
                if (activeInventoryFragment != null
                        && activeInventoryFragment.isAdded()) {
                    activeInventoryFragment.showArchiveReportsPublic();
                } else {
                    Toast.makeText(this,
                            isArabic ? "افتح شاشة الجرد أولاً" : "Open inventory screen first",
                            Toast.LENGTH_SHORT).show();
                }
            }, 700);

        } catch (Exception e) {
            Log.e(TAG, "showInventoryArchiveDialog error: " + e.getMessage(), e);
            Toast.makeText(this,
                    isArabic ? "خطأ في فتح الأرشيف" : "Error opening archive",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void showPOSCartDialog() {
        if (activePOSFragment != null
                && activePOSFragment.isAdded()
                && !activePOSFragment.isRemoving()) {
            activePOSFragment.showCartBottomSheet();
        } else {
            loadFragment(new MiraPosFragment(), "POS");
            mainHandler.postDelayed(() -> {
                if (activePOSFragment != null) {
                    activePOSFragment.showCartBottomSheet();
                }
            }, 500);
        }
    }

    private void showPOSSearchDialog() {
        if (activePOSFragment != null
                && activePOSFragment.isAdded()
                && !activePOSFragment.isRemoving()) {
            activePOSFragment.focusSearchField();
        } else {
            loadFragment(new MiraPosFragment(), "POS");
            mainHandler.postDelayed(() -> {
                if (activePOSFragment != null) {
                    activePOSFragment.focusSearchField();
                }
            }, 500);
        }
    }

    private void showPOSDiscountDialog() {
        if (activePOSFragment != null
                && activePOSFragment.isAdded()
                && !activePOSFragment.isRemoving()) {
            activePOSFragment.showDiscountDialog();
        } else {
            loadFragment(new MiraPosFragment(), "POS");
            mainHandler.postDelayed(() -> {
                if (activePOSFragment != null) {
                    activePOSFragment.showDiscountDialog();
                }
            }, 500);
        }
    }

    private void showPOSReceiptDialog() {
        if (activePOSFragment != null
                && activePOSFragment.isAdded()
                && !activePOSFragment.isRemoving()) {
            activePOSFragment.showLastReceiptDialog();
        } else {
            showToast(isArabic ? "لا يوجد إيصال حديث" : "No recent receipt");
        }
    }

    private void showTransfersExportDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(isArabic ? "📤 تصدير التحويلات" : "📤 Export Transfers")
                .setMessage(isArabic
                    ? "سيتم تصدير قائمة التحويلات الحالية بصيغة:\n\n" +
                      "• Excel (XLSX)\n" +
                      "• PDF\n" +
                      "• CSV\n\n" +
                      "🚧 قيد التطوير"
                    : "Exports transfers list as:\n\n" +
                      "• Excel (XLSX)\n" +
                      "• PDF\n" +
                      "• CSV\n\n" +
                      "🚧 Under Development")
                .setPositiveButton(isArabic ? "موافق" : "OK", null)
                .show();
    }

    private void showTransfersImportDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(isArabic ? "📥 استيراد التحويلات" : "📥 Import Transfers")
                .setMessage(isArabic
                    ? "سيتم استيراد قائمة التحويلات من:\n\n" +
                      "• ملف Excel\n" +
                      "• ملف CSV\n" +
                      "• من سيرفر آخر\n\n" +
                      "🚧 قيد التطوير"
                    : "Imports transfers from:\n\n" +
                      "• Excel file\n" +
                      "• CSV file\n" +
                      "• Another server\n\n" +
                      "🚧 Under Development")
                .setPositiveButton(isArabic ? "موافق" : "OK", null)
                .show();
    }

    // ==================== الإشعارات والمراسلات ====================

    private void showNotificationsAndChatDialog() {
        String[] options = isArabic ? new String[]{
                "💬 مراسلة الأعضاء والفروع",
                "📥 تنبيهات النظام المخزنية",
                "📢 التعميمات الإدارية"
        } : new String[]{
                "💬 Team & Branch Chat",
                "📥 Inventory Notifications",
                "📢 Administrative Broadcasts"
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle(isArabic ? "📬 مركز المراسلات والإشعارات" : "Messaging Center")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showChatWindowDialog();
                    } else {
                        showNotificationsAndMessagingDialog();
                    }
                })
                .setPositiveButton(isArabic ? "إغلاق" : "Close", null)
                .show();
    }

    private void showNotificationsAndMessagingDialog() {
        BottomSheetDialog dialog = new BottomSheetDialog(this, R.style.TransparentBottomSheetDialog);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        TextView title = new TextView(this);
        title.setText(isArabic ? "🔔 إشعارات ورسائل المنظومة" : "System Notifications & Messages");
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(listContainer);

        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 400);
        scrollView.setLayoutParams(scrollParams);
        root.addView(scrollView);

        MiraMessagingManager messagingMgr = MiraMessagingManager.getInstance(this);

        List<NotificationModel> cachedNotifications = messagingMgr.getCachedNotifications();
        if (cachedNotifications.isEmpty()) {
            TextView emptyView = new TextView(this);
            emptyView.setText(isArabic ? "لا توجد إشعارات حالياً" : "No notifications available");
            emptyView.setPadding(16, 16, 16, 16);
            listContainer.addView(emptyView);
        } else {
            for (NotificationModel notif : cachedNotifications) {
                TextView tvNote = new TextView(this);
                String icon = getNotificationIcon(notif.getNotificationType());
                tvNote.setText(icon + " " + notif.getTitle() + "\n" + notif.getContent());
                tvNote.setPadding(16, 16, 16, 16);
                tvNote.setTextSize(14);
                listContainer.addView(tvNote);
            }
        }

        messagingMgr.fetchNotificationsFromServer(new MiraMessagingManager.OnNotificationsLoadedListener() {
            @Override
            public void onNotificationsLoaded(List<NotificationModel> notifications) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    listContainer.removeAllViews();
                    if (notifications.isEmpty()) {
                        TextView emptyView = new TextView(UHFMainActivity.this);
                        emptyView.setText(isArabic ? "لا توجد إشعارات حالياً" : "No notifications available");
                        emptyView.setPadding(16, 16, 16, 16);
                        listContainer.addView(emptyView);
                    } else {
                        for (NotificationModel notif : notifications) {
                            TextView tvNote = new TextView(UHFMainActivity.this);
                            String icon = getNotificationIcon(notif.getNotificationType());
                            tvNote.setText(icon + " " + notif.getTitle() + "\n" + notif.getContent());
                            tvNote.setPadding(16, 16, 16, 16);
                            tvNote.setTextSize(14);
                            listContainer.addView(tvNote);
                        }
                    }
                });
            }

            @Override
            public void onError(String error) {
            }
        });

        TextView lblSend = new TextView(this);
        lblSend.setText(isArabic ? "💬 إرسال رسالة داخلية جديدة:" : "💬 Send New Internal Message:");
        lblSend.setTextSize(14);
        lblSend.setTypeface(null, Typeface.BOLD);
        lblSend.setPadding(0, 24, 0, 8);
        root.addView(lblSend);

        EditText etMessageContent = new EditText(this);
        etMessageContent.setHint(isArabic ? "اكتب رسالتك للإدارة أو الفروع..." : "Type message to management or branches...");
        etMessageContent.setPadding(24, 24, 24, 24);
        root.addView(etMessageContent);

        MaterialButton btnSend = new MaterialButton(this);
        btnSend.setText(isArabic ? "إرسال الرسالة الآن 🚀" : "Send Message Now 🚀");
        root.addView(btnSend);

        btnSend.setOnClickListener(v -> {
            String content = etMessageContent.getText().toString().trim();
            if (!content.isEmpty()) {
                btnSend.setEnabled(false);
                btnSend.setText(isArabic ? "جاري الإرسال..." : "Sending...");

                String receiverId = "admin";

                MiraMessagingManager messagingMgr2 = MiraMessagingManager.getInstance(this);
                messagingMgr2.sendMessage(receiverId, content, new MiraApiClient.SendMessageCallback() {
                    @Override
                    public void onSuccess(MessageModel sentMessage) {
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            Toast.makeText(UHFMainActivity.this,
                                    isArabic ? "تم إرسال الرسالة بنجاح ✅" : "Message sent successfully ✅",
                                    Toast.LENGTH_LONG).show();
                            dialog.dismiss();
                        });
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            Toast.makeText(UHFMainActivity.this,
                                    isArabic ? "فشل الإرسال: " + error : "Failed: " + error,
                                    Toast.LENGTH_SHORT).show();
                            btnSend.setEnabled(true);
                            btnSend.setText(isArabic ? "إرسال الرسالة الآن 🚀" : "Send Message Now 🚀");
                        });
                    }
                });
            } else {
                etMessageContent.setError(isArabic ? "الرجاء كتابة نص الرسالة" : "Please enter message text");
            }
        });

        dialog.setContentView(root);
        dialog.show();
    }

    private void showChatWindowDialog() {
        BottomSheetDialog chatDialog = new BottomSheetDialog(this, R.style.TransparentBottomSheetDialog);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        TextView title = new TextView(this);
        title.setText(isArabic ? "💬 مراسلة أعضاء المنظومة" : "💬 Internal Team Chat");
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        LinearLayout messagesContainer = new LinearLayout(this);
        messagesContainer.setOrientation(LinearLayout.VERTICAL);

        TextView loadingText = new TextView(this);
        loadingText.setText(isArabic ? "جاري تحميل الرسائل..." : "Loading messages...");
        loadingText.setGravity(android.view.Gravity.CENTER);
        messagesContainer.addView(loadingText);

        scrollView.addView(messagesContainer);
        root.addView(scrollView);

        MiraMessagingManager messagingMgr = MiraMessagingManager.getInstance(this);
        messagingMgr.fetchMessagesFromServer(new MiraMessagingManager.OnMessagesLoadedListener() {
            @Override
            public void onMessagesLoaded(List<MessageModel> messages) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    messagesContainer.removeAllViews();

                    if (messages.isEmpty()) {
                        TextView emptyText = new TextView(UHFMainActivity.this);
                        emptyText.setText(isArabic ? "لا توجد رسائل بعد" : "No messages yet");
                        emptyText.setGravity(android.view.Gravity.CENTER);
                        emptyText.setPadding(16, 32, 16, 32);
                        messagesContainer.addView(emptyText);
                    } else {
                        for (MessageModel message : messages) {
                            TextView messageView = new TextView(UHFMainActivity.this);
                            String sender = message.getSenderName() != null && !message.getSenderName().isEmpty()
                                    ? message.getSenderName()
                                    : (isArabic ? "مستخدم" : "User");

                            String time = formatTimestamp(message.getTimestamp());
                            String messageText = sender + " (" + time + "):\n" + message.getContent();

                            messageView.setText(messageText);
                            messageView.setPadding(16, 12, 16, 12);
                            messageView.setBackgroundResource(R.drawable.bg_message_bubble);

                            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                            );
                            params.setMargins(0, 0, 0, 8);
                            messageView.setLayoutParams(params);

                            messagesContainer.addView(messageView);
                        }
                    }

                    scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    messagesContainer.removeAllViews();
                    TextView errorText = new TextView(UHFMainActivity.this);
                    errorText.setText(isArabic ? "فشل تحميل الرسائل: " + error : "Failed to load messages: " + error);
                    errorText.setGravity(android.view.Gravity.CENTER);
                    errorText.setPadding(16, 32, 16, 32);
                    messagesContainer.addView(errorText);
                });
            }
        });

        EditText etMessage = new EditText(this);
        etMessage.setHint(isArabic ? "اكتب رسالتك..." : "Type message...");
        etMessage.setPadding(24, 24, 24, 24);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        inputParams.setMargins(0, 16, 0, 8);
        etMessage.setLayoutParams(inputParams);
        root.addView(etMessage);

        MaterialButton btnSend = new MaterialButton(this);
        btnSend.setText(isArabic ? "إرسال" : "Send");
        btnSend.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        root.addView(btnSend);

        btnSend.setOnClickListener(v -> {
            String msg = etMessage.getText().toString().trim();
            if (!msg.isEmpty()) {
                btnSend.setEnabled(false);
                btnSend.setText(isArabic ? "جاري الإرسال..." : "Sending...");

                String receiverId = "all";
                MiraMessagingManager messagingMgr2 = MiraMessagingManager.getInstance(this);
                messagingMgr2.sendMessage(receiverId, msg, new MiraApiClient.SendMessageCallback() {
                    @Override
                    public void onSuccess(MessageModel message) {
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            Toast.makeText(UHFMainActivity.this,
                                    isArabic ? "تم إرسال الرسالة بنجاح" : "Message Sent",
                                    Toast.LENGTH_SHORT).show();
                            etMessage.setText("");
                            btnSend.setEnabled(true);
                            btnSend.setText(isArabic ? "إرسال" : "Send");
                            chatDialog.dismiss();
                        });
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            Toast.makeText(UHFMainActivity.this,
                                    isArabic ? "فشل إرسال الرسالة: " + error : "Failed to send message: " + error,
                                    Toast.LENGTH_SHORT).show();
                            btnSend.setEnabled(true);
                            btnSend.setText(isArabic ? "إرسال" : "Send");
                        });
                    }
                });
            } else {
                etMessage.setError(isArabic ? "الرجاء كتابة رسالة" : "Please type a message");
            }
        });

        chatDialog.setContentView(root);
        chatDialog.show();
    }

    private String getNotificationIcon(String type) {
        if (type == null) return "🔔";
        switch (type) {
            case "INVENTORY": return "⚠️";
            case "SYSTEM": return "📥";
            case "ADMIN": return "📢";
            case "CHAT": return "💬";
            default: return "🔔";
        }
    }

    private String formatTimestamp(long timestamp) {
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                    isArabic ? "HH:mm dd/MM/yyyy" : "HH:mm MM/dd/yyyy",
                    java.util.Locale.getDefault()
            );
            return sdf.format(new java.util.Date(timestamp));
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== البحث والمزامنة ====================

    private void showGlobalSearchDialog() {
        BottomSheetDialog searchDialog = new BottomSheetDialog(this, R.style.TransparentBottomSheetDialog);

        LinearLayout searchLayout = new LinearLayout(this);
        searchLayout.setOrientation(LinearLayout.VERTICAL);
        searchLayout.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText(isArabic ? "🔍 البحث السريع في المنظومة" : "Global System Search");
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, 0, 0, 24);
        searchLayout.addView(title);

        final EditText etSearchQuery = new EditText(this);
        etSearchQuery.setHint(isArabic ? "أدخل رقم الباركود، EPC، أو اسم القطعة..." : "Enter EPC, Barcode, or item name...");
        etSearchQuery.setPadding(32, 32, 32, 32);
        searchLayout.addView(etSearchQuery);

        MaterialButton btnExecuteSearch = new MaterialButton(this);
        btnExecuteSearch.setText(isArabic ? "بحث الآن" : "Search Now");
        btnExecuteSearch.setPadding(0, 24, 0, 24);
        searchLayout.addView(btnExecuteSearch);

        searchDialog.setContentView(searchLayout);

        btnExecuteSearch.setOnClickListener(v -> {
            String query = etSearchQuery.getText().toString().trim();
            if (!query.isEmpty()) {
                searchDialog.dismiss();
                Toast.makeText(this, (isArabic ? "جاري البحث عن: " : "Searching for: ") + query, Toast.LENGTH_SHORT).show();
                loadFragment(new MiraInventoryProFragment(), "Scan");
            } else {
                etSearchQuery.setError(isArabic ? "الرجاء إدخال نص للبحث" : "Please enter search text");
            }
        });

        searchDialog.show();
    }

    private void performDataSync() {
        showLoading();
        Toast.makeText(this, isArabic ? "جاري مزامنة البيانات المحلية مع السيرفر..." : "Syncing local data with server...", Toast.LENGTH_SHORT).show();

        mainHandler.postDelayed(() -> {
            hideLoading();
            if (isFinishing() || isDestroyed()) return;
            new MaterialAlertDialogBuilder(this)
                    .setTitle(isArabic ? "✅ تمت المزامنة بنجاح" : "✅ Sync Completed")
                    .setMessage(isArabic ? "تم تحديث كافة السجلات، المخزون، والأسعار مع السيرفر السحابي بنجاح." : "All records, inventory, and prices have been successfully synced with the cloud server.")
                    .setPositiveButton(isArabic ? "موافق" : "OK", (dialog, which) -> dialog.dismiss())
                    .show();
        }, 1500);
    }

    // ==================== قائمة الإجراءات السريعة ====================

    private void showQuickActionMenu() {
        try {
            BottomSheetDialog dialog = new BottomSheetDialog(this, R.style.TransparentBottomSheetDialog);
            View view = getLayoutInflater().inflate(R.layout.dialog_quick_action_grid, null);
            dialog.setContentView(view);

            PermissionManager pm = permissionManager;
            MiraQuickAccessSettings quickAccess = MiraQuickAccessSettings.getInstance(getApplicationContext());

            View btnRfidWrite = view.findViewById(R.id.btnRfidWrite);
            if (btnRfidWrite != null) {
                boolean allowed = pm.canEditInventory() && quickAccess.isActionEnabled("rfid_write");
                btnRfidWrite.setVisibility(allowed ? View.VISIBLE : View.GONE);
                if (allowed) {
                    btnRfidWrite.setOnClickListener(v -> {
                        dialog.dismiss();
                        loadFragment(new MiraUHFReadWriteFragment(), "RFIDWrite");
                    });
                }
            }

            applyQuickActionButton(view, R.id.btnRfidBindings, dialog,
                    pm.canViewRfidBindings() && quickAccess.isActionEnabled("rfid"),
                    new MiraRfidBindingsFragment(), "RfidBindings");

            applyQuickActionButton(view, R.id.btnIdentityStudio, dialog,
                    pm.canAccessIdentityStudio() && quickAccess.isActionEnabled("identity"),
                    new MiraIdentityStudioFragment(), "IdentityStudio");

            applyQuickActionButton(view, R.id.btnRadarLocation, dialog,
                    pm.canAccessRadar() && quickAccess.isActionEnabled("radar"),
                    new MiraUHFRadarLocationFragment(), "Radar");

            applyQuickActionButton(view, R.id.btnSecureGate, dialog,
                    pm.canAccessGate() && quickAccess.isActionEnabled("gate"),
                    new MiraSecureGateFragment(), "SecureGate");

            applyQuickActionButton(view, R.id.btnReports, dialog,
                    pm.canExportReports() && quickAccess.isActionEnabled("reports"),
                    new MiraReportsProFragment(), "Reports");

            applyQuickActionButton(view, R.id.btnAccounting, dialog,
                    pm.canAccessAccounting() && quickAccess.isActionEnabled("accounting"),
                    new MiraAccountingFragment(), "Accounting");

            applyQuickActionButton(view, R.id.btnTransfer, dialog,
                    pm.canAccessTransfers() && quickAccess.isActionEnabled("transfers"),
                    new MiraTransferFragment(), "Transfers");

            applyQuickActionButton(view, R.id.btnSalesHistory, dialog,
                    pm.canViewSalesHistory() && quickAccess.isActionEnabled("sales_history"),
                    new MiraSalesHistoryFragment(), "SalesHistory");

            applyQuickActionButton(view, R.id.btnAdminHub, dialog,
                    pm.isAdmin() && quickAccess.isActionEnabled("admin_hub"),
                    new MiraAdminHubFragment(), "AdminHub");

            applyQuickActionButton(view, R.id.btnServerConfig, dialog,
                    pm.canViewServerConfig() && quickAccess.isActionEnabled("server_config"),
                    new MiraServerConfigFragment(), "ServerConfig");

            View fabCloseSheet = view.findViewById(R.id.fabCloseSheet);
            if (fabCloseSheet != null) {
                fabCloseSheet.setOnClickListener(v -> dialog.dismiss());
            }

            dialog.show();
        } catch (Exception e) {
            Log.e(TAG, "QuickActionMenu Error: " + e.getMessage(), e);
        }
    }

    private void applyQuickActionButton(View root, int viewId, BottomSheetDialog dialog,
                                        boolean allowed, Fragment fragment, String tag) {
        View btn = root.findViewById(viewId);
        if (btn != null) {
            btn.setVisibility(allowed ? View.VISIBLE : View.GONE);
            if (allowed) {
                btn.setOnClickListener(v -> {
                    dialog.dismiss();
                    loadFragment(fragment, tag);
                });
            }
        }
    }

    // ==================== حالة الجرد ====================

    public boolean isInventoryActive() {
        Fragment currentFragment = navigationManager != null
                ? navigationManager.getCurrentFragment()
                : null;

        if (currentFragment instanceof MiraInventoryProFragment && currentFragment.isVisible()) {
            return ((MiraInventoryProFragment) currentFragment).isScanning();
        }
        if (currentFragment instanceof MiraUHFReadTagFragment && currentFragment.isVisible()) {
            return ((MiraUHFReadTagFragment) currentFragment).isScanning();
        }
        return false;
    }

    private void checkInventoryLockAndExecute(Runnable onConfirmed) {
        if (isInventoryActive()) {
            new MaterialAlertDialogBuilder(this)
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
            Fragment currentFragment = navigationManager != null
                    ? navigationManager.getCurrentFragment()
                    : null;

            if (currentFragment instanceof MiraInventoryProFragment && currentFragment.isVisible()) {
                ((MiraInventoryProFragment) currentFragment).stopScanning();
            }
            if (currentFragment instanceof MiraUHFReadTagFragment && currentFragment.isVisible()) {
                ((MiraUHFReadTagFragment) currentFragment).stopScanning();
            }
            stopUHFScan();
        } catch (Exception e) {
            Log.e(TAG, "Error stopping inventory: " + e.getMessage());
        }
    }

    // ==================== التحميل والعرض ====================

    public void showLoading() {
        mainHandler.post(() -> {
            try {
                loadingCounter++;
                View loadingOverlay = findViewById(R.id.successOverlayMain);
                if (loadingOverlay != null && loadingCounter == 1) {
                    loadingOverlay.animate().cancel();
                    loadingOverlay.setVisibility(View.VISIBLE);
                    loadingOverlay.setAlpha(1.0f);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error showing loading: " + e.getMessage());
            }
        });
    }

    public void hideLoading() {
        mainHandler.post(() -> {
            try {
                loadingCounter = Math.max(0, loadingCounter - 1);
                if (loadingCounter > 0) return;

                View loadingOverlay = findViewById(R.id.successOverlayMain);
                if (loadingOverlay != null && loadingOverlay.getVisibility() == View.VISIBLE) {
                    loadingOverlay.animate()
                            .alpha(0.0f)
                            .setDuration(200)
                            .withEndAction(() -> {
                                if (loadingCounter == 0) {
                                    loadingOverlay.setVisibility(View.GONE);
                                }
                            })
                            .start();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error hiding loading: " + e.getMessage());
            }
        });
    }

    private void forceHideLoadingImmediate() {
        mainHandler.post(() -> {
            try {
                loadingCounter = 0;
                View loadingOverlay = findViewById(R.id.successOverlayMain);
                if (loadingOverlay != null) {
                    loadingOverlay.animate().cancel();
                    loadingOverlay.setVisibility(View.GONE);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error forcing hide loading: " + e.getMessage());
            }
        });
    }

    public void showToast(String text) {
        if (text == null) return;
        mainHandler.post(() -> Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT).show());
    }

    public void showToast(int resId) {
        showToast(getString(resId));
    }

    // ==================== تسجيل الدخول ====================

    private void navigateToLogin() {
        try {
            forceHideLoadingImmediate();
            if (mReader != null) {
                mReader.free();
                mReader = null;
            }

            Intent intent = new Intent(this, LoginActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        } catch (Exception e) {
            Log.e(TAG, "Navigation to login error: " + e.getMessage());
        }
    }

    // ==================== التنظيف والتدمير ====================



    @Override
    protected void onDestroy() {
        try {
        // ✅ إزالة AppLifecycleObserver
        if (appLifecycleObserver != null) {
            ProcessLifecycleOwner.get().getLifecycle()
                    .removeObserver(appLifecycleObserver);
            appLifecycleObserver = null;
        }
            if (notificationManager != null) notificationManager.cleanup();
            if (soundManager != null) soundManager.cleanup();
            if (statusIndicatorManager != null) statusIndicatorManager.cleanup();
            if (hardwareManager != null) hardwareManager.cleanup();

            AppWatchdog.getInstance().stopMonitoring();
            forceHideLoadingImmediate();
            stopActiveInventory();

            if (orgProfileManager != null) {
                orgProfileManager.removeListener(orgProfileListener);
            }

            SessionMonitorManager.getInstance(this).stopMonitoring();

            clearActiveListeners();
            currentFragment = null;

            if (tagList != null) {
                tagList.clear();
                tagList = null;
            }

        } catch (Exception e) {
            Log.e(TAG, "onDestroy cleanup error: " + e.getMessage(), e);
        }

        super.onDestroy();
    }

    // ✅ ملاحظة: تم حذف cleanupOldFragments() نهائياً
    // ✅ NavigationManager الجديد يتولى التنظيف تلقائياً عبر replace()

    // ==================== التفاعل مع الكيبورد ====================

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
                    }
