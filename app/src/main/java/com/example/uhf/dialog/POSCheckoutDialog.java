package com.example.uhf.dialog;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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

    public POSCheckoutDialog() {
        // Required empty public constructor
    }

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

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // جعل خلفية النافذة شفافة ليتناسب مع تصميم MIRA الداكن
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            getDialog().getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        }
        return inflater.inflate(R.layout.dialog_pos_checkout, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // عناصر النموذج
        EditText etCustomerName = view.findViewById(R.id.etCustomerName);
        EditText etCustomerPhone = view.findViewById(R.id.etCustomerPhone);
        Spinner spPaymentMethod = view.findViewById(R.id.spPaymentMethod);
        EditText etDiscount = view.findViewById(R.id.etDiscount);
        TextView tvFinalTotal = view.findViewById(R.id.tvFinalTotal);

        Button btnConfirmCheckout = view.findViewById(R.id.btnConfirmCheckout);
        Button btnCancelCheckout = view.findViewById(R.id.btnCancelCheckout);

        // تهيئة Spinner
        String[] paymentMethods = {"💵 نقدي", "💳 بطاقة ائتمان", "🏦 تحويل بنكي", "📱 محفظة رقمية"};
        if (getContext() != null && spPaymentMethod != null) {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                    android.R.layout.simple_spinner_dropdown_item, paymentMethods);
            spPaymentMethod.setAdapter(adapter);
        }

        // عرض المبلغ النهائي الأولي
        if (tvFinalTotal != null) {
            tvFinalTotal.setText(String.format(Locale.US, "$%,.2f", totalAmount));
        }

        // حساب الخصم التفاعلي
        if (etDiscount != null && tvFinalTotal != null) {
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
        }

        // ⚡ زر الإلغاء
        if (btnCancelCheckout != null) {
            btnCancelCheckout.setOnClickListener(v -> dismiss());
        }

        // ⚡ زر تأكيد البيع
        if (btnConfirmCheckout != null) {
            btnConfirmCheckout.setOnClickListener(v -> {
                POSCheckoutData data = new POSCheckoutData();
                if (etCustomerName != null) data.customerName = etCustomerName.getText().toString().trim();
                if (etCustomerPhone != null) data.customerPhone = etCustomerPhone.getText().toString().trim();
                
                if (spPaymentMethod != null && spPaymentMethod.getSelectedItem() != null) {
                    data.paymentMethod = spPaymentMethod.getSelectedItem().toString();
                } else {
                    data.paymentMethod = "💵 نقدي";
                }

                String discountStr = (etDiscount != null) ? etDiscount.getText().toString().trim() : "0";
                try {
                    data.discount = discountStr.isEmpty() ? 0 : Double.parseDouble(discountStr);
                } catch (Exception e) {
                    data.discount = 0;
                }
                data.finalAmount = totalAmount * (1 - data.discount / 100);

                if (listener != null) {
                    listener.onCheckout(data);
                }
                dismiss();
            });
        }
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
