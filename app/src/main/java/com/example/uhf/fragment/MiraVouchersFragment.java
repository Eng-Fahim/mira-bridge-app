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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
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
import com.example.uhf.manager.MiraPriceManager;
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

    private MiraApiClient apiClient;
    private MiraBranchesManager branchesManager;
    private MiraPriceManager priceManager;
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

    private final String[] CURRENCIES = {"YER", "SAR", "USD"};
    private final String[] METHODS_AR = {"نقدي", "تحويل بنكي", "شبكة/محفظة", "شيك"};
    private final String[] METHODS_CODE = {"cash", "bank_transfer", "e_wallet", "check"};

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_vouchers, container, false);
        apiClient = MiraApiClient.getInstance(requireContext());
        branchesManager = MiraBranchesManager.getInstance(requireContext());
        priceManager = MiraPriceManager.getInstance(requireContext());
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
                @Override public void onTabSelected(TabLayout.Tab tab) {
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
            
            // ✅ الترتيب الصحيح: (type, branchId)
            MiraApiClient.ApiResponse response = apiClient.getVouchers(currentType, branchId);
            
            mainHandler.post(() -> {
                if (response != null && response.isSuccess && response.data != null) {
                    parseVouchers(response.data);
                } else {
                    tvEmptyVouchers.setVisibility(View.VISIBLE);
                    rvVouchers.setVisibility(View.GONE);
                    
                    if (response != null && response.error != null) {
                        Toast.makeText(getContext(), "خطأ: " + response.error, Toast.LENGTH_LONG).show();
                    }
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
                    item.partyName = obj.optString("party_name", ""); // ✅ استخراج اسم العميل
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
        }
    }

    private double getRateToYER(String currency) {
        if ("YER".equalsIgnoreCase(currency)) return 1.0;
        if (priceManager != null) {
            double yerRate = priceManager.getExchangeRate(MiraPriceManager.CURRENCY_YER);
            double currencyRate = priceManager.getExchangeRate(currency);
            if (currencyRate > 10) return currencyRate;
            if (yerRate > 0 && currencyRate > 0) return yerRate / currencyRate;
        }
        if ("SAR".equalsIgnoreCase(currency)) return 141.33;
        if ("USD".equalsIgnoreCase(currency)) return 530.0;
        return 1.0;
    }

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
        AutoCompleteTextView ddParty = dialog.findViewById(R.id.ddVoucherParty); // ✅ حقل العميل
        AutoCompleteTextView ddAccount = dialog.findViewById(R.id.ddVoucherAccount);
        Spinner spKarat = dialog.findViewById(R.id.spVoucherKarat);
        EditText etGold = dialog.findViewById(R.id.etVoucherGoldWeight);
        EditText etNotes = dialog.findViewById(R.id.etVoucherNotes);
        LinearLayout llPayments = dialog.findViewById(R.id.llVoucherPaymentsContainer);
        MaterialButton btnAdd = dialog.findViewById(R.id.btnAddVoucherPayment);
        TextView tvTotal = dialog.findViewById(R.id.tvVoucherTotalPaid);
        MaterialButton btnCancel = dialog.findViewById(R.id.btnVoucherCancel);
        MaterialButton btnSave = dialog.findViewById(R.id.btnVoucherSave);

        String[] types = {isArabic ? "قبض" : "Receipt", isArabic ? "صرف" : "Payment"};
        spType.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item, types));

        String[] karats = {"24K", "22K", "21K", "18K"};
        spKarat.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item, karats));

        final List<Integer> accountIds = new ArrayList<>();
        final List<String> accountNames = new ArrayList<>();
        final List<Integer> partyIds = new ArrayList<>();
        final List<String> partyNames = new ArrayList<>();
        final List<View> paymentRows = new ArrayList<>();

        Runnable updateTotal = () -> {
            double totalYer = 0;
            for (View row : paymentRows) {
                Spinner spCurr = row.findViewById(R.id.spVoucherPayCurrency);
                EditText etAmt = row.findViewById(R.id.etVoucherPayAmount);
                TextView tvEquiv = row.findViewById(R.id.tvVoucherPayEquivalent);
                int pos = spCurr.getSelectedItemPosition();
                double amt = 0;
                try { amt = Double.parseDouble(etAmt.getText().toString()); } catch (Exception ignored) {}
                if (pos >= 0 && amt > 0) {
                    String currency = CURRENCIES[pos];
                    double rate = getRateToYER(currency);
                    double base = amt * rate;
                    totalYer += base;
                    tvEquiv.setText(String.format(Locale.US, "%,.2f YER", base));
                } else if (tvEquiv != null) {
                    tvEquiv.setText("0.00 YER");
                }
            }
            tvTotal.setText(String.format(Locale.US, "%,.2f YER", totalYer));
        };

        Runnable addPaymentRow = () -> {
            View row = getLayoutInflater().inflate(R.layout.item_voucher_payment_row, llPayments, false);
            Spinner spCurr = row.findViewById(R.id.spVoucherPayCurrency);
            Spinner spMethod = row.findViewById(R.id.spVoucherPayMethod);
            EditText etAmt = row.findViewById(R.id.etVoucherPayAmount);
            ImageButton btnRemove = row.findViewById(R.id.btnVoucherPayRemove);

            spCurr.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, CURRENCIES));
            spMethod.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, METHODS_AR));

            etAmt.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) { updateTotal.run(); }
                @Override public void afterTextChanged(android.text.Editable s) {}
            });
            spCurr.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { updateTotal.run(); }
                @Override public void onNothingSelected(AdapterView<?> p) {}
            });
            btnRemove.setOnClickListener(v -> {
                if (llPayments.getChildCount() > 1) {
                    llPayments.removeView(row);
                    paymentRows.remove(row);
                    updateTotal.run();
                }
            });

            paymentRows.add(row);
            llPayments.addView(row);
            updateTotal.run();
        };

        addPaymentRow.run();
        btnAdd.setOnClickListener(v -> addPaymentRow.run());

        // جلب الحسابات
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
                    mainHandler.post(() -> ddAccount.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_dropdown_item_1line, accountNames)));
                }
            }
        });

        // جلب العملاء والموردين
        executor.execute(() -> {
            MiraApiClient.ApiResponse res = apiClient.getVoucherParties();
            if (res.isSuccess && res.data != null) {
                JSONArray data = res.data.optJSONArray("data");
                if (data != null) {
                    partyIds.add(0);
                    partyNames.add("— بدون عميل/مورد —");
                    for (int i = 0; i < data.length(); i++) {
                        JSONObject party = data.optJSONObject(i);
                        int pid = party.optInt("id");
                        String pname = party.optString("name", "");
                        String ptype = party.optString("party_type", "");
                        String typeLabel = "customer".equals(ptype) ? "👤" : "🏭";
                        partyIds.add(pid);
                        partyNames.add(typeLabel + " " + pname);
                    }
                    mainHandler.post(() -> {
                        ddParty.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_dropdown_item_1line, partyNames));
                        ddParty.setText(partyNames.get(0), false);
                    });
                }
            }
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnSave.setOnClickListener(v -> {
            int accIndex = accountNames.indexOf(ddAccount.getText().toString());
            if (accIndex < 0) {
                Toast.makeText(getContext(), "اختر الحساب", Toast.LENGTH_SHORT).show();
                return;
            }
            int accountId = accountIds.get(accIndex);

            // ✅ استخراج party_id
            int partyIndex = partyNames.indexOf(ddParty.getText().toString());
            int partyId = (partyIndex > 0 && partyIndex < partyIds.size()) ? partyIds.get(partyIndex) : 0;

            JSONArray paymentsArray = new JSONArray();
            double totalPaid = 0;
            for (View row : paymentRows) {
                Spinner spCurr = row.findViewById(R.id.spVoucherPayCurrency);
                Spinner spMethod = row.findViewById(R.id.spVoucherPayMethod);
                EditText etAmt = row.findViewById(R.id.etVoucherPayAmount);
                double amt = 0;
                try { amt = Double.parseDouble(etAmt.getText().toString()); } catch (Exception ignored) {}
                if (amt <= 0) continue;
                int posC = spCurr.getSelectedItemPosition();
                int posM = spMethod.getSelectedItemPosition();
                if (posC < 0 || posM < 0) continue;
                try {
                    String curr = CURRENCIES[posC];
                    double rate = getRateToYER(curr);
                    double base = amt * rate;
                    totalPaid += base;
                    JSONObject pObj = new JSONObject();
                    pObj.put("currency", curr);
                    pObj.put("amount", amt);
                    pObj.put("exchange_rate", rate);
                    pObj.put("base_amount", base);
                    pObj.put("method", METHODS_CODE[posM]);
                    paymentsArray.put(pObj);
                } catch (Exception ignored) {}
            }
            
            double goldWeight = 0;
            try { goldWeight = Double.parseDouble(etGold.getText().toString()); } catch (Exception ignored) {}
            
            if (totalPaid <= 0 && goldWeight <= 0) {
                Toast.makeText(getContext(), "أدخل مدفوعات أو وزناً ذهبياً", Toast.LENGTH_SHORT).show();
                return;
            }

            createVoucher(
                spType.getSelectedItemPosition() == 0 ? "receipt" : "payment",
                accountId, paymentsArray, goldWeight,
                karats[spKarat.getSelectedItemPosition()],
                etNotes.getText().toString().trim(),
                partyId // ✅ تمرير partyId
            );
            dialog.dismiss();
        });

        dialog.show();
    }

    private void createVoucher(String type, int accountId, JSONArray payments, 
                               double goldWeight, String karat, String notes, int partyId) {
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("voucher_type", type);
                payload.put("branch_id", branchesManager.getActiveBranchId());
                payload.put("account_id", accountId);
                payload.put("party_id", partyId); // ✅ إضافة party_id للـ Payload
                payload.put("payments", payments);
                payload.put("amount_gold_grams", goldWeight);
                payload.put("gold_karat", karat);
                payload.put("notes", notes);

                MiraApiClient.ApiResponse response = apiClient.createVoucherDirect(payload);
                mainHandler.post(() -> {
                    if (response != null && response.isSuccess) {
                        Toast.makeText(getContext(), isArabic ? "✅ تم إنشاء السند" : "✅ Created", Toast.LENGTH_SHORT).show();
                        loadVouchers();
                    } else {
                        String err = response != null && response.error != null ? response.error : "فشل";
                        Toast.makeText(getContext(), "❌ " + err, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) { e.printStackTrace(); }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executor != null && !executor.isShutdown()) executor.shutdown();
    }

    private static class VoucherItem {
        int id;
        String voucherNumber, voucherType, amountMoney, amountGold, paymentMethod, accountName, date;
        String partyName = ""; // ✅ إضافة اسم العميل
    }

    private class VoucherAdapter extends RecyclerView.Adapter<VoucherAdapter.ViewHolder> {
        private List<VoucherItem> items;
        VoucherAdapter(List<VoucherItem> items) { this.items = items; }

        @NonNull @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_voucher, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            VoucherItem item = items.get(position);
            boolean isReceipt = item.voucherType.equals("receipt");
            String typeLabel = isReceipt ? "📥 قبض" : "📤 صرف";
            holder.tvTitle.setText(item.voucherNumber + " - " + typeLabel);
            
            // ✅ عرض اسم العميل إذا وجد، وإلا عرض اسم الحساب
            String subtitle = !item.partyName.isEmpty() 
                ? "👤 " + item.partyName 
                : item.accountName;
            holder.tvAccount.setText(subtitle);
            
            String amountText = String.format(Locale.US, "%,.2f YER", Double.parseDouble(item.amountMoney));
            if (Double.parseDouble(item.amountGold) > 0) {
                amountText += " | " + String.format(Locale.US, "%.2f g", Double.parseDouble(item.amountGold));
            }
            holder.tvAmount.setText(amountText);
            holder.tvAmount.setTextColor(isReceipt ? 0xFF16A34A : 0xFFDC2626);
            
            holder.itemView.setOnClickListener(v -> {
                if (item.id > 0) {
                    MiraVoucherReceiptFragment receipt = MiraVoucherReceiptFragment.newInstance(item.id);
                    receipt.show(getParentFragmentManager(), "VoucherReceipt");
                }
            });

            holder.itemView.setOnLongClickListener(v -> {
                if (item.id > 0) {
                    MiraVoucherDetailFragment detail = 
                        MiraVoucherDetailFragment.newInstance(item.id, item.voucherType);
                    detail.show(getParentFragmentManager(), "VoucherDetail");
                    return true;
                }
                return false;
            });
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
