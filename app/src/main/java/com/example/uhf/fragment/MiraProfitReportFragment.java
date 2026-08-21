package com.example.uhf.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraProfitReportFragment extends Fragment {

    private static final String TAG = "MiraProfitReportFragment";

    private MiraApiClient apiClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView tvTotalRevenue, tvTotalExpenses, tvNetProfit;
    private TextView tvGoldRevenue, tvMakingCharges, tvProfitMargin;
    private TextView tvReportPeriod;
    private MaterialButton btnRefresh, btnExportPdf;
    
    private boolean isArabic = true;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_profit_report, container, false);
        
        apiClient = MiraApiClient.getInstance(requireContext());
        isArabic = Locale.getDefault().getLanguage().equals("ar");

        initViews(view);
        setupListeners();
        loadProfitReport();

        return view;
    }

    private void initViews(View view) {
        tvTotalRevenue = view.findViewById(R.id.tvTotalRevenue);
        tvTotalExpenses = view.findViewById(R.id.tvTotalExpenses);
        tvNetProfit = view.findViewById(R.id.tvNetProfit);
        tvGoldRevenue = view.findViewById(R.id.tvGoldRevenue);
        tvMakingCharges = view.findViewById(R.id.tvMakingCharges);
        tvProfitMargin = view.findViewById(R.id.tvProfitMargin);
        tvReportPeriod = view.findViewById(R.id.tvReportPeriod);
        btnRefresh = view.findViewById(R.id.btnRefreshProfit);
        btnExportPdf = view.findViewById(R.id.btnExportPdf);
    }

    private void setupListeners() {
        btnRefresh.setOnClickListener(v -> loadProfitReport());
        btnExportPdf.setOnClickListener(v -> exportReport());
    }

    private void loadProfitReport() {
        executor.execute(() -> {
            String from = new SimpleDateFormat("yyyy-MM-01", Locale.US).format(new Date());
            String to = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            
            MiraApiClient.ApiResponse incomeResponse = apiClient.getIncomeStatement("", from, to);
            MiraApiClient.ApiResponse goldResponse = apiClient.getGoldMovementReport("", from, to);
            
            mainHandler.post(() -> {
                if (incomeResponse != null && incomeResponse.isSuccess && incomeResponse.data != null) {
                    displayProfitReport(incomeResponse.data, goldResponse != null ? goldResponse.data : null);
                } else {
                    Toast.makeText(getContext(), 
                        isArabic ? "فشل تحميل التقرير" : "Failed to load report", 
                        Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void displayProfitReport(JSONObject incomeData, JSONObject goldData) {
        try {
            JSONObject reportData = incomeData.optJSONObject("data");
            if (reportData == null) reportData = incomeData;
            
            double totalRevenue = reportData.optDouble("total_revenue", 0);
            double totalExpenses = reportData.optDouble("total_expense", 0);
            double netProfit = reportData.optDouble("net_profit", 0);
            
            double goldNet = 0;
            if (goldData != null) {
                JSONObject goldReportData = goldData.optJSONObject("data");
                if (goldReportData == null) goldReportData = goldData;
                goldNet = goldReportData.optDouble("net_gold_movement", 0);
            }
            
            double profitMargin = totalRevenue > 0 ? (netProfit / totalRevenue) * 100 : 0;
            
            tvTotalRevenue.setText(String.format(Locale.US, "%,.2f", totalRevenue));
            tvTotalExpenses.setText(String.format(Locale.US, "%,.2f", totalExpenses));
            tvNetProfit.setText(String.format(Locale.US, "%,.2f", netProfit));
            tvGoldRevenue.setText(String.format(Locale.US, "%.3f g", goldNet));
            tvMakingCharges.setText(String.format(Locale.US, "%,.2f", totalRevenue - totalExpenses));
            tvProfitMargin.setText(String.format(Locale.US, "%.2f%%", profitMargin));
            
            String from = new SimpleDateFormat("yyyy-MM-01", Locale.US).format(new Date());
            String to = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            tvReportPeriod.setText(from + " → " + to);
            
            // تلوين صافي الربح
            if (netProfit >= 0) {
                tvNetProfit.setTextColor(android.graphics.Color.parseColor("#2ECC71"));
            } else {
                tvNetProfit.setTextColor(android.graphics.Color.parseColor("#E74C3C"));
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void exportReport() {
        Toast.makeText(getContext(), 
            isArabic ? "جاري التصدير..." : "Exporting...", 
            Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
}
