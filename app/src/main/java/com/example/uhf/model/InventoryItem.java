package com.example.uhf.model;

import java.util.Locale;

public class InventoryItem {
    private final String epc;
    private final String gtin;
    private final String serial;
    private String title;       // ✅ تم إزالة final
    private String karat;
    
    private double weight;
    private double price;
    
    private int readCount = 0;
    private int lastRssi = -128;
    private long firstSeenTimestamp = 0;
    private long lastSeenTimestamp = 0;

    public InventoryItem(String epc, String gtin, String serial, String title) {
        this.epc = normalizeEpc(epc);
        this.gtin = gtin != null ? gtin : "";
        this.serial = serial != null ? serial : "";
        this.title = title != null ? title : "";
        this.karat = "";
        this.weight = 0;
        this.price = 0;
    }

    public InventoryItem(String epc, String gtin, String serial, 
                         String title, String karat) {
        this(epc, gtin, serial, title);
        this.karat = karat != null ? karat : "";
    }

    public static String normalizeEpc(String rawEpc) {
        if (rawEpc == null) return "";
        return rawEpc.trim()
                .replace(" ", "")
                .replace(":", "")
                .replace("-", "")
                .toUpperCase(Locale.US);
    }

    public synchronized void recordScan(int rssi) {
        long now = System.currentTimeMillis();
        if (this.readCount == 0) {
            this.firstSeenTimestamp = now;
        }
        this.readCount++;
        this.lastRssi = rssi;
        this.lastSeenTimestamp = now;
    }

    public String getEpc() { return epc; }
    public String getGtin() { return gtin; }
    public String getSerial() { return serial; }
    
    // ✅ title أصبح قابل للتعديل
    public String getTitle() { return title; }
    public void setTitle(String title) { 
        this.title = title != null ? title : ""; 
    }
    
    public String getKarat() { return karat; }
    public void setKarat(String karat) { this.karat = karat; }

    public double getWeight() { return weight; }
    public void setWeight(double weight) { this.weight = weight; }
    
    public double getPrice() { return price; }
    public void setPrice(double price) { this.price = price; }

    public int getReadCount() { return readCount; }
    public int getLastRssi() { return lastRssi; }
    public long getFirstSeenTimestamp() { return firstSeenTimestamp; }
    public long getLastSeenTimestamp() { return lastSeenTimestamp; }
}
