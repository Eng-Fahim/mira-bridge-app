package com.example.uhf.manager;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.uhf.BuildConfig;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.model.CartItem;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  GateSaleBridge v2.0 — SECURED
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * إصلاحات v2.0:
 *   ✅ [CRITICAL] استخراج EPC + TID (كلاهما) للتخزين المحلي
 *      → offline mode يعمل حتى لو القارئ قرأ TID (Item 26/27)
 *   ✅ [CRITICAL] simulateExitIfEnabled معطّل في الإنتاج
 *      → لا خروج كاذب من builds الإنتاج
 *   ✅ [SAFE] التطبيع (uppercase) عند الإضافة
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
            // ✅ [v2.0] استخراج كل المعرّفات (EPC + TID) للكاش
            List<String> allIdentifiers = extractAllIdentifiers(items);
            
            // ✅ EPCs فقط للـ broadcast (توافق مع المستمعين)
            List<String> epcsOnly = extractValidEpcs(items);

            if (allIdentifiers.isEmpty()) {
                Log.w(TAG, "onSaleCompleted: no valid identifiers — skip");
                return;
            }

            Log.i(TAG, "🔓 Sale completed: saleId=" + saleId
                    + ", identifiers=" + allIdentifiers.size()
                    + ", epcs=" + epcsOnly.size()
                    + ", branch=" + branchId
                    + ", offline=" + isOffline);

            // 1. حفظ في القائمة المسموحة (EPC + TID)
            addToAllowedListSafe(ctx, allIdentifiers, saleId, branchId, isOffline);

            // 2. إرسال Broadcast (EPC فقط)
            sendSaleBroadcast(ctx, epcsOnly, saleId, totalUsd, items.size(),
                    branchId, userId, isOffline);

            // 3. ✅ [v2.0] محاكاة الخروج (فقط في builds التطوير)
            simulateExitIfEnabled(ctx, epcsOnly, saleId, branchId);

        } catch (Exception e) {
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
    // 3. 🎬 محاكاة الخروج (وضع اختباري فقط)
    // ═══════════════════════════════════════════════════════════

    /**
     * ✅ [v2.0 CRITICAL] معطّل تماماً في builds الإنتاج.
     * 
     * قبل v2.0: كان يعمل بدون فحص BuildConfig → خطر خروج كاذب.
     * بعد v2.0: لا يعمل إلا إذا كان BuildConfig.ALLOW_SIMULATOR = true
     *           (وهو false في release).
     */
    private static void simulateExitIfEnabled(Context ctx,
                                              List<String> epcs,
                                              long saleId,
                                              int branchId) {
        try {
            // ✅ [v2.0] فحص صارم — لا محاكاة في الإنتاج
            if (!BuildConfig.ALLOW_SIMULATOR) {
                Log.d(TAG, "⏭️ Simulation disabled (production build)");
                return;
            }

            MiraGatesSettings settings = MiraGatesSettings.getInstance(ctx);
            if (settings == null || !settings.isSimulateExitOnSale()) {
                return; // معطّل من المستخدم
            }

            Log.w(TAG, "🎬 SIMULATE EXIT enabled — " + epcs.size() + " EPCs");
            Log.w(TAG, "🎬 DEV BUILD: items will be marked EXITED without gate scan");

            for (String epc : epcs) {
                sendSimulatedExitEvent(ctx, epc, saleId, branchId);
            }
        } catch (Exception e) {
            Log.e(TAG, "simulateExitIfEnabled error: " + e.getMessage());
        }
    }

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

    private static void addToAllowedListSafe(Context ctx, List<String> identifiers,
                                             long saleId, int branchId,
                                             boolean isOffline) {
        try {
            OfflineGateCache cache = OfflineGateCache.getInstance(ctx);
            if (cache == null) {
                Log.w(TAG, "OfflineGateCache is null — skip cache");
                return;
            }
            for (String id : identifiers) {
                cache.addToAllowedList(id, saleId, branchId, isOffline);
            }
            Log.d(TAG, "✅ Added " + identifiers.size() + " identifiers to allowed list");
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
    // Helpers — Extract Identifiers
    // ═══════════════════════════════════════════════════════════

    /**
     * ✅ [v2.0] يستخرج كل المعرّفات الفريدة (EPC + TID).
     * مهم للـ offline cache: القارئ قد يقرأ EPC أو TID.
     */
    private static List<String> extractAllIdentifiers(List<CartItem> items) {
        List<String> result = new ArrayList<>(items.size() * 2);
        for (CartItem item : items) {
            if (item == null) continue;

            // EPC
            if (item.rfidEpc != null && !item.rfidEpc.trim().isEmpty()) {
                String epc = item.rfidEpc.trim().toUpperCase();
                if (!result.contains(epc)) result.add(epc);
            } else if (item.epc != null && !item.epc.trim().isEmpty()) {
                String epc = item.epc.trim().toUpperCase();
                if (!result.contains(epc)) result.add(epc);
            }

            // ✅ TID (قد يختلف عن EPC — Items 26/27)
            if (item.tid != null && !item.tid.trim().isEmpty()) {
                String tid = item.tid.trim().toUpperCase();
                if (!result.contains(tid)) result.add(tid);
            }
        }
        return result;
    }

    /**
     * ✅ EPCs فقط — للـ broadcast (توافق مع المستمعين).
     */
    private static List<String> extractValidEpcs(List<CartItem> items) {
        List<String> result = new ArrayList<>(items.size());
        for (CartItem item : items) {
            if (item == null) continue;

            String epc = null;
            if (item.rfidEpc != null && !item.rfidEpc.trim().isEmpty()) {
                epc = item.rfidEpc.trim().toUpperCase();
            } else if (item.epc != null && !item.epc.trim().isEmpty()) {
                epc = item.epc.trim().toUpperCase();
            } else if (item.serialNo != null && !item.serialNo.trim().isEmpty()) {
                epc = item.serialNo.trim().toUpperCase();
            }

            if (epc != null && !result.contains(epc)) {
                result.add(epc);
            }
        }
        return result;
    }
}
