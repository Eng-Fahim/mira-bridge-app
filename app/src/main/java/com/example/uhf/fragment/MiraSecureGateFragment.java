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
    
    // Handlers مخصصة لتجنب التداخل
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler flashHandler = new Handler(Looper.getMainLooper());
    private final Handler soundHandler = new Handler(Looper.getMainLooper());
    
    private ExecutorService gateExecutor;
    private ScheduledExecutorService dbSyncScheduler;

    // عناصر الواجهة
    private TextView tvGateIcon, tvGateStatus, tvLastEvent, tvDebugConsole;
    private TextView tvPassCount, tvDenyCount, tvTotalCount;
    private TextView tvOverlayTitle, tvOverlayDetails;
    private EditText etManualEpc;
    private View statusIndicator;
    private FrameLayout overlayAlarm;
    private Button btnArmGate, btnDisarmGate, btnSubmitManual, btnScanBarcode;
    private Button btnSimulateAllowed, btnSimulateDenied, btnStopAlarm;
    private ScrollView scrollDebugLog;

    // حالة البوابة والعدادات
    private volatile boolean gateActive = false;
    private volatile boolean isAlarmActive = false;
    private volatile boolean isDestroyed = false;
    private int passCount = 0;
    private int denyCount = 0;
    private int totalCount = 0;

    // محركات التنبيه
    private boolean flashState = false;
    private Runnable flashRunnable;
    private Runnable soundLoopRunnable;

    // Caches وخرائط التبريد
    private final Set<String> localAllowedEpcCache = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Long> scanDebounceMap = new ConcurrentHashMap<>();

    private Vibrator vibrator;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    // مشغل الكاميرا
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
            vibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
        }
        gateExecutor = Executors.newFixedThreadPool(4);
        isDestroyed = false;
    }

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
    }

    private void setupClickListeners() {
        btnArmGate.setOnClickListener(v -> armGate());
        btnDisarmGate.setOnClickListener(v -> disarmGate());

        btnSubmitManual.setOnClickListener(v -> {
            if (isDestroyed) return;
            String manualCode = etManualEpc.getText().toString().trim();
            if (!manualCode.isEmpty()) {
                etManualEpc.setText("");
                processInputEpc(manualCode, "MANUAL");
            } else {
                Toast.makeText(getContext(), "يرجى كتابة رقم القطعة أولاً", Toast.LENGTH_SHORT).show();
            }
        });

        btnScanBarcode.setOnClickListener(v -> {
            if (isDestroyed) return;
            if (!gateActive) {
                Toast.makeText(getContext(), "قم بتفعيل البوابة أولاً!", Toast.LENGTH_SHORT).show();
                return;
            }
            ScanOptions options = new ScanOptions();
            options.setPrompt("وجه الكاميرا نحو باركود أو QR القطعة");
            options.setBeepEnabled(false);
            options.setOrientationLocked(true);
            options.setCaptureActivity(com.journeyapps.barcodescanner.CaptureActivity.class);
            barcodeLauncher.launch(options);
        });

        btnSimulateAllowed.setOnClickListener(v -> {
            if (isDestroyed) return;
            String mockAllowedEpc = "E20041000000000000009999";
            localAllowedEpcCache.add(mockAllowedEpc);
            processInputEpc(mockAllowedEpc, "SIMULATION_ALLOWED");
        });

        btnSimulateDenied.setOnClickListener(v -> {
            if (isDestroyed) return;
            String mockDeniedEpc = "E20041000000000000001111";
            localAllowedEpcCache.remove(mockDeniedEpc);
            processInputEpc(mockDeniedEpc, "SIMULATION_DENIED");
        });

        btnStopAlarm.setOnClickListener(v -> stopAlarmEffects());
    }

    private void armGate() {
        if (isDestroyed) return;
        
        gateActive = true;
        updateUI();
        logConsole("🛡️ تم تفعيل البوابة الأمنية.");

        syncLocalAllowedCache();
        startRealtimeDbSync();
        startContinuousRfidScan();
        updateGateStatusOnServer("armed");

        if (isAdded()) {
            Toast.makeText(requireContext(), getString(R.string.gate_status_armed), Toast.LENGTH_SHORT).show();
        }
    }

    private void disarmGate() {
        if (isDestroyed) return;
        
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

        logConsole("🔒 تم إيقاف البوابة.");
        updateGateStatusOnServer("offline");

        if (isAdded()) {
            Toast.makeText(requireContext(), getString(R.string.gate_status_disarmed), Toast.LENGTH_SHORT).show();
        }
    }

    private void startContinuousRfidScan() {
        if (isDestroyed || mContext == null || mContext.mReader == null) {
            logConsole("⚠️ قارئ RFID غير متوفر");
            return;
        }
        
        try {
            int rfidPower = miraGatesSettings.getRfidPower();
            mContext.mReader.setPower(rfidPower);
            logConsole("📡 قوة RFID: " + rfidPower + " dBm");

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
            logConsole("⚠️ خطأ في القارئ المادي: " + e.getMessage());
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
        
        // تنظيف الخريطة الدورية المباشرة لتجنب التراكم
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
            tvLastEvent.setText(getString(R.string.gate_status_verifying) + " [" + sourceType + ": " + shortEpc + "]");
        });

        boolean isLocallyAllowed = localAllowedEpcCache.contains(cleanedEpc);

        if (isLocallyAllowed) {
            mainHandler.post(() -> handleGateDecision(true, "قطعة مصرح بها (مباعة)", cleanedEpc));
        } else {
            queryMiraGateServer(cleanedEpc);
        }
    }

    // تنظيف العناصر القديمة من خريطة التبريد
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
                    mainHandler.post(() -> handleGateDecision(false, "🚨 خطأ أوفلاين / لا يوجد اتصال", epc));
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
                    String itemTitle = item != null ? item.optString("title", "قطعة مجوهرات") : "غير مسجلة";
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
                    mainHandler.post(() -> handleGateDecision(false, "🚨 خروج غير مصرح - سيرفر", epc));
                }
            } catch (TimeoutException e) {
                Log.e(TAG, "Query gate timeout: " + e.getMessage());
                if (future != null) future.cancel(true);
                mainHandler.post(() -> handleGateDecision(false, "⚠️ انقطاع اتصال (Timeout) - مرفوض أمنياً", epc));
            } catch (Exception e) {
                Log.e(TAG, "Query gate error: " + e.getMessage());
                if (future != null) future.cancel(true);
                mainHandler.post(() -> handleGateDecision(false, "🚨 خروج غير مصرح (أوفلاين)", epc));
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
        
        tvGateStatus.setText(R.string.gate_status_passed);
        tvGateStatus.setTextColor(Color.parseColor("#2E7D32"));
        statusIndicator.setBackgroundResource(R.drawable.circle_indicator_green);
        tvLastEvent.setText("✅ " + itemTitle + " [" + serial + "]");
        tvGateIcon.setText("✅");

        logConsole("✅ قرار البوابة: PASS - " + itemTitle);
        SoundGenerator.playSound(miraGatesSettings.getSoundPass());

        mainHandler.removeCallbacks(resetUIRunnable);
        mainHandler.postDelayed(resetUIRunnable, UI_RESET_DELAY_MS);
    }

    private void updateUIForDeny(String itemTitle, String serial) {
        if (isDestroyed || !isAdded()) return;
        
        tvGateStatus.setText(R.string.gate_status_alarm);
        tvGateStatus.setTextColor(Color.parseColor("#C62828"));
        statusIndicator.setBackgroundResource(R.drawable.circle_indicator_red);
        tvLastEvent.setText("🚨 " + itemTitle + " [" + serial + "]");
        tvGateIcon.setText("🚨");

        logConsole("🚨 قرار البوابة: DENY ALARM - " + itemTitle);
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
                                logConsole("🔄 تم تحديث القائمة المسموحة: " + latestAllowed.size() + " عنصر");
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
        if (isDestroyed || gateExecutor == null || gateExecutor.isShutdown()) return;
        
        gateExecutor.execute(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(mContext);
                if (client == null) return;
                
                client.heartbeat(status, -1);
                Log.d(TAG, "✅ Gate status updated: " + status);
            } catch (Exception e) {
                Log.e(TAG, "Update gate status error: " + e.getMessage());
            }
        });
    }

    private void logGateEventToServer(String epc, String decision, String itemTitle) {
        if (isDestroyed || gateExecutor == null || gateExecutor.isShutdown()) return;
        
        gateExecutor.execute(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(mContext);
                if (client == null) return;
                
                JSONObject payload = new JSONObject();
                payload.put("epc", epc);
                payload.put("decision", decision);
                payload.put("item_title", itemTitle);
                payload.put("timestamp", System.currentTimeMillis());
                
                MiraApiClient.ApiResponse response = client.postWithAuth("/gate-log", payload);
                
                if (response != null && response.isSuccess) {
                    Log.d(TAG, "✅ Gate event logged: " + decision + " - " + epc);
                } else {
                    Log.e(TAG, "❌ Failed to log gate event");
                }
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
            tvOverlayDetails.setText("رقم القطعة / Serial: " + serial);
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
        logConsole("🔕 تم إيقاف التنبيه الصوتي والمرئي.");
    }

    private final Runnable resetUIRunnable = () -> {
        if (!isDestroyed && gateActive && isAdded() && !isAlarmActive) {
            resetGateUI();
        }
    };

    private void resetGateUI() {
        if (isDestroyed || !isAdded()) return;
        
        tvGateIcon.setText("🚪");
        tvGateStatus.setText(R.string.gate_status_armed);
        tvGateStatus.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.tab_indicator_text));
        statusIndicator.setBackgroundResource(R.drawable.circle_indicator_green);
    }

    private void updateUI() {
        if (isDestroyed || !isAdded()) return;
        
        if (gateActive) {
            tvGateStatus.setText(R.string.gate_status_armed);
            statusIndicator.setBackgroundResource(R.drawable.circle_indicator_green);
            btnArmGate.setEnabled(false);
            btnDisarmGate.setEnabled(true);
        } else {
            tvGateStatus.setText(R.string.gate_status_disarmed);
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
