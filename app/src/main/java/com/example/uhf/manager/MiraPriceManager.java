package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;

/**
 * MIRA Price Manager
 * المدير المركزي لأسعار الذهب والعملات
 * يستخدم في جميع أجزاء التطبيق
 */
public class MiraPriceManager {

    private static final String TAG = "MiraPriceManager";
    private static final String PREFS_NAME = "MIRA_GOLD_SETTINGS";
    private static MiraPriceManager instance;
    private static Context appContext;

    private SharedPreferences prefs;
    private final DecimalFormat decimalFormat = new DecimalFormat("#,##0.00");

    // العملات المدعومة
    public static final String CURRENCY_USD = "USD";
    public static final String CURRENCY_YER = "YER";
    public static final String CURRENCY_EGP = "EGP";
    public static final String CURRENCY_SAR = "SAR";
    public static final String CURRENCY_AED = "AED";

    // العيارات المدعومة
    public static final int KARAT_24 = 24;
    public static final int KARAT_22 = 22;
    public static final int KARAT_21 = 21;
    public static final int KARAT_18 = 18;
    public static final int KARAT_14 = 14;
    public static final int KARAT_12 = 12;

    private MiraPriceManager(Context context) {
        appContext = context.getApplicationContext();
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadFromServer();
    }

   /**
 * الحصول على النسخة الوحيدة
 */
public static synchronized MiraPriceManager getInstance(Context context) {
    if (instance == null) {
        instance = new MiraPriceManager(context);
    }
    return instance;
}

// ✅ أضف هذه الدالة هنا
/**
 * تحديث فوري من السيرفر
 */
public void refreshNow() {
    loadFromServer();
}

    // ============================================
    // 💰 دوال أسعار الذهب
    // ============================================

    /**
     * الحصول على سعر الذهب الخام لعيار معين (دولار/جرام)
     */
    public double getGoldPricePerGram(int karat) {
        String key = "gold_price_" + karat + "k";
        return getDoubleFromPrefs(key, 0);
    }

    /**
     * الحصول على هامش الربح لعيار معين
     */
    public double getMargin(int karat) {
        String key = "margin_" + karat + "k";
        return getDoubleFromPrefs(key, 100);
    }

    /**
     * الحصول على المصنعية لعيار معين
     */
    public double getMakingCharge(int karat) {
        String key = "making_" + karat + "k";
        return getDoubleFromPrefs(key, 0);
    }

    /**
     * حساب السعر النهائي للجرام (مع الهامش والمصنعية)
     */
    public double getFinalPricePerGram(int karat) {
        double basePrice = getGoldPricePerGram(karat);
        double margin = getMargin(karat);
        double making = getMakingCharge(karat);

        return (basePrice * margin / 100) + making;
    }

    /**
     * حساب السعر الإجمالي لقطعة ذهب
     */
    public double calculateGoldPrice(int karat, double weightGrams) {
        double finalPricePerGram = getFinalPricePerGram(karat);
        return finalPricePerGram * weightGrams;
    }

    /**
     * حساب السعر مع تحويل العملة
     */
    public double calculateGoldPriceInCurrency(int karat, double weightGrams, String currency) {
        double priceUSD = calculateGoldPrice(karat, weightGrams);
        double exchangeRate = getExchangeRate(currency);
        return priceUSD * exchangeRate;
    }

    // ============================================
    // 💱 دوال العملات
    // ============================================

    /**
     * الحصول على العملة الافتراضية
     */
    public String getDefaultCurrency() {
        return prefs.getString("global_default_currency", CURRENCY_YER);
    }

    /**
     * الحصول على سعر الصرف لعملة معينة
     */
    public double getExchangeRate(String currency) {
        if (currency.equals(CURRENCY_USD)) {
            return 1.0;
        }

        // التحقق من مصدر السعر (رسمي أو سوقي)
        String source = prefs.getString("exchange_source_" + currency, "official");
        String key = "exchange_" + source + "_" + currency;
        
        double rate = getDoubleFromPrefs(key, 0);
        return rate > 0 ? rate : 1.0;
    }

    /**
     * تحويل مبلغ من دولار إلى عملة أخرى
     */
    public double convertFromUSD(double amountUSD, String toCurrency) {
        double rate = getExchangeRate(toCurrency);
        return amountUSD * rate;
    }

    /**
     * تحويل مبلغ إلى دولار
     */
    public double convertToUSD(double amount, String fromCurrency) {
        double rate = getExchangeRate(fromCurrency);
        return rate > 0 ? amount / rate : amount;
    }

    /**
     * تحويل مبلغ من عملة إلى أخرى
     */
    public double convertCurrency(double amount, String fromCurrency, String toCurrency) {
        double amountUSD = convertToUSD(amount, fromCurrency);
        return convertFromUSD(amountUSD, toCurrency);
    }

    /**
     * تنسيق السعر حسب العملة
     */
    public String formatPrice(double priceUSD, String currency) {
        double convertedPrice = convertFromUSD(priceUSD, currency);
        String symbol = getCurrencySymbol(currency);
        return symbol + " " + decimalFormat.format(convertedPrice);
    }

    /**
     * تنسيق السعر بالعملة الافتراضية
     */
    public String formatPriceDefault(double priceUSD) {
        String defaultCurrency = getDefaultCurrency();
        return formatPrice(priceUSD, defaultCurrency);
    }

    /**
     * الحصول على رمز العملة
     */
    public String getCurrencySymbol(String currency) {
        switch (currency) {
            case CURRENCY_USD:
                return "$";
            case CURRENCY_YER:
                return "ريال";
            case CURRENCY_EGP:
                return "ج.م";
            case CURRENCY_SAR:
                return "ر.س";
            case CURRENCY_AED:
                return "د.إ";
            default:
                return currency;
        }
    }

    /**
     * الحصول على اسم العملة بالعربية
     */
    public String getCurrencyNameArabic(String currency) {
        switch (currency) {
            case CURRENCY_USD:
                return "دولار";
            case CURRENCY_YER:
                return "ريال يمني";
            case CURRENCY_EGP:
                return "جنيه مصري";
            case CURRENCY_SAR:
                return "ريال سعودي";
            case CURRENCY_AED:
                return "درهم إماراتي";
            default:
                return currency;
        }
    }

    // ============================================
    // 📊 دوال مساعدة للعرض
    // ============================================

    /**
     * الحصول على ملخص سعر قطعة ذهب
     */
    public String getGoldItemPriceSummary(int karat, double weightGrams) {
        double priceUSD = calculateGoldPrice(karat, weightGrams);
        String defaultCurrency = getDefaultCurrency();
        double priceInCurrency = convertFromUSD(priceUSD, defaultCurrency);
        
        return String.format(
            "%s %s (%s %s)",
            decimalFormat.format(priceInCurrency),
            getCurrencySymbol(defaultCurrency),
            decimalFormat.format(priceUSD),
            "$"
        );
    }

    /**
     * الحصول على جميع الأسعار الحالية
     */
    public Map<String, Double> getAllGoldPrices() {
        Map<String, Double> prices = new HashMap<>();
        int[] karats = {KARAT_24, KARAT_22, KARAT_21, KARAT_18, KARAT_14, KARAT_12};
        
        for (int karat : karats) {
            prices.put(karat + "k_base", getGoldPricePerGram(karat));
            prices.put(karat + "k_final", getFinalPricePerGram(karat));
        }
        
        return prices;
    }

    /**
     * الحصول على جميع أسعار الصرف
     */
    public Map<String, Double> getAllExchangeRates() {
        Map<String, Double> rates = new HashMap<>();
        String[] currencies = {CURRENCY_YER, CURRENCY_EGP, CURRENCY_SAR, CURRENCY_AED};
        
        for (String currency : currencies) {
            rates.put(currency, getExchangeRate(currency));
        }
        
        return rates;
    }

    // ============================================
    // 🔄 تحديث الإعدادات
    // ============================================

    /**
     * حفظ إعدادات الذهب
     */
    public void saveGoldSettings(JSONObject goldSettings) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("gold_settings", goldSettings.toString());
        editor.apply();
    }

    /**
     * حفظ إعدادات العملات
     */
    public void saveCurrencySettings(JSONObject currencySettings) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("currency_settings", currencySettings.toString());
        editor.apply();
    }

    /**
     * تحديث الإعدادات من السيرفر
     */
    private void loadFromServer() {
        new Thread(() -> {
            try {
                com.example.uhf.api.MiraApiClient client = com.example.uhf.api.MiraApiClient.getInstance(appContext);
                com.example.uhf.api.MiraApiClient.ApiResponse response = client.getGoldCurrencySettings();
                
                if (response.isSuccess && response.data != null) {
                    JSONObject data = response.data.optJSONObject("data");
                    if (data == null) data = response.data;
                    
                    JSONObject goldData = data.optJSONObject("gold");
                    JSONObject currencyData = data.optJSONObject("currency");
                    
                    SharedPreferences.Editor editor = prefs.edit();
                    
                    if (goldData != null) {
                        // حفظ أسعار الذهب
                        String[] goldKeys = {
                            "gold_price_24k", "gold_price_22k", "gold_price_21k",
                            "gold_price_18k", "gold_price_14k",
                            "margin_24k", "margin_22k", "margin_21k",
                            "margin_18k", "margin_14k", "margin_12k",
                            "making_24k", "making_22k", "making_21k",
                            "making_18k", "making_14k", "making_12k"
                        };
                        
                        for (String key : goldKeys) {
                            if (goldData.has(key)) {
                                editor.putString(key, goldData.optString(key, "0"));
                            }
                        }
                    }
                    
                    if (currencyData != null) {
                        // حفظ أسعار العملات
                        String[] currencyKeys = {
                            "global_default_currency", "chart_currency",
                            "exchange_official_YER", "exchange_official_EGP",
                            "exchange_official_SAR", "exchange_official_AED",
                            "exchange_market_YER", "exchange_market_EGP",
                            "exchange_market_SAR", "exchange_market_AED",
                            "exchange_source_YER", "exchange_source_EGP",
                            "exchange_source_SAR", "exchange_source_AED"
                        };
                        
                        for (String key : currencyKeys) {
                            if (currencyData.has(key)) {
                                editor.putString(key, currencyData.optString(key, "0"));
                            }
                        }
                    }
                    
                    editor.apply();
                    
                    // إشعار التحديث
                    notifyPriceUpdated();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading prices from server: " + e.getMessage());
            }
        }).start();
    }

    /**
     * إشعار بأن الأسعار تم تحديثها
     */
    private void notifyPriceUpdated() {
        // يمكن استخدام EventBus أو BroadcastReceiver هنا
        // للتطبيق الحالي نستخدم Broadcast
        android.content.Intent intent = new android.content.Intent("MIRA_PRICES_UPDATED");
        appContext.sendBroadcast(intent);
    }

    /**
     * التحقق من وجود أسعار محدثة
     */
    public boolean hasValidPrices() {
        return getGoldPricePerGram(KARAT_24) > 0;
    }

    // ============================================
    // Helper Methods
    // ============================================

    private double getDoubleFromPrefs(String key, double defaultValue) {
        try {
            String value = prefs.getString(key, "");
            if (!value.isEmpty()) {
                return Double.parseDouble(value);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing " + key + ": " + e.getMessage());
        }
        return defaultValue;
    }
}
