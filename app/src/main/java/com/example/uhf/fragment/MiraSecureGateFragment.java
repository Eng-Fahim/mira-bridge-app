package com.example.uhf.fragment;

import android.content.Context;
import android.graphics.Color;
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
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.manager.MiraSettingsManager;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import com.rscja.deviceapi.entity.UHFTAGInfo;
import com.rscja.deviceapi.interfaces.IUHFInventoryCallback;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MiraSecureGateFragment extends Fragment {

    private static final String TAG = "MiraSecureGate";

    private UHFMainActivity mContext;
    private MiraSettingsManager settingsManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService gateExecutor = Executors.newFixedThreadPool(4);
    private ScheduledExecutorService dbSyncScheduler;

    // عناصر الواجهة
    private TextView tvGateIcon, tvGateStatus, tvLastEvent, tvDebugConsole;
    private TextView tvPassCount, tvDenyCount, tvTotalCount;
    private TextView tvOverlayTitle, tvOverlayDetails;
    private EditText etManualEpc;
    private View statusIndicator;
    private FrameLayout overlayAlarm;
    private Button btnArmGate, btnDisarmGate, btnSubmitManual, btnScanBarcode, btnSimulateAllowed, btnSimulateDenied, btnStopAlarm;
    private ScrollView scrollDebugLog;

    // حالة البوابة والعدادات
    private volatile boolean gateActive = false;
    private int passCount = 0;
    private int denyCount = 0;
    private int totalCount = 0;

    // محرك الوميض والصوت المتكرر
    private boolean isAlarmActive = false;
    private boolean flashState = false;
    private final Handler flashHandler = new Handler(Looper.getMainLooper());
    private Runnable flashRunnable;
    private Runnable soundLoopRunnable;

    // Cache الذاكرة السريعة لمنع التأخير (<5ms)
    private final Set<String> localAllowedEpcCache = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Long> scanDebounceMap = new ConcurrentHashMap<>();
    private static final long SCAN_COOL_DOWN_MS = 2500;

    private Vibrator vibrator;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    // 📷 مشغل مسح الكاميرا
    private final ActivityResultLauncher<ScanOptions> barcodeLauncher = registerForActivityResult(
            new ScanContract(),
            result -> {
                if (result.getContents() != null) {
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

        if (getActivity() instanceof UHFMainActivity) {
            mContext = (UHFMainActivity) getActivity();
            settingsManager = MiraSettingsManager.getInstance(mContext);
            vibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
        }

        initViews(view);
        setupClickListeners();
        updateUI();

        logConsole("🚀 تم تشغيل MIRA Secure Gate™ (وضع التنبيه المرئي والمسموع الشديد)");
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
            String manualCode = etManualEpc.getText().toString().trim();
            if (!manualCode.isEmpty()) {
                etManualEpc.setText("");
                processInputEpc(manualCode, "MANUAL");
            } else {
                Toast.makeText(getContext(), "يرجى كتابة رقم القطعة أولاً", Toast.LENGTH_SHORT).show();
            }
        });

        btnScanBarcode.setOnClickListener(v -> {
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
            String mockAllowedEpc = "E20041000000000000009999";
            localAllowedEpcCache.add(mockAllowedEpc);
            processInputEpc(mockAllowedEpc, "SIMULATION_ALLOWED");
        });

        btnSimulateDenied.setOnClickListener(v -> {
            String mockDeniedEpc = "E20041000000000000001111";
            localAllowedEpcCache.remove(mockDeniedEpc);
            processInputEpc(mockDeniedEpc, "SIMULATION_DENIED");
        });

        // زر إيقاف التنبيه
        btnStopAlarm.setOnClickListener(v -> stopAlarmEffects());
    }

    private void armGate() {
        gateActive = true;
        updateUI();
        logConsole("🛡️ تم تفعيل البوابة الأمنية.");

        syncLocalAllowedCache();
        startRealtimeDbSync();
        startContinuousRfidScan();
        updateGateStatusOnServer("armed");

        Toast.makeText(mContext, getString(R.string.gate_status_armed), Toast.LENGTH_SHORT).show();
    }

    private void disarmGate() {
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

        Toast.makeText(mContext, getString(R.string.gate_status_disarmed), Toast.LENGTH_SHORT).show();
    }

    private void startContinuousRfidScan() {
        if (mContext != null && mContext.mReader != null) {
            try {
                mContext.mReader.setPower(15);
                mContext.mReader.setInventoryCallback(new IUHFInventoryCallback() {
                    @Override
                    public void callback(UHFTAGInfo uhftagInfo) {
                        if (uhftagInfo != null && uhftagInfo.getEPC() != null) {
                            processInputEpc(uhftagInfo.getEPC(), "RFID_HW");
                        }
                    }
                });
                mContext.mReader.startInventoryTag();
            } catch (Exception e) {
                logConsole("⚠️ خطأ في القارئ المادي: " + e.getMessage());
            }
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
        dbSyncScheduler.scheduleAtFixedRate(this::syncLocalAllowedCache, 0, 5, TimeUnit.SECONDS);
    }

    private void stopRealtimeDbSync() {
        if (dbSyncScheduler != null && !dbSyncScheduler.isShutdown()) {
            dbSyncScheduler.shutdownNow();
            dbSyncScheduler = null;
        }
    }

    private void processInputEpc(String epc, String sourceType) {
        if (!gateActive) {
            Toast.makeText(getContext(), "قم بتفعيل البوابة أولاً!", Toast.LENGTH_SHORT).show();
            return;
        }

        if (epc == null || epc.trim().isEmpty()) return;

        final String cleanedEpc = epc.trim().toUpperCase();

        long currentTime = System.currentTimeMillis();
        Long lastTime = scanDebounceMap.get(cleanedEpc);

        if (lastTime != null && (currentTime - lastTime < SCAN_COOL_DOWN_MS)) {
            return;
        }
        scanDebounceMap.put(cleanedEpc, currentTime);

        mainHandler.post(() -> {
            totalCount++;
            updateCounters();
            String shortEpc = cleanedEpc.length() > 8 ? cleanedEpc.substring(cleanedEpc.length() - 8) : cleanedEpc;
            tvLastEvent.setText(getString(R.string.gate_status_verifying) + " [" + sourceType + ": " + shortEpc + "]");
        });

        boolean isLocallyAllowed = localAllowedEpcCache.contains(cleanedEpc);

        if (isLocallyAllowed) {
            mainHandler.post(() -> handleGateDecision(true, "قطعة مصرح بها (مباعة)", cleanedEpc));
        } else {
            gateExecutor.execute(() -> queryMiraGateServer(cleanedEpc));
        }
    }

    private void queryMiraGateServer(String epc) {
        try {
            String apiUrl = settingsManager.getString("mira_api_url", "https://ams.ibreg.org/wp-json/mira-gate/v1/authorize");
            String apiKey = settingsManager.getString("mira_api_key", "mira_gate_test071234567890abcdefghijklmnop");
            String gateId = settingsManager.getString("mira_gate_id", "secure_gate_01");

            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("X-MIRA-API-Key", apiKey);
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setDoOutput(true);

            JSONObject json = new JSONObject();
            json.put("epc", epc);
            json.put("gate_id", gateId);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.toString().getBytes("utf-8"));
            }

            int responseCode = conn.getResponseCode();

            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "utf-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) response.append(line);

                JSONObject responseJson = new JSONObject(response.toString());
                JSONObject decision = responseJson.optJSONObject("decision");
                JSONObject item = responseJson.optJSONObject("item");

                boolean allowed = decision != null && decision.optBoolean("allowed", false);
                String itemTitle = item != null ? item.optString("title", "قطعة مجوهرات") : "غير مسجلة بالمبيعات";
                String serial = item != null ? item.optString("serial", epc) : epc;

                if (allowed) {
                    localAllowedEpcCache.add(epc);
                } else {
                    localAllowedEpcCache.remove(epc);
                }

                mainHandler.post(() -> handleGateDecision(allowed, itemTitle, serial));
            } else {
                mainHandler.post(() -> handleGateDecision(false, "تنبيه - خطأ بالسيرفر", epc));
            }

        } catch (Exception e) {
            mainHandler.post(() -> handleGateDecision(false, "🚨 تنبيه - خروج غير مصرح", epc));
        }
    }

    private void handleGateDecision(boolean allowed, String itemTitle, String serial) {
        if (!isAdded()) return;

        if (allowed) {
            passCount++;
            updateCounters();

            tvGateStatus.setText(R.string.gate_status_passed);
            tvGateStatus.setTextColor(Color.parseColor("#2E7D32"));
            statusIndicator.setBackgroundResource(R.drawable.circle_indicator_green);
            tvLastEvent.setText("✅ " + itemTitle + " [" + serial + "]");
            tvGateIcon.setText("✅");

            logConsole("✅ قرار البوابة: PASS - " + itemTitle);

            if (mContext != null) {
                mContext.playSound(1);
            }

            mainHandler.removeCallbacks(resetUIRunnable);
            mainHandler.postDelayed(resetUIRunnable, 3000);

        } else {
            denyCount++;
            updateCounters();

            tvGateStatus.setText(R.string.gate_status_alarm);
            tvGateStatus.setTextColor(Color.parseColor("#C62828"));
            statusIndicator.setBackgroundResource(R.drawable.circle_indicator_red);
            tvLastEvent.setText("🚨 " + itemTitle + " [" + serial + "]");
            tvGateIcon.setText("🚨");

            logConsole("🚨 قرار البوابة: DENY ALARM - " + itemTitle);

            // 🚨 تفعيل مؤشرات الوميض والإنذار الصوتي الشديد
            triggerAlarmEffects(itemTitle, serial);
        }
    }

    // 🚨 تفعيل وميض الشاشة والصوت والاهتزاز الشديد
    private void triggerAlarmEffects(String itemTitle, String serial) {
        isAlarmActive = true;

        if (overlayAlarm != null) {
            tvOverlayTitle.setText("🚨 " + itemTitle);
            tvOverlayDetails.setText("رقم القطعة / Serial: " + serial);
            overlayAlarm.setVisibility(View.VISIBLE);
        }

        // 1. بدء أنيميشن الوميض الشديد بين الأحمر والأسود والأصفر
        flashRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isAlarmActive || overlayAlarm == null) return;
                flashState = !flashState;
                overlayAlarm.setBackgroundColor(flashState ? Color.parseColor("#D32F2F") : Color.parseColor("#000000"));
                flashHandler.postDelayed(this, 250);
            }
        };
        flashHandler.post(flashRunnable);

        // 2. تكرار تشغيل صوت الإنذار المرعب كل ثانيتين
        soundLoopRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isAlarmActive) return;
                if (mContext != null) {
                    mContext.playSound(2); // alarm_breach_security.mp3
                }
                if (vibrator != null && vibrator.hasVibrator()) {
                    long[] pattern = {0, 400, 200, 400};
                    vibrator.vibrate(pattern, -1);
                }
                flashHandler.postDelayed(this, 2200);
            }
        };
        flashHandler.post(soundLoopRunnable);
    }

    // 🔕 إيقاف مؤثرات الإنذار
    private void stopAlarmEffects() {
        isAlarmActive = false;
        flashHandler.removeCallbacks(flashRunnable);
        flashHandler.removeCallbacks(soundLoopRunnable);

        if (overlayAlarm != null) {
            overlayAlarm.setVisibility(View.GONE);
        }

        resetGateUI();
        logConsole("🔕 تم إيقاف التنبيه الصوتي والمرئي.");
    }

    private final Runnable resetUIRunnable = () -> {
        if (gateActive && isAdded() && !isAlarmActive) {
            resetGateUI();
        }
    };

    private void syncLocalAllowedCache() {
        try {
            String apiUrl = settingsManager.getString("mira_api_url", "https://ams.ibreg.org/wp-json/mira-gate/v1/allowed-list");
            String apiKey = settingsManager.getString("mira_api_key", "mira_gate_test071234567890abcdefghijklmnop");

            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("X-MIRA-API-Key", apiKey);
            conn.setConnectTimeout(2000);

            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) response.append(line);

                JSONObject json = new JSONObject(response.toString());
                JSONArray allowedArray = json.optJSONArray("allowed_epcs");
                if (allowedArray != null) {
                    Set<String> latestAllowed = ConcurrentHashMap.newKeySet();
                    for (int i = 0; i < allowedArray.length(); i++) {
                        latestAllowed.add(allowedArray.getString(i));
                    }
                    localAllowedEpcCache.clear();
                    localAllowedEpcCache.addAll(latestAllowed);
                }
            }
        } catch (Exception ignored) {}
    }

    private void resetGateUI() {
        tvGateIcon.setText("🚪");
        tvGateStatus.setText(R.string.gate_status_armed);
        tvGateStatus.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.tab_indicator_text));
        statusIndicator.setBackgroundResource(R.drawable.circle_indicator_green);
    }

    private void updateUI() {
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
        tvPassCount.setText(String.valueOf(passCount));
        tvDenyCount.setText(String.valueOf(denyCount));
        tvTotalCount.setText(String.valueOf(totalCount));
    }

    private void updateGateStatusOnServer(String status) {
        gateExecutor.execute(() -> {
            try {
                String apiKey = settingsManager.getString("mira_api_key", "mira_gate_test071234567890abcdefghijklmnop");
                String gateId = settingsManager.getString("mira_gate_id", "secure_gate_01");

                URL url = new URL("https://ams.ibreg.org/wp-json/mira-gate/v1/gate-status");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-MIRA-API-Key", apiKey);
                conn.setConnectTimeout(2000);
                conn.setDoOutput(true);

                JSONObject json = new JSONObject();
                json.put("gate_id", gateId);
                json.put("status", status);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.toString().getBytes("utf-8"));
                }
                conn.getResponseCode();
            } catch (Exception ignored) {}
        });
    }

    private void logConsole(String message) {
        mainHandler.post(() -> {
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
    public void onDestroy() {
        super.onDestroy();
        stopAlarmEffects();
        if (gateActive) {
            disarmGate();
        }
        stopRealtimeDbSync();
        gateExecutor.shutdown();
    }
}
