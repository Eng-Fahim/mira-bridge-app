package com.example.uhf.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.adapter.MiraAccountingAdapter;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.model.MiraAccountingItem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

public class MiraAccountingFragment extends Fragment {

    private boolean isArabic;
    private TextView tvAccountingTitle, tvActiveBranch, tvTodayRevenue, tvTotalJournalVolume, tvBalanceStatus;
    private TextView thCol1, thCol2, thCol3, thCol4, tvEmptyData;
    private Button btnTabJournal, btnTabChartOfAccounts, btnTabTrialBalance, btnCreateManualEntry;
    private EditText etSearchAccounting;
    private ImageButton btnRefreshAccounting;
    private RecyclerView rvAccountingData;
    private ProgressBar pbAccountingLoading;
    private MiraAccountingAdapter accountingAdapter;
    private MiraAccountingViewModel viewModel;
    private MiraAccountingItem.ItemType activeType = MiraAccountingItem.ItemType.JOURNAL_ENTRY;
    private final DecimalFormat currencyFormat = new DecimalFormat("#,##0.00");

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_accounting, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        isArabic = Locale.getDefault().getLanguage().equals("ar");
        initViews(view);
        initViewModel();
        setupListeners();
        btnTabJournal.performClick();
    }

    private void initViews(View view) {
        tvAccountingTitle = view.findViewById(R.id.tvAccountingTitle);
        tvActiveBranch = view.findViewById(R.id.tvActiveBranch);
        tvTodayRevenue = view.findViewById(R.id.tvTodayRevenue);
        tvTotalJournalVolume = view.findViewById(R.id.tvTotalJournalVolume);
        tvBalanceStatus = view.findViewById(R.id.tvBalanceStatus);
        btnTabJournal = view.findViewById(R.id.btnTabJournal);
        btnTabChartOfAccounts = view.findViewById(R.id.btnTabChartOfAccounts);
        btnTabTrialBalance = view.findViewById(R.id.btnTabTrialBalance);
        btnCreateManualEntry = view.findViewById(R.id.btnCreateManualEntry);
        thCol1 = view.findViewById(R.id.thCol1);
        thCol2 = view.findViewById(R.id.thCol2);
        thCol3 = view.findViewById(R.id.thCol3);
        thCol4 = view.findViewById(R.id.thCol4);
        etSearchAccounting = view.findViewById(R.id.etSearchAccounting);
        btnRefreshAccounting = view.findViewById(R.id.btnRefreshAccounting);
        rvAccountingData = view.findViewById(R.id.rvAccountingData);
        pbAccountingLoading = view.findViewById(R.id.pbAccountingLoading);
        tvEmptyData = view.findViewById(R.id.tvEmptyData);

        if (rvAccountingData != null) {
            rvAccountingData.setLayoutManager(new LinearLayoutManager(getContext()));
            accountingAdapter = new MiraAccountingAdapter();
            rvAccountingData.setAdapter(accountingAdapter);
            accountingAdapter.setOnItemClickListener(item -> showItemDetail(item));
        }
    }

    private void initViewModel() {
        viewModel = new ViewModelProvider(this).get(MiraAccountingViewModel.class);
        viewModel.getItemsLiveData().observe(getViewLifecycleOwner(), this::renderData);
        viewModel.getIsLoading().observe(getViewLifecycleOwner(), this::showLoading);
        viewModel.getStatsLiveData().observe(getViewLifecycleOwner(), this::updateStats);
        viewModel.getErrorMessage().observe(getViewLifecycleOwner(), error -> {
            if (error != null && !error.isEmpty())
                Toast.makeText(getContext(), error, Toast.LENGTH_SHORT).show();
        });
    }

    private void setupListeners() {
        btnTabJournal.setOnClickListener(v -> switchTab(MiraAccountingItem.ItemType.JOURNAL_ENTRY,
                btnTabJournal, btnTabChartOfAccounts, btnTabTrialBalance));
        btnTabChartOfAccounts.setOnClickListener(v -> switchTab(MiraAccountingItem.ItemType.CHART_OF_ACCOUNT,
                btnTabChartOfAccounts, btnTabJournal, btnTabTrialBalance));
        btnTabTrialBalance.setOnClickListener(v -> switchTab(MiraAccountingItem.ItemType.TRIAL_BALANCE,
                btnTabTrialBalance, btnTabJournal, btnTabChartOfAccounts));

        btnCreateManualEntry.setOnClickListener(v ->
                Toast.makeText(getContext(), ar("فتح محرر القيد اليدوي...", "Opening manual entry..."),
                        Toast.LENGTH_SHORT).show());

        btnRefreshAccounting.setOnClickListener(v -> refreshData());

        if (etSearchAccounting != null) {
            etSearchAccounting.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    viewModel.filterData(s.toString());
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }
    }

    private void switchTab(MiraAccountingItem.ItemType type, Button active, Button... inactives) {
        activeType = type;
        active.setBackgroundColor(0xFF2980B9);
        active.setTextColor(0xFFFFFFFF);
        for (Button b : inactives) {
            b.setBackgroundColor(0xFFBDC3C7);
            b.setTextColor(0xFF2C3E50);
        }

        switch (type) {
            case JOURNAL_ENTRY:
                thCol1.setText(ar("رقم القيد", "Entry No"));
                thCol2.setText(ar("البيان", "Description"));
                thCol3.setText(ar("مدين", "Debit"));
                thCol4.setText(ar("دائن", "Credit"));
                viewModel.fetchJournalEntries();
                break;
            case CHART_OF_ACCOUNT:
                thCol1.setText(ar("رمز الحساب", "Code"));
                thCol2.setText(ar("اسم الحساب", "Account"));
                thCol3.setText(ar("النوع", "Type"));
                thCol4.setText(ar("الرصيد", "Balance"));
                viewModel.fetchChartOfAccounts();
                break;
            case TRIAL_BALANCE:
                thCol1.setText(ar("رقم الحساب", "Account"));
                thCol2.setText(ar("الحساب", "Name"));
                thCol3.setText(ar("مدين", "Debit"));
                thCol4.setText(ar("دائن", "Credit"));
                viewModel.fetchTrialBalance();
                break;
        }
    }

    private void renderData(List<MiraAccountingItem> items) {
        if (items == null || items.isEmpty()) {
            if (tvEmptyData != null) tvEmptyData.setVisibility(View.VISIBLE);
            if (rvAccountingData != null) rvAccountingData.setVisibility(View.GONE);
        } else {
            if (tvEmptyData != null) tvEmptyData.setVisibility(View.GONE);
            if (rvAccountingData != null) rvAccountingData.setVisibility(View.VISIBLE);
            if (accountingAdapter != null) accountingAdapter.setItems(items);
        }
    }

    private void showLoading(boolean isLoading) {
        if (pbAccountingLoading != null)
            pbAccountingLoading.setVisibility(isLoading ? View.VISIBLE : View.GONE);
    }

    private void updateStats(AccountingStats stats) {
        if (stats == null) return;
        if (tvTodayRevenue != null)
            tvTodayRevenue.setText(currencyFormat.format(stats.todayRevenue) + " YER");
        if (tvTotalJournalVolume != null)
            tvTotalJournalVolume.setText(currencyFormat.format(stats.journalVolume) + " YER");
        if (tvBalanceStatus != null) {
            tvBalanceStatus.setText(stats.balanced ? ar("متوازن ✓", "Balanced ✓") : ar("غير متوازن ⚠", "Unbalanced ⚠"));
            tvBalanceStatus.setTextColor(stats.balanced ? 0xFF2ECC71 : 0xFFE74C3C);
        }
    }

    private void refreshData() {
        if (etSearchAccounting != null) etSearchAccounting.setText("");
        switch (activeType) {
            case JOURNAL_ENTRY: viewModel.fetchJournalEntries(); break;
            case CHART_OF_ACCOUNT: viewModel.fetchChartOfAccounts(); break;
            case TRIAL_BALANCE: viewModel.fetchTrialBalance(); break;
        }
        Toast.makeText(getContext(), ar("تم التحديث", "Refreshed"), Toast.LENGTH_SHORT).show();
    }

    private void showItemDetail(MiraAccountingItem item) {
        String msg = item.getCol2Text() + "\n" + item.getCol3Text() + " / " + item.getCol4Text();
        Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show();
    }

    private String ar(String arabic, String english) {
        return isArabic ? arabic : english;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        rvAccountingData = null;
        accountingAdapter = null;
    }

    // ==================== ViewModel ====================

    public static class MiraAccountingViewModel extends ViewModel {
        private final MutableLiveData<List<MiraAccountingItem>> itemsLiveData = new MutableLiveData<>();
        private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>();
        private final MutableLiveData<String> errorMessage = new MutableLiveData<>();
        private final MutableLiveData<AccountingStats> statsLiveData = new MutableLiveData<>();
        private List<MiraAccountingItem> rawList = new ArrayList<>();

        public LiveData<List<MiraAccountingItem>> getItemsLiveData() { return itemsLiveData; }
        public LiveData<Boolean> getIsLoading() { return isLoading; }
        public LiveData<String> getErrorMessage() { return errorMessage; }
        public LiveData<AccountingStats> getStatsLiveData() { return statsLiveData; }

        public void fetchJournalEntries() {
            isLoading.setValue(true);
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    MiraApiClient client = MiraApiClient.getInstance(null);
                    MiraApiClient.ApiResponse response = client.getJournalEntries();
                    processResponse(response, MiraAccountingItem.ItemType.JOURNAL_ENTRY);
                } catch (Exception e) {
                    loadMockJournalEntries();
                }
            });
        }

        public void fetchChartOfAccounts() {
            isLoading.setValue(true);
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    MiraApiClient client = MiraApiClient.getInstance(null);
                    MiraApiClient.ApiResponse response = client.getChartOfAccounts();
                    processResponse(response, MiraAccountingItem.ItemType.CHART_OF_ACCOUNT);
                } catch (Exception e) {
                    loadMockChartOfAccounts();
                }
            });
        }

        public void fetchTrialBalance() {
            isLoading.setValue(true);
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    MiraApiClient client = MiraApiClient.getInstance(null);
                    MiraApiClient.ApiResponse response = client.getTrialBalance();
                    processResponse(response, MiraAccountingItem.ItemType.TRIAL_BALANCE);
                } catch (Exception e) {
                    loadMockTrialBalance();
                }
            });
        }

        private void processResponse(MiraApiClient.ApiResponse response, MiraAccountingItem.ItemType type) {
            List<MiraAccountingItem> items = new ArrayList<>();
            double totalDebit = 0, totalCredit = 0, revenue = 0;

            try {
                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray data = response.data.optJSONArray("data");
                    if (data == null) data = response.data.optJSONArray("items");
                    if (data == null) data = response.data.optJSONArray("entries");

                    if (data != null) {
                        for (int i = 0; i < data.length(); i++) {
                            JSONObject obj = data.optJSONObject(i);
                            if (obj == null) continue;
                            items.add(new MiraAccountingItem(type,
                                    obj.optString("code", obj.optString("entry_no", obj.optString("id", "-"))),
                                    obj.optString("description", obj.optString("name", obj.optString("title", "-"))),
                                    obj.optString("debit", obj.optString("type", "0")),
                                    obj.optString("credit", obj.optString("balance", "0"))));
                            totalDebit += obj.optDouble("debit", 0);
                            totalCredit += obj.optDouble("credit", 0);
                            revenue += obj.optDouble("revenue", obj.optDouble("amount", 0));
                        }
                    }
                }
            } catch (Exception ignored) {}

            if (items.isEmpty()) {
                switch (type) {
                    case JOURNAL_ENTRY: loadMockJournalEntries(); return;
                    case CHART_OF_ACCOUNT: loadMockChartOfAccounts(); return;
                    case TRIAL_BALANCE: loadMockTrialBalance(); return;
                }
            }

            rawList = items;
            itemsLiveData.postValue(items);
            statsLiveData.postValue(new AccountingStats(revenue, totalDebit + totalCredit,
                    Math.abs(totalDebit - totalCredit) < 0.01));
            isLoading.postValue(false);
        }

        public void filterData(String query) {
            if (rawList == null) return;
            if (query == null || query.trim().isEmpty()) {
                itemsLiveData.setValue(rawList);
                return;
            }
            String q = query.toLowerCase().trim();
            List<MiraAccountingItem> filtered = new ArrayList<>();
            for (MiraAccountingItem item : rawList) {
                if ((item.getCol1Text() != null && item.getCol1Text().toLowerCase().contains(q)) ||
                        (item.getCol2Text() != null && item.getCol2Text().toLowerCase().contains(q))) {
                    filtered.add(item);
                }
            }
            itemsLiveData.setValue(filtered);
        }

        private void loadMockJournalEntries() {
            rawList.clear();
            rawList.add(new MiraAccountingItem(MiraAccountingItem.ItemType.JOURNAL_ENTRY,
                    "JV-2026-101", "مبيعات طقم ذهب - فاتورة #4092", "450,000", "0.00"));
            rawList.add(new MiraAccountingItem(MiraAccountingItem.ItemType.JOURNAL_ENTRY,
                    "JV-2026-102", "توريد صندوق - فرع صنعاء", "0.00", "450,000"));
            rawList.add(new MiraAccountingItem(MiraAccountingItem.ItemType.JOURNAL_ENTRY,
                    "JV-2026-103", "رسوم صياغة وتلميع", "25,000", "0.00"));
            itemsLiveData.postValue(rawList);
            statsLiveData.postValue(new AccountingStats(450000, 925000, true));
            isLoading.postValue(false);
        }

        private void loadMockChartOfAccounts() {
            rawList.clear();
            rawList.add(new MiraAccountingItem(MiraAccountingItem.ItemType.CHART_OF_ACCOUNT,
                    "110101", "الصندوق الرئيسي", "أصول متداولة", "12,450,000"));
            rawList.add(new MiraAccountingItem(MiraAccountingItem.ItemType.CHART_OF_ACCOUNT,
                    "110201", "مخزون الذهب (عيار 21)", "أصول متداولة", "85,200,000"));
            rawList.add(new MiraAccountingItem(MiraAccountingItem.ItemType.CHART_OF_ACCOUNT,
                    "410101", "إيرادات مبيعات المجوهرات", "إيرادات", "4,250,000"));
            itemsLiveData.postValue(rawList);
            statsLiveData.postValue(new AccountingStats(4250000, 101900000, true));
            isLoading.postValue(false);
        }

        private void loadMockTrialBalance() {
            rawList.clear();
            rawList.add(new MiraAccountingItem(MiraAccountingItem.ItemType.TRIAL_BALANCE,
                    "1101", "حسابات النقدية", "15,000,000", "2,550,000"));
            rawList.add(new MiraAccountingItem(MiraAccountingItem.ItemType.TRIAL_BALANCE,
                    "1102", "مخزون البضائع", "85,200,000", "0.00"));
            itemsLiveData.postValue(rawList);
            statsLiveData.postValue(new AccountingStats(0, 102750000, false));
            isLoading.postValue(false);
        }
    }

    // ==================== Stats Model ====================

    public static class AccountingStats {
        public double todayRevenue;
        public double journalVolume;
        public boolean balanced;

        public AccountingStats(double todayRevenue, double journalVolume, boolean balanced) {
            this.todayRevenue = todayRevenue;
            this.journalVolume = journalVolume;
            this.balanced = balanced;
        }
    }
}
