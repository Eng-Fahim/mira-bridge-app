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
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
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
    private TextView tvAccuracy, tvElapsedTime, tvLastScan, tvRssi;
    private TextView tvLastAuditDate, tvLastAccuracy, tvHighValueCount;
    private TextView tvPrevItems, tvCurrItems, tvDeltaItems;
    private CardView cardComparison;
    private Button btnStartInventory, btnBlindInventory, btnStopInventory, btnGenerateReport, btnArchiveReports;
    private RecyclerView rvInventoryItems;
    private ProgressBar progressScanned;

    private boolean isScanning = false;
    private boolean isBlindMode = false;
    private int expectedCount = 0;
    private int scannedCount = 0;
    private int foundCount = 0;
    private int varianceCount = 0;
    private int prevTotalItems = 0;
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

    private final String[] simulationGtins = {
        "0700000007885", "0700000007886", "0700000007887", "0700000007888", "0700000007889",
        "0700000007890", "0700000007891", "0700000007892", "0700000007893", "0700000007894",
        "0700000007895", "0700000007896", "0700000007897", "0700000007898", "0700000007899",
        "0700000007900", "0700000007901", "0700000007902", "0700000007903", "0700000007904",
        "0700000007905", "0700000007906", "0700000007907", "0700000007908", "0700000007909",
        "0700000007910", "0700000007911", "0700000007912", "0700000007913", "0700000007914",
        "0700000007915", "0700000007916", "0700000007917", "0700000007918", "0700000007919",
        "0700000007920", "0700000007921", "0700000007922", "0700000007923", "0700000007924",
        "0700000007925", "0700000007926", "0700000007927", "0700000007928", "0700000007929",
        "0700000007930", "0700000007931", "0700000007932", "0700000013764", "070000000040"
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_mira_inventory_pro, container, false);
        if (getContext() != null) {
            apiClient = MiraApiClient.getInstance(getContext());
        }
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
    }

    private void bindViews(View v) {
        tvInventoryStatus = v.findViewById(R.id.tvInventoryStatus);
        tvExpectedCount = v.findViewById(R.id.tvExpectedCount);
        tvScannedCount = v.findViewById(R.id.tvScannedCount);
        tvVariance = v.findViewById(R.id.tvVariance);
        tvAccuracy = v.findViewById(R.id.tvAccuracy);
        tvElapsedTime = v.findViewById(R.id.tvElapsedTime);
        tvLastScan = v.findViewById(R.id.tvLastScan);
        tvRssi = v.findViewById(R.id.tvRssi);
        tvPrevItems = v.findViewById(R.id.tvPrevItems);
        tvCurrItems = v.findViewById(R.id.tvCurrItems);
        tvDeltaItems = v.findViewById(R.id.tvDeltaItems);
        cardComparison = v.findViewById(R.id.cardComparison);
        btnStartInventory = v.findViewById(R.id.btnStartInventory);
        btnBlindInventory = v.findViewById(R.id.btnBlindInventory);
        btnStopInventory = v.findViewById(R.id.btnStopInventory);
        btnGenerateReport = v.findViewById(R.id.btnGenerateReport);
        btnArchiveReports = v.findViewById(R.id.btnArchiveReports);
        rvInventoryItems = v.findViewById(R.id.rvInventoryItems);
        progressScanned = v.findViewById(R.id.progressScanned);
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
        if (btnArchiveReports != null) btnArchiveReports.setOnClickListener(v -> showAuditHistoryDialog());
    }

    private void setInitialState() {
        if (btnStartInventory != null) btnStartInventory.setEnabled(true);
        if (btnBlindInventory != null) btnBlindInventory.setEnabled(true);
        if (btnStopInventory != null) btnStopInventory.setEnabled(false);
        if (btnGenerateReport != null) btnGenerateReport.setEnabled(false);
        expectedCount = simulationGtins.length;
        if (tvExpectedCount != null) tvExpectedCount.setText(String.valueOf(expectedCount));
        if (cardComparison != null) cardComparison.setVisibility(View.VISIBLE);
    }

    private void loadLastSnapshot() {
        if (apiClient == null) return;
        new Thread(() -> {
            int loadedPrevItems = 0;
            try {
                boolean isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");
                String showroom = isArabic ? "المعرض الرئيسي" : "Main Showroom";
                MiraApiClient.ApiResponse response = apiClient.getLastSnapshot(showroom);
                if (response != null && response.isSuccess && response.data != null) {
                    JSONObject snapshotObj = response.data;
                    if (snapshotObj.has("snapshot")) {
                        snapshotObj = snapshotObj.optJSONObject("snapshot");
                    }
                    if (snapshotObj != null) {
                        loadedPrevItems = snapshotObj.optInt("total_items", snapshotObj.optInt("found_count", 0));
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading snapshot: " + e.getMessage());
            }

            final int finalItems = loadedPrevItems;
            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    prevTotalItems = finalItems;
                    if (tvPrevItems != null) tvPrevItems.setText(String.valueOf(prevTotalItems));
                    updateComparisonCard();
                });
            }
        }).start();
    }

    public void processScannedTag(String epcOrGtin, String rssi) {
        if (!isScanning || apiClient == null) return;

        for (InventoryItem item : scannedItems) {
            if (item.serial.equalsIgnoreCase(epcOrGtin)) return;
        }

        new Thread(() -> {
            try {
                JSONObject extraParams = new JSONObject();
                if (rssi != null) extraParams.put("rssi", rssi);

                MiraApiClient.ApiResponse response = apiClient.authorize(epcOrGtin, "inventory", extraParams);

                boolean isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");
                String title = isArabic ? "قطعة غير معرفة" : "Unknown Item";
                String karat = "21K";
                String weight = "0.00g";
                String location = isArabic ? "المعرض الرئيسي" : "Main Showroom";
                String status = "⚠️";
                boolean found = false;

                if (response != null && response.isSuccess && response.data != null) {
                    JSONObject item = response.data.optJSONObject("item");
                    if (item != null) {
                        title = item.optString("title", item.optString("name", title));
                        karat = item.optString("karat", "21K");
                        double w = item.optDouble("weight", 0);
                        weight = String.format(Locale.US, "%.2fg", w);
                        location = item.optString("location", location);
                        String vaultStatus = item.optString("status", "");

                        switch (vaultStatus.toLowerCase()) {
                            case "sold": status = "⚠️"; found = false; break;
                            default: status = "✓"; found = true;
                        }
                    }
                }

                final String fTitle = title, fKarat = karat, fWeight = weight;
                final String fLocation = location, fStatus = status;
                final boolean fFound = found;

                if (isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        scannedItems.add(0, new InventoryItem(epcOrGtin, fTitle, fKarat, fWeight, fStatus, fLocation));
                        playInventorySound("scan");
                        scannedCount++;
                        if (fFound) foundCount++;

                        if (adapter != null) adapter.notifyItemInserted(0);
                        if (rvInventoryItems != null) rvInventoryItems.scrollToPosition(0);

                        boolean ar = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");
                        if (tvScannedCount != null) tvScannedCount.setText(String.valueOf(scannedCount));
                        if (tvVariance != null) tvVariance.setText(String.valueOf(Math.max(0, expectedCount - foundCount)));

                        if (progressScanned != null && expectedCount > 0) {
                            int pct = (int) ((scannedCount * 100.0) / expectedCount);
                            progressScanned.setProgress(Math.min(100, pct));
                        }

                        double acc = expectedCount > 0 ? (foundCount * 100.0 / expectedCount) : 100.0;
                        if (tvAccuracy != null) tvAccuracy.setText(String.format(Locale.US, ar ? "الدقة: %.1f%%" : "Accuracy: %.1f%%", acc));

                        updateComparisonCard();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing scanned tag: " + e.getMessage());
            }
        }).start();
    }

    private void startSessionOnServer(boolean blind) {
        if (apiClient == null) return;
        new Thread(() -> {
            try {
                boolean isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");
                MiraApiClient.ApiResponse response = apiClient.startInventorySession(isArabic ? "المعرض الرئيسي" : "Main Showroom", blind ? "blind" : "full");
                if (response != null && response.isSuccess && response.data != null) {
                    sessionId = response.data.optInt("session_id", 0);
                    int serverExpected = response.data.optInt("expected_count", 0);
                    if (serverExpected > 0) expectedCount = serverExpected;
                }
            } catch (Exception ignored) {}

            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    if (expectedCount == 0) expectedCount = simulationGtins.length;
                    if (tvExpectedCount != null) tvExpectedCount.setText(blind ? "???" : String.valueOf(expectedCount));
                    startSimulation();
                });
            }
        }).start();
    }

    private void startSimulation() {
        simulationIndex = 0;
        simulationRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isScanning || simulationIndex >= simulationGtins.length) {
                    if (isScanning) stopInventory();
                    return;
                }
                String gtin = simulationGtins[simulationIndex];
                processScannedTag(gtin, "-" + (40 + (int)(Math.random() * 25)));
                simulationIndex++;
                simulationHandler.postDelayed(this, 500);
            }
        };
        simulationHandler.postDelayed(simulationRunnable, 300);
    }

    private void closeSessionOnServer() {
        if (apiClient == null) return;
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
        if (scannedItems.isEmpty() || foundCount == 0 || apiClient == null) return;

        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("session_id", sessionId);
                boolean isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");
                payload.put("location", isArabic ? "المعرض الرئيسي" : "Main Showroom");
                payload.put("total_items", foundCount);
                payload.put("total_weight", calculateTotalWeight());
                payload.put("total_value", calculateTotalValue());

                JSONArray itemsJson = new JSONArray();
                for (InventoryItem item : scannedItems) {
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
                apiClient.createInventorySnapshot(payload);
            } catch (Exception e) {
                Log.e(TAG, "Failed to create snapshot: " + e.getMessage());
            }
        }).start();
    }

    private double calculateTotalWeight() {
        double total = 0;
        for (InventoryItem item : scannedItems) {
            try {
                String w = item.weight.replace("g", "").replace("غرام", "").trim();
                total += Double.parseDouble(w);
            } catch (Exception ignored) {}
        }
        return Math.round(total * 1000.0) / 1000.0;
    }

    private double calculateTotalValue() {
        return Math.round(calculateTotalWeight() * 65 * 100.0) / 100.0;
    }

    private void startInventory(boolean blindMode) {
        isBlindMode = blindMode;
        isScanning = true;
        startTime = System.currentTimeMillis();
        scannedItems.clear();
        scannedCount = 0;
        foundCount = 0;
        if (adapter != null) adapter.notifyDataSetChanged();

        if (btnStartInventory != null) btnStartInventory.setEnabled(false);
        if (btnBlindInventory != null) btnBlindInventory.setEnabled(false);
        if (btnStopInventory != null) btnStopInventory.setEnabled(true);
        if (btnGenerateReport != null) btnGenerateReport.setEnabled(false);

        if (cardComparison != null) cardComparison.setVisibility(View.VISIBLE);
        updateComparisonCard();

        boolean isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");
        if (tvInventoryStatus != null) {
            tvInventoryStatus.setText(isArabic ? "● جاري الجرد" : "● SCANNING");
            tvInventoryStatus.setTextColor(Color.parseColor("#38BDF8"));
        }

        playInventorySound("start");
        startSessionOnServer(blindMode);
        startTimer();
    }

    private void stopInventory() {
        isScanning = false;
        if (simulationRunnable != null) simulationHandler.removeCallbacks(simulationRunnable);
        playInventorySound("complete");

        if (btnStartInventory != null) btnStartInventory.setEnabled(true);
        if (btnBlindInventory != null) btnBlindInventory.setEnabled(true);
        if (btnStopInventory != null) btnStopInventory.setEnabled(false);
        if (btnGenerateReport != null) btnGenerateReport.setEnabled(true);

        boolean isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");
        if (tvInventoryStatus != null) {
            tvInventoryStatus.setText(isArabic ? "● مكتمل" : "● COMPLETED");
            tvInventoryStatus.setTextColor(Color.parseColor("#22C55E"));
        }

        stopTimer();
        closeSessionOnServer();
        varianceCount = Math.max(0, expectedCount - foundCount);
        updateComparisonCard();
        createSnapshotOnServer();
    }

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

    private void generateProReport() {
        if (getContext() == null || !isAdded()) return;

        boolean isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");

        android.content.SharedPreferences orgPrefs = getContext().getSharedPreferences("MIRA_ORG_PROFILE", Context.MODE_PRIVATE);
        String orgName = orgPrefs.getString("org_name", isArabic ? "مؤسسة البوعاني للذهب والمجوهرات" : "Al-Bouani Gold & Jewelry Enterprise");
        String orgPhone = orgPrefs.getString("org_phone", "+967 1 000000");
        String orgEmail = orgPrefs.getString("org_email", "info@mira-id.com");
        String orgAddress = orgPrefs.getString("org_address", isArabic ? "صنعاء - الجمهورية اليمنية" : "Sanaa - Republic of Yemen");

        double totalWt = calculateTotalWeight();
        double totalVal = calculateTotalValue();
        int dailySalesCount = 3; 
        double dailySalesWeight = 45.50; 
        
        int adjustedExpected = Math.max(0, prevTotalItems - dailySalesCount);
        int netVariance = foundCount - adjustedExpected;

        StringBuilder rows = new StringBuilder();
        int count = 1;
        for (int i = scannedItems.size() - 1; i >= 0; i--) {
            InventoryItem item = scannedItems.get(i);
            String color, icon;
            switch (item.status) {
                case "✓":  color = "#22C55E"; icon = "✅"; break;
                case "⚠️": color = "#F59E0B"; icon = "⚠️"; break;
                default:   color = "#EF4444"; icon = "❌";
            }
            rows.append(String.format(Locale.US,
                "<tr><td>%d</td><td style='color:%s; font-weight:bold;'>%s</td><td style='font-family:monospace;'>%s</td><td style='text-align:right; padding-right:12px;'>%s</td><td>%s</td><td style='font-weight:bold;'>%s</td><td>%s</td></tr>",
                count++, color, icon, item.serial, item.title, item.karat, item.weight, item.location));
        }

        double accVal = adjustedExpected > 0 ? (foundCount * 100.0 / adjustedExpected) : 100.0;
        String accColor = accVal >= 98 ? "#22C55E" : accVal >= 90 ? "#F59E0B" : "#EF4444";
        String dir = isArabic ? "rtl" : "ltr";

        String html = "<!DOCTYPE html><html dir='" + dir + "'><head><meta charset='UTF-8'><style>" +
            "@import url('https://fonts.googleapis.com/css2?family=Cairo:wght@400;600;700&display=swap');" +
            "body{font-family:'Cairo',sans-serif;color:#111827;padding:25px;direction:" + dir + ";background:#FFFFFF;font-size:11px}" +
            ".brand-header{display:flex;justify-content:space-between;align-items:center;border-bottom:2px solid #D4AF37;padding-bottom:12px;margin-bottom:15px}" +
            ".org-info h2{color:#1A237E;font-size:18px;margin:0 0 3px 0;font-weight:700}" +
            ".org-info p{color:#4B5563;font-size:10px;margin:2px 0}" +
            ".report-meta{text-align:left;font-size:10px;color:#374151}" +
            "body[dir='rtl'] .report-meta{text-align:right}" +
            ".title-box{text-align:center;background:#FDF8E7;border:1px solid #D4AF37;border-radius:6px;padding:10px;margin-bottom:15px}" +
            ".title-box h1{color:#92400E;font-size:16px;margin:0;font-weight:700}" +
            ".reconciliation-panel{background:#F8FAFC;border:1px solid #CBD5E1;border-radius:6px;padding:10px;margin-bottom:15px}" +
            ".reconciliation-panel h3{margin:0 0 6px 0;color:#1E293B;font-size:12px;border-bottom:1px solid #E2E8F0;padding-bottom:4px}" +
            ".recon-grid{display:flex;justify-content:space-between;gap:5px;text-align:center}" +
            ".recon-item{flex:1;background:#fff;padding:6px;border:1px solid #E2E8F0;border-radius:4px}" +
            ".recon-item .val{font-weight:700;font-size:13px;color:#0F172A}" +
            ".recon-item .lbl{font-size:9px;color:#64748B}" +
            ".summary{display:flex;justify-content:space-between;margin-bottom:15px;gap:6px}" +
            ".summary-card{flex:1;text-align:center;padding:10px 4px;background:#F9FAFB;border:1px solid #E5E7EB;border-radius:6px}" +
            ".summary-card .val{font-size:18px;font-weight:700;color:#111827}" +
            ".summary-card .lbl{font-size:9px;color:#6B7280;margin-top:2px}" +
            ".found .val{color:#16A34A} .variance .val{color:" + (netVariance >= 0 ? "#16A34A" : "#DC2626") + "} .accuracy .val{color:" + accColor + "}" +
            "table{width:100%;border-collapse:collapse;margin-top:10px;font-size:10px}" +
            "th{background:#1A237E;color:white;padding:7px 5px;text-align:center;font-weight:600}" +
            "td{padding:6px 5px;border-bottom:1px solid #E5E7EB;text-align:center}" +
            "tr:nth-child(even){background:#F9FAFB}" +
            ".financial-box{margin-top:15px;background:#F3F4F6;padding:10px 12px;border-radius:6px;display:flex;justify-content:space-between;font-weight:600;font-size:11px}" +
            ".signatures{display:flex;justify-content:space-between;margin-top:30px;padding-top:15px;page-break-inside:avoid}" +
            ".sig-box{text-align:center;width:40%;border-top:1px dashed #9CA3AF;padding-top:6px;font-size:10px;color:#4B5563}" +
            ".footer{text-align:center;margin-top:20px;padding-top:8px;border-top:1px solid #E5E7EB;color:#9CA3AF;font-size:9px}" +
            "@media print{body{padding:0}}</style></head><body>" +
            "<div class='brand-header'>" +
            "<div class='org-info'><h2>" + orgName + "</h2>" +
            "<p>📍 " + orgAddress + " | ☎ " + orgPhone + "</p>" +
            "<p>✉ " + orgEmail + " | mira-id.com</p></div>" +
            "<div class='report-meta'><strong>" + (isArabic ? "تاريخ التقرير:" : "Report Date:") + "</strong> " + new SimpleDateFormat("yyyy-MM-dd | HH:mm:ss", Locale.US).format(new Date()) + "<br>" +
            "<strong>" + (isArabic ? "النمط:" : "Audit Mode:") + "</strong> " + (isBlindMode ? (isArabic ? "جرد أعمى" : "Blind Audit") : (isArabic ? "جرد كامل" : "Full Audit")) + "</div></div>" +
            "<div class='title-box'><h1>" + (isArabic ? "📊 تقرير التدقيق والمطابقة الشامل للمخزون (Audit & Reconciliation Report)" : "📊 Comprehensive Inventory Audit & Reconciliation Report") + "</h1></div>" +
            "<div class='reconciliation-panel'>" +
            "<h3>" + (isArabic ? "🔄 لوحة المطابقة وحركة اليوم (Reconciliation Summary)" : "🔄 Daily Reconciliation & Movement Dashboard") + "</h3>" +
            "<div class='recon-grid'>" +
            "<div class='recon-item'><div class='val'>" + prevTotalItems + "</div><div class='lbl'>" + (isArabic ? "رصيد الجرد السابق" : "Previous Audit") + "</div></div>" +
            "<div class='recon-item'><div class='val' style='color:#D97706;'>-" + dailySalesCount + " (" + String.format(Locale.US, "%.1fg", dailySalesWeight) + ")</div><div class='lbl'>" + (isArabic ? "مبيعات اليوم المسجلة" : "Today's Sales") + "</div></div>" +
            "<div class='recon-item'><div class='val' style='color:#2563EB;'>" + adjustedExpected + "</div><div class='lbl'>" + (isArabic ? "الرصيد الدفتري المتوقع" : "Adjusted Expected") + "</div></div>" +
            "<div class='recon-item'><div class='val'>" + foundCount + "</div><div class='lbl'>" + (isArabic ? "المحسوس الفعلي (RFID)" : "Actual Scanned") + "</div></div>" +
            "</div></div>" +
            "<div class='summary'>" +
            "<div class='summary-card'><div class='val'>" + adjustedExpected + "</div><div class='lbl'>" + (isArabic ? "المتوقع بعد المبيعات" : "Expected Net") + "</div></div>" +
            "<div class='summary-card found'><div class='val'>" + foundCount + "</div><div class='lbl'>" + (isArabic ? "✅ تم التحقق" : "✅ Verified") + "</div></div>" +
            "<div class='summary-card variance'><div class='val'>" + (netVariance >= 0 ? "+" + netVariance : netVariance) + "</div><div class='lbl'>" + (isArabic ? "⚖ صافي التفاوت (Delta)" : "⚖ Net Variance") + "</div></div>" +
            "<div class='summary-card accuracy'><div class='val'>" + String.format(Locale.US, "%.1f%%", accVal) + "</div><div class='lbl'>" + (isArabic ? "📈 نسبة الدقة" : "📈 Accuracy") + "</div></div>" +
            "</div>" +
            "<table><tr><th>#</th><th>" + (isArabic ? "الحالة" : "Status") + "</th><th>" + (isArabic ? "الرقم التسلسلي / RFID" : "Serial / EPC") + "</th><th style='text-align:right; padding-right:12px;'>" + (isArabic ? "وصف القطعة والمجوهرات" : "Item Description") + "</th><th>" + (isArabic ? "العيار" : "Karat") + "</th><th>" + (isArabic ? "الوزن" : "Weight") + "</th><th>" + (isArabic ? "الموقع" : "Location") + "</th></tr>" + rows.toString() + "</table>" +
            "<div class='financial-box'>" +
            "<div>" + (isArabic ? "⚖ إجمالي الوزن الفعلي: " : "⚖ Total Verified Weight: ") + String.format(Locale.US, "%.2fg", totalWt) + "</div>" +
            "<div>" + (isArabic ? "💰 إجمالي القيمة التقديرية: $" : "💰 Est. Total Value: $") + String.format(Locale.US, "%,.2f", totalVal) + "</div>" +
            "</div>" +
            "<div class='signatures'>" +
            "<div class='sig-box'>" + (isArabic ? "توقيع أمين المستودع / المسؤول" : "Warehouse Manager Signature") + "</div>" +
            "<div class='sig-box'>" + (isArabic ? "اعتماد الإدارة والتدقيق المالي" : "Executive Audit & Finance Approval") + "</div>" +
            "</div>" +
            "<div class='footer'><strong>MIRA Bridge™ Enterprise Intelligence © 2026</strong><br>" + (isArabic ? "نظام موثق بشريحة التتبع الذكي MIRA ID™ - مطابق للمعايير الدولية" : "Secured by MIRA ID™ Smart Traceability Platform - International Standards") + "</div>" +
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
                    PrintDocumentAdapter pda = view.createPrintDocumentAdapter("MIRA_Audit_Reconciliation_Report");
                    pm.print("MIRA Audit & Reconciliation Report", pda, new PrintAttributes.Builder().build());
                }
            }
        });
    }

    private void showAuditHistoryDialog() {
        if (getContext() == null || apiClient == null) return;
        Toast.makeText(getContext(), "🔄 جاري جلب أرشيف جلسات الجرد...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getInventorySessionsList();
                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray sessionsArr = response.data.optJSONArray("sessions");
                    if (sessionsArr == null || sessionsArr.length() == 0) {
                        getActivity().runOnUiThread(() -> Toast.makeText(getContext(), "لا توجد جلسات جرد سابقة مسجلة", Toast.LENGTH_SHORT).show());
                        return;
                    }

                    final String[] sessionTitles = new String[sessionsArr.length()];
                    final int[] sessionIds = new int[sessionsArr.length()];

                    for (int i = 0; i < sessionsArr.length(); i++) {
                        JSONObject sObj = sessionsArr.optJSONObject(i);
                        if (sObj != null) {
                            sessionIds[i] = sObj.optInt("id");
                            sessionTitles[i] = "جلسة #" + sObj.optInt("id") + " - " + sObj.optString("created_at") + " (" + sObj.optString("location") + ")";
                        }
                    }

                    getActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        new AlertDialog.Builder(getContext())
                            .setTitle("📂 أرشيف وتقارير الجرد السابقة")
                            .setItems(sessionTitles, (dialog, which) -> loadAndPrintHistoricalReport(sessionIds[which]))
                            .setNegativeButton("إلغاء", null)
                            .show();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading sessions history: " + e.getMessage());
            }
        }).start();
    }

    private void loadAndPrintHistoricalReport(int targetSessionId) {
        if (apiClient == null || getContext() == null) return;
        Toast.makeText(getContext(), "🔄 جاري تحميل تفاصيل الجلسة المؤرشفة...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getInventorySessionDetail(targetSessionId);
                if (response != null && response.isSuccess && response.data != null) {
                    getActivity().runOnUiThread(() -> Toast.makeText(getContext(), "✅ تم جلب تفاصيل الجلسة بنجاح", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to load session detail: " + e.getMessage());
            }
        }).start();
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
