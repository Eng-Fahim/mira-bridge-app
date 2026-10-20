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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adapter لدليل الحسابات الشجري
 */
public class AccountTreeAdapter extends RecyclerView.Adapter<AccountTreeAdapter.VH> {

    public interface OnAccountClick {
        void onClick(AccountDto account);
    }

    // 1. القائمة الأصلية كما تأتي من الـ API أو الـ ViewModel
    private List<AccountDto> originalItems = new ArrayList<>();
    // 2. القائمة التي يتم رسمها على الشاشة (بعد إخفاء الحسابات المطوية)
    private List<AccountDto> displayItems = new ArrayList<>();
    
    // 3. لحفظ أكواد الحسابات التي قام المستخدم بفتحها
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

    // دالة ذكية لبناء القائمة وإخفاء الأبناء
    private void buildDisplayList() {
        displayItems.clear();
        
        // هذا المتغير يتذكر آخر حساب تم عرضه في كل مستوى
        // (مثلاً: المستوى 1 = "1000"، المستوى 2 = "1100"، إلخ)
        Map<Integer, String> currentPath = new HashMap<>();

        for (AccountDto a : originalItems) {
            if (a.level == 1) {
                // الحسابات الرئيسية (المستوى الأول) تظهر دائماً
                displayItems.add(a);
                currentPath.put(1, a.accountCode);
            } else {
                // الحصول على كود الأب المباشر من المستوى السابق
                String parentCode = currentPath.get(a.level - 1);
                
                // إذا لم نجد الأب (هذا يحدث غالباً عند البحث عن حساب معين)
                // نعرض الحساب لأن المستخدم يبحث عنه تحديداً
                if (parentCode == null) {
                    displayItems.add(a);
                    currentPath.put(a.level, a.accountCode);
                } 
                // إذا وجدنا الأب، نتحقق: هل الأب مفتوح (موجود في قائمة المفتوحين)؟
                else if (expandedAccounts.contains(parentCode)) {
                    displayItems.add(a); // نعم مفتوح، نعرض الابن
                    currentPath.put(a.level, a.accountCode); // نحدث المسار
                } 
                else {
                    // الأب مغلق! لا نضيف الابن للقائمة، 
                    // ونقوم بمسح أي مسارات أعمق حتى لا يظهر أبناء هذا الابن
                    for (int i = a.level; i <= 10; i++) {
                        currentPath.remove(i);
                    }
                }
            }
        }
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
        AccountDto a = displayItems.get(position); // نأخذ من القائمة المعروضة

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

        // 4. إعداد السهم (مفتوح/مغلق)
        if (a.isLeaf()) {
            h.tvArrow.setVisibility(View.GONE);
        } else {
            h.tvArrow.setVisibility(View.VISIBLE);
            
            // تغيير شكل السهم بناءً على الحالة
            if (expandedAccounts.contains(a.accountCode)) {
                h.tvArrow.setText("▼"); // مفتوح
            } else {
                h.tvArrow.setText("▶"); // مغلق
            }

            // 5. إضافة مستمع النقر للسهم (لفتح وإغلاق الشجرة)
            h.tvArrow.setOnClickListener(v -> {
                if (expandedAccounts.contains(a.accountCode)) {
                    expandedAccounts.remove(a.accountCode); // إغلاق (طي)
                } else {
                    expandedAccounts.add(a.accountCode); // فتح (بسط)
                }
                buildDisplayList(); // إعادة حساب ما سيتم عرضه
                notifyDataSetChanged(); // تحديث الشاشة
            });
        }

        // النقر على العنصر بالكامل (يفتح دفتر الأستاذ)
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
