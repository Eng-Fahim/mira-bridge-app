package com.example.uhf.MiraRfid.model;

/**
 * نتيجة مسح شريحة RFID
 */
public class RFIDScanResult {
    
    private final String epc;
    private final int rssi;
    private final long timestamp;
    private int count = 1;
    
    public RFIDScanResult(String epc, int rssi) {
        this.epc = epc;
        this.rssi = rssi;
        this.timestamp = System.currentTimeMillis();
    }
    
    public String getEpc() { return epc; }
    public int getRssi() { return rssi; }
    public long getTimestamp() { return timestamp; }
    public int getCount() { return count; }
    
    public void incrementCount() { count++; }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        RFIDScanResult that = (RFIDScanResult) obj;
        return epc != null && epc.equals(that.epc);
    }
    
    @Override
    public int hashCode() {
        return epc != null ? epc.hashCode() : 0;
    }
}
