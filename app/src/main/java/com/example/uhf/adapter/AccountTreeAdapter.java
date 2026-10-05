package com.example.uhf.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.dto.AccountDto;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter لدليل الحسابات الشجري
 */
public class AccountTreeAdapter extends RecyclerView.Adapter<AccountTreeAdapter.VH> {

    public interface OnAccountClick {
        void onClick(AccountDto account);
    }

    private List<AccountDto> items = new ArrayList<>();
    private final OnAccountClick listener;

    public AccountTreeAdapter(OnAccountClick listener) {
        this.listener = listener;
    }

    public void setItems(List<AccountDto> newItems) {
        this.items = newItems != null ? newItems : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_account_tree, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        AccountDto a = items.get(position);

        h.tvCode.setText(a.accountCode);
        h.tvName.setText(a.accountNameAr);

        // المسافة حسب المستوى
        int indent = (a.level - 1) * 16;
        h.container.setPaddingRelative(indent,                              // start
        h.container.getPaddingTop(),
        h.container.getPaddingEnd(),
        h.container.getPaddingBottom()
        );

        // لون حسب النوع
        String color;
        String icon;
        switch (a.accountType) {
            case "asset":     color = "#3498DB"; icon = "📊"; break;
            case "liability": color = "#E74C3C"; icon = "📉"; break;
            case "equity":    color = "#9B59B6"; icon = "💰"; break;
            case "revenue":   color = "#27AE60"; icon = "📈"; break;
            case "expense":   color = "#F39C12"; icon = "💸"; break;
            default:          color = "#7F8C8D"; icon = "📁";
        }

        h.indicator.setBackgroundColor(Color.parseColor(color));
        h.tvIcon.setText(icon);

        // الذهب
        if (a.isGold()) {
            h.tvGoldBadge.setVisibility(View.VISIBLE);
        } else {
            h.tvGoldBadge.setVisibility(View.GONE);
        }

        // نوع الحساب
        h.tvType.setText(a.getTypeLabelAr());
        h.tvType.setTextColor(Color.parseColor(color));

        // مؤشر الورقة
        if (a.isLeaf()) {
            h.tvArrow.setVisibility(View.GONE);
        } else {
            h.tvArrow.setVisibility(View.VISIBLE);
            h.tvArrow.setText("▼");
        }

        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(a);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        View indicator;
        TextView tvIcon, tvCode, tvName, tvType, tvGoldBadge, tvArrow;
        ViewGroup container;

        VH(@NonNull View v) {
            super(v);
            indicator = v.findViewById(R.id.accountIndicator);
            tvIcon = v.findViewById(R.id.tvAccountIcon);
            tvCode = v.findViewById(R.id.tvAccountCode);
            tvName = v.findViewById(R.id.tvAccountName);
            tvType = v.findViewById(R.id.tvAccountType);
            tvGoldBadge = v.findViewById(R.id.tvGoldBadge);
            tvArrow = v.findViewById(R.id.tvAccountArrow);
            container = v.findViewById(R.id.accountContainer);
        }
    }
}
