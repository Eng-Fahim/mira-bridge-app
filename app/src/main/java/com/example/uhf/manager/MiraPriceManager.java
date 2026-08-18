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
 */
public class MiraPriceManager {

    private static final String TAG = "MiraPriceManager";
    private static final String PREFS_NAME = "MIRA_GOLD_SETTINGS";
    private static MiraPriceManager instance;
    private static Context appContext;

    private SharedPreferences prefs;
    private final DecimalFormat decimalFormat = new DecimalFormat("#,##0.00");

    public static final String CURRENCY_USD = "USD";
    public static final String CURRENCY_YER = "YER";
    public static final String CURRENCY_EGP = "EGP";
    public static final String CURRENCY_SAR = "SAR";
    public static final String CURRENCY_AED = "AED";

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

    public static synchronized MiraPriceManager getInstance(Context context) {
        if (instance == null) {
            instance = new MiraPriceManager(context);
        }
        return instance;
    }

    public void refreshNow() {
        loadFromServer();
    }

    // ============================================
    // 💰 دوال أسعار الذهب
    // ============================================

    public double getGoldPricePerGram(int karat) {
        String key = "gold_price_" + karat + "k";
        return getDoubleFromPrefs(key, getDefaultGoldPrice(karat));
    }

    public double getMargin(int karat) {
        String key = "margin_" + karat + "k";
        return getDoubleFromPrefs(key, getDefaultMargin(karat));
    }

    public double getMakingCharge(int karat) {
        String key = "making_" + karat + "k";
        return getDoubleFromPrefs(key, getDefaultMaking(karat));
    }

    public double getFinalPricePerGram(int karat) {
        double basePrice = getGoldPricePerGram(karat);
        double margin = getMargin(karat);
        double making = getMakingCharge(karat);
        return basePrice + (basePrice * margin / 100) + making;
    }

    public double calculateGoldPrice(int karat, double weightGrams) {
        return getFinalPricePerGram(karat) * weightGrams;
    }

    public double calculateGoldPriceInCurrency(int karat, double weightGrams, String currency) {
        double priceUSD = calculateGoldPrice(karat, weightGrams);
        return convertFromUSD(priceUSD, currency);
    }

    // ============================================
    // 💱 دوال العملات
    // ============================================

    public String getDefaultCurrency() {
        try {
            String currencySettingsJson = prefs.getString("currency_settings", "");
            if (!currencySettingsJson.isEmpty()) {
                JSONObject currencySettings = new JSONObject(currencySettingsJson);
                String currency = currencySettings.optString("global_default_currency", "");
                if (!currency.isEmpty()) return currency;
            }
            String currency = prefs.getString("global_default_currency", "");
            if (!currency.isEmpty()) return currency;
        } catch (Exception e) {
            Log.e(TAG, "Error getting default currency: " + e.getMessage());
        }
        return CURRENCY_YER;
    }

    // ✅ دالة واحدة فقط لسعر الصرف
    public double getExchangeRate(String currency) {
        if (currency.equals(CURRENCY_USD)) return 1.0;

        try {
            // ✅ 1. القراءة المباشرة من prefs
            boolean useMarketRate = prefs.getBoolean("use_market_rate", false);
            String source = useMarketRate ? "market" : "official";
            String key = "exchange_" + source + "_" + currency;
            String directValue = prefs.getString(key, "");
            if (!directValue.isEmpty()) {
                double rate = Double.parseDouble(directValue);
                if (rate > 0) return rate;
            }

            // ✅ 2. القراءة من currency_settings JSON
            String currencySettingsJson = prefs.getString("currency_settings", "");
            if (!currencySettingsJson.isEmpty()) {
                JSONObject currencySettings = new JSONObject(currencySettingsJson);
                
                // قراءة use_market_rate
                if (currencySettings.has("use_market_rate")) {
                    Object obj = currencySettings.get("use_market_rate");
                    if (obj instanceof Boolean) {
                        useMarketRate = (Boolean) obj;
                    } else {
                        String str = obj.toString();
                        useMarketRate = str.equals("true") || str.equals("1");
                    }
                }
                
                source = useMarketRate ? "market" : "official";
                key = "exchange_" + source + "_" + currency;
                double rate = currencySettings.optDouble(key, 0);
                if (rate > 0) return rate;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading exchange rate: " + e.getMessage());
        }

        // ✅ قيم افتراضية
        switch (currency) {
            case CURRENCY_YER: return 530;
            case CURRENCY_EGP: return 47.5;
            case CURRENCY_SAR: return 3.75;
            case CURRENCY_AED: return 3.67;
            default: return 1.0;
        }
    }

    public double convertFromUSD(double amountUSD, String toCurrency) {
        return amountUSD * getExchangeRate(toCurrency);
    }

    public double convertToUSD(double amount, String fromCurrency) {
        double rate = getExchangeRate(fromCurrency);
        return rate > 0 ? amount / rate : amount;
    }

    public double convertCurrency(double amount, String fromCurrency, String toCurrency) {
        double amountUSD = convertToUSD(amount, fromCurrency);
        return convertFromUSD(amountUSD, toCurrency);
    }

    public String formatPrice(double priceUSD, String currency) {
        double convertedPrice = convertFromUSD(priceUSD, currency);
        String symbol = getCurrencySymbol(currency);
        if (currency.equals(CURRENCY_YER) || currency.equals(CURRENCY_EGP)) {
            DecimalFormat largeFormat = new DecimalFormat("#,##0");
            return largeFormat.format(convertedPrice) + " " + symbol;
        }
        return symbol + " " + decimalFormat.format(convertedPrice);
    }

    public String formatPriceDefault(double priceUSD) {
        return formatPrice(priceUSD, getDefaultCurrency());
    }

    public String getCurrencySymbol(String currency) {
        switch (currency) {
            case CURRENCY_USD: return "$";
            case CURRENCY_YER: return "ريال";
            case CURRENCY_EGP: return "ج.م";
            case CURRENCY_SAR: return "ر.س";
            case CURRENCY_AED: return "د.إ";
            default: return currency;
        }
    }

    public String getCurrencyNameArabic(String currency) {
        switch (currency) {
            case CURRENCY_USD: return "دولار";
            case CURRENCY_YER: return "ريال يمني";
            case CURRENCY_EGP: return "جنيه مصري";
            case CURRENCY_SAR: return "ريال سعودي";
            case CURRENCY_AED: return "درهم إماراتي";
            default: return currency;
        }
    }

    // ============================================
    // 📊 دوال مساعدة
    // ============================================

    public String getGoldItemPriceSummary(int karat, double weightGrams) {
        double priceUSD = calculateGoldPrice(karat, weightGrams);
        String defaultCurrency = getDefaultCurrency();
        double priceInCurrency = convertFromUSD(priceUSD, defaultCurrency);
        return String.format("%s %s (%s $)",
            decimalFormat.format(priceInCurrency),
            getCurrencySymbol(defaultCurrency),
            decimalFormat.format(priceUSD));
    }

    public Map<String, Double> getAllGoldPrices() {
        Map<String, Double> prices = new HashMap<>();
        int[] karats = {KARAT_24, KARAT_22, KARAT_21, KARAT_18, KARAT_14, KARAT_12};
        for (int karat : karats) {
            prices.put(karat + "k_base", getGoldPricePerGram(karat));
            prices.put(karat + "k_final", getFinalPricePerGram(karat));
        }
        return prices;
    }

    public Map<String, Double> getAllExchangeRates() {
        Map<String, Double> rates = new HashMap<>();
        String[] currencies = {CURRENCY_YER, CURRENCY_EGP, CURRENCY_SAR, CURRENCY_AED};
        for (String currency : currencies) {
            rates.put(currency, getExchangeRate(currency));
        }
        return rates;
    }

    // ============================================
    // حفظ الإعدادات
    // ============================================

    public void saveGoldSettings(JSONObject goldSettings) {
        prefs.edit().putString("gold_settings", goldSettings.toString()).apply();
    }

    public void saveCurrencySettings(JSONObject currencySettings) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("currency_settings", currencySettings.toString());
        
        // ✅ حفظ use_market_rate كـ boolean
        boolean useMarketRate = currencySettings.optBoolean("use_market_rate", false);
        editor.putBoolean("use_market_rate", useMarketRate);
        
        // ✅ حفظ القيم مباشرة
        String[] currencies = {CURRENCY_YER, CURRENCY_EGP, CURRENCY_SAR, CURRENCY_AED};
        for (String currency : currencies) {
            String officialKey = "exchange_official_" + currency;
            String marketKey = "exchange_market_" + currency;
            
            if (currencySettings.has(officialKey)) {
                editor.putString(officialKey, currencySettings.optString(officialKey, "0"));
            }
            if (currencySettings.has(marketKey)) {
                editor.putString(marketKey, currencySettings.optString(marketKey, "0"));
            }
        }
        
        editor.apply();
    }

    // ============================================
    // القيم الافتراضية
    // ============================================

    private double getDefaultGoldPrice(int karat) {
        switch (karat) {
            case 24: return 75.00;
            case 22: return 68.75;
            case 21: return 65.62;
            case 18: return 56.25;
            case 14: return 43.75;
            case 12: return 37.50;
            default: return 65.62;
        }
    }

    private double getDefaultMargin(int karat) {
        switch (karat) {
            case 24: return 95.0;
            case 22: return 88.0;
            case 21: return 90.0;
            case 18: return 100.8;
            case 14: return 105.8;
            case 12: return 110.0;
            default: return 90.0;
        }
    }

    private double getDefaultMaking(int karat) {
        switch (karat) {
            case 24: return 5.0;
            case 22: return 8.0;
            case 21: return 10.0;
            case 18: return 12.0;
            case 14: return 15.0;
            case 12: return 18.0;
            default: return 10.0;
        }
    }

    // ============================================
    // القراءة من التخزين - دالة واحدة فقط
    // ============================================

    private double getDoubleFromPrefs(String key, double defaultValue) {
        try {
            String goldSettingsJson = prefs.getString("gold_settings", "");
            if (!goldSettingsJson.isEmpty()) {
                JSONObject goldSettings = new JSONObject(goldSettingsJson);
                if (goldSettings.has(key)) {
                    return goldSettings.optDouble(key, defaultValue);
                }
            }
            
            String currencySettingsJson = prefs.getString("currency_settings", "");
            if (!currencySettingsJson.isEmpty()) {
                JSONObject currencySettings = new JSONObject(currencySettingsJson);
                if (currencySettings.has(key)) {
                    return currencySettings.optDouble(key, defaultValue);
                }
            }
            
            String value = prefs.getString(key, "");
            if (!value.isEmpty()) {
                return Double.parseDouble(value);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing " + key + ": " + e.getMessage());
        }
        return defaultValue;
    }

    // ============================================
    // التحديث من السيرفر
    // ============================================

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
                        String[] currencyKeys = {
                            "global_default_currency", "chart_currency",
                            "exchange_official_YER", "exchange_official_EGP",
                            "exchange_official_SAR", "exchange_official_AED",
                            "exchange_market_YER", "exchange_market_EGP",
                            "exchange_market_SAR", "exchange_market_AED",
                            "use_market_rate"
                        };
                        for (String key : currencyKeys) {
                            if (currencyData.has(key)) {
                                editor.putString(key, currencyData.optString(key, ""));
                            }
                        }
                        
                        // ✅ حفظ use_market_rate كـ boolean
                        if (currencyData.has("use_market_rate")) {
                            boolean useMarket = currencyData.optBoolean("use_market_rate", false);
                            editor.putBoolean("use_market_rate", useMarket);
                        }
                    }
                    
                    editor.apply();
                    notifyPriceUpdated();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading prices from server: " + e.getMessage());
            }
        }).start();
    }

    private void notifyPriceUpdated() {
        android.content.Intent intent = new android.content.Intent("MIRA_PRICES_UPDATED");
        appContext.sendBroadcast(intent);
    }

    public boolean hasValidPrices() {
        return getGoldPricePerGram(KARAT_24) > 0;
    }
}
