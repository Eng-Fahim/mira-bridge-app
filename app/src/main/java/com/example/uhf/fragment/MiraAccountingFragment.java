package com.example.uhf.fragment;

import android.os.Bundle;
import android.util.Log;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.adapter.JournalEntryAdapter;
import com.example.uhf.api.dto.*;
import com.example.uhf.util.DateHelper;
import com.example.uhf.util.NumberFormatter;
import com.example.uhf.viewmodel.AccountingViewModel;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputEditText;

import java.util.Arrays;

public class MiraAccountingFragment extends Fragment {
    
    private static final String TAG = "MiraAccountingFragment";
    
    private AccountingViewModel viewModel;
    
    // Views
    private TabLayout tabLayout;
    private RecyclerView rvData;
    private ProgressBar pbLoading;
    private LinearLayout emptyState;
    private TextView tvEmptyText;
    private TextInputEditText etSearch;
    private LinearLayout kpiContainer;
    private ExtendedFloatingActionButton fabCreateEntry;
    
    private JournalEntryAdapter journalAdapter;
    
    private String currentTab = "journal";
    private int currentPage = 1;
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, 
                            @Nullable ViewGroup container, 
                            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_accounting, container, false);
        
        initViews(view);
        setupViewModel();
        setupTabs();
        setupRecyclerView();
        setupListeners();
        
        // التحميل الأولي
        loadCurrentTab();
        
        return view;
    }
    
    private void initViews(View v) {
        tabLayout = v.findViewById(R.id.tabAccounting);
        rvData = v.findViewById(R.id.rvAccountingData);
        pbLoading = v.findViewById(R.id.pbLoading);
        emptyState = v.findViewById(R.id.emptyState);
        tvEmptyText = v.findViewById(R.id.tvEmptyText);
        etSearch = v.findViewById(R.id.etSearch);
        kpiContainer = v.findViewById(R.id.kpiContainer);
        fabCreateEntry = v.findViewById(R.id.fabCreateEntry);
    }
    
    private void setupViewModel() {
        viewModel = new ViewModelProvider(this).get(AccountingViewModel.class);
        
        viewModel.getLoading().observe(getViewLifecycleOwner(), isLoading -> {
            pbLoading.setVisibility(isLoading != null && isLoading ? View.VISIBLE : View.GONE);
        });
        
        viewModel.getError().observe(getViewLifecycleOwner(), error -> {
            if (error != null && !error.isEmpty()) {
                Toast.makeText(requireContext(), "❌ " + error, Toast.LENGTH_SHORT).show();
                viewModel.clearError();
            }
        });
    }
    
    private void setupTabs() {
        if (tabLayout == null) return;
        
        tabLayout.addTab(tabLayout.newTab().setText("📋 القيود"));
        tabLayout.addTab(tabLayout.newTab().setText("⚖️ الميزان"));
        tabLayout.addTab(tabLayout.newTab().setText("📊 الحسابات"));
        tabLayout.addTab(tabLayout.newTab().setText("📈 الدخل"));
        tabLayout.addTab(tabLayout.newTab().setText("💰 الميزانية"));
        tabLayout.addTab(tabLayout.newTab().setText("🥇 الذهب"));
        
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                switch (tab.getPosition()) {
                    case 0: currentTab = "journal"; break;
                    case 1: currentTab = "trial"; break;
                    case 2: currentTab = "accounts"; break;
                    case 3: currentTab = "income"; break;
                    case 4: currentTab = "balance"; break;
                    case 5: currentTab = "gold"; break;
                }
                loadCurrentTab();
            }
            
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {
                loadCurrentTab();
            }
        });
    }
    
    private void setupRecyclerView() {
        journalAdapter = new JournalEntryAdapter(entry -> {
            // TODO: فتح تفاصيل القيد
            Toast.makeText(requireContext(), 
                "قيد: " + entry.entryNumber, Toast.LENGTH_SHORT).show();
        });
        
        if (rvData != null) {
            rvData.setLayoutManager(new LinearLayoutManager(getContext()));
            rvData.setAdapter(journalAdapter);
        }
    }
    
    private void setupListeners() {
        if (fabCreateEntry != null) {
            fabCreateEntry.setOnClickListener(v -> {
                Toast.makeText(requireContext(), "إنشاء قيد جديد", Toast.LENGTH_SHORT).show();
                // TODO: فتح نافذة إنشاء القيد
            });
        }
    }
    
    private void loadCurrentTab() {
        String from = DateHelper.startOfYearApi();
        String to = DateHelper.todayApi();
        
        switch (currentTab) {
            case "journal":
                observeJournalEntries();
                viewModel.loadJournalEntries(1, 50, null);
                break;
                
            case "trial":
                observeTrialBalance();
                viewModel.loadTrialBalance(from, to, 0);
                break;
                
            case "accounts":
                observeAccounts();
                viewModel.loadAccounts(null, null);
                break;
                
            case "income":
                observeIncomeStatement();
                viewModel.loadIncomeStatement(from, to, 0);
                break;
                
            case "balance":
                observeBalanceSheet();
                viewModel.loadBalanceSheet(DateHelper.todayApi());
                break;
                
            case "gold":
                observeGoldMovement();
                viewModel.loadGoldMovement(from, to, 0);
                break;
        }
    }
    
    private void observeJournalEntries() {
        viewModel.getJournalEntries().observe(getViewLifecycleOwner(), entries -> {
            if (entries == null || entries.length == 0) {
                showEmpty("لا توجد قيود");
                return;
            }
            
            showData();
            journalAdapter.setItems(Arrays.asList(entries));
            renderJournalKPIs(entries);
        });
    }
    
    private void renderJournalKPIs(JournalEntryDto[] entries) {
        if (kpiContainer == null) return;
        kpiContainer.removeAllViews();
        
        double totalDebit = 0;
        double totalCredit = 0;
        int posted = 0;
        int draft = 0;
        
        for (JournalEntryDto e : entries) {
            totalDebit += e.totalDebit;
            totalCredit += e.totalCredit;
            if (e.isPosted()) posted++;
            if (e.isDraft()) draft++;
        }
        
        addKpiCard("📋 إجمالي القيود", String.valueOf(entries.length), "#3498DB");
        addKpiCard("✅ معتمدة", String.valueOf(posted), "#27AE60");
        addKpiCard("📝 مسودة", String.valueOf(draft), "#F39C12");
        addKpiCard("💰 الإجمالي", NumberFormatter.moneyShort(totalDebit), "#9B59B6");
    }
    
    private void observeTrialBalance() {
        viewModel.getTrialBalance().observe(getViewLifecycleOwner(), tb -> {
            if (tb == null || tb.rows == null || tb.rows.isEmpty()) {
                showEmpty("لا توجد بيانات في هذه الفترة");
                return;
            }
            
            showData();
            renderTrialBalanceKPIs(tb);
            // TODO: عرض الجدول
        });
    }
    
    private void renderTrialBalanceKPIs(TrialBalanceDto tb) {
        if (kpiContainer == null) return;
        kpiContainer.removeAllViews();
        
        addKpiCard("📊 مدين", NumberFormatter.moneyShort(tb.totalDebit), "#3498DB");
        addKpiCard("📊 دائن", NumberFormatter.moneyShort(tb.totalCredit), "#E74C3C");
        addKpiCard("⚖️ الحالة", tb.balanced ? "✅ متوازن" : "❌ غير متوازن", 
                  tb.balanced ? "#27AE60" : "#C0392B");
        addKpiCard("📈 الحسابات", String.valueOf(tb.rows.size()), "#9B59B6");
    }
    
    private void observeAccounts() {
        viewModel.getAccounts().observe(getViewLifecycleOwner(), accounts -> {
            if (accounts == null || accounts.length == 0) {
                showEmpty("لا توجد حسابات");
                return;
            }
            
            showData();
            addKpiCard("📊 الحسابات", String.valueOf(accounts.length), "#3498DB");
        });
    }
    
    private void observeIncomeStatement() {
        viewModel.getIncomeStatement().observe(getViewLifecycleOwner(), is -> {
            if (is == null) return;
            
            showData();
            if (kpiContainer == null) return;
            kpiContainer.removeAllViews();
            
            addKpiCard("📈 الإيرادات", NumberFormatter.moneyShort(is.netRevenue), "#3498DB");
            addKpiCard("💵 التكلفة", NumberFormatter.moneyShort(is.cogs), "#E74C3C");
            addKpiCard("💰 الربح", NumberFormatter.moneyShort(is.netProfit), 
                      is.netProfit >= 0 ? "#27AE60" : "#C0392B");
            addKpiCard("📊 الهامش", NumberFormatter.percent(is.netProfitMargin), "#9B59B6");
        });
    }
    
    private void observeBalanceSheet() {
        viewModel.getBalanceSheet().observe(getViewLifecycleOwner(), bs -> {
            if (bs == null) return;
            
            showData();
            if (kpiContainer == null) return;
            kpiContainer.removeAllViews();
            
            addKpiCard("📊 الأصول", NumberFormatter.moneyShort(bs.totalAssets), "#3498DB");
            addKpiCard("📉 الالتزامات", NumberFormatter.moneyShort(bs.totalLiabilities), "#E74C3C");
            addKpiCard("💰 حقوق", NumberFormatter.moneyShort(bs.totalEquityWithIncome), "#9B59B6");
            addKpiCard("⚖️ الحالة", bs.balanced ? "✅ متوازن" : "❌", 
                      bs.balanced ? "#27AE60" : "#C0392B");
        });
    }
    
    private void observeGoldMovement() {
        viewModel.getGoldMovement().observe(getViewLifecycleOwner(), gm -> {
            if (gm == null || gm.totals == null) return;
            
            showData();
            if (kpiContainer == null) return;
            kpiContainer.removeAllViews();
            
            addKpiCard("📥 وارد", NumberFormatter.gold(gm.totals.goldIn), "#27AE60");
            addKpiCard("📤 صادر", NumberFormatter.gold(gm.totals.goldOut), "#E74C3C");
            addKpiCard("🥇 الرصيد", NumberFormatter.gold(gm.totals.closingGold), "#D4AF37");
        });
    }
    
    private void addKpiCard(String title, String value, String color) {
        if (kpiContainer == null || getContext() == null) return;
        
        View card = LayoutInflater.from(getContext())
            .inflate(R.layout.kpi_card, kpiContainer, false);
        
        TextView tvTitle = card.findViewById(R.id.tvKpiTitle);
        TextView tvValue = card.findViewById(R.id.tvKpiValue);
        View indicator = card.findViewById(R.id.kpiIndicator);
        
        tvTitle.setText(title);
        tvValue.setText(value);
        tvValue.setTextColor(android.graphics.Color.parseColor(color));
        indicator.setBackgroundColor(android.graphics.Color.parseColor(color));
        
        kpiContainer.addView(card);
    }
    
    private void showEmpty(String message) {
        if (emptyState != null) {
            emptyState.setVisibility(View.VISIBLE);
        }
        if (rvData != null) {
            rvData.setVisibility(View.GONE);
        }
        if (tvEmptyText != null) {
            tvEmptyText.setText(message);
        }
    }
    
    private void showData() {
        if (emptyState != null) emptyState.setVisibility(View.GONE);
        if (rvData != null) rvData.setVisibility(View.VISIBLE);
    }
}
