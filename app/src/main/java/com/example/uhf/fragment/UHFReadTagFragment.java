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
import com.example.uhf.activity.UHFMainActivity;
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

    // UI Elements
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

    private SmartScaleConnector scaleConnector;
    private double liveScaleWeight = 0.0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.uhf_readtag_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

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

        initSmartScale();
        showMockVerificationResult();

        if (btnScan != null) {
            btnScan.setOnClickListener(v -> {
                String inputCode = etGtinInput != null ? 
                    etGtinInput.getText().toString().trim() : "";
                if (!inputCode.isEmpty()) {
                    handleScannedTag(inputCode);
                } else {
                    Toast.makeText(getContext(), "Please enter or scan a valid EPC/GTIN", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

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
                        Toast.makeText(getContext(), "⚖️ Scale Connected: " + deviceName, Toast.LENGTH_SHORT).show());
                }
            }
            @Override
            public void onScaleDisconnected() { liveScaleWeight = 0.0; }
            @Override
            public void onScaleError(String error) { Log.e(TAG, "Scale Error: " + error); }
        });
    }

    /**
     * 🎯 نقطة المعالجة الموحدة للوسم
     * 1. يرسل إلى POS Mode عبر Activity
     * 2. يرسل إلى خادم MIRA للتحقق
     */
    private void handleScannedTag(String epc) {
        // ⚡ تمرير إلى POS Mode (إذا كان نشطاً)
        if (getActivity() instanceof UHFMainActivity) {
            ((UHFMainActivity) getActivity()).onTagRead(epc);
        }
        // 📡 إرسال إلى خادم MIRA
        sendTagToMiraServer(epc, "-50");
    }

    public void sendTagToMiraServer(final String epc, final String rssi) {
        SharedPreferences prefs = null;
        if (getContext() != null) {
            prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }

        final boolean showCard = prefs == null || 
            prefs.getBoolean("show_mira_card", true);

        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                if (showCard && cardMiraResult != null) {
                    cardMiraResult.setVisibility(View.VISIBLE);
                }
            });
        }

        final SharedPreferences finalPrefs = prefs;

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

                JSONObject jsonParam = new JSONObject();
                jsonParam.put("epc", epc);
                jsonParam.put("gate_id", gateId);
                if (rssi != null && !rssi.isEmpty()) {
                    jsonParam.put("rssi", rssi);
                }
                if (liveScaleWeight > 0) {
                    jsonParam.put("scale_weight", liveScaleWeight);
                }

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonParam.toString().getBytes("utf-8");
                    os.write(input, 0, input.length);
                }

                final int responseCode = conn.getResponseCode();
                inputStream = (responseCode >= 200 && responseCode < 300) ? 
                    conn.getInputStream() : conn.getErrorStream();

                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream, "utf-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line.trim());
                }

                final String jsonResponseStr = response.toString();

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> 
                        updateUIWithMiraResponse(jsonResponseStr, epc));
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

    private void updateUIWithMiraResponse(String jsonResponseStr, String fallbackEpc) {
        try {
            JSONObject jsonObject = new JSONObject(jsonResponseStr);
            JSONObject decision = jsonObject.optJSONObject("decision");
            JSONObject item = jsonObject.optJSONObject("item");
            JSONObject barcode = jsonObject.optJSONObject("barcode");

            boolean allowed = decision != null && 
                decision.optBoolean("allowed", false);

            String title = (item != null) ? 
                item.optString("title", "Item Verified") : "Unknown Item";
            String karat = (item != null) ? 
                item.optString("karat", "N/A") : "N/A";
            double expectedWeight = (item != null) ? 
                item.optDouble("weight", 0.0) : 0.0;
            double value = (item != null) ? 
                item.optDouble("price", 0.0) : 0.0;
            String statusStr = (item != null) ? 
                item.optString("status", "VALID").toUpperCase() : "N/A";

            String serialNo = fallbackEpc;
            if (barcode != null && !barcode.optString("serial_no", "").isEmpty()) {
                serialNo = barcode.optString("serial_no");
            }

            if (tvMiraProductName != null) tvMiraProductName.setText(title);
            if (tvPurityVal != null) tvPurityVal.setText(karat.isEmpty() ? "N/A" : karat);
            if (tvWeightVal != null) {
                if (liveScaleWeight > 0 && expectedWeight > 0) {
                    tvWeightVal.setText(String.format(Locale.US, "%.2fg (Scale: %.2fg)", expectedWeight, liveScaleWeight));
                } else {
                    tvWeightVal.setText(expectedWeight > 0 ? 
                        String.format(Locale.US, "%.2fg", expectedWeight) : "0.00g");
                }
            }
            if (tvValueVal != null) tvValueVal.setText(value > 0 ? 
                String.format(Locale.US, "$%,.0f", value) : "$0");
            if (tvSerialVal != null) tvSerialVal.setText("SN: " + serialNo);

            boolean isGs1Valid = barcode != null && barcode.optBoolean("valid", true);
            boolean isWeightMatch = true;
            if (liveScaleWeight > 0 && expectedWeight > 0) {
                isWeightMatch = Math.abs(liveScaleWeight - expectedWeight) <= 0.05;
            }

            if (tvHeaderStatus != null && tvStatusBadge != null) {
                if (allowed && isGs1Valid && isWeightMatch) {
                    if (liveScaleWeight > 0) {
                        tvHeaderStatus.setText("✅✅✅ TRI-VERIFIED (100%) — MATCHED");
                        tvHeaderStatus.setTextColor(Color.parseColor("#4ADE80"));
                        tvStatusBadge.setText("3X VERIFIED");
                        tvStatusBadge.setTextColor(Color.parseColor("#4ADE80"));
                    } else {
                        tvHeaderStatus.setText("✅✅ DOUBLE-VERIFIED (RFID + GS1)");
                        tvHeaderStatus.setTextColor(Color.parseColor("#38BDF8"));
                        tvStatusBadge.setText("2X VERIFIED");
                        tvStatusBadge.setTextColor(Color.parseColor("#38BDF8"));
                    }
                } else if (!isWeightMatch) {
                    tvHeaderStatus.setText(String.format(Locale.US, 
                        "🚨 WEIGHT MISMATCH! (Exp: %.2fg vs Act: %.2fg)", 
                        expectedWeight, liveScaleWeight));
                    tvHeaderStatus.setTextColor(Color.parseColor("#EF4444"));
                    tvStatusBadge.setText("WEIGHT ALERT");
                    tvStatusBadge.setTextColor(Color.parseColor("#EF4444"));
                } else {
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

    private void showMockVerificationResult() {
        if (cardMiraResult != null) cardMiraResult.setVisibility(View.VISIBLE);
        if (tvMiraProductName != null) tvMiraProductName.setText("خاتم ذهب 21K");
        if (tvPurityVal != null) tvPurityVal.setText("21K");
        if (tvWeightVal != null) tvWeightVal.setText("10.00g (Scale: 10.00g)");
        if (tvValueVal != null) tvValueVal.setText("$750");
        if (tvSerialVal != null) tvSerialVal.setText("SN: 070045537109");
        if (tvHeaderStatus != null) {
            tvHeaderStatus.setText("✅✅✅ TRI-VERIFIED (100%) — MATCHED");
            tvHeaderStatus.setTextColor(Color.parseColor("#4ADE80"));
        }
        if (tvStatusBadge != null) {
            tvStatusBadge.setText("3X VERIFIED");
            tvStatusBadge.setTextColor(Color.parseColor("#4ADE80"));
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (scaleConnector != null) scaleConnector.disconnect();
    }
                }
