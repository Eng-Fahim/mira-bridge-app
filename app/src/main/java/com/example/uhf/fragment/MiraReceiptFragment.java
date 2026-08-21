package com.example.uhf.fragment;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.DialogFragment;

import com.example.uhf.R;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.example.uhf.manager.MiraPriceManager;
import com.example.uhf.manager.MiraVoucherShareManager;
import com.example.uhf.tools.ArabicNumberToWords;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MiraReceiptFragment extends DialogFragment {

    private static final String TAG = "MiraReceiptFragment";
    private static final String ARG_RECEIPT_JSON = "receipt_json";

    private String receiptJsonStr = "{}";
    private boolean isArabic;
    private MiraOrgProfileManager orgProfile;
    private MiraPriceManager priceManager;
    private String orgName = "MIRA Jewelry";
    private String defaultCurrency = "YER";

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
        setStyle(DialogFragment.STYLE_NORMAL, android.R.style.Theme_Material_Light_Dialog_MinWidth);
        
        if (getArguments() != null) {
            receiptJsonStr = getArguments().getString(ARG_RECEIPT_JSON, "{}");
        }
        
        isArabic = Locale.getDefault().getLanguage().equals("ar");
        
        if (getContext() != null) {
            orgProfile = MiraOrgProfileManager.getInstance(getContext());
            priceManager = MiraPriceManager.getInstance(getContext());
            orgName = orgProfile.getDisplayName();
            defaultCurrency = priceManager.getDefaultCurrency();
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
        
        if (webReceipt != null) {
            webReceipt.getSettings().setJavaScriptEnabled(true);
            webReceipt.setWebViewClient(new WebViewClient());
        }
    }

    private void applyLanguage() {
        if (btnPrintReceipt != null) btnPrintReceipt.setText(isArabic ? "🖨️ طباعة" : "🖨️ Print");
        if (btnShareReceipt != null) btnShareReceipt.setText(isArabic ? "📤 مشاركة" : "📤 Share");
        if (btnSaveReceipt != null) btnSaveReceipt.setText(isArabic ? "💾 حفظ" : "💾 Save");
    }

    private void setupListeners() {
        if (btnCloseReceipt != null) btnCloseReceipt.setOnClickListener(v -> closeFragment());
        if (btnPrintReceipt != null) btnPrintReceipt.setOnClickListener(v -> printReceipt());
        if (btnShareReceipt != null) btnShareReceipt.setOnClickListener(v -> shareReceiptAsJson());
        if (btnSaveReceipt != null) btnSaveReceipt.setOnClickListener(v -> saveReceiptAsPDF());
    }

    // ==================== 🖨️ طباعة ====================
    private void printReceipt() {
        if (getContext() == null || webReceipt == null) return;
        
        saveReceiptOffline();
        
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                PrintManager pm = (PrintManager) requireContext().getSystemService(Context.PRINT_SERVICE);
                PrintDocumentAdapter adapter = webReceipt.createPrintDocumentAdapter(orgName);
                pm.print(orgName, adapter, new PrintAttributes.Builder().build());
                Toast.makeText(getContext(), isArabic ? "🖨️ تم فتح الطباعة" : "🖨️ Print opened", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(getContext(), 
                isArabic ? "💾 تم حفظ السند محلياً" : "💾 Receipt saved locally", 
                Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== 📤 مشاركة JSON ====================
    private void shareReceiptAsJson() {
        try {
            JSONObject receiptData = new JSONObject(receiptJsonStr);
            
            // ✅ إضافة بيانات المنظمة
            receiptData.put("org_name", orgName);
            receiptData.put("org_phone", orgProfile.getOrgPhone());
            receiptData.put("org_email", orgProfile.getOrgEmail());
            receiptData.put("tax_number", orgProfile.getTaxNumber());
            
            // ✅ إضافة المبلغ بالكلمات
            double finalAmount = receiptData.optDouble("final_amount", 
                receiptData.optDouble("total_amount", 0));
            String currency = receiptData.optString("currency", defaultCurrency);
            String currencyName = ArabicNumberToWords.getCurrencyArabicName(currency);
            receiptData.put("amount_in_words", 
                ArabicNumberToWords.convert(finalAmount, currencyName));
            
            // ✅ إضافة RFID و DataMatrix للقطع
            JSONArray items = receiptData.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    
                    // RFID EPC
                    String epc = item.optString("rfid_epc", item.optString("epc", ""));
                    if (!epc.isEmpty()) {
                        item.put("rfid_epc", epc);
                    }
                    
                    // GS1 DataMatrix
                    String gtin = item.optString("gtin", item.optString("gtin13", ""));
                    String serial = item.optString("serial", item.optString("serial_number", ""));
                    if (!gtin.isEmpty()) {
                        item.put("gs1_datamatrix", buildGS1DataMatrix(gtin, serial));
                    }
                }
            }
            
            // ✅ مشاركة
            MiraVoucherShareManager.getInstance(requireContext())
                .shareVoucherAsJson(receiptData);
                
        } catch (Exception e) {
            Log.e(TAG, "Error sharing JSON: " + e.getMessage());
            // Fallback للمشاركة كـ PDF
            shareReceiptAsPDF();
        }
    }

    /**
     * ✅ بناء GS1 DataMatrix
     */
    private String buildGS1DataMatrix(String gtin, String serial) {
        StringBuilder sb = new StringBuilder();
        sb.append("01").append(gtin.length() == 14 ? gtin : String.format("%014d", 
            parseLongSafely(gtin)));
        
        if (serial != null && !serial.isEmpty()) {
            sb.append("21").append(serial);
        }
        
        return sb.toString();
    }

    private long parseLongSafely(String value) {
        try {
            return Long.parseLong(value.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    // ==================== 📤 مشاركة PDF ====================
    private void shareReceiptAsPDF() {
        webReceipt.postDelayed(() -> {
            try {
                File pdf = generatePDF();
                if (pdf == null) { shareAsText(); return; }
                Uri uri = FileProvider.getUriForFile(requireContext(), 
                    requireContext().getPackageName() + ".fileprovider", pdf);
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("application/pdf");
                intent.putExtra(Intent.EXTRA_STREAM, uri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(intent, isArabic ? "مشاركة الإيصال" : "Share Receipt"));
            } catch (Exception e) { 
                shareAsText(); 
            }
        }, 500);
    }

    private void shareAsText() {
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, orgName + " - " + receiptJsonStr);
            startActivity(Intent.createChooser(intent, isArabic ? "مشاركة" : "Share"));
        } catch (Exception ignored) {}
    }

    // ==================== 💾 حفظ PDF ====================
    private void saveReceiptAsPDF() {
        try {
            File pdf = generatePDF();
            if (pdf != null) {
                Toast.makeText(getContext(), 
                    (isArabic ? "✅ تم الحفظ: " : "✅ Saved: ") + pdf.getName(), 
                    Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(getContext(), 
                isArabic ? "❌ فشل الحفظ" : "❌ Save failed", 
                Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== 🏗️ generatePDF ====================
    private File generatePDF() {
        try {
            Bitmap bitmap = captureWebView();
            if (bitmap == null) return null;

            PdfDocument doc = new PdfDocument();
            int w = 595;
            int h = bitmap.getHeight() * w / bitmap.getWidth() + 40;
            PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(w, h, 1).create();
            PdfDocument.Page page = doc.startPage(info);
            Canvas canvas = page.getCanvas();
            canvas.drawBitmap(Bitmap.createScaledBitmap(bitmap, w - 40, h - 40, true), 20, 20, null);
            doc.finishPage(page);

            String fn = orgName.replaceAll("\\s+", "_") + "_Receipt_" + 
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".pdf";
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MIRA_Receipts");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, fn);
            FileOutputStream fos = new FileOutputStream(file);
            doc.writeTo(fos);
            doc.close();
            fos.close();
            return file;
        } catch (Exception e) {
            Log.e(TAG, "PDF error: " + e.getMessage());
            return null;
        }
    }

    private Bitmap captureWebView() {
        if (webReceipt == null) return null;
        try {
            webReceipt.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            webReceipt.layout(0, 0, webReceipt.getMeasuredWidth(), webReceipt.getMeasuredHeight());
            Bitmap b = Bitmap.createBitmap(webReceipt.getMeasuredWidth(), 
                webReceipt.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
            webReceipt.draw(new Canvas(b));
            return b;
        } catch (Exception e) { 
            return null; 
        }
    }

    // ==================== 💾 OFFLINE SAVE ====================
    private void saveReceiptOffline() {
        try {
            if (getContext() == null) return;
            
            JSONObject receiptData = new JSONObject(receiptJsonStr);
            receiptData.put("saved_at", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
            receiptData.put("org_name", orgName);
            receiptData.put("printed", false);
            receiptData.put("currency", defaultCurrency);
            
            android.content.SharedPreferences prefs = getContext()
                .getSharedPreferences("offline_receipts", Context.MODE_PRIVATE);
            String existingJson = prefs.getString("receipts", "[]");
            JSONArray receiptsArray = new JSONArray(existingJson);
            receiptsArray.put(receiptData);
            
            while (receiptsArray.length() > 50) {
                receiptsArray.remove(0);
            }
            
            prefs.edit().putString("receipts", receiptsArray.toString()).apply();
            
            Log.i(TAG, "💾 Receipt saved offline: " + receiptData.optString("sale_id", "unknown"));
        } catch (Exception e) {
            Log.e(TAG, "Save receipt offline error: " + e.getMessage());
        }
    }

    // ==================== 📄 HTML RECEIPT ====================
    private void renderHtmlReceipt() {
        if (webReceipt == null) return;
        
        String slogan = orgProfile != null ? orgProfile.getOrgSlogan() : "";
        String phone = orgProfile != null ? orgProfile.getOrgPhone() : "";
        String address = orgProfile != null ? orgProfile.getOrgAddress() : "";
        String taxNumber = orgProfile != null ? orgProfile.getTaxNumber() : "VAT-3100293841";
        String dir = isArabic ? "rtl" : "ltr";

        StringBuilder h = new StringBuilder();
        h.append("<!DOCTYPE html><html dir='").append(dir).append("'><head><meta charset='UTF-8'>")
         .append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>")
         .append("<style>")
         .append("body { font-family: 'Segoe UI', Tahoma, sans-serif; padding: 10px; max-width: 380px; margin: auto; background: #fff; color: #1a1a1a; font-size: 11px; }")
         .append(".header { text-align: center; border-bottom: 2px double #1A237E; padding-bottom: 8px; margin-bottom: 8px; }")
         .append(".org-name { font-size: 18px; font-weight: bold; color: #1A237E; }")
         .append(".org-meta { font-size: 9px; color: #555; margin-top: 2px; }")
         .append(".receipt-title { font-size: 13px; font-weight: bold; text-align: center; background: #f0f2f5; padding: 4px; margin: 6px 0; border-radius: 4px; border: 1px dashed #1A237E; }")
         .append(".info-grid { width: 100%; font-size: 10px; margin-bottom: 8px; }")
         .append(".info-grid td { padding: 2px 0; }")
         .append(".label { color: #666; } .value { font-weight: bold; color: #000; }")
         .append("table.items { width: 100%; border-collapse: collapse; margin: 8px 0; font-size: 10px; }")
         .append("table.items th { background: #1A237E; color: #fff; padding: 4px; text-align: center; border: 1px solid #1A237E; }")
         .append("table.items td { padding: 4px; border: 1px solid #ddd; text-align: center; }")
         .append(".totals-table { width: 100%; font-size: 10px; margin-top: 6px; }")
         .append(".totals-table td { padding: 3px 0; }")
         .append(".grand-total { font-size: 15px; font-weight: bold; color: #1A237E; border-top: 1px solid #1A237E; border-bottom: 2px double #1A237E; padding: 4px 0; }")
         .append(".tafweet { font-size: 9px; font-style: italic; color: #444; text-align: center; margin-top: 4px; background: #fafafa; padding: 4px; border-radius: 4px; }")
         .append(".qr-container { text-align: center; margin-top: 10px; padding-top: 6px; border-top: 1px dashed #ccc; }")
         .append(".qr-container img { width: 110px; height: 110px; }")
         .append(".footer { text-align: center; margin-top: 10px; font-size: 8px; color: #777; }")
         .append("</style></head><body>");

        h.append("<div class='header'>")
         .append("<div class='org-name'>").append(orgName).append("</div>")
         .append("<div class='org-meta'>").append(slogan).append("</div>")
         .append("<div class='org-meta'>").append(address).append(" | ").append(phone).append("</div>")
         .append("<div class='org-meta'><b>الرقم الضريبي: </b>").append(taxNumber).append("</div>")
         .append("</div>");

        try {
            JSONObject t = new JSONObject(receiptJsonStr).optJSONObject("transaction");
            if (t == null) t = new JSONObject(receiptJsonStr);

            String saleId = t.optString("sale_id", "—");
            String saleNumber = t.optString("sale_number", t.optString("invoice_number", "—"));
            String dateStr = t.optString("timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()));
            String customer = t.optString("customer_name", "عميل نقدي");
            String paymentMethod = t.optString("payment_method", "CASH");
            String accountCode = t.optString("account_code", "110101");
            
            String currency = t.optString("currency", defaultCurrency);
            String currencySymbol = getCurrencySymbol(currency);

            h.append("<div class='receipt-title'>سند بيع ضريبي / TAX INVOICE</div>");

            h.append("<table class='info-grid'>")
             .append("<tr><td class='label'>رقم السند:</td><td class='value'>").append(saleNumber).append("</td>")
             .append("<td class='label'>التاريخ:</td><td class='value'>").append(dateStr).append("</td></tr>")
             .append("<tr><td class='label'>العميل:</td><td class='value'>").append(customer).append("</td>")
             .append("<td class='label'>الدفع:</td><td class='value'>").append(paymentMethod).append("</td></tr>")
             .append("<tr><td class='label'>العملة:</td><td class='value' colspan='3'>").append(currency).append("</td></tr>")
             .append("</table>");

            JSONArray items = t.optJSONArray("items");
            if (items != null && items.length() > 0) {
                h.append("<table class='items'><thead><tr>")
                 .append("<th>#</th><th>الصنف</th><th>العيار</th><th>الوزن</th><th>المبلغ</th>")
                 .append("</tr></thead><tbody>");
                
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it == null) continue;
                    double weight = it.optDouble("weight", 0.0);
                    double itemPrice = it.optDouble("price", it.optDouble("sale_price", 0));

                    h.append("<tr>")
                     .append("<td>").append(i + 1).append("</td>")
                     .append("<td style='text-align:right;'>").append(it.optString("title", "—")).append("</td>")
                     .append("<td>").append(it.optString("karat", "—")).append("</td>")
                     .append("<td>").append(String.format(Locale.US, "%.2f", weight)).append("</td>")
                     .append("<td>").append(priceManager.formatPriceDefault(itemPrice)).append("</td>")
                     .append("</tr>");
                }
                h.append("</tbody></table>");
            }

            double subtotal = t.optDouble("subtotal", t.optDouble("total_amount", 0));
            double discount = t.optDouble("discount_amount", 0);
            double finalAmt = t.optDouble("final_amount", subtotal - discount);

            h.append("<table class='totals-table'>")
             .append("<tr><td class='label'>الإجمالي:</td><td class='value' style='text-align:left;'>")
             .append(priceManager.formatPriceDefault(subtotal)).append("</td></tr>");
            
            if (discount > 0) {
                h.append("<tr><td class='label'>الخصم:</td><td class='value' style='text-align:left; color:red;'>- ")
                 .append(priceManager.formatPriceDefault(discount)).append("</td></tr>");
            }

            h.append("<tr class='grand-total'><td class='label' style='color:#1A237E;'>الصافي:</td>")
             .append("<td class='value' style='text-align:left; color:#1A237E;'>")
             .append(priceManager.formatPriceDefault(finalAmt)).append("</td></tr>")
             .append("</table>");

            // ✅ التفقيط الصحيح
            String tafweetText = ArabicNumberToWords.convert(finalAmt, 
                ArabicNumberToWords.getCurrencyArabicName(currency));
            h.append("<div class='tafweet'><b>المبلغ فقط: </b>").append(tafweetText).append("</div>");

            // ✅ QR Code
            String qrData = Uri.encode("Org:" + orgName + "|Receipt:" + saleNumber + "|Total:" + finalAmt + "|Currency:" + currency + "|Tax:" + taxNumber);
            String qrApiUrl = "https://api.qrserver.com/v1/create-qr-code/?size=150x150&data=" + qrData;

            h.append("<div class='qr-container'>")
             .append("<img src='").append(qrApiUrl).append("' alt='QR Verify'/><br>")
             .append("<span style='font-size:8px;color:#888;'>مسح الرمز للتحقق من صحة السند</span>")
             .append("</div>");

        } catch (Exception e) {
            Log.e(TAG, "Error rendering receipt", e);
            h.append("<p style='color:red;text-align:center'>خطأ في معالجة بيانات السند</p>");
        }

        h.append("<div class='footer'>")
         .append("شكرًا لتعاملكم معنا | ").append(orgName).append("<br>")
         .append("نظام MIRA Trust Platform © 2026")
         .append("</div></body></html>");

        webReceipt.loadDataWithBaseURL(null, h.toString(), "text/html", "UTF-8", null);
    }

    private String getCurrencySymbol(String currency) {
        switch (currency.toUpperCase()) {
            case "USD": return "$";
            case "YER": return "YER";
            case "EGP": return "EGP";
            case "SAR": return "SAR";
            case "AED": return "AED";
            default: return currency;
        }
    }

    private void closeFragment() {
        if (!isAdded()) return;
        dismissAllowingStateLoss();
    }
}
