package com.example.uhf.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.uhf.R;
import com.example.uhf.api.dto.AgingReportDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AgingReportAdapter extends RecyclerView.Adapter<AgingReportAdapter.VH> {

    private List<AgingReportDto.AgingRow> items = new ArrayList<>();

    public void setItems(List<AgingReportDto.AgingRow> newItems) {
        this.items = newItems != null ? newItems : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_aging_row, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        AgingReportDto.AgingRow row = items.get(position);
        
        h.tvAccName.setText(row.accountName);
        h.tvBalance.setText(format(row.balance));
        h.tvAge0_30.setText(format(row.age0_30));
        h.tvAge31_60.setText(format(row.age31_60));
        h.tvAge61_90.setText(format(row.age61_90));
        h.tvAgeOver90.setText(format(row.ageOver90));
    }

    private String format(double val) {
        if (Math.abs(val) < 0.01) return "-";
        return String.format(Locale.US, "%,.0f", val); // بدون كسور لتوفير المساحة
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvAccName, tvBalance, tvAge0_30, tvAge31_60, tvAge61_90, tvAgeOver90;
        VH(@NonNull View v) {
            super(v);
            tvAccName = v.findViewById(R.id.tvAccName);
            tvBalance = v.findViewById(R.id.tvBalance);
            tvAge0_30 = v.findViewById(R.id.tvAge0_30);
            tvAge31_60 = v.findViewById(R.id.tvAge31_60);
            tvAge61_90 = v.findViewById(R.id.tvAge61_90);
            tvAgeOver90 = v.findViewById(R.id.tvAgeOver90);
        }
    }
}
