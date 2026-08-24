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
        MiraFiscalYearItem item = items.get(position);
        holder.tvYearName.setText(item.getYearName());
        holder.tvDates.setText(String.format(Locale.US, "%s  ➔  %s", item.getStartDate(), item.getEndDate()));
        
        holder.tvClosingCash.setText(String.format(Locale.US, "%,.2f", item.getClosingCashBalance()));
        holder.tvClosingGold.setText(String.format(Locale.US, "%.3f g", item.getClosingGoldWeight24k()));

        if (item.isClosed()) {
            holder.chipStatus.setText(isArabic ? "🔒 مغلقة" : "🔒 Closed");
            holder.chipStatus.setChipBackgroundColorResource(android.R.color.holo_red_light);
            holder.chipStatus.setTextColor(Color.WHITE);
        } else {
            holder.chipStatus.setText(isArabic ? "✅ مفتوحة" : "✅ Active");
            holder.chipStatus.setChipBackgroundColorResource(android.R.color.holo_green_dark);
            holder.chipStatus.setTextColor(Color.WHITE);
        }

        holder.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onItemLongClick(item);
            return true;
        });
    }

    @Override
    public int getItemCount() { return items != null ? items.size() : 0; }

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
