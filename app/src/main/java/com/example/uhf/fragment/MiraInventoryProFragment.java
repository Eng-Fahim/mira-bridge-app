package com.example.uhf.fragment;

import android.app.Activity;
import android.content.Context;
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
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.adapter.ModernScannedItemsAdapter;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.dialog.inventory.InventoryDialogsManager;
import com.example.uhf.model.InventorySnapshot;
import com.example.uhf.engine.MiraInventoryEngine;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.manager.inventory.InventoryCatalogManager;
import com.example.uhf.manager.inventory.InventoryFeedbackHelper;
import com.example.uhf.manager.inventory.InventoryFilterManager;
import com.example.uhf.manager.inventory.InventoryPersistenceManager;
import com.example.uhf.manager.inventory.InventoryPrintManager;
import com.example.uhf.manager.inventory.InventoryScanController;
import com.example.uhf.manager.inventory.InventorySessionManager;
import com.example.uhf.model.InventoryItem;
import com.example.uhf.model.InventoryReport;
import com.example.uhf.model.ScannedItemDisplay;
import com.example.uhf.utils.InventoryAnimationHelper;
import com.example.uhf.utils.MiraExecutorService;
import com.example.uhf.utils.SoundManager;
import com.example.uhf.manager.inventory.InventorySnapshotManager;
import com.example.uhf.MiraRfid.MiraReaderSettings;
import com.example.uhf.fragment.MiraDashboardFragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import com.example.uhf.MiraRfid.MiraScanSessionController;
/**
 * MiraInventoryProFragment - الإصدار 4.0 (DIRECT C83 READER)
 *
 * ✅ الإصلاحات الجوهرية عن 3.0:
 *  1. تهيئة RFID مبكراً (قبل initViews)
 *  2. الزناد يستجيب DOWN/UP فوراً
 *  3. startScanning لا ينتظر الكتالوج
 *  4. onTagRead بدون posting مزدوج
 *  5. stopScanning فوري
 *  6. إزالة التوست المزعج عند الإيقاف
 *  7. loadCatalogInBackground (جديد)
 *  8. processTagReadDirect (جديد)
 *  9. reclassifyScannedItems (جديد)
 */
 public class MiraInventoryProFragment extends KeyDwonFragment
        implements InventoryScanController.ScanCallback,
                   InventoryDialogsManager.DialogsCallback,
                   MiraScanSessionController.Host {  
    private static final String TAG = "MiraInventoryPro";

    // ==================== ثوابت ====================
    private static final long UI_UPDATE_THROTTLE_MIN_MS = 100;
    private static final long UI_UPDATE_THROTTLE_MEDIUM_MS = 200;
    private static final long UI_UPDATE_THROTTLE_MAX_MS = 350;

    private static final int ITEMS_COUNT_SMALL = 200;
    private static final int ITEMS_COUNT_MEDIUM = 800;
    
    

    // ==================== Refs ====================
    private View welcomeState;
    private View activeContentGroup;
    private View progressCard;
    private View metricsRow;
    private View fancyDivider;
    private View iconActionsRow;
    private View nestedScroll;
    private ImageButton btnInventoryClose;

    private static final int KEYCODE_RFID_TRIGGER_1 = 139;
    private static final int KEYCODE_RFID_TRIGGER_2 = 280;
    private static final int KEYCODE_RFID_TRIGGER_3 = 293;

    private static final int ITEMS_LIST_PAGE_SIZE = 100;

    private static final String PREFS_UI = "mira_inventory_ui_prefs";
    private static final String KEY_SKIP_RESTORE_PROMPT = "skip_restore_prompt";
    private static final long MIN_SESSION_AGE_TO_PROMPT_MS = 2 * 60 * 1000;
    private static final int MIN_ITEMS_TO_PROMPT = 3;

    // ==================== Views ====================
    private TextView tvProgressPercent;
    private ProgressBar progressScanned;
    private TextView tvScannedCount;
    private TextView tvExpectedCount;

    private View statusDot;
    private View statusBar;
    private TextView tvInventoryStatus;
    private TextView tvElapsedTime;
    private TextView tvRssi;

    private MaterialButtonToggleGroup segmentedModes;
    private MaterialButton btnModeFull;
    private MaterialButton btnModeCategory;
    private MaterialButton btnModeBlind;
    private MaterialButton btnViewSnapshots;

    private TextView tvVariance;
    private TextView tvAccuracy;
    private TextView tvItemCount;

    private TextView tvTotalWeight;
    private TextView tvTotalValue;

    private MaterialButton btnPowerControl;
    private MaterialButton btnGenerateReport;
    private MaterialButton btnArchiveReports;

    private EditText etSearchItems;
    private ImageView btnClearSearch;
    private ImageView btnLiveFilter;

    private RecyclerView rvInventoryItems;
    private ModernScannedItemsAdapter modernAdapter;
    private View tvEmptyState;

    private MaterialButton btnMainToggleScan;
    private MaterialButton btnCommitSession;

    private View lastScanCard;
    private TextView tvLastScanTitle;
    private TextView tvLastScanEpc;
    private TextView tvLastScanKarat;
    private TextView tvLastScanWeight;
    private TextView tvLastScanRssi;
    private TextView tvLastScanStatus;

    private TextView tvLastScanPrice;
    private TextView tvLastScanReadCount;
    private TextView tvLastScanSerial;
    private TextView tvLastScanTime;
    private View chipReadCount;
    private View rowSerialTime;

    private ImageView ivWelcomeLogo;

    private MaterialButton btnOpenItemsList;
    private MaterialButton btnPowerControlCompact;
    private MaterialButton btnArchiveReportsCompact;

    // ==================== Managers ====================
    private InventoryFeedbackHelper feedbackHelper;
    private InventoryAnimationHelper animationHelper;
    private InventorySessionManager sessionManager;
    private InventoryCatalogManager catalogManager;
    private InventoryFilterManager filterManager;
    private InventoryDialogsManager dialogsManager;
    private InventoryPersistenceManager persistenceManager;
    private InventoryPrintManager printManager;
    private InventoryScanController scanController;
    private MiraScanSessionController scanSession;

    // ==================== Core ====================
    private MiraInventoryEngine inventoryEngine;
    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private PermissionManager permissionManager;
    private SoundManager soundManager;

    // ==================== Executors ====================
    private MiraExecutorService miraExecutor;
    private ExecutorService backgroundExecutor;
    private ExecutorService ioExecutor;

    // ==================== Handlers ====================
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ==================== State ====================
    private volatile boolean isScanning = false;
    private volatile boolean isFragmentDestroyed = false;
    private volatile boolean isArabic = true;
    private volatile int expectedCount = 0;
    // ✅ جديد
    private volatile int currentPowerDbm = 27;  // fallback فقط — يُستبدل فوراً
    private volatile String selectedCategoryFilter = null;
    private volatile int currentLiveFilter = 0;
    private volatile int lastScannedForAnimation = 0;
    private JSONObject lastServerReport = null;
    private boolean autoSnapshotEnabled = true;
    // ✅ [جديد] لمنع تكرار الاحتفال
    private volatile boolean hasCelebrated = false;

    private long scanStartTime = 0;
    private volatile boolean pendingUiUpdate = false;

    private volatile double aggregateTotalWeight = 0.0;
    private volatile double aggregateTotalValue = 0.0;

    private volatile boolean hasTriedRestoringSession = false;

    private MaterialButton btnTakeSnapshot;
    private InventorySnapshotManager snapshotManager;
    
    /** ✅ [Stage-1] لمنع الاسترجاع المزدوج */
private volatile boolean hasRestoredThisSession = false;
    
    // ═══════════════════════════════════════════════════════
//  ✅ [جديد - Stage 1] حالة الجلسة
// ═══════════════════════════════════════════════════════

/** هل الجلسة مُوقفة مؤقتاً (يمكن الاستئناف)؟ */
private volatile boolean isPaused = false;
/** ✅ v4.0 — هل بدء المسح القادم استئناف لجلسة سابقة؟ */
private volatile boolean nextScanIsResume = false;
/** هل هناك جلسة محفوظة لم تُعتمد بعد؟ */
private volatile boolean hasUncommittedSession = false;

/** متى بدأت الجلسة الفعلية (لا يتصفّر عند الإيقاف) */
private volatile long sessionElapsedBeforePause = 0;

/** زر الإيقاف المؤقت */


/** زر الإنهاء والمراجعة */
private MaterialButton btnFinishAndReview;

    // ==================== Collections ====================
    private final List<ScannedItemDisplay> scannedItemsDisplayList =
            new CopyOnWriteArrayList<>();

    private final Set<String> catalogEpcs =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    // ==================== Back Press ====================
    private OnBackPressedCallback backPressedCallback;

    // ==================== Factory ====================

    public static MiraInventoryProFragment newInstance() {
        return new MiraInventoryProFragment();
    }

    public static MiraInventoryProFragment newInstance(String mode) {
        MiraInventoryProFragment fragment = new MiraInventoryProFragment();
        Bundle args = new Bundle();
        args.putString("INVENTORY_MODE", mode);
        fragment.setArguments(args);
        return fragment;
    }

    public boolean isScanning() { return isScanning; }

    // ==================== Lifecycle: onCreateView ====================

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = null;

        // 1) Inflate
        try {
            view = inflater.inflate(R.layout.fragment_mira_inventory_pro, container, false);
        } catch (Exception e) {
            Log.e(TAG, "Inflate error", e);
            TextView fallback = new TextView(requireContext());
            fallback.setText("✅ الجرد يعمل (Fallback)\n\n" + e.getMessage());
            fallback.setTextSize(16);
            fallback.setGravity(android.view.Gravity.CENTER);
            return fallback;
        }

        isFragmentDestroyed = false;

        // 2) Executors
        try {
            miraExecutor = MiraExecutorService.getInstance();
            if (miraExecutor != null && miraExecutor.isHealthy()) {
                backgroundExecutor = miraExecutor.getBackgroundExecutor();
                ioExecutor = miraExecutor.getBackgroundExecutor();
            } else {
                backgroundExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
                ioExecutor = backgroundExecutor;
            }
        } catch (Exception e) {
            Log.e(TAG, "Executor init error", e);
            backgroundExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
            ioExecutor = backgroundExecutor;
        }

        // 3) Locale + RTL
        try {
            isArabic = Locale.getDefault().getLanguage().equals("ar");
            if (view != null) {
                view.setLayoutDirection(isArabic
                        ? View.LAYOUT_DIRECTION_RTL
                        : View.LAYOUT_DIRECTION_LTR);
            }
        } catch (Exception e) {
            Log.e(TAG, "Locale error", e);
        }

        // 4) SoundManager
        try {
            soundManager = new SoundManager(requireContext());
        } catch (Exception e) {
            Log.e(TAG, "SoundManager error", e);
        }
        
        scanSession = new MiraScanSessionController(this);


        // 5) FeedbackHelper + AnimationHelper
        try {
            feedbackHelper = new InventoryFeedbackHelper(requireContext());
        } catch (Exception e) {
            Log.e(TAG, "FeedbackHelper error", e);
        }

        try {
            animationHelper = new InventoryAnimationHelper(isArabic);
        } catch (Exception e) {
            Log.e(TAG, "AnimationHelper error", e);
        }

        // 6) SessionManager
        try {
            sessionManager = new InventorySessionManager(requireContext(), isArabic);
        } catch (Exception e) {
            Log.e(TAG, "SessionManager error", e);
        }

        // 7) CatalogManager
        try {
            catalogManager = new InventoryCatalogManager(requireContext(), isArabic);
        } catch (Exception e) {
            Log.e(TAG, "CatalogManager error", e);
        }

        // 8) FilterManager
        try {
            filterManager = new InventoryFilterManager(isArabic);
        } catch (Exception e) {
            Log.e(TAG, "FilterManager error", e);
        }

        // 9) DialogsManager
        try {
            dialogsManager = new InventoryDialogsManager(requireContext(), isArabic);
            dialogsManager.setSoundManager(soundManager);
            dialogsManager.setCurrentPowerDbm(currentPowerDbm);
            dialogsManager.setCallback(this);
        } catch (Exception e) {
            Log.e(TAG, "DialogsManager error", e);
        }
        
        // ✅ [جديد] زامن القيمة الحالية مع MiraReaderSettings
        try {
        MiraReaderSettings settings = MiraReaderSettings.getInstance(requireContext());
        currentPowerDbm = settings.getPowerDbm();
        Log.d(TAG, "✅ Power synced from MiraReaderSettings: " + currentPowerDbm + " dBm");
        } catch (Exception e) {
        Log.w(TAG, "Sync power error: " + e.getMessage());
        }
        // 10) PersistenceManager
        try {
            persistenceManager = new InventoryPersistenceManager(
                    requireContext(), isArabic, ioExecutor);
        } catch (Exception e) {
            Log.e(TAG, "PersistenceManager error", e);
        }

        // 11) PrintManager
        try {
            printManager = new InventoryPrintManager(requireContext(), isArabic);
            setupPrintManagerCallback();
        } catch (Exception e) {
            Log.e(TAG, "PrintManager error", e);
        }

        // 12) SnapshotManager
        try {
            snapshotManager = new InventorySnapshotManager(requireContext(), ioExecutor);
        } catch (Exception e) {
            Log.e(TAG, "snapshotManager error", e);
        }

        // 13) Core: Engine + API + Branches + Permissions
        try {
            String sessionId = "SESS-" + UUID.randomUUID().toString()
                    .substring(0, 8).toUpperCase(Locale.US);
            inventoryEngine = new MiraInventoryEngine(
                    requireContext(), sessionId, getDeviceId());
        } catch (Exception e) {
            Log.e(TAG, "Engine error", e);
        }

        try {
            apiClient = MiraApiClient.getInstance(requireContext());
        } catch (Exception e) {
            apiClient = null;
        }

        try {
            branchesManager = MiraBranchesManager.getInstance(requireContext());
        } catch (Exception e) {
            branchesManager = null;
        }

        try {
            permissionManager = PermissionManager.getInstance(requireContext());
        } catch (Exception e) {
            permissionManager = null;
        }

        // 14) ScanController
        try {
            scanController = new InventoryScanController(
                    requireContext(), isArabic, backgroundExecutor, ioExecutor);
            scanController.setCallback(this);
            scanController.setSoundManager(soundManager);
            scanController.setCurrentPowerDbm(currentPowerDbm);
            if (inventoryEngine != null) {
                scanController.setInventoryEngine(inventoryEngine);
            }
        } catch (Exception e) {
            Log.e(TAG, "ScanController error", e);
        }

        // ✅✅✅ 15) RFID Manager — تهيئة فورية (قبل initViews)
try {
    com.example.uhf.MiraRfid.RFIDManager rfidManager =
            com.example.uhf.MiraRfid.RFIDManager.getInstance(requireContext());

    if (rfidManager != null && scanController != null) {
        scanController.attachRfidManager(rfidManager);

        Log.d(TAG, "✅ RFID attached EARLY: state=" + rfidManager.getState());
    }
} catch (Exception e) {
    Log.e(TAG, "Early RFID init error", e);
}

        // 16) Views
        try {
            initViews(view);
        } catch (Exception e) {
            Log.e(TAG, "initViews error", e);
        }

        mainHandler.postDelayed(this::animateWelcomeLogo, 300);

        // 17) Back press
        try {
            setupBackPressHandler();
        } catch (Exception e) {
            Log.e(TAG, "BackPress error", e);
        }

        // 18) Permissions UI + Language
        try {
            applyPermissions();
        } catch (Exception e) {
            Log.e(TAG, "Permissions error", e);
        }

        try {
            applyLanguage();
        } catch (Exception e) {
            Log.e(TAG, "Language error", e);
        }

        // 19) تحديث أولي لحالة الأزرار
        mainHandler.postDelayed(() -> {
            if (isAdded() && !isFragmentDestroyed) {
                updateButtonsState();
            }
        }, 150);

        // 20) استرجاع صامت فوري
        mainHandler.postDelayed(this::silentRestoreSession, 100);

        // 21) تحديد الحالة الأولية (Welcome / Active)
        mainHandler.postDelayed(() -> {
            if (isAdded() && !isFragmentDestroyed) {
                if (!scannedItemsDisplayList.isEmpty()) {
                    switchToActiveState(false);
                } else {
                    switchToWelcomeState();
                }
            }
        }, 200);

        return view;
    }

    // ==================== initViews ====================

    private void initViews(View view) {
        if (view == null) return;

        btnInventoryClose = view.findViewById(R.id.btnInventoryClose);

        tvProgressPercent = view.findViewById(R.id.tvProgressPercent);
        progressScanned = view.findViewById(R.id.progressScanned);
        tvScannedCount = view.findViewById(R.id.tvScannedCount);
        tvExpectedCount = view.findViewById(R.id.tvExpectedCount);

        btnTakeSnapshot = view.findViewById(R.id.btnTakeSnapshot);
        btnViewSnapshots = view.findViewById(R.id.btnViewSnapshots);

        ivWelcomeLogo = view.findViewById(R.id.ivWelcomeLogo);
        welcomeState = view.findViewById(R.id.welcomeState);
        activeContentGroup = view.findViewById(R.id.activeContentGroup);
        progressCard = view.findViewById(R.id.progressCard);
        metricsRow = view.findViewById(R.id.metricsRow);
        fancyDivider = view.findViewById(R.id.fancyDivider);
        iconActionsRow = view.findViewById(R.id.iconActionsRow);
        nestedScroll = view.findViewById(R.id.nestedScroll);

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

        MaterialButton btnClearSession = view.findViewById(R.id.btnClearSession);
        if (btnClearSession != null) {
            btnClearSession.setOnClickListener(v -> confirmResetSession());
        }

        btnPowerControl = view.findViewById(R.id.btnPowerControl);
        btnGenerateReport = view.findViewById(R.id.btnGenerateReport);
        btnArchiveReports = view.findViewById(R.id.btnArchiveReports);

        etSearchItems = view.findViewById(R.id.etSearchItems);
        btnClearSearch = view.findViewById(R.id.btnClearSearch);
        btnLiveFilter = view.findViewById(R.id.btnLiveFilter);

        rvInventoryItems = view.findViewById(R.id.rvInventoryItems);
        tvEmptyState = view.findViewById(R.id.tvEmptyState);

        btnMainToggleScan = view.findViewById(R.id.btnMainToggleScan);
        btnCommitSession = view.findViewById(R.id.btnCommitSession);

        View flashOverlayView = view.findViewById(R.id.flashOverlay);
        if (feedbackHelper != null) {
            feedbackHelper.setFlashOverlay(flashOverlayView);
        }

        lastScanCard = view.findViewById(R.id.lastScanCard);
        tvLastScanTitle = view.findViewById(R.id.tvLastScanTitle);
        tvLastScanEpc = view.findViewById(R.id.tvLastScanEpc);
        tvLastScanKarat = view.findViewById(R.id.tvLastScanKarat);
        tvLastScanWeight = view.findViewById(R.id.tvLastScanWeight);
        tvLastScanRssi = view.findViewById(R.id.tvLastScanRssi);
        tvLastScanStatus = view.findViewById(R.id.tvLastScanStatus);

        btnOpenItemsList = view.findViewById(R.id.btnOpenItemsList);
        btnPowerControlCompact = view.findViewById(R.id.btnPowerControlCompact);
        btnArchiveReportsCompact = view.findViewById(R.id.btnArchiveReportsCompact);

        tvLastScanPrice = view.findViewById(R.id.tvLastScanPrice);
        tvLastScanReadCount = view.findViewById(R.id.tvLastScanReadCount);
        tvLastScanSerial = view.findViewById(R.id.tvLastScanSerial);
        tvLastScanTime = view.findViewById(R.id.tvLastScanTime);
        chipReadCount = view.findViewById(R.id.chipReadCount);
        rowSerialTime = view.findViewById(R.id.rowSerialTime);
        
        // ✅ [Stage-1] ربط الأزرار الجديدة

btnFinishAndReview = view.findViewById(R.id.btnFinishAndReview);

        setupRecyclerView();
        setupButtons();
        setupSearchBar();
        setupSegmentedControl();
    }

    private void setupRecyclerView() {
        try {
            if (rvInventoryItems == null) return;

            rvInventoryItems.setLayoutManager(
                    new LinearLayoutManager(requireContext()));
            rvInventoryItems.setNestedScrollingEnabled(false);

            modernAdapter = new ModernScannedItemsAdapter(isArabic, item -> {
                if (dialogsManager != null) {
                    dialogsManager.showItemOptionsDialog(item);
                }
            });

            rvInventoryItems.setAdapter(modernAdapter);
            rvInventoryItems.setHasFixedSize(false);
            rvInventoryItems.setItemAnimator(null);
        } catch (Exception e) {
            Log.e(TAG, "setupRecyclerView error", e);
        }
    }

    private void setupButtons() {
    // ✅ زر Start/Pause الرئيسي
    if (btnMainToggleScan != null) {
        btnMainToggleScan.setOnClickListener(v -> {
            if (isScanning) {
                pauseScanning();  // ✅ Pause بدلاً من Stop
            } else {
                if (canEditInventory()) {
                    startScanning();  // ✅ Resume أو New
                } else {
                    showAccessDenied();
                }
            }
        });
    }

    // ✅ [جديد] زر "إنهاء ومراجعة"
    if (btnFinishAndReview != null) {
        btnFinishAndReview.setOnClickListener(v -> {
            if (isScanning) {
                pauseScanning();
                mainHandler.postDelayed(this::showFinishAndReviewDialog, 300);
            } else {
                showFinishAndReviewDialog();
            }
        });
    }

    // ✅ زر الاعتماد (كما هو)
    if (btnCommitSession != null) {
        btnCommitSession.setOnClickListener(v -> showCommitConfirmationDialog());
    }

    // ✅ باقي الأزرار كما هي
    if (btnPowerControl != null) {
        btnPowerControl.setOnClickListener(v -> showPowerControlSheet());
    }
    if (btnGenerateReport != null) {
        btnGenerateReport.setOnClickListener(v -> printCurrentReport());
    }
    if (btnTakeSnapshot != null) {
        btnTakeSnapshot.setOnClickListener(v -> takeSnapshot());
    }
    if (btnViewSnapshots != null) {
        btnViewSnapshots.setOnClickListener(v -> openSnapshotsScreen());
    }
    if (btnArchiveReports != null) {
        btnArchiveReports.setOnClickListener(v -> showArchiveReports());
    }
    if (btnOpenItemsList != null) {
        btnOpenItemsList.setOnClickListener(v -> showItemsListDialog());
    }
    if (btnPowerControlCompact != null) {
        btnPowerControlCompact.setOnClickListener(v -> showPowerControlSheet());
    }
    if (btnArchiveReportsCompact != null) {
        btnArchiveReportsCompact.setOnClickListener(v -> showArchiveReports());
    }

    // زر الإغلاق
    if (btnInventoryClose != null) {
        btnInventoryClose.setOnClickListener(v -> {
            try {
                if (getActivity() != null) {
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager)
                            getActivity().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                    if (imm != null && etSearchItems != null) {
                        imm.hideSoftInputFromWindow(etSearchItems.getWindowToken(), 0);
                    }
                }
            } catch (Exception ignored) {}
            handleCustomBack();
        });
    }
}

/**
 * ✅ [Stage-1] نافذة "إنهاء ومراجعة"
 *             تُعرض قبل الاعتماد النهائي
 */
private void showFinishAndReviewDialog() {
    if (!isAdded() || isFragmentDestroyed) return;

    try {
        int scanned = scannedItemsDisplayList.size();
        if (scanned == 0) {
            showToast(isArabic ? "⚠️ لا توجد بيانات لمراجعتها"
                               : "⚠️ No data to review");
            return;
        }

        // حساب الإحصائيات
        int registered = 0;
        int unregistered = 0;
        int totalReads = 0;

        synchronized (scannedItemsDisplayList) {
            for (ScannedItemDisplay item : scannedItemsDisplayList) {
                if (item == null) continue;
                totalReads += item.count;

                if (catalogEpcs.contains(item.epc)) {
                    registered++;
                } else {
                    unregistered++;
                }
            }
        }

        int missing = Math.max(0, expectedCount - registered);
        int accuracy = expectedCount > 0
                ? Math.min(100, (registered * 100) / expectedCount)
                : 0;

        long elapsedSeconds = getTotalElapsedSeconds();
        String elapsed = formatElapsed(elapsedSeconds);

        // بناء نص الرسالة
        StringBuilder sb = new StringBuilder();
        sb.append(isArabic
                ? "📋 ملخص الجلسة قبل الاعتماد:\n\n"
                : "📋 Session summary before commit:\n\n");

        sb.append(isArabic ? "✅ مسجّل وممسوح: " : "✅ Registered & scanned: ")
          .append(registered).append("\n");

        sb.append(isArabic ? "⚠️ غير مسجّل: " : "⚠️ Unregistered: ")
          .append(unregistered).append("\n");

        if (expectedCount > 0) {
            sb.append(isArabic ? "❌ مفقود: " : "❌ Missing: ")
              .append(missing).append("\n");
            sb.append(isArabic ? "📈 الدقة: " : "📈 Accuracy: ")
              .append(accuracy).append("%\n");
        }

        sb.append(isArabic ? "🔁 إجمالي القراءات: " : "🔁 Total reads: ")
          .append(totalReads).append("\n");

        sb.append(isArabic ? "⏱️ المدة: " : "⏱️ Duration: ")
          .append(elapsed).append("\n");

        // عرض النافذة
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "🏁 إنهاء الجرد ومراجعته" : "🏁 Finish & Review")
                .setMessage(sb.toString())
                .setPositiveButton(isArabic ? "✅ اعتماد نهائي" : "✅ Commit",
                        (d, w) -> {
                            d.dismiss();
                            showCommitConfirmationDialog();
                        })
                .setNeutralButton(isArabic ? "🔍 عرض القطع" : "🔍 Show Items",
                        (d, w) -> {
                            d.dismiss();
                            showItemsListDialog();
                        })
                .setNegativeButton(isArabic ? "↩️ العودة للشاشة" : "↩️ Back to Screen", (d, w) -> {
    d.dismiss();
    // ✅ لا تبدأ المسح — دع المستخدم يضغط الزر بنفسه
    switchToActiveState(false);
    updateButtonsState();
})
                .setCancelable(false)
                .show();

    } catch (Exception e) {
        Log.e(TAG, "showFinishAndReviewDialog error", e);
    }
}

/** ✅ حساب الوقت الكلي (مع مراعاة الإيقاف المؤقت) */
private long getTotalElapsedSeconds() {
    long current = 0;
    if (isScanning && scanStartTime > 0) {
        current = (SystemClock.elapsedRealtime() - scanStartTime) / 1000;
    }
    return sessionElapsedBeforePause + current;
}

/** ✅ تنسيق المدة */
private String formatElapsed(long seconds) {
    long h = seconds / 3600;
    long m = (seconds % 3600) / 60;
    long s = seconds % 60;
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s);
}

    private void setupSearchBar() {
        if (etSearchItems == null) return;

        etSearchItems.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterItems(s.toString());
                if (btnClearSearch != null) {
                    btnClearSearch.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        if (btnClearSearch != null) {
            btnClearSearch.setOnClickListener(v -> {
                if (etSearchItems != null) etSearchItems.setText("");
            });
        }
    }

    private void setupSegmentedControl() {
        if (segmentedModes == null) return;

        int activeBg = ContextCompat.getColor(requireContext(), R.color.gold_500);
        int inactiveBg = ContextCompat.getColor(requireContext(), R.color.pure_white);
        int activeText = ContextCompat.getColor(requireContext(), R.color.pure_white);
        int inactiveText = ContextCompat.getColor(requireContext(), R.color.text_gold_accent);

        Runnable updateStyles = new Runnable() {
            @Override
            public void run() {
                int checkedId = segmentedModes.getCheckedButtonId();
                MaterialButton[] buttons = {btnModeFull, btnModeCategory, btnModeBlind};

                for (MaterialButton btn : buttons) {
                    if (btn == null) continue;
                    boolean isActive = btn.getId() == checkedId;

                    btn.setBackgroundTintList(
                            android.content.res.ColorStateList.valueOf(
                                    isActive ? activeBg : inactiveBg));
                    btn.setTextColor(isActive ? activeText : inactiveText);
                    btn.setIconTint(
                            android.content.res.ColorStateList.valueOf(
                                    isActive ? activeText : inactiveText));
                }
            }
        };

        mainHandler.post(updateStyles);

        segmentedModes.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;

            updateStyles.run();

            if (checkedId == R.id.btnModeFull) {
                selectedCategoryFilter = null;
                Log.d(TAG, "Mode: Full");
                if (!isScanning && scannedItemsDisplayList.isEmpty()) {
                    showToast(isArabic ? "🎯 جاهز للجرد الكامل"
                                       : "🎯 Ready for Full Inventory");
                }
            } else if (checkedId == R.id.btnModeCategory) {
                if (isScanning) {
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle(isArabic ? "⚠️ الجرد قيد التشغيل" : "⚠️ Inventory Running")
                            .setMessage(isArabic ?
                                    "لتغيير الفئة، يجب إيقاف الجرد الحالي.\nهل تريد المتابعة؟" :
                                    "To change category, stop the current inventory.\nContinue?")
                            .setPositiveButton(isArabic ? "إيقاف ومتابعة" : "Stop & Continue",
                                    (d, w) -> {
                                        stopScanning();
                                        mainHandler.postDelayed(
                                                this::showCategorySelectionDialog, 500);
                                    })
                            .setNegativeButton(isArabic ? "إلغاء" : "Cancel",
                                    (d, w) -> {
                                        if (segmentedModes != null && btnModeFull != null) {
                                            segmentedModes.check(R.id.btnModeFull);
                                        }
                                    })
                            .show();
                } else {
                    showCategorySelectionDialog();
                }
            } else if (checkedId == R.id.btnModeBlind) {
                selectedCategoryFilter = null;
                if (!isScanning) {
                    startBlindInventory();
                } else {
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle(isArabic ? "⚠️ الجرد قيد التشغيل" : "⚠️ Inventory Running")
                            .setMessage(isArabic ?
                                    "هل تريد إيقاف الجرد الحالي وبدء الجرد الأعمى؟" :
                                    "Stop current inventory and start blind inventory?")
                            .setPositiveButton(isArabic ? "متابعة" : "Continue",
                                    (d, w) -> {
                                        stopScanning();
                                        mainHandler.postDelayed(
                                                this::startBlindInventory, 500);
                                    })
                            .setNegativeButton(isArabic ? "إلغاء" : "Cancel",
                                    (d, w) -> {
                                        if (segmentedModes != null && btnModeFull != null) {
                                            segmentedModes.check(R.id.btnModeFull);
                                        }
                                    })
                            .show();
                }
            }
        });
    }

    // ==================== State Switching ====================

    private void switchToActiveState(boolean animate) {
        if (!isAdded() || isFragmentDestroyed) return;

        if (welcomeState != null) welcomeState.setVisibility(View.GONE);
        if (activeContentGroup == null) return;

        if (!animate) {
            activeContentGroup.setVisibility(View.VISIBLE);
            return;
        }

        activeContentGroup.setVisibility(View.VISIBLE);

        View[] cards = {
            lastScanCard,
            progressCard,
            metricsRow,
            fancyDivider,
            btnOpenItemsList,
            iconActionsRow
        };

        for (View v : cards) {
            if (v != null) {
                v.setAlpha(0f);
                v.setTranslationY(30f);
            }
        }

        for (int i = 0; i < cards.length; i++) {
            View v = cards[i];
            if (v == null) continue;
            v.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(i * 80L)
                    .setDuration(350)
                    .start();
        }
    }

    private void switchToWelcomeState() {
        if (!isAdded() || isFragmentDestroyed) return;

        if (activeContentGroup != null) activeContentGroup.setVisibility(View.GONE);
        if (welcomeState != null) welcomeState.setVisibility(View.VISIBLE);
    }

    private void animateCount(TextView textView, int from, int to) {
        if (textView == null || from == to) {
            if (textView != null) textView.setText(String.valueOf(to));
            return;
        }
        try {
            android.animation.ValueAnimator animator =
                    android.animation.ValueAnimator.ofInt(from, to);
            animator.setDuration(400);
            animator.setInterpolator(new android.view.animation.DecelerateInterpolator());
            animator.addUpdateListener(animation ->
                    textView.setText(String.valueOf(animation.getAnimatedValue()))
            );
            animator.start();
        } catch (Exception e) {
            textView.setText(String.valueOf(to));
        }
    }

    // ==================== Print Manager Callback ====================

    private void setupPrintManagerCallback() {
        if (printManager == null) return;

        printManager.setCallback(new InventoryPrintManager.PrintCallback() {
            @Override
            public List<ScannedItemDisplay> getScannedItems() {
                return new ArrayList<>(scannedItemsDisplayList);
            }

            @Override
            public String getSessionId() {
                return inventoryEngine != null
                        ? inventoryEngine.getClientSessionId()
                        : "SESS-UNKNOWN";
            }

            @Override
            public int getExpectedCount() {
                return expectedCount;
            }

            @Override
            public int getScannedCount() {
                return inventoryEngine != null
                        ? inventoryEngine.getUniqueCount()
                        : scannedItemsDisplayList.size();
            }

            @Override
            public List<InventoryReport> getArchivedReports() {
                return new ArrayList<>();
            }
        });
    }

    // ==================== Back Press ====================

    private void setupBackPressHandler() {
        backPressedCallback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleCustomBack();
            }
        };

        requireActivity().getOnBackPressedDispatcher().addCallback(
                getViewLifecycleOwner(), backPressedCallback);
    }

    private void handleCustomBack() {
    try {
        if (isScanning) {
            // ✅ إيقاف مؤقت بدلاً من نهائي
            pauseScanning();
            mainHandler.postDelayed(this::checkAndExitAfterPause, 300);
            return;
        }

        if (scannedItemsDisplayList != null && !scannedItemsDisplayList.isEmpty()) {
            showExitConfirmation();
            return;
        }

        navigateAwayFromInventory();
    } catch (Exception e) {
        Log.e(TAG, "handleCustomBack error", e);
        navigateAwayFromInventory();
    }
}

/** ✅ بعد الإيقاف المؤقت، أعد تقييم الخروج */
private void checkAndExitAfterPause() {
    if (!isAdded() || isFragmentDestroyed) return;

    if (scannedItemsDisplayList != null && !scannedItemsDisplayList.isEmpty()) {
        showExitConfirmation();
    } else {
        navigateAwayFromInventory();
    }
}

    private void showExitConfirmation() {
        if (!isAdded() || isFragmentDestroyed) return;

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "📦 مغادرة شاشة الجرد؟" : "📦 Leave Inventory?")
                .setMessage(isArabic
                        ? "لديك " + scannedItemsDisplayList.size() + " قطعة في الجلسة الحالية.\n\n"
                        + "هل تريد المغادرة؟ (سيتم حفظ الجلسة تلقائياً)"
                        : "You have " + scannedItemsDisplayList.size() + " items in the session.\n\n"
                        + "Leave anyway? (Session will be saved)")
                .setPositiveButton(isArabic ? "مغادرة" : "Leave",
                        (d, w) -> navigateAwayFromInventory())
                .setNegativeButton(isArabic ? "البقاء" : "Stay", null)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .show();
    }

    private void navigateAwayFromInventory() {
        try {
            try {
                if (isScanning) {
                    Log.d(TAG, "⏹️ Stopping scan before leaving");
                    stopScanning();
                }
            } catch (Exception ignored) {}

            try {
                if (scannedItemsDisplayList != null
                        && !scannedItemsDisplayList.isEmpty()
                        && sessionManager != null) {
                    sessionManager.saveSession(
                            expectedCount,
                            scanStartTime,
                            selectedCategoryFilter,
                            scannedItemsDisplayList);
                    Log.d(TAG, "💾 Session saved before leaving");
                }
            } catch (Exception ignored) {}

            try {
                if (getActivity() instanceof UHFMainActivity) {
                    ((UHFMainActivity) getActivity()).setNavigationLocked(false);
                }
            } catch (Exception ignored) {}

            if (getActivity() instanceof UHFMainActivity) {
                UHFMainActivity activity = (UHFMainActivity) getActivity();

                if (activity.getNavigationManager() != null) {
                    activity.getNavigationManager().goToDashboard();
                    Log.d(TAG, "✅ Navigated to Dashboard via NavigationManager");
                } else {
                    activity.loadFragmentDirectly(
                            new MiraDashboardFragment(),
                            com.example.uhf.navigation.NavigationManager.TAG_DASHBOARD);
                    Log.d(TAG, "✅ Navigated to Dashboard (fallback)");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "navigateAwayFromInventory error", e);
        }
    }

    private void showExitWarning(Runnable onConfirm) {
        if (!isAdded() || isFragmentDestroyed) return;

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "⚠️ تحذير مهم" : "⚠️ Important Warning")
                .setMessage(isArabic ?
                        "الجرد ما زال قيد التشغيل!\n\n" +
                        "إذا قمت بتغيير الواجهة، سيتوقف الجرد وستفقد البيانات غير المحفوظة.\n\n" +
                        "هل تريد إيقاف الجرد والمتابعة؟" :
                        "Inventory is still running!\n\n" +
                        "If you change the screen, the inventory will stop and unsaved data will be lost.\n\n" +
                        "Do you want to stop and continue?")
                .setPositiveButton(isArabic ? "إيقاف والمتابعة" : "Stop and Continue",
                        (dialog, which) -> {
                            if (onConfirm != null) onConfirm.run();
                        })
                .setNegativeButton(isArabic ? "البقاء في الجرد" : "Stay in Inventory",
                        (dialog, which) -> dialog.dismiss())
                .setCancelable(false)
                .show();
    }

    // ==================== Permissions ====================

    private boolean canEditInventory() {
        try {
            if (permissionManager == null) return true;
            return permissionManager.canEdit("inventory") || permissionManager.isManager();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean canViewInventory() {
        try {
            if (permissionManager == null) return true;
            return permissionManager.canView("inventory") || permissionManager.isManager();
        } catch (Exception e) {
            return false;
        }
    }

    private void applyPermissions() {
        if (permissionManager == null) return;

        boolean canView = canViewInventory();

        if (!canView && tvInventoryStatus != null) {
            tvInventoryStatus.setText(isArabic ? "⛔ غير مصرح لك بالوصول"
                                               : "⛔ Access denied");
        }
    }

    private void applyLanguage() {
        if (btnMainToggleScan != null) {
            if (isScanning) {
                btnMainToggleScan.setText(isArabic ? "⏹️ إيقاف الجرد" : "⏹️ Stop Scan");
            } else {
                btnMainToggleScan.setText(isArabic ? "▶️ بدء جرد RFID" : "▶️ Start RFID Inventory");
            }
        }
        if (btnGenerateReport != null)
            btnGenerateReport.setText(isArabic ? "📋 تقرير" : "📋 Report");
        if (btnArchiveReports != null)
            btnArchiveReports.setText(isArabic ? "📂 الأرشيف" : "📂 Archive");
        if (btnPowerControl != null)
            btnPowerControl.setText("⚡ " + currentPowerDbm + " dBm");
        if (btnCommitSession != null) {
            String currentText = btnCommitSession.getText().toString();
            if (!currentText.contains("✅") && !currentText.contains("Committed")) {
                btnCommitSession.setText(isArabic ? "اعتماد" : "Commit");
            }
        }
    }

    private void showAccessDenied() {
    if (!isAdded() || isFragmentDestroyed) return;
    showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access denied");
    // ✅ استخدم MiraFeedback بدل SoundManager
    if (scanSession != null) scanSession.notifyError();
}

    // ==================== RFID Trigger (✅ إصلاح رئيسي) ====================

    // ==================== RFID Trigger (v4.1 — Dual Mode) ====================

@Override
public boolean myOnKeyDwon(int keyCode, KeyEvent event) {
    boolean isTrigger = (keyCode == KEYCODE_RFID_TRIGGER_1
            || keyCode == KEYCODE_RFID_TRIGGER_2
            || keyCode == KEYCODE_RFID_TRIGGER_3);

    if (!isTrigger) return false;

    // ═══════════════════════════════════════════════════════════
    //  اقرأ وضع الزناد من الإعدادات
    // ═══════════════════════════════════════════════════════════
    int triggerMode = MiraReaderSettings.TRIGGER_MODE_HOLD;
    try {
        MiraReaderSettings settings = MiraReaderSettings.getInstance(requireContext());
        if (settings != null) triggerMode = settings.getTriggerMode();
    } catch (Exception ignored) {}

    // ═══════════════════════════════════════════════════════════
    //  ACTION_DOWN
    // ═══════════════════════════════════════════════════════════
    if (event.getAction() == KeyEvent.ACTION_DOWN
            && event.getRepeatCount() == 0) {

        if (!canEditInventory()) {
            showAccessDenied();
            return true;
        }

        // تأكيد لمسي فوري
        if (feedbackHelper != null) {
            try {
                feedbackHelper.hapticFeedback(
                        isScanning ? SoundManager.FeedbackType.ERROR
                                   : SoundManager.FeedbackType.KNOWN_ITEM);
            } catch (Exception ignored) {}
        }

        if (triggerMode == MiraReaderSettings.TRIGGER_MODE_TOGGLE) {
            // ✅ [Toggle] ضغطة تبدّل الحالة
            if (isScanning) {
                Log.i(TAG, "🎯 Trigger [TOGGLE]: pause");
                pauseScanning();
            } else {
                Log.i(TAG, "🎯 Trigger [TOGGLE]: start");
                startScanning();
            }
        } else {
            // ✅ [Hold] ابدأ فقط إذا لم يكن يعمل
            if (!isScanning) {
                Log.i(TAG, "🎯 Trigger [HOLD]: start");
                startScanning();
            }
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════════
    //  ACTION_UP
    // ═══════════════════════════════════════════════════════════
    if (event.getAction() == KeyEvent.ACTION_UP) {
        if (triggerMode == MiraReaderSettings.TRIGGER_MODE_HOLD) {
            // ✅ [Hold] أوقف عند رفع الإصبع
            if (isScanning) {
                Log.i(TAG, "🎯 Trigger [HOLD]: stop");
                stopScanning();
            }
        }
        // ✅ [Toggle] لا تفعل شيئاً — الحدث استُهلك في ACTION_DOWN
        return true;
    }

    return false;
}

    // ================================================================
    //  ScanController.ScanCallback Implementation
    // ================================================================
@Override
public void onScanStarted() {
    Log.d(TAG, "📡 onScanStarted");

    mainHandler.post(() -> {
        if (!isAdded() || isFragmentDestroyed) return;

        // ✅ مرّر الحالة الحقيقية بدل false المُثبّتة
        scanSession.onScanStarted(nextScanIsResume);
        nextScanIsResume = false;  // أعِد التعيين بعد الاستخدام

        isScanning = true;
        updateMainToggleButton(true);
        animateMainButton();

            if (animationHelper != null && statusBar != null) {
                animationHelper.updateStatusBarBackground(statusBar, true, true);
            }

            if (tvInventoryStatus != null) {
                tvInventoryStatus.setText(isArabic ? "📡 جاري الجرد..." : "📡 Scanning...");
            }

            if (tvElapsedTime != null) {
                tvElapsedTime.setText("00:00:00");
            }

            if (feedbackHelper != null) {
                try {
                    feedbackHelper.hapticFeedback(SoundManager.FeedbackType.KNOWN_ITEM);
                } catch (Exception ignored) {}
            }
            scanStartTime = SystemClock.elapsedRealtime();
            mainHandler.post(timerRunnable);

            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).setNavigationLocked(true);
            }

            updateButtonsState();
        });
    }

    @Override
public void onScanStopped() {
    Log.d(TAG, "📡 onScanStopped");
    
    mainHandler.post(() -> {
        if (!isAdded() || isFragmentDestroyed) return;
        
        // ✅ انقلها هنا
        scanSession.onScanStopped();
        
        isScanning = false;
        updateMainToggleButton(false);
        animateMainButton();

            if (animationHelper != null && statusBar != null) {
                boolean isConnected = false;
                try {
                    com.example.uhf.MiraRfid.RFIDManager rfidManager =
                            com.example.uhf.MiraRfid.RFIDManager.getInstance(requireContext());
                    isConnected = rfidManager != null && rfidManager.isReady();
                } catch (Exception ignored) {}
                animationHelper.updateStatusBarBackground(statusBar, isConnected, false);
            }

            if (feedbackHelper != null) feedbackHelper.resetDebounce();

            if (tvInventoryStatus != null) {
                tvInventoryStatus.setText(isArabic
                        ? "📡 تم إيقاف الجرد"
                        : "📡 Scan stopped");
            }

            // ❌ لا توست مزعج — تم حذفه

            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).setNavigationLocked(false);
            }
            updateUI();
            syncOfflineTransactions();
            updateButtonsState();
        });
    }

    // ✅ إصلاح: onTagRead بدون posting مزدوج
    @Override
public void onTagRead(String normalizedEpc, int rssi) {
    // فلتر الفئة
    if (selectedCategoryFilter != null && !selectedCategoryFilter.isEmpty()) {
        if (!catalogEpcs.contains(normalizedEpc)) return;
    }

    // ✅ قرار موحّد من Controller
    MiraScanSessionController.Decision d = scanSession.evaluateTag(normalizedEpc);

    switch (d) {
        case REJECT_SESSION_OFF:
            return;

        case REJECT_LIMIT:
            scanSession.confirmLimitReached();
            return;

        case ACCEPT_DUPLICATE:
            scanSession.confirmTagDuplicate();
            return;
case ACCEPT_NEW:
    if (Looper.myLooper() == Looper.getMainLooper()) {
        processTagReadDirect(normalizedEpc, rssi);
    } else {
        mainHandler.post(() -> processTagReadDirect(normalizedEpc, rssi));
    }
    return;
    }
}
    
@Override
public void onDuplicateTag(String epc) {
    // ✅ v4.0 — الفلتر الجديد في MiraScanSessionController يعالج المكررات
    //             قبل وصولها هنا. لا حاجة لإجراء إضافي.
}
    /**
     * ✅ معالجة مباشرة (بدون wrappers)
     */
        private void processTagReadDirect(String epc, int rssi) {
    if (!isAdded() || isFragmentDestroyed || modernAdapter == null) return;

    InventoryItem catalogItem = catalogManager != null
            ? catalogManager.getItemByEpc(epc) : null;

    addScannedItemToDisplay(epc, rssi, catalogItem);
    updateLastScan(epc, rssi);
    scheduleUiUpdate();

    // ✅ [v1.2] الصوت مع تمييز المعروف/المجهول
    if (scanSession != null) {
        boolean isKnown = catalogEpcs.contains(epc);
        scanSession.confirmTagAccepted(isKnown);
        scanSession.scheduleSingleModeStop();
    }
}

    @Override
public void onError(String error) {
    Log.e(TAG, "📡 RFID Error: " + error);
    mainHandler.post(() -> {
        if (!isAdded() || isFragmentDestroyed) return;
        showToast((isArabic ? "⚠️ " : "⚠️ ") + error);
        
        // ✅ أعِد الزر لحالته
        isScanning = false;
        updateMainToggleButton(false);
    });
}

    @Override
    public void onConnectionChanged(boolean isConnected) {
        Log.i(TAG, "📡 RFID Connection: " + isConnected);
        mainHandler.post(() -> {
            if (!isAdded() || isFragmentDestroyed) return;

            if (tvInventoryStatus != null) {
                tvInventoryStatus.setText(isConnected
                        ? (isArabic ? "📡 متصل" : "📡 Connected")
                        : (isArabic ? "📡 غير متصل" : "📡 Disconnected"));
            }

            if (statusDot != null) {
                try {
                    statusDot.setBackgroundResource(isConnected
                            ? R.drawable.bg_status_dot_green
                            : R.drawable.bg_status_dot_red);
                } catch (Exception ignored) {}
            }

            

            if (animationHelper != null && statusBar != null) {
                animationHelper.updateStatusBarBackground(
                        statusBar, isConnected, isScanning);
            }
        });
    }

    @Override
    public void onTagDebounced(String epc) {
        // لا حاجة
    }

    @Override
    public void onExpectedCountChanged(int count) {
        mainHandler.post(() -> {
            if (!isAdded() || isFragmentDestroyed) return;
            expectedCount = count;
            if (tvExpectedCount != null) {
                tvExpectedCount.setText(String.valueOf(count));
            }
        });
    }

    // ================================================================
    //  DialogsManager.DialogsCallback Implementation
    // ================================================================

    @Override
    public void onPowerChanged(int newPowerDbm) {
        currentPowerDbm = newPowerDbm;
        if (scanController != null) {
            scanController.setCurrentPowerDbm(newPowerDbm);
        }
        applyAntennaPower(newPowerDbm);

        if (btnPowerControl != null) {
            btnPowerControl.setText("⚡ " + newPowerDbm + " dBm");
        }
        if (btnPowerControlCompact != null) {
            btnPowerControlCompact.setText("⚡ " + newPowerDbm + " dBm");
        }
    }

    @Override
    public void onSessionReset() {
        confirmResetSession();
    }

    @Override
    public void onBuzzerTest() {}

    @Override
    public void onCommitConfirmed() {
        commitInventoryToServer();
    }

    @Override
    public void onPrintCurrentReport() {
        printCurrentReport();
    }

    @Override
    public void onPrintSingleItem(ScannedItemDisplay item) {
        printSingleItemLabel(item);
    }

    @Override
    public void onItemStatusChange(ScannedItemDisplay item, String newStatus) {
        updateItemStatus(item != null ? item.epc : null, newStatus);
    }

    @Override
    public void onItemDamageRegister(ScannedItemDisplay item, String damageType) {
        registerItemDamage(item != null ? item.epc : null, damageType);
    }

    @Override
    public void onShowArchive() {
        showArchiveReports();
    }

    @Override
    public void onShowSnapshots() {
        openSnapshotsScreen();
    }

    // ================================================================
    //  Scan Lifecycle (✅ إصلاح جوهري)
    // ================================================================

    private void startScanning() {
    if (!canEditInventory()) {
        showAccessDenied();
        return;
    }
    if (isScanning || isFragmentDestroyed) return;

    Log.d(TAG, "▶️ startScanning (STAGE-1)");

    if (btnGenerateReport != null) {
        btnGenerateReport.setEnabled(true);
        btnGenerateReport.setAlpha(1.0f);
    }
    if (btnCommitSession != null) {
        btnCommitSession.setText(isArabic ? "اعتماد" : "Commit");
        btnCommitSession.setAlpha(1.0f);
    }

    // ✅ [v4.0] احسب النوع الحقيقي قبل أي تعديل على الحالة
    nextScanIsResume = isPaused || hasUncommittedSession;

    if (!nextScanIsResume) {
        // جلسة جديدة تماماً
        Log.i(TAG, "🆕 New session — clearing lists");
        clearAllLists();
        sessionElapsedBeforePause = 0;
    } else {
        // استئناف جلسة موجودة
        Log.i(TAG, "🔄 Resuming session — preserving " +
                scannedItemsDisplayList.size() + " items");
    }

    // ✅ الآن عدّل الحالة بعد حساب nextScanIsResume
    isPaused = false;
    hasUncommittedSession = true;

    Log.d(TAG, "▶️ nextScanIsResume = " + nextScanIsResume);

    if (scanController == null) {
        showFatalErrorDialog(
                isArabic ? "⚠️ مدير المسح غير جاهز" : "⚠️ Scan controller not ready",
                isArabic ? "حاول إعادة فتح الشاشة" : "Try reopening the screen");
        return;
    }

    // ✅ ابدأ القارئ
    final Map<String, InventoryItem> emptyCatalog = new ConcurrentHashMap<>();

    boolean started = scanController.startScanning(emptyCatalog);
    if (!started) {
        showFatalErrorDialog(
                isArabic ? "❌ فشل بدء المسح" : "❌ Failed to start scanning",
                isArabic ? "تحقق من اتصال القارئ" : "Check reader connection");
        return;
    }

    // ✅ حمّل الكتالوج في الخلفية فقط لو لم يكن موجوداً
    if (catalogEpcs.isEmpty()) {
        loadCatalogInBackground();
    }
}

    /**
     * ✅ تحميل الكتالوج في الخلفية (لا يمنع المسح)
     */
    /**
 * ✅ تحميل الكتالوج في الخلفية (لا يمنع المسح)
 * يُستدعى من startScanning() بعد أن يكون المسح قد بدأ بالفعل
 */
private void loadCatalogInBackground() {
    if (!isAdded() || isFragmentDestroyed || catalogManager == null) return;

    backgroundExecutor.execute(() -> {
        if (isFragmentDestroyed) return;

        catalogManager.fetchCatalog(apiClient, branchesManager,
                new InventoryCatalogManager.CatalogCallback() {
                    @Override
                    public void onCatalogLoaded(Map<String, InventoryItem> catalog) {
                        mainHandler.post(() -> {
                            if (!isAdded() || isFragmentDestroyed) return;

                            // ✅ 1) احفظ الـ EPCs في الذاكرة
                            catalogEpcs.clear();
                            if (catalog != null) {
                                catalogEpcs.addAll(catalog.keySet());
                            }

                            // ✅ 2) حدّث العدد المتوقع
                            expectedCount = catalog != null ? catalog.size() : 0;

                            if (tvExpectedCount != null) {
                                tvExpectedCount.setText(
                                        expectedCount > 0 ? String.valueOf(expectedCount) : "—");
                            }
                            if (progressScanned != null) {
                                progressScanned.setMax(100);
                            }

                            // ✅ 3) أعِد تقييم المواد الممسوحة (بدون updateCatalog)
                            //    reclassifyScannedItems تقرأ من catalogManager.getItemByEpc()
                            reclassifyScannedItems();

                            Log.i(TAG, "✅ Catalog loaded in background: "
                                    + expectedCount + " items");
                        });
                    }

                    @Override
                    public void onError(String error) {
                        Log.w(TAG, "⚠️ Catalog load failed (blind mode active): " + error);
                        mainHandler.post(() -> {
                            if (!isAdded() || isFragmentDestroyed) return;

                            // ✅ الجرد يعمل أصلاً (blind) — لا نوقف شيء
                            if (tvInventoryStatus != null) {
                                tvInventoryStatus.setText(isArabic
                                        ? "📡 جرد بدون كتالوج"
                                        : "📡 Blind inventory (no catalog)");
                            }
                        });
                    }
                });
    });
}

/**
 * ✅ [Stage-1] نافذة الاستئناف الذكية
 */
private void showResumeDialog(int itemCount, int expected, long timeAgoMs) {
    if (!isAdded() || isFragmentDestroyed) return;
    if (itemCount < 1) return;

    String timeAgo;
    if (timeAgoMs < 60 * 1000) {
        timeAgo = isArabic ? "قبل لحظات" : "just now";
    } else if (timeAgoMs < 60 * 60 * 1000) {
        timeAgo = isArabic
                ? "قبل " + (timeAgoMs / 60000) + " دقيقة"
                : (timeAgoMs / 60000) + " min ago";
    } else {
        timeAgo = isArabic
                ? "قبل " + (timeAgoMs / 3600000) + " ساعة"
                : (timeAgoMs / 3600000) + " hours ago";
    }

    String message = isArabic
            ? "📂 وجدنا جلسة سابقة (" + timeAgo + ")\n\n"
            + "• القطع: " + itemCount + "\n"
            + "• المتوقع: " + (expected > 0 ? expected : "—") + "\n\n"
            + "هل تريد الاستئناف؟"
            : "📂 Previous session (" + timeAgo + ")\n\n"
            + "• Items: " + itemCount + "\n"
            + "• Expected: " + (expected > 0 ? expected : "—") + "\n\n"
            + "Resume?";

    new MaterialAlertDialogBuilder(requireContext())
            .setTitle(isArabic ? "🔄 جلسة سابقة" : "🔄 Previous Session")
            .setMessage(message)
            .setPositiveButton(isArabic ? "🔄 استئناف" : "🔄 Resume", (d, w) -> {
                hasUncommittedSession = true;
                isPaused = true;
                switchToActiveState(false);
                updateButtonsState();
            })
            .setNegativeButton(isArabic ? "🆕 جديدة" : "🆕 New", (d, w) -> {
    clearAllLists();

    // ✅ أعِد تعيين الحالة كاملة
    hasUncommittedSession = false;
    isPaused = false;
    nextScanIsResume = false;
    expectedCount = 0;
    sessionElapsedBeforePause = 0;

    if (tvExpectedCount != null) tvExpectedCount.setText("0");
    if (sessionManager != null) sessionManager.clearSession();
})
            .setCancelable(false)
            .show();
}

    /**
     * ✅ إيقاف فوري
     */
    /**
 * ✅ [Stage-1] إيقاف مؤقت — لا يمسح البيانات
 *             يمكن الاستئناف لاحقاً
 */
 public void pauseScanning() {
    if (!isScanning) return;

    // ✅ احفظ الوقت المنقضي قبل الإيقاف
    if (scanStartTime > 0) {
        sessionElapsedBeforePause += 
                (SystemClock.elapsedRealtime() - scanStartTime) / 1000;
    }

    Log.d(TAG, "⏸️ pauseScanning (STAGE-1)");

    if (scanController != null) {
        scanController.stopScanning();
    }

    isScanning = false;
    isPaused = true;
    hasUncommittedSession = true;

    mainHandler.post(() -> {
        if (!isAdded() || isFragmentDestroyed) return;

        updateMainToggleButton(false);
        animateMainButton();

        if (tvInventoryStatus != null) {
            tvInventoryStatus.setText(isArabic
                    ? "⏸️ الجرد موقوف مؤقتاً — يمكنك الاستئناف"
                    : "⏸️ Inventory paused — you can resume");
        }

        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).setNavigationLocked(false);
        }

        updateButtonsState();
    });
}

/**
 * ✅ [Stage-1] إيقاف نهائي — يُبقي البيانات لكن لا يُتيح الاستئناف
 *             (يُستدعى فقط عند الخروج من الشاشة أو الاعتماد)
 */
public void stopScanning() {
    if (!isScanning) return;

    Log.d(TAG, "⏹️ stopScanning (FINAL)");

    if (scanController != null) {
        scanController.stopScanning();
    }

    isScanning = false;

    mainHandler.post(() -> {
        if (!isAdded() || isFragmentDestroyed) return;

        updateMainToggleButton(false);
        animateMainButton();

        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).setNavigationLocked(false);
        }

        updateButtonsState();
    });
}

    private void startBlindInventory() {
        if (!canEditInventory()) {
            showAccessDenied();
            return;
        }
        if (isScanning || isFragmentDestroyed) return;

        clearAllLists();

        expectedCount = 0;
        catalogEpcs.clear();

        if (tvExpectedCount != null) tvExpectedCount.setText("—");
        if (tvInventoryStatus != null) {
            tvInventoryStatus.setText(isArabic
                    ? "⚡ جرد أعمى - بدون توقعات"
                    : "⚡ Blind inventory - No expectations");
        }

        if (scanController != null) {
            scanController.startScanning(new ConcurrentHashMap<>());
        }
    }

    private void startCategoryInventory(String category) {
        if (isScanning || isFragmentDestroyed) return;

        clearAllLists();

        showToast(isArabic
                ? "🔄 جاري تحميل كتالوج " + category + "..."
                : "🔄 Loading catalog for " + category + "...");

        final String selectedCategory = category;

        backgroundExecutor.execute(() -> {
            if (isFragmentDestroyed || catalogManager == null) return;

            final Map<String, InventoryItem>[] holder = new Map[1];
            final java.util.concurrent.CountDownLatch latch =
                    new java.util.concurrent.CountDownLatch(1);

            catalogManager.fetchCatalog(apiClient, branchesManager,
                    new InventoryCatalogManager.CatalogCallback() {
                        @Override
                        public void onCatalogLoaded(Map<String, InventoryItem> catalog) {
                            holder[0] = catalog;
                            latch.countDown();
                        }
                        @Override
                        public void onError(String error) {
                            holder[0] = new ConcurrentHashMap<>();
                            latch.countDown();
                        }
                    });

            try { latch.await(30, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            final Map<String, InventoryItem> fullCatalog =
                    holder[0] != null ? holder[0] : new ConcurrentHashMap<>();
            final Map<String, InventoryItem> filtered =
                    catalogManager.filterByCategory(fullCatalog, selectedCategory);

            mainHandler.post(() -> {
                if (!isAdded() || isFragmentDestroyed) return;

                catalogEpcs.clear();
                catalogEpcs.addAll(filtered.keySet());

                expectedCount = filtered.size();

                if (tvExpectedCount != null) {
                    tvExpectedCount.setText(String.valueOf(expectedCount));
                }

                if (tvInventoryStatus != null) {
                    tvInventoryStatus.setText(isArabic
                            ? "🎯 جرد فئوي: " + selectedCategory + " (" + expectedCount + ")"
                            : "🎯 Category: " + selectedCategory + " (" + expectedCount + ")");
                }

                if (expectedCount == 0) {
                    showToast(isArabic ? "⚠️ لا توجد قطع في هذه الفئة"
                                       : "⚠️ No items in this category");
                }

                if (scanController != null) {
                    scanController.startScanning(filtered);
                }
            });
        });
    }
private void clearAllLists() {
    try {
        synchronized (scannedItemsDisplayList) {
            scannedItemsDisplayList.clear();
        }
        aggregateTotalWeight = 0;
        aggregateTotalValue = 0;

        // ✅ [جديد] أعِد تعيين حالة الاحتفال
        hasCelebrated = false;

            if (modernAdapter != null) {
                modernAdapter.submitList(new ArrayList<>());
                modernAdapter.resetAnimationTracker();
            }

            if (rvInventoryItems != null) {
                rvInventoryItems.scrollToPosition(0);
            }

            if (tvScannedCount != null) tvScannedCount.setText("0");
            if (tvItemCount != null) tvItemCount.setText("0");
            if (tvVariance != null) {
                tvVariance.setText("—");
                tvVariance.setTextColor(ContextCompat.getColor(requireContext(),
                        R.color.text_secondary));
            }
            if (tvAccuracy != null) tvAccuracy.setText("—");
            if (progressScanned != null) progressScanned.setProgress(0);
            if (tvProgressPercent != null) tvProgressPercent.setText("0%");
            if (tvTotalWeight != null) tvTotalWeight.setText("0.00 g");
            if (tvTotalValue != null) tvTotalValue.setText("$0.00");
            if (tvEmptyState != null) tvEmptyState.setVisibility(View.VISIBLE);

            if (feedbackHelper != null) feedbackHelper.resetDebounce();

            lastScannedForAnimation = 0;

            if (tvElapsedTime != null) tvElapsedTime.setText("00:00:00");
            if (tvRssi != null) tvRssi.setText("📶 —");
            scanStartTime = 0;

            if (tvLastScanTitle != null) {
                tvLastScanTitle.setText(isArabic ? "لا توجد مسوحات" : "No scans");
            }
            if (tvLastScanEpc != null) tvLastScanEpc.setText("—");
            if (tvLastScanKarat != null) tvLastScanKarat.setText("—");
            if (tvLastScanWeight != null) tvLastScanWeight.setText("0.00g");
            if (tvLastScanRssi != null) tvLastScanRssi.setText("📶 —");
            if (tvLastScanStatus != null) {
                tvLastScanStatus.setText(isArabic ? "بانتظار المسح" : "Waiting");
                tvLastScanStatus.setTextColor(0xFF6B7280);
            }

            if (btnCommitSession != null) {
                btnCommitSession.setText(isArabic ? "اعتماد" : "Commit");
                btnCommitSession.setAlpha(1.0f);
            }

            updateButtonsState();
        } catch (Exception e) {
            Log.e(TAG, "clearAllLists error", e);
        }
    }

    private void updateMainToggleButton(boolean scanning) {
        if (btnMainToggleScan == null) return;

        try {
            if (scanning) {
                btnMainToggleScan.setText(isArabic ? "⏹️ إيقاف الجرد" : "⏹️ Stop Scan");

                try {
                    btnMainToggleScan.setIcon(requireContext().getDrawable(
                            android.R.drawable.ic_media_pause));
                } catch (Exception ignored) {}

                try {
                    int redColor = ContextCompat.getColor(requireContext(),
                            R.color.inventory_stop_red);
                    btnMainToggleScan.setBackgroundTintList(
                            android.content.res.ColorStateList.valueOf(redColor));
                } catch (Exception e) {
                    Log.w(TAG, "setBackgroundTintList failed", e);
                }

            } else {
                btnMainToggleScan.setText(isArabic ? "▶️ بدء جرد RFID" : "▶️ Start RFID Scan");

                try {
                    btnMainToggleScan.setIcon(requireContext().getDrawable(
                            android.R.drawable.ic_media_play));
                } catch (Exception ignored) {}

                try {
                    int primaryColor = ContextCompat.getColor(requireContext(),
                            R.color.colorPrimary);
                    btnMainToggleScan.setBackgroundTintList(
                            android.content.res.ColorStateList.valueOf(primaryColor));
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.e(TAG, "updateMainToggleButton error", e);
        }
    }

    private void animateMainButton() {
        if (btnMainToggleScan == null) return;
        try {
            android.animation.ObjectAnimator scaleX =
                    android.animation.ObjectAnimator.ofFloat(
                            btnMainToggleScan, "scaleX", 1f, 1.05f, 1f);
            android.animation.ObjectAnimator scaleY =
                    android.animation.ObjectAnimator.ofFloat(
                            btnMainToggleScan, "scaleY", 1f, 1.05f, 1f);
            scaleX.setDuration(300);
            scaleY.setDuration(300);
            scaleX.start();
            scaleY.start();
        } catch (Exception ignored) {}
    }

    private void showFatalErrorDialog(String title, String message) {
        if (!isAdded() || isFragmentDestroyed) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(isArabic ? "حسناً" : "OK", null)
                .setCancelable(false)
                .show();
    }

    // ================================================================
    //  Scanned Items Handling
    // ================================================================

    private void addScannedItemToDisplay(String epc, int rssi, InventoryItem item) {
    if (!isAdded() || isFragmentDestroyed || modernAdapter == null) return;
    if (Looper.myLooper() != Looper.getMainLooper()) {
        mainHandler.post(() -> addScannedItemToDisplay(epc, rssi, item));
        return;
    }

    ScannedItemDisplay currentDisplay = null;
    SoundManager.FeedbackType feedbackType = SoundManager.FeedbackType.UNKNOWN_ITEM;
    boolean isDuplicate = false;

    boolean wasEmpty = scannedItemsDisplayList.isEmpty();

    // ═══════════════════════════════════════════════════════
    //  1) ابحث عن القطعة في القائمة
    // ═══════════════════════════════════════════════════════
    for (ScannedItemDisplay display : scannedItemsDisplayList) {
        if (display != null && epc.equals(display.epc)) {
            display.count++;
            display.rssi = rssi;
            display.time = getCurrentTimeString();

            // ✅ لا نلمس المجاميع هنا — سنحسبها لاحقاً دفعة واحدة
            currentDisplay = display;
            isDuplicate = true;
            break;
        }
    }

    // ═══════════════════════════════════════════════════════
    //  2) إذا كانت قطعة جديدة → أضفها
    // ═══════════════════════════════════════════════════════
    if (!isDuplicate) {
        boolean isKnown = catalogEpcs.contains(epc);
        feedbackType = isKnown
                ? SoundManager.FeedbackType.KNOWN_ITEM
                : SoundManager.FeedbackType.UNKNOWN_ITEM;

        String title = isArabic ? "قطعة غير معروفة" : "Unknown item";
        String karat = "—";
        double weight = 0;
        double price = 0;

        InventoryItem catalogItem = catalogManager != null
                ? catalogManager.getItemByEpc(epc) : null;

        if (catalogItem != null) {
            title = catalogItem.getTitle() != null && !catalogItem.getTitle().isEmpty()
                    ? catalogItem.getTitle()
                    : (isArabic ? "قطعة ذهبية" : "Gold item");
            karat = catalogItem.getKarat() != null && !catalogItem.getKarat().isEmpty()
                    ? catalogItem.getKarat()
                    : "—";
            weight = catalogItem.getWeight();
            price = catalogItem.getPrice();
        } else if (item != null) {
            title = item.getTitle() != null && !item.getTitle().isEmpty()
                    ? item.getTitle()
                    : (isArabic ? "قطعة ذهبية" : "Gold item");
            karat = item.getKarat() != null && !item.getKarat().isEmpty()
                    ? item.getKarat()
                    : "—";
            weight = item.getWeight();
            price = item.getPrice();
        } else if (isKnown) {
            title = isArabic ? "مادة مسجلة بالكتالوج"
                             : "Catalog registered item";
        }

        ScannedItemDisplay display = new ScannedItemDisplay(
                epc, title, karat, 1, rssi, getCurrentTimeString());
        display.weight = weight;
        display.price = price;

        synchronized (scannedItemsDisplayList) {
            scannedItemsDisplayList.add(0, display);
        }
        currentDisplay = display;

        if (rvInventoryItems != null) {
            rvInventoryItems.scrollToPosition(0);
        }

        if (tvEmptyState != null) {
            tvEmptyState.setVisibility(View.GONE);
        }
    } else {
        feedbackType = SoundManager.FeedbackType.DUPLICATE_ITEM;
    }

    // ═══════════════════════════════════════════════════════
    //  3) ✅ الحل: احسب المجاميع دفعة واحدة (source of truth)
    // ═══════════════════════════════════════════════════════
    recalculateAggregates();
    updateTotalWeightAndValue();

    // ═══════════════════════════════════════════════════════
    //  4) الانتقال إلى الحالة النشطة
    // ═══════════════════════════════════════════════════════
    if (wasEmpty && !scannedItemsDisplayList.isEmpty()) {
        switchToActiveState(true);
    }

    // ═══════════════════════════════════════════════════════
    //  5) الأصوات والاهتزاز
    // ═══════════════════════════════════════════════════════
    
    // ═══════════════════════════════════════════════════════
    //  6) بطاقة آخر مسح
    // ═══════════════════════════════════════════════════════
    if (currentDisplay != null) {
        updateLastScanCard(currentDisplay, isDuplicate);
    }

    // ═══════════════════════════════════════════════════════
    //  7) تحديث القائمة (فقط للقطعة الجديدة)
    // ═══════════════════════════════════════════════════════
    if (!isDuplicate) {
        updateAdapterList();
    }
}

    private void updateAdapterList() {
        if (modernAdapter == null || isFragmentDestroyed || filterManager == null) return;

        List<ScannedItemDisplay> filtered = filterManager.applyFilter(
                scannedItemsDisplayList, catalogEpcs);

        modernAdapter.submitList(filtered);
    }

    private void filterItems(String query) {
        try {
            if (filterManager == null) return;
            filterManager.setSearchQuery(query);
            updateAdapterList();
        } catch (Exception e) {
            Log.e(TAG, "filterItems error", e);
        }
    }

    private void updateLastScan(String epc, int rssi) {
        if (tvRssi != null) {
            tvRssi.setText(String.format(Locale.US, "📶 %d", rssi));
        }
    }

    private void updateLastScanCard(ScannedItemDisplay item, boolean isDuplicate) {
        if (item == null || tvLastScanTitle == null) return;

        try {
            tvLastScanTitle.setText(item.title != null ? item.title : "—");

            if (tvLastScanEpc != null) {
                String epc = item.epc != null ? item.epc : "";
                if (!epc.isEmpty()) {
                    tvLastScanEpc.setText(formatEpcShort(epc));
                    tvLastScanEpc.setVisibility(View.VISIBLE);
                } else {
                    tvLastScanEpc.setVisibility(View.GONE);
                }
            }

            if (tvLastScanPrice != null) {
                if (item.price > 0) {
                    tvLastScanPrice.setText(String.format(Locale.US, "$%.0f", item.price));
                } else {
                    tvLastScanPrice.setText("—");
                }
            }

            if (tvLastScanWeight != null) {
                if (item.weight > 0) {
                    tvLastScanWeight.setText(String.format(Locale.US, "%.1fg", item.weight));
                } else {
                    tvLastScanWeight.setText("—");
                }
            }

            if (tvLastScanKarat != null) {
                tvLastScanKarat.setText(
                        item.karat != null && !item.karat.isEmpty() ? item.karat : "—");
            }

            if (tvLastScanRssi != null) {
                tvLastScanRssi.setText(String.format(Locale.US, "%d", item.rssi));
            }

            if (chipReadCount != null && tvLastScanReadCount != null) {
                if (item.count > 1) {
                    tvLastScanReadCount.setText(String.format(Locale.US, "× %d", item.count));
                    chipReadCount.setVisibility(View.VISIBLE);
                } else {
                    chipReadCount.setVisibility(View.GONE);
                }
            }

            if (rowSerialTime != null) {
                boolean hasSerial = false;
                boolean hasTime = false;

                if (tvLastScanSerial != null) {
                    String serial = getSerialDisplay(item);
                    if (serial != null && !serial.isEmpty()) {
                        tvLastScanSerial.setText(serial);
                        hasSerial = true;
                    }
                }

                if (tvLastScanTime != null && item.time != null && !item.time.isEmpty()) {
                    tvLastScanTime.setText("⏰ " + extractTimeShort(item.time));
                    hasTime = true;
                }

                rowSerialTime.setVisibility(
                        (hasSerial || hasTime) ? View.VISIBLE : View.GONE);
            }

            boolean isKnown = catalogEpcs.contains(item.epc);
            boolean hasCatalog = !catalogEpcs.isEmpty();

            if (tvLastScanStatus != null) {
                String statusText;
                int statusColor;

                if (isDuplicate) {
                    statusText = isArabic ? "🔁 مكرر" : "🔁 Duplicate";
                    statusColor = 0xFF3B82F6;
                } else if (isKnown) {
                    statusText = isArabic ? "✅ مطابق للمخزون" : "✅ In Catalog";
                    statusColor = 0xFF16A34A;
                } else if (hasCatalog) {
                    statusText = isArabic ? "🆕 غير متوقع" : "🆕 Unexpected";
                    statusColor = 0xFFF59E0B;
                } else {
                    statusText = isArabic ? "⚠️ غير معروف" : "⚠️ Unknown";
                    statusColor = 0xFFDC2626;
                }

                tvLastScanStatus.setText(statusText);
                tvLastScanStatus.setTextColor(statusColor);
            }

            if (lastScanCard != null) {
                android.animation.ObjectAnimator scaleX =
                        android.animation.ObjectAnimator.ofFloat(
                                lastScanCard, "scaleX", 0.98f, 1f);
                android.animation.ObjectAnimator scaleY =
                        android.animation.ObjectAnimator.ofFloat(
                                lastScanCard, "scaleY", 0.98f, 1f);
                scaleX.setDuration(200);
                scaleY.setDuration(200);
                scaleX.start();
                scaleY.start();
            }
        } catch (Exception e) {
            Log.e(TAG, "updateLastScanCard error", e);
        }
    }

    private String formatEpcShort(String epc) {
        if (epc == null || epc.isEmpty()) return "";
        if (epc.length() <= 16) return epc;
        return epc.substring(0, 8) + "…" + epc.substring(epc.length() - 6);
    }

    private String getSerialDisplay(ScannedItemDisplay item) {
        try {
            if (item == null) return null;

            InventoryItem catalogItem = catalogManager != null
                    ? catalogManager.getItemByEpc(item.epc) : null;

            if (catalogItem != null) {
                String serial = catalogItem.getSerial();
                if (serial != null && !serial.isEmpty()) {
                    return "SN: " + serial;
                }
                String gtin = catalogItem.getGtin();
                if (gtin != null && !gtin.isEmpty()) {
                    return "GTIN: " + gtin;
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractTimeShort(String timeStr) {
        if (timeStr == null || timeStr.isEmpty()) return "";
        try {
            String[] parts = timeStr.split(":");
            if (parts.length >= 2) {
                return parts[0] + ":" + parts[1];
            }
            return timeStr;
        } catch (Exception e) {
            return timeStr;
        }
    }

    /**
     * ✅ إعادة تصنيف المواد الممسوحة بعد وصول الكتالوج
     */
    private void reclassifyScannedItems() {
        if (!isAdded() || isFragmentDestroyed) return;
        if (scannedItemsDisplayList.isEmpty()) return;

        Log.i(TAG, "🔄 Reclassifying " + scannedItemsDisplayList.size()
                + " items after catalog load");

        synchronized (scannedItemsDisplayList) {
            for (ScannedItemDisplay item : scannedItemsDisplayList) {
                if (item == null) continue;

                InventoryItem catalogItem = catalogManager != null
                        ? catalogManager.getItemByEpc(item.epc) : null;

                if (catalogItem != null) {
                    item.title = catalogItem.getTitle() != null
                            && !catalogItem.getTitle().isEmpty()
                            ? catalogItem.getTitle() : item.title;
                    item.karat = catalogItem.getKarat() != null
                            && !catalogItem.getKarat().isEmpty()
                            ? catalogItem.getKarat() : item.karat;
                    item.weight = catalogItem.getWeight();
                    item.price = catalogItem.getPrice();
                }
            }
        }

        recalculateAggregates();
        updateTotalWeightAndValue();
        updateAdapterList();
        updateUI();
    }

    private void updateButtonsState() {
    if (!isAdded() || isFragmentDestroyed) return;

    int engineCount = (inventoryEngine != null) ? inventoryEngine.getUniqueCount() : 0;
    int listCount = (scannedItemsDisplayList != null) ? scannedItemsDisplayList.size() : 0;
    int scanned = Math.max(engineCount, listCount);

    boolean hasItems = scanned > 0;
    boolean canEdit = canEditInventory();
    boolean canView = canViewInventory();

    // ✅ زر Start/Pause
    if (btnMainToggleScan != null) {
        btnMainToggleScan.setEnabled(canEdit);
        btnMainToggleScan.setAlpha(canEdit ? 1.0f : 0.5f);
    }

    // ✅ [جديد] زر "إنهاء ومراجعة"
    if (btnFinishAndReview != null) {
        boolean visible = hasItems && !isScanning;
        btnFinishAndReview.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    // ✅ باقي الأزرار
    if (btnCommitSession != null) {
        boolean canCommit = hasItems && !isScanning && canEdit;
        btnCommitSession.setEnabled(canCommit);
        btnCommitSession.setAlpha(canCommit ? 1.0f : 0.5f);
    }

    if (btnGenerateReport != null) {
        boolean canReport = hasItems && !isScanning;
        btnGenerateReport.setEnabled(canReport);
        btnGenerateReport.setAlpha(canReport ? 1.0f : 0.5f);
    }

    if (btnOpenItemsList != null) {
        btnOpenItemsList.setEnabled(hasItems);
        btnOpenItemsList.setAlpha(hasItems ? 1.0f : 0.5f);
    }

    if (btnPowerControl != null) {
        btnPowerControl.setEnabled(!isScanning && canEdit);
        btnPowerControl.setAlpha((!isScanning && canEdit) ? 1.0f : 0.5f);
    }
    if (btnPowerControlCompact != null) {
        btnPowerControlCompact.setEnabled(!isScanning && canEdit);
        btnPowerControlCompact.setAlpha((!isScanning && canEdit) ? 1.0f : 0.5f);
    }
        if (btnArchiveReports != null) {
            boolean canArchive = !isScanning && canView;
            btnArchiveReports.setEnabled(canArchive);
            btnArchiveReports.setAlpha(canArchive ? 1.0f : 0.5f);
        }
        if (btnArchiveReportsCompact != null) {
            boolean canArchive = !isScanning && canView;
            btnArchiveReportsCompact.setEnabled(canArchive);
            btnArchiveReportsCompact.setAlpha(canArchive ? 1.0f : 0.5f);
        }

        if (btnTakeSnapshot != null) {
            boolean canSnapshot = hasItems && !isScanning;
            btnTakeSnapshot.setEnabled(canSnapshot);
            btnTakeSnapshot.setAlpha(canSnapshot ? 1.0f : 0.5f);
        }

        if (btnViewSnapshots != null) {
            btnViewSnapshots.setEnabled(!isScanning);
            btnViewSnapshots.setAlpha(isScanning ? 0.5f : 1.0f);
        }
    }

    // ================================================================
    //  UI Updates
    // ================================================================

    private final Runnable uiUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            pendingUiUpdate = false;
            updateUI();
            updateTotalWeightAndValue();
        }
    };

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isScanning || isFragmentDestroyed) return;
            updateElapsedTime();
            mainHandler.postDelayed(this, 1000);
        }
    };

    private void scheduleUiUpdate() {
        if (!pendingUiUpdate && !isFragmentDestroyed) {
            pendingUiUpdate = true;
            mainHandler.postDelayed(uiUpdateRunnable, getAdaptiveUiThrottle());
        }
    }

    private long getAdaptiveUiThrottle() {
        int count = scannedItemsDisplayList.size();
        if (count < ITEMS_COUNT_SMALL) return UI_UPDATE_THROTTLE_MIN_MS;
        if (count < ITEMS_COUNT_MEDIUM) return UI_UPDATE_THROTTLE_MEDIUM_MS;
        return UI_UPDATE_THROTTLE_MAX_MS;
    }

    private void updateUI() {
    if (!isAdded() || isFragmentDestroyed || inventoryEngine == null) return;

    int scanned = inventoryEngine.getUniqueCount();

    // ✅ عدد الممسوحات
    if (tvScannedCount != null) {
        String newText = String.valueOf(scanned);
        if (!newText.equals(tvScannedCount.getText().toString())) {
            if (animationHelper != null) {
                animationHelper.animateNumberChange(
                        tvScannedCount, lastScannedForAnimation, scanned);
            } else {
                tvScannedCount.setText(newText);
            }
        }
    }
    if (tvItemCount != null) tvItemCount.setText(String.valueOf(scanned));

    if (expectedCount > 0) {
        // ✅ التقدم
        int progress = Math.min((int) (((float) scanned / expectedCount) * 100), 100);

        if (progressScanned != null && animationHelper != null) {
            int prev = progressScanned.getProgress();
            if (progress != prev) {
                animationHelper.animateProgressBar(progressScanned, prev, progress);
            }
        } else if (progressScanned != null) {
            progressScanned.setProgress(progress);
        }

        if (tvProgressPercent != null && animationHelper != null) {
            animationHelper.animateProgressText(tvProgressPercent, progress);
        } else if (tvProgressPercent != null) {
            tvProgressPercent.setText(progress + "%");
        }

        // ✅ الدقة
        if (tvAccuracy != null) {
            tvAccuracy.setText(progress + "%");
            int color = progress >= 100
                    ? ContextCompat.getColor(requireContext(), R.color.success_green)
                    : progress >= 70
                    ? ContextCompat.getColor(requireContext(), R.color.warning_amber)
                    : ContextCompat.getColor(requireContext(), R.color.text_primary);
            tvAccuracy.setTextColor(color);
        }

        // ✅ الفارق
        if (tvVariance != null) {
            int variance = expectedCount - scanned;
            if (variance > 0) {
                tvVariance.setText("−" + variance);
                tvVariance.setTextColor(ContextCompat.getColor(requireContext(),
                        R.color.warning_amber));
            } else if (variance < 0) {
                tvVariance.setText("+" + Math.abs(variance));
                tvVariance.setTextColor(ContextCompat.getColor(requireContext(),
                        R.color.info_blue));
            } else {
                tvVariance.setText("0");
                tvVariance.setTextColor(ContextCompat.getColor(requireContext(),
                        R.color.success_green));
            }
        }

        // ✅ الاحتفال عند اكتمال العدد المتوقع — بدون إيقاف تلقائي
        if (scanned >= expectedCount && isScanning) {
            if (!hasCelebrated) {
                hasCelebrated = true;

                
                if (animationHelper != null) {
                    animationHelper.celebrateCompletion(
                            tvProgressPercent, progressScanned, tvScannedCount);
                }
                showToast(isArabic
                        ? "🎉 تم مسح كل المواد المتوقعة — يمكنك الاستمرار أو الإيقاف يدوياً"
                        : "🎉 All expected items scanned — continue or stop manually");
            }
        } else {
            // ✅ إعادة التعيين عند العودة تحت العدد المتوقع (بعد حذف أو مسح جديد)
            //hasCelebrated = false;
        }

    } else {
        // ✅ وضع الجرد الأعمى — لا توقعات
        if (tvProgressPercent != null) tvProgressPercent.setText("—");
        if (tvAccuracy != null) tvAccuracy.setText("—");
        if (tvVariance != null) {
            tvVariance.setText("—");
            tvVariance.setTextColor(ContextCompat.getColor(requireContext(),
                    R.color.text_secondary));
        }
    }

    // ✅ حالة القائمة الفارغة
    if (tvEmptyState != null) {
        tvEmptyState.setVisibility(scanned > 0 ? View.GONE : View.VISIBLE);
    }

    // ✅ الحالة النصية
    if (tvInventoryStatus != null && isScanning) {
        tvInventoryStatus.setText((isArabic ? "📡 جاري الجرد... (" : "📡 Scanning... (")
                + scanned + ")");
    }

    lastScannedForAnimation = scanned;
    updateButtonsState();
}

    private void updateTotalWeightAndValue() {
        final double finalWeight = aggregateTotalWeight;
        final double finalValue = aggregateTotalValue;

        mainHandler.post(() -> {
            if (!isAdded() || isFragmentDestroyed) return;

            if (tvTotalWeight != null) {
                tvTotalWeight.setText(String.format(Locale.US, "%.2f g", finalWeight));
            }
            if (tvTotalValue != null) {
                tvTotalValue.setText(String.format(Locale.US, "$%,.2f", finalValue));
            }
        });
    }

    private void recalculateAggregates() {
    double w = 0, v = 0;
    for (ScannedItemDisplay item : scannedItemsDisplayList) {
        if (item != null) {
            // ✅ الحل: لا نضرب بـ count
            //    كل EPC = قطعة فيزيائية واحدة
            //    count = عدد القراءات (للعرض فقط)
            w += item.weight;
            v += item.price;
        }
    }
    aggregateTotalWeight = w;
    aggregateTotalValue = v;
}

    private void updateElapsedTime() {
        if (tvElapsedTime != null && scanStartTime > 0) {
            long elapsedMillis = SystemClock.elapsedRealtime() - scanStartTime;
            long seconds = elapsedMillis / 1000;
            long hours = seconds / 3600;
            long minutes = (seconds % 3600) / 60;
            long secs = seconds % 60;
            tvElapsedTime.setText(String.format(Locale.US, "%02d:%02d:%02d",
                    hours, minutes, secs));
        }
    }

    // ================================================================
    //  Commit & Archive
    // ================================================================

    private void showCommitConfirmationDialog() {
        if (dialogsManager == null || inventoryEngine == null) return;
        if (!canEditInventory()) {
            showAccessDenied();
            return;
        }
        int scanned = inventoryEngine.getUniqueCount();
        dialogsManager.setExpectedCount(expectedCount);
        dialogsManager.showCommitConfirmationDialog(scanned);
    }

    private JSONObject buildLocalCommitSummary() {
        try {
            JSONObject summary = new JSONObject();
            int scanned = inventoryEngine != null ? inventoryEngine.getUniqueCount() : 0;

            String accuracy;
            if (expectedCount > 0) {
                accuracy = String.valueOf(Math.min(100, (scanned * 100) / expectedCount));
            } else {
                accuracy = "100";
            }

            String riskLevel;
            int variance = Math.abs(expectedCount - scanned);
            if (variance == 0) {
                riskLevel = "none";
            } else if (variance <= 2) {
                riskLevel = "low";
            } else if (variance <= 10) {
                riskLevel = "medium";
            } else {
                riskLevel = "high";
            }

            summary.put("accuracy", accuracy);
            summary.put("risk_level", riskLevel);
            summary.put("recommendation", isArabic
                    ? "تم الاعتماد — التقرير متاح في الأرشيف"
                    : "Committed — report available in archive");
            summary.put("expected_count", expectedCount);
            summary.put("found_count", scanned);
            summary.put("missing_count", Math.max(0, expectedCount - scanned));
            summary.put("unexpected_count", Math.max(0, scanned - expectedCount));
            summary.put("closed_at", getCurrentDateTimeString());

            return summary;
        } catch (Exception e) {
            Log.e(TAG, "buildLocalCommitSummary error", e);
            return new JSONObject();
        }
    }

    private void commitInventoryToServer() {
    if (!isAdded() || isFragmentDestroyed) return;

    if (persistenceManager == null || inventoryEngine == null || apiClient == null) {
        showToast("⚠️ غير جاهز");
        return;
    }

    if (persistenceManager.isCommittingNow()) {
        showToast(isArabic ? "⏳ جاري الإرسال بالفعل..." : "⏳ Already sending...");
        return;
    }

    showToast(isArabic ? "⏳ جاري إرسال البيانات..." : "⏳ Sending...");

    int scanned = inventoryEngine.getUniqueCount();
    int totalReads = inventoryEngine.getTotalReads();
    int branchId = getActiveBranchId();

    persistenceManager.commitInventory(
            inventoryEngine.getClientSessionId(),
            branchId,
            expectedCount,
            totalReads,
            scanned,
            scannedItemsDisplayList,
            apiClient,
            new InventoryPersistenceManager.CommitCallback() {
                @Override
                public void onCommitSuccess(JSONObject report) {
                    mainHandler.post(() -> {
                        if (!isAdded() || isFragmentDestroyed) return;

                        final JSONObject finalReport;

                        if (report == null || report.length() == 0) {
                            Log.w(TAG, "⚠️ Empty report from server — building local summary");
                            finalReport = buildLocalCommitSummary();
                        } else {
                            finalReport = report;
                        }

                        lastServerReport = finalReport;

                        showToast(isArabic ? "✅ تم اعتماد الجرد وحفظ التقرير"
                                           : "✅ Inventory committed successfully");

                        // ✅ [v4.0] صوت النجاح عبر Controller
                        if (scanSession != null) {
                            scanSession.notifySuccess();
                        } else if (soundManager != null) {
                            // Fallback للقديم
                            soundManager.playBeep(SoundManager.FeedbackType.COMPLETED);
                        }

                        // ✅ [v4.0] اعتبر الجلسة منتهية — الجلسة التالية ستكون جديدة
                        //              لكن لا تمسح القطع المعروضة (للطباعة والمراجعة)
                        isPaused = false;
                        hasUncommittedSession = false;
                        nextScanIsResume = false;
                        sessionElapsedBeforePause = 0;
                        hasCelebrated = false;

                        if (sessionManager != null) {
                            try {
                                sessionManager.clearSession();
                                Log.d(TAG, "🧹 Session cleared after commit");
                            } catch (Exception e) {
                                Log.w(TAG, "Failed to clear session after commit", e);
                            }
                        }

                        // ✅ تحديث الأزرار
                        if (btnGenerateReport != null) {
                            btnGenerateReport.setEnabled(false);
                            btnGenerateReport.setAlpha(0.5f);
                        }
                        if (btnCommitSession != null) {
                            btnCommitSession.setEnabled(false);
                            btnCommitSession.setText(isArabic ? "✅ معتمد" : "✅ Committed");
                            btnCommitSession.setAlpha(0.7f);
                        }

                        autoSaveSnapshotAfterCommit(finalReport);
                        showCommitSuccessDialog(finalReport);
                    });
                }

                @Override
                public void onCommitError(String error) {
                    mainHandler.post(() -> {
                        if (!isAdded() || isFragmentDestroyed) return;
                        Log.e(TAG, "Commit failed: " + error);
                        showToast((isArabic ? "❌ خطأ: " : "❌ Error: ") + error);

                        // ✅ [v4.0] صوت الخطأ
                        if (scanSession != null) {
                            scanSession.notifyError();
                        }
                    });
                }
            });
}

    private void showCommitSuccessDialog(JSONObject report) {
        if (!isAdded() || isFragmentDestroyed) return;

        try {
            String accuracy = extractString(report, "accuracy", "100");
            String riskLevel = extractString(report, "risk_level",
                    extractString(report, "risk", "none"));
            String recommendation = extractString(report, "recommendation",
                    extractString(report, "message",
                            isArabic ? "تم الاعتماد بنجاح" : "Committed successfully"));

            int expected = extractInt(report, "expected_count",
                    extractInt(report, "expected", expectedCount));
            int found = extractInt(report, "found_count",
                    extractInt(report, "unique_count",
                            extractInt(report, "scanned_count",
                                    inventoryEngine != null ? inventoryEngine.getUniqueCount() : 0)));
            int missing = extractInt(report, "missing_count",
                    Math.max(0, expected - found));

            StringBuilder sb = new StringBuilder();
            sb.append(isArabic ? "✅ تم اعتماد الجرد بنجاح:\n\n" : "✅ Inventory committed:\n\n");
            sb.append(isArabic ? "• الدقة: " : "• Accuracy: ").append(accuracy).append("%\n");
            sb.append(isArabic ? "• مستوى الخطورة: " : "• Risk Level: ").append(riskLevel).append("\n");

            if (expected > 0) {
                sb.append(isArabic ? "• المتوقع: " : "• Expected: ").append(expected).append("\n");
                sb.append(isArabic ? "• الممسوح: " : "• Found: ").append(found).append("\n");
                if (missing > 0) {
                    sb.append(isArabic ? "• المفقود: " : "• Missing: ").append(missing).append("\n");
                }
            }

            sb.append("\n").append(isArabic ? "• " : "• ").append(recommendation);

            String message = sb.toString();

            lastServerReport = report;

            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(isArabic ? "🎉 نجاح العملية" : "🎉 Success")
                    .setMessage(message)
                    .setPositiveButton(isArabic ? "🖨️ طباعة" : "🖨️ Print",
                            (dialog, which) -> printCurrentReport())
                    .setNeutralButton(isArabic ? "📂 الأرشيف" : "📂 Archive",
                            (dialog, which) -> showArchiveReports())
                    .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
                    .setCancelable(false)
                    .show();

        } catch (Exception e) {
            Log.e(TAG, "showCommitSuccessDialog error", e);
            showToast(isArabic ? "✅ تم الاعتماد" : "✅ Committed");
        }
    }

    private String extractString(JSONObject obj, String key, String defaultValue) {
        if (obj == null) return defaultValue;
        try {
            Object value = obj.opt(key);
            if (value == null) return defaultValue;
            String str = String.valueOf(value).trim();
            return str.isEmpty() ? defaultValue : str;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private int extractInt(JSONObject obj, String key, int defaultValue) {
        if (obj == null) return defaultValue;
        try {
            return obj.optInt(key, defaultValue);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private void showArchiveReports() {
        if (!isAdded() || isFragmentDestroyed) return;

        if (persistenceManager == null || apiClient == null || branchesManager == null) {
            showToast(isArabic ? "❌ غير جاهز" : "❌ Not ready");
            return;
        }

        AlertDialog loading = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "📂 جاري تحميل الأرشيف..."
                                   : "📂 Loading archive...")
                .setMessage(isArabic ? "يرجى الانتظار" : "Please wait")
                .setCancelable(false)
                .show();

        int branchId = getActiveBranchId();

        persistenceManager.fetchArchiveReports(apiClient, branchId,
                new InventoryPersistenceManager.ArchiveCallback() {
                    @Override
                    public void onArchiveLoaded(List<InventoryReport> reports) {
                        mainHandler.post(() -> {
                            try { loading.dismiss(); } catch (Exception ignored) {}
                            if (isAdded() && !isFragmentDestroyed) {
                                displayArchiveDialog(reports);
                            }
                        });
                    }

                    @Override
                    public void onArchiveError(String error) {
                        mainHandler.post(() -> {
                            try { loading.dismiss(); } catch (Exception ignored) {}
                            if (isAdded() && !isFragmentDestroyed) {
                                showToast((isArabic ? "❌ فشل التحميل: " : "❌ Load failed: ") + error);
                            }
                        });
                    }
                });
    }

    private void displayArchiveDialog(List<InventoryReport> reports) {
        if (!isAdded() || isFragmentDestroyed) return;

        if (reports == null || reports.isEmpty()) {
            showToast(isArabic ? "لا توجد تقارير سابقة" : "No previous reports");
            return;
        }

        String[] reportTitles = new String[reports.size()];
        for (int i = 0; i < reports.size(); i++) {
            InventoryReport r = reports.get(i);
            reportTitles[i] = String.format("📋 %s - %s (%d/%d)",
                    r.sessionId, r.date, r.scannedCount, r.expectedCount);
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "📂 أرشيف تقارير الجرد"
                                   : "📂 Inventory Reports Archive")
                .setItems(reportTitles, (dialog, which) -> {
                    InventoryReport selected = reports.get(which);
                    showReportDetails(selected);
                })
                .setPositiveButton(isArabic ? "طباعة الكل" : "Print All",
                        (dialog, which) -> {
                            if (printManager != null) {
                                printManager.printAllReports(reports);
                            }
                        })
                .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
                .show();
    }

    private void showReportDetails(InventoryReport report) {
        if (report == null || !isAdded() || isFragmentDestroyed) return;
        if (printManager != null) {
            printManager.showReportDetails(report, r -> {
                Log.d(TAG, "Print requested for " + r.sessionId);
            });
        }
    }

    private void openSnapshotsScreen() {
        try {
            if (!isAdded() || isFragmentDestroyed) return;

            if (isScanning) {
                showExitWarning(() -> {
                    stopScanning();
                    mainHandler.postDelayed(this::navigateToSnapshots, 400);
                });
            } else {
                navigateToSnapshots();
            }
        } catch (Exception e) {
            Log.e(TAG, "openSnapshotsScreen error", e);
        }
    }

    private void navigateToSnapshots() {
        try {
            if (!isAdded() || isFragmentDestroyed) return;
            if (getActivity() == null) return;

            if (getActivity() instanceof UHFMainActivity) {
                com.example.uhf.navigation.NavigationManager nav =
                        ((UHFMainActivity) getActivity()).getNavigationManager();

                if (nav != null) {
                    String tag = com.example.uhf.navigation.NavigationManager.TAG_SNAPSHOTS;
                    androidx.fragment.app.Fragment fragment = nav.createFragmentByTag(tag);

                    if (fragment != null) {
                        nav.loadFragmentDirectly(fragment, tag);
                        Log.d(TAG, "✅ Navigated to Snapshots via NavigationManager");
                        return;
                    }
                }
            }

            Log.w(TAG, "Using FragmentManager fallback for Snapshots");

            MiraSnapshotsFragment snapshotsFragment = new MiraSnapshotsFragment();

            getActivity().getSupportFragmentManager()
                    .beginTransaction()
                    .replace(android.R.id.content, snapshotsFragment, "Snapshots")
                    .addToBackStack("Snapshots")
                    .commit();

            Log.d(TAG, "✅ Navigated to Snapshots via FragmentManager");

        } catch (Exception e) {
            Log.e(TAG, "navigateToSnapshots error", e);
            showToast(isArabic ? "⚠️ خطأ في التنقل" : "⚠️ Navigation error");
        }
    }

    // ================================================================
    //  Print (HTML Report)
    // ================================================================

    private void printCurrentReport() {
        if (!isAdded() || isFragmentDestroyed) return;

        try {
            String html = buildReportHtml();
            String title = isArabic ? "تقرير الجرد" : "Inventory Report";

            android.content.Intent intent = new android.content.Intent(
                    requireContext(),
                    com.example.uhf.activity.InventoryReportPreviewActivity.class);
            intent.putExtra("report_html", html);
            intent.putExtra("report_title", title);
            startActivity(intent);

            Log.d(TAG, "✅ Report preview opened");

        } catch (Exception e) {
            Log.e(TAG, "printCurrentReport error", e);
            showToast(isArabic ? "⚠️ فشل فتح المعاينة" : "⚠️ Preview failed");
        }
    }

    private String buildReportHtml() {
        StringBuilder sb = new StringBuilder();

        if (!isAdded() || getContext() == null) return "";

        final String T_TITLE         = getString(R.string.report_title);
        final String T_SUBTITLE      = getString(R.string.report_subtitle);
        final String T_BRAND         = getString(R.string.report_brand);
        final String T_SESSION       = getString(R.string.report_session_id);
        final String T_OPERATOR      = getString(R.string.report_operator);
        final String T_DATE          = getString(R.string.report_date);
        final String T_TIME          = getString(R.string.report_time);
        final String T_BRANCH        = getString(R.string.report_branch);
        final String T_MODE          = getString(R.string.report_mode);
        final String T_MODE_FULL     = getString(R.string.report_mode_full);
        final String T_MODE_BLIND    = getString(R.string.report_mode_blind);

        final String T_SCANNED       = getString(R.string.report_stat_scanned);
        final String T_EXPECTED      = getString(R.string.report_stat_expected);
        final String T_ACCURACY      = getString(R.string.report_stat_accuracy);
        final String T_VARIANCE      = getString(R.string.report_stat_variance);

        final String T_TOTAL_WEIGHT  = getString(R.string.report_total_weight);
        final String T_TOTAL_VALUE   = getString(R.string.report_total_value);
        final String T_TOTAL_READS   = getString(R.string.report_total_reads);
        final String T_MISSING       = getString(R.string.report_missing);

        final String T_ITEMS_DETAILS = getString(R.string.report_items_details);
        final String T_COL_NUM       = getString(R.string.report_col_num);
        final String T_COL_NAME      = getString(R.string.report_col_name);
        final String T_COL_EPC       = getString(R.string.report_col_epc);
        final String T_COL_KARAT     = getString(R.string.report_col_karat);
        final String T_COL_WEIGHT    = getString(R.string.report_col_weight);
        final String T_COL_VALUE     = getString(R.string.report_col_value);
        final String T_COL_COUNT     = getString(R.string.report_col_count);

        final String T_FOOTER_NOTE   = getString(R.string.report_footer_note);
        final String T_SIGN_OPERATOR = getString(R.string.report_sign_operator);
        final String T_SIGN_MANAGER  = getString(R.string.report_sign_manager);
        final String T_SIGN_OFFICIAL = getString(R.string.report_sign_official);
        final String T_SYSTEM_USER   = getString(R.string.report_system_user);

        final String T_DIR = isArabic ? "rtl" : "ltr";
        final String T_ALIGN = isArabic ? "right" : "left";

        int scanned = inventoryEngine != null ? inventoryEngine.getUniqueCount() : 0;
        int totalReads = inventoryEngine != null ? inventoryEngine.getTotalReads() : 0;
        int missing = Math.max(0, expectedCount - scanned);
        int variance = scanned - expectedCount;

        int accuracy = expectedCount > 0
                ? Math.min(100, (scanned * 100) / expectedCount)
                : 100;

        String sessionId = inventoryEngine != null
                ? inventoryEngine.getClientSessionId() : "SESS-LOCAL";
        String branchName = branchesManager != null
                ? branchesManager.getActiveBranchDisplayName() : "—";
        String userName = getCurrentUserName();
        if (userName == null || userName.isEmpty()) {
            userName = T_SYSTEM_USER;
        }

        String dateOnly;
        String timeOnly;
        try {
            java.text.SimpleDateFormat dateFmt =
                    new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
            java.text.SimpleDateFormat timeFmt =
                    new java.text.SimpleDateFormat("HH:mm:ss", Locale.US);
            java.util.Date now = new java.util.Date();
            dateOnly = dateFmt.format(now);
            timeOnly = timeFmt.format(now);
        } catch (Exception e) {
            dateOnly = "—";
            timeOnly = "—";
        }

        String reportNumber = "RPT-" + (System.currentTimeMillis() % 1000000);

        sb.append("<!DOCTYPE html>\n")
          .append("<html dir=\"").append(T_DIR).append("\" lang=\"").append(isArabic ? "ar" : "en").append("\">\n")
          .append("<head>\n")
          .append("<meta charset=\"UTF-8\">\n")
          .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
          .append("<title>").append(T_TITLE).append("</title>\n")
          .append("<style>\n")
          .append("* { margin: 0; padding: 0; box-sizing: border-box; }\n")
          .append("@page { size: A4; margin: 15mm 10mm; }\n")
          .append("body { font-family: 'Cairo', 'Segoe UI', Arial, sans-serif; ")
          .append("       color: #1A1200; background: #FFFFFF; padding: 0; ")
          .append("       direction: ").append(T_DIR).append("; ")
          .append("       font-size: 12px; line-height: 1.5; }\n")
          .append(".report-container { border: 2px solid #B89456; border-radius: 8px; padding: 0; overflow: hidden; }\n")
          .append(".header { background: linear-gradient(135deg, #F5E4B0, #E5C158); ")
          .append("  padding: 20px 25px; border-bottom: 3px double #8B6914; ")
          .append("  display: flex; justify-content: space-between; align-items: center; }\n")
          .append(".header-left { text-align: ").append(T_ALIGN).append("; }\n")
          .append(".header-left .logo { font-size: 28px; font-weight: 900; color: #3D2E00; ")
          .append("  letter-spacing: 2px; line-height: 1.1; direction: ltr; }\n")
          .append(".header-left .logo-en { font-size: 11px; font-weight: bold; color: #6B5A28; ")
          .append("  letter-spacing: 4px; margin-top: 3px; direction: ltr; ")
          .append("  text-align: ").append(T_ALIGN).append("; }\n")
          .append(".header-left .branch { font-size: 10px; color: #6B5A28; margin-top: 8px; ")
          .append("  padding-top: 6px; border-top: 1px solid #8B6914; }\n")
          .append(".header-right { text-align: ").append(isArabic ? "left" : "right").append("; }\n")
          .append(".header-right .title { font-size: 18px; font-weight: 900; color: #3D2E00; }\n")
          .append(".header-right .subtitle { font-size: 11px; color: #6B5A28; margin-top: 3px; }\n")
          .append(".header-right .report-num { display: inline-block; background: #3D2E00; color: #F5D97A; ")
          .append("  padding: 4px 12px; border-radius: 12px; font-size: 10px; ")
          .append("  font-weight: bold; margin-top: 8px; letter-spacing: 1px; ")
          .append("  font-family: monospace; direction: ltr; }\n")
          .append(".meta { display: grid; grid-template-columns: 1fr 1fr; ")
          .append("  gap: 0; padding: 15px 25px; background: #FAF7F0; ")
          .append("  border-bottom: 1px solid #E8DFC8; }\n")
          .append(".meta-item { padding: 6px 0; font-size: 11px; }\n")
          .append(".meta-item .label { color: #6B5A28; font-weight: bold; ")
          .append("  display: inline-block; min-width: 90px; }\n")
          .append(".meta-item .value { color: #1A1200; font-weight: 600; }\n")
          .append(".ltr-inline { direction: ltr; unicode-bidi: embed; ")
          .append("  display: inline-block; font-family: monospace; }\n")
          .append(".stats { display: grid; grid-template-columns: repeat(4, 1fr); ")
          .append("  gap: 0; border-bottom: 1px solid #E8DFC8; }\n")
          .append(".stat { text-align: center; padding: 18px 10px; ")
          .append("  background: #FFFDF5; border-")
          .append(isArabic ? "right" : "left").append(": 1px solid #E8DFC8; }\n")
          .append(".stat:first-child { border-")
          .append(isArabic ? "right" : "left").append(": none; }\n")
          .append(".stat .value { font-size: 24px; font-weight: 900; ")
          .append("  color: #3D2E00; line-height: 1; }\n")
          .append(".stat .value.gold { color: #B89456; }\n")
          .append(".stat .value.green { color: #16A34A; }\n")
          .append(".stat .value.red { color: #DC2626; }\n")
          .append(".stat .label { font-size: 10px; color: #6B5A28; ")
          .append("  margin-top: 6px; font-weight: bold; letter-spacing: 0.5px; }\n")
          .append(".summary { display: grid; grid-template-columns: repeat(4, 1fr); ")
          .append("  gap: 0; padding: 12px 25px; background: #FAF7F0; ")
          .append("  border-bottom: 2px solid #B89456; }\n")
          .append(".summary-item { text-align: center; font-size: 11px; }\n")
          .append(".summary-item .label { color: #6B5A28; font-size: 9px; ")
          .append("  font-weight: bold; display: block; margin-bottom: 4px; ")
          .append("  letter-spacing: 0.5px; }\n")
          .append(".summary-item .value { color: #1A1200; font-weight: 900; ")
          .append("  font-size: 13px; direction: ltr; display: inline-block; }\n")
          .append(".table-section { padding: 20px 25px; }\n")
          .append(".table-title { font-size: 14px; font-weight: 900; color: #3D2E00; ")
          .append("  margin-bottom: 10px; padding-bottom: 8px; ")
          .append("  border-bottom: 2px solid #B89456; text-align: ")
          .append(T_ALIGN).append("; }\n")
          .append("table { width: 100%; border-collapse: collapse; font-size: 10px; }\n")
          .append("th { background: linear-gradient(135deg, #E5C158, #B89456); ")
          .append("  color: #FFFFFF; padding: 10px 6px; font-weight: bold; ")
          .append("  text-align: center; border: 1px solid #8B6914; ")
          .append("  font-size: 10px; letter-spacing: 0.5px; }\n")
          .append("td { padding: 8px 6px; border: 1px solid #E8DFC8; ")
          .append("  text-align: center; font-size: 10px; }\n")
          .append("tr:nth-child(even) td { background: #FDFCF8; }\n")
          .append("td.epc { font-family: 'Courier New', monospace; font-size: 8px; ")
          .append("  direction: ltr; text-align: left; letter-spacing: 0.3px; }\n")
          .append("td.name { text-align: ").append(T_ALIGN).append("; font-weight: 600; }\n")
          .append("td.value { font-weight: 900; color: #B89456; direction: ltr; }\n")
          .append("td.count { font-weight: 900; color: #3D2E00; }\n")
          .append(".footer { background: linear-gradient(135deg, #F5E4B0, #E5C158); ")
          .append("  padding: 15px 25px; text-align: center; ")
          .append("  border-top: 3px double #8B6914; font-size: 10px; ")
          .append("  color: #3D2E00; line-height: 1.7; }\n")
          .append(".footer .brand { font-weight: 900; letter-spacing: 2px; ")
          .append("  color: #3D2E00; direction: ltr; display: inline-block; }\n")
          .append(".footer .signature { display: flex; justify-content: space-around; ")
          .append("  margin-top: 15px; padding-top: 12px; ")
          .append("  border-top: 1px dashed #8B6914; }\n")
          .append(".footer .signature-item { text-align: center; }\n")
          .append(".footer .signature-item .line { width: 100px; height: 1px; ")
          .append("  background: #3D2E00; margin: 20px auto 5px; }\n")
          .append(".footer .signature-item .label { font-size: 9px; font-weight: bold; color: #6B5A28; }\n")
          .append("@media print { body { padding: 0; } ")
          .append("  .report-container { border: 2px solid #B89456; } ")
          .append("  .stat, th { -webkit-print-color-adjust: exact; print-color-adjust: exact; } }\n")
          .append("</style>\n</head>\n<body>\n");

        sb.append("<div class=\"report-container\">\n");

        sb.append("  <div class=\"header\">\n")
          .append("    <div class=\"header-left\">\n")
          .append("      <div class=\"logo\">MIRA</div>\n")
          .append("      <div class=\"logo-en\">JEWELRY ENTERPRISE™</div>\n")
          .append("      <div class=\"branch\">📍 ").append(escapeHtml(branchName)).append("</div>\n")
          .append("    </div>\n")
          .append("    <div class=\"header-right\">\n")
          .append("      <div class=\"title\">").append(T_TITLE).append("</div>\n")
          .append("      <div class=\"subtitle\">").append(T_SUBTITLE).append("</div>\n")
          .append("      <div class=\"report-num\">").append(reportNumber).append("</div>\n")
          .append("    </div>\n")
          .append("  </div>\n");

        sb.append("  <div class=\"meta\">\n")
          .append("    <div class=\"meta-item\"><span class=\"label\">")
          .append(T_SESSION).append(":</span> <span class=\"value ltr-inline\">")
          .append(escapeHtml(sessionId)).append("</span></div>\n")
          .append("    <div class=\"meta-item\"><span class=\"label\">")
          .append(T_OPERATOR).append(":</span> <span class=\"value\">")
          .append(escapeHtml(userName)).append("</span></div>\n")
          .append("    <div class=\"meta-item\"><span class=\"label\">")
          .append(T_DATE).append(":</span> <span class=\"value ltr-inline\">")
          .append(dateOnly).append("</span></div>\n")
          .append("    <div class=\"meta-item\"><span class=\"label\">")
          .append(T_TIME).append(":</span> <span class=\"value ltr-inline\">")
          .append(timeOnly).append("</span></div>\n")
          .append("    <div class=\"meta-item\"><span class=\"label\">")
          .append(T_BRANCH).append(":</span> <span class=\"value\">")
          .append(escapeHtml(branchName)).append("</span></div>\n")
          .append("    <div class=\"meta-item\"><span class=\"label\">")
          .append(T_MODE).append(":</span> <span class=\"value\">")
          .append(selectedCategoryFilter != null && !selectedCategoryFilter.isEmpty()
                  ? escapeHtml(selectedCategoryFilter)
                  : (expectedCount == 0 ? T_MODE_BLIND : T_MODE_FULL))
          .append("</span></div>\n")
          .append("  </div>\n");

        sb.append("  <div class=\"stats\">\n")
          .append("    <div class=\"stat\"><div class=\"value\">").append(scanned).append("</div>")
          .append("<div class=\"label\">").append(T_SCANNED).append("</div></div>\n")
          .append("    <div class=\"stat\"><div class=\"value\">").append(expectedCount).append("</div>")
          .append("<div class=\"label\">").append(T_EXPECTED).append("</div></div>\n")
          .append("    <div class=\"stat\"><div class=\"value gold\">").append(accuracy).append("%</div>")
          .append("<div class=\"label\">").append(T_ACCURACY).append("</div></div>\n")
          .append("    <div class=\"stat\"><div class=\"value ")
          .append(variance == 0 ? "green" : (variance > 0 ? "gold" : "red")).append("\">")
          .append(variance == 0 ? "0" : (variance > 0 ? "+" : "") + variance)
          .append("</div>")
          .append("<div class=\"label\">").append(T_VARIANCE).append("</div></div>\n")
          .append("  </div>\n");

        sb.append("  <div class=\"summary\">\n")
          .append("    <div class=\"summary-item\"><span class=\"label\">")
          .append(T_TOTAL_WEIGHT).append("</span>")
          .append("<span class=\"value\">")
          .append(String.format(Locale.US, "%.2f g", aggregateTotalWeight))
          .append("</span></div>\n")
          .append("    <div class=\"summary-item\"><span class=\"label\">")
          .append(T_TOTAL_VALUE).append("</span>")
          .append("<span class=\"value\" style=\"color: #B89456;\">")
          .append(String.format(Locale.US, "$%,.2f", aggregateTotalValue))
          .append("</span></div>\n")
          .append("    <div class=\"summary-item\"><span class=\"label\">")
          .append(T_TOTAL_READS).append("</span>")
          .append("<span class=\"value\">").append(totalReads).append("</span></div>\n")
          .append("    <div class=\"summary-item\"><span class=\"label\">")
          .append(T_MISSING).append("</span>")
          .append("<span class=\"value\" style=\"color: ")
          .append(missing > 0 ? "#DC2626" : "#16A34A").append(";\">")
          .append(missing).append("</span></div>\n")
          .append("  </div>\n");

        if (!scannedItemsDisplayList.isEmpty()) {
            sb.append("  <div class=\"table-section\">\n")
              .append("    <div class=\"table-title\">📋 ")
              .append(T_ITEMS_DETAILS)
              .append(" (").append(scannedItemsDisplayList.size()).append(")</div>\n")
              .append("    <table>\n<thead>\n<tr>\n")
              .append("      <th style=\"width: 4%;\">").append(T_COL_NUM).append("</th>\n")
              .append("      <th style=\"width: 22%;\">").append(T_COL_NAME).append("</th>\n")
              .append("      <th style=\"width: 30%;\">").append(T_COL_EPC).append("</th>\n")
              .append("      <th style=\"width: 8%;\">").append(T_COL_KARAT).append("</th>\n")
              .append("      <th style=\"width: 10%;\">").append(T_COL_WEIGHT).append("</th>\n")
              .append("      <th style=\"width: 12%;\">").append(T_COL_VALUE).append("</th>\n")
              .append("      <th style=\"width: 7%;\">").append(T_COL_COUNT).append("</th>\n")
              .append("    </tr>\n</thead>\n<tbody>\n");

            int index = 1;
            for (ScannedItemDisplay item : scannedItemsDisplayList) {
                if (item == null) continue;
                sb.append("      <tr>\n")
                  .append("        <td>").append(index++).append("</td>\n")
                  .append("        <td class=\"name\">").append(escapeHtml(item.title)).append("</td>\n")
                  .append("        <td class=\"epc\">").append(escapeHtml(item.epc)).append("</td>\n")
                  .append("        <td>").append(escapeHtml(item.karat)).append("</td>\n")
                  .append("        <td>").append(String.format(Locale.US, "%.2f", item.weight)).append("</td>\n")
                  .append("        <td class=\"value\">")
                  .append(String.format(Locale.US, "$%,.2f", item.price)).append("</td>\n")
                  .append("        <td class=\"count\">").append(item.count).append("</td>\n")
                  .append("      </tr>\n");
            }
            sb.append("    </tbody>\n</table>\n</div>\n");
        }

        sb.append("  <div class=\"footer\">\n")
          .append("    <div class=\"brand\">").append(T_BRAND).append("</div>\n")
          .append("    <div style=\"margin-top: 5px;\">")
          .append(T_FOOTER_NOTE)
          .append(" • <span class=\"ltr-inline\">")
          .append(dateOnly).append(" ").append(timeOnly)
          .append("</span></div>\n")
          .append("    <div class=\"signature\">\n")
          .append("      <div class=\"signature-item\">")
          .append("<div class=\"line\"></div>")
          .append("<div class=\"label\">").append(T_SIGN_OPERATOR).append("</div></div>\n")
          .append("      <div class=\"signature-item\">")
          .append("<div class=\"line\"></div>")
          .append("<div class=\"label\">").append(T_SIGN_MANAGER).append("</div></div>\n")
          .append("      <div class=\"signature-item\">")
          .append("<div class=\"line\"></div>")
          .append("<div class=\"label\">").append(T_SIGN_OFFICIAL).append("</div></div>\n")
          .append("    </div>\n")
          .append("  </div>\n");

        sb.append("</div>\n");
        sb.append("</body>\n</html>");

        return sb.toString();
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }

    private void printSingleItemLabel(ScannedItemDisplay item) {
        if (printManager != null && item != null) {
            printManager.printSingleItemLabel(item);
        }
    }

    // ================================================================
    //  Items List Dialog (Pagination)
    // ================================================================

    private void showItemsListDialog() {
        try {
            if (!isAdded() || isFragmentDestroyed) return;

            final int totalCount = scannedItemsDisplayList.size();
            if (totalCount == 0) {
                showToast(isArabic ? "لا توجد مسوحات بعد" : "No scans yet");
                return;
            }

            if (totalCount <= ITEMS_LIST_PAGE_SIZE) {
                showItemsListPage(0, totalCount);
                return;
            }

            int pages = (int) Math.ceil((double) totalCount / ITEMS_LIST_PAGE_SIZE);
            String[] options = new String[pages];

            for (int i = 0; i < pages; i++) {
                int start = i * ITEMS_LIST_PAGE_SIZE + 1;
                int end = Math.min((i + 1) * ITEMS_LIST_PAGE_SIZE, totalCount);
                options[i] = isArabic
                        ? String.format("📋 القطع %d - %d (من %d)", start, end, totalCount)
                        : String.format("📋 Items %d - %d (of %d)", start, end, totalCount);
            }

            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle((isArabic ? "📋 الممسوحات (" : "📋 Scans (") + totalCount + ")")
                    .setItems(options, (d, which) -> {
                        int startIndex = which * ITEMS_LIST_PAGE_SIZE;
                        int endIndex = Math.min(startIndex + ITEMS_LIST_PAGE_SIZE, totalCount);
                        showItemsListPage(startIndex, endIndex);
                    })
                    .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
                    .show();

        } catch (Exception e) {
            Log.e(TAG, "showItemsListDialog error", e);
        }
    }

    private void showItemsListPage(int startIndex, int endIndex) {
        try {
            if (!isAdded() || isFragmentDestroyed) return;
            if (startIndex < 0 || endIndex > scannedItemsDisplayList.size()) return;

            int pageSize = endIndex - startIndex;
            if (pageSize <= 0) return;

            String[] titles = new String[pageSize];
            final List<ScannedItemDisplay> pageItems = new ArrayList<>(pageSize);

            for (int i = 0; i < pageSize; i++) {
                ScannedItemDisplay item = scannedItemsDisplayList.get(startIndex + i);
                pageItems.add(item);
                titles[i] = String.format("%s\n%s • %s • %.2fg • 📶%d",
                        item.title, item.epc, item.karat, item.weight, item.rssi);
            }

            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle((isArabic ? "📋 الممسوحات (" : "📋 Scans (")
                            + (startIndex + 1) + "-" + endIndex
                            + (isArabic ? " من " : " of ") + scannedItemsDisplayList.size() + ")")
                    .setItems(titles, (d, which) -> {
                        if (which >= 0 && which < pageItems.size()) {
                            ScannedItemDisplay item = pageItems.get(which);
                            if (dialogsManager != null) {
                                dialogsManager.showItemOptionsDialog(item);
                            }
                        }
                    })
                    .setPositiveButton(isArabic ? "رجوع" : "Back",
                            (d, w) -> showItemsListDialog())
                    .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
                    .show();

        } catch (Exception e) {
            Log.e(TAG, "showItemsListPage error", e);
        }
    }

    // ================================================================
    //  Power / Category / Filter
    // ================================================================

    private void showPowerControlSheet() {
        if (dialogsManager != null) {
            dialogsManager.setCurrentPowerDbm(currentPowerDbm);
            dialogsManager.showPowerControlSheet();
        }
    }

    private void applyAntennaPower(int dbm) {
        try {
            com.example.uhf.MiraRfid.RFIDManager rfidManager =
                    com.example.uhf.MiraRfid.RFIDManager.getInstance(requireContext());

            if (rfidManager == null) {
                Log.w(TAG, "RFIDManager is null");
                return;
            }

            boolean success = rfidManager.setPower(dbm);

            if (success) {
                Log.i(TAG, "✅ Power applied: " + dbm + " dBm");
                showToast(isArabic
                        ? "✅ تم ضبط القدرة على " + dbm + " dBm"
                        : "✅ Power set to " + dbm + " dBm");
            } else {
                Log.w(TAG, "⚠️ Power apply failed");
                showToast(isArabic
                        ? "⚠️ فشل ضبط القدرة — تحقق من الاتصال بالقارئ"
                        : "⚠️ Power control failed — check reader");
            }

        } catch (Exception e) {
            Log.e(TAG, "applyAntennaPower error", e);
            showToast(isArabic
                    ? "⚠️ خطأ في ضبط القدرة"
                    : "⚠️ Power control error");
        }
    }

    private void showCategorySelectionDialog() {
        if (!isAdded() || isFragmentDestroyed) return;

        final String[] categoriesAr = {
                "الكل", "عيار 24", "عيار 22", "عيار 21", "عيار 18", "عيار 14",
                "سبائك", "أونصة", "فضة", "ألماس", "أخرى"
        };
        final String[] categoriesEn = {
                "All", "24K", "22K", "21K", "18K", "14K",
                "Bullion", "Ounce", "Silver", "Diamond", "Other"
        };
        final String[] categories = isArabic ? categoriesAr : categoriesEn;

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "🎯 اختر الفئة للجرد" : "🎯 Select Category")
                .setItems(categories, (dialog, which) -> {
                    applyCategoryFilter(categories[which], which);
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel",
                        (dialog, which) -> {
                            if (segmentedModes != null && btnModeFull != null) {
                                segmentedModes.check(R.id.btnModeFull);
                            }
                        })
                .show();
    }

    private void applyCategoryFilter(String category, int index) {
        try {
            if (index == 0) {
                showToast(isArabic ? "✅ وضع الجرد الكامل" : "✅ Full inventory mode");
                return;
            }

            selectedCategoryFilter = category;
            showToast(isArabic ? "🎯 جرد فئوي: " + category : "🎯 Category: " + category);

            if (isScanning) {
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(isArabic ? "⚠️ إعادة التشغيل" : "⚠️ Restart")
                        .setMessage(isArabic ?
                                "لتطبيق الفئة، يجب إيقاف الجرد الحالي وإعادة تشغيله. هل تريد المتابعة؟" :
                                "To apply the category, current inventory must be stopped and restarted. Continue?")
                        .setPositiveButton(isArabic ? "متابعة" : "Continue", (d, w) -> {
                            stopScanning();
                            mainHandler.postDelayed(
                                    () -> startCategoryInventory(category), 500);
                        })
                        .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                        .show();
            } else {
                startCategoryInventory(category);
            }
        } catch (Exception e) {
            Log.e(TAG, "applyCategoryFilter error", e);
        }
    }

    private void showLiveFilterChips() {
        try {
            if (!isAdded() || isFragmentDestroyed || filterManager == null) return;

            String[] filters = filterManager.getFilterOptions();

            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(filterManager.buildFilterDialogTitle())
                    .setItems(filters, (dialog, which) -> applyLiveFilter(which))
                    .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "showLiveFilterChips error", e);
        }
    }

    private void applyLiveFilter(int filterType) {
        try {
            if (filterManager == null || !isAdded() || isFragmentDestroyed) return;

            currentLiveFilter = filterType;
            filterManager.setCurrentFilter(filterType);

            if (filterType == InventoryFilterManager.FILTER_MISSING) {
                showMissingItemsDialog();
                return;
            }

            updateAdapterList();

            showToast(isArabic
                    ? "🎯 الفلتر: " + filterManager.getCurrentFilterName()
                    : "🎯 Filter: " + filterManager.getCurrentFilterName());
        } catch (Exception e) {
            Log.e(TAG, "applyLiveFilter error", e);
        }
    }

    private void showMissingItemsDialog() {
        try {
            if (filterManager == null || catalogManager == null
                    || !isAdded() || isFragmentDestroyed) return;

            if (catalogManager.isEmpty()) {
                showToast(filterManager.buildNoCatalogMessage());
                return;
            }

            List<String> missingEpcs = filterManager.getMissingItems(
                    catalogEpcs, scannedItemsDisplayList);

            if (missingEpcs.isEmpty()) {
                showToast(filterManager.buildNoMissingMessage());
                return;
            }

            String[] missingTitles = filterManager.buildMissingItemsList(
                    missingEpcs, catalogManager.getCatalogItemsMap());

            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(filterManager.buildMissingItemsTitle(missingEpcs.size()))
                    .setItems(missingTitles, null)
                    .setPositiveButton(isArabic ? "موافق" : "OK", null)
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "showMissingItemsDialog error", e);
        }
    }

    // ================================================================
    //  Session Save/Restore
    // ================================================================

    private void checkAndRestoreSession() {
        if (sessionManager == null || !isAdded() || isFragmentDestroyed) return;

        sessionManager.tryRestoreSession(new InventorySessionManager.SessionCallback() {
            @Override
            public void onSessionFound(JSONArray items, int expectedCount,
                                        String category, long timeAgoMillis) {
                if (!isAdded() || isFragmentDestroyed) return;
                showRestoreSessionDialog(items, expectedCount, category, timeAgoMillis);
            }

            @Override
            public void onNoSession() {
                Log.d(TAG, "No previous session found");
            }
        });
    }

    private void showRestoreSessionDialog(JSONArray items, int expectedCount,
                                       String category, long timeAgoMillis) {
        if (!isAdded() || isFragmentDestroyed) return;

        String message = sessionManager.buildRestoreMessage(items, timeAgoMillis);

        final android.widget.CheckBox dontAskAgain = new android.widget.CheckBox(requireContext());
        dontAskAgain.setText(isArabic
                ? "لا تسألني مجدداً عن هذه الجلسة"
                : "Don't ask again for this session");
        dontAskAgain.setPadding(40, 20, 40, 20);

        android.widget.LinearLayout container = new android.widget.LinearLayout(requireContext());
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        container.setPadding(40, 20, 40, 20);
        container.addView(dontAskAgain);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "🔄 استرجاع جلسة سابقة" : "🔄 Restore Previous Session")
                .setMessage(message)
                .setView(container)
                .setPositiveButton(isArabic ? "✅ استرجاع" : "✅ Restore",
                        (d, w) -> {
                            if (dontAskAgain.isChecked()) {
                                saveSkipRestorePrompt(true);
                            }
                            applyRestoredSession(items, expectedCount, category);
                        })
                .setNegativeButton(isArabic ? "بدء جديد" : "Start New",
                        (d, w) -> {
                            if (dontAskAgain.isChecked()) {
                                saveSkipRestorePrompt(true);
                            }
                            if (sessionManager != null) sessionManager.clearSession();
                            showToast(isArabic ? "✨ بدء جلسة جديدة" : "✨ Started fresh session");
                        })
                .setNeutralButton(isArabic ? "لاحقاً" : "Later", null)
                .setCancelable(true)
                .show();
    }

    private void saveSkipRestorePrompt(boolean skip) {
        try {
            if (getContext() == null) return;
            requireContext().getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_SKIP_RESTORE_PROMPT, skip).apply();
        } catch (Exception e) {
            Log.e(TAG, "saveSkipRestorePrompt error", e);
        }
    }

    private void resetSkipRestorePrompt() {
        try {
            if (getContext() == null) return;
            requireContext().getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_SKIP_RESTORE_PROMPT, false).apply();
        } catch (Exception ignored) {}
    }

    private void applyRestoredSession(JSONArray items, int expectedCount, String category) {
        try {
            if (items == null) return;

            Log.d(TAG, "🔄 Restoring " + items.length() + " items...");

            synchronized (scannedItemsDisplayList) {
                scannedItemsDisplayList.clear();
            }

            double totalWeight = 0;
            double totalValue = 0;

            synchronized (scannedItemsDisplayList) {
                for (int i = 0; i < items.length(); i++) {
                    try {
                        JSONObject itemObj = items.getJSONObject(i);

                        ScannedItemDisplay item = new ScannedItemDisplay(
                                itemObj.optString("epc", ""),
                                itemObj.optString("title", ""),
                                itemObj.optString("karat", "—"),
                                Math.max(1, itemObj.optInt("count", 1)),
                                itemObj.optInt("rssi", -50),
                                itemObj.optString("time", ""));

                        item.weight = itemObj.optDouble("weight", 0);
item.price = itemObj.optDouble("price", 0);

// ✅ [إصلاح حرج]
totalWeight += item.weight;
totalValue += item.price;

scannedItemsDisplayList.add(item);
                    } catch (Exception e) {
                        Log.w(TAG, "Item parse error at " + i, e);
                    }
                }
            }

            aggregateTotalWeight = totalWeight;
            aggregateTotalValue = totalValue;

            if (modernAdapter != null) {
                updateAdapterList();
                modernAdapter.resetAnimationTracker();
            }

            if (!scannedItemsDisplayList.isEmpty()) {
                switchToActiveState(false);
            }

            this.expectedCount = expectedCount;
            if (tvExpectedCount != null) {
                tvExpectedCount.setText(expectedCount > 0
                        ? String.valueOf(expectedCount) : "—");
            }

            selectedCategoryFilter = (category == null || category.isEmpty())
                    ? null : category;
            lastScannedForAnimation = 0;

            updateUI();
            updateTotalWeightAndValue();

            if (tvTotalWeight != null) {
                tvTotalWeight.setText(String.format(Locale.US, "%.2f g", totalWeight));
            }
            if (tvTotalValue != null) {
                tvTotalValue.setText(String.format(Locale.US, "$%,.2f", totalValue));
            }
            if (tvScannedCount != null) {
                tvScannedCount.setText(String.valueOf(scannedItemsDisplayList.size()));
            }
            if (tvItemCount != null) {
                tvItemCount.setText(String.valueOf(scannedItemsDisplayList.size()));
            }

            if (tvEmptyState != null) {
                tvEmptyState.setVisibility(
                        scannedItemsDisplayList.isEmpty() ? View.VISIBLE : View.GONE);
            }

            lastServerReport = null;

            if (btnGenerateReport != null) {
                btnGenerateReport.setEnabled(true);
                btnGenerateReport.setAlpha(1.0f);
            }

            if (btnCommitSession != null) {
                btnCommitSession.setText(isArabic ? "اعتماد" : "Commit");
                btnCommitSession.setEnabled(true);
                btnCommitSession.setAlpha(1.0f);
            }

            if (rvInventoryItems != null) {
                rvInventoryItems.scrollToPosition(0);
            }

            updateButtonsState();

            showToast(isArabic
                    ? "✅ تم استرجاع " + scannedItemsDisplayList.size() + " قطعة"
                    : "✅ Restored " + scannedItemsDisplayList.size() + " items");

            if (soundManager != null) {
                soundManager.playBeep(SoundManager.FeedbackType.COMPLETED);
            }

            Log.d(TAG, "✅ Session restored: "
                    + scannedItemsDisplayList.size() + " items, "
                    + String.format(Locale.US, "%.2f g, $%.2f", totalWeight, totalValue));

        } catch (Exception e) {
            Log.e(TAG, "applyRestoredSession error", e);
            showToast(isArabic ? "❌ فشل استرجاع الجلسة" : "❌ Restore failed");
        }
    }

    private void confirmResetSession() {
    if (!isAdded() || isFragmentDestroyed) return;

    new MaterialAlertDialogBuilder(requireContext())
            .setTitle(isArabic ? "⚠️ تأكيد" : "⚠️ Confirm")
            .setMessage(isArabic ?
                    "هل أنت متأكد من إعادة تعيين الجلسة؟\nسيتم حذف كل البيانات غير المعتمدة." :
                    "Are you sure you want to reset the session?\nAll uncommitted data will be lost.")
            .setPositiveButton(isArabic ? "إعادة تعيين" : "Reset",
                    (dialog, which) -> {
                        try {
                            if (inventoryEngine != null) {
                                inventoryEngine.clearSession();
                            }

                            clearAllLists();

                            // ✅ [v4.0] أعِد تعيين حالة الجلسة
                            isPaused = false;
                            hasUncommittedSession = false;
                            nextScanIsResume = false;
                            sessionElapsedBeforePause = 0;
                            hasCelebrated = false;

                            if (sessionManager != null) {
                                sessionManager.clearSession();
                            }

                            expectedCount = 0;
                            selectedCategoryFilter = null;
                            lastScannedForAnimation = 0;

                            if (tvExpectedCount != null) {
                                tvExpectedCount.setText("0");
                            }

                            if (btnCommitSession != null) {
                                btnCommitSession.setText(isArabic ? "اعتماد" : "Commit");
                            }
                            if (btnGenerateReport != null) {
                                btnGenerateReport.setText(isArabic ? "📋 تقرير" : "📋 Report");
                            }

                            mainHandler.postDelayed(() -> {
                                if (isAdded() && !isFragmentDestroyed) {
                                    updateUI();
                                    updateTotalWeightAndValue();
                                    updateButtonsState();
                                }
                            }, 100);

                            showToast(isArabic ? "✅ تم إعادة التعيين"
                                               : "✅ Reset successfully");

                            switchToWelcomeState();
                        } catch (Exception e) {
                            Log.e(TAG, "Reset error", e);
                            showToast(isArabic ? "❌ خطأ في إعادة التعيين"
                                               : "❌ Reset error");
                        }
                    })
            .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
            .show();
}

    // ================================================================
    //  Utilities
    // ================================================================

    private void showToast(String msg) {
        if (msg == null || msg.isEmpty()) return;
        try {
            if (isAdded() && getContext() != null) {
                Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ignored) {}
    }

    private String getCurrentTimeString() {
        try {
            java.text.SimpleDateFormat sdf =
                    new java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            return sdf.format(new java.util.Date());
        } catch (Exception e) {
            return "";
        }
    }

    private String getCurrentDateTimeString() {
        try {
            java.text.SimpleDateFormat sdf =
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
            return sdf.format(new java.util.Date());
        } catch (Exception e) {
            return "";
        }
    }

    private void takeSnapshot() {
        if (snapshotManager == null) {
            showToast(isArabic ? "⚠️ مدير اللقطات غير جاهز" : "⚠️ Snapshot manager not ready");
            return;
        }

        int scanned = inventoryEngine != null ? inventoryEngine.getUniqueCount() : 0;
        if (scanned == 0) {
            showToast(isArabic ? "⚠️ لا توجد مسوحات لحفظها" : "⚠️ No scans to save");
            return;
        }

        android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setHint(isArabic ? "اسم اللقطة (اختياري)" : "Snapshot name (optional)");
        input.setPadding(40, 40, 40, 40);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "📸 حفظ لقطة" : "📸 Save Snapshot")
                .setMessage(isArabic
                        ? "سيتم حفظ " + scanned + " قطعة في لقطة جديدة"
                        : "Will save " + scanned + " items")
                .setView(input)
                .setPositiveButton(isArabic ? "حفظ" : "Save", (d, w) -> {
                    String name = input.getText().toString().trim();

                    final String branchName = branchesManager != null
                            ? branchesManager.getActiveBranchDisplayName() : "";
                    final String mode = (selectedCategoryFilter != null && !selectedCategoryFilter.isEmpty())
                            ? "category" : "full";
                    final String user = getCurrentUserName();

                    ioExecutor.execute(() -> {
                        try {
                            InventorySnapshot snapshot = snapshotManager.createSnapshot(
                                    inventoryEngine != null ? inventoryEngine.getClientSessionId() : "",
                                    scannedItemsDisplayList,
                                    expectedCount,
                                    branchName,
                                    mode,
                                    user,
                                    name.isEmpty() ? null : name);

                            boolean saved = snapshotManager.saveSnapshot(snapshot);

                            mainHandler.post(() -> {
                                if (!isAdded() || isFragmentDestroyed) return;
                                if (saved) {
                                    showToast(isArabic ? "✅ تم حفظ اللقطة" : "✅ Snapshot saved");
                                    if (soundManager != null) {
                                        soundManager.playBeep(SoundManager.FeedbackType.COMPLETED);
                                    }
                                } else {
                                    showToast(isArabic ? "❌ فشل الحفظ" : "❌ Save failed");
                                }
                            });
                        } catch (Exception e) {
                            Log.e(TAG, "takeSnapshot error", e);
                            mainHandler.post(() -> {
                                if (!isAdded() || isFragmentDestroyed) return;
                                showToast(isArabic ? "❌ خطأ" : "❌ Error");
                            });
                        }
                    });
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
    }

    private void autoSaveSnapshotAfterCommit(JSONObject serverReport) {
        try {
            if (!autoSnapshotEnabled) {
                Log.d(TAG, "Auto-snapshot disabled — skipping");
                return;
            }

            if (snapshotManager == null || inventoryEngine == null) {
                Log.w(TAG, "autoSaveSnapshot: prerequisites missing");
                return;
            }

            final int scanned = inventoryEngine.getUniqueCount();
            if (scanned == 0) {
                Log.d(TAG, "autoSaveSnapshot: no items — skipping");
                return;
            }

            final String autoName = buildAutoSnapshotName();

            final String branchName = branchesManager != null
                    ? branchesManager.getActiveBranchDisplayName() : "";
            final String mode = (selectedCategoryFilter != null
                    && !selectedCategoryFilter.isEmpty()) ? "category" : "full";
            final String user = getCurrentUserName();

            ioExecutor.execute(() -> {
                try {
                    InventorySnapshot snapshot = snapshotManager.createSnapshot(
                            inventoryEngine.getClientSessionId(),
                            scannedItemsDisplayList,
                            expectedCount,
                            branchName,
                            mode,
                            user,
                            autoName);

                    StringBuilder notes = new StringBuilder();
                    notes.append(isArabic
                            ? "لقطة تلقائية بعد الاعتماد"
                            : "Auto-snapshot after commit");

                    if (serverReport != null && serverReport.length() > 0) {
                        String accuracy = serverReport.optString("accuracy", "");
                        String risk = serverReport.optString("risk_level", "");

                        if (!accuracy.isEmpty()) {
                            notes.append(" • ").append(isArabic ? "دقة: " : "Accuracy: ")
                                 .append(accuracy).append("%");
                        }
                        if (!risk.isEmpty() && !"unknown".equals(risk)) {
                            notes.append(" • ").append(isArabic ? "خطر: " : "Risk: ")
                                 .append(risk);
                        }
                    }

                    snapshot.setNotes(notes.toString());

                    boolean saved = snapshotManager.saveSnapshot(snapshot);

                    mainHandler.post(() -> {
                        if (!isAdded() || isFragmentDestroyed) return;
                        if (saved) {
                            Log.i(TAG, "✅ Auto-snapshot saved: " + autoName);
                            showToast(isArabic
                                    ? "📸 تم حفظ لقطة تلقائية"
                                    : "📸 Auto-snapshot saved");
                        } else {
                            Log.w(TAG, "Auto-snapshot save failed");
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "autoSaveSnapshotAfterCommit error", e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "autoSaveSnapshotAfterCommit outer error", e);
        }
    }

    private String buildAutoSnapshotName() {
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm", Locale.US);
            String dateTime = sdf.format(new java.util.Date());

            String branch = branchesManager != null
                    ? branchesManager.getActiveBranchDisplayName() : "";
            if (branch == null) branch = "";

            String modeLabel;
            if (selectedCategoryFilter != null && !selectedCategoryFilter.isEmpty()) {
                modeLabel = selectedCategoryFilter;
            } else if (expectedCount == 0) {
                modeLabel = isArabic ? "أعمى" : "Blind";
            } else {
                modeLabel = isArabic ? "كامل" : "Full";
            }

            StringBuilder sb = new StringBuilder();
            sb.append(dateTime);

            if (!branch.isEmpty()) {
                sb.append(" — ").append(branch);
            }

            sb.append(" [").append(modeLabel).append("]");

            return sb.toString();

        } catch (Exception e) {
            Log.e(TAG, "buildAutoSnapshotName error", e);
            return "Auto-" + System.currentTimeMillis();
        }
    }

    private String getCurrentUserName() {
        try {
            com.example.uhf.tools.SessionManager sm =
                    new com.example.uhf.tools.SessionManager(requireContext());
            String name = sm.getDisplayName();
            return name != null ? name : sm.getUsername();
        } catch (Exception e) {
            return "";
        }
    }

    private String getDeviceId() {
        try {
            return android.provider.Settings.Secure.getString(
                    requireContext().getContentResolver(),
                    android.provider.Settings.Secure.ANDROID_ID);
        } catch (Exception e) {
            return "UNKNOWN-DEVICE";
        }
    }

    private int getActiveBranchId() {
        try {
            return branchesManager != null ? branchesManager.getActiveBranchId() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private void syncOfflineTransactions() {
        if (persistenceManager == null || apiClient == null) return;

        persistenceManager.syncOfflineTransactions(apiClient,
                new InventoryPersistenceManager.SyncCallback() {
                    @Override
                    public void onSyncComplete(int syncedCount) {
                        if (syncedCount > 0) {
                            Log.i(TAG, "✅ Synced " + syncedCount + " transactions");
                            mainHandler.post(() -> {
                                if (isAdded() && !isFragmentDestroyed) {
                                    showToast(isArabic
                                            ? "✅ تمت مزامنة " + syncedCount + " معاملة"
                                            : "✅ Synced " + syncedCount + " transactions");
                                }
                            });
                        }
                    }

                    @Override
                    public void onSyncError(String error) {
                        Log.w(TAG, "⚠️ Sync error: " + error);
                    }
                });
    }

    private void updateItemStatus(String epc, String status) {
        Log.d(TAG, "Update status " + epc + " to " + status);
    }

    private void registerItemDamage(String epc, String damageType) {
        Log.d(TAG, "Register damage " + epc + " - " + damageType);
    }

    // ================================================================
    //  Public APIs
    // ================================================================

    public void showInventoryScannerSettings() {
        try {
            if (!isAdded() || isFragmentDestroyed) return;
            showPowerControlSheet();
        } catch (Exception e) {
            Log.e(TAG, "showInventoryScannerSettings error", e);
        }
    }

    public void showInventorySessionSettings() {
        try {
            if (!isAdded() || isFragmentDestroyed) return;
            if (dialogsManager != null) {
                dialogsManager.setExpectedCount(expectedCount);
                dialogsManager.showSessionSettingsDialog();
            }
        } catch (Exception e) {
            Log.e(TAG, "showInventorySessionSettings error", e);
        }
    }

    public void showInventoryBuzzerSettings() {
        try {
            if (!isAdded() || isFragmentDestroyed) return;
            if (dialogsManager != null) {
                dialogsManager.showBuzzerSettingsDialog();
            }
        } catch (Exception e) {
            Log.e(TAG, "showInventoryBuzzerSettings error", e);
        }
    }

    public int getScannedCount() {
        try {
            if (inventoryEngine != null) return inventoryEngine.getUniqueCount();
            return scannedItemsDisplayList != null ? scannedItemsDisplayList.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public boolean isCurrentlyScanning() {
        return isScanning;
    }

    public void showArchiveReportsPublic() {
        try {
            if (isAdded() && !isFragmentDestroyed) {
                showArchiveReports();
            }
        } catch (Exception e) {
            Log.e(TAG, "showArchiveReportsPublic error", e);
        }
    }

    // ================================================================
    //  Lifecycle Cleanup
    // ================================================================

    @Override
    public void onStart() {
        super.onStart();
        try {
            if (getActivity() instanceof UHFMainActivity) {
                UHFMainActivity activity = (UHFMainActivity) getActivity();

                activity.setBottomNavVisible(false);

                View fab = activity.findViewById(R.id.fabActionCenter);
                if (fab != null) {
                    fab.setVisibility(View.GONE);
                    Log.d(TAG, "🙈 FAB hidden in Inventory");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "onStart error", e);
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        try {
            if (getActivity() instanceof UHFMainActivity) {
                UHFMainActivity activity = (UHFMainActivity) getActivity();

                activity.setBottomNavVisible(true);

                View fab = activity.findViewById(R.id.fabActionCenter);
                if (fab != null) {
                    fab.setVisibility(View.VISIBLE);
                    Log.d(TAG, "👁️ FAB restored");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "onStop error", e);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        try {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).setActiveInventoryFragment(this);
            }

            if (shouldPromptForRestore()) {
                hasTriedRestoringSession = true;
                mainHandler.postDelayed(this::checkAndRestoreSession, 800);
            }

            updateButtonsState();

        } catch (Exception e) {
            Log.e(TAG, "onResume error", e);
        }
    }

    /**
 * ✅ [Stage-1] استئناف ذكي مع نافذة تأكيد
 */
private void silentRestoreSession() {
    // ✅ [Stage-1] منع الاسترجاع المزدوج
    if (hasRestoredThisSession) {
        Log.d(TAG, "⏭️ Session already restored — skip");
        return;
    }
    hasRestoredThisSession = true;

    if (sessionManager == null || !isAdded() || isFragmentDestroyed) return;

    try {
        if (!sessionManager.hasValidSession()) {
            Log.d(TAG, "silentRestore: no valid session");
            return;
        }

        sessionManager.tryRestoreSession(new InventorySessionManager.SessionCallback() {
            @Override
            public void onSessionFound(JSONArray items, int expected,
                                        String category, long timeAgoMillis) {
                if (!isAdded() || isFragmentDestroyed) return;
                if (items == null || items.length() == 0) return;

                Log.i(TAG, "🔄 Restoring " + items.length() + " items");
                applyRestoredSessionSilent(items, expected, category);

                // ✅ عرض نافذة الاستئناف
                mainHandler.postDelayed(() ->
                        showResumeDialog(items.length(), expected, timeAgoMillis), 500);
            }

            @Override
            public void onNoSession() {
                Log.d(TAG, "silentRestore: no session");
            }
        });
    } catch (Exception e) {
        Log.e(TAG, "silentRestoreSession error", e);
    }
}
private void applyRestoredSessionSilent(JSONArray items, int expectedCount, String category) {
    try {
        if (items == null) return;

        // ✅ [إصلاح حرج] امسح القائمة أولاً
        synchronized (scannedItemsDisplayList) {
            scannedItemsDisplayList.clear();
        }
        aggregateTotalWeight = 0;
        aggregateTotalValue = 0;
        catalogEpcs.clear();

            double totalWeight = 0;
            double totalValue = 0;

            synchronized (scannedItemsDisplayList) {
    for (int i = 0; i < items.length(); i++) {
        try {
            JSONObject itemObj = items.getJSONObject(i);

            ScannedItemDisplay item = new ScannedItemDisplay(
                    itemObj.optString("epc", ""),
                    itemObj.optString("title", ""),
                    itemObj.optString("karat", "—"),
                    Math.max(1, itemObj.optInt("count", 1)),
                    itemObj.optInt("rssi", -50),
                    itemObj.optString("time", ""));

            item.weight = itemObj.optDouble("weight", 0);
            item.price  = itemObj.optDouble("price", 0);

            // ✅ [إصلاح حرج] لا نضرب بـ count — القطعة الفيزيائية واحدة
            totalWeight += item.weight;
            totalValue  += item.price;

            scannedItemsDisplayList.add(item);
        } catch (Exception e) {
            Log.w(TAG, "Silent restore parse error at " + i, e);
        }
    }
}

            aggregateTotalWeight = totalWeight;
            aggregateTotalValue = totalValue;

            if (inventoryEngine != null) {
                for (ScannedItemDisplay item : scannedItemsDisplayList) {
                    if (item != null && item.epc != null) {
                        try {
                            inventoryEngine.addEpc(item.epc, item.rssi);
                        } catch (Exception ignored) {}
                    }
                }
            }

            if (modernAdapter != null) {
                updateAdapterList();
                modernAdapter.resetAnimationTracker();
            }

            if (!scannedItemsDisplayList.isEmpty()) {
                switchToActiveState(false);
            }

            this.expectedCount = expectedCount;
            if (tvExpectedCount != null) {
                tvExpectedCount.setText(expectedCount > 0
                        ? String.valueOf(expectedCount) : "—");
            }

            selectedCategoryFilter = (category == null || category.isEmpty())
                    ? null : category;
            lastScannedForAnimation = 0;

            updateUI();
            updateTotalWeightAndValue();

            if (tvScannedCount != null) {
                tvScannedCount.setText(String.valueOf(scannedItemsDisplayList.size()));
            }
            if (tvItemCount != null) {
                tvItemCount.setText(String.valueOf(scannedItemsDisplayList.size()));
            }

            if (tvEmptyState != null) {
                tvEmptyState.setVisibility(
                        scannedItemsDisplayList.isEmpty() ? View.VISIBLE : View.GONE);
            }

            updateButtonsState();

            Log.d(TAG, "✅ Silent restore complete: "
                    + scannedItemsDisplayList.size() + " items");

        } catch (Exception e) {
            Log.e(TAG, "applyRestoredSessionSilent error", e);
        }
    }

    private boolean shouldPromptForRestore() {
        try {
            if (hasTriedRestoringSession) return false;
            if (isScanning) return false;
            if (!scannedItemsDisplayList.isEmpty()) return false;
            if (inventoryEngine == null || inventoryEngine.getUniqueCount() > 0) return false;

            if (getContext() == null) return false;
            android.content.SharedPreferences prefs = requireContext()
                    .getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE);
            if (prefs.getBoolean(KEY_SKIP_RESTORE_PROMPT, false)) {
                Log.d(TAG, "⏭️ User chose to skip restore prompt");
                return false;
            }

            if (sessionManager != null && sessionManager.hasMeaningfulSession(
                    MIN_ITEMS_TO_PROMPT, MIN_SESSION_AGE_TO_PROMPT_MS)) {
                return true;
            }

            return false;
        } catch (Exception e) {
            Log.e(TAG, "shouldPromptForRestore error", e);
            return false;
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        try {
            if (!scannedItemsDisplayList.isEmpty()) {
                if (sessionManager != null) {
                    sessionManager.saveSession(expectedCount, scanStartTime,
                            selectedCategoryFilter, scannedItemsDisplayList);
                    Log.d(TAG, "✅ Session saved on onPause: "
                            + scannedItemsDisplayList.size() + " items");
                }
            }

            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).setActiveInventoryFragment(null);
            }
        } catch (Exception e) {
            Log.e(TAG, "onPause error", e);
        }
    }

    @Override
    public void onDestroyView() {
        Log.d(TAG, "onDestroyView: cleanup");

        try {
            if (!scannedItemsDisplayList.isEmpty()) {
                if (sessionManager != null) {
                    sessionManager.saveSession(expectedCount, scanStartTime,
                            selectedCategoryFilter, scannedItemsDisplayList);
                    Log.d(TAG, "✅ Session saved on onDestroyView: "
                            + scannedItemsDisplayList.size() + " items");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "save on destroy error", e);
        }
        
        if (scanSession != null) {
        scanSession.release();
        scanSession = null;
        }

        if (backPressedCallback != null) {
            backPressedCallback.remove();
            backPressedCallback = null;
        }

        isFragmentDestroyed = true;
        isScanning = false;

        try {
            mainHandler.removeCallbacks(uiUpdateRunnable);
            mainHandler.removeCallbacks(timerRunnable);
        } catch (Exception ignored) {}

        if (scanController != null) {
            try {
                scanController.cleanup();
            } catch (Exception e) {
                Log.e(TAG, "scanController cleanup error", e);
            }
        }

        if (soundManager != null) {
            try {
                soundManager.release();
            } catch (Exception ignored) {}
            soundManager = null;
        }

        if (feedbackHelper != null) {
            try {
                feedbackHelper.cleanup();
            } catch (Exception ignored) {}
            feedbackHelper = null;
        }

        if (filterManager != null) {
            try {
                filterManager.reset();
            } catch (Exception ignored) {}
        }

        try {
            modernAdapter = null;
            rvInventoryItems = null;
            scanController = null;
            persistenceManager = null;
            printManager = null;
        } catch (Exception ignored) {}

        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy: full cleanup");

        synchronized (scannedItemsDisplayList) {
            scannedItemsDisplayList.clear();
        }
        catalogEpcs.clear();

        if (catalogManager != null) {
            try { catalogManager.clear(); } catch (Exception ignored) {}
        }
        if (persistenceManager != null) {
            try { persistenceManager.cleanup(); } catch (Exception ignored) {}
        }

        backgroundExecutor = null;
        ioExecutor = null;
    }

    private void animateWelcomeLogo() {
        try {
            if (ivWelcomeLogo == null) return;

            ivWelcomeLogo.setAlpha(0f);
            ivWelcomeLogo.setScaleX(0.5f);
            ivWelcomeLogo.setScaleY(0.5f);

            ivWelcomeLogo.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(600)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.5f))
                    .start();

            mainHandler.postDelayed(() -> {
                if (isAdded() && !isFragmentDestroyed && ivWelcomeLogo != null) {
                    android.animation.ObjectAnimator pulse =
                            android.animation.ObjectAnimator.ofFloat(
                                    ivWelcomeLogo, "alpha", 1f, 0.7f, 1f);
                    pulse.setDuration(2000);
                    pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                    pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                    pulse.start();
                }
            }, 700);

        } catch (Exception e) {
            Log.e(TAG, "animateWelcomeLogo error", e);
        }
    }
@Override public Context getHostContext()  { return getContext(); }
@Override public Activity getHostActivity(){ return getActivity(); }
@Override public boolean isHostAlive() {
    return isAdded() && !isFragmentDestroyed;
}

@Override
public void onSessionTagAccepted(String epc, int rssi) {
    // لا شيء — نعالج في processTagReadDirect
}

@Override
public void onSessionLimitReached(int max) {
    showToast(isArabic
            ? "📊 وصلت للحد الأقصى (" + max + ")"
            : "📊 Max tags reached (" + max + ")");
    if (isScanning) pauseScanning();
}

@Override
public void onSessionShouldStop(String reason) {
    if ("single-mode".equals(reason)) {
        showToast(isArabic ? "🎯 تم" : "🎯 Done");
    }
    if (isScanning) pauseScanning();
}
} // ✅ نهاية الكلاس MiraInventoryProFragment
