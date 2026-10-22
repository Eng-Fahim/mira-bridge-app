package com.example.uhf.fragment;

import android.app.DatePickerDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
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
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.adapter.TrialBalanceAdapter;
import com.example.uhf.api.dto.TrialBalanceDto;
import com.example.uhf.utils.DateHelper;
import com.example.uhf.utils.NumberFormatter;
import com.example.uhf.viewmodel.AccountingViewModel;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class MiraTrialBalanceFragment extends Fragment {

    private AccountingViewModel viewModel;

    private LinearLayout kpiContainer;
    private RecyclerView rvRows;
    private TextView tvEmpty;
    private EditText etSearch;
    private Button btnFilterDate;
    private TabLayout tabLayout;
    private CheckBox cbLeafOnly; // ✅ فلتر عرض الحسابات الفرعية
    private TrialBalanceAdapter adapter;
    
    private List<TrialBalanceDto.TrialBalanceRow> originalRows = new ArrayList<>();
    private boolean isGoldView = false;

    // متغيرات التاريخ
    private String currentFromDate;
    private String currentToDate;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_trial_balance, container, false);

        // ربط العناصر
        kpiContainer = v.findViewById(R.id.kpiContainer);
        rvRows = v.findViewById(R.id.rvRows);
        tvEmpty = v.findViewById(R.id.tvEmpty);
        etSearch = v.findViewById(R.id.etSearch);
        btnFilterDate = v.findViewById(R.id.btnFilterDate);
        tabLayout = v.findViewById(R.id.tabLayout);
        cbLeafOnly = v.findViewById(R.id.cbLeafOnly); // يجب إضافة هذا العنصر في الـ XML

        viewModel = new ViewModelProvider(this).get(AccountingViewModel.class);

        // إعداد التواريخ الافتراضية (بداية السنة حتى اليوم)
        currentFromDate = DateHelper.startOfYearApi();
        currentToDate = DateHelper.todayApi();
        updateDateButtonText();

        adapter = new TrialBalanceAdapter();
        
        // ✅ الانتقال إلى دفتر الأستاذ عند النقر على الحساب
        adapter.setOnItemClickListener(row -> {
            if (getActivity() != null) {
                // تجهيز شاشة دفتر الأستاذ
                MiraGeneralLedgerFragment ledgerFragment = new MiraGeneralLedgerFragment();
                Bundle args = new Bundle();
                args.putLong("account_id", row.accountId); // تمرير معرف الحساب
                ledgerFragment.setArguments(args);

                // الانتقال للشاشة
                if (getActivity() instanceof UHFMainActivity) {
                    ((UHFMainActivity) getActivity()).loadFragment(ledgerFragment, "GeneralLedger");
                } else {
                    getParentFragmentManager().beginTransaction()
                        .replace(R.id.fragment_container, ledgerFragment)
                        .addToBackStack("GeneralLedger")
                        .commit();
                }
            }
        });

        rvRows.setLayoutManager(new LinearLayoutManager(getContext()));
        rvRows.setAdapter(adapter);

        // مستمع البحث
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterData(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // ✅ مستمع فلتر الحسابات الفرعية
        if (cbLeafOnly != null) {
            cbLeafOnly.setOnCheckedChangeListener((buttonView, isChecked) -> {
                filterData(etSearch.getText().toString());
            });
        }

        // مستمع تبديل التبويبات
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                isGoldView = (tab.getPosition() == 1);
                if (viewModel.getTrialBalance().getValue() != null) {
                    renderKPIs(viewModel.getTrialBalance().getValue());
                }
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        // مستمع زر الفترة (Date Picker)
        btnFilterDate.setOnClickListener(v1 -> showDateRangePicker());

        viewModel.getTrialBalance().observe(getViewLifecycleOwner(), this::render);

        // تحميل البيانات لأول مرة
        viewModel.loadTrialBalance(currentFromDate, currentToDate, 0);

        return v;
    }

    private void showDateRangePicker() {
        Calendar cal = Calendar.getInstance();
        
        // اختيار تاريخ البداية
        new DatePickerDialog(requireContext(), (view, year, month, dayOfMonth) -> {
            Calendar startCal = Calendar.getInstance();
            startCal.set(year, month, dayOfMonth);
            
            // بعد اختيار البداية، نفتح نافذة لاختيار النهاية
            new DatePickerDialog(requireContext(), (view2, year2, month2, dayOfMonth2) -> {
                Calendar endCal = Calendar.getInstance();
                endCal.set(year2, month2, dayOfMonth2);

                // تنسيق التواريخ بصيغة YYYY-MM-DD
                currentFromDate = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth);
                currentToDate = String.format(Locale.US, "%04d-%02d-%02d", year2, month2 + 1, dayOfMonth2);

                updateDateButtonText();
                viewModel.loadTrialBalance(currentFromDate, currentToDate, 0);
                
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();

        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void updateDateButtonText() {
        String shortFrom = currentFromDate.substring(5); // إزالة السنة
        String shortTo = currentToDate.substring(5);
        btnFilterDate.setText(shortFrom + " إلى " + shortTo);
    }

    private void render(TrialBalanceDto tb) {
        if (tb == null) return;

        originalRows = tb.rows != null ? tb.rows : new ArrayList<>();
        renderKPIs(tb);
        filterData(etSearch.getText().toString());
    }

    private void renderKPIs(TrialBalanceDto tb) {
        kpiContainer.removeAllViews();

        addKpi("عدد الحسابات", String.valueOf(tb.rows != null ? tb.rows.size() : 0), "#9B59B6");

        if (!isGoldView) {
            addKpi("مدين (مالي)", NumberFormatter.moneyShort(tb.totalDebit), "#3498DB");
            addKpi("دائن (مالي)", NumberFormatter.moneyShort(tb.totalCredit), "#E74C3C");
            addKpi("الحالة المالية", tb.balanced ? "✅ متوازن" : "❌ غير متوازن",
                   tb.balanced ? "#27AE60" : "#C0392B");
        } else {
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
        boolean leafOnly = cbLeafOnly != null && cbLeafOnly.isChecked();
        
        for (TrialBalanceDto.TrialBalanceRow row : originalRows) {
            // ✅ 1. فلتر الحسابات الفرعية فقط (Leaf Only)
            if (leafOnly && !row.isLeaf) {
                continue;
            }
            
            // ✅ 2. فلتر البحث
            if (!query.isEmpty()) {
                String lowerQuery = query.toLowerCase().trim();
                boolean matchesCode = row.accountCode != null && row.accountCode.contains(lowerQuery);
                boolean matchesName = row.accountNameAr != null && row.accountNameAr.toLowerCase().contains(lowerQuery);
                
                if (!matchesCode && !matchesName) {
                    continue;
                }
            }
            
            filtered.add(row);
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
