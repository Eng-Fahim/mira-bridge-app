package com.example.uhf.fragment;

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

    // 🟢 إعدادات الجرد
    private boolean soundEnabled = true;
    private boolean vibrateEnabled = true;
    private boolean autoStopEnabled = false;
    private String inventoryMode = "Full";

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
    private final Handler simulationHandler = new Handler(Looper.getMainLooper());
    private Runnable simulationRunnable;
    private int simulationIndex = 0;

    // API
    private static final String BASE_URL = "https://ams.ibreg.org/wp-json/mira-gate/v1";
    private static final String API_KEY = "mira_gate_test071234567890abcdefghijklmnop";

    // 🟢 GTINs حقيقية من قاعدة البيانات
    private final String[] realGtins = {
        "070000000010", "070045537109", "070000000030", "070069121817",
        "070000000040", "070077306396", "070000000050", "070073594930",
        "0700000007927", "0700000007928", "0700000007929"
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_mira_inventory, container, false);

        // 🟢 تحميل إعدادات الجرد
        loadInventorySettings();

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

        // 🟢 الحالة الأولية للأزرار
        btnStartInventory.setEnabled(true);
        btnStopInventory.setEnabled(false);
        btnGenerateReport.setEnabled(false);

        // Click Listeners
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

        // 🟢 صوت + اهتزاز البدء
        playInventorySound("start");

        startSessionOnServer();
        startTimer();
        startSimulation();

        Toast.makeText(getContext(), "🟢 Inventory Started - " + realGtins.length + " items expected", Toast.LENGTH_SHORT).show();
    }

    // ============================================
    // 🔴 إيقاف جلسة الجرد
    // ============================================
    private void stopInventory() {
        isScanning = false;

        // 🟢 صوت + اهتزاز الاكتمال
        playInventorySound("complete");

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
        closeSessionOnServer();

        missingCount = expectedCount - foundCount;
        if (missingCount < 0) missingCount = 0;
        updateUI();

        Toast.makeText(getContext(), "✅ Complete: " + foundCount + "/" + expectedCount, Toast.LENGTH_LONG).show();
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
                queryMiraForInventory(gtin);
                simulationIndex++;
                int delay = 500 + (int)(Math.random() * 1000);
                simulationHandler.postDelayed(this, delay);
            }
        };
        simulationHandler.postDelayed(simulationRunnable, 500);
    }

    private void queryMiraForInventory(String gtin) {
    new Thread(() -> {
        String title = gtin;
        String karat = "-";
        String weight = "-";
        String location = "Main Showroom";
        String status = "✗";
        boolean found = false;

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

            if (conn.getResponseCode() == 200) {
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String l;
                while ((l = in.readLine()) != null) sb.append(l);
                in.close();

                JSONObject json = new JSONObject(sb.toString());
                JSONObject item = json.optJSONObject("item");

                if (item != null) {
                    title = item.optString("title", gtin);
                    karat = item.optString("karat", "-");
                    double w = item.optDouble("weight", 0);
                    weight = w > 0 ? String.format(Locale.US, "%.1fg", w) : "-";
                    location = item.optString("location", "Main Showroom");

                    String vaultStatus = item.optString("status", "");
                    if (vaultStatus.equals("in_stock") || vaultStatus.equals("available") ||
                        vaultStatus.equals("sold") || vaultStatus.equals("display") ||
                        vaultStatus.equals("reserved")) {
                        status = "✓";
                        found = true;
                    } else if (vaultStatus.equals("deleted")) {
                        status = "✗";
                        found = false;
                    } else {
                        status = "✓";
                        found = true;
                    }
                }
            }
        } catch (Exception e) {
            title = "Error";
            status = "✗";
            found = false;
        }

        String finalTitle = title;
        String finalKarat = karat;
        String finalWeight = weight;
        String finalLocation = location;
        String finalStatus = status;
        boolean finalFound = found;

        getActivity().runOnUiThread(() -> {
            scannedItems.add(0, new InventoryItem(gtin, finalTitle, finalKarat, finalWeight, finalStatus, finalLocation));

            if ("✓".equals(finalStatus)) {
                playInventorySound("scan");
            } else {
                playInventorySound("alert");
            }

            scannedCount++;
            if (finalFound) foundCount++;
            adapter.notifyItemInserted(0);
            rvInventoryItems.scrollToPosition(0);

            tvLastScan.setText(gtin);
            tvRssi.setText("-" + (40 + (int)(Math.random() * 30)) + " dBm");
            tvScannedCount.setText(String.valueOf(scannedCount));

            double acc = expectedCount > 0 ? (foundCount * 100.0 / expectedCount) : 0;
            tvAccuracy.setText("Accuracy: " + String.format(Locale.US, "%.1f%%", acc));

            if (rvInventoryItems.getWidth() > 0) {
                int pct = expectedCount > 0 ? (scannedCount * 100) / expectedCount : 0;
                progressScanned.getLayoutParams().width = Math.max(1, (pct * rvInventoryItems.getWidth()) / 100);
                progressScanned.requestLayout();
            }
        });

        recordScanOnServer(gtin, finalStatus);
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

        for (int i = scannedItems.size() - 1; i >= 0; i--) {
            InventoryItem item = scannedItems.get(i);
            String color = "✓".equals(item.status) ? "#22C55E" : "#EF4444";
            String icon = "✓".equals(item.status) ? "✅" : "⚠️";

            rows.append(String.format(Locale.US,
                "<tr>" +
                "<td style='text-align:center;'>%d</td>" +
                "<td style='text-align:center;color:%s;font-size:18px;'>%s</td>" +
                "<td style='direction:rtl;text-align:right;'>%s</td>" +
                "<td style='direction:rtl;text-align:right;'>%s</td>" +
                "<td style='text-align:center;'>%s</td>" +
                "<td style='text-align:center;'>%s</td>" +
                "</tr>",
                count++, color, icon, item.serial, item.title, item.karat, item.weight
            ));
        }

        double accuracyVal = expectedCount > 0 ? (foundCount * 100.0 / expectedCount) : 0;
        String accuracyColor = accuracyVal >= 98 ? "#22C55E" : accuracyVal >= 90 ? "#F59E0B" : "#EF4444";

        return "<!DOCTYPE html><html dir='rtl'><head><meta charset='UTF-8'>" +
            "<meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
            "<style>" +
            "@import url('https://fonts.googleapis.com/css2?family=Cairo:wght@400;600;700&display=swap');" +
            "body{font-family:'Cairo',sans-serif;color:#1a1a1a;padding:20px;direction:rtl;background:#fff}" +
            ".header{text-align:center;border-bottom:3px solid #1A237E;padding-bottom:15px;margin-bottom:20px}" +
            ".header h1{color:#1A237E;font-size:24px;margin:0}" +
            ".header .sub{color:#666;font-size:13px;margin-top:5px}" +
            ".summary{display:flex;justify-content:space-around;margin:20px 0;gap:10px}" +
            ".summary-card{flex:1;text-align:center;padding:15px 10px;background:#f8f9fa;border-radius:12px;border:1px solid #e0e0e0}" +
            ".summary-card .val{font-size:26px;font-weight:700}" +
            ".summary-card .lbl{font-size:11px;color:#666;margin-top:4px}" +
            ".found .val{color:#22C55E} .missing .val{color:#EF4444} .accuracy .val{color:" + accuracyColor + "}" +
            "table{width:100%;border-collapse:collapse;margin-top:20px;font-size:13px}" +
            "th{background:#1A237E;color:white;padding:10px 8px;text-align:center;font-size:12px}" +
            "td{padding:8px;border-bottom:1px solid #e0e0e0;text-align:center}" +
            "tr:nth-child(even){background:#f5f5f5}" +
            ".footer{text-align:center;margin-top:30px;padding-top:15px;border-top:1px solid #e0e0e0;color:#999;font-size:11px}" +
            ".logo{text-align:center;margin-bottom:10px;font-size:14px;font-weight:700;color:#1A237E;letter-spacing:2px}" +
            "@media print{body{padding:0}.summary-card{background:#fff!important}}" +
            "</style></head><body>" +
            "<div class='logo'>🏷️ MIRA BRIDGE™</div>" +
            "<div class='header'>" +
            "<h1>📊 تقرير الجرد</h1>" +
            "<div class='sub'>" + new SimpleDateFormat("yyyy-MM-dd  |  HH:mm:ss", new Locale("ar")).format(new Date()) + "</div>" +
            "<div class='sub'>📍 الموقع: المعرض الرئيسي - صنعاء</div>" +
            "</div>" +
            "<div class='summary'>" +
            "<div class='summary-card'><div class='val'>" + expectedCount + "</div><div class='lbl'>العدد المتوقع</div></div>" +
            "<div class='summary-card found'><div class='val'>" + foundCount + "</div><div class='lbl'>✅ تم العثور</div></div>" +
            "<div class='summary-card missing'><div class='val'>" + missingCount + "</div><div class='lbl'>❌ مفقود</div></div>" +
            "<div class='summary-card accuracy'><div class='val'>" + String.format(Locale.US, "%.1f%%", accuracyVal) + "</div><div class='lbl'>📊 الدقة</div></div>" +
            "</div>" +
            "<table>" +
            "<tr><th>#</th><th>الحالة</th><th>الرمز</th><th>القطعة</th><th>العيار</th><th>الوزن</th></tr>" +
            rows.toString() +
            "</table>" +
            "<div class='footer'>" +
            "<strong>MIRA Technology © 2026</strong><br>" +
            "mira-id.com | ams.ibreg.org<br>" +
            "تم الإنشاء بواسطة MIRA Bridge™<br>" +
            "المدة: " + tvElapsedTime.getText() +
            "</div>" +
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
            serial = s; title = t; karat = k; weight = w; status = st; location = l;
        }
    }

    private class InventoryAdapter extends RecyclerView.Adapter<InventoryAdapter.VH> {
        private final List<InventoryItem> items;
        public InventoryAdapter(List<InventoryItem> items) { this.items = items; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_inventory, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            InventoryItem item = items.get(pos);
            h.s.setText(item.status);
            h.serial.setText(item.serial);
            h.title.setText(item.title);
            h.details.setText(item.karat + " | " + item.weight);
            h.s.setTextColor("✓".equals(item.status) ? Color.parseColor("#22C55E") : Color.parseColor("#EF4444"));
        }

        @Override
        public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView s, serial, title, details;
            VH(View v) {
                super(v);
                s = v.findViewById(R.id.tvItemStatus);
                serial = v.findViewById(R.id.tvItemSerial);
                title = v.findViewById(R.id.tvItemTitle);
                details = v.findViewById(R.id.tvItemDetails);
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopTimer();
        if (simulationRunnable != null) simulationHandler.removeCallbacks(simulationRunnable);
    }

    // ============================================
    // 🟢 تحميل إعدادات الجرد
    // ============================================
    private void loadInventorySettings() {
        android.content.SharedPreferences prefs = getContext().getSharedPreferences("MIRA_BRIDGE_SETTINGS", android.content.Context.MODE_PRIVATE);
        soundEnabled = "true".equals(prefs.getString("inventory_sound", "true"));
        vibrateEnabled = "true".equals(prefs.getString("inventory_vibrate", "true"));
        autoStopEnabled = "true".equals(prefs.getString("inventory_auto_stop", "false"));
        inventoryMode = prefs.getString("inventory_mode", "Full");
    }

    // ============================================
    // 🔊 تشغيل صوت
    // ============================================
    private void playInventorySound(String type) {
    if (!soundEnabled) return;

    try {
        if (getActivity() instanceof com.example.uhf.activity.UHFMainActivity) {
            com.example.uhf.activity.UHFMainActivity activity =
                (com.example.uhf.activity.UHFMainActivity) getActivity();
            
            if (activity == null) return;  // 🟢 حماية

            switch (type) {
                case "start":
                    safePlaySound(activity, 1);
                    break;
                case "scan":
                    safePlaySound(activity, 1);
                    break;
                case "complete":
                    safePlaySound(activity, 1);
                    safePlaySound(activity, 1);
                    break;
                case "alert":
                    safePlaySound(activity, 2);
                    break;
            }
        }
    } catch (Exception e) {
        // تجاهل أي خطأ
    }

    // 🟢 اهتزاز
    if (vibrateEnabled && getContext() != null) {
        try {
            android.os.Vibrator vibrator = (android.os.Vibrator) getContext().getSystemService(android.content.Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                switch (type) {
                    case "start":
                        vibrator.vibrate(200);
                        break;
                    case "complete":
                        vibrator.vibrate(new long[]{0, 100, 100, 100, 100, 300}, -1);
                        break;
                    case "scan":
                        vibrator.vibrate(50);
                        break;
                }
            }
        } catch (Exception e) {
            // تجاهل
        }
    }
}

// 🟢 دالة آمنة لتشغيل الصوت
private void safePlaySound(com.example.uhf.activity.UHFMainActivity activity, int soundId) {
    try {
        activity.playSound(soundId);
    } catch (Exception e) {
        // الصوت غير متوفر - تجاهل
    }
}
}
