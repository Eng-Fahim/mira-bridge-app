package com.example.uhf.fragment;

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
import androidx.fragment.app.Fragment;

import com.example.uhf.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

public class MiraReceiptFragment extends Fragment {

    private static final String TAG = "MiraReceiptFragment";
    private static final String ARG_RECEIPT_JSON = "receipt_json";

    private String receiptJsonStr = "{}";

    // UI Elements matching XML
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

    private void setupListeners() {
        if (btnCloseReceipt != null) {
            btnCloseReceipt.setOnClickListener(v -> closeFragment());
        }

        if (btnPrintReceipt != null) {
            btnPrintReceipt.setOnClickListener(v -> {
                if (getContext() != null) {
                    Toast.makeText(getContext(), "🖨️ جاري إرسال الأمر للطباعة...", Toast.LENGTH_SHORT).show();
                }
            });
        }

        if (btnShareReceipt != null) {
            btnShareReceipt.setOnClickListener(v -> {
                if (getContext() != null) {
                    Toast.makeText(getContext(), "📤 مشاركة الإيصال...", Toast.LENGTH_SHORT).show();
                }
            });
        }

        if (btnSaveReceipt != null) {
            btnSaveReceipt.setOnClickListener(v -> {
                if (getContext() != null) {
                    Toast.makeText(getContext(), "💾 تم حفظ الإيصال", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void renderHtmlReceipt() {
        if (webReceipt == null) return;

        StringBuilder html = new StringBuilder();
        html.append("<html><head><style>")
            .append("body { background-color: #0B0F17; color: #FFFFFF; font-family: sans-serif; padding: 15px; } ")
            .append(".header { text-align: center; border-bottom: 1px solid #374151; padding-bottom: 10px; margin-bottom: 15px; } ")
            .append(".row { display: flex; justify-content: space-between; margin-bottom: 8px; font-size: 14px; } ")
            .append(".table { width: 100%; border-collapse: collapse; margin-top: 15px; } ")
            .append(".table th, .table td { border-bottom: 1px solid #1F2937; padding: 8px; text-align: right; font-size: 13px; } ")
            .append(".total { border-top: 2px dashed #4ADE80; font-weight: bold; color: #4ADE80; margin-top: 15px; padding-top: 10px; } ")
            .append("</style></head><body>");

        try {
            JSONObject root = new JSONObject(receiptJsonStr);
            String receiptId = root.optString("receipt_id", root.optString("transaction_id", "INV-" + System.currentTimeMillis() / 1000));
            String date = root.optString("created_at", root.optString("date", "الآن"));

            html.append("<div class='header'><h2>فاتورة مبيعات</h2><p>رقم: ").append(receiptId).append("<br>التاريخ: ").append(date).append("</p></div>");

            JSONObject customer = root.optJSONObject("customer");
            if (customer != null) {
                html.append("<div class='row'><span>العميل:</span><span>").append(customer.optString("name", "عميل عام")).append("</span></div>");
                html.append("<div class='row'><span>الهاتف:</span><span>").append(customer.optString("phone", "-")).append("</span></div>");
                html.append("<div class='row'><span>طريقة الدفع:</span><span>").append(customer.optString("payment_method", "CASH")).append("</span></div>");
            }

            html.append("<table class='table'><thead><tr><th>القطعة</th><th>العيار/الوزن</th><th>المبلغ</th></tr></thead><tbody>");

            JSONArray items = root.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    html.append("<tr>")
                        .append("<td>").append(item.optString("title", "قطعة ذهب")).append("</td>")
                        .append("<td>").append(item.optString("karat", "21K")).append(" / ").append(String.format(Locale.US, "%.2fg", item.optDouble("weight", 0.0))).append("</td>")
                        .append("<td>").append(String.format(Locale.US, "$%,.2f", item.optDouble("sale_price", 0.0))).append("</td>")
                        .append("</tr>");
                }
            }
            html.append("</tbody></table>");

            JSONObject payment = root.optJSONObject("payment");
            if (payment != null) {
                double subtotal = payment.optDouble("subtotal", 0.0);
                double discount = payment.optDouble("discount_percent", 0.0);
                double finalAmount = payment.optDouble("final_amount", 0.0);

                html.append("<div class='row' style='margin-top:15px;'><span>الإجمالي:</span><span>").append(String.format(Locale.US, "$%,.2f", subtotal)).append("</span></div>");
                html.append("<div class='row'><span>الخصم:</span><span>").append(String.format(Locale.US, "%.1f%%", discount)).append("</span></div>");
                html.append("<div class='row total'><span>الصافي النهائي:</span><span>").append(String.format(Locale.US, "$%,.2f", finalAmount)).append("</span></div>");
            }

        } catch (Exception e) {
            Log.e(TAG, "Error generating HTML: " + e.getMessage());
            html.append("<p style='color:#EF4444;'>خطأ في قراءة بيانات الفاتورة</p>");
        }

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
