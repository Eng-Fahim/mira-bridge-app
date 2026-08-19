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
        // ✅ إرجاع 0 إذا لم يتم التحميل بعد - لا قيم افتراضية
        return getDoubleFromPrefs(key, 0);
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
        if (basePrice <= 0) return 0;
        
        double margin = getMargin(karat);
        double making = getMakingCharge(karat);
        return basePrice + (basePrice * margin / 100) + making;
    }

    public double calculateGoldPrice(int karat, double weightGrams) {
        double finalPrice = getFinalPricePerGram(karat);
        if (finalPrice <= 0) return 0;
        return finalPrice * weightGrams;
    }

    public double calculateGoldPriceInCurrency(int karat, double weightGrams, String currency) {
        double priceUSD = calculateGoldPrice(karat, weightGrams);
        if (priceUSD <= 0) return 0;
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

    public double getExchangeRate(String currency) {
        if (currency.equals(CURRENCY_USD)) return 1.0;

        try {
            boolean useMarketRate = prefs.getBoolean("use_market_rate", false);
            String source = useMarketRate ? "market" : "official";
            String key = "exchange_" + source + "_" + currency;
            String directValue = prefs.getString(key, "");
            if (!directValue.isEmpty()) {
                double rate = Double.parseDouble(directValue);
                if (rate > 0) return rate;
            }

            String currencySettingsJson = prefs.getString("currency_settings", "");
            if (!currencySettingsJson.isEmpty()) {
                JSONObject currencySettings = new JSONObject(currencySettingsJson);
                
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
        if (priceUSD <= 0) return "—";
        
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
            case CURRENCY_YER: return "YER";
            case CURRENCY_EGP: return "EGP";
            case CURRENCY_SAR: return "SAR";
            case CURRENCY_AED: return "AED";
            default: return currency;
        }
    }

    public String getCurrencyNameArabic(String currency) {
        switch (currency) {
            case CURRENCY_USD: return "دولار أمريكي";
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
        if (priceUSD <= 0) return "—";
        
        String defaultCurrency = getDefaultCurrency();
        double priceInCurrency = convertFromUSD(priceUSD, defaultCurrency);
        return String.format("%s %s ($%s)",
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
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("gold_settings", goldSettings.toString());
        
        // ✅ حفظ القيم مباشرة
        String[] goldKeys = {
            "gold_price_24k", "gold_price_22k", "gold_price_21k",
            "gold_price_18k", "gold_price_14k",
            "margin_24k", "margin_22k", "margin_21k",
            "margin_18k", "margin_14k", "margin_12k",
            "making_24k", "making_22k", "making_21k",
            "making_18k", "making_14k", "making_12k"
        };
        
        for (String key : goldKeys) {
            if (goldSettings.has(key)) {
                editor.putString(key, goldSettings.optString(key, "0"));
            }
        }
        
        editor.apply();
    }

    public void saveCurrencySettings(JSONObject currencySettings) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("currency_settings", currencySettings.toString());
        
        boolean useMarketRate = currencySettings.optBoolean("use_market_rate", false);
        editor.putBoolean("use_market_rate", useMarketRate);
        
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
    // القيم الافتراضية - فقط للهوامش والمصنعية
    // ============================================

    // ❌ حذف getDefaultGoldPrice - لا نريد سعر افتراضي قديم
    
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
    // القراءة من التخزين
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
