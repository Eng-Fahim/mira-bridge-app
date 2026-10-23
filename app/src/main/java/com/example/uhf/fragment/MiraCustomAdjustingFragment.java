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
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.api.dto.AccountDto;
import com.example.uhf.dialog.AccountPickerBottomSheet;
import com.example.uhf.utils.NumberFormatter;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ✅ شاشة قيد تسوية مخصص — متعدد السطور
 * - تدعم المبالغ المالية وأوزان الذهب
 */
public class MiraCustomAdjustingFragment extends Fragment {

    private static final String TAG = "CustomAdjusting";

    // Views
    private com.google.android.material.appbar.MaterialToolbar toolbar;
    private TextInputEditText etEntryDescription, etReference;
    private TextView tvTotalDebit, tvTotalCredit, tvDifference, tvBalanceStatus;
    private MaterialButton btnAddLine;
    private ExtendedFloatingActionButton fabSave;
    private RecyclerView rvLines;
    private ProgressBar pbLoading;
    private MaterialCardView cardSummary;

    // State
    private final List<LineModel> lines = new ArrayList<>();
    private LineAdapter adapter;
    private MiraApiClient apiClient;

    /**
     * نموذج السطر (تمت إضافة أوزان الذهب)
     */
    public static class LineModel {
        public long accountId = 0;
        public String accountCode = "";
        public String accountName = "";
        public double debit = 0;
        public double credit = 0;
        public double goldDebit = 0;  // ✅ وزن الذهب المدين
        public double goldCredit = 0; // ✅ وزن الذهب الدائن
        public String description = "";
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_custom_adjusting,
            container, false);

        apiClient = MiraApiClient.getInstance(requireContext().getApplicationContext());

        initViews(v);
        setupToolbar();
        setupRecycler();
        setupListeners();

        // ابدأ بسطرين: مدين + دائن
        addLine();
        addLine();

        return v;
    }

    private void initViews(View v) {
        toolbar              = v.findViewById(R.id.toolbar);
        etEntryDescription   = v.findViewById(R.id.etEntryDescription);
        etReference          = v.findViewById(R.id.etReference);
        tvTotalDebit         = v.findViewById(R.id.tvTotalDebit);
        tvTotalCredit        = v.findViewById(R.id.tvTotalCredit);
        tvDifference         = v.findViewById(R.id.tvDifference);
        tvBalanceStatus      = v.findViewById(R.id.tvBalanceStatus);
        btnAddLine           = v.findViewById(R.id.btnAddLine);
        fabSave              = v.findViewById(R.id.fabSave);
        rvLines              = v.findViewById(R.id.rvLines);
        pbLoading            = v.findViewById(R.id.pbLoading);
        cardSummary          = v.findViewById(R.id.cardSummary);
    }

    private void setupToolbar() {
        toolbar.setNavigationOnClickListener(v -> {
            if (hasData()) {
                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("⚠️ تجاهل القيد؟")
                    .setMessage("ستفقد كل السطور المدخلة")
                    .setPositiveButton("نعم، تجاهل", (d, w) -> close())
                    .setNegativeButton("متابعة التحرير", null)
                    .show();
            } else {
                close();
            }
        });
    }

    private void close() {
        if (getParentFragmentManager() != null) {
            getParentFragmentManager().popBackStack();
        }
    }

    private boolean hasData() {
        for (LineModel l : lines) {
            if (l.accountId > 0 || l.debit > 0 || l.credit > 0 || l.goldDebit > 0 || l.goldCredit > 0) {
                return true;
            }
        }
        return false;
    }

    private void setupRecycler() {
        adapter = new LineAdapter();
        rvLines.setLayoutManager(new LinearLayoutManager(getContext()));
        rvLines.setAdapter(adapter);
    }

    private void setupListeners() {
        btnAddLine.setOnClickListener(v -> addLine());
        fabSave.setOnClickListener(v -> saveEntry());
    }

    // ═══════════════════════════════════════════════════════════
    //  Line Management
    // ═══════════════════════════════════════════════════════════

    private void addLine() {
        lines.add(new LineModel());
        adapter.notifyItemInserted(lines.size() - 1);
        rvLines.scrollToPosition(lines.size() - 1);
        updateTotals();
    }

    private void removeLine(int position) {
        if (lines.size() <= 2) {
            Toast.makeText(requireContext(),
                "⚠️ يجب أن يبقى سطران على الأقل", Toast.LENGTH_SHORT).show();
            return;
        }
        lines.remove(position);
        adapter.notifyItemRemoved(position);
        adapter.notifyItemRangeChanged(position, lines.size() - position);
        updateTotals();
    }

    // ═══════════════════════════════════════════════════════════
    //  Totals
    // ═══════════════════════════════════════════════════════════

    private void updateTotals() {
        double totalDebit = 0;
        double totalCredit = 0;
        double totalGoldDebit = 0;
        double totalGoldCredit = 0;

        for (LineModel l : lines) {
            totalDebit += l.debit;
            totalCredit += l.credit;
            totalGoldDebit += l.goldDebit;
            totalGoldCredit += l.goldCredit;
        }

        double diff = totalDebit - totalCredit;
        double goldDiff = totalGoldDebit - totalGoldCredit;

        tvTotalDebit.setText(String.format(Locale.US, "%,.2f", totalDebit));
        tvTotalCredit.setText(String.format(Locale.US, "%,.2f", totalCredit));
        tvDifference.setText(String.format(Locale.US, "%,.2f", Math.abs(diff)));

        boolean moneyBalanced = Math.abs(diff) < 0.01 && totalDebit > 0;
        boolean goldBalanced = Math.abs(goldDiff) < 0.001;

        if (moneyBalanced && goldBalanced) {
            tvDifference.setTextColor(0xFF27AE60); // أخضر
            tvBalanceStatus.setText("✅ القيد متوازن — جاهز للحفظ");
            tvBalanceStatus.setTextColor(0xFF27AE60);
            fabSave.setEnabled(true);
            fabSave.setAlpha(1f);
        } else if (totalDebit == 0 && totalCredit == 0 && totalGoldDebit == 0) {
            tvDifference.setTextColor(0xFF7F8C8D);
            tvBalanceStatus.setText("⚪ لم تُدخل بيانات بعد");
            tvBalanceStatus.setTextColor(0xFF7F8C8D);
            fabSave.setEnabled(false);
            fabSave.setAlpha(0.5f);
        } else {
            tvDifference.setTextColor(0xFFE74C3C); // أحمر
            if (!moneyBalanced) {
                tvBalanceStatus.setText("⚠️ غير متوازن مالياً — الفرق: " + String.format(Locale.US, "%,.2f", Math.abs(diff)));
            } else if (!goldBalanced) {
                tvBalanceStatus.setText("⚠️ غير متوازن وزنياً — الفرق: " + String.format(Locale.US, "%.3f", Math.abs(goldDiff)) + "g");
            }
            tvBalanceStatus.setTextColor(0xFFE74C3C);
            fabSave.setEnabled(false);
            fabSave.setAlpha(0.5f);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Save
    // ═══════════════════════════════════════════════════════════

    private void saveEntry() {
        String desc = etEntryDescription.getText() != null
            ? etEntryDescription.getText().toString().trim() : "";
        if (desc.isEmpty()) {
            Toast.makeText(requireContext(), "⚠️ أدخل وصف القيد", Toast.LENGTH_SHORT).show();
            etEntryDescription.requestFocus();
            return;
        }

        JSONArray linesArr = new JSONArray();
        double totalDebit = 0;
        double totalCredit = 0;
        double totalGoldDebit = 0;
        double totalGoldCredit = 0;

        try {
            for (LineModel l : lines) {
                if (l.accountId <= 0) continue;
                if (l.debit <= 0 && l.credit <= 0 && l.goldDebit <= 0 && l.goldCredit <= 0) continue;

                if (l.debit > 0 && l.credit > 0) {
                    Toast.makeText(requireContext(), "⚠️ لا يمكن أن يكون السطر مديناً ودائناً معاً مالياً", Toast.LENGTH_LONG).show();
                    return;
                }
                if (l.goldDebit > 0 && l.goldCredit > 0) {
                    Toast.makeText(requireContext(), "⚠️ لا يمكن أن يكون السطر مديناً ودائناً معاً وزنياً", Toast.LENGTH_LONG).show();
                    return;
                }

                JSONObject line = new JSONObject();
                line.put("account_id", l.accountId);
                line.put("description", l.description);
                line.put("debit", l.debit);
                line.put("credit", l.credit);
                line.put("gold_debit", l.goldDebit);   // ✅ إرسال الوزن
                line.put("gold_credit", l.goldCredit); // ✅ إرسال الوزن

                linesArr.put(line);

                totalDebit += l.debit;
                totalCredit += l.credit;
                totalGoldDebit += l.goldDebit;
                totalGoldCredit += l.goldCredit;
            }
        } catch (Exception e) {
            Log.e(TAG, "build lines error: " + e.getMessage());
            return;
        }

        if (linesArr.length() < 2) {
            Toast.makeText(requireContext(), "⚠️ يجب إدخال سطرين على الأقل", Toast.LENGTH_SHORT).show();
            return;
        }

        if (Math.abs(totalDebit - totalCredit) > 0.01) {
            Toast.makeText(requireContext(), "⚠️ القيد غير متوازن مالياً", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Math.abs(totalGoldDebit - totalGoldCredit) > 0.001) {
            Toast.makeText(requireContext(), "⚠️ القيد غير متوازن وزنياً", Toast.LENGTH_SHORT).show();
            return;
        }

        setUiEnabled(false);
        pbLoading.setVisibility(View.VISIBLE);

        final JSONArray finalLines = linesArr;
        final String finalDesc = desc;

        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("entry_date", com.example.uhf.utils.DateHelper.todayApi());
                payload.put("description", finalDesc);

                String ref = etReference.getText() != null ? etReference.getText().toString().trim() : "";
                payload.put("reference", ref.isEmpty() ? "ADJ-" + System.currentTimeMillis() : ref);
                payload.put("lines", finalLines);

                MiraApiClient.ApiResponse response = apiClient.createAdjustingEntry(payload);

                new Handler(Looper.getMainLooper()).post(() -> {
                    setUiEnabled(true);
                    pbLoading.setVisibility(View.GONE);

                    if (response != null && response.isSuccess && response.data != null) {
                        String entryNumber = response.data.optString("entry_number", "");
                        Toast.makeText(requireContext(), "✅ تم إنشاء القيد: " + entryNumber, Toast.LENGTH_LONG).show();
                        close();
                    } else {
                        String err = response != null && response.error != null ? response.error : "فشل الحفظ";
                        Toast.makeText(requireContext(), "❌ " + err, Toast.LENGTH_LONG).show();
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "saveEntry error: " + e.getMessage(), e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    setUiEnabled(true);
                    pbLoading.setVisibility(View.GONE);
                    Toast.makeText(requireContext(), "❌ " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void setUiEnabled(boolean enabled) {
        fabSave.setEnabled(enabled);
        btnAddLine.setEnabled(enabled);
        etEntryDescription.setEnabled(enabled);
        etReference.setEnabled(enabled);
        fabSave.setText(enabled ? "💾 حفظ القيد" : "جاري الحفظ...");
    }

    // ═══════════════════════════════════════════════════════════
    //  Inner Adapter
    // ═══════════════════════════════════════════════════════════

    private class LineAdapter extends RecyclerView.Adapter<LineVH> {

        @NonNull
        @Override
        public LineVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_custom_line, parent, false);
            return new LineVH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull LineVH h, int position) {
            LineModel line = lines.get(position);

            h.tvLineNumber.setText("سطر " + (position + 1));

            if (line.accountId > 0) {
                h.tvAccountDisplay.setText(line.accountCode + " - " + line.accountName);
                h.tvAccountDisplay.setTextColor(h.itemView.getResources().getColor(android.R.color.primary_text_dark, null));
            } else {
                h.tvAccountDisplay.setText("اختر الحساب...");
                h.tvAccountDisplay.setTextColor(0xFF7F8C8D);
            }

            // Amounts (بدون triggering watcher)
            h.etDebit.setText(line.debit > 0 ? String.format(Locale.US, "%.2f", line.debit) : "");
            h.etCredit.setText(line.credit > 0 ? String.format(Locale.US, "%.2f", line.credit) : "");
            h.etGoldDebit.setText(line.goldDebit > 0 ? String.format(Locale.US, "%.3f", line.goldDebit) : "");
            h.etGoldCredit.setText(line.goldCredit > 0 ? String.format(Locale.US, "%.3f", line.goldCredit) : "");
            
            h.etLineDescription.setText(line.description);

            h.cardAccount.setOnClickListener(v -> openAccountPicker(line, h));
            h.btnRemoveLine.setOnClickListener(v -> removeLine(h.getAdapterPosition()));

            // Debit watcher
            if (h.debitWatcher != null) h.etDebit.removeTextChangedListener(h.debitWatcher);
            h.debitWatcher = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    try { line.debit = s.toString().trim().isEmpty() ? 0 : Double.parseDouble(s.toString()); } catch (Exception e) { line.debit = 0; }
                    updateTotals();
                }
            };
            h.etDebit.addTextChangedListener(h.debitWatcher);

            // Credit watcher
            if (h.creditWatcher != null) h.etCredit.removeTextChangedListener(h.creditWatcher);
            h.creditWatcher = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    try { line.credit = s.toString().trim().isEmpty() ? 0 : Double.parseDouble(s.toString()); } catch (Exception e) { line.credit = 0; }
                    updateTotals();
                }
            };
            h.etCredit.addTextChangedListener(h.creditWatcher);

            // ✅ Gold Debit watcher
            if (h.goldDebitWatcher != null) h.etGoldDebit.removeTextChangedListener(h.goldDebitWatcher);
            h.goldDebitWatcher = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    try { line.goldDebit = s.toString().trim().isEmpty() ? 0 : Double.parseDouble(s.toString()); } catch (Exception e) { line.goldDebit = 0; }
                    updateTotals();
                }
            };
            h.etGoldDebit.addTextChangedListener(h.goldDebitWatcher);

            // ✅ Gold Credit watcher
            if (h.goldCreditWatcher != null) h.etGoldCredit.removeTextChangedListener(h.goldCreditWatcher);
            h.goldCreditWatcher = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    try { line.goldCredit = s.toString().trim().isEmpty() ? 0 : Double.parseDouble(s.toString()); } catch (Exception e) { line.goldCredit = 0; }
                    updateTotals();
                }
            };
            h.etGoldCredit.addTextChangedListener(h.goldCreditWatcher);

            // Description watcher
            if (h.descWatcher != null) h.etLineDescription.removeTextChangedListener(h.descWatcher);
            h.descWatcher = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                @Override public void afterTextChanged(Editable s) { line.description = s.toString(); }
            };
            h.etLineDescription.addTextChangedListener(h.descWatcher);
        }

        @Override
        public int getItemCount() {
            return lines.size();
        }
    }

    private void openAccountPicker(LineModel line, LineVH holder) {
        AccountPickerBottomSheet picker = new AccountPickerBottomSheet();
        picker.setListener(account -> {
            line.accountId = account.id;
            line.accountCode = account.accountCode != null ? account.accountCode : "";
            line.accountName = account.accountNameAr != null ? account.accountNameAr : "";
            holder.tvAccountDisplay.setText(line.accountCode + " - " + line.accountName);
            holder.tvAccountDisplay.setTextColor(0xFF000000);
        });
        picker.show(getParentFragmentManager(), "AccountPicker");
    }

    // ═══════════════════════════════════════════════════════════
    //  ViewHolder
    // ═══════════════════════════════════════════════════════════

    static class LineVH extends RecyclerView.ViewHolder {
        TextView tvLineNumber, tvAccountDisplay;
        ImageButton btnRemoveLine;
        MaterialCardView cardAccount;
        TextInputEditText etDebit, etCredit, etLineDescription;
        TextInputEditText etGoldDebit, etGoldCredit; // ✅ الحقول الجديدة
        TextWatcher debitWatcher, creditWatcher, descWatcher;
        TextWatcher goldDebitWatcher, goldCreditWatcher; // ✅ المستمعات الجديدة

        LineVH(@NonNull View v) {
            super(v);
            tvLineNumber       = v.findViewById(R.id.tvLineNumber);
            tvAccountDisplay   = v.findViewById(R.id.tvAccountDisplay);
            btnRemoveLine      = v.findViewById(R.id.btnRemoveLine);
            cardAccount        = v.findViewById(R.id.cardAccount);
            etDebit            = v.findViewById(R.id.etDebit);
            etCredit           = v.findViewById(R.id.etCredit);
            etLineDescription  = v.findViewById(R.id.etLineDescription);
            etGoldDebit        = v.findViewById(R.id.etGoldDebit);  // ✅
            etGoldCredit       = v.findViewById(R.id.etGoldCredit); // ✅
        }
    }
}
