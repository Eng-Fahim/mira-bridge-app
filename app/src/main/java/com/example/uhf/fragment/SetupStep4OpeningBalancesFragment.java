package com.example.uhf.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
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

/**
 * المرحلة 4: الأرصدة الافتتاحية
 * 
 * النموذج المحاسبي:
 * - نقدية (صندوق) — إلزامي
 * - ذهب (بالجرام × العيار) — اختياري
 * - ذمم العملاء — اختياري
 * - ذمم الموردين — اختياري
 * - أصول ثابتة — اختياري
 */
public class SetupStep4OpeningBalancesFragment extends Fragment {

    private static final String TAG = "SetupStep4";

    // Cash
    private TextInputEditText etCashBalance;
    private TextInputEditText etCashNotes;

    // Banks
    private TextInputEditText etBankBalance;
    private TextInputEditText etBankName;

    // Gold
    private TextInputEditText etGoldWeight;
    private AutoCompleteTextView spGoldKarat;
    private TextInputEditText etGoldPricePerGram;

    // Diamonds
    private TextInputEditText etDiamondValue;

    // Receivables
    private TextInputEditText etReceivables;

    // Payables
    private TextInputEditText etPayables;

    // Fixed Assets
    private TextInputEditText etFixedAssets;
    private TextInputEditText etFixedAssetsDesc;

    // Summary
    private TextView tvTotalAssets;
    private TextView tvTotalGold;
    private TextView tvCapital;

    private MaterialButton btnBack;
    private MaterialButton btnNext;
    private View pbLoading;

    private MiraApiClient apiClient;
    private double currentGoldPrice = 65000; // سعر افتراضي

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_setup_step4_opening, container, false);
        
        apiClient = MiraApiClient.getInstance(requireContext());
        
        initViews(view);
        setupListeners();
        setupTextWatchers();
        loadGoldPrice();
        
        return view;
    }

    private void initViews(View v) {
        etCashBalance = v.findViewById(R.id.etCashBalance);
        etCashNotes = v.findViewById(R.id.etCashNotes);
        
        etBankBalance = v.findViewById(R.id.etBankBalance);
        etBankName = v.findViewById(R.id.etBankName);
        
        etGoldWeight = v.findViewById(R.id.etGoldWeight);
        spGoldKarat = v.findViewById(R.id.spGoldKarat);
        etGoldPricePerGram = v.findViewById(R.id.etGoldPricePerGram);
        
        etDiamondValue = v.findViewById(R.id.etDiamondValue);
        
        etReceivables = v.findViewById(R.id.etReceivables);
        etPayables = v.findViewById(R.id.etPayables);
        
        etFixedAssets = v.findViewById(R.id.etFixedAssets);
        etFixedAssetsDesc = v.findViewById(R.id.etFixedAssetsDesc);
        
        tvTotalAssets = v.findViewById(R.id.tvTotalAssets);
        tvTotalGold = v.findViewById(R.id.tvTotalGold);
        tvCapital = v.findViewById(R.id.tvCapital);
        
        btnBack = v.findViewById(R.id.btnBack);
        btnNext = v.findViewById(R.id.btnNext);
        pbLoading = v.findViewById(R.id.pbLoading);
        
        // Spinner للعيارات
        String[] karats = {"24K", "22K", "21K", "18K", "14K"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            karats
        );
        spGoldKarat.setAdapter(adapter);
        spGoldKarat.setText("21K", false);
        
        // قيم افتراضية
        etGoldPricePerGram.setText(String.format(Locale.US, "%.0f", currentGoldPrice));
        etCashBalance.setText("0");
        etBankBalance.setText("0");
        etGoldWeight.setText("0");
        etDiamondValue.setText("0");
        etReceivables.setText("0");
        etPayables.setText("0");
        etFixedAssets.setText("0");
    }

    private void setupListeners() {
        btnBack.setOnClickListener(v -> requireActivity().onBackPressed());
        btnNext.setOnClickListener(v -> saveAndNext());
    }

    private void setupTextWatchers() {
        android.text.TextWatcher watcher = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                updateSummary();
            }
        };
        
        etCashBalance.addTextChangedListener(watcher);
        etBankBalance.addTextChangedListener(watcher);
        etGoldWeight.addTextChangedListener(watcher);
        etGoldPricePerGram.addTextChangedListener(watcher);
        etDiamondValue.addTextChangedListener(watcher);
        etReceivables.addTextChangedListener(watcher);
        etPayables.addTextChangedListener(watcher);
        etFixedAssets.addTextChangedListener(watcher);
    }

    private void loadGoldPrice() {
        new Thread(() -> {
            try {
                MiraApiClient.ApiResponse response = 
                    apiClient.getAccounting("gold-rates");
                
                if (response != null && response.isSuccess && response.data != null) {
                    JSONObject data = response.data.optJSONObject("data");
                    if (data != null) {
                        double price = data.optDouble("price_per_gram_24k", 0);
                        if (price > 0) {
                            currentGoldPrice = price;
                            new Handler(Looper.getMainLooper()).post(() -> {
                                etGoldPricePerGram.setText(String.format(Locale.US, "%.0f", price));
                            });
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "loadGoldPrice error", e);
            }
        }).start();
    }

    private void updateSummary() {
        double cash = parseDouble(etCashBalance, 0);
        double bank = parseDouble(etBankBalance, 0);
        double goldWeight = parseDouble(etGoldWeight, 0);
        double goldPrice = parseDouble(etGoldPricePerGram, currentGoldPrice);
        double diamond = parseDouble(etDiamondValue, 0);
        double receivables = parseDouble(etReceivables, 0);
        double fixedAssets = parseDouble(etFixedAssets, 0);
        
        String karat = spGoldKarat.getText().toString();
        double factor = getKaratFactor(karat);
        double gold24k = goldWeight * factor;
        double goldValue = gold24k * goldPrice;
        
        double totalAssets = cash + bank + goldValue + diamond + receivables + fixedAssets;
        
        tvTotalAssets.setText(String.format(Locale.US, "%,.2f", totalAssets));
        tvTotalGold.setText(String.format(Locale.US, "%.3f g", gold24k));
        tvCapital.setText(String.format(Locale.US, "%,.2f", totalAssets));
    }

    private void saveAndNext() {
        double cash = parseDouble(etCashBalance, 0);
        double bank = parseDouble(etBankBalance, 0);
        double goldWeight = parseDouble(etGoldWeight, 0);
        double goldPrice = parseDouble(etGoldPricePerGram, currentGoldPrice);
        double diamond = parseDouble(etDiamondValue, 0);
        double receivables = parseDouble(etReceivables, 0);
        double payables = parseDouble(etPayables, 0);
        double fixedAssets = parseDouble(etFixedAssets, 0);
        
        String karat = spGoldKarat.getText().toString();
        
        // فحص أساسي
        if (cash <= 0 && bank <= 0 && goldWeight <= 0 && 
            diamond <= 0 && receivables <= 0 && fixedAssets <= 0) {
            Toast.makeText(requireContext(), 
                "يجب إدخال رصيد واحد على الأقل", Toast.LENGTH_LONG).show();
            return;
        }
        
        btnNext.setEnabled(false);
        btnNext.setText("جاري الحفظ...");
        
        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject();
                JSONArray balances = new JSONArray();
                
                // Cash
                if (cash > 0) {
                    JSONObject b = new JSONObject();
                    b.put("type", "cash");
                    b.put("amount_money", cash);
                    b.put("currency", "YER");
                    b.put("description", etCashNotes.getText().toString());
                    balances.put(b);
                }
                
                // Bank
                if (bank > 0) {
                    JSONObject b = new JSONObject();
                    b.put("type", "bank");
                    b.put("amount_money", bank);
                    b.put("currency", "YER");
                    b.put("description", etBankName.getText().toString());
                    balances.put(b);
                }
                
                // Gold
                if (goldWeight > 0) {
                    JSONObject b = new JSONObject();
                    b.put("type", "gold");
                    b.put("weight_grams", goldWeight);
                    b.put("karat", karat);
                    b.put("amount_money", goldWeight * getKaratFactor(karat) * goldPrice);
                    b.put("currency", "YER");
                    b.put("description", "مخزون ذهب " + karat);
                    balances.put(b);
                }
                
                // Diamond
                if (diamond > 0) {
                    JSONObject b = new JSONObject();
                    b.put("type", "diamond");
                    b.put("amount_money", diamond);
                    b.put("currency", "YER");
                    b.put("description", "مخزون الألماس والأحجار");
                    balances.put(b);
                }
                
                // Receivables
                if (receivables > 0) {
                    JSONObject b = new JSONObject();
                    b.put("type", "receivable");
                    b.put("amount_money", receivables);
                    b.put("currency", "YER");
                    b.put("description", "ذمم العملاء");
                    balances.put(b);
                }
                
                // Payables (كالتزام — لكن يُحسب في رأس المال)
                if (payables > 0) {
                    JSONObject b = new JSONObject();
                    b.put("type", "payable");
                    b.put("amount_money", payables);
                    b.put("currency", "YER");
                    b.put("description", "ذمم الموردين");
                    balances.put(b);
                }
                
                // Fixed Assets
                if (fixedAssets > 0) {
                    JSONObject b = new JSONObject();
                    b.put("type", "fixed_asset");
                    b.put("amount_money", fixedAssets);
                    b.put("currency", "YER");
                    b.put("description", etFixedAssetsDesc.getText().toString());
                    balances.put(b);
                }
                
                payload.put("balances", balances);
                
                MiraApiClient.ApiResponse response = 
                    apiClient.postAccounting("setup/step/4", payload);
                
                new Handler(Looper.getMainLooper()).post(() -> {
                    btnNext.setEnabled(true);
                    btnNext.setText("التالي ←");
                    
                    if (response != null && response.isSuccess) {
                        Toast.makeText(requireContext(), 
                            "✅ تم حفظ الأرصدة الافتتاحية", Toast.LENGTH_SHORT).show();
                        openNextStep();
                    } else {
                        String error = response != null ? response.error : "غير معروف";
                        Toast.makeText(requireContext(), 
                            "❌ فشل: " + error, Toast.LENGTH_LONG).show();
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

    private double parseDouble(TextInputEditText et, double def) {
        try {
            if (et == null || et.getText() == null) return def;
            String str = et.getText().toString().trim();
            return str.isEmpty() ? def : Double.parseDouble(str);
        } catch (Exception e) {
            return def;
        }
    }

    private double getKaratFactor(String karat) {
        switch (karat.toUpperCase()) {
            case "24K": return 1.0;
            case "22K": return 0.9166;
            case "21K": return 0.875;
            case "18K": return 0.75;
            case "14K": return 0.5833;
            default: return 0.875;
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
}
