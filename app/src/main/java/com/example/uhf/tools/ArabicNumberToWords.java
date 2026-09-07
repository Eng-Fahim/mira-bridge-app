package com.example.uhf.tools;

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

    public static String getCurrencyArabicName(String currency) {
        if (currency == null) return "";
        switch (currency.toUpperCase()) {
            case "USD": return "دولار أمريكي";
            case "YER": return "ريال يمني";
            case "EGP": return "جنيه مصري";
            case "SAR": return "ريال سعودي";
            case "AED": return "درهم إماراتي";
            default: return currency;
        }
    }

    public static String convert(double number, String currencyName) {
        long integerPart = (long) number;

        if (integerPart == 0) {
            return "صفر " + currencyName + " لا غير";
        }

        StringBuilder result = new StringBuilder();

        // الملايين
        if (integerPart >= 1000000) {
            int millions = (int) (integerPart / 1000000);
            result.append(getSectionText(millions, "مليون", "مليونان", "ملايين", "مليوناً"));
            integerPart %= 1000000;
        }

        // الآلاف
        if (integerPart >= 1000) {
            int thousands = (int) (integerPart / 1000);
            if (result.length() > 0) result.append(" و");
            result.append(getSectionText(thousands, "ألف", "ألفان", "آلاف", "ألفاً"));
            integerPart %= 1000;
        }

        // المئات والعشرات والآحاد
        if (integerPart > 0) {
            if (result.length() > 0) result.append(" و");
            result.append(convertThreeDigits((int) integerPart));
        }

        result.append(" ").append(currencyName).append(" لا غير");

        return result.toString().trim();
    }

    private static String getSectionText(int number, String singular, String pair, String plural, String overTen) {
        if (number == 1) {
            return singular;
        } else if (number == 2) {
            return pair;
        } else if (number >= 3 && number <= 10) {
            return convertThreeDigits(number) + " " + plural;
        } else {
            return convertThreeDigits(number) + " " + overTen;
        }
    }

    private static String convertThreeDigits(int number) {
        StringBuilder result = new StringBuilder();

        int hundreds = number / 100;
        int remainder = number % 100;

        if (hundreds > 0) {
            result.append(HUNDREDS[hundreds]);
        }

        if (remainder > 0) {
            if (result.length() > 0) {
                result.append(" و");
            }

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

        return result.toString();
    }
}
