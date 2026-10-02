package com.example.uhf.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.dto.TrialBalanceDto;
import com.example.uhf.utils.NumberFormatter;

import java.util.ArrayList;
import java.util.List;

public class TrialBalanceAdapter extends RecyclerView.Adapter<TrialBalanceAdapter.VH> {

    private List<TrialBalanceDto.TrialBalanceRow> items = new ArrayList<>();

    public void setItems(List<TrialBalanceDto.TrialBalanceRow> newItems) {
        this.items = newItems != null ? newItems : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_trial_balance_row, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        TrialBalanceDto.TrialBalanceRow r = items.get(position);

        h.tvCode.setText(r.accountCode != null ? r.accountCode : "—");
        h.tvName.setText(r.accountNameAr != null ? r.accountNameAr : "—");
        h.tvDebit.setText(r.totalDebit > 0 ? NumberFormatter.moneyShort(r.totalDebit) : "—");
        h.tvCredit.setText(r.totalCredit > 0 ? NumberFormatter.moneyShort(r.totalCredit) : "—");

        double balance = r.balance;
        h.tvBalance.setText(NumberFormatter.moneyShort(balance));
        h.tvBalance.setTextColor(balance >= 0
            ? Color.parseColor("#27AE60") : Color.parseColor("#C0392B"));

        // الذهب
        if (Math.abs(r.goldBalance) > 0.001) {
            h.tvGold.setVisibility(View.VISIBLE);
            h.tvGold.setText(NumberFormatter.goldSigned(r.goldBalance));
            h.tvGold.setTextColor(r.goldBalance > 0
                ? Color.parseColor("#27AE60") : Color.parseColor("#E74C3C"));
        } else {
            h.tvGold.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvCode, tvName, tvDebit, tvCredit, tvBalance, tvGold;

        VH(@NonNull View v) {
            super(v);
            tvCode = v.findViewById(R.id.tvRowCode);
            tvName = v.findViewById(R.id.tvRowName);
            tvDebit = v.findViewById(R.id.tvRowDebit);
            tvCredit = v.findViewById(R.id.tvRowCredit);
            tvBalance = v.findViewById(R.id.tvRowBalance);
            tvGold = v.findViewById(R.id.tvRowGold);
        }
    }
}
