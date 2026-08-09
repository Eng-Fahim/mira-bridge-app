package com.example.uhf.model;

import java.io.Serializable;
import java.text.DecimalFormat;

public class MiraAccountingItem implements Serializable {

    public enum ItemType {
        JOURNAL_ENTRY,     // قيد يومية مزدوج
        CHART_OF_ACCOUNT,  // حساب في دليل الحسابات
        TRIAL_BALANCE      // ميزان مراجعة الذهب والنقدية
    }

    private ItemType itemType;
    private String codeOrRef;       // رقم القيد / رمز الحساب
    private String nameOrDesc;      // البيان / اسم الحساب
    
    // المبالغ النقدية
    private double debitCash;       // مدين (نقدية)
    private double creditCash;      // دائن (نقدية)
    private double cashBalance;     // رصيد نقدي
    private String currency = "YER";

    // موازين الذهب والعيارات
    private double grossWeight;     // الوزن القائم (جرام)
    private int karat;              // العيار (24, 22, 21, 18)
    private double fineGold24Debit; // صافي ذهب 24 (مدين)
    private double fineGold24Credit;// صافي ذهب 24 (دائن)
    private double fineGoldBalance; // رصيد الذهب الصافي (جرام 24)

    // تفاصيل الربط والميدان
    private double makingFee;       // أجور الصياغة
    private String rfidTag;         // كود RFID المربوط بالقطعة
    private String date;

    private static final DecimalFormat dfGold = new DecimalFormat("#,##0.000");
    private static final DecimalFormat dfCash = new DecimalFormat("#,##0.00");

    // Constructor للقيود المركبة
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
        this.date = date;
    }

    // Constructor مبسط لشجرة الحسابات والميزان
    public MiraAccountingItem(ItemType itemType, String codeOrRef, String nameOrDesc,
                                double fineGoldBalance, double cashBalance, String currency) {
        this.itemType = itemType;
        this.codeOrRef = codeOrRef;
        this.nameOrDesc = nameOrDesc;
        this.fineGoldBalance = fineGoldBalance;
        this.cashBalance = cashBalance;
        this.currency = currency != null ? currency : "YER";
    }

    // معادلة تحويل الوزن إلى جرام صافي 24k
    public static double convertToFineGold24(double grossWeight, int karat) {
        if (karat <= 0) return 0.0;
        return (grossWeight * karat) / 24.0;
    }

    // Getters & Setters الأساسية
    public ItemType getItemType() { return itemType; }
    public String getCodeOrRef() { return codeOrRef; }
    public String getNameOrDesc() { return nameOrDesc; }
    public double getDebitCash() { return debitCash; }
    public double getCreditCash() { return creditCash; }
    public double getCashBalance() { return cashBalance; }
    public String getCurrency() { return currency; }
    public double getGrossWeight() { return grossWeight; }
    public int getKarat() { return karat; }
    public double getFineGold24Debit() { return fineGold24Debit; }
    public double getFineGold24Credit() { return fineGold24Credit; }
    public double getFineGoldBalance() { return fineGoldBalance; }
    public double getMakingFee() { return makingFee; }
    public String getRfidTag() { return rfidTag; }
    public String getDate() { return date; }

    public void setRfidTag(String rfidTag) { this.rfidTag = rfidTag; }
    public void setMakingFee(double makingFee) { this.makingFee = makingFee; }

    // Backward Compatibility Methods لدعم الأكواد المتبقية
    public String getCol1Text() { return codeOrRef; }
    public String getCol2Text() { return nameOrDesc; }
    public String getCol3Text() { 
        if (itemType == ItemType.JOURNAL_ENTRY) return dfGold.format(fineGold24Debit) + " جم";
        return dfGold.format(fineGoldBalance) + " جم";
    }
    public String getCol4Text() { 
        if (itemType == ItemType.JOURNAL_ENTRY) return dfCash.format(debitCash) + " " + currency;
        return dfCash.format(cashBalance) + " " + currency;
    }
}
