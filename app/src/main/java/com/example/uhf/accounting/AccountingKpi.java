package com.example.uhf.accounting;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * MIRA Accounting — KPI Value Object
 *
 * يمثّل مؤشراً مالياً واحداً في لوحة التحكم.
 *
 * ── المبادئ ──
 *  1. كل KPI له حالة صريحة (State) — لا يوجد "رقم غير معروف يُعرض كأنه صحيح"
 *  2. الحالات المدعومة:
 *       LOADING       → لا قيمة، يظهر spinner
 *       SUCCESS       → قيمة موثوقة + تسمية الفترة/العملة/الفرع
 *       NOT_CONNECTED → العمود جاهز، لم يُربط بالـ API بعد (4.1)
 *       EMPTY         → الـ API استجاب لكن لا بيانات في الفترة
 *       ERROR         → الـ API فشل، مع رسالة واضحة وإمكانية إعادة المحاولة
 *  3. كل قيمة تحمل السياق الكامل: العملة، الفترة، الفرع، وقت الحساب
 *  4. قيمة "0.00" لا تعني بالضرورة "لا بيانات" — EMPTY ≠ SUCCESS(0)
 *     هذه التفرقة ضرورية لتفادي تفسير خاطئ من المحاسب
 *
 * @version 1.0.0
 */
public final class AccountingKpi {

    // ═══════════════════════════════════════════════════════════
    //  معرّفات المؤشرات — ثابتة، تُستخدم كمفاتيح
    // ═══════════════════════════════════════════════════════════
    public static final String KEY_CASH          = "cash";
    public static final String KEY_REVENUE       = "revenue";
    public static final String KEY_EXPENSES      = "expenses";
    public static final String KEY_NET_PROFIT    = "net_profit";
    public static final String KEY_RECEIVABLES   = "receivables";
    public static final String KEY_PAYABLES      = "payables";

    // ═══════════════════════════════════════════════════════════
    //  الحالات
    // ═══════════════════════════════════════════════════════════
    public static final int STATE_LOADING       = 0;
    public static final int STATE_SUCCESS       = 1;
    public static final int STATE_NOT_CONNECTED = 2;
    public static final int STATE_EMPTY         = 3;
    public static final int STATE_ERROR         = 4;

    // ═══════════════════════════════════════════════════════════
    //  الحقول
    // ═══════════════════════════════════════════════════════════
    @NonNull public final String key;
    @NonNull public final String label;
    @NonNull public final String icon;
    public final int state;

    /** القيمة المنسقة للعرض. null إن لم تكن هناك قيمة */
    @Nullable public final String displayValue;

    /** سطر تحت القيمة: العملة، الفترة، الفرع */
    @Nullable public final String caption;

    /** رسالة خطأ قابلة للعرض (عند STATE_ERROR) */
    @Nullable public final String errorMessage;

    /** القيمة الرقمية الخام (تحليل برمجي، تصدير) */
    public final double rawValue;

    /** عملة القيمة (YER, USD, ...) */
    @Nullable public final String currency;

    /** وقت الحساب (epoch millis). 0 = لم يُحسب */
    public final long computedAt;

    private AccountingKpi(@NonNull String key,
                         @NonNull String label,
                         @NonNull String icon,
                         int state,
                         @Nullable String displayValue,
                         @Nullable String caption,
                         @Nullable String errorMessage,
                         double rawValue,
                         @Nullable String currency,
                         long computedAt) {
        this.key = key;
        this.label = label;
        this.icon = icon;
        this.state = state;
        this.displayValue = displayValue;
        this.caption = caption;
        this.errorMessage = errorMessage;
        this.rawValue = rawValue;
        this.currency = currency;
        this.computedAt = computedAt;
    }

    // ═══════════════════════════════════════════════════════════
    //  مصانع الحالات
    // ═══════════════════════════════════════════════════════════

    public static AccountingKpi loading(@NonNull String key,
                                       @NonNull String label,
                                       @NonNull String icon) {
        return new AccountingKpi(key, label, icon, STATE_LOADING,
                null, null, null, 0, null, 0);
    }

    public static AccountingKpi notConnected(@NonNull String key,
                                            @NonNull String label,
                                            @NonNull String icon) {
        return new AccountingKpi(key, label, icon, STATE_NOT_CONNECTED,
                null, "قيد الربط بالخادم", null, 0, null, 0);
    }

    public static AccountingKpi success(@NonNull String key,
                                       @NonNull String label,
                                       @NonNull String icon,
                                       double value,
                                       @NonNull String formattedValue,
                                       @NonNull String currency,
                                       @Nullable String captionSuffix) {
        String cap = currency;
        if (captionSuffix != null && !captionSuffix.isEmpty()) {
            cap = currency + " · " + captionSuffix;
        }
        return new AccountingKpi(key, label, icon, STATE_SUCCESS,
                formattedValue, cap, null, value, currency,
                System.currentTimeMillis());
    }

    public static AccountingKpi empty(@NonNull String key,
                                     @NonNull String label,
                                     @NonNull String icon,
                                     @Nullable String caption) {
        return new AccountingKpi(key, label, icon, STATE_EMPTY,
                null, caption != null ? caption : "لا بيانات في الفترة",
                null, 0, null, System.currentTimeMillis());
    }

    public static AccountingKpi error(@NonNull String key,
                                     @NonNull String label,
                                     @NonNull String icon,
                                     @NonNull String errorMessage) {
        return new AccountingKpi(key, label, icon, STATE_ERROR,
                null, null, errorMessage, 0, null,
                System.currentTimeMillis());
    }

    // ═══════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════

    public boolean hasValue() {
        return state == STATE_SUCCESS;
    }

    public boolean isLoading() {
        return state == STATE_LOADING;
    }

    public boolean isError() {
        return state == STATE_ERROR;
    }

    @NonNull
    @Override
    public String toString() {
        return "KPI{" + key + ", state=" + state + ", value=" + displayValue + "}";
    }
}
