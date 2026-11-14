package com.example.uhf.tools;

import java.util.Calendar;
import java.util.Locale;

public final class GreetingHelper {

    private GreetingHelper() {}

    public static String getDynamicGreeting(String userName, boolean isArabic) {
        Calendar cal = Calendar.getInstance();
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK);
        String name = (userName == null || userName.trim().isEmpty()) ? "" : userName.split(" ")[0];

        if (isArabic) {
            String greet;
            if (dayOfWeek == Calendar.FRIDAY)      greet = "جمعة مباركة 🌙";
            else if (hour >= 4 && hour <= 11)      greet = "صباح الخير ☀️";
            else if (hour >= 12 && hour <= 16)     greet = "طاب يومك 🌤️";
            else if (hour >= 17 && hour <= 21)     greet = "مساء الخير 🌇";
            else                                    greet = "ليلة سعيدة 🌃";
            return name.isEmpty() ? greet : name + " • " + greet;
        } else {
            String greet;
            if (dayOfWeek == Calendar.FRIDAY)      greet = "Blessed Friday 🌙";
            else if (hour >= 4 && hour <= 11)      greet = "Good Morning ☀️";
            else if (hour >= 12 && hour <= 16)     greet = "Good Afternoon 🌤️";
            else if (hour >= 17 && hour <= 21)     greet = "Good Evening 🌇";
            else                                    greet = "Good Night 🌃";
            return name.isEmpty() ? greet : name + " • " + greet;
        }
    }
}
