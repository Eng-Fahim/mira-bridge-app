package com.example.uhf.fragment;

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
import com.example.uhf.adapter.JournalLineAdapter;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.api.dto.JournalAuditDto;
import com.example.uhf.api.dto.JournalEntryDetailsDto;
import com.example.uhf.api.dto.JournalEntryDto;
import com.example.uhf.api.dto.JournalLineDto;
import com.example.uhf.utils.DateHelper;
import com.example.uhf.utils.JournalPdfExporter;
import com.example.uhf.utils.NumberFormatter;
import com.example.uhf.viewmodel.AccountingViewModel;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ✅ شاشة تفاصيل القيد المحاسبي
 * - رأس القيد + مبلغ الفاتورة + KPIs + السطور + Audit
 * - زر PDF احترافي + زر عكس القيد
 */
public class MiraJournalEntryDetailsFragment extends Fragment {

    private static final String ARG_ENTRY_ID = "entry_id";

    private MaterialToolbar toolbar;
    private TextView tvEntryNumber, tvStatus, tvDate, tvDescription, tvReference;
    private TextView tvInvoiceAmount;
    private LinearLayout kpiContainer;
    private RecyclerView rvLines;
    private TextView tvNotesLabel, tvNotes;
    private TextView tvAuditLabel;
    private LinearLayout auditContainer;
    private ProgressBar pbLoading;
    private ExtendedFloatingActionButton fabActions;
    
    // ✅ المتغير الخاص بزر العكس
    private MaterialButton btnReverseEntry; 

    private long entryId = 0;
    private AccountingViewModel viewModel;
    private JournalLineAdapter lineAdapter;
    private JournalEntryDto currentEntry;

    // ═══════════════════════════════════════════════════════════
    //  Factory
    // ═══════════════════════════════════════════════════════════

    public static MiraJournalEntryDetailsFragment newInstance(long entryId) {
        MiraJournalEntryDetailsFragment f = new MiraJournalEntryDetailsFragment();
        Bundle args = new Bundle();
        args.putLong(ARG_ENTRY_ID, entryId);
        f.setArguments(args);
        return f;
    }

    // ═══════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_journal_entry_details,
                container, false);

        if (getArguments() != null) {
            entryId = getArguments().getLong(ARG_ENTRY_ID, 0);
        }

        if (entryId <= 0) {
            Toast.makeText(requireContext(),
                "معرف القيد غير صالح", Toast.LENGTH_SHORT).show();
            if (getParentFragmentManager() != null) {
                getParentFragmentManager().popBackStack();
            }
            return v;
        }

        initViews(v);
        setupToolbar();
        setupRecycler();
        setupViewModel();

        pbLoading.setVisibility(View.VISIBLE);
        viewModel.loadJournalEntryDetails(entryId);

        return v;
    }

    private void initViews(View v) {
        toolbar         = v.findViewById(R.id.toolbar);
        tvEntryNumber   = v.findViewById(R.id.tvEntryNumber);
        tvStatus        = v.findViewById(R.id.tvStatus);
        tvDate          = v.findViewById(R.id.tvDate);
        tvDescription   = v.findViewById(R.id.tvDescription);
        tvReference     = v.findViewById(R.id.tvReference);
        tvInvoiceAmount = v.findViewById(R.id.tvInvoiceAmount);
        kpiContainer    = v.findViewById(R.id.kpiContainer);
        rvLines         = v.findViewById(R.id.rvLines);
        tvNotesLabel    = v.findViewById(R.id.tvNotesLabel);
        tvNotes         = v.findViewById(R.id.tvNotes);
        tvAuditLabel    = v.findViewById(R.id.tvAuditLabel);
        auditContainer  = v.findViewById(R.id.auditContainer);
        pbLoading       = v.findViewById(R.id.pbLoading);
        fabActions      = v.findViewById(R.id.fabActions);
        
        // ✅ ربط زر العكس
        btnReverseEntry = v.findViewById(R.id.btnReverseEntry);
    }

    private void setupToolbar() {
        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v -> {
                if (getParentFragmentManager() != null) {
                    getParentFragmentManager().popBackStack();
                }
            });
        }
    }

    private void setupRecycler() {
        lineAdapter = new JournalLineAdapter();
        rvLines.setLayoutManager(new LinearLayoutManager(getContext()));
        rvLines.setAdapter(lineAdapter);
    }

    private void setupViewModel() {
        viewModel = new ViewModelProvider(this).get(AccountingViewModel.class);

        viewModel.getJournalEntryDetails().observe(getViewLifecycleOwner(),
                details -> {
            pbLoading.setVisibility(View.GONE);
            if (details != null) {
                renderDetails(details);
            }
        });

        viewModel.getError().observe(getViewLifecycleOwner(), error -> {
            if (error != null && !error.isEmpty()) {
                pbLoading.setVisibility(View.GONE);
                Toast.makeText(requireContext(), "❌ " + error,
                        Toast.LENGTH_LONG).show();
                viewModel.clearError();
            }
        });
    }

    // ═══════════════════════════════════════════════════════════
    //  Rendering
    // ═══════════════════════════════════════════════════════════

    private void renderDetails(JournalEntryDetailsDto details) {
        JournalEntryDto entry = details.entry;
        currentEntry = entry;

        // ─── الرأس ───
        tvEntryNumber.setText(entry.entryNumber != null
                ? entry.entryNumber : "—");

        tvStatus.setText(entry.getStatusLabelAr());
        try {
            tvStatus.getBackground().setTint(entry.getStatusColor());
        } catch (Exception ignored) {}

        tvDate.setText(entry.entryDate != null
                ? "📅 " + DateHelper.toDisplay(entry.entryDate)
                : "");

        tvDescription.setText(entry.description != null
                && !entry.description.isEmpty()
                ? entry.description : "—");

        // ─── المرجع ───
        if (entry.reference != null && !entry.reference.isEmpty()) {
            tvReference.setVisibility(View.VISIBLE);
            tvReference.setText("🔖 المرجع: " + entry.reference
                + " · " + entry.getSourceTypeLabelAr());
        } else {
            tvReference.setVisibility(View.GONE);
        }

        // ─── مبلغ الفاتورة الأصلي ───
        if (entry.displayAmount > 0
                && Math.abs(entry.displayAmount - entry.totalDebit) > 0.01) {
            tvInvoiceAmount.setVisibility(View.VISIBLE);
            tvInvoiceAmount.setText("💰 مبلغ الفاتورة الأصلي: "
                + NumberFormatter.money(entry.displayAmount));
        } else {
            tvInvoiceAmount.setVisibility(View.GONE);
        }

        // ─── KPIs ───
        renderKpis(entry);

        // ─── السطور ───
        if (details.lines != null && details.lines.length > 0) {
            List<JournalLineDto> lines = Arrays.asList(details.lines);
            lineAdapter.setItems(lines);
        } else {
            lineAdapter.setItems(null);
        }

        // ─── الملاحظات ───
        if (entry.notes != null && !entry.notes.trim().isEmpty()) {
            tvNotesLabel.setVisibility(View.VISIBLE);
            tvNotes.setVisibility(View.VISIBLE);
            tvNotes.setText(entry.notes);
        } else {
            tvNotesLabel.setVisibility(View.GONE);
            tvNotes.setVisibility(View.GONE);
        }

        // ─── سجل التعديلات ───
        renderAudit(details.audit);

        // ─── زر PDF ───
        setupPdfButton(entry, details.lines);

        // ✅ إعداد زر العكس
        setupReverseButton();
    }

    private void renderKpis(JournalEntryDto entry) {
        kpiContainer.removeAllViews();

        boolean isPosSale = "pos_sale".equalsIgnoreCase(entry.sourceType)
                && entry.displayAmount > 0
                && Math.abs(entry.displayAmount - entry.totalDebit) > 0.01;

        if (isPosSale) {
            addKpi("مبلغ الفاتورة",
                NumberFormatter.moneyShort(entry.displayAmount), "#D4AF37");
        }

        addKpi("إجمالي مدين",
            NumberFormatter.moneyShort(entry.totalDebit),  "#3498DB");
        addKpi("إجمالي دائن",
            NumberFormatter.moneyShort(entry.totalCredit), "#E74C3C");

        double goldDiff = entry.totalGoldDebitGrams - entry.totalGoldCreditGrams;
        if (Math.abs(goldDiff) > 0.001) {
            addKpi("صافي الذهب",
                NumberFormatter.goldSigned(goldDiff), "#D4AF37");
        }

        addKpi(
            entry.isBalanced() ? "متوازن ✅" : "غير متوازن ⚠️",
            NumberFormatter.moneyShort(entry.totalDebit),
            entry.isBalanced() ? "#27AE60" : "#F39C12"
        );
    }

    private void addKpi(String title, String value, String colorHex) {
        View card = LayoutInflater.from(getContext())
            .inflate(R.layout.kpi_card, kpiContainer, false);

        TextView tvTitle = card.findViewById(R.id.tvKpiTitle);
        TextView tvValue = card.findViewById(R.id.tvKpiValue);
        View indicator   = card.findViewById(R.id.kpiIndicator);

        tvTitle.setText(title);
        tvValue.setText(value);

        int c = Color.parseColor(colorHex);
        tvValue.setTextColor(c);
        if (indicator != null) indicator.setBackgroundColor(c);

        kpiContainer.addView(card);
    }

    private void renderAudit(JournalAuditDto[] audit) {
        if (audit == null || audit.length == 0) {
            tvAuditLabel.setVisibility(View.GONE);
            auditContainer.setVisibility(View.GONE);
            return;
        }

        tvAuditLabel.setVisibility(View.VISIBLE);
        auditContainer.setVisibility(View.VISIBLE);
        auditContainer.removeAllViews();

        for (JournalAuditDto a : audit) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(12, 8, 12, 8);

            TextView line1 = new TextView(getContext());
            String user   = a.getUserName();
            String action = a.getActionLabelAr();
            line1.setText("• " + action + " — " + user);
            line1.setTextSize(12);
            line1.setTextColor(Color.parseColor("#2C3E50"));

            TextView line2 = new TextView(getContext());
            String when = "";
            String performedAt = a.getCreatedAt();
            if (performedAt != null && performedAt.length() >= 10) {
                when = DateHelper.toDisplay(performedAt.substring(0, 10));
            }
            String reason = (a.reason != null && !a.reason.isEmpty())
                    ? " — " + a.reason : "";
            line2.setText(when + reason);
            line2.setTextSize(10);
            line2.setTextColor(Color.parseColor("#7F8C8D"));

            row.addView(line1);
            row.addView(line2);
            auditContainer.addView(row);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  ✅ دوال عكس القيد (مصححة)
    // ═══════════════════════════════════════════════════════════

    private void setupReverseButton() {
        if (btnReverseEntry == null) return;

        // إخفاء الزر إذا كان القيد معكوساً مسبقاً أو كان هو نفسه قيداً عكسياً أو مسودة
        if (currentEntry != null) {
            boolean isReversed = "reversed".equalsIgnoreCase(currentEntry.status);
            boolean isReversal = "reversal".equalsIgnoreCase(currentEntry.sourceType);
            boolean isDraft = "draft".equalsIgnoreCase(currentEntry.status);

            if (isReversed || isReversal || isDraft) {
                btnReverseEntry.setVisibility(View.GONE);
            } else {
                btnReverseEntry.setVisibility(View.VISIBLE);
                btnReverseEntry.setEnabled(true);
                btnReverseEntry.setText("🔄 عكس هذا القيد");
            }
        } else {
            btnReverseEntry.setVisibility(View.GONE);
        }

        btnReverseEntry.setOnClickListener(v -> {
            new MaterialAlertDialogBuilder(requireContext())
                .setTitle("⚠️ تأكيد عكس القيد")
                .setMessage("سيتم إنشاء قيد عكسي جديد لإلغاء تأثير هذا القيد على الحسابات وأوزان الذهب. هل أنت متأكد؟")
                .setPositiveButton("نعم، اعكس القيد", (dialog, which) -> {
                    executeReverse();
                })
                .setNegativeButton("إلغاء", null)
                .show();
        });
    }

    private void executeReverse() {
        if (currentEntry == null || currentEntry.id <= 0) return;

        btnReverseEntry.setEnabled(false);
        btnReverseEntry.setText("⏳ جاري العكس...");

        new Thread(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(requireContext());
                // ✅ استخدمنا currentEntry.id بدلاً من currentEntryId
                MiraApiClient.ApiResponse response = client.reverseJournalEntry(String.valueOf(currentEntry.id));

                requireActivity().runOnUiThread(() -> {
                    if (response != null && response.isSuccess) {
                        Toast.makeText(requireContext(), "✅ تم إنشاء القيد العكسي بنجاح", Toast.LENGTH_LONG).show();
                        // العودة إلى الشاشة السابقة
                        getParentFragmentManager().popBackStack();
                    } else {
                        btnReverseEntry.setEnabled(true);
                        btnReverseEntry.setText("🔄 عكس هذا القيد");
                        String err = (response != null && response.error != null) ? response.error : "فشل العكس";
                        Toast.makeText(requireContext(), "❌ " + err, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                requireActivity().runOnUiThread(() -> {
                    btnReverseEntry.setEnabled(true);
                    btnReverseEntry.setText("🔄 عكس هذا القيد");
                    Toast.makeText(requireContext(), "❌ حدث خطأ في الاتصال", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    // ═══════════════════════════════════════════════════════════
    //  PDF Button
    // ═══════════════════════════════════════════════════════════

    private void setupPdfButton(JournalEntryDto entry,
                                JournalLineDto[] linesArr) {
        if (fabActions == null) return;

        fabActions.setVisibility(View.VISIBLE);
        fabActions.setText("🖨️ طباعة PDF");
        fabActions.setOnClickListener(v -> {
            if (entry == null) return;

            List<JournalLineDto> lineList = (linesArr != null)
                ? Arrays.asList(linesArr)
                : new ArrayList<>();

            android.net.Uri uri = JournalPdfExporter.exportAndShare(
                requireContext(), entry, lineList);

            if (uri != null) {
                Toast.makeText(requireContext(),
                    "📄 تم إنشاء PDF بنجاح", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(requireContext(),
                    "❌ فشل إنشاء PDF", Toast.LENGTH_SHORT).show();
            }
        });
    }
    
    @Override
    public void onStart() {
        super.onStart();
        // ✅ إخفاء BottomNav في شاشة التفاصيل
        if (getActivity() instanceof com.example.uhf.activity.UHFMainActivity) {
            ((com.example.uhf.activity.UHFMainActivity) getActivity())
                .setBottomNavVisible(false);
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        // ✅ إظهار BottomNav عند المغادرة
        if (getActivity() instanceof com.example.uhf.activity.UHFMainActivity) {
            ((com.example.uhf.activity.UHFMainActivity) getActivity())
                .setBottomNavVisible(true);
        }
    }
}
