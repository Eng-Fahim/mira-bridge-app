package com.example.uhf.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.manager.IdentitySyncManager;
import com.example.uhf.dialog.EditSyncItemDialog;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 🏗️ SyncLogAdapter - Adapter عرض سجلات المزامنة
 * 
 * الميزات:
 * - عرض كل سجل مع تفاصيله
 * - 4 أزرار لكل سجل (إرسال، تعديل، حذف، تفاصيل)
 * - ألوان حسب الحالة (pending, syncing, failed, synced)
 * - عرض عدد المحاولات وآخر خطأ
 * 
 * @author Mira Team
 * @version 1.0
 */
public class SyncLogAdapter extends RecyclerView.Adapter<SyncLogAdapter.ViewHolder> {

    public interface OnSyncLogActionListener {
        void onSyncItem(IdentitySyncManager.SyncLogItem item);
        void onEditItem(IdentitySyncManager.SyncLogItem item);
        void onDeleteItem(IdentitySyncManager.SyncLogItem item);
        void onViewDetails(IdentitySyncManager.SyncLogItem item);
    }

    private final List<IdentitySyncManager.SyncLogItem> items;
    private final boolean isArabic;
    private final OnSyncLogActionListener listener;

    public SyncLogAdapter(List<IdentitySyncManager.SyncLogItem> items,
                          boolean isArabic,
                          OnSyncLogActionListener listener) {
        this.items = items != null ? items : new ArrayList<>();
        this.isArabic = isArabic;
        this.listener = listener;
    }

    /**
     * ✅ تحديث قائمة السجلات
     */
    public void updateItems(List<IdentitySyncManager.SyncLogItem> newItems) {
        this.items.clear();
        if (newItems != null) {
            this.items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_sync_log, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        IdentitySyncManager.SyncLogItem item = items.get(position);
        if (item == null) return;

        holder.bind(item, isArabic, listener);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    // ==================== ViewHolder ====================

    static class ViewHolder extends RecyclerView.ViewHolder {

        View statusIndicator;
        TextView tvTitle, tvEpc, tvKarat, tvWeight, tvPrice;
        TextView tvSavedDate, tvAttempts, tvStatus, tvError;
        LinearLayout layoutError;
        MaterialButton btnSync, btnEdit, btnDelete, btnDetails;

        ViewHolder(@NonNull View itemView) {
            super(itemView);

            statusIndicator = itemView.findViewById(R.id.statusIndicator);
            tvTitle = itemView.findViewById(R.id.tvTitle);
            tvEpc = itemView.findViewById(R.id.tvEpc);
            tvKarat = itemView.findViewById(R.id.tvKarat);
            tvWeight = itemView.findViewById(R.id.tvWeight);
            tvPrice = itemView.findViewById(R.id.tvPrice);
            tvSavedDate = itemView.findViewById(R.id.tvSavedDate);
            tvAttempts = itemView.findViewById(R.id.tvAttempts);
            tvStatus = itemView.findViewById(R.id.tvStatus);
            tvError = itemView.findViewById(R.id.tvError);
            layoutError = itemView.findViewById(R.id.layoutError);
            btnSync = itemView.findViewById(R.id.btnSync);
            btnEdit = itemView.findViewById(R.id.btnEdit);
            btnDelete = itemView.findViewById(R.id.btnDelete);
            btnDetails = itemView.findViewById(R.id.btnDetails);
        }

        void bind(IdentitySyncManager.SyncLogItem item, boolean isArabic,
                  OnSyncLogActionListener listener) {

            // ✅ العنوان
            if (tvTitle != null) {
                tvTitle.setText(item.title != null && !item.title.isEmpty() ?
                        item.title :
                        (isArabic ? "بدون عنوان" : "Untitled"));
            }

            // ✅ EPC
            if (tvEpc != null) {
                tvEpc.setText("EPC: " + item.epc);
            }

            // ✅ العيار
            if (tvKarat != null) {
                tvKarat.setText(item.karat != null ? item.karat : "—");
            }

            // ✅ الوزن
            if (tvWeight != null) {
                tvWeight.setText(String.format(Locale.US, "%.2f g", item.grossWeight));
            }

            // ✅ السعر
            if (tvPrice != null) {
                tvPrice.setText(String.format(Locale.US, "$%.2f", item.priceUSD));
            }

            // ✅ تاريخ الحفظ
            if (tvSavedDate != null) {
                tvSavedDate.setText("📅 " + item.getFormattedSavedAt());
            }

            // ✅ عدد المحاولات
            if (tvAttempts != null) {
                if (item.attempts > 0) {
                    tvAttempts.setVisibility(View.VISIBLE);
                    tvAttempts.setText("🔄 " + item.attempts + " " +
                            (isArabic ? "محاولة" : "attempts"));
                } else {
                    tvAttempts.setVisibility(View.GONE);
                }
            }

            // ✅ الحالة + لون
            configureStatus(item, isArabic);

            // ✅ رسالة الخطأ
            configureError(item, isArabic);

            // ✅ الأزرار
            configureButtons(item, listener);
        }

        private void configureStatus(IdentitySyncManager.SyncLogItem item, 
                                     boolean isArabic) {
            String status = item.status != null ? item.status : "pending";
            String displayText;
            int color;

            switch (status) {
                case "synced":
                    displayText = isArabic ? "✅ تمت المزامنة" : "✅ Synced";
                    color = Color.parseColor("#22C55E"); // أخضر
                    break;
                case "syncing":
                    displayText = isArabic ? "⏳ جاري المزامنة" : "⏳ Syncing";
                    color = Color.parseColor("#3B82F6"); // أزرق
                    break;
                case "failed":
                    displayText = isArabic ? "❌ فشل" : "❌ Failed";
                    color = Color.parseColor("#EF4444"); // أحمر
                    break;
                case "failed_max_attempts":
                    displayText = isArabic ? "⚠️ تجاوز الحد" : "⚠️ Max attempts";
                    color = Color.parseColor("#F59E0B"); // برتقالي
                    break;
                default:
                    displayText = isArabic ? "📦 قيد الانتظار" : "📦 Pending";
                    color = Color.parseColor("#F59E0B"); // برتقالي
                    break;
            }

            if (tvStatus != null) {
                tvStatus.setText(displayText);
                tvStatus.setTextColor(color);
            }

            if (statusIndicator != null) {
                statusIndicator.setBackgroundColor(color);
            }
        }

        private void configureError(IdentitySyncManager.SyncLogItem item,
                                    boolean isArabic) {
            if (layoutError == null || tvError == null) return;

            if (item.lastError != null && !item.lastError.isEmpty()) {
                layoutError.setVisibility(View.VISIBLE);
                tvError.setText("❌ " + item.lastError);
            } else {
                layoutError.setVisibility(View.GONE);
            }
        }

        private void configureButtons(IdentitySyncManager.SyncLogItem item,
                                       OnSyncLogActionListener listener) {
            if (listener == null) return;

            // ✅ زر الإرسال
            if (btnSync != null) {
                boolean canSync = !"synced".equals(item.status) && 
                                  !"syncing".equals(item.status);
                btnSync.setEnabled(canSync);
                btnSync.setAlpha(canSync ? 1.0f : 0.5f);
                btnSync.setOnClickListener(v -> listener.onSyncItem(item));
            }

            // ✅ زر التعديل
            if (btnEdit != null) {
                boolean canEdit = !"synced".equals(item.status) &&
                                  !"syncing".equals(item.status);
                btnEdit.setEnabled(canEdit);
                btnEdit.setAlpha(canEdit ? 1.0f : 0.5f);
                btnEdit.setOnClickListener(v -> listener.onEditItem(item));
            }

            // ✅ زر الحذف
            if (btnDelete != null) {
                btnDelete.setOnClickListener(v -> listener.onDeleteItem(item));
            }

            // ✅ زر التفاصيل
            if (btnDetails != null) {
                btnDetails.setOnClickListener(v -> listener.onViewDetails(item));
            }
        }
    }
}
