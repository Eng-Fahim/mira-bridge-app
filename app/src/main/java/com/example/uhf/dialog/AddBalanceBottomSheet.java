package com.example.uhf.dialog;

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
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONObject;

import java.util.Locale;

/**
 * ✅ BottomSheet لإضافة رصيد جديد بعد اعتماد الإعداد
 * 
 * يُنشئ قيد تسوية تلقائياً عبر /setup/add-balance
 */
public class AddBalanceBottomSheet extends BottomSheetDialogFragment {

    private static final String TAG = "AddBalanceSheet";

    public interface OnBalanceAddedListener {
        void onBalanceAdded(String entryNumber);
    }

    // ✅ أنواع الرصيد
    private static final String[] TYPES = {
        "cash", "bank", "gold", "receivable", "payable", "fixed_asset", "diamond"
    };

    private static final String[] TYPES_LABELS = {
        "💵 نقدية",
        "🏦 بنك",
        "🥇 ذهب",
        "👥 ذمم عملاء",
        "🏢 ذمم موردين",
        "🪑 أصول ثابتة",
        "💎 ألماس وأحجار"
    };

    private static final String[] KARATS = {"24K", "22K", "21K", "18K", "14K"};
    private static final double[] KARAT_FACTORS = {1.0, 0.9166, 0.875, 0.75, 0.5833};

    private MaterialAutoCompleteTextView spBalanceType, spKarat;
    private TextInputLayout tilAmount, tilWeight, tilKarat;
    private TextInputEditText etAmount, etWeight, etDescription;
    private MaterialCardView cardPreview;
    private TextView tvPreview, tvBalanceStatus;
    private MaterialButton btnSave, btnCancel;
    private ProgressBar pbLoading;

    private String selectedType = "cash";
    private String selectedKarat = "21K";
    private OnBalanceAddedListener listener;

    public void setListener(OnBalanceAddedListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_add_balance, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        bindViews(view);
        setupTypeSelector();
        setupKaratSelector();
        setupWatchers();
        setupButtons();
    }

    private void bindViews(View v) {
        spBalanceType    = v.findViewById(R.id.spBalanceType);
        spKarat          = v.findViewById(R.id.spKarat);
        tilAmount        = v.findViewById(R.id.tilAmount);
        tilWeight        = v.findViewById(R.id.tilWeight);
        tilKarat         = v.findViewById(R.id.tilKarat);
        etAmount         = v.findViewById(R.id.etAmount);
        etWeight         = v.findViewById(R.id.etWeight);
        etDescription    = v.findViewById(R.id.etDescription);
        cardPreview      = v.findViewById(R.id.cardPreview);
        tvPreview        = v.findViewById(R.id.tvPreview);
        tvBalanceStatus  = v.findViewById(R.id.tvBalanceStatus);
        btnSave          = v.findViewById(R.id.btnSave);
        btnCancel        = v.findViewById(R.id.btnCancel);
        pbLoading        = v.findViewById(R.id.pbLoading);
    }

    private void setupTypeSelector() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            TYPES_LABELS
        );
        spBalanceType.setAdapter(adapter);
        spBalanceType.setText(TYPES_LABELS[0], false);

        spBalanceType.setOnItemClickListener((parent, v, pos, id) -> {
            selectedType = TYPES[pos];
            onTypeChanged();
        });
    }

    private void setupKaratSelector() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            KARATS
        );
        spKarat.setAdapter(adapter);
        spKarat.setText("21K", false);

        spKarat.setOnItemClickListener((parent, v, pos, id) -> {
            selectedKarat = KARATS[pos];
            updatePreview();
        });
    }

    private void setupWatchers() {
        etAmount.addTextChangedListener(new SimpleWatcher(this::updatePreview));
        etWeight.addTextChangedListener(new SimpleWatcher(this::updatePreview));
    }

    private void setupButtons() {
        btnCancel.setOnClickListener(v -> dismiss());

        btnSave.setOnClickListener(v -> {
            double amount = parseDouble(etAmount);
            if (amount <= 0) {
                Toast.makeText(requireContext(),
                    "⚠️ أدخل مبلغاً صحيحاً", Toast.LENGTH_SHORT).show();
                return;
            }

            // للذهب: تحقق من الوزن
            if ("gold".equals(selectedType)) {
                double weight = parseDouble(etWeight);
                if (weight <= 0) {
                    Toast.makeText(requireContext(),
                        "⚠️ أدخل وزن الذهب بالجرام", Toast.LENGTH_SHORT).show();
                    return;
                }
            }

            saveBalance(amount);
        });
    }

    private void onTypeChanged() {
        boolean isGold = "gold".equals(selectedType);

        tilKarat.setVisibility(isGold ? View.VISIBLE : View.GONE);
        tilWeight.setVisibility(isGold ? View.VISIBLE : View.GONE);

        // لذهب: العنوان يصبح "قيمة الذهب YER"
        if (isGold) {
            tilAmount.setHint("القيمة (YER)");
        } else {
            tilAmount.setHint("المبلغ (YER)");
        }

        updatePreview();
    }

    private void updatePreview() {
        double amount = parseDouble(etAmount);
        double weight = parseDouble(etWeight);

        if (amount <= 0) {
            cardPreview.setVisibility(View.GONE);
            return;
        }

        cardPreview.setVisibility(View.VISIBLE);

        StringBuilder sb = new StringBuilder();
        String accountName = getAccountLabel(selectedType);

        if ("payable".equals(selectedType)) {
            // موردون → دائن
            sb.append(String.format(Locale.US, "مدين: 3101 رأس المال          %,.2f YER\n", amount));
            sb.append(String.format(Locale.US, "دائن: %s              %,.2f YER",
                accountName, amount));
        } else {
            // الباقي → مدين
            sb.append(String.format(Locale.US, "مدين: %s              %,.2f YER\n",
                accountName, amount));
            sb.append(String.format(Locale.US, "دائن: 3101 رأس المال          %,.2f YER",
                amount));
        }

        if ("gold".equals(selectedType) && weight > 0) {
            sb.append("\n\n🥇 الذهب: ").append(String.format(Locale.US, "%.3f g", weight))
              .append(" @ ").append(selectedKarat);
        }

        tvPreview.setText(sb.toString());
        tvBalanceStatus.setText("✅ متوازن");
        tvBalanceStatus.setTextColor(0xFF27AE60);
    }

    private String getAccountLabel(String type) {
        switch (type) {
            case "cash":        return "111001 الصندوق";
            case "bank":        return "112001 البنك";
            case "gold":        return "1301/1302 المخزون";
            case "receivable":  return "113001 عملاء";
            case "payable":     return "211001 موردون";
            case "fixed_asset": return "121001 أصول ثابتة";
            case "diamond":     return "1302 ألماس وأحجار";
            default:            return type;
        }
    }

    private void saveBalance(double amount) {
        setUiEnabled(false);

        new Thread(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(
                    requireContext().getApplicationContext());

                JSONObject extra = new JSONObject();
                extra.put("description", etDescription.getText() != null
                    ? etDescription.getText().toString().trim() : "");

                if ("gold".equals(selectedType)) {
                    extra.put("karat", selectedKarat);
                }

                MiraApiClient.ApiResponse response =
                    client.addPostApprovalBalance(selectedType, amount, extra);

                new Handler(Looper.getMainLooper()).post(() -> {
                    setUiEnabled(true);

                    if (response != null && response.isSuccess && response.data != null) {
                        String entryNumber = response.data.optString("entry_number", "");

                        Toast.makeText(requireContext(),
                            "✅ تم إضافة الرصيد بنجاح\n" +
                            "قيد: " + entryNumber,
                            Toast.LENGTH_LONG).show();

                        if (listener != null) {
                            listener.onBalanceAdded(entryNumber);
                        }

                        dismiss();
                    } else {
                        String err = response != null && response.error != null
                            ? response.error : "فشل الحفظ";
                        Toast.makeText(requireContext(),
                            "❌ " + err, Toast.LENGTH_LONG).show();
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "saveBalance error: " + e.getMessage(), e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    setUiEnabled(true);
                    Toast.makeText(requireContext(),
                        "❌ " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void setUiEnabled(boolean enabled) {
        btnSave.setEnabled(enabled);
        btnCancel.setEnabled(enabled);
        spBalanceType.setEnabled(enabled);
        spKarat.setEnabled(enabled);
        etAmount.setEnabled(enabled);
        etWeight.setEnabled(enabled);
        etDescription.setEnabled(enabled);
        pbLoading.setVisibility(enabled ? View.GONE : View.VISIBLE);
        btnSave.setText(enabled ? "💾 حفظ القيد" : "جاري الحفظ...");
    }

    private double parseDouble(TextInputEditText et) {
        try {
            if (et == null || et.getText() == null) return 0;
            String str = et.getText().toString().trim();
            return str.isEmpty() ? 0 : Double.parseDouble(str);
        } catch (Exception e) {
            return 0;
        }
    }

    private static class SimpleWatcher implements TextWatcher {
        private final Runnable onChanged;
        SimpleWatcher(Runnable r) { this.onChanged = r; }
        @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
        @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
        @Override public void afterTextChanged(Editable s) { onChanged.run(); }
    }
}
