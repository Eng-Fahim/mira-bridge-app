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
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.DialogFragment;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.api.dto.PaymentMethodDto;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.MiraPriceManager;
import com.example.uhf.utils.DiagTracer;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;
import com.google.gson.Gson;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * POSCheckoutDialog v2.3 — Dynamic Payment Methods Edition
 *
 * ✅ الميزات الجديدة:
 *  1. تحميل طرق الدفع من السيرفر (endpoint: /pos/payment-methods)
 *  2. عرض الحساب المرتبط بكل طريقة
 *  3. Fallback محلي عند فشل السيرفر
 */
public class POSCheckoutDialog extends DialogFragment {

    private static final String TAG = "POSCheckoutDialog";

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
        public String accountCode = "1101";
        public String currency = "USD";
        public double originalTotal = 0.0;
        public double discount = 0.0;
        public double discountAmount = 0.0;
        public double finalAmount = 0.0;
    }

    private static final String ARG_TOTAL_AMOUNT = "total_amount";
    private static final String ARG_ITEM_COUNT = "item_count";

    private double totalAmount = 0.0;
    private int itemCount = 0;
    private OnCheckoutListener listener;
    private OnDialogDismissListener dismissListener;
    private boolean isArabic;
    private MiraOrgProfileManager orgProfile;
    private MiraPriceManager priceManager;

    private TextView tvDialogTitle, tvItemCount, tvSubtotal, tvDiscountAmount,
            tvFinalTotal, tvAccountNumber;
    private EditText etCustomerName, etCustomerPhone, etCustomerEmail,
            etCustomerAddress, etCustomerNotes, etDiscount;
    private TextInputLayout tilCustomerName, tilCustomerPhone;
    private MaterialAutoCompleteTextView spPaymentMethod;
    private Button btnConfirmCheckout, btnCancelCheckout;

    // ═══════════════════════════════════════════════════════════
    //  ✅ Payment Methods — ديناميكي من السيرفر
    // ═══════════════════════════════════════════════════════════
    private final List<PaymentMethodDto> paymentMethods = new ArrayList<>();
    private PaymentMethodDto selectedMethod = null;
    private final android.os.Handler mainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
            
    private boolean paymentWatcherAttached = false;
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

        if (getArguments() != null) {
            totalAmount = getArguments().getDouble(ARG_TOTAL_AMOUNT, 0.0);
            itemCount = getArguments().getInt(ARG_ITEM_COUNT, 0);
            DiagTracer.mark("D.onCreate args",
                    "total=" + totalAmount + ", count=" + itemCount);
        }

        try {
            isArabic = AppCompatDelegate.getApplicationLocales()
                    .toLanguageTags().contains("ar");
        } catch (Exception e) {
            isArabic = false;
        }

        if (getContext() != null) {
            try {
                orgProfile = MiraOrgProfileManager.getInstance(getContext());
                priceManager = MiraPriceManager.getInstance(getContext());
            } catch (Exception e) {
                DiagTracer.markException("managers", e);
            }
        }

        DiagTracer.mark("D.onCreate END");
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        if (dialog.getWindow() != null) {
            dialog.getWindow().requestFeature(Window.FEATURE_NO_TITLE);
            dialog.getWindow().setBackgroundDrawable(
                    new ColorDrawable(Color.TRANSPARENT));
        }
        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        try {
            if (getDialog() != null && getDialog().getWindow() != null) {
                getDialog().getWindow().setLayout(
                        (int) (getResources().getDisplayMetrics().widthPixels * 0.92),
                        WindowManager.LayoutParams.WRAP_CONTENT
                );
                getDialog().getWindow().setDimAmount(0.5f);
            }
        } catch (Exception e) {
            DiagTracer.markException("onStart window", e);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = null;
        try {
            v = inflater.inflate(R.layout.dialog_pos_checkout, container, false);
        } catch (Exception e) {
            DiagTracer.markException("onCreateView inflate", e);
        }
        return v;
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        try {
            bindViews(view);
            applyLanguage();
            setupSpinner();
            setupData();
            setupListeners();
        } catch (Exception e) {
            DiagTracer.markException("onViewCreated", e);
        }
    }

    private void bindViews(View view) {
        tvDialogTitle    = view.findViewById(R.id.tvDialogTitle);
        tvItemCount      = view.findViewById(R.id.tvItemCount);
        tvSubtotal       = view.findViewById(R.id.tvSubtotal);
        tvDiscountAmount = view.findViewById(R.id.tvDiscountAmount);
        tvFinalTotal     = view.findViewById(R.id.tvFinalTotal);
        tvAccountNumber  = view.findViewById(R.id.tvAccountNumber);

        tilCustomerName  = view.findViewById(R.id.tilCustomerName);
        tilCustomerPhone = view.findViewById(R.id.tilCustomerPhone);

        etCustomerName    = view.findViewById(R.id.etCustomerName);
        etCustomerPhone   = view.findViewById(R.id.etCustomerPhone);
        etCustomerEmail   = view.findViewById(R.id.etCustomerEmail);
        etCustomerAddress = view.findViewById(R.id.etCustomerAddress);
        etCustomerNotes   = view.findViewById(R.id.etCustomerNotes);
        etDiscount        = view.findViewById(R.id.etDiscount);

        spPaymentMethod    = view.findViewById(R.id.spPaymentMethod);
        btnCancelCheckout  = view.findViewById(R.id.btnCancelCheckout);
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
            etCustomerEmail.setHint(isArabic
                    ? "البريد الإلكتروني (اختياري)" : "Email (Optional)");
        }
        if (etCustomerAddress != null) {
            etCustomerAddress.setHint(isArabic
                    ? "العنوان (اختياري)" : "Address (Optional)");
        }
        if (etCustomerNotes != null) {
            etCustomerNotes.setHint(isArabic
                    ? "ملاحظات (اختياري)" : "Notes (Optional)");
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
    // ✅ Payment Methods — Dynamic Loading
    // ═══════════════════════════════════════════════════════════

    private void setupSpinner() {
        if (spPaymentMethod == null || getContext() == null) return;

        spPaymentMethod.setEnabled(false);
        spPaymentMethod.setHint(isArabic ? "جاري التحميل..." : "Loading...");

        loadPaymentMethodsFromServer();
    }

    /**
     * ✅ تحميل طرق الدفع من السيرفر (background thread)
     */
    private void loadPaymentMethodsFromServer() {
        new Thread(() -> {
            List<PaymentMethodDto> loaded = null;

            try {
                if (getContext() != null) {
                    MiraApiClient client = MiraApiClient.getInstance(
                            requireContext().getApplicationContext());
                    MiraApiClient.ApiResponse response = client.getPaymentMethods();

                    if (response != null && response.isSuccess && response.data != null) {
                        JSONArray arr = response.data.optJSONArray("data");
                        if (arr == null) arr = response.data.optJSONArray("methods");

                        if (arr != null && arr.length() > 0) {
                            Gson gson = new Gson();
                            loaded = new ArrayList<>();
                            for (int i = 0; i < arr.length(); i++) {
                                try {
                                    PaymentMethodDto m = gson.fromJson(
                                            arr.getJSONObject(i).toString(),
                                            PaymentMethodDto.class);
                                    if (m != null
                                            && m.code != null
                                            && !m.code.isEmpty()) {
                                        loaded.add(m);
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "loadPaymentMethodsFromServer error: " + e.getMessage());
            }

            final List<PaymentMethodDto> finalList =
                    (loaded != null && !loaded.isEmpty()) ? loaded : getFallbackMethods();
            final boolean isFallback = (loaded == null || loaded.isEmpty());

            mainHandler.post(() -> applyPaymentMethods(finalList, isFallback));
        }).start();
    }

    /**
     * ✅ قائمة احتياطية عند فشل السيرفر
     */
    private List<PaymentMethodDto> getFallbackMethods() {
        List<PaymentMethodDto> list = new ArrayList<>();

        list.add(makeMethod("CASH", "نقداً", "Cash", "💵",
                "1101", "الصناديق والنقدية", "Cash Box"));
        list.add(makeMethod("CARD", "بطاقة", "Card", "💳",
                "1102", "البنوك والمصارف", "Bank Account"));
        list.add(makeMethod("BANK_TRANSFER", "تحويل بنكي", "Bank Transfer", "🏦",
                "1102", "البنوك والمصارف", "Bank Account"));
        list.add(makeMethod("CREDIT", "آجل", "Credit", "⏳",
                "1201", "العملاء والمدينون", "Customers"));

        return list;
    }

    private PaymentMethodDto makeMethod(String code, String nameAr, String nameEn,
                                        String icon, String accountCode,
                                        String accNameAr, String accNameEn) {
        PaymentMethodDto m = new PaymentMethodDto();
        m.code = code;
        m.nameAr = nameAr;
        m.nameEn = nameEn;
        m.icon = icon;
        m.accountCode = accountCode;
        m.accountNameAr = accNameAr;
        m.accountName = accNameEn;
        return m;
    }

    /**
     * ✅ تطبيق القائمة على الواجهة
     */
    private void applyPaymentMethods(List<PaymentMethodDto> methods,
                                 boolean isFallback) {
    if (spPaymentMethod == null || !isAdded()) return;

    paymentMethods.clear();
    paymentMethods.addAll(methods);

    String[] displayNames = new String[methods.size()];
    for (int i = 0; i < methods.size(); i++) {
        displayNames[i] = methods.get(i).getDisplayName(isArabic);
    }

    // ✅ Log للتشخيص — سيُظهر عدد الطرق المستلمة
    Log.i(TAG, "📋 applyPaymentMethods: " + methods.size() + " methods"
            + " → " + java.util.Arrays.toString(displayNames));

    spPaymentMethod.setEnabled(true);
    spPaymentMethod.setHint(isArabic ? "طريقة الدفع" : "Payment Method");

    // ✅ 1) امسح المستمع القديم (لتفادي التكرار عند إعادة الفتح)
    //    لا يمكن إزالة TextWatcher قديم بدون مرجع، لذا نتجنب الإضافة المتكررة
    //    عبر flag
    if (!paymentWatcherAttached) {
        paymentWatcherAttached = true;
        spPaymentMethod.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override
            public void afterTextChanged(Editable s) {
                String text = s != null ? s.toString().trim() : "";
                if (text.isEmpty()) return;

                // ابحث عن الطريقة المطابقة
                for (PaymentMethodDto m : paymentMethods) {
                    if (m.getDisplayName(isArabic).equals(text)) {
                        selectedMethod = m;
                        updateAccountNumberFromMethod(m);
                        Log.d(TAG, "💳 Selected payment: " + m.code);
                        break;
                    }
                }
            }
        });
    }

    // ✅ 2) ضبط القائمة (بدون setOnItemClickListener)
    spPaymentMethod.setSimpleItems(displayNames);

    // ✅ 3) اختيار الطريقة الافتراضية
    if (!methods.isEmpty()) {
        spPaymentMethod.setText(displayNames[0], false);
        selectedMethod = methods.get(0);
        updateAccountNumberFromMethod(selectedMethod);
    } else {
        spPaymentMethod.setText("", false);
        selectedMethod = null;
        if (tvAccountNumber != null) {
            tvAccountNumber.setVisibility(View.GONE);
        }
    }

    if (isFallback) {
        Log.w(TAG, "⚠️ Using fallback payment methods (server may be down)");
    } else {
        Log.d(TAG, "✅ Loaded " + methods.size() + " payment methods from server");
    }
}

    /**
     * ✅ عرض بيانات الحساب المرتبط
     */
    private void updateAccountNumberFromMethod(PaymentMethodDto method) {
        if (tvAccountNumber == null) return;

        if (method == null) {
            tvAccountNumber.setVisibility(View.GONE);
            return;
        }

        StringBuilder sb = new StringBuilder();

        if (method.icon != null && !method.icon.isEmpty()) {
            sb.append(method.icon).append(" ");
        }

        String accountDisplay = method.getAccountDisplay(isArabic);
        if (accountDisplay != null && !accountDisplay.isEmpty()) {
            sb.append("🏦 ").append(accountDisplay);
        }

        if (sb.length() > 0) {
            tvAccountNumber.setText(sb.toString());
            tvAccountNumber.setVisibility(View.VISIBLE);
        } else {
            tvAccountNumber.setVisibility(View.GONE);
        }
    }

    /**
     * ✅ الطريقة المختارة
     */
    private String getCurrentPaymentMethod() {
        if (selectedMethod != null
                && selectedMethod.code != null
                && !selectedMethod.code.isEmpty()) {
            return selectedMethod.code;
        }

        // fallback: parse من النص
        if (spPaymentMethod == null || spPaymentMethod.getText() == null) {
            return "CASH";
        }
        String text = spPaymentMethod.getText().toString();
        if (text.contains("تحويل") || text.contains("Bank")) return "BANK_TRANSFER";
        if (text.contains("بطاق") || text.contains("Card")) return "CARD";
        if (text.contains("آجل") || text.contains("Credit")) return "CREDIT";
        return "CASH";
    }

    private String getCurrentAccountCode() {
        if (selectedMethod != null
                && selectedMethod.accountCode != null
                && !selectedMethod.accountCode.isEmpty()) {
            return selectedMethod.accountCode;
        }
        return "1101";
    }

    // ═══════════════════════════════════════════════════════════
    // Data
    // ═══════════════════════════════════════════════════════════

    private void setupData() {
        if (tvItemCount != null) {
            String text = isArabic
                    ? String.format(Locale.getDefault(), "%d قطعة", itemCount)
                    : String.format(Locale.getDefault(), "%d items", itemCount);
            tvItemCount.setText(text);
        }

        updateFinalAmount();
    }

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

    private void setupListeners() {
        if (etDiscount != null) {
            etDiscount.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    updateFinalAmount();
                }
                @Override
                public void afterTextChanged(Editable s) {}
            });
        }

        if (btnCancelCheckout != null) {
            btnCancelCheckout.setOnClickListener(v -> dismissAllowingStateLoss());
        }

        if (btnConfirmCheckout != null) {
            btnConfirmCheckout.setOnClickListener(v -> {
                if (!validateInputs()) return;

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

                data.paymentMethod = getCurrentPaymentMethod();
                data.accountCode = getCurrentAccountCode();

                if (priceManager != null) {
                    data.currency = priceManager.getDefaultCurrency();
                }

                String discountStr = (etDiscount != null)
                        ? etDiscount.getText().toString().trim() : "0";
                try {
                    data.discount = discountStr.isEmpty()
                            ? 0 : Double.parseDouble(discountStr);
                } catch (Exception e) {
                    data.discount = 0;
                }

                data.originalTotal = totalAmount;
                data.discountAmount = totalAmount * (data.discount / 100.0);
                data.finalAmount = totalAmount - data.discountAmount;

                dismissAllowingStateLoss();

                if (listener != null) {
                    listener.onCheckout(data);
                }
            });
        }
    }

    private void updateFinalAmount() {
        if (priceManager == null) return;

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

        try {
            if (tvSubtotal != null) {
                tvSubtotal.setText(priceManager.formatPriceDefault(totalAmount));
            }
            if (tvDiscountAmount != null) {
                tvDiscountAmount.setText(priceManager.formatPriceDefault(discountAmount));
            }
            if (tvFinalTotal != null) {
                tvFinalTotal.setText(priceManager.formatPriceDefault(finalAmount));
            }
        } catch (Exception e) {
            DiagTracer.markException("updateFinalAmount format", e);
        }
    }
}
