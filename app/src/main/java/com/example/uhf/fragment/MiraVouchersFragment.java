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
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
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

public class MiraVouchersFragment extends Fragment {

    private static final String TAG = "MiraVouchersFragment";

    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private RecyclerView rvVouchers;
    private TextView tvEmptyVouchers;
    private MaterialButton btnNewVoucher;
    private TabLayout tabVoucherTypes;

    private String currentType = "";
    private final List<VoucherItem> voucherList = new ArrayList<>();
    private VoucherAdapter adapter;
    private boolean isArabic = true;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_vouchers, container, false);
        
        apiClient = MiraApiClient.getInstance(requireContext());
        branchesManager = MiraBranchesManager.getInstance(requireContext());
        isArabic = Locale.getDefault().getLanguage().equals("ar");

        initViews(view);
        setupListeners();
        loadVouchers();

        return view;
    }

    private void initViews(View view) {
        rvVouchers = view.findViewById(R.id.rvVouchers);
        tvEmptyVouchers = view.findViewById(R.id.tvEmptyVouchers);
        btnNewVoucher = view.findViewById(R.id.btnNewVoucher);
        tabVoucherTypes = view.findViewById(R.id.tabVoucherTypes);
        
        rvVouchers.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new VoucherAdapter(voucherList);
        rvVouchers.setAdapter(adapter);
        
        if (tabVoucherTypes != null) {
            tabVoucherTypes.addTab(tabVoucherTypes.newTab().setText(isArabic ? "الكل" : "All"));
            tabVoucherTypes.addTab(tabVoucherTypes.newTab().setText(isArabic ? "قبض" : "Receipt"));
            tabVoucherTypes.addTab(tabVoucherTypes.newTab().setText(isArabic ? "صرف" : "Payment"));
        }
    }

    private void setupListeners() {
        if (tabVoucherTypes != null) {
            tabVoucherTypes.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
                @Override
                public void onTabSelected(TabLayout.Tab tab) {
                    switch (tab.getPosition()) {
                        case 0: currentType = ""; break;
                        case 1: currentType = "receipt"; break;
                        case 2: currentType = "payment"; break;
                    }
                    loadVouchers();
                }
                @Override public void onTabUnselected(TabLayout.Tab tab) {}
                @Override public void onTabReselected(TabLayout.Tab tab) {}
            });
        }
        btnNewVoucher.setOnClickListener(v -> showCreateVoucherDialog());
    }

    private void loadVouchers() {
        executor.execute(() -> {
            int branchId = branchesManager != null ? branchesManager.getActiveBranchId() : 0;
            MiraApiClient.ApiResponse response = apiClient.getVouchers(String.valueOf(branchId), currentType);
            
            mainHandler.post(() -> {
                if (response != null && response.isSuccess && response.data != null) {
                    parseVouchers(response.data);
                } else {
                    tvEmptyVouchers.setVisibility(View.VISIBLE);
                    rvVouchers.setVisibility(View.GONE);
                }
            });
        });
    }

    private void parseVouchers(JSONObject data) {
        try {
            voucherList.clear();
            JSONArray items = data.optJSONArray("data");
            
            if (items != null && items.length() > 0) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject obj = items.getJSONObject(i);
                    VoucherItem item = new VoucherItem();
                    item.id = obj.optInt("id", i + 1);
                    item.voucherNumber = obj.optString("voucher_number", "");
                    item.voucherType = obj.optString("voucher_type", "");
                    item.amountMoney = obj.optString("amount_money", "0");
                    item.amountGold = obj.optString("amount_gold_grams", "0");
                    item.paymentMethod = obj.optString("payment_method", "");
                    item.accountName = obj.optString("account_name_ar", "");
                    item.date = obj.optString("created_at", "");
                    voucherList.add(item);
                }
                
                adapter.notifyDataSetChanged();
                tvEmptyVouchers.setVisibility(View.GONE);
                rvVouchers.setVisibility(View.VISIBLE);
            } else {
                tvEmptyVouchers.setVisibility(View.VISIBLE);
                rvVouchers.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            e.printStackTrace();
            tvEmptyVouchers.setVisibility(View.VISIBLE);
            rvVouchers.setVisibility(View.GONE);
        }
    }

    // ═══════════════════════════════════════════════════
    //  إنشاء سند جديد مع اختيار الحساب
    // ═══════════════════════════════════════════════════
    private void showCreateVoucherDialog() {
        if (getContext() == null) return;
        
        Dialog dialog = new Dialog(getContext());
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_create_voucher);
        
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        }
        
        Spinner spType = dialog.findViewById(R.id.spVoucherType);
        AutoCompleteTextView ddAccount = dialog.findViewById(R.id.ddVoucherAccount);
        Spinner spKarat = dialog.findViewById(R.id.spVoucherKarat);
        Spinner spPayment = dialog.findViewById(R.id.spVoucherPaymentMethod);
        EditText etCash = dialog.findViewById(R.id.etVoucherCashAmount);
        EditText etGold = dialog.findViewById(R.id.etVoucherGoldWeight);
        EditText etNotes = dialog.findViewById(R.id.etVoucherNotes);
        MaterialButton btnCancel = dialog.findViewById(R.id.btnVoucherCancel);
        MaterialButton btnSave = dialog.findViewById(R.id.btnVoucherSave);
        
        String[] types = {isArabic ? "قبض" : "Receipt", isArabic ? "صرف" : "Payment"};
        spType.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item, types));
        
        String[] karats = {"24K", "22K", "21K", "18K"};
        spKarat.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item, karats));
        
        String[] payments = {isArabic ? "نقدي" : "cash", isArabic ? "تحويل" : "bank_transfer", isArabic ? "شبكة" : "e_wallet"};
        spPayment.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item, payments));
        
        // ✅ جلب الحسابات
        final List<Integer> accountIds = new ArrayList<>();
        final List<String> accountNames = new ArrayList<>();
        
        executor.execute(() -> {
            MiraApiClient.ApiResponse res = apiClient.getVoucherAccounts();
            if (res.isSuccess && res.data != null) {
                JSONArray data = res.data.optJSONArray("data");
                if (data != null) {
                    for (int i = 0; i < data.length(); i++) {
                        JSONObject acc = data.optJSONObject(i);
                        accountIds.add(acc.optInt("id"));
                        accountNames.add(acc.optString("account_code") + " - " + acc.optString("account_name_ar"));
                    }
                    mainHandler.post(() -> {
                        ddAccount.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_dropdown_item_1line, accountNames));
                    });
                }
            }
        });
        
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        
        btnSave.setOnClickListener(v -> {
            String cashStr = etCash.getText().toString().trim();
            String goldStr = etGold.getText().toString().trim();
            
            double cashAmount = cashStr.isEmpty() ? 0 : Double.parseDouble(cashStr);
            double goldWeight = goldStr.isEmpty() ? 0 : Double.parseDouble(goldStr);
            
            if (cashAmount <= 0 && goldWeight <= 0) {
                Toast.makeText(getContext(), isArabic ? "أدخل مبلغاً أو وزناً" : "Enter amount or weight", Toast.LENGTH_SHORT).show();
                return;
            }

            int accIndex = accountNames.indexOf(ddAccount.getText().toString());
            if (accIndex < 0) {
                Toast.makeText(getContext(), isArabic ? "اختر الحساب" : "Select Account", Toast.LENGTH_SHORT).show();
                return;
            }
            int accountId = accountIds.get(accIndex);
            
            createVoucher(
                spType.getSelectedItemPosition() == 0 ? "receipt" : "payment",
                cashAmount, goldWeight, 
                karats[spKarat.getSelectedItemPosition()],
                payments[spPayment.getSelectedItemPosition()],
                etNotes.getText().toString().trim(),
                accountId
            );
            
            dialog.dismiss();
        });
        
        dialog.show();
    }

    private void createVoucher(String type, double cash, double gold, String karat, 
                               String payment, String notes, int accountId) {
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("voucher_type", type);
                payload.put("branch_id", branchesManager.getActiveBranchId());
                payload.put("account_id", accountId);
                payload.put("amount_money", cash);
                payload.put("amount_gold_grams", gold);
                payload.put("gold_karat", karat);
                payload.put("payment_method", payment);
                payload.put("notes", notes);
                
                MiraApiClient.ApiResponse response = apiClient.createVoucher(payload);
                
                mainHandler.post(() -> {
                    if (response != null && response.isSuccess) {
                        Toast.makeText(getContext(), isArabic ? "✅ تم إنشاء السند" : "✅ Voucher created", Toast.LENGTH_SHORT).show();
                        loadVouchers();
                    } else {
                        String err = response != null && response.error != null ? response.error : "فشل";
                        Toast.makeText(getContext(), "❌ " + err, Toast.LENGTH_LONG).show();
                    }
                });
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

    private static class VoucherItem {
        int id;
        String voucherNumber, voucherType, amountMoney, amountGold, paymentMethod, accountName, date;
    }

    private class VoucherAdapter extends RecyclerView.Adapter<VoucherAdapter.ViewHolder> {
        private List<VoucherItem> items;
        VoucherAdapter(List<VoucherItem> items) { this.items = items; }
        
        @NonNull @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_voucher, parent, false);
            return new ViewHolder(view);
        }
        
        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            VoucherItem item = items.get(position);
            
            boolean isReceipt = item.voucherType.equals("receipt");
            String typeLabel = isReceipt ? (isArabic ? "📥 قبض" : "📥 Receipt") : (isArabic ? "📤 صرف" : "📤 Payment");
            
            holder.tvTitle.setText(item.voucherNumber + " - " + typeLabel);
            holder.tvAccount.setText(item.accountName);
            
            String amountText = String.format(Locale.US, "%,.2f YER", Double.parseDouble(item.amountMoney));
            if (Double.parseDouble(item.amountGold) > 0) {
                amountText += " | " + String.format(Locale.US, "%.2f g", Double.parseDouble(item.amountGold));
            }
            holder.tvAmount.setText(amountText);
            holder.tvAmount.setTextColor(isReceipt ? 0xFF16A34A : 0xFFDC2626);
        }
        
        @Override public int getItemCount() { return items.size(); }
        
        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvTitle, tvAccount, tvAmount;
            ViewHolder(View v) {
                super(v);
                tvTitle = v.findViewById(R.id.tvVoucherNumber);
                tvAccount = v.findViewById(R.id.tvVoucherAccount);
                tvAmount = v.findViewById(R.id.tvVoucherAmount);
            }
        }
    }
}
