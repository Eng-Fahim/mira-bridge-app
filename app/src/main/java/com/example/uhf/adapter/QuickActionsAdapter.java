package com.example.uhf.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.manager.MiraQuickAccessSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class QuickActionsAdapter extends RecyclerView.Adapter<QuickActionsAdapter.ViewHolder> {

    private List<MiraQuickAccessSettings.QuickAction> items = new ArrayList<>();
    private final OnItemClickListener listener;
    private final boolean isArabic;

    public interface OnItemClickListener {
        void onItemClick(String actionId);
    }

    public QuickActionsAdapter(OnItemClickListener listener) {
        this.listener = listener;
        this.isArabic = Locale.getDefault().getLanguage().equals("ar");
    }

    public void submitList(List<MiraQuickAccessSettings.QuickAction> list) {
        this.items = list != null ? list : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_quick_action_card, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        MiraQuickAccessSettings.QuickAction item = items.get(position);

        // ✅ استخدام الحقول مباشرة ودعم اللغة
        holder.tvTitle.setText(isArabic ? item.titleAr : item.titleEn);
        
        // ✅ ربط الأيقونة بحسب الـ ID
        holder.imgIcon.setImageResource(getIconForAction(item.id));

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(item.id);
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    // 🎨 خريطة تعيين الأيقونات بحسب معرف الإجراء
    private int getIconForAction(String id) {
        if (id == null) return R.drawable.ic_launcher_foreground;
        
        switch (id) {
            case "identity":
                return R.drawable.ic_identity; // استبدل بأسماء الموارد لديك
            case "radar":
                return R.drawable.ic_radar;
            case "gate":
                return R.drawable.ic_gate;
            case "reports":
                return R.drawable.ic_reports;
            case "accounting":
                return R.drawable.ic_accounting;
            case "transfers":
                return R.drawable.ic_transfer;
            case "admin_hub":
                return R.drawable.ic_admin;
            case "sales_history":
                return R.drawable.ic_history;
            case "server_config":
                return R.drawable.ic_settings;
            case "gold_rates":
                return R.drawable.ic_gold;
            case "users":
                return R.drawable.ic_users;
            default:
                return R.drawable.ic_launcher_foreground;
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvTitle;
        ImageView imgIcon;

        ViewHolder(View view) {
            super(view);
            tvTitle = view.findViewById(R.id.tvActionTitle);
            imgIcon = view.findViewById(R.id.imgActionIcon);
        }
    }
}
