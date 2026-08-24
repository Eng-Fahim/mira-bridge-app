package com.example.uhf.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.adapter.MiraFinancialReportAdapter;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.model.MiraReportRowItem;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.datepicker.MaterialDatePicker;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraFinancialReportsFragment extends Fragment {

    private static final String TAG = "MiraFinancialReportsFragment";
    private enum ReportType { BALANCE_SHEET, INCOME_STATEMENT, GOLD_MOVEMENT }

    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView tvReportTitle, tvReportDate, tvEmptyState, tvReportStatus;
    private TextView tvValueCashMetric, tvValueGoldMetric, tvLabelCashMetric, tvLabelGoldMetric;
    private ProgressBar progressBar;
    private MaterialButtonToggleGroup toggleGroupReports;
    private MaterialButton btnSelectDateRange;
    private MaterialCardView cardStatusFooter;

    private RecyclerView rvReportDetails;
    private MiraFinancialReportAdapter adapter;

    private ReportType currentReportType = ReportType.BALANCE_SHEET;
    private boolean isArabic = true;
    
    private String startDate;
    private String endDate;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_financial_reports, container, false);

        apiClient = MiraApiClient.getInstance(requireContext());
        branchesManager = MiraBranchesManager.getInstance(requireContext());
        isArabic = Locale.getDefault().getLanguage().equals("ar");

        // افتراض نطاق الشهر الحالي
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        endDate = sdf.format(new Date());
        startDate = new SimpleDateFormat("yyyy-MM-01", Locale.US).format(new Date());

        initViews(view);
        setupRecyclerView();
        setupListeners();

        // تفعيل الميزانية افتراضياً
        toggleGroupReports.check(R.id.btnBalanceSheet);
        loadCurrentReport();

        return view;
    }

    private void initViews(View view) {
        tvReportTitle = view.findViewById(R.id.tvReportTitle);
        tvReportDate = view.findViewById(R.id.tvReportDate);
        tvEmptyState = view.findViewById(R.id.tvEmptyState);
        tvReportStatus = view.findViewById(R.id.tvReportStatus);
        
        tvLabelCashMetric = view.findViewById(R.id.tvLabelCashMetric);
        tvLabelGoldMetric = view.findViewById(R.id.tvLabelGoldMetric);
        tvValueCashMetric = view.findViewById(R.id.tvValueCashMetric);
        tvValueGoldMetric = view.findViewById(R.id.tvValueGoldMetric);

        progressBar = view.findViewById(R.id.progressBar);
        toggleGroupReports = view.findViewById(R.id.toggleGroupReports);
        btnSelectDateRange = view.findViewById(R.id.btnSelectDateRange);
        cardStatusFooter = view.findViewById(R.id.cardStatusFooter);
        rvReportDetails = view.findViewById(R.id.rvReportDetails);
    }

    private void setupRecyclerView() {
        adapter = new MiraFinancialReportAdapter(new ArrayList<>(), isArabic);
        rvReportDetails.setLayoutManager(new LinearLayoutManager(getContext()));
        rvReportDetails.setAdapter(adapter);
    }

    private void setupListeners() {
        toggleGroupReports.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.btnBalanceSheet) {
                currentReportType = ReportType.BALANCE_SHEET;
            } else if (checkedId == R.id.btnIncomeStatement) {
                currentReportType = ReportType.INCOME_STATEMENT;
            } else if (checkedId == R.id.btnGoldMovement) {
                currentReportType = ReportType.GOLD_MOVEMENT;
            }
            loadCurrentReport();
        });

        btnSelectDateRange.setOnClickListener(v -> showDatePicker());
    }

    private void showDatePicker() {
        MaterialDatePicker<Pair<Long, Long>> picker = MaterialDatePicker.Builder.dateRangePicker()
                .setTitleText(isArabic ? "اختر الفترة المالية" : "Select Date Range")
                .build();

        picker.addOnPositiveButtonClickListener(selection -> {
            if (selection.first != null && selection.second != null) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                startDate = sdf.format(new Date(selection.first));
                endDate = sdf.format(new Date(selection.second));
                loadCurrentReport();
            }
        });

        picker.show(getParentFragmentManager(), "DATE_PICKER");
    }

    private void loadCurrentReport() {
        showLoading(true);
        tvReportDate.setText(String.format(Locale.US, "%s  ➔  %s", startDate, endDate));

        switch (currentReportType) {
            case BALANCE_SHEET:
                tvReportTitle.setText(isArabic ? "الميزانية العمومية" : "Balance Sheet");
                tvLabelCashMetric.setText(isArabic ? "إجمالي الأصول" : "Total Assets");
                tvLabelGoldMetric.setText(isArabic ? "صافي الذهب (24K)" : "Net Gold (24K)");
                loadBalanceSheetData();
                break;
            case INCOME_STATEMENT:
                tvReportTitle.setText(isArabic ? "قائمة الدخل" : "Income Statement");
                tvLabelCashMetric.setText(isArabic ? "إجمالي الإيرادات" : "Total Revenue");
                tvLabelGoldMetric.setText(isArabic ? "صافي الربح" : "Net Profit");
                loadIncomeStatementData();
                break;
            case GOLD_MOVEMENT:
                tvReportTitle.setText(isArabic ? "تقرير حركة الذهب" : "Gold Movement Report");
                tvLabelCashMetric.setText(isArabic ? "وارد الذهب (جرام)" : "Gold In (g)");
                tvLabelGoldMetric.setText(isArabic ? "صادر الذهب (جرام)" : "Gold Out (g)");
                loadGoldMovementData();
                break;
        }
    }

    private void loadBalanceSheetData() {
        executor.execute(() -> {
            int branchId = branchesManager.getActiveBranchId();
            MiraApiClient.ApiResponse response = apiClient.getBalanceSheet(String.valueOf(branchId), endDate);

            mainHandler.post(() -> {
                showLoading(false);
                if (response != null && response.isSuccess && response.data != null) {
                    parseBalanceSheet(response.data);
                } else {
                    showError(isArabic ? "فشل تحميل الميزانية" : "Failed to load balance sheet");
                }
            });
        });
    }

    private void loadIncomeStatementData() {
        executor.execute(() -> {
            int branchId = branchesManager.getActiveBranchId();
            MiraApiClient.ApiResponse response = apiClient.getIncomeStatement(String.valueOf(branchId), startDate, endDate);

            mainHandler.post(() -> {
                showLoading(false);
                if (response != null && response.isSuccess && response.data != null) {
                    parseIncomeStatement(response.data);
                } else {
                    showError(isArabic ? "فشل تحميل قائمة الدخل" : "Failed to load income statement");
                }
            });
        });
    }

    private void loadGoldMovementData() {
        executor.execute(() -> {
            int branchId = branchesManager.getActiveBranchId();
            MiraApiClient.ApiResponse response = apiClient.getGoldMovementReport(String.valueOf(branchId), startDate, endDate);

            mainHandler.post(() -> {
                showLoading(false);
                if (response != null && response.isSuccess && response.data != null) {
                    parseGoldMovement(response.data);
                } else {
                    showError(isArabic ? "فشل تحميل حركة الذهب" : "Failed to load gold movement");
                }
            });
        });
    }

    private void parseBalanceSheet(JSONObject data) {
        try {
            JSONObject root = data.optJSONObject("data") != null ? data.optJSONObject("data") : data;
            List<MiraReportRowItem> items = new ArrayList<>();

            // 1. الأصول
            JSONArray assets = root.optJSONArray("assets");
            if (assets != null && assets.length() > 0) {
                items.add(MiraReportRowItem.createHeader(isArabic ? "🏦 الأصول" : "🏦 Assets"));
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject obj = assets.getJSONObject(i);
                    items.add(new MiraReportRowItem(
                            obj.optString("name", "-"),
                            obj.optDouble("balance", 0.0),
                            obj.optDouble("gold_weight_24k", 0.0),
                            obj.optString("karat_details", "")
                    ));
                }
            }

            // 2. الخصوم
            JSONArray liabilities = root.optJSONArray("liabilities");
            if (liabilities != null && liabilities.length() > 0) {
                items.add(MiraReportRowItem.createHeader(isArabic ? "📋 الخصوم وحقوق الملكية" : "📋 Liabilities & Equity"));
                for (int i = 0; i < liabilities.length(); i++) {
                    JSONObject obj = liabilities.getJSONObject(i);
                    items.add(new MiraReportRowItem(
                            obj.optString("name", "-"),
                            obj.optDouble("balance", 0.0),
                            obj.optDouble("gold_weight_24k", 0.0),
                            obj.optString("karat_details", "")
                    ));
                }
            }

            // تحديث KPI المؤشرات
            double totalAssets = root.optDouble("total_assets", 0.0);
            double totalGold = root.optDouble("total_gold_assets", 0.0);
            tvValueCashMetric.setText(String.format(Locale.US, "%,.2f", totalAssets));
            tvValueGoldMetric.setText(String.format(Locale.US, "%.3f g", totalGold));

            // التوازن
            boolean balanced = root.optBoolean("is_balanced", false);
            cardStatusFooter.setVisibility(View.VISIBLE);
            tvReportStatus.setText(balanced ? 
                    (isArabic ? "✅ الحالة: الميزانية متوازنة" : "✅ Status: Balanced") : 
                    (isArabic ? "⚠️ الحالة: الميزانية غير متوازنة" : "⚠️ Status: Unbalanced"));

            updateUIWithItems(items);
        } catch (Exception e) {
            showError(isArabic ? "خطأ في قراءة بيانات الميزانية" : "Parsing Error");
        }
    }

    private void parseIncomeStatement(JSONObject data) {
        try {
            JSONObject root = data.optJSONObject("data") != null ? data.optJSONObject("data") : data;
            List<MiraReportRowItem> items = new ArrayList<>();

            double totalRevenues = 0;
            JSONArray revenues = root.optJSONArray("revenues");
            if (revenues != null && revenues.length() > 0) {
                items.add(MiraReportRowItem.createHeader(isArabic ? "💰 الإيرادات والمبيعات" : "💰 Revenues & Sales"));
                for (int i = 0; i < revenues.length(); i++) {
                    JSONObject obj = revenues.getJSONObject(i);
                    double amount = obj.optDouble("amount", 0.0);
                    totalRevenues += amount;
                    items.add(new MiraReportRowItem(
                            obj.optString("name", "-"),
                            amount,
                            obj.optDouble("gold_weight_24k", 0.0),
                            obj.optString("karat_details", "")
                    ));
                }
            }

            JSONArray expenses = root.optJSONArray("expenses");
            if (expenses != null && expenses.length() > 0) {
                items.add(MiraReportRowItem.createHeader(isArabic ? "📉 المصروفات والتكاليف" : "📉 Expenses"));
                for (int i = 0; i < expenses.length(); i++) {
                    JSONObject obj = expenses.getJSONObject(i);
                    items.add(new MiraReportRowItem(
                            obj.optString("name", "-"),
                            obj.optDouble("amount", 0.0),
                            0.0,
                            ""
                    ));
                }
            }

            double netProfit = root.optDouble("net_profit", 0.0);
            tvValueCashMetric.setText(String.format(Locale.US, "%,.2f", totalRevenues));
            tvValueGoldMetric.setText(String.format(Locale.US, "%,.2f", netProfit));

            cardStatusFooter.setVisibility(View.VISIBLE);
            tvReportStatus.setText(String.format(Locale.US, isArabic ? "صافي النتيجة المالية: %,.2f" : "Net Result: %,.2f", netProfit));

            updateUIWithItems(items);
        } catch (Exception e) {
            showError(isArabic ? "خطأ في معالجة قائمة الدخل" : "Parsing Error");
        }
    }

    private void parseGoldMovement(JSONObject data) {
        try {
            JSONObject root = data.optJSONObject("data") != null ? data.optJSONObject("data") : data;
            List<MiraReportRowItem> items = new ArrayList<>();

            double totalDebit = root.optDouble("total_debit_gold", 0.0);
            double totalCredit = root.optDouble("total_credit_gold", 0.0);
            double netMovement = root.optDouble("net_gold_movement", 0.0);

            tvValueCashMetric.setText(String.format(Locale.US, "%.3f g", totalDebit));
            tvValueGoldMetric.setText(String.format(Locale.US, "%.3f g", totalCredit));

            JSONArray details = root.optJSONArray("details");
            if (details != null && details.length() > 0) {
                items.add(MiraReportRowItem.createHeader(isArabic ? "💎 تفاصيل التداولات وحركة العيارات" : "💎 Gold Trades Breakdown"));
                for (int i = 0; i < details.length(); i++) {
                    JSONObject obj = details.getJSONObject(i);
                    items.add(new MiraReportRowItem(
                            obj.optString("account_name", "-"),
                            obj.optDouble("cash_equivalent", 0.0),
                            obj.optDouble("weight_24k", 0.0),
                            obj.optString("karat_description", "")
                    ));
                }
            }

            cardStatusFooter.setVisibility(View.VISIBLE);
            tvReportStatus.setText(String.format(Locale.US, isArabic ? "صافي حركة الذهب: %.3f جرام" : "Net Gold Movement: %.3f g", netMovement));

            updateUIWithItems(items);
        } catch (Exception e) {
            showError(isArabic ? "خطأ في قراءة بيانات حركة الذهب" : "Parsing Error");
        }
    }

    private void updateUIWithItems(List<MiraReportRowItem> items) {
        if (items.isEmpty()) {
            rvReportDetails.setVisibility(View.GONE);
            tvEmptyState.setVisibility(View.VISIBLE);
            tvEmptyState.setText(isArabic ? "لا توجد حركات مالية مسجلة لهذه الفترة" : "No records found for this period");
        } else {
            tvEmptyState.setVisibility(View.GONE);
            rvReportDetails.setVisibility(View.VISIBLE);
            adapter.updateItems(items);
        }
    }

    private void showLoading(boolean isLoading) {
        progressBar.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        if (isLoading) {
            rvReportDetails.setVisibility(View.GONE);
            tvEmptyState.setVisibility(View.GONE);
            cardStatusFooter.setVisibility(View.GONE);
        }
    }

    private void showError(String message) {
        showLoading(false);
        tvEmptyState.setVisibility(View.VISIBLE);
        tvEmptyState.setText(message);
        Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
}
