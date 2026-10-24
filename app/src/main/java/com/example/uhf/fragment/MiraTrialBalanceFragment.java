package com.example.uhf.fragment;

import android.app.DatePickerDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
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
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.utils.DateHelper;
import com.example.uhf.utils.NumberFormatter;
import com.example.uhf.utils.TrialBalanceCsvExporter;
import com.example.uhf.viewmodel.AccountingViewModel;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.tabs.TabLayout;

import org.json.JSONArray;

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
    private Button btnFilterDate, btnExport;
    private TabLayout tabLayout;
    private CheckBox cbLeafOnly;
    private Spinner spinnerBranch;
    private ProgressBar pbLoading;
    private TrialBalanceAdapter adapter;

    // Balance banner
    private MaterialCardView cardBalanceStatus;
    private TextView tvBalanceIcon, tvBalanceText;

    // Footer totals
    private TextView tvFooterOpen, tvFooterPeriod, tvFooterClose;

    // Quick chips
    private Chip chipThisMonth, chipThisQuarter, chipThisYear, chipToday;

    private List<TrialBalanceDto.TrialBalanceRow> originalRows = new ArrayList<>();
    private boolean isGoldView = false;

    private String currentFromDate;
    private String currentToDate;
    private int currentBranchId = 0;
    
    private SwitchMaterial switchHideZero; // ✅ المتغير الجديد

    // Dynamic branches
    private final List<Integer> branchIds = new ArrayList<>();
    private final List<String> branchNames = new ArrayList<>();

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
        btnFilterDate = v.findViewById(R.id.btnFilterDate);
        btnExport = v.findViewById(R.id.btnExport);
        tabLayout = v.findViewById(R.id.tabLayout);
        cbLeafOnly = v.findViewById(R.id.cbLeafOnly);
        switchHideZero = v.findViewById(R.id.switchHideZero);
        spinnerBranch = v.findViewById(R.id.spinnerBranch);
        pbLoading = v.findViewById(R.id.pbLoading);
        cardBalanceStatus = v.findViewById(R.id.cardBalanceStatus);
        tvBalanceIcon = v.findViewById(R.id.tvBalanceIcon);
        tvBalanceText = v.findViewById(R.id.tvBalanceText);
        tvFooterOpen = v.findViewById(R.id.tvFooterOpen);
        tvFooterPeriod = v.findViewById(R.id.tvFooterPeriod);
        tvFooterClose = v.findViewById(R.id.tvFooterClose);
        chipThisMonth = v.findViewById(R.id.chipThisMonth);
        chipThisQuarter = v.findViewById(R.id.chipThisQuarter);
        chipThisYear = v.findViewById(R.id.chipThisYear);
        chipToday = v.findViewById(R.id.chipToday);

        viewModel = new ViewModelProvider(this).get(AccountingViewModel.class);

        currentFromDate = DateHelper.startOfYearApi();
        currentToDate = DateHelper.todayApi();
        updateDateButtonText();
        setupBranchSpinner();

        adapter = new TrialBalanceAdapter();
        adapter.setOnItemClickListener(row -> {
            if (getActivity() != null) {
                MiraGeneralLedgerFragment ledgerFragment = new MiraGeneralLedgerFragment();
                Bundle args = new Bundle();
                args.putLong("account_id", row.accountId);
                ledgerFragment.setArguments(args);

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

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterData(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        if (cbLeafOnly != null) {
            cbLeafOnly.setOnCheckedChangeListener((buttonView, isChecked) -> filterData(etSearch.getText().toString()));
        }
        if (switchHideZero != null) {
           switchHideZero.setOnCheckedChangeListener((buttonView, isChecked) -> filterData(etSearch.getText().toString()));
           }

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                // ✅ إصلاح: تحديد النوع بناءً على اسم التبويب وليس رقمه
                String tabText = tab.getText() != null ? tab.getText().toString() : "";
                isGoldView = tabText.contains("ذهب");

                if (adapter != null) {
                    adapter.setGoldView(isGoldView);
                }

                if (viewModel.getTrialBalance().getValue() != null) {
                    renderKPIs(viewModel.getTrialBalance().getValue());
                    updateFooter(viewModel.getTrialBalance().getValue());
                }

                filterData(etSearch.getText().toString());
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        btnFilterDate.setOnClickListener(v1 -> showDateRangePicker());

        btnExport.setOnClickListener(v1 -> {
            List<TrialBalanceDto.TrialBalanceRow> currentDisplayedRows = adapter.getItems();
            if (currentDisplayedRows != null && !currentDisplayedRows.isEmpty()) {
                TrialBalanceCsvExporter.exportAndShare(requireContext(), currentDisplayedRows, currentFromDate, currentToDate);
            } else {
                Toast.makeText(getContext(), "لا توجد بيانات لتصديرها", Toast.LENGTH_SHORT).show();
            }
        });

        chipThisMonth.setOnClickListener(v1 -> applyPreset("month"));
        chipThisQuarter.setOnClickListener(v1 -> applyPreset("quarter"));
        chipThisYear.setOnClickListener(v1 -> applyPreset("year"));
        chipToday.setOnClickListener(v1 -> applyPreset("today"));

        viewModel.getLoading().observe(getViewLifecycleOwner(), isLoading -> {
            if (pbLoading != null) pbLoading.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        });

        viewModel.getTrialBalance().observe(getViewLifecycleOwner(), this::render);

        loadData();
        return v;
    }

    private void setupBranchSpinner() {
        branchNames.clear();
        branchIds.clear();
        branchNames.add("كل الفروع");
        branchIds.add(0);

        try {
            MiraBranchesManager bm = MiraBranchesManager.getInstance(requireContext());
            org.json.JSONArray branches = null;
            try {
                java.lang.reflect.Method m = bm.getClass().getMethod("getBranches");
                Object result = m.invoke(bm);
                if (result instanceof JSONArray) branches = (JSONArray) result;
            } catch (Exception ignored) {}

            if (branches != null) {
                for (int i = 0; i < branches.length(); i++) {
                    org.json.JSONObject b = branches.optJSONObject(i);
                    if (b != null) {
                        branchIds.add(b.optInt("id", 0));
                        branchNames.add(b.optString("branch_name_ar", b.optString("branch_name", "فرع")));
                    }
                }
            } else {
                int activeId = bm.getActiveBranchId();
                String activeName = bm.getActiveBranchDisplayName();
                if (activeId > 0) {
                    branchIds.add(activeId);
                    branchNames.add(activeName != null ? activeName : "الفرع الحالي");
                }
            }
        } catch (Exception e) {
            branchNames.add("الفرع الرئيسي");
            branchIds.add(1);
        }

        ArrayAdapter<String> branchAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, branchNames);
        branchAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerBranch.setAdapter(branchAdapter);

        spinnerBranch.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < branchIds.size()) {
                    currentBranchId = branchIds.get(position);
                    loadData();
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
    }

    private void applyPreset(String preset) {
        Calendar cal = Calendar.getInstance();
        switch (preset) {
            case "today":
                currentFromDate = currentToDate = formatDate(cal);
                break;
            case "month":
                cal.set(Calendar.DAY_OF_MONTH, 1);
                currentFromDate = formatDate(cal);
                currentToDate = DateHelper.todayApi();
                break;
            case "quarter": {
                int month = cal.get(Calendar.MONTH);
                int qStart = (month / 3) * 3;
                cal.set(Calendar.MONTH, qStart);
                cal.set(Calendar.DAY_OF_MONTH, 1);
                currentFromDate = formatDate(cal);
                currentToDate = DateHelper.todayApi();
                break;
            }
            case "year":
                cal.set(Calendar.DAY_OF_YEAR, 1);
                currentFromDate = formatDate(cal);
                currentToDate = DateHelper.todayApi();
                break;
        }
        updateDateButtonText();
        loadData();
    }

    private String formatDate(Calendar c) {
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    private void loadData() {
        viewModel.loadTrialBalance(currentFromDate, currentToDate, currentBranchId);
    }

    private void showDateRangePicker() {
        Calendar cal = Calendar.getInstance();
        new DatePickerDialog(requireContext(), (view, year, month, dayOfMonth) -> {
            new DatePickerDialog(requireContext(), (view2, year2, month2, dayOfMonth2) -> {
                currentFromDate = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth);
                currentToDate = String.format(Locale.US, "%04d-%02d-%02d", year2, month2 + 1, dayOfMonth2);
                updateDateButtonText();
                loadData();
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void updateDateButtonText() {
        String shortFrom = currentFromDate.length() >= 10 ? currentFromDate.substring(5) : currentFromDate;
        String shortTo = currentToDate.length() >= 10 ? currentToDate.substring(5) : currentToDate;
        btnFilterDate.setText(shortFrom + " → " + shortTo);
    }

    private void render(TrialBalanceDto tb) {
        if (tb == null) return;
        originalRows = tb.rows != null ? tb.rows : new ArrayList<>();
        renderKPIs(tb);
        updateBalanceBanner(tb);
        updateFooter(tb); // ✅ سيتم تحديث التذييل بناءً على التبويب
        filterData(etSearch.getText().toString());
    }

    private void updateBalanceBanner(TrialBalanceDto tb) {
        if (cardBalanceStatus == null) return;
        
        boolean isBalanced = isGoldView 
            ? Math.abs(tb.totalGoldDebit - tb.totalGoldCredit) < 0.001 
            : tb.balanced;
            
        if (isBalanced) {
            cardBalanceStatus.setCardBackgroundColor(0xFFF0FDF4);
            cardBalanceStatus.setStrokeColor(android.content.res.ColorStateList.valueOf(0xFF16A34A));
            tvBalanceIcon.setText("✅");
            tvBalanceText.setText("القيد متوازن — المدين = الدائن");
            tvBalanceText.setTextColor(0xFF16A34A);
        } else {
            cardBalanceStatus.setCardBackgroundColor(0xFFFEF2F2);
            cardBalanceStatus.setStrokeColor(android.content.res.ColorStateList.valueOf(0xFFDC2626));
            tvBalanceIcon.setText("⚠️");
            double diff = isGoldView 
                ? Math.abs(tb.totalGoldDebit - tb.totalGoldCredit) 
                : Math.abs(tb.totalDebit - tb.totalCredit);
            tvBalanceText.setText(String.format(Locale.US, "القيد غير متوازن — الفرق: %,.2f", diff));
            tvBalanceText.setTextColor(0xFFDC2626);
        }
    }

    // ✅ إصلاح دالة التذييل لتتغير حسب التبويب
    private void updateFooter(TrialBalanceDto tb) {
        if (tvFooterPeriod != null) {
            if (isGoldView) {
                // إظهار إجمالي الوزن بالجرام
                tvFooterPeriod.setText(String.format(Locale.US, "%,.3f g", tb.totalGoldDebit));
            } else {
                // إظهار إجمالي المال
                tvFooterPeriod.setText(String.format(Locale.US, "%,.2f", tb.totalDebit));
            }
        }
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
    boolean hideZero = switchHideZero != null && switchHideZero.isChecked(); // ✅ قراءة حالة الزر

    for (TrialBalanceDto.TrialBalanceRow row : originalRows) {
        if (leafOnly && !row.isLeaf) continue;
        
        // ✅ منطق إخفاء الحسابات الصفرية
        if (hideZero) {
            // فحص هل هناك أي حركة مالية أو وزنية (نتجاهل الكسور الصغيرة جداً)
            boolean hasMoney = 
                Math.abs(row.openingDebit) > 0.01 || Math.abs(row.openingCredit) > 0.01 ||
                Math.abs(row.periodDebit) > 0.01 || Math.abs(row.periodCredit) > 0.01 ||
                Math.abs(row.closingDebit) > 0.01 || Math.abs(row.closingCredit) > 0.01;
                
            boolean hasGold = 
                Math.abs(row.openingGoldDebit) > 0.001 || Math.abs(row.openingGoldCredit) > 0.001 ||
                Math.abs(row.periodGoldDebit) > 0.001 || Math.abs(row.periodGoldCredit) > 0.001 ||
                Math.abs(row.closingGoldDebit) > 0.001 || Math.abs(row.closingGoldCredit) > 0.001;

            // إذا لم يكن هناك نقود ولا ذهب، نتجاهل هذا السطر
            if (!hasMoney && !hasGold) {
                continue; 
            }
        }

        // فلترة البحث بالاسم أو الكود
        if (!query.isEmpty()) {
            String lowerQuery = query.toLowerCase().trim();
            boolean matchesCode = row.accountCode != null && row.accountCode.toLowerCase().contains(lowerQuery);
            boolean matchesName = row.accountNameAr != null && row.accountNameAr.toLowerCase().contains(lowerQuery);
            if (!matchesCode && !matchesName) continue;
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
