package com.example.uhf.dialog;

import android.app.Dialog;
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
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * TradeInDialog v2.0
 * - ✅ يستعيد الأسطر السابقة عند إعادة الفتح
 * - ✅ ليبل "العيار" فوق قائمة الـ karat
 * - ✅ حقل وصف اختياري لكل قطعة
 */
public class TradeInDialog extends DialogFragment {
    private static final String TAG = "TradeInDialog";
    private static final String ARG_MAX_VALUE = "max_value";
    private static final String ARG_EXISTING_LINES = "existing_lines";
    private static final String[] KARATS = {"24K", "22K", "21K", "18K", "14K"};

    public interface OnTradeInListener {
        void onConfirm(JSONArray tradeIns, double totalValue);
    }

    private static class Line {
        String id;
        String karat = "21K";
        double weight = 0;
        double value = 0;
        double pure24k = 0;
        double pricePerGram = 0;
        double discountPct = 0;
        String description = "";
        boolean calculating = false;
        View rowView;
    }

    private double maxValue = 0;
    private boolean isArabic = true;
    private OnTradeInListener listener;
    private LinearLayout llLines;
    private TextView tvTotal, tvExcess, tvExcessLabel, tvMaxValue;
    private Button btnConfirm, btnCancel, btnAdd;
    private final List<Line> lines = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static TradeInDialog newInstance(double maxValue) {
        return newInstance(maxValue, null);
    }

    public static TradeInDialog newInstance(double maxValue, JSONArray existingLines) {
        TradeInDialog d = new TradeInDialog();
        Bundle args = new Bundle();
        args.putDouble(ARG_MAX_VALUE, maxValue);
        if (existingLines != null) {
            args.putString(ARG_EXISTING_LINES, existingLines.toString());
        }
        d.setArguments(args);
        return d;
    }

    public void setOnTradeInListener(OnTradeInListener l) { this.listener = l; }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) maxValue = getArguments().getDouble(ARG_MAX_VALUE, 0);
        isArabic = Locale.getDefault().getLanguage().equals("ar");
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog d = super.onCreateDialog(savedInstanceState);
        if (d.getWindow() != null) {
            d.getWindow().requestFeature(Window.FEATURE_NO_TITLE);
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        return d;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.94),
                    WindowManager.LayoutParams.WRAP_CONTENT);
            getDialog().getWindow().setDimAmount(0.5f);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_trade_in, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        llLines = view.findViewById(R.id.llLines);
        tvTotal = view.findViewById(R.id.tvTotal);
        tvExcess = view.findViewById(R.id.tvExcess);
        tvExcessLabel = view.findViewById(R.id.tvExcessLabel);
        tvMaxValue = view.findViewById(R.id.tvMaxValue);
        btnConfirm = view.findViewById(R.id.btnConfirm);
        btnCancel = view.findViewById(R.id.btnCancel);
        btnAdd = view.findViewById(R.id.btnAdd);

        if (tvMaxValue != null) tvMaxValue.setText(String.format(Locale.US, "%,.0f YER", maxValue));
        btnCancel.setOnClickListener(v -> dismiss());
        btnAdd.setOnClickListener(v -> addEmptyLine());
        btnConfirm.setOnClickListener(v -> onConfirmClicked());

        // ✅ استعادة الأسطر السابقة
        restoreExistingLines();

        if (lines.isEmpty()) {
            addEmptyLine();
        }
    }

    private void restoreExistingLines() {
        try {
            String json = getArguments() != null ? getArguments().getString(ARG_EXISTING_LINES, null) : null;
            if (json == null || json.isEmpty()) return;

            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                Line line = new Line();
                line.id = UUID.randomUUID().toString();
                line.karat = obj.optString("karat", "21K");
                line.weight = obj.optDouble("weight_grams", 0);
                line.value = obj.optDouble("value_yer", 0);
                line.description = obj.optString("description", "");
                addLineWithData(line);
            }
            Log.d(TAG, "Restored " + lines.size() + " trade-in lines");
        } catch (Exception e) {
            Log.e(TAG, "restoreExistingLines error", e);
        }
    }

    private void addEmptyLine() {
        Line line = new Line();
        line.id = UUID.randomUUID().toString();
        addLineWithData(line);
    }

    private void addLineWithData(Line line) {
        lines.add(line);
        View row = getLayoutInflater().inflate(R.layout.item_trade_in_row, llLines, false);
        line.rowView = row;

        MaterialAutoCompleteTextView actKarat = row.findViewById(R.id.actKarat);
        TextInputEditText etWeight = row.findViewById(R.id.etWeight);
        TextInputEditText etDescription = row.findViewById(R.id.etDescription);
        ImageButton btnRemove = row.findViewById(R.id.btnRemove);

        // Karat dropdown
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_dropdown_item_1line, KARATS);
        actKarat.setAdapter(adapter);
        actKarat.setText(line.karat, false);
        actKarat.setOnItemClickListener((parent, v, pos, id) -> {
            line.karat = KARATS[pos];
            if (line.weight > 0) recalculate(line);
        });

        // Weight
        if (line.weight > 0) {
            etWeight.setText(String.format(Locale.US, "%.3f", line.weight));
        }
        etWeight.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                try { line.weight = Double.parseDouble(s.toString().trim()); }
                catch (Exception e) { line.weight = 0; }
                recalculate(line);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // Description
        if (!line.description.isEmpty()) etDescription.setText(line.description);
        etDescription.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                line.description = s != null ? s.toString().trim() : "";
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // Remove
        btnRemove.setOnClickListener(v -> {
            if (lines.size() <= 1) return;
            lines.remove(line);
            llLines.removeView(row);
            updateTotals();
        });

        llLines.addView(row);
        updateLineDisplay(line);
        updateTotals();
    }

    private void recalculate(Line line) {
        if (line.weight <= 0) {
            line.value = 0;
            line.calculating = false;
            updateLineDisplay(line);
            updateTotals();
            return;
        }
        if (line.calculating) return;
        line.calculating = true;

        final double w = line.weight;
        final String k = line.karat;

        new Thread(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(requireContext().getApplicationContext());
                MiraApiClient.ApiResponse r = client.calculateScrap(w, k);
                mainHandler.post(() -> {
                    line.calculating = false;
                    if (r != null && r.isSuccess && r.data != null) {
                        line.value = r.data.optDouble("total_value", 0);
                        line.pure24k = r.data.optDouble("pure_24k_grams", 0);
                        line.pricePerGram = r.data.optDouble("price_per_gram", 0);
                        line.discountPct = r.data.optDouble("discount_percent", 0);
                    } else {
                        line.value = 0;
                    }
                    updateLineDisplay(line);
                    updateTotals();
                });
            } catch (Exception e) {
                Log.e(TAG, "calc error", e);
                mainHandler.post(() -> {
                    line.calculating = false;
                    updateLineDisplay(line);
                    updateTotals();
                });
            }
        }).start();
    }

    private void updateLineDisplay(Line line) {
        if (line.rowView == null) return;
        TextView tvValue = line.rowView.findViewById(R.id.tvValue);
        TextView tvCalcInfo = line.rowView.findViewById(R.id.tvCalcInfo);
        if (tvValue != null) {
            tvValue.setText(line.calculating ? "..." : String.format(Locale.US, "%,.0f YER", line.value));
        }
        if (tvCalcInfo != null) {
            if (line.value > 0) {
                tvCalcInfo.setText(String.format(Locale.US,
                        "24K: %.3fg · سعر: %,.0f · خصم: %.1f%%",
                        line.pure24k, line.pricePerGram, line.discountPct));
                tvCalcInfo.setVisibility(View.VISIBLE);
            } else {
                tvCalcInfo.setVisibility(View.GONE);
            }
        }
    }

    private void updateTotals() {
        double total = 0;
        for (Line l : lines) total += l.value;

        if (tvTotal != null) tvTotal.setText(String.format(Locale.US, "%,.0f YER", total));

        double excess = total - maxValue;
        if (excess > 0.01) {
            if (tvExcess != null) {
                tvExcess.setText(String.format(Locale.US, "+%,.0f YER", excess));
                tvExcess.setTextColor(Color.parseColor("#2563EB"));
            }
            if (tvExcessLabel != null) tvExcessLabel.setVisibility(View.VISIBLE);
        } else {
            if (tvExcess != null) {
                tvExcess.setText("0 YER");
                tvExcess.setTextColor(Color.parseColor("#6B7280"));
            }
            if (tvExcessLabel != null) tvExcessLabel.setVisibility(View.GONE);
        }

        if (btnConfirm != null) {
            boolean enabled = total > 0 && !anyCalculating();
            btnConfirm.setEnabled(enabled);
            btnConfirm.setAlpha(enabled ? 1f : 0.5f);
            btnConfirm.setText(String.format(Locale.US, "تأكيد (%,.0f YER)", total));
        }
    }

    private boolean anyCalculating() {
        for (Line l : lines) if (l.calculating) return true;
        return false;
    }

    private void onConfirmClicked() {
        if (listener == null) { dismiss(); return; }
        try {
            JSONArray arr = new JSONArray();
            double total = 0;
            for (Line l : lines) {
                if (l.weight <= 0 || l.value <= 0) continue;
                JSONObject o = new JSONObject();
                o.put("weight_grams", l.weight);
                o.put("karat", l.karat);
                o.put("value_yer", l.value);
                // ✅ الوصف: المُدخل يدوياً، أو الافتراضي
                o.put("description", l.description != null && !l.description.isEmpty()
                        ? l.description
                        : (l.karat + " · " + String.format(Locale.US, "%.3fg", l.weight)));
                arr.put(o);
                total += l.value;
            }
            listener.onConfirm(arr, total);
            dismiss();
        } catch (Exception e) {
            Log.e(TAG, "confirm error", e);
        }
    }
}
