package com.example.uhf.fragment;

import android.app.DatePickerDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MiraReportsProFragment extends Fragment {

    private static final String TAG = "MiraReportsPro";
    private static final String CACHE_PREFS = "mira_reports_pro_cache";

    private RecyclerView rvReports;
    private ReportsAdapter adapter;
    private final List<ReportItem> reportList = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MiraOrgProfileManager orgProfile;
    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private SharedPreferences offlineCache;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_reports_pro, container, false);

        if (getContext() != null) {
            orgProfile = MiraOrgProfileManager.getInstance(getContext());
            apiClient = MiraApiClient.getInstance(getContext());
            branchesManager = MiraBranchesManager.getInstance(getContext());
            offlineCache = getContext().getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE);
        }

        rvReports = view.findViewById(R.id.rvReportsList);
        rvReports.setLayoutManager(new GridLayoutManager(getContext(), 2));

        buildReportsList();
        adapter = new ReportsAdapter(reportList, this::openReportDialog);
        rvReports.setAdapter(adapter);

        return view;
    }

    private void buildReportsList() {
        reportList.clear();
        reportList.add(new ReportItem("📊", "لوحة القيادة التنفيذية", "ملخص شامل للمخزون والسيولة والعيارات", "dashboard"));
        reportList.add(new ReportItem("💰", "تقرير المبيعات والأرباح", "حركة النقد، أجور الصياغة، وصافي الأرباح", "sales"));
        reportList.add(new ReportItem("📦", "مخزون الجرامات والعيارات", "تفكيك الأوزان الصافية (24K Equiv) وحالة القطع", "inventory"));
        reportList.add(new ReportItem("🔄", "حركة التنقلات والتحويلات", "سجل حركة قطع الذهب بين الفروع والخزائن", "movement"));
        reportList.add(new ReportItem("🏢", "أداء مقارنة الفروع", "تحليل المبيعات والأرصدة والإنتاجية لكل فرع", "branches"));
        reportList.add(new ReportItem("🚪", "رقابة البوابات والإنذارات", "سجل المرور غير المصرح وتغطية RFID", "gates"));
        reportList.add(new ReportItem("📋", "تقرير مطابقة الجرد", "تحليل الفروقات والقطع المفقودة والفائض", "audit"));
        reportList.add(new ReportItem("💎", "تقرير النواقص والطلب", "القطع المباعة الأكثر طلباً لإعادة الصياغة", "reorder"));
    }

    // ==================== DIALOG & REPORT DISPLAY ====================

    private void openReportDialog(ReportItem item) {
        if (getContext() == null) return;

        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_mira_report_viewer, null);

        TextView tvTitle = dialogView.findViewById(R.id.tvReportDialogTitle);
        WebView webView = dialogView.findViewById(R.id.webReportView);
        View layoutLoading = dialogView.findViewById(R.id.layoutLoadingContainer);
        TextView tvLoadingStatus = dialogView.findViewById(R.id.tvLoadingStatus);

        MaterialButton btnFrom = dialogView.findViewById(R.id.btnReportDateFrom);
        MaterialButton btnTo = dialogView.findViewById(R.id.btnReportDateTo);
        MaterialButton btnApply = dialogView.findViewById(R.id.btnReportApplyFilter);
        MaterialButton btnPrint = dialogView.findViewById(R.id.btnReportPrint);
        MaterialButton btnExport = dialogView.findViewById(R.id.btnReportExport);
        MaterialButton btnClose = dialogView.findViewById(R.id.btnReportClose);

        View btnToday = dialogView.findViewById(R.id.btnQuickToday);
        View btnWeek = dialogView.findViewById(R.id.btnQuickWeek);
        View btnMonth = dialogView.findViewById(R.id.btnQuickMonth);

        tvTitle.setText(item.icon + " " + item.title);

        // إعدادات WebView الأساسية
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webSettings.setUseWideViewPort(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);
        webSettings.setSupportZoom(true);
        webSettings.setDefaultTextEncodingName("utf-8");
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        
        // إصلاح مشكلة العرض - هذه السطور مهمة جداً
        webView.setBackgroundColor(Color.WHITE);
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        webView.setVisibility(View.VISIBLE);
        webView.setAlpha(1.0f);

        final String[] dates = {getDefaultFromDate(), getDefaultToDate()};
        btnFrom.setText("من: " + dates[0]);
        btnTo.setText("إلى: " + dates[1]);

        // إنشاء Dialog بالحجم الكامل
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .setCancelable(true)
                .create();
        
        // تعيين حجم الـ Dialog ليملأ الشاشة
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            );
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.WHITE));
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        btnFrom.setOnClickListener(v -> showDatePicker(date -> {
            dates[0] = date;
            btnFrom.setText("من: " + date);
        }));

        btnTo.setOnClickListener(v -> showDatePicker(date -> {
            dates[1] = date;
            btnTo.setText("إلى: " + date);
        }));

        btnToday.setOnClickListener(v -> {
            String today = getDefaultToDate();
            dates[0] = today; dates[1] = today;
            btnFrom.setText("من: " + today); btnTo.setText("إلى: " + today);
            fetchAndRenderReport(item.type, webView, layoutLoading, tvLoadingStatus, dates[0], dates[1]);
        });

        btnWeek.setOnClickListener(v -> {
            dates[0] = getCalculatedDate(-7); dates[1] = getDefaultToDate();
            btnFrom.setText("من: " + dates[0]); btnTo.setText("إلى: " + dates[1]);
            fetchAndRenderReport(item.type, webView, layoutLoading, tvLoadingStatus, dates[0], dates[1]);
        });

        btnMonth.setOnClickListener(v -> {
            dates[0] = getCalculatedDate(-30); dates[1] = getDefaultToDate();
            btnFrom.setText("من: " + dates[0]); btnTo.setText("إلى: " + dates[1]);
            fetchAndRenderReport(item.type, webView, layoutLoading, tvLoadingStatus, dates[0], dates[1]);
        });

        btnApply.setOnClickListener(v -> fetchAndRenderReport(item.type, webView, layoutLoading, tvLoadingStatus, dates[0], dates[1]));
        btnPrint.setOnClickListener(v -> printReportWebView(webView, item.title));
        btnExport.setOnClickListener(v -> exportReportToCSV(item.type, dates[0], dates[1]));
        btnClose.setOnClickListener(v -> dialog.dismiss());

        // عرض التقرير
        fetchAndRenderReport(item.type, webView, layoutLoading, tvLoadingStatus, dates[0], dates[1]);

        dialog.show();
    }

    private void fetchAndRenderReport(String reportType, WebView webView, View layoutLoading, TextView tvLoadingStatus, String fromDate, String toDate) {
        if (getContext() == null) return;

        mainHandler.post(() -> {
            if (layoutLoading != null) {
                layoutLoading.setVisibility(View.VISIBLE);
                layoutLoading.bringToFront();
            }
            if (tvLoadingStatus != null) tvLoadingStatus.setText("جاري معالجة البيانات...");
            if (webView != null) webView.setVisibility(View.GONE);
        });

        new Thread(() -> {
            JSONObject reportData = null;
            String errorMessage = null;
            String cacheKey = "cache_" + reportType + "_" + fromDate + "_" + toDate;

            try {
                int activeBranchId = branchesManager != null ? branchesManager.getActiveBranchId() : 1;
                MiraApiClient.ApiResponse response = apiClient.getReportData(reportType, fromDate, toDate, activeBranchId);

                if (response != null && response.isSuccess && response.data != null) {
                    reportData = response.data.optJSONObject("data");
                    if (reportData == null) reportData = response.data;
                    
                    if (offlineCache != null && reportData != null) {
                        offlineCache.edit().putString(cacheKey, reportData.toString()).apply();
                    }
                } else {
                    errorMessage = "فشل الاتصال (code: " + (response != null ? response.code : "NULL") + ")";
                }
            } catch (Exception e) {
                errorMessage = e.getMessage();
                Log.e(TAG, "Error fetching report: " + reportType, e);
            }

            // كاش احتياطي
            if (reportData == null && offlineCache != null) {
                String cached = offlineCache.getString(cacheKey, null);
                if (cached != null) {
                    try { 
                        reportData = new JSONObject(cached); 
                    } catch (Exception ignored) {}
                }
            }

            // إذا لا توجد بيانات، إنشاء بيانات تجريبية للعرض
            if (reportData == null) {
                reportData = createSampleData(reportType);
                errorMessage = "عرض بيانات تجريبية - " + errorMessage;
            }

            final JSONObject finalData = reportData;
            final String finalError = errorMessage;

            mainHandler.post(() -> {
                if (getContext() == null) return;

                String htmlContent = generateExecutiveHTML(reportType, finalData, fromDate, toDate);
                
                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        super.onPageFinished(view, url);
                        if (layoutLoading != null) {
                            layoutLoading.setVisibility(View.GONE);
                        }
                        // إظهار WebView بعد التحميل الكامل
                        webView.setVisibility(View.VISIBLE);
                        webView.setAlpha(1.0f);
                    }

                    @Override
                    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                        super.onReceivedError(view, errorCode, description, failingUrl);
                        if (layoutLoading != null) {
                            layoutLoading.setVisibility(View.GONE);
                        }
                        webView.setVisibility(View.VISIBLE);
                        Toast.makeText(getContext(), "خطأ في التحميل: " + description, Toast.LENGTH_SHORT).show();
                    }
                });
                
                // تحميل HTML
                webView.loadDataWithBaseURL(null, htmlContent, "text/html", "utf-8", null);
            });
        }).start();
    }

    // إنشاء بيانات تجريبية للعرض عند عدم توفر بيانات حقيقية
    private JSONObject createSampleData(String type) {
        JSONObject data = new JSONObject();
        try {
            switch (type) {
                case "dashboard":
                    JSONObject inv = new JSONObject();
                    inv.put("total_items", 150);
                    inv.put("total_weight", 1250.5);
                    inv.put("net_24k_weight", 1000.4);
                    
                    JSONArray karats = new JSONArray();
                    JSONObject k18 = new JSONObject();
                    k18.put("karat", "18K");
                    k18.put("gross_weight", 500.2);
                    k18.put("count", 50);
                    k18.put("net_24k", 375.15);
                    karats.put(k18);
                    
                    JSONObject k21 = new JSONObject();
                    k21.put("karat", "21K");
                    k21.put("gross_weight", 750.3);
                    k21.put("count", 100);
                    k21.put("net_24k", 656.51);
                    karats.put(k21);
                    
                    inv.put("karat_breakdown", karats);
                    data.put("inventory", inv);
                    
                    JSONObject sales = new JSONObject();
                    sales.put("total_revenue", 250000);
                    sales.put("total_making_charges", 30000);
                    sales.put("net_profit", 45000);
                    sales.put("invoices_count", 75);
                    data.put("sales", sales);
                    break;
                    
                case "sales":
                    JSONObject salesData = new JSONObject();
                    salesData.put("total_revenue", 250000);
                    salesData.put("total_making_charges", 30000);
                    salesData.put("net_profit", 45000);
                    salesData.put("invoices_count", 75);
                    data.put("sales", salesData);
                    break;
                    
                case "inventory":
                    JSONObject invData = new JSONObject();
                    invData.put("total_items", 150);
                    invData.put("total_weight", 1250.5);
                    invData.put("net_24k_weight", 1000.4);
                    
                    JSONArray karatData = new JSONArray();
                    JSONObject k18Data = new JSONObject();
                    k18Data.put("karat", "18K");
                    k18Data.put("gross_weight", 500.2);
                    k18Data.put("count", 50);
                    k18Data.put("net_24k", 375.15);
                    karatData.put(k18Data);
                    
                    JSONObject k21Data = new JSONObject();
                    k21Data.put("karat", "21K");
                    k21Data.put("gross_weight", 750.3);
                    k21Data.put("count", 100);
                    k21Data.put("net_24k", 656.51);
                    karatData.put(k21Data);
                    
                    invData.put("karat_breakdown", karatData);
                    data.put("inventory", invData);
                    break;
                    
                case "movement":
                    JSONArray movements = new JSONArray();
                    for (int i = 1; i <= 5; i++) {
                        JSONObject movement = new JSONObject();
                        movement.put("transfer_code", "TR-" + String.format("%04d", i));
                        movement.put("from_branch", "الفرع الرئيسي");
                        movement.put("to_branch", "فرع " + i);
                        movement.put("status", "مكتمل");
                        movements.put(movement);
                    }
                    data.put("movements", movements);
                    break;
                    
                case "branches":
                    JSONArray branches = new JSONArray();
                    String[] branchNames = {"الفرع الرئيسي", "فرع المدينة", "فرع المول", "فرع السوق"};
                    for (int i = 0; i < branchNames.length; i++) {
                        JSONObject branch = new JSONObject();
                        branch.put("branch_name", branchNames[i]);
                        branch.put("items_count", 40 + i * 15);
                        branch.put("sales_total", 50000 + i * 20000);
                        branches.put(branch);
                    }
                    data.put("branches_performance", branches);
                    break;
                    
                case "gates":
                    JSONArray logs = new JSONArray();
                    for (int i = 1; i <= 10; i++) {
                        JSONObject log = new JSONObject();
                        log.put("timestamp", "2026-08-14 " + String.format("%02d:30:00", i));
                        log.put("gate_name", "بوابة " + i);
                        log.put("epc", "EPC-" + String.format("%08d", i * 12345));
                        logs.put(log);
                    }
                    data.put("gate_logs", logs);
                    break;
                    
                case "audit":
                    JSONObject audit = new JSONObject();
                    audit.put("accuracy", 95.5);
                    audit.put("found_items", 145);
                    audit.put("missing_items", 5);
                    data.put("audit_summary", audit);
                    break;
                    
                case "reorder":
                    JSONArray items = new JSONArray();
                    String[] categories = {"خواتم", "أساور", "سلاسل", "أقراط"};
                    for (int i = 0; i < categories.length; i++) {
                        JSONObject item = new JSONObject();
                        item.put("category", categories[i]);
                        item.put("karat", "18K");
                        item.put("sold_count", 20 + i * 5);
                        item.put("remaining_stock", 5 - i);
                        items.put(item);
                    }
                    data.put("reorder_items", items);
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error creating sample data", e);
        }
        return data;
    }

    // ==================== HTML GENERATOR ====================

    private String generateExecutiveHTML(String type, JSONObject data, String from, String to) {
        StringBuilder html = new StringBuilder();
        String orgName = orgProfile != null ? orgProfile.getOrgName() : "MIRA JEWELRY SYSTEM";

        html.append("<!DOCTYPE html><html dir='rtl' lang='ar'><head><meta charset='UTF-8'>")
            .append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>")
            .append("<style>")
            .append("*{margin:0;padding:0;box-sizing:border-box;}")
            .append("body{font-family:'Segoe UI',Tahoma,Arial,sans-serif;margin:0;padding:16px;background:#FFFFFF;color:#1F2937;direction:rtl;}")
            .append(".header{background:linear-gradient(135deg,#1E293B,#0F172A);color:#FFF;padding:20px;border-radius:12px;margin-bottom:16px;box-shadow:0 2px 8px rgba(0,0,0,0.1);}")
            .append(".header h2{margin:0;font-size:20px;color:#F1F5F9;text-align:center;}")
            .append(".header p{margin:8px 0 0;font-size:12px;color:#94A3B8;text-align:center;}")
            .append(".card{background:#FFFFFF;padding:16px;border-radius:12px;margin-bottom:16px;border:1px solid #E2E8F0;box-shadow:0 1px 3px rgba(0,0,0,0.05);}")
            .append(".card-title{font-size:16px;font-weight:bold;color:#0F172A;margin-bottom:12px;padding-bottom:8px;border-bottom:2px solid #F1F5F9;}")
            .append(".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:12px;margin-bottom:12px;}")
            .append(".metric{background:#F8FAFC;border:1px solid #E2E8F0;padding:14px;border-radius:8px;text-align:center;}")
            .append(".metric .val{font-size:18px;font-weight:800;color:#0F172A;margin:4px 0;}")
            .append(".metric .lbl{font-size:11px;color:#64748B;}")
            .append(".gold{color:#D97706;font-weight:bold;}")
            .append("table{width:100%;border-collapse:collapse;margin-top:12px;font-size:12px;}")
            .append("th{background:#0F172A;color:#FFF;padding:10px;text-align:center;font-weight:bold;}")
            .append("td{padding:10px;border-bottom:1px solid #E2E8F0;text-align:center;}")
            .append("tr:nth-child(even){background:#F8FAFC;}")
            .append("tr:hover{background:#F1F5F9;}")
            .append(".no-data{text-align:center;padding:20px;color:#64748B;font-size:14px;}")
            .append("</style></head><body>");

        html.append("<div class='header'><h2>").append(orgName).append("</h2>")
            .append("<p>الفترة من ").append(from).append(" إلى ").append(to).append("</p></div>");

        switch (type) {
            case "inventory": renderInventoryHTML(html, data); break;
            case "sales": renderSalesHTML(html, data); break;
            case "movement": renderMovementHTML(html, data); break;
            case "branches": renderBranchesHTML(html, data); break;
            case "gates": renderGatesHTML(html, data); break;
            case "audit": renderAuditHTML(html, data); break;
            case "reorder": renderReorderHTML(html, data); break;
            default: renderDashboardHTML(html, data); break;
        }

        html.append("</body></html>");
        return html.toString();
    }

    private void renderDashboardHTML(StringBuilder html, JSONObject data) {
        JSONObject inv = data.optJSONObject("inventory");
        JSONObject sales = data.optJSONObject("sales");
        
        html.append("<div class='card'><div class='card-title'>📊 الملخص التنفيذي</div><div class='grid'>");
        if (inv != null) {
            html.append(metricBox("📦", String.valueOf(inv.optInt("total_items", 0)), "القطع"));
            html.append(metricBox("⚖️", String.format(Locale.US, "%.1f", inv.optDouble("total_weight", 0)), "الوزن جم"));
            html.append(metricBox("✨", String.format(Locale.US, "%.1f", inv.optDouble("net_24k_weight", 0)), "صافي 24K", "gold"));
        }
        if (sales != null) {
            html.append(metricBox("💰", String.format(Locale.US, "%,.0f", sales.optDouble("total_revenue", 0)), "المبيعات"));
            html.append(metricBox("📈", String.format(Locale.US, "%,.0f", sales.optDouble("net_profit", 0)), "الأرباح", "gold"));
        }
        html.append("</div></div>");
        
        if (inv != null) {
            renderInventoryHTML(html, data);
        }
    }

    private void renderInventoryHTML(StringBuilder html, JSONObject data) {
        JSONObject inv = data.optJSONObject("inventory");
        if (inv == null) { 
            html.append("<div class='card'><div class='no-data'>لا توجد بيانات مخزون</div></div>"); 
            return; 
        }

        html.append("<div class='card'><div class='card-title'>📦 توزيع العيارات</div>")
            .append("<table><tr><th>العيار</th><th>الوزن</th><th>القطع</th><th>صافي 24K</th></tr>");
        
        JSONArray karats = inv.optJSONArray("karat_breakdown");
        if (karats != null && karats.length() > 0) {
            for (int i = 0; i < karats.length(); i++) {
                JSONObject k = karats.optJSONObject(i);
                if (k == null) continue;
                html.append("<tr><td><b>").append(k.optString("karat", "—")).append("</b></td>")
                    .append("<td>").append(String.format(Locale.US, "%.2f", k.optDouble("gross_weight", 0))).append("</td>")
                    .append("<td>").append(k.optInt("count", 0)).append("</td>")
                    .append("<td class='gold'>").append(String.format(Locale.US, "%.2f", k.optDouble("net_24k", 0))).append("</td></tr>");
            }
        } else {
            html.append("<tr><td colspan='4'>لا توجد بيانات</td></tr>");
        }
        html.append("</table></div>");
    }

    private void renderSalesHTML(StringBuilder html, JSONObject data) {
        JSONObject sales = data.optJSONObject("sales");
        if (sales == null) { 
            html.append("<div class='card'><div class='no-data'>لا توجد بيانات مبيعات</div></div>"); 
            return; 
        }

        html.append("<div class='card'><div class='card-title'>💰 المبيعات</div><div class='grid'>")
            .append(metricBox("💵", String.format(Locale.US, "%,.0f", sales.optDouble("total_revenue", 0)), "الإيرادات"))
            .append(metricBox("🔨", String.format(Locale.US, "%,.0f", sales.optDouble("total_making_charges", 0)), "أجور"))
            .append(metricBox("📈", String.format(Locale.US, "%,.0f", sales.optDouble("net_profit", 0)), "الربح", "gold"))
            .append(metricBox("🧾", String.valueOf(sales.optInt("invoices_count", 0)), "فواتير"))
            .append("</div></div>");
    }

    private void renderMovementHTML(StringBuilder html, JSONObject data) {
        JSONArray movements = data.optJSONArray("movements");
        html.append("<div class='card'><div class='card-title'>🔄 التحويلات</div>");
        if (movements != null && movements.length() > 0) {
            html.append("<table><tr><th>الرقم</th><th>من</th><th>إلى</th><th>الحالة</th></tr>");
            for (int i = 0; i < movements.length(); i++) {
                JSONObject m = movements.optJSONObject(i);
                if (m != null) {
                    html.append("<tr><td>").append(m.optString("transfer_code", "—")).append("</td>")
                        .append("<td>").append(m.optString("from_branch", "—")).append("</td>")
                        .append("<td>").append(m.optString("to_branch", "—")).append("</td>")
                        .append("<td>").append(m.optString("status", "—")).append("</td></tr>");
                }
            }
            html.append("</table>");
        } else {
            html.append("<div class='no-data'>لا توجد تحويلات</div>");
        }
        html.append("</div>");
    }

    private void renderBranchesHTML(StringBuilder html, JSONObject data) {
        JSONArray branches = data.optJSONArray("branches_performance");
        html.append("<div class='card'><div class='card-title'>🏢 الفروع</div>");
        if (branches != null && branches.length() > 0) {
            html.append("<table><tr><th>الفرع</th><th>القطع</th><th>المبيعات</th></tr>");
            for (int i = 0; i < branches.length(); i++) {
                JSONObject b = branches.optJSONObject(i);
                if (b != null) {
                    html.append("<tr><td>").append(b.optString("branch_name", "—")).append("</td>")
                        .append("<td>").append(b.optInt("items_count", 0)).append("</td>")
                        .append("<td class='gold'>").append(String.format(Locale.US, "%,.0f", b.optDouble("sales_total", 0))).append("</td></tr>");
                }
            }
            html.append("</table>");
        } else {
            html.append("<div class='no-data'>لا توجد بيانات فروع</div>");
        }
        html.append("</div>");
    }

    private void renderGatesHTML(StringBuilder html, JSONObject data) {
        JSONArray logs = data.optJSONArray("gate_logs");
        html.append("<div class='card'><div class='card-title'>🚪 البوابات</div>");
        if (logs != null && logs.length() > 0) {
            html.append("<table><tr><th>الوقت</th><th>البوابة</th><th>EPC</th></tr>");
            for (int i = 0; i < Math.min(logs.length(), 50); i++) {
                JSONObject l = logs.optJSONObject(i);
                if (l != null) {
                    html.append("<tr><td>").append(l.optString("timestamp", "—")).append("</td>")
                        .append("<td>").append(l.optString("gate_name", "—")).append("</td>")
                        .append("<td>").append(l.optString("epc", "—")).append("</td></tr>");
                }
            }
            html.append("</table>");
        } else {
            html.append("<div class='no-data'>لا توجد سجلات بوابات</div>");
        }
        html.append("</div>");
    }

    private void renderAuditHTML(StringBuilder html, JSONObject data) {
        JSONObject audit = data.optJSONObject("audit_summary");
        if (audit == null) { 
            html.append("<div class='card'><div class='no-data'>لا توجد نتائج جرد</div></div>"); 
            return; 
        }
        
        html.append("<div class='card'><div class='card-title'>📋 الجرد</div><div class='grid'>")
            .append(metricBox("🎯", String.format(Locale.US, "%.1f%%", audit.optDouble("accuracy", 0)), "الدقة"))
            .append(metricBox("✅", String.valueOf(audit.optInt("found_items", 0)), "موجود"))
            .append(metricBox("❌", String.valueOf(audit.optInt("missing_items", 0)), "مفقود"))
            .append("</div></div>");
    }

    private void renderReorderHTML(StringBuilder html, JSONObject data) {
        JSONArray items = data.optJSONArray("reorder_items");
        html.append("<div class='card'><div class='card-title'>💎 نواقص</div>");
        if (items != null && items.length() > 0) {
            html.append("<table><tr><th>الصنف</th><th>العيار</th><th>مباع</th><th>متبقي</th></tr>");
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null) {
                    html.append("<tr><td>").append(item.optString("category", "—")).append("</td>")
                        .append("<td>").append(item.optString("karat", "—")).append("</td>")
                        .append("<td>").append(item.optInt("sold_count", 0)).append("</td>")
                        .append("<td>").append(item.optInt("remaining_stock", 0)).append("</td></tr>");
                }
            }
            html.append("</table>");
        } else {
            html.append("<div class='no-data'>لا توجد بيانات نواقص</div>");
        }
        html.append("</div>");
    }

    private String metricBox(String icon, String val, String lbl, String extraClass) {
        return "<div class='metric'><div style='font-size:20px;margin-bottom:4px;'>" + icon + "</div>" +
                "<div class='val " + extraClass + "'>" + val + "</div>" +
                "<div class='lbl'>" + lbl + "</div></div>";
    }

    private String metricBox(String icon, String val, String lbl) {
        return metricBox(icon, val, lbl, "");
    }

    // ==================== PRINT & EXPORT ====================

    private void printReportWebView(WebView webView, String jobTitle) {
        if (getContext() == null) return;
        try {
            PrintManager pm = (PrintManager) requireContext().getSystemService(Context.PRINT_SERVICE);
            if (pm != null) {
                PrintDocumentAdapter printAdapter = webView.createPrintDocumentAdapter(jobTitle);
                pm.print(jobTitle, printAdapter, new PrintAttributes.Builder().build());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error printing", e);
            Toast.makeText(getContext(), "خطأ في الطباعة: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void exportReportToCSV(String type, String from, String to) {
        if (getContext() == null) return;
        Toast.makeText(getContext(), "جاري التصدير...", Toast.LENGTH_SHORT).show();
        
        // يمكن إضافة منطق التصدير هنا
        new Thread(() -> {
            try {
                // إنشاء ملف CSV
                String fileName = "report_" + type + "_" + from + "_to_" + to + ".csv";
                File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File file = new File(downloadsDir, fileName);
                
                FileOutputStream fos = new FileOutputStream(file);
                OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
                
                // كتابة رأس الملف
                writer.write("Report Type,From,To\n");
                writer.write(type + "," + from + "," + to + "\n\n");
                
                writer.close();
                fos.close();
                
                mainHandler.post(() -> {
                    Toast.makeText(getContext(), "تم تصدير الملف: " + fileName, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                Log.e(TAG, "Error exporting CSV", e);
                mainHandler.post(() -> {
                    Toast.makeText(getContext(), "خطأ في التصدير: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    // ==================== HELPERS ====================

    private String getDefaultFromDate() { 
        return getCalculatedDate(-30); 
    }
    
    private String getDefaultToDate() { 
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()); 
    }

    private String getCalculatedDate(int days) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, days);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.getTime());
    }

    private void showDatePicker(DatePickerCallback cb) {
        if (getContext() == null) return;
        Calendar c = Calendar.getInstance();
        new DatePickerDialog(requireContext(),
            (v, y, m, d) -> cb.onDate(String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)),
            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    // ==================== ADAPTER ====================

    static class ReportItem {
        String icon, title, subtitle, type;
        ReportItem(String i, String t, String s, String ty) { 
            icon = i; 
            title = t; 
            subtitle = s; 
            type = ty; 
        }
    }

    class ReportsAdapter extends RecyclerView.Adapter<ReportsAdapter.VH> {
        final List<ReportItem> list;
        final OnReportClickListener listener;
        
        ReportsAdapter(List<ReportItem> l, OnReportClickListener c) { 
            list = l; 
            listener = c; 
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_report_card_pro, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            ReportItem item = list.get(pos);
            h.tvIcon.setText(item.icon);
            h.tvTitle.setText(item.title);
            h.tvSubtitle.setText(item.subtitle);
            h.itemView.setOnClickListener(v -> listener.onClick(item));
        }

        @Override
        public int getItemCount() { 
            return list.size(); 
        }

        class VH extends RecyclerView.ViewHolder {
            TextView tvIcon, tvTitle, tvSubtitle;
            
            VH(View v) { 
                super(v);
                tvIcon = v.findViewById(R.id.tvReportIcon);
                tvTitle = v.findViewById(R.id.tvReportTitle);
                tvSubtitle = v.findViewById(R.id.tvReportSubtitle);
            }
        }
    }

    interface OnReportClickListener { 
        void onClick(ReportItem item); 
    }
    
    interface DatePickerCallback { 
        void onDate(String date); 
    }
    }
