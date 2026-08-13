package com.example.uhf.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.model.MiraAccountingItem;

import java.util.ArrayList;
import java.util.List;

public class MiraAccountingAdapter extends RecyclerView.Adapter<MiraAccountingAdapter.AccountingViewHolder> {

    private final List<MiraAccountingItem> itemList = new ArrayList<>();

    public void setItems(List<MiraAccountingItem> items) {
        this.itemList.clear();
        if (items != null) {
            this.itemList.addAll(items);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public AccountingViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_mira_accounting_row, parent, false);
        return new AccountingViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AccountingViewHolder holder, int position) {
        MiraAccountingItem item = itemList.get(position);

        holder.tvCol1.setText(item.getCol1Text());
        holder.tvCol2.setText(item.getCol2Text());
        holder.tvCol3.setText(item.getCol3Text());
        holder.tvCol4.setText(item.getCol4Text());

        // تمييز ألوان القيم المحاسبية (الذهب الصافي والأموال)
        if (item.getItemType() == MiraAccountingItem.ItemType.JOURNAL_ENTRY) {
            double netGold = item.getFineWeight24Debit() - item.getFineWeight24Credit();
            double netCash = item.getDebitCash() - item.getCreditCash();

            holder.tvCol3.setTextColor(netGold >= 0 ? Color.parseColor("#F1C40F") : Color.parseColor("#E74C3C"));
            holder.tvCol4.setTextColor(netCash >= 0 ? Color.parseColor("#2ECC71") : Color.parseColor("#E74C3C"));
        } else {
            holder.tvCol3.setTextColor(Color.parseColor("#F1C40F"));
            holder.tvCol4.setTextColor(Color.parseColor("#2ECC71"));
        }
    }

    @Override
    public int getItemCount() {
        return itemList.size();
    }

    static class AccountingViewHolder extends RecyclerView.ViewHolder {
        TextView tvCol1, tvCol2, tvCol3, tvCol4;

        public AccountingViewHolder(@NonNull View itemView) {
            super(itemView);
            tvCol1 = itemView.findViewById(R.id.tvRowCol1);
            tvCol2 = itemView.findViewById(R.id.tvRowCol2);
            tvCol3 = itemView.findViewById(R.id.tvRowCol3);
            tvCol4 = itemView.findViewById(R.id.tvRowCol4);
        }
    }
}
