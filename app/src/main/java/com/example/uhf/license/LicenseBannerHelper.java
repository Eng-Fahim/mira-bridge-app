package com.example.uhf.license;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * LicenseBannerHelper — إضافة banner تحذيري أعلى الشاشة
 *
 * الاستخدام في onCreate لأي Activity رئيسية:
 *   LicenseBannerHelper.attach(this, rootView);
 */
public class LicenseBannerHelper {

    private static final String TAG = "LicenseBanner";
    private static final int BANNER_ID = 0x4D495241; // "MIRA"

    public static void attach(Activity activity, ViewGroup root) {
        if (activity == null || root == null) return;

        // احذف القديم إن وجد
        View old = root.findViewById(BANNER_ID);
        if (old != null) root.removeView(old);

        LicenseGuard.Result result = LicenseGuard.check(activity);

        // لا حاجة لـ banner في الحالة الطبيعية
        if (result.decision == LicenseGuard.Decision.ALLOWED) {
            return;
        }

        int bgColor;
        int textColor = Color.WHITE;
        String prefix;

        switch (result.decision) {
            case WARNING:
                bgColor = 0xFFF39C12; // برتقالي
                prefix = "⚠️ ";
                break;
            case RESTRICTED:
                bgColor = 0xFFE67E22; // برتقالي غامق
                prefix = "🟠 ";
                break;
            case BLOCKED:
            default:
                bgColor = 0xFFC0392B; // أحمر
                prefix = "🔴 ";
                break;
        }

        // بناء الـ banner
        LinearLayout banner = new LinearLayout(activity);
        banner.setId(BANNER_ID);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setBackgroundColor(bgColor);
        banner.setPadding(dp(activity, 16), dp(activity, 12),
                          dp(activity, 16), dp(activity, 12));

        TextView text = new TextView(activity);
        text.setText(prefix + result.message);
        text.setTextColor(textColor);
        text.setTextSize(13);
        text.setLineSpacing(0, 1.2f);

        banner.addView(text, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        // زر "اتصل" للـ BLOCKED / RESTRICTED
        if (result.decision == LicenseGuard.Decision.BLOCKED
                || result.decision == LicenseGuard.Decision.RESTRICTED) {

            TextView action = new TextView(activity);
            action.setText("↻ إعادة المحاولة الآن");
            action.setTextColor(textColor);
            action.setTextSize(13);
            action.setPadding(0, dp(activity, 8), 0, 0);
            action.setOnClickListener(v -> {
                HeartbeatScheduler.triggerNow(activity);
                activity.recreate();
            });

            banner.addView(action);
        }

        // أضف أعلى الجذر
        if (root instanceof LinearLayout) {
            ((LinearLayout) root).addView(banner, 0);
        } else if (root instanceof FrameLayout) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
            );
            ((FrameLayout) root).addView(banner, lp);
        } else {
            // fallback: أضف في آخر الجذر
            root.addView(banner, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        }
    }

    private static int dp(Activity activity, int value) {
        float density = activity.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
