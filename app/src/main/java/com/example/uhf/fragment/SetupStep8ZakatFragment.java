package com.example.uhf.fragment;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
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
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * Step 8 — إعداد الزكاة السنوية (v2.1)
 *
 * ✅ v2.1:
 *  - التنقل عبر SetupNavigator (لا popBackStack)
 *  - ينتقل مباشرة للخطوة 9 (RFID)
 *  - null-safety للأزرار
 */
public class SetupStep8ZakatFragment extends BaseSetupStepFragment {

    private static final String TAG = "SetupStep8";

    private static final String[] METHODS = {"weight", "value", "both"};
    private static final String[] METHOD_LABELS_AR = {
        "بوزن الذهب فقط",
        "بالقيمة السوقية",
        "بالوزن والقيمة (الأدق)"
    };
    private static final String[] METHOD_LABELS_EN = {
        "By Gold Weight",
        "By Market Value",
        "By Weight & Value (recommended)"
    };

    // Defaults for 1447 AH
    private static final String DEFAULT_START = "2025-06-26";
    private static final String DEFAULT_END   = "2026-06-15";

    private TextInputEditText etHijriYear, etStartDate, etEndDate,
            etNisabGold, etZakatRate;
    private AutoCompleteTextView ddMethod;
    private MaterialSwitch swIncludeTrust, swIncludeReceivables, swDeductPayables;
    private MaterialButton btnBack, btnNext;
    private View pbLoading;

    private MiraApiClient apiClient;
    private boolean isArabic;
    private String startDate = DEFAULT_START;
    private String endDate   = DEFAULT_END;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_setup_step8_zakat, container, false);
        apiClient = MiraApiClient.getInstance(requireContext());
        isArabic = Locale.getDefault().getLanguage().equals("ar");

        if (savedInstanceState != null) {
            startDate = savedInstanceState.getString("start_date", DEFAULT_START);
            endDate   = savedInstanceState.getString("end_date", DEFAULT_END);
        }

        initViews(view);
        setupListeners();
        loadCurrentSettings();
        return view;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("start_date", startDate);
        outState.putString("end_date", endDate);
    }

    private void initViews(View v) {
        etHijriYear  = v.findViewById(R.id.etHijriYear);
        etStartDate  = v.findViewById(R.id.etStartDate);
        etEndDate    = v.findViewById(R.id.etEndDate);
        etNisabGold  = v.findViewById(R.id.etNisabGold);
        etZakatRate  = v.findViewById(R.id.etZakatRate);
        ddMethod     = v.findViewById(R.id.ddMethod);
        swIncludeTrust       = v.findViewById(R.id.swIncludeTrust);
        swIncludeReceivables = v.findViewById(R.id.swIncludeReceivables);
        swDeductPayables     = v.findViewById(R.id.swDeductPayables);
        btnBack      = v.findViewById(R.id.btnBack);
        btnNext      = v.findViewById(R.id.btnNext);
        pbLoading    = v.findViewById(R.id.pbLoading);

        // Defaults
        if (etHijriYear != null) etHijriYear.setText("1447");
        if (etStartDate != null) {
            etStartDate.setText(DEFAULT_START);
            etStartDate.setFocusable(false);
        }
        if (etEndDate != null) {
            etEndDate.setText(DEFAULT_END);
            etEndDate.setFocusable(false);
        }
        if (etNisabGold != null) etNisabGold.setText("85");
        if (etZakatRate != null) etZakatRate.setText("2.5");

        if (ddMethod != null) {
            String[] labels = isArabic ? METHOD_LABELS_AR : METHOD_LABELS_EN;
            ArrayAdapter<String> ad = new ArrayAdapter<>(
                    requireContext(), android.R.layout.simple_dropdown_item_1line, labels);
            ddMethod.setAdapter(ad);
            ddMethod.setText(labels[0], false);
        }
    }

    private void setupListeners() {
        if (etStartDate != null) {
            etStartDate.setOnClickListener(v -> pickDate(true));
        }
        if (etEndDate != null) {
            etEndDate.setOnClickListener(v -> pickDate(false));
        }
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> requireActivity().onBackPressed());
        }
        if (btnNext != null) {
            btnNext.setOnClickListener(v -> saveAndNext());
        }
    }

    private void pickDate(boolean isStart) {
        Calendar cal = Calendar.getInstance();
        try {
            String d = isStart ? startDate : endDate;
            java.util.Date parsed = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d);
            if (parsed != null) cal.setTime(parsed);
        } catch (Exception ignored) {}

        new DatePickerDialog(requireContext(),
                (view, year, month, day) -> {
                    String d = String.format(Locale.US, "%04d-%02d-%02d",
                            year, month + 1, day);
                    if (isStart) {
                        startDate = d;
                        if (etStartDate != null) etStartDate.setText(d);
                    } else {
                        endDate = d;
                        if (etEndDate != null) etEndDate.setText(d);
                    }
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void loadCurrentSettings() {
        showLoading(true);
        new Thread(() -> {
            try {
                MiraApiClient.ApiResponse resp = apiClient.getAccounting("setup/step/8/current");
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);

                    if (resp == null || !resp.isSuccess || resp.data == null) return;

                    JSONObject payload = resp.data.optJSONObject("data");
                    if (payload == null) payload = resp.data;
                    JSONObject settings = payload.optJSONObject("settings");
                    if (settings == null) return;

                    populateSettings(settings);
                });
            } catch (Exception e) {
                Log.e(TAG, "loadCurrentSettings", e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);
                });
            }
        }).start();
    }

    private void populateSettings(JSONObject s) {
        try {
            int hy = s.optInt("hijri_year", 0);
            if (hy > 0 && etHijriYear != null) etHijriYear.setText(String.valueOf(hy));

            String hs = s.optString("hijri_start_date", "");
            String he = s.optString("hijri_end_date", "");
            if (!hs.isEmpty() && etStartDate != null) {
                startDate = hs;
                etStartDate.setText(hs);
            }
            if (!he.isEmpty() && etEndDate != null) {
                endDate = he;
                etEndDate.setText(he);
            }

            double nisab = s.optDouble("nisab_gold_grams", 0);
            if (nisab > 0 && etNisabGold != null) etNisabGold.setText(formatInput(nisab));

            double rate = s.optDouble("zakat_rate", 0);
            if (rate > 0 && etZakatRate != null) etZakatRate.setText(formatInput(rate));

            String method = s.optString("nisab_method", "weight");
            String[] labels = isArabic ? METHOD_LABELS_AR : METHOD_LABELS_EN;
            for (int i = 0; i < METHODS.length; i++) {
                if (METHODS[i].equals(method) && ddMethod != null) {
                    ddMethod.setText(labels[i], false);
                    break;
                }
            }

            if (swIncludeTrust != null) {
                swIncludeTrust.setChecked(s.optInt("include_trust_gold", 0) == 1);
            }
            if (swIncludeReceivables != null) {
                swIncludeReceivables.setChecked(s.optInt("include_receivables", 1) == 1);
            }
            if (swDeductPayables != null) {
                swDeductPayables.setChecked(s.optInt("deduct_payables", 1) == 1);
            }
        } catch (Exception e) {
            Log.e(TAG, "populateSettings", e);
        }
    }

    private void saveAndNext() {
        try {
            int hijriYear = parseInt(etHijriYear);
            if (hijriYear < 1400 || hijriYear > 1600) {
                Toast.makeText(requireContext(),
                        isArabic ? "السنة الهجرية يجب أن تكون بين 1400 و 1600"
                                : "Hijri year must be 1400-1600",
                        Toast.LENGTH_LONG).show();
                return;
            }

            if (startDate.isEmpty() || endDate.isEmpty()) {
                Toast.makeText(requireContext(),
                        isArabic ? "أدخل تواريخ السنة الهجرية" : "Enter Hijri year dates",
                        Toast.LENGTH_LONG).show();
                return;
            }

            if (startDate.compareTo(endDate) >= 0) {
                Toast.makeText(requireContext(),
                        isArabic ? "تاريخ النهاية يجب أن يكون بعد البداية"
                                : "End date must be after start",
                        Toast.LENGTH_LONG).show();
                return;
            }

            double nisab = parseDouble(etNisabGold);
            if (nisab < 1 || nisab > 200) {
                Toast.makeText(requireContext(),
                        isArabic ? "النصاب يجب أن يكون بين 1 و 200 جرام"
                                : "Nisab must be 1-200 grams",
                        Toast.LENGTH_LONG).show();
                return;
            }

            double rate = parseDouble(etZakatRate);
            if (rate < 0.01 || rate > 10) {
                Toast.makeText(requireContext(),
                        isArabic ? "نسبة الزكاة يجب أن تكون بين 0.01% و 10%"
                                : "Zakat rate must be 0.01-10%",
                        Toast.LENGTH_LONG).show();
                return;
            }

            String methodLabel = ddMethod != null ? ddMethod.getText().toString() : METHOD_LABELS_AR[0];
            String[] labels = isArabic ? METHOD_LABELS_AR : METHOD_LABELS_EN;
            String method = "weight";
            for (int i = 0; i < labels.length; i++) {
                if (labels[i].equals(methodLabel)) { method = METHODS[i]; break; }
            }

            JSONObject payload = new JSONObject();
            payload.put("hijri_year", hijriYear);
            payload.put("hijri_start_date", startDate);
            payload.put("hijri_end_date", endDate);
            payload.put("nisab_gold_grams", nisab);
            payload.put("nisab_method", method);
            payload.put("zakat_rate", rate);
            payload.put("include_trust_gold", swIncludeTrust != null && swIncludeTrust.isChecked());
            payload.put("include_receivables", swIncludeReceivables != null && swIncludeReceivables.isChecked());
            payload.put("deduct_payables", swDeductPayables != null && swDeductPayables.isChecked());

            if (btnNext != null) {
                btnNext.setEnabled(false);
                btnNext.setText(isArabic ? "جاري الحفظ..." : "Saving...");
            }
            showLoading(true);

            new Thread(() -> {
                try {
                    MiraApiClient.ApiResponse resp = apiClient.postAccounting("setup/step/8", payload);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (!isAdded()) return;
                        showLoading(false);

                        if (btnNext != null) {
                            btnNext.setEnabled(true);
                            btnNext.setText(isArabic ? "التالي ←" : "Next →");
                        }

                        if (resp != null && resp.isSuccess) {
                            Toast.makeText(requireContext(),
                                    isArabic ? "✅ تم حفظ إعداد الزكاة" : "✅ Zakat settings saved",
                                    Toast.LENGTH_SHORT).show();

                            // ═══════════════════════════════════════════════
                            // ✅ الانتقال للخطوة 9 (RFID)
                            // ═══════════════════════════════════════════════
                            SetupNavigator.goToNextStep(SetupStep8ZakatFragment.this, 8);
                        } else {
                            String err = (resp != null && resp.error != null) ? resp.error : "unknown";
                            Toast.makeText(requireContext(), "❌ " + err, Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (Exception ex) {
                    Log.e(TAG, "save", ex);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (!isAdded()) return;
                        showLoading(false);
                        if (btnNext != null) {
                            btnNext.setEnabled(true);
                            btnNext.setText(isArabic ? "التالي ←" : "Next →");
                        }
                        Toast.makeText(requireContext(), "❌ " + ex.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            }).start();

        } catch (Exception e) {
            Log.e(TAG, "saveAndNext", e);
            Toast.makeText(requireContext(), "❌ " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showLoading(boolean show) {
        if (pbLoading != null) pbLoading.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private int parseInt(TextInputEditText et) {
        try {
            if (et == null || et.getText() == null) return 0;
            return Integer.parseInt(et.getText().toString().trim());
        } catch (Exception e) { return 0; }
    }

    private double parseDouble(TextInputEditText et) {
        try {
            if (et == null || et.getText() == null) return 0;
            String s = et.getText().toString().trim().replace(",", "");
            return s.isEmpty() ? 0 : Double.parseDouble(s);
        } catch (Exception e) { return 0; }
    }

    private String formatInput(double v) {
        if (v == Math.floor(v)) return String.format(Locale.US, "%.0f", v);
        return String.format(Locale.US, "%.2f", v);
    }
}
