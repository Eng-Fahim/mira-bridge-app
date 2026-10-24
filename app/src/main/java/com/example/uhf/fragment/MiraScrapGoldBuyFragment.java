package com.example.uhf.fragment;

import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.tools.SessionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class MiraScrapGoldBuyFragment extends Fragment {

    // Views
    private TextInputEditText etCustomerName, etCustomerPhone, etWeight, etMarketPrice, etDeduction;
    private AutoCompleteTextView ddKarat;
    private TextView tvTotalAmount, tvRemaining;
    private MaterialButton btnSavePurchase, btnAddPayment;
    private LinearLayout llPaymentsContainer;

    // Data
    private String[] karats = {"24", "22", "21", "18", "14"};
    private final String[] CURRENCIES = {"YER", "SAR", "USD"};
    // أسعار صرف مبدئية (يفضل جلبها من السيرفر لاحقاً)
    private final double[] EXCHANGE_RATES = {1.0, 530.0, 2000.0}; 
    
    private final List<View> paymentRows = new ArrayList<>();
    private double totalGoldValue = 0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_scrap_gold_buy, container, false);

        initViews(view);
        setupKaratDropdown();
        setupCalculations();
        
        // إضافة سطر دفع افتراضي
        addPaymentRow("YER", 0.0);

        btnSavePurchase.setOnClickListener(v -> savePurchase());
        btnAddPayment.setOnClickListener(v -> addPaymentRow("YER", 0.0));

        return view;
    }

    private void initViews(View view) {
        etCustomerName = view.findViewById(R.id.etCustomerName);
        etCustomerPhone = view.findViewById(R.id.etCustomerPhone);
        etWeight = view.findViewById(R.id.etWeight);
        etMarketPrice = view.findViewById(R.id.etMarketPrice);
        etDeduction = view.findViewById(R.id.etDeduction);
        ddKarat = view.findViewById(R.id.ddKarat);
        tvTotalAmount = view.findViewById(R.id.tvTotalAmount);
        tvRemaining = view.findViewById(R.id.tvRemaining);
        btnSavePurchase = view.findViewById(R.id.btnSavePurchase);
        btnAddPayment = view.findViewById(R.id.btnAddPayment);
        llPaymentsContainer = view.findViewById(R.id.llPaymentsContainer);
    }

    private void setupKaratDropdown() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_dropdown_item_1line, karats);
        ddKarat.setAdapter(adapter);
        ddKarat.setText("21", false);

        ddKarat.setOnItemClickListener((parent, view, position, id) -> calculateTotal());
    }

    private void setupCalculations() {
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { calculateTotal(); }
            @Override public void afterTextChanged(Editable s) {}
        };

        etWeight.addTextChangedListener(watcher);
        etMarketPrice.addTextChangedListener(watcher);
        etDeduction.addTextChangedListener(watcher);
    }

    private void calculateTotal() {
        try {
            double weight = getDoubleFromEditText(etWeight);
            double marketPrice = getDoubleFromEditText(etMarketPrice);
            double deduction = getDoubleFromEditText(etDeduction);

            if (weight > 0 && marketPrice > 0) {
                double totalBeforeDeduction = weight * marketPrice;
                double totalDeduction = weight * deduction;
                totalGoldValue = totalBeforeDeduction - totalDeduction;

                tvTotalAmount.setText(String.format(Locale.US, "%,.2f YER", totalGoldValue));
            } else {
                totalGoldValue = 0;
                tvTotalAmount.setText("0.00 YER");
            }
            updateRemainingBalance();
        } catch (Exception e) {
            tvTotalAmount.setText("0.00 YER");
        }
    }

    private void addPaymentRow(String defaultCurrency, double amount) {
        View row = getLayoutInflater().inflate(R.layout.item_payment_row, llPaymentsContainer, false);
        
        Spinner spCurrency = row.findViewById(R.id.spCurrency);
        TextInputEditText etAmount = row.findViewById(R.id.etAmount);
        ImageButton btnRemove = row.findViewById(R.id.btnRemove);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, CURRENCIES);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spCurrency.setAdapter(adapter);
        
        int index = Arrays.asList(CURRENCIES).indexOf(defaultCurrency);
        spCurrency.setSelection(index >= 0 ? index : 0);

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateRemainingBalance(); }
            @Override public void afterTextChanged(Editable s) {}
        };
        etAmount.addTextChangedListener(watcher);
        
        spCurrency.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { updateRemainingBalance(); }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnRemove.setOnClickListener(v -> {
            if (llPaymentsContainer.getChildCount() > 1) {
                llPaymentsContainer.removeView(row);
                paymentRows.remove(row);
                updateRemainingBalance();
            } else {
                Toast.makeText(requireContext(), "يجب أن يبقى سطر دفع واحد على الأقل", Toast.LENGTH_SHORT).show();
            }
        });

        paymentRows.add(row);
        llPaymentsContainer.addView(row);
        
        if (amount > 0) etAmount.setText(String.valueOf(amount));
    }

    private void updateRemainingBalance() {
        double totalPaidBase = 0;
        for (View row : paymentRows) {
            Spinner sp = row.findViewById(R.id.spCurrency);
            TextInputEditText et = row.findViewById(R.id.etAmount);
            int pos = sp.getSelectedItemPosition();
            
            if (pos >= 0) {
                double rate = EXCHANGE_RATES[pos];
                double amount = getDoubleFromEditText(et);
                totalPaidBase += (amount * rate);
            }
        }
        
        double remaining = totalGoldValue - totalPaidBase;
        
        if (Math.abs(remaining) < 1) {
            tvRemaining.setText(String.format(Locale.US, "%,.2f YER", remaining));
            tvRemaining.setTextColor(Color.parseColor("#16A34A")); // أخضر (متطابق)
        } else if (remaining > 0) {
            tvRemaining.setText(String.format(Locale.US, "%,.2f YER", remaining));
            tvRemaining.setTextColor(Color.parseColor("#DC2626")); // أحمر (ناقص)
        } else {
            tvRemaining.setText(String.format(Locale.US, "+ %,.2f YER", Math.abs(remaining)));
            tvRemaining.setTextColor(Color.parseColor("#F59E0B")); // برتقالي (زيادة)
        }
    }

    private void savePurchase() {
        String name = etCustomerName.getText() != null ? etCustomerName.getText().toString().trim() : "";
        String phone = etCustomerPhone.getText() != null ? etCustomerPhone.getText().toString().trim() : "";
        double weight = getDoubleFromEditText(etWeight);
        double marketPrice = getDoubleFromEditText(etMarketPrice);
        double deduction = getDoubleFromEditText(etDeduction);
        
        if (weight <= 0 || marketPrice <= 0) {
            Toast.makeText(requireContext(), getString(R.string.error_enter_weight_price), Toast.LENGTH_SHORT).show();
            return;
        }

        JSONArray paymentsArray = new JSONArray();
        double totalPaidBase = 0;

        for (View row : paymentRows) {
            Spinner sp = row.findViewById(R.id.spCurrency);
            TextInputEditText et = row.findViewById(R.id.etAmount);
            int pos = sp.getSelectedItemPosition();
            double amount = getDoubleFromEditText(et);

            if (amount > 0 && pos >= 0) {
                try {
                    double rate = EXCHANGE_RATES[pos];
                    double base = amount * rate;
                    totalPaidBase += base;

                    JSONObject pObj = new JSONObject();
                    pObj.put("currency", CURRENCIES[pos]);
                    pObj.put("amount", amount);
                    pObj.put("exchange_rate", rate);
                    pObj.put("base_amount", base);
                    paymentsArray.put(pObj);
                } catch (Exception e) { e.printStackTrace(); }
            }
        }

        if (Math.abs(totalPaidBase - totalGoldValue) > 1) {
            Toast.makeText(requireContext(), "⚠️ إجمالي المدفوعات لا يطابق قيمة الذهب", Toast.LENGTH_LONG).show();
            return;
        }

        JSONObject payload = new JSONObject();
        try {
            payload.put("customer_name", name);
            payload.put("customer_phone", phone);
            payload.put("gross_weight_grams", weight);
            payload.put("gold_karat", ddKarat.getText().toString() + "K");
            payload.put("market_price_per_gram", marketPrice);
            payload.put("deduction_per_gram", deduction);
            payload.put("total_amount", totalGoldValue);
            payload.put("payments", paymentsArray); // ✅ إرسال مصفوفة المدفوعات

            MiraBranchesManager branchesManager = MiraBranchesManager.getInstance(requireContext());
            payload.put("branch_id", branchesManager.getActiveBranchId());

            SessionManager sessionManager = new SessionManager(requireContext());
            payload.put("user_id", sessionManager.getUserId());

        } catch (Exception e) { e.printStackTrace(); }

        btnSavePurchase.setEnabled(false);
        btnSavePurchase.setText("جاري الحفظ...");

        new Thread(() -> {
            MiraApiClient api = MiraApiClient.getInstance(requireContext());
            MiraApiClient.ApiResponse response = api.saveScrapPurchase(payload);

            if (getActivity() == null || isDetached()) return;

            getActivity().runOnUiThread(() -> {
                btnSavePurchase.setEnabled(true);
                btnSavePurchase.setText(R.string.save_purchase);

                if (response.isSuccess) {
                    Toast.makeText(requireContext(), getString(R.string.purchase_saved_success), Toast.LENGTH_LONG).show();
                    clearFields();
                } else {
                    String errorMsg = response.error != null ? response.error : "فشل الاتصال بالسيرفر";
                    Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private double getDoubleFromEditText(TextInputEditText editText) {
        if (editText == null || editText.getText() == null) return 0.0;
        String text = editText.getText().toString().trim();
        if (text.isEmpty()) return 0.0;
        try { return Double.parseDouble(text); } catch (NumberFormatException e) { return 0.0; }
    }

    private void clearFields() {
        etWeight.setText("");
        etMarketPrice.setText("");
        etDeduction.setText("");
        etCustomerName.setText("");
        etCustomerPhone.setText("");
        tvTotalAmount.setText("0.00 YER");
        
        llPaymentsContainer.removeAllViews();
        paymentRows.clear();
        addPaymentRow("YER", 0.0);
        updateRemainingBalance();
    }
}
