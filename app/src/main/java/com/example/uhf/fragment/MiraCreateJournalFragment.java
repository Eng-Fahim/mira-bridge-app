package com.example.uhf.fragment;

import android.app.DatePickerDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.uhf.R;
import com.example.uhf.api.dto.AccountDto;
import com.example.uhf.api.dto.JournalEntryDto;
import com.example.uhf.api.dto.JournalLineDto;
import com.example.uhf.utils.DateHelper;
import com.example.uhf.utils.GoldCalculator;
import com.example.uhf.utils.NumberFormatter;
import com.example.uhf.viewmodel.AccountingViewModel;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class MiraCreateJournalFragment extends Fragment {

    private AccountingViewModel viewModel;

    // Views - الرأس
    private MaterialToolbar toolbar;
    private TextInputEditText etDate, etReference, etDescription;
    private AutoCompleteTextView spCurrency;
    private TextInputEditText etExchangeRate;

    // Views - السطور
    private LinearLayout linesContainer;
    private MaterialButton btnAddLine;

    // Views - الإجماليات
    private TextView tvTotalDebit, tvTotalCredit, tvTotalGold, tvBalanceStatus;

    // Views - الأزرار
    private MaterialButton btnSaveDraft, btnSavePosted;

    // البيانات
    private final List<JournalLineDto> lines = new ArrayList<>();
    private List<AccountDto> accounts = new ArrayList<>();
    private String selectedDate = DateHelper.todayApi();
    private String currency = "YER";
    private double exchangeRate = 1.0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_create_journal, container, false);

        initViews(view);
        setupViewModel();
        setupListeners();

        // إضافة سطر فارغ
        addEmptyLine();
        addEmptyLine();

        // تحميل الحسابات
        viewModel.loadAccounts(null, null);

        return view;
    }

    private void initViews(View v) {
        toolbar = v.findViewById(R.id.toolbar);
        etDate = v.findViewById(R.id.etDate);
        etReference = v.findViewById(R.id.etReference);
        etDescription = v.findViewById(R.id.etDescription);
        spCurrency = v.findViewById(R.id.spCurrency);
        etExchangeRate = v.findViewById(R.id.etExchangeRate);
        linesContainer = v.findViewById(R.id.linesContainer);
        btnAddLine = v.findViewById(R.id.btnAddLine);
        tvTotalDebit = v.findViewById(R.id.tvTotalDebit);
        tvTotalCredit = v.findViewById(R.id.tvTotalCredit);
        tvTotalGold = v.findViewById(R.id.tvTotalGold);
        tvBalanceStatus = v.findViewById(R.id.tvBalanceStatus);
        btnSaveDraft = v.findViewById(R.id.btnSaveDraft);
        btnSavePosted = v.findViewById(R.id.btnSavePosted);

        // تاريخ افتراضي
        etDate.setText(DateHelper.toDisplay(selectedDate));
        etDate.setFocusable(false);

        // عملة افتراضية
        String[] currencies = {"YER", "USD", "SAR"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            requireContext(), android.R.layout.simple_dropdown_item_1line, currencies);
        spCurrency.setAdapter(adapter);
        spCurrency.setText("YER", false);

        etExchangeRate.setText("1.0");
    }

    private void setupViewModel() {
        viewModel = new ViewModelProvider(this).get(AccountingViewModel.class);

        viewModel.getAccounts().observe(getViewLifecycleOwner(), accounts -> {
            if (accounts != null) {
                this.accounts = new ArrayList<>();
                for (AccountDto a : accounts) {
                    if (a.isLeaf()) this.accounts.add(a);
                }
                refreshAccountDropdowns();
            }
        });

        viewModel.getError().observe(getViewLifecycleOwner(), error -> {
            if (error != null && !error.isEmpty()) {
                Toast.makeText(requireContext(), "❌ " + error, Toast.LENGTH_LONG).show();
                viewModel.clearError();
            }
        });
    }

    private void setupListeners() {
        toolbar.setNavigationOnClickListener(v ->
            requireActivity().getSupportFragmentManager().popBackStack());

        etDate.setOnClickListener(v -> pickDate());

        spCurrency.setOnItemClickListener((parent, view, position, id) -> {
            currency = spCurrency.getText().toString();
            // إذا كانت العملة الأساسية = YER → السعر 1.0
            if ("YER".equals(currency)) {
                etExchangeRate.setText("1.0");
                etExchangeRate.setEnabled(false);
                exchangeRate = 1.0;
            } else {
                etExchangeRate.setEnabled(true);
                // TODO: جلب السعر الفعلي من API
            }
            recalculateTotals();
        });

        etExchangeRate.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                try {
                    exchangeRate = s.length() > 0 ? Double.parseDouble(s.toString()) : 1.0;
                } catch (NumberFormatException e) {
                    exchangeRate = 1.0;
                }
                recalculateTotals();
            }
        });

        btnAddLine.setOnClickListener(v -> addEmptyLine());

        btnSaveDraft.setOnClickListener(v -> saveEntry(false));
        btnSavePosted.setOnClickListener(v -> saveEntry(true));
    }

    private void pickDate() {
        Calendar cal = Calendar.getInstance();
        new DatePickerDialog(requireContext(),
            (view, year, month, dayOfMonth) -> {
                selectedDate = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth);
                etDate.setText(DateHelper.toDisplay(selectedDate));
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show();
    }

    private void addEmptyLine() {
        JournalLineDto line = new JournalLineDto();
        line.debit = 0;
        line.credit = 0;
        line.goldDebitGrams = 0;
        line.goldCreditGrams = 0;
        line.karatFactor = 1.0;
        lines.add(line);

        View lineView = createLineView(line);
        linesContainer.addView(lineView);
    }

    private View createLineView(JournalLineDto line) {
        View v = LayoutInflater.from(getContext())
            .inflate(R.layout.item_edit_journal_line, linesContainer, false);

        final int index = linesContainer.getChildCount();

        // Views
        AutoCompleteTextView spAccount = v.findViewById(R.id.spLineAccount);
        TextInputEditText etDebit = v.findViewById(R.id.etLineDebit);
        TextInputEditText etCredit = v.findViewById(R.id.etLineCredit);
        TextInputEditText etGoldDebit = v.findViewById(R.id.etLineGoldDebit);
        TextInputEditText etGoldCredit = v.findViewById(R.id.etLineGoldCredit);
        AutoCompleteTextView spKarat = v.findViewById(R.id.spLineKarat);
        TextInputEditText etWeight = v.findViewById(R.id.etLineWeight);
        MaterialButton btnRemove = v.findViewById(R.id.btnRemoveLine);

        // حساب المحاذاة
        AutoCompleteTextView spAccountForAdapter = spAccount;
        ArrayAdapter<AccountDto> adapter = new ArrayAdapter<AccountDto>(
            requireContext(), android.R.layout.simple_dropdown_item_1line, accounts) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);
                AccountDto a = getItem(position);
                if (a != null) {
                    tv.setText(a.accountCode + " - " + a.accountNameAr);
                }
                return view;
            }
        };
        spAccountForAdapter.setAdapter(adapter);

        spAccountForAdapter.setOnItemClickListener((parent, view1, position, id) -> {
            AccountDto selected = (AccountDto) parent.getItemAtPosition(position);
            if (selected != null) {
                line.accountId = selected.id;
                line.accountCode = selected.accountCode;
                line.accountNameAr = selected.accountNameAr;
            }
        });

        // العيارات
        String[] karats = {"24K", "22K", "21K", "18K", "14K"};
        ArrayAdapter<String> karatAdapter = new ArrayAdapter<>(
            requireContext(), android.R.layout.simple_dropdown_item_1line, karats);
        spKarat.setAdapter(karatAdapter);
        spKarat.setText("21K", false);

        // TextWatchers
        etDebit.addTextChangedListener(simpleWatcher(s -> {
            try { line.debit = s.isEmpty() ? 0 : Double.parseDouble(s); }
            catch (NumberFormatException e) { line.debit = 0; }
            recalculateTotals();
        }));

        etCredit.addTextChangedListener(simpleWatcher(s -> {
            try { line.credit = s.isEmpty() ? 0 : Double.parseDouble(s); }
            catch (NumberFormatException e) { line.credit = 0; }
            recalculateTotals();
        }));

        etGoldDebit.addTextChangedListener(simpleWatcher(s -> {
            try { line.goldDebitGrams = s.isEmpty() ? 0 : Double.parseDouble(s); }
            catch (NumberFormatException e) { line.goldDebitGrams = 0; }
            recalculateTotals();
        }));

        etGoldCredit.addTextChangedListener(simpleWatcher(s -> {
            try { line.goldCreditGrams = s.isEmpty() ? 0 : Double.parseDouble(s); }
            catch (NumberFormatException e) { line.goldCreditGrams = 0; }
            recalculateTotals();
        }));

        // العيار → حساب factor
        spKarat.setOnItemClickListener((parent, view1, position, id) -> {
            String karat = (String) parent.getItemAtPosition(position);
            line.originalKarat = karat;
            line.karatFactor = GoldCalculator.getKaratFactor(karat);
            recalculateTotals();
        });

        etWeight.addTextChangedListener(simpleWatcher(s -> {
            try {
                double weight = s.isEmpty() ? 0 : Double.parseDouble(s);
                line.originalWeightGrams = weight;
                // حساب الوزن المكافئ عيار 24
                if (line.originalKarat != null) {
                    double weight24k = GoldCalculator.to24K(weight, line.originalKarat);
                    // نضعه في حقل الذهب حسب debit/credit
                    if (line.debit > 0) {
                        line.goldDebitGrams = weight24k;
                        etGoldDebit.setText(String.format(Locale.US, "%.4f", weight24k));
                    } else if (line.credit > 0) {
                        line.goldCreditGrams = weight24k;
                        etGoldCredit.setText(String.format(Locale.US, "%.4f", weight24k));
                    }
                }
            } catch (NumberFormatException e) {
                line.originalWeightGrams = 0;
            }
            recalculateTotals();
        }));

        // زر الحذف
        btnRemove.setOnClickListener(v1 -> {
            if (lines.size() <= 2) {
                Toast.makeText(requireContext(), "يجب أن يكون هناك سطران على الأقل", Toast.LENGTH_SHORT).show();
                return;
            }
            int idx = linesContainer.indexOfChild(v);
            if (idx >= 0 && idx < lines.size()) {
                lines.remove(idx);
                linesContainer.removeViewAt(idx);
                recalculateTotals();
            }
        });

        return v;
    }

    private TextWatcher simpleWatcher(OnTextChange callback) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                callback.onChange(s.toString());
            }
        };
    }

    private interface OnTextChange {
        void onChange(String text);
    }

    private void refreshAccountDropdowns() {
        for (int i = 0; i < linesContainer.getChildCount(); i++) {
            View lineView = linesContainer.getChildAt(i);
            AutoCompleteTextView spAccount = lineView.findViewById(R.id.spLineAccount);
            if (spAccount != null) {
                ArrayAdapter<AccountDto> adapter = new ArrayAdapter<AccountDto>(
                    requireContext(), android.R.layout.simple_dropdown_item_1line, accounts) {
                    @NonNull
                    @Override
                    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                        View view = super.getView(position, convertView, parent);
                        TextView tv = view.findViewById(android.R.id.text1);
                        AccountDto a = getItem(position);
                        if (a != null) {
                            tv.setText(a.accountCode + " - " + a.accountNameAr);
                        }
                        return view;
                    }
                };
                spAccount.setAdapter(adapter);
            }
        }
    }

    private void recalculateTotals() {
        double totalDebit = 0;
        double totalCredit = 0;
        double totalGoldDebit = 0;
        double totalGoldCredit = 0;

        for (JournalLineDto line : lines) {
            totalDebit += line.debit;
            totalCredit += line.credit;
            totalGoldDebit += line.goldDebitGrams;
            totalGoldCredit += line.goldCreditGrams;
        }

        tvTotalDebit.setText(NumberFormatter.money(totalDebit * exchangeRate));
        tvTotalCredit.setText(NumberFormatter.money(totalCredit * exchangeRate));

        double netGold = totalGoldDebit - totalGoldCredit;
        tvTotalGold.setText(NumberFormatter.goldSigned(netGold));

        boolean balanced = Math.abs(totalDebit - totalCredit) < 0.01;
        tvBalanceStatus.setText(balanced ? "✅ متوازن" : "❌ غير متوازن (فرق: "
            + NumberFormatter.money(totalDebit - totalCredit) + ")");
        tvBalanceStatus.setTextColor(balanced
            ? Color.parseColor("#27AE60") : Color.parseColor("#C0392B"));

        btnSaveDraft.setEnabled(balanced);
        btnSavePosted.setEnabled(balanced);
    }

    private void saveEntry(boolean postImmediately) {
        // التحقق من الحقول الأساسية
        if (etDescription.getText() == null || etDescription.getText().toString().trim().isEmpty()) {
            Toast.makeText(requireContext(), "يرجى إدخال البيان", Toast.LENGTH_SHORT).show();
            return;
        }

        if (lines.size() < 2) {
            Toast.makeText(requireContext(), "يجب أن يكون هناك سطران على الأقل", Toast.LENGTH_SHORT).show();
            return;
        }

        // التحقق من السطور
        for (int i = 0; i < lines.size(); i++) {
            JournalLineDto line = lines.get(i);
            if (line.accountId <= 0) {
                Toast.makeText(requireContext(), "السطر " + (i + 1) + ": يرجى اختيار حساب", Toast.LENGTH_LONG).show();
                return;
            }
            if (line.debit == 0 && line.credit == 0
                && line.goldDebitGrams == 0 && line.goldCreditGrams == 0) {
                Toast.makeText(requireContext(), "السطر " + (i + 1) + ": لا يوجد مبلغ أو ذهب", Toast.LENGTH_LONG).show();
                return;
            }
        }

        // بناء القيد
        JournalEntryDto entry = new JournalEntryDto();
        entry.entryDate = selectedDate;
        entry.branchId = 1;
        entry.reference = etReference.getText() != null ? etReference.getText().toString() : "";
        entry.description = etDescription.getText().toString();
        entry.sourceType = "manual";
        entry.currency = currency;
        entry.exchangeRate = exchangeRate;
        entry.lines = lines;

        // حساب الإجماليات
        double totalDebit = 0, totalCredit = 0, totalGoldD = 0, totalGoldC = 0;
        for (JournalLineDto l : lines) {
            totalDebit += l.debit;
            totalCredit += l.credit;
            totalGoldD += l.goldDebitGrams;
            totalGoldC += l.goldCreditGrams;
        }
        entry.totalDebit = totalDebit;
        entry.totalCredit = totalCredit;
        entry.totalDebitBase = totalDebit * exchangeRate;
        entry.totalCreditBase = totalCredit * exchangeRate;
        entry.totalGoldDebitGrams = totalGoldD;
        entry.totalGoldCreditGrams = totalGoldC;

        // الحفظ
        Toast.makeText(requireContext(),
            postImmediately ? "✅ جاري الحفظ والاعتماد..." : "💾 جاري حفظ مسودة...",
            Toast.LENGTH_SHORT).show();

        // TODO: إرسال للـ API
        // viewModel.createJournalEntry(entry, postImmediately);
    }
}
