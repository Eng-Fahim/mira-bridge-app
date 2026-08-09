package com.example.uhf.model;

import java.io.Serializable;

public class MiraAccountingItem implements Serializable {

    public enum ItemType { JOURNAL_ENTRY, CHART_OF_ACCOUNT, TRIAL_BALANCE }

    private ItemType itemType = ItemType.JOURNAL_ENTRY;
    private String codeOrRef = "";
    private String nameOrDesc = "";
    private double debitCash = 0.0;
    private double creditCash = 0.0;
    private double cashBalance = 0.0;
    private String currency = "YER";
    private double fineGold24Debit = 0.0;
    private double fineGold24Credit = 0.0;
    private double fineGoldBalance = 0.0;
    private String rfidTag = "";

    public MiraAccountingItem() {}

    public MiraAccountingItem(ItemType itemType, String codeOrRef, String nameOrDesc,
                                double fineGold24Debit, double fineGold24Credit,
                                double debitCash, double creditCash, String currency, String date) {
        this.itemType = itemType;
        this.codeOrRef = codeOrRef;
        this.nameOrDesc = nameOrDesc;
        this.fineGold24Debit = fineGold24Debit;
        this.fineGold24Credit = fineGold24Credit;
        this.debitCash = debitCash;
        this.creditCash = creditCash;
        this.currency = currency != null ? currency : "YER";
    }

    public ItemType getItemType() { return itemType != null ? itemType : ItemType.JOURNAL_ENTRY; }
    public String getCodeOrRef() { return codeOrRef != null ? codeOrRef : ""; }
    public String getNameOrDesc() { return nameOrDesc != null ? nameOrDesc : ""; }
    public double getDebitCash() { return debitCash; }
    public double getCreditCash() { return creditCash; }
    public double getCashBalance() { return cashBalance; }
    public String getCurrency() { return currency != null ? currency : "YER"; }
    public double getFineGold24Debit() { return fineGold24Debit; }
    public double getFineGold24Credit() { return fineGold24Credit; }
    public double getFineGoldBalance() { return fineGoldBalance; }
    public String getRfidTag() { return rfidTag != null ? rfidTag : ""; }

    // Getters آمنة كلياً لجميع محولات الـ Adapter
    public String getCol1Text() { return getCodeOrRef(); }
    public String getCol2Text() { return getNameOrDesc(); }
    public String getCol3Text() { return String.format("%.3f جم", fineGold24Debit - fineGold24Credit); }
    public String getCol4Text() { return String.format("%.2f %s", debitCash - creditCash, getCurrency()); }
}
