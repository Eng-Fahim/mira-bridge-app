package com.example.uhf.fragment;

import android.app.DatePickerDialog;
import android.graphics.Color;
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

import com.example.uhf.R;
import com.example.uhf.adapter.GoldAccountAdapter;
import com.example.uhf.api.dto.GoldMovementDto;
import com.example.uhf.utils.DateHelper;
import com.example.uhf.utils.NumberFormatter;
import com.example.uhf.viewmodel.AccountingViewModel;
import com.google.android.material.button.MaterialButton;

import java.util.Calendar;
import java.util.Locale;

public class MiraGoldMovementFragment extends Fragment {

    private AccountingViewModel viewModel;

    private MaterialButton btnFrom, btnTo;
    private LinearLayout kpiContainer;
    private RecyclerView rvGoldAccounts;
    private ProgressBar pbLoading;
    private LinearLayout emptyState;
    private TextView tvEmptyText;

    private String fromDate = DateHelper.startOfYearApi();
    private String toDate = DateHelper.todayApi();
    private GoldAccountAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_gold_movement, container, false);

        initViews(v);
        setupViewModel();
        setupRecycler();
        setupListeners();
        updateButtons();

        viewModel.loadGoldMovement(fromDate, toDate, 0);

        return v;
    }

    private void initViews(View v) {
        btnFrom = v.findViewById(R.id.btnFrom);
        btnTo = v.findViewById(R.id.btnTo);
        kpiContainer = v.findViewById(R.id.kpiContainer);
        rvGoldAccounts = v.findViewById(R.id.rvGoldAccounts);
        pbLoading = v.findViewById(R.id.pbLoading);
        emptyState = v.findViewById(R.id.emptyState);
        tvEmptyText = v.findViewById(R.id.tvEmptyText);
    }

    private void setupViewModel() {
        viewModel = new ViewModelProvider(this).get(AccountingViewModel.class);

        viewModel.getGoldMovement().observe(getViewLifecycleOwner(), this::render);

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
        adapter = new GoldAccountAdapter();
        rvGoldAccounts.setLayoutManager(new LinearLayoutManager(getContext()));
        rvGoldAccounts.setAdapter(adapter);
    }

    private void setupListeners() {
        btnFrom.setOnClickListener(v -> pickDate(true));
        btnTo.setOnClickListener(v -> pickDate(false));
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
                updateButtons();
                viewModel.loadGoldMovement(fromDate, toDate, 0);
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show();
    }

    private void updateButtons() {
        btnFrom.setText("من: " + DateHelper.toDisplay(fromDate));
        btnTo.setText("إلى: " + DateHelper.toDisplay(toDate));
    }

    private void render(GoldMovementDto gm) {
        if (gm == null || gm.totals == null) return;

        // ✅ KPIs مع الأوزان والقيم المالية
        kpiContainer.removeAllViews();
        
        String openText = String.format(Locale.US, "%.3fg\n%s", gm.totals.openingGold, NumberFormatter.moneyShort(gm.totals.openingValue));
        addKpi("افتتاحي", openText, "#95A5A6");
        
        String inText = String.format(Locale.US, "%.3fg\n%s", gm.totals.goldIn, NumberFormatter.moneyShort(gm.totals.valueIn));
        addKpi("📥 وارد", inText, "#27AE60");
        
        String outText = String.format(Locale.US, "%.3fg\n%s", gm.totals.goldOut, NumberFormatter.moneyShort(gm.totals.valueOut));
        addKpi("📤 صادر", outText, "#E74C3C");
        
        String closeText = String.format(Locale.US, "%.3fg\n%s", gm.totals.closingGold, NumberFormatter.moneyShort(gm.totals.closingValue));
        addKpi("🥇 الرصيد", closeText, "#D4AF37");

        // الحسابات
        if (gm.accounts != null && !gm.accounts.isEmpty()) {
            adapter.setItems(gm.accounts);
            emptyState.setVisibility(View.GONE);
            rvGoldAccounts.setVisibility(View.VISIBLE);
        } else {
            emptyState.setVisibility(View.VISIBLE);
            rvGoldAccounts.setVisibility(View.GONE);
            tvEmptyText.setText("لا توجد حركات ذهب في هذه الفترة");
        }
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
}
