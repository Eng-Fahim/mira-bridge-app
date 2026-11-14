package com.example.uhf.dialog;

import android.app.Dialog;
import android.content.DialogInterface;
import android.content.res.Configuration;
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
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.DialogFragment;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.api.dto.PaymentMethodDto;
import com.example.uhf.manager.MiraPriceManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;
import com.google.gson.Gson;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * POSCheckoutDialog v2.0 — Full Web Parity
 *
 * 🎯 الميزات الأساسية:
 *  - اختيار طريقة الدفع من قائمة (MaterialAutoCompleteTextView) — قائمة من السيرفر
 *  - اختيار العملة من قائمة (YER/SAR/USD + أي عملة أخرى من exchange_rates)
 *  - زر "المتبقي" لكل سطر
 *  - دعم عدة دفعات وعدة عملات
 *  - دعم دفع بالذهب (Trade-in)
 *  - إظهار حالة الدفع (ناقص/متطابق/رصيد دائن) بدعم الوضع الداكن
 *  - لا تُغلق عند الفشل — تعرض الخطأ داخلياً وتحفظ الإدخالات
 *
 * 🔧 Critical: code المخزن في actMethod.getTag() هو المصدر الموثوق
 *    (لا نطابق النص العربي — يسبب أخطاء "طريقة الدفع غير معرّفة")
 */
public class POSCheckoutDialog extends DialogFragment {

    private static final String TAG = "POSCheckoutDialog";
    private static final String ARG_TOTAL_AMOUNT = "total_amount";
    private static final String ARG_ITEM_COUNT = "item_count";

    private final List<String> CURRENCIES = new ArrayList<>(Arrays.asList("YER", "SAR", "USD"));

    public interface OnCheckoutListener { void onCheckout(POSCheckoutData data); }
    public interface OnDialogDismissListener { void onDialogDismissed(); }

    public static class POSCheckoutData {
        public String customerName = "", customerPhone = "", customerEmail = "",
                customerAddress = "", customerNotes = "";
        public String paymentMethod = "CASH", accountCode = "1101", currency = "MIX";
        public double originalTotal = 0.0, discount = 0.0, discountAmount = 0.0, finalAmount = 0.0;
        public JSONArray payments = new JSONArray();
        public JSONArray tradeIns = new JSONArray();
        public double tradeInTotal = 0.0;
        public String localUuid = "";
    }

    // ═══════════════════════════════════════════════════════════════════
    //  State
    // ═══════════════════════════════════════════════════════════════════

    private double totalAmount = 0.0;
    private int itemCount = 0;
    private OnCheckoutListener listener;
    private OnDialogDismissListener dismissListener;
    private boolean isArabic;
    private MiraPriceManager priceManager;

    // View references
    private TextView tvDialogTitle, tvItemCount, tvFinalTotal, tvRemaining, tvStatusIcon,
            tvStatusLabel, tvTradeInTotal;
    private LinearLayout llPaymentsContainer, llTradeInSummary, llStatusBanner,
            llInlineError;
    private TextView tvInlineError;
    private MaterialButton btnCloseDialog, btnCancelCheckout, btnConfirmCheckout,
            btnAddPayment, btnTradeIn;
    private EditText etCustomerName, etCustomerPhone;
    private TextInputLayout tilCustomerName, tilCustomerPhone;

    // Data
    private final List<PaymentMethodDto> paymentMethods = new ArrayList<>();
    private final List<View> paymentRows = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private double tradeInTotal = 0.0;
    private JSONArray tradeInLines = new JSONArray();
    private double currentTotalWithDiscountUsd = 0.0;

    // ═══════════════════════════════════════════════════════════════════
    //  Constructor / Lifecycle
    // ═══════════════════════════════════════════════════════════════════

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
            priceManager = MiraPriceManager.getInstance(getContext());
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
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.95),
                    WindowManager.LayoutParams.WRAP_CONTENT);
            getDialog().getWindow().setDimAmount(0.6f);
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
        loadPaymentMethods();
        loadCurrencies();
    }

    private void bindViews(View view) {
        tvDialogTitle    = view.findViewById(R.id.tvDialogTitle);
        tvItemCount      = view.findViewById(R.id.tvItemCount);
        tvFinalTotal     = view.findViewById(R.id.tvFinalTotal);
        tvRemaining      = view.findViewById(R.id.tvRemaining);
        tvStatusIcon     = view.findViewById(R.id.tvStatusIcon);
        tvStatusLabel    = view.findViewById(R.id.tvStatusLabel);
        tvTradeInTotal   = view.findViewById(R.id.tvTradeInTotal);

        llPaymentsContainer = view.findViewById(R.id.llPaymentsContainer);
        llTradeInSummary    = view.findViewById(R.id.llTradeInSummary);
        llStatusBanner      = view.findViewById(R.id.llStatusBanner);
        llInlineError       = view.findViewById(R.id.llInlineError);
        tvInlineError       = view.findViewById(R.id.tvInlineError);

        btnCloseDialog     = view.findViewById(R.id.btnCloseDialog);
        btnCancelCheckout  = view.findViewById(R.id.btnCancelCheckout);
        btnConfirmCheckout = view.findViewById(R.id.btnConfirmCheckout);
        btnAddPayment      = view.findViewById(R.id.btnAddPayment);
        btnTradeIn         = view.findViewById(R.id.btnTradeIn);

        etCustomerName   = view.findViewById(R.id.etCustomerName);
        etCustomerPhone  = view.findViewById(R.id.etCustomerPhone);
        tilCustomerName  = view.findViewById(R.id.tilCustomerName);
        tilCustomerPhone = view.findViewById(R.id.tilCustomerPhone);
    }

    private void applyLanguage() {
        if (tvDialogTitle != null) tvDialogTitle.setText(isArabic ? "الدفع" : "Payment");
        if (tvItemCount != null) tvItemCount.setText(formatItemCount(itemCount));
        if (btnCancelCheckout != null) btnCancelCheckout.setText(isArabic ? "إلغاء" : "Cancel");
        if (btnConfirmCheckout != null) btnConfirmCheckout.setText(isArabic ? "تأكيد الدفع" : "Confirm");
        if (btnAddPayment != null) btnAddPayment.setText(isArabic ? "+ إضافة دفعة" : "+ Add Payment");
        if (btnTradeIn != null) btnTradeIn.setText(isArabic ? "دفع بالذهب" : "Pay with Gold");
    }

    private String formatItemCount(int count) {
        if (isArabic) {
            if (count == 0) return "0 قطعة";
            if (count == 1) return "قطعة واحدة";
            if (count == 2) return "قطعتان";
            if (count <= 10) return count + " قطع";
            return count + " قطعة";
        }
        return count + (count == 1 ? " item" : " items");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Data Loading
    // ═══════════════════════════════════════════════════════════════════

    private void loadPaymentMethods() {
        new Thread(() -> {
            List<PaymentMethodDto> loaded = null;
            try {
                MiraApiClient client = MiraApiClient.getInstance(requireContext());
                MiraApiClient.ApiResponse response = client.getPaymentMethods();

                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray arr = response.data.optJSONArray("data");
                    if (arr == null) arr = response.data.optJSONArray("methods");
                    if (arr == null) arr = response.data.optJSONArray("payment_methods");
                    if (arr == null) arr = response.data.optJSONArray("items");

                    if (arr != null && arr.length() > 0) {
                        Gson gson = new Gson();
                        loaded = new ArrayList<>();
                        for (int i = 0; i < arr.length(); i++) {
                            try {
                                PaymentMethodDto m = gson.fromJson(
                                        arr.getJSONObject(i).toString(), PaymentMethodDto.class);
                                if (m != null && m.code != null && !m.code.isEmpty()) {
                                    loaded.add(m);
                                }
                            } catch (Exception e) {
                                Log.w(TAG, "skip malformed method", e);
                            }
                        }
                        Log.i(TAG, "✅ Loaded " + loaded.size() + " payment methods from server");
                    }
                } else {
                    Log.w(TAG, "⚠️ getPaymentMethods failed: "
                            + (response != null ? response.error : "null"));
                }
            } catch (Exception e) {
                Log.e(TAG, "loadPaymentMethods error", e);
            }

            // ✅ الفلترة قبل تعيين finalList
            if (loaded != null && !loaded.isEmpty()) {
                final int before = loaded.size();
                loaded.removeIf(m -> m.code != null &&
                        (m.code.equalsIgnoreCase("TRADE_IN") ||
                         m.code.equalsIgnoreCase("CUSTOMER_CREDIT")));
                Log.i(TAG, "Filtered: " + before + " → " + loaded.size() + " methods");
            }

            final List<PaymentMethodDto> finalList = (loaded != null && !loaded.isEmpty())
                    ? loaded
                    : getFallbackMethods();

            mainHandler.post(() -> {
                paymentMethods.clear();
                paymentMethods.addAll(finalList);
                setupUI();
            });
        }).start();
    }

    private void loadCurrencies() {
        new Thread(() -> {
            Set<String> currencies = new LinkedHashSet<>();
            currencies.add("YER"); // دائماً موجودة

            try {
                MiraApiClient client = MiraApiClient.getInstance(requireContext());
                MiraApiClient.ApiResponse response = client.getExchangeRates();
                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray arr = response.data.optJSONArray("data");
                    if (arr == null) arr = response.data.optJSONArray("rates");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject r = arr.getJSONObject(i);
                            String from = r.optString("from_currency", "").toUpperCase(Locale.US);
                            if (!from.isEmpty()) currencies.add(from);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "loadCurrencies error", e);
            }

            // ضمان وجود USD و SAR على الأقل
            if (currencies.size() < 2) {
                currencies.add("USD");
                currencies.add("SAR");
            }

            final List<String> finalList = new ArrayList<>(currencies);
            mainHandler.post(() -> {
                CURRENCIES.clear();
                CURRENCIES.addAll(finalList);

                // حدّث كل السطور الموجودة بأسماء العملات الجديدة
                for (View row : paymentRows) {
                    MaterialAutoCompleteTextView actCurr =
                            row.findViewById(R.id.actCurrency);
                    if (actCurr != null) {
                        actCurr.setAdapter(new ArrayAdapter<>(requireContext(),
                                android.R.layout.simple_dropdown_item_1line, CURRENCIES));
                    }
                }
                Log.i(TAG, "✅ Loaded currencies: " + CURRENCIES);
            });
        }).start();
    }

    private List<PaymentMethodDto> getFallbackMethods() {
        List<PaymentMethodDto> list = new ArrayList<>();
        String[][] fallback = {
                {"CASH",            "نقداً",            "Cash",           "💵", "111001", "الصندوق الرئيسي", "Main Cash Box"},
                {"POS_MADA",        "شبكة مدى",         "POS Mada",       "💳", "112521", "شبكة مدى",       "Mada Network"},
                {"VISA_MASTER",     "فيزا/ماستر",       "Visa/Master",    "💳", "112521", "فيزا/ماستر",     "Visa/Mastercard"},
                {"BANK_TRANSFER",   "تحويل بنكي",       "Bank Transfer",  "🏦", "1102",   "البنك",          "Bank"},
                {"E_WALLET",        "محفظة إلكترونية",  "E-Wallet",       "📱", "1103",   "محفظة",          "Wallet"}
        };
        for (String[] f : fallback) {
            PaymentMethodDto m = new PaymentMethodDto();
            m.code = f[0];
            m.nameAr = f[1];
            m.nameEn = f[2];
            m.icon = f[3];
            m.accountCode = f[4];
            m.accountNameAr = f[5];
            m.accountName = f[6];
            list.add(m);
        }
        return list;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Setup UI
    // ═══════════════════════════════════════════════════════════════════

    private void setupUI() {
        updateFinalAmount();
        addPaymentRow("YER", 0.0);

        if (btnCloseDialog != null)     btnCloseDialog.setOnClickListener(v -> dismiss());
        if (btnCancelCheckout != null)  btnCancelCheckout.setOnClickListener(v -> dismiss());
        if (btnAddPayment != null)      btnAddPayment.setOnClickListener(v -> addPaymentRow("YER", 0.0));
        if (btnTradeIn != null)         btnTradeIn.setOnClickListener(v -> openTradeInDialog());
        if (btnConfirmCheckout != null) btnConfirmCheckout.setOnClickListener(v -> validateAndSubmit());
    }

    /**
     * إضافة سطر دفع جديد.
     * ✅ Critical: نخزن code الطريقة في actMethod.getTag() لضمان الدقة.
     */
    private void addPaymentRow(String defaultCurrency, double amount) {
        View row = getLayoutInflater().inflate(R.layout.item_payment_row, llPaymentsContainer, false);

        MaterialAutoCompleteTextView actMethod = row.findViewById(R.id.actMethod);
        MaterialAutoCompleteTextView actCurrency = row.findViewById(R.id.actCurrency);
        TextView tvRowNumber = row.findViewById(R.id.tvRowNumber);
        EditText etAmount = row.findViewById(R.id.etAmount);
        TextView tvBaseEquivalent = row.findViewById(R.id.tvBaseEquivalent);
        MaterialButton btnRemaining = row.findViewById(R.id.btnRemaining);
        ImageButton btnRemove = row.findViewById(R.id.btnRemove);

        if (tvRowNumber != null) {
            tvRowNumber.setText(String.valueOf(paymentRows.size() + 1));
        }

        // ✅ بناء قائمة الأسماء + خريطة display → code
        final Map<String, String> displayToCode = new LinkedHashMap<>();
        String[] methodNames = new String[paymentMethods.size()];
        for (int i = 0; i < paymentMethods.size(); i++) {
            PaymentMethodDto m = paymentMethods.get(i);
            String display = m.getDisplayName(isArabic);
            String account = m.getAccountDisplay(isArabic);
            String fullName = (account != null && !account.isEmpty())
                    ? display + " (" + account + ")"
                    : display;
            methodNames[i] = fullName;
            displayToCode.put(fullName, m.code != null ? m.code : "CASH");
        }

        actMethod.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_dropdown_item_1line, methodNames));

        if (methodNames.length > 0) {
            actMethod.setText(methodNames[0], false);
            actMethod.setTag(displayToCode.get(methodNames[0]));
        } else {
            actMethod.setTag("CASH");
        }

        // ✅ عند تغيير الطريقة: حدّث Tag
        actMethod.setOnItemClickListener((parent, view1, position, id) -> {
            String selected = (String) parent.getItemAtPosition(position);
            String code = displayToCode.get(selected);
            actMethod.setTag(code != null ? code : "CASH");
            Log.d(TAG, "Method selected: " + selected + " → code=" + actMethod.getTag());
        });

        // Currency
        actCurrency.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_dropdown_item_1line, CURRENCIES));
        actCurrency.setText(defaultCurrency, false);

        // Text watcher للأوزان
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                double amt = parseDouble(s.toString());
                double rate = getRateToYER(actCurrency.getText().toString());
                double base = amt * rate;
                if (rate != 1.0) {
                    tvBaseEquivalent.setVisibility(View.VISIBLE);
                    tvBaseEquivalent.setText(String.format(Locale.US, "≈ %,.2f YER", base));
                } else {
                    tvBaseEquivalent.setVisibility(View.GONE);
                }
                updateStatus();
            }
            @Override public void afterTextChanged(Editable s) {}
        };
        etAmount.addTextChangedListener(watcher);
        actCurrency.setOnItemClickListener((p, v, pos, id) ->
                watcher.onTextChanged(etAmount.getText(), 0, 0, 0));

        // زر المتبقي
        btnRemaining.setOnClickListener(v -> {
            double requiredTotal = currentTotalWithDiscountUsd * getRateToYER("USD");
            double currentRowAmount = parseDouble(etAmount.getText().toString())
                    * getRateToYER(actCurrency.getText().toString());
            double currentPaid = getTotalPaidYER() - currentRowAmount;
            double rem = requiredTotal - currentPaid - tradeInTotal;
            if (rem > 0.01) {
                double rate = getRateToYER(actCurrency.getText().toString());
                etAmount.setText(String.format(Locale.US, "%.2f", rem / rate));
            }
        });

        // زر الحذف
        btnRemove.setOnClickListener(v -> {
            if (paymentRows.size() > 1) {
                llPaymentsContainer.removeView(row);
                paymentRows.remove(row);
                renumberRows();
                updateStatus();
            } else {
                Toast.makeText(requireContext(), "يجب إبقاء سطر واحد", Toast.LENGTH_SHORT).show();
            }
        });

        if (amount > 0) {
            etAmount.setText(String.format(Locale.US, "%.2f", amount));
        }
        paymentRows.add(row);
        llPaymentsContainer.addView(row);
        updateStatus();
    }

    private void renumberRows() {
        for (int i = 0; i < paymentRows.size(); i++) {
            TextView tvNum = paymentRows.get(i).findViewById(R.id.tvRowNumber);
            if (tvNum != null) {
                tvNum.setText(String.valueOf(i + 1));
            }
        }
    }

    private double getTotalPaidYER() {
        double total = 0;
        for (View row : paymentRows) {
            EditText et = row.findViewById(R.id.etAmount);
            MaterialAutoCompleteTextView actCurr = row.findViewById(R.id.actCurrency);
            if (et == null || actCurr == null) continue;
            double amt = parseDouble(et.getText().toString());
            total += amt * getRateToYER(actCurr.getText().toString());
        }
        return total;
    }

    /**
     * تحديث حالة الدفع (متبقي/متطابق/رصيد) بدعم الوضع الداكن.
     */
    private void updateStatus() {
        if (tvRemaining == null || tvStatusIcon == null || llStatusBanner == null) return;

        double requiredTotal = currentTotalWithDiscountUsd * getRateToYER("USD");
        double paid = getTotalPaidYER();
        double totalWithGold = paid + tradeInTotal;
        double remaining = requiredTotal - totalWithGold;

        boolean isDark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        if (Math.abs(remaining) < 1) {
            tvStatusIcon.setText("✅");
            if (tvStatusLabel != null)
                tvStatusLabel.setText(isArabic ? "متطابق" : "Matched");
            tvRemaining.setText("0 YER");
            tvRemaining.setTextColor(Color.parseColor(isDark ? "#86EFAC" : "#16A34A"));
            llStatusBanner.setBackgroundColor(Color.parseColor(isDark ? "#14532D" : "#DCFCE7"));
        } else if (remaining > 0) {
            tvStatusIcon.setText("⚠️");
            if (tvStatusLabel != null)
                tvStatusLabel.setText(isArabic ? "المتبقي" : "Remaining");
            tvRemaining.setText(String.format(Locale.US, "%,.0f YER", remaining));
            tvRemaining.setTextColor(Color.parseColor(isDark ? "#FCA5A5" : "#DC2626"));
            llStatusBanner.setBackgroundColor(Color.parseColor(isDark ? "#7F1D1D" : "#FEE2E2"));
        } else {
            tvStatusIcon.setText("💰");
            if (tvStatusLabel != null)
                tvStatusLabel.setText(isArabic ? "سيُضاف رصيد دائن" : "Store Credit");
            tvRemaining.setText(String.format(Locale.US, "+%,.0f YER", Math.abs(remaining)));
            tvRemaining.setTextColor(Color.parseColor(isDark ? "#93C5FD" : "#2563EB"));
            llStatusBanner.setBackgroundColor(Color.parseColor(isDark ? "#1E3A8A" : "#DBEAFE"));
        }

        // إخفاء رسالة الخطأ عند أي تعديل
        if (llInlineError != null && llInlineError.getVisibility() == View.VISIBLE) {
            llInlineError.setVisibility(View.GONE);
        }
    }

    private void openTradeInDialog() {
        TradeInDialog dialog = TradeInDialog.newInstance(
                currentTotalWithDiscountUsd * getRateToYER("USD"));
        dialog.setOnTradeInListener((tradeIns, totalValue) -> {
            this.tradeInLines = tradeIns;
            this.tradeInTotal = totalValue;
            if (tvTradeInTotal != null) {
                tvTradeInTotal.setText(String.format(Locale.US, "%,.0f YER", totalValue));
            }
            if (llTradeInSummary != null) {
                llTradeInSummary.setVisibility(totalValue > 0 ? View.VISIBLE : View.GONE);
            }
            updateStatus();
        });
        dialog.show(getParentFragmentManager(), "TradeInDialog");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Validate & Submit
    // ═══════════════════════════════════════════════════════════════════

    private void validateAndSubmit() {
        double requiredTotal = currentTotalWithDiscountUsd * getRateToYER("USD");
        double paid = getTotalPaidYER();
        double totalWithGold = paid + tradeInTotal;
        double diff = totalWithGold - requiredTotal;

        // ❌ نقص
        if (diff < -1.0) {
            String msg = isArabic
                    ? "⚠️ المبلغ ناقص " + String.format(Locale.US, "%,.0f", Math.abs(diff)) + " YER"
                    : "⚠️ Short by " + String.format(Locale.US, "%,.0f", Math.abs(diff)) + " YER";
            showInlineError(msg);
            return;
        }

        // ✅ زيادة: يتطلب رقم هاتف + تأكيد
        if (diff > 1.0) {
            String phone = etCustomerPhone != null
                    ? etCustomerPhone.getText().toString().trim() : "";
            if (phone.isEmpty()) {
                if (tilCustomerPhone != null) {
                    tilCustomerPhone.setError(isArabic
                            ? "مطلوب لإنشاء رصيد دائن"
                            : "Required to create store credit");
                }
                showInlineError(isArabic
                        ? "⚠️ الدفع الزائد يتطلب رقم هاتف العميل (لحفظ الرصيد الدائن)"
                        : "⚠️ Overpayment requires customer phone (to save credit)");
                return;
            }

            final POSCheckoutData pendingData = buildData();
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(isArabic ? "💰 سيُضاف رصيد دائن" : "💰 Store Credit")
                    .setMessage(isArabic
                            ? "المبلغ المدفوع أكثر من الفاتورة بـ "
                              + String.format(Locale.US, "%,.2f", diff)
                              + " YER\n\nسيُحفظ هذا المبلغ كرصيد دائن للعميل (صالح 12 شهراً)."
                            : "Overpaid by " + String.format(Locale.US, "%,.2f", diff)
                              + " YER\n\nThis will be saved as store credit (valid 12 months).")
                    .setPositiveButton(isArabic ? "متابعة" : "Continue",
                            (d, w) -> dispatchCheckout(pendingData))
                    .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                    .show();
            return;
        }

        // ✅ متطابق
        dispatchCheckout(buildData());
    }

    private void showInlineError(String message) {
        if (llInlineError == null || tvInlineError == null) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
            return;
        }
        tvInlineError.setText(message);
        llInlineError.setVisibility(View.VISIBLE);
        llInlineError.requestFocus();
    }

    private POSCheckoutData buildData() {
        POSCheckoutData data = new POSCheckoutData();
        data.customerName = etCustomerName != null ? etCustomerName.getText().toString().trim() : "";
        data.customerPhone = etCustomerPhone != null ? etCustomerPhone.getText().toString().trim() : "";
        data.originalTotal = totalAmount;
        data.finalAmount = totalAmount;
        data.tradeIns = tradeInLines;
        data.tradeInTotal = tradeInTotal;

        JSONArray paymentsArray = new JSONArray();
        for (View row : paymentRows) {
            EditText et = row.findViewById(R.id.etAmount);
            MaterialAutoCompleteTextView actCurr = row.findViewById(R.id.actCurrency);
            MaterialAutoCompleteTextView actMethod = row.findViewById(R.id.actMethod);
            if (et == null || actCurr == null || actMethod == null) continue;

            double amt = parseDouble(et.getText().toString());
            if (amt <= 0) continue;

            String curr = actCurr.getText().toString();

            // ✅ اقرأ الكود من Tag (موثوق 100%)
            String methodCode = actMethod.getTag() != null
                    ? String.valueOf(actMethod.getTag())
                    : resolveMethodCodeFallback(actMethod);

            Log.d(TAG, "Payment row: currency=" + curr
                    + " amount=" + amt + " method=" + methodCode);

            try {
                JSONObject pObj = new JSONObject();
                pObj.put("currency", curr);
                pObj.put("amount", amt);
                pObj.put("exchange_rate", getRateToYER(curr));
                pObj.put("base_amount", amt * getRateToYER(curr));
                pObj.put("method", methodCode);
                paymentsArray.put(pObj);
            } catch (Exception ignored) {}
        }
        data.payments = paymentsArray;

        // paymentMethod الرئيسي (للـ legacy)
        if (paymentsArray.length() > 0) {
            try {
                data.paymentMethod = paymentsArray.getJSONObject(0).optString("method", "CASH");
            } catch (Exception ignored) {}
        }

        return data;
    }

    /**
     * Fallback فقط عند فقدان Tag (نادر) — يطابق النص العربي.
     */
    private String resolveMethodCodeFallback(MaterialAutoCompleteTextView actMethod) {
        if (actMethod == null || paymentMethods.isEmpty()) return "CASH";

        String selectedText = actMethod.getText() != null
                ? actMethod.getText().toString().trim() : "";
        if (selectedText.isEmpty()) return paymentMethods.get(0).code;

        for (PaymentMethodDto m : paymentMethods) {
            if (m == null) continue;
            String display = m.getDisplayName(isArabic);
            String account = m.getAccountDisplay(isArabic);
            String full = (account != null && !account.isEmpty())
                    ? display + " (" + account + ")"
                    : display;
            if (full.equals(selectedText)) {
                return m.code != null ? m.code : "CASH";
            }
        }
        return paymentMethods.get(0).code;
    }

    /**
     * ✅ لا تُغلق الديالوج — أبقها مفتوحة حتى يرد الـ Backend.
     *    الإغلاق يتم من onCheckoutSuccess() أو onCheckoutError().
     */
    private void dispatchCheckout(POSCheckoutData data) {
        if (btnConfirmCheckout != null) {
            btnConfirmCheckout.setEnabled(false);
            btnConfirmCheckout.setText(isArabic ? "⏳ جاري التنفيذ..." : "⏳ Processing...");
        }
        if (llInlineError != null) llInlineError.setVisibility(View.GONE);

        if (listener != null) listener.onCheckout(data);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Public callbacks (called by MiraPosFragment)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * يُستدعى عند نجاح الـ Backend.
     * الوظيفة: إغلاق الديالوج فقط (البيانات محفوظة في السلة أصلاً).
     */
    public void onCheckoutSuccess() {
        try {
            dismissAllowingStateLoss();
        } catch (Exception ignored) {}
    }

    /**
     * يُستدعى عند فشل الـ Backend.
     * الوظيفة: عرض الخطأ داخلياً + إعادة تفعيل زر التأكيد.
     * الإدخالات محفوظة (customer/trade-in/payment rows) — لا شيء يضيع.
     */
    public void onCheckoutError(String errorMessage) {
        try {
            if (btnConfirmCheckout != null) {
                btnConfirmCheckout.setEnabled(true);
                btnConfirmCheckout.setText(isArabic ? "تأكيد الدفع" : "Confirm");
            }

            String cleanMsg = (errorMessage != null && !errorMessage.isEmpty())
                    ? errorMessage
                    : (isArabic ? "خطأ غير معروف" : "Unknown error");

            if (llInlineError != null && tvInlineError != null) {
                tvInlineError.setText(cleanMsg);
                llInlineError.setVisibility(View.VISIBLE);
                llInlineError.requestFocus();
            } else {
                Toast.makeText(requireContext(), cleanMsg, Toast.LENGTH_LONG).show();
            }

            Log.w(TAG, "Backend rejected checkout: " + cleanMsg);

        } catch (Exception e) {
            Log.e(TAG, "onCheckoutError", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════

    private void updateFinalAmount() {
        currentTotalWithDiscountUsd = totalAmount;
        if (tvFinalTotal != null) {
            if (priceManager != null) {
                tvFinalTotal.setText(priceManager.formatPriceDefault(totalAmount));
            } else {
                tvFinalTotal.setText(String.format(Locale.US, "%,.2f YER", totalAmount));
            }
        }
    }

    private double getRateToYER(String currency) {
        if ("YER".equalsIgnoreCase(currency)) return 1.0;
        if (priceManager != null) {
            double yerRate = priceManager.getExchangeRate(MiraPriceManager.CURRENCY_YER);
            double currencyRate = priceManager.getExchangeRate(currency);
            if (currencyRate > 10) return currencyRate;
            if (yerRate > 0 && currencyRate > 0) return yerRate / currencyRate;
        }
        if ("SAR".equalsIgnoreCase(currency)) return 141.33;
        if ("USD".equalsIgnoreCase(currency)) return 530.0;
        return 1.0;
    }

    private double parseDouble(String s) {
        try { return Double.parseDouble(s.trim()); }
        catch (Exception e) { return 0; }
    }
}
