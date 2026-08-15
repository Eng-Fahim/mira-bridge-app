package com.example.uhf.tools;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 🚨 مساعد عرض تنبيهات العبث والتلاعب
 */
public class TamperAlertHelper {

    /**
     * عرض تنبيه العبث
     */
    public static void showTamperAlert(Context context, JSONArray alerts, String epc) {
        if (context == null || alerts == null || alerts.length() == 0) return;
        
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
        builder.setTitle("🚨 تنبيه أمني - اشتباه عبث");
        
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 30, 40, 20);
        
        // رأس التنبيه
        TextView tvHeader = new TextView(context);
        tvHeader.setText("EPC: " + epc);
        tvHeader.setTextSize(14);
        tvHeader.setTextColor(Color.parseColor("#C62828"));
        tvHeader.setGravity(Gravity.CENTER);
        tvHeader.setTypeface(null, android.graphics.Typeface.BOLD);
        layout.addView(tvHeader);
        
        // خط فاصل
        View divider = new View(context);
        divider.setBackgroundColor(Color.parseColor("#E0E0E0"));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 2);
        dividerParams.setMargins(0, 20, 0, 20);
        divider.setLayoutParams(dividerParams);
        layout.addView(divider);
        
        // التنبيهات
        for (int i = 0; i < alerts.length(); i++) {
            try {
                JSONObject alert = alerts.optJSONObject(i);
                if (alert == null) continue;
                
                String message = alert.optString("message", "تنبيه");
                String severity = alert.optString("severity", "warning");
                String type = alert.optString("type", "");
                
                // أيقونة حسب النوع
                String icon = "";
                switch (type) {
                    case "weight_mismatch":
                        icon = "⚖️ ";
                        break;
                    case "tag_tampered":
                        icon = "🔒 ";
                        break;
                    case "tid_mismatch":
                        icon = "🆔 ";
                        break;
                    default:
                        icon = "⚠️ ";
                }
                
                TextView tvAlert = new TextView(context);
                tvAlert.setText(icon + message);
                tvAlert.setTextSize(15);
                tvAlert.setTextColor(getSeverityColor(severity));
                tvAlert.setPadding(0, 10, 0, 10);
                tvAlert.setTypeface(null, android.graphics.Typeface.BOLD);
                layout.addView(tvAlert);
                
                // تفاصيل الوزن
                if (alert.has("expected_weight")) {
                    TextView tvWeight = new TextView(context);
                    tvWeight.setText(String.format(
                        "📋 الوزن المتوقع: %.2fg\n📋 الوزن الفعلي: %.2fg\n📋 الفارق: %.2fg",
                        alert.optDouble("expected_weight", 0),
                        alert.optDouble("actual_weight", 0),
                        alert.optDouble("weight_diff", 0)
                    ));
                    tvWeight.setTextSize(13);
                    tvWeight.setTextColor(Color.parseColor("#666666"));
                    tvWeight.setPadding(20, 5, 0, 10);
                    layout.addView(tvWeight);
                }
            } catch (Exception ignored) {}
        }
        
        builder.setView(layout);
        builder.setPositiveButton("تسجيل الحادث", (dialog, which) -> dialog.dismiss());
        builder.setNegativeButton("تجاهل", (dialog, which) -> dialog.dismiss());
        builder.setCancelable(false);
        builder.show();
    }
    
    /**
     * الحصول على لون الخطورة
     */
    private static int getSeverityColor(String severity) {
        if (severity == null) return Color.parseColor("#666666");
        
        switch (severity) {
            case "security":
                return Color.parseColor("#B71C1C"); // أحمر داكن
            case "critical":
                return Color.parseColor("#D32F2F"); // أحمر
            case "warning":
                return Color.parseColor("#F57C00"); // برتقالي
            default:
                return Color.parseColor("#666666");
        }
    }
    
    /**
     * التحقق من وجود عبث خطير يمنع العملية
     */
    public static boolean hasSecurityAlert(JSONArray alerts) {
        if (alerts == null) return false;
        
        for (int i = 0; i < alerts.length(); i++) {
            JSONObject alert = alerts.optJSONObject(i);
            if (alert != null && "security".equals(alert.optString("severity"))) {
                return true;
            }
        }
        return false;
    }
}
