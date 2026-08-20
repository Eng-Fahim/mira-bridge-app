package com.example.uhf.fragment;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
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
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.database.InventoryDatabaseHelper;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.MiraPriceManager;
import com.example.uhf.model.InventoryItemModel;

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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class MiraInventoryProFragment extends KeyDwonFragment {

    private static final String TAG = "MiraInventoryPro";
    private static final String OFFLINE_PREFS = "inventory_pro_cache";
    private static final String PENDING_QUEUE_PREFS = "inventory_pending_queue";
    private static final int UI_UPDATE_INTERVAL = 250;
    private static final int MAX_DISPLAY_ITEMS = 500;
    private static final long VIBRATION_THROTTLE_MS = 100;
    private static final int SCREENSHOT_QUALITY = 70;
    private static final int SIMULATION_BATCH_SIZE = 5;
    private static final long SIMULATION_MIN_DELAY = 150;
    private static final long SIMULATION_MAX_DELAY = 400;

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

    // State & Counters
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
    private long lastVibrationTime = 0;

    // Simulation Flags & Handlers
    private boolean isHardwareConnected = false;
    private boolean isSimulationMode = false;
    private Handler simulationHandler;
    private Runnable simulationRunnable;
    private List<InventoryItem> simulationQueue = new ArrayList<>();
    private int simulationIndex = 0;

    // Thread-Safe Data Structures
    private final Set<String> scannedEpcSet = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, InventoryItem> localCatalog = new ConcurrentHashMap<>();
    private final List<InventoryItem> scannedItems = Collections.synchronizedList(new ArrayList<>());
    private final List<InventoryItem> pendingBatch = new CopyOnWriteArrayList<>();
    private InventoryAdapter adapter;

    // Managers & Clients
    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfile;
    private MiraPriceManager priceManager;
    private SharedPreferences offlineCache;
    private SharedPreferences pendingQueue;
    private InventoryDatabaseHelper dbHelper;

    // Handlers
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable timerRunnable;
    private Runnable batchUpdateRunnable;
    private boolean isBatchScheduled = false;

    // Hardware Settings & Sound
    private boolean soundEnabled = true;
    private boolean vibrateEnabled = true;
    private WebView printWebView;
    private android.media.SoundPool soundPool;
    private int soundScanId, soundFoundId, soundMissingId;

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
            priceManager = MiraPriceManager.getInstance(ctx);
            offlineCache = ctx.getSharedPreferences(OFFLINE_PREFS, Context.MODE_PRIVATE);
            pendingQueue = ctx.getSharedPreferences(PENDING_QUEUE_PREFS, Context.MODE_PRIVATE);
            dbHelper = InventoryDatabaseHelper.getInstance(ctx);
            initSoundPool();
        }
        isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");
        bindViews(v);
        setupTabs();
        setupRecyclerView();
        setupClickListeners();
        setInitialState();
        checkPendingQueue();
        return v;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        loadInventorySettings();
        updateBranchHeader();
        loadLastSnapshotOffline();
        loadCatalogFromLocalDatabase();
        preloadBranchCatalog();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadInventorySettings();
        updateBranchHeader();
        syncPendingQueue();
    }

    private void setupTabs() {
        if (tabInventoryModes == null) return;
        tabInventoryModes.removeAllTabs();
        tabInventoryModes.addTab(tabInventoryModes.newTab().setText(isArabic ? "📋 جرد كامل" : "📋 Full Audit"));
        tabInventoryModes.addTab(tabInventoryModes.newTab().setText(isArabic ? "⚡ جرد أعمى" : "⚡ Blind Audit"));
        tabInventoryModes.addTab(tabInventoryModes.newTab().setText(isArabic ? "📊 التقارير" : "📊 Reports"));
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
        tabInventoryModes = v.findViewById(R.id.tabInventoryModes);
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
        tvTotalValue = v.findViewById(R.id.tvTotalValue);
        tvTotalWeight = v.findViewById(R.id.tvTotalWeight);
        tvEmptyState = v.findViewById(R.id.tvEmptyState);

        cardComparison = v.findViewById(R.id.cardComparison);
        btnStartInventory = v.findViewById(R.id.btnStartInventory);
        btnBlindInventory = v.findViewById(R.id.btnBlindInventory);
        btnStopInventory = v.findViewById(R.id.btnStopInventory);
        btnGenerateReport = v.findViewById(R.id.btnGenerateReport);
        btnArchiveReports = v.findViewById(R.id.btnArchiveReports);
        rvInventoryItems = v.findViewById(R.id.rvInventoryItems);
        progressScanned = v.findViewById(R.id.progressScanned);
        progressRssi = v.findViewById(R.id.progressRssi);
        
        applyLanguage();
    }

    private void initSoundPool() {
        if (getContext() == null) return;
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                android.media.AudioAttributes audioAttributes = new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();

                soundPool = new android.media.SoundPool.Builder()
                        .setMaxStreams(5)
                        .setAudioAttributes(audioAttributes)
                        .build();
            } else {
                soundPool = new android.media.SoundPool(5, android.media.AudioManager.STREAM_MUSIC, 0);
            }

            soundScanId = soundPool.load(getContext(), R.raw.barcodebeep, 1);
            soundFoundId = soundPool.load(getContext(), R.raw.barcodebeep, 2);
            soundMissingId = soundPool.load(getContext(), R.raw.alarm_breach_security, 3);
        } catch (Exception e) {
            Log.e(TAG, "SoundPool init error: " + e.getMessage());
            soundPool = null;
        }
    }

    private void applyLanguage() {
        if (btnStartInventory != null) btnStartInventory.setText(isArabic ? "بدء الجرد" : "Start Audit");
        if (btnBlindInventory != null) btnBlindInventory.setText(isArabic ? "جرد أعمى" : "Blind Audit");
        if (btnStopInventory != null) btnStopInventory.setText(isArabic ? "إيقاف" : "Stop");
        if (btnGenerateReport != null) btnGenerateReport.setText(isArabic ? "تقرير" : "Report");
        if (btnArchiveReports != null) btnArchiveReports.setText(isArabic ? "الأرشيف" : "Archive");
    }

    private void setupRecyclerView() {
        adapter = new InventoryAdapter();
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

        if (tabInventoryModes != null) {
            tabInventoryModes.addOnTabSelectedListener(new com.google.android.material.tabs.TabLayout.OnTabSelectedListener() {
                @Override
                public void onTabSelected(com.google.android.material.tabs.TabLayout.Tab tab) {
                    handleTabSelection(tab.getPosition());
                }
                @Override public void onTabUnselected(com.google.android.material.tabs.TabLayout.Tab tab) {}
                @Override public void onTabReselected(com.google.android.material.tabs.TabLayout.Tab tab) {
                    handleTabSelection(tab.getPosition());
                }
            });
        }
    }

    private void handleTabSelection(int position) {
        switch (position) {
            case 0:
                isBlindMode = false;
                if (tvExpectedCount != null) {
                    tvExpectedCount.setVisibility(View.VISIBLE);
                    tvExpectedCount.setText(String.valueOf(expectedCount));
                }
                if (tvVariance != null) tvVariance.setVisibility(View.VISIBLE);
                if (btnStartInventory != null) btnStartInventory.setEnabled(!isScanning);
                if (btnBlindInventory != null) btnBlindInventory.setEnabled(!isScanning);
                if (rvInventoryItems != null) rvInventoryItems.setVisibility(View.VISIBLE);
                break;
            case 1:
                isBlindMode = true;
                if (tvExpectedCount != null) tvExpectedCount.setVisibility(View.GONE);
                if (tvVariance != null) tvVariance.setVisibility(View.GONE);
                if (btnStartInventory != null) btnStartInventory.setEnabled(!isScanning);
                if (btnBlindInventory != null) btnBlindInventory.setEnabled(false);
                if (rvInventoryItems != null) rvInventoryItems.setVisibility(View.VISIBLE);
                break;
            case 2:
                if (rvInventoryItems != null) rvInventoryItems.setVisibility(View.GONE);
                if (btnStartInventory != null) btnStartInventory.setEnabled(false);
                if (btnBlindInventory != null) btnBlindInventory.setEnabled(false);
                if (btnStopInventory != null) btnStopInventory.setEnabled(false);
                showSavedReports();
                break;
        }
    }

    private void setInitialState() {
        if (btnStartInventory != null) btnStartInventory.setEnabled(true);
        if (btnBlindInventory != null) btnBlindInventory.setEnabled(true);
        if (btnStopInventory != null) btnStopInventory.setEnabled(false);
        if (btnGenerateReport != null) btnGenerateReport.setEnabled(false);
        expectedCount = localCatalog.size();
        if (tvExpectedCount != null) tvExpectedCount.setText(String.valueOf(expectedCount));
        if (cardComparison != null) cardComparison.setVisibility(View.VISIBLE);
        if (progressRssi != null) progressRssi.setMax(100);
    }

    // ==================== DATABASE CATALOG PRELOAD ====================

    /**
     * تحميل الكتالوج من قاعدة البيانات المحلية SQLite
     */
    private void loadCatalogFromLocalDatabase() {
        if (dbHelper == null) return;
        
        new Thread(() -> {
            try {
                SQLiteDatabase db = dbHelper.getReadableDatabase();
                List<InventoryItem> items = new ArrayList<>();
                
                Cursor cursor = db.query(
                    InventoryDatabaseHelper.TABLE_ITEMS,
                    null,
                    InventoryDatabaseHelper.COLUMN_BRANCH_ID + " = ?",
                    new String[]{String.valueOf(branchesManager != null ? branchesManager.getActiveBranchId() : 0)},
                    null, null,
                    InventoryDatabaseHelper.COLUMN_TITLE + " ASC"
                );
                
                if (cursor != null && cursor.moveToFirst()) {
                    do {
                        String serial = cursor.getString(cursor.getColumnIndexOrThrow(InventoryDatabaseHelper.COLUMN_SERIAL));
                        String title = cursor.getString(cursor.getColumnIndexOrThrow(InventoryDatabaseHelper.COLUMN_TITLE));
                        String karat = cursor.getString(cursor.getColumnIndexOrThrow(InventoryDatabaseHelper.COLUMN_KARAT));
                        double weight = cursor.getDouble(cursor.getColumnIndexOrThrow(InventoryDatabaseHelper.COLUMN_WEIGHT));
                        String status = cursor.getString(cursor.getColumnIndexOrThrow(InventoryDatabaseHelper.COLUMN_STATUS));
                        String location = cursor.getString(cursor.getColumnIndexOrThrow(InventoryDatabaseHelper.COLUMN_LOCATION));
                        
                        items.add(new InventoryItem(serial, title, karat, weight, status, location));
                    } while (cursor.moveToNext());
                    cursor.close();
                }
                
                if (!items.isEmpty()) {
                    localCatalog.clear();
                    for (InventoryItem item : items) {
                        localCatalog.put(item.serial.toUpperCase(), item);
                    }
                    expectedCount = localCatalog.size();
                    
                    safeRun(() -> {
                        if (tvExpectedCount != null && !isBlindMode) {
                            tvExpectedCount.setText(String.valueOf(expectedCount));
                        }
                        Log.d(TAG, "📦 Loaded " + expectedCount + " items from local database");
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Load local catalog error: " + e.getMessage());
            }
        }).start();
    }

    /**
     * تحميل الكتالوج من السيرفر وتحديث قاعدة البيانات المحلية
     */
    private void preloadBranchCatalog() {
        if (apiClient == null || branchesManager == null) return;
        final int activeBranchId = branchesManager.getActiveBranchId();

        new Thread(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getBranchItemsList(activeBranchId);
                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray items = response.data.optJSONArray("items");
                    if (items != null) {
                        localCatalog.clear();
                        List<InventoryItem> itemsToSave = new ArrayList<>();
                        
                        for (int i = 0; i < items.length(); i++) {
                            try {
                                JSONObject obj = items.getJSONObject(i);
                                String serial = obj.optString("gtin13", obj.optString("serial_number", obj.optString("epc", "")));
                                if (!serial.isEmpty()) {
                                    String title = obj.optString("title", obj.optString("name", isArabic ? "قطعة ذهب" : "Gold Item"));
                                    String karat = obj.optString("karat", "21K");
                                    double weight = obj.optDouble("weight", 0.0);
                                    String location = obj.optString("location", branchesManager.getActiveBranchDisplayName());
                                    String status = !"sold".equalsIgnoreCase(obj.optString("status", "")) ? "✓" : "⚠️";
                                    
                                    InventoryItem item = new InventoryItem(serial, title, karat, weight, status, location);
                                    localCatalog.put(serial.toUpperCase(), item);
                                    itemsToSave.add(item);
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Parse item error: " + e.getMessage());
                            }
                        }
                        
                        expectedCount = localCatalog.size();
                        
                        // حفظ في قاعدة البيانات المحلية
                        saveItemsToLocalDatabase(itemsToSave);
                        
                        safeRun(() -> {
                            if (tvExpectedCount != null && !isBlindMode) {
                                tvExpectedCount.setText(String.valueOf(expectedCount));
                            }
                            Log.d(TAG, "📦 Loaded " + expectedCount + " items from server");
                        });
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Catalog Preload Error: " + e.getMessage());
                // في حالة فشل السيرفر، نعتمد على قاعدة البيانات المحلية
                loadCatalogFromLocalDatabase();
            }
        }).start();
    }

    /**
     * حفظ العناصر في قاعدة البيانات المحلية
     */
    private void saveItemsToLocalDatabase(List<InventoryItem> items) {
        if (dbHelper == null || items.isEmpty()) return;
        
        new Thread(() -> {
            try {
                SQLiteDatabase db = dbHelper.getWritableDatabase();
                db.beginTransaction();
                
                try {
                    // حذف العناصر القديمة للفرع الحالي
                    db.delete(
                        InventoryDatabaseHelper.TABLE_ITEMS,
                        InventoryDatabaseHelper.COLUMN_BRANCH_ID + " = ?",
                        new String[]{String.valueOf(branchesManager.getActiveBranchId())}
                    );
                    
                    // إضافة العناصر الجديدة
                    for (InventoryItem item : items) {
                        android.content.ContentValues values = new android.content.ContentValues();
                        values.put(InventoryDatabaseHelper.COLUMN_SERIAL, item.serial);
                        values.put(InventoryDatabaseHelper.COLUMN_TITLE, item.title);
                        values.put(InventoryDatabaseHelper.COLUMN_KARAT, item.karat);
                        values.put(InventoryDatabaseHelper.COLUMN_WEIGHT, item.numericWeight);
                        values.put(InventoryDatabaseHelper.COLUMN_STATUS, item.status);
                        values.put(InventoryDatabaseHelper.COLUMN_LOCATION, item.location);
                        values.put(InventoryDatabaseHelper.COLUMN_BRANCH_ID, branchesManager.getActiveBranchId());
                        values.put(InventoryDatabaseHelper.COLUMN_UPDATED_AT, System.currentTimeMillis());
                        
                        db.insert(InventoryDatabaseHelper.TABLE_ITEMS, null, values);
                    }
                    
                    db.setTransactionSuccessful();
                    Log.d(TAG, "💾 Saved " + items.size() + " items to local database");
                } finally {
                    db.endTransaction();
                }
            } catch (Exception e) {
                Log.e(TAG, "Save to local database error: " + e.getMessage());
            }
        }).start();
    }

    private void preloadBranchCatalogSync() {
        if (apiClient == null || branchesManager == null) return;
        final int activeBranchId = branchesManager.getActiveBranchId();
        
        // محاولة التحميل من قاعدة البيانات المحلية أولاً
        loadCatalogFromLocalDatabaseSync();
        
        // ثم محاولة التحديث من السيرفر
        MiraApiClient.ApiResponse response = apiClient.getBranchItemsList(activeBranchId);
        if (response != null && response.isSuccess && response.data != null) {
            JSONArray items = response.data.optJSONArray("items");
            if (items != null) {
                localCatalog.clear();
                List<InventoryItem> itemsToSave = new ArrayList<>();
                
                for (int i = 0; i < items.length(); i++) {
                    try {
                        JSONObject obj = items.getJSONObject(i);
                        String serial = obj.optString("gtin13", obj.optString("serial_number", obj.optString("epc", "")));
                        if (!serial.isEmpty()) {
                            InventoryItem item = new InventoryItem(
                                serial,
                                obj.optString("title", isArabic ? "قطعة ذهب" : "Gold Item"),
                                obj.optString("karat", "21K"),
                                obj.optDouble("weight", 0.0),
                                "✓",
                                branchesManager.getActiveBranchDisplayName()
                            );
                            localCatalog.put(serial.toUpperCase(), item);
                            itemsToSave.add(item);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Parse item sync error: " + e.getMessage());
                    }
                }
                expectedCount = localCatalog.size();
                saveItemsToLocalDatabase(itemsToSave);
            }
        }
    }

    private void loadCatalogFromLocalDatabaseSync() {
        if (dbHelper == null) return;
        
        try {
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            List<InventoryItem> items = new ArrayList<>();
            
            Cursor cursor = db.query(
                InventoryDatabaseHelper.TABLE_ITEMS,
                null,
                InventoryDatabaseHelper.COLUMN_BRANCH_ID + " = ?",
                new String[]{String.valueOf(branchesManager.getActiveBranchId())},
                null, null,
                InventoryDatabaseHelper.COLUMN_TITLE + " ASC"
            );
            
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    String serial = cursor.getString(cursor.getColumnIndexOrThrow(InventoryDatabaseHelper.COLUMN_SERIAL));
                    String title = cursor.getString(cursor.getColumnIndexOrThrow(InventoryDatabaseHelper.COLUMN_TITLE));
                    String karat = cursor.getString(cursor.getColumnIndexOrThrow(InventoryDatabaseHelper.COLUMN_KARAT));
                    double weight = cursor.getDouble(cursor.getColumnIndexOrThrow(InventoryDatabaseHelper.COLUMN_WEIGHT));
                    String status = cursor.getString(cursor.getColumnIndexOrThrow(InventoryDatabaseHelper.COLUMN_STATUS));
                    String location = cursor.getString(cursor.getColumnIndexOrThrow(InventoryDatabaseHelper.COLUMN_LOCATION));
                    
                    items.add(new InventoryItem(serial, title, karat, weight, status, location));
                } while (cursor.moveToNext());
                cursor.close();
            }
            
            if (!items.isEmpty()) {
                localCatalog.clear();
                for (InventoryItem item : items) {
                    localCatalog.put(item.serial.toUpperCase(), item);
                }
                expectedCount = localCatalog.size();
            }
        } catch (Exception e) {
            Log.e(TAG, "Load local catalog sync error: " + e.getMessage());
        }
    }

    private void loadLastSnapshotOffline() {
        try {
            prevTotalItems = offlineCache.getInt("prev_total_items", 0);
            if (tvPrevItems != null) tvPrevItems.setText(String.valueOf(prevTotalItems));
            updateComparisonCard();
        } catch (Exception ignored) {}
    }

    // ==================== HIGH-SPEED TAG PROCESSING ====================

    public void processScannedTag(String epcOrGtin, int rssi) {
        processScannedTag(epcOrGtin, String.valueOf(rssi));
    }

    public void processScannedTag(String epcOrGtin, String rssi) {
        if (!isScanning || epcOrGtin == null || epcOrGtin.trim().isEmpty()) return;
        final String cleanCode = epcOrGtin.trim().toUpperCase();
        if (!scannedEpcSet.add(cleanCode)) return;

        InventoryItem matchedItem = localCatalog.get(cleanCode);
        boolean isKnown = matchedItem != null;

        if (!isKnown && !isBlindMode) {
            if (localCatalog.isEmpty()) {
                safeRun(() -> {
                    Toast.makeText(getContext(),
                        isArabic ? "⚠️ قاعدة البيانات فارغة - جاري القراءة بدون تحقق" : 
                                   "⚠️ Database empty - scanning without verification",
                        Toast.LENGTH_SHORT).show();
                });
            } else {
                return;
            }
        }

        String title = isKnown ? matchedItem.title : (isArabic ? "قطعة غير معرفة" : "Unknown Item");
        String karat = isKnown ? matchedItem.karat : "21K";
        double weight = isKnown ? matchedItem.numericWeight : 0.0;
        String location = isKnown ? matchedItem.location : (branchesManager != null ? branchesManager.getActiveBranchDisplayName() : "");
        String status = isKnown ? matchedItem.status : "⚠️";

        InventoryItem newItem = new InventoryItem(cleanCode, title, karat, weight, status, location);
        scannedItems.add(0, newItem);
        pendingBatch.add(0, newItem);
        scannedCount++;
        if ("✓".equals(status)) foundCount++;

        playInventorySound(isKnown ? "found" : "missing");
        safeRun(() -> {
            if (tvLastScan != null) tvLastScan.setText(cleanCode);
            updateRssiDisplay(rssi);
        });
        scheduleBatchUpdate();
    }

    private void scheduleBatchUpdate() {
        if (isBatchScheduled || !isAdded()) return;
        isBatchScheduled = true;

        batchUpdateRunnable = () -> {
            isBatchScheduled = false;

            if (!isAdded() || getActivity() == null || getActivity().isFinishing()) return;

            try {
                if (adapter != null && !pendingBatch.isEmpty()) {
                    List<InventoryItem> batchCopy = new ArrayList<>(pendingBatch);
                    pendingBatch.clear();
                    adapter.addItems(batchCopy);
                }

                if (tvScannedCount != null) tvScannedCount.setText(String.valueOf(scannedCount));
                int remaining = Math.max(0, expectedCount - foundCount);
                if (tvVariance != null) tvVariance.setText(String.valueOf(remaining));

                if (progressScanned != null && expectedCount > 0) {
                    progressScanned.setProgress(Math.min(100, (int) ((scannedCount * 100.0) / expectedCount)));
                }

                double acc = expectedCount > 0 ? (foundCount * 100.0 / expectedCount) : 100.0;
                if (tvAccuracy != null) {
                    tvAccuracy.setText(String.format(Locale.US, isArabic ? "الدقة: %.1f%%" : "Accuracy: %.1f%%", acc));
                }

                updateTotals();
                updateComparisonCard();
                checkAutoStopCondition();
            } catch (Exception e) {
                Log.e(TAG, "Batch update error: " + e.getMessage());
            }
        };

        mainHandler.postDelayed(batchUpdateRunnable, UI_UPDATE_INTERVAL);
    }

    private void checkAutoStopCondition() {
        if (!isBlindMode && expectedCount > 0 && foundCount >= expectedCount && isScanning) {
            safeRun(() -> {
                stopSimulationLoop();
                Toast.makeText(getContext(),
                    isArabic ? "✅ اكتمل جرد جميع القطع المتوقعة تلقائياً!" : "✅ All expected items scanned successfully!",
                    Toast.LENGTH_LONG).show();
                finishInventory();
            });
        }
    }

    private void updateRssiDisplay(String rssi) {
        if (tvRssi != null) tvRssi.setText(rssi + " dBm");

        if (progressRssi != null) {
            try {
                int rssiValue = Integer.parseInt(rssi.replaceAll("[^0-9-]", ""));
                int percentage = Math.max(0, Math.min(100, (rssiValue + 100) * 100 / 70));
                progressRssi.setProgress(percentage);

                if (percentage > 70) {
                    progressRssi.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#22C55E")));
                } else if (percentage > 40) {
                    progressRssi.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#F59E0B")));
                } else {
                    progressRssi.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#EF4444")));
                }
            } catch (Exception e) {
                progressRssi.setProgress(0);
            }
        }
    }

    private void updateTotals() {
        double totalWt = calculateTotalWeight();
        double totalVal = calculateTotalValue();

        if (tvTotalWeight != null) {
            tvTotalWeight.setText(String.format(Locale.US, "%.2f g", totalWt));
        }

        if (tvTotalValue != null && priceManager != null) {
            tvTotalValue.setText(priceManager.formatPriceDefault(totalVal));
        }
    }

    // ==================== REAL DATABASE-DRIVEN SIMULATION ====================

    /**
     * بدء محاكاة حقيقية من قاعدة البيانات
     * تقوم بقراءة العناصر الفعلية من قاعدة البيانات وتحاكي حركة الماسح الضوئي
     */
    private void startSimulationLoop() {
        if (simulationHandler == null) {
            simulationHandler = new Handler(Looper.getMainLooper());
        }

        // تجهيز قائمة المحاكاة من العناصر الفعلية
        simulationQueue.clear();
        simulationQueue.addAll(localCatalog.values());
        
        // خلط العناصر لمحاكاة واقعية
        Collections.shuffle(simulationQueue);
        simulationIndex = 0;

        if (simulationQueue.isEmpty()) {
            Toast.makeText(getContext(), 
                isArabic ? "⚠️ لا توجد عناصر في قاعدة البيانات للمحاكاة" : 
                           "⚠️ No items in database for simulation", 
                Toast.LENGTH_SHORT).show();
            return;
        }

        isSimulationMode = true;
        Log.d(TAG, "🎯 Starting real database simulation with " + simulationQueue.size() + " items");

        simulationRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isScanning || !isSimulationMode) return;

                if (simulationIndex < simulationQueue.size()) {
                    // محاكاة قراءة دفعة من العناصر
                    int batchEnd = Math.min(simulationIndex + SIMULATION_BATCH_SIZE, simulationQueue.size());
                    
                    for (int i = simulationIndex; i < batchEnd; i++) {
                        InventoryItem item = simulationQueue.get(i);
                        // توليد RSSI واقعي بناءً على المسافة المحاكاة
                        int randomRssi = generateRealisticRssi(i);
                        processScannedTag(item.serial, randomRssi);
                    }
                    
                    simulationIndex = batchEnd;
                    
                    // تأخير واقعي بين الدفعات
                    long delay = SIMULATION_MIN_DELAY + (long) (Math.random() * (SIMULATION_MAX_DELAY - SIMULATION_MIN_DELAY));
                    simulationHandler.postDelayed(this, delay);
                } else {
                    Log.d(TAG, "✅ Real database simulation completed. Scanned " + simulationIndex + " items");
                    isSimulationMode = false;
                }
            }
        };

        simulationHandler.postDelayed(simulationRunnable, 500);
    }

    /**
     * توليد RSSI واقعي بناءً على المسافة المحاكاة
     */
    private int generateRealisticRssi(int index) {
        // محاكاة مسافة متفاوتة تؤثر على قوة الإشارة
        double distance = 0.5 + (Math.random() * 3.0); // 0.5 إلى 3.5 متر
        int baseRssi = -40; // قوة الإشارة الأساسية على مسافة 1 متر
        int pathLoss = (int) (20 * Math.log10(distance)); // معادلة فقدان المسار
        int rssi = baseRssi - pathLoss;
        
        // إضافة تشويش عشوائي
        rssi += (int) (Math.random() * 10) - 5;
        
        // تحديد النطاق المسموح
        return Math.max(-90, Math.min(-30, rssi));
    }

    private void stopSimulationLoop() {
        isSimulationMode = false;
        if (simulationHandler != null && simulationRunnable != null) {
            simulationHandler.removeCallbacks(simulationRunnable);
        }
        simulationQueue.clear();
        simulationIndex = 0;
    }

    // ==================== START & STOP INVENTORY ====================

    private void startInventory(boolean blindMode) {
        Context ctx = getContext();
        if (ctx == null || !isAdded()) return;

        final AlertDialog loadingDialog = showProfessionalLoadingDialog(
            isArabic ? "🔄 جاري تجهيز الجرد..." : "🔄 Preparing audit...",
            isArabic ? "يرجى الانتظار - جاري تحميل قاعدة البيانات" : "Please wait - Loading database"
        );

        new Thread(() -> {
            try {
                // محاولة التحميل من قاعدة البيانات المحلية أولاً
                loadCatalogFromLocalDatabaseSync();
                
                // إذا كانت قاعدة البيانات المحلية فارغة، حاول التحميل من السيرفر
                if (localCatalog.isEmpty()) {
                    preloadBranchCatalogSync();
                }
                
                // التحقق من وجود بيانات بعد المحاولة
                if (localCatalog.isEmpty()) {
                    safeRun(() -> {
                        dismissDialog(loadingDialog);
                        showRetryDialog(
                            isArabic ? "⚠️ قاعدة البيانات فارغة" : "⚠️ Database is empty",
                            isArabic ? "لم يتم العثور على عناصر في قاعدة البيانات. هل تريد المتابعة بدون تحقق؟" : 
                                       "No items found in database. Continue without verification?",
                            () -> startInventoryWithoutCatalog(blindMode)
                        );
                    });
                    return;
                }

                safeRun(() -> {
                    dismissDialog(loadingDialog);
                    initializeInventorySession(blindMode);
                });
            } catch (Exception e) {
                safeRun(() -> {
                    dismissDialog(loadingDialog);
                    showRetryDialog(
                        isArabic ? "❌ فشل تجهيز الجرد" : "❌ Failed to prepare audit",
                        e.getMessage(),
                        () -> startInventory(blindMode)
                    );
                });
            }
        }).start();
    }

    private void startInventoryWithoutCatalog(boolean blindMode) {
        localCatalog.clear();
        safeRun(() -> initializeInventorySession(blindMode));
    }

    private void initializeInventorySession(boolean blindMode) {
        sessionId = (int) (System.currentTimeMillis() / 1000);
        isBlindMode = blindMode;
        isScanning = true;
        startTime = System.currentTimeMillis();

        scannedEpcSet.clear();
        scannedItems.clear();
        pendingBatch.clear();
        scannedCount = 0;
        foundCount = 0;
        varianceCount = 0;

        if (adapter != null) adapter.clearItems();

        setButtonsEnabled(false);
        if (btnStopInventory != null) btnStopInventory.setEnabled(true);

        if (tvInventoryStatus != null) {
            tvInventoryStatus.setText(isArabic ? "● جاري الجرد" : "● SCANNING");
            tvInventoryStatus.setTextColor(Color.parseColor("#38BDF8"));
        }

        if (isBlindMode && tvExpectedCount != null) {
            tvExpectedCount.setText("—");
        } else if (tvExpectedCount != null) {
            tvExpectedCount.setText(String.valueOf(expectedCount));
        }

        playInventorySound("start");
        startTimer();

        // بدء المحاكاة الحقيقية إذا لم يكن هناك جهاز متصل
        if (!isHardwareConnected && !localCatalog.isEmpty()) {
            startSimulationLoop();
        }
    }

    private void stopInventory() {
        Context ctx = getContext();
        if (ctx == null || !isAdded()) return;

        try {
            AlertDialog.Builder confirmDialog = new AlertDialog.Builder(ctx);
            confirmDialog.setTitle(isArabic ? "⚠️ إيقاف الجرد؟" : "⚠️ Stop Audit?");
            confirmDialog.setMessage(isArabic ?
                "هل أنت متأكد من إيقاف الجرد؟\nسيتم حفظ التقرير وإرساله مع لقطة الشاشة." :
                "Are you sure you want to stop?\nReport and screenshot will be saved.");
            confirmDialog.setPositiveButton(isArabic ? "نعم، إيقاف" : "Yes, Stop", (d, w) -> {
                d.dismiss();
                finishInventory();
            });
            confirmDialog.setNegativeButton(isArabic ? "متابعة" : "Continue", (d, w) -> d.dismiss());
            confirmDialog.setCancelable(false);
            confirmDialog.show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing stop dialog: " + e.getMessage());
        }
    }

    private void finishInventory() {
        Context ctx = getContext();
        if (ctx == null || !isAdded()) return;

        stopSimulationLoop();

        final AlertDialog loadingDialog = showProfessionalLoadingDialog(
            isArabic ? "🔄 جاري إيقاف الجرد..." : "🔄 Stopping audit...",
            isArabic ? "حفظ التقرير والتقاط لقطة الشاشة" : "Saving report and capturing screenshot"
        );

        new Thread(() -> {
            try {
                isScanning = false;
                varianceCount = Math.max(0, expectedCount - foundCount);

                final String screenshotBase64 = captureScreenshot();
                boolean synced = syncFullInventorySessionSync(screenshotBase64);

                safeRun(() -> {
                    dismissDialog(loadingDialog);

                    if (btnStartInventory != null) btnStartInventory.setEnabled(true);
                    if (btnBlindInventory != null) btnBlindInventory.setEnabled(true);
                    if (btnStopInventory != null) btnStopInventory.setEnabled(false);
                    if (btnGenerateReport != null) btnGenerateReport.setEnabled(true);

                    if (tvInventoryStatus != null) {
                        if (synced) {
                            tvInventoryStatus.setText(isArabic ? "● مكتمل" : "● COMPLETED");
                            tvInventoryStatus.setTextColor(Color.parseColor("#22C55E"));
                        } else {
                            tvInventoryStatus.setText(isArabic ? "● محفوظ محلياً" : "● SAVED LOCALLY");
                            tvInventoryStatus.setTextColor(Color.parseColor("#F59E0B"));
                        }
                    }

                    if (tvVariance != null) tvVariance.setText(String.valueOf(varianceCount));
                    updateComparisonCard();
                    updateTotals();
                    stopTimer();
                    playInventorySound("complete");
                    showSuccessDialog(synced);
                });
            } catch (Exception e) {
                safeRun(() -> {
                    dismissDialog(loadingDialog);
                    showRetryDialog(
                        isArabic ? "❌ فشل إيقاف الجرد" : "❌ Failed to stop audit",
                        e.getMessage(),
                        this::finishInventory
                    );
                });
            }
        }).start();
    }

    // ... [باقي الدوال تبقى كما هي من الكود السابق]

    // ==================== SCREENSHOT & SYNC ====================

    private String captureScreenshot() {
        try {
            if (getActivity() == null || getView() == null) return null;

            View rootView = getView();
            Bitmap bitmap = Bitmap.createBitmap(rootView.getWidth(), rootView.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            rootView.draw(canvas);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, SCREENSHOT_QUALITY, baos);
            byte[] imageBytes = baos.toByteArray();
            bitmap.recycle();

            return Base64.encodeToString(imageBytes, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "Screenshot error: " + e.getMessage());
            return null;
        }
    }

    private boolean syncFullInventorySessionSync(String screenshotBase64) {
        try {
            JSONObject payload = createInventoryPayload();
            if (screenshotBase64 != null && !screenshotBase64.isEmpty()) {
                payload.put("screenshot_base64", screenshotBase64);
            }

            MiraApiClient.ApiResponse response = apiClient.closeInventorySession(payload);
            if (response != null && response.isSuccess) {
                if (response.data != null) {
                    int serverSessionId = response.data.optInt("session_id", 0);
                    if (serverSessionId > 0) sessionId = serverSessionId;
                }
                saveSnapshotLocally(payload);
                return true;
            } else {
                saveToPendingQueue(payload);
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Sync error: " + e.getMessage());
            try { saveToPendingQueue(createInventoryPayload()); } catch (Exception ignored) {}
            return false;
        }
    }

    private JSONObject createInventoryPayload() throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("session_id", sessionId);
        payload.put("branch_id", branchesManager.getActiveBranchId());
        payload.put("zone", branchesManager.getActiveBranchDisplayName());
        payload.put("location", branchesManager.getActiveBranchDisplayName());
        payload.put("expected_count", expectedCount);
        payload.put("total_scanned", scannedCount);
        payload.put("unique_count", foundCount);
        payload.put("found_count", foundCount);
        payload.put("missing_count", varianceCount);
        payload.put("unexpected_count", 0);
        payload.put("total_weight", calculateTotalWeight());
        payload.put("total_value", calculateTotalValue());
        payload.put("connection_type", isHardwareConnected ? "HARDWARE_RFID" : (isSimulationMode ? "SIMULATION_DATABASE" : "SIMULATOR"));

        if (getContext() != null) {
            SharedPreferences authPrefs = getContext().getSharedPreferences("MIRA_AUTH", Context.MODE_PRIVATE);
            int userId = authPrefs.getInt("user_id", authPrefs.getInt("userId", 0));
            if (userId > 0) payload.put("user_id", userId);
            String token = authPrefs.getString("auth_token", authPrefs.getString("session_token", ""));
            if (!token.isEmpty()) payload.put("token", token);
        }

        JSONArray itemsJson = new JSONArray();
        synchronized (scannedItems) {
            for (InventoryItem item : scannedItems) {
                JSONObject obj = new JSONObject();
                obj.put("code", item.serial);
                obj.put("serial", item.serial);
                obj.put("title", item.title);
                obj.put("karat", item.karat);
                obj.put("weight", item.numericWeight);
                obj.put("status", item.status);
                itemsJson.put(obj);
            }
        }
        payload.put("items", itemsJson);

        JSONArray epcsArray = new JSONArray();
        synchronized (scannedItems) {
            for (InventoryItem item : scannedItems) {
                epcsArray.put(item.serial);
            }
        }
        payload.put("scanned_epcs", epcsArray);

        return payload;
    }

    // ... [باقي الدوال تبقى كما هي]

    private void saveSnapshotLocally(JSONObject payload) {
        if (offlineCache != null) {
            offlineCache.edit()
                .putString("last_snapshot", payload.toString())
                .putInt("prev_total_items", foundCount)
                .putLong("snapshot_time", System.currentTimeMillis())
                .apply();
        }
    }

    private void saveToPendingQueue(JSONObject payload) {
        if (pendingQueue == null) return;
        try {
            String existingJson = pendingQueue.getString("pending_audits", "[]");
            JSONArray pendingArray = new JSONArray(existingJson);
            pendingArray.put(payload);
            while (pendingArray.length() > 20) pendingArray.remove(0);
            pendingQueue.edit().putString("pending_audits", pendingArray.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "Failed to save pending queue: " + e.getMessage());
        }
    }

    private void checkPendingQueue() {
        if (pendingQueue == null) return;
        try {
            String existingJson = pendingQueue.getString("pending_audits", "[]");
            JSONArray pendingArray = new JSONArray(existingJson);
            if (pendingArray.length() > 0) {
                safeRun(() -> Toast.makeText(getContext(),
                    isArabic ? "📋 يوجد " + pendingArray.length() + " جرد معلق للمزامنة" :
                               "📋 " + pendingArray.length() + " pending audits to sync",
                    Toast.LENGTH_LONG).show());
            }
        } catch (Exception e) {
            Log.e(TAG, "Check pending queue error: " + e.getMessage());
        }
    }

    private void syncPendingQueue() {
        if (pendingQueue == null || apiClient == null) return;
        new Thread(() -> {
            try {
                String existingJson = pendingQueue.getString("pending_audits", "[]");
                JSONArray pendingArray = new JSONArray(existingJson);
                if (pendingArray.length() == 0) return;

                JSONArray remainingArray = new JSONArray();
                int syncedCount = 0;

                for (int i = 0; i < pendingArray.length(); i++) {
                    try {
                        JSONObject audit = pendingArray.getJSONObject(i);
                        MiraApiClient.ApiResponse response = apiClient.closeInventorySession(audit);
                        if (response != null && response.isSuccess) {
                            syncedCount++;
                            saveSnapshotLocally(audit);
                        } else {
                            remainingArray.put(audit);
                        }
                    } catch (Exception e) {
                        try { remainingArray.put(pendingArray.getJSONObject(i)); } catch (Exception ignored) {}
                    }
                }

                pendingQueue.edit().putString("pending_audits", remainingArray.toString()).apply();
                final int synced = syncedCount;
                safeRun(() -> {
                    if (synced > 0) {
                        Toast.makeText(getContext(),
                            isArabic ? "✅ تمت مزامنة " + synced + " جرد" : "✅ Synced " + synced + " audits",
                            Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Sync pending error: " + e.getMessage());
            }
        }).start();
    }

    // ... [باقي الدوال تبقى كما هي من الكود السابق]

    private void showSavedReports() {
        Context ctx = getContext();
        if (ctx == null || apiClient == null) return;

        Toast.makeText(ctx, isArabic ? "🔄 جاري تحميل التقارير..." : "🔄 Loading reports...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                int branchId = branchesManager != null ? branchesManager.getActiveBranchId() : 0;
                MiraApiClient.ApiResponse response = apiClient.getInventoryReports(branchId, 1, 50);

                if (response != null && response.isSuccess && response.data != null) {
                    JSONObject data = response.data.optJSONObject("data");
                    if (data != null) {
                        JSONArray sessionsArr = data.optJSONArray("sessions");
                        if (sessionsArr != null && sessionsArr.length() > 0) {
                            List<String> reportList = new ArrayList<>();
                            List<JSONObject> sessionObjects = new ArrayList<>();

                            for (int i = 0; i < sessionsArr.length(); i++) {
                                JSONObject session = sessionsArr.optJSONObject(i);
                                if (session != null) {
                                    sessionObjects.add(session);
                                    int id = session.optInt("id", 0);
                                    String date = session.optString("closed_at", session.optString("started_at", ""));
                                    int expected = session.optInt("expected_count", 0);
                                    int scanned = session.optInt("scanned_count", 0);
                                    double accuracy = session.optDouble("accuracy", 0);
                                    String riskLevel = session.optString("risk_level", "none");

                                    String riskEmoji = "none".equals(riskLevel) ? "✅" : "critical".equals(riskLevel) ? "🔴" : "🟢";
                                    reportList.add(String.format(Locale.US, "%s جلسة #%d | %s | المتوقع: %d | الممسوح: %d | الدقة: %.1f%%",
                                            riskEmoji, id, date, expected, scanned, accuracy));
                                }
                            }

                            safeRun(() -> new AlertDialog.Builder(requireContext())
                                .setTitle(isArabic ? "📊 تقارير الجرد" : "📊 Inventory Reports")
                                .setItems(reportList.toArray(new String[0]), (dialog, which) -> {
                                    if (which < sessionObjects.size()) showReportDetail(sessionObjects.get(which));
                                })
                                .setPositiveButton(isArabic ? "🖨️ طباعة آخر تقرير" : "🖨️ Print Last Report", (d, w) -> {
                                    d.dismiss();
                                    generateProReport();
                                })
                                .setNegativeButton(isArabic ? "إغلاق" : "Close", null)
                                .show());
                            return;
                        }
                    }
                }
                safeRun(() -> Toast.makeText(getContext(), isArabic ? "لا توجد تقارير سابقة" : "No previous reports", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                Log.e(TAG, "Show reports error: " + e.getMessage());
            }
        }).start();
    }

    private void showReportDetail(JSONObject session) {
        try {
            int id = session.optInt("id", 0);
            String date = session.optString("closed_at", session.optString("started_at", ""));
            String location = session.optString("location", "");
            int expected = session.optInt("expected_count", 0);
            int scanned = session.optInt("scanned_count", 0);
            int missing = session.optInt("missing_count", 0);
            double accuracy = session.optDouble("accuracy", 0);

            String message = isArabic ?
                "📋 تفاصيل الجلسة #" + id + "\n\n" +
                "📅 التاريخ: " + date + "\n" +
                "📍 الموقع: " + location + "\n" +
                "📦 المتوقع: " + expected + "\n" +
                "✅ الممسوح: " + scanned + "\n" +
                "⚠️ العجز: " + missing + "\n" +
                "📊 الدقة: " + accuracy + "%\n" :
                "📋 Session #" + id + " Details\n\n" +
                "📅 Date: " + date + "\n" +
                "📍 Location: " + location + "\n" +
                "📦 Expected: " + expected + "\n" +
                "✅ Scanned: " + scanned + "\n" +
                "⚠️ Missing: " + missing + "\n" +
                "📊 Accuracy: " + accuracy + "%\n";

            new AlertDialog.Builder(requireContext())
                .setTitle(isArabic ? "📋 تفاصيل التقرير" : "📋 Report Details")
                .setMessage(message)
                .setPositiveButton(isArabic ? "إغلاق" : "Close", null)
                .show();
        } catch (Exception e) {
            Log.e(TAG, "Report detail error: " + e.getMessage());
        }
    }

    // ==================== AUXILIARY METHODS ====================

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
            mainHandler.postDelayed(timerRunnable, 1000);
        };
        mainHandler.post(timerRunnable);
    }

    private void stopTimer() {
        if (timerRunnable != null) mainHandler.removeCallbacks(timerRunnable);
    }

    private double calculateTotalWeight() {
        double total = 0;
        synchronized (scannedItems) {
            for (InventoryItem item : scannedItems) {
                total += item.numericWeight;
            }
        }
        return Math.round(total * 1000.0) / 1000.0;
    }

    private double calculateTotalValue() {
        double totalValue = 0;
        synchronized (scannedItems) {
            for (InventoryItem item : scannedItems) {
                int karatNumber = getKaratNumber(item.karat);
                double pricePerGram = priceManager != null ? priceManager.getGoldPricePerGram(karatNumber) : 65.0;
                totalValue += item.numericWeight * pricePerGram;
            }
        }
        return Math.round(totalValue * 100.0) / 100.0;
    }

    private int getKaratNumber(String karat) {
        if (karat == null) return 21;
        switch (karat.toUpperCase()) {
            case "24K": return 24;
            case "22K": return 22;
            case "21K": return 21;
            case "18K": return 18;
            case "14K": return 14;
            default: return 21;
        }
    }

    private void playInventorySound(String type) {
        if (!soundEnabled || soundPool == null || getContext() == null) return;
        try {
            AudioManager audioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                if (currentVolume == 0) return;
            }

            switch (type) {
                case "scan": soundPool.play(soundScanId, 0.5f, 0.5f, 1, 0, 1f); break;
                case "found":
                    soundPool.play(soundFoundId, 0.7f, 0.7f, 1, 0, 1f);
                    if (vibrateEnabled) vibrate(30);
                    break;
                case "missing":
                    soundPool.play(soundMissingId, 1.0f, 1.0f, 1, 0, 1f);
                    if (vibrateEnabled) vibrate(150);
                    break;
                case "start":
                    soundPool.play(soundScanId, 0.3f, 0.3f, 1, 0, 1f);
                    if (vibrateEnabled) vibrate(50);
                    break;
                case "complete":
                    soundPool.play(soundFoundId, 1.0f, 1.0f, 1, 0, 1f);
                    if (vibrateEnabled) vibrate(200);
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Play sound error: " + e.getMessage());
        }
    }

    private void vibrate(long duration) {
        Context ctx = getContext();
        if (ctx == null) return;
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastVibrationTime < VIBRATION_THROTTLE_MS) return;
        lastVibrationTime = currentTime;

        try {
            android.os.Vibrator vib = (android.os.Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (vib != null && vib.hasVibrator()) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vib.vibrate(android.os.VibrationEffect.createOneShot(duration, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vib.vibrate(duration);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Vibration error: " + e.getMessage());
        }
    }

    private void loadInventorySettings() {
        Context ctx = getContext();
        if (ctx == null) return;
        SharedPreferences p = ctx.getSharedPreferences("MIRA_BRIDGE_SETTINGS", Context.MODE_PRIVATE);
        soundEnabled = p.getBoolean("inventory_sound", true) || "true".equals(p.getString("inventory_sound", "true"));
        vibrateEnabled = p.getBoolean("inventory_vibrate", true) || "true".equals(p.getString("inventory_vibrate", "true"));
    }

    private AlertDialog showProfessionalLoadingDialog(String title, String message) {
        Context ctx = getContext();
        if (ctx == null || !isAdded() || getActivity() == null || getActivity().isFinishing()) return null;

        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
            LinearLayout layout = new LinearLayout(ctx);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(50, 40, 50, 40);
            layout.setGravity(android.view.Gravity.CENTER);

            ProgressBar progressBar = new ProgressBar(ctx);
            progressBar.setIndeterminate(true);
            progressBar.setScaleX(1.5f);
            progressBar.setScaleY(1.5f);
            layout.addView(progressBar);

            TextView tvLoading = new TextView(ctx);
            tvLoading.setText(title);
            tvLoading.setTextSize(16);
            tvLoading.setTypeface(null, android.graphics.Typeface.BOLD);
            tvLoading.setGravity(android.view.Gravity.CENTER);
            tvLoading.setPadding(0, 20, 0, 8);
            layout.addView(tvLoading);

            TextView tvSubtext = new TextView(ctx);
            tvSubtext.setText(message);
            tvSubtext.setTextSize(12);
            tvSubtext.setTextColor(Color.GRAY);
            tvSubtext.setGravity(android.view.Gravity.CENTER);
            layout.addView(tvSubtext);

            builder.setView(layout);
            builder.setCancelable(false);
            AlertDialog dialog = builder.create();
            dialog.show();
            return dialog;
        } catch (Exception e) {
            return null;
        }
    }

    private void showRetryDialog(String title, String message, Runnable retryAction) {
        Context ctx = getContext();
        if (ctx == null || !isAdded()) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setTitle(title).setMessage(message).setCancelable(false);
        if (retryAction != null) {
            builder.setPositiveButton(isArabic ? "🔄 إعادة المحاولة" : "🔄 Retry", (d, w) -> {
                d.dismiss();
                retryAction.run();
            });
        }
        builder.setNegativeButton(isArabic ? "إغلاق" : "Close", (d, w) -> d.dismiss());
        builder.show();
    }

    private void dismissDialog(AlertDialog dialog) {
        try {
            if (dialog != null && dialog.isShowing() && isAdded()) dialog.dismiss();
        } catch (Exception ignored) {}
    }

    private void setButtonsEnabled(boolean enabled) {
        if (btnStartInventory != null) btnStartInventory.setEnabled(enabled);
        if (btnBlindInventory != null) btnBlindInventory.setEnabled(enabled);
        if (btnArchiveReports != null) btnArchiveReports.setEnabled(enabled);
    }

    private void showSuccessDialog(boolean synced) {
        Context ctx = getContext();
        if (ctx == null || !isAdded()) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setTitle(isArabic ? "✅ اكتمل الجرد" : "✅ Audit Complete");

        String message = isArabic ?
            "تم إيقاف الجرد وحفظ التقرير.\n\n" +
            "رقم الجلسة: " + sessionId + "\n" +
            "المتوقع: " + expectedCount + "\n" +
            "الممسوح: " + scannedCount + "\n" +
            "العجز: " + varianceCount + "\n" +
            "الوزن: " + String.format(Locale.US, "%.2fg", calculateTotalWeight()) + "\n" +
            (synced ? "✅ تمت المزامنة مع السيرفر" : "⚠️ تم الحفظ محلياً - سيتم المزامنة لاحقاً") :
            "Audit completed successfully.\n\n" +
            "Session ID: " + sessionId + "\n" +
            "Expected: " + expectedCount + "\n" +
            "Scanned: " + scannedCount + "\n" +
            "Variance: " + varianceCount + "\n" +
            "Weight: " + String.format(Locale.US, "%.2fg", calculateTotalWeight()) + "\n" +
            (synced ? "✅ Synced with server" : "⚠️ Saved locally - will sync later");

        builder.setMessage(message);
        builder.setPositiveButton(isArabic ? "🎉 متابعة" : "🎉 Continue", (d, w) -> d.dismiss());
        builder.setCancelable(false);
        builder.show();
    }

    // ... [باقي الدوال تبقى كما هي من الكود السابق]

    private void generateProReport() {
        // ... [نفس الكود السابق]
    }

    private void showAuditHistoryDialog() {
        // ... [نفس الكود السابق]
    }

    private void safeRun(Runnable r) {
        if (!isAdded() || getActivity() == null || getActivity().isFinishing()) return;
        getActivity().runOnUiThread(() -> {
            if (isAdded() && getActivity() != null && !getActivity().isFinishing()) {
                r.run();
            }
        });
    }

    @Override
    public void myOnKeyDwon() {
        if (!isScanning) startInventory(false);
        else stopInventory();
    }

    // ==================== ITEM & ADAPTER ====================

    public static class InventoryItem {
        String serial, title, karat, status, location;
        double numericWeight;

        public InventoryItem(String s, String t, String k, double w, String st, String l) {
            this.serial = s;
            this.title = t;
            this.karat = k;
            this.numericWeight = w;
            this.status = st;
            this.location = l;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            InventoryItem item = (InventoryItem) o;
            
            if (serial != null && !serial.isEmpty()) {
                return Objects.equals(serial, item.serial);
            }
            
            return Double.compare(item.numericWeight, numericWeight) == 0 &&
                    Objects.equals(serial, item.serial) &&
                    Objects.equals(title, item.title) &&
                    Objects.equals(karat, item.karat) &&
                    Objects.equals(status, item.status) &&
                    Objects.equals(location, item.location);
        }

        @Override
        public int hashCode() {
            if (serial != null && !serial.isEmpty()) {
                return Objects.hash(serial);
            }
            return Objects.hash(serial, title, karat, status, numericWeight, location);
        }
    }

    private class InventoryAdapter extends RecyclerView.Adapter<InventoryAdapter.VH> {
        private final List<InventoryItem> items = Collections.synchronizedList(new ArrayList<>());
        private final List<InventoryItem> displayItems = new ArrayList<>();

        public void addItems(List<InventoryItem> newItems) {
            if (newItems == null || newItems.isEmpty()) return;

            items.addAll(0, newItems);

            int oldSize = displayItems.size();
            displayItems.clear();
            synchronized (items) {
                int endIndex = Math.min(items.size(), MAX_DISPLAY_ITEMS);
                displayItems.addAll(items.subList(0, endIndex));
            }

            int insertedCount = displayItems.size() - oldSize;

            if (insertedCount > 0) {
                if (displayItems.size() >= MAX_DISPLAY_ITEMS) {
                    notifyDataSetChanged();
                } else {
                    notifyItemRangeInserted(0, insertedCount);
                    if (rvInventoryItems != null && !displayItems.isEmpty()) {
                        rvInventoryItems.scrollToPosition(0);
                    }
                }
            }
        }

        public void clearItems() {
            items.clear();
            displayItems.clear();
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_inventory, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            if (pos >= displayItems.size()) return;

            InventoryItem item = displayItems.get(pos);
            if (h.s != null) {
                h.s.setText(item.status);
                h.s.setTextColor("✓".equals(item.status) ? Color.parseColor("#22C55E") :
                    "⚠️".equals(item.status) ? Color.parseColor("#F59E0B") : Color.parseColor("#EF4444"));
            }
            if (h.serial != null) h.serial.setText(item.serial);
            if (h.title != null) h.title.setText(item.title);
            if (h.details != null) h.details.setText(String.format(Locale.US, "%s | %.2fg", item.karat, item.numericWeight));
            if (h.location != null) h.location.setText(item.location);
        }

        @Override public int getItemCount() {
            return displayItems.size();
        }

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
        stopSimulationLoop();
        stopTimer();
        mainHandler.removeCallbacksAndMessages(null);
        if (batchUpdateRunnable != null) mainHandler.removeCallbacks(batchUpdateRunnable);
        if (rvInventoryItems != null) rvInventoryItems.setAdapter(null);
        
        if (printWebView != null) {
            if (printWebView.getParent() != null) {
                ((ViewGroup) printWebView.getParent()).removeView(printWebView);
            }
            printWebView.stopLoading();
            printWebView.setWebViewClient(null);
            printWebView.destroy();
            printWebView = null;
        }
        
        if (soundPool != null) {
            try {
                soundPool.release();
            } catch (Exception e) {
                Log.e(TAG, "SoundPool release error: " + e.getMessage());
            }
            soundPool = null;
        }
        
        super.onDestroyView();
    }
}
