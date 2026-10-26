package com.example.uhf.utils;

import android.content.Context;

import androidx.annotation.ColorRes;
import androidx.core.content.ContextCompat;

import com.example.uhf.R;

/**
 * Accounting Theme Helper — v1.0
 *
 * يوفر ألوان theme-aware لكل شاشات المحاسبة
 * يحترم Dark Mode / Light Mode تلقائياً
 */
public final class AccountingColors {

    private AccountingColors() {}

    public enum Status {
        SUCCESS,    // أخضر (نجاح، توازن)
        WARNING,    // برتقالي (تحذير)
        ERROR,      // أحمر (خطأ، سالب)
        INFO,       // أزرق (معلومة، مدين)
        NEUTRAL     // رمادي (فارغ، محايد)
    }

    public static int text(Context ctx, Status status) {
        return ContextCompat.getColor(ctx, textRes(status));
    }

    public static int background(Context ctx, Status status) {
        return ContextCompat.getColor(ctx, bgRes(status));
    }

    @ColorRes
    private static int textRes(Status status) {
        switch (status) {
            case SUCCESS: return R.color.acct_success;
            case WARNING: return R.color.acct_warning;
            case ERROR:   return R.color.acct_credit;
            case INFO:    return R.color.acct_debit;
            case NEUTRAL:
            default:      return R.color.acct_neutral;
        }
    }

    @ColorRes
    private static int bgRes(Status status) {
        switch (status) {
            case SUCCESS: return R.color.acct_success_bg;
            case WARNING: return R.color.acct_warning_bg;
            case ERROR:   return R.color.acct_credit_bg;
            case INFO:    return R.color.acct_debit_bg;
            case NEUTRAL:
            default:      return R.color.acct_neutral_bg;
        }
    }
}
