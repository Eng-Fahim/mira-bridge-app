package com.example.uhf.utils;

import com.example.uhf.manager.MiraPriceManager;

public class PricingCalculator {

    private static final double CARAT_TO_GRAM_FACTOR = 0.20;

    public static double[] calculatePricingDetails(
        double grossWeight, double stoneWeightCarats, double stoneValue,
        int karat, MiraPriceManager priceManager) {

        double stoneWeightGrams = stoneWeightCarats * CARAT_TO_GRAM_FACTOR;
        double netWeight = Math.max(0.0, grossWeight - stoneWeightGrams);

        MiraPriceManager.PricingDetails details = priceManager.calculatePricingDetails(karat, netWeight);
        double finalPrice = details.finalPrice + stoneValue;
        
        return new double[]{
            details.baseGoldCost,
            details.totalMakingCost,
            stoneValue,
            details.baseGoldCost + details.totalMakingCost,
            details.profitAmount,
            finalPrice,
            netWeight
        };
    }

    public static double calculateNetWeight(double grossWeight, double stoneWeightCarats) {
        double stoneWeightGrams = stoneWeightCarats * CARAT_TO_GRAM_FACTOR;
        return Math.max(0.0, grossWeight - stoneWeightGrams);
    }

    public static double caratsToGrams(double carats) {
        return carats * CARAT_TO_GRAM_FACTOR;
    }
}
