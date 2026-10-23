package com.example.uhf.fragment;

import android.app.DatePickerDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.uhf.R;
import com.example.uhf.api.dto.BalanceSheetDto;
import com.example.uhf.api.dto.TrialBalanceDto;
import com.example.uhf.utils.DateHelper;
import com.example.uhf.utils.NumberFormatter;
import com.example.uhf.viewmodel.AccountingViewModel;
import com.google.android.material.button.MaterialButton;

import java.util.Calendar;
import java.util.Locale;

/**
 * شاشة الميزانية العمومية
 */
public class MiraBalanceSheetFragment extends Fragment {

    private AccountingViewModel viewModel;

    private MaterialButton btnAsOf;
    private LinearLayout kpiContainer;
    private TextView tvBalanceStatus;
    private LinearLayout assetsContainer, liabilitiesContainer, equityContainer;
    private TextView tvTotalAssets, tvTotalLiabilities, tvTotalEquity;
    private ProgressBar pbLoading;

    private String asOfDate = DateHelper.todayApi();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_balance_sheet, container, false);

        initViews(v);
        setupViewModel();
        setupListeners();
        updateDateButton();

        viewModel.loadBalanceSheet(asOfDate);

        return v;
    }

    private void initViews(View v) {
        btnAsOf = v.findViewById(R.id.btnAsOf);
        kpiContainer = v.findViewById(R.id.kpiContainer);
        tvBalanceStatus = v.findViewById(R.id.tvBalanceStatus);
        assetsContainer = v.findViewById(R.id.assetsContainer);
        liabilitiesContainer = v.findViewById(R.id.liabilitiesContainer);
        equityContainer = v.findViewById(R.id.equityContainer);
        tvTotalAssets = v.findViewById(R.id.tvTotalAssets);
        tvTotalLiabilities = v.findViewById(R.id.tvTotalLiabilities);
        tvTotalEquity = v.findViewById(R.id.tvTotalEquity);
        pbLoading = v.findViewById(R.id.pbLoading);
    }

    private void setupViewModel() {
        viewModel = new ViewModelProvider(this).get(AccountingViewModel.class);

        viewModel.getBalanceSheet().observe(getViewLifecycleOwner(), this::render);

        viewModel.getLoading().observe(getViewLifecycleOwner(), loading -> {
            pbLoading.setVisibility(loading != null && loading ? View.VISIBLE : View.GONE);
        });

        viewModel.getError().observe(getViewLifecycleOwner(), error -> {
            if (error != null && !error.isEmpty()) {
                Toast.makeText(requireContext(), "❌ " + error, Toast.LENGTH_SHORT).show();
                viewModel.clearError();
            }
        });
    }

    private void setupListeners() {
        btnAsOf.setOnClickListener(v -> pickDate());
    }

    private void pickDate() {
        Calendar cal = Calendar.getInstance();
        new DatePickerDialog(requireContext(),
            (view, year, month, dayOfMonth) -> {
                asOfDate = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth);
                updateDateButton();
                viewModel.loadBalanceSheet(asOfDate);
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show();
    }

    private void updateDateButton() {
        btnAsOf.setText("كما في: " + DateHelper.toDisplay(asOfDate));
    }

    private void render(BalanceSheetDto bs) {
    if (bs == null) return;

    // KPIs
    kpiContainer.removeAllViews();
    // ✅ إضافة الوزن بجانب الأصول
    String assetsText = NumberFormatter.moneyShort(bs.totalAssets);
    if (bs.totalGoldAssets > 0) assetsText += " (" + String.format(Locale.US, "%.1fg", bs.totalGoldAssets) + ")";
    
    addKpi("📊 الأصول", assetsText, "#3498DB");
    addKpi("📉 الالتزامات", NumberFormatter.moneyShort(bs.totalLiabilities), "#E74C3C");
    addKpi("💰 حقوق", NumberFormatter.moneyShort(bs.totalEquityWithIncome), "#9B59B6");
    addKpi("⚖️ الحالة", bs.balanced ? "✅" : "❌", bs.balanced ? "#27AE60" : "#C0392B");

    tvBalanceStatus.setText(bs.balanced ? "✅ الميزانية متوازنة" : "❌ غير متوازنة");
    tvBalanceStatus.setTextColor(Color.parseColor(bs.balanced ? "#27AE60" : "#C0392B"));

    // الأصول
    assetsContainer.removeAllViews();
    if (bs.assets != null) {
        for (TrialBalanceDto.TrialBalanceRow r : bs.assets) {
            addAccountRow(assetsContainer, r, "#3498DB");
        }
    }
    tvTotalAssets.setText("إجمالي الأصول: " + assetsText);
    tvTotalAssets.setTextColor(Color.parseColor("#3498DB"));

    // الالتزامات
    liabilitiesContainer.removeAllViews();
    if (bs.liabilities != null) {
        for (TrialBalanceDto.TrialBalanceRow r : bs.liabilities) {
            addAccountRow(liabilitiesContainer, r, "#E74C3C");
        }
    }
    tvTotalLiabilities.setText("إجمالي الالتزامات: " + NumberFormatter.moneyShort(bs.totalLiabilities));
    
    // حقوق الملكية
    equityContainer.removeAllViews();
    if (bs.equity != null) {
        for (TrialBalanceDto.TrialBalanceRow r : bs.equity) {
            addAccountRow(equityContainer, r, "#9B59B6");
        }
    }
    if (Math.abs(bs.netIncomePeriod) > 0.01) {
        addSpecialRow(equityContainer, "صافي ربح الفترة", bs.netIncomePeriod, "#27AE60");
    }
    tvTotalEquity.setText("إجمالي حقوق الملكية: " + NumberFormatter.moneyShort(bs.totalEquityWithIncome));
}

private void addAccountRow(LinearLayout container, TrialBalanceDto.TrialBalanceRow r, String color) {
    View row = LayoutInflater.from(getContext()).inflate(R.layout.item_bs_account, container, false);
    TextView tvName = row.findViewById(R.id.tvAccountName);
    TextView tvCode = row.findViewById(R.id.tvAccountCode);
    TextView tvBalance = row.findViewById(R.id.tvBalance);

    tvName.setText(r.accountNameAr);
    tvCode.setText(r.accountCode);
    
    // ✅ إضافة الوزن بجانب الرصيد المالي للحسابات الذهبية
    String balanceText = NumberFormatter.moneyShort(r.balance);
    if (Math.abs(r.goldBalance) > 0.001) {
        balanceText += "\n(" + String.format(Locale.US, "%.3f", r.goldBalance) + " جرام)";
    }
    
    tvBalance.setText(balanceText);
    tvBalance.setTextColor(Color.parseColor(color));
    tvBalance.setGravity(android.view.Gravity.END); // لمحاذاة النص

    container.addView(row);
}

    private void addSpecialRow(LinearLayout container, String name, double value, String color) {
        View row = LayoutInflater.from(getContext())
            .inflate(R.layout.item_bs_account, container, false);

        TextView tvName = row.findViewById(R.id.tvAccountName);
        TextView tvCode = row.findViewById(R.id.tvAccountCode);
        TextView tvBalance = row.findViewById(R.id.tvBalance);

        tvName.setText(name);
        tvName.setTextColor(Color.parseColor(color));
        tvCode.setText("—");
        tvBalance.setText(NumberFormatter.moneyShort(value));
        tvBalance.setTextColor(Color.parseColor(color));

        container.addView(row);
    }

    private void addKpi(String title, String value, String color) {
        View card = LayoutInflater.from(getContext())
            .inflate(R.layout.kpi_card, kpiContainer, false);

        TextView tvTitle = card.findViewById(R.id.tvKpiTitle);
        TextView tvValue = card.findViewById(R.id.tvKpiValue);
        View indicator = card.findViewById(R.id.kpiIndicator);

        tvTitle.setText(title);
        tvValue.setText(value);
        tvValue.setTextColor(Color.parseColor(color));
        indicator.setBackgroundColor(Color.parseColor(color));

        kpiContainer.addView(card);
    }
}
