package com.example.uhf.fragment;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MiraInventoryProFragment extends KeyDwonFragment {

    private static final String TAG = "MiraInventoryPro";
    private static final String OFFLINE_PREFS = "inventory_pro_cache";

    private TextView tvInventoryStatus, tvExpectedCount, tvScannedCount, tvVariance;
    private TextView tvAccuracy, tvElapsedTime, tvLastScan, tvRssi;
    private TextView tvPrevItems, tvCurrItems, tvDeltaItems;
    private CardView cardComparison;
    private Button btnStartInventory, btnBlindInventory, btnStopInventory, btnGenerateReport, btnArchiveReports;
    private RecyclerView rvInventoryItems;
    private ProgressBar progressScanned;

    private boolean isScanning = false;
    private boolean isBlindMode = false;
    private boolean isArabic;
    private int expectedCount = 0;
    private int scannedCount = 0;
    private int foundCount = 0;
    private int varianceCount = 0;
    private int prevTotalItems = 0;
    private int sessionId = 0;
    private long startTime = 0;

    private final List<InventoryItem> scannedItems = new ArrayList<>();
    private InventoryAdapter adapter;

    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfile;
    private SharedPreferences offlineCache;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable timerRunnable;
    private final Handler simulationHandler = new Handler(Looper.getMainLooper());
    private Runnable simulationRunnable;
    private int simulationIndex = 0;

    private boolean soundEnabled = true;
    private boolean vibrateEnabled = true;

    private final String[] simulationGtins = {
        "0700000007885", "0700000007886", "0700000007887", "0700000007888", "0700000007889",
        "0700000007890", "0700000007891", "0700000007892", "0700000007893", "0700000007894",
        "0700000007895", "0700000007896", "0700000007897", "0700000007898", "0700000007899",
        "0700000007900", "0700000007901", "0700000007902", "0700000007903", "0700000007904",
        "0700000007905", "0700000007906", "0700000007907", "0700000007908", "0700000007909",
        "0700000007910", "0700000007911", "0700000007912", "0700000007913", "0700000007914",
        "0700000007915", "0700000007916", "0700000007917", "0700000007918", "0700000007919",
        "0700000007920", "0700000007921", "0700000007922", "0700000007923", "0700000007924",
        "0700000007925", "0700000007926", "0700000007927", "0700000007928", "0700000007929",
        "0700000007930", "0700000007931", "0700000007932", "0700000013764", "070000000040"
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_mira_inventory_pro, container, false);
        Context ctx = getContext();
        if (ctx != null) {
            apiClient = MiraApiClient.getInstance(ctx);
            branchesManager = MiraBranchesManager.getInstance(ctx);
            orgProfile = MiraOrgProfileManager.getInstance(ctx);
            offlineCache = ctx.getSharedPreferences(OFFLINE_PREFS, Context.MODE_PRIVATE);
        }
        isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");
        bindViews(v);
        setupRecyclerView();
        setupClickListeners();
        setInitialState();
        return v;
    }

    

    @Override
    public void onResume() {
        super.onResume();
        updateBranchHeader();
        if (!isScanning) {
            loadLastSnapshotOffline();
            loadLastSnapshot();
        }
    }

    private void updateBranchHeader() {
        if (branchesManager != null && tvInventoryStatus != null && !isScanning) {
            String branchName = branchesManager.getActiveBranchDisplayName();
            if (branchName == null || branchName.isEmpty()) {
                branchName = isArabic ? "الفرع الحالي" : "Current Branch";
            }
            tvInventoryStatus.setText("📍 " + branchName);
            tvInventoryStatus.setTextColor(Color.parseColor("#1E293B"));
        }
    }

    private void bindViews(View v) {
        tvInventoryStatus = v.findViewById(R.id.tvInventoryStatus);
        tvExpectedCount = v.findViewById(R.id.tvExpectedCount);
        tvScannedCount = v.findViewById(R.id.tvScannedCount);
        tvVariance = v.findViewById(R.id.tvVariance);
        tvAccuracy = v.findViewById(R.id.tvAccuracy);
        tvElapsedTime = v.findViewById(R.id.tvElapsedTime);
        tvLastScan = v.findViewById(R.id.tvLastScan);
        tvRssi = v.findViewById(R.id.tvRssi);
        tvPrevItems = v.findViewById(R.id.tvPrevItems);
        tvCurrItems = v.findViewById(R.id.tvCurrItems);
        tvDeltaItems = v.findViewById(R.id.tvDeltaItems);
        cardComparison = v.findViewById(R.id.cardComparison);
        btnStartInventory = v.findViewById(R.id.btnStartInventory);
        btnBlindInventory = v.findViewById(R.id.btnBlindInventory);
        btnStopInventory = v.findViewById(R.id.btnStopInventory);
        btnGenerateReport = v.findViewById(R.id.btnGenerateReport);
        btnArchiveReports = v.findViewById(R.id.btnArchiveReports);
        rvInventoryItems = v.findViewById(R.id.rvInventoryItems);
        progressScanned = v.findViewById(R.id.progressScanned);
        applyLanguage();
    }

    private void applyLanguage() {
        if (btnStartInventory != null) btnStartInventory.setText(isArabic ? "بدء الجرد" : "Start Audit");
        if (btnBlindInventory != null) btnBlindInventory.setText(isArabic ? "جرد أعمى" : "Blind Audit");
        if (btnStopInventory != null) btnStopInventory.setText(isArabic ? "إيقاف" : "Stop");
        if (btnGenerateReport != null) btnGenerateReport.setText(isArabic ? "تقرير" : "Report");
        if (btnArchiveReports != null) btnArchiveReports.setText(isArabic ? "الأرشيف" : "Archive");
    }

    private void setupRecyclerView() {
        adapter = new InventoryAdapter(scannedItems);
        if (rvInventoryItems != null) {
            rvInventoryItems.setLayoutManager(new LinearLayoutManager(getContext()));
            rvInventoryItems.setAdapter(adapter);
        }
    }

    private void setupClickListeners() {
        if (btnStartInventory != null) btnStartInventory.setOnClickListener(v -> startInventory(false));
        if (btnBlindInventory != null) btnBlindInventory.setOnClickListener(v -> startInventory(true));
        if (btnStopInventory != null) btnStopInventory.setOnClickListener(v -> stopInventory());
        if (btnGenerateReport != null) btnGenerateReport.setOnClickListener(v -> generateProReport());
        if (btnArchiveReports != null) btnArchiveReports.setOnClickListener(v -> showAuditHistoryDialog());
    }

    private void setInitialState() {
        if (btnStartInventory != null) btnStartInventory.setEnabled(true);
        if (btnBlindInventory != null) btnBlindInventory.setEnabled(true);
        if (btnStopInventory != null) btnStopInventory.setEnabled(false);
        if (btnGenerateReport != null) btnGenerateReport.setEnabled(false);
        expectedCount = simulationGtins.length;
        if (tvExpectedCount != null) tvExpectedCount.setText(String.valueOf(expectedCount));
        if (cardComparison != null) cardComparison.setVisibility(View.VISIBLE);
    }

    // ==================== OFFLINE SNAPSHOT ====================

    private void loadLastSnapshotOffline() {
        try {
            prevTotalItems = offlineCache.getInt("prev_total_items", 0);
            if (tvPrevItems != null) tvPrevItems.setText(String.valueOf(prevTotalItems));
            updateComparisonCard();
        } catch (Exception ignored) {}
    }

    private void saveSnapshotOffline(JSONObject data) {
        try {
            int total = data.optInt("total_items", data.optInt("found_count", 0));
            offlineCache.edit()
                .putInt("prev_total_items", total)
                .putLong("snapshot_time", System.currentTimeMillis())
                .apply();
        } catch (Exception ignored) {}
    }

    private void loadLastSnapshot() {
        if (apiClient == null || branchesManager == null) return;
        final int activeBranchId = branchesManager.getActiveBranchId();
        final String branchDisplayName = branchesManager.getActiveBranchDisplayName();

        new Thread(() -> {
            int loadedPrevItems = 0;
            try {
                MiraApiClient.ApiResponse response = apiClient.getLastSnapshot(branchDisplayName + " | branch:" + activeBranchId);
                if (response != null && response.isSuccess && response.data != null) {
                    JSONObject snapshotObj = response.data;
                    if (snapshotObj.has("snapshot")) snapshotObj = snapshotObj.optJSONObject("snapshot");
                    if (snapshotObj != null) {
                        loadedPrevItems = snapshotObj.optInt("total_items", snapshotObj.optInt("found_count", 0));
                        // ✅ حفظ محلي
                        saveSnapshotOffline(snapshotObj);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading snapshot: " + e.getMessage());
            }

            final int finalItems = loadedPrevItems;
            safeRun(() -> {
                prevTotalItems = finalItems;
                if (tvPrevItems != null) tvPrevItems.setText(String.valueOf(prevTotalItems));
                updateComparisonCard();
            });
        }).start();
    }

    // ==================== OFFLINE SESSION ====================

    private void saveSessionOffline(int found, int expected, int variance) {
        offlineCache.edit()
            .putInt("last_found", found)
            .putInt("last_expected", expected)
            .putInt("last_variance", variance)
            .putLong("last_session_time", System.currentTimeMillis())
            .apply();
    }

    // ==================== Tag Processing ====================

    public void processScannedTag(String epcOrGtin, String rssi) {
        if (!isScanning || apiClient == null || branchesManager == null) return;

        for (InventoryItem item : scannedItems) {
            if (item.serial.equalsIgnoreCase(epcOrGtin)) return;
        }

        final int activeBranchId = branchesManager.getActiveBranchId();
        final String branchDisplayName = branchesManager.getActiveBranchDisplayName();

        new Thread(() -> {
            try {
                JSONObject extraParams = new JSONObject();
                if (rssi != null) extraParams.put("rssi", rssi);
                extraParams.put("branch_id", activeBranchId);

                MiraApiClient.ApiResponse response = apiClient.authorize(epcOrGtin, "inventory", extraParams);

                String title = isArabic ? "قطعة غير معرفة" : "Unknown Item";
                String karat = "21K";
                String weight = "0.00g";
                String location = branchDisplayName;
                String status = "⚠️";
                boolean found = false;

                if (response != null && response.isSuccess && response.data != null) {
                    JSONObject item = response.data.optJSONObject("item");
                    if (item != null) {
                        title = item.optString("title", item.optString("name", title));
                        karat = item.optString("karat", "21K");
                        double w = item.optDouble("weight", 0);
                        weight = String.format(Locale.US, "%.2fg", w);
                        location = item.optString("location", branchDisplayName);
                        String vaultStatus = item.optString("status", "");
                        found = !"sold".equalsIgnoreCase(vaultStatus);
                        status = found ? "✓" : "⚠️";
                    }
                }

                final String fTitle = title, fKarat = karat, fWeight = weight;
                final String fLocation = location, fStatus = status;
                final boolean fFound = found;

                safeRun(() -> {
                    scannedItems.add(0, new InventoryItem(epcOrGtin, fTitle, fKarat, fWeight, fStatus, fLocation));
                    playInventorySound("scan");
                    scannedCount++;
                    if (fFound) foundCount++;

                    if (adapter != null) adapter.notifyItemInserted(0);
                    if (rvInventoryItems != null) rvInventoryItems.scrollToPosition(0);

                    if (tvScannedCount != null) tvScannedCount.setText(String.valueOf(scannedCount));
                    if (tvVariance != null) tvVariance.setText(String.valueOf(Math.max(0, expectedCount - foundCount)));
                    if (tvLastScan != null) tvLastScan.setText(epcOrGtin.substring(0, Math.min(14, epcOrGtin.length())));
                    if (tvRssi != null && rssi != null) tvRssi.setText(rssi);

                    if (progressScanned != null && expectedCount > 0) {
                        progressScanned.setProgress(Math.min(100, (int) ((scannedCount * 100.0) / expectedCount)));
                    }

                    double acc = expectedCount > 0 ? (foundCount * 100.0 / expectedCount) : 100.0;
                    if (tvAccuracy != null) {
                        tvAccuracy.setText(String.format(Locale.US, isArabic ? "الدقة: %.1f%%" : "Accuracy: %.1f%%", acc));
                    }

                    updateComparisonCard();
                });
            } catch (Exception e) {
                Log.e(TAG, "Error processing scanned tag: " + e.getMessage());
            }
        }).start();
    }

    private void startSessionOnServer(boolean blind) {
    if (apiClient == null || branchesManager == null) return;
    final int activeBranchId = branchesManager.getActiveBranchId();
    final String branchDisplayName = branchesManager.getActiveBranchDisplayName();

    new Thread(() -> {
        try {
            // ✅ جلب قائمة القطع مباشرة
            MiraApiClient.ApiResponse response = apiClient.getBranchItemsList(activeBranchId);
            
            if (response != null && response.isSuccess && response.data != null) {
                JSONArray items = response.data.optJSONArray("items");
                if (items != null && items.length() > 0) {
                    expectedCount = items.length();
                    saveRealGtinsForSimulation(items);
                    Log.i(TAG, "✅ Loaded " + items.length() + " items for inventory");
                } else {
                    // جرب المخزون العادي
                    loadInventoryFallback(activeBranchId);
                }
            } else {
                loadInventoryFallback(activeBranchId);
            }
        } catch (Exception e) {
            Log.e(TAG, "Session start error: " + e.getMessage());
            loadOfflineGtinsForSimulation();
        }

        safeRun(() -> {
            if (expectedCount == 0) expectedCount = realGtinsForSimulation.length > 0 ? realGtinsForSimulation.length : simulationGtins.length;
            if (tvExpectedCount != null) tvExpectedCount.setText(blind ? "???" : String.valueOf(expectedCount));
            startSimulation();
        });
    }).start();
}

private void loadInventoryFallback(int branchId) {
    try {
        MiraApiClient.ApiResponse response = apiClient.getBranchInventory(branchId);
        if (response != null && response.isSuccess && response.data != null) {
            JSONArray items = response.data.optJSONArray("items");
            if (items == null) items = response.data.optJSONArray("inventory");
            if (items != null && items.length() > 0) {
                expectedCount = items.length();
                saveRealGtinsForSimulation(items);
            }
        }
    } catch (Exception e) {
        loadOfflineGtinsForSimulation();
    }
}

    private void closeSessionOnServer() {
        if (apiClient == null || branchesManager == null) return;
        final int activeBranchId = branchesManager.getActiveBranchId();

        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("session_id", sessionId);
                payload.put("found_count", foundCount);
                payload.put("expected_count", expectedCount);
                payload.put("variance", varianceCount);
                payload.put("branch_id", activeBranchId);
                apiClient.closeInventorySession(payload);
            } catch (Exception ignored) {}
        }).start();
        
        // ✅ حفظ محلي
        saveSessionOffline(foundCount, expectedCount, varianceCount);
    }

// ==================== REAL GTIN SIMULATION ====================

private String[] realGtinsForSimulation = new String[0];

/**
 * حفظ أرقام GTIN الحقيقية من API للاستخدام في المحاكاة
 */
private void saveRealGtinsForSimulation(JSONArray items) {
    try {
        List<String> gtins = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            String gtin = item.optString("gtin13", item.optString("epc", ""));
            if (!gtin.isEmpty()) {
                gtins.add(gtin);
            }
        }
        
        if (!gtins.isEmpty()) {
            realGtinsForSimulation = gtins.toArray(new String[0]);
            
            // ✅ حفظ محلي للاستخدام أوفلاين
            JSONArray arr = new JSONArray();
            for (String gtin : gtins) {
                arr.put(gtin);
            }
            if (offlineCache != null) {
                offlineCache.edit()
                    .putString("real_gtins", arr.toString())
                    .putInt("real_gtins_count", gtins.size())
                    .apply();
            }
            
            Log.i(TAG, "✅ Loaded " + gtins.size() + " real GTINs for simulation");
        }
    } catch (Exception e) {
        Log.e(TAG, "Save GTINs error: " + e.getMessage());
    }
}

/**
 * تحميل أرقام GTIN المخزنة محلياً
 */
private void loadOfflineGtinsForSimulation() {
    try {
        if (offlineCache == null) return;
        String json = offlineCache.getString("real_gtins", "");
        if (!json.isEmpty()) {
            JSONArray arr = new JSONArray(json);
            List<String> gtins = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                String gtin = arr.optString(i, "");
                if (!gtin.isEmpty()) {
                    gtins.add(gtin);
                }
            }
            if (!gtins.isEmpty()) {
                realGtinsForSimulation = gtins.toArray(new String[0]);
                expectedCount = realGtinsForSimulation.length;
                Log.i(TAG, "📱 Loaded " + gtins.size() + " GTINs from offline storage");
            }
        }
    } catch (Exception e) {
        Log.e(TAG, "Load offline GTINs error: " + e.getMessage());
    }
}

    private void createSnapshotOnServer() {
        if (scannedItems.isEmpty() || foundCount == 0 || apiClient == null || branchesManager == null) return;
        final int activeBranchId = branchesManager.getActiveBranchId();
        final String branchDisplayName = branchesManager.getActiveBranchDisplayName();

        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("session_id", sessionId);
                payload.put("branch_id", activeBranchId);
                payload.put("location", branchDisplayName);
                payload.put("total_items", foundCount);
                payload.put("total_weight", calculateTotalWeight());
                payload.put("total_value", calculateTotalValue());
                
                JSONArray itemsJson = new JSONArray();
                for (InventoryItem item : scannedItems) {
                    JSONObject obj = new JSONObject();
                    obj.put("serial", item.serial); 
                    obj.put("title", item.title);
                    obj.put("karat", item.karat); 
                    obj.put("weight", item.weight);
                    obj.put("status", item.status); 
                    obj.put("location", item.location);
                    itemsJson.put(obj);
                }
                payload.put("items", itemsJson);
                
                // ✅ حفظ محلي
                try {
                    offlineCache.edit()
                        .putString("last_snapshot", payload.toString())
                        .putInt("prev_total_items", foundCount)
                        .putLong("snapshot_time", System.currentTimeMillis())
                        .apply();
                } catch (Exception ignored) {}
                
                apiClient.createInventorySnapshot(payload);
            } catch (Exception e) {
                Log.e(TAG, "Failed to create snapshot: " + e.getMessage());
            }
        }).start();
    }

    private void startSimulation() {
    simulationIndex = 0;
    
    // ✅ استخدام الأرقام الحقيقية إذا كانت متاحة
    final String[] gtinsToUse = realGtinsForSimulation.length > 0 
        ? realGtinsForSimulation 
        : simulationGtins;
    
    final int totalGtins = gtinsToUse.length;
    expectedCount = totalGtins;
    
    if (tvExpectedCount != null) {
        tvExpectedCount.setText(isBlindMode ? "???" : String.valueOf(totalGtins));
    }
    
    simulationRunnable = () -> {
        if (!isScanning || simulationIndex >= totalGtins) {
            if (isScanning) stopInventory();
            return;
        }
        processScannedTag(gtinsToUse[simulationIndex], "-" + (40 + (int)(Math.random() * 25)));
        simulationIndex++;
        simulationHandler.postDelayed(simulationRunnable, 500);
    };
    simulationHandler.postDelayed(simulationRunnable, 300);
}

@Override
public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    loadInventorySettings();
    updateBranchHeader();
    loadLastSnapshotOffline();
    loadLastSnapshot();
    loadOfflineGtinsForSimulation();  // ✅ تحميل الأرقام المحلية فوراً
}

    private double calculateTotalWeight() {
        double total = 0;
        for (InventoryItem item : scannedItems) {
            try {
                String w = item.weight.replace("g", "").replace("غرام", "").trim();
                total += Double.parseDouble(w);
            } catch (Exception ignored) {}
        }
        return Math.round(total * 1000.0) / 1000.0;
    }

    private double calculateTotalValue() {
        return Math.round(calculateTotalWeight() * 65 * 100.0) / 100.0;
    }

    private void startInventory(boolean blindMode) {
        isBlindMode = blindMode;
        isScanning = true;
        startTime = System.currentTimeMillis();
        scannedItems.clear();
        scannedCount = 0;
        foundCount = 0;
        if (adapter != null) adapter.notifyDataSetChanged();

        if (btnStartInventory != null) btnStartInventory.setEnabled(false);
        if (btnBlindInventory != null) btnBlindInventory.setEnabled(false);
        if (btnStopInventory != null) btnStopInventory.setEnabled(true);
        if (btnGenerateReport != null) btnGenerateReport.setEnabled(false);
        if (cardComparison != null) cardComparison.setVisibility(View.VISIBLE);

        updateComparisonCard();

        if (tvInventoryStatus != null) {
            tvInventoryStatus.setText(isArabic ? "● جاري الجرد" : "● SCANNING");
            tvInventoryStatus.setTextColor(Color.parseColor("#38BDF8"));
        }

        playInventorySound("start");
        startSessionOnServer(blindMode);
        startTimer();
    }

    private void stopInventory() {
        isScanning = false;
        if (simulationRunnable != null) simulationHandler.removeCallbacks(simulationRunnable);
        playInventorySound("complete");

        if (btnStartInventory != null) btnStartInventory.setEnabled(true);
        if (btnBlindInventory != null) btnBlindInventory.setEnabled(true);
        if (btnStopInventory != null) btnStopInventory.setEnabled(false);
        if (btnGenerateReport != null) btnGenerateReport.setEnabled(true);

        if (tvInventoryStatus != null) {
            tvInventoryStatus.setText(isArabic ? "● مكتمل" : "● COMPLETED");
            tvInventoryStatus.setTextColor(Color.parseColor("#22C55E"));
        }

        stopTimer();
        closeSessionOnServer();
        varianceCount = Math.max(0, expectedCount - foundCount);
        updateComparisonCard();
        createSnapshotOnServer();
    }

    private void updateComparisonCard() {
        if (cardComparison == null) return;
        cardComparison.setVisibility(View.VISIBLE);
        if (tvPrevItems != null) tvPrevItems.setText(String.valueOf(prevTotalItems));
        if (tvCurrItems != null) tvCurrItems.setText(String.valueOf(foundCount));
        int delta = foundCount - prevTotalItems;
        if (tvDeltaItems != null) {
            tvDeltaItems.setText((delta >= 0 ? "+" : "") + delta);
            tvDeltaItems.setTextColor(delta >= 0 ? Color.parseColor("#22C55E") : Color.parseColor("#EF4444"));
        }
    }

    private void startTimer() {
        timerRunnable = () -> {
            if (!isAdded()) return;
            long e = System.currentTimeMillis() - startTime;
            if (tvElapsedTime != null) {
                tvElapsedTime.setText(String.format(Locale.US, "%02d:%02d:%02d", e / 3600000, (e / 60000) % 60, (e / 1000) % 60));
            }
            handler.postDelayed(timerRunnable, 1000);
        };
        handler.post(timerRunnable);
    }

    private void stopTimer() {
        if (timerRunnable != null) handler.removeCallbacks(timerRunnable);
    }

    private void playInventorySound(String type) {
        if (!soundEnabled || getContext() == null) return;
        if (vibrateEnabled && "scan".equals(type)) {
            try {
                android.os.Vibrator vib = (android.os.Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
                if (vib != null && vib.hasVibrator()) vib.vibrate(40);
            } catch (Exception ignored) {}
        }
    }

    private void loadInventorySettings() {
        if (getContext() == null) return;
        android.content.SharedPreferences p = getContext().getSharedPreferences("MIRA_BRIDGE_SETTINGS", Context.MODE_PRIVATE);
        soundEnabled = "true".equals(p.getString("inventory_sound", "true"));
        vibrateEnabled = "true".equals(p.getString("inventory_vibrate", "true"));
    }

    // ==================== Report ====================
    // (نفس كود generateProReport السابق - لم يتغير)

    private void generateProReport() {
        if (getContext() == null || !isAdded()) return;

        String orgName = orgProfile != null ? orgProfile.getOrgName() : (isArabic ? "مؤسسة المجوهرات" : "Jewelry Enterprise");
        String orgPhone = orgProfile != null ? orgProfile.getOrgPhone() : "";
        String orgAddress = orgProfile != null ? orgProfile.getOrgAddress() : (isArabic ? "اليمن" : "Yemen");
        String activeBranchName = branchesManager != null ? branchesManager.getActiveBranchDisplayName() : "";

        double totalWt = calculateTotalWeight();
        double totalVal = calculateTotalValue();
        int dailySalesCount = 3;
        int adjustedExpected = Math.max(0, prevTotalItems - dailySalesCount);
        int netVariance = foundCount - adjustedExpected;

        StringBuilder rows = new StringBuilder();
        int count = 1;
        for (int i = scannedItems.size() - 1; i >= 0; i--) {
            InventoryItem item = scannedItems.get(i);
            String color = "✓".equals(item.status) ? "#22C55E" : "⚠️".equals(item.status) ? "#F59E0B" : "#EF4444";
            rows.append(String.format(Locale.US,
                "<tr><td>%d</td><td style='color:%s;'>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>",
                count++, color, item.status, item.serial, item.title, item.karat, item.weight));
        }

        double accVal = adjustedExpected > 0 ? (foundCount * 100.0 / adjustedExpected) : 100.0;
        String accColor = accVal >= 98 ? "#22C55E" : accVal >= 90 ? "#F59E0B" : "#EF4444";
        String dir = isArabic ? "rtl" : "ltr";

        String html = "<!DOCTYPE html><html dir='" + dir + "'><head><meta charset='UTF-8'><style>" +
            "body{font-family:sans-serif;color:#111827;padding:20px;direction:" + dir + ";background:#fff;font-size:11px}" +
            ".header{text-align:center;border-bottom:2px solid #D4AF37;padding-bottom:10px;margin-bottom:12px}" +
            ".header h2{color:#1A237E;margin:0;font-size:18px}" +
            ".header p{color:#666;font-size:10px;margin:2px 0}" +
            ".summary{display:flex;justify-content:space-between;margin:12px 0;gap:6px}" +
            ".card{flex:1;text-align:center;padding:8px;background:#F9FAFB;border-radius:6px}" +
            ".card .val{font-size:16px;font-weight:700}" +
            ".card .lbl{font-size:9px;color:#666}" +
            "table{width:100%;border-collapse:collapse;margin-top:10px;font-size:10px}" +
            "th{background:#1A237E;color:#fff;padding:6px}td{padding:5px;border-bottom:1px solid #eee;text-align:center}" +
            ".footer{text-align:center;margin-top:16px;font-size:9px;color:#999}" +
            "</style></head><body>" +
            "<div class='header'><h2>" + orgName + " - " + activeBranchName + "</h2><p>" + orgAddress + " | " + orgPhone + "</p></div>" +
            "<div style='text-align:center;font-weight:bold;margin:8px 0;color:#92400E'>" +
            (isArabic ? "تقرير تدقيق المخزون" : "Inventory Audit Report") + " | " +
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()) + "</div>" +
            "<div class='summary'>" +
            "<div class='card'><div class='val'>" + adjustedExpected + "</div><div class='lbl'>" + (isArabic ? "المتوقع" : "Expected") + "</div></div>" +
            "<div class='card'><div class='val' style='color:#22C55E'>" + foundCount + "</div><div class='lbl'>" + (isArabic ? "تم التحقق" : "Verified") + "</div></div>" +
            "<div class='card'><div class='val' style='color:" + (netVariance >= 0 ? "#22C55E" : "#EF4444") + "'>" + (netVariance >= 0 ? "+" : "") + netVariance + "</div><div class='lbl'>" + (isArabic ? "التفاوت" : "Variance") + "</div></div>" +
            "<div class='card'><div class='val' style='color:" + accColor + "'>" + String.format(Locale.US, "%.1f%%", accVal) + "</div><div class='lbl'>" + (isArabic ? "الدقة" : "Accuracy") + "</div></div>" +
            "</div>" +
            "<table><tr><th>#</th><th>" + (isArabic ? "حالة" : "St") + "</th><th>" + (isArabic ? "الرقم" : "Serial") + "</th><th>" + (isArabic ? "القطعة" : "Item") + "</th><th>" + (isArabic ? "عيار" : "K") + "</th><th>" + (isArabic ? "وزن" : "Wt") + "</th></tr>" +
            rows.toString() + "</table>" +
            "<div style='display:flex;justify-content:space-between;margin-top:12px;font-weight:bold'>" +
            "<div>" + (isArabic ? "الوزن: " : "Weight: ") + String.format(Locale.US, "%.2fg", totalWt) + "</div>" +
            "<div>" + (isArabic ? "القيمة: " : "Value: ") + String.format(Locale.US, "%,.0f YER", totalVal) + "</div></div>" +
            "<div class='footer'>MIRA Inventory Pro | " + orgName + " © 2026</div>" +
            "</body></html>";

        WebView webView = new WebView(requireContext());
        webView.getSettings().setJavaScriptEnabled(true);
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (getContext() == null || !isAdded()) return;
                PrintManager pm = (PrintManager) requireContext().getSystemService(Context.PRINT_SERVICE);
                if (pm != null) {
                    PrintDocumentAdapter pda = view.createPrintDocumentAdapter("MIRA_Inventory_Report_" + activeBranchName);
                    pm.print("MIRA Inventory Report", pda, new PrintAttributes.Builder().build());
                }
            }
        });
    }

    private void showAuditHistoryDialog() {
        if (getContext() == null || apiClient == null || branchesManager == null) return;
        Toast.makeText(getContext(), isArabic ? "🔄 جاري التحميل..." : "🔄 Loading...", Toast.LENGTH_SHORT).show();

        final int activeBranchId = branchesManager.getActiveBranchId();

        new Thread(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getInventorySessionsList();
                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray sessionsArr = response.data.optJSONArray("sessions");
                    if (sessionsArr == null || sessionsArr.length() == 0) {
                        safeRun(() -> Toast.makeText(getContext(), isArabic ? "لا توجد جلسات سابقة" : "No previous sessions", Toast.LENGTH_SHORT).show());
                        return;
                    }

                    List<String> titleList = new ArrayList<>();
                    List<Integer> idList = new ArrayList<>();

                    for (int i = 0; i < sessionsArr.length(); i++) {
                        JSONObject s = sessionsArr.optJSONObject(i);
                        if (s != null) {
                            int id = s.optInt("id");
                            idList.add(id);
                            titleList.add((isArabic ? "جلسة #" : "Session #") + id + " - " + s.optString("created_at"));
                        }
                    }

                    if (titleList.isEmpty()) {
                        safeRun(() -> Toast.makeText(getContext(), isArabic ? "لا توجد جلسات سابقة" : "No previous sessions", Toast.LENGTH_SHORT).show());
                        return;
                    }

                    final String[] titles = titleList.toArray(new String[0]);
                    final int[] ids = idList.stream().mapToInt(Integer::intValue).toArray();

                    safeRun(() -> {
                        if (!isAdded()) return;
                        new AlertDialog.Builder(getContext())
                            .setTitle(isArabic ? "📂 أرشيف الجرد" : "📂 Audit Archive")
                            .setItems(titles, (d, w) -> loadAndPrintHistoricalReport(ids[w]))
                            .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                            .show();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "History error: " + e.getMessage());
            }
        }).start();
    }

    private void loadAndPrintHistoricalReport(int targetSessionId) {
        if (apiClient == null || getContext() == null) return;
        Toast.makeText(getContext(), isArabic ? "🔄 جاري التحميل..." : "🔄 Loading...", Toast.LENGTH_SHORT).show();
    }

    private void safeRun(Runnable r) {
        if (isAdded() && getActivity() != null) getActivity().runOnUiThread(() -> { if (isAdded()) r.run(); });
    }

    @Override
    public void myOnKeyDwon() {
        if (!isScanning) startInventory(false);
        else stopInventory();
    }

    public static class InventoryItem {
        String serial, title, karat, weight, status, location;
        public InventoryItem(String s, String t, String k, String w, String st, String l) {
            serial = s; title = t; karat = k; weight = w; status = st; location = l;
        }
    }

    private class InventoryAdapter extends RecyclerView.Adapter<InventoryAdapter.VH> {
        private final List<InventoryItem> items;
        InventoryAdapter(List<InventoryItem> i) { this.items = i; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_inventory, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            InventoryItem item = items.get(pos);
            if (h.s != null) {
                h.s.setText(item.status);
                h.s.setTextColor("✓".equals(item.status) ? Color.parseColor("#22C55E") :
                    "⚠️".equals(item.status) ? Color.parseColor("#F59E0B") : Color.parseColor("#EF4444"));
            }
            if (h.serial != null) h.serial.setText(item.serial);
            if (h.title != null) h.title.setText(item.title);
            if (h.details != null) h.details.setText(item.karat + " | " + item.weight);
            if (h.location != null) h.location.setText(item.location);
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView s, serial, title, details, location;
            VH(View v) {
                super(v);
                s = v.findViewById(R.id.tvItemStatus);
                serial = v.findViewById(R.id.tvItemSerial);
                title = v.findViewById(R.id.tvItemTitle);
                details = v.findViewById(R.id.tvItemDetails);
                location = v.findViewById(R.id.tvItemLocation);
            }
        }
    }

    @Override
    public void onDestroyView() {
        isScanning = false;
        stopTimer();
        if (simulationRunnable != null) simulationHandler.removeCallbacks(simulationRunnable);
        handler.removeCallbacksAndMessages(null);
        simulationHandler.removeCallbacksAndMessages(null);
        if (rvInventoryItems != null) rvInventoryItems.setAdapter(null);
        super.onDestroyView();
    }
                                                      }
