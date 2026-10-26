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
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Step 9 — قواعد تكويد القطع (RFID + GS1 + GTIN)
 *
 * - GS1 Company Prefix (6-14 رقم)
 * - صيغة GTIN (13/14/custom)
 * - مخطط EPC (SGTIN-96/198، GID-96)
 * - بادئة HEX لـ EPC
 * - صيغة المسلسل (يجب أن تحتوي {seq})
 * - طول المسلسل (3-12)
 * - طول مرجع القطعة (1-10)
 * - خيارات متقدمة: فرع في EPC، شرائح مبرمجة مسبقاً
 */
public class SetupStep9RfidFragment extends BaseSetupStepFragment {

    private static final String TAG = "SetupStep9";

    private static final String[] GTIN_FORMATS      = {"gtin13", "gtin14", "custom"};
    private static final String[] GTIN_LABELS_AR    = {"GTIN-13 (13 رقم)", "GTIN-14 (14 رقم)", "مخصص"};
    private static final String[] GTIN_LABELS_EN    = {"GTIN-13 (13 digits)", "GTIN-14 (14 digits)", "Custom"};

    private static final String[] EPC_SCHEMES       = {"sgtin-96", "sgtin-198", "gid-96", "custom"};
    private static final String[] EPC_LABELS_AR     = {"SGTIN-96 (الأشيع)", "SGTIN-198 (طويل)", "GID-96 (عام)", "مخصص"};
    private static final String[] EPC_LABELS_EN     = {"SGTIN-96 (recommended)", "SGTIN-198 (extended)", "GID-96 (generic)", "Custom"};

    private TextInputEditText etGs1Prefix, etEpcHeader, etSerialFormat,
            etSerialLength, etItemRefLength;
    private MaterialAutoCompleteTextView ddGtinFormat, ddEpcScheme;
    private MaterialSwitch swIncludeBranch, swPreEncoded;
    private TextView tvPreview;
    private MaterialButton btnBack, btnNext;
    private View pbLoading;

    private MiraApiClient apiClient;
    private boolean isArabic;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_setup_step9_rfid, container, false);
        apiClient = MiraApiClient.getInstance(requireContext());
        isArabic = Locale.getDefault().getLanguage().equals("ar");

        initViews(view);
        setupListeners();
        loadCurrentRules();
        return view;
    }

    private void initViews(View v) {
        etGs1Prefix     = v.findViewById(R.id.etGs1Prefix);
        etEpcHeader     = v.findViewById(R.id.etEpcHeader);
        etSerialFormat  = v.findViewById(R.id.etSerialFormat);
        etSerialLength  = v.findViewById(R.id.etSerialLength);
        etItemRefLength = v.findViewById(R.id.etItemRefLength);
        ddGtinFormat    = v.findViewById(R.id.ddGtinFormat);
        ddEpcScheme     = v.findViewById(R.id.ddEpcScheme);
        swIncludeBranch = v.findViewById(R.id.swIncludeBranch);
        swPreEncoded    = v.findViewById(R.id.swPreEncoded);
        tvPreview       = v.findViewById(R.id.tvPreview);
        btnBack         = v.findViewById(R.id.btnBack);
        btnNext         = v.findViewById(R.id.btnNext);
        pbLoading       = v.findViewById(R.id.pbLoading);

        // Defaults
        etEpcHeader.setText("30");
        etSerialFormat.setText("MIR-{prefix}-{seq}");
        etSerialLength.setText("6");
        etItemRefLength.setText("5");

        String[] gtinLabels = isArabic ? GTIN_LABELS_AR : GTIN_LABELS_EN;
        ArrayAdapter<String> gtinAdapter = new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_dropdown_item_1line, gtinLabels);
        ddGtinFormat.setAdapter(gtinAdapter);
        ddGtinFormat.setText(gtinLabels[0], false);

        String[] epcLabels = isArabic ? EPC_LABELS_AR : EPC_LABELS_EN;
        ArrayAdapter<String> epcAdapter = new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_dropdown_item_1line, epcLabels);
        ddEpcScheme.setAdapter(epcAdapter);
        ddEpcScheme.setText(epcLabels[0], false);
    }

    private void setupListeners() {
        btnBack.setOnClickListener(v -> requireActivity().onBackPressed());
        btnNext.setOnClickListener(v -> saveAndNext());

        TextWatcher w = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { updatePreview(); }
        };
        etGs1Prefix.addTextChangedListener(w);
        etSerialFormat.addTextChangedListener(w);
        etSerialLength.addTextChangedListener(w);
    }

    private void loadCurrentRules() {
        showLoading(true);
        new Thread(() -> {
            try {
                MiraApiClient.ApiResponse resp = apiClient.getAccounting("setup/step/9/current");
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);

                    if (resp == null || !resp.isSuccess || resp.data == null) {
                        updatePreview();
                        return;
                    }

                    JSONObject payload = resp.data.optJSONObject("data");
                    if (payload == null) payload = resp.data;
                    JSONObject rules = payload.optJSONObject("rules");
                    if (rules == null) {
                        updatePreview();
                        return;
                    }
                    populateRules(rules);
                });
            } catch (Exception e) {
                Log.e(TAG, "loadCurrentRules", e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    showLoading(false);
                    updatePreview();
                });
            }
        }).start();
    }

    private void populateRules(JSONObject r) {
        try {
            String prefix = r.optString("gs1_company_prefix", "");
            if (!prefix.isEmpty()) etGs1Prefix.setText(prefix);

            String header = r.optString("epc_header", "30");
            if (!header.isEmpty()) etEpcHeader.setText(header);

            String serialFmt = r.optString("serial_format", "");
            if (!serialFmt.isEmpty()) etSerialFormat.setText(serialFmt);

            int seqLen = r.optInt("serial_sequence_length", 6);
            if (seqLen > 0) etSerialLength.setText(String.valueOf(seqLen));

            int refLen = r.optInt("item_ref_length", 5);
            if (refLen > 0) etItemRefLength.setText(String.valueOf(refLen));

            String gtinFmt = r.optString("gtin_format", "gtin13");
            String[] gtinLabels = isArabic ? GTIN_LABELS_AR : GTIN_LABELS_EN;
            for (int i = 0; i < GTIN_FORMATS.length; i++) {
                if (GTIN_FORMATS[i].equals(gtinFmt)) {
                    ddGtinFormat.setText(gtinLabels[i], false);
                    break;
                }
            }

            String epcScheme = r.optString("epc_scheme", "sgtin-96");
            String[] epcLabels = isArabic ? EPC_LABELS_AR : EPC_LABELS_EN;
            for (int i = 0; i < EPC_SCHEMES.length; i++) {
                if (EPC_SCHEMES[i].equals(epcScheme)) {
                    ddEpcScheme.setText(epcLabels[i], false);
                    break;
                }
            }

            swIncludeBranch.setChecked(r.optInt("include_branch_in_epc", 0) == 1);
            swPreEncoded.setChecked(r.optInt("pre_encoded_tags", 0) == 1);

            updatePreview();
        } catch (Exception e) {
            Log.e(TAG, "populateRules", e);
        }
    }

    private void updatePreview() {
        try {
            String prefix = safeText(etGs1Prefix);
            String serialFmt = safeText(etSerialFormat);
            String seqLenStr = safeText(etSerialLength);
            int seqLen = seqLenStr.isEmpty() ? 6 : Integer.parseInt(seqLenStr);

            if (prefix.isEmpty()) {
                tvPreview.setText(isArabic
                        ? "أدخل بادئة GS1 لعرض مثال التكويد"
                        : "Enter GS1 prefix to see encoding example");
                return;
            }

            // مثال
            String exampleSeq = String.format(Locale.US, "%0" + Math.min(seqLen, 8) + "d", 1);
            String example = serialFmt
                    .replace("{prefix}", prefix.substring(0, Math.min(4, prefix.length())))
                    .replace("{seq}", exampleSeq);

            tvPreview.setText(String.format(Locale.US,
                    isArabic
                            ? "🏷️ مثال: %s\n📦 GTIN: %s%s"
                            : "🏷️ Example: %s\n📦 GTIN: %s%s",
                    example,
                    prefix,
                    "00001"));
        } catch (Exception e) {
            tvPreview.setText("—");
        }
    }

    private void saveAndNext() {
        try {
            String gs1Prefix = safeText(etGs1Prefix);
            if (gs1Prefix.isEmpty() || !gs1Prefix.matches("^[0-9]{6,14}$")) {
                Toast.makeText(requireContext(),
                        isArabic ? "بادئة GS1 يجب أن تكون 6-14 رقماً فقط"
                                : "GS1 prefix must be 6-14 digits",
                        Toast.LENGTH_LONG).show();
                return;
            }

            String epcHeader = safeText(etEpcHeader).toUpperCase();
            if (epcHeader.isEmpty() || !epcHeader.matches("^[0-9A-F]{1,4}$")) {
                Toast.makeText(requireContext(),
                        isArabic ? "بادئة EPC يجب أن تكون HEX (0-9, A-F)"
                                : "EPC header must be HEX (0-9, A-F)",
                        Toast.LENGTH_LONG).show();
                return;
            }

            String serialFmt = safeText(etSerialFormat);
            if (serialFmt.isEmpty() || !serialFmt.contains("{seq}")) {
                Toast.makeText(requireContext(),
                        isArabic ? "صيغة المسلسل يجب أن تحتوي {seq}"
                                : "Serial format must contain {seq}",
                        Toast.LENGTH_LONG).show();
                return;
            }

            int seqLen = safeInt(etSerialLength);
            if (seqLen < 3 || seqLen > 12) {
                Toast.makeText(requireContext(),
                        isArabic ? "طول المسلسل يجب أن يكون 3-12"
                                : "Serial length must be 3-12",
                        Toast.LENGTH_LONG).show();
                return;
            }

            int refLen = safeInt(etItemRefLength);
            if (refLen < 1 || refLen > 10) {
                Toast.makeText(requireContext(),
                        isArabic ? "طول مرجع القطعة يجب أن يكون 1-10"
                                : "Item ref length must be 1-10",
                        Toast.LENGTH_LONG).show();
                return;
            }

            String gtinFmt = valueFor(ddGtinFormat.getText().toString(),
                    isArabic ? GTIN_LABELS_AR : GTIN_LABELS_EN, GTIN_FORMATS, "gtin13");
            String epcScheme = valueFor(ddEpcScheme.getText().toString(),
                    isArabic ? EPC_LABELS_AR : EPC_LABELS_EN, EPC_SCHEMES, "sgtin-96");

            JSONObject payload = new JSONObject();
            payload.put("gs1_company_prefix", gs1Prefix);
            payload.put("gtin_format", gtinFmt);
            payload.put("epc_scheme", epcScheme);
            payload.put("epc_header", epcHeader);
            payload.put("serial_format", serialFmt);
            payload.put("serial_sequence_length", seqLen);
            payload.put("item_ref_length", refLen);
            payload.put("include_branch_in_epc", swIncludeBranch.isChecked());
            payload.put("pre_encoded_tags", swPreEncoded.isChecked());

            btnNext.setEnabled(false);
            btnNext.setText(isArabic ? "جاري الحفظ..." : "Saving...");
            showLoading(true);

            new Thread(() -> {
                try {
                    MiraApiClient.ApiResponse resp = apiClient.postAccounting("setup/step/9", payload);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (!isAdded()) return;
                        showLoading(false);
                        btnNext.setEnabled(true);
                        btnNext.setText(isArabic ? "التالي ←" : "Next →");

                        if (resp != null && resp.isSuccess) {
                            Toast.makeText(requireContext(),
                                    isArabic ? "✅ تم حفظ قواعد التكويد" : "✅ Encoding rules saved",
                                    Toast.LENGTH_SHORT).show();
                            requireActivity().getSupportFragmentManager().popBackStack();
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
                        btnNext.setEnabled(true);
                        btnNext.setText(isArabic ? "التالي ←" : "Next →");
                        Toast.makeText(requireContext(), "❌ " + ex.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            }).start();

        } catch (Exception e) {
            Log.e(TAG, "saveAndNext", e);
            Toast.makeText(requireContext(), "❌ " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String safeText(TextInputEditText et) {
        if (et == null || et.getText() == null) return "";
        return et.getText().toString().trim();
    }

    private int safeInt(TextInputEditText et) {
        try {
            return Integer.parseInt(safeText(et));
        } catch (Exception e) { return 0; }
    }

    private String valueFor(String label, String[] labels, String[] values, String fallback) {
        for (int i = 0; i < labels.length; i++) {
            if (labels[i].equals(label)) return values[i];
        }
        return fallback;
    }

    private void showLoading(boolean show) {
        if (pbLoading != null) pbLoading.setVisibility(show ? View.VISIBLE : View.GONE);
    }
}
