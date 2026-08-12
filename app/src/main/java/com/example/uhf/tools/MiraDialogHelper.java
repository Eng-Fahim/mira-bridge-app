package com.example.uhf.tools;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.util.Locale;

/**
 * 🎨 MiraDialogHelper - محرك موحد لجميع نوافذ الحوار في التطبيق
 * 
 * الاستخدام:
 * MiraDialogHelper.getInstance(context)
 *     .setType(MiraDialogHelper.DialogType.SUCCESS)
 *     .setTitle("نجح!")
 *     .setMessage("تمت العملية بنجاح")
 *     .show();
 */
public class MiraDialogHelper {

    // ==================== الألوان الموحدة ====================
    public static final String COLOR_PRIMARY = "#0284C7";
    public static final String COLOR_PRIMARY_DARK = "#0369A1";
    public static final String COLOR_SUCCESS = "#16A34A";
    public static final String COLOR_SUCCESS_DARK = "#15803D";
    public static final String COLOR_WARNING = "#F59E0B";
    public static final String COLOR_WARNING_DARK = "#D97706";
    public static final String COLOR_DANGER = "#DC2626";
    public static final String COLOR_DANGER_DARK = "#B91C1C";
    public static final String COLOR_INFO = "#7C3AED";
    public static final String COLOR_DARK = "#0F172A";
    public static final String COLOR_GRAY = "#64748B";
    public static final String COLOR_LIGHT = "#94A3B8";
    public static final String COLOR_BG = "#F8FAFC";
    public static final String COLOR_CARD = "#FFFFFF";
    public static final String COLOR_SUCCESS_BG = "#F0FDF4";
    public static final String COLOR_ERROR_BG = "#FEF2F2";
    public static final String COLOR_WARNING_BG = "#FFFBEB";
    public static final String COLOR_INFO_BG = "#EFF6FF";
    public static final String COLOR_PRIMARY_BG = "#E0F2FE";

    // ==================== أنواع الحوار ====================
    public enum DialogType {
        SUCCESS("✅", COLOR_SUCCESS, COLOR_SUCCESS_BG),
        ERROR("❌", COLOR_DANGER, COLOR_ERROR_BG),
        WARNING("⚠️", COLOR_WARNING, COLOR_WARNING_BG),
        INFO("ℹ️", COLOR_INFO, COLOR_INFO_BG),
        CONFIRM("❓", COLOR_PRIMARY, COLOR_PRIMARY_BG),
        LOADING("⏳", COLOR_PRIMARY, COLOR_PRIMARY_BG),
        DETAILS("📋", COLOR_INFO, COLOR_LIGHT_BG),
        CUSTOM("", COLOR_PRIMARY, COLOR_LIGHT_BG);

        public final String icon;
        public final String color;
        public final String bgColor;

        DialogType(String icon, String color, String bgColor) {
            this.icon = icon;
            this.color = color;
            this.bgColor = bgColor;
        }
    }

    // ==================== Singleton ====================
    private static MiraDialogHelper instance;

    private final Context context;
    private boolean isArabic;

    private MiraDialogHelper(Context context) {
        this.context = context.getApplicationContext();
        this.isArabic = Locale.getDefault().getLanguage().equals("ar");
    }

    public static MiraDialogHelper getInstance(Context context) {
        if (instance == null || instance.context != context.getApplicationContext()) {
            instance = new MiraDialogHelper(context);
        }
        return instance;
    }

    public void setArabic(boolean arabic) {
        this.isArabic = arabic;
    }

    public boolean isArabic() {
        return isArabic;
    }

    // ==================== دوال سريعة ====================

    public AlertDialog showSuccess(String title, String message) {
        return builder().setType(DialogType.SUCCESS).setTitle(title).setMessage(message).show();
    }

    public AlertDialog showError(String title, String message) {
        return builder().setType(DialogType.ERROR).setTitle(title).setMessage(message).show();
    }

    public AlertDialog showWarning(String title, String message) {
        return builder().setType(DialogType.WARNING).setTitle(title).setMessage(message).show();
    }

    public AlertDialog showInfo(String title, String message) {
        return builder().setType(DialogType.INFO).setTitle(title).setMessage(message).show();
    }

    public AlertDialog showLoading(String message) {
        return builder().setType(DialogType.LOADING).setMessage(message).setCancelable(false).show();
    }

    public AlertDialog showConfirm(String title, String message,
                                    DialogInterface.OnClickListener positiveListener) {
        return builder().setType(DialogType.CONFIRM)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(isArabic ? "نعم، تأكيد" : "Yes, Confirm", positiveListener)
                .setNegativeButton(isArabic ? "إلغاء" : "Cancel", null)
                .show();
    }

    public AlertDialog showConfirm(String title, String message,
                                    String positiveText, String negativeText,
                                    DialogInterface.OnClickListener positiveListener,
                                    DialogInterface.OnClickListener negativeListener) {
        return builder().setType(DialogType.CONFIRM)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(positiveText, positiveListener)
                .setNegativeButton(negativeText, negativeListener)
                .show();
    }

    public AlertDialog showDetails(String title, String[][] details) {
        return builder().setType(DialogType.DETAILS)
                .setTitle(title)
                .setDetails(details)
                .show();
    }

    public void dismiss(AlertDialog dialog) {
        if (dialog != null && dialog.isShowing()) {
            try {
                dialog.dismiss();
            } catch (Exception ignored) {}
        }
    }

    // ==================== Builder ====================

    public MiraDialogBuilder builder() {
        return new MiraDialogBuilder(context);
    }

    public class MiraDialogBuilder {
        private DialogType type = DialogType.INFO;
        private String title;
        private String message;
        private String[][] details;
        private String positiveText;
        private String negativeText;
        private DialogInterface.OnClickListener positiveListener;
        private DialogInterface.OnClickListener negativeListener;
        private boolean cancelable = true;
        private View customView;

        public MiraDialogBuilder setType(DialogType type) {
            this.type = type;
            return this;
        }

        public MiraDialogBuilder setTitle(String title) {
            this.title = title;
            return this;
        }

        public MiraDialogBuilder setMessage(String message) {
            this.message = message;
            return this;
        }

        public MiraDialogBuilder setDetails(String[][] details) {
            this.details = details;
            return this;
        }

        public MiraDialogBuilder setPositiveButton(String text, DialogInterface.OnClickListener listener) {
            this.positiveText = text;
            this.positiveListener = listener;
            return this;
        }

        public MiraDialogBuilder setNegativeButton(String text, DialogInterface.OnClickListener listener) {
            this.negativeText = text;
            this.negativeListener = listener;
            return this;
        }

        public MiraDialogBuilder setCancelable(boolean cancelable) {
            this.cancelable = cancelable;
            return this;
        }

        public MiraDialogBuilder setCustomView(View view) {
            this.customView = view;
            return this;
        }

        public AlertDialog show() {
            if (context == null) return null;
            if (context instanceof Activity && ((Activity) context).isFinishing()) return null;

            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setCancelable(cancelable);

            // بناء المحتوى
            View contentView = buildContentView();
            builder.setView(contentView);

            // الأزرار
            if (type != DialogType.LOADING) {
                if (positiveText != null) {
                    builder.setPositiveButton(positiveText, positiveListener);
                } else if (positiveListener != null) {
                    builder.setPositiveButton(isArabic ? "حسناً" : "OK", positiveListener);
                }

                if (negativeText != null) {
                    builder.setNegativeButton(negativeText, negativeListener);
                }
            }

            AlertDialog dialog = builder.create();

            // تخصيص ألوان الأزرار بعد الإنشاء
            dialog.setOnShowListener(d -> {
                Button btnPositive = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
                Button btnNegative = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);

                if (btnPositive != null) {
                    btnPositive.setTextColor(Color.parseColor(type.color));
                    btnPositive.setTypeface(null, Typeface.BOLD);
                    btnPositive.setAllCaps(false);
                }
                if (btnNegative != null) {
                    btnNegative.setTextColor(Color.parseColor(COLOR_GRAY));
                    btnNegative.setTypeface(null, Typeface.BOLD);
                    btnNegative.setAllCaps(false);
                }
            });

            dialog.show();
            return dialog;
        }

        private View buildContentView() {
            LinearLayout rootLayout = new LinearLayout(context);
            rootLayout.setOrientation(LinearLayout.VERTICAL);
            rootLayout.setPadding(dp(30), dp(25), dp(30), dp(10));
            rootLayout.setGravity(Gravity.CENTER);

            if (type == DialogType.LOADING) {
                buildLoadingContent(rootLayout);
            } else if (customView != null) {
                rootLayout.addView(customView);
            } else if (type == DialogType.DETAILS && details != null) {
                buildDetailsContent(rootLayout);
            } else {
                buildStandardContent(rootLayout);
            }

            return rootLayout;
        }

        private void buildStandardContent(LinearLayout root) {
            // أيقونة
            TextView tvIcon = new TextView(context);
            tvIcon.setText(type.icon);
            tvIcon.setTextSize(sp(44));
            tvIcon.setGravity(Gravity.CENTER);
            root.addView(tvIcon);

            // عنوان
            if (title != null && !title.isEmpty()) {
                TextView tvTitle = new TextView(context);
                tvTitle.setText(title);
                tvTitle.setTextColor(Color.parseColor(type.color));
                tvTitle.setTextSize(sp(17));
                tvTitle.setTypeface(null, Typeface.BOLD);
                tvTitle.setGravity(Gravity.CENTER);
                tvTitle.setPadding(0, dp(8), 0, dp(4));
                root.addView(tvTitle);
            }

            // رسالة
            if (message != null && !message.isEmpty()) {
                TextView tvMessage = new TextView(context);
                tvMessage.setText(message);
                tvMessage.setTextColor(Color.parseColor(COLOR_GRAY));
                tvMessage.setTextSize(sp(13));
                tvMessage.setGravity(Gravity.CENTER);
                tvMessage.setLineSpacing(dp(3), 1);
                tvMessage.setPadding(0, dp(4), 0, dp(10));
                root.addView(tvMessage);
            }
        }

        private void buildDetailsContent(LinearLayout root) {
            // أيقونة
            TextView tvIcon = new TextView(context);
            tvIcon.setText(type.icon);
            tvIcon.setTextSize(sp(40));
            tvIcon.setGravity(Gravity.CENTER);
            root.addView(tvIcon);

            // عنوان
            if (title != null && !title.isEmpty()) {
                TextView tvTitle = new TextView(context);
                tvTitle.setText(title);
                tvTitle.setTextColor(Color.parseColor(type.color));
                tvTitle.setTextSize(sp(17));
                tvTitle.setTypeface(null, Typeface.BOLD);
                tvTitle.setGravity(Gravity.CENTER);
                tvTitle.setPadding(0, dp(8), 0, dp(10));
                root.addView(tvTitle);
            }

            // بطاقة التفاصيل
            MaterialCardView card = new MaterialCardView(context);
            card.setCardBackgroundColor(Color.parseColor(type.bgColor));
            card.setRadius(dp(14));
            card.setCardElevation(dp(2));
            card.setPadding(dp(16), dp(12), dp(16), dp(12));

            LinearLayout cardLayout = new LinearLayout(context);
            cardLayout.setOrientation(LinearLayout.VERTICAL);

            for (String[] detail : details) {
                addDetailRow(cardLayout, detail[0], detail[1]);
            }

            card.addView(cardLayout);
            root.addView(card);
        }

        private void buildLoadingContent(LinearLayout root) {
            ProgressBar progressBar = new ProgressBar(context);
            progressBar.setIndeterminate(true);
            progressBar.setPadding(0, 0, 0, dp(10));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    dp(50), dp(50));
            params.gravity = Gravity.CENTER;
            progressBar.setLayoutParams(params);
            root.addView(progressBar);

            if (message != null && !message.isEmpty()) {
                TextView tvMsg = new TextView(context);
                tvMsg.setText(message);
                tvMsg.setTextColor(Color.parseColor(type.color));
                tvMsg.setTextSize(sp(14));
                tvMsg.setTypeface(null, Typeface.BOLD);
                tvMsg.setGravity(Gravity.CENTER);
                tvMsg.setPadding(0, dp(8), 0, 0);
                root.addView(tvMsg);
            }
        }

        private void addDetailRow(LinearLayout parent, String label, String value) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(5), 0, dp(5));

            TextView tvLabel = new TextView(context);
            tvLabel.setText(label);
            tvLabel.setTextColor(Color.parseColor(COLOR_GRAY));
            tvLabel.setTextSize(sp(12));
            tvLabel.setTypeface(null, Typeface.BOLD);
            tvLabel.setMinWidth(dp(140));
            row.addView(tvLabel);

            TextView tvValue = new TextView(context);
            tvValue.setText(value != null ? value : "—");
            tvValue.setTextColor(Color.parseColor(COLOR_DARK));
            tvValue.setTextSize(sp(12));
            tvValue.setPadding(dp(8), 0, 0, 0);
            row.addView(tvValue);

            parent.addView(row);
        }

        private int dp(int dp) {
            return Math.round(dp * context.getResources().getDisplayMetrics().density);
        }

        private int sp(int sp) {
            return Math.round(sp * context.getResources().getDisplayMetrics().scaledDensity);
        }
    }
                                              }
