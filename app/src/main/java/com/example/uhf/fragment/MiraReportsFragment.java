package com.example.uhf.fragment;

import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

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

        try {
            isArabic = Locale.getDefault().getLanguage().equals("ar");
        } catch (Exception e) {
            isArabic = true;
        }

        initViews(view);
        setupWebView();
        setupSpinners();
        setupListeners();
        setDefaultDates();

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        view.post(() -> {
            if (isAdded() && getContext() != null) {
                loadReport(currentReportType);
            }
        });
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
        webView.setWebViewClient(new WebViewClient());
    }

    private void setupSpinners() {
        if (spReportType == null || !isAdded() || getContext() == null) return;

        String[] reports = isArabic
            ? new String[]{"تقرير المخزون", "تقرير الفروقات", "سجل التدقيق"}
            : new String[]{"Inventory Report", "Discrepancies", "Audit Logs"};

        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
            android.R.layout.simple_spinner_item, reports);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spReportType.setAdapter(adapter);

        spReportType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                switch (position) {
                    case 0: currentReportType = "inventory_detail"; break;
                    case 1: currentReportType = "discrepancies"; break;
                    case 2: currentReportType = "audit_logs"; break;
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setupListeners() {
        if (btnDateFrom != null) btnDateFrom.setOnClickListener(v -> showDatePicker(true));
        if (btnDateTo != null) btnDateTo.setOnClickListener(v -> showDatePicker(false));
        if (btnApplyFilter != null) btnApplyFilter.setOnClickListener(v -> loadReport(currentReportType));
        if (btnExportExcel != null) btnExportExcel.setOnClickListener(v -> exportExcel());
        if (btnShareReport != null) btnShareReport.setOnClickListener(v -> exportExcel());
        if (btnPrintReport != null) btnPrintReport.setOnClickListener(v -> printReport());
        if (btnExportPDF != null) btnExportPDF.setOnClickListener(v -> exportPDF());
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
        if (!isAdded() || getContext() == null) return;

        Calendar cal = Calendar.getInstance();
        new DatePickerDialog(requireContext(),
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
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show();
    }

    // ==================== ✅ تحميل التقرير مباشرة ====================

    private void loadReport(String type) {
        if (!isAdded() || getContext() == null) return;

        showLoadingHtml();

        new Thread(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(getContext());
                MiraApiClient.ApiResponse response = client.getDashboardFull();

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        if (response != null && response.isSuccess && response.data != null) {
                            lastReportData = response.data;
                            renderReportHtml(type, response.data);
                        } else {
                            renderReportHtml(type, null);
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Report load error: " + e.getMessage());
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        renderReportHtml(type, null);
                    });
                }
            }
        }).start();
    }

    // ==================== HTML ====================

    private void showLoadingHtml() {
        if (webView == null) return;
        String text = isArabic ? "جاري تحميل التقرير..." : "Loading report...";
        webView.loadDataWithBaseURL(null, "<html><body style='text-align:center;padding-top:50px;font-family:sans-serif;'><h3>" + text + "</h3></body></html>", "text/html", "UTF-8", null);
    }

    private void renderReportHtml(String type, JSONObject data) {
        if (webView == null) return;

        String html = "<!DOCTYPE html><html><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1.0'>"
            + "<style>"
            + "body{font-family:sans-serif;margin:12px;background:#f5f5f5;color:#333}"
            + ".card{background:#fff;padding:14px;border-radius:10px;margin-bottom:10px;box-shadow:0 1px 3px rgba(0,0,0,.1);text-align:center}"
            + ".card h4{color:#666;font-size:12px;margin:0 0 4px}.card .val{font-size:22px;font-weight:bold;color:#1A237E}"
            + "table{width:100%;border-collapse:collapse;background:#fff;border-radius:10px;overflow:hidden;margin-top:10px}"
            + "th{background:#1A237E;color:#fff;padding:10px;font-size:12px}"
            + "td{padding:8px 10px;border-bottom:1px solid #eee;font-size:12px;text-align:center}"
            + "</style></head><body dir='" + (isArabic ? "rtl" : "ltr") + "'>";

        switch (type) {
            case "inventory_detail":
                html += buildInventoryReport(data);
                break;
            case "discrepancies":
                html += buildDiscrepanciesReport(data);
                break;
            case "audit_logs":
                html += buildAuditReport(data);
                break;
            default:
                html += "<p>" + (isArabic ? "نوع التقرير غير معروف" : "Unknown report type") + "</p>";
        }

        html += "</body></html>";
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private String buildInventoryReport(JSONObject data) {
        JSONObject inv = data != null ? data.optJSONObject("inventory") : null;
        int total = inv != null ? inv.optInt("total_items", 0) : 0;
        double weight = inv != null ? inv.optDouble("total_weight", 0) : 0;

        JSONObject w = inv != null ? inv.optJSONObject("weights") : null;
        double k24 = w != null ? w.optDouble("k24", 0) : 0;
        double k22 = w != null ? w.optDouble("k22", 0) : 0;
        double k21 = w != null ? w.optDouble("k21", 0) : 0;
        double k18 = w != null ? w.optDouble("k18", 0) : 0;

        return "<div class='card'><h4>" + ar("إجمالي القطع", "Total Items") + "</h4><div class='val'>" + total + "</div></div>"
            + "<div class='card'><h4>" + ar("الوزن الإجمالي", "Total Weight") + "</h4><div class='val'>" + String.format(Locale.US, "%.1f g", weight) + "</div></div>"
            + "<table><tr><th>" + ar("العيار", "Karat") + "</th><th>" + ar("الوزن", "Weight") + "</th></tr>"
            + "<tr><td>24K</td><td>" + String.format("%.1f", k24) + " g</td></tr>"
            + "<tr><td>22K</td><td>" + String.format("%.1f", k22) + " g</td></tr>"
            + "<tr><td>21K</td><td>" + String.format("%.1f", k21) + " g</td></tr>"
            + "<tr><td>18K</td><td>" + String.format("%.1f", k18) + " g</td></tr>"
            + "</table>";
    }

    private String buildDiscrepanciesReport(JSONObject data) {
        JSONArray disc = data != null ? data.optJSONArray("discrepancies") : null;
        StringBuilder rows = new StringBuilder();

        if (disc != null && disc.length() > 0) {
            for (int i = 0; i < disc.length(); i++) {
                JSONObject item = disc.optJSONObject(i);
                if (item != null) {
                    rows.append("<tr><td>").append(item.optString("epc", "-")).append("</td>")
                        .append("<td>").append(item.optString("status", "-")).append("</td>")
                        .append("<td>").append(item.optString("location", "-")).append("</td></tr>");
                }
            }
        } else {
            rows.append("<tr><td colspan='3'>").append(ar("لا توجد فروقات", "No discrepancies")).append("</td></tr>");
        }

        return "<table><tr><th>EPC</th><th>" + ar("الحالة", "Status") + "</th><th>" + ar("الموقع", "Location") + "</th></tr>" + rows + "</table>";
    }

    private String buildAuditReport(JSONObject data) {
        JSONArray logs = data != null ? data.optJSONArray("logs") : null;
        StringBuilder rows = new StringBuilder();

        if (logs != null && logs.length() > 0) {
            for (int i = 0; i < logs.length(); i++) {
                JSONObject item = logs.optJSONObject(i);
                if (item != null) {
                    rows.append("<tr><td>").append(item.optString("timestamp", "-")).append("</td>")
                        .append("<td>").append(item.optString("user", "-")).append("</td>")
                        .append("<td>").append(item.optString("action", "-")).append("</td></tr>");
                }
            }
        } else {
            rows.append("<tr><td colspan='3'>").append(ar("لا توجد سجلات", "No logs")).append("</td></tr>");
        }

        return "<table><tr><th>" + ar("الوقت", "Time") + "</th><th>" + ar("المستخدم", "User") + "</th><th>" + ar("الحدث", "Action") + "</th></tr>" + rows + "</table>";
    }

    private String ar(String arabic, String english) {
        return isArabic ? arabic : english;
    }

    // ==================== تصدير ====================

    private void exportExcel() {
        if (!isAdded() || getContext() == null) return;
        if (lastReportData == null) {
            Toast.makeText(requireContext(), ar("لا توجد بيانات", "No data"), Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            StringBuilder csv = new StringBuilder();
            csv.append("Karat,Weight (g)\n");
            JSONObject w = lastReportData.optJSONObject("inventory") != null
                ? lastReportData.optJSONObject("inventory").optJSONObject("weights") : null;
            csv.append("24K,").append(w != null ? w.optDouble("k24", 0) : 0).append("\n");
            csv.append("22K,").append(w != null ? w.optDouble("k22", 0) : 0).append("\n");
            csv.append("21K,").append(w != null ? w.optDouble("k21", 0) : 0).append("\n");
            csv.append("18K,").append(w != null ? w.optDouble("k18", 0) : 0).append("\n");

            File dir = new File(requireContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "MiraReports");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "Mira_Report_" + System.currentTimeMillis() + ".csv");
            FileOutputStream out = new FileOutputStream(file);
            out.write(csv.toString().getBytes());
            out.close();

            Uri uri = FileProvider.getUriForFile(requireContext(), 
            requireContext().getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/csv");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, ar("مشاركة التقرير", "Share report")));
        } catch (IOException e) {
            Toast.makeText(requireContext(), ar("فشل التصدير", "Export failed"), Toast.LENGTH_SHORT).show();
        }
    }

    private void printReport() {
        if (webView != null) {
            webView.loadUrl("javascript:window.print();");
        }
    }

    private void exportPDF() {
        Toast.makeText(getContext(), ar("جاري التصدير...", "Exporting..."), Toast.LENGTH_SHORT).show();
        printReport();
    }
}
