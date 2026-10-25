package com.example.uhf.api.dto;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class AgingReportDto {
    
    @SerializedName("rows")
    public List<AgingRow> rows;
    
    @SerializedName("totals")
    public Totals totals;

    public static class AgingRow {
        @SerializedName("account_id") public long accountId;
        @SerializedName("account_code") public String accountCode;
        @SerializedName("account_name") public String accountName;
        @SerializedName("balance") public double balance;
        @SerializedName("0_30") public double age0_30;
        @SerializedName("31_60") public double age31_60;
        @SerializedName("61_90") public double age61_90;
        @SerializedName("over_90") public double ageOver90;
    }

    public static class Totals {
        @SerializedName("total_0_30") public double total0_30;
        @SerializedName("total_31_60") public double total31_60;
        @SerializedName("total_61_90") public double total61_90;
        @SerializedName("total_over_90") public double totalOver90;
    }
}
