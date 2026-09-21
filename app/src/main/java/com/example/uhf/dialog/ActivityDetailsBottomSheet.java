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
import android.util.Log;
import org.json.JSONArray;
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
    // ✅ المبلغ بالدولار
    double amountUsd = activity.optDouble("final_amount_usd", 
                      activity.optDouble("amount_usd", 
                      activity.optDouble("amount", 0)));
    
    // ✅ المبلغ المحلي
    double amountLocal = activity.optDouble("final_amount", 
                        activity.optDouble("amount_local", 0));
    
    // ✅ العملة وسعر الصرف
    String currencyCode = activity.optString("currency", "USD");
    double exchangeRate = activity.optDouble("exchange_rate", 1.0);
    
    // ✅ المبلغ بالدولار دائماً
    if (amountUsd > 0) {
        addDetailRow(container, "💵 " + (isArabic ? "المبلغ (USD)" : "Amount (USD)"),
                String.format(Locale.US, "$%,.2f", amountUsd));
    }
    
    // ✅ المبلغ المحلي (إن كانت العملة مختلفة)
    if (amountLocal > 0 && !currencyCode.equals("USD")) {
        addDetailRow(container, "💰 " + (isArabic ? "المبلغ المحلي" : "Local Amount"),
                formatAmount(amountLocal, currencyCode));
        
        // ✅ سعر الصرف
        if (exchangeRate > 0 && exchangeRate != 1.0) {
            addDetailRow(container, "💱 " + (isArabic ? "سعر الصرف" : "Exchange Rate"),
                    String.format(Locale.US, "1 USD = %,.2f %s", 
                            exchangeRate, currencyCode));
        }
    }
    
    // ✅ العملة (إن كانت USD، اعرضها بشكل بسيط)
    if (amountUsd > 0 && (currencyCode.equals("USD") || amountLocal <= 0)) {
        addDetailRow(container, "💱 " + (isArabic ? "العملة" : "Currency"), currencyCode);
    }
    
    // ✅ عدد القطع
    int itemCount = activity.optInt("item_count", 0);
    if (itemCount > 0) {
        addDetailRow(container, "📦 " + (isArabic ? "عدد القطع" : "Items Count"),
                String.valueOf(itemCount));
    }
    
    // ✅ رقم الفاتورة
    String saleNumber = activity.optString("sale_number", "");
    if (!saleNumber.isEmpty()) {
        addDetailRow(container, "🧾 " + (isArabic ? "رقم الفاتورة" : "Invoice #"), saleNumber);
    }
    
    // ✅ الوزن الإجمالي
    double totalWeight = activity.optDouble("total_weight", 0);
    if (totalWeight > 0) {
        addDetailRow(container, "⚖️ " + (isArabic ? "الوزن الإجمالي" : "Total Weight"),
                String.format(Locale.US, "%.2f g", totalWeight));
    }
    
    // ✅ الخصم (إن وُجد)
    double discountUsd = activity.optDouble("discount_amount_usd", 0);
    if (discountUsd > 0) {
        addDetailRow(container, "🏷️ " + (isArabic ? "الخصم" : "Discount"),
                String.format(Locale.US, "$%,.2f", discountUsd));
    }
    
    // ✅ حالة الإرجاع
    String refundStatus = activity.optString("refund_status", 
                          activity.optString("status", ""));
    if (refundStatus.equals("refunded") || refundStatus.equals("partial")) {
        String refundLabel = refundStatus.equals("refunded") 
                ? (isArabic ? "🔄 مُرجعة بالكامل" : "🔄 Fully Refunded")
                : (isArabic ? "⚠️ مُرجعة جزئياً" : "⚠️ Partially Refunded");
        addDetailRow(container, "↩️ " + (isArabic ? "حالة الإرجاع" : "Refund Status"), refundLabel);
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
                        
                   // ═══════════════════════════════════════
// 📦 عرض تفاصيل كل قطعة
// ═══════════════════════════════════════
try {
    org.json.JSONArray itemsArr = activity.optJSONArray("items");
    if (itemsArr != null && itemsArr.length() > 0) {
        // ✅ فاصل
        addSectionHeader(container, 
            "📦 " + (isArabic ? "القطع المباعة" : "Sold Items") 
            + " (" + itemsArr.length() + ")");
        
        for (int i = 0; i < itemsArr.length(); i++) {
            JSONObject item = itemsArr.optJSONObject(i);
            if (item == null) continue;
            
            // ✅ عنوان القطعة
            String itemTitle = item.optString("title", 
                               item.optString("item_title", 
                               (isArabic ? "قطعة" : "Item") + " #" + (i + 1)));
            
            // ✅ EPC / RFID
            String epc = item.optString("epc", 
                         item.optString("rfid_epc", ""));
            
            // ✅ Serial
            String serial = item.optString("serial", 
                            item.optString("serial_number", ""));
            
            // ✅ العيار
            String karat = item.optString("karat", 
                           item.optString("gold_karat", ""));
            
            // ✅ الوزن
            double weight = item.optDouble("weight", 
                            item.optDouble("gross_weight_grams", 0));
            
            // ✅ السعر
            double priceUsd = item.optDouble("price_usd", 0);
            double priceLocal = item.optDouble("price_local", 0);
            
            // ✅ بناء نص القطعة
            StringBuilder sb = new StringBuilder();
            sb.append("▸ ").append(itemTitle);
            
            if (!serial.isEmpty()) {
                sb.append("\n   🔢 Serial: ").append(serial);
            }
            
            // ✅ عرض RFID/EPC بشكل بارز
            if (!epc.isEmpty()) {
                sb.append("\n   📡 RFID: ").append(epc);
            } else {
                sb.append("\n   📡 RFID: ")
                  .append(isArabic ? "غير مربوط" : "Not bound");
            }
            
            if (!karat.isEmpty()) {
                sb.append("\n   🏷️ ").append(isArabic ? "العيار" : "Karat")
                  .append(": ").append(karat);
            }
            
            if (weight > 0) {
                sb.append("\n   ⚖️ ").append(isArabic ? "الوزن" : "Weight")
                  .append(": ").append(String.format(Locale.US, "%.2f g", weight));
            }
            
            if (priceUsd > 0) {
                sb.append("\n   💵 ").append(String.format(Locale.US, "$%,.2f", priceUsd));
            }
            
            if (priceLocal > 0 && !currencyCode.equals("USD")) {
                sb.append("  •  ").append(formatAmount(priceLocal, currencyCode));
            }
            
            // ✅ صف لكل قطعة
            addDetailRow(container, 
                "🆔 " + (isArabic ? "قطعة" : "Item") + " #" + (i + 1), 
                sb.toString());
        }
    }
} catch (Exception e) {
    Log.e("ActivityDetailsSheet", "Error parsing items: " + e.getMessage());
}     

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

    /**
 * ✅ تنسيق المبلغ حسب العملة
 * - العملات الكبيرة (YER, EGP): بدون كسور عشرية
 * - العملات الصغيرة (USD, SAR, AED): بكسرين عشريين
 */
private String formatAmount(double amount, String currency) {
    try {
        if (currency == null) currency = "USD";
        
        // ✅ العملات ذات الأرقام الكبيرة (لا نحتاج كسور)
        boolean isLargeCurrency = currency.equals("YER") 
                || currency.equals("EGP") 
                || currency.equals("IQD")
                || currency.equals("SYP");
        
        NumberFormat fmt = NumberFormat.getNumberInstance(Locale.US);
        
        if (isLargeCurrency) {
            fmt.setMaximumFractionDigits(0);
            return fmt.format(amount) + " " + currency;
        } else {
            fmt.setMaximumFractionDigits(2);
            fmt.setMinimumFractionDigits(2);
            return fmt.format(amount) + " " + currency;
        }
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
    
    /**
 * ✅ عرض المبلغ بعملتين (USD + محلي)
 */
private void addDualAmountRow(LinearLayout container, String label, 
                              double amountUsd, double amountLocal, 
                              String currency, double exchangeRate) {
    StringBuilder sb = new StringBuilder();
    
    if (amountUsd > 0) {
        sb.append(String.format(Locale.US, "$%,.2f", amountUsd));
    }
    
    if (amountLocal > 0 && !currency.equals("USD") && amountLocal != amountUsd) {
        if (sb.length() > 0) sb.append("  •  ");
        sb.append(formatAmount(amountLocal, currency));
    }
    
    if (sb.length() > 0) {
        addDetailRow(container, label, sb.toString());
    }
}

/**
 * ✅ الحصول على اسم العملة بالعربية
 */
private String getCurrencyNameArabic(String currency) {
    switch (currency) {
        case "USD": return "دولار أمريكي";
        case "YER": return "ريال يمني";
        case "EGP": return "جنيه مصري";
        case "SAR": return "ريال سعودي";
        case "AED": return "درهم إماراتي";
        case "IQD": return "دينار عراقي";
        default: return currency;
    }
}

/**
 * ✅ إضافة عنوان قسم مميز داخل القائمة
 */
private void addSectionHeader(LinearLayout container, String title) {
    try {
        TextView header = new TextView(requireContext());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 24, 0, 8);
        header.setLayoutParams(params);
        header.setText(title);
        header.setTextSize(15);
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        header.setPadding(0, 16, 0, 12);
        
        // ✅ لون مميز من الثيم
        int color = ContextCompat.getColor(requireContext(), 
                R.color.activity_neutral);
        header.setTextColor(color);
        
        // ✅ خط علوي
        header.setCompoundDrawablePadding(8);
        
        container.addView(header);
        
        // ✅ خط فاصل رفيع
        View divider = new View(requireContext());
        LinearLayout.LayoutParams dParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        dParams.setMargins(0, 4, 0, 12);
        divider.setLayoutParams(dParams);
        divider.setBackgroundColor(adjustAlpha(color, 0.3f));
        container.addView(divider);
        
    } catch (Exception e) {
        Log.e("ActivityDetailsSheet", "addSectionHeader error: " + e.getMessage());
    }
}
}
