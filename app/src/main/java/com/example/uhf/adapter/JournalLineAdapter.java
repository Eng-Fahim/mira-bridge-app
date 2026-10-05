package com.example.uhf.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.dto.JournalLineDto;
import com.example.uhf.utils.NumberFormatter;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter لسطور القيد في شاشة التفاصيل
 */
public class JournalLineAdapter
        extends RecyclerView.Adapter<JournalLineAdapter.VH> {

    private List<JournalLineDto> items = new ArrayList<>();

    public void setItems(List<JournalLineDto> newItems) {
        this.items = newItems != null ? newItems : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_journal_line, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        JournalLineDto line = items.get(position);

        h.tvAccount.setText(line.getDisplayAccount());

        // وصف السطر
        if (line.description != null && !line.description.trim().isEmpty()) {
            h.tvLineDescription.setVisibility(View.VISIBLE);
            h.tvLineDescription.setText(line.description);
        } else {
            h.tvLineDescription.setVisibility(View.GONE);
        }

        // مدين
        if (line.baseDebit > 0.01) {
            h.tvLineDebit.setText(NumberFormatter.moneyShort(line.baseDebit));
            h.tvLineDebit.setTextColor(Color.parseColor("#3498DB"));
        } else {
            h.tvLineDebit.setText("—");
            h.tvLineDebit.setTextColor(Color.parseColor("#BDC3C7"));
        }

        // دائن
        if (line.baseCredit > 0.01) {
            h.tvLineCredit.setText(NumberFormatter.moneyShort(line.baseCredit));
            h.tvLineCredit.setTextColor(Color.parseColor("#E74C3C"));
        } else {
            h.tvLineCredit.setText("—");
            h.tvLineCredit.setTextColor(Color.parseColor("#BDC3C7"));
        }

        // الذهب
        if (line.hasGold()) {
            h.tvLineGold.setVisibility(View.VISIBLE);
            double gold = line.getGoldSigned();
            String sign = gold >= 0 ? "+" : "";
            String karat = line.originalKarat != null && !line.originalKarat.isEmpty()
                    ? " (" + line.originalKarat + ")" : "";
            h.tvLineGold.setText(sign + NumberFormatter.goldSigned(gold) + karat);
        } else {
            h.tvLineGold.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvAccount, tvLineDescription, tvLineGold, tvLineDebit, tvLineCredit;

        VH(@NonNull View v) {
            super(v);
            tvAccount         = v.findViewById(R.id.tvAccount);
            tvLineDescription = v.findViewById(R.id.tvLineDescription);
            tvLineGold        = v.findViewById(R.id.tvLineGold);
            tvLineDebit       = v.findViewById(R.id.tvLineDebit);
            tvLineCredit      = v.findViewById(R.id.tvLineCredit);
        }
    }
}
