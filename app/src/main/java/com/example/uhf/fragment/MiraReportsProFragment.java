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
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
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
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
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
        // عرض شبكي (Grid) احترافي يتكيف مع الشاشات
        rvReports.setLayoutManager(new GridLayoutManager(getContext(), 2));

        buildReportsList();
        adapter = new ReportsAdapter(reportList, this::openReportDialog);
        rvReports.setAdapter(adapter);

        View btnExportAll = view.findViewById(R.id.btnExportAll);
        if (btnExportAll != null) {
            btnExportAll.setOnClickListener(v -> exportExecutiveSummary());
        }

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
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_mira_report_viewer, null);

        TextView tvTitle = dialogView.findViewById(R.id.tvReportDialogTitle);
        WebView webView = dialogView.findViewById(R.id.webReportView);
        MaterialButton btnFrom = dialogView.findViewById(R.id.btnReportDateFrom);
        MaterialButton btnTo = dialogView.findViewById(R.id.btnReportDateTo);
        MaterialButton btnApply = dialogView.findViewById(R.id.btnReportApplyFilter);
        MaterialButton btnPrint = dialogView.findViewById(R.id.btnReportPrint);
        MaterialButton btnExport = dialogView.findViewById(R.id.btnReportExport);
        MaterialButton btnClose = dialogView.findViewById(R.id.btnReportClose);

        // أزرار الفترات السريعة
        View btnToday = dialogView.findViewById(R.id.btnQuickToday);
        View btnWeek = dialogView.findViewById(R.id.btnQuickWeek);
        View btnMonth = dialogView.findViewById(R.id.btnQuickMonth);

        tvTitle.setText(item.icon + " " + item.title);

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);
        webView.setWebViewClient(new WebViewClient());

        final String[] dates = {getDefaultFromDate(), getDefaultToDate()};
        btnFrom.setText(dates[0]);
        btnTo.setText(dates[1]);

        AlertDialog dialog = new AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Light_NoActionBar)
                .setView(dialogView)
                .create();

        // إعداد المستمعات للتواريخ
        btnFrom.setOnClickListener(v -> showDatePicker(date -> {
            dates[0] = date;
            btnFrom.setText(date);
        }));

        btnTo.setOnClickListener(v -> showDatePicker(date -> {
            dates[1] = date;
            btnTo.setText(date);
        }));

        btnToday.setOnClickListener(v -> {
            String today = getDefaultToDate();
            dates[0] = today; dates[1] = today;
            btnFrom.setText(today); btnTo.setText(today);
            fetchAndRenderReport(item.type, webView, dates[0], dates[1]);
        });

        btnWeek.setOnClickListener(v -> {
            dates[0] = getCalculatedDate(-7); dates[1] = getDefaultToDate();
            btnFrom.setText(dates[0]); btnTo.setText(dates[1]);
            fetchAndRenderReport(item.type, webView, dates[0], dates[1]);
        });

        btnMonth.setOnClickListener(v -> {
            dates[0] = getCalculatedDate(-30); dates[1] = getDefaultToDate();
            btnFrom.setText(dates[0]); btnTo.setText(dates[1]);
            fetchAndRenderReport(item.type, webView, dates[0], dates[1]);
        });

        btnApply.setOnClickListener(v -> fetchAndRenderReport(item.type, webView, dates[0], dates[1]));
        btnPrint.setOnClickListener(v -> printReportWebView(webView, item.title));
        btnExport.setOnClickListener(v -> exportReportToCSV(item.type, dates[0], dates[1]));
        btnClose.setOnClickListener(v -> dialog.dismiss());

        // تحميل أولي للتقرير
        fetchAndRenderReport(item.type, webView, dates[0], dates[1]);

        dialog.show();
    }

    // ==================== DATA FETCHING & PROCESSING ====================

    private void fetchAndRenderReport(String reportType, WebView webView, String fromDate, String toDate) {
        webView.loadDataWithBaseURL(null, buildLoadingHTML(), "text/html", "UTF-8", null);

        new Thread(() -> {
            JSONObject reportData = null;
            String cacheKey = "cache_" + reportType + "_" + fromDate + "_" + toDate;

            try {
                int activeBranchId = branchesManager != null ? branchesManager.getActiveBranchId() : -1;
                MiraApiClient.ApiResponse response = apiClient.getReportData(reportType, fromDate, toDate, activeBranchId);

                if (response != null && response.isSuccess && response.data != null) {
                    reportData = response.data;
                    // حفظ الكاش
                    offlineCache.edit().putString(cacheKey, reportData.toString()).apply();
                }
            } catch (Exception e) {
                Log.e(TAG, "API Fetch failed for report " + reportType + ": " + e.getMessage());
            }

            // الاستعانة بالكاش إذا فشل الاتصال بالشبكة
            if (reportData == null) {
                String cachedJson = offlineCache.getString(cacheKey, null);
                if (cachedJson != null) {
                    try {
                        reportData = new JSONObject(cachedJson);
                    } catch (Exception ignored) {}
                }
            }

            final JSONObject finalData = reportData;
            mainHandler.post(() -> {
                if (finalData != null) {
                    String htmlContent = generateExecutiveHTML(reportType, finalData, fromDate, toDate);
                    webView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null);
                } else {
                    webView.loadDataWithBaseURL(null, buildEmptyOrErrorHTML(reportType), "text/html", "UTF-8", null);
                }
            });
        }).start();
    }

    // ==================== HTML GENERATOR (GOLD & RFID ENGINE) ====================

    private String generateExecutiveHTML(String type, JSONObject data, String from, String to) {
        StringBuilder html = new StringBuilder();
        String orgName = orgProfile != null ? orgProfile.getOrgName() : "MIRA JEWELRY SYSTEM";

        html.append("<!DOCTYPE html><html dir='rtl' lang='ar'><head><meta charset='UTF-8'>")
            .append("<style>")
            .append("body{font-family:'Segoe UI',Tahoma,sans-serif;margin:0;padding:12px;background:#F4F6F9;color:#1F2937;}")
            .append(".header-card{background:linear-gradient(135deg, #1E293B, #0F172A);color:#FFF;padding:16px;border-radius:12px;margin-bottom:12px;box-shadow:0 4px 6px rgba(0,0,0,0.1);}")
            .append(".header-card h2{margin:0 0 4px 0;font-size:18px;color:#F1F5F9;}")
            .append(".header-card p{margin:0;font-size:11px;color:#94A3B8;}")
            .append(".card{background:#FFF;padding:14px;border-radius:10px;margin-bottom:12px;border:1px solid #E2E8F0;box-shadow:0 1px 3px rgba(0,0,0,0.05);}")
            .append(".card-title{font-size:14px;font-weight:bold;color:#0F172A;margin-bottom:10px;display:flex;align-items:center;border-bottom:2px solid #F1F5F9;padding-bottom:6px;}")
            .append(".grid{display:grid;grid-template-columns:repeat(auto-fit, minmax(100px, 1fr));gap:8px;margin-bottom:10px;}")
            .append(".metric{background:#F8FAFC;border:1px solid #E2E8F0;padding:10px;border-radius:8px;text-align:center;}")
            .append(".metric .val{font-size:16px;font-weight:800;color:#0F172A;margin:2px 0;}")
            .append(".metric .lbl{font-size:10px;color:#64748B;}")
            .append(".gold-highlight{color:#D97706;font-weight:bold;}")
            .append("table{width:100%;border-collapse:collapse;margin-top:8px;font-size:11px;}")
            .append("th{background:#0F172A;color:#FFF;padding:8px;text-align:center;font-weight:600;}")
            .append("td{padding:8px;border-bottom:1px solid #E2E8F0;text-align:center;color:#334155;}")
            .append("tr:nth-child(even){background-color:#F8FAFC;}")
            .append(".badge{padding:3px 6px;border-radius:4px;font-size:9px;font-weight:bold;display:inline-block;}")
            .append(".badge-success{background:#DCFCE7;color:#15803D;}")
            .append(".badge-danger{background:#FEE2E2;color:#B91C1C;}")
            .append(".badge-warning{background:#FEF3C7;color:#B45309;}")
            .append("</style></head><body>");

        // Header Section
        html.append("<div class='header-card'>")
            .append("<h2>").append(orgName).append("</h2>")
            .append("<p>فترة التقرير: ").append(from).append(" إلى ").append(to).append(" | تم التوليد: ").append(getFormattedCurrentTime()).append("</p>")
            .append("</div>");

        switch (type) {
            case "inventory":
                renderInventoryHTML(html, data);
                break;
            case "sales":
                renderSalesHTML(html, data);
                break;
            case "movement":
                renderMovementHTML(html, data);
                break;
            case "branches":
                renderBranchesHTML(html, data);
                break;
            case "gates":
                renderGatesHTML(html, data);
                break;
            case "audit":
                renderAuditHTML(html, data);
                break;
            case "reorder":
                renderReorderHTML(html, data);
                break;
            case "dashboard":
            default:
                renderDashboardHTML(html, data);
                break;
        }

        // Footer
        html.append("<div style='text-align:center;margin-top:20px;font-size:10px;color:#94A3B8;'>")
            .append("نظام MIRA ERP للمجوهرات والـ RFID © 2026 | تقرير معتمد آلياً</div>")
            .append("</body></html>");

        return html.toString();
    }

    // --- التفاصيل التخصصية لكل تقرير ---

    private void renderDashboardHTML(StringBuilder html, JSONObject data) {
        JSONObject inv = data.optJSONObject("inventory");
        JSONObject sales = data.optJSONObject("sales");
        JSONObject net24 = data.optJSONObject("net_gold_24k");

        html.append("<div class='card'><div class='card-title'>⚖️ ميزان الذهب الصافي والسيولة</div><div class='grid'>");
        if (net24 != null) {
            html.append(metricBox("🥇", String.format(Locale.US, "%.2fg", net24.optDouble("total_net_24k", 0)), "إجمالي الصافي 24K", "gold-highlight"))
                .append(metricBox("📦", String.valueOf(inv != null ? inv.optInt("total_items", 0) : 0), "عدد القطع", ""))
                .append(metricBox("💰", String.format(Locale.US, "%,.0f", sales != null ? sales.optDouble("total_cash", 0) : 0), "النقد المحصل", ""));
        }
        html.append("</div></div>");

        if (inv != null) renderInventoryHTML(html, data);
    }

    private void renderInventoryHTML(StringBuilder html, JSONObject data) {
        JSONObject inv = data.optJSONObject("inventory");
        if (inv == null) {
            html.append("<div class='card'>لا توجد بيانات مخزون</div>");
            return;
        }

        double totalWeight = inv.optDouble("total_weight", 0);
        double net24 = inv.optDouble("net_24k_weight", 0);

        html.append("<div class='card'><div class='card-title'>📦 توزيع الأوزان والعيارات (العيار والحسابة)</div>")
            .append("<div class='grid'>")
            .append(metricBox("⚖️", String.format(Locale.US, "%.2fg", totalWeight), "الوزن القائم", ""))
            .append(metricBox("✨", String.format(Locale.US, "%.2fg", net24), "المكافئ صافي 24K", "gold-highlight"))
            .append(metricBox("🧩", String.valueOf(inv.optInt("total_items", 0)), "إجمالي القطع", ""))
            .append("</div>");

        html.append("<table><tr><th>العيار</th><th>الوزن القائم (جرام)</th><th>معامل التحويل</th><th>الصافي (24K)</th><th>عدد القطع</th></tr>");

        JSONArray karats = inv.optJSONArray("karat_breakdown");
        if (karats != null) {
            for (int i = 0; i < karats.length(); i++) {
                JSONObject k = karats.optJSONObject(i);
                if (k == null) continue;
                html.append("<tr>")
                    .append("<td><b>").append(k.optString("karat", "—")).append("</b></td>")
                    .append("<td>").append(String.format(Locale.US, "%.2f", k.optDouble("gross_weight", 0))).append("</td>")
                    .append("<td>").append(String.format(Locale.US, "%.3f", k.optDouble("factor", 0))).append("</td>")
                    .append("<td class='gold-highlight'>").append(String.format(Locale.US, "%.2f", k.optDouble("net_24k", 0))).append("</td>")
                    .append("<td>").append(k.optInt("count", 0)).append("</td>")
                    .append("</tr>");
            }
        }
        html.append("</table></div>");
    }

    private void renderSalesHTML(StringBuilder html, JSONObject data) {
        JSONObject sales = data.optJSONObject("sales");
        if (sales == null) {
            html.append("<div class='card'>لا توجد بيانات مبيعات لهذه الفترة</div>");
            return;
        }

        html.append("<div class='card'><div class='card-title'>💰 تحليل المبيعات وأجور الصياغة</div><div class='grid'>")
            .append(metricBox("💵", String.format(Locale.US, "%,.0f YER", sales.optDouble("total_revenue", 0)), "إجمالي المبيعات", ""))
            .append(metricBox("🔨", String.format(Locale.US, "%,.0f YER", sales.optDouble("total_making_charges", 0)), "أجور الصياغة", ""))
            .append(metricBox("📈", String.format(Locale.US, "%,.0f YER", sales.optDouble("net_profit", 0)), "صافي الربح", "gold-highlight"))
            .append("</div></div>");

        html.append("<div class='card'><div class='card-title'>🧾 الفواتير التفصيلية</div><table>")
            .append("<tr><th>رقم الفاتورة</th><th>العميل</th><th>الوزن المباع</th><th>الأجور</th><th>الإجمالي</th><th>طريقة الدفع</th></tr>");

        JSONArray list = sales.optJSONArray("invoices");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject inv = list.optJSONObject(i);
                if (inv == null) continue;
                html.append("<tr>")
                    .append("<td>#").append(inv.optString("invoice_no", "—")).append("</td>")
                    .append("<td>").append(inv.optString("customer_name", "عميل نقدي")).append("</td>")
                    .append("<td>").append(String.format(Locale.US, "%.2fg", inv.optDouble("weight", 0))).append("</td>")
                    .append("<td>").append(String.format(Locale.US, "%,.0f", inv.optDouble("making_fee", 0))).append("</td>")
                    .append("<td><b>").append(String.format(Locale.US, "%,.0f", inv.optDouble("total", 0))).append("</b></td>")
                    .append("<td><span class='badge badge-success'>").append(inv.optString("payment_method", "CASH")).append("</span></td>")
                    .append("</tr>");
            }
        }
        html.append("</table></div>");
    }

    private void renderMovementHTML(StringBuilder html, JSONObject data) {
        html.append("<div class='card'><div class='card-title'>🔄 سجل تحويلات الذهب بين الفروع</div><table>")
            .append("<tr><th>رقم السند</th><th>من فرع/خزينة</th><th>إلى فرع/خزينة</th><th>عدد القطع</th><th>الوزن القائم</th><th>الحالة</th></tr>");

        JSONArray movements = data.optJSONArray("movements");
        if (movements != null) {
            for (int i = 0; i < movements.length(); i++) {
                JSONObject m = movements.optJSONObject(i);
                if (m == null) continue;
                String status = m.optString("status", "PENDING");
                String badgeClass = status.equals("COMPLETED") ? "badge-success" : "badge-warning";

                html.append("<tr>")
                    .append("<td>").append(m.optString("transfer_code", "—")).append("</td>")
                    .append("<td>").append(m.optString("from_branch", "—")).append("</td>")
                    .append("<td>").append(m.optString("to_branch", "—")).append("</td>")
                    .append("<td>").append(m.optInt("items_count", 0)).append("</td>")
                    .append("<td>").append(String.format(Locale.US, "%.2fg", m.optDouble("total_weight", 0))).append("</td>")
                    .append("<td><span class='badge ").append(badgeClass).append("'>").append(status).append("</span></td>")
                    .append("</tr>");
            }
        }
        html.append("</table></div>");
    }

    private void renderBranchesHTML(StringBuilder html, JSONObject data) {
        html.append("<div class='card'><div class='card-title'>🏢 أداء وفروقات الفروع</div><table>")
            .append("<tr><th>اسم الفرع</th><th>قطع المخزون</th><th>وزن المخزون</th><th>مبيعات الفترة</th><th>نسبة تحقيق الهدف</th></tr>");

        JSONArray branches = data.optJSONArray("branches_performance");
        if (branches != null) {
            for (int i = 0; i < branches.length(); i++) {
                JSONObject b = branches.optJSONObject(i);
                if (b == null) continue;
                html.append("<tr>")
                    .append("<td><b>").append(b.optString("branch_name", "—")).append("</b></td>")
                    .append("<td>").append(b.optInt("items_count", 0)).append("</td>")
                    .append("<td>").append(String.format(Locale.US, "%.2fg", b.optDouble("inventory_weight", 0))).append("</td>")
                    .append("<td class='gold-highlight'>").append(String.format(Locale.US, "%,.0f YER", b.optDouble("sales_total", 0))).append("</td>")
                    .append("<td>").append(String.format(Locale.US, "%.1f%%", b.optDouble("target_pct", 0))).append("</td>")
                    .append("</tr>");
            }
        }
        html.append("</table></div>");
    }

    private void renderGatesHTML(StringBuilder html, JSONObject data) {
        html.append("<div class='card'><div class='card-title'>🚪 أحداث بوابات RFID والرقابة الأمنية</div><table>")
            .append("<tr><th>التاريخ والوقت</th><th>البوابة</th><th>كود القطعة (EPC)</th><th>البيان / المنتج</th><th>نوع الإشارة</th></tr>");

        JSONArray logs = data.optJSONArray("gate_logs");
        if (logs != null) {
            for (int i = 0; i < logs.length(); i++) {
                JSONObject log = logs.optJSONObject(i);
                if (log == null) continue;
                boolean isAlarm = log.optBoolean("is_alarm", false);

                html.append("<tr>")
                    .append("<td>").append(log.optString("timestamp", "—")).append("</td>")
                    .append("<td>").append(log.optString("gate_name", "—")).append("</td>")
                    .append("<td><code>").append(log.optString("epc", "—")).append("</code></td>")
                    .append("<td>").append(log.optString("item_title", "غير معرف")).append("</td>")
                    .append("<td><span class='badge ").append(isAlarm ? "badge-danger" : "badge-success").append("'>")
                    .append(isAlarm ? "🚨 إنذار مرور" : "✓ مرور مصرح").append("</span></td>")
                    .append("</tr>");
            }
        }
        html.append("</table></div>");
    }

    private void renderAuditHTML(StringBuilder html, JSONObject data) {
        JSONObject audit = data.optJSONObject("audit_summary");
        if (audit == null) {
            html.append("<div class='card'>لا توجد نتائج جرد مسجلة</div>");
            return;
        }

        html.append("<div class='card'><div class='card-title'>📋 مطابقة الجرد الذكي (RFID Audit)</div><div class='grid'>")
            .append(metricBox("🎯", String.format(Locale.US, "%.1f%%", audit.optDouble("accuracy", 0)), "دقة المطابقة", ""))
            .append(metricBox("✅", String.valueOf(audit.optInt("found_items", 0)), "القطع السليمة", ""))
            .append(metricBox("❌", String.valueOf(audit.optInt("missing_items", 0)), "قطع مفقودة", "badge-danger"))
            .append(metricBox("⚠️", String.valueOf(audit.optInt("excess_items", 0)), "قطع فائضة", "badge-warning"))
            .append("</div></div>");
    }

    private void renderReorderHTML(StringBuilder html, JSONObject data) {
        html.append("<div class='card'><div class='card-title'>💎 نواقص المخزون والقطع الأكثر طلباً</div><table>")
            .append("<tr><th>الصنف / النوع</th><th>العيار</th><th>الكمية المباعة</th><th>الرصيد المتبقي</th><th>حالة الطلب</th></tr>");

        JSONArray items = data.optJSONArray("reorder_items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                int remaining = item.optInt("remaining_stock", 0);

                html.append("<tr>")
                    .append("<td><b>").append(item.optString("category", "—")).append("</b></td>")
                    .append("<td>").append(item.optString("karat", "—")).append("</td>")
                    .append("<td>").append(item.optInt("sold_count", 0)).append("</td>")
                    .append("<td>").append(remaining).append("</td>")
                    .append("<td><span class='badge ").append(remaining == 0 ? "badge-danger" : "badge-warning").append("'>")
                    .append(remaining == 0 ? "نفذ بالكامل" : "منخفض جداً").append("</span></td>")
                    .append("</tr>");
            }
        }
        html.append("</table></div>");
    }

    private String metricBox(String icon, String val, String lbl, String customClass) {
        return "<div class='metric'><div style='font-size:16px'>" + icon + "</div>" +
                "<div class='val " + customClass + "'>" + val + "</div>" +
                "<div class='lbl'>" + lbl + "</div></div>";
    }

    private String buildLoadingHTML() {
        return "<html dir='rtl'><body style='display:flex;justify-content:center;align-items:center;height:100vh;font-family:sans-serif;background:#F8FAFC;color:#0F172A;'>" +
                "<div style='text-align:center;'><h2>⏳ جاري معالجة بيانات الذهب والمخزون...</h2><p style='color:#64748B;'>يتم حساب الأوزان والعيارات بدقة عالية</p></div></body></html>";
    }

    private String buildEmptyOrErrorHTML(String type) {
        return "<html dir='rtl'><body style='display:flex;justify-content:center;align-items:center;height:100vh;font-family:sans-serif;background:#F8FAFC;color:#64748B;'>" +
                "<div style='text-align:center;'><h2>📭 لا توجد بيانات متاحة لهذا التقرير</h2><p>تحقق من الاتصال بالشبكة أو اختر فترة زمنية أخرى</p></div></body></html>";
    }

    // ==================== PRINT & EXPORT UTILS ====================

    private void printReportWebView(WebView webView, String jobTitle) {
        if (getContext() == null) return;
        PrintManager printManager = (PrintManager) requireContext().getSystemService(Context.PRINT_SERVICE);
        if (printManager != null) {
            PrintDocumentAdapter printAdapter = webView.createPrintDocumentAdapter(jobTitle);
            printManager.print(jobTitle, printAdapter, new PrintAttributes.Builder().build());
        }
    }

    private void exportReportToCSV(String reportType, String fromDate, String toDate) {
        try {
            File dir = new File(requireContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "MiraGoldReports");
            if (!dir.exists()) dir.mkdirs();

            String fileName = "MIRA_" + reportType.toUpperCase() + "_" + System.currentTimeMillis() + ".csv";
            File file = new File(dir, fileName);

            FileOutputStream fos = new FileOutputStream(file);
            // كتابة BOM لدعم اللغة العربية في Excel
            fos.write(0xEF); fos.write(0xBB); fos.write(0xBF);
            OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8);

            writer.write("تقرير منظومة ميرا للمجوهرات والـ RFID\n");
            writer.write("نوع التقرير," + reportType + "\n");
            writer.write("الفترة من," + fromDate + ",إلى," + toDate + "\n");
            writer.write("تاريخ التصدير," + getFormattedCurrentTime() + "\n\n");

            // جلب البيانات المخزنة للتصدير
            String cacheKey = "cache_" + reportType + "_" + fromDate + "_" + toDate;
            String cachedJson = offlineCache.getString(cacheKey, "{}");
            JSONObject data = new JSONObject(cachedJson);

            if (reportType.equals("sales")) {
                writer.write("رقم الفاتورة,العميل,الوزن المباع,الأجور,الإجمالي,طريقة الدفع\n");
                JSONObject sales = data.optJSONObject("sales");
                if (sales != null) {
                    JSONArray list = sales.optJSONArray("invoices");
                    if (list != null) {
                        for (int i = 0; i < list.length(); i++) {
                            JSONObject inv = list.getJSONObject(i);
                            writer.write(inv.optString("invoice_no") + "," +
                                    inv.optString("customer_name") + "," +
                                    inv.optDouble("weight") + "," +
                                    inv.optDouble("making_fee") + "," +
                                    inv.optDouble("total") + "," +
                                    inv.optString("payment_method") + "\n");
                        }
                    }
                }
            } else {
                writer.write("بيان,القيمة\n");
                writer.write("الحالة,تم التصدير بنجاح\n");
            }

            writer.flush();
            writer.close();

            Uri uri = FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/csv");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "تصدير التقرير عبر"));

        } catch (Exception e) {
            Toast.makeText(getContext(), "فشل تصدير التقرير: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void exportExecutiveSummary() {
        Toast.makeText(getContext(), "جاري تصدير الملخص التنفيذي الشامل...", Toast.LENGTH_SHORT).show();
        exportReportToCSV("dashboard", getDefaultFromDate(), getDefaultToDate());
    }

    // ==================== HELPER METHODS ====================

    private String getDefaultFromDate() {
        return getCalculatedDate(-30);
    }

    private String getDefaultToDate() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private String getCalculatedDate(int daysOffset) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, daysOffset);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
    }

    private String getFormattedCurrentTime() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());
    }

    private void showDatePicker(DatePickerCallback callback) {
        Calendar cal = Calendar.getInstance();
        new DatePickerDialog(requireContext(),
                (v, y, m, d) -> callback.onDate(String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)),
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    // ==================== ADAPTER & RECYCLERVIEW ====================

    static class ReportItem {
        String icon, title, subtitle, type;
        ReportItem(String icon, String title, String subtitle, String type) {
            this.icon = icon; this.title = title; this.subtitle = subtitle; this.type = type;
        }
    }

    class ReportsAdapter extends RecyclerView.Adapter<ReportsAdapter.VH> {
        final List<ReportItem> list;
        final OnReportClickListener listener;

        ReportsAdapter(List<ReportItem> list, OnReportClickListener listener) {
            this.list = list; this.listener = listener;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_report_card_pro, parent, false);
            return new VH(v);
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
            VH(View v) {
                super(v);
                tvIcon = v.findViewById(R.id.tvReportIcon);
                tvTitle = v.findViewById(R.id.tvReportTitle);
                tvSubtitle = v.findViewById(R.id.tvReportSubtitle);
            }
        }
    }

    interface OnReportClickListener { void onClick(ReportItem item); }
    interface DatePickerCallback { void onDate(String date); }
}
