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

        // إعدادات الـ WebView
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);
        webSettings.setCacheMode(WebSettings.LOAD_NO_CACHE);

        final String[] dates = {getDefaultFromDate(), getDefaultToDate()};
        btnFrom.setText("من: " + dates[0]);
        btnTo.setText("إلى: " + dates[1]);

        AlertDialog dialog = new AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen)
                .setView(dialogView)
                .create();

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
        
        btnClose.setOnClickListener(v -> {
            try {
                webView.stopLoading();
                webView.destroy();
            } catch (Exception ignored) {}
            dialog.dismiss();
        });

        dialog.show();

        // تأخير الاستدعاء حتى يكتمل تجهيز قياسات الشاشة والـ WebView
        webView.post(() -> fetchAndRenderReport(item.type, webView, layoutLoading, tvLoadingStatus, dates[0], dates[1]));
    }

    private void fetchAndRenderReport(String reportType, WebView webView, View layoutLoading, TextView tvLoadingStatus, String fromDate, String toDate) {
        if (getContext() == null) return;

        mainHandler.post(() -> {
            if (layoutLoading != null) layoutLoading.setVisibility(View.VISIBLE);
            if (tvLoadingStatus != null) tvLoadingStatus.setText("جاري جلب وتحليل بيانات التقرير...");
        });

        new Thread(() -> {
            JSONObject reportData = null;
            String errorMessage = null;
            String cacheKey = "cache_pro_" + reportType + "_" + fromDate + "_" + toDate;

            try {
                int activeBranchId = branchesManager != null ? branchesManager.getActiveBranchId() : 1;
                MiraApiClient.ApiResponse response = apiClient.getReportData(reportType, fromDate, toDate, activeBranchId);

                if (response != null && response.isSuccess && response.data != null) {
                    if (response.data.has("data")) {
                        Object innerData = response.data.opt("data");
                        if (innerData instanceof JSONObject) {
                            reportData = (JSONObject) innerData;
                        } else if (innerData instanceof JSONArray) {
                            reportData = new JSONObject();
                            reportData.put("items_array", innerData);
                        }
                    }
                    if (reportData == null) {
                        reportData = response.data;
                    }

                    if (offlineCache != null && reportData != null) {
                        offlineCache.edit().putString(cacheKey, reportData.toString()).apply();
                    }
                } else {
                    errorMessage = "فشل الاتصال بالخادم (رمز الخطأ: " + (response != null ? response.code : "NULL") + ")";
                }
            } catch (Exception e) {
                errorMessage = "خطأ في المعالجة: " + e.getMessage();
            }

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
                    htmlContent = "<html dir='rtl'><body style='text-align:center;padding:50px;font-family:sans-serif;background:#F4F6F9;color:#1F2937;'>" +
                            "<h2 style='color:#DC2626;'>❌ عذراً، لا توجد بيانات متاحة</h2>" +
                            "<p style='color:#64748B;'>" + (finalError != null ? finalError : "يرجى التحقق من اتصال الإنترنت أو محاولة اختيار فترة زمنية أخرى.") + "</p>" +
                            "</body></html>";
                }

                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        super.onPageFinished(view, url);
                        view.requestLayout(); // إجبار الـ WebView على إعادة حساب أبعاده
                    }
                });

                // تحميل البيانات بتشفر UTF-8 آمن
                webView.loadDataWithBaseURL("file:///android_asset/", htmlContent, "text/html", "UTF-8", null);
            });
        }).start();
    }

    // ==================== HTML GENERATOR ====================

    private String generateExecutiveHTML(String type, JSONObject data, String from, String to) {
        StringBuilder html = new StringBuilder();
        String orgName = orgProfile != null ? orgProfile.getOrgName() : "MIRA JEWELRY SYSTEM";

        html.append("<!DOCTYPE html><html dir='rtl' lang='ar'><head><meta charset='UTF-8'>")
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
        JSONObject inv = data.has("inventory") ? data.optJSONObject("inventory") : data;
        JSONObject sales = data.has("sales") ? data.optJSONObject("sales") : data;

        html.append("<div class='card'><div class='card-title'>📊 الملخص التنفيذي</div><div class='grid'>");
        html.append(metricBox("📦", String.valueOf(inv.optInt("total_items", inv.optInt("count", 0))), "إجمالي القطع"));
        html.append(metricBox("⚖️", String.format(Locale.US, "%.1f جم", inv.optDouble("total_weight", 0)), "الوزن الإجمالي"));
        html.append(metricBox("✨", String.format(Locale.US, "%.1f جم", inv.optDouble("net_24k_weight", 0)), "صافي 24K", "gold"));
        html.append(metricBox("💰", String.format(Locale.US, "%,.0f", sales.optDouble("total_revenue", 0)), "الإيرادات"));
        html.append("</div></div>");

        renderInventoryHTML(html, data);
    }

    private void renderInventoryHTML(StringBuilder html, JSONObject data) {
        JSONObject inv = data.has("inventory") ? data.optJSONObject("inventory") : data;

        html.append("<div class='card'><div class='card-title'>📦 تفكيك العيارات والمخزون</div>")
            .append("<table><tr><th>العيار</th><th>الوزن القائم</th><th>القطع</th><th>صافي 24K</th></tr>");

        JSONArray karats = inv.optJSONArray("karat_breakdown");
        if (karats == null) karats = inv.optJSONArray("items_array");

        if (karats != null && karats.length() > 0) {
            for (int i = 0; i < karats.length(); i++) {
                JSONObject k = karats.optJSONObject(i);
                if (k == null) continue;
                html.append("<tr><td><b>").append(k.optString("karat", k.optString("name", "—"))).append("</b></td>")
                    .append("<td>").append(String.format(Locale.US, "%.2f", k.optDouble("gross_weight", k.optDouble("weight", 0)))).append("</td>")
                    .append("<td>").append(k.optInt("count", k.optInt("items_count", 0))).append("</td>")
                    .append("<td class='gold'>").append(String.format(Locale.US, "%.2f", k.optDouble("net_24k", 0))).append("</td></tr>");
            }
        } else {
            html.append("<tr><td colspan='4'>لا توجد تفاصيل عيارات مجمعة</td></tr>");
        }
        html.append("</table></div>");
    }

    private void renderSalesHTML(StringBuilder html, JSONObject data) {
        JSONObject sales = data.has("sales") ? data.optJSONObject("sales") : data;

        html.append("<div class='card'><div class='card-title'>💰 تحليل المبيعات والأرباح</div><div class='grid'>")
            .append(metricBox("💵", String.format(Locale.US, "%,.0f", sales.optDouble("total_revenue", 0)), "الإيرادات الكلية"))
            .append(metricBox("🔨", String.format(Locale.US, "%,.0f", sales.optDouble("total_making_charges", 0)), "أجور الصياغة"))
            .append(metricBox("📈", String.format(Locale.US, "%,.0f", sales.optDouble("net_profit", 0)), "صافي الربح", "gold"))
            .append(metricBox("🧾", String.valueOf(sales.optInt("invoices_count", 0)), "عدد الفواتير"))
            .append("</div></div>");
    }

    private void renderMovementHTML(StringBuilder html, JSONObject data) {
        JSONArray movements = data.optJSONArray("movements");
        if (movements == null) movements = data.optJSONArray("items_array");

        html.append("<div class='card'><div class='card-title'>🔄 سجل الحركة والتحويلات</div>");
        if (movements != null && movements.length() > 0) {
            html.append("<table><tr><th>الرمز</th><th>من</th><th>إلى</th><th>الحالة</th></tr>");
            for (int i = 0; i < movements.length(); i++) {
                JSONObject m = movements.optJSONObject(i);
                if (m != null) {
                    html.append("<tr><td>").append(m.optString("transfer_code", m.optString("code", "—"))).append("</td>")
                        .append("<td>").append(m.optString("from_branch", "—")).append("</td>")
                        .append("<td>").append(m.optString("to_branch", "—")).append("</td>")
                        .append("<td>").append(m.optString("status", "مكتمل")).append("</td></tr>");
                }
            }
            html.append("</table>");
        } else {
            html.append("<p style='text-align:center;color:#64748B;'>لا توجد عمليات تحويل مسجلة خلال الفترة</p>");
        }
        html.append("</div>");
    }

    private void renderBranchesHTML(StringBuilder html, JSONObject data) {
        JSONArray branches = data.optJSONArray("branches_performance");
        if (branches == null) branches = data.optJSONArray("items_array");

        html.append("<div class='card'><div class='card-title'>🏢 أداء الفروع المقارن</div>");
        if (branches != null && branches.length() > 0) {
            html.append("<table><tr><th>الفرع</th><th>القطع</th><th>المبيعات</th></tr>");
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
            html.append("<p style='text-align:center;color:#64748B;'>لا توجد بيانات مقارنة متاحة للفروع</p>");
        }
        html.append("</div>");
    }

    private void renderGatesHTML(StringBuilder html, JSONObject data) {
        JSONArray logs = data.optJSONArray("gate_logs");
        if (logs == null) logs = data.optJSONArray("items_array");

        html.append("<div class='card'><div class='card-title'>🚪 سجل البوابات والإنذارات</div>");
        if (logs != null && logs.length() > 0) {
            html.append("<table><tr><th>الوقت</th><th>البوابة</th><th>رمز EPC</th></tr>");
            for (int i = 0; i < Math.min(logs.length(), 50); i++) {
                JSONObject l = logs.optJSONObject(i);
                if (l != null) {
                    html.append("<tr><td>").append(l.optString("timestamp", l.optString("created_at", "—"))).append("</td>")
                        .append("<td>").append(l.optString("gate_name", "البوابة الرئيسية")).append("</td>")
                        .append("<td>").append(l.optString("epc", "—")).append("</td></tr>");
                }
            }
            html.append("</table>");
        } else {
            html.append("<p style='text-align:center;color:#64748B;'>سجل البوابات نظيف، لا توجد تنبيهات</p>");
        }
        html.append("</div>");
    }

    private void renderAuditHTML(StringBuilder html, JSONObject data) {
        JSONObject audit = data.has("audit_summary") ? data.optJSONObject("audit_summary") : data;

        html.append("<div class='card'><div class='card-title'>📋 نتائج مطابقة الجرد</div><div class='grid'>")
            .append(metricBox("🎯", String.format(Locale.US, "%.1f%%", audit.optDouble("accuracy", 100.0)), "نسبة الدقة"))
            .append(metricBox("✅", String.valueOf(audit.optInt("found_items", audit.optInt("total_found", 0))), "الموجودات"))
            .append(metricBox("❌", String.valueOf(audit.optInt("missing_items", audit.optInt("total_missing", 0))), "المفقودات"))
            .append("</div></div>");
    }

    private void renderReorderHTML(StringBuilder html, JSONObject data) {
        JSONArray items = data.optJSONArray("reorder_items");
        if (items == null) items = data.optJSONArray("items_array");

        html.append("<div class='card'><div class='card-title'>💎 قائمة النواقص وإعادة الطلب</div>");
        if (items != null && items.length() > 0) {
            html.append("<table><tr><th>الصنف</th><th>العيار</th><th>المباع</th><th>المتبقي</th></tr>");
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
            html.append("<p style='text-align:center;color:#64748B;'>المخزون متوازن، لا توجد نواقص حرجة</p>");
        }
        html.append("</div>");
    }

    private String metricBox(String icon, String val, String lbl, String extraClass) {
        return "<div class='metric'><div style='font-size:16px'>" + icon + "</div>" +
                "<div class='val " + extraClass + "'>" + val + "</div>" +
                "<div class='lbl'>" + lbl + "</div></div>";
    }

    private String metricBox(String icon, String val, String lbl) {
        return metricBox(icon, val, lbl, "");
    }

    // ==================== PRINT & EXPORT ====================

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
                Toast.makeText(getContext(), "لا توجد بيانات محفوظة لتصديرها حالياً", Toast.LENGTH_SHORT).show();
                return;
            }

            File exportDir = new File(requireContext().getCacheDir(), "exports");
            if (!exportDir.exists()) exportDir.mkdirs();

            File file = new File(exportDir, "MIRA_Report_" + type + "_" + System.currentTimeMillis() + ".csv");
            FileOutputStream fos = new FileOutputStream(file);
            
            // إضافة BOM لدعم اللغة العربية في Excel
            fos.write(0xEF); fos.write(0xBB); fos.write(0xBF);

            StringBuilder csvContent = new StringBuilder();
            csvContent.append("تقرير MIRA System,").append(type).append("\n");
            csvContent.append("من,").append(from).append(",إلى,").append(to).append("\n\n");
            csvContent.append("البيانات الخام (JSON)\n");
            csvContent.append("\"").append(cached.replace("\"", "\"\"")).append("\"\n");

            fos.write(csvContent.toString().getBytes("UTF-8"));
            fos.close();

            Uri contentUri = FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".fileprovider", file);
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/csv");
            shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "مشاركة التقرير عبر"));

        } catch (Exception e) {
            Toast.makeText(getContext(), "فشل التصدير: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== HELPERS ====================

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

    // ==================== ADAPTER ====================

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
