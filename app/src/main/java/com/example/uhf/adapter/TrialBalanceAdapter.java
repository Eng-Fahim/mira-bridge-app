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
    private boolean isGoldView = false; // ✅ جديد

    public interface OnItemClickListener {
        void onItemClick(TrialBalanceDto.TrialBalanceRow row);
    }

    private OnItemClickListener listener;

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    // ✅ جديد: تبديل وضع العرض
    public void setGoldView(boolean goldView) {
        this.isGoldView = goldView;
        notifyDataSetChanged();
    }

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

        // ✅ اختيار القيم حسب وضع العرض
        if (isGoldView) {
            // ═══ وضع الذهب (بالجرام) ═══
            h.tvOpenDebit.setText(formatGold(r.openingGoldDebit, true));
            h.tvOpenCredit.setText(formatGold(r.openingGoldCredit, false));
            h.tvPeriodDebit.setText(formatGold(r.periodGoldDebit, true));
            h.tvPeriodCredit.setText(formatGold(r.periodGoldCredit, false));
            h.tvCloseDebit.setText(formatGold(r.closingGoldDebit, true));
            h.tvCloseCredit.setText(formatGold(r.closingGoldCredit, false));

            // إخفاء شريط الذهب (لأنه أصبح العرض الرئيسي)
            h.tvGold.setVisibility(View.GONE);

        } else {
            // ═══ وضع المال (بالريال) ═══
            h.tvOpenDebit.setText(r.openingDebit > 0 ? NumberFormatter.moneyShort(r.openingDebit) : "—");
            h.tvOpenCredit.setText(r.openingCredit > 0 ? NumberFormatter.moneyShort(r.openingCredit) : "—");
            h.tvPeriodDebit.setText(r.periodDebit > 0 ? NumberFormatter.moneyShort(r.periodDebit) : "—");
            h.tvPeriodCredit.setText(r.periodCredit > 0 ? NumberFormatter.moneyShort(r.periodCredit) : "—");
            h.tvCloseDebit.setText(r.closingDebit > 0 ? NumberFormatter.moneyShort(r.closingDebit) : "—");
            h.tvCloseCredit.setText(r.closingCredit > 0 ? NumberFormatter.moneyShort(r.closingCredit) : "—");

            // إظهار شريط الذهب الصغير
            double gDebit = r.goldDebit;
            double gCredit = r.goldCredit;
            double gBalance = r.goldBalance;

            if (Math.abs(gDebit) > 0.001 || Math.abs(gCredit) > 0.001 || Math.abs(gBalance) > 0.001) {
                h.tvGold.setVisibility(View.VISIBLE);
                String dStr = Math.abs(gDebit) > 0.001 ? String.format(Locale.US, "%.3f", gDebit) : "—";
                String cStr = Math.abs(gCredit) > 0.001 ? String.format(Locale.US, "%.3f", gCredit) : "—";
                String bStr = Math.abs(gBalance) > 0.001 ? String.format(Locale.US, "%.3f", gBalance) : "0";
                h.tvGold.setText("ذهب: مدين " + dStr + "g | دائن " + cStr + "g | الرصيد " + bStr + "g");

                h.tvGold.setTextColor(gBalance >= 0
                    ? Color.parseColor("#27AE60") : Color.parseColor("#E74C3C"));
            } else {
                h.tvGold.setVisibility(View.GONE);
            }
        }

        // حدث الضغط
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onItemClick(r);
        });
    }

    // ✅ تنسيق قيم الذهب
    private String formatGold(double value, boolean isDebit) {
        if (Math.abs(value) < 0.001) return "—";
        return String.format(Locale.US, "%.3f g", value);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public List<TrialBalanceDto.TrialBalanceRow> getItems() {
        return items;
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvCode, tvName, tvGold;
        TextView tvOpenDebit, tvOpenCredit, tvPeriodDebit, tvPeriodCredit, tvCloseDebit, tvCloseCredit;

        VH(@NonNull View v) {
            super(v);
            tvCode = v.findViewById(R.id.tvRowCode);
            tvName = v.findViewById(R.id.tvRowName);
            tvGold = v.findViewById(R.id.tvRowGold);
            tvOpenDebit = v.findViewById(R.id.tvOpenDebit);
            tvOpenCredit = v.findViewById(R.id.tvOpenCredit);
            tvPeriodDebit = v.findViewById(R.id.tvPeriodDebit);
            tvPeriodCredit = v.findViewById(R.id.tvPeriodCredit);
            tvCloseDebit = v.findViewById(R.id.tvCloseDebit);
            tvCloseCredit = v.findViewById(R.id.tvCloseCredit);
        }
    }
}
