package com.example.uhf.model;

import java.util.Locale;

public class MiraAccountingItem {

    public enum ItemType {
        JOURNAL_ENTRY,
        CHART_OF_ACCOUNT,
        TRIAL_BALANCE
    }

    private ItemType itemType;
    private String codeOrRef;
    private String nameOrDesc;
    private double fineWeight24Debit;
    private double fineWeight24Credit;
    private double debitCash;
    private double creditCash;
    private String currency;
    private String rfidEpc;
    private String dateCreated;

    // Default Constructor (مهم جداً عند استخدام الموديل مع библиотеки JSON)
    public MiraAccountingItem() {
        this.itemType = ItemType.JOURNAL_ENTRY;
        this.currency = "YER";
        this.dateCreated = "";
    }

    // 1. المنشئ الكامل (للقيود اليومية)
    public MiraAccountingItem(ItemType itemType, String codeOrRef, String nameOrDesc,
                              double fineWeight24Debit, double fineWeight24Credit,
                              double debitCash, double creditCash, String currency, String dateCreated) {
        this.itemType = itemType;
        this.codeOrRef = codeOrRef;
        this.nameOrDesc = nameOrDesc;
        this.fineWeight24Debit = fineWeight24Debit;
        this.fineWeight24Credit = fineWeight24Credit;
        this.debitCash = debitCash;
        this.creditCash = creditCash;
        this.currency = currency != null ? currency : "YER";
        this.dateCreated = dateCreated != null ? dateCreated : "";
    }

    // 2. المنشئ المباشر/المبسط (لدليل الحسابات وميزان المراجعة)
    public MiraAccountingItem(ItemType itemType, String codeOrRef, String nameOrDesc,
                              double fineWeight24Debit, double debitCash, String currency) {
        this(itemType, codeOrRef, nameOrDesc, fineWeight24Debit, 0.0, debitCash, 0.0, currency, "");
    }

    // تنسيق مخرجات الأسطر للـ Adapter
    public String getCol1Text() { return codeOrRef != null ? codeOrRef : ""; }
    public String getCol2Text() { return nameOrDesc != null ? nameOrDesc : ""; }
    
    public String getCol3Text() {
        double netGold = fineWeight24Debit - fineWeight24Credit;
        if (itemType == ItemType.JOURNAL_ENTRY) {
            return String.format(Locale.US, "%.3f جم", netGold);
        }
        return String.format(Locale.US, "%.3f جم", fineWeight24Debit);
    }

    public String getCol4Text() {
        double netCash = debitCash - creditCash;
        if (itemType == ItemType.JOURNAL_ENTRY) {
            return String.format(Locale.US, "%,.0f %s", netCash, currency != null ? currency : "");
        }
        return String.format(Locale.US, "%,.0f %s", debitCash, currency != null ? currency : "");
    }

    public static double calculateFineWeight24(double grossWeight, int karat) {
        if (karat <= 0) return 0.0;
        return (grossWeight * karat) / 24.0;
    }

    // ================= Getters & Setters الكاملة =================

    public ItemType getItemType() { return itemType; }
    public void setItemType(ItemType itemType) { this.itemType = itemType; }

    public String getCodeOrRef() { return codeOrRef; }
    public void setCodeOrRef(String codeOrRef) { this.codeOrRef = codeOrRef; }

    public String getNameOrDesc() { return nameOrDesc; }
    public void setNameOrDesc(String nameOrDesc) { this.nameOrDesc = nameOrDesc; }

    public double getFineWeight24Debit() { return fineWeight24Debit; }
    public void setFineWeight24Debit(double fineWeight24Debit) { this.fineWeight24Debit = fineWeight24Debit; }

    public double getFineWeight24Credit() { return fineWeight24Credit; }
    public void setFineWeight24Credit(double fineWeight24Credit) { this.fineWeight24Credit = fineWeight24Credit; }

    public double getDebitCash() { return debitCash; }
    public void setDebitCash(double debitCash) { this.debitCash = debitCash; }

    public double getCreditCash() { return creditCash; }
    public void setCreditCash(double creditCash) { this.creditCash = creditCash; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getRfidEpc() { return rfidEpc; }
    public void setRfidEpc(String rfidEpc) { this.rfidEpc = rfidEpc; }

    public String getDateCreated() { return dateCreated; }
    public void setDateCreated(String dateCreated) { this.dateCreated = dateCreated; }
}
