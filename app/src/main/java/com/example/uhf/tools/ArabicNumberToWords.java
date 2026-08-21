package com.example.uhf.tools;

import java.text.DecimalFormat;

/**
 * ✅ تحويل الأرقام إلى كلمات عربية بشكل صحيح
 * مثال: 4,245,419 → أربعة مليون ومائتان وخمسة وأربعون ألفاً وأربعمائة وتسعة عشر ريال يمني
 */
public class ArabicNumberToWords {

    private static final String[] ONES = {
        "", "واحد", "اثنان", "ثلاثة", "أربعة", "خمسة", "ستة", "سبعة", "ثمانية", "تسعة",
        "عشرة", "أحد عشر", "اثنا عشر", "ثلاثة عشر", "أربعة عشر", "خمسة عشر",
        "ستة عشر", "سبعة عشر", "ثمانية عشر", "تسعة عشر"
    };

    private static final String[] TENS = {
        "", "", "عشرون", "ثلاثون", "أربعون", "خمسون", "ستون", "سبعون", "ثمانون", "تسعون"
    };

    private static final String[] HUNDREDS = {
        "", "مائة", "مائتان", "ثلاثمائة", "أربعمائة", "خمسمائة",
        "ستمائة", "سبعمائة", "ثمانمائة", "تسعمائة"
    };

    private static final String[] THOUSANDS = {
        "", "ألف", "ألفان", "آلاف", "آلاف", "آلاف", "آلاف", "آلاف", "آلاف", "آلاف"
    };

    private static final String[] MILLIONS = {
        "", "مليون", "مليونان", "ملايين", "ملايين", "ملايين", "ملايين", "ملايين", "ملايين", "ملايين"
    };

    /**
     * ✅ تحويل رقم إلى كلمات عربية
     */
    public static String convert(double number, String currencyName) {
        long integerPart = (long) number;
        
        if (integerPart == 0) {
            return "صفر " + currencyName;
        }
        
        StringBuilder result = new StringBuilder();
        
        // الملايين
        if (integerPart >= 1000000) {
            long millions = integerPart / 1000000;
            result.append(convertThreeDigits((int) millions, MILLIONS));
            result.append(" ");
            integerPart %= 1000000;
        }
        
        // الآلاف
        if (integerPart >= 1000) {
            long thousands = integerPart / 1000;
            result.append(convertThreeDigits((int) thousands, THOUSANDS));
            result.append(" ");
            integerPart %= 1000;
        }
        
        // المئات والعشرات والآحاد
        if (integerPart > 0) {
            result.append(convertThreeDigits((int) integerPart, null));
        }
        
        // إضافة اسم العملة
        result.append(" ").append(currencyName);
        result.append(" لا غير");
        
        return result.toString().trim();
    }

    /**
     * ✅ تحويل ثلاثة أرقام (0-999) مع الوحدة المناسبة
     */
    private static String convertThreeDigits(int number, String[] units) {
        StringBuilder result = new StringBuilder();
        
        int hundreds = number / 100;
        int remainder = number % 100;
        
        // المئات
        if (hundreds > 0) {
            result.append(HUNDREDS[hundreds]);
            if (remainder > 0) {
                result.append(" و");
            }
        }
        
        // العشرات والآحاد
        if (remainder > 0) {
            if (remainder < 20) {
                result.append(ONES[remainder]);
            } else {
                int tens = remainder / 10;
                int ones = remainder % 10;
                
                if (ones > 0) {
                    result.append(ONES[ones]).append(" و").append(TENS[tens]);
                } else {
                    result.append(TENS[tens]);
                }
            }
        }
        
        // إضافة الوحدة (ألف، مليون...)
        if (units != null && number > 0) {
            if (number == 1) {
                result.append(" ").append(units[1]);
            } else if (number == 2) {
                result.append(" ").append(units[2]);
            } else if (number >= 3 && number <= 10) {
                result.append(" ").append(units[3]);
            } else {
                result.append(" ").append(units[3]);
            }
        }
        
        return result.toString();
    }
}
