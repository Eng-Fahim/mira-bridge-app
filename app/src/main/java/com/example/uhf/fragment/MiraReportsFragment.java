package com.example.uhf.fragment;

import android.content.Intent;
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
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;

/**
 * MIRA Reports Center - مركز التقارير الشامل
 * 
 * أنواع التقارير:
 * - 📊 ملخص المخزون (حسب الفرع، العيار، الفئة)
 * - 💰 تقرير المبيعات (يومي، شهري، سنوي)
 * - 📦 تقرير التحويلات بين الفروع
 * - 🔍 تقرير الجرد والتدقيق
 * - 📋 تقرير المستخدمين والصلاحيات
 * - 🚪 تقرير البوابات الأمنية
 */
public class MiraReportsFragment extends Fragment {

    private WebView webReport;
    private TextView tvReportTitle;
    private LinearLayout layoutButtons;
    private Button btnInventory, btnSales, btnTransfers, btnAudit, btnUsers, btnGates;
    private Button btnPrint, btnShare, btnExport;

    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfile;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isArabic;
    private String currentReportType = "inventory";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_reports, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");
        apiClient = MiraApiClient.getInstance(requireContext());
        branchesManager = MiraBranchesManager.getInstance(requireContext());
        orgProfile = MiraOrgProfileManager.getInstance(requireContext());

        initViews(view);
        setupListeners();
        loadReport("inventory");
    }

    private void initViews(View v) {
        webReport = v.findViewById(R.id.webReport);
        tvReportTitle = v.findViewById(R.id.tvReportTitle);
        layoutButtons = v.findViewById(R.id.layoutReportButtons);
        btnInventory = v.findViewById(R.id.btnInventoryReport);
        btnSales = v.findViewById(R.id.btnSalesReport);
        btnTransfers = v.findViewById(R.id.btnTransfersReport);
        btnAudit = v.findViewById(R.id.btnAuditReport);
        btnUsers = v.findViewById(R.id.btnUsersReport);
        btnGates = v.findViewById(R.id.btnGatesReport);
        btnPrint = v.findViewById(R.id.btnPrintReport);
        btnShare = v.findViewById(R.id.btnShareReport);
        btnExport = v.findViewById(R.id.btnExportReport);

        applyLanguage();
        webReport.getSettings().setJavaScriptEnabled(true);
    }

    private void applyLanguage() {
        if (btnInventory != null) btnInventory.setText(isArabic ? "📊 المخزون" : "📊 Inventory");
        if (btnSales != null) btnSales.setText(isArabic ? "💰 المبيعات" : "💰 Sales");
        if (btnTransfers != null) btnTransfers.setText(isArabic ? "📦 التحويلات" : "📦 Transfers");
        if (btnAudit != null) btnAudit.setText(isArabic ? "🔍 الجرد" : "🔍 Audit");
        if (btnUsers != null) btnUsers.setText(isArabic ? "👥 المستخدمين" : "👥 Users");
        if (btnGates != null) btnGates.setText(isArabic ? "🚪 البوابات" : "🚪 Gates");
        if (btnPrint != null) btnPrint.setText(isArabic ? "🖨️ طباعة" : "🖨️ Print");
        if (btnShare != null) btnShare.setText(isArabic ? "📤 مشاركة" : "📤 Share");
        if (btnExport != null) btnExport.setText(isArabic ? "📥 تصدير" : "📥 Export");
    }

    private void setupListeners() {
        if (btnInventory != null) btnInventory.setOnClickListener(v -> { highlightButton(btnInventory); loadReport("inventory"); });
        if (btnSales != null) btnSales.setOnClickListener(v -> { highlightButton(btnSales); loadReport("sales"); });
        if (btnTransfers != null) btnTransfers.setOnClickListener(v -> { highlightButton(btnTransfers); loadReport("transfers"); });
        if (btnAudit != null) btnAudit.setOnClickListener(v -> { highlightButton(btnAudit); loadReport("audit"); });
        if (btnUsers != null) btnUsers.setOnClickListener(v -> { highlightButton(btnUsers); loadReport("users"); });
        if (btnGates != null) btnGates.setOnClickListener(v -> { highlightButton(btnGates); loadReport("gates"); });
        if (btnPrint != null) btnPrint.setOnClickListener(v -> printReport());
        if (btnShare != null) btnShare.setOnClickListener(v -> shareReport());
        if (btnExport != null) btnExport.setOnClickListener(v -> exportReport());
    }

    private void highlightButton(Button active) {
        Button[] all = {btnInventory, btnSales, btnTransfers, btnAudit, btnUsers, btnGates};
        for (Button b : all) {
            if (b != null) b.setAlpha(b == active ? 1f : 0.5f);
        }
    }

    // ==================== Load Reports ====================

    private void loadReport(String type) {
        currentReportType = type;
        tvReportTitle.setText(isArabic ? "جاري تحميل التقرير..." : "Loading report...");

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                MiraApiClient.ApiResponse response = null;
                switch (type) {
                    case "inventory": response = apiClient.getDashboardFull(); break;
                    case "sales": response = apiClient.getSalesHistory(); break;
                    case "transfers": response = apiClient.getTransfers(branchesManager.getActiveBranchId(), ""); break;
                    case "audit": response = apiClient.getLastSnapshot("Main Showroom"); break;
                    case "users": response = apiClient.getUsers(); break;
                    case "gates": response = apiClient.getGatesStats(); break;
                }

                final MiraApiClient.ApiResponse finalResponse = response;
                final String reportType = type;
                mainHandler.post(() -> {
                    if (finalResponse != null && finalResponse.isSuccess && finalResponse.data != null) {
                        renderReport(reportType, finalResponse.data);
                    } else {
                        webReport.loadData(getErrorHtml(reportType), "text/html", "UTF-8");
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> webReport.loadData(getErrorHtml(type), "text/html", "UTF-8"));
            }
        });
    }

    // ==================== Render Reports ====================

    private void renderReport(String type, JSONObject data) {
        String html = "";
        switch (type) {
            case "inventory": html = buildInventoryReport(data); break;
            case "sales": html = buildSalesReport(data); break;
            case "transfers": html = buildTransfersReport(data); break;
            case "audit": html = buildAuditReport(data); break;
            case "users": html = buildUsersReport(data); break;
            case "gates": html = buildGatesReport(data); break;
        }
        webReport.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    // ==================== Inventory Report ====================

    private String buildInventoryReport(JSONObject data) {
        tvReportTitle.setText(isArabic ? "📊 تقرير المخزون" : "📊 Inventory Report");
        JSONObject inv = data.optJSONObject("inventory");
        String orgName = orgProfile.getOrgName();
        String branchName = branchesManager.getActiveBranchDisplayName();
        String dir = isArabic ? "rtl" : "ltr";
        int totalItems = inv != null ? inv.optInt("total_items", 0) : 0;
        double totalWeight = inv != null ? inv.optDouble("total_weight", 0) : 0;
        JSONObject weights = inv != null ? inv.optJSONObject("weights") : null;
        int k24 = weights != null ? weights.optInt("k24", 0) : 0;
        int k21 = weights != null ? weights.optInt("k21", 0) : 0;
        int k18 = weights != null ? weights.optInt("k18", 0) : 0;

        return "<!DOCTYPE html><html dir='" + dir + "'><head><meta charset='UTF-8'><style>" +
            "body{font-family:'Cairo',sans-serif;padding:20px;direction:" + dir + ";color:#1a1a1a;background:#fff}" +
            ".header{text-align:center;border-bottom:2px solid #1A237E;padding-bottom:12px;margin-bottom:20px}" +
            ".header h1{color:#1A237E;font-size:20px;margin:0}.header p{color:#666;font-size:12px;margin:4px 0}" +
            ".cards{display:flex;gap:10px;margin-bottom:20px}" +
            ".card{flex:1;text-align:center;padding:16px;background:#f5f5f5;border-radius:10px}" +
            ".card .val{font-size:24px;font-weight:bold;color:#1A237E}.card .lbl{font-size:11px;color:#666}" +
            "table{width:100%;border-collapse:collapse;margin-top:16px}th{background:#1A237E;color:#fff;padding:10px}td{padding:8px;border-bottom:1px solid #eee;text-align:center}" +
            ".footer{text-align:center;margin-top:24px;font-size:10px;color:#999}" +
            "</style></head><body>" +
            "<div class='header'><h1>" + (isArabic ? "تقرير المخزون" : "Inventory Report") + "</h1>" +
            "<p>" + orgName + " | " + branchName + " | " + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()) + "</p></div>" +
            "<div class='cards'>" +
            "<div class='card'><div class='val'>" + totalItems + "</div><div class='lbl'>" + (isArabic ? "إجمالي القطع" : "Total Items") + "</div></div>" +
            "<div class='card'><div class='val'>" + String.format(Locale.US, "%.1f", totalWeight) + "</div><div class='lbl'>" + (isArabic ? "الوزن (جرام)" : "Weight (g)") + "</div></div>" +
            "</div>" +
            "<table><tr><th>" + (isArabic ? "العيار" : "Karat") + "</th><th>" + (isArabic ? "عدد القطع" : "Items") + "</th></tr>" +
            "<tr><td>24K</td><td>" + k24 + "</td></tr><tr><td>21K</td><td>" + k21 + "</td></tr><tr><td>18K</td><td>" + k18 + "</td></tr></table>" +
            "<div class='footer'>MIRA Jewelry Enterprise | " + (isArabic ? "تقرير آلي" : "Auto-generated") + " © 2026</div>" +
            "</body></html>";
    }

    // ==================== Sales Report ====================

    private String buildSalesReport(JSONObject data) {
        tvReportTitle.setText(isArabic ? "💰 تقرير المبيعات" : "💰 Sales Report");
        String orgName = orgProfile.getOrgName();
        String branchName = branchesManager.getActiveBranchDisplayName();
        String dir = isArabic ? "rtl" : "ltr";
        JSONObject sales = data.optJSONObject("sales");
        int countToday = sales != null ? sales.optInt("count_today", 0) : 0;
        int countMonth = sales != null ? sales.optInt("count_month", 0) : 0;
        int countYear = sales != null ? sales.optInt("count_year", 0) : 0;
        String today = sales != null ? sales.optString("today", "0") : "0";
        String month = sales != null ? sales.optString("month", "0") : "0";
        String year = sales != null ? sales.optString("year", "0") : "0";

        return "<!DOCTYPE html><html dir='" + dir + "'><head><meta charset='UTF-8'><style>" +
            "body{font-family:'Cairo',sans-serif;padding:20px;direction:" + dir + ";color:#1a1a1a;background:#fff}" +
            ".header{text-align:center;border-bottom:2px solid #1A237E;padding-bottom:12px;margin-bottom:20px}" +
            ".header h1{color:#1A237E;font-size:20px;margin:0}.header p{color:#666;font-size:12px;margin:4px 0}" +
            ".cards{display:flex;gap:10px;margin-bottom:20px}" +
            ".card{flex:1;text-align:center;padding:16px;background:#f5f5f5;border-radius:10px}" +
            ".card .val{font-size:20px;font-weight:bold;color:#1A237E}.card .lbl{font-size:11px;color:#666}" +
            ".footer{text-align:center;margin-top:24px;font-size:10px;color:#999}" +
            "</style></head><body>" +
            "<div class='header'><h1>" + (isArabic ? "تقرير المبيعات" : "Sales Report") + "</h1>" +
            "<p>" + orgName + " | " + branchName + " | " + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()) + "</p></div>" +
            "<div class='cards'>" +
            "<div class='card'><div class='val'>" + countToday + "</div><div class='lbl'>" + (isArabic ? "مبيعات اليوم" : "Today") + "</div><div class='lbl'>" + today + "</div></div>" +
            "<div class='card'><div class='val'>" + countMonth + "</div><div class='lbl'>" + (isArabic ? "مبيعات الشهر" : "Month") + "</div><div class='lbl'>" + month + "</div></div>" +
            "<div class='card'><div class='val'>" + countYear + "</div><div class='lbl'>" + (isArabic ? "مبيعات السنة" : "Year") + "</div><div class='lbl'>" + year + "</div></div>" +
            "</div>" +
            "<div class='footer'>MIRA Jewelry Enterprise | " + (isArabic ? "تقرير آلي" : "Auto-generated") + " © 2026</div>" +
            "</body></html>";
    }

    // ==================== Transfers Report ====================

    private String buildTransfersReport(JSONObject data) {
        tvReportTitle.setText(isArabic ? "📦 تقرير التحويلات" : "📦 Transfers Report");
        String orgName = orgProfile.getOrgName();
        String dir = isArabic ? "rtl" : "ltr";
        JSONArray transfers = data.optJSONArray("transfers");
        int total = transfers != null ? transfers.length() : 0;

        StringBuilder rows = new StringBuilder();
        if (transfers != null) {
            for (int i = 0; i < Math.min(transfers.length(), 20); i++) {
                JSONObject t = transfers.optJSONObject(i);
                if (t == null) continue;
                String status = t.optString("status", "");
                String statusAr = status.equals("received") ? "✅" : status.equals("in_transit") ? "🚚" : status.equals("pending") ? "⏳" : status;
                rows.append("<tr><td>").append(t.optString("transfer_number")).append("</td>")
                    .append("<td>").append(t.optString("from_branch_name")).append("</td>")
                    .append("<td>").append(t.optString("to_branch_name")).append("</td>")
                    .append("<td>").append(t.optInt("item_count")).append("</td>")
                    .append("<td>").append(statusAr).append("</td></tr>");
            }
        }

        return "<!DOCTYPE html><html dir='" + dir + "'><head><meta charset='UTF-8'><style>" +
            "body{font-family:'Cairo',sans-serif;padding:20px;direction:" + dir + ";color:#1a1a1a;background:#fff}" +
            ".header{text-align:center;border-bottom:2px solid #1A237E;padding-bottom:12px;margin-bottom:20px}" +
            ".header h1{color:#1A237E;font-size:20px;margin:0}.header p{color:#666;font-size:12px;margin:4px 0}" +
            "table{width:100%;border-collapse:collapse}th{background:#1A237E;color:#fff;padding:10px}td{padding:8px;border-bottom:1px solid #eee;text-align:center}" +
            ".footer{text-align:center;margin-top:24px;font-size:10px;color:#999}" +
            "</style></head><body>" +
            "<div class='header'><h1>" + (isArabic ? "تقرير التحويلات" : "Transfers Report") + "</h1>" +
            "<p>" + orgName + " | " + (isArabic ? "إجمالي: " : "Total: ") + total + " | " + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()) + "</p></div>" +
            "<table><tr><th>#</th><th>" + (isArabic ? "من" : "From") + "</th><th>" + (isArabic ? "إلى" : "To") + "</th><th>" + (isArabic ? "قطع" : "Items") + "</th><th>" + (isArabic ? "حالة" : "Status") + "</th></tr>" + rows.toString() + "</table>" +
            "<div class='footer'>MIRA Jewelry Enterprise © 2026</div>" +
            "</body></html>";
    }

    // ==================== Audit Report ====================
    private String buildAuditReport(JSONObject data) { return buildSimpleReport("audit", data); }
    // ==================== Users Report ====================
    private String buildUsersReport(JSONObject data) { return buildSimpleReport("users", data); }
    // ==================== Gates Report ====================
    private String buildGatesReport(JSONObject data) { return buildSimpleReport("gates", data); }

    private String buildSimpleReport(String type, JSONObject data) {
        String orgName = orgProfile.getOrgName();
        String dir = isArabic ? "rtl" : "ltr";
        String title = "";
        switch (type) {
            case "audit": title = isArabic ? "تقرير الجرد والتدقيق" : "Audit Report"; break;
            case "users": title = isArabic ? "تقرير المستخدمين" : "Users Report"; break;
            case "gates": title = isArabic ? "تقرير البوابات" : "Gates Report"; break;
        }
        return "<!DOCTYPE html><html dir='" + dir + "'><head><meta charset='UTF-8'><style>" +
            "body{font-family:'Cairo',sans-serif;padding:20px;direction:" + dir + ";color:#1a1a1a;background:#fff}" +
            ".header{text-align:center;border-bottom:2px solid #1A237E;padding-bottom:12px;margin-bottom:20px}" +
            ".header h1{color:#1A237E;font-size:20px;margin:0}.header p{color:#666;font-size:12px;margin:4px 0}" +
            ".footer{text-align:center;margin-top:24px;font-size:10px;color:#999}" +
            "</style></head><body>" +
            "<div class='header'><h1>" + title + "</h1>" +
            "<p>" + orgName + " | " + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()) + "</p></div>" +
            "<div style='text-align:center;padding:40px;color:#666'>" + (isArabic ? "البيانات الكاملة متاحة في التقارير المتقدمة" : "Full data available in advanced reports") + "</div>" +
            "<div class='footer'>MIRA Jewelry Enterprise © 2026</div>" +
            "</body></html>";
    }

    // ==================== Actions ====================

    private void printReport() {
        if (getContext() == null) return;
        PrintManager pm = (PrintManager) requireContext().getSystemService(android.content.Context.PRINT_SERVICE);
        PrintDocumentAdapter adapter = webReport.createPrintDocumentAdapter("MIRA_Report_" + currentReportType);
        pm.print("MIRA Report - " + currentReportType, adapter, new PrintAttributes.Builder().build());
    }

    private void shareReport() {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/html");
        share.putExtra(Intent.EXTRA_SUBJECT, "MIRA Report - " + currentReportType);
        share.putExtra(Intent.EXTRA_TEXT, webReport.getUrl());
        startActivity(Intent.createChooser(share, isArabic ? "مشاركة التقرير" : "Share Report"));
    }

    private void exportReport() {
        Toast.makeText(getContext(), isArabic ? "📥 جاري تصدير التقرير..." : "📥 Exporting report...", Toast.LENGTH_SHORT).show();
    }

    private String getErrorHtml(String type) {
        String dir = isArabic ? "rtl" : "ltr";
        return "<!DOCTYPE html><html dir='" + dir + "'><head><meta charset='UTF-8'></head><body style='font-family:sans-serif;text-align:center;padding:40px;color:#EF4444'>" +
            "<h2>" + (isArabic ? "⚠️ تعذر تحميل التقرير" : "⚠️ Failed to load report") + "</h2>" +
            "<p>" + (isArabic ? "تأكد من اتصال الانترنت وحاول مرة أخرى" : "Check your connection and try again") + "</p></body></html>";
    }
}
