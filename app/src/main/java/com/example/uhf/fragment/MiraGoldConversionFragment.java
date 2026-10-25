package com.example.uhf.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONObject;

import java.util.Locale;

public class MiraGoldConversionFragment extends Fragment {

    private TextInputEditText etCustomerName, etInWeight, etInPrice, etOutWeight, etOutPrice, etMakingCharge;
    private TextView tvInTotal, tvOutTotal, tvSettlementStatus, tvSettlementAmount;
    private MaterialButton btnSaveConversion;
    
    private double inTotalValue = 0, outTotalValue = 0, settlementAmount = 0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_gold_conversion, container, false);
        
        initViews(v);
        setupListeners();
        
        return v;
    }

    private void initViews(View v) {
        etCustomerName = v.findViewById(R.id.etCustomerName);
        etInWeight = v.findViewById(R.id.etInWeight);
        etInPrice = v.findViewById(R.id.etInPrice);
        etOutWeight = v.findViewById(R.id.etOutWeight);
        etOutPrice = v.findViewById(R.id.etOutPrice);
        etMakingCharge = v.findViewById(R.id.etMakingCharge);
        
        tvInTotal = v.findViewById(R.id.tvInTotal);
        tvOutTotal = v.findViewById(R.id.tvOutTotal);
        tvSettlementStatus = v.findViewById(R.id.tvSettlementStatus);
        tvSettlementAmount = v.findViewById(R.id.tvSettlementAmount);
        btnSaveConversion = v.findViewById(R.id.btnSaveConversion);
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
        etInPrice.addTextChangedListener(watcher);
        etOutWeight.addTextChangedListener(watcher);
        etOutPrice.addTextChangedListener(watcher);
        etMakingCharge.addTextChangedListener(watcher);
        
        btnSaveConversion.setOnClickListener(v -> saveConversion());
    }

    // ✅ دالة الحساب اللحظي
    private void calculateEverything() {
        double inW = getDouble(etInWeight);
        double inP = getDouble(etInPrice);
        double outW = getDouble(etOutWeight);
        double outP = getDouble(etOutPrice);
        double making = getDouble(etMakingCharge);

        inTotalValue = inW * inP;
        outTotalValue = outW * outP;

        // المعادلة: (قيمة الذهب الداخل) - (قيمة الذهب الخارج + المصنعية)
        settlementAmount = inTotalValue - (outTotalValue + making);

        tvInTotal.setText(String.format(Locale.US, "إجمالي الداخل: %,.2f", inTotalValue));
        tvOutTotal.setText(String.format(Locale.US, "إجمالي الخارج: %,.2f", outTotalValue));

        if (settlementAmount > 0.01) {
            // العميل له فلوس عندنا
            tvSettlementStatus.setText("المطلوب دفعه للعميل (نحن مدينون له):");
            tvSettlementAmount.setText(String.format(Locale.US, "%,.2f", settlementAmount));
            tvSettlementAmount.setTextColor(0xFFDC2626); // أحمر
        } else if (settlementAmount < -0.01) {
            // العميل مدين لنا
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
        btnSaveConversion.setText("جاري الحفظ...");

        JSONObject payload = new JSONObject();
        try {
            payload.put("customer_name", customer);
            payload.put("in_weight", getDouble(etInWeight));
            payload.put("in_karat", 21); // يمكنك إضافة حقل للعيار لاحقاً
            payload.put("in_value", inTotalValue);
            
            payload.put("out_weight", getDouble(etOutWeight));
            payload.put("out_karat", 21);
            payload.put("out_value", outTotalValue);
            payload.put("making_charge", getDouble(etMakingCharge));
            
            // إذا كان الفرق سالباً، نرسله كقيمة موجبة (لأن السيرفر يتعامل مع settlement كقيمة مطلقة مع اتجاه)
            // في الكود السابق بالسيرفر، الموجب يعني "العميل يدفع". 
            // هنا settlementAmount السالب يعني "العميل يدفع". لذا نعكسه.
            payload.put("settlement_amount", -settlementAmount); 
            payload.put("payment_method", "cash"); 
        } catch (Exception ignored) {}

        new Thread(() -> {
            MiraApiClient client = MiraApiClient.getInstance(requireContext());
            MiraApiClient.ApiResponse response = client.processGoldConversion(payload);

            requireActivity().runOnUiThread(() -> {
                btnSaveConversion.setEnabled(true);
                btnSaveConversion.setText("💾 حفظ أمر الصياغة");

                if (response != null && response.isSuccess) {
                    Toast.makeText(requireContext(), "✅ تم حفظ أمر الصياغة بنجاح", Toast.LENGTH_LONG).show();
                    // مسح الحقول
                    etInWeight.setText(""); etInPrice.setText("");
                    etOutWeight.setText(""); etOutPrice.setText("");
                    etMakingCharge.setText(""); etCustomerName.setText("");
                } else {
                    String err = (response != null && response.error != null) ? response.error : "فشل الاتصال";
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
