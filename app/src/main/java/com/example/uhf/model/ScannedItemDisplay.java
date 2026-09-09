package com.example.uhf.model;

public class ScannedItemDisplay {
    public String epc;
    public String title;
    public String karat;
    public int count;
    public int rssi;
    public String time;
    public double weight;
    public double price;

    public ScannedItemDisplay(String epc, String title, String karat, 
                              int count, int rssi, String time) {
        this.epc = epc;
        this.title = title;
        this.karat = karat;
        this.count = count;
        this.rssi = rssi;
        this.time = time;
        this.weight = 0;
        this.price = 0;
    }
}
