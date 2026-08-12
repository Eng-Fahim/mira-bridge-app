package com.example.uhf.MiraRfid.model;

import com.example.uhf.MiraRfid.MiraIRFIDReader.ReaderType;

/**
 * نموذج جهاز RFID محفوظ
 */
public class RFIDDevice {
    
    private ReaderType type;
    private String address; // MAC أو IP:Port
    private String customName;
    private int powerDbm = 28;
    private int rssiThreshold = -70;
    private int scanIntervalMs = 200;
    private boolean isDefault = false;
    private long lastUsedAt = 0;
    
    public RFIDDevice() {}
    
    public RFIDDevice(ReaderType type, String address) {
        this.type = type;
        this.address = address;
    }
    
    // Getters
    public ReaderType getType() { return type; }
    public String getAddress() { return address; }
    public String getCustomName() { return customName; }
    public int getPowerDbm() { return powerDbm; }
    public int getRssiThreshold() { return rssiThreshold; }
    public int getScanIntervalMs() { return scanIntervalMs; }
    public boolean isDefault() { return isDefault; }
    public long getLastUsedAt() { return lastUsedAt; }
    
    // Setters
    public void setType(ReaderType type) { this.type = type; }
    public void setAddress(String address) { this.address = address; }
    public void setCustomName(String customName) { this.customName = customName; }
    public void setPowerDbm(int powerDbm) { this.powerDbm = powerDbm; }
    public void setRssiThreshold(int rssiThreshold) { this.rssiThreshold = rssiThreshold; }
    public void setScanIntervalMs(int scanIntervalMs) { this.scanIntervalMs = scanIntervalMs; }
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }
    public void setLastUsedAt(long lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    
    public String getDisplayName() {
        if (customName != null && !customName.isEmpty()) {
            return customName;
        }
        if (type != null) {
            return type.displayName;
        }
        return "جهاز غير معروف";
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getDisplayName());
        if (address != null && !address.isEmpty()) {
            sb.append(" (").append(address).append(")");
        }
        return sb.toString();
    }
}
