package com.example.uhf.fragment;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.print.PrintHelper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MiraScrapReceiptFragment extends BottomSheetDialogFragment {

    private static final String ARG_PURCHASE_ID = "purchase_id";
    private static final String ARG_RAW_JSON = "raw_json";

    private int purchaseId;
    private String rawJson;
    private boolean isArabic = true;
    private View receiptView;

    // Views
    private TextView tvCompanyName, tvBranchName, tvBranchPhone;
    private TextView tvReceiptNumber, tvDateTime, tvSellerName;
    private TextView tvCustomerName, tvCustomerPhone;
    private TextView tvWeight, tvKarat, tvNetWeight;
    private TextView tvMarketPrice, tvDeduction, tvTotalAmount, tvPaymentMethod;
    private LinearLayout rowCustomerName, rowCustomerPhone;
    private MaterialButton btnPrint, btnShare;

    public static MiraScrapReceiptFragment newInstance(int purchaseId, String rawJson) {
        MiraScrapReceiptFragment f = new MiraScrapReceiptFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_PURCHASE_ID, purchaseId);
        args.putString(ARG_RAW_JSON, rawJson);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            purchaseId = getArguments().getInt(ARG_PURCHASE_ID, 0);
            rawJson = getArguments().getString(ARG_RAW_JSON, null);
        }
        isArabic = Locale.getDefault().getLanguage().equals("ar");
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_scrap_receipt_wrapper, container, false);
        
        receiptView = view.findViewById(R.id.receiptContainer);
        btnPrint = view.findViewById(R.id.btnPrintReceipt);
        btnShare = view.findViewById(R.id.btnShareReceipt);

        initViews(view);
        loadReceiptData();

        btnPrint.setOnClickListener(v -> printReceipt());
        btnShare.setOnClickListener(v -> shareReceipt());

        return view;
    }

    private void initViews(View v) {
        tvCompanyName     = v.findViewById(R.id.tvCompanyName);
        tvBranchName      = v.findViewById(R.id.tvBranchName);
        tvBranchPhone     = v.findViewById(R.id.tvBranchPhone);
        tvReceiptNumber   = v.findViewById(R.id.tvReceiptNumber);
        tvDateTime        = v.findViewById(R.id.tvDateTime);
        tvSellerName      = v.findViewById(R.id.tvSellerName);
        tvCustomerName    = v.findViewById(R.id.tvCustomerName);
        tvCustomerPhone   = v.findViewById(R.id.tvCustomerPhone);
        tvWeight          = v.findViewById(R.id.tvWeight);
        tvKarat           = v.findViewById(R.id.tvKarat);
        tvNetWeight       = v.findViewById(R.id.tvNetWeight);
        tvMarketPrice     = v.findViewById(R.id.tvMarketPrice);
        tvDeduction       = v.findViewById(R.id.tvDeduction);
        tvTotalAmount     = v.findViewById(R.id.tvTotalAmount);
        tvPaymentMethod   = v.findViewById(R.id.tvPaymentMethod);
        rowCustomerName   = v.findViewById(R.id.rowCustomerName);
        rowCustomerPhone  = v.findViewById(R.id.rowCustomerPhone);
    }

    private void loadReceiptData() {
        // أولاً: عرض البيانات الفورية من الـ JSON (لتجربة سريعة)
        if (rawJson != null) {
            try {
                JSONObject data = new JSONObject(rawJson);
                renderReceipt(data);
            } catch (Exception ignored) {}
        }

        // ثانياً: جلب البيانات الكاملة من السيرفر (نسخة محدثة)
        if (purchaseId > 0) {
            new Thread(() -> {
                MiraApiClient api = MiraApiClient.getInstance(requireContext());
                MiraApiClient.ApiResponse response = api.getScrapPurchaseReceipt(purchaseId);

                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    if (response.isSuccess && response.data != null) {
                        JSONObject data = response.data.optJSONObject("data");
                        if (data != null) renderReceipt(data);
                    }
                });
            }).start();
        }
    }

    private void renderReceipt(JSONObject data) {
        try {
            JSONObject purchase = data.optJSONObject("purchase");
            JSONObject branch   = data.optJSONObject("branch");
            JSONObject company  = data.optJSONObject("company");
            
            if (purchase == null) return;

            // Company & Branch
            if (company != null) {
                tvCompanyName.setText(company.optString("name_ar", "MIRA ID™"));
            }
            if (branch != null) {
                tvBranchName.setText(branch.optString("branch_name_ar", branch.optString("branch_name", "الفرع الرئيسي")));
                String phone = branch.optString("phone", "");
                tvBranchPhone.setText(phone.isEmpty() ? "" : "📞 " + phone);
            }

            // Receipt Info
            tvReceiptNumber.setText(purchase.optString("purchase_number"));
            tvDateTime.setText(formatDateTime(purchase.optString("created_at")));
            tvSellerName.setText(data.optString("seller_name", ""));

            // Customer
            String customerName  = purchase.optString("customer_name", "");
            String customerPhone = purchase.optString("customer_phone", "");
            
            if (!customerName.isEmpty()) {
                rowCustomerName.setVisibility(View.VISIBLE);
                tvCustomerName.setText(customerName);
            } else {
                rowCustomerName.setVisibility(View.GONE);
            }
            
            if (!customerPhone.isEmpty()) {
                rowCustomerPhone.setVisibility(View.VISIBLE);
                tvCustomerPhone.setText(customerPhone);
            } else {
                rowCustomerPhone.setVisibility(View.GONE);
            }

            // Gold Details
            double weight    = purchase.optDouble("gross_weight_grams", 0);
            double netWeight = purchase.optDouble("net_pure_weight_24k", 0);
            
            tvWeight.setText(String.format(Locale.US, "%.3f g", weight));
            tvKarat.setText(purchase.optString("gold_karat", "21K"));
            tvNetWeight.setText(String.format(Locale.US, "%.3f g", netWeight));

            // Price Details
            double marketPrice = purchase.optDouble("market_price_per_gram", 0);
            double deduction   = purchase.optDouble("deduction_per_gram", 0);
            double totalAmount = purchase.optDouble("total_amount", 0);
            String currency    = purchase.optString("currency", "SAR");
            String payMethod   = purchase.optString("payment_method", "cash");
            
            tvMarketPrice.setText(String.format(Locale.US, "%,.2f %s", marketPrice, currency));
            tvDeduction.setText(String.format(Locale.US, "%,.2f %s", deduction, currency));
            tvTotalAmount.setText(String.format(Locale.US, "%,.2f %s", totalAmount, currency));
            
            String payMethodAr = "نقداً";
            switch (payMethod.toLowerCase()) {
                case "card":     payMethodAr = "بطاقة"; break;
                case "transfer": payMethodAr = "تحويل بنكي"; break;
                case "cheque":   payMethodAr = "شيك"; break;
            }
            tvPaymentMethod.setText(payMethodAr);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String formatDateTime(String dateStr) {
        try {
            SimpleDateFormat input  = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            SimpleDateFormat output = new SimpleDateFormat("yyyy/MM/dd - HH:mm", Locale.getDefault());
            Date date = input.parse(dateStr);
            return date != null ? output.format(date) : dateStr;
        } catch (Exception e) {
            return dateStr;
        }
    }

    // ═══════════════════════════════════════════════════════
    //  طباعة PDF
    // ═══════════════════════════════════════════════════════
    private void printReceipt() {
        try {
            Bitmap bitmap = captureReceiptAsBitmap();
            if (bitmap == null) {
                Toast.makeText(requireContext(), "فشل تحضير الإيصال", Toast.LENGTH_SHORT).show();
                return;
            }

            PrintManager printManager = (PrintManager) requireContext().getSystemService(Context.PRINT_SERVICE);
            if (printManager == null) {
                Toast.makeText(requireContext(), "خدمة الطباعة غير متوفرة", Toast.LENGTH_SHORT).show();
                return;
            }

            String jobName = "SCRAP-" + tvReceiptNumber.getText();
            
            printManager.print(jobName, 
                new PrintHelper(requireContext()).getPrintDocumentAdapter(jobName, bitmap),
                new PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A5)
                    .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                    .build());
                
        } catch (Exception e) {
            Toast.makeText(requireContext(), "خطأ: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private Bitmap captureReceiptAsBitmap() {
        try {
            View view = receiptView;
            if (view == null) return null;

            Bitmap bitmap = Bitmap.createBitmap(
                view.getWidth() > 0 ? view.getWidth() : 800,
                view.getHeight() > 0 ? view.getHeight() : 1200,
                Bitmap.Config.ARGB_8888
            );
            
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(0xFFFFFFFF);
            view.draw(canvas);
            
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════
    //  مشاركة (PNG عبر WhatsApp/Email)
    // ═══════════════════════════════════════════════════════
    private void shareReceipt() {
        try {
            Bitmap bitmap = captureReceiptAsBitmap();
            if (bitmap == null) {
                Toast.makeText(requireContext(), "فشل تحضير الإيصال", Toast.LENGTH_SHORT).show();
                return;
            }

            File cacheDir = new File(requireContext().getCacheDir(), "receipts");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            
            File file = new File(cacheDir, "receipt_" + purchaseId + ".png");
            FileOutputStream fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();

            Uri uri = FileProvider.getUriForFile(
                requireContext(),
                requireContext().getPackageName() + ".fileprovider",
                file
            );

            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("image/png");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_TEXT, "إيصال شراء كسر ذهب - MIRA ID™");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            
            startActivity(Intent.createChooser(intent, "مشاركة الإيصال"));
            
        } catch (Exception e) {
            Toast.makeText(requireContext(), "خطأ: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
