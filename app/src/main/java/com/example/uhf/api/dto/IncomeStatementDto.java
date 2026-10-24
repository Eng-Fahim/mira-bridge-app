package com.example.uhf.api.dto;

import com.google.gson.annotations.SerializedName;

public class IncomeStatementDto {
    
    @SerializedName("from_date")
    public String fromDate;
    
    @SerializedName("to_date")
    public String toDate;
    
    @SerializedName("branch_id")
    public int branchId;
    
    // ═══ الإيرادات ═══
    @SerializedName("sales_revenue")
    public double salesRevenue;
    
    @SerializedName("sales_discounts")
    public double salesDiscounts;
    
    @SerializedName("net_revenue")
    public double netRevenue;
    
    @SerializedName("making_charges")
    public double makingCharges;
    
    @SerializedName("other_revenue")
    public double otherRevenue;
    
    // ═══ التكلفة ═══
    @SerializedName("cogs")
    public double cogs;
    
    @SerializedName("gross_profit")
    public double grossProfit;
    
    @SerializedName("gross_profit_margin")
    public double grossProfitMargin;
    
    // ═══ المصروفات التشغيلية ═══
    @SerializedName("operating_expenses")
    public double operatingExpenses;
    
    @SerializedName("depreciation")
    public double depreciation;
    
    @SerializedName("total_operating")
    public double totalOperating;
    
    @SerializedName("operating_profit")
    public double operatingProfit;
    
    // ═══ النتيجة النهائية ═══
    @SerializedName("profit_before_zakat")
    public double profitBeforeZakat;
    
    @SerializedName("zakat")
    public double zakat;
    
    @SerializedName("net_profit")
    public double netProfit;
    
    @SerializedName("net_profit_margin")
    public double netProfitMargin;
    
    // ═══ الذهب ═══
    @SerializedName("gold_sales_revenue")
    public double goldSalesRevenue;
    
    @SerializedName("gold_cogs")
    public double goldCogs;
    
    @SerializedName("gold_gross_profit")
    public double goldGrossProfit;

    @SerializedName("total_gold_revenue") public double totalGoldRevenue;
    @SerializedName("total_gold_expense") public double totalGoldExpense;
    @SerializedName("net_gold_profit")    public double netGoldProfit;
}
