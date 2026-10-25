package com.example.uhf.api.dto;

import com.google.gson.annotations.SerializedName;

public class ProfitAnalysisDto {
    @SerializedName("gold_revenue") public double goldRevenue;
    @SerializedName("making_revenue") public double makingRevenue;
    @SerializedName("cogs") public double cogs;
    @SerializedName("gold_profit") public double goldProfit;
    @SerializedName("making_profit") public double makingProfit;
    @SerializedName("total_profit") public double totalProfit;
}
