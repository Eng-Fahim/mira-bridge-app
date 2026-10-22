package com.example.uhf.fragment;

import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

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
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MiraTrialBalanceFragment extends Fragment {

    private AccountingViewModel viewModel;

    private LinearLayout kpiContainer;
    private RecyclerView rvRows;
    private TextView tvEmpty;
    private EditText etSearch;
    private TabLayout tabLayout;
    private TrialBalanceAdapter adapter;
    
    // لتخزين البيانات الأصلية لفلترتها بالبحث
    private List<TrialBalanceDto.TrialBalanceRow> originalRows = new ArrayList<>();
    private boolean isGoldView = false; // false = مالي، true = ذهبي

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_trial_balance, container, false);

        kpiContainer = v.findViewById(R.id.kpiContainer);
        rvRows = v.findViewById(R.id.rvRows);
        tvEmpty = v.findViewById(R.id.tvEmpty);
        etSearch = v.findViewById(R.id.etSearch);
        tabLayout = v.findViewById(R.id.tabLayout);

        viewModel = new ViewModelProvider(this).get(AccountingViewModel.class);

        adapter = new TrialBalanceAdapter();
        // ✅ إضافة مستمع للنقر على الحساب (Drill-down)
        adapter.setOnItemClickListener(row -> {
            if (getActivity() != null) {
                Toast.makeText(getContext(), "جاري فتح حركات الحساب: " + row.accountNameAr, Toast.LENGTH_SHORT).show();
                // هنا يتم الانتقال لشاشة دفتر الأستاذ (General Ledger)
                // MiraGeneralLedgerFragment ledgerFrag = MiraGeneralLedgerFragment.newInstance(row.accountId);
                // getActivity().getSupportFragmentManager().beginTransaction().replace(R.id.fragment_container, ledgerFrag).addToBackStack(null).commit();
            }
        });

        rvRows.setLayoutManager(new LinearLayoutManager(getContext()));
        rvRows.setAdapter(adapter);

        // ✅ مستمع البحث
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterData(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // ✅ مستمع تبديل التبويبات (مالي / ذهب)
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                isGoldView = (tab.getPosition() == 1);
                // تغيير ألوان البطاقات أو إخفاء المالية منها حسب التبويب
                if (viewModel.getTrialBalance().getValue() != null) {
                    renderKPIs(viewModel.getTrialBalance().getValue());
                }
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        viewModel.getTrialBalance().observe(getViewLifecycleOwner(), this::render);

        viewModel.loadTrialBalance(DateHelper.startOfYearApi(), DateHelper.todayApi(), 0);

        return v;
    }

    private void render(TrialBalanceDto tb) {
        if (tb == null) return;

        originalRows = tb.rows != null ? tb.rows : new ArrayList<>();
        renderKPIs(tb);
        filterData(etSearch.getText().toString()); // تطبيق فلتر البحث إن وجد
    }

    private void renderKPIs(TrialBalanceDto tb) {
        kpiContainer.removeAllViews();

        // بطاقات مشتركة
        addKpi("عدد الحسابات", String.valueOf(tb.rows != null ? tb.rows.size() : 0), "#9B59B6");

        if (!isGoldView) {
            // --- تبويب المالي ---
            addKpi("مدين (مالي)", NumberFormatter.moneyShort(tb.totalDebit), "#3498DB");
            addKpi("دائن (مالي)", NumberFormatter.moneyShort(tb.totalCredit), "#E74C3C");
            addKpi("الحالة المالية", tb.balanced ? "✅ متوازن" : "❌ غير متوازن",
                   tb.balanced ? "#27AE60" : "#C0392B");
        } else {
            // --- تبويب الذهب ---
            String gDebitStr = String.format(Locale.US, "%.3f g", tb.totalGoldDebit);
            String gCreditStr = String.format(Locale.US, "%.3f g", tb.totalGoldCredit);
            boolean goldBalanced = Math.abs(tb.totalGoldDebit - tb.totalGoldCredit) < 0.001;

            addKpi("ذهب مدين", gDebitStr, "#D4AF37");
            addKpi("ذهب دائن", gCreditStr, "#D4AF37");
            addKpi("الحالة الذهبية", goldBalanced ? "✅ متوازن" : "❌ غير متوازن",
                   goldBalanced ? "#27AE60" : "#C0392B");
        }
    }

    private void filterData(String query) {
        if (originalRows == null) return;
        
        List<TrialBalanceDto.TrialBalanceRow> filtered = new ArrayList<>();
        
        if (query.isEmpty()) {
            filtered.addAll(originalRows);
        } else {
            String lowerQuery = query.toLowerCase().trim();
            for (TrialBalanceDto.TrialBalanceRow row : originalRows) {
                if ((row.accountCode != null && row.accountCode.contains(lowerQuery)) ||
                    (row.accountNameAr != null && row.accountNameAr.toLowerCase().contains(lowerQuery))) {
                    filtered.add(row);
                }
            }
        }

        if (!filtered.isEmpty()) {
            adapter.setItems(filtered);
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
