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
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.uhf.R;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.api.dto.PaymentMethodDto;
import com.google.android.material.button.MaterialButton;
import com.google.gson.Gson;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class RefundDialog extends Dialog {

    private static final String TAG = "RefundDialog";

    // ✅ تحديث الواجهة لتستقبل طريقة الإرجاع
    public interface RefundCallback {
        void onRefundConfirm(String code, String reason, String refundMethodCode);
        void onBarcodeScan();
        void onCodeChanged(String code);
    }

    private static final long SEARCH_DEBOUNCE_MS = 700L;
    private static final int  MIN_SEARCH_LEN = 4;

    private static final int KEYCODE_RFID_TRIGGER_1 = 139;
    private static final int KEYCODE_RFID_TRIGGER_2 = 280;
    private static final int KEYCODE_RFID_TRIGGER_3 = 293;

    private EditText etRefundCode;
    private EditText etReason;
    private Spinner spRefundMethod; // ✅ تمت الإضافة
    private TextView tvRefundItemTitle;
    private TextView tvRefundItemStatus;
    private android.view.View cardRefundItemInfo;
    private RefundCallback callback;
    private boolean isRefundable = false;
    private boolean isArabic;

    private final Handler handler = new Handler(Looper.getMainLooper());
    @Nullable private Runnable pendingSearch = null;
    private boolean suppressWatcher = false;

    @Nullable private DialogInterface.OnKeyListener hardwareKeyListener;

    // ✅ قائمة طرق الدفع
    private final List<PaymentMethodDto> paymentMethods = new ArrayList<>();

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
        loadPaymentMethods(); // ✅ جلب طرق الدفع
        setupListeners();
    }

    private void initViews() {
        etRefundCode = findViewById(R.id.etRefundScanCode);
        etReason = findViewById(R.id.etRefundReason);
        spRefundMethod = findViewById(R.id.spRefundMethod); // ✅
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
                Toast.makeText(getContext(), isArabic ? "⚠️ أدخل رمز القطعة" : "⚠️ Enter item code", Toast.LENGTH_SHORT).show();
                return;
            }

            if (!isRefundable) {
                Toast.makeText(getContext(), isArabic ? "⚠️ لا يمكن إرجاع هذه القطعة" : "⚠️ Item not refundable", Toast.LENGTH_SHORT).show();
                return;
            }

            // ✅ استخراج كود طريقة الإرجاع
            String methodCode = "CASH";
            if (!paymentMethods.isEmpty()) {
                int pos = spRefundMethod.getSelectedItemPosition();
                if (pos >= 0 && pos < paymentMethods.size()) {
                    methodCode = paymentMethods.get(pos).code;
                }
            }

            if (callback != null) {
                callback.onRefundConfirm(code, reason.isEmpty() ? (isArabic ? "مرتجع مبيعات" : "Sales Refund") : reason, methodCode);
            }
        });

        etRefundCode.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (suppressWatcher) return;
                if (pendingSearch != null) {
                    handler.removeCallbacks(pendingSearch);
                    pendingSearch = null;
                }
                final String text = s != null ? s.toString().trim() : "";
                if (text.length() < MIN_SEARCH_LEN) {
                    hideRefundItemInfo();
                    return;
                }
                pendingSearch = () -> {
                    pendingSearch = null;
                    if (callback != null && etRefundCode != null && text.equals(etRefundCode.getText().toString().trim())) {
                        callback.onCodeChanged(text);
                    }
                };
                handler.postDelayed(pendingSearch, SEARCH_DEBOUNCE_MS);
            }
        });
    }

    // ✅ دالة جلب طرق الدفع ديناميكياً من السيرفر
    private void loadPaymentMethods() {
        new Thread(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(getContext().getApplicationContext());
                MiraApiClient.ApiResponse response = client.getPaymentMethods();

                if (response != null && response.isSuccess && response.data != null) {
                    JSONArray arr = response.data.optJSONArray("data");
                    if (arr == null) arr = response.data.optJSONArray("methods");

                    if (arr != null) {
                        Gson gson = new Gson();
                        for (int i = 0; i < arr.length(); i++) {
                            PaymentMethodDto m = gson.fromJson(arr.getJSONObject(i).toString(), PaymentMethodDto.class);
                            if (m != null && m.code != null) paymentMethods.add(m);
                        }
                    }
                }
            } catch (Exception ignored) {}

            // احتياطي
            if (paymentMethods.isEmpty()) {
                PaymentMethodDto cash = new PaymentMethodDto();
                cash.code = "CASH"; cash.nameAr = "نقداً"; cash.icon = "💵";
                paymentMethods.add(cash);
            }

            handler.post(() -> {
                if (spRefundMethod != null) {
                    List<String> names = new ArrayList<>();
                    for (PaymentMethodDto m : paymentMethods) {
                        names.add((m.icon != null ? m.icon : "") + " " + m.getDisplayName(isArabic));
                    }
                    ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, names);
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    spRefundMethod.setAdapter(adapter);
                }
            });
        }).start();
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
            hardwareKeyListener = null;
        });

        setCancelable(true);
        setCanceledOnTouchOutside(true);
    }

    public void setOnHardwareKeyListener(@Nullable DialogInterface.OnKeyListener listener) {
        this.hardwareKeyListener = listener;
    }

    @Override
    public boolean dispatchKeyEvent(@NonNull KeyEvent event) {
        try {
            int code = event.getKeyCode();
            boolean isTriggerKey = (code == KEYCODE_RFID_TRIGGER_1 || code == KEYCODE_RFID_TRIGGER_2 || code == KEYCODE_RFID_TRIGGER_3);
            if (isTriggerKey && hardwareKeyListener != null) {
                return hardwareKeyListener.onKey(this, code, event);
            }
        } catch (Exception e) {
            Log.e(TAG, "dispatchKeyEvent error", e);
        }
        return super.dispatchKeyEvent(event);
    }

    public void setRefundCode(@Nullable String code) {
        if (etRefundCode == null || code == null) return;
        suppressWatcher = true;
        try {
            etRefundCode.setText(code);
            etRefundCode.setSelection(code.length());
        } finally {
            suppressWatcher = false;
        }
        hideRefundItemInfo();
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
