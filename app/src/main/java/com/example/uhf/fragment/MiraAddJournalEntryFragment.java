package com.example.uhf.fragment;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.uhf.R;
import com.example.uhf.api.dto.AccountDto;
import com.example.uhf.utils.DateHelper;
import com.example.uhf.viewmodel.AccountingViewModel;
import com.google.android.material.dialog.MaterialAlertDialogBuilder; // ✅ استيراد المكتبة

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class MiraAddJournalEntryFragment extends Fragment {

    private AccountingViewModel viewModel;
    private EditText etDate, etReference, etDescription;
    private LinearLayout linesContainer;
    private Button btnAddLine, btnSave;
    private ImageView btnClose;
    private TextView tvTotals;

    private List<AccountDto> allAccounts = new ArrayList<>();
    private List<View> lineViews = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_add_journal_entry, container, false);

        viewModel = new ViewModelProvider(this).get(AccountingViewModel.class);

        etDate = v.findViewById(R.id.etDate);
        etReference = v.findViewById(R.id.etReference);
        etDescription = v.findViewById(R.id.etDescription);
        linesContainer = v.findViewById(R.id.linesContainer);
        btnAddLine = v.findViewById(R.id.btnAddLine);
        btnSave = v.findViewById(R.id.btnSave);
        btnClose = v.findViewById(R.id.btnClose);
        tvTotals = v.findViewById(R.id.tvTotals);

        etDate.setText(DateHelper.todayApi());
        etDate.setOnClickListener(view -> showDatePicker());

        // ✅ استقبال الأخطاء من السيرفر وعرضها بنافذة احترافية
        viewModel.getError().observe(getViewLifecycleOwner(), error -> {
            if (error != null && !error.isEmpty()) {
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle("⛔ عملية مرفوضة")
                        .setMessage(error)
                        .setPositiveButton("حسناً، فهمت", (dialog, which) -> dialog.dismiss())
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setCancelable(false)
                        .show();

                btnSave.setEnabled(true);
                btnSave.setText("حفظ وترحيل القيد");
                viewModel.clearError();
            }
        });

        viewModel.getAccounts().observe(getViewLifecycleOwner(), accounts -> {
            if (accounts != null && accounts.length > 0) {
                allAccounts.clear();
                for (AccountDto a : accounts) allAccounts.add(a);
                for (View lineView : lineViews) setupAccountDropdown(lineView);
            }
        });
        viewModel.loadAccounts(null, null, true);

        addLine();
        addLine();

        btnAddLine.setOnClickListener(view -> addLine());
        btnClose.setOnClickListener(view -> getParentFragmentManager().popBackStack());
        btnSave.setOnClickListener(view -> saveEntry());

        return v;
    }

    private void showDatePicker() {
        Calendar cal = Calendar.getInstance();
        new DatePickerDialog(requireContext(), (view, year, month, dayOfMonth) -> {
            etDate.setText(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth));
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void addLine() {
        View lineView = LayoutInflater.from(getContext()).inflate(R.layout.item_entry_line, linesContainer, false);
        linesContainer.addView(lineView);
        lineViews.add(lineView);

        setupAccountDropdown(lineView);

        EditText etDebit = lineView.findViewById(R.id.etDebit);
        EditText etCredit = lineView.findViewById(R.id.etCredit);
        EditText etGoldDebit = lineView.findViewById(R.id.etGoldDebit);
        EditText etGoldCredit = lineView.findViewById(R.id.etGoldCredit);

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { calculateTotals(); }
            @Override public void afterTextChanged(Editable s) {}
        };

        etDebit.addTextChangedListener(watcher);
        etCredit.addTextChangedListener(watcher);
        etGoldDebit.addTextChangedListener(watcher);
        etGoldCredit.addTextChangedListener(watcher);

        ImageView btnRemove = lineView.findViewById(R.id.btnRemoveLine);
        btnRemove.setOnClickListener(view -> {
            if (lineViews.size() <= 2) {
                Toast.makeText(getContext(), "يجب أن يحتوي القيد على سطرين على الأقل", Toast.LENGTH_SHORT).show();
                return;
            }
            linesContainer.removeView(lineView);
            lineViews.remove(lineView);
            calculateTotals();
        });
    }

    private void setupAccountDropdown(View lineView) {
        AutoCompleteTextView acAccount = lineView.findViewById(R.id.acAccount);
        ArrayAdapter<AccountDto> adapter = new ArrayAdapter<AccountDto>(requireContext(),
                android.R.layout.simple_dropdown_item_1line, allAccounts) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                AccountDto a = getItem(position);
                if (a != null) tv.setText(a.accountCode + " - " + a.accountNameAr);
                return tv;
            }

            @Override
            public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                TextView tv = (TextView) super.getDropDownView(position, convertView, parent);
                AccountDto a = getItem(position);
                if (a != null) tv.setText(a.accountCode + " - " + a.accountNameAr);
                return tv;
            }
        };
        acAccount.setAdapter(adapter);
        acAccount.setThreshold(0);
        acAccount.setOnClickListener(v -> acAccount.showDropDown());
        acAccount.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) acAccount.showDropDown();
        });

        acAccount.setOnItemClickListener((parent, view, position, id) -> {
            AccountDto selected = (AccountDto) parent.getItemAtPosition(position);
            acAccount.setTag(selected);
        });

        acAccount.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                acAccount.setTag(null); 
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void calculateTotals() {
        double tDebit = 0, tCredit = 0, tGoldDebit = 0, tGoldCredit = 0;

        for (View lineView : lineViews) {
            tDebit += parseDouble(((EditText) lineView.findViewById(R.id.etDebit)).getText().toString());
            tCredit += parseDouble(((EditText) lineView.findViewById(R.id.etCredit)).getText().toString());
            tGoldDebit += parseDouble(((EditText) lineView.findViewById(R.id.etGoldDebit)).getText().toString());
            tGoldCredit += parseDouble(((EditText) lineView.findViewById(R.id.etGoldCredit)).getText().toString());
        }

        String text = String.format(Locale.US, "مدين: %.2f | دائن: %.2f", tDebit, tCredit);
        if (tGoldDebit > 0 || tGoldCredit > 0) {
            text += String.format(Locale.US, "\nذهب: %.3fg | %.3fg", tGoldDebit, tGoldCredit);
        }
        
        tvTotals.setText(text);
        boolean balanced = Math.abs(tDebit - tCredit) < 0.01 && Math.abs(tGoldDebit - tGoldCredit) < 0.001;
        tvTotals.setTextColor(balanced ? 0xFF27AE60 : 0xFF3498DB);
    }

    private void saveEntry() {
        String date = etDate.getText().toString();
        String ref = etReference.getText().toString();
        String desc = etDescription.getText().toString();

        if (desc.isEmpty()) {
            Toast.makeText(getContext(), "يرجى إدخال بيان القيد", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            JSONArray linesArray = new JSONArray();
            double tDebit = 0, tCredit = 0, tGoldDebit = 0, tGoldCredit = 0;

            for (View lineView : lineViews) {
                AutoCompleteTextView acAccount = lineView.findViewById(R.id.acAccount);
                
                AccountDto selectedAccount = null;
                if (acAccount.getTag() instanceof AccountDto) {
                    selectedAccount = (AccountDto) acAccount.getTag();
                } else {
                    for (AccountDto a : allAccounts) {
                        if (acAccount.getText().toString().equals(a.accountCode + " - " + a.accountNameAr)) {
                            selectedAccount = a;
                            break;
                        }
                    }
                }

                if (selectedAccount == null) {
                    Toast.makeText(getContext(), "يرجى اختيار حساب صحيح من القائمة لكل سطر", Toast.LENGTH_LONG).show();
                    return;
                }

                double debit = parseDouble(((EditText) lineView.findViewById(R.id.etDebit)).getText().toString());
                double credit = parseDouble(((EditText) lineView.findViewById(R.id.etCredit)).getText().toString());
                double goldDebit = parseDouble(((EditText) lineView.findViewById(R.id.etGoldDebit)).getText().toString());
                double goldCredit = parseDouble(((EditText) lineView.findViewById(R.id.etGoldCredit)).getText().toString());

                JSONObject lineObj = new JSONObject();
                lineObj.put("account_id", selectedAccount.id);
                lineObj.put("description", desc);
                lineObj.put("debit", debit);
                lineObj.put("credit", credit);
                lineObj.put("gold_debit_grams", goldDebit); 
                lineObj.put("gold_credit_grams", goldCredit); 
                linesArray.put(lineObj);

                tDebit += debit; tCredit += credit;
                tGoldDebit += goldDebit; tGoldCredit += goldCredit;
            }

            if (Math.abs(tDebit - tCredit) > 0.01) {
                Toast.makeText(getContext(), "❌ القيد غير متوازن مالياً!", Toast.LENGTH_LONG).show();
                return;
            }
            if (Math.abs(tGoldDebit - tGoldCredit) > 0.001) {
                Toast.makeText(getContext(), "❌ القيد غير متوازن وزنياً!", Toast.LENGTH_LONG).show();
                return;
            }

            JSONObject payload = new JSONObject();
            payload.put("entry_date", date);
            payload.put("reference", ref);
            payload.put("description", desc);
            payload.put("source_type", "manual");
            payload.put("lines", linesArray);

            btnSave.setEnabled(false);
            btnSave.setText("جاري الحفظ...");
            
            // ✅ شبكة أمان: إلغاء تعليق الزر بعد 15 ثانية إذا لم يستجب السيرفر
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (!btnSave.isEnabled()) {
                    btnSave.setEnabled(true);
                    btnSave.setText("حفظ وترحيل القيد");
                    Toast.makeText(getContext(), "⚠️ لم يستجب السيرفر، يرجى المحاولة مرة أخرى", Toast.LENGTH_LONG).show();
                }
            }, 15000);

            // ✅ [إصلاح حرج]: حذف الاستدعاء المكرر الذي كان يحفظ القيد مرتين!
            viewModel.createJournalEntry(payload, () -> {
                Toast.makeText(getContext(), "✅ تم حفظ القيد بنجاح", Toast.LENGTH_SHORT).show();
                getParentFragmentManager().popBackStack();
            });

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(getContext(), "حدث خطأ غير متوقع أثناء الحفظ", Toast.LENGTH_SHORT).show();
            btnSave.setEnabled(true);
            btnSave.setText("حفظ وترحيل القيد");
        }
    }

    private double parseDouble(String val) {
        if (val == null || val.isEmpty()) return 0;
        try { return Double.parseDouble(val); } catch (Exception e) { return 0; }
    }
}
