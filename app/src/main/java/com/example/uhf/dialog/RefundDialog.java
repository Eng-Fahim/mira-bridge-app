package com.example.uhf.dialog;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.example.uhf.R;
import com.google.android.material.button.MaterialButton;

/**
 * RefundDialog - نافذة إرجاع القطع
 *
 * ✅ الميزات:
 * - البحث التلقائي عند تغيير الكود (مع debounce 400ms)
 * - عرض معلومات القطعة القابلة للإرجاع
 * - عرض رسائل الخطأ
 * - دعم كامل للعربية والإنجليزية
 */
public class RefundDialog extends Dialog {

    // ==================== الواجهة ====================

    public interface RefundCallback {
        void onRefundConfirm(String code, String reason);
        void onBarcodeScan();
        void onCodeChanged(String code);
    }

    // ==================== الثوابت ====================

    private static final long SEARCH_DEBOUNCE_MS = 400;
    private static final int MIN_CODE_LENGTH = 4;

    // ==================== المتغيرات ====================

    private EditText etRefundCode;
    private EditText etReason;
    private TextView tvRefundItemTitle;
    private TextView tvRefundItemStatus;
    private View cardRefundItemInfo;

    private RefundCallback callback;
    private boolean isRefundable = false;
    private final boolean isArabic;

    // ✅ debounce للبحث
    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable debounceRunnable;

    // ✅ لمنع حلقة البحث عند الملء البرمجي
    private boolean suppressWatcher = false;

    // ==================== Constructor ====================

    public RefundDialog(@NonNull Context context, RefundCallback callback) {
        super(context);
        this.callback = callback;
        this.isArabic = context.getResources().getConfiguration().locale.getLanguage().equals("ar");
    }

    // ==================== Lifecycle ====================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_refund);

        if (getWindow() != null) {
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            getWindow().setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT
            );
        }

        initViews();
        setupListeners();
    }

    // ==================== تهيئة الواجهة ====================

    private void initViews() {
        etRefundCode       = findViewById(R.id.etRefundScanCode);
        etReason           = findViewById(R.id.etRefundReason);
        tvRefundItemTitle  = findViewById(R.id.tvRefundItemTitle);
        tvRefundItemStatus = findViewById(R.id.tvRefundItemStatus);
        cardRefundItemInfo = findViewById(R.id.cardRefundItemInfo);

        MaterialButton btnScanBarcode = findViewById(R.id.btnRefundScanBarcode);
        MaterialButton btnCancel      = findViewById(R.id.btnRefundCancel);
        MaterialButton btnConfirm     = findViewById(R.id.btnRefundConfirm);

        // زر الإلغاء
        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> dismiss());
        }

        // زر مسح الباركود
        if (btnScanBarcode != null) {
            btnScanBarcode.setOnClickListener(v -> {
                if (callback != null) callback.onBarcodeScan();
            });
        }

        // زر تأكيد الإرجاع
        if (btnConfirm != null) {
            btnConfirm.setOnClickListener(v -> onConfirmClicked());
        }

        // ✅ مراقبة تغيير الكود (بحث تلقائي مع debounce)
        if (etRefundCode != null) {
            etRefundCode.addTextChangedListener(codeWatcher);
        }
    }

    // ==================== TextWatcher ====================

    private final TextWatcher codeWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            if (suppressWatcher) return;

            String code = s.toString().trim();

            // إلغاء أي بحث سابق معلّق
            if (debounceRunnable != null) {
                debounceHandler.removeCallbacks(debounceRunnable);
                debounceRunnable = null;
            }

            if (code.length() >= MIN_CODE_LENGTH) {
                debounceRunnable = () -> {
                    if (callback != null) {
                        callback.onCodeChanged(code);
                    }
                };
                debounceHandler.postDelayed(debounceRunnable, SEARCH_DEBOUNCE_MS);
            } else {
                hideRefundItemInfo();
            }
        }

        @Override
        public void afterTextChanged(Editable s) {}
    };

    // ==================== منطق التأكيد ====================

    private void onConfirmClicked() {
        String code   = etRefundCode != null ? etRefundCode.getText().toString().trim() : "";
        String reason = etReason     != null ? etReason.getText().toString().trim() : "";

        if (code.isEmpty()) {
            Toast.makeText(getContext(),
                    isArabic ? "⚠️ أدخل رمز القطعة" : "⚠️ Enter item code",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        if (!isRefundable) {
            Toast.makeText(getContext(),
                    isArabic ? "⚠️ لا يمكن إرجاع هذه القطعة" : "⚠️ Item not refundable",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        if (callback != null) {
            String finalReason = reason.isEmpty()
                    ? (isArabic ? "مرتجع مبيعات" : "Sales Refund")
                    : reason;
            callback.onRefundConfirm(code, finalReason);
        }
    }

    // ==================== Setup Listeners ====================

    private void setupListeners() {
        setOnDismissListener(dialog -> {
            // ✅ تنظيف الذاكرة
            if (debounceRunnable != null) {
                debounceHandler.removeCallbacks(debounceRunnable);
                debounceRunnable = null;
            }
            etRefundCode       = null;
            tvRefundItemTitle  = null;
            tvRefundItemStatus = null;
            cardRefundItemInfo = null;
            isRefundable       = false;
            callback           = null;
        });

        setCancelable(true);
        setCanceledOnTouchOutside(true);
    }

    // ==================== واجهات عامة ====================

    /**
     * عرض معلومات القطعة القابلة للإرجاع
     */
    public void showRefundItemInfo(String title, String price) {
        if (cardRefundItemInfo != null) {
            cardRefundItemInfo.setVisibility(View.VISIBLE);
        }
        if (tvRefundItemTitle != null) {
            tvRefundItemTitle.setText(title + " (" + price + ")");
        }
        if (tvRefundItemStatus != null) {
            tvRefundItemStatus.setText(isArabic
                    ? "✅ القطعة مباعة وقابلة للإرجاع"
                    : "✅ Item sold and refundable");
            tvRefundItemStatus.setTextColor(Color.parseColor("#2ECC71"));
        }
        isRefundable = true;
    }

    /**
     * عرض رسالة خطأ (قطعة غير قابلة للإرجاع)
     */
    public void showRefundItemError(String message) {
        if (cardRefundItemInfo != null) {
            cardRefundItemInfo.setVisibility(View.VISIBLE);
        }
        if (tvRefundItemTitle != null) {
            tvRefundItemTitle.setText(message);
        }
        if (tvRefundItemStatus != null) {
            tvRefundItemStatus.setText(isArabic
                    ? "غير قابلة للإرجاع ❌"
                    : "Not Refundable ❌");
            tvRefundItemStatus.setTextColor(Color.parseColor("#E74C3C"));
        }
        isRefundable = false;
    }

    /**
     * ✅ تعيين الكود برمجياً (بعد مسح الباركود) دون إثارة بحث مزدوج
     */
    public void setCodeText(String code) {
        if (etRefundCode == null || code == null) return;

        suppressWatcher = true;
        etRefundCode.setText(code);
        etRefundCode.setSelection(code.length());
        suppressWatcher = false;
    }

    // ==================== أدوات داخلية ====================

    private void hideRefundItemInfo() {
        if (cardRefundItemInfo != null) {
            cardRefundItemInfo.setVisibility(View.GONE);
        }
        isRefundable = false;
    }
}
