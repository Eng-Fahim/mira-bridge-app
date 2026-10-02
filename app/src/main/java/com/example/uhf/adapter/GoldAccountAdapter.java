package com.example.uhf.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.dto.GoldMovementDto;
import com.example.uhf.utils.NumberFormatter;

import java.util.ArrayList;
import java.util.List;

public class GoldAccountAdapter extends RecyclerView.Adapter<GoldAccountAdapter.VH> {

    private List<GoldMovementDto.AccountMovement> items = new ArrayList<>();

    public void setItems(List<GoldMovementDto.AccountMovement> newItems) {
        this.items = newItems != null ? newItems : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_gold_account, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        GoldMovementDto.AccountMovement r = items.get(position);

        h.tvName.setText(r.accountName != null ? r.accountName : "—");
        h.tvCode.setText(r.accountCode != null ? r.accountCode : "");

        h.tvOpening.setText(NumberFormatter.gold(r.opening));
        h.tvIn.setText("+" + NumberFormatter.gold(r.goldIn));
        h.tvIn.setTextColor(Color.parseColor("#27AE60"));
        h.tvOut.setText("-" + NumberFormatter.gold(r.goldOut));
        h.tvOut.setTextColor(Color.parseColor("#E74C3C"));

        h.tvClosing.setText(NumberFormatter.gold(r.closing));
        h.tvClosing.setTextColor(Color.parseColor("#D4AF37"));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvName, tvCode, tvOpening, tvIn, tvOut, tvClosing;

        VH(@NonNull View v) {
            super(v);
            tvName = v.findViewById(R.id.tvGoldAccountName);
            tvCode = v.findViewById(R.id.tvGoldAccountCode);
            tvOpening = v.findViewById(R.id.tvGoldOpening);
            tvIn = v.findViewById(R.id.tvGoldIn);
            tvOut = v.findViewById(R.id.tvGoldOut);
            tvClosing = v.findViewById(R.id.tvGoldClosing);
        }
    }
}
