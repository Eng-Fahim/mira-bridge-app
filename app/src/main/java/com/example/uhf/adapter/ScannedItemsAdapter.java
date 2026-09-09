package com.example.uhf.adapter;

import android.text.BidiFormatter;
import android.text.TextDirectionHeuristics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.model.ScannedItemDisplay;

import java.util.List;
import java.util.Locale;

public class ScannedItemsAdapter extends RecyclerView.Adapter<ScannedItemsAdapter.ViewHolder> {

    public interface OnItemActionListener {
        void onEditStatus(ScannedItemDisplay item);
        void onRegisterDamage(ScannedItemDisplay item);
        void onUpdateLocation(ScannedItemDisplay item);
        void onAddNotes(ScannedItemDisplay item);
    }

    private final List<ScannedItemDisplay> items;
    private final boolean isArabic;
    private final BidiFormatter bidiFormatter;
    private final OnItemActionListener listener;

    public ScannedItemsAdapter(List<ScannedItemDisplay> items, boolean isArabic,
                               OnItemActionListener listener) {
        this.items = items;
        this.isArabic = isArabic;
        this.listener = listener;
        this.bidiFormatter = BidiFormatter.getInstance(Locale.getDefault());
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_scanned_tag, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        if (position < 0 || position >= items.size()) return;

        ScannedItemDisplay item = items.get(position);
        if (item == null) return;

        String fullTitle = !"—".equals(item.karat) ?
            item.title + " (" + item.karat + ")" : item.title;
        holder.tvTagTitle.setText(fullTitle);
        holder.tvTagEpc.setText(item.epc);

        StringBuilder details = new StringBuilder();
        details.append(isArabic ? "التكرار: " : "Repeat: ").append(item.count);

        if (item.weight > 0) {
            String formattedWeight = String.format(Locale.US, "%.2f g", item.weight);
            details.append(isArabic ? " • الوزن: " : " • Weight: ")
                   .append(bidiFormatter.unicodeWrap(formattedWeight, TextDirectionHeuristics.LTR));
        }

        if (item.price > 0) {
            String formattedPrice = String.format(Locale.US, "$%,.2f", item.price);
            details.append(isArabic ? " • السعر: " : " • Price: ")
                   .append(bidiFormatter.unicodeWrap(formattedPrice, TextDirectionHeuristics.LTR));
        }

        holder.tvTagDetails.setText(details.toString());
        holder.tvTagRssi.setText(item.rssi + " dBm");
        holder.tvTagTime.setText(item.time != null ? item.time : "--:--:--");

        holder.tvTagStatus.setText(isArabic ? "ممسوح" : "Scanned");
        holder.tvTagStatus.setTextColor(0xFF1565C0);
    }

    @Override
    public int getItemCount() {
        return items != null ? items.size() : 0;
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvTagTitle;
        TextView tvTagEpc;
        TextView tvTagDetails;
        TextView tvTagStatus;
        TextView tvTagTime;
        TextView tvTagRssi;
        android.widget.Button btnQuickAction;

        ViewHolder(View itemView) {
            super(itemView);
            tvTagTitle = itemView.findViewById(R.id.tvTagTitle);
            tvTagEpc = itemView.findViewById(R.id.tvTagEpc);
            tvTagDetails = itemView.findViewById(R.id.tvTagDetails);
            tvTagStatus = itemView.findViewById(R.id.tvTagStatus);
            tvTagTime = itemView.findViewById(R.id.tvTagTime);
            tvTagRssi = itemView.findViewById(R.id.tvTagRssi);
            btnQuickAction = itemView.findViewById(R.id.btnQuickAction);

            if (btnQuickAction != null) {
                btnQuickAction.setOnClickListener(v -> {
                    int position = getAdapterPosition();
                    if (position != RecyclerView.NO_POSITION) {
                        ScannedItemDisplay item = items.get(position);
                        showQuickActionsDialog(item);
                    }
                });
            }
        }

        private void showQuickActionsDialog(ScannedItemDisplay item) {
            String[] actions = isArabic ? new String[]{
                "✏️ تعديل الحالة",
                "🔧 تسجيل تلف",
                "📍 تحديث الموقع",
                "📝 ملاحظات",
                "❌ إلغاء"
            } : new String[]{
                "✏️ Edit status",
                "🔧 Register damage",
                "📍 Update location",
                "📝 Notes",
                "❌ Cancel"
            };

            new androidx.appcompat.app.AlertDialog.Builder(itemView.getContext())
                .setTitle((isArabic ? "إجراءات سريعة - " : "Quick actions - ") + item.epc)
                .setItems(actions, (dialog, which) -> {
                    if (listener == null) return;
                    switch (which) {
                        case 0: listener.onEditStatus(item); break;
                        case 1: listener.onRegisterDamage(item); break;
                        case 2: listener.onUpdateLocation(item); break;
                        case 3: listener.onAddNotes(item); break;
                    }
                })
                .show();
        }
    }
}
