package com.example.uhf.manager;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import com.example.uhf.api.MiraApiClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * MiraPriceManager v4.0 — السيرفر هو مصدر الحقيقة الوحيد (Single Source of Truth)
 *
 * ✅ لا قرارات أو هوامش أو أسعار صرف افتراضية محلية
 * ✅ مزامنة لحظية من السيرفر ومن أي استجابة API تحتوي على exchange_rate
 * ✅ إشعار فوري عبر MIRA_PRICES_UPDATED لتوحيد العملة بين السلة والدفع والإيصال
 */
public class MiraPriceManager {

    private static final String TAG = "MiraPriceManager";
    private static final String PREFS_NAME = "MIRA_GOLD_SETTINGS";
    private static final String ACTION_PRICES_UPDATED = "MIRA_PRICES_UPDATED";
    private static final long MIN_SYNC_INTERVAL_MS = 10_000L;

    private static volatile MiraPriceManager instance;
    private static Context appContext;

    private static volatile boolean isLoading = false;
    private static volatile long lastSyncTimeMs = 0L;

    private final SharedPreferences prefs;

    private final DecimalFormat decimalFormat;
    private final DecimalFormat largeFormat;

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

        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        decimalFormat = new DecimalFormat("#,##0.00", symbols);
        largeFormat = new DecimalFormat("#,##0", symbols);

        loadFromServer(true);
    }

    public static synchronized MiraPriceManager getInstance(Context context) {
        if (instance == null) {
            instance = new MiraPriceManager(context);
        } else {
            instance.ensureFreshRates();
        }
        return instance;
    }

    public void refreshNow() {
        loadFromServer(true);
    }

    public void ensureFreshRates() {
        long now = System.currentTimeMillis();
        if (!isLoading && (now - lastSyncTimeMs >= MIN_SYNC_INTERVAL_MS)) {
            loadFromServer(false);
        }
    }

    /**
     * تحديث فوري لسعر الصرف المعتمد من استجابة السيرفر (findItem / prepareCheckout)
     * لتوحيد سعر الصرف بين السلة ونافذة الدفع والإيصال فوراً.
     */
    public void setAuthoritativeExchangeRate(String currency, double rate) {
        if (currency == null || currency.trim().isEmpty()) return;
        if (!Double.isFinite(rate) || rate <= 1.0) return;

        String code = currency.trim().toUpperCase(Locale.US);
        if (CURRENCY_USD.equals(code)) return;

        double current = getExchangeRate(code);
        if (Math.abs(current - rate) < 0.0001) {
            return;
        }

        SharedPreferences.Editor editor = prefs.edit();
        String rateStr = String.valueOf(rate);
        editor.putString("exchange_official_" + code, rateStr);
        editor.putString("exchange_market_" + code, rateStr);
        editor.putString("authoritative_rate_" + code, rateStr);

        try {
            String currencySettingsJson = prefs.getString("currency_settings", "");
            JSONObject obj = currencySettingsJson.isEmpty()
                    ? new JSONObject()
                    : new JSONObject(currencySettingsJson);
            obj.put("exchange_official_" + code, rate);
            obj.put("exchange_market_" + code, rate);
            editor.putString("currency_settings", obj.toString());
        } catch (Exception e) {
            Log.w(TAG, "setAuthoritativeExchangeRate json merge warning: " + e.getMessage());
        }

        editor.apply();
        notifyPriceUpdated();
        Log.d(TAG, "✅ Authoritative exchange rate updated: 1 USD = " + rate + " " + code);
    }

    public void setExchangeRate(String currency, double rate) {
        setAuthoritativeExchangeRate(currency, rate);
    }

    private void loadFromServer(boolean force) {
        if (isLoading) {
            return;
        }

        long now = System.currentTimeMillis();
        if (!force && (now - lastSyncTimeMs < MIN_SYNC_INTERVAL_MS)) {
            return;
        }

        isLoading = true;
        lastSyncTimeMs = now;

        Thread loaderThread = new Thread(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(appContext);
                MiraApiClient.ApiResponse response = client.getGoldCurrencySettings();

                if (response != null && response.isSuccess && response.data != null) {
                    JSONObject root = response.data;
                    JSONObject data = root.optJSONObject("data");
                    if (data == null) {
                        data = root;
                    }

                    JSONObject goldData = data.optJSONObject("gold");
                    JSONObject currencyData = data.optJSONObject("currency");

                    SharedPreferences.Editor editor = prefs.edit();

                    if (goldData != null) {
                        editor.putString("gold_settings", goldData.toString());
                        String[] goldKeys = {
                                "gold_price_24k", "gold_price_22k", "gold_price_21k",
                                "gold_price_18k", "gold_price_14k", "gold_price_12k",
                                "margin_24k", "margin_22k", "margin_21k",
                                "margin_18k", "margin_14k", "margin_12k",
                                "making_24k", "making_22k", "making_21k",
                                "making_18k", "making_14k", "making_12k"
                        };
                        for (String key : goldKeys) {
                            if (goldData.has(key) && !goldData.isNull(key)) {
                                editor.putString(key, goldData.optString(key, "0"));
                            }
                        }
                    }

                    if (currencyData != null) {
                        editor.putString("currency_settings", currencyData.toString());
                        String[] currencyKeys = {
                                "global_default_currency", "chart_currency",
                                "exchange_official_YER", "exchange_official_EGP",
                                "exchange_official_SAR", "exchange_official_AED",
                                "exchange_market_YER", "exchange_market_EGP",
                                "exchange_market_SAR", "exchange_market_AED",
                                "use_market_rate"
                        };
                        for (String key : currencyKeys) {
                            if (currencyData.has(key) && !currencyData.isNull(key)) {
                                editor.putString(key, currencyData.optString(key, ""));
                            }
                        }

                        if (currencyData.has("use_market_rate")) {
                            boolean useMarket = currencyData.optBoolean("use_market_rate", false);
                            editor.putBoolean("use_market_rate", useMarket);
                        }
                    }

                    double topExchangeRate = data.optDouble("exchange_rate",
                            root.optDouble("exchange_rate", 0.0));
                    if (topExchangeRate > 1.0 && Double.isFinite(topExchangeRate)) {
                        editor.putString("authoritative_rate_YER", String.valueOf(topExchangeRate));
                        editor.putString("exchange_official_YER", String.valueOf(topExchangeRate));
                    }

                    JSONArray ratesArr = data.optJSONArray("exchange_rates");
                    if (ratesArr == null) {
                        ratesArr = root.optJSONArray("exchange_rates");
                    }
                    if (ratesArr != null) {
                        for (int i = 0; i < ratesArr.length(); i++) {
                            JSONObject rObj = ratesArr.optJSONObject(i);
                            if (rObj == null) continue;
                            String from = rObj.optString("from_currency", "").trim().toUpperCase(Locale.US);
                            String to = rObj.optString("to_currency", "").trim().toUpperCase(Locale.US);
                            double rate = rObj.optDouble("rate", 0);
                            if (CURRENCY_USD.equals(from) && !to.isEmpty() && rate > 0) {
                                editor.putString("exchange_official_" + to, String.valueOf(rate));
                                editor.putString("exchange_market_" + to, String.valueOf(rate));
                                editor.putString("authoritative_rate_" + to, String.valueOf(rate));
                            }
                        }
                    }

                    String version = data.optString("pricing_version", root.optString("pricing_version", ""));
                    if (!version.isEmpty()) {
                        editor.putString("pricing_version", version);
                    }

                    editor.apply();
                    notifyPriceUpdated();
                    Log.d(TAG, "✅ loadFromServer: synced from server (source of truth)");
                }
            } catch (Exception e) {
                Log.e(TAG, "❌ loadFromServer error: " + e.getMessage(), e);
            } finally {
                isLoading = false;
            }
        }, "MiraPriceLoader");

        loaderThread.setDaemon(true);
        loaderThread.start();
    }

    private void notifyPriceUpdated() {
        if (appContext == null) return;
        try {
            Intent intent = new Intent(ACTION_PRICES_UPDATED);
            intent.setPackage(appContext.getPackageName());
            appContext.sendBroadcast(intent);
        } catch (Exception e) {
            Log.e(TAG, "notifyPriceUpdated error: " + e.getMessage());
        }
    }

    // ============================================
    // 💰 دوال أسعار الذهب (مباشرة من بيانات السيرفر)
    // ============================================

    public double getGoldPricePerGram(int karat) {
        ensureFreshRates();
        String key = "gold_price_" + karat + "k";
        return getDoubleFromPrefs(key, 0.0);
    }

    public double getMargin(int karat) {
        ensureFreshRates();
        String key = "margin_" + karat + "k";
        return getDoubleFromPrefs(key, 0.0);
    }

    public double getMakingCharge(int karat) {
        ensureFreshRates();
        String key = "making_" + karat + "k";
        return getDoubleFromPrefs(key, 0.0);
    }

    public double getFinalPricePerGram(int karat) {
        double basePrice = getGoldPricePerGram(karat);
        if (basePrice <= 0) return 0.0;

        double margin = getMargin(karat);
        double making = getMakingCharge(karat);

        double subtotal = basePrice + making;
        double profitAmount = subtotal * (margin / 100.0);
        return subtotal + profitAmount;
    }

    public PricingDetails calculatePricingDetails(int karat, double weightGrams) {
        double basePrice = getGoldPricePerGram(karat);
        double makingCharge = getMakingCharge(karat);
        double margin = getMargin(karat);

        if (basePrice <= 0 || weightGrams <= 0) {
            return new PricingDetails(0, 0, 0, 0, basePrice);
        }

        double baseGoldCost = weightGrams * basePrice;
        double totalMakingCost = weightGrams * makingCharge;
        double subtotal = baseGoldCost + totalMakingCost;
        double profitAmount = subtotal * (margin / 100.0);
        double finalPrice = subtotal + profitAmount;

        return new PricingDetails(
                baseGoldCost,
                totalMakingCost,
                profitAmount,
                finalPrice,
                basePrice
        );
    }

    /**
     * حساب احتياطي لمرة واحدة فقط في حالة Offline التام وعدم وجود retail_price_usd
     * بدون تكرار جمع المصنعية مرتين.
     */
    public double calculateFallbackPriceUsd(
            int karat,
            double weightGrams,
            double itemMakingPerGram,
            double itemTotalMaking,
            double stoneValueUsd
    ) {
        double basePrice = getGoldPricePerGram(karat);
        if (basePrice <= 0 || weightGrams <= 0) {
            return 0.0;
        }

        double baseGoldCost = weightGrams * basePrice;
        double totalMakingCost;
        if (itemMakingPerGram > 0) {
            totalMakingCost = weightGrams * itemMakingPerGram;
        } else if (itemTotalMaking > 0) {
            totalMakingCost = itemTotalMaking;
        } else {
            totalMakingCost = weightGrams * getMakingCharge(karat);
        }

        double margin = getMargin(karat);
        double subtotal = baseGoldCost + totalMakingCost;
        double profitAmount = subtotal * (margin / 100.0);
        return subtotal + profitAmount + Math.max(0.0, stoneValueUsd);
    }

    public static class PricingDetails {
        public final double baseGoldCost;
        public final double totalMakingCost;
        public final double profitAmount;
        public final double finalPrice;
        public final double gramPrice;

        public PricingDetails(double baseGoldCost, double totalMakingCost,
                              double profitAmount, double finalPrice, double gramPrice) {
            this.baseGoldCost = baseGoldCost;
            this.totalMakingCost = totalMakingCost;
            this.profitAmount = profitAmount;
            this.finalPrice = finalPrice;
            this.gramPrice = gramPrice;
        }
    }

    public double calculateGoldPrice(int karat, double weightGrams) {
        return calculatePricingDetails(karat, weightGrams).finalPrice;
    }

    public double getProfitMargin(int karat) {
        return getMargin(karat);
    }

    public double getProfitAmount(int karat, double weightGrams) {
        return calculatePricingDetails(karat, weightGrams).profitAmount;
    }

    public double getBaseGoldCost(int karat, double weightGrams) {
        return calculatePricingDetails(karat, weightGrams).baseGoldCost;
    }

    public double getTotalMakingCost(int karat, double weightGrams) {
        return calculatePricingDetails(karat, weightGrams).totalMakingCost;
    }

    public double calculateGoldPriceInCurrency(int karat, double weightGrams, String currency) {
        double priceUSD = calculateGoldPrice(karat, weightGrams);
        if (priceUSD <= 0) return 0.0;
        return convertFromUSD(priceUSD, currency);
    }

    // ============================================
    // 💱 دوال العملات (من إعدادات السيرفر فقط)
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
        return CURRENCY_USD;
    }

    public double getExchangeRate(String currency) {
        if (currency == null || currency.trim().isEmpty() || CURRENCY_USD.equalsIgnoreCase(currency.trim())) {
            return 1.0;
        }

        ensureFreshRates();
        String code = currency.trim().toUpperCase(Locale.US);

        try {
            boolean useMarketRate = prefs.getBoolean("use_market_rate", false);
            String source = useMarketRate ? "market" : "official";
            String key = "exchange_" + source + "_" + code;

            String directValue = prefs.getString(key, "");
            if (!directValue.isEmpty()) {
                double rate = Double.parseDouble(directValue);
                if (rate > 0) return rate;
            }

            String altKey = "exchange_" + (useMarketRate ? "official" : "market") + "_" + code;
            String altValue = prefs.getString(altKey, "");
            if (!altValue.isEmpty()) {
                double rate = Double.parseDouble(altValue);
                if (rate > 0) return rate;
            }

            String authValue = prefs.getString("authoritative_rate_" + code, "");
            if (!authValue.isEmpty()) {
                double rate = Double.parseDouble(authValue);
                if (rate > 0) return rate;
            }

            String currencySettingsJson = prefs.getString("currency_settings", "");
            if (!currencySettingsJson.isEmpty()) {
                JSONObject currencySettings = new JSONObject(currencySettingsJson);
                double rate = currencySettings.optDouble(key, 0.0);
                if (rate > 0) return rate;
                double altRate = currencySettings.optDouble(altKey, 0.0);
                if (altRate > 0) return altRate;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading exchange rate for " + code + ": " + e.getMessage());
        }

        return 0.0;
    }

    public double convertFromUSD(double amountUSD, String toCurrency) {
        if (amountUSD <= 0) return 0.0;
        if (CURRENCY_USD.equalsIgnoreCase(String.valueOf(toCurrency).trim())) return amountUSD;
        double rate = getExchangeRate(toCurrency);
        return rate > 0 ? (amountUSD * rate) : 0.0;
    }

    public double convertToUSD(double amount, String fromCurrency) {
        if (amount <= 0) return 0.0;
        if (CURRENCY_USD.equalsIgnoreCase(String.valueOf(fromCurrency).trim())) return amount;
        double rate = getExchangeRate(fromCurrency);
        return rate > 0 ? (amount / rate) : 0.0;
    }

    public double convertCurrency(double amount, String fromCurrency, String toCurrency) {
        double amountUSD = convertToUSD(amount, fromCurrency);
        return convertFromUSD(amountUSD, toCurrency);
    }

    public String formatPrice(double priceUSD, String currency) {
        if (priceUSD < 0) priceUSD = 0.0;
        String safeCurrency = (currency == null || currency.trim().isEmpty())
                ? getDefaultCurrency()
                : currency.trim().toUpperCase(Locale.US);

        double convertedPrice = convertFromUSD(priceUSD, safeCurrency);
        String symbol = getCurrencySymbol(safeCurrency);

        if (CURRENCY_YER.equals(safeCurrency) || CURRENCY_EGP.equals(safeCurrency)) {
            return largeFormat.format(convertedPrice) + " " + symbol;
        }
        return symbol + " " + decimalFormat.format(convertedPrice);
    }

    public String formatPriceDefault(double priceUSD) {
        return formatPrice(priceUSD, getDefaultCurrency());
    }

    public String getCurrencySymbol(String currency) {
        if (currency == null) return "";
        switch (currency.trim().toUpperCase(Locale.US)) {
            case CURRENCY_USD: return "$";
            case CURRENCY_YER: return "YER";
            case CURRENCY_EGP: return "EGP";
            case CURRENCY_SAR: return "SAR";
            case CURRENCY_AED: return "AED";
            default: return currency.trim().toUpperCase(Locale.US);
        }
    }

    public String getCurrencyNameArabic(String currency) {
        if (currency == null) return "";
        switch (currency.trim().toUpperCase(Locale.US)) {
            case CURRENCY_USD: return "دولار أمريكي";
            case CURRENCY_YER: return "ريال يمني";
            case CURRENCY_EGP: return "جنيه مصري";
            case CURRENCY_SAR: return "ريال سعودي";
            case CURRENCY_AED: return "درهم إماراتي";
            default: return currency;
        }
    }

    public String getGoldItemPriceSummary(int karat, double weightGrams) {
        double priceUSD = calculateGoldPrice(karat, weightGrams);
        String defaultCurrency = getDefaultCurrency();
        double priceInCurrency = convertFromUSD(priceUSD, defaultCurrency);
        return String.format(Locale.US, "%s %s ($%s)",
                decimalFormat.format(priceInCurrency),
                getCurrencySymbol(defaultCurrency),
                decimalFormat.format(Math.max(0.0, priceUSD)));
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

    public void saveGoldSettings(JSONObject goldSettings) {
        if (goldSettings == null) return;

        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("gold_settings", goldSettings.toString());

        String[] goldKeys = {
                "gold_price_24k", "gold_price_22k", "gold_price_21k",
                "gold_price_18k", "gold_price_14k", "gold_price_12k",
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
        notifyPriceUpdated();
    }

    public void saveCurrencySettings(JSONObject currencySettings) {
        if (currencySettings == null) return;

        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("currency_settings", currencySettings.toString());

        if (currencySettings.has("use_market_rate")) {
            boolean useMarketRate = currencySettings.optBoolean("use_market_rate", false);
            editor.putBoolean("use_market_rate", useMarketRate);
        }

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
        notifyPriceUpdated();
    }

    private double getDoubleFromPrefs(String key, double defaultValue) {
        try {
            String goldSettingsJson = prefs.getString("gold_settings", "");
            if (!goldSettingsJson.isEmpty()) {
                JSONObject goldSettings = new JSONObject(goldSettingsJson);
                if (goldSettings.has(key) && !goldSettings.isNull(key)) {
                    double val = goldSettings.optDouble(key, Double.NaN);
                    if (!Double.isNaN(val)) return val;
                }
            }

            String currencySettingsJson = prefs.getString("currency_settings", "");
            if (!currencySettingsJson.isEmpty()) {
                JSONObject currencySettings = new JSONObject(currencySettingsJson);
                if (currencySettings.has(key) && !currencySettings.isNull(key)) {
                    double val = currencySettings.optDouble(key, Double.NaN);
                    if (!Double.isNaN(val)) return val;
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

    public boolean hasValidPrices() {
        return getGoldPricePerGram(KARAT_24) > 0;
    }

    public static void resetLoadState() {
        isLoading = false;
        lastSyncTimeMs = 0L;
    }
}
