package com.example.uhf.fragment;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.example.rfid.R;
import com.example.rfid.api.ApiClient;
import com.example.rfid.api.ApiService;
import com.example.rfid.utils.LocaleHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MiraReportsFragment extends Fragment {

    private static final String TAG = "MiraReportsFragment";

    private WebView webView;
    private Button btnInventoryReport;
    private Button btnDiscrepanciesReport;
    private Button btnAuditLogsReport;
    private Button btnDateFrom;
    private Button btnDateTo;
    private Button btnExportExcel;
    private TextView tvReportTitle;

    private String currentReportType = "inventory_detail";
    private String dateFrom = "";
    private String dateTo = "";
    private JSONObject lastReportData = null;
    private boolean isArabic = true;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_reports, container, false);

        isArabic = LocaleHelper.isArabic(requireContext());

        initViews(view);
        setupWebView();
        setupListeners();

        // تعيين التاريخ الافتراضي (آخر 30 يوماً)
        setDefaultDates();

        // تحميل التقرير الافتراضي
        loadReport(currentReportType);

        return view;
    }

    private void initViews(View view) {
        webView = view.findViewById(R.id.web_view_report);
        btnInventoryReport = view.findViewById(R.id.btn_report_inventory);
        btnDiscrepanciesReport = view.findViewById(R.id.btn_report_discrepancies);
        btnAuditLogsReport = view.findViewById(R.id.btn_report_audit);
        btnDateFrom = view.findViewById(R.id.btn_date_from);
        btnDateTo = view.findViewById(R.id.btn_date_to);
        btnExportExcel = view.findViewById(R.id.btn_export_excel);
        tvReportTitle = view.findViewById(R.id.tv_report_title);
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        webView.addJavascriptInterface(new WebAppInterface(), "AndroidInterface");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
            }
        });
    }

    private void setupListeners() {
        btnInventoryReport.setOnClickListener(v -> {
            currentReportType = "inventory_detail";
            updateTitle(isArabic ? "تقرير تفاصيل المخزون" : "Inventory Detail Report");
            loadReport(currentReportType);
        });

        btnDiscrepanciesReport.setOnClickListener(v -> {
            currentReportType = "discrepancies";
            updateTitle(isArabic ? "تقرير الفروقات والتجاوزات" : "Discrepancies Report");
            loadReport(currentReportType);
        });

        btnAuditLogsReport.setOnClickListener(v -> {
            currentReportType = "audit_logs";
            updateTitle(isArabic ? "تقرير سجل التدقيق" : "Audit Logs Report");
            loadReport(currentReportType);
        });

        btnDateFrom.setOnClickListener(v -> showDatePicker(true));
        btnDateTo.setOnClickListener(v -> showDatePicker(false));

        btnExportExcel.setOnClickListener(v -> exportExcel());
    }

    private void setDefaultDates() {
        Calendar cal = Calendar.getInstance();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        dateTo = sdf.format(cal.getTime());

        cal.add(Calendar.DAY_OF_MONTH, -30);
        dateFrom = sdf.format(cal.getTime());

        btnDateFrom.setText(dateFrom);
        btnDateTo.setText(dateTo);
    }

    private void showDatePicker(boolean isFrom) {
        Calendar cal = Calendar.getInstance();
        DatePickerDialog dialog = new DatePickerDialog(
                requireContext(),
                (view, year, month, dayOfMonth) -> {
                    String selected = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth);
                    if (isFrom) {
                        dateFrom = selected;
                        btnDateFrom.setText(dateFrom);
                    } else {
                        dateTo = selected;
                        btnDateTo.setText(dateTo);
                    }
                    loadReport(currentReportType);
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
        );
        dialog.show();
    }

    private void updateTitle(String title) {
        if (tvReportTitle != null) {
            tvReportTitle.setText(title);
        }
    }

    private void loadReport(String type) {
        showLoadingHtml();

        ApiService api = ApiClient.getClient(requireContext()).create(ApiService.class);
        Call<String> call = api.getReportData(type, dateFrom, dateTo);

        call.enqueue(new Callback<String>() {
            @Override
            public void onResponse(@NonNull Call<String> call, @NonNull Response<String> response) {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        lastReportData = new JSONObject(response.body());
                        renderReportHtml(type, lastReportData);
                    } catch (JSONException e) {
                        Log.e(TAG, "JSON parsing error", e);
                        showErrorHtml(isArabic ? "خطأ في معالجة البيانات" : "Data Parsing Error");
                    }
                } else {
                    showErrorHtml(isArabic ? "فشل في جلب البيانات من الخادم" : "Failed to fetch data from server");
                }
            }

            @Override
            public void onFailure(@NonNull Call<String> call, @NonNull Throwable t) {
                Log.e(TAG, "Network request failed", t);
                showErrorHtml(isArabic ? "خطأ في الاتصال بالشبكة" : "Network Error");
            }
        });
    }

    private void showLoadingHtml() {
        String dir = isArabic ? "rtl" : "ltr";
        String loadingText = isArabic ? "جاري تحميل التقرير..." : "Loading report...";
        String html = "<html><body dir='" + dir + "' style='font-family:sans-serif; text-align:center; padding-top:50px;'>" +
                "<h3>" + loadingText + "</h3></body></html>";
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private void showErrorHtml(String message) {
        String dir = isArabic ? "rtl" : "ltr";
        String html = "<html><body dir='" + dir + "' style='font-family:sans-serif; text-align:center; padding-top:50px; color:red;'>" +
                "<h3>" + message + "</h3></body></html>";
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private void renderReportHtml(String type, JSONObject data) {
        StringBuilder html = new StringBuilder();
        String dir = isArabic ? "rtl" : "ltr";

        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'>")
                .append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>")
                .append("<script src='https://cdn.jsdelivr.net/npm/chart.js'></script>")
                .append("<style>")
                .append("body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; margin: 15px; direction: ").append(dir).append("; text-align: ").append(isArabic ? "right" : "left").append("; background-color: #f8f9fa; color: #333; }")
                .append(".summary-cards { display: flex; gap: 10px; margin-bottom: 20px; flex-wrap: wrap; }")
                .append(".card { flex: 1; min-width: 120px; background: #fff; padding: 15px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.05); border-top: 4px solid #007bff; text-align: center; }")
                .append(".card.highlight { border-top-color: #28a745; }")
                .append(".card h4 { margin: 0 0 5px 0; color: #6c757d; font-size: 13px; }")
                .append(".card .val { font-size: 20px; font-weight: bold; color: #212529; }")
                .append("table { width: 100%; border-collapse: collapse; background: #fff; margin-top: 15px; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 4px rgba(0,0,0,0.05); }")
                .append("th, td { padding: 10px 12px; border-bottom: 1px solid #dee2e6; font-size: 13px; }")
                .append("th { background-color: #f1f3f5; font-weight: 600; text-align: inherit; }")
                .append(".chart-container { position: relative; height: 220px; margin: 20px 0; background: #fff; padding: 15px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.05); }")
                .append("</style></head><body>");

        switch (type) {
            case "inventory_detail":
                html.append(buildInventoryDetailReport(data));
                break;
            case "discrepancies":
                html.append(buildDiscrepanciesReport(data));
                break;
            case "audit_logs":
                html.append(buildAuditLogsReport(data));
                break;
            default:
                html.append("<p>Report not found</p>");
                break;
        }

        html.append("</body></html>");
        webView.loadDataWithBaseURL(null, html.toString(), "text/html", "UTF-8", null);
    }

    private String buildInventoryDetailReport(JSONObject data) {
        JSONObject inv = data != null ? data.optJSONObject("inventory") : null;
        int totalItems = inv != null ? inv.optInt("total_items", 0) : 0;
        double totalWeight = inv != null ? inv.optDouble("total_weight", 0.0) : 0.0;

        JSONObject w = inv != null ? inv.optJSONObject("weights") : null;
        double k24 = w != null ? w.optDouble("k24", 0.0) : 0.0;
        double k22 = w != null ? w.optDouble("k22", 0.0) : 0.0;
        double k21 = w != null ? w.optDouble("k21", 0.0) : 0.0;
        double k18 = w != null ? w.optDouble("k18", 0.0) : 0.0;

        // التأكد من وجود أوزان حقيقية لمنع تجمد Chart.js بقيم صفريّة
        boolean hasData = (k24 + k22 + k21 + k18) > 0;

        StringBuilder script = new StringBuilder();
        if (hasData) {
            script.append("<div class='chart-container'><canvas id='karatChart'></canvas></div>")
                  .append("<script>")
                  .append("if (typeof Chart !== 'undefined') {")
                  .append("  try {")
                  .append("    new Chart(document.getElementById('karatChart'), {")
                  .append("      type: 'doughnut',")
                  .append("      data: {")
                  .append("        labels: ['24K', '22K', '21K', '18K'],")
                  .append("        datasets: [{ data: [").append(k24).append(",").append(k22).append(",").append(k21).append(",").append(k18).append("], backgroundColor: ['#FFD700','#FFC107','#FFB300','#FFA000'] }]")
                  .append("      },")
                  .append("      options: { responsive: true, maintainAspectRatio: false, plugins: { legend: { position: 'bottom' } } }")
                  .append("    });")
                  .append("  } catch(e) { console.log('Chart render error:', e); }")
                  .append("}")
                  .append("</script>");
        }

        return "<div class='summary-cards'>" +
                buildSummaryCard("", String.valueOf(totalItems), isArabic ? "إجمالي القطع" : "Total Items") +
                buildSummaryCard("highlight", String.format(Locale.US, "%.2f g", totalWeight), isArabic ? "الوزن الإجمالي" : "Total Weight") +
                "</div>" +
                script.toString() +
                "<table><tr><th>" + (isArabic ? "العيار" : "Karat") + "</th><th>" + (isArabic ? "الوزن (جرام)" : "Weight (g)") + "</th></tr>" +
                "<tr><td>24K</td><td>" + String.format(Locale.US, "%.2f", k24) + "</td></tr>" +
                "<tr><td>22K</td><td>" + String.format(Locale.US, "%.2f", k22) + "</td></tr>" +
                "<tr><td>21K</td><td>" + String.format(Locale.US, "%.2f", k21) + "</td></tr>" +
                "<tr><td>18K</td><td>" + String.format(Locale.US, "%.2f", k18) + "</td></tr></table>";
    }

    private String buildDiscrepanciesReport(JSONObject data) {
        JSONArray disc = data != null ? data.optJSONArray("discrepancies") : null;
        StringBuilder rows = new StringBuilder();

        if (disc != null && disc.length() > 0) {
            for (int i = 0; i < disc.length(); i++) {
                JSONObject item = disc.optJSONObject(i);
                if (item != null) {
                    rows.append("<tr>")
                            .append("<td>").append(item.optString("epc", "-")).append("</td>")
                            .append("<td>").append(item.optString("status", "-")).append("</td>")
                            .append("<td>").append(item.optString("location", "-")).append("</td>")
                            .append("</tr>");
                }
            }
        } else {
            rows.append("<tr><td colspan='3' style='text-align:center;'>")
                .append(isArabic ? "لا توجد فروقات مسجلة" : "No discrepancies found")
                .append("</td></tr>");
        }

        return "<table><tr><th>EPC</th><th>" + (isArabic ? "الحالة" : "Status") + "</th><th>" + (isArabic ? "الموقع" : "Location") + "</th></tr>" +
                rows.toString() + "</table>";
    }

    private String buildAuditLogsReport(JSONObject data) {
        JSONArray logs = data != null ? data.optJSONArray("logs") : null;
        StringBuilder rows = new StringBuilder();

        if (logs != null && logs.length() > 0) {
            for (int i = 0; i < logs.length(); i++) {
                JSONObject item = logs.optJSONObject(i);
                if (item != null) {
                    rows.append("<tr>")
                            .append("<td>").append(item.optString("timestamp", "-")).append("</td>")
                            .append("<td>").append(item.optString("user", "-")).append("</td>")
                            .append("<td>").append(item.optString("action", "-")).append("</td>")
                            .append("</tr>");
                }
            }
        } else {
            rows.append("<tr><td colspan='3' style='text-align:center;'>")
                .append(isArabic ? "لا توجد سجلات تدقيق" : "No audit logs found")
                .append("</td></tr>");
        }

        return "<table><tr><th>" + (isArabic ? "الوقت" : "Timestamp") + "</th><th>" + (isArabic ? "المستخدم" : "User") + "</th><th>" + (isArabic ? "الحدث" : "Action") + "</th></tr>" +
                rows.toString() + "</table>";
    }

    private String buildSummaryCard(String cssClass, String value, String title) {
        return "<div class='card " + cssClass + "'>" +
                "<h4>" + title + "</h4>" +
                "<div class='val'>" + value + "</div>" +
                "</div>";
    }

    private void exportExcel() {
        if (lastReportData == null) {
            Toast.makeText(requireContext(), isArabic ? "لا توجد بيانات لتصديرها" : "No data available to export", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            StringBuilder csv = new StringBuilder();
            csv.append("Report Type,").append(currentReportType).append("\n");
            csv.append("Date From,").append(dateFrom).append(",Date To,").append(dateTo).append("\n\n");

            if ("inventory_detail".equals(currentReportType)) {
                JSONObject inv = lastReportData.optJSONObject("inventory");
                JSONObject w = inv != null ? inv.optJSONObject("weights") : null;
                csv.append("Karat,Weight (g)\n");
                csv.append("24K,").append(w != null ? w.optDouble("k24", 0.0) : 0.0).append("\n");
                csv.append("22K,").append(w != null ? w.optDouble("k22", 0.0) : 0.0).append("\n");
                csv.append("21K,").append(w != null ? w.optDouble("k21", 0.0) : 0.0).append("\n");
                csv.append("18K,").append(w != null ? w.optDouble("k18", 0.0) : 0.0).append("\n");
            } else if ("discrepancies".equals(currentReportType)) {
                JSONArray disc = lastReportData.optJSONArray("discrepancies");
                csv.append("EPC,Status,Location\n");
                if (disc != null) {
                    for (int i = 0; i < disc.length(); i++) {
                        JSONObject item = disc.optJSONObject(i);
                        if (item != null) {
                            csv.append(item.optString("epc", "")).append(",")
                               .append(item.optString("status", "")).append(",")
                               .append(item.optString("location", "")).append("\n");
                        }
                    }
                }
            } else if ("audit_logs".equals(currentReportType)) {
                JSONArray logs = lastReportData.optJSONArray("logs");
                csv.append("Timestamp,User,Action\n");
                if (logs != null) {
                    for (int i = 0; i < logs.length(); i++) {
                        JSONObject item = logs.optJSONObject(i);
                        if (item != null) {
                            csv.append(item.optString("timestamp", "")).append(",")
                               .append(item.optString("user", "")).append(",")
                               .append(item.optString("action", "")).append("\n");
                        }
                    }
                }
            }

            File exportDir = new File(requireContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "MiraReports");
            if (!exportDir.exists()) {
                exportDir.mkdirs();
            }

            String filename = "Mira_Report_" + System.currentTimeMillis() + ".csv";
            File file = new File(exportDir, filename);
            FileOutputStream out = new FileOutputStream(file);
            out.write(csv.toString().getBytes());
            out.close();

            Uri fileUri = FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/csv");
            intent.putExtra(Intent.EXTRA_STREAM, fileUri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, isArabic ? "مشاركة التقرير عبر" : "Share report via"));

        } catch (IOException e) {
            Log.e(TAG, "Export CSV error", e);
            Toast.makeText(requireContext(), isArabic ? "فشل تصدير الملف" : "Failed to export file", Toast.LENGTH_SHORT).show();
        }
    }

    private class WebAppInterface {
        @JavascriptInterface
        public void onReportItemClick(String id) {
            Log.d(TAG, "Report item clicked: " + id);
        }
    }
}
