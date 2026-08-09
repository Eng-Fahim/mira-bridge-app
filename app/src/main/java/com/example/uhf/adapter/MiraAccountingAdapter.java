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

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

public class MiraAccountingAdapter extends RecyclerView.Adapter<MiraAccountingAdapter.AccountingViewHolder> {

    private List<MiraAccountingItem> itemList = new ArrayList<>();
    private OnItemClickListener listener;
    private final DecimalFormat dfGold = new DecimalFormat("#,##0.000");
    private final DecimalFormat dfCash = new DecimalFormat("#,##0.00");

    public interface OnItemClickListener {
        void onItemClick(MiraAccountingItem item);
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

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

        holder.tvRowCol1.setText(item.getCodeOrRef());
        holder.tvRowCol2.setText(item.getNameOrDesc());

        // ربط شارة الـ RFID إن وجدت
        if (holder.tvRowRfidTag != null) {
            if (item.getRfidTag() != null && !item.getRfidTag().trim().isEmpty()) {
                holder.tvRowRfidTag.setText("RFID: " + item.getRfidTag());
                holder.tvRowRfidTag.setVisibility(View.VISIBLE);
            } else {
                holder.tvRowRfidTag.setVisibility(View.GONE);
            }
        }

        if (item.getItemType() == MiraAccountingItem.ItemType.JOURNAL_ENTRY) {
            // العمود 3: صافي الذهب 24 (مدين - دائن)
            double netGold = item.getFineGold24Debit() - item.getFineGold24Credit();
            holder.tvRowCol3.setText(dfGold.format(netGold) + " جم");
            holder.tvRowCol3.setTextColor(netGold >= 0 ? Color.parseColor("#D35400") : Color.parseColor("#E67E22"));

            // العمود 4: الحركة النقدية (مدين - دائن)
            double netCash = item.getDebitCash() - item.getCreditCash();
            holder.tvRowCol4.setText(dfCash.format(netCash) + " " + item.getCurrency());
            holder.tvRowCol4.setTextColor(netCash >= 0 ? Color.parseColor("#27AE60") : Color.parseColor("#C0392B"));

        } else if (item.getItemType() == MiraAccountingItem.ItemType.CHART_OF_ACCOUNT) {
            holder.tvRowCol3.setText(dfGold.format(item.getFineGoldBalance()) + " جم");
            holder.tvRowCol3.setTextColor(Color.parseColor("#F1C40F"));

            holder.tvRowCol4.setText(dfCash.format(item.getCashBalance()) + " " + item.getCurrency());
            holder.tvRowCol4.setTextColor(Color.parseColor("#2C3E50"));

        } else {
            // ميزان المراجعة
            holder.tvRowCol3.setText(dfGold.format(item.getFineGoldBalance()) + " جم 24");
            holder.tvRowCol3.setTextColor(Color.parseColor("#D35400"));

            holder.tvRowCol4.setText(dfCash.format(item.getCashBalance()) + " " + item.getCurrency());
            holder.tvRowCol4.setTextColor(Color.parseColor("#27AE60"));
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(item);
            }
        });
    }

    @Override
    public int getItemCount() {
        return itemList.size();
    }

    static class AccountingViewHolder extends RecyclerView.ViewHolder {
        TextView tvRowCol1, tvRowCol2, tvRowCol3, tvRowCol4, tvRowRfidTag;

        public AccountingViewHolder(@NonNull View itemView) {
            super(itemView);
            tvRowCol1 = itemView.findViewById(R.id.tvRowCol1);
            tvRowCol2 = itemView.findViewById(R.id.tvRowCol2);
            tvRowCol3 = itemView.findViewById(R.id.tvRowCol3);
            tvRowCol4 = itemView.findViewById(R.id.tvRowCol4);
            tvRowRfidTag = itemView.findViewById(R.id.tvRowRfidTag);
        }
    }
}
