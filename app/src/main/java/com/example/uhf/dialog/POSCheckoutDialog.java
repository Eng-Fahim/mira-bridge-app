package com.example.uhf.dialog;

import android.app.Dialog;
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

    public interface OnCheckoutListener {
        void onCheckout(POSCheckoutData data);
    }

    public static class POSCheckoutData {
        public String customerName = "";
        public String customerPhone = "";
        public String paymentMethod = "CASH";
        public double discount = 0.0;
        public double finalAmount = 0.0;
    }

    private static final String ARG_TOTAL_AMOUNT = "total_amount";
    private static final String ARG_ITEM_COUNT = "item_count";

    private double totalAmount = 0.0;
    private int itemCount = 0;
    private OnCheckoutListener listener;

    // UI Elements - مطابقة لملف dialog_pos_checkout.xml
    private TextView tvItemCount, tvFinalTotal;
    private EditText etCustomerName, etCustomerPhone, etDiscount;
    private Spinner spPaymentMethod;
    private Button btnConfirmCheckout, btnCancelCheckout;

    public static POSCheckoutDialog newInstance(double totalAmount, int itemCount) {
        POSCheckoutDialog dialog = new POSCheckoutDialog();
        Bundle args = new Bundle();
        args.putDouble(ARG_TOTAL_AMOUNT, totalAmount);
        args.putInt(ARG_ITEM_COUNT, itemCount);
        dialog.setArguments(args);
        return dialog;
    }

    public void setCheckoutListener(OnCheckoutListener listener) {
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
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        if (dialog.getWindow() != null) {
            dialog.getWindow().requestFeature(Window.FEATURE_NO_TITLE);
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_pos_checkout, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        bindViews(view);
        setupSpinner();
        setupData();
        setupListeners();
    }

    private void bindViews(View view) {
        tvItemCount = view.findViewById(R.id.tvItemCount);
        tvFinalTotal = view.findViewById(R.id.tvFinalTotal);
        etCustomerName = view.findViewById(R.id.etCustomerName);
        etCustomerPhone = view.findViewById(R.id.etCustomerPhone);
        spPaymentMethod = view.findViewById(R.id.spPaymentMethod);
        etDiscount = view.findViewById(R.id.etDiscount);
        btnCancelCheckout = view.findViewById(R.id.btnCancelCheckout);
        btnConfirmCheckout = view.findViewById(R.id.btnConfirmCheckout);
    }

    private void setupSpinner() {
        if (spPaymentMethod != null && getContext() != null) {
            String[] methods = new String[]{"CASH", "CARD", "TRANSFER", "CREDIT"};
            ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(),
                    android.R.layout.simple_spinner_item, methods);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spPaymentMethod.setAdapter(adapter);
        }
    }

    private void setupData() {
        if (tvItemCount != null) {
            tvItemCount.setText(String.format(Locale.getDefault(), "%d قطعة", itemCount));
        }
        updateFinalAmount();
    }

    private void setupListeners() {
        if (etDiscount != null) {
            etDiscount.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    updateFinalAmount();
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }

        if (btnCancelCheckout != null) {
            btnCancelCheckout.setOnClickListener(v -> dismissAllowingStateLoss());
        }

        if (btnConfirmCheckout != null) {
            btnConfirmCheckout.setOnClickListener(v -> {
                POSCheckoutData data = new POSCheckoutData();
                if (etCustomerName != null) data.customerName = etCustomerName.getText().toString().trim();
                if (etCustomerPhone != null) data.customerPhone = etCustomerPhone.getText().toString().trim();

                if (spPaymentMethod != null && spPaymentMethod.getSelectedItem() != null) {
                    data.paymentMethod = spPaymentMethod.getSelectedItem().toString();
                }

                String discountStr = (etDiscount != null) ? etDiscount.getText().toString().trim() : "0";
                try {
                    data.discount = discountStr.isEmpty() ? 0 : Double.parseDouble(discountStr);
                } catch (Exception e) {
                    data.discount = 0;
                }

                data.finalAmount = totalAmount * (1.0 - (data.discount / 100.0));

                dismissAllowingStateLoss();

                if (listener != null) {
                    listener.onCheckout(data);
                }
            });
        }
    }

    private void updateFinalAmount() {
        double discountPercent = 0.0;
        if (etDiscount != null) {
            String val = etDiscount.getText().toString().trim();
            if (!val.isEmpty()) {
                try {
                    discountPercent = Double.parseDouble(val);
                } catch (Exception ignored) {}
            }
        }
        double finalAmount = totalAmount * (1.0 - (discountPercent / 100.0));
        if (tvFinalTotal != null) {
            tvFinalTotal.setText(String.format(Locale.US, "$%,.2f", finalAmount));
        }
    }
}
