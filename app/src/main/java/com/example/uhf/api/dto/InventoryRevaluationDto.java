package com.example.uhf.api.dto;
import com.google.gson.annotations.SerializedName;
public class InventoryRevaluationDto {
    @SerializedName("total_gold_grams") public double totalGoldGrams;
    @SerializedName("current_gram_price") public double currentGramPrice;
    @SerializedName("book_value") public double bookValue;
    @SerializedName("market_value") public double marketValue;
    @SerializedName("difference") public double difference;
    @SerializedName("status") public String status;
}
