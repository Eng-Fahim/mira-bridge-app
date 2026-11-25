package com.example.uhf.manager;

import android.util.Log;

import org.json.JSONObject;

/**
 * PosPriceResolver — محلّل أسعار القطع في شاشة البيع
 *
 * ✅ يعطي الأولوية للتسعير اللحظي (سعر الذهب الحي + المصنعية + الأحجار)
 * ✅ يستخدم الوزن الصافي (net_weight_grams) حتى لا تُحسب الأحجار بسعر الذهب
 * ✅ يتراجع تلقائياً للسعر المخزّن إذا كانت القطعة ذات سعر ثابت أو تعذر جلب سعر الذهب
 */
public final class PosPriceResolver {

    private static final String TAG = "PosPriceResolver";

    // الحد الأقصى المعقول لأي سعر بالدولار (لمنع البيانات الفاسدة)
    private static final double MAX_REASONABLE_PRICE_USD = 10_000_000.0;

    private final MiraPriceManager priceManager;

    public PosPriceResolver(MiraPriceManager priceManager) {
        this.priceManager = priceManager;
    }

    public static final class PriceResult {
        public final double priceUsd;       // السعر النهائي بالدولار
        public final PriceSource source;    // مصدر السعر
        public final boolean calculated;    // هل تم حسابه لحظياً من سعر الذهب؟

        public PriceResult(double priceUsd, PriceSource source, boolean calculated) {
            this.priceUsd = priceUsd;
            this.source = source;
            this.calculated = calculated;
        }

        public boolean isValid() {
            return priceUsd > 0;
        }
    }

    public enum PriceSource {
        LIVE_GOLD_CALCULATED,   // محسوب لحظياً (ذهب حي + مصنعية + أحجار)
        RETAIL_PRICE_USD,       // retail_price_usd (سعر ثابت مخزن)
        RETAIL_PRICE_LOCAL,     // retail_price_local ÷ exchange_rate
        RETAIL_PRICE,           // retail_price
        GENERIC_PRICE,          // price
        INVALID                 // لا يوجد سعر صالح
    }

    /**
     * يحلّل السعر من بيانات القطعة مع إعطاء الأولوية للسعر اللحظي للذهب والصرف
     */
    public PriceResult resolve(JSONObject item, int karat, double weightGrams) {
        if (item == null) {
            return new PriceResult(0, PriceSource.INVALID, false);
        }

        boolean isFixedPrice = item.optBoolean("is_fixed_price", false)
                || item.optInt("is_fixed_price", 0) == 1;

        // استخراج الوزن الصافي (إن وُجد) وإلا الوزن الإجمالي
        double netWeight = item.optDouble("net_weight_grams",
                item.optDouble("net_weight", weightGrams));
        if (netWeight <= 0) {
            netWeight = weightGrams;
        }

        // استخراج المصنعية للجرام (أو إجمالي المصنعية) وقيمة الأحجار بالدولار
        double makingPerGram = item.optDouble("making_per_gram", 0.0);
        double totalMakingCost = item.optDouble("making_charge", 0.0);
        double stoneValueUsd = item.optDouble("stone_value", 0.0);

        // 1. الأولوية القصوى: التسعير اللحظي الحي (الوزن الصافي × سعر الجرام اليوم + المصنعية + الأحجار)
        if (!isFixedPrice && netWeight > 0 && priceManager != null) {
            double liveGoldValueUsd = priceManager.calculateGoldPrice(karat, netWeight);
            if (liveGoldValueUsd > 0) {
                double makingUsd = (makingPerGram > 0)
                        ? (netWeight * makingPerGram)
                        : totalMakingCost;

                double liveTotalUsd = liveGoldValueUsd + makingUsd + Math.max(0.0, stoneValueUsd);

                if (isValidUsd(liveTotalUsd)) {
                    Log.d(TAG, "✅ Live price resolved → gold=" + liveGoldValueUsd +
                            ", making=" + makingUsd + ", stones=" + stoneValueUsd +
                            ", totalUsd=" + liveTotalUsd);
                    return new PriceResult(liveTotalUsd, PriceSource.LIVE_GOLD_CALCULATED, true);
                }
            }
        }

        // 2. الاحتياطي: الأسعار المخزنة (للقطع ذات السعر الثابت أو عند عدم توفر سعر الذهب الحي)
        double priceUsd     = item.optDouble("retail_price_usd", 0.0);
        double priceLocal   = item.optDouble("retail_price_local", 0.0);
        double priceMain    = item.optDouble("retail_price", 0.0);
        double priceGeneric = item.optDouble("price", 0.0);

        if (isValidUsd(priceUsd)) {
            return new PriceResult(priceUsd, PriceSource.RETAIL_PRICE_USD, false);
        }

        if (priceLocal > 0) {
            double rate = getYerExchangeRate();
            if (rate > 0) {
                double convertedUsd = priceLocal / rate;
                if (isValidUsd(convertedUsd)) {
                    return new PriceResult(convertedUsd, PriceSource.RETAIL_PRICE_LOCAL, false);
                }
            }
        }

        if (isValidUsd(priceMain)) {
            return new PriceResult(priceMain, PriceSource.RETAIL_PRICE, false);
        }

        if (isValidUsd(priceGeneric)) {
            return new PriceResult(priceGeneric, PriceSource.GENERIC_PRICE, false);
        }

        Log.e(TAG, "🚨 All price resolution paths failed!");
        return new PriceResult(0, PriceSource.INVALID, false);
    }

    private boolean isValidUsd(double price) {
        return price > 0 && price < MAX_REASONABLE_PRICE_USD;
    }

    private double getYerExchangeRate() {
        if (priceManager == null) return 530.0;
        double rate = priceManager.getExchangeRate("YER");
        return rate > 0 ? rate : 530.0;
    }
}
