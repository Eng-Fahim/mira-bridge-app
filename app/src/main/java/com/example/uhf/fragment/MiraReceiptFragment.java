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

    private static final String TAG = "MiraReceiptFragment";
    private static final String ARG_RECEIPT_JSON = "receipt_json";

    private String receiptJsonStr = "{}";
    private boolean isArabic;
    private MiraOrgProfileManager orgProfile;
    private String orgName = "MIRA Jewelry";

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
        isArabic = Locale.getDefault().getLanguage().equals("ar");
        if (getContext() != null) {
            orgProfile = MiraOrgProfileManager.getInstance(getContext());
            orgName = orgProfile.getOrgName();
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
        if (btnShareReceipt != null) btnShareReceipt.setOnClickListener(v -> shareReceiptAsPDF());
        if (btnSaveReceipt != null) btnSaveReceipt.setOnClickListener(v -> saveReceiptAsPDF());
    }

    // ==================== 🖨️ طباعة ====================

    private void printReceipt() {
        if (getContext() == null || webReceipt == null) return;

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            PrintManager printManager = (PrintManager) requireContext().getSystemService(Context.PRINT_SERVICE);
            String jobName = orgName + " - " + (isArabic ? "إيصال" : "Receipt");
            PrintDocumentAdapter printAdapter = webReceipt.createPrintDocumentAdapter(jobName);
            printManager.print(jobName, printAdapter, new PrintAttributes.Builder().build());
            Toast.makeText(getContext(), isArabic ? "🖨️ تم فتح نافذة الطباعة" : "🖨️ Print dialog opened", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(getContext(), isArabic ? "الطباعة غير مدعومة" : "Print not supported", Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== 📤 مشاركة كـ PDF ====================

    private void shareReceiptAsPDF() {
        try {
            File pdfFile = generatePDF();
            if (pdfFile == null) {
                Toast.makeText(getContext(), isArabic ? "فشل إنشاء الملف" : "File creation failed", Toast.LENGTH_SHORT).show();
                return;
            }

            Uri uri = FileProvider.getUriForFile(requireContext(),
                requireContext().getPackageName() + ".fileprovider", pdfFile);

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("application/pdf");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, orgName + " - " + (isArabic ? "إيصال بيع" : "Sales Receipt"));
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, isArabic ? "مشاركة الإيصال" : "Share Receipt"));
        } catch (Exception e) {
            Log.e(TAG, "Share error: " + e.getMessage());
            // fallback: مشاركة نصية
            shareAsText();
        }
    }

    private void shareAsText() {
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_SUBJECT, orgName + " - " + (isArabic ? "إيصال بيع" : "Sales Receipt"));
            intent.putExtra(Intent.EXTRA_TEXT, receiptJsonStr);
            startActivity(Intent.createChooser(intent, isArabic ? "مشاركة الإيصال" : "Share Receipt"));
        } catch (Exception e) {
            Toast.makeText(getContext(), isArabic ? "خطأ في المشاركة" : "Share error", Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== 💾 حفظ كـ PDF ====================

    private void saveReceiptAsPDF() {
        try {
            File pdfFile = generatePDF();
            if (pdfFile != null) {
                Toast.makeText(getContext(), 
                    (isArabic ? "✅ تم الحفظ: " : "✅ Saved: ") + pdfFile.getName(), 
                    Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(getContext(), isArabic ? "❌ فشل الحفظ" : "❌ Save failed", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Save error: " + e.getMessage());
            Toast.makeText(getContext(), isArabic ? "❌ خطأ في الحفظ" : "❌ Save error", Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== 🏗️ إنشاء PDF ====================

    private File generatePDF() {
        try {
            // 1. Capture WebView as Bitmap
            Bitmap bitmap = captureWebView();
            if (bitmap == null) return null;

            // 2. Create PDF
            PdfDocument document = new PdfDocument();
            int pageWidth = 595; // A4 width
            int pageHeight = bitmap.getHeight() * pageWidth / bitmap.getWidth() + 40;

            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create();
            PdfDocument.Page page = document.startPage(pageInfo);
            Canvas canvas = page.getCanvas();

            // Scale bitmap to fit
            Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, pageWidth - 40, pageHeight - 40, true);
            canvas.drawBitmap(scaledBitmap, 20, 20, null);

            document.finishPage(page);

            // 3. Save to Downloads
            String fileName = orgName.replaceAll("\\s+", "_") + "_Receipt_" 
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".pdf";
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MIRA_Receipts");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, fileName);

            FileOutputStream fos = new FileOutputStream(file);
            document.writeTo(fos);
            document.close();
            fos.close();

            return file;

        } catch (Exception e) {
            Log.e(TAG, "PDF generation error: " + e.getMessage());
            return null;
        }
    }

    private Bitmap captureWebView() {
        if (webReceipt == null) return null;
        try {
            Bitmap bitmap = Bitmap.createBitmap(webReceipt.getWidth(), webReceipt.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            webReceipt.draw(canvas);
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== HTML ====================

    private void renderHtmlReceipt() {
        if (webReceipt == null) return;

        String orgSlogan = orgProfile != null ? orgProfile.getOrgSlogan() : "";
        String orgPhone = orgProfile != null ? orgProfile.getOrgPhone() : "";
        String orgAddress = orgProfile != null ? orgProfile.getOrgAddress() : "";
        String dir = isArabic ? "rtl" : "ltr";

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html dir='").append(dir).append("'><head><meta charset='UTF-8'><style>")
            .append("body{background:#fff;color:#1a1a1a;font-family:sans-serif;padding:14px;max-width:380px;margin:auto}")
            .append(".org-name{font-size:20px;font-weight:bold;color:#1A237E;text-align:center;margin-bottom:2px}")
            .append(".org-slogan{font-size:11px;color:#666;text-align:center;margin-bottom:4px}")
            .append(".org-contact{font-size:10px;color:#888;text-align:center;line-height:1.5;margin-bottom:12px}")
            .append(".divider{border-top:2px solid #C5A059;margin:10px 0}")
            .append(".check{text-align:center;font-size:40px;color:#22C55E;margin:8px 0}")
            .append(".success{text-align:center;font-weight:bold;font-size:15px;color:#22C55E;margin-bottom:10px}")
            .append(".row{display:flex;justify-content:space-between;margin-bottom:5px;font-size:12px}")
            .append(".label{color:#666}.value{font-weight:bold}")
            .append("table{width:100%;border-collapse:collapse;margin:10px 0;font-size:11px}")
            .append("th{background:#f5f5f5;padding:5px;border-bottom:1px solid #ddd;font-size:10px}")
            .append("td{padding:5px;border-bottom:1px solid #eee;text-align:center;font-size:10px}")
            .append(".total-box{background:#1A237E;color:#fff;padding:10px;border-radius:6px;text-align:center;margin-top:10px}")
            .append(".total-amount{font-size:22px;font-weight:bold}")
            .append(".footer{text-align:center;margin-top:14px;font-size:9px;color:#888;line-height:1.5}")
            .append("</style></head><body>")

            .append("<div class='org-name'>").append(orgName).append("</div>")
            .append("<div class='org-slogan'>").append(orgSlogan).append("</div>")
            .append("<div class='org-contact'>");
        if (!orgPhone.isEmpty()) html.append("📞 ").append(orgPhone).append(" &nbsp; ");
        if (!orgAddress.isEmpty()) html.append("📍 ").append(orgAddress);
        html.append("</div><div class='divider'></div>")
            .append("<div class='check'>✓</div>")
            .append("<div class='success'>").append(isArabic ? "تم البيع بنجاح" : "Sale Completed").append("</div>");

        try {
            JSONObject root = new JSONObject(receiptJsonStr);
            JSONObject transaction = root.optJSONObject("transaction");
            if (transaction == null) transaction = root;

            String saleId = transaction.optString("sale_id", "—");
            String date = transaction.optString("timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()));
            String customer = transaction.optString("customer_name", isArabic ? "عميل" : "Customer");
            double finalAmount = transaction.optDouble("final_amount", transaction.optDouble("subtotal", 0));

            html.append("<div class='row'><span class='label'>").append(isArabic ? "رقم العملية" : "Receipt #").append("</span><span class='value'>#").append(saleId).append("</span></div>");
            html.append("<div class='row'><span class='label'>").append(isArabic ? "التاريخ" : "Date").append("</span><span class='value'>").append(date).append("</span></div>");
            html.append("<div class='row'><span class='label'>").append(isArabic ? "العميل" : "Customer").append("</span><span class='value'>").append(customer).append("</span></div>");

            JSONArray items = transaction.optJSONArray("items");
            if (items != null && items.length() > 0) {
                html.append("<table><tr><th>#</th><th>").append(isArabic ? "القطعة" : "Item").append("</th><th>").append(isArabic ? "العيار" : "Karat").append("</th><th>").append(isArabic ? "السعر" : "Price").append("</th></tr>");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    html.append("<tr><td>").append(i + 1).append("</td>")
                        .append("<td>").append(item.optString("title", "—")).append("</td>")
                        .append("<td>").append(item.optString("karat", "—")).append("</td>")
                        .append("<td>").append(String.format(Locale.US, "%,.0f", item.optDouble("price", 0))).append("</td></tr>");
                }
                html.append("</table>");
            }

            html.append("<div class='total-box'>")
                .append("<div style='font-size:10px;opacity:0.8'>").append(isArabic ? "المبلغ النهائي" : "Final Amount").append("</div>")
                .append("<div class='total-amount'>").append(String.format(Locale.US, "%,.0f YER", finalAmount)).append("</div>")
                .append("</div>");

        } catch (Exception e) {
            html.append("<p style='color:#EF4444;text-align:center'>").append(isArabic ? "خطأ في البيانات" : "Data error").append("</p>");
        }

        html.append("<div class='footer'>").append(orgName).append("<br>MIRA Jewelry System<br>© 2026</div>");
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
