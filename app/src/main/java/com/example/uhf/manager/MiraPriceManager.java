package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.example.uhf.api.MiraApiClient;

import org.json.JSONObject;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * MiraPriceManager v2.1 — الحصين ضد Thread-Leak + إصلاح formatPrice
 *
 * ✅ الإصلاحات:
 *  1. hasLoadedFromServer flag — يمنع تحميل مكرر
 *  2. loadFromServer مع Daemon Thread
 *  3. logging للأداء
 *  4. Null-safety في كل مكان
 *  5. ✅ [v2.1] formatPrice لا تُعيد "—" — تُعيد "0 YER" للقيم <= 0
 */
public class MiraPriceManager {

    private static final String TAG = "MiraPriceManager";
    private static final String PREFS_NAME = "MIRA_GOLD_SETTINGS";

    private static volatile MiraPriceManager instance;
    private static Context appContext;

    private static volatile boolean hasLoadedFromServer = false;
    private static volatile boolean isLoading = false;

    private final SharedPreferences prefs;

    // ✅ Formatters — تُهيَّأ مرة واحدة مع Locale.US
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

    // ==================== Singleton ====================

    private MiraPriceManager(Context context) {
        appContext = context.getApplicationContext();
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // ✅ تهيئة الـ Formatters مع Locale.US — إجبار الأرقام اللاتينية
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        decimalFormat = new DecimalFormat("#,##0.00", symbols);
        largeFormat = new DecimalFormat("#,##0", symbols);

        // ✅ حمّل من السيرفر مرة واحدة فقط
        if (!hasLoadedFromServer && !isLoading) {
            hasLoadedFromServer = true;
            loadFromServer();
        }
    }

    public static synchronized MiraPriceManager getInstance(Context context) {
        if (instance == null) {
            instance = new MiraPriceManager(context);
        }
        return instance;
    }

    /**
     * ✅ إعادة تحميل يدوية (من Settings مثلاً)
     */
    public void refreshNow() {
        if (isLoading) {
            Log.d(TAG, "refreshNow: already loading, skip");
            return;
        }
        loadFromServer();
    }

    /**
     * ✅ [إصلاح v2.0] آمن — لا يُكرَّر
     */
    private void loadFromServer() {
        if (isLoading) {
            Log.d(TAG, "loadFromServer: already in progress");
            return;
        }

        isLoading = true;

        Thread loaderThread = new Thread(() -> {
            long startTime = System.currentTimeMillis();

            try {
                MiraApiClient client = MiraApiClient.getInstance(appContext);
                MiraApiClient.ApiResponse response = client.getGoldCurrencySettings();

                long elapsed = System.currentTimeMillis() - startTime;
                Log.d(TAG, "📥 loadFromServer took " + elapsed + "ms, success="
                        + (response != null && response.isSuccess));

                if (response != null && response.isSuccess && response.data != null) {
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

                    Log.d(TAG, "✅ loadFromServer: prices updated");
                } else {
                    Log.w(TAG, "⚠️ loadFromServer: no valid response");
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
        try {
            android.content.Intent intent =
                    new android.content.Intent("MIRA_PRICES_UPDATED");
            appContext.sendBroadcast(intent);
        } catch (Exception e) {
            Log.e(TAG, "notifyPriceUpdated error: " + e.getMessage());
        }
    }

    // ============================================
    // 💰 دوال أسعار الذهب
    // ============================================

    public double getGoldPricePerGram(int karat) {
        String key = "gold_price_" + karat + "k";
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

        double subtotal = basePrice + making;
        double profitAmount = subtotal * (margin / 100.0);
        return subtotal + profitAmount;
    }

    public PricingDetails calculatePricingDetails(int karat, double weightGrams) {
        double basePrice = getGoldPricePerGram(karat);
        double makingCharge = getMakingCharge(karat);
        double margin = getMargin(karat);

        if (basePrice <= 0) {
            return new PricingDetails(0, 0, 0, 0, 0);
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
        PricingDetails details = calculatePricingDetails(karat, weightGrams);
        return details.finalPrice;
    }

    public double getProfitMargin(int karat) {
        return getMargin(karat);
    }

    public double getProfitAmount(int karat, double weightGrams) {
        PricingDetails details = calculatePricingDetails(karat, weightGrams);
        return details.profitAmount;
    }

    public double getBaseGoldCost(int karat, double weightGrams) {
        PricingDetails details = calculatePricingDetails(karat, weightGrams);
        return details.baseGoldCost;
    }

    public double getTotalMakingCost(int karat, double weightGrams) {
        PricingDetails details = calculatePricingDetails(karat, weightGrams);
        return details.totalMakingCost;
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
        if (CURRENCY_USD.equals(currency)) return 1.0;

        try {
            boolean useMarketRate = prefs.getBoolean("use_market_rate", false);
            String source = useMarketRate ? "market" : "official";
            String key = "exchange_" + source + "_" + currency;
            String directValue = prefs.getString(key, "");
            if (directValue != null && !directValue.isEmpty()) {
                try {
                    double rate = Double.parseDouble(directValue);
                    if (rate > 0) return rate;
                } catch (NumberFormatException ignored) {}
            }

            String currencySettingsJson = prefs.getString("currency_settings", "");
            if (currencySettingsJson != null && !currencySettingsJson.isEmpty()) {
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

    /**
     * ✅ [v2.1] formatPrice — لا تُعيد "—" — تُعيد 0 مع العملة للقيم <= 0
     * السبب: "—" على خلفية ذهبية يصبح غير مرئي، ويُظهر كأن المبلغ فارغ
     */
    public String formatPrice(double priceUSD, String currency) {
        // ✅ إذا القيمة سلبية → صفّرها. لا نُعيد "—" أبداً
        if (priceUSD < 0) priceUSD = 0;

        double convertedPrice = convertFromUSD(priceUSD, currency);
        String symbol = getCurrencySymbol(currency);

        if (CURRENCY_YER.equals(currency) || CURRENCY_EGP.equals(currency)) {
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

    public String getGoldItemPriceSummary(int karat, double weightGrams) {
        double priceUSD = calculateGoldPrice(karat, weightGrams);
        if (priceUSD <= 0) return "—";

        String defaultCurrency = getDefaultCurrency();
        double priceInCurrency = convertFromUSD(priceUSD, defaultCurrency);
        return String.format(Locale.US, "%s %s ($%s)",
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

    public void saveGoldSettings(JSONObject goldSettings) {
        if (goldSettings == null) return;

        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("gold_settings", goldSettings.toString());

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
        if (currencySettings == null) return;

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

    private double getDoubleFromPrefs(String key, double defaultValue) {
        try {
            String goldSettingsJson = prefs.getString("gold_settings", "");
            if (goldSettingsJson != null && !goldSettingsJson.isEmpty()) {
                JSONObject goldSettings = new JSONObject(goldSettingsJson);
                if (goldSettings.has(key)) {
                    return goldSettings.optDouble(key, defaultValue);
                }
            }

            String currencySettingsJson = prefs.getString("currency_settings", "");
            if (currencySettingsJson != null && !currencySettingsJson.isEmpty()) {
                JSONObject currencySettings = new JSONObject(currencySettingsJson);
                if (currencySettings.has(key)) {
                    return currencySettings.optDouble(key, defaultValue);
                }
            }

            String value = prefs.getString(key, "");
            if (value != null && !value.isEmpty()) {
                try {
                    return Double.parseDouble(value);
                } catch (NumberFormatException ignored) {}
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing " + key + ": " + e.getMessage());
        }
        return defaultValue;
    }

    public boolean hasValidPrices() {
        return getGoldPricePerGram(KARAT_24) > 0;
    }

    /**
     * ✅ [إصلاح v2.0] إعادة تعيين حالة التحميل (للاستخدام بعد logout)
     */
    public static void resetLoadState() {
        hasLoadedFromServer = false;
        isLoading = false;
    }
}
