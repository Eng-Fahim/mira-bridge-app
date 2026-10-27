package com.example.uhf.license;

import android.content.Context;
import android.util.Log;

import com.example.uhf.tools.SessionManager;

/**
 * LicenseGuard — نقطة تحقق موحدة قبل العمليات الحساسة
 *
 * الاستخدام في شاشات POS/الإرجاع/الترحيل:
 *
 *   LicenseResult r = LicenseGuard.checkForSensitiveOp(this);
 *   if (!r.allowed) {
 *       r.showDialog(activity);
 *       return;
 *   }
 *   // ... نفّذ العملية
 */
public class LicenseGuard {

    private static final String TAG = "LicenseGuard";

    public enum Decision {
        ALLOWED,        // ✅ كل شيء يعمل
        WARNING,        // ⚠️ تحذير فقط، العمليات تعمل
        RESTRICTED,     // 🟠 العمليات الحساسة ممنوعة، القراءة تعمل
        BLOCKED         // 🔴 كل شيء ممنوع
    }

    public static class Result {
        public final Decision decision;
        public final String message;
        public final int daysLeft;
        public final int graceDays;

        public Result(Decision decision, String message, int daysLeft, int graceDays) {
            this.decision = decision;
            this.message = message;
            this.daysLeft = daysLeft;
            this.graceDays = graceDays;
        }

        public boolean isAllowed() {
            return decision == Decision.ALLOWED || decision == Decision.WARNING;
        }
    }

    /**
     * فحص الحالة الحالية
     *
     * @return Result مع القرار والرسالة
     */
    public static Result check(Context context) {
        try {
            SessionManager sm = new SessionManager(context);

            // 1. لا يوجد ترخيص → ممنوع
            if (!sm.hasActivationToken()) {
                return new Result(Decision.BLOCKED,
                        "لم يتم تفعيل الترخيص بعد. يرجى التفعيل أولاً.",
                        0, 0);
            }

            // 2. حالة offline
            String status = sm.getOfflineStatus();
            int days = sm.getDaysSinceLastHeartbeat();
            int grace = sm.getOfflineGraceDays();
            int daysLeft = Math.max(0, grace - days);

            switch (status) {
                case "BLOCKED":
                    return new Result(Decision.BLOCKED,
                            "انقطع الاتصال بالخادم لأكثر من " + grace + " أيام.\n\n" +
                            "اتصل بالإنترنت لتجديد الترخيص قبل متابعة العمل.",
                            0, grace);

                case "RESTRICTED":
                    return new Result(Decision.RESTRICTED,
                            "أنت في وضع القيود — لم يتبقَّ سوى " + daysLeft + " أيام من فترة السماح.\n\n" +
                            "العمليات الحساسة (البيع، الترحيل) معطلة. اتصل بالإنترنت الآن.",
                            daysLeft, grace);

                case "WARNING":
                    return new Result(Decision.WARNING,
                            "تنبيه: أنت offline منذ " + days + " أيام. تبقّى " + daysLeft + " أيام قبل تقييد العمليات.",
                            daysLeft, grace);

                default: // ONLINE
                    return new Result(Decision.ALLOWED, "", grace, grace);
            }
        } catch (Exception e) {
            Log.e(TAG, "check error: " + e.getMessage(), e);
            // في حالة الخطأ، نسمح (لا نُعطل المستخدم بسبب bug)
            return new Result(Decision.ALLOWED, "", 0, 0);
        }
    }

    /**
     * فحص سريع للعمليات الحساسة (بيع، ترحيل، تراخيص مستخدمين)
     */
    public static boolean canPerformSensitiveOperation(Context context) {
        Result r = check(context);
        return r.isAllowed();
    }

    /**
     * هل يجب منع أي عملية كتابة؟ (BLOCKED = true)
     */
    public static boolean isWriteLocked(Context context) {
        Result r = check(context);
        return r.decision == Decision.BLOCKED;
    }
}
