package com.example.uhf.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
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
            String type = item.optString("type", "");
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

            // ✅ النصوص
            holder.tvIconAll.setText(icon);
            holder.tvTitleAll.setText(title);
            holder.tvDetailsAll.setText(details);
            holder.tvUserAll.setText("👤 " + user);
            holder.tvTimeAll.setText(formatFullTime(timestamp));

            // ✅ القيمة (المبلغ أو الدقة)
            if (amount > 0) {
                holder.tvValueAll.setText(formatAmount(amount, currency));
                holder.tvValueAll.setVisibility(View.VISIBLE);
            } else if (accuracy >= 0) {
                holder.tvValueAll.setText(String.format(Locale.US, "🎯 %.1f%%", accuracy));
                holder.tvValueAll.setVisibility(View.VISIBLE);
            } else {
                holder.tvValueAll.setVisibility(View.GONE);
            }

            // ✅ الألوان
            int color;
            int bgColor;

            switch (status) {
                case "success":
                    color = Color.parseColor("#16A34A");
                    bgColor = Color.parseColor("#DCFCE7");
                    break;
                case "warning":
                    color = Color.parseColor("#F59E0B");
                    bgColor = Color.parseColor("#FEF3C7");
                    break;
                case "danger":
                    color = Color.parseColor("#DC2626");
                    bgColor = Color.parseColor("#FEE2E2");
                    break;
                default:
                    color = Color.parseColor("#6B7280");
                    bgColor = Color.parseColor("#F3F4F6");
                    break;
            }

            holder.tvValueAll.setTextColor(color);
            holder.tvIconAll.setTextColor(color);
            if (holder.iconContainerAll != null) {
                holder.iconContainerAll.getBackground().setTint(bgColor);
            }

            // ✅ Chip الحالة
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

            // ✅ onClick
            if (clickListener != null) {
                holder.itemView.setOnClickListener(v -> clickListener.onActivityClick(item));
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * ✅ تنسيق التاريخ الكامل
     */
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

    /**
     * ✅ تنسيق المبلغ
     */
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
