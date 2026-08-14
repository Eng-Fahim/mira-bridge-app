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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.manager.MiraSettingsManager;
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

public class MiraSecureGateFragment extends Fragment {

    private static final String TAG = "MiraSecureGate";

    private UHFMainActivity mContext;
    private MiraSettingsManager settingsManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService gateExecutor = Executors.newFixedThreadPool(4);

    // عناصر الواجهة
    private TextView tvGateIcon, tvGateStatus, tvLastEvent, tvDebugConsole;
    private TextView tvPassCount, tvDenyCount, tvTotalCount;
    private View statusIndicator;
    private Button btnArmGate, btnDisarmGate, btnSimulateAllowed, btnSimulateDenied;
    private ScrollView scrollDebugLog;

    // حالة البوابة والعدادات
    private volatile boolean gateActive = false;
    private int passCount = 0;
    private int denyCount = 0;
    private int totalCount = 0;

    // Cache محلي للتحقق السريع
    private final Set<String> localAllowedEpcCache = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Long> scanDebounceMap = new ConcurrentHashMap<>();
    private static final long SCAN_COOL_DOWN_MS = 2500;

    private Vibrator vibrator;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

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

        logConsole("🚀 تم تشغيل شاشة MIRA Secure Gate");
        logConsole("ℹ️ وضع المحاكاة جاهز للاختبار بدون هاردوير.");
    }

    private void initViews(View view) {
        tvGateIcon = view.findViewById(R.id.tvGateIcon);
        tvGateStatus = view.findViewById(R.id.tvGateStatus);
        tvLastEvent = view.findViewById(R.id.tvLastEvent);
        tvPassCount = view.findViewById(R.id.tvPassCount);
        tvDenyCount = view.findViewById(R.id.tvDenyCount);
        tvTotalCount = view.findViewById(R.id.tvTotalCount);
        statusIndicator = view.findViewById(R.id.statusIndicator);
        btnArmGate = view.findViewById(R.id.btnArmGate);
        btnDisarmGate = view.findViewById(R.id.btnDisarmGate);
        btnSimulateAllowed = view.findViewById(R.id.btnSimulateAllowed);
        btnSimulateDenied = view.findViewById(R.id.btnSimulateDenied);
        tvDebugConsole = view.findViewById(R.id.tvDebugConsole);
        scrollDebugLog = view.findViewById(R.id.scrollDebugLog);
    }

    private void setupClickListeners() {
        btnArmGate.setOnClickListener(v -> armGate());
        btnDisarmGate.setOnClickListener(v -> disarmGate());

        // أزرار المحاكاة
        btnSimulateAllowed.setOnClickListener(v -> {
            if (!gateActive) {
                Toast.makeText(getContext(), "قم بتفعيل البوابة أولاً!", Toast.LENGTH_SHORT).show();
                return;
            }
            // إضافة تاق وهمي مصرح به للكاش واختباره
            String mockAllowedEpc = "E20041000000000000009999";
            localAllowedEpcCache.add(mockAllowedEpc);
            logConsole("🧪 [محاكاة]: تمرير تاق مصرح: " + mockAllowedEpc);
            processEpcString(mockAllowedEpc);
        });

        btnSimulateDenied.setOnClickListener(v -> {
            if (!gateActive) {
                Toast.makeText(getContext(), "قم بتفعيل البوابة أولاً!", Toast.LENGTH_SHORT).show();
                return;
            }
            // تاق غير مصرح به
            String mockDeniedEpc = "E20041000000000000001111";
            logConsole("🧪 [محاكاة]: تمرير تاق غير مصرح: " + mockDeniedEpc);
            processEpcString(mockDeniedEpc);
        });
    }

    private void armGate() {
        gateActive = true;
        updateUI();

        logConsole("🛡️ تم تفعيل البوابة الأمنية.");
        syncLocalAllowedCache();
        startContinuousScan();
        updateGateStatusOnServer("armed");

        Toast.makeText(mContext, getString(R.string.gate_status_armed), Toast.LENGTH_SHORT).show();
    }

    private void disarmGate() {
        gateActive = false;
        stopContinuousScan();

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

    private void startContinuousScan() {
        if (mContext != null && mContext.mReader != null) {
            try {
                mContext.mReader.setPower(15); // خفض المدى للبوابة
                mContext.mReader.setInventoryCallback(new IUHFInventoryCallback() {
                    @Override
                    public void callback(UHFTAGInfo uhftagInfo) {
                        if (uhftagInfo != null && uhftagInfo.getEPC() != null) {
                            processEpcString(uhftagInfo.getEPC());
                        }
                    }
                });
                mContext.mReader.startInventoryTag();
                logConsole("📡 القارئ المادي متصل ويعمل بمدى 15dBm");
            } catch (Exception e) {
                logConsole("⚠️ تعذر الاتصال بالقارئ المادي (في انتظار الجهاز): " + e.getMessage());
            }
        } else {
            logConsole("ℹ️ يعمل التطبيق في وضع المحاكاة بدون قارئ C72.");
        }
    }

    private void stopContinuousScan() {
        if (mContext != null && mContext.mReader != null) {
            try {
                mContext.mReader.stopInventory();
                mContext.mReader.setInventoryCallback(null);
                mContext.mReader.setPower(30); // إعادة القوة للوضع المعتاد
            } catch (Exception e) {
                logConsole("إيقاف القارئ: " + e.getMessage());
            }
        }
    }

    private void processEpcString(String epc) {
        if (!gateActive || epc == null || epc.trim().isEmpty()) return;

        long currentTime = System.currentTimeMillis();
        Long lastTime = scanDebounceMap.get(epc);

        if (lastTime != null && (currentTime - lastTime < SCAN_COOL_DOWN_MS)) {
            return; // تصفية القراءات المتكررة
        }
        scanDebounceMap.put(epc, currentTime);

        mainHandler.post(() -> {
            totalCount++;
            updateCounters();
            String shortEpc = epc.length() > 8 ? epc.substring(epc.length() - 8) : epc;
            tvLastEvent.setText(getString(R.string.gate_status_verifying) + " (" + shortEpc + ")");
        });

        // ⚡ فحص محلي سريع في أقل من 5ms
        boolean isLocallyAllowed = localAllowedEpcCache.contains(epc);

        if (isLocallyAllowed) {
            logConsole("⚡ [LOCAL CACHE MATCH] التاق مصرح به محلياً: " + epc);
            mainHandler.post(() -> handleGateDecision(true, "قطعة مصرح بها (مباعة)", epc));
        } else {
            logConsole("🌐 [SERVER QUERY] جاري الاستعلام من السيرفر عن: " + epc);
            gateExecutor.execute(() -> queryMiraGateServer(epc));
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
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            conn.setDoOutput(true);

            JSONObject json = new JSONObject();
            json.put("epc", epc);
            json.put("gate_id", gateId);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.toString().getBytes("utf-8"));
            }

            int responseCode = conn.getResponseCode();
            logConsole("🌐 Server Status Code: " + responseCode);

            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "utf-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) response.append(line);

                JSONObject responseJson = new JSONObject(response.toString());
                JSONObject decision = responseJson.optJSONObject("decision");
                JSONObject item = responseJson.optJSONObject("item");

                boolean allowed = decision != null && decision.optBoolean("allowed", false);
                String itemTitle = item != null ? item.optString("title", "مجواهرات غير مسجلة") : "قطعة غير معروفة";
                String serial = item != null ? item.optString("serial", epc) : epc;

                if (allowed) {
                    localAllowedEpcCache.add(epc);
                }

                mainHandler.post(() -> handleGateDecision(allowed, itemTitle, serial));
            } else {
                logConsole("⚠️ السيرفر أعاد استجابة غير 200 -> تفعيل إنذار احترازي");
                mainHandler.post(() -> handleGateDecision(false, "تنبيه أمني (استجابة سيرفر)", epc));
            }

        } catch (Exception e) {
            logConsole("❌ خطأ بالشبكة: " + e.getMessage() + " -> إنذار أمان محلي!");
            mainHandler.post(() -> handleGateDecision(false, "🚨 تنبيه أمني - خروج غير مصرح", epc));
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
                mContext.playSound(1); // صوت النحاح
            }

        } else {
            denyCount++;
            updateCounters();

            tvGateStatus.setText(R.string.gate_status_alarm);
            tvGateStatus.setTextColor(Color.parseColor("#C62828"));
            statusIndicator.setBackgroundResource(R.drawable.circle_indicator_red);
            tvLastEvent.setText("🚨 " + itemTitle + " [" + serial + "]");
            tvGateIcon.setText("🚨");

            logConsole("🚨 قرار البوابة: DENY ALARM - " + itemTitle);

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

    private void syncLocalAllowedCache() {
        gateExecutor.execute(() -> {
            try {
                logConsole("🔄 جاري مزامنة القائمة المصرحة من السيرفر...");
                String apiUrl = settingsManager.getString("mira_api_url", "https://ams.ibreg.org/wp-json/mira-gate/v1/allowed-list");
                String apiKey = settingsManager.getString("mira_api_key", "mira_gate_test071234567890abcdefghijklmnop");

                URL url = new URL(apiUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("X-MIRA-API-Key", apiKey);
                conn.setConnectTimeout(3000);

                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) response.append(line);

                    JSONObject json = new JSONObject(response.toString());
                    JSONArray allowedArray = json.optJSONArray("allowed_epcs");
                    if (allowedArray != null) {
                        localAllowedEpcCache.clear();
                        for (int i = 0; i < allowedArray.length(); i++) {
                            localAllowedEpcCache.add(allowedArray.getString(i));
                        }
                        logConsole("✅ تم تحميل " + localAllowedEpcCache.size() + " قطعة مصرحة في الكاش السريع.");
                    }
                } else {
                    logConsole("⚠️ فشل جلب الكاش من السيرفر HTTP " + conn.getResponseCode());
                }
            } catch (Exception e) {
                logConsole("❌ تعذر المزامنة الحالية للكاش: " + e.getMessage());
            }
        });
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
                conn.setConnectTimeout(3000);
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

    // 📺 ميزة شاشة سجلات الأخطاء المباشرة المدمجة داخل الواجهة (بديل Logcat)
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
        gateExecutor.shutdown();
    }
}
