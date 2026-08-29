package com.example.uhf.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.model.ReportModels;

import java.util.List;

public class ReportGridAdapter extends RecyclerView.Adapter<ReportGridAdapter.ViewHolder> {

    public interface OnItemClickListener {
        void onItemClick(ReportModels.ReportItem item);
    }

    private final List<ReportModels.ReportItem> items;
    private final OnItemClickListener listener;

    public ReportGridAdapter(List<ReportModels.ReportItem> items, OnItemClickListener listener) {
        this.items = items;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_report_card, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ReportModels.ReportItem item = items.get(position);

        holder.tvTitle.setText(item.title);
        holder.tvSubtitle.setText(item.subtitle);
        
        // ربط الأيقونة المتجهية المباشرة
        holder.ivIcon.setImageResource(getIconDrawable(item.type));

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(item);
            }
        });
    }

    @Override
    public int getItemCount() {
        return items != null ? items.size() : 0;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvTitle, tvSubtitle;
        ImageView ivIcon;

        ViewHolder(View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tvReportTitle);
            tvSubtitle = itemView.findViewById(R.id.tvReportSubtitle);
            ivIcon = itemView.findViewById(R.id.ivReportIcon);
        }
    }

    // دالة تحويل نوع التقرير إلى أيقونة متجهات فاخرة
    private int getIconDrawable(String type) {
        if (type == null) return R.drawable.ic_report_general;

        switch (type.toUpperCase()) {
            case "DASHBOARD":
                return R.drawable.ic_dashboard;
            case "SALES":
                return R.drawable.ic_sales;
            case "INVENTORY":
                return R.drawable.ic_inventory;
            case "MOVEMENT":
            case "TRANSFERS":
                return R.drawable.ic_transfer;
            case "GATES":
                return R.drawable.ic_gate;
            default:
                return R.drawable.ic_report_general;
        }
    }
}
