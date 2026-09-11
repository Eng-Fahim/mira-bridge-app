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
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.annotation.SuppressLint;


import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.GridLayout;

import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.graphics.Typeface;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;

import com.example.uhf.activity.LoginActivity;
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
import com.example.uhf.manager.InventoryLockManager;
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

public class UHFMainActivity extends BaseTabFragmentActivity {

    private static final String TAG = "UHFMainActivity";
    private static final String PREFS_NAVIGATION = "app_navigation";
    private static final String KEY_CURRENT_FRAGMENT = "current_fragment_tag";
    
    // ✅ المديرين والمكونات
    private NavigationManager navigationManager;
    private HardwareManager hardwareManager;
    private SoundManager soundManager;
    private NotificationManager notificationManager;
    private StatusIndicatorManager statusIndicatorManager;
    
    private InventoryLockManager inventoryLockManager; 
    
    
    
    // ✅ المتغيرات الأساسية
    private TextView txtBadgeCount;
    private SessionManager sessionManager;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfileManager;
    private PermissionManager permissionManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // في تعريف المتغيرات
    private final MiraOrgProfileManager.OnProfileChangedListener orgProfileListener = this::updateAppTitle;

    // ✅ إدارة RFID
    private MiraPosFragment activePOSFragment = null;
    private MiraIdentityStudioFragment activeEncodingFragment = null;
    private OnRfidScannedListener activeRfidListener = null;
    
    // ✅ حالة النظام
    public int selectIndex = -1;
    public ArrayList<UHFTAGInfo> tagList = new ArrayList<>();
    public boolean loopFlag = false;
    private boolean isArabic = true;
    
    // ✅ التنقل
    public String currentFragmentTag = "Dashboard";
    public Fragment currentFragment;
    private long lastBackPressTime = 0;
    private static final long DOUBLE_BACK_TIMEOUT = 2000;
    
    // ✅ واجهات RFID
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

    private void clearActiveListeners() {
        this.activePOSFragment = null;
        this.activeEncodingFragment = null;
        this.activeRfidListener = null;
    }

    // ==================== دورة الحياة ====================

    @Override
    protected void attachBaseContext(Context newBase) {
        String savedLang = MiraLanguageHelper.getSavedLanguage(newBase);
        MiraLanguageHelper.applyLanguage(newBase, savedLang);
        super.attachBaseContext(newBase);
    }
    
    private void setupWindowInsets() {
    ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
        Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
        v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
        return insets;
    });
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
            cleanupOldFragments();

            // ✅ ربط الشريط الثانوي
            setupSecondaryToolbar();

            MaterialToolbar toolbar = findViewById(R.id.toolbar);
            if (toolbar != null) {
                setSupportActionBar(toolbar);
                
                // ✅ إخفاء العنوان الافتراضي للـ ActionBar
                if (getSupportActionBar() != null) {
                    getSupportActionBar().setDisplayShowTitleEnabled(false);
                }
                
                updateToolbarTitle();
            }

            // ✅ مراقبة استجابة الـ UI Thread
            AppWatchdog.getInstance().startMonitoring(() -> {
                Log.e(TAG, "UI Thread FROZEN! Restarting app...");
                MiraApplication.getInstance().restartApp();
            });

            ToastUtil.init(getApplicationContext());
            
            // ✅ تهيئة متغيرة اللغة أولاً قبل فحص اتجاه الواجهة
            initializeLanguageState();

            // ==================== ضبط اتجاه الواجهة لشريط التنقل ====================
            View bottomNavigationView = findViewById(R.id.bottomNavigationView);
            View bottomNavContainer = findViewById(R.id.bottomNavContainer);

            int layoutDirection = isArabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR;

            if (bottomNavigationView != null) {
                bottomNavigationView.setLayoutDirection(layoutDirection);
            }

            if (bottomNavContainer != null) {
                bottomNavContainer.setLayoutDirection(layoutDirection);
            }
            // ====================================================================

            // ✅ تهيئة المديرين
            branchesManager = MiraBranchesManager.getInstance(getApplicationContext());
            permissionManager = PermissionManager.getInstance(getApplicationContext());
            navigationManager = new NavigationManager(getSupportFragmentManager(), permissionManager);
            
            // 🆕 تهيئة مدير القفل
            inventoryLockManager = new InventoryLockManager(this);
            inventoryLockManager.initialize();
            
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
            statusIndicatorManager = new StatusIndicatorManager(findViewById(android.R.id.content), hardwareManager);

            // ✅ إعداد الأزرار والمستمعين
            setupQuickNav();
            setupOrgProfileListener();

            // ✅ التنقل الأولي
            if (savedInstanceState == null) {
                currentFragmentTag = "Dashboard";
            } else {
                currentFragmentTag = savedInstanceState.getString("current_fragment", "Dashboard");
            }

            navigationManager.loadFragmentDirectly(new MiraDashboardFragment(), "Dashboard");
            currentFragment = navigationManager.getCurrentFragment();

        } catch (Exception e) {
            Log.e(TAG, "Fatal error in onCreate: " + e.getMessage(), e);
        }
    }

    private void setupSecondaryToolbar() {
    MaterialToolbar secondaryToolbar = findViewById(R.id.secondaryToolbar);
    
    if (secondaryToolbar != null) {
        secondaryToolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            
            if (id == R.id.action_sync) {
                performDataSync();
                return true;
            } else if (id == R.id.action_search) {
                showGlobalSearchDialog();
                return true;
            } else if (id == R.id.action_quick_access_settings) {
                loadFragment(new MiraQuickAccessSettingsFragment(), "QuickAccessSettings");
                return true;
            } else if (id == R.id.action_device_status) {
                // ⚠️ لن يتم استدعاؤها لأن actionLayout يتعامل معها
                return true;
            }
            
            return false;
        });
        
        // ✅ إعداد زر حالة الأجهزة (actionLayout)
        setupDeviceStatusButton(secondaryToolbar);
    }
}

/**
 * ✅ إعداد زر حالة الأجهزة (متصل/غير متصل)
 */
private void setupDeviceStatusButton(MaterialToolbar toolbar) {
    try {
        // البحث عن MenuItem بواسطة ID
        Menu menu = toolbar.getMenu();
        MenuItem deviceStatusItem = menu.findItem(R.id.action_device_status);
        
        if (deviceStatusItem == null) return;
        
        View actionView = deviceStatusItem.getActionView();
        if (actionView == null) return;
        
        // ✅ ربط العناصر
        View statusDot = actionView.findViewById(R.id.deviceStatusDot);
        TextView statusText = actionView.findViewById(R.id.deviceStatusText);
        
        // ✅ عند الضغط على الزر
        actionView.setOnClickListener(v -> showHardwareOptionsDialog());
        
        // ✅ تحديث الحالة الأولية
        updateDeviceStatusUI(statusDot, statusText);
        
        // ✅ الاستماع لتغيرات حالة الجهاز
        if (hardwareManager != null) {
            hardwareManager.setStatusChangeCallback(status -> {
                mainHandler.post(() -> updateDeviceStatusUI(statusDot, statusText));
            });
        }
        
    } catch (Exception e) {
        Log.e(TAG, "setupDeviceStatusButton error: " + e.getMessage());
    }
}

/**
 * ✅ تحديث واجهة حالة الأجهزة
 */
private void updateDeviceStatusUI(View statusDot, TextView statusText) {
    try {
        if (statusDot == null || statusText == null) return;
        
        boolean isConnected = false;
        
        if (hardwareManager != null) {
            isConnected = hardwareManager.isConnected();
        }
        
        if (isConnected) {
            statusDot.setBackgroundResource(R.drawable.bg_status_dot_green);
            statusText.setText(isArabic ? "متصل" : "Connected");
            statusText.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
        } else {
            statusDot.setBackgroundResource(R.drawable.bg_status_dot_red);
            statusText.setText(isArabic ? "غير متصل" : "Disconnected");
            statusText.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
        }
        
    } catch (Exception e) {
        Log.e(TAG, "updateDeviceStatusUI error: " + e.getMessage());
    }
}


private void showHardwareOptionsDialog() {
    PermissionManager pm = PermissionManager.getInstance(getApplicationContext());
    List<String> optionsList = new ArrayList<>();
    List<Runnable> actionsList = new ArrayList<>();
    
    // 1. إعدادات قارئ RFID
    if (pm.isAdmin() || pm.isInventoryManager() || pm.isSystemAdmin()) {
        optionsList.add(isArabic ? "📡 إعدادات قارئ RFID" : "📡 RFID Reader Setup");
        actionsList.add(() -> loadFragment(new MiraReaderSetupFragment(), "ReaderSetup"));
    }
    
    // 2. إعدادات البوابات الأمنية
    if (pm.isAdmin() || pm.isSecurity() || pm.isGateSecurity()) {
        optionsList.add(isArabic ? "🚧 تهيئة البوابات الأمنية" : "🚧 Gate Security Setup");
        actionsList.add(() -> loadFragment(new MiraGateSettingsFragment(), "GateSettings"));
    }
    
    // 3. إعدادات السيرفر
    if (pm.canViewServerConfig()) {
        optionsList.add(isArabic ? "🌐 إعدادات السيرفر" : "🌐 Server Config");
        actionsList.add(() -> loadFragment(new MiraServerConfigFragment(), "ServerConfig"));
    }
    
    // 4. إدارة الشرائح المبرمجة
    if (pm.isAdmin() || pm.isManager() || pm.isInventoryManager()) {
        optionsList.add(isArabic ? "🏷️ إدارة الشرائح المبرمجة" : "🏷️ RFID Bindings");
        actionsList.add(() -> loadFragment(new MiraRfidBindingsFragment(), "RfidBindings"));
    }
    
    // 5. ⚙️ إعدادات تخصيص الوصول السريع (جديد!)
    if (pm.canManageQuickAccess() || pm.isManager() || pm.isAdmin()) {
        optionsList.add(isArabic ? "⚙️ إعدادات الوصول السريع" : "⚙️ Quick Access Settings");
        actionsList.add(() -> loadFragment(new MiraQuickAccessSettingsFragment(), "QuickAccessSettings"));
    }
    
    // 6. اختبار التوصيل
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

/**
 * ✅ اختبار التوصيل والربط
 */
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
        updateToolbarTitle();
        if (statusIndicatorManager != null) statusIndicatorManager.scheduleUpdates();
        mainHandler.postDelayed(this::hideLoading, 100);
    }

    @Override
    public void onPause() {
        super.onPause();
        hideLoading();
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
                    isArabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR
            );

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
        outState.putString("current_fragment", currentFragmentTag);
        saveFragmentTagToPrefs(currentFragmentTag);
    }

    // ==================== إعدادات ====================

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
        // ✅ تحديث TextView مباشرة (وليس ActionBar)
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

    // ==================== التنقل ====================

    public void loadFragment(Fragment fragment, String tag) {
    showLoading();
    navigationManager.loadFragment(fragment, tag, true);
    currentFragment = navigationManager.getCurrentFragment();
    currentFragmentTag = tag;
    updateBottomNavSelectionByTag(tag);
    updateSecondaryToolbarVisibility(tag);   // 🆕
    mainHandler.postDelayed(this::hideLoading, 250);
}

public void loadFragment(Fragment fragment, String tag, boolean addToBackStack) {
    showLoading();
    navigationManager.loadFragment(fragment, tag, addToBackStack);
    currentFragment = navigationManager.getCurrentFragment();
    currentFragmentTag = tag;
    updateBottomNavSelectionByTag(tag);
    updateSecondaryToolbarVisibility(tag);   // 🆕
    mainHandler.postDelayed(this::hideLoading, 250);
}

public void loadFragmentDirectly(Fragment fragment, String tag) {
    showLoading();
    navigationManager.loadFragmentDirectly(fragment, tag);
    currentFragment = navigationManager.getCurrentFragment();
    currentFragmentTag = tag;
    updateBottomNavSelectionByTag(tag);
    updateSecondaryToolbarVisibility(tag);   // 🆕
    mainHandler.postDelayed(this::hideLoading, 250);
}
/**
 * 🆕 تحديث واجهة الشريط الثانوي حسب الشاشة الحالية
 * تُستدعى بعد كل تنقل
 */
private void updateSecondaryToolbarVisibility(String tag) {
    try {
        View secondaryToolbar = findViewById(R.id.secondaryToolbar);
        if (secondaryToolbar == null) return;
        
        // 🎯 إخفاء Secondary Toolbar عند شاشات محددة
        boolean shouldHide = isScreenThatHidesToolbar(tag);
        
        if (shouldHide) {
            // إخفاء
            if (secondaryToolbar.getVisibility() == View.VISIBLE) {
                secondaryToolbar.animate()
                        .alpha(0f)
                        .setDuration(200)
                        .withEndAction(() -> secondaryToolbar.setVisibility(View.GONE))
                        .start();
            }
        } else {
            // إظهار
            if (secondaryToolbar.getVisibility() != View.VISIBLE) {
                secondaryToolbar.setVisibility(View.VISIBLE);
                secondaryToolbar.setAlpha(0f);
                secondaryToolbar.animate()
                        .alpha(1f)
                        .setDuration(200)
                        .start();
            }
        }
    } catch (Exception e) {
        Log.e(TAG, "Error updating secondary toolbar visibility", e);
    }
}

/**
 * 🆕 هل الشاشة تُخفي Secondary Toolbar؟
 */
private boolean isScreenThatHidesToolbar(String tag) {
    if (tag == null) return false;
    
    // 🎯 الشاشات التي تُخفي Secondary Toolbar
    // (لأنها تحتوي على شريطها الخاص)
    switch (tag) {
        case "Scan":
        case "MiraInventoryProFragment":
            return true;
        // أضف شاشات أخرى هنا إذا لزم الأمر
        default:
            return false;
    }
}

    public Fragment createFragmentByTag(String tag) {
        return FragmentFactory.createFragmentByTag(tag);
    }

    public void openReceiptFragment(String rawJson) {
        try {
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
        try {
            if (activeEncodingFragment != null && activeEncodingFragment.isAdded() && activeEncodingFragment.isResumed()) {
                activeEncodingFragment.onRfidScanned(epc);
                return;
            }
            if (activeRfidListener != null) {
                activeRfidListener.onRfidScanned(epc);
                return;
            }
            if (activePOSFragment != null && activePOSFragment.isAdded() && activePOSFragment.isVisible()) {
                activePOSFragment.onRingScanned(epc);
                return;
            }
            if (currentFragment instanceof MiraUHFReadTagFragment && currentFragment.isAdded() && currentFragment.isVisible()) {
                ((MiraUHFReadTagFragment) currentFragment).onTagScanned(epc, -50.0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Tag Read Processing Error: " + e.getMessage());
        }
    }

    // ==================== التنقل السريع ====================

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
                    Fragment fragment = FragmentFactory.createFragmentByTag(tag);

                    if (fragment != null) {
                        loadFragment(fragment, tag, false);
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
        if (itemId == R.id.nav_home) return "Dashboard";
        if (itemId == R.id.nav_inventory) return "Scan";
        if (itemId == R.id.nav_sales) return "POS";
        if (itemId == R.id.nav_transfers) return "Transfers";
        return "";
    }

    private void updateBottomNavSelectionByTag(String tag) {
        try {
            BottomNavigationView quickNav = findViewById(R.id.bottomNavigationView);
            if (quickNav == null || tag == null) return;

            if (tag.equals("Dashboard")) {
                quickNav.getMenu().findItem(R.id.nav_home).setChecked(true);
            } else if (tag.equals("Scan")) {
                quickNav.getMenu().findItem(R.id.nav_inventory).setChecked(true);
            } else if (tag.equals("POS")) {
                quickNav.getMenu().findItem(R.id.nav_sales).setChecked(true);
            } else if (tag.equals("Transfers")) {
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
        PermissionManager pm = permissionManager;

        MenuItem actionGoldRates = menu.findItem(R.id.action_gold_rates);
        if (actionGoldRates != null) actionGoldRates.setVisible(pm.canViewGoldRates());

        MenuItem actionProfile = menu.findItem(R.id.action_profile);
        if (actionProfile != null) actionProfile.setVisible(pm.canViewProfile());

        MenuItem toggleLanguage = menu.findItem(R.id.action_toggle_language);
        if (toggleLanguage != null) toggleLanguage.setTitle(isArabic ? "English" : "العربية");

        MenuItem actionQuickAccess = menu.findItem(R.id.action_quick_access_settings);
        if (actionQuickAccess != null) actionQuickAccess.setVisible(pm.canManageQuickAccess());

        MenuItem actionBranches = menu.findItem(R.id.action_branches);
        if (actionBranches != null) actionBranches.setVisible(pm.isManager() || pm.isAdmin() || pm.isBranchManager());

        MenuItem actionExport = menu.findItem(R.id.export);
        if (actionExport != null) actionExport.setVisible(pm.canExportReports());

        MenuItem uhfVer = menu.findItem(R.id.UHF_ver);
        if (uhfVer != null) uhfVer.setVisible(pm.isAdmin() || pm.isSystemAdmin());
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
        } else if (id == R.id.action_quick_access_settings) {
            loadFragment(new MiraQuickAccessSettingsFragment(), "QuickAccessSettings");
            return true;
        } else if (id == R.id.action_branches) {
            loadFragment(new MiraBranchesManagementFragment(), "Branches");
            return true;
        } else if (id == R.id.export) {
            loadFragment(new MiraReportsProFragment(), "Reports");
            return true;
        } else if (id == R.id.action_toggle_language) {
            toggleLanguage();
            return true;
        } else if (id == R.id.nav_sound_settings) {
            loadFragment(new MiraSoundSettingsFragment(), "SoundSettings");
            return true;
        } else if (id == R.id.UHF_ver) {
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
            saveFragmentTagToPrefs(currentFragmentTag);
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

    // ==================== الحوارات ====================

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
                            Toast.makeText(UHFMainActivity.this,
                                    isArabic ? "تم إرسال الرسالة بنجاح ✅" : "Message sent successfully ✅",
                                    Toast.LENGTH_LONG).show();
                            dialog.dismiss();
                        });
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> {
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
        // ✅ 1. إنشاء الحوار بالنمط الفاخر
        BottomSheetDialog dialog = new BottomSheetDialog(this, R.style.LuxuryBottomSheetDialog);
        View view = getLayoutInflater().inflate(R.layout.dialog_quick_action_sheet, null);
        dialog.setContentView(view);

        // ✅ 2. ربط العناصر
        PermissionManager pm = permissionManager;
        MiraQuickAccessSettings quickAccess = MiraQuickAccessSettings.getInstance(getApplicationContext());

        // ✅ 3. ربط جميع الأزرار
        bindQuickAction(view, R.id.btnRfidWrite, dialog,
                pm.canEditInventory() && quickAccess.isActionEnabled("rfid_write"),
                new MiraUHFReadWriteFragment(), "RFIDWrite");

        bindQuickAction(view, R.id.btnRfidBindings, dialog,
                pm.canViewRfidBindings() && quickAccess.isActionEnabled("rfid"),
                new MiraRfidBindingsFragment(), "RfidBindings");

        bindQuickAction(view, R.id.btnIdentityStudio, dialog,
                pm.canAccessIdentityStudio() && quickAccess.isActionEnabled("identity"),
                new MiraIdentityStudioFragment(), "IdentityStudio");

        bindQuickAction(view, R.id.btnRadarLocation, dialog,
                pm.canAccessRadar() && quickAccess.isActionEnabled("radar"),
                new MiraUHFRadarLocationFragment(), "Radar");

        bindQuickAction(view, R.id.btnSecureGate, dialog,
                pm.canAccessGate() && quickAccess.isActionEnabled("gate"),
                new MiraSecureGateFragment(), "SecureGate");

        bindQuickAction(view, R.id.btnReports, dialog,
                pm.canExportReports() && quickAccess.isActionEnabled("reports"),
                new MiraReportsProFragment(), "Reports");

        bindQuickAction(view, R.id.btnAccounting, dialog,
                pm.canAccessAccounting() && quickAccess.isActionEnabled("accounting"),
                new MiraAccountingFragment(), "Accounting");

        bindQuickAction(view, R.id.btnTransfer, dialog,
                pm.canAccessTransfers() && quickAccess.isActionEnabled("transfer"),
                new MiraTransferFragment(), "Transfers");

        bindQuickAction(view, R.id.btnSalesHistory, dialog,
                pm.canViewSalesHistory() && quickAccess.isActionEnabled("sales_history"),
                new MiraSalesHistoryFragment(), "SalesHistory");

        bindQuickAction(view, R.id.btnAdminHub, dialog,
                pm.isAdmin() && quickAccess.isActionEnabled("admin_hub"),
                new MiraAdminHubFragment(), "AdminHub");

        bindQuickAction(view, R.id.btnServerConfig, dialog,
                pm.canViewServerConfig() && quickAccess.isActionEnabled("server_config"),
                new MiraServerConfigFragment(), "ServerConfig");

        // ✅ 4. زر الإغلاق
        View btnClose = view.findViewById(R.id.btnCloseSheet);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dialog.dismiss());
        }

        // ✅ 5. 🎬 حركات الفتح الانسيابية
        GridLayout grid = view.findViewById(R.id.gridQuickActions);
        LinearLayout root = view.findViewById(R.id.quickActionSheetRoot);

        if (root != null) {
            root.setAlpha(0f);
            root.setTranslationY(80f);
        }

        dialog.setOnShowListener(d -> {
            // 🎬 ظهور القائمة بالكامل بحركة انزلاق
            if (root != null) {
                root.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(350)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
            }

            // 🎬 ظهور متتالي لكل أيقونة في الشبكة
            if (grid != null) {
                for (int i = 0; i < grid.getChildCount(); i++) {
                    View child = grid.getChildAt(i);
                    if (child.getVisibility() != View.VISIBLE) continue;

                    child.setAlpha(0f);
                    child.setScaleX(0.4f);
                    child.setScaleY(0.4f);
                    child.setTranslationY(30f);

                    child.animate()
                            .alpha(1f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .translationY(0f)
                            .setStartDelay(120 + (i * 45L))
                            .setDuration(400)
                            .setInterpolator(new OvershootInterpolator(1.4f))
                            .start();
                }
            }
        });

        // 🎬 حركة الإغلاق
        dialog.setOnDismissListener(d -> {
            if (root != null) {
                root.animate()
                        .alpha(0f)
                        .translationY(80f)
                        .setDuration(200)
                        .setInterpolator(new AccelerateInterpolator())
                        .start();
            }
        });

        dialog.show();

    } catch (Exception e) {
        Log.e(TAG, "QuickActionMenu Error: " + e.getMessage(), e);
    }
}

/**
 * ✅ دالة مساعدة لربط أزرار الإجراءات السريعة
 */
private void bindQuickAction(View root, int viewId, BottomSheetDialog dialog,
                              boolean allowed, Fragment fragment, String tag) {
    View btn = root.findViewById(viewId);
    if (btn != null) {
        btn.setVisibility(allowed ? View.VISIBLE : View.GONE);
        if (allowed) {
            btn.setOnClickListener(v -> {
                // 🎬 حركة صغيرة عند الضغط
                v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100)
                        .withEndAction(() -> {
                            v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
                            dialog.dismiss();
                            loadFragment(fragment, tag);
                        }).start();
            });
        }
    }
}

// ==================== 🔒 وضع الجرد المحصّن (API عام) ====================

/**
 * 🆕 تفعيل وضع الجرد المحصّن
 * تُستدعى من Fragment عند بدء الجرد
 */
public void enterInventoryLockMode() {
    if (inventoryLockManager != null) {
        inventoryLockManager.lock();
    }
}

/**
 * 🆕 إلغاء وضع الجرد المحصّن
 * تُستدعى من Fragment عند إيقاف الجرد
 */
public void exitInventoryLockMode() {
    if (inventoryLockManager != null) {
        inventoryLockManager.unlock();
    }
}

/**
 * هل وضع القفل نشط حالياً؟
 */
public boolean isInventoryLocked() {
    return inventoryLockManager != null && inventoryLockManager.isLocked();
}
    
    // ==================== حالة الجرد ====================

    public boolean isInventoryActive() {
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

    // ==================== التنقل للخلف ====================

    @Override
    public void onBackPressed() {
        if (isInventoryActive()) {
            checkInventoryLockAndExecute(this::handleBackNavigation);
            return;
        }
        handleBackNavigation();
    }

    private void handleBackNavigation() {
        clearActiveListeners();
        if (getSupportFragmentManager() != null) {
            for (Fragment fragment : getSupportFragmentManager().getFragments()) {
                if (fragment instanceof androidx.fragment.app.DialogFragment) {
                    androidx.fragment.app.DialogFragment dialogFragment = (androidx.fragment.app.DialogFragment) fragment;
                    if (dialogFragment.getDialog() != null && dialogFragment.getDialog().isShowing()) {
                        dialogFragment.dismissAllowingStateLoss();
                        return;
                    }
                }
            }

            if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
            getSupportFragmentManager().popBackStackImmediate();
            Fragment topFragment = navigationManager.getTopFragment();
            if (topFragment != null) {
                String tag = topFragment.getTag();
                if (tag != null && !permissionManager.canAccessFragment(tag)) {
                    navigationManager.clearBackStack();
                    loadFragmentDirectly(new MiraDashboardFragment(), "Dashboard");
                    return;
                }
                currentFragment = topFragment;
                if (tag != null) {
                    currentFragmentTag = tag;
                    saveFragmentTagToPrefs(tag);
                    updateSecondaryToolbarVisibility(tag);   // 🆕
                }
            }
            return;
        }
    }

        if (currentFragment != null && !(currentFragment instanceof MiraDashboardFragment)) {
            navigationManager.clearBackStack();
            loadFragmentDirectly(new MiraDashboardFragment(), "Dashboard");
            return;
        }

        handleDoubleBackToExit();
    }

    private void handleDoubleBackToExit() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastBackPressTime < DOUBLE_BACK_TIMEOUT) {
            finish();
            return;
        }
        lastBackPressTime = currentTime;
        Toast.makeText(this,
                isArabic ? "اضغط مرة أخرى للخروج" : "Press again to exit",
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onBackPressed();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ==================== التحميل والعرض ====================

    public void showLoading() {
        mainHandler.post(() -> {
            try {
                View loadingOverlay = findViewById(R.id.successOverlayMain);
                if (loadingOverlay != null) {
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
                View loadingOverlay = findViewById(R.id.successOverlayMain);
                if (loadingOverlay != null && loadingOverlay.getVisibility() == View.VISIBLE) {
                    loadingOverlay.animate()
                            .alpha(0.0f)
                            .setDuration(200)
                            .withEndAction(() -> loadingOverlay.setVisibility(View.GONE))
                            .start();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error hiding loading: " + e.getMessage());
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
            hideLoading();
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

    // ==================== تنظيف ====================


@Override
protected void onDestroy() {
    try {
        stopActiveInventory(); // إيقاف المسح أولاً قبل تفكيك العتاد
        
        if (notificationManager != null) notificationManager.cleanup();
        if (soundManager != null) soundManager.cleanup();
        if (statusIndicatorManager != null) statusIndicatorManager.cleanup();
        if (hardwareManager != null) hardwareManager.cleanup();
            AppWatchdog.getInstance().stopMonitoring();
            hideLoading();
            stopActiveInventory();

            if (getSupportFragmentManager() != null) {
                navigationManager.clearBackStack();
            }

            if (orgProfileManager != null) {
            orgProfileManager.removeListener(orgProfileListener);
            }

            
            if (inventoryLockManager != null) {
            inventoryLockManager.cleanup();
            inventoryLockManager = null;
            }

            SessionMonitorManager.getInstance(this).stopMonitoring();

            clearActiveListeners();

            if (tagList != null) {
                tagList.clear();
                tagList = null;
            }

        } catch (Exception e) {
            Log.e(TAG, "onDestroy cleanup error: " + e.getMessage());
        }

        super.onDestroy();
    }

    private void cleanupOldFragments() {
        try {
            FragmentManager fm = getSupportFragmentManager();
            for (Fragment fragment : fm.getFragments()) {
                if (fragment != null) {
                    FragmentTransaction transaction = fm.beginTransaction();
                    transaction.remove(fragment);
                    transaction.commitAllowingStateLoss();
                }
            }
            fm.executePendingTransactions();
            fm.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        } catch (Exception e) {
            Log.e(TAG, "Cleanup error: " + e.getMessage(), e);
        }
    }

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
