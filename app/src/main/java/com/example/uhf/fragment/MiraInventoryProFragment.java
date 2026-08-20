package com.example.uhf.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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
    private ExecutorService scannerExecutor;
    private Handler mainThreadHandler;
    private boolean isScanning = false;
    
    // RFID
    private RFIDManager rfidManager;
    
    // Simulation
    private Handler simulationHandler;
    private Runnable simulationRunnable;
    private int simulationIndex = 0;
    private final String[] simulationTags = {
        "A000001", "A000002", "A000003", "A000004", "A000005",
        "A000006", "A000007", "A000008", "A000009", "A000010",
        "A000011", "A000012", "A000013", "A000014", "A000015"
    };
    
    // API
    private MiraApiClient apiClient;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_inventory_pro, container, false);
        initViews(view);

        scannerExecutor = Executors.newSingleThreadExecutor();
        mainThreadHandler = new Handler(Looper.getMainLooper());
        simulationHandler = new Handler(Looper.getMainLooper());

        String sessionId = "SESS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String deviceId = android.provider.Settings.Secure.getString(
                requireContext().getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID
        );

        inventoryEngine = new MiraInventoryEngine(requireContext(), sessionId, deviceId);
        apiClient = MiraApiClient.getInstance(requireContext());
        
        // ✅ تهيئة RFID
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

    // ==================== RFID CALLBACKS ====================
    
    @Override
    public void onTagRead(String epc, String rssi) {
        if (!isScanning || epc == null) return;

        int parsedRssi = -60;
        try {
            parsedRssi = Integer.parseInt(rssi);
        } catch (Exception ignored) {}

        final int finalRssi = parsedRssi;
        scannerExecutor.execute(() -> {
            inventoryEngine.onTagScanned(epc, finalRssi);
            mainThreadHandler.post(this::updateUI);
        });
    }
    
    @Override
    public void onTagRead(String epc, int rssi) {
        onTagRead(epc, String.valueOf(rssi));
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
        isScanning = true;
        
        // ✅ 1. تحميل الكتالوج من السيرفر
        loadCatalogAndStart();
    }
    
    private void loadCatalogAndStart() {
        // ✅ عرض رسالة تحميل
        Toast.makeText(requireContext(), "🔄 جاري تحميل الكتالوج...", Toast.LENGTH_SHORT).show();
        
        new Thread(() -> {
            Map<String, InventoryItem> catalog = fetchCatalogFromServer();
            
            mainThreadHandler.post(() -> {
                // ✅ 2. بدء الجلسة مع الكتالوج
                inventoryEngine.startSession(catalog);
                
                // ✅ 3. تحديث العداد المتوقع
                if (tvExpectedCount != null) {
                    tvExpectedCount.setText(String.valueOf(catalog != null ? catalog.size() : 0));
                }
                
                // ✅ 4. بدء مصدر القراءة
                startReadingSource();
            });
        }).start();
    }
    
    private Map<String, InventoryItem> fetchCatalogFromServer() {
        Map<String, InventoryItem> catalog = new ConcurrentHashMap<>();
        
        try {
            if (apiClient == null) return catalog;
            
            // ✅ جلب العناصر من الفرع النشط
            int branchId = com.example.uhf.manager.MiraBranchesManager
                .getInstance(requireContext()).getActiveBranchId();
            
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
                        double weight = obj.optDouble("weight", 0.0);
                        
                        InventoryItem item = new InventoryItem(
                            epc.toUpperCase(), title, karat, weight, "✓", ""
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
        // ✅ محاولة بدء RFID الحقيقي
        boolean hardwareStarted = false;
        
        if (rfidManager != null) {
            try {
                MiraIRFIDReader.ReaderType deviceType = rfidManager.getActiveDevice().getType();
                
                if (deviceType != MiraIRFIDReader.ReaderType.SIMULATOR) {
                    hardwareStarted = rfidManager.startScan();
                    if (hardwareStarted) {
                        Log.d(TAG, "✅ Hardware RFID started");
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Hardware start error: " + e.getMessage());
            }
        }
        
        // ✅ إذا لم يبدأ الجهاز، ابدأ المحاكاة
        if (!hardwareStarted) {
            startSimulationLoop();
            Toast.makeText(requireContext(), 
                "🎯 لا يوجد قارئ متصل - بدء المحاكاة", 
                Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(requireContext(), 
                "📡 بدء الجرد الفعلي", 
                Toast.LENGTH_SHORT).show();
        }
        
        // ✅ تفعيل/تعطيل الأزرار
        if (btnStartInventory != null) btnStartInventory.setEnabled(false);
        if (btnStopInventory != null) btnStopInventory.setEnabled(true);
    }

    // ==================== STOP SCANNING ====================
    
    private void stopScanning() {
        isScanning = false;
        stopSimulationLoop();
        
        // ✅ إيقاف RFID
        if (rfidManager != null) {
            rfidManager.stopScan();
        }
        
        scannerExecutor.execute(() -> {
            inventoryEngine.processSessionClose();
            mainThreadHandler.post(() -> {
                Toast.makeText(requireContext(), 
                    "✅ تم إيقاف الجرد وحفظ الحزمة", 
                    Toast.LENGTH_SHORT).show();
                
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
                    
                    // ✅ استدعاء onTagRead
                    onTagRead(epc, String.valueOf(rssi));
                    
                    simulationIndex++;
                    long delay = 150 + (long)(Math.random() * 350);
                    simulationHandler.postDelayed(this, delay);
                } else {
                    Log.d(TAG, "✅ Simulation completed: " + simulationIndex + " tags");
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
        
        if (scannerExecutor != null && !scannerExecutor.isShutdown()) {
            scannerExecutor.shutdown();
        }
    }
}
