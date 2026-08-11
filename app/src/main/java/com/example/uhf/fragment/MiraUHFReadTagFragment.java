package com.example.uhf.fragment;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatSpinner;
import androidx.cardview.widget.CardView;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.tools.DeviceDetector;
import com.example.uhf.tools.ToastUtil;

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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MiraUHFReadTagFragment extends Fragment {
    private static final String TAG = "MiraUHFInventory";
    private static final int REQUEST_CAMERA_SCAN = 49374;
    private static final String OFFLINE_PREFS = "uhf_inventory_cache";
    private static final String PREF_LAST_AUDIT_MISSING = "last_audit_missing_epcs";

    private static final String KEY_RFID_POWER = "rfid_power";
    private static final String KEY_SCAN_SOUND = "scan_sound";
    private static final String KEY_RSSI_THRESHOLD = "rssi_threshold";
    private static final String KEY_RFID_ENABLED = "rfid_enabled";
    private static final String KEY_RFID_SCENARIO = "rfid_scenario";

    public enum InventoryState {
        IDLE,           // جاهز للبدء
        LOADING,        // جاري تحميل المخزون
        SCANNING,       // المسح نشط (قفل التنقل مفعل)
        PAUSED,         // معلق مؤقتاً (قفل التنقل مفعل)
        COMPLETED,      // اكتمل القراءة
        FINALIZED       // اعُتمد رسمياً وأُغلق الجرد
    }

    public enum ItemStatus {
        FOUND("مطابق بالرف", "#16A34A", "✅"),
        SOLD("مباع رسمياً", "#0284C7", "🏷️"),
        TRANSFERRED("تحويل / صيانة", "#8B5CF6", "📦"),
        TRULY_MISSING("عجز حقيقي", "#DC2626", "❌"),
        UNEXPECTED("زائد / غير مسجل", "#F59E0B", "⚠️"),
        SOLD_BUT_FOUND("تحذير: مباع وموجود بالرف!", "#EF4444", "🚨");

        public final String label;
        public final String colorHex;
        public final String icon;

        ItemStatus(String label, String colorHex, String icon) {
            this.label = label;
            this.colorHex = colorHex;
            this.icon = icon;
        }
    }

    private volatile InventoryState currentState = InventoryState.IDLE;

    private final ExecutorService queryExecutor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private final Handler readerCheckHandler = new Handler(Looper.getMainLooper());
    private final Handler simulationHandler = new Handler(Looper.getMainLooper());

    private final List<ScannedTag> recentTags = Collections.synchronizedList(new ArrayList<>());
    private final List<String> scannedEPCs = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> scannedEPCSet = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<ScannedTag> batchQueue = new ConcurrentLinkedQueue<>();
    private final List<ScannedTag> filteredTags = Collections.synchronizedList(new ArrayList<>());

    private final ConcurrentHashMap<String, ExpectedItem> expectedItemsMap = new ConcurrentHashMap<>();
    private final Set<String> previousAuditMissingEPCs = ConcurrentHashMap.newKeySet();

    private final AtomicInteger expectedTotal = new AtomicInteger(0);
    private final AtomicInteger foundCount = new AtomicInteger(0);
    private final AtomicInteger soldCount = new AtomicInteger(0);
    private final AtomicInteger transferredCount = new AtomicInteger(0);
    private final AtomicInteger trulyMissingCount = new AtomicInteger(0);
    private final AtomicInteger unexpectedCount = new AtomicInteger(0);

    private volatile boolean isScanning = false;
    private volatile boolean isPaused = false;
    private volatile boolean isSimulating = false;
    private boolean exitWarningShown = false;

    private long startTime = 0;
    private long pauseStartTime = 0;
    private long totalPausedDuration = 0;
    private final AtomicInteger totalScanned = new AtomicInteger(0);
    private final AtomicInteger uniqueTags = new AtomicInteger(0);
    private int lastSecondCount = 0;
    private String sessionId;
    private String currentFilter = "all";
    private boolean isReaderConnected = false;

    private SharedPreferences globalSettings;
    private boolean isSoundEnabled = true;
    private int configuredPowerDbm = 28;
    private int configuredRssiThreshold = -100;
    private boolean rfidEnabledConfig = true;
    private String rfidScenarioConfig = "real";

    private ScheduledExecutorService batchScheduler;
    private SharedPreferences offlineCache;
    private final List<String> realGtinsForSimulation = new CopyOnWriteArrayList<>();

    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfile;
    private boolean isArabic;

    private SoundPool soundPool;
    private int soundMatch = 0, soundUnmatch = 0, soundSoldAlert = 0;
    private boolean soundLoaded = false;
    private Vibrator vibrator;

    private View rootLayout;
    private LinearLayout alertBannerView;
    private TextView tvAlertMessage;
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

    public static class ExpectedItem {
        public String epc, title, karat;
        public double weight;
        public ItemStatus status = ItemStatus.TRULY_MISSING;
        public boolean isFound = false;
        public String scanTime;
        public String dbState = "AVAILABLE"; 
        public boolean wasMissingInLastAudit = false;
    }

    static class ScannedTag {
        String epc, rssi, title, karat, weight, timestamp;
        ItemStatus status;
        boolean isExpected;
    }

    public void onKeyDownTrigger() {
        if (currentState == InventoryState.SCANNING) {
            stopInventory();
        } else if (currentState == InventoryState.IDLE || currentState == InventoryState.COMPLETED) {
            startInventory();
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_uhf_readtag, container, false);
    }

    public boolean onBackPressed() {
        if (!canNavigateAway()) {
            showExitWarningDialog();
            return true;
        }
        return false;
    }

    public boolean canNavigateAway() {
        return currentState != InventoryState.SCANNING && currentState != InventoryState.PAUSED;
    }

    private void showExitWarningDialog() {
        if (exitWarningShown || getContext() == null) return;
        exitWarningShown = true;

        new AlertDialog.Builder(requireContext())
                .setTitle(ar("⚠️ تحذير: جرد محمي قيد التشغيل", "⚠️ Warning: Active Protected Audit"))
                .setMessage(ar(
                        "جلسة الجرد نشطة حالياً وتم حظر التنقل لمنع ضياع البيانات.\nهل ترغب في إيقاف الجرد والخروج؟",
                        "Active inventory session.\nExiting will stop scanning. Are you sure?"))
                .setPositiveButton(ar("⏹ إيقاف وخروج", "⏹ Stop & Exit"), (d, w) -> {
                    stopInventory();
                    exitWarningShown = false;
                    if (getActivity() != null) {
                        getActivity().onBackPressed();
                    }
                })
                .setNegativeButton(ar("متابعة الجرد", "Continue"), (d, w) -> exitWarningShown = false)
                .setNeutralButton(ar("⏸ تعليق الجلسة", "⏸ Pause Session"), (d, w) -> {
                    if (currentState == InventoryState.SCANNING) togglePause();
                    exitWarningShown = false;
                })
                .setCancelable(false)
                .show();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        try {
            Context ctx = requireContext();
            apiClient = MiraApiClient.getInstance(ctx);
            branchesManager = MiraBranchesManager.getInstance(ctx);
            orgProfile = MiraOrgProfileManager.getInstance(ctx);
            offlineCache = ctx.getSharedPreferences(OFFLINE_PREFS, Context.MODE_PRIVATE);
            isArabic = Locale.getDefault().getLanguage().equals("ar");
            sessionId = "AUDIT-" + System.currentTimeMillis();

            loadGlobalSettings();
            initSoundPool();
            if (!initViews(view)) return;
            setupRecyclerView();
            setupListeners();
            setupZoneSpinner();
            setupFilterTabs();
            updateBranchDisplay();
            startReaderMonitoring();
            loadOfflineGtins();
            loadPreviousAuditSnapshot();
            setInventoryState(InventoryState.IDLE);
        } catch (Exception e) {
            Log.e(TAG, "onViewCreated error: " + e.getMessage());
        }
    }

    private void setInventoryState(InventoryState newState) {
        this.currentState = newState;
        this.isScanning = (newState == InventoryState.SCANNING);
        this.isPaused = (newState == InventoryState.PAUSED);

        mainHandler.post(() -> {
            switch (newState) {
                case IDLE:
                    if (btnStartInventory != null) btnStartInventory.setEnabled(true);
                    if (btnPauseInventory != null) btnPauseInventory.setVisibility(View.GONE);
                    if (btnStopInventory != null) btnStopInventory.setEnabled(false);
                    if (btnClearInventory != null) btnClearInventory.setEnabled(false);
                    if (btnExportInventory != null) btnExportInventory.setEnabled(false);
                    if (btnExportMissing != null) btnExportMissing.setEnabled(false);
                    if (btnFinalizeAudit != null) btnFinalizeAudit.setVisibility(View.GONE);
                    if (spinnerZone != null) spinnerZone.setEnabled(true);
                    break;

                case LOADING:
                    if (btnStartInventory != null) btnStartInventory.setEnabled(false);
                    if (pbScanProgress != null) pbScanProgress.setVisibility(View.VISIBLE);
                    break;

                case SCANNING:
                    if (btnStartInventory != null) btnStartInventory.setEnabled(false);
                    if (btnPauseInventory != null) {
                        btnPauseInventory.setVisibility(View.VISIBLE);
                        btnPauseInventory.setText(ar("⏸ تعليق", "⏸ Pause"));
                    }
                    if (btnStopInventory != null) btnStopInventory.setEnabled(true);
                    if (btnClearInventory != null) btnClearInventory.setEnabled(false);
                    if (btnExportInventory != null) btnExportInventory.setEnabled(false);
                    if (btnExportMissing != null) btnExportMissing.setEnabled(false);
                    if (btnFinalizeAudit != null) btnFinalizeAudit.setVisibility(View.GONE);
                    if (spinnerZone != null) spinnerZone.setEnabled(false);
                    break;

                case PAUSED:
                    if (btnPauseInventory != null) {
                        btnPauseInventory.setText(ar("▶ استئناف", "▶ Resume"));
                    }
                    break;

                case COMPLETED:
                    if (btnStartInventory != null) btnStartInventory.setEnabled(true);
                    if (btnPauseInventory != null) btnPauseInventory.setVisibility(View.GONE);
                    if (btnStopInventory != null) btnStopInventory.setEnabled(false);
                    if (btnClearInventory != null) btnClearInventory.setEnabled(true);
                    if (btnExportInventory != null) btnExportInventory.setEnabled(true);
                    if (btnExportMissing != null) btnExportMissing.setEnabled(true);
                    if (btnFinalizeAudit != null) {
                        btnFinalizeAudit.setVisibility(View.VISIBLE);
                        btnFinalizeAudit.setEnabled(true);
                    }
                    if (spinnerZone != null) spinnerZone.setEnabled(true);
                    break;

                case FINALIZED:
                    if (btnStartInventory != null) btnStartInventory.setEnabled(true);
                    if (btnPauseInventory != null) btnPauseInventory.setVisibility(View.GONE);
                    if (btnStopInventory != null) btnStopInventory.setEnabled(false);
                    if (btnClearInventory != null) btnClearInventory.setEnabled(true);
                    if (btnExportInventory != null) btnExportInventory.setEnabled(true);
                    if (btnExportMissing != null) btnExportMissing.setEnabled(true);
                    if (btnFinalizeAudit != null) {
                        btnFinalizeAudit.setVisibility(View.VISIBLE);
                        btnFinalizeAudit.setEnabled(false);
                    }
                    break;
            }
        });
    }

    public void onTagScanned(String epc, String rssi) {
        if (currentState != InventoryState.SCANNING || epc == null || epc.isEmpty()) return;

        if (rssi != null && rssi.contains("dBm")) {
            try {
                int parsedRssi = Integer.parseInt(rssi.replace("dBm", "").trim());
                if (parsedRssi < configuredRssiThreshold) return;
            } catch (Exception ignored) {}
        }

        String cleanEpc = epc.trim();

        if (!scannedEPCSet.add(cleanEpc)) {
            totalScanned.incrementAndGet();
            mainHandler.post(this::updateUI);
            return;
        }

        scannedEPCs.add(cleanEpc);
        totalScanned.incrementAndGet();
        uniqueTags.incrementAndGet();

        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        boolean isExpected = expectedItemsMap.containsKey(cleanEpc);
        ScannedTag tag = new ScannedTag();
        tag.epc = cleanEpc;
        tag.rssi = rssi != null ? rssi : "—";
        tag.timestamp = time;
        tag.isExpected = isExpected;

        if (isExpected) {
            ExpectedItem item = expectedItemsMap.get(cleanEpc);
            if (item != null) {
                item.isFound = true;
                item.scanTime = time;

                if ("SOLD".equals(item.dbState)) {
                    item.status = ItemStatus.SOLD_BUT_FOUND;
                    tag.status = ItemStatus.SOLD_BUT_FOUND;
                    tag.title = item.title + " 🚨 (مباع وموجود!)";
                    tag.karat = item.karat;
                    tag.weight = String.format(Locale.US, "%.2fg", item.weight);

                    triggerSoldItemAlert(cleanEpc, item.title);

                    mainHandler.post(() -> {
                        updateLastTagDisplay(cleanEpc, item.title, item.karat, String.format(Locale.US, "%.2fg", item.weight), ItemStatus.SOLD_BUT_FOUND);
                    });
                } else {
                    item.status = ItemStatus.FOUND;
                    foundCount.incrementAndGet();
                    if (trulyMissingCount.get() > 0) trulyMissingCount.decrementAndGet();

                    tag.status = ItemStatus.FOUND;
                    tag.title = item.title;
                    tag.karat = item.karat;
                    tag.weight = String.format(Locale.US, "%.2fg", item.weight);

                    mainHandler.post(() -> {
                        updateLastTagDisplay(cleanEpc, item.title, item.karat, String.format(Locale.US, "%.2fg", item.weight), ItemStatus.FOUND);
                        playSound(soundMatch);
                    });
                }
            }
        } else {
            unexpectedCount.incrementAndGet();
            tag.status = ItemStatus.UNEXPECTED;
            tag.title = ar("غير مسجلة / زائدة", "Unregistered / Extra");
            tag.karat = "—";
            tag.weight = "—";

            mainHandler.post(() -> {
                updateLastTagDisplay(cleanEpc, ar("غير مسجلة", "Unregistered"), "—", "—", ItemStatus.UNEXPECTED);
                playSound(soundUnmatch);
            });
        }

        batchQueue.add(tag);
        if (batchQueue.size() >= 100) flushBatch();

        queryItemFromApi(cleanEpc);

        int targetMatches = expectedTotal.get() - (soldCount.get() + transferredCount.get());
        if (targetMatches > 0 && foundCount.get() == targetMatches) {
            mainHandler.post(() -> {
                stopInventory();
                showCompletionModal();
            });
        }
    }

    private void triggerSoldItemAlert(String epc, String itemTitle) {
        if (isSoundEnabled && soundLoaded && soundSoldAlert != 0) {
            soundPool.play(soundSoldAlert, 1.0f, 1.0f, 1, 0, 1.2f);
        }

        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 150, 100, 200}, -1));
            } else {
                vibrator.vibrate(400);
            }
        }

        mainHandler.post(() -> {
            if (alertBannerView != null && tvAlertMessage != null) {
                tvAlertMessage.setText(ar(
                        "🚨 تحذير أمني: القطعة [" + itemTitle + "] مباعة بالنظام وموجودة بالرف!\nEPC: " + epc,
                        "🚨 ALERT: Item [" + itemTitle + "] is SOLD in DB but found on shelf!\nEPC: " + epc
                ));

                alertBannerView.setBackgroundColor(Color.parseColor("#DC2626"));
                alertBannerView.setVisibility(View.VISIBLE);

                mainHandler.removeCallbacks(hideAlertRunnable);
                mainHandler.postDelayed(hideAlertRunnable, 4000);
            } else {
                ToastUtil.showLong(requireContext(), 
                    "🚨 " + ar("تنبيه: قطعة مباعة وموجودة بالرف! ", "ALERT: Sold item on shelf! ") + itemTitle);
            }
        });
    }

    private final Runnable hideAlertRunnable = () -> {
        if (alertBannerView != null) {
            alertBannerView.setVisibility(View.GONE);
        }
    };

    private void loadExpectedInventory(Runnable onReady) {
        queryExecutor.execute(() -> {
            try {
                int branchId = branchesManager != null ? branchesManager.getActiveBranchId() : 1;
                MiraApiClient.ApiResponse response = apiClient.getBranchItemsList(branchId);

                expectedItemsMap.clear();
                soldCount.set(0); transferredCount.set(0);

                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray items = response.data.optJSONArray("items");
                    if (items != null && items.length() > 0) {
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject obj = items.getJSONObject(i);
                            ExpectedItem item = new ExpectedItem();
                            item.epc = obj.optString("serial_number", obj.optString("gtin13", obj.optString("epc", "")));
                            item.title = obj.optString("item_title", obj.optString("title", ""));
                            item.karat = obj.optString("gold_karat", obj.optString("karat", ""));
                            item.weight = obj.optDouble("gross_weight_grams", obj.optDouble("weight", 0));
                            item.dbState = obj.optString("status", "AVAILABLE").toUpperCase();

                            if (!item.epc.isEmpty()) {
                                if ("SOLD".equals(item.dbState)) {
                                    item.status = ItemStatus.SOLD;
                                    soldCount.incrementAndGet();
                                } else if ("TRANSFERRED".equals(item.dbState) || "IN_MAINTENANCE".equals(item.dbState)) {
                                    item.status = ItemStatus.TRANSFERRED;
                                    transferredCount.incrementAndGet();
                                } else {
                                    item.status = ItemStatus.TRULY_MISSING;
                                    if (previousAuditMissingEPCs.contains(item.epc)) {
                                        item.wasMissingInLastAudit = true;
                                    }
                                }

                                expectedItemsMap.put(item.epc, item);
                            }
                        }
                    }
                }

                if (expectedItemsMap.isEmpty()) loadOfflineInventory();
                if (expectedItemsMap.isEmpty()) loadDemoData();

                int total = expectedItemsMap.size();
                int missing = total - (soldCount.get() + transferredCount.get());
                expectedTotal.set(total);
                trulyMissingCount.set(Math.max(0, missing));

            } catch (Exception e) {
                Log.e(TAG, "load error: " + e.getMessage());
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

    private void startInventory() {
        if (currentState == InventoryState.SCANNING) return;

        setInventoryState(InventoryState.LOADING);
        loadExpectedInventory(() -> {
            scannedEPCs.clear();
            scannedEPCSet.clear();
            recentTags.clear();
            batchQueue.clear();
            totalScanned.set(0);
            uniqueTags.set(0);
            foundCount.set(0);
            unexpectedCount.set(0);
            lastSecondCount = 0;

            startTime = System.currentTimeMillis();
            totalPausedDuration = 0;

            startBatchProcessor();
            startTimer();

            setInventoryState(InventoryState.SCANNING);

            if (rfidScenarioConfig.equals("simulated") || !DeviceDetector.isChainwayC72()) {
                startSimulatedScan();
            } else {
                startHardwareScan();
            }
            ToastUtil.showShort(requireContext(), ar("▶ بدأ الجرد ذكي المخزون", "▶ Intelligent Inventory Started"));
        });
    }

    private void togglePause() {
        if (currentState == InventoryState.SCANNING) {
            setInventoryState(InventoryState.PAUSED);
            pauseStartTime = System.currentTimeMillis();
            stopHardwareScan();
            stopSimulatedScan();
            ToastUtil.showShort(requireContext(), ar("⏸ تم تعليق الجلسة", "⏸ Session Paused"));
        } else if (currentState == InventoryState.PAUSED) {
            totalPausedDuration += (System.currentTimeMillis() - pauseStartTime);
            setInventoryState(InventoryState.SCANNING);
            if (rfidScenarioConfig.equals("simulated") || !DeviceDetector.isChainwayC72()) {
                startSimulatedScan();
            } else {
                startHardwareScan();
            }
            ToastUtil.showShort(requireContext(), ar("▶ تم استئناف الجلسة", "▶ Session Resumed"));
        }
    }

    private void stopInventory() {
        if (currentState != InventoryState.SCANNING && currentState != InventoryState.PAUSED) return;

        stopHardwareScan();
        stopSimulatedScan();
        stopTimer();
        flushBatch();
        stopBatchProcessor();

        setInventoryState(InventoryState.COMPLETED);
        ToastUtil.showShort(requireContext(), ar("⏹ اكتملت عملية الجرد", "⏹ Inventory Completed"));
    }

    private void clearInventory() {
        if (currentState == InventoryState.SCANNING || currentState == InventoryState.PAUSED) return;

        scannedEPCs.clear();
        scannedEPCSet.clear();
        recentTags.clear();
        filteredTags.clear();
        batchQueue.clear();

        totalScanned.set(0);
        uniqueTags.set(0);
        foundCount.set(0);
        unexpectedCount.set(0);

        for (ExpectedItem item : expectedItemsMap.values()) {
            item.isFound = false;
            if (!"SOLD".equals(item.dbState) && !"TRANSFERRED".equals(item.dbState)) {
                item.status = ItemStatus.TRULY_MISSING;
            }
        }

        trulyMissingCount.set(Math.max(0, expectedTotal.get() - (soldCount.get() + transferredCount.get())));

        if (recentAdapter != null) recentAdapter.notifyDataSetChanged();
        updateUI();
        updateReconciliationCounters();
        updateReconciliationResult();

        setInventoryState(InventoryState.IDLE);
        ToastUtil.showShort(requireContext(), ar("🗑 تم مسح بيانات الجلسة", "🗑 Session Cleared"));
    }

    private void finalizeAuditSession() {
        if (currentState != InventoryState.COMPLETED) return;

        new AlertDialog.Builder(requireContext())
                .setTitle(ar("حفظ واعتماد الجرد", "Finalize & Save Audit"))
                .setMessage(ar("هل أنت تأكد من اعتماد جلسة الجرد الحالية وتسويتها مع النظام السحابي؟",
                        "Are you sure you want to finalize and save this audit session?"))
                .setPositiveButton(ar("نعم، اعتمد الجرد", "Yes, Finalize"), (d, w) -> {
                    saveAuditSnapshotToLocal();
                    uploadAuditReportToCloud();
                    setInventoryState(InventoryState.FINALIZED);
                    ToastUtil.showLong(requireContext(), ar("✅ تم اعتماد الجرد بنجاح", "✅ Audit Finalized Successfully"));
                })
                .setNegativeButton(ar("إلغاء", "Cancel"), null)
                .show();
    }

    private void saveAuditSnapshotToLocal() {
        try {
            Set<String> missingSet = ConcurrentHashMap.newKeySet();
            for (ExpectedItem item : expectedItemsMap.values()) {
                if (!item.isFound && item.status == ItemStatus.TRULY_MISSING) {
                    missingSet.add(item.epc);
                }
            }
            offlineCache.edit().putStringSet(PREF_LAST_AUDIT_MISSING, missingSet).apply();
            previousAuditMissingEPCs.clear();
            previousAuditMissingEPCs.addAll(missingSet);
        } catch (Exception e) {
            Log.e(TAG, "saveAuditSnapshot error: " + e.getMessage());
        }
    }

    private void uploadAuditReportToCloud() {
        queryExecutor.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("session_id", sessionId);
                payload.put("branch_id", branchesManager != null ? branchesManager.getActiveBranchId() : 1);
                payload.put("expected_total", expectedTotal.get());
                payload.put("found_count", foundCount.get());
                payload.put("sold_count", soldCount.get());
                payload.put("transferred_count", transferredCount.get());
                payload.put("truly_missing_count", trulyMissingCount.get());
                payload.put("unexpected_count", unexpectedCount.get());

                apiClient.submitInventoryAuditReport(payload);
            } catch (Exception e) {
                Log.e(TAG, "uploadReport error: " + e.getMessage());
            }
        });
    }

    private void loadPreviousAuditSnapshot() {
        try {
            Set<String> savedMissing = offlineCache.getStringSet(PREF_LAST_AUDIT_MISSING, null);
            if (savedMissing != null) {
                previousAuditMissingEPCs.addAll(savedMissing);
            }
        } catch (Exception e) {
            Log.e(TAG, "loadPreviousAudit error: " + e.getMessage());
        }
    }

    private void updateUI() {
        if (tvTotalScanned != null) tvTotalScanned.setText(String.valueOf(totalScanned.get()));
        if (tvUniqueTags != null) tvUniqueTags.setText(String.valueOf(uniqueTags.get()));
        updateReconciliationCounters();
        updateReconciliationResult();
    }

    private void updateReconciliationCounters() {
        if (tvExpectedTotal != null) tvExpectedTotal.setText(String.valueOf(expectedTotal.get()));
        if (tvFoundCount != null) tvFoundCount.setText(String.valueOf(foundCount.get()));
        if (tvMissingCount != null) tvMissingCount.setText(String.valueOf(trulyMissingCount.get()));
        if (tvUnexpectedCount != null) tvUnexpectedCount.setText(String.valueOf(unexpectedCount.get()));
    }

    private void updateReconciliationResult() {
        if (tvReconciliationResult == null) return;

        int missing = trulyMissingCount.get();
        int unexpected = unexpectedCount.get();

        if (missing == 0 && unexpected == 0) {
            tvReconciliationResult.setText(ar("✅ الجرد مطابق 100%", "✅ 100% Matching Audit"));
            tvReconciliationResult.setTextColor(Color.parseColor("#16A34A"));
        } else {
            String statusMsg = String.format(Locale.getDefault(),
                    ar("❌ عجز حقيقي: %d | ⚠️ زائد: %d", "❌ Missing: %d | ⚠️ Extra: %d"),
                    missing, unexpected);
            tvReconciliationResult.setText(statusMsg);
            tvReconciliationResult.setTextColor(Color.parseColor("#DC2626"));
        }
    }

    private void updateLastTagDisplay(String epc, String title, String karat, String weight, ItemStatus status) {
        if (tvLastTagEPC != null) tvLastTagEPC.setText(epc);
        if (tvLastTagTitle != null) tvLastTagTitle.setText(title);
        if (tvLastTagKarat != null) tvLastTagKarat.setText(karat);
        if (tvLastTagWeight != null) tvLastTagWeight.setText(weight);

        if (tvLastTagStatus != null) {
            tvLastTagStatus.setText(status.icon + " " + status.label);
            tvLastTagStatus.setTextColor(Color.parseColor(status.colorHex));
        }

        if (cardLastTag != null) {
            if (status == ItemStatus.SOLD_BUT_FOUND) {
                cardLastTag.setCardBackgroundColor(Color.parseColor("#FEE2E2"));
            } else {
                cardLastTag.setCardBackgroundColor(Color.WHITE);
            }
        }
    }

    private void showCompletionModal() {
        new AlertDialog.Builder(requireContext())
                .setTitle(ar("🎉 اكتمل جرد الرف بنجاح!", "🎉 Shelf Inventory Completed!"))
                .setMessage(ar("تم مسح ومطابقة جميع القطع المتوقعة بالرف بنسبة 100%.",
                        "All expected items on the shelf were successfully scanned and matched 100%."))
                .setPositiveButton(ar("عرض التقرير", "View Report"), null)
                .show();
    }

    private void startTimer() {
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                if (currentState == InventoryState.SCANNING) {
                    long elapsedMillis = System.currentTimeMillis() - startTime - totalPausedDuration;
                    long seconds = elapsedMillis / 1000;
                    long mins = seconds / 60;
                    long secs = seconds % 60;

                    if (tvElapsed != null) {
                        tvElapsed.setText(String.format(Locale.US, "%02d:%02d", mins, secs));
                    }

                    int currentTotal = totalScanned.get();
                    int rate = currentTotal - lastSecondCount;
                    lastSecondCount = currentTotal;

                    if (tvScanRate != null) {
                        tvScanRate.setText(String.format(Locale.US, "%d/s", rate));
                    }
                }
                timerHandler.postDelayed(this, 1000);
            }
        };
        timerHandler.post(timerRunnable);
    }

    private void stopTimer() {
        if (timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
        }
    }

    private void startBatchProcessor() {
        batchScheduler = Executors.newSingleThreadScheduledExecutor();
        batchScheduler.scheduleWithFixedDelay(this::flushBatch, 200, 200, TimeUnit.MILLISECONDS);
    }

    private void stopBatchProcessor() {
        if (batchScheduler != null && !batchScheduler.isShutdown()) {
            batchScheduler.shutdown();
        }
    }

    private void flushBatch() {
        if (batchQueue.isEmpty()) return;

        List<ScannedTag> toAdd = new ArrayList<>();
        ScannedTag tag;
        while ((tag = batchQueue.poll()) != null) {
            toAdd.add(tag);
        }

        if (!toAdd.isEmpty()) {
            recentTags.addAll(0, toAdd);
            mainHandler.post(() -> {
                filterAndRefreshList();
                updateUI();
            });
        }
    }

    private void filterAndRefreshList() {
        filteredTags.clear();
        synchronized (recentTags) {
            for (ScannedTag tag : recentTags) {
                if ("all".equals(currentFilter)) {
                    filteredTags.add(tag);
                } else if ("found".equals(currentFilter) && tag.status == ItemStatus.FOUND) {
                    filteredTags.add(tag);
                } else if ("missing".equals(currentFilter) && tag.status == ItemStatus.TRULY_MISSING) {
                    filteredTags.add(tag);
                } else if ("unexpected".equals(currentFilter) && (tag.status == ItemStatus.UNEXPECTED || tag.status == ItemStatus.SOLD_BUT_FOUND)) {
                    filteredTags.add(tag);
                }
            }
        }
        if (recentAdapter != null) recentAdapter.notifyDataSetChanged();
    }

    private void queryItemFromApi(String cleanEpc) {
        queryExecutor.execute(() -> {
            try {
                MiraApiClient.ApiResponse res = apiClient.getItemDetailsByEPC(cleanEpc);
                if (res != null && res.isSuccess && res.data != null) {
                    JSONObject item = res.data.optJSONObject("item");
                    if (item != null) {
                        String title = item.optString("item_title", item.optString("title", ""));
                        String karat = item.optString("gold_karat", item.optString("karat", ""));
                        double weight = item.optDouble("gross_weight_grams", item.optDouble("weight", 0));

                        mainHandler.post(() -> {
                            synchronized (recentTags) {
                                for (ScannedTag tag : recentTags) {
                                    if (tag.epc.equals(cleanEpc)) {
                                        tag.title = title;
                                        tag.karat = karat;
                                        tag.weight = String.format(Locale.US, "%.2fg", weight);
                                        break;
                                    }
                                }
                            }
                            filterAndRefreshList();
                        });
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    private void startHardwareScan() {
        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).startUHFScan();
        }
    }

    private void stopHardwareScan() {
        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).stopUHFScan();
        }
    }

    private void startSimulatedScan() {
        isSimulating = true;
        simulationRunnable = new Runnable() {
            @Override
            public void run() {
                if (currentState == InventoryState.SCANNING && isSimulating) {
                    generateSimulatedTag();
                    simulationHandler.postDelayed(this, 150 + new Random().nextInt(200));
                }
            }
        };
        simulationHandler.post(simulationRunnable);
    }

    private void stopSimulatedScan() {
        isSimulating = false;
        if (simulationRunnable != null) {
            simulationHandler.removeCallbacks(simulationRunnable);
        }
    }

    private void generateSimulatedTag() {
        String epc;
        if (!realGtinsForSimulation.isEmpty() && new Random().nextDouble() < 0.8) {
            epc = realGtinsForSimulation.get(new Random().nextInt(realGtinsForSimulation.size()));
        } else {
            epc = "07" + String.format(Locale.US, "%011d", new Random().nextInt(1000000000));
        }
        int rssiVal = -40 - new Random().nextInt(40);
        onTagScanned(epc, rssiVal + "dBm");
    }

    private void loadGlobalSettings() {
        try {
            globalSettings = PreferenceManager.getDefaultSharedPreferences(requireContext());
            configuredPowerDbm = globalSettings.getInt(KEY_RFID_POWER, 28);
            isSoundEnabled = globalSettings.getBoolean(KEY_SCAN_SOUND, true);
            configuredRssiThreshold = globalSettings.getInt(KEY_RSSI_THRESHOLD, -100);
            rfidEnabledConfig = globalSettings.getBoolean(KEY_RFID_ENABLED, true);
            rfidScenarioConfig = globalSettings.getString(KEY_RFID_SCENARIO, "real");
        } catch (Exception e) {
            Log.e(TAG, "loadGlobalSettings error: " + e.getMessage());
        }
    }

    private void initSoundPool() {
        try {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();

            soundPool = new SoundPool.Builder()
                    .setMaxStreams(5)
                    .setAudioAttributes(attributes)
                    .build();

            soundPool.setOnLoadCompleteListener((sp, sampleId, status) -> {
                if (status == 0) soundLoaded = true;
            });

            soundMatch = soundPool.load(requireContext(), R.raw.beep_success, 1);
            soundUnmatch = soundPool.load(requireContext(), R.raw.beep_warning, 1);
            soundSoldAlert = soundPool.load(requireContext(), R.raw.alarm_sold_item, 1);

            if (getContext() != null) {
                vibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            }
        } catch (Exception e) {
            Log.e(TAG, "SoundPool init error: " + e.getMessage());
        }
    }

    private void playSound(int soundId) {
        if (isSoundEnabled && soundLoaded && soundId != 0) {
            soundPool.play(soundId, 0.8f, 0.8f, 1, 0, 1.0f);
        }
    }

    private boolean initViews(View view) {
        rootLayout = view;
        alertBannerView = view.findViewById(R.id.alertBannerView);
        tvAlertMessage = view.findViewById(R.id.tvAlertMessage);

        tvTotalScanned = view.findViewById(R.id.tvTotalScanned);
        tvUniqueTags = view.findViewById(R.id.tvUniqueTags);
        tvScanRate = view.findViewById(R.id.tvScanRate);
        tvElapsed = view.findViewById(R.id.tvElapsed);
        tvBranchName = view.findViewById(R.id.tvBranchName);

        tvLastTagEPC = view.findViewById(R.id.tvLastTagEPC);
        tvLastTagTitle = view.findViewById(R.id.tvLastTagTitle);
        tvLastTagKarat = view.findViewById(R.id.tvLastTagKarat);
        tvLastTagWeight = view.findViewById(R.id.tvLastTagWeight);
        tvLastTagStatus = view.findViewById(R.id.tvLastTagStatus);
        cardLastTag = view.findViewById(R.id.cardLastTag);

        tvExpectedTotal = view.findViewById(R.id.tvExpectedTotal);
        tvFoundCount = view.findViewById(R.id.tvFoundCount);
        tvMissingCount = view.findViewById(R.id.tvMissingCount);
        tvUnexpectedCount = view.findViewById(R.id.tvUnexpectedCount);
        tvReconciliationResult = view.findViewById(R.id.tvReconciliationResult);
        tvReaderStatus = view.findViewById(R.id.tvReaderStatus);

        tabFilterAll = view.findViewById(R.id.tabFilterAll);
        tabFilterFound = view.findViewById(R.id.tabFilterFound);
        tabFilterMissing = view.findViewById(R.id.tabFilterMissing);
        tabFilterUnexpected = view.findViewById(R.id.tabFilterUnexpected);

        pbScanProgress = view.findViewById(R.id.pbScanProgress);
        rvRecentTags = view.findViewById(R.id.rvRecentTags);

        btnStartInventory = view.findViewById(R.id.btnStartInventory);
        btnPauseInventory = view.findViewById(R.id.btnPauseInventory);
        btnStopInventory = view.findViewById(R.id.btnStopInventory);
        btnClearInventory = view.findViewById(R.id.btnClearInventory);

        btnExportInventory = view.findViewById(R.id.btnExportInventory);
        btnExportMissing = view.findViewById(R.id.btnExportMissing);
        btnScreenshot = view.findViewById(R.id.btnScreenshot);
        btnFinalizeAudit = view.findViewById(R.id.btnFinalizeAudit);
        btnSelectZone = view.findViewById(R.id.btnSelectZone);
        btnScanCamera = view.findViewById(R.id.btnScanCamera);
        btnHelpGuide = view.findViewById(R.id.btnHelpGuide);
        btnSimulateScan = view.findViewById(R.id.btnSimulateScan);

        spinnerZone = view.findViewById(R.id.spinnerZone);
        layoutZoneSelector = view.findViewById(R.id.layoutZoneSelector);
        layoutFilterTabs = view.findViewById(R.id.layoutFilterTabs);

        return true;
    }

    private void setupRecyclerView() {
        recentAdapter = new RecentTagsAdapter(filteredTags);
        rvRecentTags.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvRecentTags.setAdapter(recentAdapter);
    }

    private void setupListeners() {
        if (btnStartInventory != null) btnStartInventory.setOnClickListener(v -> startInventory());
        if (btnPauseInventory != null) btnPauseInventory.setOnClickListener(v -> togglePause());
        if (btnStopInventory != null) btnStopInventory.setOnClickListener(v -> stopInventory());
        if (btnClearInventory != null) btnClearInventory.setOnClickListener(v -> clearInventory());
        if (btnFinalizeAudit != null) btnFinalizeAudit.setOnClickListener(v -> finalizeAuditSession());

        if (btnExportInventory != null) btnExportInventory.setOnClickListener(v -> exportInventoryReport(false));
        if (btnExportMissing != null) btnExportMissing.setOnClickListener(v -> exportInventoryReport(true));
        if (btnScreenshot != null) btnScreenshot.setOnClickListener(v -> printInventoryReport());
        if (btnScanCamera != null) btnScanCamera.setOnClickListener(v -> openManualBarcodeInputDialog());
        if (btnHelpGuide != null) btnHelpGuide.setOnClickListener(v -> showUserGuideDialog());
        if (btnSimulateScan != null) btnSimulateScan.setOnClickListener(v -> generateSimulatedTag());
    }

    private void setupZoneSpinner() {
        List<String> zones = new ArrayList<>();
        zones.add(ar("الرف الرئيسي A", "Main Shelf A"));
        zones.add(ar("واجهة العرض B", "Display Window B"));
        zones.add(ar("خزنة الأمان C", "Safe Safe C"));

        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, zones);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        if (spinnerZone != null) spinnerZone.setAdapter(adapter);
    }

    private void setupFilterTabs() {
        View.OnClickListener listener = v -> {
            int id = v.getId();
            if (id == R.id.tabFilterAll) currentFilter = "all";
            else if (id == R.id.tabFilterFound) currentFilter = "found";
            else if (id == R.id.tabFilterMissing) currentFilter = "missing";
            else if (id == R.id.tabFilterUnexpected) currentFilter = "unexpected";

            updateTabStyles();
            filterAndRefreshList();
        };

        if (tabFilterAll != null) tabFilterAll.setOnClickListener(listener);
        if (tabFilterFound != null) tabFilterFound.setOnClickListener(listener);
        if (tabFilterMissing != null) tabFilterMissing.setOnClickListener(listener);
        if (tabFilterUnexpected != null) tabFilterUnexpected.setOnClickListener(listener);

        updateTabStyles();
    }

    private void updateTabStyles() {
        int activeColor = Color.parseColor("#0284C7");
        int inactiveColor = Color.parseColor("#64748B");

        if (tabFilterAll != null) tabFilterAll.setTextColor("all".equals(currentFilter) ? activeColor : inactiveColor);
        if (tabFilterFound != null) tabFilterFound.setTextColor("found".equals(currentFilter) ? activeColor : inactiveColor);
        if (tabFilterMissing != null) tabFilterMissing.setTextColor("missing".equals(currentFilter) ? activeColor : inactiveColor);
        if (tabFilterUnexpected != null) tabFilterUnexpected.setTextColor("unexpected".equals(currentFilter) ? activeColor : inactiveColor);
    }

    private void updateBranchDisplay() {
        if (tvBranchName != null && branchesManager != null) {
            tvBranchName.setText(branchesManager.getActiveBranchName());
        }
    }

    private void startReaderMonitoring() {
        readerCheckRunnable = new Runnable() {
            @Override
            public void run() {
                isReaderConnected = true; //DeviceDetector.isChainwayC72();
                if (tvReaderStatus != null) {
                    if (isReaderConnected) {
                        tvReaderStatus.setText(ar("متصل (C72)", "Connected (C72)"));
                        tvReaderStatus.setTextColor(Color.parseColor("#16A34A"));
                    } else {
                        tvReaderStatus.setText(ar("محاكي افتراضي", "Virtual Simulator"));
                        tvReaderStatus.setTextColor(Color.parseColor("#F59E0B"));
                    }
                }
                readerCheckHandler.postDelayed(this, 3000);
            }
        };
        readerCheckHandler.post(readerCheckRunnable);
    }

    private void loadOfflineGtins() {
        realGtinsForSimulation.add("0761234567890");
        realGtinsForSimulation.add("0761234567891");
        realGtinsForSimulation.add("0761234567892");
    }

    private void loadOfflineInventory() {
        for (String gtin : realGtinsForSimulation) {
            ExpectedItem item = new ExpectedItem();
            item.epc = gtin;
            item.title = "خاتم ذهب عيار 21";
            item.karat = "21K";
            item.weight = 4.25;
            item.status = ItemStatus.TRULY_MISSING;
            expectedItemsMap.put(gtin, item);
        }
    }

    private void loadDemoData() {
        for (int i = 1; i <= 10; i++) {
            String epc = "076000000000" + i;
            ExpectedItem item = new ExpectedItem();
            item.epc = epc;
            item.title = "سوار ذهبي نموذج " + i;
            item.karat = "22K";
            item.weight = 8.5;
            item.status = ItemStatus.TRULY_MISSING;
            expectedItemsMap.put(epc, item);
        }
    }

    private void openManualBarcodeInputDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle(ar("مسح / إدخال كود يدوي", "Scan / Manual EPC"));

        final EditText input = new EditText(requireContext());
        input.setHint(ar("أدخل رمز EPC أو باركود القطعة", "Enter EPC or Barcode"));
        builder.setView(input);

        builder.setPositiveButton(ar("إضافة للجرد", "Add"), (dialog, which) -> {
            String barcode = input.getText().toString().trim();
            if (!barcode.isEmpty()) {
                onTagScanned(barcode, "-50dBm");
            }
        });
        builder.setNegativeButton(ar("إلغاء", "Cancel"), (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void showUserGuideDialog() {
        new AlertDialog.Builder(requireContext())
                .setTitle(ar("دليل الجرد الذكي", "Smart Audit Guide"))
                .setMessage(ar(
                        "• اضغط لبدء القراءة عبر القارئ C72 أو المحاكي.\n" +
                        "• التنبيه الأحمر 🚨 يشير لقطعة مباعة رسمياً بالنظام وموجودة بالرف.\n" +
                        "• يتم تصفية العجز وتأكيد مطابقة الرف بناءً على السجلات السحابية.",
                        "• Press start to scan with C72 or Simulator.\n" +
                        "• Red alert 🚨 indicates an item sold in DB but found on shelf.\n" +
                        "• Reconciles missing items against cloud backend."))
                .setPositiveButton(ar("حسناً", "OK"), null)
                .show();
    }

    private void exportInventoryReport(boolean missingOnly) {
        queryExecutor.execute(() -> {
            try {
                StringBuilder csv = new StringBuilder();
                csv.append("EPC,Title,Karat,Weight,Status,ScanTime\n");

                if (missingOnly) {
                    for (ExpectedItem item : expectedItemsMap.values()) {
                        if (!item.isFound && item.status == ItemStatus.TRULY_MISSING) {
                            csv.append(item.epc).append(",")
                               .append("\"").append(item.title).append("\",")
                               .append(item.karat).append(",")
                               .append(item.weight).append(",")
                               .append(item.status.label).append(",")
                               .append("N/A\n");
                        }
                    }
                } else {
                    synchronized (recentTags) {
                        for (ScannedTag tag : recentTags) {
                            csv.append(tag.epc).append(",")
                               .append("\"").append(tag.title != null ? tag.title : "").append("\",")
                               .append(tag.karat != null ? tag.karat : "").append(",")
                               .append(tag.weight != null ? tag.weight : "").append(",")
                               .append(tag.status != null ? tag.status.label : "").append(",")
                               .append(tag.timestamp).append("\n");
                        }
                    }
                }

                File exportDir = new File(requireContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "AuditReports");
                if (!exportDir.exists()) exportDir.mkdirs();

                String filename = (missingOnly ? "Missing_Report_" : "Full_Audit_") + System.currentTimeMillis() + ".csv";
                File file = new File(exportDir, filename);

                FileOutputStream out = new FileOutputStream(file);
                out.write(csv.toString().getBytes());
                out.close();

                mainHandler.post(() -> ToastUtil.showLong(requireContext(), 
                    ar("تم تصدير التقرير: ", "Report Exported: ") + file.getAbsolutePath()));

            } catch (Exception e) {
                Log.e(TAG, "Export error: " + e.getMessage());
                mainHandler.post(() -> ToastUtil.showShort(requireContext(), ar("فشل التصدير", "Export Failed")));
            }
        });
    }

    private void printInventoryReport() {
        try {
            WebView webView = new WebView(requireContext());
            StringBuilder html = new StringBuilder();
            html.append("<html><head><style>")
                .append("body { font-family: sans-serif; direction: rtl; text-align: right; padding: 20px; }")
                .append("table { width: 100%; border-collapse: collapse; margin-top: 15px; }")
                .append("th, td { border: 1px solid #ccc; padding: 8px; text-align: right; }")
                .append("th { background-color: #f2f2f2; }")
                .append("</style></head><body>")
                .append("<h2>تقرير جرد المخزون - ").append(sessionId).append("</h2>")
                .append("<p>إجمالي المتوقع: ").append(expectedTotal.get()).append(" | المطابق: ").append(foundCount.get()).append(" | العجز: ").append(trulyMissingCount.get()).append("</p>")
                .append("<table><tr><th>EPC</th><th>البيان</th><th>العيار</th><th>الوزن</th><th>الحالة</th></tr>");

            synchronized (recentTags) {
                for (ScannedTag tag : recentTags) {
                    html.append("<tr>")
                        .append("<td>").append(tag.epc).append("</td>")
                        .append("<td>").append(tag.title != null ? tag.title : "—").append("</td>")
                        .append("<td>").append(tag.karat != null ? tag.karat : "—").append("</td>")
                        .append("<td>").append(tag.weight != null ? tag.weight : "—").append("</td>")
                        .append("<td>").append(tag.status != null ? tag.status.label : "—").append("</td>")
                        .append("</tr>");
                }
            }
            html.append("</table></body></html>");

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    PrintManager printManager = (PrintManager) requireContext().getSystemService(Context.PRINT_SERVICE);
                    if (printManager != null) {
                        printManager.print("Mira_Audit_Report", webView.createPrintDocumentAdapter("MiraAuditAdapter"), new PrintAttributes.Builder().build());
                    }
                }
            });

            webView.loadDataWithBaseURL(null, html.toString(), "text/html", "UTF-8", null);

        } catch (Exception e) {
            Log.e(TAG, "Print error: " + e.getMessage());
        }
    }

    private String ar(String arText, String enText) {
        return isArabic ? arText : enText;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopTimer();
        stopBatchProcessor();
        stopSimulatedScan();
        if (readerCheckRunnable != null) readerCheckHandler.removeCallbacks(readerCheckRunnable);
        if (soundPool != null) soundPool.release();
    }

    // --- Adapter لRecyclerView ---
    private class RecentTagsAdapter extends RecyclerView.Adapter<RecentTagsAdapter.TagViewHolder> {
        private final List<ScannedTag> list;

        RecentTagsAdapter(List<ScannedTag> list) {
            this.list = list;
        }

        @NonNull
        @Override
        public TagViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_scanned_tag, parent, false);
            return new TagViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull TagViewHolder holder, int position) {
            ScannedTag tag = list.get(position);
            holder.tvEpc.setText(tag.epc);
            holder.tvTitle.setText(tag.title != null ? tag.title : ar("جاري التحميل...", "Loading..."));
            holder.tvTime.setText(tag.timestamp);
            holder.tvRssi.setText(tag.rssi);

            if (tag.status != null) {
                holder.tvStatus.setText(tag.status.icon + " " + tag.status.label);
                holder.tvStatus.setTextColor(Color.parseColor(tag.status.colorHex));
            }
        }

        @Override
        public int getItemCount() {
            return list.size();
        }

        class TagViewHolder extends RecyclerView.ViewHolder {
            TextView tvEpc, tvTitle, tvTime, tvRssi, tvStatus;

            TagViewHolder(@NonNull View v) {
                super(v);
                tvEpc = v.findViewById(R.id.tvTagEpc);
                tvTitle = v.findViewById(R.id.tvTagTitle);
                tvTime = v.findViewById(R.id.tvTagTime);
                tvRssi = v.findViewById(R.id.tvTagRssi);
                tvStatus = v.findViewById(R.id.tvTagStatus);
            }
        }
    }
}
