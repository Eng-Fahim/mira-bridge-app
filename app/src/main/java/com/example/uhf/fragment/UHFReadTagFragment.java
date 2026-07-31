package com.rfid.uhf;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

public class UHFReadTagFragment extends Fragment {

    private static final String TAG = "UHFReadTagFragment";

    // MIRA UI Elements
    private View cardMiraResult;
    private TextView tvMiraProductName;
    private TextView tvHeaderStatus;
    private TextView tvPurityVal;
    private TextView tvWeightVal;
    private TextView tvValueVal;
    private TextView tvSerialVal;
    private TextView tvStatusBadge;

    private EditText etGtinInput;
    private Button btnScan;

    private MiraSettingsManager settingsManager;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.uhf_readtag_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // تهيئة مدير الإعدادات
        if (getContext() != null) {
            settingsManager = MiraSettingsManager.getInstance(getContext());
        }

        // ربط عناصر واجهة المستخدم للتصميم الداكن الجديد (Dark UI)
        cardMiraResult = view.findViewById(R.id.cardMiraResult);
        tvMiraProductName = view.findViewById(R.id.tvProductName);
        tvHeaderStatus = view.findViewById(R.id.tvHeaderStatus);
        
        tvPurityVal = view.findViewById(R.id.tvPurityVal);
        tvWeightVal = view.findViewById(R.id.tvWeightVal);
        tvValueVal = view.findViewById(R.id.tvValueVal);
        tvSerialVal = view.findViewById(R.id.tvSerialVal);
        tvStatusBadge = view.findViewById(R.id.tvStatusBadge);

        etGtinInput = view.findViewById(R.id.etGtinInput);
        btnScan = view.findViewById(R.id.btnScan);

        // ربط زر المسح اليدوي/التحقق
        if (btnScan != null) {
            btnScan.setOnClickListener(v -> {
                String inputCode = etGtinInput != null ? etGtinInput.getText().toString().trim() : "";
                if (!inputCode.isEmpty()) {
                    sendTagToMiraServer(inputCode, "-50");
                } else {
                    Toast.makeText(getContext(), "Please enter or scan a valid EPC/GTIN", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    /**
     * إرسال رمز EPC/Tag إلى خادم MIRA المعتمد وتحديث بطاقة العرض الداكنة.
     *
     * @param epc  قيمة RFID EPC أو الباروكود
     * @param rssi قوة الإشارة (اختياري)
     */
    public void sendTagToMiraServer(final String epc, final String rssi) {
        if (settingsManager == null && getContext() != null) {
            settingsManager = MiraSettingsManager.getInstance(getContext());
        }

        final boolean showCard = settingsManager == null || settingsManager.getBoolean("show_mira_card", true);

        // إظهار البطاقة في حالة الإعدادات المفعلة
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                if (showCard && cardMiraResult != null) {
                    cardMiraResult.setVisibility(View.VISIBLE);
                }
            });
        }

        // تنفيذ الطلب الشبكي في Thread منفصل لعدم تجميد الـ UI
        new Thread(() -> {
            InputStream inputStream = null;
            try {
                // القيم الافتراضية للاتصال
                String apiUrl = "https://ams.ibreg.org/wp-json/mira-gate/v1/authorize";
                String apiKey = "mira_gate_test071234567890abcdefghijklmnop";
                String gateId = "handheld_c72";

                if (settingsManager != null) {
                    apiUrl = settingsManager.getString("mira_api_url", apiUrl);
                    apiKey = settingsManager.getString("mira_api_key", apiKey);
                    gateId = settingsManager.getString("mira_gate_id", gateId);
                }

                URL url = new URL(apiUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("X-MIRA-API-Key", apiKey);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setDoOutput(true);

                // تجهيز جسم الطلب JSON
                JSONObject jsonParam = new JSONObject();
                jsonParam.put("epc", epc);
                jsonParam.put("gate_id", gateId);
                if (rssi != null && !rssi.isEmpty()) {
                    jsonParam.put("rssi", rssi);
                }

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonParam.toString().getBytes("utf-8");
                    os.write(input, 0, input.length);
                }

                final int responseCode = conn.getResponseCode();
                inputStream = (responseCode >= 200 && responseCode < 300) ? conn.getInputStream() : conn.getErrorStream();

                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "utf-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line.trim());
                }

                final String jsonResponseStr = response.toString();

                // معالجة النتيجة وتحديث عناصر الواجهة
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> updateUIWithMiraResponse(jsonResponseStr, epc));
                }

            } catch (Exception e) {
                Log.e(TAG, "Server connection error: " + e.getMessage());
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (tvHeaderStatus != null) {
                            tvHeaderStatus.setText("CONNECTION ERROR");
                            tvHeaderStatus.setTextColor(Color.parseColor("#EF4444"));
                        }
                    });
                }
            } finally {
                if (inputStream != null) {
                    try { inputStream.close(); } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    /**
     * دالة مساعدة لتجميع استجابة JSON وتوزيع البيانات على عناصر البطاقة
     */
    private void updateUIWithMiraResponse(String jsonResponseStr, String fallbackEpc) {
        try {
            JSONObject jsonObject = new JSONObject(jsonResponseStr);
            JSONObject decision = jsonObject.optJSONObject("decision");
            JSONObject item = jsonObject.optJSONObject("item");
            JSONObject barcode = jsonObject.optJSONObject("barcode");

            boolean allowed = decision != null && decision.optBoolean("allowed", false);

            // استخراج بيانات المنتج
            String title = (item != null) ? item.optString("title", "Item Verified") : "Unknown Item";
            String karat = (item != null) ? item.optString("karat", "N/A") : "N/A";
            double weight = (item != null) ? item.optDouble("weight", 0.0) : 0.0;
            double value = (item != null) ? item.optDouble("price", 0.0) : 0.0;
            String statusStr = (item != null) ? item.optString("status", "VALID").toUpperCase() : "N/A";

            String serialNo = fallbackEpc;
            if (barcode != null && !barcode.optString("serial_no", "").isEmpty()) {
                serialNo = barcode.optString("serial_no");
            }

            // 1. تحديث اسم المنتج
            if (tvMiraProductName != null) {
                tvMiraProductName.setText(title);
            }

            // 2. تحديث المربعات الثلاثة القياسية (Purity, Weight, Value)
            if (tvPurityVal != null) {
                tvPurityVal.setText(karat.isEmpty() ? "N/A" : karat);
            }
            if (tvWeightVal != null) {
                tvWeightVal.setText(weight > 0 ? String.format(Locale.US, "%.2fg", weight) : "0.00g");
            }
            if (tvValueVal != null) {
                tvValueVal.setText(value > 0 ? String.format(Locale.US, "$%,.0f", value) : "$0");
            }

            // 3. تحديث السيريال والشارة
            if (tvSerialVal != null) {
                tvSerialVal.setText("SN: " + serialNo);
            }
            if (tvStatusBadge != null) {
                tvStatusBadge.setText(statusStr);
            }

            // 4. تحديث شريط القرار العلوي (Passed / Blocked)
            if (tvHeaderStatus != null) {
                if (allowed) {
                    tvHeaderStatus.setText("✓ PASSED — AUTHORIZED DISPATCH");
                    tvHeaderStatus.setTextColor(Color.parseColor("#4ADE80")); // الأخضر المضيء
                } else {
                    tvHeaderStatus.setText("☒ BLOCKED — UNAUTHORIZED");
                    tvHeaderStatus.setTextColor(Color.parseColor("#EF4444")); // الأحمر
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "JSON parse error: " + e.getMessage());
            if (tvHeaderStatus != null) {
                tvHeaderStatus.setText("INVALID RESPONSE FORMAT");
                tvHeaderStatus.setTextColor(Color.parseColor("#F59E0B")); // البرتقالي
            }
        }
    }
}
