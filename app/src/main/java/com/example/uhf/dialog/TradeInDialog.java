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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.DialogFragment;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * TradeInDialog — نسخة الأندرويد المطابقة لـ TradeInModal في الويب
 * - إدخال عيار ووزن الذهب الكسر
 * - حساب القيمة تلقائياً عبر API
 * - عرض "سيُضاف رصيد دائن" عند الزيادة
 * - دعم كامل للغتين العربية والإنجليزية
 */
public class TradeInDialog extends DialogFragment {
    private static final String TAG = "TradeInDialog";
    private static final String ARG_MAX_VALUE = "max_value";
    private static final String[] KARATS = {"24K", "22K", "21K", "18K", "14K"};

    public interface OnTradeInListener {
        void onConfirm(JSONArray tradeIns, double totalValue);
    }

    private static class Line {
        // ✅ تم حذف حقل id غير المستخدم
        String karat = "21K";
        double weight = 0;
        double value = 0;
        double pure24k = 0;
        double pricePerGram = 0;
        double discountPct = 0;
        boolean calculating = false;
        View rowView;
    }

    private double maxValue = 0;
    private boolean isArabic = false;
    private OnTradeInListener listener;

    private LinearLayout llLines;
    private TextView tvTotal, tvExcess, tvExcessLabel, tvMaxValue;
    private Button btnConfirm, btnCancel, btnAdd;

    private final List<Line> lines = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static TradeInDialog newInstance(double maxValue) {
        TradeInDialog d = new TradeInDialog();
        Bundle args = new Bundle();
        args.putDouble(ARG_MAX_VALUE, maxValue);
        d.setArguments(args);
        return d;
    }

    public void setOnTradeInListener(OnTradeInListener l) { this.listener = l; }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            maxValue = getArguments().getDouble(ARG_MAX_VALUE, 0);
        }
        // ✅ توحيد اكتشاف اللغة مع POSCheckoutDialog
        try {
            isArabic = AppCompatDelegate.getApplicationLocales()
                    .toLanguageTags().contains("ar");
        } catch (Exception e) {
            isArabic = false;
        }
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

        llLines      = view.findViewById(R.id.llLines);
        tvTotal      = view.findViewById(R.id.tvTotal);
        tvExcess     = view.findViewById(R.id.tvExcess);
        tvExcessLabel = view.findViewById(R.id.tvExcessLabel);
        tvMaxValue   = view.findViewById(R.id.tvMaxValue);
        btnConfirm   = view.findViewById(R.id.btnConfirm);
        btnCancel    = view.findViewById(R.id.btnCancel);
        btnAdd       = view.findViewById(R.id.btnAdd);

        // ✅ نصوص ثابتة حسب اللغة
        if (tvMaxValue != null) {
            tvMaxValue.setText(String.format(Locale.US, "%,.0f YER", maxValue));
        }
        if (btnAdd != null) {
            btnAdd.setText(isArabic ? "+ إضافة قطعة" : "+ Add Item");
        }
        if (btnCancel != null) {
            btnCancel.setText(isArabic ? "إلغاء" : "Cancel");
        }
        if (tvExcessLabel != null) {
            tvExcessLabel.setText(isArabic ? "سيُضاف رصيد دائن" : "Store credit to be added");
        }

        if (btnCancel != null) btnCancel.setOnClickListener(v -> dismiss());
        if (btnAdd != null)    btnAdd.setOnClickListener(v -> addLine());
        if (btnConfirm != null) btnConfirm.setOnClickListener(v -> onConfirmClicked());

        // ✅ السطر الأول افتراضي
        addLine();
    }

    private void addLine() {
        Line line = new Line();
        lines.add(line);

        View row = getLayoutInflater().inflate(R.layout.item_trade_in_row, llLines, false);
        line.rowView = row;

        Spinner spKarat    = row.findViewById(R.id.spKarat);
        EditText etWeight  = row.findViewById(R.id.etWeight);
        ImageButton btnRemove = row.findViewById(R.id.btnRemove);

        // ✅ hint للوزن حسب اللغة
        if (etWeight != null) {
            etWeight.setHint(isArabic ? "الوزن (جرام)" : "Weight (g)");
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_spinner_item, KARATS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spKarat.setAdapter(adapter);
        spKarat.setSelection(2); // 21K

        spKarat.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                line.karat = KARATS[pos];
                recalculate(line);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        etWeight.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                try { line.weight = Double.parseDouble(s.toString().trim()); }
                catch (Exception e) { line.weight = 0; }
                recalculate(line);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        btnRemove.setOnClickListener(v -> {
            if (lines.size() <= 1) return;
            lines.remove(line);
            llLines.removeView(row);
            updateTotals();
        });

        llLines.addView(row);
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
        updateLineDisplay(line);

        final double w = line.weight;
        final String k = line.karat;

        new Thread(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(
                        requireContext().getApplicationContext());
                MiraApiClient.ApiResponse r = client.calculateScrap(w, k);

                mainHandler.post(() -> {
                    line.calculating = false;
                    if (r != null && r.isSuccess && r.data != null) {
                        line.value         = r.data.optDouble("total_value", 0);
                        line.pure24k       = r.data.optDouble("pure_24k_grams", 0);
                        line.pricePerGram  = r.data.optDouble("price_per_gram", 0);
                        line.discountPct   = r.data.optDouble("discount_percent", 0);
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

        TextView tvValue    = line.rowView.findViewById(R.id.tvValue);
        TextView tvCalcInfo = line.rowView.findViewById(R.id.tvCalcInfo);

        if (tvValue != null) {
            tvValue.setText(line.calculating
                    ? "..."
                    : String.format(Locale.US, "%,.0f YER", line.value));
        }

        if (tvCalcInfo != null) {
            if (line.value > 0) {
                // ✅ نص تفاصيل الحساب حسب اللغة
                String details = isArabic
                        ? String.format(Locale.US,
                            "24K: %.3fg · سعر: %,.0f · خصم: %.1f%%",
                            line.pure24k, line.pricePerGram, line.discountPct)
                        : String.format(Locale.US,
                            "24K: %.3fg · Price: %,.0f · Disc: %.1f%%",
                            line.pure24k, line.pricePerGram, line.discountPct);
                tvCalcInfo.setText(details);
                tvCalcInfo.setVisibility(View.VISIBLE);
            } else {
                tvCalcInfo.setVisibility(View.GONE);
            }
        }
    }

    private void updateTotals() {
        double total = 0;
        for (Line l : lines) total += l.value;

        if (tvTotal != null) {
            tvTotal.setText(String.format(Locale.US, "%,.0f YER", total));
        }

        double excess = total - maxValue;
        if (excess > 0.01) {
            if (tvExcess != null) {
                tvExcess.setText(String.format(Locale.US, "+%,.0f YER", excess));
                tvExcess.setTextColor(Color.parseColor("#2563EB"));
            }
            if (tvExcessLabel != null) {
                tvExcessLabel.setVisibility(View.VISIBLE);
                tvExcessLabel.setText(isArabic
                        ? "سيُضاف رصيد دائن"
                        : "Store credit to be added");
            }
        } else {
            if (tvExcess != null) {
                tvExcess.setText("0 YER");
                tvExcess.setTextColor(Color.parseColor("#6B7280"));
            }
            if (tvExcessLabel != null) {
                tvExcessLabel.setVisibility(View.GONE);
            }
        }

        if (btnConfirm != null) {
            boolean enabled = total > 0 && !anyCalculating();
            btnConfirm.setEnabled(enabled);
            btnConfirm.setAlpha(enabled ? 1f : 0.5f);
            // ✅ نص زر التأكيد حسب اللغة
            btnConfirm.setText(isArabic
                    ? String.format(Locale.US, "تأكيد (%,.0f YER)", total)
                    : String.format(Locale.US, "Confirm (%,.0f YER)", total));
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
                arr.put(o);
                total += l.value;
            }
            listener.onConfirm(arr, total);
            // ✅ اتساق مع POSCheckoutDialog + حماية من crashes
            if (getParentFragmentManager() != null) {
                dismissAllowingStateLoss();
            }
        } catch (Exception e) {
            Log.e(TAG, "confirm error", e);
        }
    }
}
