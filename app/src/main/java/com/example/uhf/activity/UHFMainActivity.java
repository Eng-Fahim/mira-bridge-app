package com.example.uhf.activity;

import android.Manifest;
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
import android.view.View;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTabHost;

import com.example.uhf.R;
import com.example.uhf.fragment.JewelryDashboardFragment;
import com.example.uhf.fragment.MiraAccountingFragment;
import com.example.uhf.fragment.MiraBranchPickerFragment;
import com.example.uhf.fragment.MiraBranchesManagementFragment;
import com.example.uhf.fragment.MiraDashboardFragment;
import com.example.uhf.fragment.MiraIdentityStudioFragment;
import com.example.uhf.fragment.MiraInventoryProFragment;
import com.example.uhf.fragment.MiraPosFragment;
import com.example.uhf.fragment.MiraReceiptFragment;
import com.example.uhf.fragment.MiraReportsProFragment;
import com.example.uhf.fragment.MiraSalesHistoryFragment;
import com.example.uhf.fragment.MiraSecureGateFragment;
import com.example.uhf.fragment.MiraTransferFragment;
import com.example.uhf.fragment.MiraUHFReadTagFragment;
import com.example.uhf.fragment.UHFRadarLocationFragment;
import com.example.uhf.fragment.UHFReadWriteFragment;
import com.example.uhf.fragment.UHFSetFragment;
import com.example.uhf.fragment.UsersManagementFragment;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.tools.ExportExcelAsyncTask;
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

    // UI Controls & Navigation
    private FrameLayout loadingOverlay;
    public FragmentTabHost mTabHost;
    private FragmentManager fragmentManager;
    public Fragment currentFragment;
    private MiraPosFragment activePOSFragment = null;

    // Managers & State
    private SessionManager sessionManager;
    private MiraBranchesManager branchesManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    public int selectIndex = -1;
    public ArrayList<UHFTAGInfo> tagList = new ArrayList<>();
    public boolean loopFlag = false;
    private boolean isArabic = true;
    private int lastActiveBranchId = -1;

    // Audio & Hardware Threading
    private PlaySoundThread playSoundThread = null;
    private SoundPool soundPool;
    private AudioManager audioManager;
    private final HashMap<Integer, Integer> soundMap = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
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

            MaterialToolbar toolbar = findViewById(R.id.toolbar);
            if (toolbar != null) {
                setSupportActionBar(toolbar);
                updateToolbarTitle();
            }

            checkReadWritePermission();

            safeExecute(this::initSound, "Sound Init Error");
            safeExecute(this::initUHF, "UHF Hardware Init Error");
            safeExecute(this::configureReaderForDevice, "Reader Configuration Error");

            initViewPageData();
            setupTopTabs();
            setupQuickNav();
            updateDynamicUIStrings();

            showLoading();
            mainHandler.postDelayed(this::hideLoading, 300);

        } catch (Exception e) {
            Log.e(TAG, "Fatal error prevented crash in onCreate: " + e.getMessage(), e);
            Toast.makeText(this, isArabic ? "حدث خطأ أثناء تحميل الواجهة، تم التشغيل بالنمط الآمن" 
                    : "An error occurred during initialization. Safe mode enabled.", Toast.LENGTH_LONG).show();
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

    @Override
    public void onBackPressed() {
        if (mTabHost != null) {
            Fragment current = getSupportFragmentManager().findFragmentById(R.id.realtabcontent);
            if (current instanceof MiraUHFReadTagFragment && ((MiraUHFReadTagFragment) current).onBackPressed()) {
                return; // Suppress back press if handled internally by scanning fragment
            }
        }
        super.onBackPressed();
    }

    // ==================== TOP TABS MANAGEMENT ====================

    private void setupTopTabs() {
        if (sessionManager == null) return;

        SessionManager.Role role = sessionManager.getUserRole();
        String roleStr = sessionManager.getRoleString();
        boolean isSystemAdmin = (role == SessionManager.Role.SYSTEM_ADMIN);
        boolean isOrgAdmin = (role == SessionManager.Role.ORG_ADMIN);
        boolean isAdmin = (role == SessionManager.Role.ADMIN) || "ADMIN".equals(roleStr);
        boolean isBranchManager = (role == SessionManager.Role.BRANCH_MANAGER);
        boolean isAnyAdmin = isSystemAdmin || isOrgAdmin || isAdmin;
        boolean isManager = isAnyAdmin || isBranchManager;
        boolean isInventoryMgr = (role == SessionManager.Role.INVENTORY_MGR);

        setTopTabVisibility(R.id.tabTopSettings, isManager || isInventoryMgr);
        setTopTabVisibility(R.id.tabTopIdentity, isManager || isInventoryMgr);
        setTopTabVisibility(R.id.tabTopTransfers, isManager || isInventoryMgr);
        setTopTabVisibility(R.id.tabTopBranches, isManager);
        setTopTabVisibility(R.id.tabTopUsers, isAnyAdmin || isBranchManager);
        setTopTabVisibility(R.id.tabTopRfid, isManager || isInventoryMgr);

        updateTopTabsContainerVisibility();

        setupTopTab(R.id.tabTopSettings, "Settings");
        setupTopTab(R.id.tabTopIdentity, "IdentityStudio");
        setupTopTab(R.id.tabTopTransfers, "Transfers");
        setupTopTab(R.id.tabTopBranches, "BranchesManagement");
        setupTopTab(R.id.tabTopUsers, "UsersManagement");
        setupTopTab(R.id.tabTopRfid, "ReadWrite");
    }

    private void updateTopTabsContainerVisibility() {
        View container = findViewById(R.id.topTabsContainer);
        if (container == null) return;

        boolean anyVisible = false;
        int[] tabIds = {R.id.tabTopSettings, R.id.tabTopIdentity, R.id.tabTopTransfers,
                R.id.tabTopBranches, R.id.tabTopUsers, R.id.tabTopRfid};

        for (int id : tabIds) {
            View tab = findViewById(id);
            if (tab != null && tab.getVisibility() == View.VISIBLE) {
                anyVisible = true;
                break;
            }
        }

        ViewParent parentView = container.getParent();
        if (parentView instanceof View) {
            ((View) parentView).setVisibility(anyVisible ? View.VISIBLE : View.GONE);
        } else {
            container.setVisibility(anyVisible ? View.VISIBLE : View.GONE);
        }
    }

    private void setTopTabVisibility(int tabId, boolean visible) {
        View tab = findViewById(tabId);
        if (tab != null) {
            tab.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void setupTopTab(int tabId, String tag) {
        View tab = findViewById(tabId);
        if (tab != null) {
            tab.setOnClickListener(v -> switchToTabSafely(tag));
        }
    }

    // ==================== BOTTOM NAVIGATION & QUICK ACTIONS ====================

    private void setupQuickNav() {
        try {
            BottomNavigationView quickNav = findViewById(R.id.bottomQuickNav);
            if (quickNav == null) return;

            quickNav.setOnItemSelectedListener(item -> {
                int id = item.getItemId();
                if (id == R.id.nav_quick_dashboard) switchToTabSafely("Dashboard");
                else if (id == R.id.nav_quick_menu) showRadialMenu();
                else if (id == R.id.nav_quick_inventory) switchToTabSafely("Scan");
                else if (id == R.id.nav_quick_pos) switchToTabSafely("POS Mode");
                return true;
            });
        } catch (Exception e) {
            Log.e(TAG, "QuickNav Setup Error: " + e.getMessage());
        }
    }

    private void showRadialMenu() {
        try {
            SessionManager.Role role = sessionManager.getUserRole();
            String roleStr = sessionManager.getRoleString();
            boolean isSystemAdmin = (role == SessionManager.Role.SYSTEM_ADMIN);
            boolean isOrgAdmin = (role == SessionManager.Role.ORG_ADMIN);
            boolean isAdmin = (role == SessionManager.Role.ADMIN) || "ADMIN".equals(roleStr);
            boolean isBranchManager = (role == SessionManager.Role.BRANCH_MANAGER);
            boolean isAnyAdmin = isSystemAdmin || isOrgAdmin || isAdmin;
            boolean isManager = isAnyAdmin || isBranchManager;
            boolean isInventoryMgr = (role == SessionManager.Role.INVENTORY_MGR);
            boolean isPosOperator = (role == SessionManager.Role.POS_OPERATOR);
            boolean isGateSecurity = (role == SessionManager.Role.GATE_SECURITY);

            BottomSheetDialog bottomSheet = new BottomSheetDialog(this);
            View view = getLayoutInflater().inflate(R.layout.dialog_quick_menu, null);
            bottomSheet.setContentView(view);

            setMenuVisibility(view, R.id.btnMenuRadar, isManager || isInventoryMgr || isPosOperator || isGateSecurity);
            setMenuVisibility(view, R.id.btnMenuGate, isManager || isGateSecurity || isPosOperator);
            setMenuVisibility(view, R.id.btnMenuAccounting, isManager);
            setMenuVisibility(view, R.id.btnMenuReports, isManager);
            setMenuVisibility(view, R.id.btnMenuSales, isManager || isPosOperator);
            setMenuVisibility(view, R.id.btnMenuInventory, isManager || isInventoryMgr);

            bindMenuAction(view, R.id.btnMenuRadar, bottomSheet, "RadarLocation");
            bindMenuAction(view, R.id.btnMenuGate, bottomSheet, "SecureGate");
            bindMenuAction(view, R.id.btnMenuAccounting, bottomSheet, "Accounting");
            bindMenuAction(view, R.id.btnMenuReports, bottomSheet, "ReportsPro");
            bindMenuAction(view, R.id.btnMenuSales, bottomSheet, "SalesHistory");
            bindMenuAction(view, R.id.btnMenuInventory, bottomSheet, "Inventory Pro");

            bottomSheet.show();
        } catch (Exception e) {
            Log.e(TAG, "BottomSheet Error: " + e.getMessage());
        }
    }

    private void bindMenuAction(View root, int viewId, BottomSheetDialog dialog, String targetTab) {
        View btn = root.findViewById(viewId);
        if (btn != null) {
            btn.setOnClickListener(v -> {
                dialog.dismiss();
                switchToTabSafely(targetTab);
            });
        }
    }

    private void setMenuVisibility(View parent, int viewId, boolean visible) {
        View v = parent.findViewById(viewId);
        if (v != null) v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void switchToTabSafely(String tag) {
        if (mTabHost != null) {
            try {
                mTabHost.setCurrentTabByTag(tag);
            } catch (Exception e) {
                Log.w(TAG, "Tab not found or target unauthorized: " + tag);
            }
        }
    }

    // ==================== LIFECYCLE & EVENT HANDLING ====================

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        try {
            if ((keyCode == 139 || keyCode == 280 || keyCode == 293 || keyCode == 120) && event.getRepeatCount() == 0) {
                if (mTabHost != null) {
                    String currentTag = mTabHost.getCurrentTabTag();
                    if (currentTag != null) {
                        Fragment frag = getSupportFragmentManager().findFragmentByTag(currentTag);
                        if (frag instanceof MiraUHFReadTagFragment && frag.isVisible()) {
                            ((MiraUHFReadTagFragment) frag).onKeyDownTrigger();
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Key Down Trigger Error: " + e.getMessage());
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkBranchChange();
    }

    private void checkBranchChange() {
        try {
            if (branchesManager == null) return;
            int currentBranchId = branchesManager.getActiveBranchId();
            if (currentBranchId != lastActiveBranchId) {
                lastActiveBranchId = currentBranchId;
                updateToolbarTitle();
                refreshCurrentTab();
            }
        } catch (Exception e) {
            Log.e(TAG, "Branch update check failed: " + e.getMessage());
        }
    }

    private void updateDynamicUIStrings() {
        try {
            BottomNavigationView quickNav = findViewById(R.id.bottomQuickNav);
            if (quickNav != null && quickNav.getMenu() != null) {
                Menu menu = quickNav.getMenu();
                setMenuItemTitle(menu, R.id.nav_quick_dashboard, isArabic ? "الرئيسية" : "Home");
                setMenuItemTitle(menu, R.id.nav_quick_menu, isArabic ? "إجراءات" : "Actions");
                setMenuItemTitle(menu, R.id.nav_quick_inventory, isArabic ? "الجرد" : "Inventory");
                setMenuItemTitle(menu, R.id.nav_quick_pos, isArabic ? "البيع" : "POS");
            }

            setTopTabLabel(R.id.tabTopSettings, isArabic ? "⚙️ الإعدادات" : "⚙️ Settings");
            setTopTabLabel(R.id.tabTopIdentity, isArabic ? "🆔 التكويد" : "🆔 Identity");
            setTopTabLabel(R.id.tabTopTransfers, isArabic ? "📦 التحويلات" : "📦 Transfers");
            setTopTabLabel(R.id.tabTopBranches, isArabic ? "🏢 الفروع" : "🏢 Branches");
            setTopTabLabel(R.id.tabTopUsers, isArabic ? "👥 المستخدمين" : "👥 Users");
            setTopTabLabel(R.id.tabTopRfid, isArabic ? "🔧 RFID" : "🔧 RFID");

        } catch (Exception e) {
            Log.e(TAG, "Dynamic UI Translation Error: " + e.getMessage());
        }
    }

    private void setMenuItemTitle(Menu menu, int itemId, String title) {
        MenuItem item = menu.findItem(itemId);
        if (item != null) item.setTitle(title);
    }

    private void setTopTabLabel(int viewId, String text) {
        TextView tv = findViewById(viewId);
        if (tv != null) tv.setText(text);
    }

    private void updateToolbarTitle() {
        try {
            if (getSupportActionBar() != null && sessionManager != null) {
                String displayName = sessionManager.getDisplayName();
                if (displayName == null || displayName.trim().isEmpty()) {
                    displayName = sessionManager.getUsername();
                }
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

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        try {
            getMenuInflater().inflate(R.menu.main, menu);
        } catch (Exception e) {
            Log.e(TAG, "Menu Inflation Error: " + e.getMessage());
        }
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        try {
            MenuItem langItem = menu.findItem(R.id.action_change_language);
            if (langItem != null) {
                langItem.setTitle(isArabic ? "English" : "العربية");
            }
        } catch (Exception ignored) {}
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        invalidateOptionsMenu();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_change_language) { toggleLanguage(); return true; }
        if (id == R.id.action_logout) { showLogoutConfirmation(); return true; }
        if (id == R.id.action_profile) { showProfileDialog(); return true; }
        if (id == R.id.action_branches) { showBranchPicker(); return true; }
        if (id == R.id.export) { exportData(); return true; }
        if (id == R.id.UHF_ver) { getUHFVersion(); return true; }
        if (id == R.id.speed) {
            safeExecute(() -> startActivity(new Intent(this, TestActivity.class)), "Test Activity Launch Failed");
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void toggleLanguage() {
        isArabic = !isArabic;
        getSharedPreferences("app_settings", MODE_PRIVATE)
                .edit()
                .putString("language", isArabic ? "ar" : "en")
                .apply();

        applyLanguage();
        updateDynamicUIStrings();
        updateToolbarTitle();
    }

    private void applyLanguage() {
        try {
            LocaleListCompat appLocale = isArabic ? LocaleListCompat.forLanguageTags("ar") : LocaleListCompat.forLanguageTags("en");
            AppCompatDelegate.setApplicationLocales(appLocale);
            getWindow().getDecorView().setLayoutDirection(isArabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        } catch (Exception e) {
            Log.e(TAG, "Language Application Error: " + e.getMessage());
        }
    }

    private void showBranchPicker() {
        try {
            if (branchesManager.isBranchLocked() && !sessionManager.isAnyAdmin()) {
                Toast.makeText(this, isArabic ? "الفرع محدد من قبل النظام" : "Branch assignment is locked", Toast.LENGTH_LONG).show();
                return;
            }
            MiraBranchPickerFragment picker = new MiraBranchPickerFragment();
            picker.setOnBranchSelectedListener((branchId, branchName, branchCode) -> {
                if (getSupportActionBar() != null) getSupportActionBar().setSubtitle("📍 " + branchName);
                branchesManager.setActiveBranch(branchId, branchName, branchCode, "", "");
                lastActiveBranchId = branchId;
                refreshCurrentTab();
                Toast.makeText(this, (isArabic ? "✅ تم التبديل إلى: " : "✅ Switched to: ") + branchName, Toast.LENGTH_SHORT).show();
            });
            picker.show(getSupportFragmentManager(), "branch_picker");
        } catch (Exception e) {
            Log.e(TAG, "Branch Picker Display Error: " + e.getMessage());
        }
    }

    private void refreshCurrentTab() {
        try {
            String currentTabTag = mTabHost.getCurrentTabTag();
            if (currentTabTag == null) return;
            Fragment cf = getSupportFragmentManager().findFragmentByTag(currentTabTag);
            if (cf != null && cf.isAdded() && cf.getView() != null) {
                TextView tvBranch = cf.getView().findViewById(R.id.tvBranchName);
                if (tvBranch != null && branchesManager != null) {
                    tvBranch.setText("📍 " + branchesManager.getActiveBranchDisplayName());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error refreshing current tab: " + e.getMessage());
        }
    }

    public void setActivePOSFragment(MiraPosFragment fragment) {
        this.activePOSFragment = fragment;
    }

    public boolean isReaderReady() {
        try {
            return mReader != null && mReader.getConnectStatus() == ConnectionStatus.CONNECTED;
        } catch (Exception e) {
            return false;
        }
    }

    public void onTagRead(String epc) {
        try {
            if (activePOSFragment != null && activePOSFragment.isVisible()) {
                activePOSFragment.onRingScanned(epc);
                return;
            }
            if (mTabHost != null) {
                String currentTabTag = mTabHost.getCurrentTabTag();
                if (currentTabTag != null) {
                    Fragment fragment = getSupportFragmentManager().findFragmentByTag(currentTabTag);
                    if (fragment instanceof MiraUHFReadTagFragment && fragment.isVisible()) {
                        ((MiraUHFReadTagFragment) fragment).onTagScanned(epc, "—");
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Tag Read Processing Error: " + e.getMessage());
        }
    }

    // ==================== TAB INITIALIZATION & ROUTING ====================

    protected void initViewPageData() {
        try {
            fragmentManager = getSupportFragmentManager();
            mTabHost = findViewById(android.R.id.tabhost);
            if (mTabHost == null) return;

            mTabHost.setup(this, fragmentManager, R.id.realtabcontent);

            SessionManager.Role role = (sessionManager != null) ? sessionManager.getUserRole() : SessionManager.Role.POS_OPERATOR;
            String roleStr = sessionManager != null ? sessionManager.getRoleString() : "";
            boolean isSystemAdmin = (role == SessionManager.Role.SYSTEM_ADMIN);
            boolean isOrgAdmin = (role == SessionManager.Role.ORG_ADMIN);
            boolean isAdmin = (role == SessionManager.Role.ADMIN) || "ADMIN".equals(roleStr);
            boolean isBranchManager = (role == SessionManager.Role.BRANCH_MANAGER);
            boolean isAnyAdmin = isSystemAdmin || isOrgAdmin || isAdmin;
            boolean isManager = isAnyAdmin || isBranchManager;
            boolean isInventoryMgr = (role == SessionManager.Role.INVENTORY_MGR);
            boolean isPosOperator = (role == SessionManager.Role.POS_OPERATOR);
            boolean isGateSecurity = (role == SessionManager.Role.GATE_SECURITY);
            boolean isAuditor = (role == SessionManager.Role.AUDITOR);

            safeAddTab("Dashboard", isArabic ? "🏠 الرئيسية" : "🏠 Dashboard", MiraDashboardFragment.class);
            safeAddTab("Scan", isArabic ? "📡 الجرد" : "📡 Inventory", MiraUHFReadTagFragment.class);

            if (isManager || isPosOperator) {
                safeAddTab("POS Mode", isArabic ? "🛒 البيع" : "🛒 POS", MiraPosFragment.class);
            }
            if (isManager || isPosOperator || isAuditor) {
                safeAddTab("SalesHistory", isArabic ? "📜 المبيعات" : "📜 Sales", MiraSalesHistoryFragment.class);
            }
            if (isManager) {
                safeAddTab("JewelryDashboard", isArabic ? "💎 المجوهرات" : "💎 Jewelry", JewelryDashboardFragment.class);
            }
            if (isManager || isInventoryMgr) {
                safeAddTab("IdentityStudio", isArabic ? "🆔 التكويد" : "🆔 ID", MiraIdentityStudioFragment.class);
                safeAddTab("Inventory Pro", isArabic ? "📊 المخزون" : "📊 Inventory", MiraInventoryProFragment.class);
                safeAddTab("ReadWrite", isArabic ? "📝 قراءة/كتابة" : "📝 Read/Write", UHFReadWriteFragment.class);
            }
            if (isManager || isInventoryMgr || isPosOperator || isGateSecurity) {
                safeAddTab("RadarLocation", isArabic ? "🎯 الرادار" : "🎯 Radar", UHFRadarLocationFragment.class);
            }
            if (isManager || isInventoryMgr) {
                safeAddTab("Settings", isArabic ? "⚙️ إعدادات القارئ" : "⚙️ Reader Settings", UHFSetFragment.class);
            }
            if (isManager || isGateSecurity || isPosOperator) {
                safeAddTab("SecureGate", isArabic ? "🚪 البوابات" : "🚪 Gate", MiraSecureGateFragment.class);
            }
            if (isManager) {
                safeAddTab("BranchesManagement", isArabic ? "🏢 الفروع" : "🏢 Branches", MiraBranchesManagementFragment.class);
            }
            if (isAnyAdmin || isBranchManager) {
                safeAddTab("UsersManagement", isArabic ? "👥 المستخدمين" : "👥 Users", UsersManagementFragment.class);
            }
            if (isManager || isAuditor) {
                safeAddTab("ReportsPro", isArabic ? "📊 التقارير" : "📊 Reports", MiraReportsProFragment.class);
                safeAddTab("Accounting", isArabic ? "💰 المحاسبة" : "💰 Accounting", MiraAccountingFragment.class);
            }
            if (isManager || isInventoryMgr) {
                safeAddTab("Transfers", isArabic ? "📦 التحويلات" : "📦 Transfers", MiraTransferFragment.class);
            }

            mTabHost.setOnTabChangedListener(tabId -> {
                try {
                    showLoading();
                    Fragment fragment = getSupportFragmentManager().findFragmentByTag(tabId);
                    if (fragment != null) currentFragment = fragment;
                    mainHandler.postDelayed(this::hideLoading, 300);
                } catch (Exception e) {
                    Log.e(TAG, "Tab Switch Event Error: " + e.getMessage());
                    hideLoading();
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "initViewPageData Error: " + e.getMessage());
        }
    }

    private void safeAddTab(String tag, String label, Class<?> clazz) {
        try {
            if (mTabHost != null && Fragment.class.isAssignableFrom(clazz)) {
                //noinspection unchecked
                mTabHost.addTab(mTabHost.newTabSpec(tag).setIndicator(label), (Class<? extends Fragment>) clazz, null);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to register tab: " + tag + " -> " + e.getMessage());
        }
    }

    public void showLoading() {
        if (loadingOverlay != null && !isFinishing() && !isDestroyed()) {
            TextView tvTitle = loadingOverlay.findViewById(R.id.tvLoadingTitle);
            if (tvTitle != null) {
                tvTitle.setText(isArabic ? "جاري التحميل..." : "Loading...");
            }
            loadingOverlay.setVisibility(View.VISIBLE);
        }
    }

    public void hideLoading() {
        if (loadingOverlay != null && !isFinishing() && !isDestroyed()) {
            loadingOverlay.setVisibility(View.GONE);
        }
    }

    private void showLogoutConfirmation() {
        new AlertDialog.Builder(this)
                .setTitle(isArabic ? "تسجيل الخروج" : "Sign Out")
                .setMessage(isArabic ? "هل أنت متأكد من تسجيل الخروج؟" : "Are you sure you want to sign out?")
                .setPositiveButton(isArabic ? "تسجيل الخروج" : "Sign Out", (d, w) -> performLogout())
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
    }

    private void showProfileDialog() {
        String msg = (isArabic ? "المستخدم: " : "User: ") + (sessionManager != null ? sessionManager.getDisplayName() : "N/A") + "\n" +
                     (isArabic ? "الفرع: " : "Branch: ") + (branchesManager != null ? branchesManager.getActiveBranchDisplayName() : "N/A");

        new AlertDialog.Builder(this)
                .setTitle(isArabic ? "ملف المستخدم" : "User Profile")
                .setMessage(msg)
                .setPositiveButton(isArabic ? "إغلاق" : "Close", null)
                .show();
    }

    public void performLogout() {
        try {
            if (sessionManager != null) sessionManager.logout();
            if (branchesManager != null) branchesManager.clear();
            navigateToLogin();
        } catch (Exception e) {
            navigateToLogin();
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
            mainHandler.removeCallbacksAndMessages(null);
            if (playSoundThread != null) playSoundThread.stopPlay();
            releaseSoundPool();
            if (mReader != null) mReader.free();
        } catch (Exception e) {
            Log.e(TAG, "onDestroy cleanup error: " + e.getMessage());
        }
        super.onDestroy();
    }

    @Override
    public void exportData() {
        try {
            checkReadWritePermission();
            if (loopFlag) {
                UIHelper.ToastMessage(this, R.string.uhf_msg_scaning);
                return;
            }
            if (tagList == null || tagList.isEmpty()) {
                UIHelper.ToastMessage(this, R.string.uhf_msg_export_data_empty);
                return;
            }
            new ExportExcelAsyncTask(this, tagList).execute();
        } catch (Exception e) {
            Log.e(TAG, "Data Export Error: " + e.getMessage());
        }
    }

    // ==================== AUDIO FEEDBACK SYSTEM ====================

    private void initSound() {
        soundPool = new SoundPool(10, AudioManager.STREAM_MUSIC, 5);
        soundMap.put(1, soundPool.load(this, R.raw.barcodebeep, 1));
        soundMap.put(2, soundPool.load(this, R.raw.serror, 2));
        audioManager = (AudioManager) this.getSystemService(AUDIO_SERVICE);
        
        playSoundThread = new PlaySoundThread();
        playSoundThread.start();
    }

    private void releaseSoundPool() {
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
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

    public void openReceiptFragment(String responseJson) {
        try {
            MiraReceiptFragment receipt = MiraReceiptFragment.newInstance(responseJson);
            receipt.show(getSupportFragmentManager(), "mira_receipt");
        } catch (Exception e) {
            showToast(isArabic ? "✅ تم البيع بنجاح!" : "✅ Transaction completed successfully!");
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
            Log.e(TAG, "Permission Check Error: " + e.getMessage());
        }
    }

    private Toast toast;

    public void showToast(String text) {
        if (toast != null) toast.cancel();
        toast = Toast.makeText(this, text, Toast.LENGTH_SHORT);
        toast.show();
    }

    public void showToast(int resId) {
        showToast(getString(resId));
    }

    public void playSoundDelayed(int speed) {
        if (playSoundThread != null) playSoundThread.play(speed);
    }

    private final Object objectLock = new Object();

    private class PlaySoundThread extends Thread {
        private volatile boolean isStop = false;
        private int interval = 500;
        private long lastPlayTime = SystemClock.elapsedRealtime();

        @Override
        public void run() {
            while (!isStop) {
                synchronized (objectLock) {
                    try {
                        objectLock.wait(interval);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                if (!isStop && SystemClock.elapsedRealtime() - lastPlayTime < 500) {
                    playSound(1);
                }
            }
        }

        public void play(int speed) {
            interval = speed > 85 ? 3 : speed > 66 ? 100 - speed : speed > 33 ? (100 - speed) * 2 : (100 - speed) * 3;
            lastPlayTime = SystemClock.elapsedRealtime();
        }

        public void stopPlay() {
            isStop = true;
            synchronized (objectLock) {
                objectLock.notifyAll();
            }
        }
    }

    private void safeExecute(Runnable runnable, String errorMessage) {
        try {
            runnable.run();
        } catch (Exception e) {
            Log.e(TAG, errorMessage + ": " + e.getMessage());
        }
    }
}
