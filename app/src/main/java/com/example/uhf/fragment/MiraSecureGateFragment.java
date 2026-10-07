package com.example.uhf.fragment;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.DiffUtil;
import androidx.activity.OnBackPressedCallback;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraGatesSettings;
import com.example.uhf.manager.OfflineGateCache;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.service.MiraGateMonitorService;
import com.example.uhf.tools.RfidHardwareHelper;
import com.example.uhf.tools.SoundGenerator;
import com.example.uhf.MiraRfid.GateRfidBridge;
import com.example.uhf.navigation.NavigationManager;
import com.example.uhf.utils.MiraFileLogger;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MiraSecureGateFragment v3.0
 * ═══════════════════════════════════════════════════════════════
 * ✅ v3.0 الجديد:
 *  - 🎯 Grid ديناميكي (1 على الهاتف، 2 على التابلت)
 *  - 🔙 زر إغلاق + OnBackPressedCallback
 *  - 🧹 إخفاء أدوات التطوير في الإنتاج
 *  - 📊 تحسين عرض بطاقات البوابات
 *  - 🛡️ تأكيد الخروج أثناء Lockdown
 */
@RequiresApi(api = Build.VERSION_CODES.O)
public class MiraSecureGateFragment extends Fragment {

    private static final String TAG = "MiraSecureGate";
    private static final long SCAN_COOL_DOWN_MS = 2500;
    private static final int API_TIMEOUT_SECONDS = 5;
    private static final long LOG_BATCH_INTERVAL_MS = 300;
    private static final long ALLOWED_CACHE_REFRESH_MS = 10_000;

    private static final long POLL_INITIAL_MS = 5_000;
    private static final long POLL_MAX_MS = 60_000;
    private static final long PING_INITIAL_MS = 10_000;
    private static final long PING_MAX_MS = 60_000;

    private static final long CONTINUOUS_SCAN_INTERVAL_MS = 10_000;
    private static final long CONTINUOUS_SCAN_INITIAL_DELAY_MS = 5_000;

    private static final long OVERLAY_PASS_MS = 2500;
    private static final long OVERLAY_DENY_MS = 5000;

    private volatile boolean isCameraOpen = false;
    private OfflineGateCache offlineCache;
    private volatile boolean isOnline = true;

    private UHFMainActivity mContext;
    private MiraGatesSettings miraGatesSettings;
    private PermissionManager permissionManager;
    private MiraBranchesManager branchesManager;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService gateExecutor;
    private ScheduledExecutorService scheduler;

    private ScheduledFuture<?> pollFuture;
    private ScheduledFuture<?> pingFuture;

    private volatile long currentPollInterval = POLL_INITIAL_MS;
    private volatile long currentPingInterval = PING_INITIAL_MS;
    private volatile int consecutivePollFailures = 0;
    private volatile int consecutivePingFailures = 0;

    private final AtomicBoolean statusLoadInProgress = new AtomicBoolean(false);
    private volatile long lastAllowedCacheRefresh = 0;

    private final StringBuilder logBuffer = new StringBuilder();
    private final AtomicBoolean logFlushScheduled = new AtomicBoolean(false);

    private int currentBranchId = 0;
    private boolean isGlobalAdmin = false;
    private Spinner spBranchSelector;
    private TextView tvCurrentBranch;
    private final List<BranchInfo> branchesList = new ArrayList<>();

    private RecyclerView rvGatesMonitor;
    private GateMonitorAdapter monitorAdapter;
    private final List<GateMonitorItem> monitorList = new ArrayList<>();
    private TextView tvPassCount, tvDenyCount, tvTotalCount;
    private TextView tvDebugConsole;
    private ScrollView scrollDebugLog;
    private FrameLayout overlayAlarm;
    private TextView tvOverlayTitle, tvOverlayDetails;
    private TextView tvPingLatency, tvSystemStatus, tvLiveTickerText;
    private ProgressBar pbRiskMeter;
    private TextView tvRiskPercentage;
    private Button btnStopAlarm, btnToggleScan;
    private Button btnSimulateAllowed, btnSimulateDenied;
    private Button btnScanBarcode, btnEmergencyLockdown, btnClearConsole;
    private Button btnFilterAll, btnFilterAlerts, btnFilterPass;

    private MiraGatesSettings.OperationMode currentOperationMode;
    private RadioGroup rgOperationMode;
    private RadioButton rbManual, rbSemiAuto, rbFullAuto;
    private MaterialSwitch swKeepScreenOn, swContinuousScan, swSoundAlerts, swVibration;
    private TextView tvCurrentModeBadge;

    private MaterialSwitch swSimulateExitOnSale;

    private volatile int localPassCount = 0;
    private volatile int localDenyCount = 0;

    private String soldEpc = "", soldTitle = "";
    private String unsoldEpc = "", unsoldTitle = "";

    private volatile boolean isDestroyed = false;
    private volatile boolean isScanning = false;
    private volatile boolean isLockdown = false;
    private String logFilter = "ALL";
    private final Set<String> localAllowedEpcCache = ConcurrentHashMap.newKeySet();
    private GateRfidBridge gateRfidBridge;
    private final ConcurrentHashMap<String, Long> scanDebounceMap = new ConcurrentHashMap<>();
    private Vibrator vibrator;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private boolean isArabic = true;

    // ⭐ الجديد: Back press
    private OnBackPressedCallback backPressedCallback;

    // ⭐ الجديد: زر الإغلاق
    private ImageButton btnGateClose;

    private final Runnable continuousScanTask = new Runnable() {
        @Override
        public void run() {
            if (isDestroyed || !isAdded()) return;
            if (currentOperationMode == null
                    || currentOperationMode == MiraGatesSettings.OperationMode.MANUAL) {
                return;
            }
            if (isLockdown || isCameraOpen) {
                mainHandler.postDelayed(this, CONTINUOUS_SCAN_INTERVAL_MS);
                return;
            }
            if (!isScanning) {
                Log.d(TAG, "🔄 Continuous scan: restarting RFID scan");
                startScanning();
            }
            mainHandler.postDelayed(this, CONTINUOUS_SCAN_INTERVAL_MS);
        }
    };

    // ==================== Activity Result Launcher ====================

    private final ActivityResultLauncher<ScanOptions> barcodeLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                if (isDestroyed || !isAdded()) return;
                mainHandler.post(() -> {
                    isCameraOpen = false;
                    if (result != null && result.getContents() != null
                            && !isDestroyed && isAdded()) {
                        final String scannedCode = result.getContents().trim();
                        executeOnGateExecutor(() ->
                                processInputEpcBackground(scannedCode, "CAMERA"));
                    }
                    if (!isLockdown && !isDestroyed && isAdded()) {
                        autoStartScanning();
                    }
                });
            });

    // ==================== Helper Methods ====================

    private String getLocalizedText(String arabic, String english) {
        return isArabic ? arabic : english;
    }

    private boolean isTablet() {
        try {
            Configuration config = getResources().getConfiguration();
            return config.smallestScreenWidthDp >= 600;
        } catch (Exception e) {
            return false;
        }
    }

    private int getColorFromTheme(int attrRes, int fallbackColor) {
        try {
            TypedValue typedValue = new TypedValue();
            Context context = getContext();
            if (context != null && context.getTheme()
                    .resolveAttribute(attrRes, typedValue, true)) {
                return typedValue.data;
            }
        } catch (Exception e) {
            Log.e(TAG, "getColorFromTheme: " + e.getMessage());
        }
        return fallbackColor;
    }

    private int getSuccessColor() {
        return getColorFromTheme(com.google.android.material.R.attr.colorPrimary,
                Color.parseColor("#10B981"));
    }

    private int getDangerColor() {
        return getColorFromTheme(com.google.android.material.R.attr.colorError,
                Color.parseColor("#EF4444"));
    }

    private int getWarningColor() {
        return getColorFromTheme(com.google.android.material.R.attr.colorSecondary,
                Color.parseColor("#F59E0B"));
    }

    private int getTextColor() {
        return getColorFromTheme(com.google.android.material.R.attr.colorOnSurface,
                Color.WHITE);
    }

    private int getSecondaryTextColor() {
        return getColorFromTheme(com.google.android.material.R.attr.colorOnSurfaceVariant,
                Color.GRAY);
    }

    private void executeOnGateExecutor(Runnable task) {
        try {
            ExecutorService exec = gateExecutor;
            if (exec != null && !exec.isShutdown() && !exec.isTerminated()) {
                exec.execute(task);
            }
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "Task rejected: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "executeOnGateExecutor error: " + e.getMessage(), e);
        }
    }

    // ==================== Lifecycle ====================

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_secure_gate, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Context context = getContext();
        isArabic = context != null && Locale.getDefault().getLanguage().equals("ar");

        initializeComponents();
        initViews(view);
        setupListeners();
        initializeOperationMode();
        setupBackPressHandler();
        hideDevTools();

        startMonitoring();
        loadTestEpcs();
        autoStartScanning();
        startBackgroundService();

        updateSystemStatus(true);
        logConsole(getLocalizedText("🚀 تم تفعيل مركز القيادة الأمنية",
                "🚀 SECURITY COMMAND CENTER ACTIVATED"));
    }

    private void initializeComponents() {
        Context context = getContext();
        if (context == null) return;

        if (getActivity() instanceof UHFMainActivity) {
            mContext = (UHFMainActivity) getActivity();
        }
        offlineCache = OfflineGateCache.getInstance(context);
        miraGatesSettings = MiraGatesSettings.getInstance(context);
        permissionManager = PermissionManager.getInstance(context);
        branchesManager = MiraBranchesManager.getInstance(context);
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        gateRfidBridge = GateRfidBridge.getInstance(context);

        gateExecutor = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "MiraGate-Worker");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });

        isDestroyed = false;
        loadCurrentBranchInfo();
    }

    private void initViews(View view) {
        rvGatesMonitor = view.findViewById(R.id.rvGatesMonitor);
        tvPassCount = view.findViewById(R.id.tvPassCount);
        tvDenyCount = view.findViewById(R.id.tvDenyCount);
        tvTotalCount = view.findViewById(R.id.tvTotalCount);
        tvDebugConsole = view.findViewById(R.id.tvDebugConsole);
        scrollDebugLog = view.findViewById(R.id.scrollDebugLog);
        overlayAlarm = view.findViewById(R.id.overlayAlarm);
        tvOverlayTitle = view.findViewById(R.id.tvOverlayTitle);
        tvOverlayDetails = view.findViewById(R.id.tvOverlayDetails);
        tvPingLatency = view.findViewById(R.id.tvPingLatency);
        tvSystemStatus = view.findViewById(R.id.tvSystemStatus);
        tvLiveTickerText = view.findViewById(R.id.tvLiveTickerText);
        pbRiskMeter = view.findViewById(R.id.pbRiskMeter);
        tvRiskPercentage = view.findViewById(R.id.tvRiskPercentage);
        btnStopAlarm = view.findViewById(R.id.btnStopAlarm);
        btnToggleScan = view.findViewById(R.id.btnToggleScan);
        btnSimulateAllowed = view.findViewById(R.id.btnSimulateAllowed);
        btnSimulateDenied = view.findViewById(R.id.btnSimulateDenied);
        btnScanBarcode = view.findViewById(R.id.btnScanBarcode);
        btnEmergencyLockdown = view.findViewById(R.id.btnEmergencyLockdown);
        btnClearConsole = view.findViewById(R.id.btnClearConsole);
        btnFilterAll = view.findViewById(R.id.btnFilterAll);
        btnFilterAlerts = view.findViewById(R.id.btnFilterAlerts);
        btnFilterPass = view.findViewById(R.id.btnFilterPass);

        spBranchSelector = view.findViewById(R.id.spBranchSelector);
        tvCurrentBranch = view.findViewById(R.id.tvCurrentBranch);

        // ⭐ زر الإغلاق الجديد
        btnGateClose = view.findViewById(R.id.btnGateClose);

        rgOperationMode = view.findViewById(R.id.rgOperationMode);
        rbManual = view.findViewById(R.id.rbManual);
        rbSemiAuto = view.findViewById(R.id.rbSemiAuto);
        rbFullAuto = view.findViewById(R.id.rbFullAuto);
        swKeepScreenOn = view.findViewById(R.id.swKeepScreenOn);
        swContinuousScan = view.findViewById(R.id.swContinuousScan);
        swSoundAlerts = view.findViewById(R.id.swSoundAlerts);
        swVibration = view.findViewById(R.id.swVibration);
        tvCurrentModeBadge = view.findViewById(R.id.tvCurrentModeBadge);
        swSimulateExitOnSale = view.findViewById(R.id.swSimulateExitOnSale);

        if (rvGatesMonitor != null) {
            // ⭐ Grid ديناميكي: 1 على الهاتف، 2 على التابلت
            int spanCount = isTablet() ? 2 : 1;
            rvGatesMonitor.setLayoutManager(new GridLayoutManager(getContext(), spanCount));
            monitorAdapter = new GateMonitorAdapter();
            rvGatesMonitor.setAdapter(monitorAdapter);
            Log.d(TAG, "📊 Gate monitor grid: " + spanCount + " column(s)");
        }
    }

    private void setupListeners() {
        if (btnStopAlarm != null) btnStopAlarm.setOnClickListener(v -> stopAlarm());
        if (btnToggleScan != null) btnToggleScan.setOnClickListener(v -> toggleScanning());

        if (btnSimulateAllowed != null) {
            btnSimulateAllowed.setOnClickListener(v -> {
                if (!soldEpc.isEmpty()) queryMiraGateServer(soldEpc);
                else showToast(getLocalizedText("⚠️ لا توجد قطعة مباعة",
                        "⚠️ No sold item available"));
            });
        }
        if (btnSimulateDenied != null) {
            btnSimulateDenied.setOnClickListener(v -> {
                if (!unsoldEpc.isEmpty()) queryMiraGateServer(unsoldEpc);
                else showToast(getLocalizedText("⚠️ لا توجد قطعة غير مباعة",
                        "⚠️ No unsold item available"));
            });
        }

        if (btnScanBarcode != null) {
            btnScanBarcode.setOnClickListener(v -> {
                isCameraOpen = true;
                if (gateRfidBridge != null) {
                    gateRfidBridge.prepareForCamera(() -> {
                        ScanOptions options = new ScanOptions();
                        options.setPrompt(getLocalizedText(
                                "📷 وجه الكاميرا نحو باركود القطعة",
                                "📷 Aim camera at item barcode"));
                        options.setBeepEnabled(false);
                        options.setOrientationLocked(true);
                        try {
                            barcodeLauncher.launch(options);
                        } catch (Exception e) {
                            isCameraOpen = false;
                            logConsole(getLocalizedText("⚠️ تعذر فتح الكاميرا: ",
                                    "⚠️ Camera error: ") + e.getMessage());
                            autoStartScanning();
                        }
                    });
                }
            });
        }

        if (btnEmergencyLockdown != null) {
            btnEmergencyLockdown.setOnClickListener(v -> toggleLockdown());
        }
        if (btnClearConsole != null) {
            btnClearConsole.setOnClickListener(v -> {
                if (tvDebugConsole != null) tvDebugConsole.setText("");
            });
        }

        if (btnFilterAll != null) btnFilterAll.setOnClickListener(v -> {
            logFilter = "ALL";
            logConsole(getLocalizedText("🔍 فلتر: الكل", "🔍 Filter: All"));
        });
        if (btnFilterAlerts != null) btnFilterAlerts.setOnClickListener(v -> {
            logFilter = "ALERTS";
            logConsole(getLocalizedText("🔍 فلتر: الإنذارات", "🔍 Filter: Alerts"));
        });
        if (btnFilterPass != null) btnFilterPass.setOnClickListener(v -> {
            logFilter = "PASS";
            logConsole(getLocalizedText("🔍 فلتر: المقبول", "🔍 Filter: Pass"));
        });

        // ⭐ زر الإغلاق في الـHeader
        if (btnGateClose != null) {
            btnGateClose.setOnClickListener(v -> handleCustomBack());
        }

        setupOperationModeListeners();
    }

    // ═══════════════════════════════════════════════════════
    // ⭐ إخفاء أدوات التطوير في الإنتاج
    // ═══════════════════════════════════════════════════════

    private void hideDevTools() {
        try {
            boolean isDebugMode = (requireContext().getApplicationInfo().flags
                    & ApplicationInfo.FLAG_DEBUGGABLE) != 0;

            int visibility = isDebugMode ? View.VISIBLE : View.GONE;

            if (btnSimulateAllowed != null) btnSimulateAllowed.setVisibility(visibility);
            if (btnSimulateDenied != null) btnSimulateDenied.setVisibility(visibility);

            if (tvDebugConsole != null) tvDebugConsole.setVisibility(visibility);
            if (scrollDebugLog != null) scrollDebugLog.setVisibility(visibility);
            if (btnClearConsole != null) btnClearConsole.setVisibility(visibility);
            if (btnFilterAll != null) btnFilterAll.setVisibility(visibility);
            if (btnFilterAlerts != null) btnFilterAlerts.setVisibility(visibility);
            if (btnFilterPass != null) btnFilterPass.setVisibility(visibility);
            if (swSimulateExitOnSale != null) swSimulateExitOnSale.setVisibility(visibility);

            Log.d(TAG, "🧹 Dev tools visibility: " + (isDebugMode ? "VISIBLE" : "GONE"));
        } catch (Exception e) {
            Log.e(TAG, "hideDevTools error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════
    // ⭐ Back Press Handler — يعيد للـDashboard
    // ═══════════════════════════════════════════════════════

    private void setupBackPressHandler() {
        if (getActivity() == null) return;

        backPressedCallback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleCustomBack();
            }
        };

        requireActivity().getOnBackPressedDispatcher()
                .addCallback(getViewLifecycleOwner(), backPressedCallback);
        Log.d(TAG, "✅ Back press handler registered");
    }

    private void handleCustomBack() {
        try {
            // 1. نافذة الإنذار مفتوحة → أوقفها
            if (overlayAlarm != null && overlayAlarm.getVisibility() == View.VISIBLE) {
                stopAlarm();
                return;
            }

            // 2. في lockdown → اطلب تأكيد
            if (isLockdown) {
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(isArabic ? "⚠️ إغلاق طارئ نشط" : "⚠️ Lockdown Active")
                        .setMessage(isArabic
                                ? "هل تريد إلغاء الإغلاق الطارئ والمغادرة؟"
                                : "Cancel lockdown and leave?")
                        .setPositiveButton(isArabic ? "نعم، مغادرة" : "Yes, leave",
                                (d, w) -> {
                                    toggleLockdown();
                                    navigateAwayFromGate();
                                })
                        .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                        .show();
                return;
            }

            // 3. انتقال للـ Dashboard
            navigateAwayFromGate();

        } catch (Exception e) {
            Log.e(TAG, "handleCustomBack error: " + e.getMessage());
            navigateAwayFromGate();
        }
    }

    private void navigateAwayFromGate() {
        try {
            // أوقف كل شيء
            try { stopScanningSync(); } catch (Exception ignored) {}
            try { stopContinuousScan(); } catch (Exception ignored) {}
            try { applyKeepScreenOn(false); } catch (Exception ignored) {}

            if (getActivity() instanceof UHFMainActivity) {
                UHFMainActivity activity = (UHFMainActivity) getActivity();
                activity.setBottomNavVisible(true);

                if (activity.getNavigationManager() != null) {
                    activity.getNavigationManager().goToDashboard();
                    Log.d(TAG, "✅ Navigated to Dashboard via NavigationManager");
                } else {
                    activity.loadFragmentDirectly(
                            new MiraDashboardFragment(),
                            NavigationManager.TAG_DASHBOARD);
                    Log.d(TAG, "✅ Navigated to Dashboard (fallback)");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "navigateAwayFromGate error: " + e.getMessage());
        }
    }

    // ==================== Operation Mode ====================

    private void setupOperationModeListeners() {
        if (rgOperationMode != null) {
            rgOperationMode.setOnCheckedChangeListener((group, checkedId) -> {
                MiraGatesSettings.OperationMode newMode;
                if (checkedId == R.id.rbManual) {
                    newMode = MiraGatesSettings.OperationMode.MANUAL;
                } else if (checkedId == R.id.rbFullAuto) {
                    newMode = MiraGatesSettings.OperationMode.FULL_AUTO;
                } else {
                    newMode = MiraGatesSettings.OperationMode.SEMI_AUTO;
                }
                onOperationModeChanged(newMode);
            });
        }

        if (swKeepScreenOn != null) {
            swKeepScreenOn.setOnCheckedChangeListener((btn, checked) -> {
                miraGatesSettings.setKeepScreenOn(checked);
                applyKeepScreenOn(checked);
                logConsole(getLocalizedText(
                        "⚙️ إبقاء الشاشة: " + (checked ? "مفعّل" : "معطّل"),
                        "⚙️ Keep Screen: " + (checked ? "ON" : "OFF")));
            });
        }

        if (swContinuousScan != null) {
            swContinuousScan.setOnCheckedChangeListener((btn, checked) -> {
                miraGatesSettings.setContinuousScanEnabled(checked);
                if (checked) startContinuousScan();
                else stopContinuousScan();
                logConsole(getLocalizedText(
                        "⚙️ المسح المستمر: " + (checked ? "مفعّل" : "معطّل"),
                        "⚙️ Continuous: " + (checked ? "ON" : "OFF")));
            });
        }

        if (swSoundAlerts != null) {
            swSoundAlerts.setOnCheckedChangeListener((btn, checked) -> {
                miraGatesSettings.setVibrationEnabled(checked);
            });
        }

        if (swVibration != null) {
            swVibration.setOnCheckedChangeListener((btn, checked) -> {
                miraGatesSettings.setVibrationEnabled(checked);
            });
        }

        if (swSimulateExitOnSale != null) {
            swSimulateExitOnSale.setOnCheckedChangeListener((btn, checked) -> {
                miraGatesSettings.setSimulateExitOnSale(checked);
                logConsole(getLocalizedText(
                        "🎬 محاكاة الخروج: " + (checked ? "مفعّلة" : "معطّلة"),
                        "🎬 Simulate exit: " + (checked ? "ON" : "OFF")));
            });
        }
    }

    private void initializeOperationMode() {
        try {
            currentOperationMode = miraGatesSettings.getOperationMode();

            if (rgOperationMode != null) {
                switch (currentOperationMode) {
                    case MANUAL:
                        if (rbManual != null) rbManual.setChecked(true);
                        break;
                    case FULL_AUTO:
                        if (rbFullAuto != null) rbFullAuto.setChecked(true);
                        break;
                    default:
                        if (rbSemiAuto != null) rbSemiAuto.setChecked(true);
                        break;
                }
            }

            updateModeBadge();

            if (swKeepScreenOn != null)
                swKeepScreenOn.setChecked(miraGatesSettings.isKeepScreenOn());
            if (swContinuousScan != null)
                swContinuousScan.setChecked(miraGatesSettings.isContinuousScanEnabled());
            if (swSoundAlerts != null)
                swSoundAlerts.setChecked(true);
            if (swVibration != null)
                swVibration.setChecked(miraGatesSettings.isVibrationEnabled());
            if (swSimulateExitOnSale != null)
                swSimulateExitOnSale.setChecked(miraGatesSettings.isSimulateExitOnSale());

            applyOperationMode(currentOperationMode);
            Log.i(TAG, "✅ Operation mode initialized: " + currentOperationMode);
        } catch (Exception e) {
            Log.e(TAG, "initializeOperationMode error: " + e.getMessage(), e);
        }
    }

    private void onOperationModeChanged(MiraGatesSettings.OperationMode newMode) {
        if (currentOperationMode == newMode) return;

        Log.i(TAG, "🔄 Mode changed: " + currentOperationMode + " → " + newMode);

        stopContinuousScan();
        currentOperationMode = newMode;
        miraGatesSettings.setOperationMode(newMode);

        updateModeBadge();
        applyOperationMode(newMode);

        String modeName;
        switch (newMode) {
            case MANUAL:
                modeName = getLocalizedText("يدوي ✋", "Manual ✋");
                break;
            case SEMI_AUTO:
                modeName = getLocalizedText("شبه تلقائي 🔄", "Semi-Auto 🔄");
                break;
            case FULL_AUTO:
                modeName = getLocalizedText("تلقائي كامل ⚡", "Full Auto ⚡");
                break;
            default:
                modeName = "?";
        }
        logConsole(getLocalizedText("🎯 تم تغيير الوضع إلى: " + modeName,
                "🎯 Mode changed to: " + modeName));
    }

    private void applyOperationMode(MiraGatesSettings.OperationMode mode) {
        try {
            applyKeepScreenOn(miraGatesSettings.shouldKeepScreenOn());

            switch (mode) {
                case MANUAL:
                    logConsole(getLocalizedText(
                            "💡 اضغط تفعيل RFID لبدء المسح",
                            "💡 Press RFID to start scan"));
                    break;

                case SEMI_AUTO:
                    if (miraGatesSettings.isContinuousScanEnabled()) {
                        startContinuousScan();
                    }
                    applyKeepScreenOn(true);
                    break;

                case FULL_AUTO:
                    startContinuousScan();
                    applyKeepScreenOn(true);
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "applyOperationMode error: " + e.getMessage(), e);
        }
    }

    private void updateModeBadge() {
        if (tvCurrentModeBadge == null) return;
        String text;
        switch (currentOperationMode) {
            case MANUAL:
                text = getLocalizedText("✋ يدوي", "✋ Manual");
                break;
            case SEMI_AUTO:
                text = getLocalizedText("🔄 شبه تلقائي", "🔄 Semi-Auto");
                break;
            case FULL_AUTO:
                text = getLocalizedText("⚡ تلقائي", "⚡ Auto");
                break;
            default:
                text = "?";
        }
        tvCurrentModeBadge.setText(text);
    }

    private void applyKeepScreenOn(boolean keepOn) {
        try {
            if (getActivity() == null || getActivity().getWindow() == null) return;
            if (keepOn) {
                getActivity().getWindow().addFlags(
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getActivity().getWindow().clearFlags(
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        } catch (Exception e) {
            Log.e(TAG, "applyKeepScreenOn error: " + e.getMessage());
        }
    }

    private void startContinuousScan() {
        if (isDestroyed) return;
        mainHandler.removeCallbacks(continuousScanTask);
        mainHandler.postDelayed(continuousScanTask, CONTINUOUS_SCAN_INITIAL_DELAY_MS);
        Log.d(TAG, "▶️ Continuous scan started");
    }

    private void stopContinuousScan() {
        mainHandler.removeCallbacks(continuousScanTask);
        Log.d(TAG, "⏹️ Continuous scan stopped");
    }
        // ==================== Branch Management ====================

    private void loadCurrentBranchInfo() {
        try {
            Context context = getContext();
            if (context == null) return;

            currentBranchId = branchesManager != null
                    ? branchesManager.getActiveBranchId() : 0;
            isGlobalAdmin = permissionManager != null &&
                    (permissionManager.isSystemAdmin() || permissionManager.isOrgAdmin());

            mainHandler.post(() -> {
                if (isDestroyed || !isAdded()) return;
                if (isGlobalAdmin) {
                    loadBranchesForSelector();
                } else {
                    if (spBranchSelector != null)
                        spBranchSelector.setVisibility(View.GONE);
                    if (tvCurrentBranch != null) {
                        tvCurrentBranch.setText(branchesManager != null
                                ? branchesManager.getActiveBranchName() : "");
                        tvCurrentBranch.setVisibility(View.VISIBLE);
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "loadCurrentBranchInfo: " + e.getMessage());
        }
    }

    private void loadBranchesForSelector() {
        executeOnGateExecutor(() -> {
            try {
                Context context = getContext();
                if (context == null || isDestroyed) return;

                List<?> allBranches = branchesManager != null
                        ? branchesManager.getAllBranches() : new ArrayList<>();

                branchesList.clear();
                if (allBranches != null) {
                    for (Object branchObj : allBranches) {
                        BranchInfo info = convertToBranchInfo(branchObj);
                        if (info != null) branchesList.add(info);
                    }
                }

                if (!branchesList.isEmpty()) {
                    mainHandler.post(() -> {
                        if (spBranchSelector != null && isAdded() && !isDestroyed) {
                            List<String> branchNames = new ArrayList<>();
                            branchNames.add(getLocalizedText("🌐 كل الفروع", "🌐 All Branches"));
                            for (BranchInfo branch : branchesList)
                                branchNames.add(branch.branchName);

                            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                                    context, android.R.layout.simple_spinner_item, branchNames);
                            adapter.setDropDownViewResource(
                                    android.R.layout.simple_spinner_dropdown_item);
                            spBranchSelector.setAdapter(adapter);

                            if (currentBranchId > 0) {
                                for (int i = 0; i < branchesList.size(); i++) {
                                    if (branchesList.get(i).branchId == currentBranchId) {
                                        spBranchSelector.setSelection(i + 1);
                                        break;
                                    }
                                }
                            }

                            spBranchSelector.setOnItemSelectedListener(
                                    new AdapterView.OnItemSelectedListener() {
                                @Override
                                public void onItemSelected(AdapterView<?> parent,
                                        View view, int position, long id) {
                                    if (position == 0) currentBranchId = 0;
                                    else if (position - 1 < branchesList.size()) {
                                        currentBranchId =
                                                branchesList.get(position - 1).branchId;
                                    }
                                    loadAllGatesStatus();
                                    String branchName = position == 0
                                            ? getLocalizedText("كل الفروع", "All Branches")
                                            : branchesList.get(position - 1).branchName;
                                    logConsole(getLocalizedText(
                                            "🔄 تم تغيير الفرع إلى: ",
                                            "🔄 Branch changed to: ") + branchName);
                                }

                                @Override
                                public void onNothingSelected(AdapterView<?> parent) {}
                            });

                            spBranchSelector.setVisibility(View.VISIBLE);
                            if (tvCurrentBranch != null)
                                tvCurrentBranch.setVisibility(View.GONE);
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "loadBranchesForSelector: " + e.getMessage());
            }
        });
    }

    private BranchInfo convertToBranchInfo(Object obj) {
        if (obj == null) return null;
        BranchInfo info = new BranchInfo();
        Class<?> clazz = obj.getClass();
        info.branchId = extractIntProperty(clazz, obj,
                new String[]{"getId", "getBranchId", "id", "branchId"});
        info.branchName = extractStringProperty(clazz, obj,
                new String[]{"getName", "getBranchName", "name", "branchName"});
        if (info.branchName == null || info.branchName.isEmpty()) {
            info.branchName = (isArabic ? "فرع " : "Branch ") + info.branchId;
        }
        return info;
    }

    private int extractIntProperty(Class<?> clazz, Object obj, String[] names) {
        for (String name : names) {
            try {
                java.lang.reflect.Method method = clazz.getMethod(name);
                Object result = method.invoke(obj);
                if (result instanceof Number) return ((Number) result).intValue();
            } catch (Throwable ignored) {}
            try {
                java.lang.reflect.Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                Object result = field.get(obj);
                if (result instanceof Number) return ((Number) result).intValue();
            } catch (Throwable ignored) {}
        }
        return 0;
    }

    private String extractStringProperty(Class<?> clazz, Object obj, String[] names) {
        for (String name : names) {
            try {
                java.lang.reflect.Method method = clazz.getMethod(name);
                Object result = method.invoke(obj);
                if (result != null) return result.toString();
            } catch (Throwable ignored) {}
            try {
                java.lang.reflect.Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                Object result = field.get(obj);
                if (result != null) return result.toString();
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private int getCurrentBranchId() { return currentBranchId; }

    private String getCurrentBranchName() {
        try {
            if (currentBranchId == 0)
                return getLocalizedText("كل الفروع", "All Branches");
            for (BranchInfo branch : branchesList) {
                if (branch.branchId == currentBranchId) return branch.branchName;
            }
            return branchesManager != null
                    ? branchesManager.getActiveBranchName()
                    : String.valueOf(currentBranchId);
        } catch (Exception e) {
            return String.valueOf(currentBranchId);
        }
    }

    // ==================== Background Service ====================

    private void startBackgroundService() {
        try {
            Context context = getContext();
            if (context != null) {
                Intent serviceIntent = new Intent(context, MiraGateMonitorService.class);
                context.startForegroundService(serviceIntent);
                logConsole(getLocalizedText(
                        "📱 خدمة المراقبة الخلفية مفعلة",
                        "📱 Background monitoring service enabled"));
            }
        } catch (Exception e) {
            Log.e(TAG, "Start service: " + e.getMessage());
        }
    }

    // ==================== Lockdown ====================

    private void toggleLockdown() {
        isLockdown = !isLockdown;
        if (isLockdown) {
            lockAllGates();
            updateSystemStatus(false);
            logConsole(getLocalizedText("🔴 تم تفعيل الإغلاق الطارئ",
                    "🔴 LOCKDOWN ACTIVATED"));
            if (btnEmergencyLockdown != null) {
                btnEmergencyLockdown.setText(getLocalizedText(
                        "🔓 فتح البوابات", "🔓 Unlock Gates"));
                btnEmergencyLockdown.setBackgroundColor(getSuccessColor());
            }
        } else {
            unlockAllGates();
            updateSystemStatus(true);
            logConsole(getLocalizedText("🟢 تم إلغاء الإغلاق الطارئ",
                    "🟢 LOCKDOWN DEACTIVATED"));
            if (btnEmergencyLockdown != null) {
                btnEmergencyLockdown.setText(getLocalizedText(
                        "🔒 إغلاق طارئ", "🔒 Emergency Lockdown"));
                btnEmergencyLockdown.setBackgroundColor(getDangerColor());
            }
        }
    }

    private void lockAllGates() {
        executeOnGateExecutor(() -> {
            try {
                Context context = getContext();
                if (context == null) return;
                MiraApiClient client = MiraApiClient.getInstance(context);
                JSONObject payload = new JSONObject();
                payload.put("command", "LOCK_ALL");
                payload.put("branch_id", getCurrentBranchId());
                client.postWithAuth("/gate-command", payload);
            } catch (Exception e) {
                Log.e(TAG, "lockAllGates: " + e.getMessage());
            }
        });
    }

    private void unlockAllGates() {
        executeOnGateExecutor(() -> {
            try {
                Context context = getContext();
                if (context == null) return;
                MiraApiClient client = MiraApiClient.getInstance(context);
                JSONObject payload = new JSONObject();
                payload.put("command", "UNLOCK_ALL");
                payload.put("branch_id", getCurrentBranchId());
                client.postWithAuth("/gate-command", payload);
            } catch (Exception e) {
                Log.e(TAG, "unlockAllGates: " + e.getMessage());
            }
        });
    }

    private void startPulseAnimation(View view) {
        if (view == null) return;
        Animation anim = new AlphaAnimation(0.3f, 1.0f);
        anim.setDuration(400);
        anim.setRepeatMode(Animation.REVERSE);
        anim.setRepeatCount(Animation.INFINITE);
        view.startAnimation(anim);
    }

    private void toggleGateState(String gateId, String currentStatus) {
        if (gateId == null || gateId.isEmpty()) return;
        executeOnGateExecutor(() -> {
            try {
                Context context = getContext();
                if (context == null) return;
                MiraApiClient client = MiraApiClient.getInstance(context);
                JSONObject payload = new JSONObject();
                payload.put("gate_id", gateId);

                String command;
                if ("offline".equals(currentStatus) || currentStatus == null) {
                    command = "RECONNECT";
                } else if ("alert".equals(currentStatus) || "breach".equals(currentStatus)) {
                    command = "RESET_ALARM";
                } else {
                    command = "TOGGLE_LOCK";
                }
                payload.put("command", command);
                payload.put("branch_id", getCurrentBranchId());

                MiraApiClient.ApiResponse response =
                        client.postWithAuth("/gate-command", payload);

                mainHandler.post(() -> {
                    if (isDestroyed || !isAdded()) return;
                    if (response != null && response.isSuccess) {
                        logConsole(getLocalizedText("✅ تم إرسال أمر للبوابة: ",
                                "✅ Command sent to gate: ") + gateId);
                        loadAllGatesStatus();
                    } else {
                        logConsole(getLocalizedText("❌ فشل إرسال الأمر للبوابة: ",
                                "❌ Failed to send command to gate: ") + gateId);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "toggleGateState: " + e.getMessage());
                mainHandler.post(() -> {
                    if (isAdded() && !isDestroyed) {
                        logConsole(getLocalizedText("❌ خطأ في التحكم بالبوابة: ",
                                "❌ Gate control error: ") + e.getMessage());
                    }
                });
            }
        });
    }

    // ==================== Adaptive Monitoring ====================

    private void startMonitoring() {
        try {
            scheduler = Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "MiraGateScheduler");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
            scheduleNextPoll();
            scheduleNextPing();
            loadAllGatesStatus();
            refreshAllowedCacheIfNeeded(true);
        } catch (Exception e) {
            Log.e(TAG, "startMonitoring error: " + e.getMessage(), e);
        }
    }

    private void scheduleNextPoll() {
        if (isDestroyed || scheduler == null || scheduler.isShutdown()) return;
        try {
            pollFuture = scheduler.schedule(() -> {
                if (isDestroyed) return;
                try {
                    loadAllGatesStatus();
                    checkConnection();
                } catch (Exception e) {
                    Log.e(TAG, "Poll task error: " + e.getMessage(), e);
                }
                scheduleNextPoll();
            }, currentPollInterval, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "scheduleNextPoll rejected: " + e.getMessage());
        }
    }

    private void scheduleNextPing() {
        if (isDestroyed || scheduler == null || scheduler.isShutdown()) return;
        try {
            pingFuture = scheduler.schedule(() -> {
                if (isDestroyed) return;
                try {
                    checkPing();
                } catch (Exception e) {
                    Log.e(TAG, "Ping task error: " + e.getMessage(), e);
                }
                scheduleNextPing();
            }, currentPingInterval, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "scheduleNextPing rejected: " + e.getMessage());
        }
    }

    private void checkConnection() {
        if (isDestroyed || !isAdded()) return;
        Context context = getContext();
        if (context == null) return;
        MiraApiClient client = MiraApiClient.getInstance(context);

        client.pingApiAsync(isOnlineNow -> {
            if (isDestroyed || !isAdded()) return;
            boolean wasOnline = isOnline;
            isOnline = isOnlineNow;

            if (isOnlineNow) {
                consecutivePollFailures = 0;
                currentPollInterval = POLL_INITIAL_MS;
            } else {
                consecutivePollFailures++;
                long newInterval = Math.min(
                        POLL_INITIAL_MS * (1L << Math.min(consecutivePollFailures, 4)),
                        POLL_MAX_MS);
                if (newInterval != currentPollInterval) {
                    currentPollInterval = newInterval;
                }
            }

            if (isOnline != wasOnline && isAdded() && !isDestroyed) {
                if (isOnline) {
                    logConsole(getLocalizedText("🟢 عاد الاتصال - جاري المزامنة...",
                            "🟢 Connection restored - syncing..."));
                    executeOnGateExecutor(() -> {
                        refreshAllowedCacheIfNeeded(true);
                        syncOfflineEvents();
                    });
                } else {
                    logConsole(getLocalizedText("🔴 انقطع الاتصال - الوضع المحلي",
                            "🔴 Connection lost - local mode"));
                }
                if (offlineCache != null) offlineCache.saveConnectionState(isOnline);
            }
        });
    }

    private void checkPing() {
        if (isDestroyed || !isAdded()) return;
        Context context = getContext();
        if (context == null) return;

        MiraApiClient client = MiraApiClient.getInstance(context);
        long start = System.currentTimeMillis();

        client.pingApiAsync(ok -> {
            if (isDestroyed || !isAdded()) return;
            long latency = System.currentTimeMillis() - start;

            if (ok) {
                consecutivePingFailures = 0;
                currentPingInterval = PING_INITIAL_MS;
            } else {
                consecutivePingFailures++;
                long newInterval = Math.min(
                        PING_INITIAL_MS * (1L << Math.min(consecutivePingFailures, 3)),
                        PING_MAX_MS);
                if (newInterval != currentPingInterval) {
                    currentPingInterval = newInterval;
                }
            }

            if (tvPingLatency != null && isAdded() && !isDestroyed) {
                if (ok) {
                    tvPingLatency.setText(getLocalizedText("📡 Ping: ", "📡 Ping: ")
                            + latency + " ms | ✅ " + getLocalizedText("متصل", "Connected"));
                    tvPingLatency.setTextColor(getSuccessColor());
                } else {
                    tvPingLatency.setText(getLocalizedText("📡 Ping: -- | ⚠️ غير متصل",
                            "📡 Ping: -- | ⚠️ Disconnected"));
                    tvPingLatency.setTextColor(getWarningColor());
                }
            }
        });
    }

    private void updateSystemStatus(boolean armed) {
        if (tvSystemStatus == null) return;
        if (isLockdown) {
            tvSystemStatus.setText(getLocalizedText("🔴 إغلاق طارئ", "🔴 LOCKDOWN"));
            tvSystemStatus.setTextColor(getDangerColor());
        } else if (armed) {
            tvSystemStatus.setText(getLocalizedText("🟢 النظام مسلح", "🟢 SYSTEM ARMED"));
            tvSystemStatus.setTextColor(getSuccessColor());
        } else {
            tvSystemStatus.setText(getLocalizedText("🟡 وضع الاستعداد", "🟡 STANDBY"));
            tvSystemStatus.setTextColor(getWarningColor());
        }
    }

    private void updateLiveTicker(String message, boolean isAlert) {
        if (tvLiveTickerText == null) return;
        tvLiveTickerText.setText(message);
        tvLiveTickerText.setTextColor(isAlert ? getDangerColor() : getSuccessColor());
        if (isAlert) {
            Animation anim = new AlphaAnimation(0.3f, 1.0f);
            anim.setDuration(300);
            anim.setRepeatMode(Animation.REVERSE);
            anim.setRepeatCount(5);
            tvLiveTickerText.startAnimation(anim);
        }
    }

    // ==================== RFID Scanning ====================

    private void autoStartScanning() {
    // ✅ فحص مزدوج قبل الجدولة
    if (isScanning) return;
    if (gateRfidBridge != null && gateRfidBridge.isScanning()) return;

    mainHandler.postDelayed(() -> {
        if (isDestroyed || !isAdded() || isLockdown || isCameraOpen) return;
        // ✅ فحص ثانٍ قبل التنفيذ
        if (isScanning) return;
        if (gateRfidBridge != null && gateRfidBridge.isScanning()) return;
        startScanning();
    }, 1200);
}

    private void toggleScanning() {
        if (isScanning) stopScanning();
        else startScanning();
    }
private void startScanning() {
    if (isDestroyed || mContext == null || isCameraOpen) return;
    if (gateRfidBridge == null) return;
    
    // ✅ فحص إضافي: لا تبدأ إذا كان المسح يعمل
    if (isScanning) {
        Log.d(TAG, "⏸️ Scan already active — skipping");
        return;
    }
        boolean accepted = gateRfidBridge.startScan(
                new GateRfidBridge.GateTagListener() {
            @Override
            public void onTagRead(String epc, int rssi) {
                if (!isDestroyed && isAdded() && epc != null && !epc.isEmpty()) {
                    processInputEpc(epc, "RFID");
                }
            }

            @Override
            public void onScanStarted() {
                isScanning = true;
                mainHandler.post(() -> {
                    if (btnToggleScan != null) {
                        btnToggleScan.setText(getLocalizedText(
                                "🔴 إيقاف المسح", "🔴 Stop Scan"));
                    }
                    logConsole(getLocalizedText(
                            "🛡️ قارئ RFID يعمل", "🛡️ RFID reader active"));
                });
            }

            @Override
            public void onScanStopped() {
                isScanning = false;
                mainHandler.post(() -> {
                    if (btnToggleScan != null) {
                        btnToggleScan.setText(getLocalizedText(
                                "🛡️ تفعيل المسح", "🛡️ Start Scan"));
                    }
                });
            }

            @Override
            public void onError(String error) {
                MiraFileLogger.logError(TAG, "Gate scan error: " + error);
                mainHandler.post(() -> {
                    if (isAdded() && !isDestroyed) {
                        logConsole("❌ " + getLocalizedText(
                                "خطأ المسح: ", "Scan error: ") + error);
                    }
                });
            }
        }, miraGatesSettings.getRfidPower());

        if (!accepted) {
            logConsole(getLocalizedText(
                    "⚠️ القارئ غير جاهز", "⚠️ Reader not ready"));
            isScanning = false;
        }
    }

    private void stopScanning() {
        if (gateRfidBridge == null) return;
        gateRfidBridge.stopScan(() -> {
            isScanning = false;
            mainHandler.post(() -> {
                if (btnToggleScan != null) {
                    btnToggleScan.setText(getLocalizedText(
                            "🛡️ تفعيل المسح", "🛡️ Start Scan"));
                }
            });
        });
    }

    private void stopScanningSync() {
        if (gateRfidBridge != null) {
            gateRfidBridge.stopScan(null);
        }
        isScanning = false;
        mainHandler.post(() -> {
            if (btnToggleScan != null) {
                btnToggleScan.setText(getLocalizedText(
                        "🛡️ تفعيل المسح", "🛡️ Start Scan"));
            }
        });
    }

    // ==================== Gate Status ====================

    private void loadAllGatesStatus() {
        if (!statusLoadInProgress.compareAndSet(false, true)) return;

        executeOnGateExecutor(() -> {
            try {
                Context context = getContext();
                if (context == null || isDestroyed) {
                    statusLoadInProgress.set(false);
                    return;
                }

                MiraApiClient client = MiraApiClient.getInstance(context);
                MiraApiClient.ApiResponse response = client.getGatesByBranch(
                        currentBranchId > 0 ? currentBranchId : 0);

                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray arr = extractGatesArray(response.data);
                    if (arr != null) {
                        List<GateMonitorItem> items = new ArrayList<>();
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject g = arr.optJSONObject(i);
                            if (g == null) continue;
                            GateMonitorItem item = new GateMonitorItem();
                            item.gateId = g.optString("gate_id", "");
                            item.gateName = g.optString("gate_name",
                                    getLocalizedText("بوابة", "Gate"));
                            item.location = g.optString("gate_location", "");
                            item.status = g.optString("status", "offline");
                            item.passCount = g.optInt("pass_count", 0);
                            item.denyCount = g.optInt("deny_count", 0);
                            item.lastEvent = g.optString("last_event", "");
                            item.branchId = g.optInt("branch_id", 0);
                            item.branchName = g.optString("branch_name", "");
                            items.add(item);
                        }
                        mainHandler.post(() -> {
                            statusLoadInProgress.set(false);
                            if (isAdded() && !isDestroyed) updateMonitorList(items);
                        });
                        return;
                    }
                }
                mainHandler.post(() -> statusLoadInProgress.set(false));
            } catch (Exception e) {
                Log.e(TAG, "loadAllGatesStatus: " + e.getMessage());
                statusLoadInProgress.set(false);
            }
        });
    }

    private JSONArray extractGatesArray(JSONObject data) {
        JSONArray arr = data.optJSONArray("gates");
        if (arr != null) return arr;
        JSONObject d = data.optJSONObject("data");
        return d != null ? d.optJSONArray("gates") : null;
    }

    private void updateMonitorList(List<GateMonitorItem> newItems) {
        if (monitorAdapter == null) return;

        List<GateMonitorItem> oldItems = new ArrayList<>(monitorList);

        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() { return oldItems.size(); }

            @Override
            public int getNewListSize() { return newItems.size(); }

            @Override
            public boolean areItemsTheSame(int oldPos, int newPos) {
                return Objects.equals(oldItems.get(oldPos).gateId,
                        newItems.get(newPos).gateId);
            }

            @Override
            public boolean areContentsTheSame(int oldPos, int newPos) {
                GateMonitorItem o = oldItems.get(oldPos);
                GateMonitorItem n = newItems.get(newPos);
                return Objects.equals(o.status, n.status)
                        && o.passCount == n.passCount
                        && o.denyCount == n.denyCount
                        && Objects.equals(o.lastEvent, n.lastEvent)
                        && Objects.equals(o.branchName, n.branchName);
            }
        });

        monitorList.clear();
        monitorList.addAll(newItems);
        diffResult.dispatchUpdatesTo(monitorAdapter);

        int pass = 0, deny = 0;
        for (GateMonitorItem item : newItems) {
            pass += item.passCount;
            deny += item.denyCount;
        }
        if (tvPassCount != null) tvPassCount.setText(String.valueOf(pass));
        if (tvDenyCount != null) tvDenyCount.setText(String.valueOf(deny));
        if (tvTotalCount != null) tvTotalCount.setText(String.valueOf(newItems.size()));

        updateRiskMeter(pass, deny);

        if (tvCurrentBranch != null) {
            String branchLabel;
            if (currentBranchId == 0) {
                branchLabel = getLocalizedText("🌐 كل الفروع", "🌐 All Branches");
            } else {
                branchLabel = getLocalizedText("📍 الفرع: ", "📍 Branch: ")
                        + getCurrentBranchName();
            }
            tvCurrentBranch.setText(branchLabel);
        }
    }

    private void updateRiskMeter(int pass, int deny) {
        int total = pass + deny;
        if (total > 0) {
            int risk = (deny * 100) / total;
            if (pbRiskMeter != null) pbRiskMeter.setProgress(risk);
            if (tvRiskPercentage != null) {
                tvRiskPercentage.setText(getLocalizedText(
                        "نسبة الإنذارات: ", "Alert Rate: ") + risk + "%");
            }
        }
    }

    private void loadTestEpcs() {
        executeOnGateExecutor(() -> {
            try {
                Context context = getContext();
                if (context == null || isDestroyed) return;
                MiraApiClient client = MiraApiClient.getInstance(context);
                MiraApiClient.ApiResponse response = client.getTestEpcs();
                if (response != null && response.isSuccess && response.data != null) {
                    soldEpc = response.data.optString("sold_epc", "");
                    unsoldEpc = response.data.optString("unsold_epc", "");
                    mainHandler.post(() -> {
                        if (isAdded() && !isDestroyed) {
                            logConsole(getLocalizedText("✅ EPCs للمحاكاة جاهزة",
                                    "✅ Simulation EPCs ready"));
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "loadTestEpcs: " + e.getMessage());
            }
        });
    }

    private void syncOfflineEvents() {
        executeOnGateExecutor(() -> {
            try {
                Context context = getContext();
                if (context == null || isDestroyed || offlineCache == null) return;
                JSONArray pendingEvents = offlineCache.getOfflineEvents();
                if (pendingEvents == null || pendingEvents.length() == 0) return;

                MiraApiClient client = MiraApiClient.getInstance(context);
                MiraApiClient.ApiResponse response =
                        client.syncOfflineTransactions(pendingEvents);

                if (response != null && response.isSuccess) {
                    offlineCache.clearOfflineEvents();
                    offlineCache.saveLastSyncTime(System.currentTimeMillis());
                    mainHandler.post(() -> {
                        if (isAdded() && !isDestroyed) {
                            logConsole(getLocalizedText("✅ تمت مزامنة ", "✅ Synced ")
                                    + pendingEvents.length()
                                    + getLocalizedText(" حدث", " events"));
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Sync offline: " + e.getMessage());
            }
        });
    }

    private void refreshAllowedCacheIfNeeded(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && (now - lastAllowedCacheRefresh) < ALLOWED_CACHE_REFRESH_MS) return;
        lastAllowedCacheRefresh = now;

        executeOnGateExecutor(() -> {
            try {
                Context context = getContext();
                if (context == null || isDestroyed) return;
                MiraApiClient client = MiraApiClient.getInstance(context);
                MiraApiClient.ApiResponse response =
                        client.getWithAuth("/allowed-list");

                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray arr = response.data.optJSONArray("allowed_epcs");
                    if (arr != null) {
                        Set<String> allowed = ConcurrentHashMap.newKeySet();
                        for (int i = 0; i < arr.length(); i++) {
                            String epc = arr.optString(i, "");
                            if (!epc.isEmpty()) allowed.add(epc);
                        }
                        localAllowedEpcCache.clear();
                        localAllowedEpcCache.addAll(allowed);
                        if (offlineCache != null) offlineCache.saveAllowedEpcs(allowed);
                        mainHandler.post(() -> {
                            if (isAdded() && !isDestroyed) {
                                logConsole(getLocalizedText("✅ القائمة المسموحة: ",
                                        "✅ Allowed list: ") + allowed.size());
                            }
                        });
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "refreshAllowedCache error: " + e.getMessage());
            }
        });
    }

    // ==================== EPC Processing ====================

    private void processInputEpcBackground(String epc, String sourceType) {
        if (isDestroyed || epc == null || epc.trim().isEmpty()) return;
        final String cleanedEpc = epc.trim().toUpperCase();

        long now = System.currentTimeMillis();
        Long last = scanDebounceMap.putIfAbsent(cleanedEpc, now);
        if (last != null && now - last < SCAN_COOL_DOWN_MS) return;
        scanDebounceMap.put(cleanedEpc, now);

        logConsole("[".concat(sourceType).concat("] ") + cleanedEpc);

        if (offlineCache != null && offlineCache.isAllowedLocally(cleanedEpc)) {
            mainHandler.post(() -> {
                if (isAdded() && !isDestroyed) {
                    handleGateDecision(true,
                            getLocalizedText("قطعة مصرح بها (محلي)",
                                    "Authorized (local)"),
                            cleanedEpc);
                }
            });
            return;
        }

        if (!isOnline) {
            logConsole(getLocalizedText("⚠️ بدون إنترنت - رفض أمني",
                    "⚠️ Offline - security denial"));
            mainHandler.post(() -> {
                if (isAdded() && !isDestroyed) {
                    handleGateDecision(false,
                            getLocalizedText("بدون إنترنت", "Offline"),
                            cleanedEpc);
                    if (offlineCache != null) {
                        offlineCache.saveOfflineEvent(cleanedEpc, false,
                                getLocalizedText("بدون إنترنت", "Offline"), "local");
                    }
                }
            });
            return;
        }

        queryMiraGateServer(cleanedEpc);
    }

    private void processInputEpc(String epc, String sourceType) {
        if (gateExecutor != null && !gateExecutor.isShutdown()) {
            final String finalEpc = epc;
            final String finalSource = sourceType;
            executeOnGateExecutor(() ->
                    processInputEpcBackground(finalEpc, finalSource));
        } else {
            processInputEpcBackground(epc, sourceType);
        }
    }

    private void queryMiraGateServer(String epc) {
        executeOnGateExecutor(() -> {
            try {
                Context context = getContext();
                if (context == null || isDestroyed) return;

                MiraApiClient client = MiraApiClient.getInstance(context);
                JSONObject params = new JSONObject();
                params.put("context", "gate");
                params.put("branch_id", getCurrentBranchId());

                Future<MiraApiClient.ApiResponse> future = gateExecutor.submit(
                        () -> client.authorize(epc, "gate", params));
                MiraApiClient.ApiResponse response =
                        future.get(API_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                if (response != null && response.isSuccess && response.data != null) {
                    JSONObject decision = response.data.optJSONObject("decision");
                    JSONObject item = response.data.optJSONObject("item");
                    boolean allowed = decision != null
                            && decision.optBoolean("allowed", false);
                    String itemTitle = item != null
                            ? item.optString("title",
                                    getLocalizedText("قطعة", "Item"))
                            : getLocalizedText("قطعة", "Item");
                    String serial = item != null ? item.optString("serial", epc) : epc;
                    String message = decision != null
                            ? decision.optString("message", "") : "";

                    if (allowed) localAllowedEpcCache.add(epc);

                    mainHandler.post(() -> {
                        if (isDestroyed || !isAdded()) return;
                        handleGateDecision(allowed, itemTitle, serial);
                        if (!message.isEmpty())
                            logConsole((allowed ? "✅ " : "🚨 ") + message);
                    });
                }
            } catch (TimeoutException e) {
                mainHandler.post(() -> {
                    if (isAdded() && !isDestroyed) {
                        handleGateDecision(false,
                                getLocalizedText("انقطاع اتصال", "Timeout"), epc);
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (isAdded() && !isDestroyed) {
                        handleGateDecision(false,
                                getLocalizedText("غير مصرح", "Unauthorized"), epc);
                    }
                });
            }
        });
    }

    // ==================== Gate Decision Handling ====================

    private void handleGateDecision(boolean allowed, String itemTitle, String serial) {
        if (isDestroyed || !isAdded()) return;
        if (allowed && "ALERTS".equals(logFilter)) return;
        if (!allowed && "PASS".equals(logFilter)) return;

        boolean soundEnabled = miraGatesSettings != null
                && miraGatesSettings.isVibrationEnabled();
        boolean vibrationEnabled = miraGatesSettings != null
                && miraGatesSettings.isVibrationEnabled();
        boolean quietMode = currentOperationMode
                == MiraGatesSettings.OperationMode.FULL_AUTO;

        if (allowed) {
            logConsole(getLocalizedText("✅ خروج شرعي: ", "✅ Legal exit: ") + itemTitle);
            updateLiveTicker("✅ " + itemTitle, false);

            if (soundEnabled) {
                SoundGenerator.playSound(miraGatesSettings.getSoundPass());
            }

            incrementPassCountLocally();
            reportExitEventToServer(serial, itemTitle, true);

            if (currentOperationMode == MiraGatesSettings.OperationMode.FULL_AUTO) {
                showResultOverlay(true, itemTitle);
            }
        } else {
            logConsole(getLocalizedText("🚨 خروج غير شرعي: ", "🚨 Illegal exit: ")
                    + itemTitle);
            updateLiveTicker("🚨 " + itemTitle, true);

            if (vibrationEnabled) {
                triggerAlarm(itemTitle, serial);
            }

            if (soundEnabled && !quietMode) {
                SoundGenerator.playSound(miraGatesSettings.getSoundAlarm());
            }

            incrementDenyCountLocally();
            reportExitEventToServer(serial, itemTitle, false);

            showResultOverlay(false, itemTitle);
        }
    }

    private void showResultOverlay(boolean allowed, String itemTitle) {
        if (isDestroyed || !isAdded() || overlayAlarm == null) return;
        try {
            if (tvOverlayTitle != null) {
                tvOverlayTitle.setText(allowed
                        ? getLocalizedText("✅ مقبول", "✅ ACCEPTED")
                        : getLocalizedText("🚨 مرفوض", "🚨 REJECTED"));
            }
            if (tvOverlayDetails != null) {
                tvOverlayDetails.setText(itemTitle);
            }
            overlayAlarm.setBackgroundColor(
                    allowed ? getSuccessColor() : getDangerColor());
            overlayAlarm.setVisibility(View.VISIBLE);

            long hideDelay = allowed ? OVERLAY_PASS_MS : OVERLAY_DENY_MS;
            mainHandler.postDelayed(() -> {
                if (!isDestroyed && overlayAlarm != null) {
                    overlayAlarm.setVisibility(View.GONE);
                }
            }, hideDelay);
        } catch (Exception e) {
            Log.e(TAG, "showResultOverlay error: " + e.getMessage());
        }
    }

    private void incrementPassCountLocally() {
        localPassCount++;
        final int newValue = localPassCount;
        mainHandler.post(() -> {
            if (!isAdded() || isDestroyed || tvPassCount == null) return;
            try {
                int current = 0;
                try {
                    current = Integer.parseInt(tvPassCount.getText().toString().trim());
                } catch (NumberFormatException ignored) {}
                tvPassCount.setText(String.valueOf(current + 1));
            } catch (Exception e) {
                tvPassCount.setText(String.valueOf(newValue));
            }
            updateRiskMeter(localPassCount, localDenyCount);
        });
    }

    private void incrementDenyCountLocally() {
        localDenyCount++;
        final int newValue = localDenyCount;
        mainHandler.post(() -> {
            if (!isAdded() || isDestroyed || tvDenyCount == null) return;
            try {
                int current = 0;
                try {
                    current = Integer.parseInt(tvDenyCount.getText().toString().trim());
                } catch (NumberFormatException ignored) {}
                tvDenyCount.setText(String.valueOf(current + 1));
            } catch (Exception e) {
                tvDenyCount.setText(String.valueOf(newValue));
            }
            updateRiskMeter(localPassCount, localDenyCount);
        });
    }

    private void reportExitEventToServer(String epc, String itemTitle, boolean allowed) {
        executeOnGateExecutor(() -> {
            try {
                Context context = getContext();
                if (context == null || isDestroyed) return;

                MiraApiClient client = MiraApiClient.getInstance(context);
                JSONObject payload = new JSONObject();
                payload.put("epc", epc);
                payload.put("item_title", itemTitle);
                payload.put("allowed", allowed);
                payload.put("gate_id", getCurrentGateId());
                payload.put("branch_id", getCurrentBranchId());
                payload.put("timestamp", System.currentTimeMillis());
                payload.put("event_type", allowed ? "PASS" : "DENY");

                MiraApiClient.ApiResponse response =
                        client.postWithAuth("/gate-exit-event", payload);

                if (response != null && response.isSuccess) {
                    Log.d(TAG, "✅ Exit event reported: " + epc + " allowed=" + allowed);
                } else {
                    if (offlineCache != null) {
                        offlineCache.saveOfflineEvent(epc, allowed, itemTitle,
                                getCurrentGateId());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "reportExitEventToServer error: " + e.getMessage());
                try {
                    if (offlineCache != null) {
                        offlineCache.saveOfflineEvent(epc, allowed, itemTitle,
                                getCurrentGateId());
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    private String getCurrentGateId() {
        try {
            if (monitorList != null && !monitorList.isEmpty()) {
                return monitorList.get(0).gateId;
            }
        } catch (Exception ignored) {}
        return "GATE-01";
    }

    private void triggerAlarm(String itemTitle, String serial) {
        mainHandler.post(() -> {
            if (isDestroyed || !isAdded()) return;

            if (overlayAlarm != null) {
                if (tvOverlayTitle != null) {
                    tvOverlayTitle.setText(getLocalizedText(
                            "🚨 إنذار: خروج غير مصرح",
                            "🚨 ALERT: UNAUTHORIZED"));
                }
                if (tvOverlayDetails != null) {
                    tvOverlayDetails.setText(itemTitle + "\n[" + serial + "]");
                }
                overlayAlarm.setVisibility(View.VISIBLE);
                overlayAlarm.setBackgroundColor(getDangerColor());

                Animation anim = new AlphaAnimation(0.4f, 1.0f);
                anim.setDuration(300);
                anim.setRepeatMode(Animation.REVERSE);
                anim.setRepeatCount(Animation.INFINITE);
                overlayAlarm.startAnimation(anim);
            }

            if (vibrator != null)
                vibrator.vibrate(new long[]{0, 500, 200, 500}, -1);
            SoundGenerator.playSound(miraGatesSettings.getSoundAlarm());
        });
    }

    private void stopAlarm() {
        if (overlayAlarm != null) {
            overlayAlarm.clearAnimation();
            overlayAlarm.setVisibility(View.GONE);
        }
        if (vibrator != null) vibrator.cancel();
    }

    // ==================== Batch Logging ====================

    private void logConsole(String message) {
        if (message == null || isDestroyed) return;

        synchronized (logBuffer) {
            logBuffer.append("[")
                    .append(timeFormat.format(new Date()))
                    .append("] ")
                    .append(message)
                    .append("\n");
        }

        if (logFlushScheduled.compareAndSet(false, true)) {
            mainHandler.postDelayed(this::flushLogBuffer, LOG_BATCH_INTERVAL_MS);
        }
        Log.d(TAG, message);
    }

    private void flushLogBuffer() {
        logFlushScheduled.set(false);
        if (isDestroyed || !isAdded() || tvDebugConsole == null) {
            synchronized (logBuffer) { logBuffer.setLength(0); }
            return;
        }
        String toAppend;
        synchronized (logBuffer) {
            toAppend = logBuffer.toString();
            logBuffer.setLength(0);
        }
        if (toAppend.isEmpty()) return;
        try {
            tvDebugConsole.append(toAppend);
            if (scrollDebugLog != null)
                scrollDebugLog.fullScroll(View.FOCUS_DOWN);
        } catch (Exception e) {
            Log.e(TAG, "flushLogBuffer error: " + e.getMessage());
        }
    }

    // ==================== Data Models ====================

    private static class GateMonitorItem {
        String gateId, gateName, location, status, lastEvent;
        String branchName;
        int passCount, denyCount;
        int branchId;
    }

    private static class BranchInfo {
        int branchId;
        String branchName;
    }

    // ==================== Adapter ====================

    private class GateMonitorAdapter extends RecyclerView.Adapter<GateMonitorAdapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_gate_monitor_card, parent, false);
            return new VH(view);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            if (position < 0 || position >= monitorList.size()) return;
            GateMonitorItem item = monitorList.get(position);

            if (h.tvGateName != null) {
                h.tvGateName.setText(item.gateName);
                h.tvGateName.setTextColor(getTextColor());
            }
            if (h.tvBranchName != null) {
                if (currentBranchId == 0 && item.branchName != null
                        && !item.branchName.isEmpty()) {
                    h.tvBranchName.setText("🏢 " + item.branchName);
                    h.tvBranchName.setVisibility(View.VISIBLE);
                } else {
                    h.tvBranchName.setVisibility(View.GONE);
                }
            }
            if (h.tvGateLocation != null) {
                h.tvGateLocation.setText("📍 " + (item.location != null
                        && !item.location.isEmpty()
                        ? item.location
                        : getLocalizedText("غير محدد", "Not specified")));
            }
            if (h.tvPassCount != null)
                h.tvPassCount.setText(String.valueOf(item.passCount));
            if (h.tvDenyCount != null)
                h.tvDenyCount.setText(String.valueOf(item.denyCount));
            if (h.tvGateLastEvent != null) {
                h.tvGateLastEvent.setText(item.lastEvent != null
                        && !item.lastEvent.isEmpty()
                        ? item.lastEvent
                        : getLocalizedText("لا أحداث", "No events"));
                h.tvGateLastEvent.setTextColor(getSecondaryTextColor());
            }

            switch (item.status != null ? item.status.toLowerCase() : "offline") {
                case "alert":
                case "breach":
                    if (h.cardGate != null) {
                        h.cardGate.setCardBackgroundColor(Color.parseColor("#33EF4444"));
                        h.cardGate.setStrokeColor(getDangerColor());
                    }
                    if (h.tvGateStatus != null) {
                        h.tvGateStatus.setText(getLocalizedText("🚨 إنذار", "🚨 BREACH"));
                        h.tvGateStatus.setTextColor(getDangerColor());
                    }
                    if (h.vStatusDot != null) {
                        h.vStatusDot.setBackgroundResource(
                                R.drawable.circle_indicator_red);
                    }
                    if (h.btnToggleSingleGate != null) {
                        h.btnToggleSingleGate.setText(getLocalizedText(
                                "🔓 إعادة تعيين", "🔓 Reset"));
                        h.btnToggleSingleGate.setBackgroundColor(getDangerColor());
                    }
                    startPulseAnimation(h.itemView);
                    break;

                case "online":
                case "armed":
                case "active":
                    if (h.cardGate != null) {
                        h.cardGate.setCardBackgroundColor(getColorFromTheme(
                                com.google.android.material.R.attr.colorSurface,
                                Color.parseColor("#1E1E1E")));
                        h.cardGate.setStrokeColor(getSuccessColor());
                    }
                    if (h.tvGateStatus != null) {
                        h.tvGateStatus.setText(getLocalizedText(
                                "🟢 مسلحة", "🟢 ARMED"));
                        h.tvGateStatus.setTextColor(getSuccessColor());
                    }
                    if (h.vStatusDot != null) {
                        h.vStatusDot.setBackgroundResource(
                                R.drawable.circle_indicator_green);
                    }
                    if (h.btnToggleSingleGate != null) {
                        h.btnToggleSingleGate.setText(getLocalizedText(
                                "🔒 إغلاق", "🔒 Lock"));
                        h.btnToggleSingleGate.setBackgroundColor(getSecondaryTextColor());
                    }
                    h.itemView.clearAnimation();
                    break;

                default:
                    if (h.cardGate != null) {
                        h.cardGate.setCardBackgroundColor(getColorFromTheme(
                                com.google.android.material.R.attr.colorSurfaceVariant,
                                Color.parseColor("#2A2A2A")));
                        h.cardGate.setStrokeColor(getSecondaryTextColor());
                    }
                    if (h.tvGateStatus != null) {
                        h.tvGateStatus.setText(getLocalizedText(
                                "⚪ غير متصلة", "⚪ OFFLINE"));
                        h.tvGateStatus.setTextColor(getSecondaryTextColor());
                    }
                    if (h.vStatusDot != null) {
                        h.vStatusDot.setBackgroundResource(
                                R.drawable.circle_indicator_gray);
                    }
                    if (h.btnToggleSingleGate != null) {
                        h.btnToggleSingleGate.setText(getLocalizedText(
                                "🔄 إعادة اتصال", "🔄 Reconnect"));
                        h.btnToggleSingleGate.setBackgroundColor(getWarningColor());
                    }
                    h.itemView.clearAnimation();
                    break;
            }

            if (h.btnToggleSingleGate != null) {
                h.btnToggleSingleGate.setOnClickListener(v ->
                        toggleGateState(item.gateId, item.status));
            }
        }

        @Override
        public int getItemCount() {
            return monitorList.size();
        }

        class VH extends RecyclerView.ViewHolder {
            com.google.android.material.card.MaterialCardView cardGate;
            TextView tvGateName, tvGateStatus, tvGateLocation, tvPassCount,
                    tvDenyCount, tvGateLastEvent, tvBranchName;
            View vStatusDot;
            Button btnToggleSingleGate;

            VH(@NonNull View itemView) {
                super(itemView);
                cardGate = itemView.findViewById(R.id.cardGate);
                tvGateName = itemView.findViewById(R.id.tvGateName);
                tvGateStatus = itemView.findViewById(R.id.tvGateStatus);
                tvGateLocation = itemView.findViewById(R.id.tvGateLocation);
                tvPassCount = itemView.findViewById(R.id.tvPassCount);
                tvDenyCount = itemView.findViewById(R.id.tvDenyCount);
                tvGateLastEvent = itemView.findViewById(R.id.tvGateLastEvent);
                tvBranchName = itemView.findViewById(R.id.tvBranchName);
                vStatusDot = itemView.findViewById(R.id.vGateStatusDot);
                btnToggleSingleGate = itemView.findViewById(R.id.btnToggleSingleGate);
            }
        }
    }

    private void showToast(String message) {
        if (isAdded() && getContext() != null) {
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== Lifecycle ====================

    @Override
    public void onPause() {
        super.onPause();
        stopScanning();
    }

    @Override
public void onResume() {
    super.onResume();
    applyOperationMode(currentOperationMode);
    mainHandler.postDelayed(() -> {
        if (isDestroyed || !isAdded() || isLockdown || isCameraOpen) return;
        // ✅ فحص مزدوج قبل البدء
        if (isScanning) return;
        if (gateRfidBridge != null && gateRfidBridge.isScanning()) return;
        startScanning();
    }, 600);
}

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        isDestroyed = true;
        stopScanningSync();
        stopAlarm();
        stopContinuousScan();
        applyKeepScreenOn(false);

        // ⭐ إزالة Back press callback
        if (backPressedCallback != null) {
            backPressedCallback.setEnabled(false);
            backPressedCallback.remove();
            backPressedCallback = null;
            Log.d(TAG, "🗑️ Back press handler removed");
        }

        try {
            if (pollFuture != null) { pollFuture.cancel(true); pollFuture = null; }
            if (pingFuture != null) { pingFuture.cancel(true); pingFuture = null; }
            if (scheduler != null) { scheduler.shutdownNow(); scheduler = null; }
            if (gateExecutor != null) { gateExecutor.shutdownNow(); gateExecutor = null; }
        } catch (Exception e) {
            Log.e(TAG, "Cleanup error: " + e.getMessage(), e);
        }

        scanDebounceMap.clear();
        localAllowedEpcCache.clear();
        mainHandler.removeCallbacksAndMessages(null);
    }
}
    
