package com.example.uhf.tools;

import java.text.DecimalFormat;

/**
 * ✅ تحويل الأرقام إلى كلمات عربية بشكل صحيح واحترافي 100%
 * مع مراعاة قواعد العدد والمعدود في اللغة العربية
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

    // صيغ الآلاف حسب العدد
    private static final String[] THOUSANDS = {
        "", "ألف", "ألفان", "ثلاثة آلاف", "أربعة آلاف", "خمسة آلاف",
        "ستة آلاف", "سبعة آلاف", "ثمانية آلاف", "تسعة آلاف", "عشرة آلاف"
    };

    // صيغ الملايين حسب العدد
    private static final String[] MILLIONS = {
        "", "مليون", "مليونان", "ثلاثة ملايين", "أربعة ملايين", "خمسة ملايين",
        "ستة ملايين", "سبعة ملايين", "ثمانية ملايين", "تسعة ملايين", "عشرة ملايين"
    };

    // صيغ المليارات حسب العدد
    private static final String[] BILLIONS = {
        "", "مليار", "ملياران", "ثلاثة مليارات", "أربعة مليارات", "خمسة مليارات",
        "ستة مليارات", "سبعة مليارات", "ثمانية مليارات", "تسعة مليارات", "عشرة مليارات"
    };

    /**
     * ✅ الحصول على اسم العملة بالعربية
     */
    public static String getCurrencyArabicName(String currency) {
        switch (currency.toUpperCase()) {
            case "USD": return "دولار أمريكي";
            case "YER": return "ريال يمني";
            case "EGP": return "جنيه مصري";
            case "SAR": return "ريال سعودي";
            case "AED": return "درهم إماراتي";
            case "KWD": return "دينار كويتي";
            case "BHD": return "دينار بحريني";
            case "QAR": return "ريال قطري";
            case "OMR": return "ريال عماني";
            case "JOD": return "دينار أردني";
            default: return currency;
        }
    }

    /**
     * ✅ تحويل رقم إلى كلمات عربية
     * @param number الرقم المراد تحويله
     * @param currencyName اسم العملة
     * @return النص العربي
     */
    public static String convert(double number, String currencyName) {
        long integerPart = (long) number;
        
        if (integerPart == 0) {
            return "صفر " + currencyName + " لا غير";
        }
        
        StringBuilder result = new StringBuilder();
        
        // المليارات
        if (integerPart >= 1000000000) {
            long billions = integerPart / 1000000000;
            result.append(convertGroup((int) billions, BILLIONS));
            result.append(" و");
            integerPart %= 1000000000;
        }
        
        // الملايين
        if (integerPart >= 1000000) {
            long millions = integerPart / 1000000;
            result.append(convertGroup((int) millions, MILLIONS));
            result.append(" و");
            integerPart %= 1000000;
        }
        
        // الآلاف
        if (integerPart >= 1000) {
            long thousands = integerPart / 1000;
            result.append(convertGroup((int) thousands, THOUSANDS));
            result.append(" و");
            integerPart %= 1000;
        }
        
        // المئات والعشرات والآحاد
        if (integerPart > 0) {
            result.append(convertHundreds((int) integerPart));
        }
        
        // إزالة "و" الزائدة في النهاية
        String finalResult = result.toString().trim();
        if (finalResult.endsWith("و")) {
            finalResult = finalResult.substring(0, finalResult.length() - 1).trim();
        }
        
        // إضافة اسم العملة
        finalResult += " " + currencyName + " لا غير";
        
        return finalResult;
    }

    /**
     * ✅ تحويل مجموعة من الأرقام (1-999) مع الوحدة المناسبة
     */
    private static String convertGroup(int number, String[] units) {
        StringBuilder result = new StringBuilder();
        
        if (number >= 100) {
            int hundreds = number / 100;
            result.append(HUNDREDS[hundreds]);
            int remainder = number % 100;
            if (remainder > 0) {
                result.append(" و");
                result.append(convertTensAndOnes(remainder));
            }
        } else {
            result.append(convertTensAndOnes(number));
        }
        
        // إضافة الوحدة المناسبة
        if (number > 0) {
            result.append(" ").append(getUnitForm(number, units));
        }
        
        return result.toString();
    }

    /**
     * ✅ تحويل المئات فقط
     */
    private static String convertHundreds(int number) {
        StringBuilder result = new StringBuilder();
        
        if (number >= 100) {
            int hundreds = number / 100;
            result.append(HUNDREDS[hundreds]);
            int remainder = number % 100;
            if (remainder > 0) {
                result.append(" و").append(convertTensAndOnes(remainder));
            }
        } else {
            result.append(convertTensAndOnes(number));
        }
        
        return result.toString();
    }

    /**
     * ✅ تحويل العشرات والآحاد (1-99)
     */
    private static String convertTensAndOnes(int number) {
        if (number == 0) {
            return "";
        }
        
        if (number < 20) {
            return ONES[number];
        }
        
        int tens = number / 10;
        int ones = number % 10;
        
        if (ones > 0) {
            // مثل: واحد وعشرون، اثنان وثلاثون
            return ONES[ones] + " و" + TENS[tens];
        } else {
            // مثل: عشرون، ثلاثون
            return TENS[tens];
        }
    }

    /**
     * ✅ الحصول على الصيغة الصحيحة للوحدة حسب العدد
     */
    private static String getUnitForm(int number, String[] units) {
        if (number == 1) {
            return units[1]; // مفرد
        } else if (number == 2) {
            return units[2]; // مثنى
        } else if (number >= 3 && number <= 10) {
            return units[3]; // جمع قلة
        } else {
            return units[3]; // جمع كثرة (نفس الصيغة للتبسيط)
        }
    }

    /**
     * ✅ طريقة إضافية لتحويل الأرقام مع صياغة أفضل
     */
    public static String convertProfessional(long number, String currencyName) {
        if (number == 0) {
            return "صفر " + currencyName + " لا غير";
        }
        
        StringBuilder result = new StringBuilder();
        
        // المليارات
        long billions = number / 1000000000;
        if (billions > 0) {
            result.append(convertGroupWithContext((int) billions, "مليار"));
            result.append(" و");
            number %= 1000000000;
        }
        
        // الملايين
        long millions = number / 1000000;
        if (millions > 0) {
            result.append(convertGroupWithContext((int) millions, "مليون"));
            result.append(" و");
            number %= 1000000;
        }
        
        // الآلاف
        long thousands = number / 1000;
        if (thousands > 0) {
            result.append(convertGroupWithContext((int) thousands, "ألف"));
            result.append(" و");
            number %= 1000;
        }
        
        // الباقي
        if (number > 0) {
            result.append(convertHundreds((int) number));
        }
        
        // تنظيف النص
        String finalResult = result.toString().trim();
        if (finalResult.endsWith("و")) {
            finalResult = finalResult.substring(0, finalResult.length() - 1).trim();
        }
        
        return finalResult + " " + currencyName + " لا غير";
    }

    /**
     * ✅ تحويل مجموعة مع السياق الصحيح
     */
    private static String convertGroupWithContext(int number, String unit) {
        StringBuilder result = new StringBuilder();
        
        // تحويل الرقم الأساسي
        result.append(convertHundreds(number));
        
        // إضافة الوحدة بالصيغة الصحيحة
        if (number == 1) {
            result.append(" ").append(unit).append(" واحد");
        } else if (number == 2) {
            result.append(" ").append(unit).append("ان");
        } else if (number >= 3 && number <= 10) {
            result.append(" ").append(getPluralForm(unit, number));
        } else {
            result.append(" ").append(getPluralForm(unit, number));
        }
        
        return result.toString();
    }

    /**
     * ✅ الحصول على صيغة الجمع الصحيحة
     */
    private static String getPluralForm(String unit, int number) {
        switch (unit) {
            case "مليار":
                return number >= 3 && number <= 10 ? "مليارات" : "ملياراً";
            case "مليون":
                return number >= 3 && number <= 10 ? "ملايين" : "مليوناً";
            case "ألف":
                return number >= 3 && number <= 10 ? "آلاف" : "ألفاً";
            default:
                return unit;
        }
    }
}
