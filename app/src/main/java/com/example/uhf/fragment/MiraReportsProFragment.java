package com.example.uhf.fragment;

import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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

public class MiraReportsProFragment extends Fragment {

    private static final String OFFLINE_PREFS = "reports_cache";
    private static final String TAG = "MiraReportsPro";

    private RecyclerView rvReports;
    private ReportsAdapter adapter;
    private final List<ReportItem> reportList = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private MiraOrgProfileManager orgProfile;
    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private SharedPreferences offlineCache;
    private JSONObject cachedData;
    private long lastCacheTime = 0;
    private static final long CACHE_DURATION = 30 * 60 * 1000; // 30 دقيقة

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_reports_pro, container, false);

        if (getContext() != null) {
            orgProfile = MiraOrgProfileManager.getInstance(getContext());
            apiClient = MiraApiClient.getInstance(getContext());
            branchesManager = MiraBranchesManager.getInstance(getContext());
            offlineCache = getContext().getSharedPreferences(OFFLINE_PREFS, Context.MODE_PRIVATE);
        }

        rvReports = view.findViewById(R.id.rvReportsList);
        rvReports.setLayoutManager(new LinearLayoutManager(getContext()));

        buildReportsList();
        adapter = new ReportsAdapter(reportList, this::openReport);
        rvReports.setAdapter(adapter);

        // تحميل البيانات المخزنة
        loadCachedData();

        return view;
    }

    private void buildReportsList() {
        reportList.clear();
        reportList.add(new ReportItem("📊", "لوحة المعلومات", "ملخص حركة الذهب والنقد والمخزون", "dashboard"));
        reportList.add(new ReportItem("💰", "المبيعات والأرباح", "حركة النقد وأرباح الأجور اليومية", "sales"));
        reportList.add(new ReportItem("📦", "المخزون التفصيلي", "تقرير الأوزان والعيارات والقطع", "inventory"));
        reportList.add(new ReportItem("🔄", "حركة التحويلات", "سجل التنقلات بين الفروع والخزائن", "movement"));
        reportList.add(new ReportItem("🏢", "أداء الفروع", "مقارنة المبيعات والأرصدة بين الفروع", "branches"));
        reportList.add(new ReportItem("🚪", "إنذارات البوابات", "سجل الاكتشاف والعبور غير المصرح", "gates"));
        reportList.add(new ReportItem("📋", "نتائج الجرد", "عجز وفائض القطع المسجلة", "audit"));
    }

    // ==================== CACHE ====================

    private void loadCachedData() {
        try {
            String json = offlineCache.getString("dashboard_data", "");
            long time = offlineCache.getLong("cache_time", 0);
            if (!json.isEmpty() && (System.currentTimeMillis() - time) < CACHE_DURATION) {
                cachedData = new JSONObject(json);
                lastCacheTime = time;
            }
        } catch (Exception ignored) {}
    }

    private void saveCachedData(JSONObject data) {
        try {
            offlineCache.edit()
                .putString("dashboard_data", data.toString())
                .putLong("cache_time", System.currentTimeMillis())
                .apply();
            cachedData = data;
            lastCacheTime = System.currentTimeMillis();
        } catch (Exception ignored) {}
    }

    // ==================== OPEN REPORT ====================

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

        // أزرار سريعة للفترات الزمنية
        LinearLayout quickDates = new LinearLayout(getContext());
        quickDates.setOrientation(LinearLayout.HORIZONTAL);
        
        String[] quickLabels = {"اليوم", "أسبوع", "شهر", "سنة"};
        String[] quickValues = {"today", "week", "month", "year"};
        
        for (int i = 0; i < quickLabels.length; i++) {
            Button btn = new Button(getContext());
            btn.setText(quickLabels[i]);
            btn.setTextSize(10);
            btn.setPadding(8, 4, 8, 4);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            params.setMargins(2, 0, 2, 0);
            btn.setLayoutParams(params);
            final String period = quickValues[i];
            btn.setOnClickListener(v -> {
                String[] dates = getDateRange(period);
                // سيتم استخدامها في WebView
            });
            quickDates.addView(btn);
        }
        layout.addView(quickDates);

        // فلاتر التاريخ
        LinearLayout filters = new LinearLayout(getContext());
        filters.setOrientation(LinearLayout.HORIZONTAL);
        filters.setPadding(0, 12, 0, 8);

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
        btnExport.setOnClickListener(v -> exportToCSV(item.type, dates[0], dates[1]));

        // عرض فوري من الكاش إذا موجود
        if (cachedData != null) {
            String html = buildReportHTML(item.type, cachedData, dates[0], dates[1]);
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
        } else {
            webView.loadDataWithBaseURL(null, buildLoadingHTML(), "text/html", "UTF-8", null);
        }

        loadReportData(item.type, webView, dates[0], dates[1]);
        dialog.show();
    }

    private void loadReportData(String type, WebView webView, String from, String to) {
        webView.loadDataWithBaseURL(null, buildLoadingHTML(), "text/html", "UTF-8", null);

        new Thread(() -> {
            try {
                // محاولة API
                MiraApiClient.ApiResponse response = null;
                try {
                    response = apiClient.getDashboardFull(
                        branchesManager != null ? branchesManager.getActiveBranchId() : -1);
                } catch (Exception apiError) {
                    Log.e(TAG, "API error: " + apiError.getMessage());
                }

                JSONObject data = null;
                
                if (response != null && response.isSuccess && response.data != null) {
                    data = response.data;
                    saveCachedData(data);
                } else if (cachedData != null) {
                    data = cachedData;
                }

                final JSONObject finalData = data;
                mainHandler.post(() -> {
                    if (finalData != null) {
                        String html = buildReportHTML(type, finalData, from, to);
                        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
                    } else {
                        webView.loadDataWithBaseURL(null, buildEmptyHTML(type), "text/html", "UTF-8", null);
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> webView.loadDataWithBaseURL(null, buildEmptyHTML(type), "text/html", "UTF-8", null));
            }
        }).start();
    }

    // ==================== HTML REPORTS ====================

    private String buildReportHTML(String type, JSONObject data, String from, String to) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html dir='rtl'><head><meta charset='UTF-8'>")
            .append("<style>")
            .append("body{font-family:sans-serif;padding:12px;background:#f8f9fa;color:#212529}")
            .append(".card{background:#fff;padding:12px;border-radius:8px;margin-bottom:12px;box-shadow:0 1px 3px rgba(0,0,0,.1)}")
            .append(".grid{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:10px}")
            .append(".metric{flex:1;min-width:80px;text-align:center;padding:10px;background:#fff;border-radius:6px}")
            .append(".metric .val{font-size:20px;font-weight:bold;color:#1A237E}")
            .append(".metric .lbl{font-size:10px;color:#6c757d}")
            .append("table{width:100%;border-collapse:collapse;margin-top:8px;background:#fff}")
            .append("th{background:#1A237E;color:#fff;padding:8px;font-size:11px}")
            .append("td{padding:6px 8px;border-bottom:1px solid #dee2e6;font-size:11px;text-align:center}")
            .append(".highlight{color:#D4AF37;font-weight:bold}")
            .append("</style></head><body>");

        // Header
        html.append("<div class='card' style='text-align:center'>")
            .append("<h2 style='margin:0;color:#1A237E;'>").append(orgProfile != null ? orgProfile.getOrgName() : "MIRA Jewelry").append("</h2>")
            .append("<div style='color:#6c757d;font-size:10px'>").append(from).append(" - ").append(to).append("</div>")
            .append("</div>");

        JSONObject inv = data.optJSONObject("inventory");
        JSONObject sales = data.optJSONObject("sales");
        JSONObject gates = data.optJSONObject("gates");
        JSONObject audit = data.optJSONObject("last_audit");

        // Summary Metrics
        html.append("<div class='card'><h3 style='margin:0 0 10px;color:#1A237E'>📊 ملخص سريع</h3>")
            .append("<div class='grid'>");
        
        if (inv != null) {
            html.append(metricCard("📦", String.valueOf(inv.optInt("total_items", 0)), "القطع"))
                .append(metricCard("⚖️", String.format(Locale.US, "%.1fg", inv.optDouble("total_weight", 0)), "الوزن"));
        }
        if (sales != null) {
            html.append(metricCard("💰", sales.optString("today", "$0"), "مبيعات اليوم"))
                .append(metricCard("📈", sales.optString("month", "$0"), "مبيعات الشهر"));
        }
        if (gates != null) {
            html.append(metricCard("🚪", String.valueOf(gates.optInt("active", 0)), "بوابات نشطة"))
                .append(metricCard("🔔", String.valueOf(gates.optInt("alarms_today", 0)), "إنذارات"));
        }
        html.append("</div></div>");

        // Detailed based on type
        switch (type) {
            case "inventory":
            case "dashboard":
                html.append(buildInventoryDetail(inv));
                break;
            case "sales":
                html.append(buildSalesDetail(sales));
                break;
            case "gates":
                html.append(buildGatesDetail(gates));
                break;
            case "audit":
                html.append(buildAuditDetail(audit));
                break;
            default:
                html.append(buildInventoryDetail(inv));
        }

        html.append("<div style='text-align:center;margin-top:16px;font-size:9px;color:#999'>")
            .append(orgProfile != null ? orgProfile.getOrgName() : "MIRA Jewelry").append(" © 2026 | MIRA Reports Pro</div>");
        
        html.append("</body></html>");
        return html.toString();
    }

    private String metricCard(String icon, String value, String label) {
        return "<div class='metric'><div style='font-size:20px'>" + icon + "</div>" +
            "<div class='val'>" + value + "</div><div class='lbl'>" + label + "</div></div>";
    }

    private String buildInventoryDetail(JSONObject inv) {
        if (inv == null) return "<div class='card'><p>لا توجد بيانات مخزون</p></div>";
        StringBuilder h = new StringBuilder();
        h.append("<div class='card'><h3>📦 توزيع الأوزان حسب العيار</h3><table>")
         .append("<tr><th>العيار</th><th>الوزن (جرام)</th><th>النسبة</th></tr>");
        
        JSONObject w = inv.optJSONObject("weights");
        double total = inv.optDouble("total_weight", 1);
        if (w != null && total > 0) {
            String[] karats = {"24K", "22K", "21K", "18K"};
            String[] keys = {"k24", "k22", "k21", "k18"};
            for (int i = 0; i < karats.length; i++) {
                double wt = w.optDouble(keys[i], 0);
                double pct = (wt / total) * 100;
                h.append("<tr><td>").append(karats[i]).append("</td>")
                 .append("<td>").append(String.format(Locale.US, "%.2f", wt)).append("</td>")
                 .append("<td>").append(String.format(Locale.US, "%.1f%%", pct)).append("</td></tr>");
            }
        }
        h.append("</table></div>");
        return h.toString();
    }

    private String buildSalesDetail(JSONObject sales) {
        if (sales == null) return "<div class='card'><p>لا توجد بيانات مبيعات</p></div>";
        return "<div class='card'><h3>💰 تفاصيل المبيعات</h3>" +
            "<div>اليوم: <b>" + sales.optString("today", "$0") + "</b></div>" +
            "<div>الشهر: <b>" + sales.optString("month", "$0") + "</b></div>" +
            "<div>السنة: <b>" + sales.optString("year", "$0") + "</b></div>" +
            "<div>عدد القطع المباعة اليوم: <b>" + sales.optInt("count_today", 0) + "</b></div>" +
            "</div>";
    }

    private String buildGatesDetail(JSONObject gates) {
        if (gates == null) return "<div class='card'><p>لا توجد بيانات بوابات</p></div>";
        return "<div class='card'><h3>🚪 نشاط البوابات</h3>" +
            "<div>نشطة: <b>" + gates.optInt("active", 0) + "</b></div>" +
            "<div>غير متصلة: <b>" + gates.optInt("offline", 0) + "</b></div>" +
            "<div>إنذارات اليوم: <b style='color:red'>" + gates.optInt("alarms_today", 0) + "</b></div>" +
            "</div>";
    }

    private String buildAuditDetail(JSONObject audit) {
        if (audit == null) return "<div class='card'><p>لا توجد بيانات جرد</p></div>";
        return "<div class='card'><h3>📋 آخر جرد</h3>" +
            "<div>التاريخ: <b>" + audit.optString("date", "—") + "</b></div>" +
            "<div>الدقة: <b>" + String.format(Locale.US, "%.1f%%", audit.optDouble("accuracy", 0)) + "</b></div>" +
            "<div>المفقود: <b style='color:red'>" + audit.optInt("missing_items", 0) + " قطعة</b></div>" +
            "</div>";
    }

    private String buildLoadingHTML() {
        return "<html dir='rtl'><body style='text-align:center;padding-top:40px;font-family:sans-serif;color:#1A237E'>"
            + "<h3>⏳ جاري تحميل البيانات...</h3></body></html>";
    }

    private String buildEmptyHTML(String type) {
        return "<html dir='rtl'><body style='text-align:center;padding-top:40px;font-family:sans-serif;color:#6c757d'>"
            + "<h3>📭 لا توجد بيانات متاحة</h3><p>تأكد من الاتصال أو جرب لاحقاً</p></body></html>";
    }

    // ==================== HELPERS ====================

    private void printWebViewDocument(WebView webView, String jobName) {
        PrintManager printManager = (PrintManager) requireContext().getSystemService(Context.PRINT_SERVICE);
        if (printManager != null) {
            PrintDocumentAdapter printAdapter = webView.createPrintDocumentAdapter(jobName);
            printManager.print(jobName, printAdapter, new PrintAttributes.Builder().build());
        }
    }

    private void exportToCSV(String type, String from, String to) {
        try {
            File dir = new File(requireContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "MiraReports");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "MIRA_Report_" + type + "_" + System.currentTimeMillis() + ".csv");

            FileOutputStream fos = new FileOutputStream(file);
            fos.write(0xEF); fos.write(0xBB); fos.write(0xBF);
            OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            writer.write("تقرير," + type + "\n");
            writer.write("الفترة," + from + "," + to + "\n");
            writer.write("تاريخ التصدير," + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()) + "\n");
            writer.flush(); writer.close();

            Uri uri = FileProvider.getUriForFile(requireContext(),
                requireContext().getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/csv");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "مشاركة التقرير"));
        } catch (Exception e) {
            Toast.makeText(getContext(), "فشل تصدير الملف", Toast.LENGTH_SHORT).show();
        }
    }

    private String[] getDateRange(String period) {
        Calendar cal = Calendar.getInstance();
        String to = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
        switch (period) {
            case "today": return new String[]{to, to};
            case "week": cal.add(Calendar.DAY_OF_MONTH, -7); break;
            case "month": cal.add(Calendar.MONTH, -1); break;
            case "year": cal.add(Calendar.YEAR, -1); break;
        }
        return new String[]{new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime()), to};
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

    // ==================== CLASSES ====================

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
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_report_card, parent, false));
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
