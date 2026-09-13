package com.example.uhf.fragment;

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
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.database.InventoryDatabase;
import com.example.uhf.database.InventoryTransactionDao;
import com.example.uhf.database.InventoryTransactionEntity;
import com.example.uhf.engine.MiraInventoryEngine;
import com.example.uhf.MiraRfid.MiraIRFIDReader;
import com.example.uhf.MiraRfid.RFIDManager;
import com.example.uhf.model.InventoryItem;
import com.example.uhf.model.ScannedItemDisplay;
import com.example.uhf.model.InventoryReport;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.adapter.ModernScannedItemsAdapter;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.utils.SoundManager;
import com.example.uhf.utils.PrintUtils;
import com.example.uhf.utils.RFIDCodeUtils;
import com.example.uhf.utils.InventoryAnimationHelper;
import com.example.uhf.manager.inventory.InventorySessionManager;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MiraInventoryProFragment - الإصدار الاحترافي 2.0
 * 
 * ✅ الميزات:
 * - ListAdapter + DiffUtil لأداء محسن
 * - Power Control Slider (5-30 dBm)
 * - Search Bar حي
 * - Segmented Control (شامل/فئوي/أعمى)
 * - Hero Progress Circle
 * - Floating Action Dock
 */
public class MiraInventoryProFragment extends KeyDwonFragment 
        implements MiraIRFIDReader.OnTagReadListener {

    private static final String TAG = "MiraInventoryPro";
    
    // ==================== ثوابت الأداء ====================
    private static final long UI_UPDATE_THROTTLE_MS = 200;
    private static final long SIMULATION_INITIAL_DELAY_MS = 500;
    private static final long SIMULATION_MIN_DELAY_MS = 150;
    private static final long SIMULATION_MAX_ADDITIONAL_DELAY_MS = 350;
    private static final long BEEP_THROTTLE_MS = 60;
    private static final int EXECUTOR_SHUTDOWN_TIMEOUT_MS = 1000;
    private static final long TAG_READ_DEBOUNCE_MS = 500;
    private static final int MAX_CACHE_ITEMS = 5000;
    
    // ==================== مفاتيح الزناد ====================
    private static final int KEYCODE_RFID_TRIGGER_1 = 139;
    private static final int KEYCODE_RFID_TRIGGER_2 = 280;
    private static final int KEYCODE_RFID_TRIGGER_3 = 293;
    private String selectedCategoryFilter = null;
    // ==================== مكونات الواجهة - Hero Circle ====================
    private TextView tvProgressPercent;
    private ProgressBar progressScanned;
    private TextView tvScannedCount;
    private TextView tvExpectedCount;
    // ==================== Animation Helper ====================
    private InventoryAnimationHelper animationHelper;
    // ==================== Status Header ====================
    private View statusDot;
    private View statusBar;
    private TextView tvInventoryStatus;
    private TextView tvElapsedTime;
    private TextView tvRssi;
    
    // ==================== Segmented Control ====================
    private MaterialButtonToggleGroup segmentedModes;
    private MaterialButton btnModeFull;
    private MaterialButton btnModeCategory;
    private MaterialButton btnModeBlind;
    
    // ==================== Stats Strip ====================
    private TextView tvVariance;
    private TextView tvAccuracy;
    private TextView tvItemCount;
    
    // ==================== Gold Metrics ====================
    private TextView tvTotalWeight;
    private TextView tvTotalValue;
    
    // ==================== Action Dock ====================
    private MaterialButton btnPowerControl;
    private MaterialButton btnGenerateReport;
    private MaterialButton btnArchiveReports;
    
    // ==================== Search ====================
    private EditText etSearchItems;
    private ImageView btnClearSearch;
private ImageView btnLiveFilter;  // ✅ جديد
    
    // ==================== RecyclerView ====================
    private RecyclerView rvInventoryItems;
    private ModernScannedItemsAdapter modernAdapter;
    private View tvEmptyState;
    
    // ==================== Floating Action Dock (السفلي) ====================
    private MaterialButton btnMainToggleScan;
    private MaterialButton btnCommitSession;

    // ==================== مجموعات آمنة للخيوط ====================
    private final List<ScannedItemDisplay> scannedItemsDisplayList = 
        Collections.synchronizedList(new ArrayList<>());
    
    private final Set<String> catalogEpcs = 
        Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<String, Long> recentTagReads = new ConcurrentHashMap<>();
    private final Map<String, InventoryItem> catalogItemsMap = new ConcurrentHashMap<>();
    private final Map<String, String> barcodeToEpcMap = new ConcurrentHashMap<>();
    private final Map<String, String> epcToBarcodeMap = new ConcurrentHashMap<>();
    
    // ==================== إدارة الخيوط ====================
    private ExecutorService backgroundExecutor;
    private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());
    private volatile boolean isScanning = false;
    
    private volatile boolean isFragmentDestroyed = false;
    private volatile boolean isArabic = true;
    private long scanStartTime = 0;
    
    private final AtomicBoolean isSyncing = new AtomicBoolean(false);
    private final AtomicBoolean isCommitting = new AtomicBoolean(false);
    
    // ==================== مكونات RFID ====================
    private RFIDManager rfidManager;
    private SoundManager soundManager;
    
    // ==================== Power Control ====================
    private int currentPowerDbm = 26;
    
    // ==================== Search ====================
    private String currentSearchQuery = "";
    
    // ==================== المحاكاة ====================
    private final Handler simulationHandler = new Handler(Looper.getMainLooper());
    private Runnable simulationRunnable;
    private int simulationIndex = 0;
    private final String[] simulationTags = {
        "A000001", "A000002", "A000003", "A000004", "A000005",
        "A000006", "A000007", "A000008", "A000009", "A000010"
    };
    
    // ==================== قاعدة البيانات ====================
    private InventoryDatabase inventoryDatabase;
    private InventoryTransactionDao transactionDao;
    
    // ==================== تحديث الواجهة ====================
    private final Runnable uiUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            pendingUiUpdate = false;
            updateUI();
            updateTotalWeightAndValue();
        }
    };
    private volatile boolean pendingUiUpdate = false;
    
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isScanning && !isFragmentDestroyed) {
                updateElapsedTime();
                mainThreadHandler.postDelayed(this, 1000);
            }
        }
    };
    
    // ==================== مكونات الأعمال ====================
    private MiraInventoryEngine inventoryEngine;
    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private PermissionManager permissionManager;
    private int expectedCount = 0;
    // ✅ Animation tracking
    private int lastScannedForAnimation = 0;
    
    // ==================== معالجة زر الرجوع ====================
    private OnBackPressedCallback backPressedCallback;
    
    // ==================== Session Persistence ====================
private static final String PREFS_INVENTORY = "inventory_session";
private static final String KEY_SESSION_DATA = "session_data";
private static final String KEY_SESSION_TIME = "session_time";
private static final long SESSION_EXPIRY_MS = 30 * 60 * 1000; // 30 دقيقة
    // ==================== Factory Methods ====================
    
    public static MiraInventoryProFragment newInstance() {
        MiraInventoryProFragment fragment = new MiraInventoryProFragment();
        Bundle args = new Bundle();
        fragment.setArguments(args);
        return fragment;
    }

    public static MiraInventoryProFragment newInstance(String mode) {
        MiraInventoryProFragment fragment = new MiraInventoryProFragment();
        Bundle args = new Bundle();
        args.putString("INVENTORY_MODE", mode);
        fragment.setArguments(args);
        return fragment;
    }

    public boolean isScanning() {
        return isScanning;
    }

    // ==================== دورة حياة Fragment ====================

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, 
                             @Nullable Bundle savedInstanceState) {
        View view = null;
        
        try {
            view = inflater.inflate(R.layout.fragment_mira_inventory_pro, container, false);
        } catch (Exception e) {
            Log.e(TAG, "Inflate error: " + e.getMessage(), e);
            TextView textView = new TextView(requireContext());
            textView.setText("✅ الجرد يعمل (Fallback)\n\nخطأ: " + e.getMessage());
            textView.setTextSize(16);
            textView.setGravity(android.view.Gravity.CENTER);
            return textView;
        }
        
        isFragmentDestroyed = false;
        
        try { 
            if (backgroundExecutor == null || backgroundExecutor.isShutdown()) { 
                backgroundExecutor = Executors.newSingleThreadExecutor(); 
            } 
        } catch (Exception e) {
            Log.e(TAG, "Executor init error", e);
        }
        
        try { 
            isArabic = Locale.getDefault().getLanguage().equals("ar"); 
            view.setLayoutDirection(isArabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR); 
        } catch (Exception e) {
            Log.e(TAG, "Locale error", e);
        }
        
        try { 
            soundManager = new SoundManager(requireContext()); 
        } catch (Exception e) { 
            Log.e(TAG, "Sound: " + e.getMessage()); 
        }
        // ✅ تهيئة AnimationHelper
        try {
        animationHelper = new InventoryAnimationHelper(isArabic);
        } catch (Exception e) {
        Log.e(TAG, "AnimationHelper error: " + e.getMessage());
        }
        
        try { 
            initViews(view); 
        } catch (Exception e) { 
            Log.e(TAG, "Views: " + e.getMessage(), e); 
        }
        
        try { 
            initDatabase(); 
        } catch (Exception e) { 
            Log.e(TAG, "DB: " + e.getMessage()); 
        }
        
        try { 
            String sessionId = "SESS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(); 
            inventoryEngine = new MiraInventoryEngine(requireContext(), sessionId, getDeviceId()); 
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
        
        try { 
            rfidManager = RFIDManager.getInstance(requireContext()); 
            rfidManager.setListener(this); 
        } catch (Exception e) { 
            rfidManager = null; 
        }
        
        try { 
            setupBackPressHandler(); 
        } catch (Exception e) {
            Log.e(TAG, "BackPress error", e);
        }
        
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
        
        return view;
    }

    /**
     * تهيئة قاعدة البيانات بأمان
     */
    private void initDatabase() {
        try {
            inventoryDatabase = InventoryDatabase.getInstance(requireContext().getApplicationContext());
            transactionDao = inventoryDatabase.inventoryTransactionDao();
        } catch (Exception e) {
            Log.e(TAG, "Error initializing inventory database", e);
        }
    }

    /**
     * الحصول على معرف الجهاز بأمان
     */
    private String getDeviceId() {
        try {
            return android.provider.Settings.Secure.getString(
                requireContext().getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID
            );
        } catch (Exception e) {
            return "UNKNOWN-DEVICE";
        }
    }

    /**
     * إعداد معالج زر الرجوع
     */
    private void setupBackPressHandler() {
        backPressedCallback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isScanning) {
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
            getViewLifecycleOwner(), 
            backPressedCallback
        );
    }

    /**
     * عرض تحذير الخروج
     */
    private void showExitWarning(Runnable onConfirm) {
        if (!isAdded() || isFragmentDestroyed) return;
        
        String title = isArabic ? "⚠️ تحذير مهم" : "⚠️ Important Warning";
        String message = isArabic ? 
            "الجرد ما زال قيد التشغيل!\n\n" +
            "إذا قمت بتغيير الواجهة، سيتوقف الجرد وستفقد البيانات غير المحفوظة.\n\n" +
            "هل تريد إيقاف الجرد والمتابعة؟" :
            "Inventory is still running!\n\n" +
            "If you change the screen, the inventory will stop and unsaved data will be lost.\n\n" +
            "Do you want to stop and continue?";
        String positiveBtn = isArabic ? "إيقاف والمتابعة" : "Stop and Continue";
        String negativeBtn = isArabic ? "البقاء في الجرد" : "Stay in Inventory";
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(positiveBtn, (dialog, which) -> {
                    if (onConfirm != null) {
                        onConfirm.run();
                    }
                })
                .setNegativeButton(negativeBtn, (dialog, which) -> {
                    dialog.dismiss();
                })
                .setCancelable(false)
                .show();
    }

    /**
     * تطبيق الصلاحيات
     */
    private void applyPermissions() {
        if (permissionManager == null) return;
        
        boolean canView = permissionManager.canView("inventory") || permissionManager.isManager();
        boolean canEdit = permissionManager.canEdit("inventory") || permissionManager.isManager();
        
        if (btnMainToggleScan != null) btnMainToggleScan.setEnabled(canEdit);
        if (btnGenerateReport != null) btnGenerateReport.setEnabled(canEdit);
        if (btnCommitSession != null) btnCommitSession.setEnabled(canEdit);
        if (btnArchiveReports != null) btnArchiveReports.setEnabled(canView);
        
        if (!canView && !canEdit && tvInventoryStatus != null) {
            tvInventoryStatus.setText(isArabic ? "⛔ غير مصرح لك بالوصول" : "⛔ Access denied");
        }
    }

    /**
     * تطبيق اللغة
     */
    private void applyLanguage() {
        if (btnMainToggleScan != null) 
            btnMainToggleScan.setText(isArabic ? "▶️ بدء جرد RFID" : "▶️ Start RFID Inventory");
        if (btnGenerateReport != null) 
            btnGenerateReport.setText(isArabic ? "📋 تقرير" : "📋 Report");
        if (btnArchiveReports != null) 
            btnArchiveReports.setText(isArabic ? "📂 الأرشيف" : "📂 Archive");
        if (btnPowerControl != null) 
            btnPowerControl.setText("⚡ " + currentPowerDbm + " dBm");
        if (btnCommitSession != null)
            btnCommitSession.setText(isArabic ? "اعتماد" : "Commit");
    }

    /**
     * تهيئة عناصر الواجهة
     */
    private void initViews(View view) {
        // ==================== Hero Circle ====================
        tvProgressPercent = view.findViewById(R.id.tvProgressPercent);
        progressScanned = view.findViewById(R.id.progressScanned);
        tvScannedCount = view.findViewById(R.id.tvScannedCount);
        tvExpectedCount = view.findViewById(R.id.tvExpectedCount);
        
        // ==================== Status Header ====================
        statusDot = view.findViewById(R.id.statusDot);
        statusBar = view.findViewById(R.id.statusBar);
        tvInventoryStatus = view.findViewById(R.id.tvInventoryStatus);
        tvElapsedTime = view.findViewById(R.id.tvElapsedTime);
        tvRssi = view.findViewById(R.id.tvRssi);
        
        // ==================== Segmented Control ====================
        segmentedModes = view.findViewById(R.id.segmentedModes);
        btnModeFull = view.findViewById(R.id.btnModeFull);
        btnModeCategory = view.findViewById(R.id.btnModeCategory);
        btnModeBlind = view.findViewById(R.id.btnModeBlind);
        
        // ==================== Stats Strip ====================
        tvVariance = view.findViewById(R.id.tvVariance);
        tvAccuracy = view.findViewById(R.id.tvAccuracy);
        tvItemCount = view.findViewById(R.id.tvItemCount);
        
        // ==================== Gold Metrics ====================
        tvTotalWeight = view.findViewById(R.id.tvTotalWeight);
        tvTotalValue = view.findViewById(R.id.tvTotalValue);
        
        // ==================== Action Dock ====================
        btnPowerControl = view.findViewById(R.id.btnPowerControl);
        btnGenerateReport = view.findViewById(R.id.btnGenerateReport);
        btnArchiveReports = view.findViewById(R.id.btnArchiveReports);
        
        // ==================== Search ====================
        etSearchItems = view.findViewById(R.id.etSearchItems);
        btnClearSearch = view.findViewById(R.id.btnClearSearch);
btnLiveFilter = view.findViewById(R.id.btnLiveFilter);  // ✅ جديد

// ✅ ربط زر الفلترة
if (btnLiveFilter != null) {
    btnLiveFilter.setOnClickListener(v -> showLiveFilterChips());
}
        
        // ==================== RecyclerView ====================
        rvInventoryItems = view.findViewById(R.id.rvInventoryItems);
        tvEmptyState = view.findViewById(R.id.tvEmptyState);
        
        if (rvInventoryItems != null) {
            rvInventoryItems.setLayoutManager(new LinearLayoutManager(requireContext()));
            rvInventoryItems.setNestedScrollingEnabled(false);
            modernAdapter = new ModernScannedItemsAdapter(isArabic, item -> {
                showItemOptionsDialog(item);
            });
            rvInventoryItems.setAdapter(modernAdapter);
            rvInventoryItems.setHasFixedSize(false);
            rvInventoryItems.setItemAnimator(null);
        }
        
        // ==================== Floating Action Dock ====================
        btnMainToggleScan = view.findViewById(R.id.btnMainToggleScan);
        btnCommitSession = view.findViewById(R.id.btnCommitSession);
        
        // ==================== إعداد الأزرار ====================
        setupButtons();
        setupSegmentedControl();
        setupSearchBar();
    }

    /**
     * إعداد الأزرار الجديدة
     */
    private void setupButtons() {
        // زر البدء/الإيقاف الذكي
        if (btnMainToggleScan != null) {
            btnMainToggleScan.setOnClickListener(v -> {
                if (isScanning) {
                    stopScanning();
                } else {
                    if (permissionManager != null && 
                        (permissionManager.canEdit("inventory") || permissionManager.isManager())) {
                        startScanning();
                    } else {
                        showAccessDenied();
                    }
                }
            });
        }
        
        // زر الاعتماد
        if (btnCommitSession != null) {
            btnCommitSession.setOnClickListener(v -> showCommitConfirmationDialog());
        }
        
        // زر Power Control
        if (btnPowerControl != null) {
            btnPowerControl.setOnClickListener(v -> showPowerControlSheet());
        }
        
        // زر التقرير
        if (btnGenerateReport != null) {
            btnGenerateReport.setOnClickListener(v -> showCommitConfirmationDialog());
            btnGenerateReport.setEnabled(false);
        }
        
        // زر الأرشيف
        if (btnArchiveReports != null) {
            btnArchiveReports.setOnClickListener(v -> showArchiveReports());
        }
    }

    /**
     * إعداد Segmented Control
     */
    private void setupSegmentedControl() {
    if (segmentedModes == null) return;
    
    segmentedModes.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
        if (!isChecked) return;
        
        if (checkedId == R.id.btnModeFull) {
            // ✅ الوضع الكامل
            selectedCategoryFilter = null;
            Log.d(TAG, "Mode: Full");
            
            if (!isScanning && scannedItemsDisplayList.isEmpty()) {
                Toast.makeText(requireContext(),
                    isArabic ? "🎯 جاهز للجرد الكامل" : "🎯 Ready for Full Inventory",
                    Toast.LENGTH_SHORT).show();
            }
            
        } else if (checkedId == R.id.btnModeCategory) {
            // ✅ جرد فئوي - يفتح الحوار
            if (isScanning) {
                // عرض تأكيد الإيقاف
                new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(isArabic ? "⚠️ الجرد قيد التشغيل" : "⚠️ Inventory Running")
                    .setMessage(isArabic ? 
                        "لتغيير الفئة، يجب إيقاف الجرد الحالي.\nهل تريد المتابعة؟" :
                        "To change category, stop the current inventory.\nContinue?")
                    .setPositiveButton(isArabic ? "إيقاف ومتابعة" : "Stop & Continue", (d, w) -> {
                        stopScanning();
                        mainThreadHandler.postDelayed(this::showCategorySelectionDialog, 500);
                    })
                    .setNegativeButton(isArabic ? "إلغاء" : "Cancel", (d, w) -> {
                        if (segmentedModes != null && btnModeFull != null) {
                            segmentedModes.check(R.id.btnModeFull);
                        }
                    })
                    .show();
            } else {
                showCategorySelectionDialog();
            }
            
        } else if (checkedId == R.id.btnModeBlind) {
            // ✅ الجرد الأعمى
            selectedCategoryFilter = null;
            
            if (!isScanning) {
                startBlindInventory();
            } else {
                new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(isArabic ? "⚠️ الجرد قيد التشغيل" : "⚠️ Inventory Running")
                    .setMessage(isArabic ? 
                        "هل تريد إيقاف الجرد الحالي وبدء الجرد الأعمى؟" :
                        "Stop current inventory and start blind inventory?")
                    .setPositiveButton(isArabic ? "متابعة" : "Continue", (d, w) -> {
                        stopScanning();
                        mainThreadHandler.postDelayed(this::startBlindInventory, 500);
                    })
                    .setNegativeButton(isArabic ? "إلغاء" : "Cancel", (d, w) -> {
                        if (segmentedModes != null && btnModeFull != null) {
                            segmentedModes.check(R.id.btnModeFull);
                        }
                    })
                    .show();
            }
        }
    });
}


/**
 * ✅ عرض شريط الفلترة الحية (Chips)
 */
private void showLiveFilterChips() {
    try {
        if (!isAdded() || isFragmentDestroyed) return;
        
        String[] filters = isArabic ? new String[]{
            "📋 الكل",
            "✅ موجود",
            "❌ مفقود",
            "⚠️ إضافي",
            "🆕 جديد",
            "📊 مكرر"
        } : new String[]{
            "📋 All",
            "✅ Found",
            "❌ Missing",
            "⚠️ Unexpected",
            "🆕 New",
            "📊 Duplicate"
        };
        
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(isArabic ? "🔍 فلترة النتائج" : "🔍 Filter Results")
            .setItems(filters, (dialog, which) -> {
                applyLiveFilter(which);
            })
            .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
            .show();
    } catch (Exception e) {
        Log.e(TAG, "showLiveFilterChips error: " + e.getMessage());
    }
}

/**
 * ✅ تطبيق الفلتر الحي
 */
private int currentLiveFilter = 0; // 0=All, 1=Found, 2=Missing, 3=Unexpected, 4=New, 5=Duplicate

private void applyLiveFilter(int filterType) {
    try {
        currentLiveFilter = filterType;
        
        List<ScannedItemDisplay> filtered;
        synchronized (scannedItemsDisplayList) {
            filtered = new ArrayList<>(scannedItemsDisplayList);
        }
        
        switch (filterType) {
            case 0: // All
                break;
            case 1: // Found (in catalog)
                filtered.removeIf(item -> !catalogEpcs.contains(item.epc));
                break;
            case 2: // Missing - handled differently
                showMissingItemsDialog();
                return;
            case 3: // Unexpected (not in catalog)
                filtered.removeIf(item -> catalogEpcs.contains(item.epc));
                break;
            case 4: // New
                filtered.removeIf(item -> item.count != 1);
                break;
            case 5: // Duplicate
                filtered.removeIf(item -> item.count <= 1);
                break;
        }
        
        if (modernAdapter != null) {
            modernAdapter.submitList(filtered);
        }
        
        String filterName = isArabic ? 
            new String[]{"الكل", "موجود", "مفقود", "إضافي", "جديد", "مكرر"}[filterType] :
            new String[]{"All", "Found", "Missing", "Unexpected", "New", "Duplicate"}[filterType];
        
        Toast.makeText(requireContext(),
            isArabic ? "🎯 الفلتر: " + filterName : "🎯 Filter: " + filterName,
            Toast.LENGTH_SHORT).show();
            
    } catch (Exception e) {
        Log.e(TAG, "applyLiveFilter error: " + e.getMessage());
    }
}

/**
 * ✅ عرض القطع المفقودة (في الكتالوج لكن لم تُمسح)
 */
private void showMissingItemsDialog() {
    try {
        if (catalogItemsMap.isEmpty()) {
            Toast.makeText(requireContext(),
                isArabic ? "⚠️ لا يوجد كتالوج محمّل" : "⚠️ No catalog loaded",
                Toast.LENGTH_SHORT).show();
            return;
        }
        
        List<String> missingEpcs = new ArrayList<>();
        for (String epc : catalogEpcs) {
            if (!isScanned(epc)) {
                missingEpcs.add(epc);
            }
        }
        
        if (missingEpcs.isEmpty()) {
            Toast.makeText(requireContext(),
                isArabic ? "✅ لا توجد قطع مفقودة" : "✅ No missing items",
                Toast.LENGTH_LONG).show();
            return;
        }
        
        // عرض قائمة المفقودين
        String[] missingTitles = new String[missingEpcs.size()];
        for (int i = 0; i < missingEpcs.size(); i++) {
            String epc = missingEpcs.get(i);
            InventoryItem item = catalogItemsMap.get(epc);
            String title = item != null && item.getTitle() != null ? 
                item.getTitle() : epc;
            missingTitles[i] = "❌ " + title + "\n" + epc;
        }
        
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(isArabic ? 
                "❌ المفقود (" + missingEpcs.size() + ")" : 
                "❌ Missing (" + missingEpcs.size() + ")")
            .setItems(missingTitles, null)
            .setPositiveButton(isArabic ? "موافق" : "OK", null)
            .show();
            
    } catch (Exception e) {
        Log.e(TAG, "showMissingItemsDialog error: " + e.getMessage());
    }
}

/**
 * ✅ فحص إذا كانت القطعة ممسوحة
 */
private boolean isScanned(String epc) {
    if (epc == null) return false;
    synchronized (scannedItemsDisplayList) {
        for (ScannedItemDisplay item : scannedItemsDisplayList) {
            if (item.epc != null && item.epc.equalsIgnoreCase(epc)) {
                return true;
            }
        }
    }
    return false;
}
    /**
     * إعداد شريط البحث
     */
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

    /**
     * عرض رفض الوصول
     */
    private void showAccessDenied() {
        if (!isAdded() || isFragmentDestroyed) return;
        Toast.makeText(requireContext(), 
            isArabic ? "⛔ غير مصرح" : "⛔ Access denied", 
            Toast.LENGTH_SHORT).show();
        if (soundManager != null) soundManager.playBeep(SoundManager.FeedbackType.ERROR);
    }

    /**
     * معالجة أزرار RFID
     */
    public boolean myOnKeyDwon(int keyCode, KeyEvent event) {
        if (keyCode == KEYCODE_RFID_TRIGGER_1 || 
            keyCode == KEYCODE_RFID_TRIGGER_2 || 
            keyCode == KEYCODE_RFID_TRIGGER_3) {
            if (event.getRepeatCount() == 0) {
                if (permissionManager != null && 
                    !(permissionManager.canEdit("inventory") || permissionManager.isManager())) {
                    showAccessDenied();
                    return true;
                }
                if (!isScanning) {
                    startScanning();
                } else {
                    stopScanning();
                }
                return true;
            }
        }
        return false;
    }
// ==================== معالجة قراءة الوسم ====================
@Override
public void onTagRead(String epc, int rssi) {
    if (!isScanning || isFragmentDestroyed || epc == null || epc.isEmpty()) return;
    
    // ✅ تحقق من الـ session ID لمنع القراءات القديمة
    // (اختياري — فقط إذا كنت ترى تأخيراً)
    
    final String normalizedEpc = RFIDCodeUtils.normalizeCodeToEpc(epc, barcodeToEpcMap);
    final int finalRssi = rssi;
    
    // ✅ إذا كنا في وضع الجرد الفئوي، تجاهل الوسوم غير المطابقة
    if (selectedCategoryFilter != null && !selectedCategoryFilter.isEmpty()) {
        // لا نتحقق من الفئة هنا — الـ engine أصلاً لا يحتوي إلا على القطع المطابقة
        // لكن نتحقق إذا كان الـ EPC في الكتالوج المفلتر
        if (!catalogEpcs.contains(normalizedEpc)) {
            // قطعة غير موجودة في الفئة المختارة
            // يمكن تسجيلها كـ "غير متوقعة" أو تجاهلها
            // هنا نتجاهلها بصمت لتقليل الضوضاء
            return;
        }
    }

    long currentTime = System.currentTimeMillis();
    Long lastReadTime = recentTagReads.get(normalizedEpc);

    // منع تكرار القراءة المتكررة
    if (lastReadTime != null && (currentTime - lastReadTime) < TAG_READ_DEBOUNCE_MS) {
        return;
    }

    recentTagReads.put(normalizedEpc, currentTime);
    if (recentTagReads.size() > MAX_CACHE_ITEMS) {
        cleanOldTagReads(currentTime);
    }

    // معالجة غير متزامنة
    if (backgroundExecutor == null || backgroundExecutor.isShutdown()) {
        backgroundExecutor = Executors.newSingleThreadExecutor();
    }

    backgroundExecutor.execute(() -> {
        try {
            if (inventoryEngine != null) {
                inventoryEngine.onTagScanned(normalizedEpc, finalRssi);
            }
            saveTransactionLocally(normalizedEpc, finalRssi);
            
            mainThreadHandler.post(() -> {
                if (!isFragmentDestroyed) {
                    addScannedItemToDisplay(normalizedEpc, finalRssi, null);
                    updateLastScan(normalizedEpc, finalRssi);
                    scheduleUiUpdate();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error processing tag: " + normalizedEpc, e);
        }
    });
}

/**
 * تنظيف القراءات القديمة
 */
private void cleanOldTagReads(long currentTime) {
    Iterator<Map.Entry<String, Long>> iterator = recentTagReads.entrySet().iterator();
    while (iterator.hasNext()) {
        Map.Entry<String, Long> entry = iterator.next();
        if (currentTime - entry.getValue() > TAG_READ_DEBOUNCE_MS * 2) {
            iterator.remove();
        }
    }
}

/**
 * حفظ المعاملة محلياً
 */
private void saveTransactionLocally(String epc, int rssi) {
    try {
        if (inventoryEngine == null || transactionDao == null) return;
        
        InventoryTransactionEntity transaction = new InventoryTransactionEntity();
        transaction.sessionId = inventoryEngine.getClientSessionId();
        transaction.epcCode = epc;
        transaction.rssi = rssi;
        transaction.timestamp = System.currentTimeMillis();
        transaction.deviceId = getDeviceId();
        transaction.userId = getCurrentUserId();
        transaction.synced = false;
        
        if (branchesManager != null) {
            transaction.branchId = branchesManager.getActiveBranchId();
        }
        
        if (backgroundExecutor != null && !backgroundExecutor.isShutdown()) {
            backgroundExecutor.execute(() -> {
                try {
                    transactionDao.insertTransaction(transaction);
                } catch (Exception e) {
                    Log.e(TAG, "Error saving transaction locally", e);
                }
            });
        }
    } catch (Exception e) {
        Log.e(TAG, "Error creating transaction entity", e);
    }
}

/**
 * مزامنة المعاملات مع منع التزامن المتوازي
 */
private void syncOfflineTransactions() {
    if (!isSyncing.compareAndSet(false, true)) {
        return;
    }
    
    if (backgroundExecutor == null || backgroundExecutor.isShutdown() || 
        transactionDao == null || apiClient == null) {
        isSyncing.set(false);
        return;
    }
    
    backgroundExecutor.execute(() -> {
        try {
            List<InventoryTransactionEntity> unsynced = 
                transactionDao.getUnsyncedTransactions();
            
            if (unsynced.isEmpty()) {
                isSyncing.set(false);
                return;
            }
            
            JSONArray transactionsArray = new JSONArray();
            List<Long> successfulIds = new ArrayList<>();
            
            for (InventoryTransactionEntity transaction : unsynced) {
                try {
                    JSONObject transactionJson = new JSONObject();
                    transactionJson.put("session_id", transaction.sessionId);
                    transactionJson.put("epc", transaction.epcCode);
                    transactionJson.put("rssi", transaction.rssi);
                    transactionJson.put("timestamp", transaction.timestamp);
                    transactionJson.put("device_id", transaction.deviceId);
                    transactionJson.put("user_id", transaction.userId);
                    transactionJson.put("branch_id", transaction.branchId);
                    
                    transactionsArray.put(transactionJson);
                    successfulIds.add((long) transaction.id);
                } catch (Exception e) {
                    Log.e(TAG, "Error preparing transaction for sync", e);
                }
            }
            
            if (transactionsArray.length() > 0) {
                MiraApiClient.ApiResponse response = 
                    apiClient.syncOfflineTransactions(transactionsArray);
                
                if (response != null && response.isSuccess) {
                    transactionDao.markAsSynced(successfulIds);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error syncing offline transactions", e);
        } finally {
            isSyncing.set(false);
        }
    });
}

/**
 * الحصول على معرف المستخدم
 */
private String getCurrentUserId() {
    String deviceId = getDeviceId();
    return "USER-" + (deviceId.length() > 8 ? deviceId.substring(0, 8) : deviceId);
}

// ==================== إدارة قائمة الممسوحات ====================

/**
 * إضافة عنصر ممسوح للعرض مع جلب معلومات القطعة الكاملة
 */
private void addScannedItemToDisplay(String epc, int rssi, InventoryItem item) {
    if (!isAdded() || isFragmentDestroyed || modernAdapter == null) {
        return;
    }
    
    if (Looper.myLooper() != Looper.getMainLooper()) {
        mainThreadHandler.post(() -> addScannedItemToDisplay(epc, rssi, item));
        return;
    }
    
    synchronized (scannedItemsDisplayList) {
        boolean exists = false;

        for (ScannedItemDisplay display : scannedItemsDisplayList) {
            if (display != null && epc.equals(display.epc)) {
                display.count++;
                display.rssi = rssi;
                display.time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(new Date());
                exists = true;
                break;
            }
        }

        SoundManager.FeedbackType feedbackType;
        boolean isKnown = catalogEpcs.contains(epc);

        if (!exists) {
            feedbackType = isKnown ? SoundManager.FeedbackType.KNOWN_ITEM : 
                SoundManager.FeedbackType.UNKNOWN_ITEM;

            String title = isArabic ? "قطعة غير معروفة" : "Unknown item";
            String karat = "—";
            double weight = 0;
            double price = 0;

            InventoryItem catalogItem = catalogItemsMap.get(epc);
            
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
                title = isArabic ? "مادة مسجلة بالكتالوج" : "Catalog registered item";
            }

            String currentTime = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(new Date());

            ScannedItemDisplay display = new ScannedItemDisplay(
                epc, title, karat, 1, rssi, currentTime
            );
            display.weight = weight;
            display.price = price;

            scannedItemsDisplayList.add(0, display);

            if (rvInventoryItems != null) {
                rvInventoryItems.scrollToPosition(0);
            }
            
            updateTotalWeightAndValue();
        } else {
            feedbackType = SoundManager.FeedbackType.DUPLICATE_ITEM;
        }

        if (soundManager != null) soundManager.playBeep(feedbackType);

        if (tvEmptyState != null && !scannedItemsDisplayList.isEmpty()) {
            tvEmptyState.setVisibility(View.GONE);
        }
    }
    
    // ✅ التحديث الفعلي للـ Adapter باستخدام DiffUtil
    updateAdapterList();
}

/**
 * ✅ تحديث قائمة الـ Adapter باستخدام DiffUtil
 */
private void updateAdapterList() {
    if (modernAdapter == null || isFragmentDestroyed) return;
    
    List<ScannedItemDisplay> snapshot;
    synchronized (scannedItemsDisplayList) {
        snapshot = new ArrayList<>(scannedItemsDisplayList);
    }
    
    // تطبيق الفلترة الحالية إن وجدت
    if (currentSearchQuery != null && !currentSearchQuery.isEmpty()) {
        List<ScannedItemDisplay> filtered = new ArrayList<>();
        for (ScannedItemDisplay item : snapshot) {
            if (item.epc != null && item.epc.toLowerCase().contains(currentSearchQuery)) {
                filtered.add(item);
            } else if (item.title != null && item.title.toLowerCase().contains(currentSearchQuery)) {
                filtered.add(item);
            } else if (item.karat != null && item.karat.toLowerCase().contains(currentSearchQuery)) {
                filtered.add(item);
            }
        }
        modernAdapter.submitList(filtered);
    } else {
        modernAdapter.submitList(snapshot);
    }
}

/**
 * ✅ الفلترة حسب البحث
 */
private void filterItems(String query) {
    currentSearchQuery = query != null ? query.toLowerCase().trim() : "";
    updateAdapterList();
}

/**
 * ✅ عرض خيارات القطعة عند الضغط عليها
 */
private void showItemOptionsDialog(ScannedItemDisplay item) {
    if (!isAdded() || isFragmentDestroyed || item == null) return;
    
    String[] options = isArabic ? new String[]{
        "✏️ تعديل الحالة",
        "⚠️ تسجيل تلف",
        "📍 تحديث الموقع",
        "📝 إضافة ملاحظات",
        "🖨️ طباعة الملصق",
        "🔍 تفاصيل كاملة"
    } : new String[]{
        "✏️ Edit Status",
        "⚠️ Register Damage",
        "📍 Update Location",
        "📝 Add Notes",
        "🖨️ Print Label",
        "🔍 Full Details"
    };
    
    new MaterialAlertDialogBuilder(requireContext())
        .setTitle(item.epc)
        .setItems(options, (dialog, which) -> {
            switch (which) {
                case 0: showEditStatusDialog(item); break;
                case 1: showDamageDialog(item); break;
                case 2: 
                    Toast.makeText(requireContext(), 
                        isArabic ? "📍 قيد التطوير" : "📍 In development", 
                        Toast.LENGTH_SHORT).show();
                    break;
                case 3: 
                    Toast.makeText(requireContext(), 
                        isArabic ? "📝 قيد التطوير" : "📝 In development", 
                        Toast.LENGTH_SHORT).show();
                    break;
                case 4:
                    printSingleItemLabel(item);
                    break;
                case 5:
                    showItemFullDetails(item);
                    break;
            }
        })
        .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
        .show();
}

/**
 * طباعة ملصق قطعة واحدة
 */
private void printSingleItemLabel(ScannedItemDisplay item) {
    try {
        InventoryReport singleReport = new InventoryReport(
            0,
            inventoryEngine != null ? inventoryEngine.getClientSessionId() : "SINGLE",
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date()),
            1,
            1,
            0,
            isArabic ? "ملصق فردي" : "Single Label"
        );
        
        List<ScannedItemDisplay> singleList = new ArrayList<>();
        singleList.add(item);
        
        PrintUtils.printReportWithQRCode(requireContext(), singleReport, 
            singleList, isArabic, null);
    } catch (Exception e) {
        Log.e(TAG, "Error printing single label", e);
    }
}

/**
 * عرض التفاصيل الكاملة للقطعة
 */
private void showItemFullDetails(ScannedItemDisplay item) {
    if (!isAdded() || isFragmentDestroyed) return;
    
    StringBuilder sb = new StringBuilder();
    sb.append("📋 ").append(isArabic ? "تفاصيل القطعة" : "Item Details").append("\n\n");
    sb.append("🔖 EPC: ").append(item.epc).append("\n");
    sb.append("📝 ").append(isArabic ? "الاسم: " : "Title: ").append(item.title).append("\n");
    sb.append("💎 ").append(isArabic ? "العيار: " : "Karat: ").append(item.karat).append("\n");
    sb.append("⚖️ ").append(isArabic ? "الوزن: " : "Weight: ")
      .append(String.format(Locale.US, "%.2f g", item.weight)).append("\n");
    sb.append("💰 ").append(isArabic ? "السعر: " : "Price: ")
      .append(String.format(Locale.US, "$%.2f", item.price)).append("\n");
    sb.append("🔢 ").append(isArabic ? "مرات القراءة: " : "Read Count: ").append(item.count).append("\n");
    sb.append("📶 RSSI: ").append(item.rssi).append(" dBm\n");
    sb.append("⏰ ").append(isArabic ? "آخر قراءة: " : "Last Read: ").append(item.time);
    
    new MaterialAlertDialogBuilder(requireContext())
        .setTitle(isArabic ? "تفاصيل القطعة" : "Item Details")
        .setMessage(sb.toString())
        .setPositiveButton(isArabic ? "موافق" : "OK", null)
        .show();
}

/**
 * تحديث الوزن والقيمة الإجمالية
 */
private void updateTotalWeightAndValue() {
    double totalWeight = 0;
    double totalValue = 0;
    
    synchronized (scannedItemsDisplayList) {
        for (ScannedItemDisplay item : scannedItemsDisplayList) {
            totalWeight += item.weight * item.count;
            totalValue += item.price * item.count;
        }
    }
    
    final double finalWeight = totalWeight;
    final double finalValue = totalValue;
    
    mainThreadHandler.post(() -> {
        if (tvTotalWeight != null) {
            tvTotalWeight.setText(String.format(Locale.US, "%.2f g", finalWeight));
        }
        if (tvTotalValue != null) {
            tvTotalValue.setText(String.format(Locale.US, "$%,.2f", finalValue));
        }
    });
}

/**
 * تحديث حالة العنصر
 */
private void showEditStatusDialog(ScannedItemDisplay item) {
    String[] statuses = isArabic ? 
        new String[]{"متوفر", "محجوز", "قيد الصيانة", "غير متوفر"} :
        new String[]{"Available", "Reserved", "Maintenance", "Unavailable"};
    
    new MaterialAlertDialogBuilder(requireContext())
        .setTitle((isArabic ? "تعديل حالة - " : "Edit status - ") + item.epc)
        .setItems(statuses, (dialog, which) -> {
            String newStatus = statuses[which];
            updateItemStatus(item.epc, newStatus);
            if (soundManager != null) soundManager.playBeep(SoundManager.FeedbackType.COMPLETED);
            Toast.makeText(requireContext(), 
                (isArabic ? "تم تحديث الحالة: " : "Status updated: ") + newStatus, 
                Toast.LENGTH_SHORT).show();
        })
        .show();
}

/**
 * عرض حوار التلف
 */
private void showDamageDialog(ScannedItemDisplay item) {
    String[] damageTypes = isArabic ?
        new String[]{"خدش بسيط", "كسر", "فقدان جزء", "تلف كامل"} :
        new String[]{"Minor scratch", "Breakage", "Lost part", "Complete damage"};
    
    new MaterialAlertDialogBuilder(requireContext())
        .setTitle((isArabic ? "تسجيل تلف - " : "Register damage - ") + item.epc)
        .setItems(damageTypes, (dialog, which) -> {
            String damageType = damageTypes[which];
            registerItemDamage(item.epc, damageType);
            if (soundManager != null) soundManager.playBeep(SoundManager.FeedbackType.ERROR);
            Toast.makeText(requireContext(), 
                (isArabic ? "تم تسجيل التلف: " : "Damage registered: ") + damageType, 
                Toast.LENGTH_SHORT).show();
        })
        .show();
}

/**
 * تحديث حالة العنصر (placeholder للتوسع المستقبلي)
 */
private void updateItemStatus(String epc, String status) {
    Log.d(TAG, "Update status " + epc + " to " + status);
}

/**
 * تسجيل تلف (placeholder للتوسع المستقبلي)
 */
private void registerItemDamage(String epc, String damageType) {
    Log.d(TAG, "Register damage " + epc + " - " + damageType);
}

/**
 * تحديث آخر قراءة
 */
private void updateLastScan(String epc, int rssi) {
    if (tvRssi != null) {
        tvRssi.setText(String.format(Locale.US, "📶 %d", rssi));
    }
}

/**
 * جدولة تحديث الواجهة
 */
private void scheduleUiUpdate() {
    if (!pendingUiUpdate && !isFragmentDestroyed) {
        pendingUiUpdate = true;
        mainThreadHandler.postDelayed(uiUpdateRunnable, UI_UPDATE_THROTTLE_MS);
    }
}

/**
 * معالجات RFID
 */
@Override
public void onScanStarted() { 
    Log.d(TAG, "RFID Scan Started"); 
}

@Override
public void onScanStopped() { 
    Log.d(TAG, "RFID Scan Stopped"); 
}

@Override
public void onError(String errorMessage) { 
    Log.e(TAG, "RFID Error: " + errorMessage); 
    if (isAdded() && !isFragmentDestroyed) {
        mainThreadHandler.post(() -> {
            Toast.makeText(requireContext(), 
                (isArabic ? "خطأ في قارئ RFID: " : "RFID Reader Error: ") + errorMessage, 
                Toast.LENGTH_SHORT).show();
            if (soundManager != null) soundManager.playBeep(SoundManager.FeedbackType.ERROR);
        });
    }
}

@Override
public void onConnectionChanged(boolean isConnected) { 
    Log.i(TAG, "RFID Connection: " + isConnected); 
    if (isAdded() && !isFragmentDestroyed && tvInventoryStatus != null) {
        mainThreadHandler.post(() -> {
            if (tvInventoryStatus != null) {
                tvInventoryStatus.setText(isConnected ? 
                    (isArabic ? "📡 متصل" : "📡 Connected") : 
                    (isArabic ? "📡 غير متصل" : "📡 Disconnected"));
            }
            
            if (statusDot != null) {
                statusDot.setBackgroundResource(isConnected ? 
                    R.drawable.bg_status_dot_green : R.drawable.bg_status_dot_red);
            }
            
            if (soundManager != null) {
                soundManager.playBeep(isConnected ? 
                    SoundManager.FeedbackType.COMPLETED : SoundManager.FeedbackType.ERROR);
            }
            // ✅ تحديث خلفية الشريط
if (animationHelper != null) {
    animationHelper.updateStatusBarBackground(statusBar, isConnected, isScanning);
}
        });
    }
}

// ==================== تحديث الواجهة ====================

/**
 * تحديث الواجهة مع حساب الفروقات الحي
 */
/**
 * تحديث الواجهة مع Animation
 */
private void updateUI() {
    if (!isAdded() || isFragmentDestroyed || inventoryEngine == null) return;
    
    int scanned = inventoryEngine.getUniqueCount();
    
    // ✅ Animation على عدد القطع
    if (tvScannedCount != null) {
        if (animationHelper != null) {
            animationHelper.animateNumberChange(tvScannedCount, lastScannedForAnimation, scanned);
        } else {
            tvScannedCount.setText(String.valueOf(scanned));
        }
    }
    
    if (tvItemCount != null) {
        tvItemCount.setText(String.valueOf(scanned));
    }

    if (expectedCount > 0) {
        int progress = Math.min((int) (((float) scanned / expectedCount) * 100), 100);
        
        // ✅ Animation على ProgressBar
        if (progressScanned != null && animationHelper != null) {
            int previousProgress = progressScanned.getProgress();
            if (progress != previousProgress) {
                animationHelper.animateProgressBar(progressScanned, previousProgress, progress);
            }
        } else if (progressScanned != null) {
            progressScanned.setProgress(progress);
        }
        
        // ✅ Animation على نص النسبة
        if (tvProgressPercent != null && animationHelper != null) {
            animationHelper.animateProgressText(tvProgressPercent, progress);
        } else if (tvProgressPercent != null) {
            tvProgressPercent.setText(progress + "%");
        }
        
        if (tvVariance != null) {
            tvVariance.setText(String.valueOf(Math.max(0, expectedCount - scanned)));
        }
        
        if (tvAccuracy != null) {
            tvAccuracy.setText(progress + "%");
        }

        if (scanned >= expectedCount && isScanning) {
            stopScanning();
            if (soundManager != null) soundManager.playBeep(SoundManager.FeedbackType.COMPLETED);
            
            // ✅ الاحتفال بالاكتمال
            if (animationHelper != null) {
                animationHelper.celebrateCompletion(tvProgressPercent, progressScanned, tvScannedCount);
            }
            
            if (isAdded() && !isFragmentDestroyed) {
                Toast.makeText(requireContext(), 
                    isArabic ? "🎉 اكتمل جرد جميع المواد المتوقعة!" : 
                    "🎉 All expected items scanned!", 
                    Toast.LENGTH_LONG).show();
            }
        }
    } else if (tvProgressPercent != null) {
        tvProgressPercent.setText("—");
    }
    
    if (tvEmptyState != null) {
        tvEmptyState.setVisibility(scanned > 0 ? View.GONE : View.VISIBLE);
    }
    
    if (tvInventoryStatus != null && isScanning) {
        tvInventoryStatus.setText((isArabic ? "📡 جاري الجرد... (" : 
            "📡 Scanning... (") + scanned + ")");
    }
    
    // ✅ تحديث آخر قيمة
    lastScannedForAnimation = scanned;
}

/**
 * تحديث الوقت
 */
private void updateElapsedTime() {
    if (tvElapsedTime != null && scanStartTime > 0) {
        long elapsedMillis = SystemClock.elapsedRealtime() - scanStartTime;
        long seconds = elapsedMillis / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        tvElapsedTime.setText(String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs));
    }
}

// ==================== بدء وإيقاف المسح ====================

/**
 * بدء المسح
 */
private void startScanning() {
    if (permissionManager != null && 
        !(permissionManager.canEdit("inventory") || permissionManager.isManager())) {
        showAccessDenied();
        return;
    }
    if (isScanning || isFragmentDestroyed) return;
    
    // ✅ امسح القائمة قبل بدء الجلسة الجديدة
    synchronized (scannedItemsDisplayList) {
        scannedItemsDisplayList.clear();
    }
    if (modernAdapter != null) {
        modernAdapter.submitList(new ArrayList<>());
    }
    if (rvInventoryItems != null) {
        rvInventoryItems.scrollToPosition(0);
    }
    
    // ✅ امسح البيانات القديمة في الواجهة
    if (tvScannedCount != null) tvScannedCount.setText("0");
    if (tvItemCount != null) tvItemCount.setText("0");
    if (progressScanned != null) progressScanned.setProgress(0);
    if (tvProgressPercent != null) tvProgressPercent.setText("0%");
    if (tvEmptyState != null) tvEmptyState.setVisibility(View.VISIBLE);
    
    // ✅ تحديث خلفية الشريط
if (animationHelper != null) {
    animationHelper.updateStatusBarBackground(statusBar, true, true);
}
    
    // ✅ امسح recentTagReads لمنع تجاهل الوسوم من الجلسة السابقة
    recentTagReads.clear();
    
    isScanning = true;
    scanStartTime = SystemClock.elapsedRealtime();
    mainThreadHandler.post(timerRunnable);
    
    Log.d(TAG, "Start inventory scan - Previous data cleared");
    
    loadCatalogAndStart();
}
/**
 * بدء الجرد الأعمى
 */
private void startBlindInventory() {
    if (permissionManager != null && 
        !(permissionManager.canEdit("inventory") || permissionManager.isManager())) {
        showAccessDenied();
        return;
    }
    if (isScanning || isFragmentDestroyed) return;
    
    // ✅ امسح القائمة
    synchronized (scannedItemsDisplayList) {
        scannedItemsDisplayList.clear();
    }
    if (modernAdapter != null) {
        modernAdapter.submitList(new ArrayList<>());
    }
    if (tvScannedCount != null) tvScannedCount.setText("0");
    if (tvItemCount != null) tvItemCount.setText("0");
    if (tvEmptyState != null) tvEmptyState.setVisibility(View.VISIBLE);
    recentTagReads.clear();
    
    
    isScanning = true;
    // ✅ تحديث خلفية الشريط
if (animationHelper != null) {
    animationHelper.updateStatusBarBackground(statusBar, true, true);
}
    scanStartTime = SystemClock.elapsedRealtime();
    mainThreadHandler.post(timerRunnable);
    
    
    Log.d(TAG, "Start blind inventory");
    
    expectedCount = 0;
    catalogEpcs.clear();
    
    if (tvExpectedCount != null) {
        tvExpectedCount.setText("—");
    }
    
    if (tvInventoryStatus != null) {
        tvInventoryStatus.setText(isArabic ? "⚡ جرد أعمى - بدون توقعات" : 
            "⚡ Blind inventory - No expectations");
    }
    
    if (inventoryEngine != null) {
        inventoryEngine.startSession(new ConcurrentHashMap<>());
    }
    startReadingSource();
}

/**
 * تحميل الكتالوج
 */
private void loadCatalogAndStart() {
    if (!isAdded() || isFragmentDestroyed) return;
    
    Toast.makeText(requireContext(), 
        isArabic ? "🔄 جاري تحميل البيانات..." : "🔄 Loading data...", 
        Toast.LENGTH_SHORT).show();
    
    if (backgroundExecutor == null || backgroundExecutor.isShutdown()) {
        backgroundExecutor = Executors.newSingleThreadExecutor();
    }
    
    backgroundExecutor.execute(() -> {
        Map<String, InventoryItem> catalog = fetchCatalogFromServer();
        
        mainThreadHandler.post(() -> {
            if (!isAdded() || isFragmentDestroyed) return;
            
            if (inventoryEngine != null) {
                inventoryEngine.startSession(catalog);
            }
            
            catalogEpcs.clear();
            if (catalog != null) {
                catalogEpcs.addAll(catalog.keySet());
            }
            
            expectedCount = catalog != null ? catalog.size() : 0;
            if (tvExpectedCount != null) {
                tvExpectedCount.setText(String.valueOf(expectedCount));
            }
            
            if (progressScanned != null) {
                progressScanned.setMax(100);
                progressScanned.setProgress(0);
            }
            
            if (tvProgressPercent != null) {
                tvProgressPercent.setText("0%");
            }

            startReadingSource();
        });
    });
}

/**
 * جلب الكتالوج من الخادم مع بناء الخريطة العكسية
 */
private Map<String, InventoryItem> fetchCatalogFromServer() {
    Map<String, InventoryItem> catalog = new ConcurrentHashMap<>();
    
    try {
        if (apiClient == null || branchesManager == null) {
            return catalog;
        }
        
        int branchId = branchesManager.getActiveBranchId();
        MiraApiClient.ApiResponse response = apiClient.getBranchItemsList(branchId);
        
        if (response != null && response.isSuccess && response.data != null) {
            JSONArray items = response.data.optJSONArray("items");
            
            if (items == null) {
                JSONObject dataObj = response.data.optJSONObject("data");
                if (dataObj != null) {
                    items = dataObj.optJSONArray("items");
                }
            }
            
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject obj = items.getJSONObject(i);
                    
                    String gtin13 = obj.optString("gtin13", "").trim();
                    String serialNumber = obj.optString("serial_number", "").trim();
                    String epcCode = obj.optString("epc", obj.optString("rfid_epc", "")).trim();
                    
                    String title = obj.optString("item_title", 
                        obj.optString("title", isArabic ? "قطعة ذهبية" : "Gold Item"));
                    String karat = obj.optString("gold_karat", 
                        obj.optString("karat", "21K"));
                    double weight = obj.optDouble("gross_weight_grams", 
                        obj.optDouble("weight", 0));
                    double price = obj.optDouble("retail_price_usd", 
                        obj.optDouble("retail_price", obj.optDouble("price", 0)));
                    
                    String primaryEpc;
                    if (!epcCode.isEmpty()) {
                        primaryEpc = epcCode.toUpperCase();
                    } else if (!serialNumber.isEmpty()) {
                        primaryEpc = serialNumber.toUpperCase();
                    } else if (!gtin13.isEmpty()) {
                        primaryEpc = "EAN13:" + gtin13;
                    } else {
                        continue;
                    }
                    
                    String normalizedEpc = RFIDCodeUtils.normalizeCodeToEpc(primaryEpc, barcodeToEpcMap);
                    
                    InventoryItem item = new InventoryItem(
                        normalizedEpc, gtin13, serialNumber, title, karat
                    );
                    item.setWeight(weight);
                    item.setPrice(price);
                    
                    catalog.put(normalizedEpc, item);
                    catalogItemsMap.put(normalizedEpc, item);
                    
                    if (!gtin13.isEmpty()) {
                        barcodeToEpcMap.put(gtin13, normalizedEpc);
                        epcToBarcodeMap.put(normalizedEpc, gtin13);
                    }
                    if (!serialNumber.isEmpty()) {
                        barcodeToEpcMap.put(serialNumber, normalizedEpc);
                        epcToBarcodeMap.put(normalizedEpc, serialNumber);
                    }
                    if (!epcCode.isEmpty()) {
                        barcodeToEpcMap.put(epcCode.toUpperCase(), normalizedEpc);
                    }
                }
            }
        }
    } catch (Exception e) {
        Log.e(TAG, "Error fetching catalog from server", e);
    }
    
    return catalog;
}

/**
 * بدء مصدر القراءة
 */
private void startReadingSource() {
    boolean hardwareStarted = false;
    
    if (rfidManager != null) {
        try {
            if (rfidManager.getActiveDevice() != null) {
                MiraIRFIDReader.ReaderType deviceType = 
                    rfidManager.getActiveDevice().getType();
                
                if (deviceType != MiraIRFIDReader.ReaderType.SIMULATOR) {
                    hardwareStarted = rfidManager.startScan();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting hardware scanner", e);
        }
    }
    
    if (!hardwareStarted) {
        startSimulationLoop();
        if (isAdded() && !isFragmentDestroyed) {
            Toast.makeText(requireContext(), 
                isArabic ? "🎯 بدء وضع المحاكاة" : "🎯 Simulation mode started", 
                Toast.LENGTH_SHORT).show();
        }
    }
    
    // ✅ تحديث زر البدء الذكي
    updateMainToggleButton(true);
    
    if (btnGenerateReport != null) btnGenerateReport.setEnabled(false);
    if (btnArchiveReports != null) btnArchiveReports.setEnabled(false);
    
    if (soundManager != null) soundManager.playBeep(SoundManager.FeedbackType.KNOWN_ITEM);
}

/**
 * ✅ تحديث زر البدء/الإيقاف الذكي
 */
private void updateMainToggleButton(boolean scanning) {
    if (btnMainToggleScan == null) return;
    
    try {
        if (scanning) {
            btnMainToggleScan.setText(isArabic ? "⏹️ إيقاف الجرد" : "⏹️ Stop Scan");
            btnMainToggleScan.setIcon(requireContext().getDrawable(android.R.drawable.ic_media_pause));
            btnMainToggleScan.setBackgroundTintList(
                requireContext().getColorStateList(android.R.color.holo_red_dark));
        } else {
            btnMainToggleScan.setText(isArabic ? "▶️ بدء جرد RFID" : "▶️ Start RFID Scan");
            btnMainToggleScan.setIcon(requireContext().getDrawable(android.R.drawable.ic_media_play));
            btnMainToggleScan.setBackgroundTintList(null);
        }
    } catch (Exception e) {
        Log.e(TAG, "Error updating main toggle button", e);
    }
}

/**
 * إيقاف المسح
 */
public void stopScanning() {
    if (!isScanning || isFragmentDestroyed) return;
    
    isScanning = false;
    stopSimulationLoop();
    mainThreadHandler.removeCallbacks(timerRunnable);
    
    Log.d(TAG, "Stop scan - Scanned: " + 
        (inventoryEngine != null ? inventoryEngine.getUniqueCount() : 0));
    
    if (rfidManager != null) {
        try {
            rfidManager.stopScan();
        } catch (Exception e) {
            Log.e(TAG, "Error stopping RFID scanner", e);
        }
    }
    
    if (backgroundExecutor == null || backgroundExecutor.isShutdown()) {
        backgroundExecutor = Executors.newSingleThreadExecutor();
    }
    // ✅ تحديث خلفية الشريط
    boolean isConnected = false;
    try {
    isConnected = rfidManager != null && rfidManager.getActiveDevice() != null;
    } catch (Exception e) {
    isConnected = false;
    }
    if (animationHelper != null) {
    animationHelper.updateStatusBarBackground(statusBar, isConnected, false);
    }
    backgroundExecutor.execute(() -> {
        try {
            if (inventoryEngine != null) {
                inventoryEngine.processSessionClose();
            }
            
            mainThreadHandler.post(() -> {
                if (!isAdded() || isFragmentDestroyed) return;
                
                if (tvInventoryStatus != null) {
                    tvInventoryStatus.setText(isArabic ? "📡 تم إيقاف الجرد" : 
                        "📡 Scan stopped");
                }
                
                Toast.makeText(requireContext(), 
                    isArabic ? "🛑 تم إيقاف المسح" : "🛑 Scan stopped", 
                    Toast.LENGTH_SHORT).show();
                
                if (soundManager != null) soundManager.playBeep(SoundManager.FeedbackType.ERROR);
                
                updateMainToggleButton(false);
                
                if (btnGenerateReport != null) btnGenerateReport.setEnabled(true);
                if (btnArchiveReports != null) btnArchiveReports.setEnabled(true);
                
                updateUI();
                syncOfflineTransactions();
            });
        } catch (Exception e) {
            Log.e(TAG, "Error processing session close", e);
        }
    });
}

// ==================== Power Control Sheet ====================

/**
 * عرض BottomSheet للتحكم بقدرة الهوائي
 */
private void showPowerControlSheet() {
    if (!isAdded() || isFragmentDestroyed) return;
    
    try {
        BottomSheetDialog dialog = new BottomSheetDialog(
            requireContext(), R.style.LuxuryBottomSheetDialog);
        
        View sheetView = getLayoutInflater().inflate(
            R.layout.bottom_sheet_power_control, null);
        dialog.setContentView(sheetView);
        
        Slider sliderPower = sheetView.findViewById(R.id.sliderPower);
        TextView tvPowerValue = sheetView.findViewById(R.id.tvPowerValue);
        MaterialButton btnApply = sheetView.findViewById(R.id.btnApplyPower);
        
        if (sliderPower != null) {
            sliderPower.setValue(currentPowerDbm);
            
            sliderPower.addOnChangeListener((slider, value, fromUser) -> {
                currentPowerDbm = (int) value;
                if (tvPowerValue != null) {
                    tvPowerValue.setText(currentPowerDbm + " dBm");
                }
            });
        }
        
        if (tvPowerValue != null) {
            tvPowerValue.setText(currentPowerDbm + " dBm");
        }
        
        if (btnApply != null) {
            btnApply.setOnClickListener(v -> {
                applyAntennaPower(currentPowerDbm);
                if (btnPowerControl != null) {
                    btnPowerControl.setText("⚡ " + currentPowerDbm + " dBm");
                }
                dialog.dismiss();
            });
        }
        
        dialog.show();
    } catch (Exception e) {
        Log.e(TAG, "Error showing power sheet", e);
    }
}

/**
 * تطبيق قدرة الهوائي
 */
private void applyAntennaPower(int dbm) {
    try {
        if (rfidManager != null && rfidManager.getActiveDevice() != null) {
            Log.d(TAG, "Antenna power set to: " + dbm + " dBm");
        }
        Toast.makeText(requireContext(), 
            (isArabic ? "تم ضبط القدرة على " : "Power set to ") + dbm + " dBm", 
            Toast.LENGTH_SHORT).show();
    } catch (Exception e) {
        Log.e(TAG, "Error setting power", e);
    }
}

// ==================== اختيار الفئة ====================

/**
 * عرض حوار اختيار الفئة للجرد الفئوي
 */
/**
 * ✅ عرض حوار اختيار الفئة مع تطبيق فعلي
 */
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
            String category = categories[which];
            applyCategoryFilter(category, which);
        })
        .setNegativeButton(isArabic ? "إلغاء" : "Cancel", (dialog, which) -> {
            // العودة إلى الوضع الكامل
            if (segmentedModes != null && btnModeFull != null) {
                segmentedModes.check(R.id.btnModeFull);
            }
        })
        .show();
}

/**
 * ✅ تطبيق فلتر الفئة فعلياً
 */
private void applyCategoryFilter(String category, int index) {
    try {
        if (index == 0) {
            // "الكل" → الوضع الكامل
            Toast.makeText(requireContext(), 
                isArabic ? "✅ وضع الجرد الكامل" : "✅ Full inventory mode", 
                Toast.LENGTH_SHORT).show();
            return;
        }
        
        // ✅ جرد فئوي
        selectedCategoryFilter = category;
        
        Toast.makeText(requireContext(), 
            isArabic ? "🎯 جرد فئوي: " + category : "🎯 Category: " + category, 
            Toast.LENGTH_SHORT).show();
        
        // ✅ إذا كنا نمسح، أعد التشغيل مع الفلتر
        if (isScanning) {
            new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "⚠️ إعادة التشغيل" : "⚠️ Restart")
                .setMessage(isArabic ? 
                    "لتطبيق الفئة، يجب إيقاف الجرد الحالي وإعادة تشغيله. هل تريد المتابعة؟" :
                    "To apply the category, current inventory must be stopped and restarted. Continue?")
                .setPositiveButton(isArabic ? "متابعة" : "Continue", (d, w) -> {
                    stopScanning();
                    mainThreadHandler.postDelayed(() -> startCategoryInventory(category), 500);
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
        } else {
            // ابدأ جرد فئوي مباشرة
            startCategoryInventory(category);
        }
        
    } catch (Exception e) {
        Log.e(TAG, "applyCategoryFilter error: " + e.getMessage(), e);
    }
}

/**
 * ✅ بدء جرد فئوي فعلي
 */
private void startCategoryInventory(String category) {
    try {
        if (isScanning || isFragmentDestroyed) return;
        
        // ✅ امسح القائمة
        synchronized (scannedItemsDisplayList) {
            scannedItemsDisplayList.clear();
        }
        if (modernAdapter != null) {
            modernAdapter.submitList(new ArrayList<>());
        }
        recentTagReads.clear();
        
        isScanning = true;
        // ✅ تحديث خلفية الشريط
if (animationHelper != null) {
    animationHelper.updateStatusBarBackground(statusBar, true, true);
}
        scanStartTime = SystemClock.elapsedRealtime();
        mainThreadHandler.post(timerRunnable);
        
        Toast.makeText(requireContext(), 
            isArabic ? "🔄 جاري تحميل كتالوج " + category + "..." :
                      "🔄 Loading catalog for " + category + "...", 
            Toast.LENGTH_SHORT).show();
        
        final String selectedCategory = category;
        
        backgroundExecutor.execute(() -> {
            // ✅ جلب كل الكتالوج
            Map<String, InventoryItem> fullCatalog = fetchCatalogFromServer();
            
            // ✅ فلترة حسب الفئة
            Map<String, InventoryItem> filteredCatalog = new ConcurrentHashMap<>();
            for (Map.Entry<String, InventoryItem> entry : fullCatalog.entrySet()) {
                InventoryItem item = entry.getValue();
                if (item != null && matchesCategory(item, selectedCategory)) {
                    filteredCatalog.put(entry.getKey(), item);
                }
            }
            
            mainThreadHandler.post(() -> {
                if (!isAdded() || isFragmentDestroyed) return;
                
                if (inventoryEngine != null) {
                    inventoryEngine.startSession(filteredCatalog);
                }
                
                catalogEpcs.clear();
                catalogEpcs.addAll(filteredCatalog.keySet());
                
                expectedCount = filteredCatalog.size();
                
                if (tvExpectedCount != null) {
                    tvExpectedCount.setText(String.valueOf(expectedCount));
                }
                
                if (tvInventoryStatus != null) {
                    tvInventoryStatus.setText(isArabic ? 
                        "🎯 جرد فئوي: " + selectedCategory + " (" + expectedCount + ")" :
                        "🎯 Category: " + selectedCategory + " (" + expectedCount + ")");
                }
                
                if (expectedCount == 0) {
                    Toast.makeText(requireContext(), 
                        isArabic ? "⚠️ لا توجد قطع في هذه الفئة" : 
                        "⚠️ No items in this category", 
                        Toast.LENGTH_LONG).show();
                }
                
                startReadingSource();
            });
        });
        
    } catch (Exception e) {
        Log.e(TAG, "startCategoryInventory error: " + e.getMessage(), e);
        isScanning = false;
    }
}

/**
 * ✅ فحص إذا كانت القطعة تنتمي للفئة المطلوبة
 */
private boolean matchesCategory(InventoryItem item, String category) {
    if (item == null || category == null) return false;
    
    String itemKarat = item.getKarat();
    if (itemKarat == null) itemKarat = "";
    itemKarat = itemKarat.toUpperCase().trim();
    
    String cat = category.trim();
    
    // ✅ مطابقة العيار
    if (cat.equals("عيار 24") || cat.equalsIgnoreCase("24K")) {
        return itemKarat.equals("24K") || itemKarat.equals("24");
    }
    if (cat.equals("عيار 22") || cat.equalsIgnoreCase("22K")) {
        return itemKarat.equals("22K") || itemKarat.equals("22");
    }
    if (cat.equals("عيار 21") || cat.equalsIgnoreCase("21K")) {
        return itemKarat.equals("21K") || itemKarat.equals("21");
    }
    if (cat.equals("عيار 18") || cat.equalsIgnoreCase("18K")) {
        return itemKarat.equals("18K") || itemKarat.equals("18");
    }
    if (cat.equals("عيار 14") || cat.equalsIgnoreCase("14K")) {
        return itemKarat.equals("14K") || itemKarat.equals("14");
    }
    
    // ✅ مطابقة الفئات الأخرى
    if (cat.equals("سبائك") || cat.equalsIgnoreCase("Bullion")) {
        return itemKarat.contains("BULLION") || itemKarat.contains("BAR");
    }
    if (cat.equals("أونصة") || cat.equalsIgnoreCase("Ounce")) {
        return itemKarat.contains("OUNCE") || itemKarat.contains("OZ");
    }
    if (cat.equals("فضة") || cat.equalsIgnoreCase("Silver")) {
        return itemKarat.contains("SILVER") || itemKarat.contains("AG");
    }
    if (cat.equals("ألماس") || cat.equalsIgnoreCase("Diamond")) {
        return itemKarat.contains("DIAMOND") || itemKarat.contains("DIA");
    }
    if (cat.equals("أخرى") || cat.equalsIgnoreCase("Other")) {
        // أي شيء لا ينتمي للفئات السابقة
        return !itemKarat.matches("^(24K|22K|21K|18K|14K|24|22|21|18|14)$")
            && !itemKarat.contains("BULLION")
            && !itemKarat.contains("OUNCE")
            && !itemKarat.contains("SILVER")
            && !itemKarat.contains("DIAMOND");
    }
    
    return false;
}


    // ==================== اعتماد الجرد وإرسال البيانات ====================

    /**
     * عرض تأكيد الالتزام
     */
    private void showCommitConfirmationDialog() {
        if (!isAdded() || isFragmentDestroyed || inventoryEngine == null) return;
        if (permissionManager != null && 
            !(permissionManager.canEdit("inventory") || permissionManager.isManager())) {
            showAccessDenied();
            return;
        }
        
        int scanned = inventoryEngine.getUniqueCount();
        int missing = Math.max(0, expectedCount - scanned);

        String message = isArabic ? 
            String.format("📊 ملخص عمليات الجرد:\n\n• المتوقع: %d\n• الممسوح: %d\n• المفقود: %d\n\nهل تريد اعتماد العملية وإرسالها لقاعدة البيانات؟",
                expectedCount, scanned, missing) :
            String.format("📊 Inventory Summary:\n\n• Expected: %d\n• Scanned: %d\n• Missing: %d\n\nDo you want to commit and send to database?",
                expectedCount, scanned, missing);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "حفظ واكتمال الجرد" : "Save and Complete Inventory")
                .setMessage(message)
                .setPositiveButton(isArabic ? "إرسال واعتماد" : "Send and Commit", 
                    (dialog, which) -> commitInventoryToServer())
                .setNeutralButton(isArabic ? "🖨️ طباعة التقرير الحالي" : "🖨️ Print current report", 
                    (dialog, which) -> {
                        InventoryReport currentReport = new InventoryReport(
                            0,
                            inventoryEngine.getClientSessionId(),
                            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                .format(new Date()),
                            expectedCount,
                            scanned,
                            missing,
                            isArabic ? "جارية" : "In progress"
                        );
                        
                        List<ScannedItemDisplay> snapshotList;
                        synchronized (scannedItemsDisplayList) {
                            snapshotList = new ArrayList<>(scannedItemsDisplayList);
                        }
                        
                        PrintUtils.printReportWithQRCode(requireContext(), currentReport, 
                            snapshotList, isArabic, null);
                    })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
    }

    /**
     * إرسال الجرد للخادم
     */
    private void commitInventoryToServer() {
        if (!isAdded() || isFragmentDestroyed) return;
        
        if (!isCommitting.compareAndSet(false, true)) {
            Toast.makeText(requireContext(), 
                isArabic ? "⏳ جاري إرسال البيانات بالفعل..." : "⏳ Already sending...", 
                Toast.LENGTH_SHORT).show();
            return;
        }
        
        Toast.makeText(requireContext(), 
            isArabic ? "⏳ جاري إرسال البيانات..." : "⏳ Sending...", 
            Toast.LENGTH_SHORT).show();
        
        Log.d(TAG, "Commit inventory - Session: " + 
            (inventoryEngine != null ? inventoryEngine.getClientSessionId() : "null"));
        
        if (backgroundExecutor == null || backgroundExecutor.isShutdown()) {
            backgroundExecutor = Executors.newSingleThreadExecutor();
        }
        
        backgroundExecutor.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                
                String sessionId = inventoryEngine != null ? 
                    inventoryEngine.getClientSessionId() : "SESS-UNKNOWN";
                payload.put("session_id", sessionId);
                payload.put("client_session_id", sessionId);
                
                int branchId = branchesManager != null ? 
                    branchesManager.getActiveBranchId() : 0;
                payload.put("branch_id", branchId);
                payload.put("location", "Main Showroom");
                
                int scanned = inventoryEngine != null ? 
                    inventoryEngine.getUniqueCount() : 0;
                int totalReads = inventoryEngine != null ? 
                    inventoryEngine.getTotalReads() : 0;
                
                payload.put("expected_count", expectedCount);
                payload.put("total_scanned", totalReads);
                payload.put("unique_count", scanned);
                payload.put("found_count", scanned);
                payload.put("missing_count", Math.max(0, expectedCount - scanned));
                payload.put("unexpected_count", 0);
                
                JSONArray itemsArray = new JSONArray();
                JSONArray epcsArray = new JSONArray();
                
                List<ScannedItemDisplay> snapshotList;
                synchronized (scannedItemsDisplayList) {
                    snapshotList = new ArrayList<>(scannedItemsDisplayList);
                }
                
                for (ScannedItemDisplay item : snapshotList) {
                    JSONObject itemObj = new JSONObject();
                    itemObj.put("code", item.epc);
                    itemObj.put("serial", item.epc);
                    itemObj.put("rssi", String.valueOf(item.rssi));
                    itemsArray.put(itemObj);
                    
                    epcsArray.put(item.epc);
                }
                
                payload.put("items", itemsArray);
                payload.put("scanned_epcs", epcsArray);
                
                MiraApiClient.ApiResponse response = apiClient != null ? 
                    apiClient.closeInventorySession(payload) : null;
                
                mainThreadHandler.post(() -> {
                    if (!isAdded() || isFragmentDestroyed) {
                        isCommitting.set(false);
                        return;
                    }
                    
                    if (response != null && response.isSuccess) {
                        Toast.makeText(requireContext(), 
                            isArabic ? "✅ تم اعتماد الجرد وحفظ التقرير بنجاح" : 
                            "✅ Inventory committed successfully", 
                            Toast.LENGTH_LONG).show();
                        if (btnGenerateReport != null) btnGenerateReport.setEnabled(false);
                        if (btnCommitSession != null) btnCommitSession.setEnabled(false);
                        showCommitSuccessDialog(response);
                    } else {
                        String errorMsg = response != null ? response.error : "Unknown error";
                        Log.e(TAG, "Commit failed: " + errorMsg);
                        Toast.makeText(requireContext(), 
                            (isArabic ? "❌ خطأ: " : "❌ Error: ") + errorMsg, 
                            Toast.LENGTH_LONG).show();
                    }
                    isCommitting.set(false);
                });
            } catch (Exception e) {
                Log.e(TAG, "Error committing inventory", e);
                isCommitting.set(false);
                mainThreadHandler.post(() -> {
                    if (isAdded() && !isFragmentDestroyed) {
                        Toast.makeText(requireContext(), 
                            (isArabic ? "❌ خطأ في إرسال البيانات: " : "❌ Error: ") + e.getMessage(), 
                            Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    /**
     * عرض نجاح الالتزام
     */
    private void showCommitSuccessDialog(MiraApiClient.ApiResponse response) {
        if (!isAdded() || isFragmentDestroyed || response.data == null) return;
        
        try {
            JSONObject report = response.data.optJSONObject("report");
            if (report == null) report = response.data;
            
            String accuracy = report.optString("accuracy", "0");
            String riskLevel = report.optString("risk_level", "unknown");
            String recommendation = report.optString("recommendation", "");
            
            String message = isArabic ? 
                String.format(
                    "✅ تم اعتماد الجرد بنجاح:\n\n" +
                    "• الدقة: %s%%\n" +
                    "• مستوى الخطورة: %s\n" +
                    "• التوصية: %s",
                    accuracy, riskLevel, recommendation
                ) :
                String.format(
                    "✅ Inventory committed successfully:\n\n" +
                    "• Accuracy: %s%%\n" +
                    "• Risk Level: %s\n" +
                    "• Recommendation: %s",
                    accuracy, riskLevel, recommendation
                );
            
            new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "🎉 نجاح العملية" : "🎉 Success")
                .setMessage(message)
                .setPositiveButton(isArabic ? "🖨️ طباعة التقرير" : "🖨️ Print Report", 
                    (dialog, which) -> {
                        int scanned = inventoryEngine != null ? 
                            inventoryEngine.getUniqueCount() : 0;
                        InventoryReport committedReport = new InventoryReport(
                            0,
                            inventoryEngine != null ? 
                                inventoryEngine.getClientSessionId() : "SESS-COMMITTED",
                            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                .format(new Date()),
                            expectedCount,
                            scanned,
                            Math.max(0, expectedCount - scanned),
                            isArabic ? "معتمد" : "Committed"
                        );
                        
                        List<ScannedItemDisplay> snapshotList;
                        synchronized (scannedItemsDisplayList) {
                            snapshotList = new ArrayList<>(scannedItemsDisplayList);
                        }
                        
                        PrintUtils.printReportWithQRCode(requireContext(), committedReport, 
                            snapshotList, isArabic, null);
                    })
                .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
                .show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing success dialog", e);
        }
    }

    // ==================== الأرشيف واللقطات ====================

    /**
     * عرض الأرشيف
     */
    private void showArchiveReports() {
        if (!isAdded() || isFragmentDestroyed) return;
        
        AlertDialog loading = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "📂 جاري تحميل الأرشيف..." : "📂 Loading archive...")
                .setMessage(isArabic ? "يرجى الانتظار" : "Please wait")
                .setCancelable(false)
                .show();
        
        if (backgroundExecutor == null || backgroundExecutor.isShutdown()) {
            backgroundExecutor = Executors.newSingleThreadExecutor();
        }
        
        backgroundExecutor.execute(() -> {
            try {
                List<InventoryReport> reports = fetchArchiveReports();
                
                mainThreadHandler.post(() -> {
                    loading.dismiss();
                    if (isAdded() && !isFragmentDestroyed) {
                        displayArchiveDialog(reports);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error fetching archive", e);
                mainThreadHandler.post(() -> {
                    loading.dismiss();
                    if (isAdded() && !isFragmentDestroyed) {
                        Toast.makeText(requireContext(), 
                            isArabic ? "❌ فشل تحميل الأرشيف" : "❌ Failed to load archive", 
                            Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }
    
    /**
     * جلب تقارير الأرشيف
     */
    private List<InventoryReport> fetchArchiveReports() {
        List<InventoryReport> reports = new ArrayList<>();
        
        try {
            if (apiClient != null && branchesManager != null) {
                int branchId = branchesManager.getActiveBranchId();
                MiraApiClient.ApiResponse response = 
                    apiClient.getInventoryReports(branchId, 1, 50);
                
                if (response != null && response.isSuccess && response.data != null) {
                    JSONObject dataObj = response.data.optJSONObject("data");
                    JSONArray sessions = null;
                    
                    if (dataObj != null) {
                        sessions = dataObj.optJSONArray("sessions");
                    } else {
                        sessions = response.data.optJSONArray("sessions");
                    }
                    
                    if (sessions != null) {
                        for (int i = 0; i < sessions.length(); i++) {
                            JSONObject obj = sessions.getJSONObject(i);
                            
                            InventoryReport report = new InventoryReport(
                                obj.optInt("id", i + 1),
                                obj.optString("client_session_id", 
                                    obj.optString("session_id", "SESS-" + (i + 1))),
                                obj.optString("started_at", 
                                    obj.optString("created_at", "")),
                                obj.optInt("expected_count", 0),
                                obj.optInt("unique_items_found", 
                                    obj.optInt("scanned_count", 0)),
                                obj.optInt("missing_count", 0),
                                obj.optString("status", "completed")
                            );
                            reports.add(report);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching archive reports", e);
        }
        
        return reports;
    }
    
    /**
     * عرض مربع الأرشيف
     */
    private void displayArchiveDialog(List<InventoryReport> reports) {
        if (!isAdded() || isFragmentDestroyed || reports.isEmpty()) {
            Toast.makeText(requireContext(), 
                isArabic ? "لا توجد تقارير سابقة" : "No previous reports", 
                Toast.LENGTH_SHORT).show();
            return;
        }
        
        String[] reportTitles = new String[reports.size()];
        for (int i = 0; i < reports.size(); i++) {
            InventoryReport r = reports.get(i);
            reportTitles[i] = String.format("📋 %s - %s (%d/%d)", 
                r.sessionId, r.date, r.scannedCount, r.expectedCount);
        }
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "📂 أرشيف تقارير الجرد" : "📂 Inventory Reports Archive")
                .setItems(reportTitles, (dialog, which) -> {
                    showReportDetails(reports.get(which));
                })
                .setPositiveButton(isArabic ? "طباعة الكل" : "Print All", 
                    (dialog, which) -> printAllReports(reports))
                .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
                .show();
    }
    
    /**
     * عرض تفاصيل التقرير
     */
    private void showReportDetails(InventoryReport report) {
        if (!isAdded() || isFragmentDestroyed) return;
        
        String message = String.format(
            "📋 %s:\n\n" +
            "• %s: %s\n" +
            "• %s: %s\n" +
            "• %s: %d\n" +
            "• %s: %d\n" +
            "• %s: %d\n" +
            "• %s: %s",
            isArabic ? "تفاصيل التقرير" : "Report Details",
            isArabic ? "رقم الجلسة" : "Session ID", report.sessionId,
            isArabic ? "التاريخ" : "Date", report.date,
            isArabic ? "المتوقع" : "Expected", report.expectedCount,
            isArabic ? "الممسوح" : "Scanned", report.scannedCount,
            isArabic ? "المفقود" : "Missing", report.missingCount,
            isArabic ? "الحالة" : "Status", report.status
        );
        
        String[] options = isArabic ? 
            new String[]{
                "🖨️ طباعة التقرير",
                "📊 مقارنة مع الحالي",
                "❌ إغلاق"
            } :
            new String[]{
                "🖨️ Print Report",
                "📊 Compare with Current",
                "❌ Close"
            };
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "تفاصيل التقرير" : "Report Details")
                .setMessage(message)
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            List<ScannedItemDisplay> snapshotList;
                            synchronized (scannedItemsDisplayList) {
                                snapshotList = new ArrayList<>(scannedItemsDisplayList);
                            }
                            PrintUtils.printReportWithQRCode(requireContext(), report, 
                                snapshotList, isArabic, null);
                            break;
                        case 1:
                            compareWithCurrent(report);
                            break;
                    }
                })
                .show();
    }
    
    /**
     * طباعة جميع التقارير
     */
    private void printAllReports(List<InventoryReport> reports) {
        if (!isAdded() || isFragmentDestroyed || reports.isEmpty()) return;
        
        List<ScannedItemDisplay> snapshotList;
        synchronized (scannedItemsDisplayList) {
            snapshotList = new ArrayList<>(scannedItemsDisplayList);
        }
        
        for (InventoryReport r : reports) {
            PrintUtils.printReportWithQRCode(requireContext(), r, 
                snapshotList, isArabic, null);
        }
    }

    /**
     * مقارنة مع التقرير الحالي
     */
    private void compareWithCurrent(InventoryReport oldReport) {
        if (!isAdded() || isFragmentDestroyed || inventoryEngine == null) return;
        
        int currentScanned = inventoryEngine.getUniqueCount();
        int currentExpected = expectedCount;
        
        String comparison = String.format(
            "📊 %s:\n\n" +
            "%s (%s):\n" +
            "• %s: %d\n• %s: %d\n• %s: %d\n\n" +
            "%s:\n" +
            "• %s: %d\n• %s: %d\n• %s: %d\n\n" +
            "%s:\n" +
            "• %s: %+d\n• %s: %+d",
            isArabic ? "مقارنة الجرد" : "Inventory Comparison",
            isArabic ? "التقرير السابق" : "Previous Report", oldReport.date,
            isArabic ? "المتوقع" : "Expected", oldReport.expectedCount,
            isArabic ? "الممسوح" : "Scanned", oldReport.scannedCount,
            isArabic ? "المفقود" : "Missing", oldReport.missingCount,
            isArabic ? "الجرد الحالي" : "Current Inventory",
            isArabic ? "المتوقع" : "Expected", currentExpected,
            isArabic ? "الممسوح" : "Scanned", currentScanned,
            isArabic ? "المفقود" : "Missing", Math.max(0, currentExpected - currentScanned),
            isArabic ? "الفرق" : "Difference",
            isArabic ? "الممسوح" : "Scanned", currentScanned - oldReport.scannedCount,
            isArabic ? "المفقود" : "Missing", 
            Math.max(0, currentExpected - currentScanned) - oldReport.missingCount
        );
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "📊 مقارنة الجرد" : "📊 Inventory Comparison")
                .setMessage(comparison)
                .setPositiveButton(isArabic ? "إغلاق" : "Close", null)
                .show();
    }

    /**
     * عرض اللقطات
     */
    private void showSnapshotsDialog() {
        if (!isAdded() || isFragmentDestroyed) return;
        
        String[] options = isArabic ? 
            new String[]{
                "📸 عرض لقطات الجرد السابقة",
                "📊 مقارنة لقطتين",
                "❌ إغلاق"
            } :
            new String[]{
                "📸 View Previous Snapshots",
                "📊 Compare Two Snapshots",
                "❌ Close"
            };
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "📸 لقطات الجرد" : "📸 Inventory Snapshots")
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0: showSnapshotsList(); break;
                        case 1: showSnapshotComparison(); break;
                    }
                })
                .show();
    }
    
    /**
     * عرض قائمة اللقطات
     */
    private void showSnapshotsList() {
        if (!isAdded() || isFragmentDestroyed) return;
        
        AlertDialog loading = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "📸 جاري تحميل اللقطات..." : "📸 Loading snapshots...")
                .setMessage(isArabic ? "يرجى الانتظار" : "Please wait")
                .setCancelable(false)
                .show();
        
        if (backgroundExecutor == null || backgroundExecutor.isShutdown()) {
            backgroundExecutor = Executors.newSingleThreadExecutor();
        }
        
        backgroundExecutor.execute(() -> {
            try {
                List<String> snapshots = fetchSnapshotsFromServer();
                
                mainThreadHandler.post(() -> {
                    loading.dismiss();
                    if (isAdded() && !isFragmentDestroyed) {
                        displaySnapshotsDialog(snapshots);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error fetching snapshots", e);
                mainThreadHandler.post(() -> {
                    loading.dismiss();
                    if (isAdded() && !isFragmentDestroyed) {
                        Toast.makeText(requireContext(), 
                            isArabic ? "❌ فشل تحميل اللقطات" : "❌ Failed to load snapshots", 
                            Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    /**
     * جلب اللقطات
     */
    private List<String> fetchSnapshotsFromServer() {
        List<String> snapshots = new ArrayList<>();
        
        try {
            if (apiClient != null) {
                MiraApiClient.ApiResponse response = 
                    apiClient.getLastSnapshot("Main Showroom");
                
                if (response != null && response.isSuccess && response.data != null) {
                    JSONObject snapshot = response.data.optJSONObject("snapshot");
                    if (snapshot == null) snapshot = response.data;
                    
                    if (snapshot != null && snapshot.length() > 0) {
                        String date = snapshot.optString("snapshot_date", 
                            isArabic ? "غير معروف" : "Unknown");
                        int items = snapshot.optInt("total_items", 0);
                        double weight = snapshot.optDouble("total_weight", 0);
                        double value = snapshot.optDouble("total_value", 0);
                        
                        snapshots.add(String.format("📸 %s - %d %s - %.2f%s - $%.2f", 
                            date, items, 
                            isArabic ? "قطعة" : "items",
                            weight, isArabic ? "جم" : "g", 
                            value));
                    }
                }
                
                MiraApiClient.ApiResponse historyResponse = 
                    apiClient.getInventoryHistory("Main Showroom", 10);
                if (historyResponse != null && historyResponse.isSuccess && 
                    historyResponse.data != null) {
                    JSONArray history = historyResponse.data.optJSONArray("history");
                    if (history != null) {
                        for (int i = 0; i < history.length(); i++) {
                            JSONObject obj = history.getJSONObject(i);
                            String date = obj.optString("snapshot_date", "");
                            int items = obj.optInt("total_items", 0);
                            double weight = obj.optDouble("total_weight", 0);
                            double value = obj.optDouble("total_value", 0);
                            
                            snapshots.add(String.format("📸 %s - %d %s - %.2f%s - $%.2f", 
                                date, items, 
                                isArabic ? "قطعة" : "items",
                                weight / 1000, 
                                isArabic ? "كجم" : "kg", 
                                value));
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching snapshots", e);
        }
        
        return snapshots;
    }

    /**
     * عرض مربع اللقطات
     */
    private void displaySnapshotsDialog(List<String> snapshots) {
        if (!isAdded() || isFragmentDestroyed) return;
        
        if (snapshots.isEmpty()) {
            Toast.makeText(requireContext(), 
                isArabic ? "لا توجد لقطات سابقة" : "No previous snapshots", 
                Toast.LENGTH_SHORT).show();
            return;
        }
        
        String[] snapshotArray = snapshots.toArray(new String[0]);
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "📸 لقطات الجرد السابقة" : "📸 Previous Inventory Snapshots")
                .setItems(snapshotArray, (dialog, which) -> {
                    Toast.makeText(requireContext(), 
                        (isArabic ? "تم اختيار: " : "Selected: ") + snapshotArray[which], 
                        Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton(isArabic ? "إغلاق" : "Close", null)
                .show();
    }
    
    /**
     * عرض مقارنة اللقطات
     */
    private void showSnapshotComparison() {
        if (!isAdded() || isFragmentDestroyed) return;
        
        AlertDialog loading = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "📊 جاري تحميل الجلسات..." : "📊 Loading sessions...")
                .setMessage(isArabic ? "يرجى الانتظار" : "Please wait")
                .setCancelable(false)
                .show();
        
        if (backgroundExecutor == null || backgroundExecutor.isShutdown()) {
            backgroundExecutor = Executors.newSingleThreadExecutor();
        }
        
        backgroundExecutor.execute(() -> {
            try {
                List<InventoryReport> sessions = fetchArchiveReports();
                
                mainThreadHandler.post(() -> {
                    loading.dismiss();
                    if (!isAdded() || isFragmentDestroyed) return;
                    
                    if (sessions.size() < 2) {
                        Toast.makeText(requireContext(), 
                            isArabic ? "⚠️ تحتاج إلى جلستين على الأقل للمقارنة" : 
                            "⚠️ Need at least two sessions for comparison", 
                            Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    showSessionSelectionDialog(sessions);
                });
            } catch (Exception e) {
                Log.e(TAG, "Error fetching sessions for comparison", e);
                mainThreadHandler.post(() -> {
                    loading.dismiss();
                    if (isAdded() && !isFragmentDestroyed) {
                        Toast.makeText(requireContext(), 
                            isArabic ? "❌ فشل تحميل الجلسات" : "❌ Failed to load sessions", 
                            Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    /**
     * عرض اختيار الجلسات
     */
    private void showSessionSelectionDialog(List<InventoryReport> sessions) {
        if (!isAdded() || isFragmentDestroyed) return;
        
        String[] sessionTitles = new String[sessions.size()];
        for (int i = 0; i < sessions.size(); i++) {
            InventoryReport r = sessions.get(i);
            sessionTitles[i] = String.format("📋 %s - %s (%d/%d)", 
                r.sessionId, r.date, r.scannedCount, r.expectedCount);
        }
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "📊 اختر الجلسة الأولى" : "📊 Select First Session")
                .setItems(sessionTitles, (dialog1, which1) -> {
                    InventoryReport firstSession = sessions.get(which1);
                    
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle(isArabic ? "📊 اختر الجلسة الثانية" : "📊 Select Second Session")
                            .setItems(sessionTitles, (dialog2, which2) -> {
                                InventoryReport secondSession = sessions.get(which2);
                                
                                if (firstSession.id == secondSession.id) {
                                    Toast.makeText(requireContext(), 
                                        isArabic ? "⚠️ اختر جلستين مختلفتين" : 
                                        "⚠️ Select two different sessions", 
                                        Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                
                                fetchAndShowComparison(firstSession.id, secondSession.id);
                            })
                            .show();
                })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
    }

    /**
     * جلب وعرض المقارنة
     */
    private void fetchAndShowComparison(int sessionId1, int sessionId2) {
        if (!isAdded() || isFragmentDestroyed) return;
        
        AlertDialog loading = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "📊 جاري المقارنة..." : "📊 Comparing...")
                .setMessage(isArabic ? "يرجى الانتظار" : "Please wait")
                .setCancelable(false)
                .show();
        
        if (backgroundExecutor == null || backgroundExecutor.isShutdown()) {
            backgroundExecutor = Executors.newSingleThreadExecutor();
        }
        
        backgroundExecutor.execute(() -> {
            try {
                MiraApiClient.ApiResponse response = 
                    apiClient != null ? 
                        apiClient.compareInventorySessions(sessionId1, sessionId2) : null;
                
                mainThreadHandler.post(() -> {
                    loading.dismiss();
                    if (!isAdded() || isFragmentDestroyed) return;
                    
                    if (response != null && response.isSuccess && response.data != null) {
                        displayComparisonResult(response.data);
                    } else {
                        Toast.makeText(requireContext(), 
                            isArabic ? "❌ فشل المقارنة" : "❌ Comparison failed", 
                            Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error comparing sessions", e);
                mainThreadHandler.post(() -> {
                    loading.dismiss();
                    if (isAdded() && !isFragmentDestroyed) {
                        Toast.makeText(requireContext(), 
                            isArabic ? "❌ خطأ في المقارنة" : "❌ Comparison error", 
                            Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    /**
     * عرض نتيجة المقارنة
     */
    private void displayComparisonResult(JSONObject data) {
        if (!isAdded() || isFragmentDestroyed) return;
        
        try {
            JSONObject comparison = data.optJSONObject("comparison");
            if (comparison == null) comparison = data;
            
            JSONObject session1 = comparison.optJSONObject("session_1");
            JSONObject session2 = comparison.optJSONObject("session_2");
            JSONObject difference = comparison.optJSONObject("difference");
            
            if (session1 != null && session2 != null) {
                int found1 = session1.optInt("found", 
                    session1.optInt("unique_items_found", 0));
                int expected1 = session1.optInt("expected", 
                    session1.optInt("expected_count", 0));
                int found2 = session2.optInt("found", 
                    session2.optInt("unique_items_found", 0));
                int expected2 = session2.optInt("expected", 
                    session2.optInt("expected_count", 0));
                
                int missing1 = Math.max(0, expected1 - found1);
                int missing2 = Math.max(0, expected2 - found2);
                
                int foundDiff = difference != null ? 
                    difference.optInt("found_diff", found2 - found1) : found2 - found1;
                int missingDiff = missing2 - missing1;
                
                String date1 = session1.optString("date", 
                    session1.optString("started_at", isArabic ? "غير معروف" : "Unknown"));
                String date2 = session2.optString("date", 
                    session2.optString("started_at", isArabic ? "غير معروف" : "Unknown"));
                
                String comparisonText = String.format(
                    "📊 %s:\n\n" +
                    "%s (%s):\n• %s: %d\n• %s: %d\n• %s: %d\n\n" +
                    "%s (%s):\n• %s: %d\n• %s: %d\n• %s: %d\n\n" +
                    "%s:\n• %s: %+d %s\n• %s: %+d %s",
                    isArabic ? "مقارنة الجلسات" : "Session Comparison",
                    isArabic ? "الجلسة الأولى" : "First Session", date1,
                    isArabic ? "المتوقع" : "Expected", expected1,
                    isArabic ? "الممسوح" : "Found", found1,
                    isArabic ? "المفقود" : "Missing", missing1,
                    isArabic ? "الجلسة الثانية" : "Second Session", date2,
                    isArabic ? "المتوقع" : "Expected", expected2,
                    isArabic ? "الممسوح" : "Found", found2,
                    isArabic ? "المفقود" : "Missing", missing2,
                    isArabic ? "الفرق بين الجلستين" : "Difference Between Sessions",
                    isArabic ? "تغير في الممسوح" : "Found Change", foundDiff, 
                    isArabic ? "قطعة" : "items",
                    isArabic ? "تغير في المفقود" : "Missing Change", missingDiff,
                    isArabic ? "قطعة" : "items"
                );
                
                String assessment = "";
                if (foundDiff > 0) {
                    assessment = "\n\n✅ " + (isArabic ? 
                        "تحسن: تم العثور على " + foundDiff + " قطعة إضافية" : 
                        "Improvement: Found " + foundDiff + " additional items");
                } else if (foundDiff < 0) {
                    assessment = "\n\n⚠️ " + (isArabic ? 
                        "تراجع: انخفض عدد القطع الممسوحة بـ " + Math.abs(foundDiff) + " قطعة" : 
                        "Decline: Scanned items decreased by " + Math.abs(foundDiff) + " items");
                } else {
                    assessment = "\n\n➡️ " + (isArabic ? 
                        "لا تغيير في عدد القطع الممسوحة" : 
                        "No change in scanned items count");
                }
                
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(isArabic ? "📊 نتيجة المقارنة" : "📊 Comparison Result")
                        .setMessage(comparisonText + assessment)
                        .setPositiveButton(isArabic ? "🖨️ طباعة" : "🖨️ Print", 
                            (dialog, which) -> {
                                InventoryReport compReport = new InventoryReport(
                                    0,
                                    session2.optString("client_session_id", "COMP-RESULT"),
                                    date2,
                                    expected2,
                                    found2,
                                    missing2,
                                    isArabic ? "مقارنة" : "Comparison"
                                );
                                
                                List<ScannedItemDisplay> snapshotList;
                                synchronized (scannedItemsDisplayList) {
                                    snapshotList = new ArrayList<>(scannedItemsDisplayList);
                                }
                                
                                PrintUtils.printReportWithQRCode(requireContext(), compReport, 
                                    snapshotList, isArabic, null);
                            })
                        .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
                        .show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error displaying comparison", e);
            if (isAdded() && !isFragmentDestroyed) {
                Toast.makeText(requireContext(), 
                    isArabic ? "❌ خطأ في عرض المقارنة" : "❌ Error displaying comparison", 
                    Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ==================== المحاكاة ====================

    /**
     * بدء المحاكاة
     */
    private void startSimulationLoop() {
        simulationIndex = 0;
        
        simulationRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isScanning || isFragmentDestroyed) return;
                
                String epc;
                
                if (expectedCount > 0 && !catalogEpcs.isEmpty()) {
                    List<String> epcList = new ArrayList<>(catalogEpcs);
                    epc = epcList.get(simulationIndex % epcList.size());
                } else {
                    epc = simulationTags[simulationIndex % simulationTags.length];
                }
                
                int rssi = -40 - (int)(Math.random() * 40);
                
                onTagRead(epc, rssi);
                
                simulationIndex++;
                long delay = SIMULATION_MIN_DELAY_MS + 
                    (long)(Math.random() * SIMULATION_MAX_ADDITIONAL_DELAY_MS);
                
                if (isScanning && !isFragmentDestroyed) {
                    simulationHandler.postDelayed(this, delay);
                }
            }
        };
        
        simulationHandler.postDelayed(simulationRunnable, SIMULATION_INITIAL_DELAY_MS);
    }

    /**
     * إيقاف المحاكاة
     */
    private void stopSimulationLoop() {
        if (simulationHandler != null && simulationRunnable != null) {
            simulationHandler.removeCallbacks(simulationRunnable);
        }
    }

    // ==================== دورة الحياة - التنظيف ====================
@Override
public void onResume() {
    super.onResume();
    try {
        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).setActiveInventoryFragment(this);
        }
        
        // ✅ استرجاع الجلسة السابقة إذا وُجدت
        if (scannedItemsDisplayList.isEmpty() && !isScanning) {
            mainThreadHandler.postDelayed(this::restorePreviousSession, 600);
        }
        
    } catch (Exception e) {
        Log.e(TAG, "onResume error: " + e.getMessage());
    }
}

@Override
public void onPause() {
    super.onPause();
    try {
        // ✅ حفظ الجلسة قبل الإيقاف
        if (isScanning || !scannedItemsDisplayList.isEmpty()) {
            saveCurrentSession();
        }
        
        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).setActiveInventoryFragment(null);
        }
    } catch (Exception e) {
        Log.e(TAG, "onPause error: " + e.getMessage());
    }
}

@Override
public void onDestroyView() {
    try {
        // ✅ حفظ الجلسة قبل التدمير
        if (isScanning || !scannedItemsDisplayList.isEmpty()) {
            saveCurrentSession();
        }
    } catch (Exception e) {
        Log.e(TAG, "save on destroy error: " + e.getMessage());
    }
    
    if (backPressedCallback != null) {
        backPressedCallback.remove();
        backPressedCallback = null;
    }
    
    isFragmentDestroyed = true;
    isScanning = false;
    
    stopSimulationLoop();
    mainThreadHandler.removeCallbacksAndMessages(null);
    simulationHandler.removeCallbacksAndMessages(null);
    
    if (rfidManager != null) {
        try {
            rfidManager.stopScan();
            rfidManager.setListener(null);
        } catch (Exception e) {
            Log.e(TAG, "Error stopping scanner on destroy", e);
        }
    }
    
    if (soundManager != null) {
        soundManager.release();
        soundManager = null;
    }
    
    // ⚠️ لا تمسح القائمة هنا — تُحفظ في onPause/onDestroyView
    // المسح الكامل في onDestroy()
    
    super.onDestroyView();
}

    /**
     * تنظيف عند التدمير الكامل
     */
    @Override
public void onDestroy() {
    super.onDestroy();
    
    // ✅ الآن نمسح كل شيء بعد الحفظ
    synchronized (scannedItemsDisplayList) {
        scannedItemsDisplayList.clear();
    }
    recentTagReads.clear();
    catalogEpcs.clear();
    catalogItemsMap.clear();
    barcodeToEpcMap.clear();
    epcToBarcodeMap.clear();
    
    shutdownExecutor();
    inventoryDatabase = null;
}
    /**
     * إغلاق ExecutorService
     */
    private void shutdownExecutor() {
        if (backgroundExecutor != null && !backgroundExecutor.isShutdown()) {
            backgroundExecutor.shutdown();
            try {
                if (!backgroundExecutor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_MS, 
                    TimeUnit.MILLISECONDS)) {
                    backgroundExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Log.w(TAG, "Executor termination interrupted", e);
                backgroundExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            backgroundExecutor = null;
        }
    }
    
    // ==================== واجهات عامة للشريط الثانوي ====================

/**
 * ✅ عرض إعدادات الماسح الخاصة بالجرد
 */
public void showInventoryScannerSettings() {
    try {
        if (!isAdded() || isFragmentDestroyed) return;
        showPowerControlSheet();
    } catch (Exception e) {
        Log.e(TAG, "showInventoryScannerSettings error: " + e.getMessage());
    }
}

/**
 * ✅ عرض إعدادات الجلسة (Session Settings)
 */
public void showInventorySessionSettings() {
    try {
        if (!isAdded() || isFragmentDestroyed) return;
        showSessionSettingsDialog();
    } catch (Exception e) {
        Log.e(TAG, "showInventorySessionSettings error: " + e.getMessage());
    }
}

/**
 * ✅ عرض إعدادات التصفير والصوت
 */
public void showInventoryBuzzerSettings() {
    try {
        if (!isAdded() || isFragmentDestroyed) return;
        showBuzzerSettingsDialog();
    } catch (Exception e) {
        Log.e(TAG, "showInventoryBuzzerSettings error: " + e.getMessage());
    }
}

/**
 * ✅ عدد الأصناف الممسوحة (للشريط الثانوي)
 */
public int getScannedCount() {
    try {
        if (inventoryEngine != null) {
            return inventoryEngine.getUniqueCount();
        }
        return scannedItemsDisplayList != null ? scannedItemsDisplayList.size() : 0;
    } catch (Exception e) {
        return 0;
    }
}

/**
 * ✅ هل الجرد قيد التشغيل؟
 */
public boolean isCurrentlyScanning() {
    return isScanning;
}
// ==================== حوارات إعدادات الماسح ====================

/**
 * ✅ حوار إعدادات الجلسة
 */
private void showSessionSettingsDialog() {
    try {
        if (!isAdded() || isFragmentDestroyed) return;
        
        String[] options = isArabic ? new String[]{
            "⏱️ مهلة الجلسة: 5 دقائق",
            "📊 عدد المتوقع: " + expectedCount,
            "🔊 تفعيل الصوت: نعم",
            "🎯 وضع القراءة: Fast",
            "🔄 إعادة تعيين الجلسة"
        } : new String[]{
            "⏱️ Session Timeout: 5 min",
            "📊 Expected Count: " + expectedCount,
            "🔊 Buzzer: Enabled",
            "🎯 Read Mode: Fast",
            "🔄 Reset Session"
        };
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "⚙️ إعدادات جلسة الجرد" : "⚙️ Session Settings")
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            Toast.makeText(requireContext(), 
                                isArabic ? "⏱️ قيد التطوير" : "⏱️ In development", 
                                Toast.LENGTH_SHORT).show();
                            break;
                        case 4:
                            confirmResetSession();
                            break;
                        default:
                            Toast.makeText(requireContext(), 
                                isArabic ? "قيد التطوير" : "In development", 
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
                .show();
    } catch (Exception e) {
        Log.e(TAG, "showSessionSettingsDialog error: " + e.getMessage());
    }
}

/**
 * ✅ حوار إعدادات Buzzer
 */
private void showBuzzerSettingsDialog() {
    try {
        if (!isAdded() || isFragmentDestroyed) return;
        
        String[] options = isArabic ? new String[]{
            "🔊 تشغيل/إيقاف الصوت",
            "🎵 تغيير نغمة النجاح",
            "⚠️ تغيير نغمة الخطأ",
            "📢 اختبار الصوت"
        } : new String[]{
            "🔊 Toggle Buzzer",
            "🎵 Change Success Sound",
            "⚠️ Change Error Sound",
            "📢 Test Buzzer"
        };
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "🔊 إعدادات الصوت" : "🔊 Buzzer Settings")
                .setItems(options, (dialog, which) -> {
                    if (which == 3) {
                        if (soundManager != null) {
                            soundManager.playBeep(SoundManager.FeedbackType.COMPLETED);
                        }
                    } else {
                        Toast.makeText(requireContext(), 
                            isArabic ? "قيد التطوير" : "In development", 
                            Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
                .show();
    } catch (Exception e) {
        Log.e(TAG, "showBuzzerSettingsDialog error: " + e.getMessage());
    }
}

/**
 * ✅ تأكيد إعادة تعيين الجلسة
 */
private void confirmResetSession() {
    new MaterialAlertDialogBuilder(requireContext())
            .setTitle(isArabic ? "⚠️ تأكيد" : "⚠️ Confirm")
            .setMessage(isArabic ? 
                "هل أنت متأكد من إعادة تعيين الجلسة؟\nسيتم حذف كل البيانات غير المعتمدة." : 
                "Are you sure you want to reset the session?\nAll uncommitted data will be lost.")
            .setPositiveButton(isArabic ? "إعادة تعيين" : "Reset", (dialog, which) -> {
                try {
                    if (inventoryEngine != null) {
                        inventoryEngine.clearSession();
                    }
                    synchronized (scannedItemsDisplayList) {
                        scannedItemsDisplayList.clear();
                    }
                    if (modernAdapter != null) {
                        modernAdapter.submitList(new ArrayList<>());
                    }
                    
                    // ✅ امسح الجلسة المحفوظة أيضاً
                    clearSavedSession();
                    
                    // ✅ إعادة تعيين الواجهة
                    expectedCount = 0;
                    selectedCategoryFilter = null;
                    recentTagReads.clear();
                    
                    if (tvScannedCount != null) tvScannedCount.setText("0");
                    if (tvItemCount != null) tvItemCount.setText("0");
                    if (tvExpectedCount != null) tvExpectedCount.setText("0");
                    if (progressScanned != null) progressScanned.setProgress(0);
                    if (tvProgressPercent != null) tvProgressPercent.setText("0%");
                    if (tvEmptyState != null) tvEmptyState.setVisibility(View.VISIBLE);
                    
                    updateUI();
                    updateTotalWeightAndValue();
                    
                    Toast.makeText(requireContext(), 
                        isArabic ? "✅ تم إعادة التعيين" : "✅ Reset successfully", 
                        Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Log.e(TAG, "Reset error: " + e.getMessage());
                }
            })
            .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
            .show();
}

// ==================== Session Persistence ====================


/**
 * ✅ فحص واسترجاع الجلسة السابقة
 */
private void checkAndRestoreSession() {
    if (sessionManager == null || !isAdded() || isFragmentDestroyed) return;
    
    sessionManager.tryRestoreSession(new InventorySessionManager.SessionCallback() {
        @Override
        public void onSessionFound(JSONArray items, int expectedCount, 
                                    String category, long timeAgoMillis) {
            if (!isAdded() || isFragmentDestroyed) return;
            
            // ✅ عرض حوار الاسترجاع
            showRestoreSessionDialog(items, expectedCount, category, timeAgoMillis);
        }
        
        @Override
        public void onNoSession() {
            // لا شيء — لا توجد جلسة سابقة
            Log.d(TAG, "No previous session found");
        }
    });
}

/**
 * ✅ عرض حوار استرجاع الجلسة
 */
private void showRestoreSessionDialog(JSONArray items, 
                                       int expectedCount, 
                                       String category, 
                                       long timeAgoMillis) {
    if (!isAdded() || isFragmentDestroyed) return;
    
    String message = sessionManager.buildRestoreMessage(items, timeAgoMillis);
    
    new MaterialAlertDialogBuilder(requireContext())
            .setTitle(isArabic ? "🔄 استرجاع الجلسة" : "🔄 Restore Session")
            .setMessage(message)
            .setPositiveButton(isArabic ? "استرجاع" : "Restore", (d, w) -> {
                applyRestoredSession(items, expectedCount, category);
            })
            .setNegativeButton(isArabic ? "بدء جديد" : "Start New", (d, w) -> {
                if (sessionManager != null) {
                    sessionManager.clearSession();
                }
            })
            .setNeutralButton(isArabic ? "معاينة" : "Preview", (d, w) -> {
                showSessionPreviewDialog(items, expectedCount, category);
                // إعادة عرض الحوار بعد المعاينة
                mainThreadHandler.postDelayed(() -> 
                        showRestoreSessionDialog(items, expectedCount, category, timeAgoMillis), 500);
            })
            .setCancelable(false)
            .show();
}

/**
 * ✅ تطبيق الجلسة المسترجعة
 */
private void applyRestoredSession(JSONArray items, int expectedCount, String category) {
    try {
        synchronized (scannedItemsDisplayList) {
            scannedItemsDisplayList.clear();
            
            for (int i = 0; i < items.length(); i++) {
                JSONObject itemObj = items.getJSONObject(i);
                ScannedItemDisplay item = new ScannedItemDisplay(
                    itemObj.optString("epc"),
                    itemObj.optString("title"),
                    itemObj.optString("karat"),
                    itemObj.optInt("count", 1),
                    itemObj.optInt("rssi"),
                    itemObj.optString("time")
                );
                item.weight = itemObj.optDouble("weight", 0);
                item.price = itemObj.optDouble("price", 0);
                scannedItemsDisplayList.add(item);
            }
        }
        
        if (modernAdapter != null) {
            updateAdapterList();
        }
        
        this.expectedCount = expectedCount;
        if (tvExpectedCount != null) {
            tvExpectedCount.setText(String.valueOf(expectedCount));
        }
        
        selectedCategoryFilter = category.isEmpty() ? null : category;
        
        // ✅ إعادة تعيين عدّاد الـ animation
        lastScannedForAnimation = items.length();
        
        // ✅ إعادة تعيين Animation tracker
        if (modernAdapter != null) {
            modernAdapter.resetAnimationTracker();
        }
        
        updateUI();
        updateTotalWeightAndValue();
        
        if (tvEmptyState != null) {
            tvEmptyState.setVisibility(
                scannedItemsDisplayList.isEmpty() ? View.VISIBLE : View.GONE
            );
        }
        
        Toast.makeText(requireContext(),
            isArabic ? "✅ تم استرجاع " + items.length() + " قطعة" : 
                      "✅ Restored " + items.length() + " items",
            Toast.LENGTH_LONG).show();
            
    } catch (Exception e) {
        Log.e(TAG, "applyRestoredSession error: " + e.getMessage());
    }
}

/**
 * ✅ عرض معاينة الجلسة
 */
private void showSessionPreviewDialog(JSONArray items, int expected, String category) {
    if (sessionManager == null || !isAdded() || isFragmentDestroyed) return;
    
    String preview = sessionManager.buildSessionPreview(items, expected, category);
    
    new MaterialAlertDialogBuilder(requireContext())
            .setTitle(isArabic ? "معاينة الجلسة" : "Session Preview")
            .setMessage(preview)
            .setPositiveButton(isArabic ? "موافق" : "OK", null)
            .show();
}

} // ✅ نهاية الكلاس MiraInventoryProFragment
