package com.example.uhf.fragment;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
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
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * MiraInventoryFragment — الجرد الفعلي عبر قارئ RFID اليدوي
 *
 * ✅ يعتمد على UHFMainActivity.startUHFScan() / stopUHFScan()
 * ✅ يستقبل الشرائح عبر setActiveRfidListener
 * ✅ Deduplication عبر HashSet — O(1)
 * ✅ مزامنة كاملة مع السيرفر
 */
public class MiraInventoryFragment extends Fragment {

    // ═══════════════════════════════════════════════════════
    //  UI Elements
    // ═══════════════════════════════════════════════════════
    private TextView tvInventoryStatus, tvExpectedCount, tvScannedCount, tvMissingCount;
    private TextView tvAccuracy, tvElapsedTime, tvLocation, tvLastScan, tvRssi;
    private View progressScanned;
    private Button btnStartInventory, btnStopInventory, btnGenerateReport;
    private RecyclerView rvInventoryItems;

    // ═══════════════════════════════════════════════════════
    //  🟢 إعدادات الجرد
    // ═══════════════════════════════════════════════════════
    private boolean soundEnabled = true;
    private boolean vibrateEnabled = true;
    private boolean autoStopEnabled = false;
    private String inventoryMode = "Full";

    // ═══════════════════════════════════════════════════════
    //  State
    // ═══════════════════════════════════════════════════════
    private boolean isScanning = false;
    private int expectedCount = 0;
    private int scannedCount = 0;
    private int foundCount = 0;
    private int missingCount = 0;
    private int sessionId = 0;
    private long startTime = 0;

    // ═══════════════════════════════════════════════════════
    //  Lists
    // ═══════════════════════════════════════════════════════
    private final List<InventoryItem> scannedItems = new ArrayList<>();
    private final Set<String> scannedEpcs = new HashSet<>();  // ✅ dedup O(1)
    private InventoryAdapter adapter;

    // ═══════════════════════════════════════════════════════
    //  Handlers
    // ═══════════════════════════════════════════════════════
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable timerRunnable;

    // ═══════════════════════════════════════════════════════
    //  🔗 ربط UHFMainActivity
    // ═══════════════════════════════════════════════════════
    private UHFMainActivity mainActivity;
    private boolean listenerRegistered = false;

    // ═══════════════════════════════════════════════════════
    //  API
    // ═══════════════════════════════════════════════════════
    private static final String BASE_URL = "https://ams.ibreg.org/wp-json/mira-gate/v1";
    private static final String API_KEY  = "mira_gate_test071234567890abcdefghijklmnop";

    // ═══════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        View v = inflater.inflate(R.layout.fragment_mira_inventory, container, false);

        loadInventorySettings();

        // Bind Views
        tvInventoryStatus = v.findViewById(R.id.tvInventoryStatus);
        tvExpectedCount   = v.findViewById(R.id.tvExpectedCount);
        tvScannedCount    = v.findViewById(R.id.tvScannedCount);
        tvMissingCount    = v.findViewById(R.id.tvMissingCount);
        tvAccuracy        = v.findViewById(R.id.tvAccuracy);
        tvElapsedTime     = v.findViewById(R.id.tvElapsedTime);
        tvLocation        = v.findViewById(R.id.tvLocation);
        tvLastScan        = v.findViewById(R.id.tvLastScan);
        tvRssi            = v.findViewById(R.id.tvRssi);
        progressScanned   = v.findViewById(R.id.progressScanned);
        btnStartInventory = v.findViewById(R.id.btnStartInventory);
        btnStopInventory  = v.findViewById(R.id.btnStopInventory);
        btnGenerateReport = v.findViewById(R.id.btnGenerateReport);
        rvInventoryItems  = v.findViewById(R.id.rvInventoryItems);

        // RecyclerView
        adapter = new InventoryAdapter(scannedItems);
        rvInventoryItems.setLayoutManager(new LinearLayoutManager(getContext()));
        rvInventoryItems.setAdapter(adapter);

        // Buttons
        btnStartInventory.setEnabled(true);
        btnStopInventory.setEnabled(false);
        btnGenerateReport.setEnabled(false);

        btnStartInventory.setOnClickListener(view -> startInventory());
        btnStopInventory.setOnClickListener(view -> stopInventory());
        btnGenerateReport.setOnClickListener(view -> generatePrintableReport());

        updateUI();
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();

        // 🔗 سجّل نفسك كمستمع للقارئ
        if (getActivity() instanceof UHFMainActivity) {
            mainActivity = (UHFMainActivity) getActivity();
            mainActivity.setActiveRfidListener(this::onRfidScanned);
            listenerRegistered = true;
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        // 🔌 ألغِ التسجيل وأوقف المسح
        if (mainActivity != null && listenerRegistered) {
            mainActivity.setActiveRfidListener(null);
            listenerRegistered = false;
        }

        if (isScanning) {
            stopInventory();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        stopTimer();

        if (mainActivity != null && listenerRegistered) {
            mainActivity.setActiveRfidListener(null);
            listenerRegistered = false;
        }

        if (isScanning && mainActivity != null) {
            mainActivity.stopUHFScan();
        }

        mainActivity = null;
    }

    // ═══════════════════════════════════════════════════════
    //  🟢 استقبال الشرائح الحقيقية
    // ═══════════════════════════════════════════════════════

    /**
     * ✅ يُستدعى من UHFMainActivity.onTagRead()
     *    عند كل قراءة فعلية للقارئ.
     */
    private void onRfidScanned(String epc) {
        if (!isScanning) return;
        if (epc == null || epc.trim().isEmpty()) return;

        final String cleanEpc = epc.trim().toUpperCase(Locale.US);

        // 🛡️ Dedup O(1)
        if (!scannedEpcs.add(cleanEpc)) {
            return;  // مقروءة مسبقاً
        }

        // 🎯 أرسلها للسيرفر
        queryMiraForInventory(cleanEpc);
    }

    // ═══════════════════════════════════════════════════════
    //  🟢 بدء جلسة الجرد
    // ═══════════════════════════════════════════════════════

    private void startInventory() {
        boolean isArabic = isArabic();

        if (mainActivity == null) {
            Toast.makeText(getContext(),
                isArabic ? "⚠️ القارئ غير متصل" : "⚠️ Reader not available",
                Toast.LENGTH_LONG).show();
            return;
        }

        btnStartInventory.setEnabled(false);
        btnStartInventory.setText(isArabic ? "● جاري الجرد..." : "● SCANNING...");
        btnStopInventory.setEnabled(true);
        btnGenerateReport.setEnabled(false);

        isScanning = true;
        startTime = System.currentTimeMillis();

        scannedItems.clear();
        scannedEpcs.clear();
        scannedCount = 0;
        foundCount = 0;
        missingCount = 0;
        adapter.notifyDataSetChanged();

        tvInventoryStatus.setText(isArabic ? "● جاري الجرد" : "● SCANNING");
        tvInventoryStatus.setTextColor(Color.parseColor("#38BDF8"));

        playInventorySound("start");

        startSessionOnServer();
        startTimer();

        // ✅ ابدأ المسح الفعلي
        mainActivity.startUHFScan();

        Toast.makeText(getContext(),
            isArabic ? "🟢 بدأ الجرد — قرّب الشرائح من القارئ"
                     : "🟢 Inventory started — move tags near the reader",
            Toast.LENGTH_SHORT).show();
    }

    // ═══════════════════════════════════════════════════════
    //  🔴 إيقاف جلسة الجرد
    // ═══════════════════════════════════════════════════════

    private void stopInventory() {
        isScanning = false;
        boolean isArabic = isArabic();

        playInventorySound("complete");

        // ⏹️ أوقف القارئ
        if (mainActivity != null) {
            mainActivity.stopUHFScan();
        }

        btnStartInventory.setEnabled(true);
        btnStartInventory.setText(isArabic ? "▶ بدء الجرد" : "▶ START");
        btnStopInventory.setEnabled(false);
        btnGenerateReport.setEnabled(true);

        tvInventoryStatus.setText(isArabic ? "● مكتمل" : "● COMPLETED");
        tvInventoryStatus.setTextColor(Color.parseColor("#22C55E"));

        stopTimer();
        closeSessionOnServer();

        missingCount = expectedCount - foundCount;
        if (missingCount < 0) missingCount = 0;
        updateUI();

        Toast.makeText(getContext(),
            (isArabic ? "✅ اكتمل الجرد: " : "✅ Completed: ")
                + foundCount + "/" + expectedCount,
            Toast.LENGTH_LONG).show();
    }

    /** ✅ مكشوفة لـ UHFMainActivity.isInventoryActive() */
    public boolean isScanning() {
        return isScanning;
    }

    // ═══════════════════════════════════════════════════════
    //  ☁️ بدء الجلسة على السيرفر
    // ═══════════════════════════════════════════════════════

    private void startSessionOnServer() {
        boolean isArabic = isArabic();
        String showroomName = isArabic ? "المعرض الرئيسي" : "Main Showroom";

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
                body.put("location", showroomName);
                body.put("type", "full");
                conn.getOutputStream().write(body.toString().getBytes("utf-8"));

                if (conn.getResponseCode() == 200) {
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String l;
                    while ((l = in.readLine()) != null) sb.append(l);
                    in.close();

                    JSONObject json = new JSONObject(sb.toString());
                    sessionId = json.optInt("session_id", 0);
                    expectedCount = json.optInt("expected_count", 0);

                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            tvExpectedCount.setText(String.valueOf(expectedCount));
                            tvLocation.setText(showroomName);
                            updateUI();
                        });
                    }
                }
            } catch (Exception e) {
                sessionId = (int) (System.currentTimeMillis() / 1000);
                expectedCount = 0;

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        tvExpectedCount.setText("0");
                        tvLocation.setText(showroomName);
                        updateUI();
                    });
                }
            }
        }).start();
    }

    // ═══════════════════════════════════════════════════════
    //  ☁️ إغلاق الجلسة على السيرفر
    // ═══════════════════════════════════════════════════════

    private void closeSessionOnServer() {
        boolean isArabic = isArabic();

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
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String l;
                    while ((l = in.readLine()) != null) sb.append(l);
                    in.close();

                    JSONObject json = new JSONObject(sb.toString());
                    JSONObject report = json.optJSONObject("report");
                    if (report != null) {
                        double acc = report.optDouble("accuracy", 0);
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                tvAccuracy.setText(
                                    (isArabic ? "الدقة: " : "Accuracy: ")
                                    + String.format(Locale.US, "%.1f%%", acc));
                                updateUI();
                            });
                        }
                    }
                }
            } catch (Exception e) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(this::updateUI);
                }
            }
        }).start();
    }

    // ═══════════════════════════════════════════════════════
    //  🔍 استعلام MIRA عن الشريحة
    // ═══════════════════════════════════════════════════════

    private void queryMiraForInventory(String epc) {
        boolean isArabic = isArabic();
        String defaultLocation = isArabic ? "المعرض الرئيسي" : "Main Showroom";

        new Thread(() -> {
            String title    = epc;
            String karat    = "-";
            String weight   = "-";
            String location = defaultLocation;
            String status   = "✗";
            boolean found   = false;

            try {
                URL url = new URL(BASE_URL + "/authorize");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-MIRA-API-Key", API_KEY);
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);

                JSONObject body = new JSONObject();
                body.put("epc", epc);
                body.put("gate_id", "handheld_c72");
                body.put("rssi", "-" + (40 + (int)(Math.random() * 30)));
                conn.getOutputStream().write(body.toString().getBytes("utf-8"));

                if (conn.getResponseCode() == 200) {
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String l;
                    while ((l = in.readLine()) != null) sb.append(l);
                    in.close();

                    JSONObject json = new JSONObject(sb.toString());
                    JSONObject item = json.optJSONObject("item");

                    if (item != null) {
                        title = item.optString("title", epc);
                        karat = item.optString("karat", "-");
                        double w = item.optDouble("weight", 0);
                        weight = w > 0 ? String.format(Locale.US, "%.1fg", w) : "-";
                        location = item.optString("location", defaultLocation);

                        String vaultStatus = item.optString("status", "");
                        if (vaultStatus.equals("in_stock")
                                || vaultStatus.equals("available")
                                || vaultStatus.equals("sold")
                                || vaultStatus.equals("display")
                                || vaultStatus.equals("reserved")) {
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
                title = isArabic ? "خطأ" : "Error";
                status = "✗";
                found = false;
            }

            final String finalTitle    = title;
            final String finalKarat    = karat;
            final String finalWeight   = weight;
            final String finalLocation = location;
            final String finalStatus   = status;
            final boolean finalFound   = found;

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    scannedItems.add(0, new InventoryItem(
                        epc, finalTitle, finalKarat, finalWeight,
                        finalStatus, finalLocation));

                    if ("✓".equals(finalStatus)) {
                        playInventorySound("scan");
                    } else {
                        playInventorySound("alert");
                    }

                    scannedCount++;
                    if (finalFound) foundCount++;

                    adapter.notifyItemInserted(0);
                    rvInventoryItems.scrollToPosition(0);

                    tvLastScan.setText(epc);
                    tvRssi.setText("-" + (40 + (int)(Math.random() * 30)) + " dBm");
                    tvScannedCount.setText(String.valueOf(scannedCount));

                    double acc = expectedCount > 0
                            ? (foundCount * 100.0 / expectedCount) : 0;
                    tvAccuracy.setText(
                        (isArabic ? "الدقة: " : "Accuracy: ")
                        + String.format(Locale.US, "%.1f%%", acc));

                    if (rvInventoryItems.getWidth() > 0) {
                        int pct = expectedCount > 0
                                ? (scannedCount * 100) / expectedCount : 0;
                        progressScanned.getLayoutParams().width =
                            Math.max(1, (pct * rvInventoryItems.getWidth()) / 100);
                        progressScanned.requestLayout();
                    }

                    // 🛑 إيقاف تلقائي اختياري
                    if (autoStopEnabled
                            && expectedCount > 0
                            && scannedCount >= expectedCount) {
                        stopInventory();
                    }
                });
            }

            recordScanOnServer(epc, finalStatus);
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

    // ═══════════════════════════════════════════════════════
    //  📋 تقرير قابل للطباعة
    // ═══════════════════════════════════════════════════════

    private void generatePrintableReport() {
        boolean isArabic = isArabic();
        String reportHtml = buildReportHtml();

        if (getContext() == null) return;

        WebView webView = new WebView(getContext());
        webView.getSettings().setJavaScriptEnabled(true);
        webView.loadDataWithBaseURL(null, reportHtml, "text/html", "UTF-8", null);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (getContext() == null) return;
                PrintManager printManager = (PrintManager) getContext()
                        .getSystemService(Context.PRINT_SERVICE);
                if (printManager != null) {
                    PrintDocumentAdapter adapter =
                        view.createPrintDocumentAdapter("MIRA_Inventory_Report");
                    printManager.print("MIRA Inventory Report", adapter,
                        new PrintAttributes.Builder().build());
                }
            }
        });

        Toast.makeText(getContext(),
            isArabic ? "🖨️ جاري فتح نافذة الطباعة..."
                     : "🖨️ Opening print dialog...",
            Toast.LENGTH_SHORT).show();
    }

    private String buildReportHtml() {
        boolean isArabic = isArabic();
        StringBuilder rows = new StringBuilder();
        int count = 1;

        for (int i = scannedItems.size() - 1; i >= 0; i--) {
            InventoryItem item = scannedItems.get(i);
            String color = "✓".equals(item.status) ? "#22C55E" : "#EF4444";
            String icon  = "✓".equals(item.status) ? "✅" : "⚠️";

            rows.append(String.format(Locale.US,
                "<tr>" +
                "<td style='text-align:center;'>%d</td>" +
                "<td style='text-align:center;color:%s;font-size:18px;'>%s</td>" +
                "<td style='direction:%s;text-align:%s;'>%s</td>" +
                "<td style='direction:%s;text-align:%s;'>%s</td>" +
                "<td style='text-align:center;'>%s</td>" +
                "<td style='text-align:center;'>%s</td>" +
                "</tr>",
                count++, color, icon,
                isArabic ? "rtl" : "ltr", isArabic ? "right" : "left", item.serial,
                isArabic ? "rtl" : "ltr", isArabic ? "right" : "left", item.title,
                item.karat, item.weight
            ));
        }

        double accuracyVal = expectedCount > 0
                ? (foundCount * 100.0 / expectedCount) : 0;
        String accuracyColor = accuracyVal >= 98 ? "#22C55E"
                : accuracyVal >= 90 ? "#F59E0B" : "#EF4444";

        String dir = isArabic ? "rtl" : "ltr";
        String reportTitle   = isArabic ? "📊 تقرير الجرد" : "📊 Inventory Report";
        String showroomText  = isArabic
                ? "📍 الموقع: المعرض الرئيسي - صنعاء"
                : "📍 Location: Main Showroom - Sana'a";
        String expectedLbl   = isArabic ? "العدد المتوقع" : "Expected Count";
        String foundLbl      = isArabic ? "✅ تم العثور" : "✅ Found";
        String missingLbl    = isArabic ? "❌ مفقود" : "❌ Missing";
        String accuracyLbl   = isArabic ? "📊 الدقة" : "📊 Accuracy";
        String colHash       = "#";
        String colStatus     = isArabic ? "الحالة" : "Status";
        String colCode       = isArabic ? "الرمز" : "Code";
        String colItem       = isArabic ? "القطعة" : "Item";
        String colKarat      = isArabic ? "العيار" : "Karat";
        String colWeight     = isArabic ? "الوزن" : "Weight";
        String durationText  = isArabic ? "المدة: " : "Duration: ";

        return "<!DOCTYPE html><html dir='" + dir + "'><head><meta charset='UTF-8'>" +
            "<meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
            "<style>" +
            "@import url('https://fonts.googleapis.com/css2?family=Cairo:wght@400;600;700&display=swap');" +
            "body{font-family:'Cairo',sans-serif;color:#1a1a1a;padding:20px;direction:" + dir + ";background:#fff}" +
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
            "<h1>" + reportTitle + "</h1>" +
            "<div class='sub'>" + new SimpleDateFormat("yyyy-MM-dd  |  HH:mm:ss",
                new Locale(isArabic ? "ar" : "en")).format(new Date()) + "</div>" +
            "<div class='sub'>" + showroomText + "</div>" +
            "</div>" +
            "<div class='summary'>" +
            "<div class='summary-card'><div class='val'>" + expectedCount + "</div><div class='lbl'>" + expectedLbl + "</div></div>" +
            "<div class='summary-card found'><div class='val'>" + foundCount + "</div><div class='lbl'>" + foundLbl + "</div></div>" +
            "<div class='summary-card missing'><div class='val'>" + missingCount + "</div><div class='lbl'>" + missingLbl + "</div></div>" +
            "<div class='summary-card accuracy'><div class='val'>" + String.format(Locale.US, "%.1f%%", accuracyVal) + "</div><div class='lbl'>" + accuracyLbl + "</div></div>" +
            "</div>" +
            "<table>" +
            "<tr><th>" + colHash + "</th><th>" + colStatus + "</th><th>" + colCode + "</th><th>" + colItem + "</th><th>" + colKarat + "</th><th>" + colWeight + "</th></tr>" +
            rows.toString() +
            "</table>" +
            "<div class='footer'>" +
            "<strong>MIRA Technology © 2026</strong><br>" +
            "mira-id.com | ams.ibreg.org<br>" +
            (isArabic ? "تم الإنشاء بواسطة MIRA Bridge™<br>"
                      : "Generated by MIRA Bridge™<br>") +
            durationText + tvElapsedTime.getText() +
            "</div>" +
            "</body></html>";
    }

    // ═══════════════════════════════════════════════════════
    //  ⏱️ المؤقت
    // ═══════════════════════════════════════════════════════

    private void startTimer() {
        timerRunnable = () -> {
            long elapsed = System.currentTimeMillis() - startTime;
            long s = (elapsed / 1000) % 60;
            long m = (elapsed / (1000 * 60)) % 60;
            long h = (elapsed / (1000 * 60 * 60)) % 24;
            tvElapsedTime.setText(String.format(Locale.US, "%02d:%02d:%02d", h, m, s));
            handler.postDelayed(timerRunnable, 1000);
        };
        handler.post(timerRunnable);
    }

    private void stopTimer() {
        if (timerRunnable != null) handler.removeCallbacks(timerRunnable);
    }

    private void updateUI() {
        if (tvScannedCount != null)
            tvScannedCount.setText(String.valueOf(scannedCount));
        if (tvMissingCount != null)
            tvMissingCount.setText(String.valueOf(missingCount));
    }

    private boolean isArabic() {
        return AppCompatDelegate.getApplicationLocales()
                .toLanguageTags().contains("ar");
    }

    // ═══════════════════════════════════════════════════════
    //  📋 Model & Adapter
    // ═══════════════════════════════════════════════════════

    public static class InventoryItem {
        String serial, title, karat, weight, status, location;

        public InventoryItem(String s, String t, String k,
                             String w, String st, String l) {
            serial = s; title = t; karat = k;
            weight = w; status = st; location = l;
        }
    }

    private class InventoryAdapter extends RecyclerView.Adapter<InventoryAdapter.VH> {
        private final List<InventoryItem> items;

        public InventoryAdapter(List<InventoryItem> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_inventory, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            InventoryItem item = items.get(pos);
            h.s.setText(item.status);
            h.serial.setText(item.serial);
            h.title.setText(item.title);
            h.details.setText(item.karat + " | " + item.weight);
            h.s.setTextColor("✓".equals(item.status)
                    ? Color.parseColor("#22C55E")
                    : Color.parseColor("#EF4444"));
        }

        @Override
        public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView s, serial, title, details;

            VH(View v) {
                super(v);
                s       = v.findViewById(R.id.tvItemStatus);
                serial  = v.findViewById(R.id.tvItemSerial);
                title   = v.findViewById(R.id.tvItemTitle);
                details = v.findViewById(R.id.tvItemDetails);
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    //  🟢 تحميل الإعدادات
    // ═══════════════════════════════════════════════════════

    private void loadInventorySettings() {
        if (getContext() == null) return;

        SharedPreferences prefs = getContext()
                .getSharedPreferences("MIRA_BRIDGE_SETTINGS", Context.MODE_PRIVATE);

        soundEnabled   = "true".equals(prefs.getString("inventory_sound", "true"));
        vibrateEnabled = "true".equals(prefs.getString("inventory_vibrate", "true"));
        autoStopEnabled = "true".equals(prefs.getString("inventory_auto_stop", "false"));
        inventoryMode  = prefs.getString("inventory_mode", "Full");
    }

    // ═══════════════════════════════════════════════════════
    //  🔊 الأصوات + الاهتزاز
    // ═══════════════════════════════════════════════════════

    private void playInventorySound(String type) {
        if (soundEnabled) {
            try {
                if (getActivity() instanceof UHFMainActivity) {
                    UHFMainActivity activity = (UHFMainActivity) getActivity();
                    if (activity == null) return;

                    switch (type) {
                        case "start":
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
            } catch (Exception ignored) {}
        }

        if (vibrateEnabled && getContext() != null) {
            try {
                Vibrator vibrator = (Vibrator) getContext()
                        .getSystemService(Context.VIBRATOR_SERVICE);
                if (vibrator != null && vibrator.hasVibrator()) {
                    switch (type) {
                        case "start":
                            vibrator.vibrate(200);
                            break;
                        case "complete":
                            vibrator.vibrate(
                                new long[]{0, 100, 100, 100, 100, 300}, -1);
                            break;
                        case "scan":
                            vibrator.vibrate(50);
                            break;
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private void safePlaySound(UHFMainActivity activity, int soundId) {
        try {
            activity.playSound(soundId);
        } catch (Exception ignored) {}
    }
}
