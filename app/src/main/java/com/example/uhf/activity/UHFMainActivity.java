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
import android.view.Menu;
import android.view.MenuItem;
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
import com.example.uhf.fragment.MiraDashboardFragment;
import com.example.uhf.fragment.MiraIdentityStudioFragment;
import com.example.uhf.fragment.MiraInventoryProFragment;
import com.example.uhf.fragment.MiraPosFragment;
import com.example.uhf.fragment.MiraPWAWebFragment;
import com.example.uhf.fragment.MiraSecureGateFragment;
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
import com.example.uhf.tools.ExportExcelAsyncTask;
import com.example.uhf.tools.SessionManager;
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
    public ArrayList<UHFTAGInfo> tagList = new ArrayList<UHFTAGInfo>();
    public boolean loopFlag = false;
    private PlaySoundThread playSoundThread = null;
    public Fragment currentFragment;
    private SessionManager sessionManager;

    // ⭐ POS Mode - مستقبل RFID النشط
    private MiraPosFragment activePOSFragment = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 🎨 تأكيد وضع التبديل التلقائي مع النظام (Day/Night)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        
        super.onCreate(savedInstanceState);

        // 🔒 حماية الشاشة الرئيسية: التحقق من تسجيل الدخول
        sessionManager = new SessionManager(this);
        if (!sessionManager.isLoggedIn()) {
            navigateToLogin();
            return;
        }

        setContentView(R.layout.activity_main);
        checkReadWritePermission();
        
        // ✅ استخدام getDisplayName أولاً، ثم getUsername كاحتياط
        String displayName = sessionManager.getDisplayName();
        if (displayName == null || displayName.isEmpty()) {
            displayName = sessionManager.getUsername();
        }
        setTitle("MIRA Bridge™ - " + displayName);
        
        initSound();
        initUHF();
        initViewPageData();
    }

    // 🔄 الاستجابة الفورية لتغيير وضع الجهاز (Light/Dark Mode)
    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Log.d(TAG, "UI Mode / Configuration changed. Adapting themes dynamically.");
    }

    // ============================================
    // 🟢 POS Mode - تسجيل وإلغاء المستقبل النشط
    // ============================================
    public void setActivePOSFragment(MiraPosFragment fragment) {
        this.activePOSFragment = fragment;
        Log.d(TAG, "Active POS Fragment: " + (fragment != null ? "SET" : "CLEARED"));
    }

    // ============================================
    // 🟢 التحقق من حالة القارئ
    // ============================================
    public boolean isReaderReady() {
        try {
            return mReader != null && mReader.getConnectStatus() == ConnectionStatus.CONNECTED;
        } catch (Exception e) {
            return false;
        }
    }

    // ============================================
    // 🟢 استقبال مسح RFID - توجيه ذكي
    // ============================================
    public void onTagRead(String epc) {
        // 🟢 الأولوية لـ POS Mode إذا كان نشطاً
        if (activePOSFragment != null && activePOSFragment.isVisible()) {
            activePOSFragment.onRingScanned(epc);
            Log.d(TAG, "RFID → Active POS: " + epc);
            return;
        }

        // التحقق من التبويب الحالي
        if (mTabHost != null) {
            String currentTabTag = mTabHost.getCurrentTabTag();
            Fragment fragment = getSupportFragmentManager().findFragmentByTag(currentTabTag);

            // 🆔 توجيه المسح لـ Identity Studio عند قراءة أي شريحة
            if (fragment instanceof MiraIdentityStudioFragment && fragment.isVisible()) {
                ((MiraIdentityStudioFragment) fragment).onRfidScanned(epc);
                Log.d(TAG, "RFID → Identity Studio: " + epc);
                return;
            }

            if (fragment instanceof MiraPosFragment && fragment.isVisible()) {
                ((MiraPosFragment) fragment).onRingScanned(epc);
                Log.d(TAG, "RFID → POS Tab: " + epc);
                return;
            }
        }

        Log.d(TAG, "RFID scanned but no active target: " + epc);
    }

    // ============================================
    // 🟢 تفويض onActivityResult للـ Fragment الحالي
    // ============================================
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (mTabHost != null) {
            String currentTabTag = mTabHost.getCurrentTabTag();
            Fragment fragment = getSupportFragmentManager().findFragmentByTag(currentTabTag);
            if (fragment != null) {
                fragment.onActivityResult(requestCode, resultCode, data);
            }
        }

        if (currentFragment != null && currentFragment instanceof UHFReadTagFragment) {
            currentFragment.onActivityResult(requestCode, resultCode, data);
        }
    }

    // ============================================
    // 🔐 تصفية التبويبات حسب الصلاحيات (Role-Based Access)
    // ============================================
    protected void initViewPageData() {
        fm = getSupportFragmentManager();
        mTabHost = (FragmentTabHost) findViewById(android.R.id.tabhost);
        mTabHost.setup(this, fm, R.id.realtabcontent);

        SessionManager.Role role = sessionManager.getUserRole();

        // 💎 لوحة المجوهرات والـ POS (تظهر للمدراء والمبيعات)
        if (role == SessionManager.Role.ADMIN || role == SessionManager.Role.POS_OPERATOR) {
            mTabHost.addTab(
                mTabHost.newTabSpec("JewelryDashboard").setIndicator("💎"),
                JewelryDashboardFragment.class, null
            );

            mTabHost.addTab(
                mTabHost.newTabSpec("POS Mode").setIndicator("🛒"),
                MiraPosFragment.class, null
            );
        }

        // 📷 شاشة القراءة والتتبع (متاحة للجميع)
        mTabHost.addTab(mTabHost.newTabSpec(getString(R.string.uhf_msg_tab_scan))
            .setIndicator(getString(R.string.uhf_msg_tab_scan)),
            UHFReadTagFragment.class, null);

        // 🆔 استوديو التكوييد والجرد والرادار (للمدراء ومسؤولي الجرد)
        if (role == SessionManager.Role.ADMIN || role == SessionManager.Role.INVENTORY_MGR) {
            mTabHost.addTab(
                mTabHost.newTabSpec("IdentityStudio").setIndicator("🆔"),
                MiraIdentityStudioFragment.class, null
            );

            mTabHost.addTab(mTabHost.newTabSpec("Inventory Pro").setIndicator("📊"),
                MiraInventoryProFragment.class, null);

            mTabHost.addTab(mTabHost.newTabSpec(getResources().getString(R.string.uhf_radar_loaction))
                .setIndicator(getResources().getString(R.string.uhf_radar_loaction)),
                UHFRadarLocationFragment.class, null);
        }

        // 🌐 MIRA Web (PWA)
        mTabHost.addTab(
            mTabHost.newTabSpec("MIRA Web").setIndicator("🌐"),
            MiraPWAWebFragment.class, null
        );

        // 🏠 Dashboard الرئيسي
        mTabHost.addTab(
            mTabHost.newTabSpec("Dashboard").setIndicator("🏠"),
            MiraDashboardFragment.class, null
        );

        // 🚪 بوابات الحماية MIRA Secure Gate™ (للمدراء وأمن البوابات)
        if (role == SessionManager.Role.ADMIN || role == SessionManager.Role.GATE_SECURITY) {
            mTabHost.addTab(
                mTabHost.newTabSpec("MIRA Secure Gate™").setIndicator("🚪"),
                MiraSecureGateFragment.class, null
            );
        }

        // ⚙️ التبويبات المتقدمة والإعدادات (للمدراء فقط)
        if (role == SessionManager.Role.ADMIN) {
            mTabHost.addTab(mTabHost.newTabSpec(getString(R.string.uhf_msg_tab_set))
                .setIndicator(getString(R.string.uhf_msg_tab_set)),
                UHFSetFragment.class, null);

            mTabHost.addTab(mTabHost.newTabSpec(getString(R.string.location))
                .setIndicator(getString(R.string.location)),
                UHFLocationFragment.class, null);

            mTabHost.addTab(mTabHost.newTabSpec(getString(R.string.title_tag_lit))
                .setIndicator(getString(R.string.title_tag_lit)),
                UHFTagLitFragment.class, null);

            mTabHost.addTab(mTabHost.newTabSpec(getString(R.string.title_tag_flash))
                .setIndicator(getString(R.string.title_tag_flash)),
                UHFTagFlashFragment.class, null);

            mTabHost.addTab(mTabHost.newTabSpec(getString(R.string.uhf_msg_tab_read_write))
                .setIndicator(getString(R.string.uhf_msg_tab_read_write)),
                UHFReadWriteFragment.class, null);

            mTabHost.addTab(mTabHost.newTabSpec(getString(R.string.uhf_msg_tab_lock))
                .setIndicator(getString(R.string.uhf_msg_tab_lock)),
                UHFLockFragment.class, null);

            mTabHost.addTab(mTabHost.newTabSpec(getString(R.string.uhf_msg_tab_kill))
                .setIndicator(getString(R.string.uhf_msg_tab_kill)),
                UHFKillFragment.class, null);

            mTabHost.addTab(mTabHost.newTabSpec("Protected-ShortRange").setIndicator("P-SR"),
                ProtectedModeAndShortRangeModeFragment.class, null);
            mTabHost.addTab(mTabHost.newTabSpec("MarginRead").setIndicator("MR"),
                MarginReadFragment.class, null);
            mTabHost.addTab(mTabHost.newTabSpec("NxpUcodeDna").setIndicator("DNA"),
                AuthenticateNxpUcodeDnaFragment.class, null);
            mTabHost.addTab(mTabHost.newTabSpec("BlockWrite").setIndicator("BW"),
                BlockWriteFragment.class, null);
            mTabHost.addTab(mTabHost.newTabSpec("BlockPermalock").setIndicator("BP"),
                BlockPermalockFragment.class, null);
            mTabHost.addTab(mTabHost.newTabSpec(getString(R.string.action_rfid_upgrader))
                .setIndicator(getString(R.string.action_rfid_upgrader)),
                UHFUpgradeFragment.class, null);
        }
    }

    // ============================================
    // 🚪 تسجيل الخروج وتفريغ الجلسة
    // ============================================
    public void performLogout() {
        if (sessionManager != null) {
            sessionManager.logout();  // ✅ موجودة الآن في SessionManager
        }
        navigateToLogin();
    }

    private void navigateToLogin() {
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    @Override
public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.main_menu, menu);
    return true;
}

@Override
public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    int id = item.getItemId();
    
    if (id == R.id.export) {
        exportData();
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
    if (id == R.id.action_settings) {
        return true;
    }
    return super.onOptionsItemSelected(item);
}


    @Override
    protected void onDestroy() {
        Log.e(TAG, "onDestroy()");
        if (playSoundThread != null) {
            playSoundThread.stopPlay();
        }
        releaseSoundPool();
        if (mReader != null) {
            mReader.free();
        }
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

    HashMap<Integer, Integer> soundMap = new HashMap<Integer, Integer>();
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
// ============================================
// 🚪 قائمة الخيارات - تسجيل الخروج
// ============================================
@Override
public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.main_menu, menu);
    return true;
}


/**
 * 🟢 عرض حوار تأكيد تسجيل الخروج
 */
private void showLogoutConfirmation() {
    new androidx.appcompat.app.AlertDialog.Builder(this)
        .setTitle("تسجيل الخروج")
        .setMessage("هل أنت متأكد من تسجيل الخروج من منظومة MIRA؟\n\nسيتم إنهاء جلستك الحالية.")
        .setPositiveButton("تسجيل الخروج", (dialog, which) -> performLogout())
        .setNegativeButton("إلغاء", null)
        .setIcon(android.R.drawable.ic_dialog_alert)
        .show();
}

/**
 * 🟢 عرض معلومات المستخدم الحالي
 */
private void showProfileDialog() {
    String username = sessionManager.getUsername();
    String displayName = sessionManager.getDisplayName();
    String role = sessionManager.getRoleString();
    String serverUrl = sessionManager.getServerUrl();
    
    String message = "👤 المستخدم: " + displayName + "\n"
                   + "📧 اسم الدخول: " + username + "\n"
                   + "🔐 الصلاحية: " + role + "\n"
                   + "🌐 الخادم: " + serverUrl;
    
    new androidx.appcompat.app.AlertDialog.Builder(this)
        .setTitle("ملف المستخدم")
        .setMessage(message)
        .setPositiveButton("موافق", null)
        .setNeutralButton("تسجيل الخروج", (d, w) -> showLogoutConfirmation())
        .setIcon(R.drawable.ic_person)
        .show();
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

    private void checkReadWritePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, 0);
                finish();
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
            }
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 2);
            }
        }
    }

    private Toast toast;
    public void showToast(String text) {
        if (toast != null) toast.cancel();
        toast = Toast.makeText(this, text, Toast.LENGTH_SHORT);
        toast.show();
    }
    public void showToast(int resId) { showToast(getString(resId)); }
    public void playSoundDelayed(int speed) { playSoundThread.play(speed); }

    private Object objectLock = new Object();
    private class PlaySoundThread extends Thread {
        private boolean isStop = false;
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
                if (SystemClock.elapsedRealtime() - lastPlayTime < 500) playSound(1);
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
            synchronized (objectLock) { objectLock.notifyAll(); }
        }
    }
}
