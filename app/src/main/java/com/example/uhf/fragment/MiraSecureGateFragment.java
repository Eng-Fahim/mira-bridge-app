package com.example.uhf.fragment;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.manager.MiraGatesSettings;
import com.example.uhf.manager.MiraSettingsManager;
import com.example.uhf.manager.PermissionManager;
import com.example.uhf.tools.SoundGenerator;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import com.rscja.deviceapi.entity.UHFTAGInfo;
import com.rscja.deviceapi.interfaces.IUHFInventoryCallback;

import com.example.uhf.api.MiraApiClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import java.util.ArrayList;
import java.util.List;

@RequiresApi(api = Build.VERSION_CODES.O)
public class MiraSecureGateFragment extends Fragment {

    private static final String TAG = "MiraSecureGate";
    private static final long SCAN_COOL_DOWN_MS = 2500;
    private static final int API_TIMEOUT_SECONDS = 5;
    private static final int SYNC_INTERVAL_SECONDS = 30;
    private static final int UI_RESET_DELAY_MS = 3000;

    private UHFMainActivity mContext;
    private MiraSettingsManager settingsManager;
    private MiraGatesSettings miraGatesSettings;
    private PermissionManager permissionManager;
    
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler flashHandler = new Handler(Looper.getMainLooper());
    private final Handler soundHandler = new Handler(Looper.getMainLooper());
    
    private ExecutorService gateExecutor;
    private ScheduledExecutorService dbSyncScheduler;

    private TextView tvGateIcon, tvGateStatus, tvLastEvent, tvDebugConsole;
    private TextView tvPassCount, tvDenyCount, tvTotalCount;
    private TextView tvOverlayTitle, tvOverlayDetails;
    private EditText etManualEpc;
    private View statusIndicator;
    private FrameLayout overlayAlarm;
    private Button btnArmGate, btnDisarmGate, btnSubmitManual, btnScanBarcode;
    private Button btnSimulateAllowed, btnSimulateDenied, btnStopAlarm;
    private ScrollView scrollDebugLog;

    private volatile boolean gateActive = false;
    private volatile boolean isAlarmActive = false;
    private volatile boolean isDestroyed = false;
    private int passCount = 0;
    private int denyCount = 0;
    private int totalCount = 0;

    private boolean flashState = false;
    private Runnable flashRunnable;
    private Runnable soundLoopRunnable;

    private final Set<String> localAllowedEpcCache = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Long> scanDebounceMap = new ConcurrentHashMap<>();

    private Vibrator vibrator;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private final ActivityResultLauncher<ScanOptions> barcodeLauncher = registerForActivityResult(
            new ScanContract(),
            result -> {
                if (result.getContents() != null && !isDestroyed) {
                    String scannedBarcode = result.getContents().trim();
                    logConsole("📷 [الكاميرا]: تم التقاط الباركود: " + scannedBarcode);
                    processInputEpc(scannedBarcode, "CAMERA");
                }
            }
    );

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_secure_gate, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        initializeComponents();
        initViews(view);
        setupClickListeners();
        applyPermissions();
        updateUI();

        logConsole("🚀 تم تشغيل MIRA Secure Gate™");
        logConsole("🔊 صوت الإنذار: " + miraGatesSettings.getSoundAlarm());
        logConsole("📡 قوة RFID: " + miraGatesSettings.getRfidPower() + " dBm");
    }

    private void initializeComponents() {
        if (getActivity() instanceof UHFMainActivity) {
            mContext = (UHFMainActivity) getActivity();
            settingsManager = MiraSettingsManager.getInstance(mContext);
            miraGatesSettings = MiraGatesSettings.getInstance(mContext);
            permissionManager = PermissionManager.getInstance(mContext);
            vibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
        }
        gateExecutor = Executors.newFixedThreadPool(4);
        isDestroyed = false;
    }

    private void applyPermissions() {
        boolean canManageGates = permissionManager.canEdit("gates") || 
                                  permissionManager.isAdmin() || 
                                  permissionManager.isBranchManager();
        
        if (btnArmGate != null) btnArmGate.setEnabled(canManageGates);
        if (btnDisarmGate != null) btnDisarmGate.setEnabled(canManageGates);
        if (btnSimulateAllowed != null) btnSimulateAllowed.setEnabled(canManageGates);
        if (btnSimulateDenied != null) btnSimulateDenied.setEnabled(canManageGates);
        
        if (!canManageGates) {
            if (tvGateStatus != null) {
                tvGateStatus.setText(isArabic() ? "⛔ وضع المراقبة فقط" : "⛔ Monitor only mode");
            }
        }
    }

    private boolean isArabic() {
        return Locale.getDefault().getLanguage().equals("ar");
    }

    private Spinner spGateSelector;
    private List<MiraGatesSettings.GateDevice> gatesList = new ArrayList<>();
    private String currentGateId = "";
    private void initViews(View view) {
        tvGateIcon = view.findViewById(R.id.tvGateIcon);
        tvGateStatus = view.findViewById(R.id.tvGateStatus);
        tvLastEvent = view.findViewById(R.id.tvLastEvent);
        tvPassCount = view.findViewById(R.id.tvPassCount);
        tvDenyCount = view.findViewById(R.id.tvDenyCount);
        tvTotalCount = view.findViewById(R.id.tvTotalCount);
        etManualEpc = view.findViewById(R.id.etManualEpc);
        statusIndicator = view.findViewById(R.id.statusIndicator);
        btnArmGate = view.findViewById(R.id.btnArmGate);
        btnDisarmGate = view.findViewById(R.id.btnDisarmGate);
        btnSubmitManual = view.findViewById(R.id.btnSubmitManual);
        btnScanBarcode = view.findViewById(R.id.btnScanBarcode);
        btnSimulateAllowed = view.findViewById(R.id.btnSimulateAllowed);
        btnSimulateDenied = view.findViewById(R.id.btnSimulateDenied);
        tvDebugConsole = view.findViewById(R.id.tvDebugConsole);
        scrollDebugLog = view.findViewById(R.id.scrollDebugLog);
        overlayAlarm = view.findViewById(R.id.overlayAlarm);
        tvOverlayTitle = view.findViewById(R.id.tvOverlayTitle);
        tvOverlayDetails = view.findViewById(R.id.tvOverlayDetails);
        btnStopAlarm = view.findViewById(R.id.btnStopAlarm);
        
        spGateSelector = view.findViewById(R.id.spGateSelector);
    
    // تحميل البوابات
    loadGatesForSelector();
    }


private void loadGatesForSelector() {
    if (miraGatesSettings == null) return;
    
    gatesList = miraGatesSettings.getGates();
    
    if (gatesList.isEmpty()) {
        // إذا لا توجد بوابات، أضف البوابة الحالية
        MiraGatesSettings.GateDevice currentGate = new MiraGatesSettings.GateDevice();
        currentGate.gateId = "handheld_c72";
        currentGate.gateName = isArabic() ? "هذا الجهاز (C72)" : "This Device (C72)";
        currentGate.location = isArabic() ? "غير محدد" : "Unknown";
        gatesList.add(currentGate);
    }
    
    // تعبئة الـ Spinner
    String[] gateNames = new String[gatesList.size()];
    for (int i = 0; i < gatesList.size(); i++) {
        gateNames[i] = gatesList.get(i).gateName + " (" + gatesList.get(i).location + ")";
    }
    
    ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
        android.R.layout.simple_spinner_item, gateNames);
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    spGateSelector.setAdapter(adapter);
    
    // اختيار أول بوابة
    if (!gatesList.isEmpty()) {
        currentGateId = gatesList.get(0).gateId;
    }
    
    // مستمع التغيير
    spGateSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            if (position < gatesList.size()) {
                currentGateId = gatesList.get(position).gateId;
                logConsole(isArabic() ? "🚪 تم اختيار: " : "🚪 Selected: " + gatesList.get(position).gateName);
                
                // إذا كانت البوابة مفعلة، أعد التشغيل للبوابة الجديدة
                if (gateActive) {
                    disarmGate();
                    armGate();
                }
            }
        }
        @Override
        public void onNothingSelected(AdapterView<?> parent) {}
    });
}
    private void setupClickListeners() {
        btnArmGate.setOnClickListener(v -> {
            if (permissionManager.canEdit("gates") || permissionManager.isAdmin() || permissionManager.isBranchManager()) {
                armGate();
            } else {
                Toast.makeText(getContext(), 
                    isArabic() ? "⛔ غير مصرح" : "⛔ Access Denied", 
                    Toast.LENGTH_SHORT).show();
            }
        });
        
        btnDisarmGate.setOnClickListener(v -> {
            if (permissionManager.canEdit("gates") || permissionManager.isAdmin() || permissionManager.isBranchManager()) {
                disarmGate();
            } else {
                Toast.makeText(getContext(), 
                    isArabic() ? "⛔ غير مصرح" : "⛔ Access Denied", 
                    Toast.LENGTH_SHORT).show();
            }
        });

        btnSubmitManual.setOnClickListener(v -> {
            if (isDestroyed) return;
            String manualCode = etManualEpc.getText().toString().trim();
            if (!manualCode.isEmpty()) {
                etManualEpc.setText("");
                processInputEpc(manualCode, "MANUAL");
            } else {
                Toast.makeText(getContext(), 
                    isArabic() ? "يرجى كتابة رقم القطعة أولاً" : "Please enter item code first", 
                    Toast.LENGTH_SHORT).show();
            }
        });

        btnScanBarcode.setOnClickListener(v -> {
            if (isDestroyed) return;
            if (!gateActive) {
                Toast.makeText(getContext(), 
                    isArabic() ? "قم بتفعيل البوابة أولاً!" : "Arm the gate first!", 
                    Toast.LENGTH_SHORT).show();
                return;
            }
            ScanOptions options = new ScanOptions();
            options.setPrompt(isArabic() ? "وجه الكاميرا نحو باركود أو QR القطعة" : "Point camera at item barcode or QR");
            options.setBeepEnabled(false);
            options.setOrientationLocked(true);
            options.setCaptureActivity(com.journeyapps.barcodescanner.CaptureActivity.class);
            barcodeLauncher.launch(options);
        });

        btnSimulateAllowed.setOnClickListener(v -> {
            if (isDestroyed) return;
            if (!(permissionManager.canEdit("gates") || permissionManager.isAdmin() || permissionManager.isBranchManager())) {
                Toast.makeText(getContext(), 
                    isArabic() ? "⛔ غير مصرح" : "⛔ Access Denied", 
                    Toast.LENGTH_SHORT).show();
                return;
            }
            String mockAllowedEpc = "E20041000000000000009999";
            localAllowedEpcCache.add(mockAllowedEpc);
            processInputEpc(mockAllowedEpc, "SIMULATION_ALLOWED");
        });

        btnSimulateDenied.setOnClickListener(v -> {
            if (isDestroyed) return;
            if (!(permissionManager.canEdit("gates") || permissionManager.isAdmin() || permissionManager.isBranchManager())) {
                Toast.makeText(getContext(), 
                    isArabic() ? "⛔ غير مصرح" : "⛔ Access Denied", 
                    Toast.LENGTH_SHORT).show();
                return;
            }
            String mockDeniedEpc = "E20041000000000000001111";
            localAllowedEpcCache.remove(mockDeniedEpc);
            processInputEpc(mockDeniedEpc, "SIMULATION_DENIED");
        });

        btnStopAlarm.setOnClickListener(v -> stopAlarmEffects());
    }

    private void armGate() {
    if (isDestroyed) return;
    
    if (currentGateId.isEmpty()) {
        Toast.makeText(getContext(), 
            isArabic() ? "⚠️ اختر بوابة أولاً" : "⚠️ Select a gate first", 
            Toast.LENGTH_SHORT).show();
        return;
    }
    
    gateActive = true;
    updateUI();
    logConsole(isArabic() ? "🛡️ تم تفعيل البوابة: " : "🛡️ Gate armed: " + getCurrentGateName());
    
    syncLocalAllowedCache();
    startRealtimeDbSync();
    startContinuousRfidScan();
    updateGateStatusOnServerForSpecificGate(currentGateId, "armed");
}

private String getCurrentGateName() {
    for (MiraGatesSettings.GateDevice gate : gatesList) {
        if (gate.gateId.equals(currentGateId)) {
            return gate.gateName;
        }
    }
    return "";
}

    private void disarmGate() {
        if (isDestroyed) return;
        
        if (!(permissionManager.canEdit("gates") || permissionManager.isAdmin() || permissionManager.isBranchManager())) {
            Toast.makeText(getContext(), 
                isArabic() ? "⛔ غير مصرح" : "⛔ Access Denied", 
                Toast.LENGTH_SHORT).show();
            return;
        }
        
        gateActive = false;
        stopAlarmEffects();
        stopContinuousRfidScan();
        stopRealtimeDbSync();

        passCount = 0;
        denyCount = 0;
        totalCount = 0;
        updateCounters();

        scanDebounceMap.clear();
        updateUI();

        logConsole(isArabic() ? "🔒 تم إيقاف البوابة." : "🔒 Gate disarmed.");
        updateGateStatusOnServer("offline");

        if (isAdded()) {
            Toast.makeText(requireContext(), 
                isArabic() ? "تم إيقاف البوابة" : "Gate disarmed", 
                Toast.LENGTH_SHORT).show();
        }
    }

    private void startContinuousRfidScan() {
        if (isDestroyed || mContext == null || mContext.mReader == null) {
            logConsole(isArabic() ? "⚠️ قارئ RFID غير متوفر" : "⚠️ RFID reader unavailable");
            return;
        }
        
        try {
            int rfidPower = miraGatesSettings.getRfidPower();
            mContext.mReader.setPower(rfidPower);
            logConsole("📡 RFID Power: " + rfidPower + " dBm");

            mContext.mReader.setInventoryCallback(new IUHFInventoryCallback() {
                @Override
                public void callback(UHFTAGInfo uhftagInfo) {
                    if (!isDestroyed && uhftagInfo != null && uhftagInfo.getEPC() != null) {
                        processInputEpc(uhftagInfo.getEPC(), "RFID_HW");
                    }
                }
            });
            mContext.mReader.startInventoryTag();
        } catch (Exception e) {
            logConsole("⚠️ " + (isArabic() ? "خطأ في القارئ المادي: " : "Hardware reader error: ") + e.getMessage());
        }
    }

    private void stopContinuousRfidScan() {
        if (mContext != null && mContext.mReader != null) {
            try {
                mContext.mReader.stopInventory();
                mContext.mReader.setInventoryCallback(null);
                mContext.mReader.setPower(30);
            } catch (Exception e) {
                Log.e(TAG, "Error stopping reader: " + e.getMessage());
            }
        }
    }

    private void startRealtimeDbSync() {
        stopRealtimeDbSync();
        dbSyncScheduler = Executors.newSingleThreadScheduledExecutor();
        dbSyncScheduler.scheduleAtFixedRate(
            this::syncLocalAllowedCache, 
            0, 
            SYNC_INTERVAL_SECONDS, 
            TimeUnit.SECONDS
        );
    }

    private void stopRealtimeDbSync() {
        if (dbSyncScheduler != null && !dbSyncScheduler.isShutdown()) {
            dbSyncScheduler.shutdownNow();
            dbSyncScheduler = null;
        }
    }

    private void processInputEpc(String epc, String sourceType) {
        if (isDestroyed || !gateActive || !isAdded()) return;
        if (epc == null || epc.trim().isEmpty()) return;

        final String cleanedEpc = epc.trim().toUpperCase();
        long currentTime = System.currentTimeMillis();
        
        cleanupDebounceMap(currentTime);

        Long lastTime = scanDebounceMap.putIfAbsent(cleanedEpc, currentTime);
        
        if (lastTime != null) {
            if (currentTime - lastTime < SCAN_COOL_DOWN_MS) {
                return;
            }
            scanDebounceMap.put(cleanedEpc, currentTime);
        }

        mainHandler.post(() -> {
            if (isDestroyed || !isAdded()) return;
            totalCount++;
            updateCounters();
            String shortEpc = cleanedEpc.length() > 8 ? cleanedEpc.substring(cleanedEpc.length() - 8) : cleanedEpc;
            tvLastEvent.setText((isArabic() ? "جاري التحقق..." : "Verifying...") + " [" + sourceType + ": " + shortEpc + "]");
        });

        boolean isLocallyAllowed = localAllowedEpcCache.contains(cleanedEpc);

        if (isLocallyAllowed) {
            mainHandler.post(() -> handleGateDecision(true, 
                isArabic() ? "قطعة مصرح بها (مباعة)" : "Authorized item (Sold)", cleanedEpc));
        } else {
            queryMiraGateServer(cleanedEpc);
        }
    }

    private void cleanupDebounceMap(long currentTime) {
        if (scanDebounceMap.size() > 100) {
            Iterator<Map.Entry<String, Long>> iterator = scanDebounceMap.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Long> entry = iterator.next();
                if (currentTime - entry.getValue() > SCAN_COOL_DOWN_MS * 2) {
                    iterator.remove();
                }
            }
        }
    }

    private void queryMiraGateServer(String epc) {
        if (isDestroyed || gateExecutor == null || gateExecutor.isShutdown()) return;
        
        gateExecutor.execute(() -> {
            Future<MiraApiClient.ApiResponse> future = null;
            try {
                MiraApiClient client = MiraApiClient.getInstance(mContext);
                if (client == null) {
                    mainHandler.post(() -> handleGateDecision(false, 
                        isArabic() ? "🚨 خطأ أوفلاين / لا يوجد اتصال" : "🚨 Offline error", epc));
                    return;
                }
                
                JSONObject extraParams = new JSONObject();
                extraParams.put("context", "gate");
                
                final String finalEpc = epc;
                future = gateExecutor.submit(() -> client.authorize(finalEpc, "gate", extraParams));
                
                MiraApiClient.ApiResponse response = future.get(API_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                
                if (response != null && response.isSuccess && response.data != null) {
                    JSONObject decision = response.data.optJSONObject("decision");
                    JSONObject item = response.data.optJSONObject("item");
                    
                    boolean allowed = decision != null && decision.optBoolean("allowed", false);
                    String itemTitle = item != null ? item.optString("title", 
                        isArabic() ? "قطعة مجوهرات" : "Jewelry Item") : (isArabic() ? "غير مسجلة" : "Unregistered");
                    String serial = item != null ? item.optString("serial", epc) : epc;
                    
                    if (allowed) {
                        localAllowedEpcCache.add(epc);
                    } else {
                        localAllowedEpcCache.remove(epc);
                    }
                    
                    final boolean finalAllowed = allowed;
                    final String finalTitle = itemTitle;
                    final String finalSerial = serial;
                    
                    mainHandler.post(() -> handleGateDecision(finalAllowed, finalTitle, finalSerial));
                } else {
                    mainHandler.post(() -> handleGateDecision(false, 
                        isArabic() ? "🚨 خروج غير مصرح - سيرفر" : "🚨 Unauthorized - Server", epc));
                }
            } catch (TimeoutException e) {
                Log.e(TAG, "Query gate timeout: " + e.getMessage());
                if (future != null) future.cancel(true);
                mainHandler.post(() -> handleGateDecision(false, 
                    isArabic() ? "⚠️ انقطاع اتصال (Timeout) - مرفوض أمنياً" : "⚠️ Timeout - Denied", epc));
            } catch (Exception e) {
                Log.e(TAG, "Query gate error: " + e.getMessage());
                if (future != null) future.cancel(true);
                mainHandler.post(() -> handleGateDecision(false, 
                    isArabic() ? "🚨 خروج غير مصرح (أوفلاين)" : "🚨 Unauthorized (Offline)", epc));
            }
        });
    }

    private void handleGateDecision(boolean allowed, String itemTitle, String serial) {
        if (isDestroyed || !isAdded()) return;
        
        logGateEventToServer(serial, allowed ? "PASS" : "DENY_ALARM", itemTitle);

        if (allowed) {
            passCount++;
            updateCounters();
            updateUIForPass(itemTitle, serial);
        } else {
            denyCount++;
            updateCounters();
            updateUIForDeny(itemTitle, serial);
        }
    }

    private void updateUIForPass(String itemTitle, String serial) {
        if (isDestroyed || !isAdded()) return;
        
        tvGateStatus.setText(isArabic() ? "✅ مسموح" : "✅ PASS");
        tvGateStatus.setTextColor(Color.parseColor("#2E7D32"));
        statusIndicator.setBackgroundResource(R.drawable.circle_indicator_green);
        tvLastEvent.setText("✅ " + itemTitle + " [" + serial + "]");
        tvGateIcon.setText("✅");

        logConsole("✅ " + (isArabic() ? "قرار البوابة: PASS - " : "Gate decision: PASS - ") + itemTitle);
        SoundGenerator.playSound(miraGatesSettings.getSoundPass());

        mainHandler.removeCallbacks(resetUIRunnable);
        mainHandler.postDelayed(resetUIRunnable, UI_RESET_DELAY_MS);
    }

    private void updateUIForDeny(String itemTitle, String serial) {
        if (isDestroyed || !isAdded()) return;
        
        tvGateStatus.setText(isArabic() ? "🚨 إنذار" : "🚨 ALARM");
        tvGateStatus.setTextColor(Color.parseColor("#C62828"));
        statusIndicator.setBackgroundResource(R.drawable.circle_indicator_red);
        tvLastEvent.setText("🚨 " + itemTitle + " [" + serial + "]");
        tvGateIcon.setText("🚨");

        logConsole("🚨 " + (isArabic() ? "قرار البوابة: DENY ALARM - " : "Gate decision: DENY ALARM - ") + itemTitle);
        triggerAlarmEffects(itemTitle, serial);
    }

    private void syncLocalAllowedCache() {
        if (isDestroyed || gateExecutor == null || gateExecutor.isShutdown()) return;
        
        gateExecutor.execute(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(mContext);
                if (client == null) return;
                
                MiraApiClient.ApiResponse response = client.getWithAuth("/allowed-list");
                
                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray allowedArray = response.data.optJSONArray("allowed_epcs");
                    if (allowedArray != null) {
                        Set<String> latestAllowed = ConcurrentHashMap.newKeySet();
                        for (int i = 0; i < allowedArray.length(); i++) {
                            String epc = allowedArray.optString(i, "");
                            if (!epc.isEmpty()) {
                                latestAllowed.add(epc);
                            }
                        }
                        
                        localAllowedEpcCache.clear();
                        localAllowedEpcCache.addAll(latestAllowed);
                        
                        Log.d(TAG, "✅ Allowed cache synced: " + latestAllowed.size() + " EPCs");
                        
                        mainHandler.post(() -> {
                            if (!isDestroyed && isAdded()) {
                                logConsole("🔄 " + (isArabic() ? "تم تحديث القائمة المسموحة: " : "Allowed list updated: ") + latestAllowed.size());
                            }
                        });
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Sync allowed cache error: " + e.getMessage());
            }
        });
    }

    private void updateGateStatusOnServer(String status) {
    updateGateStatusOnServerForSpecificGate(currentGateId, status);
}

private void updateGateStatusOnServerForSpecificGate(String gateId, String status) {
    if (isDestroyed || gateExecutor == null) return;
    
    gateExecutor.execute(() -> {
        try {
            MiraApiClient client = MiraApiClient.getInstance(mContext);
            if (client == null) return;
            
            JSONObject payload = new JSONObject();
            payload.put("gate_id", gateId);
            payload.put("status", status);
            
            // استخدم heartbeat مع gate_id محدد
            client.heartbeat(status, 100);
            
            Log.d(TAG, "✅ Gate " + gateId + " status updated: " + status);
        } catch (Exception e) {
            Log.e(TAG, "Update gate status error: " + e.getMessage());
        }
    });
}

    private void logGateEventToServer(String epc, String decision, String itemTitle) {
    if (isDestroyed || gateExecutor == null) return;
    
    gateExecutor.execute(() -> {
        try {
            MiraApiClient client = MiraApiClient.getInstance(mContext);
            if (client == null) return;
            
            JSONObject payload = new JSONObject();
            payload.put("gate_id", currentGateId);
            payload.put("epc", epc);
            payload.put("decision", decision);
            payload.put("item_title", itemTitle);
            payload.put("timestamp", System.currentTimeMillis());
            
            client.postWithAuth("/gate-log", payload);
        } catch (Exception e) {
            Log.e(TAG, "Log gate event error: " + e.getMessage());
        }
    });
}


    private void triggerAlarmEffects(String itemTitle, String serial) {
        if (isDestroyed || !isAdded()) return;
        
        isAlarmActive = true;

        if (miraGatesSettings.isFlashEnabled() && overlayAlarm != null) {
            tvOverlayTitle.setText("🚨 " + itemTitle);
            tvOverlayDetails.setText((isArabic() ? "رقم القطعة / Serial: " : "Serial: ") + serial);
            overlayAlarm.setVisibility(View.VISIBLE);

            flashRunnable = new Runnable() {
                @Override
                public void run() {
                    if (isDestroyed || !isAlarmActive || overlayAlarm == null) return;
                    flashState = !flashState;
                    overlayAlarm.setBackgroundColor(flashState ? Color.parseColor("#D32F2F") : Color.parseColor("#000000"));
                    flashHandler.postDelayed(this, 250);
                }
            };
            flashHandler.post(flashRunnable);
        }

        soundLoopRunnable = new Runnable() {
            @Override
            public void run() {
                if (isDestroyed || !isAlarmActive) return;

                SoundGenerator.playSound(miraGatesSettings.getSoundAlarm());

                if (miraGatesSettings.isVibrationEnabled() && vibrator != null && vibrator.hasVibrator()) {
                    long[] pattern = {0, 400, 200, 400};
                    vibrator.vibrate(pattern, -1);
                }

                int alarmInterval = miraGatesSettings.getAlarmInterval();
                soundHandler.postDelayed(this, alarmInterval);
            }
        };
        soundHandler.post(soundLoopRunnable);
    }

    private void stopAlarmEffects() {
        isAlarmActive = false;
        
        if (flashHandler != null) {
            flashHandler.removeCallbacks(flashRunnable);
        }
        if (soundHandler != null) {
            soundHandler.removeCallbacks(soundLoopRunnable);
        }

        if (overlayAlarm != null) {
            overlayAlarm.setVisibility(View.GONE);
        }

        if (vibrator != null) {
            vibrator.cancel();
        }

        resetGateUI();
        logConsole(isArabic() ? "🔕 تم إيقاف التنبيه الصوتي والمرئي." : "🔕 Alarm stopped.");
    }

    private final Runnable resetUIRunnable = () -> {
        if (!isDestroyed && gateActive && isAdded() && !isAlarmActive) {
            resetGateUI();
        }
    };

    private void resetGateUI() {
        if (isDestroyed || !isAdded()) return;
        
        tvGateIcon.setText("🚪");
        tvGateStatus.setText(isArabic() ? "🛡️ مفعلة" : "🛡️ Armed");
        tvGateStatus.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.tab_indicator_text));
        statusIndicator.setBackgroundResource(R.drawable.circle_indicator_green);
    }

    private void updateUI() {
        if (isDestroyed || !isAdded()) return;
        
        if (gateActive) {
            tvGateStatus.setText(isArabic() ? "🛡️ مفعلة" : "🛡️ Armed");
            statusIndicator.setBackgroundResource(R.drawable.circle_indicator_green);
            btnArmGate.setEnabled(false);
            btnDisarmGate.setEnabled(true);
        } else {
            tvGateStatus.setText(isArabic() ? "🔒 معطلة" : "🔒 Disarmed");
            statusIndicator.setBackgroundResource(R.drawable.circle_indicator_red);
            btnArmGate.setEnabled(true);
            btnDisarmGate.setEnabled(false);
        }
    }

    private void updateCounters() {
        if (isDestroyed || !isAdded()) return;
        
        tvPassCount.setText(String.valueOf(passCount));
        tvDenyCount.setText(String.valueOf(denyCount));
        tvTotalCount.setText(String.valueOf(totalCount));
    }

    private void logConsole(String message) {
        if (isDestroyed) return;
        
        mainHandler.post(() -> {
            if (isDestroyed || !isAdded()) return;
            
            String currentTime = timeFormat.format(new Date());
            String logLine = "[" + currentTime + "] " + message + "\n";
            
            if (tvDebugConsole != null) {
                tvDebugConsole.append(logLine);
                if (scrollDebugLog != null) {
                    scrollDebugLog.post(() -> scrollDebugLog.fullScroll(View.FOCUS_DOWN));
                }
            }
            Log.d(TAG, message);
        });
    }

    @Override
    public void onPause() {
        super.onPause();
        if (gateActive) {
            stopContinuousRfidScan();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (gateActive) {
            startContinuousRfidScan();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        
        isDestroyed = true;
        
        stopAlarmEffects();
        stopContinuousRfidScan();
        stopRealtimeDbSync();
        
        if (mainHandler != null) mainHandler.removeCallbacksAndMessages(null);
        if (flashHandler != null) flashHandler.removeCallbacksAndMessages(null);
        if (soundHandler != null) soundHandler.removeCallbacksAndMessages(null);
        
        shutdownExecutors();
        cleanupResources();
        
        Log.d(TAG, "Fragment destroyed and resources cleaned");
    }

    private void shutdownExecutors() {
        if (gateExecutor != null && !gateExecutor.isShutdown()) {
            gateExecutor.shutdown();
            try {
                if (!gateExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    gateExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                gateExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        if (dbSyncScheduler != null && !dbSyncScheduler.isShutdown()) {
            dbSyncScheduler.shutdownNow();
        }
    }

    private void cleanupResources() {
        mContext = null;
        settingsManager = null;
        miraGatesSettings = null;
        vibrator = null;
        
        localAllowedEpcCache.clear();
        scanDebounceMap.clear();
        
        tvGateIcon = null;
        tvGateStatus = null;
        tvLastEvent = null;
        tvDebugConsole = null;
        tvPassCount = null;
        tvDenyCount = null;
        tvTotalCount = null;
        tvOverlayTitle = null;
        tvOverlayDetails = null;
        etManualEpc = null;
        statusIndicator = null;
        overlayAlarm = null;
        btnArmGate = null;
        btnDisarmGate = null;
        btnSubmitManual = null;
        btnScanBarcode = null;
        btnSimulateAllowed = null;
        btnSimulateDenied = null;
        btnStopAlarm = null;
        scrollDebugLog = null;
        
        gateActive = false;
        isAlarmActive = false;
    }
                            }
