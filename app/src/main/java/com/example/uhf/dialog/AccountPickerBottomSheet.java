package com.example.uhf.dialog;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.api.dto.AccountDto;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.json.JSONArray;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ✅ BottomSheet لاختيار حساب من دليل الحسابات
 */
public class AccountPickerBottomSheet extends BottomSheetDialogFragment {

    private static final String TAG = "AccountPicker";

    public interface OnAccountSelectedListener {
        void onAccountSelected(AccountDto account);
    }

    private TextInputEditText etSearch;
    private ChipGroup chipFilter;
    private RecyclerView rvAccounts;
    private ProgressBar pbLoading;

    private final List<AccountDto> allAccounts = new ArrayList<>();
    private final List<AccountDto> filteredAccounts = new ArrayList<>();
    private AccountAdapter adapter;
    private OnAccountSelectedListener listener;
    private String typeFilter = null;
    private String searchQuery = "";

    public void setListener(OnAccountSelectedListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_account_picker,
            container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        etSearch = view.findViewById(R.id.etSearch);
        chipFilter = view.findViewById(R.id.chipFilter);
        rvAccounts = view.findViewById(R.id.rvAccounts);
        pbLoading = view.findViewById(R.id.pbLoading);

        setupRecycler();
        setupFilters();
        loadAccounts();
    }

    private void setupRecycler() {
        adapter = new AccountAdapter(filteredAccounts, account -> {
            if (listener != null) {
                listener.onAccountSelected(account);
            }
            dismiss();
        });

        rvAccounts.setLayoutManager(new LinearLayoutManager(getContext()));
        rvAccounts.setAdapter(adapter);
    }

    private void setupFilters() {
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}

            @Override
            public void afterTextChanged(Editable s) {
                searchQuery = s.toString().trim().toLowerCase();
                applyFilter();
            }
        });

        chipFilter.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);

            if (id == R.id.chipAsset) typeFilter = "asset";
            else if (id == R.id.chipLiability) typeFilter = "liability";
            else if (id == R.id.chipEquity) typeFilter = "equity";
            else if (id == R.id.chipRevenue) typeFilter = "revenue";
            else if (id == R.id.chipExpense) typeFilter = "expense";
            else typeFilter = null;

            applyFilter();
        });
    }

    private void loadAccounts() {
        pbLoading.setVisibility(View.VISIBLE);

        new Thread(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(
                    requireContext().getApplicationContext());

                // ✅ leaf_only=false — نريد كل الحسابات
                MiraApiClient.ApiResponse response = client.getAccounting(
                    "accounts?leaf_only=true");

                new Handler(Looper.getMainLooper()).post(() -> {
                    pbLoading.setVisibility(View.GONE);

                    if (response != null && response.isSuccess && response.data != null) {
                        JSONArray arr = response.data.optJSONArray("data");
                        if (arr != null) {
                            Gson gson = new Gson();
                            Type listType = new TypeToken<List<AccountDto>>(){}.getType();

                            allAccounts.clear();
                            for (int i = 0; i < arr.length(); i++) {
                                try {
                                    AccountDto acc = gson.fromJson(
                                        arr.getJSONObject(i).toString(), AccountDto.class);
                                    if (acc != null) allAccounts.add(acc);
                                } catch (Exception ignored) {}
                            }

                            applyFilter();
                        }
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "loadAccounts error: " + e.getMessage(), e);
                new Handler(Looper.getMainLooper()).post(() ->
                    pbLoading.setVisibility(View.GONE));
            }
        }).start();
    }

    private void applyFilter() {
        filteredAccounts.clear();

        for (AccountDto a : allAccounts) {
            // Filter by type
            if (typeFilter != null && !typeFilter.equals(a.accountType)) continue;

            // Filter by search
            if (!searchQuery.isEmpty()) {
                String code = a.accountCode != null ? a.accountCode.toLowerCase() : "";
                String nameAr = a.accountNameAr != null ? a.accountNameAr.toLowerCase() : "";
                String nameEn = a.accountName != null ? a.accountName.toLowerCase() : "";

                if (!code.contains(searchQuery)
                    && !nameAr.contains(searchQuery)
                    && !nameEn.contains(searchQuery)) continue;
            }

            filteredAccounts.add(a);
        }

        adapter.notifyDataSetChanged();
    }

    // ═══════════════════════════════════════════════════════════
    //  Inner Adapter
    // ═══════════════════════════════════════════════════════════

    private static class AccountAdapter
            extends RecyclerView.Adapter<AccountAdapter.VH> {

        public interface OnClick {
            void onClick(AccountDto account);
        }

        private final List<AccountDto> items;
        private final OnClick listener;

        AccountAdapter(List<AccountDto> items, OnClick listener) {
            this.items = items;
            this.listener = listener;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_account_picker, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            AccountDto a = items.get(position);

            h.tvAccountCode.setText(a.accountCode != null ? a.accountCode : "—");
            h.tvAccountName.setText(a.accountNameAr != null
                ? a.accountNameAr : "—");

            // Color by type
            String color;
            String label;
            switch (a.accountType != null ? a.accountType.toLowerCase() : "") {
                case "asset":     color = "#3498DB"; label = "أصل"; break;
                case "liability": color = "#E74C3C"; label = "التزام"; break;
                case "equity":    color = "#9B59B6"; label = "حقوق"; break;
                case "revenue":   color = "#27AE60"; label = "إيراد"; break;
                case "expense":   color = "#F39C12"; label = "مصروف"; break;
                default:          color = "#7F8C8D"; label = "—";
            }

            h.tvAccountType.setText(label);
            h.tvAccountType.setTextColor(Color.parseColor(color));
            h.accountIndicator.setBackgroundColor(Color.parseColor(color));

            h.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onClick(a);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            View accountIndicator;
            android.widget.TextView tvAccountCode, tvAccountName, tvAccountType;

            VH(@NonNull View v) {
                super(v);
                accountIndicator = v.findViewById(R.id.accountIndicator);
                tvAccountCode     = v.findViewById(R.id.tvAccountCode);
                tvAccountName     = v.findViewById(R.id.tvAccountName);
                tvAccountType     = v.findViewById(R.id.tvAccountType);
            }
        }
    }
}
