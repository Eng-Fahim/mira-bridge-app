
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MIRA Secure Gate™ - Enterprise Security Module
 * 
 * الميزات المتقدمة:
 * - التحقق المحلي بالذاكرة الفائقة (Local In-Memory Cache Verification) لزمن استجابة < 10ms
 * - معالجة متوازية خفيفة الوزن بأحواض الخيوط (ExecutorService ThreadPool)
 * - دعم كامل لنظام الثيمات، اللغات، والاهتزاز الأمني القوي.
 */
public class MiraSecureGateFragment extends Fragment {

    private static final String TAG = "MiraSecureGate";

    private UHFMainActivity mContext;
    private MiraSettingsManager settingsManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // Executor للعمليات الأمنية في الخلفية لمنع خنق واجهة المستخدم
    private final ExecutorService gateExecutor = Executors.newFixedThreadPool(4);

    // 🟢 عناصر الواجهة
    private TextView tvGateIcon, tvGateStatus, tvLastEvent;
    private TextView tvPassCount, tvDenyCount, tvTotalCount;
    private View statusIndicator;
    private Button btnArmGate, btnDisarmGate;

    // 🟢 حالة البوابة والعدادات
    private volatile boolean gateActive = false;
    private int passCount = 0;
    private int denyCount = 0;
    private int totalCount = 0;

    // 🟢 ذاكرة الكاش السريعة جداً لمنع التأخير واستعلامات الشبكة المعطلة للإنذار
    // تحتوي على التاقات المسموح لها بالخروج (المباعة أو التي تحت الإذن)
    private final Set<String> localAllowedEpcCache = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Long> scanDebounceMap = new ConcurrentHashMap<>();
    private static final long SCAN_COOL_DOWN_MS = 2500; // فترة تهدئة التاق المكرر

    private Vibrator vibrator;

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
    }

    private void setupClickListeners() {
        btnArmGate.setOnClickListener(v -> armGate());
        btnDisarmGate.setOnClickListener(v -> disarmGate());
    }

    // ============================================
    // 🟢 تفعيل البوابة والأمن
    // ============================================
    private void armGate() {
        gateActive = true;
        updateUI();
        
        // 1. مزامنة ذاكرة التصاريح المحلية فوراً في الخلفية
        syncLocalAllowedCache();

        // 2. تشغيل الماسح الضوئي المستمر
        startContinuousScan();

        // 3. إبلاغ السيرفر المركزي بتفعيل البوابة
        updateGateStatusOnServer("armed");

        Toast.makeText(mContext, getString(R.string.gate_status_armed), Toast.LENGTH_SHORT).show();
    }

    // ============================================
    // 🔴 إيقاف البوابة
    // ============================================
    private void disarmGate() {
        gateActive = false;
        stopContinuousScan();

        passCount = 0;
        denyCount = 0;
        totalCount = 0;
        updateCounters();

        scanDebounceMap.clear();
        updateUI();

        updateGateStatusOnServer("offline");

        Toast.makeText(mContext, getString(R.string.gate_status_disarmed), Toast.LENGTH_SHORT).show();
    }

    // ============================================
    // 🟢 التحكم بمسح UHF Reader
    // ============================================
    private void startContinuousScan() {
        if (mContext != null && mContext.mReader != null) {
            mContext.mReader.setInventoryCallback(new IUHFInventoryCallback() {
                @Override
                public void callback(UHFTAGInfo uhftagInfo) {
                    if (uhftagInfo != null) {
                        processGateTag(uhftagInfo);
                    }
                }
            });
            mContext.mReader.startInventoryTag();
        }
    }

    private void stopContinuousScan() {
        if (mContext != null && mContext.mReader != null) {
            mContext.mReader.stopInventory();
            mContext.mReader.setInventoryCallback(null);
        }
    }

    // ============================================
    // ⚡ المعالجة الأمنية اللحظية (Zero-Latency Pipeline)
    // ============================================
    private void processGateTag(UHFTAGInfo tagInfo) {
        if (!gateActive) return;

        final String epc = tagInfo.getEPC();
        if (epc == null || epc.trim().isEmpty()) return;

        long currentTime = System.currentTimeMillis();
        Long lastTime = scanDebounceMap.get(epc);

        // تصفية التكرارات السريعة
        if (lastTime != null && (currentTime - lastTime < SCAN_COOL_DOWN_MS)) {
            return;
        }
        scanDebounceMap.put(epc, currentTime);

        // تحديث العداد
        mainHandler.post(() -> {
            totalCount++;
            updateCounters();
            String shortEpc = epc.length() > 8 ? epc.substring(epc.length() - 8) : epc;
            tvLastEvent.setText(getString(R.string.gate_status_verifying) + " (" + shortEpc + ")");
        });

        // 🟢 الفحص الأمني السريع محلياً (In-Memory Check < 5ms)
        boolean isLocallyAllowed = localAllowedEpcCache.contains(epc);

        if (isLocallyAllowed) {
            // خروج مصرح فوري!
            mainHandler.post(() -> handleGateDecision(true, "قطعة مصرح لها (مباعة)", epc));
        } else {
            // إرسال استعلام خلفي وفي نفس الوقت إطلاق الإنذار الوقائي إذا تم إعداده للتحقق المباشر
            gateExecutor.execute(() -> queryMiraGateServer(epc));
        }
    }

    // ============================================
    // 🌐 استعلام السيرفر والتصديق الأمني
    // ============================================
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
            conn.setConnectTimeout(2000); // مهلة استجابة أمنية قصيرة جداً
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
                String itemTitle = item != null ? item.optString("title", "مجواهرات غير مسجلة") : "قطعة غير معروفة";
                String serial = item != null ? item.optString("serial", epc) : epc;

                if (allowed) {
                    localAllowedEpcCache.add(epc); // تحديث الكاش محلياً
                }

                mainHandler.post(() -> handleGateDecision(allowed, itemTitle, serial));
            } else {
                // في حالة فشل السيرفر، يتم التعامل مع القطعة كغير مصرح بها أمنياً لحماية المعرض
                mainHandler.post(() -> handleGateDecision(false, "تنبيه أمني (فشل استجابة الخادم)", epc));
            }

        } catch (Exception e) {
            Log.e(TAG, "MIRA Security Query Error: " + e.getMessage());
            mainHandler.post(() -> handleGateDecision(false, "🚨 تنبيه أمني - خروج غير مصرح", epc));
        }
    }

    // ============================================
    // 🚨 تنفيذ القرار الأمني والإشارات
    // ============================================
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

            if (mContext != null) {
                mContext.playSound(1); // صوت النجاح
            }

        } else {
            denyCount++;
            updateCounters();

            tvGateStatus.setText(R.string.gate_status_alarm);
            tvGateStatus.setTextColor(Color.parseColor("#C62828"));
            statusIndicator.setBackgroundResource(R.drawable.circle_indicator_red);
            tvLastEvent.setText("🚨 " + itemTitle + " [" + serial + "]");
            tvGateIcon.setText("🚨");

            triggerSecurityAlarm();
        }

        // إعادة ضبط واجهة المراقبة بعد 3 ثوانٍ
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
            mContext.playSound(2); // صوت الإنذار القوي alarm_sold_item
        }

        // اهتزاز نمط إنذار طوارئ (Alarm Pattern)
        if (vibrator != null && vibrator.hasVibrator()) {
            long[] pattern = {0, 300, 100, 300, 100, 400};
            vibrator.vibrate(pattern, -1);
        }
    }

    // ============================================
    // 🔄 مزامنة الكاش المحلي مسبقاً (Local Cache Sync)
    // ============================================
    private void syncLocalAllowedCache() {
        gateExecutor.execute(() -> {
            try {
                // جلب قائمة التصاريح والمبيعات الحديثة لضمان التحقق الفوري
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
                        Log.d(TAG, "Local Security Cache Synced. Count: " + localAllowedEpcCache.size());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to sync local security cache: " + e.getMessage());
            }
        });
    }

    // ============================================
    // 🎨 تحديث الواجهة والعدادات
    // ============================================
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
                Log.e(TAG, "Gate status update error: " + e.getMessage());
            }
        });
    }

    @Override
    public void onPause() {
        super.onPause();
        if (gateActive) {
            stopContinuousScan();
        }
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
