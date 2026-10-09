package com.example.uhf.adapter;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.manager.MiraPriceManager;
import com.example.uhf.model.CartItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  CartAdapter v2.0 — Stable IDs, Snapshot-Based, Defensive
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * التغييرات الجوهرية عن v1.0:
 *
 *   1. ✅ setHasStableIds(true) — animations صحيحة، لا إعادة رسم كامل.
 *
 *   2. ✅ القائمة الداخلية immutable snapshot — لا يقرأ من قائمة تتغيّر تحته.
 *      Fragment يستدعي replaceItems() قبل notifyItem*.
 *
 *   3. ✅ Defensive: getItemCount و onBindViewHolder محميّان من out-of-bounds.
 *
 *   4. ✅ getBindingAdapterPosition بدلاً من getAdapterPosition المهجورة.
 *
 *   5. ✅ submitItems(snapshot) — دالة واحدة لتحديث المحتوى.
 */
public class CartAdapter extends RecyclerView.Adapter<CartAdapter.ViewHolder> {

    public interface OnRemoveListener {
        void onRemove(int position);
    }

    private static final String TAG = "CartAdapter";

    @NonNull
    private List<CartItem> items = Collections.emptyList();

    @Nullable private final OnRemoveListener removeListener;
    private final boolean isArabic;
    @Nullable private final MiraPriceManager priceManager;

    public CartAdapter(@Nullable OnRemoveListener listener,
                       boolean isArabic,
                       @Nullable MiraPriceManager priceManager) {
        this.removeListener = listener;
        this.isArabic = isArabic;
        this.priceManager = priceManager;
        setHasStableIds(true);   // ✅ animations صحيحة
    }

    // ═══════════════════════════════════════════════════════
    //  عرض القائمة — تُستدعى من Fragment
    // ═══════════════════════════════════════════════════════

    /**
     * ✅ استبدال قائمة العناصر — يجب أن تُستدعى BEFORE أي notifyItem*.
     *                 مع notifyItem* اللاحق، RecyclerView يقرأ data جديدة.
     */
    public void replaceItems(@NonNull List<CartItem> newItems) {
        this.items = Collections.unmodifiableList(new ArrayList<>(newItems));
    }

    /** للتوافق مع الحالة القديمة */
    @NonNull
    public List<CartItem> getItems() {
        return items;
    }

    // ═══════════════════════════════════════════════════════
    //  Stable IDs
    // ═══════════════════════════════════════════════════════

    @Override
    public long getItemId(int position) {
        try {
            CartItem item = items.get(position);
            if (item == null) return RecyclerView.NO_ID;

            // ✅ نستخدم EPC معرّفاً — فريد لكل قطعة
            String id = item.epc != null ? item.epc
                    : item.serialNo != null ? item.serialNo
                    : item.gtin != null ? item.gtin
                    : null;

            return (id != null) ? id.hashCode() : position;
        } catch (Exception e) {
            return RecyclerView.NO_ID;
        }
    }

    // ═══════════════════════════════════════════════════════
    //  RecyclerView lifecycle
    // ═══════════════════════════════════════════════════════

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_pos_cart_row, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int pos) {
        // ✅ حماية: تجاهل الأخطاء بدل الانفجار
        if (pos < 0 || pos >= items.size()) {
            Log.w(TAG, "onBindViewHolder: pos=" + pos
                    + " out of range [0," + items.size() + ")");
            return;
        }

        CartItem item = items.get(pos);
        if (item == null) return;

        try {
            // Title
            if (h.tvTitle != null) {
                h.tvTitle.setText(item.name != null ? item.name : "—");
            }

            // EPC
            if (h.tvEpc != null) {
                String epc = item.epc != null && !item.epc.isEmpty()
                        ? item.epc : "—";
                h.tvEpc.setText("EPC: " + epc);
            }

            // Karat + Weight
            if (h.tvKaratWeight != null) {
                double w = item.scaleWeight > 0 ? item.scaleWeight : item.weight;
                String karat = item.karat != null ? item.karat : "—";
                h.tvKaratWeight.setText(String.format(Locale.US,
                        "%s • %.2fg", karat, w));
            }

            // Status
            if (h.tvStatus != null) {
                h.tvStatus.setText(item.isAvailable
                        ? (isArabic ? "✅ متاحة" : "✅ Available")
                        : (isArabic ? "❌ غير متاحة" : "❌ Unavailable"));
            }

            // Price
            if (h.tvPrice != null) {
                try {
                    String text = priceManager != null
                            ? priceManager.formatPriceDefault(item.price)
                            : String.format(Locale.US, "$%.2f", item.price);
                    if (text == null || text.trim().isEmpty()) {
                        text = String.format(Locale.US, "$%.2f", item.price);
                    }
                    h.tvPrice.setText(text);
                } catch (Exception e) {
                    h.tvPrice.setText(String.format(Locale.US, "$%.2f", item.price));
                }
            }

            // Remove button
            if (h.btnRemove != null) {
                h.btnRemove.setOnClickListener(v -> {
                    // ✅ getBindingAdapterPosition بدلاً من getAdapterPosition المهجورة
                    int p = h.getBindingAdapterPosition();
                    if (p != RecyclerView.NO_POSITION && removeListener != null) {
                        removeListener.onRemove(p);
                    }
                });
            }

        } catch (Exception e) {
            Log.e(TAG, "bind error at pos=" + pos, e);
        }
    }

    @Override
    public int getItemCount() {
        // ✅ قائمة immutable — لا يوجد خطر Thread.
        return items.size();
    }

    // ═══════════════════════════════════════════════════════
    //  ViewHolder
    // ═══════════════════════════════════════════════════════

    static class ViewHolder extends RecyclerView.ViewHolder {
        @Nullable TextView tvTitle, tvEpc, tvKaratWeight, tvPrice, tvStatus;
        @Nullable View btnRemove;

        ViewHolder(View v) {
            super(v);
            tvTitle = v.findViewById(R.id.tvRowItemTitle);
            tvEpc = v.findViewById(R.id.tvRowItemEpc);
            tvKaratWeight = v.findViewById(R.id.tvRowItemKaratWeight);
            tvPrice = v.findViewById(R.id.tvRowItemPrice);
            tvStatus = v.findViewById(R.id.tvRowItemStatus);
            btnRemove = v.findViewById(R.id.btnRowRemove);
        }
    }
}
