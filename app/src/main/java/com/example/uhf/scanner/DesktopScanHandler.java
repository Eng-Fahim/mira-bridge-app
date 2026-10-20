package com.example.uhf.scanner;

import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

public class DesktopScanHandler {

    private final EditText targetEditText;
    private OnDesktopScanListener listener;

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
                        listener.onTagScanned(epc);
                    }
                    return true; // منع الـ Enter من عمل سطر جديد
                }
            }
            return false;
        });
        
        // كبديل احتياطي: إذا كان القارئ لا يرسل Enter
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
