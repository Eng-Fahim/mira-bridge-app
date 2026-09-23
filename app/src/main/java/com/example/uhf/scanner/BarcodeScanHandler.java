package com.example.uhf.scanner;

import android.content.Context;
import android.util.Log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ✅ معالج مسح الباركود GS1
 * - يستقبل النص الخام من ML Kit
 * - يحلّله إلى GTIN + Serial
 * - يرسله إلى UnifiedScanManager
 */
public class BarcodeScanHandler {
    
    private static final String TAG = "BarcodeScanHandler";
    
    /**
     * ✅ أنماط GS1 DataMatrix:
     * 
     * (01) GTIN-14: 14 رقم
     * (21) Serial: حتى 20 حرف
     * 
     * مثال: 010768071000051221MIRA-SN-76807100005
     *        │└──────────────┘│└────────────────┘
     *        │    GTIN (14)   │   Serial
     *        └─ AI (01)        └─ AI (21)
     */
    
    // AI 01 = GTIN-14
    private static final Pattern GTIN_PATTERN = 
        Pattern.compile("01(\\d{14})");
    
    // AI 21 = Serial
    private static final Pattern SERIAL_PATTERN = 
        Pattern.compile("21([\\x21-\\x7E]{1,20})");
    
    // GS1 separator: \x1D أو \u001D
    private static final String GS_SEPARATOR = "\u001D";
    
    private final UnifiedScanManager scanManager;
    
    public BarcodeScanHandler(Context context) {
        this.scanManager = UnifiedScanManager.getInstance(context);
    }
    
    /**
     * ✅ معالجة النص الخام من ML Kit
     */
    public void processRawBarcode(String rawBarcode) {
        if (rawBarcode == null || rawBarcode.trim().isEmpty()) {
            Log.w(TAG, "Empty barcode");
            return;
        }
        
        Log.d(TAG, "📷 Raw barcode: " + rawBarcode);
        
        try {
            // ✅ نظّف النص
            String cleaned = cleanRawBarcode(rawBarcode);
            
            // ✅ حلّل GS1
            String gtin = extractGtin(cleaned);
            String serial = extractSerial(cleaned);
            
            Log.d(TAG, "GTIN: " + gtin + " | Serial: " + serial);
            
            // ✅ القرار:
            if (serial != null && !serial.isEmpty()) {
                // ✅ الحالة الأفضل — لدينا Serial
                scanManager.scanItem(ScanMethod.GS1, serial, gtin);
                
            } else if (gtin != null && !gtin.isEmpty()) {
                // ⚠️ ليس لدينا Serial — استخدم GTIN كـ fallback
                Log.w(TAG, "No serial found — using GTIN as identifier");
                scanManager.scanItem(ScanMethod.GS1, gtin, gtin);
                
            } else {
                // ❌ لم نتعرف على الصيغة — جرّب النص الخام
                Log.w(TAG, "Unknown format — trying raw text");
                scanManager.scanItem(ScanMethod.GS1, cleaned, null);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "processRawBarcode error: " + e.getMessage());
        }
    }
    
    /**
     * ✅ تنظيف النص الخام
     */
    private String cleanRawBarcode(String raw) {
        // أزل المسافات والأسطر
        String cleaned = raw.trim();
        
        // استبدل رموز GS المتنوعة بفاصل موحّد
        cleaned = cleaned.replace("\u241D", GS_SEPARATOR);
        cleaned = cleaned.replace("<GS>", GS_SEPARATOR);
        cleaned = cleaned.replace("FNC1", GS_SEPARATOR);
        
        return cleaned;
    }
    
    /**
     * ✅ استخراج GTIN-14
     */
    public String extractGtin(String data) {
        if (data == null) return null;
        
        Matcher matcher = GTIN_PATTERN.matcher(data);
        if (matcher.find()) {
            return matcher.group(1);
        }
        
        // محاولة ثانية: البحث عن 14 رقم متتالي
        Matcher digits = Pattern.compile("(\\d{14})").matcher(data);
        if (digits.find()) {
            return digits.group(1);
        }
        
        return null;
    }
    
    /**
     * ✅ استخراج Serial
     */
    public String extractSerial(String data) {
        if (data == null) return null;
        
        Matcher matcher = SERIAL_PATTERN.matcher(data);
        if (matcher.find()) {
            String serial = matcher.group(1);
            // أزل الفواصل
            serial = serial.split(GS_SEPARATOR)[0];
            return serial.trim();
        }
        
        return null;
    }
    
    /**
     * ✅ التحقق من أن النص يحتوي على GS1 صالح
     */
    public boolean isValidGs1(String data) {
        if (data == null || data.isEmpty()) return false;
        return extractGtin(data) != null || extractSerial(data) != null;
    }
    
    /**
     * ✅ تشخيص GS1 (للتطوير)
     */
    public String diagnoseGs1(String rawBarcode) {
        StringBuilder sb = new StringBuilder();
        sb.append("Raw: ").append(rawBarcode).append("\n");
        sb.append("Length: ").append(rawBarcode.length()).append("\n");
        
        String cleaned = cleanRawBarcode(rawBarcode);
        sb.append("Cleaned: ").append(cleaned).append("\n");
        sb.append("GTIN: ").append(extractGtin(cleaned)).append("\n");
        sb.append("Serial: ").append(extractSerial(cleaned)).append("\n");
        
        return sb.toString();
    }
}
