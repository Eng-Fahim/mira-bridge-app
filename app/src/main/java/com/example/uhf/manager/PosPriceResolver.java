package com.example.uhf.manager;

import android.util.Log;

import org.json.JSONObject;

import java.util.Locale;

/**
 * PosPriceResolver — السيرفر هو مصدر الحقيقة الوحيد (Single Source of Truth)
 *
 * ✅ يعتمد السعر الرسمي القادم من السيرفر (retail_price_usd / price_bounds) أولاً دون إعادة حساب محلي متضارب
 * ✅ يزامن سعر الصرف القادم من السيرفر فوراً مع MiraPriceManager
 * ✅ لا يحتوي على أي سعر صرف ثابت (لا 530 ولا غيره)
 * ✅ في حالة Offline التام فقط، يحسب السعر بدقة دون مضاعفة المصنعية
 */
public final class PosPriceResolver {

    private static final String TAG = "PosPriceResolver";
    private static final double MAX_REASONABLE_PRICE_USD = 10_000_000.0;

    private final MiraPriceManager priceManager;

    public PosPriceResolver(MiraPriceManager priceManager) {
        this.priceManager = priceManager;
    }

    public static final class PriceResult {
        public final double priceUsd;
        public final PriceSource source;
        public final boolean calculated;

        public PriceResult(double priceUsd, PriceSource source, boolean calculated) {
            this.priceUsd = priceUsd;
            this.source = source;
            this.calculated = calculated;
        }

        public boolean isValid() {
            return isValidUsd(priceUsd);
        }
    }

    public enum PriceSource {
        LIVE_GOLD_CALCULATED,   // احتياطي Offline فقط عند غياب سعر السيرفر
        RETAIL_PRICE_USD,       // السعر الرسمي المعتمد من السيرفر بالدولار
        RETAIL_PRICE_LOCAL,     // السعر الرسمي المحلي ÷ سعر الصرف المعتمد من السيرفر
        RETAIL_PRICE,           // retail_price
        GENERIC_PRICE,          // price
        INVALID                 // لا يوجد سعر صالح
    }

    public PriceResult resolve(JSONObject item, int karat, double weightGrams) {
        if (item == null) {
            return new PriceResult(0.0, PriceSource.INVALID, false);
        }

        // 1. مزامنة سعر الصرف المعتمد من استجابة السيرفر فوراً إن وُجد
        String currencyCode = extractCurrencyCode(item);
        double serverRate = extractAuthoritativeRate(item);
        if (serverRate > 1.0 && priceManager != null) {
            priceManager.setAuthoritativeExchangeRate(currencyCode, serverRate);
        }

        // 2. الأولوية الأولى المطلقة: السعر الرسمي المحسوب في السيرفر (retail_price_usd)
        double priceUsd = item.optDouble("retail_price_usd", 0.0);
        if (isValidUsd(priceUsd)) {
            return new PriceResult(priceUsd, PriceSource.RETAIL_PRICE_USD, false);
        }

        JSONObject boundsObj = item.optJSONObject("price_bounds");
        if (boundsObj != null) {
            double recommendedUsd = boundsObj.optDouble("recommended_usd", 0.0);
            double maxUsd = boundsObj.optDouble("max_usd", 0.0);
            boolean withinMax = !(maxUsd > 0.0 && Double.isFinite(maxUsd)) || recommendedUsd <= maxUsd;
            if (isValidUsd(recommendedUsd) && withinMax) {
                return new PriceResult(recommendedUsd, PriceSource.RETAIL_PRICE_USD, false);
            }
        }

        // 3. الأولوية الثانية: السعر المحلي الرسمي من السيرفر مقسوماً على سعر الصرف المعتمد
        double priceLocal = item.optDouble("retail_price_local", 0.0);
        if (priceLocal > 0.0 && Double.isFinite(priceLocal)) {
            double rate = (serverRate > 1.0) ? serverRate : getExchangeRateForCurrency(currencyCode);
            if (rate > 1.0) {
                double convertedUsd = priceLocal / rate;
                if (isValidUsd(convertedUsd)) {
                    return new PriceResult(convertedUsd, PriceSource.RETAIL_PRICE_LOCAL, false);
                }
            }
        }

        double priceMain = item.optDouble("retail_price", 0.0);
        if (isValidUsd(priceMain)) {
            return new PriceResult(priceMain, PriceSource.RETAIL_PRICE, false);
        }

        double priceGeneric = item.optDouble("price", 0.0);
        if (isValidUsd(priceGeneric)) {
            return new PriceResult(priceGeneric, PriceSource.GENERIC_PRICE, false);
        }

        // 4. احتياطي Offline فقط: إذا لم يرسل السيرفر أي سعر للقطعة، نحسبه لمرة واحدة بدون تكرار المصنعية
        double netWeight = item.optDouble("net_weight_grams",
                item.optDouble("net_weight", weightGrams));
        if (netWeight <= 0.0) {
            netWeight = weightGrams;
        }

        if (netWeight > 0.0 && Double.isFinite(netWeight) && priceManager != null) {
            double makingPerGram = item.optDouble("making_per_gram", 0.0);
            double totalMakingCost = item.optDouble("making_charge", 0.0);
            double stoneValueUsd = Math.max(0.0, item.optDouble("stone_value",
                    item.optDouble("stone_price_usd", 0.0)));

            double fallbackUsd = priceManager.calculateFallbackPriceUsd(
                    karat,
                    netWeight,
                    makingPerGram,
                    totalMakingCost,
                    stoneValueUsd
            );

            if (isValidUsd(fallbackUsd)) {
                Log.d(TAG, "⚠️ Offline fallback price calculated: totalUsd=" + fallbackUsd);
                return new PriceResult(fallbackUsd, PriceSource.LIVE_GOLD_CALCULATED, true);
            }
        }

        Log.e(TAG, "🚨 All price resolution paths failed!");
        return new PriceResult(0.0, PriceSource.INVALID, false);
    }

    private String extractCurrencyCode(JSONObject item) {
        String code = item.optString("currency", "");
        if (code.trim().isEmpty()) {
            code = item.optString("local_currency", "");
        }
        if (code.trim().isEmpty()) {
            JSONObject bounds = item.optJSONObject("price_bounds");
            if (bounds != null) {
                code = bounds.optString("currency", "");
            }
        }
        String normalized = code.trim().toUpperCase(Locale.US);
        return normalized.isEmpty() ? MiraPriceManager.CURRENCY_YER : normalized;
    }

    private double extractAuthoritativeRate(JSONObject item) {
        double rate = item.optDouble("exchange_rate", 0.0);
        if (rate > 1.0 && Double.isFinite(rate)) {
            return rate;
        }
        JSONObject bounds = item.optJSONObject("price_bounds");
        if (bounds != null) {
            double bRate = bounds.optDouble("exchange_rate", 0.0);
            if (bRate > 1.0 && Double.isFinite(bRate)) {
                return bRate;
            }
        }
        return 0.0;
    }

    private static boolean isValidUsd(double price) {
        return Double.isFinite(price) && price > 0.0 && price < MAX_REASONABLE_PRICE_USD;
    }

    private double getExchangeRateForCurrency(String currencyCode) {
        if (priceManager == null) return 0.0;
        String target = (currencyCode == null || currencyCode.trim().isEmpty())
                ? MiraPriceManager.CURRENCY_YER
                : currencyCode.trim().toUpperCase(Locale.US);
        if (MiraPriceManager.CURRENCY_USD.equals(target)) {
            return 1.0;
        }
        double rate = priceManager.getExchangeRate(target);
        return (Double.isFinite(rate) && rate > 1.0) ? rate : 0.0;
    }
}
