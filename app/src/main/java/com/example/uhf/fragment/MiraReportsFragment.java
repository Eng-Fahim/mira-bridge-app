package com.example.uhf.fragment;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

public class MiraReportsFragment extends Fragment {

    private static final String TAG = "ReportsFragment";
    private WebView webView;
    private Spinner spReportType;
    private Button btnDateFrom, btnDateTo, btnApply;
    private TextView tvDateRange;
    private LinearLayout statsContainer;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String dateFrom = "", dateTo = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_reports_simple, container, false);

        initViews(view);
        setupSpinner();
        setDefaultDates();
        loadDefaultReport();

        return view;
    }

    private void initViews(View view) {
        webView = view.findViewById(R.id.webReport);
        spReportType = view.findViewById(R.id.spReportType);
        btnDateFrom = view.findViewById(R.id.btnDateFrom);
        btnDateTo = view.findViewById(R.id.btnDateTo);
        btnApply = view.findViewById(R.id.btnApplyFilter);
        tvDateRange = view.findViewById(R.id.tvDateRange);
        statsContainer = view.findViewById(R.id.statsContainer);

        if (webView != null) {
            webView.getSettings().setJavaScriptEnabled(true);
            webView.setWebViewClient(new WebViewClient());
        }

        btnDateFrom.setOnClickListener(v -> showDatePicker(true));
        btnDateTo.setOnClickListener(v -> showDatePicker(false));
        btnApply.setOnClickListener(v -> loadReport());
    }

    private void setupSpinner() {
        String[] reports = {"📊 تقرير المخزون", "📋 تقرير الفروقات", "🔍 سجل التدقيق", "💰 ملخص المبيعات"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), 
            android.R.layout.simple_spinner_item, reports);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spReportType.setAdapter(adapter);
    }

    private void setDefaultDates() {
        Calendar cal = Calendar.getInstance();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        dateTo = sdf.format(cal.getTime());
        cal.add(Calendar.DAY_OF_MONTH, -30);
        dateFrom = sdf.format(cal.getTime());
        btnDateFrom.setText(dateFrom);
        btnDateTo.setText(dateTo);
        tvDateRange.setText(dateFrom + " - " + dateTo);
    }

    private void showDatePicker(boolean isFrom) {
        Calendar cal = Calendar.getInstance();
        new DatePickerDialog(requireContext(), (v, y, m, d) -> {
            String date = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d);
            if (isFrom) { dateFrom = date; btnDateFrom.setText(date); }
            else { dateTo = date; btnDateTo.setText(date); }
            tvDateRange.setText(dateFrom + " - " + dateTo);
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void loadDefaultReport() {
        String html = buildReportHTML("📊 تقرير المخزون", 
            new String[][]{
                {"إجمالي القطع", "1,270"},
                {"الوزن الكلي", "85.4 kg"},
                {"عيار 24", "12.3 kg"},
                {"عيار 21", "45.8 kg"},
                {"عيار 18", "27.3 kg"}
            },
            new String[][]{
                {"24K", "24", "12.3 kg", "28%"},
                {"21K", "21", "45.8 kg", "54%"},
                {"18K", "18", "27.3 kg", "18%"}
            });
        
        if (webView != null) {
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
        }

        // إحصائيات سريعة
        addStatCard("📦", "1,270", "إجمالي القطع");
        addStatCard("⚖️", "85.4 kg", "الوزن الكلي");
        addStatCard("💰", "$12,450", "مبيعات اليوم");
    }

    private void loadReport() {
        Toast.makeText(getContext(), "جاري تحميل التقرير...", Toast.LENGTH_SHORT).show();
        
        new Thread(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(getContext());
                MiraApiClient.ApiResponse response = client.getDashboardFull();
                
                if (response != null && response.isSuccess && response.data != null) {
                    mainHandler.post(() -> renderAPIData(response.data));
                }
            } catch (Exception e) {
                Log.e(TAG, "API error: " + e.getMessage());
            }
        }).start();
    }

    private void renderAPIData(JSONObject data) {
        JSONObject inv = data.optJSONObject("inventory");
        int total = inv != null ? inv.optInt("total_items", 0) : 0;
        double weight = inv != null ? inv.optDouble("total_weight", 0) : 0;
        
        String html = buildReportHTML("📊 تقرير المخزون (API)",
            new String[][]{
                {"إجمالي القطع", String.valueOf(total)},
                {"الوزن الكلي", String.format("%.1f kg", weight)}
            },
            new String[][]{});
        
        if (webView != null) {
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
        }
    }

    private void addStatCard(String icon, String value, String label) {
        View card = LayoutInflater.from(getContext()).inflate(R.layout.item_stat_card, statsContainer, false);
        TextView tvIcon = card.findViewById(R.id.tvStatIcon);
        TextView tvValue = card.findViewById(R.id.tvStatValue);
        TextView tvLabel = card.findViewById(R.id.tvStatLabel);
        if (tvIcon != null) tvIcon.setText(icon);
        if (tvValue != null) tvValue.setText(value);
        if (tvLabel != null) tvLabel.setText(label);
        statsContainer.addView(card);
    }

    private String buildReportHTML(String title, String[][] summary, String[][] tableData) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html dir='rtl'><head><meta charset='UTF-8'><style>")
            .append("body{font-family:sans-serif;padding:16px;background:#f5f5f5}")
            .append(".card{background:#fff;padding:14px;border-radius:10px;margin-bottom:10px;text-align:center}")
            .append(".card h3{color:#1A237E;margin:0 0 8px}")
            .append(".row{display:flex;justify-content:space-between;padding:6px 0;border-bottom:1px solid #eee}")
            .append("table{width:100%;border-collapse:collapse;background:#fff;margin-top:10px}")
            .append("th{background:#1A237E;color:#fff;padding:8px}")
            .append("td{padding:8px;text-align:center;border-bottom:1px solid #eee}")
            .append("</style></head><body>")
            .append("<div class='card'><h3>").append(title).append("</h3>");

        for (String[] row : summary) {
            html.append("<div class='row'><span>").append(row[0]).append("</span><span style='font-weight:bold'>").append(row[1]).append("</span></div>");
        }
        html.append("</div>");

        if (tableData.length > 0) {
            html.append("<table><tr><th>العيار</th><th>العدد</th><th>الوزن</th><th>النسبة</th></tr>");
            for (String[] row : tableData) {
                html.append("<tr>");
                for (String cell : row) html.append("<td>").append(cell).append("</td>");
                html.append("</tr>");
            }
            html.append("</table>");
        }

        html.append("</body></html>");
        return html.toString();
    }
}
