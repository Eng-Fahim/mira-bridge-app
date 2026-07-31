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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MiraInventoryFragment extends Fragment {
    

    // UI
    private TextView tvInventoryStatus, tvExpectedCount, tvScannedCount, tvMissingCount;
    private TextView tvAccuracy, tvElapsedTime, tvLocation, tvLastScan, tvRssi;
    private View progressScanned;
    private Button btnStartInventory, btnStopInventory, btnGenerateReport;
    private RecyclerView rvInventoryItems;

    // State
    private boolean isScanning = false;
    private int expectedCount = 0;
    private int scannedCount = 0;
    private int missingCount = 0;
    private int sessionId = 0;
    private long startTime = 0;

    // Lists
    private List<InventoryItem> scannedItems = new ArrayList<>();
    private InventoryAdapter adapter;

    // Handlers
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable timerRunnable;

    // API
    private MiraApiClient apiClient;
    private PreferencesManager prefs;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_mira_inventory, container, false);

        // Bind Views
        tvInventoryStatus = v.findViewById(R.id.tvInventoryStatus);
        tvExpectedCount = v.findViewById(R.id.tvExpectedCount);
        tvScannedCount = v.findViewById(R.id.tvScannedCount);
        tvMissingCount = v.findViewById(R.id.tvMissingCount);
        tvAccuracy = v.findViewById(R.id.tvAccuracy);
        tvElapsedTime = v.findViewById(R.id.tvElapsedTime);
        tvLocation = v.findViewById(R.id.tvLocation);
        tvLastScan = v.findViewById(R.id.tvLastScan);
        tvRssi = v.findViewById(R.id.tvRssi);
        progressScanned = v.findViewById(R.id.progressScanned);
        btnStartInventory = v.findViewById(R.id.btnStartInventory);
        btnStopInventory = v.findViewById(R.id.btnStopInventory);
        btnGenerateReport = v.findViewById(R.id.btnGenerateReport);
        rvInventoryItems = v.findViewById(R.id.rvInventoryItems);

        // Setup RecyclerView
        adapter = new InventoryAdapter(scannedItems);
        rvInventoryItems.setLayoutManager(new LinearLayoutManager(getContext()));
        rvInventoryItems.setAdapter(adapter);

        // Click Listeners
        btnStartInventory.setOnClickListener(view -> startInventory());
        btnStopInventory.setOnClickListener(view -> stopInventory());
        btnGenerateReport.setOnClickListener(view -> generateReport());

        updateUI();

        return v;
    }

    /**
     * 🟢 بدء جلسة الجرد
     */
    private void startInventory() {
        isScanning = true;
        startTime = System.currentTimeMillis();
        scannedItems.clear();
        scannedCount = 0;
        adapter.notifyDataSetChanged();

        tvInventoryStatus.setText("● SCANNING");
        tvInventoryStatus.setTextColor(Color.parseColor("#38BDF8"));

        // 🟢 استدعاء API لبدء الجلسة
        startInventorySession();

        // 🟢 بدء المؤقت
        startTimer();

        Toast.makeText(getContext(), "🟢 Inventory Started", Toast.LENGTH_SHORT).show();
    }

    /**
     * 🔴 إيقاف جلسة الجرد
     */
    private void stopInventory() {
        isScanning = false;

        tvInventoryStatus.setText("● IDLE");
        tvInventoryStatus.setTextColor(Color.parseColor("#22C55E"));

        // 🟢 إيقاف المؤقت
        stopTimer();

        // 🟢 استدعاء API لإغلاق الجلسة
        closeInventorySession();

        Toast.makeText(getContext(), "🔴 Inventory Stopped", Toast.LENGTH_SHORT).show();
    }

    /**
     * ⏱️ بدء المؤقت
     */
    private void startTimer() {
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                long elapsed = System.currentTimeMillis() - startTime;
                long seconds = (elapsed / 1000) % 60;
                long minutes = (elapsed / (1000 * 60)) % 60;
                long hours = (elapsed / (1000 * 60 * 60)) % 24;
                tvElapsedTime.setText(String.format("%02d:%02d:%02d", hours, minutes, seconds));
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(timerRunnable);
    }

    private void stopTimer() {
        if (timerRunnable != null) {
            handler.removeCallbacks(timerRunnable);
        }
    }

    /**
     * ☁️ بدء جلسة الجرد في MIRA ID
     */
    private void startInventorySession() {
        new Thread(() -> {
            try {
                URL url = new URL("https://ams.ibreg.org/wp-json/mira-gate/v1/inventory/start-session");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-MIRA-API-Key", "mira_gate_test071234567890abcdefghijklmnop");
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("location", "Main Showroom");
                body.put("type", "full");
                conn.getOutputStream().write(body.toString().getBytes("utf-8"));

                if (conn.getResponseCode() == 200) {
                    java.io.BufferedReader in = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) sb.append(line);
                    in.close();

                    JSONObject json = new JSONObject(sb.toString());
                    sessionId = json.optInt("session_id", 0);
                    expectedCount = json.optInt("expected_count", 0);

                    getActivity().runOnUiThread(() -> {
                        tvExpectedCount.setText(String.valueOf(expectedCount));
                        updateUI();
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    /**
     * ☁️ إغلاق جلسة الجرد
     */
    private void closeInventorySession() {
        new Thread(() -> {
            try {
                URL url = new URL("https://ams.ibreg.org/wp-json/mira-gate/v1/inventory/close-session");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-MIRA-API-Key", "mira_gate_test071234567890abcdefghijklmnop");
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("session_id", sessionId);
                conn.getOutputStream().write(body.toString().getBytes("utf-8"));

                if (conn.getResponseCode() == 200) {
                    java.io.BufferedReader in = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) sb.append(line);
                    in.close();

                    JSONObject json = new JSONObject(sb.toString());
                    JSONObject report = json.getJSONObject("report");
                    double accuracy = report.optDouble("accuracy", 0);

                    getActivity().runOnUiThread(() -> {
                        tvAccuracy.setText("Accuracy: " + accuracy + "%");
                        updateUI();
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    /**
     * 📋 توليد تقرير
     */
    private void generateReport() {
        StringBuilder report = new StringBuilder();
        report.append("=== MIRA Inventory Report ===\n");
        report.append("Date: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date())).append("\n");
        report.append("Expected: ").append(expectedCount).append("\n");
        report.append("Scanned: ").append(scannedCount).append("\n");
        report.append("Missing: ").append(missingCount).append("\n\n");

        for (InventoryItem item : scannedItems) {
            report.append(item.status).append(" ").append(item.serial).append(" | ").append(item.title).append("\n");
        }

        Toast.makeText(getContext(), "Report Generated!", Toast.LENGTH_SHORT).show();
        tvLastScan.setText("Report ready");
    }

    /**
     * تحديث الواجهة
     */
    private void updateUI() {
        tvScannedCount.setText(String.valueOf(scannedCount));
        missingCount = expectedCount - scannedCount;
        if (missingCount < 0) missingCount = 0;
        tvMissingCount.setText(String.valueOf(missingCount));

        double accuracy = expectedCount > 0 ? ((double) scannedCount / expectedCount) * 100 : 0;
        tvAccuracy.setText("Accuracy: " + String.format("%.1f%%", accuracy));

        // Progress bar
        int progressPercent = expectedCount > 0 ? (scannedCount * 100) / expectedCount : 0;
        progressScanned.getLayoutParams().width = (progressPercent * rvInventoryItems.getWidth()) / 100;
        progressScanned.requestLayout();
    }

    /**
     * 🟢 نموذج عنصر الجرد
     */
    public static class InventoryItem {
        String serial;
        String title;
        String karat;
        String weight;
        String status; // ✓ or ✗
        String location;

        public InventoryItem(String serial, String title, String karat, String weight, String status, String location) {
            this.serial = serial;
            this.title = title;
            this.karat = karat;
            this.weight = weight;
            this.status = status;
            this.location = location;
        }
    }

    /**
     * 📋 Adapter للقائمة
     */
    private class InventoryAdapter extends RecyclerView.Adapter<InventoryAdapter.ViewHolder> {

        private List<InventoryItem> items;

        public InventoryAdapter(List<InventoryItem> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_inventory, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            InventoryItem item = items.get(position);
            holder.tvStatus.setText(item.status);
            holder.tvSerial.setText(item.serial);
            holder.tvTitle.setText(item.title);
            holder.tvDetails.setText(item.karat + " | " + item.weight);

            if ("✓".equals(item.status)) {
                holder.tvStatus.setTextColor(Color.parseColor("#22C55E"));
            } else {
                holder.tvStatus.setTextColor(Color.parseColor("#EF4444"));
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvStatus, tvSerial, tvTitle, tvDetails;
            ViewHolder(View v) {
                super(v);
                tvStatus = v.findViewById(R.id.tvItemStatus);
                tvSerial = v.findViewById(R.id.tvItemSerial);
                tvTitle = v.findViewById(R.id.tvItemTitle);
                tvDetails = v.findViewById(R.id.tvItemDetails);
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopTimer();
    }
}
