package com.example.uhf.tools;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 🎨 ThemeHelper - إدارة ثيم التطبيق وألوانه
 */
// ✅ صحيح
public class MiraThemeHelper {

    private static final String PREFS_NAME = "mira_theme_settings";

    // ألوان أساسية
    public static final String PRIMARY = "#0284C7";
    public static final String SUCCESS = "#16A34A";
    public static final String WARNING = "#F59E0B";
    public static final String DANGER = "#DC2626";
    public static final String INFO = "#7C3AED";

    // ألوان خلفيات
    public static final String BG_SUCCESS = "#F0FDF4";
    public static final String BG_ERROR = "#FEF2F2";
    public static final String BG_WARNING = "#FFFBEB";
    public static final String BG_INFO = "#EFF6FF";

    public static void setDarkMode(Context context, boolean enabled) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean("dark_mode", enabled).apply();
    }

    public static boolean isDarkMode(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean("dark_mode", false);
    }
}
