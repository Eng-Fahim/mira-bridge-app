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
import com.example.uhf.api.dto.IncomeStatementDto;
import com.example.uhf.utils.DateHelper;
import com.example.uhf.utils.NumberFormatter;
import com.example.uhf.viewmodel.AccountingViewModel;
import com.google.android.material.button.MaterialButton;

import java.util.Calendar;
import java.util.Locale;

/**
 * شاشة قائمة الدخل
 */
public class MiraIncomeStatementFragment extends Fragment {

    private AccountingViewModel viewModel;

    private MaterialButton btnFrom, btnTo;
    private LinearLayout kpiContainer, statementContainer;
    private ProgressBar pbLoading;

    private String fromDate = DateHelper.startOfYearApi();
    private String toDate = DateHelper.todayApi();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_income_statement, container, false);

        initViews(v);
        setupViewModel();
        setupListeners();
        updateButtons();

        viewModel.loadIncomeStatement(fromDate, toDate, 0);

        return v;
    }

    private void initViews(View v) {
        btnFrom = v.findViewById(R.id.btnFrom);
        btnTo = v.findViewById(R.id.btnTo);
        kpiContainer = v.findViewById(R.id.kpiContainer);
        statementContainer = v.findViewById(R.id.statementContainer);
        pbLoading = v.findViewById(R.id.pbLoading);
    }

    private void setupViewModel() {
        viewModel = new ViewModelProvider(this).get(AccountingViewModel.class);

        viewModel.getIncomeStatement().observe(getViewLifecycleOwner(), this::render);

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
        btnFrom.setOnClickListener(v -> pickDate(true));
        btnTo.setOnClickListener(v -> pickDate(false));
    }

    private void pickDate(boolean isFrom) {
        Calendar cal = Calendar.getInstance();
        try {
            String dateStr = isFrom ? fromDate : toDate;
            String[] parts = dateStr.split("-");
            cal.set(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) - 1, Integer.parseInt(parts[2]));
        } catch (Exception ignored) {}

        new DatePickerDialog(requireContext(),
            (view, year, month, dayOfMonth) -> {
                String date = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth);
                if (isFrom) fromDate = date;
                else toDate = date;
                updateButtons();
                viewModel.loadIncomeStatement(fromDate, toDate, 0);
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show();
    }

    private void updateButtons() {
        btnFrom.setText("من: " + DateHelper.toDisplay(fromDate));
        btnTo.setText("إلى: " + DateHelper.toDisplay(toDate));
    }

    private void render(IncomeStatementDto is) {
    if (is == null) return;

    kpiContainer.removeAllViews();
    addKpi("📈 الإيرادات", NumberFormatter.moneyShort(is.netRevenue), "#3498DB");
    addKpi("💵 التكلفة", NumberFormatter.moneyShort(is.cogs), "#E74C3C");
    addKpi("💰 الربح", NumberFormatter.moneyShort(is.netProfit), is.netProfit >= 0 ? "#27AE60" : "#C0392B");
    addKpi("📊 الهامش", NumberFormatter.percent(is.netProfitMargin), "#9B59B6");

    statementContainer.removeAllViews();

    // الإيرادات
    addSection(statementContainer, "الإيرادات");
    addRow(statementContainer, "مبيعات الذهب والمجوهرات", is.salesRevenue, false);
    addRow(statementContainer, "وزن مبيعات الذهب (جرام)", is.goldSalesRevenue, false); // ✅ الوزن
    
    if (is.salesDiscounts > 0) {
        addRow(statementContainer, "(-) مردودات وخصومات", -is.salesDiscounts, true);
    }
    addRowBold(statementContainer, "= صافي الإيرادات", is.netRevenue, "#3498DB");
    addRow(statementContainer, "أجور المصنعية", is.makingCharges, false);
    
    addDivider(statementContainer);

    // التكلفة
    addSection(statementContainer, "التكلفة");
    addRow(statementContainer, "(-) تكلفة المبيعات", -is.cogs, true);
    addRow(statementContainer, "وزن تكلفة المبيعات (جرام)", is.goldCogs, true); // ✅ الوزن
    
    addRowBold(statementContainer, "= الربح الإجمالي", is.grossProfit, "#27AE60");
    
    // ✅ ربح الذهب بالجرام
    addRowBold(statementContainer, "= صافي ربح الذهب (جرام)", is.goldGrossProfit, "#D4AF37");

    addDivider(statementContainer);

    // المصروفات التشغيلية
    addSection(statementContainer, "المصروفات التشغيلية");
    addRow(statementContainer, "(-) مصروفات تشغيلية", -is.operatingExpenses, true);
    addRow(statementContainer, "(-) إهلاك", -is.depreciation, true);
    addRowBold(statementContainer, "= الربح التشغيلي", is.operatingProfit, "#27AE60");

    addDivider(statementContainer);

    // الزكاة والصافي
    addSection(statementContainer, "النتيجة النهائية");
    addRow(statementContainer, "(-) الزكاة والضرائب", -is.zakat, true);
    addRowBig(statementContainer, "═ صافي الربح", is.netProfit, is.netProfit >= 0 ? "#27AE60" : "#C0392B");
}

    private void addSection(LinearLayout c, String title) {
        TextView tv = new TextView(getContext());
        tv.setText(title);
        tv.setTextColor(Color.parseColor("#7F8C8D"));
        tv.setTextSize(12);
        tv.setPadding(8, 12, 8, 4);
        c.addView(tv);
    }

    private void addRow(LinearLayout c, String label, double value, boolean subtract) {
        View row = LayoutInflater.from(getContext())
            .inflate(R.layout.item_income_row, c, false);

        TextView tvLabel = row.findViewById(R.id.tvLabel);
        TextView tvAmount = row.findViewById(R.id.tvAmount);

        tvLabel.setText(label);
        tvAmount.setText(NumberFormatter.moneyShort(Math.abs(value)));
        tvAmount.setTextColor(Color.parseColor(subtract ? "#E74C3C" : "#2C3E50"));

        c.addView(row);
    }

    private void addRowBold(LinearLayout c, String label, double value, String color) {
        View row = LayoutInflater.from(getContext())
            .inflate(R.layout.item_income_row, c, false);

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
        View row = LayoutInflater.from(getContext())
            .inflate(R.layout.item_income_row, c, false);

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
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1);
        params.setMargins(0, 8, 0, 8);
        divider.setLayoutParams(params);
        divider.setBackgroundColor(Color.parseColor("#E0E0E0"));
        c.addView(divider);
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
