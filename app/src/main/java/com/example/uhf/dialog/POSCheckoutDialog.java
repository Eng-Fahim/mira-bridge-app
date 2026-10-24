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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

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
        
        public JSONArray payments = new JSONArray(); 
        public String localUuid = ""; 
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

    private LinearLayout llPaymentsContainer;
    private Button btnAddPayment;
    private TextView tvRemaining;

    private final List<PaymentMethodDto> paymentMethods = new ArrayList<>();
    private PaymentMethodDto selectedMethod = null;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean paymentWatcherAttached = false;

    private final List<View> paymentRows = new ArrayList<>();
    private final String[] CURRENCIES = {"YER", "SAR", "USD"};
    private double currentTotalWithDiscountUsd = 0.0;

    public static POSCheckoutDialog newInstance(double totalAmount, int itemCount) {
        POSCheckoutDialog dialog = new POSCheckoutDialog();
        Bundle args = new Bundle();
        args.putDouble(ARG_TOTAL_AMOUNT, totalAmount);
        args.putInt(ARG_ITEM_COUNT, itemCount);
        dialog.setArguments(args);
        return dialog;
    }

    public void setCheckoutListener(OnCheckoutListener listener) { this.listener = listener; }
    public void setOnDismissListener(OnDialogDismissListener listener) { this.dismissListener = listener; }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        if (dismissListener != null) {
            try { dismissListener.onDialogDismissed(); } catch (Exception ignored) {}
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            totalAmount = getArguments().getDouble(ARG_TOTAL_AMOUNT, 0.0);
            itemCount = getArguments().getInt(ARG_ITEM_COUNT, 0);
        }

        try {
            isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");
        } catch (Exception e) { isArabic = false; }

        if (getContext() != null) {
            try {
                orgProfile = MiraOrgProfileManager.getInstance(getContext());
                priceManager = MiraPriceManager.getInstance(getContext());
            } catch (Exception e) { DiagTracer.markException("managers", e); }
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
        try {
            if (getDialog() != null && getDialog().getWindow() != null) {
                getDialog().getWindow().setLayout(
                        (int) (getResources().getDisplayMetrics().widthPixels * 0.92),
                        WindowManager.LayoutParams.WRAP_CONTENT
                );
                getDialog().getWindow().setDimAmount(0.5f);
            }
        } catch (Exception ignored) {}
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_pos_checkout, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        try {
            bindViews(view);
            applyLanguage();
            setupSpinner();
            setupData();
            setupListeners();
        } catch (Exception e) { DiagTracer.markException("onViewCreated", e); }
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

        llPaymentsContainer = view.findViewById(R.id.llPaymentsContainer);
        btnAddPayment       = view.findViewById(R.id.btnAddPayment);
        tvRemaining         = view.findViewById(R.id.tvRemaining);
    }

    private void applyLanguage() {
        if (tvDialogTitle != null) tvDialogTitle.setText(isArabic ? "إتمام عملية البيع" : "Complete Sale");
        if (tilCustomerName != null) tilCustomerName.setHint(isArabic ? "اسم العميل *" : "Customer Name *");
        if (tilCustomerPhone != null) tilCustomerPhone.setHint(isArabic ? "رقم الهاتف *" : "Phone Number *");
        if (etCustomerEmail != null) etCustomerEmail.setHint(isArabic ? "البريد الإلكتروني (اختياري)" : "Email (Optional)");
        if (etCustomerAddress != null) etCustomerAddress.setHint(isArabic ? "العنوان (اختياري)" : "Address (Optional)");
        if (etCustomerNotes != null) etCustomerNotes.setHint(isArabic ? "ملاحظات (اختياري)" : "Notes (Optional)");
        if (etDiscount != null) etDiscount.setHint(isArabic ? "نسبة الخصم" : "Discount");
        if (btnCancelCheckout != null) btnCancelCheckout.setText(isArabic ? "إلغاء" : "Cancel");
        if (btnConfirmCheckout != null) btnConfirmCheckout.setText(isArabic ? "تأكيد البيع ✓" : "Confirm Sale ✓");
        if (btnAddPayment != null) btnAddPayment.setText(isArabic ? "+ إضافة عملة أخرى" : "+ Add Currency");
    }

    private void setupSpinner() {
        if (spPaymentMethod == null || getContext() == null) return;
        spPaymentMethod.setEnabled(false);
        spPaymentMethod.setHint(isArabic ? "جاري التحميل..." : "Loading...");
        spPaymentMethod.setThreshold(0);
        spPaymentMethod.setOnClickListener(v -> { if (spPaymentMethod.isEnabled()) spPaymentMethod.showDropDown(); });
        spPaymentMethod.setOnFocusChangeListener((v, hasFocus) -> { if (hasFocus && spPaymentMethod.isEnabled()) spPaymentMethod.showDropDown(); });
        loadPaymentMethodsFromServer();
    }

    private void loadPaymentMethodsFromServer() {
        new Thread(() -> {
            List<PaymentMethodDto> loaded = null;
            try {
                if (getContext() != null) {
                    MiraApiClient client = MiraApiClient.getInstance(requireContext().getApplicationContext());
                    MiraApiClient.ApiResponse response = client.getPaymentMethods();

                    if (response != null && response.isSuccess && response.data != null) {
                        JSONArray arr = response.data.optJSONArray("data");
                        if (arr == null) arr = response.data.optJSONArray("methods");

                        if (arr != null && arr.length() > 0) {
                            Gson gson = new Gson();
                            loaded = new ArrayList<>();
                            for (int i = 0; i < arr.length(); i++) {
                                try {
                                    JSONObject obj = arr.getJSONObject(i);
                                    PaymentMethodDto m = gson.fromJson(obj.toString(), PaymentMethodDto.class);
                                    if (m != null && m.code != null && !m.code.isEmpty()) loaded.add(m);
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                }
            } catch (Exception e) { Log.e(TAG, "loadPaymentMethods error", e); }

            final List<PaymentMethodDto> finalList = (loaded != null && !loaded.isEmpty()) ? loaded : getFallbackMethods();
            mainHandler.post(() -> applyPaymentMethods(finalList));
        }).start();
    }

    private List<PaymentMethodDto> getFallbackMethods() {
        List<PaymentMethodDto> list = new ArrayList<>();
        list.add(makeMethod("CASH", "نقداً", "Cash", "💵", "1101", "الصناديق والنقدية", "Cash Box"));
        list.add(makeMethod("BANK_TRANSFER", "تحويل بنكي", "Bank Transfer", "🏦", "1102", "البنوك والمصاريف", "Bank Account"));
        list.add(makeMethod("E_WALLET", "محفظة إلكترونية", "E-Wallet", "📱", "1103", "المحافظ الإلكترونية", "E-Wallets"));
        return list;
    }

    private PaymentMethodDto makeMethod(String code, String nameAr, String nameEn, String icon, String accountCode, String accNameAr, String accNameEn) {
        PaymentMethodDto m = new PaymentMethodDto();
        m.code = code; m.nameAr = nameAr; m.nameEn = nameEn; m.icon = icon;
        m.accountCode = accountCode; m.accountNameAr = accNameAr; m.accountName = accNameEn;
        return m;
    }

    private void applyPaymentMethods(List<PaymentMethodDto> methods) {
        if (spPaymentMethod == null || !isAdded()) return;
        paymentMethods.clear();
        paymentMethods.addAll(methods);
        String[] displayNames = new String[methods.size()];
        
        for (int i = 0; i < methods.size(); i++) {
            String icon = methods.get(i).icon != null ? methods.get(i).icon : "💳";
            displayNames[i] = icon + " " + methods.get(i).getDisplayName(isArabic);
        }

        spPaymentMethod.setEnabled(true);
        spPaymentMethod.setHint(isArabic ? "طريقة الدفع" : "Payment Method");

        if (!paymentWatcherAttached) {
            paymentWatcherAttached = true;
            spPaymentMethod.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    String text = s != null ? s.toString().trim() : "";
                    if (text.isEmpty()) return;
                    for (PaymentMethodDto m : paymentMethods) {
                        String icon = m.icon != null ? m.icon : "💳";
                        String expectedDisplay = icon + " " + m.getDisplayName(isArabic);
                        if (expectedDisplay.equals(text)) {
                            selectedMethod = m;
                            updateAccountNumberFromMethod(m);
                            break;
                        }
                    }
                }
            });
        }
        spPaymentMethod.setSimpleItems(displayNames);
        spPaymentMethod.setThreshold(0);

        if (!methods.isEmpty()) {
            spPaymentMethod.setText(displayNames[0], false);
            selectedMethod = methods.get(0);
            updateAccountNumberFromMethod(selectedMethod);
        }
        
        // ✅ [إصلاح]: تحديث قوائم طرق الدفع للسطور التي تمت إضافتها قبل التحميل
        for (View row : paymentRows) {
            Spinner spMethod = row.findViewById(R.id.spMethod);
            if (spMethod != null) { // تم إزالة شرط getAdapter() == null
                setupMethodSpinner(spMethod);
            }
        }
    }

    private void updateAccountNumberFromMethod(PaymentMethodDto method) {
        if (tvAccountNumber == null) return;
        if (method == null) { tvAccountNumber.setVisibility(View.GONE); return; }
        StringBuilder sb = new StringBuilder();
        if (method.icon != null && !method.icon.isEmpty()) sb.append(method.icon).append(" ");
        String accountDisplay = method.getAccountDisplay(isArabic);
        if (accountDisplay != null && !accountDisplay.isEmpty()) sb.append("🏦 ").append(accountDisplay);
        if (sb.length() > 0) {
            tvAccountNumber.setText(sb.toString());
            tvAccountNumber.setVisibility(View.VISIBLE);
        } else {
            tvAccountNumber.setVisibility(View.GONE);
        }
    }

    private void setupData() {
        if (tvItemCount != null) {
            tvItemCount.setText(isArabic ? String.format(Locale.getDefault(), "%d قطعة", itemCount) : String.format(Locale.getDefault(), "%d items", itemCount));
        }
        updateFinalAmount();
        addPaymentRow(CURRENCIES[0], 0.0);
    }

    private double getRateToYER(String currency) {
        if ("YER".equalsIgnoreCase(currency)) return 1.0;
        
        if (priceManager != null) {
            double yerRate = priceManager.getExchangeRate(MiraPriceManager.CURRENCY_YER); // 530
            double currencyRate = priceManager.getExchangeRate(currency); // USD = 1.0, SAR = 3.75
            
            if (currencyRate > 10) return currencyRate;
            
            if (yerRate > 0 && currencyRate > 0) {
                return yerRate / currencyRate; 
            }
        }
        
        if ("SAR".equalsIgnoreCase(currency)) return 141.33;
        if ("USD".equalsIgnoreCase(currency)) return 530.0;
        return 1.0;
    }

    private void addPaymentRow(String defaultCurrency, double amount) {
        if (llPaymentsContainer == null) return;
        
        View row = getLayoutInflater().inflate(R.layout.item_payment_row, llPaymentsContainer, false);
        Spinner spCurrency = row.findViewById(R.id.spCurrency);
        Spinner spMethod = row.findViewById(R.id.spMethod); 
        EditText etAmount = row.findViewById(R.id.etAmount);
        TextView tvEquivalent = row.findViewById(R.id.tvBaseEquivalent);
        ImageButton btnRemove = row.findViewById(R.id.btnRemove);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, CURRENCIES);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spCurrency.setAdapter(adapter);
        
        int index = Arrays.asList(CURRENCIES).indexOf(defaultCurrency);
        spCurrency.setSelection(index >= 0 ? index : 0);

        if (spMethod != null) {
            setupMethodSpinner(spMethod);
        }

        Runnable updateEquivalent = () -> {
            try {
                int pos = spCurrency.getSelectedItemPosition();
                if (pos >= 0 && tvEquivalent != null) {
                    String curr = CURRENCIES[pos];
                    double rate = getRateToYER(curr);
                    double amt = 0;
                    try { amt = Double.parseDouble(etAmount.getText().toString()); } catch (Exception ignored) {}
                    double base = amt * rate;
                    tvEquivalent.setText(String.format(Locale.US, "%,.2f YER", base));
                }
            } catch (Exception ignored) {}
        };

        etAmount.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { 
                updateEquivalent.run();
                updateRemainingBalance(); 
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        
        spCurrency.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { 
                updateEquivalent.run();
                updateRemainingBalance(); 
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnRemove.setOnClickListener(v -> {
            if (llPaymentsContainer.getChildCount() > 1) {
                llPaymentsContainer.removeView(row);
                paymentRows.remove(row);
                updateRemainingBalance();
            } else {
                Toast.makeText(requireContext(), "يجب أن يبقى سطر دفع واحد", Toast.LENGTH_SHORT).show();
            }
        });

        paymentRows.add(row);
        llPaymentsContainer.addView(row);
        if (amount > 0) etAmount.setText(String.valueOf(amount));
        updateEquivalent.run();
    }

    private void setupMethodSpinner(Spinner spMethod) {
        List<String> methodNames = new ArrayList<>();
        if (!paymentMethods.isEmpty()) {
            for (PaymentMethodDto m : paymentMethods) {
                String icon = m.icon != null ? m.icon : "💳";
                methodNames.add(icon + " " + m.getDisplayName(isArabic));
            }
        } else {
            methodNames.add(isArabic ? "💵 نقداً" : "💵 Cash");
            methodNames.add(isArabic ? "🏦 بنك" : "🏦 Bank");
        }
        ArrayAdapter<String> methodAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, methodNames);
        methodAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spMethod.setAdapter(methodAdapter);
    }

    private void updateRemainingBalance() {
        if (tvRemaining == null) return;
        
        double totalPaidInYer = 0;
        for (View row : paymentRows) {
            Spinner spCurrency = row.findViewById(R.id.spCurrency);
            EditText et = row.findViewById(R.id.etAmount);
            int pos = spCurrency.getSelectedItemPosition();
            if (pos >= 0) {
                String currency = CURRENCIES[pos];
                double rate = getRateToYER(currency);
                double amount = 0;
                try { amount = Double.parseDouble(et.getText().toString()); } catch (Exception ignored) {}
                totalPaidInYer += (amount * rate);
            }
        }
        
        double requiredTotalInYer = currentTotalWithDiscountUsd * getRateToYER("USD");
        double remaining = requiredTotalInYer - totalPaidInYer;
        
        if (Math.abs(remaining) < 1) {
            tvRemaining.setText("0.00 YER");
            tvRemaining.setTextColor(Color.parseColor("#16A34A")); 
        } else if (remaining > 0) {
            tvRemaining.setText(String.format(Locale.US, "%,.2f YER", remaining));
            tvRemaining.setTextColor(Color.parseColor("#DC2626")); 
        } else {
            tvRemaining.setText(String.format(Locale.US, "+ %,.2f YER", Math.abs(remaining)));
            tvRemaining.setTextColor(Color.parseColor("#F59E0B")); 
        }
    }

    private boolean validateInputs() {
        boolean isValid = true;
        if (etCustomerName != null && etCustomerName.getText().toString().trim().isEmpty()) {
            if (tilCustomerName != null) tilCustomerName.setError(isArabic ? "اسم العميل مطلوب" : "Required");
            isValid = false;
        } else if (tilCustomerName != null) tilCustomerName.setError(null);

        if (etCustomerPhone != null && etCustomerPhone.getText().toString().trim().isEmpty()) {
            if (tilCustomerPhone != null) tilCustomerPhone.setError(isArabic ? "رقم الهاتف مطلوب" : "Required");
            isValid = false;
        } else if (tilCustomerPhone != null) tilCustomerPhone.setError(null);
        
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

        if (btnCancelCheckout != null) btnCancelCheckout.setOnClickListener(v -> dismissAllowingStateLoss());
        
        if (btnAddPayment != null) btnAddPayment.setOnClickListener(v -> addPaymentRow(CURRENCIES[0], 0.0));

        if (btnConfirmCheckout != null) {
            btnConfirmCheckout.setOnClickListener(v -> {
                if (!validateInputs()) return;

                JSONArray paymentsArray = new JSONArray();
                double totalPaidInYer = 0;
                
                for (View row : paymentRows) {
                    Spinner spCurrency = row.findViewById(R.id.spCurrency);
                    Spinner spMethod = row.findViewById(R.id.spMethod);
                    EditText et = row.findViewById(R.id.etAmount);
                    
                    int posCurr = spCurrency.getSelectedItemPosition();
                    int posMethod = spMethod != null ? spMethod.getSelectedItemPosition() : 0;
                    double amount = 0;
                    try { amount = Double.parseDouble(et.getText().toString()); } catch (Exception ignored) {}
                    
                    if (amount > 0 && posCurr >= 0) {
                        String currency = CURRENCIES[posCurr];
                        double rate = getRateToYER(currency);
                        double base = amount * rate;
                        totalPaidInYer += base;
                        
                        String methodCode = "CASH";
                        if (!paymentMethods.isEmpty() && posMethod >= 0 && posMethod < paymentMethods.size()) {
                            methodCode = paymentMethods.get(posMethod).code;
                        }
                        
                        try {
                            JSONObject pObj = new JSONObject();
                            pObj.put("currency", currency);
                            pObj.put("amount", amount);
                            pObj.put("exchange_rate", rate);
                            pObj.put("base_amount", base);
                            pObj.put("method", methodCode);
                            paymentsArray.put(pObj);
                        } catch (Exception ignored) {}
                    }
                }

                double requiredTotalInYer = currentTotalWithDiscountUsd * getRateToYER("USD");
                if (Math.abs(totalPaidInYer - requiredTotalInYer) > 1) {
                    Toast.makeText(requireContext(), isArabic ? "⚠️ إجمالي المدفوعات لا يطابق الفاتورة" : "⚠️ Payment total mismatch", Toast.LENGTH_SHORT).show();
                    return;
                }

                POSCheckoutData data = new POSCheckoutData();
                if (etCustomerName != null) data.customerName = etCustomerName.getText().toString().trim();
                if (etCustomerPhone != null) data.customerPhone = etCustomerPhone.getText().toString().trim();
                if (etCustomerEmail != null) data.customerEmail = etCustomerEmail.getText().toString().trim();
                if (etCustomerAddress != null) data.customerAddress = etCustomerAddress.getText().toString().trim();
                if (etCustomerNotes != null) data.customerNotes = etCustomerNotes.getText().toString().trim();

                data.paymentMethod = getCurrentPaymentMethod();
                data.accountCode = getCurrentAccountCode();
                data.currency = "MIX"; 

                String discountStr = (etDiscount != null) ? etDiscount.getText().toString().trim() : "0";
                try { data.discount = discountStr.isEmpty() ? 0 : Double.parseDouble(discountStr); } catch (Exception e) { data.discount = 0; }
                
                data.originalTotal = totalAmount;
                data.discountAmount = totalAmount * (data.discount / 100.0);
                data.finalAmount = totalAmount - data.discountAmount;
                data.payments = paymentsArray; 

                dismissAllowingStateLoss();
                if (listener != null) listener.onCheckout(data);
            });
        }
    }

    private void updateFinalAmount() {
        if (priceManager == null) return;
        double discountPercent = 0.0;
        if (etDiscount != null) {
            String val = etDiscount.getText().toString().trim();
            if (!val.isEmpty()) { try { discountPercent = Double.parseDouble(val); } catch (Exception ignored) {} }
        }

        double discountAmount = totalAmount * (discountPercent / 100.0);
        double finalAmount = totalAmount - discountAmount;
        currentTotalWithDiscountUsd = finalAmount;

        try {
            if (tvSubtotal != null) tvSubtotal.setText(priceManager.formatPriceDefault(totalAmount));
            if (tvDiscountAmount != null) tvDiscountAmount.setText(priceManager.formatPriceDefault(discountAmount));
            if (tvFinalTotal != null) tvFinalTotal.setText(priceManager.formatPriceDefault(finalAmount));
        } catch (Exception ignored) {}
        
        updateRemainingBalance(); 
    }
    
    private String getCurrentPaymentMethod() {
        if (selectedMethod != null && selectedMethod.code != null) return selectedMethod.code;
        return "CASH";
    }
    
    private String getCurrentAccountCode() {
        if (selectedMethod != null && selectedMethod.accountCode != null) return selectedMethod.accountCode;
        return "1101";
    }
    }
