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
import android.view.WindowManager;
import android.widget.AdapterView;
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
import com.example.uhf.manager.MiraPriceManager;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Locale;

public class POSCheckoutDialog extends DialogFragment {

    public interface OnCheckoutListener {
        void onCheckout(POSCheckoutData data);
    }

    public static class POSCheckoutData {
        public String customerName = "";
        public String customerPhone = "";
        public String customerEmail = "";
        public String customerAddress = "";
        public String customerNotes = "";
        public String paymentMethod = "CASH";
        public String accountCode = "110101";
        public double discount = 0.0;
        public double discountAmount = 0.0;
        public double finalAmount = 0.0;
    }

    private static final String ARG_TOTAL_AMOUNT = "total_amount";
    private static final String ARG_ITEM_COUNT = "item_count";

    private double totalAmount = 0.0;
    private int itemCount = 0;
    private OnCheckoutListener listener;
    private boolean isArabic;
    private MiraOrgProfileManager orgProfile;
    private MiraPriceManager priceManager; // ✅ إضافة مدير الأسعار

    private TextView tvDialogTitle, tvItemCount, tvSubtotal, tvDiscountAmount, tvFinalTotal, tvAccountNumber;
    private EditText etCustomerName, etCustomerPhone, etCustomerEmail, etCustomerAddress, etCustomerNotes, etDiscount;
    private TextInputLayout tilCustomerName, tilCustomerPhone;
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
            priceManager = MiraPriceManager.getInstance(getContext()); // ✅ تهيئة مدير الأسعار
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

    @Override
    public void onStart() {
        super.onStart();
        // ضبط عرض الـ Dialog ليكون 90% من عرض الشاشة لمنع الضغط الأفقي
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.92),
                    WindowManager.LayoutParams.WRAP_CONTENT
            );
        }
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
        tvAccountNumber = view.findViewById(R.id.tvAccountNumber);

        tilCustomerName = view.findViewById(R.id.tilCustomerName);
        tilCustomerPhone = view.findViewById(R.id.tilCustomerPhone);

        etCustomerName = view.findViewById(R.id.etCustomerName);
        etCustomerPhone = view.findViewById(R.id.etCustomerPhone);
        etCustomerEmail = view.findViewById(R.id.etCustomerEmail);
        etCustomerAddress = view.findViewById(R.id.etCustomerAddress);
        etCustomerNotes = view.findViewById(R.id.etCustomerNotes);
        etDiscount = view.findViewById(R.id.etDiscount);

        spPaymentMethod = view.findViewById(R.id.spPaymentMethod);
        btnCancelCheckout = view.findViewById(R.id.btnCancelCheckout);
        btnConfirmCheckout = view.findViewById(R.id.btnConfirmCheckout);
    }

    private void applyLanguage() {
        if (tvDialogTitle != null) {
            tvDialogTitle.setText(isArabic ? "إتمام عملية البيع" : "Complete Sale");
        }
        if (tilCustomerName != null) {
            tilCustomerName.setHint(isArabic ? "اسم العميل *" : "Customer Name *");
        }
        if (tilCustomerPhone != null) {
            tilCustomerPhone.setHint(isArabic ? "رقم الهاتف *" : "Phone Number *");
        }
        if (etCustomerEmail != null) {
            etCustomerEmail.setHint(isArabic ? "البريد الإلكتروني (اختياري)" : "Email (Optional)");
        }
        if (etCustomerAddress != null) {
            etCustomerAddress.setHint(isArabic ? "العنوان (اختياري)" : "Address (Optional)");
        }
        if (etCustomerNotes != null) {
            etCustomerNotes.setHint(isArabic ? "ملاحظات (اختياري)" : "Notes (Optional)");
        }
        if (etDiscount != null) {
            etDiscount.setHint(isArabic ? "نسبة الخصم" : "Discount");
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

            spPaymentMethod.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    updateAccountNumber(position);
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });

            updateAccountNumber(0);
        }
    }

    private void updateAccountNumber(int position) {
        if (tvAccountNumber == null) return;

        String[] accountNumbers = isArabic
                ? new String[]{
                "🏦 حساب النقدية: 110101",
                "💳 حساب البطاقات: 110201",
                "🏦 حساب التحويلات: 110202",
                "⏳ حساب الآجل: 110301"
        }
                : new String[]{
                "🏦 Cash Account: 110101",
                "💳 Card Account: 110201",
                "🏦 Transfer Account: 110202",
                "⏳ Credit Account: 110301"
        };

        if (position >= 0 && position < accountNumbers.length) {
            tvAccountNumber.setText(accountNumbers[position]);
            tvAccountNumber.setVisibility(View.VISIBLE);
        } else {
            tvAccountNumber.setVisibility(View.GONE);
        }
    }

    private String getCurrentAccountCode() {
        if (spPaymentMethod == null || spPaymentMethod.getSelectedItemPosition() < 0) {
            return "110101";
        }

        int position = spPaymentMethod.getSelectedItemPosition();
        switch (position) {
            case 0: return "110101";
            case 1: return "110201";
            case 2: return "110202";
            case 3: return "110301";
            default: return "110101";
        }
    }

    private String getEnglishPaymentMethod(int position) {
        switch (position) {
            case 0: return "CASH";
            case 1: return "CARD";
            case 2: return "BANK_TRANSFER";
            case 3: return "CREDIT";
            default: return "CASH";
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

    private boolean validateInputs() {
        boolean isValid = true;

        String name = etCustomerName.getText().toString().trim();
        if (name.isEmpty()) {
            tilCustomerName.setError(isArabic ? "اسم العميل مطلوب" : "Customer name is required");
            isValid = false;
        } else {
            tilCustomerName.setError(null);
        }

        String phone = etCustomerPhone.getText().toString().trim();
        if (phone.isEmpty()) {
            tilCustomerPhone.setError(isArabic ? "رقم الهاتف مطلوب" : "Phone number is required");
            isValid = false;
        } else {
            tilCustomerPhone.setError(null);
        }

        return isValid;
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
                if (!validateInputs()) {
                    return;
                }

                POSCheckoutData data = new POSCheckoutData();

                if (etCustomerName != null) data.customerName = etCustomerName.getText().toString().trim();
                if (etCustomerPhone != null) data.customerPhone = etCustomerPhone.getText().toString().trim();
                if (etCustomerEmail != null) data.customerEmail = etCustomerEmail.getText().toString().trim();
                if (etCustomerAddress != null) data.customerAddress = etCustomerAddress.getText().toString().trim();
                if (etCustomerNotes != null) data.customerNotes = etCustomerNotes.getText().toString().trim();

                int paymentPosition = spPaymentMethod != null ? spPaymentMethod.getSelectedItemPosition() : 0;
                data.paymentMethod = getEnglishPaymentMethod(paymentPosition);
                data.accountCode = getCurrentAccountCode();

                String discountStr = (etDiscount != null) ? etDiscount.getText().toString().trim() : "0";
                try {
                    data.discount = discountStr.isEmpty() ? 0 : Double.parseDouble(discountStr);
                } catch (Exception e) {
                    data.discount = 0;
                }

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
     * ✅ تحديث عرض الأسعار بالعملة الصحيحة
     */
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

        // ✅ استخدام MiraPriceManager لتنسيق الأسعار بالعملة الصحيحة
        if (tvSubtotal != null) {
            tvSubtotal.setText(priceManager.formatPriceDefault(totalAmount));
        }
        if (tvDiscountAmount != null) {
            tvDiscountAmount.setText(priceManager.formatPriceDefault(discountAmount));
        }
        if (tvFinalTotal != null) {
            tvFinalTotal.setText(priceManager.formatPriceDefault(finalAmount));
        }
    }
}
