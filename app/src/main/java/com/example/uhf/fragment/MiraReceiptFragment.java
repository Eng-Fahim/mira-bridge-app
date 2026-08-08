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
import androidx.fragment.app.DialogFragment; // تم التحويل إلى DialogFragment

import com.example.uhf.R;
import com.example.uhf.manager.MiraOrgProfileManager;

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
        // تعيين النمط ليكون نافذة منبثقة ملء الشاشة تقريباً
        setStyle(DialogFragment.STYLE_NORMAL, android.R.style.Theme_Material_Light_Dialog_MinWidth);
        if (getArguments() != null) receiptJsonStr = getArguments().getString(ARG_RECEIPT_JSON, "{}");
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
            PrintManager pm = (PrintManager) requireContext().getSystemService(Context.PRINT_SERVICE);
            PrintDocumentAdapter adapter = webReceipt.createPrintDocumentAdapter(orgName);
            pm.print(orgName, adapter, new PrintAttributes.Builder().build());
            Toast.makeText(getContext(), isArabic ? "🖨️ تم فتح الطباعة" : "🖨️ Print opened", Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== 📤 مشاركة PDF ====================
    private void shareReceiptAsPDF() {
        webReceipt.postDelayed(() -> {
            try {
                File pdf = generatePDF();
                if (pdf == null) { shareAsText(); return; }
                Uri uri = FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".fileprovider", pdf);
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("application/pdf");
                intent.putExtra(Intent.EXTRA_STREAM, uri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(intent, isArabic ? "مشاركة الإيصال" : "Share Receipt"));
            } catch (Exception e) { shareAsText(); }
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
                Toast.makeText(getContext(), (isArabic ? "✅ تم الحفظ: " : "✅ Saved: ") + pdf.getName(), Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(getContext(), isArabic ? "❌ فشل الحفظ" : "❌ Save failed", Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== 🏗️ generatePDF ====================
    private File generatePDF() {
        try {
            Bitmap bitmap = captureWebView();
            if (bitmap == null) return null;

            PdfDocument doc = new PdfDocument();
            int w = 595, h = bitmap.getHeight() * w / bitmap.getWidth() + 40;
            PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(w, h, 1).create();
            PdfDocument.Page page = doc.startPage(info);
            Canvas canvas = page.getCanvas();
            canvas.drawBitmap(Bitmap.createScaledBitmap(bitmap, w - 40, h - 40, true), 20, 20, null);
            doc.finishPage(page);

            String fn = orgName.replaceAll("\\s+", "_") + "_Receipt_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".pdf";
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
            Bitmap b = Bitmap.createBitmap(webReceipt.getMeasuredWidth(), webReceipt.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
            webReceipt.draw(new Canvas(b));
            return b;
        } catch (Exception e) { return null; }
    }

    // ==================== HTML ====================
    private void renderHtmlReceipt() {
        if (webReceipt == null) return;
        String slogan = orgProfile != null ? orgProfile.getOrgSlogan() : "";
        String phone = orgProfile != null ? orgProfile.getOrgPhone() : "";
        String address = orgProfile != null ? orgProfile.getOrgAddress() : "";
        String dir = isArabic ? "rtl" : "ltr";

        StringBuilder h = new StringBuilder();
        h.append("<!DOCTYPE html><html dir='").append(dir).append("'><head><meta charset='UTF-8'><style>")
         .append("body{font-family:sans-serif;padding:12px;max-width:380px;margin:auto;background:#fff;color:#1a1a1a}")
         .append(".org-name{font-size:20px;font-weight:bold;color:#1A237E;text-align:center}")
         .append(".org-slogan{font-size:11px;color:#666;text-align:center}")
         .append(".org-contact{font-size:10px;color:#888;text-align:center;margin-bottom:10px}")
         .append(".divider{border-top:2px solid #C5A059;margin:10px 0}")
         .append(".check{text-align:center;font-size:36px;color:#22C55E}")
         .append(".success{text-align:center;font-weight:bold;color:#22C55E;margin-bottom:8px}")
         .append(".row{display:flex;justify-content:space-between;font-size:12px;margin-bottom:4px}")
         .append(".label{color:#666}.value{font-weight:bold}")
         .append("table{width:100%;border-collapse:collapse;margin:10px 0;font-size:11px}")
         .append("th{background:#f5f5f5;padding:5px;border-bottom:1px solid #ddd}")
         .append("td{padding:5px;border-bottom:1px solid #eee;text-align:center}")
         .append(".total-box{background:#1A237E;color:#fff;padding:10px;border-radius:6px;text-align:center;margin-top:10px}")
         .append(".total{font-size:22px;font-weight:bold}")
         .append(".footer{text-align:center;margin-top:14px;font-size:9px;color:#888}")
         .append("</style></head><body>")
         .append("<div class='org-name'>").append(orgName).append("</div>")
         .append("<div class='org-slogan'>").append(slogan).append("</div>")
         .append("<div class='org-contact'>");
        if (!phone.isEmpty()) h.append("📞 ").append(phone).append(" ");
        if (!address.isEmpty()) h.append("📍 ").append(address);
        h.append("</div><div class='divider'></div>")
         .append("<div class='check'>✓</div>")
         .append("<div class='success'>").append(isArabic ? "تم البيع بنجاح" : "Sale Completed").append("</div>");

        try {
            JSONObject t = new JSONObject(receiptJsonStr).optJSONObject("transaction");
            if (t == null) t = new JSONObject(receiptJsonStr);
            h.append("<div class='row'><span class='label'>").append(isArabic ? "رقم العملية" : "Receipt #").append("</span><span class='value'>#").append(t.optString("sale_id", "—")).append("</span></div>");
            h.append("<div class='row'><span class='label'>").append(isArabic ? "التاريخ" : "Date").append("</span><span class='value'>").append(t.optString("timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()))).append("</span></div>");
            h.append("<div class='row'><span class='label'>").append(isArabic ? "العميل" : "Customer").append("</span><span class='value'>").append(t.optString("customer_name", "—")).append("</span></div>");

            JSONArray items = t.optJSONArray("items");
            if (items != null && items.length() > 0) {
                h.append("<table><tr><th>#</th><th>").append(isArabic ? "القطعة" : "Item").append("</th><th>").append(isArabic ? "العيار" : "Karat").append("</th><th>").append(isArabic ? "السعر" : "Price").append("</th></tr>");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it == null) continue;
                    h.append("<tr><td>").append(i + 1).append("</td><td>").append(it.optString("title", "—")).append("</td><td>").append(it.optString("karat", "—")).append("</td><td>").append(String.format(Locale.US, "%,.0f", it.optDouble("price", 0))).append("</td></tr>");
                }
                h.append("</table>");
            }

            double amt = t.optDouble("final_amount", t.optDouble("subtotal", 0));
            h.append("<div class='total-box'><div style='font-size:10px;opacity:0.8'>").append(isArabic ? "المبلغ النهائي" : "Final Amount").append("</div><div class='total'>").append(String.format(Locale.US, "%,.0f YER", amt)).append("</div></div>");
        } catch (Exception e) {
            h.append("<p style='color:red;text-align:center'>").append(isArabic ? "خطأ في البيانات" : "Data error").append("</p>");
        }

        h.append("<div class='footer'>").append(orgName).append("<br>MIRA Jewelry System © 2026</div></body></html>");
        webReceipt.loadDataWithBaseURL(null, h.toString(), "text/html", "UTF-8", null);
    }

    private void closeFragment() {
        if (!isAdded()) return;
        dismissAllowingStateLoss(); // إغلاق الـ Dialog بشكل آمن
    }
}
