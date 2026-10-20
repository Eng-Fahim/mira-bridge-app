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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Adapter لدليل الحسابات الشجري
 */
public class AccountTreeAdapter extends RecyclerView.Adapter<AccountTreeAdapter.VH> {

    public interface OnAccountClick {
        void onClick(AccountDto account);
    }

    // القائمة الأصلية القادمة من الـ API
    private List<AccountDto> originalItems = new ArrayList<>();
    // القائمة التي يتم عرضها فعلياً (بعد إخفاء الأبناء)
    private List<AccountDto> displayItems = new ArrayList<>();
    
    // لحفظ الحسابات التي قام المستخدم بفتحها
    private final Set<String> expandedAccounts = new HashSet<>();
    
    private final OnAccountClick listener;

    public AccountTreeAdapter(OnAccountClick listener) {
        this.listener = listener;
    }

    public void setItems(List<AccountDto> newItems) {
        this.originalItems = newItems != null ? newItems : new ArrayList<>();
        buildDisplayList(); // بناء القائمة المعروضة
        notifyDataSetChanged();
    }

    // دالة تقوم بفلترة القائمة وإخفاء الأبناء إذا كان الأب مغلقاً
    private void buildDisplayList() {
        displayItems.clear();
        for (AccountDto a : originalItems) {
            // إذا كان المستوى الأول (رئيسي) نعرضه دائماً
            if (a.level == 1) {
                displayItems.add(a);
            } else {
                // إذا كان فرعياً، نتحقق هل جميع آبائه مفتوحين؟
                if (areParentsExpanded(a)) {
                    displayItems.add(a);
                }
            }
        }
    }

    // دالة للتحقق من أن الآباء مفتوحين (تعتمد على منطق أرقام الحسابات)
    private boolean areParentsExpanded(AccountDto account) {
        String code = account.accountCode;
        // افترض أن كود الحساب 111001، آباؤه هما 1110 ثم 1100 ثم 1000
        // نقوم بتقصير الكود للوصول للأب والتحقق منه
        while (code != null && code.length() > 4) {
            code = code.substring(0, code.length() - 2); // قص آخر رقمين
            if (!expandedAccounts.contains(code)) {
                return false; // إذا كان أحد الآباء مغلقاً، لا تعرض الابن
            }
        }
        return true;
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
        AccountDto a = displayItems.get(position);

        h.tvCode.setText(a.accountCode);
        h.tvName.setText(a.accountNameAr);

        // المسافة حسب المستوى
        int indent = (a.level - 1) * 16;
        h.container.setPaddingRelative(indent, h.container.getPaddingTop(),
                h.container.getPaddingEnd(), h.container.getPaddingBottom());

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
        h.tvGoldBadge.setVisibility(a.isGold() ? View.VISIBLE : View.GONE);

        // نوع الحساب
        h.tvType.setText(a.getTypeLabelAr());
        h.tvType.setTextColor(Color.parseColor(color));

        // مؤشر السهم والورقة
        if (a.isLeaf()) {
            h.tvArrow.setVisibility(View.GONE);
        } else {
            h.tvArrow.setVisibility(View.VISIBLE);
            // تغيير شكل السهم بناءً على الحالة (مفتوح أم مغلق)
            if (expandedAccounts.contains(a.accountCode)) {
                h.tvArrow.setText("▼"); // مفتوح
            } else {
                h.tvArrow.setText("▶"); // مغلق
            }

            // ✅ إضافة مستمع النقر للسهم فقط
            h.tvArrow.setOnClickListener(v -> {
                if (expandedAccounts.contains(a.accountCode)) {
                    expandedAccounts.remove(a.accountCode); // إغلاق
                } else {
                    expandedAccounts.add(a.accountCode); // فتح
                }
                buildDisplayList(); // إعادة بناء القائمة
                notifyDataSetChanged(); // تحديث الواجهة
            });
        }

        // النقر على العنصر بالكامل (يفتح دفتر الأستاذ كما كان في كودك الأصلي)
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(a);
        });
    }

    @Override
    public int getItemCount() {
        return displayItems.size(); // نعرض القائمة المفلترة
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
