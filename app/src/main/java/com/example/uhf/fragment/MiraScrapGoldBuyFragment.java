package com.example.uhf.fragment;

import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraPriceManager;
import com.example.uhf.tools.SessionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

public class MiraScrapGoldBuyFragment extends Fragment {

    private TextInputEditText etCustomerName, etCustomerPhone, etWeight, etMarketPrice, etDeduction;
    private AutoCompleteTextView ddKarat;
    private TextView tvTotalAmount, tvRemaining;
    private MaterialButton btnSavePurchase, btnAddPayment;
    private LinearLayout llPaymentsContainer;

    private String[] karats = {"24", "22", "21", "18", "14"};
    private final String[] CURRENCIES = {"YER", "SAR", "USD"};

    private final List<View> paymentRows = new ArrayList<>();
    private double totalGoldValue = 0;
    private MiraPriceManager priceManager;

    private List<JSONObject> availableMethods = new ArrayList<>();
    private boolean isMethodsLoaded = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_scrap_gold_buy, container, false);

        priceManager = MiraPriceManager.getInstance(requireContext());

        initViews(view);
        setupKaratDropdown();
        setupCalculations();

        loadPaymentMethods();

        btnSavePurchase.setOnClickListener(v -> savePurchase());
        btnAddPayment.setOnClickListener(v -> addPaymentRow("YER", 0.0));

        return view;
    }

    private void initViews(View view) {
        etCustomerName = view.findViewById(R.id.etCustomerName);
        etCustomerPhone = view.findViewById(R.id.etCustomerPhone);
        etWeight = view.findViewById(R.id.etWeight);
        etMarketPrice = view.findViewById(R.id.etMarketPrice);
        etDeduction = view.findViewById(R.id.etDeduction);
        ddKarat = view.findViewById(R.id.ddKarat);
        tvTotalAmount = view.findViewById(R.id.tvTotalAmount);
        tvRemaining = view.findViewById(R.id.tvRemaining);
        btnSavePurchase = view.findViewById(R.id.btnSavePurchase);
        btnAddPayment = view.findViewById(R.id.btnAddPayment);
        llPaymentsContainer = view.findViewById(R.id.llPaymentsContainer);
    }

    private void loadPaymentMethods() {
        new Thread(() -> {
            try {
                MiraApiClient api = MiraApiClient.getInstance(requireContext());
                MiraApiClient.ApiResponse response = api.getPaymentMethods();

                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray arr = response.data.optJSONArray("data");
                    if (arr == null) arr = response.data.optJSONArray("methods");

                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            availableMethods.add(arr.getJSONObject(i));
                        }
                    }
                }
            } catch (Exception ignored) {}

            if (availableMethods.isEmpty()) {
                try {
                    JSONObject cash = new JSONObject();
                    cash.put("code", "CASH"); cash.put("name_ar", "نقداً"); cash.put("icon", "💵");
                    JSONObject bank = new JSONObject();
                    bank.put("code", "BANK_TRANSFER"); bank.put("name_ar", "تحويل بنكي"); bank.put("icon", "🏦");
                    availableMethods.add(cash);
                    availableMethods.add(bank);
                } catch (Exception ignored) {}
            }

            isMethodsLoaded = true;

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> addPaymentRow("YER", 0.0));
            }
        }).start();
    }

    // ✅ يستخدم MaterialAutoCompleteTextView بدل Spinner
    private void setupMethodDropdown(MaterialAutoCompleteTextView actMethod) {
        if (actMethod == null) return;
        List<String> methodNames = new ArrayList<>();
        for (JSONObject obj : availableMethods) {
            String icon = obj.optString("icon", "💳");
            String name = obj.optString("name_ar", obj.optString("code", "نقداً"));
            methodNames.add(icon + " " + name);
        }
        String[] arr = methodNames.toArray(new String[0]);
        actMethod.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_dropdown_item_1line, arr));
        if (arr.length > 0) {
            actMethod.setText(arr[0], false);
        }
    }

    private void setupKaratDropdown() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_dropdown_item_1line, karats);
        ddKarat.setAdapter(adapter);
        ddKarat.setText("21", false);
        ddKarat.setOnItemClickListener((parent, view, position, id) -> calculateTotal());
    }

    private void setupCalculations() {
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { calculateTotal(); }
            @Override public void afterTextChanged(Editable s) {}
        };
        etWeight.addTextChangedListener(watcher);
        etMarketPrice.addTextChangedListener(watcher);
        etDeduction.addTextChangedListener(watcher);
    }

    private void calculateTotal() {
        try {
            double weight = getDoubleFromEditText(etWeight);
            double marketPrice = getDoubleFromEditText(etMarketPrice);
            double deduction = getDoubleFromEditText(etDeduction);

            if (weight > 0 && marketPrice > 0) {
                double totalBeforeDeduction = weight * marketPrice;
                double totalDeduction = weight * deduction;
                totalGoldValue = totalBeforeDeduction - totalDeduction;
                tvTotalAmount.setText(String.format(Locale.US, "%,.2f YER", totalGoldValue));
            } else {
                totalGoldValue = 0;
                tvTotalAmount.setText("0.00 YER");
            }
            updateRemainingBalance();
        } catch (Exception e) {
            tvTotalAmount.setText("0.00 YER");
        }
    }

    private double getRateToYER(String currency) {
        if ("YER".equalsIgnoreCase(currency)) return 1.0;

        if (priceManager != null) {
            double yerRate = priceManager.getExchangeRate(MiraPriceManager.CURRENCY_YER);
            double currencyRate = priceManager.getExchangeRate(currency);

            if (currencyRate > 10) return currencyRate;

            if (yerRate > 0 && currencyRate > 0) {
                return yerRate / currencyRate;
            }
        }

        if ("SAR".equalsIgnoreCase(currency)) return 141.33;
        if ("USD".equalsIgnoreCase(currency)) return 530.0;
        return 1.0;
    }

    // ✅ يستخدم actCurrency + actMethod
    private void addPaymentRow(String defaultCurrency, double amount) {
        View row = getLayoutInflater().inflate(R.layout.item_payment_row, llPaymentsContainer, false);
        AutoCompleteTextView actCurrency = row.findViewById(R.id.actCurrency);
        MaterialAutoCompleteTextView actMethod = row.findViewById(R.id.actMethod);
        TextInputEditText etAmount = row.findViewById(R.id.etAmount);
        TextView tvEquivalent = row.findViewById(R.id.tvBaseEquivalent);
        ImageButton btnRemove = row.findViewById(R.id.btnRemove);

        // إعداد عملة الدفع
        if (actCurrency != null) {
            actCurrency.setAdapter(new ArrayAdapter<>(requireContext(),
                    android.R.layout.simple_dropdown_item_1line, CURRENCIES));
            actCurrency.setText(defaultCurrency, false);
        }

        // إعداد طريقة الدفع
        setupMethodDropdown(actMethod);

        Runnable updateEquivalent = () -> {
            try {
                String curr = actCurrency != null
                        ? actCurrency.getText().toString().trim().toUpperCase() : "YER";
                if (curr.isEmpty()) curr = "YER";
                double rate = getRateToYER(curr);
                double amt = getDoubleFromEditText(etAmount);
                double base = amt * rate;
                if (tvEquivalent != null) {
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

        if (actCurrency != null) {
            actCurrency.setOnItemClickListener((parent, v, position, id) -> {
                updateEquivalent.run();
                updateRemainingBalance();
            });
        }

        if (btnRemove != null) {
            btnRemove.setOnClickListener(v -> {
                if (llPaymentsContainer.getChildCount() > 1) {
                    llPaymentsContainer.removeView(row);
                    paymentRows.remove(row);
                    updateRemainingBalance();
                } else {
                    Toast.makeText(requireContext(),
                            "يجب أن يبقى سطر دفع واحد على الأقل", Toast.LENGTH_SHORT).show();
                }
            });
        }

        paymentRows.add(row);
        llPaymentsContainer.addView(row);
        if (amount > 0) etAmount.setText(String.valueOf(amount));
        updateEquivalent.run();
    }

    // ✅ يقرأ من actCurrency
    private void updateRemainingBalance() {
        double totalPaidBase = 0;
        for (View row : paymentRows) {
            AutoCompleteTextView actCurrency = row.findViewById(R.id.actCurrency);
            TextInputEditText et = row.findViewById(R.id.etAmount);
            String curr = actCurrency != null
                    ? actCurrency.getText().toString().trim().toUpperCase() : "YER";
            if (curr.isEmpty()) curr = "YER";
            double rate = getRateToYER(curr);
            double amount = getDoubleFromEditText(et);
            totalPaidBase += (amount * rate);
        }

        double remaining = totalGoldValue - totalPaidBase;

        if (Math.abs(remaining) < 1) {
            tvRemaining.setText(String.format(Locale.US, "%,.2f YER", remaining));
            tvRemaining.setTextColor(Color.parseColor("#16A34A"));
        } else if (remaining > 0) {
            tvRemaining.setText(String.format(Locale.US, "%,.2f YER", remaining));
            tvRemaining.setTextColor(Color.parseColor("#DC2626"));
        } else {
            tvRemaining.setText(String.format(Locale.US, "+ %,.2f YER", Math.abs(remaining)));
            tvRemaining.setTextColor(Color.parseColor("#F59E0B"));
        }
    }

    // ✅ يستخدم actCurrency/actMethod
    private void setFormEnabled(boolean enabled) {
        etCustomerName.setEnabled(enabled);
        etCustomerPhone.setEnabled(enabled);
        etWeight.setEnabled(enabled);
        etMarketPrice.setEnabled(enabled);
        etDeduction.setEnabled(enabled);
        ddKarat.setEnabled(enabled);
        btnAddPayment.setEnabled(enabled);
        for (View row : paymentRows) {
            View curr = row.findViewById(R.id.actCurrency);
            View method = row.findViewById(R.id.actMethod);
            View amount = row.findViewById(R.id.etAmount);
            View remove = row.findViewById(R.id.btnRemove);
            if (curr != null) curr.setEnabled(enabled);
            if (method != null) method.setEnabled(enabled);
            if (amount != null) amount.setEnabled(enabled);
            if (remove != null) remove.setEnabled(enabled);
        }
    }

    // ✅ helper: رمز العملة المختارة
    private String getSelectedCurrency(View row) {
        AutoCompleteTextView actCurrency = row.findViewById(R.id.actCurrency);
        if (actCurrency == null) return "YER";
        String text = actCurrency.getText() != null
                ? actCurrency.getText().toString().trim().toUpperCase() : "";
        return text.isEmpty() ? "YER" : text;
    }

    // ✅ helper: كود طريقة الدفع المختارة
    private String getSelectedMethodCode(View row) {
        MaterialAutoCompleteTextView actMethod = row.findViewById(R.id.actMethod);
        if (actMethod == null) return "CASH";
        String selected = actMethod.getText() != null
                ? actMethod.getText().toString().trim() : "";
        if (selected.isEmpty()) return "CASH";

        for (JSONObject obj : availableMethods) {
            String icon = obj.optString("icon", "💳");
            String name = obj.optString("name_ar", obj.optString("code", "نقداً"));
            String display = icon + " " + name;
            if (display.equals(selected)) {
                return obj.optString("code", "CASH");
            }
        }
        return "CASH";
    }

    // ✅ يستخدم actCurrency/actMethod
    private void savePurchase() {
        double weight = getDoubleFromEditText(etWeight);
        double marketPrice = getDoubleFromEditText(etMarketPrice);
        if (weight <= 0 || marketPrice <= 0) {
            Toast.makeText(requireContext(), "الرجاء إدخال الوزن والسعر", Toast.LENGTH_SHORT).show();
            return;
        }

        JSONArray paymentsArray = new JSONArray();
        double totalPaidBase = 0;

        for (View row : paymentRows) {
            TextInputEditText et = row.findViewById(R.id.etAmount);
            double amount = getDoubleFromEditText(et);

            if (amount > 0) {
                try {
                    String curr = getSelectedCurrency(row);
                    double rate = getRateToYER(curr);
                    double base = amount * rate;
                    totalPaidBase += base;

                    JSONObject pObj = new JSONObject();
                    pObj.put("currency", curr);
                    pObj.put("amount", amount);
                    pObj.put("exchange_rate", rate);
                    pObj.put("base_amount", base);
                    pObj.put("method", getSelectedMethodCode(row));

                    paymentsArray.put(pObj);
                } catch (Exception e) { e.printStackTrace(); }
            }
        }

        if (Math.abs(totalPaidBase - totalGoldValue) > 1) {
            Toast.makeText(requireContext(),
                    "⚠️ إجمالي المدفوعات لا يطابق قيمة الذهب", Toast.LENGTH_LONG).show();
            return;
        }

        JSONObject payload = new JSONObject();
        try {
            payload.put("customer_name", etCustomerName.getText() != null
                    ? etCustomerName.getText().toString().trim() : "");
            payload.put("customer_phone", etCustomerPhone.getText() != null
                    ? etCustomerPhone.getText().toString().trim() : "");
            payload.put("gross_weight_grams", weight);
            payload.put("gold_karat", ddKarat.getText().toString() + "K");
            payload.put("market_price_per_gram", marketPrice);
            payload.put("deduction_per_gram", getDoubleFromEditText(etDeduction));
            payload.put("total_amount", totalGoldValue);
            payload.put("payments", paymentsArray);

            MiraBranchesManager branchesManager = MiraBranchesManager.getInstance(requireContext());
            payload.put("branch_id", branchesManager.getActiveBranchId());
            SessionManager sessionManager = new SessionManager(requireContext());
            payload.put("user_id", sessionManager.getUserId());
        } catch (Exception e) { e.printStackTrace(); }

        setFormEnabled(false);
        btnSavePurchase.setEnabled(false);
        btnSavePurchase.setText("⏳ جاري الحفظ...");

        new Thread(() -> {
            MiraApiClient api = MiraApiClient.getInstance(requireContext());
            MiraApiClient.ApiResponse response = api.saveScrapPurchase(payload);

            if (getActivity() == null || isDetached()) return;

            getActivity().runOnUiThread(() -> {
                setFormEnabled(true);
                btnSavePurchase.setEnabled(true);
                btnSavePurchase.setText(R.string.save_purchase);

                if (response.isSuccess && response.data != null) {
                    int purchaseId = response.data.optInt("purchase_id", 0);
                    String purchaseNumber = response.data.optString("purchase_number", "");
                    showSuccessDialog(purchaseId, purchaseNumber);
                } else {
                    String errorMsg = response.error != null ? response.error : "فشل الاتصال بالسيرفر";
                    Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void showSuccessDialog(int purchaseId, String purchaseNumber) {
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle("✅ تم الحفظ بنجاح")
            .setMessage("تم تسجيل عملية شراء الكسر بنجاح.\n\nرقم العملية: " + purchaseNumber
                    + "\n\nهل تريد طباعة الإيصال للعميل الآن؟")
            .setPositiveButton("🖨️ عرض وطباعة الإيصال", (dialog, which) -> {
                if (purchaseId > 0) {
                    MiraScrapReceiptFragment receipt = MiraScrapReceiptFragment.newInstance(purchaseId, null);
                    receipt.show(getParentFragmentManager(), "ScrapReceipt");
                }
                clearFields();
            })
            .setNegativeButton("إغلاق", (dialog, which) -> clearFields())
            .setCancelable(false)
            .show();
    }

    private double getDoubleFromEditText(TextInputEditText editText) {
        if (editText == null || editText.getText() == null) return 0.0;
        String text = editText.getText().toString().trim();
        if (text.isEmpty()) return 0.0;
        try { return Double.parseDouble(text); } catch (NumberFormatException e) { return 0.0; }
    }

    private void clearFields() {
        etWeight.setText("");
        etMarketPrice.setText("");
        etDeduction.setText("");
        etCustomerName.setText("");
        etCustomerPhone.setText("");
        tvTotalAmount.setText("0.00 YER");

        llPaymentsContainer.removeAllViews();
        paymentRows.clear();
        addPaymentRow("YER", 0.0);
        updateRemainingBalance();
    }
}
