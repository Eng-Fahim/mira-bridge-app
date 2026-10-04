package com.example.uhf.utils;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class DateHelper {
    
    private static final SimpleDateFormat API_FMT = 
        new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final SimpleDateFormat DISPLAY_FMT = 
        new SimpleDateFormat("dd/MM/yyyy", Locale.US);
    private static final SimpleDateFormat AR_FMT = 
        new SimpleDateFormat("dd MMM yyyy", new Locale("ar"));
    
    public static String todayApi() {
        return API_FMT.format(new Date());
    }
    
    public static String startOfYearApi() {
    Calendar cal = Calendar.getInstance();
    int currentYear = cal.get(Calendar.YEAR);
    // ⚠️ إذا كان تاريخ الجهاز قبل 2026-01-01، استخدم 2026 (سنة البداية)
    if (currentYear < 2026) {
        currentYear = 2026;
    }
    cal.set(currentYear, Calendar.JANUARY, 1, 0, 0, 0);
    cal.set(Calendar.MILLISECOND, 0);
    return API_FMT.format(cal.getTime());
}
    
    public static String startOfMonthApi() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.DAY_OF_MONTH, 1);
        return API_FMT.format(cal.getTime());
    }
    
    public static String startOf30DaysApi() {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, -30);
        return API_FMT.format(cal.getTime());
    }
    
    public static String toDisplay(String apiDate) {
        if (apiDate == null || apiDate.isEmpty()) return "";
        try {
            Date date = API_FMT.parse(apiDate);
            return DISPLAY_FMT.format(date);
        } catch (Exception e) {
            return apiDate;
        }
    }
    
    public static String toArabic(String apiDate) {
        if (apiDate == null || apiDate.isEmpty()) return "";
        try {
            Date date = API_FMT.parse(apiDate);
            return AR_FMT.format(date);
        } catch (Exception e) {
            return apiDate;
        }
    }
    
    public static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }
}
