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
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.fragment.app.Fragment;

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
import com.example.uhf.fragment.UHFSetFragment;
import com.example.uhf.fragment.UsersManagementFragment;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.tools.DeviceDetector;
import com.example.uhf.tools.SessionManager;
import com.example.uhf.tools.ToastUtil;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.rscja.deviceapi.entity.UHFTAGInfo;
import com.rscja.deviceapi.interfaces.ConnectionStatus;

import java.util.ArrayList;
import java.util.HashMap;

public class UHFMainActivity extends BaseTabFragmentActivity {

    private static final String TAG = "MainActivity";
    public ArrayList<UHFTAGInfo> tagList = new ArrayList<>();
    public boolean loopFlag = false;
    public Fragment currentFragment;

    private PlaySoundThread playSoundThread = null;
    private SessionManager sessionManager;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfile;
    private MiraPosFragment activePOSFragment = null;

    private BottomNavigationView bottomNav;
    private boolean isArabic = true;
    private int lastActiveBranchId = -1;
    private String lastFragmentTag = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        super.onCreate(savedInstanceState);

        // تهيئة ToastUtil
        ToastUtil.init(getApplicationContext());

        try {
            isArabic = getResources().getConfiguration().locale.getLanguage().equals("ar");
            getWindow().getDecorView().setLayoutDirection(isArabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        } catch (Exception ignored) {}

        sessionManager = new SessionManager(this);
        if (!sessionManager.isLoggedIn()) {
            navigateToLogin();
            return;
        }

        branchesManager = MiraBranchesManager.getInstance(this);
        orgProfile = MiraOrgProfileManager.getInstance(this);
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

        // Bottom Navigation Setup
        setupBottomNavigation();

        // تسجيل معالج زر العودة
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
                    getSupportFragmentManager().popBackStack();
                    return;
                }
                if (bottomNav != null && bottomNav.getSelectedItemId() != R.id.nav_dashboard) {
                    bottomNav.setSelectedItemId(R.id.nav_dashboard);
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
    }

    // ==================== BOTTOM NAVIGATION ====================

    private void setupBottomNavigation() {
        bottomNav = findViewById(R.id.bottomNavigation);

        // تعكس اتجاه الأيقونات حسب اللغة
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            bottomNav.setLayoutDirection(isArabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        }

        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_dashboard) {
                switchToFragment(MiraDashboardFragment.class);
                return true;
            } else if (id == R.id.nav_jewelry) {
                switchToFragment(JewelryDashboardFragment.class);
                return true;
            } else if (id == R.id.nav_inventory) {
                switchToFragment(MiraUHFReadTagFragment.class);
                return true;
            } else if (id == R.id.nav_pos) {
                switchToFragment(MiraPosFragment.class);
                return true;
            } else if (id == R.id.nav_more) {
                showMoreDialog();
                return false; // لا تحدد التبويب
            }
            return false;
        });

        // تشغيل التبويب الافتراضي
        bottomNav.setSelectedItemId(R.id.nav_dashboard);
    }

    private void switchToFragment(Class<? extends Fragment> fragmentClass) {
        String tag = fragmentClass.getSimpleName();
        if (tag.equals(lastFragmentTag)) return; // تجنب إعادة التحميل

        Fragment fragment = getSupportFragmentManager().findFragmentByTag(tag);
        if (fragment == null) {
            try {
                fragment = fragmentClass.newInstance();
            } catch (Exception e) {
                Log.e(TAG, "Fragment error: " + e.getMessage());
                return;
            }
        }

        currentFragment = fragment;
        lastFragmentTag = tag;

        getSupportFragmentManager().beginTransaction()
                .replace(R.id.container, fragment, tag)
                .commitNow(); // commitNow يضمن التنفيذ الفوري
    }

    private void showMoreDialog() {
        SessionManager.Role role = sessionManager.getUserRole();
        boolean isManager = role == SessionManager.Role.SYSTEM_ADMIN ||
                           role == SessionManager.Role.ORG_ADMIN ||
                           role == SessionManager.Role.BRANCH_MANAGER;
        boolean isInventoryMgr = role == SessionManager.Role.INVENTORY_MGR;
        boolean isAnyAdmin = role == SessionManager.Role.SYSTEM_ADMIN ||
                            role == SessionManager.Role.ORG_ADMIN;

        java.util.List<String> options = new ArrayList<>();
        java.util.List<Class<? extends Fragment>> targets = new ArrayList<>();

        // خيارات مشتركة
        if (isManager || isInventoryMgr) {
            options.add(ar("🆔 التكويد", "🆔 Identity Studio"));
            targets.add(MiraIdentityStudioFragment.class);
        }
        if (isManager || role == SessionManager.Role.GATE_SECURITY) {
            options.add(ar("🚪 البوابات", "🚪 Secure Gate"));
            targets.add(MiraSecureGateFragment.class);
        }
        if (isManager) {
            options.add(ar("📊 التقارير", "📊 Reports"));
            targets.add(MiraReportsProFragment.class);
            options.add(ar("📦 التحويلات", "📦 Transfers"));
            targets.add(MiraTransferFragment.class);
            options.add(ar("🏢 الفروع", "🏢 Branches"));
            targets.add(MiraBranchesManagementFragment.class);
            options.add(ar("👥 المستخدمين", "👥 Users"));
            targets.add(UsersManagementFragment.class);
        }
        if (isManager || isInventoryMgr) {
            options.add(ar("📊 المخزون المتقدم", "📊 Inventory Pro"));
            targets.add(MiraInventoryProFragment.class);
        }
        if (isManager || role == SessionManager.Role.AUDITOR) {
            options.add(ar("💰 المحاسبة", "💰 Accounting"));
            targets.add(MiraAccountingFragment.class);
        }
        if (isManager) {
            options.add(ar("📜 المبيعات", "📜 Sales History"));
            targets.add(MiraSalesHistoryFragment.class);
        }
        if (isAnyAdmin) {
            options.add(ar("⚙️ الإعدادات", "⚙️ Settings"));
            targets.add(UHFSetFragment.class);
        }

        // أزرار إضافية
        options.add(ar("🔀 تغيير اللغة", "🔀 Switch Language"));
        targets.add(null);
        options.add(ar("🔀 تغيير الفرع", "🔀 Switch Branch"));
        targets.add(null);
        options.add(ar("🚪 تسجيل الخروج", "🚪 Logout"));
        targets.add(null);

        String[] items = options.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle(ar("المزيد من الخيارات", "More Options"))
                .setItems(items, (d, w) -> {
                    if (w < targets.size()) {
                        Class<? extends Fragment> cls = targets.get(w);
                        if (cls != null) {
                            switchToFragment(cls);
                        } else {
                            String selected = items[w];
                            if (selected.contains("اللغة") || selected.contains("Language")) {
                                toggleLanguage();
                            } else if (selected.contains("الفرع") || selected.contains("Branch")) {
                                showBranchPicker();
                            } else if (selected.contains("الخروج") || selected.contains("Logout")) {
                                showLogoutConfirmation();
                            }
                        }
                    }
                })
                .show();
    }

    // ==================== READER INTEGRATION ====================

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == 139 || keyCode == 280 || keyCode == 293 || keyCode == 120) {
            if (event.getRepeatCount() == 0) {
                if (currentFragment instanceof MiraUHFReadTagFragment) {
                    ((MiraUHFReadTagFragment) currentFragment).onKeyDownTrigger();
                    return true;
                }
            }
        }
        return super.onKeyDown(keyCode, event);
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
        if (currentFragment instanceof MiraUHFReadTagFragment) {
            ((MiraUHFReadTagFragment) currentFragment).onTagScanned(epc, "—");
        } else if (currentFragment instanceof MiraIdentityStudioFragment) {
            ((MiraIdentityStudioFragment) currentFragment).onRfidScanned(epc);
        } else if (currentFragment instanceof MiraPosFragment) {
            ((MiraPosFragment) currentFragment).onRingScanned(epc);
        }
    }

    // ==================== UI HELPERS ====================

    private void updateToolbarTitle() {
        if (getSupportActionBar() != null) {
            String displayName = sessionManager.getDisplayName();
            if (displayName == null || displayName.isEmpty()) displayName = sessionManager.getUsername();
            getSupportActionBar().setTitle(orgProfile.getOrgName() + " - " + displayName);
            if (branchesManager.hasActiveBranch()) {
                getSupportActionBar().setSubtitle("📍 " + branchesManager.getActiveBranchDisplayName());
            }
        }
    }

    private void toggleLanguage() {
        isArabic = !isArabic;
        getSharedPreferences("app_settings", MODE_PRIVATE).edit().putString("language", isArabic ? "ar" : "en").apply();
        LocaleListCompat appLocale = isArabic ? LocaleListCompat.forLanguageTags("ar") : LocaleListCompat.forLanguageTags("en");
        AppCompatDelegate.setApplicationLocales(appLocale);
        getWindow().getDecorView().setLayoutDirection(isArabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        recreate();
    }

    private void showBranchPicker() {
        if (branchesManager.isBranchLocked() && !sessionManager.isAnyAdmin()) {
            ToastUtil.warning(ar("الفرع محدد من قبل النظام", "Branch is locked by system"));
            return;
        }
        MiraBranchPickerFragment picker = new MiraBranchPickerFragment();
        picker.setOnBranchSelectedListener((branchId, branchName, branchCode) -> {
            branchesManager.setActiveBranch(branchId, branchName, branchCode, "", "");
            lastActiveBranchId = branchId;
            updateToolbarTitle();
            ToastUtil.success(ar("✅ تم التبديل إلى: " + branchName, "✅ Switched to: " + branchName));
        });
        picker.show(getSupportFragmentManager(), "branch_picker");
    }

    private void showLogoutConfirmation() {
        new AlertDialog.Builder(this)
                .setTitle(ar("تسجيل الخروج", "Sign Out"))
                .setMessage(ar("هل أنت متأكد؟", "Are you sure?"))
                .setPositiveButton(ar("تسجيل الخروج", "Sign Out"), (d, w) -> performLogout())
                .setNegativeButton(ar("إلغاء", "Cancel"), null)
                .setIcon(R.drawable.ic_logout)
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

    public void openReceiptFragment(String responseJson) {
        try {
            MiraReceiptFragment receipt = MiraReceiptFragment.newInstance(responseJson);
            receipt.show(getSupportFragmentManager(), "mira_receipt");
        } catch (Exception e) {
            ToastUtil.success(ar("✅ تم البيع بنجاح!", "✅ Sale completed!"));
        }
    }

    private String ar(String arabic, String english) { return isArabic ? arabic : english; }

    @Override
    protected void onResume() {
        super.onResume();
        int currentBranchId = branchesManager.getActiveBranchId();
        if (currentBranchId != lastActiveBranchId) {
            lastActiveBranchId = currentBranchId;
            updateToolbarTitle();
        }
    }

    @Override
    protected void onDestroy() {
        if (playSoundThread != null) playSoundThread.stopPlay();
        releaseSoundPool();
        if (mReader != null) mReader.free();
        super.onDestroy();
    }

    // ==================== SOUND ====================
    // (نفس الكود السابق - لم يتغير)

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
        try { soundPool.play(soundMap.get(id), volumnRatio, volumnRatio, 1, 0, 1); }
        catch (Exception e) { e.printStackTrace(); }
    }

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

    private void checkReadWritePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) { Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION); i.setData(Uri.parse("package:" + getPackageName())); startActivityForResult(i, 0); finish(); }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 2);
        }
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) { getMenuInflater().inflate(R.menu.main, menu); return true; }
    @Override public void onConfigurationChanged(@NonNull Configuration newConfig) { super.onConfigurationChanged(newConfig); invalidateOptionsMenu(); }
    @Override public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_change_language) { toggleLanguage(); return true; }
        if (id == R.id.action_logout) { showLogoutConfirmation(); return true; }
        if (id == R.id.action_profile) { showProfileDialog(); return true; }
        if (id == R.id.action_branches) { showBranchPicker(); return true; }
        if (id == R.id.UHF_ver) { getUHFVersion(); return true; }
        return super.onOptionsItemSelected(item);
    }
    @Override public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem langItem = menu.findItem(R.id.action_change_language);
        if (langItem != null) langItem.setTitle(isArabic ? "English" : "العربية");
        return super.onPrepareOptionsMenu(menu);
    }

    private void showProfileDialog() {
        String msg = (isArabic ? "المستخدم: " : "User: ") + sessionManager.getDisplayName() + "\n" +
                     (isArabic ? "الصلاحية: " : "Role: ") + sessionManager.getRoleString() + "\n" +
                     (isArabic ? "الفرع: " : "Branch: ") + branchesManager.getActiveBranchDisplayName();
        new AlertDialog.Builder(this).setTitle(isArabic ? "ملف المستخدم" : "Profile").setMessage(msg).setPositiveButton(ar("إغلاق", "Close"), null).show();
    }

    public void showToast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
    public void showToast(int resId) { showToast(getString(resId)); }
    @Override public void exportData() { ToastUtil.info("Export"); }
    }
