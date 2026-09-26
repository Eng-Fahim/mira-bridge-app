package com.example.uhf.manager;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.uhf.api.MiraApiClient;
import com.example.uhf.model.CartItem;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * GateSaleBridge v1.1
 * ═══════════════════════════════════════════════════════════════
 * جسر يربط عمليات البيع/الإرجاع بنظام البوابات الأمنية (Secure Gate).
 *
 * v1.1:
 *  - إضافة "محاكاة الخروج" (وضع اختباري)
 *  - استخراج منطق المحاكاة إلى دالة مستقلة
 */
public final class GateSaleBridge {

    private static final String TAG = "GateSaleBridge";

    // ═══════════════════════════════════════════════════════════
    // Actions
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
            // استخراج EPCs الصالحة
            List<String> epcs = extractValidEpcs(items);
            if (epcs.isEmpty()) {
                Log.w(TAG, "onSaleCompleted: no valid EPCs — skip broadcast");
                return;
            }

            Log.i(TAG, "🔓 Sale completed: saleId=" + saleId
                    + ", epcs=" + epcs.size()
                    + ", branch=" + branchId
                    + ", offline=" + isOffline);

            // 1. حفظ في القائمة المسموحة (محلياً)
            addToAllowedListSafe(ctx, epcs, saleId, branchId, isOffline);

            // 2. إرسال Broadcast للبوابات (محلياً)
            sendSaleBroadcast(ctx, epcs, saleId, totalUsd, items.size(),
                    branchId, userId, isOffline);

            // 3. ✅ [جديد] محاكاة الخروج (وضع اختباري)
            simulateExitIfEnabled(ctx, epcs, saleId, branchId);

        } catch (Exception e) {
            // ⚠️ لا نُفشل البيع أبداً بسبب الجسر
            Log.e(TAG, "onSaleCompleted error (non-fatal): " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 2. بعد الإرجاع الناجح
    // ═══════════════════════════════════════════════════════════

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
    // 3. 🎬 محاكاة الخروج (وضع اختباري)
    // ═══════════════════════════════════════════════════════════

    /**
     * إذا كان "محاكاة الخروج" مفعّلاً في MiraGatesSettings،
     * يُرسل لكل EPC حدث خروج فوري للسيرفر.
     *
     * الغرض: اختبار النظام بدون قارئ RFID.
     * ⚠️ لا يستخدم في الإنتاج.
     */
    private static void simulateExitIfEnabled(Context ctx,
                                              List<String> epcs,
                                              long saleId,
                                              int branchId) {
        try {
            MiraGatesSettings settings = MiraGatesSettings.getInstance(ctx);
            if (settings == null || !settings.isSimulateExitOnSale()) {
                return; // ✅ معطّل — لا شيء يحدث
            }

            Log.i(TAG, "🎬 SIMULATE EXIT enabled — sending /gate-exit-event for "
                    + epcs.size() + " EPCs");

            for (String epc : epcs) {
                sendSimulatedExitEvent(ctx, epc, saleId, branchId);
            }
        } catch (Exception e) {
            Log.e(TAG, "simulateExitIfEnabled error: " + e.getMessage());
        }
    }

    /**
     * يُرسل حدث خروج محاكي للسيرفر — في Thread منفصل.
     */
    private static void sendSimulatedExitEvent(Context ctx, String epc,
                                                long saleId, int branchId) {
        if (epc == null || epc.isEmpty()) return;

        new Thread(() -> {
            try {
                MiraApiClient client = MiraApiClient.getInstance(ctx);
                if (client == null) {
                    Log.w(TAG, "MiraApiClient is null");
                    return;
                }

                JSONObject payload = new JSONObject();
                payload.put("epc", epc);
                payload.put("item_title", "محاكاة خروج (بيع " + saleId + ")");
                payload.put("allowed", true);
                payload.put("gate_id", "simulation_gate");
                payload.put("branch_id", branchId);
                payload.put("timestamp", System.currentTimeMillis());
                payload.put("event_type", "PASS");

                MiraApiClient.ApiResponse response =
                        client.postWithAuth("/gate-exit-event", payload);

                if (response != null && response.isSuccess) {
                    Log.d(TAG, "✅ Simulated exit sent: " + epc);
                } else {
                    Log.w(TAG, "⚠️ Simulated exit failed: " + epc
                            + " (code=" + (response != null ? response.code : -1) + ")");
                }
            } catch (Exception e) {
                Log.e(TAG, "sendSimulatedExitEvent error: " + e.getMessage());
            }
        }, "MiraSimulateExit").start();
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
