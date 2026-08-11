package com.example.uhf.fragment;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
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

    // Dynamic Settings Keys
    private static final String KEY_RFID_POWER = "rfid_power";
    private static final String KEY_SCAN_SOUND = "scan_sound";
    private static final String KEY_RSSI_THRESHOLD = "rssi_threshold";
    private static final String KEY_RFID_ENABLED = "rfid_enabled";
    private static final String KEY_RFID_SCENARIO = "rfid_scenario";

    // Strict Inventory Session Lifecycle States
    public enum InventoryState {
        IDLE,           // جاهز للبدء
        LOADING,        // جاري تحميل المخزون واللقطة الحالية
        SCANNING,       // المسح نشط
        PAUSED,         // معلق مؤقتاً
        COMPLETED,      // اكتمال الجرد
        FINALIZED       // اعتُمِد رسمياً وأُغلق
    }

    // 🌟 خريطة الحالات الاحترافية الخماسية للقطع
    public enum ItemStatus {
        FOUND("مطابق", "#16A34A", "✅"),
        SOLD("مباع رسمياً", "#0284C7", "🏷️"),
        TRANSFERRED("تحويل / صيانة", "#8B5CF6", "📦"),
        TRULY_MISSING("مفقود فعلياً", "#DC2626", "❌"),
        UNEXPECTED("زائد / غير مسجل", "#F59E0B", "⚠️");

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

    // عدادات الجرد والتدقيق
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

    // Hardware Settings
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
    private int soundMatch = 0, soundUnmatch = 0;
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
        if (currentState == InventoryState.SCANNING) {
            stopInventory();
        } else if (currentState == InventoryState.IDLE || currentState == InventoryState.COMPLETED) {
            startInventory();
        }
    }

    public static class ExpectedItem {
        public String epc, title, karat;
        public double weight;
        public ItemStatus status = ItemStatus.TRULY_MISSING;
        public boolean isFound = false;
        public String scanTime;
        public String dbState = "AVAILABLE"; // AVAILABLE, SOLD, TRANSFERRED, IN_MAINTENANCE
        public boolean wasMissingInLastAudit = false;
    }

    static class ScannedTag {
        String epc, rssi, title, karat, weight, timestamp;
        ItemStatus status;
        boolean isExpected;
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
                        "هناك جلسة جرد نشطة حالياً.\nالخروج دون الاعتماد سيوقف القراءة ويحفظ المسودة كجرد غير مكتمل.\n\nهل ترغب في الخروج؟",
                        "Active inventory session.\nExiting without finalization will pause scanning and keep draft.\n\nAre you sure you want to exit?"))
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

    @Override
    public void onResume() {
        super.onResume();
        loadGlobalSettings();
        checkReaderStatus();
    }

    // ==================== STATE ENGINE & UI CONTROLS ====================

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

    private void loadGlobalSettings() {
        if (getContext() == null) return;
        try {
            globalSettings = PreferenceManager.getDefaultSharedPreferences(requireContext());

            String soundVal = globalSettings.getString(KEY_SCAN_SOUND, "1");
            isSoundEnabled = "1".equals(soundVal) || globalSettings.getBoolean(KEY_SCAN_SOUND, true);

            try {
                String powerStr = globalSettings.getString(KEY_RFID_POWER, "28");
                configuredPowerDbm = Integer.parseInt(powerStr);
            } catch (Exception e) {
                configuredPowerDbm = globalSettings.getInt(KEY_RFID_POWER, 28);
            }

            try {
                String rssiStr = globalSettings.getString(KEY_RSSI_THRESHOLD, "-100");
                configuredRssiThreshold = Integer.parseInt(rssiStr);
            } catch (Exception e) {
                configuredRssiThreshold = globalSettings.getInt(KEY_RSSI_THRESHOLD, -100);
            }

            String rfidEnabledVal = globalSettings.getString(KEY_RFID_ENABLED, "1");
            rfidEnabledConfig = "1".equals(rfidEnabledVal) || globalSettings.getBoolean(KEY_RFID_ENABLED, true);
            rfidScenarioConfig = globalSettings.getString(KEY_RFID_SCENARIO, "real");
        } catch (Exception e) {
            Log.e(TAG, "Error loading global settings: " + e.getMessage());
        }
    }

    private void applyHardwareSettings() {
        try {
            if (getActivity() instanceof UHFMainActivity && isReaderConnected) {
                ((UHFMainActivity) getActivity()).mReader.setPower(configuredPowerDbm);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply hardware power settings: " + e.getMessage());
        }
    }

    private void initSoundPool() {
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            soundPool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attrs).build();
            soundPool.setOnLoadCompleteListener((pool, sampleId, status) -> { if (status == 0) soundLoaded = true; });
            if (getContext() != null) {
                soundMatch = soundPool.load(getContext(), R.raw.barcodebeep, 1);
                soundUnmatch = soundPool.load(getContext(), R.raw.serror, 2);
            }
        } catch (Exception e) { Log.e(TAG, "Sound init error: " + e.getMessage()); }
    }

    private void playSound(int soundId) {
        if (!isSoundEnabled || soundId == 0) return;
        mainHandler.post(() -> {
            try {
                if (soundPool != null && soundLoaded) soundPool.play(soundId, 0.7f, 0.7f, 1, 0, 1f);
            } catch (Exception e) { Log.e(TAG, "playSound error: " + e.getMessage()); }
        });
    }

    private boolean initViews(View v) {
        rootLayout = v.findViewById(R.id.rootLayout);
        tvTotalScanned = v.findViewById(R.id.tvTotalScanned); tvUniqueTags = v.findViewById(R.id.tvUniqueTags);
        tvScanRate = v.findViewById(R.id.tvScanRate); tvElapsed = v.findViewById(R.id.tvElapsed);
        tvBranchName = v.findViewById(R.id.tvBranchName); tvLastTagEPC = v.findViewById(R.id.tvLastTagEPC);
        tvLastTagTitle = v.findViewById(R.id.tvLastTagTitle); tvLastTagKarat = v.findViewById(R.id.tvLastTagKarat);
        tvLastTagWeight = v.findViewById(R.id.tvLastTagWeight); tvLastTagStatus = v.findViewById(R.id.tvLastTagStatus);
        tvExpectedTotal = v.findViewById(R.id.tvExpectedTotal); tvFoundCount = v.findViewById(R.id.tvFoundCount);
        tvMissingCount = v.findViewById(R.id.tvMissingCount); tvUnexpectedCount = v.findViewById(R.id.tvUnexpectedCount);
        tvReconciliationResult = v.findViewById(R.id.tvReconciliationResult); tvReaderStatus = v.findViewById(R.id.tvReaderStatus);
        cardLastTag = v.findViewById(R.id.cardLastTag); pbScanProgress = v.findViewById(R.id.pbScanProgress);
        rvRecentTags = v.findViewById(R.id.rvRecentTags); btnStartInventory = v.findViewById(R.id.btnStartInventory);
        btnPauseInventory = v.findViewById(R.id.btnPauseInventory); btnStopInventory = v.findViewById(R.id.btnStopInventory);
        btnClearInventory = v.findViewById(R.id.btnClearInventory); btnExportInventory = v.findViewById(R.id.btnExportInventory);
        btnExportMissing = v.findViewById(R.id.btnExportMissing); btnScreenshot = v.findViewById(R.id.btnScreenshot);
        btnFinalizeAudit = v.findViewById(R.id.btnFinalizeAudit); btnSelectZone = v.findViewById(R.id.btnSelectZone);
        btnScanCamera = v.findViewById(R.id.btnScanCamera); btnHelpGuide = v.findViewById(R.id.btnHelpGuide);
        btnSimulateScan = v.findViewById(R.id.btnSimulateScan); spinnerZone = v.findViewById(R.id.spinnerZone);
        layoutZoneSelector = v.findViewById(R.id.layoutZoneSelector); layoutFilterTabs = v.findViewById(R.id.layoutFilterTabs);
        tabFilterAll = v.findViewById(R.id.tabFilterAll); tabFilterFound = v.findViewById(R.id.tabFilterFound);
        tabFilterMissing = v.findViewById(R.id.tabFilterMissing); tabFilterUnexpected = v.findViewById(R.id.tabFilterUnexpected);
        resetUI(); return true;
    }

    private void setupRecyclerView() {
        if (getContext() == null || rvRecentTags == null) return;
        rvRecentTags.setLayoutManager(new LinearLayoutManager(getContext()));
        recentAdapter = new RecentTagsAdapter(filteredTags);
        rvRecentTags.setAdapter(recentAdapter);
        rvRecentTags.setNestedScrollingEnabled(true);
        rvRecentTags.setHasFixedSize(true);
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
        if (btnScanCamera != null) btnScanCamera.setOnClickListener(v -> quickScanMode());
        if (btnHelpGuide != null) btnHelpGuide.setOnClickListener(v -> showHelpGuide());
        if (btnSimulateScan != null) btnSimulateScan.setOnClickListener(v -> toggleSimulation());
    }

    private void setupZoneSpinner() {
        if (spinnerZone == null || getContext() == null) return;
        List<String> zones = new ArrayList<>();
        zones.add(ar("— كل المناطق / الرفوف —", "— All Zones / Shelves —"));
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

    private void applyFilter(String filter) { currentFilter = filter; updateFilterTabStyles(); updateFilteredList(); }

    private void updateFilterTabStyles() {
        if (tabFilterAll == null) return;
        tabFilterAll.setBackgroundColor("all".equals(currentFilter) ? Color.parseColor("#FDF8E7") : Color.TRANSPARENT);
        tabFilterAll.setTextColor("all".equals(currentFilter) ? Color.parseColor("#D4AF37") : Color.parseColor("#4B5563"));
        if (tabFilterFound != null) { tabFilterFound.setBackgroundColor("found".equals(currentFilter) ? Color.parseColor("#DCFCE7") : Color.TRANSPARENT); tabFilterFound.setTextColor("found".equals(currentFilter) ? Color.parseColor("#16A34A") : Color.parseColor("#4B5563")); }
        if (tabFilterMissing != null) { tabFilterMissing.setBackgroundColor("missing".equals(currentFilter) ? Color.parseColor("#FEE2E2") : Color.TRANSPARENT); tabFilterMissing.setTextColor("missing".equals(currentFilter) ? Color.parseColor("#DC2626") : Color.parseColor("#4B5563")); }
        if (tabFilterUnexpected != null) { tabFilterUnexpected.setBackgroundColor("unexpected".equals(currentFilter) ? Color.parseColor("#FEF3C7") : Color.TRANSPARENT); tabFilterUnexpected.setTextColor("unexpected".equals(currentFilter) ? Color.parseColor("#F59E0B") : Color.parseColor("#4B5563")); }
    }

    private void updateFilteredList() {
        List<ScannedTag> temp = new ArrayList<>();
        synchronized (recentTags) {
            for (ScannedTag t : recentTags) {
                switch (currentFilter) {
                    case "found": if (t.status == ItemStatus.FOUND) temp.add(t); break;
                    case "unexpected": if (t.status == ItemStatus.UNEXPECTED) temp.add(t); break;
                    case "missing": if (t.status == ItemStatus.TRULY_MISSING) temp.add(t); break;
                    default: temp.add(t);
                }
            }
        }
        mainHandler.post(() -> {
            filteredTags.clear();
            filteredTags.addAll(temp);
            if (recentAdapter != null) recentAdapter.notifyDataSetChanged();
        });
    }

    private void updateBranchDisplay() {
        if (tvBranchName != null && branchesManager != null) {
            tvBranchName.setText("📍 " + branchesManager.getActiveBranchDisplayName());
        }
    }

    private void startReaderMonitoring() {
        readerCheckRunnable = () -> {
            checkReaderStatus();
            readerCheckHandler.postDelayed(readerCheckRunnable, 2500);
        };
        readerCheckHandler.post(readerCheckRunnable);
    }

    private void checkReaderStatus() {
        try {
            if (getActivity() instanceof UHFMainActivity) {
                isReaderConnected = ((UHFMainActivity) getActivity()).isReaderReady() && DeviceDetector.isReady();
                if (tvReaderStatus != null) {
                    if (isReaderConnected) {
                        DeviceDetector.DeviceProfile profile = DeviceDetector.getProfile();
                        String name = profile != null ? profile.modelName : DeviceDetector.getDeviceModelName();
                        tvReaderStatus.setText("🟢 " + name + " | " + configuredPowerDbm + "dBm");
                        tvReaderStatus.setTextColor(Color.parseColor("#16A34A"));
                    } else {
                        tvReaderStatus.setText(ar("🟡 Simulation / Camera Ready", "🟡 Simulation / Camera Ready"));
                        tvReaderStatus.setTextColor(Color.parseColor("#D4AF37"));
                    }
                }
            }
        } catch (Exception e) { Log.e(TAG, "checkReaderStatus error: " + e.getMessage()); }
    }

    // ==================== QUICK SCAN ====================

    private void quickScanMode() {
        if (currentState == InventoryState.SCANNING) { startCameraScan(); return; }
        String[] options = {
                ar("📷 مسح باركود/QR", "📷 Scan Barcode/QR"),
                ar("✏️ إدخال EPC يدوياً", "✏️ Enter EPC Manually"),
                ar("🎯 محاكاة بأرقام المخزون", "🎯 Simulate with Stock"),
                ar("📋 عرض المخزون المتوقع", "📋 View Expected Stock")
        };
        new AlertDialog.Builder(requireContext())
                .setTitle(ar("المسح السريع", "Quick Scan"))
                .setItems(options, (d, w) -> {
                    switch (w) {
                        case 0: startCameraScan(); break;
                        case 1: showManualEntryDialog(); break;
                        case 2: startInventoryWithSimulation(); break;
                        case 3: showExpectedStockList(); break;
                    }
                }).show();
    }

    private void startInventoryWithSimulation() {
        startInventoryAndExec(() -> {
            isSimulating = true;
            if (btnSimulateScan != null) btnSimulateScan.setText(ar("⏹ إيقاف", "⏹ Stop"));
            startSimulation();
        });
    }

    private void showExpectedStockList() {
        if (expectedItemsMap.isEmpty()) { loadExpectedInventory(this::displayStockList); } else { displayStockList(); }
    }

    private void displayStockList() {
        if (expectedItemsMap.isEmpty()) { ToastUtil.showWarning(ar("لا يوجد مخزون", "No stock")); return; }
        StringBuilder sb = new StringBuilder();
        sb.append(ar("إجمالي المتوقع المخزني: ", "Total Expected Stock: ")).append(expectedItemsMap.size()).append("\n\n");
        int c = 0;
        for (ExpectedItem i : expectedItemsMap.values()) {
            c++;
            sb.append(c).append(". ").append(i.epc).append("\n   ")
                    .append(i.title).append(" | ").append(i.karat).append(" | ")
                    .append(String.format(Locale.US, "%.2fg", i.weight))
                    .append(" [").append(i.status.label).append("]\n\n");
            if (c >= 30) {
                sb.append("... +").append(expectedItemsMap.size() - 30).append(ar(" أكثر", " more"));
                break;
            }
        }
        TextView tv = new TextView(getContext()); tv.setText(sb.toString()); tv.setTextSize(11); tv.setPadding(24, 16, 24, 16); tv.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(getContext()); sv.addView(tv);
        new AlertDialog.Builder(requireContext())
                .setTitle(ar("📋 بيان المخزون المتوقع", "📋 Expected Stock Manifest"))
                .setView(sv)
                .setPositiveButton(ar("إغلاق", "Close"), null)
                .setNeutralButton(ar("🎯 بدء محاكاة", "🎯 Start Simulation"), (d, w) -> startInventoryWithSimulation())
                .show();
    }

    private void startCameraScan() {
        try {
            Intent intent = new Intent("com.google.zxing.client.android.SCAN");
            intent.putExtra("SCAN_MODE", "QR_CODE_MODE,DATA_MATRIX_MODE,PRODUCT_MODE");
            if (getActivity() != null && intent.resolveActivity(getActivity().getPackageManager()) != null) {
                startActivityForResult(intent, REQUEST_CAMERA_SCAN);
            } else {
                ToastUtil.showInfo(ar("الكاميرا غير متاحة", "Camera unavailable"));
                showManualEntryDialog();
            }
        } catch (Exception e) { showManualEntryDialog(); }
    }

    private void showManualEntryDialog() {
        if (getContext() == null) return;
        EditText input = new EditText(getContext());
        input.setHint(ar("أدخل EPC أو GTIN", "Enter EPC or GTIN"));
        input.setPadding(32, 24, 32, 24);
        new AlertDialog.Builder(getContext())
                .setTitle(ar("إدخال يدوي", "Manual Entry"))
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
        if (currentState != InventoryState.SCANNING) startInventoryAndExec(() -> onTagScanned(epc, "MANUAL")); else onTagScanned(epc, "MANUAL");
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CAMERA_SCAN && resultCode == android.app.Activity.RESULT_OK && data != null) {
            String barcode = data.getStringExtra("SCAN_RESULT");
            if (barcode != null && !barcode.isEmpty()) processManualEntry(barcode);
        }
    }

    // ==================== INVENTORY LOGIC ====================

    private void startInventory() { startInventoryAndExec(null); }

    private void startInventoryAndExec(Runnable postAction) {
        setInventoryState(InventoryState.LOADING);
        loadGlobalSettings();
        applyHardwareSettings();

        exitWarningShown = false; startTime = System.currentTimeMillis(); totalPausedDuration = 0;
        totalScanned.set(0); uniqueTags.set(0); recentTags.clear(); scannedEPCs.clear(); scannedEPCSet.clear(); batchQueue.clear(); filteredTags.clear();
        sessionId = "AUDIT-" + System.currentTimeMillis();
        if (recentAdapter != null) recentAdapter.notifyDataSetChanged();

        if (getActivity() instanceof UHFMainActivity && isReaderConnected) ((UHFMainActivity) getActivity()).loopFlag = true;

        startBatchScheduler(); startTimer();
        if (layoutZoneSelector != null) layoutZoneSelector.setVisibility(View.VISIBLE);
        if (layoutFilterTabs != null) layoutFilterTabs.setVisibility(View.VISIBLE);

        loadExpectedInventory(() -> {
            setInventoryState(InventoryState.SCANNING);
            if (!rfidEnabledConfig || "simulate".equalsIgnoreCase(rfidScenarioConfig)) {
                toggleSimulation();
            }
            if (postAction != null) postAction.run();
        });
    }

    private void startBatchScheduler() {
        stopBatchScheduler();
        batchScheduler = Executors.newSingleThreadScheduledExecutor();
        batchScheduler.scheduleWithFixedDelay(this::flushBatch, 400, 400, TimeUnit.MILLISECONDS);
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
        while ((tag = batchQueue.poll()) != null && batch.size() < 100) batch.add(tag);

        if (!batch.isEmpty()) {
            synchronized (recentTags) { recentTags.addAll(0, batch); }
            mainHandler.post(() -> {
                updateFilteredList();
                updateReconciliationCounters();
                updateReconciliationResult();
                updateUI();
            });
        }
    }

    private void toggleSimulation() {
        if (currentState != InventoryState.SCANNING) startInventory();
        isSimulating = !isSimulating;
        if (isSimulating) {
            if (btnSimulateScan != null) btnSimulateScan.setText(ar("⏹ إيقاف", "⏹ Stop"));
            startSimulation();
        } else {
            if (btnSimulateScan != null) btnSimulateScan.setText(ar("🎯 محاكاة", "🎯 Simulate"));
            stopSimulation();
        }
    }

    private void startSimulation() {
        simulationRunnable = () -> {
            if (currentState == InventoryState.SCANNING && isSimulating) {
                String epc;
                if (!realGtinsForSimulation.isEmpty()) {
                    epc = realGtinsForSimulation.get(new Random().nextInt(realGtinsForSimulation.size()));
                } else if (!expectedItemsMap.isEmpty()) {
                    List<String> keys = new ArrayList<>(expectedItemsMap.keySet());
                    epc = keys.get(new Random().nextInt(keys.size()));
                } else {
                    epc = "E2801170" + String.format(Locale.US, "%08d", 10000000 + new Random().nextInt(90000000));
                }
                onTagScanned(epc, "-65dBm");
                simulationHandler.postDelayed(simulationRunnable, 500);
            }
        };
        simulationHandler.post(simulationRunnable);
    }

    private void stopSimulation() {
        if (simulationRunnable != null) simulationHandler.removeCallbacks(simulationRunnable);
    }

    private void togglePause() {
        if (currentState == InventoryState.SCANNING) {
            setInventoryState(InventoryState.PAUSED);
            pauseStartTime = System.currentTimeMillis();
            if (getActivity() instanceof UHFMainActivity) ((UHFMainActivity) getActivity()).loopFlag = false;
            stopTimer();
        } else if (currentState == InventoryState.PAUSED) {
            setInventoryState(InventoryState.SCANNING);
            totalPausedDuration += (System.currentTimeMillis() - pauseStartTime);
            if (getActivity() instanceof UHFMainActivity && isReaderConnected) ((UHFMainActivity) getActivity()).loopFlag = true;
            startTimer();
        }
    }

    private void stopInventory() {
        stopSimulation();
        stopBatchScheduler();
        if (getActivity() instanceof UHFMainActivity) ((UHFMainActivity) getActivity()).loopFlag = false;
        stopTimer();
        setInventoryState(InventoryState.COMPLETED);
        updateUI();
        updateReconciliationCounters();
        updateReconciliationResult();
    }

    private void clearInventory() {
        stopSimulation();
        stopBatchScheduler();
        totalScanned.set(0); uniqueTags.set(0); foundCount.set(0); soldCount.set(0);
        transferredCount.set(0); trulyMissingCount.set(0); unexpectedCount.set(0); expectedTotal.set(0);
        recentTags.clear(); scannedEPCs.clear(); scannedEPCSet.clear(); batchQueue.clear(); filteredTags.clear(); expectedItemsMap.clear();
        if (recentAdapter != null) recentAdapter.notifyDataSetChanged();
        resetUI();
        setInventoryState(InventoryState.IDLE);
    }

    // ==================== RECONCILIATION ENGINE & AUTO-STOP ====================

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
            if (item != null && !item.isFound) {
                item.isFound = true;
                item.scanTime = time;

                // 🌟 تصنيف الذكاء المخزني (إمكانية ظهور قطعة مفقودة من الجرد السابق)
                if (item.wasMissingInLastAudit) {
                    Log.i(TAG, "🎉 Recovered previously missing item: " + cleanEpc);
                }

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
        } else {
            unexpectedCount.incrementAndGet();
            tag.status = ItemStatus.UNEXPECTED;
            tag.title = ar("غير مسجلة / فرع آخر", "Unregistered / Extra");
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

        // 🌟 إيقاف تلقائي ذكي فور الوصول لمطابقة 100% من المتوقع المتوفر
        int targetMatches = expectedTotal.get() - (soldCount.get() + transferredCount.get());
        if (targetMatches > 0 && foundCount.get() == targetMatches) {
            mainHandler.post(() -> {
                stopInventory();
                showCompletionModal();
            });
        }
    }

    private void showCompletionModal() {
        if (getContext() == null) return;
        new AlertDialog.Builder(requireContext())
                .setTitle(ar("🎉 اكتمل الجرد بنجاح 100%!", "🎉 Audit Complete 100%!"))
                .setMessage(ar(
                        "تم العثور على جميع القطع المتوفرة بالرفوف بنسبة 100% (" + foundCount.get() + " قطعة).\n\nهل ترغب في اعتماد التقرير وإغلاق الجلسة؟",
                        "All available shelf items matched 100% (" + foundCount.get() + " items).\n\nDo you want to finalize and lock session?"
                ))
                .setPositiveButton(ar("✅ اعتماد الجرد رسمياً", "Finalize Audit Now"), (d, w) -> finalizeAudit())
                .setNegativeButton(ar("مراجعة التفاصيل", "Review Manifest"), null)
                .setCancelable(false)
                .show();
    }

    // ==================== API INTEGRATION & SNAPSHOTS ====================

    private void loadExpectedInventory(Runnable onReady) {
        Log.i(TAG, "🔄 Fetching store inventory snapshot from API...");
        queryExecutor.execute(() -> {
            try {
                int branchId = branchesManager != null ? branchesManager.getActiveBranchId() : 1;
                MiraApiClient.ApiResponse response = apiClient.getBranchItemsList(branchId);

                expectedItemsMap.clear();
                soldCount.set(0); transferredCount.set(0);

                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray items = response.data.optJSONArray("items");
                    if (items != null && items.length() > 0) {
                        List<String> gtins = new ArrayList<>();
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject obj = items.getJSONObject(i);
                            ExpectedItem item = new ExpectedItem();
                            item.epc = obj.optString("serial_number", obj.optString("gtin13", obj.optString("epc", "")));
                            item.title = obj.optString("item_title", obj.optString("title", ""));
                            item.karat = obj.optString("gold_karat", obj.optString("karat", ""));
                            item.weight = obj.optDouble("gross_weight_grams", obj.optDouble("weight", 0));
                            item.dbState = obj.optString("status", "AVAILABLE").toUpperCase();

                            if (!item.epc.isEmpty()) {
                                // 🌟 تصنيف الحالة الابتدائية من السيرفر
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
                                gtins.add(item.epc);
                            }
                        }
                        if (!gtins.isEmpty()) {
                            realGtinsForSimulation.clear();
                            realGtinsForSimulation.addAll(gtins);
                            saveGtinsOffline(gtins);
                        }
                    }
                }

                if (expectedItemsMap.isEmpty()) loadOfflineInventory();
                if (expectedItemsMap.isEmpty()) loadDemoData();

                int total = expectedItemsMap.size();
                int missing = total - (soldCount.get() + transferredCount.get());
                expectedTotal.set(total);
                trulyMissingCount.set(Math.max(0, missing));

                final int count = total;
                mainHandler.post(() -> ToastUtil.showSuccess(ar("✅ تم تحميل " + count + " قطعة متوقعة", "✅ Loaded " + count + " expected items")));
            } catch (Exception e) {
                Log.e(TAG, "❌ load error: " + e.getMessage());
                loadOfflineInventory();
                if (expectedItemsMap.isEmpty()) loadDemoData();
                expectedTotal.set(expectedItemsMap.size());
                trulyMissingCount.set(expectedItemsMap.size());
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

    private void queryItemFromApi(String epc) {
        queryExecutor.execute(() -> {
            try {
                JSONObject params = new JSONObject();
                params.put("branch_id", branchesManager.getActiveBranchId());
                apiClient.authorize(epc, "inventory", params);
            } catch (Exception ignored) {}
        });
    }

    // ==================== AUDIT FINALIZATION ====================

    private void finalizeAudit() {
        if (currentState == InventoryState.SCANNING) {
            ToastUtil.showWarning(ar("يرجى إيقاف المسح أولاً قبل الاعتماد", "Please stop scan before finalizing"));
            return;
        }

        int trulyMissing = trulyMissingCount.get();
        int extra = unexpectedCount.get();
        int sold = soldCount.get();

        StringBuilder msg = new StringBuilder();
        msg.append(ar("إجمالي الجرد: ", "Total Audit: ")).append(expectedTotal.get()).append("\n")
           .append(ar("✅ المطابق في الرف: ", "✅ Found on Shelf: ")).append(foundCount.get()).append("\n")
           .append(ar("🏷️ المغادرات المبررة (مباع/تحويل): ", "🏷️ Justified Out (Sold/Moved): ")).append(sold + transferredCount.get()).append("\n")
           .append(ar("❌ العجز الفعلي الحقيقي: ", "❌ Actual True Shortage: ")).append(trulyMissing).append("\n")
           .append(ar("⚠️ الزيادات والقطع غير المسجلة: ", "⚠️ Unregistered / Extra: ")).append(extra);

        if (trulyMissing > 0 || extra > 0) {
            msg.append(ar(
                    "\n\n⚠️ تنبيه هام: سيتم اعتماد هذا العجز رسميًا وتقييده في سجل التدقيق المالي للمحل.",
                    "\n\n⚠️ Warning: Variances will be officially booked to store ledger."
            ));
        }

        new AlertDialog.Builder(requireContext())
                .setTitle(ar("تأكيد اعتماد الجرد وإغلاق الجلسة", "Confirm Audit Finalization & Lock"))
                .setMessage(msg.toString())
                .setPositiveButton(ar("نعم، اعتمد واغلق الجلسة", "Yes, Finalize & Lock"), (d, w) -> sendAuditToApi())
                .setNegativeButton(ar("مراجعة", "Review"), null)
                .show();
    }

    private void sendAuditToApi() {
        if (pbScanProgress != null) pbScanProgress.setVisibility(View.VISIBLE);
        queryExecutor.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("session_id", sessionId);
                payload.put("branch_id", branchesManager.getActiveBranchId());
                payload.put("total_expected", expectedTotal.get());
                payload.put("total_found", foundCount.get());
                payload.put("total_sold", soldCount.get());
                payload.put("total_transferred", transferredCount.get());
                payload.put("total_truly_missing", trulyMissingCount.get());
                payload.put("total_unexpected", unexpectedCount.get());

                // حفظ الأرقام المفقودة للجرد القادم للمقارنة التاريخية
                saveCurrentMissingAuditSnapshot();

                MiraApiClient.ApiResponse res = apiClient.authorize(sessionId, "finalize_inventory_audit", payload);

                mainHandler.post(() -> {
                    if (pbScanProgress != null) pbScanProgress.setVisibility(View.GONE);
                    ToastUtil.showSuccess(ar("✅ تم اعتماد وأرشفة التقرير في السيرفر بنجاح", "✅ Audit officially finalized & archived"));
                    setInventoryState(InventoryState.FINALIZED);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (pbScanProgress != null) pbScanProgress.setVisibility(View.GONE);
                    ToastUtil.showError(ar("فشل إرسال اعتماد الجرد للسيرفر (تم الحفظ محلياً)", "Audit finalization failed (Saved locally)"));
                });
            }
        });
    }

    private void saveCurrentMissingAuditSnapshot() {
        try {
            JSONArray arr = new JSONArray();
            for (ExpectedItem item : expectedItemsMap.values()) {
                if (!item.isFound && item.status == ItemStatus.TRULY_MISSING) {
                    arr.put(item.epc);
                }
            }
            if (offlineCache != null) {
                offlineCache.edit().putString(PREF_LAST_AUDIT_MISSING, arr.toString()).apply();
            }
        } catch (Exception ignored) {}
    }

    private void loadPreviousAuditSnapshot() {
        try {
            if (offlineCache == null) return;
            String json = offlineCache.getString(PREF_LAST_AUDIT_MISSING, "");
            if (!json.isEmpty()) {
                JSONArray arr = new JSONArray(json);
                previousAuditMissingEPCs.clear();
                for (int i = 0; i < arr.length(); i++) {
                    String epc = arr.optString(i, "");
                    if (!epc.isEmpty()) previousAuditMissingEPCs.add(epc);
                }
            }
        } catch (Exception ignored) {}
    }

    // ==================== OFFLINE & DEMO ====================

    private void saveGtinsOffline(List<String> gtins) {
        try {
            JSONArray arr = new JSONArray();
            for (String g : gtins) arr.put(g);
            if (offlineCache != null) {
                offlineCache.edit().putString("real_gtins", arr.toString()).apply();
            }
        } catch (Exception ignored) {}
    }

    private void loadOfflineGtins() {
        try {
            if (offlineCache == null) return;
            String json = offlineCache.getString("real_gtins", "");
            if (!json.isEmpty()) {
                JSONArray arr = new JSONArray(json);
                List<String> gtins = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    String g = arr.optString(i, "");
                    if (!g.isEmpty()) gtins.add(g);
                }
                if (!gtins.isEmpty()) {
                    realGtinsForSimulation.clear();
                    realGtinsForSimulation.addAll(gtins);
                }
            }
        } catch (Exception ignored) {}
    }

    private void loadOfflineInventory() {
        try {
            if (offlineCache == null) return;
            String json = offlineCache.getString("inventory_items", "");
            if (!json.isEmpty()) {
                JSONArray items = new JSONArray(json);
                for (int i = 0; i < items.length(); i++) {
                    JSONObject obj = items.optJSONObject(i);
                    if (obj == null) continue;
                    ExpectedItem item = new ExpectedItem();
                    item.epc = obj.optString("epc", "");
                    item.title = obj.optString("title", "");
                    item.karat = obj.optString("karat", "");
                    item.weight = obj.optDouble("weight", 0);
                    item.status = ItemStatus.TRULY_MISSING;
                    if (!item.epc.isEmpty()) expectedItemsMap.put(item.epc, item);
                }
            }
        } catch (Exception ignored) {}
    }

    private void loadDemoData() {
        Log.w(TAG, "⚠️ Loading demo inventory data");
        for (int i = 1; i <= 50; i++) {
            ExpectedItem item = new ExpectedItem();
            item.epc = "E28011700000000000000" + String.format(Locale.US, "%03d", i);
            item.title = "خاتم ذهب عيار " + ((i % 2 == 0) ? "21" : "18");
            item.karat = (i % 2 == 0) ? "21K" : "18K";
            item.weight = 2.5 + (i * 0.4);
            item.status = ItemStatus.TRULY_MISSING;
            expectedItemsMap.put(item.epc, item);
        }
    }

    // ==================== UI UPDATES & REPORTING ====================

    private void updateLastTagDisplay(String epc, String title, String karat, String weight, ItemStatus status) {
        if (tvLastTagEPC != null) tvLastTagEPC.setText(epc);
        if (tvLastTagTitle != null) tvLastTagTitle.setText(title);
        if (tvLastTagKarat != null) tvLastTagKarat.setText(karat);
        if (tvLastTagWeight != null) tvLastTagWeight.setText(weight);
        if (tvLastTagStatus != null) {
            tvLastTagStatus.setText(status.icon + " " + status.label);
            tvLastTagStatus.setTextColor(Color.parseColor(status.colorHex));
        }
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
        int extra = unexpectedCount.get();
        int totalAvailable = expectedTotal.get() - (soldCount.get() + transferredCount.get());
        int found = foundCount.get();

        if (expectedTotal.get() == 0) {
            tvReconciliationResult.setText(ar("📡 جاهز لبدء الجرد والتحقق المخزني", "📡 Ready for inventory audit"));
            tvReconciliationResult.setTextColor(Color.parseColor("#4B5563"));
            if (pbScanProgress != null) pbScanProgress.setProgress(0);
        } else if (missing == 0 && extra == 0) {
            tvReconciliationResult.setText(ar("✅ اكتمال وتطابق تام 100% مع الرفوف", "✅ Perfect 100% Shelf Match"));
            tvReconciliationResult.setTextColor(Color.parseColor("#16A34A"));
            if (pbScanProgress != null) pbScanProgress.setProgress(100);
        } else {
            int progress = totalAvailable > 0 ? (found * 100 / totalAvailable) : 0;
            tvReconciliationResult.setText(ar(
                    "📊 " + found + "/" + totalAvailable + " (" + progress + "%) | عجز حقيقي: " + missing + " | زائد: " + extra,
                    "📊 " + found + "/" + totalAvailable + " (" + progress + "%) | True Shortage: " + missing + " | Extra: " + extra));
            tvReconciliationResult.setTextColor(missing > 0 ? Color.parseColor("#DC2626") : Color.parseColor("#F59E0B"));
            if (pbScanProgress != null) pbScanProgress.setProgress(Math.min(100, progress));
        }
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
        updateReconciliationResult();
    }

    private void selectZone() {
        if (spinnerZone != null) ToastUtil.showInfo(ar("تم تحديد المنطقة: ", "Zone Selected: ") + spinnerZone.getSelectedItem());
    }

    private void showHelpGuide() {
        new AlertDialog.Builder(requireContext())
                .setTitle(ar("دليل الجرد الشامل والتدقيق القياسي", "Standard Audit Guide"))
                .setMessage(ar(
                        "1. اضغط [بدء الجرد] لجلب لقطة المخزون المتوقعة.\n" +
                        "2. يتم تحييد القطع المباعة أو المحولة تلقائياً لتفادي احتسابها كعجز.\n" +
                        "3. يتوقف المسح تلقائياً عند قراءة 100% من معروض الرفوف.\n" +
                        "4. عند الاعتماد تُحفظ النتائج وتُقارن تلقائياً مع الجرد القادم.",
                        "1. Press [Start Inventory] to fetch snapshot.\n" +
                        "2. Sold/Transferred items are excluded from shortage.\n" +
                        "3. Scan auto-stops on 100% shelf match.\n" +
                        "4. Finalized audits are logged for historical comparison."))
                .setPositiveButton("فهمت", null)
                .show();
    }

    private void printReport(boolean onlyMissing) {
        if (getContext() == null) return;
        if (currentState == InventoryState.SCANNING) {
            ToastUtil.showWarning(ar("لا يمكن طباعة التقرير أثناء عملية مسح جارية!", "Cannot print while scanning is active!"));
            return;
        }

        String orgName = orgProfile != null ? orgProfile.getOrgName() : "MIRA Jewelry";
        String branchName = branchesManager != null ? branchesManager.getActiveBranchDisplayName() : "";
        String currentTime = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());
        long elapsed = (System.currentTimeMillis() - startTime - totalPausedDuration) / 1000;

        StringBuilder h = new StringBuilder();
        h.append("<!DOCTYPE html><html dir='rtl'><head><meta charset='UTF-8'><style>")
                .append("body{font-family:sans-serif;padding:16px;background:#fff;color:#111;font-size:11px}")
                .append(".header{text-align:center;border-bottom:2px solid #D4AF37;padding-bottom:10px;margin-bottom:12px}")
                .append(".header h2{color:#1A237E;margin:0;font-size:16px}")
                .append(".summary{display:flex;gap:8px;margin:12px 0;flex-wrap:wrap}")
                .append(".card{flex:1;min-width:70px;text-align:center;padding:8px;background:#F9FAFB;border-radius:8px;border:1px solid #E5E7EB}")
                .append(".card .val{font-size:16px;font-weight:bold;color:#1A237E}")
                .append(".card .lbl{font-size:9px;color:#666}")
                .append("table{width:100%;border-collapse:collapse;margin:10px 0}")
                .append("th{background:#1A237E;color:#fff;padding:6px;font-size:10px}")
                .append("td{padding:5px;border-bottom:1px solid #eee;text-align:center;font-size:10px}")
                .append(".footer{text-align:center;margin-top:16px;font-size:9px;color:#999}")
                .append("</style></head><body>")
                .append("<div class='header'><h2>").append(orgName).append("</h2><p>").append(branchName).append(" | ").append(currentTime).append(" | جلسة: ").append(sessionId).append("</p></div>")
                .append("<div style='text-align:center;font-weight:bold;color:#92400E;margin:8px 0'>")
                .append(onlyMissing ? ar("تقرير العجز والنواقص الفعلي", "Actual Shortage Report") : ar("تقرير التدقيق والجرد الشامل", "Full Audit Reconciliation Report"))
                .append("</div>")
                .append("<div class='summary'>")
                .append("<div class='card'><div class='val'>").append(expectedTotal.get()).append("</div><div class='lbl'>").append(ar("المتوقع", "Expected")).append("</div></div>")
                .append("<div class='card'><div class='val' style='color:#16A34A'>").append(foundCount.get()).append("</div><div class='lbl'>").append(ar("مطابق", "Found")).append("</div></div>")
                .append("<div class='card'><div class='val' style='color:#0284C7'>").append(soldCount.get() + transferredCount.get()).append("</div><div class='lbl'>").append(ar("مغادر مبرر", "Justified Out")).append("</div></div>")
                .append("<div class='card'><div class='val' style='color:#DC2626'>").append(trulyMissingCount.get()).append("</div><div class='lbl'>").append(ar("عجز حقيقي", "True Shortage")).append("</div></div>")
                .append("<div class='card'><div class='val' style='color:#F59E0B'>").append(unexpectedCount.get()).append("</div><div class='lbl'>").append(ar("زائد", "Extra")).append("</div></div>")
                .append("</div>");

        h.append("<table><tr><th>#</th><th>EPC</th><th>").append(ar("العنوان", "Title")).append("</th><th>").append(ar("العيار", "Karat")).append("</th><th>").append(ar("الوزن", "Weight")).append("</th><th>").append(ar("الحالة المخزنية", "Audit Status")).append("</th></tr>");

        int count = 1;
        if (onlyMissing) {
            for (ExpectedItem i : expectedItemsMap.values()) {
                if (!i.isFound && i.status == ItemStatus.TRULY_MISSING) {
                    h.append("<tr><td>").append(count++).append("</td><td>").append(i.epc)
                            .append("</td><td>").append(i.title).append("</td><td>").append(i.karat)
                            .append("</td><td>").append(String.format(Locale.US, "%.2fg", i.weight))
                            .append("</td><td style='color:#DC2626;font-weight:bold;'>").append(ar("عجز حقيقي", "TRUE SHORTAGE")).append("</td></tr>");
                }
            }
        } else {
            synchronized (recentTags) {
                for (ScannedTag t : recentTags) {
                    h.append("<tr><td>").append(count++).append("</td><td>").append(t.epc)
                            .append("</td><td>").append(t.title != null ? t.title : "—")
                            .append("</td><td>").append(t.karat != null ? t.karat : "—")
                            .append("</td><td>").append(t.weight != null ? t.weight : "—")
                            .append("</td><td style='color:").append(t.status.colorHex).append(";font-weight:bold;'>").append(t.status.label).append("</td></tr>");
                }
            }
        }

        h.append("</table>")
                .append("<div class='footer'>").append(orgName).append(" © 2026 | MIRA Audit & Reconciliation Platform</div>")
                .append("</body></html>");

        final WebView wv = new WebView(requireContext());
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                PrintManager pm = (PrintManager) requireContext().getSystemService(Context.PRINT_SERVICE);
                if (pm != null) {
                    pm.print(ar("تقرير_جرد_المجوهرات", "Jewelry_Audit_Report"), wv.createPrintDocumentAdapter("AuditReport"), new PrintAttributes.Builder().build());
                }
            }
        });
        wv.loadDataWithBaseURL(null, h.toString(), "text/html", "UTF-8", null);
    }

    private void takeScreenshot() {
        if (rootLayout == null || getContext() == null) return;
        try {
            Bitmap b = Bitmap.createBitmap(rootLayout.getWidth(), rootLayout.getHeight(), Bitmap.Config.ARGB_8888);
            rootLayout.draw(new Canvas(b));
            File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "MiraInventory");
            if (!d.exists()) d.mkdirs();
            File f = new File(d, "AUDIT_SS_" + System.currentTimeMillis() + ".png");
            FileOutputStream o = new FileOutputStream(f);
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
            o.flush();
            o.close();
            ToastUtil.showSuccess(ar("📸 تم حفظ لقطة الجرد", "📸 Saved Snapshot"));
        } catch (Exception e) {
            Log.e(TAG, "SS error: " + e.getMessage());
            ToastUtil.showError(ar("فشل حفظ اللقطة", "Save failed"));
        }
    }

    private void startTimer() {
        stopTimer();
        timerRunnable = () -> {
            if (currentState == InventoryState.PAUSED) return;
            long e = (System.currentTimeMillis() - startTime - totalPausedDuration) / 1000;
            if (tvElapsed != null) tvElapsed.setText(String.format(Locale.US, "%02d:%02d", e / 60, e % 60));
            int cur = totalScanned.get();
            double r = cur - lastSecondCount;
            lastSecondCount = cur;
            if (tvScanRate != null) tvScanRate.setText(String.format(Locale.US, "%.0f/s", Math.max(0, r)));
            timerHandler.postDelayed(timerRunnable, 1000);
        };
        timerHandler.post(timerRunnable);
    }

    private void stopTimer() { if (timerRunnable != null) timerHandler.removeCallbacks(timerRunnable); }

    private void updateUI() {
        if (tvTotalScanned != null) tvTotalScanned.setText(String.valueOf(totalScanned.get()));
        if (tvUniqueTags != null) tvUniqueTags.setText(String.valueOf(uniqueTags.get()));
    }

    private String ar(String a, String e) { return isArabic ? a : e; }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopTimer();
        stopSimulation();
        stopBatchScheduler();
        readerCheckHandler.removeCallbacks(readerCheckRunnable);
        simulationHandler.removeCallbacks(simulationRunnable);
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
    }

    class RecentTagsAdapter extends RecyclerView.Adapter<RecentTagsAdapter.VH> {
        final List<ScannedTag> list;
        RecentTagsAdapter(List<ScannedTag> l) { this.list = l; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_scanned_tag, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            ScannedTag t;
            synchronized (list) {
                if (pos >= list.size()) return;
                t = list.get(pos);
            }
            if (t != null) {
                if (h.tvEPC != null) h.tvEPC.setText(t.epc);
                if (h.tvTitle != null) h.tvTitle.setText(t.title);
                if (h.tvDetails != null) h.tvDetails.setText((t.karat != null ? t.karat : "") + " | " + (t.weight != null ? t.weight : ""));
                if (h.tvStatus != null && t.status != null) {
                    h.tvStatus.setText(t.status.icon + " " + t.status.label);
                    h.tvStatus.setTextColor(Color.parseColor(t.status.colorHex));
                }
                if (h.tvTime != null) h.tvTime.setText(t.timestamp);
            }
        }

        @Override
        public int getItemCount() {
            synchronized (list) { return list.size(); }
        }

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
