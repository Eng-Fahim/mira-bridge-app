package com.example.uhf.fragment;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.uhf.R;
import com.example.uhf.api.dto.JournalEntryDto;
import com.example.uhf.api.dto.JournalLineDto;
import com.example.uhf.utils.DateHelper;
import com.example.uhf.utils.NumberFormatter;
import com.example.uhf.viewmodel.AccountingViewModel;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

public class MiraJournalDetailFragment extends Fragment {

    private AccountingViewModel viewModel;
    private long entryId;

    private MaterialToolbar toolbar;
    private TextView tvEntryNumber, tvStatus, tvDate, tvReference, tvDescription;
    private TextView tvCurrency, tvExchangeRate;
    private TextView tvTotalDebit, tvTotalCredit, tvTotalGold;
    private LinearLayout linesContainer;
    private MaterialButton btnPost, btnReverse, btnShare, btnPrint;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            entryId = getArguments().getLong("entry_id", 0);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_journal_detail, container, false);

        initViews(view);
        setupViewModel();
        setupListeners();

        viewModel.loadJournalEntry(entryId);

        return view;
    }

    private void initViews(View rootView) {
        toolbar = rootView.findViewById(R.id.toolbar);
        tvEntryNumber = rootView.findViewById(R.id.tvEntryNumber);
        tvStatus = rootView.findViewById(R.id.tvStatus);
        tvDate = rootView.findViewById(R.id.tvDate);
        tvReference = rootView.findViewById(R.id.tvReference);
        tvDescription = rootView.findViewById(R.id.tvDescription);
        tvCurrency = rootView.findViewById(R.id.tvCurrency);
        tvExchangeRate = rootView.findViewById(R.id.tvExchangeRate);
        tvTotalDebit = rootView.findViewById(R.id.tvTotalDebit);
        tvTotalCredit = rootView.findViewById(R.id.tvTotalCredit);
        tvTotalGold = rootView.findViewById(R.id.tvTotalGold);
        linesContainer = rootView.findViewById(R.id.linesContainer);
        btnPost = rootView.findViewById(R.id.btnPost);
        btnReverse = rootView.findViewById(R.id.btnReverse);
        btnShare = rootView.findViewById(R.id.btnShare);
        btnPrint = rootView.findViewById(R.id.btnPrint);

        // ✅ الحل: استخدام اسم مختلف للمتغير في lambda
        toolbar.setNavigationOnClickListener(view -> {
            requireActivity().getSupportFragmentManager().popBackStack();
        });
    }

    private void setupViewModel() {
        viewModel = new ViewModelProvider(this).get(AccountingViewModel.class);

        viewModel.getSelectedEntry().observe(getViewLifecycleOwner(), entry -> {
            if (entry != null) {
                renderEntry(entry);
            }
        });

        viewModel.getError().observe(getViewLifecycleOwner(), error -> {
            if (error != null && !error.isEmpty()) {
                Toast.makeText(requireContext(), "❌ " + error, Toast.LENGTH_LONG).show();
                viewModel.clearError();
            }
        });
    }

    private void renderEntry(JournalEntryDto entry) {
        tvEntryNumber.setText(entry.entryNumber);
        tvDate.setText(DateHelper.toDisplay(entry.entryDate));
        tvReference.setText(entry.reference != null && !entry.reference.isEmpty()
            ? entry.reference : "—");
        tvDescription.setText(entry.description != null && !entry.description.isEmpty()
            ? entry.description : "—");

        // الحالة
        String statusText;
        int statusColor;
        switch (entry.status) {
            case "posted":    statusText = "✅ معتمد";   statusColor = Color.parseColor("#27AE60"); break;
            case "draft":     statusText = "📝 مسودة";   statusColor = Color.parseColor("#F39C12"); break;
            case "reversed":  statusText = "🔄 معكوس";   statusColor = Color.parseColor("#E74C3C"); break;
            case "cancelled": statusText = "❌ ملغى";    statusColor = Color.parseColor("#95A5A6"); break;
            default:          statusText = entry.status; statusColor = Color.GRAY;
        }
        tvStatus.setText(statusText);
        tvStatus.setTextColor(statusColor);

        // العملة
        tvCurrency.setText(entry.currency);
        tvExchangeRate.setText(String.format("سعر الصرف: %.4f", entry.exchangeRate));

        // الإجماليات
        tvTotalDebit.setText(NumberFormatter.money(entry.totalDebitBase));
        tvTotalCredit.setText(NumberFormatter.money(entry.totalCreditBase));
        tvTotalGold.setText(NumberFormatter.gold(entry.getNetGold()));

        // السطور
        linesContainer.removeAllViews();
        if (entry.lines != null && !entry.lines.isEmpty()) {
            for (JournalLineDto line : entry.lines) {
                addLineView(line);
            }
        }

        // أزرار الإجراءات حسب الحالة
        btnPost.setVisibility("draft".equals(entry.status) ? View.VISIBLE : View.GONE);
        btnReverse.setVisibility("posted".equals(entry.status) ? View.VISIBLE : View.GONE);
    }

    private void addLineView(JournalLineDto line) {
        View lineView = LayoutInflater.from(getContext())
            .inflate(R.layout.item_journal_line, linesContainer, false);

        TextView tvAccount = lineView.findViewById(R.id.tvLineAccount);
        TextView tvDescription = lineView.findViewById(R.id.tvLineDescription);
        TextView tvDebit = lineView.findViewById(R.id.tvLineDebit);
        TextView tvCredit = lineView.findViewById(R.id.tvLineCredit);
        TextView tvGold = lineView.findViewById(R.id.tvLineGold);

        String accountText = (line.accountCode != null ? line.accountCode + " - " : "")
            + (line.accountNameAr != null ? line.accountNameAr : "—");
        tvAccount.setText(accountText);
        tvDescription.setText(line.description != null ? line.description : "");

        tvDebit.setText(line.debit > 0 ? NumberFormatter.money(line.debit) : "—");
        tvCredit.setText(line.credit > 0 ? NumberFormatter.money(line.credit) : "—");

        double netGold = line.getNetGold();
        if (Math.abs(netGold) > 0.0001) {
            tvGold.setVisibility(View.VISIBLE);
            tvGold.setText(NumberFormatter.goldSigned(netGold)
                + (line.originalKarat != null ? " (" + line.originalKarat + ")" : ""));
            tvGold.setTextColor(netGold > 0
                ? Color.parseColor("#27AE60") : Color.parseColor("#E74C3C"));
        } else {
            tvGold.setVisibility(View.GONE);
        }

        linesContainer.addView(lineView);
    }

    private void setupListeners() {
        btnPost.setOnClickListener(v -> {
            viewModel.postJournalEntry(entryId);
            Toast.makeText(requireContext(), "✅ تم اعتماد القيد", Toast.LENGTH_SHORT).show();
        });

        btnReverse.setOnClickListener(v -> {
            Toast.makeText(requireContext(), "🔧 العكس قيد التطوير", Toast.LENGTH_SHORT).show();
        });

        btnShare.setOnClickListener(v -> {
            Toast.makeText(requireContext(), "📤 المشاركة قيد التطوير", Toast.LENGTH_SHORT).show();
        });

        btnPrint.setOnClickListener(v -> {
            Toast.makeText(requireContext(), "🖨️ الطباعة قيد التطوير", Toast.LENGTH_SHORT).show();
        });
    }
}
