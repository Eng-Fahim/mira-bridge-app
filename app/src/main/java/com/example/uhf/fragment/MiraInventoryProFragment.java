package com.example.uhf.fragment;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
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
import com.example.uhf.model.InventoryItem;
import com.example.uhf.engine.MiraInventoryEngine;
import com.example.uhf.MiraRfid.RFIDManager;
import com.example.uhf.MiraRfid.MiraIRFIDReader;
import com.example.uhf.MiraRfid.model.RFIDDevice;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.MiraPriceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraInventoryProFragment extends KeyDwonFragment 
        implements MiraIRFIDReader.OnTagReadListener {

    private static final String TAG = "MiraInventoryPro";
    private static final String OFFLINE_PREFS = "inventory_pro_cache";
    private static final String PENDING_QUEUE_PREFS = "inventory_pending_queue";
    private static final int UI_UPDATE_INTERVAL = 250;
    private static final int MAX_DISPLAY_ITEMS = 500;
    private static final long VIBRATION_THROTTLE_MS = 100;
    private static final int SCREENSHOT_QUALITY = 70;
    private static final int SIMULATION_BATCH_SIZE = 5;
    private static final long SIMULATION_MIN_DELAY = 200;
    private static final long SIMULATION_MAX_DELAY = 500;

    // Views
    private TextView tvInventoryStatus, tvExpectedCount, tvScannedCount, tvVariance;
    private TextView tvAccuracy, tvElapsedTime, tvLastScan, tvEmptyState, tvRssi;
    private TextView tvPrevItems, tvCurrItems, tvDeltaItems;
    private TextView tvTotalValue, tvTotalWeight;
    private CardView cardComparison;
    private Button btnStartInventory, btnBlindInventory, btnStopInventory, btnGenerateReport, btnArchiveReports;
    private RecyclerView rvInventoryItems;
    private ProgressBar progressScanned, progressRssi;
    private com.google.android.material.tabs.TabLayout tabInventoryModes;

    // Engine & Processing Threads
    private MiraInventoryEngine inventoryEngine;
    private ExecutorService scannerExecutor;
    private Handler mainThreadHandler;
    private boolean isScanning = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_inventory_pro, container, false);
        initViews(view);

        scannerExecutor = Executors.newSingleThreadExecutor();
        mainThreadHandler = new Handler(Looper.getMainLooper());

        String sessionId = "SESS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String deviceId = android.provider.Settings.Secure.getString(
                requireContext().getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID
        );

        inventoryEngine = new MiraInventoryEngine(requireContext(), sessionId, deviceId);
        
        return view;
    }

    private void initViews(View view) {
        tvExpectedCount = view.findViewById(R.id.tvExpectedCount);
        tvScannedCount = view.findViewById(R.id.tvScannedCount);
        tvVariance = view.findViewById(R.id.tvVariance);
        tvTotalValue = view.findViewById(R.id.tvTotalValue);
        tvTotalWeight = view.findViewById(R.id.tvTotalWeight);
        
        btnStartInventory = view.findViewById(R.id.btnStartInventory);
        btnStopInventory = view.findViewById(R.id.btnStopInventory);

        if (btnStartInventory != null) {
            btnStartInventory.setOnClickListener(v -> startScanning());
        }
        if (btnStopInventory != null) {
            btnStopInventory.setOnClickListener(v -> stopScanning());
        }
    }

    /**
     * استلام القراءات المباشرة من عتاد الممسحة عبر المكون OnTagReadListener
     */
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

    private void updateUI() {
        if (!isAdded()) return;
        if (tvScannedCount != null) {
            tvScannedCount.setText(String.valueOf(inventoryEngine.getUniqueCount()));
        }
    }

    private void startScanning() {
        isScanning = true;
        Toast.makeText(requireContext(), "بدء الجرد الفعلي...", Toast.LENGTH_SHORT).show();
    }

    private void stopScanning() {
        isScanning = false;
        scannerExecutor.execute(() -> {
            inventoryEngine.processSessionClose();
            mainThreadHandler.post(() -> 
                Toast.makeText(requireContext(), "تم إيقاف الجرد وحفظ الحزمة مشفرة", Toast.LENGTH_SHORT).show()
            );
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (scannerExecutor != null && !scannerExecutor.isShutdown()) {
            scannerExecutor.shutdown();
        }
    }
}
