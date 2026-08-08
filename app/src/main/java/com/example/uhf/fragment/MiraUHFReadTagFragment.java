package com.example.uhf.fragment;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MiraUHFReadTagFragment extends Fragment {
    private static final String TAG = "MiraUHFInventory";
    private static final String PREFS_NAME = "mira_inventory_session";

    // Threading
    private final ExecutorService queryExecutor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler timerHandler = new Handler(Looper.getMainLooper());

    // Data
    private final List<ScannedTag> recentTags = Collections.synchronizedList(new ArrayList<>());
    private final List<String> scannedEPCs = Collections.synchronizedList(new ArrayList<>());
    private final List<ScannedTag> filteredTags = Collections.synchronizedList(new ArrayList<>());

    // Reconciliation Engine
    private final ConcurrentHashMap<String, ExpectedItem> expectedItemsMap = new ConcurrentHashMap<>();
    private final AtomicInteger expectedTotal = new AtomicInteger(0);
    private final AtomicInteger foundCount = new AtomicInteger(0);
    private final AtomicInteger missingCount = new AtomicInteger(0);
    private final AtomicInteger unexpectedCount = new AtomicInteger(0);

    // State
    private boolean isScanning = false;
    private boolean isPaused = false;
    private long startTime = 0;
    private long pauseStartTime = 0;
    private long totalPausedDuration = 0;
    private final AtomicInteger totalScanned = new AtomicInteger(0);
    private final AtomicInteger uniqueTags = new AtomicInteger(0);
    private int lastSecondCount = 0;
    private String sessionId;
    private String currentZoneId = null;
    private String currentFilter = "all";
    private String lastScreenshotPath = null;

    // Managers
    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfile;
    private boolean isArabic;

    // Sound
    private SoundPool soundPool;
    private int soundMatch, soundUnmatch, soundError;
    private boolean soundLoaded = false;

    // UI
    private View rootLayout;
    private TextView tvTotalScanned, tvUniqueTags, tvScanRate, tvElapsed, tvBranchName;
    private TextView tvLastTagEPC, tvLastTagTitle, tvLastTagKarat, tvLastTagWeight, tvLastTagStatus;
    private TextView tvExpectedTotal, tvFoundCount, tvMissingCount, tvUnexpectedCount;
    private TextView tvReconciliationResult;
    private TextView tabFilterAll, tabFilterFound, tabFilterMissing, tabFilterUnexpected;
    private CardView cardLastTag;
    private ProgressBar pbScanProgress;
    private RecyclerView rvRecentTags;
    private Button btnStartInventory, btnPauseInventory, btnStopInventory, btnClearInventory;
    private Button btnExportInventory, btnExportMissing, btnScreenshot, btnFinalizeAudit, btnSelectZone;
    private AppCompatSpinner spinnerZone;
    private LinearLayoutManager layoutZoneSelector, layoutFilterTabs;

    private RecentTagsAdapter recentAdapter;
    private Runnable timerRunnable;

    // ==================== Data Classes ====================

    public static class ExpectedItem {
        public String epc, title, karat;
        public double weight;
        public boolean isFound = false;
        public String scanTime;
    }

    static class ScannedTag {
        String epc, rssi, title, karat, weight, status, itemStatus, category, timestamp;
        double price;
        boolean isExpected;
    }

    // ==================== Lifecycle ====================

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        try {
            return inflater.inflate(R.layout.fragment_mira_uhf_readtag, container, false);
        } catch (Exception e) {
            Log.e(TAG, "❌ inflate: " + e.getMessage());
            if (getContext() != null) {
                Toast.makeText(getContext(), "خطأ: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            return new View(getContext());
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Context ctx = getContext();
        if (ctx != null) {
            apiClient = MiraApiClient.getInstance(ctx);
            branchesManager = MiraBranchesManager.getInstance(ctx);
            orgProfile = MiraOrgProfileManager.getInstance(ctx);
        }
        isArabic = Locale.getDefault().getLanguage().equals("ar");
        sessionId = "INV-" + System.currentTimeMillis();

        initSoundPool();
        if (!initViews(view)) return;
        setupRecyclerView();
        setupListeners();
        setupZoneSpinner();
        setupFilterTabs();
        updateBranchDisplay();
        restoreSessionIfExists();
    }

    // ==================== Sound ====================

    private void initSoundPool() {
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
            soundPool = new SoundPool.Builder()
                .setMaxStreams(3)
                .setAudioAttributes(attrs)
                .build();

            soundPool.setOnLoadCompleteListener((pool, sampleId, status) -> {
                if (status == 0) soundLoaded = true;
            });

            if (getContext() != null) {
                soundMatch = soundPool.load(getContext(), R.raw.barcodebeep, 1);
                soundUnmatch = soundPool.load(getContext(), R.raw.serror, 2);
                soundError = soundPool.load(getContext(), R.raw.serror, 3);
            }
        } catch (Exception e) {
            Log.e(TAG, "Sound init: " + e.getMessage());
        }
    }

    private void playSound(int soundId) {
        try {
            if (soundPool != null && soundLoaded) {
                soundPool.play(soundId, 0.5f, 0.5f, 1, 0, 1f);
            }
        } catch (Exception e) {
            Log.e(TAG, "playSound: " + e.getMessage());
        }
    }

    // ==================== Init ====================

    private boolean initViews(View v) {
        try {
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
            spinnerZone = v.findViewById(R.id.spinnerZone);
            layoutZoneSelector = v.findViewById(R.id.layoutZoneSelector);
            layoutFilterTabs = v.findViewById(R.id.layoutFilterTabs);
            tabFilterAll = v.findViewById(R.id.tabFilterAll);
            tabFilterFound = v.findViewById(R.id.tabFilterFound);
            tabFilterMissing = v.findViewById(R.id.tabFilterMissing);
            tabFilterUnexpected = v.findViewById(R.id.tabFilterUnexpected);

            if (tvTotalScanned == null || rvRecentTags == null || btnStartInventory == null) {
                Log.e(TAG, "❌ Critical views null");
                return false;
            }
            resetUI();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "❌ initViews: " + e.getMessage());
            return false;
        }
    }

    private void setupRecyclerView() {
        try {
            if (getContext() == null || rvRecentTags == null) return;
            rvRecentTags.setLayoutManager(new LinearLayoutManager(getContext()));
            recentAdapter = new RecentTagsAdapter(filteredTags);
            rvRecentTags.setAdapter(recentAdapter);
        } catch (Exception e) {
            Log.e(TAG, "❌ RecyclerView: " + e.getMessage());
        }
    }

    private void setupListeners() {
        if (btnStartInventory != null) btnStartInventory.setOnClickListener(v -> startInventory());
        if (btnPauseInventory != null) btnPauseInventory.setOnClickListener(v -> togglePause());
        if (btnStopInventory != null) btnStopInventory.setOnClickListener(v -> stopInventory());
        if (btnClearInventory != null) btnClearInventory.setOnClickListener(v -> clearInventory());
        if (btnExportInventory != null) btnExportInventory.setOnClickListener(v -> exportFullReport());
        if (btnExportMissing != null) btnExportMissing.setOnClickListener(v -> exportMissingReport());
        if (btnScreenshot != null) btnScreenshot.setOnClickListener(v -> takeScreenshot());
        if (btnFinalizeAudit != null) btnFinalizeAudit.setOnClickListener(v -> finalizeAudit());
        if (btnSelectZone != null) btnSelectZone.setOnClickListener(v -> selectZone());
    }

    private void setupZoneSpinner() {
        if (spinnerZone == null || getContext() == null) return;
        List<String> zones = new ArrayList<>();
        zones.add(ar("— كل المناطق —", "— All Zones —"));
        zones.add(ar("واجهة الخواتم", "Ring Display"));
        zones.add(ar("واجهة الأطقم", "Set Display"));
        zones.add(ar("واجهة الأساور", "Bracelet Display"));
        zones.add(ar("واجهة العقود", "Necklace Display"));
        zones.add(ar("صينية 1", "Tray 1"));
        zones.add(ar("صينية 2", "Tray 2"));
        zones.add(ar("صينية 3", "Tray 3"));
        zones.add(ar("الخزنة", "Safe"));

        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(),
            android.R.layout.simple_spinner_item, zones);
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
        filteredTags.clear();
        synchronized (recentTags) {
            for (ScannedTag t : recentTags) {
                switch (currentFilter) {
                    case "found":
                        if (t.isExpected && t.status != null && t.status.equals("✓")) filteredTags.add(t);
                        break;
                    case "missing":
                        if (!t.isExpected) break;
                        boolean found = false;
                        for (ExpectedItem ei : expectedItemsMap.values()) {
                            if (ei.epc.equals(t.epc) && ei.isFound) { found = true; break; }
                        }
                        if (!found) filteredTags.add(t);
                        break;
                    case "unexpected":
                        if (!t.isExpected) filteredTags.add(t);
                        break;
                    default:
                        filteredTags.add(t);
                }
            }
        }
        if (recentAdapter != null) recentAdapter.notifyDataSetChanged();
    }

    private void updateBranchDisplay() {
        try {
            if (tvBranchName != null && branchesManager != null) {
                tvBranchName.setText("📍 " + branchesManager.getActiveBranchDisplayName());
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ branch: " + e.getMessage());
        }
    }

    // ==================== Zone Selection ====================

    private void selectZone() {
        if (spinnerZone == null) return;
        int pos = spinnerZone.getSelectedItemPosition();
        if (pos <= 0) {
            currentZoneId = null;
            Toast.makeText(getContext(), ar("تم اختيار كل المناطق", "All zones selected"), Toast.LENGTH_SHORT).show();
        } else {
            currentZoneId = "zone_" + pos;
            Toast.makeText(getContext(), ar("تم تحديد: " + spinnerZone.getSelectedItem().toString(),
                "Selected: " + spinnerZone.getSelectedItem().toString()), Toast.LENGTH_SHORT).show();
        }
        if (isScanning) {
            clearInventory();
            startInventory();
        }
    }

    // ==================== Session Persistence ====================

    private void saveSessionState() {
        try {
            if (getContext() == null) return;
            SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString("sessionId", sessionId);
            editor.putLong("startTime", startTime);
            editor.putLong("totalPausedDuration", totalPausedDuration);
            editor.putInt("totalScanned", totalScanned.get());
            editor.putInt("uniqueTags", uniqueTags.get());
            editor.putInt("foundCount", foundCount.get());
            editor.putInt("missingCount", missingCount.get());
            editor.putInt("unexpectedCount", unexpectedCount.get());
            editor.putInt("expectedTotal", expectedTotal.get());
            editor.putString("currentZoneId", currentZoneId);
            
            // Save found items
            JSONArray foundArr = new JSONArray();
            for (ExpectedItem item : expectedItemsMap.values()) {
                if (item.isFound) {
                    JSONObject obj = new JSONObject();
                    obj.put("epc", item.epc);
                    obj.put("scanTime", item.scanTime != null ? item.scanTime : "");
                    foundArr.put(obj);
                }
            }
            editor.putString("foundItems", foundArr.toString());
            editor.putBoolean("hasSession", true);
            editor.apply();
        } catch (Exception e) {
            Log.e(TAG, "saveSession: " + e.getMessage());
        }
    }

    private void restoreSessionIfExists() {
        try {
            if (getContext() == null) return;
            SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            if (!prefs.getBoolean("hasSession", false)) return;

            new androidx.appcompat.app.AlertDialog.Builder(getContext())
                .setTitle(ar("جلسة جرد سابقة", "Previous Session"))
                .setMessage(ar("توجد جلسة جرد لم تكتمل. هل تريد استئنافها؟", 
                    "An incomplete session exists. Resume?"))
                .setPositiveButton(ar("استئناف", "Resume"), (d, w) -> resumeSession(prefs))
                .setNegativeButton(ar("جلسة جديدة", "New Session"), (d, w) -> clearSessionData())
                .show();
        } catch (Exception e) {
            Log.e(TAG, "restoreSession: " + e.getMessage());
        }
    }

    private void resumeSession(SharedPreferences prefs) {
        try {
            sessionId = prefs.getString("sessionId", "INV-" + System.currentTimeMillis());
            startTime = prefs.getLong("startTime", System.currentTimeMillis());
            totalPausedDuration = prefs.getLong("totalPausedDuration", 0);
            totalScanned.set(prefs.getInt("totalScanned", 0));
            uniqueTags.set(prefs.getInt("uniqueTags", 0));
            foundCount.set(prefs.getInt("foundCount", 0));
            missingCount.set(prefs.getInt("missingCount", 0));
            unexpectedCount.set(prefs.getInt("unexpectedCount", 0));
            expectedTotal.set(prefs.getInt("expectedTotal", 0));
            currentZoneId = prefs.getString("currentZoneId", null);

            loadExpectedInventory(() -> {
                try {
                    String foundItemsJson = prefs.getString("foundItems", "[]");
                    JSONArray foundArr = new JSONArray(foundItemsJson);
                    for (int i = 0; i < foundArr.length(); i++) {
                        JSONObject obj = foundArr.getJSONObject(i);
                        String epc = obj.optString("epc");
                        ExpectedItem item = expectedItemsMap.get(epc);
                        if (item != null) {
                            item.isFound = true;
                            item.scanTime = obj.optString("scanTime", "");
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "resume foundItems: " + e.getMessage());
                }

                isScanning = true;
                isPaused = false;
                if (getActivity() instanceof UHFMainActivity) {
                    ((UHFMainActivity) getActivity()).loopFlag = true;
                }

                updateUIForScanning();
                startTimer();
                updateUI();
                updateReconciliationCounters();
                updateReconciliationResult();

                Toast.makeText(getContext(), ar("✅ تم استئناف الجلسة", "✅ Session resumed"), Toast.LENGTH_SHORT).show();
            });
        } catch (Exception e) {
            Log.e(TAG, "resumeSession: " + e.getMessage());
        }
    }

    private void clearSessionData() {
        try {
            if (getContext() != null) {
                getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().clear().apply();
            }
        } catch (Exception e) {
            Log.e(TAG, "clearSession: " + e.getMessage());
        }
    }

    // ==================== Inventory Control ====================

    private void startInventory() {
        loadExpectedInventory(() -> {
            isScanning = true;
            isPaused = false;
            startTime = System.currentTimeMillis();
            totalPausedDuration = 0;
            totalScanned.set(0);
            uniqueTags.set(0);
            lastSecondCount = 0;
            recentTags.clear();
            scannedEPCs.clear();
            filteredTags.clear();
            sessionId = "INV-" + System.currentTimeMillis();

            if (recentAdapter != null) recentAdapter.notifyDataSetChanged();
            updateUIForScanning();
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).loopFlag = true;
            }

            startTimer();
            updateUI();
            updateReconciliationCounters();

            if (layoutZoneSelector != null) layoutZoneSelector.setVisibility(View.VISIBLE);
            if (layoutFilterTabs != null) layoutFilterTabs.setVisibility(View.VISIBLE);

            Toast.makeText(getContext(), ar("🔍 بدء الجرد...", "🔍 Inventory Started..."), Toast.LENGTH_SHORT).show();
        });
    }

    private void togglePause() {
        if (!isScanning) return;

        if (isPaused) {
            // Resume
            isPaused = false;
            totalPausedDuration += (System.currentTimeMillis() - pauseStartTime);
            if (btnPauseInventory != null) btnPauseInventory.setText("⏸ تعليق");
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).loopFlag = true;
            }
            startTimer();
            Toast.makeText(getContext(), ar("▶ تم الاستئناف", "▶ Resumed"), Toast.LENGTH_SHORT).show();
        } else {
            // Pause
            isPaused = true;
            pauseStartTime = System.currentTimeMillis();
            if (btnPauseInventory != null) btnPauseInventory.setText("▶ استئناف");
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).loopFlag = false;
            }
            stopTimer();
            saveSessionState();
            Toast.makeText(getContext(), ar("⏸ تم التعليق - الجلسة محفوظة", "⏸ Paused - Session saved"), Toast.LENGTH_SHORT).show();
        }
    }

    private void stopInventory() {
        isScanning = false;
        isPaused = false;
        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).loopFlag = false;
        }

        updateUIForStopped();
        stopTimer();
        updateUI();
        updateReconciliationResult();
        saveSessionState();

        long elapsed = (System.currentTimeMillis() - startTime - totalPausedDuration) / 1000;
        String msg = ar(
            "✅ " + totalScanned.get() + " مسح | " + foundCount.get() + " مطابق | " + missingCount.get() + " ناقص | " + unexpectedCount.get() + " زائد",
            "✅ " + totalScanned.get() + " scans | " + foundCount.get() + " found | " + missingCount.get() + " missing | " + unexpectedCount.get() + " extra");

        Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show();

        if (btnFinalizeAudit != null) btnFinalizeAudit.setVisibility(View.VISIBLE);
    }

    private void clearInventory() {
        totalScanned.set(0);
        uniqueTags.set(0);
        foundCount.set(0);
        missingCount.set(0);
        unexpectedCount.set(0);
        expectedTotal.set(0);
        lastSecondCount = 0;
        startTime = 0;
        totalPausedDuration = 0;
        recentTags.clear();
        scannedEPCs.clear();
        filteredTags.clear();
        expectedItemsMap.clear();
        currentFilter = "all";
        if (recentAdapter != null) recentAdapter.notifyDataSetChanged();
        clearSessionData();
        resetUI();

        if (layoutZoneSelector != null) layoutZoneSelector.setVisibility(View.GONE);
        if (layoutFilterTabs != null) layoutFilterTabs.setVisibility(View.GONE);

        Toast.makeText(getContext(), ar("🗑️ تم مسح الجرد", "🗑️ Cleared"), Toast.LENGTH_SHORT).show();
    }

    private void finalizeAudit() {
        if (getContext() == null) return;

        new androidx.appcompat.app.AlertDialog.Builder(getContext())
            .setTitle(ar("اعتماد الجرد", "Finalize Audit"))
            .setMessage(ar(
                "سيتم إرسال تقرير العجز إلى الخادم وتحديث حالة القطع المفقودة.\n\n" +
                "المطابق: " + foundCount.get() + "\n" +
                "الناقص: " + missingCount.get() + "\n" +
                "الزائد: " + unexpectedCount.get() + "\n\n" +
                "هل تريد المتابعة؟",
                "Missing items will be reported and status updated.\n\n" +
                "Found: " + foundCount.get() + "\n" +
                "Missing: " + missingCount.get() + "\n" +
                "Extra: " + unexpectedCount.get() + "\n\n" +
                "Proceed?"))
            .setPositiveButton(ar("اعتماد", "Finalize"), (d, w) -> submitReconciliation())
            .setNegativeButton(ar("إلغاء", "Cancel"), null)
            .show();
    }

    private void submitReconciliation() {
        if (pbScanProgress != null) pbScanProgress.setVisibility(View.VISIBLE);

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("session_id", sessionId);
                payload.put("branch_id", branchesManager.getActiveBranchId());
                payload.put("found_count", foundCount.get());
                payload.put("missing_count", missingCount.get());
                payload.put("unexpected_count", unexpectedCount.get());
                payload.put("total_scanned", totalScanned.get());
                payload.put("zone_id", currentZoneId != null ? currentZoneId : "");

                // Missing EPCs
                JSONArray missingArr = new JSONArray();
                for (ExpectedItem item : expectedItemsMap.values()) {
                    if (!item.isFound) {
                        missingArr.put(item.epc);
                    }
                }
                payload.put("missing_epcs", missingArr);

                // Unexpected EPCs
                JSONArray unexpectedArr = new JSONArray();
                synchronized (recentTags) {
                    for (ScannedTag t : recentTags) {
                        if (!t.isExpected && !expectedItemsMap.containsKey(t.epc)) {
                            unexpectedArr.put(t.epc);
                        }
                    }
                }
                payload.put("unexpected_epcs", unexpectedArr);

                MiraApiClient.ApiResponse response = apiClient.reconcileInventory(payload);

                mainHandler.post(() -> {
                    if (pbScanProgress != null) pbScanProgress.setVisibility(View.GONE);
                    if (response != null && response.isSuccess) {
                        Toast.makeText(getContext(),
                            ar("✅ تم اعتماد الجرد بنجاح", "✅ Audit finalized successfully"),
                            Toast.LENGTH_LONG).show();
                        if (btnFinalizeAudit != null) btnFinalizeAudit.setVisibility(View.GONE);
                        clearSessionData();
                    } else {
                        Toast.makeText(getContext(),
                            ar("❌ فشل الاعتماد", "❌ Finalize failed"),
                            Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "submitReconciliation: " + e.getMessage());
                mainHandler.post(() -> {
                    if (pbScanProgress != null) pbScanProgress.setVisibility(View.GONE);
                    Toast.makeText(getContext(),
                        ar("❌ خطأ: " + e.getMessage(), "❌ Error: " + e.getMessage()),
                        Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // ==================== UI State ====================

    private void updateUIForScanning() {
        if (btnStartInventory != null) { btnStartInventory.setEnabled(false); btnStartInventory.setAlpha(0.5f); }
        if (btnPauseInventory != null) { btnPauseInventory.setVisibility(View.VISIBLE); btnPauseInventory.setText("⏸ تعليق"); }
        if (btnStopInventory != null) { btnStopInventory.setEnabled(true); btnStopInventory.setAlpha(1f); }
        if (btnClearInventory != null) btnClearInventory.setEnabled(false);
        if (pbScanProgress != null) pbScanProgress.setVisibility(View.VISIBLE);
        if (btnFinalizeAudit != null) btnFinalizeAudit.setVisibility(View.GONE);
    }

    private void updateUIForStopped() {
        if (btnStartInventory != null) { btnStartInventory.setEnabled(true); btnStartInventory.setAlpha(1f); }
        if (btnPauseInventory != null) btnPauseInventory.setVisibility(View.GONE);
        if (btnStopInventory != null) { btnStopInventory.setEnabled(false); btnStopInventory.setAlpha(0.5f); }
        if (btnClearInventory != null) btnClearInventory.setEnabled(true);
        if (pbScanProgress != null) pbScanProgress.setVisibility(View.GONE);
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
        if (tvReconciliationResult != null) tvReconciliationResult.setText(ar("في انتظار بدء الجرد...", "Awaiting inventory start..."));
        if (tvReconciliationResult != null) tvReconciliationResult.setTextColor(Color.parseColor("#111827"));
        if (tvLastTagEPC != null) tvLastTagEPC.setText("—");
        if (tvLastTagTitle != null) tvLastTagTitle.setText(ar("في انتظار المسح...", "Awaiting scan..."));
        if (tvLastTagKarat != null) tvLastTagKarat.setText("—");
        if (tvLastTagWeight != null) tvLastTagWeight.setText("—");
        if (tvLastTagStatus != null) tvLastTagStatus.setText("⚪");
        if (tvLastTagStatus != null) tvLastTagStatus.setTextColor(Color.parseColor("#9CA3AF"));
        if (btnStopInventory != null) { btnStopInventory.setEnabled(false); btnStopInventory.setAlpha(0.5f); }
        if (btnClearInventory != null) btnClearInventory.setEnabled(false);
        if (btnPauseInventory != null) btnPauseInventory.setVisibility(View.GONE);
        if (pbScanProgress != null) pbScanProgress.setVisibility(View.GONE);
        if (btnFinalizeAudit != null) btnFinalizeAudit.setVisibility(View.GONE);
        if (layoutZoneSelector != null) layoutZoneSelector.setVisibility(View.GONE);
        if (layoutFilterTabs != null) layoutFilterTabs.setVisibility(View.GONE);
    }

    public void onKeyDownTrigger() {
        if (!isScanning) startInventory();
        else if (isPaused) togglePause();
        else stopInventory();
    }

    // ==================== Reconciliation Engine ====================

    private void loadExpectedInventory(Runnable onReady) {
        if (pbScanProgress != null) pbScanProgress.setVisibility(View.VISIBLE);

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                int branchId = branchesManager.getActiveBranchId();
                MiraApiClient.ApiResponse response = apiClient.getBranchExpectedInventory(branchId);

                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray array = response.data.optJSONArray("items");
                    expectedItemsMap.clear();

                    if (array != null) {
                        for (int i = 0; i < array.length(); i++) {
                            JSONObject obj = array.getJSONObject(i);
                            ExpectedItem item = new ExpectedItem();
                            item.epc = obj.optString("epc");
                            item.title = obj.optString("title", "");
                            item.karat = obj.optString("karat", "");
                            item.weight = obj.optDouble("weight", 0);
                            item.isFound = false;
                            expectedItemsMap.put(item.epc, item);
                        }
                    }

                    expectedTotal.set(expectedItemsMap.size());
                    missingCount.set(expectedItemsMap.size());
                    foundCount.set(0);
                    unexpectedCount.set(0);
                }

                mainHandler.post(() -> {
                    if (pbScanProgress != null) pbScanProgress.setVisibility(View.GONE);
                    updateReconciliationCounters();
                    if (onReady != null) onReady.run();
                });
            } catch (Exception e) {
                Log.e(TAG, "loadExpected: " + e.getMessage());
                mainHandler.post(() -> {
                    if (pbScanProgress != null) pbScanProgress.setVisibility(View.GONE);
                    if (onReady != null) onReady.run();
                });
            }
        });
    }

    public void onTagScanned(String epc, String rssi) {
        if (!isScanning || isPaused || epc == null || epc.isEmpty()) return;

        if (scannedEPCs.contains(epc)) {
            totalScanned.incrementAndGet();
            mainHandler.post(this::updateUI);
            return;
        }

        scannedEPCs.add(epc);
        totalScanned.incrementAndGet();
        uniqueTags.incrementAndGet();

        String currentTime = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        boolean isExpected = expectedItemsMap.containsKey(epc);

        ScannedTag tag = new ScannedTag();
        tag.epc = epc;
        tag.rssi = rssi != null ? rssi : "—";
        tag.timestamp = currentTime;
        tag.isExpected = isExpected;

        if (isExpected) {
            ExpectedItem item = expectedItemsMap.get(epc);
            if (item != null && !item.isFound) {
                item.isFound = true;
                item.scanTime = currentTime;
                foundCount.incrementAndGet();
                missingCount.decrementAndGet();
                tag.status = "✓";
                tag.title = item.title;
                tag.karat = item.karat;
                tag.weight = String.format(Locale.US, "%.2fg", item.weight);
                mainHandler.post(() -> {
                    updateLastTagDisplay(epc, item.title, item.karat,
                        String.format(Locale.US, "%.2fg", item.weight), "✅");
                    playSound(soundMatch);
                });
            } else {
                tag.status = "✓";
                totalScanned.decrementAndGet();
                uniqueTags.decrementAndGet();
            }
        } else {
            unexpectedCount.incrementAndGet();
            tag.status = "⚠";
            mainHandler.post(() -> {
                updateLastTagDisplay(epc, ar("غير مسجلة", "Unregistered"), "—", "—", "⚠️");
                playSound(soundUnmatch);
            });
        }

        synchronized (recentTags) {
            recentTags.add(0, tag);
            if (recentTags.size() > 1000) recentTags.remove(recentTags.size() - 1);
        }

        mainHandler.post(() -> {
            updateFilteredList();
            if (recentAdapter != null) recentAdapter.notifyDataSetChanged();
            if (rvRecentTags != null && currentFilter.equals("all")) rvRecentTags.scrollToPosition(0);
            updateReconciliationCounters();
            updateUI();
        });
    }

    private void updateLastTagDisplay(String epc, String title, String karat, String weight, String statusIcon) {
        if (tvLastTagEPC != null) tvLastTagEPC.setText(epc);
        if (tvLastTagTitle != null) tvLastTagTitle.setText(title.isEmpty() ? epc : title);
        if (tvLastTagKarat != null) tvLastTagKarat.setText(karat.isEmpty() ? "—" : karat);
        if (tvLastTagWeight != null) tvLastTagWeight.setText(weight);
        if (tvLastTagStatus != null) {
            tvLastTagStatus.setText(statusIcon);
            tvLastTagStatus.setTextColor(statusIcon.equals("✅") ? Color.parseColor("#22C55E") : Color.parseColor("#F59E0B"));
        }
        if (cardLastTag != null) cardLastTag.setVisibility(View.VISIBLE);
    }

    private void updateReconciliationCounters() {
        if (tvExpectedTotal != null) tvExpectedTotal.setText(String.valueOf(expectedTotal.get()));
        if (tvFoundCount != null) tvFoundCount.setText(String.valueOf(foundCount.get()));
        if (tvMissingCount != null) tvMissingCount.setText(String.valueOf(missingCount.get()));
        if (tvUnexpectedCount != null) tvUnexpectedCount.setText(String.valueOf(unexpectedCount.get()));
    }

    private void updateReconciliationResult() {
        if (tvReconciliationResult == null) return;
        int found = foundCount.get();
        int missing = missingCount.get();
        int unexpected = unexpectedCount.get();
        int total = expectedTotal.get();

        if (total == 0) {
            tvReconciliationResult.setText(ar("لا يوجد مخزون متوقع للمقارنة", "No expected inventory"));
            tvReconciliationResult.setTextColor(Color.parseColor("#F59E0B"));
        } else if (missing == 0 && unexpected == 0) {
            tvReconciliationResult.setText(ar("✅ توافق تام - جميع القطع مطابقة", "✅ Full Match"));
            tvReconciliationResult.setTextColor(Color.parseColor("#16A34A"));
        } else if (missing > 0 && unexpected == 0) {
            tvReconciliationResult.setText(ar("⚠️ نقص: " + missing + " قطعة مفقودة", "⚠️ Missing: " + missing));
            tvReconciliationResult.setTextColor(Color.parseColor("#DC2626"));
        } else if (missing == 0 && unexpected > 0) {
            tvReconciliationResult.setText(ar("⚠️ زيادة: " + unexpected + " قطعة غير مسجلة", "⚠️ Extra: " + unexpected));
            tvReconciliationResult.setTextColor(Color.parseColor("#F59E0B"));
        } else {
            tvReconciliationResult.setText(ar("🚨 نقص: " + missing + " | زيادة: " + unexpected,
                "🚨 Missing: " + missing + " | Extra: " + unexpected));
            tvReconciliationResult.setTextColor(Color.parseColor("#DC2626"));
        }
    }

    // ==================== Export ====================

    private void exportFullReport() {
        StringBuilder sb = buildReportHeader();
        sb.append("No,EPC,Title,Karat,Weight,Match,Time,RSSI\n");
        synchronized (recentTags) {
            int i = 0;
            for (ScannedTag t : recentTags) {
                i++;
                sb.append(i).append(",").append(csv(t.epc)).append(",").append(csv(t.title)).append(",")
                    .append(csv(t.karat)).append(",").append(csv(t.weight)).append(",")
                    .append(t.isExpected ? "Found" : "Extra").append(",")
                    .append(csv(t.timestamp)).append(",").append(csv(t.rssi)).append("\n");
            }
        }
        shareCsv(sb, "MIRA_Full_" + sessionId + ".csv");
    }

    private void exportMissingReport() {
        StringBuilder sb = buildReportHeader();
        sb.append(ar("النواقص: ", "Missing: ")).append(missingCount.get()).append("\n\n");
        sb.append("EPC,Title,Karat,Weight,Status\n");
        for (ExpectedItem item : expectedItemsMap.values()) {
            if (!item.isFound) {
                sb.append(csv(item.epc)).append(",").append(csv(item.title)).append(",")
                    .append(csv(item.karat)).append(",").append(item.weight).append(",MISSING\n");
            }
        }
        shareCsv(sb, "MIRA_Missing_" + sessionId + ".csv");
    }

    private StringBuilder buildReportHeader() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== MIRA INVENTORY REPORT ===\n");
        sb.append("Organization: ").append(orgProfile != null ? orgProfile.getOrgName() : "MIRA").append("\n");
        sb.append("Branch: ").append(branchesManager != null ? branchesManager.getActiveBranchDisplayName() : "N/A").append("\n");
        sb.append("Session: ").append(sessionId).append("\n");
        sb.append("Zone: ").append(currentZoneId != null ? currentZoneId : "All").append("\n");
        sb.append("Date: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append("\n");
        sb.append("Expected: ").append(expectedTotal.get()).append("\n");
        sb.append("Found: ").append(foundCount.get()).append("\n");
        sb.append("Missing: ").append(missingCount.get()).append("\n");
        sb.append("Unexpected: ").append(unexpectedCount.get()).append("\n");
        sb.append("Total Scans: ").append(totalScanned.get()).append("\n");
        sb.append("================================\n\n");
        return sb;
    }

    private void shareCsv(StringBuilder sb, String filename) {
        try {
            if (getContext() == null) return;
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/csv");
            intent.putExtra(Intent.EXTRA_SUBJECT, filename);
            intent.putExtra(Intent.EXTRA_TEXT, sb.toString());
            startActivity(Intent.createChooser(intent, ar("تصدير", "Export")));
        } catch (Exception e) {
            Log.e(TAG, "shareCsv: " + e.getMessage());
        }
    }

    private String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    // ==================== Screenshot ====================

    private void takeScreenshot() {
        try {
            if (rootLayout == null) return;
            rootLayout.setDrawingCacheEnabled(true);
            Bitmap bitmap = Bitmap.createBitmap(rootLayout.getDrawingCache());
            rootLayout.setDrawingCacheEnabled(false);

            if (bitmap == null) {
                bitmap = Bitmap.createBitmap(rootLayout.getWidth(), rootLayout.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                rootLayout.draw(canvas);
            }

            String filename = "MIRA_SS_" + sessionId + "_" + System.currentTimeMillis() + ".png";
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, filename);
            FileOutputStream fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();

            String oldPath = lastScreenshotPath;
            lastScreenshotPath = file.getAbsolutePath();

            String msg = ar("📸 تم الحفظ: " + filename, "📸 Saved: " + filename);
            if (oldPath != null) {
                msg += ar("\nاللقطة السابقة محفوظة للمقارنة", "\nPrevious screenshot saved for comparison");
            }
            Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show();

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.fromFile(file), "image/png");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, ar("فتح اللقطة", "Open Screenshot")));

        } catch (Exception e) {
            Log.e(TAG, "screenshot: " + e.getMessage());
            Toast.makeText(getContext(), ar("فشل اللقطة", "Screenshot failed"), Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== Timer ====================

    private void startTimer() {
        stopTimer();
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isAdded() || isPaused) return;
                long elapsed = (System.currentTimeMillis() - startTime - totalPausedDuration) / 1000;
                if (elapsed < 0) elapsed = 0;
                if (tvElapsed != null) {
                    tvElapsed.setText(String.format(Locale.US, "%02d:%02d", elapsed / 60, elapsed % 60));
                }
                int currentTotal = totalScanned.get();
                double rate = currentTotal - lastSecondCount;
                lastSecondCount = currentTotal;
                if (tvScanRate != null && rate >= 0) {
                    tvScanRate.setText(String.format(Locale.US, "%.0f/s", rate));
                }
                timerHandler.postDelayed(this, 1000);
            }
        };
        timerHandler.post(timerRunnable);
    }

    private void stopTimer() {
        if (timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
            timerRunnable = null;
        }
    }

    private void updateUI() {
        if (tvTotalScanned != null) tvTotalScanned.setText(String.valueOf(totalScanned.get()));
        if (tvUniqueTags != null) tvUniqueTags.setText(String.valueOf(uniqueTags.get()));
    }

    private String ar(String arabic, String english) { return isArabic ? arabic : english; }

    // ==================== Cleanup ====================

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopTimer();
        timerHandler.removeCallbacksAndMessages(null);
        mainHandler.removeCallbacksAndMessages(null);
        if (isScanning) saveSessionState();
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (queryExecutor != null && !queryExecutor.isShutdown()) queryExecutor.shutdownNow();
    }

    // ==================== Adapter ====================

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
                if (h.tvTitle != null) h.tvTitle.setText(t.title != null && !t.title.isEmpty() ? t.title : "—");
                if (h.tvDetails != null) h.tvDetails.setText((t.karat != null ? t.karat : "") + " | " + (t.weight != null ? t.weight : ""));
                if (h.tvStatus != null) {
                    h.tvStatus.setText(t.status != null ? t.status : "");
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
