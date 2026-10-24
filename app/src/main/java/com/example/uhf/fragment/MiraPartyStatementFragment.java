package com.example.uhf.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MiraPartyStatementFragment extends Fragment {

    private static final String ARG_PARTY_ID = "party_id";

    private int partyId;
    private MiraApiClient apiClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView tvName, tvPhone, tvBalance, tvTotalReceipts, tvTotalPayments;
    private LinearLayout llTransactions;

    public static MiraPartyStatementFragment newInstance(int partyId) {
        MiraPartyStatementFragment f = new MiraPartyStatementFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_PARTY_ID, partyId);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) partyId = getArguments().getInt(ARG_PARTY_ID, 0);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_party_statement, container, false);
        apiClient = MiraApiClient.getInstance(requireContext());

        tvName = v.findViewById(R.id.tvStatementPartyName);
        tvPhone = v.findViewById(R.id.tvStatementPartyPhone);
        tvBalance = v.findViewById(R.id.tvStatementBalance);
        tvTotalReceipts = v.findViewById(R.id.tvTotalReceipts);
        tvTotalPayments = v.findViewById(R.id.tvTotalPayments);
        llTransactions = v.findViewById(R.id.llPartyTransactions);

        loadStatement();
        return v;
    }

    private void loadStatement() {
        executor.execute(() -> {
            MiraApiClient.ApiResponse res = apiClient.getPartyStatement(partyId);
            mainHandler.post(() -> {
                if (res.isSuccess && res.data != null) {
                    renderStatement(res.data);
                } else {
                    Toast.makeText(getContext(), "فشل تحميل كشف الحساب", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void renderStatement(JSONObject data) {
        try {
            JSONObject party = data.optJSONObject("party");
            JSONObject summary = data.optJSONObject("summary");
            JSONArray vouchers = data.optJSONArray("vouchers");

            if (party != null) {
                tvName.setText(party.optString("name", "—"));
                String phone = party.optString("phone", "");
                tvPhone.setText(phone.isEmpty() ? "" : "📞 " + phone);
                
                double balance = party.optDouble("current_balance_money", 0);
                if (Math.abs(balance) < 0.01) {
                    tvBalance.setText("0.00 YER");
                    tvBalance.setTextColor(0xFF78909C);
                } else if (balance > 0) {
                    tvBalance.setText(String.format(Locale.US, "%,.2f YER", balance));
                    tvBalance.setTextColor(0xFF16A34A);
                } else {
                    tvBalance.setText(String.format(Locale.US, "%,.2f YER", Math.abs(balance)));
                    tvBalance.setTextColor(0xFFDC2626);
                }
            }

            if (summary != null) {
                tvTotalReceipts.setText(String.format(Locale.US, "%,.2f", summary.optDouble("total_receipts_money", 0)));
                tvTotalPayments.setText(String.format(Locale.US, "%,.2f", summary.optDouble("total_payments_money", 0)));
            }

            llTransactions.removeAllViews();
            if (vouchers != null && vouchers.length() > 0) {
                for (int i = 0; i < vouchers.length(); i++) {
                    JSONObject v = vouchers.optJSONObject(i);
                    if (v == null) continue;
                    addTransactionRow(v);
                }
            } else {
                TextView tv = new TextView(requireContext());
                tv.setText("لا توجد حركات مالية");
                tv.setGravity(android.view.Gravity.CENTER);
                tv.setPadding(0, 32, 0, 32);
                tv.setTextColor(0xFFAAAAAA);
                llTransactions.addView(tv);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void addTransactionRow(JSONObject v) {
        View row = getLayoutInflater().inflate(R.layout.item_party_transaction, llTransactions, false);
        TextView tvIcon = row.findViewById(R.id.tvTxIcon);
        TextView tvNumber = row.findViewById(R.id.tvTxNumber);
        TextView tvNotes = row.findViewById(R.id.tvTxNotes);
        TextView tvDate = row.findViewById(R.id.tvTxDate);
        TextView tvAmount = row.findViewById(R.id.tvTxAmount);

        String type = v.optString("voucher_type", "receipt");
        boolean isReceipt = "receipt".equals(type);
        double amount = v.optDouble("amount_money", 0);

        tvIcon.setText(isReceipt ? "📥" : "📤");
        tvNumber.setText(v.optString("voucher_number", ""));
        tvNotes.setText(v.optString("notes", ""));
        tvDate.setText(v.optString("created_at", ""));

        if (isReceipt) {
            tvAmount.setText("+" + String.format(Locale.US, "%,.2f", amount));
            tvAmount.setTextColor(0xFF16A34A);
        } else {
            tvAmount.setText("-" + String.format(Locale.US, "%,.2f", amount));
            tvAmount.setTextColor(0xFFDC2626);
        }

        llTransactions.addView(row);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executor != null && !executor.isShutdown()) executor.shutdown();
    }
}
