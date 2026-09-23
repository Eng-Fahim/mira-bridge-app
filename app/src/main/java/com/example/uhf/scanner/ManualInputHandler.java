package com.example.uhf.scanner;

import android.content.Context;
import android.util.Log;

/**
 * ✅ معالج الإدخال اليدوي
 * - يقبل: EPC / Serial / item_id
 */
public class ManualInputHandler {
    
    private static final String TAG = "ManualInputHandler";
    
    private final UnifiedScanManager scanManager;
    
    public ManualInputHandler(Context context) {
        this.scanManager = UnifiedScanManager.getInstance(context);
    }
    
    /**
     * ✅ معالجة إدخال يدوي
     */
    public void submit(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) {
            Log.w(TAG, "Empty identifier");
            return;
        }
        
        String cleanId = identifier.trim().toUpperCase();
        
        Log.d(TAG, "⌨️ Manual input: " + cleanId);
        
        scanManager.scanItem(ScanMethod.MANUAL, cleanId, null);
    }
    
    /**
     * ✅ التحقق من صيغة الإدخال
     */
    public boolean isValid(String identifier) {
        if (identifier == null) return false;
        String clean = identifier.trim();
        if (clean.isEmpty()) return false;
        if (clean.length() < 3) return false;
        if (clean.length() > 100) return false;
        return true;
    }
    
    /**
     * ✅ إرجاع رسالة الخطأ المناسبة
     */
    public String getValidationError(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) {
            return "الرجاء إدخال المُعرّف";
        }
        if (identifier.trim().length() < 3) {
            return "المُعرّف قصير جداً";
        }
        if (identifier.trim().length() > 100) {
            return "المُعرّف طويل جداً";
        }
        return null;
    }
}
