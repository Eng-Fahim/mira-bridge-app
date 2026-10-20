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
 * Adapter لدليل الحسابات الشجري - يدعم الطي والفتح
 */
public class AccountTreeAdapter extends RecyclerView.Adapter<AccountTreeAdapter.VH> {

    public interface OnAccountClick {
        void onClick(AccountDto account);
    }

    private List<AccountDto> originalItems = new ArrayList<>();
    private List<AccountDto> displayItems = new ArrayList<>();
    
    // لحفظ أكواد الحسابات التي قام المستخدم بفتحها فقط
    private final Set<String> expandedAccounts = new HashSet<>();
    
    private final OnAccountClick listener;

    public AccountTreeAdapter(OnAccountClick listener) {
        this.listener = listener;
    }

    public void setItems(List<AccountDto> newItems) {
        this.originalItems = newItems != null ? newItems : new ArrayList<>();
        buildDisplayList(); 
        notifyDataSetChanged();
    }

    // هذه الدالة هي التي تقرر ما الذي سيظهر على الشاشة
    private void buildDisplayList() {
        displayItems.clear();
        for (AccountDto a : originalItems) {
            // إذا كان الحساب مرئياً (جميع آبائه مفتوحين أو ليس له آباء)، نضيفه للعرض
            if (isVisible(a)) {
                displayItems.add(a);
            }
        }
    }

    // دالة تتحقق هل يجب إظهار هذا الحساب أم لا
    private boolean isVisible(AccountDto account) {
        String parentCode = getParentCode(account.accountCode);
        
        // نستمر في الصعود للأعلى للتحقق من كل الآباء
        while (parentCode != null) {
            // إذا كان الأب موجوداً في القائمة ولكنه غير مفتوح، نخفي الابن
            if (!expandedAccounts.contains(parentCode)) {
                return false;
            }
            parentCode = getParentCode(parentCode);
        }
        return true; // كل الآباء مفتوحين
    }

    // دالة ذكية لاستخراج كود الأب (تفترض أن كود المستوى الأول 4 أرقام، وكل مستوى يزيد رقمين)
    // مثال: 111001 -> الأب هو 1110 -> الجد هو 1000
    private String getParentCode(String code) {
        if (code == null || code.length() <= 4) return null; 
        return code.substring(0, code.length() - 2);
    }

    // دالة للتحقق مما إذا كان للحساب أبناء (لكي نعرف هل نعرض السهم أم لا)
    private boolean hasChildren(AccountDto account) {
        for (AccountDto item : originalItems) {
            String pCode = getParentCode(item.accountCode);
            if (pCode != null && pCode.equals(account.accountCode)) {
                return true; // وجدنا ابناً لهذا الحساب
            }
        }
        return false;
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
        int indent = (a.level - 1) * 24; // زيادة المسافة قليلاً لتوضيح الشجرة
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

        h.tvGoldBadge.setVisibility(a.isGold() ? View.VISIBLE : View.GONE);

        h.tvType.setText(a.getTypeLabelAr());
        h.tvType.setTextColor(Color.parseColor(color));

        // ✅ إعداد السهم
        boolean canExpand = hasChildren(a);
        if (!canExpand) {
            h.tvArrow.setVisibility(View.INVISIBLE); // إخفاء مع الاحتفاظ بالمساحة
        } else {
            h.tvArrow.setVisibility(View.VISIBLE);
            
            // تغيير شكل السهم
            if (expandedAccounts.contains(a.accountCode)) {
                h.tvArrow.setText("▼"); // مفتوح
            } else {
                h.tvArrow.setText("▶"); // مغلق
            }

            // ✅ مساحة نقر واسعة للسهم
            h.tvArrow.setOnClickListener(v -> {
                if (expandedAccounts.contains(a.accountCode)) {
                    expandedAccounts.remove(a.accountCode); // طي
                } else {
                    expandedAccounts.add(a.accountCode); // فتح
                }
                buildDisplayList();
                notifyDataSetChanged();
            });
        }

        // النقر على باقي العنصر يفتح دفتر الأستاذ
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(a);
        });
    }

    @Override
    public int getItemCount() {
        return displayItems.size();
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
