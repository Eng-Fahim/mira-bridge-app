package com.example.uhf.dialog;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.uhf.R;
import com.google.android.material.button.MaterialButton;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  RefundDialog v2.1 — Partial Serial + RFID Hardware Trigger + Barcode Support
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * إصلاحات v2.1:
 *   ✅ [NEW] اعتراض زناد الـ RFID (139, 280, 293) وتمريره للـ Fragment.
 *            (لأن الـ Dialog يأخذ التركيز فلا يصل الزناد للـ Fragment تلقائياً)
 *   ✅ [NEW] دالة setOnHardwareKeyListener لربط الزناد بـ myOnKeyDwon.
 *   ✅ [NEW] تنظيف hardwareKeyListener عند dismiss.
 *
 * إصلاحات v2.0 (سابقة):
 *   ✅ Debounce على TextWatcher (700ms) — بدل إرسال طلب لكل حرف
 *   ✅ setRefundCode(code) — لاستقبال RFID/باركود من الخارج
 *   ✅ suppressWatcher flag — يمنع double-search عند setText برمجياً
 *   ✅ تنظيف pendingSearch عند dismiss
 */
public class RefundDialog extends Dialog {

    private static final String TAG = "RefundDialog";

    public interface RefundCallback {
        void onRefundConfirm(String code, String reason);
        void onBarcodeScan();
        void onCodeChanged(String code);
    }

    private static final long SEARCH_DEBOUNCE_MS = 700L;
    private static final int  MIN_SEARCH_LEN = 4;

    // أكواد أزرار الزناد في قارئ RFID
    private static final int KEYCODE_RFID_TRIGGER_1 = 139;
    private static final int KEYCODE_RFID_TRIGGER_2 = 280;
    private static final int KEYCODE_RFID_TRIGGER_3 = 293;

    private EditText etRefundCode;
    private EditText etReason;
    private TextView tvRefundItemTitle;
    private TextView tvRefundItemStatus;
    private android.view.View cardRefundItemInfo;
    private RefundCallback callback;
    private boolean isRefundable = false;
    private boolean isArabic;

    private final Handler handler = new Handler(Looper.getMainLooper());
    @Nullable private Runnable pendingSearch = null;
    private boolean suppressWatcher = false;

    // ✅ [v2.1] مستمع أزرار الزناد (يُمرَّر للـ Fragment)
    @Nullable private DialogInterface.OnKeyListener hardwareKeyListener;

    public RefundDialog(@NonNull Context context, RefundCallback callback) {
        super(context);
        this.callback = callback;
        this.isArabic = context.getResources().getConfiguration()
                .locale.getLanguage().equals("ar");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_refund);

        if (getWindow() != null) {
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            getWindow().setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
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
                Toast.makeText(getContext(),
                        isArabic ? "⚠️ أدخل رمز القطعة" : "⚠️ Enter item code",
                        Toast.LENGTH_SHORT).show();
                return;
            }

            if (!isRefundable) {
                Toast.makeText(getContext(),
                        isArabic ? "⚠️ لا يمكن إرجاع هذه القطعة"
                                : "⚠️ Item not refundable",
                        Toast.LENGTH_SHORT).show();
                return;
            }

            if (callback != null) {
                callback.onRefundConfirm(code, reason.isEmpty()
                        ? (isArabic ? "مرتجع مبيعات" : "Sales Refund")
                        : reason);
            }
        });

        // ✅ TextWatcher مع Debounce (700ms)
        etRefundCode.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (suppressWatcher) return;

                // إلغاء أي بحث معلّق
                if (pendingSearch != null) {
                    handler.removeCallbacks(pendingSearch);
                    pendingSearch = null;
                }

                final String text = s != null ? s.toString().trim() : "";

                if (text.length() < MIN_SEARCH_LEN) {
                    hideRefundItemInfo();
                    return;
                }

                // ✅ Debounce
                pendingSearch = () -> {
                    pendingSearch = null;
                    if (callback != null && etRefundCode != null
                            && text.equals(etRefundCode.getText().toString().trim())) {
                        Log.d(TAG, "🔍 Debounced search: " + text);
                        callback.onCodeChanged(text);
                    }
                };
                handler.postDelayed(pendingSearch, SEARCH_DEBOUNCE_MS);
            }
        });
    }

    private void setupListeners() {
        setOnDismissListener(dialog -> {
            if (pendingSearch != null) {
                handler.removeCallbacks(pendingSearch);
                pendingSearch = null;
            }
            etRefundCode = null;
            tvRefundItemTitle = null;
            tvRefundItemStatus = null;
            cardRefundItemInfo = null;
            isRefundable = false;
            suppressWatcher = false;
            hardwareKeyListener = null; // ✅ [v2.1]
        });

        setCancelable(true);
        setCanceledOnTouchOutside(true);
    }

    /**
     * ✅ [v2.1] ربط أزرار الزناد الخاصة بالقارئ (RFID).
     *    الـ Dialog يأخذ التركيز، لذا نعترض الأزرار هنا ونمرّرها للـ Fragment.
     *    الاستخدام من MiraPosFragment:
     *      refundDialog.setOnHardwareKeyListener((d, k, e) -> myOnKeyDwon(k, e));
     */
    public void setOnHardwareKeyListener(@Nullable DialogInterface.OnKeyListener listener) {
        this.hardwareKeyListener = listener;
    }

    /**
     * ✅ [v2.1] اعتراض أزرار الزناد قبل توزيعها على عناصر الـ Dialog.
     */
    @Override
    public boolean dispatchKeyEvent(@NonNull KeyEvent event) {
        try {
            int code = event.getKeyCode();
            boolean isTriggerKey =
                    (code == KEYCODE_RFID_TRIGGER_1
                            || code == KEYCODE_RFID_TRIGGER_2
                            || code == KEYCODE_RFID_TRIGGER_3);

            if (isTriggerKey && hardwareKeyListener != null) {
                Log.d(TAG, "🎯 [v2.1] Hardware trigger intercepted: " + code
                        + " action=" + event.getAction());
                return hardwareKeyListener.onKey(this, code, event);
            }
        } catch (Exception e) {
            Log.e(TAG, "dispatchKeyEvent error", e);
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * ✅ [v2.0] تعيين الكود برمجياً (من RFID أو باركود) — بدون تفعيل watcher
     *    المتصل (Fragment) سيتولى استدعاء البحث مباشرة.
     */
    public void setRefundCode(@Nullable String code) {
        if (etRefundCode == null || code == null) return;

        suppressWatcher = true;
        try {
            etRefundCode.setText(code);
            etRefundCode.setSelection(code.length());
        } finally {
            suppressWatcher = false;
        }

        // امسح أي حالة سابقة
        hideRefundItemInfo();
        Log.d(TAG, "📥 setRefundCode: " + code);
    }

    public void showRefundItemInfo(String title, String price) {
        if (cardRefundItemInfo != null)
            cardRefundItemInfo.setVisibility(android.view.View.VISIBLE);
        if (tvRefundItemTitle != null)
            tvRefundItemTitle.setText(title + " (" + price + ")");
        if (tvRefundItemStatus != null) {
            tvRefundItemStatus.setText(isArabic
                    ? "✅ القطعة مباعة وقابلة للإرجاع"
                    : "✅ Item sold and refundable");
            tvRefundItemStatus.setTextColor(Color.parseColor("#2ECC71"));
        }
        isRefundable = true;
    }

    public void showRefundItemError(String message) {
        if (cardRefundItemInfo != null)
            cardRefundItemInfo.setVisibility(android.view.View.VISIBLE);
        if (tvRefundItemTitle != null)
            tvRefundItemTitle.setText(message);
        if (tvRefundItemStatus != null) {
            tvRefundItemStatus.setText(isArabic
                    ? "غير قابلة للإرجاع ❌" : "Not Refundable ❌");
            tvRefundItemStatus.setTextColor(Color.parseColor("#E74C3C"));
        }
        isRefundable = false;
    }

    private void hideRefundItemInfo() {
        if (cardRefundItemInfo != null)
            cardRefundItemInfo.setVisibility(android.view.View.GONE);
        isRefundable = false;
    }
}
