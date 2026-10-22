package com.example.uhf.fragment;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.dto.TrialBalanceDto;
import com.example.uhf.utils.DateHelper;
import com.example.uhf.utils.NumberFormatter;
import com.example.uhf.viewmodel.AccountingViewModel;
import com.example.uhf.adapter.TrialBalanceAdapter;

import java.util.Locale;

public class MiraTrialBalanceFragment extends Fragment {

    private AccountingViewModel viewModel;

    private LinearLayout kpiContainer;
    private RecyclerView rvRows;
    private TextView tvEmpty;
    private TrialBalanceAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_trial_balance, container, false);

        kpiContainer = v.findViewById(R.id.kpiContainer);
        rvRows = v.findViewById(R.id.rvRows);
        tvEmpty = v.findViewById(R.id.tvEmpty);

        viewModel = new ViewModelProvider(this).get(AccountingViewModel.class);

        adapter = new TrialBalanceAdapter();
        rvRows.setLayoutManager(new LinearLayoutManager(getContext()));
        rvRows.setAdapter(adapter);

        viewModel.getTrialBalance().observe(getViewLifecycleOwner(), this::render);

        viewModel.loadTrialBalance(DateHelper.startOfYearApi(), DateHelper.todayApi(), 0);

        return v;
    }

    private void render(TrialBalanceDto tb) {
        if (tb == null) return;

        // ✅ 1. تحديث البطاقات العلوية (KPIs)
        kpiContainer.removeAllViews();

        // --- بطاقات مالية ---
        addKpi("مدين (مالي)", NumberFormatter.moneyShort(tb.totalDebit), "#3498DB");
        addKpi("دائن (مالي)", NumberFormatter.moneyShort(tb.totalCredit), "#E74C3C");
        addKpi("الحالة المالية", tb.balanced ? "✅ متوازن" : "❌ غير متوازن",
               tb.balanced ? "#27AE60" : "#C0392B");

        // --- بطاقات الذهب (الجديدة) ---
        // استخدام دالة مساعدة لتنسيق الأوزان
        String gDebitStr = String.format(Locale.US, "%.3f g", tb.totalGoldDebit);
        String gCreditStr = String.format(Locale.US, "%.3f g", tb.totalGoldCredit);
        
        // التحقق من توازن الذهب (الفرق أقل من 0.001 جرام)
        boolean goldBalanced = Math.abs(tb.totalGoldDebit - tb.totalGoldCredit) < 0.001;

        addKpi("ذهب مدين", gDebitStr, "#D4AF37"); // لون ذهبي
        addKpi("ذهب دائن", gCreditStr, "#D4AF37"); // لون ذهبي
        addKpi("الحالة الذهبية", goldBalanced ? "✅ متوازن" : "❌ غير متوازن",
               goldBalanced ? "#27AE60" : "#C0392B");

        addKpi("عدد الحسابات", String.valueOf(tb.rows != null ? tb.rows.size() : 0), "#9B59B6");

        // ✅ 2. الجدول
        if (tb.rows != null && !tb.rows.isEmpty()) {
            adapter.setItems(tb.rows);
            tvEmpty.setVisibility(View.GONE);
            rvRows.setVisibility(View.VISIBLE);
        } else {
            tvEmpty.setVisibility(View.VISIBLE);
            rvRows.setVisibility(View.GONE);
        }
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
