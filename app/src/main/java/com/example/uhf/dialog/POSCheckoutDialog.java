package com.example.uhf.dialog;

import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
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
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  POSCheckoutDialog v2.4 — Dynamic Payment Methods Edition
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * إصلاحات v2.4:
 *   ✅ حذف التكرار في `paymentWatcherAttached`.
 *   ✅ إصلاح `applyPaymentMethods` (كانت مقطوعة).
 *   ✅ استخدام `addTextChangedListener` بدلاً من
 *      `setOnItemClickListener` (الأخير يُعطّل dropdown الخاص بـ Material).
 *   ✅ استيراد `JSONObject`.
 *   ✅ Logs تشخيصية لعدد الطرق المستلمة من السيرفر.
 *
 * الميزات الأساسية:
 *   ✅ تحميل طرق الدفع من السيرفر (`/pos/payment-methods`).
 *   ✅ عرض الحساب المرتبط بكل طريقة.
 *   ✅ Fallback محلي عند فشل السيرفر.
 */
public class POSCheckoutDialog extends DialogFragment {

    private static final String TAG = "POSCheckoutDialog";

    // ═══════════════════════════════════════════════════════════
    //  Callbacks
    // ═══════════════════════════════════════════════════════════

    public interface OnCheckoutListener {
        void onCheckout(POSCheckoutData data);
    }

    public interface OnDialogDismissListener {
        void onDialogDismissed();
    }

    // ═══════════════════════════════════════════════════════════
    //  Data Model
    // ═══════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════
    //  Arguments
    // ═══════════════════════════════════════════════════════════

    private static final String ARG_TOTAL_AMOUNT = "total_amount";
    private static final String ARG_ITEM_COUNT = "item_count";

    // ═══════════════════════════════════════════════════════════
    //  State
    // ═══════════════════════════════════════════════════════════

    private double totalAmount = 0.0;
    private int itemCount = 0;
    private OnCheckoutListener listener;
    private OnDialogDismissListener dismissListener;
    private boolean isArabic;
    private MiraOrgProfileManager orgProfile;
    private MiraPriceManager priceManager;

    // ═══════════════════════════════════════════════════════════
    //  Views
    // ═══════════════════════════════════════════════════════════

    private TextView tvDialogTitle, tvItemCount, tvSubtotal, tvDiscountAmount,
            tvFinalTotal, tvAccountNumber;
    private EditText etCustomerName, etCustomerPhone, etCustomerEmail,
            etCustomerAddress, etCustomerNotes, etDiscount;
    private TextInputLayout tilCustomerName, tilCustomerPhone;
    private MaterialAutoCompleteTextView spPaymentMethod;
    private Button btnConfirmCheckout, btnCancelCheckout;

    // ═══════════════════════════════════════════════════════════
    //  Payment Methods
    // ═══════════════════════════════════════════════════════════

    private final List<PaymentMethodDto> paymentMethods = new ArrayList<>();
    private PaymentMethodDto selectedMethod = null;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** ✅ لمنع إضافة TextWatcher أكثر من مرة عند إعادة فتح الـ Dialog */
    private boolean paymentWatcherAttached = false;

    // ═══════════════════════════════════════════════════════════
    //  Factory
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
    //  Lifecycle
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

    // ═══════════════════════════════════════════════════════════
    //  Views Binding
    // ═══════════════════════════════════════════════════════════

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
    //  Payment Methods — Spinner Initialization
    // ═══════════════════════════════════════════════════════════

    private void setupSpinner() {
    if (spPaymentMethod == null || getContext() == null) return;

    spPaymentMethod.setEnabled(false);
    spPaymentMethod.setHint(isArabic ? "جاري التحميل..." : "Loading...");

    // ✅ [FIX] افتح القائمة عند النقر على الحقل (حتى قبل التحميل)
    spPaymentMethod.setThreshold(0);

    spPaymentMethod.setOnClickListener(v -> {
        if (spPaymentMethod.isEnabled()) {
            try {
                spPaymentMethod.showDropDown();
            } catch (Exception e) {
                Log.w(TAG, "showDropDown failed", e);
            }
        }
    });

    spPaymentMethod.setOnFocusChangeListener((v, hasFocus) -> {
        if (hasFocus && spPaymentMethod.isEnabled()) {
            try {
                spPaymentMethod.showDropDown();
            } catch (Exception e) {
                Log.w(TAG, "showDropDown (focus) failed", e);
            }
        }
    });

    loadPaymentMethodsFromServer();
}

    /**
     * ✅ تحميل طرق الدفع من السيرفر على خيط خلفي.
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
                            Log.i(TAG, "📥 Server returned " + arr.length() + " items");
                            Gson gson = new Gson();
                            loaded = new ArrayList<>();

                            for (int i = 0; i < arr.length(); i++) {
                                try {
                                    JSONObject obj = arr.getJSONObject(i);
                                    Log.d(TAG, "   item[" + i + "]: " + obj.toString());

                                    PaymentMethodDto m = gson.fromJson(
                                            obj.toString(),
                                            PaymentMethodDto.class);

                                    if (m != null && m.code != null && !m.code.isEmpty()) {
                                        loaded.add(m);
                                        Log.d(TAG, "   ✅ Accepted: " + m.code);
                                    } else {
                                        Log.w(TAG, "   ⚠️ Rejected item[" + i + "]: code=" +
                                                (m != null ? m.code : "null"));
                                    }
                                } catch (Exception e) {
                                    Log.e(TAG, "   ❌ item[" + i + "] parse error: "
                                            + e.getMessage());
                                }
                            }
                            Log.i(TAG, "✅ Accepted " + loaded.size()
                                    + " methods after filtering");
                        } else {
                            Log.w(TAG, "⚠️ Server returned empty or no array");
                        }
                    } else {
                        Log.w(TAG, "⚠️ Server response invalid or unsuccessful");
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "loadPaymentMethodsFromServer error: " + e.getMessage(), e);
            }

            final List<PaymentMethodDto> finalList =
                    (loaded != null && !loaded.isEmpty())
                            ? loaded : getFallbackMethods();
            final boolean isFallback = (loaded == null || loaded.isEmpty());

            mainHandler.post(() -> applyPaymentMethods(finalList, isFallback));
        }).start();
    }

    /**
     * ✅ قائمة احتياطية عند فشل السيرفر.
     */
    private List<PaymentMethodDto> getFallbackMethods() {
    List<PaymentMethodDto> list = new ArrayList<>();

    list.add(makeMethod("CASH", "نقداً", "Cash",
            "💵", "1101", "الصناديق والنقدية", "Cash Box"));

    list.add(makeMethod("BANK_TRANSFER", "تحويل بنكي", "Bank Transfer",
            "🏦", "1102", "البنوك والمصارف", "Bank Account"));

    list.add(makeMethod("E_WALLET", "محفظة إلكترونية", "E-Wallet",
            "📱", "1103", "المحافظ الإلكترونية", "E-Wallets"));

    list.add(makeMethod("CHECK", "شيك", "Check",
            "📝", "1104", "شيكات تحت التحصيل", "Checks under Collection"));

    list.add(makeMethod("CREDIT", "آجل", "Credit",
            "⏳", "1201", "العملاء والمدينون", "Customers / Receivables"));

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
 * ✅ تطبيق قائمة طرق الدفع على الواجهة — [v2.5 FIXED]
 *
 * إصلاحات v2.5:
 *   - setThreshold(0, THRESHOLD_ALWAYS) — تُفتح القائمة فوراً عند النقر
 *   - post() بعد setText — تضمن أن الـ dropdown جاهز بعد layout
 *   - Logs تفصيلية للتحقق من عدد الطرق المُطبّقة
 */
private void applyPaymentMethods(List<PaymentMethodDto> methods,
                                 boolean isFallback) {
    if (spPaymentMethod == null || !isAdded()) return;

    // ═══════════════════════════════════════════════════════════
    // 1) تحديث البيانات الداخلية
    // ═══════════════════════════════════════════════════════════
    paymentMethods.clear();
    paymentMethods.addAll(methods);

    String[] displayNames = new String[methods.size()];
    for (int i = 0; i < methods.size(); i++) {
        displayNames[i] = methods.get(i).getDisplayName(isArabic);
    }

    Log.i(TAG, "📋 applyPaymentMethods: " + methods.size() + " methods"
            + " → " + Arrays.toString(displayNames));

    // ═══════════════════════════════════════════════════════════
    // 2) تفعيل الحقل + ضبط الـ hint
    // ═══════════════════════════════════════════════════════════
    spPaymentMethod.setEnabled(true);
    spPaymentMethod.setHint(isArabic ? "طريقة الدفع" : "Payment Method");

    // ═══════════════════════════════════════════════════════════
    // 3) TextWatcher — لا يُضاف مرتين
    // ═══════════════════════════════════════════════════════════
    if (!paymentWatcherAttached) {
        paymentWatcherAttached = true;
        spPaymentMethod.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable s) {
                String text = s != null ? s.toString().trim() : "";
                if (text.isEmpty()) return;

                for (PaymentMethodDto m : paymentMethods) {
                    if (m.getDisplayName(isArabic).equals(text)) {
                        selectedMethod = m;
                        updateAccountNumberFromMethod(m);
                        Log.d(TAG, "💳 Selected: " + m.code);
                        break;
                    }
                }
            }
        });
    }

    // ═══════════════════════════════════════════════════════════
    // 4) ضبط القائمة
    // ═══════════════════════════════════════════════════════════
    spPaymentMethod.setSimpleItems(displayNames);

    // ✅ [FIX] اجعل القائمة تُفتح فوراً عند النقر (threshold = 0)
    
    spPaymentMethod.setThreshold(0);

    // ═══════════════════════════════════════════════════════════
    // 5) اختيار الطريقة الافتراضية
    // ═══════════════════════════════════════════════════════════
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

    // ✅ [FIX] تأكد أن الـ dropdown جاهز — يعيد التطبيق بعد layout
    spPaymentMethod.post(() -> {
        try {
            spPaymentMethod.setThreshold(0);

            // ✅ اختياري: إن أردت فتح القائمة تلقائياً عند تحميلها، أزل التعليق:
            // spPaymentMethod.showDropDown();
            // spPaymentMethod.dismissDropDown();  // لإغلاقها فوراً

        } catch (Exception e) {
            Log.w(TAG, "applyPaymentMethods post setup failed", e);
        }
    });

    // ═══════════════════════════════════════════════════════════
    // 6) Logs
    // ═══════════════════════════════════════════════════════════
    if (isFallback) {
        Log.w(TAG, "⚠️ Using FALLBACK payment methods: "
                + methods.size() + " items");
    } else {
        Log.d(TAG, "✅ Loaded " + methods.size() + " methods from server");
    }
}

    /**
     * ✅ عرض بيانات الحساب المرتبط بالطريقة المختارة.
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
     * ✅ رمز الطريقة المختارة.
     */
    private String getCurrentPaymentMethod() {
        if (selectedMethod != null
                && selectedMethod.code != null
                && !selectedMethod.code.isEmpty()) {
            return selectedMethod.code;
        }

        // Fallback: parse من النص
        if (spPaymentMethod == null || spPaymentMethod.getText() == null) {
            return "CASH";
        }

        String text = spPaymentMethod.getText().toString();
if (text.contains("تحويل") || text.contains("Bank")) return "BANK_TRANSFER";
if (text.contains("بطاق") || text.contains("Card")) return "CARD";
if (text.contains("محفظ") || text.contains("Wallet")) return "E_WALLET";
if (text.contains("شيك") || text.contains("Check")) return "CHECK";
if (text.contains("آجل") || text.contains("Credit")) return "CREDIT";
return "CASH";
    }

    /**
     * ✅ رمز الحساب المرتبط بالطريقة المختارة.
     */
    private String getCurrentAccountCode() {
        if (selectedMethod != null
                && selectedMethod.accountCode != null
                && !selectedMethod.accountCode.isEmpty()) {
            return selectedMethod.accountCode;
        }
        return "1101";
    }

    // ═══════════════════════════════════════════════════════════
    //  Setup Data
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

    // ═══════════════════════════════════════════════════════════
    //  Validation
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
    //  Setup Listeners
    // ═══════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════
    //  Total Calculation
    // ═══════════════════════════════════════════════════════════

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
