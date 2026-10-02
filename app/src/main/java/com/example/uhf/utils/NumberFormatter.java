package com.example.uhf.utils;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public class NumberFormatter {
    
    private static final DecimalFormat MONEY_FMT;
    private static final DecimalFormat GOLD_FMT;
    private static final DecimalFormat QTY_FMT;
    
    static {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        symbols.setGroupingSeparator(',');
        symbols.setDecimalSeparator('.');
        
        MONEY_FMT = new DecimalFormat("#,##0.00", symbols);
        GOLD_FMT = new DecimalFormat("#,##0.000", symbols);
        QTY_FMT = new DecimalFormat("#,##0", symbols);
    }
    
    public static String money(double value) {
        return MONEY_FMT.format(value) + " ر.ي";
    }
    
    public static String money(double value, String currency) {
        String symbol;
        switch (currency != null ? currency.toUpperCase() : "YER") {
            case "USD": symbol = "$"; break;
            case "SAR": symbol = "ر.س"; break;
            default: symbol = "ر.ي"; break;
        }
        return MONEY_FMT.format(value) + " " + symbol;
    }
    
    public static String moneyShort(double value) {
        if (Math.abs(value) >= 1_000_000_000) {
            return String.format(Locale.US, "%.2f مليار", value / 1_000_000_000);
        }
        if (Math.abs(value) >= 1_000_000) {
            return String.format(Locale.US, "%.2f مليون", value / 1_000_000);
        }
        if (Math.abs(value) >= 1_000) {
            return String.format(Locale.US, "%.2f ألف", value / 1_000);
        }
        return MONEY_FMT.format(value);
    }
    
    public static String gold(double grams) {
        return GOLD_FMT.format(grams) + " g";
    }
    
    public static String goldSigned(double grams) {
        String sign = grams >= 0 ? "+" : "";
        return sign + GOLD_FMT.format(grams) + " g";
    }
    
    public static String qty(long count) {
        return QTY_FMT.format(count);
    }
    
    public static String percent(double value) {
        return String.format(Locale.US, "%.2f%%", value);
    }
    
    public static String signed(double value) {
        String sign = value >= 0 ? "+" : "";
        return sign + MONEY_FMT.format(value);
    }
}
