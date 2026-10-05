package com.example.uhf.fragment;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.adapter.AccountTreeAdapter;
import com.example.uhf.api.dto.AccountDto;
import com.example.uhf.viewmodel.AccountingViewModel;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

/**
 * شاشة دليل الحسابات الشجري
 */
public class MiraAccountTreeFragment extends Fragment {

    private AccountingViewModel viewModel;

    private TextInputEditText etSearch;
    private ChipGroup chipTypeGroup;
    private RecyclerView rvAccounts;
    private ProgressBar pbLoading;

    private AccountTreeAdapter adapter;
    private List<AccountDto> allAccounts = new ArrayList<>();
    private String typeFilter = null;
    private String searchQuery = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_account_tree, container, false);

        initViews(v);
        setupViewModel();
        setupRecycler();
        setupListeners();

        viewModel.loadAccounts(null, null);

        return v;
    }

    private void initViews(View v) {
        etSearch = v.findViewById(R.id.etSearch);
        chipTypeGroup = v.findViewById(R.id.chipTypeGroup);
        rvAccounts = v.findViewById(R.id.rvAccounts);
        pbLoading = v.findViewById(R.id.pbLoading);
    }

    private void setupViewModel() {
        viewModel = new ViewModelProvider(this).get(AccountingViewModel.class);

        viewModel.getAccounts().observe(getViewLifecycleOwner(), accounts -> {
            if (accounts == null) return;

            allAccounts.clear();
            for (AccountDto a : accounts) {
                allAccounts.add(a);
            }
            applyFilters();
        });

        viewModel.getLoading().observe(getViewLifecycleOwner(), loading -> {
            pbLoading.setVisibility(loading != null && loading ? View.VISIBLE : View.GONE);
        });

        viewModel.getError().observe(getViewLifecycleOwner(), error -> {
            if (error != null && !error.isEmpty()) {
                Toast.makeText(requireContext(), "❌ " + error, Toast.LENGTH_SHORT).show();
                viewModel.clearError();
            }
        });
    }

    private void setupRecycler() {
    adapter = new AccountTreeAdapter(account -> {
        Fragment parent = getParentFragment();

        // ✅ الحالة المثالية: داخل MiraAccountingFragment
        if (parent instanceof MiraAccountingFragment) {
            ((MiraAccountingFragment) parent)
                .navigateToLedgerWithAccount(account.id);
            return;
        }

        // ✅ fallback: عبر UHFMainActivity
        if (getActivity() instanceof com.example.uhf.activity.UHFMainActivity) {
            Bundle args = new Bundle();
            args.putLong("account_id", account.id);

            MiraGeneralLedgerFragment gl = new MiraGeneralLedgerFragment();
            gl.setArguments(args);

            ((com.example.uhf.activity.UHFMainActivity) getActivity())
                .loadFragment(gl, "GeneralLedger");
            return;
        }

        // ❌ لا يوجد مسار — إعلام
        Toast.makeText(requireContext(),
            "لا يمكن فتح دفتر الأستاذ حالياً",
            Toast.LENGTH_SHORT).show();
    });
    rvAccounts.setLayoutManager(new LinearLayoutManager(getContext()));
    rvAccounts.setAdapter(adapter);
}

    private void setupListeners() {
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                searchQuery = s.toString().trim().toLowerCase();
                applyFilters();
            }
        });

        chipTypeGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);

            if (id == R.id.chipAll) typeFilter = null;
            else if (id == R.id.chipAsset) typeFilter = "asset";
            else if (id == R.id.chipLiability) typeFilter = "liability";
            else if (id == R.id.chipEquity) typeFilter = "equity";
            else if (id == R.id.chipRevenue) typeFilter = "revenue";
            else if (id == R.id.chipExpense) typeFilter = "expense";

            applyFilters();
        });
    }

    private void applyFilters() {
        List<AccountDto> filtered = new ArrayList<>();

        for (AccountDto a : allAccounts) {
            // فلتر النوع
            if (typeFilter != null && !typeFilter.equals(a.accountType)) continue;

            // فلتر البحث
            if (!searchQuery.isEmpty()) {
                String code = a.accountCode != null ? a.accountCode.toLowerCase() : "";
                String nameAr = a.accountNameAr != null ? a.accountNameAr.toLowerCase() : "";
                String nameEn = a.accountName != null ? a.accountName.toLowerCase() : "";

                if (!code.contains(searchQuery) 
                    && !nameAr.contains(searchQuery) 
                    && !nameEn.contains(searchQuery)) {
                    continue;
                }
            }

            filtered.add(a);
        }

        adapter.setItems(filtered);
    }
}
