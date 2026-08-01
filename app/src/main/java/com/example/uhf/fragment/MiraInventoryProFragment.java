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

    private TextView tvInventoryStatus, tvExpectedCount, tvScannedCount, tvVariance;
    private TextView tvAccuracy, tvElapsedTime, tvLocation, tvLastScan, tvRssi;
    private TextView tvLastAuditDate, tvLastAccuracy, tvHighValueCount;
    private TextView tvPrevItems, tvCurrItems, tvDeltaItems, tvHighValueList;
    private View progressScanned;
    private CardView cardComparison, cardHighValue;
    private Button btnStartInventory, btnBlindInventory, btnStopInventory, btnGenerateReport;
    private RecyclerView rvInventoryItems;

    private boolean isScanning = false;
    private boolean isBlindMode = false;
    private int expectedCount = 11;
    private int scannedCount = 0;
    private int foundCount = 0;
    private int varianceCount = 0;
    private int prevTotalItems = 10; // قيمة افتراضية أولية
    private int sessionId = 0;
    private long startTime = 0;

    private final List<InventoryItem> scannedItems = new ArrayList<>();
    private InventoryAdapter adapter;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable timerRunnable;
    private final Handler simulationHandler = new Handler(Looper.getMainLooper());
    private Runnable simulationRunnable;
    private int simulationIndex = 0;

    private boolean soundEnabled = true;
    private boolean vibrateEnabled = true;

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
        if (rvInventoryItems != null) {
            rvInventoryItems.setLayoutManager(new LinearLayoutManager(getContext()));
            rvInventoryItems.setAdapter(adapter);
        }
    }

    private void setupClickListeners() {
        if (btnStartInventory != null) btnStartInventory.setOnClickListener(v -> startInventory(false));
        if (btnBlindInventory != null) btnBlindInventory.setOnClickListener(v -> startInventory(true));
        if (btnStopInventory != null) btnStopInventory.setOnClickListener(v -> stopInventory());
        if (btnGenerateReport != null) btnGenerateReport.setOnClickListener(v -> generateProReport());
    }

    private void setInitialState() {
        if (btnStartInventory != null) btnStartInventory.setEnabled(true);
        if (btnBlindInventory != null) btnBlindInventory.setEnabled(true);
        if (btnStopInventory != null) btnStopInventory.setEnabled(false);
        if (btnGenerateReport != null) btnGenerateReport.setEnabled(false);
        if (tvExpectedCount != null) tvExpectedCount.setText(String.valueOf(realGtins.length));
        
        // إظهار بطاقة الـ High Value مسبقاً في حال توفرها
        if (cardHighValue != null) cardHighValue.setVisibility(View.VISIBLE);
    }

    private void loadLastSnapshot() {
        new Thread(() -> {
            int loadedPrevItems = 10;
            String loadedDate = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());

            try {
                URL url = new URL(BASE_URL + "/inventory/last-snapshot?location=Main%20Showroom");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("X-MIRA-API-Key", API_KEY);
                conn.setConnectTimeout(2500);

                if (conn.getResponseCode() == 200) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder(); String l;
                    while ((l = in.readLine()) != null) sb.append(l);
                    in.close();

                    JSONObject json = new JSONObject(sb.toString());
                    JSONObject snapshot = json.optJSONObject("snapshot");

                    if (snapshot != null) {
                        loadedPrevItems = snapshot.optInt("total_items", 10);
                        loadedDate = snapshot.optString("date", loadedDate);
                    }
                }
            } catch (Exception ignored) {
                // الاعتماد على القيم الافتراضية
            }

            final int finalItems = loadedPrevItems;
            final String finalDate = loadedDate;

            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    prevTotalItems = finalItems;
                    if (tvLastAuditDate != null) tvLastAuditDate.setText(finalDate.length() > 10 ? finalDate.substring(0, 10) : finalDate);
                    if (tvLastAccuracy != null) tvLastAccuracy.setText("98.1%");
                    if (tvPrevItems != null) tvPrevItems.setText(String.valueOf(prevTotalItems));
                });
            }
        }).start();
    }

    private void loadHighValueItems() {
        new Thread(() -> {
            try {
                URL url = new URL(BASE_URL + "/inventory/high-value?location=Main%20Showroom&threshold=3000");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("X-MIRA-API-Key", API_KEY);
                conn.setConnectTimeout(2500);

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
                            if (tvHighValueCount != null) tvHighValueCount.setText(String.valueOf(count));
                            if (count > 0 && items != null && tvHighValueList != null) {
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
        if (adapter != null) adapter.notifyDataSetChanged();

        if (btnStartInventory != null) btnStartInventory.setEnabled(false);
        if (btnBlindInventory != null) btnBlindInventory.setEnabled(false);
        if (btnStopInventory != null) btnStopInventory.setEnabled(true);
        if (btnGenerateReport != null) btnGenerateReport.setEnabled(false);

        // إخفاء بطاقة المقارنة حتى اكتمال عملية الجرد
        if (cardComparison != null) cardComparison.setVisibility(View.GONE);

        if (tvInventoryStatus != null) {
            tvInventoryStatus.setText("● SCANNING");
            tvInventoryStatus.setTextColor(Color.parseColor("#38BDF8"));
        }

        playInventorySound("start");
        startSessionOnServer(blindMode);
        startTimer();
        startSimulation();

        if (getContext() != null) {
            Toast.makeText(getContext(), "🟢 " + (blindMode ? "BLIND" : "FULL") + " Inventory Started", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopInventory() {
        isScanning = false;

        if (simulationRunnable != null) simulationHandler.removeCallbacks(simulationRunnable);

        playInventorySound("complete");

        if (btnStartInventory != null) btnStartInventory.setEnabled(true);
        if (btnBlindInventory != null) btnBlindInventory.setEnabled(true);
        if (btnStopInventory != null) btnStopInventory.setEnabled(false);
        if (btnGenerateReport != null) btnGenerateReport.setEnabled(true);

        if (tvInventoryStatus != null) {
            tvInventoryStatus.setText("● COMPLETED");
            tvInventoryStatus.setTextColor(Color.parseColor("#22C55E"));
        }

        stopTimer();
        closeSessionOnServer();

        varianceCount = Math.max(0, expectedCount - foundCount);

        // --- إظهار بطاقة الـ Snapshot والمقارنة بضمان مؤكد ---
        if (cardComparison != null) {
            cardComparison.setVisibility(View.VISIBLE);
            if (tvCurrItems != null) tvCurrItems.setText(String.valueOf(foundCount));
            if (tvPrevItems != null) tvPrevItems.setText(String.valueOf(prevTotalItems));
            
            int delta = foundCount - prevTotalItems;
            if (tvDeltaItems != null) {
                tvDeltaItems.setText((delta >= 0 ? "+" : "") + delta);
                tvDeltaItems.setTextColor(delta >= 0 ? Color.parseColor("#22C55E") : Color.parseColor("#EF4444"));
            }
        }

        updateUI();
        createSnapshotOnServer();

        if (getContext() != null) {
            Toast.makeText(getContext(), "✅ Complete: " + foundCount + "/" + expectedCount, Toast.LENGTH_LONG).show();
        }
    }

    private void startSessionOnServer(boolean blind) {
        expectedCount = realGtins.length;
        if (tvExpectedCount != null) {
            tvExpectedCount.setText(blind ? "???" : String.valueOf(expectedCount));
        }
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
                simulationHandler.postDelayed(this, 700);
            }
        };
        simulationHandler.postDelayed(simulationRunnable, 300);
    }

    private void queryMiraForInventory(String gtin) {
        new Thread(() -> {
            String title = "قطعة ذهب - " + gtin.substring(gtin.length() - 4);
            String karat = "21K"; 
            String weight = String.format(Locale.US, "%.2fg", 5.0 + (Math.random() * 10));
            String location = "Main Showroom"; 
            String status = "✓"; 
            boolean found = true;

            try {
                URL url = new URL(BASE_URL + "/authorize");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-MIRA-API-Key", API_KEY);
                conn.setDoOutput(true);
                conn.setConnectTimeout(2000);

                JSONObject body = new JSONObject();
                body.put("epc", gtin); 
                body.put("gate_id", "handheld_c72");
                body.put("rssi", "-" + (40 + (int)(Math.random() * 30)));
                conn.getOutputStream().write(body.toString().getBytes("utf-8"));

                if (conn.getResponseCode() == 200) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder(); String l;
                    while ((l = in.readLine()) != null) sb.append(l); in.close();

                    JSONObject json = new JSONObject(sb.toString());
                    JSONObject item = json.optJSONObject("item");

                    if (item != null) {
                        title = item.optString("title", title);
                        karat = item.optString("karat", karat);
                        double w = item.optDouble("weight", 0);
                        if (w > 0) weight = String.format(Locale.US, "%.2fg", w);
                    }
                }
            } catch (Exception ignored) {}

            String fTitle = title, fKarat = karat, fWeight = weight, fLocation = location, fStatus = status;
            boolean fFound = found;

            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    scannedItems.add(0, new InventoryItem(gtin, fTitle, fKarat, fWeight, fStatus, fLocation));
                    playInventorySound("scan");
                    scannedCount++;
                    if (fFound) foundCount++;
                    
                    if (adapter != null) {
                        adapter.notifyItemInserted(0);
                    }
                    if (rvInventoryItems != null) {
                        rvInventoryItems.scrollToPosition(0);
                    }
                    
                    if (tvLastScan != null) tvLastScan.setText(gtin);
                    if (tvRssi != null) tvRssi.setText("-" + (40 + (int)(Math.random() * 30)) + " dBm");
                    if (tvScannedCount != null) tvScannedCount.setText(String.valueOf(scannedCount));
                    if (tvVariance != null) tvVariance.setText(String.valueOf(Math.max(0, expectedCount - foundCount)));
                    
                    double acc = expectedCount > 0 ? (foundCount * 100.0 / expectedCount) : 0;
                    if (tvAccuracy != null) tvAccuracy.setText(String.format(Locale.US, "%.1f%%", acc));
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
            "<table><tr><th>#</th><th>الحالة</th><th>الرمز</th><th>القطعة</th><th>العيار</th><th>الوزن</th></tr>" + rows.toString() + "</table>" +
            "<div class='footer'><strong>MIRA Technology © 2026</strong><br>mira-id.com | تم الإنشاء بواسطة MIRA Bridge™ Pro</div>" +
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
        Toast.makeText(getContext(), "🖨️ فتح نافذة الطباعة...", Toast.LENGTH_SHORT).show();
    }

    private void startTimer() {
        timerRunnable = () -> {
            if (!isAdded()) return;
            long e = System.currentTimeMillis() - startTime;
            if (tvElapsedTime != null) {
                tvElapsedTime.setText(String.format(Locale.US, "%02d:%02d:%02d", e/3600000, (e/60000)%60, (e/1000)%60));
            }
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
        if (tvScannedCount != null) tvScannedCount.setText(String.valueOf(scannedCount));
        if (tvVariance != null) tvVariance.setText(String.valueOf(varianceCount));
    }

    private void playInventorySound(String type) {
        if (!soundEnabled) return;
        if (vibrateEnabled && getContext() != null) {
            try {
                android.os.Vibrator vib = (android.os.Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
                if (vib != null && vib.hasVibrator()) {
                    if ("scan".equals(type)) vib.vibrate(40);
                }
            } catch (Exception ignored) {}
        }
    }

    private void loadInventorySettings() {
        if (getContext() == null) return;
        android.content.SharedPreferences p = getContext().getSharedPreferences("MIRA_BRIDGE_SETTINGS", Context.MODE_PRIVATE);
        soundEnabled = "true".equals(p.getString("inventory_sound", "true"));
        vibrateEnabled = "true".equals(p.getString("inventory_vibrate", "true"));
    }

    @Override
    public void myOnKeyDwon() {
        if (!isScanning) {
            startInventory(false);
        } else {
            stopInventory();
        }
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
            if (h.s != null) {
                h.s.setText(item.status);
                h.s.setTextColor("✓".equals(item.status) ? Color.parseColor("#22C55E") : Color.parseColor("#EF4444"));
            }
            if (h.serial != null) h.serial.setText(item.serial); 
            if (h.title != null) h.title.setText(item.title);
            if (h.details != null) h.details.setText(item.karat + " | " + item.weight);
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
