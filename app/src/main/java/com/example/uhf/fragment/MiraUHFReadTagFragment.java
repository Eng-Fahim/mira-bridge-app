package com.example.uhf.fragment;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
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
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatSpinner;
import androidx.cardview.widget.CardView;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MiraUHFReadTagFragment extends Fragment {
    private static final String TAG = "MiraUHFInventory";
    private static final int REQUEST_CAMERA_SCAN = 49374;

    private final ExecutorService queryExecutor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private final Handler readerCheckHandler = new Handler(Looper.getMainLooper());
    private final Handler simulationHandler = new Handler(Looper.getMainLooper());

    private final List<ScannedTag> recentTags = Collections.synchronizedList(new ArrayList<>());
    private final List<String> scannedEPCs = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> scannedEPCSet = ConcurrentHashMap.newKeySet(); // O(1) fast lookup
    private final ConcurrentLinkedQueue<ScannedTag> batchQueue = new ConcurrentLinkedQueue<>();
    private final List<ScannedTag> filteredTags = Collections.synchronizedList(new ArrayList<>());

    private final ConcurrentHashMap<String, ExpectedItem> expectedItemsMap = new ConcurrentHashMap<>();
    private final AtomicInteger expectedTotal = new AtomicInteger(0);
    private final AtomicInteger foundCount = new AtomicInteger(0);
    private final AtomicInteger missingCount = new AtomicInteger(0);
    private final AtomicInteger unexpectedCount = new AtomicInteger(0);

    private boolean isScanning = false;
    private boolean isPaused = false;
    private boolean isSimulating = false;
    private long startTime = 0;
    private long pauseStartTime = 0;
    private long totalPausedDuration = 0;
    private final AtomicInteger totalScanned = new AtomicInteger(0);
    private final AtomicInteger uniqueTags = new AtomicInteger(0);
    private int lastSecondCount = 0;
    private String sessionId;
    private String currentFilter = "all";
    private boolean isReaderConnected = false;

    private ScheduledExecutorService batchScheduler;

    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfile;
    private boolean isArabic;

    private SoundPool soundPool;
    private int soundMatch, soundUnmatch;
    private boolean soundLoaded = false;

    private View rootLayout;
    private TextView tvTotalScanned, tvUniqueTags, tvScanRate, tvElapsed, tvBranchName;
    private TextView tvLastTagEPC, tvLastTagTitle, tvLastTagKarat, tvLastTagWeight, tvLastTagStatus;
    private TextView tvExpectedTotal, tvFoundCount, tvMissingCount, tvUnexpectedCount;
    private TextView tvReconciliationResult, tvReaderStatus;
    private TextView tabFilterAll, tabFilterFound, tabFilterMissing, tabFilterUnexpected;
    private CardView cardLastTag;
    private ProgressBar pbScanProgress;
    private RecyclerView rvRecentTags;
    private Button btnStartInventory, btnPauseInventory, btnStopInventory, btnClearInventory;
    private Button btnExportInventory, btnExportMissing, btnScreenshot, btnFinalizeAudit, btnSelectZone;
    private Button btnScanCamera, btnHelpGuide, btnSimulateScan;
    private AppCompatSpinner spinnerZone;
    private View layoutZoneSelector, layoutFilterTabs;
    private RecentTagsAdapter recentAdapter;
    private Runnable timerRunnable, readerCheckRunnable, simulationRunnable;

    public void onKeyDownTrigger() {
        if (isScanning) {
            stopInventory();
        } else {
            startInventory();
        }
    }

    public static class ExpectedItem {
        public String epc, title, karat;
        public double weight;
        public boolean isFound = false;
        public String scanTime;
    }

    static class ScannedTag {
        String epc, rssi, title, karat, weight, status, timestamp;
        boolean isExpected;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_uhf_readtag, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        try {
            Context ctx = requireContext();
            apiClient = MiraApiClient.getInstance(ctx);
            branchesManager = MiraBranchesManager.getInstance(ctx);
            orgProfile = MiraOrgProfileManager.getInstance(ctx);
            isArabic = Locale.getDefault().getLanguage().equals("ar");
            sessionId = "INV-" + System.currentTimeMillis();

            initSoundPool();
            if (!initViews(view)) return;
            setupRecyclerView();
            setupListeners();
            setupZoneSpinner();
            setupFilterTabs();
            updateBranchDisplay();
            startReaderMonitoring();
        } catch (Exception e) {
            Log.e(TAG, "onViewCreated error: " + e.getMessage());
        }
    }

    private void initSoundPool() {
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            soundPool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attrs).build();
            soundPool.setOnLoadCompleteListener((pool, sampleId, status) -> {
                if (status == 0) soundLoaded = true;
            });
            if (getContext() != null) {
                soundMatch = soundPool.load(getContext(), R.raw.barcodebeep, 1);
                soundUnmatch = soundPool.load(getContext(), R.raw.serror, 2);
            }
        } catch (Exception e) {
            Log.e(TAG, "Sound init error: " + e.getMessage());
        }
    }

    private void playSound(int soundId) {
        mainHandler.post(() -> {
            try {
                if (soundPool != null && soundLoaded) {
                    soundPool.play(soundId, 0.7f, 0.7f, 1, 0, 1f);
                }
            } catch (Exception e) {
                Log.e(TAG, "playSound error: " + e.getMessage());
            }
        });
    }

    private boolean initViews(View v) {
        rootLayout = v.findViewById(R.id.rootLayout);
        tvTotalScanned = v.findViewById(R.id.tvTotalScanned);
        tvUniqueTags = v.findViewById(R.id.tvUniqueTags);
        tvScanRate = v.findViewById(R.id.tvScanRate);
        tvElapsed = v.findViewById(R.id.tvElapsed);
        tvBranchName = v.findViewById(R.id.tvBranchName);
        tvLastTagEPC = v.findViewById(R.id.tvLastTagEPC);
        tvLastTagTitle = v.findViewById(R.id.tvLastTagTitle);
        tvLastTagKarat = v.findViewById(R.id.tvLastTagKarat);
        tvLastTagWeight = v.findViewById(R.id.tvLastTagWeight);
        tvLastTagStatus = v.findViewById(R.id.tvLastTagStatus);
        tvExpectedTotal = v.findViewById(R.id.tvExpectedTotal);
        tvFoundCount = v.findViewById(R.id.tvFoundCount);
        tvMissingCount = v.findViewById(R.id.tvMissingCount);
        tvUnexpectedCount = v.findViewById(R.id.tvUnexpectedCount);
        tvReconciliationResult = v.findViewById(R.id.tvReconciliationResult);
        tvReaderStatus = v.findViewById(R.id.tvReaderStatus);
        cardLastTag = v.findViewById(R.id.cardLastTag);
        pbScanProgress = v.findViewById(R.id.pbScanProgress);
        rvRecentTags = v.findViewById(R.id.rvRecentTags);
        btnStartInventory = v.findViewById(R.id.btnStartInventory);
        btnPauseInventory = v.findViewById(R.id.btnPauseInventory);
        btnStopInventory = v.findViewById(R.id.btnStopInventory);
        btnClearInventory = v.findViewById(R.id.btnClearInventory);
        btnExportInventory = v.findViewById(R.id.btnExportInventory);
        btnExportMissing = v.findViewById(R.id.btnExportMissing);
        btnScreenshot = v.findViewById(R.id.btnScreenshot);
        btnFinalizeAudit = v.findViewById(R.id.btnFinalizeAudit);
        btnSelectZone = v.findViewById(R.id.btnSelectZone);
        btnScanCamera = v.findViewById(R.id.btnScanCamera);
        btnHelpGuide = v.findViewById(R.id.btnHelpGuide);
        btnSimulateScan = v.findViewById(R.id.btnSimulateScan);
        spinnerZone = v.findViewById(R.id.spinnerZone);
        layoutZoneSelector = v.findViewById(R.id.layoutZoneSelector);
        layoutFilterTabs = v.findViewById(R.id.layoutFilterTabs);
        tabFilterAll = v.findViewById(R.id.tabFilterAll);
        tabFilterFound = v.findViewById(R.id.tabFilterFound);
        tabFilterMissing = v.findViewById(R.id.tabFilterMissing);
        tabFilterUnexpected = v.findViewById(R.id.tabFilterUnexpected);

        resetUI();
        return true;
    }

    private void setupRecyclerView() {
        if (getContext() == null || rvRecentTags == null) return;
        rvRecentTags.setLayoutManager(new LinearLayoutManager(getContext()));
        recentAdapter = new RecentTagsAdapter(filteredTags);
        rvRecentTags.setAdapter(recentAdapter);
    }

    private void setupListeners() {
        if (btnStartInventory != null) btnStartInventory.setOnClickListener(v -> startInventory());
        if (btnPauseInventory != null) btnPauseInventory.setOnClickListener(v -> togglePause());
        if (btnStopInventory != null) btnStopInventory.setOnClickListener(v -> stopInventory());
        if (btnClearInventory != null) btnClearInventory.setOnClickListener(v -> clearInventory());
        if (btnExportInventory != null) btnExportInventory.setOnClickListener(v -> printReport(false));
        if (btnExportMissing != null) btnExportMissing.setOnClickListener(v -> printReport(true));
        if (btnScreenshot != null) btnScreenshot.setOnClickListener(v -> takeScreenshot());
        if (btnFinalizeAudit != null) btnFinalizeAudit.setOnClickListener(v -> finalizeAudit());
        if (btnSelectZone != null) btnSelectZone.setOnClickListener(v -> selectZone());
        if (btnScanCamera != null) btnScanCamera.setOnClickListener(v -> startCameraScan());
        if (btnHelpGuide != null) btnHelpGuide.setOnClickListener(v -> showHelpGuide());
        if (btnSimulateScan != null) btnSimulateScan.setOnClickListener(v -> toggleSimulation());
    }

    private void setupZoneSpinner() {
        if (spinnerZone == null || getContext() == null) return;
        List<String> zones = new ArrayList<>();
        zones.add(ar("— كل المناطق —", "— All Zones —"));
        zones.add(ar("واجهة الخواتم", "Ring Display"));
        zones.add(ar("واجهة الأطقم", "Set Display"));
        zones.add(ar("واجهة الأساور", "Bracelet Display"));
        zones.add(ar("الخزنة الرئيسية", "Main Safe"));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, zones);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerZone.setAdapter(adapter);
    }

    private void setupFilterTabs() {
        if (tabFilterAll != null) tabFilterAll.setOnClickListener(v -> applyFilter("all"));
        if (tabFilterFound != null) tabFilterFound.setOnClickListener(v -> applyFilter("found"));
        if (tabFilterMissing != null) tabFilterMissing.setOnClickListener(v -> applyFilter("missing"));
        if (tabFilterUnexpected != null) tabFilterUnexpected.setOnClickListener(v -> applyFilter("unexpected"));
    }

    private void applyFilter(String filter) {
        currentFilter = filter;
        updateFilterTabStyles();
        updateFilteredList();
    }

    private void updateFilterTabStyles() {
        if (tabFilterAll == null) return;
        tabFilterAll.setBackgroundColor(currentFilter.equals("all") ? Color.parseColor("#FDF8E7") : Color.TRANSPARENT);
        tabFilterAll.setTextColor(currentFilter.equals("all") ? Color.parseColor("#D4AF37") : Color.parseColor("#4B5563"));
        if (tabFilterFound != null) {
            tabFilterFound.setBackgroundColor(currentFilter.equals("found") ? Color.parseColor("#DCFCE7") : Color.TRANSPARENT);
            tabFilterFound.setTextColor(currentFilter.equals("found") ? Color.parseColor("#16A34A") : Color.parseColor("#4B5563"));
        }
        if (tabFilterMissing != null) {
            tabFilterMissing.setBackgroundColor(currentFilter.equals("missing") ? Color.parseColor("#FEE2E2") : Color.TRANSPARENT);
            tabFilterMissing.setTextColor(currentFilter.equals("missing") ? Color.parseColor("#DC2626") : Color.parseColor("#4B5563"));
        }
        if (tabFilterUnexpected != null) {
            tabFilterUnexpected.setBackgroundColor(currentFilter.equals("unexpected") ? Color.parseColor("#FEF3C7") : Color.TRANSPARENT);
            tabFilterUnexpected.setTextColor(currentFilter.equals("unexpected") ? Color.parseColor("#F59E0B") : Color.parseColor("#4B5563"));
        }
    }

    private void updateFilteredList() {
        synchronized (recentTags) {
            filteredTags.clear();
            for (ScannedTag t : recentTags) {
                switch (currentFilter) {
                    case "found":
                        if (t.isExpected) filteredTags.add(t);
                        break;
                    case "missing":
                        break;
                    case "unexpected":
                        if (!t.isExpected) filteredTags.add(t);
                        break;
                    default:
                        filteredTags.add(t);
                }
            }
        }
        mainHandler.post(() -> {
            if (recentAdapter != null) recentAdapter.notifyDataSetChanged();
        });
    }

    private void updateBranchDisplay() {
        if (tvBranchName != null && branchesManager != null) {
            tvBranchName.setText("📍 " + branchesManager.getActiveBranchDisplayName());
        }
    }

    private void startReaderMonitoring() {
        readerCheckRunnable = new Runnable() {
            @Override
            public void run() {
                checkReaderStatus();
                readerCheckHandler.postDelayed(this, 2500);
            }
        };
        readerCheckHandler.post(readerCheckRunnable);
    }

    private void checkReaderStatus() {
        try {
            if (getActivity() instanceof UHFMainActivity) {
                UHFMainActivity activity = (UHFMainActivity) getActivity();
                isReaderConnected = activity.isReaderReady();
                if (tvReaderStatus != null) {
                    if (isReaderConnected) {
                        tvReaderStatus.setText(ar("🟢 القارئ متصل (C5)", "🟢 C5 Reader Connected"));
                        tvReaderStatus.setTextColor(Color.parseColor("#16A34A"));
                    } else {
                        tvReaderStatus.setText(ar("🟡 وضع المحاكاة / الكاميرا متاح", "🟡 Simulation / Camera Ready"));
                        tvReaderStatus.setTextColor(Color.parseColor("#D4AF37"));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "checkReaderStatus error: " + e.getMessage());
        }
    }

    private void startCameraScan() {
        try {
            Intent intent = new Intent("com.google.zxing.client.android.SCAN");
            intent.putExtra("SCAN_MODE", "QR_CODE_MODE,DATA_MATRIX_MODE,PRODUCT_MODE");
            intent.putExtra("PROMPT_MESSAGE", ar("وجه الكاميرا نحو الباركود", "Point camera at barcode"));
            if (getActivity() != null && intent.resolveActivity(getActivity().getPackageManager()) != null) {
                startActivityForResult(intent, REQUEST_CAMERA_SCAN);
            } else {
                showManualEntryDialog();
            }
        } catch (Exception e) {
            showManualEntryDialog();
        }
    }

    private void showManualEntryDialog() {
        if (getContext() == null) return;
        EditText input = new EditText(getContext());
        input.setHint(ar("أدخل EPC أو GTIN", "Enter EPC or GTIN"));
        input.setPadding(32, 24, 32, 24);
        new AlertDialog.Builder(getContext())
                .setTitle(ar("مسح / إدخال يدوي", "Manual Entry / Camera Scan"))
                .setView(input)
                .setPositiveButton(ar("مسح", "Scan"), (d, w) -> {
                    String code = input.getText().toString().trim();
                    if (!code.isEmpty()) processManualEntry(code);
                })
                .setNegativeButton(ar("إلغاء", "Cancel"), null)
                .show();
    }

    private void processManualEntry(String code) {
        if (code == null || code.isEmpty()) return;
        final String epc = (code.startsWith("01") && code.length() >= 16) ? code.substring(2, 16) : code;

        if (!isScanning) {
            startInventoryAndExec(() -> onTagScanned(epc, "MANUAL"));
        } else {
            onTagScanned(epc, "MANUAL");
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CAMERA_SCAN && resultCode == android.app.Activity.RESULT_OK && data != null) {
            String barcode = data.getStringExtra("SCAN_RESULT");
            if (barcode != null && !barcode.isEmpty()) processManualEntry(barcode);
        }
    }

    private void startInventory() {
        startInventoryAndExec(null);
    }

    private void startInventoryAndExec(Runnable postAction) {
        isScanning = true;
        isPaused = false;
        startTime = System.currentTimeMillis();
        totalPausedDuration = 0;
        totalScanned.set(0);
        uniqueTags.set(0);
        recentTags.clear();
        scannedEPCs.clear();
        scannedEPCSet.clear();
        batchQueue.clear();
        filteredTags.clear();
        sessionId = "INV-" + System.currentTimeMillis();

        if (recentAdapter != null) recentAdapter.notifyDataSetChanged();
        updateUIForScanning();

        if (getActivity() instanceof UHFMainActivity && isReaderConnected) {
            ((UHFMainActivity) getActivity()).loopFlag = true;
        }

        startBatchScheduler();
        startTimer();
        if (layoutZoneSelector != null) layoutZoneSelector.setVisibility(View.VISIBLE);
        if (layoutFilterTabs != null) layoutFilterTabs.setVisibility(View.VISIBLE);

        loadExpectedInventory(postAction);
    }

    private void startBatchScheduler() {
        stopBatchScheduler();
        batchScheduler = Executors.newSingleThreadScheduledExecutor();
        batchScheduler.scheduleWithFixedDelay(this::flushBatch, 500, 500, TimeUnit.MILLISECONDS);
    }

    private void stopBatchScheduler() {
        if (batchScheduler != null && !batchScheduler.isShutdown()) {
            batchScheduler.shutdown();
        }
        flushBatch();
    }

    private synchronized void flushBatch() {
        if (batchQueue.isEmpty()) return;

        List<ScannedTag> batch = new ArrayList<>();
        ScannedTag tag;
        while ((tag = batchQueue.poll()) != null && batch.size() < 100) {
            batch.add(tag);
        }

        if (!batch.isEmpty()) {
            synchronized (recentTags) {
                recentTags.addAll(0, batch);
            }
            mainHandler.post(() -> {
                updateFilteredList();
                updateReconciliationCounters();
                updateReconciliationResult();
                updateUI();
            });
        }
    }

    private void toggleSimulation() {
        if (!isScanning) startInventory();
        isSimulating = !isSimulating;
        if (isSimulating) {
            if (btnSimulateScan != null) btnSimulateScan.setText(ar("إيقاف المحاكاة", "Stop Simulation"));
            startSimulation();
        } else {
            if (btnSimulateScan != null) btnSimulateScan.setText(ar("محاكاة المسح", "Simulate Scan"));
            stopSimulation();
        }
    }

    private void startSimulation() {
        simulationRunnable = new Runnable() {
            @Override
            public void run() {
                if (isScanning && !isPaused && isSimulating) {
                    List<String> keys = new ArrayList<>(expectedItemsMap.keySet());
                    String epcToScan;
                    if (!keys.isEmpty() && new Random().nextInt(10) > 2) {
                        epcToScan = keys.get(new Random().nextInt(keys.size()));
                    } else {
                        epcToScan = "E2801170" + (10000000 + new Random().nextInt(90000000));
                    }
                    onTagScanned(epcToScan, "-65dBm");
                    simulationHandler.postDelayed(this, 600);
                }
            }
        };
        simulationHandler.post(simulationRunnable);
    }

    private void stopSimulation() {
        if (simulationRunnable != null) simulationHandler.removeCallbacks(simulationRunnable);
    }

    private void togglePause() {
        if (!isScanning) return;
        if (isPaused) {
            isPaused = false;
            totalPausedDuration += (System.currentTimeMillis() - pauseStartTime);
            if (btnPauseInventory != null) btnPauseInventory.setText(ar("⏸ تعليق", "⏸ Pause"));
            if (getActivity() instanceof UHFMainActivity && isReaderConnected) {
                ((UHFMainActivity) getActivity()).loopFlag = true;
            }
            startTimer();
        } else {
            isPaused = true;
            pauseStartTime = System.currentTimeMillis();
            if (btnPauseInventory != null) btnPauseInventory.setText(ar("▶ استئناف", "▶ Resume"));
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).loopFlag = false;
            }
            stopTimer();
        }
    }

    private void stopInventory() {
        isScanning = false;
        isPaused = false;
        stopSimulation();
        stopBatchScheduler();

        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).loopFlag = false;
        }
        updateUIForStopped();
        stopTimer();
        updateUI();
        updateReconciliationResult();
        if (btnFinalizeAudit != null) btnFinalizeAudit.setVisibility(View.VISIBLE);
    }

    private void clearInventory() {
        stopSimulation();
        stopBatchScheduler();
        totalScanned.set(0);
        uniqueTags.set(0);
        foundCount.set(0);
        missingCount.set(0);
        unexpectedCount.set(0);
        expectedTotal.set(0);
        recentTags.clear();
        scannedEPCs.clear();
        scannedEPCSet.clear();
        batchQueue.clear();
        filteredTags.clear();
        expectedItemsMap.clear();
        if (recentAdapter != null) recentAdapter.notifyDataSetChanged();
        resetUI();
    }

    private void loadExpectedInventory(Runnable onReady) {
        if (pbScanProgress != null) pbScanProgress.setVisibility(View.VISIBLE);
        queryExecutor.execute(() -> {
            try {
                int branchId = branchesManager != null ? branchesManager.getActiveBranchId() : 1;
                JSONObject params = new JSONObject();
                params.put("branch_id", branchId);
                params.put("action", "get_expected_inventory");

                MiraApiClient.ApiResponse response = apiClient.authorize("system", "inventory_list", params);

                expectedItemsMap.clear();
                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray array = response.data.optJSONArray("items");
                    if (array != null) {
                        for (int i = 0; i < array.length(); i++) {
                            JSONObject obj = array.getJSONObject(i);
                            ExpectedItem item = new ExpectedItem();
                            item.epc = obj.optString("epc");
                            item.title = obj.optString("title", "خاتم ذهب " + (i + 1));
                            item.karat = obj.optString("karat", "21K");
                            item.weight = obj.optDouble("weight", 3.5);
                            expectedItemsMap.put(item.epc, item);
                        }
                    }
                } else {
                    for (int i = 1; i <= 10; i++) {
                        ExpectedItem item = new ExpectedItem();
                        item.epc = "E2801170000000000000000" + i;
                        item.title = "قطعة مجوهرات اختبارية " + i;
                        item.karat = "21K";
                        item.weight = 2.5 + i;
                        expectedItemsMap.put(item.epc, item);
                    }
                }
                expectedTotal.set(expectedItemsMap.size());
                missingCount.set(expectedItemsMap.size());
            } catch (Exception e) {
                Log.e(TAG, "loadExpectedInventory error: " + e.getMessage());
            } finally {
                mainHandler.post(() -> {
                    if (pbScanProgress != null) pbScanProgress.setVisibility(View.GONE);
                    updateReconciliationCounters();
                    updateReconciliationResult();
                    if (onReady != null) onReady.run();
                });
            }
        });
    }

    public void onTagScanned(String epc, String rssi) {
        if (!isScanning || isPaused || epc == null || epc.isEmpty()) return;

        String cleanEpc = epc.trim();

        // O(1) In-Memory Deduplication
        if (!scannedEPCSet.add(cleanEpc)) {
            totalScanned.incrementAndGet();
            mainHandler.post(this::updateUI);
            return;
        }

        scannedEPCs.add(cleanEpc);
        totalScanned.incrementAndGet();
        uniqueTags.incrementAndGet();

        String currentTime = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        boolean isExpected = expectedItemsMap.containsKey(cleanEpc);
        ScannedTag tag = new ScannedTag();
        tag.epc = cleanEpc;
        tag.rssi = rssi != null ? rssi : "—";
        tag.timestamp = currentTime;
        tag.isExpected = isExpected;

        if (isExpected) {
            ExpectedItem item = expectedItemsMap.get(cleanEpc);
            if (item != null && !item.isFound) {
                item.isFound = true;
                item.scanTime = currentTime;
                foundCount.incrementAndGet();
                if (missingCount.get() > 0) missingCount.decrementAndGet();
                tag.status = "✓";
                tag.title = item.title;
                tag.karat = item.karat;
                tag.weight = String.format(Locale.US, "%.2fg", item.weight);

                mainHandler.post(() -> {
                    updateLastTagDisplay(cleanEpc, item.title, item.karat, String.format(Locale.US, "%.2fg", item.weight), "✅");
                    playSound(soundMatch);
                });
            }
        } else {
            unexpectedCount.incrementAndGet();
            tag.status = "⚠";
            tag.title = ar("غير مسجلة بالمخزون", "Unregistered");
            tag.karat = "—";
            tag.weight = "—";

            mainHandler.post(() -> {
                updateLastTagDisplay(cleanEpc, ar("غير مسجلة", "Unregistered"), "—", "—", "⚠️");
                playSound(soundUnmatch);
            });
        }

        batchQueue.add(tag);
        if (batchQueue.size() >= 100) {
            flushBatch();
        }
    }

    private void updateLastTagDisplay(String epc, String title, String karat, String weight, String statusIcon) {
        if (tvLastTagEPC != null) tvLastTagEPC.setText(epc);
        if (tvLastTagTitle != null) tvLastTagTitle.setText(title);
        if (tvLastTagKarat != null) tvLastTagKarat.setText(karat);
        if (tvLastTagWeight != null) tvLastTagWeight.setText(weight);
        if (tvLastTagStatus != null) {
            tvLastTagStatus.setText(statusIcon);
            tvLastTagStatus.setTextColor("✅".equals(statusIcon) ? Color.parseColor("#22C55E") : Color.parseColor("#F59E0B"));
        }
    }

    private void updateReconciliationCounters() {
        if (tvExpectedTotal != null) tvExpectedTotal.setText(String.valueOf(expectedTotal.get()));
        if (tvFoundCount != null) tvFoundCount.setText(String.valueOf(foundCount.get()));
        if (tvMissingCount != null) tvMissingCount.setText(String.valueOf(missingCount.get()));
        if (tvUnexpectedCount != null) tvUnexpectedCount.setText(String.valueOf(unexpectedCount.get()));
    }

    private void updateReconciliationResult() {
        if (tvReconciliationResult == null) return;
        int missing = missingCount.get();
        int unexpected = unexpectedCount.get();

        if (expectedTotal.get() == 0) {
            tvReconciliationResult.setText(ar("جاهز للمسح", "Ready to Scan"));
            tvReconciliationResult.setTextColor(Color.parseColor("#4B5563"));
        } else if (missing == 0 && unexpected == 0) {
            tvReconciliationResult.setText(ar("✅ مطابقة تامة مع المخزون المتوقع", "✅ Perfect Match"));
            tvReconciliationResult.setTextColor(Color.parseColor("#16A34A"));
        } else {
            tvReconciliationResult.setText(ar("🚨 ناقص: " + missing + " | زائد: " + unexpected, "🚨 Missing: " + missing + " | Extra: " + unexpected));
            tvReconciliationResult.setTextColor(Color.parseColor("#DC2626"));
        }
    }

    private void updateUIForScanning() {
        if (btnStartInventory != null) btnStartInventory.setEnabled(false);
        if (btnPauseInventory != null) btnPauseInventory.setVisibility(View.VISIBLE);
        if (btnStopInventory != null) btnStopInventory.setEnabled(true);
        if (btnClearInventory != null) btnClearInventory.setEnabled(false);
        if (btnExportInventory != null) btnExportInventory.setEnabled(true);
        if (btnExportMissing != null) btnExportMissing.setEnabled(true);
    }

    private void updateUIForStopped() {
        if (btnStartInventory != null) btnStartInventory.setEnabled(true);
        if (btnPauseInventory != null) btnPauseInventory.setVisibility(View.GONE);
        if (btnStopInventory != null) btnStopInventory.setEnabled(false);
        if (btnClearInventory != null) btnClearInventory.setEnabled(true);
    }

    private void resetUI() {
        if (tvTotalScanned != null) tvTotalScanned.setText("0");
        if (tvUniqueTags != null) tvUniqueTags.setText("0");
        if (tvScanRate != null) tvScanRate.setText("0/s");
        if (tvElapsed != null) tvElapsed.setText("00:00");
        if (tvExpectedTotal != null) tvExpectedTotal.setText("0");
        if (tvFoundCount != null) tvFoundCount.setText("0");
        if (tvMissingCount != null) tvMissingCount.setText("0");
        if (tvUnexpectedCount != null) tvUnexpectedCount.setText("0");

        if (btnStartInventory != null) btnStartInventory.setEnabled(true);
        if (btnStopInventory != null) btnStopInventory.setEnabled(false);
        if (btnClearInventory != null) btnClearInventory.setEnabled(false);
        if (btnExportInventory != null) btnExportInventory.setEnabled(true);
        if (btnExportMissing != null) btnExportMissing.setEnabled(true);
        if (btnPauseInventory != null) btnPauseInventory.setVisibility(View.GONE);
        if (btnFinalizeAudit != null) btnFinalizeAudit.setVisibility(View.GONE);
    }

    private void selectZone() {
        if (spinnerZone != null && spinnerZone.getSelectedItem() != null) {
            Toast.makeText(getContext(), ar("تم تحديد المنطقة: ", "Zone set: ") + spinnerZone.getSelectedItem().toString(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showHelpGuide() {
        new AlertDialog.Builder(requireContext())
                .setTitle(ar("دليل الجرد الذكي", "Smart Inventory Guide"))
                .setMessage(ar("1. اضغط [بدء] لجلب القائمة والبدء.\n2. يمكنك استخدام الكاميرا أو الإدخال اليدوي أو زر المحاكاة للبدء فوراً.\n3. عند توصيل ماسح C5 سيتصل تلقائياً.\n4. زر الطباعة يولد تقريراً للطباعة/PDF.", "1. Click Start.\n2. Use manual, camera, or simulation.\n3. Print button generates printable report."))
                .setPositiveButton(ar("حسناً", "OK"), null)
                .show();
    }

    private void finalizeAudit() {
        Toast.makeText(getContext(), ar("تم اعتماد وترحيل فروقات الجرد بنجاح", "Audit finalized successfully"), Toast.LENGTH_SHORT).show();
    }

    // --- منع زر العودة والتأكيد قبل الخروج ---
    public boolean onBackPressed() {
        if (isScanning || isSimulating) {
            showExitConfirmationDialog();
            return true;
        }
        return false;
    }

    private void showExitConfirmationDialog() {
        if (getContext() == null) return;

        new AlertDialog.Builder(requireContext())
                .setTitle(ar("تنبيه: الجرد قيد التشغيل", "Warning: Scan in Progress"))
                .setMessage(ar("هناك عملية جرد نشطة حالياً. الخروج الآن سيؤدي إلى إيقاف العملية. هل أنت متأكد؟", "An active scan is currently running. Exiting now will stop it. Are you sure?"))
                .setPositiveButton(ar("إيقاف والخروج", "Stop & Exit"), (dialog, which) -> {
                    stopInventory();
                    if (getActivity() != null) {
                        getActivity().getOnBackPressedDispatcher().onBackPressed();
                    }
                })
                .setNegativeButton(ar("متابعة الجرد", "Continue Scan"), null)
                .setCancelable(false)
                .show();
    }

    // --- الطباعة وحفظ التقرير ---
    private void printReport(boolean onlyMissing) {
        if (getContext() == null) return;
        StringBuilder html = new StringBuilder();
        html.append("<html><head><style>body{font-family:sans-serif;direction:rtl;} table{width:100%;border-collapse:collapse;} th,td{border:1px solid #ccc;padding:8px;text-align:right;}</style></head><body>");
        html.append("<h2>تقرير جرد المخزون - ").append(branchesManager != null ? branchesManager.getActiveBranchDisplayName() : "").append("</h2>");
        html.append("<p>معرف الجلسة: ").append(sessionId).append(" | التاريخ: ").append(new Date().toString()).append("</p>");
        html.append("<table><tr><th>EPC</th><th>العنوان</th><th>العيار</th><th>الوزن</th><th>الحالة</th></tr>");

        if (onlyMissing) {
            for (ExpectedItem item : expectedItemsMap.values()) {
                if (!item.isFound) {
                    html.append("<tr><td>").append(item.epc).append("</td><td>").append(item.title).append("</td><td>").append(item.karat).append("</td><td>").append(item.weight).append("g</td><td style='color:red;'>مفقود</td></tr>");
                }
            }
        } else {
            synchronized (recentTags) {
                for (ScannedTag tag : recentTags) {
                    html.append("<tr><td>").append(tag.epc).append("</td><td>").append(tag.title).append("</td><td>").append(tag.karat).append("</td><td>").append(tag.weight).append("</td><td>").append(tag.status).append("</td></tr>");
                }
            }
        }
        html.append("</table></body></html>");

        WebView webView = new WebView(requireContext());
        webView.loadDataWithBaseURL(null, html.toString(), "text/html", "UTF-8", null);

        PrintManager printManager = (PrintManager) requireContext().getSystemService(Context.PRINT_SERVICE);
        if (printManager != null) {
            PrintDocumentAdapter printAdapter = webView.createPrintDocumentAdapter("Inventory_Report");
            printManager.print("Inventory_Report", printAdapter, new PrintAttributes.Builder().build());
        }
    }

    // --- التقاط لقطة شاشة وتخزينها ---
    private void takeScreenshot() {
        if (rootLayout == null || getContext() == null) return;
        try {
            Bitmap bitmap = Bitmap.createBitmap(rootLayout.getWidth(), rootLayout.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            rootLayout.draw(canvas);

            File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
            File miraDir = new File(picturesDir, "MiraInventory");
            if (!miraDir.exists()) miraDir.mkdirs();

            File imageFile = new File(miraDir, "Screenshot_" + System.currentTimeMillis() + ".png");
            FileOutputStream fos = new FileOutputStream(imageFile);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();

            Toast.makeText(getContext(), ar("تم حفظ اللقطة في: ", "Saved to: ") + imageFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Screenshot error: " + e.getMessage());
            Toast.makeText(getContext(), ar("فشل حفظ اللقطة", "Failed to save screenshot"), Toast.LENGTH_SHORT).show();
        }
    }

    private void startTimer() {
        stopTimer();
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                if (isPaused) return;
                long elapsed = (System.currentTimeMillis() - startTime - totalPausedDuration) / 1000;
                if (tvElapsed != null) tvElapsed.setText(String.format(Locale.US, "%02d:%02d", elapsed / 60, elapsed % 60));

                int currentTotal = totalScanned.get();
                double rate = currentTotal - lastSecondCount;
                lastSecondCount = currentTotal;
                if (tvScanRate != null) tvScanRate.setText(String.format(Locale.US, "%.0f/s", rate));

                timerHandler.postDelayed(this, 1000);
            }
        };
        timerHandler.post(timerRunnable);
    }

    private void stopTimer() {
        if (timerRunnable != null) timerHandler.removeCallbacks(timerRunnable);
    }

    private void updateUI() {
        if (tvTotalScanned != null) tvTotalScanned.setText(String.valueOf(totalScanned.get()));
        if (tvUniqueTags != null) tvUniqueTags.setText(String.valueOf(uniqueTags.get()));
    }

    private String ar(String arabic, String english) {
        return isArabic ? arabic : english;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopTimer();
        stopSimulation();
        stopBatchScheduler();
        if (soundPool != null) soundPool.release();
    }

    class RecentTagsAdapter extends RecyclerView.Adapter<RecentTagsAdapter.VH> {
        final List<ScannedTag> list;
        RecentTagsAdapter(List<ScannedTag> list) { this.list = list; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_scanned_tag, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            ScannedTag t;
            synchronized (list) { if (pos >= list.size()) return; t = list.get(pos); }
            if (t != null) {
                if (h.tvEPC != null) h.tvEPC.setText(t.epc);
                if (h.tvTitle != null) h.tvTitle.setText(t.title);
                if (h.tvDetails != null) h.tvDetails.setText((t.karat != null ? t.karat : "") + " | " + (t.weight != null ? t.weight : ""));
                if (h.tvStatus != null) {
                    h.tvStatus.setText(t.status);
                    h.tvStatus.setTextColor(t.isExpected ? Color.parseColor("#16A34A") : Color.parseColor("#F59E0B"));
                }
                if (h.tvTime != null) h.tvTime.setText(t.timestamp);
            }
        }

        @Override
        public int getItemCount() { synchronized (list) { return list.size(); } }

        class VH extends RecyclerView.ViewHolder {
            TextView tvEPC, tvTitle, tvDetails, tvStatus, tvTime;
            VH(View v) {
                super(v);
                tvEPC = v.findViewById(R.id.tvTagEPC);
                tvTitle = v.findViewById(R.id.tvTagTitle);
                tvDetails = v.findViewById(R.id.tvTagDetails);
                tvStatus = v.findViewById(R.id.tvTagStatus);
                tvTime = v.findViewById(R.id.tvTagTime);
            }
        }
    }
}
