package com.example.uhf.manager;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * PosUIManager — مدير واجهة شاشة البيع
 *
 * ✅ Dialog Loading احترافي
 * ✅ Dialog Success/Error موحّد
 * ✅ Dialog Retry
 * ✅ Toasts
 * ✅ Broadcasts موحّدة
 *
 * لا يحتفظ بأي مرجع للـ Fragment — يقبل Context في كل دالة.
 */
public final class PosUIManager {

    private static final String TAG = "PosUIManager";

    private PosUIManager() {
        // Utility class
    }

    // ═══════════════════════════════════════════════════════
    // Loading Dialog
    // ═══════════════════════════════════════════════════════

    @Nullable
    public static AlertDialog showLoadingDialog(@NonNull Context ctx,
                                                 boolean isArabic) {
        try {
            MaterialCardView card = new MaterialCardView(ctx);
            card.setRadius(dp(ctx, 20));
            card.setCardElevation(dp(ctx, 8));

            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(ctx, 40), dp(ctx, 36),
                    dp(ctx, 40), dp(ctx, 36));
            root.setGravity(Gravity.CENTER);

            ProgressBar pb = new ProgressBar(ctx);
            pb.setIndeterminate(true);
            root.addView(pb);

            TextView tv = new TextView(ctx);
            tv.setText(isArabic ? R.string.pos_msg_processing
                    : R.string.pos_msg_processing);
            tv.setTextSize(17);
            tv.setTypeface(null, android.graphics.Typeface.BOLD);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, dp(ctx, 20), 0, dp(ctx, 6));
            tv.setTextColor(Color.parseColor("#1A1A1A"));
            root.addView(tv);

            card.addView(root);

            AlertDialog dialog = new MaterialAlertDialogBuilder(ctx)
                    .setView(card)
                    .setCancelable(false)
                    .create();

            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(
                        new ColorDrawable(Color.TRANSPARENT));
            }
            dialog.show();
            return dialog;
        } catch (Exception e) {
            android.util.Log.e(TAG, "showLoadingDialog error: " + e.getMessage());
            return null;
        }
    }

    public static void dismissDialog(@Nullable AlertDialog dialog) {
        try {
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        } catch (Exception ignored) {}
    }

    // ═══════════════════════════════════════════════════════
    // Success / Error Dialog
    // ═══════════════════════════════════════════════════════

    public static void showResultDialog(@NonNull Context ctx,
                                        @NonNull String title,
                                        @NonNull String message,
                                        boolean isSuccess,
                                        boolean isArabic) {
        try {
            MaterialCardView card = new MaterialCardView(ctx);
            card.setRadius(dp(ctx, 24));
            card.setCardElevation(dp(ctx, 10));

            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(ctx, 40), dp(ctx, 30),
                    dp(ctx, 40), dp(ctx, 30));
            root.setGravity(Gravity.CENTER);

            TextView icon = new TextView(ctx);
            icon.setText(isSuccess ? "✅" : "❌");
            icon.setTextSize(48);
            icon.setGravity(Gravity.CENTER);
            root.addView(icon);

            TextView tvTitle = new TextView(ctx);
            tvTitle.setText(title);
            tvTitle.setTextSize(18);
            tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            tvTitle.setGravity(Gravity.CENTER);
            tvTitle.setPadding(0, dp(ctx, 12), 0, dp(ctx, 8));
            tvTitle.setTextColor(isSuccess ? 0xFF22C55E : 0xFFEF4444);
            root.addView(tvTitle);

            TextView tvMsg = new TextView(ctx);
            tvMsg.setText(message);
            tvMsg.setTextSize(14);
            tvMsg.setGravity(Gravity.CENTER);
            tvMsg.setPadding(0, dp(ctx, 4), 0, dp(ctx, 16));
            tvMsg.setTextColor(0xFF555555);
            root.addView(tvMsg);

            card.addView(root);

            new MaterialAlertDialogBuilder(ctx)
                    .setView(card)
                    .setPositiveButton(R.string.pos_ok, (d, w) -> d.dismiss())
                    .setCancelable(false)
                    .show();
        } catch (Exception e) {
            android.util.Log.e(TAG, "showResultDialog error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════
    // Retry Dialog
    // ═══════════════════════════════════════════════════════

    public interface OnRetryListener {
        void onRetry();
    }

    public static void showRetryDialog(@NonNull Context ctx,
                                       @NonNull String title,
                                       @NonNull String message,
                                       @NonNull OnRetryListener onRetry,
                                       boolean isArabic) {
        try {
            new MaterialAlertDialogBuilder(ctx)
                    .setTitle(title)
                    .setMessage(message)
                    .setCancelable(false)
                    .setPositiveButton(R.string.pos_retry, (d, w) -> {
                        d.dismiss();
                        onRetry.onRetry();
                    })
                    .setNegativeButton(R.string.pos_close,
                            (d, w) -> d.dismiss())
                    .show();
        } catch (Exception e) {
            android.util.Log.e(TAG, "showRetryDialog error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════
    // Toast
    // ═══════════════════════════════════════════════════════

    public static void toast(@Nullable Context ctx, @Nullable String msg) {
    if (ctx == null || msg == null || msg.isEmpty()) return;
    try {
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();   // ← LONG
    } catch (Exception ignored) {}
}

    // ═══════════════════════════════════════════════════════
    // Broadcast Notifications
    // ═══════════════════════════════════════════════════════

    /**
     * إشعار كل الشاشات المعنية بتحديثها
     *
     * @param ctx      Context
     * @param source   مصدر الحدث ("sale", "refund", "sale_offline")
     * @param branchId رقم الفرع
     */
    public static void notifyDataChanged(@Nullable Context ctx,
                                          @NonNull String source,
                                          int branchId) {
        if (ctx == null) return;
        long timestamp = System.currentTimeMillis();

        sendBroadcastSafely(ctx, "com.example.uhf.REFRESH_SALES",
                source, branchId, timestamp);
        sendBroadcastSafely(ctx, "com.example.uhf.REFRESH_INVENTORY",
                source, branchId, timestamp);
        sendBroadcastSafely(ctx, "com.example.uhf.REFRESH_DASHBOARD",
                source, branchId, timestamp);
    }

    private static void sendBroadcastSafely(@NonNull Context ctx,
                                             @NonNull String action,
                                             @NonNull String source,
                                             int branchId,
                                             long timestamp) {
        try {
            Intent intent = new Intent(action);
            intent.setPackage(ctx.getPackageName());
            intent.putExtra("source", source);
            intent.putExtra("branch_id", branchId);
            intent.putExtra("timestamp", timestamp);
            ctx.sendBroadcast(intent);
        } catch (Exception e) {
            android.util.Log.w(TAG, "broadcast error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════
    // Utilities
    // ═══════════════════════════════════════════════════════

    private static int dp(@NonNull Context ctx, int dp) {
        float density = ctx.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
