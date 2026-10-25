package com.example.uhf.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.uhf.R;
import com.example.uhf.api.dto.AuditLogDto;

import java.util.ArrayList;
import java.util.List;

public class AuditLogAdapter extends RecyclerView.Adapter<AuditLogAdapter.VH> {

    private List<AuditLogDto> items = new ArrayList<>();

    public void setItems(List<AuditLogDto> newItems) {
        this.items = newItems != null ? newItems : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_audit_log, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        AuditLogDto log = items.get(position);

        // ترجمة اسم العملية (Action) ليكون مقروءاً
        String actionAr = getActionTranslation(log.action);
        h.tvAction.setText(actionAr);

        // عرض التفاصيل (إذا كانت JSON، نعرضها كنص بسيط)
        if (log.details != null && !log.details.isEmpty()) {
            h.tvDetails.setText(log.details);
            h.tvDetails.setVisibility(View.VISIBLE);
        } else {
            h.tvDetails.setVisibility(View.GONE);
        }

        h.tvDate.setText(log.createdAt);

        // تلوين الشريط الجانبي حسب الحالة
        if ("success".equalsIgnoreCase(log.status)) {
            h.vStatusColor.setBackgroundColor(Color.parseColor("#22C55E")); // أخضر
        } else if ("failed".equalsIgnoreCase(log.status)) {
            h.vStatusColor.setBackgroundColor(Color.parseColor("#EF4444")); // أحمر
        } else {
            h.vStatusColor.setBackgroundColor(Color.parseColor("#F59E0B")); // برتقالي (أخطاء)
        }
    }

    private String getActionTranslation(String action) {
        if (action == null) return "إجراء غير معروف";
        switch (action) {
            case "CREATE_JOURNAL_ENTRY": return "📝 إنشاء قيد محاسبي";
            case "REVERSE_JOURNAL_ENTRY": return "🔄 عكس قيد محاسبي";
            case "CREATE_PURCHASE_INVOICE": return "🧾 إنشاء فاتورة مشتريات";
            case "LOGIN": return "🔐 تسجيل دخول";
            case "LOGOUT": return "🚪 تسجيل خروج";
            case "UPDATE": return "✏️ تعديل";
            case "DELETE": return "🗑️ حذف";
            default: return action;
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        View vStatusColor;
        TextView tvAction, tvDetails, tvDate;

        VH(@NonNull View v) {
            super(v);
            vStatusColor = v.findViewById(R.id.vStatusColor);
            tvAction = v.findViewById(R.id.tvAction);
            tvDetails = v.findViewById(R.id.tvDetails);
            tvDate = v.findViewById(R.id.tvDate);
        }
    }
}
