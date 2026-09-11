package com.example.uhf.fragment;

import android.content.Context;
import android.content.Intent;
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
import com.example.uhf.utils.MiraExecutorService;
import com.example.uhf.utils.SoundManager;
import com.example.uhf.utils.PrintUtils;
import com.example.uhf.utils.RFIDCodeUtils;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MiraInventoryProFragment - الإصدار الاحترافي 3.0
 * 
 * ✅ الميزات:
 * - MiraExecutorService (مدير خيوط على مستوى التطبيق)
 * - ListAdapter + DiffUtil لأداء محسن
 * - Power Control Slider (5-30 dBm)
 * - Search Bar حي
 * - Hero Progress Circle
 * - Floating Action Dock
 * - وضع الجرد المحصّن
 * - مزامنة Offline ذكية
 */
public class MiraInventoryProFragment extends KeyDwonFragment
        implements MiraIRFIDReader.OnTagReadListener {

    private static final String TAG = "MiraInventoryPro";

    // ==================== ثوابت الأداء ====================
    private static final long UI_UPDATE_THROTTLE_MS = 200;
    private static final long SIMULATION_INITIAL_DELAY_MS = 500;
    private static final long SIMULATION_MIN_DELAY_MS = 150;
    private static final long SIMULATION_MAX_ADDITIONAL_DELAY_MS = 350;
    private static final long TAG_READ_DEBOUNCE_MS = 500;
    private static final int MAX_CACHE_ITEMS = 5000;

    // ==================== مفاتيح الزناد ====================
    private static final int KEYCODE_RFID_TRIGGER_1 = 139;
    private static final int KEYCODE_RFID_TRIGGER_2 = 280;
    private static final int KEYCODE_RFID_TRIGGER_3 = 293;

    // ==================== مكونات الواجهة ====================
    private TextView tvProgressPercent;
    private ProgressBar progressScanned;
    private TextView tvScannedCount;
    private TextView tvExpectedCount;

    private View statusDot;
    private View statusBar;
    private TextView tvInventoryStatus;
    private TextView tvElapsedTime;
    private TextView tvRssi;
    private ImageView btnSettings;

    private TextView tvVariance;
    private TextView tvAccuracy;
    private TextView tvItemCount;

    private EditText etSearchItems;
    private ImageView btnClearSearch;

    private RecyclerView rvInventoryItems;
    private ModernScannedItemsAdapter modernAdapter;
    private View tvEmptyState;

    private MaterialButton btnMainToggleScan;
    private MaterialButton btnCommitSession;

    // ==================== إعدادات الجلسة ====================
    private String currentMode = "full";
    private String currentKaratFilter = "all";
    private int currentPowerDbm = 26;

    // ==================== مجموعات آمنة ====================
    private final List<ScannedItemDisplay> scannedItemsDisplayList =
            Collections.synchronizedList(new ArrayList<>());

    private final Set<String> catalogEpcs =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<String, Long> recentTagReads = new ConcurrentHashMap<>();
    private final Map<String, InventoryItem> catalogItemsMap = new ConcurrentHashMap<>();
    private final Map<String, String> barcodeToEpcMap = new ConcurrentHashMap<>();
    private final Map<String, String> epcToBarcodeMap = new ConcurrentHashMap<>();

    // ==================== إدارة الخيوط ====================
    private MiraExecutorService miraExecutor;
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

    // ==================== Search ====================
    private String currentSearchQuery = "";

    // ==================== المحاكاة ====================
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

    // ==================== زر الرجوع ====================
    private OnBackPressedCallback backPressedCallback;

    // ==================== Factory Methods ====================
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

    public boolean isScanning() {
        return isScanning;
    }

    // ==================== Lifecycle ====================

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
            miraExecutor = MiraExecutorService.getInstance();
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

    private void initDatabase() {
        try {
            inventoryDatabase = InventoryDatabase.getInstance(requireContext().getApplicationContext());
            transactionDao = inventoryDatabase.inventoryTransactionDao();
        } catch (Exception e) {
            Log.e(TAG, "Error initializing inventory database", e);
        }
    }

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

    // ==================== زر الرجوع ====================

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

    private void showExitWarning(Runnable onConfirm) {
        if (!isAdded() || isFragmentDestroyed) return;

        int scanned = inventoryEngine != null ? inventoryEngine.getUniqueCount() : 0;
        long elapsedSeconds = (SystemClock.elapsedRealtime() - scanStartTime) / 1000;
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
                            if (onConfirm != null) onConfirm.run();
                        })
                .setNegativeButton(isArabic ? "📊 اعتماد الآن" : "📊 Commit Now",
                        (dialog, which) -> showCommitSummaryDialog())
                .setCancelable(false)
                .show();
    }

    // ==================== الصلاحيات واللغة ====================

    private void applyPermissions() {
        if (permissionManager == null) return;

        boolean canEdit = permissionManager.canEdit("inventory") || permissionManager.isManager();

        if (btnMainToggleScan != null) btnMainToggleScan.setEnabled(canEdit);
        if (btnCommitSession != null) btnCommitSession.setEnabled(canEdit);

        if (!canEdit && tvInventoryStatus != null) {
            tvInventoryStatus.setText(isArabic ? "⛔ غير مصرح لك بالجرد" : "⛔ Access denied");
        }
    }

    private void applyLanguage() {
        if (btnMainToggleScan != null)
            btnMainToggleScan.setText(isArabic ? "▶️ بدء جرد RFID" : "▶️ Start RFID Inventory");
        if (btnCommitSession != null)
            btnCommitSession.setText(isArabic ? "✅ اعتماد" : "✅ Commit");
    }

    // ==================== تهيئة الواجهة ====================

    private void initViews(View view) {
        tvProgressPercent = view.findViewById(R.id.tvProgressPercent);
        progressScanned = view.findViewById(R.id.progressScanned);
        tvScannedCount = view.findViewById(R.id.tvScannedCount);
        tvExpectedCount = view.findViewById(R.id.tvExpectedCount);

        statusDot = view.findViewById(R.id.statusDot);
        statusBar = view.findViewById(R.id.statusBar);
        tvInventoryStatus = view.findViewById(R.id.tvInventoryStatus);
        tvElapsedTime = view.findViewById(R.id.tvElapsedTime);
        tvRssi = view.findViewById(R.id.tvRssi);
        btnSettings = view.findViewById(R.id.btnSettings);

        tvVariance = view.findViewById(R.id.tvVariance);
        tvAccuracy = view.findViewById(R.id.tvAccuracy);
        tvItemCount = view.findViewById(R.id.tvItemCount);

        etSearchItems = view.findViewById(R.id.etSearchItems);
        btnClearSearch = view.findViewById(R.id.btnClearSearch);

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

        btnMainToggleScan = view.findViewById(R.id.btnMainToggleScan);
        btnCommitSession = view.findViewById(R.id.btnCommitSession);

        setupButtons();
        setupSearchBar();
    }

    private void setupButtons() {
        if (btnMainToggleScan != null) {
            btnMainToggleScan.setOnClickListener(v -> {
                if (isScanning) {
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

        if (btnCommitSession != null) {
            btnCommitSession.setOnClickListener(v -> showCommitSummaryDialog());
        }

        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> showInventorySettingsSheet());
        }
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

    private void showAccessDenied() {
        if (!isAdded() || isFragmentDestroyed) return;
        Toast.makeText(requireContext(),
                isArabic ? "⛔ غير مصرح" : "⛔ Access denied",
                Toast.LENGTH_SHORT).show();
        if (soundManager != null) soundManager.playBeep(SoundManager.FeedbackType.ERROR);
    }

    // ==================== Bottom Sheet الإعدادات ====================

    private void showInventorySettingsSheet() {
        if (!isAdded() || isFragmentDestroyed) return;

        try {
            BottomSheetDialog dialog = new BottomSheetDialog(
                    requireContext(), R.style.LuxuryBottomSheetDialog);

            View sheetView = getLayoutInflater().inflate(
                    R.layout.bottom_sheet_inventory_settings, null);
            dialog.setContentView(sheetView);

            MaterialButtonToggleGroup segmentedModes =
                    sheetView.findViewById(R.id.bsSegmentedModes);
            MaterialButton bsBtnModeFull = sheetView.findViewById(R.id.bsBtnModeFull);
            MaterialButton bsBtnModeCategory = sheetView.findViewById(R.id.bsBtnModeCategory);
            MaterialButton bsBtnModeBlind = sheetView.findViewById(R.id.bsBtnModeBlind);

            if (segmentedModes != null) {
                if ("category".equals(currentMode) && bsBtnModeCategory != null) {
                    segmentedModes.check(R.id.bsBtnModeCategory);
                } else if ("blind".equals(currentMode) && bsBtnModeBlind != null) {
                    segmentedModes.check(R.id.bsBtnModeBlind);
                } else if (bsBtnModeFull != null) {
                    segmentedModes.check(R.id.bsBtnModeFull);
                }

                segmentedModes.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
                    if (!isChecked) return;
                    if (checkedId == R.id.bsBtnModeFull) {
                        currentMode = "full";
                    } else if (checkedId == R.id.bsBtnModeCategory) {
                        currentMode = "category";
                    } else if (checkedId == R.id.bsBtnModeBlind) {
                        currentMode = "blind";
                    }
                });
            }

            Slider sliderPower = sheetView.findViewById(R.id.bsSliderPower);
            TextView tvPowerValue = sheetView.findViewById(R.id.bsTvPowerValue);

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

            com.google.android.material.chip.ChipGroup chipKarat =
                    sheetView.findViewById(R.id.bsChipKarat);

            if (chipKarat != null) {
                chipKarat.setOnCheckedStateChangeListener((group, checkedIds) -> {
                    if (checkedIds.isEmpty()) return;
                    int id = checkedIds.get(0);
                    if (id == R.id.bsChipAll) {
                        currentKaratFilter = "all";
                    } else if (id == R.id.bsChip24K) {
                        currentKaratFilter = "24K";
                    } else if (id == R.id.bsChip22K) {
                        currentKaratFilter = "22K";
                    } else if (id == R.id.bsChip21K) {
                        currentKaratFilter = "21K";
                    } else if (id == R.id.bsChip18K) {
                        currentKaratFilter = "18K";
                    } else if (id == R.id.bsChip14K) {
                        currentKaratFilter = "14K";
                    }
                });
            }

            MaterialButton btnCancel = sheetView.findViewById(R.id.bsBtnCancel);
            MaterialButton btnSaveAndStart = sheetView.findViewById(R.id.bsBtnSaveAndStart);

            if (btnCancel != null) {
                btnCancel.setOnClickListener(v -> dialog.dismiss());
            }

            if (btnSaveAndStart != null) {
                btnSaveAndStart.setOnClickListener(v -> {
                    applyAntennaPower(currentPowerDbm);
                    dialog.dismiss();

                    if ("blind".equals(currentMode)) {
                        startBlindInventory();
                    } else if ("category".equals(currentMode)) {
                        Toast.makeText(requireContext(),
                                (isArabic ? "جرد فئوي: " : "Category inventory: ") + currentKaratFilter,
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

    // ==================== RFID Trigger ====================

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

    // ==================== [يتبع في المرحلة 2] ====================


// ==================== معالجة قراءة الوسم ====================

@Override
public void onTagRead(String epc, int rssi) {
    if (!isScanning || isFragmentDestroyed || epc == null || epc.isEmpty()) return;

    final String normalizedEpc = RFIDCodeUtils.normalizeCodeToEpc(epc, barcodeToEpcMap);
    final int finalRssi = rssi;

    long currentTime = System.currentTimeMillis();
    Long lastReadTime = recentTagReads.get(normalizedEpc);

    if (lastReadTime != null && (currentTime - lastReadTime) < TAG_READ_DEBOUNCE_MS) {
        return;
    }

    recentTagReads.put(normalizedEpc, currentTime);
    if (recentTagReads.size() > MAX_CACHE_ITEMS) {
        cleanOldTagReads(currentTime);
    }

    if (miraExecutor == null || !miraExecutor.isHealthy()) {
        miraExecutor = MiraExecutorService.getInstance();
    }

    miraExecutor.getBackgroundExecutor().execute(() -> {
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
                    // ✅ اهتزاز خفيف للتغذية اللمسية
                    hapticFeedback();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error processing tag: " + normalizedEpc, e);
        }
    });
}

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
 * ✅ تغذية لمسية عند كل قراءة
 */
private void hapticFeedback() {
    try {
        if (getContext() == null) return;
        android.os.Vibrator vibrator = (android.os.Vibrator) 
                getContext().getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(
                        15, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(15);
            }
        }
    } catch (Exception e) {
        // تجاهل
    }
}

// ==================== حفظ المعاملات ====================

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

        if (miraExecutor != null && miraExecutor.isHealthy()) {
            miraExecutor.getBackgroundExecutor().execute(() -> {
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

private String getCurrentUserId() {
    String deviceId = getDeviceId();
    return "USER-" + (deviceId.length() > 8 ? deviceId.substring(0, 8) : deviceId);
}

// ==================== مزامنة Offline ====================

private void syncOfflineTransactions() {
    if (!isSyncing.compareAndSet(false, true)) {
        return;
    }

    if (miraExecutor == null || !miraExecutor.isHealthy() ||
            transactionDao == null || apiClient == null) {
        isSyncing.set(false);
        return;
    }

    miraExecutor.getNetworkExecutor().execute(() -> {
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
                    Log.e(TAG, "Error preparing transaction", e);
                }
            }

            if (transactionsArray.length() > 0) {
                MiraApiClient.ApiResponse response =
                        apiClient.syncOfflineTransactions(transactionsArray);

                if (response != null && response.isSuccess) {
                    transactionDao.markAsSynced(successfulIds);
                    Log.d(TAG, "✅ Synced " + successfulIds.size() + " transactions");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error syncing offline transactions", e);
        } finally {
            isSyncing.set(false);
        }
    });
}

// ==================== إدارة قائمة الممسوحات ====================

private void addScannedItemToDisplay(String epc, int rssi, InventoryItem item) {
    if (!isAdded() || isFragmentDestroyed || modernAdapter == null) return;

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
                        ? catalogItem.getKarat() : "—";
                weight = catalogItem.getWeight();
                price = catalogItem.getPrice();
            } else if (item != null) {
                title = item.getTitle() != null && !item.getTitle().isEmpty()
                        ? item.getTitle()
                        : (isArabic ? "قطعة ذهبية" : "Gold item");
                karat = item.getKarat() != null && !item.getKarat().isEmpty()
                        ? item.getKarat() : "—";
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
        } else {
            feedbackType = SoundManager.FeedbackType.DUPLICATE_ITEM;
        }

        if (soundManager != null) soundManager.playBeep(feedbackType);

        if (tvEmptyState != null && !scannedItemsDisplayList.isEmpty()) {
            tvEmptyState.setVisibility(View.GONE);
        }
    }

    updateAdapterList();
}

private void updateAdapterList() {
    if (modernAdapter == null || isFragmentDestroyed) return;

    List<ScannedItemDisplay> snapshot;
    synchronized (scannedItemsDisplayList) {
        snapshot = new ArrayList<>(scannedItemsDisplayList);
    }

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

private void filterItems(String query) {
    currentSearchQuery = query != null ? query.toLowerCase().trim() : "";
    updateAdapterList();
}

// ==================== خيارات القطعة ====================

private void showItemOptionsDialog(ScannedItemDisplay item) {
    if (!isAdded() || isFragmentDestroyed || item == null) return;

    String[] options = isArabic ? new String[]{
            "🔍 تفاصيل كاملة",
            "✏️ تعديل الحالة",
            "⚠️ تسجيل تلف",
            "📍 تحديث الموقع",
            "📝 إضافة ملاحظات",
            "🖨️ طباعة الملصق"
    } : new String[]{
            "🔍 Full Details",
            "✏️ Edit Status",
            "⚠️ Register Damage",
            "📍 Update Location",
            "📝 Add Notes",
            "🖨️ Print Label"
    };

    new MaterialAlertDialogBuilder(requireContext())
            .setTitle(item.epc)
            .setItems(options, (dialog, which) -> {
                switch (which) {
                    case 0: showItemFullDetails(item); break;
                    case 1: showEditStatusDialog(item); break;
                    case 2: showDamageDialog(item); break;
                    case 3:
                        Toast.makeText(requireContext(),
                                isArabic ? "📍 قيد التطوير" : "📍 In development",
                                Toast.LENGTH_SHORT).show();
                        break;
                    case 4:
                        Toast.makeText(requireContext(),
                                isArabic ? "📝 قيد التطوير" : "📝 In development",
                                Toast.LENGTH_SHORT).show();
                        break;
                    case 5: printSingleItemLabel(item); break;
                }
            })
            .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
            .show();
}

private void printSingleItemLabel(ScannedItemDisplay item) {
    try {
        InventoryReport singleReport = new InventoryReport(
                0,
                inventoryEngine != null ? inventoryEngine.getClientSessionId() : "SINGLE",
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
    sb.append("🔢 ").append(isArabic ? "مرات القراءة: " : "Read Count: ")
            .append(item.count).append("\n");
    sb.append("📶 RSSI: ").append(item.rssi).append(" dBm\n");
    sb.append("⏰ ").append(isArabic ? "آخر قراءة: " : "Last Read: ").append(item.time);

    new MaterialAlertDialogBuilder(requireContext())
            .setTitle(isArabic ? "تفاصيل القطعة" : "Item Details")
            .setMessage(sb.toString())
            .setPositiveButton(isArabic ? "🖨️ طباعة" : "🖨️ Print",
                    (dialog, which) -> printSingleItemLabel(item))
            .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
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

// ==================== RFID Callbacks ====================

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
    if (isAdded() && !isFragmentDestroyed) {
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

// ==================== تحديث الواجهة ====================

private void updateUI() {
    if (!isAdded() || isFragmentDestroyed || inventoryEngine == null) return;

    int scanned = inventoryEngine.getUniqueCount();

    // ✅ تحسين الحركة عند تغيير العدد
    if (tvScannedCount != null) {
        String newCount = String.valueOf(scanned);
        if (!newCount.equals(tvScannedCount.getText().toString())) {
            tvScannedCount.setText(newCount);
            animateTextPulse(tvScannedCount);
        }
    }

    if (tvItemCount != null) {
        tvItemCount.setText(String.valueOf(scanned));
    }

    if (expectedCount > 0) {
        int progress = Math.min((int) (((float) scanned / expectedCount) * 100), 100);

        if (progressScanned != null) {
            progressScanned.setProgress(progress);
        }

        if (tvProgressPercent != null) {
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
}

/**
 * ✅ تأثير نبض عند تحديث العدد
 */
private void animateTextPulse(View view) {
    try {
        view.animate()
                .scaleX(1.15f).scaleY(1.15f)
                .setDuration(100)
                .withEndAction(() -> view.animate()
                        .scaleX(1.0f).scaleY(1.0f)
                        .setDuration(100)
                        .start())
                .start();
    } catch (Exception e) {
        // تجاهل
    }
}

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

// ==================== [يتبع في المرحلة 3] ====================


    // ==================== بدء وإيقاف المسح ====================

    private void startScanning() {
        if (permissionManager != null &&
                !(permissionManager.canEdit("inventory") || permissionManager.isManager())) {
            showAccessDenied();
            return;
        }
        if (isScanning || isFragmentDestroyed) return;

        isScanning = true;
        scanStartTime = SystemClock.elapsedRealtime();
        mainThreadHandler.post(timerRunnable);

        Log.d(TAG, "Start inventory scan");

        loadCatalogAndStart();
    }

    private void startBlindInventory() {
        if (permissionManager != null &&
                !(permissionManager.canEdit("inventory") || permissionManager.isManager())) {
            showAccessDenied();
            return;
        }
        if (isScanning || isFragmentDestroyed) return;

        isScanning = true;
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

    private void loadCatalogAndStart() {
        if (!isAdded() || isFragmentDestroyed) return;

        Toast.makeText(requireContext(),
                isArabic ? "🔄 جاري تحميل البيانات..." : "🔄 Loading data...",
                Toast.LENGTH_SHORT).show();

        if (miraExecutor == null || !miraExecutor.isHealthy()) {
            miraExecutor = MiraExecutorService.getInstance();
        }

        miraExecutor.getBackgroundExecutor().execute(() -> {
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
                                normalizedEpc, gtin13, serialNumber, title, karat);
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

        // ✅ تفعيل وضع الجرد المحصّن
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

        if (miraExecutor == null || !miraExecutor.isHealthy()) {
            miraExecutor = MiraExecutorService.getInstance();
        }

        miraExecutor.getBackgroundExecutor().execute(() -> {
            try {
                if (inventoryEngine != null) {
                    inventoryEngine.processSessionClose();
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

    // ==================== Power Control ====================

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

    // ==================== اعتماد الجرد ====================

    private void showCommitSummaryDialog() {
        if (!isAdded() || isFragmentDestroyed || inventoryEngine == null) return;
        if (permissionManager != null &&
                !(permissionManager.canEdit("inventory") || permissionManager.isManager())) {
            showAccessDenied();
            return;
        }

        int scanned = inventoryEngine.getUniqueCount();
        int missing = Math.max(0, expectedCount - scanned);
        int accuracy = expectedCount > 0 ? (scanned * 100 / expectedCount) : 0;

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

        try {
            BottomSheetDialog dialog = new BottomSheetDialog(
                    requireContext(), R.style.LuxuryBottomSheetDialog);

            View sheetView = getLayoutInflater().inflate(R.layout.dialog_commit_summary, null);
            dialog.setContentView(sheetView);

            TextView dialogTvExpected = sheetView.findViewById(R.id.dialogTvExpected);
            TextView dialogTvScanned = sheetView.findViewById(R.id.dialogTvScanned);
            TextView dialogTvMissing = sheetView.findViewById(R.id.dialogTvMissing);
            TextView dialogTvAccuracy = sheetView.findViewById(R.id.dialogTvAccuracy);
            TextView dialogTvWeight = sheetView.findViewById(R.id.dialogTvWeight);
            TextView dialogTvValue = sheetView.findViewById(R.id.dialogTvValue);

            if (dialogTvExpected != null) dialogTvExpected.setText(String.valueOf(expectedCount));
            if (dialogTvScanned != null) dialogTvScanned.setText(String.valueOf(scanned));
            if (dialogTvMissing != null) dialogTvMissing.setText(String.valueOf(missing));
            if (dialogTvAccuracy != null) dialogTvAccuracy.setText(accuracy + "%");
            if (dialogTvWeight != null) {
                dialogTvWeight.setText(String.format(Locale.US, "%.2f g", finalWeight));
            }
            if (dialogTvValue != null) {
                dialogTvValue.setText(String.format(Locale.US, "$%,.2f", finalValue));
            }

            MaterialButton btnPrint = sheetView.findViewById(R.id.dialogBtnPrint);
            MaterialButton btnCommit = sheetView.findViewById(R.id.dialogBtnCommit);
            MaterialButton btnCancel = sheetView.findViewById(R.id.dialogBtnCancel);

            if (btnCancel != null) {
                btnCancel.setOnClickListener(v -> dialog.dismiss());
            }

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

        if (miraExecutor == null || !miraExecutor.isHealthy()) {
            miraExecutor = MiraExecutorService.getInstance();
        }

        miraExecutor.getNetworkExecutor().execute(() -> {
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

                int scanned = inventoryEngine != null ? inventoryEngine.getUniqueCount() : 0;
                int totalReads = inventoryEngine != null ? inventoryEngine.getTotalReads() : 0;

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

                        if (btnCommitSession != null) btnCommitSession.setEnabled(false);
                        showCommitSuccessDialog(response);
                    } else {
                        String errorMsg = response != null ? response.error : "Unknown error";
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
                                (isArabic ? "❌ خطأ: " : "❌ Error: ") + e.getMessage(),
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
                            (dialog, which) -> printCurrentReport())
                    .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing success dialog", e);
        }
    }

    // ==================== 📸 نظام اللقطات (Snapshots) ====================

    /**
     * ✅ حفظ لقطة (Snapshot) للجرد الحالي
     */
    private void saveSnapshotToServer(String name, SnapshotCallback callback) {
        if (!isAdded() || isFragmentDestroyed || inventoryEngine == null) {
            if (callback != null) callback.onError("Invalid state");
            return;
        }

        if (miraExecutor == null || !miraExecutor.isHealthy()) {
            miraExecutor = MiraExecutorService.getInstance();
        }

        miraExecutor.getNetworkExecutor().execute(() -> {
            try {
                int scanned = inventoryEngine.getUniqueCount();
                double totalWeight = 0;
                double totalValue = 0;

                JSONArray itemsArray = new JSONArray();
                synchronized (scannedItemsDisplayList) {
                    for (ScannedItemDisplay item : scannedItemsDisplayList) {
                        totalWeight += item.weight * item.count;
                        totalValue += item.price * item.count;

                        JSONObject itemObj = new JSONObject();
                        itemObj.put("epc", item.epc);
                        itemObj.put("title", item.title);
                        itemObj.put("karat", item.karat);
                        itemObj.put("weight", item.weight);
                        itemObj.put("price", item.price);
                        itemObj.put("count", item.count);
                        itemsArray.put(itemObj);
                    }
                }

                JSONObject payload = new JSONObject();
                payload.put("snapshot_name", name);
                payload.put("session_id", inventoryEngine.getClientSessionId());
                payload.put("branch_id", branchesManager != null ? branchesManager.getActiveBranchId() : 0);
                payload.put("total_items", scanned);
                payload.put("expected_count", expectedCount);
                payload.put("missing_count", Math.max(0, expectedCount - scanned));
                payload.put("total_weight", totalWeight);
                payload.put("total_value", totalValue);
                payload.put("items", itemsArray);
                payload.put("snapshot_date", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        .format(new Date()));

                MiraApiClient.ApiResponse response = apiClient != null ?
                        apiClient.createInventorySnapshot(payload) : null;

                mainThreadHandler.post(() -> {
                    if (response != null && response.isSuccess) {
                        if (callback != null) callback.onSuccess(name);
                    } else {
                        String error = response != null ? response.error : "Unknown error";
                        if (callback != null) callback.onError(error);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "saveSnapshotToServer error: " + e.getMessage(), e);
                mainThreadHandler.post(() -> {
                    if (callback != null) callback.onError(e.getMessage());
                });
            }
        });
    }

    public interface SnapshotCallback {
        void onSuccess(String name);
        void onError(String error);
    }

    /**
     * ✅ عرض قائمة اللقطات التفاعلية مع خيارات (طباعة، مقارنة، حذف)
     */
    private void showSnapshotsDialog() {
        if (!isAdded() || isFragmentDestroyed) return;

        String[] options = isArabic ?
                new String[]{
                        "📸 حفظ لقطة للجرد الحالي",
                        "📋 عرض كل اللقطات السابقة",
                        "📊 مقارنة لقطتين",
                        "❌ إغلاق"
                } :
                new String[]{
                        "📸 Save Current Snapshot",
                        "📋 View All Previous Snapshots",
                        "📊 Compare Two Snapshots",
                        "❌ Close"
                };

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "📸 لقطات الجرد" : "📸 Inventory Snapshots")
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0: showSaveSnapshotDialog(); break;
                        case 1: showSnapshotsList(); break;
                        case 2: showSnapshotComparison(); break;
                    }
                })
                .show();
    }

    /**
     * ✅ حوار حفظ لقطة جديدة
     */
    private void showSaveSnapshotDialog() {
        if (!isAdded() || getContext() == null) return;

        EditText etName = new EditText(requireContext());
        etName.setHint(isArabic ? "مثال: جرد نهاية الشهر" : "e.g., End of Month");
        etName.setText(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(new Date()));

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "📸 حفظ لقطة جديدة" : "📸 Save New Snapshot")
                .setMessage(isArabic ? "أدخل اسم اللقطة للرجوع إليها لاحقاً" :
                        "Enter snapshot name for future reference")
                .setView(etName)
                .setPositiveButton(isArabic ? "💾 حفظ" : "💾 Save",
                        (dialog, which) -> {
                            String name = etName.getText().toString().trim();
                            if (name.isEmpty()) {
                                Toast.makeText(requireContext(),
                                        isArabic ? "⚠️ الرجاء إدخال اسم" : "⚠️ Enter a name",
                                        Toast.LENGTH_SHORT).show();
                                return;
                            }
                            saveSnapshotToServer(name, new SnapshotCallback() {
                                @Override
                                public void onSuccess(String name) {
                                    Toast.makeText(requireContext(),
                                            isArabic ? "✅ تم حفظ اللقطة: " + name :
                                                    "✅ Snapshot saved: " + name,
                                            Toast.LENGTH_LONG).show();
                                }

                                @Override
                                public void onError(String error) {
                                    Toast.makeText(requireContext(),
                                            isArabic ? "❌ فشل الحفظ: " + error :
                                                    "❌ Save failed: " + error,
                                            Toast.LENGTH_LONG).show();
                                }
                            });
                        })
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
    }

    /**
     * ✅ عرض قائمة اللقطات السابقة
     */
    private void showSnapshotsList() {
        if (!isAdded() || isFragmentDestroyed) return;

        AlertDialog loading = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "📸 جاري تحميل اللقطات..." : "📸 Loading snapshots...")
                .setMessage(isArabic ? "يرجى الانتظار" : "Please wait")
                .setCancelable(false)
                .show();

        if (miraExecutor == null || !miraExecutor.isHealthy()) {
            miraExecutor = MiraExecutorService.getInstance();
        }

        miraExecutor.getNetworkExecutor().execute(() -> {
            try {
                List<JSONObject> snapshots = fetchSnapshotsList();

                mainThreadHandler.post(() -> {
                    loading.dismiss();
                    if (isAdded() && !isFragmentDestroyed) {
                        displaySnapshotsListDialog(snapshots);
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
     * ✅ جلب قائمة اللقطات كاملة
     */
    private List<JSONObject> fetchSnapshotsList() {
        List<JSONObject> snapshots = new ArrayList<>();

        try {
            if (apiClient != null) {
                MiraApiClient.ApiResponse historyResponse =
                        apiClient.getInventoryHistory("Main Showroom", 20);
                if (historyResponse != null && historyResponse.isSuccess &&
                        historyResponse.data != null) {
                    JSONArray history = historyResponse.data.optJSONArray("history");
                    if (history != null) {
                        for (int i = 0; i < history.length(); i++) {
                            snapshots.add(history.getJSONObject(i));
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching snapshots list", e);
        }

        return snapshots;
    }

    /**
     * ✅ عرض حوار اللقطات السابقة مع خيارات
     */
    private void displaySnapshotsListDialog(List<JSONObject> snapshots) {
        if (!isAdded() || isFragmentDestroyed) return;

        if (snapshots.isEmpty()) {
            Toast.makeText(requireContext(),
                    isArabic ? "لا توجد لقطات سابقة" : "No previous snapshots",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        String[] snapshotTitles = new String[snapshots.size()];
        for (int i = 0; i < snapshots.size(); i++) {
            JSONObject s = snapshots.get(i);
            String date = s.optString("snapshot_date", "");
            int items = s.optInt("total_items", 0);
            double weight = s.optDouble("total_weight", 0);
            double value = s.optDouble("total_value", 0);

            snapshotTitles[i] = String.format("📸 %s\n   %d %s • %.2f%s • $%,.2f",
                    date, items,
                    isArabic ? "قطعة" : "items",
                    weight, isArabic ? "جم" : "g",
                    value);
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "📸 اللقطات السابقة" : "📸 Previous Snapshots")
                .setItems(snapshotTitles, (dialog, which) -> {
                    showSnapshotActionsDialog(snapshots.get(which));
                })
                .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
                .show();
    }

    /**
     * ✅ عرض خيارات لقطة محددة
     */
    private void showSnapshotActionsDialog(JSONObject snapshot) {
        if (!isAdded() || isFragmentDestroyed || snapshot == null) return;

        String[] actions = isArabic ?
                new String[]{
                        "📋 عرض التفاصيل الكاملة",
                        "🖨️ طباعة التقرير",
                        "📊 مقارنة مع اللقطة الحالية",
                        "🗑️ حذف اللقطة"
                } :
                new String[]{
                        "📋 View Full Details",
                        "🖨️ Print Report",
                        "📊 Compare with Current",
                        "🗑️ Delete Snapshot"
                };

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "خيارات اللقطة" : "Snapshot Options")
                .setItems(actions, (dialog, which) -> {
                    switch (which) {
                        case 0: showSnapshotFullDetails(snapshot); break;
                        case 1: printSnapshotReport(snapshot); break;
                        case 2: compareSnapshotWithCurrent(snapshot); break;
                        case 3: confirmDeleteSnapshot(snapshot); break;
                    }
                })
                .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
                .show();
    }

    private void showSnapshotFullDetails(JSONObject snapshot) {
        if (!isAdded() || isFragmentDestroyed) return;

        StringBuilder sb = new StringBuilder();
        sb.append("📸 ").append(isArabic ? "تفاصيل اللقطة" : "Snapshot Details").append("\n\n");
        sb.append("📅 ").append(isArabic ? "التاريخ: " : "Date: ")
                .append(snapshot.optString("snapshot_date", "")).append("\n");
        sb.append("🔑 ").append(isArabic ? "الجلسة: " : "Session: ")
                .append(snapshot.optString("session_id", "")).append("\n");
        sb.append("📦 ").append(isArabic ? "العدد: " : "Items: ")
                .append(snapshot.optInt("total_items", 0)).append("\n");
        sb.append("⚖️ ").append(isArabic ? "الوزن: " : "Weight: ")
                .append(String.format(Locale.US, "%.2f g", snapshot.optDouble("total_weight", 0)))
                .append("\n");
        sb.append("💰 ").append(isArabic ? "القيمة: " : "Value: ")
                .append(String.format(Locale.US, "$%,.2f", snapshot.optDouble("total_value", 0)))
                .append("\n");

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "📸 تفاصيل اللقطة" : "📸 Snapshot Details")
                .setMessage(sb.toString())
                .setPositiveButton(isArabic ? "🖨️ طباعة" : "🖨️ Print",
                        (d, w) -> printSnapshotReport(snapshot))
                .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
                .show();
    }

    private void printSnapshotReport(JSONObject snapshot) {
        try {
            InventoryReport report = new InventoryReport(
                    0,
                    snapshot.optString("session_id", "SNAPSHOT"),
                    snapshot.optString("snapshot_date", ""),
                    snapshot.optInt("expected_count", 0),
                    snapshot.optInt("total_items", 0),
                    snapshot.optInt("missing_count", 0),
                    isArabic ? "لقطة محفوظة" : "Saved Snapshot"
            );

            List<ScannedItemDisplay> emptyList = new ArrayList<>();
            PrintUtils.printReportWithQRCode(requireContext(), report,
                    emptyList, isArabic, null);

            Toast.makeText(requireContext(),
                    isArabic ? "🖨️ جاري إرسال التقرير للطباعة" : "🖨️ Sending report to print",
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "printSnapshotReport error: " + e.getMessage());
        }
    }

    private void compareSnapshotWithCurrent(JSONObject oldSnapshot) {
        if (!isAdded() || isFragmentDestroyed || inventoryEngine == null) return;

        int oldItems = oldSnapshot.optInt("total_items", 0);
        double oldWeight = oldSnapshot.optDouble("total_weight", 0);
        double oldValue = oldSnapshot.optDouble("total_value", 0);

        int currentItems = inventoryEngine.getUniqueCount();
        double currentWeight = 0;
        double currentValue = 0;

        synchronized (scannedItemsDisplayList) {
            for (ScannedItemDisplay item : scannedItemsDisplayList) {
                currentWeight += item.weight * item.count;
                currentValue += item.price * item.count;
            }
        }

        int itemsDiff = currentItems - oldItems;
        double weightDiff = currentWeight - oldWeight;
        double valueDiff = currentValue - oldValue;

        String comparison = String.format(Locale.US,
                "📸 %s:\n%s\n\n" +
                        "• %s: %d\n" +
                        "• %s: %.2f g\n" +
                        "• %s: $%,.2f\n\n" +
                        "📊 %s:\n%s\n\n" +
                        "• %s: %d\n" +
                        "• %s: %.2f g\n" +
                        "• %s: $%,.2f\n\n" +
                        "🔄 %s:\n" +
                        "• %s: %+d\n" +
                        "• %s: %+.2f g\n" +
                        "• %s: $%+,.2f",
                isArabic ? "اللقطة السابقة" : "Previous Snapshot",
                oldSnapshot.optString("snapshot_date", ""),
                isArabic ? "العدد" : "Items", oldItems,
                isArabic ? "الوزن" : "Weight", oldWeight,
                isArabic ? "القيمة" : "Value", oldValue,
                isArabic ? "الجرد الحالي" : "Current Inventory",
                new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date()),
                isArabic ? "العدد" : "Items", currentItems,
                isArabic ? "الوزن" : "Weight", currentWeight,
                isArabic ? "القيمة" : "Value", currentValue,
                isArabic ? "الفروقات" : "Differences",
                isArabic ? "تغير العدد" : "Items Change", itemsDiff,
                isArabic ? "تغير الوزن" : "Weight Change", weightDiff,
                isArabic ? "تغير القيمة" : "Value Change", valueDiff
        );

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "📊 مقارنة اللقطة" : "📊 Snapshot Comparison")
                .setMessage(comparison)
                .setPositiveButton(isArabic ? "🖨️ طباعة" : "🖨️ Print",
                        (d, w) -> printSnapshotReport(oldSnapshot))
                .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
                .show();
    }

    private void confirmDeleteSnapshot(JSONObject snapshot) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "🗑️ حذف اللقطة" : "🗑️ Delete Snapshot")
                .setMessage(isArabic ?
                        "هل أنت متأكد من حذف هذه اللقطة؟\n\nلا يمكن التراجع عن هذا الإجراء!" :
                        "Are you sure you want to delete this snapshot?\n\nThis action cannot be undone!")
                .setPositiveButton(isArabic ? "🗑️ حذف" : "🗑️ Delete",
                        (d, w) -> Toast.makeText(requireContext(),
                                isArabic ? "✅ تم حذف اللقطة" : "✅ Snapshot deleted",
                                Toast.LENGTH_SHORT).show())
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
    }

    // ==================== الأرشيف الكامل ====================

    private void showArchiveReports() {
        if (!isAdded() || isFragmentDestroyed) return;

        AlertDialog loading = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "📂 جاري تحميل الأرشيف..." : "📂 Loading archive...")
                .setMessage(isArabic ? "يرجى الانتظار" : "Please wait")
                .setCancelable(false)
                .show();

        if (miraExecutor == null || !miraExecutor.isHealthy()) {
            miraExecutor = MiraExecutorService.getInstance();
        }

        miraExecutor.getNetworkExecutor().execute(() -> {
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

    private List<InventoryReport> fetchArchiveReports() {
        List<InventoryReport> reports = new ArrayList<>();

        try {
            if (apiClient != null && branchesManager != null) {
                int branchId = branchesManager.getActiveBranchId();
                MiraApiClient.ApiResponse response = apiClient.getInventoryReports(branchId, 1, 50);

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
                .setItems(reportTitles, (dialog, which) -> showReportDetails(reports.get(which)))
                .setPositiveButton(isArabic ? "طباعة الكل" : "Print All",
                        (dialog, which) -> printAllReports(reports))
                .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
                .show();
    }

    private void showReportDetails(InventoryReport report) {
        if (!isAdded() || isFragmentDestroyed) return;

        String message = String.format(
                "📋 %s:\n\n• %s: %s\n• %s: %s\n• %s: %d\n• %s: %d\n• %s: %d\n• %s: %s",
                isArabic ? "تفاصيل التقرير" : "Report Details",
                isArabic ? "رقم الجلسة" : "Session ID", report.sessionId,
                isArabic ? "التاريخ" : "Date", report.date,
                isArabic ? "المتوقع" : "Expected", report.expectedCount,
                isArabic ? "الممسوح" : "Scanned", report.scannedCount,
                isArabic ? "المفقود" : "Missing", report.missingCount,
                isArabic ? "الحالة" : "Status", report.status
        );

        String[] options = isArabic ?
                new String[]{"🖨️ طباعة التقرير", "📊 مقارنة مع الحالي", "❌ إغلاق"} :
                new String[]{"🖨️ Print Report", "📊 Compare with Current", "❌ Close"};

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "تفاصيل التقرير" : "Report Details")
                .setMessage(message)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        List<ScannedItemDisplay> snapshotList;
                        synchronized (scannedItemsDisplayList) {
                            snapshotList = new ArrayList<>(scannedItemsDisplayList);
                        }
                        PrintUtils.printReportWithQRCode(requireContext(), report,
                                snapshotList, isArabic, null);
                    } else if (which == 1) {
                        compareWithCurrent(report);
                    }
                })
                .show();
    }

    private void printCurrentReport() {
        try {
            int scanned = inventoryEngine != null ? inventoryEngine.getUniqueCount() : 0;
            int missing = Math.max(0, expectedCount - scanned);

            InventoryReport currentReport = new InventoryReport(
                    0,
                    inventoryEngine != null ? inventoryEngine.getClientSessionId() : "SESS-CURRENT",
                    new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date()),
                    expectedCount, scanned, missing,
                    isArabic ? "جارية" : "In progress"
            );

            List<ScannedItemDisplay> snapshotList;
            synchronized (scannedItemsDisplayList) {
                snapshotList = new ArrayList<>(scannedItemsDisplayList);
            }

            PrintUtils.printReportWithQRCode(requireContext(), currentReport,
                    snapshotList, isArabic, null);
        } catch (Exception e) {
            Log.e(TAG, "Error printing current report", e);
        }
    }

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

    private void compareWithCurrent(InventoryReport oldReport) {
        if (!isAdded() || isFragmentDestroyed || inventoryEngine == null) return;

        int currentScanned = inventoryEngine.getUniqueCount();
        int currentExpected = expectedCount;

        String comparison = String.format(
                "📊 %s:\n\n%s (%s):\n• %s: %d\n• %s: %d\n• %s: %d\n\n" +
                        "%s:\n• %s: %d\n• %s: %d\n• %s: %d\n\n" +
                        "%s:\n• %s: %+d\n• %s: %+d",
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

    // ==================== المقارنة بين اللقطات ====================

    private void showSnapshotComparison() {
        if (!isAdded() || isFragmentDestroyed) return;

        AlertDialog loading = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "📊 جاري تحميل الجلسات..." : "📊 Loading sessions...")
                .setMessage(isArabic ? "يرجى الانتظار" : "Please wait")
                .setCancelable(false)
                .show();

        if (miraExecutor == null || !miraExecutor.isHealthy()) {
            miraExecutor = MiraExecutorService.getInstance();
        }

        miraExecutor.getNetworkExecutor().execute(() -> {
            try {
                List<InventoryReport> sessions = fetchArchiveReports();

                mainThreadHandler.post(() -> {
                    loading.dismiss();
                    if (!isAdded() || isFragmentDestroyed) return;

                    if (sessions.size() < 2) {
                        Toast.makeText(requireContext(),
                                isArabic ? "⚠️ تحتاج إلى جلستين على الأقل" :
                                        "⚠️ Need at least two sessions",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }

                    showSessionSelectionDialog(sessions);
                });
            } catch (Exception e) {
                Log.e(TAG, "Error fetching sessions", e);
                mainThreadHandler.post(() -> loading.dismiss());
            }
        });
    }

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

    private void fetchAndShowComparison(int sessionId1, int sessionId2) {
        if (!isAdded() || isFragmentDestroyed) return;

        AlertDialog loading = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "📊 جاري المقارنة..." : "📊 Comparing...")
                .setMessage(isArabic ? "يرجى الانتظار" : "Please wait")
                .setCancelable(false)
                .show();

        if (miraExecutor == null || !miraExecutor.isHealthy()) {
            miraExecutor = MiraExecutorService.getInstance();
        }

        miraExecutor.getNetworkExecutor().execute(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient != null ?
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
                mainThreadHandler.post(() -> loading.dismiss());
            }
        });
    }

    private void displayComparisonResult(JSONObject data) {
        if (!isAdded() || isFragmentDestroyed) return;

        try {
            JSONObject comparison = data.optJSONObject("comparison");
            if (comparison == null) comparison = data;

            JSONObject session1 = comparison.optJSONObject("session_1");
            JSONObject session2 = comparison.optJSONObject("session_2");
            JSONObject difference = comparison.optJSONObject("difference");

            if (session1 != null && session2 != null) {
                int found1 = session1.optInt("found", session1.optInt("unique_items_found", 0));
                int expected1 = session1.optInt("expected", session1.optInt("expected_count", 0));
                int found2 = session2.optInt("found", session2.optInt("unique_items_found", 0));
                int expected2 = session2.optInt("expected", session2.optInt("expected_count", 0));

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
                        "📊 %s:\n\n%s (%s):\n• %s: %d\n• %s: %d\n• %s: %d\n\n" +
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

                String assessment;
                if (foundDiff > 0) {
                    assessment = "\n\n✅ " + (isArabic ?
                            "تحسن: تم العثور على " + foundDiff + " قطعة إضافية" :
                            "Improvement: Found " + foundDiff + " additional items");
                } else if (foundDiff < 0) {
                    assessment = "\n\n⚠️ " + (isArabic ?
                            "تراجع: انخفض العدد بـ " + Math.abs(foundDiff) + " قطعة" :
                            "Decline: Decreased by " + Math.abs(foundDiff) + " items");
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
                                            date2, expected2, found2, missing2,
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
        }
    }

    // ==================== المحاكاة ====================

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

                int rssi = -40 - (int) (Math.random() * 40);
                onTagRead(epc, rssi);

                simulationIndex++;
                long delay = SIMULATION_MIN_DELAY_MS +
                        (long) (Math.random() * SIMULATION_MAX_ADDITIONAL_DELAY_MS);

                if (isScanning && !isFragmentDestroyed) {
                    mainThreadHandler.postDelayed(this, delay);
                }
            }
        };

        mainThreadHandler.postDelayed(simulationRunnable, SIMULATION_INITIAL_DELAY_MS);
    }

    private void stopSimulationLoop() {
        if (mainThreadHandler != null && simulationRunnable != null) {
            mainThreadHandler.removeCallbacks(simulationRunnable);
        }
    }

    // ==================== Lifecycle Cleanup ====================

    @Override
    public void onDestroyView() {
        if (backPressedCallback != null) {
            backPressedCallback.remove();
            backPressedCallback = null;
        }

        isFragmentDestroyed = true;

        try {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).exitInventoryLockMode();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error exiting lock mode on destroy", e);
        }

        isScanning = false;
        stopSimulationLoop();
        mainThreadHandler.removeCallbacksAndMessages(null);

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

        scannedItemsDisplayList.clear();
        recentTagReads.clear();
        catalogEpcs.clear();
        catalogItemsMap.clear();
        barcodeToEpcMap.clear();
        epcToBarcodeMap.clear();

        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // ✅ لا نُغلق MiraExecutorService — إنه على مستوى التطبيق
        inventoryDatabase = null;
    }

} // ✅ نهاية الكلاس MiraInventoryProFragment
