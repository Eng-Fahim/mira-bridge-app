package com.mira.jewelry.ui.reports;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.mira.jewelry.R;
import com.mira.jewelry.models.OrganizationProfile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

public class MiraReportsProFragment extends Fragment {

    private WebView webView;
    private OrganizationProfile orgProfile;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_mira_reports_pro, container, false);
        webView = root.findViewById(R.id.report_webview);
        
        setupWebView();
        return root;
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDefaultTextEncodingName("utf-8");
        
        webView.setWebViewClient(new WebViewClient());
    }

    public void updateReportView(String type, JSONObject apiResponseData, String fromDate, String toDate) {
        if (webView == null || apiResponseData == null) return;

        try {
            String htmlContent = generateExecutiveHTML(type, apiResponseData, fromDate, toDate);
            webView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(getContext(), "حدث خطأ أثناء معالجة بيانات التقرير", Toast.LENGTH_SHORT).show();
        }
    }

    private String generateExecutiveHTML(String type, JSONObject data, String from, String to) {
        StringBuilder html = new StringBuilder();
        String orgName = (orgProfile != null && orgProfile.getOrgName() != null) ? orgProfile.getOrgName() : "MIRA JEWELRY SYSTEM";

        html.append("<!DOCTYPE html><html dir='rtl' lang='ar'><head><meta charset='UTF-8'>")
            .append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>")
            .append("<style>")
            .append("body{font-family:system-ui,-apple-system,sans-serif;margin:0;padding:12px;background:#F8FAFC;color:#0F172A;}")
            .append(".header{background:linear-gradient(135deg,#0F172A,#1E293B);color:#FFF;padding:18px;border-radius:12px;margin-bottom:14px;box-shadow:0 4px 6px -1px rgba(0,0,0,0.1);}")
            .append(".header h2{margin:0;font-size:20px;font-weight:700;letter-spacing:-0.5px;}")
            .append(".header p{margin:6px 0 0;font-size:11px;color:#94A3B8;}")
            .append(".card{background:#FFF;padding:16px;border-radius:12px;margin-bottom:12px;border:1px solid #E2E8F0;box-shadow:0 1px 2px rgba(0,0,0,0.05);}")
            .append(".card-title{font-size:13px;font-weight:700;color:#334155;margin-bottom:12px;border-bottom:1px solid #F1F5F9;padding-bottom:8px;text-transform:uppercase;}")
            .append(".grid{display:grid;grid-template-columns:repeat(2, 1fr);gap:10px;margin-bottom:8px;}")
            .append(".metric{background:#F8FAFC;border:1px solid #E2E8F0;padding:12px;border-radius:10px;text-align:center;}")
            .append(".metric .val{font-size:16px;font-weight:800;color:#0F172A;margin-top:2px;}")
            .append(".metric .lbl{font-size:10px;color:#64748B;font-weight:600;margin-top:2px;}")
            .append(".gold{color:#D97706;}")
            .append("table{width:100%;border-collapse:separate;border-spacing:0;margin-top:8px;font-size:11px;}")
            .append("th{background:#F1F5F9;color:#475569;padding:10px 8px;font-weight:700;text-align:center;border-bottom:1px solid #CBD5E1;}")
            .append("td{padding:10px 8px;border-bottom:1px solid #E2E8F0;text-align:center;color:#334155;}")
            .append("tr:last-child td{border-bottom:none;}")
            .append(".badge{padding:3px 8px;border-radius:6px;font-size:10px;font-weight:700;display:inline-block;}")
            .append(".badge-success{background:#DCFCE7;color:#15803D;}")
            .append(".badge-danger{background:#FEE2E2;color:#B91C1C;}")
            .append("</style></head><body>");

        html.append("<div class='header'><h2>").append(orgName).append("</h2>")
            .append("<p>تقرير النطاق التنفيذي للفترة من: ").append(from).append(" إلى: ").append(to).append("</p></div>");

        switch (type) {
            case "inventory": 
                renderInventoryHTML(html, data); 
                break;
            case "sales": 
                renderSalesHTML(html, data); 
                break;
            case "audit": 
                renderAuditHTML(html, data); 
                break;
            case "gates": 
                renderGatesHTML(html, data); 
                break;
            default: 
                renderDashboardHTML(html, data); 
                break;
        }

        html.append("</body></html>");
        return html.toString();
    }

    private void renderDashboardHTML(StringBuilder html, JSONObject data) {
        JSONObject summary = data.optJSONObject("summary");
        if (summary == null) summary = data;

        html.append("<div class='card'><div class='card-title'>📊 ملخص الأداء التنفيذي</div><div class='grid'>")
            .append(metricBox("📦", String.valueOf(summary.optInt("total_items", 0)), "إجمالي القطع"))
            .append(metricBox("⚖️", String.format(Locale.US, "%.2f جم", summary.optDouble("total_weight", 0)), "الوزن القائم"))
            .append(metricBox("💵", String.format(Locale.US, "%,.2f", summary.optDouble("total_revenue", 0)), "المبيعات"))
            .append(metricBox("📈", String.format(Locale.US, "%,.2f", summary.optDouble("net_profit", 0)), "أجور الصياغة"))
            .append("</div></div>");

        renderInventoryHTML(html, data);
    }

    private void renderInventoryHTML(StringBuilder html, JSONObject data) {
        JSONObject inv = data.optJSONObject("inventory");
        if (inv == null) inv = data;

        html.append("<div class='card'><div class='card-title'>📦 تفكيك الأوزان والعيارات المقارنة</div>")
            .append("<table><tr><th>العيار</th><th>الوزن القائم</th><th>وزن الأحجار</th><th>الوزن الصافي</th><th>صافي 24K</th></tr>");

        JSONArray karats = inv.optJSONArray("karat_breakdown");
        if (karats != null && karats.length() > 0) {
            for (int i = 0; i < karats.length(); i++) {
                JSONObject k = karats.optJSONObject(i);
                if (k == null) continue;
                html.append("<tr><td><b>").append(k.optString("karat", "—")).append("</b></td>")
                    .append("<td>").append(String.format(Locale.US, "%.2f جم", k.optDouble("gross_weight", 0))).append("</td>")
                    .append("<td>").append(String.format(Locale.US, "%.2f جم", k.optDouble("stones_weight", 0))).append("</td>")
                    .append("<td>").append(String.format(Locale.US, "%.2f جم", k.optDouble("net_weight", 0))).append("</td>")
                    .append("<td class='gold'><b>").append(String.format(Locale.US, "%.2f جم", k.optDouble("net_24k", 0))).append("</b></td></tr>");
            }
        } else {
            html.append("<tr><td colspan='5'>لا توجد تفاصيل متاحة للمخزون حالياً</td></tr>");
        }
        html.append("</table></div>");
    }

    private void renderSalesHTML(StringBuilder html, JSONObject data) {
        JSONObject sales = data.optJSONObject("sales");
        if (sales == null) sales = data;

        html.append("<div class='card'><div class='card-title'>💰 المؤشرات المالية والمبيعات</div><div class='grid'>")
            .append(metricBox("💵", String.format(Locale.US, "%,.2f", sales.optDouble("total_revenue", 0)), "إجمالي الإيرادات"))
            .append(metricBox("🔨", String.format(Locale.US, "%,.2f", sales.optDouble("total_making_charges", 0)), "أجور الصياغة (الربح)"))
            .append(metricBox("⚖️", String.format(Locale.US, "%.2f جم", sales.optDouble("total_gold_weight_sold", 0)), "الذهب المباع"))
            .append(metricBox("🧾", String.valueOf(sales.optInt("invoices_count", 0)), "الفواتير المصدرة"))
            .append("</div></div>");
    }

    private void renderAuditHTML(StringBuilder html, JSONObject data) {
        JSONObject audit = data.optJSONObject("audit_summary");
        if (audit == null) audit = data;

        html.append("<div class='card'><div class='card-title'>📋 نتائج تدقيق ودقة الجرد</div><div class='grid'>")
            .append(metricBox("🎯", String.format(Locale.US, "%.2f%%", audit.optDouble("accuracy", 100.0)), "نسبة التطابق"))
            .append(metricBox("✅", String.valueOf(audit.optInt("found_items", 0)), "قطع مطابقة"))
            .append(metricBox("❌", String.valueOf(audit.optInt("missing_items", 0)), "عجز/مفقودات"))
            .append(metricBox("📊", String.valueOf(audit.optInt("total_sessions", 0)), "جلسات الجرد"))
            .append("</div></div>");
    }

    private void renderGatesHTML(StringBuilder html, JSONObject data) {
    JSONArray logs = data.optJSONArray("gate_logs");
    html.append("<div class='card'><div class='card-title'>🚪 سجل البوابات الأمنية والتمرير</div>");

    if (logs != null && logs.length() > 0) {
        html.append("<table><tr><th>التاريخ والوقت</th><th>البوابة</th><th>رمز EPC</th><th>الحالة</th></tr>");
        for (int i = 0; i < Math.min(logs.length(), 50); i++) {
            JSONObject l = logs.optJSONObject(i);
            if (l == null) continue;

            String time = l.optString("event_time", l.optString("created_at", l.optString("scan_time", "—")));
            String gateName = l.optString("gate_name", "البوابة الرئيسية");
            String epc = l.optString("epc", l.optString("epc_code", "—"));
            
            // فحص حالة الإنذار من مختلف المعلمات المتوقعة
            boolean isAlarm = l.optBoolean("is_alarm", false) || l.optInt("alarm", 0) == 1 || "ALARM".equalsIgnoreCase(l.optString("status"));

            html.append("<tr><td>").append(time).append("</td>")
                .append("<td>").append(gateName).append("</td>")
                .append("<td><code>").append(epc).append("</code></td>")
                .append("<td><span class='badge ").append(isAlarm ? "badge-danger'>🚨 إنذار" : "badge-success'>✅ مصرح").append("</span></td></tr>");
        }
        html.append("</table>");
    } else {
        html.append("<div style='text-align:center;padding:20px;color:#64748B;'>")
            .append("ℹ️ لا توجد أحداث مرور مسجلة عبر البوابات حالياً.")
            .append("</div>");
    }
    html.append("</div>");
}


    private String metricBox(String icon, String value, String label) {
        return "<div class='metric'><div class='lbl'>" + icon + " " + label + "</div><div class='val'>" + value + "</div></div>";
    }

    public void setOrganizationProfile(OrganizationProfile profile) {
        this.orgProfile = profile;
    }
}
