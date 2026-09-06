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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public class MiraAccountingFragment extends Fragment {

    private static final String TAG = "MiraAccountingFragment";

    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private PermissionManager permissionManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    private static final ExecutorService executor = Executors.newFixedThreadPool(3);
    
    private final AtomicBoolean isDestroyed = new AtomicBoolean(false);

    private RecyclerView rvAccountingData;
    private ProgressBar pbLoading;
    private TextView tvEmptyData;
    private EditText etSearch;
    private TextView tvTodayRevenue, tvTotalGoldVolume, tvBalanceStatus;
    private TextView tvBranchName; // ✅ جديد - عرض اسم الفرع
    private TabLayout tabAccounting;
    private MaterialButton btnCreateManualEntry;
    private MaterialButton btnFinancialReports;
    private ImageButton btnRefresh;

    private String currentTab = "journal";
    private final List<AccountingEntry> entriesList = new ArrayList<>();
    private final List<AccountingEntry> filteredList = new ArrayList<>();
    private AccountingAdapter adapter;

    private boolean isArabic = true;
    private int currentBranchId = 0; // ✅ جديد

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_accounting, container, false);
        
        isDestroyed.set(false);
        
        apiClient = MiraApiClient.getInstance(requireContext());
        branchesManager = MiraBranchesManager.getInstance(requireContext());
        permissionManager = PermissionManager.getInstance(requireContext());
        isArabic = Locale.getDefault().getLanguage().equals("ar");
        
        // ✅ الحصول على الفرع الحالي
        currentBranchId = branchesManager != null ? branchesManager.getActiveBranchId() : 0;

        initViews(view);
        setupRecyclerView();
        setupListeners();
        applyPermissions();
        updateBranchDisplay(); // ✅ عرض اسم الفرع
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
        tvBranchName = view.findViewById(R.id.tvBranchName); // ✅ جديد
        btnCreateManualEntry = view.findViewById(R.id.btnCreateManualEntry);
        btnFinancialReports = view.findViewById(R.id.btnFinancialReports);
        tabAccounting = view.findViewById(R.id.tabAccounting);
        
        if (tabAccounting != null) {
            tabAccounting.addTab(tabAccounting.newTab().setText(isArabic ? "القيود" : "Journal"));
            tabAccounting.addTab(tabAccounting.newTab().setText(isArabic ? "دليل الحسابات" : "Chart of Accounts"));
            tabAccounting.addTab(tabAccounting.newTab().setText(isArabic ? "ميزان المراجعة" : "Trial Balance"));
        }
    }

    // ✅ عرض اسم الفرع الحالي
    private void updateBranchDisplay() {
        if (tvBranchName != null && branchesManager != null) {
            String branchName = branchesManager.getActiveBranchDisplayName();
            tvBranchName.setText("🏢 " + branchName);
        }
    }

    private void applyPermissions() {
        if (btnCreateManualEntry != null) {
            boolean canEdit = permissionManager.canEdit("accounting") || 
                              permissionManager.isAdmin() || 
                              permissionManager.isBranchManager();
            btnCreateManualEntry.setVisibility(canEdit ? View.VISIBLE : View.GONE);
        }
        
        if (btnFinancialReports != null) {
            boolean canView = permissionManager.canView("accounting") || 
                              permissionManager.canView("reports") || 
                              permissionManager.isAdmin() || 
                              permissionManager.isBranchManager();
            btnFinancialReports.setVisibility(canView ? View.VISIBLE : View.GONE);
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
            btnRefresh.setOnClickListener(v -> {
                refreshCurrentBranch(); // ✅ تحديث الفرع
                loadData(currentTab);
            });
        }
        
        if (btnCreateManualEntry != null) {
            btnCreateManualEntry.setOnClickListener(v -> {
                boolean canEdit = permissionManager.canEdit("accounting") || 
                                  permissionManager.isAdmin() || 
                                  permissionManager.isBranchManager();
                if (canEdit) {
                    showCreateEntryDialog();
                } else {
                    showToast(isArabic ? "⛔ غير مصرح لك بإنشاء قيود" : "⛔ Access Denied");
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
                    showToast(isArabic ? "⛔ غير مصرح لك بالوصول للتقارير المالية" : "⛔ Access Denied");
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

    // ✅ تحديث الفرع الحالي
    private void refreshCurrentBranch() {
        if (branchesManager != null) {
            currentBranchId = branchesManager.getActiveBranchId();
            updateBranchDisplay();
        }
    }

    private void loadData(String tab) {
        if (isDestroyed.get() || !isAdded()) return;
        
        if (permissionManager == null || (!permissionManager.canView("accounting") && 
            !permissionManager.isAdmin() && 
            !permissionManager.isBranchManager() && 
            !"AUDITOR".equals(new com.example.uhf.tools.SessionManager(requireContext()).getRoleString()))) {
            showEmptyState(true);
            if (tvEmptyData != null) {
                tvEmptyData.setText(isArabic ? "⛔ غير مصرح لك بالوصول" : "⛔ Access Denied");
            }
            if (pbLoading != null) pbLoading.setVisibility(View.GONE);
            return;
        }
        
        // ✅ تحديث الفرع قبل التحميل
        refreshCurrentBranch();
        
        if (pbLoading != null) pbLoading.setVisibility(View.VISIBLE);
        if (tvEmptyData != null) tvEmptyData.setVisibility(View.GONE);
        
        executeSafely(() -> {
            try {
                // ✅ استخدام الفرع الحالي في جميع الطلبات
                String branchIdStr = String.valueOf(currentBranchId);
                
                MiraApiClient.ApiResponse response = null;
                
                switch (tab) {
                    case "journal":
                        response = apiClient.getJournalEntries(branchIdStr);
                        break;
                    case "chart":
                        // دليل الحسابات مشترك بين الفروع
                        response = apiClient.getChartOfAccounts();
                        break;
                    case "trial":
                        response = apiClient.getTrialBalance(branchIdStr);
                        break;
                }
                
                final MiraApiClient.ApiResponse finalResponse = response;
                
                mainHandler.post(() -> {
                    if (isDestroyed.get() || !isAdded()) return;
                    
                    if (pbLoading != null) pbLoading.setVisibility(View.GONE);
                    
                    if (finalResponse != null && finalResponse.isSuccess && finalResponse.data != null) {
                        parseAndDisplay(finalResponse.data, tab);
                    } else {
                        showEmptyState(true);
                    }
                });
            } catch (Exception e) {
                logNonFatal("loadData.thread", e);
                mainHandler.post(() -> {
                    if (isDestroyed.get() || !isAdded()) return;
                    if (pbLoading != null) pbLoading.setVisibility(View.GONE);
                    showEmptyState(true);
                });
            }
        });
    }

    private void executeSafely(Runnable task) {
        if (executor != null && !executor.isShutdown() && !executor.isTerminated()) {
            try {
                executor.execute(task);
                return;
            } catch (RejectedExecutionException e) {
                logNonFatal("executeSafely.rejected", e);
            }
        }
        
        new Thread(task).start();
    }

    private void parseAndDisplay(JSONObject data, String tab) {
    try {
        entriesList.clear();
        JSONArray items = data.optJSONArray("data");
        
        if (items == null) {
            JSONObject dataObj = data.optJSONObject("data");
            if (dataObj != null) {
                items = dataObj.optJSONArray("entries");
                if (items == null) {
                    items = dataObj.optJSONArray("accounts");
                }
            }
        }
        
        if (items != null && items.length() > 0) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject obj = items.getJSONObject(i);
                AccountingEntry entry = new AccountingEntry();
                entry.id = i + 1;
                entry.branchId = obj.optInt("branch_id", 0);
                entry.branchName = obj.optString("branch_name", "");
                
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
                    
                    // ✅✅✅ دعم أسماء حقول متعددة للمبالغ
                    entry.debit = safeDouble(
                        obj.optString("total_debit", 
                        obj.optString("debit_total", 
                        obj.optString("sum_debit", "0")))
                    );
                    entry.credit = safeDouble(
                        obj.optString("total_credit", 
                        obj.optString("credit_total", 
                        obj.optString("sum_credit", "0")))
                    );
                    
                    // ✅✅✅ دعم أسماء حقول متعددة للذهب
                    entry.goldDebit = safeDouble(
                        obj.optString("total_gold_debit", 
                        obj.optString("gold_debit", 
                        obj.optString("total_gold_debit_grams", 
                        obj.optString("sum_gold_debit", "0"))))
                    );
                    entry.goldCredit = safeDouble(
                        obj.optString("total_gold_credit", 
                        obj.optString("gold_credit", 
                        obj.optString("total_gold_credit_grams", 
                        obj.optString("sum_gold_credit", "0"))))
                    );
                }
                
                entriesList.add(entry);
            }
        }
        
        filterData(etSearch != null ? etSearch.getText().toString() : "");
        updateSummary();
        showEmptyState(entriesList.isEmpty());
        
    } catch (Exception e) {
        logNonFatal("parseAndDisplay", e);
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
    double totalGoldDebit = 0;
    double totalGoldCredit = 0;
    
    for (AccountingEntry entry : entriesList) {
        totalDebit += entry.debit;
        totalCredit += entry.credit;
        totalGoldDebit += entry.goldDebit;
        totalGoldCredit += entry.goldCredit;
    }
    
    // ✅✅✅ صافي الذهب = المدين - الدائن
    double netGold = totalGoldDebit - totalGoldCredit;
    
    if (tvTodayRevenue != null) {
        tvTodayRevenue.setText(String.format(Locale.US, "%,.2f", totalDebit));
    }
    
    if (tvTotalGoldVolume != null) {
        if (netGold != 0) {
            tvTotalGoldVolume.setText(String.format(Locale.US, "%+.3f g", netGold));
            tvTotalGoldVolume.setTextColor(netGold > 0 ? 
                Color.parseColor("#2ECC71") : Color.parseColor("#E74C3C"));
        } else {
            tvTotalGoldVolume.setText("0.000 g");
            tvTotalGoldVolume.setTextColor(Color.parseColor("#95A5A6"));
        }
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
        if (isDestroyed.get() || !isAdded()) return;
        
        if (tvEmptyData != null) {
            tvEmptyData.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (rvAccountingData != null) {
            rvAccountingData.setVisibility(show ? View.GONE : View.VISIBLE);
        }
    }

    private void showCreateEntryDialog() {
        if (getContext() == null || isDestroyed.get()) return;
        
        boolean canEdit = permissionManager.canEdit("accounting") || 
                          permissionManager.isAdmin() || 
                          permissionManager.isBranchManager();
        if (!canEdit) {
            showToast(isArabic ? "⛔ غير مصرح" : "⛔ Access Denied");
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
                    showToast(isArabic ? "يرجى تعبئة المرجع والبيان" : "Please fill reference and description");
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
        executeSafely(() -> {
            try {
                // ✅ تحديث الفرع قبل الإنشاء
                refreshCurrentBranch();
                
                JSONObject payload = new JSONObject();
                payload.put("reference", ref);
                payload.put("description", desc);
                payload.put("entry_date", new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()));
                payload.put("source_type", "manual");
                payload.put("branch_id", currentBranchId); // ✅ إضافة الفرع
                
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
                    if (isDestroyed.get() || !isAdded()) return;
                    
                    if (response != null && response.isSuccess) {
                        showToast(isArabic ? "✅ تم إنشاء القيد" : "✅ Entry created");
                        loadData(currentTab);
                    } else {
                        String error = response != null ? response.error : "Unknown";
                        showToast((isArabic ? "❌ فشل: " : "❌ Failed: ") + error);
                    }
                });
            } catch (Exception e) {
                logNonFatal("createJournalEntry.thread", e);
                mainHandler.post(() -> {
                    if (isDestroyed.get() || !isAdded()) return;
                    showToast(isArabic ? "❌ خطأ في الإنشاء" : "❌ Error");
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

    private void showToast(String message) {
        try {
            if (isAdded() && !isDestroyed.get() && getContext() != null) {
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            logNonFatal("showToast", e);
        }
    }

    private void logNonFatal(String location, Throwable throwable) {
        try {
            Log.e(TAG, location + ": " + throwable.getMessage(), throwable);
            
            crashlytics.setCustomKey("error_location", location);
            crashlytics.setCustomKey("fragment", "MiraAccountingFragment");
            crashlytics.recordException(throwable);
        } catch (Exception e) {
            Log.e(TAG, "logNonFatal error: " + e.getMessage());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // ✅ تحديث الفرع عند العودة
        if (branchesManager != null) {
            int newBranchId = branchesManager.getActiveBranchId();
            if (newBranchId != currentBranchId) {
                currentBranchId = newBranchId;
                updateBranchDisplay();
                loadData(currentTab);
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        isDestroyed.set(true);
        mainHandler.removeCallbacksAndMessages(null);
    }

    private static class AccountingEntry {
        int id;
        String code;
        String description;
        double debit;
        double credit;
        double goldDebit;
        double goldCredit;
        int branchId; // ✅ جديد
        String branchName; // ✅ جديد
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
    
    // ✅✅✅ عرض صافي الذهب (مدين - دائن)
    double netGold = entry.goldDebit - entry.goldCredit;
    
    if (netGold > 0) {
        holder.tvGold.setText(String.format(Locale.US, "+%.3f g", netGold));
        holder.tvGold.setTextColor(Color.parseColor("#2ECC71"));
    } else if (netGold < 0) {
        holder.tvGold.setText(String.format(Locale.US, "%.3f g", netGold));
        holder.tvGold.setTextColor(Color.parseColor("#E74C3C"));
    } else {
        holder.tvGold.setText("0.000 g");
        holder.tvGold.setTextColor(Color.parseColor("#95A5A6"));
    }
    
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
