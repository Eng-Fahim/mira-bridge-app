package com.example.uhf.fragment;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MiraScanProFragment extends Fragment {

    private EditText etScanInput;
    private Button btnScan;
    private CardView cardResult;
    private TextView tvScanStatus, tvItemTitle, tvKarat, tvWeight, tvValue, tvItemStatus;
    private View indicatorStatus, layoutStatus;

    private static final String API_URL = "https://ams.ibreg.org/wp-json/mira-gate/v1/authorize";
    private static final String API_KEY = "mira_gate_test071234567890abcdefghijklmnop";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_mira_scan_pro, container, false);

        etScanInput = v.findViewById(R.id.etScanInput);
        btnScan = v.findViewById(R.id.btnScan);
        cardResult = v.findViewById(R.id.cardResult);
        tvScanStatus = v.findViewById(R.id.tvScanStatus);
        tvItemTitle = v.findViewById(R.id.tvItemTitle);
        tvKarat = v.findViewById(R.id.tvKarat);
        tvWeight = v.findViewById(R.id.tvWeight);
        tvValue = v.findViewById(R.id.tvValue);
        tvItemStatus = v.findViewById(R.id.tvItemStatus);
        indicatorStatus = v.findViewById(R.id.indicatorStatus);
        layoutStatus = v.findViewById(R.id.layoutStatus);

        btnScan.setOnClickListener(view -> scanTag());

        return v;
    }

    private void scanTag() {
        String code = etScanInput.getText().toString().trim();
        if (code.isEmpty()) {
            Toast.makeText(getContext(), "أدخل رمز القطعة", Toast.LENGTH_SHORT).show();
            return;
        }

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
                body.put("epc", code);
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
                    JSONObject item = json.getJSONObject("item");

                    boolean allowed = decision.getBoolean("allowed");
                    String message = decision.getString("message");
                    String title = item.optString("title", "غير معروف");
                    String karat = item.optString("karat", "");
                    String weight = item.optString("weight", "");
                    String status = item.optString("status", "");

                    getActivity().runOnUiThread(() -> {
                        cardResult.setVisibility(View.VISIBLE);
                        tvItemTitle.setText("📦 " + title);
                        tvKarat.setText(karat);
                        tvWeight.setText(weight + " غ");
                        tvScanStatus.setText(message);

                        if (allowed) {
                            layoutStatus.setBackgroundColor(Color.parseColor("#1B5E20"));
                            indicatorStatus.setBackgroundColor(Color.parseColor("#4CAF50"));
                            tvItemStatus.setText("🟢 " + (status.equals("sold") ? "مباع" : status));
                            tvItemStatus.setTextColor(Color.parseColor("#4CAF50"));
                        } else {
                            layoutStatus.setBackgroundColor(Color.parseColor("#B71C1C"));
                            indicatorStatus.setBackgroundColor(Color.parseColor("#F44336"));
                            tvItemStatus.setText("🔴 ممنوع");
                            tvItemStatus.setTextColor(Color.parseColor("#F44336"));
                        }
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
}
