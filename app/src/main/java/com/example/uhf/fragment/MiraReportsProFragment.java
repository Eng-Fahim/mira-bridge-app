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
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
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
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
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

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_reports_pro, container, false);

        orgProfile = MiraOrgProfileManager.getInstance(requireContext());
        apiClient = MiraApiClient.getInstance(requireContext());

        rvReports = view.findViewById(R.id.rvReportsList);
        rvReports.setLayoutManager(new LinearLayoutManager(getContext()));

        buildReportsList();

        adapter = new ReportsAdapter(reportList, this::openReport);
        rvReports.setAdapter(adapter);

        return view;
    }

    private void buildReportsList() {
        reportList.clear();
        reportList.add(new ReportItem("📊", "لوحة المعلومات الملموسة", "ملخص حركة الذهب والنقد", "dashboard"));
        reportList.add(new ReportItem("💰", "المبيعات والأرباح", "حركة النقد وأرباح الأجور", "sales"));
        reportList.add(new ReportItem("📦", "المخزون التفصيلي", "تقرير الأوزان والعيارات والقطع", "inventory"));
        reportList.add(new ReportItem("🔄", "حركة التحويلات", "سجل التنقلات بين الفروع والخزائن", "movement"));
        reportList.add(new ReportItem("🏢", "أداء الفروع والمحفظة", "مقارنة المبيعات والأرصدة", "branches"));
        reportList.add(new ReportItem("🚪", "إنذارات بوابات RFID", "سجل الاكتشاف والعبور غير المصرح", "gates"));
        reportList.add(new ReportItem("📋", "نتائج الجرد الذكي", "عجز وفائض القطع المسجلة", "audit"));
    }

    private void openReport(ReportItem item) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(requireContext());
        
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(16, 16, 16, 16);

        TextView tvTitle = new TextView(getContext());
        tvTitle.setText(item.icon + " " + item.title);
        tvTitle.setTextSize(18);
        tvTitle.setPadding(0, 0, 0, 16);
        tvTitle.setTextColor(0xFF1A237E);
        layout.addView(tvTitle);

        LinearLayout filters = new LinearLayout(getContext());
        filters.setOrientation(LinearLayout.HORIZONTAL);

        Button btnFrom = new Button(getContext());
        btnFrom.setText("من: " + getDefaultFrom());
        btnFrom.setTextSize(11);
        
        Button btnTo = new Button(getContext());
        btnTo.setText("إلى: " + getDefaultTo());
        btnTo.setTextSize(11);

        Button btnApply = new Button(getContext());
        btnApply.setText("تطبيق");
        btnApply.setTextSize(11);

        filters.addView(btnFrom);
        filters.addView(btnTo);
        filters.addView(btnApply);
        layout.addView(filters);

        WebView webView = new WebView(getContext());
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient());
        LinearLayout.LayoutParams webParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 800);
        webView.setLayoutParams(webParams);
        layout.addView(webView);

        LinearLayout actions = new LinearLayout(getContext());
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, 16, 0, 0);

        Button btnPrint = new Button(getContext());
        btnPrint.setText("🖨️ طباعة");
        Button btnExport = new Button(getContext());
        btnExport.setText("📤 تصدير CSV");
        Button btnClose = new Button(getContext());
        btnClose.setText("إغلاق");

        actions.addView(btnPrint);
        actions.addView(btnExport);
        actions.addView(btnClose);
        layout.addView(actions);

        builder.setView(layout);
        android.app.AlertDialog dialog = builder.create();

        final String[] dates = {getDefaultFrom(), getDefaultTo()};

        btnFrom.setOnClickListener(v -> showDatePicker(date -> { dates[0] = date; btnFrom.setText("من: " + date); }));
        btnTo.setOnClickListener(v -> showDatePicker(date -> { dates[1] = date; btnTo.setText("إلى: " + date); }));
        btnApply.setOnClickListener(v -> loadReportData(item.type, webView, dates[0], dates[1]));
        btnClose.setOnClickListener(v -> dialog.dismiss());
        btnPrint.setOnClickListener(v -> printWebViewDocument(webView, item.title));
        btnExport.setOnClickListener(v -> exportToExcel(item.type, dates[0], dates[1]));

        loadReportData(item.type, webView, dates[0], dates[1]);
        dialog.show();
    }

    private void loadReportData(String type, WebView webView, String from, String to) {
        webView.loadDataWithBaseURL(null, buildLoadingHTML(), "text/html", "UTF-8", null);

        new Thread(() -> {
            try {
                MiraApiClient.ApiResponse response = apiClient.getDashboardFull();
                
                if (response != null && response.isSuccess && response.data != null) {
                    String html = buildReportHTML(type, response.data, from, to);
                    mainHandler.post(() -> webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null));
                } else {
                    mainHandler.post(() -> webView.loadDataWithBaseURL(null, buildEmptyHTML(type), "text/html", "UTF-8", null));
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
            .append("body{font-family:'Segoe UI',Tahoma,sans-serif;padding:12px;background:#f8f9fa;color:#212529}")
            .append(".card{background:#fff;padding:12px;border-radius:8px;margin-bottom:12px;box-shadow:0 1px 3px rgba(0,0,0,.1)}")
            .append(".grid{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:10px}")
            .append("table{width:100%;border-collapse:collapse;margin-top:8px;background:#fff}")
            .append("th{background:#1A237E;color:#fff;padding:8px;font-size:11px}")
            .append("td{padding:6px 8px;border-bottom:1px solid #dee2e6;font-size:11px;text-align:center}")
            .append("</style></head><body>");

        html.append("<div class='card' style='text-align:center'>")
            .append("<h2 style='margin:0;color:#1A237E;'>").append(orgProfile.getOrgName()).append("</h2>")
            .append("<div style='color:#6c757d;font-size:11px'>").append(orgProfile.getOrgSlogan()).append("</div>")
            .append("<div style='color:#495057;font-size:10px;margin-top:4px'>فترة التقرير: ").append(from).append(" إلى ").append(to).append("</div>")
            .append("</div>");

        switch (type) {
            case "dashboard":
            case "inventory":
                html.append(buildInventoryReport(data));
                break;
            case "sales":
                html.append(buildSalesReport(data));
                break;
            case "gates":
                html.append(buildGatesReport(data));
                break;
            default:
                html.append(buildInventoryReport(data));
        }

        html.append("</body></html>");
        return html.toString();
    }

    private String buildInventoryReport(JSONObject data) {
        JSONObject inv = data.optJSONObject("inventory");
        StringBuilder h = new StringBuilder();
        
        if (inv != null) {
            h.append("<div class='grid'>")
             .append(summaryCard("📦", String.valueOf(inv.optInt("total_items")), "عدد القطع"))
             .append(summaryCard("⚖️", String.format(Locale.US, "%.2f g", inv.optDouble("total_weight")), "إجمالي الوزن"))
             .append("</div>");

            JSONObject w = inv.optJSONObject("weights");
            if (w != null) {
                h.append("<table><tr><th>العيار</th><th>الوزن الإجمالي (جرام)</th></tr>");
                h.append(tableRow("عيار 24", String.valueOf(w.optDouble("k24", 0))));
                h.append(tableRow("عيار 22", String.valueOf(w.optDouble("k22", 0))));
                h.append(tableRow("عيار 21", String.valueOf(w.optDouble("k21", 0))));
                h.append(tableRow("عيار 18", String.valueOf(w.optDouble("k18", 0))));
                h.append("</table>");
            }
        }
        return h.toString();
    }

    private String buildSalesReport(JSONObject data) {
        JSONObject sales = data.optJSONObject("sales");
        StringBuilder h = new StringBuilder();
        h.append("<div class='card'><h3 style='color:#1A237E;margin:0 0 8px;'>💰 المبيعات والأجور</h3>");
        if (sales != null) {
            h.append("<div>مبيعات اليوم: <b>").append(sales.optString("today", "$0")).append("</b></div>");
            h.append("<div>مبيعات الشهر: <b>").append(sales.optString("month", "$0")).append("</b></div>");
        }
        h.append("</div>");
        return h.toString();
    }

    private String buildGatesReport(JSONObject data) {
        JSONObject gates = data.optJSONObject("gates");
        StringBuilder h = new StringBuilder();
        h.append("<div class='card'><h3 style='color:#1A237E;margin:0 0 8px;'>🚪 نشاط بوابات RFID</h3>");
        if (gates != null) {
            h.append("<div>بوابات نشطة: <b>").append(gates.optInt("active", 0)).append("</b></div>");
            h.append("<div>حوادث الإنذار اليوم: <b style='color:red'>").append(gates.optInt("alarms_today", 0)).append("</b></div>");
        }
        h.append("</div>");
        return h.toString();
    }

    private String summaryCard(String icon, String value, String label) {
        return "<div style='flex:1;background:#fff;padding:8px;border-radius:6px;text-align:center;box-shadow:0 1px 2px rgba(0,0,0,.05)'>"
            + "<div style='font-size:18px'>" + icon + "</div>"
            + "<div style='font-size:14px;font-weight:bold;color:#1A237E'>" + value + "</div>"
            + "<div style='font-size:10px;color:#6c757d'>" + label + "</div></div>";
    }

    private String tableRow(String col1, String col2) {
        return "<tr><td>" + col1 + "</td><td>" + col2 + "</td></tr>";
    }

    private String buildLoadingHTML() {
        return "<html dir='rtl'><body style='text-align:center;padding-top:40px;font-family:sans-serif;color:#1A237E'>"
            + "<h3>⏳ جاري جلب واستخلاص البيانات...</h3></body></html>";
    }

    private String buildEmptyHTML(String type) {
        return "<html dir='rtl'><body style='text-align:center;padding-top:40px;font-family:sans-serif;color:#6c757d'>"
            + "<h3>📭 لا توجد سجلات مطابقة للفترة المحددة</h3></body></html>";
    }

    private void printWebViewDocument(WebView webView, String jobName) {
        PrintManager printManager = (PrintManager) requireContext().getSystemService(Context.PRINT_SERVICE);
        if (printManager != null) {
            PrintDocumentAdapter printAdapter = webView.createPrintDocumentAdapter(jobName);
            printManager.print(jobName, printAdapter, new PrintAttributes.Builder().build());
        }
    }

    private void exportToExcel(String type, String from, String to) {
        try {
            File dir = new File(requireContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "MiraReports");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "MIRA_Report_" + type + "_" + System.currentTimeMillis() + ".csv");

            FileOutputStream fos = new FileOutputStream(file);
            // كتابة UTF-8 BOM لفتح الملف بترميز عربي صحيح في Excel
            fos.write(0xEF);
            fos.write(0xBB);
            fos.write(0xBF);

            OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            writer.write("اسم المنشأة," + orgProfile.getOrgName() + "\n");
            writer.write("نوع التقرير," + type + "\n");
            writer.write("من تاريخ," + from + ",إلى تاريخ," + to + "\n\n");
            
            writer.write("البند,القيمة\n");
            writer.write("حالة التقرير,مكتمل\n");
            
            writer.flush();
            writer.close();

            Uri uri = FileProvider.getUriForFile(requireContext(),
                requireContext().getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/csv");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "مشاركة التقرير عبر"));
        } catch (Exception e) {
            Toast.makeText(getContext(), "فشل تصدير الملف", Toast.LENGTH_SHORT).show();
        }
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

    // ==================== Sub Classes & Interfaces ====================

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
