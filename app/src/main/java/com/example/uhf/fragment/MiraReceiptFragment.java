package com.example.uhf.fragment;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.manager.MiraOrgProfileManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MiraReceiptFragment extends Fragment {

    private static final String TAG = "MiraReceiptFragment";
    private static final String ARG_RECEIPT_JSON = "receipt_json";

    private String receiptJsonStr = "{}";
    private boolean isArabic;
    private MiraOrgProfileManager orgProfile;

    private WebView webReceipt;
    private TextView btnCloseReceipt;
    private Button btnPrintReceipt, btnShareReceipt, btnSaveReceipt;

    public static MiraReceiptFragment newInstance(String jsonResponse) {
        MiraReceiptFragment fragment = new MiraReceiptFragment();
        Bundle args = new Bundle();
        args.putString(ARG_RECEIPT_JSON, jsonResponse);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            receiptJsonStr = getArguments().getString(ARG_RECEIPT_JSON, "{}");
        }
        isArabic = AppCompatDelegate.getApplicationLocales().toLanguageTags().contains("ar");
        if (getContext() != null) {
            orgProfile = MiraOrgProfileManager.getInstance(getContext());
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mira_receipt, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        bindViews(view);
        applyLanguage();
        setupListeners();
        renderHtmlReceipt();
    }

    private void bindViews(View view) {
        webReceipt = view.findViewById(R.id.webMiraReceipt);
        btnCloseReceipt = view.findViewById(R.id.btnMiraCloseReceipt);
        btnPrintReceipt = view.findViewById(R.id.btnMiraPrintReceipt);
        btnShareReceipt = view.findViewById(R.id.btnMiraShareReceipt);
        btnSaveReceipt = view.findViewById(R.id.btnMiraSaveReceipt);
    }

    private void applyLanguage() {
        if (btnPrintReceipt != null) btnPrintReceipt.setText(isArabic ? "🖨️ طباعة" : "🖨️ Print");
        if (btnShareReceipt != null) btnShareReceipt.setText(isArabic ? "📤 مشاركة" : "📤 Share");
        if (btnSaveReceipt != null) btnSaveReceipt.setText(isArabic ? "💾 حفظ" : "💾 Save");
    }

    private void setupListeners() {
        if (btnCloseReceipt != null) btnCloseReceipt.setOnClickListener(v -> closeFragment());
        if (btnPrintReceipt != null) btnPrintReceipt.setOnClickListener(v -> Toast.makeText(getContext(), isArabic ? "🖨️ جاري الطباعة..." : "🖨️ Printing...", Toast.LENGTH_SHORT).show());
        if (btnShareReceipt != null) btnShareReceipt.setOnClickListener(v -> shareReceipt());
        if (btnSaveReceipt != null) btnSaveReceipt.setOnClickListener(v -> Toast.makeText(getContext(), isArabic ? "💾 تم الحفظ" : "💾 Saved", Toast.LENGTH_SHORT).show());
    }

    private void shareReceipt() {
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_SUBJECT, (isArabic ? "إيصال بيع - " : "Sales Receipt - ") + (orgProfile != null ? orgProfile.getOrgName() : "MIRA"));
            intent.putExtra(Intent.EXTRA_TEXT, receiptJsonStr);
            startActivity(Intent.createChooser(intent, isArabic ? "مشاركة الإيصال" : "Share Receipt"));
        } catch (Exception e) {
            Toast.makeText(getContext(), isArabic ? "خطأ في المشاركة" : "Share error", Toast.LENGTH_SHORT).show();
        }
    }

    private void renderHtmlReceipt() {
        if (webReceipt == null) return;

        String orgName = orgProfile != null ? orgProfile.getOrgName() : "MIRA Jewelry";
        String orgSlogan = orgProfile != null ? orgProfile.getOrgSlogan() : "";
        String orgPhone = orgProfile != null ? orgProfile.getOrgPhone() : "";
        String orgAddress = orgProfile != null ? orgProfile.getOrgAddress() : "";
        String currency = "YER";
        String dir = isArabic ? "rtl" : "ltr";

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html dir='").append(dir).append("'><head><meta charset='UTF-8'><style>")
            .append("body{background:#fff;color:#1a1a1a;font-family:sans-serif;padding:16px;max-width:400px;margin:auto;direction:").append(dir).append("}")
            .append(".header{text-align:center;border-bottom:2px solid #C5A059;padding-bottom:12px;margin-bottom:16px}")
            .append(".org-name{font-size:20px;font-weight:bold;color:#1A237E;margin-bottom:2px}")
            .append(".org-slogan{font-size:11px;color:#666;margin-bottom:4px}")
            .append(".org-contact{font-size:10px;color:#888;line-height:1.5}")
            .append(".checkmark{width:50px;height:50px;background:#22C55E;border-radius:50%;display:flex;align-items:center;justify-content:center;margin:12px auto;font-size:28px;color:#fff}")
            .append(".row{display:flex;justify-content:space-between;margin-bottom:6px;font-size:13px}")
            .append(".label{color:#666}.value{font-weight:bold}")
            .append("table{width:100%;border-collapse:collapse;margin:12px 0;font-size:12px}")
            .append("th{background:#f5f5f5;padding:6px;text-align:center;font-weight:bold;border-bottom:1px solid #ddd}")
            .append("td{padding:6px;text-align:center;border-bottom:1px solid #eee}")
            .append(".total-section{background:#1A237E;color:#fff;padding:12px;border-radius:8px;text-align:center;margin-top:12px}")
            .append(".total-amount{font-size:24px;font-weight:bold}")
            .append(".footer{text-align:center;margin-top:16px;font-size:10px;color:#888;line-height:1.6}")
            .append("</style></head><body>");

        // Header - بيانات الجهة
        html.append("<div class='header'>")
            .append("<div class='org-name'>").append(orgName).append("</div>");
        if (!orgSlogan.isEmpty()) html.append("<div class='org-slogan'>").append(orgSlogan).append("</div>");
        html.append("<div class='org-contact'>");
        if (!orgPhone.isEmpty()) html.append("📞 ").append(orgPhone).append("<br>");
        if (!orgAddress.isEmpty()) html.append("📍 ").append(orgAddress);
        html.append("</div></div>");

        // علامة النجاح
        html.append("<div class='checkmark'>✓</div>");
        html.append("<div style='text-align:center;font-weight:bold;font-size:16px;margin-bottom:12px;color:#22C55E'>")
            .append(isArabic ? "تم البيع بنجاح!" : "Sale Completed!").append("</div>");

        try {
            JSONObject root = new JSONObject(receiptJsonStr);
            JSONObject transaction = root.optJSONObject("transaction");
            if (transaction == null) transaction = root;

            String saleId = transaction.optString("sale_id", transaction.optString("transaction_id", "—"));
            String date = transaction.optString("timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()));
            String customer = transaction.optString("customer_name", isArabic ? "عميل" : "Customer");
            String phone = transaction.optString("customer_phone", "");
            String paymentMethod = transaction.optString("payment_method", "CASH");
            int count = transaction.optInt("total_items", transaction.optInt("item_count", 0));
            double subtotal = transaction.optDouble("subtotal", 0);
            double discount = transaction.optDouble("discount_percent", 0);
            double finalAmount = transaction.optDouble("final_amount", subtotal);

            String paymentAr;
            switch (paymentMethod) {
                case "CASH": paymentAr = isArabic ? "نقداً" : "Cash"; break;
                case "CARD": paymentAr = isArabic ? "بطاقة" : "Card"; break;
                case "TRANSFER": paymentAr = isArabic ? "تحويل" : "Transfer"; break;
                default: paymentAr = paymentMethod;
            }

            html.append("<div class='row'><span class='label'>").append(isArabic ? "رقم العملية" : "Receipt #").append("</span><span class='value'>#").append(saleId).append("</span></div>");
            html.append("<div class='row'><span class='label'>").append(isArabic ? "التاريخ" : "Date").append("</span><span class='value'>").append(date).append("</span></div>");
            html.append("<div class='row'><span class='label'>").append(isArabic ? "العميل" : "Customer").append("</span><span class='value'>").append(customer).append("</span></div>");
            if (!phone.isEmpty()) html.append("<div class='row'><span class='label'>").append(isArabic ? "الهاتف" : "Phone").append("</span><span class='value'>").append(phone).append("</span></div>");
            html.append("<div class='row'><span class='label'>").append(isArabic ? "الدفع" : "Payment").append("</span><span class='value'>").append(paymentAr).append("</span></div>");
            html.append("<div class='row'><span class='label'>").append(isArabic ? "القطع" : "Items").append("</span><span class='value'>").append(count).append("</span></div>");

            // جدول القطع
            JSONArray items = transaction.optJSONArray("items");
            if (items != null && items.length() > 0) {
                html.append("<table><tr><th>#</th><th>").append(isArabic ? "القطعة" : "Item").append("</th><th>").append(isArabic ? "العيار" : "Karat").append("</th><th>").append(isArabic ? "الوزن" : "Weight").append("</th><th>").append(isArabic ? "السعر" : "Price").append("</th></tr>");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    html.append("<tr><td>").append(i + 1).append("</td>")
                        .append("<td>").append(item.optString("title", "—")).append("</td>")
                        .append("<td>").append(item.optString("karat", "—")).append("</td>")
                        .append("<td>").append(String.format(Locale.US, "%.2fg", item.optDouble("weight", 0))).append("</td>")
                        .append("<td>").append(String.format(Locale.US, "%,.0f", item.optDouble("price", 0))).append("</td></tr>");
                }
                html.append("</table>");
            }

            // الإجمالي
            html.append("<div class='total-section'>")
                .append("<div style='font-size:11px;opacity:0.8'>").append(isArabic ? "المبلغ النهائي" : "Final Amount").append("</div>")
                .append("<div class='total-amount'>").append(String.format(Locale.US, "%,.0f %s", finalAmount, currency)).append("</div>")
                .append("</div>");

        } catch (Exception e) {
            Log.e(TAG, "HTML error: " + e.getMessage());
            html.append("<p style='color:#EF4444;text-align:center'>").append(isArabic ? "خطأ في بيانات الإيصال" : "Receipt data error").append("</p>");
        }

        // Footer
        html.append("<div class='footer'>")
            .append(orgName).append("<br>")
            .append(isArabic ? "نظام MIRA لإدارة المجوهرات" : "MIRA Jewelry Management System").append("<br>")
            .append("© 2026 MIRA Technology")
            .append("</div>");

        html.append("</body></html>");
        webReceipt.loadDataWithBaseURL(null, html.toString(), "text/html", "UTF-8", null);
    }

    private void closeFragment() {
        if (!isAdded()) return;
        if (getParentFragmentManager() != null && getParentFragmentManager().getBackStackEntryCount() > 0) {
            getParentFragmentManager().popBackStack();
        } else if (getActivity() != null) {
            getActivity().onBackPressed();
        }
    }
}
