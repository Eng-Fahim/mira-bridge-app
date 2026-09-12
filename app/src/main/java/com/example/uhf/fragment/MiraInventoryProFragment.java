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
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
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
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.adapter.ModernScannedItemsAdapter;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.utils.SoundManager;
import com.example.uhf.utils.PrintUtils;
import com.example.uhf.utils.RFIDCodeUtils;
import com.example.uhf.viewmodel.MiraInventoryViewModel;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  MiraInventoryProFragment — الإصدار 3.0 (ViewModel-Integrated)
 * ═══════════════════════════════════════════════════════════════════════════
 *  
 *  🎯 الترقية الرئيسية:
 *  - كل البيانات الحرجة انتقلت إلى MiraInventoryViewModel
 *  - البيانات تبقى عبر التنقلات بين الشاشات
 *  - الأداء محسّن بـ Root View Caching
 *  - LiveData يُحدّث الواجهة تلقائياً
 *  
 *  ✅ الميزات المُحافظ عليها:
 *  - ListAdapter + DiffUtil
 *  - Power Control Slider (5-30 dBm)
 *  - Search Bar حي
 *  - Segmented Control (شامل/فئوي/أعمى)
 *  - Hero Progress Circle
 *  - Floating Action Dock
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

    // ═══════════════════════════════════════════════════════════════════
    // 🎯 ViewModel — المصدر الوحيد للحقيقة
    // ═══════════════════════════════════════════════════════════════════
    private MiraInventoryViewModel viewModel;

    // ═══════════════════════════════════════════════════════════════════
    // 🖼️ Root View Cache — منع إعادة inflate
    // ═══════════════════════════════════════════════════════════════════
    private View rootView;
    private boolean isViewInitialized = false;

    // ==================== مكونات الواجهة - Hero Circle ====================
    private TextView tvProgressPercent;
    private ProgressBar progressScanned;
    private TextView tvScannedCount;
    private TextView tvExpectedCount;

    // ==================== Status Header ====================
    private View statusDot;
    private View statusBar;
    private TextView tvInventoryStatus;
    private TextView tvElapsedTime;
    private TextView tvRssi;
    private ImageView btnSettings;

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

    // ==================== RecyclerView ====================
    private RecyclerView rvInventoryItems;
    private ModernScannedItemsAdapter modernAdapter;
    private View tvEmptyState;

    // ==================== Floating Action Dock ====================
    private MaterialButton btnMainToggleScan;
    private MaterialButton btnCommitSession;

    // ═══════════════════════════════════════════════════════════════════
    // ✅ الحقول التالية بقيت في Fragment (لأنها غير مرتبطة بحالة الجلسة)
    // ═══════════════════════════════════════════════════════════════════

    // ==================== إدارة الخيوط ====================
    private ExecutorService backgroundExecutor;
    private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());
    private volatile boolean isFragmentDestroyed = false;
    private volatile boolean isArabic = true;

    private final AtomicBoolean isSyncing = new AtomicBoolean(false);
    private final AtomicBoolean isCommitting = new AtomicBoolean(false);

    // ==================== مكونات RFID ====================
    private RFIDManager rfidManager;
    private SoundManager soundManager;

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
        }
    };
    private volatile boolean pendingUiUpdate = false;

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isCurrentScanning() && !isFragmentDestroyed) {
                updateElapsedTime();
                mainThreadHandler.postDelayed(this, 1000);
            }
        }
    };

    // ==================== مكونات الأعمال ====================
    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private PermissionManager permissionManager;

    // ==================== معالجة زر الرجوع ====================
    private OnBackPressedCallback backPressedCallback;

    // ==================== قائمة ممسوحات معروضة (snapshot للـ adapter) ====================
    // ⚠️ هذه نسخة للعرض فقط — المصدر الحقيقي في ViewModel
    private List<ScannedItemDisplay> displaySnapshot = new ArrayList<>();

    // ═══════════════════════════════════════════════════════════════════
    //  Factory Methods
    // ═══════════════════════════════════════════════════════════════════

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
        Boolean scanning = viewModel != null
                ? viewModel.getIsScanning().getValue()
                : null;
        return Boolean.TRUE.equals(scanning);
    }

    private boolean isCurrentScanning() {
        return isScanning();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  دورة حياة Fragment
    // ═══════════════════════════════════════════════════════════════════

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // ✅ Root View Cache — منع إعادة inflate
        if (rootView == null) {
            try {
                rootView = inflater.inflate(R.layout.fragment_mira_inventory_pro, container, false);
            } catch (Exception e) {
                Log.e(TAG, "Inflate error: " + e.getMessage(), e);
                TextView textView = new TextView(requireContext());
                textView.setText("✅ الجرد يعمل (Fallback)\n\nخطأ: " + e.getMessage());
                textView.setTextSize(16);
                textView.setGravity(android.view.Gravity.CENTER);
                return textView;
            }
        } else {
            // ✅ إزالة rootView من parent السابق (إن وُجد)
            if (rootView.getParent() != null) {
                ((ViewGroup) rootView.getParent()).removeView(rootView);
            }
        }

        isFragmentDestroyed = false;

        // ✅ الحصول على ViewModel (Activity-scoped)
        viewModel = new ViewModelProvider(requireActivity()).get(MiraInventoryViewModel.class);

        // ✅ تهيئة الـ Executor
        try {
            if (backgroundExecutor == null || backgroundExecutor.isShutdown()) {
                backgroundExecutor = Executors.newSingleThreadExecutor();
            }
        } catch (Exception e) {
            Log.e(TAG, "Executor init error", e);
        }

        // ✅ اللغة
        try {
            isArabic = Locale.getDefault().getLanguage().equals("ar");
            rootView.setLayoutDirection(isArabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        } catch (Exception e) {
            Log.e(TAG, "Locale error", e);
        }

        // ✅ Sound Manager
        try {
            soundManager = new SoundManager(requireContext());
        } catch (Exception e) {
            Log.e(TAG, "Sound: " + e.getMessage());
        }

        // ✅ قاعدة البيانات
        try {
            initDatabase();
        } catch (Exception e) {
            Log.e(TAG, "DB: " + e.getMessage());
        }

        // ✅ API + Managers
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

        // ✅ RFID Manager
        try {
            rfidManager = RFIDManager.getInstance(requireContext());
            rfidManager.setListener(this);
        } catch (Exception e) {
            rfidManager = null;
        }

        return rootView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // ✅ تهيئة Views مرة واحدة فقط (بفضل Root View Cache)
        if (!isViewInitialized) {
            try {
                initViews(view);
                setupBackPressHandler();
                applyPermissions();
                applyLanguage();
                isViewInitialized = true;
                Log.d(TAG, "Views initialized for the first time");
            } catch (Exception e) {
                Log.e(TAG, "Init error: " + e.getMessage(), e);
            }
        } else {
            Log.d(TAG, "Views already initialized — reusing");
        }

        // ✅ ربط LiveData (يُنفَّذ في كل مرة — آمن)
        observeViewModel();

        // ✅ استعادة حالة الجرد إن كانت نشطة
        restoreScanningStateIfNeeded();
    }

    /**
     * ✅ مراقبة كل LiveData — يُحدّث الواجهة تلقائياً عند أي تغيير
     */
    private void observeViewModel() {
        if (viewModel == null) return;

        // 📋 قائمة العناصر الممسوحة
        viewModel.getScannedItems().observe(getViewLifecycleOwner(), items -> {
            if (items == null) items = new ArrayList<>();
            displaySnapshot = new ArrayList<>(items);
            updateAdapterList();
            updateEmptyState();
        });

        // 🔢 العدد الممسوح
        viewModel.getScannedCount().observe(getViewLifecycleOwner(), count -> {
            if (tvScannedCount != null) {
                tvScannedCount.setText(String.valueOf(count != null ? count : 0));
            }
            if (tvItemCount != null) {
                tvItemCount.setText(String.valueOf(count != null ? count : 0));
            }
            updateUI();
        });

        // 📊 العدد المتوقع
        viewModel.getExpectedCount().observe(getViewLifecycleOwner(), expected -> {
            int exp = expected != null ? expected : 0;
            if (tvExpectedCount != null) {
                tvExpectedCount.setText(exp > 0 ? String.valueOf(exp) : "—");
            }
            updateUI();
        });

        // 🟢 حالة المسح
        viewModel.getIsScanning().observe(getViewLifecycleOwner(), scanning -> {
            boolean isScan = Boolean.TRUE.equals(scanning);
            updateMainToggleButton(isScan);
            
            if (isScan) {
                // ✅ بدء العدّاد
                mainThreadHandler.removeCallbacks(timerRunnable);
                mainThreadHandler.post(timerRunnable);
            } else {
                mainThreadHandler.removeCallbacks(timerRunnable);
            }
        });

        // 🎛️ نمط الجرد
        viewModel.getCurrentMode().observe(getViewLifecycleOwner(), mode -> {
            if (segmentedModes != null && mode != null) {
                try {
                    if ("category".equals(mode)) {
                        segmentedModes.check(R.id.btnModeCategory);
                    } else if ("blind".equals(mode)) {
                        segmentedModes.check(R.id.btnModeBlind);
                    } else {
                        segmentedModes.check(R.id.btnModeFull);
                    }
                } catch (Exception ignored) {}
            }
        });

        // ⚡ قدرة الهوائي
        viewModel.getCurrentPowerDbm().observe(getViewLifecycleOwner(), dbm -> {
            if (btnPowerControl != null && dbm != null) {
                btnPowerControl.setText("⚡ " + dbm + " dBm");
            }
        });

        // 🔍 نص البحث
        viewModel.getCurrentSearchQuery().observe(getViewLifecycleOwner(), query -> {
            if (etSearchItems != null && query != null && !query.equals(etSearchItems.getText().toString())) {
                etSearchItems.setText(query);
                if (btnClearSearch != null) {
                    btnClearSearch.setVisibility(query.length() > 0 ? View.VISIBLE : View.GONE);
                }
            }
        });
    }

    /**
     * ✅ استعادة حالة الجرد النشط عند العودة للشاشة
     */
    private void restoreScanningStateIfNeeded() {
        if (viewModel == null) return;
        
        Boolean scanning = viewModel.getIsScanning().getValue();
        if (Boolean.TRUE.equals(scanning) && !isFragmentDestroyed) {
            Log.d(TAG, "Restoring active scanning state");
            
            // إعادة تفعيل القفل
            try {
                if (getActivity() instanceof UHFMainActivity) {
                    ((UHFMainActivity) getActivity()).enterInventoryLockMode();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error restoring lock mode", e);
            }
            
            // إعادة تشغيل مصدر القراءة
            startReadingSource();
            
            // استئناف المؤقت
            mainThreadHandler.post(timerRunnable);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  تهيئة قاعدة البيانات
    // ═══════════════════════════════════════════════════════════════════

    private void initDatabase() {
        try {
            inventoryDatabase = InventoryDatabase.getInstance(
                    requireContext().getApplicationContext());
            transactionDao = inventoryDatabase.inventoryTransactionDao();
        } catch (Exception e) {
            Log.e(TAG, "Error initializing inventory database", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  إعداد معالج زر الرجوع
    // ═══════════════════════════════════════════════════════════════════

    private void setupBackPressHandler() {
        backPressedCallback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isCurrentScanning()) {
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
     * 🆕 عرض تحذير الخروج الاحترافي (3 خيارات)
     */
    private void showExitWarning(Runnable onConfirm) {
        if (!isAdded() || isFragmentDestroyed) return;

        int scanned = viewModel != null && viewModel.getInventoryEngine() != null
                ? viewModel.getInventoryEngine().getUniqueCount() : 0;
        Long startTime = viewModel != null ? viewModel.getScanStartTime().getValue() : 0L;
        long elapsedSeconds = startTime != null && startTime > 0
                ? (SystemClock.elapsedRealtime() - startTime) / 1000 : 0;
        long minutes = elapsedSeconds / 60;
        long seconds = elapsedSeconds % 60;

        String title = isArabic ? "🔴 جرد نشط" : "🔴 Inventory Active";
        String message = isArabic ?
                "لا يمكنك الخروج أثناء الجرد النشط.\n\n" +
                        "📊 القطع الممسوحة: " + scanned + "\n" +
                        "⏱ الوقت المنقضي: " + String.format(Locale.US, "%02d:%02d", minutes, seconds) +
                        "\n\nاختر الإجراء المناسب:" :
                "You cannot exit during an active inventory.\n\n" +
                        "📊 Scanned items: " + scanned + "\n" +
                        "⏱ Elapsed time: " + String.format(Locale.US, "%02d:%02d", minutes, seconds) +
                        "\n\nChoose the appropriate action:";

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(isArabic ? "✅ البقاء في الجرد" : "✅ Stay",
                        (dialog, which) -> dialog.dismiss())
                .setNeutralButton(isArabic ? "⏹️ إيقاف والانتقال" : "⏹️ Stop & Exit",
                        (dialog, which) -> {
                            stopScanning();
                            if (onConfirm != null) {
                                onConfirm.run();
                            }
                        })
                .setNegativeButton(isArabic ? "📊 اعتماد الآن" : "📊 Commit Now",
                        (dialog, which) -> showCommitSummaryDialog())
                .setCancelable(false)
                .show();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  تطبيق الصلاحيات
    // ═══════════════════════════════════════════════════════════════════

    private void applyPermissions() {
        if (permissionManager == null) return;

        boolean canEdit = permissionManager.canEdit("inventory") || permissionManager.isManager();

        if (btnMainToggleScan != null) btnMainToggleScan.setEnabled(canEdit);
        if (btnCommitSession != null) btnCommitSession.setEnabled(canEdit);

        if (!canEdit && tvInventoryStatus != null) {
            tvInventoryStatus.setText(isArabic ? "⛔ غير مصرح لك بالجرد" : "⛔ Access denied");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  تطبيق اللغة
    // ═══════════════════════════════════════════════════════════════════

    private void applyLanguage() {
        if (btnMainToggleScan != null)
            btnMainToggleScan.setText(isArabic ? "▶️ بدء جرد RFID" : "▶️ Start RFID Inventory");
        if (btnCommitSession != null)
            btnCommitSession.setText(isArabic ? "✅ اعتماد" : "✅ Commit");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  تهيئة عناصر الواجهة
    // ═══════════════════════════════════════════════════════════════════

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
        btnSettings = view.findViewById(R.id.btnSettings);

        // ==================== Stats Strip ====================
        tvVariance = view.findViewById(R.id.tvVariance);
        tvAccuracy = view.findViewById(R.id.tvAccuracy);
        tvItemCount = view.findViewById(R.id.tvItemCount);

        // ==================== Search ====================
        etSearchItems = view.findViewById(R.id.etSearchItems);
        btnClearSearch = view.findViewById(R.id.btnClearSearch);

        // ==================== RecyclerView ====================
        rvInventoryItems = view.findViewById(R.id.rvInventoryItems);
        tvEmptyState = view.findViewById(R.id.tvEmptyState);

        if (rvInventoryItems != null) {
            rvInventoryItems.setLayoutManager(new LinearLayoutManager(requireContext()));
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
        setupSearchBar();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  إعداد الأزرار
    // ═══════════════════════════════════════════════════════════════════

    private void setupButtons() {
        // ==================== زر البدء/الإيقاف الذكي ====================
        if (btnMainToggleScan != null) {
            btnMainToggleScan.setOnClickListener(v -> {
                if (isCurrentScanning()) {
                    stopScanning();
                } else {
                    if (permissionManager != null &&
                            (permissionManager.canEdit("inventory") || permissionManager.isManager())) {
                        showInventorySettingsSheet();
                    } else {
                        showAccessDenied();
                    }
                }
            });
        }

        // ==================== زر الاعتماد ====================
        if (btnCommitSession != null) {
            btnCommitSession.setOnClickListener(v -> showCommitSummaryDialog());
        }

        // ==================== زر الإعدادات في Status Bar ====================
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> showInventorySettingsSheet());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  إعداد شريط البحث
    // ═══════════════════════════════════════════════════════════════════

    private void setupSearchBar() {
        if (etSearchItems == null) return;

        etSearchItems.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString();
                // ✅ حفظ في ViewModel
                if (viewModel != null) {
                    viewModel.setCurrentSearchQuery(query.toLowerCase().trim());
                }
                if (btnClearSearch != null) {
                    btnClearSearch.setVisibility(query.length() > 0 ? View.VISIBLE : View.GONE);
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

    // ═══════════════════════════════════════════════════════════════════
    //  عرض رفض الوصول
    // ═══════════════════════════════════════════════════════════════════

    private void showAccessDenied() {
        if (!isAdded() || isFragmentDestroyed) return;
        Toast.makeText(requireContext(),
                isArabic ? "⛔ غير مصرح" : "⛔ Access denied",
                Toast.LENGTH_SHORT).show();
        if (soundManager != null) soundManager.playBeep(SoundManager.FeedbackType.ERROR);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Bottom Sheet: إعدادات الجلسة
    // ═══════════════════════════════════════════════════════════════════

    private void showInventorySettingsSheet() {
        if (!isAdded() || isFragmentDestroyed) return;

        try {
            BottomSheetDialog dialog = new BottomSheetDialog(
                    requireContext(), R.style.LuxuryBottomSheetDialog);

            View sheetView = getLayoutInflater().inflate(
                    R.layout.bottom_sheet_inventory_settings, null);
            dialog.setContentView(sheetView);

            // ==================== 1. Segmented Control (الأنماط) ====================
            MaterialButtonToggleGroup segmentedModesLocal =
                    sheetView.findViewById(R.id.bsSegmentedModes);
            MaterialButton bsBtnModeFull = sheetView.findViewById(R.id.bsBtnModeFull);
            MaterialButton bsBtnModeCategory = sheetView.findViewById(R.id.bsBtnModeCategory);
            MaterialButton bsBtnModeBlind = sheetView.findViewById(R.id.bsBtnModeBlind);

            String currentMode = viewModel != null && viewModel.getCurrentMode().getValue() != null
                    ? viewModel.getCurrentMode().getValue() : "full";

            if (segmentedModesLocal != null) {
                if ("category".equals(currentMode) && bsBtnModeCategory != null) {
                    segmentedModesLocal.check(R.id.bsBtnModeCategory);
                } else if ("blind".equals(currentMode) && bsBtnModeBlind != null) {
                    segmentedModesLocal.check(R.id.bsBtnModeBlind);
                } else if (bsBtnModeFull != null) {
                    segmentedModesLocal.check(R.id.bsBtnModeFull);
                }

                segmentedModesLocal.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
                    if (!isChecked) return;
                    if (checkedId == R.id.bsBtnModeFull) {
                        if (viewModel != null) viewModel.setCurrentMode("full");
                    } else if (checkedId == R.id.bsBtnModeCategory) {
                        if (viewModel != null) viewModel.setCurrentMode("category");
                    } else if (checkedId == R.id.bsBtnModeBlind) {
                        if (viewModel != null) viewModel.setCurrentMode("blind");
                    }
                });
            }

            // ==================== 2. Power Slider ====================
            Slider sliderPower = sheetView.findViewById(R.id.bsSliderPower);
            TextView tvPowerValue = sheetView.findViewById(R.id.bsTvPowerValue);

            int currentPower = viewModel != null && viewModel.getCurrentPowerDbm().getValue() != null
                    ? viewModel.getCurrentPowerDbm().getValue() : 26;

            if (sliderPower != null) {
                sliderPower.setValue(currentPower);

                sliderPower.addOnChangeListener((slider, value, fromUser) -> {
                    if (viewModel != null) {
                        viewModel.setCurrentPowerDbm((int) value);
                    }
                    if (tvPowerValue != null) {
                        tvPowerValue.setText((int) value + " dBm");
                    }
                });
            }

            if (tvPowerValue != null) {
                tvPowerValue.setText(currentPower + " dBm");
            }

            // ==================== 3. Karat Filter ====================
            com.google.android.material.chip.ChipGroup chipKarat =
                    sheetView.findViewById(R.id.bsChipKarat);

            String currentKarat = viewModel != null && viewModel.getCurrentKaratFilter().getValue() != null
                    ? viewModel.getCurrentKaratFilter().getValue() : "all";

            // ✅ تحديد الحالة الأولية
            if (chipKarat != null) {
                try {
                    if ("24K".equals(currentKarat)) chipKarat.check(R.id.bsChip24K);
                    else if ("22K".equals(currentKarat)) chipKarat.check(R.id.bsChip22K);
                    else if ("21K".equals(currentKarat)) chipKarat.check(R.id.bsChip21K);
                    else if ("18K".equals(currentKarat)) chipKarat.check(R.id.bsChip18K);
                    else if ("14K".equals(currentKarat)) chipKarat.check(R.id.bsChip14K);
                    else chipKarat.check(R.id.bsChipAll);
                } catch (Exception ignored) {}

                chipKarat.setOnCheckedStateChangeListener((group, checkedIds) -> {
                    if (checkedIds.isEmpty() || viewModel == null) return;
                    int id = checkedIds.get(0);
                    if (id == R.id.bsChipAll) {
                        viewModel.setCurrentKaratFilter("all");
                    } else if (id == R.id.bsChip24K) {
                        viewModel.setCurrentKaratFilter("24K");
                    } else if (id == R.id.bsChip22K) {
                        viewModel.setCurrentKaratFilter("22K");
                    } else if (id == R.id.bsChip21K) {
                        viewModel.setCurrentKaratFilter("21K");
                    } else if (id == R.id.bsChip18K) {
                        viewModel.setCurrentKaratFilter("18K");
                    } else if (id == R.id.bsChip14K) {
                        viewModel.setCurrentKaratFilter("14K");
                    }
                });
            }

            // ==================== 4. الأزرار ====================
            MaterialButton btnCancel = sheetView.findViewById(R.id.bsBtnCancel);
            MaterialButton btnSaveAndStart = sheetView.findViewById(R.id.bsBtnSaveAndStart);

            if (btnCancel != null) {
                btnCancel.setOnClickListener(v -> dialog.dismiss());
            }

            if (btnSaveAndStart != null) {
                btnSaveAndStart.setOnClickListener(v -> {
                    int power = viewModel != null && viewModel.getCurrentPowerDbm().getValue() != null
                            ? viewModel.getCurrentPowerDbm().getValue() : 26;
                    applyAntennaPower(power);

                    dialog.dismiss();

                    String mode = viewModel != null && viewModel.getCurrentMode().getValue() != null
                            ? viewModel.getCurrentMode().getValue() : "full";

                    if ("blind".equals(mode)) {
                        startBlindInventory();
                    } else if ("category".equals(mode)) {
                        String karat = viewModel != null && viewModel.getCurrentKaratFilter().getValue() != null
                                ? viewModel.getCurrentKaratFilter().getValue() : "all";
                        Toast.makeText(requireContext(),
                                (isArabic ? "جرد فئوي: " : "Category inventory: ") + karat,
                                Toast.LENGTH_SHORT).show();
                        startScanning();
                    } else {
                        startScanning();
                    }
                });
            }

            dialog.show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing inventory settings sheet", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  معالجة أزرار RFID
    // ═══════════════════════════════════════════════════════════════════

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
                if (!isCurrentScanning()) {
                    startScanning();
                } else {
                    stopScanning();
                }
                return true;
            }
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  معالجة قراءة الوسم
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void onTagRead(String epc, int rssi) {
        if (!isCurrentScanning() || isFragmentDestroyed || epc == null || epc.isEmpty()) return;

        // ✅ تحويل عبر خريطة الباركود من ViewModel
        final String normalizedEpc;
        if (viewModel != null) {
            normalizedEpc = RFIDCodeUtils.normalizeCodeToEpc(epc, viewModel.getBarcodeToEpcMap());
        } else {
            normalizedEpc = epc.toUpperCase();
        }
        final int finalRssi = rssi;

        // ✅ فحص التكرار
        long currentTime = System.currentTimeMillis();
        Map<String, Long> recentTagReads = viewModel != null
                ? viewModel.getRecentTagReads() : null;
        if (recentTagReads == null) return;

        Long lastReadTime = recentTagReads.get(normalizedEpc);
        if (lastReadTime != null && (currentTime - lastReadTime) < TAG_READ_DEBOUNCE_MS) {
            return;
        }

        recentTagReads.put(normalizedEpc, currentTime);
        if (recentTagReads.size() > MAX_CACHE_ITEMS) {
            cleanOldTagReads(currentTime, recentTagReads);
        }

        if (backgroundExecutor == null || backgroundExecutor.isShutdown()) {
            backgroundExecutor = Executors.newSingleThreadExecutor();
        }

        backgroundExecutor.execute(() -> {
            try {
                // ✅ تسجيل في محرّك الجرد (داخل ViewModel)
                if (viewModel != null && viewModel.getInventoryEngine() != null) {
                    viewModel.getInventoryEngine().onTagScanned(normalizedEpc, finalRssi);
                }
                saveTransactionLocally(normalizedEpc, finalRssi);

                mainThreadHandler.post(() -> {
                    if (!isFragmentDestroyed) {
                        processScannedItem(normalizedEpc, finalRssi);
                        updateLastScan(normalizedEpc, finalRssi);
                        scheduleUiUpdate();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error processing tag: " + normalizedEpc, e);
            }
        });
    }

    private void cleanOldTagReads(long currentTime, Map<String, Long> recentTagReads) {
        Iterator<Map.Entry<String, Long>> iterator = recentTagReads.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (currentTime - entry.getValue() > TAG_READ_DEBOUNCE_MS * 2) {
                iterator.remove();
            }
        }
    }

    /**
     * ✅ معالجة القطعة الممسوحة — إضافة إلى ViewModel
     */
    private void processScannedItem(String epc, int rssi) {
        if (!isAdded() || isFragmentDestroyed || viewModel == null || modernAdapter == null) {
            return;
        }

        // ✅ البحث عن معلومات القطعة في ViewModel
        InventoryItem catalogItem = viewModel.getCatalogItemsMap().get(epc);
        boolean isKnown = viewModel.getCatalogEpcs().contains(epc);

        String title = isArabic ? "قطعة غير معروفة" : "Unknown item";
        String karat = "—";
        double weight = 0;
        double price = 0;

        if (catalogItem != null) {
            title = catalogItem.getTitle() != null && !catalogItem.getTitle().isEmpty()
                    ? catalogItem.getTitle()
                    : (isArabic ? "قطعة ذهبية" : "Gold item");
            karat = catalogItem.getKarat() != null && !catalogItem.getKarat().isEmpty()
                    ? catalogItem.getKarat() : "—";
            weight = catalogItem.getWeight();
            price = catalogItem.getPrice();
        } else if (isKnown) {
            title = isArabic ? "مادة مسجلة بالكتالوج" : "Catalog registered item";
        }

        String currentTime = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(new Date());

        ScannedItemDisplay newItem = new ScannedItemDisplay(
                epc, title, karat, 1, rssi, currentTime
        );
        newItem.weight = weight;
        newItem.price = price;

        // ✅ إضافة إلى ViewModel (LiveData سيُحدّث الواجهة تلقائياً)
        MiraInventoryViewModel.ScanFeedback feedback =
                viewModel.addOrUpdateScannedItem(newItem);

        // ✅ تشغيل الصوت المناسب
        if (soundManager != null) {
            switch (feedback) {
                case KNOWN:
                    soundManager.playBeep(SoundManager.FeedbackType.KNOWN_ITEM);
                    break;
                case UNKNOWN:
                    soundManager.playBeep(SoundManager.FeedbackType.UNKNOWN_ITEM);
                    break;
                case DUPLICATE:
                    soundManager.playBeep(SoundManager.FeedbackType.DUPLICATE_ITEM);
                    break;
            }
        }

        // ✅ التمرير للأعلى عند إضافة عنصر جديد
        if (feedback != MiraInventoryViewModel.ScanFeedback.DUPLICATE
                && rvInventoryItems != null) {
            rvInventoryItems.scrollToPosition(0);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  تحديث قائمة الـ Adapter
    // ═══════════════════════════════════════════════════════════════════

    private void updateAdapterList() {
        if (modernAdapter == null || isFragmentDestroyed) return;

        String query = viewModel != null && viewModel.getCurrentSearchQuery().getValue() != null
                ? viewModel.getCurrentSearchQuery().getValue() : "";

        if (query != null && !query.isEmpty()) {
            List<ScannedItemDisplay> filtered = new ArrayList<>();
            for (ScannedItemDisplay item : displaySnapshot) {
                if (item.epc != null && item.epc.toLowerCase().contains(query)) {
                    filtered.add(item);
                } else if (item.title != null && item.title.toLowerCase().contains(query)) {
                    filtered.add(item);
                } else if (item.karat != null && item.karat.toLowerCase().contains(query)) {
                    filtered.add(item);
                }
            }
            modernAdapter.submitList(filtered);
        } else {
            modernAdapter.submitList(new ArrayList<>(displaySnapshot));
        }
    }

    private void updateEmptyState() {
        if (tvEmptyState != null && modernAdapter != null) {
            int count = viewModel != null && viewModel.getScannedCount().getValue() != null
                    ? viewModel.getScannedCount().getValue() : 0;
            tvEmptyState.setVisibility(count > 0 ? View.GONE : View.VISIBLE);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  حفظ المعاملات محلياً
    // ═══════════════════════════════════════════════════════════════════

    private void saveTransactionLocally(String epc, int rssi) {
        try {
            if (viewModel == null || viewModel.getInventoryEngine() == null || transactionDao == null) return;

            InventoryTransactionEntity transaction = new InventoryTransactionEntity();
            transaction.sessionId = viewModel.getInventoryEngine().getClientSessionId();
            transaction.epcCode = epc;
            transaction.rssi = rssi;
            transaction.timestamp = System.currentTimeMillis();
            transaction.deviceId = viewModel.getDeviceId();
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

    // ═══════════════════════════════════════════════════════════════════
    //  المزامنة
    // ═══════════════════════════════════════════════════════════════════

    private void syncOfflineTransactions() {
        if (!isSyncing.compareAndSet(false, true)) return;

        if (backgroundExecutor == null || backgroundExecutor.isShutdown() ||
                transactionDao == null || apiClient == null) {
            isSyncing.set(false);
            return;
        }

        backgroundExecutor.execute(() -> {
            try {
                List<InventoryTransactionEntity> unsynced = transactionDao.getUnsyncedTransactions();
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

    private String getCurrentUserId() {
        String deviceId = viewModel != null ? viewModel.getDeviceId() : "UNKNOWN";
        return "USER-" + (deviceId.length() > 8 ? deviceId.substring(0, 8) : deviceId);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  خيارات القطعة (Dialog)
    // ═══════════════════════════════════════════════════════════════════

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
                        case 3:
                            Toast.makeText(requireContext(),
                                    isArabic ? "قيد التطوير" : "In development",
                                    Toast.LENGTH_SHORT).show();
                            break;
                        case 4: printSingleItemLabel(item); break;
                        case 5: showItemFullDetails(item); break;
                    }
                })
                .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
                .show();
    }

    private void printSingleItemLabel(ScannedItemDisplay item) {
        try {
            String sessionId = viewModel != null && viewModel.getInventoryEngine() != null
                    ? viewModel.getInventoryEngine().getClientSessionId() : "SINGLE";

            InventoryReport singleReport = new InventoryReport(
                    0, sessionId,
                    new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date()),
                    1, 1, 0,
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

    private void updateItemStatus(String epc, String status) {
        Log.d(TAG, "Update status " + epc + " to " + status);
    }

    private void registerItemDamage(String epc, String damageType) {
        Log.d(TAG, "Register damage " + epc + " - " + damageType);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  تحديثات الواجهة
    // ═══════════════════════════════════════════════════════════════════

    private void updateLastScan(String epc, int rssi) {
        if (tvRssi != null) {
            tvRssi.setText(String.format(Locale.US, "📶 %d", rssi));
        }
    }

    private void scheduleUiUpdate() {
        if (!pendingUiUpdate && !isFragmentDestroyed) {
            pendingUiUpdate = true;
            mainThreadHandler.postDelayed(uiUpdateRunnable, UI_UPDATE_THROTTLE_MS);
        }
    }

    private void updateUI() {
        if (!isAdded() || isFragmentDestroyed || viewModel == null) return;
        if (viewModel.getInventoryEngine() == null) return;

        int scanned = viewModel.getInventoryEngine().getUniqueCount();
        int expected = viewModel.getExpectedCount().getValue() != null
                ? viewModel.getExpectedCount().getValue() : 0;

        if (tvScannedCount != null) {
            tvScannedCount.setText(String.valueOf(scanned));
        }
        if (tvItemCount != null) {
            tvItemCount.setText(String.valueOf(scanned));
        }

        if (expected > 0) {
            int progress = Math.min((int) (((float) scanned / expected) * 100), 100);

            if (progressScanned != null) progressScanned.setProgress(progress);
            if (tvProgressPercent != null) tvProgressPercent.setText(progress + "%");
            if (tvVariance != null) tvVariance.setText(String.valueOf(Math.max(0, expected - scanned)));
            if (tvAccuracy != null) tvAccuracy.setText(progress + "%");

            if (scanned >= expected && isCurrentScanning()) {
                stopScanning();
                if (soundManager != null) soundManager.playBeep(SoundManager.FeedbackType.COMPLETED);
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

        if (tvInventoryStatus != null && isCurrentScanning()) {
            tvInventoryStatus.setText((isArabic ? "📡 جاري الجرد... (" :
                    "📡 Scanning... (") + scanned + ")");
        }
    }

    private void updateElapsedTime() {
        if (tvElapsedTime != null && viewModel != null) {
            Long startTime = viewModel.getScanStartTime().getValue();
            if (startTime != null && startTime > 0) {
                long elapsedMillis = SystemClock.elapsedRealtime() - startTime;
                long seconds = elapsedMillis / 1000;
                long hours = seconds / 3600;
                long minutes = (seconds % 3600) / 60;
                long secs = seconds % 60;
                tvElapsedTime.setText(String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  معالجات RFID
    // ═══════════════════════════════════════════════════════════════════

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
            });
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  بدء / إيقاف المسح
    // ═══════════════════════════════════════════════════════════════════

    private void startScanning() {
        if (permissionManager != null &&
                !(permissionManager.canEdit("inventory") || permissionManager.isManager())) {
            showAccessDenied();
            return;
        }
        if (isCurrentScanning() || isFragmentDestroyed) return;

        if (viewModel != null) {
            viewModel.setScanning(true);
        }

        Log.d(TAG, "Start inventory scan");
        loadCatalogAndStart();
    }

    private void startBlindInventory() {
        if (permissionManager != null &&
                !(permissionManager.canEdit("inventory") || permissionManager.isManager())) {
            showAccessDenied();
            return;
        }
        if (isCurrentScanning() || isFragmentDestroyed) return;

        if (viewModel != null) {
            viewModel.setScanning(true);
            viewModel.clearCatalog();
        }

        Log.d(TAG, "Start blind inventory");

        if (tvExpectedCount != null) {
            tvExpectedCount.setText("—");
        }

        if (tvInventoryStatus != null) {
            tvInventoryStatus.setText(isArabic ? "⚡ جرد أعمى - بدون توقعات" :
                    "⚡ Blind inventory - No expectations");
        }

        if (viewModel != null && viewModel.getInventoryEngine() != null) {
            viewModel.getInventoryEngine().startSession(new ConcurrentHashMap<>());
        }

        startReadingSource();
    }

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

                // ✅ حفظ الكتالوج في ViewModel
                if (viewModel != null) {
                    viewModel.updateCatalog(catalog);

                    if (viewModel.getInventoryEngine() != null) {
                        viewModel.getInventoryEngine().startSession(catalog);
                    }
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
     * ✅ جلب الكتالوج من السيرفر — لا يحتاج حفظ هنا (ViewModel يحفظه)
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
                        String karat = obj.optString("gold_karat", obj.optString("karat", "21K"));
                        double weight = obj.optDouble("gross_weight_grams", obj.optDouble("weight", 0));
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

                        Map<String, String> barcodeMap = viewModel != null
                                ? viewModel.getBarcodeToEpcMap()
                                : new ConcurrentHashMap<>();
                        String normalizedEpc = RFIDCodeUtils.normalizeCodeToEpc(primaryEpc, barcodeMap);

                        InventoryItem item = new InventoryItem(
                                normalizedEpc, gtin13, serialNumber, title, karat);
                        item.setWeight(weight);
                        item.setPrice(price);

                        catalog.put(normalizedEpc, item);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching catalog from server", e);
        }

        return catalog;
    }

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

        updateMainToggleButton(true);

        if (soundManager != null) soundManager.playBeep(SoundManager.FeedbackType.KNOWN_ITEM);

        try {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).enterInventoryLockMode();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error entering lock mode", e);
        }
    }

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

    public void stopScanning() {
        if (!isCurrentScanning() || isFragmentDestroyed) return;

        if (viewModel != null) {
            viewModel.setScanning(false);
        }

        stopSimulationLoop();
        mainThreadHandler.removeCallbacks(timerRunnable);

        if (viewModel != null && viewModel.getInventoryEngine() != null) {
            Log.d(TAG, "Stop scan - Scanned: " + viewModel.getInventoryEngine().getUniqueCount());
        }

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

        backgroundExecutor.execute(() -> {
            try {
                if (viewModel != null && viewModel.getInventoryEngine() != null) {
                    viewModel.getInventoryEngine().processSessionClose();
                }

                mainThreadHandler.post(() -> {
                    if (!isAdded() || isFragmentDestroyed) return;

                    if (tvInventoryStatus != null) {
                        tvInventoryStatus.setText(isArabic ? "📡 تم إيقاف الجرد" : "📡 Scan stopped");
                    }

                    Toast.makeText(requireContext(),
                            isArabic ? "🛑 تم إيقاف المسح" : "🛑 Scan stopped",
                            Toast.LENGTH_SHORT).show();

                    if (soundManager != null) soundManager.playBeep(SoundManager.FeedbackType.ERROR);

                    updateMainToggleButton(false);
                    updateUI();
                    syncOfflineTransactions();

                    try {
                        if (getActivity() instanceof UHFMainActivity) {
                            ((UHFMainActivity) getActivity()).exitInventoryLockMode();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error exiting lock mode", e);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error processing session close", e);
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Power Control
    // ═══════════════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════════════
    //  اعتماد الجرد
    // ═══════════════════════════════════════════════════════════════════

    private void showCommitSummaryDialog() {
        if (!isAdded() || isFragmentDestroyed || viewModel == null) return;
        if (viewModel.getInventoryEngine() == null) return;
        if (permissionManager != null &&
                !(permissionManager.canEdit("inventory") || permissionManager.isManager())) {
            showAccessDenied();
            return;
        }

        int scanned = viewModel.getInventoryEngine().getUniqueCount();
        int expected = viewModel.getExpectedCount().getValue() != null
                ? viewModel.getExpectedCount().getValue() : 0;
        int missing = Math.max(0, expected - scanned);
        int accuracy = expected > 0 ? (scanned * 100 / expected) : 0;

        double totalWeight = 0;
        double totalValue = 0;
        for (ScannedItemDisplay item : displaySnapshot) {
            totalWeight += item.weight * item.count;
            totalValue += item.price * item.count;
        }

        final double finalWeight = totalWeight;
        final double finalValue = totalValue;

        try {
            BottomSheetDialog dialog = new BottomSheetDialog(
                    requireContext(), R.style.LuxuryBottomSheetDialog);

            View sheetView = getLayoutInflater().inflate(
                    R.layout.dialog_commit_summary, null);
            dialog.setContentView(sheetView);

            TextView dialogTvExpected = sheetView.findViewById(R.id.dialogTvExpected);
            TextView dialogTvScanned = sheetView.findViewById(R.id.dialogTvScanned);
            TextView dialogTvMissing = sheetView.findViewById(R.id.dialogTvMissing);
            TextView dialogTvAccuracy = sheetView.findViewById(R.id.dialogTvAccuracy);
            TextView dialogTvWeight = sheetView.findViewById(R.id.dialogTvWeight);
            TextView dialogTvValue = sheetView.findViewById(R.id.dialogTvValue);

            if (dialogTvExpected != null) dialogTvExpected.setText(String.valueOf(expected));
            if (dialogTvScanned != null) dialogTvScanned.setText(String.valueOf(scanned));
            if (dialogTvMissing != null) dialogTvMissing.setText(String.valueOf(missing));
            if (dialogTvAccuracy != null) dialogTvAccuracy.setText(accuracy + "%");
            if (dialogTvWeight != null) dialogTvWeight.setText(String.format(Locale.US, "%.2f g", finalWeight));
            if (dialogTvValue != null) dialogTvValue.setText(String.format(Locale.US, "$%,.2f", finalValue));

            MaterialButton btnPrint = sheetView.findViewById(R.id.dialogBtnPrint);
            MaterialButton btnCommit = sheetView.findViewById(R.id.dialogBtnCommit);
            MaterialButton btnCancel = sheetView.findViewById(R.id.dialogBtnCancel);

            if (btnCancel != null) btnCancel.setOnClickListener(v -> dialog.dismiss());
            if (btnPrint != null) {
                btnPrint.setOnClickListener(v -> {
                    dialog.dismiss();
                    printCurrentReport();
                });
            }
            if (btnCommit != null) {
                btnCommit.setOnClickListener(v -> {
                    dialog.dismiss();
                    commitInventoryToServer();
                });
            }

            dialog.show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing commit summary dialog", e);
        }
    }

    private void commitInventoryToServer() {
        if (!isAdded() || isFragmentDestroyed || viewModel == null) return;
        if (viewModel.getInventoryEngine() == null) return;

        if (!isCommitting.compareAndSet(false, true)) {
            Toast.makeText(requireContext(),
                    isArabic ? "⏳ جاري إرسال البيانات بالفعل..." : "⏳ Already sending...",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(requireContext(),
                isArabic ? "⏳ جاري إرسال البيانات..." : "⏳ Sending...",
                Toast.LENGTH_SHORT).show();

        if (backgroundExecutor == null || backgroundExecutor.isShutdown()) {
            backgroundExecutor = Executors.newSingleThreadExecutor();
        }

        final String sessionId = viewModel.getInventoryEngine().getClientSessionId();

        backgroundExecutor.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("session_id", sessionId);
                payload.put("client_session_id", sessionId);

                int branchId = branchesManager != null ? branchesManager.getActiveBranchId() : 0;
                payload.put("branch_id", branchId);
                payload.put("location", "Main Showroom");

                int scanned = viewModel.getInventoryEngine().getUniqueCount();
                int totalReads = viewModel.getInventoryEngine().getTotalReads();
                int expected = viewModel.getExpectedCount().getValue() != null
                        ? viewModel.getExpectedCount().getValue() : 0;

                payload.put("expected_count", expected);
                payload.put("total_scanned", totalReads);
                payload.put("unique_count", scanned);
                payload.put("found_count", scanned);
                payload.put("missing_count", Math.max(0, expected - scanned));
                payload.put("unexpected_count", 0);

                JSONArray itemsArray = new JSONArray();
                JSONArray epcsArray = new JSONArray();

                for (ScannedItemDisplay item : displaySnapshot) {
                    JSONObject itemObj = new JSONObject();
                    itemObj.put("code", item.epc);
                    itemObj.put("serial", item.epc);
                    itemObj.put("rssi", String.valueOf(item.rssi));
                    itemsArray.put(itemObj);
                    epcsArray.put(item.epc);
                }

                payload.put("items", itemsArray);
                payload.put("scanned_epcs", epcsArray);

                MiraApiClient.ApiResponse response = apiClient != null
                        ? apiClient.closeInventorySession(payload) : null;

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

    private void showCommitSuccessDialog(MiraApiClient.ApiResponse response) {
        if (!isAdded() || isFragmentDestroyed || response.data == null) return;

        try {
            JSONObject report = response.data.optJSONObject("report");
            if (report == null) report = response.data;

            String accuracy = report.optString("accuracy", "0");
            String riskLevel = report.optString("risk_level", "unknown");
            String recommendation = report.optString("recommendation", "");

            String message = isArabic ?
                    String.format("✅ تم اعتماد الجرد بنجاح:\n\n• الدقة: %s%%\n• مستوى الخطورة: %s\n• التوصية: %s",
                            accuracy, riskLevel, recommendation) :
                    String.format("✅ Inventory committed successfully:\n\n• Accuracy: %s%%\n• Risk Level: %s\n• Recommendation: %s",
                            accuracy, riskLevel, recommendation);

            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(isArabic ? "🎉 نجاح العملية" : "🎉 Success")
                    .setMessage(message)
                    .setPositiveButton(isArabic ? "🖨️ طباعة التقرير" : "🖨️ Print Report",
                            (dialog, which) -> {
                                if (viewModel == null || viewModel.getInventoryEngine() == null) return;
                                int scanned = viewModel.getInventoryEngine().getUniqueCount();
                                int expected = viewModel.getExpectedCount().getValue() != null
                                        ? viewModel.getExpectedCount().getValue() : 0;
                                InventoryReport committedReport = new InventoryReport(
                                        0,
                                        viewModel.getInventoryEngine().getClientSessionId(),
                                        new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date()),
                                        expected, scanned,
                                        Math.max(0, expected - scanned),
                                        isArabic ? "معتمد" : "Committed"
                                );

                                PrintUtils.printReportWithQRCode(requireContext(), committedReport,
                                        new ArrayList<>(displaySnapshot), isArabic, null);
                            })
                    .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing success dialog", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  طباعة التقارير
    // ═══════════════════════════════════════════════════════════════════

    private void printCurrentReport() {
        try {
            if (viewModel == null || viewModel.getInventoryEngine() == null) return;

            int scanned = viewModel.getInventoryEngine().getUniqueCount();
            int expected = viewModel.getExpectedCount().getValue() != null
                    ? viewModel.getExpectedCount().getValue() : 0;
            int missing = Math.max(0, expected - scanned);

            InventoryReport currentReport = new InventoryReport(
                    0,
                    viewModel.getInventoryEngine().getClientSessionId(),
                    new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date()),
                    expected, scanned, missing,
                    isArabic ? "جارية" : "In progress"
            );

            PrintUtils.printReportWithQRCode(requireContext(), currentReport,
                    new ArrayList<>(displaySnapshot), isArabic, null);
        } catch (Exception e) {
            Log.e(TAG, "Error printing current report", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  المحاكاة
    // ═══════════════════════════════════════════════════════════════════

    private void startSimulationLoop() {
        simulationIndex = 0;

        simulationRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isCurrentScanning() || isFragmentDestroyed) return;

                String epc;

                Set<String> catalogEpcs = viewModel != null
                        ? viewModel.getCatalogEpcs() : Collections.emptySet();
                int expected = viewModel != null && viewModel.getExpectedCount().getValue() != null
                        ? viewModel.getExpectedCount().getValue() : 0;

                if (expected > 0 && !catalogEpcs.isEmpty()) {
                    List<String> epcList = new ArrayList<>(catalogEpcs);
                    epc = epcList.get(simulationIndex % epcList.size());
                } else {
                    epc = simulationTags[simulationIndex % simulationTags.length];
                }

                int rssi = -40 - (int) (Math.random() * 40);
                onTagRead(epc, rssi);

                simulationIndex++;
                long delay = SIMULATION_MIN_DELAY_MS +
                        (long) (Math.random() * SIMULATION_MAX_ADDITIONAL_DELAY_MS);

                if (isCurrentScanning() && !isFragmentDestroyed) {
                    simulationHandler.postDelayed(this, delay);
                }
            }
        };

        simulationHandler.postDelayed(simulationRunnable, SIMULATION_INITIAL_DELAY_MS);
    }

    private void stopSimulationLoop() {
        if (simulationHandler != null && simulationRunnable != null) {
            simulationHandler.removeCallbacks(simulationRunnable);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  دورة الحياة - التنظيف
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void onDestroyView() {
        // ✅ إزالة Callback زر الرجوع
        if (backPressedCallback != null) {
            backPressedCallback.remove();
            backPressedCallback = null;
        }

        isFragmentDestroyed = true;

        // ✅ إلغاء القفل (احترازي)
        try {
            if (getActivity() instanceof UHFMainActivity) {
                Boolean scanning = viewModel != null ? viewModel.getIsScanning().getValue() : null;
                if (!Boolean.TRUE.equals(scanning)) {
                    ((UHFMainActivity) getActivity()).exitInventoryLockMode();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error exiting lock mode on destroy", e);
        }

        // ⚠️ لا نلمس isScanning في ViewModel — الجرد يستمر لو كان نشطاً
        stopSimulationLoop();
        mainThreadHandler.removeCallbacksAndMessages(null);
        simulationHandler.removeCallbacksAndMessages(null);

        // ⚠️ لا نوقف RFID — قد يكون الجرد نشطاً وسيُستعاد
        if (rfidManager != null) {
            try {
                Boolean scanning = viewModel != null ? viewModel.getIsScanning().getValue() : null;
                if (!Boolean.TRUE.equals(scanning)) {
                    rfidManager.stopScan();
                    rfidManager.setListener(null);
                }
                // إذا كان الجرد نشطاً، نُبقي المستمع
            } catch (Exception e) {
                Log.e(TAG, "Error handling RFID on destroy", e);
            }
        }

        // ✅ تنظيف SoundManager
        if (soundManager != null) {
            soundManager.release();
            soundManager = null;
        }

        // ✅ إعادة تعيين حالة التهيئة لإعادة الربط عند العودة
        isViewInitialized = false;

        // ⚠️ لا نمسح displaySnapshot — المصدر في ViewModel
        displaySnapshot.clear();

        // ✅ إزالة rootView من parent (لكن نُبقي المرجع)
        if (rootView != null && rootView.getParent() != null) {
            ((ViewGroup) rootView.getParent()).removeView(rootView);
        }

        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        shutdownExecutor();
        inventoryDatabase = null;
        rootView = null;
    }

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

} // ✅ نهاية الكلاس
