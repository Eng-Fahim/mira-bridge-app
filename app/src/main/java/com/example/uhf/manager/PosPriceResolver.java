package com.example.uhf.manager;

import android.util.Log;

import org.json.JSONObject;

/**
 * PosPriceResolver — محلّل أسعار القطع في شاشة البيع
 *
 * ✅ يحل مشكلة المنطق المعقّد في parseItemResponse القديم
 * ✅ يعيد PriceResult غنيّاً بالمعلومات (PriceSource + تحذيرات)
 * ✅ يستخدم MiraPriceManager للتحويل عند الحاجة
 */
public final class PosPriceResolver {

    private static final String TAG = "PosPriceResolver";

    // الحد الأقصى المعقول لأي سعر (لمنع الأخطاء من البيانات الفاسدة)
    private static final double MAX_REASONABLE_PRICE = 100_000_000;

    private final MiraPriceManager priceManager;

    public PosPriceResolver(MiraPriceManager priceManager) {
        this.priceManager = priceManager;
    }

    // ============================================
    // 🎯 نتيجة التحليل
    // ============================================

    public static final class PriceResult {
        public final double priceUsd;       // السعر النهائي بالدولار
        public final PriceSource source;    // من أين جاء السعر
        public final boolean calculated;    // هل تم حسابه ديناميكياً؟

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
        RETAIL_PRICE_USD,       // retail_price_usd
        RETAIL_PRICE_LOCAL,     // retail_price_local / exchange
        RETAIL_PRICE,           // retail_price (main)
        GENERIC_PRICE,          // price (generic)
        CALCULATED,             // محسوب من الذهب
        INVALID                 // لا سعر
    }

    // ============================================
    // 🎯 الواجهة الرئيسية
    // ============================================

    /**
     * يحلّل السعر من JSONObject القطعة
     *
     * @param item        بيانات القطعة
     * @param karat       العيار (21, 18, ...)
     * @param weightGrams الوزن بالجرام
     * @return PriceResult
     */
    public PriceResult resolve(JSONObject item, int karat, double weightGrams) {
        if (item == null) {
            return new PriceResult(0, PriceSource.INVALID, false);
        }

        // استخراج الحقول
        double priceLocal   = item.optDouble("retail_price_local", 0);
        double priceMain    = item.optDouble("retail_price", 0);
        double priceUsd     = item.optDouble("retail_price_usd", 0);
        double priceGeneric = item.optDouble("price", 0);

        Log.d(TAG, "💰 Price fields → " +
                "usd=" + priceUsd +
                ", local=" + priceLocal +
                ", main=" + priceMain +
                ", generic=" + priceGeneric);

        // 1. أولوية: retail_price_usd
        if (isValid(priceUsd)) {
            return new PriceResult(priceUsd, PriceSource.RETAIL_PRICE_USD, false);
        }

        // 2. retail_price_local ÷ exchange rate
        if (isValid(priceLocal)) {
            double rate = getYerExchangeRate();
            if (rate > 0) {
                double converted = priceLocal / rate;
                return new PriceResult(converted, PriceSource.RETAIL_PRICE_LOCAL, false);
            }
        }

        // 3. retail_price (main)
        if (isValid(priceMain)) {
            return new PriceResult(priceMain, PriceSource.RETAIL_PRICE, false);
        }

        // 4. price (generic)
        if (isValid(priceGeneric)) {
            return new PriceResult(priceGeneric, PriceSource.GENERIC_PRICE, false);
        }

        // 5. حساب ديناميكي من الذهب
        if (weightGrams > 0 && priceManager != null) {
            double calculated = priceManager.calculateGoldPrice(karat, weightGrams);
            if (calculated > 0) {
                Log.w(TAG, "⚠️ Calculated dynamically: " + calculated);
                return new PriceResult(calculated, PriceSource.CALCULATED, true);
            }
        }

        // 6. لا شيء
        Log.e(TAG, "🚨 All price fields invalid!");
        return new PriceResult(0, PriceSource.INVALID, false);
    }

    // ============================================
    // 🛠️ دوال مساعدة
    // ============================================

    private boolean isValid(double price) {
        return price > 0 && price < MAX_REASONABLE_PRICE;
    }

    private double getYerExchangeRate() {
        if (priceManager == null) return 530.0;
        double rate = priceManager.getExchangeRate("YER");
        return rate > 0 ? rate : 530.0;
    }
}
