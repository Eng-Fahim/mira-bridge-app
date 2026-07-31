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

    // UI Elements
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
    private Handler simulationHandler = new Handler(Looper.getMainLooper());
    private Runnable simulationRunnable;
    private int simulationIndex = 0;

    // 🟢 قائمة القطع الاختبارية (GTINs حقيقية + اختبارية)
    private String[][] testItems = {
        {"070000000010", "Gold Ring 21K", "21K", "5.20g", "Main Showroom"},
        {"070000000030", "Gold Bracelet 18K", "18K", "12.80g", "Main Showroom"},
        {"070000000040", "Gold Necklace 24K", "24K", "25.00g", "Main Showroom"},
        {"070000000050", "Gold Earrings 21K", "21K", "3.20g", "Main Showroom"},
        {"0700000007927", "Gold Chain 18K", "18K", "15.50g", "Main Showroom"},
        {"0700000007928", "Gold Bangle 22K", "22K", "18.30g", "Main Showroom"},
        {"0700000007929", "Gold Pendant 21K", "21K", "8.10g", "Main Showroom"},
        {"070045537109", "Gold Coin 24K", "24K", "10.00g", "Main Showroom"},
        {"070069121817", "Gold Cufflinks 18K", "18K", "6.40g", "Main Showroom"},
        {"070073594930", "Gold Anklet 21K", "21K", "7.80g", "Main Showroom"},
        {"070077306396", "Gold Brooch 22K", "22K", "9.20g", "Main Showroom"},
        {"BWA-26000101", "21K Diamond Ring", "21K", "5.20g", "Main Showroom"},
    };

    private static final String API_URL = "https://ams.ibreg.org/wp-json/mira-gate/v1/authorize";
    private static final String API_KEY = "mira_gate_test071234567890abcdefghijklmnop";

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

    // ============================================
    // 🟢 بدء جلسة الجرد
    // ============================================
    private void startInventory() {
        isScanning = true;
        startTime = System.currentTimeMillis();
        scannedItems.clear();
        scannedCount = 0;
        simulationIndex = 0;
        adapter.notifyDataSetChanged();

        tvInventoryStatus.setText("● SCANNING");
        tvInventoryStatus.setTextColor(Color.parseColor("#38BDF8"));

        // 🟢 محاكاة - بيانات اختبارية
        expectedCount = testItems.length;
        sessionId = (int) (System.currentTimeMillis() / 1000);
        tvExpectedCount.setText(String.valueOf(expectedCount));
        tvLocation.setText("Main Showroom");
        updateUI();

        // 🟢 استدعاء API لبدء الجلسة
        startInventorySessionOnServer();

        // 🟢 بدء المؤقت
        startTimer();

        // 🟢 بدء المحاكاة
        startSimulation();

        Toast.makeText(getContext(), "🟢 Inventory Started - " + expectedCount + " items expected", Toast.LENGTH_SHORT).show();
    }

    // ============================================
    // 🔴 إيقاف جلسة الجرد
    // ============================================
    private void stopInventory() {
        isScanning = false;

        // إيقاف المحاكاة
        if (simulationRunnable != null) {
            simulationHandler.removeCallbacks(simulationRunnable);
        }

        tvInventoryStatus.setText("● IDLE");
        tvInventoryStatus.setTextColor(Color.parseColor("#22C55E"));

        stopTimer();

        // استدعاء API لإغلاق الجلسة
        closeInventorySessionOnServer();

        Toast.makeText(getContext(), "🔴 Inventory Stopped - " + scannedCount + " items scanned", Toast.LENGTH_SHORT).show();
    }

    // ============================================
    // 🟢 محاكاة المسح التلقائي
    // ============================================
    private void startSimulation() {
        simulationIndex = 0;
        simulationRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isScanning || simulationIndex >= testItems.length) {
                    if (isScanning) {
                        stopInventory();
                        Toast.makeText(getContext(), "✅ Simulation Complete: " + scannedCount + "/" + expectedCount + " items", Toast.LENGTH_LONG).show();
                    }
                    return;
                }

                String[] item = testItems[simulationIndex];
                String serial = item[0];
                String title = item[1];
                String karat = item[2];
                String weight = item[3];
                String location = item[4];

                // إضافة إلى القائمة
                scannedItems.add(0, new InventoryItem(serial, title, karat, weight, "✓", location));
                scannedCount++;
                adapter.notifyItemInserted(0);
                rvInventoryItems.scrollToPosition(0);

                // تحديث الواجهة
                tvLastScan.setText(serial);
                tvRssi.setText("-" + (40 + (int)(Math.random() * 30)) + " dBm");
                updateUI();

                // محاكاة استعلام MIRA ID
                simulateMiraQuery(serial, title, karat, weight);

                simulationIndex++;

                // تأخير عشوائي بين المسحات
                int delay = 600 + (int)(Math.random() * 900);
                simulationHandler.postDelayed(this, delay);
            }
        };

        simulationHandler.postDelayed(simulationRunnable, 800);
    }

    // ============================================
    // 🟢 محاكاة استعلام MIRA ID
    // ============================================
    private void simulateMiraQuery(String serial, String title, String karat, String weight) {
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
                body.put("epc", serial);
                body.put("gate_id", "handheld_c72");
                body.put("rssi", "-" + (40 + (int)(Math.random() * 30)));
                conn.getOutputStream().write(body.toString().getBytes("utf-8"));

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) sb.append(line);
                    in.close();

                    // 🟢 يمكن استخدام الرد لتحديث معلومات القطعة
                    JSONObject json = new JSONObject(sb.toString());
                    // Log response for debugging
                }
            } catch (Exception e) {
                // فشل الاتصال - يتم تجاهله في المحاكاة
            }
        }).start();
    }

    // ============================================
    // ☁️ بدء جلسة الجرد في MIRA ID
    // ============================================
    private void startInventorySessionOnServer() {
        new Thread(() -> {
            try {
                URL url = new URL("https://ams.ibreg.org/wp-json/mira-gate/v1/inventory/start-session");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-MIRA-API-Key", API_KEY);
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("location", "Main Showroom");
                body.put("type", "full");
                conn.getOutputStream().write(body.toString().getBytes("utf-8"));

                if (conn.getResponseCode() == 200) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) sb.append(line);
                    in.close();

                    JSONObject json = new JSONObject(sb.toString());
                    int serverSessionId = json.optInt("session_id", 0);
                    int serverExpected = json.optInt("expected_count", 0);

                    getActivity().runOnUiThread(() -> {
                        if (serverSessionId > 0) sessionId = serverSessionId;
                        if (serverExpected > 0) {
                            expectedCount = serverExpected;
                            tvExpectedCount.setText(String.valueOf(expectedCount));
                        }
                        updateUI();
                    });
                }
            } catch (Exception e) {
                // استخدام القيم المحلية
            }
        }).start();
    }

    // ============================================
    // ☁️ إغلاق جلسة الجرد
    // ============================================
    private void closeInventorySessionOnServer() {
        new Thread(() -> {
            try {
                URL url = new URL("https://ams.ibreg.org/wp-json/mira-gate/v1/inventory/close-session");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-MIRA-API-Key", API_KEY);
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("session_id", sessionId);
                conn.getOutputStream().write(body.toString().getBytes("utf-8"));

                if (conn.getResponseCode() == 200) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
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
                // تجاهل
            }
        }).start();
    }

    // ============================================
    // ⏱️ المؤقت
    // ============================================
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

    // ============================================
    // 📋 توليد تقرير
    // ============================================
    private void generateReport() {
        StringBuilder report = new StringBuilder();
        report.append("═══════════════════════════\n");
        report.append("  MIRA INVENTORY REPORT\n");
        report.append("═══════════════════════════\n");
        report.append("Date: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append("\n");
        report.append("Location: Main Showroom\n");
        report.append("───────────────────────────\n");
        report.append("Expected: ").append(expectedCount).append("\n");
        report.append("Scanned:  ").append(scannedCount).append("\n");
        report.append("Missing:  ").append(missingCount).append("\n");
        report.append("Accuracy: ").append(String.format("%.1f%%", expectedCount > 0 ? (scannedCount * 100.0 / expectedCount) : 0)).append("\n");
        report.append("───────────────────────────\n");
        report.append("Duration: ").append(tvElapsedTime.getText()).append("\n");
        report.append("═══════════════════════════\n\n");
        report.append("SCANNED ITEMS:\n");
        report.append("───────────────────────────\n");

        int count = 1;
        for (InventoryItem item : scannedItems) {
            report.append(String.format("%2d. %s | %s | %s | %s\n",
                count++, item.serial, item.title, item.karat, item.weight));
        }

        report.append("═══════════════════════════\n");
        report.append("MIRA Technology © 2026\n");
        report.append("mira-id.com\n");

        tvLastScan.setText("Report Generated ✓");
        Toast.makeText(getContext(), "📋 Report Generated!\n" + scannedCount + " items listed", Toast.LENGTH_LONG).show();

        // يمكن حفظ التقرير أو مشاركته
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
            getContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText("MIRA Report", report.toString());
        clipboard.setPrimaryClip(clip);
        Toast.makeText(getContext(), "📋 Report copied to clipboard!", Toast.LENGTH_SHORT).show();
    }

    // ============================================
    // 🟢 تحديث الواجهة
    // ============================================
    private void updateUI() {
        tvScannedCount.setText(String.valueOf(scannedCount));
        missingCount = expectedCount - scannedCount;
        if (missingCount < 0) missingCount = 0;
        tvMissingCount.setText(String.valueOf(missingCount));

        double accuracy = expectedCount > 0 ? ((double) scannedCount / expectedCount) * 100 : 0;
        tvAccuracy.setText("Accuracy: " + String.format("%.1f%%", accuracy));

        // Progress bar
        if (rvInventoryItems.getWidth() > 0) {
            int progressPercent = expectedCount > 0 ? (scannedCount * 100) / expectedCount : 0;
            int maxWidth = rvInventoryItems.getWidth();
            progressScanned.getLayoutParams().width = Math.max(1, (progressPercent * maxWidth) / 100);
            progressScanned.requestLayout();
        }
    }

    // ============================================
    // 📋 نموذج عنصر الجرد
    // ============================================
    public static class InventoryItem {
        String serial;
        String title;
        String karat;
        String weight;
        String status;
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

    // ============================================
    // 📋 Adapter للقائمة
    // ============================================
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
        if (simulationRunnable != null) {
            simulationHandler.removeCallbacks(simulationRunnable);
        }
    }
            }
