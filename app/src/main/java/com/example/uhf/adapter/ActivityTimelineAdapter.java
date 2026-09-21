package com.example.uhf.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;

import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * ✅ Adapter لعرض Timeline آخر العمليات
 * - يستخدم ListAdapter + DiffUtil لتحديث سلس وبدون ANR
 */
public class ActivityTimelineAdapter 
        extends ListAdapter<JSONObject, ActivityTimelineAdapter.ViewHolder> {

    private final boolean isArabic;
    private final OnActivityClickListener clickListener;

    // ═══════════════════════════════════════════════════════
    // ✅ DiffUtil: يقارن العناصر بكفاءة عالية
    // ═══════════════════════════════════════════════════════
    private static final DiffUtil.ItemCallback<JSONObject> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<JSONObject>() {
                @Override
                public boolean areItemsTheSame(@NonNull JSONObject oldItem,
                                               @NonNull JSONObject newItem) {
                    // ✅ معرّف فريد لكل عملية
                    String oldId = oldItem.optString("id", "");
                    String newId = newItem.optString("id", "");
                    if (!oldId.isEmpty() && !newId.isEmpty()) {
                        return oldId.equals(newId);
                    }
                    // fallback: قارن بالـ timestamp + type
                    return oldItem.optString("timestamp").equals(newItem.optString("timestamp"))
                            && oldItem.optString("type").equals(newItem.optString("type"));
                }

                @Override
                public boolean areContentsTheSame(@NonNull JSONObject oldItem,
                                                  @NonNull JSONObject newItem) {
                    // ✅ قارن الحقول المهمة فقط (سريع جداً)
                    return oldItem.optString("status").equals(newItem.optString("status"))
                            && oldItem.optString("title_ar").equals(newItem.optString("title_ar"))
                            && oldItem.optString("title_en").equals(newItem.optString("title_en"))
                            && oldItem.optString("details_ar").equals(newItem.optString("details_ar"))
                            && oldItem.optString("details_en").equals(newItem.optString("details_en"))
                            && oldItem.optString("user").equals(newItem.optString("user"))
                            && oldItem.optDouble("amount", 0) == newItem.optDouble("amount", 0)
                            && oldItem.optDouble("accuracy", -1) == newItem.optDouble("accuracy", -1);
                }
            };

    public interface OnActivityClickListener {
        void onActivityClick(JSONObject activity);
    }

    public ActivityTimelineAdapter(boolean isArabic, OnActivityClickListener listener) {
        super(DIFF_CALLBACK);
        this.isArabic = isArabic;
        this.clickListener = listener;
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
        JSONObject item = getItem(position);
        if (item == null) return;

        try {
            // ✅ البيانات
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
            // ✅ الألوان حسب الحالة
            // ═══════════════════════════════════════════════════════
            int color;
            int iconBgColor;

            switch (status) {
                case "success":
                    color = Color.parseColor("#16A34A");
                    iconBgColor = Color.parseColor("#DCFCE7");
                    break;
                case "warning":
                    color = Color.parseColor("#F59E0B");
                    iconBgColor = Color.parseColor("#FEF3C7");
                    break;
                case "danger":
                    color = Color.parseColor("#DC2626");
                    iconBgColor = Color.parseColor("#FEE2E2");
                    break;
                default:
                    color = Color.parseColor("#6B7280");
                    iconBgColor = Color.parseColor("#F3F4F6");
                    break;
            }

            holder.tvActivityAmount.setTextColor(color);
            holder.tvActivityIcon.setTextColor(color);

            if (holder.iconContainer != null) {
                holder.iconContainer.setBackgroundResource(R.drawable.bg_activity_icon);
                holder.iconContainer.getBackground().setTint(iconBgColor);
            }

            // ✅ Chip الحالة
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
