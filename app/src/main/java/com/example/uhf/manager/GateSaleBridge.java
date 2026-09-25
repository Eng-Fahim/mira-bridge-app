package com.example.uhf.manager;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.uhf.model.CartItem;

import java.util.ArrayList;
import java.util.List;

/**
 * GateSaleBridge v1.0
 * ═══════════════════════════════════════════════════════════════
 * جسر يربط عمليات البيع/الإرجاع بنظام البوابات الأمنية (Secure Gate).
 *
 * المسؤوليات:
 *  1. حفظ EPCs القطع المباعة في القائمة المسموحة (OfflineGateCache)
 *  2. إرسال Broadcast لتطبيق البوابات (MiraGateMonitorService)
 *  3. تسجيل عملية البيع لأغراض التقارير
 *
 * المبادئ:
 *  - Best-effort: فشل الجسر لا يؤثر أبداً على عملية البيع
 *  - Thread-safe: يُستدعى من أي خيط
 *  - Non-blocking: كل العمليات سريعة (cache + broadcast)
 *
 * @author MIRA Enterprise
 * @version 1.0
 */
public final class GateSaleBridge {

    private static final String TAG = "GateSaleBridge";

    // ═══════════════════════════════════════════════════════════
    // Actions — يقرأها GateEventReceiver / MiraGateMonitorService
    // ═══════════════════════════════════════════════════════════
    public static final String ACTION_SALE_COMPLETED =
            "com.mira.gate.SALE_COMPLETED";
    public static final String ACTION_REFUND_COMPLETED =
            "com.mira.gate.REFUND_COMPLETED";

    // ═══════════════════════════════════════════════════════════
    // Extras
    // ═══════════════════════════════════════════════════════════
    public static final String EXTRA_SALE_ID        = "sale_id";
    public static final String EXTRA_EPCS           = "epcs";
    public static final String EXTRA_BRANCH_ID      = "branch_id";
    public static final String EXTRA_USER_ID        = "user_id";
    public static final String EXTRA_TOTAL_USD      = "total_usd";
    public static final String EXTRA_ITEM_COUNT     = "item_count";
    public static final String EXTRA_TIMESTAMP      = "timestamp";
    public static final String EXTRA_IS_OFFLINE     = "is_offline";
    public static final String EXTRA_REASON         = "reason";

    private GateSaleBridge() { /* no instances */ }

    // ═══════════════════════════════════════════════════════════
    // 1. بعد البيع الناجح
    // ═══════════════════════════════════════════════════════════

    /**
     * يُستدعى بعد نجاح البيع (أونلاين أو أوفلاين).
     *
     * @param ctx        سياق التطبيق
     * @param items      قائمة القطع المباعة (CartItems)
     * @param saleId     معرف البيع من السيرفر (0 للأوفلاين)
     * @param totalUsd   الإجمالي بالدولار
     * @param branchId   الفرع النشط
     * @param userId     معرف المستخدم
     * @param isOffline  هل البيع أوفلاين؟
     */
    public static void onSaleCompleted(@NonNull Context ctx,
                                       @Nullable List<CartItem> items,
                                       long saleId,
                                       double totalUsd,
                                       int branchId,
                                       int userId,
                                       boolean isOffline) {
        if (items == null || items.isEmpty()) {
            Log.w(TAG, "onSaleCompleted: empty items — skip");
            return;
        }

        try {
            // استخراج EPCs الصالحة (تجاهل الفارغة)
            List<String> epcs = extractValidEpcs(items);
            if (epcs.isEmpty()) {
                Log.w(TAG, "onSaleCompleted: no valid EPCs — skip broadcast");
                return;
            }

            Log.i(TAG, "🔓 Sale completed: saleId=" + saleId
                    + ", epcs=" + epcs.size()
                    + ", branch=" + branchId
                    + ", offline=" + isOffline);

            // 1. حفظ في القائمة المسموحة
            addToAllowedListSafe(ctx, epcs, saleId, branchId, isOffline);

            // 2. إرسال Broadcast للبوابات
            sendSaleBroadcast(ctx, epcs, saleId, totalUsd, items.size(),
                    branchId, userId, isOffline);

        } catch (Exception e) {
            // ⚠️ لا نُفشل البيع أبداً بسبب الجسر
            Log.e(TAG, "onSaleCompleted error (non-fatal): " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 2. بعد الإرجاع الناجح
    // ═══════════════════════════════════════════════════════════

    /**
     * يُستدعى بعد نجاح الإرجاع.
     * يزيل EPCs من القائمة المسموحة (لأنها عادت للمخزون).
     */
    public static void onRefundCompleted(@NonNull Context ctx,
                                         @Nullable String epc,
                                         @Nullable String serialNo,
                                         long saleId,
                                         int branchId,
                                         int userId,
                                         @Nullable String reason) {
        try {
            List<String> ids = new ArrayList<>(2);
            if (epc != null && !epc.trim().isEmpty()) ids.add(epc.trim());
            if (serialNo != null && !serialNo.trim().isEmpty()) ids.add(serialNo.trim());

            if (ids.isEmpty()) {
                Log.w(TAG, "onRefundCompleted: no ids — skip");
                return;
            }

            Log.i(TAG, "🔒 Refund completed: ids=" + ids.size()
                    + ", saleId=" + saleId + ", branch=" + branchId);

            // 1. إزالة من القائمة المسموحة
            removeFromAllowedListSafe(ctx, ids);

            // 2. إرسال Broadcast
            sendRefundBroadcast(ctx, ids, saleId, branchId, userId, reason);

        } catch (Exception e) {
            Log.e(TAG, "onRefundCompleted error (non-fatal): " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Helpers — Cache
    // ═══════════════════════════════════════════════════════════

    private static void addToAllowedListSafe(Context ctx, List<String> epcs,
                                             long saleId, int branchId,
                                             boolean isOffline) {
        try {
            OfflineGateCache cache = OfflineGateCache.getInstance(ctx);
            if (cache == null) {
                Log.w(TAG, "OfflineGateCache is null — skip cache");
                return;
            }
            for (String epc : epcs) {
                cache.addToAllowedList(epc, saleId, branchId, isOffline);
            }
            Log.d(TAG, "✅ Added " + epcs.size() + " EPCs to allowed list");
        } catch (Exception e) {
            Log.w(TAG, "addToAllowedListSafe error: " + e.getMessage());
        }
    }

    private static void removeFromAllowedListSafe(Context ctx, List<String> ids) {
        try {
            OfflineGateCache cache = OfflineGateCache.getInstance(ctx);
            if (cache == null) return;
            for (String id : ids) {
                cache.removeFromAllowedList(id);
            }
            Log.d(TAG, "✅ Removed " + ids.size() + " ids from allowed list");
        } catch (Exception e) {
            Log.w(TAG, "removeFromAllowedListSafe error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Helpers — Broadcast
    // ═══════════════════════════════════════════════════════════

    private static void sendSaleBroadcast(Context ctx, List<String> epcs,
                                          long saleId, double totalUsd,
                                          int itemCount, int branchId,
                                          int userId, boolean isOffline) {
        try {
            Intent intent = new Intent(ACTION_SALE_COMPLETED);
            intent.setPackage(ctx.getPackageName());
            intent.putStringArrayListExtra(EXTRA_EPCS, new ArrayList<>(epcs));
            intent.putExtra(EXTRA_SALE_ID, saleId);
            intent.putExtra(EXTRA_TOTAL_USD, totalUsd);
            intent.putExtra(EXTRA_ITEM_COUNT, itemCount);
            intent.putExtra(EXTRA_BRANCH_ID, branchId);
            intent.putExtra(EXTRA_USER_ID, userId);
            intent.putExtra(EXTRA_IS_OFFLINE, isOffline);
            intent.putExtra(EXTRA_TIMESTAMP, System.currentTimeMillis());
            ctx.sendBroadcast(intent);

            Log.d(TAG, "📡 Broadcast sent: " + ACTION_SALE_COMPLETED);
        } catch (Exception e) {
            Log.w(TAG, "sendSaleBroadcast error: " + e.getMessage());
        }
    }

    private static void sendRefundBroadcast(Context ctx, List<String> ids,
                                            long saleId, int branchId,
                                            int userId, String reason) {
        try {
            Intent intent = new Intent(ACTION_REFUND_COMPLETED);
            intent.setPackage(ctx.getPackageName());
            intent.putStringArrayListExtra(EXTRA_EPCS, new ArrayList<>(ids));
            intent.putExtra(EXTRA_SALE_ID, saleId);
            intent.putExtra(EXTRA_BRANCH_ID, branchId);
            intent.putExtra(EXTRA_USER_ID, userId);
            intent.putExtra(EXTRA_REASON, reason != null ? reason : "");
            intent.putExtra(EXTRA_TIMESTAMP, System.currentTimeMillis());
            ctx.sendBroadcast(intent);

            Log.d(TAG, "📡 Broadcast sent: " + ACTION_REFUND_COMPLETED);
        } catch (Exception e) {
            Log.w(TAG, "sendRefundBroadcast error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Helpers — Extract EPCs
    // ═══════════════════════════════════════════════════════════

    /**
     * يستخرج EPCs صالحة من قائمة CartItem.
     * الأولوية: rfidEpc → epc → serialNo (fallback)
     */
    private static List<String> extractValidEpcs(List<CartItem> items) {
        List<String> result = new ArrayList<>(items.size());
        for (CartItem item : items) {
            if (item == null) continue;

            String epc = null;
            if (item.rfidEpc != null && !item.rfidEpc.trim().isEmpty()) {
                epc = item.rfidEpc.trim();
            } else if (item.epc != null && !item.epc.trim().isEmpty()) {
                epc = item.epc.trim();
            } else if (item.serialNo != null && !item.serialNo.trim().isEmpty()) {
                epc = item.serialNo.trim();
            }

            if (epc != null && !result.contains(epc)) {
                result.add(epc);
            }
        }
        return result;
    }
}
