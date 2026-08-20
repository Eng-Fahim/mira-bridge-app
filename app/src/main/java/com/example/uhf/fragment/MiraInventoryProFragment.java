package com.example.uhf.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
    private Button btnStartInventory;
    private Button btnStopInventory;

    private MiraInventoryEngine inventoryEngine;
    private ExecutorService backgroundExecutor;
    private Handler mainThreadHandler;
    private volatile boolean isScanning = false;
    
    private RFIDManager rfidManager;
    
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

    private void initViews(View view) {
        tvExpectedCount = view.findViewById(R.id.tvExpectedCount);
        tvScannedCount = view.findViewById(R.id.tvScannedCount);
        
        btnStartInventory = view.findViewById(R.id.btnStartInventory);
        btnStopInventory = view.findViewById(R.id.btnStopInventory);

        if (btnStartInventory != null) {
            btnStartInventory.setOnClickListener(v -> startScanning());
        }
        if (btnStopInventory != null) {
            btnStopInventory.setOnClickListener(v -> stopScanning());
            btnStopInventory.setEnabled(false);
        }
    }

    // ==================== HARDWARE KEY TRIGGER ====================

    public boolean myOnKeyDwon(int keyCode, KeyEvent event) {
        if (keyCode == 139 || keyCode == 280 || keyCode == 293) { // RFID Trigger Buttons
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
            inventoryEngine.onTagScanned(epc, rssi);
            mainThreadHandler.post(this::updateUI);
        });
    }

    @Override
    public void onScanStarted() {
        Log.d(TAG, "📡 RFID Scan Started");
    }

    @Override
    public void onScanStopped() {
        Log.d(TAG, "📡 RFID Scan Stopped");
    }

    @Override
    public void onError(String errorMessage) {
        Log.e(TAG, "RFID Error: " + errorMessage);
    }

    @Override
    public void onConnectionChanged(boolean isConnected) {
        Log.i(TAG, "RFID Connection: " + isConnected);
    }

    // ==================== UI UPDATE ====================
    
    private void updateUI() {
        if (!isAdded()) return;
        if (tvScannedCount != null) {
            tvScannedCount.setText(String.valueOf(inventoryEngine.getUniqueCount()));
        }
    }

    // ==================== START SCANNING ====================
    
    private void startScanning() {
        if (isScanning) return;
        isScanning = true;
        loadCatalogAndStart();
    }
    
    private void loadCatalogAndStart() {
        Toast.makeText(requireContext(), "🔄 جاري تحميل الكتالوج...", Toast.LENGTH_SHORT).show();
        
        backgroundExecutor.execute(() -> {
            Map<String, InventoryItem> catalog = fetchCatalogFromServer();
            
            mainThreadHandler.post(() -> {
                if (!isAdded()) return;
                inventoryEngine.startSession(catalog);
                
                if (tvExpectedCount != null) {
                    tvExpectedCount.setText(String.valueOf(catalog != null ? catalog.size() : 0));
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
            Log.e(TAG, "Fetch catalog error: " + e.getMessage());
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
            Toast.makeText(requireContext(), "🎯 بدء وضع المحاكاة", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(requireContext(), "📡 بدء الجرد الفعلي", Toast.LENGTH_SHORT).show();
        }
        
        if (btnStartInventory != null) btnStartInventory.setEnabled(false);
        if (btnStopInventory != null) btnStopInventory.setEnabled(true);
    }

    // ==================== STOP SCANNING ====================
    
    private void stopScanning() {
        isScanning = false;
        stopSimulationLoop();
        
        if (rfidManager != null) {
            rfidManager.stopScan();
        }
        
        backgroundExecutor.execute(() -> {
            inventoryEngine.processSessionClose();
            mainThreadHandler.post(() -> {
                if (!isAdded()) return;
                Toast.makeText(requireContext(), "✅ تم إيقاف الجرد وحفظ الحزمة", Toast.LENGTH_SHORT).show();
                
                if (btnStartInventory != null) btnStartInventory.setEnabled(true);
                if (btnStopInventory != null) btnStopInventory.setEnabled(false);
            });
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
                    long delay = 150 + (long)(Math.random() * 350);
                    simulationHandler.postDelayed(this, delay);
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
        
        if (simulationHandler != null) {
            simulationHandler.removeCallbacksAndMessages(null);
        }
        
        if (backgroundExecutor != null && !backgroundExecutor.isShutdown()) {
            backgroundExecutor.shutdown();
        }
    }
}
