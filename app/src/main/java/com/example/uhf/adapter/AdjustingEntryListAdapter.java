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
 * ✅ Adapter لعرض قيود التسوية + القيد الافتتاحي
 */
public class AdjustingEntryListAdapter
        extends RecyclerView.Adapter<AdjustingEntryListAdapter.VH> {

    public interface OnEntryClick {
        void onClick(JournalEntryDto entry);
    }

    private final List<JournalEntryDto> items = new ArrayList<>();
    private final OnEntryClick listener;

    public AdjustingEntryListAdapter(OnEntryClick listener) {
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
            .inflate(R.layout.item_adjusting_entry, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        JournalEntryDto e = items.get(position);

        h.tvEntryNumber.setText(e.entryNumber != null ? e.entryNumber : "—");
        h.tvEntryDate.setText(e.entryDate != null
            ? DateHelper.toDisplay(e.entryDate) : "");
        h.tvDescription.setText(e.description != null
            ? e.description : "—");

        // نوع القيد + لون
        String typeLabel;
        int color;

        if ("opening".equalsIgnoreCase(e.sourceType)) {
            typeLabel = "📖 القيد الافتتاحي";
            color = Color.parseColor("#D4AF37");
        } else if ("adjusting".equalsIgnoreCase(e.sourceType)
                || "inventory_add".equalsIgnoreCase(e.sourceType)) {
            typeLabel = "✏️ قيد تسوية";
            color = Color.parseColor("#9B59B6");
        } else {
            typeLabel = e.getSourceTypeLabelAr();
            color = Color.parseColor("#7F8C8D");
        }

        h.tvEntryType.setText(typeLabel);
        h.tvEntryType.setTextColor(color);
        h.typeIndicator.setBackgroundColor(color);

        // المبالغ
        h.tvDebit.setText("مدين: " + NumberFormatter.moneyShort(e.totalDebit));
        h.tvCredit.setText("دائن: " + NumberFormatter.moneyShort(e.totalCredit));

        // النقر
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(e);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvEntryNumber, tvEntryDate, tvEntryType,
                 tvDescription, tvDebit, tvCredit;
        View typeIndicator;

        VH(@NonNull View v) {
            super(v);
            tvEntryNumber  = v.findViewById(R.id.tvEntryNumber);
            tvEntryDate    = v.findViewById(R.id.tvEntryDate);
            tvEntryType    = v.findViewById(R.id.tvEntryType);
            tvDescription  = v.findViewById(R.id.tvDescription);
            tvDebit        = v.findViewById(R.id.tvDebit);
            tvCredit       = v.findViewById(R.id.tvCredit);
            typeIndicator  = v.findViewById(R.id.typeIndicator);
        }
    }
}
