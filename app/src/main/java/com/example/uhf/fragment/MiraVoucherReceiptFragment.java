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
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.manager.MiraOrgProfileManager;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MiraVoucherReceiptFragment extends BottomSheetDialogFragment {

    private static final String TAG = "VoucherReceipt";
    private static final String ARG_VOUCHER_ID = "voucher_id";

    private int voucherId;
    private boolean isArabic = true;
    private WebView webView;
    private ProgressBar progressBar;
    private String orgName = "MIRA ID";
    private MiraOrgProfileManager orgProfile;

    public static MiraVoucherReceiptFragment newInstance(int voucherId) {
        MiraVoucherReceiptFragment f = new MiraVoucherReceiptFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_VOUCHER_ID, voucherId);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) voucherId = getArguments().getInt(ARG_VOUCHER_ID, 0);
        isArabic = Locale.getDefault().getLanguage().equals("ar");
        if (getContext() != null) {
            orgProfile = MiraOrgProfileManager.getInstance(getContext());
            orgName = orgProfile.getDisplayName();
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_voucher_receipt_wrapper, container, false);
        webView = v.findViewById(R.id.webVoucherReceipt);
        progressBar = v.findViewById(R.id.progressVoucherLoading);

        // ✅ تم تصحيح التكرار هنا وحذف الأسطر الزائدة
        MaterialButton btnPrint = v.findViewById(R.id.btnPrintVoucher);
        MaterialButton btnShare = v.findViewById(R.id.btnShareVoucher);
        MaterialButton btnSave = v.findViewById(R.id.btnSaveVoucherPDF);
        MaterialButton btnDetail = v.findViewById(R.id.btnViewJournalDetail); // ✅ زر التفاصيل

        if (webView != null) {
            WebSettings s = webView.getSettings();
            s.setJavaScriptEnabled(true);
            s.setUseWideViewPort(true);
            s.setLoadWithOverviewMode(true);
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    if (progressBar != null) progressBar.setVisibility(View.GONE);
                }
            });
        }

        btnPrint.setOnClickListener(v2 -> printVoucher());
        btnShare.setOnClickListener(v2 -> shareVoucherPDF());
        btnSave.setOnClickListener(v2 -> saveVoucherPDF());

        // ✅ فتح التفاصيل
        btnDetail.setOnClickListener(v2 -> {
            MiraVoucherDetailFragment detail = MiraVoucherDetailFragment.newInstance(voucherId, "receipt");
            detail.show(getParentFragmentManager(), "VoucherDetail");
            dismiss();
        });

        loadVoucherData();
        return v;
    }

    private void loadVoucherData() {
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        new Thread(() -> {
            MiraApiClient api = MiraApiClient.getInstance(requireContext());
            MiraApiClient.ApiResponse response = api.getVoucherDetail(voucherId);
            
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (response.isSuccess && response.data != null) {
                    renderReceipt(response.data);
                } else {
                    Toast.makeText(requireContext(), "فشل تحميل السند", Toast.LENGTH_SHORT).show();
                    if (progressBar != null) progressBar.setVisibility(View.GONE);
                }
            });
        }).start();
    }

    private void renderReceipt(JSONObject data) {
        try {
            JSONObject voucher = data.optJSONObject("voucher");
            JSONArray payments = data.optJSONArray("payments");
            if (voucher == null) return;

            String voucherType = voucher.optString("voucher_type", "receipt");
            boolean isReceipt = "receipt".equals(voucherType);
            String title = isReceipt ? "سند قبض" : "سند صرف";
            String icon = isReceipt ? "📥" : "📤";
            String titleColor = isReceipt ? "#16A34A" : "#DC2626";
            
            String voucherNumber = voucher.optString("voucher_number");
            String date = voucher.optString("created_at");
            String accountName = voucher.optString("account_name_ar", "—");
            String accountCode = voucher.optString("account_code", "");
            String notes = voucher.optString("notes", "");
            String paymentMethod = voucher.optString("payment_method", "cash");
            
            double amountMoney = voucher.optDouble("amount_money", 0);
            double amountGold = voucher.optDouble("amount_gold_grams", 0);
            String goldKarat = voucher.optString("gold_karat", "");

            String phone = orgProfile != null ? orgProfile.getOrgPhone() : "";
            String address = orgProfile != null ? orgProfile.getOrgAddress() : "";
            String dir = isArabic ? "rtl" : "ltr";

            StringBuilder h = new StringBuilder();
            h.append("<!DOCTYPE html><html dir='").append(dir).append("'><head>")
             .append("<meta charset='UTF-8'>")
             .append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>")
             .append("<style>")
             .append("* { box-sizing: border-box; }")
             .append("body { font-family: 'Segoe UI', Tahoma, sans-serif; padding: 16px; margin: 0; background: #fff; color: #1a1a1a; font-size: 12px; line-height: 1.5; }")
             .append(".header { text-align: center; border-bottom: 3px solid ").append(titleColor).append("; padding-bottom: 12px; margin-bottom: 16px; }")
             .append(".org-name { font-size: 24px; font-weight: bold; color: #0d1b2a; }")
             .append(".org-info { font-size: 10px; color: #666; margin-top: 4px; }")
             .append(".voucher-title { display: flex; justify-content: space-between; align-items: center; background: ").append(titleColor).append("; color: #fff; padding: 12px 16px; border-radius: 8px; margin: 16px 0; }")
             .append(".voucher-title h1 { margin: 0; font-size: 20px; }")
             .append(".voucher-title .num { font-size: 12px; background: rgba(255,255,255,0.2); padding: 4px 10px; border-radius: 4px; }")
             .append(".info-box { border: 1px solid #e0e0e0; border-radius: 8px; padding: 12px; margin: 12px 0; background: #fafafa; }")
             .append(".info-row { display: flex; justify-content: space-between; padding: 6px 0; border-bottom: 1px dashed #e0e0e0; }")
             .append(".info-row:last-child { border-bottom: none; }")
             .append(".label { color: #666; font-weight: normal; }")
             .append(".value { color: #0d1b2a; font-weight: bold; }")
             .append(".payments-table { width: 100%; border-collapse: collapse; margin: 16px 0; }")
             .append(".payments-table th { background: #0d1b2a; color: #f4d58d; padding: 10px; text-align: center; font-size: 11px; }")
             .append(".payments-table td { padding: 10px; text-align: center; border-bottom: 1px solid #eee; }")
             .append(".payments-table tr:nth-child(even) { background: #fafafa; }")
             .append(".total-box { background: ").append(titleColor).append("; color: #fff; padding: 16px; border-radius: 8px; margin: 16px 0; text-align: center; }")
             .append(".total-box .label { color: rgba(255,255,255,0.85); font-size: 13px; }")
             .append(".total-box .amount { color: #fff; font-size: 26px; font-weight: bold; margin-top: 4px; }")
             .append(".notes-box { border: 1px dashed #ccc; padding: 12px; border-radius: 8px; margin: 16px 0; background: #fff8e1; font-size: 11px; }")
             .append(".signatures { display: flex; justify-content: space-between; margin-top: 40px; gap: 20px; }")
             .append(".sig-box { flex: 1; text-align: center; }")
             .append(".sig-line { border-top: 1px solid #333; margin-top: 40px; padding-top: 6px; font-size: 11px; color: #666; }")
             .append(".footer { text-align: center; margin-top: 24px; font-size: 9px; color: #999; border-top: 1px solid #eee; padding-top: 8px; }")
             .append(".stamp { position: relative; display: inline-block; border: 2px dashed #ccc; padding: 20px 40px; border-radius: 50%; color: #999; font-size: 10px; margin-top: 16px; }")
             .append("</style></head><body>");

            // Header
            h.append("<div class='header'>")
             .append("<div class='org-name'>").append(escape(orgName)).append("</div>")
             .append("<div class='org-info'>📍 ").append(escape(address.isEmpty() ? "صنعاء - اليمن" : address))
             .append(" | 📞 ").append(escape(phone)).append("</div>")
             .append("</div>");

            // Title Bar
            h.append("<div class='voucher-title'>")
             .append("<h1>").append(icon).append(" ").append(title).append("</h1>")
             .append("<div class='num'># ").append(escape(voucherNumber)).append("</div>")
             .append("</div>");

            // Info
            h.append("<div class='info-box'>")
             .append("<div class='info-row'><span class='label'>التاريخ:</span><span class='value'>").append(escape(date)).append("</span></div>")
             .append("<div class='info-row'><span class='label'>الحساب:</span><span class='value'>").append(escape(accountCode + " - " + accountName)).append("</span></div>");
            
            if (!notes.isEmpty()) {
                h.append("<div class='info-row'><span class='label'>البيان:</span><span class='value'>").append(escape(notes)).append("</span></div>");
            }
            
            h.append("</div>");

            // Payments Table
            if (payments != null && payments.length() > 0) {
                h.append("<table class='payments-table'>")
                 .append("<thead><tr>")
                 .append("<th>العملة</th>")
                 .append("<th>المبلغ</th>")
                 .append("<th>طريقة الدفع</th>")
                 .append("<th>المعادل (YER)</th>")
                 .append("</tr></thead><tbody>");

                for (int i = 0; i < payments.length(); i++) {
                    JSONObject p = payments.optJSONObject(i);
                    if (p == null) continue;
                    String currency = p.optString("currency", "YER");
                    double amount = p.optDouble("amount", 0);
                    double baseAmount = p.optDouble("base_amount_yer", 0);
                    String method = p.optString("payment_method", "cash");
                    
                    String methodAr = "نقداً";
                    if ("bank_transfer".equals(method) || "transfer".equals(method)) methodAr = "تحويل بنكي";
                    else if ("e_wallet".equals(method) || "network".equals(method)) methodAr = "محفظة/شبكة";
                    else if ("check".equals(method) || "cheque".equals(method)) methodAr = "شيك";
                    else if ("card".equals(method)) methodAr = "بطاقة";
                    
                    String icon2 = "🇾🇪";
                    if ("USD".equals(currency)) icon2 = "🇺🇸";
                    else if ("SAR".equals(currency)) icon2 = "🇸🇦";
                    
                    h.append("<tr>")
                     .append("<td>").append(icon2).append(" ").append(currency).append("</td>")
                     .append("<td>").append(String.format(Locale.US, "%,.2f", amount)).append("</td>")
                     .append("<td>").append(methodAr).append("</td>")
                     .append("<td><b>").append(String.format(Locale.US, "%,.2f", baseAmount)).append("</b></td>")
                     .append("</tr>");
                }
                h.append("</tbody></table>");
            }

            // Gold (if any)
            if (amountGold > 0) {
                h.append("<div class='info-box'>")
                 .append("<div class='info-row'><span class='label'>⚖️ الوزن الذهبي:</span><span class='value'>")
                 .append(String.format(Locale.US, "%.3f g", amountGold))
                 .append(!goldKarat.isEmpty() ? " (" + escape(goldKarat) + ")" : "")
                 .append("</span></div></div>");
            }

            // Total
            if (amountMoney > 0) {
                h.append("<div class='total-box'>")
                 .append("<div class='label'>إجمالي المبلغ</div>")
                 .append("<div class='amount'>").append(String.format(Locale.US, "%,.2f", amountMoney)).append(" YER</div>")
                 .append("</div>");
            }

            // Signatures
            h.append("<div class='signatures'>")
             .append("<div class='sig-box'><div class='sig-line'>توقيع المستلم</div></div>")
             .append("<div class='sig-box'><div class='sig-line'>توقيع وختم المحل</div></div>")
             .append("</div>");

            // Footer
            h.append("<div class='footer'>")
             .append("هذا السند صادر إلكترونياً من نظام MIRA ID™ | تاريخ الطباعة: ")
             .append(new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(new Date()))
             .append("</div>");

            h.append("</body></html>");

            webView.loadDataWithBaseURL(null, h.toString(), "text/html", "UTF-8", null);

        } catch (Exception e) {
            Log.e(TAG, "renderReceipt error", e);
        }
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private void printVoucher() {
        try {
            PrintManager pm = (PrintManager) requireContext().getSystemService(Context.PRINT_SERVICE);
            PrintDocumentAdapter adapter = webView.createPrintDocumentAdapter("Voucher");
            pm.print("Voucher_" + voucherId, adapter, new PrintAttributes.Builder().build());
        } catch (Exception e) {
            Toast.makeText(requireContext(), "فشل الطباعة", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareVoucherPDF() {
        Toast.makeText(requireContext(), "⏳ جاري التجهيز...", Toast.LENGTH_SHORT).show();
        webView.postDelayed(() -> {
            try {
                File pdf = generatePDF();
                if (pdf != null && pdf.exists()) {
                    Uri uri = FileProvider.getUriForFile(requireContext(), 
                        requireContext().getPackageName() + ".fileprovider", pdf);
                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("application/pdf");
                    shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(shareIntent, "مشاركة السند"));
                }
            } catch (Exception e) {
                Log.e(TAG, "share error", e);
            }
        }, 500);
    }

    private void saveVoucherPDF() {
        Toast.makeText(requireContext(), "⏳ جاري الحفظ...", Toast.LENGTH_SHORT).show();
        webView.postDelayed(() -> {
            try {
                File pdf = generatePDF();
                if (pdf != null) {
                    Toast.makeText(requireContext(), "✅ تم الحفظ: " + pdf.getName(), Toast.LENGTH_LONG).show();
                }
            } catch (Exception e) {
                Toast.makeText(requireContext(), "❌ فشل الحفظ", Toast.LENGTH_SHORT).show();
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

            String fileName = "Voucher_" + voucherId + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".pdf";
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MIRA_Vouchers");
            if (!dir.exists()) dir.mkdirs();

            File file = new File(dir, fileName);
            FileOutputStream fos = new FileOutputStream(file);
            doc.writeTo(fos);
            doc.close();
            fos.close();
            return file;
        } catch (Exception e) {
            return null;
        }
    }

    private Bitmap captureWebView() {
        if (webView == null) return null;
        try {
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            );
            webView.layout(0, 0, webView.getMeasuredWidth(), webView.getMeasuredHeight());
            Bitmap bitmap = Bitmap.createBitmap(webView.getMeasuredWidth(), webView.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(android.graphics.Color.WHITE);
            webView.draw(canvas);
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }
}
