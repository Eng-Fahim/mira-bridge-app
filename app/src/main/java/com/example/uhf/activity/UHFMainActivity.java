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
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
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
import com.example.uhf.fragment.MiraUHFReadTagFragment;
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
import com.example.uhf.tools.DeviceDetector;
import com.example.uhf.tools.UIHelper;
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

        // تسجيل معالج زر العودة لحماية عملية الجرد النشطة
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
                    getSupportFragmentManager().popBackStack();
                    return;
                }

                if (mTabHost != null) {
                    String currentTabTag = mTabHost.getCurrentTabTag();
                    if (currentTabTag != null) {
                        Fragment activeFragment = getSupportFragmentManager().findFragmentByTag(currentTabTag);
                        if (activeFragment instanceof MiraUHFReadTagFragment && activeFragment.isVisible()) {
                            if (((MiraUHFReadTagFragment) activeFragment).onBackPressed()) {
                                return; 
                            }
                        }
                    }
                }

                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
    } // إغلاق onCreate بالشكل الصحيح

    /**
     * معالجة ضغطة زر الـ Hardware الخارجي للجهاز لتبويب الجرد المباشر
     */
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
        if (id == R.id.action_change_language) {
            toggleLanguage();
            return true;
        }
        if (id == R.id.action_logout) {
            showLogoutConfirmation();
            return true;
        }
        if (id == R.id.action_profile) {
            showProfileDialog();
            return true;
        }
        if (id == R.id.action_branches) {
            showBranchPicker();
            return true;
        }
        if (id == R.id.export) {
            exportData();
            return true;
        }
        if (id == R.id.UHF_ver) {
            getUHFVersion();
            return true;
        }
        if (id == R.id.speed) {
            startActivity(new Intent(this, TestActivity.class));
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
    }

    private void applyLanguage() {
        LocaleListCompat appLocale = isArabic ? LocaleListCompat.forLanguageTags("ar") : LocaleListCompat.forLanguageTags("en");
        AppCompatDelegate.setApplicationLocales(appLocale);
        getWindow().getDecorView().setLayoutDirection(isArabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        recreate();
    }

    private void showBranchPicker() {
        if (branchesManager.isBranchLocked() && !sessionManager.isAnyAdmin()) {
            Toast.makeText(this,
                isArabic ? "الفرع محدد من قبل النظام ولا يمكن تغييره" : "Branch is locked by system and cannot be changed",
                Toast.LENGTH_LONG).show();
            return;
        }

        MiraBranchPickerFragment picker = new MiraBranchPickerFragment();
        picker.setOnBranchSelectedListener((branchId, branchName, branchCode) -> {
            if (getSupportActionBar() != null) {
                getSupportActionBar().setSubtitle("📍 " + branchName);
            }
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

            Fragment currentFragment = getSupportFragmentManager().findFragmentByTag(currentTabTag);
            if (currentFragment != null && currentFragment.isAdded()) {
                View v = currentFragment.getView();
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
                    return;
                }
                if (fragment instanceof MiraIdentityStudioFragment && fragment.isVisible()) {
                    ((MiraIdentityStudioFragment) fragment).onRfidScanned(epc);
                    return;
                }
                if (fragment instanceof MiraPosFragment && fragment.isVisible()) {
                    ((MiraPosFragment) fragment).onRingScanned(epc);
                }
            }
        }
    }

    protected void initViewPageData() {
        fm = getSupportFragmentManager();
        mTabHost = findViewById(android.R.id.tabhost);
        mTabHost.setup(this, fm, R.id.realtabcontent);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            mTabHost.setLayoutDirection(isArabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        }

        mTabHost.setOnTabChangedListener(null);

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
            safeAddTab("RadarLocation", isArabic ? "🎯 الرادار" : "🎯 Radar", UHFRadarLocationFragment.class);
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

        if (isSystemAdmin) {
            safeAddTab("Settings", isArabic ? "⚙️ الإعدادات" : "⚙️ Settings", UHFSetFragment.class);
            safeAddTab("Location", isArabic ? "📍 المواقع" : "📍 Location", UHFLocationFragment.class);
            safeAddTab("TagLit", isArabic ? "💡 إضاءة" : "💡 TagLit", UHFTagLitFragment.class);
            safeAddTab("TagFlash", isArabic ? "⚡ فلاش" : "⚡ Flash", UHFTagFlashFragment.class);
            safeAddTab("ReadWrite", isArabic ? "📝 كتابة" : "📝 R/W", UHFReadWriteFragment.class);
            safeAddTab("Lock", isArabic ? "🔒 قفل" : "🔒 Lock", UHFLockFragment.class);
            safeAddTab("Kill", isArabic ? "🛑 إلغاء" : "🛑 Kill", UHFKillFragment.class);
            safeAddTab("Protected-ShortRange", isArabic ? "🛡️ آمن" : "🛡️ Protected", ProtectedModeAndShortRangeModeFragment.class);
            safeAddTab("MarginRead", isArabic ? "📊 الهامش" : "📊 Margin", MarginReadFragment.class);
            safeAddTab("NxpUcodeDna", isArabic ? "🧬 DNA" : "🧬 DNA", AuthenticateNxpUcodeDnaFragment.class);
            safeAddTab("BlockWrite", isArabic ? "📦 بلوك" : "📦 Block", BlockWriteFragment.class);
            safeAddTab("BlockPermalock", isArabic ? "🔐 دائم" : "🔐 Permalock", BlockPermalockFragment.class);
            safeAddTab("Upgrade", isArabic ? "⬆️ ترقية" : "⬆️ Upgrade", UHFUpgradeFragment.class);
        }

        mTabHost.setOnTabChangedListener(tabId -> {
            try {
                Fragment fragment = getSupportFragmentManager().findFragmentByTag(tabId);
                if (fragment != null) currentFragment = fragment;
            } catch (Exception e) {
                Log.e(TAG, "Tab switch error: " + e.getMessage());
            }
        });
    }

    private void safeAddTab(String tag, String label, Class<?> clazz) {
        try {
            if (Fragment.class.isAssignableFrom(clazz)) {
                @SuppressWarnings("unchecked")
                Class<? extends Fragment> fragmentClass = (Class<? extends Fragment>) clazz;
                mTabHost.addTab(mTabHost.newTabSpec(tag).setIndicator(label), fragmentClass, null);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to add tab: " + tag + " -> " + e.getMessage());
        }
    }

    private void showLogoutConfirmation() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(isArabic ? "تسجيل الخروج" : "Sign Out")
            .setMessage(isArabic ? "هل أنت متأكد أنك تريد تسجيل الخروج؟" : "Are you sure you want to sign out?")
            .setPositiveButton(isArabic ? "تسجيل الخروج" : "Sign Out", (d, w) -> performLogout())
            .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
            .setIcon(R.drawable.ic_logout)
            .show();
    }

    private void showProfileDialog() {
        String username = sessionManager.getUsername();
        String displayName = sessionManager.getDisplayName();
        String role = sessionManager.getRoleString();
        String serverUrl = sessionManager.getServerUrl();
        boolean biometricEnabled = sessionManager.isBiometricEnabled();

        String message = (isArabic ? "المستخدم: " : "User: ") + displayName + "\n"
                + (isArabic ? "اسم المستخدم: " : "Username: ") + username + "\n"
                + (isArabic ? "الصلاحية: " : "Role: ") + role + "\n"
                + (isArabic ? "البصمة: " : "Biometric: ") + (biometricEnabled ? (isArabic ? "مفعلة" : "Enabled") : (isArabic ? "معطلة" : "Disabled")) + "\n"
                + (isArabic ? "الفرع: " : "Branch: ") + branchesManager.getActiveBranchDisplayName() + "\n"
                + (isArabic ? "الخادم: " : "Server: ") + serverUrl;

        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(isArabic ? "ملف المستخدم" : "User Profile")
            .setMessage(message)
            .setPositiveButton(isArabic ? "إغلاق" : "Close", null)
            .setNeutralButton(isArabic ? "تسجيل الخروج" : "Sign Out", (d, w) -> showLogoutConfirmation())
            .setIcon(R.drawable.ic_person_outline)
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
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    protected void onDestroy() {
        if (playSoundThread != null) {
            playSoundThread.stopPlay();
            playSoundThread = null;
        }
        releaseSoundPool();
        if (mReader != null) mReader.free();
        super.onDestroy();
    }

    @Override
    public void exportData() {
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
    }

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

    private void releaseSoundPool() {
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
    }

    public void playSound(int id) {
        if (soundPool == null) return;
        float audioMaxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        float audioCurrentVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        volumnRatio = audioCurrentVolume / audioMaxVolume;
        try {
            soundPool.play(soundMap.get(id), volumnRatio, volumnRatio, 1, 0, 1);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void openReceiptFragment(String responseJson) {
        try {
            MiraReceiptFragment receipt = MiraReceiptFragment.newInstance(responseJson);
            receipt.show(getSupportFragmentManager(), "mira_receipt");
        } catch (Exception e) {
            showToast("✅ تم البيع بنجاح!");
        }
    }

    private void checkReadWritePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, 0);
                finish();
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 2);
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
        int interval = 500;
        long lastPlayTime = SystemClock.elapsedRealtime();

        @Override
        public void run() {
            while (!isStop) {
                long start = 0;
                synchronized (objectLock) {
                    while (!isStop) {
                        if (start == 0) start = SystemClock.elapsedRealtime();
                        else if (SystemClock.elapsedRealtime() - start >= interval) break;
                        else SystemClock.sleep(1);
                    }
                }
                if (!isStop && SystemClock.elapsedRealtime() - lastPlayTime < 500) {
                    playSound(1);
                }
            }
        }

        public void play(int speed) {
            int t = 3;
            if (speed > 85) t = 3;
            else if (speed > 66) t = 100 - speed;
            else if (speed > 33) t = (100 - speed) * 2;
            else t = (100 - speed) * 3;
            interval = t;
            lastPlayTime = SystemClock.elapsedRealtime();
        }

        public void stopPlay() {
            isStop = true;
            synchronized (objectLock) {
                objectLock.notifyAll();
            }
        }
    }
}
