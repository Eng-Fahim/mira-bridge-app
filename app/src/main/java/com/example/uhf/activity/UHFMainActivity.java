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
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTabHost;

import com.example.uhf.R;
import com.example.uhf.fragment.AuthenticateNxpUcodeDnaFragment;
import com.example.uhf.fragment.BlockPermalockFragment;
import com.example.uhf.fragment.BlockWriteFragment;
import com.example.uhf.fragment.JewelryDashboardFragment;
import com.example.uhf.fragment.MarginReadFragment;
import com.example.uhf.fragment.MiraBranchPickerFragment;
import com.example.uhf.fragment.MiraDashboardFragment;
import com.example.uhf.fragment.MiraIdentityStudioFragment;
import com.example.uhf.fragment.MiraInventoryProFragment;
import com.example.uhf.fragment.MiraPosFragment;
import com.example.uhf.fragment.MiraReceiptFragment;
import com.example.uhf.fragment.MiraSalesHistoryFragment;
import com.example.uhf.fragment.MiraSecureGateFragment;
import com.example.uhf.fragment.MiraTransferFragment;
import com.example.uhf.fragment.ProtectedModeAndShortRangeModeFragment;
import com.example.uhf.fragment.UHFKillFragment;
import com.example.uhf.fragment.UHFLocationFragment;
import com.example.uhf.fragment.UHFLockFragment;
import com.example.uhf.fragment.UHFRadarLocationFragment;
import com.example.uhf.fragment.UHFReadTagFragment;
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
import com.example.uhf.tools.UIHelper;
import com.rscja.deviceapi.entity.UHFTAGInfo;
import com.rscja.deviceapi.interfaces.ConnectionStatus;

//التقارير
import com.example.uhf.fragment.MiraReportsFragment;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        super.onCreate(savedInstanceState);

        boolean isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");
        if (isArabic) {
            getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        } else {
            getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        }

        sessionManager = new SessionManager(this);
        if (!sessionManager.isLoggedIn()) {
            navigateToLogin();
            return;
        }

        branchesManager = MiraBranchesManager.getInstance(this);

        setContentView(R.layout.activity_main);

        com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            String displayName = sessionManager.getDisplayName();
            if (displayName == null || displayName.isEmpty()) {
                displayName = sessionManager.getUsername();
            }
            if (getSupportActionBar() != null) {
                MiraOrgProfileManager orgProfile = MiraOrgProfileManager.getInstance(this);
                getSupportActionBar().setTitle(orgProfile.getOrgName() + " - " + displayName);

                if (branchesManager.hasActiveBranch()) {
                    getSupportActionBar().setSubtitle("📍 " + branchesManager.getActiveBranchDisplayName());
                }
            }
        }

        checkReadWritePermission();
        initSound();
        initUHF();
        initViewPageData();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Log.d(TAG, "UI Mode / Configuration changed. Adapting themes dynamically.");
    }

    public void setActivePOSFragment(MiraPosFragment fragment) { this.activePOSFragment = fragment; }

    public boolean isReaderReady() {
        try {
            return mReader != null && mReader.getConnectStatus() == ConnectionStatus.CONNECTED;
        } catch (Exception e) { return false; }
    }

    public void onTagRead(String epc) {
        if (activePOSFragment != null && activePOSFragment.isVisible()) {
            activePOSFragment.onRingScanned(epc);
            return;
        }
        if (mTabHost != null) {
            String currentTabTag = mTabHost.getCurrentTabTag();
            Fragment fragment = getSupportFragmentManager().findFragmentByTag(currentTabTag);
            if (fragment instanceof MiraIdentityStudioFragment && fragment.isVisible()) {
                ((MiraIdentityStudioFragment) fragment).onRfidScanned(epc);
            } else if (fragment instanceof MiraPosFragment && fragment.isVisible()) {
                ((MiraPosFragment) fragment).onRingScanned(epc);
            }
        }
    }

    protected void initViewPageData() {
        fm = getSupportFragmentManager();
        mTabHost = findViewById(android.R.id.tabhost);
        mTabHost.setup(this, fm, R.id.realtabcontent);

        boolean isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            mTabHost.setLayoutDirection(isArabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        }

        SessionManager.Role role = sessionManager.getUserRole();

        boolean isSystemAdmin = (role == SessionManager.Role.SYSTEM_ADMIN);
        boolean isOrgAdmin = (role == SessionManager.Role.ORG_ADMIN);
        boolean isBranchManager = (role == SessionManager.Role.BRANCH_MANAGER);
        boolean isAnyAdmin = isSystemAdmin || isOrgAdmin;
        boolean isManager = isAnyAdmin || isBranchManager;
        boolean isInventoryMgr = (role == SessionManager.Role.INVENTORY_MGR);
        boolean isPosOperator = (role == SessionManager.Role.POS_OPERATOR);
        boolean isGateSecurity = (role == SessionManager.Role.GATE_SECURITY);

        // 🏠 لوحة التحكم - للجميع
        mTabHost.addTab(
            mTabHost.newTabSpec("Dashboard").setIndicator(isArabic ? "🏠 الرئيسية" : "🏠 Dashboard"),
            MiraDashboardFragment.class, null
        );

        // 💎 المجوهرات + POS + سجل المبيعات
        if (isManager || isPosOperator) {
            mTabHost.addTab(
                mTabHost.newTabSpec("JewelryDashboard").setIndicator(isArabic ? "💎 المجوهرات" : "💎 Jewelry"),
                JewelryDashboardFragment.class, null
            );
            mTabHost.addTab(
                mTabHost.newTabSpec("POS Mode").setIndicator(isArabic ? "🛒 البيع" : "🛒 POS"),
                MiraPosFragment.class, null
            );
            mTabHost.addTab(
                mTabHost.newTabSpec("SalesHistory").setIndicator(isArabic ? "📜 المبيعات" : "📜 Sales"),
                MiraSalesHistoryFragment.class, null
            );
        }

        // 📷 المسح - للجميع
        mTabHost.addTab(
            mTabHost.newTabSpec("Scan").setIndicator(isArabic ? "📷 المسح" : "📷 Scan"),
            UHFReadTagFragment.class, null
        );

        // 🆔 التكويد + المخزون + الرادار
        if (isManager || isInventoryMgr) {
            mTabHost.addTab(
                mTabHost.newTabSpec("IdentityStudio").setIndicator(isArabic ? "🆔 التكويد" : "🆔 ID"),
                MiraIdentityStudioFragment.class, null
            );
            mTabHost.addTab(
                mTabHost.newTabSpec("Inventory Pro").setIndicator(isArabic ? "📊 المخزون" : "📊 Inventory"),
                MiraInventoryProFragment.class, null
            );
            mTabHost.addTab(
                mTabHost.newTabSpec("RadarLocation").setIndicator(isArabic ? "🎯 الرادار" : "🎯 Radar"),
                UHFRadarLocationFragment.class, null
            );
        }

        // 🚪 البوابات
        if (isManager || isGateSecurity) {
            mTabHost.addTab(
                mTabHost.newTabSpec("SecureGate").setIndicator(isArabic ? "🚪 البوابات" : "🚪 Gate"),
                MiraSecureGateFragment.class, null
            );
        }

        // 👥 المستخدمين
        if (isManager) {
            mTabHost.addTab(
                mTabHost.newTabSpec("UsersManagement").setIndicator(isArabic ? "👥 المستخدمين" : "👥 Users"),
                UsersManagementFragment.class, null
            );
        }

        // 📈 التقارير
        if (isManager) {
            mTabHost.addTab(
                mTabHost.newTabSpec("Reports").setIndicator(isArabic ? "📈 التقارير" : "📈 Reports"),
                MiraReportsFragment.class, null
            );
        }

        // 📦 التحويلات
        if (isManager || isInventoryMgr) {
            mTabHost.addTab(
                mTabHost.newTabSpec("Transfers").setIndicator(isArabic ? "📦 التحويلات" : "📦 Transfers"),
                MiraTransferFragment.class, null
            );
        }

        // ⚙️ الإعدادات المتقدمة - SYSTEM_ADMIN فقط
        if (isSystemAdmin) {
            mTabHost.addTab(mTabHost.newTabSpec("Settings").setIndicator(isArabic ? "⚙️ الإعدادات" : "⚙️ Settings"), UHFSetFragment.class, null);
            mTabHost.addTab(mTabHost.newTabSpec("Location").setIndicator(isArabic ? "📍 المواقع" : "📍 Location"), UHFLocationFragment.class, null);
            mTabHost.addTab(mTabHost.newTabSpec("TagLit").setIndicator(isArabic ? "💡 إضاءة" : "💡 TagLit"), UHFTagLitFragment.class, null);
            mTabHost.addTab(mTabHost.newTabSpec("TagFlash").setIndicator(isArabic ? "⚡ فلاش" : "⚡ Flash"), UHFTagFlashFragment.class, null);
            mTabHost.addTab(mTabHost.newTabSpec("ReadWrite").setIndicator(isArabic ? "📝 كتابة" : "📝 R/W"), UHFReadWriteFragment.class, null);
            mTabHost.addTab(mTabHost.newTabSpec("Lock").setIndicator(isArabic ? "🔒 قفل" : "🔒 Lock"), UHFLockFragment.class, null);
            mTabHost.addTab(mTabHost.newTabSpec("Kill").setIndicator(isArabic ? "🛑 إلغاء" : "🛑 Kill"), UHFKillFragment.class, null);
            mTabHost.addTab(mTabHost.newTabSpec("Protected-ShortRange").setIndicator(isArabic ? "🛡️ آمن" : "🛡️ Protected"), ProtectedModeAndShortRangeModeFragment.class, null);
            mTabHost.addTab(mTabHost.newTabSpec("MarginRead").setIndicator(isArabic ? "📊 الهامش" : "📊 Margin"), MarginReadFragment.class, null);
            mTabHost.addTab(mTabHost.newTabSpec("NxpUcodeDna").setIndicator(isArabic ? "🧬 DNA" : "🧬 DNA"), AuthenticateNxpUcodeDnaFragment.class, null);
            mTabHost.addTab(mTabHost.newTabSpec("BlockWrite").setIndicator(isArabic ? "📦 بلوك" : "📦 Block"), BlockWriteFragment.class, null);
            mTabHost.addTab(mTabHost.newTabSpec("BlockPermalock").setIndicator(isArabic ? "🔐 دائم" : "🔐 Permalock"), BlockPermalockFragment.class, null);
            mTabHost.addTab(mTabHost.newTabSpec("Upgrade").setIndicator(isArabic ? "⬆️ ترقية" : "⬆️ Upgrade"), UHFUpgradeFragment.class, null);
        }
    }

    private void showBranchPicker() {
        // ✅ فقط SYSTEM_ADMIN و ORG_ADMIN يمكنهم تغيير الفرع
        if (branchesManager.isBranchLocked() && !sessionManager.isAnyAdmin()) {
            Toast.makeText(this, "الفرع محدد من قبل النظام ولا يمكن تغييره", Toast.LENGTH_LONG).show();
            return;
        }

        MiraBranchPickerFragment picker = new MiraBranchPickerFragment();
        picker.setOnBranchSelectedListener((branchId, branchName, branchCode) -> {
            if (getSupportActionBar() != null) {
                getSupportActionBar().setSubtitle("📍 " + branchName);
            }
            branchesManager.setActiveBranch(branchId, branchName, branchCode, "", "");
            Toast.makeText(this, "✅ " + branchName, Toast.LENGTH_SHORT).show();
        });
        picker.show(getSupportFragmentManager(), "branch_picker");
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_logout) { showLogoutConfirmation(); return true; }
        if (id == R.id.action_profile) { showProfileDialog(); return true; }
        if (id == R.id.action_branches) { showBranchPicker(); return true; }
        if (id == R.id.export) { exportData(); return true; }
        if (id == R.id.UHF_ver) { getUHFVersion(); return true; }
        if (id == R.id.speed) { startActivity(new Intent(this, TestActivity.class)); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void showLogoutConfirmation() {
        boolean isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(isArabic ? "تسجيل الخروج" : "Sign Out")
            .setMessage(isArabic ? "هل أنت متأكد أنك تريد تسجيل الخروج؟" : "Are you sure you want to sign out?")
            .setPositiveButton(isArabic ? "تسجيل الخروج" : "Sign Out", (d, w) -> performLogout())
            .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
            .setIcon(R.drawable.ic_logout)
            .show();
    }

    private void showProfileDialog() {
        boolean isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");
        String username = sessionManager.getUsername();
        String displayName = sessionManager.getDisplayName();
        String role = sessionManager.getRoleString();
        String serverUrl = sessionManager.getServerUrl();
        boolean biometricEnabled = sessionManager.isBiometricEnabled();

        String roleLabel;
        switch (role) {
            case "SYSTEM_ADMIN":   roleLabel = "🔧 System Administrator"; break;
            case "ORG_ADMIN":      roleLabel = "🏢 Organization Admin"; break;
            case "BRANCH_MANAGER": roleLabel = "👤 Branch Manager"; break;
            case "INVENTORY_MGR":  roleLabel = "📊 Inventory Manager"; break;
            case "POS_OPERATOR":   roleLabel = "🛒 POS Operator"; break;
            case "SECURITY":       roleLabel = "🔒 Security"; break;
            case "GATE_SECURITY":  roleLabel = "🚪 Gate Security"; break;
            case "AUDITOR":        roleLabel = "🔍 Auditor"; break;
            default:               roleLabel = role;
        }

        String message = (isArabic ? "المستخدم: " : "User: ") + displayName + "\n"
                       + (isArabic ? "اسم المستخدم: " : "Username: ") + username + "\n"
                       + (isArabic ? "الصلاحية: " : "Role: ") + roleLabel + "\n"
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
        Log.e(TAG, "onDestroy()");
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
        float audioMaxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        float audioCurrentVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        volumnRatio = audioCurrentVolume / audioMaxVolume;
        try { soundPool.play(soundMap.get(id), volumnRatio, volumnRatio, 1, 0, 1); } catch (Exception e) { e.printStackTrace(); }
    }

    public void openReceiptFragment(String responseJson) {
        try {
            MiraReceiptFragment receipt = MiraReceiptFragment.newInstance(responseJson);
            FragmentManager fm = getSupportFragmentManager();
            if (fm != null && !fm.isStateSaved() && !fm.isDestroyed()) {
                fm.beginTransaction()
                  .replace(android.R.id.content, receipt)
                  .addToBackStack("mira_receipt")
                  .commitAllowingStateLoss();
            } else {
                showToast("✅ تم البيع بنجاح!");
            }
        } catch (Exception e) {
            Log.e("UHFMainActivity", "Error opening receipt: " + e.getMessage());
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
    public void showToast(String text) { if (toast != null) toast.cancel(); toast = Toast.makeText(this, text, Toast.LENGTH_SHORT); toast.show(); }
    public void showToast(int resId) { showToast(getString(resId)); }
    public void playSoundDelayed(int speed) { playSoundThread.play(speed); }

    private final Object objectLock = new Object();
    private class PlaySoundThread extends Thread {
        private boolean isStop = false;
        int interval = 500;
        long lastPlayTime = SystemClock.elapsedRealtime();
        @Override public void run() {
            while (!isStop) {
                long start = 0;
                synchronized (objectLock) {
                    while (!isStop) {
                        if (start == 0) start = SystemClock.elapsedRealtime();
                        else if (SystemClock.elapsedRealtime() - start >= interval) break;
                        else SystemClock.sleep(1);
                    }
                }
                if (SystemClock.elapsedRealtime() - lastPlayTime < 500) playSound(1);
            }
        }
        public void play(int speed) {
            int t = 3;
            if (speed > 85) t = 3; else if (speed > 66) t = 100 - speed;
            else if (speed > 33) t = (100 - speed) * 2; else t = (100 - speed) * 3;
            interval = t; lastPlayTime = SystemClock.elapsedRealtime();
        }
        public void stopPlay() { isStop = true; synchronized (objectLock) { objectLock.notifyAll(); } }
    }
}
