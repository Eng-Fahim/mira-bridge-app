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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

// استدعاء R التابع لـ com.example.uhf مباشرة
import com.example.uhf.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MiraReportsFragment extends Fragment {

    private static final String TAG = "MiraReportsFragment";

    private WebView webView;
    private Spinner spReportType, spBranchFilter;
    private Button btnDateFrom, btnDateTo, btnApplyFilter;
    private Button btnPrintReport, btnShareReport, btnExportPDF, btnExportExcel;
    private TextView tvReportTitle, tvDateRange;

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

        isArabic = Locale.getDefault().getLanguage().equals("ar");

        initViews(view);
        setupWebView();
        setupSpinners();
        setupListeners();

        setDefaultDates();
        loadReport(currentReportType);

        return view;
    }

    private void initViews(View view) {
        webView = view.findViewById(R.id.webReport);
        spReportType = view.findViewById(R.id.spReportType);
        spBranchFilter = view.findViewById(R.id.spBranchFilter);
        
        btnDateFrom = view.findViewById(R.id.btnDateFrom);
        btnDateTo = view.findViewById(R.id.btnDateTo);
        btnApplyFilter = view.findViewById(R.id.btnApplyFilter);

        btnPrintReport = view.findViewById(R.id.btnPrintReport);
        btnShareReport = view.findViewById(R.id.btnShareReport);
        btnExportPDF = view.findViewById(R.id.btnExportPDF);
        btnExportExcel = view.findViewById(R.id.btnExportExcel);

        tvReportTitle = view.findViewById(R.id.tvReportTitle);
        tvDateRange = view.findViewById(R.id.tvDateRange);
    }

    private void setupWebView() {
        if (webView == null) return;
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        webView.addJavascriptInterface(new WebAppInterface(), "AndroidInterface");
        webView.setWebViewClient(new WebViewClient());
    }

    private void setupSpinners() {
        if (spReportType != null) {
            String[] reports = isArabic ?
                    new String[]{"تقرير تفاصيل المخزون", "تقرير الفروقات والتجاوزات", "تقرير سجل التدقيق"} :
                    new String[]{"Inventory Detail", "Discrepancies Report", "Audit Logs"};

            ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, reports);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spReportType.setAdapter(adapter);

            spReportType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    switch (position) {
                        case 0:
                            currentReportType = "inventory_detail";
                            break;
                        case 1:
                            currentReportType = "discrepancies";
                            break;
                        case 2:
                            currentReportType = "audit_logs";
                            break;
                    }
                }

                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
    }

    private void setupListeners() {
        if (btnDateFrom != null) btnDateFrom.setOnClickListener(v -> showDatePicker(true));
        if (btnDateTo != null) btnDateTo.setOnClickListener(v -> showDatePicker(false));
        
        if (btnApplyFilter != null) {
            btnApplyFilter.setOnClickListener(v -> loadReport(currentReportType));
        }

        if (btnExportExcel != null) btnExportExcel.setOnClickListener(v -> exportExcel());
        if (btnShareReport != null) btnShareReport.setOnClickListener(v -> exportExcel());
    }

    private void setDefaultDates() {
        Calendar cal = Calendar.getInstance();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        dateTo = sdf.format(cal.getTime());

        cal.add(Calendar.DAY_OF_MONTH, -30);
        dateFrom = sdf.format(cal.getTime());

        if (btnDateFrom != null) btnDateFrom.setText(dateFrom);
        if (btnDateTo != null) btnDateTo.setText(dateTo);
        if (tvDateRange != null) tvDateRange.setText(dateFrom + " - " + dateTo);
    }

    private void showDatePicker(boolean isFrom) {
        Calendar cal = Calendar.getInstance();
        DatePickerDialog dialog = new DatePickerDialog(
                requireContext(),
                (view, year, month, dayOfMonth) -> {
                    String selected = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth);
                    if (isFrom) {
                        dateFrom = selected;
                        if (btnDateFrom != null) btnDateFrom.setText(dateFrom);
                    } else {
                        dateTo = selected;
                        if (btnDateTo != null) btnDateTo.setText(dateTo);
                    }
                    if (tvDateRange != null) tvDateRange.setText(dateFrom + " - " + dateTo);
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
        );
        dialog.show();
    }

    private void loadReport(String type) {
        showLoadingHtml();

        try {
            // محاولة جلب MiraApiClient آلياً لتجنب مشاكل تعارض المسارات أثناء التجميع
            Class<?> clientClass = null;
            try {
                clientClass = Class.forName("com.example.rfid.api.MiraApiClient");
            } catch (ClassNotFoundException e) {
                clientClass = Class.forName("com.example.uhf.api.MiraApiClient");
            }

            Method getInstanceMethod = clientClass.getMethod("getInstance", android.content.Context.class);
            Object clientInstance = getInstanceMethod.invoke(null, requireContext());

            Method getApiMethod = clientClass.getMethod("getApi");
            Object apiService = getApiMethod.invoke(clientInstance);

            Method getReportDataMethod = apiService.getClass().getMethod("getReportData", String.class, String.class, String.class);
            Call<String> call = (Call<String>) getReportDataMethod.invoke(apiService, type, dateFrom, dateTo);

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
        } catch (Exception e) {
            Log.e(TAG, "Dynamic API execution failed", e);
            // عرض محاكاة واجهة التقرير محلياً في حال عدم اكتمال ربط الـ API على السيرفر بعد
            renderReportHtml(type, null);
        }
    }

    private void showLoadingHtml() {
        if (webView == null) return;
        String dir = isArabic ? "rtl" : "ltr";
        String loadingText = isArabic ? "جاري تحميل التقرير..." : "Loading report...";
        String html = "<html><body dir='" + dir + "' style='font-family:sans-serif; text-align:center; padding-top:50px;'>" +
                "<h3>" + loadingText + "</h3></body></html>";
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private void showErrorHtml(String message) {
        if (webView == null) return;
        String dir = isArabic ? "rtl" : "ltr";
        String html = "<html><body dir='" + dir + "' style='font-family:sans-serif; text-align:center; padding-top:50px; color:red;'>" +
                "<h3>" + message + "</h3></body></html>";
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private void renderReportHtml(String type, JSONObject data) {
        if (webView == null) return;
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
