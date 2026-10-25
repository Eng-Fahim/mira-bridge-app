package com.example.uhf.fragment;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONObject;

import java.util.Locale;

public class MiraGoldConversionFragment extends Fragment {

    private TextInputEditText etCustomerName, etInWeight, etInWastage, etInPrice, etOutWeight, etOutPrice, etMakingCharge;
    private MaterialAutoCompleteTextView ddInKarat, ddOutKarat;
    private TextView tvInTotal, tvOutTotal, tvSettlementStatus, tvSettlementAmount;
    private MaterialButton btnSaveConversion;
    
    private double inTotalValue = 0, outTotalValue = 0, settlementAmount = 0;
    private final String[] karats = {"24K", "22K", "21K", "18K"};

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_gold_conversion, container, false);
        
        initViews(v);
        setupDropdowns();
        setupListeners();
        
        return v;
    }

    private void initViews(View v) {
        etCustomerName = v.findViewById(R.id.etCustomerName);
        etInWeight = v.findViewById(R.id.etInWeight);
        etInWastage = v.findViewById(R.id.etInWastage);
        etInPrice = v.findViewById(R.id.etInPrice);
        etOutWeight = v.findViewById(R.id.etOutWeight);
        etOutPrice = v.findViewById(R.id.etOutPrice);
        etMakingCharge = v.findViewById(R.id.etMakingCharge);
        
        ddInKarat = v.findViewById(R.id.ddInKarat);
        ddOutKarat = v.findViewById(R.id.ddOutKarat);
        
        tvInTotal = v.findViewById(R.id.tvInTotal);
        tvOutTotal = v.findViewById(R.id.tvOutTotal);
        tvSettlementStatus = v.findViewById(R.id.tvSettlementStatus);
        tvSettlementAmount = v.findViewById(R.id.tvSettlementAmount);
        btnSaveConversion = v.findViewById(R.id.btnSaveConversion);
    }

    private void setupDropdowns() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_dropdown_item_1line, karats);
        ddInKarat.setAdapter(adapter);
        ddOutKarat.setAdapter(adapter);
        
        ddInKarat.setText("21K", false);
        ddOutKarat.setText("21K", false);
    }

    private void setupListeners() {
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                calculateEverything();
            }
            @Override public void afterTextChanged(Editable s) {}
        };

        etInWeight.addTextChangedListener(watcher);
        etInWastage.addTextChangedListener(watcher);
        etInPrice.addTextChangedListener(watcher);
        etOutWeight.addTextChangedListener(watcher);
        etOutPrice.addTextChangedListener(watcher);
        etMakingCharge.addTextChangedListener(watcher);
        
        ddInKarat.setOnItemClickListener((parent, view, position, id) -> calculateEverything());
        ddOutKarat.setOnItemClickListener((parent, view, position, id) -> calculateEverything());
        
        btnSaveConversion.setOnClickListener(v -> saveConversion());
    }

    private void calculateEverything() {
        double inW = getDouble(etInWeight);
        double wastagePercent = getDouble(etInWastage);
        double inP = getDouble(etInPrice);
        
        double outW = getDouble(etOutWeight);
        double outP = getDouble(etOutPrice);
        double makingPerGram = getDouble(etMakingCharge);

        // 1. حساب الذهب الداخل: (الوزن - نسبة الفاقد) × سعر الجرام
        double netInWeight = inW - (inW * (wastagePercent / 100.0));
        inTotalValue = netInWeight * inP;

        // 2. حساب الذهب الخارج: (الوزن × سعر الجرام) + (الوزن × المصنعية)
        outTotalValue = (outW * outP) + (outW * makingPerGram);

        // 3. حساب التسوية
        settlementAmount = inTotalValue - outTotalValue;

        tvInTotal.setText(String.format(Locale.US, "القيمة الصافية: %,.2f", inTotalValue));
        tvOutTotal.setText(String.format(Locale.US, "إجمالي الخارج: %,.2f", outTotalValue));

        if (settlementAmount > 0.01) {
            tvSettlementStatus.setText("المطلوب دفعه للعميل (نحن مدينون له):");
            tvSettlementAmount.setText(String.format(Locale.US, "%,.2f", settlementAmount));
            tvSettlementAmount.setTextColor(0xFFDC2626); // أحمر
        } else if (settlementAmount < -0.01) {
            tvSettlementStatus.setText("المطلوب تحصيله من العميل:");
            tvSettlementAmount.setText(String.format(Locale.US, "%,.2f", Math.abs(settlementAmount)));
            tvSettlementAmount.setTextColor(0xFF16A34A); // أخضر
        } else {
            tvSettlementStatus.setText("لا يوجد مستحقات (تمت المقاصة):");
            tvSettlementAmount.setText("0.00");
            tvSettlementAmount.setTextColor(0xFF1A1A1A);
        }
    }

    private void saveConversion() {
        String customer = etCustomerName.getText() != null ? etCustomerName.getText().toString().trim() : "";
        if (customer.isEmpty()) customer = "عميل صياغة";

        if (inTotalValue <= 0 || outTotalValue <= 0) {
            Toast.makeText(requireContext(), "الرجاء إدخال أوزان وأسعار صحيحة", Toast.LENGTH_SHORT).show();
            return;
        }

        btnSaveConversion.setEnabled(false);
        btnSaveConversion.setText("جاري الاعتماد...");

        JSONObject payload = new JSONObject();
        try {
            payload.put("customer_name", customer);
            
            // بيانات الداخل (نرسل السعر الأصلي للجرام كما يطلبه السيرفر الآن)
            payload.put("in_weight", getDouble(etInWeight));
            payload.put("in_karat", ddInKarat.getText().toString().replace("K", ""));
            payload.put("in_wastage", getDouble(etInWastage));
            payload.put("in_price", getDouble(etInPrice)); // ✅ التعديل: السيرفر يحتاج السعر
            
            // بيانات الخارج (نرسل السعر الأصلي والمصنعية للجرام)
            payload.put("out_weight", getDouble(etOutWeight));
            payload.put("out_karat", ddOutKarat.getText().toString().replace("K", ""));
            payload.put("out_price", getDouble(etOutPrice)); // ✅ التعديل: السيرفر يحتاج السعر
            payload.put("making_charge_per_gram", getDouble(etMakingCharge)); // ✅ التعديل: السيرفر يحتاج مصنعية الجرام
            
            // التسوية (نرسل القيمة التي سيدفعها/يستلمها العميل)
            // إذا كانت settlementAmount سالبة (العميل مدين لنا)، نرسلها موجبة للسيرفر
            // إذا كانت موجبة (نحن مدينون له)، نرسلها سالبة للسيرفر
            payload.put("settlement_amount", -settlementAmount); 
            payload.put("payment_method", "cash"); 
        } catch (Exception ignored) {}

        new Thread(() -> {
            MiraApiClient client = MiraApiClient.getInstance(requireContext());
            MiraApiClient.ApiResponse response = client.processGoldConversion(payload);

            requireActivity().runOnUiThread(() -> {
                btnSaveConversion.setEnabled(true);
                btnSaveConversion.setText("💾 اعتماد أمر الصياغة");

                if (response != null && response.isSuccess) {
                    Toast.makeText(requireContext(), "✅ تم حفظ القيد المحاسبي بنجاح", Toast.LENGTH_LONG).show();
                    etInWeight.setText(""); etInPrice.setText("");
                    etOutWeight.setText(""); etOutPrice.setText("");
                    etMakingCharge.setText(""); etCustomerName.setText("");
                    calculateEverything(); // تصفير الحسابات
                } else {
                    String err = (response != null && response.error != null) ? response.error : "فشل الاتصال بالسيرفر";
                    Toast.makeText(requireContext(), "❌ " + err, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private double getDouble(TextInputEditText et) {
        if (et == null || et.getText() == null) return 0.0;
        String val = et.getText().toString().trim();
        if (val.isEmpty()) return 0.0;
        try { return Double.parseDouble(val); } catch (Exception e) { return 0.0; }
    }
}
