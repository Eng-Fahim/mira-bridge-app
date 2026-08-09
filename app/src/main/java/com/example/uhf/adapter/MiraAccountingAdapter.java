package com.example.uhf.adapter;

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

    private List<MiraAccountingItem> itemList = new ArrayList<>();

    public void setItems(List<MiraAccountingItem> items) {
        this.itemList = items != null ? items : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public AccountingViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_mira_accounting_row, parent, false);
        return new AccountingViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AccountingViewHolder holder, int position) {
        MiraAccountingItem item = itemList.get(position);

        if (holder.tvRowCol1 != null) holder.tvRowCol1.setText(item.getCol1Text());
        if (holder.tvRowCol2 != null) holder.tvRowCol2.setText(item.getCol2Text());
        if (holder.tvRowCol3 != null) holder.tvRowCol3.setText(item.getCol3Text());
        if (holder.tvRowCol4 != null) holder.tvRowCol4.setText(item.getCol4Text());
    }

    @Override
    public int getItemCount() {
        return itemList.size();
    }

    static class AccountingViewHolder extends RecyclerView.ViewHolder {
        TextView tvRowCol1, tvRowCol2, tvRowCol3, tvRowCol4;

        public AccountingViewHolder(@NonNull View itemView) {
            super(itemView);
            tvRowCol1 = itemView.findViewById(R.id.tvRowCol1);
            tvRowCol2 = itemView.findViewById(R.id.tvRowCol2);
            tvRowCol3 = itemView.findViewById(R.id.tvRowCol3);
            tvRowCol4 = itemView.findViewById(R.id.tvRowCol4);
        }
    }
}
