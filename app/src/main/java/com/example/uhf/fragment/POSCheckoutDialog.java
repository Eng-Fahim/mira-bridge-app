package com.example.uhf.dialog;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.*;
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
    
    private CheckoutListener listener;
    private double totalAmount;
    private int itemCount;
    
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
        EditText etCustomerEmail = view.findViewById(R.id.etCustomerEmail);
        Spinner spPaymentMethod = view.findViewById(R.id.spPaymentMethod);
        EditText etDiscount = view.findViewById(R.id.etDiscount);
        EditText etNotes = view.findViewById(R.id.etNotes);
        TextView tvFinalTotal = view.findViewById(R.id.tvFinalTotal);
        
        tvFinalTotal.setText(String.format(Locale.US, "$%,.2f", totalAmount));
        
        // حساب الخصم
        etDiscount.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                try {
                    double discount = Double.parseDouble(s.toString());
                    double finalTotal = totalAmount * (1 - discount / 100);
                    tvFinalTotal.setText(String.format(Locale.US, "$%,.2f", finalTotal));
                } catch (NumberFormatException e) {
                    tvFinalTotal.setText(String.format(Locale.US, "$%,.2f", totalAmount));
                }
            }
        });
        
        builder.setView(view)
            .setTitle("📋 تفاصيل عملية البيع - " + itemCount + " قطعة")
            .setPositiveButton("✅ تأكيد البيع", (dialog, which) -> {
                POSCheckoutData data = new POSCheckoutData();
                data.customerName = etCustomerName.getText().toString();
                data.customerPhone = etCustomerPhone.getText().toString();
                data.customerEmail = etCustomerEmail.getText().toString();
                data.paymentMethod = spPaymentMethod.getSelectedItem().toString();
                data.notes = etNotes.getText().toString();
                try {
                    data.discount = Double.parseDouble(etDiscount.getText().toString());
                } catch (NumberFormatException e) {
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
        public String customerName;
        public String customerPhone;
        public String customerEmail;
        public String paymentMethod;
        public String notes;
        public double discount;
        public double finalAmount;
    }
}
