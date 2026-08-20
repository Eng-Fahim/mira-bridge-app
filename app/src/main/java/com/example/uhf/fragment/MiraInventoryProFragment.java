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

public class MiraInventoryProFragment extends KeyDwonFragment 
        implements MiraIRFIDReader.OnTagReadListener {

    private static final String TAG = "MiraInventoryPro";

    private TextView tvExpectedCount;
    private TextView tvScannedCount;
    private TextView tvProgressPercent;
    private ProgressBar progressBarInventory;
    
    private Button btnStartInventory;
    private Button btnStopInventory;
    private Button btnCommitInventory;

    private MiraInventoryEngine inventoryEngine;
    private ExecutorService backgroundExecutor;
    private Handler mainThreadHandler;
    private volatile boolean isScanning = false;
    
    private RFIDManager rfidManager;
    
    // Sound & Beeper Controls
    private SoundPool soundPool;
    private int beepSoundId;
    private boolean soundLoaded = false;
    private long lastBeepTime = 0;

    // Simulation Controls
    private Handler simulationHandler;
    private Runnable simulationRunnable;
    private int simulationIndex = 0;
    private final String[] simulationTags = {
        "A000001", "A000002", "A000003", "A000004", "A000005",
        "A000006", "A000007", "A000008", "A000009", "A000010"
    };
    
    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_inventory_pro, container, false);
        
        initSoundPool();
        initViews(view);

        backgroundExecutor = Executors.newFixedThreadPool(2);
        mainThreadHandler = new Handler(Looper.getMainLooper());
        simulationHandler = new Handler(Looper.getMainLooper());

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
                .setMaxStreams(5)
                .setAudioAttributes(attributes)
                .build();

        // تأكد من وجود صوت beep.ogg داخل مجلد res/raw
        beepSoundId = soundPool.load(requireContext(), R.raw.beep, 1);
        soundPool.setOnLoadCompleteListener((sp, sampleId, status) -> soundLoaded = (status == 0));
    }

    private void playScanBeep() {
        // خفض معدل تكرار الصوت لتجنب البطء عند المسح السريع جداً
        long now = System.currentTimeMillis();
        if (soundLoaded && (now - lastBeepTime > 60)) { 
            soundPool.play(beepSoundId, 0.7f, 0.7f, 0, 0, 1.0f);
            lastBeepTime = now;
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
        if (keyCode == 139 || keyCode == 280 || keyCode == 293) {
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
        if (!isScanning || epc == null) return;

        backgroundExecutor.execute(() -> {
            boolean isNew = inventoryEngine.onTagScanned(epc, rssi);
            if (isNew) {
                mainThreadHandler.post(() -> {
                    playScanBeep();
                    updateUI();
                });
            }
        });
    }

    @Override
    public void onScanStarted() { Log.d(TAG, "📡 RFID Scan Started"); }
    @Override
    public void onScanStopped() { Log.d(TAG, "📡 RFID Scan Stopped"); }
    @Override
    public void onError(String errorMessage) { Log.e(TAG, "RFID Error: " + errorMessage); }
    @Override
    public void onConnectionChanged(boolean isConnected) { Log.i(TAG, "RFID Connection: " + isConnected); }

    // ==================== UI & PROGRESS UPDATE ====================
    
    private void updateUI() {
        if (!isAdded()) return;
        
        int scanned = inventoryEngine.getUniqueCount();
        int expected = inventoryEngine.getExpectedCount();

        if (tvScannedCount != null) {
            tvScannedCount.setText(String.valueOf(scanned));
        }

        if (expected > 0 && progressBarInventory != null) {
            int progress = (int) (((float) scanned / expected) * 100);
            progressBarInventory.setProgress(Math.min(progress, 100));
            
            if (tvProgressPercent != null) {
                tvProgressPercent.setText(progress + "%");
            }

            // التحقق من اكتمال الجرد بنسبة 100%
            if (scanned >= expected && isScanning) {
                stopScanning();
                Toast.makeText(requireContext(), "🎉 اكتمل جرد جميع المواد المتوقعة!", Toast.LENGTH_LONG).show();
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
        Toast.makeText(requireContext(), "🔄 جاري تحميل البيانات...", Toast.LENGTH_SHORT).show();
        
        backgroundExecutor.execute(() -> {
            Map<String, InventoryItem> catalog = fetchCatalogFromServer();
            
            mainThreadHandler.post(() -> {
                if (!isAdded()) return;
                inventoryEngine.startSession(catalog);
                
                int expectedSize = catalog != null ? catalog.size() : 0;
                if (tvExpectedCount != null) {
                    tvExpectedCount.setText(String.valueOf(expectedSize));
                }
                
                if (progressBarInventory != null) {
                    progressBarInventory.setMax(100);
                    progressBarInventory.setProgress(0);
                }

                startReadingSource();
            });
        });
    }
    
    private Map<String, InventoryItem> fetchCatalogFromServer() {
        Map<String, InventoryItem> catalog = new ConcurrentHashMap<>();
        try {
            if (apiClient == null || branchesManager == null) return catalog;
            
            int branchId = branchesManager.getActiveBranchId();
            MiraApiClient.ApiResponse response = apiClient.getBranchItemsList(branchId);
            
            if (response != null && response.isSuccess && response.data != null) {
                JSONArray items = response.data.optJSONArray("items");
                if (items == null) {
                    JSONObject dataObj = response.data.optJSONObject("data");
                    if (dataObj != null) items = dataObj.optJSONArray("items");
                }
                
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject obj = items.getJSONObject(i);
                        String epc = obj.optString("gtin13", 
                            obj.optString("serial_number", 
                            obj.optString("epc", "")));
                        
                        if (epc.isEmpty()) continue;
                        
                        InventoryItem item = new InventoryItem(
                            epc.toUpperCase(),
                            obj.optString("title", "Item"),
                            obj.optString("karat", "21K"),
                            "✓"
                        );
                        catalog.put(epc.toUpperCase(), item);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Catalog error: " + e.getMessage());
        }
        return catalog;
    }
    
    private void startReadingSource() {
        boolean hardwareStarted = false;
        
        if (rfidManager != null) {
            try {
                MiraIRFIDReader.ReaderType deviceType = rfidManager.getActiveDevice().getType();
                if (deviceType != MiraIRFIDReader.ReaderType.SIMULATOR) {
                    hardwareStarted = rfidManager.startScan();
                }
            } catch (Exception e) {
                Log.e(TAG, "Hardware start error: " + e.getMessage());
            }
        }
        
        if (!hardwareStarted) {
            startSimulationLoop();
            Toast.makeText(requireContext(), "🎯 بدء وضع المحاكاة", Toast.LENGTH_SHORT).show();
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
        
        if (rfidManager != null) rfidManager.stopScan();
        
        backgroundExecutor.execute(() -> {
            inventoryEngine.processSessionClose();
            mainThreadHandler.post(() -> {
                if (!isAdded()) return;
                Toast.makeText(requireContext(), "🛑 تم إيقاف المسح", Toast.LENGTH_SHORT).show();
                
                if (btnStartInventory != null) btnStartInventory.setEnabled(true);
                if (btnStopInventory != null) btnStopInventory.setEnabled(false);
                if (btnCommitInventory != null) btnCommitInventory.setEnabled(true);
            });
        });
    }

    // ==================== COMMIT & UPLOAD TO DATABASE ====================

    private void showCommitConfirmationDialog() {
        int scanned = inventoryEngine.getUniqueCount();
        int expected = inventoryEngine.getExpectedCount();
        int missing = Math.max(0, expected - scanned);

        String message = String.format("📊 ملخص عمليات الجرد:\n\n• المتوقع: %d\n• المنسوح: %d\n• المفقود: %d\n\nهل تريد اعتماد العملية وإرسالها لقاعدة البيانات؟",
                expected, scanned, missing);

        new AlertDialog.Builder(requireContext())
                .setTitle("حفظ واكتمال الجرد")
                .setMessage(message)
                .setPositiveButton("إرسال واعتماد", (dialog, which) -> commitInventoryToServer())
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void commitInventoryToServer() {
        Toast.makeText(requireContext(), "⏳ جاري إرسال البيانات للأنظمة...", Toast.LENGTH_SHORT).show();
        
        backgroundExecutor.execute(() -> {
            try {
                // إرسال الحزمة للسيرفر عبر ApiClient
                JSONObject payload = inventoryEngine.getExportPayload();
                MiraApiClient.ApiResponse response = apiClient.submitInventorySession(payload);
                
                mainThreadHandler.post(() -> {
                    if (response != null && response.isSuccess) {
                        Toast.makeText(requireContext(), "✅ تم اعتماد الجرد بنجاح في قاعدة البيانات", Toast.LENGTH_LONG).show();
                        if (btnCommitInventory != null) btnCommitInventory.setEnabled(false);
                    } else {
                        Toast.makeText(requireContext(), "❌ فشل الإرسال: " + (response != null ? response.message : "خطأ شبكة"), Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Commit error: " + e.getMessage());
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
                
                if (simulationIndex < simulationTags.length) {
                    String epc = simulationTags[simulationIndex];
                    int rssi = -40 - (int)(Math.random() * 40);
                    
                    onTagRead(epc, rssi);
                    
                    simulationIndex++;
                    long delay = 250 + (long)(Math.random() * 250);
                    simulationHandler.postDelayed(this, delay);
                } else {
                    stopScanning();
                }
            }
        };
        
        simulationHandler.postDelayed(simulationRunnable, 500);
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
            rfidManager.stopScan();
            rfidManager.setListener(null);
        }
        
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
        
        if (simulationHandler != null) {
            simulationHandler.removeCallbacksAndMessages(null);
        }
        
        if (backgroundExecutor != null && !backgroundExecutor.isShutdown()) {
            backgroundExecutor.shutdown();
        }
    }
}
