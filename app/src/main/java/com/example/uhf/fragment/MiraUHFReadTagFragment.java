package com.example.uhf.fragment;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.SoundPool;
import android.net.wifi.WifiManager;
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
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatSpinner;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.MiraRfid.MiraIRFIDReader;
import com.example.uhf.MiraRfid.RFIDManager;
import com.example.uhf.MiraRfid.model.RFIDDevice;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MiraUHFReadTagFragment extends MiraBaseFragment {

    private static final String TAG = "MiraUHFReadTagFragment";
    
    private TextView tvConnectionType, tvConnectionDetails;
    private LinearLayout layoutExportButtons;
    
    
    // إعدادات زمنية
    private static final long DEFAULT_INACTIVITY_TIMEOUT_MS = 180000;
    private static final long UI_UPDATE_INTERVAL_MS = 300;
    private static final int BATCH_SIZE_THRESHOLD = 50;
    
    // إعدادات البلوتوث
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    
    // ثوابت
    private static final String OFFLINE_PREFS = "uhf_inventory_cache";
    private static final String PREF_LAST_CONNECTED_DEVICE = "last_bt_device";

    // عناصر الواجهة
    private LinearLayout alertBannerView;
    private TextView tvAlertMessage;
    private Button btnDisconnectReader, btnExportCSV, btnPrintReport, btnShareReport;
    private com.google.android.material.card.MaterialCardView cardConnectionInfoView;
    private TextView tvReaderStatus;
    private AppCompatSpinner spinnerZone;
    private TextView tvTotalScanned, tvUniqueTags, tvScanRate, tvElapsed;
    private TextView tvExpectedTotal, tvFoundCount, tvMissingCount, tvUnexpectedCount, tvReconciliationResult;
    private TextView tvLastTagTitle, tvLastTagEPC, tvLastTagKarat, tvLastTagWeight, tvLastTagStatus;
    private Button btnStartInventory, btnPauseInventory, btnStopInventory, btnClearInventory, btnFinalizeAudit;
    private Button btnConnectReader;
    private ProgressBar pbScanProgress;
    private TextView tabFilterAll, tabFilterFound, tabFilterMissing, tabFilterUnexpected;
    private RecyclerView rvRecentTags;

    // RFID Manager
    private RFIDManager rfidManager;
    private MiraIRFIDReader activeReader;

    // المكونات الداخلية
    private TagAdapter tagAdapter;
    private final List<TagModel> tagList = new ArrayList<>();
    private final List<TagModel> filteredTagList = new ArrayList<>();
    private final Set<String> scannedEpcSet = new HashSet<>();
    private final Map<String, ExpectedItem> expectedItemsMap = new HashMap<>();
    
    private final Queue<TagModel> pendingTagsQueue = new LinkedList<>();
    private final List<TagModel> missingItemsCache = new ArrayList<>();
    private boolean isMissingCacheDirty = true;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler uiUpdateHandler = new Handler(Looper.getMainLooper());
    private ExecutorService networkExecutor;
    private ExecutorService hardwareExecutor;
    
    private SoundPool soundPool;
    private int soundSuccessId, soundWarningId, soundAlertId;
    private Vibrator vibrator;
    private SharedPreferences offlineCache;

    public enum InventoryState {
        IDLE, LOADING, SCANNING, PAUSED, COMPLETED, FINALIZED
    }
    
    public enum ConnectionType {
        RFID_MANAGER, BLUETOOTH, WIFI, SIMULATOR
    }
    
    private void showToast(String msg) {
    try {
        if (getContext() != null && isAdded()) {
            Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
        }
    } catch (Exception e) {
        Log.e(TAG, "showToast error: " + e.getMessage());
    }
    }

    private InventoryState currentState = InventoryState.IDLE;
    private ConnectionType activeConnectionType = ConnectionType.SIMULATOR;
    private long startTimeMs = 0;
    private long pauseStartTimeMs = 0;
    private long totalPausedDurationMs = 0;
    private long totalScannedCount = 0;
    private int lastSecondCount = 0;
    private long inactivityTimeoutMs = DEFAULT_INACTIVITY_TIMEOUT_MS;
    private String sessionId;
    private String selectedZone = "الكل";
    private String currentFilter = "ALL";
    private boolean isUiUpdateScheduled = false;
    private long lastUiUpdateTime = 0;
    private final AtomicBoolean isProcessingBatch = new AtomicBoolean(false);

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private BluetoothDevice connectedDevice;
    private InputStream btInputStream;
    private OutputStream btOutputStream;
    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(intent.getAction())) {
                handleBluetoothDisconnection();
            }
        }
    };

    private WifiManager wifiManager;
    private java.net.Socket wifiSocket;
    private InputStream wifiInputStream;
    private OutputStream wifiOutputStream;

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentState == InventoryState.SCANNING) {
                long elapsedMs = System.currentTimeMillis() - startTimeMs - totalPausedDurationMs;
                long seconds = elapsedMs / 1000;
                long minutes = seconds / 60;
                long secs = seconds % 60;
                tvElapsed.setText(String.format(Locale.US, "%02d:%02d", minutes, secs));
                int currentTotal = (int) totalScannedCount;
                int rate = currentTotal - lastSecondCount;
                lastSecondCount = currentTotal;
                tvScanRate.setText(String.format(Locale.US, "%d/s", Math.max(0, rate)));
                mainHandler.postDelayed(this, 1000);
            }
        }
    };

    private final Runnable inactivityRunnable = this::stopScanningDueToInactivity;
    
    private final Runnable simulationRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentState == InventoryState.SCANNING && activeConnectionType == ConnectionType.SIMULATOR) {
                generateSimulatedTag();
                mainHandler.postDelayed(this, 150 + new Random().nextInt(200));
            }
        }
    };
    
    private final Runnable uiBatchUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            isUiUpdateScheduled = false;
            processPendingBatch();
            updateReconciliationStats();
            if (currentFilter.equals("MISSING")) {
                isMissingCacheDirty = true;
            }
            applyFilter(currentFilter);
            lastUiUpdateTime = System.currentTimeMillis();
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_uhf_readtag, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        try {
            Context ctx = requireContext();
            networkExecutor = Executors.newFixedThreadPool(2);
            hardwareExecutor = Executors.newSingleThreadExecutor();
            offlineCache = ctx.getSharedPreferences(OFFLINE_PREFS, Context.MODE_PRIVATE);
            
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            wifiManager = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            
            IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED);
            ctx.registerReceiver(bluetoothReceiver, filter);
            
            soundPool = new SoundPool.Builder().setMaxStreams(3).build();
            soundSuccessId = soundPool.load(ctx, R.raw.beep_success, 1);
            soundWarningId = soundPool.load(ctx, R.raw.beep_warning, 1);
            soundAlertId = soundPool.load(ctx, R.raw.alarm_sold_item, 1);
            
            vibrator = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);

            // تهيئة RFID Manager
            rfidManager = RFIDManager.getInstance(ctx);
            rfidManager.setListener(new MiraIRFIDReader.OnTagReadListener() {
                @Override
                public void onTagRead(String epc, int rssi) {
                    onTagScanned(epc, rssi);
                }
                @Override
                public void onScanStarted() {
                    Log.i(TAG, "RFID Scan Started");
                }
                @Override
                public void onScanStopped() {
                    Log.i(TAG, "RFID Scan Stopped");
                }
                @Override
                public void onConnectionChanged(boolean connected) {
                    Log.i(TAG, "RFID Connection: " + connected);
                }
                @Override
                public void onError(String error) {
                    Log.e(TAG, "RFID Error: " + error);
                }
            });

            sessionId = "AUDIT-" + System.currentTimeMillis();
            isArabic = Locale.getDefault().getLanguage().equals("ar");

            detectAndSetConnectionType();
            initViews(view);
            setupListeners();
            setupRecyclerView();
            setupZoneSpinner();
            loadDynamicInventorySettings();
            loadExpectedInventory();
            startReaderMonitoring();
            setInventoryState(InventoryState.IDLE);
            
        } catch (Exception e) {
            Log.e(TAG, "onViewCreated error: " + e.getMessage());
        }
    }

    private void detectAndSetConnectionType() {
        RFIDDevice activeDevice = rfidManager.getActiveDevice();
        if (activeDevice != null && activeDevice.getType() != null && 
            activeDevice.getType() != MiraIRFIDReader.ReaderType.SIMULATOR) {
            activeConnectionType = ConnectionType.RFID_MANAGER;
        } else if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
            String lastDevice = offlineCache.getString(PREF_LAST_CONNECTED_DEVICE, null);
            if (lastDevice != null) {
                activeConnectionType = ConnectionType.BLUETOOTH;
            } else {
                activeConnectionType = ConnectionType.SIMULATOR;
            }
        } else {
            activeConnectionType = ConnectionType.SIMULATOR;
        }
    }

    private boolean isChainwayC72Device() {
        try {
            String model = Build.MODEL;
            String manufacturer = Build.MANUFACTURER;
            return (model != null && model.toUpperCase().contains("C72")) ||
                   (manufacturer != null && manufacturer.toUpperCase().contains("CHAINWAY"));
        } catch (Exception e) {
            return false;
        }
    }

    private void initViews(View view) {
        alertBannerView = view.findViewById(R.id.alertBannerView);
        tvAlertMessage = view.findViewById(R.id.tvAlertMessage);
        tvReaderStatus = view.findViewById(R.id.tvReaderStatus);
        spinnerZone = view.findViewById(R.id.spinnerZone);

        tvTotalScanned = view.findViewById(R.id.tvTotalScanned);
        tvUniqueTags = view.findViewById(R.id.tvUniqueTags);
        tvScanRate = view.findViewById(R.id.tvScanRate);
        tvElapsed = view.findViewById(R.id.tvElapsed);

        tvExpectedTotal = view.findViewById(R.id.tvExpectedTotal);
        tvFoundCount = view.findViewById(R.id.tvFoundCount);
        tvMissingCount = view.findViewById(R.id.tvMissingCount);
        tvUnexpectedCount = view.findViewById(R.id.tvUnexpectedCount);
        tvReconciliationResult = view.findViewById(R.id.tvReconciliationResult);

        tvLastTagTitle = view.findViewById(R.id.tvLastTagTitle);
        tvLastTagEPC = view.findViewById(R.id.tvLastTagEPC);
        tvLastTagKarat = view.findViewById(R.id.tvLastTagKarat);
        tvLastTagWeight = view.findViewById(R.id.tvLastTagWeight);
        tvLastTagStatus = view.findViewById(R.id.tvLastTagStatus);

        btnStartInventory = view.findViewById(R.id.btnStartInventory);
        btnPauseInventory = view.findViewById(R.id.btnPauseInventory);
        btnStopInventory = view.findViewById(R.id.btnStopInventory);
        btnClearInventory = view.findViewById(R.id.btnClearInventory);
        btnFinalizeAudit = view.findViewById(R.id.btnFinalizeAudit);
        btnConnectReader = view.findViewById(R.id.btnConnectReader);
        btnDisconnectReader = view.findViewById(R.id.btnDisconnectReader);
        btnExportCSV = view.findViewById(R.id.btnExportCSV);
        btnPrintReport = view.findViewById(R.id.btnPrintReport);
        btnShareReport = view.findViewById(R.id.btnShareReport);
        cardConnectionInfoView = view.findViewById(R.id.cardConnectionInfo);
        
        pbScanProgress = view.findViewById(R.id.pbScanProgress);

        tabFilterAll = view.findViewById(R.id.tabFilterAll);
        tabFilterFound = view.findViewById(R.id.tabFilterFound);
        tabFilterMissing = view.findViewById(R.id.tabFilterMissing);
        tabFilterUnexpected = view.findViewById(R.id.tabFilterUnexpected);

        rvRecentTags = view.findViewById(R.id.rvRecentTags);
        
        if (btnConnectReader != null) {
            btnConnectReader.setVisibility(
                activeConnectionType == ConnectionType.BLUETOOTH || 
                activeConnectionType == ConnectionType.WIFI ? View.VISIBLE : View.GONE);
        }
    }

    private void setupListeners() {
        btnStartInventory.setOnClickListener(v -> {
            if (currentState == InventoryState.PAUSED) {
                resumeScanning();
            } else {
                startScanning();
            }
        });
        
        btnPauseInventory.setOnClickListener(v -> pauseScanning());
        btnStopInventory.setOnClickListener(v -> stopScanning());
        btnClearInventory.setOnClickListener(v -> clearScanData());
        btnFinalizeAudit.setOnClickListener(v -> finalizeAndUploadAudit());
        
        if (btnConnectReader != null) btnConnectReader.setOnClickListener(v -> showConnectionDialog());
        if (btnDisconnectReader != null) btnDisconnectReader.setOnClickListener(v -> disconnectExternalReader());
        if (btnExportCSV != null) btnExportCSV.setOnClickListener(v -> exportInventoryReport());
        if (btnPrintReport != null) btnPrintReport.setOnClickListener(v -> printInventoryReport());
        if (btnShareReport != null) btnShareReport.setOnClickListener(v -> shareInventoryReport());

        tabFilterAll.setOnClickListener(v -> applyFilter("ALL"));
        tabFilterFound.setOnClickListener(v -> applyFilter("FOUND"));
        tabFilterMissing.setOnClickListener(v -> applyFilter("MISSING"));
        tabFilterUnexpected.setOnClickListener(v -> applyFilter("UNEXPECTED"));
    }
    
    private void disconnectExternalReader() {
        closeBluetoothConnection();
        closeWifiConnection();
        switchToSimulator();
        if (cardConnectionInfoView != null) cardConnectionInfoView.setVisibility(View.GONE);
    }

    private void setupRecyclerView() {
        tagAdapter = new TagAdapter(filteredTagList);
        rvRecentTags.setLayoutManager(new LinearLayoutManager(getContext()));
        rvRecentTags.setAdapter(tagAdapter);
    }

    private void shareInventoryReport() {
        networkExecutor.execute(() -> {
            try {
                StringBuilder csv = new StringBuilder();
                csv.append("EPC,Title,Karat,Weight,Status,Timestamp\n");

                for (TagModel tag : tagList) {
                    csv.append(tag.epc).append(",")
                       .append("\"").append(tag.title != null ? tag.title : "").append("\",")
                       .append(tag.karat != null ? tag.karat : "").append(",")
                       .append(String.format(Locale.US, "%.2f", tag.weight)).append(",")
                       .append(tag.status != null ? tag.status : "").append(",")
                       .append(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()))
                       .append("\n");
                }

                File exportDir = new File(requireContext().getExternalFilesDir(
                        Environment.DIRECTORY_DOCUMENTS), "AuditReports");
                if (!exportDir.exists()) exportDir.mkdirs();

                String filename = "Audit_Report_" + System.currentTimeMillis() + ".csv";
                File file = new File(exportDir, filename);

                FileOutputStream out = new FileOutputStream(file);
                out.write(csv.toString().getBytes());
                out.close();

                mainHandler.post(() -> {
                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("text/csv");
                    shareIntent.putExtra(Intent.EXTRA_STREAM, 
                            FileProvider.getUriForFile(requireContext(),
                                    requireContext().getPackageName() + ".fileprovider", file));
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(shareIntent, "مشاركة تقرير الجرد"));
                });

            } catch (Exception e) {
                Log.e(TAG, "Share error: " + e.getMessage());
            }
        });
    }
    private void setupZoneSpinner() {
    List<String> zones = new ArrayList<>();
    zones.add(isArabic ? "الكل" : "All");
    zones.add(isArabic ? "خواتم" : "Rings");
    zones.add(isArabic ? "أساور" : "Bracelets");
    zones.add(isArabic ? "أطقم" : "Sets");
    zones.add(isArabic ? "عقود" : "Necklaces");
    zones.add(isArabic ? "سلاسل" : "Chains");
    zones.add(isArabic ? "أقراط" : "Earrings");
    zones.add(isArabic ? "مشغولات أخرى" : "Other Jewelry");

    ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), 
            android.R.layout.simple_spinner_item, zones);
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    spinnerZone.setAdapter(adapter);

    spinnerZone.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            selectedZone = zones.get(position);
            // ✅ إعادة تحميل المخزون حسب النوع المحدد
            if (currentState == InventoryState.IDLE || currentState == InventoryState.COMPLETED) {
                loadExpectedInventory();
            }
        }
        @Override
        public void onNothingSelected(AdapterView<?> parent) {}
    });
}

    private void setInventoryState(InventoryState newState) {
        this.currentState = newState;

        mainHandler.post(() -> {
            switch (newState) {
                case IDLE:
                    btnStartInventory.setVisibility(View.VISIBLE);
                    btnStartInventory.setText("▶ بدء الجرد");
                    btnStartInventory.setEnabled(true);
                    btnPauseInventory.setVisibility(View.GONE);
                    btnStopInventory.setEnabled(false);
                    btnClearInventory.setEnabled(false);
                    btnFinalizeAudit.setVisibility(View.GONE);
                    pbScanProgress.setVisibility(View.GONE);
                    spinnerZone.setEnabled(true);
                    break;

                case LOADING:
                    btnStartInventory.setEnabled(false);
                    pbScanProgress.setVisibility(View.VISIBLE);
                    break;

                case SCANNING:
                    btnStartInventory.setVisibility(View.GONE);
                    btnPauseInventory.setVisibility(View.VISIBLE);
                    btnPauseInventory.setText("⏸ تعليق");
                    btnStopInventory.setEnabled(true);
                    btnClearInventory.setEnabled(false);
                    btnFinalizeAudit.setVisibility(View.GONE);
                    pbScanProgress.setVisibility(View.VISIBLE);
                    spinnerZone.setEnabled(false);
                    break;

                case PAUSED:
                    btnStartInventory.setVisibility(View.VISIBLE);
                    btnStartInventory.setText("▶ استئناف");
                    btnStartInventory.setEnabled(true);
                    btnPauseInventory.setVisibility(View.GONE);
                    btnStopInventory.setEnabled(true);
                    btnClearInventory.setEnabled(false);
                    btnFinalizeAudit.setVisibility(View.GONE);
                    pbScanProgress.setVisibility(View.GONE);
                    spinnerZone.setEnabled(false);
                    break;

                case COMPLETED:
                    btnStartInventory.setVisibility(View.VISIBLE);
                    btnStartInventory.setText("▶ بدء الجرد");
                    btnStartInventory.setEnabled(true);
                    btnPauseInventory.setVisibility(View.GONE);
                    btnStopInventory.setEnabled(false);
                    btnClearInventory.setEnabled(true);
                    btnFinalizeAudit.setVisibility(View.VISIBLE);
                    btnFinalizeAudit.setEnabled(true);
                    pbScanProgress.setVisibility(View.GONE);
                    spinnerZone.setEnabled(true);
                    break;

                case FINALIZED:
                    btnStartInventory.setVisibility(View.VISIBLE);
                    btnStartInventory.setEnabled(true);
                    btnPauseInventory.setVisibility(View.GONE);
                    btnStopInventory.setEnabled(false);
                    btnClearInventory.setEnabled(true);
                    btnFinalizeAudit.setVisibility(View.VISIBLE);
                    btnFinalizeAudit.setEnabled(false);
                    pbScanProgress.setVisibility(View.GONE);
                    spinnerZone.setEnabled(true);
                    break;
            }
        });
    }

    private void showConnectionDialog() {
        String[] options;
        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
            options = new String[]{"بلوتوث", "WiFi TCP", "محاكي"};
        } else {
            options = new String[]{"WiFi TCP", "محاكي"};
        }

        new AlertDialog.Builder(requireContext())
                .setTitle("اختيار نوع الاتصال بالقارئ")
                .setItems(options, (dialog, which) -> {
                    String selected = options[which];
                    switch (selected) {
                        case "بلوتوث":
                            connectViaBluetooth();
                            break;
                        case "WiFi TCP":
                            showWifiConnectionDialog();
                            break;
                        case "محاكي":
                            switchToSimulator();
                            break;
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void connectViaBluetooth() {
        if (bluetoothAdapter == null) {
            Toast.makeText(getContext(), "الجهاز لا يدعم البلوتوث", Toast.LENGTH_SHORT).show();
            return;
        }

        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        List<String> deviceNames = new ArrayList<>();
        List<BluetoothDevice> deviceList = new ArrayList<>();

        for (BluetoothDevice device : pairedDevices) {
            deviceNames.add(device.getName() + " (" + device.getAddress() + ")");
            deviceList.add(device);
        }

        if (deviceNames.isEmpty()) {
            Toast.makeText(getContext(), "لا توجد أجهزة بلوتوث مقترنة", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(requireContext())
                .setTitle("اختيار قارئ UHF")
                .setItems(deviceNames.toArray(new String[0]), (dialog, which) -> {
                    BluetoothDevice device = deviceList.get(which);
                    connectToBluetoothDevice(device);
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void connectToBluetoothDevice(BluetoothDevice device) {
        hardwareExecutor.execute(() -> {
            try {
                mainHandler.post(() -> {
                    tvReaderStatus.setText("جاري الاتصال...");
                    tvReaderStatus.setTextColor(Color.parseColor("#F59E0B"));
                });

                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                bluetoothAdapter.cancelDiscovery();
                bluetoothSocket.connect();

                btInputStream = bluetoothSocket.getInputStream();
                btOutputStream = bluetoothSocket.getOutputStream();
                connectedDevice = device;

                offlineCache.edit().putString(PREF_LAST_CONNECTED_DEVICE, device.getAddress()).apply();

                activeConnectionType = ConnectionType.BLUETOOTH;

                mainHandler.post(() -> {
                    tvReaderStatus.setText("متصل - BT: " + device.getName());
                    tvReaderStatus.setTextColor(Color.parseColor("#16A34A"));
                    Toast.makeText(getContext(), "✅ تم الاتصال بالقارئ عبر البلوتوث", Toast.LENGTH_SHORT).show();
                });

                startBluetoothReading();

            } catch (IOException e) {
                Log.e(TAG, "Bluetooth connection failed: " + e.getMessage());
                mainHandler.post(() -> {
                    tvReaderStatus.setText("فشل الاتصال");
                    tvReaderStatus.setTextColor(Color.parseColor("#DC2626"));
                    Toast.makeText(getContext(), "❌ فشل الاتصال: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void startBluetoothReading() {
        hardwareExecutor.execute(() -> {
            byte[] buffer = new byte[1024];
            int bytes;

            while (bluetoothSocket != null && bluetoothSocket.isConnected()) {
                try {
                    if (btInputStream != null && (bytes = btInputStream.read(buffer)) > 0) {
                        String data = new String(buffer, 0, bytes);
                        parseAndProcessTagData(data);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Bluetooth read error: " + e.getMessage());
                    break;
                }
            }

            handleBluetoothDisconnection();
        });
    }

    private void handleBluetoothDisconnection() {
        mainHandler.post(() -> {
            if (activeConnectionType == ConnectionType.BLUETOOTH) {
                tvReaderStatus.setText("انقطع الاتصال");
                tvReaderStatus.setTextColor(Color.parseColor("#DC2626"));
                activeConnectionType = ConnectionType.SIMULATOR;
                
                if (currentState == InventoryState.SCANNING) {
                    Toast.makeText(getContext(), "⚠️ انقطع اتصال البلوتوث - تم التحويل للمحاكي", Toast.LENGTH_LONG).show();
                }
            }
        });
        
        closeBluetoothConnection();
    }

    private void closeBluetoothConnection() {
        try {
            if (btInputStream != null) { btInputStream.close(); btInputStream = null; }
            if (btOutputStream != null) { btOutputStream.close(); btOutputStream = null; }
            if (bluetoothSocket != null) { bluetoothSocket.close(); bluetoothSocket = null; }
        } catch (IOException e) {
            Log.e(TAG, "Error closing bluetooth: " + e.getMessage());
        }
    }

    private void showWifiConnectionDialog() {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(requireContext());
        builder.setTitle("الاتصال عبر WiFi TCP");

        final android.widget.EditText ipInput = new android.widget.EditText(requireContext());
        ipInput.setHint("عنوان IP (مثال: 192.168.1.100)");
        ipInput.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        
        final android.widget.EditText portInput = new android.widget.EditText(requireContext());
        portInput.setHint("المنفذ (مثال: 8888)");
        portInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);

        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 10);
        layout.addView(ipInput);
        layout.addView(portInput);
        builder.setView(layout);

        builder.setPositiveButton("اتصال", (dialog, which) -> {
            String ip = ipInput.getText().toString().trim();
            String portStr = portInput.getText().toString().trim();
            
            if (!ip.isEmpty() && !portStr.isEmpty()) {
                int port = Integer.parseInt(portStr);
                connectViaWifi(ip, port);
            }
        });
        builder.setNegativeButton("إلغاء", null);
        builder.show();
    }

    private void connectViaWifi(String ipAddress, int port) {
        hardwareExecutor.execute(() -> {
            try {
                mainHandler.post(() -> {
                    tvReaderStatus.setText("جاري الاتصال WiFi...");
                    tvReaderStatus.setTextColor(Color.parseColor("#F59E0B"));
                });

                wifiSocket = new java.net.Socket(ipAddress, port);
                wifiInputStream = wifiSocket.getInputStream();
                wifiOutputStream = wifiSocket.getOutputStream();

                activeConnectionType = ConnectionType.WIFI;

                mainHandler.post(() -> {
                    tvReaderStatus.setText("متصل - WiFi: " + ipAddress);
                    tvReaderStatus.setTextColor(Color.parseColor("#16A34A"));
                    Toast.makeText(getContext(), "✅ تم الاتصال بالقارئ عبر WiFi", Toast.LENGTH_SHORT).show();
                });

                startWifiReading();

            } catch (IOException e) {
                Log.e(TAG, "WiFi connection failed: " + e.getMessage());
                mainHandler.post(() -> {
                    tvReaderStatus.setText("فشل الاتصال WiFi");
                    tvReaderStatus.setTextColor(Color.parseColor("#DC2626"));
                    Toast.makeText(getContext(), "❌ فشل الاتصال: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void startWifiReading() {
        hardwareExecutor.execute(() -> {
            byte[] buffer = new byte[1024];
            int bytes;

            while (wifiSocket != null && wifiSocket.isConnected()) {
                try {
                    if (wifiInputStream != null && (bytes = wifiInputStream.read(buffer)) > 0) {
                        String data = new String(buffer, 0, bytes);
                        parseAndProcessTagData(data);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "WiFi read error: " + e.getMessage());
                    break;
                }
            }

            handleWifiDisconnection();
        });
    }

    private void handleWifiDisconnection() {
        mainHandler.post(() -> {
            if (activeConnectionType == ConnectionType.WIFI) {
                tvReaderStatus.setText("انقطع اتصال WiFi");
                tvReaderStatus.setTextColor(Color.parseColor("#DC2626"));
                activeConnectionType = ConnectionType.SIMULATOR;
            }
        });
        
        closeWifiConnection();
    }

    private void closeWifiConnection() {
        try {
            if (wifiInputStream != null) { wifiInputStream.close(); wifiInputStream = null; }
            if (wifiOutputStream != null) { wifiOutputStream.close(); wifiOutputStream = null; }
            if (wifiSocket != null) { wifiSocket.close(); wifiSocket = null; }
        } catch (IOException e) {
            Log.e(TAG, "Error closing WiFi: " + e.getMessage());
        }
    }

    private void switchToSimulator() {
        closeBluetoothConnection();
        closeWifiConnection();
        activeConnectionType = ConnectionType.SIMULATOR;
        tvReaderStatus.setText("محاكي افتراضي");
        tvReaderStatus.setTextColor(Color.parseColor("#F59E0B"));
        Toast.makeText(getContext(), "تم التحويل إلى وضع المحاكاة", Toast.LENGTH_SHORT).show();
    }

    private void parseAndProcessTagData(String rawData) {
        try {
            String cleanData = rawData.replaceAll("[\\r\\n\\s]+", " ").trim();
            String[] parts = cleanData.split("[,;\\s]+");
            
            for (String part : parts) {
                String epc = null;
                double rssi = -50;
                
                if (part.startsWith("{")) {
                    try {
                        JSONObject json = new JSONObject(part);
                        epc = json.optString("epc", json.optString("tag", null));
                        rssi = json.optDouble("rssi", -50);
                    } catch (Exception ignored) {}
                } else if (part.contains("=")) {
                    String[] kv = part.split("=");
                    if (kv.length >= 2) {
                        if (kv[0].toUpperCase().contains("EPC")) {
                            epc = kv[1];
                        }
                        if (kv[0].toUpperCase().contains("RSSI")) {
                            try { rssi = Double.parseDouble(kv[1]); } catch (Exception ignored) {}
                        }
                    }
                } else if (part.length() >= 24 && part.matches("[A-Fa-f0-9]+")) {
                    epc = part;
                }
                
                if (epc != null && !epc.isEmpty()) {
                    final String finalEpc = epc;
                    final double finalRssi = rssi;
                    onTagScanned(finalEpc, finalRssi);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Parse error: " + e.getMessage());
        }
    }

    private void loadDynamicInventorySettings() {
        networkExecutor.execute(() -> {
            try {
                MiraApiClient api = MiraApiClient.getInstance(requireContext());
                MiraApiClient.ApiResponse settingsRes = api.getInventorySettings("Sanaa_Main");
                if (settingsRes != null && settingsRes.isSuccess && settingsRes.data != null) {
                    long timeout = settingsRes.data.optLong("inactivity_timeout", DEFAULT_INACTIVITY_TIMEOUT_MS);
                    mainHandler.post(() -> this.inactivityTimeoutMs = timeout);
                }
            } catch (Exception e) {
                Log.e(TAG, "خطأ في تحميل إعدادات الجرد: " + e.getMessage());
            }
        });
    }


private void loadExpectedInventory() {
    setInventoryState(InventoryState.LOADING);
    
    networkExecutor.execute(() -> {
        try {
            MiraApiClient api = MiraApiClient.getInstance(requireContext());
            
            // ✅ استخدام الفرع النشط الفعلي
            int branchId = 1;
            try {
                com.example.uhf.manager.MiraBranchesManager bm = 
                    com.example.uhf.manager.MiraBranchesManager.getInstance(requireContext());
                branchId = bm.getActiveBranchId();
            } catch (Exception ignored) {}
            
            MiraApiClient.ApiResponse response = api.getBranchItemsList(branchId);
            
            expectedItemsMap.clear();

            if (response != null && response.isSuccess && response.data != null) {
                JSONArray items = response.data.optJSONArray("items");
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject obj = items.getJSONObject(i);
                        String epc = obj.optString("serial_number", 
                                obj.optString("gtin13", obj.optString("epc", "")));
                        String category = obj.optString("jewelry_category", 
                                obj.optString("category", ""));
                        
                        // ✅ تصفية حسب المنطقة (النوع) المحدد
                        if (!selectedZone.equals("الكل") && !selectedZone.equals("All")) {
                            String zoneArabic = getArabicCategory(selectedZone);
                            String zoneEnglish = getEnglishCategory(selectedZone);
                            if (!category.equalsIgnoreCase(zoneArabic) && 
                                !category.equalsIgnoreCase(zoneEnglish) &&
                                !category.contains(zoneArabic) &&
                                !category.contains(zoneEnglish)) {
                                continue; // تخطي هذا العنصر
                            }
                        }
                        
                        if (!epc.isEmpty()) {
                            ExpectedItem item = new ExpectedItem(
                                    epc,
                                    obj.optString("item_title", obj.optString("title", "قطعة غير معنونة")),
                                    obj.optString("gold_karat", obj.optString("karat", "21k")),
                                    obj.optDouble("gross_weight_grams", obj.optDouble("weight", 0.0)),
                                    "SOLD".equalsIgnoreCase(obj.optString("status", ""))
                            );
                            expectedItemsMap.put(epc, item);
                        }
                    }
                }
            }

            if (expectedItemsMap.isEmpty()) {
                loadDemoData();
            }

            mainHandler.post(() -> {
                isMissingCacheDirty = true;
                updateReconciliationStats();
                setInventoryState(InventoryState.IDLE);
            });

        } catch (Exception e) {
            Log.e(TAG, "فشل جلب قائمة القطع المتوقعة: " + e.getMessage());
            mainHandler.post(() -> {
                loadDemoData();
                isMissingCacheDirty = true;
                updateReconciliationStats();
                setInventoryState(InventoryState.IDLE);
            });
        }
    });
}

/**
 * تحويل اسم النوع من العربية إلى الإنجليزية للبحث في API
 */
private String getEnglishCategory(String arabicName) {
    switch (arabicName) {
        case "خواتم": return "Rings";
        case "أساور": return "Bracelets";
        case "أطقم": return "Sets";
        case "عقود": return "Necklaces";
        case "سلاسل": return "Chains";
        case "أقراط": return "Earrings";
        default: return arabicName;
    }
}

/**
 * تحويل اسم النوع من الإنجليزية إلى العربية للبحث في API
 */
private String getArabicCategory(String englishName) {
    switch (englishName) {
        case "Rings": return "خواتم";
        case "Bracelets": return "أساور";
        case "Sets": return "أطقم";
        case "Necklaces": return "عقود";
        case "Chains": return "سلاسل";
        case "Earrings": return "أقراط";
        default: return englishName;
    }
}

    private void loadDemoData() {
        for (int i = 1; i <= 15; i++) {
            String epc = "076000000000" + String.format(Locale.US, "%02d", i);
            boolean isSold = (i == 5 || i == 12);
            ExpectedItem item = new ExpectedItem(
                    epc,
                    "سوار ذهبي نموذج " + i,
                    (i % 2 == 0) ? "21K" : "18K",
                    5.0 + (i * 0.5),
                    isSold
            );
            expectedItemsMap.put(epc, item);
        }
    }

    private void startScanning() {
        if (currentState == InventoryState.SCANNING) return;

        setInventoryState(InventoryState.SCANNING);
        startTimeMs = System.currentTimeMillis();
        totalPausedDurationMs = 0;
        lastSecondCount = 0;

        tvReaderStatus.setText("جاري المسح...");
        tvReaderStatus.setTextColor(Color.parseColor("#0284C7"));

        mainHandler.post(timerRunnable);
        resetInactivityTimer();

        switch (activeConnectionType) {
            case RFID_MANAGER:
                rfidManager.startScan();
                break;
            case BLUETOOTH:
                startBluetoothScan();
                break;
            case WIFI:
                startWifiScan();
                break;
            case SIMULATOR:
                startSimulation();
                break;
        }

        Toast.makeText(getContext(), "▶ بدأ الجرد الذكي للمخزون", Toast.LENGTH_SHORT).show();
    }

    private void pauseScanning() {
        if (currentState != InventoryState.SCANNING) return;

        setInventoryState(InventoryState.PAUSED);
        pauseStartTimeMs = System.currentTimeMillis();
        
        stopAllReaders();
        mainHandler.removeCallbacks(inactivityRunnable);
        
        tvReaderStatus.setText("مُعلق مؤقتاً");
        tvReaderStatus.setTextColor(Color.parseColor("#F59E0B"));
        
        Toast.makeText(getContext(), "⏸ تم تعليق الجلسة", Toast.LENGTH_SHORT).show();
    }

    private void resumeScanning() {
        if (currentState != InventoryState.PAUSED) return;

        totalPausedDurationMs += (System.currentTimeMillis() - pauseStartTimeMs);
        setInventoryState(InventoryState.SCANNING);
        
        resetInactivityTimer();
        
        switch (activeConnectionType) {
            case RFID_MANAGER: rfidManager.startScan(); break;
            case BLUETOOTH: startBluetoothScan(); break;
            case WIFI: startWifiScan(); break;
            case SIMULATOR: startSimulation(); break;
        }

        tvReaderStatus.setText("جاري المسح...");
        tvReaderStatus.setTextColor(Color.parseColor("#0284C7"));
        
        Toast.makeText(getContext(), "▶ تم استئناف الجلسة", Toast.LENGTH_SHORT).show();
    }

    public void stopScanning() {
        if (currentState != InventoryState.SCANNING && currentState != InventoryState.PAUSED) return;

        stopAllReaders();
        mainHandler.removeCallbacks(timerRunnable);
        mainHandler.removeCallbacks(inactivityRunnable);
        
        processPendingBatch();

        setInventoryState(InventoryState.COMPLETED);
        
        updateReaderStatusDisplay();
        
        Toast.makeText(getContext(), "⏹ اكتملت عملية الجرد", Toast.LENGTH_SHORT).show();
    }

    private void stopAllReaders() {
        stopSimulation();
        rfidManager.stopScan();
        
        if (btOutputStream != null && activeConnectionType == ConnectionType.BLUETOOTH) {
            try {
                btOutputStream.write("STOP\r\n".getBytes());
                btOutputStream.flush();
            } catch (IOException ignored) {}
        }
        if (wifiOutputStream != null && activeConnectionType == ConnectionType.WIFI) {
            try {
                wifiOutputStream.write("STOP\r\n".getBytes());
                wifiOutputStream.flush();
            } catch (IOException ignored) {}
        }
    }

    private void stopScanningDueToInactivity() {
        stopScanning();
        Toast.makeText(getContext(), 
                "⏱ تم إيقاف المسح تلقائياً لعدم وجود قراءات جديدة", 
                Toast.LENGTH_LONG).show();
    }

    private void resetInactivityTimer() {
        mainHandler.removeCallbacks(inactivityRunnable);
        if (currentState == InventoryState.SCANNING) {
            mainHandler.postDelayed(inactivityRunnable, inactivityTimeoutMs);
        }
    }

    private void clearScanData() {
        if (currentState == InventoryState.SCANNING || currentState == InventoryState.PAUSED) {
            stopScanning();
        }

        scannedEpcSet.clear();
        tagList.clear();
        filteredTagList.clear();
        pendingTagsQueue.clear();
        missingItemsCache.clear();
        isMissingCacheDirty = true;
        totalScannedCount = 0;
        startTimeMs = 0;
        lastSecondCount = 0;

        tvTotalScanned.setText("0");
        tvUniqueTags.setText("0");
        tvScanRate.setText("0/s");
        tvElapsed.setText("00:00");
        alertBannerView.setVisibility(View.GONE);
        
        tvLastTagTitle.setText("—");
        tvLastTagEPC.setText("EPC: —");
        tvLastTagKarat.setText("العيار: —");
        tvLastTagWeight.setText("الوزن: —");
        tvLastTagStatus.setText("الحالة: —");
        tvLastTagStatus.setTextColor(Color.parseColor("#64748B"));

        updateReconciliationStats();
        tagAdapter.notifyDataSetChanged();
        setInventoryState(InventoryState.IDLE);
        
        Toast.makeText(getContext(), "🗑 تم مسح بيانات الجلسة", Toast.LENGTH_SHORT).show();
    }

    private void startBluetoothScan() {
        if (btOutputStream != null) {
            hardwareExecutor.execute(() -> {
                try {
                    btOutputStream.write("START\r\n".getBytes());
                    btOutputStream.flush();
                } catch (IOException e) {
                    Log.e(TAG, "Bluetooth start scan error: " + e.getMessage());
                }
            });
        }
    }

    private void startWifiScan() {
        if (wifiOutputStream != null) {
            hardwareExecutor.execute(() -> {
                try {
                    wifiOutputStream.write("START\r\n".getBytes());
                    wifiOutputStream.flush();
                } catch (IOException e) {
                    Log.e(TAG, "WiFi start scan error: " + e.getMessage());
                }
            });
        }
    }

    public void onTagScanned(String epc, double rssi) {
        if (currentState != InventoryState.SCANNING || epc == null || epc.isEmpty()) return;

        resetInactivityTimer();
        totalScannedCount++;

        synchronized (pendingTagsQueue) {
            TagModel model = new TagModel();
            model.epc = epc.trim();
            model.rssi = rssi;
            pendingTagsQueue.add(model);
        }

        mainHandler.post(() -> {
            tvTotalScanned.setText(String.valueOf(totalScannedCount));
            
            if (!scannedEpcSet.contains(epc.trim())) {
                scannedEpcSet.add(epc.trim());
                tvUniqueTags.setText(String.valueOf(scannedEpcSet.size()));
            }
        });

        scheduleUiUpdate();
        
        if (pendingTagsQueue.size() >= BATCH_SIZE_THRESHOLD) {
            mainHandler.post(this::processPendingBatch);
        }
    }

    private void scheduleUiUpdate() {
        long now = System.currentTimeMillis();
        long timeSinceLastUpdate = now - lastUiUpdateTime;

        if (!isUiUpdateScheduled) {
            if (timeSinceLastUpdate >= UI_UPDATE_INTERVAL_MS) {
                mainHandler.post(uiBatchUpdateRunnable);
            } else {
                isUiUpdateScheduled = true;
                long delay = UI_UPDATE_INTERVAL_MS - timeSinceLastUpdate;
                mainHandler.postDelayed(uiBatchUpdateRunnable, delay);
            }
        }
    }

    private void processPendingBatch() {
        if (isProcessingBatch.get() || pendingTagsQueue.isEmpty()) return;
        
        isProcessingBatch.set(true);
        
        try {
            List<TagModel> batch = new ArrayList<>();
            synchronized (pendingTagsQueue) {
                TagModel tag;
                while ((tag = pendingTagsQueue.poll()) != null) {
                    batch.add(tag);
                }
            }

            if (batch.isEmpty()) return;

            for (TagModel model : batch) {
                processSingleTag(model);
            }

            updateReconciliationStats();
            applyFilter(currentFilter);
            checkInventoryCompletion();
            
        } finally {
            isProcessingBatch.set(false);
        }
    }

    private void processSingleTag(TagModel model) {
        ExpectedItem expected = expectedItemsMap.get(model.epc);
        
        if (expected != null) {
            model.title = expected.title;
            model.karat = expected.karat;
            model.weight = expected.weight;

            if (expected.isSold) {
                model.status = "SOLD_WARNING";
                triggerSoldAlert(expected.title, model.epc);
                playAlertSound();
            } else {
                model.status = "FOUND";
                playSuccessSound();
            }
        } else {
            model.title = "قطعة غير مسجلة بالفرع";
            model.karat = "—";
            model.weight = 0.0;
            model.status = "UNEXPECTED";
            playWarningSound();
        }

        tagList.add(0, model);
        updateLastTagCard(model);
        isMissingCacheDirty = true;
    }

    private void checkInventoryCompletion() {
        int expectedCount = expectedItemsMap.size();
        int soldCount = 0;
        for (ExpectedItem item : expectedItemsMap.values()) {
            if (item.isSold) soldCount++;
        }
        
        int targetMatches = expectedCount - soldCount;
        int foundCount = 0;
        for (String epc : scannedEpcSet) {
            ExpectedItem item = expectedItemsMap.get(epc);
            if (item != null && !item.isSold) {
                foundCount++;
            }
        }

        if (targetMatches > 0 && foundCount >= targetMatches) {
            mainHandler.post(() -> {
                stopScanning();
                showCompletionDialog();
            });
        }
    }

    private void updateReaderStatusDisplay() {
        switch (activeConnectionType) {
            case RFID_MANAGER:
                RFIDDevice device = rfidManager.getActiveDevice();
                tvReaderStatus.setText(device != null ? device.getDisplayName() : "RFID Manager");
                tvReaderStatus.setTextColor(Color.parseColor("#16A34A"));
                break;
            case BLUETOOTH:
                tvReaderStatus.setText(connectedDevice != null ? 
                    "متصل - BT: " + connectedDevice.getName() : "متصل - بلوتوث");
                tvReaderStatus.setTextColor(Color.parseColor("#16A34A"));
                break;
            case WIFI:
                tvReaderStatus.setText("متصل - WiFi");
                tvReaderStatus.setTextColor(Color.parseColor("#16A34A"));
                break;
            case SIMULATOR:
                tvReaderStatus.setText("محاكي افتراضي");
                tvReaderStatus.setTextColor(Color.parseColor("#F59E0B"));
                break;
        }
    }

    private void updateReconciliationStats() {
        int expectedCount = expectedItemsMap.size();
        int foundCount = 0;
        int unexpectedCount = 0;
        int soldCount = 0;

        for (ExpectedItem item : expectedItemsMap.values()) {
            if (item.isSold) soldCount++;
        }

        for (String epc : scannedEpcSet) {
            ExpectedItem item = expectedItemsMap.get(epc);
            if (item != null) {
                if (!item.isSold) foundCount++;
            } else {
                unexpectedCount++;
            }
        }

        int missingCount = Math.max(0, expectedCount - soldCount - foundCount);

        tvExpectedTotal.setText(String.valueOf(expectedCount));
        tvFoundCount.setText(String.valueOf(foundCount));
        tvMissingCount.setText(String.valueOf(missingCount));
        tvUnexpectedCount.setText(String.valueOf(unexpectedCount));

        if (missingCount == 0 && unexpectedCount == 0 && expectedCount > 0) {
            tvReconciliationResult.setText("✅ الجرد مكتمل ومطابق 100%");
            tvReconciliationResult.setTextColor(Color.parseColor("#16A34A"));
        } else if (foundCount > 0) {
            tvReconciliationResult.setText(String.format(Locale.US, 
                    "عجز حقيقي: %d | زائد: %d", missingCount, unexpectedCount));
            tvReconciliationResult.setTextColor(Color.parseColor("#DC2626"));
        } else {
            tvReconciliationResult.setText("جاهز لبدء الجرد");
            tvReconciliationResult.setTextColor(Color.parseColor("#475569"));
        }
    }

    private void applyFilter(String filter) {
        currentFilter = filter;
        filteredTagList.clear();
        resetTabStyles();

        switch (filter) {
            case "FOUND":
                tabFilterFound.setTextColor(Color.parseColor("#0284C7"));
                for (TagModel t : tagList) {
                    if ("FOUND".equals(t.status)) filteredTagList.add(t);
                }
                break;
                
            case "MISSING":
                tabFilterMissing.setTextColor(Color.parseColor("#0284C7"));
                if (isMissingCacheDirty) {
                    buildMissingItemsCache();
                }
                filteredTagList.addAll(missingItemsCache);
                break;
                
            case "UNEXPECTED":
                tabFilterUnexpected.setTextColor(Color.parseColor("#0284C7"));
                for (TagModel t : tagList) {
                    if ("UNEXPECTED".equals(t.status) || "SOLD_WARNING".equals(t.status)) {
                        filteredTagList.add(t);
                    }
                }
                break;
                
            default:
                tabFilterAll.setTextColor(Color.parseColor("#0284C7"));
                filteredTagList.addAll(tagList);
                break;
        }

        tagAdapter.notifyDataSetChanged();
    }

    private void buildMissingItemsCache() {
        missingItemsCache.clear();
        for (Map.Entry<String, ExpectedItem> entry : expectedItemsMap.entrySet()) {
            if (!scannedEpcSet.contains(entry.getKey()) && !entry.getValue().isSold) {
                TagModel missingItem = new TagModel();
                missingItem.epc = entry.getKey();
                missingItem.title = entry.getValue().title;
                missingItem.karat = entry.getValue().karat;
                missingItem.weight = entry.getValue().weight;
                missingItem.status = "MISSING";
                missingItem.rssi = 0;
                missingItemsCache.add(missingItem);
            }
        }
        isMissingCacheDirty = false;
    }

    private void showCompletionDialog() {
        new AlertDialog.Builder(requireContext())
                .setTitle("🎉 اكتمل جرد الرف بنجاح!")
                .setMessage("تم مسح ومطابقة جميع القطع المتوقعة بالرف بنسبة 100%.")
                .setPositiveButton("طباعة التقرير", (d, w) -> printInventoryReport())
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private void triggerSoldAlert(String title, String epc) {
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(
                        new long[]{0, 150, 100, 200, 100, 300}, -1));
            } else {
                vibrator.vibrate(500);
            }
        }

        alertBannerView.setVisibility(View.VISIBLE);
        tvAlertMessage.setText(String.format(
                "🚨 تحذير أمني: القطعة [%s] مباعة بالنظام وموجودة بالرف!\nEPC: %s", 
                title, epc));
        alertBannerView.setBackgroundColor(Color.parseColor("#DC2626"));

        mainHandler.removeCallbacks(hideAlertRunnable);
        mainHandler.postDelayed(hideAlertRunnable, 5000);
    }

    private final Runnable hideAlertRunnable = () -> {
        if (alertBannerView != null) {
            alertBannerView.setVisibility(View.GONE);
        }
    };

    private void updateLastTagCard(TagModel model) {
        tvLastTagTitle.setText(model.title);
        tvLastTagEPC.setText("EPC: " + model.epc);
        tvLastTagKarat.setText("العيار: " + model.karat);
        tvLastTagWeight.setText(String.format(Locale.US, "الوزن: %.2f جرام", model.weight));

        switch (model.status) {
            case "FOUND":
                tvLastTagStatus.setText("الحالة: ✅ مطابق");
                tvLastTagStatus.setTextColor(Color.parseColor("#16A34A"));
                break;
            case "SOLD_WARNING":
                tvLastTagStatus.setText("الحالة: 🚨 قطعة مباعة!");
                tvLastTagStatus.setTextColor(Color.parseColor("#DC2626"));
                break;
            default:
                tvLastTagStatus.setText("الحالة: ⚠️ زائد / غريب");
                tvLastTagStatus.setTextColor(Color.parseColor("#F59E0B"));
                break;
        }
    }

    private void resetTabStyles() {
        int defaultColor = Color.parseColor("#475569");
        tabFilterAll.setTextColor(defaultColor);
        tabFilterFound.setTextColor(defaultColor);
        tabFilterMissing.setTextColor(defaultColor);
        tabFilterUnexpected.setTextColor(defaultColor);
    }

    private void finalizeAndUploadAudit() {
        if (currentState != InventoryState.COMPLETED) return;

        new AlertDialog.Builder(requireContext())
                .setTitle("حفظ واعتماد الجرد")
                .setMessage("هل أنت متأكد من اعتماد جلسة الجرد الحالية وتسويتها مع النظام السحابي؟")
                .setPositiveButton("نعم، اعتمد الجرد", (d, w) -> uploadAuditToCloud())
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void uploadAuditToCloud() {
    networkExecutor.execute(() -> {
        try {
            // ✅ حساب الإحصائيات
            int found = 0;
            int unexpected = 0;
            int soldCount = 0;
            for (ExpectedItem item : expectedItemsMap.values()) {
                if (item.isSold) soldCount++;
            }
            for (String epc : scannedEpcSet) {
                ExpectedItem item = expectedItemsMap.get(epc);
                if (item != null) {
                    if (!item.isSold) found++;
                } else {
                    unexpected++;
                }
            }
            int missing = Math.max(0, expectedItemsMap.size() - soldCount - found);
            
            // ✅ جلب branch_id الفعلي
            int branchId = 1;
            try {
                com.example.uhf.manager.MiraBranchesManager bm = 
                    com.example.uhf.manager.MiraBranchesManager.getInstance(requireContext());
                branchId = bm.getActiveBranchId();
            } catch (Exception ignored) {}
            
            // ✅ بناء payload
            JSONObject payload = new JSONObject();
            payload.put("session_id", sessionId);
            payload.put("zone", selectedZone);
            payload.put("total_scanned", totalScannedCount);
            payload.put("unique_count", scannedEpcSet.size());
            payload.put("expected_count", expectedItemsMap.size());
            payload.put("found_count", found);
            payload.put("missing_count", missing);
            payload.put("unexpected_count", unexpected);
            payload.put("connection_type", activeConnectionType.name());
            payload.put("branch_id", branchId);

            // ✅ إرسال قائمة EPCs المقروءة
            JSONArray scannedArray = new JSONArray();
            for (String epc : scannedEpcSet) {
                scannedArray.put(epc);
            }
            payload.put("scanned_epcs", scannedArray);

            Log.e(TAG, "══════════ CLOSE INVENTORY ══════════");
            Log.e(TAG, "Branch ID: " + branchId);
            Log.e(TAG, "Zone: " + selectedZone);
            Log.e(TAG, "Expected: " + expectedItemsMap.size());
            Log.e(TAG, "Found: " + found);
            Log.e(TAG, "Missing: " + missing);
            Log.e(TAG, "Unexpected: " + unexpected);
            Log.e(TAG, "Payload: " + payload.toString());

            MiraApiClient api = MiraApiClient.getInstance(requireContext());
            MiraApiClient.ApiResponse res = api.closeInventorySession(payload);

            Log.e(TAG, "Response Code: " + (res != null ? res.code : "NULL"));
            Log.e(TAG, "Response Success: " + (res != null ? res.isSuccess : "NULL"));
            Log.e(TAG, "Response Data: " + (res != null && res.data != null ? res.data.toString() : "NULL"));

            final JSONObject reportData = (res != null && res.data != null) ? res.data : null;
            final int finalBranchId = branchId;
            
            mainHandler.post(() -> {
                if (res != null && res.isSuccess) {
                    setInventoryState(InventoryState.FINALIZED);
                    
                    // ✅ عرض التقرير
                    if (reportData != null) {
                        showAuditResultDialog(reportData);
                    } else {
                        showToast("✅ تم اعتماد الجرد بنجاح - الفرع: " + finalBranchId);
                    }
                } else {
                    String errorMsg = "❌ فشل رفع الجرد";
                    if (res != null && res.data != null) {
                        errorMsg = res.data.optString("message", errorMsg);
                    }
                    if (res != null && res.error != null) {
                        errorMsg += "\n" + res.error;
                    }
                    showErrorDialog(errorMsg);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Upload error: " + e.getMessage());
            e.printStackTrace();
            mainHandler.post(() -> showErrorDialog("❌ خطأ: " + e.getMessage()));
        }
    });
}
    private void showAuditResultDialog(JSONObject data) {
        try {
            JSONObject report = data.optJSONObject("report");
            JSONObject inventoryUpdate = data.optJSONObject("inventory_update");
            JSONObject riskAssessment = data.optJSONObject("risk_assessment");
            
            String riskLevel = riskAssessment != null ? riskAssessment.optString("level", "none") : "none";
            String icon = getRiskIcon(riskLevel);
            int iconColor = getRiskColor(riskLevel);
            
            List<String[]> details = new ArrayList<>();
            if (report != null) {
                details.add(new String[]{"📦 المتوقع", String.valueOf(report.optInt("expected_count", 0))});
                details.add(new String[]{"✅ المطابق", String.valueOf(report.optInt("found_count", 0))});
                details.add(new String[]{"🔍 إجمالي المسح", String.valueOf(report.optInt("total_scanned", 0))});
                details.add(new String[]{"❌ عجز", String.valueOf(report.optInt("missing_count", 0))});
                details.add(new String[]{"⚠️ زائد", String.valueOf(report.optInt("unexpected_count", 0))});
                double accuracy = report.optDouble("accuracy", 0);
                details.add(new String[]{"🎯 نسبة التطابق", accuracy + "%"});
            }
            if (inventoryUpdate != null && inventoryUpdate.optBoolean("updated", false)) {
                details.add(new String[]{"✅ تم التحقق", String.valueOf(inventoryUpdate.optInt("verified", 0))});
                details.add(new String[]{"🔍 غير مقروء", String.valueOf(inventoryUpdate.optInt("unverified", 0))});
            }
            
            String riskMsg = riskAssessment != null ? riskAssessment.optString("message", "") : "";
            
            showDetailsDialog(
                icon + " " + (isArabic ? "تقرير اعتماد الجرد" : "Audit Report"),
                details.toArray(new String[0][])
            );
            
            if (riskMsg != null && !riskMsg.isEmpty()) {
                showToast(riskMsg);
            }
        } catch (Exception e) {
            Log.e(TAG, "showAuditResultDialog error: " + e.getMessage());
            Toast.makeText(getContext(), "✅ تم اعتماد الجرد بنجاح", Toast.LENGTH_LONG).show();
        }
    }

    private String getRiskIcon(String level) {
        switch (level) {
            case "none": return "✅";
            case "low": return "👍";
            case "medium": return "⚠️";
            case "high": return "🚨";
            case "critical": return "🔴";
            default: return "✅";
        }
    }

    private int getRiskColor(String level) {
        switch (level) {
            case "none": return Color.parseColor("#16A34A");
            case "low": return Color.parseColor("#16A34A");
            case "medium": return Color.parseColor("#F59E0B");
            case "high": return Color.parseColor("#EA580C");
            case "critical": return Color.parseColor("#DC2626");
            default: return Color.parseColor("#16A34A");
        }
    }

    private void showErrorDialog(String message) {
        try {
            if (getContext() == null || !isAdded()) return;
            new AlertDialog.Builder(requireContext())
                    .setTitle(isArabic ? "❌ فشل اعتماد الجرد" : "❌ Audit Finalization Failed")
                    .setMessage(message)
                    .setPositiveButton(isArabic ? "حسناً" : "OK", (d, w) -> d.dismiss())
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "showErrorDialog error: " + e.getMessage());
        }
    }

    private void exportInventoryReport() {
        networkExecutor.execute(() -> {
            try {
                StringBuilder csv = new StringBuilder();
                csv.append("EPC,Title,Karat,Weight,Status,Timestamp\n");

                for (TagModel tag : tagList) {
                    csv.append(tag.epc).append(",")
                       .append("\"").append(tag.title != null ? tag.title : "").append("\",")
                       .append(tag.karat != null ? tag.karat : "").append(",")
                       .append(String.format(Locale.US, "%.2f", tag.weight)).append(",")
                       .append(tag.status != null ? tag.status : "").append(",")
                       .append(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()))
                       .append("\n");
                }

                File exportDir = new File(requireContext().getExternalFilesDir(
                        Environment.DIRECTORY_DOCUMENTS), "AuditReports");
                if (!exportDir.exists()) exportDir.mkdirs();

                String filename = "Audit_Report_" + System.currentTimeMillis() + ".csv";
                File file = new File(exportDir, filename);

                FileOutputStream out = new FileOutputStream(file);
                out.write(csv.toString().getBytes());
                out.close();

                mainHandler.post(() -> Toast.makeText(getContext(), 
                        "تم تصدير التقرير: " + file.getAbsolutePath(), 
                        Toast.LENGTH_LONG).show());

            } catch (Exception e) {
                Log.e(TAG, "Export error: " + e.getMessage());
            }
        });
    }

    private void printInventoryReport() {
        try {
            WebView webView = new WebView(requireContext());
            String currentDate = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(new Date());

            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html><html><head><meta charset='utf-8'>")
                .append("<style>")
                .append("body { font-family: sans-serif; direction: rtl; text-align: right; padding: 20px; }")
                .append(".header { text-align: center; border-bottom: 2px solid #0284c7; padding-bottom: 12px; margin-bottom: 20px; }")
                .append(".header h1 { margin: 0; color: #0f172a; }")
                .append(".summary { background: #f8fafc; padding: 12px; border-radius: 8px; margin-bottom: 20px; }")
                .append("table { width: 100%; border-collapse: collapse; font-size: 12px; }")
                .append("th, td { border: 1px solid #cbd5e1; padding: 8px; text-align: right; }")
                .append("th { background-color: #f1f5f9; }")
                .append(".found { color: #16a34a; font-weight: bold; }")
                .append(".missing { color: #dc2626; font-weight: bold; }")
                .append(".warning { color: #ef4444; font-weight: bold; background: #fee2e2; }")
                .append("</style></head><body>")
                .append("<div class='header'>")
                .append("<h1>تقرير جرد المخزون</h1>")
                .append("<p>الجلسة: ").append(sessionId).append(" | التاريخ: ").append(currentDate).append("</p>")
                .append("<p>نوع الاتصال: ").append(activeConnectionType.name()).append("</p>")
                .append("</div>")
                .append("<div class='summary'>")
                .append("<p>إجمالي المتوقع: ").append(expectedItemsMap.size())
                .append(" | المطابق: ").append(tvFoundCount.getText())
                .append(" | العجز: ").append(tvMissingCount.getText())
                .append(" | الزائد: ").append(tvUnexpectedCount.getText()).append("</p>")
                .append("</div>")
                .append("<table><tr><th>#</th><th>EPC</th><th>البيان</th><th>العيار</th><th>الوزن</th><th>الحالة</th></tr>");

            int index = 1;
            for (TagModel tag : tagList) {
                String cssClass = "found";
                if ("MISSING".equals(tag.status)) cssClass = "missing";
                else if ("SOLD_WARNING".equals(tag.status)) cssClass = "warning";

                html.append("<tr>")
                    .append("<td>").append(index++).append("</td>")
                    .append("<td>").append(tag.epc).append("</td>")
                    .append("<td>").append(tag.title != null ? tag.title : "—").append("</td>")
                    .append("<td>").append(tag.karat != null ? tag.karat : "—").append("</td>")
                    .append("<td>").append(String.format(Locale.US, "%.2f جرام", tag.weight)).append("</td>")
                    .append("<td class='").append(cssClass).append("'>").append(tag.status).append("</td>")
                    .append("</tr>");
            }

            html.append("</table></body></html>");

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    PrintManager printManager = (PrintManager) requireContext()
                            .getSystemService(Context.PRINT_SERVICE);
                    if (printManager != null) {
                        printManager.print("Mira_Audit_" + sessionId, 
                                webView.createPrintDocumentAdapter("MiraAudit"),
                                new PrintAttributes.Builder().build());
                    }
                }
            });

            webView.loadDataWithBaseURL(null, html.toString(), "text/html", "UTF-8", null);

        } catch (Exception e) {
            Log.e(TAG, "Print error: " + e.getMessage());
        }
    }

    private void startSimulation() {
        mainHandler.post(simulationRunnable);
    }

    private void stopSimulation() {
        mainHandler.removeCallbacks(simulationRunnable);
    }

    private void generateSimulatedTag() {
        if (currentState != InventoryState.SCANNING) return;

        String epc;
        if (!expectedItemsMap.isEmpty() && new Random().nextDouble() < 0.85) {
            List<String> keys = new ArrayList<>(expectedItemsMap.keySet());
            epc = keys.get(new Random().nextInt(keys.size()));
        } else {
            epc = "07" + String.format(Locale.US, "%011d", new Random().nextInt(1000000000));
        }

        double rssi = -35 - new Random().nextInt(45);
        onTagScanned(epc, rssi);
    }

    private void startReaderMonitoring() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                updateReaderStatusDisplay();
                if (isAdded()) {
                    mainHandler.postDelayed(this, 3000);
                }
            }
        }, 1000);
    }

    private void playSuccessSound() {
        if (soundPool != null) soundPool.play(soundSuccessId, 0.7f, 0.7f, 1, 0, 1.0f);
    }

    private void playWarningSound() {
        if (soundPool != null) soundPool.play(soundWarningId, 0.7f, 0.7f, 1, 0, 1.0f);
    }

    private void playAlertSound() {
        if (soundPool != null) soundPool.play(soundAlertId, 1.0f, 1.0f, 1, 0, 1.0f);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopScanning();
        mainHandler.removeCallbacksAndMessages(null);
        uiUpdateHandler.removeCallbacksAndMessages(null);

        closeBluetoothConnection();
        closeWifiConnection();
        rfidManager.release();

        if (networkExecutor != null && !networkExecutor.isShutdown()) networkExecutor.shutdown();
        if (hardwareExecutor != null && !hardwareExecutor.isShutdown()) hardwareExecutor.shutdown();
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
        
        try {
            requireContext().unregisterReceiver(bluetoothReceiver);
        } catch (Exception ignored) {}
    }

    public boolean isScanning() {
        return currentState == InventoryState.SCANNING || currentState == InventoryState.PAUSED;
    }

    public boolean onBackPressed() {
        if (currentState == InventoryState.SCANNING || currentState == InventoryState.PAUSED) {
            showExitWarningDialog();
            return true;
        }
        return false;
    }

    private void showExitWarningDialog() {
        if (getContext() == null) return;
        
        new AlertDialog.Builder(requireContext())
                .setTitle("⚠️ تحذير: جرد محمي قيد التشغيل")
                .setMessage("جلسة الجرد نشطة حالياً.\nهل ترغب في إيقاف الجرد والخروج؟")
                .setPositiveButton("⏹ إيقاف وخروج", (d, w) -> {
                    stopScanning();
                    if (getActivity() != null) {
                        getActivity().onBackPressed();
                    }
                })
                .setNegativeButton("متابعة الجرد", null)
                .setCancelable(false)
                .show();
    }

    public void onKeyDownTrigger() {
        if (currentState == InventoryState.SCANNING || currentState == InventoryState.PAUSED) {
            stopScanning();
        } else if (currentState == InventoryState.IDLE || currentState == InventoryState.COMPLETED) {
            startScanning();
        }
    }

    private static class ExpectedItem {
        String epc, title, karat;
        double weight;
        boolean isSold;

        ExpectedItem(String epc, String title, String karat, double weight, boolean isSold) {
            this.epc = epc;
            this.title = title;
            this.karat = karat;
            this.weight = weight;
            this.isSold = isSold;
        }
    }

    private static class TagModel {
        String epc, title, karat, status;
        double weight, rssi;
    }

    private class TagAdapter extends RecyclerView.Adapter<TagAdapter.ViewHolder> {
        private final List<TagModel> list;

        TagAdapter(List<TagModel> list) {
            this.list = list;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_scanned_tag, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            TagModel item = list.get(position);
            
            holder.tvEpc.setText(item.epc);
            holder.tvTitle.setText(item.title != null ? item.title : "جاري التحميل...");
            holder.tvRssi.setText(String.format(Locale.US, "%.0f dBm", item.rssi));

            switch (item.status) {
                case "FOUND":
                    holder.tvStatus.setText("✅ مطابق");
                    holder.tvStatus.setTextColor(Color.parseColor("#16A34A"));
                    break;
                case "SOLD_WARNING":
                    holder.tvStatus.setText("🚨 مباع!");
                    holder.tvStatus.setTextColor(Color.parseColor("#DC2626"));
                    break;
                case "MISSING":
                    holder.tvStatus.setText("❌ عجز");
                    holder.tvStatus.setTextColor(Color.parseColor("#DC2626"));
                    break;
                default:
                    holder.tvStatus.setText("⚠️ زائد");
                    holder.tvStatus.setTextColor(Color.parseColor("#F59E0B"));
                    break;
            }
        }

        @Override
        public int getItemCount() {
            return list.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvEpc, tvTitle, tvRssi, tvStatus;

            ViewHolder(@NonNull View v) {
                super(v);
                tvEpc = v.findViewById(R.id.tvTagEpc);
                tvTitle = v.findViewById(R.id.tvTagTitle);
                tvRssi = v.findViewById(R.id.tvTagRssi);
                tvStatus = v.findViewById(R.id.tvTagStatus);
            }
        }
    }
                }
