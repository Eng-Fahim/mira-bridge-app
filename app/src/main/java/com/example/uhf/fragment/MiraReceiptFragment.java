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
        if (btnSaveReceipt != null) btnSaveReceipt.setText(isArabic ? "💾 حفظ PDF" : "💾 Save PDF");
    }

    private void setupListeners() {
        if (btnCloseReceipt != null) btnCloseReceipt.setOnClickListener(v -> closeFragment());
        if (btnPrintReceipt != null) btnPrintReceipt.setOnClickListener(v -> printReceipt());
        if (btnShareReceipt != null) btnShareReceipt.setOnClickListener(v -> shareReceiptAsPDF());
        if (btnSaveReceipt != null) btnSaveReceipt.setOnClickListener(v -> saveReceiptAsPDF());
    }

    // ==================== 🖨️ طباعة ====================
    private void printReceipt() {
        if (getContext() == null || webReceipt == null) return;
        
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                PrintManager pm = (PrintManager) requireContext().getSystemService(Context.PRINT_SERVICE);
                PrintDocumentAdapter adapter = webReceipt.createPrintDocumentAdapter(orgName + "_Invoice");
                pm.print(orgName + "_Invoice", adapter, new PrintAttributes.Builder().build());
            }
        } catch (Exception e) {
            Log.e(TAG, "Print error: " + e.getMessage());
            Toast.makeText(getContext(), 
                isArabic ? "❌ فشل الطباعة" : "❌ Print failed", 
                Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== 📤 مشاركة PDF ====================
    private void shareReceiptAsPDF() {
        Toast.makeText(getContext(), 
            isArabic ? "⏳ جاري تجهيز الملف..." : "⏳ Preparing file...", 
            Toast.LENGTH_SHORT).show();
        
        webReceipt.postDelayed(() -> {
            try {
                File pdf = generatePDF();
                if (pdf != null && pdf.exists()) {
                    Uri uri = FileProvider.getUriForFile(requireContext(), 
                        requireContext().getPackageName() + ".fileprovider", pdf);
                    
                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("application/pdf");
                    shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                    shareIntent.putExtra(Intent.EXTRA_SUBJECT, orgName + " - " + (isArabic ? "فاتورة بيع" : "Invoice"));
                    shareIntent.putExtra(Intent.EXTRA_TEXT, isArabic ? "فاتورة بيع من " : "Invoice from " + orgName);
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    
                    startActivity(Intent.createChooser(shareIntent, 
                        isArabic ? "مشاركة الفاتورة" : "Share Invoice"));
                } else {
                    Toast.makeText(getContext(), 
                        isArabic ? "❌ فشل إنشاء الملف" : "❌ File creation failed", 
                        Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Log.e(TAG, "Share error: " + e.getMessage());
                shareAsImage();
            }
        }, 800);
    }

    // ✅ مشاركة كصورة كبديل
    private void shareAsImage() {
        try {
            Bitmap bitmap = captureWebView();
            if (bitmap == null) {
                shareAsText();
                return;
            }
            
            File dir = new File(requireContext().getCacheDir(), "shares");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "invoice_" + System.currentTimeMillis() + ".png");
            FileOutputStream fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
            
            Uri uri = FileProvider.getUriForFile(requireContext(), 
                requireContext().getPackageName() + ".fileprovider", file);
            
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("image/png");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, isArabic ? "مشاركة الفاتورة" : "Share Invoice"));
        } catch (Exception e) {
            shareAsText();
        }
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
        Toast.makeText(getContext(), 
            isArabic ? "⏳ جاري الحفظ..." : "⏳ Saving...", 
            Toast.LENGTH_SHORT).show();
        
        webReceipt.postDelayed(() -> {
            try {
                File pdf = generatePDF();
                if (pdf != null) {
                    Toast.makeText(getContext(), 
                        (isArabic ? "✅ تم الحفظ: " : "✅ Saved: ") + pdf.getAbsolutePath(), 
                        Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(getContext(), 
                        isArabic ? "❌ فشل الحفظ" : "❌ Save failed", 
                        Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(getContext(), 
                    isArabic ? "❌ فشل الحفظ" : "❌ Save failed", 
                    Toast.LENGTH_SHORT).show();
            }
        }, 800);
    }

    // ==================== 🏗️ generatePDF ====================
    private File generatePDF() {
        try {
            Bitmap bitmap = captureWebView();
            if (bitmap == null) return null;

            PdfDocument doc = new PdfDocument();
            int pageWidth = 595; // A4 width in points
            int pageHeight = Math.max(842, bitmap.getHeight() * pageWidth / bitmap.getWidth() + 60);
            
            PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create();
            PdfDocument.Page page = doc.startPage(info);
            Canvas canvas = page.getCanvas();
            
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, pageWidth - 40, pageHeight - 60, true);
            canvas.drawBitmap(scaled, 20, 20, null);
            doc.finishPage(page);

            String fileName = orgName.replaceAll("\\s+", "_") + "_Invoice_" + 
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".pdf";
            
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MIRA_Invoices");
            if (!dir.exists()) dir.mkdirs();
            
            File file = new File(dir, fileName);
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
            webReceipt.measure(
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            );
            webReceipt.layout(0, 0, webReceipt.getMeasuredWidth(), webReceipt.getMeasuredHeight());
            
            Bitmap bitmap = Bitmap.createBitmap(
                webReceipt.getMeasuredWidth(), 
                webReceipt.getMeasuredHeight(), 
                Bitmap.Config.ARGB_8888
            );
            
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(android.graphics.Color.WHITE);
            webReceipt.draw(canvas);
            return bitmap;
        } catch (Exception e) { 
            Log.e(TAG, "Capture error: " + e.getMessage());
            return null; 
        }
    }

    // ==================== 📄 HTML RECEIPT ====================
    private void renderHtmlReceipt() {
    if (webReceipt == null) return;
    
    String phone = orgProfile != null ? orgProfile.getOrgPhone() : "";
    String address = orgProfile != null ? orgProfile.getOrgAddress() : "";
    String dir = isArabic ? "rtl" : "ltr";

    StringBuilder h = new StringBuilder();
    h.append("<!DOCTYPE html><html dir='").append(dir).append("'><head><meta charset='UTF-8'>")
     .append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>")
     .append("<style>")
     .append("body { font-family: 'Segoe UI', Tahoma, sans-serif; padding: 15px; max-width: 420px; margin: auto; background: #fff; color: #1a1a1a; font-size: 11px; }")
     .append(".header { text-align: center; border-bottom: 3px double #1A237E; padding-bottom: 12px; margin-bottom: 12px; }")
     .append(".org-name { font-size: 22px; font-weight: bold; color: #1A237E; }")
     .append(".org-meta { font-size: 10px; color: #555; margin-top: 3px; }")
     .append(".invoice-title { font-size: 16px; font-weight: bold; text-align: center; background: #1A237E; color: #fff; padding: 8px; margin: 10px 0; border-radius: 4px; }")
     .append(".info-grid { width: 100%; font-size: 10px; margin-bottom: 10px; border: 1px solid #e0e0e0; border-radius: 4px; padding: 8px; background: #FAFAFA; }")
     .append(".info-grid td { padding: 3px 0; }")
     .append(".label { color: #666; font-size: 9px; } .value { font-weight: bold; color: #000; font-size: 10px; }")
     .append("table.items { width: 100%; border-collapse: collapse; margin: 10px 0; font-size: 10px; }")
     .append("table.items th { background: #1A237E; color: #fff; padding: 6px; text-align: center; border: 1px solid #1A237E; font-size: 9px; }")
     .append("table.items td { padding: 6px; border: 1px solid #ddd; text-align: center; }")
     .append(".serial-box { font-size: 8px; background: #E8EAF6; padding: 4px; margin-top: 3px; border-radius: 3px; word-break: break-all; text-align: left; direction: ltr; }")
     .append(".totals-table { width: 100%; font-size: 11px; margin-top: 8px; }")
     .append(".totals-table td { padding: 4px 0; }")
     .append(".grand-total { font-size: 18px; font-weight: bold; color: #1A237E; border-top: 2px solid #1A237E; border-bottom: 3px double #1A237E; padding: 8px 0; background: #F5F5F5; }")
     .append(".warranty-box { font-size: 10px; font-weight: bold; text-align: center; margin: 10px 0; padding: 8px; border-radius: 4px; border: 1px dashed #4CAF50; background: #E8F5E9; color: #2E7D32; }")
     .append(".qr-container { text-align: center; margin-top: 12px; padding-top: 8px; border-top: 2px dashed #1A237E; }")
     .append(".qr-container img { width: 120px; height: 120px; }")
     .append(".footer { text-align: center; margin-top: 12px; font-size: 9px; color: #777; border-top: 1px solid #eee; padding-top: 8px; }")
     .append(".signature-line { display: flex; justify-content: space-between; margin-top: 25px; font-size: 9px; }")
     .append(".signature-box { text-align: center; width: 45%; }")
     .append(".signature-line-box { border-top: 1px solid #333; margin-top: 30px; padding-top: 3px; }")
     .append("</style></head><body>");

    h.append("<div class='header'>")
     .append("<div class='org-name'>").append(orgName).append("</div>")
     .append("<div class='org-meta'>").append(address).append(" | ").append(phone).append("</div>")
     .append("</div>");

    try {
        JSONObject root = new JSONObject(receiptJsonStr);
        
        // ✅✅✅ البحث عن transaction في أماكن متعددة
        JSONObject t = root.optJSONObject("transaction");
        if (t == null) t = root.optJSONObject("data");
        if (t == null) t = root;
        
        // ✅ قراءة جميع الحقول مع fallback
        String saleNumber = t.optString("sale_number", 
            t.optString("invoice_number", 
            t.optString("reference", "—")));
        
        String dateStr = t.optString("timestamp", 
            t.optString("created_at", 
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date())));
        
        String customer = t.optString("customer_name", 
            t.optString("customer", 
            isArabic ? "عميل نقدي" : "Cash Customer"));
        
        String customerPhone = t.optString("customer_phone", 
            t.optString("phone", ""));
        
        String paymentMethod = t.optString("payment_method", 
            t.optString("payment", "CASH"));
        
        String branchName = t.optString("branch_name", 
            t.optString("branch", 
            isArabic ? "الفرع الرئيسي" : "Main Branch"));
        
        String sellerName = t.optString("seller_name", 
            t.optString("username", 
            t.optString("user_name", "—")));
        
        String currency = t.optString("currency", defaultCurrency);
        double exchangeRate = t.optDouble("exchange_rate", 1.0);

        // ✅ عنوان الفاتورة
        h.append("<div class='invoice-title'>")
         .append(isArabic ? "🧾 فاتورة بيع" : "🧾 INVOICE")
         .append("</div>");

        // Info Grid
        h.append("<table class='info-grid'>")
         .append("<tr><td class='label'>").append(isArabic ? "رقم الفاتورة:" : "Invoice #:").append("</td><td class='value'>").append(saleNumber).append("</td>")
         .append("<td class='label'>").append(isArabic ? "التاريخ:" : "Date:").append("</td><td class='value'>").append(dateStr).append("</td></tr>")
         .append("<tr><td class='label'>").append(isArabic ? "العميل:" : "Customer:").append("</td><td class='value'>").append(customer).append("</td>")
         .append("<td class='label'>").append(isArabic ? "الهاتف:" : "Phone:").append("</td><td class='value'>").append(customerPhone.isEmpty() ? "—" : customerPhone).append("</td></tr>")
         .append("<tr><td class='label'>").append(isArabic ? "البائع:" : "Seller:").append("</td><td class='value'>").append(sellerName).append("</td>")
         .append("<td class='label'>").append(isArabic ? "الفرع:" : "Branch:").append("</td><td class='value'>").append(branchName).append("</td></tr>")
         .append("<tr><td class='label'>").append(isArabic ? "طريقة الدفع:" : "Payment:").append("</td><td class='value' colspan='3'>").append(paymentMethod).append("</td></tr>")
         .append("</table>");

        // ✅ Items - البحث في أماكن متعددة
        JSONArray items = t.optJSONArray("items");
        if (items == null) items = t.optJSONArray("sold_items");
        if (items == null) items = t.optJSONArray("products");
        
        if (items != null && items.length() > 0) {
            h.append("<table class='items'><thead><tr>")
             .append("<th>#</th>")
             .append("<th>").append(isArabic ? "الصنف" : "Item").append("</th>")
             .append("<th>").append(isArabic ? "الرقم التسلسلي" : "Serial #").append("</th>")
             .append("<th>").append(isArabic ? "العيار" : "Karat").append("</th>")
             .append("<th>").append(isArabic ? "الوزن" : "Weight").append("</th>")
             .append("<th>").append(isArabic ? "السعر" : "Price").append("</th>")
             .append("</tr></thead><tbody>");
            
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                
                double weight = it.optDouble("weight", 
                    it.optDouble("gross_weight", 0.0));
                
                double itemPriceUSD = it.optDouble("price_usd", 
                    it.optDouble("sale_price", 
                    it.optDouble("price", 
                    it.optDouble("final_price", 0))));
                
                String serial = it.optString("serial_number", 
                    it.optString("serial", 
                    it.optString("epc", 
                    it.optString("code", ""))));

                String title = it.optString("title", 
                    it.optString("name", 
                    it.optString("item_title", "—")));
                
                String karat = it.optString("karat", 
                    it.optString("gold_karat", "—"));

                h.append("<tr>")
                 .append("<td>").append(i + 1).append("</td>")
                 .append("<td style='text-align:right;'><b>").append(title).append("</b></td>")
                 .append("<td><div class='serial-box'>").append(serial).append("</div></td>")
                 .append("<td>").append(karat).append("</td>")
                 .append("<td>").append(String.format(Locale.US, "%.2f g", weight)).append("</td>")
                 .append("<td><b>").append(priceManager != null ? priceManager.formatPriceDefault(itemPriceUSD) : String.valueOf(itemPriceUSD)).append("</b></td>")
                 .append("</tr>");
            }
            h.append("</tbody></table>");
        }

        // ✅ Totals - مع fallback شامل
        double subtotalUSD = t.optDouble("subtotal_usd", 
            t.optDouble("subtotal", 0));
        
        double discountUSD = t.optDouble("discount_amount_usd", 
            t.optDouble("discount_amount", 
            t.optDouble("discount", 0)));
        
        double finalUSD = t.optDouble("final_amount_usd", 
            t.optDouble("final_amount", 
            t.optDouble("total", subtotalUSD - discountUSD)));
        
        double finalLocal = t.optDouble("final_amount_local", 
            t.optDouble("final_amount", finalUSD * exchangeRate));

        h.append("<table class='totals-table'>")
         .append("<tr><td class='label'>").append(isArabic ? "الإجمالي الفرعي:" : "Subtotal:").append("</td>")
         .append("<td class='value' style='text-align:left;'>")
         .append(priceManager != null ? priceManager.formatPriceDefault(subtotalUSD) : String.valueOf(subtotalUSD))
         .append("</td></tr>");
        
        if (discountUSD > 0) {
            h.append("<tr><td class='label'>").append(isArabic ? "الخصم:" : "Discount:").append("</td>")
             .append("<td class='value' style='text-align:left; color:red;'>- ")
             .append(priceManager != null ? priceManager.formatPriceDefault(discountUSD) : String.valueOf(discountUSD))
             .append("</td></tr>");
        }

        h.append("<tr class='grand-total'><td class='label' style='color:#1A237E; font-size:12px;'>")
         .append(isArabic ? "💰 الصافي الإجمالي:" : "💰 Net Total:")
         .append("</td>")
         .append("<td class='value' style='text-align:left; color:#1A237E; font-size:14px;'>")
         .append(priceManager != null ? priceManager.formatPriceDefault(finalUSD) : String.valueOf(finalUSD))
         .append("</td></tr>")
         .append("</table>");

        // ✅ المبلغ كتابةً
        if (finalLocal > 0) {
            try {
                String tafweetText = ArabicNumberToWords.convert(finalLocal, 
                    ArabicNumberToWords.getCurrencyArabicName(currency));
                h.append("<div style='font-size:10px;font-style:italic;color:#444;text-align:center;margin-top:6px;padding:6px;background:#fafafa;border-radius:4px;border:1px dashed #ccc;'>")
                 .append("<b>").append(isArabic ? "المبلغ كتابةً: " : "Amount in words: ").append("</b>")
                 .append(tafweetText)
                 .append("</div>");
            } catch (Exception e) {
                Log.e(TAG, "Tafweet error: " + e.getMessage());
            }
        }

        // ✅ الضمان
        h.append("<div class='warranty-box'>")
         .append(isArabic ? "🔒 ضمان لمدة سنة واحدة من تاريخ الشراء ضد عيوب التصنيع" : "🔒 1 Year Warranty against manufacturing defects")
         .append("</div>");

        // ✅ QR Code
        String qrData = Uri.encode("Org:" + orgName + "|Invoice:" + saleNumber + "|Total:" + finalUSD + "|Date:" + dateStr);
        String qrApiUrl = "https://api.qrserver.com/v1/create-qr-code/?size=150x150&data=" + qrData;
        
        h.append("<div class='qr-container'>")
         .append("<img src='").append(qrApiUrl).append("' alt='QR Verify'/><br>")
         .append("<span style='font-size:8px;color:#888;'>")
         .append(isArabic ? "📱 مسح للتحقق" : "📱 Scan to verify")
         .append("</span>")
         .append("</div>");

        // ✅ التوقيعات
        h.append("<div class='signature-line'>")
         .append("<div class='signature-box'><div class='signature-line-box'>")
         .append(isArabic ? "توقيع البائع" : "Seller Signature")
         .append("</div></div>")
         .append("<div class='signature-box'><div class='signature-line-box'>")
         .append(isArabic ? "توقيع العميل" : "Customer Signature")
         .append("</div></div>")
         .append("</div>");

    } catch (Exception e) {
        Log.e(TAG, "Error rendering receipt: " + e.getMessage(), e);
        // ✅ عرض البيانات الخام إذا فشل التحليل
        h.append("<p style='color:red;text-align:center'>")
         .append(isArabic ? "خطأ في معالجة البيانات" : "Error processing data")
         .append("</p>")
         .append("<pre style='font-size:8px;word-wrap:break-word;white-space:pre-wrap;'>")
         .append(receiptJsonStr)
         .append("</pre>");
    }

    // Footer
    h.append("<div class='footer'>")
     .append(isArabic ? "شكرًا لتعاملكم معنا" : "Thank you").append("<br>")
     .append(orgName).append(" © 2026")
     .append("</div></body></html>");

    webReceipt.loadDataWithBaseURL(null, h.toString(), "text/html", "UTF-8", null);
}

    private void closeFragment() {
        if (!isAdded()) return;
        dismissAllowingStateLoss();
    }
}
