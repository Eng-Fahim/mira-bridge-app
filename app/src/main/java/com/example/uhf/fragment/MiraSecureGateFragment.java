package com.example.uhf.fragment;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.manager.MiraSettingsManager;
import com.rscja.deviceapi.entity.UHFTAGInfo;
import com.rscja.deviceapi.interfaces.IUHFInventoryCallback;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * MIRA Secure Gate™ Fragment
 * 
 * وضع البوابة الأمنية:
 * - مسح RFID مستمر مع تصفية القراءات المكررة (Debounce Filter)
 * - استعلام MIRA ID لكل تاق مع التعامل الكامل مع استجابات الشبكة
 * - فتح/قفل البوابة وتنبيهات الواجهة
 */
public class MiraSecureGateFragment extends Fragment {

    private static final String TAG = "MiraSecureGate";

    private UHFMainActivity mContext;
    private MiraSettingsManager settingsManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // 🟢 عناصر الواجهة
    private TextView tvGateIcon, tvGateStatus, tvLastEvent;
    private TextView tvPassCount, tvDenyCount, tvTotalCount;
    private View statusIndicator;
    private Button btnArmGate, btnDisarmGate;

    // 🟢 حالة البوابة والعدادات
    private boolean gateActive = false;
    private int passCount = 0;
    private int denyCount = 0;
    private int totalCount = 0;

    // 🟢 فحص التكرار (Debounce Mechanism)
    private String lastScannedEpc = "";
    private long lastScanTime = 0;
    private static final long SCAN_COOL_DOWN = 2000; // منع استعلام نفس التاق خلال ثانيتين

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_secure_gate, container, false);
        
        tvGateIcon = view.findViewById(R.id.tvGateIcon);
        tvGateStatus = view.findViewById(R.id.tvGateStatus);
        tvLastEvent = view.findViewById(R.id.tvLastEvent);
        tvPassCount = view.findViewById(R.id.tvPassCount);
        tvDenyCount = view.findViewById(R.id.tvDenyCount);
        tvTotalCount = view.findViewById(R.id.tvTotalCount);
        statusIndicator = view.findViewById(R.id.statusIndicator);
        btnArmGate = view.findViewById(R.id.btnArmGate);
        btnDisarmGate = view.findViewById(R.id.btnDisarmGate);

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mContext = (UHFMainActivity) getActivity();
        if (mContext != null) {
            settingsManager = MiraSettingsManager.getInstance(mContext);
        }

        btnArmGate.setOnClickListener(v -> armGate());
        btnDisarmGate.setOnClickListener(v -> disarmGate());
        
        updateUI();
    }

    // ============================================
    // 🟢 تفعيل البوابة
    // ============================================
    private void armGate() {
        gateActive = true;
        updateUI();
        
        startContinuousScan();
        updateGateStatusOnServer("armed");
        
        Toast.makeText(mContext, "🛡️ MIRA Secure Gate™ مفعلة", Toast.LENGTH_SHORT).show();
    }

    // ============================================
    // 🔴 إيقاف البوابة
    // ============================================
    private void disarmGate() {
        gateActive = false;
        updateUI();
        
        stopContinuousScan();
        
        passCount = 0;
        denyCount = 0;
        totalCount = 0;
        updateCounters();
        
        updateGateStatusOnServer("offline");
        
        Toast.makeText(mContext, "🔒 تم إيقاف البوابة", Toast.LENGTH_SHORT).show();
    }

    // ============================================
    // 🟢 التحكم بمسح الهاردوير (UHF Reader)
    // ============================================
    private void startContinuousScan() {
        if (mContext != null && mContext.mReader != null) {
            mContext.mReader.setInventoryCallback(new IUHFInventoryCallback() {
                @Override
                public void callback(UHFTAGInfo uhftagInfo) {
                    processGateTag(uhftagInfo);
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
    // 🟢 معالجة التاق مع فلترة التكرار
    // ============================================
    private void processGateTag(UHFTAGInfo tagInfo) {
        if (!gateActive) return;
        
        String epc = tagInfo.getEPC();
        if (epc == null || epc.trim().isEmpty()) return;

        long currentTime = System.currentTimeMillis();
        // منع معالجة التاق نفسه فوراً إذا كان ثابتاً أمام المستشعر
        if (epc.equals(lastScannedEpc) && (currentTime - lastScanTime < SCAN_COOL_DOWN)) {
            return;
        }

        lastScannedEpc = epc;
        lastScanTime = currentTime;

        handler.post(() -> {
            totalCount++;
            updateCounters();
            
            String shortEpc = epc.length() > 8 ? epc.substring(epc.length() - 8) : epc;
            tvLastEvent.setText("🔍 جاري التحقق من التاق: ..." + shortEpc);
            tvLastEvent.setTextColor(Color.parseColor("#FFC107"));
        });

        // استعلام السيرفر
        queryMiraGate(epc);
    }

    // ============================================
    // 🟢 استعلام سيرفر MIRA ID
    // ============================================
    private void queryMiraGate(String epc) {
        new Thread(() -> {
            try {
                String apiUrl = settingsManager.getString("mira_api_url",
                    "https://ams.ibreg.org/wp-json/mira-gate/v1/authorize");
                String apiKey = settingsManager.getString("mira_api_key",
                    "mira_gate_test071234567890abcdefghijklmnop");
                String gateId = settingsManager.getString("mira_gate_id", "secure_gate_01");

                URL url = new URL(apiUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("X-MIRA-API-Key", apiKey);
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setDoOutput(true);

                JSONObject json = new JSONObject();
                json.put("epc", epc);
                json.put("gate_id", gateId);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.toString().getBytes("utf-8"));
                }

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "utf-8"));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) response.append(line);

                    JSONObject responseJson = new JSONObject(response.toString());
                    JSONObject decision = responseJson.optJSONObject("decision");
                    JSONObject item = responseJson.optJSONObject("item");

                    boolean allowed = decision != null && decision.optBoolean("allowed", false);
                    String itemTitle = item != null ? item.optString("title", "عنصر غير معروف") : "عنصر غير معروف";
                    String serial = item != null ? item.optString("serial", epc) : epc;

                    handler.post(() -> handleGateDecision(allowed, itemTitle, serial));
                } else {
                    handler.post(() -> {
                        tvLastEvent.setText("⚠️ خطأ سيرفر (HTTP " + responseCode + ")");
                        tvLastEvent.setTextColor(Color.parseColor("#FF9800"));
                    });
                }

            } catch (Exception e) {
                Log.e(TAG, "MIRA query error: " + e.getMessage());
                handler.post(() -> {
                    tvLastEvent.setText("❌ فشل الاتصال بالشبكة!");
                    tvLastEvent.setTextColor(Color.RED);
                });
            }
        }).start();
    }

    // ============================================
    // 🟢 تنفيذ قرار البوابة في الواجهة
    // ============================================
    private void handleGateDecision(boolean allowed, String itemTitle, String serial) {
        if (allowed) {
            passCount++;
            updateCounters();
            
            tvGateStatus.setText("✅ فتح - " + itemTitle);
            tvGateStatus.setTextColor(Color.parseColor("#4CAF50"));
            statusIndicator.setBackgroundResource(R.drawable.circle_indicator_green);
            tvLastEvent.setText("✅ " + serial + " - " + itemTitle);
            tvLastEvent.setTextColor(Color.parseColor("#4CAF50"));
            
            simulateGateOpen();
            
            handler.postDelayed(() -> {
                if (gateActive) {
                    resetGateUI();
                }
            }, 3000);

        } else {
            denyCount++;
            updateCounters();
            
            tvGateStatus.setText("🚨 إنذار - " + itemTitle);
            tvGateStatus.setTextColor(Color.parseColor("#F44336"));
            statusIndicator.setBackgroundResource(R.drawable.circle_indicator_red);
            tvLastEvent.setText("🚨 " + serial + " - غير مصرح!");
            tvLastEvent.setTextColor(Color.RED);
            
            simulateAlert();
            
            handler.postDelayed(() -> {
                if (gateActive) {
                    resetGateUI();
                }
            }, 2000);
        }
    }

    // ============================================
    // 🟢 محاكاة الإشارات والأصوات
    // ============================================
    private void simulateGateOpen() {
        tvGateIcon.setText("✅");
        if (mContext != null) {
            mContext.playSound(1);
        }
    }

    private void simulateAlert() {
        tvGateIcon.setText("🚨");
        if (mContext != null) {
            mContext.playSound(2);
        }
    }

    // ============================================
    // 🟢 إعادة تعيين الواجهة للوضع الجاهز
    // ============================================
    private void resetGateUI() {
        tvGateIcon.setText("🚪");
        tvGateStatus.setText("🛡️ البوابة مفعلة - جاري المسح");
        tvGateStatus.setTextColor(Color.parseColor("#FFFFFF"));
        statusIndicator.setBackgroundResource(R.drawable.circle_indicator_green);
    }

    // ============================================
    // 🟢 تحديث الواجهة والعدادات
    // ============================================
    private void updateUI() {
        if (gateActive) {
            tvGateStatus.setText("🛡️ البوابة مفعلة - جاري المسح");
            tvGateStatus.setTextColor(Color.parseColor("#FFFFFF"));
            statusIndicator.setBackgroundResource(R.drawable.circle_indicator_green);
            btnArmGate.setEnabled(false);
            btnArmGate.setAlpha(0.5f);
            btnDisarmGate.setEnabled(true);
            btnDisarmGate.setAlpha(1.0f);
        } else {
            tvGateStatus.setText("🔒 البوابة غير مفعلة");
            tvGateStatus.setTextColor(Color.parseColor("#FFFFFF"));
            statusIndicator.setBackgroundResource(R.drawable.circle_indicator_red);
            btnArmGate.setEnabled(true);
            btnArmGate.setAlpha(1.0f);
            btnDisarmGate.setEnabled(false);
            btnDisarmGate.setAlpha(0.5f);
        }
    }

    private void updateCounters() {
        tvPassCount.setText(String.valueOf(passCount));
        tvDenyCount.setText(String.valueOf(denyCount));
        tvTotalCount.setText(String.valueOf(totalCount));
    }

    // ============================================
    // 🟢 تحديث حالة البوابة على السيرفر
    // ============================================
    private void updateGateStatusOnServer(String status) {
        new Thread(() -> {
            try {
                String apiKey = settingsManager.getString("mira_api_key",
                    "mira_gate_test071234567890abcdefghijklmnop");
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
                json.put("pass_count", passCount);
                json.put("deny_count", denyCount);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.toString().getBytes("utf-8"));
                }

                int code = conn.getResponseCode();
                Log.d(TAG, "Gate status update: " + code);

            } catch (Exception e) {
                Log.e(TAG, "Status update error: " + e.getMessage());
            }
        }).start();
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
    }
}
