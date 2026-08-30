package com.example.uhf.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.model.ReportModels;

import java.util.List;

public class ReportGridAdapter extends RecyclerView.Adapter<ReportGridAdapter.VH> {
    
    private final List<ReportModels.ReportItem> items;
    private final OnReportClickListener listener;
    private final boolean isArabic;
    
    public interface OnReportClickListener {
        void onClick(ReportModels.ReportItem item);
    }
    
    public ReportGridAdapter(List<ReportModels.ReportItem> items, 
                             OnReportClickListener listener, 
                             boolean isArabic) {
        this.items = items;
        this.listener = listener;
        this.isArabic = isArabic;
    }
    
    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_report_card_pro, parent, false);
        return new VH(view);
    }
    
    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        ReportModels.ReportItem item = items.get(pos);
        
        // ✅ استخدام tvReportIcon بدلاً من ivReportIcon
        h.tvIcon.setText(item.icon);
        h.tvTitle.setText(item.title);
        h.tvSubtitle.setText(item.subtitle);
        
        // تطبيق اللون
        if (item.color != 0 && h.flIconContainer != null) {
            h.flIconContainer.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                    h.itemView.getContext().getColor(item.color)));
        }
        
        // شارة متقدم
        if (h.chipAdvanced != null) {
            if (item.isAdvanced) {
                h.chipAdvanced.setVisibility(View.VISIBLE);
                h.chipAdvanced.setText(isArabic ? "✨ متقدم" : "✨ Advanced");
            } else {
                h.chipAdvanced.setVisibility(View.GONE);
            }
        }
        
        // النقر
        h.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onClick(item);
            }
        });
    }
    
    @Override
    public int getItemCount() {
        return items != null ? items.size() : 0;
    }
    
    class VH extends RecyclerView.ViewHolder {
        TextView tvIcon;      // ✅ TextView بدلاً من ImageView
        TextView tvTitle;
        TextView tvSubtitle;
        FrameLayout flIconContainer;
        TextView chipAdvanced;
        
        VH(View v) {
            super(v);
            tvIcon = v.findViewById(R.id.tvReportIcon);        // ✅ صحيح
            tvTitle = v.findViewById(R.id.tvReportTitle);
            tvSubtitle = v.findViewById(R.id.tvReportSubtitle);
            flIconContainer = v.findViewById(R.id.flIconContainer);
            chipAdvanced = v.findViewById(R.id.chipAdvanced);
        }
    }
}
