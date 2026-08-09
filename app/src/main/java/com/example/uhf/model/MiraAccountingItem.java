package com.example.uhf.model;

import java.io.Serializable;
import java.text.DecimalFormat;

public class MiraAccountingItem implements Serializable {

    public enum ItemType {
        JOURNAL_ENTRY,     
        CHART_OF_ACCOUNT,  
        TRIAL_BALANCE,     
        GOLD_TRANSFER      
    }

    public enum AccountCategory {
        ASSET_CASH,        
        ASSET_GOLD,        
        LIABILITY,         
        EQUITY,            
        REVENUE,           
        EXPENSE            
    }

    private String id;
    private ItemType itemType = ItemType.JOURNAL_ENTRY;
    private String accountCode = "";
    private String accountName = "";
    private String description = "";
    private String referenceNo = "";     
    private String date = "";

    private double debitCash = 0.0;
    private double creditCash = 0.0;
    private double balanceCash = 0.0;
    private String currency = "YER";

    private double grossWeight = 0.0;     
    private int karat = 0;                
    private double fineWeight24Debit = 0.0;  
    private double fineWeight24Credit = 0.0; 
    private double netGoldBalance = 0.0;     

    private double makingFee = 0.0;       
    private String rfidEpc = "";         
    private AccountCategory category;

    private static final DecimalFormat dfGold = new DecimalFormat("#,##0.000");
    private static final DecimalFormat dfCash = new DecimalFormat("#,##0.00");

    public MiraAccountingItem() {}

    // Constructor 1: القيود اليومية مع التاريخ
    public MiraAccountingItem(ItemType itemType, String referenceNo, String description,
                                double fineWeight24Debit, double fineWeight24Credit,
                                double debitCash, double creditCash, String currency, String date) {
        this.itemType = itemType;
        this.referenceNo = referenceNo;
        this.description = description;
        this.fineWeight24Debit = fineWeight24Debit;
        this.fineWeight24Credit = fineWeight24Credit;
        this.debitCash = debitCash;
        this.creditCash = creditCash;
        this.currency = currency != null ? currency : "YER";
        this.date = date;
    }

    // Constructor 2: القيود بدون تاريخ
    public MiraAccountingItem(ItemType itemType, String referenceNo, String description,
                                double debitCash, double creditCash,
                                double fineWeight24Debit, double fineWeight24Credit,
                                String currency) {
        this(itemType, referenceNo, description, fineWeight24Debit, fineWeight24Credit, debitCash, creditCash, currency, "");
    }

    // Constructor 3: دليل الحسابات وميزان المراجعة
    public MiraAccountingItem(ItemType itemType, String accountCode, String accountName,
                                double netGoldBalance, double balanceCash, String currency) {
        this.itemType = itemType;
        this.accountCode = accountCode;
        this.accountName = accountName;
        this.netGoldBalance = netGoldBalance;
        this.balanceCash = balanceCash;
        this.currency = currency != null ? currency : "YER";
    }

    public static double calculateFineWeight24(double grossWeight, int karat) {
        if (karat <= 0) return 0.0;
        return (grossWeight * karat) / 24.0;
    }

    public static double convertToFineGold24(double grossWeight, int karat) {
        return calculateFineWeight24(grossWeight, karat);
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public ItemType getItemType() { return itemType != null ? itemType : ItemType.JOURNAL_ENTRY; }
    public void setItemType(ItemType itemType) { this.itemType = itemType; }

    public String getAccountCode() { return accountCode != null ? accountCode : ""; }
    public void setAccountCode(String accountCode) { this.accountCode = accountCode; }

    public String getAccountName() { return accountName != null ? accountName : ""; }
    public void setAccountName(String accountName) { this.accountName = accountName; }

    public String getDescription() { return description != null ? description : ""; }
    public void setDescription(String description) { this.description = description; }

    public String getReferenceNo() { return referenceNo != null ? referenceNo : ""; }
    public void setReferenceNo(String referenceNo) { this.referenceNo = referenceNo; }

    public String getDate() { return date != null ? date : ""; }
    public void setDate(String date) { this.date = date; }

    public double getDebitCash() { return debitCash; }
    public void setDebitCash(double debitCash) { this.debitCash = debitCash; }

    public double getCreditCash() { return creditCash; }
    public void setCreditCash(double creditCash) { this.creditCash = creditCash; }

    public double getBalanceCash() { return balanceCash; }
    public double getCashBalance() { return balanceCash; }
    public void setBalanceCash(double balanceCash) { this.balanceCash = balanceCash; }

    public String getCurrency() { return currency != null ? currency : "YER"; }
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
    public double getFineGold24Debit() { return fineWeight24Debit; }
    public void setFineWeight24Debit(double fineWeight24Debit) { this.fineWeight24Debit = fineWeight24Debit; }

    public double getFineWeight24Credit() { return fineWeight24Credit; }
    public double getFineGold24Credit() { return fineWeight24Credit; }
    public void setFineWeight24Credit(double fineWeight24Credit) { this.fineWeight24Credit = fineWeight24Credit; }

    public double getNetGoldBalance() { return netGoldBalance; }
    public double getFineGoldBalance() { return netGoldBalance; }
    public void setNetGoldBalance(double netGoldBalance) { this.netGoldBalance = netGoldBalance; }

    public double getMakingFee() { return makingFee; }
    public void setMakingFee(double makingFee) { this.makingFee = makingFee; }

    public String getRfidEpc() { return rfidEpc != null ? rfidEpc : ""; }
    public String getRfidTag() { return getRfidEpc(); }
    public void setRfidEpc(String rfidEpc) { this.rfidEpc = rfidEpc; }
    public void setRfidTag(String rfidTag) { setRfidEpc(rfidTag); }

    public AccountCategory getCategory() { return category; }
    public void setCategory(AccountCategory category) { this.category = category; }

    // Getters متوافقة مع الـ Adapter
    public String getCodeOrRef() { 
        return !getAccountCode().isEmpty() ? getAccountCode() : getReferenceNo(); 
    }
    
    public String getNameOrDesc() { 
        return !getAccountName().isEmpty() ? getAccountName() : getDescription(); 
    }

    public String getCol1Text() { return getCodeOrRef(); }
    public String getCol2Text() { return getNameOrDesc(); }
    
    public String getCol3Text() { 
        if (getItemType() == ItemType.JOURNAL_ENTRY) {
            double netGold = fineWeight24Debit - fineWeight24Credit;
            return dfGold.format(netGold) + " جم";
        }
        return dfGold.format(netGoldBalance) + " جم 24";
    }
    
    public String getCol4Text() { 
        if (getItemType() == ItemType.JOURNAL_ENTRY) {
            double netCash = debitCash - creditCash;
            return dfCash.format(netCash) + " " + getCurrency();
        }
        return dfCash.format(balanceCash) + " " + getCurrency();
    }
}
