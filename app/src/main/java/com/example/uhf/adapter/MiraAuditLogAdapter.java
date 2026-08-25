package com.example.uhf.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.model.MiraAuditLogItem;
import com.google.android.material.chip.Chip;

import java.util.List;

public class MiraAuditLogAdapter extends RecyclerView.Adapter<MiraAuditLogAdapter.ViewHolder> {

    private final List<MiraAuditLogItem> items;
    private final boolean isArabic;

    public MiraAuditLogAdapter(List<MiraAuditLogItem> items, boolean isArabic) {
        this.items = items;
        this.isArabic = isArabic;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_mira_audit_log, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        MiraAuditLogItem item = items.get(position);
        holder.tvUser.setText(item.getUsername());
        holder.tvDate.setText(item.getCreatedAt());
        holder.tvDetails.setText(item.getDetails());

        setupActionChip(holder.chipAction, item.getAction());
    }

    private void setupActionChip(Chip chip, String action) {
        String label = action;
        int color = Color.GRAY;

        switch (action) {
            case "ACCOUNTING_CREATE":
            case "GOLD_RECEIPT":
                label = isArabic ? "✅ إنشاء سند" : "Create";
                color = Color.parseColor("#2ECC71");
                break;
            case "ACCOUNTING_UPDATE":
                label = isArabic ? "✏️ تعديل قيد" : "Update";
                color = Color.parseColor("#F39C12");
                break;
            case "ACCOUNTING_DELETE":
                label = isArabic ? "🗑️ إلغاء / حذف" : "Delete";
                color = Color.parseColor("#E74C3C");
                break;
            case "ACCOUNTING_CLOSE_YEAR":
                label = isArabic ? "🔒 إغلاق سنة" : "Close Year";
                color = Color.parseColor("#8E44AD");
                break;
            case "GOLD_ADJUSTMENT":
                label = isArabic ? "⚖️ تسوية أوزان" : "Gold Adjust";
                color = Color.parseColor("#3498DB");
                break;
        }

        chip.setText(label);
        chip.setChipBackgroundColorResource(android.R.color.transparent);
        chip.setTextColor(color);
        chip.setStrokeColorResource(android.R.color.transparent);
    }

    @Override
    public int getItemCount() { return items != null ? items.size() : 0; }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvUser, tvDate, tvDetails;
        Chip chipAction;

        ViewHolder(View itemView) {
            super(itemView);
            tvUser = itemView.findViewById(R.id.tvAuditUser);
            tvDate = itemView.findViewById(R.id.tvAuditDate);
            tvDetails = itemView.findViewById(R.id.tvAuditDetails);
            chipAction = itemView.findViewById(R.id.chipAuditAction);
        }
    }
}
