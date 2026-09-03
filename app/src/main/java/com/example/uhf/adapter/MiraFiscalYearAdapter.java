package com.example.uhf.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.model.MiraFiscalYearItem;
import com.google.android.material.chip.Chip;

import java.util.List;
import java.util.Locale;

public class MiraFiscalYearAdapter extends RecyclerView.Adapter<MiraFiscalYearAdapter.ViewHolder> {

    public interface OnItemClickListener {
        void onItemLongClick(MiraFiscalYearItem item);
    }

    private final List<MiraFiscalYearItem> items;
    private final boolean isArabic;
    private final OnItemClickListener listener;

    // ✅ ألوان ثابتة
    private static final int COLOR_POSITIVE = Color.parseColor("#27AE60");
    private static final int COLOR_NEGATIVE = Color.parseColor("#E74C3C");
    private static final int COLOR_NEUTRAL = Color.parseColor("#95A5A6");
    private static final int COLOR_GOLD = Color.parseColor("#D35400");

    public MiraFiscalYearAdapter(List<MiraFiscalYearItem> items, boolean isArabic, OnItemClickListener listener) {
        this.items = items;
        this.isArabic = isArabic;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_mira_fiscal_year, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        // ✅ حماية من null
        if (items == null || position < 0 || position >= items.size()) return;
        
        MiraFiscalYearItem item = items.get(position);
        holder.tvYearName.setText(item.getYearName());
        holder.tvDates.setText(String.format(Locale.US, "%s  ➔  %s", item.getStartDate(), item.getEndDate()));
        
        // ✅✅✅ النقد مع علامة ولون
        double cash = item.getClosingCashBalance();
        if (cash > 0) {
            holder.tvClosingCash.setText(String.format(Locale.US, "+%,.2f", cash));
            holder.tvClosingCash.setTextColor(COLOR_POSITIVE);
        } else if (cash < 0) {
            holder.tvClosingCash.setText(String.format(Locale.US, "%,.2f", cash));
            holder.tvClosingCash.setTextColor(COLOR_NEGATIVE);
        } else {
            holder.tvClosingCash.setText("0.00");
            holder.tvClosingCash.setTextColor(COLOR_NEUTRAL);
        }
        
        // ✅✅✅ الذهب مع علامة ولون
        double gold = item.getClosingGoldWeight24k();
        if (gold > 0) {
            holder.tvClosingGold.setText(String.format(Locale.US, "+%.3f g", gold));
            holder.tvClosingGold.setTextColor(COLOR_GOLD);
        } else if (gold < 0) {
            holder.tvClosingGold.setText(String.format(Locale.US, "%.3f g", gold));
            holder.tvClosingGold.setTextColor(COLOR_NEGATIVE);
        } else {
            holder.tvClosingGold.setText("0.000 g");
            holder.tvClosingGold.setTextColor(COLOR_NEUTRAL);
        }

        // ✅✅✅ حالة السنة
        if (item.isClosed()) {
            holder.chipStatus.setText(isArabic ? "🔒 مغلقة" : "🔒 Closed");
            holder.chipStatus.setChipBackgroundColor(COLOR_NEGATIVE);
            holder.chipStatus.setTextColor(Color.WHITE);
        } else {
            holder.chipStatus.setText(isArabic ? "✅ مفتوحة" : "✅ Active");
            holder.chipStatus.setChipBackgroundColor(COLOR_POSITIVE);
            holder.chipStatus.setTextColor(Color.WHITE);
        }

        holder.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onItemLongClick(item);
            return true;
        });
    }

    @Override
    public int getItemCount() { 
        return items != null ? items.size() : 0; 
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvYearName, tvDates, tvClosingCash, tvClosingGold;
        Chip chipStatus;

        ViewHolder(View itemView) {
            super(itemView);
            tvYearName = itemView.findViewById(R.id.tvFiscalYearName);
            tvDates = itemView.findViewById(R.id.tvFiscalDates);
            tvClosingCash = itemView.findViewById(R.id.tvClosingCash);
            tvClosingGold = itemView.findViewById(R.id.tvClosingGold);
            chipStatus = itemView.findViewById(R.id.chipStatus);
        }
    }
}
