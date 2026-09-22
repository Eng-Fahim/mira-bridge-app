package com.example.uhf.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.model.InventorySnapshot;

import java.util.Locale;

/**
 * ✅ SnapshotAdapter — عرض قائمة اللقطات
 *
 * يستخدم ListAdapter + DiffUtil للأداء الأمثل
 */
public class SnapshotAdapter 
        extends ListAdapter<InventorySnapshot, SnapshotAdapter.ViewHolder> {

    public interface OnSnapshotClickListener {
        void onSnapshotClick(@NonNull InventorySnapshot snapshot);
    }

    public interface OnSnapshotLongClickListener {
        void onSnapshotLongClick(@NonNull InventorySnapshot snapshot);
    }

    private final boolean isArabic;
    private OnSnapshotClickListener clickListener;
    private OnSnapshotLongClickListener longClickListener;

    private static final DiffUtil.ItemCallback<InventorySnapshot> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<InventorySnapshot>() {
                @Override
                public boolean areItemsTheSame(@NonNull InventorySnapshot oldItem,
                                               @NonNull InventorySnapshot newItem) {
                    return oldItem.getId().equals(newItem.getId());
                }

                @Override
                public boolean areContentsTheSame(@NonNull InventorySnapshot oldItem,
                                                  @NonNull InventorySnapshot newItem) {
                    return oldItem.getTimestamp() == newItem.getTimestamp()
                            && oldItem.getTotalItems() == newItem.getTotalItems()
                            && oldItem.getTotalWeight() == newItem.getTotalWeight()
                            && oldItem.getTotalValue() == newItem.getTotalValue();
                }
            };

    public SnapshotAdapter(boolean isArabic,
                           @NonNull OnSnapshotClickListener clickListener,
                           @NonNull OnSnapshotLongClickListener longClickListener) {
        super(DIFF_CALLBACK);
        this.isArabic = isArabic;
        this.clickListener = clickListener;
        this.longClickListener = longClickListener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_snapshot, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        InventorySnapshot snapshot = getItem(position);
        if (snapshot == null) return;

        try {
            // ✅ الاسم
            if (holder.tvName != null) {
                String name = snapshot.getName();
                if (name == null || name.isEmpty()) {
                    name = isArabic ? "لقطة غير مسماة" : "Unnamed Snapshot";
                }
                holder.tvName.setText(name);
            }

            // ✅ الوقت النسبي
            if (holder.tvTime != null) {
                holder.tvTime.setText(snapshot.getRelativeTime(isArabic));
            }

            // ✅ التاريخ الكامل
            if (holder.tvDate != null) {
                holder.tvDate.setText(snapshot.getFormattedDate());
            }

            // ✅ العدد
            if (holder.tvItems != null) {
                holder.tvItems.setText(isArabic
                        ? "📦 " + snapshot.getTotalItems() + " قطعة"
                        : "📦 " + snapshot.getTotalItems() + " items");
            }

            // ✅ الوزن
            if (holder.tvWeight != null) {
                holder.tvWeight.setText(String.format(Locale.US,
                        "⚖️ %.2f g", snapshot.getTotalWeight()));
            }

            // ✅ القيمة
            if (holder.tvValue != null) {
                holder.tvValue.setText(String.format(Locale.US,
                        "💰 $%,.2f", snapshot.getTotalValue()));
            }

            // ✅ المطابقة (المتوقع vs المسح)
            if (holder.tvAccuracy != null) {
                int expected = snapshot.getExpectedCount();
                int scanned = snapshot.getTotalItems();

                if (expected > 0) {
                    int percentage = Math.min((scanned * 100) / expected, 100);
                    int color;
                    if (percentage >= 100) {
                        color = 0xFF16A34A;  // أخضر
                    } else if (percentage >= 70) {
                        color = 0xFFF59E0B;  // برتقالي
                    } else {
                        color = 0xFFDC2626;  // أحمر
                    }
                    holder.tvAccuracy.setText("🎯 " + percentage + "%");
                    holder.tvAccuracy.setTextColor(color);
                    holder.tvAccuracy.setVisibility(View.VISIBLE);
                } else {
                    holder.tvAccuracy.setText(isArabic ? "⚡ أعمى" : "⚡ Blind");
                    holder.tvAccuracy.setTextColor(0xFF6B7280);
                    holder.tvAccuracy.setVisibility(View.VISIBLE);
                }
            }

            // ✅ الفرع
            if (holder.tvBranch != null) {
                String branch = snapshot.getBranchName();
                if (branch != null && !branch.isEmpty()) {
                    holder.tvBranch.setText("🏢 " + branch);
                    holder.tvBranch.setVisibility(View.VISIBLE);
                } else {
                    holder.tvBranch.setVisibility(View.GONE);
                }
            }

            // ✅ المستخدم
            if (holder.tvUser != null) {
                String user = snapshot.getCreatedBy();
                if (user != null && !user.isEmpty()) {
                    holder.tvUser.setText("👤 " + user);
                    holder.tvUser.setVisibility(View.VISIBLE);
                } else {
                    holder.tvUser.setVisibility(View.GONE);
                }
            }

            // ✅ onClick
            if (clickListener != null) {
                holder.itemView.setOnClickListener(v -> {
                    try {
                        clickListener.onSnapshotClick(snapshot);
                    } catch (Exception ignored) {}
                });
            }

            // ✅ onLongClick
            if (longClickListener != null) {
                holder.itemView.setOnLongClickListener(v -> {
                    try {
                        longClickListener.onSnapshotLongClick(snapshot);
                    } catch (Exception ignored) {}
                    return true;
                });
            }

        } catch (Exception e) {
            android.util.Log.e("SnapshotAdapter", "bind error", e);
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView tvName;
        final TextView tvTime;
        final TextView tvDate;
        final TextView tvItems;
        final TextView tvWeight;
        final TextView tvValue;
        final TextView tvAccuracy;
        final TextView tvBranch;
        final TextView tvUser;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tvSnapshotName);
            tvTime = itemView.findViewById(R.id.tvSnapshotTime);
            tvDate = itemView.findViewById(R.id.tvSnapshotDate);
            tvItems = itemView.findViewById(R.id.tvSnapshotItems);
            tvWeight = itemView.findViewById(R.id.tvSnapshotWeight);
            tvValue = itemView.findViewById(R.id.tvSnapshotValue);
            tvAccuracy = itemView.findViewById(R.id.tvSnapshotAccuracy);
            tvBranch = itemView.findViewById(R.id.tvSnapshotBranch);
            tvUser = itemView.findViewById(R.id.tvSnapshotUser);
        }
    }
}
