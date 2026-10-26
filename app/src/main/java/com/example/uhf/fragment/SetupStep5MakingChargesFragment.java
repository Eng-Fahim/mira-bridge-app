package com.example.uhf.fragment;

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
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Step 5 — أجور الصياغة لكل فئة وعيار (v2.1)
 *
 * ✅ v2.1:
 *  - التنقل عبر SetupNavigator (لا popBackStack)
 *  - ينتقل مباشرة للخطوة 6 (الأرصدة الافتتاحية)
 *  - null-safety للأزرار والـ containers
 */
public class SetupStep5MakingChargesFragment extends BaseSetupStepFragment {

    private static final String TAG = "SetupStep5";

    private static final String[] KARAT_OPTIONS = {"كل العيارات", "24K", "22K", "21K", "18K", "14K"};
    private static final String[] TYPE_OPTIONS_AR = {
        "لكل جرام (مبلغ)",
        "نسبة من قيمة الذهب %",
        "مبلغ لكل قطعة"
    };
    private static final String[] TYPE_OPTIONS_EN = {
        "Fixed per gram (amount)",
        "Percent of gold value %",
        "Fixed per piece"
    };
    private static final String[] TYPE_VALUES = {
        "fixed_per_gram", "percent_of_gold", "fixed_per_piece"
    };

    private static final String[][] DEFAULTS_AR = {
        // category, karat (null = all), charge_value, min_charge
        {"سبائك",      null,   "500",  "500"},
        {"خواتم",      "21K",  "2500", "5000"},
        {"خواتم",      "18K",  "3500", "7000"},
        {"أساور",      "21K",  "2000", "8000"},
        {"عقود",       "21K",  "3000", "10000"},
        {"أقراط",      "21K",  "2200", "4000"},
        {"قلائد",      "21K",  "2800", "9000"},
        {"أطقم",       "21K",  "3200", "15000"},
    };

    private LinearLayout container;
    private MaterialButton btnAdd, btnBack, btnNext;
    private View pbLoading;
    private TextView tvSummary;

    private final List<Row> rows = new ArrayList<>();
    private MiraApiClient apiClient;
    private boolean isArabic;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup viewGroup,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_setup_step5_making_charges, viewGroup, false);
        apiClient = MiraApiClient.getInstance(requireContext());
        isArabic = Locale.getDefault().getLanguage().equals("ar");

        initViews(view);
        setupListeners();
        loadCurrentCharges();
        return view;
    }

    private void initViews(View v) {
        container = v.findViewById(R.id.chargesContainer);
        btnAdd    = v.findViewById(R.id.btnAdd);
        btnBack   = v.findViewById(R.id.btnBack);
        btnNext   = v.findViewById(R.id.btnNext);
        pbLoading = v.findViewById(R.id.pbLoading);
        tvSummary = v.findViewById(R.id.tvSummary);
    }

    private void setupListeners() {
        if (btnAdd != null) {
            btnAdd.setOnClickListener(v -> addRow("", null, "fixed_per_gram", "", ""));
        }
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> requireActivity().onBackPressed());
        }
        if (btnNext != null) {
            btnNext.setOnClickListener(v -> saveAndNext());
        }
    }

    private void loadCurrentCharges() {
        showLoading(true);
        new Thread(() -> {
            try {
                MiraApiClient.ApiResponse resp = apiClient.getAccounting("setup/step/5/current");
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);
                    if (container == null) return;

                    JSONArray charges = null;
                    if (resp != null && resp.isSuccess && resp.data != null) {
                        JSONObject payload = resp.data.optJSONObject("data");
                        if (payload == null) payload = resp.data;
                        charges = payload.optJSONArray("charges");
                    }

                    container.removeAllViews();
                    rows.clear();

                    if (charges != null && charges.length() > 0) {
                        for (int i = 0; i < charges.length(); i++) {
                            JSONObject c = charges.optJSONObject(i);
                            if (c == null) continue;
                            String cat = c.optString("category", "");
                            String karat = c.optString("karat", null);
                            if (karat == null || "null".equals(karat) || karat.isEmpty()) karat = null;
                            String type = c.optString("charge_type", "fixed_per_gram");
                            String val = fmtInput(c.optDouble("charge_value", 0));
                            String min = fmtInput(c.optDouble("min_charge", 0));
                            addRow(cat, karat, type, val, min);
                        }
                    } else {
                        for (String[] d : DEFAULTS_AR) {
                            addRow(d[0], d[1], "fixed_per_gram", d[2], d[3]);
                        }
                    }
                    updateSummary();
                });
            } catch (Exception e) {
                Log.e(TAG, "loadCurrentCharges", e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);
                    if (container == null) return;
                    for (String[] d : DEFAULTS_AR) {
                        addRow(d[0], d[1], "fixed_per_gram", d[2], d[3]);
                    }
                    updateSummary();
                });
            }
        }).start();
    }

    private void addRow(String category, String karat, String type,
                        String value, String minCharge) {
        if (container == null) return;

        View row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_setup_making_charge, container, false);

        TextInputEditText etCategory = row.findViewById(R.id.etCategory);
        AutoCompleteTextView ddKarat = row.findViewById(R.id.ddKarat);
        AutoCompleteTextView ddType  = row.findViewById(R.id.ddType);
        TextInputEditText etValue    = row.findViewById(R.id.etValue);
        TextInputEditText etMin      = row.findViewById(R.id.etMinCharge);
        ImageButton btnRemove        = row.findViewById(R.id.btnRemove);

        // Karat dropdown
        ArrayAdapter<String> karatAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                KARAT_OPTIONS);
        ddKarat.setAdapter(karatAdapter);
        if (karat == null) {
            ddKarat.setText(KARAT_OPTIONS[0], false);
        } else {
            ddKarat.setText(karat, false);
        }

        // Type dropdown
        String[] typeLabels = isArabic ? TYPE_OPTIONS_AR : TYPE_OPTIONS_EN;
        ArrayAdapter<String> typeAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                typeLabels);
        ddType.setAdapter(typeAdapter);
        int idx = 0;
        for (int i = 0; i < TYPE_VALUES.length; i++) {
            if (TYPE_VALUES[i].equals(type)) { idx = i; break; }
        }
        ddType.setText(typeLabels[idx], false);

        etCategory.setText(category);
        etValue.setText(value);
        etMin.setText(minCharge);

        Row r = new Row(etCategory, ddKarat, ddType, etValue, etMin);
        rows.add(r);

        TextWatcher w = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { updateSummary(); }
        };
        etCategory.addTextChangedListener(w);
        etValue.addTextChangedListener(w);
        etMin.addTextChangedListener(w);

        btnRemove.setOnClickListener(v -> {
            if (rows.size() <= 1) {
                Toast.makeText(requireContext(),
                        isArabic ? "يجب أن تبقى فئة واحدة على الأقل" : "Keep at least one category",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            container.removeView(row);
            rows.remove(r);
            updateSummary();
        });

        container.addView(row);
    }

    private void saveAndNext() {
        try {
            JSONArray chargesArray = new JSONArray();
            int validCount = 0;

            for (Row r : rows) {
                String category = r.etCategory.getText() != null
                        ? r.etCategory.getText().toString().trim() : "";
                if (category.isEmpty()) continue;

                String karat = r.ddKarat.getText().toString().trim();
                if ("كل العيارات".equals(karat) || karat.isEmpty()) {
                    karat = null;
                }

                String typeLabel = r.ddType.getText().toString().trim();
                String typeValue = "fixed_per_gram";
                String[] typeLabels = isArabic ? TYPE_OPTIONS_AR : TYPE_OPTIONS_EN;
                for (int i = 0; i < typeLabels.length; i++) {
                    if (typeLabels[i].equals(typeLabel)) {
                        typeValue = TYPE_VALUES[i];
                        break;
                    }
                }

                double value = parseDouble(r.etValue);
                double min   = parseDouble(r.etMin);

                if (value <= 0) {
                    Toast.makeText(requireContext(),
                            isArabic ? "أدخل قيمة أجرة أكبر من صفر للفئة " + category
                                     : "Enter charge value > 0 for " + category,
                            Toast.LENGTH_LONG).show();
                    return;
                }

                if ("percent_of_gold".equals(typeValue) && (value <= 0 || value > 100)) {
                    Toast.makeText(requireContext(),
                            isArabic ? "النسبة يجب أن تكون بين 0 و 100 للفئة " + category
                                     : "Percent must be 0-100 for " + category,
                            Toast.LENGTH_LONG).show();
                    return;
                }

                JSONObject c = new JSONObject();
                c.put("category", category);
                if (karat != null) c.put("karat", karat);
                c.put("charge_type", typeValue);
                c.put("charge_value", value);
                c.put("min_charge", min);
                chargesArray.put(c);
                validCount++;
            }

            if (validCount == 0) {
                Toast.makeText(requireContext(),
                        isArabic ? "أدخل فئة واحدة على الأقل" : "Add at least one category",
                        Toast.LENGTH_LONG).show();
                return;
            }

            JSONObject payload = new JSONObject();
            payload.put("charges", chargesArray);
            payload.put("branch_id", 1);

            if (btnNext != null) {
                btnNext.setEnabled(false);
                btnNext.setText(isArabic ? "جاري الحفظ..." : "Saving...");
            }
            showLoading(true);

            new Thread(() -> {
                try {
                    MiraApiClient.ApiResponse resp = apiClient.postAccounting("setup/step/5", payload);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (!isAdded()) return;
                        showLoading(false);

                        if (btnNext != null) {
                            btnNext.setEnabled(true);
                            btnNext.setText(isArabic ? "التالي ←" : "Next →");
                        }

                        if (resp != null && resp.isSuccess) {
                            Toast.makeText(requireContext(),
                                    isArabic ? "✅ تم حفظ أجور الصياغة" : "✅ Making charges saved",
                                    Toast.LENGTH_SHORT).show();

                            // ═══════════════════════════════════════════════
                            // ✅ الانتقال للخطوة 6 (الأرصدة الافتتاحية)
                            // ═══════════════════════════════════════════════
                            SetupNavigator.goToNextStep(SetupStep5MakingChargesFragment.this, 5);
                        } else {
                            String err = (resp != null && resp.error != null) ? resp.error : "unknown";
                            Toast.makeText(requireContext(), "❌ " + err, Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (Exception ex) {
                    Log.e(TAG, "saveAndNext", ex);
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
            Log.e(TAG, "buildPayload", e);
            Toast.makeText(requireContext(), "❌ " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void updateSummary() {
        if (tvSummary == null) return;

        int valid = 0;
        for (Row r : rows) {
            String cat = r.etCategory.getText() != null
                    ? r.etCategory.getText().toString().trim() : "";
            double val = parseDouble(r.etValue);
            if (!cat.isEmpty() && val > 0) valid++;
        }
        tvSummary.setText(String.format(Locale.US,
                isArabic ? "%d فئة جاهزة للحفظ" : "%d categories ready", valid));
    }

    private void showLoading(boolean show) {
        if (pbLoading != null) pbLoading.setVisibility(show ? View.VISIBLE : View.GONE);
        if (container != null) container.setAlpha(show ? 0.5f : 1.0f);
    }

    private double parseDouble(TextInputEditText et) {
        try {
            if (et == null || et.getText() == null) return 0;
            String s = et.getText().toString().trim().replace(",", "");
            return s.isEmpty() ? 0 : Double.parseDouble(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private String fmtInput(double v) {
        if (v == 0) return "";
        if (v == Math.floor(v)) return String.format(Locale.US, "%.0f", v);
        return String.format(Locale.US, "%.2f", v);
    }

    private static class Row {
        final TextInputEditText etCategory, etValue, etMin;
        final AutoCompleteTextView ddKarat, ddType;

        Row(TextInputEditText etCategory, AutoCompleteTextView ddKarat,
            AutoCompleteTextView ddType, TextInputEditText etValue, TextInputEditText etMin) {
            this.etCategory = etCategory;
            this.ddKarat    = ddKarat;
            this.ddType     = ddType;
            this.etValue    = etValue;
            this.etMin      = etMin;
        }
    }
}
