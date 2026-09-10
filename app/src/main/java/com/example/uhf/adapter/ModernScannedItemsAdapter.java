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
import com.example.uhf.model.ScannedItemDisplay;

import java.util.Locale;

/**
 * ✅ ModernScannedItemsAdapter - محسّن بالأداء
 * يستخدم ListAdapter + DiffUtil لتحديث سلس بدون تجمد الواجهة
 * 
 * الميزات:
 * - تحديث سلس (Smooth Updates)
 * - لا مزيد من notifyItemInserted اليدوي
 * - أداء محسن مع RFID سريع (50+ tag/sec)
 */
public class ModernScannedItemsAdapter 
        extends ListAdapter<ScannedItemDisplay, ModernScannedItemsAdapter.ViewHolder> {

    public interface OnItemClickListener {
        void onItemClick(ScannedItemDisplay item);
    }

    private final boolean isArabic;
    private final OnItemClickListener listener;

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
                return oldItem.count == newItem.count 
                    && oldItem.rssi == newItem.rssi
                    && (oldItem.time != null ? oldItem.time.equals(newItem.time) : newItem.time == null);
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
        }
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
            // العنوان
            if (tvItemTitle != null) {
                tvItemTitle.setText(item.title != null && !item.title.isEmpty() 
                    ? item.title 
                    : (isArabic ? "قطعة غير معروفة" : "Unknown item"));
            }

            // EPC
            if (tvItemEpc != null) {
                tvItemEpc.setText(item.epc != null ? item.epc : "—");
            }

            // العيار
            if (tvItemKarat != null) {
                tvItemKarat.setText(item.karat != null && !item.karat.isEmpty() 
                    ? item.karat 
                    : "—");
            }

            // الوزن
            if (tvItemWeight != null) {
                if (item.weight > 0) {
                    tvItemWeight.setText(String.format(Locale.US, "%.2fg", item.weight));
                    tvItemWeight.setVisibility(View.VISIBLE);
                } else {
                    tvItemWeight.setVisibility(View.GONE);
                }
            }

            // السعر
            if (tvItemPrice != null) {
                if (item.price > 0) {
                    tvItemPrice.setText(String.format(Locale.US, "$%.0f", item.price));
                    tvItemPrice.setVisibility(View.VISIBLE);
                } else {
                    tvItemPrice.setVisibility(View.GONE);
                }
            }

            // العداد
            if (tvItemCount != null) {
                tvItemCount.setText("x" + item.count);
            }

            // الوقت
            if (tvItemTime != null) {
                tvItemTime.setText(item.time != null ? item.time : "");
            }
        }
    }
}
