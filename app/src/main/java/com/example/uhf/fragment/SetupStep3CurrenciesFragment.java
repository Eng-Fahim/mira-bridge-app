package com.example.uhf.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * المرحلة 3: العملات وأسعار الصرف (v2.0)
 *
 * ✅ v2.0:
 *  - التنقل عبر SetupNavigator (لا FragmentTransaction يدوي)
 *  - ينتقل مباشرة للخطوة 4 (أسعار الذهب)
 *  - null-safety للأزرار
 */
public class SetupStep3CurrenciesFragment extends BaseSetupStepFragment {

    private static final String TAG = "SetupStep3";

    private AutoCompleteTextView spBaseCurrency;
    private TextInputEditText etUsdYer;
    private TextInputEditText etSarYer;
    private TextInputEditText etUsdSar;
    private MaterialButton btnBack;
    private MaterialButton btnNext;

    private MiraApiClient apiClient;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_setup_step3_currencies, container, false);

        apiClient = MiraApiClient.getInstance(requireContext());

        initViews(view);
        setupListeners();

        return view;
    }

    private void initViews(View v) {
        spBaseCurrency = v.findViewById(R.id.spBaseCurrency);
        etUsdYer = v.findViewById(R.id.etUsdYer);
        etSarYer = v.findViewById(R.id.etSarYer);
        etUsdSar = v.findViewById(R.id.etUsdSar);
        btnBack = v.findViewById(R.id.btnBack);
        btnNext = v.findViewById(R.id.btnNext);

        // Base currency: YER
        String[] currencies = {"YER", "USD", "SAR"};
        if (spBaseCurrency != null) {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    requireContext(),
                    android.R.layout.simple_dropdown_item_1line,
                    currencies
            );
            spBaseCurrency.setAdapter(adapter);
            spBaseCurrency.setText("YER", false);
        }

        // قيم افتراضية
        if (etUsdYer != null) etUsdYer.setText("535");
        if (etSarYer != null) etSarYer.setText("142.5");
        if (etUsdSar != null) etUsdSar.setText("3.75");
    }

    private void setupListeners() {
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> requireActivity().onBackPressed());
        }
        if (btnNext != null) {
            btnNext.setOnClickListener(v -> saveAndNext());
        }
    }

    private void saveAndNext() {
        String baseCurrency = spBaseCurrency != null
                ? spBaseCurrency.getText().toString().trim()
                : "YER";

        double usdYer = parseDouble(safeText(etUsdYer), 535);
        double sarYer = parseDouble(safeText(etSarYer), 142.5);
        double usdSar = parseDouble(safeText(etUsdSar), 3.75);

        if (btnNext != null) {
            btnNext.setEnabled(false);
            btnNext.setText("جاري الحفظ...");
        }

        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("base_currency", baseCurrency);

                JSONArray rates = new JSONArray();

                JSONObject usdYerRate = new JSONObject();
                usdYerRate.put("from", "USD");
                usdYerRate.put("to", "YER");
                usdYerRate.put("rate", usdYer);
                usdYerRate.put("type", "parallel");
                rates.put(usdYerRate);

                JSONObject sarYerRate = new JSONObject();
                sarYerRate.put("from", "SAR");
                sarYerRate.put("to", "YER");
                sarYerRate.put("rate", sarYer);
                sarYerRate.put("type", "parallel");
                rates.put(sarYerRate);

                JSONObject usdSarRate = new JSONObject();
                usdSarRate.put("from", "USD");
                usdSarRate.put("to", "SAR");
                usdSarRate.put("rate", usdSar);
                usdSarRate.put("type", "parallel");
                rates.put(usdSarRate);

                payload.put("rates", rates);

                MiraApiClient.ApiResponse response =
                        apiClient.postAccounting("setup/step/3", payload);

                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;

                    if (btnNext != null) {
                        btnNext.setEnabled(true);
                        btnNext.setText("التالي ←");
                    }

                    if (response != null && response.isSuccess) {
                        Toast.makeText(requireContext(),
                                "✅ تم حفظ العملات وأسعار الصرف",
                                Toast.LENGTH_SHORT).show();

                        // ═══════════════════════════════════════════════
                        // ✅ الانتقال للخطوة 4 (أسعار الذهب)
                        // ═══════════════════════════════════════════════
                        SetupNavigator.goToNextStep(SetupStep3CurrenciesFragment.this, 3);
                    } else {
                        String error = (response != null && response.error != null)
                                ? response.error : "غير معروف";
                        Toast.makeText(requireContext(),
                                "❌ فشل: " + error, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "save error", e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    if (btnNext != null) {
                        btnNext.setEnabled(true);
                        btnNext.setText("التالي ←");
                    }
                    Toast.makeText(requireContext(),
                            "❌ " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private String safeText(TextInputEditText et) {
        if (et == null || et.getText() == null) return "";
        return et.getText().toString().trim();
    }

    private double parseDouble(String str, double def) {
        try {
            if (str == null || str.isEmpty()) return def;
            return Double.parseDouble(str.replace(",", ""));
        } catch (Exception e) {
            return def;
        }
    }
}
