package com.example.uhf.fragment;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.room.Room;

import com.example.uhf.R;
import com.example.uhf.database.InventoryDatabase;
import com.example.uhf.database.InventoryTransactionDao;
import com.example.uhf.database.InventoryTransactionEntity;
import com.example.uhf.engine.MiraInventoryEngine;
import com.example.uhf.MiraRfid.MiraIRFIDReader;
import com.example.uhf.MiraRfid.RFIDManager;
import com.example.uhf.model.InventoryItem;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.PermissionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MiraInventoryProFragment extends KeyDwonFragment 
        implements MiraIRFIDReader.OnTagReadListener {

    private static final String TAG = "MiraInventoryPro";
    private ScannedItemsAdapter scannedItemsAdapter;
    private List<ScannedItemDisplay> scannedItemsDisplayList = new ArrayList<>();
    
    private static final long UI_UPDATE_THROTTLE_MS = 200;
    private static final long SIMULATION_INITIAL_DELAY_MS = 500;
    private static final long SIMULATION_MIN_DELAY_MS = 150;
    private static final long SIMULATION_MAX_ADDITIONAL_DELAY_MS = 350;
    private static final long BEEP_THROTTLE_MS = 60;
    private static final int EXECUTOR_SHUTDOWN_TIMEOUT_MS = 500;
    private static final int MAX_SOUND_STREAMS = 5;
    private static final float BEEP_VOLUME = 0.7f;
    private static final float BEEP_RATE = 1.0f;
    private static final int BEEP_PRIORITY = 0;
    private static final int BEEP_LOOP = 0;
    private static final long TAG_READ_DEBOUNCE_MS = 500;
    private static final int MAX_AUDIT_ENTRIES = 1000;
    private static final int EAN13_LENGTH = 13;
    private static final int UPC12_LENGTH = 12;
    
    private static final int KEYCODE_RFID_TRIGGER_1 = 139;
    private static final int KEYCODE_RFID_TRIGGER_2 = 280;
    private static final int KEYCODE_RFID_TRIGGER_3 = 293;
    
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
    private TextView tvTotalWeight;
    private TextView tvTotalValue;
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
    private final Map<String, Long> recentTagReads = new ConcurrentHashMap<>();
    private final Map<String, String> barcodeToEpcMap = new ConcurrentHashMap<>();
    private final List<AuditEntry> auditLog = new ArrayList<>();
    
    // Room Database
    private InventoryDatabase inventoryDatabase;
    private InventoryTransactionDao transactionDao;
    
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
    private PermissionManager permissionManager;
    private int expectedCount = 0;
    private List<InventoryItem> scannedItemsList = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_inventory_pro, container, false);
        
        initSoundPool();
        initViews(view);
        initDatabase();

        String sessionId = "SESS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String deviceId = android.provider.Settings.Secure.getString(
                requireContext().getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID
        );

        inventoryEngine = new MiraInventoryEngine(requireContext(), sessionId, deviceId);
        apiClient = MiraApiClient.getInstance(requireContext());
        branchesManager = MiraBranchesManager.getInstance(requireContext());
        permissionManager = PermissionManager.getInstance(requireContext());
        
        rfidManager = RFIDManager.getInstance(requireContext());
        rfidManager.setListener(this);
        
        applyPermissions();
        
        return view;
    }

    private void initDatabase() {
        inventoryDatabase = Room.databaseBuilder(
            requireContext(),
            InventoryDatabase.class,
            "inventory_database"
        ).build();
        
        transactionDao = inventoryDatabase.inventoryTransactionDao();
    }

    private void applyPermissions() {
        boolean canView = permissionManager.canView("inventory") || permissionManager.isManager();
        boolean canEdit = permissionManager.canEdit("inventory") || permissionManager.isManager();
        
        if (btnStartInventory != null) btnStartInventory.setEnabled(canEdit);
        if (btnBlindInventory != null) btnBlindInventory.setEnabled(canEdit);
        if (btnGenerateReport != null) btnGenerateReport.setEnabled(canEdit);
        if (btnArchiveReports != null) btnArchiveReports.setEnabled(canView);
        if (btnViewSnapshots != null) btnViewSnapshots.setEnabled(canView);
        
        if (!canView && !canEdit) {
            if (tvInventoryStatus != null) {
                tvInventoryStatus.setText("⛔ غير مصرح لك بالوصول");
            }
        }
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

    private enum ScanFeedbackType {
        KNOWN_ITEM,
        UNKNOWN_ITEM,
        DUPLICATE_ITEM,
        MISSING_ITEM
    }

    private void playScanBeep(ScanFeedbackType feedbackType) {
        long now = System.currentTimeMillis();
        if (!soundLoaded || (now - lastBeepTime <= BEEP_THROTTLE_MS)) {
            return;
        }
        
        float rate = BEEP_RATE;
        float volume = BEEP_VOLUME;
        
        switch (feedbackType) {
            case KNOWN_ITEM:
                rate = 1.0f;
                volume = 0.7f;
                break;
            case UNKNOWN_ITEM:
                rate = 0.8f;
                volume = 0.5f;
                break;
            case DUPLICATE_ITEM:
                rate = 1.5f;
                volume = 0.4f;
                break;
            case MISSING_ITEM:
                rate = 0.6f;
                volume = 0.9f;
                break;
        }
        
        try {
            soundPool.play(beepSoundId, volume, volume, BEEP_PRIORITY, BEEP_LOOP, rate);
            lastBeepTime = now;
        } catch (Exception e) {
            Log.e(TAG, "Error playing beep sound", e);
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
        
        tvTotalWeight = view.findViewById(R.id.tvTotalWeight);
        tvTotalValue = view.findViewById(R.id.tvTotalValue);
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

        if (btnStartInventory != null) {
            btnStartInventory.setOnClickListener(v -> {
                if (permissionManager.canEdit("inventory") || permissionManager.isManager()) {
                    startScanning();
                } else {
                    Toast.makeText(requireContext(), "⛔ غير مصرح", Toast.LENGTH_SHORT).show();
                }
            });
        }
        if (btnBlindInventory != null) {
            btnBlindInventory.setOnClickListener(v -> {
                if (permissionManager.canEdit("inventory") || permissionManager.isManager()) {
                    startBlindInventory();
                } else {
                    Toast.makeText(requireContext(), "⛔ غير مصرح", Toast.LENGTH_SHORT).show();
                }
            });
        }
        if (btnStopInventory != null) {
            btnStopInventory.setOnClickListener(v -> stopScanning());
            btnStopInventory.setEnabled(false);
        }
        if (btnGenerateReport != null) {
            btnGenerateReport.setOnClickListener(v -> showCommitConfirmationDialog());
            btnGenerateReport.setEnabled(false);
        }
        if (btnArchiveReports != null) {
            btnArchiveReports.setOnClickListener(v -> showArchiveReports());
        }
        if (btnViewSnapshots != null) {
            btnViewSnapshots.setOnClickListener(v -> showSnapshotsDialog());
        }
    }

    public boolean myOnKeyDwon(int keyCode, KeyEvent event) {
        if (keyCode == KEYCODE_RFID_TRIGGER_1 || keyCode == KEYCODE_RFID_TRIGGER_2 || keyCode == KEYCODE_RFID_TRIGGER_3) {
            if (event.getRepeatCount() == 0) {
                if (!(permissionManager.canEdit("inventory") || permissionManager.isManager())) {
                    Toast.makeText(requireContext(), "⛔ غير مصرح", Toast.LENGTH_SHORT).show();
                    return true;
                }
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

        final String normalizedEpc = normalizeCodeToEpc(epc);
        final int finalRssi = rssi;
        
        // De-duplication check
        long currentTime = System.currentTimeMillis();
        Long lastReadTime = recentTagReads.get(normalizedEpc);
        
        if (lastReadTime != null && (currentTime - lastReadTime) < TAG_READ_DEBOUNCE_MS) {
            return;
        }
        
        recentTagReads.put(normalizedEpc, currentTime);
        
        // تنظيف الخريطة دورياً
        if (recentTagReads.size() > 1000) {
            cleanOldTagReads(currentTime);
        }
        
        backgroundExecutor.execute(() -> {
            try {
                inventoryEngine.onTagScanned(normalizedEpc, finalRssi);
                saveTransactionLocally(normalizedEpc, finalRssi);
                
                mainThreadHandler.post(() -> {
                    updateLastScan(normalizedEpc, finalRssi);
                    addScannedItemToDisplay(normalizedEpc, finalRssi, null);
                    scheduleUiUpdate();
                });
            } catch (Exception e) {
                Log.e(TAG, "Error processing tag: " + normalizedEpc, e);
            }
        });
    }

    private void cleanOldTagReads(long currentTime) {
        Iterator<Map.Entry<String, Long>> iterator = recentTagReads.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (currentTime - entry.getValue() > TAG_READ_DEBOUNCE_MS * 2) {
                iterator.remove();
            }
        }
    }

    private String normalizeCodeToEpc(String code) {
        if (code == null || code.isEmpty()) return "";
        
        String normalized = code.trim().toUpperCase();
        
        if (normalized.startsWith("EPC") || normalized.startsWith("E280")) {
            return normalized;
        }
        
        if (normalized.length() == EAN13_LENGTH && normalized.matches("\\d{13}")) {
            return convertEan13ToSgtin96(normalized);
        }
        
        if (normalized.length() == UPC12_LENGTH && normalized.matches("\\d{12}")) {
            String ean13 = "0" + normalized;
            return convertEan13ToSgtin96(ean13);
        }
        
        String mappedEpc = barcodeToEpcMap.get(normalized);
        if (mappedEpc != null) {
            return mappedEpc;
        }
        
        return normalized;
    }

    private String convertEan13ToSgtin96(String ean13) {
        String companyPrefix = ean13.substring(0, 6);
        String itemReference = ean13.substring(6, 12);
        String checkDigit = ean13.substring(12);
        
        return String.format("E280%s%s%s", companyPrefix, itemReference, checkDigit);
    }

    private void saveTransactionLocally(String epc, int rssi) {
        try {
            InventoryTransactionEntity transaction = new InventoryTransactionEntity();
            transaction.sessionId = inventoryEngine.getClientSessionId();
            transaction.epcCode = epc;
            transaction.rssi = rssi;
            transaction.timestamp = System.currentTimeMillis();
            transaction.deviceId = android.provider.Settings.Secure.getString(
                requireContext().getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID
            );
            transaction.userId = getCurrentUserId();
            transaction.synced = false;
            transaction.branchId = branchesManager.getActiveBranchId();
            
            backgroundExecutor.execute(() -> {
                try {
                    transactionDao.insertTransaction(transaction);
                } catch (Exception e) {
                    Log.e(TAG, "Error saving transaction locally", e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error creating transaction entity", e);
        }
    }

    private void syncOfflineTransactions() {
        backgroundExecutor.execute(() -> {
            List<InventoryTransactionEntity> unsynced = transactionDao.getUnsyncedTransactions();
            
            if (unsynced.isEmpty()) return;
            
            JSONArray transactionsArray = new JSONArray();
            List<Integer> successfulIds = new ArrayList<>();
            
            for (InventoryTransactionEntity transaction : unsynced) {
                try {
                    JSONObject transactionJson = new JSONObject();
                    transactionJson.put("session_id", transaction.sessionId);
                    transactionJson.put("epc", transaction.epcCode);
                    transactionJson.put("rssi", transaction.rssi);
                    transactionJson.put("timestamp", transaction.timestamp);
                    transactionJson.put("device_id", transaction.deviceId);
                    transactionJson.put("user_id", transaction.userId);
                    transactionJson.put("branch_id", transaction.branchId);
                    
                    transactionsArray.put(transactionJson);
                    successfulIds.add(transaction.id);
                } catch (Exception e) {
                    Log.e(TAG, "Error preparing transaction for sync", e);
                }
            }
            
            if (transactionsArray.length() > 0) {
                MiraApiClient.ApiResponse response = apiClient.syncOfflineTransactions(transactionsArray);
                
                if (response != null && response.isSuccess) {
                    transactionDao.markAsSynced(successfulIds);
                }
            }
        });
    }

    private static class AuditEntry {
        String timestamp;
        String action;
        String details;
        String userId;
        String deviceId;
        
        AuditEntry(String action, String details, String userId, String deviceId) {
            this.timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                .format(new Date());
            this.action = action;
            this.details = details;
            this.userId = userId;
            this.deviceId = deviceId;
        }
    }

    private void logAudit(String action, String details) {
        String userId = getCurrentUserId();
        String deviceId = android.provider.Settings.Secure.getString(
            requireContext().getContentResolver(),
            android.provider.Settings.Secure.ANDROID_ID
        );
        
        AuditEntry entry = new AuditEntry(action, details, userId, deviceId);
        auditLog.add(entry);
        
        if (auditLog.size() > MAX_AUDIT_ENTRIES) {
            auditLog.remove(0);
        }
        
        Log.d(TAG, String.format("AUDIT: [%s] %s - %s (User: %s, Device: %s)", 
            entry.timestamp, entry.action, entry.details, entry.userId, entry.deviceId));
    }

    private String getCurrentUserId() {
        return "USER-" + android.provider.Settings.Secure.getString(
            requireContext().getContentResolver(),
            android.provider.Settings.Secure.ANDROID_ID
        ).substring(0, 8);
    }

    private void addScannedItemToDisplay(String epc, int rssi, InventoryItem item) {
        if (scannedItemsAdapter == null) return;
        
        boolean exists = false;
        int existingIndex = -1;

        for (int i = 0; i < scannedItemsDisplayList.size(); i++) {
            ScannedItemDisplay display = scannedItemsDisplayList.get(i);
            if (display.epc.equals(epc)) {
                display.count++;
                display.rssi = rssi;
                display.time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                exists = true;
                existingIndex = i;
                break;
            }
        }
        
        ScanFeedbackType feedbackType;
        boolean isKnown = catalogEpcs.contains(epc);
        
        if (!exists) {
            feedbackType = isKnown ? ScanFeedbackType.KNOWN_ITEM : ScanFeedbackType.UNKNOWN_ITEM;
            
            String title = "قطعة غير معروفة";
            String karat = "—";
            
            if (item != null) {
                title = item.getTitle() != null ? item.getTitle() : "قطعة ذهبية";
                karat = item.getKarat() != null ? item.getKarat() : "—";
            } else if (catalogEpcs.contains(epc)) {
                title = "مادة مسجلة بالكتالوج";
            }
            
            String currentTime = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            
            ScannedItemDisplay display = new ScannedItemDisplay(
                epc,
                title,
                karat,
                1,
                rssi,
                currentTime
            );
            scannedItemsDisplayList.add(0, display);
            scannedItemsAdapter.notifyItemInserted(0);
            if (rvInventoryItems != null) {
                rvInventoryItems.scrollToPosition(0);
            }
        } else {
            feedbackType = ScanFeedbackType.DUPLICATE_ITEM;
            scannedItemsAdapter.notifyItemChanged(existingIndex);
        }
        
        playScanBeep(feedbackType);
        
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
        String time;
        
        ScannedItemDisplay(String epc, String title, String karat, int count, int rssi, String time) {
            this.epc = epc;
            this.title = title;
            this.karat = karat;
            this.count = count;
            this.rssi = rssi;
            this.time = time;
        }
    }

    private class ScannedItemsAdapter extends RecyclerView.Adapter<ScannedItemsAdapter.ViewHolder> {
        
        private List<ScannedItemDisplay> items;
        
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

            if (!"—".equals(item.karat)) {
                holder.tvTagTitle.setText(item.title + " (" + item.karat + ")");
            } else {
                holder.tvTagTitle.setText(item.title);
            }

            holder.tvTagEpc.setText(item.epc);
            holder.tvTagDetails.setText("التكرار: " + item.count + " مرة");
            holder.tvTagRssi.setText(item.rssi + " dBm");
            holder.tvTagTime.setText(item.time != null ? item.time : "--:--:--");

            if (expectedCount > 0 && !catalogEpcs.isEmpty()) {
                if (catalogEpcs.contains(item.epc)) {
                    holder.tvTagStatus.setText("مطابق");
                    holder.tvTagStatus.setTextColor(0xFF2E7D32);
                } else {
                    holder.tvTagStatus.setText("غير متوقع");
                    holder.tvTagStatus.setTextColor(0xFFC62828);
                }
            } else {
                holder.tvTagStatus.setText("ممسوح");
                holder.tvTagStatus.setTextColor(0xFF1565C0);
            }
        }
        
        @Override
        public int getItemCount() {
            return items.size();
        }
        
        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvTagTitle;
            TextView tvTagEpc;
            TextView tvTagDetails;
            TextView tvTagStatus;
            TextView tvTagTime;
            TextView tvTagRssi;
            MaterialButton btnQuickAction;
            
            ViewHolder(View itemView) {
                super(itemView);
                tvTagTitle = itemView.findViewById(R.id.tvTagTitle);
                tvTagEpc = itemView.findViewById(R.id.tvTagEpc);
                tvTagDetails = itemView.findViewById(R.id.tvTagDetails);
                tvTagStatus = itemView.findViewById(R.id.tvTagStatus);
                tvTagTime = itemView.findViewById(R.id.tvTagTime);
                tvTagRssi = itemView.findViewById(R.id.tvTagRssi);
                btnQuickAction = itemView.findViewById(R.id.btnQuickAction);
                
                if (btnQuickAction != null) {
                    btnQuickAction.setOnClickListener(v -> {
                        int position = getAdapterPosition();
                        if (position != RecyclerView.NO_POSITION) {
                            ScannedItemDisplay item = items.get(position);
                            showQuickActionsDialog(item);
                        }
                    });
                }
            }
            
            private void showQuickActionsDialog(ScannedItemDisplay item) {
                String[] actions = {
                    "✏️ تعديل الحالة",
                    "🔧 تسجيل تلف",
                    "📍 تحديث الموقع",
                    "📝 ملاحظات",
                    "❌ إلغاء"
                };
                
                new MaterialAlertDialogBuilder(itemView.getContext())
                    .setTitle("إجراءات سريعة - " + item.epc)
                    .setItems(actions, (dialog, which) -> {
                        switch (which) {
                            case 0:
                                showEditStatusDialog(item);
                                break;
                            case 1:
                                showDamageDialog(item);
                                break;
                            case 2:
                                showLocationDialog(item);
                                break;
                            case 3:
                                showNotesDialog(item);
                                break;
                        }
                    })
                    .show();
            }
            
            private void showEditStatusDialog(ScannedItemDisplay item) {
                String[] statuses = {"متوفر", "محجوز", "قيد الصيانة", "غير متوفر"};
                
                new MaterialAlertDialogBuilder(itemView.getContext())
                    .setTitle("تعديل حالة - " + item.epc)
                    .setItems(statuses, (dialog, which) -> {
                        String newStatus = statuses[which];
                        updateItemStatus(item.epc, newStatus);
                        Toast.makeText(itemView.getContext(), 
                            "تم تحديث الحالة إلى: " + newStatus, 
                            Toast.LENGTH_SHORT).show();
                    })
                    .show();
            }
            
            private void showDamageDialog(ScannedItemDisplay item) {
                String[] damageTypes = {"خدش بسيط", "كسر", "فقدان جزء", "تلف كامل"};
                
                new MaterialAlertDialogBuilder(itemView.getContext())
                    .setTitle("تسجيل تلف - " + item.epc)
                    .setItems(damageTypes, (dialog, which) -> {
                        String damageType = damageTypes[which];
                        registerItemDamage(item.epc, damageType);
                        Toast.makeText(itemView.getContext(), 
                            "تم تسجيل التلف: " + damageType, 
                            Toast.LENGTH_SHORT).show();
                    })
                    .show();
            }
            
            private void showLocationDialog(ScannedItemDisplay item) {
                Toast.makeText(itemView.getContext(), 
                    "تحديث الموقع قيد التطوير", 
                    Toast.LENGTH_SHORT).show();
            }
            
            private void showNotesDialog(ScannedItemDisplay item) {
                Toast.makeText(itemView.getContext(), 
                    "إضافة ملاحظات قيد التطوير", 
                    Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void updateItemStatus(String epc, String status) {
        logAudit("UPDATE_STATUS", "تحديث حالة " + epc + " إلى " + status);
    }

    private void registerItemDamage(String epc, String damageType) {
        logAudit("REGISTER_DAMAGE", "تسجيل تلف " + epc + " - " + damageType);
    }

    private void updateLastScan(String epc, int rssi) {
        if (tvLastScan != null) {
            tvLastScan.setText("آخر قراءة: " + epc);
        }
        if (tvRssi != null) {
            tvRssi.setText("📶 " + rssi);
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
    public void onScanStarted() { 
        Log.d(TAG, "RFID Scan Started"); 
    }
    
    @Override
    public void onScanStopped() { 
        Log.d(TAG, "RFID Scan Stopped"); 
    }
    
    @Override
    public void onError(String errorMessage) { 
        Log.e(TAG, "RFID Error: " + errorMessage); 
        if (isAdded()) {
            mainThreadHandler.post(() -> {
                Toast.makeText(requireContext(), "خطأ في قارئ RFID: " + errorMessage, Toast.LENGTH_SHORT).show();
            });
        }
    }
    
    @Override
    public void onConnectionChanged(boolean isConnected) { 
        Log.i(TAG, "RFID Connection: " + isConnected); 
        if (isAdded() && tvInventoryStatus != null) {
            tvInventoryStatus.setText(isConnected ? "📡 متصل" : "📡 غير متصل");
        }
    }

    private void updateUI() {
        if (!isAdded() || inventoryEngine == null) return;
        
        int scanned = inventoryEngine.getUniqueCount();
        int totalReads = inventoryEngine.getTotalReads();
        int duplicates = inventoryEngine.getDuplicateCount();

        if (tvScannedCount != null) {
            tvScannedCount.setText(String.valueOf(scanned));
        }
        
        if (tvItemCount != null) {
            tvItemCount.setText(String.valueOf(scanned));
        }

        if (expectedCount > 0) {
            int progress = (int) (((float) scanned / expectedCount) * 100);
            progress = Math.min(progress, 100);
            
            if (progressScanned != null) {
                progressScanned.setProgress(progress);
            }
            
            if (tvProgressPercent != null) {
                tvProgressPercent.setText(progress + "%");
            }
            
            int variance = Math.max(0, expectedCount - scanned);
            if (tvVariance != null) {
                tvVariance.setText(String.valueOf(variance));
            }
            
            int accuracy = expectedCount > 0 ? (int)(((float)scanned / expectedCount) * 100) : 0;
            if (tvAccuracy != null) {
                tvAccuracy.setText(accuracy + "%");
            }

            if (scanned >= expectedCount && isScanning) {
                stopScanning();
                if (isAdded()) {
                    Toast.makeText(requireContext(), "🎉 اكتمل جرد جميع المواد المتوقعة!", Toast.LENGTH_LONG).show();
                }
            }
        }
        
        if (tvEmptyState != null) {
            tvEmptyState.setVisibility(scanned > 0 ? View.GONE : View.VISIBLE);
        }
        
        if (tvInventoryStatus != null && isScanning) {
            tvInventoryStatus.setText("📡 جاري الجرد... (الممسوح: " + scanned + ")");
        }
    }
    
    private void updateElapsedTime() {
        if (tvElapsedTime != null && scanStartTime > 0) {
            long elapsedMillis = SystemClock.elapsedRealtime() - scanStartTime;
            long seconds = elapsedMillis / 1000;
            long hours = seconds / 3600;
            long minutes = (seconds % 3600) / 60;
            long secs = seconds % 60;
            tvElapsedTime.setText(String.format("%02d:%02d:%02d", hours, minutes, secs));
        }
    }

    private void startScanning() {
        if (!(permissionManager.canEdit("inventory") || permissionManager.isManager())) {
            Toast.makeText(requireContext(), "⛔ غير مصرح", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isScanning) return;
        isScanning = true;
        scanStartTime = SystemClock.elapsedRealtime();
        mainThreadHandler.post(timerRunnable);
        
        logAudit("START_SCAN", "بدء جرد جديد");
        
        loadCatalogAndStart();
    }
    
    private void startBlindInventory() {
        if (!(permissionManager.canEdit("inventory") || permissionManager.isManager())) {
            Toast.makeText(requireContext(), "⛔ غير مصرح", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isScanning) return;
        isScanning = true;
        scanStartTime = SystemClock.elapsedRealtime();
        mainThreadHandler.post(timerRunnable);
        
        logAudit("START_BLIND_SCAN", "بدء جرد أعمى");
        
        expectedCount = 0;
        catalogEpcs.clear();
        
        if (tvExpectedCount != null) {
            tvExpectedCount.setText("—");
        }
        
        if (tvInventoryStatus != null) {
            tvInventoryStatus.setText("⚡ جرد أعمى - بدون توقعات");
        }
        
        inventoryEngine.startSession(new ConcurrentHashMap<>());
        startReadingSource();
    }
    
    private void loadCatalogAndStart() {
        if (!isAdded()) return;
        
        Toast.makeText(requireContext(), "🔄 جاري تحميل البيانات...", Toast.LENGTH_SHORT).show();
        
        backgroundExecutor.execute(() -> {
            Map<String, InventoryItem> catalog = fetchCatalogFromServer();
            
            mainThreadHandler.post(() -> {
                if (!isAdded()) return;
                
                inventoryEngine.startSession(catalog);
                
                catalogEpcs.clear();
                if (catalog != null) {
                    catalogEpcs.addAll(catalog.keySet());
                }
                
                expectedCount = catalog != null ? catalog.size() : 0;
                if (tvExpectedCount != null) {
                    tvExpectedCount.setText(String.valueOf(expectedCount));
                }
                
                if (progressScanned != null) {
                    progressScanned.setMax(100);
                    progressScanned.setProgress(0);
                }
                
                if (tvProgressPercent != null) {
                    tvProgressPercent.setText("0%");
                }

                startReadingSource();
            });
        });
    }
    
    private Map<String, InventoryItem> fetchCatalogFromServer() {
        Map<String, InventoryItem> catalog = new ConcurrentHashMap<>();
        
        try {
            if (apiClient == null || branchesManager == null) {
                Log.w(TAG, "API client or branches manager is null");
                return catalog;
            }
            
            int branchId = branchesManager.getActiveBranchId();
            MiraApiClient.ApiResponse response = apiClient.getBranchItemsList(branchId);
            
            if (response != null && response.isSuccess && response.data != null) {
                JSONArray items = response.data.optJSONArray("items");
                
                if (items == null) {
                    JSONObject dataObj = response.data.optJSONObject("data");
                    if (dataObj != null) {
                        items = dataObj.optJSONArray("items");
                    }
                }
                
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject obj = items.getJSONObject(i);
                        String epc = obj.optString("gtin13", 
                            obj.optString("serial_number", 
                            obj.optString("epc", ""))).trim();
                        
                        if (epc.isEmpty()) continue;
                        
                        String title = obj.optString("title", "Gold Item");
                        String karat = obj.optString("karat", "21K");
                        
                        String normalizedEpc = normalizeCodeToEpc(epc);
                        
                        InventoryItem item = new InventoryItem(
                            normalizedEpc,
                            title,
                            karat,
                            "✓"
                        );
                        catalog.put(normalizedEpc, item);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching catalog from server", e);
        }
        
        Log.d(TAG, "Catalog loaded: " + catalog.size() + " items");
        return catalog;
    }
    
    private void startReadingSource() {
        boolean hardwareStarted = false;
        
        if (rfidManager != null) {
            try {
                if (rfidManager.getActiveDevice() != null) {
                    MiraIRFIDReader.ReaderType deviceType = rfidManager.getActiveDevice().getType();
                    
                    if (deviceType != MiraIRFIDReader.ReaderType.SIMULATOR) {
                        hardwareStarted = rfidManager.startScan();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error starting hardware scanner", e);
            }
        }
        
        if (!hardwareStarted) {
            startSimulationLoop();
            if (isAdded()) {
                Toast.makeText(requireContext(), "🎯 بدء وضع المحاكاة", Toast.LENGTH_SHORT).show();
            }
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
        
        logAudit("STOP_SCAN", "إيقاف الجرد - الممسوح: " + inventoryEngine.getUniqueCount());
        
        if (rfidManager != null) {
            try {
                rfidManager.stopScan();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping RFID scanner", e);
            }
        }
        
        backgroundExecutor.execute(() -> {
            try {
                inventoryEngine.processSessionClose();
                
                mainThreadHandler.post(() -> {
                    if (!isAdded()) return;
                    
                    if (tvInventoryStatus != null) {
                        tvInventoryStatus.setText("📡 تم إيقاف الجرد");
                    }
                    
                    Toast.makeText(requireContext(), "🛑 تم إيقاف المسح", Toast.LENGTH_SHORT).show();
                    
                    if (btnStartInventory != null) btnStartInventory.setEnabled(true);
                    if (btnBlindInventory != null) btnBlindInventory.setEnabled(true);
                    if (btnStopInventory != null) btnStopInventory.setEnabled(false);
                    if (btnGenerateReport != null) btnGenerateReport.setEnabled(true);
                    if (btnArchiveReports != null) btnArchiveReports.setEnabled(true);
                    if (btnViewSnapshots != null) btnViewSnapshots.setEnabled(true);
                    
                    updateUI();
                    
                    // مزامنة البيانات المحلية
                    syncOfflineTransactions();
                });
            } catch (Exception e) {
                Log.e(TAG, "Error processing session close", e);
            }
        });
    }

    private void showCommitConfirmationDialog() {
        if (!isAdded() || inventoryEngine == null) return;
        if (!(permissionManager.canEdit("inventory") || permissionManager.isManager())) {
            Toast.makeText(requireContext(), "⛔ غير مصرح", Toast.LENGTH_SHORT).show();
            return;
        }
        
        int scanned = inventoryEngine.getUniqueCount();
        int missing = Math.max(0, expectedCount - scanned);

        String message = String.format("📊 ملخص عمليات الجرد:\n\n• المتوقع: %d\n• الممسوح: %d\n• المفقود: %d\n\nهل تريد اعتماد العملية وإرسالها لقاعدة البيانات؟",
                expectedCount, scanned, missing);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("حفظ واكتمال الجرد")
                .setMessage(message)
                .setPositiveButton("إرسال واعتماد", (dialog, which) -> commitInventoryToServer())
                .setNeutralButton("🖨️ طباعة التقرير الحالي", (dialog, which) -> {
                    InventoryReport currentReport = new InventoryReport(
                        0,
                        inventoryEngine.getClientSessionId(),
                        new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date()),
                        expectedCount,
                        scanned,
                        missing,
                        "جارية"
                    );
                    printReportWithQRCode(currentReport);
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void commitInventoryToServer() {
        if (!isAdded()) return;
        
        Toast.makeText(requireContext(), "⏳ جاري إرسال البيانات...", Toast.LENGTH_SHORT).show();
        
        logAudit("COMMIT_INVENTORY", "اعتماد الجرد - الجلسة: " + inventoryEngine.getClientSessionId());
        
        backgroundExecutor.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                
                String sessionId = inventoryEngine.getClientSessionId();
                payload.put("session_id", sessionId);
                payload.put("client_session_id", sessionId);
                
                int branchId = branchesManager != null ? branchesManager.getActiveBranchId() : 0;
                payload.put("branch_id", branchId);
                payload.put("location", "Main Showroom");
                
                int scanned = inventoryEngine.getUniqueCount();
                int totalReads = inventoryEngine.getTotalReads();
                int duplicates = inventoryEngine.getDuplicateCount();
                
                payload.put("expected_count", expectedCount);
                payload.put("total_scanned", totalReads);
                payload.put("unique_count", scanned);
                payload.put("found_count", scanned);
                payload.put("missing_count", Math.max(0, expectedCount - scanned));
                payload.put("unexpected_count", 0);
                
                JSONArray itemsArray = new JSONArray();
                JSONArray epcsArray = new JSONArray();
                
                for (ScannedItemDisplay item : scannedItemsDisplayList) {
                    JSONObject itemObj = new JSONObject();
                    itemObj.put("code", item.epc);
                    itemObj.put("serial", item.epc);
                    itemObj.put("rssi", String.valueOf(item.rssi));
                    itemsArray.put(itemObj);
                    
                    epcsArray.put(item.epc);
                }
                
                payload.put("items", itemsArray);
                payload.put("scanned_epcs", epcsArray);
                
                MiraApiClient.ApiResponse response = apiClient.closeInventorySession(payload);
                
                mainThreadHandler.post(() -> {
                    if (!isAdded()) return;
                    
                    if (response != null && response.isSuccess) {
                        Toast.makeText(requireContext(), "✅ تم اعتماد الجرد وحفظ التقرير بنجاح", Toast.LENGTH_LONG).show();
                        if (btnGenerateReport != null) btnGenerateReport.setEnabled(false);
                        showCommitSuccessDialog(response);
                    } else {
                        String errorMsg = response != null ? response.error : "Unknown error";
                        Log.e(TAG, "Commit failed: " + errorMsg);
                        Toast.makeText(requireContext(), "❌ خطأ: " + errorMsg, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error committing inventory", e);
                mainThreadHandler.post(() -> {
                    if (isAdded()) {
                        Toast.makeText(requireContext(), "❌ خطأ في إرسال البيانات: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void showCommitSuccessDialog(MiraApiClient.ApiResponse response) {
        if (!isAdded() || response.data == null) return;
        
        try {
            JSONObject report = response.data.optJSONObject("report");
            if (report == null) report = response.data;
            
            String accuracy = report.optString("accuracy", "0");
            String riskLevel = report.optString("risk_level", "unknown");
            String recommendation = report.optString("recommendation", "");
            
            String message = String.format(
                "✅ تم اعتماد الجرد بنجاح:\n\n" +
                "• الدقة: %s%%\n" +
                "• مستوى الخطورة: %s\n" +
                "• التوصية: %s",
                accuracy, riskLevel, recommendation
            );
            
            new MaterialAlertDialogBuilder(requireContext())
                .setTitle("🎉 نجاح العملية")
                .setMessage(message)
                .setPositiveButton("🖨️ طباعة التقرير", (dialog, which) -> {
                    int scanned = inventoryEngine != null ? inventoryEngine.getUniqueCount() : 0;
                    InventoryReport committedReport = new InventoryReport(
                        0,
                        inventoryEngine != null ? inventoryEngine.getClientSessionId() : "SESS-COMMITTED",
                        new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date()),
                        expectedCount,
                        scanned,
                        Math.max(0, expectedCount - scanned),
                        "معتمد"
                    );
                    printReportWithQRCode(committedReport);
                })
                .setNegativeButton("إغلاق", null)
                .show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing success dialog", e);
        }
    }

    private void showArchiveReports() {
        if (!isAdded()) return;
        
        MaterialAlertDialogBuilder loadingDialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle("📂 جاري تحميل الأرشيف...")
                .setMessage("يرجى الانتظار")
                .setCancelable(false);
        AlertDialog loading = loadingDialog.show();
        
        backgroundExecutor.execute(() -> {
            try {
                List<InventoryReport> reports = fetchArchiveReports();
                
                mainThreadHandler.post(() -> {
                    loading.dismiss();
                    if (isAdded()) {
                        displayArchiveDialog(reports);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error fetching archive", e);
                mainThreadHandler.post(() -> {
                    loading.dismiss();
                    if (isAdded()) {
                        Toast.makeText(requireContext(), "❌ فشل تحميل الأرشيف", Toast.LENGTH_SHORT).show();
                    }
                });
            }
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
                    JSONArray sessions = null;
                    
                    if (dataObj != null) {
                        sessions = dataObj.optJSONArray("sessions");
                    } else {
                        sessions = response.data.optJSONArray("sessions");
                    }
                    
                    if (sessions != null) {
                        for (int i = 0; i < sessions.length(); i++) {
                            JSONObject obj = sessions.getJSONObject(i);
                            
                            InventoryReport report = new InventoryReport(
                                obj.optInt("id", i + 1),
                                obj.optString("client_session_id", obj.optString("session_id", "SESS-" + (i + 1))),
                                obj.optString("started_at", obj.optString("created_at", "")),
                                obj.optInt("expected_count", 0),
                                obj.optInt("unique_items_found", obj.optInt("scanned_count", 0)),
                                obj.optInt("missing_count", 0),
                                obj.optString("status", "completed")
                            );
                            reports.add(report);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching archive reports", e);
        }
        
        return reports;
    }
    
    private void displayArchiveDialog(List<InventoryReport> reports) {
        if (!isAdded() || reports.isEmpty()) {
            Toast.makeText(requireContext(), "لا توجد تقارير سابقة", Toast.LENGTH_SHORT).show();
            return;
        }
        
        String[] reportTitles = new String[reports.size()];
        for (int i = 0; i < reports.size(); i++) {
            InventoryReport r = reports.get(i);
            reportTitles[i] = String.format("📋 %s - %s (%d/%d)", 
                r.sessionId, r.date, r.scannedCount, r.expectedCount);
        }
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("📂 أرشيف تقارير الجرد")
                .setItems(reportTitles, (dialog, which) -> {
                    showReportDetails(reports.get(which));
                })
                .setPositiveButton("طباعة الكل", (dialog, which) -> {
                    printAllReports(reports);
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }
    
    private void showReportDetails(InventoryReport report) {
        if (!isAdded()) return;
        
        String message = String.format(
            "📋 تفاصيل التقرير:\n\n" +
            "• رقم الجلسة: %s\n" +
            "• التاريخ: %s\n" +
            "• المتوقع: %d\n" +
            "• الممسوح: %d\n" +
            "• المفقود: %d\n" +
            "• الحالة: %s",
            report.sessionId, report.date, report.expectedCount,
            report.scannedCount, report.missingCount, report.status
        );
        
        String[] options = {"🖨️ طباعة التقرير", "📊 مقارنة مع الحالي", "❌ إغلاق"};
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("تفاصيل التقرير")
                .setMessage(message)
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            printReportWithQRCode(report);
                            break;
                        case 1:
                            compareWithCurrent(report);
                            break;
                    }
                })
                .show();
    }
    
    private void printReport(InventoryReport report) {
        if (!isAdded()) return;
        Toast.makeText(requireContext(), "🖨️ تحضير المستند للطباعة...", Toast.LENGTH_SHORT).show();
        printReportDirectly(report, scannedItemsDisplayList);
    }

    private void printAllReports(List<InventoryReport> reports) {
        if (!isAdded() || reports.isEmpty()) return;
        for (InventoryReport r : reports) {
            printReportWithQRCode(r);
        }
    }

    private void printReportDirectly(InventoryReport report, List<ScannedItemDisplay> items) {
        // نفس الكود السابق للطباعة
        try {
            WebView webView = new WebView(requireContext());
            
            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html><html><head><meta charset='utf-8'>")
                .append("<style>")
                .append("body { font-family: sans-serif; direction: rtl; text-align: right; margin: 20px; }")
                .append(".header { text-align: center; border-bottom: 2px solid #333; padding-bottom: 10px; margin-bottom: 20px; }")
                .append(".header h1 { margin: 0; color: #1a237e; font-size: 22px; }")
                .append(".header p { margin: 5px 0 0 0; color: #666; font-size: 13px; }")
                .append(".summary-table { width: 100%; border-collapse: collapse; margin-bottom: 20px; }")
                .append(".summary-table td { padding: 8px; border: 1px solid #ddd; font-weight: bold; }")
                .append(".summary-table td.label { background-color: #f5f5f5; width: 25%; }")
                .append(".items-table { width: 100%; border-collapse: collapse; margin-top: 10px; }")
                .append(".items-table th { background-color: #1a237e; color: white; padding: 8px; border: 1px solid #1a237e; font-size: 12px; }")
                .append(".items-table td { padding: 6px; border: 1px solid #ddd; font-size: 11px; text-align: center; }")
                .append(".footer { margin-top: 30px; text-align: left; font-size: 10px; color: #888; border-top: 1px solid #ccc; padding-top: 5px; }")
                .append("</style></head><body>");

            html.append("<div class='header'>")
                .append("<h1>تقرير عمليات الجرد المتقدم - Mira RFID</h1>")
                .append("<p>رقم الجلسة: ").append(report.sessionId).append(" | التاريخ: ").append(report.date).append("</p>")
                .append("</div>");

            int accuracy = report.expectedCount > 0 ? (int)(((float)report.scannedCount / report.expectedCount) * 100) : 100;

            html.append("<table class='summary-table'>")
                .append("<tr><td class='label'>المواد المتوقعة:</td><td>").append(report.expectedCount).append("</td>")
                .append("<td class='label'>المواد الممسوحة:</td><td>").append(report.scannedCount).append("</td></tr>")
                .append("<tr><td class='label'>الفارق / المفقود:</td><td>").append(report.missingCount).append("</td>")
                .append("<td class='label'>نسبة الدقة:</td><td>").append(accuracy).append("%</td></tr>")
                .append("</table>");

            html.append("<h3>تفاصيل العناصر الممسوحة:</h3>");
            html.append("<table class='items-table'><thead><tr>")
                .append("<th>#</th><th>الرمز (EPC)</th><th>وصف القطعة</th><th>العيار</th><th>التكرار</th><th>الإشارة (dBm)</th>")
                .append("</tr></thead><tbody>");

            if (items != null && !items.isEmpty()) {
                for (int i = 0; i < items.size(); i++) {
                    ScannedItemDisplay item = items.get(i);
                    html.append("<tr>")
                        .append("<td>").append(i + 1).append("</td>")
                        .append("<td>").append(item.epc).append("</td>")
                        .append("<td>").append(item.title).append("</td>")
                        .append("<td>").append(item.karat).append("</td>")
                        .append("<td>").append(item.count).append("</td>")
                        .append("<td>").append(item.rssi).append("</td>")
                        .append("</tr>");
                }
            } else {
                html.append("<tr><td colspan='6'>لا توجد عناصر ممسوحة مسجلة</td></tr>");
            }

            html.append("</tbody></table>");
            html.append("<div class='footer'>تم إنشاء هذا التقرير تلقائياً بواسطة نظام Mira Pro RFID</div>");
            html.append("</body></html>");

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    PrintManager printManager = (PrintManager) requireContext().getSystemService(Context.PRINT_SERVICE);
                    if (printManager != null) {
                        String jobName = "Mira_Inventory_Report_" + report.sessionId;
                        PrintDocumentAdapter printAdapter = view.createPrintDocumentAdapter(jobName);
                        PrintAttributes attributes = new PrintAttributes.Builder()
                                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                                .build();
                        printManager.print(jobName, printAdapter, attributes);
                    }
                }
            });

            webView.loadDataWithBaseURL(null, html.toString(), "text/html", "UTF-8", null);
        } catch (Exception e) {
            Log.e(TAG, "Error executing print task", e);
            Toast.makeText(requireContext(), "❌ فشلت عملية الطباعة: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void printReportWithQRCode(InventoryReport report) {
        if (!isAdded()) return;
        
        try {
            String qrData = generateReportQRData(report);
            Bitmap qrBitmap = generateQRCode(qrData);
            String qrBase64 = convertBitmapToBase64(qrBitmap);
            
            WebView webView = new WebView(requireContext());
            
            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html><html><head><meta charset='utf-8'>")
                .append("<style>")
                .append("body { font-family: sans-serif; direction: rtl; text-align: right; margin: 20px; }")
                .append(".header { text-align: center; border-bottom: 2px solid #333; padding-bottom: 10px; margin-bottom: 20px; }")
                .append(".header h1 { margin: 0; color: #1a237e; font-size: 22px; }")
                .append(".header p { margin: 5px 0 0 0; color: #666; font-size: 13px; }")
                .append(".summary-table { width: 100%; border-collapse: collapse; margin-bottom: 20px; }")
                .append(".summary-table td { padding: 8px; border: 1px solid #ddd; font-weight: bold; }")
                .append(".summary-table td.label { background-color: #f5f5f5; width: 25%; }")
                .append(".items-table { width: 100%; border-collapse: collapse; margin-top: 10px; }")
                .append(".items-table th { background-color: #1a237e; color: white; padding: 8px; border: 1px solid #1a237e; font-size: 12px; }")
                .append(".items-table td { padding: 6px; border: 1px solid #ddd; font-size: 11px; text-align: center; }")
                .append(".footer { margin-top: 30px; text-align: left; font-size: 10px; color: #888; border-top: 1px solid #ccc; padding-top: 5px; }")
                .append(".qr-code { text-align: center; margin-top: 20px; }")
                .append(".qr-code img { width: 150px; height: 150px; }")
                .append("</style></head><body>");

            html.append("<div class='header'>")
                .append("<h1>تقرير عمليات الجرد المتقدم - Mira RFID</h1>")
                .append("<p>رقم الجلسة: ").append(report.sessionId).append(" | التاريخ: ").append(report.date).append("</p>")
                .append("</div>");

            int accuracy = report.expectedCount > 0 ? (int)(((float)report.scannedCount / report.expectedCount) * 100) : 100;

            html.append("<table class='summary-table'>")
                .append("<tr><td class='label'>المواد المتوقعة:</td><td>").append(report.expectedCount).append("</td>")
                .append("<td class='label'>المواد الممسوحة:</td><td>").append(report.scannedCount).append("</td></tr>")
                .append("<tr><td class='label'>الفارق / المفقود:</td><td>").append(report.missingCount).append("</td>")
                .append("<td class='label'>نسبة الدقة:</td><td>").append(accuracy).append("%</td></tr>")
                .append("</table>");

            html.append("<h3>تفاصيل العناصر الممسوحة:</h3>");
            html.append("<table class='items-table'><thead><tr>")
                .append("<th>#</th><th>الرمز (EPC)</th><th>وصف القطعة</th><th>العيار</th><th>التكرار</th><th>الإشارة (dBm)</th>")
                .append("</tr></thead><tbody>");

            if (scannedItemsDisplayList != null && !scannedItemsDisplayList.isEmpty()) {
                for (int i = 0; i < scannedItemsDisplayList.size(); i++) {
                    ScannedItemDisplay item = scannedItemsDisplayList.get(i);
                    html.append("<tr>")
                        .append("<td>").append(i + 1).append("</td>")
                        .append("<td>").append(item.epc).append("</td>")
                        .append("<td>").append(item.title).append("</td>")
                        .append("<td>").append(item.karat).append("</td>")
                        .append("<td>").append(item.count).append("</td>")
                        .append("<td>").append(item.rssi).append("</td>")
                        .append("</tr>");
                }
            } else {
                html.append("<tr><td colspan='6'>لا توجد عناصر ممسوحة مسجلة</td></tr>");
            }

            html.append("</tbody></table>");
            
            if (qrBase64 != null && !qrBase64.isEmpty()) {
                html.append("<div class='qr-code'>")
                    .append("<img src='data:image/png;base64,").append(qrBase64).append("'/>")
                    .append("<p>امسح للتحقق من التقرير</p>")
                    .append("</div>");
            }
            
            html.append("<div class='footer'>تم إنشاء هذا التقرير تلقائياً بواسطة نظام Mira Pro RFID</div>");
            html.append("</body></html>");

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    PrintManager printManager = (PrintManager) requireContext().getSystemService(Context.PRINT_SERVICE);
                    if (printManager != null) {
                        String jobName = "Mira_Inventory_Report_" + report.sessionId;
                        PrintDocumentAdapter printAdapter = view.createPrintDocumentAdapter(jobName);
                        PrintAttributes attributes = new PrintAttributes.Builder()
                                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                                .build();
                        printManager.print(jobName, printAdapter, attributes);
                    }
                }
            });

            webView.loadDataWithBaseURL(null, html.toString(), "text/html", "UTF-8", null);
            
        } catch (Exception e) {
            Log.e(TAG, "Error printing report with QR code", e);
            Toast.makeText(requireContext(), "❌ فشلت عملية الطباعة: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private Bitmap generateQRCode(String data) {
        try {
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            BitMatrix bitMatrix = qrCodeWriter.encode(data, BarcodeFormat.QR_CODE, 300, 300);
            
            int width = bitMatrix.getWidth();
            int height = bitMatrix.getHeight();
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    bitmap.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "Error generating QR code", e);
            return null;
        }
    }
    
    private String convertBitmapToBase64(Bitmap bitmap) {
        if (bitmap == null) return "";
        
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream);
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        
        return Base64.encodeToString(byteArray, Base64.DEFAULT);
    }
    
    private String generateReportQRData(InventoryReport report) {
        try {
            JSONObject qrData = new JSONObject();
            qrData.put("type", "inventory_report");
            qrData.put("session_id", report.sessionId);
            qrData.put("date", report.date);
            qrData.put("expected_count", report.expectedCount);
            qrData.put("scanned_count", report.scannedCount);
            qrData.put("missing_count", report.missingCount);
            qrData.put("status", report.status);
            
            return qrData.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
    
    private void compareWithCurrent(InventoryReport oldReport) {
        if (!isAdded() || inventoryEngine == null) return;
        
        int currentScanned = inventoryEngine.getUniqueCount();
        int currentExpected = expectedCount;
        
        String comparison = String.format(
            "📊 مقارنة الجرد:\n\n" +
            "التقرير السابق (%s):\n" +
            "• المتوقع: %d\n" +
            "• الممسوح: %d\n" +
            "• المفقود: %d\n\n" +
            "الجرد الحالي:\n" +
            "• المتوقع: %d\n" +
            "• الممسوح: %d\n" +
            "• المفقود: %d\n\n" +
            "الفرق:\n" +
            "• الممسوح: %+d\n" +
            "• المفقود: %+d",
            oldReport.date,
            oldReport.expectedCount, oldReport.scannedCount, oldReport.missingCount,
            currentExpected, currentScanned, Math.max(0, currentExpected - currentScanned),
            currentScanned - oldReport.scannedCount,
            Math.max(0, currentExpected - currentScanned) - oldReport.missingCount
        );
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("📊 مقارنة الجرد")
                .setMessage(comparison)
                .setPositiveButton("إغلاق", null)
                .show();
    }

    private void showSnapshotsDialog() {
        if (!isAdded()) return;
        
        String[] options = {
            "📸 عرض لقطات الجرد السابقة",
            "📊 مقارنة لقطتين",
            "❌ إغلاق"
        };
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("📸 لقطات الجرد")
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            showSnapshotsList();
                            break;
                        case 1:
                            showSnapshotComparison();
                            break;
                    }
                })
                .show();
    }
    
    private void showSnapshotsList() {
        if (!isAdded()) return;
        
        MaterialAlertDialogBuilder loadingDialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle("📸 جاري تحميل اللقطات...")
                .setMessage("يرجى الانتظار")
                .setCancelable(false);
        AlertDialog loading = loadingDialog.show();
        
        backgroundExecutor.execute(() -> {
            try {
                List<String> snapshots = fetchSnapshotsFromServer();
                
                mainThreadHandler.post(() -> {
                    loading.dismiss();
                    if (isAdded()) {
                        displaySnapshotsDialog(snapshots);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error fetching snapshots", e);
                mainThreadHandler.post(() -> {
                    loading.dismiss();
                    if (isAdded()) {
                        Toast.makeText(requireContext(), "❌ فشل تحميل اللقطات", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private List<String> fetchSnapshotsFromServer() {
        List<String> snapshots = new ArrayList<>();
        
        try {
            if (apiClient != null) {
                MiraApiClient.ApiResponse response = apiClient.getLastSnapshot("Main Showroom");
                
                if (response != null && response.isSuccess && response.data != null) {
                    JSONObject snapshot = response.data.optJSONObject("snapshot");
                    if (snapshot == null) snapshot = response.data;
                    
                    if (snapshot != null && snapshot.length() > 0) {
                        String date = snapshot.optString("snapshot_date", "غير معروف");
                        int items = snapshot.optInt("total_items", 0);
                        double weight = snapshot.optDouble("total_weight", 0);
                        double value = snapshot.optDouble("total_value", 0);
                        
                        snapshots.add(String.format("📸 %s - %d قطعة - %.2fg - $%.2f", 
                            date, items, weight, value));
                    }
                }
                
                MiraApiClient.ApiResponse historyResponse = apiClient.getInventoryHistory("Main Showroom", 10);
                if (historyResponse != null && historyResponse.isSuccess && historyResponse.data != null) {
                    JSONArray history = historyResponse.data.optJSONArray("history");
                    if (history != null) {
                        for (int i = 0; i < history.length(); i++) {
                            JSONObject obj = history.getJSONObject(i);
                            String date = obj.optString("snapshot_date", "");
                            int items = obj.optInt("total_items", 0);
                            double weight = obj.optDouble("total_weight", 0);
                            double value = obj.optDouble("total_value", 0);
                            
                            snapshots.add(String.format("📸 %s - %d قطعة - %.2fkg - $%.2f", 
                                date, items, weight / 1000, value));
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching snapshots", e);
        }
        
        return snapshots;
    }

    private void displaySnapshotsDialog(List<String> snapshots) {
        if (!isAdded()) return;
        
        if (snapshots.isEmpty()) {
            Toast.makeText(requireContext(), "لا توجد لقطات سابقة", Toast.LENGTH_SHORT).show();
            return;
        }
        
        String[] snapshotArray = snapshots.toArray(new String[0]);
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("📸 لقطات الجرد السابقة")
                .setItems(snapshotArray, (dialog, which) -> {
                    Toast.makeText(requireContext(), "تم اختيار: " + snapshotArray[which], Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton("إغلاق", null)
                .show();
    }
    
    private void showSnapshotComparison() {
        if (!isAdded()) return;
        
        MaterialAlertDialogBuilder loadingDialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle("📊 جاري تحميل الجلسات...")
                .setMessage("يرجى الانتظار")
                .setCancelable(false);
        AlertDialog loading = loadingDialog.show();
        
        backgroundExecutor.execute(() -> {
            try {
                List<InventoryReport> sessions = fetchArchiveReports();
                
                mainThreadHandler.post(() -> {
                    loading.dismiss();
                    if (!isAdded()) return;
                    
                    if (sessions.size() < 2) {
                        Toast.makeText(requireContext(), 
                            "⚠️ تحتاج إلى جلستين على الأقل للمقارنة", 
                            Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    showSessionSelectionDialog(sessions);
                });
            } catch (Exception e) {
                Log.e(TAG, "Error fetching sessions for comparison", e);
                mainThreadHandler.post(() -> {
                    loading.dismiss();
                    if (isAdded()) {
                        Toast.makeText(requireContext(), 
                            "❌ فشل تحميل الجلسات", 
                            Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void showSessionSelectionDialog(List<InventoryReport> sessions) {
        if (!isAdded()) return;
        
        String[] sessionTitles = new String[sessions.size()];
        for (int i = 0; i < sessions.size(); i++) {
            InventoryReport r = sessions.get(i);
            sessionTitles[i] = String.format("📋 %s - %s (%d/%d)", 
                r.sessionId, r.date, r.scannedCount, r.expectedCount);
        }
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("📊 اختر الجلسة الأولى")
                .setItems(sessionTitles, (dialog1, which1) -> {
                    InventoryReport firstSession = sessions.get(which1);
                    
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle("📊 اختر الجلسة الثانية")
                            .setItems(sessionTitles, (dialog2, which2) -> {
                                InventoryReport secondSession = sessions.get(which2);
                                
                                if (firstSession.id == secondSession.id) {
                                    Toast.makeText(requireContext(), 
                                        "⚠️ اختر جلستين مختلفتين", 
                                        Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                
                                fetchAndShowComparison(firstSession.id, secondSession.id);
                            })
                            .show();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void fetchAndShowComparison(int sessionId1, int sessionId2) {
        if (!isAdded()) return;
        
        MaterialAlertDialogBuilder loadingDialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle("📊 جاري المقارنة...")
                .setMessage("يرجى الانتظار")
                .setCancelable(false);
        AlertDialog loading = loadingDialog.show();
        
        backgroundExecutor.execute(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.compareInventorySessions(sessionId1, sessionId2);
                
                mainThreadHandler.post(() -> {
                    loading.dismiss();
                    if (!isAdded()) return;
                    
                    if (response != null && response.isSuccess && response.data != null) {
                        displayComparisonResult(response.data);
                    } else {
                        Toast.makeText(requireContext(), 
                            "❌ فشل المقارنة", 
                            Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error comparing sessions", e);
                mainThreadHandler.post(() -> {
                    loading.dismiss();
                    if (isAdded()) {
                        Toast.makeText(requireContext(), 
                            "❌ خطأ في المقارنة", 
                            Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void displayComparisonResult(JSONObject data) {
        if (!isAdded()) return;
        
        try {
            JSONObject comparison = data.optJSONObject("comparison");
            if (comparison == null) comparison = data;
            
            JSONObject session1 = comparison.optJSONObject("session_1");
            JSONObject session2 = comparison.optJSONObject("session_2");
            JSONObject difference = comparison.optJSONObject("difference");
            
            if (session1 != null && session2 != null) {
                int found1 = session1.optInt("found", session1.optInt("unique_items_found", 0));
                int expected1 = session1.optInt("expected", session1.optInt("expected_count", 0));
                int found2 = session2.optInt("found", session2.optInt("unique_items_found", 0));
                int expected2 = session2.optInt("expected", session2.optInt("expected_count", 0));
                
                int missing1 = Math.max(0, expected1 - found1);
                int missing2 = Math.max(0, expected2 - found2);
                
                int foundDiff = difference != null ? difference.optInt("found_diff", found2 - found1) : found2 - found1;
                int missingDiff = missing2 - missing1;
                
                String date1 = session1.optString("date", session1.optString("started_at", "غير معروف"));
                String date2 = session2.optString("date", session2.optString("started_at", "غير معروف"));
                
                String comparisonText = String.format(
                    "📊 مقارنة الجلسات:\n\n" +
                    "الجلسة الأولى (%s):\n" +
                    "• المتوقع: %d\n" +
                    "• الممسوح: %d\n" +
                    "• المفقود: %d\n\n" +
                    "الجلسة الثانية (%s):\n" +
                    "• المتوقع: %d\n" +
                    "• الممسوح: %d\n" +
                    "• المفقود: %d\n\n" +
                    "الفرق بين الجلستين:\n" +
                    "• تغير في الممسوح: %+d قطعة\n" +
                    "• تغير في المفقود: %+d قطعة",
                    date1, expected1, found1, missing1,
                    date2, expected2, found2, missing2,
                    foundDiff, missingDiff
                );
                
                String assessment = "";
                if (foundDiff > 0) {
                    assessment = "\n\n✅ تحسن: تم العثور على " + foundDiff + " قطعة إضافية";
                } else if (foundDiff < 0) {
                    assessment = "\n\n⚠️ تراجع: انخفض عدد القطع الممسوحة بـ " + Math.abs(foundDiff) + " قطعة";
                } else {
                    assessment = "\n\n➡️ لا تغيير في عدد القطع الممسوحة";
                }
                
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle("📊 نتيجة المقارنة")
                        .setMessage(comparisonText + assessment)
                        .setPositiveButton("🖨️ طباعة", (dialog, which) -> {
                            InventoryReport compReport = new InventoryReport(
                                0,
                                session2.optString("client_session_id", "COMP-RESULT"),
                                date2,
                                expected2,
                                found2,
                                missing2,
                                "مقارنة"
                            );
                            printReportWithQRCode(compReport);
                        })
                        .setNegativeButton("إغلاق", null)
                        .show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error displaying comparison", e);
            Toast.makeText(requireContext(), 
                "❌ خطأ في عرض المقارنة", 
                Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (isScanning) {
            showExitWarning();
        }
    }
    
    private void showExitWarning() {
        if (!isAdded()) return;
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("⚠️ تحذير مهم")
                .setMessage("الجرد ما زال قيد التشغيل!\n\n" +
                           "إذا قمت بتغيير الواجهة، سيتوقف الجرد وستفقد البيانات غير المحفوظة.\n\n" +
                           "هل تريد إيقاف الجرد والمتابعة؟")
                .setPositiveButton("إيقاف والمتابعة", (dialog, which) -> {
                    stopScanning();
                })
                .setNegativeButton("البقاء في الجرد", (dialog, which) -> {
                    dialog.dismiss();
                })
                .setCancelable(false)
                .show();
    }

    private void startSimulationLoop() {
        simulationIndex = 0;
        
        simulationRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isScanning) return;
                
                String epc;
                
                if (expectedCount > 0 && !catalogEpcs.isEmpty()) {
                    List<String> epcList = new ArrayList<>(catalogEpcs);
                    epc = epcList.get(simulationIndex % epcList.size());
                } else {
                    epc = simulationTags[simulationIndex % simulationTags.length];
                }
                
                int rssi = -40 - (int)(Math.random() * 40);
                
                onTagRead(epc, rssi);
                
                simulationIndex++;
                long delay = SIMULATION_MIN_DELAY_MS + 
                    (long)(Math.random() * SIMULATION_MAX_ADDITIONAL_DELAY_MS);
                
                if (isScanning) {
                    simulationHandler.postDelayed(this, delay);
                }
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
            try {
                rfidManager.stopScan();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping scanner on destroy", e);
            }
            rfidManager.setListener(null);
        }
        
        if (soundPool != null) {
            try {
                soundPool.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing sound pool", e);
            }
            soundPool = null;
        }
        
        if (simulationHandler != null) {
            simulationHandler.removeCallbacksAndMessages(null);
        }
        
        if (backgroundExecutor != null && !backgroundExecutor.isShutdown()) {
            backgroundExecutor.shutdown();
            try {
                if (!backgroundExecutor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    backgroundExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Log.w(TAG, "Executor termination interrupted", e);
                backgroundExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        if (inventoryDatabase != null) {
            inventoryDatabase.close();
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
