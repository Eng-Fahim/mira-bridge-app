package com.example.uhf.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

public class SetupStep4OpeningBalancesFragment extends BaseSetupStepFragment {
    private static final String TAG = "SetupStep4";

    // Containers
    private LinearLayout cashContainer;
    private LinearLayout banksContainer;
    private LinearLayout goldContainer;
    private LinearLayout receivablesContainer;
    private LinearLayout payablesContainer;
    private LinearLayout fixedAssetsContainer;

    // Diamonds
    private TextInputEditText etDiamondValue;

    // Buttons
    private MaterialButton btnAddCash, btnAddBank, btnAddGold;
    private MaterialButton btnAddReceivable, btnAddPayable, btnAddFixedAsset;
    private MaterialButton btnBack, btnNext;

    // Summary
    private TextView tvTotalAssets, tvTotalGold, tvCapital;

    private MiraApiClient apiClient;
    private double goldPrice24k = 65000;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_setup_step4_opening, container, false);

        apiClient = MiraApiClient.getInstance(requireContext());

        initViews(view);
        setupListeners();
        loadGoldPrice();

        // ✅ نضيف صفًا افتراضيًا لكل قسم
        addCashRow();
        addBankRow();
        addGoldRow();
        addReceivableRow();
        addPayableRow();
        addFixedAssetRow();

        return view;
    }

    private void initViews(View v) {
        cashContainer = v.findViewById(R.id.cashContainer);
        banksContainer = v.findViewById(R.id.banksContainer);
        goldContainer = v.findViewById(R.id.goldContainer);
        receivablesContainer = v.findViewById(R.id.receivablesContainer);
        payablesContainer = v.findViewById(R.id.payablesContainer);
        fixedAssetsContainer = v.findViewById(R.id.fixedAssetsContainer);
        etDiamondValue = v.findViewById(R.id.etDiamondValue);

        btnAddCash = v.findViewById(R.id.btnAddCash);
        btnAddBank = v.findViewById(R.id.btnAddBank);
        btnAddGold = v.findViewById(R.id.btnAddGold);
        btnAddReceivable = v.findViewById(R.id.btnAddReceivable);
        btnAddPayable = v.findViewById(R.id.btnAddPayable);
        btnAddFixedAsset = v.findViewById(R.id.btnAddFixedAsset);

        btnBack = v.findViewById(R.id.btnBack);
        btnNext = v.findViewById(R.id.btnNext);

        tvTotalAssets = v.findViewById(R.id.tvTotalAssets);
        tvTotalGold = v.findViewById(R.id.tvTotalGold);
        tvCapital = v.findViewById(R.id.tvCapital);
    }

    private void setupListeners() {
        btnAddCash.setOnClickListener(v -> addCashRow());
        btnAddBank.setOnClickListener(v -> addBankRow());
        btnAddGold.setOnClickListener(v -> addGoldRow());
        btnAddReceivable.setOnClickListener(v -> addReceivableRow());
        btnAddPayable.setOnClickListener(v -> addPayableRow());
        btnAddFixedAsset.setOnClickListener(v -> addFixedAssetRow());

        etDiamondValue.addTextChangedListener(new SimpleWatcher(this::updateSummary));

        btnBack.setOnClickListener(v -> requireActivity().onBackPressed());
        btnNext.setOnClickListener(v -> saveAndNext());
    }

    // ═══════════════════════════════════════════════════════
    //  Add Rows
    // ═══════════════════════════════════════════════════════

    private void addCashRow() {
        View row = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_setup_cash_row, cashContainer, false);

        TextInputEditText etAmount = row.findViewById(R.id.etCashAmount);
        TextInputEditText etNotes = row.findViewById(R.id.etCashNotes);
        ImageButton btnRemove = row.findViewById(R.id.btnRemoveCash);

        etAmount.addTextChangedListener(new SimpleWatcher(this::updateSummary));
        btnRemove.setOnClickListener(v -> {
            if (cashContainer.getChildCount() > 1) {
                cashContainer.removeView(row);
                updateSummary();
            } else {
                etAmount.setText("0");
            }
        });

        cashContainer.addView(row);
    }

    private void addBankRow() {
        View row = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_setup_bank_row, banksContainer, false);

        TextInputEditText etAmount = row.findViewById(R.id.etBankAmount);
        ImageButton btnRemove = row.findViewById(R.id.btnRemoveBank);

        etAmount.addTextChangedListener(new SimpleWatcher(this::updateSummary));
        btnRemove.setOnClickListener(v -> {
            if (banksContainer.getChildCount() > 1) {
                banksContainer.removeView(row);
                updateSummary();
            } else {
                etAmount.setText("0");
            }
        });

        banksContainer.addView(row);
    }

    private void addGoldRow() {
        View row = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_setup_gold_row, goldContainer, false);

        TextInputEditText etWeight = row.findViewById(R.id.etGoldWeight);
        TextInputEditText etPrice = row.findViewById(R.id.etGoldPrice);
        AutoCompleteTextView spKarat = row.findViewById(R.id.spGoldKarat);
        ImageButton btnRemove = row.findViewById(R.id.btnRemoveGold);

        String[] karats = {"24K", "22K", "21K", "18K", "14K"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            karats
        );
        spKarat.setAdapter(adapter);
        spKarat.setText("21K", false);
        etPrice.setText(String.format(Locale.US, "%.0f", goldPrice24k));

        etWeight.addTextChangedListener(new SimpleWatcher(this::updateSummary));
        etPrice.addTextChangedListener(new SimpleWatcher(this::updateSummary));
        spKarat.setOnItemClickListener((p, v, pos, id) -> updateSummary());

        btnRemove.setOnClickListener(v -> {
            if (goldContainer.getChildCount() > 1) {
                goldContainer.removeView(row);
                updateSummary();
            } else {
                etWeight.setText("0");
            }
        });

        goldContainer.addView(row);
    }

    private void addReceivableRow() {
        addGenericRow(receivablesContainer, "اسم العميل");
    }

    private void addPayableRow() {
        addGenericRow(payablesContainer, "اسم المورد");
    }

    private void addFixedAssetRow() {
        addGenericRow(fixedAssetsContainer, "اسم الأصل");
    }

    private void addGenericRow(LinearLayout container, String nameHint) {
        View row = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_setup_generic_row, container, false);

        TextInputEditText etName = row.findViewById(R.id.etName);
        TextInputEditText etAmount = row.findViewById(R.id.etAmount);
        ImageButton btnRemove = row.findViewById(R.id.btnRemove);

        etName.setHint(nameHint);
        etAmount.addTextChangedListener(new SimpleWatcher(this::updateSummary));

        btnRemove.setOnClickListener(v -> {
            if (container.getChildCount() > 1) {
                container.removeView(row);
                updateSummary();
            } else {
                etAmount.setText("0");
            }
        });

        container.addView(row);
    }

    // ═══════════════════════════════════════════════════════
    //  Summary
    // ═══════════════════════════════════════════════════════

    private void updateSummary() {
        double totalCash = 0;
        double totalBanks = 0;
        double totalGoldMoney = 0;
        double totalGold24k = 0;
        double totalReceivables = 0;
        double totalFixedAssets = 0;

        // Cash
        for (int i = 0; i < cashContainer.getChildCount(); i++) {
            TextInputEditText et = cashContainer.getChildAt(i).findViewById(R.id.etCashAmount);
            totalCash += parseDouble(et);
        }

        // Banks
        for (int i = 0; i < banksContainer.getChildCount(); i++) {
            TextInputEditText et = banksContainer.getChildAt(i).findViewById(R.id.etBankAmount);
            totalBanks += parseDouble(et);
        }

        // Gold
        for (int i = 0; i < goldContainer.getChildCount(); i++) {
            View row = goldContainer.getChildAt(i);
            TextInputEditText etWeight = row.findViewById(R.id.etGoldWeight);
            TextInputEditText etPrice = row.findViewById(R.id.etGoldPrice);
            AutoCompleteTextView spKarat = row.findViewById(R.id.spGoldKarat);

            double weight = parseDouble(etWeight);
            double price = parseDouble(etPrice);
            if (price <= 0) price = goldPrice24k;

            String karat = spKarat.getText().toString();
            double factor = karatFactor(karat);

            double weight24k = weight * factor;
            totalGold24k += weight24k;
            totalGoldMoney += weight24k * price;
        }

        // Diamonds
        double diamonds = parseDouble(etDiamondValue);

        // Receivables
        for (int i = 0; i < receivablesContainer.getChildCount(); i++) {
            TextInputEditText et = receivablesContainer.getChildAt(i).findViewById(R.id.etAmount);
            totalReceivables += parseDouble(et);
        }

        // Fixed Assets
        for (int i = 0; i < fixedAssetsContainer.getChildCount(); i++) {
            TextInputEditText et = fixedAssetsContainer.getChildAt(i).findViewById(R.id.etAmount);
            totalFixedAssets += parseDouble(et);
        }

        double totalAssets = totalCash + totalBanks + totalGoldMoney +
                             diamonds + totalReceivables + totalFixedAssets;

        tvTotalAssets.setText(String.format(Locale.US, "%,.2f", totalAssets));
        tvTotalGold.setText(String.format(Locale.US, "%.3f g", totalGold24k));
        tvCapital.setText(String.format(Locale.US, "%,.2f", totalAssets));
    }

    private double karatFactor(String karat) {
        switch (karat.toUpperCase()) {
            case "24K": return 1.0;
            case "22K": return 0.9166;
            case "21K": return 0.875;
            case "18K": return 0.75;
            case "14K": return 0.5833;
            default: return 0.875;
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Save
    // ═══════════════════════════════════════════════════════

    private void saveAndNext() {
        btnNext.setEnabled(false);
        btnNext.setText("جاري الحفظ...");

        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject();
                JSONArray balances = new JSONArray();

                // ─── Cash ───
                for (int i = 0; i < cashContainer.getChildCount(); i++) {
                    View row = cashContainer.getChildAt(i);
                    TextInputEditText etAmount = row.findViewById(R.id.etCashAmount);
                    TextInputEditText etNotes = row.findViewById(R.id.etCashNotes);

                    double amount = parseDouble(etAmount);
                    if (amount > 0) {
                        JSONObject b = new JSONObject();
                        b.put("type", "cash");
                        b.put("amount_money", amount);
                        b.put("currency", "YER");
                        b.put("description", etNotes.getText().toString());
                        balances.put(b);
                    }
                }

                // ─── Banks ───
                for (int i = 0; i < banksContainer.getChildCount(); i++) {
                    View row = banksContainer.getChildAt(i);
                    TextInputEditText etName = row.findViewById(R.id.etBankName);
                    TextInputEditText etAmount = row.findViewById(R.id.etBankAmount);

                    double amount = parseDouble(etAmount);
                    if (amount > 0) {
                        JSONObject b = new JSONObject();
                        b.put("type", "bank");
                        b.put("amount_money", amount);
                        b.put("currency", "YER");
                        b.put("description", etName.getText().toString());
                        balances.put(b);
                    }
                }

                // ─── Gold ───
                for (int i = 0; i < goldContainer.getChildCount(); i++) {
                    View row = goldContainer.getChildAt(i);
                    TextInputEditText etWeight = row.findViewById(R.id.etGoldWeight);
                    TextInputEditText etPrice = row.findViewById(R.id.etGoldPrice);
                    AutoCompleteTextView spKarat = row.findViewById(R.id.spGoldKarat);

                    double weight = parseDouble(etWeight);
                    if (weight > 0) {
                        double price = parseDouble(etPrice);
                        if (price <= 0) price = goldPrice24k;
                        String karat = spKarat.getText().toString();
                        double weight24k = weight * karatFactor(karat);

                        JSONObject b = new JSONObject();
                        b.put("type", "gold");
                        b.put("weight_grams", weight);
                        b.put("karat", karat);
                        b.put("amount_money", weight24k * price);
                        b.put("currency", "YER");
                        b.put("description", "ذهب " + karat);
                        balances.put(b);
                    }
                }

                // ─── Diamonds ───
                double diamonds = parseDouble(etDiamondValue);
                if (diamonds > 0) {
                    JSONObject b = new JSONObject();
                    b.put("type", "diamond");
                    b.put("amount_money", diamonds);
                    b.put("currency", "YER");
                    b.put("description", "ألماس وأحجار");
                    balances.put(b);
                }

                // ─── Receivables ───
                for (int i = 0; i < receivablesContainer.getChildCount(); i++) {
                    View row = receivablesContainer.getChildAt(i);
                    TextInputEditText etName = row.findViewById(R.id.etName);
                    TextInputEditText etAmount = row.findViewById(R.id.etAmount);

                    double amount = parseDouble(etAmount);
                    if (amount > 0) {
                        JSONObject b = new JSONObject();
                        b.put("type", "receivable");
                        b.put("amount_money", amount);
                        b.put("currency", "YER");
                        b.put("description", etName.getText().toString());
                        balances.put(b);
                    }
                }

                // ─── Payables ───
                for (int i = 0; i < payablesContainer.getChildCount(); i++) {
                    View row = payablesContainer.getChildAt(i);
                    TextInputEditText etName = row.findViewById(R.id.etName);
                    TextInputEditText etAmount = row.findViewById(R.id.etAmount);

                    double amount = parseDouble(etAmount);
                    if (amount > 0) {
                        JSONObject b = new JSONObject();
                        b.put("type", "payable");
                        b.put("amount_money", amount);
                        b.put("currency", "YER");
                        b.put("description", etName.getText().toString());
                        balances.put(b);
                    }
                }

                // ─── Fixed Assets ───
                for (int i = 0; i < fixedAssetsContainer.getChildCount(); i++) {
                    View row = fixedAssetsContainer.getChildAt(i);
                    TextInputEditText etName = row.findViewById(R.id.etName);
                    TextInputEditText etAmount = row.findViewById(R.id.etAmount);

                    double amount = parseDouble(etAmount);
                    if (amount > 0) {
                        JSONObject b = new JSONObject();
                        b.put("type", "fixed_asset");
                        b.put("amount_money", amount);
                        b.put("currency", "YER");
                        b.put("description", etName.getText().toString());
                        balances.put(b);
                    }
                }

                if (balances.length() == 0) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        btnNext.setEnabled(true);
                        btnNext.setText("التالي ←");
                        Toast.makeText(requireContext(),
                            "يجب إدخال رصيد واحد على الأقل",
                            Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                payload.put("balances", balances);

                MiraApiClient.ApiResponse response =
                    apiClient.postAccounting("setup/step/4", payload);

                new Handler(Looper.getMainLooper()).post(() -> {
    btnNext.setEnabled(true);
    btnNext.setText("التالي ←");

    if (response != null && response.isSuccess) {
        openNextStep();
    } else {
        String err = response != null ? response.error : "غير معروف";
        Toast.makeText(requireContext(),
            "❌ فشل الحفظ: " + err, Toast.LENGTH_LONG).show();
    }
});
            } catch (Exception e) {
                Log.e(TAG, "save error", e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    btnNext.setEnabled(true);
                    btnNext.setText("التالي ←");
                    Toast.makeText(requireContext(),
                        "❌ " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void loadGoldPrice() {
        new Thread(() -> {
            try {
                MiraApiClient.ApiResponse response =
                    apiClient.getWithAuth("gold-rates");

                if (response != null && response.isSuccess && response.data != null) {
                    JSONObject data = response.data.optJSONObject("data");
                    if (data != null) {
                        double price = data.optDouble("price_per_gram_24k", 0);
                        if (price > 0) {
                            goldPrice24k = price;
                            new Handler(Looper.getMainLooper()).post(() -> {
                                // تحديث كل أسعار الذهب
                                for (int i = 0; i < goldContainer.getChildCount(); i++) {
                                    View row = goldContainer.getChildAt(i);
                                    TextInputEditText etPrice = row.findViewById(R.id.etGoldPrice);
                                    if (etPrice != null) {
                                        etPrice.setText(String.format(Locale.US, "%.0f", price));
                                    }
                                }
                                updateSummary();
                            });
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "loadGoldPrice error", e);
            }
        }).start();
    }

    private double parseDouble(TextInputEditText et) {
        try {
            if (et == null || et.getText() == null) return 0;
            String str = et.getText().toString().trim();
            return str.isEmpty() ? 0 : Double.parseDouble(str);
        } catch (Exception e) {
            return 0;
        }
    }

    private void openNextStep() {
        SetupStep5UsersFragment next = new SetupStep5UsersFragment();
        requireActivity().getSupportFragmentManager()
            .beginTransaction()
            .replace(R.id.fragment_container, next)
            .addToBackStack("SetupStep5")
            .commit();
    }

    private static class SimpleWatcher implements TextWatcher {
        private final Runnable onChanged;
        SimpleWatcher(Runnable onChanged) { this.onChanged = onChanged; }
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
        @Override public void afterTextChanged(Editable s) { onChanged.run(); }
    }
}
