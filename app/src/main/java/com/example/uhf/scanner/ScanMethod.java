package com.example.uhf.scanner;

/**
 * ✅ طرق المسح المدعومة
 */
public enum ScanMethod {
    RFID("rfid", "مسح RFID"),
    GS1("gs1", "مسح باركود GS1"),
    MANUAL("manual", "إدخال يدوي"),
DESKTOP_RFID("desktop_rfid", "مسح القارئ المكتبي");
    
    private final String code;
    private final String label;
    
    ScanMethod(String code, String label) {
        this.code = code;
        this.label = label;
    }
    
    public String getCode() { return code; }
    public String getLabel() { return label; }
    
    public static ScanMethod fromCode(String code) {
        for (ScanMethod m : values()) {
            if (m.code.equals(code)) return m;
        }
        return RFID;
    }
}
