package com.example.uhf.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Context;


import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.animation.ObjectAnimator;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;


import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.adapter.ModernScannedItemsAdapter;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.dialog.inventory.InventoryDialogsManager;
import com.example.uhf.engine.MiraInventoryEngine;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.manager.inventory.InventoryCatalogManager;
import com.example.uhf.manager.inventory.InventoryFilterManager;
import com.example.uhf.manager.inventory.InventoryPersistenceManager;
import com.example.uhf.manager.inventory.InventoryScanController;
import com.example.uhf.manager.inventory.InventorySessionManager;
import com.example.uhf.MiraRfid.RFIDManager;
import com.example.uhf.model.InventoryItem;
import com.example.uhf.model.InventoryReport;
import com.example.uhf.model.ScannedItemDisplay;
import com.example.uhf.utils.InventoryAnimationHelper;
import com.example.uhf.utils.PrintUtils;
import com.example.uhf.utils.SoundManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MiraInventoryProFragment v4.0 — النسخة النهائية الكاملة
 *
 * ✅ إصلاحات v4.0:
 *  - setupBackPressHandler() + showExitWarning() مضافان
 *  - stopScanning() public
 *  - Fragment = UI فقط
 *  - ScanController = منطق المسح
 *  - Executors: ioExecutor + processingExecutor
 *  - AtomicBoolean + generation counter
 *  - UI Batching كل 250ms
 *  - CopyOnWriteArrayList
 */
public class MiraInventoryProFragment extends KeyDwonFragment {

    private static final String TAG = "MiraInventoryPro";

    // ==================== ثوابت ====================
    private static final long UI_UPDATE_THROTTLE_MS = 200;
    private static final int EXECUTOR_SHUTDOWN_TIMEOUT_MS = 1500;

    private static final int KEYCODE_RFID_TRIGGER_1 = 139;
    private static final int KEYCODE_RFID_TRIGGER_2 = 280;
    private static final int KEYCODE_RFID_TRIGGER_3 = 293;

    // ==================== حالة ====================
    private volatile String selectedCategoryFilter = null;
    private volatile boolean isFragmentDestroyed = false;
    private volatile boolean isArabic = true;
    private volatile int expectedCount = 0;
    private volatile int currentPowerDbm = 26;
    private volatile int lastScannedForAnimation = 0;

    private volatile boolean lastConnectionState = false;
    private final AtomicBoolean connectionStateKnown = new AtomicBoolean(false);

    // ==================== Views ====================
    private TextView tvProgressPercent, tvScannedCount, tvExpectedCount, tvInventoryStatus,
            tvElapsedTime, tvRssi, tvVariance, tvAccuracy, tvItemCount,
            tvTotalWeight, tvTotalValue;
            // ✅ Last Scan Card Views
    private TextView tvLastScanTitle, tvLastScanEpc, tvLastScanKarat,
        tvLastScanWeight, tvLastScanRssi, tvLastScanStatus;
    private View lastScanCard, flashOverlay, alertsRow;
    private TextView tvAlertsText;
    private MaterialButton btnOpenItemsList;
    private MaterialButton btnPowerControlCompact, btnArchiveReportsCompact;
    private ProgressBar progressScanned;
    private View statusDot, statusBar, tvEmptyState;
    private MaterialButtonToggleGroup segmentedModes;
    private MaterialButton btnModeFull, btnModeCategory, btnModeBlind,
            btnPowerControl, btnGenerateReport, btnArchiveReports,
            btnMainToggleScan, btnCommitSession;
    private EditText etSearchItems;
    private ImageView btnClearSearch, btnLiveFilter;
    private RecyclerView rvInventoryItems;
    private ModernScannedItemsAdapter modernAdapter;

    // ==================== بيانات ====================
    private final List<ScannedItemDisplay> scannedItemsDisplayList =
            new CopyOnWriteArrayList<>();
    private final Set<String> catalogEpcs =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    // ✅ UI Batching
    private final ConcurrentLinkedQueue<ScannedItemDisplay> pendingUiItems =
            new ConcurrentLinkedQueue<>();
    private final AtomicBoolean uiFlushScheduled = new AtomicBoolean(false);
    private final AtomicBoolean uiUpdateScheduled = new AtomicBoolean(false);

    // ==================== Executors ====================
    private ExecutorService ioExecutor;
    private ExecutorService processingExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    private Vibrator vibrator;

    // ==================== مكونات ====================
    private MiraInventoryEngine inventoryEngine;
    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private PermissionManager permissionManager;
    private SoundManager soundManager;
    private RFIDManager rfidManager;

    private InventoryAnimationHelper animationHelper;
    private InventorySessionManager sessionManager;
    private InventoryCatalogManager catalogManager;
    private InventoryFilterManager filterManager;
    private InventoryDialogsManager dialogsManager;
    private InventoryPersistenceManager persistenceManager;
    private InventoryScanController scanController;

    private OnBackPressedCallback backPressedCallback;

    // ==================== Runnables ====================
    private final Runnable uiUpdateRunnable = () -> {
        uiUpdateScheduled.set(false);
        updateUI();
        updateTotalWeightAndValue();
    };

    private final Runnable uiFlushRunnable = () -> {
        uiFlushScheduled.set(false);
        flushPendingUiItems();
    };

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (scanController != null && scanController.isScanning()
                    && !isFragmentDestroyed) {
                updateElapsedTime();
                mainHandler.postDelayed(this, 1000);
            }
        }
    };

    // ==================== Factory ====================
    public static MiraInventoryProFragment newInstance() {
        return new MiraInventoryProFragment();
    }

    public boolean isScanning() {
        return scanController != null && scanController.isScanning();
    }

    // ==================== Lifecycle ====================
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                              @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        try {
            return inflater.inflate(R.layout.fragment_mira_inventory_pro, container, false);
        } catch (Exception e) {
            Log.e(TAG, "Inflate error", e);
            TextView tv = new TextView(requireContext());
            tv.setText("✅ الجرد يعمل (Fallback)\n\n" + e.getMessage());
            tv.setGravity(android.view.Gravity.CENTER);
            return tv;
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        isFragmentDestroyed = false;

        initExecutors();
        initLocale(view);
        initSoundManager();
        initManagers();
        initViews(view);
        initBusinessManagers();
        initEngine();
        initRfidAndScanController();

        setupBackPressHandler();
        applyPermissions();
        applyLanguage();
    }

    private void initExecutors() {
        try {
            if (ioExecutor == null || ioExecutor.isShutdown()) {
                ioExecutor = new ThreadPoolExecutor(
                        1, 2, 30L, TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(128),
                        r -> {
                            Thread t = new Thread(r, "Mira-IO");
                            t.setPriority(Thread.NORM_PRIORITY - 1);
                            t.setDaemon(true);
                            return t;
                        },
                        new ThreadPoolExecutor.DiscardOldestPolicy());
            }
            if (processingExecutor == null || processingExecutor.isShutdown()) {
                processingExecutor = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "Mira-Processing");
                    t.setPriority(Thread.NORM_PRIORITY);
                    t.setDaemon(true);
                    return t;
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "initExecutors error", e);
        }
    }

    private void initLocale(View view) {
        try {
            isArabic = Locale.getDefault().getLanguage().equals("ar");
            view.setLayoutDirection(isArabic
                    ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        } catch (Exception ignored) {}
    }

    private void initSoundManager() {
        try { soundManager = new SoundManager(requireContext()); }
        catch (Exception e) { Log.e(TAG, "SoundManager error", e); }
    }

    private void initManagers() {
        try { animationHelper = new InventoryAnimationHelper(isArabic); }
        catch (Exception e) { Log.e(TAG, "animationHelper error", e); }
        try { sessionManager = new InventorySessionManager(requireContext(), isArabic); }
        catch (Exception e) { Log.e(TAG, "sessionManager error", e); }
        try { catalogManager = new InventoryCatalogManager(requireContext(), isArabic); }
        catch (Exception e) { Log.e(TAG, "catalogManager error", e); }
        try { filterManager = new InventoryFilterManager(isArabic); }
        catch (Exception e) { Log.e(TAG, "filterManager error", e); }
        try {
            persistenceManager = new InventoryPersistenceManager(
                    requireContext(), isArabic, ioExecutor);
        } catch (Exception e) { Log.e(TAG, "persistenceManager error", e); }
        try {
            dialogsManager = new InventoryDialogsManager(requireActivity(), isArabic);
            dialogsManager.setSoundManager(soundManager);
            dialogsManager.setCurrentPowerDbm(currentPowerDbm);
            dialogsManager.setCallback(createDialogsCallback());
        } catch (Exception e) { Log.e(TAG, "dialogsManager error", e); }
        try {
            scanController = new InventoryScanController(
                    requireContext(), isArabic, processingExecutor, ioExecutor);
            scanController.setSoundManager(soundManager);
            scanController.setCallback(createScanCallback());
        } catch (Exception e) { Log.e(TAG, "scanController error", e); }
    }

    private void initBusinessManagers() {
        try {
            apiClient = MiraApiClient.getInstance(requireContext());
            branchesManager = MiraBranchesManager.getInstance(requireContext());
            permissionManager = PermissionManager.getInstance(requireContext());
        } catch (Exception e) { Log.e(TAG, "business managers error", e); }
    }

    private void initEngine() {
        try {
            String sessionId = "SESS-" + UUID.randomUUID().toString()
                    .substring(0, 8).toUpperCase();
            inventoryEngine = new MiraInventoryEngine(requireContext(),
                    sessionId, getDeviceId());
            if (scanController != null) {
                scanController.setInventoryEngine(inventoryEngine);
            }
        } catch (Exception e) { Log.e(TAG, "engine error", e); }
    }

    private void initRfidAndScanController() {
        try {
            rfidManager = RFIDManager.getInstance(requireContext());
            if (scanController != null) {
                scanController.attachRfidManager(rfidManager);
            }
        } catch (Exception e) { Log.e(TAG, "rfid error", e); }
    }

    // ==================== Back Press Handler ====================
    private void setupBackPressHandler() {
        try {
            backPressedCallback = new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    if (isScanning()) {
                        showExitWarning(() -> {
                            stopScanning();
                            setEnabled(false);
                            requireActivity().getOnBackPressedDispatcher().onBackPressed();
                        });
                    } else {
                        setEnabled(false);
                        requireActivity().getOnBackPressedDispatcher().onBackPressed();
                    }
                }
            };

            requireActivity().getOnBackPressedDispatcher().addCallback(
                    getViewLifecycleOwner(), backPressedCallback);
        } catch (Exception e) {
            Log.e(TAG, "setupBackPressHandler error: " + e.getMessage());
        }
    }

    private void showExitWarning(Runnable onConfirm) {
        if (!isAdded() || isFragmentDestroyed) return;

        String title = isArabic ? "⚠️ تحذير مهم" : "⚠️ Important Warning";
        String message = isArabic ?
                "الجرد ما زال قيد التشغيل!\n\nإذا قمت بتغيير الواجهة، سيتوقف الجرد.\n\nهل تريد المتابعة؟" :
                "Inventory is still running!\n\nIf you change the screen, the inventory will stop.\n\nContinue?";

        try {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(isArabic ? "إيقاف والمتابعة" : "Stop and Continue",
                            (dialog, which) -> {
                                if (onConfirm != null) onConfirm.run();
                            })
                    .setNegativeButton(isArabic ? "البقاء" : "Stay",
                            (dialog, which) -> dialog.dismiss())
                    .setCancelable(false)
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "showExitWarning error: " + e.getMessage());
        }
    }

    // ==================== Callbacks ====================
    private InventoryDialogsManager.DialogsCallback createDialogsCallback() {
        return new InventoryDialogsManager.DialogsCallback() {
            @Override public void onPowerChanged(int p) {
                currentPowerDbm = p;
                applyAntennaPower(p);
                if (btnPowerControl != null)
                    btnPowerControl.setText("⚡ " + p + " dBm");
            }
            @Override public void onSessionReset() { confirmResetSession(); }
            @Override public void onBuzzerTest() {}
            @Override public void onCommitConfirmed() { commitInventoryToServer(); }
            @Override public void onPrintCurrentReport() { printCurrentReport(); }
            @Override public void onPrintSingleItem(ScannedItemDisplay item) {
                printSingleItemLabel(item);
            }
            @Override public void onItemStatusChange(ScannedItemDisplay item, String s) {
                updateItemStatus(item.epc, s);
            }
            @Override public void onItemDamageRegister(ScannedItemDisplay item, String d) {
                registerItemDamage(item.epc, d);
            }
            @Override public void onShowArchive() { showArchiveReports(); }
            @Override public void onShowSnapshots() { showSnapshotsList(); }
        };
    }

    private InventoryScanController.ScanCallback createScanCallback() {
        return new InventoryScanController.ScanCallback() {
            @Override public void onScanStarted() {
                updateMainToggleButton(true);
                if (btnGenerateReport != null) btnGenerateReport.setEnabled(false);
                if (btnArchiveReports != null) btnArchiveReports.setEnabled(false);
                mainHandler.post(timerRunnable);
            }
            @Override public void onScanStopped() {
                mainHandler.removeCallbacks(timerRunnable);
                updateMainToggleButton(false);
                if (btnGenerateReport != null) btnGenerateReport.setEnabled(true);
                if (btnArchiveReports != null) btnArchiveReports.setEnabled(true);
                if (tvInventoryStatus != null) {
                    tvInventoryStatus.setText(isArabic ?
                            "📡 تم إيقاف الجرد" : "📡 Scan stopped");
                }
                scheduleUiUpdate();
                syncOfflineTransactions();
            }
            @Override public void onTagRead(String normalizedEpc, int rssi) {
                addScannedItemToDisplay(normalizedEpc, rssi);
                updateLastScan(normalizedEpc, rssi);
            }
            @Override public void onDuplicateTag(String epc) {}
            @Override
public void onError(String error) {
    mainHandler.post(() -> {
        if (isFragmentDestroyed || !isAdded()) return;
        
        // ✅ رسالة خطأ واضحة مع خيارات
        String title = isArabic ? "⚠️ فشل تحميل الكاتولوج" 
                                : "⚠️ Catalog Load Failed";
        String message = (error != null && !error.isEmpty()) 
                ? error 
                : (isArabic ? "فشل غير معروف" : "Unknown error");
        
        if (tvInventoryStatus != null) {
            tvInventoryStatus.setText(
                    isArabic ? "⚠️ فشل التحميل" : "⚠️ Load failed");
        }
        
        // ✅ Dialog مع 3 خيارات
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setMessage(message + "\n\n" + 
                        (isArabic ? "هل تريد إعادة المحاولة؟" 
                                  : "Retry?"))
                .setPositiveButton(isArabic ? "🔄 إعادة المحاولة" : "🔄 Retry", 
                        (d, w) -> {
                            d.dismiss();
                            mainHandler.postDelayed(() -> startScanning(), 500);
                        })
                .setNeutralButton(isArabic ? "⚡ جرد أعمى" : "⚡ Blind Mode",
                        (d, w) -> {
                            d.dismiss();
                            startBlindInventory();
                        })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", 
                        (d, w) -> d.dismiss())
                .setCancelable(false)
                .show();
    });
}

            @Override public void onConnectionChanged(boolean isConnected) {
                handleConnectionChanged(isConnected);
            }
            @Override public void onTagDebounced(String epc) {}
            @Override public void onExpectedCountChanged(int count) {
                expectedCount = count;
                if (tvExpectedCount != null) {
                    tvExpectedCount.setText(count > 0 ? String.valueOf(count) : "—");
                }
            }
        };
    }

    // ==================== Views ====================
    private void initViews(View view) {
    // ✅ Views القديمة (للتوافق)
    tvProgressPercent = view.findViewById(R.id.tvProgressPercent);
    progressScanned = view.findViewById(R.id.progressScanned);
    tvScannedCount = view.findViewById(R.id.tvScannedCount);
    tvExpectedCount = view.findViewById(R.id.tvExpectedCount);
    statusDot = view.findViewById(R.id.statusDot);
    statusBar = view.findViewById(R.id.statusBar);
    tvInventoryStatus = view.findViewById(R.id.tvInventoryStatus);
    tvElapsedTime = view.findViewById(R.id.tvElapsedTime);
    tvRssi = view.findViewById(R.id.tvRssi);
    segmentedModes = view.findViewById(R.id.segmentedModes);
    btnModeFull = view.findViewById(R.id.btnModeFull);
    btnModeCategory = view.findViewById(R.id.btnModeCategory);
    btnModeBlind = view.findViewById(R.id.btnModeBlind);
    tvVariance = view.findViewById(R.id.tvVariance);
    tvAccuracy = view.findViewById(R.id.tvAccuracy);
    tvItemCount = view.findViewById(R.id.tvItemCount);
    tvTotalWeight = view.findViewById(R.id.tvTotalWeight);
    tvTotalValue = view.findViewById(R.id.tvTotalValue);
    btnGenerateReport = view.findViewById(R.id.btnGenerateReport);
    btnArchiveReports = view.findViewById(R.id.btnArchiveReports);
    btnPowerControl = view.findViewById(R.id.btnPowerControl);
    etSearchItems = view.findViewById(R.id.etSearchItems);
    btnClearSearch = view.findViewById(R.id.btnClearSearch);
    btnLiveFilter = view.findViewById(R.id.btnLiveFilter);
    rvInventoryItems = view.findViewById(R.id.rvInventoryItems);
    tvEmptyState = view.findViewById(R.id.tvEmptyState);
    btnMainToggleScan = view.findViewById(R.id.btnMainToggleScan);
    btnCommitSession = view.findViewById(R.id.btnCommitSession);

    // ✅ Last Scan Card
    lastScanCard = view.findViewById(R.id.lastScanCard);
    tvLastScanTitle = view.findViewById(R.id.tvLastScanTitle);
    tvLastScanEpc = view.findViewById(R.id.tvLastScanEpc);
    tvLastScanKarat = view.findViewById(R.id.tvLastScanKarat);
    tvLastScanWeight = view.findViewById(R.id.tvLastScanWeight);
    tvLastScanRssi = view.findViewById(R.id.tvLastScanRssi);
    tvLastScanStatus = view.findViewById(R.id.tvLastScanStatus);
    flashOverlay = view.findViewById(R.id.flashOverlay);
    alertsRow = view.findViewById(R.id.alertsRow);
    tvAlertsText = view.findViewById(R.id.tvAlertsText);
    btnOpenItemsList = view.findViewById(R.id.btnOpenItemsList);
    btnPowerControlCompact = view.findViewById(R.id.btnPowerControlCompact);
    btnArchiveReportsCompact = view.findViewById(R.id.btnArchiveReportsCompact);

    // ✅ تهيئة Vibrator
    initVibrator();

    // ✅ RecyclerView (يُخزّن مرجعاً، لكنه مخفي — يعمل في BottomSheet)
    if (rvInventoryItems != null) {
        rvInventoryItems.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvInventoryItems.setNestedScrollingEnabled(false);
        modernAdapter = new ModernScannedItemsAdapter(
                isArabic, this::showItemOptionsDialog);
        rvInventoryItems.setAdapter(modernAdapter);
        rvInventoryItems.setHasFixedSize(false);
        rvInventoryItems.setItemAnimator(null);
    }

    if (btnLiveFilter != null) {
        btnLiveFilter.setOnClickListener(v -> showLiveFilterChips());
    }

    setupButtons();
    setupSegmentedControl();
    setupSearchBar();
}

/**
 * ✅ تهيئة Vibrator (متوافق مع API 31+)
 */
private void initVibrator() {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) requireContext()
                    .getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = vm != null ? vm.getDefaultVibrator() : null;
        } else {
            vibrator = (Vibrator) requireContext()
                    .getSystemService(Context.VIBRATOR_SERVICE);
        }
    } catch (Exception e) {
        Log.e(TAG, "initVibrator error", e);
        vibrator = null;
    }
}

/**
 * ✅ اهتزاز حسب نوع المسح
 */
private void hapticFeedback(SoundManager.FeedbackType type) {
    if (vibrator == null || !vibrator.hasVibrator()) return;
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            switch (type) {
                case KNOWN_ITEM:
                    vibrator.vibrate(VibrationEffect.createOneShot(40,
                            VibrationEffect.DEFAULT_AMPLITUDE));
                    break;
                case DUPLICATE_ITEM:
                    vibrator.vibrate(VibrationEffect.createWaveform(
                            new long[]{0, 25, 40, 25}, -1));
                    break;
                case UNKNOWN_ITEM:
                    vibrator.vibrate(VibrationEffect.createOneShot(150, 255));
                    break;
                case COMPLETED:
                    vibrator.vibrate(VibrationEffect.createWaveform(
                            new long[]{0, 50, 40, 50, 40, 100}, -1));
                    break;
                case ERROR:
                    vibrator.vibrate(VibrationEffect.createWaveform(
                            new long[]{0, 200, 80, 200}, -1));
                    break;
            }
        } else {
            vibrator.vibrate(40);
        }
    } catch (Exception e) {
        Log.e(TAG, "hapticFeedback error", e);
    }
}

/**
 * ✅ وميض ملون عند المسح
 */
private void flashOverlay(int colorHex) {
    if (flashOverlay == null) return;
    try {
        flashOverlay.setBackgroundColor(colorHex);
        flashOverlay.setAlpha(0.6f);

        ObjectAnimator anim = ObjectAnimator.ofFloat(flashOverlay, "alpha", 0.6f, 0f);
        anim.setDuration(280);
        anim.start();
    } catch (Exception e) {
        Log.e(TAG, "flashOverlay error", e);
    }
}

    private void setupButtons() {
        if (btnMainToggleScan != null) {
            btnMainToggleScan.setOnClickListener(v -> {
                if (isScanning()) stopScanning();
                else if (canEdit()) startScanning();
                else showAccessDenied();
            });
        }
        if (btnCommitSession != null) {
            btnCommitSession.setOnClickListener(v -> showCommitConfirmationDialog());
        }
        if (btnPowerControl != null) {
            btnPowerControl.setOnClickListener(v -> showPowerControlSheet());
        }
        if (btnGenerateReport != null) {
            btnGenerateReport.setOnClickListener(v -> showCommitConfirmationDialog());
            btnGenerateReport.setEnabled(false);
        }
        if (btnArchiveReports != null) {
            btnArchiveReports.setOnClickListener(v -> showArchiveReports());
        }
        
        if (btnOpenItemsList != null) {
    btnOpenItemsList.setOnClickListener(v -> showItemsBottomSheet());
}
if (btnPowerControlCompact != null) {
    btnPowerControlCompact.setOnClickListener(v -> showPowerControlSheet());
}
if (btnArchiveReportsCompact != null) {
    btnArchiveReportsCompact.setOnClickListener(v -> showArchiveReports());
}
    }
    
    /**
 * ✅ عرض قائمة الممسوحات في Bottom Sheet
 */
private void showItemsBottomSheet() {
    if (!isAdded() || isFragmentDestroyed) return;

    com.google.android.material.bottomsheet.BottomSheetDialog sheet =
            new com.google.android.material.bottomsheet.BottomSheetDialog(
                    requireContext(), R.style.TransparentBottomSheetDialog);

    View sheetView = getLayoutInflater().inflate(
            R.layout.bottom_sheet_inventory_items, null);

    // ✅ إعادة ربط RecyclerView في الـ Sheet
    RecyclerView sheetRecycler = sheetView.findViewById(R.id.rvSheetItems);
    if (sheetRecycler != null && modernAdapter != null) {
        sheetRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        sheetRecycler.setAdapter(modernAdapter);
    }

    // ✅ العدّاد
    TextView tvCount = sheetView.findViewById(R.id.tvSheetItemsCount);
    if (tvCount != null) {
        tvCount.setText(String.format(Locale.US, "%d %s",
                scannedItemsDisplayList.size(),
                isArabic ? "قطعة" : "items"));
    }

    sheet.setContentView(sheetView);
    sheet.show();
}

    private void setupSearchBar() {
        if (etSearchItems == null) return;
        etSearchItems.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                filterItems(s.toString());
                if (btnClearSearch != null)
                    btnClearSearch.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }
        });
        if (btnClearSearch != null) {
            btnClearSearch.setOnClickListener(v -> {
                if (etSearchItems != null) etSearchItems.setText("");
            });
        }
    }

    private boolean canEdit() {
        return permissionManager != null
                && (permissionManager.canEdit("inventory") || permissionManager.isManager());
    }

    private void showAccessDenied() {
        if (!isAdded() || isFragmentDestroyed) return;
        Toast.makeText(requireContext(),
                isArabic ? "⛔ غير مصرح" : "⛔ Access denied", Toast.LENGTH_SHORT).show();
        if (soundManager != null) soundManager.playBeep(SoundManager.FeedbackType.ERROR);
    }

    // ==================== Key Events ====================
    @Override
    public boolean myOnKeyDwon(int keyCode, KeyEvent event) {
        if ((keyCode == KEYCODE_RFID_TRIGGER_1
                || keyCode == KEYCODE_RFID_TRIGGER_2
                || keyCode == KEYCODE_RFID_TRIGGER_3)
                && event.getRepeatCount() == 0) {
            if (!canEdit()) { showAccessDenied(); return true; }
            if (!isScanning()) startScanning(); else stopScanning();
            return true;
        }
        return false;
    }

    // ==================== Start/Stop ====================
    private void startScanning() {
        if (isFragmentDestroyed || isScanning()) return;
        if (!canEdit()) { showAccessDenied(); return; }

        resetUiForNewScan();
        scanController.setBarcodeToEpcMap(catalogManager.getBarcodeToEpcMap());

        if (tvInventoryStatus != null) {
            tvInventoryStatus.setText(isArabic ?
                    "🔄 جاري تحميل الكتالوج..." : "🔄 Loading catalog...");
        }
        Toast.makeText(requireContext(),
                isArabic ? "🔄 جاري التحميل..." : "🔄 Loading...",
                Toast.LENGTH_SHORT).show();

        catalogManager.fetchCatalog(apiClient, branchesManager,
                new InventoryCatalogManager.CatalogCallback() {
            @Override
            public void onCatalogLoaded(Map<String, InventoryItem> catalog) {
                mainHandler.post(() -> {
                    if (isFragmentDestroyed || !isAdded()) return;

                    catalogEpcs.clear();
                    if (catalog != null) catalogEpcs.addAll(catalog.keySet());
                    expectedCount = catalogEpcs.size();

                    if (scanController != null) {
                        scanController.startScanning(catalog);
                    }
                });
            }
            @Override
            public void onError(String error) {
                mainHandler.post(() -> {
                    if (isFragmentDestroyed || !isAdded()) return;
                    Toast.makeText(requireContext(),
                            (isArabic ? "⚠️ " : "⚠️ ") + error,
                            Toast.LENGTH_LONG).show();
                    if (tvInventoryStatus != null) {
                        tvInventoryStatus.setText(isArabic ?
                                "⚠️ فشل تحميل الكتالوج" : "⚠️ Catalog load failed");
                    }
                });
            }
        });
    }

    private void resetUiForNewScan() {
        scannedItemsDisplayList.clear();
        pendingUiItems.clear();
        if (modernAdapter != null) {
            modernAdapter.submitList(new ArrayList<>());
            modernAdapter.resetAnimationTracker();
        }
        if (rvInventoryItems != null) rvInventoryItems.scrollToPosition(0);
        if (tvScannedCount != null) tvScannedCount.setText("0");
        if (tvItemCount != null) tvItemCount.setText("0");
        if (progressScanned != null) progressScanned.setProgress(0);
        if (tvProgressPercent != null) tvProgressPercent.setText("0%");
        if (tvEmptyState != null) tvEmptyState.setVisibility(View.VISIBLE);
        lastScannedForAnimation = 0;
    }

    public void stopScanning() {
        if (scanController != null) scanController.stopScanning();
    }

    // ==================== Connection Handling ====================
    private void handleConnectionChanged(boolean isConnected) {
    if (connectionStateKnown.get() && lastConnectionState == isConnected) return;
    lastConnectionState = isConnected;
    connectionStateKnown.set(true);

    if (!isAdded() || isFragmentDestroyed) return;

    mainHandler.post(() -> {
        if (tvInventoryStatus != null) {
            tvInventoryStatus.setText(isConnected
                    ? (isArabic ? "🟢 متصل" : "🟢 Connected")
                    : (isArabic ? "🔴 غير متصل" : "🔴 Disconnected"));
        }
        if (statusDot != null) {
            statusDot.setBackgroundResource(isConnected
                    ? R.drawable.bg_status_dot_green
                    : R.drawable.bg_status_dot_red);
        }
        if (soundManager != null) {
            soundManager.playBeep(isConnected
                    ? SoundManager.FeedbackType.COMPLETED
                    : SoundManager.FeedbackType.ERROR);
        }
        hapticFeedback(isConnected
                ? SoundManager.FeedbackType.COMPLETED
                : SoundManager.FeedbackType.ERROR);
    });
}

    // ==================== Add Scanned Item ====================
    private void addScannedItemToDisplay(String epc, int rssi) {
    if (!isAdded() || isFragmentDestroyed || epc == null) return;
    if (Looper.myLooper() != Looper.getMainLooper()) {
        mainHandler.post(() -> addScannedItemToDisplay(epc, rssi));
        return;
    }

    try {
        ScannedItemDisplay existing = null;
        for (ScannedItemDisplay d : scannedItemsDisplayList) {
            if (d != null && epc.equals(d.epc)) { existing = d; break; }
        }

        SoundManager.FeedbackType feedback;

        if (existing != null) {
            existing.count++;
            existing.rssi = rssi;
            existing.time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(new Date());
            feedback = SoundManager.FeedbackType.DUPLICATE_ITEM;

            // ✅ وميض أزرق
            flashOverlay(0x883B82F6);
            // ✅ تحديث "آخر مسح" بالقطعة المكررة
            updateLastScanCard(existing, true);
        } else {
            boolean isKnown = catalogEpcs.contains(epc);
            feedback = isKnown
                    ? SoundManager.FeedbackType.KNOWN_ITEM
                    : SoundManager.FeedbackType.UNKNOWN_ITEM;

            InventoryItem catalogItem = catalogManager != null
                    ? catalogManager.getItemByEpc(epc) : null;

            String title = catalogItem != null && catalogItem.getTitle() != null
                    ? catalogItem.getTitle()
                    : (isKnown ? (isArabic ? "قطعة مسجلة" : "Catalog item")
                               : (isArabic ? "قطعة غير معروفة" : "Unknown item"));
            String karat = catalogItem != null && catalogItem.getKarat() != null
                    ? catalogItem.getKarat() : "—";
            double weight = catalogItem != null ? catalogItem.getWeight() : 0;
            double price = catalogItem != null ? catalogItem.getPrice() : 0;

            ScannedItemDisplay display = new ScannedItemDisplay(
                    epc, title, karat, 1, rssi,
                    new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                            .format(new Date()));
            display.weight = weight;
            display.price = price;

            scannedItemsDisplayList.add(0, display);
            pendingUiItems.offer(display);

            // ✅ وميض حسب النوع
            if (isKnown) {
                flashOverlay(0x8816A34A); // أخضر
            } else {
                flashOverlay(0x88DC2626); // أحمر
            }

            // ✅ تحديث "آخر مسح"
            updateLastScanCard(display, false);
        }

        // ✅ اهتزاز + صوت
        hapticFeedback(feedback);
        if (soundManager != null) soundManager.playBeep(feedback);

        if (tvEmptyState != null && !scannedItemsDisplayList.isEmpty()) {
            tvEmptyState.setVisibility(View.GONE);
        }

        scheduleUiFlush();
        scheduleUiUpdate();

    } catch (Exception e) {
        Log.e(TAG, "addScannedItemToDisplay error", e);
    }
}

/**
 * ✅ تحديث بطاقة "آخر مسح"
 */
private void updateLastScanCard(ScannedItemDisplay item, boolean isDuplicate) {
    if (item == null) return;
    if (tvLastScanTitle == null) return;

    try {
        tvLastScanTitle.setText(item.title != null ? item.title : "—");
        tvLastScanEpc.setText(item.epc != null ? item.epc : "—");
        tvLastScanKarat.setText(item.karat != null ? item.karat : "—");
        tvLastScanWeight.setText(String.format(Locale.US, "%.2fg", item.weight));
        tvLastScanRssi.setText(String.format(Locale.US, "📶 %d", item.rssi));

        boolean isKnown = catalogEpcs.contains(item.epc);
        if (isKnown) {
            tvLastScanStatus.setText(isArabic ? "✅ مطابق للمخزون" : "✅ In Catalog");
            tvLastScanStatus.setTextColor(0xFF16A34A);
        } else {
            tvLastScanStatus.setText(isArabic ? "⚠️ غير معروف" : "⚠️ Unknown");
            tvLastScanStatus.setTextColor(0xFFDC2626);
        }

        if (isDuplicate) {
            tvLastScanStatus.setText(isArabic ? "🔁 مكرر" : "🔁 Duplicate");
            tvLastScanStatus.setTextColor(0xFF3B82F6);
        }

        // ✅ Animation نبض على البطاقة
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(lastScanCard, "scaleX", 0.98f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(lastScanCard, "scaleY", 0.98f, 1f);
        scaleX.setDuration(200);
        scaleY.setDuration(200);
        scaleX.start();
        scaleY.start();

    } catch (Exception e) {
        Log.e(TAG, "updateLastScanCard error", e);
    }
}

    private void scheduleUiFlush() {
        if (uiFlushScheduled.compareAndSet(false, true)) {
            mainHandler.postDelayed(uiFlushRunnable, 250);
        }
    }

    private void flushPendingUiItems() {
        if (isFragmentDestroyed || modernAdapter == null) return;

        List<ScannedItemDisplay> batch = new ArrayList<>();
        ScannedItemDisplay item;
        while ((item = pendingUiItems.poll()) != null) batch.add(item);
        if (batch.isEmpty()) return;

        List<ScannedItemDisplay> current = new ArrayList<>(scannedItemsDisplayList);
        modernAdapter.submitList(current);
    }

    private void scheduleUiUpdate() {
        if (uiUpdateScheduled.compareAndSet(false, true)) {
            mainHandler.postDelayed(uiUpdateRunnable, UI_UPDATE_THROTTLE_MS);
        }
    }

    // ==================== Update UI ====================
    private void updateUI() {
        if (!isAdded() || isFragmentDestroyed || inventoryEngine == null) return;

        try {
            int scanned = inventoryEngine.getUniqueCount();

            if (tvScannedCount != null) {
                if (animationHelper != null) {
                    animationHelper.animateNumberChange(
                            tvScannedCount, lastScannedForAnimation, scanned);
                } else tvScannedCount.setText(String.valueOf(scanned));
            }
            if (tvItemCount != null) tvItemCount.setText(String.valueOf(scanned));

            if (expectedCount > 0) {
                int progress = Math.min((int) (((float) scanned / expectedCount) * 100), 100);

                if (progressScanned != null) {
                    if (animationHelper != null) {
                        animationHelper.animateProgressBar(
                                progressScanned, progressScanned.getProgress(), progress);
                    } else progressScanned.setProgress(progress);
                }
                if (tvProgressPercent != null) {
                    if (animationHelper != null) {
                        animationHelper.animateProgressText(tvProgressPercent, progress);
                    } else tvProgressPercent.setText(progress + "%");
                }
                if (tvVariance != null)
                    tvVariance.setText(String.valueOf(Math.max(0, expectedCount - scanned)));
                if (tvAccuracy != null) tvAccuracy.setText(progress + "%");

                if (scanned >= expectedCount && isScanning()) {
                    stopScanning();
                    if (soundManager != null)
                        soundManager.playBeep(SoundManager.FeedbackType.COMPLETED);
                    if (animationHelper != null) {
                        animationHelper.celebrateCompletion(
                                tvProgressPercent, progressScanned, tvScannedCount);
                    }
                    if (isAdded() && !isFragmentDestroyed) {
                        Toast.makeText(requireContext(),
                                isArabic ? "🎉 اكتمل جرد جميع المواد!"
                                        : "🎉 All items scanned!",
                                Toast.LENGTH_LONG).show();
                    }
                }
            } else if (tvProgressPercent != null) {
                tvProgressPercent.setText("—");
            }

            if (tvEmptyState != null) {
                tvEmptyState.setVisibility(scanned > 0 ? View.GONE : View.VISIBLE);
            }
            if (tvInventoryStatus != null && isScanning()) {
                tvInventoryStatus.setText((isArabic ? "📡 جاري الجرد... (" :
                        "📡 Scanning... (") + scanned + ")");
            }
            lastScannedForAnimation = scanned;
        } catch (Exception e) {
            Log.e(TAG, "updateUI error", e);
        }
    }

    private void updateElapsedTime() {
        if (tvElapsedTime != null && scanController != null) {
            try {
                long start = scanController.getScanStartTime();
                if (start <= 0) return;
                long ms = SystemClock.elapsedRealtime() - start;
                long s = ms / 1000;
                tvElapsedTime.setText(String.format(Locale.US, "%02d:%02d:%02d",
                        s / 3600, (s % 3600) / 60, s % 60));
            } catch (Exception ignored) {}
        }
    }

    private void updateTotalWeightAndValue() {
        double w = 0, v = 0;
        for (ScannedItemDisplay item : scannedItemsDisplayList) {
            w += item.weight * item.count;
            v += item.price * item.count;
        }
        final double fw = w, fv = v;
        mainHandler.post(() -> {
            if (tvTotalWeight != null)
                tvTotalWeight.setText(String.format(Locale.US, "%.2f g", fw));
            if (tvTotalValue != null)
                tvTotalValue.setText(String.format(Locale.US, "$%,.2f", fv));
        });
    }

    private void updateLastScan(String epc, int rssi) {
        if (tvRssi != null) tvRssi.setText(String.format(Locale.US, "📶 %d", rssi));
    }

    // ==================== Filtering ====================
    private void filterItems(String query) {
        if (filterManager == null) return;
        filterManager.setSearchQuery(query);
        List<ScannedItemDisplay> filtered = filterManager.applyFilter(
                scannedItemsDisplayList, catalogEpcs);
        if (modernAdapter != null) modernAdapter.submitList(filtered);
    }

    private void showLiveFilterChips() {
        if (!isAdded() || isFragmentDestroyed || filterManager == null) return;
        String[] filters = filterManager.getFilterOptions();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(), R.layout.item_dialog_list,
                android.R.id.text1, filters);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(filterManager.buildFilterDialogTitle())
                .setAdapter(adapter, (d, which) -> { applyLiveFilter(which); d.dismiss(); })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
    }

    private void applyLiveFilter(int filterType) {
        if (filterManager == null || !isAdded() || isFragmentDestroyed) return;
        filterManager.setCurrentFilter(filterType);
        if (filterType == InventoryFilterManager.FILTER_MISSING) {
            showMissingItemsDialog(); return;
        }
        List<ScannedItemDisplay> filtered = filterManager.applyFilter(
                scannedItemsDisplayList, catalogEpcs);
        if (modernAdapter != null) modernAdapter.submitList(filtered);
        Toast.makeText(requireContext(),
                (isArabic ? "🎯 الفلتر: " : "🎯 Filter: ")
                        + filterManager.getCurrentFilterName(),
                Toast.LENGTH_SHORT).show();
    }

    private void showMissingItemsDialog() {
        if (filterManager == null || catalogManager == null
                || !isAdded() || isFragmentDestroyed) return;
        if (catalogManager.isEmpty()) {
            Toast.makeText(requireContext(),
                    filterManager.buildNoCatalogMessage(), Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> missing = filterManager.getMissingItems(
                catalogEpcs, scannedItemsDisplayList);
        if (missing.isEmpty()) {
            Toast.makeText(requireContext(),
                    filterManager.buildNoMissingMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        String[] titles = filterManager.buildMissingItemsList(
                missing, catalogManager.getCatalogItemsMap());
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(), R.layout.item_dialog_list, android.R.id.text1, titles);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(filterManager.buildMissingItemsTitle(missing.size()))
                .setAdapter(adapter, null)
                .setPositiveButton(isArabic ? "موافق" : "OK", null)
                .show();
    }

    // ==================== Item Options ====================
    private void showItemOptionsDialog(ScannedItemDisplay item) {
        if (dialogsManager != null) dialogsManager.showItemOptionsDialog(item);
    }
    private void printSingleItemLabel(ScannedItemDisplay item) {
        try {
            InventoryReport r = new InventoryReport(0,
                    inventoryEngine != null ? inventoryEngine.getClientSessionId() : "SINGLE",
                    new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                            .format(new Date()),
                    1, 1, 0, isArabic ? "ملصق فردي" : "Single Label");
            List<ScannedItemDisplay> single = new ArrayList<>();
            single.add(item);
            PrintUtils.printReportWithQRCode(requireContext(), r, single, isArabic, null);
        } catch (Exception e) { Log.e(TAG, "printSingleItemLabel error", e); }
    }
    private void printCurrentReport() {
        try {
            if (inventoryEngine == null) return;
            int scanned = inventoryEngine.getUniqueCount();
            InventoryReport r = new InventoryReport(0,
                    inventoryEngine.getClientSessionId(),
                    new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                            .format(new Date()),
                    expectedCount, scanned, Math.max(0, expectedCount - scanned),
                    isArabic ? "جارية" : "In progress");
            List<ScannedItemDisplay> snapshot =
                    new ArrayList<>(scannedItemsDisplayList);
            PrintUtils.printReportWithQRCode(requireContext(), r, snapshot, isArabic, null);
        } catch (Exception e) { Log.e(TAG, "printCurrentReport error", e); }
    }
    private void updateItemStatus(String epc, String status) {
        Log.d(TAG, "Status " + epc + " → " + status);
    }
    private void registerItemDamage(String epc, String damage) {
        Log.d(TAG, "Damage " + epc + ": " + damage);
    }

    // ==================== Commit ====================
    private void showCommitConfirmationDialog() {
        if (dialogsManager == null || inventoryEngine == null) return;
        if (!canEdit()) { showAccessDenied(); return; }
        dialogsManager.setExpectedCount(expectedCount);
        dialogsManager.showCommitConfirmationDialog(inventoryEngine.getUniqueCount());
    }

    private void commitInventoryToServer() {
        if (!isAdded() || isFragmentDestroyed) return;
        if (persistenceManager == null || inventoryEngine == null || apiClient == null) {
            Toast.makeText(requireContext(), "⚠️ غير جاهز", Toast.LENGTH_SHORT).show();
            return;
        }
        if (persistenceManager.isCommittingNow()) {
            Toast.makeText(requireContext(),
                    isArabic ? "⏳ جاري الإرسال..." : "⏳ Sending...",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(requireContext(),
                isArabic ? "⏳ جاري الإرسال..." : "⏳ Sending...",
                Toast.LENGTH_SHORT).show();
        int scanned = inventoryEngine.getUniqueCount();
        int totalReads = inventoryEngine.getTotalReads();
        int branchId = branchesManager != null
                ? branchesManager.getActiveBranchId() : 0;

        persistenceManager.commitInventory(
                inventoryEngine.getClientSessionId(), branchId,
                expectedCount, totalReads, scanned,
                new ArrayList<>(scannedItemsDisplayList), apiClient,
                new InventoryPersistenceManager.CommitCallback() {
                    @Override public void onCommitSuccess(JSONObject report) {
                        mainHandler.post(() -> {
                            if (!isAdded() || isFragmentDestroyed) return;
                            Toast.makeText(requireContext(),
                                    isArabic ? "✅ تم الاعتماد" : "✅ Committed",
                                    Toast.LENGTH_LONG).show();
                            if (btnGenerateReport != null)
                                btnGenerateReport.setEnabled(false);
                            if (btnCommitSession != null)
                                btnCommitSession.setEnabled(false);
                            showCommitSuccessDialog(report);
                        });
                    }
                    @Override public void onCommitError(String error) {
                        mainHandler.post(() -> {
                            if (!isAdded() || isFragmentDestroyed) return;
                            Toast.makeText(requireContext(),
                                    (isArabic ? "❌ " : "❌ ") + error,
                                    Toast.LENGTH_LONG).show();
                        });
                    }
                });
    }

    private void showCommitSuccessDialog(JSONObject report) {
        if (!isAdded() || isFragmentDestroyed || report == null) return;
        try {
            String accuracy = report.optString("accuracy", "0");
            String risk = report.optString("risk_level", "unknown");
            String rec = report.optString("recommendation", "");
            String msg = isArabic
                    ? String.format("✅ تم الاعتماد:\n\n• الدقة: %s%%\n• الخطورة: %s\n• التوصية: %s",
                            accuracy, risk, rec)
                    : String.format("✅ Committed:\n\n• Accuracy: %s%%\n• Risk: %s\n• Recommendation: %s",
                            accuracy, risk, rec);
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(isArabic ? "🎉 نجاح" : "🎉 Success")
                    .setMessage(msg)
                    .setPositiveButton(isArabic ? "🖨️ طباعة" : "🖨️ Print",
                            (d, w) -> printCurrentReport())
                    .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
                    .show();
        } catch (Exception e) { Log.e(TAG, "showCommitSuccessDialog error", e); }
    }

    // ==================== Archive ====================
    public void showArchiveReports() {
        if (!isAdded() || isFragmentDestroyed) return;

        AlertDialog loading = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "📂 جاري التحميل..." : "📂 Loading...")
                .setMessage(isArabic ? "يرجى الانتظار" : "Please wait")
                .setCancelable(false)
                .show();

        if (persistenceManager == null || apiClient == null || branchesManager == null) {
            try { loading.dismiss(); } catch (Exception ignored) {}
            return;
        }

        int branchId = branchesManager.getActiveBranchId();
        persistenceManager.fetchArchiveReports(apiClient, branchId,
                new InventoryPersistenceManager.ArchiveCallback() {
                    @Override public void onArchiveLoaded(List<InventoryReport> reports) {
                        mainHandler.post(() -> {
                            try { loading.dismiss(); } catch (Exception ignored) {}
                            if (isAdded() && !isFragmentDestroyed)
                                displayArchiveDialog(reports);
                        });
                    }
                    @Override public void onArchiveError(String error) {
                        mainHandler.post(() -> {
                            try { loading.dismiss(); } catch (Exception ignored) {}
                            if (isAdded() && !isFragmentDestroyed)
                                Toast.makeText(requireContext(),
                                        (isArabic ? "❌ فشل: " : "❌ ") + error,
                                        Toast.LENGTH_SHORT).show();
                        });
                    }
                });
    }

    public void showArchiveReportsPublic() {
        if (isAdded() && !isFragmentDestroyed) showArchiveReports();
    }

   private void displayArchiveDialog(List<InventoryReport> reports) {
    if (!isAdded() || isFragmentDestroyed) return;
    if (reports == null || reports.isEmpty()) {
        Toast.makeText(requireContext(),
                isArabic ? "لا توجد تقارير" : "No reports",
                Toast.LENGTH_SHORT).show();
        return;
    }

    // ✅ قائمة منسقة مع أيقونة الحالة
    String[] titles = new String[reports.size()];
    for (int i = 0; i < reports.size(); i++) {
        InventoryReport r = reports.get(i);
        String statusIcon = getReportStatusIcon(r);
        titles[i] = String.format("%s %s\n   📅 %s   •   ✅ %d/%d",
                statusIcon, r.sessionId, r.date,
                r.scannedCount, r.expectedCount);
    }

    final int[] selectedIndex = {-1};
    ArrayAdapter<String> adapter = new ArrayAdapter<>(
            requireContext(), R.layout.item_dialog_list,
            android.R.id.text1, titles);

    new MaterialAlertDialogBuilder(requireContext())
            .setTitle(isArabic ? "📂 أرشيف التقارير" : "📂 Archive Reports")
            .setSingleChoiceItems(adapter, -1, (d, which) -> {
                selectedIndex[0] = which;
            })
            .setPositiveButton(isArabic ? "⚙️ إجراءات" : "⚙️ Actions", (d, w) -> {
                if (selectedIndex[0] < 0) {
                    Toast.makeText(requireContext(),
                            isArabic ? "⚠️ اختر تقريراً أولاً"
                                     : "⚠️ Select a report first",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                d.dismiss();
                showReportActionsDialog(reports.get(selectedIndex[0]));
            })
            .setNeutralButton(isArabic ? "👁️ عرض سريع" : "👁️ Quick View", (d, w) -> {
                if (selectedIndex[0] >= 0) {
                    showReportDetails(reports.get(selectedIndex[0]));
                } else {
                    Toast.makeText(requireContext(),
                            isArabic ? "⚠️ اختر تقريراً" : "⚠️ Select a report",
                            Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
            .show();
}

/**
 * ✅ أيقونة حالة التقرير
 */
private String getReportStatusIcon(InventoryReport r) {
    if (r == null) return "📋";
    if (r.expectedCount == 0) return "⚡";           // جرد أعمى
    if (r.scannedCount >= r.expectedCount) return "✅"; // مكتمل
    if (r.scannedCount > 0) return "⚠️";             // جزئي
    return "📋";                                     // فارغ
}

    
    
    /**
 * ✅ قائمة الإجراءات الكاملة
 */
private void showReportActionsDialog(InventoryReport report) {
    if (!isAdded() || isFragmentDestroyed || report == null) return;

    String[] actions = isArabic
            ? new String[]{
                "👁️ عرض التفاصيل الكاملة",
                "🖨️ طباعة التقرير",
                "📊 مقارنة بجلسة أخرى",
                "🔗 فتح في شاشة الجرد",
                "🗑️ حذف التقرير"
            }
            : new String[]{
                "👁️ View Full Details",
                "🖨️ Print Report",
                "📊 Compare with Session",
                "🔗 Open in Inventory",
                "🗑️ Delete Report"
            };

    new MaterialAlertDialogBuilder(requireContext())
            .setTitle((isArabic ? "⚙️ إجراءات: " : "⚙️ Actions: ") + report.sessionId)
            .setItems(actions, (d, which) -> {
                switch (which) {
                    case 0: fetchAndShowReportDetails(report); break;
                    case 1: printArchivedReport(report); break;
                    case 2: showCompareSessionDialog(report); break;
                    case 3: openReportInInventory(report); break;
                    case 4: confirmDeleteReport(report); break;
                }
            })
            .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
            .show();
}

/**
 * ✅ جلب تفاصيل التقرير من السيرفر ثم عرضها
 */
private void fetchAndShowReportDetails(InventoryReport report) {
    if (!isAdded() || isFragmentDestroyed || report == null) return;
    if (persistenceManager == null || apiClient == null) {
        showReportDetails(report);
        return;
    }

    AlertDialog loading = new MaterialAlertDialogBuilder(requireContext())
            .setTitle(isArabic ? "📥 جاري التحميل..." : "📥 Loading...")
            .setMessage(isArabic ? "يتم جلب تفاصيل التقرير" : "Fetching report details")
            .setCancelable(false)
            .show();

    persistenceManager.fetchReportDetails(apiClient, report.sessionId,
            new InventoryPersistenceManager.ReportDetailsCallback() {
                @Override
                public void onDetailsLoaded(List<ScannedItemDisplay> items) {
                    mainHandler.post(() -> {
                        try { loading.dismiss(); } catch (Exception ignored) {}
                        if (!isAdded() || isFragmentDestroyed) return;

                        if (items == null || items.isEmpty()) {
                            showReportDetails(report);
                            Toast.makeText(requireContext(),
                                    isArabic ? "⚠️ لا توجد تفاصيل — عرض الملخص"
                                             : "⚠️ No details — showing summary",
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            showReportDetailsWithItems(report, items);
                        }
                    });
                }

                @Override
                public void onError(String error) {
                    mainHandler.post(() -> {
                        try { loading.dismiss(); } catch (Exception ignored) {}
                        if (!isAdded() || isFragmentDestroyed) return;
                        showReportDetails(report);
                        Toast.makeText(requireContext(),
                                (isArabic ? "⚠️ " : "⚠️ ") + error,
                                Toast.LENGTH_SHORT).show();
                    });
                }
            });
}

/**
 * ✅ عرض التقرير مع قائمة العناصر
 */
private void showReportDetailsWithItems(InventoryReport report,
                                         List<ScannedItemDisplay> items) {
    if (!isAdded() || isFragmentDestroyed) return;

    StringBuilder sb = new StringBuilder();
    sb.append(String.format("%s: %s\n", isArabic ? "الجلسة" : "Session", report.sessionId));
    sb.append(String.format("%s: %s\n\n", isArabic ? "التاريخ" : "Date", report.date));
    sb.append(String.format("📦 %s: %d\n",
            isArabic ? "العناصر" : "Items", items.size()));
    sb.append("─────────────────\n\n");

    int limit = Math.min(items.size(), 15);
    for (int i = 0; i < limit; i++) {
        ScannedItemDisplay item = items.get(i);
        sb.append(String.format("%d. %s\n   %s • %.2fg\n\n",
                i + 1, item.title, item.karat, item.weight));
    }

    if (items.size() > limit) {
        sb.append(String.format("\n... %s %d %s\n",
                isArabic ? "و" : "and",
                items.size() - limit,
                isArabic ? "عنصر آخر" : "more items"));
    }

    new MaterialAlertDialogBuilder(requireContext())
            .setTitle((isArabic ? "📋 " : "📋 ") + report.sessionId)
            .setMessage(sb.toString())
            .setPositiveButton(isArabic ? "🖨️ طباعة الكل" : "🖨️ Print All",
                    (d, w) -> {
                        try {
                            PrintUtils.printReportWithQRCode(
                                    requireContext(), report, items, isArabic, null);
                        } catch (Exception e) {
                            Log.e(TAG, "print error", e);
                        }
                    })
            .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
            .show();
}

/**
 * ✅ عرض التفاصيل (الملخص فقط)
 */
private void showReportDetails(InventoryReport report) {
    if (!isAdded() || isFragmentDestroyed || report == null) return;

    int missing = Math.max(0, report.expectedCount - report.scannedCount);
    String accuracy = report.expectedCount > 0
            ? String.format(Locale.US, "%.1f%%",
                (report.scannedCount * 100.0 / report.expectedCount))
            : "N/A";

    String msg = String.format(
            "%s: %s\n\n" +
            "📅 %s: %s\n" +
            "📦 %s: %d\n" +
            "✅ %s: %d\n" +
            "❌ %s: %d\n" +
            "📊 %s: %s\n" +
            "🏷️ %s: %s",
            isArabic ? "الجلسة" : "Session", report.sessionId,
            isArabic ? "التاريخ" : "Date", report.date,
            isArabic ? "المتوقع" : "Expected", report.expectedCount,
            isArabic ? "الممسوح" : "Scanned", report.scannedCount,
            isArabic ? "المفقود" : "Missing", missing,
            isArabic ? "الدقة" : "Accuracy", accuracy,
            isArabic ? "الحالة" : "Status", report.status);

    new MaterialAlertDialogBuilder(requireContext())
            .setTitle((isArabic ? "📋 " : "📋 ") + report.sessionId)
            .setMessage(msg)
            .setPositiveButton(isArabic ? "🖨️ طباعة" : "🖨️ Print",
                    (d, w) -> printArchivedReport(report))
            .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
            .show();
}

/**
 * ✅ طباعة تقرير من الأرشيف
 */
private void printArchivedReport(InventoryReport report) {
    if (!isAdded() || isFragmentDestroyed || report == null) return;

    try {
        List<ScannedItemDisplay> emptyItems = new ArrayList<>();
        PrintUtils.printReportWithQRCode(
                requireContext(), report, emptyItems, isArabic, null);
        Toast.makeText(requireContext(),
                isArabic ? "🖨️ جاري الطباعة..." : "🖨️ Printing...",
                Toast.LENGTH_SHORT).show();
    } catch (Exception e) {
        Log.e(TAG, "printArchivedReport error", e);
        Toast.makeText(requireContext(),
                isArabic ? "❌ فشل الطباعة" : "❌ Print failed",
                Toast.LENGTH_SHORT).show();
    }
}

/**
 * ✅ فتح التقرير في شاشة الجرد
 */
private void openReportInInventory(InventoryReport report) {
    if (!isAdded() || isFragmentDestroyed || report == null) return;

    new MaterialAlertDialogBuilder(requireContext())
            .setTitle(isArabic ? "🔗 فتح في الجرد" : "🔗 Open in Inventory")
            .setMessage(isArabic
                    ? "سيتم فتح شاشة الجرد.\n\nهل تريد المتابعة؟"
                    : "Inventory screen will open.\n\nContinue?")
            .setPositiveButton(isArabic ? "فتح" : "Open", (d, w) -> {
                if (getActivity() instanceof UHFMainActivity) {
                    ((UHFMainActivity) getActivity()).loadFragment(
                            new MiraInventoryProFragment(), "Scan");
                }
            })
            .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
            .show();
}

/**
 * ✅ مقارنة (قيد التطوير)
 */
private void showCompareSessionDialog(InventoryReport currentReport) {
    if (!isAdded() || isFragmentDestroyed || currentReport == null) return;

    Toast.makeText(requireContext(),
            isArabic ? "📊 المقارنة قيد التطوير"
                     : "📊 Comparison coming soon",
            Toast.LENGTH_LONG).show();
}

/**
 * ✅ تأكيد حذف التقرير
 */
private void confirmDeleteReport(InventoryReport report) {
    if (!isAdded() || isFragmentDestroyed || report == null) return;

    new MaterialAlertDialogBuilder(requireContext())
            .setTitle(isArabic ? "⚠️ تأكيد الحذف" : "⚠️ Confirm Delete")
            .setMessage(isArabic
                    ? "هل أنت متأكد من حذف التقرير " + report.sessionId + "؟\n\n" +
                      "⚠️ لا يمكن التراجع عن هذه العملية!"
                    : "Delete report " + report.sessionId + "?\n\n" +
                      "⚠️ This cannot be undone!")
            .setPositiveButton(isArabic ? "🗑️ حذف" : "🗑️ Delete", (d, w) -> {
                deleteReport(report);
            })
            .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show();
}

/**
 * ✅ حذف التقرير فعلياً
 */
private void deleteReport(InventoryReport report) {
    if (!isAdded() || isFragmentDestroyed) return;
    if (persistenceManager == null || apiClient == null) {
        Toast.makeText(requireContext(),
                isArabic ? "⚠️ غير جاهز" : "⚠️ Not ready",
                Toast.LENGTH_SHORT).show();
        return;
    }

    Toast.makeText(requireContext(),
            isArabic ? "🗑️ جاري الحذف..." : "🗑️ Deleting...",
            Toast.LENGTH_SHORT).show();

    persistenceManager.deleteReport(apiClient, report.sessionId,
            new InventoryPersistenceManager.DeleteCallback() {
                @Override
                public void onDeleteSuccess() {
                    mainHandler.post(() -> {
                        if (!isAdded() || isFragmentDestroyed) return;
                        Toast.makeText(requireContext(),
                                isArabic ? "✅ تم الحذف" : "✅ Deleted",
                                Toast.LENGTH_SHORT).show();
                        showArchiveReports();  // إعادة تحميل
                    });
                }

                @Override
                public void onDeleteError(String error) {
                    mainHandler.post(() -> {
                        if (!isAdded() || isFragmentDestroyed) return;
                        Toast.makeText(requireContext(),
                                (isArabic ? "❌ " : "❌ ") + error,
                                Toast.LENGTH_SHORT).show();
                    });
                }
            });
}

    // ==================== Snapshots ====================
    private void showSnapshotsList() {
        if (!isAdded() || isFragmentDestroyed) return;
        if (apiClient == null) return;
        if (persistenceManager == null) return;

        AlertDialog loading = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "📸 جاري التحميل..." : "📸 Loading...")
                .setMessage(isArabic ? "يرجى الانتظار" : "Please wait")
                .setCancelable(false)
                .show();

        persistenceManager.fetchSnapshots(apiClient, "Main Showroom", 50,
                new InventoryPersistenceManager.ArchiveCallback() {
                    @Override public void onArchiveLoaded(List<InventoryReport> snaps) {
                        mainHandler.post(() -> {
                            try { loading.dismiss(); } catch (Exception ignored) {}
                            if (isAdded() && !isFragmentDestroyed)
                                displaySnapshotsDialog(snaps);
                        });
                    }
                    @Override public void onArchiveError(String error) {
                        mainHandler.post(() -> {
                            try { loading.dismiss(); } catch (Exception ignored) {}
                        });
                    }
                });
    }

    private void displaySnapshotsDialog(List<InventoryReport> snaps) {
        if (!isAdded() || isFragmentDestroyed) return;
        if (snaps == null || snaps.isEmpty()) {
            Toast.makeText(requireContext(),
                    isArabic ? "لا توجد لقطات" : "No snapshots",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String[] arr = new String[snaps.size()];
        for (int i = 0; i < snaps.size(); i++) {
            InventoryReport r = snaps.get(i);
            arr[i] = "📸 " + r.date + " - " + r.scannedCount + " items";
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(), R.layout.item_dialog_list, android.R.id.text1, arr);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "📸 اللقطات" : "📸 Snapshots")
                .setAdapter(adapter, (d, which) -> d.dismiss())
                .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
                .show();
    }

    // ==================== Session Settings ====================
    private void showPowerControlSheet() {
        if (dialogsManager != null) {
            dialogsManager.setCurrentPowerDbm(currentPowerDbm);
            dialogsManager.showPowerControlSheet();
        }
    }
    private void applyAntennaPower(int dbm) {
        if (rfidManager != null && rfidManager.getActiveDevice() != null) {
            Log.d(TAG, "Power → " + dbm + " dBm");
        }
        Toast.makeText(requireContext(),
                (isArabic ? "القدرة: " : "Power: ") + dbm + " dBm",
                Toast.LENGTH_SHORT).show();
    }

    private void confirmResetSession() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "⚠️ تأكيد" : "⚠️ Confirm")
                .setMessage(isArabic ? "إعادة تعيين الجلسة؟" : "Reset session?")
                .setPositiveButton(isArabic ? "إعادة" : "Reset", (d, w) -> {
                    if (inventoryEngine != null) inventoryEngine.clearSession();
                    scannedItemsDisplayList.clear();
                    pendingUiItems.clear();
                    if (modernAdapter != null) {
                        modernAdapter.submitList(new ArrayList<>());
                        modernAdapter.resetAnimationTracker();
                    }
                    if (sessionManager != null) sessionManager.clearSession();
                    expectedCount = 0;
                    lastScannedForAnimation = 0;
                    if (tvScannedCount != null) tvScannedCount.setText("0");
                    if (tvItemCount != null) tvItemCount.setText("0");
                    if (tvExpectedCount != null) tvExpectedCount.setText("0");
                    if (progressScanned != null) progressScanned.setProgress(0);
                    if (tvProgressPercent != null) tvProgressPercent.setText("0%");
                    if (tvEmptyState != null) tvEmptyState.setVisibility(View.VISIBLE);
                    scheduleUiUpdate();
                    updateTotalWeightAndValue();
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
    }

    // ==================== Session Restore ====================
    private void checkAndRestoreSession() {
        if (sessionManager == null || !isAdded() || isFragmentDestroyed) return;
        sessionManager.tryRestoreSession(new InventorySessionManager.SessionCallback() {
            @Override public void onSessionFound(JSONArray items, int expected,
                                                  String category, long timeAgo) {
                if (!isAdded() || isFragmentDestroyed) return;
                showRestoreSessionDialog(items, expected, category, timeAgo);
            }
            @Override public void onNoSession() {}
        });
    }

    private void showRestoreSessionDialog(JSONArray items, int expected,
                                            String category, long timeAgo) {
        if (!isAdded() || isFragmentDestroyed || sessionManager == null) return;
        String msg = sessionManager.buildRestoreMessage(items, timeAgo);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "🔄 استرجاع" : "🔄 Restore")
                .setMessage(msg)
                .setPositiveButton(isArabic ? "استرجاع" : "Restore", (d, w) ->
                        applyRestoredSession(items, expected, category))
                .setNegativeButton(isArabic ? "بدء جديد" : "Start New", (d, w) -> {
                    if (sessionManager != null) sessionManager.clearSession();
                })
                .setCancelable(false)
                .show();
    }

    private void applyRestoredSession(JSONArray items, int expected, String category) {
        try {
            scannedItemsDisplayList.clear();
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.getJSONObject(i);
                ScannedItemDisplay d = new ScannedItemDisplay(
                        o.optString("epc"), o.optString("title"),
                        o.optString("karat"), o.optInt("count", 1),
                        o.optInt("rssi"), o.optString("time"));
                d.weight = o.optDouble("weight", 0);
                d.price = o.optDouble("price", 0);
                scannedItemsDisplayList.add(d);
            }
            if (modernAdapter != null) {
                modernAdapter.submitList(new ArrayList<>(scannedItemsDisplayList));
            }
            this.expectedCount = expected;
            if (tvExpectedCount != null)
                tvExpectedCount.setText(String.valueOf(expected));
            selectedCategoryFilter = (category != null && !category.isEmpty())
                    ? category : null;
            lastScannedForAnimation = items.length();
            scheduleUiUpdate();
            updateTotalWeightAndValue();
        } catch (Exception e) {
            Log.e(TAG, "applyRestoredSession error", e);
        }
    }

    // ==================== Segmented Control ====================
    private void setupSegmentedControl() {
        if (segmentedModes == null) return;
        segmentedModes.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.btnModeFull) {
                selectedCategoryFilter = null;
            } else if (checkedId == R.id.btnModeCategory) {
                showCategorySelectionDialog();
            } else if (checkedId == R.id.btnModeBlind) {
                selectedCategoryFilter = null;
                startBlindInventory();
            }
        });
    }

    private void showCategorySelectionDialog() {
    if (!isAdded() || isFragmentDestroyed) return;

    final String[] categories = isArabic
            ? new String[]{"الكل", "عيار 24", "عيار 22", "عيار 21",
                    "عيار 18", "عيار 14", "سبائك", "أونصة", "فضة", "ألماس", "أخرى"}
            : new String[]{"All", "24K", "22K", "21K", "18K", "14K",
                    "Bullion", "Ounce", "Silver", "Diamond", "Other"};

    final int[] selectedIndex = {0};

    ArrayAdapter<String> adapter = new ArrayAdapter<>(
            requireContext(), R.layout.item_dialog_list,
            android.R.id.text1, categories);

    new MaterialAlertDialogBuilder(requireContext())
            .setTitle(isArabic ? "🎯 اختر الفئة للجرد" : "🎯 Select Inventory Category")
            .setSingleChoiceItems(adapter, 0, (d, which) -> {
                selectedIndex[0] = which;
            })
            .setPositiveButton(isArabic ? "✅ تطبيق" : "✅ Apply", (d, w) -> {
                d.dismiss();
                applyCategoryFilter(categories[selectedIndex[0]], selectedIndex[0]);
            })
            .setNegativeButton(isArabic ? "إلغاء" : "Cancel", (d, w) -> {
                d.dismiss();
                if (segmentedModes != null && btnModeFull != null) {
                    segmentedModes.check(R.id.btnModeFull);
                }
            })
            .show();
}

    private void applyCategoryFilter(String category, int index) {
        if (index == 0) {
            Toast.makeText(requireContext(),
                    isArabic ? "✅ الكل" : "✅ All", Toast.LENGTH_SHORT).show();
            return;
        }
        selectedCategoryFilter = category;

        if (isScanning()) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(isArabic ? "⚠️ إعادة" : "⚠️ Restart")
                    .setMessage(isArabic ? "إيقاف الجرد الحالي؟" : "Stop current?")
                    .setPositiveButton(isArabic ? "نعم" : "Yes", (d, w) -> {
                        stopScanning();
                        mainHandler.postDelayed(() -> startCategoryInventory(category), 500);
                    })
                    .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                    .show();
        } else startCategoryInventory(category);
    }

    private void startCategoryInventory(String category) {
        if (isFragmentDestroyed) return;
        resetUiForNewScan();
        Toast.makeText(requireContext(),
                (isArabic ? "🔄 تحميل " : "🔄 Loading ") + category,
                Toast.LENGTH_SHORT).show();

        catalogManager.fetchCatalog(apiClient, branchesManager,
                new InventoryCatalogManager.CatalogCallback() {
                    @Override public void onCatalogLoaded(Map<String, InventoryItem> full) {
                        Map<String, InventoryItem> filtered =
                                catalogManager.filterByCategory(full, category);
                        mainHandler.post(() -> {
                            if (isFragmentDestroyed || !isAdded()) return;
                            catalogEpcs.clear();
                            catalogEpcs.addAll(filtered.keySet());
                            expectedCount = filtered.size();
                            if (tvExpectedCount != null)
                                tvExpectedCount.setText(String.valueOf(expectedCount));
                            if (scanController != null) {
                                scanController.startScanning(filtered);
                            }
                        });
                    }
                    @Override public void onError(String error) {
                        mainHandler.post(() -> {
                            if (isFragmentDestroyed || !isAdded()) return;
                            Toast.makeText(requireContext(),
                                    (isArabic ? "⚠️ " : "⚠️ ") + error,
                                    Toast.LENGTH_LONG).show();
                        });
                    }
                });
    }

    private void startBlindInventory() {
        if (isFragmentDestroyed || isScanning()) return;
        resetUiForNewScan();
        expectedCount = 0;
        if (tvExpectedCount != null) tvExpectedCount.setText("—");
        if (tvProgressPercent != null) tvProgressPercent.setText("—");
        if (tvInventoryStatus != null) {
            tvInventoryStatus.setText(isArabic ?
                    "⚡ جرد أعمى" : "⚡ Blind inventory");
        }
        if (scanController != null) {
            scanController.setBarcodeToEpcMap(catalogManager.getBarcodeToEpcMap());
            scanController.startScanning(null);
        }
    }

    // ==================== Helpers ====================
    private void updateMainToggleButton(boolean scanning) {
        if (btnMainToggleScan == null) return;
        try {
            if (scanning) {
                btnMainToggleScan.setText(isArabic ? "⏹️ إيقاف" : "⏹️ Stop");
                btnMainToggleScan.setIcon(requireContext().getDrawable(
                        android.R.drawable.ic_media_pause));
                btnMainToggleScan.setBackgroundTintList(
                        requireContext().getColorStateList(
                                android.R.color.holo_red_dark));
            } else {
                btnMainToggleScan.setText(isArabic ?
                        "▶️ بدء جرد RFID" : "▶️ Start RFID");
                btnMainToggleScan.setIcon(requireContext().getDrawable(
                        android.R.drawable.ic_media_play));
                btnMainToggleScan.setBackgroundTintList(null);
            }
        } catch (Exception ignored) {}
    }

    private String getDeviceId() {
        try {
            return android.provider.Settings.Secure.getString(
                    requireContext().getContentResolver(),
                    android.provider.Settings.Secure.ANDROID_ID);
        } catch (Exception e) { return "UNKNOWN-DEVICE"; }
    }

    private void syncOfflineTransactions() {
        if (persistenceManager == null || apiClient == null) return;
        persistenceManager.syncOfflineTransactions(apiClient,
                new InventoryPersistenceManager.SyncCallback() {
                    @Override public void onSyncComplete(int count) {
                        if (count > 0 && isAdded() && !isFragmentDestroyed) {
                            mainHandler.post(() -> Toast.makeText(requireContext(),
                                    (isArabic ? "✅ مزامنة " + count :
                                            "✅ Synced " + count),
                                    Toast.LENGTH_SHORT).show());
                        }
                    }
                    @Override public void onSyncError(String e) {
                        Log.w(TAG, "sync error: " + e);
                    }
                });
    }

    private void applyPermissions() {
        if (permissionManager == null) return;
        boolean canView = permissionManager.canView("inventory")
                || permissionManager.isManager();
        boolean canEdit = canEdit();
        if (btnMainToggleScan != null) btnMainToggleScan.setEnabled(canEdit);
        if (btnGenerateReport != null) btnGenerateReport.setEnabled(canEdit);
        if (btnCommitSession != null) btnCommitSession.setEnabled(canEdit);
        if (btnArchiveReports != null) btnArchiveReports.setEnabled(canView);
    }

    private void applyLanguage() {
        if (btnMainToggleScan != null)
            btnMainToggleScan.setText(isArabic ?
                    "▶️ بدء جرد RFID" : "▶️ Start RFID");
        if (btnGenerateReport != null)
            btnGenerateReport.setText(isArabic ? "📋 تقرير" : "📋 Report");
        if (btnArchiveReports != null)
            btnArchiveReports.setText(isArabic ? "📂 الأرشيف" : "📂 Archive");
        if (btnPowerControl != null)
            btnPowerControl.setText("⚡ " + currentPowerDbm + " dBm");
        if (btnCommitSession != null)
            btnCommitSession.setText(isArabic ? "اعتماد" : "Commit");
    }

    // ==================== Lifecycle ====================
    @Override public void onResume() {
        super.onResume();
        try {
            if (!isAdded() || isFragmentDestroyed || isRemoving() || getActivity() == null) {
                return;
            }
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).setActiveInventoryFragment(this);
            }
            if (scannedItemsDisplayList.isEmpty() && !isScanning()) {
                mainHandler.postDelayed(this::checkAndRestoreSession, 600);
            }
        } catch (Exception e) { Log.e(TAG, "onResume error", e); }
    }

    @Override public void onPause() {
        super.onPause();
        try {
            if (isAdded() && !isRemoving()) {
                if ((isScanning() || !scannedItemsDisplayList.isEmpty())
                        && sessionManager != null) {
                    sessionManager.saveSession(expectedCount,
                            scanController != null ? scanController.getScanStartTime() : 0,
                            selectedCategoryFilter, scannedItemsDisplayList);
                }
                if (getActivity() instanceof UHFMainActivity) {
                    ((UHFMainActivity) getActivity()).setActiveInventoryFragment(null);
                }
            }
        } catch (Exception e) { Log.e(TAG, "onPause error", e); }
    }

    @Override public void onDestroyView() {
        try {
            if ((isScanning() || !scannedItemsDisplayList.isEmpty())
                    && sessionManager != null) {
                sessionManager.saveSession(expectedCount,
                        scanController != null ? scanController.getScanStartTime() : 0,
                        selectedCategoryFilter, scannedItemsDisplayList);
            }
        } catch (Exception ignored) {}

        try {
            if (backPressedCallback != null) {
                backPressedCallback.remove();
                backPressedCallback = null;
            }
            isFragmentDestroyed = true;
            mainHandler.removeCallbacksAndMessages(null);
            if (scanController != null) scanController.stopScanning();
            if (soundManager != null) { soundManager.release(); soundManager = null; }
            if (filterManager != null) filterManager.reset();
            if (modernAdapter != null) modernAdapter.resetAnimationTracker();
        } catch (Exception e) { Log.e(TAG, "onDestroyView error", e); }

        super.onDestroyView();
    }

    @Override public void onDestroy() {
        super.onDestroy();
        try {
            scannedItemsDisplayList.clear();
            pendingUiItems.clear();
            catalogEpcs.clear();
            if (catalogManager != null) catalogManager.clear();
            if (persistenceManager != null) persistenceManager.cleanup();
            if (scanController != null) scanController.cleanup();
        } catch (Exception e) { Log.e(TAG, "onDestroy error", e); }
        shutdownExecutors();
    }

    private void shutdownExecutors() {
        shutdownExecutor(ioExecutor);
        shutdownExecutor(processingExecutor);
        ioExecutor = null;
        processingExecutor = null;
    }

    private void shutdownExecutor(ExecutorService ex) {
        if (ex == null || ex.isShutdown()) return;
        ex.shutdown();
        try {
            if (!ex.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                ex.shutdownNow();
            }
        } catch (InterruptedException e) {
            ex.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ==================== Public APIs ====================
    public void showInventoryScannerSettings() {
        if (isAdded() && !isFragmentDestroyed) showPowerControlSheet();
    }
    public void showInventorySessionSettings() {
        if (isAdded() && !isFragmentDestroyed && dialogsManager != null) {
            dialogsManager.setExpectedCount(expectedCount);
            dialogsManager.showSessionSettingsDialog();
        }
    }
    public void showInventoryBuzzerSettings() {
        if (isAdded() && !isFragmentDestroyed && dialogsManager != null) {
            dialogsManager.showBuzzerSettingsDialog();
        }
    }

    public int getScannedCount() {
        try {
            if (inventoryEngine != null) return inventoryEngine.getUniqueCount();
            return scannedItemsDisplayList.size();
        } catch (Exception e) { return 0; }
    }

    public boolean isCurrentlyScanning() {
        return isScanning();
    }
    }
