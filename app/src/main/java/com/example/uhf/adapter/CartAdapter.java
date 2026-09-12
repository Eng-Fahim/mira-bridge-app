package com.example.uhf.adapter;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.manager.MiraPriceManager;
import com.example.uhf.model.CartItem;

import java.util.Locale;

/**
 * ═══════════════════════════════════════════════════════════════════
 *  CartAdapter — ListAdapter مع DiffUtil للأداء الأمثل
 * ═══════════════════════════════════════════════════════════════════
 *
 *  🎯 التحسينات:
 *  - يستخدم DiffUtil لتحديث العناصر بكفاءة (بدون notifyDataSetChanged)
 *  - submitList() للتبديل الآمن مع LiveData
 *  - يحافظ على نفس الواجهة والتصميم
 *  - thread-safe (يعمل مع البيانات من ViewModel)
 *
 *  📌 ملاحظة مهمة: ListAdapter لا يحتاج List خارجي — يُدير قائمته بنفسه.
 */
public class CartAdapter extends ListAdapter<CartItem, CartAdapter.ViewHolder> {

    private static final String TAG = "CartAdapter";

    // ═══════════════════════════════════════════════════════════════════
    //  1. DiffUtil Callback — للكشف الذكي عن التغييرات
    // ═══════════════════════════════════════════════════════════════════

    private static final DiffUtil.ItemCallback<CartItem> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<CartItem>() {

        /**
         * هل القطعتان نفس القطعة؟ (للمقارنة المنطقية)
         * نستخدم EPC كمُعرّف فريد
         */
        @Override
        public boolean areItemsTheSame(@NonNull CartItem oldItem,
                                        @NonNull CartItem newItem) {
            if (oldItem.epc == null || newItem.epc == null) {
                return false;
            }
            return oldItem.epc.equals(newItem.epc);
        }

        /**
         * هل محتوى القطعتين متطابق؟ (لتجنب التحديثات الزائدة)
         * نقارن الحقول التي تتغير فعلاً
         */
        @Override
        public boolean areContentsTheSame(@NonNull CartItem oldItem,
                                           @NonNull CartItem newItem) {
            // ✅ مقارنة الحقول التي تتغير
            if (!equalsSafe(oldItem.name, newItem.name)) return false;
            if (!equalsSafe(oldItem.karat, newItem.karat)) return false;
            if (!equalsSafe(oldItem.status, newItem.status)) return false;
            if (!equalsSafe(oldItem.serialNo, newItem.serialNo)) return false;
            if (!equalsSafe(oldItem.gtin, newItem.gtin)) return false;
            if (!equalsSafe(oldItem.location, newItem.location)) return false;

            if (Double.compare(oldItem.weight, newItem.weight) != 0) return false;
            if (Double.compare(oldItem.scaleWeight, newItem.scaleWeight) != 0) return false;
            if (Double.compare(oldItem.price, newItem.price) != 0) return false;

            if (oldItem.isAvailable != newItem.isAvailable) return false;
            if (oldItem.itemId != newItem.itemId) return false;

            return true;
        }

        private boolean equalsSafe(String a, String b) {
            if (a == null && b == null) return true;
            if (a == null || b == null) return false;
            return a.equals(b);
        }
    };

    // ═══════════════════════════════════════════════════════════════════
    //  2. Callbacks و Dependencies
    // ═══════════════════════════════════════════════════════════════════

    public interface OnRemoveListener {
        void onRemove(int position);
    }

    private final OnRemoveListener removeListener;
    private final boolean isArabic;
    private final MiraPriceManager priceManager;

    // ═══════════════════════════════════════════════════════════════════
    //  3. البنّاء
    // ═══════════════════════════════════════════════════════════════════

    public CartAdapter(@NonNull OnRemoveListener listener,
                       boolean isArabic,
                       @NonNull MiraPriceManager priceManager) {
        super(DIFF_CALLBACK);
        this.removeListener = listener;
        this.isArabic = isArabic;
        this.priceManager = priceManager;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  4. Lifecycle — onCreateViewHolder
    // ═══════════════════════════════════════════════════════════════════

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_pos_cart_row, parent, false);
        return new ViewHolder(view);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  5. onBindViewHolder — ربط البيانات
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        try {
            CartItem item = getItem(position);
            if (item == null) return;

            bindItemData(holder, item);
            bindRemoveButton(holder);

        } catch (Exception e) {
            Log.e(TAG, "onBindViewHolder error: " + e.getMessage(), e);
        }
    }

    /**
     * ربط بيانات القطعة بعناصر الواجهة.
     */
    private void bindItemData(@NonNull ViewHolder h, @NonNull CartItem item) {
        // ✅ العنوان
        if (h.tvTitle != null) {
            h.tvTitle.setText(item.name != null && !item.name.isEmpty()
                    ? item.name
                    : (isArabic ? "قطعة" : "Item"));
        }

        // ✅ EPC
        if (h.tvEpc != null) {
            h.tvEpc.setText("EPC: " + (item.epc != null ? item.epc : "—"));
        }

        // ✅ العيار والوزن (يفضّل scaleWeight إذا > 0)
        if (h.tvKaratWeight != null) {
            double displayWeight = item.scaleWeight > 0 ? item.scaleWeight : item.weight;
            String karat = item.karat != null && !item.karat.isEmpty() ? item.karat : "—";

            h.tvKaratWeight.setText(
                    String.format(Locale.US, "%s | %.2fg", karat, displayWeight)
            );
        }

        // ✅ السعر
        if (h.tvPrice != null) {
            try {
                h.tvPrice.setText(priceManager.formatPriceDefault(item.price));
            } catch (Exception e) {
                h.tvPrice.setText(String.format(Locale.US, "$%.2f", item.price));
            }
        }

        // ✅ الحالة (متاحة/غير متاحة)
        if (h.tvStatus != null) {
            h.tvStatus.setText(item.isAvailable
                    ? (isArabic ? "✅ متاحة" : "✅ Available")
                    : (isArabic ? "❌ غير متاحة" : "❌ Unavailable"));

            // ✅ تغيير اللون حسب الحالة
            try {
                int color = item.isAvailable ? 0xFF22C55E : 0xFFEF4444;
                h.tvStatus.setTextColor(color);
            } catch (Exception ignored) {}
        }
    }

    /**
     * ربط زر الحذف — يستخدم getBindingAdapterPosition للحصول على الموقع الصحيح.
     */
    @SuppressWarnings("deprecation")
private void bindRemoveButton(@NonNull ViewHolder h) {
    if (h.btnRemove == null) return;

    h.btnRemove.setOnClickListener(v -> {
        // ✅ استخدام getAdapterPosition() — متوافقة مع كل إصدارات RecyclerView
        int pos = h.getAdapterPosition();
        if (pos != RecyclerView.NO_POSITION && removeListener != null) {
            removeListener.onRemove(pos);
        }
    });
}

    // ═══════════════════════════════════════════════════════════════════
    //  6. ViewHolder
    // ═══════════════════════════════════════════════════════════════════

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvTitle, tvEpc, tvKaratWeight, tvPrice, tvStatus;
        View btnRemove;

        ViewHolder(@NonNull View v) {
            super(v);
            tvTitle = v.findViewById(R.id.tvRowItemTitle);
            tvEpc = v.findViewById(R.id.tvRowItemEpc);
            tvKaratWeight = v.findViewById(R.id.tvRowItemKaratWeight);
            tvPrice = v.findViewById(R.id.tvRowItemPrice);
            tvStatus = v.findViewById(R.id.tvRowItemStatus);
            btnRemove = v.findViewById(R.id.btnRowRemove);
        }
    }

} // ✅ نهاية الكلاس
