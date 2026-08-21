package com.example.uhf.fragment;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.tabs.TabLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraAccountingChartsFragment extends Fragment {

    private static final String TAG = "MiraAccountingChartsFragment";

    private MiraApiClient apiClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TabLayout tabChartType;
    private LinearLayout chartContainer;
    private TextView tvChartTitle;
    private TextView tvChartSummary;
    
    private boolean isArabic = true;
    private String currentChart = "revenue"; // revenue, gold, expenses

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_accounting_charts, container, false);
        
        apiClient = MiraApiClient.getInstance(requireContext());
        isArabic = Locale.getDefault().getLanguage().equals("ar");

        initViews(view);
        setupListeners();
        loadChartData();

        return view;
    }

    private void initViews(View view) {
        tabChartType = view.findViewById(R.id.tabChartType);
        chartContainer = view.findViewById(R.id.chartContainer);
        tvChartTitle = view.findViewById(R.id.tvChartTitle);
        tvChartSummary = view.findViewById(R.id.tvChartSummary);
        
        if (tabChartType != null) {
            tabChartType.addTab(tabChartType.newTab().setText(isArabic ? "الإيرادات" : "Revenue"));
            tabChartType.addTab(tabChartType.newTab().setText(isArabic ? "حركة الذهب" : "Gold Movement"));
            tabChartType.addTab(tabChartType.newTab().setText(isArabic ? "المصروفات" : "Expenses"));
        }
    }

    private void setupListeners() {
        if (tabChartType != null) {
            tabChartType.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
                @Override
                public void onTabSelected(TabLayout.Tab tab) {
                    switch (tab.getPosition()) {
                        case 0: currentChart = "revenue"; break;
                        case 1: currentChart = "gold"; break;
                        case 2: currentChart = "expenses"; break;
                    }
                    loadChartData();
                }
                @Override public void onTabUnselected(TabLayout.Tab tab) {}
                @Override public void onTabReselected(TabLayout.Tab tab) {}
            });
        }
    }

    private void loadChartData() {
        executor.execute(() -> {
            try {
                MiraApiClient.ApiResponse response = null;
                
                switch (currentChart) {
                    case "revenue":
                        response = apiClient.getIncomeStatement("", 
                            new java.text.SimpleDateFormat("yyyy-MM-01", Locale.US).format(new java.util.Date()),
                            new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date()));
                        break;
                    case "gold":
                        response = apiClient.getGoldMovementReport("", 
                            new java.text.SimpleDateFormat("yyyy-MM-01", Locale.US).format(new java.util.Date()),
                            new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date()));
                        break;
                    case "expenses":
                        response = apiClient.getIncomeStatement("", 
                            new java.text.SimpleDateFormat("yyyy-MM-01", Locale.US).format(new java.util.Date()),
                            new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date()));
                        break;
                }
                
                final MiraApiClient.ApiResponse finalResponse = response;
                
                mainHandler.post(() -> {
                    if (finalResponse != null && finalResponse.isSuccess && finalResponse.data != null) {
                        renderChart(finalResponse.data);
                    } else {
                        Toast.makeText(getContext(), 
                            isArabic ? "فشل تحميل البيانات" : "Failed to load data", 
                            Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    /**
     * ✅ رسم بياني بسيط باستخدام Views (بدون مكتبات خارجية)
     */
    private void renderChart(JSONObject data) {
        try {
            chartContainer.removeAllViews();
            
            JSONObject reportData = data.optJSONObject("data");
            if (reportData == null) reportData = data;
            
            List<ChartBar> bars = new ArrayList<>();
            double maxValue = 0;
            
            if ("revenue".equals(currentChart) || "expenses".equals(currentChart)) {
                JSONArray items = currentChart.equals("revenue") ? 
                    reportData.optJSONArray("revenues") : reportData.optJSONArray("expenses");
                
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.getJSONObject(i);
                        String name = item.optString("name", "");
                        double amount = item.optDouble("amount", 0);
                        bars.add(new ChartBar(name, amount, getBarColor(i)));
                        maxValue = Math.max(maxValue, amount);
                    }
                }
                
                if ("revenue".equals(currentChart)) {
                    tvChartTitle.setText(isArabic ? "📊 الإيرادات الشهرية" : "📊 Monthly Revenue");
                    tvChartSummary.setText(isArabic ? "إجمالي: " : "Total: " + 
                        String.format(Locale.US, "%,.2f", reportData.optDouble("total_revenue", 0)));
                } else {
                    tvChartTitle.setText(isArabic ? "📊 المصروفات" : "📊 Expenses");
                    tvChartSummary.setText(isArabic ? "إجمالي: " : "Total: " + 
                        String.format(Locale.US, "%,.2f", reportData.optDouble("total_expense", 0)));
                }
            } else if ("gold".equals(currentChart)) {
                tvChartTitle.setText(isArabic ? "📊 حركة الذهب" : "📊 Gold Movement");
                
                double goldIn = reportData.optDouble("total_debit_gold", 0);
                double goldOut = reportData.optDouble("total_credit_gold", 0);
                double netGold = reportData.optDouble("net_gold_movement", 0);
                
                bars.add(new ChartBar(isArabic ? "وارد" : "In", goldIn, Color.parseColor("#4CAF50")));
                bars.add(new ChartBar(isArabic ? "صادر" : "Out", goldOut, Color.parseColor("#F44336")));
                bars.add(new ChartBar(isArabic ? "صافي" : "Net", netGold, Color.parseColor("#2196F3")));
                
                maxValue = Math.max(goldIn, Math.max(goldOut, Math.abs(netGold)));
                
                tvChartSummary.setText(isArabic ? "صافي: " : "Net: " + 
                    String.format(Locale.US, "%.3f g", netGold));
            }
            
            // رسم الأعمدة
            for (ChartBar bar : bars) {
                addBarToChart(bar, maxValue);
            }
            
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(getContext(), 
                isArabic ? "خطأ في عرض الرسم" : "Error rendering chart", 
                Toast.LENGTH_SHORT).show();
        }
    }

    private void addBarToChart(ChartBar bar, double maxValue) {
        if (chartContainer == null || getContext() == null) return;
        
        LinearLayout barRow = new LinearLayout(getContext());
        barRow.setOrientation(LinearLayout.VERTICAL);
        barRow.setPadding(8, 4, 8, 4);
        
        // اسم العنصر
        TextView tvName = new TextView(getContext());
        tvName.setText(bar.name);
        tvName.setTextSize(11);
        tvName.setTextColor(Color.BLACK);
        barRow.addView(tvName);
        
        // الشريط
        LinearLayout barBackground = new LinearLayout(getContext());
        barBackground.setOrientation(LinearLayout.HORIZONTAL);
        barBackground.setPadding(0, 2, 0, 2);
        
        // قيمة الشريط
        View barView = new View(getContext());
        int barWidth = maxValue > 0 ? (int) ((bar.value / maxValue) * 300) : 0;
        barWidth = Math.max(barWidth, 10);
        barView.setLayoutParams(new LinearLayout.LayoutParams(barWidth, 30));
        barView.setBackgroundColor(bar.color);
        barBackground.addView(barView);
        
        // قيمة رقمية
        TextView tvValue = new TextView(getContext());
        tvValue.setText(String.format(Locale.US, "%,.2f", bar.value));
        tvValue.setTextSize(10);
        tvValue.setPadding(8, 0, 0, 0);
        barBackground.addView(tvValue);
        
        barRow.addView(barBackground);
        chartContainer.addView(barRow);
    }

    private int getBarColor(int index) {
        int[] colors = {
            Color.parseColor("#4CAF50"),
            Color.parseColor("#2196F3"),
            Color.parseColor("#FF9800"),
            Color.parseColor("#9C27B0"),
            Color.parseColor("#F44336"),
            Color.parseColor("#00BCD4"),
            Color.parseColor("#795548"),
            Color.parseColor("#607D8B")
        };
        return colors[index % colors.length];
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }

    // ==================== MODEL ====================
    private static class ChartBar {
        String name;
        double value;
        int color;
        
        ChartBar(String name, double value, int color) {
            this.name = name;
            this.value = value;
            this.color = color;
        }
    }
}
