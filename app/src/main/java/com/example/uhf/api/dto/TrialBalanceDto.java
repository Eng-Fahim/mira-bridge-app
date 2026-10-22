package com.example.uhf.api.dto;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class TrialBalanceDto {
    
    @SerializedName("rows")
    public List<TrialBalanceRow> rows;
    
    @SerializedName("from_date")
    public String fromDate;
    
    @SerializedName("to_date")
    public String toDate;
    
    @SerializedName("total_debit")
    public double totalDebit;
    
    @SerializedName("total_credit")
    public double totalCredit;
    
    @SerializedName("total_gold_debit")
    public double totalGoldDebit;
    
    @SerializedName("total_gold_credit")
    public double totalGoldCredit;
    
    @SerializedName("balanced")
    public boolean balanced;
    
    public static class TrialBalanceRow {
        @SerializedName("account_id")
        public long accountId;
        
        @SerializedName("account_code")
        public String accountCode;
        
        @SerializedName("account_name_ar")
        public String accountNameAr;
        
        @SerializedName("account_type")
        public String accountType;
        
        @SerializedName("nature")
        public String nature;
        
        @SerializedName("total_debit")
        public double totalDebit;
        
        @SerializedName("total_credit")
        public double totalCredit;
        
        @SerializedName("balance")
        public double balance;
        
        @SerializedName("gold_debit")
        public double goldDebit;
        
        @SerializedName("gold_credit")
        public double goldCredit;
        
        @SerializedName("gold_balance")
        public double goldBalance;
        
        @SerializedName("is_leaf")
        public boolean isLeaf;
        
        // ✅ الحقول الجديدة التي طلبها البروفيسور (أول المدة، حركة الفترة، آخر المدة)
        @SerializedName("opening_debit")   public double openingDebit;
        @SerializedName("opening_credit")  public double openingCredit;
        @SerializedName("period_debit")    public double periodDebit;
        @SerializedName("period_credit")   public double periodCredit;
        @SerializedName("closing_debit")   public double closingDebit;
        @SerializedName("closing_credit")  public double closingCredit;
    }
}
