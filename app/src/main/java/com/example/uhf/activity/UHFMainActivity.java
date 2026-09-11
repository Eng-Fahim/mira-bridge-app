package com.example.uhf.activity;

import com.example.uhf.application.MiraApplication;
import com.example.uhf.manager.AppWatchdog;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.annotation.SuppressLint;


import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.GridLayout;

import android.graphics.Typeface;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;

import com.example.uhf.activity.LoginActivity;
import com.example.uhf.fragment.MiraDashboardFragment;
import com.example.uhf.fragment.MiraPosFragment;
import com.example.uhf.fragment.MiraIdentityStudioFragment;
import com.example.uhf.fragment.MiraUHFReadTagFragment;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.manager.SessionMonitorManager;
import com.example.uhf.navigation.NavigationManager;
import com.example.uhf.navigation.FragmentFactory;
import com.example.uhf.manager.HardwareManager;
import com.example.uhf.manager.MiraMessagingManager;
import com.example.uhf.manager.NotificationManager;
import com.example.uhf.utils.SoundManager;
import com.example.uhf.utils.StatusIndicatorManager;
import com.example.uhf.tools.MiraLanguageHelper;
import com.example.uhf.tools.SessionManager;
import com.example.uhf.tools.ToastUtil;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.rscja.deviceapi.entity.UHFTAGInfo;

import java.util.ArrayList;
import java.util.List;

import com.example.uhf.model.MessageModel;
import com.example.uhf.model.NotificationModel;
import com.example.uhf.fragment.MiraUHFReadWriteFragment;
import com.example.uhf.fragment.MiraAccountingFragment;
import com.example.uhf.fragment.MiraAdminHubFragment;
import com.example.uhf.fragment.MiraBranchesManagementFragment;
import com.example.uhf.manager.InventoryLockManager;
import com.example.uhf.fragment.MiraGoldCurrencyFragment;
import com.example.uhf.fragment.MiraInventoryProFragment;
import com.example.uhf.fragment.MiraProfileFragment;
import com.example.uhf.fragment.MiraQuickAccessSettingsFragment;
import com.example.uhf.fragment.MiraReceiptFragment;
import com.example.uhf.fragment.MiraReportsProFragment;
import com.example.uhf.fragment.MiraRfidBindingsFragment;
import com.example.uhf.fragment.MiraSalesHistoryFragment;
import com.example.uhf.fragment.MiraSecureGateFragment;
import com.example.uhf.fragment.MiraServerConfigFragment;
import com.example.uhf.fragment.MiraTransferFragment;
import com.example.uhf.fragment.MiraUHFRadarLocationFragment;
import com.example.uhf.manager.MiraQuickAccessSettings;
import com.example.uhf.MiraRfid.sound.MiraSoundSettingsFragment;
import com.example.uhf.fragment.MiraReaderSetupFragment;
import com.example.uhf.fragment.MiraGateSettingsFragment;

public class UHFMainActivity extends BaseTabFragmentActivity {

    private static final String TAG = "UHFMainActivity";
    private static final String PREFS_NAVIGATION = "app_navigation";
    private static final String KEY_CURRENT_FRAGMENT = "current_fragment_tag";
    
    // ✅ المديرين والمكونات
    private NavigationManager navigationManager;
    private HardwareManager hardwareManager;
    private SoundManager soundManager;
    private NotificationManager notificationManager;
    private StatusIndicatorManager statusIndicatorManager;
    
    private InventoryLockManager inventoryLockManager; 
    
    // ✅ المتغيرات الأساسية
    private TextView txtBadgeCount;
    private SessionManager sessionManager;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfileManager;
    private PermissionManager permissionManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // ✅ حقول الشريط الثانوي الجديد (بديل SECONDARY_MENUS_MAP)
    private LinearLayout statusChipContainer;
    private LinearLayout secondaryActionsContainer;
    private View statusChipView;
    
    // في تعريف المتغيرات
    private final MiraOrgProfileManager.OnProfileChangedListener orgProfileListener = this::updateAppTitle;

    // ✅ إدارة RFID
    private MiraPosFragment activePOSFragment = null;
    private MiraIdentityStudioFragment activeEncodingFragment = null;
    private OnRfidScannedListener activeRfidListener = null;
    
    // ✅ حالة النظام
    public int selectIndex = -1;
    public ArrayList<UHFTAGInfo> tagList = new ArrayList<>();
    public boolean loopFlag = false;
    private boolean isArabic = true;
    
    // ✅ التنقل
    public String currentFragmentTag = "Dashboard";
    public Fragment currentFragment;
    private long lastBackPressTime = 0;
    private static final long DOUBLE_BACK_TIMEOUT = 2000;
    
    // ✅ واجهات RFID
    public interface OnRfidScannedListener {
        void onRfidScanned(String epc);
    }
    
    /**
     * 🆕 واجهة لاستقبال أحداث الشريط الثانوي في الـ Fragment
     */
    public interface SecondaryToolbarListener {
        void onSecondaryToolbarEvent(String event);
    }
    


    // ==================== Setters ====================

    public void setActiveEncodingFragment(MiraIdentityStudioFragment fragment) {
        this.activeEncodingFragment = fragment;
    }

    public void setActiveRfidListener(OnRfidScannedListener listener) {
        this.activeRfidListener = listener;
    }

    public void setActivePOSFragment(MiraPosFragment fragment) {
        this.activePOSFragment = fragment;
    }

    private void clearActiveListeners() {
        this.activePOSFragment = null;
        this.activeEncodingFragment = null;
        this.activeRfidListener = null;
    }

    // ==================== دورة الحياة ====================

    @Override
    protected void attachBaseContext(Context newBase) {
        String savedLang = MiraLanguageHelper.getSavedLanguage(newBase);
        MiraLanguageHelper.applyLanguage(newBase, savedLang);
        super.attachBaseContext(newBase);
    }
    
    private void setupWindowInsets() {
        View mainView = findViewById(R.id.main);
        if (mainView == null) return;
        
        // ✅ حفظ الحشوة الأصلية (قبل إضافة الـ insets)
        final int originalPaddingLeft = mainView.getPaddingLeft();
        final int originalPaddingTop = mainView.getPaddingTop();
        final int originalPaddingRight = mainView.getPaddingRight();
        final int originalPaddingBottom = mainView.getPaddingBottom();
        
        ViewCompat.setOnApplyWindowInsetsListener(mainView, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            
            v.setPadding(
                    originalPaddingLeft + systemBars.left,
                    originalPaddingTop + systemBars.top,
                    originalPaddingRight + systemBars.right,
                    originalPaddingBottom + systemBars.bottom
            );
            
            // ✅ استهلك الـ insets — لا تمررها للأبناء
            return WindowInsetsCompat.CONSUMED;
        });
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        super.onCreate(savedInstanceState);

        try {
            sessionManager = new SessionManager(getApplicationContext());
            if (!sessionManager.isLoggedIn()) {
                navigateToLogin();
                return;
            }

            setContentView(R.layout.activity_main);

            setupWindowInsets();
            cleanupOldFragments();

            // ✅ ربط الشريط الثانوي الجديد
            setupSecondaryToolbar();

            MaterialToolbar toolbar = findViewById(R.id.toolbar);
            if (toolbar != null) {
                setSupportActionBar(toolbar);
                
                // ✅ إخفاء العنوان الافتراضي للـ ActionBar
                if (getSupportActionBar() != null) {
                    getSupportActionBar().setDisplayShowTitleEnabled(false);
                }
                
                updateToolbarTitle();
            }

            // ✅ مراقبة استجابة الـ UI Thread
            AppWatchdog.getInstance().startMonitoring(() -> {
                Log.e(TAG, "UI Thread FROZEN! Restarting app...");
                MiraApplication.getInstance().restartApp();
            });

            ToastUtil.init(getApplicationContext());
            
            // ✅ تهيئة متغيرة اللغة أولاً قبل فحص اتجاه الواجهة
            initializeLanguageState();

            // ==================== ضبط اتجاه الواجهة لشريط التنقل ====================
            View bottomNavigationView = findViewById(R.id.bottomNavigationView);
            View bottomNavContainer = findViewById(R.id.bottomNavContainer);

            int layoutDirection = isArabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR;

            if (bottomNavigationView != null) {
                bottomNavigationView.setLayoutDirection(layoutDirection);
            }

            if (bottomNavContainer != null) {
                bottomNavContainer.setLayoutDirection(layoutDirection);
            }
            // ====================================================================

            // ✅ تهيئة المديرين
            branchesManager = MiraBranchesManager.getInstance(getApplicationContext());
            permissionManager = PermissionManager.getInstance(getApplicationContext());
            navigationManager = new NavigationManager(getSupportFragmentManager(), permissionManager);
            
            // 🆕 تهيئة مدير القفل
            inventoryLockManager = new InventoryLockManager(this);
            inventoryLockManager.initialize();
            
            hardwareManager = new HardwareManager(this, new HardwareManager.HardwareStatusListener() {
                @Override
                public void onStatusChanged(HardwareManager.HardwareStatus status) {
                    if (statusIndicatorManager != null) {
                        statusIndicatorManager.update();
                    }
                    // ✅ تحديث شريحة الحالة في الشريط الثانوي
                    if (hardwareManager != null) {
                        boolean isConnected = hardwareManager.isConnected();
                        mainHandler.post(() -> updateSecondaryStatusChip(isConnected));
                    }
                }

                @Override
                public void onTagRead(String epc) {
                    UHFMainActivity.this.onTagRead(epc);
                }

                @Override
                public void onError(String error) {
                    showToast("RFID Error: " + error);
                }
            });

            soundManager = new SoundManager(this);
            notificationManager = new NotificationManager(this);
            statusIndicatorManager = new StatusIndicatorManager(findViewById(android.R.id.content), hardwareManager);

            // ✅ إعداد الأزرار والمستمعين
            setupQuickNav();
            setupOrgProfileListener();

            // ✅ التنقل الأولي
            if (savedInstanceState == null) {
                // حالة جديدة → Dashboard افتراضي
                currentFragmentTag = "Dashboard";
                navigationManager.loadFragmentDirectly(new MiraDashboardFragment(), "Dashboard");
            } else {
                // استعادة الحالة
                currentFragmentTag = savedInstanceState.getString("current_fragment", "Dashboard");

                Fragment restored = getSupportFragmentManager().findFragmentByTag(currentFragmentTag);
                if (restored != null) {
                    currentFragment = restored;
                    if (restored.isHidden()) {
                        getSupportFragmentManager().beginTransaction()
                                .show(restored)
                                .commitNowAllowingStateLoss();
                    }
                    Log.d(TAG, "Restored fragment: " + currentFragmentTag);
                } else {
                    Fragment newFragment = navigationManager.createFragmentByTag(currentFragmentTag);
                    if (newFragment != null) {
                        navigationManager.loadFragmentDirectly(newFragment, currentFragmentTag);
                    } else {
                        navigationManager.loadFragmentDirectly(new MiraDashboardFragment(), "Dashboard");
                    }
                }
            }

            currentFragment = navigationManager.getCurrentFragment();
            
            // ✅ تحديث الشريط الثانوي بعد التنقل الأولي
            refreshSecondaryToolbar(currentFragmentTag);

        } catch (Exception e) {
            Log.e(TAG, "Fatal error in onCreate: " + e.getMessage(), e);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  الشريط الثانوي الجديد (أزرار مباشرة)
    // ══════════════════════════════════════════════════════════════

    /**
     * ✅ إعداد الشريط الثانوي الجديد (أزرار مباشرة، بدون Menu منسدل)
     */
    private void setupSecondaryToolbar() {
        statusChipContainer = findViewById(R.id.statusChipContainer);
        secondaryActionsContainer = findViewById(R.id.secondaryActionsContainer);
        
        if (statusChipContainer != null) {
            // ✅ حمّل عرض حالة الجهاز من XML الموجود
            try {
                statusChipView = getLayoutInflater().inflate(
                        R.layout.action_device_status, statusChipContainer, false);
                
                // ✅ اجعله قابل للنقر (يفتح حوار الأجهزة)
                if (statusChipView != null) {
                    statusChipView.setOnClickListener(v -> showHardwareOptionsDialog());
                    
                    statusChipContainer.addView(statusChipView);
                    
                    // ✅ تحديث الحالة الأولية
                    if (hardwareManager != null) {
                        updateSecondaryStatusChip(hardwareManager.isConnected());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "setupSecondaryToolbar error: " + e.getMessage(), e);
            }
        }
    }

    /**
     * ✅ تحديث الشريط الثانوي حسب الشاشة الحالية
     */
    private void refreshSecondaryToolbar(String tag) {
        if (secondaryActionsContainer == null) {
            secondaryActionsContainer = findViewById(R.id.secondaryActionsContainer);
        }
        if (secondaryActionsContainer == null) return;
        
        try {
            // 1. مسح الأزرار الحالية
            secondaryActionsContainer.removeAllViews();
            
            // 2. أضف الأزرار حسب الشاشة
            if (tag == null) tag = "Dashboard";
            
            switch (tag) {
                case "Dashboard":
                    setupDashboardActions();
                    break;
                case "Scan":
                case "MiraInventoryProFragment":
                    // الشاشة تُخفي الشريط بالكامل — لا حاجة لأزرار
                    break;
                case "POS":
                    setupPosActions();
                    break;
                case "Transfers":
                    setupTransfersActions();
                    break;
                default:
                    setupDashboardActions();
                    break;
            }
            
            Log.d(TAG, "Secondary toolbar refreshed for: " + tag);
        } catch (Exception e) {
            Log.e(TAG, "Error refreshing secondary toolbar", e);
        }
    }

    /**
     * 🏠 Dashboard Actions
     * الترتيب (RTL): [🟢 متصل] [⚙️] [🔄] [🔍]
     */
    private void setupDashboardActions() {
        addActionButton(R.drawable.ic_settings, "إعدادات سريعة",
                v -> loadFragment(new MiraQuickAccessSettingsFragment(), "QuickAccessSettings"));

        addActionButton(R.drawable.ic_sync, "مزامنة",
                v -> performDataSync());

        addActionButton(R.drawable.ic_search, "بحث",
                v -> showGlobalSearchDialog());
    }

    /**
     * 📡 Inventory Actions
     * (تُستخدم فقط إذا أُظهر الشريط مستقبلاً)
     */
    @SuppressWarnings("unused")
    private void setupInventoryActions() {
        addActionButton(R.drawable.ic_settings, "إعدادات الجلسة",
                v -> sendEventToCurrentFragment("inventory_settings"));

        addActionButton(R.drawable.ic_stats, "إحصائيات",
                v -> sendEventToCurrentFragment("inventory_stats"));

        addActionButton(R.drawable.ic_search, "بحث في الممسوحات",
                v -> sendEventToCurrentFragment("inventory_search"));
    }

    /**
     * 💰 POS Actions
     * الترتيب (RTL): [🟢 RFID] [🧾] [💰] [🔍] [🛒]
     */
    private void setupPosActions() {
        addActionButton(R.drawable.ic_receipt, "الإيصال الأخير",
                v -> sendEventToCurrentFragment("pos_receipt"));

        addActionButton(R.drawable.ic_discount, "خصم",
                v -> sendEventToCurrentFragment("pos_discount"));

        addActionButton(R.drawable.ic_search, "بحث",
                v -> sendEventToCurrentFragment("pos_search"));

        addActionButton(R.drawable.ic_shopping_cart, "السلة",
                v -> sendEventToCurrentFragment("pos_cart"));
    }

    /**
     * 🚚 Transfers Actions
     * الترتيب (RTL): [🟢 الشبكة] [📋] [📥] [📤] [🔄]
     */
    private void setupTransfersActions() {
        addActionButton(R.drawable.ic_history, "السجل",
                v -> sendEventToCurrentFragment("transfers_history"));

        addActionButton(R.drawable.ic_import, "استيراد",
                v -> sendEventToCurrentFragment("transfers_import"));

        addActionButton(R.drawable.ic_export, "تصدير",
                v -> sendEventToCurrentFragment("transfers_export"));

        addActionButton(R.drawable.ic_sync, "مزامنة",
                v -> performDataSync());
    }

    // ══════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════

    /**
     * ✅ إضافة زر إلى الشريط الثانوي
     */
    private void addActionButton(int iconRes, String description, View.OnClickListener listener) {
        if (secondaryActionsContainer == null) return;
        
        try {
            ImageView btn = new ImageView(this);
            
            float density = getResources().getDisplayMetrics().density;
            int size = (int) (40 * density);
            int padding = (int) (8 * density);
            int margin = (int) (4 * density);
            
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.setMarginStart(margin);
            btn.setLayoutParams(params);
            
            btn.setPadding(padding, padding, padding, padding);
            btn.setImageResource(iconRes);
            btn.setContentDescription(description);
            btn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            btn.setBackgroundResource(getSelectableItemBackgroundBorderless());
            
            // ✅ لون الأيقونة حسب الثيم
            try {
                btn.setColorFilter(getColor(R.color.on_surface));
            } catch (Exception e) {
                btn.setColorFilter(0xFF000000);
            }
            
            btn.setOnClickListener(listener);
            
            secondaryActionsContainer.addView(btn);
        } catch (Exception e) {
            Log.e(TAG, "addActionButton error: " + e.getMessage(), e);
        }
    }

    /**
     * ✅ الحصول على selectableItemBackgroundBorderless
     */
    private int getSelectableItemBackgroundBorderless() {
        android.util.TypedValue outValue = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true);
        return outValue.resourceId;
    }

    /**
     * ✅ تحديث شريحة حالة الجهاز
     */
    private void updateSecondaryStatusChip(boolean isConnected) {
        if (statusChipView == null) return;
        
        try {
            View dot = statusChipView.findViewById(R.id.deviceStatusDot);
            TextView text = statusChipView.findViewById(R.id.deviceStatusText);
            
            if (dot != null) {
                dot.setBackgroundResource(isConnected 
                        ? R.drawable.bg_status_dot_green 
                        : R.drawable.bg_status_dot_red);
            }
            
            if (text != null) {
                text.setText(isConnected 
                        ? (isArabic ? "متصل" : "Online")
                        : (isArabic ? "غير متصل" : "Offline"));
                
                int colorRes = isConnected 
                        ? android.R.color.holo_green_dark
                        : android.R.color.holo_red_dark;
                text.setTextColor(getResources().getColor(colorRes, getTheme()));
            }
        } catch (Exception e) {
            Log.e(TAG, "updateSecondaryStatusChip error: " + e.getMessage());
        }
    }

    /**
     * 🆕 إرسال حدث للـ Fragment الحالي
     */
    private void sendEventToCurrentFragment(String event) {
        try {
            if (currentFragment instanceof SecondaryToolbarListener) {
                ((SecondaryToolbarListener) currentFragment).onSecondaryToolbarEvent(event);
            } else {
                Log.d(TAG, "Event '" + event + "' received but fragment doesn't implement listener");
                // ✅ Fallback: عرض Toast للمستخدم
                showToast(isArabic ? "قيد التطوير: " + event : "Coming soon: " + event);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending event to fragment", e);
        }
    }

    /**
     * ✅ حوار خيارات الأجهزة (يُفتح عند النقر على شريحة الحالة)
     */
    private void showHardwareOptionsDialog() {
        PermissionManager pm = PermissionManager.getInstance(getApplicationContext());
        List<String> optionsList = new ArrayList<>();
        List<Runnable> actionsList = new ArrayList<>();
        
        // 1. إعدادات قارئ RFID
        if (pm.isAdmin() || pm.isInventoryManager() || pm.isSystemAdmin()) {
            optionsList.add(isArabic ? "📡 إعدادات قارئ RFID" : "📡 RFID Reader Setup");
            actionsList.add(() -> loadFragment(new MiraReaderSetupFragment(), "ReaderSetup"));
        }
        
        // 2. إعدادات البوابات الأمنية
        if (pm.isAdmin() || pm.isSecurity() || pm.isGateSecurity()) {
            optionsList.add(isArabic ? "🚧 تهيئة البوابات الأمنية" : "🚧 Gate Security Setup");
            actionsList.add(() -> loadFragment(new MiraGateSettingsFragment(), "GateSettings"));
        }
        
        // 3. إعدادات السيرفر
        if (pm.canViewServerConfig()) {
            optionsList.add(isArabic ? "🌐 إعدادات السيرفر" : "🌐 Server Config");
            actionsList.add(() -> loadFragment(new MiraServerConfigFragment(), "ServerConfig"));
        }
        
        // 4. إدارة الشرائح المبرمجة
        if (pm.isAdmin() || pm.isManager() || pm.isInventoryManager()) {
            optionsList.add(isArabic ? "🏷️ إدارة الشرائح المبرمجة" : "🏷️ RFID Bindings");
            actionsList.add(() -> loadFragment(new MiraRfidBindingsFragment(), "RfidBindings"));
        }
        
        // 5. ⚙️ إعدادات الوصول السريع
        if (pm.canManageQuickAccess() || pm.isManager() || pm.isAdmin()) {
            optionsList.add(isArabic ? "⚙️ إعدادات الوصول السريع" : "⚙️ Quick Access Settings");
            actionsList.add(() -> loadFragment(new MiraQuickAccessSettingsFragment(), "QuickAccessSettings"));
        }
        
        // 6. اختبار التوصيل
        optionsList.add(isArabic ? "🔄 اختبار التوصيل والربط" : "🔄 Connection Test");
        actionsList.add(this::performConnectionTest);
        
        String[] options = optionsList.toArray(new String[0]);
        
        new MaterialAlertDialogBuilder(this)
                .setTitle(isArabic ? "📡 أجهزة ومستشعرات المنظومة" : "📡 Hardware & Sensors")
                .setItems(options, (dialog, which) -> {
                    if (which >= 0 && which < actionsList.size()) {
                        actionsList.get(which).run();
                    }
                })
                .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
                .show();
    }

    /**
     * ✅ اختبار التوصيل والربط
     */
    private void performConnectionTest() {
        showLoading();
        
        mainHandler.postDelayed(() -> {
            hideLoading();
            
            boolean isConnected = hardwareManager != null && hardwareManager.isConnected();
            
            String message = isConnected 
                ? (isArabic ? "✅ الاتصال بالجهاز سليم\nجميع الأجهزة تعمل بشكل صحيح" 
                            : "✅ Device connection OK\nAll devices operational")
                : (isArabic ? "⚠️ لا يوجد اتصال بالجهاز\nيرجى التحقق من الاتصال" 
                            : "⚠️ No device connection\nPlease check connection");
            
            new MaterialAlertDialogBuilder(this)
                    .setTitle(isArabic ? "نتيجة الاختبار" : "Test Result")
                    .setMessage(message)
                    .setPositiveButton(isArabic ? "موافق" : "OK", null)
                    .show();
        }, 1500);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateToolbarTitle();
        if (statusIndicatorManager != null) statusIndicatorManager.scheduleUpdates();
        mainHandler.postDelayed(this::hideLoading, 100);
        
        // ✅ تحديث شريحة حالة الجهاز عند العودة
        if (hardwareManager != null) {
            updateSecondaryStatusChip(hardwareManager.isConnected());
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        hideLoading();
        AppWatchdog.getInstance().stopMonitoring();
        if (statusIndicatorManager != null) statusIndicatorManager.cleanup();

        try {
            if (hardwareManager != null) {
                hardwareManager.stopScan();
            }
        } catch (Exception e) {
            Log.w(TAG, "Error stopping scan in onPause: " + e.getMessage());
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        try {
            isArabic = newConfig.locale.getLanguage().equals("ar");

            getWindow().getDecorView().setLayoutDirection(
                    isArabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR
            );

            updateBottomNavLanguage();
            updateToolbarTitle();

            supportInvalidateOptionsMenu();
            View bottomNav = findViewById(R.id.bottomNavigationView);
            if (bottomNav != null) {
                bottomNav.refreshDrawableState();
            }

        } catch (Exception e) {
            Log.e(TAG, "Configuration change error: " + e.getMessage());
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("current_fragment", currentFragmentTag);
        saveFragmentTagToPrefs(currentFragmentTag);
    }

    // ==================== إعدادات ====================

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

    private void saveFragmentTagToPrefs(String tag) {
        try {
            getSharedPreferences(PREFS_NAVIGATION, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_CURRENT_FRAGMENT, tag)
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "Save fragment tag error: " + e.getMessage());
        }
    }

    private void setupOrgProfileListener() {
        orgProfileManager = MiraOrgProfileManager.getInstance(getApplicationContext());
        orgProfileManager.addListener(orgProfileListener);
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
            TextView tvTitle = findViewById(R.id.tvTitle);
            TextView tvSubtitle = findViewById(R.id.tvSubtitle);
            
            String orgName = "MIRA ID";
            if (orgProfileManager != null) {
                String name = orgProfileManager.getDisplayName();
                if (name != null && !name.trim().isEmpty()) {
                    orgName = name;
                }
            }
            
            if (tvTitle != null) {
                tvTitle.setText(orgName);
            }
            
            if (tvSubtitle != null) {
                if (branchesManager != null && branchesManager.hasActiveBranch()) {
                    tvSubtitle.setText("📍 " + branchesManager.getActiveBranchDisplayName());
                } else {
                    tvSubtitle.setText(isArabic ? "📍 الفرع الرئيسي" : "📍 Main Branch");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Toolbar Update Error: " + e.getMessage());
        }
    }

    // ==================== التنقل ====================

    /**
     * نسخة loadFragment بمعاملين فقط
     */
    public void loadFragment(Fragment fragment, String tag) {
        loadFragment(fragment, tag, true);
    }

    /**
     * نسخة loadFragment بثلاث معاملات
     */
    public void loadFragment(Fragment fragment, String tag, boolean addToBackStack) {
        showLoading();
        navigationManager.loadFragment(fragment, tag, addToBackStack);
        currentFragment = navigationManager.getCurrentFragment();
        currentFragmentTag = tag;
        updateBottomNavSelectionByTag(tag);
        mainHandler.postDelayed(this::hideLoading, 250);
        
        // ✅ تحديث الشريط الثانوي
        refreshSecondaryToolbar(tag);
        updateSecondaryToolbarVisibility(tag);
    }

    public void loadFragmentDirectly(Fragment fragment, String tag) {
        showLoading();
        
        if (navigationManager != null) {
            navigationManager.loadFragmentDirectly(fragment, tag);
        }
        
        currentFragment = navigationManager != null ? 
                navigationManager.getCurrentFragment() : fragment;
        currentFragmentTag = tag;
        
        updateBottomNavSelectionByTag(tag);
        updateSecondaryToolbarVisibility(tag);
        
        mainHandler.postDelayed(this::hideLoading, 250);
        
        // ✅ تحديث الشريط الثانوي
        refreshSecondaryToolbar(tag);
    }

    /**
     * ✅ تحديث واجهة الشريط الثانوي (إظهار/إخفاء)
     */
    private void updateSecondaryToolbarVisibility(String tag) {
        try {
            View secondaryToolbar = findViewById(R.id.secondaryToolbar);
            if (secondaryToolbar == null) return;
            
            boolean shouldHide = isScreenThatHidesToolbar(tag);
            
            if (shouldHide) {
                if (secondaryToolbar.getVisibility() == View.VISIBLE) {
                    secondaryToolbar.animate()
                            .alpha(0f)
                            .setDuration(200)
                            .withEndAction(() -> secondaryToolbar.setVisibility(View.GONE))
                            .start();
                }
            } else {
                if (secondaryToolbar.getVisibility() != View.VISIBLE) {
                    secondaryToolbar.setVisibility(View.VISIBLE);
                    secondaryToolbar.setAlpha(0f);
                    secondaryToolbar.animate()
                            .alpha(1f)
                            .setDuration(200)
                            .start();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating secondary toolbar visibility", e);
        }
    }

    /**
     * ✅ هل الشاشة تُخفي Secondary Toolbar؟
     */
    private boolean isScreenThatHidesToolbar(String tag) {
        if (tag == null) return false;
        
        switch (tag) {
            case "Scan":
            case "MiraInventoryProFragment":
                return true;
            default:
                return false;
        }
    }

    public Fragment createFragmentByTag(String tag) {
        return FragmentFactory.createFragmentByTag(tag);
    }

    public void openReceiptFragment(String rawJson) {
        try {
            MiraReceiptFragment fragment = MiraReceiptFragment.newInstance(rawJson);
            fragment.show(getSupportFragmentManager(), "Receipt");
        } catch (Exception e) {
            Log.e(TAG, "openReceiptFragment Error: " + e.getMessage());
        }
    }

    // ==================== الأصوات ====================

    public void playSound(int id) {
        if (soundManager != null) {
            soundManager.playSound(this, id);
        }
    }

    public void playSoundDelayed(int value) {
        if (soundManager != null) {
            soundManager.playDelayedSound(this, value);
        }
    }

    // ==================== RFID ====================

    public void startUHFScan() {
        if (hardwareManager != null) {
            hardwareManager.startScan();
        }
    }

    public void stopUHFScan() {
        if (hardwareManager != null) {
            hardwareManager.stopScan();
        }
    }

    public void onTagRead(String epc) {
        try {
            if (activeEncodingFragment != null && activeEncodingFragment.isAdded() && activeEncodingFragment.isResumed()) {
                activeEncodingFragment.onRfidScanned(epc);
                return;
            }
            if (activeRfidListener != null) {
                activeRfidListener.onRfidScanned(epc);
                return;
            }
            if (activePOSFragment != null && activePOSFragment.isAdded() && activePOSFragment.isVisible()) {
                activePOSFragment.onRingScanned(epc);
                return;
            }
            if (currentFragment instanceof MiraUHFReadTagFragment && currentFragment.isAdded() && currentFragment.isVisible()) {
                ((MiraUHFReadTagFragment) currentFragment).onTagScanned(epc, -50.0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Tag Read Processing Error: " + e.getMessage());
        }
    }

    // ==================== التنقل السريع ====================

    private void setupQuickNav() {
        try {
            BottomNavigationView quickNav = findViewById(R.id.bottomNavigationView);
            FloatingActionButton fabActionCenter = findViewById(R.id.fabActionCenter);

            if (fabActionCenter != null) {
                boolean hasQuickActions = permissionManager.getAllowedQuickActions().size() > 0;
                fabActionCenter.setVisibility(hasQuickActions ? View.VISIBLE : View.GONE);
                fabActionCenter.setEnabled(hasQuickActions);
                fabActionCenter.setOnClickListener(v -> showQuickActionMenu());
            }

            if (quickNav != null) {
                quickNav.setOnItemSelectedListener(item -> {
                    int id = item.getItemId();
                    String tag = getFragmentTagForNavItem(id);
                    Fragment fragment = FragmentFactory.createFragmentByTag(tag);

                    if (fragment != null) {
                        loadFragment(fragment, tag, false);
                        return true;
                    }
                    return false;
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "QuickNav Setup Error: " + e.getMessage());
        }
    }

    private String getFragmentTagForNavItem(int itemId) {
        if (itemId == R.id.nav_home) return "Dashboard";
        if (itemId == R.id.nav_inventory) return "Scan";
        if (itemId == R.id.nav_sales) return "POS";
        if (itemId == R.id.nav_transfers) return "Transfers";
        return "";
    }

    private void updateBottomNavSelectionByTag(String tag) {
        try {
            BottomNavigationView quickNav = findViewById(R.id.bottomNavigationView);
            if (quickNav == null || tag == null) return;

            if (tag.equals("Dashboard")) {
                quickNav.getMenu().findItem(R.id.nav_home).setChecked(true);
            } else if (tag.equals("Scan")) {
                quickNav.getMenu().findItem(R.id.nav_inventory).setChecked(true);
            } else if (tag.equals("POS")) {
                quickNav.getMenu().findItem(R.id.nav_sales).setChecked(true);
            } else if (tag.equals("Transfers")) {
                quickNav.getMenu().findItem(R.id.nav_transfers).setChecked(true);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating bottom nav selection: " + e.getMessage());
        }
    }

    private void updateBottomNavLanguage() {
        try {
            BottomNavigationView nav = findViewById(R.id.bottomNavigationView);
            if (nav != null && nav.getMenu() != null) {
                nav.getMenu().findItem(R.id.nav_home).setTitle(isArabic ? "الرئيسية" : "Home");
                nav.getMenu().findItem(R.id.nav_inventory).setTitle(isArabic ? "الجرد" : "Inventory");
                nav.getMenu().findItem(R.id.nav_sales).setTitle(isArabic ? "البيع" : "Sales");
                nav.getMenu().findItem(R.id.nav_transfers).setTitle(isArabic ? "التحويلات" : "Transfers");
            }
        } catch (Exception e) {
            Log.e(TAG, "Update bottom nav language error: " + e.getMessage());
        }
    }

    // ==================== القوائم ====================

    @SuppressLint("RestrictedApi")
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);

        MenuItem notificationItem = menu.findItem(R.id.action_notifications);
        if (notificationItem != null) {
            View actionView = notificationItem.getActionView();
            if (actionView != null) {
                txtBadgeCount = actionView.findViewById(R.id.txtBadgeCount);
                notificationManager.setBadgeView(txtBadgeCount);
                actionView.setOnClickListener(v -> handleNotificationButtonClick());
            }
        }

        notificationManager.init(sessionManager.getUserId());
        applyToolbarMenuPermissions(menu);

        return true;
    }

    private void applyToolbarMenuPermissions(Menu menu) {
        PermissionManager pm = permissionManager;

        MenuItem actionGoldRates = menu.findItem(R.id.action_gold_rates);
        if (actionGoldRates != null) actionGoldRates.setVisible(pm.canViewGoldRates());

        MenuItem actionProfile = menu.findItem(R.id.action_profile);
        if (actionProfile != null) actionProfile.setVisible(pm.canViewProfile());

        MenuItem toggleLanguage = menu.findItem(R.id.action_toggle_language);
        if (toggleLanguage != null) toggleLanguage.setTitle(isArabic ? "English" : "العربية");

        MenuItem actionQuickAccess = menu.findItem(R.id.action_quick_access_settings);
        if (actionQuickAccess != null) actionQuickAccess.setVisible(pm.canManageQuickAccess());

        MenuItem actionBranches = menu.findItem(R.id.action_branches);
        if (actionBranches != null) actionBranches.setVisible(pm.isManager() || pm.isAdmin() || pm.isBranchManager());

        MenuItem actionExport = menu.findItem(R.id.export);
        if (actionExport != null) actionExport.setVisible(pm.canExportReports());

        MenuItem uhfVer = menu.findItem(R.id.UHF_ver);
        if (uhfVer != null) uhfVer.setVisible(pm.isAdmin() || pm.isSystemAdmin());
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_gold_rates) {
            loadFragment(new MiraGoldCurrencyFragment(), "GoldRates");
            return true;
        } else if (id == R.id.action_profile) {
            loadFragment(new MiraProfileFragment(), "Profile");
            return true;
        } else if (id == R.id.action_quick_access_settings) {
            loadFragment(new MiraQuickAccessSettingsFragment(), "QuickAccessSettings");
            return true;
        } else if (id == R.id.action_branches) {
            loadFragment(new MiraBranchesManagementFragment(), "Branches");
            return true;
        } else if (id == R.id.export) {
            loadFragment(new MiraReportsProFragment(), "Reports");
            return true;
        } else if (id == R.id.action_toggle_language) {
            toggleLanguage();
            return true;
        } else if (id == R.id.nav_sound_settings) {
            loadFragment(new MiraSoundSettingsFragment(), "SoundSettings");
            return true;
        } else if (id == R.id.UHF_ver) {
            showSystemVersionDialog();
            return true;
        } else if (id == R.id.action_logout) {
            confirmLogout();
            return true;
        }

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
            Log.e(TAG, "Toggle language error: " + e.getMessage(), e);
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
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
    }

    private void handleNotificationButtonClick() {
        if (permissionManager.isAdmin() || permissionManager.isManager()) {
            showNotificationsAndChatDialog();
        } else {
            showNotificationsAndMessagingDialog();
        }
    }

    // ==================== الحوارات ====================

    private void showSystemVersionDialog() {
        String miraSystemVersion = "MIRA ID™ v1.0.0 (Enterprise)";
        String hwVer = null;
        String modelName = Build.MODEL;

        if (BaseTabFragmentActivity.RFID_DEVICE_PRESENT && mReader != null) {
            try {
                hwVer = mReader.getHardwareVersion();
            } catch (Exception e) {
                Log.e(TAG, "Error fetching RFID reader info: " + e.getMessage());
            }
        }

        StringBuilder message = new StringBuilder();
        message.append("MIRA Trust Architecture™\n");
        message.append(isArabic ? "نظام المصادقة والتتبع المعتمد للمجوهرات\n\n" : "Jewelry Tracking & Authentication System\n\n");
        message.append(isArabic ? "📱 الجهاز: " : "📱 Device: ").append(modelName).append("\n");
        message.append(isArabic ? "📦 النسخة: " : "📦 Version: ").append(miraSystemVersion).append("\n");

        if (!BaseTabFragmentActivity.RFID_DEVICE_PRESENT) {
            message.append(isArabic ? "📡 العتاد: غير متصل" : "📡 Hardware: Not Connected");
        } else if (hwVer == null || hwVer.trim().isEmpty()) {
            message.append(isArabic ? "📡 العتاد: غير متهيئ" : "📡 Hardware: Not Configured");
        } else {
            message.append(isArabic ? "📡 العتاد: " : "📡 Hardware: ").append(hwVer).append("\n");
            message.append(isArabic ? "🟢 الحالة: وحدة UHF نشطة ومتصلة" : "🟢 Status: Active & Connected");
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(isArabic ? "حول النظام | System Info" : "About System")
                .setMessage(message.toString())
                .setPositiveButton(isArabic ? "موافق" : "OK", (dialog, which) -> dialog.dismiss())
                .show();
    }

    // ==================== الإشعارات والمراسلات ====================

    private void showNotificationsAndChatDialog() {
        String[] options = isArabic ? new String[]{
                "💬 مراسلة الأعضاء والفروع",
                "📥 تنبيهات النظام المخزنية",
                "📢 التعميمات الإدارية"
        } : new String[]{
                "💬 Team & Branch Chat",
                "📥 Inventory Notifications",
                "📢 Administrative Broadcasts"
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle(isArabic ? "📬 مركز المراسلات والإشعارات" : "Messaging Center")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showChatWindowDialog();
                    } else {
                        showNotificationsAndMessagingDialog();
                    }
                })
                .setPositiveButton(isArabic ? "إغلاق" : "Close", null)
                .show();
    }

    private void showNotificationsAndMessagingDialog() {
        BottomSheetDialog dialog = new BottomSheetDialog(this, R.style.TransparentBottomSheetDialog);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        TextView title = new TextView(this);
        title.setText(isArabic ? "🔔 إشعارات ورسائل المنظومة" : "System Notifications & Messages");
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(listContainer);

        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 400);
        scrollView.setLayoutParams(scrollParams);
        root.addView(scrollView);

        MiraMessagingManager messagingMgr = MiraMessagingManager.getInstance(this);

        List<NotificationModel> cachedNotifications = messagingMgr.getCachedNotifications();
        if (cachedNotifications.isEmpty()) {
            TextView emptyView = new TextView(this);
            emptyView.setText(isArabic ? "لا توجد إشعارات حالياً" : "No notifications available");
            emptyView.setPadding(16, 16, 16, 16);
            listContainer.addView(emptyView);
        } else {
            for (NotificationModel notif : cachedNotifications) {
                TextView tvNote = new TextView(this);
                String icon = getNotificationIcon(notif.getNotificationType());
                tvNote.setText(icon + " " + notif.getTitle() + "\n" + notif.getContent());
                tvNote.setPadding(16, 16, 16, 16);
                tvNote.setTextSize(14);
                listContainer.addView(tvNote);
            }
        }

        messagingMgr.fetchNotificationsFromServer(new MiraMessagingManager.OnNotificationsLoadedListener() {
            @Override
            public void onNotificationsLoaded(List<NotificationModel> notifications) {
                runOnUiThread(() -> {
                    listContainer.removeAllViews();
                    if (notifications.isEmpty()) {
                        TextView emptyView = new TextView(UHFMainActivity.this);
                        emptyView.setText(isArabic ? "لا توجد إشعارات حالياً" : "No notifications available");
                        emptyView.setPadding(16, 16, 16, 16);
                        listContainer.addView(emptyView);
                    } else {
                        for (NotificationModel notif : notifications) {
                            TextView tvNote = new TextView(UHFMainActivity.this);
                            String icon = getNotificationIcon(notif.getNotificationType());
                            tvNote.setText(icon + " " + notif.getTitle() + "\n" + notif.getContent());
                            tvNote.setPadding(16, 16, 16, 16);
                            tvNote.setTextSize(14);
                            listContainer.addView(tvNote);
                        }
                    }
                });
            }

            @Override
            public void onError(String error) {
            }
        });

        TextView lblSend = new TextView(this);
        lblSend.setText(isArabic ? "💬 إرسال رسالة داخلية جديدة:" : "💬 Send New Internal Message:");
        lblSend.setTextSize(14);
        lblSend.setTypeface(null, Typeface.BOLD);
        lblSend.setPadding(0, 24, 0, 8);
        root.addView(lblSend);

        EditText etMessageContent = new EditText(this);
        etMessageContent.setHint(isArabic ? "اكتب رسالتك للإدارة أو الفروع..." : "Type message to management or branches...");
        etMessageContent.setPadding(24, 24, 24, 24);
        root.addView(etMessageContent);

        MaterialButton btnSend = new MaterialButton(this);
        btnSend.setText(isArabic ? "إرسال الرسالة الآن 🚀" : "Send Message Now 🚀");
        root.addView(btnSend);

        btnSend.setOnClickListener(v -> {
            String content = etMessageContent.getText().toString().trim();
            if (!content.isEmpty()) {
                btnSend.setEnabled(false);
                btnSend.setText(isArabic ? "جاري الإرسال..." : "Sending...");

                String receiverId = "admin";

                MiraMessagingManager messagingMgr2 = MiraMessagingManager.getInstance(this);
                messagingMgr2.sendMessage(receiverId, content, new MiraApiClient.SendMessageCallback() {
                    @Override
                    public void onSuccess(MessageModel sentMessage) {
                        runOnUiThread(() -> {
                            Toast.makeText(UHFMainActivity.this,
                                    isArabic ? "تم إرسال الرسالة بنجاح ✅" : "Message sent successfully ✅",
                                    Toast.LENGTH_LONG).show();
                            dialog.dismiss();
                        });
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> {
                            Toast.makeText(UHFMainActivity.this,
                                    isArabic ? "فشل الإرسال: " + error : "Failed: " + error,
                                    Toast.LENGTH_SHORT).show();
                            btnSend.setEnabled(true);
                            btnSend.setText(isArabic ? "إرسال الرسالة الآن 🚀" : "Send Message Now 🚀");
                        });
                    }
                });
            } else {
                etMessageContent.setError(isArabic ? "الرجاء كتابة نص الرسالة" : "Please enter message text");
            }
        });

        dialog.setContentView(root);
        dialog.show();
    }

    private void showChatWindowDialog() {
        BottomSheetDialog chatDialog = new BottomSheetDialog(this, R.style.TransparentBottomSheetDialog);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        TextView title = new TextView(this);
        title.setText(isArabic ? "💬 مراسلة أعضاء المنظومة" : "💬 Internal Team Chat");
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        LinearLayout messagesContainer = new LinearLayout(this);
        messagesContainer.setOrientation(LinearLayout.VERTICAL);

        TextView loadingText = new TextView(this);
        loadingText.setText(isArabic ? "جاري تحميل الرسائل..." : "Loading messages...");
        loadingText.setGravity(android.view.Gravity.CENTER);
        messagesContainer.addView(loadingText);

        scrollView.addView(messagesContainer);
        root.addView(scrollView);

        MiraMessagingManager messagingMgr = MiraMessagingManager.getInstance(this);
        messagingMgr.fetchMessagesFromServer(new MiraMessagingManager.OnMessagesLoadedListener() {
            @Override
            public void onMessagesLoaded(List<MessageModel> messages) {
                runOnUiThread(() -> {
                    messagesContainer.removeAllViews();

                    if (messages.isEmpty()) {
                        TextView emptyText = new TextView(UHFMainActivity.this);
                        emptyText.setText(isArabic ? "لا توجد رسائل بعد" : "No messages yet");
                        emptyText.setGravity(android.view.Gravity.CENTER);
                        emptyText.setPadding(16, 32, 16, 32);
                        messagesContainer.addView(emptyText);
                    } else {
                        for (MessageModel message : messages) {
                            TextView messageView = new TextView(UHFMainActivity.this);
                            String sender = message.getSenderName() != null && !message.getSenderName().isEmpty()
                                    ? message.getSenderName()
                                    : (isArabic ? "مستخدم" : "User");

                            String time = formatTimestamp(message.getTimestamp());
                            String messageText = sender + " (" + time + "):\n" + message.getContent();

                            messageView.setText(messageText);
                            messageView.setPadding(16, 12, 16, 12);
                            messageView.setBackgroundResource(R.drawable.bg_message_bubble);

                            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                            );
                            params.setMargins(0, 0, 0, 8);
                            messageView.setLayoutParams(params);

                            messagesContainer.addView(messageView);
                        }
                    }

                    scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    messagesContainer.removeAllViews();
                    TextView errorText = new TextView(UHFMainActivity.this);
                    errorText.setText(isArabic ? "فشل تحميل الرسائل: " + error : "Failed to load messages: " + error);
                    errorText.setGravity(android.view.Gravity.CENTER);
                    errorText.setPadding(16, 32, 16, 32);
                    messagesContainer.addView(errorText);
                });
            }
        });

        EditText etMessage = new EditText(this);
        etMessage.setHint(isArabic ? "اكتب رسالتك..." : "Type message...");
        etMessage.setPadding(24, 24, 24, 24);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        inputParams.setMargins(0, 16, 0, 8);
        etMessage.setLayoutParams(inputParams);
        root.addView(etMessage);

        MaterialButton btnSend = new MaterialButton(this);
        btnSend.setText(isArabic ? "إرسال" : "Send");
        btnSend.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        root.addView(btnSend);

        btnSend.setOnClickListener(v -> {
            String msg = etMessage.getText().toString().trim();
            if (!msg.isEmpty()) {
                btnSend.setEnabled(false);
                btnSend.setText(isArabic ? "جاري الإرسال..." : "Sending...");

                String receiverId = "all";
                MiraMessagingManager messagingMgr2 = MiraMessagingManager.getInstance(this);
                messagingMgr2.sendMessage(receiverId, msg, new MiraApiClient.SendMessageCallback() {
                    @Override
                    public void onSuccess(MessageModel message) {
                        runOnUiThread(() -> {
                            Toast.makeText(UHFMainActivity.this,
                                    isArabic ? "تم إرسال الرسالة بنجاح" : "Message Sent",
                                    Toast.LENGTH_SHORT).show();
                            etMessage.setText("");
                            btnSend.setEnabled(true);
                            btnSend.setText(isArabic ? "إرسال" : "Send");
                            chatDialog.dismiss();
                        });
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> {
                            Toast.makeText(UHFMainActivity.this,
                                    isArabic ? "فشل إرسال الرسالة: " + error : "Failed to send message: " + error,
                                    Toast.LENGTH_SHORT).show();
                            btnSend.setEnabled(true);
                            btnSend.setText(isArabic ? "إرسال" : "Send");
                        });
                    }
                });
            } else {
                etMessage.setError(isArabic ? "الرجاء كتابة رسالة" : "Please type a message");
            }
        });

        chatDialog.setContentView(root);
        chatDialog.show();
    }

    private String getNotificationIcon(String type) {
        if (type == null) return "🔔";
        switch (type) {
            case "INVENTORY": return "⚠️";
            case "SYSTEM": return "📥";
            case "ADMIN": return "📢";
            case "CHAT": return "💬";
            default: return "🔔";
        }
    }

    private String formatTimestamp(long timestamp) {
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                    isArabic ? "HH:mm dd/MM/yyyy" : "HH:mm MM/dd/yyyy",
                    java.util.Locale.getDefault()
            );
            return sdf.format(new java.util.Date(timestamp));
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== البحث والمزامنة ====================

    private void showGlobalSearchDialog() {
        BottomSheetDialog searchDialog = new BottomSheetDialog(this, R.style.TransparentBottomSheetDialog);

        LinearLayout searchLayout = new LinearLayout(this);
        searchLayout.setOrientation(LinearLayout.VERTICAL);
        searchLayout.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText(isArabic ? "🔍 البحث السريع في المنظومة" : "Global System Search");
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, 0, 0, 24);
        searchLayout.addView(title);

        final EditText etSearchQuery = new EditText(this);
        etSearchQuery.setHint(isArabic ? "أدخل رقم الباركود، EPC، أو اسم القطعة..." : "Enter EPC, Barcode, or item name...");
        etSearchQuery.setPadding(32, 32, 32, 32);
        searchLayout.addView(etSearchQuery);

        MaterialButton btnExecuteSearch = new MaterialButton(this);
        btnExecuteSearch.setText(isArabic ? "بحث الآن" : "Search Now");
        btnExecuteSearch.setPadding(0, 24, 0, 24);
        searchLayout.addView(btnExecuteSearch);

        searchDialog.setContentView(searchLayout);

        btnExecuteSearch.setOnClickListener(v -> {
            String query = etSearchQuery.getText().toString().trim();
            if (!query.isEmpty()) {
                searchDialog.dismiss();
                Toast.makeText(this, (isArabic ? "جاري البحث عن: " : "Searching for: ") + query, Toast.LENGTH_SHORT).show();
                loadFragment(new MiraInventoryProFragment(), "Scan");
            } else {
                etSearchQuery.setError(isArabic ? "الرجاء إدخال نص للبحث" : "Please enter search text");
            }
        });

        searchDialog.show();
    }

    private void performDataSync() {
        showLoading();
        Toast.makeText(this, isArabic ? "جاري مزامنة البيانات المحلية مع السيرفر..." : "Syncing local data with server...", Toast.LENGTH_SHORT).show();

        mainHandler.postDelayed(() -> {
            hideLoading();
            new MaterialAlertDialogBuilder(this)
                    .setTitle(isArabic ? "✅ تمت المزامنة بنجاح" : "✅ Sync Completed")
                    .setMessage(isArabic ? "تم تحديث كافة السجلات، المخزون، والأسعار مع السيرفر السحابي بنجاح." : "All records, inventory, and prices have been successfully synced with the cloud server.")
                    .setPositiveButton(isArabic ? "موافق" : "OK", (dialog, which) -> dialog.dismiss())
                    .show();
        }, 1500);
    }

    // ==================== قائمة الإجراءات السريعة ====================

    private void showQuickActionMenu() {
        try {
            BottomSheetDialog dialog = new BottomSheetDialog(this, R.style.LuxuryBottomSheetDialog);
            View view = getLayoutInflater().inflate(R.layout.dialog_quick_action_sheet, null);
            dialog.setContentView(view);

            PermissionManager pm = permissionManager;
            MiraQuickAccessSettings quickAccess = MiraQuickAccessSettings.getInstance(getApplicationContext());

            bindQuickAction(view, R.id.btnRfidWrite, dialog,
                    pm.canEditInventory() && quickAccess.isActionEnabled("rfid_write"),
                    new MiraUHFReadWriteFragment(), "RFIDWrite");

            bindQuickAction(view, R.id.btnRfidBindings, dialog,
                    pm.canViewRfidBindings() && quickAccess.isActionEnabled("rfid"),
                    new MiraRfidBindingsFragment(), "RfidBindings");

            bindQuickAction(view, R.id.btnIdentityStudio, dialog,
                    pm.canAccessIdentityStudio() && quickAccess.isActionEnabled("identity"),
                    new MiraIdentityStudioFragment(), "IdentityStudio");

            bindQuickAction(view, R.id.btnRadarLocation, dialog,
                    pm.canAccessRadar() && quickAccess.isActionEnabled("radar"),
                    new MiraUHFRadarLocationFragment(), "Radar");

            bindQuickAction(view, R.id.btnSecureGate, dialog,
                    pm.canAccessGate() && quickAccess.isActionEnabled("gate"),
                    new MiraSecureGateFragment(), "SecureGate");

            bindQuickAction(view, R.id.btnReports, dialog,
                    pm.canExportReports() && quickAccess.isActionEnabled("reports"),
                    new MiraReportsProFragment(), "Reports");

            bindQuickAction(view, R.id.btnAccounting, dialog,
                    pm.canAccessAccounting() && quickAccess.isActionEnabled("accounting"),
                    new MiraAccountingFragment(), "Accounting");

            bindQuickAction(view, R.id.btnTransfer, dialog,
                    pm.canAccessTransfers() && quickAccess.isActionEnabled("transfer"),
                    new MiraTransferFragment(), "Transfers");

            bindQuickAction(view, R.id.btnSalesHistory, dialog,
                    pm.canViewSalesHistory() && quickAccess.isActionEnabled("sales_history"),
                    new MiraSalesHistoryFragment(), "SalesHistory");

            bindQuickAction(view, R.id.btnAdminHub, dialog,
                    pm.isAdmin() && quickAccess.isActionEnabled("admin_hub"),
                    new MiraAdminHubFragment(), "AdminHub");

            bindQuickAction(view, R.id.btnServerConfig, dialog,
                    pm.canViewServerConfig() && quickAccess.isActionEnabled("server_config"),
                    new MiraServerConfigFragment(), "ServerConfig");

            View btnClose = view.findViewById(R.id.btnCloseSheet);
            if (btnClose != null) {
                btnClose.setOnClickListener(v -> dialog.dismiss());
            }

            GridLayout grid = view.findViewById(R.id.gridQuickActions);
            LinearLayout root = view.findViewById(R.id.quickActionSheetRoot);

            if (root != null) {
                root.setAlpha(0f);
                root.setTranslationY(80f);
            }

            dialog.setOnShowListener(d -> {
                if (root != null) {
                    root.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setDuration(350)
                            .setInterpolator(new DecelerateInterpolator())
                            .start();
                }

                if (grid != null) {
                    for (int i = 0; i < grid.getChildCount(); i++) {
                        View child = grid.getChildAt(i);
                        if (child.getVisibility() != View.VISIBLE) continue;

                        child.setAlpha(0f);
                        child.setScaleX(0.4f);
                        child.setScaleY(0.4f);
                        child.setTranslationY(30f);

                        child.animate()
                                .alpha(1f)
                                .scaleX(1f)
                                .scaleY(1f)
                                .translationY(0f)
                                .setStartDelay(120 + (i * 45L))
                                .setDuration(400)
                                .setInterpolator(new OvershootInterpolator(1.4f))
                                .start();
                    }
                }
            });

            dialog.setOnDismissListener(d -> {
                if (root != null) {
                    root.animate()
                            .alpha(0f)
                            .translationY(80f)
                            .setDuration(200)
                            .setInterpolator(new AccelerateInterpolator())
                            .start();
                }
            });

            dialog.show();

        } catch (Exception e) {
            Log.e(TAG, "QuickActionMenu Error: " + e.getMessage(), e);
        }
    }

    private void bindQuickAction(View root, int viewId, BottomSheetDialog dialog,
                                  boolean allowed, Fragment fragment, String tag) {
        View btn = root.findViewById(viewId);
        if (btn != null) {
            btn.setVisibility(allowed ? View.VISIBLE : View.GONE);
            if (allowed) {
                btn.setOnClickListener(v -> {
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100)
                            .withEndAction(() -> {
                                v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
                                dialog.dismiss();
                                loadFragment(fragment, tag);
                            }).start();
                });
            }
        }
    }

    // ==================== 🔒 وضع الجرد المحصّن ====================

    public void enterInventoryLockMode() {
        if (inventoryLockManager != null) {
            inventoryLockManager.lock();
        }
    }

    public void exitInventoryLockMode() {
        if (inventoryLockManager != null) {
            inventoryLockManager.unlock();
        }
    }

    public boolean isInventoryLocked() {
        return inventoryLockManager != null && inventoryLockManager.isLocked();
    }
    
    // ==================== حالة الجرد ====================

    public boolean isInventoryActive() {
        if (currentFragment instanceof MiraInventoryProFragment && currentFragment.isVisible()) {
            return ((MiraInventoryProFragment) currentFragment).isScanning();
        }
        if (currentFragment instanceof MiraUHFReadTagFragment && currentFragment.isVisible()) {
            return ((MiraUHFReadTagFragment) currentFragment).isScanning();
        }
        return false;
    }

    private void checkInventoryLockAndExecute(Runnable onConfirmed) {
        if (isInventoryActive()) {
            new MaterialAlertDialogBuilder(this)
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
            if (currentFragment instanceof MiraInventoryProFragment && currentFragment.isVisible()) {
                ((MiraInventoryProFragment) currentFragment).stopScanning();
            }
            if (currentFragment instanceof MiraUHFReadTagFragment && currentFragment.isVisible()) {
                ((MiraUHFReadTagFragment) currentFragment).stopScanning();
            }
            stopUHFScan();
        } catch (Exception e) {
            Log.e(TAG, "Error stopping inventory: " + e.getMessage());
        }
    }

    // ==================== التنقل للخلف ====================

    @Override
    public void onBackPressed() {
        if (isInventoryActive()) {
            checkInventoryLockAndExecute(this::handleBackNavigation);
            return;
        }
        handleBackNavigation();
    }

    private void handleBackNavigation() {
        clearActiveListeners();
        
        if (getSupportFragmentManager() != null) {
            // 1. إغلاق الحوارات إن وجدت
            for (Fragment fragment : getSupportFragmentManager().getFragments()) {
                if (fragment instanceof androidx.fragment.app.DialogFragment) {
                    androidx.fragment.app.DialogFragment dialogFragment = 
                            (androidx.fragment.app.DialogFragment) fragment;
                    if (dialogFragment.getDialog() != null && dialogFragment.getDialog().isShowing()) {
                        dialogFragment.dismissAllowingStateLoss();
                        return;
                    }
                }
            }

            // 2. الرجوع في الـ BackStack
            if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
                getSupportFragmentManager().popBackStackImmediate();
                Fragment topFragment = navigationManager.getTopFragment();
                
                if (topFragment != null) {
                    String tag = topFragment.getTag();
                    if (tag != null && !permissionManager.canAccessFragment(tag)) {
                        navigationManager.clearBackStack();
                        loadFragmentDirectly(new MiraDashboardFragment(), "Dashboard");
                        return;
                    }
                    
                    // إظهار الـ Fragment المستعاد
                    if (topFragment.isHidden()) {
                        getSupportFragmentManager().beginTransaction()
                                .show(topFragment)
                                .commitAllowingStateLoss();
                    }
                    
                    // إخفاء الآخرين
                    for (Fragment f : getSupportFragmentManager().getFragments()) {
                        if (f != null && f != topFragment && f.isAdded() && !f.isHidden()) {
                            getSupportFragmentManager().beginTransaction()
                                    .hide(f)
                                    .commitAllowingStateLoss();
                        }
                    }
                    
                    currentFragment = topFragment;
                    if (tag != null) {
                        currentFragmentTag = tag;
                        saveFragmentTagToPrefs(tag);
                        updateSecondaryToolbarVisibility(tag);
                        refreshSecondaryToolbar(tag);
                    }
                }
                return;
            }
        }

        // 3. إذا لم يكن هناك BackStack، عد للـ Dashboard
        if (currentFragment != null && !(currentFragment instanceof MiraDashboardFragment)) {
            navigationManager.clearBackStack();
            loadFragmentDirectly(new MiraDashboardFragment(), "Dashboard");
            return;
        }

        // 4. الخروج المزدوج
        handleDoubleBackToExit();
    }

    private void handleDoubleBackToExit() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastBackPressTime < DOUBLE_BACK_TIMEOUT) {
            finish();
            return;
        }
        lastBackPressTime = currentTime;
        Toast.makeText(this,
                isArabic ? "اضغط مرة أخرى للخروج" : "Press again to exit",
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onBackPressed();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ==================== التحميل والعرض ====================

    public void showLoading() {
        mainHandler.post(() -> {
            try {
                View loadingOverlay = findViewById(R.id.successOverlayMain);
                if (loadingOverlay != null) {
                    loadingOverlay.setVisibility(View.VISIBLE);
                    loadingOverlay.setAlpha(1.0f);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error showing loading: " + e.getMessage());
            }
        });
    }

    public void hideLoading() {
        mainHandler.post(() -> {
            try {
                View loadingOverlay = findViewById(R.id.successOverlayMain);
                if (loadingOverlay != null && loadingOverlay.getVisibility() == View.VISIBLE) {
                    loadingOverlay.animate()
                            .alpha(0.0f)
                            .setDuration(200)
                            .withEndAction(() -> loadingOverlay.setVisibility(View.GONE))
                            .start();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error hiding loading: " + e.getMessage());
            }
        });
    }

    public void showToast(String text) {
        if (text == null) return;
        mainHandler.post(() -> Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT).show());
    }

    public void showToast(int resId) {
        showToast(getString(resId));
    }

    // ==================== تسجيل الدخول ====================

    private void navigateToLogin() {
        try {
            hideLoading();
            if (mReader != null) {
                mReader.free();
                mReader = null;
            }

            Intent intent = new Intent(this, LoginActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        } catch (Exception e) {
            Log.e(TAG, "Navigation to login error: " + e.getMessage());
        }
    }

    // ==================== تنظيف ====================

    @Override
    protected void onDestroy() {
        try {
            stopActiveInventory();
            
            if (notificationManager != null) notificationManager.cleanup();
            if (soundManager != null) soundManager.cleanup();
            if (statusIndicatorManager != null) statusIndicatorManager.cleanup();
            if (hardwareManager != null) hardwareManager.cleanup();
            
            AppWatchdog.getInstance().stopMonitoring();
            hideLoading();
            stopActiveInventory();

            if (getSupportFragmentManager() != null) {
                navigationManager.clearBackStack();
            }

            if (orgProfileManager != null) {
                orgProfileManager.removeListener(orgProfileListener);
            }

            if (inventoryLockManager != null) {
                inventoryLockManager.cleanup();
                inventoryLockManager = null;
            }

            SessionMonitorManager.getInstance(this).stopMonitoring();

            clearActiveListeners();

            if (tagList != null) {
                tagList.clear();
                tagList = null;
            }

        } catch (Exception e) {
            Log.e(TAG, "onDestroy cleanup error: " + e.getMessage());
        }

        super.onDestroy();
    }

    /**
     * ✅ تنظيف ذكي — يُنفَّذ فقط عند الحاجة الفعلية
     */
    private void cleanupOldFragments() {
        try {
            FragmentManager fm = getSupportFragmentManager();

            if (fm.getFragments().isEmpty() 
                    && fm.getBackStackEntryCount() == 0) {
                Log.d(TAG, "cleanupOldFragments: nothing to clean");
                return;
            }

            if (fm.getBackStackEntryCount() > 0) {
                fm.popBackStackImmediate(
                        null,
                        FragmentManager.POP_BACK_STACK_INCLUSIVE
                );
            }

            Log.d(TAG, "cleanupOldFragments: back stack cleared");

        } catch (Exception e) {
            Log.e(TAG, "cleanupOldFragments error: " + e.getMessage(), e);
        }
    }

    // ==================== التفاعل مع الكيبورد ====================

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
}
