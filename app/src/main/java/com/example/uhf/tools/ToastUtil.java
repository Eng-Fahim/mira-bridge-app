package com.example.uhf.tools;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

/**
 * MIRA Toast Utility - Professional International Toast Notifications
 * 
 * Usage:
 *   ToastUtil.success(context, "Item scanned successfully");
 *   ToastUtil.error(context, "Connection failed");
 *   ToastUtil.warning(context, "Battery low");
 *   ToastUtil.info(context, "Inventory started");
 */
public class ToastUtil {

    // ==================== Colors ====================
    private static final int COLOR_SUCCESS_BG = 0xFFDCFCE7;  // أخضر فاتح
    private static final int COLOR_SUCCESS_ICON = 0xFF16A34A; // أخضر غامق
    private static final int COLOR_SUCCESS_TEXT = 0xFF166534;

    private static final int COLOR_ERROR_BG = 0xFFFEE2E2;    // أحمر فاتح
    private static final int COLOR_ERROR_ICON = 0xFFDC2626;   // أحمر غامق
    private static final int COLOR_ERROR_TEXT = 0xFF991B1B;

    private static final int COLOR_WARNING_BG = 0xFFFEF3C7;   // برتقالي فاتح
    private static final int COLOR_WARNING_ICON = 0xFFF59E0B; // برتقالي غامق
    private static final int COLOR_WARNING_TEXT = 0xFF92400E;

    private static final int COLOR_INFO_BG = 0xFFDBEAFE;      // أزرق فاتح
    private static final int COLOR_INFO_ICON = 0xFF3B82F6;    // أزرق غامق
    private static final int COLOR_INFO_TEXT = 0xFF1E40AF;

    private static final int COLOR_DARK_BG = 0xFF1F2937;
    private static final int COLOR_DARK_TEXT = 0xFFF9FAFB;

    // ==================== Config ====================
    private static final float CORNER_RADIUS_DP = 12f;
    private static final float ICON_SIZE_SP = 20f;
    private static final float TEXT_SIZE_SP = 14f;
    private static final int PADDING_HORIZONTAL_DP = 16;
    private static final int PADDING_VERTICAL_DP = 14;
    private static final int ICON_MARGIN_END_DP = 12;
    private static final int ELEVATION_DP = 6;

    @SuppressLint("StaticFieldLeak")
    private static Context appContext;
    private static Toast currentToast;
    private static boolean isDarkMode = false;

    /**
     * Initialize ToastUtil - Call once in Application or MainActivity
     */
    public static void init(Context context) {
        appContext = context.getApplicationContext();
    }

    /**
     * Enable/disable dark mode support
     */
    public static void setDarkMode(boolean dark) {
        isDarkMode = dark;
    }

    // ==================== Public API ====================

    public static void success(String message) {
        show(message, COLOR_SUCCESS_BG, COLOR_SUCCESS_ICON, COLOR_SUCCESS_TEXT, "✓");
    }

    public static void error(String message) {
        show(message, COLOR_ERROR_BG, COLOR_ERROR_ICON, COLOR_ERROR_TEXT, "✗");
    }

    public static void warning(String message) {
        show(message, COLOR_WARNING_BG, COLOR_WARNING_ICON, COLOR_WARNING_TEXT, "⚠");
    }

    public static void info(String message) {
        show(message, COLOR_INFO_BG, COLOR_INFO_ICON, COLOR_INFO_TEXT, "ℹ");
    }

    public static void success(int resId) {
        success(appContext.getString(resId));
    }

    public static void error(int resId) {
        error(appContext.getString(resId));
    }

    public static void warning(int resId) {
        warning(appContext.getString(resId));
    }

    public static void info(int resId) {
        info(appContext.getString(resId));
    }

    // ==================== Core ====================

    private static void show(String message, int bgColor, int iconColor, int textColor, String icon) {
        if (appContext == null) {
            Log.e("ToastUtil", "Not initialized! Call ToastUtil.init(context) first.");
            return;
        }

        // Cancel previous toast
        if (currentToast != null) {
            currentToast.cancel();
        }

        // Apply dark mode override
        if (isDarkMode) {
            bgColor = COLOR_DARK_BG;
            textColor = COLOR_DARK_TEXT;
        }

        // Build custom view
        View layout = createToastView(message, bgColor, iconColor, textColor, icon);

        currentToast = new Toast(appContext);
        currentToast.setDuration(Toast.LENGTH_SHORT);
        currentToast.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, dpToPx(80));
        currentToast.setView(layout);
        currentToast.show();
    }

    private static View createToastView(String message, int bgColor, int iconColor, int textColor, String icon) {
        // Root layout
        LinearLayout root = new LinearLayout(appContext);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(
                dpToPx(PADDING_HORIZONTAL_DP),
                dpToPx(PADDING_VERTICAL_DP),
                dpToPx(PADDING_HORIZONTAL_DP),
                dpToPx(PADDING_VERTICAL_DP)
        );

        // Background
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dpToPx(CORNER_RADIUS_DP));
        bg.setStroke(1, adjustAlpha(iconColor, 0.3f));
        root.setBackground(bg);

        // Elevation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            root.setElevation(dpToPx(ELEVATION_DP));
        }

        // Icon
        TextView iconView = new TextView(appContext);
        iconView.setText(icon);
        iconView.setTextSize(TypedValue.COMPLEX_UNIT_SP, ICON_SIZE_SP);
        iconView.setTextColor(iconColor);
        iconView.setGravity(Gravity.CENTER);
        iconView.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        iconParams.setMarginEnd(dpToPx(ICON_MARGIN_END_DP));
        iconView.setLayoutParams(iconParams);
        root.addView(iconView);

        // Message text
        TextView textView = new TextView(appContext);
        textView.setText(message);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP);
        textView.setTextColor(textColor);
        textView.setGravity(Gravity.CENTER_VERTICAL);
        textView.setMaxLines(3);
        root.addView(textView);

        return root;
    }

    // ==================== Utility ====================

    private static int dpToPx(float dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp,
                appContext.getResources().getDisplayMetrics()
        );
    }

    private static int adjustAlpha(int color, float factor) {
        int alpha = Math.round(Color.alpha(color) * factor);
        int red = Color.red(color);
        int green = Color.green(color);
        int blue = Color.blue(color);
        return Color.argb(alpha, red, green, blue);
    }

    // ==================== Legacy Compatibility ====================

    /**
     * @deprecated Use success/error/warning/info instead
     */
    @Deprecated
    public static void showSuccess(CharSequence text) { success(text.toString()); }

    @Deprecated
    public static void showError(CharSequence text) { error(text.toString()); }

    @Deprecated
    public static void showWarning(CharSequence text) { warning(text.toString()); }

    @Deprecated
    public static void showInfo(CharSequence text) { info(text.toString()); }

    /**
     * Simple text-only toast
     */
    public static void show(String message) {
        if (appContext != null) {
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show();
        }
    }
}
