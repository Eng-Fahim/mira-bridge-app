package com.example.uhf.fragment;

import android.content.Context;
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
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MiraInventoryProFragment extends KeyDwonFragment {

    // UI Elements
    private TextView tvInventoryStatus, tvExpectedCount, tvScannedCount, tvVariance;
    private TextView tvAccuracy, tvElapsedTime, tvLocation, tvLastScan, tvRssi;
    private TextView tvLastAuditDate, tvLastAccuracy, tvHighValueCount;
    private TextView tvPrevItems, tvCurrItems, tvDeltaItems, tvHighValueList;
    private View progressScanned;
    private CardView cardComparison, cardHighValue;
    private Button btnStartInventory, btnBlindInventory, btnStopInventory, btnGenerateReport;
    private RecyclerView rvInventoryItems;

    // State Variables
    private boolean isScanning = false;
    private boolean isBlindMode = false;
    private int expectedCount = 0;
    private int scannedCount = 0;
    private int foundCount = 0;
    private int varianceCount = 0;
    private int prevTotalItems = 0;
    private int sessionId = 0;
    private long startTime = 0;

    // Data Lists
    private final List<InventoryItem> scannedItems = new ArrayList<>();
    private InventoryAdapter adapter;

    // Handlers
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable timerRunnable;
    private final Handler simulationHandler = new Handler(Looper.getMainLooper());
    private Runnable simulationRunnable;
    private int simulationIndex = 0;

    // Settings
    private boolean soundEnabled = true;
    private boolean vibrateEnabled = true;

    // API Config
    private static final String BASE_URL = "https://ams.ibreg.org/wp-json/mira-gate/v1";
    private static final String API_KEY = "mira_gate_test071234567890abcdefghijklmnop";

    private final String[] realGtins = {
        "070000000010", "070045537109", "070000000030", "070069121817",
        "070000000040", "070077306396", "070000000050", "070073594930",
        "0700000007927", "0700000007928", "0700000007929"
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_mira_inventory_pro, container, false);
        bindViews(v);
        setupRecyclerView();
        setupClickListeners();
        setInitialState();
        return v;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        loadInventorySettings();
        loadLastSnapshot();
        loadHighValueItems();
    }

    private void bindViews(View v) {
        tvInventoryStatus = v.findViewById(R.id.tvInventoryStatus);
        tvExpectedCount = v.findViewById(R.id.tvExpectedCount);
        tvScannedCount = v.findViewById(R.id.tvScannedCount);
        tvVariance = v.findViewById(R.id.tvVariance);
        tvAccuracy = v.findViewById(R.id.tvAccuracy);
        tvElapsedTime = v.findViewById(R.id.tvElapsedTime);
        tvLocation = v.findViewById(R.id.tvLocation);
        tvLastScan = v.findViewById(R.id.tvLastScan);
        tvRssi = v.findViewById(R.id.tvRssi);
        tvLastAuditDate = v.findViewById(R.id.tvLastAuditDate);
        tvLastAccuracy = v.findViewById(R.id.tvLastAccuracy);
        tvHighValueCount = v.findViewById(R.id.tvHighValueCount);
        tvPrevItems = v.findViewById(R.id.tvPrevItems);
        tvCurrItems = v.findViewById(R.id.tvCurrItems);
        tvDeltaItems = v.findViewById(R.id.tvDeltaItems);
        tvHighValueList = v.findViewById(R.id.tvHighValueList);
        progressScanned = v.findViewById(R.id.progressScanned);
        cardComparison = v.findViewById(R.id.cardComparison);
        cardHighValue = v.findViewById(R.id.cardHighValue);
        btnStartInventory = v.findViewById(R.id.btnStartInventory);
        btnBlindInventory = v.findViewById(R.id.btnBlindInventory);
        btnStopInventory = v.findViewById(R.id.btnStopInventory);
        btnGenerateReport = v.findViewById(R.id.btnGenerateReport);
        rvInventoryItems = v.findViewById(R.id.rvInventoryItems);
    }

    private void setupRecyclerView() {
        adapter = new InventoryAdapter(scannedItems);
        rvInventoryItems.setLayoutManager(new LinearLayoutManager(getContext()));
        rvInventoryItems.setAdapter(adapter);
    }

    private void setupClickListeners() {
        btnStartInventory.setOnClickListener(v -> startInventory(false));
        btnBlindInventory.setOnClickListener(v -> startInventory(true));
        btnStopInventory.setOnClickListener(v -> stopInventory());
        btnGenerateReport.setOnClickListener(v -> generateProReport());
    }

    private void setInitialState() {
        btnStartInventory.setEnabled(true);
        btnBlindInventory.setEnabled(true);
        btnStopInventory.setEnabled(false);
        btnGenerateReport.setEnabled(false);
    }

    private void loadLastSnapshot() {
        new Thread(() -> {
            try {
                URL url = new URL(BASE_URL + "/inventory/last-snapshot?location=Main%20Showroom");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("X-MIRA-API-Key", API_KEY);
                conn.setConnectTimeout(5000);

                if (conn.getResponseCode() == 200) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder(); String l;
                    while ((l = in.readLine()) != null) sb.append(l);
                    in.close();

                    JSONObject json = new JSONObject(sb.toString());
                    JSONObject snapshot = json.optJSONObject("snapshot");

                    if (snapshot != null && isAdded() && getActivity() != null) {
                        prevTotalItems = snapshot.optInt("total_items", 0);
                        String date = snapshot.optString("date", "-");

                        getActivity().runOnUiThread(() -> {
                            if (!isAdded()) return;
                            tvLastAuditDate.setText(date.length() > 10 ? date.substring(0, 10) : date);
                            tvLastAccuracy.setText(String.format(Locale.US, "%.1f%%", 98.1));
                            tvPrevItems.setText(String.valueOf(prevTotalItems));
                        });
                    }
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void loadHighValueItems() {
        new Thread(() -> {
            try {
                URL url = new URL(BASE_URL + "/inventory/high-value?location=Main%20Showroom&threshold=3000");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("X-MIRA-API-Key", API_KEY);

                if (conn.getResponseCode() == 200) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder(); String l;
                    while ((l = in.readLine()) != null) sb.append(l);
                    in.close();

                    JSONObject json = new JSONObject(sb.toString());
                    int count = json.optInt("count", 0);
                    JSONArray items = json.optJSONArray("items");

                    if (isAdded() && getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            if (!isAdded()) return;
                            tvHighValueCount.setText(String.valueOf(count));
                            if (count > 0 && items != null) {
                                StringBuilder list = new StringBuilder();
                                for (int i = 0; i < Math.min(3, items.length()); i++) {
                                    JSONObject item = items.optJSONObject(i);
                                    if (item != null) {
                                        list.append("• ").append(item.optString("item_title")).append("\n");
                                    }
                                }
                                tvHighValueList.setText(list.toString());
                            }
                        });
                    }
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void startInventory(boolean blindMode) {
        isBlindMode = blindMode;
        isScanning = true;
        startTime = System.currentTimeMillis();
        scannedItems.clear();
        scannedCount = 0;
        foundCount = 0;
        simulationIndex = 0;
        adapter.notifyDataSetChanged();

        btnStartInventory.setEnabled(false);
        btnBlindInventory.setEnabled(false);
        btnStopInventory.setEnabled(true);
        btnGenerateReport.setEnabled(false);

        tvInventoryStatus.setText("● SCANNING");
        tvInventoryStatus.setTextColor(Color.parseColor("#38BDF8"));

        playInventorySound("start");
        startSessionOnServer(blindMode);
        startTimer();
        startSimulation();

        String mode = blindMode ? "BLIND" : "FULL";
        if (getContext() != null) {
            Toast.makeText(getContext(), "🟢 " + mode + " Inventory Started", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopInventory() {
        isScanning = false;

        if (simulationRunnable != null) simulationHandler.removeCallbacks(simulationRunnable);

        playInventorySound("complete");

        btnStartInventory.setEnabled(true);
        btnBlindInventory.setEnabled(true);
        btnStopInventory.setEnabled(false);
        btnGenerateReport.setEnabled(true);

        tvInventoryStatus.setText("● COMPLETED");
        tvInventoryStatus.setTextColor(Color.parseColor("#22C55E"));

        stopTimer();
        closeSessionOnServer();

        varianceCount = expectedCount - foundCount;
        if (varianceCount < 0) varianceCount = 0;

        if (prevTotalItems > 0) {
            cardComparison.setVisibility(View.VISIBLE);
            tvCurrItems.setText(String.valueOf(foundCount));
            int delta = foundCount - prevTotalItems;
            tvDeltaItems.setText((delta >= 0 ? "+" : "") + delta);
            tvDeltaItems.setTextColor(delta >= 0 ? Color.parseColor("#22C55E") : Color.parseColor("#EF4444"));
        }

        updateUI();
        createSnapshotOnServer();

        if (getContext() != null) {
            Toast.makeText(getContext(), "✅ Complete: " + foundCount + "/" + expectedCount, Toast.LENGTH_LONG).show();
        }
    }

    private void startSessionOnServer(boolean blind) {
        new Thread(() -> {
            try {
                String endpoint = blind ? "/inventory/blind-start" : "/inventory/start-session";
                URL url = new URL(BASE_URL + endpoint);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-MIRA-API-Key", API_KEY);
                conn.setDoOutput(true);
                conn.setConnectTimeout(8000);

                JSONObject body = new JSONObject();
                body.put("location", "Main Showroom");
                body.put("type", blind ? "blind" : "full");
                conn.getOutputStream().write(body.toString().getBytes("utf-8"));

                if (conn.getResponseCode() == 200) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder(); String l;
                    while ((l = in.readLine()) != null) sb.append(l);
                    in.close();

                    JSONObject json = new JSONObject(sb.toString());
                    sessionId = json.optInt("session_id", 0);
                    expectedCount = json.optInt("expected_count", realGtins.length);

                    if (isAdded() && getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            if (!isAdded()) return;
                            if (!blind) tvExpectedCount.setText(String.valueOf(expectedCount));
                            else tvExpectedCount.setText("???");
                            tvLocation.setText("Main Showroom");
                            updateUI();
                        });
                    }
                }
            } catch (Exception e) {
                sessionId = (int) (System.currentTimeMillis() / 1000);
                expectedCount = realGtins.length;
                if (isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        tvExpectedCount.setText(isBlindMode ? "???" : String.valueOf(expectedCount));
                    });
                }
            }
        }).start();
    }

    private void closeSessionOnServer() {
        new Thread(() -> {
            try {
                URL url = new URL(BASE_URL + "/inventory/close-session");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-MIRA-API-Key", API_KEY);
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("session_id", sessionId);
                conn.getOutputStream().write(body.toString().getBytes("utf-8"));
                conn.getResponseCode();
            } catch (Exception ignored) {}
        }).start();
    }

    private void createSnapshotOnServer() {
        new Thread(() -> {
            try {
                URL url = new URL(BASE_URL + "/inventory/create-snapshot");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-MIRA-API-Key", API_KEY);
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("session_id", sessionId);
                body.put("location", "Main Showroom");
                conn.getOutputStream().write(body.toString().getBytes("utf-8"));
                conn.getResponseCode();
            } catch (Exception ignored) {}
        }).start();
    }

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
            String title = gtin; String karat = "-"; String weight = "-";
            String location = "Main Showroom"; String status = "✗"; boolean found = false;

            try {
                URL url = new URL(BASE_URL + "/authorize");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-MIRA-API-Key", API_KEY);
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);

                JSONObject body = new JSONObject();
                body.put("epc", gtin); body.put("gate_id", "handheld_c72");
                body.put("rssi", "-" + (40 + (int)(Math.random() * 30)));
                conn.getOutputStream().write(body.toString().getBytes("utf-8"));

                if (conn.getResponseCode() == 200) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder(); String l;
                    while ((l = in.readLine()) != null) sb.append(l); in.close();

                    JSONObject json = new JSONObject(sb.toString());
                    JSONObject item = json.optJSONObject("item");

                    if (item != null) {
                        title = item.optString("title", gtin);
                        karat = item.optString("karat", "-");
                        double w = item.optDouble("weight", 0);
                        weight = w > 0 ? String.format(Locale.US, "%.1fg", w) : "-";
                        location = item.optString("location", "Main Showroom");
                        String vs = item.optString("status", "");
                        if (vs.equals("in_stock") || vs.equals("available") || vs.equals("sold") || vs.equals("display") || vs.equals("reserved")) {
                            status = "✓"; found = true;
                        }
                    }
                }
            } catch (Exception e) {
                title = "Error"; status = "✗";
            }

            String fTitle = title, fKarat = karat, fWeight = weight, fLocation = location, fStatus = status;
            boolean fFound = found;

            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    scannedItems.add(0, new InventoryItem(gtin, fTitle, fKarat, fWeight, fStatus, fLocation));
                    playInventorySound("✓".equals(fStatus) ? "scan" : "alert");
                    scannedCount++;
                    if (fFound) foundCount++;
                    adapter.notifyItemInserted(0);
                    if (rvInventoryItems != null) {
                        rvInventoryItems.scrollToPosition(0);
                    }
                    tvLastScan.setText(gtin);
                    tvRssi.setText("-" + (40 + (int)(Math.random() * 30)) + " dBm");
                    tvScannedCount.setText(String.valueOf(scannedCount));
                    tvVariance.setText(String.valueOf(expectedCount - foundCount));
                    double acc = expectedCount > 0 ? (foundCount * 100.0 / expectedCount) : 0;
                    tvAccuracy.setText("Accuracy: " + String.format(Locale.US, "%.1f%%", acc));
                    if (rvInventoryItems != null && rvInventoryItems.getWidth() > 0) {
                        int pct = expectedCount > 0 ? (scannedCount * 100) / expectedCount : 0;
                        progressScanned.getLayoutParams().width = Math.max(1, (pct * rvInventoryItems.getWidth()) / 100);
                        progressScanned.requestLayout();
                    }
                });
            }
        }).start();
    }

    private void generateProReport() {
        if (getContext() == null) return;

        StringBuilder rows = new StringBuilder(); int count = 1;
        for (int i = scannedItems.size() - 1; i >= 0; i--) {
            InventoryItem item = scannedItems.get(i);
            String color = "✓".equals(item.status) ? "#22C55E" : "#EF4444";
            String icon = "✓".equals(item.status) ? "✅" : "⚠️";
            rows.append(String.format(Locale.US,
                "<tr><td>%d</td><td style='color:%s'>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>",
                count++, color, icon, item.serial, item.title, item.karat, item.weight));
        }
        double accVal = expectedCount > 0 ? (foundCount * 100.0 / expectedCount) : 0;
        String accColor = accVal >= 98 ? "#22C55E" : accVal >= 90 ? "#F59E0B" : "#EF4444";

        String html = "<!DOCTYPE html><html dir='rtl'><head><meta charset='UTF-8'><style>" +
            "@import url('https://fonts.googleapis.com/css2?family=Cairo:wght@400;600;700&display=swap');" +
            "body{font-family:'Cairo',sans-serif;color:#1a1a1a;padding:20px;direction:rtl;background:#fff}" +
            ".header{text-align:center;border-bottom:3px solid #1A237E;padding-bottom:15px;margin-bottom:20px}" +
            ".header h1{color:#1A237E;font-size:24px;margin:0}" +
            ".summary{display:flex;justify-content:space-around;margin:20px 0;gap:10px}" +
            ".summary-card{flex:1;text-align:center;padding:15px 10px;background:#f8f9fa;border-radius:12px}" +
            ".summary-card .val{font-size:26px;font-weight:700}" +
            ".summary-card .lbl{font-size:11px;color:#666;margin-top:4px}" +
            ".found .val{color:#22C55E} .variance .val{color:#EF4444} .accuracy .val{color:" + accColor + "}" +
            "table{width:100%;border-collapse:collapse;margin-top:20px;font-size:13px}" +
            "th{background:#1A237E;color:white;padding:10px 8px;text-align:center;font-size:12px}" +
            "td{padding:8px;border-bottom:1px solid #e0e0e0;text-align:center}" +
            "tr:nth-child(even){background:#f5f5f5}" +
            ".footer{text-align:center;margin-top:30px;padding-top:15px;border-top:1px solid #e0e0e0;color:#999;font-size:11px}" +
            "@media print{body{padding:0}}" +
            "</style></head><body>" +
            "<div style='text-align:center;font-weight:700;color:#1A237E;letter-spacing:2px'>🏷️ MIRA BRIDGE™</div>" +
            "<div class='header'><h1>📊 تقرير الجرد المتقدم</h1>" +
            "<div class='sub'>" + new SimpleDateFormat("yyyy-MM-dd | HH:mm:ss", new Locale("ar")).format(new Date()) + "</div>" +
            "<div class='sub'>📍 المعرض الرئيسي - صنعاء | نمط: " + (isBlindMode ? "جرد أعمى" : "جرد كامل") + "</div></div>" +
            "<div class='summary'>" +
            "<div class='summary-card'><div class='val'>" + expectedCount + "</div><div class='lbl'>العدد المتوقع</div></div>" +
            "<div class='summary-card found'><div class='val'>" + foundCount + "</div><div class='lbl'>✅ تم العثور</div></div>" +
            "<div class='summary-card variance'><div class='val'>" + varianceCount + "</div><div class='lbl'>❌ التفاوت</div></div>" +
            "<div class='summary-card accuracy'><div class='val'>" + String.format(Locale.US, "%.1f%%", accVal) + "</div><div class='lbl'>📊 الدقة</div></div>" +
            "</div>" +
            (prevTotalItems > 0 ? "<div style='text-align:center;margin:10px 0;padding:10px;background:#f0f0f0;border-radius:8px'>📊 مقارنة بالجرد السابق (" + tvLastAuditDate.getText() + "): <b>" + prevTotalItems + "</b> → <b>" + foundCount + "</b> (تفاوت: " + (foundCount - prevTotalItems >= 0 ? "+" : "") + (foundCount - prevTotalItems) + ")</div>" : "") +
            "<table><tr><th>#</th><th>الحالة</th><th>الرمز</th><th>القطعة</th><th>العيار</th><th>الوزن</th></tr>" + rows.toString() + "</table>" +
            "<div class='footer'><strong>MIRA Technology © 2026</strong><br>mira-id.com | تم الإنشاء بواسطة MIRA Bridge™ Pro<br>المدة: " + tvElapsedTime.getText() + "</div>" +
            "</body></html>";

        WebView webView = new WebView(requireContext());
        webView.getSettings().setJavaScriptEnabled(true);
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (getContext() == null) return;
                PrintManager pm = (PrintManager) requireContext().getSystemService(Context.PRINT_SERVICE);
                if (pm != null) {
                    PrintDocumentAdapter pda = view.createPrintDocumentAdapter("MIRA_Inventory_Pro_Report");
                    pm.print("MIRA Inventory Pro Report", pda, new PrintAttributes.Builder().build());
                }
            }
        });
        Toast.makeText(getContext(), "🖨️ Opening print dialog...", Toast.LENGTH_SHORT).show();
    }

    private void startTimer() {
        timerRunnable = () -> {
            if (!isAdded()) return;
            long e = System.currentTimeMillis() - startTime;
            tvElapsedTime.setText(String.format(Locale.US, "%02d:%02d:%02d", e/3600000, (e/60000)%60, (e/1000)%60));
            handler.postDelayed(timerRunnable, 1000);
        };
        handler.post(timerRunnable);
    }

    private void stopTimer() { 
        if (timerRunnable != null) {
            handler.removeCallbacks(timerRunnable); 
        }
    }

    private void updateUI() {
        tvScannedCount.setText(String.valueOf(scannedCount));
        tvVariance.setText(String.valueOf(varianceCount));
    }

    private void playInventorySound(String type) {
        if (!soundEnabled) return;
        try {
            if (getActivity() instanceof com.example.uhf.activity.UHFMainActivity) {
                com.example.uhf.activity.UHFMainActivity a = (com.example.uhf.activity.UHFMainActivity) getActivity();
                if (a == null) return;
                switch (type) {
                    case "start": safePlay(a, 1); break;
                    case "scan": safePlay(a, 1); break;
                    case "complete": safePlay(a, 1); safePlay(a, 1); break;
                    case "alert": safePlay(a, 2); break;
                }
            }
        } catch (Exception ignored) {}
        if (vibrateEnabled && getContext() != null) {
            try {
                android.os.Vibrator vib = (android.os.Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
                if (vib != null && vib.hasVibrator()) {
                    switch (type) {
                        case "start": vib.vibrate(200); break;
                        case "complete": vib.vibrate(new long[]{0,100,100,100,100,300}, -1); break;
                        case "scan": vib.vibrate(50); break;
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private void safePlay(com.example.uhf.activity.UHFMainActivity a, int id) { 
        try { a.playSound(id); } catch (Exception ignored) {} 
    }

    private void loadInventorySettings() {
        if (getContext() == null) return;
        android.content.SharedPreferences p = getContext().getSharedPreferences("MIRA_BRIDGE_SETTINGS", Context.MODE_PRIVATE);
        soundEnabled = "true".equals(p.getString("inventory_sound", "true"));
        vibrateEnabled = "true".equals(p.getString("inventory_vibrate", "true"));
    }

    @Override
    public void myOnKeyDwon() {
        // مطلوب من KeyDwonFragment
    }

    public static class InventoryItem {
        String serial, title, karat, weight, status, location;
        public InventoryItem(String s, String t, String k, String w, String st, String l) {
            serial=s; title=t; karat=k; weight=w; status=st; location=l;
        }
    }

    private class InventoryAdapter extends RecyclerView.Adapter<InventoryAdapter.VH> {
        private final List<InventoryItem> items;
        public InventoryAdapter(List<InventoryItem> i) { this.items = i; }
        
        @NonNull 
        @Override 
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
                s=v.findViewById(R.id.tvItemStatus); 
                serial=v.findViewById(R.id.tvItemSerial); 
                title=v.findViewById(R.id.tvItemTitle); 
                details=v.findViewById(R.id.tvItemDetails); 
            }
        }
    }

    @Override 
    public void onDestroyView() {
        isScanning = false;
        stopTimer();
        if (simulationRunnable != null) {
            simulationHandler.removeCallbacks(simulationRunnable);
        }
        handler.removeCallbacksAndMessages(null);
        simulationHandler.removeCallbacksAndMessages(null);
        
        if (rvInventoryItems != null) {
            rvInventoryItems.setAdapter(null);
        }
        super.onDestroyView();
    }
}
