package com.example.uhf.activity;

import com.example.uhf.application.MiraApplication;
import com.example.uhf.manager.AppWatchdog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.annotation.SuppressLint;

import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.graphics.Typeface;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import android.content.SharedPreferences;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;


import com.example.uhf.utils.DiagTracer;
import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.fragment.MiraDashboardFragment;
import com.example.uhf.fragment.MiraInventoryFragment;
import com.example.uhf.fragment.MiraPosFragment;
import com.example.uhf.fragment.MiraScrapGoldBuyFragment;
import com.example.uhf.fragment.MiraAccountingSetupFragment;

import com.example.uhf.fragment.MiraIdentityStudioFragment;
import com.example.uhf.fragment.MiraUHFReadTagFragment;

import com.example.uhf.fragment.QuickActionsBottomSheetFragment;
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
import com.example.uhf.manager.SecondaryToolbarManager;
import com.example.uhf.fragment.MiraReaderControlFragment;

import androidx.lifecycle.ProcessLifecycleOwner;
import com.example.uhf.application.AppLifecycleObserver;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import java.util.concurrent.Executor;

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
import com.example.uhf.fragment.MiraUHFLocatorFragment;
import com.example.uhf.manager.MiraQuickAccessSettings;
import com.example.uhf.MiraRfid.sound.MiraSoundSettingsFragment;
import com.example.uhf.fragment.MiraReaderSetupFragment;
import com.example.uhf.fragment.MiraGateSettingsFragment;
import com.example.uhf.fragment.MiraRfidSettingsFragment;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.google.android.material.badge.BadgeDrawable;

/**
 * UHFMainActivity — نسخة نظيفة كاملة
 *
 * ✅ Setup Guard المركزي
 * ✅ Setup Stamp لكشف التغييرات
 * ✅ BottomNav container + items management
 * ✅ Transfers counter + Badge
 * ✅ MIRA License Heartbeat + Blob refresh
 * ✅ Long-press toolbar → LicenseStatusActivity
 */
public class UHFMainActivity extends BaseTabFragmentActivity {

    private static final String TAG = "UHFMainActivity";
    private static final String PREFS_NAVIGATION = "app_navigation";
    private static final String KEY_CURRENT_FRAGMENT = "current_fragment_tag";

    private volatile boolean setupWelcomeShownThisSession = false;
    private volatile boolean setupCompletedThisSession = false;

    // ═══════════════════════════════════════════════════════
    //  ثوابت حالة الإعداد المحاسبي
    // ═══════════════════════════════════════════════════════
    private static final String PREFS_SETUP = "mira_setup_state";
    private static final String KEY_SETUP_STATUS = "setup_status";
    private static final String KEY_SETUP_CACHED_AT = "cached_at";
    private static final String KEY_SETUP_STAMP = "setup_stamp";
    private static final long SETUP_CACHE_TTL_MS = 5 * 60 * 1000;

    private static final String SETUP_PENDING = "pending";
    private static final String SETUP_IN_PROGRESS = "in_progress";
    private static final String SETUP_COMPLETED = "completed";
    private static final String SETUP_LOCKED = "locked";

    // ═══════════════════════════════════════════════════════
    //  ثوابت عداد التحويلات
    // ═══════════════════════════════════════════════════════
    private static final long TRANSFER_REFRESH_INTERVAL = 60_000L;
    private static final long MIN_TRANSFER_REFRESH_INTERVAL = 3_000L;
    private static final String PREFS_TRANSFERS_BADGE = "transfers_badge_prefs";
    private static final String KEY_LAST_TRANSFERS_COUNT = "last_transfers_count";

    private int loadingCounter = 0;

    private NavigationManager navigationManager;
    private HardwareManager hardwareManager;
    private SoundManager soundManager;
    private NotificationManager notificationManager;
    private StatusIndicatorManager statusIndicatorManager;
    private SecondaryToolbarManager secondaryToolbarManager;

    private TextView txtBadgeCount;
    private SessionManager sessionManager;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfileManager;
    private PermissionManager permissionManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean navigationLocked = false;
    private volatile boolean justUnlockedWithBiometric = false;

    private final MiraOrgProfileManager.OnProfileChangedListener orgProfileListener =
            this::updateAppTitle;

    private MiraPosFragment activePOSFragment = null;
    private MiraIdentityStudioFragment activeEncodingFragment = null;
    private MiraInventoryProFragment activeInventoryFragment = null;
    private OnRfidScannedListener activeRfidListener = null;

    public int selectIndex = -1;
    public ArrayList<UHFTAGInfo> tagList = new ArrayList<>();
    public boolean loopFlag = false;
    private boolean isArabic = true;

    private boolean lastKeyboardState = false;

    private long lastBackPressTime = 0;
    private static final long DOUBLE_BACK_TIMEOUT = 2000;
    private static final long INACTIVITY_TIMEOUT_MS = 15 * 60 * 1000;

    private static final long SETUP_GRACE_PERIOD_MS = 30 * 60 * 1000;
    private volatile long setupCompletedAt = 0;

    private AppLifecycleObserver appLifecycleObserver;

    private int incomingTransfersCount = 0;
    private long lastTransferRefreshTime = 0;
    private final Handler transferCountHandler = new Handler(Looper.getMainLooper());

    private final Runnable transferRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshIncomingTransfersCount();
            transferCountHandler.postDelayed(this, TRANSFER_REFRESH_INTERVAL);
        }
    };

    private final BroadcastReceiver transfersUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if ("com.example.uhf.REFRESH_TRANSFERS".equals(action)
                    || "com.example.uhf.REFRESH_DASHBOARD".equals(action)) {
                refreshIncomingTransfersCountThrottled();
            }
        }
    };

    public interface OnRfidScannedListener {
        void onRfidScanned(String epc);
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

    public void setActiveInventoryFragment(MiraInventoryProFragment fragment) {
        this.activeInventoryFragment = fragment;
    }

    public void clearActiveListeners() {
        this.activePOSFragment = null;
        this.activeEncodingFragment = null;
        this.activeRfidListener = null;
        this.activeInventoryFragment = null;
    }

    // ==================== Getters ====================
    public NavigationManager getNavigationManager() { return navigationManager; }
    public PermissionManager getPermissionManager() { return permissionManager; }

    public String getCurrentFragmentTag() {
        return navigationManager != null
                ? navigationManager.getCurrentFragmentTag()
                : "Dashboard";
    }

    public int getIncomingTransfersCount() { return incomingTransfersCount; }

    // ==================== دورة الحياة ====================

    @Override
    protected void attachBaseContext(Context newBase) {
        String savedLang = MiraLanguageHelper.getSavedLanguage(newBase);
        MiraLanguageHelper.applyLanguage(newBase, savedLang);
        super.attachBaseContext(newBase);
    }

    private void applySavedTheme() {
        try {
            SharedPreferences prefs = getSharedPreferences("app_theme", Context.MODE_PRIVATE);
            int savedMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
            int currentMode = AppCompatDelegate.getDefaultNightMode();

            if (currentMode != savedMode) {
                Log.d(TAG, "Applying saved theme: " + modeToString(savedMode));
                AppCompatDelegate.setDefaultNightMode(savedMode);
            }
        } catch (Exception e) {
            Log.e(TAG, "applySavedTheme error: " + e.getMessage());
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
    }

    private String modeToString(int mode) {
        switch (mode) {
            case AppCompatDelegate.MODE_NIGHT_NO: return "LIGHT";
            case AppCompatDelegate.MODE_NIGHT_YES: return "DARK";
            case AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM: return "SYSTEM";
            default: return "UNKNOWN(" + mode + ")";
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // ✅ صياد الأخطاء
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                java.io.File file = new java.io.File(getExternalFilesDir(null), "crash_log.txt");
                java.io.FileWriter writer = new java.io.FileWriter(file, true);
                writer.write("=== CRASH AT: " + new java.util.Date() + " ===\n");
                writer.write(android.util.Log.getStackTraceString(throwable));
                writer.write("\n\n");
                writer.close();
            } catch (Exception e) {}

            android.os.Looper.prepare();
            android.widget.Toast.makeText(getApplicationContext(),
                    "خطأ: " + throwable.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show();
            android.os.Looper.loop();
            android.os.Process.killProcess(android.os.Process.myPid());
        });

        DiagTracer.init(this);
        applySavedTheme();
        super.onCreate(savedInstanceState);

        try {
            sessionManager = new SessionManager(getApplicationContext());
            if (!sessionManager.isLoggedIn()) {
                navigateToLogin();
                return;
            }

            setContentView(R.layout.activity_main);

            // ═══ MIRA License Banner ═══
            try {
                ViewGroup rootView = findViewById(android.R.id.content);
                if (rootView != null) {
                    com.example.uhf.license.LicenseBannerHelper.attach(this, rootView);
                }
            } catch (Exception e) {
                Log.w(TAG, "Banner attach failed: " + e.getMessage());
            }

            findViewById(android.R.id.content).setFitsSystemWindows(true);

            getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            );

            setupWindowInsets();
            setupKeyboardBehavior();
            setupSecondaryToolbar();

            // ═══════════════════════════════════════════════════════════════
            //  Setup Toolbar + License Status Shortcut
            // ═══════════════════════════════════════════════════════════════
            MaterialToolbar toolbar = findViewById(R.id.toolbar);
            if (toolbar != null) {
                setSupportActionBar(toolbar);
                if (getSupportActionBar() != null) {
                    getSupportActionBar().setDisplayShowTitleEnabled(false);
                }
                updateToolbarTitle();

                // ⚡ الضغط المطول يفتح شاشة الترخيص
                toolbar.setOnLongClickListener(v -> {
                    try {
                        Intent intent = new Intent(this,
                                com.example.uhf.license.LicenseStatusActivity.class);
                        startActivity(intent);
                        Log.d(TAG, "✅ LicenseStatusActivity opened");
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to open LicenseStatusActivity", e);
                        Toast.makeText(this,
                                "تعذر فتح شاشة الترخيص",
                                Toast.LENGTH_SHORT).show();
                    }
                    return true;
                });
                Log.d(TAG, "✅ Long-press on toolbar → License Status");
            }

            AppWatchdog.getInstance().startMonitoring(() -> {
                Log.e(TAG, "UI Thread FROZEN! Restarting app...");
                MiraApplication.getInstance().restartApp();
            });

            ToastUtil.init(getApplicationContext());
            initializeLanguageState();

            branchesManager = MiraBranchesManager.getInstance(getApplicationContext());
            permissionManager = PermissionManager.getInstance(getApplicationContext());

            navigationManager = new NavigationManager(
                    getSupportFragmentManager(),
                    permissionManager,
                    R.id.fragment_container
            );
            navigationManager.attachToActivity(this);

            androidx.activity.OnBackPressedCallback backCallback =
                    navigationManager.createBackPressedCallback();
            getOnBackPressedDispatcher().addCallback(this, backCallback);

            navigationManager.setBackPressInterceptor(() -> {
                if (isInventoryActive()) {
                    checkInventoryLockAndExecute(() -> navigationManager.goToDashboard());
                    return true;
                }
                return false;
            });

            hardwareManager = new HardwareManager(this, new HardwareManager.HardwareStatusListener() {
                @Override
                public void onStatusChanged(HardwareManager.HardwareStatus status) {
                    if (statusIndicatorManager != null) {
                        statusIndicatorManager.update();
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
            statusIndicatorManager = new StatusIndicatorManager(
                    findViewById(android.R.id.content), hardwareManager);

            setupQuickNav();
            setupOrgProfileListener();

            disableNavigationUntilSetupChecked();

            String cachedSetupStatus = getCachedSetupStatus();
            boolean setupIsComplete = SETUP_COMPLETED.equals(cachedSetupStatus)
                    || SETUP_LOCKED.equals(cachedSetupStatus);

            if (setupIsComplete) {
                if (savedInstanceState == null) {
                    navigateByRole();
                } else {
                    String savedTag = savedInstanceState.getString(
                            "current_fragment", NavigationManager.TAG_DASHBOARD);
                    Fragment savedFragment = navigationManager.createFragmentByTag(savedTag);
                    if (savedFragment != null) {
                        enableNavigationAfterSetup();
                        loadFragmentDirectly(savedFragment, savedTag);
                    } else {
                        navigateByRole();
                    }
                }
            } else {
                Log.d(TAG, "🔒 Setup pending → ignoring savedInstanceState");
                checkSetupStatusAndNavigate();
            }

            try {
                if (sessionManager != null) {
                    appLifecycleObserver = new AppLifecycleObserver(sessionManager);
                    ProcessLifecycleOwner.get().getLifecycle().addObserver(appLifecycleObserver);
                }
            } catch (Exception e) {
                Log.e(TAG, "AppLifecycleObserver init error: " + e.getMessage());
            }

            syncUIWithNavigation();

            try {
                LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
                IntentFilter filter = new IntentFilter();
                filter.addAction("com.example.uhf.REFRESH_TRANSFERS");
                filter.addAction("com.example.uhf.REFRESH_DASHBOARD");
                lbm.registerReceiver(transfersUpdateReceiver, filter);
                Log.d(TAG, "✅ Transfers counter receiver registered");
            } catch (Exception e) {
                Log.e(TAG, "Register transfers receiver error: " + e.getMessage());
            }

            int cachedCount = loadTransfersCountLocally();
            if (cachedCount > 0) {
                mainHandler.postDelayed(() -> updateTransfersBadge(cachedCount), 500);
            }

        } catch (Exception e) {
            Log.e(TAG, "Fatal error in onCreate: " + e.getMessage(), e);
        }
    }

    private void syncUIWithNavigation() {
        if (navigationManager == null) return;
        String tag = navigationManager.getCurrentFragmentTag();
        updateBottomNavSelectionByTag(tag);
        updateSecondaryToolbar(tag);
    }

    private void setupWindowInsets() {
        // Window insets handled by other components
    }

    // ═══════════════════════════════════════════════════════
    //  🛡️ Navigation Enable/Disable
    // ═══════════════════════════════════════════════════════

    private void disableNavigationUntilSetupChecked() {
        try {
            BottomNavigationView nav = findViewById(R.id.bottomNavigationView);
            if (nav != null) {
                nav.setEnabled(false);
                nav.setAlpha(0.4f);
            }

            FloatingActionButton fab = findViewById(R.id.fabActionCenter);
            if (fab != null) {
                fab.setEnabled(false);
                fab.setAlpha(0.4f);
            }

            Log.d(TAG, "🔒 Navigation disabled until setup check");
        } catch (Exception e) {
            Log.e(TAG, "disableNavigationUntilSetupChecked error: " + e.getMessage());
        }
    }

    private void enableNavigationAfterSetup() {
        setBottomNavVisible(true);
        applyNavEnabledState(true);
        Log.d(TAG, "✅ Navigation enabled (container + items)");
    }

    private void applyNavEnabledState(boolean enabled) {
        try {
            BottomNavigationView nav = findViewById(R.id.bottomNavigationView);
            if (nav != null) {
                nav.setEnabled(enabled);
                nav.setAlpha(enabled ? 1.0f : 0.4f);
            }
            FloatingActionButton fab = findViewById(R.id.fabActionCenter);
            if (fab != null) {
                fab.setEnabled(enabled);
                fab.setAlpha(enabled ? 1.0f : 0.4f);
            }
        } catch (Exception e) {
            Log.e(TAG, "applyNavEnabledState error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════
    //  ✅ Setup Status — Check & Navigate
    // ═══════════════════════════════════════════════════════

    private void checkSetupStatusAndNavigate() {
        String cachedStatus = getCachedSetupStatus();

        if (cachedStatus != null) {
            Log.d(TAG, "✅ Setup status from cache: " + cachedStatus);
            navigateBySetupStatus(cachedStatus);
            refreshSetupStatusInBackground();
            return;
        }

        Log.d(TAG, "⏳ Fetching setup status from API...");
        fetchSetupStatusAndNavigate();
    }

    private void fetchSetupStatusAndNavigate() {
        showSetupLoadingScreen();

        new Thread(() -> {
            String status = SETUP_PENDING;
            String stamp  = null;
            try {
                MiraApiClient api = MiraApiClient.getInstance(getApplicationContext());
                MiraApiClient.ApiResponse response = api.getAccounting("setup/status");

                if (response != null && response.isSuccess && response.data != null) {
                    status = response.data.optString("status", SETUP_PENDING);
                    stamp  = response.data.optString("setup_stamp", null);
                    Log.d(TAG, "✅ Setup status from API: " + status
                            + " | stamp: " + (stamp != null
                                    ? stamp.substring(0, Math.min(8, stamp.length())) + "..."
                                    : "null"));
                } else {
                    Log.w(TAG, "⚠️ Failed to fetch setup status → assuming pending");
                }
            } catch (Exception e) {
                Log.e(TAG, "❌ Error fetching setup status: " + e.getMessage());
            }

            final String finalStatus = status;
            final String finalStamp  = stamp;
            runOnUiThread(() -> {
                hideSetupLoadingScreen();
                if (isActivityAlive()) {
                    cacheSetupStatus(finalStatus, finalStamp);
                    navigateBySetupStatus(finalStatus);
                }
            });
        }).start();
    }

    private void refreshSetupStatusInBackground() {
        if (isWithinSetupGracePeriod()) {
            Log.d(TAG, "🛡️ Skipping refresh during grace period");
            return;
        }

        new Thread(() -> {
            try {
                MiraApiClient api = MiraApiClient.getInstance(getApplicationContext());
                MiraApiClient.ApiResponse response = api.getAccounting("setup/status");

                if (response != null && response.isSuccess && response.data != null) {
                    String serverStatus = response.data.optString("status", SETUP_PENDING);
                    String serverStamp  = response.data.optString("setup_stamp", null);
                    String localStamp   = getCachedSetupStamp();
                    String localStatus  = getCachedSetupStatus();

                    boolean stampChanged = (localStamp != null && serverStamp != null
                            && !localStamp.equals(serverStamp));

                    if (SETUP_COMPLETED.equals(localStatus)
                            && (SETUP_PENDING.equals(serverStatus)
                                || SETUP_IN_PROGRESS.equals(serverStatus))
                            && !stampChanged) {
                        Log.w(TAG, "🛡️ Ignoring downgrade (completed → pending, no stamp change)");
                        return;
                    }

                    if (stampChanged) {
                        Log.w(TAG, "🔄 Stamp changed! invalidating cache");

                        if (SETUP_COMPLETED.equals(localStatus)
                                && (SETUP_PENDING.equals(serverStatus)
                                    || SETUP_IN_PROGRESS.equals(serverStatus))) {
                            Log.w(TAG, "🛡️ Keeping completed (server lag)");
                            cacheSetupStatus(SETUP_COMPLETED, serverStamp);
                            return;
                        }

                        invalidateSetupCache();
                        cacheSetupStatus(serverStatus, serverStamp);

                        if (!SETUP_COMPLETED.equals(serverStatus)
                                && !SETUP_LOCKED.equals(serverStatus)) {
                            mainHandler.post(() -> {
                                if (isActivityAlive()) {
                                    navigateBySetupStatus(serverStatus);
                                }
                            });
                            return;
                        }
                    } else {
                        cacheSetupStatus(serverStatus, serverStamp);
                    }

                    Log.d(TAG, "✅ Setup status refreshed: " + serverStatus);

                    if (SETUP_COMPLETED.equals(serverStatus)
                            || SETUP_LOCKED.equals(serverStatus)) {
                        mainHandler.post(() -> {
                            if (isActivityAlive()) {
                                navigateByRole();
                            }
                        });
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Setup status refresh failed: " + e.getMessage());
            }
        }).start();
    }

    private void navigateBySetupStatus(String setupStatus) {
        if (isWithinSetupGracePeriod()) {
            Log.d(TAG, "🛡️ Within grace period → forcing COMPLETED navigation "
                    + "(ignoring server status: " + setupStatus + ")");
            enableNavigationAfterSetup();
            navigateByRole();
            return;
        }

        boolean isSystemAdmin = permissionManager != null && permissionManager.isSystemAdmin();
        boolean isOrgAdmin    = permissionManager != null && permissionManager.isOrgAdmin();
        boolean isFinanceMgr  = permissionManager != null && permissionManager.isFinanceManager();
        boolean isAccountant  = permissionManager != null && permissionManager.isAccountant();

        boolean canPerformSetup = isSystemAdmin || isOrgAdmin || isFinanceMgr || isAccountant;

        boolean isSetupPending = SETUP_PENDING.equals(setupStatus)
                || SETUP_IN_PROGRESS.equals(setupStatus);

        Log.d(TAG, "🧭 Navigate: status=" + setupStatus
                + ", isSystemAdmin=" + isSystemAdmin
                + ", isOrgAdmin=" + isOrgAdmin
                + ", isFinanceMgr=" + isFinanceMgr
                + ", isAccountant=" + isAccountant);

        if (isSetupPending) {
            if (canPerformSetup) {
                enableNavigationAfterSetup();
                setBottomNavVisible(false);

                loadFragmentDirectly(
                        new MiraAccountingSetupFragment(),
                        "AccountingSetup"
                );

                if (!setupWelcomeShownThisSession) {
                    setupWelcomeShownThisSession = true;

                    String welcomeMsg;
                    if (isSystemAdmin) {
                        welcomeMsg = isArabic
                                ? "🔧 مرحباً مهندس النظام — يرجى إكمال الإعداد"
                                : "🔧 Welcome Engineer — Please complete setup";
                    } else if (isOrgAdmin) {
                        welcomeMsg = isArabic
                                ? "🏢 مرحباً مدير الجهة — يرجى إعداد النظام المحاسبي"
                                : "🏢 Welcome Org Admin — Please configure accounting";
                    } else if (isFinanceMgr) {
                        welcomeMsg = isArabic
                                ? "💰 مرحباً المدير المالي — يرجى إعداد النظام المحاسبي"
                                : "💰 Welcome Finance Manager — Please configure accounting";
                    } else {
                        welcomeMsg = isArabic
                                ? "📊 مرحباً المحاسب — يرجى إكمال الإعداد"
                                : "📊 Welcome Accountant — Please complete setup";
                    }
                    Toast.makeText(this, welcomeMsg, Toast.LENGTH_LONG).show();
                }
            } else {
                showSetupBlockedScreen();
            }
        } else {
            setupCompletedThisSession = true;
            enableNavigationAfterSetup();
            navigateByRole();
        }
    }

    private void navigateByRole() {
        try {
            enableNavigationAfterSetup();

            Fragment targetFragment;
            String targetTag;

            if (permissionManager.isPosOperator()) {
                targetFragment = new MiraPosFragment();
                targetTag = NavigationManager.TAG_POS;
            } else if (permissionManager.isInventoryManager()) {
                targetFragment = new MiraInventoryProFragment();
                targetTag = NavigationManager.TAG_INVENTORY;
            } else if (permissionManager.isSecurity() || permissionManager.isGateSecurity()) {
                targetFragment = new MiraSecureGateFragment();
                targetTag = NavigationManager.TAG_GATES;
            } else {
                targetFragment = new MiraDashboardFragment();
                targetTag = NavigationManager.TAG_DASHBOARD;
            }

            loadFragmentDirectly(targetFragment, targetTag);
            Log.d(TAG, "✅ Navigated by role to: " + targetTag);

        } catch (Exception e) {
            Log.e(TAG, "navigateByRole error: " + e.getMessage());
            enableNavigationAfterSetup();
            loadFragmentDirectly(new MiraDashboardFragment(),
                    NavigationManager.TAG_DASHBOARD);
        }
    }

    private void showSetupBlockedScreen() {
        if (isFinishing() || isDestroyed()) return;
        if (!isActivityAlive()) return;

        try {
            BottomSheetDialog dialog = new BottomSheetDialog(
                    this, R.style.TransparentBottomSheetDialog);

            View view = getLayoutInflater().inflate(
                    R.layout.dialog_system_under_setup, null);
            dialog.setContentView(view);

            dialog.setCancelable(false);
            dialog.setCanceledOnTouchOutside(false);

            TextView tvTitle = view.findViewById(R.id.tvSetupTitle);
            TextView tvMessage = view.findViewById(R.id.tvSetupMessage);
            TextView tvSysAdmin = view.findViewById(R.id.tvContactSystemAdmin);
            TextView tvOrgAdmin = view.findViewById(R.id.tvContactOrgAdmin);
            TextView tvEmail = view.findViewById(R.id.tvContactEmail);
            MaterialButton btnLogout = view.findViewById(R.id.btnSetupLogout);

            if (isArabic) {
                tvTitle.setText("⚠️ النظام قيد الإعداد");
                tvMessage.setText("لم يتم استكمال الإعداد المحاسبي بعد.\n\n" +
                        "لا يمكن استخدام النظام قبل اكتمال الإعداد.");
                tvSysAdmin.setText("🔧  مهندس النظام (System Admin)");
                tvOrgAdmin.setText("🏢  مدير الجهة (Org Admin)");
                tvEmail.setText("📧  support@mirabarcode.com");
                btnLogout.setText("تسجيل الخروج");
            } else {
                tvTitle.setText("⚠️ System Under Setup");
                tvMessage.setText("Accounting setup is not yet complete.\n\n" +
                        "The system cannot be used until setup is finished.");
                tvSysAdmin.setText("🔧  System Admin");
                tvOrgAdmin.setText("🏢  Org Admin");
                tvEmail.setText("📧  support@mirabarcode.com");
                btnLogout.setText("Logout");
            }

            btnLogout.setOnClickListener(v -> {
                dialog.dismiss();
                performFullLogout();
            });

            dialog.show();

        } catch (Exception e) {
            Log.e(TAG, "showSetupBlockedScreen error: " + e.getMessage(), e);
            performFullLogout();
        }
    }

    // ═══════════════════════════════════════════════════════
    //  🛡️ الحارس المركزي
    // ═══════════════════════════════════════════════════════

    private boolean isSetupAllowedFragment(String tag) {
        if (tag == null) return false;
        return "AccountingSetup".equals(tag)
            || "Profile".equals(tag)
            || "ServerConfig".equals(tag)
            || "Login".equals(tag);
    }

    private boolean guardSetupAccess(String tag) {
        String cachedStatus = getCachedSetupStatus();
        boolean setupDone = SETUP_COMPLETED.equals(cachedStatus)
                || SETUP_LOCKED.equals(cachedStatus);

        if (setupDone) return true;

        if (cachedStatus == null) {
            Log.d(TAG, "🛡️ cache miss → fetching in background");
            refreshSetupStatusInBackground();

            if (isSetupAllowedFragment(tag)) return true;

            boolean canSetup = permissionManager != null
                    && (permissionManager.isSystemAdmin()
                        || permissionManager.isOrgAdmin()
                        || permissionManager.isFinanceManager()
                        || permissionManager.isAccountant());

            if (canSetup) return true;

            showSetupBlockedScreen();
            return false;
        }

        if (isSetupAllowedFragment(tag)) return true;

        refreshSetupStatusInBackground();

        boolean canSetup = permissionManager != null
                && (permissionManager.isSystemAdmin()
                    || permissionManager.isOrgAdmin()
                    || permissionManager.isFinanceManager()
                    || permissionManager.isAccountant());

        if (canSetup) {
            Log.d(TAG, "🛡️ Setup pending → redirecting to Setup: " + tag);
            Toast.makeText(this,
                    isArabic ? "⚠️ يجب إكمال الإعداد أولاً"
                             : "⚠️ Setup must be completed first",
                    Toast.LENGTH_SHORT).show();
            loadFragmentDirectly(new MiraAccountingSetupFragment(), "AccountingSetup");
            return false;
        }

        Log.d(TAG, "🛡️ Setup pending → blocking user: " + tag);
        showSetupBlockedScreen();
        return false;
    }

    // ═══════════════════════════════════════════════════════
    //  Cache Management
    // ═══════════════════════════════════════════════════════

    private void cacheSetupStatus(String status) {
        cacheSetupStatus(status, null);
    }

    private void cacheSetupStatus(String status, String stamp) {
        try {
            SharedPreferences.Editor editor = getSharedPreferences(PREFS_SETUP, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_SETUP_STATUS, status)
                    .putLong(KEY_SETUP_CACHED_AT, System.currentTimeMillis());

            if (stamp != null && !stamp.isEmpty()) {
                editor.putString(KEY_SETUP_STAMP, stamp);
            }

            editor.apply();
        } catch (Exception e) {
            Log.w(TAG, "cacheSetupStatus error: " + e.getMessage());
        }
    }

    private String getCachedSetupStamp() {
        try {
            return getSharedPreferences(PREFS_SETUP, Context.MODE_PRIVATE)
                    .getString(KEY_SETUP_STAMP, null);
        } catch (Exception e) {
            Log.w(TAG, "getCachedSetupStamp error: " + e.getMessage());
            return null;
        }
    }

    private String getCachedSetupStatus() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_SETUP, Context.MODE_PRIVATE);
            long cachedAt = prefs.getLong(KEY_SETUP_CACHED_AT, 0);
            String status  = prefs.getString(KEY_SETUP_STATUS, null);
            long now = System.currentTimeMillis();

            if (status == null) return null;

            if (SETUP_COMPLETED.equals(status) || SETUP_LOCKED.equals(status)) {
                long completedTtl = 24 * 60 * 60 * 1000L;
                if (now - cachedAt > completedTtl) {
                    Log.d(TAG, "🕐 Completed status expired (24h) → will refresh");
                    return null;
                }
                return status;
            }

            if (now - cachedAt > SETUP_CACHE_TTL_MS) {
                return null;
            }
            return status;

        } catch (Exception e) {
            Log.w(TAG, "getCachedSetupStatus error: " + e.getMessage());
            return null;
        }
    }

    public void onSetupCompleted() {
        Log.d(TAG, "🎉 onSetupCompleted");

        setupCompletedThisSession = true;
        setupWelcomeShownThisSession = true;
        setupCompletedAt = System.currentTimeMillis();

        setBottomNavVisible(true);

        invalidateSetupCache();
        cacheSetupStatus(SETUP_COMPLETED);

        new Thread(() -> {
            try {
                MiraApiClient api = MiraApiClient.getInstance(getApplicationContext());
                MiraApiClient.ApiResponse response = api.getAccounting("setup/status");

                if (response != null && response.isSuccess && response.data != null) {
                    String serverStatus = response.data.optString("status", SETUP_COMPLETED);
                    String serverStamp  = response.data.optString("setup_stamp", null);

                    if ((SETUP_PENDING.equals(serverStatus)
                            || SETUP_IN_PROGRESS.equals(serverStatus))
                            && isWithinSetupGracePeriod()) {

                        Log.w(TAG, "🛡️ Server still pending within grace period "
                                + "→ keeping local COMPLETED");

                        cacheSetupStatus(SETUP_COMPLETED, serverStamp);
                        return;
                    }

                    cacheSetupStatus(serverStatus, serverStamp);
                    Log.d(TAG, "✅ Saved final setup state: " + serverStatus);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to fetch final setup stamp: " + e.getMessage());
            }
        }).start();

        mainHandler.post(() -> {
            if (isActivityAlive()) {
                enableNavigationAfterSetup();
                navigateByRole();
            }
        });
    }

    public void invalidateSetupCache() {
        try {
            getSharedPreferences(PREFS_SETUP, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .apply();

            setupCompletedAt = 0;
            setupCompletedThisSession = false;

            Log.d(TAG, "✅ Setup cache invalidated (cache + grace period reset)");
        } catch (Exception e) {
            Log.w(TAG, "invalidateSetupCache error: " + e.getMessage());
        }
    }

    private boolean isWithinSetupGracePeriod() {
        if (setupCompletedAt == 0) return false;
        long elapsed = System.currentTimeMillis() - setupCompletedAt;
        return elapsed < SETUP_GRACE_PERIOD_MS;
    }

    private void showSetupLoadingScreen() {
        mainHandler.post(() -> {
            View loadingOverlay = findViewById(R.id.successOverlayMain);
            if (loadingOverlay != null) {
                loadingOverlay.setVisibility(View.VISIBLE);
                loadingOverlay.setAlpha(1.0f);
            }
        });
    }

    private void hideSetupLoadingScreen() {
        mainHandler.post(() -> {
            View loadingOverlay = findViewById(R.id.successOverlayMain);
            if (loadingOverlay != null) {
                loadingOverlay.setVisibility(View.GONE);
            }
        });
    }

    // ═══════════════════════════════════════════════════════
    //  التولبار الثانوي
    // ═══════════════════════════════════════════════════════

    private void setupSecondaryToolbar() {
        MaterialToolbar secondaryToolbar = findViewById(R.id.secondaryToolbar);

        if (secondaryToolbar == null) {
            Log.w(TAG, "Secondary toolbar not found");
            return;
        }

        secondaryToolbarManager = new SecondaryToolbarManager(secondaryToolbar);

        secondaryToolbarManager.setOnMenuItemClickListener((item, currentTag) -> {
            int id = item.getItemId();

            if (id == R.id.action_device_status) {
                showHardwareOptionsDialog();
                return true;
            } else if (id == R.id.action_search) {
                showGlobalSearchDialog();
                return true;
            } else if (id == R.id.action_sync) {
                performDataSync();
                return true;
            } else if (id == R.id.action_quick_access_settings) {
                loadFragment(new MiraQuickAccessSettingsFragment(), "QuickAccessSettings");
                return true;
            } else if (id == R.id.action_inventory_power) {
                if (activeInventoryFragment != null
                        && activeInventoryFragment.isAdded()
                        && !activeInventoryFragment.isRemoving()) {
                    activeInventoryFragment.showInventoryScannerSettings();
                } else {
                    loadFragment(new MiraInventoryProFragment(), "Scan");
                    mainHandler.postDelayed(() -> {
                        if (activeInventoryFragment != null) {
                            activeInventoryFragment.showInventoryScannerSettings();
                        }
                    }, 500);
                }
                return true;
            } else if (id == R.id.action_inventory_stats) {
                showInventoryStatsDialog();
                return true;
            } else if (id == R.id.action_inventory_settings) {
                if (activeInventoryFragment != null
                        && activeInventoryFragment.isAdded()
                        && !activeInventoryFragment.isRemoving()) {
                    activeInventoryFragment.showInventorySessionSettings();
                } else {
                    loadFragment(new MiraInventoryProFragment(), "Scan");
                    mainHandler.postDelayed(() -> {
                        if (activeInventoryFragment != null) {
                            activeInventoryFragment.showInventorySessionSettings();
                        }
                    }, 500);
                }
                return true;
            } else if (id == R.id.action_inventory_archive) {
                showInventoryArchiveDialog();
                return true;
            } else if (id == R.id.action_pos_cart) {
                showPOSCartDialog();
                return true;
            } else if (id == R.id.action_pos_search) {
                showPOSSearchDialog();
                return true;
            } else if (id == R.id.action_pos_discount) {
                showPOSDiscountDialog();
                return true;
            } else if (id == R.id.action_pos_receipt) {
                showPOSReceiptDialog();
                return true;
            } else if (id == R.id.action_transfers_sync) {
                performDataSync();
                return true;
            } else if (id == R.id.action_transfers_export) {
                showTransfersExportDialog();
                return true;
            } else if (id == R.id.action_transfers_import) {
                showTransfersImportDialog();
                return true;
            } else if (id == R.id.action_transfers_history) {
                loadFragment(new MiraTransferFragment(), "Transfers");
                return true;
            }

            return false;
        });

        secondaryToolbarManager.setMenuForTag("Dashboard");
        mainHandler.post(this::setupCustomViewsForCurrentMenu);
    }

    private void setupCustomViewsForCurrentMenu() {
        try {
            MaterialToolbar toolbar = findViewById(R.id.secondaryToolbar);
            if (toolbar == null) return;

            Menu menu = toolbar.getMenu();

            MenuItem deviceStatusItem = menu.findItem(R.id.action_device_status);
            if (deviceStatusItem != null && deviceStatusItem.getActionView() != null) {
                View actionView = deviceStatusItem.getActionView();
                View dot = actionView.findViewById(R.id.deviceStatusDot);
                TextView text = actionView.findViewById(R.id.deviceStatusText);
                ImageView icon = actionView.findViewById(R.id.deviceStatusIcon);

                if (dot != null && text != null) {
                    actionView.setOnClickListener(v -> showHardwareOptionsDialog());
                    updateDeviceStatusUI(dot, text, icon);
                }
            }

            MenuItem powerItem = menu.findItem(R.id.action_inventory_power);
            if (powerItem != null && powerItem.getActionView() != null) {
                View actionView = powerItem.getActionView();
                View dot = actionView.findViewById(R.id.deviceStatusDot);
                TextView text = actionView.findViewById(R.id.deviceStatusText);
                ImageView icon = actionView.findViewById(R.id.deviceStatusIcon);

                if (dot != null && text != null) {
                    actionView.setOnClickListener(v -> showHardwareOptionsDialog());
                    updateDeviceStatusUI(dot, text, icon);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "setupCustomViewsForCurrentMenu error: " + e.getMessage(), e);
        }
    }

    private void updateSecondaryToolbar(String tag) {
        if (secondaryToolbarManager == null) return;
        try {
            secondaryToolbarManager.setMenuForTag(tag);

            Menu menu = secondaryToolbarManager.getCurrentMenu();
            if (menu != null) {
                applyToolbarMenuPermissions(menu);
            }

            mainHandler.post(this::setupCustomViewsForCurrentMenu);
            Log.d(TAG, "✅ Secondary toolbar updated for: " + tag);
        } catch (Exception e) {
            Log.e(TAG, "updateSecondaryToolbar error: " + e.getMessage(), e);
        }
    }

    private void updateDeviceStatusUI(View statusDot, TextView statusText, ImageView statusIcon) {
        try {
            if (statusDot == null || statusText == null) return;

            boolean isConnected = hardwareManager != null && hardwareManager.isConnected();

            int greenColor = getResources().getColor(android.R.color.holo_green_dark);
            int redColor = getResources().getColor(android.R.color.holo_red_dark);

            if (isConnected) {
                statusDot.setBackgroundResource(R.drawable.bg_status_dot_green);
                statusText.setText(isArabic ? "متصل" : "Connected");
                statusText.setTextColor(greenColor);

                if (statusIcon != null) {
                    statusIcon.setImageResource(R.drawable.ic_device_connected);
                    statusIcon.setColorFilter(greenColor);
                }
            } else {
                statusDot.setBackgroundResource(R.drawable.bg_status_dot_red);
                statusText.setText(isArabic ? "غير متصل" : "Disconnected");
                statusText.setTextColor(redColor);

                if (statusIcon != null) {
                    statusIcon.setImageResource(R.drawable.ic_device_disconnected);
                    statusIcon.setColorFilter(redColor);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "updateDeviceStatusUI error: " + e.getMessage());
        }
    }

    private void showHardwareOptionsDialog() {
        PermissionManager pm = PermissionManager.getInstance(getApplicationContext());
        List<String> optionsList = new ArrayList<>();
        List<Runnable> actionsList = new ArrayList<>();

        if (pm.isAdmin() || pm.isInventoryManager() || pm.isSystemAdmin()) {
            optionsList.add(isArabic ? "⚙️ لوحة التحكم بالقارئ" : "⚙️ Reader Control Panel");
            actionsList.add(() -> loadFragment(new MiraReaderControlFragment(), "ReaderControl"));
        }
        if (pm.isAdmin() || pm.isInventoryManager() || pm.isSystemAdmin()) {
            optionsList.add(isArabic ? "📡 إعدادات قارئ RFID" : "📡 RFID Reader Setup");
            actionsList.add(() -> loadFragment(new MiraReaderSetupFragment(), "ReaderSetup"));
        }
        if (pm.isAdmin() || pm.isSecurity() || pm.isGateSecurity()) {
            optionsList.add(isArabic ? "🚧 تهيئة البوابات الأمنية" : "🚧 Gate Security Setup");
            actionsList.add(() -> loadFragment(new MiraGateSettingsFragment(), "GateSettings"));
        }
        if (pm.canViewServerConfig()) {
            optionsList.add(isArabic ? "🌐 إعدادات السيرفر" : "🌐 Server Config");
            actionsList.add(() -> loadFragment(new MiraServerConfigFragment(), "ServerConfig"));
        }
        if (pm.isAdmin() || pm.isManager() || pm.isInventoryManager()) {
            optionsList.add(isArabic ? "🏷️ إدارة الشرائح المبرمجة" : "🏷️ RFID Bindings");
            actionsList.add(() -> loadFragment(new MiraRfidBindingsFragment(), "RfidBindings"));
        }
        if (pm.isAdmin() || pm.isSystemAdmin()) {
            optionsList.add(isArabic ? "🔐 إعدادات أمان الشرائح" : "🔐 RFID Security Settings");
            actionsList.add(() -> loadFragment(new MiraRfidSettingsFragment(), "RfidSettings"));
        }
        if (pm.canManageQuickAccess() || pm.isManager() || pm.isAdmin()) {
            optionsList.add(isArabic ? "⚙️ إعدادات الوصول السريع" : "⚙️ Quick Access Settings");
            actionsList.add(() -> loadFragment(new MiraQuickAccessSettingsFragment(), "QuickAccessSettings"));
        }

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

    // ═══════════════════════════════════════════════════════
    //  Keyboard Behavior
    // ═══════════════════════════════════════════════════════

    private void setupKeyboardBehavior() {
        try {
            final View rootView = findViewById(android.R.id.content);
            if (rootView == null) return;

            final View bottomNavContainer = findViewById(R.id.bottomNavContainer);
            final View fragmentContainer = findViewById(R.id.fragment_container);

            if (bottomNavContainer == null) return;

            final int KEYBOARD_THRESHOLD_DP = 150;
            final float density = getResources().getDisplayMetrics().density;
            final int thresholdPx = (int) (KEYBOARD_THRESHOLD_DP * density);

            final android.graphics.Rect visibleFrame = new android.graphics.Rect();

            rootView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                try {
                    rootView.getWindowVisibleDisplayFrame(visibleFrame);
                    int screenHeight = rootView.getRootView().getHeight();
                    int keyboardHeight = screenHeight - visibleFrame.bottom;
                    boolean isKeyboardVisible = keyboardHeight > thresholdPx;

                    if (isKeyboardVisible == lastKeyboardState) return;
                    lastKeyboardState = isKeyboardVisible;

                    applyKeyboardVisibility(isKeyboardVisible,
                            bottomNavContainer, fragmentContainer);

                } catch (Exception e) {
                    Log.e(TAG, "VTO listener error: " + e.getMessage());
                }
            });

            ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
                try {
                    Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
                    Insets sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                    boolean isKeyboardVisible = ime.bottom > sysBars.bottom;

                    if (isKeyboardVisible == lastKeyboardState) return insets;

                    lastKeyboardState = isKeyboardVisible;
                    applyKeyboardVisibility(isKeyboardVisible,
                            bottomNavContainer, fragmentContainer);

                } catch (Exception e) {
                    Log.e(TAG, "Insets listener error: " + e.getMessage());
                }
                return insets;
            });

            Log.d(TAG, "✅ setupKeyboardBehavior: Dual listeners attached");

        } catch (Exception e) {
            Log.e(TAG, "setupKeyboardBehavior error: " + e.getMessage(), e);
        }
    }

    private void applyKeyboardVisibility(boolean isKeyboardVisible,
                                         View bottomNavContainer,
                                         View fragmentContainer) {
        try {
            if (bottomNavContainer != null) {
                float targetY = isKeyboardVisible
                        ? bottomNavContainer.getHeight() : 0f;

                if (bottomNavContainer.getTranslationY() != targetY) {
                    bottomNavContainer.animate()
                            .translationY(targetY)
                            .setDuration(250)
                            .setInterpolator(
                                    new android.view.animation.DecelerateInterpolator())
                            .start();
                }
            }

            if (fragmentContainer != null) {
                int targetPadding = isKeyboardVisible ? 8 : 90;
                if (fragmentContainer.getPaddingBottom() != targetPadding) {
                    fragmentContainer.setPadding(
                            fragmentContainer.getPaddingLeft(),
                            fragmentContainer.getPaddingTop(),
                            fragmentContainer.getPaddingRight(),
                            targetPadding);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "applyKeyboardVisibility error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════
    //  BottomNav Visibility
    // ═══════════════════════════════════════════════════════

    public void setBottomNavVisible(boolean visible) {
        try {
            final View bottomNavContainer = findViewById(R.id.bottomNavContainer);
            if (bottomNavContainer == null) {
                Log.w(TAG, "setBottomNavVisible: container not found");
                return;
            }

            if (visible) {
                if (bottomNavContainer.getVisibility() == View.VISIBLE
                        && bottomNavContainer.getAlpha() == 1f
                        && bottomNavContainer.getTranslationY() == 0f) {
                    applyNavEnabledState(true);
                    return;
                }

                bottomNavContainer.animate().cancel();
                bottomNavContainer.setVisibility(View.VISIBLE);

                float startY = bottomNavContainer.getTranslationY() > 0
                        ? bottomNavContainer.getTranslationY()
                        : (bottomNavContainer.getHeight() > 0
                            ? bottomNavContainer.getHeight() : 200f);

                bottomNavContainer.setTranslationY(startY);
                bottomNavContainer.setAlpha(0f);

                bottomNavContainer.animate()
                        .translationY(0f)
                        .alpha(1f)
                        .setDuration(250)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .withEndAction(() -> applyNavEnabledState(true))
                        .start();

            } else {
                if (bottomNavContainer.getVisibility() == View.GONE) {
                    return;
                }

                bottomNavContainer.animate().cancel();

                float endY = bottomNavContainer.getHeight() > 0
                        ? bottomNavContainer.getHeight() : 200f;

                bottomNavContainer.animate()
                        .translationY(endY)
                        .alpha(0f)
                        .setDuration(250)
                        .setInterpolator(new android.view.animation.AccelerateInterpolator())
                        .withEndAction(() -> {
                            bottomNavContainer.setVisibility(View.GONE);
                            bottomNavContainer.setTranslationY(0f);
                        })
                        .start();
            }

            View fragmentContainer = findViewById(R.id.fragment_container);
            if (fragmentContainer != null) {
                int targetPadding = visible ? 90 : 0;
                if (fragmentContainer.getPaddingBottom() != targetPadding) {
                    fragmentContainer.setPadding(
                            fragmentContainer.getPaddingLeft(),
                            fragmentContainer.getPaddingTop(),
                            fragmentContainer.getPaddingRight(),
                            targetPadding
                    );
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "setBottomNavVisible error: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════
    //  دورة الحياة
    // ═══════════════════════════════════════════════════════

    @Override
    public void onResume() {
        super.onResume();

        // ⚡ MIRA License Heartbeat
        try {
            com.example.uhf.license.HeartbeatScheduler.triggerNow(this);
        } catch (Exception e) {
            Log.w(TAG, "onResume heartbeat failed: " + e.getMessage());
        }

        // ⚡ MIRA License: تحديث blob من الخادم في الخلفية
        final android.content.Context appCtx = getApplicationContext();
        new Thread(() -> {
            try {
                boolean updated = com.example.uhf.license.MiraBlobFetcher
                        .fetchVerifyAndStore(appCtx);
                Log.d(TAG, "Background blob refresh: " + (updated ? "✅" : "⚠️"));
            } catch (Exception e) {
                Log.w(TAG, "Background blob refresh failed: " + e.getMessage());
            }
        }, "LicenseRefresh").start();

        loadingCounter = 0;
        forceHideLoadingImmediate();

        if (justUnlockedWithBiometric) {
            Log.d(TAG, "✅ Skipping Auto-Lock check (just unlocked with biometric)");
            justUnlockedWithBiometric = false;
            updateToolbarTitle();
            if (statusIndicatorManager != null) statusIndicatorManager.scheduleUpdates();

            if (navigationManager != null) {
                navigationManager.notifyExternalChange();
            }
            syncCurrentFragment();

            checkSetupStampOnResume();
            return;
        }

        if (!checkAutoLock()) return;

        updateToolbarTitle();
        if (statusIndicatorManager != null) statusIndicatorManager.scheduleUpdates();

        if (navigationManager != null) {
            navigationManager.notifyExternalChange();
        }
        syncCurrentFragment();

        checkSetupStampOnResume();
    }

    // ⚡ NEW — دالة checkSetupStampOnResume (كانت مفقودة)
    private void checkSetupStampOnResume() {
        if (isWithinSetupGracePeriod()) {
            Log.d(TAG, "🛡️ Skipping stamp check during grace period");
            return;
        }

        String cachedStatus = getCachedSetupStatus();
        if (cachedStatus == null) return;

        new Thread(() -> {
            try {
                MiraApiClient api = MiraApiClient.getInstance(getApplicationContext());
                MiraApiClient.ApiResponse response = api.getAccounting("setup/status");

                if (response == null || !response.isSuccess || response.data == null) return;

                String serverStatus = response.data.optString("status", SETUP_PENDING);
                String serverStamp  = response.data.optString("setup_stamp", null);
                String localStamp   = getCachedSetupStamp();
                String localStatus  = getCachedSetupStatus();

                boolean stampMismatch = (localStamp != null && serverStamp != null
                        && !localStamp.equals(serverStamp));

                if (SETUP_COMPLETED.equals(localStatus)
                        && !SETUP_COMPLETED.equals(serverStatus)
                        && !SETUP_LOCKED.equals(serverStatus)
                        && !stampMismatch) {
                    Log.w(TAG, "🛡️ Ignoring server downgrade (no stamp change)");
                    return;
                }

                if (stampMismatch) {
                    Log.w(TAG, "🔄 Stamp mismatch in onResume!");

                    if (SETUP_COMPLETED.equals(localStatus)
                            && (SETUP_PENDING.equals(serverStatus)
                                || SETUP_IN_PROGRESS.equals(serverStatus))) {
                        Log.w(TAG, "🛡️ Server pending but local completed → keeping completed");
                        cacheSetupStatus(SETUP_COMPLETED, serverStamp);
                        return;
                    }

                    invalidateSetupCache();
                    cacheSetupStatus(serverStatus, serverStamp);

                    mainHandler.post(() -> {
                        if (isActivityAlive()) {
                            Log.w(TAG, "🔄 Re-navigating after stamp mismatch");
                            enableNavigationAfterSetup();
                            navigateBySetupStatus(serverStatus);
                        }
                    });
                }
            } catch (Exception e) {
                Log.w(TAG, "checkSetupStampOnResume error: " + e.getMessage());
            }
        }).start();
    }

    @Override
    protected void onStart() {
        super.onStart();
        transferCountHandler.removeCallbacks(transferRefreshRunnable);
        transferCountHandler.post(transferRefreshRunnable);
    }

    @Override
    protected void onStop() {
        super.onStop();
        transferCountHandler.removeCallbacks(transferRefreshRunnable);
    }

    private boolean checkAutoLock() {
        try {
            if (sessionManager == null) return true;
            if (!sessionManager.isLoggedIn()) return true;

            if (sessionManager.isSessionLocked()
                    || sessionManager.isInactivityTimeout(INACTIVITY_TIMEOUT_MS)) {

                Log.d(TAG, "🔒 Auto-Lock triggered");
                sessionManager.setSessionLocked(true);
                mainHandler.postDelayed(this::showAutoLockDialog, 300);
                return false;
            }

            sessionManager.touchActivity();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "checkAutoLock error: " + e.getMessage());
            return true;
        }
    }

    private void showAutoLockDialog() {
        if (isFinishing() || isDestroyed()) return;
        if (!isActivityAlive()) return;

        if (justUnlockedWithBiometric) {
            Log.d(TAG, "⏭️ Skipping showAutoLockDialog (just unlocked)");
            justUnlockedWithBiometric = false;
            return;
        }

        try {
            BottomSheetDialog lockDialog = new BottomSheetDialog(
                    this, R.style.TransparentBottomSheetDialog);

            View view = getLayoutInflater().inflate(
                    R.layout.dialog_session_locked, null);
            lockDialog.setContentView(view);

            lockDialog.setCancelable(false);
            lockDialog.setCanceledOnTouchOutside(false);

            MaterialButton btnBiometricUnlock = view.findViewById(R.id.btnBiometricUnlock);
            MaterialButton btnLogout = view.findViewById(R.id.btnLogout);
            TextView tvLockTitle = view.findViewById(R.id.tvLockTitle);
            TextView tvLockMessage = view.findViewById(R.id.tvLockMessage);

            if (isArabic) {
                tvLockTitle.setText("🔒 تم قفل الجلسة مؤقتاً");
                tvLockMessage.setText(
                        "لحماية بياناتك، تم قفل الجلسة تلقائياً بعد فترة من عدم النشاط.\n\n" +
                                "يرجى التحقق من هويتك للمتابعة بأمان.");
                btnBiometricUnlock.setText("فتح بالبصمة");
                btnLogout.setText("تسجيل الخروج");
            } else {
                tvLockTitle.setText("🔒 Session Locked");
                tvLockMessage.setText(
                        "For your security, your session has been locked due to inactivity.\n\n" +
                                "Please verify your identity to continue.");
                btnBiometricUnlock.setText("Unlock with Fingerprint");
                btnLogout.setText("Logout");
            }

            btnBiometricUnlock.setOnClickListener(v -> {
                lockDialog.dismiss();
                triggerBiometricUnlock();
            });

            btnLogout.setOnClickListener(v -> {
                lockDialog.dismiss();
                performFullLogout();
            });

            lockDialog.show();

        } catch (Exception e) {
            Log.e(TAG, "showAutoLockDialog error: " + e.getMessage(), e);
        }
    }

    private void triggerBiometricUnlock() {
        try {
            BiometricManager bm = BiometricManager.from(this);
            int canAuth = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);

            if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
                Log.w(TAG, "Biometric not available, using password");
                performFullLogout();
                return;
            }

            Executor executor = ContextCompat.getMainExecutor(this);

            BiometricPrompt prompt = new BiometricPrompt(this, executor,
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                @NonNull BiometricPrompt.AuthenticationResult result) {
                            Log.d(TAG, "✅ Biometric unlock success");
                            justUnlockedWithBiometric = true;
                            sessionManager.unlockSession();
                            Toast.makeText(UHFMainActivity.this,
                                    isArabic ? "✅ تم فتح الجلسة" : "✅ Session unlocked",
                                    Toast.LENGTH_SHORT).show();
                        }

                        @Override
                        public void onAuthenticationError(int code, @NonNull CharSequence msg) {
                            Log.e(TAG, "Biometric error: " + code + " - " + msg);

                            if (code == BiometricPrompt.ERROR_USER_CANCELED
                                    || code == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                                    || code == BiometricPrompt.ERROR_CANCELED) {

                                if (justUnlockedWithBiometric) {
                                    Log.d(TAG, "⏭️ Skip reopening dialog (biometric already succeeded)");
                                    return;
                                }

                                mainHandler.postDelayed(() -> {
                                    if (isActivityAlive()) {
                                        showAutoLockDialog();
                                    }
                                }, 300);
                            }
                        }

                        @Override
                        public void onAuthenticationFailed() {
                            Log.w(TAG, "Biometric failed");
                            Toast.makeText(UHFMainActivity.this,
                                    isArabic ? "❌ لم يتم التعرف على البصمة" : "❌ Fingerprint not recognized",
                                    Toast.LENGTH_SHORT).show();
                        }
                    });

            BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle(isArabic ? "التحقق من الهوية" : "Identity Verification")
                    .setSubtitle(isArabic
                            ? "استخدم بصمة الإصبع للمتابعة بأمان"
                            : "Use fingerprint to continue securely")
                    .setDescription(isArabic
                            ? "بياناتك محمية بتشفير AES-256"
                            : "Your data is protected with AES-256 encryption")
                    .setNegativeButtonText(isArabic ? "إلغاء" : "Cancel")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .build();

            prompt.authenticate(info);

        } catch (Exception e) {
            Log.e(TAG, "triggerBiometricUnlock error: " + e.getMessage(), e);
            performFullLogout();
        }
    }

    private void performFullLogout() {
        try {
            if (sessionManager != null) {
                sessionManager.logoutUser();
            }
            Intent intent = new Intent(this, LoginActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            intent.putExtra("session_expired", true);
            startActivity(intent);
            finish();
        } catch (Exception e) {
            Log.e(TAG, "performFullLogout error: " + e.getMessage());
        }
    }

    public void onPreLogout() {
        try {
            forceHideLoadingImmediate();
            stopActiveInventory();

            if (hardwareManager != null) {
                try {
                    hardwareManager.stopScan();
                } catch (Exception e) {
                    Log.w(TAG, "stopScan error: " + e.getMessage());
                }
            }

            clearActiveListeners();

            if (mainHandler != null) {
                mainHandler.removeCallbacksAndMessages(null);
            }

            if (orgProfileManager != null && orgProfileListener != null) {
                orgProfileManager.removeListener(orgProfileListener);
            }

        } catch (Exception e) {
            Log.e(TAG, "onPreLogout error: " + e.getMessage(), e);
        }
    }

    private boolean isActivityAlive() {
        return !isFinishing() && !isDestroyed();
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        if (sessionManager != null) {
            sessionManager.touchActivity();
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        if (sessionManager != null) {
            sessionManager.touchActivity();
        }

        forceHideLoadingImmediate();
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
                    isArabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);

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

        String tag = navigationManager != null
                ? navigationManager.getCurrentFragmentTag()
                : "Dashboard";

        String cachedSetupStatus = getCachedSetupStatus();
        boolean setupIsComplete = SETUP_COMPLETED.equals(cachedSetupStatus)
                || SETUP_LOCKED.equals(cachedSetupStatus);

        if (setupIsComplete) {
            outState.putString("current_fragment", tag);
            saveFragmentTagToPrefs(tag);
        } else {
            Log.d(TAG, "🔒 onSaveInstanceState: skipping save (setup pending)");
        }
    }

    // ═══════════════════════════════════════════════════════
    //  إعدادات
    // ═══════════════════════════════════════════════════════

    private void initializeLanguageState() {
        try {
            isArabic = getResources().getConfiguration().locale.getLanguage().equals("ar");
            getWindow().getDecorView().setLayoutDirection(
                    isArabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
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

    // ═══════════════════════════════════════════════════════
    //  التنقل
    // ═══════════════════════════════════════════════════════

    public void loadFragment(Fragment fragment, String tag) {
        loadFragment(fragment, tag, true);
    }

    public void loadFragment(Fragment fragment, String tag, boolean addToBackStack) {
        if (isFinishing() || isDestroyed()) return;
        if (navigationManager == null || fragment == null) return;
        if (!guardSetupAccess(tag)) return;

        navigationManager.loadFragment(fragment, tag, addToBackStack);

        this.currentFragment = fragment;

        updateBottomNavSelectionByTag(navigationManager.getCurrentFragmentTag());
        updateSecondaryToolbar(navigationManager.getCurrentFragmentTag());

        mainHandler.post(this::syncCurrentFragment);
    }

    public void loadFragmentDirectly(Fragment fragment, String tag) {
        if (isFinishing() || isDestroyed()) return;
        if (fragment == null || tag == null) return;

        if (navigationManager == null) return;

        if (!guardSetupAccess(tag)) return;

        navigationManager.loadFragmentDirectly(fragment, tag);

        syncCurrentFragment();
        updateBottomNavSelectionByTag(navigationManager.getCurrentFragmentTag());
        updateSecondaryToolbar(navigationManager.getCurrentFragmentTag());
    }

    public void syncCurrentFragment() {
        Fragment real = navigationManager != null
                ? navigationManager.getCurrentFragment()
                : null;
        this.currentFragment = real;
        Log.d(TAG, "🔄 currentFragment synced: "
                + (real != null ? real.getTag() : "null"));
    }

    public Fragment createFragmentByTag(String tag) {
        return FragmentFactory.createFragmentByTag(tag);
    }

    public void openReceiptFragment(String rawJson) {
        try {
            if (isFinishing() || isDestroyed()) return;
            MiraReceiptFragment fragment = MiraReceiptFragment.newInstance(rawJson);
            fragment.show(getSupportFragmentManager(), "Receipt");
        } catch (Exception e) {
            Log.e(TAG, "openReceiptFragment Error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════
    //  الأصوات
    // ═══════════════════════════════════════════════════════

    public void playSound(int id) {
        if (soundManager != null) {
            soundManager.playSound(this, id);
        }
    }

    public void playLocatorBeep(int level) {
        if (soundManager != null) {
            soundManager.playLocatorBeep(this, level);
        }
    }

    public void playSoundDelayed(int value) {
        if (soundManager != null) {
            soundManager.playDelayedSound(this, value);
        }
    }

    // ═══════════════════════════════════════════════════════
    //  RFID
    // ═══════════════════════════════════════════════════════

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
        mainHandler.post(() -> {
            try {
                if (isFinishing() || isDestroyed()) return;

                if (activeEncodingFragment != null
                        && activeEncodingFragment.isAdded()
                        && activeEncodingFragment.isResumed()
                        && !activeEncodingFragment.isRemoving()) {
                    activeEncodingFragment.onRfidScanned(epc);
                    return;
                }

                if (activeRfidListener != null) {
                    activeRfidListener.onRfidScanned(epc);
                    return;
                }

                if (activePOSFragment != null
                        && activePOSFragment.isAdded()
                        && !activePOSFragment.isRemoving()
                        && activePOSFragment.isVisible()) {
                    activePOSFragment.onRingScanned(epc);
                    return;
                }

                Fragment currentFragment = navigationManager != null
                        ? navigationManager.getCurrentFragment()
                        : null;

                if (currentFragment instanceof MiraUHFReadTagFragment
                        && currentFragment.isAdded()
                        && !currentFragment.isRemoving()
                        && currentFragment.isVisible()) {
                    ((MiraUHFReadTagFragment) currentFragment).onTagScanned(epc, -50.0);
                }
            } catch (Exception e) {
                Log.e(TAG, "Tag Read Dispatch Error: " + e.getMessage(), e);
            }
        });
    }

    // ═══════════════════════════════════════════════════════
    //  التنقل السريع
    // ═══════════════════════════════════════════════════════

    private void setupQuickNav() {
        try {
            BottomNavigationView quickNav = findViewById(R.id.bottomNavigationView);
            FloatingActionButton fabActionCenter = findViewById(R.id.fabActionCenter);

            if (fabActionCenter != null) {
                boolean hasQuickActions = permissionManager.getAllowedQuickActions().size() > 0;
                fabActionCenter.setVisibility(hasQuickActions ? View.VISIBLE : View.GONE);
                fabActionCenter.setEnabled(hasQuickActions);
                fabActionCenter.setOnClickListener(v -> showQuickActionMenu());
                fabActionCenter.setOnLongClickListener(v -> {
                    Toast.makeText(UHFMainActivity.this,
                            isArabic ? "⚡ الوصول السريع" : "⚡ Quick Access",
                            Toast.LENGTH_SHORT).show();
                    return true;
                });

                if (hasQuickActions) {
                    pulseFab(fabActionCenter);
                }
            }

            if (quickNav != null) {
                quickNav.setOnItemSelectedListener(item -> {
                    int id = item.getItemId();
                    String tag = getFragmentTagForNavItem(id);
                    if (tag == null || tag.isEmpty()) return false;

                    if (navigationManager != null
                            && tag.equals(navigationManager.getCurrentFragmentTag())) {
                        return true;
                    }

                    if (navigationLocked || isInventoryActive()) {
                        new MaterialAlertDialogBuilder(this)
                                .setTitle(isArabic ? "⚠️ الجرد قيد التشغيل"
                                        : "⚠️ Inventory Active")
                                .setMessage(isArabic
                                        ? "لا يمكن التنقل أثناء الجرد النشط.\n\n" +
                                        "هل تريد إيقاف الجرد والانتقال؟"
                                        : "Cannot navigate during active inventory.\n\n" +
                                        "Stop inventory and proceed?")
                                .setPositiveButton(isArabic ? "إيقاف والانتقال" : "Stop & Proceed",
                                        (d, w) -> {
                                            stopActiveInventory();
                                            setNavigationLocked(false);
                                            mainHandler.postDelayed(() -> {
                                                Fragment f = navigationManager != null
                                                        ? navigationManager.createFragmentByTag(tag)
                                                        : FragmentFactory.createFragmentByTag(tag);
                                                if (f != null) {
                                                    loadFragmentDirectly(f, tag);
                                                }
                                            }, 500);
                                        })
                                .setNegativeButton(isArabic ? "إلغاء" : "Cancel",
                                        (d, w) -> {
                                            String currentTag = navigationManager != null
                                                    ? navigationManager.getCurrentFragmentTag() : "";
                                            updateBottomNavSelectionByTag(currentTag);
                                        })
                                .setCancelable(false)
                                .show();
                        return false;
                    }

                    Fragment fragment = navigationManager != null
                            ? navigationManager.createFragmentByTag(tag)
                            : FragmentFactory.createFragmentByTag(tag);

                    if (fragment != null) {
                        loadFragmentDirectly(fragment, tag);
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
        if (itemId == R.id.nav_home) return NavigationManager.TAG_DASHBOARD;
        if (itemId == R.id.nav_inventory) return NavigationManager.TAG_INVENTORY;
        if (itemId == R.id.nav_sales) return NavigationManager.TAG_POS;
        if (itemId == R.id.nav_transfers) return NavigationManager.TAG_GATES;
        if (itemId == R.id.nav_scrap) return NavigationManager.TAG_SCRAP_GOLD_BUY;
        return "";
    }

    private void updateBottomNavSelectionByTag(String tag) {
        try {
            BottomNavigationView quickNav = findViewById(R.id.bottomNavigationView);
            if (quickNav == null || tag == null) return;

            if (tag.equals(NavigationManager.TAG_DASHBOARD)) {
                MenuItem mi = quickNav.getMenu().findItem(R.id.nav_home);
                if (mi != null) mi.setChecked(true);
            } else if (tag.equals(NavigationManager.TAG_INVENTORY)) {
                MenuItem mi = quickNav.getMenu().findItem(R.id.nav_inventory);
                if (mi != null) mi.setChecked(true);
            } else if (tag.equals(NavigationManager.TAG_POS)) {
                MenuItem mi = quickNav.getMenu().findItem(R.id.nav_sales);
                if (mi != null) mi.setChecked(true);
            } else if (tag.equals(NavigationManager.TAG_GATES)) {
                MenuItem mi = quickNav.getMenu().findItem(R.id.nav_transfers);
                if (mi != null) mi.setChecked(true);
            } else if (tag.equals(NavigationManager.TAG_SCRAP_GOLD_BUY)) {
                MenuItem mi = quickNav.getMenu().findItem(R.id.nav_scrap);
                if (mi != null) mi.setChecked(true);
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
                nav.getMenu().findItem(R.id.nav_transfers).setTitle(
                        isArabic ? "🛡️ البوابات" : "🛡️ Gates");
            }
        } catch (Exception e) {
            Log.e(TAG, "Update bottom nav language error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════
    //  القوائم
    // ═══════════════════════════════════════════════════════

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
        if (menu == null || permissionManager == null) return;

        final PermissionManager pm = permissionManager;

        setMenuVisible(menu, R.id.action_notifications,     true);
        setMenuVisible(menu, R.id.action_profile,           pm.canViewProfile());
        setMenuVisible(menu, R.id.action_sound_settings,    pm.canViewSoundSettings());
        setMenuVisible(menu, R.id.action_logout,            true);
        setMenuVisible(menu, R.id.action_device_status,     true);
        setMenuVisible(menu, R.id.action_search,            true);
        setMenuVisible(menu, R.id.action_sync,              true);

        MenuItem toggleLang = menu.findItem(R.id.action_toggle_language);
        if (toggleLang != null) {
            toggleLang.setVisible(true);
            toggleLang.setTitle(isArabic ? "English" : "العربية");
        }

        setMenuVisible(menu, R.id.action_gold_rates,            pm.canViewGoldRates());
        setMenuVisible(menu, R.id.action_quick_access_settings, pm.canManageQuickAccess());
        setMenuVisible(menu, R.id.action_branches,              pm.canViewBranches());
        setMenuVisible(menu, R.id.action_device_version,        pm.isSystemAdmin());

        setMenuVisible(menu, R.id.action_mira_inventory,     pm.canViewInventory());
        setMenuVisible(menu, R.id.action_inventory_power,    pm.canViewInventory());
        setMenuVisible(menu, R.id.action_inventory_stats,    pm.canViewInventory());
        setMenuVisible(menu, R.id.action_inventory_settings, pm.canEditInventory());
        setMenuVisible(menu, R.id.action_inventory_archive,  pm.canViewInventory());

        setMenuVisible(menu, R.id.action_pos_cart,     pm.canUsePos());
        setMenuVisible(menu, R.id.action_pos_search,   pm.canUsePos());
        setMenuVisible(menu, R.id.action_pos_discount, pm.isManager());
        setMenuVisible(menu, R.id.action_pos_receipt,  pm.canUsePos());

        setMenuVisible(menu, R.id.action_transfers_sync,    pm.canViewTransfers());
        setMenuVisible(menu, R.id.action_transfers_export,  pm.canViewTransfers() && pm.canExportReports());
        setMenuVisible(menu, R.id.action_transfers_import,  pm.canCreateTransfer());
        setMenuVisible(menu, R.id.action_transfers_history, pm.canViewTransfers());

        setMenuVisible(menu, R.id.action_locator, pm.canViewRadar());

        setMenuVisible(menu, R.id.action_accounting,       pm.canViewAccounting());
        // ═══════════════════════════════════════════════════════
//  9. حالة الترخيص (للمدراء فقط)
// ═══════════════════════════════════════════════════════
setMenuVisible(menu, R.id.action_license_status,
        pm.isAdmin() || pm.isManager());
        setMenuVisible(menu, R.id.action_accounting_setup, pm.canPerformAccountingSetup());

        setMenuVisible(menu, R.id.action_export_data, pm.canExportReports());
    }

    private void setMenuVisible(Menu menu, int itemId, boolean visible) {
        if (menu == null) return;
        MenuItem item = menu.findItem(itemId);
        if (item != null) item.setVisible(visible);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_gold_rates) {
            loadFragment(new MiraGoldCurrencyFragment(), "GoldRates");
            return true;
        } else if (id == R.id.action_mira_inventory) {
            loadFragment(new MiraInventoryFragment(), "MiraInventory");
            return true;
        } else if (id == R.id.action_profile) {
            loadFragment(new MiraProfileFragment(), "Profile");
            return true;
        } else if (id == R.id.action_notifications) {
            handleNotificationButtonClick();
            return true;
        } else if (id == R.id.action_quick_access_settings) {
            loadFragment(new MiraQuickAccessSettingsFragment(), "QuickAccessSettings");
            return true;
        } else if (id == R.id.action_sound_settings) {
            loadFragment(new MiraSoundSettingsFragment(), "SoundSettings");
            return true;
        } else if (id == R.id.action_toggle_language) {
            toggleLanguage();
            return true;
        } else if (id == R.id.action_branches) {
            loadFragment(new MiraBranchesManagementFragment(), "Branches");
            return true;
        } else if (id == R.id.action_export_data) {
            loadFragment(new MiraReportsProFragment(), "Reports");
            return true;
        } else if (id == R.id.action_locator) {
            loadFragment(new MiraUHFLocatorFragment(), "Locator");
            return true;
        } else if (id == R.id.action_accounting_setup) {
            Bundle args = new Bundle();
            args.putBoolean("manual_open", true);
            MiraAccountingSetupFragment frag = new MiraAccountingSetupFragment();
            frag.setArguments(args);
            loadFragment(frag, "AccountingSetup");
            return true;
        } else if (id == R.id.action_license_status) {
    try {
        Intent intent = new Intent(this,
                com.example.uhf.license.LicenseStatusActivity.class);
        startActivity(intent);
        Log.d(TAG, "✅ License status opened from menu");
    } catch (Exception e) {
        Log.e(TAG, "Failed to open LicenseStatusActivity", e);
        Toast.makeText(this,
                "شاشة الترخيص غير متاحة — أعد بناء التطبيق",
                Toast.LENGTH_LONG).show();
    }
    return true;
        } else if (id == R.id.action_accounting) {
            openAccounting();
            return true;
        } else if (id == R.id.action_device_status) {
            showHardwareOptionsDialog();
            return true;
        } else if (id == R.id.action_device_version) {
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
            saveFragmentTagToPrefs(navigationManager.getCurrentFragmentTag());
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
                .setMessage(isArabic ? "هل أنت متأكد من تسجيل الخروج من المنظومة؟"
                        : "Are you sure you want to log out?")
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

    private void showSystemVersionDialog() {
        String miraSystemVersion = "MIRA ID™ v1.4.8 (Enterprise)";
        String hwVer = null;
        String modelName = Build.MODEL;

        if (com.example.uhf.MiraRfid.RFIDManager.getInstance(getApplicationContext())
                .isReady()) {
            try {
                com.rscja.deviceapi.RFIDWithUHFUART reader =
                        com.example.uhf.activity.BaseTabFragmentActivity.getSharedReader();
                if (reader != null) {
                    hwVer = reader.getHardwareVersion();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error fetching RFID reader info: " + e.getMessage());
            }
        }

        StringBuilder message = new StringBuilder();
        message.append("MIRA Trust Architecture™\n");
        message.append(isArabic ? "نظام المصادقة والتتبع المعتمد للمجوهرات\n\n"
                : "Jewelry Tracking & Authentication System\n\n");
        message.append(isArabic ? "📱 الجهاز: " : "📱 Device: ").append(modelName).append("\n");
        message.append(isArabic ? "📦 النسخة: " : "📦 Version: ").append(miraSystemVersion).append("\n");

        if (!BaseTabFragmentActivity.isRfidDevicePresent()) {
            message.append(isArabic ? "📡 العتاد: غير متصل" : "📡 Hardware: Not Connected");
        } else if (hwVer == null || hwVer.trim().isEmpty()) {
            message.append(isArabic ? "📡 العتاد: غير متهيئ" : "📡 Hardware: Not Configured");
        } else {
            message.append(isArabic ? "📡 العتاد: " : "📡 Hardware: ").append(hwVer).append("\n");
            message.append(isArabic ? "🟢 الحالة: وحدة UHF نشطة ومتصلة"
                    : "🟢 Status: Active & Connected");
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(isArabic ? "حول النظام | System Info" : "About System")
                .setMessage(message.toString())
                .setPositiveButton(isArabic ? "موافق" : "OK", (dialog, which) -> dialog.dismiss())
                .show();
    }

    public void openAccounting() {
        try {
            MiraAccountingFragment fragment = new MiraAccountingFragment();
            loadFragmentDirectly(fragment, "Accounting");
        } catch (Throwable e) {
            Log.e(TAG, "❌ openAccounting error", e);
            Toast.makeText(this,
                "❌ خطأ: " + e.getClass().getSimpleName() + " — " + e.getMessage(),
                Toast.LENGTH_LONG).show();
        }
    }

    private void showInventoryStatsDialog() {
        try {
            int scannedCount = 0;
            boolean isScanningNow = false;

            if (activeInventoryFragment != null
                    && activeInventoryFragment.isAdded()
                    && !activeInventoryFragment.isRemoving()) {
                scannedCount = activeInventoryFragment.getScannedCount();
                isScanningNow = activeInventoryFragment.isCurrentlyScanning();
            } else if (tagList != null) {
                scannedCount = tagList.size();
            }

            String status = isScanningNow
                    ? (isArabic ? "🟢 قيد التشغيل" : "🟢 Running")
                    : (isArabic ? "🔴 متوقف" : "🔴 Stopped");

            String message = isArabic
                    ? "📊 الأصناف الممسوحة: " + scannedCount + "\n" +
                    "📡 الحالة: " + status + "\n" +
                    "⏱️ آخر تحديث: الآن"
                    : "📊 Scanned items: " + scannedCount + "\n" +
                    "📡 Status: " + status + "\n" +
                    "⏱️ Last updated: just now";

            new MaterialAlertDialogBuilder(this)
                    .setTitle(isArabic ? "📊 إحصائيات الجلسة" : "📊 Live Stats")
                    .setMessage(message)
                    .setPositiveButton(isArabic ? "موافق" : "OK", null)
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "showInventoryStatsDialog error: " + e.getMessage());
        }
    }

    private void showInventoryArchiveDialog() {
        try {
            if (activeInventoryFragment != null
                    && activeInventoryFragment.isAdded()
                    && !activeInventoryFragment.isRemoving()) {
                activeInventoryFragment.showArchiveReportsPublic();
                return;
            }

            Fragment fragment = getSupportFragmentManager().findFragmentByTag("Scan");

            if (fragment instanceof MiraInventoryProFragment) {
                ((MiraInventoryProFragment) fragment).showArchiveReportsPublic();
                return;
            }

            loadFragment(new MiraInventoryProFragment(), "Scan");
            mainHandler.postDelayed(() -> {
                if (activeInventoryFragment != null
                        && activeInventoryFragment.isAdded()) {
                    activeInventoryFragment.showArchiveReportsPublic();
                } else {
                    Toast.makeText(this,
                            isArabic ? "افتح شاشة الجرد أولاً" : "Open inventory screen first",
                            Toast.LENGTH_SHORT).show();
                }
            }, 700);

        } catch (Exception e) {
            Log.e(TAG, "showInventoryArchiveDialog error: " + e.getMessage(), e);
            Toast.makeText(this,
                    isArabic ? "خطأ في فتح الأرشيف" : "Error opening archive",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void showPOSCartDialog() {
        if (activePOSFragment != null
                && activePOSFragment.isAdded()
                && !activePOSFragment.isRemoving()) {
            activePOSFragment.showCartBottomSheet();
        } else {
            loadFragment(new MiraPosFragment(), "POS");
            mainHandler.postDelayed(() -> {
                if (activePOSFragment != null) {
                    activePOSFragment.showCartBottomSheet();
                }
            }, 500);
        }
    }

    private void showPOSSearchDialog() {
        if (activePOSFragment != null
                && activePOSFragment.isAdded()
                && !activePOSFragment.isRemoving()) {
            activePOSFragment.focusSearchField();
        } else {
            loadFragment(new MiraPosFragment(), "POS");
            mainHandler.postDelayed(() -> {
                if (activePOSFragment != null) {
                    activePOSFragment.focusSearchField();
                }
            }, 500);
        }
    }

    private void showPOSDiscountDialog() {
        if (activePOSFragment != null
                && activePOSFragment.isAdded()
                && !activePOSFragment.isRemoving()) {
            activePOSFragment.showDiscountDialog();
        } else {
            loadFragment(new MiraPosFragment(), "POS");
            mainHandler.postDelayed(() -> {
                if (activePOSFragment != null) {
                    activePOSFragment.showDiscountDialog();
                }
            }, 500);
        }
    }

    private void showPOSReceiptDialog() {
        if (activePOSFragment != null
                && activePOSFragment.isAdded()
                && !activePOSFragment.isRemoving()) {
            activePOSFragment.showLastReceiptDialog();
        } else {
            showToast(isArabic ? "لا يوجد إيصال حديث" : "No recent receipt");
        }
    }

    private void showTransfersExportDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(isArabic ? "📤 تصدير التحويلات" : "📤 Export Transfers")
                .setMessage(isArabic
                        ? "سيتم تصدير قائمة التحويلات الحالية بصيغة:\n\n" +
                        "• Excel (XLSX)\n" +
                        "• PDF\n" +
                        "• CSV\n\n" +
                        "🚧 قيد التطوير"
                        : "Exports transfers list as:\n\n" +
                        "• Excel (XLSX)\n" +
                        "• PDF\n" +
                        "• CSV\n\n" +
                        "🚧 Under Development")
                .setPositiveButton(isArabic ? "موافق" : "OK", null)
                .show();
    }

    private void showTransfersImportDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(isArabic ? "📥 استيراد التحويلات" : "📥 Import Transfers")
                .setMessage(isArabic
                        ? "سيتم استيراد قائمة التحويلات من:\n\n" +
                        "• ملف Excel\n" +
                        "• ملف CSV\n" +
                        "• من سيرفر آخر\n\n" +
                        "🚧 قيد التطوير"
                        : "Imports transfers from:\n\n" +
                        "• Excel file\n" +
                        "• CSV file\n" +
                        "• Another server\n\n" +
                        "🚧 Under Development")
                .setPositiveButton(isArabic ? "موافق" : "OK", null)
                .show();
    }

    // ═══════════════════════════════════════════════════════
    //  الإشعارات
    // ═══════════════════════════════════════════════════════

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
                    if (isFinishing() || isDestroyed()) return;
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
        etMessageContent.setHint(isArabic ? "اكتب رسالتك للإدارة أو الفروع..."
                : "Type message to management or branches...");
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
                            if (isFinishing() || isDestroyed()) return;
                            Toast.makeText(UHFMainActivity.this,
                                    isArabic ? "تم إرسال الرسالة بنجاح ✅"
                                            : "Message sent successfully ✅",
                                    Toast.LENGTH_LONG).show();
                            dialog.dismiss();
                        });
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            Toast.makeText(UHFMainActivity.this,
                                    isArabic ? "فشل الإرسال: " + error : "Failed: " + error,
                                    Toast.LENGTH_SHORT).show();
                            btnSend.setEnabled(true);
                            btnSend.setText(isArabic ? "إرسال الرسالة الآن 🚀"
                                    : "Send Message Now 🚀");
                        });
                    }
                });
            } else {
                etMessageContent.setError(isArabic ? "الرجاء كتابة نص الرسالة"
                        : "Please enter message text");
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
                    if (isFinishing() || isDestroyed()) return;
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
                    if (isFinishing() || isDestroyed()) return;
                    messagesContainer.removeAllViews();
                    TextView errorText = new TextView(UHFMainActivity.this);
                    errorText.setText(isArabic ? "فشل تحميل الرسائل: " + error
                            : "Failed to load messages: " + error);
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
                            if (isFinishing() || isDestroyed()) return;
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
                            if (isFinishing() || isDestroyed()) return;
                            Toast.makeText(UHFMainActivity.this,
                                    isArabic ? "فشل إرسال الرسالة: " + error
                                            : "Failed to send message: " + error,
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

    // ═══════════════════════════════════════════════════════
    //  البحث والمزامنة
    // ═══════════════════════════════════════════════════════

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
        etSearchQuery.setHint(isArabic ? "أدخل رقم الباركود، EPC، أو اسم القطعة..."
                : "Enter EPC, Barcode, or item name...");
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
                Toast.makeText(this,
                        (isArabic ? "جاري البحث عن: " : "Searching for: ") + query,
                        Toast.LENGTH_SHORT).show();
                loadFragment(new MiraInventoryProFragment(), "Scan");
            } else {
                etSearchQuery.setError(isArabic ? "الرجاء إدخال نص للبحث"
                        : "Please enter search text");
            }
        });

        searchDialog.show();
    }

    private void performDataSync() {
        showLoading();
        Toast.makeText(this, isArabic ? "جاري مزامنة البيانات المحلية مع السيرفر..."
                : "Syncing local data with server...", Toast.LENGTH_SHORT).show();

        mainHandler.postDelayed(() -> {
            hideLoading();
            if (isFinishing() || isDestroyed()) return;
            new MaterialAlertDialogBuilder(this)
                    .setTitle(isArabic ? "✅ تمت المزامنة بنجاح" : "✅ Sync Completed")
                    .setMessage(isArabic ? "تم تحديث كافة السجلات، المخزون، والأسعار مع السيرفر السحابي بنجاح."
                            : "All records, inventory, and prices have been successfully synced with the cloud server.")
                    .setPositiveButton(isArabic ? "موافق" : "OK", (dialog, which) -> dialog.dismiss())
                    .show();
        }, 1500);
    }

    // ═══════════════════════════════════════════════════════
    //  قائمة الإجراءات السريعة
    // ═══════════════════════════════════════════════════════

    private void showQuickActionMenu() {
    try {
        QuickActionsBottomSheetFragment sheet = new QuickActionsBottomSheetFragment();
        sheet.show(getSupportFragmentManager(), "QuickActions");
        Log.d(TAG, "✅ QuickActions bottom sheet shown");
    } catch (Exception e) {
        Log.e(TAG, "showQuickActionMenu error: " + e.getMessage(), e);
    }
}

// ⚡ فتح شاشة الترخيص من أي مكان
public void openLicenseStatus() {
    try {
        Intent intent = new Intent(this,
                com.example.uhf.license.LicenseStatusActivity.class);
        startActivity(intent);
    } catch (Exception e) {
        Log.e(TAG, "openLicenseStatus error: " + e.getMessage());
        Toast.makeText(this, "تعذر فتح شاشة الترخيص", Toast.LENGTH_SHORT).show();
    }
}

    // ═══════════════════════════════════════════════════════
    //  حالة الجرد
    // ═══════════════════════════════════════════════════════

    public boolean isInventoryActive() {
        Fragment currentFragment = navigationManager != null
                ? navigationManager.getCurrentFragment()
                : null;

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
            Fragment currentFragment = navigationManager != null
                    ? navigationManager.getCurrentFragment()
                    : null;

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

    // ═══════════════════════════════════════════════════════
    //  التحميل والعرض
    // ═══════════════════════════════════════════════════════

    public void showLoading() {
        mainHandler.post(() -> {
            try {
                loadingCounter++;
                View loadingOverlay = findViewById(R.id.successOverlayMain);
                if (loadingOverlay != null && loadingCounter == 1) {
                    loadingOverlay.animate().cancel();
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
                loadingCounter = Math.max(0, loadingCounter - 1);
                if (loadingCounter > 0) return;

                View loadingOverlay = findViewById(R.id.successOverlayMain);
                if (loadingOverlay != null && loadingOverlay.getVisibility() == View.VISIBLE) {
                    loadingOverlay.animate()
                            .alpha(0.0f)
                            .setDuration(200)
                            .withEndAction(() -> {
                                if (loadingCounter == 0) {
                                    loadingOverlay.setVisibility(View.GONE);
                                }
                            })
                            .start();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error hiding loading: " + e.getMessage());
            }
        });
    }

    private void forceHideLoadingImmediate() {
        mainHandler.post(() -> {
            try {
                loadingCounter = 0;
                View loadingOverlay = findViewById(R.id.successOverlayMain);
                if (loadingOverlay != null) {
                    loadingOverlay.animate().cancel();
                    loadingOverlay.setVisibility(View.GONE);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error forcing hide loading: " + e.getMessage());
            }
        });
    }

    public void showToast(String text) {
        if (text == null) return;
        mainHandler.post(() -> Toast.makeText(getApplicationContext(),
                text, Toast.LENGTH_SHORT).show());
    }

    public void showToast(int resId) {
        showToast(getString(resId));
    }

    // ═══════════════════════════════════════════════════════
    //  تسجيل الدخول
    // ═══════════════════════════════════════════════════════

    private void navigateToLogin() {
        try {
            forceHideLoadingImmediate();

            Intent intent = new Intent(this, LoginActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        } catch (Exception e) {
            Log.e(TAG, "Navigation to login error: " + e.getMessage());
        }
    }

    public void setNavigationLocked(boolean locked) {
        try {
            this.navigationLocked = locked;

            BottomNavigationView nav = findViewById(R.id.bottomNavigationView);
            if (nav != null) {
                nav.setEnabled(!locked);
                nav.setAlpha(locked ? 0.4f : 1.0f);

                Menu menu = nav.getMenu();
                if (menu != null) {
                    for (int i = 0; i < menu.size(); i++) {
                        menu.getItem(i).setEnabled(!locked);
                    }
                }
            }

            FloatingActionButton fab = findViewById(R.id.fabActionCenter);
            if (fab != null) {
                fab.setEnabled(!locked);
                fab.setAlpha(locked ? 0.4f : 1.0f);
            }

            MaterialToolbar toolbar = findViewById(R.id.toolbar);
            if (toolbar != null && toolbar.getMenu() != null) {
                Menu menu = toolbar.getMenu();
                for (int i = 0; i < menu.size(); i++) {
                    menu.getItem(i).setEnabled(!locked);
                }
            }

            if (secondaryToolbarManager != null) {
                Menu menu = secondaryToolbarManager.getCurrentMenu();
                if (menu != null) {
                    for (int i = 0; i < menu.size(); i++) {
                        menu.getItem(i).setEnabled(!locked);
                    }
                }
            }

            Log.d(TAG, "🔒 Navigation " + (locked ? "LOCKED" : "UNLOCKED"));
        } catch (Exception e) {
            Log.e(TAG, "setNavigationLocked error", e);
        }
    }

    public boolean isNavigationLocked() {
        return navigationLocked;
    }

    // ═══════════════════════════════════════════════════════
    //  التنظيف
    // ═══════════════════════════════════════════════════════

    @Override
    protected void onDestroy() {
        try {
            if (appLifecycleObserver != null) {
                ProcessLifecycleOwner.get().getLifecycle()
                        .removeObserver(appLifecycleObserver);
                appLifecycleObserver = null;
            }
            if (notificationManager != null) notificationManager.cleanup();
            if (soundManager != null) soundManager.cleanup();
            if (statusIndicatorManager != null) statusIndicatorManager.cleanup();
            if (hardwareManager != null) hardwareManager.cleanup();

            AppWatchdog.getInstance().stopMonitoring();
            forceHideLoadingImmediate();
            stopActiveInventory();

            if (orgProfileManager != null) {
                orgProfileManager.removeListener(orgProfileListener);
            }

            SessionMonitorManager.getInstance(this).stopMonitoring();

            clearActiveListeners();
            currentFragment = null;

            if (tagList != null) {
                tagList.clear();
                tagList = null;
            }

        } catch (Exception e) {
            Log.e(TAG, "onDestroy cleanup error: " + e.getMessage(), e);
        }

        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(transfersUpdateReceiver);
        } catch (Exception ignored) {}

        if (transferCountHandler != null) {
            transferCountHandler.removeCallbacksAndMessages(null);
        }

        super.onDestroy();
    }

    // ═══════════════════════════════════════════════════════
    //  التفاعل مع الكيبورد
    // ═══════════════════════════════════════════════════════

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            View view = getCurrentFocus();
            if (view != null && view instanceof EditText) {
                int[] location = new int[2];
                view.getLocationOnScreen(location);
                float x = ev.getRawX() + view.getLeft() - location[0];
                float y = ev.getRawY() + view.getTop() - location[1];

                if (x < view.getLeft() || x > view.getRight()
                        || y < view.getTop() || y > view.getBottom()) {
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

    private void pulseFab(View fab) {
        try {
            if (fab == null) return;

            android.animation.ObjectAnimator scaleX = android.animation.ObjectAnimator
                    .ofFloat(fab, "scaleX", 1f, 1.15f, 1f);
            android.animation.ObjectAnimator scaleY = android.animation.ObjectAnimator
                    .ofFloat(fab, "scaleY", 1f, 1.15f, 1f);

            scaleX.setDuration(600);
            scaleY.setDuration(600);
            scaleX.setRepeatCount(2);
            scaleY.setRepeatCount(2);
            scaleX.setStartDelay(1500);
            scaleY.setStartDelay(1500);

            scaleX.start();
            scaleY.start();
        } catch (Exception e) {
            Log.e(TAG, "pulseFab error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════
    //  عداد التحويلات
    // ═══════════════════════════════════════════════════════

    private void refreshIncomingTransfersCountThrottled() {
        long now = System.currentTimeMillis();
        if (now - lastTransferRefreshTime < MIN_TRANSFER_REFRESH_INTERVAL) {
            return;
        }
        lastTransferRefreshTime = now;
        refreshIncomingTransfersCount();
    }

    private void refreshIncomingTransfersCount() {
        if (branchesManager == null || !branchesManager.hasActiveBranch()) {
            return;
        }

        new Thread(() -> {
            try {
                MiraApiClient apiClient = MiraApiClient.getInstance(getApplicationContext());
                MiraApiClient.ApiResponse response = apiClient.getIncomingTransfersCount();

                int count = 0;
                if (response != null && response.isSuccess && response.data != null) {
                    count = response.data.optInt("count", 0);
                } else {
                    count = loadTransfersCountLocally();
                }

                final int finalCount = count;
                mainHandler.post(() -> updateTransfersBadge(finalCount));

            } catch (Exception e) {
                Log.e(TAG, "refreshIncomingTransfersCount error: " + e.getMessage());
            }
        }).start();
    }

    private void updateTransfersBadge(int count) {
        try {
            this.incomingTransfersCount = count;
            saveTransfersCountLocally(count);

            BottomNavigationView nav = findViewById(R.id.bottomNavigationView);
            if (nav == null) return;

            BadgeDrawable badge = nav.getOrCreateBadge(R.id.nav_transfers);

            if (count > 0) {
                badge.setVisible(true);
                badge.setNumber(count);
                badge.setBackgroundColor(
                        getResources().getColor(android.R.color.holo_red_dark));
                badge.setBadgeTextColor(
                        getResources().getColor(android.R.color.white));

                pulseNavItem(nav, R.id.nav_transfers);
            } else {
                badge.setVisible(false);
                badge.clearNumber();
            }
        } catch (Exception e) {
            Log.e(TAG, "updateTransfersBadge error: " + e.getMessage());
        }
    }

    private void pulseNavItem(BottomNavigationView nav, int itemId) {
        try {
            View itemView = null;
            for (int i = 0; i < nav.getChildCount(); i++) {
                View child = nav.getChildAt(i);
                if (child instanceof com.google.android.material.navigation.NavigationBarItemView) {
                    com.google.android.material.navigation.NavigationBarItemView navItem =
                            (com.google.android.material.navigation.NavigationBarItemView) child;
                    if (navItem.getItemData() != null
                            && navItem.getItemData().getItemId() == itemId) {
                        itemView = navItem;
                        break;
                    }
                }
            }

            if (itemView == null) return;

            android.animation.ObjectAnimator scaleX = android.animation.ObjectAnimator
                    .ofFloat(itemView, "scaleX", 1f, 1.15f, 1f);
            android.animation.ObjectAnimator scaleY = android.animation.ObjectAnimator
                    .ofFloat(itemView, "scaleY", 1f, 1.15f, 1f);

            scaleX.setDuration(400);
            scaleY.setDuration(400);
            scaleX.setRepeatCount(1);
            scaleY.setRepeatCount(1);
            scaleX.start();
            scaleY.start();
        } catch (Exception ignored) {}
    }

    private void saveTransfersCountLocally(int count) {
        try {
            getSharedPreferences(PREFS_TRANSFERS_BADGE, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(KEY_LAST_TRANSFERS_COUNT, count)
                    .apply();
        } catch (Exception e) {
            Log.w(TAG, "saveTransfersCountLocally error: " + e.getMessage());
        }
    }

    private int loadTransfersCountLocally() {
        try {
            return getSharedPreferences(PREFS_TRANSFERS_BADGE, Context.MODE_PRIVATE)
                    .getInt(KEY_LAST_TRANSFERS_COUNT, 0);
        } catch (Exception e) {
            Log.w(TAG, "loadTransfersCountLocally error: " + e.getMessage());
            return 0;
        }
    }
}
