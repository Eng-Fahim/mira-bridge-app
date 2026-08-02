package com.example.uhf.activity;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
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

    // ⭐ POS Mode - مستقبل RFID النشط
    private MiraPosFragment activePOSFragment = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        checkReadWritePermission();
        setTitle("MIRA Bridge™");
        initSound();
        initUHF();
        initViewPageData();
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

            if (fragment instanceof MiraPosFragment && fragment.isVisible()) {
                ((MiraPosFragment) fragment).onRingScanned(epc);
                Log.d(TAG, "RFID → POS Tab: " + epc);
                return;
            }
        }

        Log.d(TAG, "RFID scanned but POS not active: " + epc);
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

    protected void initViewPageData() {
        fm = getSupportFragmentManager();
        mTabHost = (FragmentTabHost) findViewById(android.R.id.tabhost);
        mTabHost.setup(this, fm, R.id.realtabcontent);

        // 💎 Jewelry Dashboard - لوحة المجوهرات
        mTabHost.addTab(
            mTabHost.newTabSpec("JewelryDashboard").setIndicator("💎"),
            JewelryDashboardFragment.class, null
        );

        // 🛒 MIRA POS Mode - وضع البيع
        mTabHost.addTab(
            mTabHost.newTabSpec("POS Mode").setIndicator("🛒"),
            MiraPosFragment.class, null
        );

        // 📷 Scan
        mTabHost.addTab(mTabHost.newTabSpec(getString(R.string.uhf_msg_tab_scan))
            .setIndicator(getString(R.string.uhf_msg_tab_scan)),
            UHFReadTagFragment.class, null);

        // 🌐 MIRA Web (PWA)
        mTabHost.addTab(
            mTabHost.newTabSpec("MIRA Web").setIndicator("🌐"),
            MiraPWAWebFragment.class, null
        );

        // 🏠 Dashboard - التبويب الرئيسي
        mTabHost.addTab(
            mTabHost.newTabSpec("Dashboard").setIndicator("🏠"),
            MiraDashboardFragment.class, null
        );

        // ⚙️ Config
        mTabHost.addTab(mTabHost.newTabSpec(getString(R.string.uhf_msg_tab_set))
            .setIndicator(getString(R.string.uhf_msg_tab_set)),
            UHFSetFragment.class, null);

        // 📊 Inventory Pro
        mTabHost.addTab(mTabHost.newTabSpec("Inventory Pro").setIndicator("📊"),
            MiraInventoryProFragment.class, null);

        // 📡 Radar
        mTabHost.addTab(mTabHost.newTabSpec(getResources().getString(R.string.uhf_radar_loaction))
            .setIndicator(getResources().getString(R.string.uhf_radar_loaction)),
            UHFRadarLocationFragment.class, null);

        // 📍 Locating
        mTabHost.addTab(mTabHost.newTabSpec(getString(R.string.location))
            .setIndicator(getString(R.string.location)),
            UHFLocationFragment.class, null);

        // 💡 Tag Lit
        mTabHost.addTab(mTabHost.newTabSpec(getString(R.string.title_tag_lit))
            .setIndicator(getString(R.string.title_tag_lit)),
            UHFTagLitFragment.class, null);

        // ⚡ Tag Flash
        mTabHost.addTab(mTabHost.newTabSpec(getString(R.string.title_tag_flash))
            .setIndicator(getString(R.string.title_tag_flash)),
            UHFTagFlashFragment.class, null);

        // Read/Write
        mTabHost.addTab(mTabHost.newTabSpec(getString(R.string.uhf_msg_tab_read_write))
            .setIndicator(getString(R.string.uhf_msg_tab_read_write)),
            UHFReadWriteFragment.class, null);

        // Lock
        mTabHost.addTab(mTabHost.newTabSpec(getString(R.string.uhf_msg_tab_lock))
            .setIndicator(getString(R.string.uhf_msg_tab_lock)),
            UHFLockFragment.class, null);

        // Kill
        mTabHost.addTab(mTabHost.newTabSpec(getString(R.string.uhf_msg_tab_kill))
            .setIndicator(getString(R.string.uhf_msg_tab_kill)),
            UHFKillFragment.class, null);

        // 🚪 MIRA Secure Gate™
        mTabHost.addTab(
            mTabHost.newTabSpec("MIRA Secure Gate™").setIndicator("🚪"),
            MiraSecureGateFragment.class, null
        );

        // تبويبات متقدمة (مخفية أو ثانوية)
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

    @Override
    protected void onDestroy() {
        Log.e("zz_pp", "onDestroy()");
        releaseSoundPool();
        if (mReader != null) {
            mReader.free();
        }
        super.onDestroy();
        android.os.Process.killProcess(android.os.Process.myPid());
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
