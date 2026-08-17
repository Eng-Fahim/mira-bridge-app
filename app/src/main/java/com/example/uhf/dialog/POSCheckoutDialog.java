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
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.DialogFragment;

import com.example.uhf.R;
import com.example.uhf.manager.MiraOrgProfileManager;

import java.util.Locale;

public class POSCheckoutDialog extends DialogFragment {

    public interface OnCheckoutListener {
        void onCheckout(POSCheckoutData data);
    }

    /**
     * ✅ نموذج بيانات البيع الكامل
     */
    public static class POSCheckoutData {
        public String customerName = "";
        public String customerPhone = "";
        public String customerEmail = "";      // ✅ جديد
        public String customerAddress = "";    // ✅ جديد
        public String customerNotes = "";      // ✅ جديد
        public String paymentMethod = "CASH";
        public double discount = 0.0;          // نسبة الخصم
        public double discountAmount = 0.0;    // ✅ قيمة الخصم
        public double finalAmount = 0.0;
    }

    private static final String ARG_TOTAL_AMOUNT = "total_amount";
    private static final String ARG_ITEM_COUNT = "item_count";

    private double totalAmount = 0.0;
    private int itemCount = 0;
    private OnCheckoutListener listener;
    private boolean isArabic;
    private MiraOrgProfileManager orgProfile;

    private TextView tvDialogTitle, tvItemCount, tvSubtotal, tvDiscountAmount, tvFinalTotal;
    private EditText etCustomerName, etCustomerPhone, etCustomerEmail, etCustomerAddress, etCustomerNotes, etDiscount;
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
        isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");
        if (getContext() != null) {
            orgProfile = MiraOrgProfileManager.getInstance(getContext());
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
        applyLanguage();
        setupSpinner();
        setupData();
        setupListeners();
    }

    private void bindViews(View view) {
        tvDialogTitle = view.findViewById(R.id.tvDialogTitle);
        tvItemCount = view.findViewById(R.id.tvItemCount);
        tvSubtotal = view.findViewById(R.id.tvSubtotal);
        tvDiscountAmount = view.findViewById(R.id.tvDiscountAmount);
        tvFinalTotal = view.findViewById(R.id.tvFinalTotal);
        etCustomerName = view.findViewById(R.id.etCustomerName);
        etCustomerPhone = view.findViewById(R.id.etCustomerPhone);
        etCustomerEmail = view.findViewById(R.id.etCustomerEmail);
        etCustomerAddress = view.findViewById(R.id.etCustomerAddress);
        etCustomerNotes = view.findViewById(R.id.etCustomerNotes);
        spPaymentMethod = view.findViewById(R.id.spPaymentMethod);
        etDiscount = view.findViewById(R.id.etDiscount);
        btnCancelCheckout = view.findViewById(R.id.btnCancelCheckout);
        btnConfirmCheckout = view.findViewById(R.id.btnConfirmCheckout);
    }

    private void applyLanguage() {
        if (tvDialogTitle != null) {
            tvDialogTitle.setText(isArabic ? "إتمام عملية البيع" : "Complete Sale");
        }
        if (etCustomerName != null) {
            etCustomerName.setHint(isArabic ? "اسم العميل" : "Customer Name");
        }
        if (etCustomerPhone != null) {
            etCustomerPhone.setHint(isArabic ? "رقم الهاتف" : "Phone Number");
        }
        if (etCustomerEmail != null) {
            etCustomerEmail.setHint(isArabic ? "البريد الإلكتروني (اختياري)" : "Email (optional)");
        }
        if (etCustomerAddress != null) {
            etCustomerAddress.setHint(isArabic ? "العنوان (اختياري)" : "Address (optional)");
        }
        if (etCustomerNotes != null) {
            etCustomerNotes.setHint(isArabic ? "ملاحظات (اختياري)" : "Notes (optional)");
        }
        if (etDiscount != null) {
            etDiscount.setHint(isArabic ? "نسبة الخصم %" : "Discount %");
        }
        if (btnCancelCheckout != null) {
            btnCancelCheckout.setText(isArabic ? "إلغاء" : "Cancel");
        }
        if (btnConfirmCheckout != null) {
            btnConfirmCheckout.setText(isArabic ? "تأكيد البيع ✓" : "Confirm Sale ✓");
        }
    }

    private void setupSpinner() {
        if (spPaymentMethod != null && getContext() != null) {
            String[] methods = isArabic
                ? new String[]{"💵 نقداً", "💳 بطاقة", "🏦 تحويل بنكي", "⏳ آجل"}
                : new String[]{"CASH", "CARD", "BANK_TRANSFER", "CREDIT"};
            ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(),
                    android.R.layout.simple_spinner_item, methods);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spPaymentMethod.setAdapter(adapter);
        }
    }

    private void setupData() {
        if (tvItemCount != null) {
            tvItemCount.setText(isArabic
                ? String.format(Locale.getDefault(), "%d قطعة", itemCount)
                : String.format(Locale.getDefault(), "%d items", itemCount));
        }
        updateFinalAmount();
    }

    private void setupListeners() {
        if (etDiscount != null) {
            etDiscount.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateFinalAmount(); }
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
                if (etCustomerEmail != null) data.customerEmail = etCustomerEmail.getText().toString().trim();
                if (etCustomerAddress != null) data.customerAddress = etCustomerAddress.getText().toString().trim();
                if (etCustomerNotes != null) data.customerNotes = etCustomerNotes.getText().toString().trim();

                // ✅ تحويل طريقة الدفع للقيمة الإنجليزية الموحدة
                if (spPaymentMethod != null && spPaymentMethod.getSelectedItem() != null) {
                    String method = spPaymentMethod.getSelectedItem().toString();
                    data.paymentMethod = getEnglishPaymentMethod(method);
                }

                String discountStr = (etDiscount != null) ? etDiscount.getText().toString().trim() : "0";
                try {
                    data.discount = discountStr.isEmpty() ? 0 : Double.parseDouble(discountStr);
                } catch (Exception e) {
                    data.discount = 0;
                }

                // ✅ حساب قيمة الخصم والمبلغ النهائي
                data.discountAmount = totalAmount * (data.discount / 100.0);
                data.finalAmount = totalAmount - data.discountAmount;

                dismissAllowingStateLoss();

                if (listener != null) {
                    listener.onCheckout(data);
                }
            });
        }
    }

    /**
     * ✅ تحويل طريقة الدفع للقيمة الإنجليزية الموحدة
     */
    private String getEnglishPaymentMethod(String method) {
        if (method == null) return "CASH";
        
        switch (method) {
            case "💵 نقداً":
            case "CASH":
                return "CASH";
            case "💳 بطاقة":
            case "CARD":
                return "CARD";
            case "🏦 تحويل بنكي":
            case "BANK_TRANSFER":
            case "TRANSFER":
                return "BANK_TRANSFER";
            case "⏳ آجل":
            case "CREDIT":
                return "CREDIT";
            default:
                return method;
        }
    }

    private void updateFinalAmount() {
        double discountPercent = 0.0;
        if (etDiscount != null) {
            String val = etDiscount.getText().toString().trim();
            if (!val.isEmpty()) {
                try { discountPercent = Double.parseDouble(val); } catch (Exception ignored) {}
            }
        }
        
        double discountAmount = totalAmount * (discountPercent / 100.0);
        double finalAmount = totalAmount - discountAmount;
        
        if (tvSubtotal != null) {
            tvSubtotal.setText(String.format(Locale.US, "%,.0f YER", totalAmount));
        }
        if (tvDiscountAmount != null) {
            tvDiscountAmount.setText(String.format(Locale.US, "%,.0f YER", discountAmount));
        }
        if (tvFinalTotal != null) {
            tvFinalTotal.setText(String.format(Locale.US, "%,.0f YER", finalAmount));
        }
    }
}
