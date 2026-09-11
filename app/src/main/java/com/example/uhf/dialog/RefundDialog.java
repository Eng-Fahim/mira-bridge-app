package com.example.uhf.dialog;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.example.uhf.R;
import com.google.android.material.button.MaterialButton;

public class RefundDialog extends Dialog {

    public interface RefundCallback {
        void onRefundConfirm(String code, String reason);
        void onBarcodeScan();
        void onCodeChanged(String code);
    }

    private EditText etRefundCode;
    private EditText etReason;
    private TextView tvRefundItemTitle;
    private TextView tvRefundItemStatus;
    private android.view.View cardRefundItemInfo;
    private RefundCallback callback;
    private boolean isRefundable = false;
    private boolean isArabic;

    public RefundDialog(@NonNull Context context, RefundCallback callback) {
        super(context);
        this.callback = callback;
        this.isArabic = context.getResources().getConfiguration().locale.getLanguage().equals("ar");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_refund);
        
        if (getWindow() != null) {
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        }
        
        initViews();
        setupListeners();
    }

    private void initViews() {
        etRefundCode = findViewById(R.id.etRefundScanCode);
        etReason = findViewById(R.id.etRefundReason);
        tvRefundItemTitle = findViewById(R.id.tvRefundItemTitle);
        tvRefundItemStatus = findViewById(R.id.tvRefundItemStatus);
        cardRefundItemInfo = findViewById(R.id.cardRefundItemInfo);
        MaterialButton btnScanBarcode = findViewById(R.id.btnRefundScanBarcode);
        MaterialButton btnCancel = findViewById(R.id.btnRefundCancel);
        MaterialButton btnConfirm = findViewById(R.id.btnRefundConfirm);
        
        btnCancel.setOnClickListener(v -> dismiss());
        
        btnScanBarcode.setOnClickListener(v -> {
            if (callback != null) callback.onBarcodeScan();
        });
        
        btnConfirm.setOnClickListener(v -> {
            String code = etRefundCode.getText().toString().trim();
            String reason = etReason.getText().toString().trim();
            
            if (code.isEmpty()) {
                Toast.makeText(getContext(), isArabic ? "⚠️ أدخل رمز القطعة" : "⚠️ Enter item code", 
                              Toast.LENGTH_SHORT).show();
                return;
            }
            
            if (!isRefundable) {
                Toast.makeText(getContext(), isArabic ? "⚠️ لا يمكن إرجاع هذه القطعة" : "⚠️ Item not refundable", 
                              Toast.LENGTH_SHORT).show();
                return;
            }
            
            if (callback != null) {
                callback.onRefundConfirm(code, reason.isEmpty() ? 
                    (isArabic ? "مرتجع مبيعات" : "Sales Refund") : reason);
            }
        });
        
        etRefundCode.addTextChangedListener(new TextWatcher() {
    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
        if (s.length() >= 4) {
            // سيتم البحث من الـ Fragment   ← ❌ تعليق فقط، لا شيء يحدث!
        } else {
            hideRefundItemInfo();
        }
    }
    @Override public void afterTextChanged(Editable s) {}
    });
    }

    private void setupListeners() {
        setOnDismissListener(dialog -> {
            etRefundCode = null;
            tvRefundItemTitle = null;
            tvRefundItemStatus = null;
            cardRefundItemInfo = null;
            isRefundable = false;
        });
        
        setCancelable(true);
        setCanceledOnTouchOutside(true);
    }

    public void showRefundItemInfo(String title, String price) {
        if (cardRefundItemInfo != null) cardRefundItemInfo.setVisibility(android.view.View.VISIBLE);
        if (tvRefundItemTitle != null) tvRefundItemTitle.setText(title + " (" + price + ")");
        if (tvRefundItemStatus != null) {
            tvRefundItemStatus.setText(isArabic ? "✅ القطعة مباعة وقابلة للإرجاع" : "✅ Item sold and refundable");
            tvRefundItemStatus.setTextColor(Color.parseColor("#2ECC71"));
        }
        isRefundable = true;
    }

    public void showRefundItemError(String message) {
        if (cardRefundItemInfo != null) cardRefundItemInfo.setVisibility(android.view.View.VISIBLE);
        if (tvRefundItemTitle != null) tvRefundItemTitle.setText(message);
        if (tvRefundItemStatus != null) {
            tvRefundItemStatus.setText(isArabic ? "غير قابلة للإرجاع ❌" : "Not Refundable ❌");
            tvRefundItemStatus.setTextColor(Color.parseColor("#E74C3C"));
        }
        isRefundable = false;
    }

    private void hideRefundItemInfo() {
        if (cardRefundItemInfo != null) cardRefundItemInfo.setVisibility(android.view.View.GONE);
        isRefundable = false;
    }
}
