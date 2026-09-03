package com.example.uhf.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.model.MiraReportRowItem;
import com.google.android.material.card.MaterialCardView;

import java.util.List;
import java.util.Locale;

public class MiraFinancialReportAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private List<MiraReportRowItem> items;
    private final boolean isArabic;

    public MiraFinancialReportAdapter(List<MiraReportRowItem> items, boolean isArabic) {
        this.items = items;
        this.isArabic = isArabic;
    }

    public void updateItems(List<MiraReportRowItem> newItems) {
        this.items = newItems;
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        // ✅ حماية من null أو index out of bounds
        if (items == null || position < 0 || position >= items.size()) {
            return MiraReportRowItem.TYPE_DATA;
        }
        return items.get(position).getViewType();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_mira_financial_report_row, parent, false);
        return new ReportViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (items == null || position >= items.size()) return;
        
        MiraReportRowItem item = items.get(position);
        ReportViewHolder vh = (ReportViewHolder) holder;

        if (item.getViewType() == MiraReportRowItem.TYPE_HEADER) {
            vh.tvHeaderTitle.setVisibility(View.VISIBLE);
            vh.cardDataRow.setVisibility(View.GONE);
            vh.tvHeaderTitle.setText(item.getTitle());
        } else {
            vh.tvHeaderTitle.setVisibility(View.GONE);
            vh.cardDataRow.setVisibility(View.VISIBLE);

            vh.tvAccountName.setText(item.getTitle());
            vh.tvCashAmount.setText(String.format(Locale.US, "%,.2f", item.getCashAmount()));

            // ✅✅✅ تلوين النقد
            if (item.getCashAmount() > 0) {
                vh.tvCashAmount.setTextColor(Color.parseColor("#27AE60"));
            } else if (item.getCashAmount() < 0) {
                vh.tvCashAmount.setTextColor(Color.parseColor("#E74C3C"));
            } else {
                vh.tvCashAmount.setTextColor(Color.parseColor("#95A5A6"));
            }

            // ✅✅✅ دعم القيم السالبة للذهب
            if (item.getGoldWeight24k() != 0 || !item.getKaratDetails().isEmpty()) {
                vh.layoutGoldDetails.setVisibility(View.VISIBLE);
                
                double goldWeight = item.getGoldWeight24k();
                if (goldWeight != 0) {
                    vh.tvGoldWeight24k.setText(String.format(Locale.US, "%+.3f g (24K)", goldWeight));
                    
                    if (goldWeight > 0) {
                        vh.tvGoldWeight24k.setTextColor(Color.parseColor("#D35400"));
                    } else {
                        vh.tvGoldWeight24k.setTextColor(Color.parseColor("#E74C3C"));
                    }
                } else {
                    vh.tvGoldWeight24k.setText("0.000 g (24K)");
                    vh.tvGoldWeight24k.setTextColor(Color.parseColor("#95A5A6"));
                }
                
                if (!item.getKaratDetails().isEmpty()) {
                    vh.tvKaratDetails.setVisibility(View.VISIBLE);
                    vh.tvKaratDetails.setText(item.getKaratDetails());
                } else {
                    vh.tvKaratDetails.setVisibility(View.GONE);
                }
            } else {
                vh.layoutGoldDetails.setVisibility(View.GONE);
            }
        }
    }

    @Override
    public int getItemCount() {
        return items != null ? items.size() : 0;
    }

    static class ReportViewHolder extends RecyclerView.ViewHolder {
        TextView tvHeaderTitle, tvAccountName, tvCashAmount, tvKaratDetails, tvGoldWeight24k;
        RelativeLayout layoutGoldDetails;
        MaterialCardView cardDataRow;

        public ReportViewHolder(@NonNull View itemView) {
            super(itemView);
            tvHeaderTitle = itemView.findViewById(R.id.tvHeaderTitle);
            cardDataRow = itemView.findViewById(R.id.cardDataRow);
            tvAccountName = itemView.findViewById(R.id.tvAccountName);
            tvCashAmount = itemView.findViewById(R.id.tvCashAmount);
            tvKaratDetails = itemView.findViewById(R.id.tvKaratDetails);
            tvGoldWeight24k = itemView.findViewById(R.id.tvGoldWeight24k);
            layoutGoldDetails = itemView.findViewById(R.id.layoutGoldDetails);
        }
    }
}
