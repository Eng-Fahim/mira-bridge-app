package com.example.uhf.api.dto;

import com.google.gson.annotations.SerializedName;

public class JournalLineDto {
    
    @SerializedName("id")
    public long id;
    
    @SerializedName("entry_id")
    public long entryId;
    
    @SerializedName("account_id")
    public long accountId;
    
    @SerializedName("account_code")
    public String accountCode;
    
    @SerializedName("account_name_ar")
    public String accountNameAr;
    
    @SerializedName("description")
    public String description;
    
    @SerializedName("debit")
    public double debit;
    
    @SerializedName("credit")
    public double credit;
    
    @SerializedName("base_debit")
    public double baseDebit;
    
    @SerializedName("base_credit")
    public double baseCredit;
    
    @SerializedName("currency")
    public String currency;
    
    @SerializedName("exchange_rate")
    public double exchangeRate;
    
    @SerializedName("gold_debit_grams")
    public double goldDebitGrams;
    
    @SerializedName("gold_credit_grams")
    public double goldCreditGrams;
    
    @SerializedName("original_karat")
    public String originalKarat;
    
    @SerializedName("original_weight_grams")
    public double originalWeightGrams;
    
    @SerializedName("karat_factor")
    public double karatFactor;
    
    @SerializedName("branch_id")
    public int branchId;
    
    @SerializedName("party_id")
    public Long partyId;
    
    public double getNetGold() {
        return goldDebitGrams - goldCreditGrams;
    }
    
    public boolean isDebit() { return debit > 0; }
    public boolean isCredit() { return credit > 0; }
}
