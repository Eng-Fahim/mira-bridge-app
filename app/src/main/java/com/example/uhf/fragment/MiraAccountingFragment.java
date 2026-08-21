package com.example.uhf.fragment;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraAccountingFragment extends Fragment {

    private static final String TAG = "MiraAccountingFragment";

    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private RecyclerView rvAccountingData;
    private ProgressBar pbLoading;
    private TextView tvEmptyData;
    private EditText etSearch;
    private TextView tvTodayRevenue, tvTotalGoldVolume, tvBalanceStatus;
    private TabLayout tabAccounting;
    private MaterialButton btnCreateManualEntry;
    private ImageButton btnRefresh;

    private String currentTab = "journal"; // journal, chart, trial
    private final List<AccountingEntry> entriesList = new ArrayList<>();
    private final List<AccountingEntry> filteredList = new ArrayList<>();
    private AccountingAdapter adapter;

    private boolean isArabic = true;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_accounting, container, false);
        
        apiClient = MiraApiClient.getInstance(requireContext());
        branchesManager = MiraBranchesManager.getInstance(requireContext());
        isArabic = Locale.getDefault().getLanguage().equals("ar");

        initViews(view);
        setupRecyclerView();
        setupListeners();
        
        // ✅ تحميل البيانات عند الفتح
        loadData(currentTab);

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
        btnCreateManualEntry = view.findViewById(R.id.btnCreateManualEntry);
        
        tabAccounting = view.findViewById(R.id.tabAccounting);
        if (tabAccounting != null) {
            tabAccounting.addTab(tabAccounting.newTab().setText(isArabic ? "القيود" : "Journal"));
            tabAccounting.addTab(tabAccounting.newTab().setText(isArabic ? "دليل الحسابات" : "Chart"));
            tabAccounting.addTab(tabAccounting.newTab().setText(isArabic ? "ميزان المراجعة" : "Trial"));
        }
    }

    private void setupRecyclerView() {
        if (rvAccountingData != null) {
            rvAccountingData.setLayoutManager(new LinearLayoutManager(getContext()));
            adapter = new AccountingAdapter(filteredList);
            rvAccountingData.setAdapter(adapter);
        }
    }

    private void setupListeners() {
        if (tabAccounting != null) {
            tabAccounting.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
                @Override
                public void onTabSelected(TabLayout.Tab tab) {
                    switch (tab.getPosition()) {
                        case 0: currentTab = "journal"; break;
                        case 1: currentTab = "chart"; break;
                        case 2: currentTab = "trial"; break;
                    }
                    loadData(currentTab);
                }
                @Override public void onTabUnselected(TabLayout.Tab tab) {}
                @Override public void onTabReselected(TabLayout.Tab tab) {}
            });
        }

        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> loadData(currentTab));
        }
        
        if (btnCreateManualEntry != null) {
            btnCreateManualEntry.setOnClickListener(v -> showCreateEntryDialog());
        }

        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    filterData(s.toString());
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }
    }

    /**
     * ✅ تحميل البيانات من السيرفر
     */
    private void loadData(String tab) {
        pbLoading.setVisibility(View.VISIBLE);
        
        executor.execute(() -> {
            try {
                int branchId = branchesManager != null ? branchesManager.getActiveBranchId() : 0;
                String branchIdStr = String.valueOf(branchId);
                
                MiraApiClient.ApiResponse response = null;
                
                switch (tab) {
                    case "journal":
                        response = apiClient.getJournalEntries(branchIdStr);
                        break;
                    case "chart":
                        response = apiClient.getChartOfAccounts();
                        break;
                    case "trial":
                        response = apiClient.getTrialBalance(branchIdStr);
                        break;
                }
                
                final MiraApiClient.ApiResponse finalResponse = response;
                
                mainHandler.post(() -> {
                    pbLoading.setVisibility(View.GONE);
                    
                    if (finalResponse != null && finalResponse.isSuccess && finalResponse.data != null) {
                        parseAndDisplay(finalResponse.data, tab);
                    } else {
                        showEmptyState(true);
                        Toast.makeText(getContext(), 
                            isArabic ? "فشل تحميل البيانات" : "Failed to load data", 
                            Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error loading accounting data: " + e.getMessage());
                mainHandler.post(() -> {
                    pbLoading.setVisibility(View.GONE);
                    showEmptyState(true);
                });
            }
        });
    }

    /**
     * ✅ تحليل وعرض البيانات
     */
    private void parseAndDisplay(JSONObject data, String tab) {
        try {
            entriesList.clear();
            
            JSONArray items = null;
            
            // استخراج البيانات من التنسيقات المختلفة
            if (data.has("data")) {
                JSONObject dataObj = data.optJSONObject("data");
                if (dataObj != null && dataObj.has("data")) {
                    items = dataObj.optJSONArray("data");
                } else {
                    items = data.optJSONArray("data");
                }
            }
            
            if (items == null) {
                items = data.optJSONArray("items");
            }
            
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject obj = items.getJSONObject(i);
                    
                    AccountingEntry entry = new AccountingEntry();
                    entry.id = obj.optInt("id", i + 1);
                    entry.code = obj.optString("entry_number", obj.optString("account_code", ""));
                    entry.description = obj.optString("description", obj.optString("account_name", obj.optString("account_name_ar", "")));
                    entry.debit = obj.optDouble("total_debit", obj.optDouble("debit", 0));
                    entry.credit = obj.optDouble("total_credit", obj.optDouble("credit", 0));
                    entry.goldDebit = obj.optDouble("total_gold_debit_grams", obj.optDouble("gold_debit", 0));
                    entry.goldCredit = obj.optDouble("total_gold_credit_grams", obj.optDouble("gold_credit", 0));
                    entry.date = obj.optString("entry_date", obj.optString("created_at", ""));
                    
                    entriesList.add(entry);
                }
            }
            
            filterData("");
            
            // تحديث الملخص
            updateSummary();
            
            showEmptyState(entriesList.isEmpty());
            
        } catch (Exception e) {
            Log.e(TAG, "Error parsing data: " + e.getMessage());
            showEmptyState(true);
        }
    }

    private void updateSummary() {
        double totalDebit = 0;
        double totalCredit = 0;
        double totalGold = 0;
        
        for (AccountingEntry entry : entriesList) {
            totalDebit += entry.debit;
            totalCredit += entry.credit;
            totalGold += entry.goldDebit;
        }
        
        if (tvTodayRevenue != null) {
            tvTodayRevenue.setText(String.format(Locale.US, "%,.2f", totalDebit));
        }
        
        if (tvTotalGoldVolume != null) {
            tvTotalGoldVolume.setText(String.format(Locale.US, "%.3f g 24K", totalGold));
        }
        
        if (tvBalanceStatus != null) {
            boolean isBalanced = Math.abs(totalDebit - totalCredit) < 0.01;
            tvBalanceStatus.setText(isBalanced ? "✓ متوازن" : "⚠️ غير متوازن");
            tvBalanceStatus.setTextColor(isBalanced ? Color.parseColor("#2ECC71") : Color.parseColor("#E74C3C"));
        }
    }

    private void filterData(String query) {
        filteredList.clear();
        
        for (AccountingEntry entry : entriesList) {
            if (query == null || query.isEmpty() ||
                entry.code.toLowerCase().contains(query.toLowerCase()) ||
                entry.description.toLowerCase().contains(query.toLowerCase())) {
                filteredList.add(entry);
            }
        }
        
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void showEmptyState(boolean show) {
        if (tvEmptyData != null) {
            tvEmptyData.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (rvAccountingData != null) {
            rvAccountingData.setVisibility(show ? View.GONE : View.VISIBLE);
        }
    }

    private void showCreateEntryDialog() {
        // يمكن إضافة نافذة إنشاء قيد هنا
        Toast.makeText(getContext(), 
            isArabic ? "سيتم إضافة هذه الميزة قريباً" : "Feature coming soon", 
            Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }

    // ==================== MODEL ====================
    
    private static class AccountingEntry {
        int id;
        String code;
        String description;
        double debit;
        double credit;
        double goldDebit;
        double goldCredit;
        String date;
    }

    // ==================== ADAPTER ====================
    
    private class AccountingAdapter extends RecyclerView.Adapter<AccountingAdapter.ViewHolder> {
        
        private final List<AccountingEntry> items;
        
        AccountingAdapter(List<AccountingEntry> items) {
            this.items = items;
        }
        
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(android.R.layout.simple_list_item_2, parent, false);
            return new ViewHolder(view);
        }
        
        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            AccountingEntry entry = items.get(position);
            holder.tvTitle.setText(entry.code + " - " + entry.description);
            holder.tvSubtitle.setText(String.format(Locale.US, 
                "مدين: %.2f | دائن: %.2f | ذهب: %.3f g", 
                entry.debit, entry.credit, entry.goldDebit));
        }
        
        @Override
        public int getItemCount() {
            return items.size();
        }
        
        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvTitle;
            TextView tvSubtitle;
            
            ViewHolder(View itemView) {
                super(itemView);
                tvTitle = itemView.findViewById(android.R.id.text1);
                tvSubtitle = itemView.findViewById(android.R.id.text2);
            }
        }
    }
}
