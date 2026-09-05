package com.example.uhf.activity;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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

import com.example.uhf.R;
import com.example.uhf.fragment.*;
import com.example.uhf.manager.*;
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
                    if (name != null && !name.trim().isEmpty()) orgName = name;
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

            ToastUtil.init(getApplicationContext());
            initializeLanguageState();

            SessionMonitorManager.getInstance(this).startMonitoring(this);
            branchesManager = MiraBranchesManager.getInstance(getApplicationContext());
            if (branchesManager != null) lastActiveBranchId = branchesManager.getActiveBranchId();

            loadingOverlay = findViewById(R.id.loading_overlay);
            fragmentManager = getSupportFragmentManager();

            setupOrgProfileListener();
            updateBottomNavLanguage();

            MaterialToolbar toolbar = findViewById(R.id.toolbar);
            if (toolbar != null) {
                setSupportActionBar(toolbar);
                updateToolbarTitle();
            }

            View btnHardwareStatus = findViewById(R.id.btnHardwareStatus);
            if (btnHardwareStatus != null) {
                btnHardwareStatus.setOnClickListener(v -> showHardwareOptionsDialog());
            }

            checkReadWritePermission();
            
            // تهيئة العتاد والصوت بشكل آمن
            initSound();
            initUHF(); // يتم تنفيذه الآن بشكل غير متزامن كلياً عبر BaseTabFragmentActivity

            PermissionManager.resetInstance();
            setupQuickNav();
            loadEssentialData();

            if (savedInstanceState == null) {
                currentFragmentTag = "Dashboard";
            } else {
                currentFragmentTag = savedInstanceState.getString("current_fragment", "Dashboard");
            }
            loadFragmentByTag(currentFragmentTag);

        } catch (Exception e) {
            Log.e(TAG, "Fatal error in onCreate: " + e.getMessage(), e);
        }
    }

    private void loadEssentialData() {
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                com.example.uhf.api.MiraApiClient apiClient = com.example.uhf.api.MiraApiClient.getInstance(getApplicationContext());

                com.example.uhf.api.MiraApiClient.ApiResponse profileResponse = apiClient.getOrgProfile();
                if (profileResponse != null && profileResponse.isSuccess && profileResponse.data != null) {
                    org.json.JSONObject org = profileResponse.data.optJSONObject("organization");
                    if (org != null) MiraOrgProfileManager.getInstance(getApplicationContext()).saveFromServer(org);
                }

                com.example.uhf.api.MiraApiClient.ApiResponse branchesResponse = apiClient.getBranches();
                if (branchesResponse != null && branchesResponse.isSuccess && branchesResponse.data != null) {
                    MiraBranchesManager.getInstance(getApplicationContext()).saveBranchesFromServer(branchesResponse.data);
                }

                com.example.uhf.api.MiraApiClient.ApiResponse goldResponse = apiClient.getGoldCurrencySettings();
                if (goldResponse != null && goldResponse.isSuccess && goldResponse.data != null) {
                    org.json.JSONObject data = goldResponse.data.optJSONObject("data");
                    if (data == null) data = goldResponse.data;
                    if (data.optJSONObject("gold") != null)
                        com.example.uhf.manager.MiraPriceManager.getInstance(getApplicationContext()).saveGoldSettings(data.optJSONObject("gold"));
                    if (data.optJSONObject("currency") != null)
                        com.example.uhf.manager.MiraPriceManager.getInstance(getApplicationContext()).saveCurrencySettings(data.optJSONObject("currency"));
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading essential data: " + e.getMessage());
            } finally {
                mainHandler.post(() -> {
                    updateToolbarTitle();
                    hideLoading();
                });
            }
        });
        executor.shutdown();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateToolbarTitle();
    }

    private void initializeLanguageState() {
        try {
            isArabic = getResources().getConfiguration().locale.getLanguage().equals("ar");
            getWindow().getDecorView().setLayoutDirection(isArabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        } catch (Exception e) {
            Log.w(TAG, "Locale check failed");
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
            getSharedPreferences(PREFS_NAVIGATION, Context.MODE_PRIVATE).edit().putString(KEY_CURRENT_FRAGMENT, tag).apply();
        } catch (Exception e) {
            Log.e(TAG, "Save prefs error");
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
        if (toggleLanguage != null) toggleLanguage.setTitle(isArabic ? "تغيير اللغة (English)" : "Change Language (العربية)");

        if (menu.findItem(R.id.action_gold_rates) != null) menu.findItem(R.id.action_gold_rates).setVisible(pm.canViewGoldRates());
        if (menu.findItem(R.id.action_quick_access_settings) != null) menu.findItem(R.id.action_quick_access_settings).setVisible(pm.canManageQuickAccess());
        if (menu.findItem(R.id.action_dashboard) != null) menu.findItem(R.id.action_dashboard).setVisible(pm.canViewDashboard());
        if (menu.findItem(R.id.action_branches) != null) menu.findItem(R.id.action_branches).setVisible(pm.isManager() || pm.isAdmin() || pm.isBranchManager());
        if (menu.findItem(R.id.action_profile) != null) menu.findItem(R.id.action_profile).setVisible(pm.canViewProfile());
        if (menu.findItem(R.id.action_logout) != null) menu.findItem(R.id.action_logout).setVisible(true);
        if (menu.findItem(R.id.export) != null) menu.findItem(R.id.export).setVisible(pm.canExportReports() || pm.isManager() || pm.isAuditor());
        if (menu.findItem(R.id.UHF_ver) != null) menu.findItem(R.id.UHF_ver).setVisible(pm.isAdmin() || pm.isSystemAdmin());
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_dashboard) { checkInventoryLockAndExecute(() -> { clearBackStack(); loadFragment(new MiraDashboardFragment(), "Dashboard", false); }); return true; }
        if (id == R.id.action_branches) { checkInventoryLockAndExecute(() -> loadFragment(new MiraBranchesManagementFragment(), "Branches")); return true; }
        if (id == R.id.action_gold_rates) { checkInventoryLockAndExecute(() -> loadFragment(new MiraGoldCurrencyFragment(), "GoldRates")); return true; }
        if (id == R.id.action_quick_access_settings) { checkInventoryLockAndExecute(() -> loadFragment(new MiraQuickAccessSettingsFragment(), "QuickAccessSettings")); return true; }
        if (id == R.id.action_toggle_language) { toggleLanguage(); return true; }
        if (id == R.id.UHF_ver) { showUHFVersionDialog(); return true; }
        if (id == R.id.action_profile) { checkInventoryLockAndExecute(() -> loadFragment(new MiraProfileFragment(), "Profile")); return true; }
        if (id == R.id.action_logout) { confirmLogout(); return true; }
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
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null).show();
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
                    if (id == R.id.nav_placeholder) { showQuickActionMenu(); return false; }
                    String fragmentTag = getFragmentTagForNavItem(id);
                    if (fragmentTag.isEmpty()) return false;
                    if (!pm.canAccessFragment(fragmentTag)) { showPermissionDenied(); return false; }

                    if (id == R.id.nav_home) { checkInventoryLockAndExecute(() -> { clearBackStack(); loadFragment(new MiraDashboardFragment(), "Dashboard", false); }); return true; }
                    if (id == R.id.nav_inventory) { checkInventoryLockAndExecute(() -> loadFragment(new MiraInventoryProFragment(), "Scan")); return true; }
                    if (id == R.id.nav_sales) { checkInventoryLockAndExecute(() -> loadFragment(new MiraPosFragment(), "POS")); return true; }
                    if (id == R.id.nav_transfers) { checkInventoryLockAndExecute(() -> loadFragment(new MiraTransferFragment(), "Transfers")); return true; }
                    return false;
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "QuickNav Setup Error: " + e.getMessage());
        }
    }

    private void applyBottomNavPermissions(BottomNavigationView quickNav, PermissionManager pm) {
        Menu menu = quickNav.getMenu();
        if (menu.findItem(R.id.nav_home) != null) menu.findItem(R.id.nav_home).setVisible(pm.canViewDashboard());
        if (menu.findItem(R.id.nav_inventory) != null) menu.findItem(R.id.nav_inventory).setVisible(pm.canViewInventory());
        if (menu.findItem(R.id.nav_sales) != null) menu.findItem(R.id.nav_sales).setVisible(pm.canUsePos() || pm.canViewSalesHistory());
        if (menu.findItem(R.id.nav_transfers) != null) menu.findItem(R.id.nav_transfers).setVisible(pm.canViewTransfers());
        
        MenuItem navPlaceholder = menu.findItem(R.id.nav_placeholder);
        if (navPlaceholder != null) {
            int visibleCount = 0;
            for (int i = 0; i < menu.size(); i++) if (menu.getItem(i).isVisible()) visibleCount++;
            navPlaceholder.setVisible(visibleCount >= 3);
        }
    }

    private String getFragmentTagForNavItem(int itemId) {
        if (itemId == R.id.nav_home) return "Dashboard";
        if (itemId == R.id.nav_inventory) return "Scan";
        if (itemId == R.id.nav_sales) return "POS";
        if (itemId == R.id.nav_transfers) return "Transfers";
        return "";
    }

    private void showPermissionDenied() {
        Toast.makeText(this, isArabic ? "⛔ غير مصرح لك بالوصول لهذه الواجهة" : "⛔ Access Denied", Toast.LENGTH_SHORT).show();
    }

    private void showQuickActionMenu() {
        try {
            BottomSheetDialog dialog = new BottomSheetDialog(this, R.style.TransparentBottomSheetDialog);
            View view = getLayoutInflater().inflate(R.layout.dialog_quick_action_grid, null);
            dialog.setContentView(view);
            PermissionManager pm = PermissionManager.getInstance(getApplicationContext());
            MiraQuickAccessSettings quickAccess = MiraQuickAccessSettings.getInstance(getApplicationContext());
            List<String> allowedActions = pm.getAllowedQuickActions();

            View btnRfidWrite = view.findViewById(R.id.btnRfidWrite);
            if (btnRfidWrite != null) {
                boolean allowed = pm.canEditInventory() && quickAccess.isActionEnabled("rfid_write");
                btnRfidWrite.setVisibility(allowed ? View.VISIBLE : View.GONE);
                if (allowed) btnRfidWrite.setOnClickListener(v -> { dialog.dismiss(); checkInventoryLockAndExecute(() -> loadFragment(new MiraUHFReadWriteFragment(), "RFIDWrite")); });
            }

            applyQuickActionButton(view, R.id.btnRfidBindings, dialog, allowedActions.contains("rfid") && quickAccess.isActionEnabled("rfid"), new MiraRfidBindingsFragment(), "RfidBindings");
            applyQuickActionButton(view, R.id.btnIdentityStudio, dialog, allowedActions.contains("identity") && quickAccess.isActionEnabled("identity"), new MiraIdentityStudioFragment(), "IdentityStudio");
            applyQuickActionButton(view, R.id.btnRadarLocation, dialog, allowedActions.contains("radar") && quickAccess.isActionEnabled("radar"), new MiraUHFRadarLocationFragment(), "Radar");
            applyQuickActionButton(view, R.id.btnSecureGate, dialog, allowedActions.contains("gate") && quickAccess.isActionEnabled("gate"), new MiraSecureGateFragment(), "SecureGate");
            applyQuickActionButton(view, R.id.btnReports, dialog, allowedActions.contains("reports") && quickAccess.isActionEnabled("reports"), new MiraReportsProFragment(), "Reports");
            applyQuickActionButton(view, R.id.btnAccounting, dialog, allowedActions.contains("accounting") && quickAccess.isActionEnabled("accounting"), new MiraAccountingFragment(), "Accounting");
            applyQuickActionButton(view, R.id.btnTransfer, dialog, allowedActions.contains("transfers") && quickAccess.isActionEnabled("transfers"), new MiraTransferFragment(), "Transfers");
            applyQuickActionButton(view, R.id.btnSalesHistory, dialog, allowedActions.contains("sales_history") && quickAccess.isActionEnabled("sales_history"), new MiraSalesHistoryFragment(), "SalesHistory");
            applyQuickActionButton(view, R.id.btnAdminHub, dialog, allowedActions.contains("admin_hub") && quickAccess.isActionEnabled("admin_hub"), new MiraAdminHubFragment(), "AdminHub");
            applyQuickActionButton(view, R.id.btnServerConfig, dialog, allowedActions.contains("server_config") && quickAccess.isActionEnabled("server_config"), new MiraServerConfigFragment(), "ServerConfig");

            View fabCloseSheet = view.findViewById(R.id.fabCloseSheet);
            if (fabCloseSheet != null) fabCloseSheet.setOnClickListener(v -> dialog.dismiss());
            dialog.show();
        } catch (Exception e) {
            Log.e(TAG, "QuickActionMenu Error: " + e.getMessage(), e);
        }
    }

    private void applyQuickActionButton(View root, int viewId, BottomSheetDialog dialog, boolean allowed, Fragment fragment, String tag) {
        View btn = root.findViewById(viewId);
        if (btn != null) {
            btn.setVisibility(allowed ? View.VISIBLE : View.GONE);
            if (allowed) btn.setOnClickListener(v -> { dialog.dismiss(); checkInventoryLockAndExecute(() -> loadFragment(fragment, tag)); });
        }
    }

    public void loadFragment(Fragment fragment, String tag) { loadFragment(fragment, tag, true); }

    public void loadFragment(Fragment fragment, String tag, boolean addToBackStack) {
        if (isFinishing() || isDestroyed() || fragment == null) return;
        try {
            showLoading();
            currentFragment = fragment;
            currentFragmentTag = tag;
            saveFragmentTagToPrefs(tag);

            if (!addToBackStack) clearBackStack();

            FragmentTransaction transaction = fragmentManager.beginTransaction();
            transaction.setCustomAnimations(0, 0, 0, 0);
            transaction.replace(R.id.fragment_container, fragment, tag);
            if (addToBackStack) transaction.addToBackStack(tag);
            transaction.commitAllowingStateLoss();
            
            updateBottomNavSelectionByTag(tag);
            mainHandler.postDelayed(this::hideLoading, 150);
        } catch (Exception e) {
            Log.e(TAG, "Error switching fragment: " + e.getMessage());
            hideLoading();
        }
    }

    private void loadFragmentByTag(String tag) {
        try {
            PermissionManager pm = PermissionManager.getInstance(getApplicationContext());
            if (!pm.canAccessFragment(tag)) tag = "Dashboard";
            Fragment fragment = createFragmentByTag(tag);
            loadFragment(fragment, tag, false);
        } catch (Exception e) {
            try { loadFragment(new MiraDashboardFragment(), "Dashboard", false); } catch (Exception ignored) {}
        }
    }

    private Fragment createFragmentByTag(String tag) {
        if (tag == null) return new MiraDashboardFragment();
        switch (tag) {
            case "Dashboard": return new MiraDashboardFragment();
            case "Branches": return new MiraBranchesManagementFragment();
            case "RfidBindings": return new MiraRfidBindingsFragment();
            case "Scan": return new MiraInventoryProFragment();
            case "POS": return new MiraPosFragment();
            case "Transfers": return new MiraTransferFragment();
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
            default: return new MiraDashboardFragment();
        }
    }

    private void showUHFVersionDialog() {
        rfidExecutor.execute(() -> {
            String hwVer = null;
            try { if (mReader != null) hwVer = mReader.getHardwareVersion(); } catch (Exception ignored) {}
            
            final String finalHwVer = hwVer;
            mainHandler.post(() -> {
                StringBuilder message = new StringBuilder();
                message.append("MIRA Trust Architecture™\n");
                message.append(isArabic ? "نظام المصادقة والتتبع المعتمد للمجوهرات\n\n" : "Jewelry Tracking & Authentication System\n\n");
                message.append(isArabic ? "📱 الموديل: " : "📱 Model: ").append(Build.MODEL).append("\n");
                message.append(isArabic ? "📦 المنظومة: " : "📦 System: ").append("MIRA ID™ v1.0.0 (Enterprise)").append("\n");

                if (finalHwVer == null || finalHwVer.trim().isEmpty()) {
                    message.append(isArabic ? "📡 العتاد: غير متهيئ (Chainway C72 SDK)\n🔴 الحالة: غير متصل بقارئ RFID" : "📡 Hardware: Not Configured\n🔴 Status: Disconnected");
                } else {
                    message.append(isArabic ? "📡 العتاد: " : "📡 Hardware: ").append(finalHwVer).append("\n");
                    message.append(isArabic ? "🟢 الحالة: وحدة UHF نشطة ومتصلة" : "🟢 Status: Active & Connected");
                }

                new MaterialAlertDialogBuilder(this)
                        .setTitle(isArabic ? "عن المنظومة | MIRA ID™" : "About System | MIRA ID™")
                        .setMessage(message.toString())
                        .setPositiveButton(isArabic ? "موافق" : "OK", (dialog, which) -> dialog.dismiss())
                        .show();
            });
        });
    }

    private void updateBottomNavSelectionByTag(String tag) {
        try {
            BottomNavigationView quickNav = findViewById(R.id.bottomNavigationView);
            if (quickNav == null || tag == null) return;
            Menu menu = quickNav.getMenu();
            if (tag.equals("Dashboard")) menu.findItem(R.id.nav_home).setChecked(true);
            else if (tag.equals("Scan")) menu.findItem(R.id.nav_inventory).setChecked(true);
            else if (tag.equals("POS")) menu.findItem(R.id.nav_sales).setChecked(true);
            else if (tag.equals("Transfers")) menu.findItem(R.id.nav_transfers).setChecked(true);
        } catch (Exception ignored) {}
    }

    private Fragment getTopFragment() {
        if (fragmentManager != null && fragmentManager.getBackStackEntryCount() > 0) {
            FragmentManager.BackStackEntry entry = fragmentManager.getBackStackEntryAt(fragmentManager.getBackStackEntryCount() - 1);
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

        if (pm.isAdmin() || pm.isInventoryManager()) optionsList.add(isArabic ? "إعدادات قارئ RFID" : "RFID Reader Setup");
        if (pm.isAdmin() || pm.isSecurity() || pm.isGateSecurity()) optionsList.add(isArabic ? "تهيئة البوابات الأمنية" : "Gate Security Setup");
        if (pm.canViewServerConfig()) optionsList.add(isArabic ? "🌐 إعدادات السيرفر" : "🌐 Server Config");
        if (pm.isAdmin() || pm.isManager() || pm.isInventoryManager()) optionsList.add(isArabic ? "📋 إدارة الشرائح المبرمجة" : "📋 RFID Bindings");
        optionsList.add(isArabic ? "اختبار التوصيل" : "Connection Test");

        String[] options = optionsList.toArray(new String[0]);
        new MaterialAlertDialogBuilder(this)
                .setTitle(isArabic ? "📡 أجهزة ومستشعرات المنظومة" : "📡 Hardware & Sensors")
                .setItems(options, (dialog, which) -> {
                    String selected = options[which];
                    if (selected.contains("RFID") || selected.contains("قارئ")) loadFragment(new MiraReaderSetupFragment(), "ReaderSetup");
                    else if (selected.contains("Gate") || selected.contains("بوابات")) loadFragment(new MiraGateSettingsFragment(), "GateSettings");
                    else if (selected.contains("Server") || selected.contains("سيرفر")) loadFragment(new MiraServerConfigFragment(), "ServerConfig");
                    else if (selected.contains("Bindings") || selected.contains("شرائح")) loadFragment(new MiraRfidBindingsFragment(), "RfidBindings");
                    else Toast.makeText(this, isArabic ? "الأجهزة متصلة" : "Devices connected", Toast.LENGTH_SHORT).show();
                }).show();
    }

    public void startUHFScan() {
        loopFlag = true;
        rfidExecutor.execute(() -> {
            try { if (mReader != null) mReader.startInventoryTag(); } catch (Exception e) { Log.e(TAG, "Start UHF Error", e); }
        });
    }

    public void stopUHFScan() {
        loopFlag = false;
        rfidExecutor.execute(() -> {
            try { if (mReader != null) mReader.stopInventory(); } catch (Exception e) { Log.e(TAG, "Stop UHF Error", e); }
        });
    }

    public void openReceiptFragment(String rawJson) {
        try { MiraReceiptFragment.newInstance(rawJson).show(fragmentManager, "Receipt"); } catch (Exception ignored) {}
    }

    public void showToast(String text) { if (text != null) mainHandler.post(() -> Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT).show()); }
    public void showToast(int resId) { mainHandler.post(() -> Toast.makeText(getApplicationContext(), getString(resId), Toast.LENGTH_SHORT).show()); }

    public void playSoundDelayed(int val) {
        mainHandler.postDelayed(() -> playSound(val), 10);
    }

    public boolean isInventoryActive() {
        if (currentFragment instanceof MiraInventoryProFragment && currentFragment.isVisible()) return ((MiraInventoryProFragment) currentFragment).isScanning();
        if (currentFragment instanceof MiraUHFReadTagFragment && currentFragment.isVisible()) return ((MiraUHFReadTagFragment) currentFragment).isScanning();
        return false;
    }

    private void checkInventoryLockAndExecute(Runnable onConfirmed) {
        if (isInventoryActive()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(isArabic ? "⚠️ الجرد قيد التشغيل" : "⚠️ Inventory In Progress")
                    .setMessage(isArabic ? "عملية الجرد تعمل حالياً. هل تريد إيقاف الجرد والانتقال؟" : "Active scanning in progress. Stop and proceed?")
                    .setPositiveButton(isArabic ? "إيقاف والانتقال" : "Stop & Proceed", (dialog, which) -> {
                        stopActiveInventory();
                        if (onConfirmed != null) onConfirmed.run();
                    })
                    .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null).show();
        } else {
            if (onConfirmed != null) onConfirmed.run();
        }
    }

    private void stopActiveInventory() {
        try {
            if (currentFragment instanceof MiraInventoryProFragment && currentFragment.isVisible()) ((MiraInventoryProFragment) currentFragment).stopScanning();
            if (currentFragment instanceof MiraUHFReadTagFragment && currentFragment.isVisible()) ((MiraUHFReadTagFragment) currentFragment).stopScanning();
            stopUHFScan();
        } catch (Exception ignored) {}
    }

    @Override
    public void onBackPressed() {
        if (isInventoryActive()) { checkInventoryLockAndExecute(this::handleBackNavigation); return; }
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
                    if (tag != null && !pm.canAccessFragment(tag)) { clearBackStack(); loadFragment(new MiraDashboardFragment(), "Dashboard", false); return; }
                    currentFragment = topFragment;
                    if (tag != null) { currentFragmentTag = tag; saveFragmentTagToPrefs(tag); }
                }
                return;
            }
        }
        if (currentFragment != null && !(currentFragment instanceof MiraDashboardFragment)) {
            clearBackStack();
            loadFragment(new MiraDashboardFragment(), "Dashboard", false);
            return;
        }
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastBackPressTime < DOUBLE_BACK_TIMEOUT) { finish(); return; }
        lastBackPressTime = currentTime;
        Toast.makeText(this, isArabic ? "اضغط مرة أخرى للخروج" : "Press again to exit", Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) { onBackPressed(); return true; }
        return super.onKeyDown(keyCode, event);
    }

    public void onTagRead(String epc) {
        try {
            if (activeEncodingFragment != null && activeEncodingFragment.isAdded()) { activeEncodingFragment.onRfidScanned(epc); return; }
            if (activeRfidListener != null) { activeRfidListener.onRfidScanned(epc); return; }
            if (activePOSFragment != null && activePOSFragment.isVisible()) { activePOSFragment.onRingScanned(epc); return; }
            if (currentFragment instanceof MiraUHFReadTagFragment && currentFragment.isVisible()) ((MiraUHFReadTagFragment) currentFragment).onTagScanned(epc, -50.0);
        } catch (Exception ignored) {}
    }

    private void updateBottomNavLanguage() {
        try {
            BottomNavigationView nav = findViewById(R.id.bottomNavigationView);
            if (nav != null && nav.getMenu() != null) {
                Menu menu = nav.getMenu();
                if (menu.findItem(R.id.nav_home) != null) menu.findItem(R.id.nav_home).setTitle(isArabic ? "الرئيسية" : "Home");
                if (menu.findItem(R.id.nav_inventory) != null) menu.findItem(R.id.nav_inventory).setTitle(isArabic ? "الجرد" : "Inventory");
                if (menu.findItem(R.id.nav_sales) != null) menu.findItem(R.id.nav_sales).setTitle(isArabic ? "البيع" : "Sales");
                if (menu.findItem(R.id.nav_transfers) != null) menu.findItem(R.id.nav_transfers).setTitle(isArabic ? "التحويلات" : "Transfers");
            }
        } catch (Exception ignored) {}
    }

    public void showLoading() {
        if (loadingOverlay != null && !isFinishing() && !isDestroyed()) {
            TextView tvTitle = loadingOverlay.findViewById(R.id.tvLoadingTitle);
            if (tvTitle != null) tvTitle.setText(isArabic ? "جاري التحميل..." : "Loading...");
            loadingOverlay.setVisibility(View.VISIBLE);
        }
    }

    public void hideLoading() { if (loadingOverlay != null && !isFinishing() && !isDestroyed()) loadingOverlay.setVisibility(View.GONE); }

    public void setActivePOSFragment(MiraPosFragment fragment) { this.activePOSFragment = fragment; }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            View view = getCurrentFocus();
            if (view instanceof EditText) {
                int[] location = new int[2];
                view.getLocationOnScreen(location);
                float x = ev.getRawX() + view.getLeft() - location[0];
                float y = ev.getRawY() + view.getTop() - location[1];
                if (x < view.getLeft() || x > view.getRight() || y < view.getTop() || y > view.getBottom()) {
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
                    view.clearFocus();
                }
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private void initSound() {
        soundPool = new SoundPool(10, AudioManager.STREAM_MUSIC, 5);
        soundMap.put(1, soundPool.load(this, R.raw.barcodebeep, 1));
        soundMap.put(2, soundPool.load(this, R.raw.alarm_breach_security, 2));
        audioManager = (AudioManager) this.getSystemService(AUDIO_SERVICE);
    }

    public void playSound(int id) {
        // ✅ تشغيل الصوت آمن تماماً ولا يسبب تجميد لأنه يعتمد على الـ SoundPool
        if (soundPool == null || audioManager == null) return;
        try {
            float max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            float cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            float volumeRatio = cur / max;
            soundPool.play(soundMap.get(id), volumeRatio, volumeRatio, 1, 0, 1);
        } catch (Exception ignored) {}
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
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
                if (!permissions.isEmpty()) ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), PERMISSION_REQUEST_CODE);
            }
        } catch (Exception ignored) {}
    }

    private void navigateToLogin() {
        try {
            rfidExecutor.execute(() -> { try { if (mReader != null) mReader.free(); } catch (Exception ignored) {} });
            Intent intent = new Intent(this, LoginActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        try {
            stopActiveInventory();
            if (soundPool != null) { soundPool.release(); soundPool = null; }
            if (fragmentManager != null) clearBackStack();
            if (orgProfileManager != null && profileListener != null) orgProfileManager.removeListener(profileListener);
            SessionMonitorManager.getInstance(this).stopMonitoring();
        } catch (Exception ignored) {}
        super.onDestroy();
    }
}
