package com.example.uhf.fragment;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.util.Log;
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
import com.example.uhf.api.MiraApiClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MiraInventoryProFragment extends KeyDwonFragment {

    private static final String TAG = "MiraInventoryPro";

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
    private int prevTotalItems = 0; // القيمة الافتراضية للجرد السابق
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

    private MiraApiClient apiClient;

    private final String[] realGtins = {
        "170000000010", "170045537109", "170000000030", "170069121817",
        "170000000040", "170077306396", "170000000050", "170073594930",
        "1700000007927", "1700000007928", "1700000007929"
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_mira_inventory_pro, container, false);
        apiClient = MiraApiClient.getInstance(requireContext());
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
        if (cardHighValue != null) cardHighValue.setVisibility(View.VISIBLE);
        if (cardComparison != null) cardComparison.setVisibility(View.VISIBLE); // 🟢 إظهار بطاقة المقارنة دائماً
    }

    // ===================== API Calls (قراءة وسحب بيانات الجرد) =====================

    private void loadLastSnapshot() {
        new Thread(() -> {
            int loadedPrevItems = 0;
            String loadedDate = "لا يوجد جرد سابق";
            String loadedAccuracy = "100%";

            try {
                MiraApiClient.ApiResponse response = apiClient.getLastSnapshot("Main Showroom");
                if (response.isSuccess && response.data != null) {
                    JSONObject snapshotObj = response.data;
                    
                    if (snapshotObj.has("snapshot")) {
                        snapshotObj = snapshotObj.optJSONObject("snapshot");
                    } else if (snapshotObj.has("snapshots")) {
                        JSONArray arr = snapshotObj.optJSONArray("snapshots");
                        if (arr != null && arr.length() > 0) {
                            snapshotObj = arr.optJSONObject(0);
                        }
                    }

                    if (snapshotObj != null) {
                        loadedPrevItems = snapshotObj.optInt("total_items", snapshotObj.optInt("found_count", 0));
                        loadedDate = snapshotObj.optString("snapshot_date", snapshotObj.optString("created_at", loadedDate));
                        double acc = snapshotObj.optDouble("accuracy", 100.0);
                        loadedAccuracy = String.format(Locale.US, "%.1f%%", acc);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading snapshot: " + e.getMessage());
            }

            final int finalItems = loadedPrevItems;
            final String finalDate = loadedDate;
            final String finalAccuracy = loadedAccuracy;

            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    prevTotalItems = finalItems;
                    if (tvLastAuditDate != null)
                        tvLastAuditDate.setText(finalDate.length() > 10 ? finalDate.substring(0, 10) : finalDate);
                    if (tvLastAccuracy != null) tvLastAccuracy.setText(finalAccuracy);
                    if (tvPrevItems != null) tvPrevItems.setText(String.valueOf(prevTotalItems));
                    updateComparisonCard();
                });
            }
        }).start();
    }

    private void loadHighValueItems() {
        new Thread(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getHighValueItems("Main Showroom", 3000);
                if (response.isSuccess && response.data != null) {
                    int count = response.data.optInt("count", 0);
                    JSONArray items = response.data.optJSONArray("items");

                    if (isAdded() && getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            if (!isAdded()) return;
                            if (tvHighValueCount != null) tvHighValueCount.setText(String.valueOf(count));
                            if (count > 0 && items != null && tvHighValueList != null) {
                                StringBuilder list = new StringBuilder();
                                for (int i = 0; i < Math.min(3, items.length()); i++) {
                                    JSONObject item = items.optJSONObject(i);
                                    if (item != null) {
                                        list.append("• ").append(item.optString("item_title", item.optString("title", "قطع عالية قيمة"))).append("\n");
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

    private void queryMiraForInventory(String gtin) {
        new Thread(() -> {
            String title = "قطعة ذهب - " + gtin.substring(Math.max(0, gtin.length() - 4));
            String karat = "21K";
            String weight = String.format(Locale.US, "%.2fg", 5.0 + (Math.random() * 10));
            String location = "Main Showroom";
            String status = "✓";
            boolean found = true;
            String rssi = "-" + (40 + (int)(Math.random() * 30));

            try {
                JSONObject extraParams = new JSONObject();
                extraParams.put("rssi", rssi);

                MiraApiClient.ApiResponse response = apiClient.authorize(gtin, "inventory", extraParams);

                if (response.isSuccess && response.data != null) {
                    JSONObject item = response.data.optJSONObject("item");
                    if (item != null) {
                        title = item.optString("title", title);
                        karat = item.optString("karat", karat);
                        double w = item.optDouble("weight", 0);
                        if (w > 0) weight = String.format(Locale.US, "%.2fg", w);
                        String vaultStatus = item.optString("status", "");
                        location = item.optString("location", location);

                        switch (vaultStatus.toLowerCase()) {
                            case "sold":
                                status = "⚠️"; found = false; break;
                            case "in_stock":
                            case "available":
                            case "transferred":
                                status = "✓"; found = true; break;
                            case "reserved":
                                status = "🔒"; found = false; break;
                            default:
                                status = "✓"; found = true;
                        }
                    }
                }
            } catch (Exception ignored) {}

            final String fTitle = title, fKarat = karat, fWeight = weight;
            final String fLocation = location, fStatus = status;
            final boolean fFound = found;
            final String fGtin = gtin;
            final String fRssi = rssi;

            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    scannedItems.add(0, new InventoryItem(fGtin, fTitle, fKarat, fWeight, fStatus, fLocation));
                    playInventorySound("scan");
                    scannedCount++;
                    if (fFound) foundCount++;

                    if (adapter != null) adapter.notifyItemInserted(0);
                    if (rvInventoryItems != null) rvInventoryItems.scrollToPosition(0);

                    if (tvLastScan != null) tvLastScan.setText(fGtin);
                    if (tvRssi != null) tvRssi.setText(fRssi + " dBm");
                    if (tvScannedCount != null) tvScannedCount.setText(String.valueOf(scannedCount));
                    if (tvVariance != null) tvVariance.setText(String.valueOf(Math.max(0, expectedCount - foundCount)));

                    double acc = expectedCount > 0 ? (foundCount * 100.0 / expectedCount) : 0;
                    if (tvAccuracy != null) tvAccuracy.setText(String.format(Locale.US, "%.1f%%", acc));

                    // 🟢 تحديث شاشة المقارنة حيّاً أثناء الجرد
                    updateComparisonCard();
                });
            }
        }).start();
    }

    private void startSessionOnServer(boolean blind) {
        new Thread(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.startInventorySession("Main Showroom", blind ? "blind" : "full");
                if (response.isSuccess && response.data != null) {
                    sessionId = response.data.optInt("session_id", 0);
                    int serverExpected = response.data.optInt("expected_count", 0);
                    if (serverExpected > 0) {
                        expectedCount = serverExpected;
                    }
                }
            } catch (Exception e) {
                expectedCount = realGtins.length;
            }

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    if (tvExpectedCount != null) {
                        tvExpectedCount.setText(blind ? "???" : String.valueOf(expectedCount));
                    }
                    startSimulation();
                });
            }
        }).start();
    }

    private void closeSessionOnServer() {
        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("session_id", sessionId);
                payload.put("found_count", foundCount);
                payload.put("expected_count", expectedCount);
                payload.put("variance", varianceCount);
                apiClient.closeInventorySession(payload);
            } catch (Exception ignored) {}
        }).start();
    }

    private void createSnapshotOnServer() {
    // 🟢 1. شرط حماية جذري: منع حفظ أية snapshot إذا لم تكن هناك عناصر مقروءة
    if (scannedItems == null || scannedItems.isEmpty() || foundCount == 0) {
        Log.w(TAG, "⚠️ Cancelled snapshot creation: No scanned items available (foundCount is 0).");
        return;
    }

    new Thread(() -> {
        try {
            JSONObject payload = new JSONObject();
            payload.put("session_id", sessionId);
            payload.put("location", "Main Showroom");
            payload.put("total_items", foundCount); // استخدام العناصر الموجودة بالفعل
            payload.put("total_weight", calculateTotalWeight());
            payload.put("total_value", calculateTotalValue());

            JSONArray itemsJson = new JSONArray();
            // 🟢 استخدام نسخ مؤقت للقائمة لتفادي أخطاء الـ Thread Concurrency
            List<InventoryItem> tempItems = new ArrayList<>(scannedItems);
            
            for (InventoryItem item : tempItems) {
                JSONObject obj = new JSONObject();
                obj.put("serial", item.serial);
                obj.put("title", item.title);
                obj.put("karat", item.karat);
                obj.put("weight", item.weight);
                obj.put("status", item.status);
                obj.put("location", item.location);
                itemsJson.put(obj);
            }
            payload.put("items", itemsJson);

            // إرسال البيانات فقط بعد التحقق التام من وجود محتوى
            MiraApiClient.ApiResponse response = apiClient.createInventorySnapshot(payload);
            if (response.isSuccess) {
                Log.d(TAG, "✅ Snapshot saved successfully with " + tempItems.size() + " items.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error creating snapshot: " + e.getMessage());
        }
    }).start();
}

    private double calculateTotalWeight() {
        double total = 0;
        for (InventoryItem item : scannedItems) {
            try {
                String w = item.weight.replace("g", "").replace("f", "").trim();
                total += Double.parseDouble(w);
            } catch (Exception ignored) {}
        }
        return Math.round(total * 1000.0) / 1000.0;
    }

    private double calculateTotalValue() {
        return Math.round(calculateTotalWeight() * 65 * 100.0) / 100.0;
    }

    // ===================== Inventory Logic =====================

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

        if (cardComparison != null) cardComparison.setVisibility(View.VISIBLE);
        updateComparisonCard();

        if (tvInventoryStatus != null) {
            tvInventoryStatus.setText("● SCANNING");
            tvInventoryStatus.setTextColor(Color.parseColor("#38BDF8"));
        }

        playInventorySound("start");
        startSessionOnServer(blindMode);
        startTimer();

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

        updateComparisonCard();
        updateUI();
        createSnapshotOnServer();

        if (getContext() != null) {
            Toast.makeText(getContext(), "✅ Complete: " + foundCount + "/" + expectedCount, Toast.LENGTH_LONG).show();
        }
    }

    // 🟢 دالة مخصصة لتحديث بطاقة المقارنة بين الجرد السابق والجالي
    private void updateComparisonCard() {
        if (cardComparison == null) return;
        cardComparison.setVisibility(View.VISIBLE);

        if (tvPrevItems != null) tvPrevItems.setText(String.valueOf(prevTotalItems));
        if (tvCurrItems != null) tvCurrItems.setText(String.valueOf(foundCount));

        int delta = foundCount - prevTotalItems;
        if (tvDeltaItems != null) {
            tvDeltaItems.setText((delta >= 0 ? "+" : "") + delta);
            tvDeltaItems.setTextColor(delta >= 0 ? Color.parseColor("#22C55E") : Color.parseColor("#EF4444"));
        }
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

    private void generateProReport() {
        if (getContext() == null || !isAdded()) return;

        StringBuilder rows = new StringBuilder();
        int count = 1;
        for (int i = scannedItems.size() - 1; i >= 0; i--) {
            InventoryItem item = scannedItems.get(i);
            String color, icon;
            switch (item.status) {
                case "✓":  color = "#22C55E"; icon = "✅"; break;
                case "⚠️": color = "#F59E0B"; icon = "⚠️"; break;
                case "🔒": color = "#3B82F6"; icon = "🔒"; break;
                default:   color = "#EF4444"; icon = "❌";
            }
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
                if (getContext() == null || !isAdded()) return;
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
        if (timerRunnable != null) handler.removeCallbacks(timerRunnable);
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
        if (!isScanning) startInventory(false);
        else stopInventory();
    }

    // ===================== Models =====================

    public static class InventoryItem {
        String serial, title, karat, weight, status, location;
        public InventoryItem(String s, String t, String k, String w, String st, String l) {
            serial = s; title = t; karat = k; weight = w; status = st; location = l;
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
                switch (item.status) {
                    case "✓":  h.s.setTextColor(Color.parseColor("#22C55E")); break;
                    case "⚠️": h.s.setTextColor(Color.parseColor("#F59E0B")); break;
                    case "🔒": h.s.setTextColor(Color.parseColor("#3B82F6")); break;
                    default:   h.s.setTextColor(Color.parseColor("#EF4444"));
                }
            }
            if (h.serial != null) h.serial.setText(item.serial);
            if (h.title != null) h.title.setText(item.title);
            if (h.details != null) h.details.setText(item.karat + " | " + item.weight);
            if (h.location != null) h.location.setText(item.location);
        }

        @Override
        public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView s, serial, title, details, location;
            VH(View v) {
                super(v);
                s = v.findViewById(R.id.tvItemStatus);
                serial = v.findViewById(R.id.tvItemSerial);
                title = v.findViewById(R.id.tvItemTitle);
                details = v.findViewById(R.id.tvItemDetails);
                location = v.findViewById(R.id.tvItemLocation);
            }
        }
    }

    @Override
    public void onDestroyView() {
        isScanning = false;
        stopTimer();
        if (simulationRunnable != null) simulationHandler.removeCallbacks(simulationRunnable);
        handler.removeCallbacksAndMessages(null);
        simulationHandler.removeCallbacksAndMessages(null);
        if (rvInventoryItems != null) rvInventoryItems.setAdapter(null);
        super.onDestroyView();
    }
}
