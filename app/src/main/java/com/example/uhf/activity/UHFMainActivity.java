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
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.example.uhf.R;
import com.example.uhf.fragment.MiraServerConfigFragment;
import com.example.uhf.manager.MiraQuickAccessSettings;
import com.example.uhf.fragment.AuthenticateNxpUcodeDnaFragment;
import com.example.uhf.fragment.MiraSalesHistoryFragment;
import com.example.uhf.fragment.MiraAccountingFragment;
import com.example.uhf.fragment.MiraAdminHubFragment;
import com.example.uhf.fragment.MiraGoldCurrencyFragment;
import com.example.uhf.fragment.MiraDashboardFragment;
import com.example.uhf.fragment.MiraGateSettingsFragment;
import com.example.uhf.fragment.MiraIdentityStudioFragment;
import com.example.uhf.fragment.MiraPosFragment;
import com.example.uhf.fragment.MiraReaderSetupFragment;
import com.example.uhf.fragment.MiraReceiptFragment;
import com.example.uhf.fragment.MiraReportsProFragment;
import com.example.uhf.fragment.MiraSecureGateFragment;
import com.example.uhf.fragment.MiraTransferFragment;
import com.example.uhf.fragment.MiraUHFReadTagFragment;
import com.example.uhf.fragment.MiraUHFRadarLocationFragment;
import com.example.uhf.fragment.UHFLocationFragment;
import com.example.uhf.fragment.UHFSetFragment;
import com.example.uhf.fragment.MiraProfileFragment;
import com.example.uhf.fragment.UsersManagementFragment;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.tools.MiraLanguageHelper;
import com.example.uhf.tools.SessionManager;
import com.example.uhf.tools.ToastUtil;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import com.rscja.deviceapi.entity.UHFTAGInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.example.uhf.fragment.MiraUHFReadWriteFragment;

public class UHFMainActivity extends BaseTabFragmentActivity {

    private static final String TAG = "UHFMainActivity";
    private static final int PERMISSION_REQUEST_CODE = 1001;

    public TabHost mTabHost = null;

    private FrameLayout loadingOverlay;
    private FragmentManager fragmentManager;
    public Fragment currentFragment;
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
    private int lastActiveBranchId = -1;
    
    // ✅ إضافة متغيرات للتعامل مع زر الرجوع
    private long lastBackPressTime = 0;
    private static final long DOUBLE_BACK_TIMEOUT = 2000; // 2 ثانية

    private PlaySoundThread playSoundThread = null;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
            String savedLang = MiraLanguageHelper.getSavedLanguage(this);
            MiraLanguageHelper.applyLanguage(this, savedLang);
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
            fragmentManager = getSupportFragmentManager();
            
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

            safeExecute(this::initSound, "Sound Init Error");
            safeExecute(this::initUHF, "UHF Hardware Init Error");
            safeExecute(this::configureReaderForDevice, "Reader Configuration Error");

            setupQuickNav();

            // العودة للواجهة الرئيسية (Dashboard)
            loadFragment(new MiraDashboardFragment(), "Dashboard");

            showLoading();
            mainHandler.postDelayed(this::hideLoading, 300);

        } catch (Exception e) {
            Log.e(TAG, "Fatal error in onCreate: " + e.getMessage(), e);
            Toast.makeText(this, isArabic ? "حدث خطأ أثناء تحميل الواجهة" : "Initialization Error", Toast.LENGTH_LONG).show();
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

    // ==================== TOOLBAR MENU ====================

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_dashboard) {
            checkInventoryLockAndExecute(() -> loadFragment(new MiraDashboardFragment(), "Dashboard"));
            return true;
        } else if (id == R.id.action_gold_rates) {
            checkInventoryLockAndExecute(() -> loadFragment(new MiraGoldCurrencyFragment(), "GoldRates"));
            return true;
        } else if (id == R.id.action_toggle_language) {
            toggleLanguage();
            return true;
        } else if (id == R.id.action_profile) {
    // ✅ فتح واجهة الملف الشخصي MiraProfileFragment
    checkInventoryLockAndExecute(() -> loadFragment(new MiraProfileFragment(), "Profile"));
    return true;
        } else if (id == R.id.action_logout) {
            confirmLogout();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void toggleLanguage() {
        String newLang = isArabic ? "en" : "ar";
        MiraLanguageHelper.applyLanguage(this, newLang);
        Intent intent = new Intent(this, UHFMainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void showGoldRatesDialog() {
        new AlertDialog.Builder(this)
                .setTitle(isArabic ? "💰 أسعار الذهب والبورصة" : "💰 Gold Rates & Exchange")
                .setMessage(isArabic ? "عرض وتحديث أسعار الذهب المباشرة والعملات."
                        : "View and update live gold and currency exchange rates.")
                .setPositiveButton(isArabic ? "فتح أسعار الذهب" : "Open Gold Rates", (dialog, which) -> {
                    loadFragment(new MiraGoldCurrencyFragment(), "GoldRates");
                })
                .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
                .show();
    }

    private void showProfileInfoDialog() {
        String name = sessionManager.getDisplayName();
        String user = sessionManager.getUsername();
        String role = sessionManager.getUserRole() != null ? sessionManager.getUserRole().name() : "";

        new AlertDialog.Builder(this)
                .setTitle(isArabic ? "👤 الملف الشخصي" : "👤 User Profile")
                .setMessage((isArabic ? "الاسم: " : "Name: ") + name + "\n" +
                        (isArabic ? "اسم المستخدم: " : "Username: ") + user + "\n" +
                        (isArabic ? "الصلاحية: " : "Role: ") + role)
                .setPositiveButton(isArabic ? "موافق" : "OK", null)
                .show();
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle(isArabic ? "تسجيل الخروج" : "Logout")
                .setMessage(isArabic ? "هل أنت تأكد من تسجيل الخروج من المنظومة؟" : "Are you sure you want to log out?")
                .setPositiveButton(isArabic ? "تأكيد" : "Logout", (dialog, which) -> {
                    sessionManager.logout();
                    navigateToLogin();
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
    }

    // ==================== NAV SYSTEM & FRAGMENT MANAGEMENT ====================

   private void setupQuickNav() {
    try {
        BottomNavigationView quickNav = findViewById(R.id.bottomNavigationView);
        FloatingActionButton fabActionCenter = findViewById(R.id.fabActionCenter);

        if (fabActionCenter != null) {
            fabActionCenter.setOnClickListener(v -> showQuickActionMenu());
        }

        if (quickNav != null) {
            quickNav.setOnItemSelectedListener(item -> {
                int id = item.getItemId();

                if (id == R.id.nav_home) {
                    checkInventoryLockAndExecute(() -> loadFragment(new MiraDashboardFragment(), "Dashboard"));
                    return true;
                } else if (id == R.id.nav_inventory) {
                    checkInventoryLockAndExecute(() -> loadFragment(new MiraUHFReadTagFragment(), "Scan"));
                    return true;
                } else if (id == R.id.nav_sales) {
                    // ✅ البيع الجديد POS
                    checkInventoryLockAndExecute(() -> loadFragment(new MiraPosFragment(), "POS"));
                    return true;
                } else if (id == R.id.nav_transfers) {
                    // ✅ التحويلات
                    checkInventoryLockAndExecute(() -> loadFragment(new MiraTransferFragment(), "Transfers"));
                    return true;
                } else if (id == R.id.nav_placeholder) {
                    showQuickActionMenu();
                    return false;
                }
                return false;
            });
        }
    } catch (Exception e) {
        Log.e(TAG, "QuickNav Setup Error: " + e.getMessage());
    }
}

  private void showQuickActionMenu() {
    try {
        BottomSheetDialog dialog = new BottomSheetDialog(this, R.style.TransparentBottomSheetDialog);
        View view = getLayoutInflater().inflate(R.layout.dialog_quick_action_grid, null);
        dialog.setContentView(view);

        // ✅ استخدام إعدادات الوصول السريع
        MiraQuickAccessSettings quickAccess = MiraQuickAccessSettings.getInstance(this);

        // ✅ إضافة زر برمجة RFID
        View btnRfidWrite = view.findViewById(R.id.btnRfidWrite);
        if (btnRfidWrite != null) {
            btnRfidWrite.setVisibility(View.VISIBLE);
            btnRfidWrite.setOnClickListener(v -> {
                dialog.dismiss();
                checkInventoryLockAndExecute(() -> loadFragment(new MiraUHFReadWriteFragment(), "RFIDWrite"));
            });
        } // ✅ القوس هنا

        // 1. استوديو التكويد
        if (quickAccess.isActionEnabled("identity")) {
            bindMenuAction(view, R.id.btnIdentityStudio, dialog, new MiraIdentityStudioFragment(), "IdentityStudio");
        } else {
            view.findViewById(R.id.btnIdentityStudio).setVisibility(View.GONE);
        }

        // 2. الرادار
        if (quickAccess.isActionEnabled("radar")) {
            bindMenuAction(view, R.id.btnRadarLocation, dialog, new MiraUHFRadarLocationFragment(), "Radar");
        } else {
            view.findViewById(R.id.btnRadarLocation).setVisibility(View.GONE);
        }

        // 3. البوابة الأمنية
        if (quickAccess.isActionEnabled("gate")) {
            bindMenuAction(view, R.id.btnSecureGate, dialog, new MiraSecureGateFragment(), "SecureGate");
        } else {
            view.findViewById(R.id.btnSecureGate).setVisibility(View.GONE);
        }

        // 4. التقارير
        if (quickAccess.isActionEnabled("reports")) {
            bindMenuAction(view, R.id.btnReports, dialog, new MiraReportsProFragment(), "Reports");
        } else {
            view.findViewById(R.id.btnReports).setVisibility(View.GONE);
        }

        // 5. المحاسبة
        if (quickAccess.isActionEnabled("accounting")) {
            bindMenuAction(view, R.id.btnAccounting, dialog, new MiraAccountingFragment(), "Accounting");
        } else {
            view.findViewById(R.id.btnAccounting).setVisibility(View.GONE);
        }

        // 6. التحويلات
        if (quickAccess.isActionEnabled("transfers")) {
            bindMenuAction(view, R.id.btnTransfer, dialog, new MiraTransferFragment(), "Transfers");
        } else {
            view.findViewById(R.id.btnTransfer).setVisibility(View.GONE);
        }

        // 7. سجل المبيعات
        if (quickAccess.isActionEnabled("sales_history")) {
            bindMenuAction(view, R.id.btnSalesHistory, dialog, new MiraSalesHistoryFragment(), "SalesHistory");
        } else {
            view.findViewById(R.id.btnSalesHistory).setVisibility(View.GONE);
        }

        // 8. الخيارات الإدارية
        if (quickAccess.isActionEnabled("admin_hub")) {
            View btnAdminHub = view.findViewById(R.id.btnAdminHub);
            if (btnAdminHub != null) {
                btnAdminHub.setVisibility(View.VISIBLE);
                btnAdminHub.setOnClickListener(v -> {
                    dialog.dismiss();
                    checkInventoryLockAndExecute(() -> loadFragment(new MiraAdminHubFragment(), "AdminHub"));
                });
            }
        } else {
            View btnAdminHub = view.findViewById(R.id.btnAdminHub);
            if (btnAdminHub != null) btnAdminHub.setVisibility(View.GONE);
        }

        // 9. إعدادات السيرفر
        if (quickAccess.isActionEnabled("server_config")) {
            View btnServerConfig = view.findViewById(R.id.btnServerConfig);
            if (btnServerConfig != null) {
                btnServerConfig.setVisibility(View.VISIBLE);
                btnServerConfig.setOnClickListener(v -> {
                    dialog.dismiss();
                    loadFragment(new MiraServerConfigFragment(), "ServerConfig");
                });
            }
        } else {
            View btnServerConfig = view.findViewById(R.id.btnServerConfig);
            if (btnServerConfig != null) btnServerConfig.setVisibility(View.GONE);
        }

        // زر الإغلاق
        View fabCloseSheet = view.findViewById(R.id.fabCloseSheet);
        if (fabCloseSheet != null) {
            fabCloseSheet.setOnClickListener(v -> dialog.dismiss());
        }

        dialog.show();
    } catch (Exception e) {
        Log.e(TAG, "QuickActionMenu Error: " + e.getMessage(), e);
    }
}

    public void loadFragment(Fragment fragment, String tag) {
        try {
            if (fragment == null) return;
            showLoading();
            currentFragment = fragment;
            fragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, fragment, tag)
                    .commitAllowingStateLoss();

            mainHandler.postDelayed(this::hideLoading, 200);
        } catch (Exception e) {
            Log.e(TAG, "Error switching fragment: " + e.getMessage());
            hideLoading();
        }
    }

    private void bindMenuAction(View root, int viewId, BottomSheetDialog dialog, Fragment fragment, String tag) {
        if (root == null || viewId == 0) return;
        View btn = root.findViewById(viewId);
        if (btn != null) {
            btn.setVisibility(View.VISIBLE);
            btn.setOnClickListener(v -> {
                dialog.dismiss();
                checkInventoryLockAndExecute(() -> loadFragment(fragment, tag));
            });
        }
    }

    private void showHardwareOptionsDialog() {
    String[] options = isArabic ? 
        new String[]{"إعدادات قارئ RFID", "تهيئة البوابات الأمنية", "🌐 إعدادات السيرفر", "اختبار التوصيل"} : 
        new String[]{"RFID Reader Setup", "Gate Security Setup", "🌐 Server Config", "Connection Test"};

    new AlertDialog.Builder(this)
        .setTitle(isArabic ? "📡 أجهزة ومستشعرات المنظومة" : "📡 Hardware & Sensors")
        .setItems(options, (dialog, which) -> {
            if (which == 0) loadFragment(new MiraReaderSetupFragment(), "ReaderSetup");
            else if (which == 1) loadFragment(new MiraGateSettingsFragment(), "GateSettings");
            else if (which == 2) loadFragment(new MiraServerConfigFragment(), "ServerConfig"); // ✅
            else Toast.makeText(this, "الأجهزة متصلة", Toast.LENGTH_SHORT).show();
        })
        .show();
}

    // ==================== LEGACY COMPATIBILITY METHODS ====================

    public void startUHFScan() {
        try {
            loopFlag = true;
            if (mReader != null) {
                mReader.startInventoryTag();
            }
        } catch (Exception e) {
            Log.e(TAG, "startUHFScan Error: " + e.getMessage());
        }
    }

    public void stopUHFScan() {
        try {
            loopFlag = false;
            if (mReader != null) {
                mReader.stopInventory();
            }
        } catch (Exception e) {
            Log.e(TAG, "stopUHFScan Error: " + e.getMessage());
        }
    }

    public void openReceiptFragment(String rawJson) {
        try {
            MiraReceiptFragment fragment = MiraReceiptFragment.newInstance(rawJson);
            // ✅ استخدام show بدلاً من loadFragment للسماح بالعودة الصحيحة
            fragment.show(fragmentManager, "Receipt");
        } catch (Exception e) {
            Log.e(TAG, "openReceiptFragment Error: " + e.getMessage());
        }
    }

    public void showToast(String text) {
        if (text == null) return;
        mainHandler.post(() -> Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT).show());
    }

    public void showToast(int resId) {
        mainHandler.post(() -> Toast.makeText(getApplicationContext(), getString(resId), Toast.LENGTH_SHORT).show());
    }

    public void playSoundDelayed(int val) {
        mainHandler.postDelayed(() -> {
            try {
                playSound(val);
            } catch (Exception e) {
                Log.e(TAG, "Error playing delayed sound: " + e.getMessage());
            }
        }, 10);
    }

    // ==================== INVENTORY LOCK PROTECTION ====================

    public boolean isInventoryActive() {
        if (loopFlag) return true;
        if (currentFragment instanceof MiraUHFReadTagFragment && currentFragment.isVisible()) {
            return ((MiraUHFReadTagFragment) currentFragment).isScanning();
        }
        return false;
    }

    private void checkInventoryLockAndExecute(Runnable onConfirmed) {
        if (isInventoryActive()) {
            new AlertDialog.Builder(this)
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
            stopUHFScan();
            if (currentFragment instanceof MiraUHFReadTagFragment) {
                ((MiraUHFReadTagFragment) currentFragment).stopScanning();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping inventory: " + e.getMessage());
        }
    }

    // ==================== ✅ BACK BUTTON HANDLING ====================

    @Override
    public void onBackPressed() {
        // 1️⃣ إذا كان الجرد نشط، اطلب التأكيد أولاً
        if (isInventoryActive()) {
            checkInventoryLockAndExecute(() -> handleBackNavigation());
            return;
        }
        
        handleBackNavigation();
    }

    /**
     * ✅ التعامل مع زر الرجوع بشكل صحيح
     */
    private void handleBackNavigation() {
        // 2️⃣ إغلاق أي Dialog مفتوح
        for (Fragment fragment : fragmentManager.getFragments()) {
            if (fragment instanceof androidx.fragment.app.DialogFragment) {
                androidx.fragment.app.DialogFragment dialogFragment = 
                    (androidx.fragment.app.DialogFragment) fragment;
                if (dialogFragment.getDialog() != null && dialogFragment.getDialog().isShowing()) {
                    dialogFragment.dismissAllowingStateLoss();
                    return;
                }
            }
        }
        
        // 3️⃣ إذا كان هناك Back Stack، الرجوع إليه
        if (fragmentManager.getBackStackEntryCount() > 0) {
            fragmentManager.popBackStack();
            return;
        }
        
        // 4️⃣ إذا كنا في شاشة غير Dashboard، الرجوع إلى Dashboard
        if (currentFragment != null && !(currentFragment instanceof MiraDashboardFragment)) {
            loadFragment(new MiraDashboardFragment(), "Dashboard");
            return;
        }
        
        // 5️⃣ في الشاشة الرئيسية - الضغط المزدوج للخروج
        handleDoubleBackToExit();
    }

    /**
     * ✅ التعامل مع الضغط المزدوج للخروج من التطبيق
     */
    private void handleDoubleBackToExit() {
        long currentTime = System.currentTimeMillis();
        
        if (currentTime - lastBackPressTime < DOUBLE_BACK_TIMEOUT) {
            // ضغطة ثانية خلال 2 ثانية - الخروج من التطبيق
            finish();
            return;
        }
        
        // الضغطة الأولى - عرض رسالة
        lastBackPressTime = currentTime;
        Toast.makeText(this, 
            isArabic ? "اضغط مرة أخرى للخروج" : "Press again to exit", 
            Toast.LENGTH_SHORT).show();
    }

    /**
     * ✅ معالجة أزرار الأجهزة الفيزيائية
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onBackPressed();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ==================== RFID & HARDWARE LISTENERS ====================

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

    // ==================== SYSTEM & TOOLBAR HELPERS ====================

    private void updateToolbarTitle() {
        try {
            if (getSupportActionBar() != null && sessionManager != null) {
                String displayName = sessionManager.getDisplayName();
                if (displayName == null || displayName.trim().isEmpty()) displayName = sessionManager.getUsername();
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

    public void showLoading() {
        if (loadingOverlay != null && !isFinishing() && !isDestroyed()) {
            TextView tvTitle = loadingOverlay.findViewById(R.id.tvLoadingTitle);
            if (tvTitle != null) tvTitle.setText(isArabic ? "جاري التحميل..." : "Loading...");
            loadingOverlay.setVisibility(View.VISIBLE);
        }
    }

    public void hideLoading() {
        if (loadingOverlay != null && !isFinishing() && !isDestroyed()) {
            loadingOverlay.setVisibility(View.GONE);
        }
    }

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

    private void initSound() {
        soundPool = new SoundPool(10, AudioManager.STREAM_MUSIC, 5);
        soundMap.put(1, soundPool.load(this, R.raw.barcodebeep, 1));
        soundMap.put(2, soundPool.load(this, R.raw.alarm_breach_security, 2));
        audioManager = (AudioManager) this.getSystemService(AUDIO_SERVICE);
        playSoundThread = new PlaySoundThread();
        playSoundThread.start();
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
                    ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), PERMISSION_REQUEST_CODE);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Permission Error: " + e.getMessage());
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
            stopActiveInventory();
            mainHandler.removeCallbacksAndMessages(null);
            if (playSoundThread != null) playSoundThread.stopPlay();
            if (soundPool != null) soundPool.release();
            if (mReader != null) mReader.free();
        } catch (Exception e) {
            Log.e(TAG, "onDestroy cleanup error: " + e.getMessage());
        }
        super.onDestroy();
    }

    private void safeExecute(Runnable runnable, String errorMessage) {
        try {
            runnable.run();
        } catch (Exception e) {
            Log.e(TAG, errorMessage + ": " + e.getMessage());
        }
    }

    private class PlaySoundThread extends Thread {
        private volatile boolean isStop = false;
        private int interval = 500;
        private long lastPlayTime = SystemClock.elapsedRealtime();

        @Override
        public void run() {
            while (!isStop) {
                synchronized (this) {
                    try {
                        wait(interval);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                if (!isStop && SystemClock.elapsedRealtime() - lastPlayTime < 500) {
                    playSound(1);
                }
            }
        }

        public void stopPlay() {
            isStop = true;
            synchronized (this) {
                notifyAll();
            }
        }
    }
                }
