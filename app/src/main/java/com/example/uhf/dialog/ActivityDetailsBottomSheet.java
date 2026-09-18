package com.example.uhf.dialog;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.activity.UHFMainActivity;
import com.example.uhf.fragment.MiraAllActivitiesFragment;
import com.example.uhf.fragment.MiraInventoryProFragment;
import com.example.uhf.fragment.MiraRfidBindingsFragment;
import com.example.uhf.fragment.MiraSalesHistoryFragment;
import com.example.uhf.fragment.MiraTransferFragment;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;

import org.json.JSONObject;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * ✅ Bottom Sheet احترافي لعرض تفاصيل العملية
 * 
 * ✅ v2.0:
 *  - إجراءات حقيقية مرتبطة بالشاشات
 *  - احترام كامل للثيمات (فاتح/داكن)
 */
public class ActivityDetailsBottomSheet extends BottomSheetDialogFragment {

    private static final String ARG_ACTIVITY = "activity_json";
    private static final String ARG_IS_ARABIC = "is_arabic";

    private JSONObject activity;
    private boolean isArabic;

    public static ActivityDetailsBottomSheet newInstance(JSONObject activity, boolean isArabic) {
        ActivityDetailsBottomSheet sheet = new ActivityDetailsBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_ACTIVITY, activity != null ? activity.toString() : "{}");
        args.putBoolean(ARG_IS_ARABIC, isArabic);
        sheet.setArguments(args);
        return sheet;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            try {
                activity = new JSONObject(getArguments().getString(ARG_ACTIVITY, "{}"));
            } catch (Exception e) {
                activity = new JSONObject();
            }
            isArabic = getArguments().getBoolean(ARG_IS_ARABIC, true);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_activity_details, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        FrameLayout iconContainer = view.findViewById(R.id.iconContainer);
        TextView tvDetailIcon = view.findViewById(R.id.tvDetailIcon);
        TextView tvDetailTitle = view.findViewById(R.id.tvDetailTitle);
        TextView tvDetailTime = view.findViewById(R.id.tvDetailTime);
        LinearLayout detailsContainer = view.findViewById(R.id.detailsContainer);
        LinearLayout actionsContainer = view.findViewById(R.id.actionsContainer);

        String icon = activity.optString("icon", "⚙️");
        String title = isArabic ? activity.optString("title_ar", "") 
                                : activity.optString("title_en", "");
        String timestamp = activity.optString("timestamp", "");
        String status = activity.optString("status", "neutral");

        tvDetailIcon.setText(icon);
        tvDetailTitle.setText(title);
        tvDetailTime.setText(formatFullTime(timestamp));

        int statusColor = getStatusColor(status);
        iconContainer.getBackground().setTint(adjustAlpha(statusColor, 0.15f));
        tvDetailIcon.setTextColor(statusColor);

        buildDetails(detailsContainer, statusColor);
        buildActions(actionsContainer);
    }

    // ═══════════════════════════════════════════════════════
    // ✅ التفاصيل
    // ═══════════════════════════════════════════════════════

    private void buildDetails(LinearLayout container, int statusColor) {
        if (activity == null) return;

        String type = activity.optString("type", "");

        addDetailRow(container, "📋 " + (isArabic ? "النوع" : "Type"), getTypeName(type));
        addDetailRow(container, "👤 " + (isArabic ? "المستخدم" : "User"), 
                activity.optString("user", "—"));

        switch (type) {
            case "sale":
                double amount = activity.optDouble("amount", 0);
                String currency = activity.optString("currency", "USD");
                if (amount > 0) {
                    addDetailRow(container, "💰 " + (isArabic ? "المبلغ" : "Amount"),
                            formatAmount(amount, currency));
                }
                break;

            case "inventory":
                double accuracy = activity.optDouble("accuracy", -1);
                if (accuracy >= 0) {
                    addDetailRow(container, "🎯 " + (isArabic ? "الدقة" : "Accuracy"),
                            String.format(Locale.US, "%.1f%%", accuracy));
                }
                String risk = activity.optString("risk_level", "none");
                addDetailRow(container, "⚠️ " + (isArabic ? "الخطورة" : "Risk"),
                        getRiskName(risk));
                break;

            case "transfer":
                addDetailRow(container, "📊 " + (isArabic ? "الحالة" : "Status"),
                        activity.optString("transfer_status", "—"));
                break;

            case "rfid":
                addDetailRow(container, "🏷️ " + (isArabic ? "الحالة" : "Status"),
                        activity.optString("rfid_status", "active"));
                break;
        }

        String details = isArabic ? activity.optString("details_ar", "") 
                                  : activity.optString("details_en", "");
        if (!details.isEmpty()) {
            addDetailRow(container, "📝 " + (isArabic ? "التفاصيل" : "Details"), details);
        }

        int refId = activity.optInt("reference_id", 0);
        if (refId > 0) {
            addDetailRow(container, "🔖 " + (isArabic ? "المعرف" : "Reference"), "#" + refId);
        }
    }

    private void addDetailRow(LinearLayout container, String label, String value) {
        View row = getLayoutInflater().inflate(R.layout.item_detail_row, container, false);
        TextView tvLabel = row.findViewById(R.id.tvDetailLabel);
        TextView tvValue = row.findViewById(R.id.tvDetailValue);
        tvLabel.setText(label);
        tvValue.setText(value);
        container.addView(row);
    }

    // ═══════════════════════════════════════════════════════
    // ✅ الإجراءات (مربوطة بالشاشات)
    // ═══════════════════════════════════════════════════════

    private void buildActions(LinearLayout container) {
        if (activity == null) return;

        String type = activity.optString("type", "");
        int refId = activity.optInt("reference_id", 0);

        switch (type) {
            // ═══════════════════════════════════════
            // 🛒 بيع
            // ═══════════════════════════════════════
            case "sale":
                addActionButton(container, "📄 " + (isArabic ? "عرض سجل المبيعات" : "View Sales History"),
                        v -> {
                            dismiss();
                            navigateTo(new MiraSalesHistoryFragment(), "SalesHistory");
                        });

                addActionButton(container, "🖨️ " + (isArabic ? "طباعة الفاتورة" : "Print Invoice"),
                        v -> {
                            dismiss();
                            Toast.makeText(getContext(),
                                    isArabic ? "🖨️ جاري تحضير الفاتورة..." : "🖨️ Preparing invoice...",
                                    Toast.LENGTH_SHORT).show();
                            // TODO: استدعاء PrintUtils مع refId
                            navigateTo(new MiraSalesHistoryFragment(), "SalesHistory");
                        });

                addActionButton(container, "↩️ " + (isArabic ? "فتح الاسترجاع" : "Open Refund"),
                        v -> {
                            dismiss();
                            navigateTo(new MiraSalesHistoryFragment(), "SalesHistory");
                            Toast.makeText(getContext(),
                                    isArabic ? "↩️ اختر الفاتورة من السجل للاسترجاع" 
                                             : "↩️ Select the invoice from history to refund",
                                    Toast.LENGTH_LONG).show();
                        });
                break;

            // ═══════════════════════════════════════
            // 📦 جرد
            // ═══════════════════════════════════════
            case "inventory":
                addActionButton(container, "📋 " + (isArabic ? "عرض شاشة الجرد" : "Open Inventory"),
                        v -> {
                            dismiss();
                            navigateTo(new MiraInventoryProFragment(), "Scan");
                        });

                addActionButton(container, "📂 " + (isArabic ? "فتح الأرشيف" : "Open Archive"),
                        v -> {
                            dismiss();
                            navigateTo(new MiraInventoryProFragment(), "Scan");
                            // بعد الفتح، سيُعرض الأرشيف من داخل الشاشة
                        });
                break;

            // ═══════════════════════════════════════
            // 🔄 تحويل
            // ═══════════════════════════════════════
            case "transfer":
                addActionButton(container, "🚚 " + (isArabic ? "فتح التحويلات" : "Open Transfers"),
                        v -> {
                            dismiss();
                            navigateTo(new MiraTransferFragment(), "Transfers");
                        });
                break;

            // ═══════════════════════════════════════
            // 🏷️ RFID
            // ═══════════════════════════════════════
            case "rfid":
                addActionButton(container, "🔍 " + (isArabic ? "فتح إدارة الشرائح" : "Open Bindings"),
                        v -> {
                            dismiss();
                            navigateTo(new MiraRfidBindingsFragment(), "RfidBindings");
                        });
                break;

            // ═══════════════════════════════════════
            // ⚙️ نظام
            // ═══════════════════════════════════════
            case "system":
                addActionButton(container, "📊 " + (isArabic ? "عرض كل العمليات" : "View All Activities"),
                        v -> {
                            dismiss();
                            navigateTo(new MiraAllActivitiesFragment(), "AllActivities");
                        });
                break;
        }

        // ═══════════════════════════════════════
        // 📋 إجراء عام — نسخ
        // ═══════════════════════════════════════
        addActionButton(container, "📋 " + (isArabic ? "نسخ التفاصيل" : "Copy Details"),
                v -> {
                    String jsonStr = activity.toString();
                    ClipboardManager clipboard = (ClipboardManager) requireContext()
                            .getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText("Activity", jsonStr);
                    if (clipboard != null) clipboard.setPrimaryClip(clip);
                    Toast.makeText(getContext(),
                            isArabic ? "✅ تم النسخ" : "✅ Copied",
                            Toast.LENGTH_SHORT).show();
                    dismiss();
                });
    }

    /**
     * ✅ الانتقال إلى Fragment
     */
    private void navigateTo(Fragment fragment, String tag) {
        try {
            if (getActivity() instanceof UHFMainActivity) {
                ((UHFMainActivity) getActivity()).loadFragment(fragment, tag);
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(getContext(),
                    isArabic ? "⚠️ تعذر فتح الشاشة" : "⚠️ Cannot open screen",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void addActionButton(LinearLayout container, String text,
                                  View.OnClickListener listener) {
        MaterialButton btn = new MaterialButton(requireContext(), null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 6, 0, 0);
        btn.setLayoutParams(params);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        btn.setPadding(32, 24, 32, 24);
        btn.setOnClickListener(listener);
        container.addView(btn);
    }

    // ═══════════════════════════════════════════════════════
    // ✅ الألوان (تحترم الثيمات)
    // ═══════════════════════════════════════════════════════

    private int getStatusColor(String status) {
        Context ctx = getContext();
        if (ctx == null) return Color.GRAY;

        switch (status) {
            case "success": return ContextCompat.getColor(ctx, R.color.activity_success);
            case "warning": return ContextCompat.getColor(ctx, R.color.activity_warning);
            case "danger":  return ContextCompat.getColor(ctx, R.color.activity_danger);
            default:        return ContextCompat.getColor(ctx, R.color.activity_neutral);
        }
    }

    private String formatFullTime(String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) return "—";
        try {
            SimpleDateFormat input = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            SimpleDateFormat output = new SimpleDateFormat(
                    isArabic ? "EEEE، d MMMM yyyy - HH:mm" : "EEEE, MMM d, yyyy - HH:mm",
                    isArabic ? new Locale("ar") : Locale.US);
            Date date = input.parse(timestamp);
            return output.format(date);
        } catch (Exception e) {
            return timestamp;
        }
    }

    private String formatAmount(double amount, String currency) {
        try {
            NumberFormat fmt = NumberFormat.getNumberInstance(Locale.US);
            fmt.setMaximumFractionDigits(2);
            return fmt.format(amount) + " " + currency;
        } catch (Exception e) {
            return String.format(Locale.US, "%.2f %s", amount, currency);
        }
    }

    private String getTypeName(String type) {
        if (!isArabic) return type;
        switch (type) {
            case "sale": return "بيع";
            case "inventory": return "جرد";
            case "transfer": return "تحويل";
            case "rfid": return "RFID";
            case "system": return "نظام";
            default: return type;
        }
    }

    private String getRiskName(String risk) {
        if (!isArabic) return risk;
        switch (risk) {
            case "none": return "✅ آمن";
            case "low": return "🟢 منخفض";
            case "medium": return "⚠️ متوسط";
            case "high": return "🔴 مرتفع";
            case "critical": return "🚨 حرج";
            default: return risk;
        }
    }

    private int adjustAlpha(int color, float factor) {
        int alpha = Math.round(Color.alpha(color) * factor);
        int red = Color.red(color);
        int green = Color.green(color);
        int blue = Color.blue(color);
        return Color.argb(alpha, red, green, blue);
    }
}
