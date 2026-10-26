package com.example.uhf.accounting;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * MIRA Accounting Dashboard — Placeholder
 *
 * شاشة لوحة التحكم المالية — ستُبنى تدريجياً في المرحلة الرابعة.
 * هذا الإصدار يعرض رسالة تعريفية فقط، لكنه يعمل مع نظام التنقل
 * الجديد دون أخطاء، ويسمح باختبار الـ registry من الآن.
 *
 * لا يعتمد على أي ملف layout، لتفادي تلويث res/ مؤقتاً.
 */
public class MiraAccountingDashboardFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        Context ctx = inflater.getContext();
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        int pad = dp(ctx, 24);
        root.setPadding(pad, pad, pad, pad);

        TextView icon = new TextView(ctx);
        icon.setText("🏠");
        icon.setTextSize(64);
        icon.setGravity(Gravity.CENTER);
        root.addView(icon, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(ctx);
        title.setText("لوحة التحكم المالية");
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(ctx, 12);
        root.addView(title, titleLp);

        TextView sub = new TextView(ctx);
        sub.setText("قيد التطوير — المرحلة القادمة\nستعرض المؤشرات المالية والتنبيهات والوصول السريع");
        sub.setTextSize(14);
        sub.setGravity(Gravity.CENTER);
        sub.setAlpha(0.7f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(ctx, 8);
        root.addView(sub, subLp);

        return root;
    }

    private static int dp(Context ctx, int value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }
}
