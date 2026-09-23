package com.example.uhf.scanner;

import org.json.JSONObject;

/**
 * ✅ نتيجة عملية مسح واحدة
 */
public class ScanResult {
    
    public enum Status {
        SCANNED,          // ✅ ممسوحة بنجاح
        ALREADY_SCANNED,  // ⚠️ ممسوحة مسبقاً
        NOT_FOUND,        // ❌ غير مسجلة في النظام
        EXTRA,            // ⚠️ غير مدرجة في هذه الشحنة
        ERROR             // 🔴 خطأ في الاتصال
    }
    
    private final Status status;
    private final String itemId;
    private final String title;
    private final String serial;
    private final String gtin;
    private final String goldKarat;
    private final double weight;
    private final int scannedCount;
    private final int expectedCount;
    private final boolean allScanned;
    private final String message;
    private final String method;
    
    private ScanResult(Builder b) {
        this.status = b.status;
        this.itemId = b.itemId;
        this.title = b.title;
        this.serial = b.serial;
        this.gtin = b.gtin;
        this.goldKarat = b.goldKarat;
        this.weight = b.weight;
        this.scannedCount = b.scannedCount;
        this.expectedCount = b.expectedCount;
        this.allScanned = b.allScanned;
        this.message = b.message;
        this.method = b.method;
    }
    
    public Status getStatus() { return status; }
    public String getItemId() { return itemId; }
    public String getTitle() { return title; }
    public String getSerial() { return serial; }
    public String getGtin() { return gtin; }
    public String getGoldKarat() { return goldKarat; }
    public double getWeight() { return weight; }
    public int getScannedCount() { return scannedCount; }
    public int getExpectedCount() { return expectedCount; }
    public boolean isAllScanned() { return allScanned; }
    public String getMessage() { return message; }
    public String getMethod() { return method; }
    
    public boolean isSuccess() {
        return status == Status.SCANNED;
    }
    
    /**
     * ✅ تحليل استجابة السيرفر
     */
    public static ScanResult fromJson(JSONObject json, String method) {
        Builder b = new Builder();
        b.method = method;
        
        b.message = json.optString("message", "");
        b.scannedCount = json.optInt("scanned_count", 0);
        b.expectedCount = json.optInt("expected_count", 0);
        b.allScanned = json.optBoolean("all_scanned", false);
        b.itemId = String.valueOf(json.optInt("item_id", 0));
        b.title = json.optString("title", "");
        b.serial = json.optString("serial", "");
        b.gtin = json.optString("gtin", "");
        b.goldKarat = json.optString("gold_karat", "");
        b.weight = json.optDouble("weight", 0);
        
        String statusStr = json.optString("status", "");
        switch (statusStr) {
            case "scanned":
                b.status = Status.SCANNED;
                break;
            case "already_scanned":
                b.status = Status.ALREADY_SCANNED;
                break;
            case "not_found":
                b.status = Status.NOT_FOUND;
                break;
            case "extra":
                b.status = Status.EXTRA;
                break;
            default:
                b.status = Status.ERROR;
                break;
        }
        
        return new ScanResult(b);
    }
    
    public static ScanResult error(String message, String method) {
        Builder b = new Builder();
        b.status = Status.ERROR;
        b.message = message;
        b.method = method;
        return new ScanResult(b);
    }
    
    // Builder
    public static class Builder {
        Status status = Status.ERROR;
        String itemId = "";
        String title = "";
        String serial = "";
        String gtin = "";
        String goldKarat = "";
        double weight = 0;
        int scannedCount = 0;
        int expectedCount = 0;
        boolean allScanned = false;
        String message = "";
        String method = "rfid";
        
        public ScanResult build() { return new ScanResult(this); }
    }
}
