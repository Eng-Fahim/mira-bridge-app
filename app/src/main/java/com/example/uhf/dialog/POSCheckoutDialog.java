package com.example.uhf.dialog;

import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.Log;
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
import com.example.uhf.utils.DiagTracer;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Locale;

/**
 * POSCheckoutDialog v2.1 — Diagnostic Edition
 * ═══════════════════════════════════════════════════════════════
 * - كل مرحلة موثّقة بـ DiagTracer.mark()
 * - التوستات تظهر على الشاشة لكشف نقطة التعليق
 */
public class POSCheckoutDialog extends DialogFragment {

    public interface OnCheckoutListener {
        void onCheckout(POSCheckoutData data);
    }

    public interface OnDialogDismissListener {
        void onDialogDismissed();
    }

    public static class POSCheckoutData {
        public String customerName = "";
        public String customerPhone = "";
        public String customerEmail = "";
        public String customerAddress = "";
        public String customerNotes = "";
        public String paymentMethod = "CASH";
        public String accountCode = "110101";
        public String currency = "USD";
        public double originalTotal = 0.0;
        public double discount = 0.0;
        public double discountAmount = 0.0;
        public double finalAmount = 0.0;
    }

    private static final String ARG_TOTAL_AMOUNT = "total_amount";
    private static final String ARG_ITEM_COUNT = "item_count";
    private static final String DLOG = "POSCheckout";

    private double totalAmount = 0.0;
    private int itemCount = 0;
    private OnCheckoutListener listener;
    private OnDialogDismissListener dismissListener;
    private boolean isArabic;
    private MiraOrgProfileManager orgProfile;
    private MiraPriceManager priceManager;

    private TextView tvDialogTitle, tvItemCount, tvSubtotal, tvDiscountAmount, tvFinalTotal, tvAccountNumber;
    private EditText etCustomerName, etCustomerPhone, etCustomerEmail, etCustomerAddress, etCustomerNotes, etDiscount;
    private TextInputLayout tilCustomerName, tilCustomerPhone;
    private Spinner spPaymentMethod;
    private Button btnConfirmCheckout, btnCancelCheckout;

    // ═══════════════════════════════════════════════════════════
    // newInstance
    // ═══════════════════════════════════════════════════════════
    public static POSCheckoutDialog newInstance(double totalAmount, int itemCount) {
        DiagTracer.mark("D.newInstance START",
                "total=" + totalAmount + ", count=" + itemCount);

        POSCheckoutDialog dialog = new POSCheckoutDialog();
        Bundle args = new Bundle();
        args.putDouble(ARG_TOTAL_AMOUNT, totalAmount);
        args.putInt(ARG_ITEM_COUNT, itemCount);
        dialog.setArguments(args);

        DiagTracer.mark("D.newInstance END");
        return dialog;
    }

    public void setCheckoutListener(OnCheckoutListener listener) {
        this.listener = listener;
    }

    public void setOnDismissListener(OnDialogDismissListener listener) {
        this.dismissListener = listener;
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        DiagTracer.mark("D.onDismiss START");
        super.onDismiss(dialog);
        if (dismissListener != null) {
            try {
                dismissListener.onDialogDismissed();
                DiagTracer.mark("D.onDismiss listener called");
            } catch (Exception e) {
                DiagTracer.markException("onDismiss listener", e);
            }
        }
        DiagTracer.mark("D.onDismiss END");
    }

    // ═══════════════════════════════════════════════════════════
    // onCreate
    // ═══════════════════════════════════════════════════════════
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        DiagTracer.mark("D.onCreate START");
        super.onCreate(savedInstanceState);
        DiagTracer.mark("D.onCreate super done");

        if (getArguments() != null) {
            totalAmount = getArguments().getDouble(ARG_TOTAL_AMOUNT, 0.0);
            itemCount = getArguments().getInt(ARG_ITEM_COUNT, 0);
            DiagTracer.mark("D.onCreate args",
                    "total=" + totalAmount + ", count=" + itemCount);
        } else {
            DiagTracer.mark("D.onCreate NO ARGS!");
        }

        try {
            isArabic = AppCompatDelegate.getApplicationLocales()
                    .toLanguageTags().contains("ar");
            DiagTracer.mark("D.onCreate isArabic=" + isArabic);
        } catch (Exception e) {
            DiagTracer.markException("isArabic", e);
            isArabic = false;
        }

        if (getContext() != null) {
            DiagTracer.mark("D.onCreate ctx ok — before orgProfile");
            try {
                orgProfile = MiraOrgProfileManager.getInstance(getContext());
                DiagTracer.mark("D.onCreate after orgProfile=" + (orgProfile != null));
            } catch (Exception e) {
                DiagTracer.markException("orgProfile", e);
            }

            try {
                priceManager = MiraPriceManager.getInstance(getContext());
                DiagTracer.mark("D.onCreate after priceManager=" + (priceManager != null));
            } catch (Exception e) {
                DiagTracer.markException("priceManager", e);
            }
        } else {
            DiagTracer.mark("D.onCreate ctx=null");
        }

        DiagTracer.mark("D.onCreate END");
    }

    // ═══════════════════════════════════════════════════════════
    // onCreateDialog
    // ═══════════════════════════════════════════════════════════
    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        DiagTracer.mark("D.onCreateDialog START");
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        if (dialog.getWindow() != null) {
            dialog.getWindow().requestFeature(Window.FEATURE_NO_TITLE);
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DiagTracer.mark("D.onCreateDialog window configured");
        }
        DiagTracer.mark("D.onCreateDialog END");
        return dialog;
    }

    // ═══════════════════════════════════════════════════════════
    // onStart
    // ═══════════════════════════════════════════════════════════
    @Override
    public void onStart() {
        DiagTracer.mark("D.onStart START");
        super.onStart();
        try {
            if (getDialog() != null && getDialog().getWindow() != null) {
                getDialog().getWindow().setLayout(
                        (int) (getResources().getDisplayMetrics().widthPixels * 0.92),
                        WindowManager.LayoutParams.WRAP_CONTENT
                );
                getDialog().getWindow().setDimAmount(0.5f);
                DiagTracer.mark("D.onStart window set WRAP_CONTENT");
            } else {
                DiagTracer.mark("D.onStart window=null");
            }
        } catch (Exception e) {
            DiagTracer.markException("onStart window", e);
        }
        DiagTracer.mark("D.onStart END");
    }

    // ═══════════════════════════════════════════════════════════
    // onCreateView
    // ═══════════════════════════════════════════════════════════
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        DiagTracer.mark("D.onCreateView START");
        View v = null;
        try {
            v = inflater.inflate(R.layout.dialog_pos_checkout, container, false);
            DiagTracer.mark("D.onCreateView inflated=" + (v != null));
        } catch (Exception e) {
            DiagTracer.markException("onCreateView inflate", e);
        }
        return v;
    }

    // ═══════════════════════════════════════════════════════════
    // onViewCreated
    // ═══════════════════════════════════════════════════════════
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        DiagTracer.mark("D.onViewCreated START");
        super.onViewCreated(view, savedInstanceState);

        try {
            DiagTracer.mark("D.bindViews before");
            bindViews(view);
            DiagTracer.mark("D.bindViews after",
                    "tvItemCount=" + (tvItemCount != null)
                            + ", tvSubtotal=" + (tvSubtotal != null)
                            + ", tvFinalTotal=" + (tvFinalTotal != null)
                            + ", btnConfirm=" + (btnConfirmCheckout != null));

            DiagTracer.mark("D.applyLanguage before");
            applyLanguage();
            DiagTracer.mark("D.applyLanguage after");

            DiagTracer.mark("D.setupSpinner before");
            setupSpinner();
            DiagTracer.mark("D.setupSpinner after");

            DiagTracer.mark("D.setupData before");
            setupData();
            DiagTracer.mark("D.setupData after");

            DiagTracer.mark("D.setupListeners before");
            setupListeners();
            DiagTracer.mark("D.setupListeners after");

        } catch (Exception e) {
            DiagTracer.markException("onViewCreated", e);
        }

        DiagTracer.mark("D.onViewCreated END");
    }

    // ═══════════════════════════════════════════════════════════
    // bindViews
    // ═══════════════════════════════════════════════════════════
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

    // ═══════════════════════════════════════════════════════════
    // applyLanguage
    // ═══════════════════════════════════════════════════════════
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

    // ═══════════════════════════════════════════════════════════
    // setupSpinner
    // ═══════════════════════════════════════════════════════════
    private void setupSpinner() {
        DiagTracer.mark("D.setupSpinner inside START");

        if (spPaymentMethod == null) {
            DiagTracer.mark("D.setupSpinner spPaymentMethod=null → ABORT");
            return;
        }
        if (getContext() == null) {
            DiagTracer.mark("D.setupSpinner ctx=null → ABORT");
            return;
        }

        String[] methods = isArabic
                ? new String[]{"💵 نقداً", "💳 بطاقة", "🏦 تحويل بنكي", "⏳ آجل"}
                : new String[]{"CASH", "CARD", "BANK_TRANSFER", "CREDIT"};

        DiagTracer.mark("D.setupSpinner building adapter");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(),
                android.R.layout.simple_spinner_item, methods);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        DiagTracer.mark("D.setupSpinner setAdapter");
        spPaymentMethod.setAdapter(adapter);
        DiagTracer.mark("D.setupSpinner adapter set");

        spPaymentMethod.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                DiagTracer.mark("D.spinner onItemSelected pos=" + position);
                updateAccountNumber(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                DiagTracer.mark("D.spinner onNothingSelected");
            }
        });

        DiagTracer.mark("D.setupSpinner updateAccountNumber(0)");
        updateAccountNumber(0);

        DiagTracer.mark("D.setupSpinner inside END");
    }

    // ═══════════════════════════════════════════════════════════
    // updateAccountNumber
    // ═══════════════════════════════════════════════════════════
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

    // ═══════════════════════════════════════════════════════════
    // getCurrentAccountCode
    // ═══════════════════════════════════════════════════════════
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

    // ═══════════════════════════════════════════════════════════
    // getEnglishPaymentMethod
    // ═══════════════════════════════════════════════════════════
    private String getEnglishPaymentMethod(int position) {
        switch (position) {
            case 0: return "CASH";
            case 1: return "CARD";
            case 2: return "BANK_TRANSFER";
            case 3: return "CREDIT";
            default: return "CASH";
        }
    }

    // ═══════════════════════════════════════════════════════════
    // setupData
    // ═══════════════════════════════════════════════════════════
    private void setupData() {
        DiagTracer.mark("D.setupData inside START",
                "itemCount=" + itemCount + ", totalAmount=" + totalAmount);

        if (tvItemCount != null) {
            String text = isArabic
                    ? String.format(Locale.getDefault(), "%d قطعة", itemCount)
                    : String.format(Locale.getDefault(), "%d items", itemCount);
            tvItemCount.setText(text);
            DiagTracer.mark("D.setupData tvItemCount set", text);
        } else {
            DiagTracer.mark("D.setupData tvItemCount=null");
        }

        DiagTracer.mark("D.setupData calling updateFinalAmount");
        updateFinalAmount();
        DiagTracer.mark("D.setupData inside END");
    }

    // ═══════════════════════════════════════════════════════════
    // validateInputs
    // ═══════════════════════════════════════════════════════════
    private boolean validateInputs() {
        boolean isValid = true;

        if (etCustomerName != null) {
            String name = etCustomerName.getText().toString().trim();
            if (name.isEmpty()) {
                if (tilCustomerName != null) {
                    tilCustomerName.setError(isArabic
                            ? "اسم العميل مطلوب"
                            : "Customer name is required");
                }
                isValid = false;
            } else {
                if (tilCustomerName != null) tilCustomerName.setError(null);
            }
        }

        if (etCustomerPhone != null) {
            String phone = etCustomerPhone.getText().toString().trim();
            if (phone.isEmpty()) {
                if (tilCustomerPhone != null) {
                    tilCustomerPhone.setError(isArabic
                            ? "رقم الهاتف مطلوب"
                            : "Phone number is required");
                }
                isValid = false;
            } else {
                if (tilCustomerPhone != null) tilCustomerPhone.setError(null);
            }
        }

        return isValid;
    }

    // ═══════════════════════════════════════════════════════════
    // setupListeners
    // ═══════════════════════════════════════════════════════════
    private void setupListeners() {
        if (etDiscount != null) {
            etDiscount.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    // ⚠️ لا نُضيف DiagTracer هنا (يُستدعى كثيراً)
                    updateFinalAmount();
                }
                @Override
                public void afterTextChanged(Editable s) {}
            });
        }

        if (btnCancelCheckout != null) {
            btnCancelCheckout.setOnClickListener(v -> {
                DiagTracer.mark("D.btnCancel clicked");
                dismissAllowingStateLoss();
            });
        }

        if (btnConfirmCheckout != null) {
            btnConfirmCheckout.setOnClickListener(v -> {
                DiagTracer.mark("D.btnConfirm clicked");

                if (!validateInputs()) {
                    DiagTracer.mark("D.btnConfirm validation failed");
                    return;
                }

                POSCheckoutData data = new POSCheckoutData();

                if (etCustomerName != null)
                    data.customerName = etCustomerName.getText().toString().trim();
                if (etCustomerPhone != null)
                    data.customerPhone = etCustomerPhone.getText().toString().trim();
                if (etCustomerEmail != null)
                    data.customerEmail = etCustomerEmail.getText().toString().trim();
                if (etCustomerAddress != null)
                    data.customerAddress = etCustomerAddress.getText().toString().trim();
                if (etCustomerNotes != null)
                    data.customerNotes = etCustomerNotes.getText().toString().trim();

                int paymentPosition = spPaymentMethod != null
                        ? spPaymentMethod.getSelectedItemPosition() : 0;
                data.paymentMethod = getEnglishPaymentMethod(paymentPosition);
                data.accountCode = getCurrentAccountCode();

                if (priceManager != null) {
                    data.currency = priceManager.getDefaultCurrency();
                }

                String discountStr = (etDiscount != null)
                        ? etDiscount.getText().toString().trim() : "0";
                try {
                    data.discount = discountStr.isEmpty() ? 0 : Double.parseDouble(discountStr);
                } catch (Exception e) {
                    data.discount = 0;
                }

                data.originalTotal = totalAmount;
                data.discountAmount = totalAmount * (data.discount / 100.0);
                data.finalAmount = totalAmount - data.discountAmount;

                DiagTracer.mark("D.btnConfirm dismiss",
                        "final=" + data.finalAmount);

                dismissAllowingStateLoss();

                if (listener != null) {
                    DiagTracer.mark("D.btnConfirm calling listener");
                    listener.onCheckout(data);
                } else {
                    DiagTracer.mark("D.btnConfirm listener=null!");
                }
            });
        }
    }

    // ═══════════════════════════════════════════════════════════
    // updateFinalAmount
    // ═══════════════════════════════════════════════════════════
    private void updateFinalAmount() {
        DiagTracer.mark("D.updateFinalAmount START");

        if (priceManager == null) {
            DiagTracer.mark("D.updateFinalAmount priceManager=null → ABORT");
            return;
        }

        double discountPercent = 0.0;
        if (etDiscount != null) {
            String val = etDiscount.getText().toString().trim();
            if (!val.isEmpty()) {
                try {
                    discountPercent = Double.parseDouble(val);
                } catch (Exception ignored) {}
            }
        }

        double discountAmount = totalAmount * (discountPercent / 100.0);
        double finalAmount = totalAmount - discountAmount;

        DiagTracer.mark("D.updateFinalAmount calc",
                "total=" + totalAmount + ", disc=" + discountAmount 
                        + ", final=" + finalAmount);

        try {
            if (tvSubtotal != null) {
                DiagTracer.mark("D.updateFinalAmount before formatPriceDefault(subtotal)");
                String s = priceManager.formatPriceDefault(totalAmount);
                tvSubtotal.setText(s);
                DiagTracer.mark("D.updateFinalAmount tvSubtotal set", s);
            }
            if (tvDiscountAmount != null) {
                String s = priceManager.formatPriceDefault(discountAmount);
                tvDiscountAmount.setText(s);
                DiagTracer.mark("D.updateFinalAmount tvDiscountAmount set", s);
            }
            if (tvFinalTotal != null) {
                DiagTracer.mark("D.updateFinalAmount before formatPriceDefault(final)");
                String s = priceManager.formatPriceDefault(finalAmount);
                tvFinalTotal.setText(s);
                DiagTracer.mark("D.updateFinalAmount tvFinalTotal set", s);
            }
        } catch (Exception e) {
            DiagTracer.markException("updateFinalAmount format", e);
        }

        DiagTracer.mark("D.updateFinalAmount END");
    }
}
