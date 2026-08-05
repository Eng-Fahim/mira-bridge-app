package com.example.uhf.fragment;

import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
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

    private String receiptJsonStr = "";

    // UI Elements
    private TextView tvReceiptId, tvReceiptDate, tvCustomerName, tvCustomerPhone;
    private TextView tvPaymentMethod, tvSubtotal, tvDiscount, tvFinalTotal;
    private LinearLayout containerItems;
    private Button btnCloseReceipt, btnPrintReceipt;

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
        parseAndDisplayReceipt();
    }

    private void bindViews(View view) {
        tvReceiptId = view.findViewById(R.id.tvReceiptId);
        tvReceiptDate = view.findViewById(R.id.tvReceiptDate);
        tvCustomerName = view.findViewById(R.id.tvReceiptCustomerName);
        tvCustomerPhone = view.findViewById(R.id.tvReceiptCustomerPhone);
        tvPaymentMethod = view.findViewById(R.id.tvReceiptPaymentMethod);
        tvSubtotal = view.findViewById(R.id.tvReceiptSubtotal);
        tvDiscount = view.findViewById(R.id.tvReceiptDiscount);
        tvFinalTotal = view.findViewById(R.id.tvReceiptFinalTotal);
        containerItems = view.findViewById(R.id.containerReceiptItems);
        btnCloseReceipt = view.findViewById(R.id.btnCloseReceipt);
        btnPrintReceipt = view.findViewById(R.id.btnPrintReceipt);
    }

    private void setupListeners() {
        // ⚡ معالجة خروج وآمن للـ Fragment لعدم التسبب في انهيار بالنافذة الخلفية
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
    }

    private void parseAndDisplayReceipt() {
        try {
            JSONObject root = new JSONObject(receiptJsonStr);

            // استخراج تفاصيل السند الأساسية
            String receiptId = root.optString("receipt_id", root.optString("transaction_id", "INV-" + System.currentTimeMillis() / 1000));
            String date = root.optString("created_at", root.optString("date", "الآن"));

            if (tvReceiptId != null) tvReceiptId.setText("رقم الفاتورة: " + receiptId);
            if (tvReceiptDate != null) tvReceiptDate.setText("التاريخ: " + date);

            // بيانات العميل والدفع
            JSONObject customer = root.optJSONObject("customer");
            if (customer != null) {
                if (tvCustomerName != null) tvCustomerName.setText(customer.optString("name", "عميل عام"));
                if (tvCustomerPhone != null) tvCustomerPhone.setText(customer.optString("phone", "-"));
                if (tvPaymentMethod != null) tvPaymentMethod.setText(customer.optString("payment_method", "نقداً"));
            }

            // البيانات المالية
            JSONObject payment = root.optJSONObject("payment");
            if (payment != null) {
                double subtotal = payment.optDouble("subtotal", 0.0);
                double discount = payment.optDouble("discount_percent", 0.0);
                double finalAmount = payment.optDouble("final_amount", 0.0);

                if (tvSubtotal != null) tvSubtotal.setText(String.format(Locale.US, "$%,.2f", subtotal));
                if (tvDiscount != null) tvDiscount.setText(String.format(Locale.US, "%.1f%%", discount));
                if (tvFinalTotal != null) tvFinalTotal.setText(String.format(Locale.US, "$%,.2f", finalAmount));
            }

            // عرض العناصر في القائمة
            JSONArray items = root.optJSONArray("items");
            if (items != null && containerItems != null && getContext() != null) {
                containerItems.removeAllViews();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    View itemView = LayoutInflater.from(getContext()).inflate(R.layout.item_receipt_row, containerItems, false);

                    TextView tvTitle = itemView.findViewById(R.id.tvReceiptItemTitle);
                    TextView tvDetails = itemView.findViewById(R.id.tvReceiptItemDetails);
                    TextView tvPrice = itemView.findViewById(R.id.tvReceiptItemPrice);

                    String title = item.optString("title", "قطعة ذهب");
                    String karat = item.optString("karat", "21K");
                    double weight = item.optDouble("weight", 0.0);
                    double price = item.optDouble("sale_price", 0.0);

                    if (tvTitle != null) tvTitle.setText(title);
                    if (tvDetails != null) tvDetails.setText(String.format(Locale.US, "%s | %.2fg", karat, weight));
                    if (tvPrice != null) tvPrice.setText(String.format(Locale.US, "$%,.2f", price));

                    containerItems.addView(itemView);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing receipt JSON: " + e.getMessage());
        }
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
