package com.example.uhf.fragment;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.PermissionManager;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraOpeningBalancesFragment extends Fragment {

    private static final String TAG = "MiraOpeningBalancesFragment";

    private MiraApiClient apiClient;
    private PermissionManager permissionManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private RecyclerView rvAccounts;
    private TextView tvEmptyAccounts;
    private MaterialButton btnSaveAll;

    private final List<AccountBalance> accountList = new ArrayList<>();
    private AccountBalanceAdapter adapter;
    private boolean isArabic = true;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_opening_balances, container, false);
        
        apiClient = MiraApiClient.getInstance(requireContext());
        permissionManager = PermissionManager.getInstance(requireContext());
        isArabic = Locale.getDefault().getLanguage().equals("ar");

        initViews(view);
        setupListeners();
        applyPermissions();
        loadAccounts();

        return view;
    }

    private void applyPermissions() {
        boolean canEdit = permissionManager.canEdit("accounting") || permissionManager.isAdmin();
        
        if (btnSaveAll != null) {
            btnSaveAll.setEnabled(canEdit);
            btnSaveAll.setAlpha(canEdit ? 1.0f : 0.5f);
        }
        
        if (adapter != null) {
            adapter.setEditable(canEdit);
        }
    }

    private void initViews(View view) {
        rvAccounts = view.findViewById(R.id.rvAccounts);
        tvEmptyAccounts = view.findViewById(R.id.tvEmptyAccounts);
        btnSaveAll = view.findViewById(R.id.btnSaveAll);
        
        rvAccounts.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new AccountBalanceAdapter(accountList);
        rvAccounts.setAdapter(adapter);
    }

    private void setupListeners() {
        btnSaveAll.setOnClickListener(v -> {
            if (permissionManager.canEdit("accounting") || permissionManager.isAdmin()) {
                saveAllBalances();
            } else {
                Toast.makeText(getContext(), 
                    isArabic ? "⛔ غير مصرح لك بالحفظ" : "⛔ Access Denied",
                    Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadAccounts() {
        if (!(permissionManager.canView("accounting") || permissionManager.isAdmin() || 
              permissionManager.isBranchManager() || permissionManager.isAuditor())) {
            tvEmptyAccounts.setVisibility(View.VISIBLE);
            tvEmptyAccounts.setText(isArabic ? "⛔ غير مصرح لك بالوصول" : "⛔ Access Denied");
            rvAccounts.setVisibility(View.GONE);
            return;
        }
        
        executor.execute(() -> {
            MiraApiClient.ApiResponse response = apiClient.getOpeningBalances();
            
            mainHandler.post(() -> {
                if (response != null && response.isSuccess && response.data != null) {
                    parseAccounts(response.data);
                }
            });
        });
    }

    private void parseAccounts(JSONObject data) {
        try {
            accountList.clear();
            JSONArray items = data.optJSONArray("data");
            
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject obj = items.getJSONObject(i);
                    AccountBalance account = new AccountBalance();
                    account.id = obj.optInt("id", i + 1);
                    account.code = obj.optString("account_code", "");
                    account.name = obj.optString("account_name_ar", obj.optString("account_name", ""));
                    account.cashBalance = obj.optString("opening_balance", "0");
                    account.goldBalance = obj.optString("opening_gold_grams", "0");
                    accountList.add(account);
                }
                adapter.notifyDataSetChanged();
                tvEmptyAccounts.setVisibility(View.GONE);
                rvAccounts.setVisibility(View.VISIBLE);
            } else {
                tvEmptyAccounts.setVisibility(View.VISIBLE);
                rvAccounts.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            tvEmptyAccounts.setVisibility(View.VISIBLE);
        }
    }

    private void saveAllBalances() {
        if (!(permissionManager.canEdit("accounting") || permissionManager.isAdmin())) {
            Toast.makeText(getContext(), 
                isArabic ? "⛔ غير مصرح" : "⛔ Access Denied",
                Toast.LENGTH_SHORT).show();
            return;
        }
        
        for (AccountBalance account : accountList) {
            if (account.isModified) {
                saveBalance(account);
            }
        }
        Toast.makeText(getContext(), 
            isArabic ? "✅ تم حفظ الأرصدة" : "✅ Balances saved", 
            Toast.LENGTH_SHORT).show();
    }

    private void saveBalance(AccountBalance account) {
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("account_id", account.id);
                payload.put("opening_balance", Double.parseDouble(account.cashBalance));
                payload.put("opening_gold", Double.parseDouble(account.goldBalance));
                
                apiClient.setOpeningBalance(account.id, 
                    Double.parseDouble(account.cashBalance), 
                    Double.parseDouble(account.goldBalance));
                
                account.isModified = false;
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }

    private static class AccountBalance {
        int id;
        String code;
        String name;
        String cashBalance;
        String goldBalance;
        boolean isModified = false;
    }

    private class AccountBalanceAdapter extends RecyclerView.Adapter<AccountBalanceAdapter.ViewHolder> {
        private List<AccountBalance> items;
        private boolean editable = true;
        
        AccountBalanceAdapter(List<AccountBalance> items) { this.items = items; }
        
        void setEditable(boolean editable) {
            this.editable = editable;
            notifyDataSetChanged();
        }
        
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_opening_balance, parent, false);
            return new ViewHolder(view);
        }
        
        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            AccountBalance account = items.get(position);
            holder.tvCode.setText(account.code);
            holder.tvName.setText(account.name);
            holder.etCash.setText(account.cashBalance);
            holder.etGold.setText(account.goldBalance);
            
            holder.etCash.setEnabled(editable);
            holder.etGold.setEnabled(editable);
            
            holder.etCash.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus && editable) {
                    String newValue = holder.etCash.getText().toString().trim();
                    account.cashBalance = newValue;
                    account.isModified = true;
                }
            });
            
            holder.etGold.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus && editable) {
                    String newValue = holder.etGold.getText().toString().trim();
                    account.goldBalance = newValue;
                    account.isModified = true;
                }
            });
        }
        
        @Override
        public int getItemCount() { return items.size(); }
        
        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvCode, tvName;
            EditText etCash, etGold;
            
            ViewHolder(View itemView) {
                super(itemView);
                tvCode = itemView.findViewById(R.id.tvAccountCode);
                tvName = itemView.findViewById(R.id.tvAccountName);
                etCash = itemView.findViewById(R.id.etCashBalance);
                etGold = itemView.findViewById(R.id.etGoldBalance);
            }
        }
    }
}
