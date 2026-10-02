package com.example.uhf.utils;

import java.util.HashMap;
import java.util.Map;

public class GoldCalculator {
    
    public static final double TROY_OUNCE_IN_GRAMS = 31.1034768;
    
    private static final Map<String, Double> KARAT_FACTORS = new HashMap<>();
    static {
        KARAT_FACTORS.put("24K", 1.000000);
        KARAT_FACTORS.put("23K", 0.958333);
        KARAT_FACTORS.put("22K", 0.916600);
        KARAT_FACTORS.put("21K", 0.875000);
        KARAT_FACTORS.put("20K", 0.833333);
        KARAT_FACTORS.put("18K", 0.750000);
        KARAT_FACTORS.put("14K", 0.583333);
        KARAT_FACTORS.put("12K", 0.500000);
        KARAT_FACTORS.put("10K", 0.416600);
    }
    
    /**
     * تحويل الوزن من أي عيار إلى 24K
     */
    public static double to24K(double weight, String karat) {
        return weight * getKaratFactor(karat);
    }
    
    /**
     * تحويل من 24K إلى عيار آخر
     */
    public static double from24K(double weight24k, String karat) {
        double factor = getKaratFactor(karat);
        return factor > 0 ? weight24k / factor : 0;
    }
    
    /**
     * معامل العيار
     */
    public static double getKaratFactor(String karat) {
        if (karat == null) return 0.875;
        
        String normalized = karat.toUpperCase().trim();
        if (!normalized.endsWith("K")) {
            normalized += "K";
        }
        
        Double factor = KARAT_FACTORS.get(normalized);
        return factor != null ? factor : 0.875;
    }
    
    /**
     * تنسيق عيار للعرض
     */
    public static String formatKarat(String karat) {
        if (karat == null || karat.isEmpty()) return "24K";
        return karat.toUpperCase();
    }
}
