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

    private static final String ARG_TOTAL_AMOUNT = "total_amount";
    private static final String ARG_ITEM_COUNT = "item_count";

    private CheckoutListener listener;
    private double totalAmount;
    private int itemCount;

    // ⚡ 1. الكونستركتور الفارغ إجباري لمنع انهيار أندرويد
    public POSCheckoutDialog() {
        // Required empty public constructor
    }

    // ⚡ 2. النمط القياسي لإنشاء الـ Dialog مع Arguments
    public static POSCheckoutDialog newInstance(double totalAmount, int itemCount) {
        POSCheckoutDialog dialog = new POSCheckoutDialog();
        Bundle args = new Bundle();
        args.putDouble(ARG_TOTAL_AMOUNT, totalAmount);
        args.putInt(ARG_ITEM_COUNT, itemCount);
        dialog.setArguments(args);
        return dialog;
    }

    public void setCheckoutListener(CheckoutListener listener) {
        this.listener = listener;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            totalAmount = getArguments().getDouble(ARG_TOTAL_AMOUNT, 0.0);
            itemCount = getArguments().getInt(ARG_ITEM_COUNT, 0);
        }
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

        // عرض المبلغ النهائي Initial
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
                    data.paymentMethod = spPaymentMethod.getSelectedItem() != null ? 
                            spPaymentMethod.getSelectedItem().toString() : "💵 نقدي";
                    
                    String discountStr = etDiscount.getText().toString().trim();
                    try {
                        data.discount = discountStr.isEmpty() ? 0 : Double.parseDouble(discountStr);
                    } catch (Exception e) {
                        data.discount = 0;
                    }
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
