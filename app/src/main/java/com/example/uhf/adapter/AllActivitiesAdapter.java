package com.example.uhf.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;

import org.json.JSONObject;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ✅ Adapter لعرض جميع العمليات
 */
public class AllActivitiesAdapter extends RecyclerView.Adapter<AllActivitiesAdapter.ViewHolder> {

    private final List<JSONObject> items = new ArrayList<>();
    private final boolean isArabic;
    private final OnActivityClickListener clickListener;

    public interface OnActivityClickListener {
        void onActivityClick(JSONObject activity);
    }

    public AllActivitiesAdapter(boolean isArabic, OnActivityClickListener listener) {
        this.isArabic = isArabic;
        this.clickListener = listener;
    }

    public void submitList(List<JSONObject> newList) {
        items.clear();
        if (newList != null) items.addAll(newList);
        notifyDataSetChanged();
    }

    public void appendList(List<JSONObject> newList) {
        if (newList == null || newList.isEmpty()) return;
        int start = items.size();
        items.addAll(newList);
        notifyItemRangeInserted(start, newList.size());
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_all_activity, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        JSONObject item = items.get(position);
        if (item == null) return;

        try {
            Context ctx = holder.itemView.getContext();

            String icon = item.optString("icon", "⚙️");
            String status = item.optString("status", "neutral");
            String title = isArabic ? item.optString("title_ar", "") 
                                    : item.optString("title_en", "");
            String details = isArabic ? item.optString("details_ar", "") 
                                      : item.optString("details_en", "");
            String user = item.optString("user", "—");
            String timestamp = item.optString("timestamp", "");
            double amount = item.optDouble("amount", 0);
            String currency = item.optString("currency", "USD");
            double accuracy = item.optDouble("accuracy", -1);

            holder.tvIconAll.setText(icon);
            holder.tvTitleAll.setText(title);
            holder.tvDetailsAll.setText(details);
            holder.tvUserAll.setText("👤 " + user);
            holder.tvTimeAll.setText(formatFullTime(timestamp));

            if (amount > 0) {
                holder.tvValueAll.setText(formatAmount(amount, currency));
                holder.tvValueAll.setVisibility(View.VISIBLE);
            } else if (accuracy >= 0) {
                holder.tvValueAll.setText(String.format(Locale.US, "🎯 %.1f%%", accuracy));
                holder.tvValueAll.setVisibility(View.VISIBLE);
            } else {
                holder.tvValueAll.setVisibility(View.GONE);
            }

            int color;
            int bgColor;

            switch (status) {
                case "success":
                    color = ContextCompat.getColor(ctx, R.color.activity_success);
                    bgColor = ContextCompat.getColor(ctx, R.color.activity_success_bg);
                    break;
                case "warning":
                    color = ContextCompat.getColor(ctx, R.color.activity_warning);
                    bgColor = ContextCompat.getColor(ctx, R.color.activity_warning_bg);
                    break;
                case "danger":
                    color = ContextCompat.getColor(ctx, R.color.activity_danger);
                    bgColor = ContextCompat.getColor(ctx, R.color.activity_danger_bg);
                    break;
                default:
                    color = ContextCompat.getColor(ctx, R.color.activity_neutral);
                    bgColor = ContextCompat.getColor(ctx, R.color.activity_neutral_bg);
                    break;
            }

            holder.tvValueAll.setTextColor(color);
            holder.tvIconAll.setTextColor(color);
            if (holder.iconContainerAll != null) {
                holder.iconContainerAll.getBackground().setTint(bgColor);
            }

            if ("danger".equals(status)) {
                holder.tvStatusAll.setVisibility(View.VISIBLE);
                holder.tvStatusAll.setText(isArabic ? "⚠️ حرج" : "⚠️ Critical");
                holder.tvStatusAll.setTextColor(color);
            } else if ("warning".equals(status)) {
                holder.tvStatusAll.setVisibility(View.VISIBLE);
                holder.tvStatusAll.setText(isArabic ? "⚠️ تحذير" : "⚠️ Warning");
                holder.tvStatusAll.setTextColor(color);
            } else {
                holder.tvStatusAll.setVisibility(View.GONE);
            }

            if (clickListener != null) {
                holder.itemView.setOnClickListener(v -> clickListener.onActivityClick(item));
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String formatFullTime(String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) return "—";
        try {
            SimpleDateFormat input = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            SimpleDateFormat output = new SimpleDateFormat(
                    isArabic ? "d MMM yyyy • HH:mm" : "MMM d, yyyy • HH:mm",
                    isArabic ? new Locale("ar") : Locale.US);
            Date date = input.parse(timestamp);
            return output.format(date);
        } catch (Exception e) {
            return timestamp;
        }
    }

    private String formatAmount(double amount, String currency) {
        try {
            NumberFormat fmt = NumberFormat.getNumberInstance(Locale.US);
            fmt.setMaximumFractionDigits(2);
            return fmt.format(amount) + " " + currency;
        } catch (Exception e) {
            return String.format(Locale.US, "%.2f %s", amount, currency);
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final FrameLayout iconContainerAll;
        final TextView tvIconAll, tvTitleAll, tvValueAll, tvDetailsAll,
                tvUserAll, tvTimeAll, tvStatusAll;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            iconContainerAll = itemView.findViewById(R.id.iconContainerAll);
            tvIconAll = itemView.findViewById(R.id.tvIconAll);
            tvTitleAll = itemView.findViewById(R.id.tvTitleAll);
            tvValueAll = itemView.findViewById(R.id.tvValueAll);
            tvDetailsAll = itemView.findViewById(R.id.tvDetailsAll);
            tvUserAll = itemView.findViewById(R.id.tvUserAll);
            tvTimeAll = itemView.findViewById(R.id.tvTimeAll);
            tvStatusAll = itemView.findViewById(R.id.tvStatusAll);
        }
    }
}
