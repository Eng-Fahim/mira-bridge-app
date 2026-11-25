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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MiraPriceManager v3.1 — قراءة وإدارة بيانات الأسعار والعملات القادمة من السيرفر
 */
public class MiraPriceManager {

    private static final String TAG = "MiraPriceManager";
    private static final String PREFS_NAME = "MIRA_GOLD_SETTINGS";
    public static final String ACTION_PRICES_UPDATED = "MIRA_PRICES_UPDATED";
    private static final long MIN_SYNC_INTERVAL_MS = 10_000L;

    private static volatile MiraPriceManager instance;

    private final Context appContext;
    private final SharedPreferences prefs;
    private final DecimalFormat decimalFormat;
    private final DecimalFormat largeFormat;
    private final ExecutorService syncExecutor;

    private final AtomicBoolean isLoading = new AtomicBoolean(false);
    private volatile long lastSyncTimeMs = 0L;

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
        this.appContext = context.getApplicationContext();
        this.prefs = this.appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        this.decimalFormat = new DecimalFormat("#,##0.00", symbols);
        this.largeFormat = new DecimalFormat("#,##0", symbols);

        this.syncExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "MiraPriceSync");
            t.setDaemon(true);
            return t;
        });

        loadFromServer(true);
    }

    public static MiraPriceManager getInstance(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        if (instance == null) {
            synchronized (MiraPriceManager.class) {
                if (instance == null) {
                    instance = new MiraPriceManager(context);
                }
            }
        }
        return instance;
    }

    /**
     * مزامنة فورية من السيرفر (تُستدعى عند فتح الشاشات أو بعد تعديل الإعدادات)
     */
    public void refreshNow() {
        loadFromServer(true);
    }

    /**
     * مزامنة دورية مضبوطة بفاصل زمني أدنى
     */
    public void ensureFreshRates() {
        long now = System.currentTimeMillis();
        if (!isLoading.get() && (now - lastSyncTimeMs >= MIN_SYNC_INTERVAL_MS)) {
            loadFromServer(false);
        }
    }

    private void loadFromServer(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && (now - lastSyncTimeMs < MIN_SYNC_INTERVAL_MS)) {
            return;
        }
        if (!isLoading.compareAndSet(false, true)) {
            return;
        }
        lastSyncTimeMs = now;

        syncExecutor.execute(() -> {
            long startTime = System.currentTimeMillis();
            try {
                MiraApiClient client = MiraApiClient.getInstance(appContext);
                MiraApiClient.ApiResponse response = client.getGoldCurrencySettings();

                long elapsed = System.currentTimeMillis() - startTime;
                Log.d(TAG, "loadFromServer took " + elapsed + "ms, success="
                        + (response != null && response.isSuccess));

                if (response != null && response.isSuccess && response.data != null) {
                    JSONObject root = response.data;
                    JSONObject data = root.optJSONObject("data");
                    if (data == null) {
                        data = root;
                    }

                    JSONObject goldData = data.optJSONObject("gold");
                    JSONObject currencyData = data.optJSONObject("currency");

                    String oldVersion = prefs.getString("pricing_version", "");
                    String newVersion = data.optString("pricing_version",
                            root.optString("pricing_version", oldVersion));

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
                            editor.putBoolean("use_market_rate", currencyData.optBoolean("use_market_rate", false));
                        }
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
                            }
                        }
                    }

                    if (newVersion != null && !newVersion.isEmpty()) {
                        editor.putString("pricing_version", newVersion);
                    }

                    editor.apply();

                    if (force || !newVersion.equals(oldVersion) || oldVersion.isEmpty()) {
                        notifyPriceUpdated();
                    }
                } else {
                    Log.w(TAG, "loadFromServer: no valid response");
                }
            } catch (Exception e) {
                Log.e(TAG, "loadFromServer error: " + e.getMessage(), e);
            } finally {
                isLoading.set(false);
            }
        });
    }

    private void notifyPriceUpdated() {
        try {
            Intent intent = new Intent(ACTION_PRICES_UPDATED);
            intent.setPackage(appContext.getPackageName());
            appContext.sendBroadcast(intent);
        } catch (Exception e) {
            Log.e(TAG, "notifyPriceUpdated error: " + e.getMessage());
        }
    }

    // ============================================
    // قراءة القيم المخزّنة القادمة من السيرفر
    // ============================================

    public String getPricingVersion() {
        return prefs.getString("pricing_version", "");
    }

    public double getGoldPricePerGram(int karat) {
        return getDoubleFromPrefs("gold_price_" + karat + "k", 0.0);
    }

    public double getMargin(int karat) {
        return getDoubleFromPrefs("margin_" + karat + "k", 0.0);
    }

    public double getMakingCharge(int karat) {
        return getDoubleFromPrefs("making_" + karat + "k", 0.0);
    }

    public String getDefaultCurrency() {
        try {
            String currencySettingsJson = prefs.getString("currency_settings", "");
            if (!currencySettingsJson.isEmpty()) {
                JSONObject currencySettings = new JSONObject(currencySettingsJson);
                String currency = currencySettings.optString("global_default_currency", "");
                if (!currency.isEmpty()) return currency.trim().toUpperCase(Locale.US);
            }
            String currency = prefs.getString("global_default_currency", "");
            if (!currency.isEmpty()) return currency.trim().toUpperCase(Locale.US);
        } catch (Exception e) {
            Log.e(TAG, "Error getting default currency: " + e.getMessage());
        }
        return CURRENCY_USD;
    }

    public double getExchangeRate(String currency) {
        if (currency == null || currency.trim().isEmpty() || CURRENCY_USD.equalsIgnoreCase(currency.trim())) {
            return 1.0;
        }

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

    public String formatAmount(double amount, String currency) {
        double safeAmount = Math.max(0.0, amount);
        String safeCurrency = (currency == null || currency.trim().isEmpty())
                ? getDefaultCurrency()
                : currency.trim().toUpperCase(Locale.US);

        String symbol = getCurrencySymbol(safeCurrency);
        if (CURRENCY_YER.equals(safeCurrency) || CURRENCY_EGP.equals(safeCurrency)) {
            return largeFormat.format(safeAmount) + " " + symbol;
        }
        return symbol + " " + decimalFormat.format(safeAmount);
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

    public Map<String, Double> getAllGoldPrices() {
        Map<String, Double> prices = new HashMap<>();
        int[] karats = {KARAT_24, KARAT_22, KARAT_21, KARAT_18, KARAT_14, KARAT_12};
        for (int karat : karats) {
            prices.put(karat + "k", getGoldPricePerGram(karat));
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
            if (goldSettings.has(key) && !goldSettings.isNull(key)) {
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
            editor.putBoolean("use_market_rate", currencySettings.optBoolean("use_market_rate", false));
        }

        String[] currencies = {CURRENCY_YER, CURRENCY_EGP, CURRENCY_SAR, CURRENCY_AED};
        for (String currency : currencies) {
            String officialKey = "exchange_official_" + currency;
            String marketKey = "exchange_market_" + currency;
            if (currencySettings.has(officialKey) && !currencySettings.isNull(officialKey)) {
                editor.putString(officialKey, currencySettings.optString(officialKey, "0"));
            }
            if (currencySettings.has(marketKey) && !currencySettings.isNull(marketKey)) {
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

    public void resetLoadState() {
        isLoading.set(false);
        lastSyncTimeMs = 0L;
    }
}
