package com.example.uhf.fragment;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
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

import org.json.JSONObject;

import java.util.Locale;

public class MiraScrapGoldBuyFragment extends Fragment {

    private TextInputEditText etCustomerName, etCustomerPhone, etWeight, etMarketPrice, etDeduction;
    private AutoCompleteTextView ddKarat;
    private TextView tvTotalAmount;
    private MaterialButton btnSavePurchase;

    private String[] karats = {"24", "22", "21", "18", "14"};
    private double currentKarat = 21.0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_scrap_gold_buy, container, false);

        initViews(view);
        setupKaratDropdown();
        setupCalculations();

        btnSavePurchase.setOnClickListener(v -> savePurchase());

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
        btnSavePurchase = view.findViewById(R.id.btnSavePurchase);
    }

    private void setupKaratDropdown() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_dropdown_item_1line, karats);
        ddKarat.setAdapter(adapter);
        ddKarat.setText("21", false); // العيار الافتراضي

        ddKarat.setOnItemClickListener((parent, view, position, id) -> {
            currentKarat = Double.parseDouble(karats[position]);
            calculateTotal();
        });
    }

    private void setupCalculations() {
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                calculateTotal();
            }
            @Override
            public void afterTextChanged(Editable s) {}
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
                double finalTotal = totalBeforeDeduction - totalDeduction;

                tvTotalAmount.setText(String.format(Locale.US, "%.2f", finalTotal));
            } else {
                tvTotalAmount.setText("0.00");
            }
        } catch (Exception e) {
            tvTotalAmount.setText("0.00");
        }
    }

    private double getDoubleFromEditText(TextInputEditText editText) {
        if (editText == null || editText.getText() == null) return 0.0;
        String text = editText.getText().toString().trim();
        if (text.isEmpty()) return 0.0;
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return 0.0;
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

        // تجهيز البيانات للإرسال
        JSONObject payload = new JSONObject();
        try {
            payload.put("customer_name", name);
            payload.put("customer_phone", phone);
            payload.put("gross_weight_grams", weight);
            payload.put("gold_karat", ddKarat.getText().toString() + "K");
            payload.put("market_price_per_gram", marketPrice);
            payload.put("deduction_per_gram", deduction);
            
            double totalAmount = Double.parseDouble(tvTotalAmount.getText().toString());
            payload.put("total_amount", totalAmount);

            // جلب معرف الفرع الحالي
            MiraBranchesManager branchesManager = MiraBranchesManager.getInstance(requireContext());
            payload.put("branch_id", branchesManager.getActiveBranchId());

            // جلب معرف المستخدم الحالي
            SessionManager sessionManager = new SessionManager(requireContext());
            payload.put("user_id", sessionManager.getUserId());

        } catch (Exception e) {
            e.printStackTrace();
        }

        // إظهار حالة التحميل
        btnSavePurchase.setEnabled(false);
        btnSavePurchase.setText(R.string.saving);

        // إرسال الطلب للسيرفر في خيط منفصل
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

    private void clearFields() {
        etWeight.setText("");
        etMarketPrice.setText("");
        etDeduction.setText("");
        etCustomerName.setText("");
        etCustomerPhone.setText("");
        tvTotalAmount.setText("0.00");
    }
}
