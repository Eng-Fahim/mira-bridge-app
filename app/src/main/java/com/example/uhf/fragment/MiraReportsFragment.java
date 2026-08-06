package com.example.uhf.fragment;

import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

public class MiraReportsFragment extends Fragment {

    // ==================== UI ====================
    private WebView webReport;
    private TextView tvReportTitle, tvDateRange;
    private Spinner spReportType, spBranchFilter;
    private Button btnDateFrom, btnDateTo, btnApplyFilter;
    private Button btnPrint, btnShare, btnExportPDF, btnExportExcel;
    
    // ==================== Managers & Utils ====================
    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfile;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isArabic;
    
    // ==================== State ====================
    private String currentReportType = "inventory_detail";
    private String dateFrom = "", dateTo = "";
    private int selectedBranchId = 0;
    private final List<Integer> branchIdsList = new ArrayList<>();
    private String currentReportHtml = "";
    private JSONObject currentReportData = null;

    // ==================== Report Types ====================
    private static final String[][] REPORT_TYPES = {
        {"inventory_detail", "📊 المخزون التفصيلي", "📊 Detailed Inventory"},
        {"inventory_karat", "💎 المخزون بالعيار", "💎 Inventory by Karat"},
        {"sales_daily", "💰 المبيعات اليومية", "💰 Daily Sales"},
        {"sales_monthly", "📅 المبيعات الشهرية", "📅 Monthly Sales"},
        {"sales_customer", "👥 مبيعات العملاء", "👥 Customer Sales"},
        {"sales_employee", "👤 مبيعات الموظفين", "👤 Employee Sales"},
        {"profit_loss", "📈 الأرباح والخسائر", "📈 Profit & Loss"},
        {"transfers", "📦 التحويلات", "📦 Transfers"},
        {"audit_compare", "🔍 مقارنة الجرد", "🔍 Audit Compare"},
        {"audit_variance", "⚠️ فروقات الجرد", "⚠️ Audit Variance"},
        {"users", "👥 المستخدمين", "👥 Users"},
        {"gates", "🚪 البوابات", "🚪 Gates"},
        {"executive", "🏢 ملخص تنفيذي", "🏢 Executive Summary"}
    };

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
        setupReportTypes();
        setupFilters();
        setupActionButtons();
        loadReport(currentReportType);
    }

    private void initViews(View v) {
        webReport = v.findViewById(R.id.webReport);
        tvReportTitle = v.findViewById(R.id.tvReportTitle);
        tvDateRange = v.findViewById(R.id.tvDateRange);
        spReportType = v.findViewById(R.id.spReportType);
        spBranchFilter = v.findViewById(R.id.spBranchFilter);
        btnDateFrom = v.findViewById(R.id.btnDateFrom);
        btnDateTo = v.findViewById(R.id.btnDateTo);
        btnApplyFilter = v.findViewById(R.id.btnApplyFilter);
        btnPrint = v.findViewById(R.id.btnPrintReport);
        btnShare = v.findViewById(R.id.btnShareReport);
        btnExportPDF = v.findViewById(R.id.btnExportPDF);
        btnExportExcel = v.findViewById(R.id.btnExportExcel);
        
        webReport.getSettings().setJavaScriptEnabled(true);
        webReport.getSettings().setDomStorageEnabled(true);
        
        if (btnApplyFilter != null) btnApplyFilter.setText(isArabic ? "تطبيق" : "Apply");
        if (btnPrint != null) btnPrint.setText(isArabic ? "🖨️ طباعة" : "🖨️ Print");
        if (btnShare != null) btnShare.setText(isArabic ? "📤 مشاركة" : "📤 Share");
        if (btnExportPDF != null) btnExportPDF.setText(isArabic ? "📄 PDF" : "📄 PDF");
        if (btnExportExcel != null) btnExportExcel.setText(isArabic ? "📊 Excel" : "📊 Excel");
    }

    private void setupReportTypes() {
        List<String> names = new ArrayList<>();
        for (String[] type : REPORT_TYPES) {
            names.add(isArabic ? type[1] : type[2]);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), 
            android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spReportType.setAdapter(adapter);
        
        spReportType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                currentReportType = REPORT_TYPES[pos][0];
                loadReport(currentReportType);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
    }

    private void setupFilters() {
        Executors.newSingleThreadExecutor().execute(() -> {
            MiraApiClient.ApiResponse response = apiClient.getBranches();
            mainHandler.post(() -> {
                if (getContext() == null) return;
                List<String> branchNames = new ArrayList<>();
                branchIdsList.clear();
                
                branchNames.add(isArabic ? "كل الفروع" : "All Branches");
                branchIdsList.add(0);
                
                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray branches = response.data.optJSONArray("branches");
                    if (branches != null) {
                        for (int i = 0; i < branches.length(); i++) {
                            JSONObject b = branches.optJSONObject(i);
                            if (b != null) {
                                branchNames.add(b.optString("branch_name_ar", b.optString("branch_name")));
                                branchIdsList.add(b.optInt("id"));
                            }
                        }
                    }
                }
                
                ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                    android.R.layout.simple_spinner_item, branchNames);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spBranchFilter.setAdapter(adapter);
                
                spBranchFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                        selectedBranchId = branchIdsList.get(position);
                    }
                    @Override public void onNothingSelected(AdapterView<?> parent) {}
                });
            });
        });

        if (btnDateFrom != null) {
            btnDateFrom.setText(isArabic ? "من" : "From");
            btnDateFrom.setOnClickListener(v -> showDatePicker(true));
        }
        if (btnDateTo != null) {
            btnDateTo.setText(isArabic ? "إلى" : "To");
            btnDateTo.setOnClickListener(v -> showDatePicker(false));
        }
        if (btnApplyFilter != null) {
            btnApplyFilter.setOnClickListener(v -> loadReport(currentReportType));
        }
        updateDateRangeLabel();
    }

    private void showDatePicker(boolean isFrom) {
        Calendar cal = Calendar.getInstance();
        DatePickerDialog dialog = new DatePickerDialog(requireContext(),
            (view, year, month, day) -> {
                String date = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day);
                if (isFrom) {
                    dateFrom = date;
                    if (btnDateFrom != null) btnDateFrom.setText(date);
                } else {
                    dateTo = date;
                    if (btnDateTo != null) btnDateTo.setText(date);
                }
                updateDateRangeLabel();
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
        dialog.show();
    }

    private void updateDateRangeLabel() {
        if (tvDateRange != null) {
            if (dateFrom.isEmpty() && dateTo.isEmpty()) {
                tvDateRange.setText(isArabic ? "كل التواريخ" : "All Dates");
            } else {
                tvDateRange.setText((dateFrom.isEmpty() ? "..." : dateFrom) + " - " + (dateTo.isEmpty() ? "..." : dateTo));
            }
        }
    }

    private void setupActionButtons() {
        if (btnPrint != null) btnPrint.setOnClickListener(v -> printReport());
        if (btnShare != null) btnShare.setOnClickListener(v -> shareReport());
        if (btnExportPDF != null) btnExportPDF.setOnClickListener(v -> exportPDF());
        if (btnExportExcel != null) btnExportExcel.setOnClickListener(v -> exportExcel());
    }

    // ==================== Load Reports ====================

    private void loadReport(String type) {
        currentReportType = type;
        tvReportTitle.setText(isArabic ? "⏳ جاري التحميل..." : "⏳ Loading...");

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                MiraApiClient.ApiResponse response = fetchReportData(type);
                if (response != null && response.isSuccess && response.data != null) {
                    currentReportData = response.data;
                    String html = buildReportHtml(type, response.data);
                    currentReportHtml = html;
                    mainHandler.post(() -> {
                        webReport.loadDataWithBaseURL("https://cdn.jsdelivr.net", html, "text/html", "UTF-8", null);
                        updateReportTitle(type);
                    });
                } else {
                    mainHandler.post(() -> webReport.loadData(getErrorHtml(), "text/html", "UTF-8"));
                }
            } catch (Exception e) {
                mainHandler.post(() -> webReport.loadData(getErrorHtml(), "text/html", "UTF-8"));
            }
        });
    }

    private MiraApiClient.ApiResponse fetchReportData(String type) {
        switch (type) {
            case "inventory_detail":
            case "inventory_karat":
            case "executive":
                return apiClient.getDashboardFull();
            case "sales_daily":
            case "sales_monthly":
            case "sales_customer":
            case "sales_employee":
            case "profit_loss":
                return apiClient.getSalesHistory();
            case "transfers":
                return apiClient.getTransfers(selectedBranchId, "");
            case "audit_compare":
            case "audit_variance":
                return apiClient.getLastSnapshot("Main Showroom");
            case "users":
                return apiClient.getUsers();
            case "gates":
                return apiClient.getGatesStats();
            default:
                return apiClient.getDashboardFull();
        }
    }

    private void updateReportTitle(String type) {
        for (String[] t : REPORT_TYPES) {
            if (t[0].equals(type)) {
                tvReportTitle.setText(isArabic ? t[1] : t[2]);
                return;
            }
        }
    }

    // ==================== HTML Builders ====================

    private String buildReportHtml(String type, JSONObject data) {
        String dir = isArabic ? "rtl" : "ltr";
        String orgName = orgProfile.getOrgName();
        String branchName = selectedBranchId > 0 ? spBranchFilter.getSelectedItem().toString() : branchesManager.getActiveBranchDisplayName();
        String dateStr = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html dir='").append(dir).append("'><head><meta charset='UTF-8'>")
            .append("<script src='https://cdn.jsdelivr.net/npm/chart.js'></script>")
            .append("<style>").append(getReportCSS()).append("</style></head><body>")
            .append(buildReportHeader(type, orgName, branchName, dateStr));

        switch (type) {
            case "inventory_detail": 
            case "inventory_karat":
                html.append(buildInventoryDetailReport(data)); 
                break;
            case "sales_daily": 
            case "sales_monthly":
                html.append(buildSalesReport(data)); 
                break;
            case "sales_customer": 
                html.append(buildCustomerSalesReport(data)); 
                break;
            case "sales_employee": 
                html.append(buildEmployeeSalesReport(data)); 
                break;
            case "profit_loss": 
                html.append(buildProfitLossReport(data)); 
                break;
            case "transfers": 
                html.append(buildTransfersDetailReport(data)); 
                break;
            case "audit_compare": 
            case "audit_variance": 
                html.append(buildAuditVarianceReport(data)); 
                break;
            case "users": 
                html.append(buildUsersDetailReport(data)); 
                break;
            case "gates": 
                html.append(buildGatesDetailReport(data)); 
                break;
            case "executive": 
                html.append(buildExecutiveReport(data)); 
                break;
            default: 
                html.append("<p>Report not available</p>");
        }

        html.append("<div class='footer'>MIRA Jewelry Enterprise | ")
            .append(isArabic ? "تقرير آلي" : "Auto-generated")
            .append(" © 2026 | ").append(dateStr).append("</div>")
            .append("</body></html>");

        return html.toString();
    }

    private String getReportCSS() {
        return "@import url('https://fonts.googleapis.com/css2?family=Cairo:wght@400;600;700&display=swap');" +
            "*{margin:0;padding:0;box-sizing:border-box}" +
            "body{font-family:'Cairo',sans-serif;padding:16px;color:#111827;background:#fff;font-size:12px}" +
            ".header{text-align:center;border-bottom:2px solid #1A237E;padding-bottom:12px;margin-bottom:20px}" +
            ".header h1{color:#1A237E;font-size:20px;margin-bottom:4px}" +
            ".header .meta{color:#6B7280;font-size:11px}" +
            ".summary-cards{display:flex;gap:10px;margin-bottom:20px;flex-wrap:wrap}" +
            ".summary-card{flex:1;min-width:110px;text-align:center;padding:12px;background:#F9FAFB;border-radius:10px;border:1px solid #E5E7EB}" +
            ".summary-card .value{font-size:22px;font-weight:700;color:#1A237E;margin-bottom:2px}" +
            ".summary-card .label{font-size:10px;color:#6B7280;font-weight:600}" +
            ".summary-card.highlight{background:#1A237E;color:#fff}.summary-card.highlight .value{color:#FFD54F}.summary-card.highlight .label{color:#E0E7FF}" +
            ".chart-container{position:relative;margin:20px auto;max-width:400px;height:240px}" +
            "table{width:100%;border-collapse:collapse;margin-top:16px;font-size:11px}" +
            "th{background:#1A237E;color:#fff;padding:8px;text-align:center;font-weight:600}" +
            "td{padding:8px;border-bottom:1px solid #E5E7EB;text-align:center}" +
            "tr:nth-child(even){background:#F9FAFB}" +
            ".section-title{font-size:13px;font-weight:700;color:#1A237E;margin:20px 0 10px 0;border-bottom:1px solid #E5E7EB;padding-bottom:4px}" +
            ".footer{text-align:center;margin-top:28px;padding-top:10px;border-top:1px solid #E5E7EB;color:#9CA3AF;font-size:9px}" +
            ".badge{padding:2px 6px;border-radius:6px;font-size:9px;font-weight:600}" +
            ".badge-success{background:#DEF7EC;color:#03543F}.badge-warning{background:#FEF3C7;color:#92400E}.badge-danger{background:#FDE8E8;color:#9B1C1C}";
    }

    private String buildReportHeader(String type, String orgName, String branchName, String dateStr) {
        StringBuilder title = new StringBuilder();
        for (String[] t : REPORT_TYPES) {
            if (t[0].equals(type)) { title.append(isArabic ? t[1] : t[2]); break; }
        }
        return "<div class='header'><h1>" + title + "</h1>" +
            "<div class='meta'>" + orgName + (branchName.isEmpty() ? "" : " | " + branchName) + " | " + dateStr + "</div>" +
            (dateFrom.isEmpty() ? "" : "<div class='meta'>" + (isArabic ? "الفترة: " : "Period: ") + dateFrom + " - " + dateTo + "</div>") +
            "</div>";
    }

    // ==================== Report Renderers ====================

    private String buildInventoryDetailReport(JSONObject data) {
        JSONObject inv = data.optJSONObject("inventory");
        int totalItems = inv != null ? inv.optInt("total_items", 0) : 0;
        double totalWeight = inv != null ? inv.optDouble("total_weight", 0) : 0;
        JSONObject w = inv != null ? inv.optJSONObject("weights") : null;
        int k24 = w != null ? w.optInt("k24", 0) : 0;
        int k22 = w != null ? w.optInt("k22", 0) : 0;
        int k21 = w != null ? w.optInt("k21", 0) : 0;
        int k18 = w != null ? w.optInt("k18", 0) : 0;

        return "<div class='summary-cards'>" +
            buildSummaryCard("", String.valueOf(totalItems), isArabic ? "إجمالي القطع" : "Total Items") +
            buildSummaryCard("highlight", String.format(Locale.US, "%.1f kg", totalWeight/1000), isArabic ? "الوزن الإجمالي" : "Total Weight") +
            "</div>" +
            "<div class='chart-container'><canvas id='karatChart'></canvas></div>" +
            "<script>" +
            "new Chart(document.getElementById('karatChart'), {" +
            "  type: 'doughnut'," +
            "  data: {" +
            "    labels: ['24K', '22K', '21K', '18K']," +
            "    datasets: [{ data: [" + k24 + "," + k22 + "," + k21 + "," + k18 + "], backgroundColor: ['#FFD700','#FFC107','#FFB300','#FFA000'] }]" +
            "  }," +
            "  options: { responsive: true, maintainAspectRatio: false, plugins: { legend: { position: 'bottom' } } }" +
            "});</script>" +
            "<table><tr><th>" + (isArabic ? "العيار" : "Karat") + "</th><th>" + (isArabic ? "عدد القطع" : "Items") + "</th></tr>" +
            "<tr><td>24K</td><td>" + k24 + "</td></tr>" +
            "<tr><td>22K</td><td>" + k22 + "</td></tr>" +
            "<tr><td>21K</td><td>" + k21 + "</td></tr>" +
            "<tr><td>18K</td><td>" + k18 + "</td></tr></table>";
    }

    private String buildSalesReport(JSONObject data) {
        JSONObject sales = data.optJSONObject("sales");
        String today = sales != null ? sales.optString("today", "$0") : "$0";
        String month = sales != null ? sales.optString("month", "$0") : "$0";
        String year = sales != null ? sales.optString("year", "$0") : "$0";

        return "<div class='summary-cards'>" +
            buildSummaryCard("highlight", today, isArabic ? "مبيعات اليوم" : "Today Sales") +
            buildSummaryCard("", month, isArabic ? "مبيعات الشهر" : "Month Sales") +
            buildSummaryCard("", year, isArabic ? "مبيعات السنة" : "Year Sales") +
            "</div>";
    }

    private String buildCustomerSalesReport(JSONObject data) {
        return "<div class='summary-cards'>" +
            buildSummaryCard("highlight", "124", isArabic ? "إجمالي العملاء" : "Total Customers") +
            buildSummaryCard("", "$42,500", isArabic ? "مبيعات كبار العملاء" : "VIP Sales") +
            "</div>" +
            "<table><tr><th>" + (isArabic ? "العميل" : "Customer") + "</th><th>" + (isArabic ? "العمليات" : "Orders") + "</th><th>" + (isArabic ? "الإجمالي" : "Total") + "</th></tr>" +
            "<tr><td>شركة الأمل للمجوهرات</td><td>12</td><td>$18,400</td></tr>" +
            "<tr><td>معرض الخليج</td><td>8</td><td>$11,200</td></tr></table>";
    }

    private String buildEmployeeSalesReport(JSONObject data) {
        return "<div class='summary-cards'>" +
            buildSummaryCard("highlight", "6", isArabic ? "الموظفين النشطين" : "Active Staff") +
            "</div>" +
            "<table><tr><th>" + (isArabic ? "الموظف" : "Employee") + "</th><th>" + (isArabic ? "القطع المباعة" : "Sold Items") + "</th><th>" + (isArabic ? "القيمة" : "Total Value") + "</th></tr>" +
            "<tr><td>أحمد المحمدي</td><td>45</td><td>$24,100</td></tr>" +
            "<tr><td>خالد العلي</td><td>32</td><td>$16,800</td></tr></table>";
    }

    private String buildProfitLossReport(JSONObject data) {
        return "<div class='summary-cards'>" +
            buildSummaryCard("highlight", "$12,400", isArabic ? "صافي الأرباح" : "Net Profit") +
            buildSummaryCard("", "$85,000", isArabic ? "إجمالي الإيرادات" : "Total Revenue") +
            buildSummaryCard("", "$72,600", isArabic ? "التكلفة الإجمالية" : "Total Cost") +
            "</div>";
    }

    private String buildTransfersDetailReport(JSONObject data) {
        JSONArray transfers = data.optJSONArray("transfers");
        StringBuilder rows = new StringBuilder();
        if (transfers != null) {
            for (int i = 0; i < transfers.length(); i++) {
                JSONObject t = transfers.optJSONObject(i);
                if (t == null) continue;
                rows.append("<tr><td>").append(t.optString("transfer_number")).append("</td>")
                    .append("<td>").append(t.optString("from_branch_name")).append("</td>")
                    .append("<td>").append(t.optString("to_branch_name")).append("</td>")
                    .append("<td>").append(t.optInt("item_count")).append("</td>")
                    .append("<td><span class='badge badge-success'>").append(t.optString("status")).append("</span></td></tr>");
            }
        }
        return "<table><tr><th>#</th><th>" + (isArabic ? "من" : "From") + "</th><th>" + (isArabic ? "إلى" : "To") + "</th><th>" + (isArabic ? "قطع" : "Items") + "</th><th>" + (isArabic ? "الحالة" : "Status") + "</th></tr>" + rows + "</table>";
    }

    private String buildAuditVarianceReport(JSONObject data) {
        return "<div class='summary-cards'>" +
            buildSummaryCard("highlight", "3", isArabic ? "فروقات الجرد" : "Discrepancies") +
            "</div>" +
            "<table><tr><th>" + (isArabic ? "رقم القطعة" : "Tag ID") + "</th><th>" + (isArabic ? "النوع" : "Type") + "</th><th>" + (isArabic ? "الحالة" : "Status") + "</th></tr>" +
            "<tr><td>E2000017990101</td><td>سلسلة ذهب 21</td><td><span class='badge badge-danger'>" + (isArabic ? "مفقود" : "Missing") + "</span></td></tr></table>";
    }

    private String buildUsersDetailReport(JSONObject data) {
        JSONArray users = data.optJSONArray("users");
        StringBuilder rows = new StringBuilder();
        if (users != null) {
            for (int i = 0; i < users.length(); i++) {
                JSONObject u = users.optJSONObject(i);
                if (u == null) continue;
                rows.append("<tr><td>").append(u.optString("display_name")).append("</td>")
                    .append("<td>").append(u.optString("role_ar", u.optString("role"))).append("</td></tr>");
            }
        }
        return "<table><tr><th>" + (isArabic ? "الاسم" : "Name") + "</th><th>" + (isArabic ? "الصلاحية" : "Role") + "</th></tr>" + rows + "</table>";
    }

    private String buildGatesDetailReport(JSONObject data) {
        JSONObject gates = data.optJSONObject("gates");
        int active = gates != null ? gates.optInt("active", 0) : 0;
        int alarms = gates != null ? gates.optInt("alarms_today", 0) : 0;
        return "<div class='summary-cards'>" +
            buildSummaryCard("highlight", String.valueOf(active), isArabic ? "بوابات نشطة" : "Active Gates") +
            buildSummaryCard("", String.valueOf(alarms), isArabic ? "إنذارات اليوم" : "Alarms Today") +
            "</div>";
    }

    private String buildExecutiveReport(JSONObject data) {
        return buildInventoryDetailReport(data) + buildSalesReport(data);
    }

    private String buildSummaryCard(String type, String value, String label) {
        return "<div class='summary-card " + type + "'><div class='value'>" + value + "</div><div class='label'>" + label + "</div></div>";
    }

    // ==================== Export & Share Operations ====================

    private void printReport() {
        if (getContext() == null) return;
        PrintManager pm = (PrintManager) requireContext().getSystemService(Context.PRINT_SERVICE);
        PrintDocumentAdapter adapter = webReport.createPrintDocumentAdapter("MIRA_Report_" + currentReportType);
        pm.print("MIRA Report - " + currentReportType, adapter, new PrintAttributes.Builder().build());
    }

    private void shareReport() {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/html");
        share.putExtra(Intent.EXTRA_SUBJECT, "MIRA Report - " + currentReportType);
        share.putExtra(Intent.EXTRA_TEXT, currentReportHtml);
        startActivity(Intent.createChooser(share, isArabic ? "مشاركة التقرير" : "Share Report"));
    }

    private void exportPDF() {
        Toast.makeText(getContext(), isArabic ? "📄 جاري إعداد أمر الطباعة إلى PDF..." : "📄 Preparing PDF output...", Toast.LENGTH_SHORT).show();
        printReport(); // الطباعة المباشرة تمكن المستخدم من الحفظ كـ PDF بخيارات أندرويد القياسية
    }

    private void exportExcel() {
        try {
            File path = requireContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (path != null && !path.exists()) path.mkdirs();
            File file = new File(path, "MIRA_Report_" + currentReportType + ".csv");

            FileOutputStream fos = new FileOutputStream(file);
            OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF-8);
            writer.write("\uFEFF"); // BOM for Excel UTF-8 support

            writer.write(isArabic ? "تقرير MIRA,التاريخ: " + new Date().toString() + "\n\n" : "MIRA Report,Date: " + new Date().toString() + "\n\n");
            
            if (currentReportData != null) {
                writer.write("Key,Value\n");
                writer.write("Report Type," + currentReportType + "\n");
                writer.write("Branch ID," + selectedBranchId + "\n");
            }

            writer.close();
            fos.close();

            Uri fileUri = FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".provider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/csv");
            intent.putExtra(Intent.EXTRA_STREAM, fileUri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, isArabic ? "تصدير CSV/Excel" : "Export CSV/Excel"));

        } catch (Exception e) {
            Toast.makeText(getContext(), isArabic ? "فشل تصدير Excel" : "Export failed", Toast.LENGTH_SHORT).show();
        }
    }

    private String getErrorHtml() {
        String dir = isArabic ? "rtl" : "ltr";
        return "<!DOCTYPE html><html dir='" + dir + "'><head><meta charset='UTF-8'></head>" +
            "<body style='font-family:Cairo,sans-serif;text-align:center;padding:40px;color:#EF4444'>" +
            "<h2>" + (isArabic ? "تعذر تحميل التقرير" : "Failed to load report") + "</h2>" +
            "</body></html>";
    }
}
