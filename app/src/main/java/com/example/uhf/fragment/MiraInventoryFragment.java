package com.example.uhf.fragment;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;

import org.json.JSONArray;
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
    private int foundCount = 0;
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

    // API
    private static final String BASE_URL = "https://ams.ibreg.org/wp-json/mira-gate/v1";
    private static final String API_KEY = "mira_gate_test071234567890abcdefghijklmnop";

    // 🟢 GTINs حقيقية من قاعدة البيانات
    private String[] realGtins = {
        "070000000010", "070000000030", "070000000040", "070000000050",
        "0700000007927", "0700000007928", "0700000007929",
        "070045537109", "070069121817", "070073594930", "070077306396"
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_mira_inventory, container, false);

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

        adapter = new InventoryAdapter(scannedItems);
        rvInventoryItems.setLayoutManager(new LinearLayoutManager(getContext()));
        rvInventoryItems.setAdapter(adapter);

        // 🟢 الحالة الأولية
        btnStartInventory.setEnabled(true);
        btnStopInventory.setEnabled(false);
        btnGenerateReport.setEnabled(false);
        
        btnStartInventory.setOnClickListener(view -> startInventory());
        btnStopInventory.setOnClickListener(view -> stopInventory());
        btnGenerateReport.setOnClickListener(view -> generatePrintableReport());

        updateUI();

        return v;
    }

    // ============================================
    // 🟢 بدء جلسة الجرد
    // ============================================
    private void startInventory() {
        // 🟢 تعطيل الأزرار المناسبة
        btnStartInventory.setEnabled(false);
        btnStartInventory.setText("● SCANNING...");
        btnStopInventory.setEnabled(true);
        btnGenerateReport.setEnabled(false);

        isScanning = true;
        startTime = System.currentTimeMillis();
        scannedItems.clear();
        scannedCount = 0;
        foundCount = 0;
        simulationIndex = 0;
        adapter.notifyDataSetChanged();

        tvInventoryStatus.setText("● SCANNING");
        tvInventoryStatus.setTextColor(Color.parseColor("#38BDF8"));

        // 🟢 بدء جلسة على السيرفر
        startSessionOnServer();

        // 🟢 بدء المؤقت
        startTimer();

        // 🟢 بدء المحاكاة
        startSimulation();
    }

    // ============================================
    // 🔴 إيقاف جلسة الجرد
    // ============================================
    private void stopInventory() {
        isScanning = false;

        if (simulationRunnable != null) {
            simulationHandler.removeCallbacks(simulationRunnable);
        }

        btnStartInventory.setEnabled(true);
        btnStartInventory.setText("▶ START");
        btnStopInventory.setEnabled(false);
        btnGenerateReport.setEnabled(true);

        tvInventoryStatus.setText("● COMPLETED");
        tvInventoryStatus.setTextColor(Color.parseColor("#22C55E"));

        stopTimer();

        // 🟢 إغلاق الجلسة على السيرفر
        closeSessionOnServer();

        // 🟢 تحديث missing count
        missingCount = expectedCount - foundCount;
        if (missingCount < 0) missingCount = 0;
        updateUI();

        Toast.makeText(getContext(), "✅ Inventory Complete: " + foundCount + "/" + expectedCount, Toast.LENGTH_LONG).show();
    }

    // ============================================
    // ☁️ بدء الجلسة على السيرفر
    // ============================================
    private void startSessionOnServer() {
        new Thread(() -> {
            try {
                URL url = new URL(BASE_URL + "/inventory/start-session");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-MIRA-API-Key", API_KEY);
                conn.setDoOutput(true);
                conn.setConnectTimeout(8000);

                JSONObject body = new JSONObject();
                body.put("location", "Main Showroom");
                body.put("type", "full");
                conn.getOutputStream().write(body.toString().getBytes("utf-8"));

                if (conn.getResponseCode() == 200) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String l;
                    while ((l = in.readLine()) != null) sb.append(l);
                    in.close();

                    JSONObject json = new JSONObject(sb.toString());
                    sessionId = json.optInt("session_id", 0);
                    expectedCount = json.optInt("expected_count", realGtins.length);

                    getActivity().runOnUiThread(() -> {
                        tvExpectedCount.setText(String.valueOf(expectedCount));
                        tvLocation.setText("Main Showroom");
                        updateUI();
                    });
                }
            } catch (Exception e) {
                // استخدام القيم المحلية
                sessionId = (int) (System.currentTimeMillis() / 1000);
                expectedCount = realGtins.length;
                getActivity().runOnUiThread(() -> {
                    tvExpectedCount.setText(String.valueOf(expectedCount));
                    updateUI();
                });
            }
        }).start();
    }

    // ============================================
    // ☁️ إغلاق الجلسة على السيرفر
    // ============================================
    private void closeSessionOnServer() {
        new Thread(() -> {
            try {
                URL url = new URL(BASE_URL + "/inventory/close-session");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-MIRA-API-Key", API_KEY);
                conn.setDoOutput(true);
                conn.setConnectTimeout(8000);

                JSONObject body = new JSONObject();
                body.put("session_id", sessionId);
                conn.getOutputStream().write(body.toString().getBytes("utf-8"));

                if (conn.getResponseCode() == 200) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String l;
                    while ((l = in.readLine()) != null) sb.append(l);
                    in.close();

                    JSONObject json = new JSONObject(sb.toString());
                    JSONObject report = json.optJSONObject("report");
                    if (report != null) {
                        double acc = report.optDouble("accuracy", 0);
                        getActivity().runOnUiThread(() -> {
                            tvAccuracy.setText("Accuracy: " + acc + "%");
                            updateUI();
                        });
                    }
                }
            } catch (Exception e) {
                getActivity().runOnUiThread(() -> updateUI());
            }
        }).start();
    }

    // ============================================
    // 🤖 محاكاة المسح - مع استعلام MIRA ID
    // ============================================
    private void startSimulation() {
        simulationIndex = 0;
        simulationRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isScanning || simulationIndex >= realGtins.length) {
                    if (isScanning) stopInventory();
                    return;
                }

                String gtin = realGtins[simulationIndex];
                queryMiraForInventory(gtin, simulationIndex);
                simulationIndex++;

                int delay = 500 + (int)(Math.random() * 1000);
                simulationHandler.postDelayed(this, delay);
            }
        };
        simulationHandler.postDelayed(simulationRunnable, 500);
    }

    private void queryMiraForInventory(String gtin, int index) {
        new Thread(() -> {
            try {
                URL url = new URL(BASE_URL + "/authorize");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-MIRA-API-Key", API_KEY);
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);

                JSONObject body = new JSONObject();
                body.put("epc", gtin);
                body.put("gate_id", "handheld_c72");
                body.put("rssi", "-" + (40 + (int)(Math.random() * 30)));
                conn.getOutputStream().write(body.toString().getBytes("utf-8"));

                String title = "Item " + gtin.substring(gtin.length() - 4);
                String karat = "21K";
                String weight = "5.20g";
                String location = "Main Showroom";
                String status = "✓";
                boolean found = true;

                if (conn.getResponseCode() == 200) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String l;
                    while ((l = in.readLine()) != null) sb.append(l);
                    in.close();

                    JSONObject json = new JSONObject(sb.toString());
                    JSONObject item = json.optJSONObject("item");
                    JSONObject decision = json.optJSONObject("decision");

                    if (item != null) {
                        title = item.optString("title", title);
                        karat = item.optString("karat", karat);
                        weight = item.optString("weight", weight);
                        location = item.optString("location", location);
                        status = decision != null && decision.optBoolean("allowed", false) ? "✓" : "⚠️";
                        found = true;
                    } else {
                        status = "✗";
                        found = false;
                    }
                }

                String finalTitle = title;
                String finalKarat = karat;
                String finalWeight = weight;
                String finalLocation = location;
                String finalStatus = status;
                boolean finalFound = found;

                getActivity().runOnUiThread(() -> {
                    scannedItems.add(0, new InventoryItem(gtin, finalTitle, finalKarat, finalWeight, finalStatus, finalLocation));
                    scannedCount++;
                    if (finalFound) foundCount++;
                    adapter.notifyItemInserted(0);
                    rvInventoryItems.scrollToPosition(0);

                    tvLastScan.setText(gtin);
                    tvRssi.setText("-" + (40 + (int)(Math.random() * 30)) + " dBm");
                    tvScannedCount.setText(String.valueOf(scannedCount));
                    
                    double acc = expectedCount > 0 ? (foundCount * 100.0 / expectedCount) : 0;
                    tvAccuracy.setText("Accuracy: " + String.format("%.1f%%", acc));
                    
                    if (rvInventoryItems.getWidth() > 0) {
                        int pct = expectedCount > 0 ? (scannedCount * 100) / expectedCount : 0;
                        progressScanned.getLayoutParams().width = Math.max(1, (pct * rvInventoryItems.getWidth()) / 100);
                        progressScanned.requestLayout();
                    }
                });

                // 🟢 تسجيل في session_items على السيرفر
                recordScanOnServer(gtin, finalStatus);

            } catch (Exception e) {
                getActivity().runOnUiThread(() -> {
                    scannedItems.add(0, new InventoryItem(gtin, "Unknown", "-", "-", "✗", "Unknown"));
                    scannedCount++;
                    adapter.notifyItemInserted(0);
                    tvLastScan.setText(gtin + " (offline)");
                });
            }
        }).start();
    }

    private void recordScanOnServer(String code, String status) {
        new Thread(() -> {
            try {
                URL url = new URL(BASE_URL + "/inventory/scan-item");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-MIRA-API-Key", API_KEY);
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);

                JSONObject body = new JSONObject();
                body.put("session_id", sessionId);
                body.put("code", code);
                body.put("rssi", "-" + (40 + (int)(Math.random() * 30)));
                conn.getOutputStream().write(body.toString().getBytes("utf-8"));
                conn.getResponseCode();
            } catch (Exception ignored) {}
        }).start();
    }

    // ============================================
    // 📋 تقرير قابل للطباعة
    // ============================================
    private void generatePrintableReport() {
        String reportHtml = buildReportHtml();
        
        WebView webView = new WebView(getContext());
        webView.getSettings().setJavaScriptEnabled(true);
        webView.loadDataWithBaseURL(null, reportHtml, "text/html", "UTF-8", null);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                PrintManager printManager = (PrintManager) getContext().getSystemService(getContext().PRINT_SERVICE);
                PrintDocumentAdapter printAdapter = view.createPrintDocumentAdapter("MIRA_Inventory_Report");
                printManager.print("MIRA Inventory Report", printAdapter, new PrintAttributes.Builder().build());
            }
        });
        
        Toast.makeText(getContext(), "🖨️ Opening print dialog...", Toast.LENGTH_SHORT).show();
    }

    private String buildReportHtml() {
        StringBuilder rows = new StringBuilder();
        int count = 1;
        for (InventoryItem item : scannedItems) {
            String color = "✓".equals(item.status) ? "#22C55E" : "#EF4444";
            rows.append(String.format(
                "<tr><td>%d</td><td style='color:%s'>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>",
                count++, color, item.status, item.serial, item.title, item.karat, item.weight));
        }

        return "<!DOCTYPE html><html dir='ltr'><head><meta charset='UTF-8'>" +
            "<style>body{font-family:'Cairo',sans-serif;color:#1a1a1a;padding:20px}" +
            "h1{color:#1A237E;text-align:center;border-bottom:3px solid #1A237E;padding-bottom:10px}" +
            ".summary{display:flex;justify-content:space-around;margin:20px 0}" +
            ".summary div{text-align:center;padding:10px 20px;background:#f5f5f5;border-radius:8px}" +
            ".summary .val{font-size:24px;font-weight:bold;color:#1A237E}" +
            "table{width:100%;border-collapse:collapse;margin-top:20px}" +
            "th{background:#1A237E;color:white;padding:10px;text-align:left}" +
            "td{padding:8px;border-bottom:1px solid #ddd}" +
            "tr:nth-child(even){background:#f9f9f9}" +
            ".footer{text-align:center;margin-top:30px;color:#666;font-size:12px}" +
            "</style></head><body>" +
            "<h1>📊 MIRA Inventory Report</h1>" +
            "<p style='text-align:center'>Date: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + " | Location: Main Showroom</p>" +
            "<div class='summary'>" +
            "<div><div class='val'>" + expectedCount + "</div>Expected</div>" +
            "<div><div class='val'>" + foundCount + "</div>Found</div>" +
            "<div><div class='val'>" + missingCount + "</div>Missing</div>" +
            "<div><div class='val'>" + String.format("%.1f%%", expectedCount > 0 ? (foundCount * 100.0 / expectedCount) : 0) + "</div>Accuracy</div>" +
            "</div>" +
            "<table><tr><th>#</th><th>Status</th><th>GTIN/Serial</th><th>Item</th><th>Karat</th><th>Weight</th></tr>" +
            rows.toString() +
            "</table>" +
            "<div class='footer'>MIRA Technology © 2026 | mira-id.com | Generated by MIRA Bridge™</div>" +
            "</body></html>";
    }

    // ============================================
    // ⏱️ المؤقت
    // ============================================
    private void startTimer() {
        timerRunnable = () -> {
            long elapsed = System.currentTimeMillis() - startTime;
            long s = (elapsed / 1000) % 60;
            long m = (elapsed / (1000 * 60)) % 60;
            long h = (elapsed / (1000 * 60 * 60)) % 24;
            tvElapsedTime.setText(String.format("%02d:%02d:%02d", h, m, s));
            handler.postDelayed(timerRunnable, 1000);
        };
        handler.post(timerRunnable);
    }

    private void stopTimer() {
        if (timerRunnable != null) handler.removeCallbacks(timerRunnable);
    }

    private void updateUI() {
        tvScannedCount.setText(String.valueOf(scannedCount));
        tvMissingCount.setText(String.valueOf(missingCount));
    }

    // ============================================
    // 📋 Model & Adapter
    // ============================================
    public static class InventoryItem {
        String serial, title, karat, weight, status, location;
        public InventoryItem(String s, String t, String k, String w, String st, String l) {
            serial=s; title=t; karat=k; weight=w; status=st; location=l;
        }
    }

    private class InventoryAdapter extends RecyclerView.Adapter<InventoryAdapter.VH> {
        private List<InventoryItem> items;
        public InventoryAdapter(List<InventoryItem> items) { this.items = items; }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_inventory, p, false));
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            InventoryItem item = items.get(pos);
            h.s.setText(item.status);
            h.serial.setText(item.serial);
            h.title.setText(item.title);
            h.details.setText(item.karat + " | " + item.weight);
            h.s.setTextColor("✓".equals(item.status) ? Color.parseColor("#22C55E") : Color.parseColor("#EF4444"));
        }
        @Override public int getItemCount() { return items.size(); }
        class VH extends RecyclerView.ViewHolder {
            TextView s, serial, title, details;
            VH(View v) { super(v); s=v.findViewById(R.id.tvItemStatus); serial=v.findViewById(R.id.tvItemSerial); title=v.findViewById(R.id.tvItemTitle); details=v.findViewById(R.id.tvItemDetails); }
        }
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        stopTimer();
        if (simulationRunnable != null) simulationHandler.removeCallbacks(simulationRunnable);
    }
                }
