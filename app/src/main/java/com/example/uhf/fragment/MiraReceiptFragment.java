package com.example.uhf.fragment;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
import com.example.uhf.manager.MiraOrgProfileManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MiraReceiptFragment extends Fragment {

    private static final String ARG_TRANSACTION = "transaction_data";

    private JSONObject transactionData;
    private WebView webView;
    private Button btnPrint, btnShare, btnSave, btnClose;
    private MiraOrgProfileManager orgProfile;

    public static MiraReceiptFragment newInstance(String transactionJson) {
        MiraReceiptFragment fragment = new MiraReceiptFragment();
        Bundle args = new Bundle();
        args.putString(ARG_TRANSACTION, transactionJson);
        fragment.setArguments(args);
        return fragment;
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

        Context context = getContext();
        if (context == null) return;

        // 🏢 تهيئة مدير بيانات الجهة
        orgProfile = MiraOrgProfileManager.getInstance(context);

        webView = view.findViewById(R.id.webMiraReceipt);
        btnPrint = view.findViewById(R.id.btnMiraPrintReceipt);
        btnShare = view.findViewById(R.id.btnMiraShareReceipt);
        btnSave = view.findViewById(R.id.btnMiraSaveReceipt);
        btnClose = view.findViewById(R.id.btnMiraCloseReceipt);

        if (btnPrint != null) btnPrint.setOnClickListener(v -> printReceipt());
        if (btnShare != null) btnShare.setOnClickListener(v -> shareReceipt());
        if (btnSave != null) btnSave.setOnClickListener(v -> saveReceiptAsPDF());
        if (btnClose != null) btnClose.setOnClickListener(v -> {
            if (getParentFragmentManager() != null) {
                getParentFragmentManager().popBackStack();
            }
        });

        if (getArguments() != null) {
            String json = getArguments().getString(ARG_TRANSACTION);
            if (json != null && !json.isEmpty()) {
                try {
                    transactionData = new JSONObject(json);
                    loadReceiptHTML();
                } catch (Exception e) {
                    showToast("خطأ في بيانات الإيصال");
                }
            }
        }
    }

    private void loadReceiptHTML() {
        if (webView == null || transactionData == null) return;
        try {
            JSONObject transaction = transactionData.optJSONObject("transaction");
            if (transaction == null) transaction = transactionData;

            String saleId = transaction.optString("sale_id", "—");
            String customerName = transaction.optString("customer_name", "عميل");
            String customerPhone = transaction.optString("customer_phone", "");
            String paymentMethod = transaction.optString("payment_method", "نقدي");
            String timestamp = transaction.optString("timestamp",
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
            double subtotal = transaction.optDouble("subtotal", 0);
            double discount = transaction.optDouble("discount_percent", 0);
            double finalAmount = transaction.optDouble("final_amount", subtotal);
            int itemCount = transaction.optInt("total_items", transaction.optInt("item_count", 0));

            JSONArray items = transaction.optJSONArray("items");
            StringBuilder itemsHTML = new StringBuilder();
            int count = 1;
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item != null) {
                        itemsHTML.append(String.format(Locale.US,
                            "<tr><td>%d</td><td>%s</td><td>%s</td><td>%.2fg</td><td>$%,.2f</td></tr>",
                            count++, item.optString("title", "—"), item.optString("karat", "—"),
                            item.optDouble("weight", 0), item.optDouble("price", 0)));
                    }
                }
            }

            String html = buildHTML(saleId, customerName, customerPhone, paymentMethod,
                    timestamp, subtotal, discount, finalAmount, itemCount, itemsHTML.toString());

            webView.getSettings().setJavaScriptEnabled(true);
            webView.setWebViewClient(new WebViewClient());
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);

        } catch (Exception e) {
            showToast("خطأ في تحميل الإيصال");
        }
    }

    private String buildHTML(String saleId, String customerName, String customerPhone,
                              String paymentMethod, String timestamp, double subtotal,
                              double discount, double finalAmount, int itemCount, String itemsHTML) {
        
        // 🏢 جلب بيانات الجهة
        String orgName = orgProfile.getOrgName();
        String orgSlogan = orgProfile.getOrgSlogan();
        String orgPhone = orgProfile.getOrgPhone();
        String orgEmail = orgProfile.getOrgEmail();
        String orgAddress = orgProfile.getOrgAddress();
        String orgWebsite = orgProfile.getOrgWebsite();

        // بناء سطر التواصل
        StringBuilder contactLine = new StringBuilder();
        if (!orgPhone.isEmpty()) contactLine.append("📞 ").append(orgPhone).append(" &nbsp; ");
        if (!orgEmail.isEmpty()) contactLine.append("📧 ").append(orgEmail);
        if (contactLine.length() == 0) contactLine.append("MIRA Enterprise Platform");

        // ترجمة طريقة الدفع
        String paymentMethodAr;
        switch (paymentMethod.toLowerCase()) {
            case "cash": paymentMethodAr = "نقداً"; break;
            case "card": paymentMethodAr = "بطاقة"; break;
            case "transfer": paymentMethodAr = "تحويل بنكي"; break;
            default: paymentMethodAr = paymentMethod;
        }

        return "<!DOCTYPE html><html dir='rtl'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
            "<style>" +
            "@import url('https://fonts.googleapis.com/css2?family=Cairo:wght@400;600;700;800&display=swap');" +
            "*{margin:0;padding:0;box-sizing:border-box}" +
            "body{font-family:'Cairo',sans-serif;background:#f8fafc;padding:20px;direction:rtl;color:#1e293b}" +
            ".receipt{max-width:400px;margin:0 auto;background:#fff;border-radius:16px;overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,.08)}" +
            ".header{background:linear-gradient(135deg,#1A237E,#283593);color:#fff;padding:24px 20px;text-align:center}" +
            ".header .org-name{font-size:22px;font-weight:800;letter-spacing:1px;margin-bottom:2px}" +
            ".header .org-slogan{font-size:10px;opacity:.85;margin-bottom:4px}" +
            ".header .contact{font-size:9px;opacity:.7;margin-bottom:12px}" +
            ".header .checkmark{width:64px;height:64px;background:#4ADE80;border-radius:50%;display:flex;align-items:center;justify-content:center;margin:12px auto;font-size:32px;color:#fff}" +
            ".info{padding:20px;border-bottom:1px solid #e2e8f0}" +
            ".info .row{display:flex;justify-content:space-between;margin-bottom:8px;font-size:13px}" +
            ".info .label{color:#64748b}.info .value{font-weight:600}" +
            ".items{padding:16px 20px}.items h3{font-size:14px;color:#1A237E;margin-bottom:12px;font-weight:700}" +
            "table{width:100%;border-collapse:collapse;font-size:11px}" +
            "th{background:#f1f5f9;color:#475569;padding:8px 6px;text-align:center;font-weight:600}" +
            "td{padding:8px 6px;border-bottom:1px solid #f1f5f9;text-align:center}" +
            ".totals{padding:16px 20px;background:#f8fafc;border-top:2px solid #e2e8f0}" +
            ".totals .row{display:flex;justify-content:space-between;margin-bottom:6px;font-size:13px}" +
            ".totals .total{font-size:20px;font-weight:800;color:#1A237E;margin-top:8px;padding-top:8px;border-top:1px solid #e2e8f0}" +
            ".footer{text-align:center;padding:16px 20px;font-size:10px;color:#94a3b8;border-top:1px solid #f1f5f9;line-height:1.8}" +
            ".footer .org-footer{color:#1A237E;font-weight:700;font-size:12px}" +
            "@media print{body{background:#fff;padding:0}.receipt{box-shadow:none;max-width:100%;border-radius:0}}" +
            "</style></head><body><div class='receipt'>" +
            
            // 🏢 Header - اسم وشعار الجهة
            "<div class='header'>" +
            "<div class='org-name'>" + orgName + "</div>" +
            "<div class='org-slogan'>" + orgSlogan + "</div>" +
            "<div class='contact'>" + contactLine.toString() + "</div>" +
            "<div class='checkmark'>✓</div>" +
            "<div style='font-size:18px;font-weight:700'>تم البيع بنجاح!</div></div>" +
            
            // معلومات العملية
            "<div class='info'>" +
            "<div class='row'><span class='label'>رقم العملية</span><span class='value'>#" + saleId + "</span></div>" +
            "<div class='row'><span class='label'>التاريخ</span><span class='value'>" + timestamp + "</span></div>" +
            "<div class='row'><span class='label'>العميل</span><span class='value'>" + customerName + "</span></div>" +
            (customerPhone.isEmpty() ? "" : "<div class='row'><span class='label'>الهاتف</span><span class='value'>" + customerPhone + "</span></div>") +
            "<div class='row'><span class='label'>طريقة الدفع</span><span class='value'>" + paymentMethodAr + "</span></div>" +
            "<div class='row'><span class='label'>عدد القطع</span><span class='value'>" + itemCount + "</span></div>" +
            "</div>" +
            
            // القطع المباعة
            "<div class='items'><h3>📋 القطع المباعة</h3>" +
            "<table><tr><th>#</th><th>القطعة</th><th>العيار</th><th>الوزن</th><th>السعر</th></tr>" +
            itemsHTML + "</table></div>" +
            
            // المبالغ
            "<div class='totals'>" +
            "<div class='row'><span>المجموع الفرعي</span><span>$" + String.format(Locale.US, "%,.2f", subtotal) + "</span></div>" +
            (discount > 0 ? "<div class='row'><span>الخصم (" + discount + "%)</span><span style='color:#EF4444'>-$" + String.format(Locale.US, "%,.2f", subtotal * discount / 100) + "</span></div>" : "") +
            "<div class='row total'><span>المبلغ النهائي</span><span>$" + String.format(Locale.US, "%,.2f", finalAmount) + "</span></div>" +
            "</div>" +
            
            // Footer - توقيع الجهة
            "<div class='footer'>" +
            "<div class='org-footer'>" + orgName + "</div>" +
            (!orgAddress.isEmpty() ? orgAddress + "<br>" : "") +
            (!orgWebsite.isEmpty() ? orgWebsite + "<br>" : "") +
            "MIRA Trust Architecture™ © 2026" +
            "</div>" +
            
            "</div></body></html>";
    }

    private void printReceipt() {
        if (getContext() == null || webView == null) return;
        PrintManager pm = (PrintManager) requireContext().getSystemService(Context.PRINT_SERVICE);
        if (pm != null) {
            PrintDocumentAdapter adapter = webView.createPrintDocumentAdapter("MIRA_Receipt_" + orgProfile.getOrgName());
            pm.print("MIRA Receipt - " + orgProfile.getOrgName(), adapter, new PrintAttributes.Builder().build());
        }
    }

    private void shareReceipt() {
        try {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "إيصال بيع - " + orgProfile.getOrgName());
            shareIntent.putExtra(Intent.EXTRA_TEXT, 
                orgProfile.getOrgName() + " - إيصال بيع\n" + 
                orgProfile.getOrgSlogan() + "\n\n" +
                (transactionData != null ? transactionData.toString() : ""));
            startActivity(Intent.createChooser(shareIntent, "مشاركة الإيصال عبر"));
        } catch (Exception e) {
            showToast("خطأ في المشاركة");
        }
    }

    private void saveReceiptAsPDF() {
        showToast("📄 جاري حفظ الإيصال...");
        try {
            PdfDocument document = new PdfDocument();
            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(595, 842, 1).create();
            PdfDocument.Page page = document.startPage(pageInfo);
            Canvas canvas = page.getCanvas();
            Paint paint = new Paint();
            paint.setColor(Color.BLACK);
            paint.setTextSize(14);
            paint.setFakeBoldText(true);
            canvas.drawText(orgProfile.getOrgName(), 40, 40, paint);
            paint.setTextSize(10);
            paint.setFakeBoldText(false);
            canvas.drawText(orgProfile.getOrgSlogan(), 40, 58, paint);
            canvas.drawText("إيصال بيع - " + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date()), 40, 76, paint);
            document.finishPage(page);

            String fileName = "MIRA_Receipt_" + System.currentTimeMillis() + ".pdf";
            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File file = new File(downloadsDir, fileName);
            FileOutputStream fos = new FileOutputStream(file);
            document.writeTo(fos);
            document.close();
            fos.close();

            showToast("✅ تم الحفظ: " + fileName);
        } catch (Exception e) {
            showToast("❌ فشل الحفظ: " + e.getMessage());
        }
    }

    private void showToast(String message) {
        if (getContext() != null && isAdded()) {
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
        }
    }
}
