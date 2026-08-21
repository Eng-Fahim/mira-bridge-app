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
import com.example.uhf.manager.MiraBranchesManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraFinancialReportsFragment extends Fragment {

    private static final String TAG = "MiraFinancialReportsFragment";

    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView tvReportTitle, tvReportDate, tvReportContent;
    private MaterialButton btnBalanceSheet, btnIncomeStatement, btnGoldMovement;
    
    private boolean isArabic = true;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_financial_reports, container, false);
        
        apiClient = MiraApiClient.getInstance(requireContext());
        branchesManager = MiraBranchesManager.getInstance(requireContext());
        isArabic = Locale.getDefault().getLanguage().equals("ar");

        initViews(view);
        setupListeners();
        
        // تحميل الميزانية افتراضياً
        loadBalanceSheet();

        return view;
    }

    private void initViews(View view) {
        tvReportTitle = view.findViewById(R.id.tvReportTitle);
        tvReportDate = view.findViewById(R.id.tvReportDate);
        tvReportContent = view.findViewById(R.id.tvReportContent);
        btnBalanceSheet = view.findViewById(R.id.btnBalanceSheet);
        btnIncomeStatement = view.findViewById(R.id.btnIncomeStatement);
        btnGoldMovement = view.findViewById(R.id.btnGoldMovement);
    }

    private void setupListeners() {
        btnBalanceSheet.setOnClickListener(v -> loadBalanceSheet());
        btnIncomeStatement.setOnClickListener(v -> loadIncomeStatement());
        btnGoldMovement.setOnClickListener(v -> loadGoldMovement());
    }

    private void loadBalanceSheet() {
        tvReportTitle.setText(isArabic ? "الميزانية العمومية" : "Balance Sheet");
        tvReportDate.setText(new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()));
        
        executor.execute(() -> {
            int branchId = branchesManager.getActiveBranchId();
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            
            MiraApiClient.ApiResponse response = apiClient.getBalanceSheet(
                String.valueOf(branchId), today);
            
            mainHandler.post(() -> {
                if (response != null && response.isSuccess && response.data != null) {
                    displayBalanceSheet(response.data);
                } else {
                    tvReportContent.setText(isArabic ? "فشل تحميل البيانات" : "Failed to load data");
                }
            });
        });
    }

    private void loadIncomeStatement() {
        tvReportTitle.setText(isArabic ? "قائمة الدخل" : "Income Statement");
        
        executor.execute(() -> {
            int branchId = branchesManager.getActiveBranchId();
            String from = new SimpleDateFormat("yyyy-MM-01", Locale.US).format(new Date());
            String to = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            
            MiraApiClient.ApiResponse response = apiClient.getIncomeStatement(
                String.valueOf(branchId), from, to);
            
            mainHandler.post(() -> {
                if (response != null && response.isSuccess && response.data != null) {
                    displayIncomeStatement(response.data);
                } else {
                    tvReportContent.setText(isArabic ? "فشل تحميل البيانات" : "Failed to load data");
                }
            });
        });
    }

    private void loadGoldMovement() {
        tvReportTitle.setText(isArabic ? "تقرير حركة الذهب" : "Gold Movement Report");
        
        executor.execute(() -> {
            int branchId = branchesManager.getActiveBranchId();
            String from = new SimpleDateFormat("yyyy-MM-01", Locale.US).format(new Date());
            String to = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            
            MiraApiClient.ApiResponse response = apiClient.getGoldMovementReport(
                String.valueOf(branchId), from, to);
            
            mainHandler.post(() -> {
                if (response != null && response.isSuccess && response.data != null) {
                    displayGoldMovement(response.data);
                } else {
                    tvReportContent.setText(isArabic ? "فشل تحميل البيانات" : "Failed to load data");
                }
            });
        });
    }

    private void displayBalanceSheet(JSONObject data) {
        try {
            JSONObject reportData = data.optJSONObject("data");
            if (reportData == null) reportData = data;
            
            StringBuilder sb = new StringBuilder();
            
            sb.append("═══════════════════════════\n");
            sb.append(isArabic ? "📊 الميزانية العمومية\n" : "📊 Balance Sheet\n");
            sb.append("═══════════════════════════\n\n");
            
            sb.append(isArabic ? "🏦 الأصول (Assets):\n" : "🏦 Assets:\n");
            JSONArray assets = reportData.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject acc = assets.getJSONObject(i);
                    sb.append("  • ").append(acc.optString("name", ""))
                      .append(": ").append(String.format(Locale.US, "%,.2f", acc.optDouble("balance", 0)))
                      .append("\n");
                }
            }
            sb.append(isArabic ? "  إجمالي الأصول: " : "  Total Assets: ")
              .append(String.format(Locale.US, "%,.2f", reportData.optDouble("total_assets", 0)))
              .append("\n\n");
            
            sb.append(isArabic ? "📋 الخصوم (Liabilities):\n" : "📋 Liabilities:\n");
            JSONArray liabilities = reportData.optJSONArray("liabilities");
            if (liabilities != null) {
                for (int i = 0; i < liabilities.length(); i++) {
                    JSONObject acc = liabilities.getJSONObject(i);
                    sb.append("  • ").append(acc.optString("name", ""))
                      .append(": ").append(String.format(Locale.US, "%,.2f", acc.optDouble("balance", 0)))
                      .append("\n");
                }
            }
            sb.append(isArabic ? "  إجمالي الخصوم: " : "  Total Liabilities: ")
              .append(String.format(Locale.US, "%,.2f", reportData.optDouble("total_liabilities", 0)))
              .append("\n\n");
            
            sb.append(isArabic ? "💎 الذهب الصافي (24K):\n" : "💎 Net Gold (24K):\n");
            sb.append("  ").append(String.format(Locale.US, "%.3f", reportData.optDouble("total_gold_assets", 0)))
              .append(" g\n\n");
            
            boolean balanced = reportData.optBoolean("is_balanced", false);
            sb.append(balanced ? "✅ " : "⚠️ ")
              .append(isArabic ? "الحالة: " : "Status: ")
              .append(balanced ? (isArabic ? "متوازن" : "Balanced") : (isArabic ? "غير متوازن" : "Unbalanced"));
            
            tvReportContent.setText(sb.toString());
            
        } catch (Exception e) {
            tvReportContent.setText(isArabic ? "خطأ في عرض البيانات" : "Error displaying data");
        }
    }

    private void displayIncomeStatement(JSONObject data) {
        try {
            JSONObject reportData = data.optJSONObject("data");
            if (reportData == null) reportData = data;
            
            StringBuilder sb = new StringBuilder();
            
            sb.append("═══════════════════════════\n");
            sb.append(isArabic ? "📈 قائمة الدخل\n" : "📈 Income Statement\n");
            sb.append("═══════════════════════════\n\n");
            
            sb.append(isArabic ? "💰 الإيرادات:\n" : "💰 Revenues:\n");
            JSONArray revenues = reportData.optJSONArray("revenues");
            if (revenues != null) {
                for (int i = 0; i < revenues.length(); i++) {
                    JSONObject acc = revenues.getJSONObject(i);
                    sb.append("  • ").append(acc.optString("name", ""))
                      .append(": ").append(String.format(Locale.US, "%,.2f", acc.optDouble("amount", 0)))
                      .append("\n");
                }
            }
            
            sb.append(isArabic ? "\n📉 المصروفات:\n" : "\n📉 Expenses:\n");
            JSONArray expenses = reportData.optJSONArray("expenses");
            if (expenses != null) {
                for (int i = 0; i < expenses.length(); i++) {
                    JSONObject acc = expenses.getJSONObject(i);
                    sb.append("  • ").append(acc.optString("name", ""))
                      .append(": ").append(String.format(Locale.US, "%,.2f", acc.optDouble("amount", 0)))
                      .append("\n");
                }
            }
            
            sb.append("\n═══════════════════════════\n");
            sb.append(isArabic ? "صافي الربح: " : "Net Profit: ")
              .append(String.format(Locale.US, "%,.2f", reportData.optDouble("net_profit", 0)));
            
            tvReportContent.setText(sb.toString());
            
        } catch (Exception e) {
            tvReportContent.setText(isArabic ? "خطأ في عرض البيانات" : "Error displaying data");
        }
    }

    private void displayGoldMovement(JSONObject data) {
        try {
            JSONObject reportData = data.optJSONObject("data");
            if (reportData == null) reportData = data;
            
            StringBuilder sb = new StringBuilder();
            
            sb.append("═══════════════════════════\n");
            sb.append(isArabic ? "💎 حركة الذهب\n" : "💎 Gold Movement\n");
            sb.append("═══════════════════════════\n\n");
            
            sb.append(isArabic ? "📥 وارد ذهب: " : "📥 Gold In: ")
              .append(String.format(Locale.US, "%.3f", reportData.optDouble("total_debit_gold", 0)))
              .append(" g\n");
            
            sb.append(isArabic ? "📤 صادر ذهب: " : "📤 Gold Out: ")
              .append(String.format(Locale.US, "%.3f", reportData.optDouble("total_credit_gold", 0)))
              .append(" g\n");
            
            sb.append(isArabic ? "📊 صافي الحركة: " : "📊 Net Movement: ")
              .append(String.format(Locale.US, "%.3f", reportData.optDouble("net_gold_movement", 0)))
              .append(" g\n");
            
            tvReportContent.setText(sb.toString());
            
        } catch (Exception e) {
            tvReportContent.setText(isArabic ? "خطأ في عرض البيانات" : "Error displaying data");
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
                     }
