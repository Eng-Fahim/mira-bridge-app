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
import com.example.uhf.application.MiraApplication;
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
import java.util.HashSet;
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
 * MiraInventoryProFragment - Production-Ready Version (Final)
 * 
 * تم تطبيق جميع التحسينات:
 * 1. تصحيح تحويل SGTIN-96 مع دعم EAN-13 المباشر
 * 2. إدارة آمنة لـ OnBackPressedCallback
 * 3. بناء خريطة عكسية للباركود في fetchCatalogFromServer
 * 4. إدارة ذاكرة WebView
 * 5. Thread-Safe Collections
 * 6. Singleton Database Pattern
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
    
    // عناصر المقارنة
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
    private final List<InventoryItem> scannedItemsList = 
        Collections.synchronizedList(new ArrayList<>());
    
    private final Set<String> catalogEpcs = 
        Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<String, Long> recentTagReads = new ConcurrentHashMap<>();
    // ✅ خريطة EPC → معلومات القطعة الكاملة
    private final Map<String, InventoryItem> catalogItemsMap = new ConcurrentHashMap<>();
    
    // ✅ خريطة عكسية للباركود → EPC موحد
    private final Map<String, String> barcodeToEpcMap = new ConcurrentHashMap<>();
    
    // ✅ خريطة EPC → باركود للتحويل العكسي
    private final Map<String, String> epcToBarcodeMap = new ConcurrentHashMap<>();
    
    private final List<AuditEntry> auditLog = 
        Collections.synchronizedList(new ArrayList<>());
    
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
        }
    };
    private volatile boolean pendingUiUpdate = false;
    
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isScanning && isAdded() && !isFragmentDestroyed) {
                updateElapsedTime();
                mainThreadHandler.postDelayed(this, 1000);
            }
        }
    };
    private long scanStartTime = 0;
    
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
        backgroundExecutor = Executors.newSingleThreadExecutor();
        
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

    /**
 * تهيئة قاعدة البيانات بأمان باستخدام Singleton Pattern المباشر
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
        boolean canView = permissionManager.canView("inventory") || permissionManager.isManager();
        boolean canEdit = permissionManager.canEdit("inventory") || permissionManager.isManager();
        
        if (btnStartInventory != null) btnStartInventory.setEnabled(canEdit);
        if (btnBlindInventory != null) btnBlindInventory.setEnabled(canEdit);
        if (btnGenerateReport != null) btnGenerateReport.setEnabled(canEdit);
        if (btnArchiveReports != null) btnArchiveReports.setEnabled(canView);
        if (btnViewSnapshots != null) btnViewSnapshots.setEnabled(canView);
        
        if (!canView && !canEdit) {
            if (tvInventoryStatus != null) {
                tvInventoryStatus.setText(isArabic ? "⛔ غير مصرح لك بالوصول" : "⛔ Access denied");
            }
        }
    }

    /**
     * تطبيق اللغة
     */
    private void applyLanguage() {
        if (btnStartInventory != null) 
            btnStartInventory.setText(isArabic ? "▶️ بدء الجرد" : "▶️ Start Inventory");
        if (btnBlindInventory != null) 
            btnBlindInventory.setText(isArabic ? "👁️ جرد أعمى" : "👁️ Blind Inventory");
        if (btnStopInventory != null) 
            btnStopInventory.setText(isArabic ? "⏹️ إيقاف" : "⏹️ Stop");
        if (btnGenerateReport != null) 
            btnGenerateReport.setText(isArabic ? "📋 تقرير" : "📋 Report");
        if (btnArchiveReports != null) 
            btnArchiveReports.setText(isArabic ? "📂 الأرشيف" : "📂 Archive");
        if (btnViewSnapshots != null) 
            btnViewSnapshots.setText(isArabic ? "📸 اللقطات" : "📸 Snapshots");
        
        if (tabInventoryModes != null) {
            tabInventoryModes.removeAllTabs();
            tabInventoryModes.addTab(tabInventoryModes.newTab()
                .setText(isArabic ? "جرد شامل" : "Full Inventory"));
            tabInventoryModes.addTab(tabInventoryModes.newTab()
                .setText(isArabic ? "جرد فئوي" : "Category Inventory"));
        }
    }

    /**
     * تهيئة الأصوات
     */
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
        
        soundPool.setOnLoadCompleteListener((sp, sampleId, status) -> {
            soundLoaded = (status == 0);
            if (!soundLoaded) {
                Log.w(TAG, "Failed to load sounds, status: " + status);
            }
        });
    }

    /**
     * أنواع التغذية الراجعة للصوت
     */
    private enum ScanFeedbackType {
        KNOWN_ITEM,
        UNKNOWN_ITEM,
        DUPLICATE_ITEM,
        MISSING_ITEM,
        COMPLETED,
        ERROR
    }

    /**
     * تشغيل الأصوات
     */
    private void playScanBeep(ScanFeedbackType feedbackType) {
        long now = System.currentTimeMillis();
        if (!soundLoaded || (now - lastBeepTime <= BEEP_THROTTLE_MS)) {
            return;
        }
        
        float rate = BEEP_RATE;
        float volume = BEEP_VOLUME;
        int soundId = beepSoundId;
        
        switch (feedbackType) {
            case KNOWN_ITEM:
                rate = 1.0f;
                volume = 0.8f;
                soundId = successSoundId;
                break;
            case UNKNOWN_ITEM:
                rate = 0.7f;
                volume = 0.6f;
                soundId = errorSoundId;
                break;
            case DUPLICATE_ITEM:
                rate = 1.5f;
                volume = 0.5f;
                soundId = duplicateSoundId;
                break;
            case MISSING_ITEM:
                rate = 0.5f;
                volume = 0.9f;
                soundId = errorSoundId;
                break;
            case COMPLETED:
                final float completedRate = 1.2f;
                final float completedVolume = 1.0f;
                final int completedSoundId = successSoundId;
                
                soundPool.play(completedSoundId, completedVolume, completedVolume, 
                    BEEP_PRIORITY, BEEP_LOOP, completedRate);
                mainThreadHandler.postDelayed(() -> {
                    if (soundPool != null) {
                        soundPool.play(completedSoundId, completedVolume, completedVolume, 
                            BEEP_PRIORITY, BEEP_LOOP, completedRate);
                    }
                }, 200);
                lastBeepTime = now;
                return;
            case ERROR:
                rate = 0.6f;
                volume = 0.8f;
                soundId = errorSoundId;
                break;
        }
        
        final float finalRate = rate;
        final float finalVolume = volume;
        final int finalSoundId = soundId;
        
        try {
            if (soundPool != null) {
                soundPool.play(finalSoundId, finalVolume, finalVolume, 
                    BEEP_PRIORITY, BEEP_LOOP, finalRate);
                lastBeepTime = now;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error playing sound", e);
        }
    }

    /**
     * تهيئة عناصر الواجهة
     */
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
        
        // ✅ عناصر المقارنة
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

    /**
     * إعداد الأزرار
     */
    private void setupButtons() {
        if (btnStartInventory != null) {
            btnStartInventory.setOnClickListener(v -> {
                if (permissionManager.canEdit("inventory") || permissionManager.isManager()) {
                    startScanning();
                } else {
                    showAccessDenied();
                }
            });
        }
        if (btnBlindInventory != null) {
            btnBlindInventory.setOnClickListener(v -> {
                if (permissionManager.canEdit("inventory") || permissionManager.isManager()) {
                    startBlindInventory();
                } else {
                    showAccessDenied();
                }
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
        if (btnArchiveReports != null) {
            btnArchiveReports.setOnClickListener(v -> showArchiveReports());
        }
        if (btnViewSnapshots != null) {
            btnViewSnapshots.setOnClickListener(v -> showSnapshotsDialog());
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
        playScanBeep(ScanFeedbackType.ERROR);
    }

    /**
     * معالجة أزرار RFID
     */
    public boolean myOnKeyDwon(int keyCode, KeyEvent event) {
        if (keyCode == KEYCODE_RFID_TRIGGER_1 || 
            keyCode == KEYCODE_RFID_TRIGGER_2 || 
            keyCode == KEYCODE_RFID_TRIGGER_3) {
            if (event.getRepeatCount() == 0) {
                if (!(permissionManager.canEdit("inventory") || permissionManager.isManager())) {
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

    /**
     * معالجة قراءة الوسم
     */
    @Override
    public void onTagRead(String epc, int rssi) {
        if (!isScanning || isFragmentDestroyed || epc == null || epc.isEmpty()) return;

        final String normalizedEpc = normalizeCodeToEpc(epc);
        final int finalRssi = rssi;

        long currentTime = System.currentTimeMillis();
        Long lastReadTime = recentTagReads.get(normalizedEpc);

        if (lastReadTime != null && (currentTime - lastReadTime) < TAG_READ_DEBOUNCE_MS) {
            return;
        }

        recentTagReads.put(normalizedEpc, currentTime);

        if (recentTagReads.size() > 1000) {
            cleanOldTagReads(currentTime);
        }

        if (backgroundExecutor == null || backgroundExecutor.isShutdown()) {
            backgroundExecutor = Executors.newSingleThreadExecutor();
        }

        backgroundExecutor.execute(() -> {
            try {
                inventoryEngine.onTagScanned(normalizedEpc, finalRssi);
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
     * تحويل الرمز إلى EPC موحد
     * ✅ يدعم EPC مباشر، EAN-13، UPC-12، والباركود المسجل
     */
    private String normalizeCodeToEpc(String code) {
        if (code == null || code.isEmpty()) return "";
        
        String normalized = code.trim().toUpperCase();
        
        // ✅ إذا كان EPC Hexadecimal قياسي
        if (normalized.startsWith("E280") && normalized.length() >= 24) {
            return normalized;
        }
        
        // ✅ إذا كان EAN-13
        if (normalized.length() == EAN13_LENGTH && normalized.matches("\\d{13}")) {
            // ✅ البحث في الخريطة العكسية أولاً
            String mappedEpc = barcodeToEpcMap.get(normalized);
            if (mappedEpc != null) {
                return mappedEpc;
            }
            // إذا لم يوجد، نستخدم EAN-13 مباشرة كمعرف موحد
            return "EAN13:" + normalized;
        }
        
        // ✅ إذا كان UPC-12
        if (normalized.length() == UPC12_LENGTH && normalized.matches("\\d{12}")) {
            String ean13 = "0" + normalized;
            String mappedEpc = barcodeToEpcMap.get(ean13);
            if (mappedEpc != null) {
                return mappedEpc;
            }
            return "EAN13:" + ean13;
        }
        
        // ✅ البحث في الخريطة العكسية للباركود
        String mappedEpc = barcodeToEpcMap.get(normalized);
        if (mappedEpc != null) {
            return mappedEpc;
        }
        
        // ✅ البحث العكسي EPC → باركود
        String mappedBarcode = epcToBarcodeMap.get(normalized);
        if (mappedBarcode != null) {
            return normalized;
        }
        
        return normalized;
    }

    /**
     * حفظ المعاملة محلياً
     */
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

    /**
     * مزامنة المعاملات مع منع التزامن المتوازي
     */
    private void syncOfflineTransactions() {
        if (!isSyncing.compareAndSet(false, true)) {
            return;
        }
        
        if (backgroundExecutor == null || backgroundExecutor.isShutdown()) {
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
                List<Integer> successfulIds = new ArrayList<>();
                
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
                        successfulIds.add(transaction.id);
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
     * إدخال تدقيق
     */
    private static class AuditEntry {
        String timestamp;
        String action;
        String details;
        String userId;
        String deviceId;
        
        AuditEntry(String action, String details, String userId, String deviceId) {
            this.timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", 
                Locale.getDefault()).format(new Date());
            this.action = action;
            this.details = details;
            this.userId = userId;
            this.deviceId = deviceId;
        }
    }

    /**
     * تسجيل التدقيق
     */
    private void logAudit(String action, String details) {
        String userId = getCurrentUserId();
        String deviceId = getDeviceId();
        
        AuditEntry entry = new AuditEntry(action, details, userId, deviceId);
        auditLog.add(entry);
        
        if (auditLog.size() > MAX_AUDIT_ENTRIES) {
            auditLog.remove(0);
        }
        
        Log.d(TAG, String.format("AUDIT: [%s] %s - %s (User: %s, Device: %s)", 
            entry.timestamp, entry.action, entry.details, entry.userId, entry.deviceId));
    }

    /**
     * الحصول على معرف المستخدم
     */
    private String getCurrentUserId() {
        String deviceId = getDeviceId();
        return "USER-" + (deviceId.length() > 8 ? deviceId.substring(0, 8) : deviceId);
    }

    /**
     * إضافة عنصر ممسوح للعرض
     */
    /**
 * إضافة عنصر ممسوح للعرض
 * ✅ مع جلب معلومات القطعة الكاملة من الخريطة
 */
private void addScannedItemToDisplay(String epc, int rssi, InventoryItem item) {
    if (!isAdded() || isFragmentDestroyed || scannedItemsAdapter == null) {
        return;
    }
    
    if (Looper.myLooper() != Looper.getMainLooper()) {
        mainThreadHandler.post(() -> addScannedItemToDisplay(epc, rssi, item));
        return;
    }
    
    synchronized (scannedItemsDisplayList) {
        boolean exists = false;
        int existingIndex = -1;

        // ✅ التحقق من وجود العنصر مسبقاً
        for (int i = 0; i < scannedItemsDisplayList.size(); i++) {
            ScannedItemDisplay display = scannedItemsDisplayList.get(i);
            if (display != null && epc.equals(display.epc)) {
                display.count++;
                display.rssi = rssi;
                display.time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(new Date());
                exists = true;
                existingIndex = i;
                break;
            }
        }

        ScanFeedbackType feedbackType;
        boolean isKnown = catalogEpcs.contains(epc);

        if (!exists) {
            feedbackType = isKnown ? ScanFeedbackType.KNOWN_ITEM : 
                ScanFeedbackType.UNKNOWN_ITEM;

            // ✅✅✅ القيم الافتراضية
            String title = isArabic ? "قطعة غير معروفة" : "Unknown item";
            String karat = "—";
            double weight = 0;
            double price = 0;

            // ✅✅✅ البحث في الخريطة الكاملة أولاً
            InventoryItem catalogItem = catalogItemsMap.get(epc);
            
            if (catalogItem != null) {
                // ✅ وجدت في الكتالوج - استخدام المعلومات الكاملة
                title = catalogItem.getTitle() != null && !catalogItem.getTitle().isEmpty() 
                    ? catalogItem.getTitle() 
                    : (isArabic ? "قطعة ذهبية" : "Gold item");
                karat = catalogItem.getKarat() != null && !catalogItem.getKarat().isEmpty() 
                    ? catalogItem.getKarat() 
                    : "—";
                weight = catalogItem.getWeight();
                price = catalogItem.getPrice();
            } else if (item != null) {
                // ✅ وجدت من المعامل المباشر
                title = item.getTitle() != null && !item.getTitle().isEmpty() 
                    ? item.getTitle() 
                    : (isArabic ? "قطعة ذهبية" : "Gold item");
                karat = item.getKarat() != null && !item.getKarat().isEmpty() 
                    ? item.getKarat() 
                    : "—";
                weight = item.getWeight();
                price = item.getPrice();
            } else if (isKnown) {
                // ✅ موجودة في الـ Set لكن بدون معلومات كاملة
                title = isArabic ? "مادة مسجلة بالكتالوج" : "Catalog registered item";
            }

            String currentTime = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(new Date());

            // ✅ إنشاء العنصر مع جميع البيانات
            ScannedItemDisplay display = new ScannedItemDisplay(
                epc, title, karat, 1, rssi, currentTime
            );
            display.weight = weight;  // ✅ الوزن
            display.price = price;    // ✅ السعر

            // ✅ إضافة للقائمة
            scannedItemsDisplayList.add(0, display);
            scannedItemsAdapter.notifyItemInserted(0);

            if (rvInventoryItems != null) {
                rvInventoryItems.scrollToPosition(0);
            }
            
            // ✅✅✅ تحديث الوزن والقيمة الإجمالية
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

    /**
     * نموذج عرض العنصر
     */
   private static class ScannedItemDisplay {
    String epc;
    String title;
    String karat;
    int count;
    int rssi;
    String time;
    double weight; // ✅ إضافة
    double price;  // ✅ إضافة
    
    ScannedItemDisplay(String epc, String title, String karat, 
                      int count, int rssi, String time) {
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


/**
 * ✅ تحديث الوزن والقيمة الإجمالية
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
     * محول القائمة
     */
    private class ScannedItemsAdapter extends RecyclerView.Adapter<ScannedItemsAdapter.ViewHolder> {
        
        private final List<ScannedItemDisplay> items;
        
        ScannedItemsAdapter(List<ScannedItemDisplay> items) {
            this.items = items;
        }
        
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_scanned_tag, parent, false);
            return new ViewHolder(view);
        }
        
        @Override
public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
    ScannedItemDisplay item = items.get(position);

    if (!"—".equals(item.karat)) {
        holder.tvTagTitle.setText(item.title + " (" + item.karat + ")");
    } else {
        holder.tvTagTitle.setText(item.title);
    }

    holder.tvTagEpc.setText(item.epc);
    
    // ✅✅✅ عرض الوزن والسعر
    String details = (isArabic ? "التكرار: " : "Repeat: ") + 
        item.count + (isArabic ? " مرة" : " times");
    
    if (item.weight > 0) {
        details += (isArabic ? " • الوزن: " : " • Weight: ") + 
            String.format(Locale.US, "%.2f g", item.weight);
    }
    if (item.price > 0) {
        details += (isArabic ? " • السعر: " : " • Price: ") + 
            String.format(Locale.US, "$%,.2f", item.price);
    }
    
    holder.tvTagDetails.setText(details);
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
            return items.size();
        }
        
        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvTagTitle;
            TextView tvTagEpc;
            TextView tvTagDetails;
            TextView tvTagStatus;
            TextView tvTagTime;
            TextView tvTagRssi;
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
                        int position = getAdapterPosition();
                        if (position != RecyclerView.NO_POSITION) {
                            ScannedItemDisplay item = items.get(position);
                            showQuickActionsDialog(item);
                        }
                    });
                }
            }
            
            private void showQuickActionsDialog(ScannedItemDisplay item) {
                String[] actions = isArabic ? new String[]{
                    "✏️ تعديل الحالة",
                    "🔧 تسجيل تلف",
                    "📍 تحديث الموقع",
                    "📝 ملاحظات",
                    "❌ إلغاء"
                } : new String[]{
                    "✏️ Edit status",
                    "🔧 Register damage",
                    "📍 Update location",
                    "📝 Notes",
                    "❌ Cancel"
                };
                
                new MaterialAlertDialogBuilder(itemView.getContext())
                    .setTitle((isArabic ? "إجراءات سريعة - " : "Quick actions - ") + item.epc)
                    .setItems(actions, (dialog, which) -> {
                        switch (which) {
                            case 0: showEditStatusDialog(item); break;
                            case 1: showDamageDialog(item); break;
                            case 2: showLocationDialog(item); break;
                            case 3: showNotesDialog(item); break;
                        }
                    })
                    .show();
            }
            
            private void showEditStatusDialog(ScannedItemDisplay item) {
                String[] statuses = isArabic ? 
                    new String[]{"متوفر", "محجوز", "قيد الصيانة", "غير متوفر"} :
                    new String[]{"Available", "Reserved", "Maintenance", "Unavailable"};
                
                new MaterialAlertDialogBuilder(itemView.getContext())
                    .setTitle((isArabic ? "تعديل حالة - " : "Edit status - ") + item.epc)
                    .setItems(statuses, (dialog, which) -> {
                        String newStatus = statuses[which];
                        updateItemStatus(item.epc, newStatus);
                        Toast.makeText(itemView.getContext(), 
                            (isArabic ? "تم تحديث الحالة إلى: " : "Status updated to: ") + newStatus, 
                            Toast.LENGTH_SHORT).show();
                        playScanBeep(ScanFeedbackType.COMPLETED);
                    })
                    .show();
            }
            
            private void showDamageDialog(ScannedItemDisplay item) {
                String[] damageTypes = isArabic ?
                    new String[]{"خدش بسيط", "كسر", "فقدان جزء", "تلف كامل"} :
                    new String[]{"Minor scratch", "Breakage", "Lost part", "Complete damage"};
                
                new MaterialAlertDialogBuilder(itemView.getContext())
                    .setTitle((isArabic ? "تسجيل تلف - " : "Register damage - ") + item.epc)
                    .setItems(damageTypes, (dialog, which) -> {
                        String damageType = damageTypes[which];
                        registerItemDamage(item.epc, damageType);
                        Toast.makeText(itemView.getContext(), 
                            (isArabic ? "تم تسجيل التلف: " : "Damage registered: ") + damageType, 
                            Toast.LENGTH_SHORT).show();
                        playScanBeep(ScanFeedbackType.ERROR);
                    })
                    .show();
            }
            
            private void showLocationDialog(ScannedItemDisplay item) {
                Toast.makeText(itemView.getContext(), 
                    isArabic ? "تحديث الموقع قيد التطوير" : "Location update in development", 
                    Toast.LENGTH_SHORT).show();
            }
            
            private void showNotesDialog(ScannedItemDisplay item) {
                Toast.makeText(itemView.getContext(), 
                    isArabic ? "إضافة ملاحظات قيد التطوير" : "Notes in development", 
                    Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * تحديث حالة العنصر
     */
    private void updateItemStatus(String epc, String status) {
        logAudit("UPDATE_STATUS", 
            (isArabic ? "تحديث حالة " : "Update status ") + epc + 
            (isArabic ? " إلى " : " to ") + status);
    }

    /**
     * تسجيل تلف
     */
    private void registerItemDamage(String epc, String damageType) {
        logAudit("REGISTER_DAMAGE", 
            (isArabic ? "تسجيل تلف " : "Register damage ") + epc + " - " + damageType);
    }

    /**
     * تحديث آخر قراءة
     */
    private void updateLastScan(String epc, int rssi) {
        if (tvLastScan != null) {
            tvLastScan.setText((isArabic ? "آخر قراءة: " : "Last scan: ") + epc);
        }
        if (tvRssi != null) {
            tvRssi.setText("📶 " + rssi);
        }
        if (progressRssi != null) {
            int rssiPercent = Math.max(0, Math.min(100, (rssi + 100) * 100 / 70));
            progressRssi.setProgress(rssiPercent);
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
     * بدء المسح
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
                playScanBeep(ScanFeedbackType.ERROR);
            });
        }
    }
    
    @Override
    public void onConnectionChanged(boolean isConnected) { 
        Log.i(TAG, "RFID Connection: " + isConnected); 
        if (isAdded() && !isFragmentDestroyed && tvInventoryStatus != null) {
            tvInventoryStatus.setText(isConnected ? 
                (isArabic ? "📡 متصل" : "📡 Connected") : 
                (isArabic ? "📡 غير متصل" : "📡 Disconnected"));
            
            if (isConnected) {
                playScanBeep(ScanFeedbackType.COMPLETED);
            } else {
                playScanBeep(ScanFeedbackType.ERROR);
            }
        }
    }

    /**
     * تحديث الواجهة
     */
    /**
 * تحديث الواجهة وحساب الفروقات بشكل حي
 */
private void updateUI() {
    if (!isAdded() || isFragmentDestroyed || inventoryEngine == null) return;
    
    int scanned = inventoryEngine.getUniqueCount();
    int totalReads = inventoryEngine.getTotalReads();
    int duplicates = inventoryEngine.getDuplicateCount();

    if (tvScannedCount != null) {
        tvScannedCount.setText(String.valueOf(scanned));
    }
    
    if (tvItemCount != null) {
        tvItemCount.setText(String.valueOf(scanned));
    }

    // ✅ تحديث كارت المقارنة الحي (Current vs Expected/Previous)
    if (tvCurrItems != null) {
        tvCurrItems.setText(String.valueOf(scanned));
    }
    if (tvPrevItems != null) {
        tvPrevItems.setText(String.valueOf(expectedCount));
    }
    if (tvDeltaItems != null) {
        int delta = scanned - expectedCount;
        tvDeltaItems.setText(String.format(Locale.US, "%+d", delta));
        
        // تغيير لون الفارق بناءً على النتيجة
        if (delta == 0 && expectedCount > 0) {
            tvDeltaItems.setTextColor(0xFF2E7D32); // أخضر (مطابق)
        } else if (delta < 0) {
            tvDeltaItems.setTextColor(0xFFC62828); // أحمر (عجز)
        } else {
            tvDeltaItems.setTextColor(0xFF1565C0); // أزرق (زيادة)
        }
    }

    if (expectedCount > 0) {
        int progress = (int) (((float) scanned / expectedCount) * 100);
        progress = Math.min(progress, 100);
        
        if (progressScanned != null) {
            progressScanned.setProgress(progress);
        }
        
        if (tvProgressPercent != null) {
            tvProgressPercent.setText(progress + "%");
        }
        
        int variance = Math.max(0, expectedCount - scanned);
        if (tvVariance != null) {
            tvVariance.setText(String.valueOf(variance));
        }
        
        int accuracy = (int) (((float) scanned / expectedCount) * 100);
        if (tvAccuracy != null) {
            tvAccuracy.setText(accuracy + "%");
        }

        if (scanned >= expectedCount && isScanning) {
            stopScanning();
            playScanBeep(ScanFeedbackType.COMPLETED);
            if (isAdded() && !isFragmentDestroyed) {
                Toast.makeText(requireContext(), 
                    isArabic ? "🎉 اكتمل جرد جميع المواد المتوقعة!" : 
                    "🎉 All expected items scanned!", 
                    Toast.LENGTH_LONG).show();
            }
        }
    }
    
    if (tvEmptyState != null) {
        tvEmptyState.setVisibility(scanned > 0 ? View.GONE : View.VISIBLE);
    }
    
    if (tvInventoryStatus != null && isScanning) {
        tvInventoryStatus.setText((isArabic ? "📡 جاري الجرد... (الممسوح: " : 
            "📡 Scanning... (Scanned: ") + scanned + ")");
    }
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
            tvElapsedTime.setText(String.format("%02d:%02d:%02d", hours, minutes, secs));
        }
    }



    /**
     * بدء المسح
     */
    private void startScanning() {
        if (!(permissionManager.canEdit("inventory") || permissionManager.isManager())) {
            showAccessDenied();
            return;
        }
        if (isScanning || isFragmentDestroyed) return;
        
        isScanning = true;
        scanStartTime = SystemClock.elapsedRealtime();
        mainThreadHandler.post(timerRunnable);
        
        logAudit("START_SCAN", isArabic ? "بدء جرد جديد" : "Start new inventory");
        
        loadCatalogAndStart();
    }
    
    /**
     * بدء الجرد الأعمى
     */
    private void startBlindInventory() {
        if (!(permissionManager.canEdit("inventory") || permissionManager.isManager())) {
            showAccessDenied();
            return;
        }
        if (isScanning || isFragmentDestroyed) return;
        
        isScanning = true;
        scanStartTime = SystemClock.elapsedRealtime();
        mainThreadHandler.post(timerRunnable);
        
        logAudit("START_BLIND_SCAN", isArabic ? "بدء جرد أعمى" : "Start blind inventory");
        
        expectedCount = 0;
        catalogEpcs.clear();
        
        if (tvExpectedCount != null) {
            tvExpectedCount.setText("—");
        }
        
        if (tvInventoryStatus != null) {
            tvInventoryStatus.setText(isArabic ? "⚡ جرد أعمى - بدون توقعات" : 
                "⚡ Blind inventory - No expectations");
        }
        
        inventoryEngine.startSession(new ConcurrentHashMap<>());
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
                
                inventoryEngine.startSession(catalog);
                
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
     * جلب الكتالوج من الخادم
     * ✅ مع بناء الخريطة العكسية للباركود
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
                    
                    // ✅ استخراج جميع الحقول
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
                    
                    // ✅ تحديد المعرف الأساسي
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
                    
                    String normalizedEpc = normalizeCodeToEpc(primaryEpc);
                    
                    // ✅✅✅ استخدام الـ Constructor الصحيح مع karat
                    InventoryItem item = new InventoryItem(
                        normalizedEpc,        // epc
                        gtin13,               // gtin
                        serialNumber,         // serial
                        title,                // title
                        karat                 // karat ✅
                    );
                    
                    // ✅✅✅ استخدام setters للوزن والسعر
                    item.setWeight(weight);
                    item.setPrice(price);
                    
                    catalog.put(normalizedEpc, item);
                    
                    // ✅✅✅ تخزين في الخريطة الكاملة
                    catalogItemsMap.put(normalizedEpc, item);
                    
                    // ✅ بناء الخرائط العكسية
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
        
        if (btnStartInventory != null) btnStartInventory.setEnabled(false);
        if (btnBlindInventory != null) btnBlindInventory.setEnabled(false);
        if (btnStopInventory != null) btnStopInventory.setEnabled(true);
        if (btnGenerateReport != null) btnGenerateReport.setEnabled(false);
        if (btnArchiveReports != null) btnArchiveReports.setEnabled(false);
        if (btnViewSnapshots != null) btnViewSnapshots.setEnabled(false);
        
        playScanBeep(ScanFeedbackType.KNOWN_ITEM);
    }

    /**
     * إيقاف المسح
     */
    private void stopScanning() {
        if (!isScanning || isFragmentDestroyed) return;
        
        isScanning = false;
        stopSimulationLoop();
        mainThreadHandler.removeCallbacks(timerRunnable);
        
        logAudit("STOP_SCAN", 
            (isArabic ? "إيقاف الجرد - الممسوح: " : "Stop scan - Scanned: ") + 
            inventoryEngine.getUniqueCount());
        
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
                inventoryEngine.processSessionClose();
                
                mainThreadHandler.post(() -> {
                    if (!isAdded() || isFragmentDestroyed) return;
                    
                    if (tvInventoryStatus != null) {
                        tvInventoryStatus.setText(isArabic ? "📡 تم إيقاف الجرد" : 
                            "📡 Scan stopped");
                    }
                    
                    Toast.makeText(requireContext(), 
                        isArabic ? "🛑 تم إيقاف المسح" : "🛑 Scan stopped", 
                        Toast.LENGTH_SHORT).show();
                    
                    playScanBeep(ScanFeedbackType.ERROR);
                    
                    if (btnStartInventory != null) btnStartInventory.setEnabled(true);
                    if (btnBlindInventory != null) btnBlindInventory.setEnabled(true);
                    if (btnStopInventory != null) btnStopInventory.setEnabled(false);
                    if (btnGenerateReport != null) btnGenerateReport.setEnabled(true);
                    if (btnArchiveReports != null) btnArchiveReports.setEnabled(true);
                    if (btnViewSnapshots != null) btnViewSnapshots.setEnabled(true);
                    
                    updateUI();
                    syncOfflineTransactions();
                });
            } catch (Exception e) {
                Log.e(TAG, "Error processing session close", e);
            }
        });
    }

    /**
     * عرض تأكيد الالتزام
     */
    private void showCommitConfirmationDialog() {
        if (!isAdded() || isFragmentDestroyed || inventoryEngine == null) return;
        if (!(permissionManager.canEdit("inventory") || permissionManager.isManager())) {
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
                        printReportWithQRCode(currentReport);
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
                "⏳ جاري إرسال البيانات بالفعل...", 
                Toast.LENGTH_SHORT).show();
            return;
        }
        
        Toast.makeText(requireContext(), "⏳ جاري إرسال البيانات...", Toast.LENGTH_SHORT).show();
        
        logAudit("COMMIT_INVENTORY", 
            "اعتماد الجرد - الجلسة: " + inventoryEngine.getClientSessionId());
        
        if (backgroundExecutor == null || backgroundExecutor.isShutdown()) {
            backgroundExecutor = Executors.newSingleThreadExecutor();
        }
        
        backgroundExecutor.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                
                String sessionId = inventoryEngine.getClientSessionId();
                payload.put("session_id", sessionId);
                payload.put("client_session_id", sessionId);
                
                int branchId = branchesManager != null ? 
                    branchesManager.getActiveBranchId() : 0;
                payload.put("branch_id", branchId);
                payload.put("location", "Main Showroom");
                
                int scanned = inventoryEngine.getUniqueCount();
                int totalReads = inventoryEngine.getTotalReads();
                
                payload.put("expected_count", expectedCount);
                payload.put("total_scanned", totalReads);
                payload.put("unique_count", scanned);
                payload.put("found_count", scanned);
                payload.put("missing_count", Math.max(0, expectedCount - scanned));
                payload.put("unexpected_count", 0);
                
                JSONArray itemsArray = new JSONArray();
                JSONArray epcsArray = new JSONArray();
                
                List<ScannedItemDisplay> snapshotList = 
                    new ArrayList<>(scannedItemsDisplayList);
                
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
                
                MiraApiClient.ApiResponse response = apiClient.closeInventorySession(payload);
                
                mainThreadHandler.post(() -> {
                    if (!isAdded() || isFragmentDestroyed) {
                        isCommitting.set(false);
                        return;
                    }
                    
                    if (response != null && response.isSuccess) {
                        Toast.makeText(requireContext(), 
                            "✅ تم اعتماد الجرد وحفظ التقرير بنجاح", 
                            Toast.LENGTH_LONG).show();
                        if (btnGenerateReport != null) btnGenerateReport.setEnabled(false);
                        showCommitSuccessDialog(response);
                    } else {
                        String errorMsg = response != null ? response.error : "Unknown error";
                        Log.e(TAG, "Commit failed: " + errorMsg);
                        Toast.makeText(requireContext(), 
                            "❌ خطأ: " + errorMsg, 
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
                            "❌ خطأ في إرسال البيانات: " + e.getMessage(), 
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
            
            String message = String.format(
                "✅ تم اعتماد الجرد بنجاح:\n\n" +
                "• الدقة: %s%%\n" +
                "• مستوى الخطورة: %s\n" +
                "• التوصية: %s",
                accuracy, riskLevel, recommendation
            );
            
            new MaterialAlertDialogBuilder(requireContext())
                .setTitle("🎉 نجاح العملية")
                .setMessage(message)
                .setPositiveButton("🖨️ طباعة التقرير", (dialog, which) -> {
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
                        "معتمد"
                    );
                    printReportWithQRCode(committedReport);
                })
                .setNegativeButton("إغلاق", null)
                .show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing success dialog", e);
        }
    }

    /**
     * عرض الأرشيف
     */
    private void showArchiveReports() {
        if (!isAdded() || isFragmentDestroyed) return;
        
        MaterialAlertDialogBuilder loadingDialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "📂 جاري تحميل الأرشيف..." : "📂 Loading archive...")
                .setMessage(isArabic ? "يرجى الانتظار" : "Please wait")
                .setCancelable(false);
        AlertDialog loading = loadingDialog.show();
        
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
        
        String[] options = {
            isArabic ? "🖨️ طباعة التقرير" : "🖨️ Print Report",
            isArabic ? "📊 مقارنة مع الحالي" : "📊 Compare with Current",
            isArabic ? "❌ إغلاق" : "❌ Close"
        };
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "تفاصيل التقرير" : "Report Details")
                .setMessage(message)
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            printReportWithQRCode(report);
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
        for (InventoryReport r : reports) {
            printReportWithQRCode(r);
        }
    }

    /**
     * طباعة التقرير مع QR - نسخة محسنة
     */
    private void printReportWithQRCode(InventoryReport report) {
        if (!isAdded() || isFragmentDestroyed) return;
        
        try {
            String qrData = generateReportQRData(report);
            Bitmap qrBitmap = generateQRCode(qrData);
            String qrBase64 = convertBitmapToBase64(qrBitmap);
            
            final WebView[] webViewRef = new WebView[1];
            WebView webView = new WebView(requireContext());
            webViewRef[0] = webView;
            
            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html><html><head><meta charset='utf-8'>")
                .append("<style>")
                .append("body { font-family: sans-serif; direction: rtl; text-align: right; margin: 20px; }")
                .append(".header { text-align: center; border-bottom: 2px solid #333; padding-bottom: 10px; margin-bottom: 20px; }")
                .append(".header h1 { margin: 0; color: #1a237e; font-size: 22px; }")
                .append(".header p { margin: 5px 0 0 0; color: #666; font-size: 13px; }")
                .append(".summary-table { width: 100%; border-collapse: collapse; margin-bottom: 20px; }")
                .append(".summary-table td { padding: 8px; border: 1px solid #ddd; font-weight: bold; }")
                .append(".summary-table td.label { background-color: #f5f5f5; width: 25%; }")
                .append(".items-table { width: 100%; border-collapse: collapse; margin-top: 10px; }")
                .append(".items-table th { background-color: #1a237e; color: white; padding: 8px; border: 1px solid #1a237e; font-size: 12px; }")
                .append(".items-table td { padding: 6px; border: 1px solid #ddd; font-size: 11px; text-align: center; }")
                .append(".footer { margin-top: 30px; text-align: left; font-size: 10px; color: #888; border-top: 1px solid #ccc; padding-top: 5px; }")
                .append(".qr-code { text-align: center; margin-top: 20px; }")
                .append(".qr-code img { width: 150px; height: 150px; }")
                .append("</style></head><body>");

            html.append("<div class='header'>")
                .append("<h1>").append(isArabic ? "تقرير عمليات الجرد المتقدم - Mira RFID" : 
                    "Advanced Inventory Report - Mira RFID").append("</h1>")
                .append("<p>").append(isArabic ? "رقم الجلسة: " : "Session ID: ")
                .append(report.sessionId)
                .append(isArabic ? " | التاريخ: " : " | Date: ")
                .append(report.date).append("</p>")
                .append("</div>");

            int accuracy = report.expectedCount > 0 ? 
                (int)(((float)report.scannedCount / report.expectedCount) * 100) : 100;

            html.append("<table class='summary-table'>")
                .append("<tr><td class='label'>").append(isArabic ? "المواد المتوقعة:" : "Expected Items:")
                .append("</td><td>").append(report.expectedCount).append("</td>")
                .append("<td class='label'>").append(isArabic ? "المواد الممسوحة:" : "Scanned Items:")
                .append("</td><td>").append(report.scannedCount).append("</td></tr>")
                .append("<tr><td class='label'>").append(isArabic ? "الفارق / المفقود:" : "Variance / Missing:")
                .append("</td><td>").append(report.missingCount).append("</td>")
                .append("<td class='label'>").append(isArabic ? "نسبة الدقة:" : "Accuracy:")
                .append("</td><td>").append(accuracy).append("%</td></tr>")
                .append("</table>");

            html.append("<h3>").append(isArabic ? "تفاصيل العناصر الممسوحة:" : "Scanned Items Details:")
                .append("</h3>");
            html.append("<table class='items-table'><thead><tr>")
                .append("<th>#</th>")
                .append("<th>").append(isArabic ? "الرمز (EPC)" : "Code (EPC)").append("</th>")
                .append("<th>").append(isArabic ? "وصف القطعة" : "Item Description").append("</th>")
                .append("<th>").append(isArabic ? "العيار" : "Karat").append("</th>")
                .append("<th>").append(isArabic ? "التكرار" : "Count").append("</th>")
                .append("<th>").append(isArabic ? "الإشارة (dBm)" : "Signal (dBm)").append("</th>")
                .append("</tr></thead><tbody>");

            List<ScannedItemDisplay> snapshotList = 
                new ArrayList<>(scannedItemsDisplayList);
            
            if (!snapshotList.isEmpty()) {
                for (int i = 0; i < snapshotList.size(); i++) {
                    ScannedItemDisplay item = snapshotList.get(i);
                    html.append("<tr>")
                        .append("<td>").append(i + 1).append("</td>")
                        .append("<td>").append(item.epc).append("</td>")
                        .append("<td>").append(item.title).append("</td>")
                        .append("<td>").append(item.karat).append("</td>")
                        .append("<td>").append(item.count).append("</td>")
                        .append("<td>").append(item.rssi).append("</td>")
                        .append("</tr>");
                }
            } else {
                html.append("<tr><td colspan='6'>")
                    .append(isArabic ? "لا توجد عناصر ممسوحة مسجلة" : "No scanned items recorded")
                    .append("</td></tr>");
            }

            html.append("</tbody></table>");
            
            if (qrBase64 != null && !qrBase64.isEmpty()) {
                html.append("<div class='qr-code'>")
                    .append("<img src='data:image/png;base64,").append(qrBase64).append("'/>")
                    .append("<p>").append(isArabic ? "امسح للتحقق من التقرير" : "Scan to verify report")
                    .append("</p>")
                    .append("</div>");
            }
            
            html.append("<div class='footer'>")
                .append(isArabic ? "تم إنشاء هذا التقرير تلقائياً بواسطة نظام Mira Pro RFID" : 
                    "This report was automatically generated by Mira Pro RFID System")
                .append("</div>");
            html.append("</body></html>");

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    try {
                        PrintManager printManager = (PrintManager) 
                            requireContext().getSystemService(Context.PRINT_SERVICE);
                        if (printManager != null) {
                            String jobName = "Mira_Inventory_Report_" + report.sessionId;
                            PrintDocumentAdapter printAdapter = 
                                view.createPrintDocumentAdapter(jobName);
                            PrintAttributes attributes = new PrintAttributes.Builder()
                                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                                    .build();
                            printManager.print(jobName, printAdapter, attributes);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error starting print", e);
                    } finally {
                        cleanupWebView(webViewRef[0]);
                    }
                }
            });

            webView.loadDataWithBaseURL(null, html.toString(), "text/html", "UTF-8", null);
            
        } catch (Exception e) {
            Log.e(TAG, "Error printing report with QR code", e);
            if (isAdded() && !isFragmentDestroyed) {
                Toast.makeText(requireContext(), 
                    "❌ " + (isArabic ? "فشلت عملية الطباعة: " : "Print failed: ") + e.getMessage(), 
                    Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * تنظيف WebView
     */
    private void cleanupWebView(WebView webView) {
        if (webView == null) return;
        
        mainThreadHandler.postDelayed(() -> {
            try {
                webView.stopLoading();
                webView.loadUrl("about:blank");
                webView.clearHistory();
                webView.clearCache(true);
                webView.removeAllViews();
                webView.destroy();
            } catch (Exception e) {
                Log.w(TAG, "Error cleaning up WebView", e);
            }
        }, 1000);
    }
    
    /**
     * توليد QR
     */
    private Bitmap generateQRCode(String data) {
        try {
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            BitMatrix bitMatrix = qrCodeWriter.encode(data, BarcodeFormat.QR_CODE, 300, 300);
            
            int width = bitMatrix.getWidth();
            int height = bitMatrix.getHeight();
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    bitmap.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "Error generating QR code", e);
            return null;
        }
    }
    
    /**
     * تحويل الصورة إلى Base64
     */
    private String convertBitmapToBase64(Bitmap bitmap) {
        if (bitmap == null) return "";
        
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream);
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        
        return Base64.encodeToString(byteArray, Base64.DEFAULT);
    }
    
    /**
     * توليد بيانات QR
     */
    private String generateReportQRData(InventoryReport report) {
        try {
            JSONObject qrData = new JSONObject();
            qrData.put("type", "inventory_report");
            qrData.put("session_id", report.sessionId);
            qrData.put("date", report.date);
            qrData.put("expected_count", report.expectedCount);
            qrData.put("scanned_count", report.scannedCount);
            qrData.put("missing_count", report.missingCount);
            qrData.put("status", report.status);
            
            return qrData.toString();
        } catch (Exception e) {
            return "{}";
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
        
        String[] options = {
            isArabic ? "📸 عرض لقطات الجرد السابقة" : "📸 View Previous Snapshots",
            isArabic ? "📊 مقارنة لقطتين" : "📊 Compare Two Snapshots",
            isArabic ? "❌ إغلاق" : "❌ Close"
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
        
        MaterialAlertDialogBuilder loadingDialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "📸 جاري تحميل اللقطات..." : "📸 Loading snapshots...")
                .setMessage(isArabic ? "يرجى الانتظار" : "Please wait")
                .setCancelable(false);
        AlertDialog loading = loadingDialog.show();
        
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
        
        MaterialAlertDialogBuilder loadingDialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "📊 جاري تحميل الجلسات..." : "📊 Loading sessions...")
                .setMessage(isArabic ? "يرجى الانتظار" : "Please wait")
                .setCancelable(false);
        AlertDialog loading = loadingDialog.show();
        
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
        
        MaterialAlertDialogBuilder loadingDialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isArabic ? "📊 جاري المقارنة..." : "📊 Comparing...")
                .setMessage(isArabic ? "يرجى الانتظار" : "Please wait")
                .setCancelable(false);
        AlertDialog loading = loadingDialog.show();
        
        if (backgroundExecutor == null || backgroundExecutor.isShutdown()) {
            backgroundExecutor = Executors.newSingleThreadExecutor();
        }
        
        backgroundExecutor.execute(() -> {
            try {
                MiraApiClient.ApiResponse response = 
                    apiClient.compareInventorySessions(sessionId1, sessionId2);
                
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
                                printReportWithQRCode(compReport);
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

    /**
     * تنظيف عند تدمير العرض
     */
    @Override
    public void onDestroyView() {
        // ✅ إزالة آمنة لـ BackPressedCallback
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
            } catch (Exception e) {
                Log.e(TAG, "Error stopping scanner on destroy", e);
            }
            rfidManager.setListener(null);
        }
        
        if (soundPool != null) {
            try {
                soundPool.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing sound pool", e);
            }
            soundPool = null;
        }
        
        scannedItemsDisplayList.clear();
        scannedItemsList.clear();
        catalogEpcs.clear();
        catalogItemsMap.clear();
        recentTagReads.clear();
        barcodeToEpcMap.clear();
        epcToBarcodeMap.clear();
        auditLog.clear();
        
        super.onDestroyView();
    }

    /**
     * تنظيف عند التدمير الكامل
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        
        shutdownExecutor();
        
        // قاعدة البيانات تُدار على مستوى Application
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
    
    /**
     * نموذج تقرير الجرد
     */
    private static class InventoryReport {
        int id;
        String sessionId;
        String date;
        int expectedCount;
        int scannedCount;
        int missingCount;
        String status;
        
        InventoryReport(int id, String sessionId, String date, 
                       int expectedCount, int scannedCount, int missingCount, 
                       String status) {
            this.id = id;
            this.sessionId = sessionId;
            this.date = date;
            this.expectedCount = expectedCount;
            this.scannedCount = scannedCount;
            this.missingCount = missingCount;
            this.status = status;
        }
    }
                        }
