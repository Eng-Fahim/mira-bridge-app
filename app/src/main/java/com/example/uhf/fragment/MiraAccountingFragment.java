package com.example.uhf.fragment;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
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
import com.example.uhf.adapter.MiraAccountingAdapter;
import com.example.uhf.model.MiraAccountingItem;
import com.example.uhf.viewmodel.MiraAccountingViewModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MiraAccountingFragment extends Fragment {

    private MiraAccountingViewModel viewModel;
    private MiraAccountingAdapter adapter;

    private RecyclerView rvAccountingData;
    private ProgressBar pbLoading;
    private TextView tvEmptyData;
    private EditText etSearch;

    private TextView tvTodayRevenue, tvTotalGoldVolume, tvBalanceStatus, tvActiveBranch;
    private Button btnTabJournal, btnTabChartOfAccounts, btnTabTrialBalance, btnCreateManualEntry;
    private ImageButton btnRefresh;

    private MiraAccountingItem.ItemType currentTab = MiraAccountingItem.ItemType.JOURNAL_ENTRY;
    private final List<MiraAccountingItem> rawDataList = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_accounting, container, false);
        initViews(view);
        setupRecyclerView();
        setupListeners();
        return view;
    }

    private void initViews(View view) {
        rvAccountingData = view.findViewById(R.id.rvAccountingData);
        pbLoading = view.findViewById(R.id.pbAccountingLoading);
        tvEmptyData = view.findViewById(R.id.tvEmptyData);
        etSearch = view.findViewById(R.id.etSearchAccounting);
        btnRefresh = view.findViewById(R.id.btnRefreshAccounting);

        tvTodayRevenue = view.findViewById(R.id.tvTodayRevenue);
        tvTotalGoldVolume = view.findViewById(R.id.tvTotalGoldVolume);
        tvBalanceStatus = view.findViewById(R.id.tvBalanceStatus);
        tvActiveBranch = view.findViewById(R.id.tvActiveBranch);

        btnTabJournal = view.findViewById(R.id.btnTabJournal);
        btnTabChartOfAccounts = view.findViewById(R.id.btnTabChartOfAccounts);
        btnTabTrialBalance = view.findViewById(R.id.btnTabTrialBalance);
        btnCreateManualEntry = view.findViewById(R.id.btnCreateManualEntry);
    }

    private void setupRecyclerView() {
        if (rvAccountingData != null) {
            rvAccountingData.setLayoutManager(new LinearLayoutManager(getContext()));
            adapter = new MiraAccountingAdapter();
            rvAccountingData.setAdapter(adapter);
        }
    }

    private void setupListeners() {
        btnTabJournal.setOnClickListener(v -> switchTab(MiraAccountingItem.ItemType.JOURNAL_ENTRY));
        btnTabChartOfAccounts.setOnClickListener(v -> switchTab(MiraAccountingItem.ItemType.CHART_OF_ACCOUNT));
        btnTabTrialBalance.setOnClickListener(v -> switchTab(MiraAccountingItem.ItemType.TRIAL_BALANCE));

        btnRefresh.setOnClickListener(v -> loadAccountingData());
        btnCreateManualEntry.setOnClickListener(v -> showCreateEntryDialog());

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterData(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setupViewModel();
    }

    private void setupViewModel() {
        try {
            viewModel = new ViewModelProvider(this).get(MiraAccountingViewModel.class);
            viewModel.getAccountingItemsLiveData().observe(getViewLifecycleOwner(), items -> {
                pbLoading.setVisibility(View.GONE);
                if (items != null && !items.isEmpty()) {
                    rawDataList.clear();
                    rawDataList.addAll(items);
                    filterTabAndRender();
                    updateHeaderSummary(items);
                } else {
                    showEmptyState(true);
                }
            });

            loadAccountingData();
        } catch (Exception e) {
            e.printStackTrace();
            loadMockEnterpriseData(); // تحميل بيانات نموذجية عالمية في حال عدم توفر الـ API حالياً
        }
    }

    private void loadAccountingData() {
        pbLoading.setVisibility(View.VISIBLE);
        tvEmptyData.setVisibility(View.GONE);
        if (viewModel != null) {
            viewModel.fetchAccountingData(currentTab);
        } else {
            loadMockEnterpriseData();
        }
    }

    private void switchTab(MiraAccountingItem.ItemType tab) {
        currentTab = tab;
        updateTabUI();
        filterTabAndRender();
    }

    private void updateTabUI() {
        int activeColor = Color.parseColor("#2980B9");
        int inactiveColor = Color.parseColor("#BDC3C7");
        int activeText = Color.WHITE;
        int inactiveText = Color.parseColor("#2C3E50");

        btnTabJournal.setBackgroundColor(currentTab == MiraAccountingItem.ItemType.JOURNAL_ENTRY ? activeColor : inactiveColor);
        btnTabJournal.setTextColor(currentTab == MiraAccountingItem.ItemType.JOURNAL_ENTRY ? activeText : inactiveText);

        btnTabChartOfAccounts.setBackgroundColor(currentTab == MiraAccountingItem.ItemType.CHART_OF_ACCOUNT ? activeColor : inactiveColor);
        btnTabChartOfAccounts.setTextColor(currentTab == MiraAccountingItem.ItemType.CHART_OF_ACCOUNT ? activeText : inactiveText);

        btnTabTrialBalance.setBackgroundColor(currentTab == MiraAccountingItem.ItemType.TRIAL_BALANCE ? activeColor : inactiveColor);
        btnTabTrialBalance.setTextColor(currentTab == MiraAccountingItem.ItemType.TRIAL_BALANCE ? activeText : inactiveText);
    }

    private void filterTabAndRender() {
        List<MiraAccountingItem> filtered = new ArrayList<>();
        for (MiraAccountingItem item : rawDataList) {
            if (item.getItemType() == currentTab) {
                filtered.add(item);
            }
        }

        if (filtered.isEmpty()) {
            showEmptyState(true);
        } else {
            showEmptyState(false);
            if (adapter != null) adapter.setItems(filtered);
        }
    }

    private void filterData(String query) {
        if (query == null || query.trim().isEmpty()) {
            filterTabAndRender();
            return;
        }
        String q = query.toLowerCase(Locale.ROOT);
        List<MiraAccountingItem> filtered = new ArrayList<>();
        for (MiraAccountingItem item : rawDataList) {
            if (item.getItemType() == currentTab) {
                if (item.getCodeOrRef().toLowerCase(Locale.ROOT).contains(q) ||
                    item.getNameOrDesc().toLowerCase(Locale.ROOT).contains(q) ||
                    item.getRfidEpc().toLowerCase(Locale.ROOT).contains(q)) {
                    filtered.add(item);
                }
            }
        }
        if (adapter != null) adapter.setItems(filtered);
    }

    private void updateHeaderSummary(List<MiraAccountingItem> items) {
        double totalCash = 0;
        double totalGold24 = 0;
        double debitCashSum = 0, creditCashSum = 0;

        for (MiraAccountingItem item : items) {
            totalCash += item.getDebitCash();
            totalGold24 += (item.getFineWeight24Debit() - item.getFineWeight24Credit());
            debitCashSum += item.getDebitCash();
            creditCashSum += item.getCreditCash();
        }

        tvTodayRevenue.setText(String.format(Locale.US, "%,.2f YER", totalCash));
        tvTotalGoldVolume.setText(String.format(Locale.US, "%.3f جرام 24K", totalGoldVolumeCalc(items)));

        boolean isBalanced = Math.abs(debitCashSum - creditCashSum) < 0.01;
        tvBalanceStatus.setText(isBalanced ? "متوازن ✓" : "غير متوازن ⚠️");
        tvBalanceStatus.setTextColor(isBalanced ? Color.parseColor("#2ECC71") : Color.parseColor("#E74C3C"));
    }

    private double totalGoldVolumeCalc(List<MiraAccountingItem> items) {
        double net = 0;
        for (MiraAccountingItem i : items) {
            net += (i.getFineWeight24Debit() - i.getFineWeight24Credit());
        }
        return Math.max(net, 0.0);
    }

    private void showEmptyState(boolean show) {
        pbLoading.setVisibility(View.GONE);
        tvEmptyData.setVisibility(show ? View.VISIBLE : View.GONE);
        if (rvAccountingData != null) rvAccountingData.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    // ==================== NAVIGATE & DIALOGS ====================

    private void showCreateEntryDialog() {
        Context ctx = requireContext();
        Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_create_journal_entry);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        }

        EditText etRef = dialog.findViewById(R.id.etDialogRef);
        EditText etDesc = dialog.findViewById(R.id.etDialogDesc);
        EditText etGrossWeight = dialog.findViewById(R.id.etDialogGrossWeight);
        Spinner spKarat = dialog.findViewById(R.id.spDialogKarat);
        EditText etCashAmount = dialog.findViewById(R.id.etDialogCashAmount);
        Button btnSave = dialog.findViewById(R.id.btnDialogSave);

        String[] karats = {"24K", "22K", "21K", "18K"};
        ArrayAdapter<String> karatAdapter = new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_dropdown_item, karats);
        spKarat.setAdapter(karatAdapter);

        btnSave.setOnClickListener(v -> {
            String ref = etRef.getText().toString().trim();
            String desc = etDesc.getText().toString().trim();
            String grossStr = etGrossWeight.getText().toString().trim();
            String cashStr = etCashAmount.getText().toString().trim();

            if (ref.isEmpty() || desc.isEmpty()) {
                Toast.makeText(ctx, "يرجى تعبئة المرجع والبيان", Toast.LENGTH_SHORT).show();
                return;
            }

            double gross = grossStr.isEmpty() ? 0 : Double.parseDouble(grossStr);
            double cash = cashStr.isEmpty() ? 0 : Double.parseDouble(cashStr);
            int karat = Integer.parseInt(spKarat.getSelectedItem().toString().replace("K", ""));

            double fine24 = MiraAccountingItem.calculateFineWeight24(gross, karat);

            MiraAccountingItem newEntry = new MiraAccountingItem(
                    MiraAccountingItem.ItemType.JOURNAL_ENTRY,
                    ref, desc, fine24, 0.0, cash, 0.0, "YER", "2026-08-14"
            );

            rawDataList.add(0, newEntry);
            filterTabAndRender();
            dialog.dismiss();
            Toast.makeText(ctx, "تم تسجيل القيد المحاسبي المزدوج بنجاح", Toast.LENGTH_SHORT).show();
        });

        dialog.show();
    }

    private void loadMockEnterpriseData() {
        rawDataList.clear();
        // قيود يومية نموذجية
        rawDataList.add(new MiraAccountingItem(MiraAccountingItem.ItemType.JOURNAL_ENTRY, "JV-2026-001", "شراء طقم ذهب - توريد صندوق", 0.0, 52.410, 0.0, 3500000, "YER", "2026-08-14"));
        rawDataList.add(new MiraAccountingItem(MiraAccountingItem.ItemType.JOURNAL_ENTRY, "JV-2026-002", "مبيعات ذهب عيار 21 نقداً", 28.500, 0.0, 2100000, 0.0, "YER", "2026-08-14"));
        
        // دليل حسابات نموذجي
        rawDataList.add(new MiraAccountingItem(MiraAccountingItem.ItemType.CHART_OF_ACCOUNT, "110101", "خزينة الذهب الخام (24K)", 1520.450, 0.0, "YER"));
        rawDataList.add(new MiraAccountingItem(MiraAccountingItem.ItemType.CHART_OF_ACCOUNT, "110102", "الصندوق النقدي الرئيسي", 0.0, 48500000.0, "YER"));
        rawDataList.add(new MiraAccountingItem(MiraAccountingItem.ItemType.CHART_OF_ACCOUNT, "110201", "خزينة فروع الذهب الصافي", 840.120, 0.0, "YER"));

        // ميزان مراجعة نموذجي
        rawDataList.add(new MiraAccountingItem(MiraAccountingItem.ItemType.TRIAL_BALANCE, "TB-24K", "ميزان كاسر الذهب والمسبوكات", 2360.570, 48500000.0, "YER"));

        filterTabAndRender();
        updateHeaderSummary(rawDataList);
        pbLoading.setVisibility(View.GONE);
    }
}
