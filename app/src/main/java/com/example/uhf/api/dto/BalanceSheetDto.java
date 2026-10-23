package com.example.uhf.api.dto;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class BalanceSheetDto {
    
    @SerializedName("as_of")
    public String asOf;
    
    @SerializedName("assets")
    public List<TrialBalanceDto.TrialBalanceRow> assets;
    
    @SerializedName("liabilities")
    public List<TrialBalanceDto.TrialBalanceRow> liabilities;
    
    @SerializedName("equity")
    public List<TrialBalanceDto.TrialBalanceRow> equity;
    
    @SerializedName("total_assets")
    public double totalAssets;
    
    @SerializedName("total_liabilities")
    public double totalLiabilities;
    
    @SerializedName("total_equity")
    public double totalEquity;
    
    @SerializedName("net_income_period")
    public double netIncomePeriod;
    
    @SerializedName("total_equity_with_income")
    public double totalEquityWithIncome;
    
    @SerializedName("balanced")
    public boolean balanced;

    @SerializedName("total_gold_assets")
    public double totalGoldAssets;
    
    @SerializedName("total_gold_liabilities")
    public double totalGoldLiabilities;
}
