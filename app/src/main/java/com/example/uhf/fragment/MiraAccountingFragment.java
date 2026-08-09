package com.example.uhf.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * Enterprise Accounting Item for Gold & Jewelry ERP
 * Supports Dual-Currency and Fine Gold Weight (24K Standard)
 */
public class MiraAccountingItem implements Serializable {

    public enum ItemType {
        JOURNAL_ENTRY,     // قيد يومية مزدوج
        CHART_OF_ACCOUNT,  // حساب في دليل الحسابات
        TRIAL_BALANCE,     // ميزان مراجعة
        GOLD_TRANSFER      // تحويل ذهب كسر/سبائك
    }

    public enum AccountCategory {
        ASSET_CASH,        // أصول نقدية
        ASSET_GOLD,        // أصول ذهب/مخزون
        LIABILITY,         // التزامات
        EQUITY,            // حقوق ملكية
        REVENUE,           // إيرادات
        EXPENSE            // مصروفات
    }

    // البيانات التعريفية الأساسية
    private String id;
    private ItemType itemType;
    private String accountCode;
    private String accountName;
    private String description;
    private String referenceNo;     // رقم الفاتورة أو المرجع
    private String date;

    // المبالغ النقدية (Fiat Currency)
    private double debitCash;
    private double creditCash;
    private double balanceCash;
    private String currency;        // YER, SAR, USD

    // الحسابات العينية (الذهب والمجوهرات)
    private double grossWeight;     // الوزن القائم (جرام)
    private int karat;              // العيار (24, 22, 21, 18, 14)
    private double fineWeight24Debit;  // صافي الذهب المدين (عيار 24)
    private double fineWeight24Credit; // صافي الذهب الدائن (عيار 24)
    private double netGoldBalance;     // رصيد الذهب الصافي المتبقي

    // تفاصيل الصياغة والربط الميداني
    private double makingFee;       // أجور الصياغة والتصنيع
    private String rfidEpc;         // كود الـ RFID المربوط بالقطعة
    private AccountCategory category;

    // Default Constructor
    public MiraAccountingItem() {}

    // Multi-purpose Constructor for Journal Entries
    public MiraAccountingItem(ItemType itemType, String referenceNo, String description,
                                double debitCash, double creditCash,
                                double fineWeight24Debit, double fineWeight24Credit,
                                String currency) {
        this.itemType = itemType;
        this.referenceNo = referenceNo;
        this.description = description;
        this.debitCash = debitCash;
        this.creditCash = creditCash;
        this.fineWeight24Debit = fineWeight24Debit;
        this.fineWeight24Credit = fineWeight24Credit;
        this.currency = currency != null ? currency : "YER";
    }

    // Helper: حساب الوزن الصافي 24 تلقائياً بناءً على العيار والوزن القائم
    public static double calculateFineWeight24(double grossWeight, int karat) {
        if (karat <= 0) return 0.0;
        return (grossWeight * karat) / 24.0;
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public ItemType getItemType() { return itemType; }
    public void setItemType(ItemType itemType) { this.itemType = itemType; }

    public String getAccountCode() { return accountCode; }
    public void setAccountCode(String accountCode) { this.accountCode = accountCode; }

    public String getAccountName() { return accountName; }
    public void setAccountName(String accountName) { this.accountName = accountName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getReferenceNo() { return referenceNo; }
    public void setReferenceNo(String referenceNo) { this.referenceNo = referenceNo; }

    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }

    public double getDebitCash() { return debitCash; }
    public void setDebitCash(double debitCash) { this.debitCash = debitCash; }

    public double getCreditCash() { return creditCash; }
    public void setCreditCash(double creditCash) { this.creditCash = creditCash; }

    public double getBalanceCash() { return balanceCash; }
    public void setBalanceCash(double balanceCash) { this.balanceCash = balanceCash; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public double getGrossWeight() { return grossWeight; }
    public void setGrossWeight(double grossWeight) {
        this.grossWeight = grossWeight;
        if (this.karat > 0) {
            this.fineWeight24Debit = calculateFineWeight24(grossWeight, this.karat);
        }
    }

    public int getKarat() { return karat; }
    public void setKarat(int karat) {
        this.karat = karat;
        if (this.grossWeight > 0) {
            this.fineWeight24Debit = calculateFineWeight24(this.grossWeight, karat);
        }
    }

    public double getFineWeight24Debit() { return fineWeight24Debit; }
    public void setFineWeight24Debit(double fineWeight24Debit) { this.fineWeight24Debit = fineWeight24Debit; }

    public double getFineWeight24Credit() { return fineWeight24Credit; }
    public void setFineWeight24Credit(double fineWeight24Credit) { this.fineWeight24Credit = fineWeight24Credit; }

    public double getNetGoldBalance() { return netGoldBalance; }
    public void setNetGoldBalance(double netGoldBalance) { this.netGoldBalance = netGoldBalance; }

    public double getMakingFee() { return makingFee; }
    public void setMakingFee(double makingFee) { this.makingFee = makingFee; }

    public String getRfidEpc() { return rfidEpc; }
    public void setRfidEpc(String rfidEpc) { this.rfidEpc = rfidEpc; }

    public AccountCategory getCategory() { return category; }
    public void setCategory(AccountCategory category) { this.category = category; }
}
