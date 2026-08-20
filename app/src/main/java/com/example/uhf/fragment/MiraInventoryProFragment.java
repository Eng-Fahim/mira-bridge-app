package com.example.uhf.fragment;

import android.content.DialogInterface;
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private MaterialButton btnViewSnapshots;

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
    
    // Catalog EPCs for simulation
    private final Set<String> catalogEpcs = new HashSet<>();
    
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
        btnViewSnapshots = view.findViewById(R.id.btnViewSnapshots);

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
        if (btnViewSnapshots != null) {
            btnViewSnapshots.setOnClickListener(v -> showSnapshotsDialog());
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
            
            int variance = Math.max(0, expectedCount - scanned);
            if (tvVariance != null) {
                tvVariance.setText(String.valueOf(variance));
            }
            
            int accuracy = expectedCount > 0 ? (int)(((float)scanned / expectedCount) * 100) : 0;
            if (tvAccuracy != null) {
                tvAccuracy.setText(accuracy + "%");
            }

            if (scanned >= expectedCount && isScanning) {
                stopScanning();
                if (isAdded()) {
                    Toast.makeText(requireContext(), "🎉 اكتمل جرد جميع المواد المتوقعة!", Toast.LENGTH_LONG).show();
                }
            }
        }
        
        if (tvEmptyState != null) {
            tvEmptyState.setVisibility(scanned > 0 ? View.GONE : View.VISIBLE);
        }
        
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
        mainThreadHandler.post(timerRunnable);
        loadCatalogAndStart();
    }
    
    private void startBlindInventory() {
        if (isScanning) return;
        isScanning = true;
        scanStartTime = SystemClock.elapsedRealtime();
        mainThreadHandler.post(timerRunnable);
        
        expectedCount = 0;
        catalogEpcs.clear();
        
        if (tvExpectedCount != null) {
            tvExpectedCount.setText("—");
        }
        
        if (tvInventoryStatus != null) {
            tvInventoryStatus.setText("⚡ جرد أعمى - بدون توقعات");
        }
        
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
                        
                        if (epc.isEmpty()) continue;
                        
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
        if (btnArchiveReports != null) btnArchiveReports.setEnabled(false);
        if (btnViewSnapshots != null) btnViewSnapshots.setEnabled(false);
    }

    // ==================== STOP SCANNING ====================
    
    private void stopScanning() {
        if (!isScanning) return;
        isScanning = false;
        stopSimulationLoop();
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
                    if (btnArchiveReports != null) btnArchiveReports.setEnabled(true);
                    if (btnViewSnapshots != null) btnViewSnapshots.setEnabled(true);
                    
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

        new MaterialAlertDialogBuilder(requireContext())
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

    // ==================== ARCHIVE & REPORTS ====================
    
    private void showArchiveReports() {
        if (!isAdded()) return;
        
        // Show loading dialog
        MaterialAlertDialogBuilder loadingDialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle("📂 جاري تحميل الأرشيف...")
                .setMessage("يرجى الانتظار")
                .setCancelable(false);
        AlertDialog loading = loadingDialog.show();
        
        backgroundExecutor.execute(() -> {
            try {
                List<InventoryReport> reports = fetchArchiveReports();
                
                mainThreadHandler.post(() -> {
                    loading.dismiss();
                    if (isAdded()) {
                        displayArchiveDialog(reports);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error fetching archive", e);
                mainThreadHandler.post(() -> {
                    loading.dismiss();
                    if (isAdded()) {
                        Toast.makeText(requireContext(), "❌ فشل تحميل الأرشيف", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }
    
    private List<InventoryReport> fetchArchiveReports() {
        List<InventoryReport> reports = new ArrayList<>();
        
        try {
            if (apiClient != null) {
                MiraApiClient.ApiResponse response = apiClient.getInventoryReports(
                    branchesManager.getActiveBranchId(), 1, 20
                );
                
                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray sessions = response.data.optJSONArray("sessions");
                    if (sessions == null) {
                        sessions = response.data.optJSONArray("data");
                    }
                    
                    if (sessions != null) {
                        for (int i = 0; i < sessions.length(); i++) {
                            JSONObject obj = sessions.getJSONObject(i);
                            InventoryReport report = new InventoryReport(
                                obj.optInt("id", i + 1),
                                obj.optString("session_id", "SESS-" + (i + 1)),
                                obj.optString("date", ""),
                                obj.optInt("expected_count", 0),
                                obj.optInt("scanned_count", 0),
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
        
        // If no reports, add sample data
        if (reports.isEmpty()) {
            reports.add(new InventoryReport(1, "SESS-001", "2024-01-15 10:30", 50, 48, 2, "completed"));
            reports.add(new InventoryReport(2, "SESS-002", "2024-01-14 14:45", 50, 50, 0, "completed"));
            reports.add(new InventoryReport(3, "SESS-003", "2024-01-13 09:15", 50, 45, 5, "completed"));
        }
        
        return reports;
    }
    
    private void displayArchiveDialog(List<InventoryReport> reports) {
        if (!isAdded() || reports.isEmpty()) {
            Toast.makeText(requireContext(), "لا توجد تقارير سابقة", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Build list of report titles
        String[] reportTitles = new String[reports.size()];
        for (int i = 0; i < reports.size(); i++) {
            InventoryReport r = reports.get(i);
            reportTitles[i] = String.format("📋 %s - %s (%d/%d)", 
                r.sessionId, r.date, r.scannedCount, r.expectedCount);
        }
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("📂 أرشيف تقارير الجرد")
                .setItems(reportTitles, (dialog, which) -> {
                    showReportDetails(reports.get(which));
                })
                .setPositiveButton("طباعة الكل", (dialog, which) -> {
                    printAllReports(reports);
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }
    
    private void showReportDetails(InventoryReport report) {
        if (!isAdded()) return;
        
        String message = String.format(
            "📋 تفاصيل التقرير:\n\n" +
            "• رقم الجلسة: %s\n" +
            "• التاريخ: %s\n" +
            "• المتوقع: %d\n" +
            "• الممسوح: %d\n" +
            "• المفقود: %d\n" +
            "• الحالة: %s\n\n" +
            "خيارات:",
            report.sessionId, report.date, report.expectedCount,
            report.scannedCount, report.missingCount, report.status
        );
        
        String[] options = {"🖨️ طباعة", "📊 مقارنة مع الحالي", "❌ إغلاق"};
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("تفاصيل التقرير")
                .setMessage(message)
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            printReport(report);
                            break;
                        case 1:
                            compareWithCurrent(report);
                            break;
                    }
                })
                .show();
    }
    
    private void printReport(InventoryReport report) {
        if (isAdded()) {
            Toast.makeText(requireContext(), "🖨️ جاري طباعة التقرير: " + report.sessionId, Toast.LENGTH_SHORT).show();
            // TODO: Implement actual printing logic
        }
    }
    
    private void printAllReports(List<InventoryReport> reports) {
        if (isAdded()) {
            Toast.makeText(requireContext(), "🖨️ جاري طباعة " + reports.size() + " تقرير", Toast.LENGTH_SHORT).show();
            // TODO: Implement actual printing logic
        }
    }
    
    private void compareWithCurrent(InventoryReport oldReport) {
        if (!isAdded() || inventoryEngine == null) return;
        
        int currentScanned = inventoryEngine.getUniqueCount();
        int currentExpected = expectedCount;
        
        String comparison = String.format(
            "📊 مقارنة الجرد:\n\n" +
            "التقرير السابق (%s):\n" +
            "• المتوقع: %d\n" +
            "• الممسوح: %d\n" +
            "• المفقود: %d\n\n" +
            "الجرد الحالي:\n" +
            "• المتوقع: %d\n" +
            "• الممسوح: %d\n" +
            "• المفقود: %d\n\n" +
            "الفرق:\n" +
            "• الممسوح: %+d\n" +
            "• المفقود: %+d",
            oldReport.date,
            oldReport.expectedCount, oldReport.scannedCount, oldReport.missingCount,
            currentExpected, currentScanned, Math.max(0, currentExpected - currentScanned),
            currentScanned - oldReport.scannedCount,
            Math.max(0, currentExpected - currentScanned) - oldReport.missingCount
        );
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("📊 مقارنة الجرد")
                .setMessage(comparison)
                .setPositiveButton("إغلاق", null)
                .show();
    }

    // ==================== SNAPSHOTS ====================
    
    private void showSnapshotsDialog() {
        if (!isAdded()) return;
        
        String[] options = {
            "📸 عرض لقطات الجرد السابقة",
            "📊 مقارنة لقطتين",
            "❌ إغلاق"
        };
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("📸 لقطات الجرد")
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            showSnapshotsList();
                            break;
                        case 1:
                            showSnapshotComparison();
                            break;
                    }
                })
                .show();
    }
    
    private void showSnapshotsList() {
        if (!isAdded()) return;
        
        // Sample snapshots data
        String[] snapshots = {
            "📸 لقطة 1 - 2024-01-15 10:30 - 48 قطعة",
            "📸 لقطة 2 - 2024-01-14 14:45 - 50 قطعة",
            "📸 لقطة 3 - 2024-01-13 09:15 - 45 قطعة"
        };
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("📸 لقطات الجرد السابقة")
                .setItems(snapshots, (dialog, which) -> {
                    Toast.makeText(requireContext(), "تم اختيار: " + snapshots[which], Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton("إغلاق", null)
                .show();
    }
    
    private void showSnapshotComparison() {
        if (!isAdded()) return;
        
        String comparison = 
            "📊 مقارنة اللقطات:\n\n" +
            "لقطة 1 (2024-01-15):\n" +
            "• الممسوح: 48 قطعة\n" +
            "• المفقود: 2 قطعة\n\n" +
            "لقطة 2 (2024-01-14):\n" +
            "• الممسوح: 50 قطعة\n" +
            "• المفقود: 0 قطعة\n\n" +
            "الفرق بين اللقطتين:\n" +
            "• تغير في الممسوح: -2 قطعة\n" +
            "• تغير في المفقود: +2 قطعة";
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("📊 مقارنة اللقطات")
                .setMessage(comparison)
                .setPositiveButton("إغلاق", null)
                .show();
    }

    // ==================== EXIT WARNING ====================
    
    @Override
    public void onPause() {
        super.onPause();
        if (isScanning) {
            showExitWarning();
        }
    }
    
    private void showExitWarning() {
        if (!isAdded()) return;
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("⚠️ تحذير مهم")
                .setMessage("الجرد ما زال قيد التشغيل!\n\n" +
                           "إذا قمت بتغيير الواجهة، سيتوقف الجرد وستفقد البيانات غير المحفوظة.\n\n" +
                           "هل تريد إيقاف الجرد والمتابعة؟")
                .setPositiveButton("إيقاف والمتابعة", (dialog, which) -> {
                    stopScanning();
                })
                .setNegativeButton("البقاء في الجرد", (dialog, which) -> {
                    // Stay on current screen
                    dialog.dismiss();
                })
                .setCancelable(false)
                .show();
    }

    // ==================== SIMULATION ====================
    
    private void startSimulationLoop() {
        simulationIndex = 0;
        
        simulationRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isScanning) return;
                
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
    
    // ==================== MODEL CLASS ====================
    
    private static class InventoryReport {
        int id;
        String sessionId;
        String date;
        int expectedCount;
        int scannedCount;
        int missingCount;
        String status;
        
        InventoryReport(int id, String sessionId, String date, 
                       int expectedCount, int scannedCount, int missingCount, String status) {
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
