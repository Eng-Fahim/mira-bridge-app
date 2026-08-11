package com.example.uhf.activity;

import com.example.uhf.fragment.MiraUHFReadTagFragment;
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
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTabHost;

import com.example.uhf.R;
import com.example.uhf.fragment.AuthenticateNxpUcodeDnaFragment;
import com.example.uhf.fragment.BlockPermalockFragment;
import com.example.uhf.fragment.BlockWriteFragment;
import com.example.uhf.fragment.JewelryDashboardFragment;
import com.example.uhf.fragment.MarginReadFragment;
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
import com.example.uhf.fragment.ProtectedModeAndShortRangeModeFragment;
import com.example.uhf.fragment.UHFKillFragment;
import com.example.uhf.fragment.UHFLocationFragment;
import com.example.uhf.fragment.UHFLockFragment;
import com.example.uhf.fragment.UHFRadarLocationFragment;
import com.example.uhf.fragment.UHFReadWriteFragment;
import com.example.uhf.fragment.UHFSetFragment;
import com.example.uhf.fragment.UHFTagFlashFragment;
import com.example.uhf.fragment.UHFTagLitFragment;
import com.example.uhf.fragment.UHFUpgradeFragment;
import com.example.uhf.fragment.UsersManagementFragment;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.tools.ExportExcelAsyncTask;
import com.example.uhf.tools.SessionManager;
import com.example.uhf.tools.ToastUtil;
import com.example.uhf.tools.UIHelper;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.rscja.deviceapi.entity.UHFTAGInfo;
import com.rscja.deviceapi.interfaces.ConnectionStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class UHFMainActivity extends BaseTabFragmentActivity {

    private FrameLayout loadingOverlay; 
    private final static String TAG = "UHFMainActivity";
    public FragmentTabHost mTabHost;
    private FragmentManager fm;
    public int selectIndex = -1;
    public ArrayList<UHFTAGInfo> tagList = new ArrayList<>();
    public boolean loopFlag = false;
    private PlaySoundThread playSoundThread = null;
    public Fragment currentFragment;
    private SessionManager sessionManager;
    private MiraBranchesManager branchesManager;

    private MiraPosFragment activePOSFragment = null;
    private boolean isArabic = true;
    private int lastActiveBranchId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
            super.onCreate(savedInstanceState);

            ToastUtil.init(getApplicationContext());

            try {
                isArabic = getResources().getConfiguration().locale.getLanguage().equals("ar");
                if (isArabic) {
                    getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
                } else {
                    getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
                }
            } catch (Exception ignored) {}

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

            com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.toolbar);
            if (toolbar != null) {
                setSupportActionBar(toolbar);
                updateToolbarTitle();
            }

            checkReadWritePermission();

            try { initSound(); } catch (Exception e) { Log.e(TAG, "Sound init error: " + e.getMessage()); }
            try { initUHF(); } catch (Exception e) { Log.e(TAG, "UHF init error: " + e.getMessage()); }
            try { configureReaderForDevice(); } catch (Exception e) { Log.e(TAG, "Reader config error: " + e.getMessage()); }

            initViewPageData();
            setupTopTabs();
            setupQuickNav();
            
            updateDynamicUIStrings();

            // عرض التحميل الفاخر عند الفتح في المرة الأولى
            showLoading();
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::hideLoading, 300);

        } catch (Exception e) {
            Log.e(TAG, "Fatal error prevented crash in onCreate: " + e.getMessage(), e);
            Toast.makeText(this, "حدث خطأ أثناء تحميل الواجهة، تم التشغيل بالنمط الآمن", Toast.LENGTH_LONG).show();
        }
    }
    
    @Override
public void onBackPressed() {
    Fragment current = getSupportFragmentManager().findFragmentById(R.id.realtabcontent);
    if (current instanceof MiraUHFReadTagFragment) {
        if (((MiraUHFReadTagFragment) current).onBackPressed()) {
            return; // امنع الخروج
        }
    }
    super.onBackPressed();
}

    // ==================== TOP TABS ====================

    private void setupTopTabs() {
    SessionManager.Role role = sessionManager.getUserRole();
    boolean isSystemAdmin = (role == SessionManager.Role.SYSTEM_ADMIN);
    boolean isOrgAdmin = (role == SessionManager.Role.ORG_ADMIN);
    boolean isBranchManager = (role == SessionManager.Role.BRANCH_MANAGER);
    boolean isAnyAdmin = isSystemAdmin || isOrgAdmin;
    boolean isManager = isAnyAdmin || isBranchManager;
    boolean isInventoryMgr = (role == SessionManager.Role.INVENTORY_MGR);
    boolean isGateSecurity = (role == SessionManager.Role.GATE_SECURITY);
    boolean isPosOperator = (role == SessionManager.Role.POS_OPERATOR);
    boolean isAuditor = (role == SessionManager.Role.AUDITOR);
    
    // ⚙️ الإعدادات - للمدراء ومديري المخزون
    setTopTabVisibility(R.id.tabTopSettings, isManager || isInventoryMgr);
    // 🆔 التكويد - للمدراء ومديري المخزون
    setTopTabVisibility(R.id.tabTopIdentity, isManager || isInventoryMgr);
    // 📦 التحويلات - للمدراء ومديري المخزون
    setTopTabVisibility(R.id.tabTopTransfers, isManager || isInventoryMgr);
    // 🏢 الفروع - للمدراء فقط
    setTopTabVisibility(R.id.tabTopBranches, isManager);
    // 👥 المستخدمين - للمدراء فقط
    setTopTabVisibility(R.id.tabTopUsers, isManager);
    // 🔧 RFID - للمدراء ومديري المخزون
    setTopTabVisibility(R.id.tabTopRfid, isManager || isInventoryMgr);
    
    setupTopTab(R.id.tabTopSettings, "Settings");
    setupTopTab(R.id.tabTopIdentity, "IdentityStudio");
    setupTopTab(R.id.tabTopTransfers, "Transfers");
    setupTopTab(R.id.tabTopBranches, "BranchesManagement");
    setupTopTab(R.id.tabTopUsers, "UsersManagement");
    setupTopTab(R.id.tabTopRfid, "ReadWrite");
}

private void setTopTabVisibility(int tabId, boolean visible) {
    View tab = findViewById(tabId);
    if (tab != null) tab.setVisibility(visible ? View.VISIBLE : View.GONE);
}


    private void setupTopTab(int tabId, String tag) {
        View tab = findViewById(tabId);
        if (tab != null) {
            tab.setOnClickListener(v -> switchToTabSafely(tag));
        }
    }

    // ==================== BOTTOM NAV ====================

    private void setupQuickNav() {
    try {
        BottomNavigationView quickNav = findViewById(R.id.bottomQuickNav);
        if (quickNav == null) return;

        quickNav.setOnItemSelectedListener(item -> {
            try {
                int id = item.getItemId();
                if (id == R.id.nav_quick_dashboard) switchToTabSafely("Dashboard");
                else if (id == R.id.nav_quick_menu) showRadialMenu(); // ⚡ فتح قائمة الإجراءات الفاخرة
                else if (id == R.id.nav_quick_inventory) switchToTabSafely("Scan");
                else if (id == R.id.nav_quick_pos) switchToTabSafely("POS Mode");
            } catch (Exception e) {
                Log.e(TAG, "Navigation Error: " + e.getMessage());
            }
            return true;
        });
    } catch (Exception e) {
        Log.e(TAG, "QuickNav Setup Error: " + e.getMessage());
    }
}


    private void showRadialMenu() {
    try {
        SessionManager.Role role = sessionManager.getUserRole();
        boolean isSystemAdmin = (role == SessionManager.Role.SYSTEM_ADMIN);
        boolean isOrgAdmin = (role == SessionManager.Role.ORG_ADMIN);
        boolean isBranchManager = (role == SessionManager.Role.BRANCH_MANAGER);
        boolean isAnyAdmin = isSystemAdmin || isOrgAdmin;
        boolean isManager = isAnyAdmin || isBranchManager;
        boolean isInventoryMgr = (role == SessionManager.Role.INVENTORY_MGR);
        boolean isGateSecurity = (role == SessionManager.Role.GATE_SECURITY);
        
        com.google.android.material.bottomsheet.BottomSheetDialog bottomSheet = 
            new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        
        View view = getLayoutInflater().inflate(R.layout.dialog_quick_menu, null);
        bottomSheet.setContentView(view);

        // ✅ إخفاء الخيارات حسب الصلاحية
        setMenuVisibility(view, R.id.btnMenuRadar, isManager || isInventoryMgr);
        setMenuVisibility(view, R.id.btnMenuGate, isManager || isGateSecurity);
        setMenuVisibility(view, R.id.btnMenuAccounting, isManager);
        setMenuVisibility(view, R.id.btnMenuReports, isManager);
        setMenuVisibility(view, R.id.btnMenuSales, isManager);
        setMenuVisibility(view, R.id.btnMenuInventory, isManager || isInventoryMgr);

        view.findViewById(R.id.btnMenuRadar).setOnClickListener(v -> { bottomSheet.dismiss(); switchToTabSafely("RadarLocation"); });
        view.findViewById(R.id.btnMenuGate).setOnClickListener(v -> { bottomSheet.dismiss(); switchToTabSafely("SecureGate"); });
        view.findViewById(R.id.btnMenuAccounting).setOnClickListener(v -> { bottomSheet.dismiss(); switchToTabSafely("Accounting"); });
        view.findViewById(R.id.btnMenuReports).setOnClickListener(v -> { bottomSheet.dismiss(); switchToTabSafely("ReportsPro"); });
        view.findViewById(R.id.btnMenuSales).setOnClickListener(v -> { bottomSheet.dismiss(); switchToTabSafely("SalesHistory"); });
        view.findViewById(R.id.btnMenuInventory).setOnClickListener(v -> { bottomSheet.dismiss(); switchToTabSafely("Inventory Pro"); });

        bottomSheet.show();
    } catch (Exception e) {
        Log.e(TAG, "BottomSheet Error: " + e.getMessage());
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
                Log.w(TAG, "Tab not found or unauthorized: " + tag);
            }
        }
    }

    // ==================== KEY EVENTS & LIFECYCLE ====================

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        try {
            if (keyCode == 139 || keyCode == 280 || keyCode == 293 || keyCode == 120) {
                if (event.getRepeatCount() == 0 && mTabHost != null) {
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
            Log.e(TAG, "Key Down Error: " + e.getMessage());
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
            Log.e(TAG, "Branch check error: " + e.getMessage());
        }
    }
    
    // تحديث تسميات القائمة السفلية والعلوبة والـ Loading طبقاً للغة الحالية ديناميكياً
private void updateDynamicUIStrings() {
    try {
        BottomNavigationView quickNav = findViewById(R.id.bottomQuickNav);
        if (quickNav != null && quickNav.getMenu() != null) {
            Menu menu = quickNav.getMenu();
            MenuItem itemDash = menu.findItem(R.id.nav_quick_dashboard);
            MenuItem itemActions = menu.findItem(R.id.nav_quick_menu);
            MenuItem itemScan = menu.findItem(R.id.nav_quick_inventory);
            MenuItem itemPos = menu.findItem(R.id.nav_quick_pos);

            if (itemDash != null) itemDash.setTitle(isArabic ? "الرئيسية" : "Home");
            
            if (itemActions != null) itemActions.setTitle(isArabic ? "إجراءات" : "Actions");
            if (itemScan != null) itemScan.setTitle(isArabic ? "الجرد" : "Inventory");
            if (itemPos != null) itemPos.setTitle(isArabic ? "البيع" : "POS");
        }

        // تحديث أزرار الشريط العلوي
        setTopTabLabel(R.id.tabTopSettings, isArabic ? "⚙️ الإعدادات" : "⚙️ Settings");
        setTopTabLabel(R.id.tabTopIdentity, isArabic ? "🆔 التكويد" : "🆔 Identity");
        setTopTabLabel(R.id.tabTopTransfers, isArabic ? "📦 التحويلات" : "📦 Transfers");
        setTopTabLabel(R.id.tabTopBranches, isArabic ? "🏢 الفروع" : "🏢 Branches");
        setTopTabLabel(R.id.tabTopUsers, isArabic ? "👥 المستخدمين" : "👥 Users");
        setTopTabLabel(R.id.tabTopRfid, isArabic ? "🔧 RFID" : "🔧 RFID");

    } catch (Exception e) {
        Log.e(TAG, "Dynamic UI Strings update error: " + e.getMessage());
    }
}

private void setTopTabLabel(int viewId, String text) {
    TextView tv = findViewById(viewId);
    if (tv != null) tv.setText(text);
}


    private void updateToolbarTitle() {
        try {
            if (getSupportActionBar() != null && sessionManager != null) {
                String displayName = sessionManager.getDisplayName();
                if (displayName == null || displayName.isEmpty()) {
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
            Log.e(TAG, "Toolbar update error: " + e.getMessage());
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        try { getMenuInflater().inflate(R.menu.main, menu); } catch (Exception e) { Log.e(TAG, "Menu inflation error: " + e.getMessage()); }
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        try {
            MenuItem langItem = menu.findItem(R.id.action_change_language);
            if (langItem != null) langItem.setTitle(isArabic ? "English" : "العربية");
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
        if (id == R.id.speed) { try { startActivity(new Intent(this, TestActivity.class)); } catch (Exception ignored) {} return true; }
        return super.onOptionsItemSelected(item);
    }

    private void toggleLanguage() {
    isArabic = !isArabic;
    getSharedPreferences("app_settings", MODE_PRIVATE)
            .edit()
            .putString("language", isArabic ? "ar" : "en")
            .apply();
            
    applyLanguage();
    
    // 👈 تحديث نصوص القوائم والأشرطة فوراً
    updateDynamicUIStrings();
    updateToolbarTitle();
}


    private void applyLanguage() {
        try {
            LocaleListCompat appLocale = isArabic ? LocaleListCompat.forLanguageTags("ar") : LocaleListCompat.forLanguageTags("en");
            AppCompatDelegate.setApplicationLocales(appLocale);
            getWindow().getDecorView().setLayoutDirection(isArabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        } catch (Exception e) { Log.e(TAG, "Language apply error: " + e.getMessage()); }
    }

    private void showBranchPicker() {
        try {
            if (branchesManager.isBranchLocked() && !sessionManager.isAnyAdmin()) {
                Toast.makeText(this, isArabic ? "الفرع محدد من قبل النظام" : "Branch is locked", Toast.LENGTH_LONG).show();
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
        } catch (Exception e) { Log.e(TAG, "Branch picker error: " + e.getMessage()); }
    }

    private void refreshCurrentTab() {
        try {
            String currentTabTag = mTabHost.getCurrentTabTag();
            if (currentTabTag == null) return;
            Fragment cf = getSupportFragmentManager().findFragmentByTag(currentTabTag);
            if (cf != null && cf.isAdded()) {
                View v = cf.getView();
                if (v != null) {
                    TextView tvBranch = v.findViewById(R.id.tvBranchName);
                    if (tvBranch != null && branchesManager != null) {
                        tvBranch.setText("📍 " + branchesManager.getActiveBranchDisplayName());
                    }
                }
            }
        } catch (Exception e) { Log.e(TAG, "Error refreshing tab: " + e.getMessage()); }
    }

    public void setActivePOSFragment(MiraPosFragment fragment) { this.activePOSFragment = fragment; }

    public boolean isReaderReady() {
        try { return mReader != null && mReader.getConnectStatus() == ConnectionStatus.CONNECTED; }
        catch (Exception e) { return false; }
    }

    public void onTagRead(String epc) {
        try {
            if (activePOSFragment != null && activePOSFragment.isVisible()) { activePOSFragment.onRingScanned(epc); return; }
            if (mTabHost != null) {
                String currentTabTag = mTabHost.getCurrentTabTag();
                if (currentTabTag != null) {
                    Fragment fragment = getSupportFragmentManager().findFragmentByTag(currentTabTag);
                    if (fragment instanceof MiraUHFReadTagFragment && fragment.isVisible()) {
                        ((MiraUHFReadTagFragment) fragment).onTagScanned(epc, "—");
                    }
                }
            }
        } catch (Exception e) { Log.e(TAG, "Tag read handling error: " + e.getMessage()); }
    }

    protected void initViewPageData() {
    try {
        fm = getSupportFragmentManager();
        mTabHost = findViewById(android.R.id.tabhost);
        if (mTabHost == null) return;
        
        mTabHost.setup(this, fm, R.id.realtabcontent);

        SessionManager.Role role = (sessionManager != null) ? sessionManager.getUserRole() : SessionManager.Role.POS_OPERATOR;
        boolean isSystemAdmin = (role == SessionManager.Role.SYSTEM_ADMIN);
        boolean isOrgAdmin = (role == SessionManager.Role.ORG_ADMIN);
        boolean isBranchManager = (role == SessionManager.Role.BRANCH_MANAGER);
        boolean isAnyAdmin = isSystemAdmin || isOrgAdmin;
        boolean isManager = isAnyAdmin || isBranchManager;
        boolean isInventoryMgr = (role == SessionManager.Role.INVENTORY_MGR);
        boolean isPosOperator = (role == SessionManager.Role.POS_OPERATOR);
        boolean isGateSecurity = (role == SessionManager.Role.GATE_SECURITY);
        boolean isAuditor = (role == SessionManager.Role.AUDITOR);

        // 🌍 الجميع
        safeAddTab("Dashboard", isArabic ? "🏠 الرئيسية" : "🏠 Dashboard", MiraDashboardFragment.class);
        
        // 🛒 المدراء + البائعين
        if (isManager || isPosOperator) {
            safeAddTab("JewelryDashboard", isArabic ? "💎 المجوهرات" : "💎 Jewelry", JewelryDashboardFragment.class);
            safeAddTab("POS Mode", isArabic ? "🛒 البيع" : "🛒 POS", MiraPosFragment.class);
            safeAddTab("SalesHistory", isArabic ? "📜 المبيعات" : "📜 Sales", MiraSalesHistoryFragment.class);
        }
        
        // 📡 الجميع
        safeAddTab("Scan", isArabic ? "📡 الجرد" : "📡 Inventory", MiraUHFReadTagFragment.class);
        
        // 🆔 المدراء + مديري المخزون
        if (isManager || isInventoryMgr) {
            safeAddTab("IdentityStudio", isArabic ? "🆔 التكويد" : "🆔 ID", MiraIdentityStudioFragment.class);
            safeAddTab("Inventory Pro", isArabic ? "📊 المخزون" : "📊 Inventory", MiraInventoryProFragment.class);
        }

        // 🔧 RFID - المدراء + مديري المخزون
        if (isManager || isInventoryMgr) {
            safeAddTab("ReadWrite", isArabic ? "📝 قراءة/كتابة" : "📝 Read/Write", UHFReadWriteFragment.class);
            safeAddTab("RadarLocation", isArabic ? "🎯 الرادار" : "🎯 Radar", UHFRadarLocationFragment.class);
        }
        
        // ⚙️ الإعدادات - المدراء + مديري المخزون
        if (isManager || isInventoryMgr) {
            safeAddTab("Settings", isArabic ? "⚙️ إعدادات القارئ" : "⚙️ Reader Settings", UHFSetFragment.class);
        }

        // 🚪 المدراء + أمن البوابات
        if (isManager || isGateSecurity) {
            safeAddTab("SecureGate", isArabic ? "🚪 البوابات" : "🚪 Gate", MiraSecureGateFragment.class);
        }
        
        // 🏢 المدراء فقط
        if (isManager) {
            safeAddTab("BranchesManagement", isArabic ? "🏢 الفروع" : "🏢 Branches", MiraBranchesManagementFragment.class);
            safeAddTab("UsersManagement", isArabic ? "👥 المستخدمين" : "👥 Users", UsersManagementFragment.class);
            safeAddTab("ReportsPro", isArabic ? "📊 التقارير" : "📊 Reports", MiraReportsProFragment.class);
        }
        
        // 💰 المدراء + المدققين
        if (isManager || isAuditor) {
            safeAddTab("Accounting", isArabic ? "💰 المحاسبة" : "💰 Accounting", MiraAccountingFragment.class);
        }
        
        // 📦 المدراء + مديري المخزون
        if (isManager || isInventoryMgr) {
            safeAddTab("Transfers", isArabic ? "📦 التحويلات" : "📦 Transfers", MiraTransferFragment.class);
        }
        
        mTabHost.setOnTabChangedListener(tabId -> {
            try {
                showLoading();
                Fragment fragment = getSupportFragmentManager().findFragmentByTag(tabId);
                if (fragment != null) currentFragment = fragment;
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::hideLoading, 300);
            } catch (Exception e) { 
                Log.e(TAG, "Tab switch error: " + e.getMessage()); 
                hideLoading();
            }
        });

    } catch (Exception e) {
        Log.e(TAG, "initViewPageData error: " + e.getMessage());
    }
}


    private void safeAddTab(String tag, String label, Class<?> clazz) {
        try {
            if (mTabHost != null && Fragment.class.isAssignableFrom(clazz)) {
                mTabHost.addTab(mTabHost.newTabSpec(tag).setIndicator(label), (Class<? extends Fragment>) clazz, null);
            }
        } catch (Exception e) { Log.e(TAG, "Failed to add tab: " + e.getMessage()); }
    }

    public void showLoading() {
        if (loadingOverlay != null) {
            TextView tvTitle = loadingOverlay.findViewById(R.id.tvLoadingTitle);
            if (tvTitle != null) {
                tvTitle.setText(isArabic ? "جاري التحميل..." : "Loading...");
            }
            loadingOverlay.setVisibility(View.VISIBLE);
        }
    }

    public void hideLoading() {
        if (loadingOverlay != null) {
            loadingOverlay.setVisibility(View.GONE);
        }
    }

    private void showLogoutConfirmation() {
        try {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(isArabic ? "تسجيل الخروج" : "Sign Out")
                .setMessage(isArabic ? "هل أنت متأكد؟" : "Are you sure?")
                .setPositiveButton(isArabic ? "تسجيل الخروج" : "Sign Out", (d, w) -> performLogout())
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
        } catch (Exception ignored) {}
    }

    private void showProfileDialog() {
        try {
            String msg = (isArabic ? "المستخدم: " : "User: ") + (sessionManager != null ? sessionManager.getDisplayName() : "N/A") + "\n" +
                         (isArabic ? "الفرع: " : "Branch: ") + (branchesManager != null ? branchesManager.getActiveBranchDisplayName() : "N/A");
            new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(isArabic ? "ملف المستخدم" : "Profile")
                .setMessage(msg)
                .setPositiveButton(ar("إغلاق", "Close"), null)
                .show();
        } catch (Exception ignored) {}
    }

    public void performLogout() {
        try {
            if (sessionManager != null) sessionManager.logout();
            if (branchesManager != null) branchesManager.clear();
            navigateToLogin();
        } catch (Exception e) { navigateToLogin(); }
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
            if (playSoundThread != null) playSoundThread.stopPlay();
            releaseSoundPool();
            if (mReader != null) mReader.free();
        } catch (Exception e) { Log.e(TAG, "onDestroy error: " + e.getMessage()); }
        super.onDestroy();
    }

    @Override
    public void exportData() {
        try {
            checkReadWritePermission();
            if (loopFlag) { UIHelper.ToastMessage(this, R.string.uhf_msg_scaning); return; }
            if (tagList == null || tagList.isEmpty()) { UIHelper.ToastMessage(this, R.string.uhf_msg_export_data_empty); return; }
            new ExportExcelAsyncTask(this, tagList).execute();
        } catch (Exception e) { Log.e(TAG, "Export error: " + e.getMessage()); }
    }

    // ==================== SOUND ====================
    HashMap<Integer, Integer> soundMap = new HashMap<>();
    private SoundPool soundPool;
    private float volumnRatio;
    private AudioManager am;

    private void initSound() {
        soundPool = new SoundPool(10, AudioManager.STREAM_MUSIC, 5);
        soundMap.put(1, soundPool.load(this, R.raw.barcodebeep, 1));
        soundMap.put(2, soundPool.load(this, R.raw.serror, 2));
        am = (AudioManager) this.getSystemService(AUDIO_SERVICE);
        playSoundThread = new PlaySoundThread();
        playSoundThread.start();
    }

    private void releaseSoundPool() { if (soundPool != null) { soundPool.release(); soundPool = null; } }

    public void playSound(int id) {
        if (soundPool == null || am == null) return;
        try {
            float max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            float cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            volumnRatio = cur / max;
            soundPool.play(soundMap.get(id), volumnRatio, volumnRatio, 1, 0, 1);
        } catch (Exception e) { e.printStackTrace(); }
    }

    public void openReceiptFragment(String responseJson) {
        try {
            MiraReceiptFragment receipt = MiraReceiptFragment.newInstance(responseJson);
            receipt.show(getSupportFragmentManager(), "mira_receipt");
        } catch (Exception e) { showToast("✅ تم البيع بنجاح!"); }
    }

    private void checkReadWritePermission() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) { Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION); i.setData(Uri.parse("package:" + getPackageName())); startActivityForResult(i, 0); }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                List<String> permissions = new ArrayList<>();
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
                if (!permissions.isEmpty()) requestPermissions(permissions.toArray(new String[0]), 1);
            }
        } catch (Exception e) { Log.e(TAG, "Permission check error: " + e.getMessage()); }
    }

    private Toast toast;
    public void showToast(String text) { if (toast != null) toast.cancel(); toast = Toast.makeText(this, text, Toast.LENGTH_SHORT); toast.show(); }
    public void showToast(int resId) { showToast(getString(resId)); }
    public void playSoundDelayed(int speed) { if (playSoundThread != null) playSoundThread.play(speed); }

    private final Object objectLock = new Object();
    private class PlaySoundThread extends Thread {
        private volatile boolean isStop = false;
        int interval = 500;
        long lastPlayTime = SystemClock.elapsedRealtime();
        @Override public void run() {
            while (!isStop) { synchronized (objectLock) { try { objectLock.wait(interval); } catch (InterruptedException e) { break; } } if (!isStop && SystemClock.elapsedRealtime() - lastPlayTime < 500) playSound(1); }
        }
        public void play(int speed) { interval = speed > 85 ? 3 : speed > 66 ? 100 - speed : speed > 33 ? (100 - speed) * 2 : (100 - speed) * 3; lastPlayTime = SystemClock.elapsedRealtime(); }
        public void stopPlay() { isStop = true; synchronized (objectLock) { objectLock.notifyAll(); } }
    }

    private String ar(String arabic, String english) { return isArabic ? arabic : english; }
        }
