package com.example.uhf.fragment;

import android.app.DatePickerDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.adapter.LedgerRowAdapter;
import com.example.uhf.api.dto.AccountDto;
import com.example.uhf.api.dto.GeneralLedgerDto;
import com.example.uhf.utils.DateHelper;
import com.example.uhf.utils.LedgerCsvExporter;
import com.example.uhf.utils.NumberFormatter;
import com.example.uhf.viewmodel.AccountingViewModel;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class MiraGeneralLedgerFragment extends Fragment {

    private static final String PREFS_FILTERS    = "ledger_filters";
    private static final String KEY_LAST_ACCOUNT = "last_account_id";
    private static final String KEY_FROM_DATE    = "from_date";
    private static final String KEY_TO_DATE      = "to_date";
    private static final int    PAGE_SIZE        = 100;
    private static final long   DEBOUNCE_MS      = 350;

    private AutoCompleteTextView spAccount;
    private MaterialButton btnFrom, btnTo, btnLoad, btnExport;
    private LinearLayout kpiContainer;
    private RecyclerView rvLedger;
    private ProgressBar pbLoading;
    private LinearLayout emptyState;
    private TextView tvEmptyText;
    private SwipeRefreshLayout swipeRefresh;

    private AccountingViewModel viewModel;

    private String  fromDate            = DateHelper.startOfYearApi();
    private String  toDate              = DateHelper.todayApi();
    private long    selectedAccountId   = 0;
    private String  selectedAccountCode = "";
    private String  selectedAccountName = "";

    private final List<AccountDto> accountsList = new ArrayList<>();
    private ArrayAdapter<AccountDto> accountAdapter;
    private LedgerRowAdapter adapter;

    private double currentOpening     = 0;
    private double currentClosing     = 0;
    private double currentTotalDebit  = 0;
    private double currentTotalCredit = 0;

    private int     currentPage   = 1;
    private boolean isLoadingMore = false;
    private boolean hasMorePages  = true;

    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;
    private boolean isProgrammaticTextChange = false; 

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_general_ledger, container, false);

        if (getArguments() != null) {
            long accId = getArguments().getLong("account_id", 0);
            if (accId > 0) selectedAccountId = accId;
        }

        initViews(v);
        setupSpinnerOnce();
        setupViewModel();
        setupRecycler();
        setupListeners();

        restoreFilters();
        updateDateButtons();

        viewModel.loadAccounts(null, null, null);

        return v;
    }

    // ✅ الدالة التي يبحث عنها MiraAccountingFragment
    public void setAccountId(long accountId) {
        if (this.selectedAccountId != accountId) {
            this.selectedAccountId = accountId;
            if (isAdded() && getView() != null) {
                for (AccountDto a : accountsList) {
                    if (a.id == accountId) {
                        selectedAccountCode = a.accountCode;
                        selectedAccountName = a.accountNameAr;
                        isProgrammaticTextChange = true;
                        spAccount.setText(a.accountCode + " - " + a.accountNameAr, false);
                        isProgrammaticTextChange = false;
                        break;
                    }
                }
                loadLedger();
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (debounceHandler != null && pendingSearch != null) {
            debounceHandler.removeCallbacks(pendingSearch);
            pendingSearch = null;
        }
        if (rvLedger != null) {
            rvLedger.setAdapter(null);
        }
    }

    private void initViews(View v) {
        spAccount      = v.findViewById(R.id.spAccount);
        btnFrom        = v.findViewById(R.id.btnFrom);
        btnTo          = v.findViewById(R.id.btnTo);
        btnLoad        = v.findViewById(R.id.btnLoad);
        btnExport      = v.findViewById(R.id.btnExport);
        kpiContainer   = v.findViewById(R.id.kpiContainer);
        rvLedger       = v.findViewById(R.id.rvLedger);
        pbLoading      = v.findViewById(R.id.pbLoading);
        emptyState     = v.findViewById(R.id.emptyState);
        tvEmptyText    = v.findViewById(R.id.tvEmptyText);
        swipeRefresh   = v.findViewById(R.id.swipeRefresh);
    }

    private void setupSpinnerOnce() {
        accountAdapter = new ArrayAdapter<AccountDto>(
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
                    String prefix = a.isLeaf() ? "" : "📁 ";
                    tv.setText(prefix + a.accountCode + " - " + a.accountNameAr);
                }
                return view;
            }

            @Override
            public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);
                AccountDto a = getItem(position);
                if (a != null && tv != null) {
                    String prefix = a.isLeaf() ? "" : "📁 ";
                    tv.setText(prefix + a.accountCode + " - " + a.accountNameAr);
                }
                return view;
            }
        };

        spAccount.setAdapter(accountAdapter);

        spAccount.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (isProgrammaticTextChange) return;

                String query = s.toString().trim();

                if (query.isEmpty() || !query.equals(selectedAccountCode + " - " + selectedAccountName)) {
                    selectedAccountId = 0;
                    selectedAccountCode = "";
                    selectedAccountName = "";
                }

                if (pendingSearch != null) debounceHandler.removeCallbacks(pendingSearch);
                
                pendingSearch = () -> {
                    if (query.length() >= 2) {
                        viewModel.loadAccounts(null, query, null);
                    } else if (query.isEmpty()) {
                        viewModel.loadAccounts(null, null, null);
                    }
                };
                debounceHandler.postDelayed(pendingSearch, DEBOUNCE_MS);
            }
        });

        spAccount.setOnItemClickListener((parent, view, position, id) -> {
            AccountDto selected = (AccountDto) parent.getItemAtPosition(position);
            if (selected != null) {
                selectedAccountId   = selected.id;
                selectedAccountCode = selected.accountCode;
                selectedAccountName = selected.accountNameAr;
                
                isProgrammaticTextChange = true;
                spAccount.setText(selected.accountCode + " - " + selected.accountNameAr, false);
                isProgrammaticTextChange = false;

                spAccount.dismissDropDown();
                saveFilters();
                
                loadLedger();
            }
        });
    }

    private void setupViewModel() {
        viewModel = new ViewModelProvider(this).get(AccountingViewModel.class);

        viewModel.getAccounts().observe(getViewLifecycleOwner(), accounts -> {
            if (accounts == null) return;

            accountsList.clear();
            
            // ✅ إصلاح خطأ addAll (المصفوفة Array لا تقبل addAll مباشرة)
            for (AccountDto a : accounts) {
                accountsList.add(a);
            }
            
            accountAdapter.notifyDataSetChanged();

            if (selectedAccountId > 0 && !accountsList.isEmpty() && spAccount.getText().toString().isEmpty()) {
                AccountDto matched = null;
                for (AccountDto a : accountsList) {
                    if (a.id == selectedAccountId) {
                        matched = a;
                        break;
                    }
                }

                if (matched != null) {
                    selectedAccountCode = matched.accountCode;
                    selectedAccountName = matched.accountNameAr;
                    
                    isProgrammaticTextChange = true;
                    spAccount.setText(matched.accountCode + " - " + matched.accountNameAr, false);
                    isProgrammaticTextChange = false;
                } else {
                    selectedAccountCode = "";
                    selectedAccountName = "حساب #" + selectedAccountId;
                    isProgrammaticTextChange = true;
                    spAccount.setText("حساب #" + selectedAccountId, false);
                    isProgrammaticTextChange = false;
                }

                spAccount.post(this::loadLedger);
            }
        });

        viewModel.getGeneralLedger().observe(getViewLifecycleOwner(), ledger -> {
            pbLoading.setVisibility(View.GONE);
            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            isLoadingMore = false;

            if (ledger == null) {
                showEmpty("لم يتم استلام بيانات");
                return;
            }

            if (ledger.rows == null || ledger.rows.isEmpty()) {
                double opening = (ledger.opening != null) ? ledger.opening.balance : 0;
                renderKPIs(opening, 0, 0, ledger.closingBalance);
                if (currentPage > 1) {
                    hasMorePages = false;
                } else {
                    showEmpty("لا توجد حركات لهذا الحساب في الفترة المحددة");
                }
                return;
            }

            hasMorePages = (ledger.rows.size() >= PAGE_SIZE);

            double totalDebit  = 0;
            double totalCredit = 0;
            for (GeneralLedgerDto.LedgerRowDto row : ledger.rows) {
                totalDebit  += row.baseDebit;
                totalCredit += row.baseCredit;
            }

            double opening = (ledger.opening != null) ? ledger.opening.balance : 0;
            renderKPIs(opening, totalDebit, totalCredit, ledger.closingBalance);

            List<LedgerRowAdapter.LedgerRow> uiRows = new ArrayList<>();
            for (GeneralLedgerDto.LedgerRowDto dto : ledger.rows) {
                LedgerRowAdapter.LedgerRow row = new LedgerRowAdapter.LedgerRow();
                row.entryId     = dto.entryId;
                row.date        = dto.entryDate;
                row.entryNumber = dto.entryNumber;

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

            if (currentPage == 1) {
                adapter.setItems(uiRows);
            } else {
                adapter.appendItems(uiRows);
            }

            showData();
        });

        viewModel.getLoading().observe(getViewLifecycleOwner(), loading -> {
            boolean isLoading = (loading != null && loading);
            if (!isLoadingMore) {
                pbLoading.setVisibility(isLoading ? View.VISIBLE : View.GONE);
            }
        });

        viewModel.getError().observe(getViewLifecycleOwner(), error -> {
            if (error != null && !error.isEmpty()) {
                Toast.makeText(requireContext(), "❌ " + error, Toast.LENGTH_LONG).show();
                viewModel.clearError();
            }
        });
    }

    private void setupRecycler() {
        adapter = new LedgerRowAdapter(row -> {
            if (row.entryId > 0) {
                MiraJournalEntryDetailsFragment details =
                    MiraJournalEntryDetailsFragment.newInstance(row.entryId);

                if (getActivity() instanceof UHFMainActivity) {
                    ((UHFMainActivity) getActivity()).loadFragment(details, "JournalEntryDetails");
                } else {
                    requireActivity().getSupportFragmentManager().beginTransaction()
                        .replace(R.id.fragment_container, details)
                        .addToBackStack("JournalEntryDetails")
                        .commit();
                }
            } else {
                Toast.makeText(requireContext(), "معرف القيد مفقود", Toast.LENGTH_SHORT).show();
            }
        });

        LinearLayoutManager lm = new LinearLayoutManager(getContext());
        rvLedger.setLayoutManager(lm);
        rvLedger.setAdapter(adapter);

        rvLedger.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (dy <= 0) return;
                if (isLoadingMore || !hasMorePages) return;
                if (selectedAccountId <= 0) return;

                int visibleCount = lm.getChildCount();
                int totalCount   = lm.getItemCount();
                int firstVisible = lm.findFirstVisibleItemPosition();

                if (firstVisible + visibleCount >= totalCount - 5) {
                    loadNextPage();
                }
            }
        });
    }

    private void loadNextPage() {
        if (isLoadingMore || !hasMorePages || selectedAccountId <= 0) return;
        isLoadingMore = true;
        currentPage++;
        pbLoading.setVisibility(View.VISIBLE);
        viewModel.loadGeneralLedger(selectedAccountId, fromDate, toDate, 0, currentPage);
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
            if (selectedAccountId <= 0) {
                Toast.makeText(requireContext(), "اختر حساباً أولاً", Toast.LENGTH_SHORT).show();
                return;
            }

            List<LedgerRowAdapter.LedgerRow> currentRows = adapter.getItems();
            if (currentRows == null || currentRows.isEmpty()) {
                Toast.makeText(requireContext(), "لا توجد بيانات للتصدير", Toast.LENGTH_SHORT).show();
                return;
            }

            Uri uri = LedgerCsvExporter.exportAndShare(
                requireContext(), currentRows, selectedAccountCode, selectedAccountName,
                fromDate, toDate, currentOpening, currentClosing
            );

            if (uri != null) {
                Toast.makeText(requireContext(), "📥 تم إنشاء الملف", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(requireContext(), "❌ فشل التصدير", Toast.LENGTH_SHORT).show();
            }
        });

        if (swipeRefresh != null) {
            swipeRefresh.setColorSchemeResources(R.color.mira_gold, android.R.color.holo_blue_bright, android.R.color.holo_green_light);
            swipeRefresh.setOnRefreshListener(() -> {
                if (selectedAccountId > 0) {
                    loadLedger();
                } else {
                    swipeRefresh.setRefreshing(false);
                    Toast.makeText(requireContext(), "اختر حساباً أولاً", Toast.LENGTH_SHORT).show();
                }
            });
        }
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
                    if (isFrom) fromDate = date; else toDate = date;
                    updateDateButtons();
                    saveFilters();
                },
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show();
    }

    private void updateDateButtons() {
        btnFrom.setText("من: " + DateHelper.toDisplay(fromDate));
        btnTo.setText("إلى: " + DateHelper.toDisplay(toDate));
    }

    private void loadLedger() {
        if (selectedAccountId <= 0) {
            Toast.makeText(requireContext(), "يرجى اختيار حساب", Toast.LENGTH_SHORT).show();
            return;
        }

        currentPage   = 1;
        hasMorePages  = true;
        isLoadingMore = false;

        adapter.setItems(new ArrayList<>());
        pbLoading.setVisibility(View.VISIBLE);
        emptyState.setVisibility(View.GONE);
        rvLedger.setVisibility(View.GONE);

        if (swipeRefresh != null) swipeRefresh.setRefreshing(true);

        viewModel.loadGeneralLedger(selectedAccountId, fromDate, toDate, 0, currentPage);
    }

    private void renderKPIs(double opening, double debit, double credit, double closing) {
        currentOpening     = opening;
        currentClosing     = closing;
        currentTotalDebit  = debit;
        currentTotalCredit = credit;

        kpiContainer.removeAllViews();
        addKpi("افتتاحي", NumberFormatter.moneyShort(opening), "#3498DB");
        addKpi("مدين",    NumberFormatter.moneyShort(debit),   "#9B59B6");
        addKpi("دائن",    NumberFormatter.moneyShort(credit),  "#E74C3C");
        addKpi("ختامي",   NumberFormatter.moneyShort(closing), "#27AE60");
    }

    private void addKpi(String title, String value, String color) {
        if (!isAdded()) return;
        View card = LayoutInflater.from(requireContext()).inflate(R.layout.kpi_card, kpiContainer, false);
        TextView tvTitle = card.findViewById(R.id.tvKpiTitle);
        TextView tvValue = card.findViewById(R.id.tvKpiValue);
        View indicator   = card.findViewById(R.id.kpiIndicator);

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

    private void saveFilters() {
        Context ctx = getContext();
        if (ctx == null) return;

        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_FILTERS, Context.MODE_PRIVATE);
        prefs.edit()
            .putLong(KEY_LAST_ACCOUNT, selectedAccountId)
            .putString(KEY_FROM_DATE, fromDate)
            .putString(KEY_TO_DATE, toDate)
            .apply();
    }

    private void restoreFilters() {
        Context ctx = getContext();
        if (ctx == null) return;

        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_FILTERS, Context.MODE_PRIVATE);
        String savedFrom = prefs.getString(KEY_FROM_DATE, null);
        String savedTo   = prefs.getString(KEY_TO_DATE, null);

        if (savedFrom != null && !savedFrom.isEmpty()) fromDate = savedFrom;
        if (savedTo != null && !savedTo.isEmpty())     toDate   = savedTo;

        if (selectedAccountId <= 0) {
            long lastAccount = prefs.getLong(KEY_LAST_ACCOUNT, 0);
            if (lastAccount > 0) selectedAccountId = lastAccount;
        }
    }
}
