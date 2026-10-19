package com.example.uhf.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.model.ScannedItemDisplay;

import java.util.Locale;

/**
 * ✅ ModernScannedItemsAdapter - محسّن بالأداء + Animations
 * 
 * الميزات:
 * - ListAdapter + DiffUtil لتحديث سلس
 * - Animation على العناصر الجديدة
 * - Ripple effect عند الضغط
 */
public class ModernScannedItemsAdapter 
        extends ListAdapter<ScannedItemDisplay, ModernScannedItemsAdapter.ViewHolder> {

    public interface OnItemClickListener {
        void onItemClick(ScannedItemDisplay item);
    }

    private final boolean isArabic;
    private final OnItemClickListener listener;
    
    // ✅ تتبع آخر position لعرضه (لمنع animation عند التمرير)
    private int lastAnimatedPosition = -1;

    private static final DiffUtil.ItemCallback<ScannedItemDisplay> DIFF_CALLBACK = 
        new DiffUtil.ItemCallback<ScannedItemDisplay>() {
            @Override
            public boolean areItemsTheSame(@NonNull ScannedItemDisplay oldItem, 
                                           @NonNull ScannedItemDisplay newItem) {
                return oldItem.epc != null && oldItem.epc.equals(newItem.epc);
            }

            @Override
public boolean areContentsTheSame(@NonNull ScannedItemDisplay oldItem, 
                                  @NonNull ScannedItemDisplay newItem) {
    // ✅ [Fix-A3] المقارنة الكاملة — يشمل title/karat/weight/price
    // السبب: بعد reclassifyScannedItems() تُحدَّث هذه الحقول في الذاكرة
    // لكن UI لا يتحدث إلا إذا كانت هذه الحقول في المقارنة
    if (oldItem.count != newItem.count) return false;
    if (oldItem.rssi != newItem.rssi) return false;

    String oldTime = oldItem.time != null ? oldItem.time : "";
    String newTime = newItem.time != null ? newItem.time : "";
    if (!oldTime.equals(newTime)) return false;

    String oldTitle = oldItem.title != null ? oldItem.title : "";
    String newTitle = newItem.title != null ? newItem.title : "";
    if (!oldTitle.equals(newTitle)) return false;

    String oldKarat = oldItem.karat != null ? oldItem.karat : "";
    String newKarat = newItem.karat != null ? newItem.karat : "";
    if (!oldKarat.equals(newKarat)) return false;

    if (Math.abs(oldItem.weight - newItem.weight) > 0.001) return false;
    if (Math.abs(oldItem.price - newItem.price) > 0.01) return false;

    return true;
}
        };

    public ModernScannedItemsAdapter(boolean isArabic, OnItemClickListener listener) {
        super(DIFF_CALLBACK);
        this.isArabic = isArabic;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_scanned_modern, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ScannedItemDisplay item = getItem(position);
        if (item != null) {
            holder.bind(item, isArabic);
            holder.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onItemClick(item);
            });
            
            // ✅ Animation على العناصر الجديدة
            applyEntryAnimation(holder.itemView, position);
        }
    }

    /**
     * ✅ Animation عند عرض العنصر لأول مرة
     */
    private void applyEntryAnimation(View view, int position) {
        if (position > lastAnimatedPosition) {
            view.setAlpha(0f);
            view.setTranslationY(-30f);
            view.setScaleX(0.9f);
            view.setScaleY(0.9f);
            
            view.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(320)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .start();
            
            lastAnimatedPosition = position;
        }
    }

    /**
     * ✅ إعادة تعيين التتبع (عند إعادة تحميل القائمة)
     */
    public void resetAnimationTracker() {
        lastAnimatedPosition = -1;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvItemTitle, tvItemEpc, tvItemKarat, tvItemWeight, 
                 tvItemPrice, tvItemCount, tvItemTime;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvItemTitle = itemView.findViewById(R.id.tvItemTitle);
            tvItemEpc = itemView.findViewById(R.id.tvItemEpc);
            tvItemKarat = itemView.findViewById(R.id.tvItemKarat);
            tvItemWeight = itemView.findViewById(R.id.tvItemWeight);
            tvItemPrice = itemView.findViewById(R.id.tvItemPrice);
            tvItemCount = itemView.findViewById(R.id.tvItemCount);
            tvItemTime = itemView.findViewById(R.id.tvItemTime);
        }

        void bind(ScannedItemDisplay item, boolean isArabic) {
            if (tvItemTitle != null) {
                tvItemTitle.setText(item.title != null && !item.title.isEmpty() 
                    ? item.title 
                    : (isArabic ? "قطعة غير معروفة" : "Unknown item"));
            }

            if (tvItemEpc != null) {
                tvItemEpc.setText(item.epc != null ? item.epc : "—");
            }

            if (tvItemKarat != null) {
                tvItemKarat.setText(item.karat != null && !item.karat.isEmpty() 
                    ? item.karat 
                    : "—");
            }

            if (tvItemWeight != null) {
                if (item.weight > 0) {
                    tvItemWeight.setText(String.format(Locale.US, "%.2fg", item.weight));
                    tvItemWeight.setVisibility(View.VISIBLE);
                } else {
                    tvItemWeight.setVisibility(View.GONE);
                }
            }

            if (tvItemPrice != null) {
                if (item.price > 0) {
                    tvItemPrice.setText(String.format(Locale.US, "$%.0f", item.price));
                    tvItemPrice.setVisibility(View.VISIBLE);
                } else {
                    tvItemPrice.setVisibility(View.GONE);
                }
            }

            if (tvItemCount != null) {
                tvItemCount.setText("x" + item.count);
            }

            if (tvItemTime != null) {
                tvItemTime.setText(item.time != null ? item.time : "");
            }
        }
    }
}
