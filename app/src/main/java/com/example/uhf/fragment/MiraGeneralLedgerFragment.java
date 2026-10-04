package com.example.uhf.fragment;

import android.app.DatePickerDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.adapter.LedgerRowAdapter;
import com.example.uhf.api.dto.AccountDto;
import com.example.uhf.api.dto.GeneralLedgerDto;
import com.example.uhf.utils.DateHelper;
import com.example.uhf.utils.NumberFormatter;
import com.example.uhf.viewmodel.AccountingViewModel;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * شاشة دفتر الأستاذ العام
 *
 * ✅ يعرض الحركات الفعلية من API (reports/general-ledger/{account_id})
 * ✅ يحوّل GeneralLedgerDto.LedgerRowDto إلى LedgerRowAdapter.LedgerRow
 * ✅ يعرض KPIs: افتتاحي / مدين / دائن / ختامي
 */
public class MiraGeneralLedgerFragment extends Fragment {

    private AccountingViewModel viewModel;

    // Views
    private AutoCompleteTextView spAccount;
    private MaterialButton btnFrom, btnTo, btnLoad, btnExport;
    private LinearLayout kpiContainer;
    private RecyclerView rvLedger;
    private ProgressBar pbLoading;
    private LinearLayout emptyState;
    private TextView tvEmptyText;

    // State
    private String fromDate = DateHelper.startOfYearApi();
    private String toDate = DateHelper.todayApi();
    private long selectedAccountId = 0;
    private String selectedAccountCode = "";
    private String selectedAccountName = "";
    private final List<AccountDto> accountsList = new ArrayList<>();
    private LedgerRowAdapter adapter;

    // ═══════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_general_ledger, container, false);

        initViews(v);
        setupViewModel();
        setupRecycler();
        setupListeners();
        updateDateButtons();

        // تحميل قائمة الحسابات
        viewModel.loadAccounts(null, null);

        // إذا تم تمرير account_id عبر Arguments
        if (getArguments() != null) {
            long accId = getArguments().getLong("account_id", 0);
            if (accId > 0) {
                selectedAccountId = accId;
            }
        }

        return v;
    }

    // ═══════════════════════════════════════════════════════════
    //  Initialization
    // ═══════════════════════════════════════════════════════════

    private void initViews(View v) {
        spAccount     = v.findViewById(R.id.spAccount);
        btnFrom       = v.findViewById(R.id.btnFrom);
        btnTo         = v.findViewById(R.id.btnTo);
        btnLoad       = v.findViewById(R.id.btnLoad);
        btnExport     = v.findViewById(R.id.btnExport);
        kpiContainer  = v.findViewById(R.id.kpiContainer);
        rvLedger      = v.findViewById(R.id.rvLedger);
        pbLoading     = v.findViewById(R.id.pbLoading);
        emptyState    = v.findViewById(R.id.emptyState);
        tvEmptyText   = v.findViewById(R.id.tvEmptyText);
    }

    private void setupViewModel() {
        viewModel = new ViewModelProvider(this).get(AccountingViewModel.class);

        // ─── الحسابات ───
        viewModel.getAccounts().observe(getViewLifecycleOwner(), accounts -> {
            if (accounts != null) {
                accountsList.clear();
                for (AccountDto a : accounts) {
                    if (a.isLeaf()) accountsList.add(a);
                }
                setupAccountSpinner();
            }
        });

        // ─── دفتر الأستاذ ───
        viewModel.getGeneralLedger().observe(getViewLifecycleOwner(), ledger -> {
            pbLoading.setVisibility(View.GONE);

            if (ledger == null) {
                showEmpty("لم يتم استلام بيانات");
                return;
            }

            if (ledger.rows == null || ledger.rows.isEmpty()) {
                double opening = (ledger.opening != null) ? ledger.opening.balance : 0;
                renderKPIs(opening, 0, 0, ledger.closingBalance);
                showEmpty("لا توجد حركات لهذا الحساب في الفترة المحددة");
                return;
            }

            // إجماليات المدين/الدائن
            double totalDebit = 0;
            double totalCredit = 0;
            for (GeneralLedgerDto.LedgerRowDto row : ledger.rows) {
                totalDebit  += row.baseDebit;
                totalCredit += row.baseCredit;
            }

            double opening = (ledger.opening != null) ? ledger.opening.balance : 0;
            renderKPIs(opening, totalDebit, totalCredit, ledger.closingBalance);

            // تحويل DTO → LedgerRow
            List<LedgerRowAdapter.LedgerRow> uiRows = new ArrayList<>();
            for (GeneralLedgerDto.LedgerRowDto dto : ledger.rows) {
                LedgerRowAdapter.LedgerRow row = new LedgerRowAdapter.LedgerRow();

                row.date = dto.entryDate;
                row.entryNumber = dto.entryNumber;

                // فضّل description السطر، وإن كان فارغاً استخدم وصف القيد
                if (dto.description != null && !dto.description.trim().isEmpty()) {
                    row.description = dto.description;
                } else if (dto.entryDescription != null && !dto.entryDescription.trim().isEmpty()) {
                    row.description = dto.entryDescription;
                } else {
                    row.description = "—";
                }

                row.reference      = dto.reference;
                row.baseDebit      = dto.baseDebit;
                row.baseCredit     = dto.baseCredit;
                row.runningBalance = dto.runningBalance;
                row.runningGold    = dto.runningGoldBalance;

                uiRows.add(row);
            }

            adapter.setItems(uiRows);
            showData();
        });

        // ─── Loading ───
        viewModel.getLoading().observe(getViewLifecycleOwner(), loading -> {
            pbLoading.setVisibility((loading != null && loading) ? View.VISIBLE : View.GONE);
        });

        // ─── Error ───
        viewModel.getError().observe(getViewLifecycleOwner(), error -> {
            if (error != null && !error.isEmpty()) {
                Toast.makeText(requireContext(), "❌ " + error, Toast.LENGTH_LONG).show();
                viewModel.clearError();
            }
        });
    }

    private void setupAccountSpinner() {
        ArrayAdapter<AccountDto> arrayAdapter = new ArrayAdapter<AccountDto>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                accountsList
        ) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);
                AccountDto a = getItem(position);
                if (a != null && tv != null) {
                    tv.setText(a.accountCode + " - " + a.accountNameAr);
                }
                return view;
            }

            @Override
            public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);
                AccountDto a = getItem(position);
                if (a != null && tv != null) {
                    tv.setText(a.accountCode + " - " + a.accountNameAr);
                }
                return view;
            }
        };

        spAccount.setAdapter(arrayAdapter);

        spAccount.setOnItemClickListener((parent, view, position, id) -> {
            AccountDto selected = (AccountDto) parent.getItemAtPosition(position);
            if (selected != null) {
                selectedAccountId   = selected.id;
                selectedAccountCode = selected.accountCode;
                selectedAccountName = selected.accountNameAr;
            }
        });
    }

    private void setupRecycler() {
        adapter = new LedgerRowAdapter(row -> {
            // TODO: فتح تفاصيل الحركة (قيد / فاتورة)
        });
        rvLedger.setLayoutManager(new LinearLayoutManager(getContext()));
        rvLedger.setAdapter(adapter);
    }

    private void setupListeners() {
        btnFrom.setOnClickListener(v -> pickDate(true));
        btnTo.setOnClickListener(v -> pickDate(false));

        btnLoad.setOnClickListener(v -> {
            if (selectedAccountId <= 0) {
                Toast.makeText(requireContext(),
                        "يرجى اختيار حساب", Toast.LENGTH_SHORT).show();
                return;
            }
            loadLedger();
        });

        btnExport.setOnClickListener(v -> {
            Toast.makeText(requireContext(), "📥 جاري التصدير...", Toast.LENGTH_SHORT).show();
            // TODO: تصدير CSV
        });
    }

    // ═══════════════════════════════════════════════════════════
    //  Actions
    // ═══════════════════════════════════════════════════════════

    private void pickDate(boolean isFrom) {
        Calendar cal = Calendar.getInstance();
        try {
            String dateStr = isFrom ? fromDate : toDate;
            String[] parts = dateStr.split("-");
            cal.set(Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]) - 1,
                    Integer.parseInt(parts[2]));
        } catch (Exception ignored) {}

        new DatePickerDialog(requireContext(),
                (view, year, month, dayOfMonth) -> {
                    String date = String.format(Locale.US, "%04d-%02d-%02d",
                            year, month + 1, dayOfMonth);
                    if (isFrom) {
                        fromDate = date;
                    } else {
                        toDate = date;
                    }
                    updateDateButtons();
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
        ).show();
    }

    private void updateDateButtons() {
        btnFrom.setText("من: " + DateHelper.toDisplay(fromDate));
        btnTo.setText("إلى: " + DateHelper.toDisplay(toDate));
    }

    private void loadLedger() {
        if (selectedAccountId <= 0) {
            Toast.makeText(requireContext(),
                    "يرجى اختيار حساب", Toast.LENGTH_SHORT).show();
            return;
        }

        pbLoading.setVisibility(View.VISIBLE);
        emptyState.setVisibility(View.GONE);
        rvLedger.setVisibility(View.GONE);

        viewModel.loadGeneralLedger(selectedAccountId, fromDate, toDate, 0);
    }

    // ═══════════════════════════════════════════════════════════
    //  UI Helpers
    // ═══════════════════════════════════════════════════════════

    private void renderKPIs(double opening, double debit, double credit, double closing) {
        kpiContainer.removeAllViews();

        addKpi("افتتاحي", NumberFormatter.moneyShort(opening), "#3498DB");
        addKpi("مدين",    NumberFormatter.moneyShort(debit),   "#9B59B6");
        addKpi("دائن",    NumberFormatter.moneyShort(credit),  "#E74C3C");
        addKpi("ختامي",   NumberFormatter.moneyShort(closing), "#27AE60");
    }

    private void addKpi(String title, String value, String color) {
        View card = LayoutInflater.from(getContext())
                .inflate(R.layout.kpi_card, kpiContainer, false);

        TextView tvTitle   = card.findViewById(R.id.tvKpiTitle);
        TextView tvValue   = card.findViewById(R.id.tvKpiValue);
        View indicator     = card.findViewById(R.id.kpiIndicator);

        tvTitle.setText(title);
        tvValue.setText(value);

        int c = Color.parseColor(color);
        tvValue.setTextColor(c);
        if (indicator != null) indicator.setBackgroundColor(c);

        kpiContainer.addView(card);
    }

    private void showEmpty(String message) {
        emptyState.setVisibility(View.VISIBLE);
        rvLedger.setVisibility(View.GONE);
        tvEmptyText.setText(message);
    }

    private void showData() {
        emptyState.setVisibility(View.GONE);
        rvLedger.setVisibility(View.VISIBLE);
    }
}
