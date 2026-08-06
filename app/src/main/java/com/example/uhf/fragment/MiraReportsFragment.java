package com.example.uhf.fragment;

import android.app.DatePickerDialog;
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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
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

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

/**
 * MIRA Reports Center - مركز التقارير المتكامل
 * 
 * التقارير المتاحة:
 * 1. 📊 المخزون التفصيلي (بالعيار، الفرع، الفئة)
 * 2. 💰 المبيعات (يومي، شهري، سنوي، حسب العميل، حسب الموظف)
 * 3. 📦 التحويلات بين الفروع
 * 4. 🔍 الجرد والمطابقة (مقارنة، فروقات، نواقص)
 * 5. 👥 المستخدمين والصلاحيات
 * 6. 🚪 البوابات الأمنية والإنذارات
 * 7. 📈 الأرباح والإيرادات
 * 8. 🏢 ملخص تنفيذي لجميع الفروع
 */
public class MiraReportsFragment extends Fragment {

    // ==================== UI ====================
    private WebView webReport;
    private TextView tvReportTitle, tvDateRange;
    private Spinner spReportType, spBranchFilter;
    private Button btnDateFrom, btnDateTo, btnApplyFilter;
    private Button btnPrint, btnShare, btnExportPDF, btnExportExcel;
    private LinearLayout layoutFilters, layoutActions;
    
    // ==================== Managers ====================
    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private MiraOrgProfileManager orgProfile;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isArabic;
    
    // ==================== State ====================
    private String currentReportType = "inventory_detail";
    private String dateFrom = "", dateTo = "";
    private int selectedBranchId = 0;
    private String currentReportHtml = "";

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
        loadReport("inventory_detail");
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
        layoutFilters = v.findViewById(R.id.layoutReportFilters);
        layoutActions = v.findViewById(R.id.layoutReportActions);
        
        webReport.getSettings().setJavaScriptEnabled(true);
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
        // تحميل الفروع
        Executors.newSingleThreadExecutor().execute(() -> {
            MiraApiClient.ApiResponse response = apiClient.getBranches();
            mainHandler.post(() -> {
                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray branches = response.data.optJSONArray("branches");
                    List<String> branchNames = new ArrayList<>();
                    List<Integer> branchIds = new ArrayList<>();
                    
                    branchNames.add(isArabic ? "كل الفروع" : "All Branches");
                    branchIds.add(0);
                    
                    if (branches != null) {
                        for (int i = 0; i < branches.length(); i++) {
                            JSONObject b = branches.optJSONObject(i);
                            if (b != null) {
                                branchNames.add(b.optString("branch_name_ar", b.optString("branch_name")));
                                branchIds.add(b.optInt("id"));
                            }
                        }
                    }
                    
                    ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                        android.R.layout.simple_spinner_item, branchNames);
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    spBranchFilter.setAdapter(adapter);
                }
            });
        });
        
        // التاريخ
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
        
        // تحديث branch filter
        selectedBranchId = spBranchFilter.getSelectedItemPosition() > 0 ? 
            spBranchFilter.getSelectedItemPosition() : 0;

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                MiraApiClient.ApiResponse response = fetchReportData(type);
                
                if (response != null && response.isSuccess && response.data != null) {
                    String html = buildReportHtml(type, response.data);
                    currentReportHtml = html;
                    mainHandler.post(() -> {
                        webReport.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
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
            case "executive":
                return apiClient.getDashboardFull();
            default:
                return null;
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
        String branchName = selectedBranchId > 0 ? "" : branchesManager.getActiveBranchDisplayName();
        String dateStr = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());
        
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html dir='").append(dir).append("'><head><meta charset='UTF-8'><style>")
            .append(getReportCSS())
            .append("</style></head><body>")
            .append(buildReportHeader(type, orgName, branchName, dateStr));
        
        switch (type) {
            case "inventory_detail": html.append(buildInventoryDetailReport(data)); break;
            case "inventory_karat": html.append(buildInventoryKaratReport(data)); break;
            case "sales_daily": html.append(buildSalesReport(data, "daily")); break;
            case "sales_monthly": html.append(buildSalesReport(data, "monthly")); break;
            case "sales_customer": html.append(buildCustomerSalesReport(data)); break;
            case "sales_employee": html.append(buildEmployeeSalesReport(data)); break;
            case "profit_loss": html.append(buildProfitLossReport(data)); break;
            case "transfers": html.append(buildTransfersDetailReport(data)); break;
            case "audit_compare": html.append(buildAuditCompareReport(data)); break;
            case "audit_variance": html.append(buildAuditVarianceReport(data)); break;
            case "users": html.append(buildUsersDetailReport(data)); break;
            case "gates": html.append(buildGatesDetailReport(data)); break;
            case "executive": html.append(buildExecutiveReport(data)); break;
            default: html.append("<p>Report not available</p>");
        }
        
        html.append("<div class='footer'>MIRA Jewelry Enterprise | ").append(isArabic ? "تقرير آلي" : "Auto-generated").append(" © 2026 | ").append(dateStr).append("</div>");
        html.append("</body></html>");
        
        return html.toString();
    }

    private String getReportCSS() {
        return "@import url('https://fonts.googleapis.com/css2?family=Cairo:wght@400;600;700&display=swap');" +
            "*{margin:0;padding:0;box-sizing:border-box}" +
            "body{font-family:'Cairo',sans-serif;padding:20px;color:#1a1a1a;background:#fff;font-size:12px}" +
            ".header{text-align:center;border-bottom:3px solid #1A237E;padding-bottom:16px;margin-bottom:24px}" +
            ".header h1{color:#1A237E;font-size:22px;margin:0 0 4px 0}" +
            ".header .meta{color:#666;font-size:11px}" +
            ".summary-cards{display:flex;gap:12px;margin-bottom:24px;flex-wrap:wrap}" +
            ".summary-card{flex:1;min-width:120px;text-align:center;padding:16px;background:linear-gradient(135deg,#f5f7fa,#e4e8ed);border-radius:12px;border:1px solid #e0e0e0}" +
            ".summary-card .value{font-size:26px;font-weight:700;color:#1A237E;margin-bottom:4px}" +
            ".summary-card .label{font-size:10px;color:#666;text-transform:uppercase}" +
            ".summary-card.highlight{background:linear-gradient(135deg,#1A237E,#283593);color:#fff}" +
            ".summary-card.highlight .value{color:#FFD54F}.summary-card.highlight .label{color:#E8EAF6}" +
            ".summary-card.warning{background:linear-gradient(135deg,#FFF3E0,#FFE0B2)}.summary-card.warning .value{color:#E65100}" +
            ".summary-card.success{background:linear-gradient(135deg,#E8F5E9,#C8E6C9)}.summary-card.success .value{color:#1B5E20}" +
            ".summary-card.danger{background:linear-gradient(135deg,#FFEBEE,#FFCDD2)}.summary-card.danger .value{color:#B71C1C}" +
            "table{width:100%;border-collapse:collapse;margin-top:20px;font-size:11px}" +
            "th{background:#1A237E;color:#fff;padding:10px 8px;text-align:center;font-weight:600}" +
            "td{padding:8px;border-bottom:1px solid #eee;text-align:center}" +
            "tr:nth-child(even){background:#fafafa}tr:hover{background:#f0f0f0}" +
            ".section-title{font-size:14px;font-weight:700;color:#1A237E;margin:24px 0 12px 0;padding-bottom:6px;border-bottom:1px solid #e0e0e0}" +
            ".footer{text-align:center;margin-top:32px;padding-top:12px;border-top:1px solid #eee;color:#999;font-size:9px}" +
            ".badge{padding:2px 8px;border-radius:10px;font-size:10px;font-weight:600}" +
            ".badge-success{background:#E8F5E9;color:#1B5E20}.badge-warning{background:#FFF3E0;color:#E65100}.badge-danger{background:#FFEBEE;color:#B71C1C}" +
            ".badge-info{background:#E3F2FD;color:#0D47A1}" +
            "@media print{body{padding:0}.summary-card{break-inside:avoid}}";
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

    // ==================== تقرير المخزون التفصيلي ====================
    
    private String buildInventoryDetailReport(JSONObject data) {
        JSONObject inv = data.optJSONObject("inventory");
        int totalItems = inv != null ? inv.optInt("total_items", 0) : 0;
        double totalWeight = inv != null ? inv.optDouble("total_weight", 0) : 0;
        JSONObject w = inv != null ? inv.optJSONObject("weights") : null;
        int k24 = w != null ? w.optInt("k24", 0) : 0;
        int k21 = w != null ? w.optInt("k21", 0) : 0;
        int k18 = w != null ? w.optInt("k18", 0) : 0;
        int k22 = w != null ? w.optInt("k22", 0) : 0;
        
        JSONObject sales = data.optJSONObject("sales");
        int soldToday = sales != null ? sales.optInt("count_today", 0) : 0;
        int soldMonth = sales != null ? sales.optInt("count_month", 0) : 0;
        String salesToday = sales != null ? sales.optString("today", "$0") : "$0";
        String salesMonth = sales != null ? sales.optString("month", "$0") : "$0";
        
        return "<div class='summary-cards'>" +
            buildSummaryCard("", String.valueOf(totalItems), isArabic ? "إجمالي القطع" : "Total Items", "") +
            buildSummaryCard("highlight", String.format(Locale.US, "%.1f kg", totalWeight/1000), isArabic ? "الوزن الإجمالي" : "Total Weight", "") +
            buildSummaryCard("success", String.valueOf(soldToday), isArabic ? "مباع اليوم" : "Sold Today", "") +
            buildSummaryCard("warning", String.valueOf(soldMonth), isArabic ? "مباع الشهر" : "Sold Month", "") +
            "</div>" +
            "<div class='section-title'>" + (isArabic ? "📊 توزيع القطع حسب العيار" : "📊 Distribution by Karat") + "</div>" +
            "<table><tr><th>" + (isArabic ? "العيار" : "Karat") + "</th><th>" + (isArabic ? "عدد القطع" : "Items") + "</th><th>" + (isArabic ? "النسبة" : "Percentage") + "</th></tr>" +
            "<tr><td>24K</td><td>" + k24 + "</td><td>" + (totalItems > 0 ? String.format("%.1f%%", k24*100.0/totalItems) : "0%") + "</td></tr>" +
            "<tr><td>22K</td><td>" + k22 + "</td><td>" + (totalItems > 0 ? String.format("%.1f%%", k22*100.0/totalItems) : "0%") + "</td></tr>" +
            "<tr><td>21K</td><td>" + k21 + "</td><td>" + (totalItems > 0 ? String.format("%.1f%%", k21*100.0/totalItems) : "0%") + "</td></tr>" +
            "<tr><td>18K</td><td>" + k18 + "</td><td>" + (totalItems > 0 ? String.format("%.1f%%", k18*100.0/totalItems) : "0%") + "</td></tr>" +
            "</table>" +
            "<div class='section-title'>" + (isArabic ? "💰 ملخص المبيعات" : "💰 Sales Summary") + "</div>" +
            "<div class='summary-cards'>" +
            buildSummaryCard("", salesToday, isArabic ? "مبيعات اليوم" : "Today", "") +
            buildSummaryCard("highlight", salesMonth, isArabic ? "مبيعات الشهر" : "Month", "") +
            "</div>";
    }

    private String buildInventoryKaratReport(JSONObject data) {
        return buildInventoryDetailReport(data); // Same data, different presentation
    }

    // ==================== تقارير المبيعات ====================

    private String buildSalesReport(JSONObject data, String period) {
        JSONObject sales = data.optJSONObject("sales");
        int countToday = sales != null ? sales.optInt("count_today", 0) : 0;
        int countMonth = sales != null ? sales.optInt("count_month", 0) : 0;
        int countYear = sales != null ? sales.optInt("count_year", 0) : 0;
        String today = sales != null ? sales.optString("today", "$0") : "$0";
        String month = sales != null ? sales.optString("month", "$0") : "$0";
        String year = sales != null ? sales.optString("year", "$0") : "$0";
        
        return "<div class='summary-cards'>" +
            buildSummaryCard("success", String.valueOf(countToday), isArabic ? "عدد مبيعات اليوم" : "Today Count", "") +
            buildSummaryCard("highlight", today, isArabic ? "قيمة مبيعات اليوم" : "Today Value", "") +
            buildSummaryCard("", String.valueOf(countMonth), isArabic ? "عدد مبيعات الشهر" : "Month Count", "") +
            buildSummaryCard("highlight", month, isArabic ? "قيمة مبيعات الشهر" : "Month Value", "") +
            buildSummaryCard("", String.valueOf(countYear), isArabic ? "عدد مبيعات السنة" : "Year Count", "") +
            buildSummaryCard("highlight", year, isArabic ? "قيمة مبيعات السنة" : "Year Value", "") +
            "</div>";
    }

    private String buildCustomerSalesReport(JSONObject data) {
        return buildSimpleTableReport(data, isArabic ? "مبيعات العملاء" : "Customer Sales", 
            isArabic ? "سيتم تفعيله قريباً مع بيانات العملاء التفصيلية" : "Coming soon with detailed customer data");
    }

    private String buildEmployeeSalesReport(JSONObject data) {
        return buildSimpleTableReport(data, isArabic ? "مبيعات الموظفين" : "Employee Sales",
            isArabic ? "سيتم تفعيله قريباً مع بيانات الموظفين التفصيلية" : "Coming soon with detailed employee data");
    }

    private String buildProfitLossReport(JSONObject data) {
        return buildSimpleTableReport(data, isArabic ? "الأرباح والخسائر" : "Profit & Loss",
            isArabic ? "قيد التطوير - سيحتوي على مقارنة التكلفة بسعر البيع" : "Under development - Will contain cost vs sale price comparison");
    }

    // ==================== تقارير أخرى ====================

    private String buildTransfersDetailReport(JSONObject data) {
        JSONArray transfers = data.optJSONArray("transfers");
        int total = transfers != null ? transfers.length() : 0;
        int pending = 0, inTransit = 0, received = 0;
        
        StringBuilder rows = new StringBuilder();
        if (transfers != null) {
            for (int i = 0; i < transfers.length(); i++) {
                JSONObject t = transfers.optJSONObject(i);
                if (t == null) continue;
                String status = t.optString("status", "");
                if ("pending".equals(status)) pending++;
                else if ("in_transit".equals(status)) inTransit++;
                else if ("received".equals(status)) received++;
                
                String badge = "";
                switch (status) {
                    case "received": badge = "<span class='badge badge-success'>✅</span>"; break;
                    case "in_transit": badge = "<span class='badge badge-info'>🚚</span>"; break;
                    case "pending": badge = "<span class='badge badge-warning'>⏳</span>"; break;
                    case "rejected": badge = "<span class='badge badge-danger'>❌</span>"; break;
                    default: badge = status;
                }
                
                rows.append("<tr><td>").append(t.optString("transfer_number")).append("</td>")
                    .append("<td>").append(t.optString("from_branch_name")).append("</td>")
                    .append("<td>").append(t.optString("to_branch_name")).append("</td>")
                    .append("<td>").append(t.optInt("item_count")).append("</td>")
                    .append("<td>").append(String.format(Locale.US, "%.1fg", t.optDouble("total_weight", 0))).append("</td>")
                    .append("<td>").append(badge).append("</td>")
                    .append("<td>").append(t.optString("created_at", "").substring(0, Math.min(10, t.optString("created_at", "").length()))).append("</td></tr>");
            }
        }
        
        return "<div class='summary-cards'>" +
            buildSummaryCard("", String.valueOf(total), isArabic ? "الإجمالي" : "Total", "") +
            buildSummaryCard("warning", String.valueOf(pending), isArabic ? "معلقة" : "Pending", "") +
            buildSummaryCard("info", String.valueOf(inTransit), isArabic ? "بالطريق" : "In Transit", "") +
            buildSummaryCard("success", String.valueOf(received), isArabic ? "مستلمة" : "Received", "") +
            "</div>" +
            "<table><tr><th>#</th><th>" + (isArabic ? "من" : "From") + "</th><th>" + (isArabic ? "إلى" : "To") + "</th><th>" + (isArabic ? "قطع" : "Items") + "</th><th>" + (isArabic ? "وزن" : "Weight") + "</th><th>" + (isArabic ? "حالة" : "Status") + "</th><th>" + (isArabic ? "تاريخ" : "Date") + "</th></tr>" +
            rows.toString() + "</table>";
    }

    private String buildAuditCompareReport(JSONObject data) {
        return buildSimpleTableReport(data, isArabic ? "مقارنة الجرد" : "Audit Compare",
            isArabic ? "مقارنة بين آخر جلستي جرد" : "Comparison between last two audit sessions");
    }

    private String buildAuditVarianceReport(JSONObject data) {
        return buildSimpleTableReport(data, isArabic ? "فروقات الجرد" : "Audit Variance",
            isArabic ? "القطع التي لم يتم العثور عليها أو الزائدة" : "Missing or extra items found");
    }

    private String buildUsersDetailReport(JSONObject data) {
        JSONArray users = data.optJSONArray("users");
        int total = users != null ? users.length() : 0;
        int active = 0, online = 0;
        
        StringBuilder rows = new StringBuilder();
        if (users != null) {
            for (int i = 0; i < Math.min(users.length(), 50); i++) {
                JSONObject u = users.optJSONObject(i);
                if (u == null) continue;
                String status = u.optString("status", "active");
                if ("active".equals(status)) active++;
                if (u.optBoolean("is_online", false)) online++;
                
                rows.append("<tr><td>").append(u.optString("display_name")).append("</td>")
                    .append("<td>@").append(u.optString("username")).append("</td>")
                    .append("<td>").append(u.optString("role_ar", u.optString("role"))).append("</td>")
                    .append("<td>").append(u.optString("branch_name", "—")).append("</td>")
                    .append("<td>").append("active".equals(status) ? "<span class='badge badge-success'>" + (isArabic ? "نشط" : "Active") + "</span>" : "<span class='badge badge-danger'>" + (isArabic ? "موقوف" : "Suspended") + "</span>").append("</td>")
                    .append("<td>").append(u.optString("last_login", "—").substring(0, Math.min(10, u.optString("last_login", "—").length()))).append("</td></tr>");
            }
        }
        
        return "<div class='summary-cards'>" +
            buildSummaryCard("", String.valueOf(total), isArabic ? "إجمالي المستخدمين" : "Total Users", "") +
            buildSummaryCard("success", String.valueOf(active), isArabic ? "نشط" : "Active", "") +
            buildSummaryCard("info", String.valueOf(online), isArabic ? "متصل الآن" : "Online Now", "") +
            "</div>" +
            "<table><tr><th>" + (isArabic ? "الاسم" : "Name") + "</th><th>" + (isArabic ? "المستخدم" : "Username") + "</th><th>" + (isArabic ? "الصلاحية" : "Role") + "</th><th>" + (isArabic ? "الفرع" : "Branch") + "</th><th>" + (isArabic ? "الحالة" : "Status") + "</th><th>" + (isArabic ? "آخر دخول" : "Last Login") + "</th></tr>" +
            rows.toString() + "</table>";
    }

    private String buildGatesDetailReport(JSONObject data) {
        JSONObject gates = data.optJSONObject("gates");
        int active = gates != null ? gates.optInt("active", 0) : 0;
        int offline = gates != null ? gates.optInt("offline", 0) : 0;
        int alarms = gates != null ? gates.optInt("alarms_today", 0) : 0;
        
        return "<div class='summary-cards'>" +
            buildSummaryCard("success", String.valueOf(active), isArabic ? "بوابات نشطة" : "Active Gates", "") +
            buildSummaryCard("danger", String.valueOf(offline), isArabic ? "بوابات متوقفة" : "Offline Gates", "") +
            buildSummaryCard("warning", String.valueOf(alarms), isArabic ? "إنذارات اليوم" : "Today Alarms", "") +
            "</div>";
    }

    private String buildExecutiveReport(JSONObject data) {
        // ملخص كل الفروع في صفحة واحدة
        return buildInventoryDetailReport(data) + 
            "<div class='section-title'>" + (isArabic ? "🏢 ملخص تنفيذي" : "🏢 Executive Summary") + "</div>" +
            "<p style='text-align:center;color:#666;padding:20px'>" + 
            (isArabic ? "جميع المؤشرات الرئيسية في تقرير واحد للمدير العام" : "All key indicators in one report for the CEO") + "</p>";
    }

    // ==================== Helpers ====================

    private String buildSummaryCard(String type, String value, String label, String extra) {
        return "<div class='summary-card " + type + "'><div class='value'>" + value + "</div><div class='label'>" + label + "</div>" + extra + "</div>";
    }

    private String buildSimpleTableReport(JSONObject data, String title, String message) {
        return "<div style='text-align:center;padding:60px 20px'>" +
            "<div style='font-size:48px;margin-bottom:16px'>📊</div>" +
            "<h2 style='color:#1A237E;margin-bottom:8px'>" + title + "</h2>" +
            "<p style='color:#666'>" + message + "</p></div>";
    }

    // ==================== Actions ====================

    private void printReport() {
        if (getContext() == null) return;
        PrintManager pm = (PrintManager) requireContext().getSystemService(android.content.Context.PRINT_SERVICE);
        PrintDocumentAdapter adapter = webReport.createPrintDocumentAdapter("MIRA_Report_" + currentReportType);
        pm.print("MIRA Report - " + currentReportType, adapter, new PrintAttributes.Builder().build());
        Toast.makeText(getContext(), isArabic ? "🖨️ جاري الطباعة..." : "🖨️ Printing...", Toast.LENGTH_SHORT).show();
    }

    private void shareReport() {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/html");
        share.putExtra(Intent.EXTRA_SUBJECT, "MIRA Report - " + currentReportType);
        share.putExtra(Intent.EXTRA_TEXT, currentReportHtml);
        startActivity(Intent.createChooser(share, isArabic ? "مشاركة التقرير" : "Share Report"));
    }

    private void exportPDF() {
        Toast.makeText(getContext(), isArabic ? "📄 جاري تصدير PDF..." : "📄 Exporting PDF...", Toast.LENGTH_SHORT).show();
        // TODO: Implement PDF generation
    }

    private void exportExcel() {
        Toast.makeText(getContext(), isArabic ? "📊 جاري تصدير Excel..." : "📊 Exporting Excel...", Toast.LENGTH_SHORT).show();
        // TODO: Implement Excel generation
    }

    private String getErrorHtml() {
        String dir = isArabic ? "rtl" : "ltr";
        return "<!DOCTYPE html><html dir='" + dir + "'><head><meta charset='UTF-8'></head>" +
            "<body style='font-family:Cairo,sans-serif;text-align:center;padding:60px 20px;color:#EF4444'>" +
            "<div style='font-size:48px;margin-bottom:16px'>⚠️</div>" +
            "<h2>" + (isArabic ? "تعذر تحميل التقرير" : "Failed to load report") + "</h2>" +
            "<p style='color:#666'>" + (isArabic ? "تأكد من اتصال الانترنت وحاول مرة أخرى" : "Check your connection and try again") + "</p>" +
            "</body></html>";
    }
                             }
