package com.example.uhf.dialog;

import android.app.Dialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.example.uhf.R;

import java.util.Locale;

public class POSCheckoutDialog extends DialogFragment {

    public interface CheckoutListener {
        void onCheckout(POSCheckoutData data);
    }

    private final CheckoutListener listener;
    private final double totalAmount;
    private final int itemCount;

    public POSCheckoutDialog(double totalAmount, int itemCount, CheckoutListener listener) {
        this.totalAmount = totalAmount;
        this.itemCount = itemCount;
        this.listener = listener;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity());
        LayoutInflater inflater = requireActivity().getLayoutInflater();
        View view = inflater.inflate(R.layout.dialog_pos_checkout, null);

        // عناصر النموذج
        EditText etCustomerName = view.findViewById(R.id.etCustomerName);
        EditText etCustomerPhone = view.findViewById(R.id.etCustomerPhone);
        Spinner spPaymentMethod = view.findViewById(R.id.spPaymentMethod);
        EditText etDiscount = view.findViewById(R.id.etDiscount);
        TextView tvFinalTotal = view.findViewById(R.id.tvFinalTotal);

        // تهيئة Spinner
        String[] paymentMethods = {"💵 نقدي", "💳 بطاقة ائتمان", "🏦 تحويل بنكي", "📱 محفظة رقمية"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, paymentMethods);
        spPaymentMethod.setAdapter(adapter);

        // عرض المبلغ النهائي
        tvFinalTotal.setText(String.format(Locale.US, "$%,.2f", totalAmount));

        // حساب الخصم مباشرة
        etDiscount.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                try {
                    double discount = s.length() > 0 ? Double.parseDouble(s.toString()) : 0;
                    double finalTotal = totalAmount * (1 - discount / 100);
                    tvFinalTotal.setText(String.format(Locale.US, "$%,.2f", finalTotal));
                } catch (NumberFormatException e) {
                    tvFinalTotal.setText(String.format(Locale.US, "$%,.2f", totalAmount));
                }
            }
        });

        builder.setView(view)
                .setTitle("📋 تفاصيل البيع - " + itemCount + " قطعة")
                .setPositiveButton("✅ تأكيد البيع", (dialog, which) -> {
                    POSCheckoutData data = new POSCheckoutData();
                    data.customerName = etCustomerName.getText().toString().trim();
                    data.customerPhone = etCustomerPhone.getText().toString().trim();
                    data.paymentMethod = spPaymentMethod.getSelectedItem().toString();
                    
                    String discountStr = etDiscount.getText().toString().trim();
                    data.discount = discountStr.isEmpty() ? 0 : Double.parseDouble(discountStr);
                    data.finalAmount = totalAmount * (1 - data.discount / 100);

                    if (listener != null) {
                        listener.onCheckout(data);
                    }
                })
                .setNegativeButton("❌ إلغاء", null);

        return builder.create();
    }

    public static class POSCheckoutData {
        public String customerName = "";
        public String customerPhone = "";
        public String customerEmail = "";
        public String paymentMethod = "💵 نقدي";
        public String notes = "";
        public double discount = 0;
        public double finalAmount = 0;
    }
}
