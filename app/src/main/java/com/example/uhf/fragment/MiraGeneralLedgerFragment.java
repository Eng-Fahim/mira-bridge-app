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
import com.example.uhf.api.dto.JournalLineDto;
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
    private List<AccountDto> accountsList = new ArrayList<>();
    private LedgerRowAdapter adapter;

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

        // تحميل الحسابات
        viewModel.loadAccounts(null, null);

        // إذا كان هناك account_id مُمرر
        if (getArguments() != null) {
            long accId = getArguments().getLong("account_id", 0);
            if (accId > 0) {
                selectedAccountId = accId;
            }
        }

        return v;
    }

    private void initViews(View v) {
        spAccount = v.findViewById(R.id.spAccount);
        btnFrom = v.findViewById(R.id.btnFrom);
        btnTo = v.findViewById(R.id.btnTo);
        btnLoad = v.findViewById(R.id.btnLoad);
        btnExport = v.findViewById(R.id.btnExport);
        kpiContainer = v.findViewById(R.id.kpiContainer);
        rvLedger = v.findViewById(R.id.rvLedger);
        pbLoading = v.findViewById(R.id.pbLoading);
        emptyState = v.findViewById(R.id.emptyState);
        tvEmptyText = v.findViewById(R.id.tvEmptyText);
    }

    private void setupViewModel() {
        viewModel = new ViewModelProvider(this).get(AccountingViewModel.class);

        // الحسابات
        viewModel.getAccounts().observe(getViewLifecycleOwner(), accounts -> {
            if (accounts != null) {
                accountsList.clear();
                for (AccountDto a : accounts) {
                    if (a.isLeaf()) accountsList.add(a);
                }
                setupAccountSpinner();
            }
        });

        // loading
        viewModel.getLoading().observe(getViewLifecycleOwner(), loading -> {
            pbLoading.setVisibility(loading != null && loading ? View.VISIBLE : View.GONE);
        });

        // error
        viewModel.getError().observe(getViewLifecycleOwner(), error -> {
            if (error != null && !error.isEmpty()) {
                Toast.makeText(requireContext(), "❌ " + error, Toast.LENGTH_LONG).show();
                viewModel.clearError();
            }
        });
    }

    private void setupAccountSpinner() {
        ArrayAdapter<AccountDto> adapter = new ArrayAdapter<AccountDto>(
            requireContext(), android.R.layout.simple_dropdown_item_1line, accountsList) {
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

        spAccount.setOnItemClickListener((parent, view, position, id) -> {
            AccountDto selected = (AccountDto) parent.getItemAtPosition(position);
            if (selected != null) {
                selectedAccountId = selected.id;
                selectedAccountCode = selected.accountCode;
                selectedAccountName = selected.accountNameAr;
            }
        });
    }

    private void setupRecycler() {
        adapter = new LedgerRowAdapter(row -> {
            // TODO: فتح تفاصيل الحركة
        });
        rvLedger.setLayoutManager(new LinearLayoutManager(getContext()));
        rvLedger.setAdapter(adapter);
    }

    private void setupListeners() {
        btnFrom.setOnClickListener(v -> pickDate(true));
        btnTo.setOnClickListener(v -> pickDate(false));

        btnLoad.setOnClickListener(v -> {
            if (selectedAccountId <= 0) {
                Toast.makeText(requireContext(), "يرجى اختيار حساب", Toast.LENGTH_SHORT).show();
                return;
            }
            loadLedger();
        });

        btnExport.setOnClickListener(v -> {
            Toast.makeText(requireContext(), "📥 جاري التصدير...", Toast.LENGTH_SHORT).show();
            // TODO: تصدير CSV
        });
    }

    private void pickDate(boolean isFrom) {
        Calendar cal = Calendar.getInstance();
        try {
            String dateStr = isFrom ? fromDate : toDate;
            String[] parts = dateStr.split("-");
            cal.set(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) - 1, Integer.parseInt(parts[2]));
        } catch (Exception ignored) {}

        new DatePickerDialog(requireContext(),
            (view, year, month, dayOfMonth) -> {
                String date = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth);
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
        // ملاحظة: نستخدم loadTrialBalance الآن، ثم نبني ledger محليًا
        // لاحقًا: viewModel.loadGeneralLedger(selectedAccountId, fromDate, toDate, 0);

        // مؤقتًا: عرض بيانات وهمية للاختبار
        pbLoading.setVisibility(View.VISIBLE);

        // للاختبار
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            pbLoading.setVisibility(View.GONE);
            renderKPIs(0, 0, 0, 0);
            showEmpty("سيتم عرض بيانات الحساب: " + selectedAccountCode + " - " + selectedAccountName);
        }, 800);
    }

    private void renderKPIs(double opening, double debit, double credit, double closing) {
        kpiContainer.removeAllViews();

        addKpi("افتتاحي", NumberFormatter.moneyShort(opening), "#3498DB");
        addKpi("مدين", NumberFormatter.moneyShort(debit), "#9B59B6");
        addKpi("دائن", NumberFormatter.moneyShort(credit), "#E74C3C");
        addKpi("ختامي", NumberFormatter.moneyShort(closing), "#27AE60");
    }

    private void addKpi(String title, String value, String color) {
        View card = LayoutInflater.from(getContext())
            .inflate(R.layout.kpi_card, kpiContainer, false);

        TextView tvTitle = card.findViewById(R.id.tvKpiTitle);
        TextView tvValue = card.findViewById(R.id.tvKpiValue);
        View indicator = card.findViewById(R.id.kpiIndicator);

        tvTitle.setText(title);
        tvValue.setText(value);
        tvValue.setTextColor(Color.parseColor(color));
        indicator.setBackgroundColor(Color.parseColor(color));

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
