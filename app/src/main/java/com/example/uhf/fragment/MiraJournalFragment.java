package com.example.uhf.fragment;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import com.example.uhf.adapter.JournalEntryAdapter;
import com.example.uhf.utils.DateHelper;
import com.example.uhf.viewmodel.AccountingViewModel;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Locale;

public class MiraJournalFragment extends Fragment {

    private AccountingViewModel viewModel;
    private JournalEntryAdapter adapter;

    private MaterialButton btnFromDate, btnToDate;
    private ChipGroup chipStatusGroup;
    private Chip chipAll, chipPosted, chipDraft, chipReversed;
    private RecyclerView rvEntries;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar pbLoading;
    private LinearLayout emptyState;
    private TextView tvEmptyText;
    private FloatingActionButton fabAddEntry; // ✅ متغير الزر

    private String fromDate = DateHelper.startOfYearApi();
    private String toDate   = DateHelper.todayApi();
    private String statusFilter = null;
    private int currentPage = 1;
    private final int PER_PAGE = 50;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mira_journal, container, false);

        initViews(view);
        setupViewModel();
        setupRecycler();
        setupListeners();
        loadEntries();

        return view;
    }

    // ✅ تحديث القائمة تلقائياً عند العودة من شاشة إضافة القيد
    @Override
    public void onResume() {
        super.onResume();
        if (viewModel != null) {
            loadEntries();
        }
    }

    private void initViews(View v) {
        btnFromDate     = v.findViewById(R.id.btnFromDate);
        btnToDate       = v.findViewById(R.id.btnToDate);
        chipStatusGroup = v.findViewById(R.id.chipStatusGroup);
        chipAll         = v.findViewById(R.id.chipAll);
        chipPosted      = v.findViewById(R.id.chipPosted);
        chipDraft       = v.findViewById(R.id.chipDraft);
        chipReversed    = v.findViewById(R.id.chipReversed);
        rvEntries       = v.findViewById(R.id.rvEntries);
        swipeRefresh    = v.findViewById(R.id.swipeRefresh);
        pbLoading       = v.findViewById(R.id.pbLoading);
        emptyState      = v.findViewById(R.id.emptyState);
        tvEmptyText     = v.findViewById(R.id.tvEmptyText);
        
        // ✅ ربط الزر هنا
        fabAddEntry     = v.findViewById(R.id.fabAddEntry);
    }

    private void setupViewModel() {
        viewModel = new ViewModelProvider(this).get(AccountingViewModel.class);

        viewModel.getJournalEntries().observe(getViewLifecycleOwner(), entries -> {
            swipeRefresh.setRefreshing(false);
            pbLoading.setVisibility(View.GONE);

            if (entries == null || entries.length == 0) {
                showEmpty("لا توجد قيود في هذه الفترة");
                return;
            }

            showData();
            adapter.setItems(Arrays.asList(entries));
        });

        viewModel.getLoading().observe(getViewLifecycleOwner(), isLoading -> {
            if (isLoading != null && isLoading && !swipeRefresh.isRefreshing()) {
                pbLoading.setVisibility(View.VISIBLE);
            } else {
                pbLoading.setVisibility(View.GONE);
            }
        });

        viewModel.getError().observe(getViewLifecycleOwner(), error -> {
            if (error != null && !error.isEmpty()) {
                Toast.makeText(requireContext(), "❌ " + error, Toast.LENGTH_LONG).show();
                swipeRefresh.setRefreshing(false);
                pbLoading.setVisibility(View.GONE);
                viewModel.clearError();
            }
        });
    }

    private void setupRecycler() {
        adapter = new JournalEntryAdapter(entry -> {
            if (entry.id <= 0) {
                Toast.makeText(requireContext(), "معرف القيد مفقود", Toast.LENGTH_SHORT).show();
                return;
            }

            MiraJournalEntryDetailsFragment details =
                MiraJournalEntryDetailsFragment.newInstance(entry.id);

            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).loadFragment(details, "JournalEntryDetails");
            } else {
                requireActivity().getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.fragment_container, details)
                    .addToBackStack("JournalEntryDetails")
                    .commit();
            }
        });

        rvEntries.setLayoutManager(new LinearLayoutManager(getContext()));
        rvEntries.setAdapter(adapter);
    }

    private void setupListeners() {
        btnFromDate.setOnClickListener(v -> pickDate(true));
        btnToDate.setOnClickListener(v -> pickDate(false));

        swipeRefresh.setOnRefreshListener(this::loadEntries);

        chipStatusGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);

            if (id == R.id.chipAll) statusFilter = null;
            else if (id == R.id.chipPosted) statusFilter = "posted";
            else if (id == R.id.chipDraft) statusFilter = "draft";
            else if (id == R.id.chipReversed) statusFilter = "reversed";

            loadEntries();
        });

        // ✅ مستمع زر الإضافة (+)
        // الحل النهائي لفتح الشاشة فوق التبويبات بدون تجميد
// ✅ مستمع زر الإضافة (+) بطريقة آمنة داخل التبويبات
if (fabAddEntry != null) {
    fabAddEntry.setOnClickListener(v -> {
        MiraAddJournalEntryFragment addFragment = new MiraAddJournalEntryFragment();
        
        // نستخدم getParentFragmentManager لفتح الشاشة داخل الحاوية الخاصة بالتبويبات
        getParentFragmentManager().beginTransaction()
            .replace(R.id.tabContentContainer, addFragment, "AddJournalEntry")
            .addToBackStack("AddJournalEntry")
            .commit();
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
                if (isFrom) fromDate = date;
                else toDate = date;
                updateDateButtons();
                loadEntries();
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show();
    }

    private void loadEntries() {
        currentPage = 1;
        updateDateButtons();
        // ✅ تمرير التواريخ المحددة للـ ViewModel
        viewModel.loadJournalEntriesWithFilters(currentPage, PER_PAGE, statusFilter, fromDate, toDate);
    }

    private void updateDateButtons() {
        btnFromDate.setText("من: " + DateHelper.toDisplay(fromDate));
        btnToDate.setText("إلى: " + DateHelper.toDisplay(toDate));
    }

    private void showEmpty(String message) {
        emptyState.setVisibility(View.VISIBLE);
        rvEntries.setVisibility(View.GONE);
        tvEmptyText.setText(message);
    }

    private void showData() {
        emptyState.setVisibility(View.GONE);
        rvEntries.setVisibility(View.VISIBLE);
    }
}
