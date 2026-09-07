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
import android.widget.FrameLayout;
import android.widget.TabHost;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
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

    private static final boolean ENABLE_DASHBOARD = true;
    private static final boolean ENABLE_INVENTORY = true;
    private static final boolean ENABLE_POS = true;
    private static final boolean ENABLE_TRANSFERS = true;
    private static final boolean ENABLE_OTHER = true;

    private static final String TAG = "UHFMainActivity";
    private static final String PREFS_NAVIGATION = "app_navigation";
    private static final String KEY_CURRENT_FRAGMENT = "current_fragment_tag";

    public TabHost mTabHost = null;

    private boolean isProfileListenerSetup = false;
    private MiraOrgProfileManager orgProfileManager;
    private MiraOrgProfileManager.OnProfileChangedListener profileListener;

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

    private String currentFragmentTag = "Dashboard";

    private long lastBackPressTime = 0;
    private static final long DOUBLE_BACK_TIMEOUT = 2000;

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
                String orgName = "MIRA ID";
                if (orgProfileManager != null) {
                    String name = orgProfileManager.getDisplayName();
                    if (name != null && !name.trim().isEmpty()) {
                        orgName = name;
                    }
                }
                getSupportActionBar().setTitle(orgName);

                if (branchesManager != null && branchesManager.hasActiveBranch()) {
                    getSupportActionBar().setSubtitle("📍 " + branchesManager.getActiveBranchDisplayName());
                } else {
                    getSupportActionBar().setSubtitle(isArabic ? "النظام الرئيسي" : "Main Hub");
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

            cleanupOldFragments();

            // الحارس التلقائي الذكي لمنع التجمد وإلغاء الحاجة لزر إعادة التشغيل اليدوي
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
            setupOrgProfileListener();

            if (savedInstanceState == null) {
                currentFragmentTag = "Dashboard";
            } else {
                currentFragmentTag = savedInstanceState.getString("current_fragment", "Dashboard");
            }

            loadFragmentDirectly(new MiraDashboardFragment(), "Dashboard");

        } catch (Exception e) {
            Log.e(TAG, "Fatal error in onCreate: " + e.getMessage(), e);
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

    @Override
    public void onResume() {
        super.onResume();
        updateToolbarTitle();
        mainHandler.postDelayed(this::hideLoading, 100);
    }

    @Override
    public void onPause() {
        super.onPause();
        hideLoading();
        AppWatchdog.getInstance().stopMonitoring();
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

    @SuppressLint("RestrictedApi")
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);

        if (menu instanceof MenuBuilder) {
            ((MenuBuilder) menu).setOptionalIconsVisible(true);
        }

        PermissionManager pm = PermissionManager.getInstance(getApplicationContext());
        applyToolbarMenuPermissions(menu, pm);
        return true;
    }

    private void applyToolbarMenuPermissions(Menu menu, PermissionManager pm) {
        if (pm == null) return;

        MenuItem toggleLanguage = menu.findItem(R.id.action_toggle_language);
        if (toggleLanguage != null) {
            toggleLanguage.setTitle(isArabic ? "English" : "العربية");
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

        MenuItem actionHardware = menu.findItem(R.id.action_hardware_setup);
        if (actionHardware != null) {
            actionHardware.setVisible(true);
        }

        MenuItem actionExport = menu.findItem(R.id.export);
        if (actionExport != null) {
            actionExport.setVisible(pm.canExportReports());
        }

        MenuItem actionLogout = menu.findItem(R.id.action_logout);
        if (actionLogout != null) {
            actionLogout.setVisible(true);
        }

        MenuItem uhfVer = menu.findItem(R.id.UHF_ver);
        if (uhfVer != null) {
            uhfVer.setVisible(pm.isAdmin() || pm.isSystemAdmin());
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_hardware_setup) {
            showHardwareOptionsDialog();
            return true;
        } else if (id == R.id.action_dashboard) {
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
        } else if (id == R.id.export) {
            checkInventoryLockAndExecute(() -> loadFragment(new MiraReportsProFragment(), "Reports"));
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

    private void showQuickActionMenu() {
        try {
            BottomSheetDialog dialog = new BottomSheetDialog(this, R.style.TransparentBottomSheetDialog);
            View view = getLayoutInflater().inflate(R.layout.dialog_quick_action_grid, null);
            dialog.setContentView(view);

            PermissionManager pm = PermissionManager.getInstance(getApplicationContext());
            MiraQuickAccessSettings quickAccess = MiraQuickAccessSettings.getInstance(getApplicationContext());

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

            transaction.commit();
            fragmentManager.executePendingTransactions();

            updateBottomNavSelectionByTag(tag);
            hideLoading();

        } catch (Exception e) {
            Log.e(TAG, "Error switching fragment: " + e.getMessage(), e);
            try {
                FragmentTransaction transaction = fragmentManager.beginTransaction();
                transaction.replace(R.id.fragment_container, fragment, tag);
                transaction.commitNowAllowingStateLoss();
                updateBottomNavSelectionByTag(tag);
            } catch (Exception e2) {
                Log.e(TAG, "Final fallback failed: " + e2.getMessage(), e2);
            }
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
        } catch (Exception e) {
            Log.e(TAG, "Direct load error: " + e.getMessage(), e);
            hideLoading();
        }
    }

    public Fragment createFragmentByTag(String tag) {
        if (tag == null) return new MiraDashboardFragment();

        switch (tag) {
            case "Dashboard":
                return new MiraDashboardFragment();
            case "Scan":
                return ENABLE_INVENTORY ? new MiraInventoryProFragment() : new MiraDashboardFragment();
            case "POS":
                return ENABLE_POS ? new MiraPosFragment() : new MiraDashboardFragment();
            case "Transfers":
                return ENABLE_TRANSFERS ? new MiraTransferFragment() : new MiraDashboardFragment();
            default:
                if (ENABLE_OTHER) {
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

    private void showHardwareOptionsDialog() {
        PermissionManager pm = PermissionManager.getInstance(getApplicationContext());
        List<String> optionsList = new ArrayList<>();

        if (pm.isAdmin() || pm.isInventoryManager() || pm.isSystemAdmin()) {
            optionsList.add(isArabic ? "📡 إعدادات قارئ RFID" : "📡 RFID Reader Setup");
        }
        if (pm.isAdmin() || pm.isSecurity() || pm.isGateSecurity()) {
            optionsList.add(isArabic ? "🚧 تهيئة البوابات الأمنية" : "🚧 Gate Security Setup");
        }
        if (pm.canViewServerConfig()) {
            optionsList.add(isArabic ? "🌐 إعدادات السيرفر" : "🌐 Server Config");
        }
        if (pm.isAdmin() || pm.isManager() || pm.isInventoryManager()) {
            optionsList.add(isArabic ? "🏷️ إدارة الشرائح المبرمجة" : "🏷️ RFID Bindings");
        }

        optionsList.add(isArabic ? "🔄 اختبار التوصيل والربط" : "🔄 Connection Test");

        String[] options = optionsList.toArray(new String[0]);

        new MaterialAlertDialogBuilder(this)
                .setTitle(isArabic ? "أجهزة ومستشعرات المنظومة" : "Hardware & Sensors")
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
                        Toast.makeText(this, isArabic ? "الأجهزة تعمل بحالة جيدة" : "All devices operational", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void showUHFVersionDialog() {
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
        message.append(isArabic ? "📱 الموديل: " : "📱 Model: ").append(modelName).append("\n");
        message.append(isArabic ? "📦 المنظومة: " : "📦 System: ").append(miraSystemVersion).append("\n");

        if (!BaseTabFragmentActivity.RFID_DEVICE_PRESENT) {
            message.append(isArabic ? "📡 العتاد: غير متصل" : "📡 Hardware: Not Connected");
        } else if (hwVer == null || hwVer.trim().isEmpty()) {
            message.append(isArabic ? "📡 العتاد: غير متهيئ" : "📡 Hardware: Not Configured");
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

    public void startUHFScan() {
        if (!BaseTabFragmentActivity.RFID_DEVICE_PRESENT || !isReaderReady()) return;
        try {
            loopFlag = true;
            if (mReader != null) mReader.startInventoryTag();
        } catch (Exception e) {
            Log.e(TAG, "startUHFScan Error: " + e.getMessage());
        }
    }

    public void stopUHFScan() {
        if (!BaseTabFragmentActivity.RFID_DEVICE_PRESENT) return;
        try {
            loopFlag = false;
            if (mReader != null) mReader.stopInventory();
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

    // --- 🛠️ دوال المساعدة للرسائل والأصوات (حل أخطاء الـ Compile) ---

    public void showToast(int resId) {
        showToast(getString(resId));
    }

    public void showToast(String text) {
        if (text == null) return;
        mainHandler.post(() -> Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT).show());
    }

    public void playSound(int id) {
        try {
            if (soundUtils != null) {
                soundUtils.play(id);
            }
        } catch (Exception e) {
            Log.e(TAG, "playSound Error: " + e.getMessage());
        }
    }

    public void playSoundDelayed(int value) {
        try {
            mainHandler.postDelayed(() -> playSound(1), Math.max(10, 1000 - (value * 10)));
        } catch (Exception e) {
            Log.e(TAG, "playSoundDelayed Error: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------

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
                    androidx.fragment.app.DialogFragment dialogFragment = (androidx.fragment.app.DialogFragment) fragment;
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

    public void showLoading() {}

    public void hideLoading() {}

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

    @Override
    protected void onDestroy() {
        try {
            AppWatchdog.getInstance().stopMonitoring();
            hideLoading();
            stopActiveInventory();

            if (fragmentManager != null) {
                clearBackStack();
            }

            if (orgProfileManager != null && profileListener != null) {
                orgProfileManager.removeListener(profileListener);
            }

            SessionMonitorManager.getInstance(this).stopMonitoring();

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
}
