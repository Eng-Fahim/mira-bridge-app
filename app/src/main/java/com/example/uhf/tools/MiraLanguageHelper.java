package com.example.uhf.tools;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.view.View;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import java.util.Locale;

/**
 * 🌍 MiraLanguageHelper - إدارة اللغة واتجاه الصفحة
 */
public class MiraLanguageHelper {

    private static final String PREFS_NAME = "mira_language_settings";
    private static final String KEY_LANGUAGE = "app_language";

    // الثوابت
    public static final String LANG_ARABIC = "ar";
    public static final String LANG_ENGLISH = "en";

    /**
     * تحميل اللغة المحفوظة
     */
    public static String getSavedLanguage(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_LANGUAGE, LANG_ARABIC);
    }

    /**
     * حفظ اللغة
     */
    public static void saveLanguage(Context context, String languageCode) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_LANGUAGE, languageCode).apply();
    }

    /**
     * تطبيق اللغة وتحديث Context (إرجاع Context جديد لـ attachBaseContext)
     */
    public static Context applyLanguage(Context context, String languageCode) {
        saveLanguage(context, languageCode);

        Locale locale = new Locale(languageCode);
        Locale.setDefault(locale);

        Resources resources = context.getResources();
        Configuration config = new Configuration(resources.getConfiguration());

        // تطبيق على AppCompatDelegate
        try {
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(languageCode)
            );
        } catch (Exception ignored) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale);
            config.setLayoutDirection(locale);
            return context.createConfigurationContext(config);
        } else {
            config.locale = locale;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                config.setLayoutDirection(locale);
            }
            resources.updateConfiguration(config, resources.getDisplayMetrics());
            return context;
        }
    }

    /**
     * التحقق إذا كانت اللغة الحالية عربية
     */
    public static boolean isArabic(Context context) {
        return getSavedLanguage(context).equals(LANG_ARABIC);
    }

    /**
     * تطبيق اتجاه RTL/LTR على View
     */
    public static void applyLayoutDirection(View view, boolean isArabic) {
        if (view == null) return;
        view.setLayoutDirection(isArabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
    }

    /**
     * الحصول على Locale الحالي
     */
    public static Locale getCurrentLocale(Context context) {
        return new Locale(getSavedLanguage(context));
    }

    /**
     * تبديل اللغة
     */
    public static void toggleLanguage(Context context) {
        String current = getSavedLanguage(context);
        String newLang = current.equals(LANG_ARABIC) ? LANG_ENGLISH : LANG_ARABIC;
        applyLanguage(context, newLang);
    }
}
