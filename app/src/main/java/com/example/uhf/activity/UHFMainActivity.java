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

public class UHFMainActivity extends BaseTabFragmentActivity {

    private final static String TAG = "MainActivity";
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
        lastActiveBranchId = branchesManager.getActiveBranchId();

        setContentView(R.layout.activity_main);

        com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            updateToolbarTitle();
        }

        checkReadWritePermission();
        initSound();
        initUHF();
        configureReaderForDevice();
        initViewPageData();
        
        // ✅ الجديد - Bottom Navigation سريعة
        setupQuickNav();
    }

    // ==================== QUICK BOTTOM NAV (جديد - آمن) ====================

    private void setupQuickNav() {
        BottomNavigationView quickNav = findViewById(R.id.bottomQuickNav);
        if (quickNav == null) return;

        quickNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_quick_dashboard) {
                if (mTabHost != null) mTabHost.setCurrentTabByTag("Dashboard");
            } else if (id == R.id.nav_quick_inventory) {
                if (mTabHost != null) mTabHost.setCurrentTabByTag("Scan");
            } else if (id == R.id.nav_quick_pos) {
                if (mTabHost != null) mTabHost.setCurrentTabByTag("POS Mode");
            } else if (id == R.id.nav_quick_more) {
                // فتح قائمة المزيد
                String[] options = {
                    ar("🆔 التكويد", "🆔 Identity"),
                    ar("📊 التقارير", "📊 Reports"),
                    ar("📦 التحويلات", "📦 Transfers"),
                    ar("⚙️ الإعدادات", "⚙️ Settings"),
                    ar("🔀 تغيير اللغة", "🔀 Language"),
                    ar("🚪 تسجيل الخروج", "🚪 Logout")
                };
                new androidx.appcompat.app.AlertDialog.Builder(UHFMainActivity.this)
                    .setTitle(ar("المزيد", "More"))
                    .setItems(options, (d, w) -> {
                        switch (w) {
                            case 0: if (mTabHost != null) mTabHost.setCurrentTabByTag("IdentityStudio"); break;
                            case 1: if (mTabHost != null) mTabHost.setCurrentTabByTag("ReportsPro"); break;
                            case 2: if (mTabHost != null) mTabHost.setCurrentTabByTag("Transfers"); break;
                            case 3: if (mTabHost != null) mTabHost.setCurrentTabByTag("Settings"); break;
                            case 4: toggleLanguage(); break;
                            case 5: showLogoutConfirmation(); break;
                        }
                    })
                    .show();
            }
            return true;
        });
    }

    // ==================== باقي الكود الأصلي (لم يتغير) ====================

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
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
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkBranchChange();
    }

    private void checkBranchChange() {
        int currentBranchId = branchesManager.getActiveBranchId();
        if (currentBranchId != lastActiveBranchId) {
            lastActiveBranchId = currentBranchId;
            updateToolbarTitle();
            refreshCurrentTab();
        }
    }

    private void updateToolbarTitle() {
        if (getSupportActionBar() != null) {
            String displayName = sessionManager.getDisplayName();
            if (displayName == null || displayName.isEmpty()) {
                displayName = sessionManager.getUsername();
            }
            MiraOrgProfileManager orgProfile = MiraOrgProfileManager.getInstance(this);
            getSupportActionBar().setTitle(orgProfile.getOrgName() + " - " + displayName);
            if (branchesManager.hasActiveBranch()) {
                getSupportActionBar().setSubtitle("📍 " + branchesManager.getActiveBranchDisplayName());
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem langItem = menu.findItem(R.id.action_change_language);
        if (langItem != null) {
            langItem.setTitle(isArabic ? "English" : "العربية");
        }
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
        if (id == R.id.speed) { startActivity(new Intent(this, TestActivity.class)); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void toggleLanguage() {
        isArabic = !isArabic;
        getSharedPreferences("app_settings", MODE_PRIVATE).edit().putString("language", isArabic ? "ar" : "en").apply();
        applyLanguage();
    }

    private void applyLanguage() {
        LocaleListCompat appLocale = isArabic ? LocaleListCompat.forLanguageTags("ar") : LocaleListCompat.forLanguageTags("en");
        AppCompatDelegate.setApplicationLocales(appLocale);
        getWindow().getDecorView().setLayoutDirection(isArabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        recreate();
    }

    private void showBranchPicker() {
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
    }

    private void refreshCurrentTab() {
        try {
            String currentTabTag = mTabHost.getCurrentTabTag();
            if (currentTabTag == null) return;
            Fragment cf = getSupportFragmentManager().findFragmentByTag(currentTabTag);
            if (cf != null && cf.isAdded()) {
                View v = cf.getView();
                if (v != null) {
                    android.widget.TextView tvBranch = v.findViewById(R.id.tvBranchName);
                    if (tvBranch != null && branchesManager != null) {
                        tvBranch.setText("📍 " + branchesManager.getActiveBranchDisplayName());
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error refreshing tab: " + e.getMessage());
        }
    }

    public void setActivePOSFragment(MiraPosFragment fragment) { this.activePOSFragment = fragment; }

    public boolean isReaderReady() {
        try { return mReader != null && mReader.getConnectStatus() == ConnectionStatus.CONNECTED; }
        catch (Exception e) { return false; }
    }

    public void onTagRead(String epc) {
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
    }

    protected void initViewPageData() {
        fm = getSupportFragmentManager();
        mTabHost = findViewById(android.R.id.tabhost);
        mTabHost.setup(this, fm, R.id.realtabcontent);

        SessionManager.Role role = sessionManager.getUserRole();
        boolean isSystemAdmin = (role == SessionManager.Role.SYSTEM_ADMIN);
        boolean isOrgAdmin = (role == SessionManager.Role.ORG_ADMIN);
        boolean isBranchManager = (role == SessionManager.Role.BRANCH_MANAGER);
        boolean isAnyAdmin = isSystemAdmin || isOrgAdmin;
        boolean isManager = isAnyAdmin || isBranchManager;
        boolean isInventoryMgr = (role == SessionManager.Role.INVENTORY_MGR);
        boolean isPosOperator = (role == SessionManager.Role.POS_OPERATOR);
        boolean isGateSecurity = (role == SessionManager.Role.GATE_SECURITY);

        safeAddTab("Dashboard", isArabic ? "🏠 الرئيسية" : "🏠 Dashboard", MiraDashboardFragment.class);
        if (isManager || isPosOperator) {
            safeAddTab("JewelryDashboard", isArabic ? "💎 المجوهرات" : "💎 Jewelry", JewelryDashboardFragment.class);
            safeAddTab("POS Mode", isArabic ? "🛒 البيع" : "🛒 POS", MiraPosFragment.class);
            safeAddTab("SalesHistory", isArabic ? "📜 المبيعات" : "📜 Sales", MiraSalesHistoryFragment.class);
        }
        safeAddTab("Scan", isArabic ? "📡 الجرد" : "📡 Inventory", MiraUHFReadTagFragment.class);
        if (isManager || isInventoryMgr) {
            safeAddTab("IdentityStudio", isArabic ? "🆔 التكويد" : "🆔 ID", MiraIdentityStudioFragment.class);
            safeAddTab("Inventory Pro", isArabic ? "📊 المخزون" : "📊 Inventory", MiraInventoryProFragment.class);
        }
        if (isManager || isGateSecurity) {
            safeAddTab("SecureGate", isArabic ? "🚪 البوابات" : "🚪 Gate", MiraSecureGateFragment.class);
        }
        if (isManager) {
            safeAddTab("BranchesManagement", isArabic ? "🏢 الفروع" : "🏢 Branches", MiraBranchesManagementFragment.class);
            safeAddTab("UsersManagement", isArabic ? "👥 المستخدمين" : "👥 Users", UsersManagementFragment.class);
            safeAddTab("ReportsPro", isArabic ? "📊 التقارير" : "📊 Reports", MiraReportsProFragment.class);
        }
        if (isManager || role == SessionManager.Role.AUDITOR) {
            safeAddTab("Accounting", isArabic ? "💰 المحاسبة" : "💰 Accounting", MiraAccountingFragment.class);
        }
        if (isManager || isInventoryMgr) {
            safeAddTab("Transfers", isArabic ? "📦 التحويلات" : "📦 Transfers", MiraTransferFragment.class);
        }

        mTabHost.setOnTabChangedListener(tabId -> {
            try {
                Fragment fragment = getSupportFragmentManager().findFragmentByTag(tabId);
                if (fragment != null) currentFragment = fragment;
            } catch (Exception e) { Log.e(TAG, "Tab switch error: " + e.getMessage()); }
        });
    }

    private void safeAddTab(String tag, String label, Class<?> clazz) {
        try {
            if (Fragment.class.isAssignableFrom(clazz)) {
                mTabHost.addTab(mTabHost.newTabSpec(tag).setIndicator(label), (Class<? extends Fragment>) clazz, null);
            }
        } catch (Exception e) { Log.e(TAG, "Failed to add tab: " + tag); }
    }

    private void showLogoutConfirmation() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(isArabic ? "تسجيل الخروج" : "Sign Out")
            .setMessage(isArabic ? "هل أنت متأكد؟" : "Are you sure?")
            .setPositiveButton(isArabic ? "تسجيل الخروج" : "Sign Out", (d, w) -> performLogout())
            .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
            .show();
    }

    private void showProfileDialog() {
        String msg = (isArabic ? "المستخدم: " : "User: ") + sessionManager.getDisplayName() + "\n" +
                     (isArabic ? "الفرع: " : "Branch: ") + branchesManager.getActiveBranchDisplayName();
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(isArabic ? "ملف المستخدم" : "Profile")
            .setMessage(msg)
            .setPositiveButton(ar("إغلاق", "Close"), null)
            .show();
    }

    public void performLogout() {
        if (sessionManager != null) sessionManager.logout();
        if (branchesManager != null) branchesManager.clear();
        navigateToLogin();
    }

    private void navigateToLogin() {
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        if (playSoundThread != null) playSoundThread.stopPlay();
        releaseSoundPool();
        if (mReader != null) mReader.free();
        super.onDestroy();
    }

    @Override
    public void exportData() {
        checkReadWritePermission();
        if (loopFlag) { UIHelper.ToastMessage(this, R.string.uhf_msg_scaning); return; }
        if (tagList == null || tagList.isEmpty()) { UIHelper.ToastMessage(this, R.string.uhf_msg_export_data_empty); return; }
        new ExportExcelAsyncTask(this, tagList).execute();
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
        if (soundPool == null) return;
        float max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        float cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        volumnRatio = cur / max;
        try { soundPool.play(soundMap.get(id), volumnRatio, volumnRatio, 1, 0, 1); } catch (Exception e) { e.printStackTrace(); }
    }

    public void openReceiptFragment(String responseJson) {
        try {
            MiraReceiptFragment receipt = MiraReceiptFragment.newInstance(responseJson);
            receipt.show(getSupportFragmentManager(), "mira_receipt");
        } catch (Exception e) { showToast("✅ تم البيع بنجاح!"); }
    }

    private void checkReadWritePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) { Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION); i.setData(Uri.parse("package:" + getPackageName())); startActivityForResult(i, 0); finish(); }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 2);
        }
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
