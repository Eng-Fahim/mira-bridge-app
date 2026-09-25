package com.example.uhf.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.example.uhf.manager.OfflineGateCache;
import com.example.uhf.service.MiraGateMonitorService;

/**
 * ✅ GateEventReceiver — مستقبل أحداث البوابات
 *
 * يستقبل:
 * - GATE_EVENT: حدث في البوابة
 * - GATE_ALERT: إنذار أمني
 * - GATE_SYNC: طلب مزامنة
 *
 * ثم:
 * - يحفظ الحدث في OfflineGateCache
 * - يشغل/يُنبّه MiraGateMonitorService
 */
public class GateEventReceiver extends BroadcastReceiver {

    private static final String TAG = "GateEventReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;

        try {
            String action = intent.getAction();
            Log.d(TAG, "📥 Received: " + action);

            String epc = intent.getStringExtra("epc");
            boolean allowed = intent.getBooleanExtra("allowed", false);
            String itemTitle = intent.getStringExtra("item_title");
            String gateId = intent.getStringExtra("gate_id");

            // ✅ 1) حفظ الحدث محلياً
            if (epc != null && !epc.isEmpty()) {
                OfflineGateCache cache = OfflineGateCache.getInstance(context);
                cache.saveOfflineEvent(epc, allowed, itemTitle, gateId);
                Log.d(TAG, "💾 Event saved: " + epc + " allowed=" + allowed);
            }

            // ✅ 2) إشعار الخدمة بالتحديث
            Intent serviceIntent = new Intent(context, MiraGateMonitorService.class);
            serviceIntent.putExtra("force_sync", true);
            context.startForegroundService(serviceIntent);

        } catch (Exception e) {
            Log.e(TAG, "onReceive error: " + e.getMessage(), e);
        }
    }
}
