package com.example.uhf.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.dto.JournalEntryDto;
import com.example.uhf.utils.DateHelper;
import com.example.uhf.utils.NumberFormatter;

import java.util.ArrayList;
import java.util.List;

/**
 * ✅ Adapter لقائمة القيود المحاسبية
 *
 * التحديثات:
 * - يستخدم getDisplayAmount() لعرض مبلغ الفاتورة الأصلي (POS)
 * - بدل totalDebit (مجموع حركات القيد)
 */
public class JournalEntryAdapter extends RecyclerView.Adapter<JournalEntryAdapter.VH> {

    public interface OnEntryClick {
        void onClick(JournalEntryDto entry);
    }

    private final List<JournalEntryDto> items = new ArrayList<>();
    private final OnEntryClick listener;

    public JournalEntryAdapter(OnEntryClick listener) {
        this.listener = listener;
    }

    public void setItems(List<JournalEntryDto> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_journal_entry, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        JournalEntryDto e = items.get(position);

        h.tvNumber.setText(e.entryNumber != null ? e.entryNumber : "—");
        h.tvDate.setText(DateHelper.toDisplay(e.entryDate));
        h.tvDescription.setText(e.description != null ? e.description : "—");

        // ✅✅✅ التعديل الحاسم: استخدام getDisplayAmount()
        // - لقيود POS: يعرض مبلغ الفاتورة الأصلي (مثل 93,152.80)
        // - لقيود يدوية: يعرض totalDebit (السلوك القديم)
        h.tvAmount.setText(NumberFormatter.money(e.getDisplayAmount()));

        // ─── لون الحالة ───
        int statusColor;
        String statusText;
        switch (e.status != null ? e.status : "draft") {
            case "posted":
                statusColor = Color.parseColor("#27AE60");
                statusText = "معتمد";
                break;
            case "draft":
                statusColor = Color.parseColor("#F39C12");
                statusText = "مسودة";
                break;
            case "reversed":
                statusColor = Color.parseColor("#E74C3C");
                statusText = "معكوس";
                break;
            case "cancelled":
                statusColor = Color.parseColor("#95A5A6");
                statusText = "ملغى";
                break;
            default:
                statusColor = Color.GRAY;
                statusText = e.status;
        }

        h.tvStatus.setText(statusText);
        h.tvStatus.setTextColor(statusColor);
        h.statusIndicator.setBackgroundColor(statusColor);

        // ─── الذهب ───
        double netGold = e.getNetGold();
        if (Math.abs(netGold) > 0.0001) {
            h.tvGold.setVisibility(View.VISIBLE);
            h.tvGold.setText(NumberFormatter.goldSigned(netGold));
            h.tvGold.setTextColor(netGold > 0
                ? Color.parseColor("#27AE60")
                : Color.parseColor("#E74C3C"));
        } else {
            h.tvGold.setVisibility(View.GONE);
        }

        // ─── النقر ───
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(e);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    // ═══════════════════════════════════════════════════════════
    //  ViewHolder
    // ═══════════════════════════════════════════════════════════
    static class VH extends RecyclerView.ViewHolder {
        TextView tvNumber, tvDate, tvDescription, tvAmount, tvStatus, tvGold;
        View statusIndicator;

        VH(@NonNull View itemView) {
            super(itemView);
            tvNumber        = itemView.findViewById(R.id.tvEntryNumber);
            tvDate          = itemView.findViewById(R.id.tvEntryDate);
            tvDescription   = itemView.findViewById(R.id.tvEntryDescription);
            tvAmount        = itemView.findViewById(R.id.tvEntryAmount);
            tvStatus        = itemView.findViewById(R.id.tvEntryStatus);
            tvGold          = itemView.findViewById(R.id.tvEntryGold);
            statusIndicator = itemView.findViewById(R.id.statusIndicator);
        }
    }
}
