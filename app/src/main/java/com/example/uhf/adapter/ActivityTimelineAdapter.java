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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ✅ Adapter لعرض Timeline آخر العمليات
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
            // ✅ البيانات
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

            // ✅ العنوان
            String title = isArabic ? titleAr : titleEn;
            if (title.isEmpty()) title = titleAr.isEmpty() ? titleEn : titleAr;

            // ✅ التفاصيل
            String details = isArabic ? detailsAr : detailsEn;
            if (details.isEmpty()) details = detailsAr.isEmpty() ? detailsEn : detailsAr;

            // ✅ الوقت (HH:mm)
            String time = extractTime(timestamp);

            // ═══════════════════════════════════════════════════════
            // ✅ تطبيق البيانات
            // ═══════════════════════════════════════════════════════
            holder.tvActivityIcon.setText(icon);
            holder.tvActivityTitle.setText(title);
            holder.tvActivityDetails.setText(details);
            holder.tvActivityUser.setText("👤 " + user);
            holder.tvActivityTime.setText(time);

            // ✅ المبلغ (إذا موجود)
            if (amount > 0) {
                holder.tvActivityAmount.setVisibility(View.VISIBLE);
                holder.tvActivityAmount.setText(formatAmount(amount, currency));
            } else if (accuracy >= 0) {
                // ✅ الدقة بدلاً من المبلغ
                holder.tvActivityAmount.setVisibility(View.VISIBLE);
                holder.tvActivityAmount.setText(String.format(Locale.US, "🎯 %.1f%%", accuracy));
            } else {
                holder.tvActivityAmount.setVisibility(View.GONE);
            }

            // ═══════════════════════════════════════════════════════
            // ✅ الألوان حسب الحالة
            // ═══════════════════════════════════════════════════════
            int color;
            int iconBgColor;

            switch (status) {
                case "success":
                    color = Color.parseColor("#16A34A"); // أخضر
                    iconBgColor = Color.parseColor("#DCFCE7"); // أخضر فاتح
                    break;
                case "warning":
                    color = Color.parseColor("#F59E0B"); // برتقالي
                    iconBgColor = Color.parseColor("#FEF3C7"); // برتقالي فاتح
                    break;
                case "danger":
                    color = Color.parseColor("#DC2626"); // أحمر
                    iconBgColor = Color.parseColor("#FEE2E2"); // أحمر فاتح
                    break;
                default:
                    color = Color.parseColor("#6B7280"); // رمادي
                    iconBgColor = Color.parseColor("#F3F4F6"); // رمادي فاتح
                    break;
            }

            holder.tvActivityAmount.setTextColor(color);
            holder.tvActivityIcon.setTextColor(color);

            // ✅ خلفية الأيقونة
            if (holder.iconContainer != null) {
                holder.iconContainer.setBackgroundResource(R.drawable.bg_activity_icon);
                holder.iconContainer.getBackground().setTint(iconBgColor);
            }

            // ✅ Chip الحالة (اختياري)
            if (holder.tvActivityStatusChip != null) {
                if ("danger".equals(status) || "warning".equals(status)) {
                    holder.tvActivityStatusChip.setVisibility(View.VISIBLE);
                    if ("danger".equals(status)) {
                        holder.tvActivityStatusChip.setText(isArabic ? "⚠️ حرج" : "⚠️ Critical");
                        holder.tvActivityStatusChip.setTextColor(Color.parseColor("#DC2626"));
                    } else {
                        holder.tvActivityStatusChip.setText(isArabic ? "⚠️ تحذير" : "⚠️ Warning");
                        holder.tvActivityStatusChip.setTextColor(Color.parseColor("#F59E0B"));
                    }
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

    /**
     * ✅ استخراج الوقت (YYYY-MM-DD HH:MM:SS → HH:MM)
     */
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

    /**
     * ✅ تنسيق المبلغ
     */
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
