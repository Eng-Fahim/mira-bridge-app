package com.example.uhf.fragment;

import android.app.DatePickerDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.uhf.R;
import com.example.uhf.api.dto.BalanceSheetDto;
import com.example.uhf.api.dto.TrialBalanceDto;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.utils.DateHelper;
import com.example.uhf.utils.NumberFormatter;
import com.example.uhf.viewmodel.AccountingViewModel;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class MiraBalanceSheetFragment extends Fragment {

    private AccountingViewModel viewModel;

    private MaterialButton btnAsOf;
    private Spinner spinnerBranchBalance;
    private LinearLayout kpiContainer;
    private TextView tvBalanceStatus;
    private LinearLayout assetsContainer, liabilitiesContainer, equityContainer;
    private TextView tvTotalAssets, tvTotalLiabilities, tvTotalEquity;
    private ProgressBar pbLoading;

    private String asOfDate = DateHelper.todayApi();
    private int currentBranchId = 0;

    private final List<Integer> branchIds = new ArrayList<>();
    private final List<String> branchNames = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_balance_sheet, container, false);

        initViews(v);
        setupViewModel();
        setupBranchSpinner();
        setupListeners();
        updateDateButton();

        viewModel.loadBalanceSheet(asOfDate, currentBranchId);

        return v;
    }

    private void initViews(View v) {
        btnAsOf = v.findViewById(R.id.btnAsOf);
        spinnerBranchBalance = v.findViewById(R.id.spinnerBranchBalance);
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
            if (pbLoading != null) pbLoading.setVisibility(loading != null && loading ? View.VISIBLE : View.GONE);
        });
        viewModel.getError().observe(getViewLifecycleOwner(), error -> {
            if (error != null && !error.isEmpty() && isAdded()) {
                Toast.makeText(requireContext(), "❌ " + error, Toast.LENGTH_SHORT).show();
                viewModel.clearError();
            }
        });
    }

    private void setupBranchSpinner() {
        branchNames.clear();
        branchIds.clear();
        branchNames.add("كل الفروع");
        branchIds.add(0);
        try {
            MiraBranchesManager bm = MiraBranchesManager.getInstance(requireContext());
            int activeId = bm.getActiveBranchId();
            String activeName = bm.getActiveBranchDisplayName();
            if (activeId > 0) {
                branchIds.add(activeId);
                branchNames.add(activeName != null ? activeName : "الفرع الحالي");
            }
        } catch (Exception ignored) {}

        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, branchNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerBranchBalance.setAdapter(adapter);

        spinnerBranchBalance.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < branchIds.size()) {
                    currentBranchId = branchIds.get(position);
                    viewModel.loadBalanceSheet(asOfDate, currentBranchId);
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
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
                viewModel.loadBalanceSheet(asOfDate, currentBranchId);
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show();
    }

    private void updateDateButton() {
        btnAsOf.setText("كما في: " + DateHelper.toDisplay(asOfDate));
    }

    private void render(BalanceSheetDto bs) {
        if (bs == null) return;

        // ─── KPIs ───
        kpiContainer.removeAllViews();

        String assetsText = NumberFormatter.moneyShort(bs.totalAssets);
        if (bs.totalGoldAssets > 0) {
            assetsText += " (" + String.format(Locale.US, "%.1fg", bs.totalGoldAssets) + ")";
        }

        addKpi("📊 الأصول", assetsText, "#3498DB");
        addKpi("📉 الالتزامات", NumberFormatter.moneyShort(bs.totalLiabilities), "#E74C3C");
        addKpi("💰 الملكية", NumberFormatter.moneyShort(bs.totalEquityWithIncome), "#9B59B6");
        addKpi("⚖️ الحالة", bs.balanced ? "✅" : "❌", bs.balanced ? "#27AE60" : "#C0392B");

        // ─── Balance Banner ───
        if (bs.balanced) {
            tvBalanceStatus.setText("✅ الميزانية متوازنة — الأصول = الخصوم + الملكية");
            tvBalanceStatus.setTextColor(0xFF16A34A);
            tvBalanceStatus.setBackgroundColor(0xFFF0FDF4);
        } else {
            double diff = Math.abs(bs.totalAssets - (bs.totalLiabilities + bs.totalEquityWithIncome));
            tvBalanceStatus.setText(String.format(Locale.US, "⚠️ غير متوازنة — الفرق: %,.2f", diff));
            tvBalanceStatus.setTextColor(0xFFDC2626);
            tvBalanceStatus.setBackgroundColor(0xFFFEF2F2);
        }

        // ─── Assets ───
        renderAccounts(assetsContainer, bs.assets, "#3498DB");
        tvTotalAssets.setText("إجمالي الأصول: " + assetsText);

        // ─── Liabilities ───
        renderAccounts(liabilitiesContainer, bs.liabilities, "#E74C3C");
        String liabText = NumberFormatter.moneyShort(bs.totalLiabilities);
        if (bs.totalGoldLiabilities > 0) {
            liabText += " (" + String.format(Locale.US, "%.1fg", bs.totalGoldLiabilities) + ")";
        }
        tvTotalLiabilities.setText("إجمالي الالتزامات: " + liabText);

        // ─── Equity ───
        renderAccounts(equityContainer, bs.equity, "#9B59B6");
        if (Math.abs(bs.netIncomePeriod) > 0.01) {
            addSpecialRow(equityContainer, "صافي ربح الفترة", bs.netIncomePeriod, "#27AE60");
        }
        tvTotalEquity.setText("إجمالي حقوق الملكية: " + NumberFormatter.moneyShort(bs.totalEquityWithIncome));
    }

    private void renderAccounts(LinearLayout container, List<TrialBalanceDto.TrialBalanceRow> accounts, String color) {
        container.removeAllViews();
        if (accounts == null || accounts.isEmpty()) {
            TextView tv = new TextView(requireContext());
            tv.setText("لا توجد بيانات");
            tv.setTextColor(0xFFAAAAAA);
            tv.setTextSize(11);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setPadding(0, 12, 0, 12);
            container.addView(tv);
            return;
        }

        for (TrialBalanceDto.TrialBalanceRow r : accounts) {
            if (Math.abs(r.balance) < 0.01) continue;
            addAccountRow(container, r, color);
        }
    }

    private void addAccountRow(LinearLayout container, TrialBalanceDto.TrialBalanceRow r, String color) {
        View row = LayoutInflater.from(getContext()).inflate(R.layout.item_bs_account, container, false);
        TextView tvName = row.findViewById(R.id.tvAccountName);
        TextView tvCode = row.findViewById(R.id.tvAccountCode);
        TextView tvBalance = row.findViewById(R.id.tvBalance);

        tvName.setText(r.accountNameAr != null ? r.accountNameAr : "—");
        tvCode.setText(r.accountCode != null ? r.accountCode : "—");

        String balanceText = NumberFormatter.moneyShort(r.balance);
        if (Math.abs(r.goldBalance) > 0.001) {
            balanceText += "\n(" + String.format(Locale.US, "%.3f g", r.goldBalance) + ")";
        }

        tvBalance.setText(balanceText);
        tvBalance.setTextColor(Color.parseColor(color));
        tvBalance.setGravity(android.view.Gravity.END);

        container.addView(row);
    }

    private void addSpecialRow(LinearLayout container, String name, double value, String color) {
        View row = LayoutInflater.from(getContext()).inflate(R.layout.item_bs_account, container, false);

        TextView tvName = row.findViewById(R.id.tvAccountName);
        TextView tvCode = row.findViewById(R.id.tvAccountCode);
        TextView tvBalance = row.findViewById(R.id.tvBalance);

        tvName.setText(name);
        tvName.setTextColor(Color.parseColor(color));
        tvName.setTypeface(null, android.graphics.Typeface.BOLD);
        tvCode.setText("—");
        tvBalance.setText(NumberFormatter.moneyShort(value));
        tvBalance.setTextColor(Color.parseColor(color));
        tvBalance.setTypeface(null, android.graphics.Typeface.BOLD);

        container.addView(row);
    }

    private void addKpi(String title, String value, String color) {
        View card = LayoutInflater.from(getContext()).inflate(R.layout.kpi_card, kpiContainer, false);
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
