package com.example.uhf.fragment;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
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

import com.example.uhf.R;
import com.example.uhf.engine.SmartScaleConnector;

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
    private static final String PREFS_NAME = "mira_settings";

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

    // 📡 المحور الأول: موصل الميزان الذكي ومتغير الوزن اللحظي
    private SmartScaleConnector scaleConnector;
    private double liveScaleWeight = 0.0; // الوزن الفعلي الملتقط من الميزان البلوتوث

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.uhf_readtag_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // ربط عناصر واجهة المستخدم للتصميم الداكن (Dark UI)
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

        // 1️⃣ بدء تشغيل الميزان الذكي تلقائياً عند فتح الشاشة
        initSmartScale();

        showMockVerificationResult();

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
     * ⚖️ تهيئة والاتصال بالميزان الذكي (Bluetooth Scale)
     */
    private void initSmartScale() {
        scaleConnector = new SmartScaleConnector();
        scaleConnector.setListener(new SmartScaleConnector.ScaleListener() {
            @Override
            public void onWeightReceived(double weightGrams) {
                liveScaleWeight = weightGrams;
                Log.d(TAG, "Scale Live Weight: " + weightGrams + "g");
            }

            @Override
            public void onScaleConnected(String deviceName) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> 
                        Toast.makeText(getContext(), "⚖️ Scale Connected: " + deviceName, Toast.LENGTH_SHORT).show()
                    );
                }
            }

            @Override
            public void onScaleDisconnected() {
                liveScaleWeight = 0.0;
            }

            @Override
            public void onScaleError(String error) {
                Log.e(TAG, "Scale Error: " + error);
            }
        });
    }

    /**
     * إرسال رمز EPC/Tag إلى خادم MIRA المعتمد وتحديث بطاقة العرض.
     */
    public void sendTagToMiraServer(final String epc, final String rssi) {
        SharedPreferences prefs = null;
        if (getContext() != null) {
            prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }

        final boolean showCard = prefs == null || prefs.getBoolean("show_mira_card", true);

        // إظهار البطاقة في حالة الإعدادات المفعلة
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                if (showCard && cardMiraResult != null) {
                    cardMiraResult.setVisibility(View.VISIBLE);
                }
            });
        }

        final SharedPreferences finalPrefs = prefs;

        // تنفيذ الطلب الشبكي في Thread منفصل
        new Thread(() -> {
            InputStream inputStream = null;
            try {
                String apiUrl = "https://ams.ibreg.org/wp-json/mira-gate/v1/authorize";
                String apiKey = "mira_gate_test071234567890abcdefghijklmnop";
                String gateId = "handheld_c72";

                if (finalPrefs != null) {
                    apiUrl = finalPrefs.getString("mira_api_url", apiUrl);
                    apiKey = finalPrefs.getString("mira_api_key", apiKey);
                    gateId = finalPrefs.getString("mira_gate_id", gateId);
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
                
                // 📡 إرسال وزن الميزان الفعلي المباشر للسيرفر للتأكيد إن وجد
                if (liveScaleWeight > 0) {
                    jsonParam.put("scale_weight", liveScaleWeight);
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

                // معالجة النتيجة وتحديث عناصر الواجهة بالمحرك الثلاثي
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
     * 💎 دالة تجميع الاستجابة وتطبيق التحقق الثلاثي الـ Hybrid Tri-Verification على الواجهة
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
            double expectedWeight = (item != null) ? item.optDouble("weight", 0.0) : 0.0;
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
                // إظهار الوزن الفعلي الموزون مقابل الوزن المتوقع إن وجد الميزان
                if (liveScaleWeight > 0 && expectedWeight > 0) {
                    tvWeightVal.setText(String.format(Locale.US, "%.2fg (Scale: %.2fg)", expectedWeight, liveScaleWeight));
                } else {
                    tvWeightVal.setText(expectedWeight > 0 ? String.format(Locale.US, "%.2fg", expectedWeight) : "0.00g");
                }
            }
            if (tvValueVal != null) {
                tvValueVal.setText(value > 0 ? String.format(Locale.US, "$%,.0f", value) : "$0");
            }

            // 3. تحديث السيريال
            if (tvSerialVal != null) {
                tvSerialVal.setText("SN: " + serialNo);
            }

            // 4. 🌟 خوارزمية التحقق الثلاثي اللحظي (Tri-Verification Logic)
            boolean isGs1Valid = barcode != null && barcode.optBoolean("valid", true);
            boolean isWeightMatch = true;

            // هامش السماحية بالوزن ±0.05g
            if (liveScaleWeight > 0 && expectedWeight > 0) {
                isWeightMatch = Math.abs(liveScaleWeight - expectedWeight) <= 0.05;
            }

            if (tvHeaderStatus != null && tvStatusBadge != null) {
                if (allowed && isGs1Valid && isWeightMatch) {
                    if (liveScaleWeight > 0) {
                        // ✅✅✅ مطابقة ثلاثية كاملة (100%)
                        tvHeaderStatus.setText("✅✅✅ TRI-VERIFIED (100%) — MATCHED");
                        tvHeaderStatus.setTextColor(Color.parseColor("#4ADE80"));
                        tvStatusBadge.setText("3X VERIFIED");
                        tvStatusBadge.setTextColor(Color.parseColor("#4ADE80"));
                    } else {
                        // ✅✅ مطابقة ثنائية (RFID + GS1 فقط بدون ميزان)
                        tvHeaderStatus.setText("✅✅ DOUBLE-VERIFIED (RFID + GS1)");
                        tvHeaderStatus.setTextColor(Color.parseColor("#38BDF8"));
                        tvStatusBadge.setText("2X VERIFIED");
                        tvStatusBadge.setTextColor(Color.parseColor("#38BDF8"));
                    }
                } else if (!isWeightMatch) {
                    // 🚨 تحذير عدم تطابق الوزن!
                    tvHeaderStatus.setText(String.format(Locale.US, "🚨 WEIGHT MISMATCH! (Exp: %.2fg vs Act: %.2fg)", expectedWeight, liveScaleWeight));
                    tvHeaderStatus.setTextColor(Color.parseColor("#EF4444"));
                    tvStatusBadge.setText("WEIGHT ALERT");
                    tvStatusBadge.setTextColor(Color.parseColor("#EF4444"));
                } else {
                    // ☒ محظور / غير مصرح
                    tvHeaderStatus.setText("☒ BLOCKED — UNAUTHORIZED");
                    tvHeaderStatus.setTextColor(Color.parseColor("#EF4444"));
                    tvStatusBadge.setText(statusStr);
                    tvStatusBadge.setTextColor(Color.parseColor("#EF4444"));
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "JSON parse error: " + e.getMessage());
            if (tvHeaderStatus != null) {
                tvHeaderStatus.setText("INVALID RESPONSE FORMAT");
                tvHeaderStatus.setTextColor(Color.parseColor("#F59E0B"));
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (scaleConnector != null) {
            scaleConnector.disconnect();
        }
    }
}
