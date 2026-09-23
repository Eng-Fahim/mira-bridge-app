package com.example.uhf.dialog;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.example.uhf.R;
import com.example.uhf.scanner.ScanMethod;

/**
 * ✅ حوار اختيار طريقة المسح
 * - يظهر 3 خيارات: RFID / GS1 / Manual
 */
public class ScanMethodDialog extends Dialog {
    
    public interface OnMethodSelectedListener {
        void onMethodSelected(ScanMethod method);
    }
    
    private final OnMethodSelectedListener listener;
    private final boolean isArabic;
    
    public ScanMethodDialog(@NonNull Context context, 
                            boolean isArabic,
                            OnMethodSelectedListener listener) {
        super(context);
        this.listener = listener;
        this.isArabic = isArabic;
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_scan_method);
        
        if (getWindow() != null) {
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            getWindow().setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
        }
        
        setCancelable(true);
        setCanceledOnTouchOutside(true);
        
        // ✅ ربط العناصر
        TextView tvTitle = findViewById(R.id.tvScanMethodTitle);
        TextView tvSubtitle = findViewById(R.id.tvScanMethodSubtitle);
        LinearLayout btnRfid = findViewById(R.id.btnScanRfid);
        LinearLayout btnGs1 = findViewById(R.id.btnScanGs1);
        LinearLayout btnManual = findViewById(R.id.btnScanManual);
        TextView btnCancel = findViewById(R.id.btnScanCancel);
        
        // ✅ النصوص
        if (isArabic) {
            tvTitle.setText("اختر طريقة المسح");
            tvSubtitle.setText("يمكنك استخدام أي طريقة لإضافة القطع");
            btnCancel.setText("إلغاء");
        } else {
            tvTitle.setText("Choose Scan Method");
            tvSubtitle.setText("Use any method to add items");
            btnCancel.setText("Cancel");
        }
        
        // ✅ الأزرار
        btnRfid.setOnClickListener(v -> {
            dismiss();
            if (listener != null) listener.onMethodSelected(ScanMethod.RFID);
        });
        
        btnGs1.setOnClickListener(v -> {
            dismiss();
            if (listener != null) listener.onMethodSelected(ScanMethod.GS1);
        });
        
        btnManual.setOnClickListener(v -> {
            dismiss();
            if (listener != null) listener.onMethodSelected(ScanMethod.MANUAL);
        });
        
        btnCancel.setOnClickListener(v -> dismiss());
    }
}
