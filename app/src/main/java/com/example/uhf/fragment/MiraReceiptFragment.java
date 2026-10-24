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
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
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
    private ProgressBar progressLoading;
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
        setStyle(DialogFragment.STYLE_NORMAL, android.R.style.Theme_Material_Light_Dialog_Alert);

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

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
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
        progressLoading = view.findViewById(R.id.progressReceiptLoading);
        btnCloseReceipt = view.findViewById(R.id.btnMiraCloseReceipt);
        btnPrintReceipt = view.findViewById(R.id.btnPrintReceipt);
        btnShareReceipt = view.findViewById(R.id.btnShareReceipt);
        btnSaveReceipt = view.findViewById(R.id.btnSaveReceipt);

        if (webReceipt != null) {
            WebSettings settings = webReceipt.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setUseWideViewPort(true);
            settings.setLoadWithOverviewMode(true);
            settings.setBuiltInZoomControls(false);
            settings.setSupportZoom(false);

            webReceipt.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    if (progressLoading != null) progressLoading.setVisibility(View.GONE);
                }
            });
        }
    }

    private void applyLanguage() {
        if (btnPrintReceipt != null) btnPrintReceipt.setText(isArabic ? "طباعة" : "Print");
        if (btnShareReceipt != null) btnShareReceipt.setText(isArabic ? "مشاركة" : "Share");
        if (btnSaveReceipt != null) btnSaveReceipt.setText(isArabic ? "حفظ PDF" : "Save PDF");
    }

    private void setupListeners() {
        if (btnCloseReceipt != null) btnCloseReceipt.setOnClickListener(v -> closeFragment());
        if (btnPrintReceipt != null) btnPrintReceipt.setOnClickListener(v -> printReceipt());
        if (btnShareReceipt != null) btnShareReceipt.setOnClickListener(v -> shareReceiptAsPDF());
        if (btnSaveReceipt != null) btnSaveReceipt.setOnClickListener(v -> saveReceiptAsPDF());
    }

    // ═══════════════════════════════════════════════════════
    // دوال مساعدة آمنة لقراءة JSON
    // ═══════════════════════════════════════════════════════

    private String safeString(JSONObject obj, String key, String defaultValue) {
        if (obj == null || key == null) return defaultValue;
        try {
            Object val = obj.opt(key);
            if (val == null || val == JSONObject.NULL) return defaultValue;
            String s = String.valueOf(val).trim();
            if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
                return defaultValue;
            }
            return s;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private String findFirstNonEmpty(JSONObject obj, String... keys) {
        if (obj == null || keys == null) return "—";
        for (String key : keys) {
            String val = safeString(obj, key, null);
            if (val != null && !val.isEmpty()) {
                return val;
            }
        }
        return "—";
    }

    private double safeDouble(JSONObject obj, String key, double defaultValue) {
        if (obj == null || key == null) return defaultValue;
        try {
            Object val = obj.opt(key);
            if (val == null || val == JSONObject.NULL) return defaultValue;
            if (val instanceof Number) {
                return ((Number) val).doubleValue();
            }
            String s = String.valueOf(val).trim().replace(",", "");
            if (s.isEmpty() || "null".equalsIgnoreCase(s)) return defaultValue;
            return Double.parseDouble(s);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private boolean safeBoolean(JSONObject obj, String key, boolean defaultValue) {
        if (obj == null || key == null) return defaultValue;
        try {
            Object val = obj.opt(key);
            if (val == null || val == JSONObject.NULL) return defaultValue;
            if (val instanceof Boolean) return (Boolean) val;
            String s = String.valueOf(val).trim().toLowerCase();
            if ("true".equals(s) || "1".equals(s) || "yes".equals(s)) return true;
            if ("false".equals(s) || "0".equals(s) || "no".equals(s)) return false;
            return defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private String safeFormatPrice(double amount) {
        try {
            if (priceManager != null) {
                String formatted = priceManager.formatPriceDefault(amount);
                if (formatted != null && !formatted.isEmpty()) {
                    return formatted;
                }
            }
            return String.format(Locale.US, "%,.2f", amount);
        } catch (Exception e) {
            return String.format(Locale.US, "%,.2f", amount);
        }
    }

    private String escapeHtml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&#39;");
    }

    // ═══════════════════════════════════════════════════════
    // ✅ دوال مساعدة للعملات وطرق الدفع
    // ═══════════════════════════════════════════════════════

    private String getCurrencyIcon(String currency) {
        if (currency == null) return "💵";
        switch (currency.toUpperCase()) {
            case "YER": return "🇾🇪";
            case "SAR": return "🇸🇦";
            case "USD": return "🇺🇸";
            case "AED": return "🇦🇪";
            case "EUR": return "🇪🇺";
            default: return "💵";
        }
    }

    private String getCurrencyArabicName(String currency) {
        if (currency == null) return "ريال يمني";
        switch (currency.toUpperCase()) {
            case "YER": return "ريال يمني";
            case "SAR": return "ريال سعودي";
            case "USD": return "دولار أمريكي";
            case "AED": return "درهم إماراتي";
            case "EUR": return "يورو";
            default: return currency;
        }
    }

    private String getPaymentMethodArabic(String method) {
        if (method == null) return "نقداً";
        switch (method.toLowerCase()) {
            case "cash": return "نقداً 💵";
            case "card": return "بطاقة 💳";
            case "bank_transfer":
            case "transfer": return "تحويل بنكي 🏦";
            case "e_wallet": return "محفظة إلكترونية 📱";
            case "check":
            case "cheque": return "شيك 📝";
            case "credit": return "آجل ⏳";
            case "split": return "متعدد 💰";
            default: return method;
        }
    }

    // ═══════════════════════════════════════════════════════
    // Print / Share / Save
    // ═══════════════════════════════════════════════════════

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
                } else {
                    shareAsImage();
                }
            } catch (Exception e) {
                Log.e(TAG, "Share error: " + e.getMessage());
                shareAsImage();
            }
        }, 500);
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
        }, 500);
    }

    private File generatePDF() {
        try {
            Bitmap bitmap = captureWebView();
            if (bitmap == null) return null;

            PdfDocument doc = new PdfDocument();
            int pageWidth = 595;
            int pageHeight = Math.max(842, (bitmap.getHeight() * pageWidth) / bitmap.getWidth());

            PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create();
            PdfDocument.Page page = doc.startPage(info);
            Canvas canvas = page.getCanvas();

            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, pageWidth, pageHeight, true);
            canvas.drawBitmap(scaled, 0, 0, null);
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

    // ═══════════════════════════════════════════════════════
    // ✅ دالة جديدة: بناء HTML لتفاصيل الدفع المتعدد
    // ═══════════════════════════════════════════════════════
    private String buildPaymentsHtml(JSONObject t, JSONObject paymentObj) {
        try {
            // البحث عن مصفوفة payments في عدة أماكن محتملة
            JSONArray payments = t.optJSONArray("payments");
            if (payments == null && paymentObj != null) {
                payments = paymentObj.optJSONArray("payments");
            }

            // إذا لم توجد مصفوفة، ارجع نصاً فارغاً
            if (payments == null || payments.length() == 0) {
                return "";
            }

            // إذا كانت دفعة واحدة فقط، لا داعي لعرض القسم (السطر الأساسي يكفي)
            if (payments.length() == 1) {
                JSONObject single = payments.optJSONObject(0);
                if (single != null) {
                    String curr = safeString(single, "currency", "YER");
                    String method = safeString(single, "method", "cash");
                    // إذا كانت العملة الأساسية ونقداً، لا نعرض تفاصيل
                    if ("YER".equalsIgnoreCase(curr) && "cash".equalsIgnoreCase(method)) {
                        return "";
                    }
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append("<div class='payment-breakdown'>")
              .append("<div class='payment-title'>💵 ")
              .append(isArabic ? "تفاصيل طرق الدفع المتعددة" : "Multi-Payment Details")
              .append("</div>")
              .append("<table class='payments-table'>")
              .append("<thead><tr>")
              .append("<th>").append(isArabic ? "العملة" : "Currency").append("</th>")
              .append("<th>").append(isArabic ? "المبلغ" : "Amount").append("</th>")
              .append("<th>").append(isArabic ? "طريقة الدفع" : "Method").append("</th>")
              .append("<th>").append(isArabic ? "المعادل (YER)" : "Equivalent (YER)").append("</th>")
              .append("</tr></thead><tbody>");

            double totalInBase = 0;

            for (int i = 0; i < payments.length(); i++) {
                JSONObject p = payments.optJSONObject(i);
                if (p == null) continue;

                String currency = safeString(p, "currency", "YER");
                double amount = safeDouble(p, "amount", 0);
                double baseAmount = safeDouble(p, "base_amount", amount);
                String method = safeString(p, "method", "cash");
                double exchangeRate = safeDouble(p, "exchange_rate", 1.0);

                totalInBase += baseAmount;

                String icon = getCurrencyIcon(currency);
                String currName = getCurrencyArabicName(currency);
                String methodAr = getPaymentMethodArabic(method);

                // عرض سعر الصرف إذا كانت العملة مختلفة عن YER
                String rateInfo = "";
                if (!"YER".equalsIgnoreCase(currency) && exchangeRate > 0) {
                    rateInfo = " <span style='font-size:9px; color:#718096;'>(1 " + currency + " = "
                            + String.format(Locale.US, "%,.0f", exchangeRate) + " YER)</span>";
                }

                sb.append("<tr>")
                  .append("<td style='text-align:center; font-weight:bold;'>")
                  .append(icon).append(" ").append(currency)
                  .append("</td>")
                  .append("<td style='text-align:center; font-weight:bold; color:#0d1b2a;'>")
                  .append(String.format(Locale.US, "%,.2f", amount))
                  .append(rateInfo)
                  .append("</td>")
                  .append("<td style='text-align:center;'>").append(methodAr).append("</td>")
                  .append("<td style='text-align:center; font-weight:bold; color:#c59b27;'>")
                  .append(String.format(Locale.US, "%,.2f", baseAmount))
                  .append("</td>")
                  .append("</tr>");
            }

            // سطر الإجمالي
            if (payments.length() > 1) {
                sb.append("<tr class='payment-total-row'>")
                  .append("<td colspan='3' style='text-align:right; font-weight:bold;'>")
                  .append(isArabic ? "الإجمالي المدفوع:" : "Total Paid:")
                  .append("</td>")
                  .append("<td style='text-align:center; font-weight:bold; color:#0d1b2a;'>")
                  .append(String.format(Locale.US, "%,.2f", totalInBase))
                  .append(" YER</td>")
                  .append("</tr>");
            }

            sb.append("</tbody></table></div>");
            return sb.toString();

        } catch (Exception e) {
            Log.e(TAG, "buildPaymentsHtml error: " + e.getMessage());
            return "";
        }
    }

    // ═══════════════════════════════════════════════════════
    // HTML Rendering
    // ═══════════════════════════════════════════════════════

    private void renderHtmlReceipt() {
        if (webReceipt == null) return;
        if (progressLoading != null) progressLoading.setVisibility(View.VISIBLE);

        String phone = orgProfile != null ? orgProfile.getOrgPhone() : "";
        String address = orgProfile != null ? orgProfile.getOrgAddress() : "";
        String dir = isArabic ? "rtl" : "ltr";

        StringBuilder h = new StringBuilder();
        h.append("<!DOCTYPE html><html dir='").append(dir).append("'><head><meta charset='UTF-8'>")
         .append("<meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no'>")
         .append("<style>")
         .append("* { box-sizing: border-box; }")
         .append("body { font-family: 'Segoe UI', Tahoma, sans-serif; padding: 12px; margin: 0 auto; max-width: 100%; background: #fff; color: #1a1a1a; font-size: 11px; line-height: 1.4; }")
         .append(".header { display: flex; justify-content: space-between; align-items: center; border-bottom: 2px solid #0d1b2a; padding-bottom: 10px; margin-bottom: 10px; }")
         .append(".brand-box { text-align: right; }")
         .append(".org-name { font-size: 22px; font-weight: 800; color: #0d1b2a; }")
         .append(".org-subtitle { font-size: 10px; color: #c59b27; font-weight: bold; text-transform: uppercase; }")
         .append(".org-meta { font-size: 10px; color: #555; margin-top: 2px; }")
         .append(".invoice-title-bar { display: flex; justify-content: space-between; align-items: center; background: #0d1b2a; color: #fff; padding: 8px 12px; border-radius: 6px; margin: 10px 0; }")
         .append(".invoice-title { font-size: 15px; font-weight: bold; color: #f4d58d; }")
         .append(".invoice-type { font-size: 11px; background: rgba(255,255,255,0.15); padding: 3px 8px; border-radius: 4px; border: 1px solid #c59b27; }")
         .append(".refund-alert { background: #fff5f5; border: 1px solid #feb2b2; color: #c53030; padding: 8px; border-radius: 6px; text-align: center; font-weight: bold; margin-bottom: 10px; font-size: 12px; }")
         .append(".info-grid { width: 100%; font-size: 10px; margin-bottom: 10px; border: 1px solid #e0e0e0; border-radius: 6px; padding: 8px; background: #fafafa; }")
         .append(".info-grid table { width: 100%; border-collapse: collapse; }")
         .append(".info-grid td { padding: 3px 4px; vertical-align: top; }")
         .append(".label { color: #666; } .value { font-weight: bold; color: #0d1b2a; }")
         .append("table.items { width: 100%; border-collapse: collapse; margin: 10px 0; font-size: 10px; }")
         .append("table.items th { background: #0d1b2a; color: #f4d58d; padding: 6px 4px; text-align: center; border: 1px solid #0d1b2a; }")
         .append("table.items td { padding: 6px 4px; border: 1px solid #e2e8f0; text-align: center; color: #2d3748; }")
         .append("table.items tr:nth-child(even) { background-color: #f8fafc; }")
         .append(".returned-row { background-color: #fff5f5 !important; color: #e53e3e; text-decoration: line-through; }")
         .append(".serial-box { font-family: monospace; font-size: 9px; background: #edf2f7; padding: 2px 4px; border-radius: 3px; color: #2b6cb0; word-break: break-all; display: inline-block; }")
         .append(".karat-badge { display: inline-block; padding: 1px 5px; border-radius: 8px; font-weight: bold; background: #fefcbf; color: #744210; border: 1px solid #f6e05e; }")
         
         // ✅ CSS جديد لجدول المدفوعات
         .append(".payment-breakdown { margin: 12px 0; border: 2px solid #c59b27; border-radius: 8px; overflow: hidden; background: #fffdf5; }")
         .append(".payment-title { background: #0d1b2a; color: #f4d58d; padding: 8px 12px; font-weight: bold; font-size: 12px; text-align: center; letter-spacing: 0.5px; }")
         .append("table.payments-table { width: 100%; border-collapse: collapse; font-size: 10px; }")
         .append("table.payments-table th { background: #faf8f0; color: #0d1b2a; padding: 6px 4px; text-align: center; border-bottom: 2px solid #e0d5b0; font-weight: bold; font-size: 10px; }")
         .append("table.payments-table td { padding: 8px 4px; border-bottom: 1px solid #f0ead0; color: #2d3748; text-align: center; }")
         .append("table.payments-table tr:last-child td { border-bottom: none; }")
         .append(".payment-total-row td { background: #faf8f0; padding-top: 8px; padding-bottom: 8px; }")
         
         .append(".summary-container { display: flex; justify-content: space-between; margin-top: 10px; gap: 10px; }")
         .append(".notes-box { width: 50%; font-size: 9px; border: 1px dashed #cbd5e0; padding: 8px; border-radius: 6px; background: #f7fafc; }")
         .append(".totals-box { width: 48%; }")
         .append(".totals-table { width: 100%; font-size: 11px; border-collapse: collapse; }")
         .append(".totals-table td { padding: 4px 0; }")
         .append(".grand-total { font-size: 14px; font-weight: bold; color: #0d1b2a; border-top: 2px solid #0d1b2a; border-bottom: 2px solid #0d1b2a; padding: 6px 0; }")
         .append(".tafqeet-box { font-size: 10px; color: #1a202c; text-align: center; margin-top: 8px; padding: 6px; background: #f7fafc; border-radius: 6px; border: 1px solid #e2e8f0; }")
         .append(".warranty-box { font-size: 10px; font-weight: bold; text-align: center; margin: 10px 0; padding: 6px; border-radius: 6px; border: 1px dashed #38a169; background: #f0fff4; color: #22543d; }")
         .append(".footer-section { margin-top: 15px; display: flex; justify-content: space-between; align-items: flex-end; }")
         .append(".qr-box { text-align: center; }")
         .append(".qr-box img { width: 80px; height: 80px; border: 1px solid #e2e8f0; padding: 2px; border-radius: 4px; }")
         .append(".signatures { display: flex; gap: 20px; }")
         .append(".sig-box { text-align: center; width: 100px; font-size: 9px; font-weight: bold; color: #4a5568; }")
         .append(".sig-line { border-top: 1px solid #a0aec0; margin-top: 30px; padding-top: 3px; }")
         .append(".copyright { text-align: center; margin-top: 15px; font-size: 8px; color: #a0aec0; border-top: 1px solid #edf2f7; padding-top: 6px; }")
         .append("</style></head><body>");

        try {
            JSONObject root = new JSONObject(receiptJsonStr);
            JSONObject t = root.optJSONObject("transaction");
            if (t == null) t = root.optJSONObject("data");
            if (t == null) t = root.optJSONObject("receipt");
            if (t == null) t = root;

            JSONObject customerObj = t.optJSONObject("customer");
            JSONObject paymentObj = t.optJSONObject("payment");

            String sellerName = findFirstNonEmpty(t, "seller_name", "seller", "cashier_name", "created_by");
            if ("—".equals(sellerName)) sellerName = "—";

            String branchName = safeString(t, "branch_name", safeString(t, "branch", ""));
            if (branchName.isEmpty() && getContext() != null) {
                try {
                    MiraBranchesManager bm = MiraBranchesManager.getInstance(getContext());
                    if (bm != null && bm.hasActiveBranch()) branchName = bm.getActiveBranchDisplayName();
                } catch (Exception ignored) {}
            }
            if (branchName.isEmpty()) branchName = isArabic ? "الفرع الرئيسي" : "Main Branch";

            String saleNumber = findFirstNonEmpty(t, "sale_number", "invoice_number", "receipt_number", "id");
            String dateStr = findFirstNonEmpty(t, "timestamp", "created_at", "sale_date");
            if ("—".equals(dateStr)) {
                dateStr = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());
            }

            String customer = safeString(t, "customer_name",
                    customerObj != null ? safeString(customerObj, "name", "") : "");
            if (customer.isEmpty()) customer = isArabic ? "عميل نقدي" : "Cash Customer";

            String customerPhone = safeString(t, "customer_phone",
                    customerObj != null ? safeString(customerObj, "phone", "") : "");

            String paymentMethod = findFirstNonEmpty(t, "payment_method",
                    paymentObj != null ? safeString(paymentObj, "payment_method", "CASH") : "CASH");
            if ("—".equals(paymentMethod)) paymentMethod = "CASH";

            // ✅ تحديث اسم طريقة الدفع ليعرض "متعدد" عند وجود دفع مقسم
            String paymentMethodDisplay = getPaymentMethodArabic(paymentMethod);

            String currency = safeString(t, "currency",
                    paymentObj != null ? safeString(paymentObj, "currency_code", "") : "");
            if (currency.isEmpty() && priceManager != null) currency = priceManager.getDefaultCurrency();
            if (currency.isEmpty()) currency = defaultCurrency;

            String currencyName = paymentObj != null ? safeString(paymentObj, "currency_name", "") : "";
            if (currencyName.isEmpty()) {
                try {
                    currencyName = ArabicNumberToWords.getCurrencyArabicName(currency);
                } catch (Exception ignored) {
                    currencyName = currency;
                }
            }

            // ✅ قراءة exchange_rate
            double exchangeRate = safeDouble(t, "exchange_rate",
                    paymentObj != null ? safeDouble(paymentObj, "exchange_rate", 0) : 0);

            if (exchangeRate <= 1.0 && priceManager != null) {
                try {
                    double pmRate = priceManager.getExchangeRate(currency);
                    if (pmRate > 1.0) {
                        exchangeRate = pmRate;
                        Log.d(TAG, "✅ exchangeRate from priceManager: " + exchangeRate);
                    }
                } catch (Exception ignored) {}
            }

            if (exchangeRate <= 1.0) {
                exchangeRate = 530.0;
                Log.d(TAG, "⚠️ exchangeRate fallback to 530");
            }

            String refundStatus = findFirstNonEmpty(t, "refund_status", "status");
            if ("—".equals(refundStatus)) refundStatus = "completed";

            boolean isFullyRefunded = "refunded".equals(refundStatus) || "fully_refunded".equals(refundStatus);
            boolean isPartiallyRefunded = "partially_refunded".equals(refundStatus);

            // ═══ Header ═══
            h.append("<div class='header'>")
             .append("<div class='brand-box'>")
             .append("<div class='org-name'>").append(escapeHtml(orgName)).append("</div>")
             .append("<div class='org-subtitle'>").append(isArabic ? "للمجوهرات والذهب والماس" : "Jewelry & Gold Specialist").append("</div>")
             .append("<div class='org-meta'>📍 ").append(escapeHtml(address.isEmpty() ? "صنعاء - اليمن" : address))
             .append(" | 📞 ").append(escapeHtml(phone)).append("</div>")
             .append("</div>")
             .append("</div>");

            // ═══ Title Bar ═══
            String titleText = isFullyRefunded ? (isArabic ? "🔄 سند مرتجع مبيعات كلي" : "FULL RETURN INVOICE") :
                               isPartiallyRefunded ? (isArabic ? "⚠️ سند مرتجع مبيعات جزئي" : "PARTIAL RETURN INVOICE") :
                               (isArabic ? "🧾 سند بيع مجوهرات" : "JEWELRY INVOICE");

            h.append("<div class='invoice-title-bar'>")
             .append("<div class='invoice-title'>").append(titleText).append("</div>")
             .append("<div class='invoice-type'>").append(isArabic ? "فاتورة رقم: " : "INV #: ").append(escapeHtml(saleNumber)).append("</div>")
             .append("</div>");

            if (isPartiallyRefunded) {
                h.append("<div class='refund-alert'>⚠️ تنبيه: تم إرجاع بعض القطع من هذه الفاتورة. الأصناف المرتجعة مشطوبة أدناه.</div>");
            } else if (isFullyRefunded) {
                h.append("<div class='refund-alert'>🚨 تنبيه: هذه الفاتورة مرتجعة بالكامل والمبلغ مرجع للعميل.</div>");
            }

            // ═══ Info Grid ═══
            h.append("<div class='info-grid'><table>")
             .append("<tr>")
             .append("<td class='label'>").append(isArabic ? "اسم العميل:" : "Customer:").append("</td><td class='value'>").append(escapeHtml(customer)).append("</td>")
             .append("<td class='label'>").append(isArabic ? "التاريخ والوقت:" : "Date:").append("</td><td class='value'>").append(escapeHtml(dateStr)).append("</td>")
             .append("</tr><tr>")
             .append("<td class='label'>").append(isArabic ? "هاتف العميل:" : "Phone:").append("</td><td class='value'>").append(customerPhone.isEmpty() ? "—" : escapeHtml(customerPhone)).append("</td>")
             .append("<td class='label'>").append(isArabic ? "مُحرر السند:" : "Seller:").append("</td><td class='value'>").append(escapeHtml(sellerName)).append("</td>")
             .append("</tr><tr>")
             .append("<td class='label'>").append(isArabic ? "طريقة الدفع:" : "Payment:").append("</td><td class='value'>").append(escapeHtml(paymentMethodDisplay)).append("</td>")
             .append("<td class='label'>").append(isArabic ? "الفرع:" : "Branch:").append("</td><td class='value'>").append(escapeHtml(branchName)).append("</td>")
             .append("</tr></table></div>");

            // ═══ Items ═══
            JSONArray items = t.optJSONArray("items");
            if (items == null) items = t.optJSONArray("sold_items");
            if (items == null) items = t.optJSONArray("products");

            double totalWeightSum = 0;

            if (items != null && items.length() > 0) {
                h.append("<table class='items'><thead><tr>")
                 .append("<th style='width:25px;'>#</th>")
                 .append("<th>").append(isArabic ? "الوصف والصنف" : "Item Description").append("</th>")
                 .append("<th>").append(isArabic ? "الرقم التسلسلي / RFID" : "Serial / Tag").append("</th>")
                 .append("<th>").append(isArabic ? "العيار" : "Karat").append("</th>")
                 .append("<th>").append(isArabic ? "الوزن (جرام)" : "Weight (g)").append("</th>")
                 .append("<th>").append(isArabic ? "الصياغة" : "Labor").append("</th>")
                 .append("<th>").append(isArabic ? "الإجمالي" : "Total").append("</th>")
                 .append("</tr></thead><tbody>");

                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it == null) continue;

                    boolean isItemRefunded = safeBoolean(it, "is_returned",
                                            safeBoolean(it, "is_refunded", false)) || isFullyRefunded;

                    double weight = safeDouble(it, "weight",
                                    safeDouble(it, "gross_weight",
                                    safeDouble(it, "gross_weight_grams", 0)));
                    if (!isItemRefunded) {
                        totalWeightSum += weight;
                    }

                    double laborFee = safeDouble(it, "making_fee",
                                       safeDouble(it, "labor_price", 0));
                    double itemPriceUSD = safeDouble(it, "price_usd",
                                          safeDouble(it, "sale_price",
                                          safeDouble(it, "price", 0)));

                    String serial = findFirstNonEmpty(it,
                            "serial_number", "serial_no", "item_code", "epc",
                            "barcode", "gtin", "tag", "code");

                    String title = findFirstNonEmpty(it,
                            "title", "item_title", "name", "product_name", "description");

                    String karat = findFirstNonEmpty(it, "karat", "gold_karat");
                    if ("—".equals(karat)) karat = "21K";

                    String rowClass = isItemRefunded ? "class='returned-row'" : "";

                    h.append("<tr ").append(rowClass).append(">")
                     .append("<td>").append(i + 1).append("</td>")
                     .append("<td style='text-align:right; font-weight:bold;'>")
                     .append(escapeHtml(title))
                     .append(isItemRefunded ? " <span style='color:#e53e3e;'>(مرتجع)</span>" : "")
                     .append("</td>")
                     .append("<td><span class='serial-box'>").append(escapeHtml(serial)).append("</span></td>")
                     .append("<td><span class='karat-badge'>").append(escapeHtml(karat)).append("</span></td>")
                     .append("<td><b>").append(String.format(Locale.US, "%.3f", weight)).append("</b> g</td>")
                     .append("<td>").append(laborFee > 0 ? safeFormatPrice(laborFee) : "—").append("</td>")
                     .append("<td style='font-weight:bold; color:#0d1b2a;'>").append(safeFormatPrice(itemPriceUSD)).append("</td>")
                     .append("</tr>");
                }
                h.append("</tbody></table>");
            }

            // ═══════════════════════════════════════════════════════
            // ✅ [جديد] قسم تفاصيل الدفع المتعدد
            // ═══════════════════════════════════════════════════════
            String paymentsHtml = buildPaymentsHtml(t, paymentObj);
            if (!paymentsHtml.isEmpty()) {
                h.append(paymentsHtml);
            }

            // ═══ Totals ═══
            double subtotalUSD = safeDouble(t, "subtotal_usd",
                    paymentObj != null ? safeDouble(paymentObj, "subtotal_usd",
                    safeDouble(t, "subtotal", 0)) : safeDouble(t, "subtotal", 0));

            double discountUSD = safeDouble(t, "discount_amount_usd",
                    safeDouble(t, "discount_usd",
                    paymentObj != null ? safeDouble(paymentObj, "discount_amount_usd",
                    safeDouble(t, "discount", 0)) : safeDouble(t, "discount", 0)));

            double finalUSD = safeDouble(t, "final_amount_usd",
                    safeDouble(t, "total_amount_usd",
                    safeDouble(t, "total_usd",
                    paymentObj != null ? safeDouble(paymentObj, "final_amount_usd",
                    subtotalUSD - discountUSD) : (subtotalUSD - discountUSD))));

            double finalLocal = safeDouble(t, "final_amount",
                    safeDouble(t, "total_amount",
                    safeDouble(t, "final_amount_local",
                    safeDouble(t, "total",
                    paymentObj != null ? safeDouble(paymentObj, "final_amount",
                    paymentObj != null ? safeDouble(paymentObj, "final_amount_local", 0) : 0) : 0))));

            if (finalLocal <= 0 && finalUSD > 0) {
                finalLocal = finalUSD * exchangeRate;
                Log.d(TAG, "⚠️ finalLocal calculated: " + finalLocal + " = " + finalUSD + " × " + exchangeRate);
            }

            h.append("<div class='summary-container'>");

            // ✅ Notes box
            h.append("<div class='notes-box'>")
             .append("<b>📌 ").append(isArabic ? "ملاحظات وإرشادات الضمان:" : "Terms & Conditions:").append("</b><br>")
             .append("1. ").append(isArabic ? "المجوهرات المباعة مضمونة من عيوب التصنيع لمدة عام كامل." : "Jewelry guaranteed against defects for 1 year.").append("<br>")
             .append("2. ").append(isArabic ? "عند إعادة البيع أو الاستبدال يجب إحضار هذا السند الأصلي." : "Original invoice required for resale or exchange.").append("<br>")
             .append("3. ").append(isArabic ? "صافي الوزن المباع المتبقي: " : "Net Active Weight: ").append("<b>").append(String.format(Locale.US, "%.3f g", totalWeightSum)).append("</b>")
             .append("</div>");

            // ✅ Totals box
            h.append("<div class='totals-box'><table class='totals-table'>");

            // ✅ Subtotal
            h.append("<tr><td class='label'>")
             .append(isArabic ? "المبلغ الإجمالي:" : "Subtotal:")
             .append("</td><td class='value' style='text-align:left;'>")
             .append(safeFormatPrice(subtotalUSD))
             .append("</td></tr>");

            // ✅ Discount
            if (discountUSD > 0) {
                h.append("<tr><td class='label'>")
                 .append(isArabic ? "الخصم الممنوح:" : "Discount:")
                 .append("</td><td class='value' style='text-align:left; color:#e53e3e;'>- ")
                 .append(safeFormatPrice(discountUSD))
                 .append("</td></tr>");
            }

            // ✅ Grand Total
            String displayAmount;
            String displayCurrencyLabel;

            if (finalLocal > 0 && Math.abs(finalLocal - finalUSD) > 0.01) {
                displayAmount = String.format(Locale.US, "%,.2f", finalLocal);
                displayCurrencyLabel = currency;
            } else {
                displayAmount = String.format(Locale.US, "%,.2f", finalUSD);
                displayCurrencyLabel = "USD";
            }

            h.append("<tr class='grand-total'><td class='label' style='color:#0d1b2a;'>")
             .append(isArabic ? "💰 الصافي المستحق:" : "💰 Net Total:")
             .append("</td><td class='value' style='text-align:left; color:#0d1b2a;'>")
             .append(displayAmount)
             .append(" <span style='font-size:11px; color:#c59b27;'>")
             .append(displayCurrencyLabel)
             .append("</span>")
             .append("</td></tr>");

            h.append("</table></div></div>");

            // ═══ Tafqeet ═══
            if (finalUSD > 0 || finalLocal > 0) {
                try {
                    boolean isLocal = "YER".equalsIgnoreCase(currency)
                            || "SAR".equalsIgnoreCase(currency)
                            || "AED".equalsIgnoreCase(currency);
                    double amountForWords = isLocal && finalLocal > 0 ? finalLocal : finalUSD;

                    String amountInWords = ArabicNumberToWords.convert(amountForWords, currencyName);

                    h.append("<div class='tafqeet-box'>")
                     .append("<b>").append(isArabic ? "مبلغ الفاتورة كتابةً: " : "Amount in words: ").append("</b>")
                     .append("<span style='color:#c59b27; font-weight:bold;'>").append(escapeHtml(amountInWords)).append("</span>")
                     .append("</div>");
                } catch (Exception e) {
                    Log.e(TAG, "Tafqeet error: " + e.getMessage());
                }
            }

            // ═══ Warranty ═══
            h.append("<div class='warranty-box'>")
             .append("🛡️ ").append(isArabic ? "سند موثق برقم تسلسلي مع الضمان المالي والتصنيعي للمجوهرات" : "Certified Gold & Jewelry Transaction Guarantee")
             .append("</div>");

            // ═══ Footer ═══
            String qrData = Uri.encode("Org:" + orgName + "|Invoice:" + saleNumber + "|Total:" + finalUSD + "|Weight:" + totalWeightSum + "g");
            String qrApiUrl = "https://api.qrserver.com/v1/create-qr-code/?size=150x150&data=" + qrData;

            h.append("<div class='footer-section'>")
             .append("<div class='qr-box'>")
             .append("<img src='").append(qrApiUrl).append("' alt='QR Verify'/><br>")
             .append("<span style='font-size:8px; color:#718096;'>").append(isArabic ? "رمز التحقق الرقمي" : "Digital Verification").append("</span>")
             .append("</div>")

             .append("<div class='signatures'>")
             .append("<div class='sig-box'><div class='sig-line'>").append(isArabic ? "توقيع المشتري" : "Customer Sign").append("</div></div>")
             .append("<div class='sig-box'><div class='sig-line'>").append(isArabic ? "ختم وتوقيع المحل" : "Authorized Stamp").append("</div></div>")
             .append("</div>")
             .append("</div>");

        } catch (Exception e) {
            Log.e(TAG, "Error rendering receipt: " + e.getMessage(), e);
            h.append("<p style='color:red;text-align:center;padding:20px;'>❌ خطأ في تحميل البيانات</p>");
            h.append("<p style='color:#666;text-align:center;font-size:10px;'>").append(escapeHtml(e.getMessage())).append("</p>");
        }

        h.append("<div class='copyright'>")
         .append(escapeHtml(orgName)).append(" © 2026 | ")
         .append(isArabic ? "نظام إدارة المبيعات الذكي - ميرا للمجوهرات" : "MIRA Jewelry POS System")
         .append("</div></body></html>");

        webReceipt.loadDataWithBaseURL(null, h.toString(), "text/html", "UTF-8", null);
    }

    private void closeFragment() {
        if (!isAdded()) return;
        dismissAllowingStateLoss();
    }
}
