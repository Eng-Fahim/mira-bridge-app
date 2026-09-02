package com.example.uhf.fragment;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.google.android.material.tabs.TabLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraAccountingChartsFragment extends Fragment {

    private MiraApiClient apiClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TabLayout tabChartType;
    private LinearLayout chartContainer;
    private TextView tvChartTitle;
    private TextView tvChartSummary;
    private ProgressBar progressBar;
    
    private boolean isArabic = true;
    private String currentChart = "gold"; // gold, revenue, expenses

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
        progressBar = view.findViewById(R.id.progressBar);
        
        if (tabChartType != null) {
            tabChartType.removeAllTabs();
            tabChartType.addTab(tabChartType.newTab().setText(isArabic ? "ميزان الذهب (24K)" : "Gold (24K)"));
            tabChartType.addTab(tabChartType.newTab().setText(isArabic ? "الإيرادات" : "Revenue"));
            tabChartType.addTab(tabChartType.newTab().setText(isArabic ? "المصروفات" : "Expenses"));
        }
    }

    private void setupListeners() {
        if (tabChartType != null) {
            tabChartType.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
                @Override
                public void onTabSelected(TabLayout.Tab tab) {
                    switch (tab.getPosition()) {
                        case 0: currentChart = "gold"; break;
                        case 1: currentChart = "revenue"; break;
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
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                MiraApiClient.ApiResponse response = null;
                SimpleDateFormat sdfStart = new SimpleDateFormat("yyyy-MM-01", Locale.US);
                SimpleDateFormat sdfEnd = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                Date now = new Date();

                if ("gold".equals(currentChart)) {
                    response = apiClient.getGoldMovementReport("", sdfStart.format(now), sdfEnd.format(now));
                } else {
                    response = apiClient.getIncomeStatement("", sdfStart.format(now), sdfEnd.format(now));
                }
                
                final MiraApiClient.ApiResponse finalResponse = response;
                
                mainHandler.post(() -> {
                    if (progressBar != null) progressBar.setVisibility(View.GONE);
                    if (finalResponse != null && finalResponse.isSuccess && finalResponse.data != null) {
                        renderChart(finalResponse.data);
                    } else {
                        Toast.makeText(getContext(), isArabic ? "فشل تحميل التحليلات" : "Failed to load chart", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (progressBar != null) progressBar.setVisibility(View.GONE);
                });
            }
        });
    }

    private void renderChart(JSONObject data) {
        try {
            chartContainer.removeAllViews();
            JSONObject reportData = data.optJSONObject("data");
            if (reportData == null) reportData = data;
            
            List<ChartBar> bars = new ArrayList<>();
            double maxValue = 0;
            
            if ("gold".equals(currentChart)) {
                tvChartTitle.setText(isArabic ? "📊 تدفق أوزان الذهب الخالص (24K)" : "📊 Net Gold Flow (24K)");
                
                double goldIn = reportData.optDouble("total_debit_gold", 0);
                double goldOut = reportData.optDouble("total_credit_gold", 0);
                double netGold = reportData.optDouble("net_gold_movement", 0);
                
                bars.add(new ChartBar(isArabic ? "المقبوضات (وارد)" : "Inflow", goldIn, Color.parseColor("#2ECC71"), true));
                bars.add(new ChartBar(isArabic ? "المسلمات (صادر)" : "Outflow", goldOut, Color.parseColor("#E74C3C"), true));
                bars.add(new ChartBar(isArabic ? "الصافي المدور" : "Net Balance", netGold, Color.parseColor("#D4AF37"), true));
                
                maxValue = Math.max(goldIn, Math.max(goldOut, Math.abs(netGold)));
                tvChartSummary.setText(String.format(Locale.US, isArabic ? "الصافي: %.3f جرام" : "Net: %.3f g", netGold));

            } else {
    boolean isRev = "revenue".equals(currentChart);
    tvChartTitle.setText(isRev ? (isArabic ? "📊 تحليل مصادر الإيرادات" : "📊 Revenue Sources") : (isArabic ? "📊 بند المصروفات" : "📊 Expenses Breakdown"));
    
    JSONArray items = isRev ? reportData.optJSONArray("revenues") : reportData.optJSONArray("expenses");
    if (items != null) {
        // ✅✅✅ استبعاد القيود العكسية من الإيرادات
        JSONArray filteredItems = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null) {
                String name = item.optString("name", "");
                String description = item.optString("description", "");
                String reference = item.optString("reference", "");
                
                // ✅ استبعاد المرتجعات من الإيرادات
                boolean isRefund = name.contains("مرتجع") || name.contains("Refund") ||
                                   description.contains("مرتجع") || description.contains("Refund") ||
                                   reference.contains("REFUND") || reference.contains("refund");
                
                if (!isRefund) {
                    filteredItems.put(item);
                }
            }
        }
        items = filteredItems;
        
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            bars.add(new ChartBar(item.optString("name", ""), item.optDouble("amount", 0), getBarColor(i), false));
            maxValue = Math.max(maxValue, item.optDouble("amount", 0));
        }
    }
    
    // ✅ حساب صافي الإيرادات (بعد المرتجعات)
    double total = 0;
    if (isRev) {
        double grossRevenue = reportData.optDouble("total_revenue", 0);
        double refunds = reportData.optDouble("total_refunds", 0);
        total = grossRevenue - refunds;
    } else {
        total = reportData.optDouble("total_expense", 0);
    }
    
    tvChartSummary.setText(String.format(Locale.US, isArabic ? "الإجمالي الصافي: %,.2f" : "Net Total: %,.2f", total));
}
            
            for (ChartBar bar : bars) {
                addDynamicBar(bar, maxValue);
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void addDynamicBar(ChartBar bar, double maxValue) {
        if (chartContainer == null || getContext() == null) return;
        
        LinearLayout itemLayout = new LinearLayout(getContext());
        itemLayout.setOrientation(LinearLayout.VERTICAL);
        itemLayout.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        itemLayout.setPadding(0, 12, 0, 12);

        // Name & Text Value Row
        LinearLayout labelRow = new LinearLayout(getContext());
        labelRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView tvName = new TextView(getContext());
        tvName.setText(bar.name);
        tvName.setTextSize(13);
        tvName.setTypeface(null, android.graphics.Typeface.BOLD);
        tvName.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView tvVal = new TextView(getContext());
        tvVal.setText(bar.isGold ? String.format(Locale.US, "%.3f g", bar.value) : String.format(Locale.US, "%,.2f", bar.value));
        tvVal.setTextSize(13);
        tvVal.setTypeface(null, android.graphics.Typeface.BOLD);

        tvVal.setTextColor(bar.color);

        labelRow.addView(tvName);
        labelRow.addView(tvVal);
        itemLayout.addView(labelRow);

        // Dynamic Percentage Bar Track
        LinearLayout trackLayout = new LinearLayout(getContext());
        trackLayout.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 24));
        trackLayout.setBackgroundColor(Color.parseColor("#E0E0E0"));
        trackLayout.setOrientation(LinearLayout.HORIZONTAL);
        trackLayout.setWeightSum(100f);

        float weightPercent = maxValue > 0 ? (float) ((Math.abs(bar.value) / maxValue) * 100f) : 0f;
        weightPercent = Math.max(weightPercent, 2f); // Min visible bar width

        CardView barView = new CardView(getContext());
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weightPercent);
        barView.setLayoutParams(barParams);
        barView.setCardBackgroundColor(bar.color);
        barView.setRadius(6f);

        trackLayout.addView(barView);
        itemLayout.addView(trackLayout);

        chartContainer.addView(itemLayout);
    }

    private int getBarColor(int index) {
        int[] colors = {
            Color.parseColor("#2ECC71"), Color.parseColor("#3498DB"),
            Color.parseColor("#E67E22"), Color.parseColor("#9B59B6"),
            Color.parseColor("#E74C3C"), Color.parseColor("#1ABC9C")
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

    private static class ChartBar {
        String name;
        double value;
        int color;
        boolean isGold;
        
        ChartBar(String name, double value, int color, boolean isGold) {
            this.name = name;
            this.value = value;
            this.color = color;
            this.isGold = isGold;
        }
    }
}
