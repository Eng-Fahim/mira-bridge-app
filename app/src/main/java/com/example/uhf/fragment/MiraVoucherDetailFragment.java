package com.example.uhf.fragment;

import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

public class MiraVoucherDetailFragment extends BottomSheetDialogFragment {

    private static final String TAG = "VoucherDetail";
    private static final String ARG_VOUCHER_ID = "voucher_id";
    private static final String ARG_VOUCHER_TYPE = "voucher_type";

    private int voucherId;
    private String voucherType;
    private boolean isArabic = true;

    // Views
    private TextView tvTitle, tvNumber, tvDate, tvAccount, tvNotes, tvTotal;
    private LinearLayout llJournalLines;
    private TextView tvTotalDebit, tvTotalCredit, tvBalanceIcon, tvBalanceText;
    private MaterialCardView cardBalanceStatus;

    public static MiraVoucherDetailFragment newInstance(int voucherId, String voucherType) {
        MiraVoucherDetailFragment f = new MiraVoucherDetailFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_VOUCHER_ID, voucherId);
        args.putString(ARG_VOUCHER_TYPE, voucherType);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            voucherId = getArguments().getInt(ARG_VOUCHER_ID, 0);
            voucherType = getArguments().getString(ARG_VOUCHER_TYPE, "receipt");
        }
        isArabic = Locale.getDefault().getLanguage().equals("ar");
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_voucher_detail, container, false);
        initViews(v);
        loadDetail();
        return v;
    }

    private void initViews(View v) {
        tvTitle       = v.findViewById(R.id.tvDetailVoucherTitle);
        tvNumber      = v.findViewById(R.id.tvDetailVoucherNumber);
        tvDate        = v.findViewById(R.id.tvDetailVoucherDate);
        tvAccount     = v.findViewById(R.id.tvDetailAccount);
        tvNotes       = v.findViewById(R.id.tvDetailNotes);
        tvTotal       = v.findViewById(R.id.tvDetailTotal);
        llJournalLines = v.findViewById(R.id.llJournalLines);
        tvTotalDebit  = v.findViewById(R.id.tvTotalDebit);
        tvTotalCredit = v.findViewById(R.id.tvTotalCredit);
        tvBalanceIcon = v.findViewById(R.id.tvBalanceIcon);
        tvBalanceText = v.findViewById(R.id.tvBalanceText);
        cardBalanceStatus = v.findViewById(R.id.cardBalanceStatus);
    }

    private void loadDetail() {
        new Thread(() -> {
            MiraApiClient api = MiraApiClient.getInstance(requireContext());
            MiraApiClient.ApiResponse response = api.getVoucherDetail(voucherId);

            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (response.isSuccess && response.data != null) {
                    renderDetail(response.data);
                } else {
                    Toast.makeText(requireContext(), "فشل تحميل تفاصيل السند", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private void renderDetail(JSONObject data) {
        try {
            JSONObject voucher = data.optJSONObject("voucher");
            JSONArray lines = data.optJSONArray("lines");
            if (voucher == null) return;

            String type = voucher.optString("voucher_type", "receipt");
            boolean isReceipt = "receipt".equals(type);
            
            // Header
            String title = isReceipt ? "📥 سند قبض" : "📤 سند صرف";
            tvTitle.setText(title);
            tvTitle.setTextColor(isReceipt ? 0xFF16A34A : 0xFFDC2626);
            tvNumber.setText("# " + voucher.optString("voucher_number"));
            tvDate.setText("📅 " + voucher.optString("created_at"));

            // Info
            String accCode = voucher.optString("account_code", "");
            String accName = voucher.optString("account_name_ar", "—");
            tvAccount.setText(accCode + " - " + accName);
            
            String notes = voucher.optString("notes", "");
            tvNotes.setText(notes.isEmpty() ? "—" : notes);

            // Total
            double total = voucher.optDouble("amount_money", 0);
            tvTotal.setText(String.format(Locale.US, "%,.2f YER", total));

            // Journal lines
            renderJournalLines(lines);

        } catch (Exception e) {
            Log.e(TAG, "renderDetail error", e);
        }
    }

    private void renderJournalLines(JSONArray lines) {
        llJournalLines.removeAllViews();
        if (lines == null || lines.length() == 0) {
            tvBalanceIcon.setText("⚠️");
            tvBalanceText.setText("لا توجد أسطر قيد");
            return;
        }

        double totalDebit = 0;
        double totalCredit = 0;

        for (int i = 0; i < lines.length(); i++) {
            JSONObject line = lines.optJSONObject(i);
            if (line == null) continue;

            String accCode = line.optString("account_code", "");
            String accName = line.optString("account_name_ar", "");
            String desc = line.optString("description", "");
            double debit = line.optDouble("debit", 0);
            double credit = line.optDouble("credit", 0);
            double goldDebit = line.optDouble("gold_debit_grams", 0);
            double goldCredit = line.optDouble("gold_credit_grams", 0);

            totalDebit += debit;
            totalCredit += credit;

            View row = getLayoutInflater().inflate(R.layout.item_journal_line, llJournalLines, false);
            TextView tvAcc = row.findViewById(R.id.tvLineAccount);
            TextView tvDebit = row.findViewById(R.id.tvLineDebit);
            TextView tvCredit = row.findViewById(R.id.tvLineCredit);
            
            // ✅ تم تصحيح اسم المعرف هنا ليطابق ملف الـ XML
            TextView tvDesc = row.findViewById(R.id.tvLineDescription); 

            tvAcc.setText(accCode + " - " + accName);
            if (tvDesc != null) {
                tvDesc.setText(desc);
            }
            
            // Debit
            if (debit > 0) {
                tvDebit.setText(String.format(Locale.US, "%,.2f", debit));
                tvDebit.setTextColor(0xFF1976D2);
            } else if (goldDebit > 0) {
                tvDebit.setText(String.format(Locale.US, "%.3fg", goldDebit));
                tvDebit.setTextColor(0xFF1976D2);
            } else {
                tvDebit.setText("—");
                tvDebit.setTextColor(0xFFAAAAAA);
            }

            // Credit
            if (credit > 0) {
                tvCredit.setText(String.format(Locale.US, "%,.2f", credit));
                tvCredit.setTextColor(0xFFDC2626);
            } else if (goldCredit > 0) {
                tvCredit.setText(String.format(Locale.US, "%.3fg", goldCredit));
                tvCredit.setTextColor(0xFFDC2626);
            } else {
                tvCredit.setText("—");
                tvCredit.setTextColor(0xFFAAAAAA);
            }

            llJournalLines.addView(row);
        }

        // Totals
        tvTotalDebit.setText(String.format(Locale.US, "%,.2f", totalDebit));
        tvTotalCredit.setText(String.format(Locale.US, "%,.2f", totalCredit));

        // Balance check
        boolean balanced = Math.abs(totalDebit - totalCredit) < 0.01;
        if (balanced) {
            tvBalanceIcon.setText("✅");
            tvBalanceText.setText("القيد متوازن — المدين = الدائن");
            tvBalanceText.setTextColor(0xFF16A34A);
            cardBalanceStatus.setStrokeColor(android.content.res.ColorStateList.valueOf(0xFF16A34A));
            cardBalanceStatus.setCardBackgroundColor(0xFFF0FDF4);
        } else {
            tvBalanceIcon.setText("⚠️");
            tvBalanceText.setText(String.format(Locale.US, "القيد غير متوازن — فرق: %,.2f", Math.abs(totalDebit - totalCredit)));
            tvBalanceText.setTextColor(0xFFDC2626);
            cardBalanceStatus.setStrokeColor(android.content.res.ColorStateList.valueOf(0xFFDC2626));
            cardBalanceStatus.setCardBackgroundColor(0xFFFEF2F2);
        }
    }
}
