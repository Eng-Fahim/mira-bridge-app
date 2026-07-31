package com.example.uhf.fragment;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MiraGateProFragment extends Fragment {

    // UI Elements
    private View statusIndicator;
    private TextView tvGateStatus;
    private TextView tvPassCount, tvDenyCount, tvUnknownCount;
    private Button btnArm, btnDisarm;
    private TextView tvLastEvent;

    // Gate State
    private boolean gateArmed = false;
    private int passCount = 0;
    private int denyCount = 0;
    private int unknownCount = 0;

    // API
    private static final String API_URL = "https://ams.ibreg.org/wp-json/mira-gate/v1/authorize";
    private static final String API_KEY = "mira_gate_test071234567890abcdefghijklmnop";

    // Handlers
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable scanRunnable;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_mira_gate_pro, container, false);

        // Bind Views
        statusIndicator = v.findViewById(R.id.statusIndicator);
        tvGateStatus = v.findViewById(R.id.tvGateStatus);
        tvPassCount = v.findViewById(R.id.tvPassCount);
        tvDenyCount = v.findViewById(R.id.tvDenyCount);
        tvUnknownCount = v.findViewById(R.id.tvUnknownCount);
        btnArm = v.findViewById(R.id.btnArm);
        btnDisarm = v.findViewById(R.id.btnDisarm);
        tvLastEvent = new TextView(getContext()); // For toast-like messages

        // Arm Gate
        btnArm.setOnClickListener(view -> {
            gateArmed = true;
            updateGateUI();
            startScanning();
            Toast.makeText(getContext(), "🛡️ MIRA Secure Gate™ ARMED", Toast.LENGTH_SHORT).show();
        });

        // Disarm Gate
        btnDisarm.setOnClickListener(view -> {
            gateArmed = false;
            updateGateUI();
            stopScanning();
            Toast.makeText(getContext(), "🔒 Gate DISARMED", Toast.LENGTH_SHORT).show();
        });

        updateGateUI();

        return v;
    }

    /**
     * Update UI based on gate state
     */
    private void updateGateUI() {
        if (gateArmed) {
            if (statusIndicator != null) {
                statusIndicator.setBackgroundColor(Color.parseColor("#4CAF50"));
            }
            if (tvGateStatus != null) {
                tvGateStatus.setText("🛡️ GATE ARMED");
                tvGateStatus.setTextColor(Color.parseColor("#4CAF50"));
            }
            btnArm.setEnabled(false);
            btnArm.setAlpha(0.5f);
            btnDisarm.setEnabled(true);
            btnDisarm.setAlpha(1.0f);
        } else {
            if (statusIndicator != null) {
                statusIndicator.setBackgroundColor(Color.parseColor("#F44336"));
            }
            if (tvGateStatus != null) {
                tvGateStatus.setText("🔒 GATE DISARMED");
                tvGateStatus.setTextColor(Color.parseColor("#F44336"));
            }
            btnArm.setEnabled(true);
            btnArm.setAlpha(1.0f);
            btnDisarm.setEnabled(false);
            btnDisarm.setAlpha(0.5f);
        }

        // Update counters
        if (tvPassCount != null) tvPassCount.setText(String.valueOf(passCount));
        if (tvDenyCount != null) tvDenyCount.setText(String.valueOf(denyCount));
        if (tvUnknownCount != null) tvUnknownCount.setText(String.valueOf(unknownCount));
    }

    /**
     * Start continuous scanning simulation
     */
    private void startScanning() {
        scanRunnable = new Runnable() {
            @Override
            public void run() {
                if (!gateArmed) return;

                // Simulate scanning a tag every 3 seconds
                simulateTagScan();
                handler.postDelayed(this, 3000);
            }
        };
        handler.post(scanRunnable);
    }

    /**
     * Stop scanning
     */
    private void stopScanning() {
        if (scanRunnable != null) {
            handler.removeCallbacks(scanRunnable);
        }
    }

    /**
     * Simulate scanning a tag and querying MIRA ID
     */
    private void simulateTagScan() {
        // Demo: Simulate authorized scan
        String[] demoEpc = {"BWA-26000101", "BWA-26000102", "BWA-26000103"};
        String randomEpc = demoEpc[(int) (Math.random() * demoEpc.length)];

        queryMiraGate(randomEpc);
    }

    /**
     * Query MIRA ID API for gate decision
     */
    private void queryMiraGate(String epc) {
        new Thread(() -> {
            try {
                URL url = new URL(API_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-MIRA-API-Key", API_KEY);
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);

                JSONObject body = new JSONObject();
                body.put("epc", epc);
                body.put("gate_id", "handheld_c72");
                conn.getOutputStream().write(body.toString().getBytes("utf-8"));

                if (conn.getResponseCode() == 200) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) sb.append(line);
                    in.close();

                    JSONObject json = new JSONObject(sb.toString());
                    JSONObject decision = json.getJSONObject("decision");
                    boolean allowed = decision.getBoolean("allowed");
                    String itemTitle = json.getJSONObject("item").optString("title", "Unknown");

                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            if (allowed) {
                                passCount++;
                                flashGate(Color.parseColor("#4CAF50"));
                                Toast.makeText(getContext(), "✅ " + itemTitle + " - AUTHORIZED", Toast.LENGTH_SHORT).show();
                            } else {
                                denyCount++;
                                flashGate(Color.parseColor("#F44336"));
                                Toast.makeText(getContext(), "🚨 " + itemTitle + " - DENIED!", Toast.LENGTH_SHORT).show();
                            }
                            updateGateUI();
                        });
                    }
                }
            } catch (Exception e) {
                unknownCount++;
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> updateGateUI());
                }
            }
        }).start();
    }

    /**
     * Visual flash effect on gate indicator
     */
    private void flashGate(int color) {
        if (statusIndicator == null) return;

        statusIndicator.setBackgroundColor(color);
        handler.postDelayed(() -> {
            if (statusIndicator != null) {
                statusIndicator.setBackgroundColor(gateArmed ? Color.parseColor("#4CAF50") : Color.parseColor("#F44336"));
            }
        }, 500);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopScanning();
    }
}
