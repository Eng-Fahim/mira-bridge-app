package com.example.uhf.data.entity;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * كيان Room Database لتخزين العناصر المشفرة
 * مع فهرس فريد على حقل EPC لمنع التكرار
 */
@Entity(
    tableName = "encoded_items",
    indices = {
        @Index(value = "epc", unique = true),  // فهرس فريد لمنع التكرار
        @Index(value = "title"),               // فهرس للبحث بالعنوان
        @Index(value = "created_at")           // فهرس للترتيب الزمني
    }
)
public class EncodedItemEntity {
    
    @PrimaryKey(autoGenerate = true)
    private int id;
    
    private String epc;           // فريد - لا يمكن تكراره
    private String title;
    private String purityCode;
    private double grossWeight;
    private double netWeight;
    private double stoneWeight;
    private int stoneCount;
    private double stoneValue;
    private String stoneType;
    private String category;
    private String location;
    private int branchId;
    private int userId;
    private String rfidTid;
    private double retailPrice;
    private String currency;
    private long createdAt;
    private boolean isSynced;
    
    // Constructors
    public EncodedItemEntity() {
        this.createdAt = System.currentTimeMillis();
        this.isSynced = false;
    }
    
    // Getters and Setters
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    
    public String getEpc() { return epc; }
    public void setEpc(String epc) { 
        // توحيد الأحرف الكبيرة عند التخزين
        this.epc = epc != null ? epc.trim().toUpperCase(Locale.US) : null; 
    }
    
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    
    public String getPurityCode() { return purityCode; }
    public void setPurityCode(String purityCode) { this.purityCode = purityCode; }
    
    public double getGrossWeight() { return grossWeight; }
    public void setGrossWeight(double grossWeight) { this.grossWeight = grossWeight; }
    
    public double getNetWeight() { return netWeight; }
    public void setNetWeight(double netWeight) { this.netWeight = netWeight; }
    
    public double getStoneWeight() { return stoneWeight; }
    public void setStoneWeight(double stoneWeight) { this.stoneWeight = stoneWeight; }
    
    public int getStoneCount() { return stoneCount; }
    public void setStoneCount(int stoneCount) { this.stoneCount = stoneCount; }
    
    public double getStoneValue() { return stoneValue; }
    public void setStoneValue(double stoneValue) { this.stoneValue = stoneValue; }
    
    public String getStoneType() { return stoneType; }
    public void setStoneType(String stoneType) { this.stoneType = stoneType; }
    
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    
    public int getBranchId() { return branchId; }
    public void setBranchId(int branchId) { this.branchId = branchId; }
    
    public int getUserId() { return userId; }
    public void setUserId(int userId) { this.userId = userId; }
    
    public String getRfidTid() { return rfidTid; }
    public void setRfidTid(String rfidTid) { this.rfidTid = rfidTid; }
    
    public double getRetailPrice() { return retailPrice; }
    public void setRetailPrice(double retailPrice) { this.retailPrice = retailPrice; }
    
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    
    public boolean isSynced() { return isSynced; }
    public void setSynced(boolean synced) { isSynced = synced; }
}
