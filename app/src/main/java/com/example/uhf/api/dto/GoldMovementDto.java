package com.example.uhf.api.dto;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class GoldMovementDto {
    
    @SerializedName("from_date")
    public String fromDate;
    
    @SerializedName("to_date")
    public String toDate;
    
    @SerializedName("accounts")
    public List<AccountMovement> accounts;
    
    @SerializedName("totals")
    public Totals totals;
    
    public static class AccountMovement {
        @SerializedName("account_id")
        public long accountId;
        
        @SerializedName("account_code")
        public String accountCode;
        
        @SerializedName("account_name")
        public String accountName;
        
        @SerializedName("opening")
        public double opening;
        
        @SerializedName("gold_in")
        public double goldIn;
        
        @SerializedName("gold_out")
        public double goldOut;
        
        @SerializedName("closing")
        public double closing;
        
        @SerializedName("movement")
        public double movement;
        
        // ✅ القيم المالية للحساب
        @SerializedName("opening_value") public double openingValue;
        @SerializedName("value_in")      public double valueIn;
        @SerializedName("value_out")     public double valueOut;
        @SerializedName("closing_value") public double closingValue;
    }
    
    public static class Totals {
        @SerializedName("opening_gold")
        public double openingGold;
        
        @SerializedName("gold_in")
        public double goldIn;
        
        @SerializedName("gold_out")
        public double goldOut;
        
        @SerializedName("closing_gold")
        public double closingGold;
        
        // ✅ القيم المالية الإجمالية
        @SerializedName("opening_value") public double openingValue;
        @SerializedName("value_in")      public double valueIn;
        @SerializedName("value_out")     public double valueOut;
        @SerializedName("closing_value") public double closingValue;
    }
}
