package com.example.uhf.fragment;

import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
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
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.PermissionManager;
import com.google.android.material.button.MaterialButton;

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
    private PermissionManager permissionManager;
    private SharedPreferences offlineCache;
    private boolean isArabic;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_reports_pro, container, false);

        if (getContext() != null) {
            orgProfile = MiraOrgProfileManager.getInstance(getContext());
            apiClient = MiraApiClient.getInstance(getContext());
            branchesManager = MiraBranchesManager.getInstance(getContext());
            permissionManager = PermissionManager.getInstance(getContext());
            offlineCache = getContext().getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE);
        }
        
        isArabic = Locale.getDefault().getLanguage().equals("ar");

        rvReports = view.findViewById(R.id.rvReportsList);
        rvReports.setLayoutManager(new GridLayoutManager(getContext(), 2));

        buildReportsList();
        adapter = new ReportsAdapter(reportList, this::openReportDialog);
        rvReports.setAdapter(adapter);

        return view;
    }

    private void buildReportsList() {
        reportList.clear();
        
        if (isArabic) {
            reportList.add(new ReportItem("📊", "لوحة القيادة التنفيذية", "ملخص شامل للمخزون والسيولة والعيارات", "dashboard"));
            reportList.add(new ReportItem("💰", "تقرير المبيعات والأرباح", "حركة النقد، أجور الصياغة، وصافي الأرباح", "sales"));
            reportList.add(new ReportItem("📦", "مخزون الجرامات والعيارات", "تفكيك الأوزان الصافية (24K Equiv) وحالة القطع", "inventory"));
            reportList.add(new ReportItem("🔄", "حركة التنقلات والتحويلات", "سجل حركة قطع الذهب بين الفروع والخزائن", "movement"));
            reportList.add(new ReportItem("🏢", "أداء مقارنة الفروع", "تحليل المبيعات والأرصدة والإنتاجية لكل فرع", "branches"));
            reportList.add(new ReportItem("🚪", "رقابة البوابات والإنذارات", "سجل المرور غير المصرح وتغطية RFID", "gates"));
            reportList.add(new ReportItem("📋", "تقرير مطابقة الجرد", "تحليل الفروقات والقطع المفقودة والفائض", "audit"));
            reportList.add(new ReportItem("💎", "تقرير النواقص والطلب", "القطع المباعة الأكثر طلباً لإعادة الصياغة", "reorder"));
        } else {
            reportList.add(new ReportItem("📊", "Executive Dashboard", "Comprehensive inventory, liquidity & karat summary", "dashboard"));
            reportList.add(new ReportItem("💰", "Sales & Profit Report", "Cash flow, making charges & net profit", "sales"));
            reportList.add(new ReportItem("📦", "Karat & Weight Inventory", "Net weight breakdown (24K Equiv) & item status", "inventory"));
            reportList.add(new ReportItem("🔄", "Movement & Transfers", "Gold item movement log between branches & vaults", "movement"));
            reportList.add(new ReportItem("🏢", "Branch Performance", "Sales, balances & productivity analysis per branch", "branches"));
            reportList.add(new ReportItem("🚪", "Gate & Alarm Control", "Unauthorized passage log & RFID coverage", "gates"));
            reportList.add(new ReportItem("📋", "Inventory Audit Report", "Variance analysis, missing & surplus items", "audit"));
            reportList.add(new ReportItem("💎", "Reorder & Demand", "Best-selling items for re-crafting", "reorder"));
        }
    }

    private void openReportDialog(ReportItem item) {
        if (getContext() == null) return;
        
        if (!permissionManager.canAccessFragment("Reports")) {
            Toast.makeText(getContext(), 
                isArabic ? "⛔ غير مصرح لك بالوصول للتقارير" : "⛔ Access Denied",
                Toast.LENGTH_SHORT).show();
            return;
        }

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

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);
        webSettings.setCacheMode(WebSettings.LOAD_NO_CACHE);

        final String[] dates = {getDefaultFromDate(), getDefaultToDate()};
        btnFrom.setText((isArabic ? "من: " : "From: ") + dates[0]);
        btnTo.setText((isArabic ? "إلى: " : "To: ") + dates[1]);

        AlertDialog dialog = new AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen)
                .setView(dialogView)
                .create();

        btnFrom.setOnClickListener(v -> showDatePicker(date -> {
            dates[0] = date;
            btnFrom.setText((isArabic ? "من: " : "From: ") + date);
        }));

        btnTo.setOnClickListener(v -> showDatePicker(date -> {
            dates[1] = date;
            btnTo.setText((isArabic ? "إلى: " : "To: ") + date);
        }));

        btnToday.setOnClickListener(v -> {
            String today = getDefaultToDate();
            dates[0] = today; dates[1] = today;
            btnFrom.setText((isArabic ? "من: " : "From: ") + today); 
            btnTo.setText((isArabic ? "إلى: " : "To: ") + today);
            fetchAndRenderReport(item.type, webView, layoutLoading, tvLoadingStatus, dates[0], dates[1]);
        });

        btnWeek.setOnClickListener(v -> {
            dates[0] = getCalculatedDate(-7); dates[1] = getDefaultToDate();
            btnFrom.setText((isArabic ? "من: " : "From: ") + dates[0]); 
            btnTo.setText((isArabic ? "إلى: " : "To: ") + dates[1]);
            fetchAndRenderReport(item.type, webView, layoutLoading, tvLoadingStatus, dates[0], dates[1]);
        });

        btnMonth.setOnClickListener(v -> {
            dates[0] = getCalculatedDate(-30); dates[1] = getDefaultToDate();
            btnFrom.setText((isArabic ? "من: " : "From: ") + dates[0]); 
            btnTo.setText((isArabic ? "إلى: " : "To: ") + dates[1]);
            fetchAndRenderReport(item.type, webView, layoutLoading, tvLoadingStatus, dates[0], dates[1]);
        });

        btnApply.setOnClickListener(v -> fetchAndRenderReport(item.type, webView, layoutLoading, tvLoadingStatus, dates[0], dates[1]));
        btnPrint.setOnClickListener(v -> printReportWebView(webView, item.title));
        btnExport.setOnClickListener(v -> exportReportToCSV(item.type, dates[0], dates[1]));
        
        btnClose.setOnClickListener(v -> {
            try {
                webView.stopLoading();
                webView.destroy();
            } catch (Exception ignored) {}
            dialog.dismiss();
        });

        dialog.show();

        webView.post(() -> fetchAndRenderReport(item.type, webView, layoutLoading, tvLoadingStatus, dates[0], dates[1]));
    }

    private void fetchAndRenderReport(String reportType, WebView webView, View layoutLoading, TextView tvLoadingStatus, String fromDate, String toDate) {
        if (getContext() == null) return;

        mainHandler.post(() -> {
            if (layoutLoading != null) layoutLoading.setVisibility(View.VISIBLE);
            if (tvLoadingStatus != null) tvLoadingStatus.setText(
                isArabic ? "جاري جلب وتحليل بيانات التقرير..." : "Fetching and analyzing report data...");
        });

        new Thread(() -> {
            JSONObject reportData = null;
            String errorMessage = null;
            String cacheKey = "cache_pro_" + reportType + "_" + fromDate + "_" + toDate;

            try {
                int activeBranchId = branchesManager != null ? branchesManager.getActiveBranchId() : 1;
                MiraApiClient.ApiResponse response = apiClient.getReportData(reportType, fromDate, toDate, activeBranchId);

                if (response != null && response.isSuccess && response.data != null) {
                    // ✅ الإصلاح: استخدم response.data مباشرة
                    reportData = response.data;
                    
                    // ✅ سجل تشخيصي
                    Log.d(TAG, "=== Report Response (" + reportType + ") ===");
                    Log.d(TAG, "Response keys: " + reportData.keys().toString());
                    Log.d(TAG, "Full data (first 500 chars): " + 
                        reportData.toString().substring(0, Math.min(500, reportData.toString().length())));
                    
                    // ✅ حفظ في الكاش
                    if (offlineCache != null) {
                        offlineCache.edit().putString(cacheKey, reportData.toString()).apply();
                    }
                } else {
                    errorMessage = isArabic ? 
                        "فشل الاتصال بالخادم (رمز الخطأ: " + (response != null ? response.code : "NULL") + ")" :
                        "Server connection failed (Error code: " + (response != null ? response.code : "NULL") + ")";
                    Log.e(TAG, "❌ Report fetch failed: " + errorMessage);
                }
            } catch (Exception e) {
                errorMessage = (isArabic ? "خطأ في المعالجة: " : "Processing error: ") + e.getMessage();
                Log.e(TAG, "Report fetch exception: " + e.getMessage());
            }

            // ✅ إذا فشل، حاول من الكاش
            if (reportData == null && offlineCache != null) {
                String cached = offlineCache.getString(cacheKey, null);
                if (cached != null) {
                    try { 
                        reportData = new JSONObject(cached); 
                    } catch (Exception ignored) {}
                }
            }

            final JSONObject finalData = reportData;
            final String finalError = errorMessage;

            mainHandler.post(() -> {
                if (getContext() == null || webView == null) return;

                if (layoutLoading != null) {
                    layoutLoading.setVisibility(View.GONE);
                }

                String htmlContent;
                if (finalData != null) {
                    htmlContent = generateExecutiveHTML(reportType, finalData, fromDate, toDate);
                } else {
                    String errorTitle = isArabic ? "❌ عذراً، لا توجد بيانات متاحة" : "❌ No data available";
                    String errorDesc = finalError != null ? finalError : 
                        (isArabic ? "يرجى التحقق من اتصال الإنترنت أو محاولة اختيار فترة زمنية أخرى." : 
                        "Please check your internet connection or try another date range.");
                    
                    htmlContent = "<html dir='" + (isArabic ? "rtl" : "ltr") + "'><body style='text-align:center;padding:50px;font-family:sans-serif;background:#F4F6F9;color:#1F2937;'>" +
                            "<h2 style='color:#DC2626;'>" + errorTitle + "</h2>" +
                            "<p style='color:#64748B;'>" + errorDesc + "</p>" +
                            "</body></html>";
                }

                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        super.onPageFinished(view, url);
                        view.requestLayout();
                    }
                });

                webView.loadDataWithBaseURL("file:///android_asset/", htmlContent, "text/html", "UTF-8", null);
            });
        }).start();
    }

    private String generateExecutiveHTML(String type, JSONObject data, String from, String to) {
        StringBuilder html = new StringBuilder();
        String orgName = orgProfile != null ? orgProfile.getOrgName() : "MIRA JEWELRY SYSTEM";
        String dir = isArabic ? "rtl" : "ltr";
        String lang = isArabic ? "ar" : "en";

        html.append("<!DOCTYPE html><html dir='").append(dir).append("' lang='").append(lang).append("'><head><meta charset='UTF-8'>")
            .append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>")
            .append("<style>")
            .append("body{font-family:sans-serif;margin:0;padding:12px;background:#F4F6F9;color:#1F2937;}")
            .append(".header{background:linear-gradient(135deg,#1E293B,#0F172A);color:#FFF;padding:16px;border-radius:12px;margin-bottom:12px;}")
            .append(".header h2{margin:0;font-size:18px;color:#F1F5F9;}")
            .append(".header p{margin:4px 0 0;font-size:11px;color:#94A3B8;}")
            .append(".card{background:#FFF;padding:14px;border-radius:10px;margin-bottom:12px;border:1px solid #E2E8F0;box-shadow: 0 1px 3px rgba(0,0,0,0.05);}")
            .append(".card-title{font-size:14px;font-weight:bold;color:#0F172A;margin-bottom:10px;border-bottom:2px solid #F1F5F9;padding-bottom:6px;}")
            .append(".grid{display:grid;grid-template-columns:repeat(2, 1fr);gap:8px;margin-bottom:10px;}")
            .append(".metric{background:#F8FAFC;border:1px solid #E2E8F0;padding:10px;border-radius:8px;text-align:center;}")
            .append(".metric .val{font-size:15px;font-weight:800;color:#0F172A;}")
            .append(".metric .lbl{font-size:10px;color:#64748B;}")
            .append(".gold{color:#D97706;font-weight:bold;}")
            .append("table{width:100%;border-collapse:collapse;margin-top:8px;font-size:11px;}")
            .append("th{background:#0F172A;color:#FFF;padding:8px;text-align:center;}")
            .append("td{padding:8px;border-bottom:1px solid #E2E8F0;text-align:center;}")
            .append("</style></head><body>");

        html.append("<div class='header'><h2>").append(orgName).append("</h2>")
            .append("<p>").append(isArabic ? "الفترة من " : "Period: ").append(from)
            .append(isArabic ? " إلى " : " to ").append(to).append("</p></div>");

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
        // ✅ الإصلاح: قراءة البنية الصحيحة
        JSONObject inv = data.optJSONObject("inventory");
        JSONObject sales = data.optJSONObject("sales");
        
        if (inv == null) inv = data;
        if (sales == null) sales = data;

        html.append("<div class='card'><div class='card-title'>")
            .append(isArabic ? "📊 الملخص التنفيذي" : "📊 Executive Summary")
            .append("</div><div class='grid'>");
        html.append(metricBox("📦", String.valueOf(inv.optInt("total_items", 0)), 
            isArabic ? "إجمالي القطع" : "Total Items"));
        html.append(metricBox("⚖️", String.format(Locale.US, "%.1f g", inv.optDouble("total_weight", 0)), 
            isArabic ? "الوزن الإجمالي" : "Total Weight"));
        html.append(metricBox("✨", String.format(Locale.US, "%.1f g", inv.optDouble("net_24k_weight", 0)), 
            isArabic ? "صافي 24K" : "Net 24K", "gold"));
        html.append(metricBox("💰", String.format(Locale.US, "%,.0f", sales.optDouble("total_revenue", 0)), 
            isArabic ? "الإيرادات" : "Revenue"));
        html.append("</div></div>");
    }

    private void renderInventoryHTML(StringBuilder html, JSONObject data) {
        // ✅ الإصلاح: قراءة karat_breakdown من inventory
        JSONObject inv = data.optJSONObject("inventory");
        if (inv == null) inv = data;

        html.append("<div class='card'><div class='card-title'>")
            .append(isArabic ? "📦 تفكيك العيارات والمخزون" : "📦 Karat & Inventory Breakdown")
            .append("</div>")
            .append("<table><tr><th>").append(isArabic ? "العيار" : "Karat").append("</th><th>")
            .append(isArabic ? "الوزن القائم" : "Gross Weight").append("</th><th>")
            .append(isArabic ? "القطع" : "Items").append("</th><th>")
            .append(isArabic ? "صافي 24K" : "Net 24K").append("</th></tr>");

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
            html.append("<tr><td colspan='4'>").append(isArabic ? "لا توجد تفاصيل عيارات مجمعة" : "No karat details").append("</td></tr>");
        }
        html.append("</table></div>");
    }

    private void renderSalesHTML(StringBuilder html, JSONObject data) {
        // ✅ الإصلاح: قراءة من sales مباشرة
        JSONObject sales = data.optJSONObject("sales");
        if (sales == null) sales = data;

        html.append("<div class='card'><div class='card-title'>")
            .append(isArabic ? "💰 تحليل المبيعات والأرباح" : "💰 Sales & Profit Analysis")
            .append("</div><div class='grid'>")
            .append(metricBox("💵", String.format(Locale.US, "%,.0f", sales.optDouble("total_revenue", 0)), 
                isArabic ? "الإيرادات الكلية" : "Total Revenue"))
            .append(metricBox("🔨", String.format(Locale.US, "%,.0f", sales.optDouble("total_making_charges", 0)), 
                isArabic ? "أجور الصياغة" : "Making Charges"))
            .append(metricBox("📈", String.format(Locale.US, "%,.0f", sales.optDouble("net_profit", 0)), 
                isArabic ? "صافي الربح" : "Net Profit", "gold"))
            .append(metricBox("🧾", String.valueOf(sales.optInt("invoices_count", 0)), 
                isArabic ? "عدد الفواتير" : "Invoices"))
            .append("</div></div>");
    }

    private void renderMovementHTML(StringBuilder html, JSONObject data) {
        JSONArray movements = data.optJSONArray("movements");

        html.append("<div class='card'><div class='card-title'>")
            .append(isArabic ? "🔄 سجل الحركة والتحويلات" : "🔄 Movement & Transfers Log")
            .append("</div>");
        if (movements != null && movements.length() > 0) {
            html.append("<table><tr><th>").append(isArabic ? "الرمز" : "Code").append("</th><th>")
                .append(isArabic ? "من" : "From").append("</th><th>")
                .append(isArabic ? "إلى" : "To").append("</th><th>")
                .append(isArabic ? "الحالة" : "Status").append("</th></tr>");
            for (int i = 0; i < movements.length(); i++) {
                JSONObject m = movements.optJSONObject(i);
                if (m != null) {
                    html.append("<tr><td>").append(m.optString("transfer_code", m.optString("code", "—"))).append("</td>")
                        .append("<td>").append(m.optString("from_branch", "—")).append("</td>")
                        .append("<td>").append(m.optString("to_branch", "—")).append("</td>")
                        .append("<td>").append(m.optString("status", isArabic ? "مكتمل" : "Completed")).append("</td></tr>");
                }
            }
            html.append("</table>");
        } else {
            html.append("<p style='text-align:center;color:#64748B;'>")
                .append(isArabic ? "لا توجد عمليات تحويل مسجلة خلال الفترة" : "No transfers recorded in this period")
                .append("</p>");
        }
        html.append("</div>");
    }

    private void renderBranchesHTML(StringBuilder html, JSONObject data) {
        JSONArray branches = data.optJSONArray("branches_performance");

        html.append("<div class='card'><div class='card-title'>")
            .append(isArabic ? "🏢 أداء الفروع المقارن" : "🏢 Branch Performance Comparison")
            .append("</div>");
        if (branches != null && branches.length() > 0) {
            html.append("<table><tr><th>").append(isArabic ? "الفرع" : "Branch").append("</th><th>")
                .append(isArabic ? "القطع" : "Items").append("</th><th>")
                .append(isArabic ? "المبيعات" : "Sales").append("</th></tr>");
            for (int i = 0; i < branches.length(); i++) {
                JSONObject b = branches.optJSONObject(i);
                if (b != null) {
                    html.append("<tr><td>").append(b.optString("branch_name", b.optString("name", "—"))).append("</td>")
                        .append("<td>").append(b.optInt("items_count", 0)).append("</td>")
                        .append("<td class='gold'>").append(String.format(Locale.US, "%,.0f", b.optDouble("sales_total", 0))).append("</td></tr>");
                }
            }
            html.append("</table>");
        } else {
            html.append("<p style='text-align:center;color:#64748B;'>")
                .append(isArabic ? "لا توجد بيانات مقارنة متاحة للفروع" : "No branch comparison data available")
                .append("</p>");
        }
        html.append("</div>");
    }

    private void renderGatesHTML(StringBuilder html, JSONObject data) {
        JSONArray logs = data.optJSONArray("gate_logs");

        html.append("<div class='card'><div class='card-title'>")
            .append(isArabic ? "🚪 سجل البوابات الأمنية والتمرير" : "🚪 Gate Security & Passage Log")
            .append("</div>");

        if (logs != null && logs.length() > 0) {
            html.append("<table><tr><th>").append(isArabic ? "التاريخ والوقت" : "Date & Time").append("</th><th>")
                .append(isArabic ? "البوابة" : "Gate").append("</th><th>")
                .append(isArabic ? "رمز EPC" : "EPC Code").append("</th><th>")
                .append(isArabic ? "الحالة" : "Status").append("</th></tr>");
            for (int i = 0; i < Math.min(logs.length(), 50); i++) {
                JSONObject l = logs.optJSONObject(i);
                if (l == null) continue;

                String time = l.optString("created_at", "—");
                String gateName = l.optString("gate_name", isArabic ? "البوابة الرئيسية" : "Main Gate");
                String epc = l.optString("epc", "—");
                
                boolean isAlarm = "ALARM".equalsIgnoreCase(l.optString("status"));

                html.append("<tr><td>").append(time).append("</td>")
                    .append("<td>").append(gateName).append("</td>")
                    .append("<td><code>").append(epc).append("</code></td>")
                    .append("<td><span style='color:").append(isAlarm ? "#DC2626;'>" : "#16A34A;'>")
                    .append(isAlarm ? (isArabic ? "🚨 إنذار" : "🚨 Alarm") : (isArabic ? "✅ مصرح" : "✅ Authorized"))
                    .append("</span></td></tr>");
            }
            html.append("</table>");
        } else {
            html.append("<div style='text-align:center;padding:15px;color:#64748B;'>")
                .append(isArabic ? "ℹ️ لا توجد أحداث مرور أو تنبيهات مسجلة عبر البوابات حالياً." : 
                    "ℹ️ No gate passage events or alerts recorded.")
                .append("</div>");
        }
        html.append("</div>");
    }

    private void renderAuditHTML(StringBuilder html, JSONObject data) {
        // ✅ الإصلاح: قراءة من audit_summary مباشرة
        JSONObject audit = data.optJSONObject("audit_summary");
        if (audit == null) audit = data;

        int expected = audit.optInt("expected_items", 0);
        int found = audit.optInt("found_items", 0);
        int missing = audit.optInt("missing_items", 0);
        int sessions = audit.optInt("total_sessions", 0);
        
        double accuracy = audit.optDouble("accuracy", 0.0);

        html.append("<div class='card'><div class='card-title'>")
            .append(isArabic ? "📋 نتائج مطابقة الجرد" : "📋 Inventory Audit Results")
            .append("</div>");

        html.append("<div class='grid'>")
            .append(metricBox("🎯", String.format(Locale.US, "%.1f%%", accuracy), 
                isArabic ? "نسبة الدقة" : "Accuracy"))
            .append(metricBox("✅", String.valueOf(found), 
                isArabic ? "الموجودات" : "Found"))
            .append(metricBox("❌", String.valueOf(missing), 
                isArabic ? "المفقودات" : "Missing"))
            .append(metricBox("📊", String.valueOf(sessions), 
                isArabic ? "عدد الجلسات" : "Sessions"))
            .append("</div></div>");
    }

    private void renderReorderHTML(StringBuilder html, JSONObject data) {
        JSONArray items = data.optJSONArray("reorder_items");

        html.append("<div class='card'><div class='card-title'>")
            .append(isArabic ? "💎 قائمة النواقص وإعادة الطلب" : "💎 Reorder & Demand List")
            .append("</div>");
        if (items != null && items.length() > 0) {
            html.append("<table><tr><th>").append(isArabic ? "الصنف" : "Item").append("</th><th>")
                .append(isArabic ? "العيار" : "Karat").append("</th><th>")
                .append(isArabic ? "المباع" : "Sold").append("</th><th>")
                .append(isArabic ? "المتبقي" : "Remaining").append("</th></tr>");
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null) {
                    html.append("<tr><td>").append(item.optString("category", item.optString("name", "—"))).append("</td>")
                        .append("<td>").append(item.optString("karat", "—")).append("</td>")
                        .append("<td>").append(item.optInt("sold_count", 0)).append("</td>")
                        .append("<td>").append(item.optInt("remaining_stock", 0)).append("</td></tr>");
                }
            }
            html.append("</table>");
        } else {
            html.append("<p style='text-align:center;color:#64748B;'>")
                .append(isArabic ? "المخزون متوازن، لا توجد نواقص حرجة" : "Inventory balanced, no critical shortages")
                .append("</p>");
        }
        html.append("</div>");
    }

    private String metricBox(String icon, String val, String lbl) {
        return metricBox(icon, val, lbl, "");
    }

    private String metricBox(String icon, String val, String lbl, String extraClass) {
        return "<div class='metric'><div style='font-size:16px'>" + icon + "</div>" +
                "<div class='val " + extraClass + "'>" + val + "</div>" +
                "<div class='lbl'>" + lbl + "</div></div>";
    }

    private void printReportWebView(WebView webView, String jobTitle) {
        if (getContext() == null) return;
        PrintManager pm = (PrintManager) requireContext().getSystemService(Context.PRINT_SERVICE);
        if (pm != null) {
            pm.print(jobTitle, webView.createPrintDocumentAdapter(jobTitle), new PrintAttributes.Builder().build());
        }
    }

    private void exportReportToCSV(String type, String from, String to) {
        if (getContext() == null) return;
        try {
            String cacheKey = "cache_pro_" + type + "_" + from + "_" + to;
            String cached = offlineCache != null ? offlineCache.getString(cacheKey, null) : null;

            if (cached == null) {
                Toast.makeText(getContext(), 
                    isArabic ? "لا توجد بيانات محفوظة لتصديرها حالياً" : "No data to export",
                    Toast.LENGTH_SHORT).show();
                return;
            }

            File exportDir = new File(requireContext().getCacheDir(), "exports");
            if (!exportDir.exists()) exportDir.mkdirs();

            File file = new File(exportDir, "MIRA_Report_" + type + "_" + System.currentTimeMillis() + ".csv");
            FileOutputStream fos = new FileOutputStream(file);
            
            fos.write(0xEF); fos.write(0xBB); fos.write(0xBF);

            StringBuilder csvContent = new StringBuilder();
            csvContent.append(isArabic ? "تقرير MIRA System," : "MIRA System Report,").append(type).append("\n");
            csvContent.append(isArabic ? "من," : "From,").append(from).append(isArabic ? ",إلى," : ",To,").append(to).append("\n\n");
            csvContent.append(isArabic ? "البيانات الخام (JSON)\n" : "Raw Data (JSON)\n");
            csvContent.append("\"").append(cached.replace("\"", "\"\"")).append("\"\n");

            fos.write(csvContent.toString().getBytes("UTF-8"));
            fos.close();

            Uri contentUri = FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".fileprovider", file);
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/csv");
            shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, 
                isArabic ? "مشاركة التقرير عبر" : "Share report via"));

        } catch (Exception e) {
            Toast.makeText(getContext(), 
                (isArabic ? "فشل التصدير: " : "Export failed: ") + e.getMessage(),
                Toast.LENGTH_SHORT).show();
        }
    }

    private String getDefaultFromDate() { return getCalculatedDate(-30); }
    private String getDefaultToDate() { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()); }

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

    static class ReportItem {
        String icon, title, subtitle, type;
        ReportItem(String i, String t, String s, String ty) { icon=i; title=t; subtitle=s; type=ty; }
    }

    class ReportsAdapter extends RecyclerView.Adapter<ReportsAdapter.VH> {
        final List<ReportItem> list;
        final OnReportClickListener listener;
        ReportsAdapter(List<ReportItem> l, OnReportClickListener c) { list=l; listener=c; }

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
        public int getItemCount() { return list.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvIcon, tvTitle, tvSubtitle;
            VH(View v) { super(v);
                tvIcon = v.findViewById(R.id.tvReportIcon);
                tvTitle = v.findViewById(R.id.tvReportTitle);
                tvSubtitle = v.findViewById(R.id.tvReportSubtitle);
            }
        }
    }

    interface OnReportClickListener { void onClick(ReportItem item); }
    interface DatePickerCallback { void onDate(String date); }
            }
