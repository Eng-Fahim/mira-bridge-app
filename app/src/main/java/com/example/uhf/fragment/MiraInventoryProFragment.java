package com.example.uhf.fragment;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
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
    private static final int SCREENSHOT_QUALITY = 70; // جودة الصورة 70%

    // Views
    private TextView tvInventoryStatus, tvExpectedCount, tvScannedCount, tvVariance;
    private TextView tvAccuracy, tvElapsedTime, tvLastScan, tvRssi;
    private TextView tvPrevItems, tvCurrItems, tvDeltaItems;
    private TextView tvTotalValue, tvTotalWeight;
    private CardView cardComparison;
    private Button btnStartInventory, btnBlindInventory, btnStopInventory, btnGenerateReport, btnArchiveReports;
    private RecyclerView rvInventoryItems;
    private ProgressBar progressScanned, progressRssi;

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

    // Fast Data Structures (Thread-Safe)
    private final Set<String> scannedEpcSet = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, InventoryItem> localCatalog = new ConcurrentHashMap<>();
    private final List<InventoryItem> scannedItems = new CopyOnWriteArrayList<>();
    private final List<InventoryItem> pendingBatch = new CopyOnWriteArrayList<>();
    private InventoryAdapter adapter;

    // Managers & Clients
    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfile;
    private MiraPriceManager priceManager;
    private SharedPreferences offlineCache;
    private SharedPreferences pendingQueue;

    // Handlers
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable timerRunnable;
    private Runnable batchUpdateRunnable;
    private boolean isBatchScheduled = false;

    // Hardware Settings
    private boolean soundEnabled = true;
    private boolean vibrateEnabled = true;
    private WebView printWebView;
    
    // Sound Pool
    private android.media.SoundPool soundPool;
    private int soundScanId, soundFoundId, soundMissingId;

    // Simulation Fallback Data
    private final String[] simulationGtins = {
        "0700000007885", "0700000007886", "0700000007887", "0700000007888", "0700000007889",
        "0700000007890", "0700000007891", "0700000007892", "0700000007893", "0700000007894",
        "0700000007895", "0700000007896", "0700000007897", "0700000007898", "0700000007899"
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
            priceManager = MiraPriceManager.getInstance(ctx);
            offlineCache = ctx.getSharedPreferences(OFFLINE_PREFS, Context.MODE_PRIVATE);
            pendingQueue = ctx.getSharedPreferences(PENDING_QUEUE_PREFS, Context.MODE_PRIVATE);
            initSoundPool();
        }
        isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");
        bindViews(v);
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
        preloadBranchCatalog();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateBranchHeader();
        syncPendingQueue();
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
        tvTotalValue = v.findViewById(R.id.tvTotalValue);
        tvTotalWeight = v.findViewById(R.id.tvTotalWeight);
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
            
            Log.i(TAG, "✅ SoundPool initialized successfully");
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
    }

    private void setInitialState() {
        if (btnStartInventory != null) btnStartInventory.setEnabled(true);
        if (btnBlindInventory != null) btnBlindInventory.setEnabled(true);
        if (btnStopInventory != null) btnStopInventory.setEnabled(false);
        if (btnGenerateReport != null) btnGenerateReport.setEnabled(false);
        expectedCount = localCatalog.isEmpty() ? simulationGtins.length : localCatalog.size();
        if (tvExpectedCount != null) tvExpectedCount.setText(String.valueOf(expectedCount));
        if (cardComparison != null) cardComparison.setVisibility(View.VISIBLE);
        if (progressRssi != null) progressRssi.setMax(100);
    }

    // ==================== OFFLINE CATALOG & PRELOAD ====================

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
                                    localCatalog.put(serial.toUpperCase(), new InventoryItem(serial, title, karat, weight, status, location));
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Parse item error: " + e.getMessage());
                            }
                        }
                        expectedCount = localCatalog.size();
                        safeRun(() -> {
                            if (tvExpectedCount != null && !isBlindMode) {
                                tvExpectedCount.setText(String.valueOf(expectedCount));
                            }
                        });
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Catalog Preload Error: " + e.getMessage());
            }
        }).start();
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
        updateRssiDisplay(rssi);
        scheduleBatchUpdate();
    }

    private void scheduleBatchUpdate() {
        if (isBatchScheduled || !isAdded()) return;
        isBatchScheduled = true;
        
        batchUpdateRunnable = () -> {
            isBatchScheduled = false;
            
            if (!isAdded() || getActivity() == null || getActivity().isFinishing()) {
                return;
            }
            
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
            } catch (Exception e) {
                Log.e(TAG, "Batch update error: " + e.getMessage());
            }
        };
        
        mainHandler.postDelayed(batchUpdateRunnable, UI_UPDATE_INTERVAL);
    }

    private void updateRssiDisplay(String rssi) {
        if (tvRssi != null) {
            tvRssi.setText(rssi + " dBm");
        }
        
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

    // ==================== START INVENTORY ====================

    private void startInventory(boolean blindMode) {
        Context ctx = getContext();
        if (ctx == null || !isAdded()) return;

        final AlertDialog loadingDialog = showProfessionalLoadingDialog(
            isArabic ? "🔄 جاري تجهيز الجرد..." : "🔄 Preparing audit...",
            isArabic ? "يرجى الانتظار - تحميل الكتالوج المحلي" : "Please wait - Loading local catalog"
        );

        new Thread(() -> {
            try {
                if (localCatalog.isEmpty()) {
                    preloadBranchCatalogSync();
                }

                safeRun(() -> {
                    dismissDialog(loadingDialog);

                    // ✅ إعادة تعيين sessionId كرقم دائماً
                    sessionId = (int) (System.currentTimeMillis() / 1000);
                    Log.d(TAG, "✅ New numeric session ID: " + sessionId);

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
                    }

                    playInventorySound("start");
                    startTimer();
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

    private void preloadBranchCatalogSync() {
        if (apiClient == null || branchesManager == null) return;
        final int activeBranchId = branchesManager.getActiveBranchId();
        MiraApiClient.ApiResponse response = apiClient.getBranchItemsList(activeBranchId);
        if (response != null && response.isSuccess && response.data != null) {
            JSONArray items = response.data.optJSONArray("items");
            if (items != null) {
                localCatalog.clear();
                for (int i = 0; i < items.length(); i++) {
                    try {
                        JSONObject obj = items.getJSONObject(i);
                        String serial = obj.optString("gtin13", obj.optString("serial_number", obj.optString("epc", "")));
                        if (!serial.isEmpty()) {
                            localCatalog.put(serial.toUpperCase(), new InventoryItem(
                                serial,
                                obj.optString("title", isArabic ? "قطعة ذهب" : "Gold Item"),
                                obj.optString("karat", "21K"),
                                obj.optDouble("weight", 0.0),
                                "✓",
                                branchesManager.getActiveBranchDisplayName()
                            ));
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Parse item error: " + e.getMessage());
                    }
                }
                expectedCount = localCatalog.size();
            }
        }
    }

    // ==================== STOP INVENTORY ====================

    private void stopInventory() {
        Context ctx = getContext();
        if (ctx == null || !isAdded()) return;

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
    }

    private void finishInventory() {
        Context ctx = getContext();
        if (ctx == null || !isAdded()) return;

        final AlertDialog loadingDialog = showProfessionalLoadingDialog(
            isArabic ? "🔄 جاري إيقاف الجرد..." : "🔄 Stopping audit...",
            isArabic ? "حفظ التقرير والتقاط لقطة الشاشة" : "Saving report and capturing screenshot"
        );

        new Thread(() -> {
            try {
                isScanning = false;
                varianceCount = Math.max(0, expectedCount - foundCount);
                
                // ✅ التقاط لقطة الشاشة أولاً
                final String screenshotBase64 = captureScreenshot();
                Log.d(TAG, "📸 Screenshot captured: " + (screenshotBase64 != null ? screenshotBase64.length() + " chars" : "null"));
                
                // ✅ إرسال البيانات مع اللقطة
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

    // ==================== SCREENSHOT CAPTURE ====================

    /**
     * التقاط لقطة شاشة للشاشة الحالية
     * @return Base64 encoded image or null if failed
     */
    private String captureScreenshot() {
        try {
            if (getActivity() == null || getView() == null) {
                Log.e(TAG, "Cannot capture: activity or view is null");
                return null;
            }

            View rootView = getView();
            Bitmap bitmap = Bitmap.createBitmap(
                rootView.getWidth(),
                rootView.getHeight(),
                Bitmap.Config.ARGB_8888
            );

            Canvas canvas = new Canvas(bitmap);
            rootView.draw(canvas);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, SCREENSHOT_QUALITY, baos);
            byte[] imageBytes = baos.toByteArray();
            bitmap.recycle();

            String base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP);
            
            Log.d(TAG, "📸 Screenshot captured: " + imageBytes.length + " bytes");
            
            return base64;
        } catch (Exception e) {
            Log.e(TAG, "❌ Screenshot error: " + e.getMessage());
            return null;
        }
    }

    // ==================== SYNC WITH SCREENSHOT ====================

    private boolean syncFullInventorySessionSync(String screenshotBase64) {
        try {
            JSONObject payload = createInventoryPayload();
            
            // ✅ إضافة اللقطة إلى payload
            if (screenshotBase64 != null && !screenshotBase64.isEmpty()) {
                payload.put("screenshot_base64", screenshotBase64);
                Log.d(TAG, "📸 Screenshot added to payload");
            }
            
            Log.d(TAG, "📤 Sending payload with screenshot");
            
            MiraApiClient.ApiResponse response = apiClient.closeInventorySession(payload);
            
            if (response != null) {
                Log.d(TAG, "📥 Server response code: " + response.code);
                Log.d(TAG, "📥 Server response success: " + response.isSuccess);
                
                if (response.isSuccess) {
                    if (response.data != null) {
                        try {
                            int serverSessionId = response.data.optInt("session_id", 0);
                            if (serverSessionId > 0) {
                                sessionId = serverSessionId;
                                Log.d(TAG, "✅ Updated session ID from server: " + sessionId);
                            }
                            
                            // ✅ التحقق من حفظ اللقطة
                            String screenshotUrl = response.data.optString("screenshot_url", "");
                            if (!screenshotUrl.isEmpty()) {
                                Log.d(TAG, "📸 Screenshot saved at: " + screenshotUrl);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Parse response error: " + e.getMessage());
                        }
                    }
                    
                    saveSnapshotLocally(payload);
                    return true;
                } else {
                    String errorMsg = response.error != null ? response.error : "Unknown error";
                    Log.e(TAG, "❌ Server rejected: " + errorMsg);
                    saveToPendingQueue(payload);
                    return false;
                }
            } else {
                Log.e(TAG, "❌ Response is null");
                saveToPendingQueue(payload);
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Sync error: " + e.getMessage());
            e.printStackTrace();
            try {
                saveToPendingQueue(createInventoryPayload());
            } catch (Exception ex) {
                Log.e(TAG, "Failed to save pending: " + ex.getMessage());
            }
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
        payload.put("connection_type", "SIMULATOR");
        
        // ✅ user_id
        if (getContext() != null) {
            SharedPreferences authPrefs = getContext().getSharedPreferences("MIRA_AUTH", Context.MODE_PRIVATE);
            int userId = authPrefs.getInt("user_id", authPrefs.getInt("userId", 0));
            if (userId > 0) {
                payload.put("user_id", userId);
            }
            
            String token = authPrefs.getString("auth_token", "");
            if (token.isEmpty()) {
                token = authPrefs.getString("session_token", "");
            }
            if (!token.isEmpty()) {
                payload.put("token", token);
            }
        }
        
        // ✅ items
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
        
        // ✅ scanned_epcs للتوافق
        JSONArray epcsArray = new JSONArray();
        synchronized (scannedItems) {
            for (InventoryItem item : scannedItems) {
                epcsArray.put(item.serial);
            }
        }
        payload.put("scanned_epcs", epcsArray);
        
        return payload;
    }

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
            
            while (pendingArray.length() > 20) {
                pendingArray.remove(0);
            }
            
            pendingQueue.edit()
                .putString("pending_audits", pendingArray.toString())
                .apply();
            
            Log.i(TAG, "✅ Audit saved to pending queue");
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
                safeRun(() -> {
                    Toast.makeText(getContext(), 
                        isArabic ? "📋 يوجد " + pendingArray.length() + " جرد معلق للمزامنة" : 
                                   "📋 " + pendingArray.length() + " pending audits to sync", 
                        Toast.LENGTH_LONG).show();
                });
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
                            if (response != null && response.error != null) {
                                Log.e(TAG, "Pending sync error: " + response.error);
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Pending item sync error: " + e.getMessage());
                        try {
                            remainingArray.put(pendingArray.getJSONObject(i));
                        } catch (Exception ex) {
                            Log.e(TAG, "Failed to add to remaining: " + ex.getMessage());
                        }
                    }
                }
                
                pendingQueue.edit()
                    .putString("pending_audits", remainingArray.toString())
                    .apply();
                
                final int synced = syncedCount;
                final int remaining = remainingArray.length();
                
                safeRun(() -> {
                    if (synced > 0) {
                        Toast.makeText(getContext(), 
                            isArabic ? "✅ تمت مزامنة " + synced + " جرد" : 
                                       "✅ Synced " + synced + " audits", 
                            Toast.LENGTH_SHORT).show();
                    }
                    
                    if (remaining > 0 && tvInventoryStatus != null && !isScanning) {
                        tvInventoryStatus.setText(isArabic ? "📋 " + remaining + " جرد معلق" : 
                                                           "📋 " + remaining + " pending");
                        tvInventoryStatus.setTextColor(Color.parseColor("#F59E0B"));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Sync pending queue error: " + e.getMessage());
            }
        }).start();
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
                double pricePerGram = priceManager != null ? 
                    priceManager.getGoldPricePerGram(karatNumber) : 65.0;
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
        if (!soundEnabled || soundPool == null) return;
        
        try {
            switch (type) {
                case "scan":
                    soundPool.play(soundScanId, 0.5f, 0.5f, 1, 0, 1f);
                    break;
                    
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
        if (getContext() == null) return;
        
        long currentTime = System.currentTimeMillis();
        
        if (currentTime - lastVibrationTime < VIBRATION_THROTTLE_MS) {
            return;
        }
        
        lastVibrationTime = currentTime;
        
        try {
            android.os.Vibrator vib = (android.os.Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
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
        if (getContext() == null) return;
        SharedPreferences p = getContext().getSharedPreferences("MIRA_BRIDGE_SETTINGS", Context.MODE_PRIVATE);
        soundEnabled = "true".equals(p.getString("inventory_sound", "true"));
        vibrateEnabled = "true".equals(p.getString("inventory_vibrate", "true"));
    }

    // ==================== DIALOGS ====================

    private AlertDialog showProfessionalLoadingDialog(String title, String message) {
        Context ctx = getContext();
        if (ctx == null || !isAdded() || getActivity() == null || getActivity().isFinishing()) {
            return null;
        }

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
            LinearLayout.LayoutParams pbParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            pbParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
            progressBar.setLayoutParams(pbParams);
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
            tvSubtext.setPadding(0, 8, 0, 0);
            layout.addView(tvSubtext);

            builder.setView(layout);
            builder.setCancelable(false);
            AlertDialog dialog = builder.create();
            
            if (isAdded() && getActivity() != null && !getActivity().isFinishing()) {
                dialog.show();
                return dialog;
            }
            
            return null;
        } catch (Exception e) {
            Log.e(TAG, "showLoadingDialog error: " + e.getMessage());
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
            if (dialog != null && dialog.isShowing() && isAdded() && getActivity() != null && !getActivity().isFinishing()) {
                dialog.dismiss();
            }
        } catch (Exception e) {
            Log.e(TAG, "dismissDialog error: " + e.getMessage());
        }
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
        
        String message;
        if (isArabic) {
            message = "تم إيقاف الجرد وحفظ التقرير.\n\n" +
                "رقم الجلسة: " + sessionId + "\n" +
                "المتوقع: " + expectedCount + "\n" +
                "الممسوح: " + scannedCount + "\n" +
                "العجز: " + varianceCount + "\n" +
                "الوزن: " + String.format(Locale.US, "%.2fg", calculateTotalWeight()) + "\n" +
                (synced ? "✅ تمت المزامنة مع السيرفر" : "⚠️ تم الحفظ محلياً - سيتم المزامنة لاحقاً");
        } else {
            message = "Audit completed successfully.\n\n" +
                "Session ID: " + sessionId + "\n" +
                "Expected: " + expectedCount + "\n" +
                "Scanned: " + scannedCount + "\n" +
                "Variance: " + varianceCount + "\n" +
                "Weight: " + String.format(Locale.US, "%.2fg", calculateTotalWeight()) + "\n" +
                (synced ? "✅ Synced with server" : "⚠️ Saved locally - will sync later");
        }
        
        builder.setMessage(message);
        builder.setPositiveButton(isArabic ? "🎉 متابعة" : "🎉 Continue", (d, w) -> d.dismiss());
        builder.setCancelable(false);
        builder.show();
    }

    // ==================== PRINT REPORT ====================

    private void generateProReport() {
        if (getContext() == null || !isAdded()) return;

        String orgName = orgProfile != null ? orgProfile.getOrgName() : (isArabic ? "مؤسسة المجوهرات" : "Jewelry Enterprise");
        String orgPhone = orgProfile != null ? orgProfile.getOrgPhone() : "";
        String orgAddress = orgProfile != null ? orgProfile.getOrgAddress() : (isArabic ? "اليمن" : "Yemen");
        String activeBranchName = branchesManager != null ? branchesManager.getActiveBranchDisplayName() : "";

        double totalWt = calculateTotalWeight();
        double totalVal = calculateTotalValue();

        StringBuilder rows = new StringBuilder();
        int count = 1;
        synchronized (scannedItems) {
            for (int i = scannedItems.size() - 1; i >= 0; i--) {
                InventoryItem item = scannedItems.get(i);
                String color = "✓".equals(item.status) ? "#22C55E" : "⚠️".equals(item.status) ? "#F59E0B" : "#EF4444";
                rows.append(String.format(Locale.US,
                    "<tr><td>%d</td><td style='color:%s;'>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%.2fg</td></tr>",
                    count++, color, item.status, item.serial, item.title, item.karat, item.numericWeight));
            }
        }

        double accVal = expectedCount > 0 ? (foundCount * 100.0 / expectedCount) : 100.0;
        String accColor = accVal >= 98 ? "#22C55E" : accVal >= 90 ? "#F59E0B" : "#EF4444";
        String dir = isArabic ? "rtl" : "ltr";

        String html = "<!DOCTYPE html><html dir='" + dir + "'><head><meta charset='UTF-8'><style>" +
            "body{font-family:sans-serif;color:#111827;padding:20px;direction:" + dir + ";background:#fff;font-size:11px}" +
            ".header{text-align:center;border-bottom:2px solid #D4AF37;padding-bottom:10px;margin-bottom:12px}" +
            ".header h2{color:#1A237E;margin:0;font-size:18px}" +
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
            "<div class='card'><div class='val'>" + expectedCount + "</div><div class='lbl'>" + (isArabic ? "المتوقع" : "Expected") + "</div></div>" +
            "<div class='card'><div class='val' style='color:#22C55E'>" + foundCount + "</div><div class='lbl'>" + (isArabic ? "تم التحقق" : "Verified") + "</div></div>" +
            "<div class='card'><div class='val' style='color:" + (varianceCount == 0 ? "#22C55E" : "#EF4444") + "'>" + varianceCount + "</div><div class='lbl'>" + (isArabic ? "العجز" : "Variance") + "</div></div>" +
            "<div class='card'><div class='val' style='color:" + accColor + "'>" + String.format(Locale.US, "%.1f%%", accVal) + "</div><div class='lbl'>" + (isArabic ? "الدقة" : "Accuracy") + "</div></div>" +
            "</div>" +
            "<table><tr><th>#</th><th>" + (isArabic ? "حالة" : "St") + "</th><th>" + (isArabic ? "الرقم" : "Serial") + "</th><th>" + (isArabic ? "القطعة" : "Item") + "</th><th>" + (isArabic ? "عيار" : "K") + "</th><th>" + (isArabic ? "وزن" : "Wt") + "</th></tr>" +
            rows.toString() + "</table>" +
            "<div style='display:flex;justify-content:space-between;margin-top:12px;font-weight:bold'>" +
            "<div>" + (isArabic ? "الوزن الكلي: " : "Total Weight: ") + String.format(Locale.US, "%.2fg", totalWt) + "</div>" +
            "<div>" + (isArabic ? "القيمة التقديرية: " : "Est. Value: ") + 
            (priceManager != null ? priceManager.formatPriceDefault(totalVal) : String.format(Locale.US, "%,.0f USD", totalVal)) + 
            "</div></div>" +
            "<div class='footer'>MIRA Inventory Pro | " + orgName + " © 2026</div>" +
            "</body></html>";

        if (printWebView != null) {
            printWebView.destroy();
        }
        
        printWebView = new WebView(requireContext());
        printWebView.getSettings().setJavaScriptEnabled(true);
        printWebView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
        printWebView.setWebViewClient(new WebViewClient() {
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
        Context ctx = getContext();
        if (ctx == null || apiClient == null) return;
        Toast.makeText(ctx, isArabic ? "🔄 جاري التحميل..." : "🔄 Loading...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getInventorySessionsList();
                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray sessionsArr = response.data.optJSONArray("sessions");
                    if (sessionsArr == null || sessionsArr.length() == 0) return;
                    List<String> titleList = new ArrayList<>();
                    for (int i = 0; i < sessionsArr.length(); i++) {
                        JSONObject s = sessionsArr.optJSONObject(i);
                        if (s != null) {
                            titleList.add((isArabic ? "جلسة #" : "Session #") + s.optInt("id") + " - " + s.optString("created_at"));
                        }
                    }
                    safeRun(() -> new AlertDialog.Builder(requireContext())
                        .setTitle(isArabic ? "📂 أرشيف الجرد" : "📂 Audit Archive")
                        .setItems(titleList.toArray(new String[0]), null)
                        .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                        .show());
                }
            } catch (Exception e) {
                Log.e(TAG, "History error: " + e.getMessage());
            }
        }).start();
    }

    private void safeRun(Runnable r) {
        if (!isAdded() || getActivity() == null || getActivity().isFinishing()) {
            return;
        }
        
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
            return Double.compare(item.numericWeight, numericWeight) == 0 &&
                    Objects.equals(serial, item.serial) &&
                    Objects.equals(status, item.status) &&
                    Objects.equals(location, item.location);
        }

        @Override
        public int hashCode() {
            return Objects.hash(serial, status, numericWeight, location);
        }
    }

    private class InventoryAdapter extends RecyclerView.Adapter<InventoryAdapter.VH> {
        private final List<InventoryItem> items = new CopyOnWriteArrayList<>();
        private final List<InventoryItem> displayItems = new ArrayList<>();
        
        public void addItems(List<InventoryItem> newItems) {
            if (newItems == null || newItems.isEmpty()) return;
            
            items.addAll(0, newItems);
            
            int oldSize = displayItems.size();
            displayItems.clear();
            int endIndex = Math.min(items.size(), MAX_DISPLAY_ITEMS);
            displayItems.addAll(items.subList(0, endIndex));
            
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
        stopTimer();
        mainHandler.removeCallbacksAndMessages(null);
        if (batchUpdateRunnable != null) mainHandler.removeCallbacks(batchUpdateRunnable);
        if (rvInventoryItems != null) rvInventoryItems.setAdapter(null);
        if (printWebView != null) {
            printWebView.destroy();
            printWebView = null;
        }
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
        super.onDestroyView();
    }
            }
