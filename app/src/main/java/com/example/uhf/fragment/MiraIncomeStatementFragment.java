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
import com.example.uhf.api.dto.IncomeStatementDto;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.utils.DateHelper;
import com.example.uhf.utils.NumberFormatter;
import com.example.uhf.viewmodel.AccountingViewModel;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class MiraIncomeStatementFragment extends Fragment {

    private AccountingViewModel viewModel;

    private MaterialButton btnFrom, btnTo, btnExportIncome;
    private Spinner spinnerBranchIncome;
    private LinearLayout kpiContainer, statementContainer;
    private ProgressBar pbLoading;
    private Chip chipIncomeThisMonth, chipIncomeThisQuarter, chipIncomeThisYear;

    private String fromDate = DateHelper.startOfYearApi();
    private String toDate = DateHelper.todayApi();
    private int currentBranchId = 0;

    private final List<Integer> branchIds = new ArrayList<>();
    private final List<String> branchNames = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_income_statement, container, false);
        initViews(v);
        setupViewModel();
        setupBranchSpinner();
        setupListeners();
        updateButtons();
        viewModel.loadIncomeStatement(fromDate, toDate, currentBranchId);
        return v;
    }

    private void initViews(View v) {
        btnFrom = v.findViewById(R.id.btnFrom);
        btnTo = v.findViewById(R.id.btnTo);
        btnExportIncome = v.findViewById(R.id.btnExportIncome);
        spinnerBranchIncome = v.findViewById(R.id.spinnerBranchIncome);
        kpiContainer = v.findViewById(R.id.kpiContainer);
        statementContainer = v.findViewById(R.id.statementContainer);
        pbLoading = v.findViewById(R.id.pbLoading);
        chipIncomeThisMonth = v.findViewById(R.id.chipIncomeThisMonth);
        chipIncomeThisQuarter = v.findViewById(R.id.chipIncomeThisQuarter);
        chipIncomeThisYear = v.findViewById(R.id.chipIncomeThisYear);
    }

    private void setupViewModel() {
        viewModel = new ViewModelProvider(this).get(AccountingViewModel.class);
        viewModel.getIncomeStatement().observe(getViewLifecycleOwner(), this::render);
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
        spinnerBranchIncome.setAdapter(adapter);

        spinnerBranchIncome.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < branchIds.size()) {
                    currentBranchId = branchIds.get(position);
                    viewModel.loadIncomeStatement(fromDate, toDate, currentBranchId);
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
    }

    private void setupListeners() {
        btnFrom.setOnClickListener(v -> pickDate(true));
        btnTo.setOnClickListener(v -> pickDate(false));
        chipIncomeThisMonth.setOnClickListener(v -> applyPreset("month"));
        chipIncomeThisQuarter.setOnClickListener(v -> applyPreset("quarter"));
        chipIncomeThisYear.setOnClickListener(v -> applyPreset("year"));
        btnExportIncome.setOnClickListener(v -> {
            IncomeStatementDto data = viewModel.getIncomeStatement().getValue();
            if (data != null) {
                Toast.makeText(getContext(), "📥 سيتم تطوير التصدير PDF قريباً", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void applyPreset(String preset) {
        Calendar cal = Calendar.getInstance();
        switch (preset) {
            case "month":
                cal.set(Calendar.DAY_OF_MONTH, 1);
                break;
            case "quarter": {
                int month = cal.get(Calendar.MONTH);
                cal.set(Calendar.MONTH, (month / 3) * 3);
                cal.set(Calendar.DAY_OF_MONTH, 1);
                break;
            }
            case "year":
                cal.set(Calendar.DAY_OF_YEAR, 1);
                break;
        }
        fromDate = formatDate(cal);
        toDate = DateHelper.todayApi();
        updateButtons();
        viewModel.loadIncomeStatement(fromDate, toDate, currentBranchId);
    }

    private String formatDate(Calendar c) {
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    private void pickDate(boolean isFrom) {
        Calendar cal = Calendar.getInstance();
        try {
            String dateStr = isFrom ? fromDate : toDate;
            String[] parts = dateStr.split("-");
            cal.set(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) - 1, Integer.parseInt(parts[2]));
        } catch (Exception ignored) {}

        new DatePickerDialog(requireContext(), (view, year, month, dayOfMonth) -> {
            String date = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth);
            if (isFrom) fromDate = date; else toDate = date;
            updateButtons();
            viewModel.loadIncomeStatement(fromDate, toDate, currentBranchId);
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void updateButtons() {
        btnFrom.setText("من: " + DateHelper.toDisplay(fromDate));
        btnTo.setText("إلى: " + DateHelper.toDisplay(toDate));
    }

    private void render(IncomeStatementDto is) {
        if (is == null) return;

        kpiContainer.removeAllViews();
        addKpi("📈 صافي الإيرادات", NumberFormatter.moneyShort(is.netRevenue), "#3498DB");
        addKpi("💵 التكلفة", NumberFormatter.moneyShort(is.cogs), "#E74C3C");
        addKpi("💰 صافي الربح", NumberFormatter.moneyShort(is.netProfit), is.netProfit >= 0 ? "#27AE60" : "#C0392B");
        addKpi("📊 الهامش", NumberFormatter.percent(is.netProfitMargin), "#9B59B6");

        statementContainer.removeAllViews();

        // ─── الإيرادات ───
        addSection(statementContainer, "📈 الإيرادات");
        addRow(statementContainer, "مبيعات الذهب والمجوهرات", is.salesRevenue, false);
        addRow(statementContainer, "أجور المصنعية", is.makingCharges, false);
        if (is.otherRevenue > 0) addRow(statementContainer, "إيرادات أخرى", is.otherRevenue, false);
        if (is.salesDiscounts > 0) addRow(statementContainer, "(-) مردودات وخصومات", -is.salesDiscounts, true);
        addRowBold(statementContainer, "= صافي الإيرادات", is.netRevenue, "#3498DB");
        addRow(statementContainer, "⚖️ وزن مبيعات الذهب (جرام)", is.goldSalesRevenue, false);

        addDivider(statementContainer);

        // ─── التكلفة ───
        addSection(statementContainer, "💵 التكلفة");
        addRow(statementContainer, "(-) تكلفة المبيعات", -is.cogs, true);
        addRow(statementContainer, "⚖️ وزن تكلفة المبيعات (جرام)", is.goldCogs, true);
        addRowBold(statementContainer, "= الربح الإجمالي", is.grossProfit, "#27AE60");
        addRow(statementContainer, "⚖️ ربح الذهب (جرام)", is.goldGrossProfit, false);

        addDivider(statementContainer);

        // ─── المصروفات التشغيلية ───
        addSection(statementContainer, "📉 المصروفات التشغيلية");
        addRow(statementContainer, "(-) مصروفات تشغيلية", -is.operatingExpenses, true);
        if (is.depreciation > 0) addRow(statementContainer, "(-) إهلاك", -is.depreciation, true);
        addRowBold(statementContainer, "= الربح التشغيلي", is.operatingProfit, "#27AE60");

        addDivider(statementContainer);

        // ─── النتيجة النهائية ───
        addSection(statementContainer, "🏆 النتيجة النهائية");
        if (is.zakat > 0) addRow(statementContainer, "(-) الزكاة والضرائب", -is.zakat, true);
        addRowBig(statementContainer, "═ صافي الربح", is.netProfit, is.netProfit >= 0 ? "#27AE60" : "#C0392B");
    }

    private void addSection(LinearLayout c, String title) {
        TextView tv = new TextView(getContext());
        tv.setText(title);
        tv.setTextColor(Color.parseColor("#7F8C8D"));
        tv.setTextSize(12);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setPadding(8, 12, 8, 4);
        c.addView(tv);
    }

    private void addRow(LinearLayout c, String label, double value, boolean subtract) {
        View row = LayoutInflater.from(getContext()).inflate(R.layout.item_income_row, c, false);
        TextView tvLabel = row.findViewById(R.id.tvLabel);
        TextView tvAmount = row.findViewById(R.id.tvAmount);
        tvLabel.setText(label);
        tvAmount.setText(NumberFormatter.moneyShort(Math.abs(value)));
        tvAmount.setTextColor(Color.parseColor(subtract ? "#E74C3C" : "#2C3E50"));
        c.addView(row);
    }

    private void addRowBold(LinearLayout c, String label, double value, String color) {
        View row = LayoutInflater.from(getContext()).inflate(R.layout.item_income_row, c, false);
        TextView tvLabel = row.findViewById(R.id.tvLabel);
        TextView tvAmount = row.findViewById(R.id.tvAmount);
        tvLabel.setText(label);
        tvLabel.setTextColor(Color.parseColor(color));
        tvLabel.setTextSize(14);
        tvLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        tvAmount.setText(NumberFormatter.moneyShort(value));
        tvAmount.setTextColor(Color.parseColor(color));
        tvAmount.setTextSize(14);
        tvAmount.setTypeface(null, android.graphics.Typeface.BOLD);
        row.setBackgroundColor(Color.parseColor("#F0F0F1"));
        c.addView(row);
    }

    private void addRowBig(LinearLayout c, String label, double value, String color) {
        View row = LayoutInflater.from(getContext()).inflate(R.layout.item_income_row, c, false);
        TextView tvLabel = row.findViewById(R.id.tvLabel);
        TextView tvAmount = row.findViewById(R.id.tvAmount);
        tvLabel.setText(label);
        tvLabel.setTextColor(Color.parseColor(color));
        tvLabel.setTextSize(16);
        tvLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        tvAmount.setText(NumberFormatter.moneyShort(value));
        tvAmount.setTextColor(Color.parseColor(color));
        tvAmount.setTextSize(16);
        tvAmount.setTypeface(null, android.graphics.Typeface.BOLD);
        c.addView(row);
    }

    private void addDivider(LinearLayout c) {
        View divider = new View(getContext());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        params.setMargins(0, 8, 0, 8);
        divider.setLayoutParams(params);
        divider.setBackgroundColor(Color.parseColor("#E0E0E0"));
        c.addView(divider);
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
