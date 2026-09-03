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

    // ✅ شارة RFID
    String rfid = item.getRfidEpc();
    if (rfid != null && !rfid.trim().isEmpty()) {
        holder.tvRfidTag.setText("RFID: " + rfid);
        holder.tvRfidTag.setVisibility(View.VISIBLE);
    } else {
        holder.tvRfidTag.setVisibility(View.GONE);
    }

    // ✅ تنسيق الألوان
    if (item.getItemType() == MiraAccountingItem.ItemType.JOURNAL_ENTRY) {
        double netGold = item.getFineWeight24Debit() - item.getFineWeight24Credit();
        double netCash = item.getDebitCash() - item.getCreditCash();

        // ✅✅✅ تحسين الألوان
        if (netGold > 0) {
            holder.tvCol3.setTextColor(Color.parseColor("#D35400")); // برتقالي - زيادة ذهب
        } else if (netGold < 0) {
            holder.tvCol3.setTextColor(Color.parseColor("#E74C3C")); // أحمر - نقص ذهب
        } else {
            holder.tvCol3.setTextColor(Color.parseColor("#95A5A6")); // رمادي - لا ذهب
        }

        if (netCash > 0) {
            holder.tvCol4.setTextColor(Color.parseColor("#27AE60")); // أخضر - إيراد
        } else if (netCash < 0) {
            holder.tvCol4.setTextColor(Color.parseColor("#E74C3C")); // أحمر - مصروف
        } else {
            holder.tvCol4.setTextColor(Color.parseColor("#95A5A6")); // رمادي - لا نقد
        }
    } else {
        holder.tvCol3.setTextColor(Color.parseColor("#D35400"));
        holder.tvCol4.setTextColor(Color.parseColor("#27AE60"));
    }
}

    @Override
    public int getItemCount() {
        return itemList.size();
    }

    static class AccountingViewHolder extends RecyclerView.ViewHolder {
        TextView tvCol1, tvCol2, tvCol3, tvCol4, tvRfidTag;

        public AccountingViewHolder(@NonNull View itemView) {
            super(itemView);
            tvCol1 = itemView.findViewById(R.id.tvRowCol1);
            tvCol2 = itemView.findViewById(R.id.tvRowCol2);
            tvCol3 = itemView.findViewById(R.id.tvRowCol3);
            tvCol4 = itemView.findViewById(R.id.tvRowCol4);
            tvRfidTag = itemView.findViewById(R.id.tvRowRfidTag);
        }
    }
}
