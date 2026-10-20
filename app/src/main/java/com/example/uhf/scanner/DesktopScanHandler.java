package com.example.uhf.scanner;

import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

/**
 * ✅ معالج القارئ المكتبي (USB HID)
 * يعتمد على استقبال النص المكتوب من القارئ في حقل الإدخال
 */
public class DesktopScanHandler {

    private final EditText targetEditText;
    private OnDesktopScanListener listener;
    private final StringBuilder buffer = new StringBuilder();

    public interface OnDesktopScanListener {
        void onTagScanned(String epc);
    }

    public DesktopScanHandler(EditText targetEditText) {
        this.targetEditText = targetEditText;
        setupListener();
    }

    public void setOnDesktopScanListener(OnDesktopScanListener listener) {
        this.listener = listener;
    }

    private void setupListener() {
        if (targetEditText == null) return;

        // الاستماع لضغطة زر Enter التي يرسلها القارئ في نهاية الباركود
        targetEditText.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_ENTER) {
                    String epc = targetEditText.getText().toString().trim();
                    if (!epc.isEmpty() && listener != null) {
                        // ✅ تم قراءة الكود بنجاح من القارئ المكتبي
                        listener.onTagScanned(epc);
                        
                        // مسح الحقل استعداداً للقراءة التالية (اختياري)
                        // targetEditText.setText(""); 
                    }
                    return true; // منع الـ Enter من عمل سطر جديد
                }
            }
            return false;
        });
        
        // كبديل احتياطي: إذا كان القارئ لا يرسل Enter بل يرسل Action_Done
        targetEditText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                 String epc = targetEditText.getText().toString().trim();
                 if (!epc.isEmpty() && listener != null) {
                     listener.onTagScanned(epc);
                     return true;
                 }
            }
            return false;
        });
    }
}
