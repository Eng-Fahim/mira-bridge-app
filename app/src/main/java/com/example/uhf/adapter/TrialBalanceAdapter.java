package com.example.uhf.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.dto.TrialBalanceDto;
import com.example.uhf.utils.NumberFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TrialBalanceAdapter extends RecyclerView.Adapter<TrialBalanceAdapter.VH> {

    private List<TrialBalanceDto.TrialBalanceRow> items = new ArrayList<>();

    public void setItems(List<TrialBalanceDto.TrialBalanceRow> newItems) {
        this.items = newItems != null ? newItems : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_trial_balance_row, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        TrialBalanceDto.TrialBalanceRow r = items.get(position);

        h.tvCode.setText(r.accountCode != null ? r.accountCode : "—");
        h.tvName.setText(r.accountNameAr != null ? r.accountNameAr : "—");
        
        // المبالغ المالية
        h.tvDebit.setText(r.totalDebit > 0 ? NumberFormatter.moneyShort(r.totalDebit) : "—");
        h.tvCredit.setText(r.totalCredit > 0 ? NumberFormatter.moneyShort(r.totalCredit) : "—");

        double balance = r.balance;
        h.tvBalance.setText(NumberFormatter.moneyShort(balance));
        h.tvBalance.setTextColor(balance >= 0
            ? Color.parseColor("#27AE60") : Color.parseColor("#C0392B"));

        // ✅ أوزان الذهب
        double gDebit = r.goldDebit;
        double gCredit = r.goldCredit;
        double gBalance = r.goldBalance;

        // نتحقق إذا كان هناك أي حركة ذهبية (وارد، صادر، أو رصيد)
        if (Math.abs(gDebit) > 0.001 || Math.abs(gCredit) > 0.001 || Math.abs(gBalance) > 0.001) {
            h.tvGold.setVisibility(View.VISIBLE);
            
            // تجهيز النصوص (إخفاء الأصفار وإظهار شرطة)
            String dStr = Math.abs(gDebit) > 0.001 ? String.format(Locale.US, "%.3f", gDebit) : "—";
            String cStr = Math.abs(gCredit) > 0.001 ? String.format(Locale.US, "%.3f", gCredit) : "—";
            String bStr = Math.abs(gBalance) > 0.001 ? String.format(Locale.US, "%.3f", gBalance) : "0";
            
            h.tvGold.setText("ذهب: مدين " + dStr + "g | دائن " + cStr + "g | الرصيد " + bStr + "g");
            
            // تلوين الرصيد الذهبي (أخضر للمدين، أحمر للدائن)
            h.tvGold.setTextColor(gBalance >= 0
                ? Color.parseColor("#27AE60") : Color.parseColor("#E74C3C"));
        } else {
            h.tvGold.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvCode, tvName, tvDebit, tvCredit, tvBalance, tvGold;

        VH(@NonNull View v) {
            super(v);
            tvCode = v.findViewById(R.id.tvRowCode);
            tvName = v.findViewById(R.id.tvRowName);
            tvDebit = v.findViewById(R.id.tvRowDebit);
            tvCredit = v.findViewById(R.id.tvRowCredit);
            tvBalance = v.findViewById(R.id.tvRowBalance);
            tvGold = v.findViewById(R.id.tvRowGold);
        }
    }
}
