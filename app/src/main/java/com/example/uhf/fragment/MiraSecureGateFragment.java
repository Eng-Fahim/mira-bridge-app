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
    private EditText etManualEpc;
    private View statusIndicator;
    private Button btnArmGate, btnDisarmGate, btnSubmitManual, btnScanBarcode, btnSimulateAllowed, btnSimulateDenied;
    private ScrollView scrollDebugLog;

    // حالة البوابة والعدادات
    private volatile boolean gateActive = false;
    private int passCount = 0;
    private int denyCount = 0;
    private int totalCount = 0;

    // Cache الذاكرة السريعة لمنع التأخير (<5ms)
    private final Set<String> localAllowedEpcCache = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Long> scanDebounceMap = new ConcurrentHashMap<>();
    private static final long SCAN_COOL_DOWN_MS = 2500; // مهلة منع تكرار نفس التاق

    private Vibrator vibrator;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    // 📷 مشغل مسح الكاميرا للباركود/QR
    private final ActivityResultLauncher<ScanOptions> barcodeLauncher = registerForActivityResult(
            new ScanContract(),
            result -> {
                if (result.getContents() != null) {
                    String scannedBarcode = result.getContents().trim();
                    logConsole("📷 [مسح الكاميرا]: تم إلتقاط الباركود: " + scannedBarcode);
                    processInputEpc(scannedBarcode, "CAMERA");
                } else {
                    logConsole("📷 تم إلغاء مسح الكاميرا.");
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

        logConsole("🚀 تم تشغيل شاشة MIRA Secure Gate™");
        logConsole("ℹ️ الدعم ثلاثي الوسائط جاهز: (RFID + الكاميرا + الإدخال اليدوي).");
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
    }

    private void setupClickListeners() {
        btnArmGate.setOnClickListener(v -> armGate());
        btnDisarmGate.setOnClickListener(v -> disarmGate());

        // 1️⃣ الإدخال اليدوي
        btnSubmitManual.setOnClickListener(v -> {
            String manualCode = etManualEpc.getText().toString().trim();
            if (!manualCode.isEmpty()) {
                etManualEpc.setText("");
                processInputEpc(manualCode, "MANUAL");
            } else {
                Toast.makeText(getContext(), "يرجى كتابة رقم القطعة أولاً", Toast.LENGTH_SHORT).show();
            }
        });

        // 2️⃣ مسح الباركود للكاميرا
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

        // 3️⃣ أزرار المحاكاة السريعة
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
    }

    private void armGate() {
        gateActive = true;
        updateUI();

        logConsole("🛡️ تم تفعيل البوابة الأمنية.");
        
        // 1. المزامنة الفورية الأولى عند التفعيل
        syncLocalAllowedCache();
        
        // 2. تفعيل المزامنة الدورية المستمرة (كل 5 ثواني) لتأثر البوابة بأي تغيير في السيرفر فوراً
        startRealtimeDbSync();

        // 3. تشغيل مسح الـ RFID المادي
        startContinuousRfidScan();
        
        updateGateStatusOnServer("armed");
        Toast.makeText(mContext, getString(R.string.gate_status_armed), Toast.LENGTH_SHORT).show();
    }

    private void disarmGate() {
        gateActive = false;
        
        stopContinuousRfidScan();
        stopRealtimeDbSync();

        passCount = 0;
        denyCount = 0;
        totalCount = 0;
        updateCounters();

        scanDebounceMap.clear();
        updateUI();

        logConsole("🔒 تم إيقاف تفعيل البوابة.");
        updateGateStatusOnServer("offline");

        Toast.makeText(mContext, getString(R.string.gate_status_disarmed), Toast.LENGTH_SHORT).show();
    }

    // 📡 بدء المسح المادي لقارئ C72 (RFID Input)
    private void startContinuousRfidScan() {
        if (mContext != null && mContext.mReader != null) {
            try {
                mContext.mReader.setPower(15); // خفض القوة لحصر النطاق على الباب
                mContext.mReader.setInventoryCallback(new IUHFInventoryCallback() {
                    @Override
                    public void callback(UHFTAGInfo uhftagInfo) {
                        if (uhftagInfo != null && uhftagInfo.getEPC() != null) {
                            processInputEpc(uhftagInfo.getEPC(), "RFID_HW");
                        }
                    }
                });
                mContext.mReader.startInventoryTag();
                logConsole("📡 القارئ المادي متصل ويعمل بمدى 15dBm");
            } catch (Exception e) {
                logConsole("⚠️ خطأ في القارئ المادي: " + e.getMessage());
            }
        } else {
            logConsole("ℹ️ يعمل التطبيق بالوضع الافتراضي (في انتظار توصيل القارئ C72).");
        }
    }

    private void stopContinuousRfidScan() {
        if (mContext != null && mContext.mReader != null) {
            try {
                mContext.mReader.stopInventory();
                mContext.mReader.setInventoryCallback(null);
                mContext.mReader.setPower(30); // إعادة القوة لوضعها الأقصى
            } catch (Exception e) {
                logConsole("إيقاف القارئ: " + e.getMessage());
            }
        }
    }

    // 🔄 المزامنة الدورية اللحظية من قاعدة البيانات
    private void startRealtimeDbSync() {
        stopRealtimeDbSync();
        dbSyncScheduler = Executors.newSingleThreadScheduledExecutor();
        // إعادة الاستعلام المباشر كل 5 ثوانٍ لجلب التغيرات في المبيعات والقطع المسروقة
        dbSyncScheduler.scheduleAtFixedRate(this::syncLocalAllowedCache, 0, 5, TimeUnit.SECONDS);
    }

    private void stopRealtimeDbSync() {
        if (dbSyncScheduler != null && !dbSyncScheduler.isShutdown()) {
            dbSyncScheduler.shutdownNow();
            dbSyncScheduler = null;
        }
    }

    // 🎯 النقطة الموحدة لمعالجة المدخلات (RFID / كاميرا / يدوي)
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
            return; // تصفية التكرار اللحظي
        }
        scanDebounceMap.put(cleanedEpc, currentTime);

        mainHandler.post(() -> {
            totalCount++;
            updateCounters();
            String shortEpc = cleanedEpc.length() > 8 ? cleanedEpc.substring(cleanedEpc.length() - 8) : cleanedEpc;
            tvLastEvent.setText(getString(R.string.gate_status_verifying) + " [" + sourceType + ": " + shortEpc + "]");
        });

        logConsole("📥 [" + sourceType + "] فحص القطعة: " + cleanedEpc);

        // 1️⃣ فحص سريع جدًا في الكاش المحلي (أقل من 5ms)
        boolean isLocallyAllowed = localAllowedEpcCache.contains(cleanedEpc);

        if (isLocallyAllowed) {
            logConsole("⚡ [LOCAL CACHE MATCH] القطعة مصرح بها في الكاش المحلي.");
            mainHandler.post(() -> handleGateDecision(true, "قطعة مصرح بها (مباعة)", cleanedEpc));
        } else {
            // 2️⃣ استعلام مباشر لحظي مع السيرفر وقاعدة البيانات للمؤكد التام
            logConsole("🌐 [REALTIME DB QUERY] جاري الاستعلام المباشر من قاعدة البيانات...");
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
                    localAllowedEpcCache.add(epc); // تحديث الكاش بالقطعة المسموحة حديثًا
                } else {
                    localAllowedEpcCache.remove(epc); // إزالة القطعة إذا أصبحت مسروقة/غير مصرحة
                }

                mainHandler.post(() -> handleGateDecision(allowed, itemTitle, serial));
            } else {
                logConsole("⚠️ استجابة السيرفر (" + responseCode + ") -> تفعيل الإنذار الوقائي");
                mainHandler.post(() -> handleGateDecision(false, "تنبيه - خطأ بالسيرفر", epc));
            }

        } catch (Exception e) {
            logConsole("❌ تعذر الاتصال بالشبكة: " + e.getMessage() + " -> إنذار أمني!");
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

            logConsole("✅ قرار البوابة: PASS (مصرح) - " + itemTitle);

            if (mContext != null) {
                mContext.playSound(1); // beep_success.mp3
            }

        } else {
            denyCount++;
            updateCounters();

            tvGateStatus.setText(R.string.gate_status_alarm);
            tvGateStatus.setTextColor(Color.parseColor("#C62828"));
            statusIndicator.setBackgroundResource(R.drawable.circle_indicator_red);
            tvLastEvent.setText("🚨 " + itemTitle + " [" + serial + "]");
            tvGateIcon.setText("🚨");

            logConsole("🚨 قرار البوابة: DENY ALARM (غير مصرح/مسروق) - " + itemTitle);

            triggerSecurityAlarm();
        }

        mainHandler.removeCallbacks(resetUIRunnable);
        mainHandler.postDelayed(resetUIRunnable, 3000);
    }

    private final Runnable resetUIRunnable = () -> {
        if (gateActive && isAdded()) {
            resetGateUI();
        }
    };

    private void triggerSecurityAlarm() {
        if (mContext != null) {
            mContext.playSound(2); // alarm_sold_item.mp3
        }

        if (vibrator != null && vibrator.hasVibrator()) {
            long[] pattern = {0, 300, 100, 300, 100, 400};
            vibrator.vibrate(pattern, -1);
        }
    }

    // 🔄 جلب المزامنة اللحظية للقطع المسموحة من السيرفر
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
                    
                    // تحديث الذاكرة المحلية بالقائمة الجديدة تمامًا
                    localAllowedEpcCache.clear();
                    localAllowedEpcCache.addAll(latestAllowed);
                    
                    logConsole("🔄 [DB Sync]: تم تحديث الذاكرة بـ (" + localAllowedEpcCache.size() + ") قطعة مسموحة.");
                }
            }
        } catch (Exception e) {
            // تجاهل أخطاء الشبكة أثناء البولينج لتفادي الضجيج
        }
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
            } catch (Exception e) {
                logConsole("خطأ تحديث حالة البوابة: " + e.getMessage());
            }
        });
    }

    // 📺 شاشة تسجيل السجلات المباشرة المدمجة (Console)
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
        if (gateActive) {
            disarmGate();
        }
        stopRealtimeDbSync();
        gateExecutor.shutdown();
    }
}
