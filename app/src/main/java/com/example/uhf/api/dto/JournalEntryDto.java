package com.example.uhf.api.dto;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class JournalEntryDto {
    
    @SerializedName("id")
    public long id;
    
    @SerializedName("entry_number")
    public String entryNumber;
    
    @SerializedName("entry_date")
    public String entryDate;
    
    @SerializedName("branch_id")
    public int branchId;
    
    @SerializedName("reference")
    public String reference;
    
    @SerializedName("source_type")
    public String sourceType;
    
    @SerializedName("description")
    public String description;
    
    @SerializedName("currency")
    public String currency;
    
    @SerializedName("base_currency")
    public String baseCurrency;
    
    @SerializedName("exchange_rate")
    public double exchangeRate;
    
    @SerializedName("total_debit")
    public double totalDebit;
    
    @SerializedName("total_credit")
    public double totalCredit;
    
    @SerializedName("total_debit_base")
    public double totalDebitBase;
    
    @SerializedName("total_credit_base")
    public double totalCreditBase;
    
    @SerializedName("total_gold_debit_grams")
    public double totalGoldDebitGrams;
    
    @SerializedName("total_gold_credit_grams")
    public double totalGoldCreditGrams;
    
    @SerializedName("status")
    public String status;  // draft, posted, reversed, cancelled
    
    @SerializedName("created_at")
    public String createdAt;
    
    @SerializedName("posted_at")
    public String postedAt;
    
    @SerializedName("lines")
    public List<JournalLineDto> lines;
    
    // Getters مفيدة
    public boolean isPosted() { return "posted".equals(status); }
    public boolean isDraft() { return "draft".equals(status); }
    public boolean isBalanced() {
        return Math.abs(totalDebitBase - totalCreditBase) < 0.01;
    }
    public double getNetGold() {
        return totalGoldDebitGrams - totalGoldCreditGrams;
    }
}
