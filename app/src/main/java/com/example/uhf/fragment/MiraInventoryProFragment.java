package com.example.uhf.fragment;

import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import com.example.uhf.R;
import com.example.uhf.engine.MiraInventoryEngine;
import com.example.uhf.MiraRfid.MiraIRFIDReader;
import com.example.uhf.MiraRfid.RFIDManager;
import com.example.uhf.model.InventoryItem;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MiraInventoryProFragment extends KeyDwonFragment 
        implements MiraIRFIDReader.OnTagReadListener {

    private static final String TAG = "MiraInventoryPro";
    
    // Constants for better readability and maintainability
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
    private ProgressBar progressBarInventory;
    
    private Button btnStartInventory;
    private Button btnStopInventory;
    private Button btnCommitInventory;

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
    
    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    
    // Local variables to track counts
    private int expectedCount = 0;
    private int sessionId = -1; // Track server session ID if created

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

        beepSoundId = soundPool.load(requireContext(), R.raw.beep, 1);
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
        tvExpectedCount = view.findViewById(R.id.tvExpectedCount);
        tvScannedCount = view.findViewById(R.id.tvScannedCount);
        tvProgressPercent = view.findViewById(R.id.tvProgressPercent);
        progressBarInventory = view.findViewById(R.id.progressBarInventory);
        
        btnStartInventory = view.findViewById(R.id.btnStartInventory);
        btnStopInventory = view.findViewById(R.id.btnStopInventory);
        btnCommitInventory = view.findViewById(R.id.btnCommitInventory);

        if (btnStartInventory != null) {
            btnStartInventory.setOnClickListener(v -> startScanning());
        }
        if (btnStopInventory != null) {
            btnStopInventory.setOnClickListener(v -> stopScanning());
            btnStopInventory.setEnabled(false);
        }
        if (btnCommitInventory != null) {
            btnCommitInventory.setOnClickListener(v -> showCommitConfirmationDialog());
            btnCommitInventory.setEnabled(false);
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
        
        backgroundExecutor.execute(() -> {
            try {
                inventoryEngine.onTagScanned(normalizedEpc, rssi);
                mainThreadHandler.post(() -> {
                    playScanBeep();
                    scheduleUiUpdate();
                });
            } catch (Exception e) {
                Log.e(TAG, "Error processing tag: " + normalizedEpc, e);
            }
        });
    }
    
    private void scheduleUiUpdate() {
        // Schedule only one pending main-thread update to reduce UI churn
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
    }

    // ==================== UI & PROGRESS UPDATE ====================
    
    private void updateUI() {
        if (!isAdded() || inventoryEngine == null) return;
        
        int scanned = inventoryEngine.getUniqueCount();

        if (tvScannedCount != null) {
            tvScannedCount.setText(String.valueOf(scanned));
        }

        if (expectedCount > 0 && progressBarInventory != null) {
            int progress = (int) (((float) scanned / expectedCount) * 100);
            progressBarInventory.setProgress(Math.min(progress, 100));
            
            if (tvProgressPercent != null) {
                tvProgressPercent.setText(progress + "%");
            }

            // Check if inventory is complete
            if (scanned >= expectedCount && isScanning) {
                stopScanning();
                if (isAdded()) {
                    Toast.makeText(requireContext(), "🎉 اكتمل جرد جميع المواد المتوقعة!", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    // ==================== START SCANNING ====================
    
    private void startScanning() {
        if (isScanning) return;
        isScanning = true;
        loadCatalogAndStart();
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
                
                if (progressBarInventory != null) {
                    progressBarInventory.setMax(100);
                    progressBarInventory.setProgress(0);
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
                            Log.w(TAG, "Empty EPC found in item at index: " + i);
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
                } else {
                    Log.w(TAG, "No items array found in response");
                }
            } else {
                String errorMsg = response != null && response.error != null ? response.error : "Unknown error";
                Log.w(TAG, "Failed to fetch catalog: " + errorMsg);
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
                } else {
                    Log.w(TAG, "Active device is null, falling back to simulation");
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
        } else {
            if (isAdded()) {
                Toast.makeText(requireContext(), "📡 بدء الجرد الفعلي", Toast.LENGTH_SHORT).show();
            }
        }
        
        if (btnStartInventory != null) btnStartInventory.setEnabled(false);
        if (btnStopInventory != null) btnStopInventory.setEnabled(true);
        if (btnCommitInventory != null) btnCommitInventory.setEnabled(false);
    }

    // ==================== STOP SCANNING ====================
    
    private void stopScanning() {
        if (!isScanning) return;
        isScanning = false;
        stopSimulationLoop();
        
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
                    
                    Toast.makeText(requireContext(), "🛑 تم إيقاف المسح", Toast.LENGTH_SHORT).show();
                    
                    if (btnStartInventory != null) btnStartInventory.setEnabled(true);
                    if (btnStopInventory != null) btnStopInventory.setEnabled(false);
                    if (btnCommitInventory != null) btnCommitInventory.setEnabled(true);
                    
                    // Update final UI
                    updateUI();
                });
            } catch (Exception e) {
                Log.e(TAG, "Error processing session close", e);
                mainThreadHandler.post(() -> {
                    if (isAdded()) {
                        Toast.makeText(requireContext(), "❌ خطأ في إيقاف الجرد", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    // ==================== COMMIT & UPLOAD TO DATABASE ====================

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
        
        Toast.makeText(requireContext(), "⏳ جاري إرسال البيانات للأنظمة...", Toast.LENGTH_SHORT).show();
        
        backgroundExecutor.execute(() -> {
            try {
                // Build evidence payload from inventory engine
                JSONObject payload = inventoryEngine.buildEvidencePayload();
                
                // Use the existing method to submit inventory audit
                if (apiClient != null) {
                    apiClient.submitInventoryAuditReport(payload);
                }
                
                mainThreadHandler.post(() -> {
                    if (!isAdded()) return;
                    
                    Toast.makeText(requireContext(), "✅ تم اعتماد الجرد بنجاح في قاعدة البيانات", Toast.LENGTH_LONG).show();
                    if (btnCommitInventory != null) btnCommitInventory.setEnabled(false);
                });
            } catch (Exception e) {
                Log.e(TAG, "Error committing inventory to server", e);
                mainThreadHandler.post(() -> {
                    if (isAdded()) {
                        Toast.makeText(requireContext(), "❌ خطأ في إرسال البيانات", Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    // ==================== SIMULATION ====================
    
    private void startSimulationLoop() {
        simulationIndex = 0;
        
        simulationRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isScanning) return;
                
                // Use modulo for continuous simulation
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
        
        if (mainThreadHandler != null) {
            mainThreadHandler.removeCallbacksAndMessages(null);
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
        // Optional: Stop scanning when fragment is paused
        if (isScanning) {
            stopScanning();
        }
    }
}
