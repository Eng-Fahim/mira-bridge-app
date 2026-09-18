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
 * ✅ Adapter لعرض Timeline آخر العمليات
 * 
 * ✅ v2.0:
 *  - احترام كامل للثيمات (فاتح/داكن)
 *  - استخدام ContextCompat.getColor
 */
public class ActivityTimelineAdapter extends RecyclerView.Adapter<ActivityTimelineAdapter.ViewHolder> {

    private final List<JSONObject> items = new ArrayList<>();
    private final boolean isArabic;
    private final OnActivityClickListener clickListener;

    public interface OnActivityClickListener {
        void onActivityClick(JSONObject activity);
    }

    public ActivityTimelineAdapter(boolean isArabic, OnActivityClickListener listener) {
        this.isArabic = isArabic;
        this.clickListener = listener;
    }

    public void submitList(List<JSONObject> newList) {
        items.clear();
        if (newList != null) items.addAll(newList);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_activity_timeline, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        JSONObject item = items.get(position);
        if (item == null) return;

        try {
            Context ctx = holder.itemView.getContext();

            String type = item.optString("type", "system");
            String icon = item.optString("icon", "⚙️");
            String status = item.optString("status", "neutral");
            String titleAr = item.optString("title_ar", "");
            String titleEn = item.optString("title_en", "");
            String detailsAr = item.optString("details_ar", "");
            String detailsEn = item.optString("details_en", "");
            String user = item.optString("user", "—");
            String timestamp = item.optString("timestamp", "");
            double amount = item.optDouble("amount", 0);
            String currency = item.optString("currency", "USD");
            double accuracy = item.optDouble("accuracy", -1);

            String title = isArabic ? titleAr : titleEn;
            if (title.isEmpty()) title = titleAr.isEmpty() ? titleEn : titleAr;

            String details = isArabic ? detailsAr : detailsEn;
            if (details.isEmpty()) details = detailsAr.isEmpty() ? detailsEn : detailsAr;

            String time = extractTime(timestamp);

            // ✅ البيانات
            holder.tvActivityIcon.setText(icon);
            holder.tvActivityTitle.setText(title);
            holder.tvActivityDetails.setText(details);
            holder.tvActivityUser.setText("👤 " + user);
            holder.tvActivityTime.setText(time);

            // ✅ المبلغ / الدقة
            if (amount > 0) {
                holder.tvActivityAmount.setVisibility(View.VISIBLE);
                holder.tvActivityAmount.setText(formatAmount(amount, currency));
            } else if (accuracy >= 0) {
                holder.tvActivityAmount.setVisibility(View.VISIBLE);
                holder.tvActivityAmount.setText(String.format(Locale.US, "🎯 %.1f%%", accuracy));
            } else {
                holder.tvActivityAmount.setVisibility(View.GONE);
            }

            // ═══════════════════════════════════════════════════════
            // ✅ الألوان (تحترم الثيم)
            // ═══════════════════════════════════════════════════════
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

            holder.tvActivityAmount.setTextColor(color);
            holder.tvActivityIcon.setTextColor(color);

            if (holder.iconContainer != null) {
                holder.iconContainer.setBackgroundResource(R.drawable.bg_activity_icon);
                holder.iconContainer.getBackground().setTint(bgColor);
            }

            // ✅ Chip الحالة
            if (holder.tvActivityStatusChip != null) {
                if ("danger".equals(status) || "warning".equals(status)) {
                    holder.tvActivityStatusChip.setVisibility(View.VISIBLE);
                    if ("danger".equals(status)) {
                        holder.tvActivityStatusChip.setText(isArabic ? "⚠️ حرج" : "⚠️ Critical");
                    } else {
                        holder.tvActivityStatusChip.setText(isArabic ? "⚠️ تحذير" : "⚠️ Warning");
                    }
                    holder.tvActivityStatusChip.setTextColor(color);
                } else {
                    holder.tvActivityStatusChip.setVisibility(View.GONE);
                }
            }

            // ✅ onClick
            if (clickListener != null) {
                holder.itemView.setOnClickListener(v -> clickListener.onActivityClick(item));
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String extractTime(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) return "";
        try {
            String[] parts = dateStr.split(" ");
            if (parts.length >= 2) {
                String[] timeParts = parts[1].split(":");
                if (timeParts.length >= 2) {
                    return timeParts[0] + ":" + timeParts[1];
                }
            }
        } catch (Exception ignored) {}
        return dateStr;
    }

    private String formatAmount(double amount, String currency) {
        try {
            NumberFormat fmt = NumberFormat.getNumberInstance(Locale.US);
            fmt.setMaximumFractionDigits(2);
            fmt.setMinimumFractionDigits(0);
            return fmt.format(amount) + " " + currency;
        } catch (Exception e) {
            return String.format(Locale.US, "%.2f %s", amount, currency);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final FrameLayout iconContainer;
        final TextView tvActivityIcon, tvActivityTitle, tvActivityDetails,
                tvActivityUser, tvActivityTime, tvActivityAmount, tvActivityStatusChip;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            iconContainer = itemView.findViewById(R.id.iconContainer);
            tvActivityIcon = itemView.findViewById(R.id.tvActivityIcon);
            tvActivityTitle = itemView.findViewById(R.id.tvActivityTitle);
            tvActivityDetails = itemView.findViewById(R.id.tvActivityDetails);
            tvActivityUser = itemView.findViewById(R.id.tvActivityUser);
            tvActivityTime = itemView.findViewById(R.id.tvActivityTime);
            tvActivityAmount = itemView.findViewById(R.id.tvActivityAmount);
            tvActivityStatusChip = itemView.findViewById(R.id.tvActivityStatusChip);
        }
    }
}
