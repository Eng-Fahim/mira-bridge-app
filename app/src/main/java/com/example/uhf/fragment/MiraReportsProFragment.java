package com.example.uhf.fragment;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
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
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
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

public class MiraReportsProFragment extends Fragment {

    private RecyclerView rvReports;
    private ReportsAdapter adapter;
    private final List<ReportItem> reportList = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private MiraOrgProfileManager orgProfile;
    private MiraApiClient apiClient;
    private String lastReportHTML = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_reports_pro, container, false);

        orgProfile = MiraOrgProfileManager.getInstance(requireContext());
        apiClient = MiraApiClient.getInstance(requireContext());

        rvReports = view.findViewById(R.id.rvReportsList);
        rvReports.setLayoutManager(new LinearLayoutManager(getContext()));

        // بناء قائمة التقارير
        buildReportsList();

        adapter = new ReportsAdapter(reportList, this::openReport);
        rvReports.setAdapter(adapter);

        return view;
    }

    private void buildReportsList() {
        reportList.clear();
        reportList.add(new ReportItem("📊", "لوحة المعلومات", "ملخص شامل للنظام", "dashboard"));
        reportList.add(new ReportItem("💰", "المبيعات والأرباح", "تقرير يومي، شهري، سنوي", "sales"));
        reportList.add(new ReportItem("📦", "المخزون التفصيلي", "حسب العيار، الفرع، الفئة", "inventory"));
        reportList.add(new ReportItem("🔄", "حركة المخزون", "دخول، خروج، تحويلات", "movement"));
        reportList.add(new ReportItem("🏢", "أداء الفروع", "مقارنة شاملة", "branches"));
        reportList.add(new ReportItem("👥", "المستخدمين", "نشاط وصلاحيات", "users"));
        reportList.add(new ReportItem("🚪", "البوابات الأمنية", "إنذارات وعبور", "gates"));
        reportList.add(new ReportItem("📋", "الجرد والتدقيق", "جلسات وفروقات", "audit"));
        reportList.add(new ReportItem("💎", "القطع الأعلى قيمة", "Top 50 قطعة", "highvalue"));
        reportList.add(new ReportItem("📈", "اتجاهات السوق", "رسوم بيانية", "trends"));
    }

    private void openReport(ReportItem item) {
        // عرض التقرير في Dialog مع WebView
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(requireContext());
        
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(8, 8, 8, 8);

        // Title
        TextView tvTitle = new TextView(getContext());
        tvTitle.setText(item.icon + " " + item.title);
        tvTitle.setTextSize(18);
        tvTitle.setPadding(16, 12, 16, 12);
        tvTitle.setTextColor(0xFF1A237E);
        layout.addView(tvTitle);

        // Filters
        LinearLayout filters = new LinearLayout(getContext());
        filters.setOrientation(LinearLayout.HORIZONTAL);
        filters.setPadding(8, 4, 8, 4);

        Button btnFrom = new Button(getContext());
        btnFrom.setText("من تاريخ");
        btnFrom.setTextSize(11);
        Button btnTo = new Button(getContext());
        btnTo.setText("إلى تاريخ");
        btnTo.setTextSize(11);
        Button btnApply = new Button(getContext());
        btnApply.setText("تطبيق");
        btnApply.setTextSize(11);

        filters.addView(btnFrom);
        filters.addView(btnTo);
        filters.addView(btnApply);
        layout.addView(filters);

        // WebView
        WebView webView = new WebView(getContext());
        webView.getSettings().setJavaScriptEnabled(true);
        webView.setWebViewClient(new WebViewClient());
        LinearLayout.LayoutParams webParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 500);
        webView.setLayoutParams(webParams);
        layout.addView(webView);

        // Buttons
        LinearLayout actions = new LinearLayout(getContext());
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(8, 8, 8, 8);

        Button btnPrint = new Button(getContext());
        btnPrint.setText("🖨️ طباعة");
        Button btnExport = new Button(getContext());
        btnExport.setText("📤 تصدير Excel");
        Button btnClose = new Button(getContext());
        btnClose.setText("إغلاق");

        actions.addView(btnPrint);
        actions.addView(btnExport);
        actions.addView(btnClose);
        layout.addView(actions);

        builder.setView(layout);
        android.app.AlertDialog dialog = builder.create();

        // Load report
        loadReportData(item.type, webView);

        // Listeners
        String[] dates = {getDefaultFrom(), getDefaultTo()};
        
        btnFrom.setOnClickListener(v -> showDatePicker(date -> { dates[0] = date; btnFrom.setText(date); }));
        btnTo.setOnClickListener(v -> showDatePicker(date -> { dates[1] = date; btnTo.setText(date); }));
        btnApply.setOnClickListener(v -> loadReportData(item.type, webView, dates[0], dates[1]));
        btnClose.setOnClickListener(v -> dialog.dismiss());
        btnPrint.setOnClickListener(v -> webView.loadUrl("javascript:window.print();"));
        btnExport.setOnClickListener(v -> exportToExcel(item.type, dates[0], dates[1]));

        dialog.show();
    }

    private void loadReportData(String type, WebView webView) {
        loadReportData(type, webView, getDefaultFrom(), getDefaultTo());
    }

    private void loadReportData(String type, WebView webView, String from, String to) {
        webView.loadDataWithBaseURL(null, buildLoadingHTML(), "text/html", "UTF-8", null);

        new Thread(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getDashboardFull();
                
                if (response != null && response.isSuccess && response.data != null) {
                    String html = buildReportHTML(type, response.data, from, to);
                    lastReportHTML = html;
                    mainHandler.post(() -> webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null));
                } else {
                    String html = buildEmptyHTML(type);
                    mainHandler.post(() -> webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null));
                }
            } catch (Exception e) {
                mainHandler.post(() -> webView.loadDataWithBaseURL(null, buildEmptyHTML(type), "text/html", "UTF-8", null));
            }
        }).start();
    }

    private String buildReportHTML(String type, JSONObject data, String from, String to) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html dir='rtl'><head><meta charset='UTF-8'>")
            .append("<script src='https://cdn.jsdelivr.net/npm/chart.js'></script>")
            .append("<style>")
            .append("body{font-family:'Segoe UI',sans-serif;padding:14px;background:#f5f5f5;color:#333}")
            .append(".card{background:#fff;padding:14px;border-radius:10px;margin-bottom:10px;box-shadow:0 1px 3px rgba(0,0,0,.1)}")
            .append(".card h3{color:#1A237E;margin:0 0 8px;font-size:16px}")
            .append(".row{display:flex;justify-content:space-between;padding:5px 0;border-bottom:1px solid #eee;font-size:13px}")
            .append(".val{font-weight:bold;color:#1A237E}")
            .append("table{width:100%;border-collapse:collapse;margin-top:10px;background:#fff;border-radius:8px;overflow:hidden}")
            .append("th{background:#1A237E;color:#fff;padding:8px;font-size:12px}")
            .append("td{padding:7px 8px;border-bottom:1px solid #eee;font-size:12px;text-align:center}")
            .append(".chart-box{background:#fff;padding:12px;border-radius:10px;margin-top:10px;height:220px}")
            .append("</style></head><body>");

        // Org header
        html.append("<div class='card' style='text-align:center'>")
            .append("<h2>").append(orgProfile.getOrgName()).append("</h2>")
            .append("<p style='color:#666;font-size:11px'>").append(orgProfile.getOrgSlogan()).append("</p>")
            .append("<p style='color:#888;font-size:10px'>").append(from).append(" - ").append(to).append("</p>")
            .append("</div>");

        // Report content based on type
        switch (type) {
            case "dashboard":
                html.append(buildDashboardReport(data));
                break;
            case "sales":
                html.append(buildSalesReport(data));
                break;
            case "inventory":
                html.append(buildInventoryReport(data));
                break;
            case "gates":
                html.append(buildGatesReport(data));
                break;
            case "branches":
                html.append(buildBranchesReport(data));
                break;
            default:
                html.append(buildDashboardReport(data));
        }

        html.append("<div style='text-align:center;padding:16px;color:#888;font-size:9px'>")
            .append(orgProfile.getOrgName()).append(" | MIRA Reports | © ").append(new SimpleDateFormat("yyyy").format(new Date()))
            .append("</div>");
        html.append("</body></html>");

        return html.toString();
    }

    private String buildDashboardReport(JSONObject data) {
        JSONObject inv = data.optJSONObject("inventory");
        JSONObject sales = data.optJSONObject("sales");
        JSONObject gates = data.optJSONObject("gates");
        JSONObject audit = data.optJSONObject("last_audit");

        StringBuilder h = new StringBuilder();
        
        // Summary Cards
        h.append("<div style='display:flex;gap:8px;flex-wrap:wrap'>");
        h.append(summaryCard("📦", inv != null ? String.valueOf(inv.optInt("total_items")) : "0", "إجمالي القطع"));
        h.append(summaryCard("⚖️", inv != null ? String.format("%.1f kg", inv.optDouble("total_weight")) : "0", "الوزن"));
        h.append(summaryCard("💰", sales != null ? sales.optString("today", "$0") : "$0", "مبيعات اليوم"));
        h.append(summaryCard("🚪", gates != null ? String.valueOf(gates.optInt("active")) : "0", "بوابات نشطة"));
        h.append("</div>");

        // Weights Table
        if (inv != null) {
            JSONObject w = inv.optJSONObject("weights");
            if (w != null) {
                h.append("<table><tr><th>العيار</th><th>الوزن</th></tr>");
                h.append(tableRow("24K", String.valueOf(w.optInt("k24"))));
                h.append(tableRow("22K", String.valueOf(w.optInt("k22"))));
                h.append(tableRow("21K", String.valueOf(w.optInt("k21"))));
                h.append(tableRow("18K", String.valueOf(w.optInt("k18"))));
                h.append("</table>");
            }
        }

        // Sales
        if (sales != null) {
            h.append("<div class='card'><h3>📈 المبيعات</h3>");
            h.append("<div class='row'><span>اليوم</span><span class='val'>").append(sales.optString("today")).append("</span></div>");
            h.append("<div class='row'><span>الشهر</span><span class='val'>").append(sales.optString("month")).append("</span></div>");
            h.append("<div class='row'><span>السنة</span><span class='val'>").append(sales.optString("year")).append("</span></div>");
            h.append("</div>");
        }

        // Chart
        if (inv != null) {
            JSONObject w = inv.optJSONObject("weights");
            if (w != null) {
                h.append("<div class='chart-box'><canvas id='weightChart'></canvas></div>");
                h.append("<script>new Chart(document.getElementById('weightChart'),{type:'doughnut',data:{labels:['24K','22K','21K','18K'],datasets:[{data:[")
                  .append(w.optInt("k24")).append(",").append(w.optInt("k22")).append(",")
                  .append(w.optInt("k21")).append(",").append(w.optInt("k18"))
                  .append("],backgroundColor:['#FFD700','#FFC107','#FFB300','#FFA000']}]}});</script>");
            }
        }

        return h.toString();
    }

    private String buildSalesReport(JSONObject data) {
        JSONObject sales = data.optJSONObject("sales");
        StringBuilder h = new StringBuilder();
        h.append("<div class='card'><h3>💰 تقرير المبيعات</h3>");
        if (sales != null) {
            h.append("<div class='row'><span>مبيعات اليوم</span><span class='val'>").append(sales.optString("today")).append("</span></div>");
            h.append("<div class='row'><span>مبيعات الشهر</span><span class='val'>").append(sales.optString("month")).append("</span></div>");
            h.append("<div class='row'><span>مبيعات السنة</span><span class='val'>").append(sales.optString("year")).append("</span></div>");
        }
        h.append("</div>");
        return h.toString();
    }

    private String buildInventoryReport(JSONObject data) {
        return buildDashboardReport(data);
    }

    private String buildGatesReport(JSONObject data) {
        JSONObject gates = data.optJSONObject("gates");
        StringBuilder h = new StringBuilder();
        h.append("<div class='card'><h3>🚪 البوابات الأمنية</h3>");
        if (gates != null) {
            h.append("<div class='row'><span>بوابات نشطة</span><span class='val'>").append(gates.optInt("active")).append("</span></div>");
            h.append("<div class='row'><span>بوابات متوقفة</span><span class='val'>").append(gates.optInt("offline")).append("</span></div>");
            h.append("<div class='row'><span>إنذارات اليوم</span><span class='val' style='color:#EF4444'>").append(gates.optInt("alarms_today")).append("</span></div>");
        }
        h.append("</div>");
        return h.toString();
    }

    private String buildBranchesReport(JSONObject data) {
        StringBuilder h = new StringBuilder();
        h.append("<div class='card'><h3>🏢 أداء الفروع</h3>");
        h.append("<p style='color:#666'>تحتاج API منفصل للفروع</p>");
        h.append("</div>");
        return h.toString();
    }

    private String summaryCard(String icon, String value, String label) {
        return "<div style='flex:1;min-width:80px;background:#fff;padding:10px;border-radius:8px;text-align:center'>"
            + "<div style='font-size:20px'>" + icon + "</div>"
            + "<div style='font-size:16px;font-weight:bold;color:#1A237E;margin:4px 0'>" + value + "</div>"
            + "<div style='font-size:10px;color:#888'>" + label + "</div></div>";
    }

    private String tableRow(String col1, String col2) {
        return "<tr><td>" + col1 + "</td><td>" + col2 + "</td></tr>";
    }

    private String buildLoadingHTML() {
        return "<html><body style='text-align:center;padding-top:60px;font-family:sans-serif;background:#f5f5f5'>"
            + "<h3 style='color:#1A237E'>⏳ جاري تحميل التقرير...</h3></body></html>";
    }

    private String buildEmptyHTML(String type) {
        return "<html><body style='text-align:center;padding-top:60px;font-family:sans-serif;background:#f5f5f5'>"
            + "<h3 style='color:#888'>📭 لا توجد بيانات متاحة لهذا التقرير</h3></body></html>";
    }

    private String getDefaultFrom() {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, -30);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
    }

    private String getDefaultTo() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private void showDatePicker(DatePickerCallback callback) {
        Calendar cal = Calendar.getInstance();
        new DatePickerDialog(requireContext(),
            (v, y, m, d) -> callback.onDate(String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)),
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void exportToExcel(String type, String from, String to) {
        try {
            StringBuilder csv = new StringBuilder();
            csv.append(orgProfile.getOrgName()).append("\n");
            csv.append("تقرير: ").append(type).append("\n");
            csv.append("من: ").append(from).append(" إلى: ").append(to).append("\n\n");

            File dir = new File(requireContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "MiraReports");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "MIRA_Report_" + type + "_" + System.currentTimeMillis() + ".csv");
            FileOutputStream out = new FileOutputStream(file);
            out.write(csv.toString().getBytes());
            out.close();

            Uri uri = FileProvider.getUriForFile(requireContext(),
                requireContext().getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/csv");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "مشاركة التقرير"));
        } catch (Exception e) {
            Toast.makeText(getContext(), "فشل التصدير", Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== Models ====================

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

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_report_card, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            ReportItem r = list.get(pos);
            h.tvIcon.setText(r.icon);
            h.tvTitle.setText(r.title);
            h.tvSubtitle.setText(r.subtitle);
            h.itemView.setOnClickListener(v -> listener.onClick(r));
        }

        @Override public int getItemCount() { return list.size(); }

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
