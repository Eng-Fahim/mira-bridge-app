package com.example.uhf.fragment;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.text.BidiFormatter;
import android.text.TextDirectionHeuristics;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
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
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.PermissionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
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
 * MiraInventoryProFragment - Production-Ready Version
 */
public class MiraInventoryProFragment extends KeyDwonFragment 
        implements MiraIRFIDReader.OnTagReadListener {

    private static final String TAG = "MiraInventoryPro";
    
    // ==================== Constants ====================
    private static final long UI_UPDATE_THROTTLE_MS = 200;
    private static final long SIMULATION_INITIAL_DELAY_MS = 500;
    private static final long SIMULATION_MIN_DELAY_MS = 150;
    private static final long SIMULATION_MAX_ADDITIONAL_DELAY_MS = 350;
    private static final long BEEP_THROTTLE_MS = 60;
    private static final int EXECUTOR_SHUTDOWN_TIMEOUT_MS = 1000;
    private static final int MAX_SOUND_STREAMS = 5;
    private static final float BEEP_VOLUME = 0.7f;
    private static final float BEEP_RATE = 1.0f;
    private static final int BEEP_PRIORITY = 0;
    private static final int BEEP_LOOP = 0;
    private static final long TAG_READ_DEBOUNCE_MS = 500;
    private static final int MAX_AUDIT_ENTRIES = 1000;
    private static final int EAN13_LENGTH = 13;
    private static final int UPC12_LENGTH = 12;
    
    private static final int KEYCODE_RFID_TRIGGER_1 = 139;
    private static final int KEYCODE_RFID_TRIGGER_2 = 280;
    private static final int KEYCODE_RFID_TRIGGER_3 = 293;
    
    // ==================== UI Components ====================
    private TextView tvExpectedCount;
    private TextView tvScannedCount;
    private TextView tvProgressPercent;
    private ProgressBar progressScanned;
    private TextView tvInventoryStatus;
    private TextView tvElapsedTime;
    private TextView tvRssi;
    private ProgressBar progressRssi;
    private TextView tvLastScan;
    private TextView tvVariance;
    private TextView tvAccuracy;
    private TextView tvTotalWeight;
    private TextView tvTotalValue;
    private TextView tvItemCount;
    private TextView tvEmptyState;
    private RecyclerView rvInventoryItems;
    private TabLayout tabInventoryModes;
    
    private TextView tvPrevItems;
    private TextView tvCurrItems;
    private TextView tvDeltaItems;
    private MaterialCardView cardComparison;
    
    private MaterialButton btnStartInventory;
    private MaterialButton btnBlindInventory;
    private MaterialButton btnStopInventory;
    private MaterialButton btnGenerateReport;
    private MaterialButton btnArchiveReports;
    private MaterialButton btnViewSnapshots;

    // ==================== Thread-Safe Collections ====================
    private final List<ScannedItemDisplay> scannedItemsDisplayList = 
        Collections.synchronizedList(new ArrayList<>());
    
    private final Set<String> catalogEpcs = 
        Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<String, Long> recentTagReads = new ConcurrentHashMap<>();
    private final Map<String, InventoryItem> catalogItemsMap = new ConcurrentHashMap<>();
    private final Map<String, String> barcodeToEpcMap = new ConcurrentHashMap<>();
    private final Map<String, String> epcToBarcodeMap = new ConcurrentHashMap<>();
    private final List<AuditEntry> auditLog = Collections.synchronizedList(new ArrayList<>());
    
    // ==================== Thread Management ====================
    private ExecutorService backgroundExecutor;
    private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());
    private volatile boolean isScanning = false;
    private volatile boolean isFragmentDestroyed = false;
    
    private final AtomicBoolean isSyncing = new AtomicBoolean(false);
    private final AtomicBoolean isCommitting = new AtomicBoolean(false);
    
    // ==================== RFID Components ====================
    private RFIDManager rfidManager;
    private SoundPool soundPool;
    private int beepSoundId;
    private int successSoundId;
    private int errorSoundId;
    private int duplicateSoundId;
    private boolean soundLoaded = false;
    private long lastBeepTime = 0;
    
    private boolean isArabic = true;
    
    // ==================== Simulation ====================
    private final Handler simulationHandler = new Handler(Looper.getMainLooper());
    private Runnable simulationRunnable;
    private int simulationIndex = 0;
    private final String[] simulationTags = {
        "A000001", "A000002", "A000003", "A000004", "A000005",
        "A000006", "A000007", "A000008", "A000009", "A000010"
    };
    
    // ==================== Database ====================
    private InventoryDatabase inventoryDatabase;
    private InventoryTransactionDao transactionDao;
    
    // ==================== UI Update ====================
    private final Runnable uiUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            pendingUiUpdate = false;
            updateUI();
            updateTotalWeightAndValue();
        }
    };
    private volatile boolean pendingUiUpdate = false;
    private long scanStartTime = 0;

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isScanning && !isFragmentDestroyed) {
                updateElapsedTime();
                mainThreadHandler.postDelayed(this, 1000);
            }
        }
    };
    
    // ==================== Business Components ====================
    private MiraInventoryEngine inventoryEngine;
    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private PermissionManager permissionManager;
    private int expectedCount = 0;
    
    // ==================== BackPress Handling ====================
    private OnBackPressedCallback backPressedCallback;
    
    // ==================== Adapter ====================
    private ScannedItemsAdapter scannedItemsAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, 
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_inventory_pro, container, false);
        
        isFragmentDestroyed = false;

        if (backgroundExecutor == null || backgroundExecutor.isShutdown()) {
            backgroundExecutor = Executors.newSingleThreadExecutor();
        }
        
        isArabic = Locale.getDefault().getLanguage().equals("ar");
        view.setLayoutDirection(isArabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        
        initSoundPool();
        initViews(view);
        initDatabase();
        
        String sessionId = "SESS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String deviceId = getDeviceId();
        
        inventoryEngine = new MiraInventoryEngine(requireContext(), sessionId, deviceId);
        apiClient = MiraApiClient.getInstance(requireContext());
        branchesManager = MiraBranchesManager.getInstance(requireContext());
        permissionManager = PermissionManager.getInstance(requireContext());
        
        rfidManager = RFIDManager.getInstance(requireContext());
        rfidManager.setListener(this);
        
        setupBackPressHandler();
        applyPermissions();
        applyLanguage();
        
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
        
        String title = isArabic ? "⚠️ تحذير مهم" : "⚠️ Important Warning";
        String message = isArabic ? 
            "الجرد ما زال قيد التشغيل!\n\nإذا قمت بتغيير الواجهة، سيتوقف الجرد وستفقد البيانات غير المحفوظة.\n\nهل تريد إيقاف الجرد والمتابعة؟" :
            "Inventory is still running!\n\nIf you change the screen, the inventory will stop and unsaved data will be lost.\n\nDo you want to stop and continue?";
        String positiveBtn = isArabic ? "إيقاف والمتابعة" : "Stop and Continue";
        String negativeBtn = isArabic ? "البقاء في الجرد" : "Stay in Inventory";
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(positiveBtn, (dialog, which) -> {
                    if (onConfirm != null) onConfirm.run();
                })
                .setNegativeButton(negativeBtn, (dialog, which) -> dialog.dismiss())
                .setCancelable(false)
                .show();
    }

    private void applyPermissions() {
        boolean canView = permissionManager.canView("inventory") || permissionManager.isManager();
        boolean canEdit = permissionManager.canEdit("inventory") || permissionManager.isManager();
        
        if (btnStartInventory != null) btnStartInventory.setEnabled(canEdit);
        if (btnBlindInventory != null) btnBlindInventory.setEnabled(canEdit);
        if (btnGenerateReport != null) btnGenerateReport.setEnabled(canEdit);
        if (btnArchiveReports != null) btnArchiveReports.setEnabled(canView);
        if (btnViewSnapshots != null) btnViewSnapshots.setEnabled(canView);
        
        if (!canView && !canEdit && tvInventoryStatus != null) {
            tvInventoryStatus.setText(isArabic ? "⛔ غير مصرح لك بالوصول" : "⛔ Access denied");
        }
    }

    private void applyLanguage() {
        if (btnStartInventory != null) btnStartInventory.setText(isArabic ? "▶️ بدء الجرد" : "▶️ Start Inventory");
        if (btnBlindInventory != null) btnBlindInventory.setText(isArabic ? "👁️ جرد أعمى" : "👁️ Blind Inventory");
        if (btnStopInventory != null) btnStopInventory.setText(isArabic ? "⏹️ إيقاف" : "⏹️ Stop");
        if (btnGenerateReport != null) btnGenerateReport.setText(isArabic ? "📋 تقرير" : "📋 Report");
        if (btnArchiveReports != null) btnArchiveReports.setText(isArabic ? "📂 الأرشيف" : "📂 Archive");
        if (btnViewSnapshots != null) btnViewSnapshots.setText(isArabic ? "📸 اللقطات" : "📸 Snapshots");
        
        if (tabInventoryModes != null) {
            tabInventoryModes.removeAllTabs();
            tabInventoryModes.addTab(tabInventoryModes.newTab().setText(isArabic ? "جرد شامل" : "Full Inventory"));
            tabInventoryModes.addTab(tabInventoryModes.newTab().setText(isArabic ? "جرد فئوي" : "Category Inventory"));
        }
    }

    private void initSoundPool() {
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
                
        soundPool = new SoundPool.Builder()
                .setMaxStreams(MAX_SOUND_STREAMS)
                .setAudioAttributes(attributes)
                .build();

        beepSoundId = soundPool.load(requireContext(), R.raw.barcodebeep, 1);
        successSoundId = soundPool.load(requireContext(), R.raw.barcodebeep, 1);
        errorSoundId = soundPool.load(requireContext(), R.raw.barcodebeep, 1);
        duplicateSoundId = soundPool.load(requireContext(), R.raw.barcodebeep, 1);
        
        soundPool.setOnLoadCompleteListener((sp, sampleId, status) -> soundLoaded = (status == 0));
    }

    private enum ScanFeedbackType { KNOWN_ITEM, UNKNOWN_ITEM, DUPLICATE_ITEM, MISSING_ITEM, COMPLETED, ERROR }

    private void playScanBeep(ScanFeedbackType feedbackType) {
        long now = System.currentTimeMillis();
        if (!soundLoaded || (now - lastBeepTime <= BEEP_THROTTLE_MS)) return;
        
        float rate = BEEP_RATE;
        float volume = BEEP_VOLUME;
        int soundId = beepSoundId;
        
        switch (feedbackType) {
            case KNOWN_ITEM: rate = 1.0f; volume = 0.8f; soundId = successSoundId; break;
            case UNKNOWN_ITEM: rate = 0.7f; volume = 0.6f; soundId = errorSoundId; break;
            case DUPLICATE_ITEM: rate = 1.5f; volume = 0.5f; soundId = duplicateSoundId; break;
            case MISSING_ITEM: rate = 0.5f; volume = 0.9f; soundId = errorSoundId; break;
            case COMPLETED:
                soundPool.play(successSoundId, 1.0f, 1.0f, BEEP_PRIORITY, BEEP_LOOP, 1.2f);
                mainThreadHandler.postDelayed(() -> {
                    if (soundPool != null) soundPool.play(successSoundId, 1.0f, 1.0f, BEEP_PRIORITY, BEEP_LOOP, 1.2f);
                }, 200);
                lastBeepTime = now;
                return;
            case ERROR: rate = 0.6f; volume = 0.8f; soundId = errorSoundId; break;
        }
        
        try {
            if (soundPool != null) {
                soundPool.play(soundId, volume, volume, BEEP_PRIORITY, BEEP_LOOP, rate);
                lastBeepTime = now;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error playing sound", e);
        }
    }

    private void initViews(View view) {
        tvExpectedCount = view.findViewById(R.id.tvExpectedCount);
        tvScannedCount = view.findViewById(R.id.tvScannedCount);
        tvVariance = view.findViewById(R.id.tvVariance);
        tvAccuracy = view.findViewById(R.id.tvAccuracy);
        
        tvProgressPercent = view.findViewById(R.id.tvProgressPercent);
        progressScanned = view.findViewById(R.id.progressScanned);
        progressRssi = view.findViewById(R.id.progressRssi);
        
        tvInventoryStatus = view.findViewById(R.id.tvInventoryStatus);
        tvElapsedTime = view.findViewById(R.id.tvElapsedTime);
        tvRssi = view.findViewById(R.id.tvRssi);
        tvLastScan = view.findViewById(R.id.tvLastScan);
        
        tvTotalWeight = view.findViewById(R.id.tvTotalWeight);
        tvTotalValue = view.findViewById(R.id.tvTotalValue);
        tvItemCount = view.findViewById(R.id.tvItemCount);
        tvEmptyState = view.findViewById(R.id.tvEmptyState);
        
        tvPrevItems = view.findViewById(R.id.tvPrevItems);
        tvCurrItems = view.findViewById(R.id.tvCurrItems);
        tvDeltaItems = view.findViewById(R.id.tvDeltaItems);
        cardComparison = view.findViewById(R.id.cardComparison);
        
        rvInventoryItems = view.findViewById(R.id.rvInventoryItems);
        if (rvInventoryItems != null) {
            rvInventoryItems.setLayoutManager(new LinearLayoutManager(requireContext()));
            scannedItemsAdapter = new ScannedItemsAdapter(scannedItemsDisplayList);
            rvInventoryItems.setAdapter(scannedItemsAdapter);
            rvInventoryItems.setHasFixedSize(false);
            rvInventoryItems.setItemAnimator(null);
        }
        
        tabInventoryModes = view.findViewById(R.id.tabInventoryModes);
        
        btnStartInventory = view.findViewById(R.id.btnStartInventory);
        btnBlindInventory = view.findViewById(R.id.btnBlindInventory);
        btnStopInventory = view.findViewById(R.id.btnStopInventory);
        btnGenerateReport = view.findViewById(R.id.btnGenerateReport);
        btnArchiveReports = view.findViewById(R.id.btnArchiveReports);
        btnViewSnapshots = view.findViewById(R.id.btnViewSnapshots);

        setupButtons();
    }

    private void setupButtons() {
        if (btnStartInventory != null) {
            btnStartInventory.setOnClickListener(v -> {
                if (permissionManager.canEdit("inventory") || permissionManager.isManager()) startScanning();
                else showAccessDenied();
            });
        }
        if (btnBlindInventory != null) {
            btnBlindInventory.setOnClickListener(v -> {
                if (permissionManager.canEdit("inventory") || permissionManager.isManager()) startBlindInventory();
                else showAccessDenied();
            });
        }
        if (btnStopInventory != null) {
            btnStopInventory.setOnClickListener(v -> stopScanning());
            btnStopInventory.setEnabled(false);
        }
        if (btnGenerateReport != null) {
            btnGenerateReport.setOnClickListener(v -> showCommitConfirmationDialog());
            btnGenerateReport.setEnabled(false);
        }
        if (btnArchiveReports != null) btnArchiveReports.setOnClickListener(v -> showArchiveReports());
        if (btnViewSnapshots != null) btnViewSnapshots.setOnClickListener(v -> showSnapshotsDialog());
    }

    private void showAccessDenied() {
        if (!isAdded() || isFragmentDestroyed) return;
        Toast.makeText(requireContext(), isArabic ? "⛔ غير مصرح" : "⛔ Access denied", Toast.LENGTH_SHORT).show();
        playScanBeep(ScanFeedbackType.ERROR);
    }

    public boolean myOnKeyDwon(int keyCode, KeyEvent event) {
        if (keyCode == KEYCODE_RFID_TRIGGER_1 || keyCode == KEYCODE_RFID_TRIGGER_2 || keyCode == KEYCODE_RFID_TRIGGER_3) {
            if (event.getRepeatCount() == 0) {
                if (!(permissionManager.canEdit("inventory") || permissionManager.isManager())) {
                    showAccessDenied();
                    return true;
                }
                if (!isScanning) startScanning();
                else stopScanning();
                return true;
            }
        }
        return false;
    }

    @Override
    public void onTagRead(String epc, int rssi) {
        if (!isScanning || isFragmentDestroyed || epc == null || epc.isEmpty()) return;

        final String normalizedEpc = normalizeCodeToEpc(epc);
        long currentTime = System.currentTimeMillis();
        Long lastReadTime = recentTagReads.get(normalizedEpc);

        if (lastReadTime != null && (currentTime - lastReadTime) < TAG_READ_DEBOUNCE_MS) return;

        recentTagReads.put(normalizedEpc, currentTime);
        if (recentTagReads.size() > 1000) cleanOldTagReads(currentTime);

        if (backgroundExecutor == null || backgroundExecutor.isShutdown()) {
            backgroundExecutor = Executors.newSingleThreadExecutor();
        }

        backgroundExecutor.execute(() -> {
            try {
                inventoryEngine.onTagScanned(normalizedEpc, rssi);
                saveTransactionLocally(normalizedEpc, rssi);
                
                mainThreadHandler.post(() -> {
                    if (!isFragmentDestroyed) {
                        addScannedItemToDisplay(normalizedEpc, rssi, null);
                        updateLastScan(normalizedEpc, rssi);
                        scheduleUiUpdate();
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
            if (currentTime - entry.getValue() > TAG_READ_DEBOUNCE_MS * 2) iterator.remove();
        }
    }

    private String normalizeCodeToEpc(String code) {
        if (code == null || code.isEmpty()) return "";
        String normalized = code.trim().toUpperCase();
        
        if (normalized.startsWith("E280") && normalized.length() >= 24) return normalized;
        
        if (normalized.length() == EAN13_LENGTH && normalized.matches("\\d{13}")) {
            String mappedEpc = barcodeToEpcMap.get(normalized);
            return mappedEpc != null ? mappedEpc : "EAN13:" + normalized;
        }
        
        if (normalized.length() == UPC12_LENGTH && normalized.matches("\\d{12}")) {
            String ean13 = "0" + normalized;
            String mappedEpc = barcodeToEpcMap.get(ean13);
            return mappedEpc != null ? mappedEpc : "EAN13:" + ean13;
        }
        
        String mappedEpc = barcodeToEpcMap.get(normalized);
        if (mappedEpc != null) return mappedEpc;
        
        return normalized;
    }

    private void saveTransactionLocally(String epc, int rssi) {
        try {
            InventoryTransactionEntity transaction = new InventoryTransactionEntity();
            transaction.sessionId = inventoryEngine.getClientSessionId();
            transaction.epcCode = epc;
            transaction.rssi = rssi;
            transaction.timestamp = System.currentTimeMillis();
            transaction.deviceId = getDeviceId();
            transaction.userId = getCurrentUserId();
            transaction.synced = false;
            transaction.branchId = branchesManager.getActiveBranchId();
            
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

    private void syncOfflineTransactions() {
        if (!isSyncing.compareAndSet(false, true)) return;
        if (backgroundExecutor == null || backgroundExecutor.isShutdown()) {
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
                List<Long> successfulIds = new ArrayList<>(); // تم تغيير النوع إلى Long لتوافق الـ DAO
                
                for (InventoryTransactionEntity transaction : unsynced) {
                    JSONObject transactionJson = new JSONObject();
                    transactionJson.put("session_id", transaction.sessionId);
                    transactionJson.put("epc", transaction.epcCode);
                    transactionJson.put("rssi", transaction.rssi);
                    transactionJson.put("timestamp", transaction.scanTimestamp); // تم التعديل إلى scanTimestamp المطابق للـ Entity
                    transactionJson.put("device_id", transaction.deviceId);
                    transactionJson.put("user_id", transaction.userId);
                    transactionJson.put("branch_id", transaction.branchId);
                    
                    transactionsArray.put(transactionJson);
                    successfulIds.add((long) transaction.id); // تحويل الـ int إلى Long لتجنب خطأ توافق الأنواع
                }
                
                if (transactionsArray.length() > 0) {
                    MiraApiClient.ApiResponse response = apiClient.syncOfflineTransactions(transactionsArray);
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

    private static class AuditEntry {
        String timestamp;
        String action;
        String details;
        String userId;
        String deviceId;
        
        AuditEntry(String action, String details, String userId, String deviceId) {
            this.timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
            this.action = action;
            this.details = details;
            this.userId = userId;
            this.deviceId = deviceId;
        }
    }

    private void logAudit(String action, String details) {
        AuditEntry entry = new AuditEntry(action, details, getCurrentUserId(), getDeviceId());
        auditLog.add(entry);
        if (auditLog.size() > MAX_AUDIT_ENTRIES) auditLog.remove(0);
    }

    private String getCurrentUserId() {
        String deviceId = getDeviceId();
        return "USER-" + (deviceId.length() > 8 ? deviceId.substring(0, 8) : deviceId);
    }

    private void addScannedItemToDisplay(String epc, int rssi, InventoryItem item) {
        if (!isAdded() || isFragmentDestroyed || scannedItemsAdapter == null) return;
        
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainThreadHandler.post(() -> addScannedItemToDisplay(epc, rssi, item));
            return;
        }
        
        synchronized (scannedItemsDisplayList) {
            boolean exists = false;
            int existingIndex = -1;

            for (int i = 0; i < scannedItemsDisplayList.size(); i++) {
                ScannedItemDisplay display = scannedItemsDisplayList.get(i);
                if (display != null && epc.equals(display.epc)) {
                    display.count++;
                    display.rssi = rssi;
                    display.time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                    exists = true;
                    existingIndex = i;
                    break;
                }
            }

            ScanFeedbackType feedbackType;
            boolean isKnown = catalogEpcs.contains(epc);

            if (!exists) {
                feedbackType = isKnown ? ScanFeedbackType.KNOWN_ITEM : ScanFeedbackType.UNKNOWN_ITEM;

                String title = isArabic ? "قطعة غير معروفة" : "Unknown item";
                String karat = "—";
                double weight = 0;
                double price = 0;

                InventoryItem catalogItem = catalogItemsMap.get(epc);
                if (catalogItem != null) {
                    title = catalogItem.getTitle() != null && !catalogItem.getTitle().isEmpty() ? catalogItem.getTitle() : (isArabic ? "قطعة ذهبية" : "Gold item");
                    karat = catalogItem.getKarat() != null && !catalogItem.getKarat().isEmpty() ? catalogItem.getKarat() : "—";
                    weight = catalogItem.getWeight();
                    price = catalogItem.getPrice();
                } else if (item != null) {
                    title = item.getTitle() != null && !item.getTitle().isEmpty() ? item.getTitle() : (isArabic ? "قطعة ذهبية" : "Gold item");
                    karat = item.getKarat() != null && !item.getKarat().isEmpty() ? item.getKarat() : "—";
                    weight = item.getWeight();
                    price = item.getPrice();
                }

                String currentTime = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());

                ScannedItemDisplay display = new ScannedItemDisplay(epc, title, karat, 1, rssi, currentTime);
                display.weight = weight;
                display.price = price;

                scannedItemsDisplayList.add(0, display);
                scannedItemsAdapter.notifyItemInserted(0);

                if (rvInventoryItems != null) rvInventoryItems.scrollToPosition(0);
                updateTotalWeightAndValue();
            } else {
                feedbackType = ScanFeedbackType.DUPLICATE_ITEM;
                scannedItemsAdapter.notifyItemChanged(existingIndex);
            }

            playScanBeep(feedbackType);
            if (tvEmptyState != null && !scannedItemsDisplayList.isEmpty()) {
                tvEmptyState.setVisibility(View.GONE);
            }
        }
    }

    private static class ScannedItemDisplay {
        String epc;
        String title;
        String karat;
        int count;
        int rssi;
        String time;
        double weight;
        double price;
        
        ScannedItemDisplay(String epc, String title, String karat, int count, int rssi, String time) {
            this.epc = epc;
            this.title = title;
            this.karat = karat;
            this.count = count;
            this.rssi = rssi;
            this.time = time;
            this.weight = 0;
            this.price = 0;
        }
    }

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
            if (tvTotalWeight != null) tvTotalWeight.setText(String.format(Locale.US, "%.2f g", finalWeight));
            if (tvTotalValue != null) tvTotalValue.setText(String.format(Locale.US, "$%,.2f", finalValue));
        });
    }

    private class ScannedItemsAdapter extends RecyclerView.Adapter<ScannedItemsAdapter.ViewHolder> {
        private final List<ScannedItemDisplay> items;
        private final BidiFormatter bidiFormatter;

        ScannedItemsAdapter(List<ScannedItemDisplay> items) {
            this.items = items;
            this.bidiFormatter = BidiFormatter.getInstance(Locale.getDefault());
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_scanned_tag, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            if (position < 0 || position >= items.size()) return;
            
            ScannedItemDisplay item = items.get(position);
            if (item == null) return;

            String fullTitle = !"—".equals(item.karat) ? item.title + " (" + item.karat + ")" : item.title;
            holder.tvTagTitle.setText(fullTitle);
            holder.tvTagEpc.setText(item.epc);

            StringBuilder details = new StringBuilder();
            details.append(isArabic ? "التكرار: " : "Repeat: ").append(item.count);

            if (item.weight > 0) {
                String formattedWeight = String.format(Locale.US, "%.2f g", item.weight);
                details.append(isArabic ? " • الوزن: " : " • Weight: ")
                       .append(bidiFormatter.unicodeWrap(formattedWeight, TextDirectionHeuristics.LTR));
            }

            if (item.price > 0) {
                String formattedPrice = String.format(Locale.US, "$%,.2f", item.price);
                details.append(isArabic ? " • السعر: " : " • Price: ")
                       .append(bidiFormatter.unicodeWrap(formattedPrice, TextDirectionHeuristics.LTR));
            }

            holder.tvTagDetails.setText(details.toString());
            holder.tvTagRssi.setText(item.rssi + " dBm");
            holder.tvTagTime.setText(item.time != null ? item.time : "--:--:--");

            if (expectedCount > 0 && !catalogEpcs.isEmpty()) {
                if (catalogEpcs.contains(item.epc)) {
                    holder.tvTagStatus.setText(isArabic ? "مطابق" : "Matched");
                    holder.tvTagStatus.setTextColor(0xFF2E7D32);
                } else {
                    holder.tvTagStatus.setText(isArabic ? "غير متوقع" : "Unexpected");
                    holder.tvTagStatus.setTextColor(0xFFC62828);
                }
            } else {
                holder.tvTagStatus.setText(isArabic ? "ممسوح" : "Scanned");
                holder.tvTagStatus.setTextColor(0xFF1565C0);
            }
        }

        @Override
        public int getItemCount() {
            return items != null ? items.size() : 0;
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvTagTitle, tvTagEpc, tvTagDetails, tvTagStatus, tvTagTime, tvTagRssi;
            MaterialButton btnQuickAction;

            ViewHolder(View itemView) {
                super(itemView);
                tvTagTitle = itemView.findViewById(R.id.tvTagTitle);
                tvTagEpc = itemView.findViewById(R.id.tvTagEpc);
                tvTagDetails = itemView.findViewById(R.id.tvTagDetails);
                tvTagStatus = itemView.findViewById(R.id.tvTagStatus);
                tvTagTime = itemView.findViewById(R.id.tvTagTime);
                tvTagRssi = itemView.findViewById(R.id.tvTagRssi);
                btnQuickAction = itemView.findViewById(R.id.btnQuickAction);

                if (btnQuickAction != null) {
                    btnQuickAction.setOnClickListener(v -> {
                        int pos = getAdapterPosition();
                        if (pos != RecyclerView.NO_POSITION) showQuickActionsDialog(items.get(pos));
                    });
                }
            }

            private void showQuickActionsDialog(ScannedItemDisplay item) {
                String[] actions = isArabic ? 
                    new String[]{"✏️ تعديل الحالة", "🔧 تسجيل تلف", "📍 تحديث الموقع", "📝 ملاحظات", "❌ إلغاء"} : 
                    new String[]{"✏️ Edit status", "🔧 Register damage", "📍 Update location", "📝 Notes", "❌ Cancel"};

                new MaterialAlertDialogBuilder(itemView.getContext())
                    .setTitle((isArabic ? "إجراءات سريعة - " : "Quick actions - ") + item.epc)
                    .setItems(actions, (dialog, which) -> {
                        switch (which) {
                            case 0: showEditStatusDialog(item); break;
                            case 1: showDamageDialog(item); break;
                            case 2: Toast.makeText(itemView.getContext(), isArabic ? "قيد التطوير" : "In dev", Toast.LENGTH_SHORT).show(); break;
                            case 3: Toast.makeText(itemView.getContext(), isArabic ? "قيد التطوير" : "In dev", Toast.LENGTH_SHORT).show(); break;
                        }
                    }).show();
            }

            private void showEditStatusDialog(ScannedItemDisplay item) {
                String[] statuses = isArabic ? new String[]{"متوفر", "محجوز", "قيد الصيانة", "غير متوفر"} : new String[]{"Available", "Reserved", "Maintenance", "Unavailable"};
                new MaterialAlertDialogBuilder(itemView.getContext())
                    .setTitle((isArabic ? "تعديل حالة - " : "Edit status - ") + item.epc)
                    .setItems(statuses, (dialog, which) -> {
                        updateItemStatus(item.epc, statuses[which]);
                        playScanBeep(ScanFeedbackType.COMPLETED);
                    }).show();
            }

            private void showDamageDialog(ScannedItemDisplay item) {
                String[] damageTypes = isArabic ? new String[]{"خدش بسيط", "كسر", "فقدان جزء", "تلف كامل"} : new String[]{"Minor scratch", "Breakage", "Lost part", "Complete damage"};
                new MaterialAlertDialogBuilder(itemView.getContext())
                    .setTitle((isArabic ? "تسجيل تلف - " : "Register damage - ") + item.epc)
                    .setItems(damageTypes, (dialog, which) -> {
                        registerItemDamage(item.epc, damageTypes[which]);
                        playScanBeep(ScanFeedbackType.ERROR);
                    }).show();
            }
        }
    }

    private void updateItemStatus(String epc, String status) {
        logAudit("UPDATE_STATUS", "Update status of " + epc + " to " + status);
    }

    private void registerItemDamage(String epc, String damageType) {
        logAudit("REGISTER_DAMAGE", "Damage registered for " + epc + " - " + damageType);
    }

    private void updateLastScan(String epc, int rssi) {
        if (tvLastScan != null) tvLastScan.setText((isArabic ? "آخر قراءة: " : "Last scan: ") + epc);
        if (tvRssi != null) tvRssi.setText("📶 " + rssi);
        if (progressRssi != null) {
            int rssiPercent = Math.max(0, Math.min(100, (rssi + 100) * 100 / 70));
            progressRssi.setProgress(rssiPercent);
        }
    }

    private void scheduleUiUpdate() {
        if (!pendingUiUpdate && !isFragmentDestroyed) {
            pendingUiUpdate = true;
            mainThreadHandler.postDelayed(uiUpdateRunnable, UI_UPDATE_THROTTLE_MS);
        }
    }

    @Override public void onScanStarted() { Log.d(TAG, "Scan Started"); }
    @Override public void onScanStopped() { Log.d(TAG, "Scan Stopped"); }
    
    @Override
    public void onError(String errorMessage) {
        if (isAdded() && !isFragmentDestroyed) {
            mainThreadHandler.post(() -> {
                Toast.makeText(requireContext(), (isArabic ? "خطأ: " : "Error: ") + errorMessage, Toast.LENGTH_SHORT).show();
                playScanBeep(ScanFeedbackType.ERROR);
            });
        }
    }
    
    @Override
    public void onConnectionChanged(boolean isConnected) {
        if (isAdded() && !isFragmentDestroyed && tvInventoryStatus != null) {
            tvInventoryStatus.setText(isConnected ? (isArabic ? "📡 متصل" : "📡 Connected") : (isArabic ? "📡 غير متصل" : "📡 Disconnected"));
            playScanBeep(isConnected ? ScanFeedbackType.COMPLETED : ScanFeedbackType.ERROR);
        }
    }

    private void updateUI() {
        if (!isAdded() || isFragmentDestroyed || inventoryEngine == null) return;
        
        int scanned = inventoryEngine.getUniqueCount();
        if (tvScannedCount != null) tvScannedCount.setText(String.valueOf(scanned));
        if (tvItemCount != null) tvItemCount.setText(String.valueOf(scanned));
        if (tvCurrItems != null) tvCurrItems.setText(String.valueOf(scanned));
        if (tvPrevItems != null) tvPrevItems.setText(String.valueOf(expectedCount));
        
        if (tvDeltaItems != null) {
            int delta = scanned - expectedCount;
            tvDeltaItems.setText(String.format(Locale.US, "%+d", delta));
            if (delta == 0 && expectedCount > 0) tvDeltaItems.setTextColor(0xFF2E7D32);
            else if (delta < 0) tvDeltaItems.setTextColor(0xFFC62828);
            else tvDeltaItems.setTextColor(0xFF1565C0);
        }

        if (expectedCount > 0) {
            int progress = Math.min((int) (((float) scanned / expectedCount) * 100), 100);
            if (progressScanned != null) progressScanned.setProgress(progress);
            if (tvProgressPercent != null) tvProgressPercent.setText(progress + "%");
            if (tvVariance != null) tvVariance.setText(String.valueOf(Math.max(0, expectedCount - scanned)));
            if (tvAccuracy != null) tvAccuracy.setText(progress + "%");

            if (scanned >= expectedCount && isScanning) {
                stopScanning();
                playScanBeep(ScanFeedbackType.COMPLETED);
                Toast.makeText(requireContext(), isArabic ? "🎉 اكتمل الجرد!" : "🎉 Inventory complete!", Toast.LENGTH_LONG).show();
            }
        }
        
        if (tvEmptyState != null) tvEmptyState.setVisibility(scanned > 0 ? View.GONE : View.VISIBLE);
    }

    private void updateElapsedTime() {
        if (tvElapsedTime != null && scanStartTime > 0) {
            long seconds = (SystemClock.elapsedRealtime() - scanStartTime) / 1000;
            tvElapsedTime.setText(String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60));
        }
    }

    private void startScanning() {
        if (!(permissionManager.canEdit("inventory") || permissionManager.isManager())) {
            showAccessDenied();
            return;
        }
        if (isScanning || isFragmentDestroyed) return;
        
        isScanning = true;
        scanStartTime = SystemClock.elapsedRealtime();
        mainThreadHandler.post(timerRunnable);
        logAudit("START_SCAN", "Start new inventory");
        loadCatalogAndStart();
    }
    
    private void startBlindInventory() {
        if (!(permissionManager.canEdit("inventory") || permissionManager.isManager())) {
            showAccessDenied();
            return;
        }
        if (isScanning || isFragmentDestroyed) return;
        
        isScanning = true;
        scanStartTime = SystemClock.elapsedRealtime();
        mainThreadHandler.post(timerRunnable);
        logAudit("START_BLIND_SCAN", "Start blind inventory");
        
        expectedCount = 0;
        catalogEpcs.clear();
        if (tvExpectedCount != null) tvExpectedCount.setText("—");
        
        inventoryEngine.startSession(new ConcurrentHashMap<>());
        startReadingSource();
    }
    
    private void loadCatalogAndStart() {
        if (!isAdded() || isFragmentDestroyed) return;
        Toast.makeText(requireContext(), isArabic ? "🔄 جاري التحميل..." : "🔄 Loading...", Toast.LENGTH_SHORT).show();
        
        if (backgroundExecutor == null || backgroundExecutor.isShutdown()) {
            backgroundExecutor = Executors.newSingleThreadExecutor();
        }
        
        backgroundExecutor.execute(() -> {
            Map<String, InventoryItem> catalog = fetchCatalogFromServer();
            mainThreadHandler.post(() -> {
                if (!isAdded() || isFragmentDestroyed) return;
                
                inventoryEngine.startSession(catalog);
                catalogEpcs.clear();
                if (catalog != null) catalogEpcs.addAll(catalog.keySet());
                
                expectedCount = catalog != null ? catalog.size() : 0;
                if (tvExpectedCount != null) tvExpectedCount.setText(String.valueOf(expectedCount));
                
                startReadingSource();
            });
        });
    }

    private Map<String, InventoryItem> fetchCatalogFromServer() {
        Map<String, InventoryItem> catalog = new ConcurrentHashMap<>();
        try {
            if (apiClient == null || branchesManager == null) return catalog;
            
            MiraApiClient.ApiResponse response = apiClient.getBranchItemsList(branchesManager.getActiveBranchId());
            if (response != null && response.isSuccess && response.data != null) {
                JSONArray items = response.data.optJSONArray("items");
                if (items == null && response.data.optJSONObject("data") != null) {
                    items = response.data.optJSONObject("data").optJSONArray("items");
                }
                
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject obj = items.getJSONObject(i);
                        String gtin13 = obj.optString("gtin13", "").trim();
                        String serialNumber = obj.optString("serial_number", "").trim();
                        String epcCode = obj.optString("epc", obj.optString("rfid_epc", "")).trim();
                        String title = obj.optString("item_title", obj.optString("title", "Gold Item"));
                        String karat = obj.optString("gold_karat", obj.optString("karat", "21K"));
                        double weight = obj.optDouble("gross_weight_grams", obj.optDouble("weight", 0));
                        double price = obj.optDouble("retail_price_usd", obj.optDouble("price", 0));
                        
                        String primaryEpc = !epcCode.isEmpty() ? epcCode.toUpperCase() : 
                                           (!serialNumber.isEmpty() ? serialNumber.toUpperCase() : 
                                           (!gtin13.isEmpty() ? "EAN13:" + gtin13 : ""));
                        if (primaryEpc.isEmpty()) continue;
                        
                        String normalizedEpc = normalizeCodeToEpc(primaryEpc);
                        InventoryItem item = new InventoryItem(normalizedEpc, gtin13, serialNumber, title, karat);
                        item.setWeight(weight);
                        item.setPrice(price);
                        
                        catalog.put(normalizedEpc, item);
                        catalogItemsMap.put(normalizedEpc, item);
                        
                        if (!gtin13.isEmpty()) barcodeToEpcMap.put(gtin13, normalizedEpc);
                        if (!serialNumber.isEmpty()) barcodeToEpcMap.put(serialNumber, normalizedEpc);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching catalog", e);
        }
        return catalog;
    }
    
    private void startReadingSource() {
        boolean hardwareStarted = false;
        if (rfidManager != null && rfidManager.getActiveDevice() != null) {
            if (rfidManager.getActiveDevice().getType() != MiraIRFIDReader.ReaderType.SIMULATOR) {
                hardwareStarted = rfidManager.startScan();
            }
        }
        
        if (!hardwareStarted) startSimulationLoop();
        
        if (btnStartInventory != null) btnStartInventory.setEnabled(false);
        if (btnBlindInventory != null) btnBlindInventory.setEnabled(false);
        if (btnStopInventory != null) btnStopInventory.setEnabled(true);
        playScanBeep(ScanFeedbackType.KNOWN_ITEM);
    }

    private void stopScanning() {
        if (!isScanning || isFragmentDestroyed) return;
        
        isScanning = false;
        stopSimulationLoop();
        mainThreadHandler.removeCallbacks(timerRunnable);
        
        if (rfidManager != null) {
            try { rfidManager.stopScan(); } catch (Exception e) { Log.e(TAG, "Error stopping RFID", e); }
        }
        
        if (backgroundExecutor == null || backgroundExecutor.isShutdown()) {
            backgroundExecutor = Executors.newSingleThreadExecutor();
        }
        
        backgroundExecutor.execute(() -> {
            inventoryEngine.processSessionClose();
            mainThreadHandler.post(() -> {
                if (!isAdded() || isFragmentDestroyed) return;
                
                if (btnStartInventory != null) btnStartInventory.setEnabled(true);
                if (btnBlindInventory != null) btnBlindInventory.setEnabled(true);
                if (btnStopInventory != null) btnStopInventory.setEnabled(false);
                if (btnGenerateReport != null) btnGenerateReport.setEnabled(true);
                
                updateUI();
                syncOfflineTransactions();
            });
        });
    }

    private void showCommitConfirmationDialog() {
        if (!isAdded() || isFragmentDestroyed || inventoryEngine == null) return;
        int scanned = inventoryEngine.getUniqueCount();
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "حفظ واكتمال الجرد" : "Save Inventory")
                .setMessage(String.format(Locale.getDefault(), "المتوقع: %d | الممسوح: %d", expectedCount, scanned))
                .setPositiveButton(isArabic ? "اعتماد" : "Commit", (d, w) -> commitInventoryToServer())
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
    }

    private void commitInventoryToServer() {
        if (!isCommitting.compareAndSet(false, true)) return;
        
        if (backgroundExecutor == null || backgroundExecutor.isShutdown()) {
            backgroundExecutor = Executors.newSingleThreadExecutor();
        }
        
        backgroundExecutor.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("session_id", inventoryEngine.getClientSessionId());
                payload.put("unique_count", inventoryEngine.getUniqueCount());
                
                MiraApiClient.ApiResponse response = apiClient.closeInventorySession(payload);
                mainThreadHandler.post(() -> {
                    isCommitting.set(false);
                    if (response != null && response.isSuccess) {
                        Toast.makeText(requireContext(), "✅ تم الاعتماد بنجاح", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                isCommitting.set(false);
            }
        });
    }

    private void showArchiveReports() {
        Toast.makeText(requireContext(), isArabic ? "جاري جلب الأرشيف..." : "Loading archive...", Toast.LENGTH_SHORT).show();
    }

    private void printReportWithQRCode(InventoryReport report) {
        try {
            Bitmap qrBitmap = generateQRCode(report.sessionId);
            String qrBase64 = convertBitmapToBase64(qrBitmap);
            
            WebView webView = new WebView(requireContext());
            String html = "<html><body><h1>Report: " + report.sessionId + "</h1>" +
                    "<img src='data:image/png;base64," + qrBase64 + "'/></body></html>";
            
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    PrintManager printManager = (PrintManager) requireContext().getSystemService(Context.PRINT_SERVICE);
                    if (printManager != null) {
                        printManager.print("Mira_Report", view.createPrintDocumentAdapter("Mira_Report"), new PrintAttributes.Builder().build());
                    }
                }
            });
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
        } catch (Exception e) {
            Log.e(TAG, "Printing failed", e);
        }
    }

    private Bitmap generateQRCode(String data) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, 300, 300);
            Bitmap bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.RGB_565);
            for (int x = 0; x < 300; x++) {
                for (int y = 0; y < 300; y++) {
                    bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    private String convertBitmapToBase64(Bitmap bitmap) {
        if (bitmap == null) return "";
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        return Base64.encodeToString(stream.toByteArray(), Base64.DEFAULT);
    }

    private void showSnapshotsDialog() {
        Toast.makeText(requireContext(), "Snapshots", Toast.LENGTH_SHORT).show();
    }

    private void startSimulationLoop() {
        simulationIndex = 0;
        simulationRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isScanning || isFragmentDestroyed) return;
                String epc = simulationTags[simulationIndex % simulationTags.length];
                onTagRead(epc, -50);
                simulationIndex++;
                simulationHandler.postDelayed(this, SIMULATION_MIN_DELAY_MS);
            }
        };
        simulationHandler.postDelayed(simulationRunnable, SIMULATION_INITIAL_DELAY_MS);
    }

    private void stopSimulationLoop() {
        if (simulationHandler != null && simulationRunnable != null) {
            simulationHandler.removeCallbacks(simulationRunnable);
        }
    }

    private static class InventoryReport {
        int id;
        String sessionId, date, status;
        int expectedCount, scannedCount, missingCount;
        InventoryReport(int id, String sessionId, String date, int expectedCount, int scannedCount, int missingCount, String status) {
            this.id = id; this.sessionId = sessionId; this.date = date;
            this.expectedCount = expectedCount; this.scannedCount = scannedCount;
            this.missingCount = missingCount; this.status = status;
        }
    }

    @Override
    public void onDestroyView() {
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
                Log.e(TAG, "Error cleaning RFID Manager", e);
            }
        }
        
        if (soundPool != null) {
            try {
                soundPool.release();
                soundPool = null;
            } catch (Exception e) {
                Log.e(TAG, "Error releasing sound pool", e);
            }
        }
        
        if (backgroundExecutor != null && !backgroundExecutor.isShutdown()) {
            backgroundExecutor.shutdown();
            try {
                if (!backgroundExecutor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    backgroundExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                backgroundExecutor.shutdownNow();
            }
        }
        
        scannedItemsDisplayList.clear();
        recentTagReads.clear();
        catalogEpcs.clear();
        catalogItemsMap.clear();
        barcodeToEpcMap.clear();
        epcToBarcodeMap.clear();
        
        super.onDestroyView();
    }
}
