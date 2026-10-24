package com.example.uhf.fragment;

import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraBranchesManager;
import com.example.uhf.manager.MiraPriceManager; // ✅
import com.example.uhf.tools.SessionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
    private MiraPriceManager priceManager; // ✅

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_scrap_gold_buy, container, false);

        priceManager = MiraPriceManager.getInstance(requireContext());

        initViews(view);
        setupKaratDropdown();
        setupCalculations();
        
        addPaymentRow("YER", 0.0);

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

    private void setupKaratDropdown() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_dropdown_item_1line, karats);
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

    // ✅ دالة جلب سعر الصرف الصحيحة (من أي عملة إلى الريال اليمني YER)
    private double getRateToYER(String currency) {
        if (priceManager != null) {
            double yerRate = priceManager.getExchangeRate(MiraPriceManager.CURRENCY_YER);
            double currencyRate = priceManager.getExchangeRate(currency);
            
            // بما أن priceManager يعيد السعر مقابل الدولار،
            // فإن سعر الصرف من العملة إلى اليمني = سعر اليمني / سعر العملة
            if (yerRate > 0 && currencyRate > 0) {
                return yerRate / currencyRate;
            }
        }
        
        // احتياطي
        if ("SAR".equalsIgnoreCase(currency)) return 141.33;
        if ("USD".equalsIgnoreCase(currency)) return 530.0;
        return 1.0; // YER
    }

    private void addPaymentRow(String defaultCurrency, double amount) {
        View row = getLayoutInflater().inflate(R.layout.item_payment_row, llPaymentsContainer, false);
        Spinner spCurrency = row.findViewById(R.id.spCurrency);
        TextInputEditText etAmount = row.findViewById(R.id.etAmount);
        TextView tvEquivalent = row.findViewById(R.id.tvBaseEquivalent);
        ImageButton btnRemove = row.findViewById(R.id.btnRemove);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, CURRENCIES);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spCurrency.setAdapter(adapter);
        
        int index = Arrays.asList(CURRENCIES).indexOf(defaultCurrency);
        spCurrency.setSelection(index >= 0 ? index : 0);

        Runnable updateEquivalent = () -> {
            try {
                int pos = spCurrency.getSelectedItemPosition();
                if (pos >= 0) {
                    String curr = CURRENCIES[pos];
                    double rate = getRateToYER(curr);
                    double amt = getDoubleFromEditText(etAmount);
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
                Toast.makeText(requireContext(), "يجب أن يبقى سطر دفع واحد على الأقل", Toast.LENGTH_SHORT).show();
            }
        });

        paymentRows.add(row);
        llPaymentsContainer.addView(row);
        if (amount > 0) etAmount.setText(String.valueOf(amount));
        
        updateEquivalent.run();
    }

    private void updateRemainingBalance() {
        double totalPaidBase = 0;
        for (View row : paymentRows) {
            Spinner sp = row.findViewById(R.id.spCurrency);
            TextInputEditText et = row.findViewById(R.id.etAmount);
            int pos = sp.getSelectedItemPosition();
            if (pos >= 0) {
                String curr = CURRENCIES[pos];
                double rate = getRateToYER(curr);
                double amount = getDoubleFromEditText(et);
                totalPaidBase += (amount * rate);
            }
        }
        
        double remaining = totalGoldValue - totalPaidBase;
        
        if (Math.abs(remaining) < 1) {
            tvRemaining.setText(String.format(Locale.US, "%,.2f YER", remaining));
            tvRemaining.setTextColor(Color.parseColor("#16A34A")); // أخضر
        } else if (remaining > 0) {
            tvRemaining.setText(String.format(Locale.US, "%,.2f YER", remaining));
            tvRemaining.setTextColor(Color.parseColor("#DC2626")); // أحمر
        } else {
            tvRemaining.setText(String.format(Locale.US, "+ %,.2f YER", Math.abs(remaining)));
            tvRemaining.setTextColor(Color.parseColor("#F59E0B")); // برتقالي
        }
    }

    private void setFormEnabled(boolean enabled) {
        etCustomerName.setEnabled(enabled);
        etCustomerPhone.setEnabled(enabled);
        etWeight.setEnabled(enabled);
        etMarketPrice.setEnabled(enabled);
        etDeduction.setEnabled(enabled);
        ddKarat.setEnabled(enabled);
        btnAddPayment.setEnabled(enabled);
        for (View row : paymentRows) {
            row.findViewById(R.id.spCurrency).setEnabled(enabled);
            row.findViewById(R.id.etAmount).setEnabled(enabled);
            row.findViewById(R.id.btnRemove).setEnabled(enabled);
        }
    }

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
            Spinner sp = row.findViewById(R.id.spCurrency);
            TextInputEditText et = row.findViewById(R.id.etAmount);
            int pos = sp.getSelectedItemPosition();
            double amount = getDoubleFromEditText(et);
            
            if (amount > 0 && pos >= 0) {
                try {
                    String curr = CURRENCIES[pos];
                    double rate = getRateToYER(curr);
                    double base = amount * rate;
                    totalPaidBase += base;
                    
                    JSONObject pObj = new JSONObject();
                    pObj.put("currency", curr);
                    pObj.put("amount", amount);
                    pObj.put("exchange_rate", rate);
                    pObj.put("base_amount", base);
                    paymentsArray.put(pObj);
                } catch (Exception e) { e.printStackTrace(); }
            }
        }

        if (Math.abs(totalPaidBase - totalGoldValue) > 1) {
            Toast.makeText(requireContext(), "⚠️ إجمالي المدفوعات لا يطابق قيمة الذهب", Toast.LENGTH_LONG).show();
            return;
        }

        JSONObject payload = new JSONObject();
        try {
            payload.put("customer_name", etCustomerName.getText() != null ? etCustomerName.getText().toString().trim() : "");
            payload.put("customer_phone", etCustomerPhone.getText() != null ? etCustomerPhone.getText().toString().trim() : "");
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
            .setMessage("تم تسجيل عملية شراء الكسر بنجاح.\n\nرقم العملية: " + purchaseNumber + "\n\nهل تريد طباعة الإيصال للعميل الآن؟")
            .setPositiveButton("🖨️ عرض وطباعة الإيصال", (dialog, which) -> {
                if (purchaseId > 0) {
                    MiraScrapReceiptFragment receipt = MiraScrapReceiptFragment.newInstance(purchaseId, null);
                    receipt.show(getParentFragmentManager(), "ScrapReceipt");
                }
                clearFields();
            })
            .setNegativeButton("إغلاق", (dialog, which) -> {
                clearFields();
            })
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
