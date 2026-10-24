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
import java.util.Locale;

public class TrialBalanceAdapter extends RecyclerView.Adapter<TrialBalanceAdapter.VH> {

    private List<TrialBalanceDto.TrialBalanceRow> items = new ArrayList<>();
    private boolean isGoldView = false;

    public interface OnItemClickListener {
        void onItemClick(TrialBalanceDto.TrialBalanceRow row);
    }

    private OnItemClickListener listener;

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void setGoldView(boolean goldView) {
        this.isGoldView = goldView;
        notifyDataSetChanged();
    }

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

        if (isGoldView) {
            // ═══ وضع الذهب (الجرامات) ═══
            h.tvOpenDebit.setText(formatGold(r.openingGoldDebit));
            h.tvOpenCredit.setText(formatGold(r.openingGoldCredit));
            h.tvPeriodDebit.setText(formatGold(r.periodGoldDebit));
            h.tvPeriodCredit.setText(formatGold(r.periodGoldCredit));
            h.tvCloseDebit.setText(formatGold(r.closingGoldDebit));
            h.tvCloseCredit.setText(formatGold(r.closingGoldCredit));

            h.tvGold.setVisibility(View.GONE); // إخفاء شريط الذهب السفلي

        } else {
            // ═══ وضع المال (الريالات) ═══
            h.tvOpenDebit.setText(r.openingDebit > 0 ? NumberFormatter.moneyShort(r.openingDebit) : "—");
            h.tvOpenCredit.setText(r.openingCredit > 0 ? NumberFormatter.moneyShort(r.openingCredit) : "—");
            h.tvPeriodDebit.setText(r.periodDebit > 0 ? NumberFormatter.moneyShort(r.periodDebit) : "—");
            h.tvPeriodCredit.setText(r.periodCredit > 0 ? NumberFormatter.moneyShort(r.periodCredit) : "—");
            h.tvCloseDebit.setText(r.closingDebit > 0 ? NumberFormatter.moneyShort(r.closingDebit) : "—");
            h.tvCloseCredit.setText(r.closingCredit > 0 ? NumberFormatter.moneyShort(r.closingCredit) : "—");

            // إظهار شريط الذهب الصغير (في حال كانت القطعة تتبع حساباً ذهبياً)
            if (Math.abs(r.periodGoldDebit) > 0.001 || Math.abs(r.periodGoldCredit) > 0.001) {
                h.tvGold.setVisibility(View.VISIBLE);
                h.tvGold.setText(String.format(Locale.US, "وزن: مدين %.3fg | دائن %.3fg", r.periodGoldDebit, r.periodGoldCredit));
                h.tvGold.setTextColor(Color.parseColor("#B08D57"));
            } else {
                h.tvGold.setVisibility(View.GONE);
            }
        }

        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onItemClick(r);
        });
    }

    private String formatGold(double value) {
        if (Math.abs(value) < 0.001) return "—";
        return String.format(Locale.US, "%.3f", value);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public List<TrialBalanceDto.TrialBalanceRow> getItems() {
        return items;
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvCode, tvName, tvGold;
        TextView tvOpenDebit, tvOpenCredit, tvPeriodDebit, tvPeriodCredit, tvCloseDebit, tvCloseCredit;

        VH(@NonNull View v) {
            super(v);
            tvCode = v.findViewById(R.id.tvRowCode);
            tvName = v.findViewById(R.id.tvRowName);
            tvGold = v.findViewById(R.id.tvRowGold);
            tvOpenDebit = v.findViewById(R.id.tvOpenDebit);
            tvOpenCredit = v.findViewById(R.id.tvOpenCredit);
            tvPeriodDebit = v.findViewById(R.id.tvPeriodDebit);
            tvPeriodCredit = v.findViewById(R.id.tvPeriodCredit);
            tvCloseDebit = v.findViewById(R.id.tvCloseDebit);
            tvCloseCredit = v.findViewById(R.id.tvCloseCredit);
        }
    }
}
