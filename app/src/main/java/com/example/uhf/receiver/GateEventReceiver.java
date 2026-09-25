package com.example.uhf.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.example.uhf.manager.GateSaleBridge;
import com.example.uhf.manager.OfflineGateCache;
import com.example.uhf.service.MiraGateMonitorService;

import org.json.JSONObject;

/**
 * GateEventReceiver v2.0
 * ═══════════════════════════════════════════════════════════════
 * مستقبل أحداث البوابات + أحداث البيع/الإرجاع.
 *
 * Actions المدعومة:
 *  - GATE_EVENT: حدث في البوابة (خروج قطعة)
 *  - GATE_ALERT: إنذار أمني
 *  - GATE_SYNC: طلب مزامنة يدوية
 *  - com.mira.gate.SALE_COMPLETED (جديد)
 *  - com.mira.gate.REFUND_COMPLETED (جديد)
 *
 * المبادئ:
 *  - Receiver سريع جداً (لا عمليات ثقيلة)
 *  - أي عملية شبكية → تُوكل للـ Service
 *  - Fail-safe: أي خطأ لا يُسقط التطبيق
 */
public class GateEventReceiver extends BroadcastReceiver {

    private static final String TAG = "GateEventReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;

        try {
            String action = intent.getAction();
            if (action == null) return;

            Log.d(TAG, "📥 Received: " + action);

            switch (action) {

                // ═══════════════════════════════════════════════
                // 1) حدث في البوابة (خروج/إنذار)
                // ═══════════════════════════════════════════════
                case "com.example.uhf.GATE_EVENT":
                case "com.example.uhf.GATE_ALERT":
                case "com.example.uhf.GATE_SYNC":
                    handleGateEvent(context, intent);
                    break;

                // ═══════════════════════════════════════════════
                // 2) بيع مكتمل (جديد v2.0)
                // ═══════════════════════════════════════════════
                case GateSaleBridge.ACTION_SALE_COMPLETED:
                    handleSaleCompleted(context, intent);
                    break;

                // ═══════════════════════════════════════════════
                // 3) إرجاع مكتمل (جديد v2.0)
                // ═══════════════════════════════════════════════
                case GateSaleBridge.ACTION_REFUND_COMPLETED:
                    handleRefundCompleted(context, intent);
                    break;

                default:
                    Log.d(TAG, "⚠️ Unknown action: " + action);
            }

        } catch (Exception e) {
            Log.e(TAG, "onReceive error: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 1) معالجة أحداث البوابة (كما كان سابقاً + تحسينات)
    // ═══════════════════════════════════════════════════════════

    private void handleGateEvent(Context context, Intent intent) {
        String epc = intent.getStringExtra("epc");
        boolean allowed = intent.getBooleanExtra("allowed", false);
        String itemTitle = intent.getStringExtra("item_title");
        String gateId = intent.getStringExtra("gate_id");

        // ✅ 1) حفظ الحدث محلياً
        if (epc != null && !epc.isEmpty()) {
            try {
                OfflineGateCache cache = OfflineGateCache.getInstance(context);
                cache.saveOfflineEvent(epc, allowed, itemTitle, gateId);
                Log.d(TAG, "💾 Event saved: " + epc + " allowed=" + allowed);
            } catch (Exception e) {
                Log.e(TAG, "saveOfflineEvent error: " + e.getMessage());
            }
        }

        // ✅ 2) تنبيه الخدمة (مزامنة اختيارية)
        notifyMonitorService(context, "GATE_EVENT", allowed);
    }

    // ═══════════════════════════════════════════════════════════
    // 2) معالجة البيع المكتمل
    // ═══════════════════════════════════════════════════════════

    /**
     * ملاحظة مهمة:
     * GateSaleBridge.onSaleCompleted() نفسها تقوم بـ:
     *   1. إضافة EPCs إلى OfflineGateCache.addToAllowedList()
     *   2. إرسال هذا البث
     *
     * هنا فقط نسجل إحصائية + ننبّه الخدمة.
     * لا نضيف EPCs مرة أخرى (تجنباً للتكرار).
     */
    private void handleSaleCompleted(Context context, Intent intent) {
        try {
            java.util.ArrayList<String> epcs =
                    intent.getStringArrayListExtra(GateSaleBridge.EXTRA_EPCS);
            long saleId = intent.getLongExtra(GateSaleBridge.EXTRA_SALE_ID, 0L);
            int branchId = intent.getIntExtra(GateSaleBridge.EXTRA_BRANCH_ID, 0);
            boolean isOffline = intent.getBooleanExtra(
                    GateSaleBridge.EXTRA_IS_OFFLINE, false);

            int epcCount = (epcs != null) ? epcs.size() : 0;
            Log.i(TAG, "🛒 Sale received: saleId=" + saleId
                    + ", epcs=" + epcCount
                    + ", branch=" + branchId
                    + ", offline=" + isOffline);

            // ✅ تسجيل حدث إحصائي محلي (اختياري)
            try {
                OfflineGateCache cache = OfflineGateCache.getInstance(context);
                cache.saveOfflineEvent(
                        "__SALE__",
                        true,
                        "sale_id=" + saleId + " (epcs=" + epcCount + ")",
                        String.valueOf(branchId)
                );
            } catch (Exception e) {
                Log.w(TAG, "saveOfflineEvent (sale) error: " + e.getMessage());
            }

            // ✅ تنبيه الخدمة (لتحديث الإشعار مثلاً)
            notifyMonitorService(context, "SALE_COMPLETED", true);

        } catch (Exception e) {
            Log.e(TAG, "handleSaleCompleted error: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 3) معالجة الإرجاع المكتمل
    // ═══════════════════════════════════════════════════════════

    private void handleRefundCompleted(Context context, Intent intent) {
        try {
            java.util.ArrayList<String> ids =
                    intent.getStringArrayListExtra(GateSaleBridge.EXTRA_EPCS);
            long saleId = intent.getLongExtra(GateSaleBridge.EXTRA_SALE_ID, 0L);
            int branchId = intent.getIntExtra(GateSaleBridge.EXTRA_BRANCH_ID, 0);
            String reason = intent.getStringExtra(GateSaleBridge.EXTRA_REASON);

            int idCount = (ids != null) ? ids.size() : 0;
            Log.i(TAG, "↩️ Refund received: saleId=" + saleId
                    + ", ids=" + idCount
                    + ", branch=" + branchId
                    + ", reason=" + reason);

            // ✅ تسجيل حدث إحصائي
            try {
                OfflineGateCache cache = OfflineGateCache.getInstance(context);
                cache.saveOfflineEvent(
                        "__REFUND__",
                        false,
                        "sale_id=" + saleId + " (ids=" + idCount + ")",
                        String.valueOf(branchId)
                );
            } catch (Exception e) {
                Log.w(TAG, "saveOfflineEvent (refund) error: " + e.getMessage());
            }

            // ✅ تنبيه الخدمة
            notifyMonitorService(context, "REFUND_COMPLETED", false);

        } catch (Exception e) {
            Log.e(TAG, "handleRefundCompleted error: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Helper — إشعار الخدمة
    // ═══════════════════════════════════════════════════════════

    private void notifyMonitorService(Context context, String eventType,
                                       boolean allowed) {
        try {
            Intent serviceIntent = new Intent(context, MiraGateMonitorService.class);
            serviceIntent.putExtra(MiraGateMonitorService.EXTRA_EVENT_TYPE, eventType);
            serviceIntent.putExtra(MiraGateMonitorService.EXTRA_FORCE_SYNC, false);
            serviceIntent.putExtra(MiraGateMonitorService.EXTRA_ALLOWED, allowed);

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.w(TAG, "notifyMonitorService error: " + e.getMessage());
        }
    }
}
