package com.example.uhf.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.manager.MiraPriceManager;
import com.example.uhf.model.CartItem;

import java.util.List;
import java.util.Locale;

public class CartAdapter extends RecyclerView.Adapter<CartAdapter.ViewHolder> {

    public interface OnRemoveListener {
        void onRemove(int position);
    }

    private final List<CartItem> items;
    private final OnRemoveListener removeListener;
    private final boolean isArabic;
    private final MiraPriceManager priceManager;

    public CartAdapter(List<CartItem> items, OnRemoveListener listener, 
                       boolean isArabic, MiraPriceManager priceManager) {
        this.items = items;
        this.removeListener = listener;
        this.isArabic = isArabic;
        this.priceManager = priceManager;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_pos_cart_row, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int pos) {
        try {
            CartItem item = items.get(pos);
            if (h.tvTitle != null) h.tvTitle.setText(item.name);
            if (h.tvEpc != null) h.tvEpc.setText("EPC: " + item.epc);
            if (h.tvKaratWeight != null) {
                h.tvKaratWeight.setText(String.format(Locale.US, "%s | %.2fg", item.karat,
                        item.scaleWeight > 0 ? item.scaleWeight : item.weight));
            }
            if (h.tvPrice != null) h.tvPrice.setText(priceManager.formatPriceDefault(item.price));
            if (h.tvStatus != null) {
                h.tvStatus.setText(item.isAvailable ? 
                    (isArabic ? "✅ متاحة" : "✅ Available") : 
                    (isArabic ? "❌ غير متاحة" : "❌ Unavailable"));
            }
            if (h.btnRemove != null) {
                h.btnRemove.setOnClickListener(v -> {
                    int p = h.getAdapterPosition();
                    if (p != RecyclerView.NO_POSITION) removeListener.onRemove(p);
                });
            }
        } catch (Exception e) {
            android.util.Log.e("CartAdapter", "Bind error: " + e.getMessage());
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvTitle, tvEpc, tvKaratWeight, tvPrice, tvStatus;
        View btnRemove;

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
