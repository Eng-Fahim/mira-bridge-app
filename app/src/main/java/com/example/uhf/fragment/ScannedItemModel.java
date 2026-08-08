package com.example.uhf.fragment;

public class ScannedItemModel {
    private String epc;
    private double weight;
    private String category;
    private String status;
    private int readCount;

    // Constructor (المنشئ)
    public ScannedItemModel(String epc, double weight, String category, String status, int readCount) {
        this.epc = epc;
        this.weight = weight;
        this.category = category;
        this.status = status;
        this.readCount = readCount;
    }

    // Getters & Setters الآمنة
    public String getEpc() {
        return epc != null ? epc : "";
    }

    public void setEpc(String epc) {
        this.epc = epc;
    }

    public double getWeight() {
        return weight;
    }

    public void setWeight(double weight) {
        this.weight = weight;
    }

    public String getCategory() {
        return category != null ? category : "عام";
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getStatus() {
        return status != null ? status : "مطابق";
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getReadCount() {
        return readCount;
    }

    public void setReadCount(int readCount) {
        this.readCount = readCount;
    }
}
