package com.example.uhf.activity;

import com.example.uhf.application.MiraApplication;
import com.example.uhf.manager.AppWatchdog;
import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
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
import android.widget.TabHost;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.appcompat.view.menu.MenuBuilder;
import android.annotation.SuppressLint;

import com.example.uhf.R;
import com.example.uhf.fragment.MiraAccountingFragment;
import com.example.uhf.fragment.MiraAdminHubFragment;
import com.example.uhf.fragment.MiraBranchesManagementFragment;
import com.example.uhf.fragment.MiraDashboardFragment;
import com.example.uhf.fragment.MiraGateSettingsFragment;
import com.example.uhf.fragment.MiraGoldCurrencyFragment;
import com.example.uhf.fragment.MiraIdentityStudioFragment;
import com.example.uhf.fragment.MiraInventoryProFragment;
import com.example.uhf.fragment.MiraPosFragment;
import com.example.uhf.fragment.MiraProfileFragment;
import com.example.uhf.fragment.MiraQuickAccessSettingsFragment;
import com.example.uhf.fragment.MiraReaderSetupFragment;
import com.example.uhf.fragment.MiraReceiptFragment;
import com.example.uhf.fragment.MiraReportsProFragment;
import com.example.uhf.fragment.MiraRfidBindingsFragment;
import com.example.uhf.fragment.MiraSalesHistoryFragment;
import com.example.uhf.fragment.MiraSecureGateFragment;
import com.example.uhf.fragment.MiraServerConfigFragment;
import com.example.uhf.fragment.MiraTransferFragment;
import com.example.uhf.fragment.MiraUHFRadarLocationFragment;
import com.example.uhf.fragment.MiraUHFReadTagFragment;
import com.example.uhf.fragment.MiraUHFReadWriteFragment;
import com.example.uhf.fragment.UsersManagementFragment;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.MiraQuickAccessSettings;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.manager.SessionMonitorManager;
import com.example.uhf.tools.MiraLanguageHelper;
import com.example.uhf.tools.SessionManager;
import com.example.uhf.tools.ToastUtil;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.rscja.deviceapi.entity.UHFTAGInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class UHFMainActivity extends BaseTabFragmentActivity {

private static final boolean ENABLE_DASHBOARD = true;      // ✅ مفعل
private static final boolean ENABLE_INVENTORY = true;      // ✅ مفعل
private static final boolean ENABLE_POS = true;           // ❌ معطل مؤقتاً
private static final boolean ENABLE_TRANSFERS = true;     // ❌ معطل مؤقتاً
private static final boolean ENABLE_OTHER = true;         // ❌ معطل مؤقتاً

    private static final String TAG = "UHFMainActivity";
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final String PREFS_NAVIGATION = "app_navigation";
    private static final String KEY_CURRENT_FRAGMENT = "current_fragment_tag";

    public TabHost mTabHost = null;

    private boolean isProfileListenerSetup = false;
    private MiraOrgProfileManager orgProfileManager;
    private MiraOrgProfileManager.OnProfileChangedListener profileListener;

    private FrameLayout loadingOverlay;
    private FragmentManager fragmentManager;

    private MiraPosFragment activePOSFragment = null;
    private MiraIdentityStudioFragment activeEncodingFragment;
    private OnRfidScannedListener activeRfidListener;

    private SessionManager sessionManager;
    private MiraBranchesManager branchesManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public int selectIndex = -1;
    public ArrayList<UHFTAGInfo> tagList = new ArrayList<>();
    public boolean loopFlag = false;
    private boolean isArabic = true;
    private int lastActiveBranchId = -1;

    private String currentFragmentTag = "Dashboard";

    private long lastBackPressTime = 0;
    private static final long DOUBLE_BACK_TIMEOUT = 2000;

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

    private void setupOrgProfileListener() {
        if (isProfileListenerSetup) return;
        isProfileListenerSetup = true;

        orgProfileManager = MiraOrgProfileManager.getInstance(getApplicationContext());
        profileListener = this::updateAppTitle;
        orgProfileManager.addListener(profileListener);
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
            if (getSupportActionBar() != null) {
                String orgName = "MIRA";
                if (orgProfileManager != null) {
                    String name = orgProfileManager.getDisplayName();
                    if (name != null && !name.trim().isEmpty()) {
                        orgName = name;
                    }
                }
                getSupportActionBar().setTitle(orgName);

                if (branchesManager != null && branchesManager.hasActiveBranch()) {
                    getSupportActionBar().setSubtitle("📍 " + branchesManager.getActiveBranchDisplayName());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Toolbar Update Error: " + e.getMessage());
        }
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        String savedLang = MiraLanguageHelper.getSavedLanguage(newBase);
        MiraLanguageHelper.applyLanguage(newBase, savedLang);
        super.attachBaseContext(newBase);
    }

   @Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    try {
        sessionManager = new SessionManager(getApplicationContext());
        if (!sessionManager.isLoggedIn()) {
            navigateToLogin();
            return;
        }

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        setContentView(R.layout.activity_main);

        AppWatchdog.getInstance().startMonitoring(() -> {
            Log.e(TAG, "🚨 UI Thread FROZEN! Restarting app...");
            MiraApplication.getInstance().restartApp();
        });

        ToastUtil.init(getApplicationContext());
        initializeLanguageState();

        branchesManager = MiraBranchesManager.getInstance(getApplicationContext());
        fragmentManager = getSupportFragmentManager();

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            updateToolbarTitle();
        }

        setupQuickNav();

        if (savedInstanceState == null) {
            currentFragmentTag = "Dashboard";
        } else {
            currentFragmentTag = savedInstanceState.getString("current_fragment", "Dashboard");
        }

        // ✅ تحميل الـ Fragment الحقيقي
        loadFragmentDirectly(createFragmentByTag(currentFragmentTag), currentFragmentTag);

    } catch (Exception e) {
        Log.e(TAG, "Fatal error in onCreate: " + e.getMessage(), e);
        try {
            loadFragmentDirectly(new MiraDashboardFragment(), "Dashboard");
        } catch (Exception ignored) {}
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
            
        } catch (Exception e) {
            Log.e(TAG, "Configuration change error: " + e.getMessage());
        }
    }
    
    private void loadEssentialData() {
    Thread dataThread = new Thread(() -> {
        try {
            com.example.uhf.api.MiraApiClient apiClient =
                    com.example.uhf.api.MiraApiClient.getInstance(getApplicationContext());

                com.example.uhf.api.MiraApiClient.ApiResponse profileResponse = apiClient.getOrgProfile();
                if (profileResponse != null && profileResponse.isSuccess && profileResponse.data != null) {
                    org.json.JSONObject org = profileResponse.data.optJSONObject("organization");
                    if (org != null) {
                        MiraOrgProfileManager.getInstance(getApplicationContext()).saveFromServer(org);
                    }
                }

                com.example.uhf.api.MiraApiClient.ApiResponse branchesResponse = apiClient.getBranches();
                if (branchesResponse != null && branchesResponse.isSuccess && branchesResponse.data != null) {
                    MiraBranchesManager.getInstance(getApplicationContext()).saveBranchesFromServer(branchesResponse.data);
                }

                com.example.uhf.api.MiraApiClient.ApiResponse goldResponse = apiClient.getGoldCurrencySettings();
                if (goldResponse != null && goldResponse.isSuccess && goldResponse.data != null) {
                    org.json.JSONObject data = goldResponse.data.optJSONObject("data");
                    if (data == null) data = goldResponse.data;
                    org.json.JSONObject goldData = data.optJSONObject("gold");
                    org.json.JSONObject currencyData = data.optJSONObject("currency");
                    if (goldData != null) {
                        com.example.uhf.manager.MiraPriceManager.getInstance(getApplicationContext()).saveGoldSettings(goldData);
                    }
                    if (currencyData != null) {
                        com.example.uhf.manager.MiraPriceManager.getInstance(getApplicationContext()).saveCurrencySettings(currencyData);
                    }
                }

                            
            Log.d(TAG, "✅ Essential data loaded successfully");

        } catch (Exception e) {
            Log.e(TAG, "Error loading essential data: " + e.getMessage());
        } finally {
            mainHandler.post(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    updateToolbarTitle();
                    hideLoading();
                }
            });
        }
    });
    
    dataThread.setName("EssentialDataLoader");
    dataThread.setDaemon(true);
    dataThread.start();
}

    @Override
    public void onResume() {
        super.onResume();
        updateToolbarTitle();
        // ✅ تم التعديل هنا: استخدام postDelayed لضمان تنظيف الشاشة من أي حالة تجمد سابقة
        mainHandler.postDelayed(this::hideLoading, 100);
    }
    
    @Override
    public void onPause() {
        super.onPause();
        hideLoading();
        AppWatchdog.getInstance().stopMonitoring();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // ✅ تم التعديل هنا: ضمان إخفاء نافذة التحميل تماماً عند وضع التطبيق في الخلفية
        hideLoading();
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

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("current_fragment", currentFragmentTag);
        saveFragmentTagToPrefs(currentFragmentTag);
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

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);

        if (menu instanceof androidx.appcompat.view.menu.MenuBuilder) {
            ((androidx.appcompat.view.menu.MenuBuilder) menu).setOptionalIconsVisible(true);
        }

        PermissionManager pm = PermissionManager.getInstance(getApplicationContext());
        applyToolbarMenuPermissions(menu, pm);
        return true;
    }

    private void applyToolbarMenuPermissions(Menu menu, PermissionManager pm) {
        if (pm == null) return;

        MenuItem toggleLanguage = menu.findItem(R.id.action_toggle_language);
        if (toggleLanguage != null) {
            toggleLanguage.setTitle(isArabic ? "تغيير اللغة (English)" : "Change Language (العربية)");
        }

        MenuItem actionGoldRates = menu.findItem(R.id.action_gold_rates);
        if (actionGoldRates != null) {
            actionGoldRates.setVisible(pm.canViewGoldRates());
        }

        MenuItem actionQuickAccess = menu.findItem(R.id.action_quick_access_settings);
        if (actionQuickAccess != null) {
            actionQuickAccess.setVisible(pm.canManageQuickAccess());
        }

        MenuItem actionDashboard = menu.findItem(R.id.action_dashboard);
        if (actionDashboard != null) {
            actionDashboard.setVisible(pm.canViewDashboard());
        }

        MenuItem actionBranches = menu.findItem(R.id.action_branches);
        if (actionBranches != null) {
            actionBranches.setVisible(pm.isManager() || pm.isAdmin() || pm.isBranchManager());
        }

        MenuItem actionProfile = menu.findItem(R.id.action_profile);
        if (actionProfile != null) {
            actionProfile.setVisible(pm.canViewProfile());
        }

        MenuItem actionLogout = menu.findItem(R.id.action_logout);
        if (actionLogout != null) {
            actionLogout.setVisible(true);
        }

        MenuItem exportItem = menu.findItem(R.id.export);
        if (exportItem != null) {
            exportItem.setVisible(pm.canExportReports() || pm.isManager() || pm.isAuditor());
        }

        MenuItem uhfVer = menu.findItem(R.id.UHF_ver);
        if (uhfVer != null) {
            uhfVer.setVisible(pm.isAdmin() || pm.isSystemAdmin());
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_dashboard) {
            checkInventoryLockAndExecute(() -> {
                clearBackStack();
                loadFragment(new MiraDashboardFragment(), "Dashboard", false);
            });
            return true;
        } else if (id == R.id.action_branches) {
            checkInventoryLockAndExecute(() -> loadFragment(new MiraBranchesManagementFragment(), "Branches"));
            return true;
        } else if (id == R.id.action_gold_rates) {
            checkInventoryLockAndExecute(() -> loadFragment(new MiraGoldCurrencyFragment(), "GoldRates"));
            return true;
        } else if (id == R.id.action_quick_access_settings) {
            checkInventoryLockAndExecute(() -> loadFragment(new MiraQuickAccessSettingsFragment(), "QuickAccessSettings"));
            return true;
        } else if (id == R.id.action_toggle_language) {
            toggleLanguage();
            return true;
        } else if (id == R.id.UHF_ver) {
            showUHFVersionDialog();
            return true;
        } else if (id == R.id.action_profile) {
            checkInventoryLockAndExecute(() -> loadFragment(new MiraProfileFragment(), "Profile"));
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

    private void setupQuickNav() {
        try {
            BottomNavigationView quickNav = findViewById(R.id.bottomNavigationView);
            FloatingActionButton fabActionCenter = findViewById(R.id.fabActionCenter);

            PermissionManager pm = PermissionManager.getInstance(getApplicationContext());

            if (fabActionCenter != null) {
                boolean hasQuickActions = pm.getAllowedQuickActions().size() > 0;
                fabActionCenter.setVisibility(hasQuickActions ? View.VISIBLE : View.GONE);
                fabActionCenter.setEnabled(hasQuickActions);
                fabActionCenter.setOnClickListener(v -> showQuickActionMenu());
            }

            if (quickNav != null) {
                applyBottomNavPermissions(quickNav, pm);

                quickNav.setOnItemSelectedListener(item -> {
    int id = item.getItemId();
    
    String tag = getFragmentTagForNavItem(id);
    Fragment fragment = createFragmentByTag(tag);
    
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

    private void setupRestartButton() {
        try {
            View btnRestartApp = findViewById(R.id.btnRestartApp);
            if (btnRestartApp != null) {
                btnRestartApp.setOnClickListener(v -> showRestartConfirmationDialog());
            }
        } catch (Exception e) {
            Log.e(TAG, "Restart button setup error: " + e.getMessage());
        }
    }

    private void showRestartConfirmationDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(isArabic ? "إعادة تشغيل التطبيق" : "Restart Application")
                .setMessage(isArabic ? 
                    "هل تريد إعادة تشغيل التطبيق؟\nسيتم إغلاق جميع العمليات وإعادة التشغيل." : 
                    "Do you want to restart the application?\nAll processes will be closed and restarted.")
                .setPositiveButton(isArabic ? "إعادة التشغيل" : "Restart", (dialog, which) -> {
                    performAppRestart();
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
    }

    private void performAppRestart() {
        try {
            Toast.makeText(this, 
                isArabic ? "جاري إعادة التشغيل..." : "Restarting...", 
                Toast.LENGTH_SHORT).show();
            
            stopActiveInventory();
            stopUHFScan();
            
            if (soundPool != null) {
                soundPool.release();
                soundPool = null;
            }
            
            hideLoading();
            
            mainHandler.postDelayed(() -> {
                MiraApplication.getInstance().restartApp();
            }, 500);
            
        } catch (Exception e) {
            Log.e(TAG, "Restart error: " + e.getMessage());
            MiraApplication.getInstance().restartApp();
        }
    }

    private void applyBottomNavPermissions(BottomNavigationView quickNav, PermissionManager pm) {
    Menu menu = quickNav.getMenu();

    MenuItem navHome = menu.findItem(R.id.nav_home);
    if (navHome != null) navHome.setVisible(true);

    MenuItem navInventory = menu.findItem(R.id.nav_inventory);
    if (navInventory != null) navInventory.setVisible(true);

    MenuItem navSales = menu.findItem(R.id.nav_sales);
    if (navSales != null) navSales.setVisible(true);

    MenuItem navTransfers = menu.findItem(R.id.nav_transfers);
    if (navTransfers != null) navTransfers.setVisible(true);
}
    private String getFragmentTagForNavItem(int itemId) {
        if (itemId == R.id.nav_home) return "Dashboard";
        if (itemId == R.id.nav_inventory) return "Scan";
        if (itemId == R.id.nav_sales) return "POS";
        if (itemId == R.id.nav_transfers) return "Transfers";
        return "";
    }

    private void showPermissionDenied() {
        Toast.makeText(this,
                isArabic ? "⛔ غير مصرح لك بالوصول لهذه الواجهة" : "⛔ Access Denied",
                Toast.LENGTH_SHORT).show();
    }

        private void showQuickActionMenu() {
        try {
            BottomSheetDialog dialog = new BottomSheetDialog(this, R.style.TransparentBottomSheetDialog);
            View view = getLayoutInflater().inflate(R.layout.dialog_quick_action_grid, null);
            dialog.setContentView(view);

            PermissionManager pm = PermissionManager.getInstance(getApplicationContext());
            MiraQuickAccessSettings quickAccess = MiraQuickAccessSettings.getInstance(getApplicationContext());

            // 1. برمجة RFID
            View btnRfidWrite = view.findViewById(R.id.btnRfidWrite);
            if (btnRfidWrite != null) {
                boolean allowed = pm.canEditInventory() && quickAccess.isActionEnabled("rfid_write");
                btnRfidWrite.setVisibility(allowed ? View.VISIBLE : View.GONE);
                if (allowed) {
                    btnRfidWrite.setOnClickListener(v -> {
                        dialog.dismiss();
                        checkInventoryLockAndExecute(() -> loadFragment(new MiraUHFReadWriteFragment(), "RFIDWrite"));
                    });
                }
            }

            // 2. ربط الشرائح (RfidBindings)
            applyQuickActionButton(view, R.id.btnRfidBindings, dialog,
                    pm.canViewRfidBindings() && quickAccess.isActionEnabled("rfid"),
                    new MiraRfidBindingsFragment(), "RfidBindings");

            // 3. استوديو التكويد (IdentityStudio)
            applyQuickActionButton(view, R.id.btnIdentityStudio, dialog,
                    pm.canAccessIdentityStudio() && quickAccess.isActionEnabled("identity"),
                    new MiraIdentityStudioFragment(), "IdentityStudio");

            // 4. رادار التتبع (Radar)
            applyQuickActionButton(view, R.id.btnRadarLocation, dialog,
                    pm.canAccessRadar() && quickAccess.isActionEnabled("radar"),
                    new MiraUHFRadarLocationFragment(), "Radar");

            // 5. البوابات الأمنية (SecureGate)
            applyQuickActionButton(view, R.id.btnSecureGate, dialog,
                    pm.canAccessGate() && quickAccess.isActionEnabled("gate"),
                    new MiraSecureGateFragment(), "SecureGate");

            // 6. التقارير (Reports)
            applyQuickActionButton(view, R.id.btnReports, dialog,
                    pm.canExportReports() && quickAccess.isActionEnabled("reports"),
                    new MiraReportsProFragment(), "Reports");

            // 7. المحاسبة (Accounting)
            applyQuickActionButton(view, R.id.btnAccounting, dialog,
                    pm.canAccessAccounting() && quickAccess.isActionEnabled("accounting"),
                    new MiraAccountingFragment(), "Accounting");

            // 8. التحويلات (Transfer)
            applyQuickActionButton(view, R.id.btnTransfer, dialog,
                    pm.canAccessTransfers() && quickAccess.isActionEnabled("transfers"),
                    new MiraTransferFragment(), "Transfers");

            // 9. سجل المبيعات (SalesHistory)
            applyQuickActionButton(view, R.id.btnSalesHistory, dialog,
                    pm.canViewSalesHistory() && quickAccess.isActionEnabled("sales_history"),
                    new MiraSalesHistoryFragment(), "SalesHistory");

            // 10. الخيارات الإدارية (AdminHub)
            applyQuickActionButton(view, R.id.btnAdminHub, dialog,
                    pm.isAdmin() && quickAccess.isActionEnabled("admin_hub"),
                    new MiraAdminHubFragment(), "AdminHub");

            // 11. إعدادات السيرفر (ServerConfig)
            applyQuickActionButton(view, R.id.btnServerConfig, dialog,
                    pm.canViewServerConfig() && quickAccess.isActionEnabled("server_config"),
                    new MiraServerConfigFragment(), "ServerConfig");

            // زر الإغلاق السفلي
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
                    checkInventoryLockAndExecute(() -> loadFragment(fragment, tag));
                });
            }
        }
    }

    public void loadFragment(Fragment fragment, String tag) {
    loadFragment(fragment, tag, true);
}

public void loadFragment(Fragment fragment, String tag, boolean addToBackStack) {
    if (isFinishing() || isDestroyed() || fragment == null) return;

    try {
        // ❌ لا showLoading إطلاقاً
        currentFragment = fragment;
        currentFragmentTag = tag;

        if (!addToBackStack) {
            clearBackStack();
        }

        FragmentTransaction transaction = fragmentManager.beginTransaction();
        transaction.setCustomAnimations(0, 0, 0, 0);
        transaction.replace(R.id.fragment_container, fragment, tag);

        if (addToBackStack) {
            transaction.addToBackStack(tag);
        }

        transaction.commitNowAllowingStateLoss();
        
        updateBottomNavSelectionByTag(tag);
        
        // ✅ إخفاء loading فوراً
        hideLoading();

    } catch (Exception e) {
        Log.e(TAG, "Error switching fragment: " + e.getMessage(), e);
        hideLoading();
    }
}
private void loadFragmentDirectly(Fragment fragment, String tag) {
    try {
        currentFragment = fragment;
        currentFragmentTag = tag;
        
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        transaction.replace(R.id.fragment_container, fragment, tag);
        transaction.commitNow();
        
        updateBottomNavSelectionByTag(tag);
        hideLoading();
        
        Log.d(TAG, "Fragment loaded directly: " + tag);
    } catch (Exception e) {
        Log.e(TAG, "Direct load error: " + e.getMessage(), e);
        hideLoading();
    }
}
    private void loadFragmentByTag(String tag) {
        try {
            PermissionManager pm = PermissionManager.getInstance(getApplicationContext());

            if (!pm.canAccessFragment(tag)) {
                tag = "Dashboard";
            }

            Fragment fragment = createFragmentByTag(tag);
            loadFragment(fragment, tag, false);

        } catch (Exception e) {
            Log.e(TAG, "Load fragment by tag error: " + e.getMessage(), e);
            try {
                loadFragment(new MiraDashboardFragment(), "Dashboard", false);
            } catch (Exception ignored) {}
        }
    }

    public Fragment createFragmentByTag(String tag) {
    if (tag == null) return new MiraDashboardFragment();

    switch (tag) {
        case "Dashboard":
            if (ENABLE_DASHBOARD) return new MiraDashboardFragment();
            return new MiraDashboardFragment(); // ✅ دائماً يعمل

        case "Scan":
            if (ENABLE_INVENTORY) return new MiraInventoryProFragment();
            return new MiraDashboardFragment(); // ✅ بديل مؤقت

        case "POS":
            if (ENABLE_POS) return new MiraPosFragment();
            return new MiraDashboardFragment();

        case "Transfers":
            if (ENABLE_TRANSFERS) return new MiraTransferFragment();
            return new MiraDashboardFragment();

        default:
            if (ENABLE_OTHER) {
                // ✅ تفعيل الكل
                switch (tag) {
                    case "IdentityStudio": return new MiraIdentityStudioFragment();
                    case "Radar": return new MiraUHFRadarLocationFragment();
                    case "SecureGate": return new MiraSecureGateFragment();
                    case "Reports": return new MiraReportsProFragment();
                    case "Accounting": return new MiraAccountingFragment();
                    case "AdminHub": return new MiraAdminHubFragment();
                    case "SalesHistory": return new MiraSalesHistoryFragment();
                    case "ServerConfig": return new MiraServerConfigFragment();
                    case "GoldRates": return new MiraGoldCurrencyFragment();
                    case "Profile": return new MiraProfileFragment();
                    case "UsersManagement": return new UsersManagementFragment();
                    case "RFIDWrite": return new MiraUHFReadWriteFragment();
                    case "ReaderSetup": return new MiraReaderSetupFragment();
                    case "GateSettings": return new MiraGateSettingsFragment();
                    case "QuickAccessSettings": return new MiraQuickAccessSettingsFragment();
                    case "Branches": return new MiraBranchesManagementFragment();
                    case "RfidBindings": return new MiraRfidBindingsFragment();
                }
            }
            return new MiraDashboardFragment();
    }
}

    private void showUHFVersionDialog() {
        String miraSystemVersion = "MIRA ID™ v1.0.0 (Enterprise)";
        String swVer = miraSystemVersion;
        String hwVer = null;
        String modelName = Build.MODEL;

        if (!BaseTabFragmentActivity.RFID_DEVICE_PRESENT) {
            hwVer = null;
        } else {
            try {
                if (mReader != null) {
                    hwVer = mReader.getHardwareVersion();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error fetching RFID reader info: " + e.getMessage());
            }
        }

        StringBuilder message = new StringBuilder();
        message.append("MIRA Trust Architecture™\n");
        message.append(isArabic ? "نظام المصادقة والتتبع المعتمد للمجوهرات\n\n" : "Jewelry Tracking & Authentication System\n\n");
        message.append(isArabic ? "📱 الموديل: " : "📱 Model: ").append(modelName).append("\n");
        message.append(isArabic ? "📦 المنظومة: " : "📦 System: ").append(swVer).append("\n");

        if (!BaseTabFragmentActivity.RFID_DEVICE_PRESENT) {
            message.append(isArabic ? "📡 العتاد: غير متصل" : "📡 Hardware: Not Connected").append("\n");
            message.append(isArabic ? "⏳ الحالة: بانتظار وصول الجهاز" : "⏳ Status: Waiting for device");
        } else if (hwVer == null || hwVer.equalsIgnoreCase("null") || hwVer.trim().isEmpty()) {
            message.append(isArabic ? "📡 العتاد: غير متهيئ" : "📡 Hardware: Not Configured").append("\n");
            message.append(isArabic ? "🔴 الحالة: غير متصل بقارئ RFID" : "🔴 Status: Disconnected");
        } else {
            message.append(isArabic ? "📡 العتاد: " : "📡 Hardware: ").append(hwVer).append("\n");
            message.append(isArabic ? "🟢 الحالة: وحدة UHF نشطة ومتصلة" : "🟢 Status: Active & Connected");
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(isArabic ? "عن المنظومة | MIRA ID™" : "About System | MIRA ID™")
                .setMessage(message.toString())
                .setPositiveButton(isArabic ? "موافق" : "OK", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void updateBottomNavSelectionByTag(String tag) {
        try {
            BottomNavigationView quickNav = findViewById(R.id.bottomNavigationView);
            if (quickNav == null || tag == null) return;

            Menu menu = quickNav.getMenu();
            if (tag.equals("Dashboard")) {
                menu.findItem(R.id.nav_home).setChecked(true);
            } else if (tag.equals("Scan")) {
                menu.findItem(R.id.nav_inventory).setChecked(true);
            } else if (tag.equals("POS")) {
                menu.findItem(R.id.nav_sales).setChecked(true);
            } else if (tag.equals("Transfers")) {
                menu.findItem(R.id.nav_transfers).setChecked(true);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating bottom nav selection: " + e.getMessage());
        }
    }

    private Fragment getTopFragment() {
        if (fragmentManager != null && fragmentManager.getBackStackEntryCount() > 0) {
            FragmentManager.BackStackEntry entry =
                    fragmentManager.getBackStackEntryAt(fragmentManager.getBackStackEntryCount() - 1);
            return fragmentManager.findFragmentByTag(entry.getName());
        }
        return currentFragment;
    }

    private void clearBackStack() {
        if (fragmentManager != null && fragmentManager.getBackStackEntryCount() > 0) {
            fragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }
    }

    private void showHardwareOptionsDialog() {
        PermissionManager pm = PermissionManager.getInstance(getApplicationContext());
        List<String> optionsList = new ArrayList<>();

        if (pm.isAdmin() || pm.isInventoryManager()) {
            optionsList.add(isArabic ? "إعدادات قارئ RFID" : "RFID Reader Setup");
        }

        if (pm.isAdmin() || pm.isSecurity() || pm.isGateSecurity()) {
            optionsList.add(isArabic ? "تهيئة البوابات الأمنية" : "Gate Security Setup");
        }

        if (pm.canViewServerConfig()) {
            optionsList.add(isArabic ? "🌐 إعدادات السيرفر" : "🌐 Server Config");
        }
        if (pm.isAdmin() || pm.isManager() || pm.isInventoryManager()) {
            optionsList.add(isArabic ? "📋 إدارة الشرائح المبرمجة" : "📋 RFID Bindings");
        }

        optionsList.add(isArabic ? "اختبار التوصيل" : "Connection Test");

        String[] options = optionsList.toArray(new String[0]);

        new MaterialAlertDialogBuilder(this)
                .setTitle(isArabic ? "📡 أجهزة ومستشعرات المنظومة" : "📡 Hardware & Sensors")
                .setItems(options, (dialog, which) -> {
                    String selected = options[which];

                    if (selected.contains("RFID") || selected.contains("قارئ")) {
                        loadFragment(new MiraReaderSetupFragment(), "ReaderSetup");
                    } else if (selected.contains("Gate") || selected.contains("بوابات")) {
                        loadFragment(new MiraGateSettingsFragment(), "GateSettings");
                    } else if (selected.contains("Server") || selected.contains("سيرفر")) {
                        loadFragment(new MiraServerConfigFragment(), "ServerConfig");
                    } else if (selected.contains("Bindings") || selected.contains("شرائح")) {
                        loadFragment(new MiraRfidBindingsFragment(), "RfidBindings");
                    } else {
                        Toast.makeText(this, isArabic ? "الأجهزة متصلة" : "Devices connected", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    public void startUHFScan() {
        if (!BaseTabFragmentActivity.RFID_DEVICE_PRESENT) {
            Log.d(TAG, "RFID device not present - skipping scan");
            return;
        }
        
        if (!isReaderReady()) {
            Log.d(TAG, "RFID reader not ready - skipping scan");
            return;
        }
        
        try {
            loopFlag = true;
            if (mReader != null) {
                mReader.startInventoryTag();
            }
        } catch (Exception e) {
            Log.e(TAG, "startUHFScan Error: " + e.getMessage());
        }
    }

    public void stopUHFScan() {
        if (!BaseTabFragmentActivity.RFID_DEVICE_PRESENT) {
            return;
        }
        
        try {
            loopFlag = false;
            if (mReader != null) {
                mReader.stopInventory();
            }
        } catch (Exception e) {
            Log.e(TAG, "stopUHFScan Error: " + e.getMessage());
        }
    }

    public void openReceiptFragment(String rawJson) {
        try {
            MiraReceiptFragment fragment = MiraReceiptFragment.newInstance(rawJson);
            fragment.show(fragmentManager, "Receipt");
        } catch (Exception e) {
            Log.e(TAG, "openReceiptFragment Error: " + e.getMessage());
        }
    }

    public void showToast(String text) {
        if (text == null) return;
        mainHandler.post(() -> Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT).show());
    }

    public void showToast(int resId) {
        mainHandler.post(() -> Toast.makeText(getApplicationContext(), getString(resId), Toast.LENGTH_SHORT).show());
    }

    public void playSoundDelayed(int val) {
        mainHandler.postDelayed(() -> {
            try {
                playSound(val);
            } catch (Exception e) {
                Log.e(TAG, "Error playing delayed sound: " + e.getMessage());
            }
        }, 10);
    }

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

    @Override
    public void onBackPressed() {
        if (isInventoryActive()) {
            checkInventoryLockAndExecute(this::handleBackNavigation);
            return;
        }
        handleBackNavigation();
    }

    private void handleBackNavigation() {
        if (fragmentManager != null) {
            for (Fragment fragment : fragmentManager.getFragments()) {
                if (fragment instanceof androidx.fragment.app.DialogFragment) {
                    androidx.fragment.app.DialogFragment dialogFragment =
                            (androidx.fragment.app.DialogFragment) fragment;
                    if (dialogFragment.getDialog() != null && dialogFragment.getDialog().isShowing()) {
                        dialogFragment.dismissAllowingStateLoss();
                        return;
                    }
                }
            }

            if (fragmentManager.getBackStackEntryCount() > 0) {
                fragmentManager.popBackStackImmediate();
                Fragment topFragment = getTopFragment();
                if (topFragment != null) {
                    String tag = topFragment.getTag();

                    PermissionManager pm = PermissionManager.getInstance(getApplicationContext());
                    if (tag != null && !pm.canAccessFragment(tag)) {
                        clearBackStack();
                        loadFragment(new MiraDashboardFragment(), "Dashboard", false);
                        return;
                    }

                    currentFragment = topFragment;
                    if (tag != null) {
                        currentFragmentTag = tag;
                        saveFragmentTagToPrefs(tag);
                    }
                }
                return;
            }
        }

        if (currentFragment != null && !(currentFragment instanceof MiraDashboardFragment)) {
            clearBackStack();
            loadFragment(new MiraDashboardFragment(), "Dashboard", false);
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

    private void updateBottomNavLanguage() {
        try {
            BottomNavigationView nav = findViewById(R.id.bottomNavigationView);
            if (nav != null && nav.getMenu() != null) {
                Menu menu = nav.getMenu();

                MenuItem home = menu.findItem(R.id.nav_home);
                if (home != null) home.setTitle(isArabic ? "الرئيسية" : "Home");

                MenuItem inventory = menu.findItem(R.id.nav_inventory);
                if (inventory != null) inventory.setTitle(isArabic ? "الجرد" : "Inventory");

                MenuItem sales = menu.findItem(R.id.nav_sales);
                if (sales != null) sales.setTitle(isArabic ? "البيع" : "Sales");

                MenuItem transfers = menu.findItem(R.id.nav_transfers);
                if (transfers != null) transfers.setTitle(isArabic ? "التحويلات" : "Transfers");
            }
        } catch (Exception e) {
            Log.e(TAG, "Update bottom nav language error: " + e.getMessage());
        }
    }

    public void showLoading() {
   /* mainHandler.post(() -> {
        if (loadingOverlay != null && !isFinishing() && !isDestroyed()) {
            TextView tvTitle = loadingOverlay.findViewById(R.id.tvLoadingTitle);
            if (tvTitle != null) tvTitle.setText(isArabic ? "جاري التحميل..." : "Loading...");
            loadingOverlay.setVisibility(View.VISIBLE);
            loadingOverlay.setAlpha(1.0f);
            loadingOverlay.setClickable(true);
            loadingOverlay.setFocusable(true);
            
            // ✅ إخفاء تلقائي بعد 3 ثواني كحد أقصى
            mainHandler.postDelayed(this::hideLoading, 3000);
        }
    });*/
    
}

public void hideLoading() {
    /*mainHandler.post(() -> {
        if (loadingOverlay != null) {
            loadingOverlay.setVisibility(View.GONE);
            loadingOverlay.setAlpha(0.0f);
            loadingOverlay.setClickable(false);
            loadingOverlay.setFocusable(false);
        }
    });*/
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
        try {
            soundPool = new SoundPool.Builder()
                    .setMaxStreams(10)
                    .setAudioAttributes(
                            new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                    .build()
                    )
                    .build();
            
            soundPool.setOnLoadCompleteListener((soundPool, sampleId, status) -> {
                if (status == 0) {
                    Log.d(TAG, "Sound loaded successfully: " + sampleId);
                } else {
                    Log.e(TAG, "Failed to load sound: " + sampleId);
                }
            });
            
            soundMap.put(1, soundPool.load(this, R.raw.barcodebeep, 1));
            soundMap.put(2, soundPool.load(this, R.raw.alarm_breach_security, 2));
            audioManager = (AudioManager) this.getSystemService(AUDIO_SERVICE);
            
        } catch (Exception e) {
            Log.e(TAG, "Sound init error: " + e.getMessage());
        }
    }

    public void playSound(int id) {
        if (soundPool == null || audioManager == null) return;
        
        try {
            Integer soundId = soundMap.get(id);
            if (soundId == null) return;
            
            float max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            float cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            float volumeRatio = max > 0 ? cur / max : 0.5f;
            
            soundPool.play(soundId, volumeRatio, volumeRatio, 1, 0, 1.0f);
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
                    ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), PERMISSION_REQUEST_CODE);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Permission Error: " + e.getMessage());
        }
    }

    private void navigateToLogin() {
        try {
            hideLoading();
            
            try {
                if (mReader != null) {
                    mReader.free();
                    mReader = null;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error resetting RFID services", e);
            }

            Intent intent = new Intent(this, LoginActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        } catch (Exception e) {
            Log.e(TAG, "Navigation to login error: " + e.getMessage());
        }
    }
    
    @Override
    protected void onDestroy() {
        try {
            AppWatchdog.getInstance().stopMonitoring();
            hideLoading();
            stopActiveInventory();

            if (soundPool != null) {
                try {
                    soundPool.release();
                } catch (Exception e) {
                    Log.e(TAG, "Sound release error: " + e.getMessage());
                }
                soundPool = null;
            }
            audioManager = null;
            
            if (fragmentManager != null) {
                try {
                    clearBackStack();
                } catch (Exception e) {
                    Log.e(TAG, "Backstack clear error: " + e.getMessage());
                }
            }
            
            if (orgProfileManager != null && profileListener != null) {
                try {
                    orgProfileManager.removeListener(profileListener);
                } catch (Exception e) {
                    Log.e(TAG, "Listener removal error: " + e.getMessage());
                }
            }
            
            try {
                SessionMonitorManager.getInstance(this).stopMonitoring();
            } catch (Exception e) {
                Log.e(TAG, "Session monitor stop error: " + e.getMessage());
            }

            activePOSFragment = null;
            activeEncodingFragment = null;
            activeRfidListener = null;
            
            if (tagList != null) {
                tagList.clear();
                tagList = null;
            }

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
}
