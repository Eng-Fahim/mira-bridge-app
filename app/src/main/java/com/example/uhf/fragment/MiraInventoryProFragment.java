package com.example.uhf.fragment;

import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.engine.MiraInventoryEngine;
import com.example.uhf.MiraRfid.MiraIRFIDReader;
import com.example.uhf.MiraRfid.RFIDManager;
import com.example.uhf.model.InventoryItem;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MiraInventoryProFragment extends KeyDwonFragment 
        implements MiraIRFIDReader.OnTagReadListener {

    private static final String TAG = "MiraInventoryPro";
    
    // Constants
    private static final long UI_UPDATE_THROTTLE_MS = 200;
    private static final long SIMULATION_INITIAL_DELAY_MS = 500;
    private static final long SIMULATION_MIN_DELAY_MS = 150;
    private static final long SIMULATION_MAX_ADDITIONAL_DELAY_MS = 350;
    private static final long BEEP_THROTTLE_MS = 60;
    private static final int EXECUTOR_SHUTDOWN_TIMEOUT_MS = 500;
    private static final int MAX_SOUND_STREAMS = 5;
    private static final float BEEP_VOLUME = 0.7f;
    private static final float BEEP_RATE = 1.0f;
    private static final int BEEP_PRIORITY = 0;
    private static final int BEEP_LOOP = 0;
    
    // Hardware key codes for RFID trigger
    private static final int KEYCODE_RFID_TRIGGER_1 = 139;
    private static final int KEYCODE_RFID_TRIGGER_2 = 280;
    private static final int KEYCODE_RFID_TRIGGER_3 = 293;
    
    // UI Components
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
    
    private MaterialButton btnStartInventory;
    private MaterialButton btnBlindInventory;
    private MaterialButton btnStopInventory;
    private MaterialButton btnGenerateReport;
    private MaterialButton btnArchiveReports;

    private MiraInventoryEngine inventoryEngine;
    
    // Use single-thread executor for sequential tag processing
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    
    private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());
    private volatile boolean isScanning = false;
    
    private RFIDManager rfidManager;
    
    // Sound & Beeper Controls
    private SoundPool soundPool;
    private int beepSoundId;
    private boolean soundLoaded = false;
    private long lastBeepTime = 0;

    // Simulation Controls
    private final Handler simulationHandler = new Handler(Looper.getMainLooper());
    private Runnable simulationRunnable;
    private int simulationIndex = 0;
    private final String[] simulationTags = {
        "A000001", "A000002", "A000003", "A000004", "A000005",
        "A000006", "A000007", "A000008", "A000009", "A000010"
    };
    
    // UI-update throttling
    private final Runnable uiUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            pendingUiUpdate = false;
            updateUI();
        }
    };
    private volatile boolean pendingUiUpdate = false;
    
    // Timer for elapsed time
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isScanning && isAdded()) {
                updateElapsedTime();
                mainThreadHandler.postDelayed(this, 1000);
            }
        }
    };
    private long scanStartTime = 0;
    
    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    
    // Local variables
    private int expectedCount = 0;
    private List<InventoryItem> scannedItemsList = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_inventory_pro, container, false);
        
        initSoundPool();
        initViews(view);

        String sessionId = "SESS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String deviceId = android.provider.Settings.Secure.getString(
                requireContext().getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID
        );

        inventoryEngine = new MiraInventoryEngine(requireContext(), sessionId, deviceId);
        apiClient = MiraApiClient.getInstance(requireContext());
        branchesManager = MiraBranchesManager.getInstance(requireContext());
        
        rfidManager = RFIDManager.getInstance(requireContext());
        rfidManager.setListener(this);
        
        return view;
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

        // Use existing barcodebeep.ogg from raw folder
        beepSoundId = soundPool.load(requireContext(), R.raw.barcodebeep, 1);
        soundPool.setOnLoadCompleteListener((sp, sampleId, status) -> {
            soundLoaded = (status == 0);
            if (!soundLoaded) {
                Log.w(TAG, "Failed to load beep sound, status: " + status);
            }
        });
    }

    private void playScanBeep() {
        long now = System.currentTimeMillis();
        if (soundLoaded && (now - lastBeepTime > BEEP_THROTTLE_MS)) { 
            try {
                soundPool.play(beepSoundId, BEEP_VOLUME, BEEP_VOLUME, BEEP_PRIORITY, BEEP_LOOP, BEEP_RATE);
                lastBeepTime = now;
            } catch (Exception e) {
                Log.e(TAG, "Error playing beep sound", e);
            }
        }
    }

    private void initViews(View view) {
        // Counts
        tvExpectedCount = view.findViewById(R.id.tvExpectedCount);
        tvScannedCount = view.findViewById(R.id.tvScannedCount);
        tvVariance = view.findViewById(R.id.tvVariance);
        tvAccuracy = view.findViewById(R.id.tvAccuracy);
        
        // Progress
        tvProgressPercent = view.findViewById(R.id.tvProgressPercent);
        progressScanned = view.findViewById(R.id.progressScanned);
        progressRssi = view.findViewById(R.id.progressRssi);
        
        // Status
        tvInventoryStatus = view.findViewById(R.id.tvInventoryStatus);
        tvElapsedTime = view.findViewById(R.id.tvElapsedTime);
        tvRssi = view.findViewById(R.id.tvRssi);
        tvLastScan = view.findViewById(R.id.tvLastScan);
        
        // Info
        tvTotalWeight = view.findViewById(R.id.tvTotalWeight);
        tvTotalValue = view.findViewById(R.id.tvTotalValue);
        tvItemCount = view.findViewById(R.id.tvItemCount);
        tvEmptyState = view.findViewById(R.id.tvEmptyState);
        
        // RecyclerView
        rvInventoryItems = view.findViewById(R.id.rvInventoryItems);
        if (rvInventoryItems != null) {
            rvInventoryItems.setLayoutManager(new LinearLayoutManager(requireContext()));
        }
        
        // Tabs
        tabInventoryModes = view.findViewById(R.id.tabInventoryModes);
        if (tabInventoryModes != null) {
            tabInventoryModes.addTab(tabInventoryModes.newTab().setText("جرد شامل"));
            tabInventoryModes.addTab(tabInventoryModes.newTab().setText("جرد فئوي"));
        }
        
        // Buttons
        btnStartInventory = view.findViewById(R.id.btnStartInventory);
        btnBlindInventory = view.findViewById(R.id.btnBlindInventory);
        btnStopInventory = view.findViewById(R.id.btnStopInventory);
        btnGenerateReport = view.findViewById(R.id.btnGenerateReport);
        btnArchiveReports = view.findViewById(R.id.btnArchiveReports);

        if (btnStartInventory != null) {
            btnStartInventory.setOnClickListener(v -> startScanning());
        }
        if (btnBlindInventory != null) {
            btnBlindInventory.setOnClickListener(v -> startBlindInventory());
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
    }

    // ==================== HARDWARE KEY TRIGGER ====================

    public boolean myOnKeyDwon(int keyCode, KeyEvent event) {
        if (keyCode == KEYCODE_RFID_TRIGGER_1 || keyCode == KEYCODE_RFID_TRIGGER_2 || keyCode == KEYCODE_RFID_TRIGGER_3) {
            if (event.getRepeatCount() == 0) {
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

    // ==================== RFID CALLBACKS ====================
    
    @Override
    public void onTagRead(String epc, int rssi) {
        if (!isScanning || epc == null || epc.isEmpty()) return;

        // Normalize EPC once before processing
        final String normalizedEpc = epc.trim().toUpperCase();
        final int finalRssi = rssi;
        
        backgroundExecutor.execute(() -> {
            try {
                inventoryEngine.onTagScanned(normalizedEpc, finalRssi);
                mainThreadHandler.post(() -> {
                    playScanBeep();
                    updateLastScan(normalizedEpc, finalRssi);
                    scheduleUiUpdate();
                });
            } catch (Exception e) {
                Log.e(TAG, "Error processing tag: " + normalizedEpc, e);
            }
        });
    }
    
    private void updateLastScan(String epc, int rssi) {
        if (tvLastScan != null) {
            tvLastScan.setText("آخر قراءة: " + epc);
        }
        if (tvRssi != null) {
            tvRssi.setText("📶 " + rssi);
        }
        if (progressRssi != null) {
            // Convert RSSI to percentage (typical range -100 to -30)
            int rssiPercent = Math.max(0, Math.min(100, (rssi + 100) * 100 / 70));
            progressRssi.setProgress(rssiPercent);
        }
    }
    
    private void scheduleUiUpdate() {
        if (!pendingUiUpdate) {
            pendingUiUpdate = true;
            mainThreadHandler.postDelayed(uiUpdateRunnable, UI_UPDATE_THROTTLE_MS);
        }
    }

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
        if (isAdded()) {
            mainThreadHandler.post(() -> {
                Toast.makeText(requireContext(), "خطأ في قارئ RFID: " + errorMessage, Toast.LENGTH_SHORT).show();
            });
        }
    }
    
    @Override
    public void onConnectionChanged(boolean isConnected) { 
        Log.i(TAG, "RFID Connection: " + isConnected); 
        if (isAdded() && tvInventoryStatus != null) {
            tvInventoryStatus.setText(isConnected ? "📡 متصل" : "📡 غير متصل");
        }
    }

    // ==================== UI & PROGRESS UPDATE ====================
    
    private void updateUI() {
        if (!isAdded() || inventoryEngine == null) return;
        
        int scanned = inventoryEngine.getUniqueCount();
        int totalReads = inventoryEngine.getTotalReads();
        int duplicates = inventoryEngine.getDuplicateCount();

        if (tvScannedCount != null) {
            tvScannedCount.setText(String.valueOf(scanned));
        }
        
        if (tvItemCount != null) {
            tvItemCount.setText(String.valueOf(scanned));
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
            
            // Calculate variance (missing items)
            int variance = Math.max(0, expectedCount - scanned);
            if (tvVariance != null) {
                tvVariance.setText(String.valueOf(variance));
            }
            
            // Calculate accuracy
            int accuracy = expectedCount > 0 ? (int)(((float)scanned / expectedCount) * 100) : 0;
            if (tvAccuracy != null) {
                tvAccuracy.setText(accuracy + "%");
            }

            // Check if inventory is complete
            if (scanned >= expectedCount && isScanning) {
                stopScanning();
                if (isAdded()) {
                    Toast.makeText(requireContext(), "🎉 اكتمل جرد جميع المواد المتوقعة!", Toast.LENGTH_LONG).show();
                }
            }
        }
        
        // Update empty state
        if (tvEmptyState != null) {
            tvEmptyState.setVisibility(scanned > 0 ? View.GONE : View.VISIBLE);
        }
        
        // Update status
        if (tvInventoryStatus != null && isScanning) {
            tvInventoryStatus.setText("📡 جاري الجرد... (الممسوح: " + scanned + ")");
        }
    }
    
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

    // ==================== START SCANNING ====================
    
    private void startScanning() {
        if (isScanning) return;
        isScanning = true;
        scanStartTime = SystemClock.elapsedRealtime();
        
        // Start timer
        mainThreadHandler.post(timerRunnable);
        
        loadCatalogAndStart();
    }
    
    private void startBlindInventory() {
        if (isScanning) return;
        isScanning = true;
        scanStartTime = SystemClock.elapsedRealtime();
        
        // Start timer
        mainThreadHandler.post(timerRunnable);
        
        // For blind inventory, no expected count
        expectedCount = 0;
        
        if (tvExpectedCount != null) {
            tvExpectedCount.setText("—");
        }
        
        if (tvInventoryStatus != null) {
            tvInventoryStatus.setText("⚡ جرد أعمى - بدون توقعات");
        }
        
        // Start with empty catalog
        inventoryEngine.startSession(new ConcurrentHashMap<>());
        startReadingSource();
    }
    
    private void loadCatalogAndStart() {
        if (!isAdded()) return;
        
        Toast.makeText(requireContext(), "🔄 جاري تحميل البيانات...", Toast.LENGTH_SHORT).show();
        
        backgroundExecutor.execute(() -> {
            Map<String, InventoryItem> catalog = fetchCatalogFromServer();
            
            mainThreadHandler.post(() -> {
                if (!isAdded()) return;
                
                inventoryEngine.startSession(catalog);
                
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
                Log.w(TAG, "API client or branches manager is null");
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
                        String epc = obj.optString("gtin13", 
                            obj.optString("serial_number", 
                            obj.optString("epc", ""))).trim();
                        
                        if (epc.isEmpty()) {
                            continue;
                        }
                        
                        String title = obj.optString("title", "Gold Item");
                        String karat = obj.optString("karat", "21K");
                        
                        InventoryItem item = new InventoryItem(
                            epc.toUpperCase(),
                            title,
                            karat,
                            "✓"
                        );
                        catalog.put(epc.toUpperCase(), item);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching catalog from server", e);
        }
        
        Log.d(TAG, "Catalog loaded: " + catalog.size() + " items");
        return catalog;
    }
    
    private void startReadingSource() {
        boolean hardwareStarted = false;
        
        if (rfidManager != null) {
            try {
                if (rfidManager.getActiveDevice() != null) {
                    MiraIRFIDReader.ReaderType deviceType = rfidManager.getActiveDevice().getType();
                    
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
            if (isAdded()) {
                Toast.makeText(requireContext(), "🎯 بدء وضع المحاكاة", Toast.LENGTH_SHORT).show();
            }
        }
        
        if (btnStartInventory != null) btnStartInventory.setEnabled(false);
        if (btnBlindInventory != null) btnBlindInventory.setEnabled(false);
        if (btnStopInventory != null) btnStopInventory.setEnabled(true);
        if (btnGenerateReport != null) btnGenerateReport.setEnabled(false);
    }

    // ==================== STOP SCANNING ====================
    
    private void stopScanning() {
        if (!isScanning) return;
        isScanning = false;
        stopSimulationLoop();
        
        // Stop timer
        mainThreadHandler.removeCallbacks(timerRunnable);
        
        if (rfidManager != null) {
            try {
                rfidManager.stopScan();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping RFID scanner", e);
            }
        }
        
        backgroundExecutor.execute(() -> {
            try {
                inventoryEngine.processSessionClose();
                mainThreadHandler.post(() -> {
                    if (!isAdded()) return;
                    
                    if (tvInventoryStatus != null) {
                        tvInventoryStatus.setText("📡 تم إيقاف الجرد");
                    }
                    
                    Toast.makeText(requireContext(), "🛑 تم إيقاف المسح", Toast.LENGTH_SHORT).show();
                    
                    if (btnStartInventory != null) btnStartInventory.setEnabled(true);
                    if (btnBlindInventory != null) btnBlindInventory.setEnabled(true);
                    if (btnStopInventory != null) btnStopInventory.setEnabled(false);
                    if (btnGenerateReport != null) btnGenerateReport.setEnabled(true);
                    
                    // Final UI update
                    updateUI();
                });
            } catch (Exception e) {
                Log.e(TAG, "Error processing session close", e);
            }
        });
    }

    // ==================== COMMIT & UPLOAD ====================

    private void showCommitConfirmationDialog() {
        if (!isAdded() || inventoryEngine == null) return;
        
        int scanned = inventoryEngine.getUniqueCount();
        int missing = Math.max(0, expectedCount - scanned);

        String message = String.format("📊 ملخص عمليات الجرد:\n\n• المتوقع: %d\n• الممسوح: %d\n• المفقود: %d\n\nهل تريد اعتماد العملية وإرسالها لقاعدة البيانات؟",
                expectedCount, scanned, missing);

        new AlertDialog.Builder(requireContext())
                .setTitle("حفظ واكتمال الجرد")
                .setMessage(message)
                .setPositiveButton("إرسال واعتماد", (dialog, which) -> commitInventoryToServer())
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void commitInventoryToServer() {
        if (!isAdded()) return;
        
        Toast.makeText(requireContext(), "⏳ جاري إرسال البيانات...", Toast.LENGTH_SHORT).show();
        
        backgroundExecutor.execute(() -> {
            try {
                JSONObject payload = inventoryEngine.buildEvidencePayload();
                
                if (apiClient != null) {
                    apiClient.submitInventoryAuditReport(payload);
                }
                
                mainThreadHandler.post(() -> {
                    if (!isAdded()) return;
                    
                    Toast.makeText(requireContext(), "✅ تم اعتماد الجرد بنجاح", Toast.LENGTH_LONG).show();
                    if (btnGenerateReport != null) btnGenerateReport.setEnabled(false);
                });
            } catch (Exception e) {
                Log.e(TAG, "Error committing inventory", e);
                mainThreadHandler.post(() -> {
                    if (isAdded()) {
                        Toast.makeText(requireContext(), "❌ خطأ في إرسال البيانات", Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void showArchiveReports() {
        if (isAdded()) {
            Toast.makeText(requireContext(), "📂 عرض التقارير المؤرشفة", Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== SIMULATION ====================
    
    private void startSimulationLoop() {
        simulationIndex = 0;
        
        simulationRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isScanning) return;
                
                String epc = simulationTags[simulationIndex % simulationTags.length];
                int rssi = -40 - (int)(Math.random() * 40);
                
                onTagRead(epc, rssi);
                
                simulationIndex++;
                long delay = SIMULATION_MIN_DELAY_MS + 
                    (long)(Math.random() * SIMULATION_MAX_ADDITIONAL_DELAY_MS);
                
                if (isScanning) {
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

    // ==================== DESTROY ====================
    
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        
        isScanning = false;
        stopSimulationLoop();
        
        mainThreadHandler.removeCallbacksAndMessages(null);
        
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
        
        if (simulationHandler != null) {
            simulationHandler.removeCallbacksAndMessages(null);
        }
        
        // Shutdown background executor gracefully
        if (backgroundExecutor != null && !backgroundExecutor.isShutdown()) {
            backgroundExecutor.shutdown();
            try {
                if (!backgroundExecutor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    backgroundExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Log.w(TAG, "Executor termination interrupted", e);
                backgroundExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    @Override
    public void onPause() {
        super.onPause();
        if (isScanning) {
            stopScanning();
        }
    }
}
