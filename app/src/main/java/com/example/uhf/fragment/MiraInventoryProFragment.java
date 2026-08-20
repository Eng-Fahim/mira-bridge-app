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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale; // Fix: Added missing Locale import
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraInventoryProFragment extends KeyDwonFragment 
        implements MiraIRFIDReader.OnTagReadListener {

    private static final String TAG = "MiraInventoryPro";
    private ScannedItemsAdapter scannedItemsAdapter;
    private final List<ScannedItemDisplay> scannedItemsDisplayList = new ArrayList<>();
    
    // Constants
    private static final long UI_UPDATE_THROTTLE_MS = 200;
    private static final long SIMULATION_INITIAL_DELAY_MS = 500;
    private static final long SIMULATION_MIN_DELAY_MS = 150;
    private static final long BEEP_THROTTLE_MS = 60;
    private static final int MAX_SOUND_STREAMS = 5;
    private static final float BEEP_VOLUME = 0.7f;
    private static final float BEEP_RATE = 1.0f;
    private static final int BEEP_PRIORITY = 0;
    private static final int BEEP_LOOP = 0;
    
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
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());
    private volatile boolean isScanning = false;
    
    private RFIDManager rfidManager;
    private SoundPool soundPool;
    private int beepSoundId;
    private boolean soundLoaded = false;
    private long lastBeepTime = 0;

    private final Handler simulationHandler = new Handler(Looper.getMainLooper());
    private Runnable simulationRunnable;
    private int simulationIndex = 0;
    private final String[] simulationTags = {
        "A000001", "A000002", "A000003", "A000004", "A000005",
        "A000006", "A000007", "A000008", "A000009", "A000010"
    };
    
    private final Set<String> catalogEpcs = new HashSet<>();
    
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
            if (isScanning && isAdded()) {
                updateElapsedTime();
                mainThreadHandler.postDelayed(this, 1000);
            }
        }
    };
    private long scanStartTime = 0;
    
    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private int expectedCount = 0;

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
        
        tvItemCount = view.findViewById(R.id.tvItemCount);
        tvEmptyState = view.findViewById(R.id.tvEmptyState);
        
        rvInventoryItems = view.findViewById(R.id.rvInventoryItems);
        if (rvInventoryItems != null) {
            rvInventoryItems.setLayoutManager(new LinearLayoutManager(requireContext()));
            scannedItemsAdapter = new ScannedItemsAdapter(scannedItemsDisplayList);
            rvInventoryItems.setAdapter(scannedItemsAdapter);
            rvInventoryItems.setHasFixedSize(false);
            rvInventoryItems.setItemAnimator(null);
        }
        
        tabInventoryModes = view.findViewById(R.id.tabInventoryModes);
        if (tabInventoryModes != null) {
            tabInventoryModes.addTab(tabInventoryModes.newTab().setText("جرد شامل"));
            tabInventoryModes.addTab(tabInventoryModes.newTab().setText("جرد فئوي"));
        }
        
        btnStartInventory = view.findViewById(R.id.btnStartInventory);
        btnBlindInventory = view.findViewById(R.id.btnBlindInventory);
        btnStopInventory = view.findViewById(R.id.btnStopInventory);
        btnGenerateReport = view.findViewById(R.id.btnGenerateReport);
        btnArchiveReports = view.findViewById(R.id.btnArchiveReports);
        btnViewSnapshots = view.findViewById(R.id.btnViewSnapshots);

        if (btnStartInventory != null) btnStartInventory.setOnClickListener(v -> startScanning());
        if (btnBlindInventory != null) btnBlindInventory.setOnClickListener(v -> startBlindInventory());
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
                    addScannedItemToDisplay(normalizedEpc, finalRssi, null);
                    scheduleUiUpdate();
                });
            } catch (Exception e) {
                Log.e(TAG, "Error processing tag: " + normalizedEpc, e);
            }
        });
    }

    private void addScannedItemToDisplay(String epc, int rssi, InventoryItem item) {
        if (scannedItemsAdapter == null) return;
        
        boolean exists = false;
        for (ScannedItemDisplay display : scannedItemsDisplayList) {
            if (display.epc.equals(epc)) {
                display.count++;
                display.rssi = rssi;
                exists = true;
                break;
            }
        }
        
        if (!exists) {
            String title = (item != null && item.getTitle() != null) ? item.getTitle() : "صنف مسجّل";
            String karat = "—"; // Safe fallback to avoid missing method errors
            
            ScannedItemDisplay display = new ScannedItemDisplay(
                epc,
                title,
                karat,
                1,
                rssi
            );
            scannedItemsDisplayList.add(0, display);
        }
        
        scannedItemsAdapter.notifyDataSetChanged();
        
        if (tvEmptyState != null && !scannedItemsDisplayList.isEmpty()) {
            tvEmptyState.setVisibility(View.GONE);
        }
    }

    private static class ScannedItemDisplay {
        String epc;
        String title;
        String karat;
        int count;
        int rssi;
        
        ScannedItemDisplay(String epc, String title, String karat, int count, int rssi) {
            this.epc = epc;
            this.title = title;
            this.karat = karat;
            this.count = count;
            this.rssi = rssi;
        }
    }

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
            holder.tvTitle.setText(item.title + (!item.karat.equals("—") ? " (" + item.karat + ")" : ""));
            holder.tvEpc.setText("EPC: " + item.epc);
            holder.tvRssi.setText(item.rssi + " dBm");
            holder.tvCount.setText(item.count + "x");
        }
        
        @Override
        public int getItemCount() {
            return items.size();
        }
        
        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvTitle;
            TextView tvEpc;
            TextView tvRssi;
            TextView tvCount;
            
            ViewHolder(View itemView) {
                super(itemView);
                tvTitle = itemView.findViewById(R.id.tvTagTitle);
                tvEpc = itemView.findViewById(R.id.tvTagEpc);
                tvRssi = itemView.findViewById(R.id.tvTagRssi);
                tvCount = itemView.findViewById(R.id.tvTagCount); // Safe binding
            }
        }
    }

    private void updateLastScan(String epc, int rssi) {
        if (tvLastScan != null) {
            tvLastScan.setText("آخر قراءة: " + epc);
        }
        if (tvRssi != null) {
            tvRssi.setText("📶 " + rssi + " dBm");
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
    public void onScanStarted() { Log.d(TAG, "RFID Scan Started"); }
    
    @Override
    public void onScanStopped() { Log.d(TAG, "RFID Scan Stopped"); }
    
    @Override
    public void onError(String errorMessage) { 
        Log.e(TAG, "RFID Error: " + errorMessage); 
        if (isAdded()) {
            mainThreadHandler.post(() -> Toast.makeText(requireContext(), "خطأ القارئ: " + errorMessage, Toast.LENGTH_SHORT).show());
        }
    }
    
    @Override
    public void onConnectionChanged(boolean isConnected) { 
        Log.i(TAG, "RFID Connection: " + isConnected); 
        if (isAdded() && tvInventoryStatus != null) {
            tvInventoryStatus.setText(isConnected ? "📡 المتصل بالماسح" : "📡 غير متصل");
        }
    }

    private void updateUI() {
        if (!isAdded() || inventoryEngine == null) return;
        
        int scanned = inventoryEngine.getUniqueCount();

        if (tvScannedCount != null) tvScannedCount.setText(String.valueOf(scanned));
        if (tvItemCount != null) tvItemCount.setText(String.valueOf(scanned));

        if (expectedCount > 0) {
            int progress = (int) (((float) scanned / expectedCount) * 100);
            progress = Math.min(progress, 100);
            
            if (progressScanned != null) progressScanned.setProgress(progress);
            if (tvProgressPercent != null) tvProgressPercent.setText(progress + "%");
            
            int variance = Math.max(0, expectedCount - scanned);
            if (tvVariance != null) tvVariance.setText(String.valueOf(variance));
            
            int accuracy = expectedCount > 0 ? (int)(((float)scanned / expectedCount) * 100) : 0;
            if (tvAccuracy != null) tvAccuracy.setText(accuracy + "%");

            if (scanned >= expectedCount && isScanning) {
                stopScanning();
                if (isAdded()) {
                    Toast.makeText(requireContext(), "🎉 اكتمل جرد كافة المواد المتوقعة!", Toast.LENGTH_LONG).show();
                }
            }
        }
        
        if (tvEmptyState != null) {
            tvEmptyState.setVisibility(scanned > 0 ? View.GONE : View.VISIBLE);
        }
        
        if (tvInventoryStatus != null && isScanning) {
            tvInventoryStatus.setText("📡 جاري المسح... (القطع: " + scanned + ")");
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
        
        if (tvExpectedCount != null) tvExpectedCount.setText("—");
        if (tvInventoryStatus != null) tvInventoryStatus.setText("⚡ جرد أعمى مستمر");
        
        inventoryEngine.startSession(new ConcurrentHashMap<>());
        startReadingSource();
    }
    
    private void loadCatalogAndStart() {
        if (!isAdded()) return;
        
        Toast.makeText(requireContext(), "🔄 جاري مزامنة القواعد...", Toast.LENGTH_SHORT).show();
        
        backgroundExecutor.execute(() -> {
            Map<String, InventoryItem> catalog = fetchCatalogFromServer();
            
            mainThreadHandler.post(() -> {
                if (!isAdded()) return;
                
                inventoryEngine.startSession(catalog);
                
                catalogEpcs.clear();
                if (catalog != null) catalogEpcs.addAll(catalog.keySet());
                
                expectedCount = catalog != null ? catalog.size() : 0;
                if (tvExpectedCount != null) tvExpectedCount.setText(String.valueOf(expectedCount));
                if (progressScanned != null) {
                    progressScanned.setMax(100);
                    progressScanned.setProgress(0);
                }
                if (tvProgressPercent != null) tvProgressPercent.setText("0%");

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
                            obj.optString("epc", ""))).trim();
                        
                        if (epc.isEmpty()) continue;
                        
                        String title = obj.optString("title", "صنف تجاري");
                        String karat = obj.optString("karat", "—");
                        
                        InventoryItem item = new InventoryItem(epc.toUpperCase(), title, karat, "✓");
                        catalog.put(epc.toUpperCase(), item);
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
            try {
                if (rfidManager.getActiveDevice().getType() != MiraIRFIDReader.ReaderType.SIMULATOR) {
                    hardwareStarted = rfidManager.startScan();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error starting scanner", e);
            }
        }
        
        if (!hardwareStarted) {
            startSimulationLoop();
            if (isAdded()) Toast.makeText(requireContext(), "🎯 وضع المحاكاة نشط", Toast.LENGTH_SHORT).show();
        }
        
        if (btnStartInventory != null) btnStartInventory.setEnabled(false);
        if (btnBlindInventory != null) btnBlindInventory.setEnabled(false);
        if (btnStopInventory != null) btnStopInventory.setEnabled(true);
        if (btnGenerateReport != null) btnGenerateReport.setEnabled(false);
        if (btnArchiveReports != null) btnArchiveReports.setEnabled(false);
        if (btnViewSnapshots != null) btnViewSnapshots.setEnabled(false);
    }

    private void stopScanning() {
        if (!isScanning) return;
        isScanning = false;
        stopSimulationLoop();
        mainThreadHandler.removeCallbacks(timerRunnable);
        
        if (rfidManager != null) {
            try {
                rfidManager.stopScan();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping scanner", e);
            }
        }
        
        backgroundExecutor.execute(() -> {
            try {
                inventoryEngine.processSessionClose();
                mainThreadHandler.post(() -> {
                    if (!isAdded()) return;
                    
                    if (tvInventoryStatus != null) tvInventoryStatus.setText("📡 متوقف");
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

    private void showCommitConfirmationDialog() {
        if (!isAdded() || inventoryEngine == null) return;
        
        int scanned = inventoryEngine.getUniqueCount();
        int missing = Math.max(0, expectedCount - scanned);

        String message = String.format(Locale.getDefault(), "📊 ملخص العمليات:\n\n• المتوقع: %d\n• الممسوح: %d\n• العجز: %d\n\nتأكيد الاعتماد التام للعملية؟",
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
        Toast.makeText(requireContext(), "⏳ جاري حفظ الجلسة...", Toast.LENGTH_SHORT).show();
        
        backgroundExecutor.execute(() -> {
            try {
                JSONObject payload = inventoryEngine.buildEvidencePayload();
                int branchId = branchesManager != null ? branchesManager.getActiveBranchId() : 0;
                payload.put("branch_id", branchId);
                
                JSONArray itemsArray = new JSONArray();
                for (ScannedItemDisplay item : scannedItemsDisplayList) {
                    JSONObject itemObj = new JSONObject();
                    itemObj.put("code", item.epc);
                    itemObj.put("serial", item.epc);
                    itemObj.put("rssi", item.rssi);
                    itemsArray.put(itemObj);
                }
                payload.put("items", itemsArray);
                
                MiraApiClient.ApiResponse response = apiClient.closeInventorySession(payload);
                
                mainThreadHandler.post(() -> {
                    if (!isAdded()) return;
                    if (response != null && response.isSuccess) {
                        Toast.makeText(requireContext(), "✅ تم الاعتماد بنجاح", Toast.LENGTH_LONG).show();
                        if (btnGenerateReport != null) btnGenerateReport.setEnabled(false);
                        showCommitSuccessDialog(response);
                    } else {
                        Toast.makeText(requireContext(), "❌ خطأ في نقل البيانات", Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error committing inventory", e);
            }
        });
    }

    private void showCommitSuccessDialog(MiraApiClient.ApiResponse response) {
        if (!isAdded() || response.data == null) return;
        try {
            JSONObject report = response.data.optJSONObject("report");
            if (report == null) report = response.data;
            
            String accuracy = report.optString("accuracy", "0");
            String riskLevel = report.optString("risk_level", "معياري");
            
            String message = String.format(Locale.getDefault(), "✅ نجحت العملية:\n\n• نسبة التوافق: %s%%\n• تقييم المخاطر: %s", accuracy, riskLevel);
            
            new MaterialAlertDialogBuilder(requireContext())
                .setTitle("🎉 تقرير الاعتماد")
                .setMessage(message)
                .setPositiveButton("إغلاق", null)
                .show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing dialog", e);
        }
    }

    private void showArchiveReports() {
        if (!isAdded()) return;
        AlertDialog loading = new MaterialAlertDialogBuilder(requireContext())
                .setTitle("📂 جاري جلب التقرير...")
                .setCancelable(false)
                .show();
        
        backgroundExecutor.execute(() -> {
            List<InventoryReport> reports = fetchArchiveReports();
            mainThreadHandler.post(() -> {
                loading.dismiss();
                if (isAdded()) displayArchiveDialog(reports);
            });
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
                    JSONArray sessions = dataObj != null ? dataObj.optJSONArray("sessions") : response.data.optJSONArray("sessions");
                    
                    if (sessions != null) {
                        for (int i = 0; i < sessions.length(); i++) {
                            JSONObject obj = sessions.getJSONObject(i);
                            reports.add(new InventoryReport(
                                obj.optInt("id", i + 1),
                                obj.optString("client_session_id", "SESS-" + (i + 1)),
                                obj.optString("started_at", ""),
                                0,
                                obj.optInt("unique_items_found", 0),
                                0,
                                "مكتمل"
                            ));
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Archive fetch fail", e);
        }
        return reports;
    }
    
    private void displayArchiveDialog(List<InventoryReport> reports) {
        if (!isAdded() || reports.isEmpty()) {
            Toast.makeText(requireContext(), "الأرشيف فارغ", Toast.LENGTH_SHORT).show();
            return;
        }
        
        String[] titles = new String[reports.size()];
        for (int i = 0; i < reports.size(); i++) {
            titles[i] = "📋 " + reports.get(i).sessionId + " (" + reports.get(i).scannedCount + " قطعة)";
        }
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("📂 الأرشيف")
                .setItems(titles, (d, w) -> showReportDetails(reports.get(w)))
                .setNegativeButton("إلغاء", null)
                .show();
    }
    
    private void showReportDetails(InventoryReport report) {
        if (!isAdded()) return;
        String message = "رقم الجلسة: " + report.sessionId + "\nالمقروء: " + report.scannedCount;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("التفاصيل")
                .setMessage(message)
                .setPositiveButton("إغلاق", null)
                .show();
    }

    private void showSnapshotsDialog() {
        if (!isAdded()) return;
        String[] options = {"📸 استعراض اللقطات", "إلغاء"};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("📸 اللقطات")
                .setItems(options, (d, w) -> {
                    if (w == 0) Toast.makeText(requireContext(), "تحديد اللقطات...", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (isScanning) {
            stopScanning();
        }
    }

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

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        isScanning = false;
        stopSimulationLoop();
        mainThreadHandler.removeCallbacksAndMessages(null);
        
        if (rfidManager != null) {
            try { rfidManager.stopScan(); } catch (Exception ignored) {}
            rfidManager.setListener(null);
        }
        
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
        
        if (backgroundExecutor != null && !backgroundExecutor.isShutdown()) {
            backgroundExecutor.shutdownNow();
        }
    }
    
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
