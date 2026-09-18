package com.example.uhf.dialog;

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

import com.example.uhf.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;

import org.json.JSONObject;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * ✅ Bottom Sheet احترافي لعرض تفاصيل العملية
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

        // ✅ Views
        FrameLayout iconContainer = view.findViewById(R.id.iconContainer);
        TextView tvDetailIcon = view.findViewById(R.id.tvDetailIcon);
        TextView tvDetailTitle = view.findViewById(R.id.tvDetailTitle);
        TextView tvDetailTime = view.findViewById(R.id.tvDetailTime);
        LinearLayout detailsContainer = view.findViewById(R.id.detailsContainer);
        LinearLayout actionsContainer = view.findViewById(R.id.actionsContainer);

        // ✅ البيانات
        String icon = activity.optString("icon", "⚙️");
        String title = isArabic ? activity.optString("title_ar", "") 
                                : activity.optString("title_en", "");
        String timestamp = activity.optString("timestamp", "");
        String status = activity.optString("status", "neutral");

        tvDetailIcon.setText(icon);
        tvDetailTitle.setText(title);
        tvDetailTime.setText(formatFullTime(timestamp));

        // ✅ لون الحالة
        int statusColor;
        switch (status) {
            case "success": statusColor = Color.parseColor("#16A34A"); break;
            case "warning": statusColor = Color.parseColor("#F59E0B"); break;
            case "danger":  statusColor = Color.parseColor("#DC2626"); break;
            default:        statusColor = Color.parseColor("#6B7280"); break;
        }
        iconContainer.getBackground().setTint(adjustAlpha(statusColor, 0.15f));
        tvDetailIcon.setTextColor(statusColor);

        // ✅ بناء الصفوف حسب نوع العملية
        buildDetails(detailsContainer, statusColor);

        // ✅ بناء الإجراءات
        buildActions(actionsContainer);
    }

    /**
     * ✅ بناء صفوف التفاصيل
     */
    private void buildDetails(LinearLayout container, int statusColor) {
        if (activity == null) return;

        String type = activity.optString("type", "");

        // ✅ التفاصيل العامة
        addDetailRow(container, "📋 " + (isArabic ? "النوع" : "Type"), getTypeName(type));
        addDetailRow(container, "👤 " + (isArabic ? "المستخدم" : "User"), 
                activity.optString("user", "—"));

        // ✅ تفاصيل حسب النوع
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

            case "system":
                // لا شيء إضافي
                break;
        }

        // ✅ التفاصيل النصية
        String details = isArabic ? activity.optString("details_ar", "") 
                                  : activity.optString("details_en", "");
        if (!details.isEmpty()) {
            addDetailRow(container, "📝 " + (isArabic ? "التفاصيل" : "Details"), details);
        }

        // ✅ Reference ID
        int refId = activity.optInt("reference_id", 0);
        if (refId > 0) {
            addDetailRow(container, "🔖 " + (isArabic ? "المعرف" : "Reference"), "#" + refId);
        }
    }

    /**
     * ✅ إضافة صف تفصيلي
     */
    private void addDetailRow(LinearLayout container, String label, String value) {
        View row = getLayoutInflater().inflate(R.layout.item_detail_row, container, false);
        TextView tvLabel = row.findViewById(R.id.tvDetailLabel);
        TextView tvValue = row.findViewById(R.id.tvDetailValue);
        tvLabel.setText(label);
        tvValue.setText(value);
        container.addView(row);
    }

    /**
     * ✅ بناء الإجراءات حسب نوع العملية
     */
    private void buildActions(LinearLayout container) {
        if (activity == null) return;

        String type = activity.optString("type", "");

        // ═══════════════════════════════════════════════════════
        // Actions حسب النوع
        // ═══════════════════════════════════════════════════════

        if ("sale".equals(type)) {
            addActionButton(container, "🖨️ " + (isArabic ? "طباعة الفاتورة" : "Print Invoice"),
                    v -> {
                        Toast.makeText(getContext(),
                                isArabic ? "🖨️ جاري الطباعة..." : "🖨️ Printing...",
                                Toast.LENGTH_SHORT).show();
                        dismiss();
                    });
            addActionButton(container, "↩️ " + (isArabic ? "استرجاع" : "Refund"),
                    v -> {
                        Toast.makeText(getContext(),
                                isArabic ? "↩️ فتح شاشة الاسترجاع..." : "↩️ Opening refund...",
                                Toast.LENGTH_SHORT).show();
                        dismiss();
                    });
        } else if ("inventory".equals(type)) {
            addActionButton(container, "📋 " + (isArabic ? "عرض التقرير الكامل" : "View Full Report"),
                    v -> {
                        Toast.makeText(getContext(),
                                isArabic ? "📋 جاري فتح التقرير..." : "📋 Opening report...",
                                Toast.LENGTH_SHORT).show();
                        dismiss();
                    });
            addActionButton(container, "🖨️ " + (isArabic ? "طباعة التقرير" : "Print Report"),
                    v -> {
                        Toast.makeText(getContext(),
                                isArabic ? "🖨️ جاري الطباعة..." : "🖨️ Printing...",
                                Toast.LENGTH_SHORT).show();
                        dismiss();
                    });
        } else if ("transfer".equals(type)) {
            addActionButton(container, "🚚 " + (isArabic ? "تتبع التحويل" : "Track Transfer"),
                    v -> {
                        Toast.makeText(getContext(),
                                isArabic ? "🚚 جاري التتبع..." : "🚚 Tracking...",
                                Toast.LENGTH_SHORT).show();
                        dismiss();
                    });
        } else if ("rfid".equals(type)) {
            addActionButton(container, "🔍 " + (isArabic ? "فحص الشريحة" : "Verify Chip"),
                    v -> {
                        Toast.makeText(getContext(),
                                isArabic ? "🔍 جاري الفحص..." : "🔍 Verifying...",
                                Toast.LENGTH_SHORT).show();
                        dismiss();
                    });
        }

        // ═══════════════════════════════════════════════════════
        // Actions عامة
        // ═══════════════════════════════════════════════════════
        addActionButton(container, "📋 " + (isArabic ? "نسخ التفاصيل" : "Copy Details"),
                v -> {
                    String jsonStr = activity.toString();
                    android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) requireContext()
                                    .getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    android.content.ClipData clip =
                            android.content.ClipData.newPlainText("Activity", jsonStr);
                    if (clipboard != null) clipboard.setPrimaryClip(clip);
                    Toast.makeText(getContext(),
                            isArabic ? "✅ تم النسخ" : "✅ Copied",
                            Toast.LENGTH_SHORT).show();
                    dismiss();
                });
    }

    /**
     * ✅ إضافة زر إجراء
     */
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

    /**
     * ✅ تنسيق التاريخ الكامل
     */
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

    /**
     * ✅ تنسيق المبلغ
     */
    private String formatAmount(double amount, String currency) {
        try {
            NumberFormat fmt = NumberFormat.getNumberInstance(Locale.US);
            fmt.setMaximumFractionDigits(2);
            return fmt.format(amount) + " " + currency;
        } catch (Exception e) {
            return String.format(Locale.US, "%.2f %s", amount, currency);
        }
    }

    /**
     * ✅ اسم النوع بالعربية
     */
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

    /**
     * ✅ اسم مستوى الخطورة
     */
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

    /**
     * ✅ تعديل شفافية اللون
     */
    private int adjustAlpha(int color, float factor) {
        int alpha = Math.round(Color.alpha(color) * factor);
        int red = Color.red(color);
        int green = Color.green(color);
        int blue = Color.blue(color);
        return Color.argb(alpha, red, green, blue);
    }
}
