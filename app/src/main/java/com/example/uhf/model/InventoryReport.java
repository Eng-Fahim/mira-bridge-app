package com.example.uhf.model;

public class InventoryReport {
    public int id;
    public String sessionId;
    public String date;
    public int expectedCount;
    public int scannedCount;
    public int missingCount;
    public String status;

    public InventoryReport(int id, String sessionId, String date, 
                           int expectedCount, int scannedCount, int missingCount, 
                           String status) {
        this.id = id;
        this.sessionId = sessionId;
        this.date = date;
        this.expectedCount = expectedCount;
        this.scannedCount = scannedCount;
        this.missingCount = missingCount;
        this.status = status;
    }
}
