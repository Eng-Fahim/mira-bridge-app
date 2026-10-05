package com.example.uhf.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.utils.NumberFormatter;

import java.util.ArrayList;
import java.util.List;

/**
 * ✅ Adapter لعرض سطور دفتر الأستاذ
 *
 * الميزات:
 * - عرض تاريخ/بيان/مدين/دائن/رصيد/ذهب
 * - دعم Pagination عبر appendItems
 * - النقر على صف
 */
public class LedgerRowAdapter extends RecyclerView.Adapter<LedgerRowAdapter.VH> {

    // ═══════════════════════════════════════════════════════════
    //  Row Model
    // ═══════════════════════════════════════════════════════════
    public static class LedgerRow {
        public long entryId;      // ✅ لفتح التفاصيل
        public String date;
        public String entryNumber;
        public String description;
        public String reference;
        public double baseDebit;
        public double baseCredit;
        public double runningBalance;
        public double runningGold;
    }

    // ═══════════════════════════════════════════════════════════
    //  Callback
    // ═══════════════════════════════════════════════════════════
    public interface OnRowClick {
        void onClick(LedgerRow row);
    }

    // ═══════════════════════════════════════════════════════════
    //  State
    // ═══════════════════════════════════════════════════════════
    private final List<LedgerRow> items = new ArrayList<>();
    private final OnRowClick listener;

    public LedgerRowAdapter(OnRowClick listener) {
        this.listener = listener;
    }

    // ═══════════════════════════════════════════════════════════
    //  Data Management
    // ═══════════════════════════════════════════════════════════

    /**
     * ✅ استبدال كامل للقائمة (يُستخدم عند تغيير الحساب/الفترة)
     */
    public void setItems(List<LedgerRow> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        notifyDataSetChanged();
    }

    /**
     * ✅ إضافة سطور جديدة في النهاية (للـ Pagination)
     * يستخدم notifyItemRangeInserted لتحديث أسرع
     */
    public void appendItems(List<LedgerRow> newItems) {
        if (newItems == null || newItems.isEmpty()) return;
        int startPos = items.size();
        items.addAll(newItems);
        notifyItemRangeInserted(startPos, newItems.size());
    }

    /**
     * ✅ الحصول على القائمة الحالية (للتصدير CSV)
     */
    public List<LedgerRow> getItems() {
        return items;
    }

    /**
     * ✅ مسح كل السطور
     */
    public void clear() {
        if (items.isEmpty()) return;
        int size = items.size();
        items.clear();
        notifyItemRangeRemoved(0, size);
    }

    // ═══════════════════════════════════════════════════════════
    //  RecyclerView.Adapter
    // ═══════════════════════════════════════════════════════════

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_ledger_row, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        LedgerRow r = items.get(position);

        h.tvDate.setText(r.date != null ? r.date : "—");
        h.tvDescription.setText(r.description != null ? r.description : "—");
        h.tvEntryNumber.setText(r.entryNumber != null ? r.entryNumber : "");

        // ─── مدين ───
        if (r.baseDebit > 0.01) {
            h.tvDebit.setText(NumberFormatter.moneyShort(r.baseDebit));
            h.tvDebit.setTextColor(Color.parseColor("#3498DB"));
        } else {
            h.tvDebit.setText("—");
            h.tvDebit.setTextColor(Color.parseColor("#BDC3C7"));
        }

        // ─── دائن ───
        if (r.baseCredit > 0.01) {
            h.tvCredit.setText(NumberFormatter.moneyShort(r.baseCredit));
            h.tvCredit.setTextColor(Color.parseColor("#E74C3C"));
        } else {
            h.tvCredit.setText("—");
            h.tvCredit.setTextColor(Color.parseColor("#BDC3C7"));
        }

        // ─── الرصيد ───
        h.tvBalance.setText(NumberFormatter.moneyShort(r.runningBalance));
        h.tvBalance.setTextColor(r.runningBalance >= 0
            ? Color.parseColor("#27AE60")
            : Color.parseColor("#C0392B"));

        // ─── الذهب ───
        if (Math.abs(r.runningGold) > 0.001) {
            h.tvGold.setVisibility(View.VISIBLE);
            h.tvGold.setText(NumberFormatter.goldSigned(r.runningGold));
            h.tvGold.setTextColor(r.runningGold > 0
                ? Color.parseColor("#D4AF37")
                : Color.parseColor("#E67E22"));
        } else {
            h.tvGold.setVisibility(View.GONE);
        }

        // ─── النقر ───
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(r);
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
        TextView tvDate, tvDescription, tvEntryNumber,
                 tvDebit, tvCredit, tvBalance, tvGold;

        VH(@NonNull View v) {
            super(v);
            tvDate         = v.findViewById(R.id.tvDate);
            tvDescription  = v.findViewById(R.id.tvDescription);
            tvEntryNumber  = v.findViewById(R.id.tvEntryNumber);
            tvDebit        = v.findViewById(R.id.tvDebit);
            tvCredit       = v.findViewById(R.id.tvCredit);
            tvBalance      = v.findViewById(R.id.tvBalance);
            tvGold         = v.findViewById(R.id.tvGold);
        }
    }
}
