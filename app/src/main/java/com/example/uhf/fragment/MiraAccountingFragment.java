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
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.PermissionManager;
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
    private PermissionManager permissionManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private RecyclerView rvAccountingData;
    private ProgressBar pbLoading;
    private TextView tvEmptyData;
    private EditText etSearch;
    private TextView tvTodayRevenue, tvTotalGoldVolume, tvBalanceStatus;
    private TabLayout tabAccounting;
    private MaterialButton btnCreateManualEntry;
    private MaterialButton btnFinancialReports;
    private ImageButton btnRefresh;

    private String currentTab = "journal";
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
        permissionManager = PermissionManager.getInstance(requireContext());
        isArabic = Locale.getDefault().getLanguage().equals("ar");

        initViews(view);
        setupRecyclerView();
        setupListeners();
        applyPermissions();
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
        btnFinancialReports = view.findViewById(R.id.btnFinancialReports);
        tabAccounting = view.findViewById(R.id.tabAccounting);
        
        if (tabAccounting != null) {
            tabAccounting.addTab(tabAccounting.newTab().setText(isArabic ? "القيود" : "Journal"));
            tabAccounting.addTab(tabAccounting.newTab().setText(isArabic ? "دليل الحسابات" : "Chart of Accounts"));
            tabAccounting.addTab(tabAccounting.newTab().setText(isArabic ? "ميزان المراجعة" : "Trial Balance"));
        }
    }

    private void applyPermissions() {
        if (btnCreateManualEntry != null) {
            if (permissionManager.canEdit("accounting") || permissionManager.isAdmin()) {
                btnCreateManualEntry.setVisibility(View.VISIBLE);
            } else {
                btnCreateManualEntry.setVisibility(View.GONE);
            }
        }
        
        if (btnFinancialReports != null) {
            if (permissionManager.canView("accounting") || permissionManager.canView("reports")) {
                btnFinancialReports.setVisibility(View.VISIBLE);
            } else {
                btnFinancialReports.setVisibility(View.GONE);
            }
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
            btnCreateManualEntry.setOnClickListener(v -> {
                if (permissionManager.canEdit("accounting") || permissionManager.isAdmin()) {
                    showCreateEntryDialog();
                } else {
                    Toast.makeText(getContext(), 
                        isArabic ? "⛔ غير مصرح لك بإنشاء قيود" : "⛔ Access Denied",
                        Toast.LENGTH_SHORT).show();
                }
            });
        }
        
        if (btnFinancialReports != null) {
            btnFinancialReports.setOnClickListener(v -> {
                if (permissionManager.canAccessFragment("FinancialReports")) {
                    if (getActivity() instanceof UHFMainActivity) {
                        ((UHFMainActivity) getActivity()).loadFragment(
                            new MiraFinancialReportsFragment(), "FinancialReports");
                    }
                } else {
                    Toast.makeText(getContext(), 
                        isArabic ? "⛔ غير مصرح لك بالوصول للتقارير المالية" : "⛔ Access Denied",
                        Toast.LENGTH_SHORT).show();
                }
            });
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

    private void loadData(String tab) {
        if (!permissionManager.canView("accounting") && !permissionManager.isAdmin() && 
            !permissionManager.isBranchManager() && 
            !"AUDITOR".equals(new com.example.uhf.tools.SessionManager(requireContext()).getRoleString())) {
            showEmptyState(true);
            if (tvEmptyData != null) {
                tvEmptyData.setText(isArabic ? "⛔ غير مصرح لك بالوصول" : "⛔ Access Denied");
            }
            if (pbLoading != null) pbLoading.setVisibility(View.GONE);
            return;
        }
        
        if (pbLoading != null) pbLoading.setVisibility(View.VISIBLE);
        if (tvEmptyData != null) tvEmptyData.setVisibility(View.GONE);
        
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
                    if (pbLoading != null) pbLoading.setVisibility(View.GONE);
                    
                    if (finalResponse != null && finalResponse.isSuccess && finalResponse.data != null) {
                        parseAndDisplay(finalResponse.data, tab);
                    } else {
                        showEmptyState(true);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error: " + e.getMessage(), e);
                mainHandler.post(() -> {
                    if (pbLoading != null) pbLoading.setVisibility(View.GONE);
                    showEmptyState(true);
                });
            }
        });
    }

    private void parseAndDisplay(JSONObject data, String tab) {
        try {
            entriesList.clear();
            JSONArray items = data.optJSONArray("data");
            
            if (items != null && items.length() > 0) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject obj = items.getJSONObject(i);
                    AccountingEntry entry = new AccountingEntry();
                    entry.id = i + 1;
                    
                    if ("journal".equals(tab)) {
                        entry.code = obj.optString("entry_number", obj.optString("reference", "—"));
                        entry.description = obj.optString("description", "—");
                        entry.debit = safeDouble(obj.optString("total_debit", "0"));
                        entry.credit = safeDouble(obj.optString("total_credit", "0"));
                        entry.goldDebit = safeDouble(obj.optString("total_gold_debit_grams", "0"));
                        entry.goldCredit = safeDouble(obj.optString("total_gold_credit_grams", "0"));
                    } else if ("chart".equals(tab)) {
                        entry.code = obj.optString("account_code", obj.optString("code", "—"));
                        entry.description = obj.optString("account_name_ar", obj.optString("account_name", "—"));
                        entry.debit = safeDouble(obj.optString("opening_balance", "0"));
                        entry.credit = 0;
                        entry.goldDebit = safeDouble(obj.optString("opening_gold_grams", "0"));
                    } else if ("trial".equals(tab)) {
                        entry.code = obj.optString("account_code", obj.optString("code", "—"));
                        entry.description = obj.optString("account_name_ar", obj.optString("account_name", "—"));
                        entry.debit = safeDouble(obj.optString("total_debit", "0"));
                        entry.credit = safeDouble(obj.optString("total_credit", "0"));
                        entry.goldDebit = safeDouble(obj.optString("total_gold_debit", "0"));
                        entry.goldCredit = safeDouble(obj.optString("total_gold_credit", "0"));
                    }
                    
                    entriesList.add(entry);
                }
            }
            
            filterData(etSearch != null ? etSearch.getText().toString() : "");
            updateSummary();
            showEmptyState(entriesList.isEmpty());
            
        } catch (Exception e) {
            Log.e(TAG, "Error parsing: " + e.getMessage());
            showEmptyState(true);
        }
    }

    private double safeDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (Exception e) {
            return 0.0;
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
            tvTotalGoldVolume.setText(String.format(Locale.US, "%.3f g", totalGold));
        }
        
        if (tvBalanceStatus != null) {
            boolean isBalanced = Math.abs(totalDebit - totalCredit) < 0.01;
            tvBalanceStatus.setText(isBalanced ? 
                (isArabic ? "✓ متوازن" : "✓ Balanced") : 
                (isArabic ? "⚠️ غير متوازن" : "⚠️ Unbalanced"));
            tvBalanceStatus.setTextColor(isBalanced ? Color.parseColor("#2ECC71") : Color.parseColor("#E74C3C"));
        }
    }

    private void filterData(String query) {
        filteredList.clear();
        
        for (AccountingEntry entry : entriesList) {
            if (query == null || query.isEmpty() ||
                (entry.code != null && entry.code.toLowerCase().contains(query.toLowerCase())) ||
                (entry.description != null && entry.description.toLowerCase().contains(query.toLowerCase()))) {
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
        if (getContext() == null) return;
        
        if (!permissionManager.canEdit("accounting") && !permissionManager.isAdmin()) {
            Toast.makeText(getContext(), 
                isArabic ? "⛔ غير مصرح" : "⛔ Access Denied",
                Toast.LENGTH_SHORT).show();
            return;
        }
        
        Dialog dialog = new Dialog(getContext());
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
        MaterialButton btnCancel = dialog.findViewById(R.id.btnDialogCancel);
        MaterialButton btnSave = dialog.findViewById(R.id.btnDialogSave);
        
        String[] karats = {"24K", "22K", "21K", "18K", "14K"};
        ArrayAdapter<String> karatAdapter = new ArrayAdapter<>(getContext(), 
            android.R.layout.simple_spinner_dropdown_item, karats);
        if (spKarat != null) spKarat.setAdapter(karatAdapter);
        
        if (btnCancel != null) btnCancel.setOnClickListener(v -> dialog.dismiss());
        
        if (btnSave != null) {
            btnSave.setOnClickListener(v -> {
                String ref = etRef != null ? etRef.getText().toString().trim() : "";
                String desc = etDesc != null ? etDesc.getText().toString().trim() : "";
                String grossStr = etGrossWeight != null ? etGrossWeight.getText().toString().trim() : "";
                String cashStr = etCashAmount != null ? etCashAmount.getText().toString().trim() : "";
                
                if (ref.isEmpty() || desc.isEmpty()) {
                    Toast.makeText(getContext(), 
                        isArabic ? "يرجى تعبئة المرجع والبيان" : "Please fill reference and description", 
                        Toast.LENGTH_SHORT).show();
                    return;
                }
                
                double grossWeight = grossStr.isEmpty() ? 0 : Double.parseDouble(grossStr);
                double cashAmount = cashStr.isEmpty() ? 0 : Double.parseDouble(cashStr);
                String karat = spKarat != null ? spKarat.getSelectedItem().toString() : "21K";
                
                createJournalEntry(ref, desc, grossWeight, karat, cashAmount);
                dialog.dismiss();
            });
        }
        
        dialog.show();
    }

    private void createJournalEntry(String ref, String desc, double grossWeight, String karat, double cashAmount) {
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("reference", ref);
                payload.put("description", desc);
                payload.put("entry_date", new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date()));
                payload.put("source_type", "manual");
                
                double karatFactor = getKaratFactor(karat);
                double net24K = grossWeight * karatFactor;
                
                JSONArray lines = new JSONArray();
                
                JSONObject debitLine = new JSONObject();
                debitLine.put("account_id", 1);
                debitLine.put("description", "شراء " + ref);
                debitLine.put("debit", cashAmount);
                debitLine.put("credit", 0);
                debitLine.put("gold_debit", net24K);
                debitLine.put("gold_credit", 0);
                lines.put(debitLine);
                
                JSONObject creditLine = new JSONObject();
                creditLine.put("account_id", 2);
                creditLine.put("description", "دفع " + ref);
                creditLine.put("debit", 0);
                creditLine.put("credit", cashAmount);
                creditLine.put("gold_debit", 0);
                creditLine.put("gold_credit", 0);
                lines.put(creditLine);
                
                payload.put("lines", lines);
                payload.put("total_debit", cashAmount);
                payload.put("total_credit", cashAmount);
                payload.put("total_gold_debit", net24K);
                payload.put("total_gold_credit", 0);
                
                MiraApiClient.ApiResponse response = apiClient.createJournalEntry(payload);
                
                mainHandler.post(() -> {
                    if (response != null && response.isSuccess) {
                        Toast.makeText(getContext(), 
                            isArabic ? "✅ تم إنشاء القيد" : "✅ Entry created", 
                            Toast.LENGTH_SHORT).show();
                        loadData(currentTab);
                    } else {
                        String error = response != null ? response.error : "Unknown";
                        Toast.makeText(getContext(), 
                            isArabic ? "❌ فشل: " + error : "❌ Failed: " + error, 
                            Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(() -> {
                    Toast.makeText(getContext(), 
                        isArabic ? "❌ خطأ في الإنشاء" : "❌ Error", 
                        Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private double getKaratFactor(String karat) {
        switch (karat.toUpperCase()) {
            case "24K": return 1.0;
            case "22K": return 0.9166;
            case "21K": return 0.875;
            case "18K": return 0.750;
            case "14K": return 0.5833;
            default: return 0.875;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }

    private static class AccountingEntry {
        int id;
        String code;
        String description;
        double debit;
        double credit;
        double goldDebit;
        double goldCredit;
    }

    private class AccountingAdapter extends RecyclerView.Adapter<AccountingAdapter.ViewHolder> {
        
        private final List<AccountingEntry> items;
        
        AccountingAdapter(List<AccountingEntry> items) {
            this.items = items;
        }
        
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_accounting_entry, parent, false);
            return new ViewHolder(view);
        }
        
        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            AccountingEntry entry = items.get(position);
            holder.tvCode.setText(entry.code != null ? entry.code : "—");
            holder.tvDescription.setText(entry.description != null ? entry.description : "—");
            holder.tvGold.setText(String.format(Locale.US, "%.3f g", entry.goldDebit));
            holder.tvDebit.setText(String.format(Locale.US, "+%,.2f", entry.debit));
            holder.tvCredit.setText(String.format(Locale.US, "-%,.2f", entry.credit));
        }
        
        @Override
        public int getItemCount() {
            return items.size();
        }
        
        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvCode, tvDescription, tvGold, tvDebit, tvCredit;
            
            ViewHolder(View itemView) {
                super(itemView);
                tvCode = itemView.findViewById(R.id.tvRowCode);
                tvDescription = itemView.findViewById(R.id.tvRowDescription);
                tvGold = itemView.findViewById(R.id.tvRowGold);
                tvDebit = itemView.findViewById(R.id.tvRowDebit);
                tvCredit = itemView.findViewById(R.id.tvRowCredit);
            }
        }
    }
}
