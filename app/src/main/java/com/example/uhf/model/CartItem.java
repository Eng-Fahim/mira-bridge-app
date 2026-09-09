package com.example.uhf.model;

public class CartItem {
    public String epc, name, karat, status, serialNo, gtin, location;
    public double weight, scaleWeight, price;
    public boolean isAvailable;
    public int itemId;

    public CartItem(String epc, String name, String karat, double weight,
                    double scaleWeight, double price, boolean isAvailable, String status) {
        this.epc = epc;
        this.name = name;
        this.karat = karat;
        this.weight = weight;
        this.scaleWeight = scaleWeight;
        this.price = price;
        this.isAvailable = isAvailable;
        this.status = status;
    }
}
