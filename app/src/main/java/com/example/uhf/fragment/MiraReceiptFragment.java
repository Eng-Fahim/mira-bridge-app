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
import com.example.uhf.manager.MiraBranchesManager;
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

    private void printReceipt() {
        if (getContext() == null || webReceipt == null) return;
        try {
            PrintManager pm = (PrintManager) requireContext().getSystemService(Context.PRINT_SERVICE);
            PrintDocumentAdapter adapter = webReceipt.createPrintDocumentAdapter(orgName + "_Invoice");
            pm.print(orgName + "_Invoice", adapter, new PrintAttributes.Builder().build());
        } catch (Exception e) {
            Log.e(TAG, "Print error: " + e.getMessage());
            Toast.makeText(getContext(), isArabic ? "❌ فشل الطباعة" : "❌ Print failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareReceiptAsPDF() {
        Toast.makeText(getContext(), isArabic ? "⏳ جاري تجهيز الملف..." : "⏳ Preparing file...", Toast.LENGTH_SHORT).show();
        webReceipt.postDelayed(() -> {
            try {
                File pdf = generatePDF();
                if (pdf != null && pdf.exists()) {
                    Uri uri = FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".fileprovider", pdf);
                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("application/pdf");
                    shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                    shareIntent.putExtra(Intent.EXTRA_SUBJECT, orgName + " - " + (isArabic ? "فاتورة مجوهرات" : "Jewelry Invoice"));
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(shareIntent, isArabic ? "مشاركة الفاتورة" : "Share Invoice"));
                }
            } catch (Exception e) {
                Log.e(TAG, "Share error: " + e.getMessage());
                shareAsImage();
            }
        }, 800);
    }

    private void shareAsImage() {
        try {
            Bitmap bitmap = captureWebView();
            if (bitmap == null) return;
            File dir = new File(requireContext().getCacheDir(), "shares");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "invoice_" + System.currentTimeMillis() + ".png");
            FileOutputStream fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
            
            Uri uri = FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".fileprovider", file);
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("image/png");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, isArabic ? "مشاركة الفاتورة" : "Share Invoice"));
        } catch (Exception ignored) {}
    }

    private void saveReceiptAsPDF() {
        Toast.makeText(getContext(), isArabic ? "⏳ جاري الحفظ..." : "⏳ Saving...", Toast.LENGTH_SHORT).show();
        webReceipt.postDelayed(() -> {
            try {
                File pdf = generatePDF();
                if (pdf != null) {
                    Toast.makeText(getContext(), (isArabic ? "✅ تم الحفظ في المستندات: " : "✅ Saved to Downloads: ") + pdf.getName(), Toast.LENGTH_LONG).show();
                }
            } catch (Exception e) {
                Toast.makeText(getContext(), isArabic ? "❌ فشل الحفظ" : "❌ Save failed", Toast.LENGTH_SHORT).show();
            }
        }, 800);
    }

    private File generatePDF() {
        try {
            Bitmap bitmap = captureWebView();
            if (bitmap == null) return null;

            PdfDocument doc = new PdfDocument();
            int pageWidth = 595; // A4 Standard Points Width
            int pageHeight = Math.max(842, bitmap.getHeight() * pageWidth / bitmap.getWidth() + 40);
            
            PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create();
            PdfDocument.Page page = doc.startPage(info);
            Canvas canvas = page.getCanvas();
            
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, pageWidth - 40, pageHeight - 40, true);
            canvas.drawBitmap(scaled, 20, 20, null);
            doc.finishPage(page);

            String fileName = orgName.replaceAll("\\s+", "_") + "_Invoice_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".pdf";
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
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            );
            webReceipt.layout(0, 0, webReceipt.getMeasuredWidth(), webReceipt.getMeasuredHeight());
            
            Bitmap bitmap = Bitmap.createBitmap(webReceipt.getMeasuredWidth(), webReceipt.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(android.graphics.Color.WHITE);
            webReceipt.draw(canvas);
            return bitmap;
        } catch (Exception e) { 
            Log.e(TAG, "Capture error: " + e.getMessage());
            return null; 
        }
    }

    private void renderHtmlReceipt() {
        if (webReceipt == null) return;
        
        String phone = orgProfile != null ? orgProfile.getOrgPhone() : "";
        String address = orgProfile != null ? orgProfile.getOrgAddress() : "";
        String dir = isArabic ? "rtl" : "ltr";

        StringBuilder h = new StringBuilder();
        h.append("<!DOCTYPE html><html dir='").append(dir).append("'><head><meta charset='UTF-8'>")
         .append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>")
         .append("<style>")
         .append("@page { size: A4 portrait; margin: 10mm; }")
         .append("body { font-family: 'Segoe UI', Arial, sans-serif; padding: 20px; max-width: 800px; margin: auto; background: #fff; color: #1a1a1a; font-size: 12px; }")
         
         // Header Styling (Luxury Gold Theme)
         .append(".header { display: flex; justify-content: space-between; align-items: center; border-bottom: 3px double #0d1b2a; padding-bottom: 15px; margin-bottom: 15px; }")
         .append(".brand-box { text-align: right; }")
         .append(".org-name { font-size: 26px; font-weight: 800; color: #0d1b2a; letter-spacing: 0.5px; }")
         .append(".org-subtitle { font-size: 11px; color: #c59b27; font-weight: bold; text-transform: uppercase; }")
         .append(".org-meta { font-size: 11px; color: #555; margin-top: 4px; }")
         
         // Invoice Badge
         .append(".invoice-title-bar { display: flex; justify-content: space-between; align-items: center; background: linear-gradient(135deg, #0d1b2a 0%, #1b263b 100%); color: #fff; padding: 10px 18px; border-radius: 6px; margin: 15px 0; }")
         .append(".invoice-title { font-size: 18px; font-weight: bold; color: #f4d58d; }")
         .append(".invoice-type { font-size: 12px; background: rgba(255,255,255,0.15); padding: 4px 12px; border-radius: 20px; border: 1px solid #c59b27; }")

         // Grid Info Box
         .append(".info-grid { width: 100%; font-size: 11px; margin-bottom: 15px; border: 1px solid #e0e0e0; border-radius: 6px; padding: 12px; background: #fafafa; border-right: 4px solid #c59b27; }")
         .append(".info-grid table { width: 100%; border-collapse: collapse; }")
         .append(".info-grid td { padding: 5px 8px; vertical-align: top; }")
         .append(".label { color: #666; font-size: 11px; } .value { font-weight: bold; color: #0d1b2a; font-size: 11px; }")
         
         // Items Table
         .append("table.items { width: 100%; border-collapse: collapse; margin: 15px 0; font-size: 11px; }")
         .append("table.items th { background: #0d1b2a; color: #f4d58d; padding: 10px 6px; text-align: center; border: 1px solid #0d1b2a; font-size: 11px; font-weight: 600; }")
         .append("table.items td { padding: 8px 6px; border: 1px solid #e2e8f0; text-align: center; color: #2d3748; }")
         .append("table.items tr:nth-child(even) { background-color: #f8fafc; }")
         .append(".serial-box { font-family: monospace; font-size: 10px; background: #edf2f7; padding: 3px 6px; border-radius: 4px; color: #2b6cb0; word-break: break-all; }")
         .append(".karat-badge { display: inline-block; padding: 2px 8px; border-radius: 12px; font-weight: bold; background: #fefcbf; color: #744210; border: 1px solid #f6e05e; }")

         // Totals Layout
         .append(".summary-container { display: flex; justify-content: space-between; margin-top: 15px; }")
         .append(".notes-box { width: 55%; font-size: 10px; border: 1px dashed #cbd5e0; padding: 10px; border-radius: 6px; background: #f7fafc; }")
         .append(".totals-box { width: 40%; }")
         .append(".totals-table { width: 100%; font-size: 12px; border-collapse: collapse; }")
         .append(".totals-table td { padding: 6px 0; }")
         .append(".grand-total { font-size: 16px; font-weight: bold; color: #0d1b2a; border-top: 2px solid #0d1b2a; border-bottom: 3px double #0d1b2a; padding: 10px 0; background: #fffdf5; }")
         
         // Warranty & Legal
         .append(".tafqeet-box { font-size: 11px; color: #1a202c; text-align: center; margin-top: 12px; padding: 8px; background: #f7fafc; border-radius: 6px; border: 1px solid #e2e8f0; }")
         .append(".warranty-box { font-size: 11px; font-weight: bold; text-align: center; margin: 15px 0; padding: 10px; border-radius: 6px; border: 1px dashed #38a169; background: #f0fff4; color: #22543d; }")
         
         // Footer & Signatures
         .append(".footer-section { margin-top: 30px; display: flex; justify-content: space-between; align-items: flex-end; }")
         .append(".qr-box { text-align: center; }")
         .append(".qr-box img { width: 110px; height: 110px; border: 1px solid #e2e8f0; padding: 4px; border-radius: 6px; }")
         .append(".signatures { display: flex; gap: 40px; }")
         .append(".sig-box { text-align: center; width: 130px; font-size: 11px; font-weight: bold; color: #4a5568; }")
         .append(".sig-line { border-top: 1px solid #a0aec0; margin-top: 45px; padding-top: 5px; }")
         
         .append(".copyright { text-align: center; margin-top: 20px; font-size: 9px; color: #a0aec0; border-top: 1px solid #edf2f7; padding-top: 10px; }")
         .append("</style></head><body>");

        try {
            JSONObject root = new JSONObject(receiptJsonStr);
            JSONObject t = root.optJSONObject("transaction");
            if (t == null) t = root.optJSONObject("data");
            if (t == null) t = root.optJSONObject("receipt");
            if (t == null) t = root;
            
            JSONObject customerObj = t.optJSONObject("customer");
            JSONObject paymentObj = t.optJSONObject("payment");

            String sellerName = t.optString("seller_name", t.optString("seller", t.optString("cashier_name", t.optString("created_by", "—"))));
            String branchName = t.optString("branch_name", t.optString("branch", ""));
            if (branchName.isEmpty() && getContext() != null) {
                try {
                    MiraBranchesManager bm = MiraBranchesManager.getInstance(getContext());
                    if (bm != null && bm.hasActiveBranch()) branchName = bm.getActiveBranchDisplayName();
                } catch (Exception ignored) {}
            }
            if (branchName.isEmpty()) branchName = isArabic ? "الفرع الرئيسي" : "Main Branch";
            
            String saleNumber = t.optString("sale_number", t.optString("invoice_number", t.optString("receipt_number", t.optString("id", "—"))));
            String dateStr = t.optString("timestamp", t.optString("created_at", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date())));
            
            String customer = t.optString("customer_name", customerObj != null ? customerObj.optString("name", "") : "");
            if (customer.isEmpty()) customer = isArabic ? "عميل نقدي" : "Cash Customer";
            
            String customerPhone = t.optString("customer_phone", customerObj != null ? customerObj.optString("phone", "") : "");
            
            String paymentMethod = t.optString("payment_method", paymentObj != null ? paymentObj.optString("payment_method", "CASH") : "CASH");
            
            String currency = t.optString("currency", paymentObj != null ? paymentObj.optString("currency_code", "") : "");
            if (currency.isEmpty() && priceManager != null) currency = priceManager.getCurrencySymbol("YER");
            if (currency.isEmpty()) currency = defaultCurrency;
            
            String currencyName = paymentObj != null ? paymentObj.optString("currency_name", "") : ArabicNumberToWords.getCurrencyArabicName(currency);

            double exchangeRate = t.optDouble("exchange_rate", paymentObj != null ? paymentObj.optDouble("exchange_rate", 1.0) : 1.0);
            if (priceManager != null && exchangeRate == 1.0) exchangeRate = priceManager.getExchangeRate(currency);
            
            String refundStatus = t.optString("refund_status", t.optString("status", "completed"));
            boolean isRefund = "refunded".equals(refundStatus) || "fully_refunded".equals(refundStatus) || "partially_refunded".equals(refundStatus);

            // Header Render
            h.append("<div class='header'>")
             .append("<div class='brand-box'>")
             .append("<div class='org-name'>").append(orgName).append("</div>")
             .append("<div class='org-subtitle'>").append(isArabic ? "للمجوهرات والذهب والماس" : "Jewelry & Gold Specialist").append("</div>")
             .append("<div class='org-meta'>📍 ").append(address.isEmpty() ? "صنعاء - اليمن" : address).append(" | 📞 ").append(phone).append("</div>")
             .append("</div>")
             .append("</div>");

            // Title Bar
            h.append("<div class='invoice-title-bar'>")
             .append("<div class='invoice-title'>").append(isRefund ? (isArabic ? "🔄 سند مرتجع مبيعات" : "RETURN INVOICE") : (isArabic ? "🧾 سند بيع مجوهرات" : "JEWELRY INVOICE")).append("</div>")
             .append("<div class='invoice-type'>").append(isArabic ? "فاتورة رقم: " : "INV #: ").append(saleNumber).append("</div>")
             .append("</div>");

            // Info Grid Table
            h.append("<div class='info-grid'><table>")
             .append("<tr>")
             .append("<td class='label'>").append(isArabic ? "اسم العميل:" : "Customer:").append("</td><td class='value'>").append(customer).append("</td>")
             .append("<td class='label'>").append(isArabic ? "التاريخ والوقت:" : "Date:").append("</td><td class='value'>").append(dateStr).append("</td>")
             .append("</tr><tr>")
             .append("<td class='label'>").append(isArabic ? "هاتف العميل:" : "Phone:").append("</td><td class='value'>").append(customerPhone.isEmpty() ? "—" : customerPhone).append("</td>")
             .append("<td class='label'>").append(isArabic ? "مُحرر السند:" : "Seller:").append("</td><td class='value'>").append(sellerName).append("</td>")
             .append("</tr><tr>")
             .append("<td class='label'>").append(isArabic ? "طريقة الدفع:" : "Payment:").append("</td><td class='value'>").append(paymentMethod).append("</td>")
             .append("<td class='label'>").append(isArabic ? "الفرع:" : "Branch:").append("</td><td class='value'>").append(branchName).append("</td>")
             .append("</tr></table></div>");

            JSONArray items = t.optJSONArray("items");
            if (items == null) items = t.optJSONArray("sold_items");
            if (items == null) items = t.optJSONArray("products");
            
            double totalWeightSum = 0;

            if (items != null && items.length() > 0) {
                h.append("<table class='items'><thead><tr>")
                 .append("<th style='width:30px;'>#</th>")
                 .append("<th>").append(isArabic ? "الوصف والصنف" : "Item Description").append("</th>")
                 .append("<th>").append(isArabic ? "الرقم التسلسلي / RFID" : "Serial / Tag").append("</th>")
                 .append("<th>").append(isArabic ? "العيار" : "Karat").append("</th>")
                 .append("<th>").append(isArabic ? "الوزن (جرام)" : "Weight (g)").append("</th>")
                 .append("<th>").append(isArabic ? "المجوهرات / الصياغة" : "Labor/Stone").append("</th>")
                 .append("<th>").append(isArabic ? "الإجمالي" : "Total").append("</th>")
                 .append("</tr></thead><tbody>");
                
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it == null) continue;
                    
                    double weight = it.optDouble("weight", it.optDouble("gross_weight", 0));
                    totalWeightSum += weight;

                    double laborFee = it.optDouble("making_fee", it.optDouble("labor_price", 0));
                    double itemPriceUSD = it.optDouble("price_usd", it.optDouble("sale_price", it.optDouble("price", 0)));
                    
                    String serial = it.optString("serial_number", it.optString("serial_no", it.optString("epc", "—")));
                    String title = it.optString("title", it.optString("item_title", "—"));
                    String karat = it.optString("karat", it.optString("gold_karat", "21K"));

                    h.append("<tr>")
                     .append("<td>").append(i + 1).append("</td>")
                     .append("<td style='text-align:right; font-weight:bold;'>").append(title).append("</td>")
                     .append("<td><span class='serial-box'>").append(serial).append("</span></td>")
                     .append("<td><span class='karat-badge'>").append(karat).append("</span></td>")
                     .append("<td><b>").append(String.format(Locale.US, "%.3f", weight)).append("</b> g</td>")
                     .append("<td>").append(laborFee > 0 ? (priceManager != null ? priceManager.formatPriceDefault(laborFee) : String.valueOf(laborFee)) : "—").append("</td>")
                     .append("<td style='font-weight:bold; color:#0d1b2a;'>").append(priceManager != null ? priceManager.formatPriceDefault(itemPriceUSD) : String.valueOf(itemPriceUSD)).append("</td>")
                     .append("</tr>");
                }
                h.append("</tbody></table>");
            }

            double subtotalUSD = t.optDouble("subtotal_usd", paymentObj != null ? paymentObj.optDouble("subtotal_usd", 0) : t.optDouble("subtotal", 0));
            double discountUSD = t.optDouble("discount_amount_usd", paymentObj != null ? paymentObj.optDouble("discount_amount_usd", 0) : t.optDouble("discount", 0));
            double finalUSD = t.optDouble("final_amount_usd", paymentObj != null ? paymentObj.optDouble("final_amount_usd", subtotalUSD - discountUSD) : (subtotalUSD - discountUSD));
            double finalLocal = t.optDouble("final_amount_local", paymentObj != null ? paymentObj.optDouble("final_amount_local", finalUSD * exchangeRate) : finalUSD * exchangeRate);

            // Summary Section
            h.append("<div class='summary-container'>")
             .append("<div class='notes-box'>")
             .append("<b>📌 ").append(isArabic ? "ملاحظات وإرشادات الضمان:" : "Terms & Conditions:").append("</b><br>")
             .append("1. ").append(isArabic ? "المجوهرات المباعة مضمونة من عيوب التصنيع لمدة عام كامل." : "Jewelry guaranteed against defects for 1 year.").append("<br>")
             .append("2. ").append(isArabic ? "عند إعادة البيع أو الاستبدال يجب إحضار هذا السند الأصلي." : "Original invoice required for resale or exchange.").append("<br>")
             .append("3. ").append(isArabic ? "إجمالي الوزن الكلي للمبيعات: " : "Total Net Weight: ").append("<b>").append(String.format(Locale.US, "%.3f g", totalWeightSum)).append("</b>")
             .append("</div>")
             
             .append("<div class='totals-box'><table class='totals-table'>")
             .append("<tr><td class='label'>").append(isArabic ? "المبلغ الإجمالي:" : "Subtotal:").append("</td><td class='value' style='text-align:left;'>").append(priceManager != null ? priceManager.formatPriceDefault(subtotalUSD) : String.valueOf(subtotalUSD)).append("</td></tr>");
            
            if (discountUSD > 0) {
                h.append("<tr><td class='label'>").append(isArabic ? "الخصم الممنوح:" : "Discount:").append("</td><td class='value' style='text-align:left; color:#e53e3e;'>- ").append(priceManager != null ? priceManager.formatPriceDefault(discountUSD) : String.valueOf(discountUSD)).append("</td></tr>");
            }

            h.append("<tr class='grand-total'><td class='label' style='color:#0d1b2a;'>").append(isArabic ? "💰 الصافي المستحق:" : "💰 Net Total:").append("</td><td class='value' style='text-align:left; color:#0d1b2a;'>").append(priceManager != null ? priceManager.formatPriceDefault(finalUSD) : String.valueOf(finalUSD)).append("</td></tr>")
             .append("</table></div></div>");

            // Tafqeet Box
            if (finalUSD > 0 || finalLocal > 0) {
                try {
                    boolean isLocal = "YER".equalsIgnoreCase(currency) || "SAR".equalsIgnoreCase(currency) || "AED".equalsIgnoreCase(currency);
                    String amountInWords = ArabicNumberToWords.convert(isLocal ? finalLocal : finalUSD, currencyName);
                    
                    h.append("<div class='tafqeet-box'>")
                     .append("<b>").append(isArabic ? "مبلغ الفاتورة كتابةً: " : "Amount in words: ").append("</b>")
                     .append("<span style='color:#c59b27; font-weight:bold;'>").append(amountInWords).append("</span>")
                     .append("</div>");
                } catch (Exception e) {
                    Log.e(TAG, "Tafqeet error: " + e.getMessage());
                }
            }

            // Warranty Seal
            h.append("<div class='warranty-box'>")
             .append("🛡️ ").append(isArabic ? "سند موثق برقم تسلسلي مع ضمان المالي والتصنيعي للمجوهرات" : "Certified Gold & Jewelry Transaction Guarantee")
             .append("</div>");

            // Footer Section
            String qrData = Uri.encode("Org:" + orgName + "|Invoice:" + saleNumber + "|Total:" + finalUSD + "|Weight:" + totalWeightSum + "g");
            String qrApiUrl = "https://api.qrserver.com/v1/create-qr-code/?size=150x150&data=" + qrData;
            
            h.append("<div class='footer-section'>")
             .append("<div class='qr-box'>")
             .append("<img src='").append(qrApiUrl).append("' alt='QR Verify'/><br>")
             .append("<span style='font-size:9px; color:#718096;'>").append(isArabic ? "رمز التحقق الرقمي" : "Digital Verification").append("</span>")
             .append("</div>")
             
             .append("<div class='signatures'>")
             .append("<div class='sig-box'><div class='sig-line'>").append(isArabic ? "توقيع المشتري" : "Customer Sign").append("</div></div>")
             .append("<div class='sig-box'><div class='sig-line'>").append(isArabic ? "ختم وتوقيع المحل" : "Authorized Stamp").append("</div></div>")
             .append("</div>")
             .append("</div>");

        } catch (Exception e) {
            Log.e(TAG, "Error rendering receipt: " + e.getMessage(), e);
            h.append("<p style='color:red;text-align:center'>Error rendering receipt</p>");
        }

        h.append("<div class='copyright'>")
         .append(orgName).append(" © 2026 | ").append(isArabic ? "نظام إدارة المبيعات الذكي - ميرا للمجوهرات" : "MIRA Jewelry POS System")
         .append("</div></body></html>");

        webReceipt.loadDataWithBaseURL(null, h.toString(), "text/html", "UTF-8", null);
    }

    private void closeFragment() {
        if (!isAdded()) return;
        dismissAllowingStateLoss();
    }
}
